package com.github.barteksc.pdfviewer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.widget.Magnifier;

import com.github.barteksc.pdfviewer.util.TextDirectionUtil;
import com.shockwave.pdfium.PdfiumCore;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TextSelectionManager {

    private static final int DEFAULT_SELECTION_COLOR = 0x663F51B5;
    private static final float INITIAL_HIT_TOLERANCE_DP = 8f;
    private static final float HANDLE_HIT_TOLERANCE_DP = 12f;
    private static final float SCALE_PROBE_PX = 100f;
    private static final float LINE_OVERLAP_THRESHOLD = 0.6f;
    private static final float LINE_MERGE_GAP_FACTOR = 0.5f;
    private static final float LOOSE_HEIGHT_LIMIT_FACTOR = 2.5f;
    private static final float FALLBACK_LINE_PAD_FACTOR = 0.15f;

    private enum Handle { NONE, START, END }

    private final PDFView pdfView;
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path selectionPath = new Path();
    private final Path handlePath = new Path();
    private final float[] boxScratch = new float[4];
    private final List<PageRects> pageRects = new ArrayList<>();
    private final Set<Integer> activePages = new HashSet<>();
    private final float handleTouchRadius;
    private final float minHandleRadius;
    private final float maxHandleRadius;
    private final float initialHitTolerancePx;
    private final float handleHitTolerancePx;

    private boolean enabled;
    private TextSelection selection;
    private Handle dragging = Handle.NONE;
    private float dragOffsetX;
    private float dragOffsetY;
    private Object magnifier;
    private boolean magnifierCreated;

    private static final class PageRects {
        final int pageIndex;
        final int startChar;
        final int endChar;
        final List<RectF> rects = new ArrayList<>();

        PageRects(int pageIndex, int startChar, int endChar) {
            this.pageIndex = pageIndex;
            this.startChar = startChar;
            this.endChar = endChar;
        }
    }

    TextSelectionManager(PDFView pdfView) {
        this.pdfView = pdfView;
        float density = pdfView.getResources().getDisplayMetrics().density;
        handleTouchRadius = 28f * density;
        minHandleRadius = 10f * density;
        maxHandleRadius = 20f * density;
        initialHitTolerancePx = INITIAL_HIT_TOLERANCE_DP * density;
        handleHitTolerancePx = HANDLE_HIT_TOLERANCE_DP * density;
        selectionPaint.setStyle(Paint.Style.FILL);
        selectionPaint.setColor(DEFAULT_SELECTION_COLOR);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFF3F51B5);
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clear();
        }
    }

    void setSelectionColor(int color) {
        selectionPaint.setColor((0x66 << 24) | (color & 0x00FFFFFF));
        handlePaint.setColor((0xFF << 24) | (color & 0x00FFFFFF));
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean hasSelection() {
        return selection != null && !selection.isEmpty();
    }

    boolean isDraggingHandle() {
        return dragging != Handle.NONE;
    }

    boolean handleTouch(MotionEvent event) {
        if (!enabled || selection == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return beginHandleDrag(event.getX(), event.getY());
            case MotionEvent.ACTION_MOVE:
                if (dragging != Handle.NONE) {
                    moveHandle(event.getX(), event.getY());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != Handle.NONE) {
                    endHandleDrag();
                    return true;
                }
                return false;
            default:
                return dragging != Handle.NONE;
        }
    }

    boolean handleSingleTap(float viewX, float viewY) {
        if (selection == null) {
            return false;
        }
        if (!containsSelectionPoint(viewX, viewY) && handleHitTest(viewX, viewY) == Handle.NONE) {
            clear();
        }
        return true;
    }

    boolean startWordSelectionAt(float viewX, float viewY) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (!enabled || pdfFile == null) {
            return false;
        }

        float docX = -pdfView.getCurrentXOffset() + viewX;
        float docY = -pdfView.getCurrentYOffset() + viewY;
        int page = pdfFile.getPageAtOffset(pdfView.isSwipeVertical() ? docY : docX,
                pdfView.isSwipeVertical() ? docX : docY, pdfView.getZoom());
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return false;
        }

        clear();
        pdfFile.ensureTextPage(page);
        activePages.add(page);
        if (pdfFile.pageCharCount(page) <= 0) {
            clear();
            return false;
        }

        PointF point = pdfFile.documentToPdf(page, pdfView.getZoom(), docX, docY);
        float tolerance = viewDistanceToPdf(page, initialHitTolerancePx);
        int glyph = pdfFile.charIndexAtPagePoint(page, point.x, point.y, tolerance);
        if (glyph < 0) {
            clear();
            return false;
        }

        int[] word = expandWord(page, glyph);
        selection = new TextSelection();
        selection.basePage = page;
        selection.baseChar = word[0];
        selection.extentPage = page;
        selection.extentChar = word[1];
        rebuildRects();
        if (pageRects.isEmpty()) {
            clear();
            return false;
        }
        notifyChanged();
        pdfView.invalidate();
        return hasSelection();
    }

    /**
     * Selects the text of a saved passage, with handles, as if dragged there: from the character
     * at the start of the first rect to the one at the end of the last. [pages] are 0-based and
     * [normalizedRects] top-origin page fractions, in reading order (a review feedback anchor's
     * regions). False, with nothing selected, when no text lies there.
     */
    boolean startSelectionCovering(List<Integer> pages, List<RectF> normalizedRects) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (!enabled || pdfFile == null || pages == null || normalizedRects == null
                || pages.isEmpty() || pages.size() != normalizedRects.size()) {
            return false;
        }
        int firstPage = pages.get(0);
        int lastPage = pages.get(pages.size() - 1);
        if (firstPage < 0 || lastPage < firstPage || lastPage >= pdfFile.getPagesCount()) {
            return false;
        }

        clear();
        int start = caretInRect(firstPage, normalizedRects.get(0), true);
        int end = caretInRect(lastPage, normalizedRects.get(normalizedRects.size() - 1), false);
        if (start < 0 || end < 0 || TextSelection.compare(firstPage, start, lastPage, end) >= 0) {
            clear();
            return false;
        }
        selection = new TextSelection();
        selection.basePage = firstPage;
        selection.baseChar = start;
        selection.extentPage = lastPage;
        selection.extentChar = end;
        rebuildRects();
        if (pageRects.isEmpty()) {
            clear();
            return false;
        }
        notifyChanged();
        pdfView.invalidate();
        return hasSelection();
    }

    /** The caret at the start (or end) of [normalized] on [page]: before its first (or after its last) character. */
    private int caretInRect(int page, RectF normalized, boolean start) {
        PdfFile pdfFile = pdfView.pdfFile;
        pdfFile.ensureTextPage(page);
        activePages.add(page);
        if (pdfFile.pageCharCount(page) <= 0) {
            return -1;
        }
        com.shockwave.pdfium.util.SizeF size = pdfFile.getPagePointSize(page);
        float width = size.getWidth();
        float height = size.getHeight();
        if (width <= 0f || height <= 0f || normalized.width() <= 0f || normalized.height() <= 0f) {
            return -1;
        }
        // Just inside the rect, on its middle line, so the lookup lands on a glyph of this line.
        float inset = Math.min(normalized.width() * width * 0.25f, 2f);
        float x = start ? normalized.left * width + inset : normalized.right * width - inset;
        float y = (1f - normalized.centerY()) * height;
        float tolerance = normalized.height() * height * 0.5f;
        int glyph = pdfFile.charIndexAtPagePoint(page, x, y, tolerance);
        if (glyph < 0) {
            glyph = pdfFile.charIndexAtPagePoint(page, x, y, tolerance * 4f);
        }
        if (glyph < 0) {
            return -1;
        }
        return start ? glyph : clamp(glyph + 1, 0, pdfFile.pageCharCount(page));
    }

    String getSelectedText() {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null || selection == null || selection.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (PageRects fragment : pageRects) {
            String value = normalizeCopiedText(pdfFile.textRange(
                    fragment.pageIndex,
                    fragment.startChar,
                    fragment.endChar - fragment.startChar
            ));
            if (value.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(value);
        }
        return text.toString();
    }

    PDFView.HighlightRequest getHighlightRequest() {
        if (selection == null || selection.isEmpty() || pageRects.isEmpty() || pdfView.pdfFile == null) {
            return null;
        }
        List<PDFView.HighlightFragment> fragments = new ArrayList<>(pageRects.size());
        for (PageRects page : pageRects) {
            List<RectF> rects = new ArrayList<>(page.rects.size());
            for (RectF pdfRect : page.rects) {
                rects.add(new RectF(pdfRect));
            }
            String text = normalizeCopiedText(pdfView.pdfFile.textRange(
                    page.pageIndex,
                    page.startChar,
                    page.endChar - page.startChar
            ));
            fragments.add(new PDFView.HighlightFragment(page.pageIndex, rects, text));
        }
        return new PDFView.HighlightRequest(fragments);
    }

    RectF getSelectionViewBounds() {
        if (selection == null || pageRects.isEmpty() || pdfView.pdfFile == null) {
            return null;
        }

        RectF union = null;
        float zoom = pdfView.getZoom();
        for (PageRects page : pageRects) {
            for (RectF pdfRect : page.rects) {
                RectF docRect = pdfView.pdfFile.pdfRectToDocument(
                        page.pageIndex,
                        zoom,
                        pdfRect.left,
                        pdfRect.bottom,
                        pdfRect.right,
                        pdfRect.top
                );
                if (docRect == null) {
                    continue;
                }
                docRect.offset(pdfView.getCurrentXOffset(), pdfView.getCurrentYOffset());
                if (union == null) {
                    union = docRect;
                } else {
                    union.union(docRect);
                }
            }
        }
        return union;
    }

    void clear() {
        boolean hadSelection = selection != null;
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile != null) {
            for (Integer page : activePages) {
                pdfFile.closeTextPage(page);
            }
        }
        activePages.clear();
        selection = null;
        dragging = Handle.NONE;
        pageRects.clear();
        dismissMagnifier();
        if (hadSelection) {
            pdfView.callbacks.callOnTextSelectionCleared();
        }
        pdfView.invalidate();
    }

    void recycle() {
        boolean hadSelection = selection != null;
        selection = null;
        activePages.clear();
        dragging = Handle.NONE;
        pageRects.clear();
        dismissMagnifier();
        if (hadSelection) {
            pdfView.callbacks.callOnTextSelectionCleared();
        }
        if (pdfView.pdfFile != null) {
            pdfView.pdfFile.closeAllTextPages();
        }
    }

    void draw(Canvas canvas) {
        if (selection == null || pageRects.isEmpty() || pdfView.pdfFile == null) {
            return;
        }

        float zoom = pdfView.getZoom();
        selectionPath.rewind();
        boolean hasRect = false;
        for (PageRects page : pageRects) {
            for (RectF pdfRect : TextLineRects.merge(page.rects)) {
                RectF docRect = pdfView.pdfFile.pdfRectToDocument(
                        page.pageIndex,
                        zoom,
                        pdfRect.left,
                        pdfRect.bottom,
                        pdfRect.right,
                        pdfRect.top
                );
                if (docRect != null) {
                    selectionPath.addRect(docRect, Path.Direction.CW);
                    hasRect = true;
                }
            }
        }
        if (hasRect) {
            canvas.drawPath(selectionPath, selectionPaint);
        }
        drawHandles(canvas, zoom);
    }

    private void drawHandles(Canvas canvas, float zoom) {
        drawHandle(canvas, Handle.START, zoom);
        drawHandle(canvas, Handle.END, zoom);
    }

    private void drawHandle(Canvas canvas, Handle handle, float zoom) {
        RectF docRect = handleRunDocumentRect(handle, zoom);
        if (docRect == null) {
            return;
        }

        float radius = handleRadiusFor(docRect);
        float anchorX = handleAnchorX(handle, docRect);
        float anchorY = docRect.bottom;
        boolean bulgeRight = handleBulgesRight(handle);
        float centerX = bulgeRight ? anchorX + radius : anchorX - radius;
        float centerY = anchorY + radius;
        handlePath.rewind();
        handlePath.addCircle(centerX, centerY, radius, Path.Direction.CW);
        if (bulgeRight) {
            handlePath.addRect(anchorX, anchorY, centerX, centerY, Path.Direction.CW);
        } else {
            handlePath.addRect(centerX, anchorY, anchorX, centerY, Path.Direction.CW);
        }
        canvas.drawPath(handlePath, handlePaint);
    }

    private boolean handleBulgesRight(Handle handle) {
        return (handle == Handle.END) != isHandleEndpointRtl(handle);
    }

    private float handleRadiusFor(RectF docRect) {
        float radius = docRect.height() * 0.5f;
        return Math.max(minHandleRadius, Math.min(radius, maxHandleRadius));
    }

    private int caretAt(int page, float pdfX, float pdfY, float tolerance) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null) {
            return -1;
        }

        int glyph = pdfFile.charIndexAtPagePoint(page, pdfX, pdfY, tolerance);
        if (glyph < 0) {
            glyph = pdfFile.charIndexAtPagePoint(page, pdfX, pdfY, tolerance * 3f);
        }
        if (glyph < 0) {
            glyph = pdfFile.charIndexAtPagePoint(page, pdfX, pdfY, tolerance * 8f);
        }
        if (glyph < 0) {
            return -1;
        }
        if (!charBox(page, glyph, boxScratch)) {
            return glyph;
        }

        float centerX = (boxScratch[0] + boxScratch[2]) * 0.5f;
        boolean afterHalf = pdfX > centerX;
        if (TextDirectionUtil.isRtl(pdfFile.charUnicode(page, glyph))) {
            afterHalf = !afterHalf;
        }
        return afterHalf ? glyph + 1 : glyph;
    }

    private boolean beginHandleDrag(float viewX, float viewY) {
        Handle handle = handleHitTest(viewX, viewY);
        if (handle == Handle.NONE || selection == null) {
            return false;
        }

        dragging = handle;
        PointF anchor = handleTextAnchorView(handle);
        if (anchor != null) {
            dragOffsetX = viewX - anchor.x;
            dragOffsetY = viewY - anchor.y;
        } else {
            dragOffsetX = 0f;
            dragOffsetY = 0f;
        }
        int startPage = selection.startPage();
        int startChar = selection.startChar();
        int endPage = selection.endPage();
        int endChar = selection.endChar();
        if (handle == Handle.START) {
            selection.basePage = endPage;
            selection.baseChar = endChar;
            selection.extentPage = startPage;
            selection.extentChar = startChar;
        } else {
            selection.basePage = startPage;
            selection.baseChar = startChar;
            selection.extentPage = endPage;
            selection.extentChar = endChar;
        }
        if (anchor != null) {
            showMagnifier(anchor.x, anchor.y);
        }
        return true;
    }

    private void moveHandle(float viewX, float viewY) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null || selection == null) {
            return;
        }

        float lookupX = viewX - dragOffsetX;
        float lookupY = viewY - dragOffsetY;
        autoScroll(lookupX, lookupY);
        showMagnifier(lookupX, lookupY);
        float docX = -pdfView.getCurrentXOffset() + lookupX;
        float docY = -pdfView.getCurrentYOffset() + lookupY;
        int page = pdfFile.getPageAtOffset(
                pdfView.isSwipeVertical() ? docY : docX,
                pdfView.isSwipeVertical() ? docX : docY,
                pdfView.getZoom()
        );
        if (page < 0 || page >= pdfFile.getPagesCount()) {
            return;
        }
        pdfFile.ensureTextPage(page);
        activePages.add(page);
        PointF point = pdfFile.documentToPdf(page, pdfView.getZoom(), docX, docY);
        float tolerance = viewDistanceToPdf(page, handleHitTolerancePx);
        int caret = caretAt(page, point.x, point.y, tolerance);
        if (caret < 0) {
            return;
        }

        int charCount = pdfFile.pageCharCount(page);
        caret = clamp(caret, 0, charCount);
        int order = TextSelection.compare(page, caret, selection.basePage, selection.baseChar);
        if ((dragging == Handle.START && order >= 0)
                || (dragging == Handle.END && order <= 0)) {
            return;
        }

        int previousPage = selection.extentPage;
        int previousExtent = selection.extentChar;
        selection.extentPage = page;
        selection.extentChar = caret;
        rebuildRects();
        if (pageRects.isEmpty()) {
            selection.extentPage = previousPage;
            selection.extentChar = previousExtent;
            rebuildRects();
        } else {
            notifyChanged();
            pdfView.invalidate();
        }
    }

    private void notifyChanged() {
        if (selection == null || selection.isEmpty()) {
            return;
        }
        pdfView.callbacks.callOnTextSelectionChanged(
                getSelectionViewBounds(),
                selection.startPage()
        );
    }

    private void autoScroll(float viewX, float viewY) {
        float density = pdfView.getResources().getDisplayMetrics().density;
        float edge = 48f * density;
        float step = 18f * density;
        float dx = 0f;
        float dy = 0f;
        if (pdfView.isSwipeVertical()) {
            if (viewY < edge) {
                dy = step;
            } else if (viewY > pdfView.getHeight() - edge) {
                dy = -step;
            }
        } else {
            if (viewX < edge) {
                dx = step;
            } else if (viewX > pdfView.getWidth() - edge) {
                dx = -step;
            }
        }
        if (dx != 0f || dy != 0f) {
            pdfView.moveRelativeTo(dx, dy);
            pdfView.loadPages();
        }
    }

    private void endHandleDrag() {
        dragging = Handle.NONE;
        dismissMagnifier();
    }

    private void showMagnifier(float viewX, float viewY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        if (!magnifierCreated) {
            magnifierCreated = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                magnifier = new Magnifier.Builder(pdfView).build();
            } else {
                magnifier = new Magnifier(pdfView);
            }
        }
        if (magnifier != null) {
            ((Magnifier) magnifier).show(viewX, viewY);
        }
    }

    private void dismissMagnifier() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && magnifier != null) {
            ((Magnifier) magnifier).dismiss();
        }
    }

    private float viewDistanceToPdf(int page, float distancePx) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null || distancePx <= 0f) {
            return distancePx;
        }
        float zoom = pdfView.getZoom();
        PointF origin = pdfFile.documentToPdf(page, zoom, 0f, 0f);
        PointF shifted = pdfFile.documentToPdf(page, zoom, SCALE_PROBE_PX, 0f);
        float scale = Math.abs(shifted.x - origin.x) / SCALE_PROBE_PX;
        return scale > 0f ? distancePx * scale : distancePx;
    }

    private Handle handleHitTest(float viewX, float viewY) {
        Handle bestHandle = Handle.NONE;
        float bestDistance = Float.MAX_VALUE;
        PointF start = handleCenterView(Handle.START);
        if (start != null) {
            float distance = squaredDistance(viewX, viewY, start.x, start.y);
            if (distance <= handleTouchRadius * handleTouchRadius) {
                bestHandle = Handle.START;
                bestDistance = distance;
            }
        }

        PointF end = handleCenterView(Handle.END);
        if (end != null) {
            float distance = squaredDistance(viewX, viewY, end.x, end.y);
            if (distance <= handleTouchRadius * handleTouchRadius && distance < bestDistance) {
                bestHandle = Handle.END;
            }
        }
        return bestHandle;
    }

    private PointF handleCenterView(Handle handle) {
        RectF docRect = handleRunDocumentRect(handle, pdfView.getZoom());
        if (docRect == null) {
            return null;
        }
        float radius = handleRadiusFor(docRect);
        float anchorX = handleAnchorX(handle, docRect);
        float x = handleBulgesRight(handle) ? anchorX + radius : anchorX - radius;
        float y = docRect.bottom + radius;
        return new PointF(x + pdfView.getCurrentXOffset(), y + pdfView.getCurrentYOffset());
    }

    private PointF handleTextAnchorView(Handle handle) {
        RectF docRect = handleRunDocumentRect(handle, pdfView.getZoom());
        if (docRect == null) {
            return null;
        }
        float x = handleAnchorX(handle, docRect) + pdfView.getCurrentXOffset();
        float y = docRect.centerY() + pdfView.getCurrentYOffset();
        return new PointF(x, y);
    }

    private float handleAnchorX(Handle handle, RectF docRect) {
        boolean rtl = isHandleEndpointRtl(handle);
        if (handle == Handle.START) {
            return rtl ? docRect.right : docRect.left;
        }
        return rtl ? docRect.left : docRect.right;
    }

    private boolean isHandleEndpointRtl(Handle handle) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null || selection == null) {
            return false;
        }

        int page = handle == Handle.START ? selection.startPage() : selection.endPage();
        int charCount = pdfFile.pageCharCount(page);
        if (charCount <= 0) {
            return false;
        }

        int index = handle == Handle.START ? selection.startChar() : selection.endChar() - 1;
        index = clamp(index, 0, charCount - 1);
        return TextDirectionUtil.isRtl(pdfFile.charUnicode(page, index));
    }

    private RectF handleRunDocumentRect(Handle handle, float zoom) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null || selection == null || pageRects.isEmpty()) {
            return null;
        }
        PageRects page = handle == Handle.START ? pageRects.get(0) : pageRects.get(pageRects.size() - 1);
        if (page.rects.isEmpty()) {
            return null;
        }
        RectF pdfRect = handle == Handle.START
                ? page.rects.get(0)
                : page.rects.get(page.rects.size() - 1);
        return pdfFile.pdfRectToDocument(
                page.pageIndex,
                zoom,
                pdfRect.left,
                pdfRect.bottom,
                pdfRect.right,
                pdfRect.top
        );
    }

    private boolean containsSelectionPoint(float viewX, float viewY) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null || selection == null) {
            return false;
        }
        for (PageRects page : pageRects) {
            for (RectF pdfRect : page.rects) {
                RectF docRect = pdfFile.pdfRectToDocument(
                        page.pageIndex,
                        pdfView.getZoom(),
                        pdfRect.left,
                        pdfRect.bottom,
                        pdfRect.right,
                        pdfRect.top
                );
                if (docRect == null) {
                    continue;
                }
                docRect.offset(pdfView.getCurrentXOffset(), pdfView.getCurrentYOffset());
                if (docRect.contains(viewX, viewY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean charBox(int page, int index, float[] out) {
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null) {
            return false;
        }
        if (pdfFile.looseCharBox(page, index, out) && out[2] > out[0] && out[3] > out[1]) {
            return true;
        }
        return pdfFile.tightCharBox(page, index, out) && out[2] > out[0] && out[3] > out[1];
    }

    private int[] expandWord(int page, int glyph) {
        PdfFile pdfFile = pdfView.pdfFile;
        int charCount = pdfFile.pageCharCount(page);
        if (glyph < 0 || glyph >= charCount) {
            return new int[] {0, 0};
        }
        if (!isWordChar(pdfFile.charUnicode(page, glyph))) {
            return new int[] {glyph, glyph + 1};
        }

        int start = glyph;
        int end = glyph;
        while (start > 0 && isWordChar(pdfFile.charUnicode(page, start - 1))) {
            start--;
        }
        while (end + 1 < charCount && isWordChar(pdfFile.charUnicode(page, end + 1))) {
            end++;
        }
        return new int[] {start, end + 1};
    }

    private void rebuildRects() {
        pageRects.clear();
        PdfFile pdfFile = pdfView.pdfFile;
        if (pdfFile == null || selection == null || selection.isEmpty()) {
            return;
        }

        int firstPage = selection.startPage();
        int lastPage = selection.endPage();
        Set<Integer> requiredPages = new LinkedHashSet<>();
        for (int page = firstPage; page <= lastPage; ++page) {
            pdfFile.ensureTextPage(page);
            requiredPages.add(page);
            int charCount = pdfFile.pageCharCount(page);
            int start = page == firstPage ? selection.startChar() : 0;
            int end = page == lastPage ? selection.endChar() : charCount;
            start = clamp(start, 0, charCount);
            end = clamp(end, start, charCount);
            if (end <= start) {
                continue;
            }

            PageRects fragment = new PageRects(page, start, end);
            float[] values = pdfFile.textRects(page, start, end - start);
            List<RectF> runs = new ArrayList<>();
            for (int i = 0; i + 3 < values.length; i += 4) {
                float left = Math.min(values[i], values[i + 2]);
                float right = Math.max(values[i], values[i + 2]);
                float bottom = Math.min(values[i + 1], values[i + 3]);
                float top = Math.max(values[i + 1], values[i + 3]);
                if (right > left && top > bottom) {
                    runs.add(pdfRect(left, bottom, right, top));
                }
            }

            int index = 0;
            while (index < runs.size()) {
                float lineTop = runs.get(index).top;
                float lineBottom = runs.get(index).bottom;
                int lineEnd = index + 1;
                while (lineEnd < runs.size() && isSameLine(lineTop, lineBottom, runs.get(lineEnd))) {
                    lineTop = Math.max(lineTop, runs.get(lineEnd).top);
                    lineBottom = Math.min(lineBottom, runs.get(lineEnd).bottom);
                    lineEnd++;
                }
                appendLineRects(pdfFile, page, fragment.rects, runs.subList(index, lineEnd), lineTop, lineBottom);
                index = lineEnd;
            }
            if (!fragment.rects.isEmpty()) {
                pageRects.add(fragment);
            }
        }
        for (Integer page : new HashSet<>(activePages)) {
            if (!requiredPages.contains(page)) {
                pdfFile.closeTextPage(page);
                activePages.remove(page);
            }
        }
        activePages.addAll(requiredPages);
    }

    private boolean isSameLine(float lineTop, float lineBottom, RectF run) {
        float overlap = Math.min(lineTop, run.top) - Math.max(lineBottom, run.bottom);
        float smaller = Math.min(lineTop - lineBottom, run.top - run.bottom);
        return smaller > 0f && overlap / smaller >= LINE_OVERLAP_THRESHOLD;
    }

    private void appendLineRects(PdfFile pdfFile, int page, List<RectF> output,
                                 List<RectF> lineRuns,
                                 float tightTop, float tightBottom) {
        float tightHeight = tightTop - tightBottom;
        if (tightHeight <= 0f || lineRuns.isEmpty()) {
            return;
        }

        float top = tightTop;
        float bottom = tightBottom;
        boolean loosened = false;
        RectF firstRun = lineRuns.get(0);
        int glyph = pdfFile.charIndexAtPagePoint(page, (firstRun.left + firstRun.right) * 0.5f,
                (tightTop + tightBottom) * 0.5f, tightHeight * 0.5f);
        if (glyph >= 0 && pdfFile.looseCharBox(page, glyph, boxScratch)) {
            float looseBottom = Math.min(boxScratch[1], boxScratch[3]);
            float looseTop = Math.max(boxScratch[1], boxScratch[3]);
            float looseHeight = looseTop - looseBottom;
            if (looseHeight > 0f && looseHeight <= LOOSE_HEIGHT_LIMIT_FACTOR * tightHeight) {
                top = Math.max(top, looseTop);
                bottom = Math.min(bottom, looseBottom);
                loosened = true;
            }
        }
        if (!loosened) {
            float pad = FALLBACK_LINE_PAD_FACTOR * tightHeight;
            top += pad;
            bottom -= pad;
        }

        List<RectF> sorted = new ArrayList<>(lineRuns);
        Collections.sort(sorted, new Comparator<RectF>() {
            @Override
            public int compare(RectF a, RectF b) {
                return Float.compare(a.left, b.left);
            }
        });
        float maxGap = LINE_MERGE_GAP_FACTOR * (top - bottom);
        RectF current = null;
        for (RectF run : sorted) {
            if (current != null && run.left - current.right <= maxGap) {
                current.right = Math.max(current.right, run.right);
            } else {
                current = pdfRect(run.left, bottom, run.right, top);
                output.add(current);
            }
        }
    }

    private RectF pdfRect(float left, float bottom, float right, float top) {
        RectF rect = new RectF();
        rect.left = left;
        rect.top = top;
        rect.right = right;
        rect.bottom = bottom;
        return rect;
    }

    private String normalizeCopiedText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(PdfiumCore.mapPresentationFormMarks(text), Normalizer.Form.NFKC)
                .replace("\uFFFE\r\n", "")
                .replace("\uFFFE\n", "")
                .replace("\uFFFE\r", "")
                .replace("\uFFFE", "")
                .replace("\u200B", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private boolean isWordChar(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                || codePoint == '_'
                || Character.getType(codePoint) == Character.NON_SPACING_MARK;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private float squaredDistance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }
}
