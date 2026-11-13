// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

/** Plays MP3 chunks back to back, in the order they are queued. */
interface ChunkAudioPlayer {
    /**
     * Queues [audio] after everything already queued and suspends until it starts playing.
     * Throws when the audio cannot be played.
     */
    suspend fun enqueueAndAwaitStart(audio: ByteArray)

    /** Suspends until everything queued has finished playing. */
    suspend fun awaitIdle()
}

/** The device's own text-to-speech, used when the cloud voice is unavailable. */
interface SystemVoice {
    /** Speaks [text] and suspends until it has been spoken. Throws when there is no system voice. */
    suspend fun speak(text: String)
}

interface ReadAloudListener {
    /** Chunk [index] of [count] started; [cloud] is false when the system voice reads it. */
    fun onChunkStarted(index: Int, count: Int, cloud: Boolean) {}

    /**
     * The cloud voice failed at chunk [index] for [cause] (null when the audio could not be
     * played); it and every later chunk use the system voice.
     */
    fun onCloudUnavailable(index: Int, cause: SpeechUnavailableException?) {}

    /** No OpenRouter key is saved: everything is read by the system voice. */
    fun onNoCloudKey() {}
}

/**
 * Reads chunks aloud with the cloud voice: chunk 0 is fetched first, then each next chunk is
 * fetched while the previous one plays, so at most one request is in flight and one chunk waits
 * ready behind the one playing. The first failed request switches the rest to [systemVoice], and
 * without a [synthesizer] (no OpenRouter key) the system voice reads everything.
 */
class ReadAloudEngine(
    private val synthesizer: SpeechSynthesizer?,
    private val player: ChunkAudioPlayer,
    private val systemVoice: SystemVoice,
    private val ioDispatcher: CoroutineDispatcher,
    private val listener: ReadAloudListener = object : ReadAloudListener {},
) {
    suspend fun read(chunks: List<String>) = coroutineScope {
        if (chunks.isEmpty()) return@coroutineScope
        if (synthesizer == null) {
            listener.onNoCloudKey()
            readWithSystemVoice(chunks, 0)
            return@coroutineScope
        }
        var pending: Deferred<Fetched> = async { fetch(synthesizer, chunks[0]) }
        var index = 0
        var failure: SpeechUnavailableException? = null
        while (index < chunks.size) {
            val fetched = pending.await()
            val audio = fetched.audio
            if (audio == null) {
                failure = fetched.failure
                break
            }
            try {
                player.enqueueAndAwaitStart(audio)
            } catch (error: CancellationException) {
                throw error
            } catch (unplayable: Exception) {
                break
            }
            listener.onChunkStarted(index, chunks.size, cloud = true)
            index++
            if (index < chunks.size) {
                val next = chunks[index]
                pending = async { fetch(synthesizer, next) }
            }
        }
        player.awaitIdle()
        if (index < chunks.size) {
            if (failure?.reason == SpeechFailureReason.NO_KEY) {
                listener.onNoCloudKey()
            } else {
                listener.onCloudUnavailable(index, failure)
            }
            readWithSystemVoice(chunks, index)
        }
    }

    private suspend fun readWithSystemVoice(chunks: List<String>, from: Int) {
        for (i in from until chunks.size) {
            listener.onChunkStarted(i, chunks.size, cloud = false)
            systemVoice.speak(chunks[i])
        }
    }

    private class Fetched(val audio: ByteArray?, val failure: SpeechUnavailableException?)

    private suspend fun fetch(synthesizer: SpeechSynthesizer, text: String): Fetched = try {
        Fetched(runInterruptible(ioDispatcher) { synthesizer.synthesize(text) }, null)
    } catch (error: CancellationException) {
        throw error
    } catch (failure: SpeechUnavailableException) {
        Fetched(null, failure)
    } catch (failure: Exception) {
        val reason = if (failure is IOException) SpeechFailureReason.NO_CONNECTION else SpeechFailureReason.REQUEST_REFUSED
        Fetched(null, SpeechUnavailableException(reason, detail = failure.javaClass.simpleName))
    }
}
