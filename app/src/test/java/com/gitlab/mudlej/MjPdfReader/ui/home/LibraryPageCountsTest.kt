// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryPageCountsTest {
    private val index = SourceLinkedPdfIndexSnapshot(filenames = setOf("concepts.pdf"), available = true)
    private fun documents(vararg json: String): List<JsonObject> = json.map { JsonParser.parseString(it).asJsonObject }

    @Test fun pagesSplitIntoSourceLinkedDocumentsAndPlainPdfs() {
        val counts = libraryPageCounts(documents(
            """{"filename":"concepts.pdf","source_file":"concepts/main.tex","page_count":100}""",
            """{"filename":"talk.pdf","source_file":"notes/talk.md","page_count":20}""",
            // Linked by the source index alone (no source_file in the record).
            """{"filename":"concepts.pdf","page_count":5}""",
            """{"filename":"paper.pdf","imported":true,"source_file":"third_party/paper.pdf","page_count":200}""",
            """{"filename":"scan.pdf","page_count":35}""",
        ), index)
        assertEquals(LibraryPageCounts(source = 125, pdf = 235), counts)
        assertEquals("125 source + 235 PDF pages", libraryPageCountsText(counts))
    }

    @Test fun documentsWithoutPagesCountForNothing() {
        val counts = libraryPageCounts(documents(
            """{"source_file":"notes/new.md","page_count":0}""",
            """{"filename":"paper.pdf","imported":true}""",
            """{"filename":"odd.pdf","imported":true,"page_count":null}""",
        ), index)
        assertEquals(LibraryPageCounts(), counts)
        assertNull(libraryPageCountsText(counts))
    }

    @Test fun aKindWithNoPagesIsLeftOut() {
        assertEquals("22 source pages", libraryPageCountsText(LibraryPageCounts(source = 22)))
        assertEquals("153 PDF pages", libraryPageCountsText(LibraryPageCounts(pdf = 153)))
        assertEquals("1 PDF page", libraryPageCountsText(LibraryPageCounts(pdf = 1)))
        assertEquals("1 source + 1 PDF pages", libraryPageCountsText(LibraryPageCounts(source = 1, pdf = 1)))
    }

    @Test fun pagesOfUnknownKindBeforeTheSourceIndexArrives() {
        val counts = libraryPageCounts(documents(
            """{"filename":"talk.pdf","source_file":"notes/talk.md","page_count":20}""",
            """{"filename":"other.pdf","page_count":12}""",
        ), SourceLinkedPdfIndexSnapshot())
        assertEquals(LibraryPageCounts(source = 20, unknown = 12), counts)
        assertEquals("20 source + 12 other pages", libraryPageCountsText(counts))
        // Nothing known yet: the plain total, as before.
        assertEquals("12 pages", libraryPageCountsText(LibraryPageCounts(unknown = 12)))
        assertEquals("1 page", libraryPageCountsText(LibraryPageCounts(unknown = 1)))
    }
}
