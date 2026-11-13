// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class OverviewCommentMetadataTest {
    private val comment = OverviewComment("imported", "id", "", "Comment", "", "", "", 0,
        "pdf_only", -1, "StrikeOut", "", "", 0)
    private val projects = JsonParser.parseString("""[
        {"id":"p","name":"Research","target":"combined.pdf","documents":[
            {"filename":"imported.pdf","name":"Paper"}]}]
    """).asJsonArray

    @Test fun missingPageRetainsProjectAndDocument() {
        val result = overviewCommentMetadataLocations(comment, projects).single()
        assertEquals("imported", result.stem)
        assertEquals("Paper", result.documentName)
        assertEquals(listOf("Research"), result.projects)
        assertNull(result.page)
    }

    @Test fun knownPageIsOneBased() {
        assertEquals(207, overviewCommentMetadataLocations(comment.copy(pageIndex = 206), projects).single().page)
    }

    @Test fun placementsKeepTheirOwnDocumentAndPageAndDeduplicateFragments() {
        val first = LogicalCommentPdfLocation("imported.pdf", "hash", 7, "exact")
        val second = first.copy(pdfFilename = "combined.pdf", page = 20)
        val result = overviewCommentMetadataLocations(comment.copy(logicalLocations = listOf(first, first, second)), projects)
        assertEquals(listOf("imported", "combined"), result.map { it.stem })
        assertEquals(listOf(7, null), result.map { it.page })
        assertEquals(20, result.last().projectPage)
        assertTrue(result.all { it.projects == listOf("Research") })
    }

    @Test fun unavailablePlacementDoesNotClaimCurrentPage() {
        val location = LogicalCommentPdfLocation("imported.pdf", "old", 7, "unavailable")
        assertNull(overviewCommentMetadataLocations(comment.copy(logicalLocations = listOf(location)), projects).single().page)
    }

    @Test fun combinedAndIndividualPagesUseVersionedComposition() {
        val catalogue = JsonParser.parseString("""[{"id":"p","name":"Course","target":"combined.pdf",
            "documents":[{"filename":"lecture.pdf","name":"Lecture"}],
            "composition":{"filename":"combined.pdf","sha256":"combined-hash","segments":[
                {"filename":"lecture.pdf","name":"Lecture","sha256":"lecture-hash","start_page":200,"page_count":20}]}}]""").asJsonArray
        val combined = comment.copy(logicalLocations = listOf(LogicalCommentPdfLocation("combined.pdf", "combined-hash", 207, "page_only")))
        val result = overviewCommentMetadataLocations(combined, catalogue).single()
        assertEquals("Lecture", result.documentName)
        assertEquals(8, result.page)
        assertEquals(207, result.projectPage)
        val individual = combined.copy(logicalLocations = listOf(LogicalCommentPdfLocation("lecture.pdf", "lecture-hash", 8, "page_only")))
        assertEquals(207, overviewCommentMetadataLocations(individual, catalogue).single().projectPage)
        val stale = combined.copy(logicalLocations = listOf(LogicalCommentPdfLocation("combined.pdf", "old", 207, "page_only")))
        assertNull(overviewCommentMetadataLocations(stale, catalogue).single().page)
        val outside = combined.copy(logicalLocations = listOf(LogicalCommentPdfLocation("combined.pdf", "combined-hash", 220, "page_only")))
        assertNull(overviewCommentMetadataLocations(outside, catalogue).single().page)
    }

    @Test fun multiPagePagesAreListedAsRanges() {
        org.junit.Assert.assertEquals("2–5, 8", overviewPageRanges(listOf(5, 2, 3, 8, 4, 3)))
        org.junit.Assert.assertEquals("7", overviewPageRanges(listOf(7)))
    }
}
