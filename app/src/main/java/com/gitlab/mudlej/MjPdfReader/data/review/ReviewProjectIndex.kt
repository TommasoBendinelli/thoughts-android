// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Small durable ownership index. Legacy files are classified once, never on every deck opening. */
internal class ReviewProjectIndex(private val file: File) {
    private val entries = runCatching { JsonParser.parseString(file.readText()).asJsonObject }
        .getOrDefault(JsonObject())
    private var dirty = false
    private val payloads = object : LinkedHashMap<String, Pair<String, JsonObject>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, JsonObject>>?) = size > 1024
    }
    private fun identity(record: File) = "${record.length()}:${record.lastModified()}"
    private fun string(value: JsonObject, key: String) = runCatching { value.get(key)?.asString }.getOrNull().orEmpty()

    fun payload(record: File): JsonObject? {
        val stamp = identity(record)
        synchronized(this) {
            payloads[record.path]?.takeIf { it.first == stamp }?.let { return it.second }
        }
        val parsed = runCatching { JsonParser.parseString(record.readText()).asJsonObject }.getOrNull() ?: return null
        record(record, parsed)
        return parsed
    }

    @Synchronized fun record(record: File, payload: JsonObject) {
        val primary = payload.get("primary")?.takeIf { it.isJsonObject }?.asJsonObject
        val stem = primary?.let { string(it, "pdf_stem") }.orEmpty().ifBlank { string(payload, "pdf_stem") }
        val entry = JsonObject().apply {
            addProperty("card_id", string(payload, "card_id"))
            addProperty("pdf_stem", stem)
            addProperty("identity", identity(record))
        }
        if (entries.get(record.path) != entry) {
            entries.add(record.path, entry)
            dirty = true
        }
        payloads[record.path] = entry.get("identity").asString to payload
    }

    @Synchronized fun filesForProject(files: List<File>, pdfStem: String): Set<String> {
        var changed = false
        // New legacy files need classification. Existing other-project records retain
        // their indexed ownership; the sync writer updates ownership on replacement.
        for (record in files) if (!entries.has(record.path)) {
            record(record, payload(record) ?: JsonObject())
            changed = true
        }
        if (changed) persist()
        val ids = entries.entrySet().mapNotNull { (_, value) ->
            val entry = value.asJsonObject
            string(entry, "card_id").takeIf { string(entry, "pdf_stem") == pdfStem && it.isNotBlank() }
        }.toSet()
        return files.filter { record ->
            val entry = entries.get(record.path)?.asJsonObject ?: return@filter false
            string(entry, "pdf_stem") == pdfStem || string(entry, "card_id") in ids
        }.mapTo(hashSetOf()) { it.path }
    }

    @Synchronized fun projectForCard(cardId: String): String? = entries.entrySet().firstNotNullOfOrNull { (_, value) ->
        val entry = value.asJsonObject
        string(entry, "pdf_stem").takeIf { it.isNotBlank() && string(entry, "card_id") == cardId }
    }

    @Synchronized fun persist() {
        if (!dirty) return
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(entries.toString())
        check(temporary.renameTo(file)) { "Cannot save review project index" }
        dirty = false
    }

    companion object {
        private val indexes = ConcurrentHashMap<String, ReviewProjectIndex>()
        fun at(root: File): ReviewProjectIndex = indexes.computeIfAbsent(root.absolutePath) {
            ReviewProjectIndex(File(root, ".project-membership-v1.json"))
        }
    }
}
