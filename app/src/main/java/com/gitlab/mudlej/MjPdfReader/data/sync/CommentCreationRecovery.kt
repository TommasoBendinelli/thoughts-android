// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tbend.thoughts.sdk.ProjectionRecord

private val DUPLICATE_CREATION_FAILURES = listOf(
    "logical comment origin is immutable",
    "logical comment revision conflict: expected base_revision ",
)

/** A second first-revision capture of a comment Fedora already accepted. */
internal fun ThoughtsOutboxEntity.isBlockedDuplicateCreation(): Boolean = runCatching {
    deliveryState == ThoughtsOutboxEntity.STATE_BLOCKED && entityType == "logical_comment" &&
        collection == "comments" && failureCode == "http_409" && baseRevision == 0L &&
        DUPLICATE_CREATION_FAILURES.any(failureDetail::startsWith) &&
        JsonParser.parseString(payloadJson).asJsonObject.let {
            it.get("revision")?.asInt == 1 && it.get("deleted")?.asBoolean != true
        }
}.getOrDefault(false)

/** Only a live canonical comment with the same identity, capture and contents settles the duplicate. */
internal fun confirmedDuplicateCreation(
    operation: ThoughtsOutboxEntity,
    canonical: Map<String, ProjectionRecord>,
): ProjectionRecord? = runCatching {
    if (!operation.isBlockedDuplicateCreation()) return null
    val payload = JsonParser.parseString(operation.payloadJson).asJsonObject
    val comment = canonical[operation.entityId] ?: return null
    if (comment.deleted || comment.payload.get("deleted")?.asBoolean == true) return null
    fun JsonObject.field(key: String): JsonElement = get(key)?.takeUnless {
        it.isJsonNull || it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isEmpty()
    } ?: JsonNull.INSTANCE
    if (listOf("comment_id", "parent_id", "thread_root_id", "subtype", "contents", "author",
            "selected_text", "color", "priority", "location", "chatgpt_url").any {
        payload.field(it) != comment.payload.field(it)
    }) return null
    if (captureOrigin(payload) != captureOrigin(comment.payload)) return null
    comment
}.getOrNull()

// Recaptured annotations may list an empty fragment set the original capture omitted.
private fun captureOrigin(payload: JsonObject): JsonElement {
    val origin = payload.get("origin")?.deepCopy() ?: return JsonNull.INSTANCE
    if (!origin.isJsonObject) return origin
    origin.asJsonObject.get("raw_pdf_geometry")?.takeIf { it.isJsonObject }?.asJsonObject?.let { raw ->
        if ((raw.get("fragments") as? JsonArray)?.size() == 0) raw.remove("fragments")
    }
    return origin
}
