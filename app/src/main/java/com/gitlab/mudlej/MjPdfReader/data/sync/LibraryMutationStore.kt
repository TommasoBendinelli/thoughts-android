// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** One local Library change, published to Fedora only by the next Sync the user starts. */
data class LibraryMutation(
    val requestId: String,
    val type: Type,
    val filename: String,
    /** The added PDF's SHA-256, or the PDF a rename or archive change was made on. */
    val sha256: String,
    val createdAtEpochMillis: Long,
    val documentId: String = "",
    val sizeBytes: Long = 0,
    val displayName: String = "",
    val archived: Boolean = false,
    val state: State = State.PENDING,
    val failureDetail: String = "",
) {
    enum class Type(val wire: String) { ADD("add"), RENAME("rename"), ARCHIVE("archive") }

    enum class State(val wire: String) {
        /** Saved on this phone; the next Sync sends it. */
        PENDING("pending"),

        /** Fedora accepted it; kept on screen until a Library pulled after the delivery replaces it. */
        DELIVERED("delivered"),

        /** Fedora refused an added PDF (its filename is taken); kept, never sent again. */
        CONFLICT("conflict"),
    }

    /** Waiting for Sync, as the phone's pending-changes surfaces count it. */
    val awaitsSync: Boolean get() = state != State.DELIVERED
}

class LibraryFilenameTakenException(message: String) : IllegalStateException(message)

/**
 * The phone's durable, ordered queue of Library imports, display-name changes and archive changes.
 *
 * The action changes the local Library at once ([libraryMutationOverlay] is applied whenever the
 * Library index is read, so a refreshed Library generation never hides a pending change), and
 * explicit Sync publishes the queue in order before it pulls the Library
 * ([LibraryMutationPublisher]). Added PDFs are kept in this store, named by their Library
 * filename, until a Library that contains them has been activated.
 */
class LibraryMutationStore internal constructor(
    val root: File,
    private val onChanged: () -> Unit = {},
    private val registerImport: (filename: String, sha256: String, file: File) -> String =
        { filename, sha256, file -> DocumentIdentityStore().registerImport(filename, sha256, file) },
    private val forgetImport: (documentId: String) -> Unit = { DocumentIdentityStore().forgetImport(it) },
) {
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this(
        defaultRoot(PhoneRenderQueue.defaultCanonicalPdfDirectory()),
        PendingChangesSignal::notifyChanged,
    )

    private val stateFile = stateFile(root)

    fun operations(): List<LibraryMutation> = synchronized(lock) { load() }

    /** Operations Sync still has to send, in the order they were made. */
    fun pending(): List<LibraryMutation> = operations().filter { it.state == LibraryMutation.State.PENDING }

    /** Changes the phone still shows as pending, including refused imports kept on this phone. */
    fun awaitingSync(): List<LibraryMutation> = operations().filter(LibraryMutation::awaitsSync)

    fun pdfFile(mutation: LibraryMutation): File = pdfFile(root, mutation)

    /**
     * Moves an already verified [staged] PDF into this store and queues it. The staged file is
     * consumed only once the PDF is safely stored here.
     */
    fun importPdf(
        staged: File,
        filename: String,
        sha256: String,
        isFilenameTaken: (String) -> Boolean = { false },
        createdAtEpochMillis: Long = System.currentTimeMillis(),
        requestId: String = UUID.randomUUID().toString(),
    ): LibraryMutation = synchronized(lock) {
        require(isRegistrableFilename(filename)) { "Invalid Library PDF filename" }
        require(sha256.matches(SHA256)) { "Invalid Library PDF identity" }
        require(runCatching { UUID.fromString(requestId) }.isSuccess) { "Invalid Library request identity" }
        require(staged.isFile) { "The PDF to add is missing" }
        val current = load()
        if (current.any { it.type == LibraryMutation.Type.ADD && it.filename == filename } || isFilenameTaken(filename)) {
            throw LibraryFilenameTakenException("A different Library PDF already uses this filename")
        }
        val directory = File(File(root, PDF_DIRECTORY), requestId)
        check(!directory.exists() || directory.deleteRecursively()) { "Could not prepare the Library PDF store" }
        check(directory.mkdirs()) { "Could not create the Library PDF store" }
        val stored = File(directory, filename)
        var documentId = ""
        try {
            if (!staged.renameTo(stored)) {
                FileOutputStream(stored).use { output ->
                    staged.inputStream().use { input -> input.copyTo(output) }
                    output.fd.sync()
                }
            }
            check(fileSha256(stored) == sha256) { "The PDF changed while it was added to the Library" }
            stored.setReadOnly()
            documentId = try {
                registerImport(filename, sha256, stored)
            } catch (error: IllegalArgumentException) {
                throw LibraryFilenameTakenException(error.message ?: "This filename belongs to another PDF version")
            }
            val mutation = LibraryMutation(
                requestId = requestId,
                type = LibraryMutation.Type.ADD,
                filename = filename,
                sha256 = sha256,
                createdAtEpochMillis = createdAtEpochMillis.coerceAtLeast(0),
                documentId = documentId,
                sizeBytes = stored.length(),
            )
            write(current + mutation)
            staged.delete()
            mutation
        } catch (error: Throwable) {
            // Nothing was queued: give the staged copy back to the caller and forget the identity.
            if (documentId.isNotEmpty()) runCatching { forgetImport(documentId) }
            if (!staged.exists() && stored.isFile) stored.renameTo(staged)
            directory.deleteRecursively()
            throw error
        }
    }

    fun enqueueRename(
        filename: String,
        expectedSha256: String,
        displayName: String,
        createdAtEpochMillis: Long = System.currentTimeMillis(),
        requestId: String = UUID.randomUUID().toString(),
    ): LibraryMutation {
        val normalized = displayName.trim()
        require(isValidDisplayName(normalized)) { "Invalid Library display name" }
        return enqueueMetadata(
            LibraryMutation(
                requestId = requestId,
                type = LibraryMutation.Type.RENAME,
                filename = filename,
                sha256 = expectedSha256.lowercase(),
                createdAtEpochMillis = createdAtEpochMillis.coerceAtLeast(0),
                displayName = normalized,
            ),
        )
    }

    fun enqueueArchive(
        filename: String,
        expectedSha256: String,
        archived: Boolean,
        createdAtEpochMillis: Long = System.currentTimeMillis(),
        requestId: String = UUID.randomUUID().toString(),
    ): LibraryMutation = enqueueMetadata(
        LibraryMutation(
            requestId = requestId,
            type = LibraryMutation.Type.ARCHIVE,
            filename = filename,
            sha256 = expectedSha256.lowercase(),
            createdAtEpochMillis = createdAtEpochMillis.coerceAtLeast(0),
            archived = archived,
        ),
    )

    /**
     * A later change of the same field on the same PDF supersedes an unsent one; the new change
     * goes to the end of the queue, after the import it may depend on.
     */
    private fun enqueueMetadata(mutation: LibraryMutation): LibraryMutation = synchronized(lock) {
        require(isSafePdfFilename(mutation.filename)) { "Invalid Library PDF filename" }
        require(mutation.sha256.matches(SHA256)) { "Invalid Library PDF identity" }
        require(runCatching { UUID.fromString(mutation.requestId) }.isSuccess) { "Invalid Library request identity" }
        val retained = load().filterNot {
            it.state == LibraryMutation.State.PENDING && it.type == mutation.type &&
                it.filename == mutation.filename && it.sha256 == mutation.sha256
        }
        write(retained + mutation)
        mutation
    }

    /**
     * Withdraws an import Fedora has not received (or refused) together with every change made on
     * it, and deletes its stored copy. False when Fedora already has the PDF.
     */
    fun cancelAdd(filename: String, sha256: String): Boolean = synchronized(lock) {
        val current = load()
        val add = current.firstOrNull {
            it.type == LibraryMutation.Type.ADD && it.filename == filename && it.sha256 == sha256
        } ?: return false
        if (add.state == LibraryMutation.State.DELIVERED) return false
        write(current.filterNot { it.filename == filename && it.sha256 == sha256 })
        File(File(root, PDF_DIRECTORY), add.requestId).deleteRecursively()
        runCatching { forgetImport(add.documentId) }
        true
    }

    fun markDelivered(requestId: String) = update(requestId) {
        it.copy(state = LibraryMutation.State.DELIVERED, failureDetail = "")
    }

    fun markConflict(requestId: String, detail: String) = update(requestId) {
        it.copy(state = LibraryMutation.State.CONFLICT, failureDetail = detail.trim().take(MAX_FAILURE_CHARS))
    }

    /** A retryable failure: the change stays queued for the next Sync. */
    fun recordFailure(requestId: String, detail: String) = update(requestId) {
        it.copy(failureDetail = detail.trim().take(MAX_FAILURE_CHARS))
    }

    /** Drops a change Fedora refused because its PDF changed or disappeared there. */
    fun discard(requestId: String) = synchronized(lock) {
        val current = load()
        val retained = current.filterNot { it.requestId == requestId }
        if (retained.size != current.size) write(retained)
    }

    fun deliveredRequestIds(): Set<String> = operations()
        .filter { it.state == LibraryMutation.State.DELIVERED }
        .mapTo(linkedSetOf(), LibraryMutation::requestId)

    /**
     * Retires delivered changes once a Library fetched after their delivery is active: from then
     * on the Library index itself shows them (or a later change made elsewhere).
     */
    fun retire(deliveredBeforeFetch: Set<String>): Int = synchronized(lock) {
        if (deliveredBeforeFetch.isEmpty()) return 0
        val current = load()
        val retired = current.filter {
            it.state == LibraryMutation.State.DELIVERED && it.requestId in deliveredBeforeFetch
        }
        if (retired.isEmpty()) return 0
        write(current - retired.toSet())
        retired.filter { it.type == LibraryMutation.Type.ADD }.forEach { add ->
            File(File(root, PDF_DIRECTORY), add.requestId).deleteRecursively()
        }
        retired.size
    }

    /** Stored copies of added PDFs by SHA-256; the caller verifies them before reuse. */
    fun storedPdfs(): Map<String, File> = operations()
        .filter { it.type == LibraryMutation.Type.ADD }
        .mapNotNull { add -> pdfFile(add).takeIf(File::isFile)?.let { add.sha256 to it } }
        .toMap()

    private fun update(requestId: String, transform: (LibraryMutation) -> LibraryMutation) = synchronized(lock) {
        val current = load()
        val index = current.indexOfFirst { it.requestId == requestId }
        if (index < 0) return@synchronized
        val updated = transform(current[index])
        if (updated == current[index]) return@synchronized
        write(current.toMutableList().also { it[index] = updated })
    }

    private fun load(): List<LibraryMutation> = readOperations(stateFile)

    private fun write(operations: List<LibraryMutation>) {
        if (operations.isEmpty()) {
            if (!stateFile.exists() || stateFile.delete()) onChanged()
            return
        }
        val value = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            add("operations", JsonArray().apply { operations.forEach { add(it.toJson()) } })
        }
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, ".${stateFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write((value.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            check(temporary.renameTo(stateFile)) { "Could not save the pending Library change" }
        } finally {
            temporary.delete()
        }
        onChanged()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val STATE_FILENAME = "state.json"
        private const val PDF_DIRECTORY = "pdfs"
        private const val MAX_STATE_BYTES = 2_000_000L
        private const val MAX_FAILURE_CHARS = 600
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val lock = Any()

        /** Beside the canonical Library directory, which each Library activation replaces whole. */
        fun defaultRoot(canonicalPdfDirectory: File): File =
            File(canonicalPdfDirectory.absoluteFile.parentFile, "library-mutations")

        fun stateFile(root: File): File = File(root, STATE_FILENAME)

        fun pdfFile(root: File, mutation: LibraryMutation): File =
            File(File(File(root, PDF_DIRECTORY), mutation.requestId), mutation.filename)

        /** Strict: an unreadable queue must never be mistaken for an empty one. */
        fun readOperations(stateFile: File): List<LibraryMutation> {
            if (!stateFile.isFile) return emptyList()
            require(stateFile.length() in 1..MAX_STATE_BYTES) { "The pending Library changes are invalid" }
            val root = JsonParser.parseString(stateFile.readText(StandardCharsets.UTF_8)).asJsonObject
            require(root.get("schema_version")?.asInt == SCHEMA_VERSION) {
                "The pending Library changes have an unsupported version"
            }
            val operations = (root.getAsJsonArray("operations") ?: JsonArray()).map { fromJson(it.asJsonObject) }
            require(operations.distinctBy(LibraryMutation::requestId).size == operations.size) {
                "The pending Library changes are duplicated"
            }
            return operations
        }

        private fun fromJson(value: JsonObject): LibraryMutation {
            fun string(name: String) = value.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val type = LibraryMutation.Type.entries.firstOrNull { it.wire == string("type") }
                ?: throw IllegalArgumentException("Unknown Library change")
            val state = LibraryMutation.State.entries.firstOrNull { it.wire == string("state") }
                ?: throw IllegalArgumentException("Unknown Library change state")
            return LibraryMutation(
                requestId = string("request_id").also { require(runCatching { UUID.fromString(it) }.isSuccess) },
                type = type,
                filename = string("filename").also { require(isSafePdfFilename(it)) },
                sha256 = string("sha256").also { require(it.matches(SHA256)) },
                createdAtEpochMillis = value.get("created_at_epoch_millis")?.asLong ?: 0,
                documentId = string("document_id").also {
                    require(type != LibraryMutation.Type.ADD || DocumentIdentityStore.validId(it))
                },
                sizeBytes = value.get("size_bytes")?.asLong ?: 0,
                displayName = string("display_name").also {
                    require(type != LibraryMutation.Type.RENAME || isValidDisplayName(it))
                },
                archived = value.get("archived")?.asBoolean == true,
                state = state,
                failureDetail = string("failure_detail").take(MAX_FAILURE_CHARS),
            )
        }

        private fun LibraryMutation.toJson() = JsonObject().apply {
            addProperty("request_id", requestId)
            addProperty("type", type.wire)
            addProperty("filename", filename)
            addProperty("sha256", sha256)
            addProperty("created_at_epoch_millis", createdAtEpochMillis)
            when (type) {
                LibraryMutation.Type.ADD -> {
                    addProperty("document_id", documentId)
                    addProperty("size_bytes", sizeBytes)
                }
                LibraryMutation.Type.RENAME -> addProperty("display_name", displayName)
                LibraryMutation.Type.ARCHIVE -> addProperty("archived", archived)
            }
            addProperty("state", state.wire)
            failureDetail.takeIf(String::isNotEmpty)?.let { addProperty("failure_detail", it) }
        }

        fun isValidDisplayName(value: String): Boolean =
            value.isNotEmpty() && value.length <= 200 && value.none { it.code < 32 || it.code == 127 }

        private fun isSafePdfFilename(filename: String): Boolean =
            filename.endsWith(".pdf", ignoreCase = true) && filename.length > 4 &&
                filename == File(filename).name && !filename.contains('/') && !filename.contains('\\')

        /** The rule Library imports have always used (pdfLinkSafeFilename, document identities). */
        private fun isRegistrableFilename(filename: String): Boolean =
            filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf"))

        private fun fileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
