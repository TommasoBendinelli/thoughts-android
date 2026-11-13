// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

/**
 * What "Open conversation" puts on the clipboard when no agent listens for a reply: the user's
 * words and the note they answer, so pasting it into the conversation that sent the note gives
 * that agent everything (it reads the marked picture with its note tools). Null when there is
 * nothing to paste (no reply sent and nothing typed).
 */
fun agentNoteReplyForConversation(title: String, noteId: String, comment: String?, replied: Boolean): String? {
    val words = comment?.trim()?.takeIf(String::isNotEmpty)
    val note = "your agent note \"${title.ifBlank { "Agent note" }}\" (note $noteId)"
    val picture = "Read my reply with its picture: get_note_reply(note_id=\"$noteId\")."
    return when {
        replied && words != null -> "My reply on $note:\n$words\n\n$picture"
        replied -> "I replied on $note with a drawing. $picture"
        words != null -> "About $note:\n$words"
        else -> null
    }
}
