// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A valid card is never called unavailable because the reader is reloading or was resumed. */
class ReviewOpenDecisionTest {
    private val card = "ffa514e6ce18" + "a".repeat(52)
    private val other = "b".repeat(64)

    private fun outcome(
        shown: String = card,
        pages: Int = 12,
        installed: String? = card,
        reloadRequested: Boolean = false,
        waited: Boolean = false,
        placingAgain: Boolean = false,
        areas: Boolean = true,
    ) = reviewOpenOutcome(shown, pages, card, areas, installed, reloadRequested, waited, placingAgain)

    @Test fun theCardsOwnPdfShowsIt() {
        assertEquals(ReviewOpenOutcome.SHOW, outcome())
        assertEquals(ReviewOpenOutcome.SHOW, outcome(installed = null))
        assertEquals("Hashes compare case-insensitively", ReviewOpenOutcome.SHOW, outcome(shown = card.uppercase()))
    }

    @Test fun aPdfStillLoadingIsWaitedFor() {
        // The 2026-10-14 report: back from another app, the reader reloaded the imported PDF and
        // the card was dropped while the PDF's hash or pages were not there yet.
        assertEquals(ReviewOpenOutcome.WAIT_FOR_DOCUMENT, outcome(shown = ""))
        assertEquals(ReviewOpenOutcome.WAIT_FOR_DOCUMENT, outcome(pages = 0))
        assertEquals(ReviewOpenOutcome.WAIT_FOR_DOCUMENT, outcome(shown = "", installed = null))
    }

    @Test fun aPdfThatNeverLoadsEndsTheWait() {
        assertEquals(ReviewOpenOutcome.RELOAD_INSTALLED, outcome(shown = "", waited = true))
        assertEquals(ReviewOpenOutcome.STALE, outcome(shown = "", waited = true, reloadRequested = true))
        assertEquals(ReviewOpenOutcome.STALE, outcome(pages = 0, waited = true, installed = null))
    }

    @Test fun anOlderPdfShownReloadsTheInstalledOneOnce() {
        assertEquals(ReviewOpenOutcome.RELOAD_INSTALLED, outcome(shown = other))
        assertEquals(ReviewOpenOutcome.STALE, outcome(shown = other, reloadRequested = true))
    }

    @Test fun onlyAReallyDifferentPdfMakesTheCardUnavailable() {
        assertEquals(ReviewOpenOutcome.STALE, outcome(shown = other, installed = other))
        assertEquals(ReviewOpenOutcome.STALE, outcome(shown = other, installed = null))
        assertEquals(ReviewOpenOutcome.STALE, outcome(areas = false))
        assertEquals("Placing a card again uses the PDF shown",
            ReviewOpenOutcome.SHOW, outcome(shown = other, installed = other, placingAgain = true))
    }

    @Test fun comingBackToTheSameCardDoesNotOpenItAgain() {
        val document = "file:///library/DFlash.pdf" to card
        fun shown(
            shownId: String? = "c1", active: Boolean = true, pending: Boolean = false,
            shownFor: Pair<Any?, String?>? = document, current: Pair<Any?, String?> = document,
            pages: Int = 12, placingAgain: Boolean = false,
        ) = reviewCardAlreadyShown(shownId, "c1", active, pending, shownFor, current, pages, placingAgain)
        assertTrue(shown())
        assertFalse("Another card", shown(shownId = "c2"))
        assertFalse("No card yet", shown(shownId = null))
        assertFalse("Review ended", shown(active = false))
        assertFalse("An open is under way", shown(pending = true))
        assertFalse("The PDF was replaced", shown(current = document.first to other))
        assertFalse("Another document", shown(current = "file:///library/other.pdf" to card))
        assertFalse("The PDF is reloading", shown(pages = 0))
        assertFalse("Place this card again edits it", shown(placingAgain = true))
    }
}
