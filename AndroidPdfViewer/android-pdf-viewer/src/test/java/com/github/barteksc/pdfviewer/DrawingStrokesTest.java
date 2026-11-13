// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DrawingStrokesTest {
    private static final float DELTA = 1e-6f;

    /** A box around most of a 200 × 400 page at (10, 20). */
    private final List<float[]> box = Collections.singletonList(new float[]{0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f, 0.1f, 0.1f});

    private boolean near(List<float[]> strokes, float x, float y) {
        return DrawingStrokes.near(strokes, 10f, 20f, 200f, 400f, x, y, 5f);
    }

    @Test public void boundsAreSlightlyLargerAndStayOnThePage() {
        assertArrayEquals(new float[]{0.095f, 0.095f, 0.905f, 0.905f}, DrawingStrokes.bounds(box), DELTA);
        List<float[]> corner = Arrays.asList(new float[]{0f, 0.001f}, new float[]{1f, 0.5f});
        assertArrayEquals(new float[]{0f, 0f, 1f, 0.505f}, DrawingStrokes.bounds(corner), DELTA);
    }

    @Test public void aDotHasAnArea() {
        float[] bounds = DrawingStrokes.bounds(Collections.singletonList(new float[]{0.5f, 0.5f}));
        assertTrue(bounds[2] > bounds[0] && bounds[3] > bounds[1]);
    }

    @Test public void aTapCountsOnTheLineAndNotInsideIt() {
        assertTrue(near(box, 110f, 60f)); // on the top edge
        assertTrue(near(box, 110f, 64f)); // within reach
        assertFalse(near(box, 110f, 66f));
        assertFalse(near(box, 110f, 220f)); // the middle of the box
    }

    @Test public void aTapFindsADot() {
        List<float[]> dot = Collections.singletonList(new float[]{0.5f, 0.5f});
        assertTrue(near(dot, 110f, 220f));
        assertFalse(near(dot, 120f, 220f));
    }

    @Test public void strokesAreNotJoinedToEachOther() {
        List<float[]> two = Arrays.asList(new float[]{0.1f, 0.5f}, new float[]{0.9f, 0.5f});
        assertFalse(near(two, 110f, 220f));
    }
}
