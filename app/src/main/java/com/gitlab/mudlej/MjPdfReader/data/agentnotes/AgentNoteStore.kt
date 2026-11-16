// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A reply the phone made, as kept in its outbox until the server has it. */
data class LocalAgentNoteReply(
    val replyId: String,
    val noteId: String,
    val createdAtMillis: Long,
    val mediaType: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val strokeCount: Int,
    val message: String?,
    /** The server confirmed it; [server] is its record then. */
    val sent: Boolean,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** The server refused it for good (bad request, conflict); it is not retried. */
    val rejected: Boolean = false,
    val server: AgentNoteReply? = null,
    /** "Send with picture": the glasses photo's media type and capture; the bytes are its photo file. */
    val photoMediaType: String? = null,
    val photoCapture: String? = null,
    /**
     * What the answering agent may use, as chosen when the reply was written (an
     * [AgentNoteReplySources] API value), sent with it however late; null for replies queued
     * before the choice, which go without it.
     */
    val sources: String? = null,
) {
    val pending: Boolean get() = !sent && !rejected

    /** [sources] as a choice this phone knows, else the server's record of it; null when neither says. */
    val sourcesChoice: AgentNoteReplySources? get() = AgentNoteReplySources.fromApi(sources) ?: server?.sourcesChoice

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("reply_id", replyId)
        addProperty("note_id", noteId)
        addProperty("created_at_millis", createdAtMillis)
        addProperty("media_type", mediaType)
        addProperty("image_width", imageWidth)
        addProperty("image_height", imageHeight)
        addProperty("stroke_count", strokeCount)
        addProperty("message", message)
        addProperty("sent", sent)
        addProperty("attempts", attempts)
        addProperty("last_error", lastError)
        addProperty("rejected", rejected)
        server?.let { add("server", it.toJson()) }
        photoMediaType?.let { addProperty("photo_media_type", it) }
        photoCapture?.let { addProperty("photo_capture", it) }
        sources?.let { addProperty("sources", it) }
    }

    companion object {
        fun fromJson(json: JsonObject) = LocalAgentNoteReply(
            replyId = requireNotNull(json.string("reply_id")),
            noteId = requireNotNull(json.string("note_id")),
            createdAtMillis = json.long("created_at_millis"),
            mediaType = json.string("media_type") ?: "image/png",
            imageWidth = json.long("image_width").toInt(),
            imageHeight = json.long("image_height").toInt(),
            strokeCount = json.long("stroke_count").toInt(),
            message = json.string("message"),
            sent = json.get("sent")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            attempts = json.long("attempts").toInt(),
            lastError = json.string("last_error"),
            rejected = json.get("rejected")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            server = json.getAsJsonObjectOrNull("server")?.let { runCatching { AgentNoteReply.fromJson(it) }.getOrNull() },
            photoMediaType = json.string("photo_media_type"),
            photoCapture = json.string("photo_capture"),
            sources = json.string("sources"),
        )
    }
}

/**
 * The phone's agent notes on disk, under `filesDir/thoughts/agent-notes/`:
 *
 * ```
 * state.json                      inbox cursor
 * notes/<note_id>/note.json       the server's note record (written last: its presence means complete)
 * notes/<note_id>/image.png|jpg   the note image
 * notes/<note_id>/draft.json      strokes in progress (see AgentNoteDraft)
 * replies/<reply_id>.json         a reply: metadata and the sent flag (the outbox)
 * replies/<reply_id>.png          the annotated image
 * replies/<reply_id>.strokes.json the stroke document
 * replies/<reply_id>.photo        a photo sent with it (Send with picture), when there is one
 * ```
 *
 * Every file is written to a temporary name, synced and renamed. Plain java.io, so it is tested
 * on the JVM with a temporary directory.
 */
class AgentNoteStore(val root: File) {
    private val notesDir = File(root, "notes")
    private val repliesDir = File(root, "replies")
    private val stateFile = File(root, "state.json")

    // --- inbox cursor ---

    fun inboxCursor(): Long? = synchronized(LOCK) {
        readJson(stateFile)?.get("inbox_cursor")?.takeIf { it.isJsonPrimitive }?.asLong
    }

    fun setInboxCursor(cursor: Long) = synchronized(LOCK) {
        val state = readJson(stateFile) ?: JsonObject()
        state.addProperty("inbox_cursor", cursor)
        writeAtomically(stateFile, state.toString().toByteArray())
    }

    // --- notes ---

    private fun noteDir(noteId: String) = File(notesDir, safeName(noteId))

    fun hasNote(noteId: String): Boolean = synchronized(LOCK) {
        File(noteDir(noteId), NOTE_FILE).isFile && imageFile(noteId) != null
    }

    /** Stores a note and its image; the image is written first, so a stored note always has its image. */
    fun saveNote(note: AgentNote, image: ByteArray) = synchronized(LOCK) {
        val dir = noteDir(note.noteId).apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("image.") && it.name != "image.${note.imageExtension}" }
            ?.forEach { it.delete() }
        writeAtomically(File(dir, "image.${note.imageExtension}"), image)
        writeAtomically(File(dir, NOTE_FILE), note.toJson().toString().toByteArray())
    }

    /** Refreshes a stored note's record (replies, receipts) from the server; no-op if it is not stored. */
    fun updateNote(note: AgentNote): Boolean = synchronized(LOCK) {
        val file = File(noteDir(note.noteId), NOTE_FILE)
        if (!file.isFile) return false
        // The list refreshes every listed note on each opening: rewrite (and sync) only changed ones.
        val bytes = note.toJson().toString().toByteArray()
        if (!file.readBytes().contentEquals(bytes)) writeAtomically(file, bytes)
        true
    }

    fun note(noteId: String): AgentNote? = synchronized(LOCK) {
        readJson(File(noteDir(noteId), NOTE_FILE))?.let { runCatching { AgentNote.fromJson(it) }.getOrNull() }
    }

    fun imageFile(noteId: String): File? =
        noteDir(noteId).listFiles()?.firstOrNull { it.isFile && it.name.startsWith("image.") && !it.name.endsWith(TMP) }

    /** Every stored note, newest first. */
    fun notes(): List<AgentNote> = synchronized(LOCK) {
        notesDir.listFiles().orEmpty().filter(File::isDirectory).mapNotNull { dir ->
            readJson(File(dir, NOTE_FILE))?.let { runCatching { AgentNote.fromJson(it) }.getOrNull() }
        }.sortedWith(compareByDescending<AgentNote> { it.createdAt }.thenByDescending { it.sequence })
    }

    /** Removes a note, its draft, and every reply to it (sent or not). */
    fun deleteNote(noteId: String) = synchronized(LOCK) {
        replies(noteId).forEach { deleteReplyFiles(it.replyId) }
        noteDir(noteId).deleteRecursively()
    }

    // --- draft ---

    fun saveDraft(noteId: String, json: String) = synchronized(LOCK) {
        val dir = noteDir(noteId).apply { mkdirs() }
        writeAtomically(File(dir, DRAFT_FILE), json.toByteArray())
    }

    fun draft(noteId: String): String? = synchronized(LOCK) {
        File(noteDir(noteId), DRAFT_FILE).takeIf(File::isFile)?.readText()
    }

    fun clearDraft(noteId: String) = synchronized(LOCK) {
        File(noteDir(noteId), DRAFT_FILE).delete()
        Unit
    }

    // --- replies / outbox ---

    fun replyImageFile(replyId: String) = File(repliesDir, "${safeName(replyId)}.png")
    fun replyStrokesFile(replyId: String) = File(repliesDir, "${safeName(replyId)}.strokes.json")
    fun replyPhotoFile(replyId: String) = File(repliesDir, "${safeName(replyId)}.photo")
    private fun replyMetaFile(replyId: String) = File(repliesDir, "${safeName(replyId)}.json")

    /** Queues a reply: the image, strokes and photo first, then its metadata, which makes it visible. */
    fun queueReply(reply: LocalAgentNoteReply, image: ByteArray, strokesJson: String?, photo: ByteArray? = null) = synchronized(LOCK) {
        require((photo == null) == (reply.photoMediaType == null)) { "A photo needs its media type, and only then" }
        repliesDir.mkdirs()
        writeAtomically(replyImageFile(reply.replyId), image)
        strokesJson?.let { writeAtomically(replyStrokesFile(reply.replyId), it.toByteArray()) }
        photo?.let { writeAtomically(replyPhotoFile(reply.replyId), it) }
        writeAtomically(replyMetaFile(reply.replyId), reply.toJson().toString().toByteArray())
    }

    fun reply(replyId: String): LocalAgentNoteReply? = synchronized(LOCK) {
        readJson(replyMetaFile(replyId))?.let { runCatching { LocalAgentNoteReply.fromJson(it) }.getOrNull() }
    }

    private fun allReplies(): List<LocalAgentNoteReply> =
        repliesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".strokes.json") }
            .mapNotNull { file -> readJson(file)?.let { runCatching { LocalAgentNoteReply.fromJson(it) }.getOrNull() } }
            .sortedBy { it.createdAtMillis }

    /** The phone's replies to a note, oldest first. */
    fun replies(noteId: String): List<LocalAgentNoteReply> = synchronized(LOCK) {
        allReplies().filter { it.noteId == noteId }
    }

    /** Every note's replies, oldest first, from one reading of the replies folder. */
    fun repliesByNote(): Map<String, List<LocalAgentNoteReply>> = synchronized(LOCK) {
        allReplies().groupBy { it.noteId }
    }

    /** The outbox: replies not yet confirmed by the server and not refused for good, oldest first. */
    fun pendingReplies(): List<LocalAgentNoteReply> = synchronized(LOCK) { allReplies().filter { it.pending } }

    fun markReplySent(replyId: String, server: AgentNoteReply) = updateReply(replyId) {
        it.copy(sent = true, server = server, lastError = null)
    }

    fun markReplyFailed(replyId: String, error: String, permanent: Boolean) = updateReply(replyId) {
        it.copy(attempts = it.attempts + 1, lastError = error, rejected = permanent)
    }

    /** Keeps a reply image the server has (made elsewhere, or no longer on disk) for viewing. */
    fun saveDownloadedReply(reply: AgentNoteReply, image: ByteArray) = synchronized(LOCK) {
        repliesDir.mkdirs()
        writeAtomically(replyImageFile(reply.replyId), image)
        if (!replyMetaFile(reply.replyId).isFile) {
            val local = LocalAgentNoteReply(
                reply.replyId, reply.noteId, System.currentTimeMillis(), reply.mediaType,
                reply.imageWidth, reply.imageHeight, reply.strokeCount, reply.message, sent = true, server = reply,
                sources = reply.sources,
            )
            writeAtomically(replyMetaFile(reply.replyId), local.toJson().toString().toByteArray())
        }
    }

    private fun updateReply(replyId: String, change: (LocalAgentNoteReply) -> LocalAgentNoteReply): Boolean =
        synchronized(LOCK) {
            val current = reply(replyId) ?: return false
            writeAtomically(replyMetaFile(replyId), change(current).toJson().toString().toByteArray())
            true
        }

    private fun deleteReplyFiles(replyId: String) {
        replyMetaFile(replyId).delete()
        replyImageFile(replyId).delete()
        replyStrokesFile(replyId).delete()
        replyPhotoFile(replyId).delete()
    }

    // --- files ---

    private fun readJson(file: File): JsonObject? = runCatching {
        if (!file.isFile) null else JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    companion object {
        private val LOCK = Any()
        private const val NOTE_FILE = "note.json"
        private const val DRAFT_FILE = "draft.json"
        private const val TMP = ".tmp"

        /** `filesDir/thoughts/agent-notes`. */
        fun under(filesDir: File) = AgentNoteStore(File(filesDir, "thoughts/agent-notes"))

        /** IDs are client UUIDs; anything else is reduced to a safe file name. */
        internal fun safeName(id: String): String {
            val cleaned = id.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')
            require(cleaned.isNotEmpty()) { "Invalid id" }
            return cleaned.take(120)
        }

        /** Writes [bytes] to a temporary file in the same directory, syncs it and renames it over [target]. */
        fun writeAtomically(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}$TMP")
            try {
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }
}
