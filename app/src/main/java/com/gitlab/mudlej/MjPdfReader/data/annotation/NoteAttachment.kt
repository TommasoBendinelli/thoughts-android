// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Portable references only; local paths and picker permissions never enter Sync. */
internal data class NoteAttachment(val id: String, val name: String, val mimeType: String, val sha256: String, val size: Long) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        require(name.isNotBlank() && name !in setOf(".", "..") && name.length <= 255 && name.none { it.isISOControl() || it == '/' || it == '\\' })
        require(mimeType.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(size in 0..MAX_BYTES)
    }
    fun toJson() = JsonObject().apply {
        addProperty("id", id); addProperty("name", name); addProperty("mime_type", mimeType)
        addProperty("object_sha256", sha256); addProperty("size_bytes", size)
    }
    companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
        fun read(payload: JsonObject): List<NoteAttachment> = payload.get("attachments")?.let { raw ->
            require(raw.isJsonArray && raw.asJsonArray.size() <= 100) { "Invalid attachments" }
            raw.asJsonArray.map { value ->
                val item = value.asJsonObject
                NoteAttachment(item.get("id").asString, item.get("name").asString, item.get("mime_type").asString,
                    item.get("object_sha256").asString, item.get("size_bytes").asLong)
            }.also { require(it.map(NoteAttachment::id).distinct().size == it.size) }
        }.orEmpty()
        fun write(payload: JsonObject, items: List<NoteAttachment>): JsonObject = payload.apply {
            require(items.size <= 100)
            add("attachments", JsonArray().apply { items.forEach { add(it.toJson()) } })
        }
    }
}
