// Written for Thoughts. License is GPLv3.

package com.github.barteksc.pdfviewer;

final class TextCommentMarkerGeometry {

    private static final float BODY_HEIGHT_RATIO = 0.8f;
    private static final float TAIL_SHOULDER_LEFT_RATIO = 0.2f;
    private static final float TAIL_TIP_X_RATIO = 0.275f;
    private static final float TAIL_SHOULDER_RIGHT_RATIO = 0.475f;

    private TextCommentMarkerGeometry() {
    }

    static Bubble centeredBubble(
            float left,
            float top,
            float right,
            float bottom,
            float size) {
        float centerX = (left + right) / 2f;
        float centerY = (top + bottom) / 2f;
        float halfSize = Math.max(0f, size) / 2f;
        float bubbleLeft = centerX - halfSize;
        float bubbleTop = centerY - halfSize;
        float safeSize = halfSize * 2f;
        return bubbleWithTail(
                bubbleLeft,
                bubbleTop,
                safeSize,
                bubbleLeft + safeSize * TAIL_TIP_X_RATIO,
                false
        );
    }

    static Bubble atHighlightStart(
            float highlightLeft,
            float highlightTop,
            float highlightBottom,
            float pageLeft,
            float pageTop,
            float pageRight,
            float pageBottom,
            float size) {
        float safeSize = Math.max(0f, size);
        float normalizedPageLeft = Math.min(pageLeft, pageRight);
        float normalizedPageRight = Math.max(pageLeft, pageRight);
        float normalizedPageTop = Math.min(pageTop, pageBottom);
        float normalizedPageBottom = Math.max(pageTop, pageBottom);
        float normalizedHighlightTop = Math.min(highlightTop, highlightBottom);
        float normalizedHighlightBottom = Math.max(highlightTop, highlightBottom);
        float bubbleLeft = clampStart(
                highlightLeft - safeSize * TAIL_TIP_X_RATIO,
                normalizedPageLeft,
                normalizedPageRight,
                safeSize);
        float tailTipX = clamp(
                highlightLeft,
                bubbleLeft,
                bubbleLeft + safeSize);

        if (normalizedHighlightTop - safeSize >= normalizedPageTop) {
            return bubbleWithTail(
                    bubbleLeft,
                    normalizedHighlightTop - safeSize,
                    safeSize,
                    tailTipX,
                    false
            );
        }
        if (normalizedHighlightBottom + safeSize <= normalizedPageBottom) {
            return bubbleWithTail(
                    bubbleLeft,
                    normalizedHighlightBottom,
                    safeSize,
                    tailTipX,
                    true
            );
        }
        return bubbleWithTail(
                bubbleLeft,
                clampStart(
                        normalizedHighlightTop - safeSize,
                        normalizedPageTop,
                        normalizedPageBottom,
                        safeSize),
                safeSize,
                tailTipX,
                false
        );
    }

    private static Bubble bubbleWithTail(
            float left,
            float top,
            float size,
            float tailTipX,
            boolean tailOnTop) {
        float right = left + size;
        float bottom = top + size;
        float bodyTop = tailOnTop ? top + size * (1f - BODY_HEIGHT_RATIO) : top;
        float bodyBottom = tailOnTop ? bottom : top + size * BODY_HEIGHT_RATIO;
        return new Bubble(
                left,
                top,
                right,
                bottom,
                bodyTop,
                bodyBottom,
                left + size * TAIL_SHOULDER_LEFT_RATIO,
                tailTipX,
                left + size * TAIL_SHOULDER_RIGHT_RATIO,
                tailOnTop
        );
    }

    private static float clampStart(float value, float start, float end, float size) {
        float maximum = end - size;
        if (start > maximum) {
            return start;
        }
        return clamp(value, start, maximum);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static Badge upperRightBadge(Bubble bubble, float diameter, float inset) {
        float safeDiameter = Math.max(0f, diameter);
        float radius = safeDiameter / 2f;
        float safeInset = Math.max(0f, inset);
        return new Badge(
                bubble.right - safeInset - radius,
                bubble.bodyTop + safeInset + radius,
                radius
        );
    }

    static final class Badge {
        final float centerX;
        final float centerY;
        final float radius;

        Badge(float centerX, float centerY, float radius) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
        }
    }

    static final class Bubble {
        final float left;
        final float top;
        final float right;
        final float bottom;
        final float bodyTop;
        final float bodyBottom;
        final float tailShoulderLeft;
        final float tailTipX;
        final float tailShoulderRight;
        final boolean tailOnTop;

        Bubble(
                float left,
                float top,
                float right,
                float bottom,
                float bodyTop,
                float bodyBottom,
                float tailShoulderLeft,
                float tailTipX,
                float tailShoulderRight,
                boolean tailOnTop) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.bodyTop = bodyTop;
            this.bodyBottom = bodyBottom;
            this.tailShoulderLeft = tailShoulderLeft;
            this.tailTipX = tailTipX;
            this.tailShoulderRight = tailShoulderRight;
            this.tailOnTop = tailOnTop;
        }

        Bubble offset(float dx, float dy) {
            return new Bubble(
                    left + dx,
                    top + dy,
                    right + dx,
                    bottom + dy,
                    bodyTop + dy,
                    bodyBottom + dy,
                    tailShoulderLeft + dx,
                    tailTipX + dx,
                    tailShoulderRight + dx,
                    tailOnTop
            );
        }
    }
}
