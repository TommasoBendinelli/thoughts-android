// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;

import com.shockwave.pdfium.util.SizeF;

final class PdfCommentMoveManager {

    private static final float TOUCH_TARGET_DP = 48f;

    private final PDFView pdfView;
    private PDFView.PdfComment comment;
    private PDFView.OnPdfCommentMoveListener listener;
    private RectF previewBounds;
    private boolean dragging;
    private boolean validDrop;

    PdfCommentMoveManager(PDFView pdfView) {
        this.pdfView = pdfView;
    }

    boolean start(PDFView.PdfComment comment, PDFView.OnPdfCommentMoveListener listener) {
        if (comment == null || comment.pdfBounds == null || comment.isReply()
                || comment.subtype != PDFView.PdfComment.SUBTYPE_TEXT || listener == null) {
            return false;
        }
        this.comment = comment;
        this.listener = listener;
        this.previewBounds = new RectF(comment.pdfBounds);
        this.dragging = false;
        this.validDrop = true;
        pdfView.invalidate();
        return true;
    }

    boolean isActive() {
        return comment != null;
    }

    boolean isDragging() {
        return dragging;
    }

    void cancel() {
        comment = null;
        listener = null;
        previewBounds = null;
        dragging = false;
        validDrop = false;
        pdfView.invalidate();
    }

    boolean matches(int pageIndex, String name, int annotationIndex) {
        if (comment == null || comment.pageIndex != pageIndex) {
            return false;
        }
        if (comment.name != null && !comment.name.isEmpty()
                && name != null && !name.isEmpty()) {
            return comment.name.equals(name);
        }
        return comment.annotationIndex == annotationIndex;
    }

    RectF getPreviewBounds() {
        return previewBounds == null ? null : new RectF(previewBounds);
    }

    boolean handleTouch(MotionEvent event) {
        if (!isActive() || pdfView.pdfFile == null) {
            return false;
        }
        float docX = -pdfView.getCurrentXOffset() + event.getX();
        float docY = -pdfView.getCurrentYOffset() + event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                boolean hit = hitsMarker(docX, docY);
                if (!hit) {
                    return false;
                }
                dragging = true;
                validDrop = updatePreview(docX, docY);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    return false;
                }
                validDrop = updatePreview(docX, docY);
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    return false;
                }
                validDrop = updatePreview(docX, docY);
                RectF proposed = validDrop && previewBounds != null
                        ? new RectF(previewBounds) : null;
                PDFView.OnPdfCommentMoveListener completedListener = listener;
                dragging = false;
                if (proposed != null && completedListener != null
                        && completedListener.onMove(proposed)) {
                    cancel();
                } else {
                    previewBounds = new RectF(comment.pdfBounds);
                    pdfView.invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    return false;
                }
                dragging = false;
                previewBounds = new RectF(comment.pdfBounds);
                validDrop = false;
                pdfView.invalidate();
                return true;
            default:
                return dragging;
        }
    }

    private boolean hitsMarker(float docX, float docY) {
        RectF docRect = docRect(previewBounds);
        if (docRect == null) {
            return false;
        }
        float centerX = docRect.centerX();
        float centerY = docRect.centerY();
        float radius = TOUCH_TARGET_DP * pdfView.getResources().getDisplayMetrics().density / 2f;
        float dx = docX - centerX;
        float dy = docY - centerY;
        return dx * dx + dy * dy <= radius * radius;
    }

    private boolean updatePreview(float docX, float docY) {
        PdfFile file = pdfView.pdfFile;
        int page = file.getPageAtOffset(
                pdfView.isSwipeVertical() ? docY : docX,
                pdfView.isSwipeVertical() ? docX : docY,
                pdfView.getZoom());
        if (page != comment.pageIndex) {
            pdfView.invalidate();
            return false;
        }
        PointF center = file.documentToPdf(page, pdfView.getZoom(), docX, docY);
        SizeF pageSize = file.getPagePointSize(page);
        float width = Math.abs(comment.pdfBounds.right - comment.pdfBounds.left);
        float height = Math.abs(comment.pdfBounds.top - comment.pdfBounds.bottom);
        if (width <= 0 || height <= 0 || center.x < 0 || center.y < 0
                || center.x > pageSize.getWidth() || center.y > pageSize.getHeight()) {
            pdfView.invalidate();
            return false;
        }
        float halfWidth = width / 2f;
        float halfHeight = height / 2f;
        float centerX = Math.max(halfWidth,
                Math.min(pageSize.getWidth() - halfWidth, center.x));
        float centerY = Math.max(halfHeight,
                Math.min(pageSize.getHeight() - halfHeight, center.y));
        previewBounds = new RectF(
                centerX - halfWidth,
                centerY + halfHeight,
                centerX + halfWidth,
                centerY - halfHeight);
        pdfView.invalidate();
        return true;
    }

    private RectF docRect(RectF bounds) {
        if (bounds == null || pdfView.pdfFile == null) {
            return null;
        }
        return pdfView.pdfFile.pdfRectToDocument(
                comment.pageIndex,
                pdfView.getZoom(),
                bounds.left,
                bounds.bottom,
                bounds.right,
                bounds.top);
    }
}
