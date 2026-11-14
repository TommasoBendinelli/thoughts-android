// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.tbend.thoughts.sdk.RenderStateIfChangedCall
import org.tbend.thoughts.sdk.RenderStateRecord
import org.tbend.thoughts.sdk.RenderStateTimings

class ConditionalAnswerTest {
    private var etag: String? = "a".repeat(64)
    private var body = """{"contract_version":2,"latest_server_sequence":41,"library_revision":"r","source_frontier":3}"""
    private var status = 200
    private val conditions = mutableListOf<String?>()
    private var bytesAnswered = 0
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/v2/sync/state") { exchange ->
            val sent = exchange.requestHeaders.getFirst("If-None-Match")
            synchronized(conditions) { conditions += sent }
            etag?.let { exchange.responseHeaders.add("ETag", "\"$it\"") }
            if (status == 200 && etag != null && sent == "\"$etag\"") {
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
            } else {
                val raw = body.toByteArray()
                bytesAnswered += raw.size
                exchange.sendResponseHeaders(status, raw.size.toLong())
                exchange.responseBody.use { it.write(raw) }
            }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val kept = ConditionalAnswer<JsonObject>()

    @After
    fun stop() {
        server.stop(0)
    }

    private fun ask(key: String = base): JsonObject? {
        val connection = URL("$base/v2/sync/state").openConnection() as HttpURLConnection
        return try {
            readSyncState(connection, "token-" + "x".repeat(4), key, kept)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun anIdlePollSendsTheETagAndKeepsTheAnswerItHad() {
        val first = ask()
        assertEquals(41L, first?.get("latest_server_sequence")?.asLong)
        val answeredOnce = bytesAnswered
        repeat(3) { assertSame(first, ask()) }
        assertEquals(listOf(null, "\"$etag\"", "\"$etag\"", "\"$etag\""), conditions)
        // The body travelled once for four polls.
        assertEquals(answeredOnce, bytesAnswered)
    }

    @Test
    fun aChangedStateReplacesTheKeptAnswer() {
        ask()
        etag = "b".repeat(64)
        body = body.replace("41", "42")
        assertEquals(42L, ask()?.get("latest_server_sequence")?.asLong)
        assertEquals(42L, ask()?.get("latest_server_sequence")?.asLong)
        assertEquals(listOf(null, "\"${"a".repeat(64)}\"", "\"${"b".repeat(64)}\""), conditions)
    }

    @Test
    fun anAnswerIsNeverUsedForAnotherServer() {
        ask()
        ask(key = "https://thoughts.example.org")
        assertEquals(listOf<String?>(null, null), conditions)
    }

    @Test
    fun aServerWithoutETagsIsAskedInFullEveryTime() {
        etag = null
        assertNotNull(ask())
        assertNotNull(ask())
        assertEquals(listOf<String?>(null, null), conditions)
    }

    @Test
    fun aRefusalIsNoAnswerAndKeepsWhatWasKept() {
        val first = ask()
        status = 503
        assertNull(ask())
        status = 200
        assertSame(first, ask())
    }

    @Test
    fun weakAndQuotedETagsAreKeptByTheirValue() {
        val answer = ConditionalAnswer<String>()
        answer.keep("k", " W/\"abc\" ", "value")
        assertEquals("abc" to "value", answer.kept("k"))
        assertNull(answer.kept("other"))
        answer.keep("k", null, "unnamed")
        assertNull(answer.kept("k"))
        answer.keep("k", "\"abc\"", "value")
        answer.clear()
        assertNull(answer.kept("k"))
    }

    private fun renderState(observedAt: String?) = RenderStateRecord(
        status = "current", renderFingerprint = "f", sourceFrontier = 7, observedAt = observedAt,
        serverDurationMs = 1.0, documents = emptyList(),
    )

    @Test
    fun anUnchangedRenderStateIsShownAgainAsCheckedNow() {
        val keptState = renderState("2026-01-02T03:04:05Z")
        val timings = RenderStateTimings(serverMs = 1.0, totalMs = 2.0)
        // 304 with the server's clock: the kept state, observed then.
        val unchanged = renderStateToShow(RenderStateIfChangedCall(null, "e", timings, 1_800_000_000_000), keptState, 5)
        assertEquals(keptState.copy(observedAt = "2027-01-15T08:00:00Z"), unchanged)
        // 304 without a Date header: the phone's clock.
        assertEquals("1970-01-01T00:00:05Z",
            renderStateToShow(RenderStateIfChangedCall(null, "e", timings), keptState, 5_000)?.observedAt)
        // A new state is shown as the server sent it.
        val changed = renderState("2026-02-03T04:05:06Z")
        assertSame(changed, renderStateToShow(RenderStateIfChangedCall(changed, "f", timings), keptState, 5))
        // "Unchanged" about nothing kept shows nothing rather than an empty status.
        assertNull(renderStateToShow(RenderStateIfChangedCall(null, "e", timings), null, 5))
    }
}
