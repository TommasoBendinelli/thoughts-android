// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.SourceOwner
import com.google.gson.JsonElement
import com.google.gson.JsonParser

internal fun logicalCommentScopeKeys(entityId: String, raw: String): Set<String> {
    val keys = linkedSetOf("comment:$entityId")
    fun visit(value: JsonElement, field: String = "", parent: String = "") {
        when {
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { (name, child) -> visit(child, name, field) }
            value.isJsonArray -> value.asJsonArray.forEach { visit(it, field, parent) }
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                val text = value.asString
                if (text.isNotBlank()) when (field) {
                    "comment_id", "thread_root_id", "parent_id", "in_reply_to" -> keys += "comment:$text"
                    "collection" -> if (parent == "origin" || parent == "pdf_placement") keys += "pdf:${text.removeSuffix(".pdf")}"
                    "pdf_filename", "pdf_filenames", "pdf_stem" -> keys += "pdf:${text.removeSuffix(".pdf")}"
                    "source_file", "primary_source_file" -> SourceOwner.of(text)?.let { keys += "project:$it" }
                }
            }
        }
    }
    runCatching { visit(JsonParser.parseString(raw)) }
    return keys
}
