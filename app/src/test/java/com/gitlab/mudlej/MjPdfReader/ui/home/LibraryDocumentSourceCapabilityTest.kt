// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDocumentSourceCapabilityTest {
    private val linked = SourceLinkedPdfIndexSnapshot(filenames = setOf("project.pdf"), available = true)
    private fun capability(json: String, index: SourceLinkedPdfIndexSnapshot = linked) =
        libraryDocumentSourceCapability(JsonParser.parseString(json).asJsonObject, index)

    @Test fun importedComponentDoesNotInheritProjectSourceLink() {
        assertEquals(SourcePdfCapability.NOT_SOURCE_LINKED,
            capability("""{"filename":"project.pdf","imported":true,"source_file":"third_party/paper.pdf"}"""))
    }

    @Test fun sourceDocumentIsLinkedEvenBeforeBuildOrIndexDownload() {
        assertEquals(SourcePdfCapability.SOURCE_LINKED,
            capability("""{"source_file":"notes/talk.md","start_page":0}""", SourceLinkedPdfIndexSnapshot()))
    }

    @Test fun pdfSourceComponentIsUnlinkedWithoutImportedFlag() {
        assertEquals(SourcePdfCapability.NOT_SOURCE_LINKED,
            capability("""{"filename":"project.pdf","source_file":"third_party/paper.pdf"}"""))
    }

    @Test fun filenameFallbackUsesIndexAndPreservesUnknownState() {
        assertEquals(SourcePdfCapability.SOURCE_LINKED, capability("""{"filename":"project.pdf"}"""))
        assertEquals(SourcePdfCapability.NOT_SOURCE_LINKED, capability("""{"filename":"other.pdf"}"""))
        assertEquals(SourcePdfCapability.INDEX_UNAVAILABLE,
            capability("""{"filename":"other.pdf","source_file":null}""", SourceLinkedPdfIndexSnapshot()))
    }

    @Test fun missingSourcesWarnOnlySourceLinkedDocuments() {
        val document = JsonParser.parseString("""{"source_file":"notes/talk.md","missing_sources":["notes/shared.md","notes/shared.md"]}""").asJsonObject
        assertEquals(listOf("notes/shared.md"), libraryDocumentMissingSources(document, SourcePdfCapability.SOURCE_LINKED))
        assertEquals(emptyList<String>(), libraryDocumentMissingSources(document, SourcePdfCapability.NOT_SOURCE_LINKED))
    }
}
