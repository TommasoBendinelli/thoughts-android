// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A layout synced while a card is up reaches its before step without restarting the app (2026-11-03:
 * the phone showed no before panel until it was restarted), and an unchanged layout leaves it alone.
 */
class ReviewBeforeStepRedrawTest {
    private val steps = listOf("Forgot", "Partly recalled", "Recalled")
    private val recall = ReviewTagGroups.Group("r", "Recall", steps, steps, phase = ReviewTagGroups.PHASE_BEFORE_AND_AFTER)
    private val feeling = ReviewTagGroups.Group("f", "Feeling", listOf("Meh", "OK"), listOf("Meh", "OK"), phase = ReviewTagGroups.PHASE_BEFORE)

    @Test fun aSyncThatAddsBeforeGroupsRedrawsTheCardOnScreen() {
        // Drawn with the layout of before the Sync: nothing asked before the answer.
        assertTrue(reviewBeforeStepNeedsRedraw(emptyList(), listOf(recall, feeling), sameAnswer = true))
        assertTrue(reviewBeforeStepNeedsRedraw(null, listOf(recall, feeling), sameAnswer = true))
    }

    @Test fun aSyncThatChangesOrDropsThemRedrawsToo() {
        assertTrue(reviewBeforeStepNeedsRedraw(listOf(recall, feeling), listOf(recall), sameAnswer = true))
        assertTrue(reviewBeforeStepNeedsRedraw(listOf(recall), listOf(recall.copy(scale = steps.take(2), tags = steps.take(2))), sameAnswer = true))
        assertTrue(reviewBeforeStepNeedsRedraw(listOf(recall, feeling), emptyList(), sameAnswer = true))
    }

    @Test fun anUnchangedLayoutKeepsThePanelAndTheNoteBeingTyped() {
        assertFalse(reviewBeforeStepNeedsRedraw(listOf(recall, feeling), listOf(recall, feeling), sameAnswer = true))
        assertFalse(reviewBeforeStepNeedsRedraw(emptyList(), emptyList(), sameAnswer = true))
    }

    @Test fun anotherAnswerIsAlwaysDrawn() {
        assertTrue(reviewBeforeStepNeedsRedraw(listOf(recall, feeling), listOf(recall, feeling), sameAnswer = false))
    }
}
