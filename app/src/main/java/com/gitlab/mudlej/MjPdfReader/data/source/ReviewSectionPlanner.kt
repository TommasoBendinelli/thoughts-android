// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.content.Context
import android.os.ParcelFileDescriptor
import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.util.UUID

/** The source edit marking a section's body for a new card's ID (ReviewStore.createManualCard). */
class SectionPlacement internal constructor(internal val edit: (String) -> LocalReviewSources.Edit)

/** What a selection of a heading gives: no section, a section that cannot be a card (and why), or its card's plan. */
internal sealed class SectionOutcome {
    object NotHeading : SectionOutcome()
    data class Refused(val message: String) : SectionOutcome()
    data class Found(val plan: SectionCardPlan) : SectionOutcome()
}

/**
 * A section card ready to save: the areas its body covers (1-based pages of its PDF), their text,
 * and for a document built from source the body's lines and the edit marking them for a card ID.
 */
internal data class SectionCardPlan(
    val name: String,
    val title: String,
    val firstPage: Int,
    val lastPage: Int,
    val sourceFile: String?,
    val firstLine: Int,
    val lastLine: Int,
    val wrappedCards: Int,
    val areas: List<SectionArea>,
    val text: String,
    val sourceAvailable: Boolean,
    val placement: SectionPlacement?,
)

/**
 * Finds the section whose heading a selection shows (as the desktop's planReviewSectionCard): in
 * a note built from source, the heading's source line gives the section in the source (marked as
 * one passage that may hold other cards whole) and the PDF shows its body from below the heading
 * to the next section's heading; in a PDF without source, its outline or its headings do.
 */
internal class ReviewSectionPlanner(private val context: Context) {
    private val sources = OfflineSourceBundleStore(context)

    fun plan(file: File, filename: String, hash: String, heading: SectionArea): SectionOutcome {
        if (!file.isFile || com.gitlab.mudlej.MjPdfReader.data.sync.verifiedFileCache.sha256(file) != hash)
            return SectionOutcome.Refused("The displayed PDF is unavailable or changed.")
        val identity = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().forFile(file)
        val metadata = sources.librarySourceMetadata(filename)
        val sourceAvailable = if (identity?.get("kind")?.asString == "unlinked") false
            else reviewSourceAvailability(metadata?.pdfs.orEmpty(), filename, hash, listOf(heading.page))
        val core = PdfiumCore(context)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val document = try { core.newDocument(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
        try {
            val raw = HashMap<Int, SectionPage?>()
            val chars = HashMap<Int, Pair<PageCharMetrics, Float>>()
            fun rawPage(number: Int): SectionPage? = raw.getOrPut(number) { readPage(core, document, number, chars) }
            val pageCount = core.getPageCount(document)
            val layout = SectionLayout(pageCount) { number ->
                rawPage(number)?.let { page -> ReviewSections.withoutFurniture(page, listOf(number - 1, number + 1).mapNotNull { if (it in 1..pageCount) rawPage(it) else null }) }
            }
            return when (sourceAvailable) {
                false -> withoutSource(core, document, layout, heading)
                // This phone cannot tell yet whether the document has source.
                null -> if (ReviewSections.pdfSection(layout, outline(core, document), heading, identifyOnly = true).isHeading)
                    SectionOutcome.Refused("Sync this document, then make the card of its section.") else SectionOutcome.NotHeading
                true -> fromSource(layout, filename, hash, heading, chars,
                    metadata?.pdfs?.get(filename)?.segments?.firstOrNull { heading.page >= it.startPage && heading.page < it.startPage + it.pageCount }
                        ?.let { it.startPage + it.pageCount - 1 } ?: pageCount)
            }
        } finally {
            core.closeDocument(document)
        }
    }

    private fun withoutSource(core: PdfiumCore, document: PdfDocument, layout: SectionLayout, heading: SectionArea): SectionOutcome {
        val section = ReviewSections.pdfSection(layout, outline(core, document), heading)
        if (!section.isHeading) return SectionOutcome.NotHeading
        section.error?.let { return SectionOutcome.Refused(it) }
        return SectionOutcome.Found(SectionCardPlan(
            name = ReviewSections.name(ReviewSections.kindOfLevel(section.level), section.number, section.title), title = section.title,
            firstPage = section.areas.minOf { it.page }, lastPage = section.areas.maxOf { it.page },
            sourceFile = null, firstLine = 0, lastLine = 0, wrappedCards = 0,
            areas = section.areas, text = section.text, sourceAvailable = false, placement = null,
        ))
    }

    private fun fromSource(layout: SectionLayout, filename: String, hash: String, heading: SectionArea,
                           chars: Map<Int, Pair<PageCharMetrics, Float>>, lastPage: Int): SectionOutcome {
        val page = layout.page(heading.page) ?: return SectionOutcome.NotHeading
        val (metrics, height) = chars[heading.page] ?: return SectionOutcome.NotHeading
        // The heading's characters, and the source lines they were built from.
        val inside = (0 until metrics.count).filter { i ->
            val x = (metrics.left[i] + metrics.right[i]) / 2 / page.width
            val y = 1 - (metrics.top[i] + metrics.bottom[i]) / 2 / height
            !Character.isWhitespace(metrics.codePoint[i]) && x in heading.left..heading.right && y in heading.top..heading.bottom
        }
        if (inside.isEmpty()) return SectionOutcome.NotHeading
        val text = buildString { inside.forEach { appendCodePoint(metrics.codePoint[it]) } }
        val samples = listOf(inside.first(), inside[inside.size / 2], inside.last()).distinct()
        val resolved = samples.mapNotNull { i ->
            sources.resolve(SourceSelection(filename, hash, heading.page,
                (metrics.left[i] + metrics.right[i]) / 2, height - (metrics.top[i] + metrics.bottom[i]) / 2, text))
        }.filter { it.navigationBaseContents != null }
        val snapshot = resolved.firstOrNull() ?: return SectionOutcome.NotHeading
        if (resolved.any { it.sourceFile != snapshot.sourceFile }) return SectionOutcome.NotHeading
        val path = snapshot.sourceFile
        val built = checkNotNull(snapshot.navigationBaseContents)
        val builtLines = resolved.map { it.sourceLine }
        // The build's lines name current lines when only card markers changed since.
        val lineMap = ReviewSourceMarkup.lineMapIgnoringMarkers(built, snapshot.contents)
        if (lineMap == null) {
            val before = ReviewSections.sourceSection(path, built, builtLines)
            return if (before.isHeading) SectionOutcome.Refused("${path.substringAfterLast('/')} changed since this PDF was built. Sync, then make the card.")
                else SectionOutcome.NotHeading
        }
        val section = ReviewSections.sourceSection(path, snapshot.contents, builtLines.mapNotNull { lineMap.getOrNull(it - 1) })
        if (!section.isHeading) return SectionOutcome.NotHeading
        section.error?.let { return SectionOutcome.Refused(it) }
        val contents = snapshot.contents
        val base = snapshot.baseContents ?: contents
        // The marking must work before the person writes a question.
        runCatching { ReviewSourceMarkup.placeSection(path, contents, UUID.randomUUID().toString(), section.firstLine, section.lastLine) }
            .onFailure { return SectionOutcome.Refused(it.message?.takeIf(String::isNotBlank) ?: "The section's source could not be marked.") }
        val (areas, body) = ReviewSections.areasToNextHeading(layout, heading, section.nextTitles, lastPage)
        if (areas.isEmpty()) return SectionOutcome.Refused("The section's text was not found in this PDF. Sync, then try again.")
        val number = ReviewSections.headingNumber(page.lines.filter { line ->
            line.centreY / page.height in heading.top..heading.bottom
        }.joinToString(" ") { it.text })
        return SectionOutcome.Found(SectionCardPlan(
            name = ReviewSections.name(section.kind, if (number.valid) number.text else "", section.title), title = section.title,
            firstPage = areas.minOf { it.page }, lastPage = areas.maxOf { it.page },
            sourceFile = path, firstLine = section.firstLine, lastLine = section.lastLine,
            wrappedCards = ReviewSections.wrappedCards(path, contents, section.firstLine, section.lastLine),
            areas = areas, text = body, sourceAvailable = true,
            placement = SectionPlacement { id -> ReviewSourceMarkup.placeSection(path, contents, id, section.firstLine, section.lastLine).copy(base = base) },
        ))
    }

    private fun outline(core: PdfiumCore, document: PdfDocument): List<SectionOutlineEntry> {
        val entries = mutableListOf<SectionOutlineEntry>()
        fun flatten(items: List<PdfDocument.Bookmark>, depth: Int) {
            for (item in items) {
                entries += SectionOutlineEntry(item.title.orEmpty().trim(), depth, item.pageIdx.toInt() + 1)
                if (item.hasChildren()) flatten(item.children, depth + 1)
            }
        }
        runCatching { flatten(core.getTableOfContents(document), 1) }
        return entries
    }

    private fun readPage(core: PdfiumCore, document: PdfDocument, number: Int, chars: HashMap<Int, Pair<PageCharMetrics, Float>>): SectionPage? {
        core.openPage(document, number - 1)
        try {
            core.openTextPage(document, number - 1)
            val width = core.getPageWidthPoint(document, number - 1).toFloat()
            val height = core.getPageHeightPoint(document, number - 1).toFloat()
            val count = core.textCountChars(document, number - 1)
            if (count !in 1..100_000 || width <= 0 || height <= 0) return SectionPage(width, height, 1, emptyList())
            val metrics = PageCharMetrics.fromRaw(core.textCharMetrics(document, number - 1, 0, count)) ?: return null
            chars[number] = metrics to height
            return ReviewSections.pageFromChars(width, height, (0 until metrics.count).map { i ->
                ReviewSections.PageChar(metrics.left[i], height - metrics.top[i], metrics.right[i], height - metrics.bottom[i],
                    metrics.codePoint[i], metrics.fontSize[i])
            })
        } finally {
            core.closePage(document, number - 1)
        }
    }
}
