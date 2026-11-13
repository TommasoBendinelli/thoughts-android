// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ReviewMaskGeometryTest {
    @Test public void fillsWordAndLineGapsWithoutChangingSourceRegions() {
        float[] first = {0.1f, 0.2f, 0.2f, 0.21f};
        List<float[]> blocks = ReviewMaskGeometry.blocks(Arrays.asList(first,
                new float[]{0.21f, 0.2f, 0.4f, 0.21f},
                new float[]{0.1f, 0.214f, 0.3f, 0.224f}), 1.4f);
        assertEquals(1, blocks.size());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.4f, 0.224f}, blocks.get(0), 0.00001f);
        assertEquals(0.2f, first[2], 0f);
    }

    @Test public void preservesSeparateParagraphsColumnsAndVisualRegions() {
        List<float[]> regions = Arrays.asList(
                new float[]{0.1f, 0.2f, 0.4f, 0.21f},
                new float[]{0.1f, 0.24f, 0.4f, 0.25f},
                new float[]{0.5f, 0.2f, 0.8f, 0.21f},
                new float[]{0.1f, 0.4f, 0.8f, 0.7f});
        assertEquals(4, ReviewMaskGeometry.blocks(regions, 1.4f).size());
    }

    @Test public void respectsPageAspectRatio() {
        List<float[]> regions = Arrays.asList(
                new float[]{0.1f, 0.2f, 0.2f, 0.21f},
                new float[]{0.212f, 0.2f, 0.3f, 0.21f});
        assertEquals(1, ReviewMaskGeometry.blocks(regions, 1.5f).size());
        assertEquals(2, ReviewMaskGeometry.blocks(regions, 0.7f).size());
    }

    @Test public void realSdpoCardFormsSolidBlocksAndKeepsEveryFragmentCovered() {
        Map<Integer, List<float[]>> pages = new TreeMap<>();
        try (Scanner input = new Scanner(getClass().getResourceAsStream("/review-mask-sdpo.txt"))) {
            input.useLocale(Locale.US);
            while (input.hasNextInt()) {
                int page = input.nextInt();
                float[] rect = {input.nextFloat(), input.nextFloat(), input.nextFloat(), input.nextFloat()};
                pages.computeIfAbsent(page, key -> new ArrayList<>()).add(rect);
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList(12, 13)), pages.keySet());
        for (Map.Entry<Integer, List<float[]>> page : pages.entrySet()) {
            List<float[]> blocks = ReviewMaskGeometry.blocks(page.getValue(), 842f / 595f);
            assertTrue("Expected a few solid blocks on page " + page.getKey(), blocks.size() <= 3);
            for (float[] rect : page.getValue()) {
                assertTrue(blocks.stream().anyMatch(b -> b[0] <= rect[0] && b[1] <= rect[1]
                        && b[2] >= rect[2] && b[3] >= rect[3]));
            }
            Collections.reverse(page.getValue());
            assertEquals(blocks.size(), ReviewMaskGeometry.blocks(page.getValue(), 842f / 595f).size());
        }
    }

    private static List<float[]> rects(String resource) {
        List<float[]> rects = new ArrayList<>();
        try (Scanner input = new Scanner(ReviewMaskGeometryTest.class.getResourceAsStream(resource))) {
            input.useLocale(Locale.US);
            while (input.hasNextFloat()) {
                rects.add(new float[]{input.nextFloat(), input.nextFloat(), input.nextFloat(), input.nextFloat()});
            }
        }
        return rects;
    }

    @Test public void hidesATableAsOneBlockOverItsRulesAndEmptyCells() {
        List<float[]> table = Arrays.asList(
                new float[]{0.20f, 0.30f, 0.30f, 0.31f}, new float[]{0.50f, 0.30f, 0.60f, 0.31f}, new float[]{0.70f, 0.30f, 0.80f, 0.31f},
                new float[]{0.20f, 0.315f, 0.28f, 0.325f}, new float[]{0.70f, 0.315f, 0.78f, 0.325f},
                new float[]{0.20f, 0.33f, 0.30f, 0.34f}, new float[]{0.50f, 0.33f, 0.55f, 0.34f});
        List<float[]> blocks = ReviewMaskGeometry.blocks(table, 1.4f);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0)[0] < 0.20f && blocks.get(0)[2] > 0.80f);
        List<float[]> withText = new ArrayList<>(table);
        withText.add(new float[]{0.1f, 0.6f, 0.9f, 0.61f});
        assertEquals(2, ReviewMaskGeometry.blocks(withText, 1.4f).size());

        // With the page's words it grows to the text column but stops before nearby text.
        List<float[]> page = new ArrayList<>(table);
        page.add(new float[]{0.10f, 0.25f, 0.90f, 0.26f});
        page.add(new float[]{0.10f, 0.40f, 0.90f, 0.41f});
        float[] grown = ReviewMaskGeometry.blocks(table, 1.4f, page).get(0);
        assertTrue(grown[0] < 0.10f && grown[2] > 0.90f);
        assertTrue(grown[1] > 0.26f && grown[3] < 0.40f && grown[3] > 0.36f);
    }

    @Test public void realOlmoTableIsHiddenUpToTheParagraphsAroundIt() {
        // olmo3.pdf page 8, "Independent runs": caption and table cells. Rules were
        // measured at x 0.1440..0.8549 and y 0.2549, 0.2852, 0.3636; the paragraph
        // above ends at 0.2148 and the next one starts at 0.3918.
        List<float[]> blocks = ReviewMaskGeometry.blocks(rects("/review-mask-olmo3-table.txt"), 841.89f / 595.276f,
                rects("/review-mask-olmo3-page.txt"));
        assertEquals(1, blocks.size());
        float[] block = blocks.get(0);
        assertTrue(block[0] <= 0.1440f && block[2] >= 0.8549f);
        assertTrue(block[1] > 0.2148f && block[1] <= 0.2549f);
        assertTrue(block[3] >= 0.3640f && block[3] < 0.3918f);
    }

    /** A selection over four paragraphs (MOPD p. 6, "SFT model." to "regression from the Stage-1 student."). */
    private static final float[][] MOPD_SELECTION = {
        {0.120054f, 0.425655f, 0.206262f, 0.438004f},
        {0.226494f, 0.425655f, 0.881054f, 0.438004f},
        {0.119832f, 0.441645f, 0.881053f, 0.454124f},
        {0.119707f, 0.457739f, 0.684701f, 0.470217f},
        {0.119311f, 0.494114f, 0.281854f, 0.506463f},
        {0.302023f, 0.494114f, 0.882244f, 0.506463f},
        {0.119676f, 0.510104f, 0.88224f, 0.522583f},
        {0.119832f, 0.526198f, 0.880753f, 0.538676f},
        {0.119707f, 0.542304f, 0.627799f, 0.55477f},
        {0.119534f, 0.578563f, 0.194905f, 0.591016f},
        {0.214322f, 0.578563f, 0.88107f, 0.591016f},
        {0.119498f, 0.594657f, 0.880624f, 0.607135f},
        {0.119739f, 0.61075f, 0.880632f, 0.623229f},
        {0.119605f, 0.626844f, 0.882077f, 0.639322f},
        {0.119505f, 0.642952f, 0.465319f, 0.655417f},
        {0.119523f, 0.67921f, 0.254801f, 0.691662f},
        {0.274492f, 0.67921f, 0.881724f, 0.691662f},
        {0.119822f, 0.695316f, 0.880162f, 0.707781f},
        {0.119593f, 0.711398f, 0.88017f, 0.725777f},
        {0.119506f, 0.726994f, 0.880641f, 0.741871f},
        {0.119829f, 0.743585f, 0.880195f, 0.756063f},
        {0.120363f, 0.759882f, 0.125971f, 0.762157f},
        {0.11915f, 0.759181f, 0.881057f, 0.774058f},
        {0.119515f, 0.777347f, 0.618963f, 0.789825f},
        {0.626658f, 0.775209f, 0.632175f, 0.776219f},
        {0.626567f, 0.777551f, 0.632175f, 0.779825f},
        {0.625354f, 0.781146f, 0.631086f, 0.787083f},
        {0.639045f, 0.781146f, 0.648428f, 0.787083f},
        {0.665445f, 0.774849f, 0.670331f, 0.783956f},
        {0.658563f, 0.784765f, 0.677254f, 0.793276f},
        {0.685005f, 0.777192f, 0.881097f, 0.790137f},
        {0.118514f, 0.793453f, 0.880594f, 0.805919f},
        {0.119597f, 0.809534f, 0.407537f, 0.822013f}

    };

    private static List<float[]> withSurroundingText(float[]... others) {
        List<float[]> words = new ArrayList<>(Arrays.asList(MOPD_SELECTION));
        words.add(new float[]{0.12f, 0.395f, 0.35f, 0.41f});   // "4.1 Experimental setup" above
        words.add(new float[]{0.12f, 0.835f, 0.30f, 0.85f});   // "4.2 Main results" below
        words.addAll(Arrays.asList(others));
        return words;
    }

    @Test public void selectionOverSeveralParagraphsIsHiddenAsOneBlock() {
        List<float[]> blocks = ReviewMaskGeometry.blocks(Arrays.asList(MOPD_SELECTION), 1.294f, withSurroundingText());
        assertEquals(1, blocks.size());
        assertArrayEquals(new float[]{0.1185f, 0.4257f, 0.8823f, 0.8220f}, blocks.get(0), 0.0002f);
        // Without the page's text the paragraph gaps cannot be checked, so the paragraphs stay apart.
        assertEquals(4, ReviewMaskGeometry.blocks(Arrays.asList(MOPD_SELECTION), 1.294f).size());
    }

    @Test public void paragraphsStayApartWhenJoiningWouldHideOtherText() {
        // A margin note in the gap after "SFT model." is not part of the answer.
        List<float[]> blocks = ReviewMaskGeometry.blocks(Arrays.asList(MOPD_SELECTION), 1.294f,
                withSurroundingText(new float[]{0.80f, 0.476f, 0.86f, 0.488f}));
        assertEquals(2, blocks.size());
    }

    @Test public void paragraphsStayApartAcrossATallBlankGap() {
        List<float[]> regions = Arrays.asList(
                new float[]{0.1f, 0.20f, 0.8f, 0.21f},
                new float[]{0.1f, 0.25f, 0.8f, 0.26f},
                new float[]{0.1f, 0.265f, 0.8f, 0.275f});
        List<float[]> blocks = ReviewMaskGeometry.blocks(regions, 1.4f, regions);
        assertEquals(2, blocks.size());
    }
}
