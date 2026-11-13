// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextCommentMarkerGeometryTest {

    @Test
    public void centersFixedSizeBubbleInsideRectangularBounds() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.centeredBubble(
                10f, 20f, 50f, 80f, 20f);

        assertEquals(20f, marker.left, 0f);
        assertEquals(40f, marker.top, 0f);
        assertEquals(40f, marker.right, 0f);
        assertEquals(60f, marker.bottom, 0f);
        assertEquals(56f, marker.bodyBottom, 0f);
    }

    @Test
    public void placesBubbleAboveAndLeftWithTailAtHighlightStart() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.atHighlightStart(
                100f, 40f, 60f,
                0f, 0f, 600f, 800f,
                16f);

        assertEquals(95.6f, marker.left, 0.001f);
        assertEquals(24f, marker.top, 0f);
        assertEquals(111.6f, marker.right, 0.001f);
        assertEquals(40f, marker.bottom, 0f);
        assertEquals(100f, marker.tailTipX, 0f);
        assertFalse(marker.tailOnTop);
    }

    @Test
    public void clampsHighlightMarkerInsideLeftPageEdge() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.atHighlightStart(
                2f, 40f, 60f,
                0f, 0f, 600f, 800f,
                16f);

        assertEquals(0f, marker.left, 0f);
        assertEquals(16f, marker.right, 0f);
        assertEquals(24f, marker.top, 0f);
        assertEquals(40f, marker.bottom, 0f);
        assertEquals(2f, marker.tailTipX, 0f);
    }

    @Test
    public void placesHighlightMarkerBelowLineWhenTopEdgeHasNoRoom() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.atHighlightStart(
                100f, 5f, 15f,
                0f, 0f, 600f, 800f,
                16f);

        assertEquals(95.6f, marker.left, 0.001f);
        assertEquals(111.6f, marker.right, 0.001f);
        assertEquals(15f, marker.top, 0f);
        assertEquals(31f, marker.bottom, 0f);
        assertEquals(18.2f, marker.bodyTop, 0.001f);
        assertEquals(31f, marker.bodyBottom, 0f);
        assertTrue(marker.tailOnTop);
    }

    @Test
    public void clampsHighlightMarkerInsideRightPageEdge() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.atHighlightStart(
                595f, 40f, 60f,
                0f, 0f, 600f, 800f,
                16f);

        assertEquals(584f, marker.left, 0f);
        assertEquals(600f, marker.right, 0f);
        assertEquals(595f, marker.tailTipX, 0f);
    }

    @Test
    public void placesTailOnLowerLeftOfBubble() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.centeredBubble(
                10f, 20f, 50f, 80f, 20f);

        assertEquals(24f, marker.tailShoulderLeft, 0f);
        assertEquals(25.5f, marker.tailTipX, 0f);
        assertEquals(29.5f, marker.tailShoulderRight, 0f);
        assertEquals(60f, marker.bottom, 0f);
    }

    @Test
    public void shadowOffsetMovesWholeBubble() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.centeredBubble(
                10f, 20f, 50f, 80f, 20f);
        TextCommentMarkerGeometry.Bubble shadow = marker.offset(0f, 1f);

        assertEquals(20f, shadow.left, 0f);
        assertEquals(41f, shadow.top, 0f);
        assertEquals(57f, shadow.bodyBottom, 0f);
        assertEquals(61f, shadow.bottom, 0f);
        assertEquals(25.5f, shadow.tailTipX, 0f);
    }

    @Test
    public void negativeSizeCannotInvertBubble() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.centeredBubble(
                50f, 80f, 10f, 20f, -20f);

        assertEquals(30f, marker.left, 0f);
        assertEquals(50f, marker.top, 0f);
        assertEquals(30f, marker.right, 0f);
        assertEquals(50f, marker.bottom, 0f);
        assertEquals(50f, marker.bodyBottom, 0f);
    }

    @Test
    public void placesUnresolvedBadgeInsideUpperRightCorner() {
        TextCommentMarkerGeometry.Bubble marker = TextCommentMarkerGeometry.centeredBubble(
                10f, 20f, 50f, 80f, 20f);
        TextCommentMarkerGeometry.Badge badge = TextCommentMarkerGeometry.upperRightBadge(
                marker, 6f, 1f);

        assertEquals(36f, badge.centerX, 0f);
        assertEquals(44f, badge.centerY, 0f);
        assertEquals(3f, badge.radius, 0f);
    }
}
