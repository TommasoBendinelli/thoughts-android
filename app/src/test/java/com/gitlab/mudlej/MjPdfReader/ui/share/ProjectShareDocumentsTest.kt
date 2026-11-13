// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundlePdf
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundleSegment
import org.junit.Assert.*
import org.junit.Test

class ProjectShareDocumentsTest {
    private val hash = "a".repeat(64)
    private fun segment(start: Int, count: Int, source: String? = "tex/second_document.tex") =
        OfflineBundleSegment(start, count, true, source, null, null, emptyList())
    private fun pdf(vararg segments: OfflineBundleSegment) =
        OfflineBundlePdf("project.pdf", hash, 10, segments.toList())

    @Test fun inclusiveBoundariesAndCurrentPageSelectTheWholeDocument() {
        val documents = projectShareDocuments(pdf(segment(1, 3), segment(4, 5), segment(9, 2)), hash, 10)
        assertEquals(PdfPageSelection.Range(3, 7), documents[1].pages)
        assertEquals("second document", documents[1].name)
        assertArrayEquals(intArrayOf(3, 4, 5, 6, 7), documents[1].pages.pageIndices())
        assertEquals(0, currentProjectShareDocument(documents, 2))
        assertEquals(1, currentProjectShareDocument(documents, 3))
        assertEquals(1, currentProjectShareDocument(documents, 7))
        assertEquals(2, currentProjectShareDocument(documents, 8))
    }

    @Test fun staleHashOrPageCountDisablesDocumentExport() {
        val pdf = pdf(segment(1, 10))
        assertTrue(projectShareDocuments(pdf, "b".repeat(64), 10).isEmpty())
        assertTrue(projectShareDocuments(pdf, hash, 9).isEmpty())
    }

    @Test fun actualTitleComesFromTheMatchingDocumentBookmark() {
        val document = ProjectShareDocument("paper main", PdfPageSelection.Range(45, 59))
        val title = "When the Self-Teacher Follows the Student's Mistake: Causal Analysis of Prefix Contamination in On-Policy Distillation"
        assertEquals(title, projectShareDocumentTitle(document, mapOf(46 to title), mapOf(46 to "paper main")))
        assertEquals("Catalogue title", projectShareDocumentTitle(document, mapOf(47 to "A section"), mapOf(46 to "Catalogue title")))
        assertEquals("paper main", projectShareDocumentTitle(document, emptyMap(), emptyMap()))
    }

    @Test fun invalidAndOverlappingBoundariesAreRejectedRatherThanClipped() {
        listOf(
            pdf(segment(0, 1)), pdf(segment(1, 0)), pdf(segment(9, 3)),
            pdf(segment(1, 5), segment(5, 3)), pdf(segment(Int.MAX_VALUE, 2)),
        ).forEach { assertTrue(projectShareDocuments(it, hash, 10).isEmpty()) }
    }

    @Test fun gapsAreNotExportedAndMissingNamesHaveAFallback() {
        val documents = projectShareDocuments(pdf(segment(4, 2, null)), hash, 10)
        assertEquals("Document 4", documents.single().name)
        assertEquals(PdfPageSelection.Range(3, 4), documents.single().pages)
        assertEquals(0, currentProjectShareDocument(documents, 0))
    }
}
