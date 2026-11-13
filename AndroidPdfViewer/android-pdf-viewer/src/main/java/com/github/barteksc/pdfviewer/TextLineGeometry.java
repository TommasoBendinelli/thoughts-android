// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Display-only word grouping, matching Desktop's gui/textlinegeometry.h.
 * Call once per semantic group and page, with both axes in points or pixels.
 * Source regions are never mutated; separate lines and distant runs stay separate.
 */
final class TextLineGeometry {
    private TextLineGeometry() {}

    static List<float[]> merge(List<float[]> fragments) {
        List<float[]> rects = new ArrayList<>();
        for (float[] r : fragments) {
            if (r == null || r.length < 4 || !Float.isFinite(r[0]) || !Float.isFinite(r[1])
                    || !Float.isFinite(r[2]) || !Float.isFinite(r[3])
                    || r[2] <= r[0] || r[3] <= r[1]) continue;
            rects.add(r.clone());
        }
        rects.sort(Comparator.<float[]>comparingDouble(TextLineGeometry::centerY)
                .thenComparingDouble(r -> r[0]));
        List<float[]> merged = new ArrayList<>();
        int first = 0;
        while (first < rects.size()) {
            int end = first + 1;
            float[] anchor = rects.get(first);
            while (end < rects.size()) {
                float[] next = rects.get(end);
                float small = Math.min(height(anchor), height(next));
                if (Math.max(height(anchor), height(next)) > 3f * small
                        || Math.abs(centerY(anchor) - centerY(next)) > .65f * small) break;
                end++;
            }
            List<float[]> line = new ArrayList<>(rects.subList(first, end));
            line.sort(Comparator.comparingDouble(r -> r[0]));
            float[] run = line.get(0).clone();
            float lastHeight = height(run);
            for (int i = 1; i < line.size(); i++) {
                float[] word = line.get(i);
                if (word[0] - run[2] <= Math.max(lastHeight, height(word))) {
                    run[0] = Math.min(run[0], word[0]);
                    run[1] = Math.min(run[1], word[1]);
                    run[2] = Math.max(run[2], word[2]);
                    run[3] = Math.max(run[3], word[3]);
                } else {
                    merged.add(run);
                    run = word.clone();
                }
                lastHeight = height(word);
            }
            merged.add(run);
            first = end;
        }
        return merged;
    }

    private static float height(float[] r) { return r[3] - r[1]; }
    private static float centerY(float[] r) { return (r[1] + r[3]) * .5f; }
}
