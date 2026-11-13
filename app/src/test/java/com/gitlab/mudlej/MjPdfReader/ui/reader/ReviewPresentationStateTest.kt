// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewIntervalUnit
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The review in progress survives the reader being destroyed while the user is in another app. */
class ReviewPresentationStateTest {
    @Test fun theAnswerInProgressRoundTripsThroughTheSavedState() {
        val draft = ReviewAnswerDraft("card-1").apply {
            settle(listOf("g1", "other"))
            toggle("g1")
            setNote("g1", "mixed up the two techniques")
            setNote("other", "see slide 12")
        }
        val state = ReviewPresentationState(
            cardId = "card-1",
            hidden = false,
            answerRevealed = true,
            answerShown = false,
            maskFlipped = true,
            selectedTags = listOf("fair", "plus-one"),
            interval = ReviewInterval(BigDecimal("1.5"), ReviewIntervalUnit.WEEKS),
            draft = draft.snapshot(),
        )
        val decoded = ReviewPresentationState.decode(state.encode())
        assertEquals(state, decoded)
        val restored = ReviewAnswerDraft.restored("card-1", decoded!!.draft)
        assertEquals("card-1", restored.cardId)
        assertEquals("g1", restored.expanded)
        assertEquals(draft.recordedNotes(), restored.recordedNotes())
        // The user had opened a row, so a lone row does not reopen on its own.
        restored.settle(listOf("g1", "other"))
        assertEquals("g1", restored.expanded)
    }

    @Test fun theBeforeStepRoundTrips() {
        val draft = ReviewAnswerDraft("card-4").apply {
            beforeShown = true
            beforeTags = listOf("Meh", "Partly recalled")
            setNote("before_note", "felt shaky")
            answerShownAt = java.time.Instant.parse("2026-11-03T12:00:05.120Z")
        }
        val state = ReviewPresentationState(cardId = "card-4", answerShown = true, selectedTags = listOf("Partly recalled"),
            draft = draft.snapshot())
        val decoded = ReviewPresentationState.decode(state.encode())
        assertEquals(state, decoded)
        val restored = ReviewAnswerDraft.restored("card-4", decoded!!.draft)
        assertEquals(true, restored.beforeShown)
        assertEquals(listOf("Meh", "Partly recalled"), restored.beforeTags)
        assertEquals(java.time.Instant.parse("2026-11-03T12:00:05.120Z"), restored.answerShownAt)
        assertEquals(true, restored.beforeLocked)
        assertEquals(mapOf("before_note" to "felt shaky"), restored.recordedNotes())
        // A state saved before answers had two steps: no before step, nothing locked.
        val older = ReviewPresentationState.decode(
            """{"version":1,"card_id":"card-5","selected_tags":[],"draft":{"touched":false,"notes":{}}}""")!!
        val plain = ReviewAnswerDraft.restored("card-5", older.draft)
        assertEquals(false, plain.beforeShown)
        assertEquals(emptyList<String>(), plain.beforeTags)
        assertNull(plain.answerShownAt)
    }

    @Test fun contextNoteEditsAndTheShownScopeRoundTrip() {
        val draft = ReviewAnswerDraft("card-3").apply {
            settleContext(mapOf(
                ReviewContextNotes.Scope.PAGE to ReviewContextNotes.Target("7", "Page 7"),
                ReviewContextNotes.Scope.DOCUMENT to ReviewContextNotes.Target("", "Paper"),
            ), mapOf(ReviewContextNotes.Scope.PAGE to "old"))
            setContextText(ReviewContextNotes.Scope.PAGE, "new")
            setContextText(ReviewContextNotes.Scope.DOCUMENT, "whole paper")
            contextScope = ReviewContextNotes.Scope.DOCUMENT
        }
        val state = ReviewPresentationState(cardId = "card-3", answerRevealed = true, draft = draft.snapshot())
        val decoded = ReviewPresentationState.decode(state.encode())
        assertEquals(state, decoded)
        val restored = ReviewAnswerDraft.restored("card-3", decoded!!.draft)
        assertEquals(draft.recordedContextNotes(), restored.recordedContextNotes())
        assertEquals(ReviewContextNotes.Scope.DOCUMENT, restored.contextScope)
        assertEquals("new", restored.contextText(ReviewContextNotes.Scope.PAGE))
    }

    @Test fun noTagsAndADateIntervalAreKept() {
        val state = ReviewPresentationState(
            cardId = "card-2",
            answerShown = true,
            selectedTags = emptyList(),
            interval = ReviewInterval(BigDecimal.ONE, ReviewIntervalUnit.DAYS, LocalDate.of(2026, 11, 3)),
        )
        assertEquals(state, ReviewPresentationState.decode(state.encode()))
    }

    @Test fun missingOrForeignStateOpensTheCardAnew() {
        assertNull(ReviewPresentationState.decode(null))
        assertNull(ReviewPresentationState.decode(""))
        assertNull(ReviewPresentationState.decode("not json"))
        assertNull(ReviewPresentationState.decode("""{"version":99,"card_id":"x"}"""))
        assertNull(ReviewPresentationState.decode("""{"version":1}"""))
    }
}
