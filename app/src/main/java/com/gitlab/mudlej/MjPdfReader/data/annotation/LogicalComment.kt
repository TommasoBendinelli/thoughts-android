// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

data class LogicalCommentPdfLocation(
    val pdfFilename: String,
    val pdfSha256: String,
    val page: Int,
    val status: String,
    val point: List<Float> = emptyList(),
    val regions: List<List<Float>> = emptyList(),
    val sourceSha256: String = "",
    val sourceBundleSha256: String = "",
    val buildSha256: String = "",
    val role: String = "target",
    val placementKind: String = if (regions.isNotEmpty()) "exact_text" else "exact_point",
    val proof: ReviewPlacementProof? = null,
    /**
     * A copy LaTeX typeset elsewhere (contents entry, list of figures or
     * tables, running head) rather than the source's own occurrence.
     */
    val duplicate: Boolean = false,
) : java.io.Serializable {
    val pageIndex: Int get() = (page - 1).coerceAtLeast(0)
    val isExact: Boolean get() = status == "exact"
    val canDisplayExactPlacement: Boolean get() = isExact && page > 0 &&
        if (regions.isNotEmpty()) regions.all { rect ->
            rect.size == 4 && rect.all { it.isFinite() && it in 0f..1f } && rect[2] > rect[0] && rect[3] > rect[1]
        } else point.size == 2 && point.all { it.isFinite() && it in 0f..1f }
    val isPageOnly: Boolean get() = status == "page_only"
}

/**
 * The first location matching [predicate], preferring the source's own
 * occurrence over LaTeX-made duplicates and falling back to a duplicate only
 * when nothing else matches.
 */
internal inline fun List<LogicalCommentPdfLocation>.firstPrimaryOrNull(
    predicate: (LogicalCommentPdfLocation) -> Boolean = { true },
): LogicalCommentPdfLocation? = firstOrNull { !it.duplicate && predicate(it) } ?: firstOrNull(predicate)

data class ReviewSourceTarget(
    val role: String,
    val sourceFile: String,
    val sourceSha256: String,
    val byteStart: Long,
    val byteEnd: Long,
    val lineStart: Int,
    val lineEnd: Int,
    val columnStart: Int,
    val columnEnd: Int,
    val textSha256: String,
    val renderIntent: String,
    val preview: String,
    val anchorId: String,
) : java.io.Serializable

private val COMPILER_SPAN_PATTERN = Regex("span-[0-9]+")

data class ReviewPlacementProof(
    val resolverVersion: Int,
    val method: String,
    val sourceSha256: String,
    val sourceBundleSha256: String,
    val buildSha256: String,
    val pdfSha256: String,
    val extractedTextSha256: String,
    val candidateCount: Int,
    val uniqueness: String,
    val verified: Boolean,
    val reason: String,
    val humanConfirmed: Boolean,
    val markerIds: List<String> = emptyList(),
    val occurrence: Int = 0,
    // Older Gson/serialized projections have no values for these added fields.
    // Kotlin constructor defaults are not applied when those caches are read.
    val sourceFormat: String? = "latex",
    val rendererEvidenceSha256: String? = "",
    val fragmentId: String? = "",
) : java.io.Serializable {
    fun hasCompilerEvidence(reviewKey: String, placementKind: String): Boolean {
        if (resolverVersion !in setOf(3, 4) || !verified || candidateCount != 1 || occurrence < 1) return false
        if (resolverVersion == 4) {
            val prefix = "$reviewKey:"
            return sourceFormat == "markdown" && reviewKey.startsWith("comment:") &&
                rendererEvidenceSha256.orEmpty().matches(Regex("[0-9a-f]{64}")) &&
                fragmentId.orEmpty().matches(Regex("thoughts-md-fragment-[1-9][0-9]*")) &&
                markerIds.size == 1 && markerIds[0].startsWith(prefix) &&
                markerIds[0].removePrefix(prefix).matches(Regex("[A-Za-z0-9_-]+")) &&
                when (placementKind) {
                    "exact_point" -> method == "markdown_source_boundary"
                    "exact_text" -> method in setOf("markdown_text_fragment", "markdown_svg_object", "markdown_image_object")
                    else -> false
                }
        }
        if (sourceFormat != null && sourceFormat != "latex") return false
        val roles = when (placementKind) {
            "exact_point" -> {
                if (method != "tex_shipout_point" || markerIds.size != 1) return false
                listOf("point")
            }
            "exact_text" -> {
                if (method !in setOf("tex_shipout_text", "tex_shipout_box") || markerIds.size != 2) return false
                listOf("start", "end")
            }
            else -> return false
        }
        val prefix = "thoughts-shipout:$reviewKey:"
        val suffix = ":$occurrence"
        val stems = markerIds.mapIndexed { index, marker ->
            if (!marker.startsWith(prefix) || !marker.endsWith(suffix)) return false
            val role = marker.removePrefix(prefix).removeSuffix(suffix)
            if (placementKind == "exact_point") {
                if (role != roles[index]) return false
                ""
            } else {
                if (role == roles[index]) ""
                else {
                    val ending = "-${roles[index]}"
                    if (!role.endsWith(ending)) return false
                    role.removeSuffix(ending).also { if (!it.matches(COMPILER_SPAN_PATTERN)) return false }
                }
            }
        }
        return stems.distinct().size == 1
    }
}

data class ReviewCorrespondence(
    val reviewKey: String,
    val kind: String,
    val itemId: String,
    val threadRootId: String,
    val lifecycle: String,
    val state: String,
    val reason: String,
    val sourceBundleSha256: String,
    val sourceTargets: List<ReviewSourceTarget>,
    val locations: List<LogicalCommentPdfLocation>,
) : java.io.Serializable

internal fun logicalCommentMutationPayload(
    comment: OverviewComment,
    contents: String = comment.contents,
    color: Int = comment.color,
    priority: CommentPriority = comment.priority,
    location: CommentLocation? = comment.location,
    chatGptUrl: String = comment.chatGptUrl,
    deleted: Boolean = false,
): JsonObject? {
    val payload = runCatching {
        JsonParser.parseString(comment.logicalPayloadJson).asJsonObject.deepCopy()
    }.getOrNull() ?: return null
    return logicalCommentMutationPayload(
        payload,
        comment.id,
        deleted,
        contents,
        color,
        priority,
        location,
        chatGptUrl,
        replaceLocation = true,
    )
}

/** The `deletion_reason` of an explicit delete (the shared SDK's `CommentService` writes the same). */
internal const val USER_DELETION_REASON = "user_deleted"

internal fun logicalCommentMutationPayload(
    basePayload: JsonObject,
    commentId: String,
    deleted: Boolean,
    contents: String? = null,
    color: Int? = null,
    priority: CommentPriority? = null,
    location: CommentLocation? = null,
    chatGptUrl: String? = null,
    replaceLocation: Boolean = false,
    needsAction: Boolean? = null,
): JsonObject? {
    val payload = basePayload.deepCopy()
    val id = payload.string("comment_id").takeIf(String::isNotBlank) ?: return null
    if (id != commentId || payload.int("schema_version") != 1) return null
    if (payload.get("origin")?.isJsonObject != true &&
        payload.get("scope")?.takeIf { it.isJsonObject }?.asJsonObject?.string("state") != "standalone"
    ) return null
    payload.addProperty("revision", payload.long("revision").coerceAtLeast(0) + 1)
    payload.addProperty("updated_at", Instant.now().toString())
    payload.addProperty("origin_id", "thoughts-android")
    payload.addProperty("deleted", deleted)
    if (deleted) payload.addProperty("deletion_reason", USER_DELETION_REASON) else payload.remove("deletion_reason")
    contents?.let { payload.addProperty("contents", it) }
    priority?.let { payload.addProperty("priority", it.wireValue) }
    needsAction?.let { payload.addProperty("needs_action", it) }
    color?.let { payload.add("color", colorToJson(it)) }
    if (replaceLocation) payload.keepOrReplaceCommentLocation("location", location, removeWhenAbsent = false)
    chatGptUrl?.let { payload.addProperty("chatgpt_url", it) }
    payload.remove("locations")
    payload.remove("source_file")
    payload.remove("source_line")
    payload.remove("source_sha256")
    return payload
}


internal fun logicalCommentColor(payload: JsonObject): Int {
    val values = payload.getAsJsonArray("color")
    fun channel(index: Int): Int {
        val fallback = when (index) {
            0 -> CommentCreationMetadata.DEFAULT_POPUP_COLOR ushr 16 and 0xFF
            1 -> CommentCreationMetadata.DEFAULT_POPUP_COLOR ushr 8 and 0xFF
            else -> CommentCreationMetadata.DEFAULT_POPUP_COLOR and 0xFF
        }
        val value = runCatching { values?.get(index)?.asDouble }.getOrNull() ?: return fallback
        return (if (value <= 1.0) value * 255.0 else value).toInt().coerceIn(0, 255)
    }
    val alpha = runCatching { values?.get(3)?.asDouble }.getOrNull()
        ?.let { if (it <= 1.0) it * 255.0 else it }
        ?.toInt()
        ?.coerceIn(0, 255)
        ?: 255
    return (alpha shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
}

private fun colorToJson(color: Int): JsonArray = JsonArray().apply {
    add((color ushr 16 and 0xFF) / 255.0)
    add((color ushr 8 and 0xFF) / 255.0)
    add((color and 0xFF) / 255.0)
    add((color ushr 24 and 0xFF) / 255.0)
}

private fun JsonObject.string(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

private fun JsonObject.int(name: String): Int =
    runCatching { get(name)?.asInt }.getOrNull() ?: 0

private fun JsonObject.long(name: String): Long =
    runCatching { get(name)?.asLong }.getOrNull() ?: 0L
