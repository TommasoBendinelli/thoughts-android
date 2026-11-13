// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import android.graphics.Path;

import java.util.List;

/**
 * Pencil strokes of a drawing (Thoughts document feedback): each stroke is [x0, y0, x1, y1, ...] in
 * normalized top-left page coordinates. The line is drawn as on Desktop (reviewfeedbackoverlay.cpp).
 */
final class DrawingStrokes {
    /** Around the strokes, as on Desktop: what a drawing occupies on its page. */
    private static final float BOUNDS_MARGIN = 0.005f;
    /** Round ends make a line too short to see into a dot. */
    private static final float DOT_LENGTH = 0.00001f;

    private DrawingStrokes() {}

    /** [left, top, right, bottom] around [strokes], slightly larger, on the page. */
    static float[] bounds(List<float[]> strokes) {
        float left = 1f, top = 1f, right = 0f, bottom = 0f;
        for (float[] stroke : strokes) {
            for (int i = 0; i + 1 < stroke.length; i += 2) {
                left = Math.min(left, stroke[i]);
                top = Math.min(top, stroke[i + 1]);
                right = Math.max(right, stroke[i]);
                bottom = Math.max(bottom, stroke[i + 1]);
            }
        }
        return new float[]{
                Math.max(0f, left - BOUNDS_MARGIN), Math.max(0f, top - BOUNDS_MARGIN),
                Math.min(1f, right + BOUNDS_MARGIN), Math.min(1f, bottom + BOUNDS_MARGIN)};
    }

    /** A smooth line through the points of each stroke: curves that meet halfway between points. */
    static Path path(List<float[]> strokes) {
        Path path = new Path();
        for (float[] stroke : strokes) {
            if (stroke.length < 2) continue;
            int last = stroke.length - 2;
            path.moveTo(stroke[0], stroke[1]);
            if (last == 0) {
                path.lineTo(stroke[0] + DOT_LENGTH, stroke[1]);
                continue;
            }
            for (int i = 2; i < last; i += 2) {
                path.quadTo(stroke[i], stroke[i + 1], (stroke[i] + stroke[i + 2]) / 2f, (stroke[i + 1] + stroke[i + 3]) / 2f);
            }
            path.lineTo(stroke[last], stroke[last + 1]);
        }
        return path;
    }

    /**
     * Whether (x, y) lies within [reach] of a stroke, for a page at (left, top) of the given size;
     * all in the same pixels. A page is not square, so distances are measured there.
     */
    static boolean near(List<float[]> strokes, float left, float top, float width, float height, float x, float y, float reach) {
        double limit = (double) reach * reach;
        for (float[] stroke : strokes) {
            if (stroke.length < 2) continue;
            double ax = left + stroke[0] * width;
            double ay = top + stroke[1] * height;
            if (squaredDistance(x, y, ax, ay, ax, ay) <= limit) return true;
            for (int i = 2; i + 1 < stroke.length; i += 2) {
                double bx = left + stroke[i] * width;
                double by = top + stroke[i + 1] * height;
                if (squaredDistance(x, y, ax, ay, bx, by) <= limit) return true;
                ax = bx;
                ay = by;
            }
        }
        return false;
    }

    /** From (x, y) to the segment a-b. */
    private static double squaredDistance(double x, double y, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double length = dx * dx + dy * dy;
        double along = length == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / length));
        double offX = x - (ax + along * dx);
        double offY = y - (ay + along * dy);
        return offX * offX + offY * offY;
    }
}
