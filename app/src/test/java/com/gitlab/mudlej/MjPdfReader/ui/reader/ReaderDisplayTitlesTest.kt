// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReaderDisplayTitlesTest {
    private val directory = File("/library/current_pdfs")
    private val path = File(directory, "import-123.pdf").path
    private fun catalogue(olmo: String = "olmo3") = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("id", "models"); addProperty("name", "Models"); addProperty("target", "models.pdf")
            add("documents", JsonArray().apply {
                for ((id, title) in listOf("olmo-id" to olmo, "qwen-id" to "qwen3")) add(JsonObject().apply {
                    addProperty("document_id", id); addProperty("name", title); addProperty("filename", "import-123.pdf")
                })
            })
        })
    }

    @Test fun selectedDocumentWinsOverSharedFilenameAndRefreshesAfterRenameOrSync() {
        val target = ReaderTitleTarget(documentId = "olmo-id", fallbackTitle = "Old title")
        assertEquals("olmo3", readerDisplayTitle(catalogue(), mapOf("import-123.pdf" to "Import"), target, path, directory))
        assertEquals("OLMo 3.1", readerDisplayTitle(catalogue("OLMo 3.1"), emptyMap(), target, path, directory))
        assertEquals("qwen3", readerDisplayTitle(catalogue(), emptyMap(), ReaderTitleTarget(documentId = "qwen-id"), path, directory))
    }

    @Test fun selectedIdentitySurvivesPdfRelocationAndKeepsLabelIfCatalogueIsUnavailable() {
        val target = ReaderTitleTarget(documentId = "olmo-id", fallbackTitle = "OLMo 3")
        assertEquals("olmo3", readerDisplayTitle(catalogue(), emptyMap(), target, "/new/location.pdf", directory))
        assertEquals("OLMo 3", readerDisplayTitle(JsonArray(), emptyMap(), target, path, directory))
    }

    @Test fun aggregateProjectHasItsOwnTitleAndAmbiguousFilenameNeverSelectsASibling() {
        assertEquals("Models", readerDisplayTitle(catalogue(), emptyMap(), ReaderTitleTarget(projectId = "models"), path, directory))
        assertNull(readerDisplayTitle(catalogue(), emptyMap(), ReaderTitleTarget(), path, directory))
    }

    @Test fun unrelatedExternalCopyCannotAcquireLibraryTitleFromFilename() {
        assertNull(readerDisplayTitle(catalogue(), mapOf("import-123.pdf" to "Library title"), ReaderTitleTarget(), "/downloads/import-123.pdf", directory))
        assertEquals("MiMo-V2.6", readerDisplayTitle(JsonArray(), mapOf("import-123.pdf" to "MiMo-V2.6"), ReaderTitleTarget(), path, directory))
    }

    @Test fun refreshedMetadataOverridesBothCurrentFilenameAndInactiveTaskLabel() {
        val reader = "Reader"
        val tabs = buildOpenDocumentTabs(listOf(
            DocumentTaskSnapshot(1, reader, reader, "file://$path", "import-123.pdf"),
            DocumentTaskSnapshot(2, reader, reader, "file://$path", "old qwen title")),
            1, reader, CurrentDocumentSnapshot("file://$path", "import-123.pdf"),
            mapOf(1 to "OLMo 3.1", 2 to "Qwen 3"))
        assertEquals(listOf("Qwen 3", "OLMo 3.1"), tabs.map { it.title })
    }

    @Test fun projectNameComesFromTheLibraryProjectContainingTheDocument() {
        val projects = catalogue().apply {
            add(JsonObject().apply {
                addProperty("id", "bench"); addProperty("name", " Benchmarks ")
                add("documents", JsonArray().apply { add(JsonObject().apply {
                    addProperty("document_id", "export-id"); addProperty("filename", "built.pdf"); addProperty("attachment_filename", "export.pdf")
                }) })
            })
        }
        assertEquals("Models", readerProjectName(projects, ReaderTitleTarget(), path, directory))
        assertEquals("Models", readerProjectName(projects, ReaderTitleTarget(projectId = "models"), "/elsewhere/reading.pdf", directory))
        assertEquals("Benchmarks", readerProjectName(projects, ReaderTitleTarget(documentId = "export-id"), "/elsewhere/x.pdf", directory))
        assertEquals("Benchmarks", readerProjectName(projects, ReaderTitleTarget(), File(directory, "export.pdf").path, directory))
        // A same-named file outside the Library folder belongs to no project.
        assertNull(readerProjectName(projects, ReaderTitleTarget(), "/sdcard/Download/import-123.pdf", directory))
        assertNull(readerProjectName(JsonArray(), ReaderTitleTarget(), path, directory))
    }
}
