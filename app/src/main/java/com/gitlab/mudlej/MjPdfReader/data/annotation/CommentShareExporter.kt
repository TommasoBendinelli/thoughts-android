// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.core.io.parseCommentTimestamp
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CommentShareExport(
    val uri: Uri,
    val fileName: String,
    val threadCount: Int,
    val commentCount: Int,
)

class CommentShareExporter(
    private val context: Context,
    private val sidecarStore: CommentSidecarStore = CommentSidecarStore(context),
) {

    suspend fun export(
        documentUri: Uri,
        documentName: String,
        embeddedComments: List<PDFView.PdfComment>? = null,
        pageSelection: PdfPageSelection = PdfPageSelection.All,
        sharedDocumentUri: Uri = documentUri,
        sharedDocumentName: String = documentName,
    ): CommentShareExport = withContext(Dispatchers.IO) {
        val sidecar = sidecarStore.sidecarFile(documentUri, documentName)
        val embedded = embeddedComments ?: if (sidecar.isFile) {
            emptyList()
        } else {
            readEmbeddedComments(documentUri)
        }
        val sourceHash = computeSha256(context, documentUri).orEmpty()
        val store = LogicalCommentStore(context)
        store.importDocument(documentUri, documentName, sourceHash, embedded)
        val snapshot = store.snapshot(documentName)
        val comments = selectCommentsForShare(
            projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(), snapshot.logicalComments,
                documentName, sourceHash).documents.flatMap { it.comments },
            pageSelection,
        )
        val pdfSha256 = computeSha256(context, sharedDocumentUri).orEmpty()
        val exportedAt = Instant.now()
        val json = buildCommentShareJson(
            sharedDocumentName,
            pdfSha256,
            exportedAt,
            comments,
            pageSelection,
        )
        val target = commentShareCacheFile(context, sharedDocumentName)
        val temporary = File(target.parentFile, "${target.name}.part")
        temporary.writeText(json)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        val roots = comments.count { it.parentId.isBlank() } +
            orphanReplies(comments).size
        CommentShareExport(
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            ),
            fileName = target.name,
            threadCount = roots,
            commentCount = comments.size,
        )
    }

    private fun readEmbeddedComments(uri: Uri): List<PDFView.PdfComment> {
        val descriptor = when (uri.scheme) {
            "file" -> ParcelFileDescriptor.open(
                File(requireNotNull(uri.path)),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            else -> context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Could not open PDF for comment export")
        }
        val core = PdfiumCore(context)
        var document: PdfDocument? = null
        try {
            val openedDocument = core.newDocument(descriptor)
            document = openedDocument
            return buildList {
                repeat(core.getPageCount(openedDocument)) { page ->
                    core.getPdfComments(openedDocument, page).forEach { comment ->
                        add(
                            PDFView.PdfComment(
                                page,
                                comment.annotationIndex,
                                comment.subtype,
                                comment.name,
                                null,
                                comment.bounds,
                                comment.color,
                                comment.contents,
                                comment.author,
                                comment.creationDate,
                                comment.modifiedDate,
                                comment.inReplyToName,
                                comment.anchorStatus,
                            )
                        )
                    }
                }
            }
        } finally {
            if (document != null) {
                core.closeDocument(document)
            } else {
                descriptor.close()
            }
        }
    }

    companion object {
        private const val COMMENT_SIDECAR_SUFFIX = "_comments.jsonl"
    }
}

internal fun buildCommentShareJson(
    documentName: String,
    pdfSha256: String,
    exportedAt: Instant,
    comments: List<OverviewComment>,
    pageSelection: PdfPageSelection = PdfPageSelection.All,
): String {
    val roots = comments.filter { it.parentId.isBlank() }
        .sortedWith(commentExportOrder())
    val repliesByParent = comments.filter(OverviewComment::isReply)
        .groupBy(OverviewComment::parentId)
    val orphans = orphanReplies(comments).sortedWith(commentExportOrder())
    val threads = JsonArray()
    roots.forEach { root ->
        threads.add(
            commentThreadJson(
                root,
                repliesByParent[root.id].orEmpty(),
                pageSelection = pageSelection,
            )
        )
    }
    orphans.forEach { orphan ->
        threads.add(
            commentThreadJson(
                orphan,
                emptyList(),
                orphan.parentId,
                pageSelection,
            )
        )
    }

    val export = JsonObject().apply {
        addProperty("format", "thoughts-comments")
        addProperty("schema_version", 1)
        addProperty(
            "description",
            "Current comments and replies for the accompanying PDF. Page numbers are one-based.",
        )
        add("document", JsonObject().apply {
            addProperty("filename", ensureCommentsDocumentName(documentName))
            pdfSha256.takeIf(String::isNotBlank)?.let { addProperty("sha256", it) }
        })
        addProperty("exported_at", exportedAt.toString())
        addProperty("page_numbering", "one-based")
        addProperty(
            "pdf_coordinate_system",
            "PDF points: [left, bottom, right, top] in the page coordinate system.",
        )
        addProperty("thread_count", threads.size())
        addProperty("comment_count", comments.size)
        add("threads", threads)
    }
    return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(export) + "\n"
}

private fun commentThreadJson(
    root: OverviewComment,
    replies: List<OverviewComment>,
    orphanedParentId: String = "",
    pageSelection: PdfPageSelection = PdfPageSelection.All,
): JsonObject = commentJson(root, pageSelection).apply {
    orphanedParentId.takeIf(String::isNotBlank)?.let {
        addProperty("orphaned_parent_id", it)
    }
    add("replies", JsonArray().apply {
        replies.sortedWith(commentExportOrder()).forEach {
            add(commentJson(it, pageSelection))
        }
    })
}

private fun commentJson(
    comment: OverviewComment,
    pageSelection: PdfPageSelection,
): JsonObject = JsonObject().apply {
    addProperty("id", comment.id)
    addProperty("page", pageSelection.sharedPageIndex(comment.pageIndex) + 1)
    if (pageSelection is PdfPageSelection.Range) {
        addProperty("original_page", comment.pageIndex + 1)
    }
    addProperty("type", comment.subtype.ifBlank { "Text" }.lowercase())
    addProperty("text", comment.contents)
    addOptional("selected_text", comment.selectedText)
    addOptional("author", comment.author)
    addOptional("created_at", normalizedCommentTime(comment.created))
    addOptional("modified_at", normalizedCommentTime(comment.modified))
    addProperty("priority", comment.priority.wireValue)
    if (comment.pdfRect.size == 4) {
        add("pdf_rect", JsonArray().apply { comment.pdfRect.forEach(::add) })
    }
    comment.location?.let { add("location", it.toJson()) }
    val source = JsonObject().apply {
        addOptional("file", comment.sourceFile)
        comment.sourceLine.takeIf { it > 0 }?.let { addProperty("line", it) }
        addOptional("context", comment.sourceContext)
        addOptional("anchor_status", comment.anchorStatus)
        addOptional("anchor_confidence", comment.anchorConfidence)
    }
    if (source.size() > 0) add("source", source)
}

private fun JsonObject.addOptional(name: String, value: String) {
    value.takeIf(String::isNotBlank)?.let { addProperty(name, it) }
}

private fun normalizedCommentTime(value: String): String {
    return parseCommentTimestamp(value)?.toString() ?: value
}

private fun commentExportOrder(): Comparator<OverviewComment> =
    compareBy<OverviewComment> { it.pageIndex }
        .thenBy { it.activityMillis }
        .thenBy { it.id }

private fun orphanReplies(comments: List<OverviewComment>): List<OverviewComment> {
    val ids = comments.map(OverviewComment::id).toSet()
    return comments.filter { it.isReply && it.parentId !in ids }
}

internal fun selectCommentsForShare(
    comments: List<OverviewComment>,
    pageSelection: PdfPageSelection,
): List<OverviewComment> = comments.filter { pageSelection.includes(it.pageIndex) }

private fun ensureCommentsDocumentName(name: String): String {
    val clean = name.substringAfterLast('/').ifBlank { "Shared PDF.pdf" }
    return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
}

private fun commentShareCacheFile(context: Context, documentName: String): File {
    val pdfName = ensureCommentsDocumentName(documentName)
    val stem = pdfName.dropLast(4)
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .trim()
        .trim('.')
        .ifBlank { "Shared PDF" }
    val directory = File(context.cacheDir, "share").apply { mkdirs() }
    val expiredBefore = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    directory.listFiles()?.filter {
        (it.name.endsWith(".pdf", ignoreCase = true) ||
            it.name.endsWith(".comments.json", ignoreCase = true)) &&
            it.lastModified() < expiredBefore
    }?.forEach(File::delete)
    return File(directory, "$stem.comments.json")
}
