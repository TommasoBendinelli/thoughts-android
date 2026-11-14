// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MarkerPressGuardTest {

    @Test
    public void aStillTapOnAStillPageCounts() {
        MarkerPressGuard guard = new MarkerPressGuard();
        guard.onDown(false, -10f, -2000f);
        assertTrue(guard.isStillTap(-10f, -2000f, 8f));
        assertTrue("Less than the slop", guard.isStillTap(-12f, -1995f, 8f));
    }

    @Test
    public void aPressThatStoppedAFlingDoesNotCount() {
        MarkerPressGuard guard = new MarkerPressGuard();
        guard.onDown(true, 0f, -500f);
        assertFalse(guard.isStillTap(0f, -500f, 8f));
    }

    @Test
    public void aPageThatMovedUnderTheFingerDoesNotCount() {
        MarkerPressGuard guard = new MarkerPressGuard();
        guard.onDown(false, 0f, -500f);
        assertFalse("Auto scroll moved it", guard.isStillTap(0f, -530f, 8f));
        assertFalse("Sideways too", guard.isStillTap(20f, -500f, 8f));
    }

    @Test
    public void noPressNoTap() {
        assertFalse(new MarkerPressGuard().isStillTap(0f, 0f, 8f));
    }

    @Test
    public void eachPressStartsAfresh() {
        MarkerPressGuard guard = new MarkerPressGuard();
        guard.onDown(true, 0f, 0f);
        guard.onDown(false, 0f, -40f);
        assertTrue(guard.isStillTap(0f, -40f, 8f));
    }
}
