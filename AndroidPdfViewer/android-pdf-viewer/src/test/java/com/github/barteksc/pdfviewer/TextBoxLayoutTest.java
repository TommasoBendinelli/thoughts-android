// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TextBoxLayoutTest {
    private static final float DELTA = 1e-4f;

    /** The text box of document-feedback.md §11 on an A4 page (595 × 842 pt). */
    private final float[] rect = {0.1204f, 0.3312f, 0.3518f, 0.3501f};
    private final float fontSize = 0.0145f;
    private final float a4 = 595f / 842f;

    @Test public void theFontIsItsShareOfThePageHeight() {
        assertEquals(14.5f, TextBoxLayout.textSize(fontSize, 1000f), DELTA);
        assertEquals(29f, TextBoxLayout.textSize(fontSize, 2000f), DELTA);
        // Scaled from the reference layout to that size, so it grows with the zoom.
        assertEquals(14.5f, TextBoxLayout.scale(fontSize, 1000f) * TextBoxLayout.REFERENCE_TEXT_SIZE, DELTA);
        assertEquals(2f * TextBoxLayout.scale(fontSize, 1000f), TextBoxLayout.scale(fontSize, 2000f), DELTA);
    }

    @Test public void theTextWrapsAtTheWidthOfTheRect() {
        int width = TextBoxLayout.referenceWidth(rect, fontSize, a4);
        // Scaled onto a page of any height, the wrap width is the rect's width there, to a reference pixel.
        for (float pageHeight : new float[]{400f, 842f, 3000f}) {
            float pageWidth = pageHeight * a4;
            float scale = TextBoxLayout.scale(fontSize, pageHeight);
            float drawn = width * scale;
            float expected = (rect[2] - rect[0]) * pageWidth;
            assertTrue(drawn + " vs " + expected, drawn >= expected - 0.01f && drawn <= expected + scale + 0.01f);
        }
        // A wider page, or a smaller font, leaves room for more of the text on a line.
        assertTrue(TextBoxLayout.referenceWidth(rect, fontSize, 842f / 595f) > width);
        assertTrue(TextBoxLayout.referenceWidth(rect, fontSize / 2f, a4) > width);
    }

    @Test public void aNarrowRectStillHasAWidth() {
        assertEquals(1, TextBoxLayout.referenceWidth(new float[]{0.5f, 0.1f, 0.5000001f, 0.2f}, 0.2f, a4));
    }

    @Test public void theAreaIsTheRectWhileTheTextFitsInIt() {
        assertArrayEquals(rect, TextBoxLayout.area(rect, fontSize, a4, 10f, 10f), DELTA);
    }

    @Test public void theAreaGrowsWhereTheTextRunsPastTheRect() {
        // Three lines of the reference size are three font heights of the page.
        float lines = 3f * TextBoxLayout.REFERENCE_TEXT_SIZE;
        float[] area = TextBoxLayout.area(rect, fontSize, a4, 0f, lines);
        assertEquals(rect[1] + 3f * fontSize, area[3], DELTA);
        assertEquals(rect[2], area[2], DELTA);
        // A line one reference size wide is one font height of the page, in page widths.
        area = TextBoxLayout.area(rect, fontSize, a4, 100f * TextBoxLayout.REFERENCE_TEXT_SIZE, 0f);
        assertEquals(rect[0] + 100f * fontSize / a4, area[2], DELTA);
        assertEquals(rect[3], area[3], DELTA);
        assertEquals(rect[0], area[0], DELTA);
        assertEquals(rect[1], area[1], DELTA);
    }
}
