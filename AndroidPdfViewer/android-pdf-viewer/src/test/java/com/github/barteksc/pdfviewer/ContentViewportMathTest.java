// Written for MJ PDF. License is GPLv3.
package com.github.barteksc.pdfviewer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ContentViewportMathTest {

    @Test
    public void centerOffset_fullViewport_centersContent() {
        assertEquals(-300f, ContentViewportMath.centerOffset(700f, 0f, 800f), 0f);
    }

    @Test
    public void centerOffset_partiallyCoveredViewport_centersAboveOverlay() {
        assertEquals(-500f, ContentViewportMath.centerOffset(700f, 0f, 400f), 0f);
    }

    @Test
    public void clampStripOffset_fullViewport_preservesLegacyBounds() {
        assertEquals(0f, ContentViewportMath.clampStripOffset(100f, 1600f, 0f, 800f), 0f);
        assertEquals(-800f, ContentViewportMath.clampStripOffset(-900f, 1600f, 0f, 800f), 0f);
    }

    @Test
    public void clampStripOffset_partiallyCoveredViewport_allowsBottomAtVisibleEdge() {
        assertEquals(-1200f, ContentViewportMath.clampStripOffset(-1300f, 1600f, 0f, 400f), 0f);
    }

    @Test
    public void clampStripOffset_shortContent_centersInsideVisibleViewport() {
        assertEquals(175f, ContentViewportMath.clampStripOffset(-50f, 150f, 100f, 400f), 0f);
    }
}
