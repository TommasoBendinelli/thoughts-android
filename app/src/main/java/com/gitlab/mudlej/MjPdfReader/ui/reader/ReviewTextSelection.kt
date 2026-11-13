// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.graphics.RectF
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion
import com.shockwave.pdfium.util.SizeF

internal fun reviewSelectionRegions(
    request: PDFView.HighlightRequest,
    pageSize: (Int) -> SizeF?,
): List<ReviewAnswerRegion> = request.fragments.flatMap { fragment ->
    val size = pageSize(fragment.pageIndex) ?: return emptyList()
    if (fragment.pageIndex < 0 || size.width <= 0 || size.height <= 0) return emptyList()
    fragment.pdfRects.map { rect ->
        // PDF selection uses bottom-origin points; cards use top-origin fractions.
        val normalized = RectF().apply {
            left = minOf(rect.left, rect.right) / size.width
            top = 1f - maxOf(rect.top, rect.bottom) / size.height
            right = maxOf(rect.left, rect.right) / size.width
            bottom = 1f - minOf(rect.top, rect.bottom) / size.height
        }
        if (listOf(normalized.left, normalized.top, normalized.right, normalized.bottom).any { !it.isFinite() || it !in 0f..1f } ||
            normalized.right <= normalized.left || normalized.bottom <= normalized.top) return emptyList()
        ReviewAnswerRegion(fragment.pageIndex + 1, normalized)
    }
}
