// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicalCommentOverlayProjectionTest {

    @Test fun strikethroughPreservesClassForPdfAndSourceLinkedLocations() {
        for (scope in listOf("pdf_only", "source_linked")) {
            val comment = logicalComment("strike", LogicalCommentPdfLocation("concepts.pdf", "a".repeat(64), 1,
                "exact", regions = listOf(listOf(.1f, .2f, .4f, .22f), listOf(.1f, .25f, .3f, .27f))))
                .copy(scopeState = scope, payloadJson = """{"subtype":"StrikeOut","contents":""}""")
            val placed = logicalCommentOverlaySpecs(listOf(comment), "concepts.pdf", "a".repeat(64))
            assertEquals(2, placed.size)
            assertTrue(placed.all { it.subtype == 3 })
            assertTrue(logicalCommentOverlaySpecs(listOf(comment), "concepts.pdf", "b".repeat(64)).isEmpty())
            val unknown = comment.copy(payloadJson = """{"subtype":"Unsupported"}""")
            assertTrue(logicalCommentOverlaySpecs(listOf(unknown), "concepts.pdf", "a".repeat(64)).isEmpty())
        }
    }

    @Test fun rawOriginNeedsTheExactPdfAndValidPageDimensions() {
        val comment = logicalComment("raw", LogicalCommentPdfLocation("concepts.pdf", "a".repeat(64), 1, "exact")).copy(
            correspondence = null, scopeState = "pdf_only",
            payloadJson = """{"subtype":"Text","origin":{"collection":"concepts","pdf_sha256":"${"a".repeat(64)}","geometry_format":"pdf_points_bottom_left","raw_pdf_geometry":{"page":4,"rect":[100,300,120,280]}}}""",
        )
        val placed = logicalCommentOverlaySpecs(listOf(comment), "concepts.pdf", "a".repeat(64), pageSize = { 600f to 800f }).single()
        assertEquals(3, placed.pageIndex)
        assertEquals(0.625f, placed.top, 0.0001f)
        assertTrue(logicalCommentOverlaySpecs(listOf(comment), "concepts.pdf", "b".repeat(64), pageSize = { 600f to 800f }).isEmpty())
        assertTrue(logicalCommentOverlaySpecs(listOf(comment), "concepts.pdf", "a".repeat(64)).isEmpty())
        assertTrue(logicalCommentOverlaySpecs(listOf(comment), "concepts.pdf", "a".repeat(64), pageSize = { 10f to 10f }).isEmpty())
    }

    @Test
    fun openingPdfNormallyShowsEveryExactRootForItsCurrentRevision() {
        val current = logicalComment(
            id = "current",
            location = LogicalCommentPdfLocation(
                pdfFilename = "concepts.pdf",
                pdfSha256 = "a".repeat(64),
                page = 1,
                status = "exact",
                regions = listOf(listOf(0.1f, 0.2f, 0.3f, 0.4f)),
            ),
        )
        val stale = logicalComment(
            id = "stale",
            location = current.locations.single().copy(pdfSha256 = "b".repeat(64)),
        )
        val reply = current.copy(id = "reply", parentId = "current")

        val overlays = logicalCommentOverlaySpecs(
            listOf(current, stale, reply),
            "concepts.pdf",
            "a".repeat(64),
        )

        assertEquals(1, overlays.size)
        assertEquals("current", overlays.single().commentId)
        assertEquals(0, overlays.single().pageIndex)
    }

    @Test
    fun pointPlacementBecomesATappableMarkerAndSelectionIsPreserved() {
        val comment = logicalComment(
            id = "point",
            location = LogicalCommentPdfLocation(
                pdfFilename = "concepts.pdf",
                pdfSha256 = "a".repeat(64),
                page = 4,
                status = "exact",
                point = listOf(0.5f, 0.25f),
            ),
        )

        val overlay = logicalCommentOverlaySpecs(
            listOf(comment),
            "concepts.pdf",
            "a".repeat(64),
            selectedCommentId = "point",
        ).single()

        assertTrue(overlay.selected)
        assertEquals(3, overlay.pageIndex)
        assertEquals(0.488f, overlay.left, 0.0001f)
        assertEquals(0.262f, overlay.bottom, 0.0001f)
    }

    private fun logicalComment(
        id: String,
        location: LogicalCommentPdfLocation,
    ) = OfflineLogicalComment(
        id = id,
        threadRootId = id,
        parentId = null,
        revision = 1,
        deleted = false,
        scopeState = "source_linked",
        sourceFile = "docs-concepts/tex/source/qwen3.tex",
        sourceLine = 1,
        originCollection = "concepts",
        originPageIndex = 0,
        payloadJson = "{}",
        correspondence = placement(id, listOf(location)),
    )
}

private fun placement(id: String, locations: List<com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation>) =
    com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence(
        "comment:$id", "comment", id, id, "active", "source_linked", "", "", emptyList(), locations)
