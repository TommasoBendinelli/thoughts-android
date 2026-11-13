// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.github.barteksc.pdfviewer.PDFView

data class PdfCommentThread(
    val root: PDFView.PdfComment,
    val replies: List<PDFView.PdfComment>,
)

fun buildPdfCommentThreads(comments: List<PDFView.PdfComment>): List<PdfCommentThread> {
    val ordered = comments.sortedWith(
        compareBy<PDFView.PdfComment>({ it.pageIndex }, { it.annotationIndex })
    )
    val byName = ordered
        .filter { it.name.isNotBlank() }
        .associateBy { it.name }
    val roots = LinkedHashMap<String, PDFView.PdfComment>()
    val replies = LinkedHashMap<String, MutableList<PDFView.PdfComment>>()

    fun key(comment: PDFView.PdfComment): String =
        comment.name.ifBlank {
            "${comment.pageIndex}:${comment.annotationIndex}:${comment.subtype}"
        }

    fun resolveRoot(comment: PDFView.PdfComment): PDFView.PdfComment {
        var current = comment
        val visited = mutableSetOf<String>()
        while (current.inReplyToName.isNotBlank() && visited.add(key(current))) {
            val parent = byName[current.inReplyToName] ?: break
            current = parent
        }
        return current
    }

    ordered.filterNot { it.isReply }.forEach { root ->
        roots[key(root)] = root
    }
    ordered.filter { it.isReply }.forEach { reply ->
        val root = resolveRoot(reply)
        val rootKey = key(root)
        roots.putIfAbsent(rootKey, root)
        if (reply !== root) {
            replies.getOrPut(rootKey) { mutableListOf() }.add(reply)
        }
    }

    return roots.values
        .sortedWith(compareBy({ it.pageIndex }, { it.annotationIndex }))
        .map { root ->
            val rootKey = key(root)
            PdfCommentThread(
                root = root,
                replies = replies[rootKey].orEmpty()
                    .sortedWith(compareBy({ it.pageIndex }, { it.annotationIndex })),
            )
        }
}
