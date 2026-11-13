// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LibraryMutationStoreTest {
    private lateinit var temporary: File
    private lateinit var root: File
    private val registered = linkedMapOf<String, Pair<String, File>>()
    private val forgotten = mutableListOf<String>()
    private var changes = 0

    @Before
    fun setUp() {
        temporary = Files.createTempDirectory("library-mutation-store").toFile()
        root = File(temporary, "library-mutations")
    }

    @After
    fun tearDown() {
        temporary.setWritableRecursively()
        temporary.deleteRecursively()
    }

    private fun store() = LibraryMutationStore(
        root,
        onChanged = { changes++ },
        registerImport = { filename, sha256, file ->
            val existing = registered.entries.firstOrNull { it.value.first == filename }
            require(existing == null || existing.value.second.let { sha(it) } == sha256) {
                "This filename belongs to another PDF version"
            }
            existing?.key ?: "4b1e2f3a-9c8d-4e7f-a6b5-${(100000000000 + registered.size).toString().padStart(12, '0')}"
                .also { registered[it] = filename to file }
        },
        forgetImport = { forgotten += it; registered.remove(it) },
    )

    private fun staged(name: String, body: String = "%PDF-1.4 $name"): File =
        File(temporary, "staged-$name").apply { writeText(body) }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun queueSurvivesRestartInOrderAndAddKeepsTheStoredCopy() {
        val pdf = staged("paper")
        val digest = sha(pdf)
        val add = store().importPdf(pdf, "paper.pdf", digest, createdAtEpochMillis = 10)
        store().enqueueRename("paper.pdf", digest, "  My paper  ", createdAtEpochMillis = 20)
        store().enqueueArchive("other.pdf", "c".repeat(64), true, createdAtEpochMillis = 30)

        val restarted = store().operations()
        assertEquals(
            listOf(LibraryMutation.Type.ADD, LibraryMutation.Type.RENAME, LibraryMutation.Type.ARCHIVE),
            restarted.map { it.type },
        )
        assertEquals("My paper", restarted[1].displayName)
        assertEquals(digest, restarted[1].sha256)
        assertTrue(restarted.all { it.state == LibraryMutation.State.PENDING })
        assertFalse("The staged copy is consumed once stored", pdf.exists())
        val stored = store().pdfFile(add)
        assertEquals("paper.pdf", stored.name)
        assertEquals(digest, sha(stored))
        assertEquals(add.documentId, registered.keys.single())
        assertEquals(stored, registered.values.single().second)
        assertEquals(mapOf(digest to stored), store().storedPdfs())
        assertTrue(changes >= 3)
    }

    @Test
    fun takenFilenameKeepsTheStagedCopyAndQueuesNothing() {
        val first = staged("one")
        store().importPdf(first, "paper.pdf", sha(first))
        val second = staged("two")
        try {
            store().importPdf(second, "paper.pdf", sha(second))
            fail("A second import under the same filename must be refused")
        } catch (_: LibraryFilenameTakenException) {
        }
        val third = staged("three")
        try {
            store().importPdf(third, "taken.pdf", sha(third), isFilenameTaken = { it == "taken.pdf" })
            fail("A filename the Library already lists must be refused")
        } catch (_: LibraryFilenameTakenException) {
        }
        assertTrue(second.isFile)
        assertTrue(third.isFile)
        assertEquals(1, store().operations().size)
    }

    @Test
    fun registryConflictIsReportedAsTakenFilenameAndForgetsNothing() {
        val first = staged("one")
        val firstAdd = store().importPdf(first, "paper.pdf", sha(first))
        assertTrue(store().cancelAdd("paper.pdf", firstAdd.sha256))
        // A cancelled import forgets its identity, so the name is free for another PDF.
        assertEquals(listOf(firstAdd.documentId), forgotten)
        val second = staged("two")
        store().importPdf(second, "paper.pdf", sha(second))
        assertEquals(1, store().operations().size)
    }

    @Test
    fun laterChangeOfTheSameFieldSupersedesAnUnsentOneAndMovesBehindTheImport() {
        val pdf = staged("paper")
        val digest = sha(pdf)
        store().enqueueRename("paper.pdf", digest, "First")
        store().importPdf(pdf, "paper.pdf", digest)
        store().enqueueArchive("paper.pdf", digest, true)
        store().enqueueRename("paper.pdf", digest, "Second")
        store().enqueueArchive("paper.pdf", digest, false)

        val operations = store().operations()
        assertEquals(
            listOf(LibraryMutation.Type.ADD, LibraryMutation.Type.RENAME, LibraryMutation.Type.ARCHIVE),
            operations.map { it.type },
        )
        assertEquals("Second", operations[1].displayName)
        assertFalse(operations[2].archived)
    }

    @Test
    fun deliveredRenameIsNotSupersededButResent() {
        val digest = "a".repeat(64)
        val first = store().enqueueRename("paper.pdf", digest, "First")
        store().markDelivered(first.requestId)
        store().enqueueRename("paper.pdf", digest, "Second")
        assertEquals(listOf("First", "Second"), store().operations().map { it.displayName })
        assertEquals(listOf("Second"), store().pending().map { it.displayName })
    }

    @Test
    fun cancelWithdrawsAnUnsentImportWithItsChangesButNotADeliveredOne() {
        val pdf = staged("paper")
        val digest = sha(pdf)
        val add = store().importPdf(pdf, "paper.pdf", digest)
        store().enqueueRename("paper.pdf", digest, "Name")
        store().enqueueRename("other.pdf", "b".repeat(64), "Other")
        val stored = store().pdfFile(add)

        assertTrue(store().cancelAdd("paper.pdf", digest))
        assertEquals(listOf("other.pdf"), store().operations().map { it.filename })
        assertFalse(stored.exists())

        val again = staged("again")
        val second = store().importPdf(again, "again.pdf", sha(again))
        store().markDelivered(second.requestId)
        assertFalse(store().cancelAdd("again.pdf", second.sha256))
        assertTrue(store().pdfFile(second).isFile)
    }

    @Test
    fun retireRemovesOnlyChangesDeliveredBeforeTheFetchAndTheirCopies() {
        val pdf = staged("paper")
        val add = store().importPdf(pdf, "paper.pdf", sha(pdf))
        val rename = store().enqueueRename("paper.pdf", add.sha256, "Name")
        store().markDelivered(add.requestId)
        val beforeFetch = store().deliveredRequestIds()
        store().markDelivered(rename.requestId)

        assertEquals(1, store().retire(beforeFetch))
        assertFalse(store().pdfFile(add).exists())
        assertEquals(listOf(rename.requestId), store().operations().map { it.requestId })
        assertEquals(1, store().retire(store().deliveredRequestIds()))
        assertTrue(store().operations().isEmpty())
        assertFalse(LibraryMutationStore.stateFile(root).exists())
    }

    @Test
    fun conflictIsKeptAndCountsAsAwaitingSyncButNotAsPending() {
        val pdf = staged("paper")
        val add = store().importPdf(pdf, "paper.pdf", sha(pdf))
        store().markConflict(add.requestId, "another PDF already uses this filename there")
        val delivered = store().enqueueArchive("other.pdf", "b".repeat(64), true)
        store().markDelivered(delivered.requestId)

        assertTrue(store().pending().isEmpty())
        assertEquals(listOf(add.requestId), store().awaitingSync().map { it.requestId })
        assertEquals("another PDF already uses this filename there", store().operations().first().failureDetail)
        assertNotNull(store().storedPdfs()[add.sha256])
    }

    @Test
    fun retryableFailureDetailKeepsTheChangePending() {
        val rename = store().enqueueRename("paper.pdf", "a".repeat(64), "Name")
        store().recordFailure(rename.requestId, "connection reset")
        val stored = store().pending().single()
        assertEquals(LibraryMutation.State.PENDING, stored.state)
        assertEquals("connection reset", stored.failureDetail)
    }

    @Test
    fun unreadableQueueIsAnErrorNotAnEmptyQueue() {
        LibraryMutationStore.stateFile(root).apply { parentFile?.mkdirs(); writeText("{\"schema_version\":99}") }
        try {
            store().operations()
            fail("An unsupported queue must not read as empty")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun invalidDisplayNameIsRejectedBeforeSaving() {
        try {
            store().enqueueRename("paper.pdf", "a".repeat(64), "   ")
            fail("A blank name must be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(store().operations().isEmpty())
    }

    private fun File.setWritableRecursively() {
        walkTopDown().forEach { it.setWritable(true) }
    }
}
