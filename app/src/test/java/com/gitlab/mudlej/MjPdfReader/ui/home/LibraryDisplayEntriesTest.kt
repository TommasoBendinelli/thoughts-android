// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryDisplayEntriesTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("library-display-entries").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun canonicalLibraryCatalogueListsOnlyDownloadedCataloguePdfs() {
        val linked = root.resolve("linked.pdf").apply { writeText("linked") }
        val unlinked = root.resolve("unlinked.pdf").apply { writeText("unlinked") }
        root.resolve("not-in-catalogue.pdf").writeText("stray")
        val snapshot = SourceLinkedPdfIndexSnapshot(
            available = true,
            libraryKindsByFilename = mapOf(
                linked.name to LibraryPdfKind.SOURCE_LINKED,
                unlinked.name to LibraryPdfKind.UNLINKED,
                "missing.pdf" to LibraryPdfKind.UNLINKED,
            ),
            libraryPdfSha256ByFilename = mapOf(
                linked.name to "a".repeat(64),
                unlinked.name to "b".repeat(64),
                "missing.pdf" to "d".repeat(64),
            ),
            libraryAvailable = true,
        )

        val entries = libraryDisplayEntries(snapshot, root)

        assertEquals(listOf("linked.pdf", "unlinked.pdf"), entries.map { File(it.path).name })
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), entries.map { it.hash })
        assertEquals(listOf(linked.length(), unlinked.length()), entries.map { it.size })
        assertEquals(root.canonicalFile, checkNotNull(File(entries.first().path).parentFile).canonicalFile)
    }

    @Test
    fun sourceIndexIsCanonicalFallbackBeforeLibraryIndexArrives() {
        root.resolve("linked.pdf").writeText("linked")
        val snapshot = SourceLinkedPdfIndexSnapshot(
            filenames = setOf("linked.pdf"),
            pdfSha256ByFilename = mapOf("linked.pdf" to "a".repeat(64)),
            available = true,
        )

        val entries = libraryDisplayEntries(snapshot, root)

        assertEquals(listOf("linked.pdf"), entries.map { File(it.path).name })
        assertEquals("a".repeat(64), entries.single().hash)
    }

    @Test
    fun noEntriesWhenNoCanonicalIndexExists() {
        root.resolve("local.pdf").writeText("local")

        assertTrue(libraryDisplayEntries(SourceLinkedPdfIndexSnapshot(), root).isEmpty())
    }
}
