// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.content.SharedPreferences
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/**
 * The before step of each card, kept on this phone until that card's answer is recorded: the before
 * panel's picks, its note and when the answer was first shown. A card that drops out of the review
 * for a while (unavailable while a proposal in its passage is pending, skipped, the reader closed,
 * the app restarted) comes back with what was picked and typed, in the same review or a later one.
 *
 * A draft belongs to the card's answer in effect when it was started ([Draft.answeredAt], the card's
 * last answer then; null for a card never answered): once the card has another last answer, recorded
 * here or on another device and brought by Sync, the draft is obsolete and dropped. A before step
 * with no pick and no note keeps nothing, so clearing the picks and the note clears the draft.
 */
internal class ReviewBeforeDrafts(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    data class Draft(
        /** The before panel's picks, as [ReviewAnswerDraft.beforeTags]. */
        val tags: List<String>,
        /** The before note, as typed. */
        val note: String,
        /** When the answer was first shown; null while it never was. Set: the before step comes back locked. */
        val answerShownAt: Instant?,
        /** Opened again after the answer was shown ("Back to before step"), until it is shown again. */
        val reopened: Boolean,
        /** The card's last answer when the draft was started; null for a card never answered. */
        val answeredAt: Instant?,
    ) {
        val empty: Boolean get() = tags.isEmpty() && note.isBlank()
    }

    /** [cardId]'s draft, when it was started after the answer [answeredAt] (the card's last now); an obsolete one is dropped. */
    fun get(cardId: String, answeredAt: Instant?): Draft? {
        val draft = decode(preferences.getString(cardId, null)) ?: return null
        if (draft.answeredAt != answeredAt || draft.empty) {
            remove(cardId)
            return null
        }
        return draft
    }

    /** Keeps [draft] for [cardId]; an empty one removes it. */
    fun put(cardId: String, draft: Draft) {
        if (cardId.isBlank()) return
        if (draft.empty) remove(cardId)
        else preferences.edit().putString(cardId, encode(draft)).apply()
    }

    fun remove(cardId: String) {
        if (preferences.contains(cardId)) preferences.edit().remove(cardId).apply()
    }

    companion object {
        const val PREFERENCES = "review_before_drafts"
        private const val VERSION = 1

        /** The before step [draft] holds now, to be kept for the answer after [answeredAt]. */
        fun of(draft: ReviewAnswerDraft, answeredAt: Instant?): Draft = Draft(
            draft.beforeTags,
            draft.note(ReviewGroupNotes.BEFORE_NOTE_KEY),
            draft.answerShownAt,
            draft.beforeReopened,
            answeredAt,
        )

        fun encode(draft: Draft): String = JsonObject().apply {
            addProperty("version", VERSION)
            add("tags", JsonArray().apply { draft.tags.forEach(::add) })
            if (draft.note.isNotEmpty()) addProperty("note", draft.note)
            draft.answerShownAt?.let { addProperty("answer_shown_at", it.toString()) }
            if (draft.reopened) addProperty("reopened", true)
            draft.answeredAt?.let { addProperty("answered_at", it.toString()) }
            addProperty("saved_at", Instant.now().toString())
        }.toString()

        /** Null for anything this version did not write. */
        fun decode(encoded: String?): Draft? {
            if (encoded.isNullOrBlank()) return null
            return runCatching {
                val root = JsonParser.parseString(encoded).asJsonObject
                if (root.get("version")?.asInt != VERSION) return null
                fun instant(name: String) = root.get(name)?.asString?.let(Instant::parse)
                Draft(
                    tags = root.getAsJsonArray("tags")?.map { it.asString }.orEmpty(),
                    note = root.get("note")?.asString.orEmpty(),
                    answerShownAt = instant("answer_shown_at"),
                    reopened = root.get("reopened")?.asBoolean == true,
                    answeredAt = instant("answered_at"),
                )
            }.getOrNull()
        }
    }
}

/**
 * Gives a new [ReviewAnswerDraft] the before step kept for its card: the picks and note, and, when
 * the answer had been shown, the time it was first shown (the step comes back locked).
 */
internal fun ReviewAnswerDraft.restoreBefore(kept: ReviewBeforeDrafts.Draft) {
    beforeShown = true
    beforeTags = kept.tags
    setNote(ReviewGroupNotes.BEFORE_NOTE_KEY, kept.note)
    answerShownAt = kept.answerShownAt
    beforeReopened = kept.reopened && kept.answerShownAt != null
}
