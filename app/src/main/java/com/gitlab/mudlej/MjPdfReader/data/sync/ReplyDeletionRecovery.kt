// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tbend.thoughts.sdk.ProjectionRecord

internal fun ThoughtsOutboxEntity.isBlockedReplyDeletion(): Boolean = runCatching {
    deliveryState == ThoughtsOutboxEntity.STATE_BLOCKED && entityType == "logical_comment" &&
        collection == "comments" && failureCode == "http_409" &&
        failureDetail == "logical comment reply references a missing parent" &&
        JsonParser.parseString(payloadJson).asJsonObject.let {
            it.get("deleted")?.asBoolean == true && !it.get("parent_id")?.asString.isNullOrBlank()
        }
}.getOrDefault(false)

/** Only fresh canonical tombstones with matching immutable identity settle a blocked deletion. */
internal fun confirmedReplyDeletion(
    operation: ThoughtsOutboxEntity,
    canonical: Map<String, ProjectionRecord>,
): ProjectionRecord? = runCatching {
    if (!operation.isBlockedReplyDeletion()) return null
    val payload = JsonParser.parseString(operation.payloadJson).asJsonObject
    val reply = canonical[operation.entityId] ?: return null
    val parent = canonical[payload.get("parent_id").asString] ?: return null
    if (!reply.deleted || !parent.deleted || reply.payload.get("deleted")?.asBoolean != true ||
        parent.payload.get("deleted")?.asBoolean != true) return null
    fun JsonObject.field(key: String) = get(key) ?: JsonNull.INSTANCE
    if (listOf("comment_id", "parent_id", "thread_root_id", "origin", "subtype").any {
        payload.field(it) != reply.payload.field(it)
    }) return null
    if (reply.payload.field("thread_root_id") != parent.payload.field("thread_root_id")) return null
    reply
}.getOrNull()
