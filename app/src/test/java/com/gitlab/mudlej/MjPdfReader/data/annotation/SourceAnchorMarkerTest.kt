// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.github.barteksc.pdfviewer.PDFView
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAnchorMarkerTest {

    @Test
    fun excludesGeometryFromAnotherPdfRevision() {
        val activeHash = "a".repeat(64)
        val comments = listOf(
            comment("matching", "anchored", annotationPdfSha256 = activeHash),
            comment("stale", "needs_anchor", annotationPdfSha256 = "b".repeat(64)),
            comment("legacy", "anchored"),
        )

        assertEquals(
            listOf("matching"),
            renderableStoredPdfComments(comments, activeHash).map(StoredPdfComment::id),
        )
    }

    @Test
    fun returnsOnlyUnresolvedRootCommentNames() {
        val comments = listOf(
            comment("anchored", "anchored"),
            comment("needs", "needs_anchor"),
            comment("orphaned", "orphaned"),
            comment("reply", "needs_anchor", inReplyTo = "needs"),
            comment("ordinary", ""),
        )

        assertEquals(
            setOf("needs", "orphaned"),
            sourceAnchorUnresolvedCommentNames(comments),
        )
    }

    private fun comment(
        id: String,
        anchorStatus: String,
        inReplyTo: String = "",
        annotationPdfSha256: String = "",
    ) = StoredPdfComment(
        pageIndex = 0,
        subtype = PDFView.PdfComment.SUBTYPE_TEXT,
        id = id,
        rects = emptyList(),
        color = 0,
        contents = "",
        author = "",
        creationDate = "",
        modifiedDate = "",
        inReplyTo = inReplyTo,
        selectedText = "",
        fragments = emptyList(),
        priority = CommentPriority.NORMAL,
        location = null,
        anchorStatus = anchorStatus,
        annotationPdfSha256 = annotationPdfSha256,
    )
}
