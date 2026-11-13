// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/** Scope reason of a comment made in the HTML view until Fedora inserts its marker. */
internal const val HTML_PENDING_SERVER_LINK = "html_pending_server_link"

/** Keep reply dependencies ahead of their children, including restored journals. */
internal fun <T> orderCommentCreations(records: List<T>, existingIds: Set<String>, id: (T) -> String, parent: (T) -> String): List<T> {
    val available = existingIds.toMutableSet()
    val pending = records.toMutableList()
    val ordered = mutableListOf<T>()
    while (pending.isNotEmpty()) {
        val ready = pending.filter { id(it) in available || parent(it).isBlank() || parent(it) in available }
        check(ready.isNotEmpty()) { "A comment reply is missing its parent; its save journal is retained" }
        ready.forEach { available.add(id(it)) }
        ordered.addAll(ready)
        pending.removeAll(ready.toSet())
    }
    return ordered
}

/** Local-only conversion: comment identity never depends on an anchor job. */
internal fun canonicalCommentForAnnotation(raw: JsonObject, collection: String, previous: JsonObject?, parent: JsonObject?): JsonObject? {
    fun text(key: String) = raw.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val id = text("id").ifBlank { text("annotation_id") }
    require(id.isNotBlank())
    val parentId = text("in_reply_to")
    val deleted = raw.get("deleted")?.asBoolean == true
    val fields = listOf("contents", "author", "subtype", "selected_text", "color", "priority", "location", "chatgpt_url")
    if (previous != null) {
        // Retired journals and sidecars cannot perform an Undo against canonical deletion history.
        if (previous.get("deleted")?.asBoolean == true && !deleted) return null
        if (deleted && raw.get("source_anchor_deletion")?.asBoolean == true) return null
        val deletionChanged = (previous.get("deleted")?.asBoolean == true) != deleted
        val changed = deletionChanged || fields.any { raw.has(it) && raw.get(it) != previous.get(it) }
        if (!changed) return null
        return previous.deepCopy().apply {
            if (!deletionChanged) fields.forEach { key -> raw.get(key)?.let { add(key, it.deepCopy()) } }
            addProperty("deleted", deleted)
            addProperty("revision", previous.get("revision").asLong + 1)
            addProperty("updated_at", text("updated_at"))
            addProperty("origin_id", "thoughts-android")
            listOf("locations", "source_file", "source_line", "source_sha256").forEach(::remove)
        }
    }
    require(parentId.isBlank() || parent?.get("comment_id")?.asString == parentId) { "The reply's comment must be saved first" }
    return JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("comment_id", id)
        addProperty("thread_root_id", if (parentId.isBlank()) id else parent!!.get("thread_root_id").asString)
        add("parent_id", if (parentId.isBlank()) JsonNull.INSTANCE else com.google.gson.JsonPrimitive(parentId))
        addProperty("revision", 1)
        addProperty("created_at", text("created").ifBlank { text("updated_at") })
        addProperty("updated_at", text("updated_at"))
        addProperty("origin_id", "thoughts-android")
        addProperty("deleted", deleted)
        fields.forEach { key -> raw.get(key)?.let { add(key, it.deepCopy()) } }
        add("scope", JsonObject().apply {
            addProperty("state", "pdf_only")
            addProperty("reason", "awaiting_unique_source_anchor")
        })
        add("origin", if (parentId.isNotBlank()) parent!!.get("origin").deepCopy() else JsonObject().apply {
            addProperty("collection", collection)
            addProperty("document_id", text("document_id"))
            addProperty("pdf_sha256", text("pdf_sha256"))
            addProperty("page_index", (raw.get("page")?.asInt?.minus(1) ?: 0).coerceAtLeast(0))
            add("rect", JsonArray())
            add("fragments", JsonArray())
            addProperty("geometry_format", "pdf_points_bottom_left")
            add("raw_pdf_geometry", JsonObject().apply {
                listOf("page", "rect", "quad_points", "fragments").forEach { key -> raw.get(key)?.let { add(key, it.deepCopy()) } }
            })
        })
    }
}
