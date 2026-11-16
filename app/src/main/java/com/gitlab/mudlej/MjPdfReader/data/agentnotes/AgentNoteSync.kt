// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The phone's side of the agent-notes channel without Android: asks the inbox, stores new notes
 * with their images (deduplicated by note ID), posts the `delivered` receipt, advances the cursor,
 * and sends the reply outbox. Used by the live loop and the periodic worker.
 */
class AgentNoteSync(
    private val api: AgentNotesApi,
    private val store: AgentNoteStore,
    private val log: (String) -> Unit = {},
) {
    /**
     * One inbox request. Returns the notes stored by this call (oldest first). The cursor moves
     * past each note once it is stored (or skipped for good), so a failed download is asked again.
     */
    fun pollInbox(wait: Int, context: AgentStudyContext? = null): List<AgentNote> {
        INBOX_LOCK.lock()
        try {
            return pollLocked(wait, context)
        } finally {
            INBOX_LOCK.unlock()
        }
    }

    /** [pollInbox] unless another caller (the live loop) is asking right now; then null. */
    fun tryPollInbox(wait: Int, context: AgentStudyContext? = null): List<AgentNote>? {
        if (!INBOX_LOCK.tryLock()) return null
        try {
            return pollLocked(wait, context)
        } finally {
            INBOX_LOCK.unlock()
        }
    }

    private fun pollLocked(wait: Int, context: AgentStudyContext?): List<AgentNote> {
        val after = store.inboxCursor()
        val page = api.inbox(after, wait, context?.reviewCardId, context?.documentId)
        val stored = mutableListOf<AgentNote>()
        var cursor = after
        for (note in page.notes.sortedBy { it.sequence }) {
            if (!note.deleted && !store.hasNote(note.noteId)) {
                val image = try {
                    api.downloadImage(note)
                } catch (error: AgentNotesHttpException) {
                    if (!error.permanent) throw error
                    // Deleted meanwhile or too large: nothing to show; do not ask for it forever.
                    log("agent-notes skip note=${note.noteId.take(8)} status=${error.statusCode} code=${error.errorCode}")
                    cursor = maxOf(cursor ?: 0L, note.sequence)
                    store.setInboxCursor(cursor)
                    continue
                }
                store.saveNote(note, image)
                stored += note
                runCatching { api.receipt(note.noteId, "delivered") }
                    .onFailure { log("agent-notes delivered receipt failed ${it.javaClass.simpleName}") }
            }
            cursor = maxOf(cursor ?: 0L, note.sequence)
            store.setInboxCursor(cursor)
        }
        page.cursor?.let { if (cursor == null || it > cursor) store.setInboxCursor(it) }
        return stored
    }

    /**
     * The periodic job's round: sends the outbox, then asks the inbox without waiting, but only when
     * this phone receives notes ([AgentNotesReceiving]) and the live loop is not asking right now.
     * Returns (replies sent, notes stored).
     */
    fun periodicCheck(receiving: Boolean): Pair<Int, List<AgentNote>> {
        val sent = flushOutbox()
        val fresh = if (receiving) tryPollInbox(wait = 0).orEmpty() else emptyList()
        return sent to fresh
    }

    /**
     * A push said a note is waiting: asks the inbox now without waiting, like [periodicCheck]. While
     * the live loop holds its long poll ([liveLoopRunning]) the server answers that request with the
     * note, so this one steps aside; otherwise it waits for a request in flight (the periodic job's)
     * and asks after it, so a note that came after that request started is not missed. Either way a
     * note is stored and returned once ([AgentNoteStore.hasNote] under the inbox lock).
     */
    fun pushCheck(liveLoopRunning: Boolean): List<AgentNote> =
        if (liveLoopRunning) tryPollInbox(wait = 0).orEmpty() else pollInbox(wait = 0)

    /**
     * Sends every queued reply. Returns how many the server confirmed. A network failure stops the
     * round (the next one retries); a permanent refusal marks that reply and goes on.
     */
    fun flushOutbox(): Int {
        var sent = 0
        for (reply in store.pendingReplies()) {
            if (sendReply(reply) != null) sent++ else if (store.reply(reply.replyId)?.pending == true) break
        }
        return sent
    }

    /** Sends one queued reply; the server's record on success, null when it stays queued or was refused. */
    fun sendReply(reply: LocalAgentNoteReply): AgentNoteReply? {
        val image = store.replyImageFile(reply.replyId).takeIf { it.isFile }?.readBytes()
        if (image == null) {
            store.markReplyFailed(reply.replyId, "image missing", permanent = true)
            return null
        }
        val strokes = store.replyStrokesFile(reply.replyId).takeIf { it.isFile }?.readText()
        val photo = reply.photoMediaType?.let { mediaType ->
            val bytes = store.replyPhotoFile(reply.replyId).takeIf { it.isFile }?.readBytes()
            if (bytes == null) {
                // Without its picture the reply would split the words from it again: never send it so.
                store.markReplyFailed(reply.replyId, "photo missing", permanent = true)
                return null
            }
            AgentNoteReplyPhotoUpload(bytes, mediaType, reply.photoCapture)
        }
        return try {
            // The choice it was written with, not the one made since.
            val server = api.postReply(reply.noteId, reply.replyId, image, reply.mediaType, strokes, reply.message, photo, reply.sources)
            store.markReplySent(reply.replyId, server)
            refreshNoteAfterReply(reply.noteId)
            server
        } catch (error: AgentNotesHttpException) {
            store.markReplyFailed(reply.replyId, "HTTP ${error.statusCode} ${error.errorCode.orEmpty()}".trim(), error.permanent)
            log("agent-notes reply failed status=${error.statusCode} code=${error.errorCode} permanent=${error.permanent}")
            null
        } catch (error: IOException) {
            store.markReplyFailed(reply.replyId, error.javaClass.simpleName, permanent = false)
            null
        }
    }

    private fun refreshNoteAfterReply(noteId: String) {
        runCatching { api.note(noteId) }.onSuccess { store.updateNote(it) }
    }

    companion object {
        /** One inbox request at a time in this process (the live loop and the worker share the cursor). */
        private val INBOX_LOCK = ReentrantLock()
    }
}

/** 2 s, 4 s, 8 s … up to 60 s after consecutive errors. */
fun agentNotesBackoffMillis(previousMillis: Long): Long =
    if (previousMillis <= 0) 2_000L else minOf(previousMillis * 2, 60_000L)

/**
 * The live long-poll loop: runs while [shouldRun] says so; each cycle sends the outbox, then waits
 * on the inbox for up to [waitSeconds]. New notes go to [onNewNotes]. Errors back off
 * ([agentNotesBackoffMillis]). [sleep] is injectable for tests.
 */
class AgentNoteInboxLoop(
    private val sync: AgentNoteSync,
    private val shouldRun: () -> Boolean,
    private val context: () -> AgentStudyContext?,
    private val onNewNotes: (List<AgentNote>) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val waitSeconds: Int = 25,
    private val log: (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The back-off delays slept so far (for tests and logs). */
    val backoffs = mutableListOf<Long>()

    suspend fun run() {
        var backoff = 0L
        while (shouldRun()) {
            currentCoroutineContext().ensureActive()
            try {
                runCatching { sync.flushOutbox() }
                val started = nowMillis()
                val fresh = sync.pollInbox(waitSeconds, context())
                backoff = 0L
                if (fresh.isNotEmpty()) {
                    onNewNotes(fresh)
                } else if (nowMillis() - started < MIN_EMPTY_CYCLE_MILLIS && shouldRun()) {
                    // A server that does not hold the request must not turn the loop into a busy poll.
                    sleep(MIN_EMPTY_CYCLE_MILLIS)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!shouldRun()) break
                backoff = agentNotesBackoffMillis(backoff)
                backoffs += backoff
                log("agent-notes inbox error ${error.javaClass.simpleName}; retry in ${backoff / 1000} s")
                sleep(backoff)
            }
        }
    }
}

private const val MIN_EMPTY_CYCLE_MILLIS = 1_000L

/**
 * Where notes that just arrived go. A new note never takes over the screen: it [waiting]s for the
 * note screen's New note button, and outside that screen it also notifies, so it can be tapped from anywhere.
 * The user's own pages ([AgentNote.isPhonePage]) that another device sent, such as the Mac's Send
 * Page to Agent, are stored and listed but never announced, like the pages this phone sends.
 */
data class AgentNoteArrival(val waiting: List<AgentNote>, val notify: List<AgentNote>)

fun agentNoteArrival(notes: List<AgentNote>, onNoteScreen: Boolean, shownNoteId: String? = null): AgentNoteArrival {
    val fresh = notes.filter { it.noteId != shownNoteId && !it.isPhonePage }
    return AgentNoteArrival(fresh, if (onNoteScreen) emptyList() else fresh)
}

/** A draft in progress: the strokes plus the comment and tool, so it survives process death. */
data class AgentNoteDraft(
    val strokesJson: String,
    val message: String = "",
    val tool: String = "pen",
    val color: String = "#000000",
) {
    fun toJson(): String = JsonObject().apply {
        add("strokes", JsonParser.parseString(strokesJson))
        addProperty("message", message)
        addProperty("tool", tool)
        addProperty("color", color)
    }.toString()

    companion object {
        fun fromJson(json: String): AgentNoteDraft? = runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            AgentNoteDraft(
                strokesJson = root.get("strokes").toString(),
                message = root.string("message").orEmpty(),
                tool = root.string("tool") ?: "pen",
                color = root.string("color") ?: "#000000",
            )
        }.getOrNull()
    }
}
