// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.google.gson.JsonArray

internal data class OverviewCommentMetadataLocation(
    val stem: String,
    val page: Int?,
    val projects: List<String>,
    val documentName: String,
    val projectPage: Int? = null,
    val requiresDocumentMapping: Boolean = false,
)

internal fun overviewCommentMetadataLocations(
    comment: OverviewComment,
    projects: JsonArray,
): List<OverviewCommentMetadataLocation> {
    val locations = comment.logicalLocations.map {
        it.pdfFilename.removeSuffix(".pdf") to it.page.takeIf { page -> page > 0 && it.status != "unavailable" }
    }.distinct().ifEmpty {
        listOf(comment.documentStem to comment.pageIndex.takeIf { it >= 0 }?.plus(1))
    }
    return locations.map { (stem, page) ->
        val filename = "$stem.pdf"
        var documentName = ""
        var documentPage = page
        var projectPage: Int? = null
        var requiresDocumentMapping = false
        val names = projects.mapNotNull { value ->
            val project = value.asJsonObject
            val documents = project.getAsJsonArray("documents") ?: JsonArray()
            val document = documents.firstOrNull {
                it.asJsonObject.get("filename")?.asString.equals(filename, ignoreCase = true)
            }?.asJsonObject
            if (document == null && !project.get("target")?.asString.equals(filename, ignoreCase = true)) {
                return@mapNotNull null
            }
            if (document == null && project.get("target")?.asString == filename) {
                requiresDocumentMapping = true
                projectPage = page
                documentPage = null
            }
            if (documentName.isBlank()) documentName = document?.get("name")?.asString.orEmpty()
            val composition = project.getAsJsonObject("composition")
            val hash = comment.logicalLocations.firstOrNull {
                it.pdfFilename == filename && it.page == page && it.status != "unavailable"
            }?.pdfSha256
            if (page != null && hash != null && composition != null) {
                val combined = composition.get("filename")?.asString == filename &&
                    composition.get("sha256")?.asString == hash
                val segments = composition.getAsJsonArray("segments") ?: JsonArray()
                val matches = segments.map { it.asJsonObject }.filter { segment ->
                    val start = segment.get("start_page")?.asInt ?: 0
                    val count = segment.get("page_count")?.asInt ?: 0
                    start > 0 && count > 0 && if (combined) page >= start && page - start < count
                    else segment.get("filename")?.asString == filename && segment.get("sha256")?.asString == hash && page <= count
                }
                matches.singleOrNull()?.let { segment ->
                    val start = segment.get("start_page").asInt
                    documentName = segment.get("name")?.asString.orEmpty()
                    documentPage = if (combined) page - start + 1 else page
                    projectPage = if (combined) page else start + page - 1
                }
            }
            project.get("name")?.asString?.takeIf(String::isNotBlank)
                ?: project.get("id")?.asString
        }.distinct()
        OverviewCommentMetadataLocation(stem, documentPage, names, documentName, projectPage, requiresDocumentMapping)
    }
}

/** "2–5, 8" for pages 2, 3, 4, 5 and 8. */
internal fun overviewPageRanges(pages: List<Int>): String {
    val sorted = pages.distinct().sorted()
    val ranges = mutableListOf<IntRange>()
    for (page in sorted) {
        val last = ranges.lastOrNull()
        if (last != null && page == last.last + 1) ranges[ranges.lastIndex] = last.first..page else ranges += page..page
    }
    return ranges.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
}
