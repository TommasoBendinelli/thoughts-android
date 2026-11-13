// Written for MJ PDF. License is GPLv3.
package com.github.barteksc.pdfviewer;

final class ContentViewportMath {

    private ContentViewportMath() {
    }

    static float centerOffset(float contentCenter, float viewportStart, float viewportEnd) {
        return viewportStart + (viewportEnd - viewportStart) / 2f - contentCenter;
    }

    static float clampStripOffset(
            float requestedOffset,
            float contentSize,
            float viewportStart,
            float viewportEnd
    ) {
        float viewportSize = viewportEnd - viewportStart;
        if (contentSize < viewportSize) {
            return viewportStart + (viewportSize - contentSize) / 2f;
        }
        return Math.max(
                Math.min(requestedOffset, viewportStart),
                viewportEnd - contentSize
        );
    }
}
