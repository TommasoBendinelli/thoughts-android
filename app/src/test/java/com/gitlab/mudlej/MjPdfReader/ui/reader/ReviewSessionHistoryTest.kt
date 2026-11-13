// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionHistoryTest {

    @Test
    fun deletionRemovesEarlierUndoEntriesWithoutLosingOtherCards() {
        val history = ReviewSessionHistory()
        history.record("other", "event-other")
        history.record("deleted", "event-before-delete")
        history.recordSkip("deleted", "skip-before-delete")
        history.discardDeletedCard("deleted")
        assertEquals("other", history.removePrevious()?.cardId)
        assertFalse(history.canUndo)
    }

    @Test
    fun successfulRatingsAreReturnedLastInFirstOut() {
        val history = ReviewSessionHistory()
        history.record("card-one", "event-one")
        history.record("card-two", "event-two")

        assertEquals(ReviewSessionHistoryEntry.Rating("card-two", "event-two"), history.previous())
        assertEquals(ReviewSessionHistoryEntry.Rating("card-two", "event-two"), history.removePrevious())
        assertEquals(ReviewSessionHistoryEntry.Rating("card-one", "event-one"), history.previous())
    }

    @Test
    fun mixedActionsUndoInOrderWithoutJumpingToAnOlderSkip() {
        val history = ReviewSessionHistory()
        history.recordSkip("first", "skip-one")
        history.record("second", "rating-two")
        history.recordSkip("third", "skip-three", singleCardSession = true)

        assertEquals(ReviewSessionHistoryEntry.Skip("third", "skip-three", true), history.removePrevious())
        assertEquals(ReviewSessionHistoryEntry.Rating("second", "rating-two"), history.removePrevious())
        assertEquals(ReviewSessionHistoryEntry.Skip("first", "skip-one"), history.removePrevious())
        assertFalse(history.canUndo)
    }

    @Test
    fun consecutiveSkipsCanBothBeUndone() {
        val history = ReviewSessionHistory()
        history.recordSkip("first", "skip-one")
        history.recordSkip("second", "skip-two")

        assertEquals("second", history.removePrevious()?.cardId)
        assertEquals("first", history.removePrevious()?.cardId)
        assertNull(history.removePrevious())
    }

    @Test
    fun emptyHistoryDisablesPrevious() {
        val history = ReviewSessionHistory()

        assertFalse(history.canUndo)
        assertNull(history.previous())
        assertNull(history.removePrevious())
    }

    @Test
    fun removingPreviousUpdatesAvailability() {
        val history = ReviewSessionHistory()
        history.record("card", "event")

        assertTrue(history.canUndo)
        history.removePrevious()
        assertFalse(history.canUndo)
    }
}
