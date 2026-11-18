// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.SharedPreferences
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The before step is kept per card until that card's answer is recorded: a before note must not be lost
 * when a pending proposal makes the card unavailable for a while and the review goes on.
 */
class ReviewBeforeDraftsTest {
    private val answeredAt = Instant.parse("2026-11-09T08:00:00.500Z")
    private val note = "I remember the outline but not the details."

    @Test fun aBeforeStepSurvivesTheCardDroppingOutAndComingBack() {
        val preferences = MemoryPreferences()
        val drafts = ReviewBeforeDrafts(preferences)
        // The before step of "Method overview" is filled in, picks and note.
        val typed = ReviewAnswerDraft("method-overview").apply {
            beforeShown = true
            beforeTags = listOf("Fairly sure", "OK", "Phone")
            setNote(ReviewGroupNotes.BEFORE_NOTE_KEY, note)
        }
        drafts.put("method-overview", ReviewBeforeDrafts.of(typed, answeredAt))
        // The card becomes unavailable and the review moves on: another card, with its own draft.
        val other = ReviewAnswerDraft("other-card")
        assertTrue(other.beforeTags.isEmpty())
        assertNull("Nothing kept for a card never filled in", drafts.get("other-card", null))

        // Later (a new review, a new reader: only what is stored), the card comes back.
        val reopened = ReviewBeforeDrafts(preferences).get("method-overview", answeredAt)!!
        val shown = ReviewAnswerDraft("method-overview").apply { restoreBefore(reopened) }
        assertEquals(listOf("Fairly sure", "OK", "Phone"), shown.beforeTags)
        assertEquals(note, shown.note(ReviewGroupNotes.BEFORE_NOTE_KEY))
        assertTrue(shown.beforeShown)
        assertFalse("The answer was not shown: the step is open to change", shown.beforeLocked)
        // Recorded with the answer as the before note.
        assertEquals(mapOf(ReviewGroupNotes.BEFORE_NOTE_KEY to note), shown.recordedNotes())
    }

    @Test fun aStepWhoseAnswerWasShownComesBackLockedWithTheTimeItWasFirstShown() {
        val drafts = ReviewBeforeDrafts(MemoryPreferences())
        val shownAt = Instant.parse("2026-11-16T10:30:00.250Z")
        val typed = ReviewAnswerDraft("card").apply {
            beforeShown = true
            beforeTags = listOf("Unsure")
            answerShownAt = shownAt
        }
        drafts.put("card", ReviewBeforeDrafts.of(typed, null))
        val back = ReviewAnswerDraft("card").apply { restoreBefore(drafts.get("card", null)!!) }
        assertTrue(back.beforeLocked)
        assertEquals(shownAt, back.answerShownAt)

        // "Back to before step" was open when the card left: it comes back open, keeping that time.
        typed.beforeReopened = true
        drafts.put("card", ReviewBeforeDrafts.of(typed, null))
        val reopened = ReviewAnswerDraft("card").apply { restoreBefore(drafts.get("card", null)!!) }
        assertFalse(reopened.beforeLocked)
        assertEquals(shownAt, reopened.answerShownAt)
    }

    @Test fun aNewerAnswerMakesTheDraftObsolete() {
        val preferences = MemoryPreferences()
        val drafts = ReviewBeforeDrafts(preferences)
        drafts.put("card", ReviewBeforeDrafts.Draft(listOf("Sure"), "", null, false, answeredAt))
        // The card was answered meanwhile (here, or on the Mac and brought by Sync).
        assertNull(drafts.get("card", Instant.parse("2026-11-16T12:00:00Z")))
        assertFalse("The obsolete draft is dropped", preferences.contains("card"))
        // A card never answered keeps its draft until its first answer.
        drafts.put("new", ReviewBeforeDrafts.Draft(listOf("Guessing"), "", null, false, null))
        assertNull(drafts.get("new", answeredAt))
    }

    @Test fun clearingThePicksAndTheNoteClearsTheDraft() {
        val preferences = MemoryPreferences()
        val drafts = ReviewBeforeDrafts(preferences)
        drafts.put("card", ReviewBeforeDrafts.Draft(listOf("Sure"), "a note", null, false, answeredAt))
        assertTrue(preferences.contains("card"))
        drafts.put("card", ReviewBeforeDrafts.Draft(emptyList(), "   ", Instant.now(), false, answeredAt))
        assertFalse(preferences.contains("card"))
        // Recording the answer removes it too.
        drafts.put("card", ReviewBeforeDrafts.Draft(listOf("Sure"), "", null, false, answeredAt))
        drafts.remove("card")
        assertNull(drafts.get("card", answeredAt))
    }

    @Test fun onlyWhatThisVersionWroteIsRead() {
        assertNull(ReviewBeforeDrafts.decode(null))
        assertNull(ReviewBeforeDrafts.decode("not json"))
        assertNull(ReviewBeforeDrafts.decode("""{"version":2,"tags":["Sure"]}"""))
        val draft = ReviewBeforeDrafts.Draft(listOf("Sure", "OK"), "n", Instant.parse("2026-11-16T10:00:00.120Z"), true, answeredAt)
        assertEquals(draft, ReviewBeforeDrafts.decode(ReviewBeforeDrafts.encode(draft)))
    }

    private class MemoryPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableListOf<() -> Unit>()
            override fun putString(key: String, value: String?) = apply { changes += { values[key] = value } }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = apply { changes += { values.remove(key) } }
            override fun clear() = apply { changes += { values.clear() } }
            override fun commit(): Boolean { changes.forEach { it() }; return true }
            override fun apply() { commit() }
        }
    }
}
