// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageSubsetExporterTest {

    @Test
    fun subsetFilenameUsesOneBasedInclusivePages() {
        assertEquals(
            "paper.pages-5-8.pdf",
            PdfPageSubsetExporter.subsetPdfFileName(
                "paper.pdf",
                PdfPageSelection.Range(4, 7),
            ),
        )
    }

    @Test
    fun subsetFilenameIsSanitizedAndGetsAPdfSuffix() {
        assertEquals(
            "notes_.pages-1-2.pdf",
            PdfPageSubsetExporter.subsetPdfFileName(
                "notes?",
                PdfPageSelection.Range(0, 1),
            ),
        )
    }
}
