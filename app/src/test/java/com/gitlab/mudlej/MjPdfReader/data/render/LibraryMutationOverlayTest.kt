// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutation
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutationStore
import com.gitlab.mudlej.MjPdfReader.ui.home.libraryDisplayEntries
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The phone's Library shows queued Library changes over whichever generation is active. */
class LibraryMutationOverlayTest {
    private lateinit var temporary: File
    private lateinit var canonical: File
    private lateinit var mutationRoot: File
    private val publishedSha = "a".repeat(64)

    @Before
    fun setUp() {
        temporary = Files.createTempDirectory("library-overlay").toFile()
        canonical = File(temporary, "current-pdfs").apply { mkdirs() }
        mutationRoot = LibraryMutationStore.defaultRoot(canonical)
    }

    @After
    fun tearDown() {
        temporary.walkTopDown().forEach { it.setWritable(true) }
        temporary.deleteRecursively()
    }

    private fun store() = LibraryMutationStore(
        mutationRoot,
        registerImport = { _, _, _ -> "4b1e2f3a-9c8d-4e7f-a6b5-000000000001" },
        forgetImport = {},
    )

    private fun writeLibrary(revision: String, vararg entries: Triple<String, String, String>) {
        File(canonical, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME).writeText(
            """{"schema_version":1,"library_revision":"$revision","source_index_sha256":"${"9".repeat(64)}","pdfs":[""" +
                entries.joinToString(",") { (filename, sha, name) ->
                    """{"filename":"$filename","kind":"unlinked","pdf_sha256":"$sha","display_name":"$name","archived":false}"""
                } + "]}",
        )
        entries.forEach { (filename, _, _) -> File(canonical, filename).writeText("%PDF-published") }
    }

    private fun addPdf(filename: String): LibraryMutation {
        val staged = File(temporary, "staged-$filename").apply { writeText("%PDF-1.4 $filename") }
        val digest = MessageDigest.getInstance("SHA-256").digest(staged.readBytes()).joinToString("") { "%02x".format(it) }
        return store().importPdf(staged, filename, digest)
    }

    private fun snapshot() = SourceLinkedPdfIndex(canonical).apply { invalidate() }.snapshot()

    @Test
    fun importRenameAndArchiveShowAtOnceAndSurviveARefreshedGeneration() {
        writeLibrary("1".repeat(64), Triple("published.pdf", publishedSha, "Published"))
        val add = addPdf("added.pdf")
        store().enqueueRename("added.pdf", add.sha256, "My import")
        store().enqueueRename("published.pdf", publishedSha, "Renamed")
        store().enqueueArchive("published.pdf", publishedSha, true)

        fun assertProjection(snapshot: SourceLinkedPdfIndexSnapshot) {
            assertEquals(LibraryPdfKind.UNLINKED, snapshot.libraryKindsByFilename["added.pdf"])
            assertEquals(add.sha256, snapshot.libraryPdfSha256ByFilename["added.pdf"])
            assertEquals("My import", snapshot.libraryDisplayNamesByFilename["added.pdf"])
            assertEquals("Renamed", snapshot.libraryDisplayNamesByFilename["published.pdf"])
            assertEquals(setOf("published.pdf"), snapshot.archivedLibraryFilenames)
            val local = store().pdfFile(add)
            assertEquals(local, snapshot.pendingLibraryPdfFiles["added.pdf"])
            assertEquals(LibraryPdfKind.UNLINKED, snapshot.libraryKindFor(local, canonical))
            assertEquals(LibraryPdfKind.UNLINKED, snapshot.libraryKindFor(File(canonical, "added.pdf"), canonical))
            assertNull(snapshot.libraryKindFor(File(temporary, "added.pdf"), canonical))
            assertNull("Unpublished PDFs have no citable canonical identity",
                snapshot.canonicalLibraryPdfSha256(local, canonical))
            val entries = libraryDisplayEntries(snapshot, canonical).map { it.path }
            assertTrue(entries.containsAll(listOf(local.path, File(canonical, "published.pdf").path)))
            assertFalse(File(canonical, "added.pdf").path in entries)
        }
        assertProjection(snapshot())

        // Another Sync (or any other path) activates a new generation before the changes are sent.
        writeLibrary("2".repeat(64), Triple("published.pdf", publishedSha, "Published"), Triple("new.pdf", "b".repeat(64), "New"))
        val refreshed = snapshot()
        assertProjection(refreshed)
        assertEquals("New", refreshed.libraryDisplayNamesByFilename["new.pdf"])

        // After the process restarts the queue is read again.
        assertProjection(SourceLinkedPdfIndex(canonical).snapshot())
    }

    @Test
    fun changesApplyOnlyToThePdfVersionTheyWereMadeOn() {
        writeLibrary("1".repeat(64), Triple("published.pdf", "c".repeat(64), "Replaced elsewhere"))
        store().enqueueRename("published.pdf", publishedSha, "Stale")
        store().enqueueArchive("published.pdf", publishedSha, true)
        val snapshot = snapshot()
        assertEquals("Replaced elsewhere", snapshot.libraryDisplayNamesByFilename["published.pdf"])
        assertTrue(snapshot.archivedLibraryFilenames.isEmpty())
    }

    @Test
    fun publishedFilenameWinsOverAnImportThatClashedWithIt() {
        val add = addPdf("clash.pdf")
        writeLibrary("1".repeat(64), Triple("clash.pdf", publishedSha, "Theirs"))
        val snapshot = snapshot()
        assertEquals(publishedSha, snapshot.libraryPdfSha256ByFilename["clash.pdf"])
        assertFalse("clash.pdf" in snapshot.pendingLibraryPdfFiles)
        assertTrue(store().pdfFile(add).isFile)
    }

    @Test
    fun deliveredImportUsesThePublishedCopyOnceActive() {
        val add = addPdf("added.pdf")
        store().markDelivered(add.requestId)
        writeLibrary("1".repeat(64), Triple("added.pdf", add.sha256, ""))
        val snapshot = snapshot()
        assertTrue(snapshot.pendingLibraryPdfFiles.isEmpty())
        assertEquals(File(canonical, "added.pdf"), snapshot.libraryPdfFile("added.pdf", canonical))
    }

    @Test
    fun withoutAnyLibraryIndexTheOverlayStaysOff() {
        // Library availability decides how comments are routed; a queue alone must not flip it.
        addPdf("added.pdf")
        val snapshot = snapshot()
        assertFalse(snapshot.libraryAvailable)
        assertTrue(snapshot.libraryKindsByFilename.isEmpty())
    }

    @Test
    fun unreadableQueueLeavesTheLibraryAsPublished() {
        writeLibrary("1".repeat(64), Triple("published.pdf", publishedSha, "Published"))
        LibraryMutationStore.stateFile(mutationRoot).apply { parentFile?.mkdirs(); writeText("{broken") }
        val snapshot = snapshot()
        assertTrue(snapshot.libraryAvailable)
        assertEquals(setOf("published.pdf"), snapshot.libraryFilenames)
    }

    @Test
    fun queueChangesInvalidateTheCachedSnapshot() {
        writeLibrary("1".repeat(64), Triple("published.pdf", publishedSha, "Published"))
        val index = SourceLinkedPdfIndex(canonical)
        assertEquals("Published", index.snapshot().libraryDisplayNamesByFilename["published.pdf"])
        store().enqueueRename("published.pdf", publishedSha, "Renamed")
        assertEquals("Renamed", index.snapshot().libraryDisplayNamesByFilename["published.pdf"])
    }
}
