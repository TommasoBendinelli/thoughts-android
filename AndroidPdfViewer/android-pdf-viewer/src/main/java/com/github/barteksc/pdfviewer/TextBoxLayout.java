// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * The text of a text box (Thoughts document feedback): typed on the Mac in a rect [left, top, right,
 * bottom] in normalized top-left page coordinates, with a font whose height is [fontSize] of the
 * page's height. The text starts at the top left of the rect and wraps at its width; in this phone's
 * sans-serif font it may run past the rect, which is fine: the text matters, not the box.
 *
 * The text is laid out once at [REFERENCE_TEXT_SIZE] and scaled onto the page, so it is as large on
 * the page at every zoom and wraps at the same words while zooming.
 */
final class TextBoxLayout {
    /** The size in pixels the text is laid out at before it is scaled onto the page. */
    static final float REFERENCE_TEXT_SIZE = 64f;

    private TextBoxLayout() {}

    /** The font's size in pixels on a page [pageHeight] pixels high. */
    static float textSize(float fontSize, float pageHeight) {
        return fontSize * pageHeight;
    }

    /** From the reference layout to a page [pageHeight] pixels high. */
    static float scale(float fontSize, float pageHeight) {
        return textSize(fontSize, pageHeight) / REFERENCE_TEXT_SIZE;
    }

    /**
     * Where the reference layout wraps: the rect's width in reference pixels, on a page whose width
     * is [pageAspect] times its height. At least one pixel.
     */
    static int referenceWidth(float[] rect, float fontSize, float pageAspect) {
        double width = (double) (rect[2] - rect[0]) * pageAspect / fontSize * REFERENCE_TEXT_SIZE;
        return (int) Math.max(1, Math.ceil(width - 1e-3));
    }

    /**
     * What the text covers on its page, normalized [left, top, right, bottom]: the rect, and further
     * where a reference layout of [layoutWidth] × [layoutHeight] pixels runs past it.
     */
    static float[] area(float[] rect, float fontSize, float pageAspect, float layoutWidth, float layoutHeight) {
        float right = rect[0] + layoutWidth * fontSize / (REFERENCE_TEXT_SIZE * pageAspect);
        float bottom = rect[1] + layoutHeight * fontSize / REFERENCE_TEXT_SIZE;
        return new float[]{rect[0], rect[1], Math.max(rect[2], right), Math.max(rect[3], bottom)};
    }

    /** [text] in sans-serif at the reference size, wrapping at [width] pixels; line breaks are kept. */
    static StaticLayout layout(CharSequence text, int width) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.SANS_SERIF);
        paint.setTextSize(REFERENCE_TEXT_SIZE);
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();
    }

    /** The widest line of [layout], in its pixels. */
    static float width(Layout layout) {
        float widest = 0f;
        for (int line = 0; line < layout.getLineCount(); line++) widest = Math.max(widest, layout.getLineWidth(line));
        return widest;
    }
}
