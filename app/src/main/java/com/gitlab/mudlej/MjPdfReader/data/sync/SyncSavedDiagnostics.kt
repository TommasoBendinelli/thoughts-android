// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Raw diagnostics are private, append-only, and independent of bounded UI history. */
internal class SyncDiagnosticArchive(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "sync-diagnostics"))
    private val gson = Gson()

    fun append(id: String, kind: String, value: Any) {
        runCatching {
            synchronized(lock) {
                val file = file(id)
                root.mkdirs()
                val entry = JsonObject().apply {
                    addProperty("kind", kind)
                    addProperty("saved_at", System.currentTimeMillis())
                    add("value", gson.toJsonTree(value))
                }
                FileOutputStream(file, true).use {
                    it.write((entry.toString() + "\n").toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
            }
        }.onFailure { Log.w("SyncDiagnostics", "Could not save raw diagnostics", it) }
    }

    fun read(id: String): String = synchronized(lock) {
        file(id).takeIf(File::isFile)?.readText().orEmpty()
    }

    fun saveBundle(id: String, text: String) = synchronized(lock) {
        file(id) // Validate the operation identity.
        root.mkdirs()
        val destination = File(root, "$id.txt")
        val temporary = File(root, "$id.txt.tmp")
        FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temporary.renameTo(destination)) { "Could not save diagnostic bundle" }
    }

    private fun file(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, "$id.jsonl")
    }
    companion object { private val lock = Any() }
}

internal data class SavedSyncDetails(
    val builds: List<SyncBuildProgress> = emptyList(),
    val receipts: List<JsonObject> = emptyList(),
    val rawArchive: String = "",
)

/** Match historical receipts by immutable build ID, even when retries update their run association. */
internal fun relevantSyncReceipts(record: SyncOperationRecord, receipts: List<JsonObject>): List<JsonObject> {
    val ids = record.buildProgress.map { it.buildId }.toSet() + record.journalEvents.map { it.buildId }
    val legacyEvents = record.events.joinToString("\n") + record.journalEvents.joinToString("\n") { it.message }
    return receipts.filter {
        val id = it.get("build_id")?.takeIf { value -> value.isJsonPrimitive }?.asString.orEmpty()
        it.get("terminal_operation_id")?.takeIf { value -> value.isJsonPrimitive }?.asString == record.id ||
            (id.isNotBlank() && (id in ids || legacyEvents.contains(id)))
    }
}

internal fun loadSavedSyncDetails(
    context: Context,
    record: SyncOperationRecord,
    includeRawArchive: Boolean = false,
): SavedSyncDetails {
    val gson = Gson()
    // The status dialog must never parse the unbounded diagnostic archive.
    val raw = if (includeRawArchive) SyncDiagnosticArchive(context).read(record.id) else ""
    val archivedBuilds = raw.lineSequence().mapNotNull { line -> runCatching {
        val entry = JsonParser.parseString(line).asJsonObject
        if (entry.get("kind")?.asString == "builds")
            gson.fromJson(entry.get("value"), Array<SyncBuildProgress>::class.java).toList() else null
    }.getOrNull() }.flatten().toList()
    val receipts = relevantSyncReceipts(record, PhoneRenderQueue.defaultReceiptDirectory().listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { runCatching {
            JsonParser.parseString(it.readText()).asJsonObject
        }.getOrNull() })
    val receiptBuilds = receipts.map { receipt ->
        fun text(key: String) = receipt.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        SyncBuildProgress(text("build_id"), text("target"), text("server_status"), "", 0, 0, "", text("message"),
            failedDocuments = receipt.getAsJsonArray("failed_documents")?.mapNotNull { value -> runCatching {
                val item = value.asJsonObject
                SyncProjectFailure(item.get("pdf_filename").asString, item.get("message")?.asString.orEmpty())
            }.getOrNull() }, logReference = text("log_reference"), workKind = text("repair_mode"))
    }
    val builds = (record.buildProgress + archivedBuilds + receiptBuilds).groupBy { it.buildId }.values.map { versions ->
        versions.lastOrNull { !it.failedDocuments.isNullOrEmpty() } ?: versions.last()
    }
    return SavedSyncDetails(builds, receipts, raw)
}

internal fun savedSyncDiagnosticBundle(
    record: SyncOperationRecord,
    saved: SavedSyncDetails,
    readLog: (String) -> String?,
): String {
    val gson = Gson()
    val references = (saved.builds.mapNotNull { it.logReference?.takeIf(String::isNotBlank) } +
        Regex("server-object:[a-f0-9]{64}").findAll(gson.toJson(record)).map { it.value }).distinct()
    return buildString {
        appendLine("Thoughts diagnostics v2 — saved locally")
        appendLine("Operation: ${record.id}; retry of: ${record.retryOf ?: "none"}")
        appendLine("Server journal: ${if (record.serverTerminal) "terminal event saved" else "incomplete or unavailable"}")
        appendLine("Raw event archive: ${if (saved.rawArchive.isBlank()) "unavailable for this older run; stored events follow" else "available"}")
        appendLine("\nStored operation and events\n${gson.toJson(record)}")
        appendLine("\nSaved builds\n${gson.toJson(saved.builds)}")
        appendLine("\nSaved build receipts\n${gson.toJson(saved.receipts)}")
        appendLine("\nRaw diagnostic events\n${saved.rawArchive}")
        if (references.isEmpty()) appendLine("Build logs: no saved reference is available for this run.")
        references.forEach { reference ->
            appendLine("\nBuild log: $reference")
            appendLine(readLog(reference) ?: "INCOMPLETE: this build log is not available locally.")
        }
    }
}
