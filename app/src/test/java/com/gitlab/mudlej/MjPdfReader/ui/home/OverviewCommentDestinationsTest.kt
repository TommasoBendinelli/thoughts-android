// Written for Thoughts. License is GPLv3.

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

class OverviewCommentDestinationsTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("overview-comment-destinations").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun pickerUsesOnlyCanonicalLinkedAndUnlinkedLibraryFiles() {
        root.resolve("linked.pdf").writeText("linked")
        root.resolve("unlinked.pdf").writeText("unlinked")
        root.resolve("stray.pdf").writeText("stray")
        val snapshot = SourceLinkedPdfIndexSnapshot(
            filenames = setOf("linked.pdf"),
            pdfSha256ByFilename = mapOf("linked.pdf" to "a".repeat(64)),
            available = true,
            libraryKindsByFilename = mapOf(
                "linked.pdf" to LibraryPdfKind.SOURCE_LINKED,
                "unlinked.pdf" to LibraryPdfKind.UNLINKED,
                "missing.pdf" to LibraryPdfKind.UNLINKED,
            ),
            libraryPdfSha256ByFilename = mapOf(
                "linked.pdf" to "a".repeat(64),
                "unlinked.pdf" to "b".repeat(64),
                "missing.pdf" to "c".repeat(64),
            ),
            libraryDisplayNamesByFilename = mapOf(
                "linked.pdf" to "Thoughts",
                "unlinked.pdf" to "Loose notes",
            ),
            libraryAvailable = true,
        )

        val destinations = overviewCommentDestinationEntries(snapshot, root)

        assertEquals(listOf("linked.pdf", "unlinked.pdf"), destinations.map { File(it.entry.path).name })
        assertEquals(
            listOf(LibraryPdfKind.SOURCE_LINKED, LibraryPdfKind.UNLINKED),
            destinations.map { it.kind },
        )
        assertEquals(listOf("Thoughts", "Loose notes"), destinations.map { it.label })
        assertTrue(destinations.all {
            requireNotNull(File(it.entry.path).parentFile).canonicalFile == root.canonicalFile
        })
    }

    @Test
    fun sourceIndexIsAClosedLinkedOnlyFallback() {
        root.resolve("linked.pdf").writeText("linked")
        root.resolve("legacy.pdf").writeText("legacy")
        val snapshot = SourceLinkedPdfIndexSnapshot(
            filenames = setOf("linked.pdf"),
            pdfSha256ByFilename = mapOf("linked.pdf" to "a".repeat(64)),
            available = true,
        )

        val destinations = overviewCommentDestinationEntries(snapshot, root)

        assertEquals(listOf("linked.pdf"), destinations.map { File(it.entry.path).name })
        assertEquals(LibraryPdfKind.SOURCE_LINKED, destinations.single().kind)
    }

    @Test
    fun pickerFailsClosedWithoutAnAuthoritativeIndex() {
        root.resolve("legacy.pdf").writeText("legacy")

        assertTrue(
            overviewCommentDestinationEntries(SourceLinkedPdfIndexSnapshot(), root).isEmpty(),
        )
    }

    @Test
    fun duplicateDisplayNamesRemainUnambiguous() {
        root.resolve("first.pdf").writeText("first")
        root.resolve("second.pdf").writeText("second")
        val snapshot = SourceLinkedPdfIndexSnapshot(
            libraryKindsByFilename = mapOf(
                "first.pdf" to LibraryPdfKind.SOURCE_LINKED,
                "second.pdf" to LibraryPdfKind.UNLINKED,
            ),
            libraryPdfSha256ByFilename = mapOf(
                "first.pdf" to "a".repeat(64),
                "second.pdf" to "b".repeat(64),
            ),
            libraryDisplayNamesByFilename = mapOf(
                "first.pdf" to "Notes",
                "second.pdf" to "Notes",
            ),
            libraryAvailable = true,
        )

        assertEquals(
            listOf("Notes (first.pdf)", "Notes (second.pdf)"),
            overviewCommentDestinationEntries(snapshot, root).map { it.label },
        )
    }
}
