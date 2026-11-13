// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.graphics.RectF
import com.github.barteksc.pdfviewer.PDFView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SelectionSecondaryActionTest {

    @Test
    fun verifiedSourceSelectionUsesOpenSourceAction() {
        assertEquals(
            SelectionSecondaryAction.OPEN_SOURCE,
            selectionSecondaryAction(canOpenSource = true),
        )
    }

    @Test
    fun ordinaryOrUnverifiedSelectionKeepsTranslateAction() {
        assertEquals(
            SelectionSecondaryAction.TRANSLATE,
            selectionSecondaryAction(canOpenSource = false),
        )
    }

    @Test
    fun liveSelectionRequestIsPreserved() {
        val request = PDFView.HighlightRequest(
            3,
            listOf(RectF(10f, 20f, 80f, 32f)),
            "selected text",
        )

        assertSame(request, selectionSourceRequest(null, request))
    }

    @Test
    fun savedHighlightCreatesEquivalentSourceRequest() {
        val bounds = RectF(10f, 20f, 80f, 32f)
        val annotation = PDFView.HighlightAnnotation(
            4,
            7,
            "highlight-group",
            RectF(20f, 40f, 160f, 64f),
            bounds,
            "saved highlighted text",
        )

        val request = selectionSourceRequest(annotation, null)

        assertEquals(4, request?.pageIndex)
        assertEquals("saved highlighted text", request?.selectedText)
        assertEquals(1, request?.pdfRects?.size)
    }

    @Test
    fun selectionEndpointsAreItsFirstAndLastRectangles() {
        val first = RectF(10f, 20f, 80f, 32f)
        val middle = RectF(10f, 40f, 80f, 52f)
        val last = RectF(10f, 60f, 40f, 72f)
        val onePage = PDFView.HighlightRequest(2, listOf(first, middle, last), "three lines")

        val (start, end) = selectionSourceEndpoints(onePage)!!

        assertEquals(2, start.pageIndex)
        assertSame(first, start.pdfRects.single())
        assertEquals(2, end.pageIndex)
        assertSame(last, end.pdfRects.single())
    }

    @Test
    fun selectionAcrossPagesEndsOnItsLastPage() {
        val first = RectF(10f, 20f, 80f, 32f)
        val last = RectF(10f, 60f, 40f, 72f)
        val request = PDFView.HighlightRequest(listOf(
            PDFView.HighlightFragment(4, listOf(first), "end of page"),
            PDFView.HighlightFragment(5, listOf(RectF(), last), "start of next"),
        ))

        val (start, end) = selectionSourceEndpoints(request)!!

        assertEquals(4, start.pageIndex)
        assertSame(first, start.pdfRects.single())
        assertEquals(5, end.pageIndex)
        assertSame(last, end.pdfRects.single())
    }

    @Test
    fun oneRectangleSelectionLooksUpOnce() {
        val request = PDFView.HighlightRequest(0, listOf(RectF(1f, 2f, 3f, 4f)), "word")

        val (start, end) = selectionSourceEndpoints(request)!!

        assertSame(start, end)
    }

    @Test
    fun selectionWithoutGeometryHasNoEndpoints() {
        assertNull(selectionSourceEndpoints(null))
        assertNull(selectionSourceEndpoints(PDFView.HighlightRequest(3, emptyList(), "text")))
        assertNull(selectionSourceEndpoints(PDFView.HighlightRequest(-1, listOf(RectF()), "text")))
    }
}
