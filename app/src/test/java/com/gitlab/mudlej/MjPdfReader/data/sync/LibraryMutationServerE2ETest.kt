// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.tbend.thoughts.sdk.LibrarySnapshot
import org.tbend.thoughts.sdk.SyncClient

/**
 * End-to-end against a real thoughts-server: Library changes made offline are published by the
 * Sync Library phase, a repeated Sync sends nothing, and refusals are conflicts.
 *
 * Runs only when THOUGHTS_LIBRARY_E2E_CONFIG (default
 * /var/tmp/thoughts-builder/tmp/llib-e2e/server.json) names a disposable server:
 * {"base_url": "http://127.0.0.1:18431", "token": "..."}. Never point it at a real Library.
 */
class LibraryMutationServerE2ETest {
    private lateinit var temporary: File
    private lateinit var client: SyncClient
    private lateinit var canonical: File
    private val run = UUID.randomUUID().toString().take(8)

    @Before
    fun setUp() {
        val config = File(System.getenv("THOUGHTS_LIBRARY_E2E_CONFIG") ?: "/var/tmp/thoughts-builder/tmp/llib-e2e/server.json")
        assumeTrue("No disposable thoughts-server configured", config.isFile)
        val value = JsonParser.parseString(config.readText()).asJsonObject
        val baseUrl = value.get("base_url").asString
        assumeTrue("Only a local disposable server", baseUrl.startsWith("http://127.0.0.1:"))
        client = SyncClient(baseUrl, value.get("token").asString, Gson())
        temporary = Files.createTempDirectory("library-e2e").toFile()
        canonical = File(temporary, "current-pdfs").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::temporary.isInitialized) {
            temporary.walkTopDown().forEach { it.setWritable(true) }
            temporary.deleteRecursively()
        }
    }

    private fun store() = LibraryMutationStore(
        LibraryMutationStore.defaultRoot(canonical),
        registerImport = { _, _, _ -> UUID.randomUUID().toString() },
        forgetImport = {},
    )

    private fun pdfBytes(label: String) = "%PDF-1.4\n% $label $run\n%%EOF\n".toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun publish() = LibraryMutationPublisher(
        store(),
        SyncClientLibraryMutationServer(client),
        { sha(it.readBytes()) },
    ).publish(Long.MAX_VALUE)

    /** What another device (Thoughts Desktop) publishes directly. */
    private fun publishElsewhere(filename: String, bytes: ByteArray): String {
        val digest = sha(bytes)
        client.uploadObject(bytes.inputStream(), bytes.size.toLong(), digest, "application/pdf")
        client.importLibraryPdf(filename, digest, UUID.randomUUID().toString())
        return digest
    }

    private fun library(): LibrarySnapshot = requireNotNull(client.library(null))

    private fun stage(filename: String, bytes: ByteArray) = File(temporary, "staged-$filename").apply { writeBytes(bytes) }

    /** Installs [snapshot] as the phone's Library index, as a Sync activation does. */
    private fun activate(snapshot: LibrarySnapshot) {
        File(canonical, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME).writeText(
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("library_revision", snapshot.libraryRevision)
                addProperty("source_index_sha256", snapshot.sourceIndexSha256 ?: "0".repeat(64))
                add("pdfs", JsonArray().apply {
                    snapshot.publications.forEach { publication ->
                        add(JsonObject().apply {
                            addProperty("filename", publication.filename)
                            addProperty("kind", publication.metadata.get("kind")?.asString ?: "source_linked")
                            addProperty("pdf_sha256", publication.sha256)
                            publication.metadata.get("display_name")?.asString?.let { addProperty("display_name", it) }
                            addProperty("archived", publication.metadata.get("archived")?.asBoolean == true)
                        })
                    }
                })
            }.toString(),
        )
    }

    @Test
    fun offlineChangesArePublishedOnceAndARepeatedSyncSendsNothing() {
        val shared = "e2e-$run-shared.pdf"
        val sharedSha = publishElsewhere(shared, pdfBytes("shared"))
        activate(library())

        // Offline: nothing below talks to the server.
        val added = "e2e-$run-added.pdf"
        val addedBytes = pdfBytes("added")
        val add = store().importPdf(stage(added, addedBytes), added, sha(addedBytes))
        store().enqueueRename(added, add.sha256, "Added on the phone")
        store().enqueueRename(shared, sharedSha, "Renamed on the phone")
        store().enqueueArchive(shared, sharedSha, true)
        val shown = SourceLinkedPdfIndex(canonical).snapshot()
        assertEquals("Added on the phone", shown.libraryDisplayNamesByFilename[added])
        assertEquals("Renamed on the phone", shown.libraryDisplayNamesByFilename[shared])
        assertTrue(shared in shown.archivedLibraryFilenames)
        assertFalse(library().publications.any { it.filename == added })

        // Sync: the Library phase publishes them in order.
        assertEquals(4, publish().delivered)
        val published = library()
        val addedPublication = published.publications.single { it.filename == added }
        assertEquals(add.sha256, addedPublication.sha256)
        assertEquals(add.documentId, addedPublication.documentId)
        assertEquals("unlinked", addedPublication.metadata.get("kind").asString)
        assertEquals("Added on the phone", addedPublication.metadata.get("display_name").asString)
        val sharedPublication = published.publications.single { it.filename == shared }
        assertEquals("Renamed on the phone", sharedPublication.metadata.get("display_name").asString)
        assertTrue(sharedPublication.metadata.get("archived").asBoolean)

        // Until the pulled Library is active the phone keeps showing the delivered changes.
        val delivered = store().deliveredRequestIds()
        assertEquals(4, delivered.size)
        assertEquals("Renamed on the phone", SourceLinkedPdfIndex(canonical).snapshot().libraryDisplayNamesByFilename[shared])
        activate(published)
        assertEquals(4, store().retire(delivered))
        val after = SourceLinkedPdfIndex(canonical).snapshot()
        assertEquals("Added on the phone", after.libraryDisplayNamesByFilename[added])
        assertEquals("Renamed on the phone", after.libraryDisplayNamesByFilename[shared])
        assertTrue(shared in after.archivedLibraryFilenames)
        assertTrue(after.pendingLibraryPdfFiles.isEmpty())

        // A repeated Sync is a no-op: nothing is queued and Fedora's Library does not change.
        assertEquals(0, publish().delivered)
        assertEquals(published.libraryRevision, library().libraryRevision)
    }

    @Test
    fun resendingAnAcknowledgedImportIsIdempotent() {
        val filename = "e2e-$run-resent.pdf"
        val bytes = pdfBytes("resent")
        val add = store().importPdf(stage(filename, bytes), filename, sha(bytes))
        assertEquals(1, publish().delivered)
        val revision = library().libraryRevision

        // The acknowledgement was lost: the same change is sent again by the next Sync.
        val ack = SyncClientLibraryMutationServer(client).importPdf(filename, add.sha256, add.documentId)
        assertEquals("existing", ack.status)
        assertEquals(add.documentId, ack.documentId)
        assertEquals(1, library().publications.count { it.filename == filename })
        assertEquals("Re-sending changes nothing", revision, library().libraryRevision)
    }

    @Test
    fun importUnderAFilenameTakenElsewhereIsKeptAsAConflict() {
        val filename = "e2e-$run-clash.pdf"
        val theirs = publishElsewhere(filename, pdfBytes("theirs"))
        val mine = pdfBytes("mine")
        val add = store().importPdf(stage(filename, mine), filename, sha(mine))
        store().enqueueRename(filename, add.sha256, "Mine")
        val other = "e2e-$run-independent.pdf"
        val otherBytes = pdfBytes("independent")
        store().importPdf(stage(other, otherBytes), other, sha(otherBytes))

        try {
            publish()
            fail("The refused import fails the Sync with a message")
        } catch (error: LibraryMutationConflictException) {
            assertTrue(error.userMessage, error.userMessage.contains("“$filename” was not added"))
        }
        val operations = store().operations()
        assertEquals(LibraryMutation.State.CONFLICT, operations.single { it.requestId == add.requestId }.state)
        assertEquals(LibraryMutation.State.PENDING, operations.single { it.type == LibraryMutation.Type.RENAME }.state)
        assertEquals(LibraryMutation.State.DELIVERED, operations.single { it.filename == other }.state)
        assertTrue(store().pdfFile(add).isFile)
        val publications = library().publications
        assertEquals(theirs, publications.single { it.filename == filename }.sha256)
        assertTrue(publications.any { it.filename == other })

        // Later Syncs are not blocked and do not resend it.
        assertEquals(0, publish().delivered)
    }

    @Test
    fun renameOfAPdfReplacedElsewhereIsDroppedAndReported() {
        val filename = "e2e-$run-replaced.pdf"
        publishElsewhere(filename, pdfBytes("current"))
        store().enqueueRename(filename, "f".repeat(64), "Stale")
        store().enqueueArchive("e2e-$run-missing.pdf", "e".repeat(64), true)
        try {
            publish()
            fail("Refused changes are reported")
        } catch (error: LibraryMutationConflictException) {
            assertTrue(error.userMessage, error.userMessage.contains("was not applied"))
        }
        assertTrue(store().operations().isEmpty())
        assertFalse(library().publications.single { it.filename == filename }.metadata.has("display_name"))
    }
}
