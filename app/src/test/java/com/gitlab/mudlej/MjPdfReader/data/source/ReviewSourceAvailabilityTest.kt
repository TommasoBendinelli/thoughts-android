// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewSourceAvailabilityTest {
    @Test
    fun `combined PDF checks exact selected component and rejects uncertain evidence`() {
        val authored = OfflineBundleSegment(1, 322, true, "notes.tex", null, null, emptyList())
        val imported = OfflineBundleSegment(323, 32, false, null, null, null, emptyList())
        val pdf = OfflineBundlePdf("paper.pdf", "a".repeat(64), 354, listOf(authored, imported))
        fun availability(pages: List<Int>, sha: String = pdf.pdfSha256, segments: List<OfflineBundleSegment> = pdf.segments) =
            reviewSourceAvailability(mapOf(pdf.filename to pdf.copy(segments = segments)), pdf.filename, sha, pages)
        assertEquals(false, availability(listOf(326, 327)))
        assertEquals(true, availability(listOf(1)))
        assertNull(availability(listOf(326), "b".repeat(64)))
        assertNull(availability(listOf(322, 323)))
        assertNull(availability(listOf(355)))
        assertNull(availability(listOf(326), segments = listOf(imported, imported)))
        assertNull(reviewSourceAvailability(emptyMap(), pdf.filename, pdf.pdfSha256, listOf(326)))
        assertEquals(false, availability(listOf(1), segments = listOf(imported.copy(startPage = 1))))
    }
}
