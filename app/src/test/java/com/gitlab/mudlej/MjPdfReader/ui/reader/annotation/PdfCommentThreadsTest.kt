// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.github.barteksc.pdfviewer.PDFView
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfCommentThreadsTest {

    @Test
    fun groupsRepliesUnderRootsAndCountsOnlyThreads() {
        val comments = listOf(
            comment(page = 0, index = 0, name = "root-a"),
            comment(page = 0, index = 1, name = "reply-a1", parent = "root-a"),
            comment(page = 0, index = 2, name = "reply-a2", parent = "reply-a1"),
            comment(page = 2, index = 0, name = "root-b"),
        )

        val threads = buildPdfCommentThreads(comments)

        assertEquals(2, threads.size)
        assertEquals("root-a", threads[0].root.name)
        assertEquals(listOf("reply-a1", "reply-a2"), threads[0].replies.map { it.name })
        assertEquals("root-b", threads[1].root.name)
    }

    @Test
    fun preservesPageAndAnnotationOrderAndKeepsOrphansReachable() {
        val comments = listOf(
            comment(page = 4, index = 3, name = "later"),
            comment(page = 1, index = 8, name = "orphan", parent = "missing"),
            comment(page = 1, index = 2, name = "first"),
        )

        val threads = buildPdfCommentThreads(comments)

        assertEquals(listOf("first", "orphan", "later"), threads.map { it.root.name })
    }

    private fun comment(
        page: Int,
        index: Int,
        name: String,
        parent: String = "",
    ) = PDFView.PdfComment(
        page,
        index,
        1,
        name,
        null,
        null,
        name,
        "Author",
        "D:20260724120000",
        "D:20260724120000",
        parent,
    )
}
