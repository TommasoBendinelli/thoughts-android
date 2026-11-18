// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewIntervalUnit
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKindSource
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewCanFollowProject
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewIntervalExceedsProjectCap
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewKindChoiceText
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewKindMenuTitle
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewSwappedKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The review card menu's kind choices (protocol/review-card-kinds.md section 5). */
class ReviewKindMenuTest {
    @Test fun makeProjectOrKnowledgeCardIsOfferedWithEveryCardAndFollowTheProjectOnlyWhenItApplies() {
        val plain = reviewSessionMenuActions(hasCard = true, answerVisible = false)
        assertTrue(ReviewSessionMenuAction.KIND in plain)
        assertFalse(ReviewSessionMenuAction.FOLLOW_PROJECT in plain)
        val own = reviewSessionMenuActions(hasCard = true, answerVisible = true, followProject = true)
        assertEquals(own.indexOf(ReviewSessionMenuAction.KIND) + 1, own.indexOf(ReviewSessionMenuAction.FOLLOW_PROJECT))
        assertEquals(own.indexOf(ReviewSessionMenuAction.FOLLOW_PROJECT) + 1, own.indexOf(ReviewSessionMenuAction.SKIP))
        // Retire still sits directly above Delete.
        assertEquals(own.indexOf(ReviewSessionMenuAction.DELETE) - 1, own.indexOf(ReviewSessionMenuAction.RETIRE))
        assertEquals(listOf(ReviewSessionMenuAction.UNDO, ReviewSessionMenuAction.END),
            reviewSessionMenuActions(hasCard = false, answerVisible = false, followProject = true))
        assertEquals(18, ReviewSessionMenuAction.KIND.id)
        assertEquals(19, ReviewSessionMenuAction.FOLLOW_PROJECT.id)
    }

    private fun card(kind: String, source: ReviewKindSource, followed: String?) = ReviewCard("c", "approved", "paper", "a".repeat(64), 1,
        "b".repeat(64), "q", RectF(), null, "", reviewKind = kind, reviewKindSource = source, followedKind = followed)

    @Test fun theChoicesFollowTheCardsKindAndSource() {
        val project = card("project", ReviewKindSource.PROJECT, "project")
        assertEquals("knowledge", reviewSwappedKind(project))
        assertEquals(R.string.review_kind_make_knowledge, reviewKindMenuTitle(project))
        assertFalse(reviewCanFollowProject(project))
        val own = card("knowledge", ReviewKindSource.CARD, "project")
        assertEquals("project", reviewSwappedKind(own))
        assertEquals(R.string.review_kind_make_project, reviewKindMenuTitle(own))
        assertTrue(reviewCanFollowProject(own))
        // No Project holds it: there is nothing to follow.
        assertFalse(reviewCanFollowProject(card("project", ReviewKindSource.CARD, null)))
        assertEquals(R.string.review_history_made_project, reviewKindChoiceText("project"))
        assertEquals(R.string.review_history_made_knowledge, reviewKindChoiceText("knowledge"))
        assertEquals(R.string.review_history_follows_project, reviewKindChoiceText("follow_project"))
    }

    @Test fun anIntervalLongerThanAWeekIsCappedForAProjectCard() {
        val now = Instant.parse("2026-11-17T10:00:00Z")
        assertFalse(reviewIntervalExceedsProjectCap(ReviewInterval(1, ReviewIntervalUnit.WEEKS), now))
        assertFalse(reviewIntervalExceedsProjectCap(ReviewInterval(3, ReviewIntervalUnit.DAYS), now))
        assertTrue(reviewIntervalExceedsProjectCap(ReviewInterval(2, ReviewIntervalUnit.WEEKS), now))
    }
}
