// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCommentSheetPresentationTest {

    @Test
    fun shortThreadUsesOnlyItsNaturalHeight() {
        assertEquals(
            420,
            contentSizedCommentSheetHeight(
                contentHeight = 250,
                fixedHeight = 170,
                availableHeight = 1_000,
            ),
        )
    }

    @Test
    fun longThreadIsCappedAtTheAvailableHeight() {
        assertEquals(
            1_000,
            contentSizedCommentSheetHeight(
                contentHeight = 1_200,
                fixedHeight = 170,
                availableHeight = 1_000,
            ),
        )
    }

    @Test
    fun commentMoveKeepsTheRequestedMinimumHeight() {
        assertEquals(
            500,
            contentSizedCommentSheetHeight(
                contentHeight = 120,
                fixedHeight = 170,
                availableHeight = 1_000,
                minimumHeight = 500,
            ),
        )
    }

    @Test
    fun showsRegularThreadWhenSoftwareKeyboardIsClosed() {
        assertEquals(
            ReplySheetPresentation.THREAD,
            replySheetPresentation(imeVisible = false, threadExpandedForIme = false),
        )
        assertEquals(
            ReplySheetPresentation.THREAD,
            replySheetPresentation(imeVisible = false, threadExpandedForIme = true),
        )
    }

    @Test
    fun defaultsToCompactComposerWhenSoftwareKeyboardOpens() {
        assertEquals(
            ReplySheetPresentation.COMPOSER_ONLY,
            replySheetPresentation(imeVisible = true, threadExpandedForIme = false),
        )
    }

    @Test
    fun keepsKeyboardOpenWhileThreadIsExpanded() {
        assertEquals(
            ReplySheetPresentation.THREAD_WITH_IME,
            replySheetPresentation(imeVisible = true, threadExpandedForIme = true),
        )
    }

    @Test
    fun forwardsOnlyTouchesInsideTheExposedPdf() {
        assertTrue(shouldForward(rawX = 360f, rawY = 500f))
        assertTrue(shouldForward(rawX = 40f, rawY = 200f))

        assertFalse(shouldForward(rawX = 360f, rawY = 100f))
        assertFalse(shouldForward(rawX = 360f, rawY = 180f))
        assertFalse(shouldForward(rawX = 360f, rawY = 900f))
        assertFalse(shouldForward(rawX = 20f, rawY = 500f))
        assertFalse(shouldForward(rawX = 680f, rawY = 500f))
    }

    @Test
    fun doesNotForwardWhenTheSheetCoversTheWholePdf() {
        assertFalse(
            shouldForwardCommentSheetTouchToPdf(
                rawX = 360f,
                rawY = 200f,
                pdfLeft = 40,
                pdfTop = 200,
                pdfRight = 680,
                pdfBottom = 1400,
                sheetTop = 200,
                blockedByUiControl = false,
            ),
        )
    }

    @Test
    fun doesNotForwardThroughAControlOverlayingThePdf() {
        assertFalse(shouldForward(rawX = 160f, rawY = 500f, blockedByUiControl = true))
    }

    private fun shouldForward(
        rawX: Float,
        rawY: Float,
        blockedByUiControl: Boolean = false,
    ) =
        shouldForwardCommentSheetTouchToPdf(
            rawX = rawX,
            rawY = rawY,
            pdfLeft = 40,
            pdfTop = 200,
            pdfRight = 680,
            pdfBottom = 1400,
            sheetTop = 900,
            blockedByUiControl = blockedByUiControl,
        )
}
