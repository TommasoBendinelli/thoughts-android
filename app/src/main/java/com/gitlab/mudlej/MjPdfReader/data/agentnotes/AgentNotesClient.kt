// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerAddress
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerConfig
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections

/** The server's agent-notes endpoints, as the phone uses them (protocol/agent-notes.md). Blocking. */
interface AgentNotesApi {
    fun inbox(after: Long?, wait: Int, reviewCardId: String? = null, documentId: String? = null): AgentNoteInboxPage
    fun list(documentId: String? = null, reviewCardId: String? = null, limit: Int = 50): List<AgentNote>
    fun note(noteId: String): AgentNote
    fun downloadImage(note: AgentNote): ByteArray
    fun downloadReplyImage(reply: AgentNoteReply): ByteArray
    fun receipt(noteId: String, state: String): AgentNote
    fun postReply(
        noteId: String,
        replyId: String,
        imageBytes: ByteArray,
        mediaType: String,
        strokesJson: String?,
        message: String?,
    ): AgentNoteReply
    /** A reply with a [photo] sent with it (agent_note_reply_photos_v1); without one it is the plain post. */
    fun postReply(
        noteId: String,
        replyId: String,
        imageBytes: ByteArray,
        mediaType: String,
        strokesJson: String?,
        message: String?,
        photo: AgentNoteReplyPhotoUpload?,
    ): AgentNoteReply =
        if (photo == null) postReply(noteId, replyId, imageBytes, mediaType, strokesJson, message)
        else throw UnsupportedOperationException("photo")
    fun delete(noteId: String)
    /** Whether an agent listens for replies to [noteId] right now; UNKNOWN on a server that cannot tell. */
    fun listening(noteId: String): AgentNoteListening = listeningAnswer(noteId).state
    /** [listening] with who answers the reply (the sender's agent, a topic owner or the reply agent). */
    fun listeningAnswer(noteId: String): AgentNoteListeningAnswer = AgentNoteListeningAnswer.UNKNOWN
    /**
     * Chooses the agent that answers replies to [noteId] ([agent]), or automatic (null), and
     * returns the note's listening answer as it is then (server [AGENT_NOTE_ANSWER_BY_CAPABILITY]).
     * An agent the server does not know is refused with 409 [AGENT_NOTE_ANSWERER_UNKNOWN].
     */
    fun setAnswerBy(noteId: String, agent: AgentNoteAgent?): AgentNoteListeningAnswer =
        throw UnsupportedOperationException("setAnswerBy")
    /** What the server can do (its capability names); empty when it does not say. */
    fun capabilities(): Set<String> = emptySet()
    /** Creates a note from this phone (a page sent to the agent); the same [note] again is idempotent. */
    fun createNote(note: AgentNote, imageBytes: ByteArray, strokeCount: Int): AgentNote =
        throw UnsupportedOperationException("createNote")
}

/** The server answered with an HTTP error; [errorCode] is its `error_code` when it sent one. */
class AgentNotesHttpException(
    val statusCode: Int,
    val errorCode: String?,
    message: String,
) : IOException(message) {
    /** A retry of the same request cannot succeed (bad request, conflict, too large, gone). */
    val permanent: Boolean get() = statusCode in 400..499 && statusCode != 401 && statusCode != 403 &&
        statusCode != 408 && statusCode != 429
}

/** No Thoughts server is configured on this phone. */
class AgentNotesNotConfiguredException : IOException("The Thoughts server is not configured")

/**
 * HTTP client for `/v1/agent-notes` with the device token the app uses for every other /v1 call,
 * with HttpURLConnection. Call it off the
 * main thread. [log] receives one line per request with status, sizes and timing, never contents.
 */
class AgentNotesClient(
    private val config: () -> ThoughtsServerConfig?,
    private val connectionFactory: (URL) -> URLConnection = { it.openConnection() },
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 30_000,
    private val log: (String) -> Unit = {},
    private val maxImageBytes: Long = MAX_IMAGE_BYTES,
) : AgentNotesApi {
    private val inFlight = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())

    override fun inbox(after: Long?, wait: Int, reviewCardId: String?, documentId: String?): AgentNoteInboxPage {
        val boundedWait = wait.coerceIn(0, 30)
        val json = requestJson(
            "GET",
            BASE_PATH + "/inbox" + query(
                "after" to after?.toString(),
                "wait" to boundedWait.toString(),
                "review_card_id" to reviewCardId,
                "document_id" to documentId,
            ),
            readTimeout = (boundedWait + 15) * 1000,
            label = "inbox",
        )
        val notes = json.get("notes")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?.map { AgentNote.fromJson(it.asJsonObject) }.orEmpty()
        val cursor = json.get("cursor")?.takeIf { it.isJsonPrimitive }?.asLong
        return AgentNoteInboxPage(notes, cursor)
    }

    override fun list(documentId: String?, reviewCardId: String?, limit: Int): List<AgentNote> {
        val json = requestJson(
            "GET",
            BASE_PATH + query(
                "document_id" to documentId,
                "review_card_id" to reviewCardId,
                "limit" to limit.coerceIn(1, 200).toString(),
            ),
            label = "list",
        )
        return json.get("notes")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?.map { AgentNote.fromJson(it.asJsonObject) }.orEmpty()
    }

    override fun note(noteId: String): AgentNote =
        AgentNote.fromJson(requestJson("GET", "$BASE_PATH/${segment(noteId)}", label = "note"))

    override fun downloadImage(note: AgentNote): ByteArray =
        download(imagePath(note.imageUrl, "$BASE_PATH/${segment(note.noteId)}/image"), "image")

    override fun downloadReplyImage(reply: AgentNoteReply): ByteArray =
        download(imagePath(reply.imageUrl, "$BASE_PATH/replies/${segment(reply.replyId)}/image"), "reply-image")

    /** The server's image address when it is one of its own agent-note paths, else the documented path. */
    private fun imagePath(supplied: String, documented: String): String {
        val server = config() ?: throw AgentNotesNotConfiguredException()
        return acceptedImagePath(server.baseUrl, supplied)
            ?: documented.also { if (supplied.isNotBlank()) log("agent-notes image_url ignored: not an agent-notes path of the server") }
    }

    override fun receipt(noteId: String, state: String): AgentNote {
        require(state == "delivered" || state == "opened") { "Unknown receipt state $state" }
        val body = JsonObject().apply { addProperty("state", state) }.toString().toByteArray(StandardCharsets.UTF_8)
        return AgentNote.fromJson(
            requestJson("POST", "$BASE_PATH/${segment(noteId)}/receipt", body = body, label = "receipt-$state"),
        )
    }

    override fun postReply(
        noteId: String,
        replyId: String,
        imageBytes: ByteArray,
        mediaType: String,
        strokesJson: String?,
        message: String?,
    ): AgentNoteReply = postReply(noteId, replyId, imageBytes, mediaType, strokesJson, message, null)

    override fun postReply(
        noteId: String,
        replyId: String,
        imageBytes: ByteArray,
        mediaType: String,
        strokesJson: String?,
        message: String?,
        photo: AgentNoteReplyPhotoUpload?,
    ): AgentNoteReply {
        // Streamed: the base64 of a large PNG (and of the photo) is written straight into the request.
        val prefix = buildString {
            append("{\"reply_id\":").append(JsonPrimitive(replyId))
            message?.takeIf(String::isNotBlank)?.let { append(",\"message\":").append(JsonPrimitive(it)) }
            strokesJson?.let { append(",\"strokes\":").append(JsonParser.parseString(it).toString()) }
            append(",\"image\":{\"media_type\":").append(JsonPrimitive(mediaType)).append(",\"data_base64\":\"")
        }.toByteArray(StandardCharsets.UTF_8)
        val middle = photo?.let {
            buildString {
                append("\"},\"photo\":{\"media_type\":").append(JsonPrimitive(it.mediaType))
                it.capture?.let { capture -> append(",\"capture\":").append(JsonPrimitive(capture)) }
                append(",\"data_base64\":\"")
            }.toByteArray(StandardCharsets.UTF_8)
        }
        val suffix = "\"}}".toByteArray(StandardCharsets.UTF_8)
        val base64Length = 4L * ((imageBytes.size + 2) / 3) +
            (photo?.let { (middle?.size ?: 0) + 4L * ((it.bytes.size + 2) / 3) } ?: 0L)
        val length = prefix.size + base64Length + suffix.size
        val json = request(
            "POST",
            "$BASE_PATH/${segment(noteId)}/replies",
            bodyLength = length,
            writeBody = { output ->
                output.write(prefix)
                val encoder = Base64.getEncoder().wrap(NonClosingOutputStream(output))
                encoder.write(imageBytes)
                encoder.close()
                if (photo != null && middle != null) {
                    output.write(middle)
                    val photoEncoder = Base64.getEncoder().wrap(NonClosingOutputStream(output))
                    photoEncoder.write(photo.bytes)
                    photoEncoder.close()
                }
                output.write(suffix)
            },
            label = "reply",
            accept = { connection -> parseJson(readLimited(connection.inputStream, MAX_JSON_BYTES)) },
        )
        return AgentNoteReply.fromJson(json, noteId)
    }

    override fun delete(noteId: String) {
        request("DELETE", "$BASE_PATH/${segment(noteId)}", label = "delete", accept = { Unit })
    }

    override fun listeningAnswer(noteId: String): AgentNoteListeningAnswer = try {
        AgentNoteListeningAnswer.fromJson(requestJson("GET", "$BASE_PATH/${segment(noteId)}/listening", label = "listening"))
    } catch (error: AgentNotesHttpException) {
        // A server before agent_note_listening_v1 has no such route: it cannot tell.
        if (error.statusCode == 404) AgentNoteListeningAnswer.UNKNOWN else throw error
    }

    override fun setAnswerBy(noteId: String, agent: AgentNoteAgent?): AgentNoteListeningAnswer {
        val body = JsonObject().apply { add("agent", agent?.toChoiceJson() ?: com.google.gson.JsonNull.INSTANCE) }
            .toString().toByteArray(StandardCharsets.UTF_8)
        return AgentNoteListeningAnswer.fromJson(
            requestJson("PUT", "$BASE_PATH/${segment(noteId)}/answer-by", body = body, label = "answer-by"),
        )
    }

    override fun capabilities(): Set<String> =
        requestJson("GET", "/v1/health", label = "capabilities").get("capabilities")
            ?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?.mapNotNullTo(linkedSetOf()) { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
            .orEmpty()

    override fun createNote(note: AgentNote, imageBytes: ByteArray, strokeCount: Int): AgentNote {
        // Streamed like a reply: the base64 of the page is written straight into the request.
        val head = JsonObject().apply {
            addProperty("note_id", note.noteId)
            addProperty("title", note.title)
            note.message?.takeIf(String::isNotBlank)?.let { addProperty("message", it) }
            note.documentId?.let { addProperty("document_id", it) }
            note.documentFilename?.let { addProperty("document_filename", it) }
            note.reviewCardId?.let { addProperty("review_card_id", it) }
            val origin = (note.origin?.deepCopy() ?: JsonObject())
            origin.addProperty("stroke_count", strokeCount)
            add("origin", origin)
        }.toString()
        val prefix = (head.removeSuffix("}") + ",\"image\":{\"media_type\":" + JsonPrimitive(note.mediaType) + ",\"data_base64\":\"")
            .toByteArray(StandardCharsets.UTF_8)
        val suffix = "\"}}".toByteArray(StandardCharsets.UTF_8)
        val length = prefix.size + 4L * ((imageBytes.size + 2) / 3) + suffix.size
        val json = request(
            "POST",
            BASE_PATH,
            bodyLength = length,
            writeBody = { output ->
                output.write(prefix)
                val encoder = Base64.getEncoder().wrap(NonClosingOutputStream(output))
                encoder.write(imageBytes)
                encoder.close()
                output.write(suffix)
            },
            label = "create",
            accept = { connection -> parseJson(readLimited(connection.inputStream, MAX_JSON_BYTES)) },
        )
        return AgentNote.fromJson(json.getAsJsonObject("note") ?: json)
    }

    /** Disconnects every request in flight (the live loop's long-poll), so a stopped loop ends at once. */
    fun abortInFlight() {
        synchronized(inFlight) { inFlight.toList() }.forEach { runCatching { it.disconnect() } }
    }

    private fun download(path: String, label: String): ByteArray =
        request("GET", path, label = label, acceptHeader = "image/*", accept = { connection ->
            val declared = connection.contentLengthLong
            if (declared > maxImageBytes) {
                throw AgentNotesHttpException(413, "agent_note_too_large", "Image is too large")
            }
            readLimited(connection.inputStream, maxImageBytes)
        })

    private fun requestJson(
        method: String,
        path: String,
        body: ByteArray? = null,
        readTimeout: Int = readTimeoutMillis,
        label: String,
    ): JsonObject = request(
        method,
        path,
        bodyLength = body?.size?.toLong(),
        writeBody = body?.let { bytes -> { output: OutputStream -> output.write(bytes) } },
        readTimeout = readTimeout,
        label = label,
        accept = { connection -> parseJson(readLimited(connection.inputStream, MAX_JSON_BYTES)) },
    )

    private fun <T> request(
        method: String,
        path: String,
        bodyLength: Long? = null,
        writeBody: ((OutputStream) -> Unit)? = null,
        readTimeout: Int = readTimeoutMillis,
        acceptHeader: String = "application/json",
        label: String,
        accept: (HttpURLConnection) -> T,
    ): T {
        val server = config() ?: throw AgentNotesNotConfiguredException()
        val started = System.nanoTime()
        val url = URI.create(server.baseUrl.trimEnd('/') + path).toURL()
        val connection = connectionFactory(url) as HttpURLConnection
        inFlight += connection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeout
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${server.token}")
            connection.setRequestProperty("Accept", acceptHeader)
            if (writeBody != null && bodyLength != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bodyLength)
                connection.outputStream.use(writeBody)
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = runCatching {
                    connection.errorStream?.use { readLimited(it, MAX_JSON_BYTES) }?.let(::parseJson)
                }.getOrNull()
                val code = error?.string("error_code")
                val detail = error?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString
                log("agent-notes $label failed status=$status code=$code ms=${elapsedMs(started)}")
                throw AgentNotesHttpException(status, code, detail ?: "Agent notes request failed with HTTP $status")
            }
            val result = accept(connection)
            val size = (result as? ByteArray)?.size?.let { " bytes=$it" }.orEmpty() +
                (bodyLength?.let { " sent=$it" }.orEmpty())
            log("agent-notes $label ok status=$status$size ms=${elapsedMs(started)}")
            return result
        } catch (error: IOException) {
            if (error !is AgentNotesHttpException) {
                log("agent-notes $label failed ${error.javaClass.simpleName} ms=${elapsedMs(started)}")
            }
            throw error
        } finally {
            inFlight -= connection
            connection.disconnect()
        }
    }

    private fun parseJson(bytes: ByteArray): JsonObject = try {
        JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
    } catch (error: RuntimeException) {
        throw IOException("The server's answer is not a JSON object", error)
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > limit) throw AgentNotesHttpException(413, "agent_note_too_large", "Response is too large")
        }
        output.toByteArray()
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000

    private class NonClosingOutputStream(private val target: OutputStream) : OutputStream() {
        override fun write(b: Int) = target.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)
        override fun flush() = target.flush()
        override fun close() = Unit
    }

    companion object {
        const val BASE_PATH = "/v1/agent-notes"
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_JSON_BYTES = 8L * 1024 * 1024

        private fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private val IMAGE_PATH = Regex("$BASE_PATH/[A-Za-z0-9._~%/-]+")

        /**
         * [supplied] (a note's or reply's `image_url`) when the device token may be sent there: a
         * plain path under /v1/agent-notes/ whose address, once appended to [baseUrl], is still the
         * configured server. Null for anything else, such as "@other.example/x", which appended to
         * the base address would name another host.
         */
        internal fun acceptedImagePath(baseUrl: String, supplied: String): String? {
            if (!IMAGE_PATH.matches(supplied) || supplied.split('/').any { it == ".." || it == "." } || "//" in supplied) return null
            val base = baseUrl.trimEnd('/')
            val result = runCatching { URI.create(base + supplied) }.getOrNull() ?: return null
            val sameServer = result.rawUserInfo == null &&
                ThoughtsServerAddress.originOf(result.toString())?.let { it == ThoughtsServerAddress.originOf(base) } == true
            return supplied.takeIf { sameServer && result.rawPath?.endsWith(supplied) == true }
        }

        internal fun query(vararg parameters: Pair<String, String?>): String {
            val present = parameters.filter { !it.second.isNullOrBlank() }
            if (present.isEmpty()) return ""
            return present.joinToString("&", prefix = "?") { (key, value) ->
                key + "=" + URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            }
        }
    }
}
