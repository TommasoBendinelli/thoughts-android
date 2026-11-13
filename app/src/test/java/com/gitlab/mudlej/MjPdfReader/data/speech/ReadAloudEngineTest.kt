// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudEngineTest {
    /** Records what started playing; a chunk "plays" until the next one is queued or awaitIdle. */
    class FakePlayer(
        private val events: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        private val failOn: Set<String> = emptySet(),
    ) : ChunkAudioPlayer {
        val played: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun enqueueAndAwaitStart(audio: ByteArray) {
            val text = String(audio)
            if (text in failOn) throw IOException("corrupt audio")
            played += text
            events += "play:$text"
        }

        override suspend fun awaitIdle() {
            events += "idle"
        }
    }

    class FakeVoice(private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())) : SystemVoice {
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun speak(text: String) {
            spoken += text
            events += "speak:$text"
        }
    }

    class RecordingListener : ReadAloudListener {
        val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val unavailableAt: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val causes: MutableList<SpeechFailureReason?> = Collections.synchronizedList(mutableListOf())
        var noKey = 0

        override fun onChunkStarted(index: Int, count: Int, cloud: Boolean) {
            started += "$index/$count ${if (cloud) "cloud" else "system"}"
        }

        override fun onCloudUnavailable(index: Int, cause: SpeechUnavailableException?) {
            unavailableAt += index
            causes += cause?.reason
        }

        override fun onNoCloudKey() {
            noKey++
        }
    }

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()

    /** Echoes the text as "audio", failing for [failOn]; records request order and concurrency. */
    private fun synthesizer(failOn: Set<String> = emptySet()) = SpeechSynthesizer { text ->
        val now = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        events += "fetch:$text"
        try {
            Thread.sleep(20)
            if (text in failOn) throw SpeechUnavailableException(SpeechFailureReason.PROVIDER_ERROR, 503)
            text.toByteArray()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    @Test fun playsInOrderFetchingOneChunkAheadOfPlayback() = runBlocking {
        val player = FakePlayer(events)
        val listener = RecordingListener()
        ReadAloudEngine(synthesizer(), player, FakeVoice(events), Dispatchers.IO, listener)
            .read(listOf("a", "b", "c", "d"))

        assertEquals(listOf("a", "b", "c", "d"), player.played)
        // Each next chunk is requested only once the previous one has started playing, so one
        // request is in flight while one chunk plays.
        assertEquals(
            listOf("fetch:a", "play:a", "fetch:b", "play:b", "fetch:c", "play:c", "fetch:d", "play:d", "idle"),
            events.toList(),
        )
        assertEquals(1, maxInFlight.get())
        assertEquals(listOf("0/4 cloud", "1/4 cloud", "2/4 cloud", "3/4 cloud"), listener.started)
    }

    @Test fun nextChunkIsFetchedWhileTheCurrentOnePlays() = runBlocking {
        val fetchedWhilePlaying = CompletableDeferred<Boolean>()
        val playing = CompletableDeferred<Unit>()
        // A player whose first chunk keeps playing until the second chunk's request has arrived.
        val player = object : ChunkAudioPlayer {
            val played = mutableListOf<String>()
            override suspend fun enqueueAndAwaitStart(audio: ByteArray) {
                played += String(audio)
                if (played.size == 1) playing.complete(Unit)
            }
            override suspend fun awaitIdle() = Unit
        }
        val synthesizer = SpeechSynthesizer { text ->
            if (text == "b") fetchedWhilePlaying.complete(playing.isCompleted)
            text.toByteArray()
        }
        ReadAloudEngine(synthesizer, player, FakeVoice(), Dispatchers.IO).read(listOf("a", "b"))

        assertTrue(fetchedWhilePlaying.await())
        assertEquals(listOf("a", "b"), player.played)
    }

    @Test fun firstChunkFailureReadsEverythingWithTheSystemVoice() = runBlocking {
        val player = FakePlayer(events)
        val voice = FakeVoice(events)
        val listener = RecordingListener()
        ReadAloudEngine(synthesizer(failOn = setOf("a")), player, voice, Dispatchers.IO, listener)
            .read(listOf("a", "b", "c"))

        assertEquals(emptyList<String>(), player.played)
        assertEquals(listOf("a", "b", "c"), voice.spoken)
        assertEquals(listOf(0), listener.unavailableAt)
        assertEquals(listOf("fetch:a", "idle", "speak:a", "speak:b", "speak:c"), events.toList())
    }

    @Test fun laterFailureFinishesCloudAudioThenContinuesWithTheSystemVoice() = runBlocking {
        val player = FakePlayer(events)
        val voice = FakeVoice(events)
        val listener = RecordingListener()
        ReadAloudEngine(synthesizer(failOn = setOf("c")), player, voice, Dispatchers.IO, listener)
            .read(listOf("a", "b", "c", "d"))

        assertEquals(listOf("a", "b"), player.played)
        assertEquals(listOf("c", "d"), voice.spoken)
        assertEquals(listOf(2), listener.unavailableAt)
        // Chunk b finishes playing before the system voice starts on c; d is never requested.
        assertEquals(
            listOf("fetch:a", "play:a", "fetch:b", "play:b", "fetch:c", "idle", "speak:c", "speak:d"),
            events.toList(),
        )
        assertEquals(listOf("0/4 cloud", "1/4 cloud", "2/4 system", "3/4 system"), listener.started)
    }

    @Test fun unplayableAudioFallsBackFromThatChunk() = runBlocking {
        val player = FakePlayer(events, failOn = setOf("b"))
        val voice = FakeVoice(events)
        ReadAloudEngine(synthesizer(), player, voice, Dispatchers.IO).read(listOf("a", "b", "c"))

        assertEquals(listOf("a"), player.played)
        assertEquals(listOf("b", "c"), voice.spoken)
    }

    @Test fun withoutAKeyTheSystemVoiceReadsEverythingAndNothingIsFetched() = runBlocking {
        val player = FakePlayer(events)
        val voice = FakeVoice(events)
        val listener = RecordingListener()
        ReadAloudEngine(null, player, voice, Dispatchers.IO, listener).read(listOf("a", "b"))

        assertEquals(listOf("a", "b"), voice.spoken)
        assertEquals(emptyList<String>(), player.played)
        assertEquals(1, listener.noKey)
        // The hint for a missing key, not the "cloud voice unavailable" warning.
        assertEquals(emptyList<Int>(), listener.unavailableAt)
        assertEquals(listOf("speak:a", "speak:b"), events.toList())
        assertEquals(listOf("0/2 system", "1/2 system"), listener.started)
    }

    @Test fun aKeyThatCannotBeReadCountsAsNoKey() = runBlocking {
        val voice = FakeVoice(events)
        val listener = RecordingListener()
        val synthesizer = SpeechSynthesizer { throw SpeechUnavailableException(SpeechFailureReason.NO_KEY) }
        ReadAloudEngine(synthesizer, FakePlayer(events), voice, Dispatchers.IO, listener).read(listOf("a"))

        assertEquals(listOf("a"), voice.spoken)
        assertEquals(1, listener.noKey)
        assertEquals(emptyList<Int>(), listener.unavailableAt)
    }

    @Test fun theFailureReasonReachesTheListener() = runBlocking {
        val listener = RecordingListener()
        val synthesizer = SpeechSynthesizer { throw SpeechUnavailableException(SpeechFailureReason.KEY_REJECTED, 401) }
        ReadAloudEngine(synthesizer, FakePlayer(events), FakeVoice(events), Dispatchers.IO, listener).read(listOf("a"))

        assertEquals(listOf(0), listener.unavailableAt)
        assertEquals(listOf<SpeechFailureReason?>(SpeechFailureReason.KEY_REJECTED), listener.causes)
        assertEquals(0, listener.noKey)
    }

    @Test fun anUnexpectedErrorStillFallsBack() = runBlocking {
        val voice = FakeVoice(events)
        val listener = RecordingListener()
        val synthesizer = SpeechSynthesizer { throw IOException("reset") }
        ReadAloudEngine(synthesizer, FakePlayer(events), voice, Dispatchers.IO, listener).read(listOf("a"))

        assertEquals(listOf("a"), voice.spoken)
        assertEquals(listOf<SpeechFailureReason?>(SpeechFailureReason.NO_CONNECTION), listener.causes)
    }

    @Test fun unplayableAudioReportsNoCause() = runBlocking {
        val listener = RecordingListener()
        ReadAloudEngine(synthesizer(), FakePlayer(events, failOn = setOf("a")), FakeVoice(events), Dispatchers.IO, listener)
            .read(listOf("a"))

        assertEquals(listOf<SpeechFailureReason?>(null), listener.causes)
    }

    @Test fun nothingToRead() = runBlocking {
        val player = FakePlayer(events)
        ReadAloudEngine(synthesizer(), player, FakeVoice(events), Dispatchers.IO).read(emptyList())
        assertEquals(emptyList<String>(), events.toList())
    }
}
