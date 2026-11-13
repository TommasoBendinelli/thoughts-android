// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageSelectionTest {

    @Test
    fun rangeUsesInclusiveZeroBasedIndices() {
        val range = PdfPageSelection.Range(4, 7)

        assertEquals(4, range.pageCount)
        assertTrue(range.includes(4))
        assertTrue(range.includes(7))
        assertFalse(range.includes(3))
        assertEquals(0, range.sharedPageIndex(4))
        assertEquals(3, range.sharedPageIndex(7))
        assertTrue(range.pageIndices().contentEquals(intArrayOf(4, 5, 6, 7)))
    }

    @Test
    fun allPagesPreservesOriginalIndices() {
        assertTrue(PdfPageSelection.All.includes(42))
        assertEquals(42, PdfPageSelection.All.sharedPageIndex(42))
    }

    @Test
    fun validatesOneBasedUserInput() {
        val valid = validatePdfPageRange("5", "8", 10)
            as PdfPageRangeValidation.Valid

        assertEquals(PdfPageSelection.Range(4, 7), valid.selection)
        assertSame(
            PdfPageRangeValidation.MissingPage,
            validatePdfPageRange("", "8", 10),
        )
        assertSame(
            PdfPageRangeValidation.OutOfBounds,
            validatePdfPageRange("0", "8", 10),
        )
        assertSame(
            PdfPageRangeValidation.OutOfBounds,
            validatePdfPageRange("5", "11", 10),
        )
        assertSame(
            PdfPageRangeValidation.Reversed,
            validatePdfPageRange("8", "5", 10),
        )
    }
}
