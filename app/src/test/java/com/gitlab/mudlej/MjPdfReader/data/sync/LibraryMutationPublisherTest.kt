// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.tbend.thoughts.sdk.ServerException

class LibraryMutationPublisherTest {
    private lateinit var temporary: File
    private lateinit var store: LibraryMutationStore
    private lateinit var server: FakeServer
    private var nextId = 1

    @Before
    fun setUp() {
        temporary = Files.createTempDirectory("library-mutation-publisher").toFile()
        store = LibraryMutationStore(
            File(temporary, "library-mutations"),
            registerImport = { _, _, _ -> "4b1e2f3a-9c8d-4e7f-a6b5-${(nextId++).toString().padStart(12, '0')}" },
            forgetImport = {},
        )
        server = FakeServer()
    }

    @After
    fun tearDown() {
        temporary.walkTopDown().forEach { it.setWritable(true) }
        temporary.deleteRecursively()
    }

    private fun sha(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun add(filename: String, bytes: Int = 64): LibraryMutation {
        val staged = File(temporary, "staged-$filename").apply { writeBytes("%PDF-$filename".toByteArray() + ByteArray(bytes)) }
        return store.importPdf(staged, filename, sha(staged))
    }

    private fun publish(budget: Long = Long.MAX_VALUE) =
        LibraryMutationPublisher(store, server, ::sha).publish(budget)

    @Test
    fun sendsEveryChangeInOrderAndKeepsThemUntilRetired() {
        val added = add("paper.pdf")
        store.enqueueRename("paper.pdf", added.sha256, "Paper")
        store.enqueueArchive("old.pdf", "b".repeat(64), true)

        val result = publish()

        assertEquals(3, result.delivered)
        assertEquals(
            listOf("upload:${added.sha256}", "import:paper.pdf", "rename:paper.pdf:Paper", "archive:old.pdf:true"),
            server.calls,
        )
        assertTrue(store.operations().all { it.state == LibraryMutation.State.DELIVERED })
        assertTrue(store.pending().isEmpty())
        assertTrue("The local copy stays until the published PDF is active", store.pdfFile(added).isFile)

        server.calls.clear()
        assertEquals(0, publish().delivered)
        assertTrue("A repeated Sync sends nothing again", server.calls.isEmpty())
    }

    @Test
    fun importFedoraAlreadyHasCountsAsDeliveredWithoutUploadingAgain() {
        val added = add("paper.pdf")
        server.objects += added.sha256
        server.importStatus = "existing"

        assertEquals(1, publish().delivered)
        assertEquals(listOf("import:paper.pdf"), server.calls)
        assertEquals(LibraryMutation.State.DELIVERED, store.operations().single().state)
    }

    @Test
    fun retryableFailureKeepsTheChangeQueuedAndStopsInOrder() {
        add("first.pdf")
        store.enqueueRename("second.pdf", "b".repeat(64), "Second")
        server.failImport = IOException("connection reset")

        try {
            publish()
            fail("A transport failure fails the Sync")
        } catch (error: IOException) {
            assertEquals("connection reset", error.message)
        }
        assertEquals(2, store.pending().size)
        assertEquals("connection reset", store.pending().first().failureDetail)
        assertFalse("Later changes wait for the earlier one", server.calls.any { it.startsWith("rename") })

        server.failImport = ServerException(503, "busy")
        try {
            publish()
            fail("A server failure fails the Sync")
        } catch (_: ServerException) {
        }
        assertEquals(2, store.pending().size)

        server.failImport = null
        server.calls.clear()
        assertEquals(2, publish().delivered)
        assertEquals("The object uploaded by the failed attempt is not uploaded again",
            listOf("import:first.pdf", "rename:second.pdf:Second"), server.calls)
    }

    @Test
    fun invalidAcknowledgementKeepsTheChangeQueued() {
        store.enqueueRename("paper.pdf", "a".repeat(64), "Paper")
        server.renameEcho = "Something else"
        try {
            publish()
            fail("An acknowledgement for a different change is not accepted")
        } catch (_: IOException) {
        }
        assertEquals(1, store.pending().size)
    }

    @Test
    fun refusedImportIsKeptWithItsChangesWhileOtherChangesProceed() {
        val clash = add("clash.pdf")
        store.enqueueRename("clash.pdf", clash.sha256, "Mine")
        store.enqueueArchive("other.pdf", "b".repeat(64), true)
        server.importConflicts += "clash.pdf"

        try {
            publish()
            fail("A conflict fails the Sync with a message")
        } catch (error: LibraryMutationConflictException) {
            assertTrue(error.userMessage, error.userMessage.contains("“clash.pdf” was not added to the shared Library"))
            assertTrue(error.userMessage, error.userMessage.contains("It stays on this phone"))
        }
        val operations = store.operations().associateBy { it.filename to it.type }
        assertEquals(LibraryMutation.State.CONFLICT, operations.getValue("clash.pdf" to LibraryMutation.Type.ADD).state)
        assertEquals(LibraryMutation.State.PENDING, operations.getValue("clash.pdf" to LibraryMutation.Type.RENAME).state)
        assertEquals(LibraryMutation.State.DELIVERED, operations.getValue("other.pdf" to LibraryMutation.Type.ARCHIVE).state)
        assertTrue(store.pdfFile(clash).isFile)
        assertFalse(server.calls.any { it.startsWith("rename:clash.pdf") })

        // The refused import is not sent again; the next Sync is not blocked by it.
        server.calls.clear()
        assertEquals(0, publish().delivered)
        assertTrue(server.calls.isEmpty())
        assertEquals(listOf(clash.requestId, store.operations()[1].requestId), store.awaitingSync().map { it.requestId })
    }

    @Test
    fun staleRenameOrArchiveIsDroppedAndNamedInTheFailure() {
        store.enqueueRename("replaced.pdf", "a".repeat(64), "Old version")
        store.enqueueArchive("gone.pdf", "b".repeat(64), true)
        server.metadataStatus["replaced.pdf"] = 409
        server.metadataStatus["gone.pdf"] = 404

        try {
            publish()
            fail("A refused change fails the Sync with a message")
        } catch (error: LibraryMutationConflictException) {
            assertTrue(error.userMessage, error.userMessage.contains("The new name of “replaced.pdf” was not applied"))
            assertTrue(error.userMessage, error.userMessage.contains("Archiving “gone.pdf” was not applied"))
        }
        assertTrue(store.operations().isEmpty())
    }

    @Test
    fun importLargerThanTheMobileDataBudgetWaitsWithEverythingAfterIt() {
        store.enqueueRename("before.pdf", "a".repeat(64), "Before")
        val small = add("small.pdf", bytes = 10)
        val large = add("large.pdf", bytes = 5_000)
        store.enqueueRename("after.pdf", "b".repeat(64), "After")

        val result = publish(budget = 1_000)

        assertEquals(2, result.delivered)
        assertEquals(store.pdfFile(large).length(), result.deferredUploadBytes)
        assertEquals(2, result.deferredIdentities.size)
        assertEquals(listOf("rename:before.pdf:Before", "upload:${small.sha256}", "import:small.pdf"), server.calls)
        assertEquals(listOf("large.pdf", "after.pdf"), store.pending().map { it.filename })

        // Approved (or on Wi-Fi): the rest goes, in order.
        server.calls.clear()
        val approved = publish()
        assertEquals(2, approved.delivered)
        assertEquals(listOf("upload:${large.sha256}", "import:large.pdf", "rename:after.pdf:After"), server.calls)
    }

    @Test
    fun refusalMetBeforeAWaitingUploadIsReportedAndTheUploadWaits() {
        add("clash.pdf", bytes = 10)
        val large = add("large.pdf", bytes = 5_000)
        server.importConflicts += "clash.pdf"
        try {
            publish(budget = 1_000)
            fail("The refusal is reported")
        } catch (error: LibraryMutationConflictException) {
            assertTrue(error.userMessage, error.userMessage.contains("clash.pdf"))
        }
        val waiting = store.pending().single()
        assertEquals(large.requestId, waiting.requestId)
        assertEquals("", waiting.failureDetail)
        val next = publish(budget = 1_000)
        assertEquals(0, next.delivered)
        assertEquals(store.pdfFile(large).length(), next.deferredUploadBytes)
    }

    @Test
    fun importWhoseObjectFedoraHasNeedsNoBudget() {
        val large = add("large.pdf", bytes = 5_000)
        server.objects += large.sha256
        assertEquals(1, publish(budget = 0).delivered)
    }

    @Test
    fun missingLocalCopyIsReportedAndNotSent() {
        val added = add("paper.pdf")
        val stored = store.pdfFile(added)
        stored.parentFile!!.setWritable(true)
        stored.setWritable(true)
        assertTrue(stored.delete())
        try {
            publish()
            fail("A missing copy is reported")
        } catch (error: LibraryMutationConflictException) {
            assertTrue(error.userMessage, error.userMessage.contains("missing or damaged"))
        }
        assertTrue(server.calls.isEmpty())
        assertEquals(LibraryMutation.State.CONFLICT, store.operations().single().state)
    }

    private inner class FakeServer : LibraryMutationServer {
        val calls = mutableListOf<String>()
        val objects = mutableSetOf<String>()
        var importStatus = "imported"
        var failImport: Exception? = null
        val importConflicts = mutableSetOf<String>()
        val metadataStatus = mutableMapOf<String, Int>()
        var renameEcho: String? = null

        override fun hasObject(sha256: String) = sha256 in objects

        override fun uploadPdf(file: File, sha256: String): String {
            calls += "upload:$sha256"
            objects += sha(file)
            return sha(file)
        }

        override fun importPdf(filename: String, sha256: String, documentId: String): LibraryMutationAck {
            failImport?.let { throw it }
            if (filename in importConflicts) throw ServerException(409, "A different Library PDF already uses this filename")
            check(sha256 in objects) { "Uploaded PDF object not found" }
            calls += "import:$filename"
            return LibraryMutationAck(filename, sha256, importStatus, documentId = documentId)
        }

        override fun setDisplayName(filename: String, expectedSha256: String, displayName: String): LibraryMutationAck {
            metadataStatus[filename]?.let { throw ServerException(it, "refused") }
            calls += "rename:$filename:$displayName"
            return LibraryMutationAck(filename, expectedSha256, "updated", displayName = renameEcho ?: displayName)
        }

        override fun setArchived(filename: String, expectedSha256: String, archived: Boolean): LibraryMutationAck {
            metadataStatus[filename]?.let { throw ServerException(it, "refused") }
            calls += "archive:$filename:$archived"
            return LibraryMutationAck(filename, expectedSha256, "updated", archived = archived)
        }
    }
}
