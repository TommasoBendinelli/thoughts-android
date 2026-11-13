// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The card browser, card details and History for retired cards. */
class ReviewRetireBrowserTest {
    private val at = Instant.parse("2026-10-27T10:00:00Z")

    @Test fun retiredFilterListsOnlyRetiredCardsAndScheduleFiltersLeaveThemOut() {
        val retired = browserCard("retired", ReviewBrowserState.RETIRED, retiredAt = at)
        val retiredNever = browserCard("retired-never", ReviewBrowserState.RETIRED, retiredAt = at,
            schedule = ReviewSchedule(at, 0, retained = true))
        val fresh = browserCard("fresh", ReviewBrowserState.NEW)
        val never = browserCard("never", ReviewBrowserState.RETAINED, schedule = ReviewSchedule(at, 0, retained = true))
        val all = listOf(retired, retiredNever, fresh, never)
        fun ids(filter: ReviewCardBrowserFilter) =
            reviewCardBrowserResult(all, null, "", filter, ReviewCardBrowserSort.DUE).cards.map { it.card.id }.toSet()
        assertEquals(setOf("retired", "retired-never"), ids(ReviewCardBrowserFilter.RETIRED))
        assertEquals(setOf("fresh", "never"), ids(ReviewCardBrowserFilter.NEW))
        assertEquals(setOf("never"), ids(ReviewCardBrowserFilter.NEVER))
        assertEquals(setOf("fresh"), ids(ReviewCardBrowserFilter.DUE))
        assertEquals(setOf("fresh"), ids(ReviewCardBrowserFilter.ACTIVE))
        assertEquals(all.map { it.card.id }.toSet(), ids(ReviewCardBrowserFilter.ALL))
        assertEquals(ReviewCardBrowserFilter.RETIRED, reviewCardBrowserFilterFromStoredName("RETIRED"))
        assertEquals(R.string.review_card_browser_retired_or_unpublished, ReviewCardBrowserFilter.RETIRED.labelRes())
        assertEquals(R.string.review_card_browser_retired, ReviewBrowserState.RETIRED.labelRes())
    }

    @Test fun retiredCardsCannotBeReviewedOrExportedForStudy() {
        val retired = browserCard("retired", ReviewBrowserState.RETIRED, retiredAt = at)
        assertFalse(retired.canReview())
        assertEquals(emptyList<ReviewBrowserCard>(), reviewQaExportCards(listOf(retired), "deck"))
    }

    @Test fun historyNamesRetireRestoreAndUndo() {
        fun entry(action: String) = ReviewHistoryEntry("e", null, at, "phone", null, null, null,
            retracted = false, effective = false, pendingSync = false, sourcePath = null, queueAction = action)
        assertEquals(R.string.review_history_retire, entry("retire").historyMarkRes())
        assertEquals(R.string.review_history_restore, entry("restore").historyMarkRes())
        assertEquals(R.string.review_history_undo_retire, entry("undo_retire").historyMarkRes())
        // Skips keep their labels.
        assertEquals(R.string.review_history_skip, entry("skip").historyMarkRes())
        assertEquals(R.string.review_history_undo_skip, entry("undo_skip").historyMarkRes())
        val undo = entry("undo_retire").copy(undoneEventId = "retire-1")
        assertEquals(true, undo.technicalText().contains("undone_event_id=retire-1"))
    }

    @Test fun menusOfferRetireOnActiveCardsAndRestoreOnRetiredOnes() {
        assertEquals(R.string.review_card_retire_menu, reviewRetireMenuTitle(retired = false))
        assertEquals(R.string.review_card_restore_menu, reviewRetireMenuTitle(retired = true))
        val active = browserCard("active", ReviewBrowserState.REVIEW)
        val retired = browserCard("retired", ReviewBrowserState.RETIRED, retiredAt = at)
        val deleted = browserCard("deleted", ReviewBrowserState.DELETED).copy(deleteEventId = "delete")
        val candidate = browserCard("candidate", ReviewBrowserState.CANDIDATE, status = "candidate")
        val unselected = browserCard("unselected", ReviewBrowserState.NEW)
        val shown = listOf(active, retired, deleted, candidate, unselected)
        assertEquals(listOf("active") to listOf("retired"),
            reviewRetireSelection(shown, setOf("active", "retired", "deleted", "candidate")))
        assertEquals(emptyList<String>() to listOf("retired"), reviewRetireSelection(shown, setOf("retired")))
    }

    @Test fun retiredDateIsTheLocalDay() {
        assertEquals("2026-10-27", reviewRetiredDate(at, ZoneOffset.UTC))
        assertEquals("2026-10-28", reviewRetiredDate(Instant.parse("2026-10-27T23:30:00Z"), ZoneId.of("Europe/Zurich")))
    }

    private fun browserCard(
        id: String,
        state: ReviewBrowserState,
        retiredAt: Instant? = null,
        schedule: ReviewSchedule? = null,
        status: String = "approved",
    ) = ReviewBrowserCard(
        card = ReviewCard(id = id, status = status, pdfStem = "deck", pdfSha256 = "a".repeat(64), page = 1,
            pageVisualSha256 = "b".repeat(64), question = "Question $id", rect = RectF(), schedule = schedule,
            reanchorState = "ok", retiredAt = retiredAt, retireEventId = retiredAt?.let { "retire-$id" }),
        state = state, dueAt = null, intervalDays = 0, reviewCount = 0, lapseCount = 0, lastRating = null,
        lastReviewedAt = null, createdAt = null, updatedAt = null, revision = null, sourcePath = null,
        pendingSync = false, deleteEventId = null, history = emptyList(),
    )
}
