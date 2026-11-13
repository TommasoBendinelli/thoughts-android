// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;

import com.shockwave.pdfium.util.SizeF;

import java.util.ArrayList;
import java.util.List;

/**
 * Transient, view-only editor for the normalized answer regions of a review card. It holds one
 * or several boxes, possibly on different pages; the selected one shows corner handles.
 */
final class ReviewRegionSelectionManager {

    /** Told when the selected box or the number of boxes changes. */
    interface Listener {
        void onReviewRegionsChanged(int selectedIndex, int count);
    }

    /** One box: PDF point coordinates, left < right and bottom < top. */
    private static final class Box {
        final int pageIndex;
        final RectF pdfRect;

        Box(int pageIndex, RectF pdfRect) {
            this.pageIndex = pageIndex;
            this.pdfRect = pdfRect;
        }
    }

    private static final float INITIAL_PAGE_FRACTION = 0.24f;
    private static final float MIN_PAGE_FRACTION = 0.04f;
    private static final float HANDLE_RADIUS_DP = 6f;
    private static final float HANDLE_TOUCH_RADIUS_DP = 24f;
    private static final float MOVE_TOUCH_MARGIN_DP = 12f;
    private static final int ACCENT_COLOR = 0xFF2E7D32;

    private enum DragMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    private final PDFView pdfView;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint otherFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint otherBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float handleRadius;
    private final float handleTouchRadius;
    private final float moveTouchMargin;
    private final float labelRadius;
    private final List<Box> boxes = new ArrayList<>();
    private int selected = -1;
    private Listener listener;
    private DragMode dragMode = DragMode.NONE;
    private float lastDocX;
    private float lastDocY;

    ReviewRegionSelectionManager(PDFView pdfView) {
        this.pdfView = pdfView;
        float density = pdfView.getResources().getDisplayMetrics().density;
        handleRadius = HANDLE_RADIUS_DP * density;
        handleTouchRadius = HANDLE_TOUCH_RADIUS_DP * density;
        moveTouchMargin = MOVE_TOUCH_MARGIN_DP * density;
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x302E7D32);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.8f * density);
        borderPaint.setColor(ACCENT_COLOR);
        borderPaint.setPathEffect(new DashPathEffect(new float[]{6f * density, 4f * density}, 0));
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(ACCENT_COLOR);
        otherFillPaint.setStyle(Paint.Style.FILL);
        otherFillPaint.setColor(0x182E7D32);
        otherBorderPaint.setStyle(Paint.Style.STROKE);
        otherBorderPaint.setStrokeWidth(1.2f * density);
        otherBorderPaint.setColor(0xB02E7D32);
        otherBorderPaint.setPathEffect(new DashPathEffect(new float[]{4f * density, 4f * density}, 0));
        labelRadius = 9f * density;
        labelBackgroundPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(11f * density);
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private void notifyChanged() {
        if (listener != null) listener.onReviewRegionsChanged(selected, boxes.size());
    }

    private Box selectedBox() {
        return selected >= 0 && selected < boxes.size() ? boxes.get(selected) : null;
    }

    boolean startAt(float viewX, float viewY) {
        Box box = boxAt(viewX, viewY, false);
        if (box == null) {
            return false;
        }
        boxes.clear();
        boxes.add(box);
        selected = 0;
        dragMode = DragMode.NONE;
        pdfView.invalidate();
        notifyChanged();
        return true;
    }

    /**
     * Adds a default-size box centred on the view point and selects it. A point between pages
     * places the box on the nearest page, so the box always lands on the page being looked at.
     * Returns the new box's index, or -1.
     */
    int addAt(float viewX, float viewY) {
        Box box = boxAt(viewX, viewY, true);
        if (box == null) {
            return -1;
        }
        boxes.add(box);
        selected = boxes.size() - 1;
        dragMode = DragMode.NONE;
        pdfView.invalidate();
        notifyChanged();
        return selected;
    }

    private Box boxAt(float viewX, float viewY, boolean clampToPage) {
        PdfFile file = pdfView.pdfFile;
        if (file == null) {
            return null;
        }
        float docX = -pdfView.getCurrentXOffset() + viewX;
        float docY = -pdfView.getCurrentYOffset() + viewY;
        int page = file.getPageAtOffset(
                pdfView.isSwipeVertical() ? docY : docX,
                pdfView.isSwipeVertical() ? docX : docY,
                pdfView.getZoom());
        if (page < 0 || page >= file.getPagesCount()) {
            return null;
        }
        SizeF size = file.getPagePointSize(page);
        RectF pageBounds = file.pdfRectToDocument(
                page, pdfView.getZoom(), 0, 0, size.getWidth(), size.getHeight());
        if (pageBounds == null) {
            return null;
        }
        if (!pageBounds.contains(docX, docY)) {
            if (!clampToPage) {
                return null;
            }
            docX = clamp(docX, pageBounds.left, pageBounds.right);
            docY = clamp(docY, pageBounds.top, pageBounds.bottom);
        }
        PointF center = file.documentToPdf(page, pdfView.getZoom(), docX, docY);
        if (center == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
            return null;
        }
        float side = Math.min(size.getWidth(), size.getHeight()) * INITIAL_PAGE_FRACTION;
        float left = clamp(center.x - side / 2f, 0f, size.getWidth() - side);
        float bottom = clamp(center.y - side / 2f, 0f, size.getHeight() - side);
        return new Box(page, new RectF(left, bottom + side, left + side, bottom));
    }

    boolean start(int page, RectF normalized) {
        Box box = boxFromNormalized(page, normalized);
        if (box == null) return false;
        boxes.clear();
        boxes.add(box);
        selected = 0;
        dragMode = DragMode.NONE;
        pdfView.jumpTo(page, false);
        pdfView.invalidate();
        notifyChanged();
        return true;
    }

    /** Edits several boxes at once; nothing changes unless every box is valid. No scrolling. */
    boolean startAll(List<PDFView.ReviewRegionSelection> regions, int selectedIndex) {
        if (regions == null || regions.isEmpty()) return false;
        List<Box> created = new ArrayList<>();
        for (PDFView.ReviewRegionSelection region : regions) {
            Box box = region == null ? null : boxFromNormalized(region.pageIndex, region.normalizedRect);
            if (box == null) return false;
            created.add(box);
        }
        boxes.clear();
        boxes.addAll(created);
        selected = Math.max(0, Math.min(selectedIndex, boxes.size() - 1));
        dragMode = DragMode.NONE;
        pdfView.invalidate();
        notifyChanged();
        return true;
    }

    private Box boxFromNormalized(int page, RectF normalized) {
        PdfFile file = pdfView.pdfFile;
        if (file == null || page < 0 || page >= file.getPagesCount() || normalized == null
                || !Float.isFinite(normalized.left) || !Float.isFinite(normalized.top)
                || !Float.isFinite(normalized.right) || !Float.isFinite(normalized.bottom)
                || normalized.left < 0f || normalized.top < 0f
                || normalized.right > 1f || normalized.bottom > 1f
                || normalized.left >= normalized.right || normalized.top >= normalized.bottom) {
            return null;
        }
        SizeF size = file.getPagePointSize(page);
        if (size.getWidth() <= 0 || size.getHeight() <= 0) return null;
        return new Box(page, new RectF(normalized.left * size.getWidth(),
                (1f - normalized.top) * size.getHeight(),
                normalized.right * size.getWidth(),
                (1f - normalized.bottom) * size.getHeight()));
    }

    /** Removes the selected box unless it is the last one. */
    boolean removeSelected() {
        if (boxes.size() < 2 || selectedBox() == null) return false;
        boxes.remove(selected);
        selected = Math.min(selected, boxes.size() - 1);
        dragMode = DragMode.NONE;
        pdfView.invalidate();
        notifyChanged();
        return true;
    }

    boolean select(int index) {
        if (index < 0 || index >= boxes.size()) return false;
        if (index != selected) {
            selected = index;
            pdfView.invalidate();
            notifyChanged();
        }
        return true;
    }

    int selectedIndex() {
        return selectedBox() == null ? -1 : selected;
    }

    int count() {
        return boxes.size();
    }

    void cancel() {
        boolean had = !boxes.isEmpty();
        boxes.clear();
        selected = -1;
        dragMode = DragMode.NONE;
        pdfView.invalidate();
        if (had) notifyChanged();
    }

    boolean hasSelection() {
        return !boxes.isEmpty();
    }

    /** The selected box. */
    PDFView.ReviewRegionSelection selection() {
        Box box = selectedBox();
        return box == null ? null : normalized(box);
    }

    /** Every box, in order; null if one cannot be measured. */
    List<PDFView.ReviewRegionSelection> selections() {
        List<PDFView.ReviewRegionSelection> result = new ArrayList<>();
        for (Box box : boxes) {
            PDFView.ReviewRegionSelection value = normalized(box);
            if (value == null) return null;
            result.add(value);
        }
        return result;
    }

    private PDFView.ReviewRegionSelection normalized(Box box) {
        PdfFile file = pdfView.pdfFile;
        if (file == null) {
            return null;
        }
        SizeF size = file.getPagePointSize(box.pageIndex);
        if (size.getWidth() <= 0 || size.getHeight() <= 0) {
            return null;
        }
        RectF pdfRect = box.pdfRect;
        RectF normalized = new RectF(
                pdfRect.left / size.getWidth(),
                (size.getHeight() - pdfRect.top) / size.getHeight(),
                pdfRect.right / size.getWidth(),
                (size.getHeight() - pdfRect.bottom) / size.getHeight());
        return new PDFView.ReviewRegionSelection(box.pageIndex, normalized);
    }

    void draw(Canvas canvas) {
        if (boxes.isEmpty()) {
            return;
        }
        boolean numbered = boxes.size() > 1;
        // The selected box last, so its handles stay on top of overlapping boxes.
        for (int i = 0; i < boxes.size(); i++) {
            if (i != selected) drawBox(canvas, i, false, numbered);
        }
        if (selectedBox() != null) drawBox(canvas, selected, true, numbered);
    }

    private void drawBox(Canvas canvas, int index, boolean isSelected, boolean numbered) {
        RectF rect = documentRect(boxes.get(index));
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        canvas.drawRect(rect, isSelected ? fillPaint : otherFillPaint);
        canvas.drawRect(rect, isSelected ? borderPaint : otherBorderPaint);
        if (isSelected) {
            canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint);
            canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint);
            canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint);
            canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint);
        }
        if (numbered) {
            // The area number, inside the top-left corner and clear of the corner handle.
            float cx = Math.min(rect.left + handleRadius + labelRadius + 2f, rect.centerX());
            float cy = Math.min(rect.top + handleRadius + labelRadius + 2f, rect.centerY());
            labelBackgroundPaint.setColor(isSelected ? ACCENT_COLOR : 0xB02E7D32);
            canvas.drawCircle(cx, cy, labelRadius, labelBackgroundPaint);
            canvas.drawText(String.valueOf(index + 1), cx,
                    cy - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint);
        }
    }

    boolean handleTouch(MotionEvent event) {
        if (boxes.isEmpty() || pdfView.pdfFile == null) {
            return false;
        }
        float docX = -pdfView.getCurrentXOffset() + event.getX();
        float docY = -pdfView.getCurrentYOffset() + event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return beginDrag(docX, docY);
            case MotionEvent.ACTION_MOVE:
                if (dragMode == DragMode.NONE) return false;
                moveDrag(docX, docY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragMode == DragMode.NONE) return false;
                dragMode = DragMode.NONE;
                return true;
            default:
                return dragMode != DragMode.NONE;
        }
    }

    private boolean beginDrag(float docX, float docY) {
        Box current = selectedBox();
        RectF rect = current == null ? null : documentRect(current);
        DragMode mode = DragMode.NONE;
        if (rect != null) {
            // Small existing masks have overlapping touch targets: choose the nearest corner.
            boolean right = docX >= rect.centerX();
            boolean bottom = docY >= rect.centerY();
            if (hits(docX, docY, right ? rect.right : rect.left, bottom ? rect.bottom : rect.top)) {
                mode = bottom
                        ? (right ? DragMode.RESIZE_BR : DragMode.RESIZE_BL)
                        : (right ? DragMode.RESIZE_TR : DragMode.RESIZE_TL);
            } else if (docX >= rect.left && docX <= rect.right
                    && docY >= rect.top && docY <= rect.bottom) {
                mode = DragMode.MOVE;
            }
        }
        if (mode == DragMode.NONE) {
            // Touching another box selects it and moves it; the newest box wins where they overlap.
            for (int i = boxes.size() - 1; i >= 0; i--) {
                if (i == selected) continue;
                RectF other = documentRect(boxes.get(i));
                if (other != null && other.contains(docX, docY)) {
                    select(i);
                    mode = DragMode.MOVE;
                    break;
                }
            }
        }
        if (mode == DragMode.NONE && rect != null
                && docX >= rect.left - moveTouchMargin
                && docX <= rect.right + moveTouchMargin
                && docY >= rect.top - moveTouchMargin
                && docY <= rect.bottom + moveTouchMargin) {
            mode = DragMode.MOVE;
        }
        if (mode == DragMode.NONE) {
            return false;
        }
        dragMode = mode;
        lastDocX = docX;
        lastDocY = docY;
        return true;
    }

    private boolean hits(float x, float y, float cx, float cy) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= handleTouchRadius * handleTouchRadius;
    }

    private void moveDrag(float docX, float docY) {
        PdfFile file = pdfView.pdfFile;
        Box box = selectedBox();
        if (box == null) return;
        RectF pdfRect = box.pdfRect;
        RectF documentRect = documentRect(box);
        SizeF size = file.getPagePointSize(box.pageIndex);
        if (documentRect == null || documentRect.width() <= 0 || documentRect.height() <= 0
                || size.getWidth() <= 0 || size.getHeight() <= 0) {
            return;
        }
        float dx = (docX - lastDocX) * ((pdfRect.right - pdfRect.left) / documentRect.width());
        float dy = -(docY - lastDocY) * ((pdfRect.top - pdfRect.bottom) / documentRect.height());
        float minWidth = MIN_PAGE_FRACTION * size.getWidth();
        float minHeight = MIN_PAGE_FRACTION * size.getHeight();
        switch (dragMode) {
            case MOVE:
                dx = clamp(dx, -pdfRect.left, size.getWidth() - pdfRect.right);
                dy = clamp(dy, -pdfRect.bottom, size.getHeight() - pdfRect.top);
                pdfRect.left += dx;
                pdfRect.right += dx;
                pdfRect.top += dy;
                pdfRect.bottom += dy;
                break;
            case RESIZE_TL:
                pdfRect.left = clamp(pdfRect.left + dx, 0f, pdfRect.right - minWidth);
                pdfRect.top = clamp(pdfRect.top + dy, pdfRect.bottom + minHeight, size.getHeight());
                break;
            case RESIZE_TR:
                pdfRect.right = clamp(pdfRect.right + dx, pdfRect.left + minWidth, size.getWidth());
                pdfRect.top = clamp(pdfRect.top + dy, pdfRect.bottom + minHeight, size.getHeight());
                break;
            case RESIZE_BL:
                pdfRect.left = clamp(pdfRect.left + dx, 0f, pdfRect.right - minWidth);
                pdfRect.bottom = clamp(pdfRect.bottom + dy, 0f, pdfRect.top - minHeight);
                break;
            case RESIZE_BR:
                pdfRect.right = clamp(pdfRect.right + dx, pdfRect.left + minWidth, size.getWidth());
                pdfRect.bottom = clamp(pdfRect.bottom + dy, 0f, pdfRect.top - minHeight);
                break;
            default:
                break;
        }
        lastDocX = docX;
        lastDocY = docY;
        pdfView.invalidate();
    }

    private RectF documentRect(Box box) {
        PdfFile file = pdfView.pdfFile;
        return file == null ? null : file.pdfRectToDocument(
                box.pageIndex,
                pdfView.getZoom(),
                box.pdfRect.left,
                box.pdfRect.bottom,
                box.pdfRect.right,
                box.pdfRect.top,
                false);
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (maximum < minimum) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
