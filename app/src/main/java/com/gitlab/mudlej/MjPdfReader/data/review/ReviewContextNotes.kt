// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonObject
import java.time.Instant

/**
 * Notes about a card's section, page or whole document, written from the tag sheet's "About this…"
 * block and shared by every card of the same document. They ride on the answer's review event as
 * `review_context_notes`, next to `review_group_notes`:
 *
 * ```
 * "review_context_notes": {
 *   "section":  {"key": "3 Method › 3.2 Loss", "label": "3.2 Loss", "text": "…"},
 *   "page":     {"key": "7", "label": "Page 7", "text": "…"},
 *   "document": {"key": "", "label": "<display name>", "text": "…"}
 * }
 * ```
 *
 * An event carries only the scopes the answer changed; `text: ""` clears a note. A note belongs to
 * (document, scope, key), where the document is [documentKey] of the card. Its current text is the
 * one of the newest answer (reviewed_at, then event_id) of any card of that document that carries
 * that scope with that key ([current]); undone answers and deletions do not count. The section key
 * is the PDF outline path to the deepest heading at or before the card ([sectionOf]); the page key
 * is the 1-based page number; the document key is empty. Same wire format and rules as the desktop.
 */
object ReviewContextNotes {
    const val FIELD = "review_context_notes"
    const val MAX_LENGTH = ReviewGroupNotes.MAX_LENGTH
    /** Joins the outline titles of a section key, from the top level down. */
    const val SECTION_SEPARATOR = " › "

    enum class Scope(val wire: String) {
        SECTION("section"),
        PAGE("page"),
        DOCUMENT("document");

        companion object {
            fun of(wire: String): Scope? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** What a scope's note is about for one card: its [key] and the [label] shown for it. */
    data class Target(val key: String, val label: String)

    /** One scope of an event's field: the note's key, its label and its text ("" clears it). */
    data class Note(val key: String, val label: String, val text: String)

    /** A note as recorded: surrounding blanks trimmed, at most [MAX_LENGTH] characters. */
    fun clean(text: String): String = text.trim().take(MAX_LENGTH).trim()

    /** The document a card's notes belong to: its `document_id`, else (a card without one) its PDF stem. */
    fun documentKey(card: ReviewCard): String = card.documentId.ifBlank { card.pdfStem }

    fun pageKey(page: Int): String = page.toString()

    /** Titles as keys use them: blanks (Unicode ones too) collapsed to one space, trimmed (Qt's simplified()). */
    fun cleanTitle(title: String?): String = title.orEmpty().replace(BLANKS, " ").trim()

    private val BLANKS = Regex("[\\s\\p{Z}]+")

    /** The event field for [changes] (scope to note, texts cleaned), or null when nothing changed. */
    fun toJson(changes: Map<Scope, Note>): JsonObject? {
        if (changes.isEmpty()) return null
        return JsonObject().apply {
            Scope.entries.forEach { scope ->
                val note = changes[scope] ?: return@forEach
                add(scope.wire, JsonObject().apply {
                    addProperty("key", note.key)
                    addProperty("label", note.label)
                    addProperty("text", clean(note.text))
                })
            }
        }
    }

    /** The scopes an event changed, by wire name; a scope without a string key and text is ignored. */
    fun parse(event: JsonObject): Map<String, Note> = runCatching {
        val field = event.get(FIELD)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        buildMap {
            field.entrySet().forEach { (scope, value) ->
                if (Scope.of(scope) == null || !value.isJsonObject) return@forEach
                val note = value.asJsonObject
                val key = note.get("key")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return@forEach
                val text = note.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return@forEach
                val label = note.get("label")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                put(scope, Note(key, label, clean(text)))
            }
        }
    }.getOrDefault(emptyMap())

    /** One answer as [current] reads it: the card's document, when it was given, and what it changed. */
    data class Answer(
        val documentKey: String,
        val eventId: String,
        val reviewedAt: Instant?,
        val notes: Map<String, Note>,
    )

    /**
     * The current notes of [documentKey]: (scope wire name, key) to text, the newest answer
     * (reviewed_at, then event_id) of any card of that document deciding each; a cleared note is "".
     */
    fun current(answers: Sequence<Answer>, documentKey: String): Map<Pair<String, String>, String> {
        val newest = HashMap<Pair<String, String>, Answer>()
        val order = compareBy<Answer>({ it.reviewedAt ?: Instant.MIN }, { it.eventId })
        answers.filter { it.documentKey == documentKey }.forEach { answer ->
            answer.notes.forEach { (scope, note) ->
                val at = scope to note.key
                val previous = newest[at]
                if (previous == null || order.compare(answer, previous) > 0) newest[at] = answer
            }
        }
        return newest.mapValues { (at, answer) -> answer.notes.getValue(at.first).text }
    }

    /** The answers of one card's history that count for its notes: not undone, not a deletion, not an undo. */
    fun answersOf(documentKey: String, history: List<ReviewHistoryEntry>): Sequence<Answer> =
        history.asSequence()
            .filter { it.contextNotes.isNotEmpty() && !it.retracted && it.retractedEventId == null && it.rating != "delete" }
            .map { Answer(documentKey, it.eventId, it.reviewedAt, it.contextNotes) }

    /**
     * An outline (bookmark) entry: [pageIndex] 0-based (negative: no destination), [top] the
     * destination's height on the page as a fraction from the top, when known.
     */
    data class OutlineEntry(
        val title: String,
        val pageIndex: Int,
        val top: Float? = null,
        val children: List<OutlineEntry> = emptyList(),
    )

    /**
     * The section of a card whose primary region starts at [top] (fraction from the top) of the
     * 0-based [pageIndex], as the desktop works it out: at each outline level, from the top, the entry
     * that starts last at or before that point (of entries starting at the same point, the one listed
     * later), then the same among its entries, down to an entry with none that qualifies. An entry
     * starts at its destination, an entry without a known [OutlineEntry.top] at the top of its page,
     * and an entry without a destination where its earliest entry starts. The key is the chosen
     * titles ([cleanTitle]) joined with [SECTION_SEPARATOR]; the label is the last one. Null when no
     * top-level entry starts at or before the card (or there is no outline).
     */
    fun sectionOf(outline: List<OutlineEntry>, pageIndex: Int, top: Float): Target? {
        val position = Position(pageIndex, top)
        val path = mutableListOf<String>()
        var level = outline
        for (depth in 0..MAX_DEPTH) {
            if (pageIndex < 0) break
            var chosen: OutlineEntry? = null
            var chosenAt: Position? = null
            level.forEach { entry ->
                val start = startOf(entry) ?: return@forEach
                if (position < start) return@forEach
                if (chosenAt == null || start >= chosenAt!!) {
                    chosen = entry
                    chosenAt = start
                }
            }
            val entry = chosen ?: break
            path += cleanTitle(entry.title)
            level = entry.children
        }
        if (path.isEmpty()) return null
        return Target(path.joinToString(SECTION_SEPARATOR), path.last())
    }

    private data class Position(val pageIndex: Int, val top: Float) : Comparable<Position> {
        override fun compareTo(other: Position): Int =
            compareValuesBy(this, other, Position::pageIndex, Position::top)
    }

    private fun startOf(entry: OutlineEntry, depth: Int = 0): Position? {
        if (entry.pageIndex >= 0) return Position(entry.pageIndex, entry.top?.takeIf { it.isFinite() } ?: 0f)
        if (depth > MAX_DEPTH) return null
        return entry.children.mapNotNull { startOf(it, depth + 1) }.minOrNull()
    }

    private const val MAX_DEPTH = 64

    /** The outline with [top]s filled in by [locate] for the entries on [pageIndex] it can find. */
    fun withTops(outline: List<OutlineEntry>, pageIndex: Int, locate: (String) -> Float?): List<OutlineEntry> =
        outline.map { entry ->
            entry.copy(
                top = entry.top ?: if (entry.pageIndex == pageIndex) cleanTitle(entry.title).takeIf(String::isNotEmpty)?.let(locate) else null,
                children = withTops(entry.children, pageIndex, locate),
            )
        }

    /**
     * Where a heading titled [title] starts in a page's [raw] text (as Pdfium extracts it, lines
     * separated by line breaks): the character range of the first occurrence that begins a line,
     * possibly after a section number ("3.2 ", "A "), blanks compared collapsed and case kept.
     * Null when the title does not start any line. An occurrence that does not start a line is
     * running text, not the heading, and is never used.
     */
    fun headingRange(raw: String, title: String): IntRange? {
        val words = cleanTitle(title).split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return null
        val pattern = Regex(words.joinToString("\\s+") { Regex.escape(it) })
        var from = 0
        while (from < raw.length) {
            val match = pattern.find(raw, from) ?: return null
            val lineStart = raw.lastIndexOfAny(charArrayOf('\n', '\r'), match.range.first - 1).let { it + 1 }
            val lead = raw.substring(lineStart, match.range.first)
            if (lead.isBlank() || SECTION_NUMBER.matches(lead)) return match.range
            from = match.range.first + 1
        }
        return null
    }

    /** What may stand before a heading's title on its line: a section number and blanks. */
    private val SECTION_NUMBER = Regex("\\s*(?:[0-9]+|[A-Z]|[IVXLC]+)(?:\\.[0-9]+)*\\.?\\s+")
}
