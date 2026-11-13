// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;

/** Android adapter; merge in full page coordinates before clipping or zooming. */
final class TextLineRects {
    private TextLineRects() {}

    static List<RectF> merge(List<RectF> rects) { return merge(rects, 1f, 1f); }

    static List<RectF> merge(List<RectF> rects, float xScale, float yScale) {
        List<float[]> fragments = new ArrayList<>();
        for (RectF r : rects) {
            if (r != null) fragments.add(new float[]{Math.min(r.left, r.right) * xScale,
                    Math.min(r.top, r.bottom) * yScale, Math.max(r.left, r.right) * xScale,
                    Math.max(r.top, r.bottom) * yScale});
        }
        List<RectF> lines = new ArrayList<>();
        for (float[] r : TextLineGeometry.merge(fragments)) {
            lines.add(new RectF(r[0] / xScale, r[1] / yScale, r[2] / xScale, r[3] / yScale));
        }
        return lines;
    }
}
