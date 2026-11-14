// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

/**
 * Whether a tap on a page marker was meant for it. A press that stopped the page while it still
 * moved (a fling coasting, a page snap) only stops the page, and so does one during which the page
 * moved under the finger (auto scroll, an animation): neither acts on the marker it ends on. A
 * press that moved past the touch slop is a scroll, which never becomes a tap at all.
 */
final class MarkerPressGuard {
    private boolean pressed;
    private boolean stoppedMotion;
    private float downXOffset;
    private float downYOffset;

    /** A finger went down; [pageMoving]: the page was moving by itself then. */
    void onDown(boolean pageMoving, float xOffset, float yOffset) {
        pressed = true;
        stoppedMotion = pageMoving;
        downXOffset = xOffset;
        downYOffset = yOffset;
    }

    /** At the tap: true when the press neither stopped the page nor saw it move more than [slop]. */
    boolean isStillTap(float xOffset, float yOffset, float slop) {
        return pressed && !stoppedMotion
                && Math.abs(xOffset - downXOffset) < slop
                && Math.abs(yOffset - downYOffset) < slop;
    }
}
