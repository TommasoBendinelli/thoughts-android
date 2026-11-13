// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundlePdf
import com.gitlab.mudlej.MjPdfReader.data.source.SourceOwner
import com.google.gson.JsonArray
import java.io.File

internal data class ReviewBrowserProject(
    val id: String,
    val name: String,
    val filenames: Set<String>,
) {
    fun contains(card: ReviewBrowserCard): Boolean = "${card.card.pdfStem}.pdf" in filenames
}

/** Resolve logical documents inside a combined PDF, using source identity for stale cards. */
internal class ReviewDocumentNames(
    private val projects: JsonArray,
    private val pdfs: Map<String, OfflineBundlePdf>,
    private val displayNames: Map<String, String>,
) {
    private val documents = projects.flatMap { project ->
        project.asJsonObject.getAsJsonArray("documents")?.map { it.asJsonObject }.orEmpty()
    }.groupBy { it.get("filename")?.asString }
    private val projectNames = buildMap<String, MutableList<String>> {
        projects.forEach { value ->
            val project = value.asJsonObject
            val name = project.get("name")?.asString?.takeIf(String::isNotBlank)
                ?: project.get("id")?.asString?.takeIf(String::isNotBlank) ?: return@forEach
            val filenames = listOfNotNull(project.get("target")?.asString) +
                project.getAsJsonArray("documents")?.mapNotNull {
                    it.asJsonObject.get("filename")?.asString
                }.orEmpty()
            filenames.distinct().forEach { getOrPut(it) { mutableListOf() }.add(name) }
        }
    }.mapValues { (_, names) -> names.distinct().joinToString("\n") }

    fun projectName(card: ReviewCard): String = projectNames["${card.pdfStem}.pdf"].orEmpty()

    fun projectsFor(filenames: Set<String>): List<ReviewBrowserProject> = projects.mapNotNull { value ->
        val project = value.asJsonObject
        val id = project.get("id")?.asString?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val name = project.get("name")?.asString?.takeIf(String::isNotBlank) ?: id
        val members = (listOfNotNull(project.get("target")?.asString) +
            project.getAsJsonArray("documents")?.mapNotNull {
                it.asJsonObject.get("filename")?.asString
            }.orEmpty()).toSet()
        if (members.none { it in filenames }) null else ReviewBrowserProject(id, name, members)
    }.distinctBy { it.id }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun name(card: ReviewCard): String {
        val filename = "${card.pdfStem}.pdf"
        val catalogue = documents[filename].orEmpty()
        val pdf = pdfs[filename]
        val sources = card.sourceFiles.toSet()
        val segments = pdf?.segments.orEmpty()
        val sourceSegments = segments.filter { segment ->
            sources.any { source -> source == segment.primarySourceFile ||
                segment.inputs.any { it.sourceFile == source } }
        }
        val directDocuments = catalogue.filter { it.get("source_file")?.asString in sources }
        val sourceNames = directDocuments.mapNotNull { it.get("name")?.asString?.takeIf(String::isNotBlank) } +
            sourceSegments.map { segment ->
                catalogue.firstOrNull { it.get("source_file")?.asString == segment.primarySourceFile }
                    ?.get("name")?.asString?.takeIf(String::isNotBlank)
                    ?: segment.primarySourceFile?.let(::sourceName) ?: "Document ${segment.startPage}"
            }
        if (sourceNames.isNotEmpty()) return sourceNames.distinct().joinToString("\n")

        // Page boundaries are meaningful only for the exact PDF version that owns the card.
        val pageNames = catalogue.filter { doc ->
            val start = doc.get("start_page")?.asInt ?: 1
            val count = doc.get("page_count")?.asInt ?: 0
            doc.get("sha256")?.asString.equals(card.pdfSha256, ignoreCase = true) &&
                count > 0 && card.page.toLong() in start.toLong() until start.toLong() + count
        }.mapNotNull { it.get("name")?.asString?.takeIf(String::isNotBlank) }
        if (pageNames.isNotEmpty()) return pageNames.distinct().joinToString("\n")
        if (pdf?.pdfSha256.equals(card.pdfSha256, ignoreCase = true)) {
            segments.singleOrNull { card.page >= it.startPage &&
                card.page.toLong() < it.startPage.toLong() + it.pageCount }
                ?.let { return it.primarySourceFile?.let(::sourceName) ?: "Document ${it.startPage}" }
        }
        // Removed source documents still identify historical cards even without a current segment.
        // A default-folder document lives anywhere in its folder, not under a source/ directory.
        sources.filter { "/source/" in it || SourceOwner.of(it)?.let(SourceOwner::isDefaultFolder) == true }.takeIf { it.isNotEmpty() }
            ?.let { return it.map(::sourceName).distinct().joinToString("\n") }
        return displayNames[filename]?.takeIf(String::isNotBlank) ?: filename
    }

    private fun sourceName(path: String): String = File(path).nameWithoutExtension.replace('_', ' ')
}
