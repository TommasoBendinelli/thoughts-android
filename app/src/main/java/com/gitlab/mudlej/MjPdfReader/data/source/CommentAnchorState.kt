// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonObject

/**
 * "Anchor removed": a source-linked comment whose marker vanished from the source without an
 * explicit delete. The comment is kept (`needs_attention` + `reason: anchor_removed`) with its
 * last verified location so it can be restored. Only the server's transition is synced; local
 * overlays compute the same scope for display.
 */
internal object CommentAnchorState {
    const val STATE = "needs_attention"
    const val REASON = "anchor_removed"
    const val DISPLAY_STATUS = "anchor_removed"
    private const val MAX_MIRROR = 16384

    fun isAnchorRemoved(scope: JsonObject?): Boolean =
        scope != null && scope.text("state") == STATE && scope.text("reason") == REASON

    fun isAnchorRemovedPayload(payload: JsonObject?): Boolean =
        isAnchorRemoved(payload?.get("scope")?.takeIf { it.isJsonObject }?.asJsonObject)

    /** Cheap test for a serialized payload; parses only when the reason text is present. */
    fun isAnchorRemovedPayloadJson(payloadJson: String): Boolean =
        payloadJson.contains(REASON) && runCatching {
            isAnchorRemovedPayload(com.google.gson.JsonParser.parseString(payloadJson).asJsonObject)
        }.getOrDefault(false)

    fun anchorKind(subtype: String, sourceFile: String): String = when {
        sourceFile.endsWith(".md") || sourceFile.endsWith(".markdown") -> "markdown"
        subtype == "StrikeOut" -> "CommentStrikethrough"
        subtype in setOf("Highlight", "Underline", "Squiggly") -> "CommentHighlight"
        else -> "CommentAnchor"
    }

    /** The local overlay scope: the last verified location carried over, without a server timestamp. */
    fun anchorRemovedScope(last: JsonObject, anchorId: String, payload: JsonObject): JsonObject {
        val sourceFile = last.text("source_file")
        return JsonObject().apply {
            addProperty("state", STATE)
            addProperty("reason", REASON)
            addProperty("anchor_id", anchorId)
            if (sourceFile.isNotBlank()) addProperty("source_file", sourceFile)
            last.get("source_line")?.takeIf { it.isJsonPrimitive }?.let { add("source_line", it.deepCopy()) }
            addProperty("anchor_kind", last.text("anchor_kind").ifBlank { anchorKind(payload.text("subtype"), sourceFile) })
            addProperty("anchor_mirror", last.text("anchor_mirror").ifBlank { payload.text("contents") }.take(MAX_MIRROR))
            last.get("anchor_removed_at")?.takeIf { it.isJsonPrimitive }?.let { add("anchor_removed_at", it.deepCopy()) }
        }
    }

    private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
