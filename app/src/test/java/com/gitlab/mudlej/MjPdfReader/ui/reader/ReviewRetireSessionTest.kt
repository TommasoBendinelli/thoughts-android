// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.hidesPdfHighlight
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRetireSessionTest {

    @Test fun retireCardSitsDirectlyAboveDeleteCardInTheCardMenu() {
        val revealed = reviewSessionMenuActions(hasCard = true, answerVisible = true)
        assertEquals(listOf(
            ReviewSessionMenuAction.FOCUS, ReviewSessionMenuAction.MORE_INFO, ReviewSessionMenuAction.COPY_FOR_AGENT,
            ReviewSessionMenuAction.EDIT, ReviewSessionMenuAction.AGENT_CONVERSATIONS, ReviewSessionMenuAction.KIND,
            ReviewSessionMenuAction.SKIP, ReviewSessionMenuAction.UNDO, ReviewSessionMenuAction.HIDE_AGAIN, ReviewSessionMenuAction.RETIRE,
            ReviewSessionMenuAction.DELETE, ReviewSessionMenuAction.END,
        ), revealed)
        val hidden = reviewSessionMenuActions(hasCard = true, answerVisible = false)
        assertEquals(hidden.indexOf(ReviewSessionMenuAction.DELETE) - 1, hidden.indexOf(ReviewSessionMenuAction.RETIRE))
        assertFalse(ReviewSessionMenuAction.HIDE_AGAIN in hidden)
        // Flip is in the menu while the answer is covered (the bar has only Reveal), not once it shows.
        assertEquals(hidden.indexOf(ReviewSessionMenuAction.SKIP) + 1, hidden.indexOf(ReviewSessionMenuAction.FLIP))
        assertFalse(ReviewSessionMenuAction.FLIP in revealed)
        // Without a card (session finished) only Undo and End review remain.
        assertEquals(listOf(ReviewSessionMenuAction.UNDO, ReviewSessionMenuAction.END),
            reviewSessionMenuActions(hasCard = false, answerVisible = false))
        // Once the before step is locked it can be gone back to, with the answer shown or hidden.
        val locked = reviewSessionMenuActions(hasCard = true, answerVisible = false, beforeLocked = true)
        assertEquals(locked.indexOf(ReviewSessionMenuAction.UNDO) + 1, locked.indexOf(ReviewSessionMenuAction.BACK_TO_BEFORE))
        assertFalse(ReviewSessionMenuAction.BACK_TO_BEFORE in revealed)
        assertFalse(ReviewSessionMenuAction.BACK_TO_BEFORE in
            reviewSessionMenuActions(hasCard = false, answerVisible = false, beforeLocked = true))
        // Item ids stay as before; Retire has its own.
        assertEquals(4, ReviewSessionMenuAction.DELETE.id)
        assertEquals(ReviewSessionMenuAction.entries.size, ReviewSessionMenuAction.entries.map { it.id }.toSet().size)
    }

    @Test fun undoAfterRetireNamesTheRetireEventAndOtherUndoesKeepTheirOrder() {
        val history = ReviewSessionHistory()
        history.record("first", "rating-one")
        history.recordRetire("second", "retire-event")
        history.recordSkip("third", "skip-three")

        assertEquals(ReviewSessionHistoryEntry.Skip("third", "skip-three"), history.removePrevious())
        assertEquals(ReviewSessionHistoryEntry.Retire("second", "retire-event"), history.removePrevious())
        assertEquals(ReviewSessionHistoryEntry.Rating("first", "rating-one"), history.removePrevious())
        assertFalse(history.canUndo)
    }

    @Test fun deletingARetiredCardLaterDropsItsRetireFromUndo() {
        val history = ReviewSessionHistory()
        history.recordRetire("card", "retire-event")
        history.discardDeletedCard("card")
        assertFalse(history.canUndo)
    }

    @Test fun retiredCardsAreNotCountedAmongADocumentsCards() {
        val cards = listOf(card("a", ReviewBrowserState.NEW), card("b", ReviewBrowserState.REVIEW),
            card("retired", ReviewBrowserState.RETIRED, retiredAt = Instant.parse("2026-10-27T10:00:00Z")))
        assertEquals(DocumentCardCounts(total = 2, due = 1, new = 1, needsAttention = 0),
            documentCardCounts(cards, "manual.pdf", dueCount = 1))
    }

    /**
     * 13 Nov: retired cards stay on the PDF version they were retired on. The PDF shown neither
     * lists nor draws them, even when it is that version; the sheet lists them apart.
     */
    @Test fun retiredCardsLeaveThePdfShownForTheirOwnList() {
        assertFalse(ReviewBrowserState.RETIRED.hidesPdfHighlight())
        assertTrue(ReviewBrowserState.DELETED.hidesPdfHighlight())
        val active = card("active", ReviewBrowserState.REVIEW)
        val onThisVersion = card("retired-here", ReviewBrowserState.RETIRED, retiredAt = Instant.parse("2026-10-27T10:00:00Z"))
        val onOlderVersion = card("retired-before", ReviewBrowserState.RETIRED, retiredAt = Instant.parse("2026-11-12T10:00:00Z"),
            pdfSha256 = "0".repeat(64))
        val otherDocument = card("elsewhere", ReviewBrowserState.RETIRED, retiredAt = Instant.parse("2026-11-12T10:00:00Z"),
            pdfStem = "notes")
        val deleted = card("deleted", ReviewBrowserState.DELETED, retiredAt = Instant.parse("2026-11-01T10:00:00Z"))
        val cards = listOf(active, onThisVersion, onOlderVersion, otherDocument, deleted)

        assertEquals(listOf(active), documentReviewCards(cards, "manual.pdf", null, "a".repeat(64)))
        assertEquals("Most recently retired first, whatever their version",
            listOf(onOlderVersion, onThisVersion), retiredDocumentReviewCards(cards, "manual.pdf", null))
        assertTrue(retiredDocumentReviewCards(listOf(active), "manual.pdf", null).isEmpty())
    }

    private fun card(
        id: String,
        state: ReviewBrowserState,
        retiredAt: Instant? = null,
        pdfSha256: String = "a".repeat(64),
        pdfStem: String = "manual",
    ) = ReviewBrowserCard(
        card = ReviewCard(id = id, status = "approved", pdfStem = pdfStem, pdfSha256 = pdfSha256, page = 1,
            pageVisualSha256 = "b".repeat(64), question = "Q", rect = RectF(), schedule = null, reanchorState = "ok",
            retiredAt = retiredAt),
        state = state, dueAt = null, intervalDays = 0, reviewCount = 0, lapseCount = 0, lastRating = null,
        lastReviewedAt = null, createdAt = null, updatedAt = null, revision = null, sourcePath = null,
        pendingSync = false, deleteEventId = null, history = emptyList(),
    )
}
