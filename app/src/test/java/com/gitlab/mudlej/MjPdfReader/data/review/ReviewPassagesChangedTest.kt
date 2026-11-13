// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPassagesChangedTest {
    private fun passage(content: String, id: String? = null) = JsonObject().apply {
        id?.let { addProperty("segment_id", it) }
        addProperty("content_sha256", content)
    }

    @Test fun olderCardsWithoutSegmentIdsNeedOnlyTheirMarker() {
        // Some older cards store no segment_id; the source scan always reports one.
        assertFalse(reviewPassagesChanged(listOf(passage("a")), listOf(passage("a", "0"))))
        assertFalse(reviewPassagesChanged(listOf(passage("a")), listOf(passage("b", "0"))))
        assertTrue(reviewPassagesChanged(listOf(passage("a")), emptyList()))
    }

    @Test fun editedTextBetweenTheMarkersKeepsTheCard() {
        assertFalse(reviewPassagesChanged(listOf(passage("a", "0")), listOf(passage("edited", "0"))))
        assertTrue(reviewPassagesChanged(listOf(passage("a", "0")), listOf(passage("a", "1"))))
        val card = listOf(passage("a", "0"), passage("b", "1"))
        assertFalse(reviewPassagesChanged(card, listOf(passage("a", "0"), passage("edited", "1"))))
    }

    @Test fun severalPassagesWithoutIdsStillCompareContent() {
        val card = listOf(passage("a"), passage("b"))
        assertFalse(reviewPassagesChanged(card, listOf(passage("a", "0"), passage("b", "1"))))
        assertTrue(reviewPassagesChanged(card, listOf(passage("a", "0"), passage("edited", "1"))))
    }

    @Test fun severalPassagesCompareByIdentityInAnyOrder() {
        val card = listOf(passage("a", "0"), passage("b", "1"))
        assertFalse(reviewPassagesChanged(card, listOf(passage("b", "1"), passage("a", "0"))))
        assertTrue(reviewPassagesChanged(card, listOf(passage("a", "0"))))
        assertTrue(reviewPassagesChanged(card, listOf(passage("a", "0"), passage("b", "2"))))
    }

    @Test fun editedOrRepairingCardsKeepTheirVerifiedPdfHighlight() {
        // A local source edit marks a card as needing attention, but its regions still match the PDF shown.
        assertFalse(ReviewBrowserState.NEEDS_ATTENTION.hidesPdfHighlight())
        assertFalse(ReviewBrowserState.REPAIRING.hidesPdfHighlight())
        assertFalse(ReviewBrowserState.REVIEW.hidesPdfHighlight())
        assertTrue(ReviewBrowserState.DELETED.hidesPdfHighlight())
        assertTrue(ReviewBrowserState.EXCLUDED.hidesPdfHighlight())
    }
}
