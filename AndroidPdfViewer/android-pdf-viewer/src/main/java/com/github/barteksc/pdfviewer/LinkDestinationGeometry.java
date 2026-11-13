// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

final class LinkDestinationGeometry {

    private LinkDestinationGeometry() {
    }

    static Anchor resolve(float encodedX, float encodedY, float pageWidth, float pageHeight) {
        if (!isPositiveFinite(pageWidth) || !isPositiveFinite(pageHeight)) {
            return null;
        }
        boolean hasX = isFinite(encodedX);
        boolean hasY = isFinite(encodedY);
        if (!hasX && !hasY) {
            return null;
        }
        float x = hasX ? clamp(encodedX, 0f, pageWidth) : pageWidth * 0.5f;
        float y = hasY ? clamp(encodedY, 0f, pageHeight) : pageHeight * 0.5f;
        return new Anchor(x, y);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isPositiveFinite(float value) {
        return value > 0f && isFinite(value);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    static final class Anchor {
        final float x;
        final float y;

        Anchor(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
