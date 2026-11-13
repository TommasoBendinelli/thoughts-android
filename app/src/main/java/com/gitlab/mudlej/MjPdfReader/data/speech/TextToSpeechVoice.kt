// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

/**
 * Android's text-to-speech as the fallback voice, preferring the best installed English voice.
 * Pausing stops the current chunk; resuming speaks that chunk again from its start. Main thread only.
 */
class TextToSpeechVoice(
    context: Context,
    private val log: (String) -> Unit = {},
) : SystemVoice {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val utterances = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private var paused = false
    private var resumed = CompletableDeferred<Unit>()

    override suspend fun speak(text: String) {
        val engine = engine()
        while (true) {
            if (paused) resumed.await()
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Boolean>()
            utterances[id] = done
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) {
                utterances.remove(id)
                throw IOException("The system voice could not speak")
            }
            val completed = try {
                done.await()
            } finally {
                utterances.remove(id)
            }
            // Interrupted by a pause: say this chunk again once resumed.
            if (completed || !paused) return
        }
    }

    fun pause() {
        if (paused) return
        paused = true
        resumed = CompletableDeferred()
        tts?.stop()
    }

    fun resume() {
        if (!paused) return
        paused = false
        resumed.complete(Unit)
    }

    fun release() {
        utterances.values.forEach { it.cancel() }
        utterances.clear()
        resumed.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready?.cancel()
        ready = null
    }

    private suspend fun engine(): TextToSpeech {
        val existing = tts
        val readiness = ready ?: CompletableDeferred<Boolean>().also { deferred ->
            ready = deferred
            tts = TextToSpeech(appContext) { status ->
                mainHandler.post { deferred.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        if (!readiness.await()) throw IOException("No system voice is available")
        val engine = tts ?: throw IOException("The system voice was released")
        if (existing == null) configure(engine)
        return engine
    }

    private fun configure(engine: TextToSpeech) {
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val voice = runCatching { bestEnglishVoice(engine.voices.orEmpty()) }.getOrNull()
        if (voice != null) {
            engine.voice = voice
            log("system voice ${voice.name} quality=${voice.quality} network=${voice.isNetworkConnectionRequired}")
        } else {
            engine.language = Locale.US
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finish(utteranceId, completed = true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, completed = true)

            override fun onError(utteranceId: String?, errorCode: Int) {
                log("system voice error $errorCode")
                finish(utteranceId, completed = true)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) =
                finish(utteranceId, completed = false)
        })
    }

    private fun finish(utteranceId: String?, completed: Boolean) {
        mainHandler.post { utterances[utteranceId]?.complete(completed) }
    }

    companion object {
        /** Installed English voices first by local over network, then quality, then the device's region. */
        internal fun bestEnglishVoice(voices: Collection<Voice>): Voice? {
            val region = Locale.getDefault().country
            return voices
                .filter { it.locale.language == "en" }
                .filterNot { voice ->
                    voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
                }
                .maxWithOrNull(
                    compareBy<Voice> { !it.isNetworkConnectionRequired }
                        .thenBy { it.quality }
                        .thenBy { it.locale.country == region }
                        .thenBy { it.locale.country == "US" },
                )
        }
    }
}
