// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest

internal fun sourceManifestInputs(artifacts: List<Pair<String, String>>, stale: Map<String, Boolean>) =
    JsonArray().apply {
        artifacts.sortedBy { it.first }.forEach { (filename, digest) ->
            add(JsonArray().apply { add(filename); add(digest); add(stale[filename] == true) })
        }
    }

internal fun sourceManifestReceipt(inputs: JsonArray, bundleId: String, objects: File) = JsonObject().apply {
    addProperty("version", 2)
    add("inputs", inputs.deepCopy())
    addProperty("bundle_id", bundleId)
    add("objects", JsonArray().apply {
        objects.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}")) }
            .sortedBy(File::getName).forEach { file ->
                add(JsonArray().apply { add(file.name); add(file.length()); add(file.lastModified()) })
            }
    })
}

internal fun canReuseSourceManifest(receipt: JsonObject?, inputs: JsonArray, activeBundleId: String, objects: File, diagnostics: SyncDiagnostics? = null): Boolean =
    runCatching {
        fun miss(reason: String): Boolean { diagnostics?.decision("source_manifest_reuse", reason); return false }
        if (receipt == null) return@runCatching miss("receipt_missing")
        if (receipt.get("version").asInt != 2) return@runCatching miss("receipt_version_changed")
        if (receipt.get("inputs") != inputs) return@runCatching miss("document_inputs_changed")
        if (receipt.get("bundle_id").asString != activeBundleId || !activeBundleId.matches(Regex("[0-9a-f]{64}")))
            return@runCatching miss("active_bundle_changed")
        val entries = receipt.getAsJsonArray("objects")
        if (entries.none { it.asJsonArray[0].asString == activeBundleId }) return@runCatching miss("manifest_object_missing_from_receipt")
        if (!entries.all { entry ->
            val row = entry.asJsonArray
            val name = row[0].asString
            val file = File(objects, name)
            name.matches(Regex("[0-9a-f]{64}")) && file.isFile &&
                file.length() == row[1].asLong && file.lastModified() == row[2].asLong
        }) return@runCatching miss("cached_object_missing_or_stat_changed")
        val hash = MessageDigest.getInstance("SHA-256")
        File(objects, activeBundleId).inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        (hash.digest().joinToString("") { "%02x".format(it) } == activeBundleId).also {
            diagnostics?.decision("source_manifest_reuse", if (it) "hit" else "manifest_digest_mismatch")
        }
    }.getOrElse {
        diagnostics?.decision("source_manifest_reuse", "receipt_unreadable_or_invalid")
        false
    }
