// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser

internal const val STANDALONE_NOTES_CAPABILITY = "standalone_notes_v1"

/** Only the legacy schema rejection is recoverable by advertising Notes support. */
internal fun ThoughtsOutboxEntity.hasLegacyNoteSchemaFailure(): Boolean = runCatching {
    if (deliveryState != ThoughtsOutboxEntity.STATE_BLOCKED || failureCode != "http_422" ||
        entityType != "logical_comment" || collection != "comments") return false
    val payload = JsonParser.parseString(payloadJson).asJsonObject
    if (payload.get("subtype")?.asString != "Note" ||
        payload.getAsJsonObject("scope")?.get("state")?.asString != "standalone" ||
        payload.get("origin")?.let { !it.isJsonNull } == true) return false
    val errors = JsonParser.parseString(failureDetail).asJsonObject.getAsJsonArray("detail")
    if (errors.size() != 2) return false
    val paths = errors.map { value ->
        val error = value.asJsonObject
        val path = error.getAsJsonArray("loc").map { it.asString }
        when {
            path.takeLast(1) == listOf("origin") &&
                error.get("type")?.asString == "model_type" &&
                error.getAsJsonObject("ctx")?.get("class_name")?.asString == "LogicalCommentOrigin" -> "origin"
            path.takeLast(2) == listOf("scope", "state") &&
                error.get("type")?.asString == "literal_error" &&
                error.get("input")?.asString == "standalone" &&
                error.getAsJsonObject("ctx")?.get("expected")?.asString ==
                "'pdf_only', 'source_linked' or 'needs_attention'" -> "scope"
            else -> return false
        }
    }
    paths.toSet() == setOf("origin", "scope")
}.getOrDefault(false)

internal fun recoverableNoteOperationIds(
    operations: List<ThoughtsOutboxEntity>,
    serverCapabilities: List<String>,
): List<String> = if (STANDALONE_NOTES_CAPABILITY in serverCapabilities) {
    operations.filter { it.hasLegacyNoteSchemaFailure() }.map { it.operationId }
} else emptyList()
