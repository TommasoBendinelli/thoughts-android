// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

final class LinkIndicatorGeometry {

    private LinkIndicatorGeometry() {
    }

    static Bounds normalizeMappedRect(
            float firstX,
            float firstY,
            float secondX,
            float secondY,
            float pageWidth,
            float pageHeight) {
        if (!isPositiveFinite(pageWidth) || !isPositiveFinite(pageHeight)
                || !isFinite(firstX) || !isFinite(firstY)
                || !isFinite(secondX) || !isFinite(secondY)) {
            return null;
        }
        float left = Math.min(firstX, secondX) / pageWidth;
        float top = Math.min(firstY, secondY) / pageHeight;
        float right = Math.max(firstX, secondX) / pageWidth;
        float bottom = Math.max(firstY, secondY) / pageHeight;
        if (right <= left || bottom <= top) {
            return null;
        }
        return new Bounds(left, top, right, bottom);
    }

    static Bounds toDocumentRect(
            float normalizedLeft,
            float normalizedTop,
            float normalizedRight,
            float normalizedBottom,
            float cropLeft,
            float cropTop,
            float cropRight,
            float cropBottom,
            float pageOriginX,
            float pageOriginY,
            float renderedWidth,
            float renderedHeight) {
        float cropWidth = cropRight - cropLeft;
        float cropHeight = cropBottom - cropTop;
        if (!isPositiveFinite(cropWidth) || !isPositiveFinite(cropHeight)
                || !isPositiveFinite(renderedWidth) || !isPositiveFinite(renderedHeight)) {
            return null;
        }
        float localLeft = (normalizedLeft - cropLeft) * renderedWidth / cropWidth;
        float localTop = (normalizedTop - cropTop) * renderedHeight / cropHeight;
        float localRight = (normalizedRight - cropLeft) * renderedWidth / cropWidth;
        float localBottom = (normalizedBottom - cropTop) * renderedHeight / cropHeight;
        float left = pageOriginX + Math.min(localLeft, localRight);
        float top = pageOriginY + Math.min(localTop, localBottom);
        float right = pageOriginX + Math.max(localLeft, localRight);
        float bottom = pageOriginY + Math.max(localTop, localBottom);
        if (!isFinite(left) || !isFinite(top) || !isFinite(right) || !isFinite(bottom)
                || right <= left || bottom <= top) {
            return null;
        }
        return new Bounds(left, top, right, bottom);
    }

    private static boolean isPositiveFinite(float value) {
        return value > 0f && isFinite(value);
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    static final class Bounds {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Bounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
