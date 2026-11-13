// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenRouterSpeechClientTest {
    private class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private class Reply(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    /** A one-request-per-connection HTTP server on localhost, enough for HttpURLConnection. */
    private class FakeServer(private val reply: (Recorded) -> Reply) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val requests = CopyOnWriteArrayList<Recorded>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use(::serve)
            }
        }.apply { isDaemon = true; start() }

        private fun serve(client: Socket) {
            val input = client.getInputStream()
            val requestLine = readLine(input)
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val parts = requestLine.split(' ')
            val recorded = Recorded(parts[0], parts[1], headers, String(body, StandardCharsets.UTF_8))
            requests += recorded
            val response = reply(recorded)
            val head = buildString {
                append("HTTP/1.1 ${response.status} Status\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${response.body.size}\r\n")
                response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Connection: close\r\n\r\n")
            }
            client.getOutputStream().apply {
                write(head.toByteArray(StandardCharsets.ISO_8859_1))
                write(response.body)
                flush()
            }
        }

        private fun readLine(input: InputStream): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) line.write(c)
            }
            return line.toString(StandardCharsets.ISO_8859_1.name())
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }

    private class MemoryCache : SpeechAudioCache {
        val entries = ConcurrentHashMap<String, ByteArray>()
        override fun get(key: String): ByteArray? = entries[key]
        override fun put(key: String, audio: ByteArray) {
            entries[key] = audio
        }
    }

    private val servers = mutableListOf<FakeServer>()
    private val logs = CopyOnWriteArrayList<String>()
    private val apiKey = "sk-or-v1-0123456789abcdef"

    @After fun closeServers() = servers.forEach { it.close() }

    private fun server(reply: (Recorded) -> Reply) = FakeServer(reply).also { servers += it }

    private fun client(
        endpoint: String,
        key: String? = apiKey,
        cache: SpeechAudioCache? = null,
        readCache: Boolean = true,
    ) = OpenRouterSpeechClient(
        apiKey = { key },
        cache = cache,
        readCache = readCache,
        endpoint = endpoint,
        connectTimeoutMillis = 2_000,
        readTimeoutMillis = 5_000,
        log = { logs += it },
    )

    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 1, 2, 3)

    private fun Recorded.json() = JsonParser.parseString(body).asJsonObject

    private fun error(status: Int, message: String) =
        Reply(status, "application/json", """{"error":{"code":$status,"message":"$message"}}""".toByteArray())

    private fun expectFailure(block: () -> Unit): SpeechUnavailableException {
        try {
            block()
        } catch (error: SpeechUnavailableException) {
            return error
        }
        fail("expected the cloud voice to fail")
        throw AssertionError()
    }

    // Request building

    @Test fun requestBodyMatchesWhatTheServerProxySent() {
        val synthesis = OpenRouterSpeech.syntheses("Hello there.").first()
        val body = synthesis.requestBody()
        assertEquals(setOf("model", "input", "response_format", "voice", "instructions"), body.keySet())
        assertEquals("fish-audio/s2.1-pro-free:free", body["model"].asString)
        assertEquals("Hello there.", body["input"].asString)
        assertEquals("mp3", body["response_format"].asString)
        assertEquals("536d3a5e000945adb7038665781a4aca", body["voice"].asString)
        assertEquals(OpenRouterSpeech.INSTRUCTIONS, body["instructions"].asString)
    }

    @Test fun theFreeModelIsTriedBeforeThePaidOne() {
        assertEquals(
            listOf("fish-audio/s2.1-pro-free:free", "fish-audio/s2.1-pro"),
            OpenRouterSpeech.syntheses("Hi.").map { it.model },
        )
    }

    @Test fun instructionsOnlyGoToModelsThatHonourThem() {
        assertTrue(OpenRouterSpeech.modelAcceptsInstructions("fish-audio/s2.1-pro"))
        assertTrue(OpenRouterSpeech.modelAcceptsInstructions("openai/gpt-4o-mini-tts"))
        assertFalse(OpenRouterSpeech.modelAcceptsInstructions("hexgrad/kokoro-82m"))
        val body = Synthesis("hexgrad/kokoro-82m", "af_bella", null, "Hi.").requestBody()
        assertFalse(body.has("instructions"))
    }

    @Test fun textIsNormalizedAndLimited() {
        assertEquals("One two three.", OpenRouterSpeech.normalize("  One\n two\t\tthree. "))
        try {
            OpenRouterSpeech.normalize("   ")
            fail("empty text")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            OpenRouterSpeech.normalize("a".repeat(OpenRouterSpeech.MAX_TEXT_CHARS + 1))
            fail("too long")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test fun cacheKeysDependOnEverythingThatShapesTheAudioButNotTheKey() {
        val (free, paid) = OpenRouterSpeech.syntheses("Hi.")
        assertEquals(free.cacheKey(), OpenRouterSpeech.syntheses("Hi.").first().cacheKey())
        assertTrue(free.cacheKey() != paid.cacheKey())
        assertTrue(free.cacheKey() != OpenRouterSpeech.syntheses("Hi!").first().cacheKey())
        assertTrue(Regex("^[0-9a-f]{64}$").matches(free.cacheKey()))
    }

    // Fallback decisions

    @Test fun whichFailuresMoveToTheNextModel() {
        assertTrue(OpenRouterSpeech.shouldTryNextModel(null))
        assertTrue(OpenRouterSpeech.shouldTryNextModel(402))
        assertTrue(OpenRouterSpeech.shouldTryNextModel(429))
        assertTrue(OpenRouterSpeech.shouldTryNextModel(500))
        assertTrue(OpenRouterSpeech.shouldTryNextModel(503))
        assertFalse(OpenRouterSpeech.shouldTryNextModel(400))
        assertFalse(OpenRouterSpeech.shouldTryNextModel(401))
        assertFalse(OpenRouterSpeech.shouldTryNextModel(403))
        assertEquals(SpeechFailureReason.KEY_REJECTED, OpenRouterSpeech.reasonFor(401))
        assertEquals(SpeechFailureReason.NO_CREDITS, OpenRouterSpeech.reasonFor(402))
        assertEquals(SpeechFailureReason.RATE_LIMITED, OpenRouterSpeech.reasonFor(429))
        assertEquals(SpeechFailureReason.PROVIDER_ERROR, OpenRouterSpeech.reasonFor(502))
        assertEquals(SpeechFailureReason.REQUEST_REFUSED, OpenRouterSpeech.reasonFor(400))
    }

    // Against a local HTTP server

    @Test fun postsToOpenRouterWithTheUsersKeyAndReturnsTheMp3() {
        val server = server { Reply(200, "audio/mpeg", mp3) }
        val text = "Café — “quoted” text."
        val speech = client(server.baseUrl + "/api/v1/audio/speech")
        val audio = speech.synthesize(text)

        assertArrayEquals(mp3, audio)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/audio/speech", request.path)
        assertEquals("Bearer $apiKey", request.headers["authorization"])
        assertEquals("Thoughts", request.headers["x-title"])
        assertTrue(request.headers["content-type"].orEmpty().startsWith("application/json"))
        assertEquals(text, request.json()["input"].asString)
        assertEquals(OpenRouterSpeech.MODEL, request.json()["model"].asString)
        assertEquals(OpenRouterSpeech.MODEL, speech.lastModel)
        // The log names sizes and the model, never the text or the key.
        assertTrue(logs.single().contains("cache=miss"))
        assertFalse(logs.any { it.contains("quoted") || it.contains(apiKey) })
    }

    @Test fun theDefaultEndpointIsOpenRouter() {
        assertEquals("https://openrouter.ai/api/v1/audio/speech", OpenRouterSpeech.URL)
    }

    @Test fun noKeyFailsWithoutARequest() {
        val server = server { Reply(200, "audio/mpeg", mp3) }
        val failure = expectFailure { client(server.baseUrl, key = null).synthesize("Hello.") }
        assertEquals(SpeechFailureReason.NO_KEY, failure.reason)
        val blank = expectFailure { client(server.baseUrl, key = " ").synthesize("Hello.") }
        assertEquals(SpeechFailureReason.NO_KEY, blank.reason)
        assertEquals(0, server.requests.size)
    }

    @Test fun aBusyFreeTierMovesToThePaidModel() {
        val server = server { request ->
            if (request.json()["model"].asString == OpenRouterSpeech.MODEL) error(429, "busy") else Reply(200, "audio/mpeg", mp3)
        }
        val speech = client(server.baseUrl)
        assertArrayEquals(mp3, speech.synthesize("Hello."))
        assertEquals(listOf(OpenRouterSpeech.MODEL, OpenRouterSpeech.FALLBACK_MODEL), server.requests.map { it.json()["model"].asString })
        assertEquals(OpenRouterSpeech.FALLBACK_MODEL, speech.lastModel)
    }

    @Test fun aProviderErrorMovesToThePaidModel() {
        val server = server { request ->
            if (request.json()["model"].asString == OpenRouterSpeech.MODEL) error(503, "down") else Reply(200, "audio/mpeg", mp3)
        }
        assertArrayEquals(mp3, client(server.baseUrl).synthesize("Hello."))
        assertEquals(2, server.requests.size)
    }

    @Test fun aRejectedKeyIsNotRetriedWithThePaidModel() {
        val server = server { error(401, "No auth credentials found") }
        val failure = expectFailure { client(server.baseUrl).synthesize("Hello.") }
        assertEquals(SpeechFailureReason.KEY_REJECTED, failure.reason)
        assertEquals(401, failure.statusCode)
        // OpenRouter's message is kept for Test voice, but never logged.
        assertEquals("No auth credentials found", failure.detail)
        assertFalse(logs.any { it.contains("credentials") })
        assertEquals(1, server.requests.size)
    }

    @Test fun whenBothModelsFailTheLastFailureIsReported() {
        val server = server { request ->
            if (request.json()["model"].asString == OpenRouterSpeech.MODEL) error(429, "busy") else error(402, "Insufficient credits")
        }
        val failure = expectFailure { client(server.baseUrl).synthesize("Hello.") }
        assertEquals(SpeechFailureReason.NO_CREDITS, failure.reason)
        assertEquals(2, server.requests.size)
    }

    @Test fun nonAudioSuccessFails() {
        // A captive portal answers 200 with a web page.
        val server = server { Reply(200, "text/html", "<html></html>".toByteArray()) }
        val failure = expectFailure { client(server.baseUrl).synthesize("Hello.") }
        assertEquals(SpeechFailureReason.NOT_AUDIO, failure.reason)
        // 200 is not a reason to try the paid model.
        assertEquals(1, server.requests.size)
    }

    @Test fun refusedConnectionFails() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val failure = expectFailure { client("http://127.0.0.1:$closedPort/api/v1/audio/speech").synthesize("Hello.") }
        assertEquals(SpeechFailureReason.NO_CONNECTION, failure.reason)
        assertNull(failure.statusCode)
        // Both models were tried: no answer may be a passing network problem.
        assertEquals(2, logs.count { it.startsWith("speech failed") })
    }

    // Cache

    @Test fun cachedAudioIsReplayedWithoutARequest() {
        val server = server { Reply(200, "audio/mpeg", mp3) }
        val cache = MemoryCache()
        client(server.baseUrl, cache = cache).synthesize("Hello.")
        assertArrayEquals(mp3, client(server.baseUrl, cache = cache).synthesize("Hello."))
        assertEquals(1, server.requests.size)
        assertTrue(logs.last().contains("cache=hit"))
    }

    @Test fun audioFromThePaidModelIsFoundInTheCacheToo() {
        val server = server { Reply(200, "audio/mpeg", mp3) }
        val cache = MemoryCache()
        cache.put(OpenRouterSpeech.syntheses("Hello.")[1].cacheKey(), mp3)
        val speech = client(server.baseUrl, cache = cache)
        assertArrayEquals(mp3, speech.synthesize("Hello."))
        assertEquals(0, server.requests.size)
        assertEquals(OpenRouterSpeech.FALLBACK_MODEL, speech.lastModel)
    }

    @Test fun testVoiceSkipsTheCacheButStillFillsIt() {
        val server = server { Reply(200, "audio/mpeg", mp3) }
        val cache = MemoryCache()
        cache.put(OpenRouterSpeech.syntheses("Hello.").first().cacheKey(), byteArrayOf(1))
        assertArrayEquals(mp3, client(server.baseUrl, cache = cache, readCache = false).synthesize("Hello."))
        assertEquals(1, server.requests.size)
        assertArrayEquals(mp3, cache.get(OpenRouterSpeech.syntheses("Hello.").first().cacheKey()))
    }

    @Test fun noKeyMeansNoCachedAudioEither() {
        val cache = MemoryCache()
        cache.put(OpenRouterSpeech.syntheses("Hello.").first().cacheKey(), mp3)
        val failure = expectFailure { client("http://127.0.0.1:9", key = null, cache = cache).synthesize("Hello.") }
        assertEquals(SpeechFailureReason.NO_KEY, failure.reason)
    }

    @Test fun diskCacheRoundTripsAndEvictsTheLeastRecentlyUsed() {
        val directory = Files.createTempDirectory("speech-cache").toFile()
        try {
            val cache = DiskSpeechAudioCache(directory, maxBytes = 10)
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            val c = "c".repeat(64)
            cache.put(a, ByteArray(4) { 1 })
            File(directory, "$a.mp3").setLastModified(1_000)
            cache.put(b, ByteArray(4) { 2 })
            File(directory, "$b.mp3").setLastModified(2_000)
            assertArrayEquals(ByteArray(4) { 1 }, cache.get(a)) // a is now the most recent
            cache.put(c, ByteArray(4) { 3 })
            assertNull(cache.get(b))
            assertArrayEquals(ByteArray(4) { 1 }, cache.get(a))
            assertArrayEquals(ByteArray(4) { 3 }, cache.get(c))
            // Names that are not synthesis keys are never read or written.
            cache.put("../escape", ByteArray(1))
            assertNull(cache.get("../escape"))
            assertFalse(File(directory.parentFile, "escape.mp3").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    // With the reading engine

    @Test fun aRejectedKeyFallsBackToTheSystemVoice() = runBlocking {
        val server = server { error(401, "bad key") }
        val player = ReadAloudEngineTest.FakePlayer()
        val voice = ReadAloudEngineTest.FakeVoice()
        val listener = ReadAloudEngineTest.RecordingListener()
        ReadAloudEngine(client(server.baseUrl), player, voice, Dispatchers.IO, listener).read(listOf("One.", "Two."))

        assertEquals(emptyList<String>(), player.played)
        assertEquals(listOf("One.", "Two."), voice.spoken)
        assertEquals(listOf(0), listener.unavailableAt)
        assertEquals(listOf<SpeechFailureReason?>(SpeechFailureReason.KEY_REJECTED), listener.causes)
        // Fallback stops asking OpenRouter after the first failure.
        assertEquals(1, server.requests.size)
    }

    @Test fun successPlaysOpenRoutersAudio() = runBlocking {
        val server = server { request -> Reply(200, "audio/mpeg", request.json()["input"].asString.toByteArray()) }
        val player = ReadAloudEngineTest.FakePlayer()
        val voice = ReadAloudEngineTest.FakeVoice()
        ReadAloudEngine(client(server.baseUrl), player, voice, Dispatchers.IO).read(listOf("One.", "Two.", "Three."))

        assertEquals(listOf("One.", "Two.", "Three."), player.played)
        assertEquals(emptyList<String>(), voice.spoken)
    }
}
