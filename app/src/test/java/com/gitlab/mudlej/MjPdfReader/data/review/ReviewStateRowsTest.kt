// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.RectF
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewStateRowsTest {
    private fun row(active: Boolean = true, eligibleAt: Long = 1_000, reviews: Int = 3) = ReviewStateRow(
        pdfStem = "notes", active = active, eligibleAt = eligibleAt, documentId = "doc", dueAt = 2_000,
        reviewCount = reviews, currentMark = "Recalled", deleted = false, retained = false,
    )

    private fun card(schedule: ReviewSchedule?, initialDueAt: Instant? = null) = ReviewCard(
        id = "card", status = "approved", pdfStem = "notes", pdfSha256 = "a".repeat(64), page = 1,
        pageVisualSha256 = "b".repeat(64), question = "Q", rect = RectF(), schedule = schedule, reanchorState = "ok",
        initialDueAt = initialDueAt, documentId = "doc",
    )

    /** A source scan after a Sync: 300 cards, two of them moved in their source. */
    @Test
    fun afterASourceChangeOnlyTheCardsThatChangedAreWritten() {
        val stored = (1..300).associate { "card-$it" to row() }
        val rows = stored.toMutableMap()
        rows["card-7"] = row(active = false)
        rows["card-212"] = row(eligibleAt = 5_000)
        val toWrite = reviewStatesToWrite(rows, dirty = emptySet(), stored = stored)
        assertEquals(setOf("card-7", "card-212"), toWrite)
        // Every row was written before: 300 statements, now 2.
        assertEquals(300, rows.size)
    }

    @Test
    fun aCardWhoseRecordsChangedIsWrittenEvenWhenItsRowIsTheSame() {
        val stored = mapOf("a" to row(), "b" to row())
        // Writing the row is what clears the card's dirty flag.
        assertEquals(setOf("b"), reviewStatesToWrite(stored, dirty = setOf("b"), stored = stored))
    }

    @Test
    fun aCardWithoutAStoredRowIsWritten() {
        val rows = mapOf("old" to row(), "new" to row())
        assertEquals(setOf("new"), reviewStatesToWrite(rows, dirty = emptySet(), stored = mapOf("old" to row())))
    }

    @Test
    fun nothingChangedWritesNothing() {
        val stored = (1..50).associate { "card-$it" to row(reviews = it) }
        assertTrue(reviewStatesToWrite(stored, dirty = emptySet(), stored = stored).isEmpty())
    }

    @Test
    fun theRowHoldsWhatTheDueAndCountQueriesRead() {
        val due = Instant.ofEpochMilli(9_000)
        val scheduled = reviewStateRow(card(ReviewSchedule(dueAt = due, intervalDays = 4)), active = true,
            eligibleAt = 8_000, reviewCount = 2, mark = "Recalled")
        assertEquals(ReviewStateRow("notes", true, 8_000, "doc", 9_000, 2, "Recalled", deleted = false, retained = false), scheduled)
        // A card kept without reviews has no due time.
        val retained = reviewStateRow(card(ReviewSchedule(dueAt = due, intervalDays = 4, retained = true)), false, 0, 0, "")
        assertNull(retained.dueAt)
        assertTrue(retained.retained)
        assertTrue(reviewStateRow(card(ReviewSchedule(dueAt = due, intervalDays = 4, deleted = true)), false, 0, 0, "").deleted)
        // Never reviewed: due when it was first set to be.
        assertEquals(4_000L, reviewStateRow(card(null, initialDueAt = Instant.ofEpochMilli(4_000)), true, 0, 0, "").dueAt)
        // A card whose records are gone keeps an empty row.
        assertEquals(ReviewStateRow("", false, 0, "", null, 0, "", deleted = false, retained = false),
            reviewStateRow(null, false, 0, 0, ""))
    }
}
