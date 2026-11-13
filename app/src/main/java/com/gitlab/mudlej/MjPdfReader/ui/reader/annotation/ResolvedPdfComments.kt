// Written for Thoughts. License is GPLv3.
package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.graphics.RectF
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.data.annotation.*
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.google.gson.JsonParser

/** Dimensions are already cached by the viewer and do not depend on resolved annotations. */
internal fun PDFView.commentPageSizes(): Map<Int, Pair<Float, Float>> =
    (0 until pageCount).mapNotNull { page ->
        getPagePointSize(page)?.takeIf { it.width > 0f && it.height > 0f }
            ?.let { page to (it.width to it.height) }
    }.toMap()

/** One current-PDF projection for drawing, the native list, selection, and export. */
internal fun resolveStoredPdfComments(
    stored: List<StoredPdfComment>,
    logical: List<OfflineLogicalComment>,
    filename: String,
    pdfSha256: String,
    pageSize: (Int) -> Pair<Float, Float>?,
): List<StoredPdfComment> {
    val result = mutableMapOf<String, StoredPdfComment>()
    val byId = logical.associateBy { it.id }
    val overlays = logicalCommentOverlaySpecs(logical, filename, pdfSha256, pageSize = pageSize).groupBy { it.commentId }
    for (comment in logical) {
        val root = byId[comment.threadRootId] ?: comment
        if (comment.deleted || root.deleted) {
            result.remove(comment.id)
            continue
        }
        val correspondence = root.correspondence
        val specs = overlays[root.id].orEmpty()
        if (specs.isEmpty()) {
            result.remove(comment.id)
            // Unplaced comments remain accessible through the logical list.
            continue
        }
        val payload = runCatching { JsonParser.parseString(comment.payloadJson).asJsonObject }.getOrNull() ?: continue
        val groups = if (specs.first().isPoint) specs.map { listOf(it) } else specs.groupBy { it.pageIndex }.toSortedMap().values.toList()
        val fragments = groups.mapNotNull { regions ->
            val page = regions.first().pageIndex
            val size = pageSize(page) ?: return@mapNotNull null
            if (!size.first.isFinite() || !size.second.isFinite() || size.first <= 0 || size.second <= 0) return@mapNotNull null
            StoredPdfCommentFragment(page, regions.map { region -> RectF(
                region.left * size.first, (1f-region.bottom) * size.second,
                region.right * size.first, (1f-region.top) * size.second,
            ) })
        }
        if (fragments.size != groups.size || fragments.isEmpty()) { result.remove(comment.id); continue }
        fun text(name: String) = payload.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val rootPayload = runCatching { JsonParser.parseString(root.payloadJson).asJsonObject }.getOrNull()
        val subtype = if (comment.parentId != null) PDFView.PdfComment.SUBTYPE_TEXT
            else PDFView.PdfComment.subtypeFromName(rootPayload?.get("subtype")?.asString ?: if (specs.first().isPoint) "Text" else "Highlight")
        if (subtype == 0 || (PDFView.PdfComment.isTextMarkup(subtype) && specs.first().isPoint)) {
            result.remove(comment.id)
            continue
        }
        result[comment.id] = StoredPdfComment(
            pageIndex = fragments.first().pageIndex, subtype = subtype, id = comment.id,
            rects = fragments.first().rects, fragments = fragments,
            color = logicalCommentColor(payload), contents = text("contents"), author = text("author"),
            creationDate = text("created_at"), modifiedDate = text("updated_at"),
            inReplyTo = if (comment.parentId == null) "" else root.id, selectedText = text("selected_text"),
            priority = CommentPriority.fromWireValue(text("priority")),
            location = payload.get("location")?.takeIf { it.isJsonObject }?.asJsonObject?.let(CommentLocation::fromJson),
            anchorStatus = correspondence?.state ?: comment.scopeState, chatGptUrl = text("chatgpt_url"), annotationPdfSha256 = pdfSha256,
        )
    }
    return result.values.sortedBy { it.id }
}

internal fun managedPdfCommentRecords(
        comments: List<StoredPdfComment>,
    ): List<PDFView.ManagedPdfComment> = buildList {
        comments.forEach { comment ->
            val fragments = if (
                comment.fragments.isNotEmpty()
            ) {
                comment.fragments
            } else {
                listOf(
                    com.gitlab.mudlej.MjPdfReader.data.annotation.StoredPdfCommentFragment(
                        comment.pageIndex,
                        comment.rects,
                    )
                )
            }
            fragments.forEachIndexed { index, fragment ->
                add(
                    PDFView.ManagedPdfComment(
                        fragment.pageIndex,
                        comment.subtype,
                        PDFView.managedCommentFragmentName(comment.id, index),
                        fragment.rects,
                        comment.color,
                        if (index == 0) comment.contents else "",
                        comment.author,
                        comment.creationDate,
                        comment.modifiedDate,
                        comment.inReplyTo,
                        comment.anchorStatus,
                    )
                )
            }
        }
    }
