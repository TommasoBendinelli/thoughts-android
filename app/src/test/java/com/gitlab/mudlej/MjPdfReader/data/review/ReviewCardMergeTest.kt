// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReviewCardMergeTest {
    private val source = "docs-test/tex/fragments/sync.tex"
    private val read = "a".repeat(64)
    private val current = "b".repeat(64)

    private fun anchor(file: String = source, sha: String = read) = JsonObject().apply {
        addProperty("version", 2)
        add("segments", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("segment_id", "0")
                addProperty("source_file", file)
                addProperty("source_sha256", sha)
            })
        })
    }

    private fun card(id: String, anchor: JsonObject? = anchor(), retiredAt: Instant? = null, wholeDocument: Boolean = false) =
        ReviewCard(id, "approved", "manual", "c".repeat(64), 1, "d".repeat(64), "Question $id", RectF(), null, "succeeded",
            sourceAnchor = anchor, retiredAt = retiredAt, wholeDocument = wholeDocument)

    @Test fun cardsOfOneSourceAreReady() {
        val check = ReviewCardMerge.check(listOf(card("one"), card("two", anchor(sha = current)), card("three")))
        val plan = (check as ReviewCardMerge.Check.Ready).plan
        assertEquals(listOf("one", "two", "three"), plan.cardIds)
        assertEquals(source, plan.source)
        // The version most passages were read from.
        assertEquals(read, plan.expectedSourceSha256)
    }

    @Test fun whatCannotBeMergedIsRefusedBeforeAsking() {
        fun refusal(vararg cards: ReviewCard) = (ReviewCardMerge.check(cards.toList()) as ReviewCardMerge.Check.Refused).refusal
        assertEquals(ReviewCardMerge.Refusal.TOO_FEW, refusal(card("one")))
        assertEquals(ReviewCardMerge.Refusal.TOO_FEW, refusal(card("one"), card("one")))
        assertEquals(ReviewCardMerge.Refusal.TOO_MANY, refusal(*Array(21) { card("c$it") }))
        assertEquals(ReviewCardMerge.Refusal.RETIRED, refusal(card("one"), card("two", retiredAt = Instant.EPOCH)))
        assertEquals(ReviewCardMerge.Refusal.NOT_IN_SOURCE, refusal(card("one"), card("two", anchor = null)))
        assertEquals(ReviewCardMerge.Refusal.NOT_IN_SOURCE, refusal(card("one"), card("two", wholeDocument = true)))
        assertEquals(ReviewCardMerge.Refusal.SEVERAL_SOURCES, refusal(card("one"), card("two", anchor("docs-test/tex/other.tex"))))
    }

    @Test fun theMostReviewedCardIsKeptByDefault() {
        assertEquals("two", ReviewCardMerge.defaultKept(listOf("one", "two", "three"), mapOf("one" to 1, "two" to 4, "three" to 4)))
        assertEquals("one", ReviewCardMerge.defaultKept(listOf("one", "two"), emptyMap()))
    }

    // ------------------------------------------------------------------ the server call

    private class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private class Reply(val status: Int, val body: String)

    /** A one-request-per-connection HTTP server on localhost, enough for HttpURLConnection. */
    private class FakeServer(private val reply: (Recorded) -> Reply) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val requests = CopyOnWriteArrayList<Recorded>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { client.use(::serve) }
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
            val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                if (n < 0) break
                read += n
            }
            val parts = requestLine.split(' ')
            val recorded = Recorded(parts[0], parts[1], headers, String(body, StandardCharsets.UTF_8))
            requests += recorded
            val response = reply(recorded)
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            client.getOutputStream().apply {
                write(("HTTP/1.1 ${response.status} Status\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                write(bytes)
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

    private val servers = mutableListOf<FakeServer>()
    @After fun closeServers() = servers.forEach { it.close() }

    private fun server(reply: (Recorded) -> Reply) = FakeServer(reply).also { servers += it }

    private var nextRequest = 0
    private fun client(server: FakeServer) = ReviewCardMergeClient(
        config = { ThoughtsServerConfig(server.baseUrl, "device-token") },
        newRequestId = { "0190a000-0000-7000-8000-0000000000%02d".format(++nextRequest) },
    )

    private val plan = ReviewCardMerge.Plan(listOf("one", "two"), source, read)
    private val health = Reply(200, """{"capabilities":["review_card_merge_v1"]}""")
    private val merged = Reply(200, """{"schema_version":1,"accepted":true,"terminal":true,"state":"merged","replayed":false,
        "kept_card_id":"two","deleted_card_ids":["one"],"question":"Merged question","passages":2}""")

    @Test fun mergeSendsTheServersRequestAndReadsTheAnswer() {
        val fake = server { if (it.path == "/v1/health") health else merged }
        val result = client(fake).merge(plan, "two", "  Merged question ")
        assertEquals("two", result.keptCardId)
        assertEquals(listOf("one"), result.deletedCardIds)
        assertEquals(2, result.passages)
        val post = fake.requests.single { it.method == "POST" }
        assertEquals(ReviewCardMerge.PATH, post.path)
        assertEquals("Bearer device-token", post.headers["authorization"])
        val body = JsonParser.parseString(post.body).asJsonObject
        assertEquals(setOf("request_id", "card_ids", "keep_card_id", "question", "source", "expected_source_sha256"), body.keySet())
        assertEquals(listOf("one", "two"), body.getAsJsonArray("card_ids").map { it.asString })
        assertEquals("two", body.get("keep_card_id").asString)
        assertEquals("Merged question", body.get("question").asString)
        assertEquals(source, body.get("source").asString)
        assertEquals(read, body.get("expected_source_sha256").asString)
    }

    @Test fun aSourceThatMovedOnIsMergedOnTheServersVersionOnce() {
        val fake = server { request ->
            when {
                request.path == "/v1/health" -> health
                JsonParser.parseString(request.body).asJsonObject.get("expected_source_sha256").asString == read ->
                    Reply(409, """{"accepted":false,"error_code":"source_conflict","detail":"The source changed.","current_source_sha256":"$current"}""")
                else -> merged
            }
        }
        assertEquals("two", client(fake).merge(plan, "two", "Merged question").keptCardId)
        val posts = fake.requests.filter { it.method == "POST" }.map { JsonParser.parseString(it.body).asJsonObject }
        assertEquals(listOf(read, current), posts.map { it.get("expected_source_sha256").asString })
        // A refused request changed nothing; the second is a request of its own.
        assertNotEquals(posts[0].get("request_id").asString, posts[1].get("request_id").asString)
    }

    @Test fun aRefusalKeepsTheServersReason() {
        val fake = server {
            if (it.path == "/v1/health") health
            else Reply(409, """{"accepted":false,"error_code":"merge_refused","detail":"The cards are on different PDF versions."}""")
        }
        try {
            client(fake).merge(plan, "two", "Merged question")
            fail("The merge should be refused")
        } catch (refused: ReviewCardMergeException) {
            assertEquals(409, refused.status)
            assertEquals("merge_refused", refused.code)
            assertEquals("The cards are on different PDF versions.", refused.message)
        }
        assertEquals(1, fake.requests.count { it.method == "POST" })
    }

    @Test fun aServerWithoutMergingIsNotAsked() {
        val fake = server { Reply(200, """{"capabilities":["agent_note_v1"]}""") }
        try {
            client(fake).merge(plan, "two", "Merged question")
            fail("An older server cannot merge")
        } catch (expected: ReviewCardMergeUnsupportedException) {
            assertTrue(fake.requests.none { it.method == "POST" })
        }
    }
}
