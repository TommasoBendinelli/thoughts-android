// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TextLineGeometryTest {
    @Test public void desktopScreenshotHasTwoContinuousLines() {
        // Same 17 regions as Desktop autotests/data/comment-highlight-lines.json.
        List<float[]> words = new ArrayList<>();
        try (Scanner input = new Scanner(getClass().getResourceAsStream("/comment-highlight-lines.txt"))) {
            input.useLocale(Locale.US);
            while (input.hasNextFloat()) words.add(new float[]{input.nextFloat() * 595.276f,
                    input.nextFloat() * 841.89f, input.nextFloat() * 595.276f, input.nextFloat() * 841.89f});
        }
        assertEquals(17, words.size());
        for (float scale : new float[]{.5f, 1f, 2f, 4f}) {
            List<float[]> scaled = new ArrayList<>();
            for (float[] r : words) scaled.add(new float[]{r[0]*scale, r[1]*scale, r[2]*scale, r[3]*scale});
            Collections.reverse(scaled);
            List<float[]> lines = TextLineGeometry.merge(scaled);
            assertEquals(2, lines.size());
            for (float[] r : scaled) assertTrue(lines.stream().anyMatch(b -> b[0] <= r[0] && b[1] <= r[1]
                    && b[2] >= r[2] && b[3] >= r[3]));
        }
    }

    @Test public void fillsSpacesButNotLineOrColumnGaps() {
        float[] first = {10, 20, 30, 30};
        List<float[]> lines = TextLineGeometry.merge(Arrays.asList(first,
                new float[]{34, 20, 54, 30}, new float[]{100, 20, 120, 30},
                new float[]{10, 33, 40, 43}, new float[]{200, -75, 300, 125}));
        assertEquals(4, lines.size());
        assertTrue(lines.stream().anyMatch(r -> Arrays.equals(r, new float[]{10, 20, 54, 30})));
        assertArrayEquals(new float[]{10, 20, 30, 30}, first, 0f);
    }

    @Test public void duplicateAndInvalidRegionsDoNotChangeTheRun() {
        List<float[]> words = Arrays.asList(new float[]{10,20,30,30}, new float[]{10,20,30,30},
                new float[]{34,21,54,29}, new float[]{0,0,0,10}, new float[]{Float.NaN,0,10,10},
                new float[]{0,0,Float.POSITIVE_INFINITY,10});
        List<float[]> lines = TextLineGeometry.merge(words);
        assertEquals(1, lines.size());
        assertArrayEquals(new float[]{10,20,54,30}, lines.get(0), 0f);
    }

    @Test public void pageAspectRatioIsAppliedBeforeGrouping() {
        assertEquals(2, TextLineGeometry.merge(Arrays.asList(new float[]{200,100,400,110},
                new float[]{420,100,620,110})).size());
        assertEquals(1, TextLineGeometry.merge(Arrays.asList(new float[]{50,400,100,440},
                new float[]{105,400,155,440})).size());
    }
}
