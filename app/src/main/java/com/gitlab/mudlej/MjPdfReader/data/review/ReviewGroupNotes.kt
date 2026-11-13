// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonObject

/**
 * Short notes an answer carries per tag group ("Understanding: the second step is still vague").
 * They travel with the review event as `review_group_notes`, an object mapping a group id to
 * its note; the implicit Other group uses the key [OTHER_KEY]. The answer's one free-form note
 * (the field at the end of the tag sheet) uses the reserved key [ANSWER_NOTE_KEY]; the note typed
 * before the answer was shown (review in two steps, [ReviewPhases]) the reserved key
 * [BEFORE_NOTE_KEY]. The field is left out when every note is empty. Like the tags, notes are plain
 * labels with no scheduling meaning.
 */
object ReviewGroupNotes {
    const val FIELD = "review_group_notes"
    /** The key of the implicit Other group (stored groups have UUIDs). */
    const val OTHER_KEY = "other"
    /** The key of the answer's free-form note: not a group; always listed last, as "Note". */
    const val ANSWER_NOTE_KEY = "note"
    const val ANSWER_NOTE_NAME = "Note"
    /** The key of the before panel's note: not a group; always listed first, as "Before note". */
    const val BEFORE_NOTE_KEY = "before_note"
    const val BEFORE_NOTE_NAME = "Before note"
    const val MAX_LENGTH = 500

    fun keyOf(group: ReviewTagGroups.Group?): String = group?.id ?: OTHER_KEY

    /** Notes as recorded: surrounding blanks trimmed, at most [MAX_LENGTH] characters, empty ones dropped. */
    fun normalize(notes: Map<String, String>): Map<String, String> = buildMap {
        notes.forEach { (key, text) ->
            val id = key.trim()
            val note = text.trim().take(MAX_LENGTH).trim()
            if (id.isNotEmpty() && note.isNotEmpty()) put(id, note)
        }
    }

    /** The event field, or null when there is no note (then the field is omitted). */
    fun toJson(notes: Map<String, String>): JsonObject? =
        normalize(notes).takeIf { it.isNotEmpty() }?.let { JsonObject().apply { it.forEach(::addProperty) } }

    /**
     * "<Group>: <note>" lines in the order of the current groups, then Other, then notes of groups
     * that no longer exist (shown by their id), then the answer's note ([ANSWER_NOTE_KEY], named
     * [noteName]) last; the before note ([BEFORE_NOTE_KEY], named [beforeNoteName]) comes first.
     * Names come from the current record; "other", "note" and "before_note" are never group ids.
     */
    fun lines(
        notes: Map<String, String>,
        groups: List<ReviewTagGroups.Group>,
        otherName: String = ReviewTagGroups.OTHER_NAME,
        noteName: String = ANSWER_NOTE_NAME,
        beforeNoteName: String = BEFORE_NOTE_NAME,
    ): List<String> {
        val reserved = setOf(OTHER_KEY, ANSWER_NOTE_KEY, BEFORE_NOTE_KEY)
        val order = groups.map { it.id }.filter { it !in reserved } + OTHER_KEY
        fun rank(key: String) = when (key) {
            ANSWER_NOTE_KEY -> Int.MAX_VALUE
            BEFORE_NOTE_KEY -> -1
            else -> order.indexOf(key).let { if (it < 0) Int.MAX_VALUE - 1 else it }
        }
        return notes.entries
            .sortedBy { (key, _) -> rank(key) }
            .map { (key, note) ->
                val name = when (key) {
                    OTHER_KEY -> otherName
                    ANSWER_NOTE_KEY -> noteName
                    BEFORE_NOTE_KEY -> beforeNoteName
                    else -> groups.firstOrNull { it.id == key }?.name ?: key
                }
                "$name: $note"
            }
    }
}

/** The answer's group notes; anything malformed is ignored. */
internal fun JsonObject.reviewGroupNotes(): Map<String, String> = runCatching {
    (get(ReviewGroupNotes.FIELD) as? JsonObject)?.entrySet()?.mapNotNull { (key, value) ->
        runCatching { key to value.asString }.getOrNull()
    }?.toMap()?.let(ReviewGroupNotes::normalize)
}.getOrNull().orEmpty()
