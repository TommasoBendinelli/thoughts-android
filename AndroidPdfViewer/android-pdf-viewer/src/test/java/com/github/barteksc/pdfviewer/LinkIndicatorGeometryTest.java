// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinkIndicatorGeometryTest {

    @Test
    public void normalizeMappedRect_sortsRotatedCornersIntoHorizontalBounds() {
        LinkIndicatorGeometry.Bounds bounds = LinkIndicatorGeometry.normalizeMappedRect(
                600f, 492f, 200f, 481f, 842f, 595f);

        assertNotNull(bounds);
        assertEquals(200f / 842f, bounds.left, 0.0001f);
        assertEquals(481f / 595f, bounds.top, 0.0001f);
        assertEquals(600f / 842f, bounds.right, 0.0001f);
        assertEquals(492f / 595f, bounds.bottom, 0.0001f);
        assertTrue(bounds.right - bounds.left > bounds.bottom - bounds.top);
    }

    @Test
    public void normalizeMappedRect_rejectsDegenerateAndInvalidPageGeometry() {
        assertNull(LinkIndicatorGeometry.normalizeMappedRect(
                10f, 20f, 10f, 30f, 600f, 800f));
        assertNull(LinkIndicatorGeometry.normalizeMappedRect(
                10f, 20f, 30f, 40f, 0f, 800f));
    }

    @Test
    public void toDocumentRect_scalesNormalizedBoundsIntoPageStrip() {
        LinkIndicatorGeometry.Bounds bounds = LinkIndicatorGeometry.toDocumentRect(
                0.2f, 0.3f, 0.4f, 0.5f,
                0f, 0f, 1f, 1f,
                50f, 100f, 1000f, 600f);

        assertNotNull(bounds);
        assertEquals(250f, bounds.left, 0.001f);
        assertEquals(280f, bounds.top, 0.001f);
        assertEquals(450f, bounds.right, 0.001f);
        assertEquals(400f, bounds.bottom, 0.001f);
    }

    @Test
    public void toDocumentRect_appliesCropBeforeRenderedPageScale() {
        LinkIndicatorGeometry.Bounds bounds = LinkIndicatorGeometry.toDocumentRect(
                0.2f, 0.3f, 0.4f, 0.5f,
                0.1f, 0.2f, 0.9f, 0.8f,
                25f, 75f, 800f, 600f);

        assertNotNull(bounds);
        assertEquals(125f, bounds.left, 0.001f);
        assertEquals(175f, bounds.top, 0.001f);
        assertEquals(325f, bounds.right, 0.001f);
        assertEquals(375f, bounds.bottom, 0.001f);
    }
}
