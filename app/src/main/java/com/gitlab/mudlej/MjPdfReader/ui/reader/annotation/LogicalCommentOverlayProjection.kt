// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.github.barteksc.pdfviewer.PDFView

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentColor

internal data class LogicalCommentOverlaySpec(
    val commentId: String,
    val pageIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val selected: Boolean,
    val isPoint: Boolean = false,
    val sourceX: Float? = null,
    val sourceY: Float? = null,
    val hasThread: Boolean = true,
    val color: Int = 0xFFFFFF00.toInt(),
    val subtype: Int = PDFView.PdfComment.SUBTYPE_HIGHLIGHT,
)

internal fun logicalCommentOverlaySpecs(
    comments: List<OfflineLogicalComment>,
    pdfFilename: String,
    pdfSha256: String,
    selectedCommentId: String? = null,
    pageSize: (Int) -> Pair<Float, Float>? = { null },
): List<LogicalCommentOverlaySpec> {
    val slots = mutableMapOf<String, Int>()
    val selectedRoot = comments.firstOrNull { it.id == selectedCommentId }?.threadRootId ?: selectedCommentId
    return comments.sortedBy { it.id }
    .asSequence()
    .filter { comment ->
        !comment.deleted && comment.parentId == null && comment.id.isNotBlank()
    }
    .flatMap { comment ->
        val payload = runCatching { JsonParser.parseString(comment.payloadJson).asJsonObject }.getOrNull()
        val hasThread = comments.any { member ->
            !member.deleted && member.threadRootId == comment.id && runCatching {
                JsonParser.parseString(member.payloadJson).asJsonObject.get("contents")?.asString?.isNotBlank() == true
            }.getOrDefault(false)
        }
        val color = payload?.let(::logicalCommentColor) ?: 0xFFFFFF00.toInt()
        val placed = comment.locations.distinct().asSequence()
            .filter { location ->
                location.status == "exact" &&
                    location.pdfFilename.equals(pdfFilename, ignoreCase = true) &&
                    location.pdfSha256 == pdfSha256
            }
            .flatMap { location ->
                val isPoint = location.regions.isEmpty() && location.point.size == 2
                val key = if (isPoint) "${location.pageIndex}:${(location.point[0]*10000).toInt()}:${(location.point[1]*10000).toInt()}" else ""
                val slot = if (isPoint) slots.getOrDefault(key, 0).also { slots[key] = it + 1 } else 0
                val rects = location.regions.mapNotNull(::validNormalizedRect).ifEmpty {
                    location.point.takeIf { it.size == 2 }?.let { pointMarkerRect(it, slot) }?.let(::listOf).orEmpty()
                }
                rects.asSequence().map { rect ->
                    LogicalCommentOverlaySpec(
                        commentId = comment.id,
                        pageIndex = location.pageIndex,
                        left = rect[0],
                        top = rect[1],
                        right = rect[2],
                        bottom = rect[3],
                        selected = comment.id == selectedRoot,
                        isPoint = isPoint,
                        sourceX = location.point.getOrNull(0),
                        sourceY = location.point.getOrNull(1),
                    )
                }
            }.toList()
        val override = payload?.getAsJsonObject("pdf_placement")
        val explicit = if (override == null) emptyList() else capturedOriginOverlaySpecs(
            comment.copy(payloadJson = payload.deepCopy().apply { add("origin", override.deepCopy()) }.toString()),
            pdfFilename, pdfSha256, selectedRoot, pageSize,
        )
        explicit.ifEmpty { placed }.ifEmpty { capturedOriginOverlaySpecs(comment, pdfFilename, pdfSha256, selectedRoot, pageSize) }
            .mapNotNull { spec ->
                val subtype = PDFView.PdfComment.subtypeFromName(payload?.get("subtype")?.asString
                    ?: if (spec.isPoint) "Text" else "Highlight")
                if (subtype == 0 || (PDFView.PdfComment.isTextMarkup(subtype) && spec.isPoint)) null
                else spec.copy(hasThread = hasThread, color = color, subtype = subtype)
            }.asSequence()
    }
    .distinctBy { overlay ->
        listOf(
            overlay.commentId,
            overlay.pageIndex,
            overlay.left,
            overlay.top,
            overlay.right,
            overlay.bottom,
        )
    }
    .toList()
}

private fun validNormalizedRect(values: List<Float>): List<Float>? {
    if (values.size != 4 || values.any { !it.isFinite() || it !in 0f..1f }) return null
    return values.takeIf { it[0] < it[2] && it[1] < it[3] }
}

private fun pointMarkerRect(point: List<Float>, slot: Int): List<Float>? {
    val directionX = if (point[0] <= 0.5f) 1 else -1
    val directionY = if (point[1] <= 0.5f) 1 else -1
    val columns = ((if (directionX > 0) 0.988f - point[0] else point[0] - 0.012f) / 0.032f).toInt().coerceAtLeast(0) + 1
    val x = (point[0] + directionX * (slot % columns) * 0.032f).coerceIn(0.012f, 0.988f)
    val y = (point[1] + directionY * (slot / columns) * 0.032f).coerceIn(0.012f, 0.988f)
    if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
    val halfSide = 0.012f
    return listOf(
        (x - halfSide).coerceAtLeast(0f),
        (y - halfSide).coerceAtLeast(0f),
        (x + halfSide).coerceAtMost(1f),
        (y + halfSide).coerceAtMost(1f),
    )
}


/** Captured PDF coordinates are provisional and valid only on that exact PDF. */
internal fun capturedOriginOverlaySpecs(
    comment: OfflineLogicalComment,
    filename: String,
    pdfSha256: String,
    selectedRoot: String? = null,
    pageSize: (Int) -> Pair<Float, Float>? = { null },
): List<LogicalCommentOverlaySpec> = runCatching {
    val payload = JsonParser.parseString(comment.payloadJson).asJsonObject
    val origin = payload.getAsJsonObject("origin") ?: return@runCatching emptyList()
    if (origin.get("pdf_sha256")?.asString != pdfSha256 ||
        origin.get("collection")?.asString + ".pdf" != filename) return@runCatching emptyList()
    val isPoint = payload.get("subtype")?.asString.orEmpty().ifBlank { "Text" } == "Text"
    val rawOrigin = origin.get("geometry_format")?.asString == "pdf_points_bottom_left"
    val geometry = if (rawOrigin) origin.getAsJsonObject("raw_pdf_geometry") ?: return@runCatching emptyList() else origin
    val fragments = geometry.getAsJsonArray("fragments")?.takeIf { it.size() > 0 }
        ?: JsonArray().apply { add(geometry) }
    fragments.flatMap { element ->
        val fragment = element.asJsonObject
        val page = if (rawOrigin) (fragment.get("page")?.asInt ?: return@runCatching emptyList()) - 1
            else fragment.get("page_index")?.asInt ?: return@runCatching emptyList()
        if (page < 0) return@runCatching emptyList()
        val size = if (rawOrigin) pageSize(page) ?: return@runCatching emptyList() else 1f to 1f
        if (!size.first.isFinite() || !size.second.isFinite() || size.first <= 0 || size.second <= 0) return@runCatching emptyList()
        fun normalized(values: List<Float>) = if (!rawOrigin) values else values.mapIndexed { index, v -> if (index % 2 == 0) v / size.first else 1f - v / size.second }
        val rect = fragment.getAsJsonArray("rect")?.map { it.asFloat }?.let(::normalized)?.let { r ->
            if (rawOrigin && r.size == 4) listOf(minOf(r[0], r[2]), minOf(r[1], r[3]), maxOf(r[0], r[2]), maxOf(r[1], r[3])) else r
        }?.let(::validNormalizedRect)
            ?: return@runCatching emptyList()
        val quads = normalized(fragment.getAsJsonArray("quad_points")?.map { it.asFloat }.orEmpty())
        if (quads.size % 8 != 0 || quads.any { !it.isFinite() || it !in 0f..1f }) return@runCatching emptyList()
        val regions = if (isPoint || quads.isEmpty()) listOf(rect) else quads.chunked(8).map { quad ->
            val xs = quad.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = quad.filterIndexed { i, _ -> i % 2 == 1 }
            validNormalizedRect(listOf(xs.min(), ys.min(), xs.max(), ys.max())) ?: return@runCatching emptyList()
        }
        regions.map { r -> LogicalCommentOverlaySpec(comment.id, page, r[0], r[1], r[2], r[3],
            comment.id == selectedRoot, isPoint) }
    }
}.getOrDefault(emptyList())
