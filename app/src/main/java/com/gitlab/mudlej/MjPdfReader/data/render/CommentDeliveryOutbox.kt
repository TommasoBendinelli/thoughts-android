// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

data class PendingCommentDelivery(
    val batchId: String,
    val target: String,
    val records: List<CommentDeliveryRecord>,
    val createdAtEpochMillis: Long,
)

class CommentDeliveryOutbox(
    private val directory: File,
    private val onChanged: () -> Unit = {},
    private val directorySync: (File) -> Unit = ::syncDirectoryDurably,
) {
    fun enqueue(
        target: String,
        records: Collection<CommentDeliveryRecord>,
    ): PendingCommentDelivery {
        require(target.isSafePdfBasename()) { "Comment delivery target must be a PDF basename" }
        require(records.isNotEmpty()) { "Comment delivery must contain at least one record" }
        val normalized = records
            .groupBy(CommentDeliveryRecord::id)
            .map { (_, candidates) -> candidates.maxBy(CommentDeliveryRecord::revision) }
            .sortedBy(CommentDeliveryRecord::id)
        normalized.forEach { record ->
            require(record.id.isSafeCommentId()) { "Comment delivery id is invalid" }
            require(record.revision > 0) { "Comment delivery revision must be positive" }
            val payload = JsonParser.parseString(record.json)
            require(payload.isJsonObject) { "Comment delivery record must be an object" }
            require(payload.asJsonObject.string("id") == record.id) {
                "Comment delivery record id does not match"
            }
            require(payload.asJsonObject.int("revision") == record.revision) {
                "Comment delivery record revision does not match"
            }
        }
        val batchId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("batch_id", batchId)
            addProperty("target", target)
            addProperty("created_at", Instant.now().toString())
            add("comment_records", JsonArray().apply {
                normalized.forEach { add(JsonParser.parseString(it.json)) }
            })
        }
        atomicWrite(File(directory, "$batchId.json"), payload.toString())
        onChanged()
        return PendingCommentDelivery(
            batchId,
            target,
            normalized,
            System.currentTimeMillis(),
        )
    }

    fun load(): List<PendingCommentDelivery> {
        return directory.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty().mapNotNull(::parse).sortedBy(PendingCommentDelivery::createdAtEpochMillis)
    }

    fun hasPendingTarget(target: String): Boolean = load().any { it.target == target }

    fun remove(batchIds: Collection<String>) {
        var removed = false
        batchIds.forEach { batchId ->
            if (batchId.matches(UUID_REGEX)) {
                val file = File(directory, "$batchId.json")
                val deleted = file.delete()
                removed = deleted || removed
                check(deleted || !file.exists()) {
                    "Could not remove delivered comment batch"
                }
            }
        }
        if (removed) {
            directorySync(directory)
            onChanged()
        }
    }

    private fun parse(file: File): PendingCommentDelivery? = runCatching {
        val payload = JsonParser.parseString(file.readText()).asJsonObject
        if (payload.int("schema_version") != SCHEMA_VERSION) return@runCatching null
        val batchId = payload.string("batch_id")
            ?.takeIf { it == file.nameWithoutExtension && it.matches(UUID_REGEX) }
            ?: return@runCatching null
        val target = payload.string("target")
            ?.takeIf(String::isSafePdfBasename)
            ?: return@runCatching null
        val createdAt = payload.string("created_at")
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: file.lastModified()
        val records = payload.get("comment_records")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { value ->
                val record = value.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@mapNotNull null
                val id = record.string("id")
                    ?.takeIf(String::isSafeCommentId)
                    ?: return@mapNotNull null
                val revision = record.int("revision")
                    ?.takeIf { it > 0 }
                    ?: return@mapNotNull null
                CommentDeliveryRecord(id, revision, record.toString())
            }
            .orEmpty()
        if (records.isEmpty()) return@runCatching null
        PendingCommentDelivery(batchId, target, records, createdAt)
    }.getOrNull()

    private fun atomicWrite(destination: File, contents: String) {
        directory.mkdirs()
        val temporary = File(directory, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write((contents + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            check(temporary.renameTo(destination)) { "Could not publish comment delivery batch" }
            directorySync(directory)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private val UUID_REGEX =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun forContext(context: Context): CommentDeliveryOutbox {
            return CommentDeliveryOutbox(
                File(context.filesDir, "comment-delivery-outbox"),
                onChanged = PendingChangesSignal::notifyChanged,
            )
        }
    }
}

private fun syncDirectoryDurably(directory: File) {
    val descriptor = try {
        Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    } catch (error: ErrnoException) {
        throw IllegalStateException("Could not open comment delivery outbox", error)
    }
    try {
        Os.fsync(descriptor)
    } catch (error: ErrnoException) {
        throw IllegalStateException("Could not durability-sync comment delivery outbox", error)
    } finally {
        Os.close(descriptor)
    }
}

private fun String.isSafePdfBasename(): Boolean {
    return isNotBlank() &&
        endsWith(".pdf", ignoreCase = true) &&
        !contains('/') &&
        !contains('\\')
}

private fun String.isSafeCommentId(): Boolean {
    return isNotBlank() && length <= 256 && !contains('/') && !contains('\\')
}

private fun JsonObject.string(name: String): String? {
    return get(name)?.takeUnless { it.isJsonNull }?.asString
}

private fun JsonObject.int(name: String): Int? {
    return runCatching { get(name)?.asInt }.getOrNull()
}
