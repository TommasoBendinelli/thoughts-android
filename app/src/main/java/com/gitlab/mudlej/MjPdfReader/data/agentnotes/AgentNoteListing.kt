// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

/** Whether the user has answered a note. */
enum class AgentNoteAnswerState { ANSWERED, NOT_ANSWERED, NOT_SENT_YET }

/** One reply as the list shows it: the phone's own copy when it has one, else the server's record. */
data class AgentNoteReplyEntry(
    val replyId: String,
    val createdAtMillis: Long?,
    val createdAt: String,
    val message: String?,
    /** Waiting in the outbox. */
    val pending: Boolean,
    /** The server has it. */
    val sent: Boolean,
)

/** A note in the list with its replies (oldest first). */
data class AgentNoteEntry(
    val note: AgentNote,
    val replies: List<AgentNoteReplyEntry>,
    val state: AgentNoteAnswerState,
    /** Known only from the server: its image is not on the phone yet. */
    val serverOnly: Boolean,
)

/** Which notes a list shows: those of a review card, of a document, or all. */
data class AgentNoteFilter(val reviewCardId: String? = null, val documentId: String? = null) {
    fun matches(note: AgentNote): Boolean = when {
        !reviewCardId.isNullOrBlank() -> note.reviewCardId == reviewCardId
        !documentId.isNullOrBlank() -> note.documentId == documentId
        else -> true
    }
}

/**
 * Merges the server's list (null when unreachable) with the phone's cache: every note of either,
 * the server's record winning for metadata (receipts, replies), deleted notes dropped; newest first.
 * When [serverComplete] (the server returned fewer notes than asked for), a cached note the server
 * no longer lists was deleted elsewhere and is dropped too, unless a reply to it still waits to be sent.
 */
fun mergeAgentNotes(
    local: List<AgentNote>,
    server: List<AgentNote>?,
    localReplies: Map<String, List<LocalAgentNoteReply>>,
    filter: AgentNoteFilter,
    serverComplete: Boolean = false,
): List<AgentNoteEntry> {
    val localIds = local.map { it.noteId }.toSet()
    val serverIds = server?.map { it.noteId }?.toSet()
    val byId = LinkedHashMap<String, AgentNote>()
    local.filter(filter::matches).filter { note ->
        serverIds == null || !serverComplete || note.noteId in serverIds ||
            localReplies[note.noteId].orEmpty().any { it.pending }
    }.forEach { byId[it.noteId] = it }
    server?.filter(filter::matches)?.forEach { note ->
        if (note.deleted) byId.remove(note.noteId) else byId[note.noteId] = note
    }
    return byId.values.map { note ->
        val mine = localReplies[note.noteId].orEmpty()
        val mineIds = mine.map { it.replyId }.toSet()
        val replies = mine.map {
            AgentNoteReplyEntry(it.replyId, it.createdAtMillis, it.server?.createdAt.orEmpty(), it.message, it.pending, it.sent)
        } + note.replies.filter { it.replyId !in mineIds }.map {
            AgentNoteReplyEntry(it.replyId, null, it.createdAt, it.message, pending = false, sent = true)
        }
        val state = when {
            replies.any { it.sent } -> AgentNoteAnswerState.ANSWERED
            mine.any { it.pending } -> AgentNoteAnswerState.NOT_SENT_YET
            else -> AgentNoteAnswerState.NOT_ANSWERED
        }
        AgentNoteEntry(note, replies, state, serverOnly = note.noteId !in localIds)
    }.sortedWith(compareByDescending<AgentNoteEntry> { it.note.createdAt }.thenByDescending { it.note.sequence })
}

/** The power-of-two sample size that keeps a decoded image at or under [maxPixels]. */
fun agentNoteSampleSize(width: Int, height: Int, maxPixels: Long): Int {
    var sample = 1
    while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) sample *= 2
    return sample
}

/**
 * The notes the note screen's arrows step through, oldest first, deleted ones left out: those sent
 * by [current]'s conversation, or, for a note that names none (a chat through the connector sends
 * none), every note that names none.
 */
fun conversationNotes(notes: List<AgentNote>, current: AgentNote): List<AgentNote> {
    val key = current.conversationKey
    val same = notes.filter { !it.deleted && it.conversationKey == key }
        .let { found -> if (found.any { it.noteId == current.noteId }) found else found + current }
    return same.sortedWith(compareBy<AgentNote> { it.createdAt }.thenBy { it.sequence })
}
