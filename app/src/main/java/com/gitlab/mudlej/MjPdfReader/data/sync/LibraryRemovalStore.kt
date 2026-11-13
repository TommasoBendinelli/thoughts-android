// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

data class PendingLibraryRemoval(
    val requestId: String,
    val filename: String,
    val sha256: String,
    val createdAtEpochMillis: Long,
    val failureDetail: String = "",
)

/** Durable, identity-scoped requests to remove unlinked PDFs from the shared Library. */
class LibraryRemovalStore internal constructor(
    private val stateFile: File,
    private val onChanged: () -> Unit = {},
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "thoughts-sync/library-removals.json"),
        PendingChangesSignal::notifyChanged,
    )

    @Synchronized
    fun pending(): List<PendingLibraryRemoval> = load()

    @Synchronized
    fun matchingFilenames(activePdfSha256ByFilename: Map<String, String>): Set<String> = load()
        .filter { removal ->
            activePdfSha256ByFilename[removal.filename]
                ?.equals(removal.sha256, ignoreCase = true) == true
        }
        .mapTo(linkedSetOf(), PendingLibraryRemoval::filename)

    @Synchronized
    fun enqueue(
        filename: String,
        sha256: String,
        createdAtEpochMillis: Long = System.currentTimeMillis(),
        requestId: String = UUID.randomUUID().toString(),
    ): PendingLibraryRemoval {
        require(isSafePdfFilename(filename)) { "Invalid Library PDF filename" }
        val normalizedSha256 = sha256.lowercase()
        require(normalizedSha256.matches(SHA256)) { "Invalid Library PDF identity" }
        require(runCatching { UUID.fromString(requestId) }.isSuccess) {
            "Invalid Library removal request identity"
        }
        val current = load().toMutableList()
        current.firstOrNull {
            it.filename == filename && it.sha256 == normalizedSha256
        }?.let { existing ->
            if (existing.failureDetail.isEmpty()) return existing
            val cleared = existing.copy(failureDetail = "")
            current[current.indexOf(existing)] = cleared
            write(current)
            return cleared
        }
        current.removeAll { it.filename == filename }
        val removal = PendingLibraryRemoval(
            requestId = requestId,
            filename = filename,
            sha256 = normalizedSha256,
            createdAtEpochMillis = createdAtEpochMillis.coerceAtLeast(0),
        )
        current += removal
        write(current)
        return removal
    }

    @Synchronized
    fun recordFailure(requestId: String, detail: String) {
        update(requestId) { it.copy(failureDetail = detail.trim().take(MAX_FAILURE_CHARS)) }
    }

    @Synchronized
    fun clearFailure(requestId: String) {
        update(requestId) { it.copy(failureDetail = "") }
    }

    /**
     * Retires a tombstone only after the activated generation no longer contains that exact
     * filename and SHA-256 identity.
     */
    @Synchronized
    fun clearActivated(activePdfSha256ByFilename: Map<String, String>): Int {
        val current = load()
        val retained = current.filter { removal ->
            activePdfSha256ByFilename[removal.filename]
                ?.equals(removal.sha256, ignoreCase = true) == true
        }
        if (retained.size == current.size) return 0
        write(retained)
        return current.size - retained.size
    }

    private fun update(
        requestId: String,
        transform: (PendingLibraryRemoval) -> PendingLibraryRemoval,
    ) {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.requestId == requestId }
        if (index < 0) return
        val updated = transform(current[index])
        if (updated == current[index]) return
        current[index] = updated
        write(current)
    }

    private fun load(): List<PendingLibraryRemoval> {
        if (!stateFile.isFile) return emptyList()
        require(stateFile.length() in 1..MAX_STATE_BYTES) {
            "The pending Library removal state is invalid"
        }
        val root = JsonParser.parseString(stateFile.readText(StandardCharsets.UTF_8)).asJsonObject
        require(root.int("schema_version") == SCHEMA_VERSION) {
            "The pending Library removal state has an unsupported version"
        }
        return (root.getAsJsonArray("removals") ?: JsonArray()).map { element ->
            val value = element.asJsonObject
            PendingLibraryRemoval(
                requestId = value.string("request_id").also { requestId ->
                    require(runCatching { UUID.fromString(requestId) }.isSuccess)
                },
                filename = value.string("filename").also { filename ->
                    require(isSafePdfFilename(filename))
                },
                sha256 = value.string("sha256").lowercase().also { digest ->
                    require(digest.matches(SHA256))
                },
                createdAtEpochMillis = value.long("created_at_epoch_millis").also { createdAt ->
                    require(createdAt >= 0)
                },
                failureDetail = value.string("failure_detail").take(MAX_FAILURE_CHARS),
            )
        }.also { removals ->
            require(removals.distinctBy(PendingLibraryRemoval::requestId).size == removals.size)
            require(removals.distinctBy(PendingLibraryRemoval::filename).size == removals.size)
        }.sortedBy(PendingLibraryRemoval::createdAtEpochMillis)
    }

    private fun write(removals: List<PendingLibraryRemoval>) {
        if (removals.isEmpty()) {
            if (!stateFile.exists() || stateFile.delete()) onChanged()
            return
        }
        val root = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            add("removals", JsonArray().apply {
                removals.sortedBy(PendingLibraryRemoval::createdAtEpochMillis).forEach { removal ->
                    add(JsonObject().apply {
                        addProperty("request_id", removal.requestId)
                        addProperty("filename", removal.filename)
                        addProperty("sha256", removal.sha256)
                        addProperty("created_at_epoch_millis", removal.createdAtEpochMillis)
                        removal.failureDetail.takeIf(String::isNotEmpty)?.let { detail ->
                            addProperty("failure_detail", detail)
                        }
                    })
                }
            })
        }
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, ".${stateFile.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write((root.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(stateFile)) { "Could not save the pending Library removal" }
        onChanged()
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_STATE_BYTES = 100_000L
        const val MAX_FAILURE_CHARS = 600
        val SHA256 = Regex("^[0-9a-f]{64}$")

        fun JsonObject.string(name: String): String = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            .orEmpty()

        fun JsonObject.int(name: String): Int =
            get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

        fun JsonObject.long(name: String): Long =
            get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: -1

        fun isSafePdfFilename(filename: String): Boolean =
            filename.endsWith(".pdf", ignoreCase = true) &&
                filename.length > 4 &&
                filename == File(filename).name &&
                !filename.contains('/') &&
                !filename.contains('\\')
    }
}
