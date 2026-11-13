// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Joins nearby answer fragments in page coordinates, independently of zoom. */
final class ReviewMaskGeometry {
    private ReviewMaskGeometry() {}

    static List<float[]> blocks(List<float[]> fragments, float pageHeightOverWidth) {
        return blocks(fragments, pageHeightOverWidth, null);
    }

    /**
     * pageWords, every text box on the page when known, lets a table answer grow over its
     * rules and empty space up to, never over, the surrounding text. Matches Thoughts Desktop.
     */
    static List<float[]> blocks(List<float[]> fragments, float pageHeightOverWidth, List<float[]> pageWords) {
        // Share visible word-to-line grouping, then cover the answer's line gaps.
        List<float[]> physical = new ArrayList<>();
        for (float[] r : fragments) {
            physical.add(new float[]{r[0], r[1] * pageHeightOverWidth, r[2], r[3] * pageHeightOverWidth});
        }
        fragments = new ArrayList<>();
        for (float[] r : TextLineGeometry.merge(physical)) {
            fragments.add(new float[]{r[0], r[1] / pageHeightOverWidth, r[2], r[3] / pageHeightOverWidth});
        }
        // A table answer (several rows, some split into columns) is hidden as one block:
        // covering only its words leaves the rules, column gaps and empty cells readable.
        List<float[]> tables = new ArrayList<>();
        fragments = withoutTables(fragments, pageWords, tables);
        int[] parent = new int[fragments.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < parent.length; i++) {
            float[] a = fragments.get(i);
            for (int j = 0; j < i; j++) {
                float[] b = fragments.get(j);
                // Use the smaller fragment so a tall image cannot bridge unrelated text.
                float height = Math.min(a[3] - a[1], b[3] - b[1]);
                float xGap = Math.max(a[0], b[0]) - Math.min(a[2], b[2]);
                float yGap = Math.max(a[1], b[1]) - Math.min(a[3], b[3]);
                boolean neighbours = xGap <= 0f && yGap <= 0.8f * height;
                if (neighbours) parent[root(parent, i)] = root(parent, j);
            }
        }
        // Only original fragments establish connectivity; enlarged bounds cannot
        // progressively absorb other paragraphs, columns, or figures.
        Map<Integer, float[]> bounds = new LinkedHashMap<>();
        for (int i = 0; i < parent.length; i++) {
            float[] rect = fragments.get(i);
            int root = root(parent, i);
            float[] block = bounds.get(root);
            if (block == null) bounds.put(root, rect.clone());
            else {
                block[0] = Math.min(block[0], rect[0]);
                block[1] = Math.min(block[1], rect[1]);
                block[2] = Math.max(block[2], rect[2]);
                block[3] = Math.max(block[3], rect[3]);
            }
        }
        List<float[]> result = new ArrayList<>(tables);
        result.addAll(joinAcrossBlankGaps(new ArrayList<>(bounds.values()), fragments, pageWords));
        return withoutContained(result);
    }

    /** Drops blocks inside another block, such as the parts of an inline fraction within its paragraph. */
    private static List<float[]> withoutContained(List<float[]> blocks) {
        List<float[]> kept = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            float[] a = blocks.get(i);
            boolean contained = false;
            for (int j = 0; j < blocks.size() && !contained; j++) {
                float[] b = blocks.get(j);
                boolean inside = j != i && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3];
                // Of two identical blocks, keep the first.
                contained = inside && (j < i || !Arrays.equals(a, b));
            }
            if (!contained) kept.add(a);
        }
        return kept;
    }

    /**
     * One selection over several paragraphs is one answer: join its blocks across the blank space
     * between them, as long as their enclosing rectangle hides no page text outside the answer.
     * A gap taller than three lines (a figure, a display) or text beside the gap keeps them apart.
     */
    private static List<float[]> joinAcrossBlankGaps(List<float[]> blocks, List<float[]> fragments, List<float[]> pageWords) {
        if (pageWords == null || pageWords.isEmpty() || blocks.size() < 2) return blocks;
        float[] heights = new float[fragments.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = fragments.get(i)[3] - fragments.get(i)[1];
        Arrays.sort(heights);
        float lineHeight = heights[heights.length / 2];
        List<float[]> joined = new ArrayList<>();
        for (float[] block : blocks) joined.add(block.clone());
        boolean changed = true;
        while (changed) {
            changed = false;
            search:
            for (int i = 0; i < joined.size(); i++) {
                for (int j = i + 1; j < joined.size(); j++) {
                    float[] a = joined.get(i), b = joined.get(j);
                    float xGap = Math.max(a[0], b[0]) - Math.min(a[2], b[2]);
                    float yGap = Math.max(a[1], b[1]) - Math.min(a[3], b[3]);
                    if (xGap > 0f || yGap > 3f * lineHeight) continue;
                    float[] union = {Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                            Math.max(a[2], b[2]), Math.max(a[3], b[3])};
                    if (hidesOtherText(union, fragments, pageWords, lineHeight)) continue;
                    joined.set(i, union);
                    joined.remove(j);
                    changed = true;
                    break search;
                }
            }
        }
        return joined;
    }

    private static boolean hidesOtherText(float[] area, List<float[]> fragments, List<float[]> pageWords, float lineHeight) {
        float dx = 0.005f, dy = 0.25f * lineHeight;
        for (float[] w : pageWords) {
            float x = (w[0] + w[2]) / 2f, y = (w[1] + w[3]) / 2f;
            if (x <= area[0] || x >= area[2] || y <= area[1] || y >= area[3]) continue;
            boolean answer = false;
            for (float[] f : fragments) {
                if (x >= f[0] - dx && x <= f[2] + dx && y >= f[1] - dy && y <= f[3] + dy) { answer = true; break; }
            }
            if (!answer) return true;
        }
        return false;
    }

    private static List<float[]> withoutTables(List<float[]> fragments, List<float[]> pageWords, List<float[]> tables) {
        int[] cluster = new int[fragments.size()];
        for (int i = 0; i < cluster.length; i++) cluster[i] = i;
        for (int i = 0; i < cluster.length; i++) {
            for (int j = 0; j < i; j++) {
                float[] a = fragments.get(i), b = fragments.get(j);
                float height = Math.min(a[3] - a[1], b[3] - b[1]);
                float yGap = Math.max(a[1], b[1]) - Math.min(a[3], b[3]);
                if (yGap <= 1.2f * height) cluster[root(cluster, i)] = root(cluster, j);
            }
        }
        Map<Integer, List<Integer>> members = new LinkedHashMap<>();
        for (int i = 0; i < cluster.length; i++) {
            members.computeIfAbsent(root(cluster, i), key -> new ArrayList<>()).add(i);
        }
        List<float[]> remaining = new ArrayList<>();
        for (List<Integer> group : members.values()) {
            // Rows are printed lines: fragments that overlap vertically, such as the stacked
            // parts of an inline fraction, belong to one row.
            List<float[]> bands = new ArrayList<>();
            List<List<float[]>> rows = new ArrayList<>();
            for (int index : group) {
                float[] f = fragments.get(index);
                bands.add(new float[]{f[1], f[3]});
                rows.add(new ArrayList<>(Collections.singletonList(f)));
            }
            for (boolean merged = true; merged; ) {
                merged = false;
                for (int a = 0; a < bands.size() && !merged; a++) {
                    for (int b = a + 1; b < bands.size() && !merged; b++) {
                        float[] x = bands.get(a), y = bands.get(b);
                        if (x[0] < y[1] && y[0] < x[1]) {
                            x[0] = Math.min(x[0], y[0]);
                            x[1] = Math.max(x[1], y[1]);
                            rows.get(a).addAll(rows.remove(b));
                            bands.remove(b);
                            merged = true;
                        }
                    }
                }
            }
            if (bands.size() < 2 || !alignedColumns(rows)) {
                for (int index : group) remaining.add(fragments.get(index));
                continue;
            }
            float[] table = fragments.get(group.get(0)).clone();
            float lineHeight = 1f;
            for (int index : group) {
                float[] f = fragments.get(index);
                table[0] = Math.min(table[0], f[0]);
                table[1] = Math.min(table[1], f[1]);
                table[2] = Math.max(table[2], f[2]);
                table[3] = Math.max(table[3], f[3]);
                lineHeight = Math.min(lineHeight, f[3] - f[1]);
            }
            float left = table[0] - 0.012f, top = table[1] - 0.6f * lineHeight;
            float right = table[2] + 0.012f, bottom = table[3] + 0.6f * lineHeight;
            if (pageWords != null && !pageWords.isEmpty()) {
                // Rules sit just outside the text column; stop short of any other text,
                // and never more than three lines away.
                float columnLeft = table[0], columnRight = table[2];
                for (float[] w : pageWords) {
                    columnLeft = Math.min(columnLeft, w[0]);
                    columnRight = Math.max(columnRight, w[2]);
                }
                float gap = 0.3f * lineHeight;
                left = columnLeft - 0.015f;
                right = columnRight + 0.015f;
                top = table[1] - 3f * lineHeight;
                bottom = table[3] + 3f * lineHeight;
                for (float[] w : pageWords) {
                    float x = (w[0] + w[2]) / 2f, y = (w[1] + w[3]) / 2f;
                    if (x >= table[0] && x <= table[2] && y >= table[1] && y <= table[3]) continue;
                    boolean besideRows = w[1] < table[3] && w[3] > table[1];
                    boolean withinColumns = w[0] < table[2] && w[2] > table[0];
                    if (withinColumns && w[3] <= table[1]) top = Math.max(top, w[3] + gap);
                    if (withinColumns && w[1] >= table[3]) bottom = Math.min(bottom, w[1] - gap);
                    if (besideRows && w[2] <= table[0]) left = Math.max(left, w[2] + 0.005f);
                    if (besideRows && w[0] >= table[2]) right = Math.min(right, w[0] - 0.005f);
                }
                left = Math.min(left, table[0]);
                top = Math.min(top, table[1]);
                right = Math.max(right, table[2]);
                bottom = Math.max(bottom, table[3]);
            }
            tables.add(new float[]{Math.max(0f, left), Math.max(0f, top), Math.min(1f, right), Math.min(1f, bottom)});
        }
        return remaining;
    }

    /**
     * A table's columns line up: two rows split at horizontally overlapping gaps. A paragraph's
     * run-in heading or an inline formula also splits a row, but not in line with another row.
     */
    private static boolean alignedColumns(List<List<float[]>> rows) {
        List<float[]> gaps = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<float[]> row = new ArrayList<>(rows.get(r));
            row.sort((a, b) -> Float.compare(a[0], b[0]));
            float right = row.get(0)[2];
            for (float[] f : row.subList(1, row.size())) {
                if (f[0] > right) gaps.add(new float[]{right, f[0], r});
                right = Math.max(right, f[2]);
            }
        }
        for (int i = 0; i < gaps.size(); i++) {
            for (int j = 0; j < i; j++) {
                float[] a = gaps.get(i), b = gaps.get(j);
                if (a[2] != b[2] && Math.max(a[0], b[0]) < Math.min(a[1], b[1])) return true;
            }
        }
        return false;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }
}
