// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.tableofcontents

import com.gitlab.mudlej.MjPdfReader.pdf.TableOfContentsEntry
import com.shockwave.pdfium.PdfDocument
import org.junit.Assert.*
import org.junit.Test

class TableOfContentsPresentationTest {
    private fun bookmark(page: Long, vararg children: PdfDocument.Bookmark) = PdfDocument.Bookmark().apply {
        title = "Section on page $page"
        pageIdx = page
        setChildren(children.toList())
    }

    @Test fun currentSectionUsesDeepestHeadingAndIgnoresInvalidDestinations() {
        val root = TableOfContentsEntry(bookmark(0, bookmark(0), bookmark(4), bookmark(-1)), 0, "0")
        val next = TableOfContentsEntry(bookmark(8), 0, "1")
        assertNull(currentContentsPath(listOf(root, next), -1))
        assertEquals("0.0", currentContentsPath(listOf(root, next), 0))
        assertEquals("0.0", currentContentsPath(listOf(root, next), 3))
        assertEquals("0.1", currentContentsPath(listOf(root, next), 7))
        assertEquals("1", currentContentsPath(listOf(root, next), 8))
    }

    @Test fun outlineOrderDoesNotOverrideTheNearestPrecedingPage() {
        val later = TableOfContentsEntry(bookmark(12), 0, "0")
        val earlier = TableOfContentsEntry(bookmark(3), 0, "1")
        assertNull(currentContentsPath(listOf(later, earlier), 2))
        assertEquals("1", currentContentsPath(listOf(later, earlier), 10))
        assertEquals("0", currentContentsPath(listOf(later, earlier), 14))
    }

    @Test fun navigatingWithoutClosingRebindsTheCurrentRow() {
        val entry = TableOfContentsEntry(bookmark(0), 0, "0")
        val before = TableOfContentsRow(entry, expandable = false, expanded = false, current = false)
        val after = before.copy(current = true)
        val comparator = TableOfContentsComparator()
        assertTrue(comparator.areItemsTheSame(before, after))
        assertFalse(comparator.areContentsTheSame(before, after))
    }
}
