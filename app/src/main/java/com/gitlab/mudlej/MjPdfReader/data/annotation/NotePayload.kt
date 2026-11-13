// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonNull
import com.google.gson.JsonObject

/** Notes share comment identity and synchronization, but never fabricate a PDF origin. */
internal fun newNotePayload(
    id: String,
    text: String,
    timestamp: String,
    attachments: List<NoteAttachment> = emptyList(),
    author: String = CommentAuthor.GENERIC_DEFAULT,
): JsonObject {
    require(id.isNotBlank() && (text.isNotBlank() || attachments.isNotEmpty()))
    return JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("comment_id", id)
        addProperty("thread_root_id", id)
        add("parent_id", JsonNull.INSTANCE)
        addProperty("revision", 1)
        addProperty("created_at", timestamp)
        addProperty("updated_at", timestamp)
        addProperty("origin_id", "thoughts-android")
        addProperty("deleted", false)
        addProperty("contents", text)
        NoteAttachment.write(this, attachments)
        addProperty("author", author)
        addProperty("subtype", "Note")
        addProperty("priority", "normal")
        add("origin", JsonNull.INSTANCE)
        add("project", JsonNull.INSTANCE)
        add("scope", JsonObject().apply { addProperty("state", "standalone") })
    }
}

/** Older outbox/projection serializers omitted JSON nulls; restore the Note contract on read. */
internal fun restoreNoteNulls(payload: JsonObject): JsonObject {
    if (payload.get("subtype")?.asString != "Note" ||
        payload.getAsJsonObject("scope")?.get("state")?.asString != "standalone") return payload
    return payload.deepCopy().apply {
        if (!has("origin")) add("origin", JsonNull.INSTANCE)
        add("project", JsonNull.INSTANCE)
        remove("project_name")
    }
}
