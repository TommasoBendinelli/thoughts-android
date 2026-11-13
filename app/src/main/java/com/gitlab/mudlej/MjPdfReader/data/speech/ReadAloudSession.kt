// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the reader's Read aloud bar and notification show. */
data class ReadAloudState(
    val active: Boolean = false,
    /** Waiting for the first audio (or for the system voice to start). */
    val loading: Boolean = false,
    val paused: Boolean = false,
    /** No key, or the cloud voice failed, and the device's text-to-speech reads instead. */
    val systemVoice: Boolean = false,
)

sealed interface ReadAloudEvent {
    /** OpenRouter gave no audio; the system voice took over. */
    data class CloudVoiceUnavailable(val reason: SpeechFailureReason?) : ReadAloudEvent

    /** No OpenRouter key is saved, so the system voice reads. */
    data object NoCloudKey : ReadAloudEvent
}

/** Which voice a reading ended up with; Settings' Test voice reports it. */
sealed interface ReadAloudOutcome {
    data class CloudVoice(val model: String?) : ReadAloudOutcome

    /** [failure] is null when no key is saved or the audio could not be played. */
    data class SystemVoice(val failure: SpeechUnavailableException?, val noKey: Boolean) : ReadAloudOutcome
}

/**
 * The one Read aloud playback of the app: started from a reader's selection, it keeps playing while
 * the screen is off (through [ReadAloudService]) and stops when its reader closes or another Read
 * aloud starts. It holds audio focus and pauses when focus is lost or headphones are unplugged.
 * Main thread only. Logs sizes, timings and states under [TAG], never the text.
 */
class ReadAloudSession private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val keyStore = OpenRouterKeyStore(appContext)
    private val cacheDirectory = File(appContext.cacheDir, "read-aloud")
    private val audioCache = DiskSpeechAudioCache(File(appContext.cacheDir, "read-aloud-audio"))

    private val mutableState = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<ReadAloudEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ReadAloudEvent> = mutableEvents.asSharedFlow()

    private var generation = 0
    private var job: Job? = null
    private var player: MediaPlayerChunkPlayer? = null
    private var voice: TextToSpeechVoice? = null
    private var owner: Int? = null
    private var hasFocus = false
    private var pausedByFocusLoss = false
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post { onFocusChange(change) }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "headphones unplugged; pausing")
                pause()
            }
        }
    }

    /**
     * Reads [text] aloud, replacing any reading in progress. [owner] is the reader that started
     * it; [stopIfOwnedBy] with the same object ends it. Returns false when there is nothing to read
     * or audio focus was refused.
     *
     * The cloud voice is OpenRouter, called from the phone with the key saved in Settings; without
     * a key, or when it fails, the system voice reads. [readCache] false asks OpenRouter even for
     * text read before, and [onOutcome] hears once which voice is reading (Test voice).
     */
    fun start(
        text: String,
        owner: Any,
        readCache: Boolean = true,
        onOutcome: ((ReadAloudOutcome) -> Unit)? = null,
    ): Boolean {
        stop()
        val prepared = ReadAloudText.prepare(text)
        val chunks = ReadAloudText.chunks(prepared)
        if (chunks.isEmpty()) return false
        if (!requestFocus()) {
            Log.w(TAG, "audio focus refused")
            return false
        }
        val session = generation
        this.owner = System.identityHashCode(owner)
        cacheDirectory.listFiles()?.forEach { it.delete() }
        val log: (String) -> Unit = { Log.i(TAG, it) }
        val player = MediaPlayerChunkPlayer(appContext, cacheDirectory, log).also { this.player = it }
        val voice = TextToSpeechVoice(appContext, log).also { this.voice = it }
        mutableState.value = ReadAloudState(active = true, loading = true)
        registerNoisyReceiver()
        acquireWakeLock()
        ReadAloudService.start(appContext)

        val started = SystemClock.elapsedRealtime()
        Log.i(TAG, "start chars=${prepared.length} chunks=${chunks.size} first=${chunks.first().length}")
        var outcomeReported = false
        fun report(outcome: ReadAloudOutcome) {
            if (outcomeReported) return
            outcomeReported = true
            onOutcome?.invoke(outcome)
        }
        // Decrypted once per reading, on the first request's thread, and never logged.
        val apiKey by lazy { keyStore.key() }
        val client = if (keyStore.hasKey()) {
            OpenRouterSpeechClient({ apiKey }, audioCache, readCache = readCache, log = log)
        } else {
            null
        }
        val engine = ReadAloudEngine(
            client,
            player,
            voice,
            Dispatchers.IO,
            object : ReadAloudListener {
                override fun onChunkStarted(index: Int, count: Int, cloud: Boolean) {
                    if (session != generation) return
                    if (index == 0) Log.i(TAG, "first audio after ${SystemClock.elapsedRealtime() - started} ms")
                    mutableState.value = mutableState.value.copy(loading = false, systemVoice = !cloud)
                    if (cloud) report(ReadAloudOutcome.CloudVoice(client?.lastModel))
                }

                override fun onCloudUnavailable(index: Int, cause: SpeechUnavailableException?) {
                    if (session != generation) return
                    Log.w(
                        TAG,
                        "cloud voice unavailable at chunk ${index + 1}/${chunks.size} " +
                            "(${cause?.reason ?: "unplayable"} ${cause?.statusCode ?: ""}); using the system voice",
                    )
                    mutableState.value = mutableState.value.copy(systemVoice = true)
                    mutableEvents.tryEmit(ReadAloudEvent.CloudVoiceUnavailable(cause?.reason))
                    report(ReadAloudOutcome.SystemVoice(cause, noKey = false))
                }

                override fun onNoCloudKey() {
                    if (session != generation) return
                    Log.i(TAG, "no OpenRouter key; using the system voice")
                    mutableState.value = mutableState.value.copy(systemVoice = true)
                    mutableEvents.tryEmit(ReadAloudEvent.NoCloudKey)
                    report(ReadAloudOutcome.SystemVoice(null, noKey = true))
                }
            },
        )
        job = scope.launch {
            try {
                engine.read(chunks)
                Log.i(TAG, "finished after ${SystemClock.elapsedRealtime() - started} ms")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "stopped by ${error.javaClass.simpleName}")
            } finally {
                if (session == generation) teardown()
            }
        }
        return true
    }

    fun pause() {
        if (!mutableState.value.active || mutableState.value.paused) return
        pausedByFocusLoss = false
        player?.pause()
        voice?.pause()
        releaseWakeLock()
        mutableState.value = mutableState.value.copy(paused = true)
    }

    fun resume() {
        if (!mutableState.value.active || !mutableState.value.paused) return
        if (!hasFocus && !requestFocus()) return
        pausedByFocusLoss = false
        acquireWakeLock()
        player?.resume()
        voice?.resume()
        mutableState.value = mutableState.value.copy(paused = false)
    }

    fun togglePause() {
        if (mutableState.value.paused) resume() else pause()
    }

    fun stop() {
        if (job == null && !mutableState.value.active) return
        Log.i(TAG, "stop")
        job?.cancel()
        teardown()
    }

    /** Stops the reading [owner] started, when it is still the one playing. */
    fun stopIfOwnedBy(owner: Any) {
        if (this.owner == System.identityHashCode(owner)) stop()
    }

    private fun teardown() {
        generation++
        job = null
        owner = null
        player?.release()
        player = null
        voice?.release()
        voice = null
        abandonFocus()
        unregisterNoisyReceiver()
        releaseWakeLock()
        pausedByFocusLoss = false
        // ReadAloudService sees this and leaves the foreground by itself.
        mutableState.value = ReadAloudState()
        cacheDirectory.listFiles()?.forEach { it.delete() }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // Speech is hard to follow ducked, so it pauses for notifications too.
                val wasPlaying = mutableState.value.active && !mutableState.value.paused
                pause()
                pausedByFocusLoss = wasPlaying
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (pausedByFocusLoss) resume()
            }
        }
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(manager::abandonAudioFocusRequest)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
        hasFocus = false
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    /** Keeps the CPU up between chunks (fetching, system voice) while the screen is off. */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: appContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Thoughts:ReadAloud")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
            ?: return
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    }

    companion object {
        const val TAG = "ThoughtsReadAloud"
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

        @Volatile
        private var instance: ReadAloudSession? = null

        fun get(context: Context): ReadAloudSession =
            instance ?: synchronized(this) {
                instance ?: ReadAloudSession(context).also { instance = it }
            }
    }
}
