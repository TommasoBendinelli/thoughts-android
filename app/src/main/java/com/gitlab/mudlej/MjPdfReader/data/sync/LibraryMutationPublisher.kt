// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.io.IOException
import org.tbend.thoughts.sdk.ServerException
import org.tbend.thoughts.sdk.SyncClient

/** The Fedora calls the Library phase of Sync makes; a fake replaces it in tests. */
internal interface LibraryMutationServer {
    fun hasObject(sha256: String): Boolean
    fun uploadPdf(file: File, sha256: String): String
    fun importPdf(filename: String, sha256: String, documentId: String): LibraryMutationAck
    fun setDisplayName(filename: String, expectedSha256: String, displayName: String): LibraryMutationAck
    fun setArchived(filename: String, expectedSha256: String, archived: Boolean): LibraryMutationAck
}

internal data class LibraryMutationAck(
    val filename: String,
    val sha256: String,
    val status: String,
    val documentId: String = "",
    val displayName: String = "",
    val archived: Boolean? = null,
)

internal class SyncClientLibraryMutationServer(private val client: SyncClient) : LibraryMutationServer {
    override fun hasObject(sha256: String): Boolean = client.hasObject(sha256)

    override fun uploadPdf(file: File, sha256: String): String =
        file.inputStream().use { input -> client.uploadObject(input, file.length(), sha256, "application/pdf").sha256 }

    override fun importPdf(filename: String, sha256: String, documentId: String): LibraryMutationAck =
        client.importLibraryPdf(filename, sha256, documentId).let {
            LibraryMutationAck(it.filename, it.sha256, it.status, documentId = it.documentId)
        }

    override fun setDisplayName(filename: String, expectedSha256: String, displayName: String): LibraryMutationAck =
        client.setLibraryDisplayName(filename, expectedSha256, displayName).let {
            LibraryMutationAck(it.filename, it.sha256, it.status, displayName = it.displayName)
        }

    override fun setArchived(filename: String, expectedSha256: String, archived: Boolean): LibraryMutationAck =
        client.setLibraryArchiveState(filename, expectedSha256, archived).let {
            LibraryMutationAck(it.filename, it.sha256, it.status, archived = it.archived)
        }
}

/** Library changes Sync could not send yet because their upload needs mobile-data approval. */
internal data class LibraryMutationPublication(
    val delivered: Int = 0,
    val deferredUploadBytes: Long = 0,
    val deferredIdentities: List<String> = emptyList(),
)

/**
 * Fedora refused one or more Library changes. The message is what the Sync status shows; the
 * changes themselves are kept (an import) or reported as not applied (a rename or archive change).
 */
class LibraryMutationConflictException(val userMessage: String) : IllegalStateException(userMessage)

/**
 * The Library phase of an explicit Sync: sends queued Library changes to Fedora in order, before
 * the Sync asks for the new Library state.
 *
 * Every request is idempotent (an import names its document identifier and SHA-256; Fedora answers
 * "existing" for one it already has; renames and archive changes carry the expected SHA-256), so
 * a change whose acknowledgement was lost is simply sent again. A transport or server failure
 * leaves the change queued and fails the Sync. A refusal (HTTP 409, or 404 for a rename or archive
 * change of a PDF that is gone) is a conflict: an import is kept on this phone and never sent
 * again, together with later changes of that PDF; a rename or archive change is dropped and named
 * in the Sync failure message.
 */
internal class LibraryMutationPublisher(
    private val store: LibraryMutationStore,
    private val server: LibraryMutationServer,
    private val sha256: (File) -> String,
    private val progress: (String) -> Unit = {},
) {
    /**
     * [uploadBudgetBytes] is how many bytes of PDFs this Sync may upload now (unlimited on Wi-Fi or
     * after mobile-data approval). The first import that would exceed it stops the phase; it and
     * every later change are reported as deferred so the transfer plan asks for approval.
     */
    fun publish(uploadBudgetBytes: Long): LibraryMutationPublication {
        val operations = store.operations()
        val conflicted = operations
            .filter { it.type == LibraryMutation.Type.ADD && it.state == LibraryMutation.State.CONFLICT }
            .mapTo(hashSetOf()) { it.filename to it.sha256 }
        val queue = operations.filter {
            it.state == LibraryMutation.State.PENDING && (it.filename to it.sha256) !in conflicted
        }
        val conflicts = mutableListOf<String>()
        var uploaded = 0L
        var delivered = 0
        queue.forEachIndexed { index, operation ->
            // Later changes of a refused import wait with it.
            if ((operation.filename to operation.sha256) in conflicted) return@forEachIndexed
            progress("Publishing Library changes (${index + 1} of ${queue.size})")
            val outcome = try {
                when (operation.type) {
                    LibraryMutation.Type.ADD -> {
                        val file = store.pdfFile(operation)
                        if (!file.isFile || sha256(file) != operation.sha256) {
                            Outcome.Conflict("its copy on this phone is missing or damaged", kept = false)
                        } else {
                            val needsUpload = !server.hasObject(operation.sha256)
                            if (needsUpload && uploaded + file.length() > uploadBudgetBytes) {
                                Outcome.Deferred
                            } else {
                                if (needsUpload) {
                                    check(server.uploadPdf(file, operation.sha256) == operation.sha256) {
                                        "The server stored a different PDF object"
                                    }
                                    uploaded += file.length()
                                }
                                send(operation) {
                                    server.importPdf(operation.filename, operation.sha256, operation.documentId)
                                }
                            }
                        }
                    }
                    LibraryMutation.Type.RENAME -> send(operation) {
                        server.setDisplayName(operation.filename, operation.sha256, operation.displayName)
                    }
                    LibraryMutation.Type.ARCHIVE -> send(operation) {
                        server.setArchived(operation.filename, operation.sha256, operation.archived)
                    }
                }
            } catch (error: Exception) {
                store.recordFailure(operation.requestId, error.message.orEmpty().ifBlank { error.javaClass.simpleName })
                throw error
            }
            when (outcome) {
                Outcome.Deferred -> {
                    // A refusal already met in this pass is reported first; the next Sync asks for
                    // the upload.
                    if (conflicts.isNotEmpty()) throw LibraryMutationConflictException(conflicts.joinToString("\n"))
                    return deferred(delivered, queue.drop(index))
                }
                Outcome.Delivered -> {
                    store.markDelivered(operation.requestId)
                    delivered++
                }
                is Outcome.Conflict -> {
                    if (operation.type == LibraryMutation.Type.ADD) {
                        store.markConflict(operation.requestId, outcome.reason)
                        conflicted += operation.filename to operation.sha256
                        conflicts += "“${operation.filename}” was not added to the shared Library: " +
                            outcome.reason + if (outcome.kept) ". It stays on this phone." else "."
                    } else {
                        store.discard(operation.requestId)
                        val change = if (operation.type == LibraryMutation.Type.RENAME) {
                            "The new name of “${operation.filename}”"
                        } else if (operation.archived) {
                            "Archiving “${operation.filename}”"
                        } else {
                            "Unarchiving “${operation.filename}”"
                        }
                        conflicts += "$change was not applied: ${outcome.reason}."
                    }
                }
            }
        }
        if (conflicts.isNotEmpty()) throw LibraryMutationConflictException(conflicts.joinToString("\n"))
        return LibraryMutationPublication(delivered = delivered)
    }

    private fun deferred(delivered: Int, remaining: List<LibraryMutation>) = LibraryMutationPublication(
        delivered = delivered,
        deferredUploadBytes = remaining.sumOf { operation ->
            if (operation.type == LibraryMutation.Type.ADD) {
                store.pdfFile(operation).takeIf(File::isFile)?.length() ?: operation.sizeBytes
            } else {
                0L
            }
        },
        deferredIdentities = remaining.map { "library-change:${it.requestId}:${it.type.wire}:${it.sha256}" },
    )

    private fun send(operation: LibraryMutation, request: () -> LibraryMutationAck): Outcome {
        val ack = try {
            request()
        } catch (error: ServerException) {
            return conflictReason(operation, error)?.let { Outcome.Conflict(it) } ?: throw error
        }
        val valid = ack.filename == operation.filename && ack.sha256 == operation.sha256 && when (operation.type) {
            LibraryMutation.Type.ADD -> ack.documentId == operation.documentId && ack.status in setOf("imported", "existing")
            LibraryMutation.Type.RENAME -> ack.displayName == operation.displayName && ack.status in setOf("updated", "unchanged")
            LibraryMutation.Type.ARCHIVE -> ack.archived == operation.archived && ack.status in setOf("updated", "unchanged")
        }
        if (!valid) throw IOException("The server returned an invalid Library ${operation.type.wire} acknowledgement")
        return Outcome.Delivered
    }

    private fun conflictReason(operation: LibraryMutation, error: ServerException): String? = when {
        error.statusCode == 409 && operation.type == LibraryMutation.Type.ADD ->
            "another PDF already uses this filename there"
        error.statusCode == 409 -> "the PDF was changed on another device"
        error.statusCode == 404 && operation.type != LibraryMutation.Type.ADD ->
            "the PDF is no longer in the shared Library"
        else -> null
    }

    private sealed interface Outcome {
        data object Delivered : Outcome
        data object Deferred : Outcome
        data class Conflict(val reason: String, val kept: Boolean = true) : Outcome
    }
}
