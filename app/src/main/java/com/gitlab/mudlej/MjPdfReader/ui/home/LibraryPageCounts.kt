// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability
import com.google.gson.JsonObject

/**
 * A Project's pages by kind: of documents built from linked sources (TeX, Markdown), of plain PDFs
 * (imported papers), and of documents whose kind is not known yet (before the source index arrives).
 */
internal data class LibraryPageCounts(val source: Int = 0, val pdf: Int = 0, val unknown: Int = 0)

internal fun libraryPageCounts(documents: List<JsonObject>, index: SourceLinkedPdfIndexSnapshot): LibraryPageCounts {
    var source = 0
    var pdf = 0
    var unknown = 0
    for (document in documents) {
        val pages = document.get("page_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (pages <= 0) continue
        when (libraryDocumentSourceCapability(document, index)) {
            SourcePdfCapability.SOURCE_LINKED -> source += pages
            SourcePdfCapability.NOT_SOURCE_LINKED -> pdf += pages
            else -> unknown += pages
        }
    }
    return LibraryPageCounts(source, pdf, unknown)
}

/**
 * "120 source + 235 PDF pages" for a Project row: a kind with no pages is left out, pages of unknown
 * kind are "other" beside known ones and plain "pages" alone; null when there are no pages.
 */
internal fun libraryPageCountsText(counts: LibraryPageCounts): String? {
    val parts = buildList {
        if (counts.source > 0) add("${counts.source} source")
        if (counts.pdf > 0) add("${counts.pdf} PDF")
        if (counts.unknown > 0 && isNotEmpty()) add("${counts.unknown} other")
    }
    val total = counts.source + counts.pdf + counts.unknown
    if (total == 0) return null
    val noun = if (total == 1) "page" else "pages"
    return if (parts.isEmpty()) "$total $noun" else "${parts.joinToString(" + ")} $noun"
}
