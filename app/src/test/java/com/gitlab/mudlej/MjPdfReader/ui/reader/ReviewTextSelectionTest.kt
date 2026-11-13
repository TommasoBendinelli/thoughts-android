// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.graphics.RectF
import com.github.barteksc.pdfviewer.PDFView
import com.shockwave.pdfium.util.SizeF
import org.junit.Assert.*
import org.junit.Test

class ReviewTextSelectionTest {
    private fun rect(l: Float, t: Float, r: Float, b: Float) = RectF().apply {
        left = l; top = t; right = r; bottom = b
    }
    @Test fun `selection keeps separate lines and pages with top origin coordinates`() {
        val request = PDFView.HighlightRequest(listOf(
            PDFView.HighlightFragment(0, listOf(rect(20f, 180f, 80f, 170f), rect(10f, 160f, 50f, 150f)), "First line"),
            PDFView.HighlightFragment(1, listOf(rect(30f, 100f, 60f, 90f)), "Next page"),
        ))
        val regions = reviewSelectionRegions(request) { SizeF(100f, 200f) }
        assertEquals(listOf(1, 1, 2), regions.map { it.page })
        assertEquals(.2f, regions[0].rect.left, .0001f)
        assertEquals(.1f, regions[0].rect.top, .0001f)
        assertEquals(.15f, regions[0].rect.bottom, .0001f)
        assertEquals(.5f, regions[1].rect.right, .0001f)
    }
    @Test fun `missing page geometry rejects whole selection`() {
        val request = PDFView.HighlightRequest(listOf(
            PDFView.HighlightFragment(0, listOf(rect(20f, 180f, 80f, 170f)), "First"),
            PDFView.HighlightFragment(1, listOf(rect(30f, 100f, 60f, 90f)), "Second"),
        ))
        assertTrue(reviewSelectionRegions(request) { if (it == 0) SizeF(100f, 200f) else null }.isEmpty())
    }
}
