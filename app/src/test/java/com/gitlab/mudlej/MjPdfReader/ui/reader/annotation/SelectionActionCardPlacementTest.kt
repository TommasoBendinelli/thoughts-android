// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionActionCardPlacementTest {

    @Test
    fun bottomReviewOverlayForcesActionsToTop() {
        assertTrue(
            shouldPlaceSelectionActionsAtTop(
                selectionCenterY = 200f,
                pdfHeight = 1_000,
                bottomOverlayVisible = true,
            ),
        )
    }

    @Test
    fun selectionNearBottomPlacesActionsAtTop() {
        assertTrue(
            shouldPlaceSelectionActionsAtTop(
                selectionCenterY = 700f,
                pdfHeight = 1_000,
                bottomOverlayVisible = false,
            ),
        )
    }

    @Test
    fun ordinarySelectionWithoutOverlayKeepsActionsAtBottom() {
        assertFalse(
            shouldPlaceSelectionActionsAtTop(
                selectionCenterY = 300f,
                pdfHeight = 1_000,
                bottomOverlayVisible = false,
            ),
        )
    }

    @Test
    fun actionsStayAboveTheNavigationBarTheReaderIsDrawnUnder() {
        // Root 2392 tall with a 132 navigation bar, no overlay: above 2260.
        assertEquals(2_216, selectionActionsAreaBottom(2_392, 132, overlayTop = null, margin = 44))
        // The study mode bar (which pads itself over the navigation bar) is higher: above it.
        assertEquals(1_923, selectionActionsAreaBottom(2_392, 132, overlayTop = 1_967, margin = 44))
        // Fullscreen hides the navigation bar.
        assertEquals(2_348, selectionActionsAreaBottom(2_392, 0, overlayTop = null, margin = 44))
    }

    // Area 100..1000 (above the review card), card 200 tall: top edge 100..300, bottom edge 800..1000.
    private fun top(selectionTop: Float?, selectionBottom: Float?, preferTop: Boolean = true) = selectionActionsTop(
        selectionTop = selectionTop,
        selectionBottom = selectionBottom,
        cardHeight = 200,
        areaTop = 100,
        areaBottom = 1_000,
        preferTop = preferTop,
    )

    @Test
    fun actionsStayOnTheirEdgeWhenTheSelectionIsClear() {
        assertEquals(100, top(500f, 540f))
        assertEquals(800, top(300f, 340f, preferTop = false))
        assertEquals(100, top(null, null))
    }

    @Test
    fun selectionUnderTheTopEdgeMovesActionsToTheBottomEdge() {
        assertEquals(800, top(150f, 190f))
    }

    @Test
    fun selectionUnderTheBottomEdgeMovesActionsToTheTopEdge() {
        assertEquals(100, top(850f, 890f, preferTop = false))
    }

    @Test
    fun selectionReachingBothEdgesTakesTheEdgeCoveringLessOfIt() {
        // Top edge covers 250..300 (50), bottom edge 800..850 (50): the preferred one.
        assertEquals(100, top(250f, 850f))
        assertEquals(800, top(250f, 850f, preferTop = false))
        // Top edge covers 150..300 (150), bottom edge 800..820 (20).
        assertEquals(800, top(150f, 820f))
    }
}
