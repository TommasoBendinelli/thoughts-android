// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReading

internal fun AnnotationEdit.forReadingDocument(document: ProjectReading.Document): AnnotationEdit {
    fun local(page: Int): Int {
        require(page in document.firstPage until document.firstPage + document.pages) { "A comment must belong to one document" }
        return page - document.firstPage
    }
    return when (this) {
        is AnnotationEdit.AddPdfComment -> copy(page = local(page))
        is AnnotationEdit.AddPdfMarkupComment -> copy(page = local(page), fragments = fragments.map { it.copy(page = local(it.page)) })
        is AnnotationEdit.AddPdfReply -> copy(page = local(page))
        is AnnotationEdit.SetPdfCommentContents -> copy(page = local(page))
        is AnnotationEdit.SetPdfCommentMetadata -> copy(page = local(page))
        is AnnotationEdit.DeletePdfComment -> copy(page = local(page))
        else -> error("Unsupported project comment action")
    }
}
