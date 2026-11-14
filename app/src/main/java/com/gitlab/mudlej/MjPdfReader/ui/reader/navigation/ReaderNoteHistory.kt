// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The agent note a link was followed from, as an intent extra (JSON of [AgentNotePlace]). */
const val EXTRA_AGENT_NOTE_PLACE = "org.tbend.thoughts.AGENT_NOTE_PLACE"

/**
 * An agent note as a place of a reader tab's history: the note (or a past reply to it) a link was
 * followed from, and how its picture was zoomed and scrolled, so Back shows it as it was left.
 */
data class AgentNotePlace(
    val noteId: String,
    val replyId: String? = null,
    /** The note's title, for the history list. */
    val title: String? = null,
    /** The picture's zoom (1 fits the width) and the image point at the view's top left, in fractions of the image; no zoom, not known. */
    val zoom: Float? = null,
    val left: Float = 0f,
    val top: Float = 0f,
) {
    /** The place's address in the history: one per note (and per reply), never a PDF's. */
    val uri: String
        get() = "$SCHEME://note/${encode(noteId)}" + (replyId?.let { "/reply/${encode(it)}" } ?: "")

    fun toJson(): String = JsonObject().apply {
        addProperty("note_id", noteId)
        replyId?.let { addProperty("reply_id", it) }
        title?.let { addProperty("title", it) }
        zoom?.let {
            addProperty("zoom", it)
            addProperty("left", left)
            addProperty("top", top)
        }
    }.toString()

    companion object {
        private const val SCHEME = "thoughts-agent-note"

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        fun fromJson(value: String?): AgentNotePlace? = value?.let {
            runCatching {
                val json = JsonParser.parseString(it).asJsonObject
                fun string(name: String) = json.get(name)?.takeIf { e -> e.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
                fun number(name: String) = json.get(name)?.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isNumber }
                    ?.asFloat?.takeIf(Float::isFinite)
                AgentNotePlace(
                    noteId = string("note_id") ?: return@runCatching null,
                    replyId = string("reply_id"),
                    title = string("title"),
                    zoom = number("zoom"),
                    left = number("left") ?: 0f,
                    top = number("top") ?: 0f,
                )
            }.getOrNull()
        }
    }
}

/**
 * Back and Forward between a reader tab and the agent notes window, which is another task. A tab
 * whose arrow showed an agent note says so here, so the notes window offers the arrows that lead on
 * from that note; an arrow pressed there waits here for the tab, which carries it out when it is
 * shown again. In memory only: after the app restarts the notes window simply has no such arrows.
 */
object ReaderNoteHistory {
    /** The tab [readerTaskId] showed [note] in place of its document; from the note, Back and Forward lead on as these say. */
    data class Shown(val readerTaskId: Int, val note: AgentNotePlace, val canGoBack: Boolean, val canGoForward: Boolean)

    var shown: Shown? = null
        private set
    private val requests = mutableMapOf<Int, Boolean>()

    fun show(shown: Shown) {
        this.shown = shown
        requests.remove(shown.readerTaskId)
    }

    /** The arrows for the note on screen in the notes window, or null when no reader arrow led there. */
    fun shownFor(noteId: String, replyId: String?): Shown? =
        shown?.takeIf { it.note.noteId == noteId && it.note.replyId == replyId }

    /** The notes window moved on (another note, a link followed): no arrow leads back into the reader from it. */
    fun forget() {
        shown = null
    }

    /** An arrow pressed on the note, for [readerTaskId] to carry out when it shows again: [back] Back, else Forward. */
    fun request(readerTaskId: Int, back: Boolean) {
        requests[readerTaskId] = back
    }

    fun hasRequest(readerTaskId: Int): Boolean = readerTaskId in requests

    /** The tab [readerTaskId] is shown again (or closed): the arrow pressed for it on the note, if any. */
    fun returned(readerTaskId: Int): Boolean? {
        if (shown?.readerTaskId == readerTaskId) shown = null
        return requests.remove(readerTaskId)
    }
}
