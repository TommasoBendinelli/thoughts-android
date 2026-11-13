// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewLibrarySummary
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewQueueCounts
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePerformancePolicyTest {

    @Test
    fun eachTabRendersOnlyOncePerDirtyGeneration() {
        val tracker = HomeTabRenderTracker()

        HomeTab.entries.forEach { tab ->
            assertTrue(tracker.needsRender(tab))
            tracker.markRendered(tab)
            assertFalse(tracker.needsRender(tab))
        }

        tracker.markDirty(HomeTab.DIARY, HomeTab.LIBRARY)

        assertTrue(tracker.needsRender(HomeTab.DIARY))
        assertTrue(tracker.needsRender(HomeTab.LIBRARY))
        assertFalse(tracker.needsRender(HomeTab.REVIEW))
        tracker.markRendered(HomeTab.DIARY)
        assertFalse(tracker.needsRender(HomeTab.DIARY))
    }

    @Test
    fun selectionCleanupIsSkippedWhenNothingIsSelected() {
        assertFalse(shouldFinishOverviewSelection(0, backCallbackEnabled = false))
        assertTrue(shouldFinishOverviewSelection(1, backCallbackEnabled = true))
        assertTrue(shouldFinishOverviewSelection(0, backCallbackEnabled = true))
    }

    @Test
    fun tabSelectionRefreshesSearchOnlyWhileSearchIsVisible() {
        assertFalse(shouldRefreshHomeSearch(adapterInitialized = false, searchShowing = false))
        assertFalse(shouldRefreshHomeSearch(adapterInitialized = true, searchShowing = false))
        assertFalse(shouldRefreshHomeSearch(adapterInitialized = false, searchShowing = true))
        assertTrue(shouldRefreshHomeSearch(adapterInitialized = true, searchShowing = true))
    }

    @Test
    fun reviewLoadStateNeverInventsAnEmptySummary() {
        val initial = ReviewTabLoadState.Loading

        assertNull(initial.summary)
        assertNull(initial.beginRefresh().summary)
        assertNull(initial.failRefresh().summary)
    }

    @Test
    fun reviewRefreshAndFailureRetainTheLastLoadedSummary() {
        val summary = ReviewLibrarySummary(
            counts = ReviewQueueCounts(newCount = 3, learningCount = 2, reviewCount = 1),
            activeCount = 9,
        )
        val loaded = ReviewTabLoadState.Loaded(summary)
        val refreshing = loaded.beginRefresh()
        val failed = refreshing.failRefresh()

        assertTrue(refreshing is ReviewTabLoadState.Refreshing)
        assertTrue(failed is ReviewTabLoadState.Failed)
        assertSame(summary, refreshing.summary)
        assertSame(summary, failed.summary)
    }

    @Test
    fun reviewSummaryRefreshIsScheduledWhenItsNextCardBecomesDue() {
        val now = Instant.parse("2026-09-03T10:00:00Z").toEpochMilli()

        assertNull(reviewDueRefreshDelayMillis(null, now))
        assertEquals(
            5_000L,
            reviewDueRefreshDelayMillis(Instant.ofEpochMilli(now + 5_000L), now),
        )
        assertEquals(
            1L,
            reviewDueRefreshDelayMillis(Instant.ofEpochMilli(now - 1L), now),
        )
    }

    @Test
    fun commentRowsCarrySelectionAndDistinguishEmptyStates() {
        val comment = comment("comment-1")
        assertEquals(
            listOf(OverviewCommentRow.Comment(comment, selected = true)),
            overviewCommentRows(listOf(comment), setOf("paper\u0000comment-1"), hasAnyComments = true),
        )
        assertEquals(
            listOf(OverviewCommentRow.Empty(hasAnyComments = false)),
            overviewCommentRows(emptyList(), emptySet(), hasAnyComments = false),
        )
        assertEquals(
            listOf(OverviewCommentRow.Empty(hasAnyComments = true)),
            overviewCommentRows(emptyList(), emptySet(), hasAnyComments = true),
        )
    }

    @Test
    fun proposalDecisionStatusChangesOnlyTheAffectedDiffRowContents() {
        val first = comment("proposal-1")
        val second = comment("comment-2")

        assertEquals(
            listOf(
                OverviewCommentRow.Comment(first, selected = false, decisionStatus = "Pending"),
                OverviewCommentRow.Comment(second, selected = false),
            ),
            overviewCommentRows(
                listOf(first, second),
                emptySet(),
                hasAnyComments = true,
            ) { comment -> if (comment.id == "proposal-1") "Pending" else null },
        )
    }

    private fun comment(id: String) = OverviewComment(
        documentStem = "paper",
        id = id,
        parentId = "",
        contents = "Comment",
        selectedText = "",
        author = "Alex",
        sourceFile = "",
        sourceLine = 0,
        anchorStatus = "pdf_only",
        pageIndex = 0,
        subtype = "Text",
        created = "",
        modified = "",
        activityMillis = 1,
    )
}
