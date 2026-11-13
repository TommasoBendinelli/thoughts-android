// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagScales
import java.time.Instant

/**
 * What the tag sheet keeps for one answer while it is open or closed again: the note typed for
 * each group, and the section, page and document notes of "About this…" ([context]). Next records
 * the notes with the tags; the next card starts with a fresh draft. In a review in two steps it also
 * keeps the before panel's picks ([beforeTags]; its note is the group note
 * [ReviewGroupNotes.BEFORE_NOTE_KEY]) and when the answer was first shown, which locks them.
 * [expanded] is no longer read by the sheet (open groups are remembered per phone, in
 * [ReviewTagGroupFolding]); it stays so saved review state keeps restoring.
 */
internal class ReviewAnswerDraft(val cardId: String? = null) {
    /** The key ([ReviewGroupNotes.keyOf]) of the open group row, or null when all are closed. */
    var expanded: String? = null
        private set
    private var touched = false
    private val notes = linkedMapOf<String, String>()

    /** Tapping a row opens it and closes the others; tapping the open row closes it. */
    fun toggle(key: String) {
        touched = true
        expanded = if (expanded == key) null else key
    }

    /** With a single row (no groups yet: only Other) it starts open, until the user closes it. */
    fun settle(keys: List<String>) {
        if (expanded != null && expanded !in keys) expanded = null
        if (!touched && expanded == null && keys.size == 1) expanded = keys.single()
    }

    fun note(key: String): String = notes[key].orEmpty()

    fun setNote(key: String, text: String) {
        val note = text.take(ReviewGroupNotes.MAX_LENGTH)
        if (note.isBlank()) notes.remove(key) else notes[key] = note
    }

    fun hasNote(key: String): Boolean = notes[key]?.isNotBlank() == true

    /** The notes Next records (trimmed, empty ones left out). */
    fun recordedNotes(): Map<String, String> = ReviewGroupNotes.normalize(notes)

    /**
     * What "About this…" is about for this card, per scope (a scope without a target, e.g. Section in
     * a PDF without headings, is not offered), with each note's text when the sheet first opened.
     */
    var context: ContextSnapshot? = null
        private set
    /** The text typed for each scope ([ReviewContextNotes.Scope.wire]); a scope never edited is absent. */
    private val contextEdits = linkedMapOf<String, String>()
    /** The scope the selector shows; null until it is chosen. */
    var contextScope: ReviewContextNotes.Scope? = null

    /**
     * Sets what each scope is about and the notes' current texts, the first time the sheet opens for
     * the answer; later openings keep them, so edits are compared with what the answer started from.
     */
    fun settleContext(targets: Map<ReviewContextNotes.Scope, ReviewContextNotes.Target>, current: Map<ReviewContextNotes.Scope, String>) {
        if (context != null) return
        context = ContextSnapshot(
            targets.mapKeys { it.key.wire }.mapValues { (_, target) -> ContextTarget(target.key, target.label) },
            current.mapKeys { it.key.wire },
        )
    }

    /** The text the box shows for [scope]: what was typed, else the note's current text. */
    fun contextText(scope: ReviewContextNotes.Scope): String =
        contextEdits[scope.wire] ?: context?.current?.get(scope.wire).orEmpty()

    fun setContextText(scope: ReviewContextNotes.Scope, text: String) {
        contextEdits[scope.wire] = text.take(ReviewContextNotes.MAX_LENGTH)
    }

    /**
     * The scopes Next records: those whose cleaned text differs from the note's text when the sheet
     * opened ("" clears a note). Nothing when the sheet never offered the block.
     */
    fun recordedContextNotes(): Map<ReviewContextNotes.Scope, ReviewContextNotes.Note> {
        val context = context ?: return emptyMap()
        return buildMap {
            ReviewContextNotes.Scope.entries.forEach { scope ->
                val target = context.targets[scope.wire] ?: return@forEach
                val typed = contextEdits[scope.wire] ?: return@forEach
                val text = ReviewContextNotes.clean(typed)
                if (text != ReviewContextNotes.clean(context.current[scope.wire].orEmpty()))
                    put(scope, ReviewContextNotes.Note(target.key, target.label, text))
            }
        }
    }

    /** True once the before panel was shown with a group to pick in: the answer is given in two steps. */
    var beforeShown = false
    /** The before panel's picks; nothing carries over from an earlier answer. */
    var beforeTags: List<String> = emptyList()
    /**
     * When the answer was first shown (Reveal or Set knowledge); from then on the before panel is
     * locked. Going back to the panel keeps this time: it is when the answer was really first seen.
     */
    var answerShownAt: Instant? = null
    /** True from "Back to before step" until the answer is shown again: the panel is open for changes. */
    var beforeReopened = false
    val beforeLocked: Boolean get() = answerShownAt != null && !beforeReopened

    /** Everything the draft holds, as it is saved while the app is in the background. */
    fun snapshot(): Snapshot = Snapshot(expanded, touched, LinkedHashMap(notes),
        context?.copy(edits = LinkedHashMap(contextEdits), scope = contextScope?.wire),
        beforeShown, beforeTags, answerShownAt?.toString(), beforeReopened)

    data class Snapshot(
        val expanded: String?,
        val touched: Boolean,
        val notes: Map<String, String>,
        val context: ContextSnapshot? = null,
        val beforeShown: Boolean = false,
        val beforeTags: List<String> = emptyList(),
        /** ISO-8601, as [Instant.toString] writes it. */
        val answerShownAt: String? = null,
        val beforeReopened: Boolean = false,
    )

    data class ContextTarget(val key: String, val label: String)

    /** "About this…" for one answer: targets and current texts by scope wire name, edits, the shown scope. */
    data class ContextSnapshot(
        val targets: Map<String, ContextTarget>,
        val current: Map<String, String>,
        val edits: Map<String, String> = emptyMap(),
        val scope: String? = null,
    )

    companion object {
        /** The draft of [cardId] as it was saved ([snapshot]). */
        fun restored(cardId: String, snapshot: Snapshot): ReviewAnswerDraft = ReviewAnswerDraft(cardId).apply {
            expanded = snapshot.expanded
            touched = snapshot.touched
            snapshot.notes.forEach { (key, text) -> setNote(key, text) }
            beforeShown = snapshot.beforeShown
            beforeTags = snapshot.beforeTags
            answerShownAt = snapshot.answerShownAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            beforeReopened = snapshot.beforeReopened
            snapshot.context?.let { saved ->
                context = saved.copy(edits = emptyMap(), scope = null)
                saved.edits.forEach { (scope, text) -> ReviewContextNotes.Scope.of(scope)?.let { setContextText(it, text) } }
                contextScope = saved.scope?.let(ReviewContextNotes.Scope::of)
            }
        }
    }
}

/** The row's one-line summary: the chosen tags of this group, in the group's order ("Fair, Important"). */
internal fun reviewTagGroupSummary(groupTags: List<String>, chosen: Collection<String>, label: (String) -> String): String =
    groupTags.filter { it in chosen }.joinToString(", ", transform = label)

/**
 * The review bar's tag cell: [tags] in order ([ReviewTagScales.summaryTags]), at most two named,
 * joined with " · ", the rest counted ([more]: "I get it · Recalled +2"); [none] when there are none.
 */
internal fun reviewTagCellText(
    tags: List<String>,
    label: (String) -> String,
    none: String,
    more: (String, Int) -> String = { text, count -> "$text +$count" },
): String {
    if (tags.isEmpty()) return none
    val named = tags.take(2).joinToString(" · ", transform = label)
    return if (tags.size <= 2) named else more(named, tags.size - 2)
}
