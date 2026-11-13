// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerConfig
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentNotesClientTest {
    private class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body, StandardCharsets.UTF_8)
    }

    private class Reply(val status: Int, val contentType: String, val body: ByteArray)

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
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val parts = requestLine.split(' ')
            val recorded = Recorded(parts[0], parts[1], headers, body)
            requests += recorded
            val response = reply(recorded)
            val head = buildString {
                append("HTTP/1.1 ${response.status} Status\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${response.body.size}\r\n")
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

    private val servers = mutableListOf<FakeServer>()
    private val logs = CopyOnWriteArrayList<String>()
    private val connections = CopyOnWriteArrayList<URLConnection>()

    @After fun closeServers() = servers.forEach { it.close() }

    private fun server(reply: (Recorded) -> Reply) = FakeServer(reply).also { servers += it }

    private fun client(baseUrl: String?, maxImageBytes: Long = AgentNotesClient.MAX_IMAGE_BYTES) = AgentNotesClient(
        config = { baseUrl?.let { ThoughtsServerConfig(it, "device-token") } },
        connectionFactory = { url -> url.openConnection().also { connections += it } },
        connectTimeoutMillis = 2_000,
        readTimeoutMillis = 5_000,
        log = { logs += it },
        maxImageBytes = maxImageBytes,
    )

    private fun json(text: String, status: Int = 200) = Reply(status, "application/json", text.toByteArray())

    private fun noteJson(id: String, sequence: Int, title: String = "Note $sequence") = """
        {"note_id":"$id","sequence":$sequence,"created_at":"2026-10-15T12:00:0${sequence % 10}Z","sender_device_id":"thoughts-mac",
         "title":"$title","message":"Look at this","media_type":"image/png","image_width":1200,"image_height":1800,
         "image_bytes":1234,"image_sha256":"abc","image_url":"/v1/agent-notes/$id/image","document_id":"doc-1",
         "document_filename":"Notes.pdf","review_card_id":"card-1","review_question":"Why?","origin":{"agent":"claude"},
         "delivered_at":null,"delivered_device_id":null,"opened_at":null,"deleted":false,
         "replies":[{"reply_id":"r-$id","sequence":3,"note_id":"$id","device_id":"phone","created_at":"2026-10-15T12:05:00Z",
           "media_type":"image/png","image_width":1200,"image_height":1800,"image_bytes":99,"image_sha256":"def",
           "image_url":"/v1/agent-notes/replies/r-$id/image","strokes_url":null,"stroke_count":7,"message":"done"}]}
    """.trimIndent()

    @Test fun inboxLongPollSendsCursorWaitAndStudyContext() {
        val server = server { json("""{"notes":[${noteJson("n-1", 12)}],"cursor":12,"server_time":"2026-10-15T12:00:00Z"}""") }
        val page = client(server.baseUrl + "/").inbox(after = 5, wait = 25, reviewCardId = "card-1", documentId = "doc 1")

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/agent-notes/inbox?after=5&wait=25&review_card_id=card-1&document_id=doc%201", request.path)
        assertEquals("Bearer device-token", request.headers["authorization"])
        // The read timeout covers the server's wait plus 15 s.
        assertEquals(40_000, connections.single().readTimeout)
        assertEquals(12L, page.cursor)
        val note = page.notes.single()
        assertEquals("n-1", note.noteId)
        assertEquals(12L, note.sequence)
        assertEquals("card-1", note.reviewCardId)
        assertEquals("Why?", note.reviewQuestion)
        assertEquals("claude", note.origin?.get("agent")?.asString)
        assertNull(note.openedAt)
        assertEquals(7, note.replies.single().strokeCount)
        assertNull(note.replies.single().strokesUrl)
        assertTrue(logs.single().startsWith("agent-notes inbox ok status=200"))
    }

    @Test fun firstInboxRequestHasNoCursorAndWaitIsBounded() {
        val server = server { json("""{"notes":[],"cursor":40}""") }
        val page = client(server.baseUrl).inbox(after = null, wait = 99)
        assertEquals("/v1/agent-notes/inbox?wait=30", server.requests.single().path)
        assertEquals(45_000, connections.single().readTimeout)
        assertTrue(page.notes.isEmpty())
        assertEquals(40L, page.cursor)
    }

    @Test fun listFiltersAndParsesNewestFirst() {
        val server = server { json("""{"notes":[${noteJson("b", 2)},${noteJson("a", 1)}]}""") }
        val notes = client(server.baseUrl).list(documentId = "doc-1", reviewCardId = null, limit = 500)
        assertEquals("/v1/agent-notes?document_id=doc-1&limit=200", server.requests.single().path)
        assertEquals(listOf("b", "a"), notes.map { it.noteId })
    }

    @Test fun noteAndReceipt() {
        val server = server { request ->
            when (request.path) {
                "/v1/agent-notes/n-1" -> json(noteJson("n-1", 1))
                "/v1/agent-notes/n-1/receipt" -> json(noteJson("n-1", 1).replace("\"opened_at\":null", "\"opened_at\":\"2026-10-15T12:01:00Z\""))
                else -> json("{}", 404)
            }
        }
        val client = client(server.baseUrl)
        assertEquals("Note 1", client.note("n-1").title)
        val opened = client.receipt("n-1", "opened")
        assertEquals("2026-10-15T12:01:00Z", opened.openedAt)
        val receipt = server.requests.last()
        assertEquals("POST", receipt.method)
        assertEquals("""{"state":"opened"}""", receipt.text)
        assertTrue(receipt.headers["content-type"].orEmpty().startsWith("application/json"))
    }

    @Test fun downloadsTheImageAndRefusesOneAboveTheLimit() {
        val png = Random(1).nextBytes(1000)
        val server = server { Reply(200, "image/png", png) }
        val note = AgentNote.fromJson(JsonParser.parseString(noteJson("n-1", 1)).asJsonObject)
        assertArrayEquals(png, client(server.baseUrl).downloadImage(note))
        assertEquals("/v1/agent-notes/n-1/image", server.requests.single().path)
        // A missing image_url falls back to the documented path.
        client(server.baseUrl).downloadImage(note.copy(imageUrl = ""))
        assertEquals("/v1/agent-notes/n-1/image", server.requests.last().path)

        try {
            client(server.baseUrl, maxImageBytes = 999).downloadImage(note)
            fail("An image above the limit must not be returned")
        } catch (error: AgentNotesHttpException) {
            assertEquals("agent_note_too_large", error.errorCode)
            assertTrue(error.permanent)
        }
    }

    @Test fun anImageAddressThatIsNotTheServersOwnPathIsNotFollowed() {
        val png = Random(2).nextBytes(100)
        val server = server { Reply(200, "image/png", png) }
        val elsewhere = server { Reply(200, "image/png", png) }
        val note = AgentNote.fromJson(JsonParser.parseString(noteJson("n-1", 1)).asJsonObject)
        val reply = note.replies.single()
        val otherHost = elsewhere.baseUrl.removePrefix("http://")
        // Appended to the base address, "@host/…" would make the configured server the user name of another host.
        for (hostile in listOf(
            "@$otherHost/v1/agent-notes/n-1/image", ".$otherHost/x", ":1@$otherHost/x", "//$otherHost/v1/agent-notes/n-1/image",
            "http://$otherHost/v1/agent-notes/n-1/image", "/v1/agent-notes/../../v1/library", "/v1/agent-notes/n-1/image?to=$otherHost",
            "/v1/library/projects", "/v1/agent-notes", "/v1/agent-notes/n 1/image", "/v1/agent-notes/n-1\\image", "/v1/agent-notes/n-1/image#x",
        )) {
            assertNull(hostile, AgentNotesClient.acceptedImagePath(server.baseUrl, hostile))
            assertArrayEquals(png, client(server.baseUrl).downloadImage(note.copy(imageUrl = hostile)))
            assertEquals(hostile, "/v1/agent-notes/n-1/image", server.requests.last().path)
            assertArrayEquals(png, client(server.baseUrl).downloadReplyImage(reply.copy(imageUrl = hostile)))
            assertEquals(hostile, "/v1/agent-notes/replies/r-n-1/image", server.requests.last().path)
        }
        // The other host was never contacted, so it never saw the device token.
        assertTrue(elsewhere.requests.isEmpty())
        assertTrue(server.requests.all { it.headers["authorization"] == "Bearer device-token" })
        assertTrue(logs.any { "image_url ignored" in it })
        // The server's own paths are used as given, also when the server lives under a path.
        assertEquals("/v1/agent-notes/n-1/image", AgentNotesClient.acceptedImagePath("https://thoughts.example.org", "/v1/agent-notes/n-1/image"))
        assertEquals(
            "/v1/agent-notes/replies/r-1/image",
            AgentNotesClient.acceptedImagePath("https://thoughts.example.org/thoughts/", "/v1/agent-notes/replies/r-1/image"),
        )
    }

    @Test fun postsTheReplyWithBase64ImageStrokesAndMessage() {
        val replyJson = """{"reply_id":"r-1","sequence":4,"note_id":"n-1","device_id":"phone","created_at":"2026-10-15T12:06:00Z",
            "media_type":"image/png","image_width":10,"image_height":20,"image_bytes":1000,"image_sha256":"x",
            "image_url":"/v1/agent-notes/replies/r-1/image","strokes_url":"/v1/agent-notes/replies/r-1/strokes","stroke_count":1,"message":"Ünïcode \"quoted\""}"""
        val server = server { json(replyJson, 201) }
        // 1000 bytes: not a multiple of 3, so the base64 ends with padding.
        val png = Random(2).nextBytes(1000)
        val document = AnnotationDocument(10, 20).apply {
            add(AnnotationStroke(AnnotationTool.PEN, 0xE53935, 2f, 1f, floatArrayOf(1f, 2f, 3f, 4f)))
        }
        val reply = client(server.baseUrl).postReply("n-1", "r-1", png, "image/png", document.toJson(), "Ünïcode \"quoted\"")

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/agent-notes/n-1/replies", request.path)
        assertEquals(request.body.size.toString(), request.headers["content-length"])
        val body = JsonParser.parseString(request.text).asJsonObject
        assertEquals(setOf("reply_id", "message", "strokes", "image"), body.keySet())
        assertEquals("r-1", body["reply_id"].asString)
        assertEquals("Ünïcode \"quoted\"", body["message"].asString)
        assertEquals(JsonParser.parseString(document.toJson()), body["strokes"])
        val image = body.getAsJsonObject("image")
        assertEquals("image/png", image["media_type"].asString)
        assertArrayEquals(png, Base64.getDecoder().decode(image["data_base64"].asString))
        assertEquals("r-1", reply.replyId)
        assertEquals(4L, reply.sequence)
        assertEquals("/v1/agent-notes/replies/r-1/strokes", reply.strokesUrl)
        // Logs carry sizes, never the comment.
        assertFalse(logs.any { it.contains("quoted") })
        assertTrue(logs.single().contains("sent=${request.body.size}"))
    }

    @Test fun postsAPhotoWithTheReply() {
        val server = server { json("""{"reply_id":"r-3","note_id":"n-1","photo":{"url":"/v1/agent-notes/replies/r-3/photo"}}""", 201) }
        val png = Random(3).nextBytes(10)
        val photo = Random(4).nextBytes(1001)
        client(server.baseUrl).postReply(
            "n-1", "r-3", png, "image/png", null, "From this picture",
            AgentNoteReplyPhotoUpload(photo, "image/jpeg", "IMG_20261111_170138_123_ab12"),
        )
        val request = server.requests.single()
        assertEquals(request.body.size.toString(), request.headers["content-length"])
        val body = JsonParser.parseString(request.text).asJsonObject
        assertEquals(setOf("reply_id", "message", "image", "photo"), body.keySet())
        assertArrayEquals(png, Base64.getDecoder().decode(body.getAsJsonObject("image")["data_base64"].asString))
        val sent = body.getAsJsonObject("photo")
        assertEquals("image/jpeg", sent["media_type"].asString)
        assertEquals("IMG_20261111_170138_123_ab12", sent["capture"].asString)
        assertArrayEquals(photo, Base64.getDecoder().decode(sent["data_base64"].asString))
    }

    @Test fun replyAnswerSaysWhetherAnAgentListens() {
        val server = server { request ->
            when (request.path) {
                "/v1/agent-notes/n-1/replies" -> json(
                    """{"reply_id":"r-1","note_id":"n-1","listening":{"state":"not_listening","listeners":[]}}""", 201)
                else -> json("""{"reply_id":"r-2","note_id":"n-2"}""", 201) // a server before agent_note_listening_v1
            }
        }
        val client = client(server.baseUrl)
        assertEquals(AgentNoteListening.NOT_LISTENING, client.postReply("n-1", "r-1", byteArrayOf(1), "image/png", null, null).listening)
        assertEquals(AgentNoteListening.UNKNOWN, client.postReply("n-2", "r-2", byteArrayOf(1), "image/png", null, null).listening)
        // Never kept: a stored reply record says nothing about who heard it.
        assertFalse(AgentNoteReply("r-1", "n-1", listening = AgentNoteListening.LISTENING).toJson().has("listening"))
    }

    @Test fun listeningAsksTheNoteAndAnOlderServerIsUnknown() {
        val server = server { request ->
            when (request.path) {
                "/v1/agent-notes/n-1/listening" -> json(
                    """{"note_id":"n-1","state":"listening","checked_at":"2026-11-11T16:01:00Z",
                       "listeners":[{"device_id":"thoughts-mac","conversation_id":"c-1","note_id":null,"last_seen_at":"2026-11-11T16:00:40Z"}]}""")
                "/v1/agent-notes/n-2/listening" -> json("""{"note_id":"n-2","state":"not_listening","listeners":[]}""")
                "/v1/agent-notes/n-3/listening" -> json("""{"detail":"Not Found"}""", 404)
                "/v1/agent-notes/n-4/listening" -> json("""{"note_id":"n-4","state":"unknown","listeners":[]}""")
                else -> Reply(503, "text/html", "<html>busy</html>".toByteArray())
            }
        }
        val client = client(server.baseUrl)
        assertEquals(AgentNoteListening.LISTENING, client.listening("n-1"))
        assertEquals(AgentNoteListening.NOT_LISTENING, client.listening("n-2"))
        assertEquals(AgentNoteListening.UNKNOWN, client.listening("n-3"))
        assertEquals(AgentNoteListening.UNKNOWN, client.listening("n-4"))
        assertEquals(listOf("GET"), server.requests.map { it.method }.distinct())
        try {
            client.listening("n-5")
            fail("503 must throw, so the screen keeps its last answer")
        } catch (error: AgentNotesHttpException) {
            assertEquals(503, error.statusCode)
        }
    }

    @Test fun replyWithoutStrokesOrMessageOmitsThem() {
        val server = server { json("""{"reply_id":"r-2","note_id":"n-1"}""", 200) }
        client(server.baseUrl).postReply("n-1", "r-2", byteArrayOf(1, 2, 3), "image/png", null, "  ")
        val body = JsonParser.parseString(server.requests.single().text).asJsonObject
        assertEquals(setOf("reply_id", "image"), body.keySet())
        assertEquals("AQID", body.getAsJsonObject("image")["data_base64"].asString)
    }

    @Test fun mapsServerErrorsToTypedExceptions() {
        val server = server { request ->
            when {
                request.path.endsWith("/replies") ->
                    json("""{"detail":"Different content for this reply_id","error_code":"agent_note_conflict"}""", 409)
                request.path.endsWith("/missing") -> json("""{"detail":"Not found","error_code":"agent_note_not_found"}""", 404)
                else -> Reply(503, "text/html", "<html>busy</html>".toByteArray())
            }
        }
        val client = client(server.baseUrl)
        try {
            client.postReply("n-1", "r-1", byteArrayOf(1), "image/png", null, null)
            fail("409 must throw")
        } catch (error: AgentNotesHttpException) {
            assertEquals(409, error.statusCode)
            assertEquals("agent_note_conflict", error.errorCode)
            assertEquals("Different content for this reply_id", error.message)
            assertTrue(error.permanent)
        }
        try {
            client.note("missing")
            fail("404 must throw")
        } catch (error: AgentNotesHttpException) {
            assertEquals("agent_note_not_found", error.errorCode)
            assertTrue(error.permanent)
        }
        try {
            client.inbox(1, 0)
            fail("503 must throw")
        } catch (error: AgentNotesHttpException) {
            assertEquals(503, error.statusCode)
            assertNull(error.errorCode)
            assertFalse(error.permanent)
        }
        assertTrue(logs.any { it.contains("status=409 code=agent_note_conflict") })
    }

    @Test fun deleteAcceptsNoContent() {
        val server = server { Reply(204, "application/json", ByteArray(0)) }
        client(server.baseUrl).delete("n-1")
        assertEquals("DELETE", server.requests.single().method)
        assertEquals("/v1/agent-notes/n-1", server.requests.single().path)
    }

    @Test fun networkErrorsAndMissingConfigurationThrow() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        try {
            client("http://127.0.0.1:$closedPort").inbox(null, 0)
            fail("A refused connection must throw")
        } catch (expected: IOException) {
        }
        assertTrue(logs.single().startsWith("agent-notes inbox failed"))
        try {
            client(null).list()
            fail("No server, no notes")
        } catch (expected: AgentNotesNotConfiguredException) {
        }
    }

    @Test fun abortEndsALongPollAtOnce() {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        // Accepts and never answers.
        val accepted = CopyOnWriteArrayList<Socket>()
        Thread { runCatching { accepted += serverSocket.accept() } }.apply { isDaemon = true; start() }
        val client = client("http://127.0.0.1:${serverSocket.localPort}")
        val started = System.nanoTime()
        val worker = Thread {
            try {
                client.inbox(null, 25)
            } catch (expected: IOException) {
            }
        }
        worker.start()
        Thread.sleep(300)
        client.abortInFlight()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        serverSocket.close()
        accepted.forEach { it.close() }
    }
}
