// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.content.Context
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import kotlin.math.abs
import kotlin.math.max

internal class ReviewAnswerTextLoader(
    private val context: Context,
    private val pdfDirectory: File = PhoneRenderQueue.defaultCanonicalPdfDirectory(),
) {

    fun populate(
        cards: List<ReviewBrowserCard>,
        canonicalPdfSha256ByFilename: Map<String, String>?,
    ): List<ReviewBrowserCard> {
        if (cards.isEmpty() || canonicalPdfSha256ByFilename == null) return cards
        val answers = HashMap<String, String>()
        val core = PdfiumCore(context)
        cards.groupBy { item ->
            File(pdfDirectory, "${item.card.pdfStem}.pdf")
        }.forEach { (pdf, deckCards) ->
            if (pdf.isFile) {
                val sha = com.gitlab.mudlej.MjPdfReader.data.review.basicPdfSha256(pdf)
                extractDeckAnswers(core, pdf, deckCards.filter { it.card.pdfSha256 == sha }, answers)
            }
        }
        return cards.map { item ->
            item.copy(answerText = answers[item.card.id].orEmpty())
        }
    }

    /**
     * The text inside one card's answer [regions] of [pdf], region by region in reading order
     * (page, then top, then left); empty when the PDF cannot be read.
     */
    fun regionsText(pdf: File, regions: List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion>): String {
        if (!pdf.isFile || regions.isEmpty()) return ""
        val core = PdfiumCore(context)
        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        var document: PdfDocument? = null
        return try {
            document = core.newDocument(descriptor)
            val pageCount = core.getPageCount(document)
            regions.sortedWith(compareBy({ it.page }, { it.rect.top }, { it.rect.left }))
                .groupBy { it.page }.flatMap { (pageNumber, pageRegions) ->
                    val pageIndex = pageNumber - 1
                    if (pageIndex !in 0 until pageCount) return@flatMap emptyList()
                    val metrics = readPageMetrics(core, document, pageIndex) ?: return@flatMap emptyList()
                    val size = core.getPageSizePoint(document, pageIndex)
                    if (size.width <= 0f || size.height <= 0f) return@flatMap emptyList()
                    pageRegions.map { extractReviewAnswerText(metrics, size.width, size.height, it.rect) }
                }.filter(String::isNotBlank).joinToString("\n")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not extract the answer text from ${pdf.name}", error)
            ""
        } finally {
            val openedDocument = document
            if (openedDocument != null) runCatching { core.closeDocument(openedDocument) }
            else runCatching { descriptor.close() }
        }
    }

    /**
     * The answer text of review card feedback (protocol/review-card-feedback.md §4.1): the card's
     * [regions] in list order, with each character's glyph box; null when the PDF cannot be read.
     */
    fun feedbackAnswerText(pdf: File, regions: List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Region>): FeedbackAnswerText? {
        if (!pdf.isFile || regions.isEmpty()) return null
        val core = PdfiumCore(context)
        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        var document: PdfDocument? = null
        return try {
            val opened = core.newDocument(descriptor)
            document = opened
            val pageCount = core.getPageCount(opened)
            val pages = HashMap<Int, FeedbackPageText?>()
            FeedbackAnswerText.build(regions) { pageNumber ->
                pages.getOrPut(pageNumber) {
                    val pageIndex = pageNumber - 1
                    if (pageIndex !in 0 until pageCount) return@getOrPut null
                    val metrics = readPageMetrics(core, opened, pageIndex) ?: return@getOrPut null
                    val size = core.getPageSizePoint(opened, pageIndex)
                    if (size.width <= 0f || size.height <= 0f) null else FeedbackPageText(metrics, size.width, size.height)
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not extract the feedback answer text from ${pdf.name}", error)
            null
        } finally {
            val openedDocument = document
            if (openedDocument != null) runCatching { core.closeDocument(openedDocument) }
            else runCatching { descriptor.close() }
        }
    }

    /**
     * Opens [pdf] once for [block], which gets the page count and each 1-based page's characters
     * (read when first asked for, then kept); null when the PDF cannot be read. For document
     * feedback, whose search domain is whole pages (protocol/document-feedback.md §4).
     */
    fun <T> withPages(pdf: File, block: (pageCount: Int, pageText: (Int) -> FeedbackPageText?) -> T): T? {
        if (!pdf.isFile) return null
        val core = PdfiumCore(context)
        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        var document: PdfDocument? = null
        return try {
            val opened = core.newDocument(descriptor)
            document = opened
            val pageCount = core.getPageCount(opened)
            val pages = HashMap<Int, FeedbackPageText?>()
            block(pageCount) { pageNumber ->
                pages.getOrPut(pageNumber) {
                    val pageIndex = pageNumber - 1
                    if (pageIndex !in 0 until pageCount) return@getOrPut null
                    val metrics = readPageMetrics(core, opened, pageIndex) ?: return@getOrPut null
                    val size = core.getPageSizePoint(opened, pageIndex)
                    if (size.width <= 0f || size.height <= 0f) null else FeedbackPageText(metrics, size.width, size.height)
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read the page text of ${pdf.name}", error)
            null
        } finally {
            val openedDocument = document
            if (openedDocument != null) runCatching { core.closeDocument(openedDocument) }
            else runCatching { descriptor.close() }
        }
    }

    private fun extractDeckAnswers(
        core: PdfiumCore,
        pdf: File,
        cards: List<ReviewBrowserCard>,
        answers: MutableMap<String, String>,
    ) {
        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        var document: PdfDocument? = null
        try {
            document = core.newDocument(descriptor)
            val pageCount = core.getPageCount(document)
            cards.groupBy { it.card.page }.forEach pageLoop@ { (pageNumber, pageCards) ->
                val pageIndex = pageNumber - 1
                if (pageIndex !in 0 until pageCount) return@pageLoop
                val metrics = readPageMetrics(core, document, pageIndex) ?: return@pageLoop
                val size = core.getPageSizePoint(document, pageIndex)
                if (size.width <= 0f || size.height <= 0f) return@pageLoop
                pageCards.forEach { item ->
                    answers[item.card.id] = extractReviewAnswerText(
                        metrics = metrics,
                        pageWidth = size.width,
                        pageHeight = size.height,
                        normalizedRegion = item.card.rect,
                    )
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not extract review answers from ${pdf.name}", error)
        } finally {
            val openedDocument = document
            if (openedDocument != null) {
                runCatching { core.closeDocument(openedDocument) }
            } else {
                runCatching { descriptor.close() }
            }
        }
    }

    private fun readPageMetrics(
        core: PdfiumCore,
        document: PdfDocument,
        pageIndex: Int,
    ): PageCharMetrics? {
        var opened = false
        return try {
            core.openPage(document, pageIndex)
            opened = true
            if (core.openTextPage(document, pageIndex) == 0L) return null
            val charCount = core.textCountChars(document, pageIndex)
            if (charCount <= 0 || charCount > MAX_PAGE_CHARS) return null
            PageCharMetrics.fromRaw(core.textCharMetrics(document, pageIndex, 0, charCount))
        } finally {
            if (opened) core.closePage(document, pageIndex)
        }
    }

    companion object {
        private const val TAG = "ReviewAnswerText"
        private const val MAX_PAGE_CHARS = 20_000
    }
}

internal fun extractReviewAnswerText(
    metrics: PageCharMetrics,
    pageWidth: Float,
    pageHeight: Float,
    normalizedRegion: RectF,
): String = extractReviewAnswerText(
    metrics = metrics,
    pageWidth = pageWidth,
    pageHeight = pageHeight,
    normalizedRegion = ReviewAnswerRegion(
        normalizedRegion.left,
        normalizedRegion.top,
        normalizedRegion.right,
        normalizedRegion.bottom,
    ),
)

internal data class ReviewAnswerRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun extractReviewAnswerText(
    metrics: PageCharMetrics,
    pageWidth: Float,
    pageHeight: Float,
    normalizedRegion: ReviewAnswerRegion,
): String = reviewAnswerGlyphText(metrics, pageWidth, pageHeight, normalizedRegion).text.take(MAX_ANSWER_CHARS)

/**
 * The text of one answer region as [extractReviewAnswerText] gives it, with each UTF-16 unit's
 * glyph box (normalized, top-left origin: left, top, right, bottom), or null for an inserted
 * space or line break. Review card feedback draws passages of it.
 */
internal class GlyphText(val text: String, val boxes: List<FloatArray?>)

internal fun reviewAnswerGlyphText(
    metrics: PageCharMetrics,
    pageWidth: Float,
    pageHeight: Float,
    normalizedRegion: ReviewAnswerRegion,
): GlyphText {
    if (
        metrics.count == 0 || pageWidth <= 0f || pageHeight <= 0f ||
        normalizedRegion.left < 0f || normalizedRegion.top < 0f ||
        normalizedRegion.right > 1f || normalizedRegion.bottom > 1f ||
        normalizedRegion.left >= normalizedRegion.right ||
        normalizedRegion.top >= normalizedRegion.bottom
    ) {
        return GlyphText("", emptyList())
    }
    val regionLeft = normalizedRegion.left * pageWidth
    val regionRight = normalizedRegion.right * pageWidth
    val regionBottom = (1f - normalizedRegion.bottom) * pageHeight
    val regionTop = (1f - normalizedRegion.top) * pageHeight
    val selected = BooleanArray(metrics.count)
    for (index in 0 until metrics.count) {
        val left = minOf(metrics.left[index], metrics.right[index])
        val right = maxOf(metrics.left[index], metrics.right[index])
        val bottom = minOf(metrics.bottom[index], metrics.top[index])
        val top = maxOf(metrics.bottom[index], metrics.top[index])
        if (right <= left || top <= bottom || !isVisibleCodePoint(metrics.codePoint[index])) continue
        val centerX = (left + right) * 0.5f
        val centerY = (bottom + top) * 0.5f
        selected[index] = centerX in regionLeft..regionRight && centerY in regionBottom..regionTop
    }
    val first = selected.indexOfFirst { it }
    val last = selected.indexOfLast { it }
    if (first < 0 || last < first) return GlyphText("", emptyList())

    fun box(index: Int): FloatArray? {
        val left = minOf(metrics.left[index], metrics.right[index])
        val right = maxOf(metrics.left[index], metrics.right[index])
        val bottom = minOf(metrics.bottom[index], metrics.top[index])
        val top = maxOf(metrics.bottom[index], metrics.top[index])
        if (right <= left || top <= bottom) return null
        return floatArrayOf(left / pageWidth, 1f - top / pageHeight, right / pageWidth, 1f - bottom / pageHeight)
    }
    val output = GlyphTextBuilder()
    var previousSelected = -1
    var separatorPending = false
    for (index in first..last) {
        val codePoint = metrics.codePoint[index]
        when {
            selected[index] -> {
                if (previousSelected >= 0 && startsNewLine(metrics, previousSelected, index)) {
                    output.lineBreak()
                } else if (separatorPending) {
                    output.space()
                }
                output.append(codePoint, box(index))
                previousSelected = index
                separatorPending = false
            }
            codePoint == '\n'.code || codePoint == '\r'.code -> {
                if (previousSelected >= 0) output.lineBreak()
                separatorPending = false
            }
            codePoint == 0xFFFE -> Unit
            Character.isWhitespace(codePoint) -> separatorPending = previousSelected >= 0
            isCombiningMark(codePoint) && previousSelected >= 0 ->
                output.append(codePoint, box(index) ?: box(previousSelected))
            isVisibleCodePoint(codePoint) -> separatorPending = previousSelected >= 0
        }
    }
    return output.normalized()
}

/** Text with a glyph box per UTF-16 unit, normalized as the answer text always was. */
private class GlyphTextBuilder {
    private val text = StringBuilder()
    private val boxes = ArrayList<FloatArray?>()

    fun append(codePoint: Int, box: FloatArray?) {
        val units = Character.toChars(codePoint)
        units.forEach { unit ->
            text.append(unit)
            boxes += box
        }
    }

    fun space() {
        if (text.isNotEmpty() && !text.last().isWhitespace()) {
            text.append(' ')
            boxes += null
        }
    }

    fun lineBreak() {
        while (text.isNotEmpty() && text.last() == ' ') {
            text.deleteCharAt(text.lastIndex)
            boxes.removeAt(boxes.lastIndex)
        }
        if (text.isNotEmpty() && text.last() != '\n') {
            text.append('\n')
            boxes += null
        }
    }

    /**
     * Each line with its runs of inline whitespace collapsed to one space and trimmed, empty lines
     * dropped: `lineSequence().map { replace(INLINE_WHITESPACE, " ").trim() }.filter(isNotEmpty)`.
     */
    fun normalized(): GlyphText {
        val outText = StringBuilder()
        val outBoxes = ArrayList<FloatArray?>()
        var lineStart = 0
        val full = text.toString()
        while (lineStart <= full.length) {
            val lineEnd = full.indexOf('\n', lineStart).let { if (it < 0) full.length else it }
            val lineText = StringBuilder()
            val lineBoxes = ArrayList<FloatArray?>()
            var index = lineStart
            while (index < lineEnd) {
                if (full[index] in INLINE_WHITESPACE_CHARS) {
                    lineText.append(' ')
                    lineBoxes += null
                    while (index < lineEnd && full[index] in INLINE_WHITESPACE_CHARS) index++
                } else {
                    lineText.append(full[index])
                    lineBoxes += boxes[index]
                    index++
                }
            }
            var from = 0
            var to = lineText.length
            while (from < to && lineText[from].isWhitespace()) from++
            while (to > from && lineText[to - 1].isWhitespace()) to--
            if (to > from) {
                if (outText.isNotEmpty()) {
                    outText.append('\n')
                    outBoxes += null
                }
                outText.append(lineText, from, to)
                outBoxes.addAll(lineBoxes.subList(from, to))
            }
            if (lineEnd >= full.length) break
            lineStart = lineEnd + 1
        }
        return GlyphText(outText.toString(), outBoxes)
    }
}

private fun startsNewLine(metrics: PageCharMetrics, previous: Int, current: Int): Boolean {
    val previousBottom = minOf(metrics.bottom[previous], metrics.top[previous])
    val previousTop = maxOf(metrics.bottom[previous], metrics.top[previous])
    val currentBottom = minOf(metrics.bottom[current], metrics.top[current])
    val currentTop = maxOf(metrics.bottom[current], metrics.top[current])
    val previousHeight = previousTop - previousBottom
    val currentHeight = currentTop - currentBottom
    val verticalShift = abs(
        (previousBottom + previousTop) * 0.5f - (currentBottom + currentTop) * 0.5f,
    )
    val previousRight = maxOf(metrics.left[previous], metrics.right[previous])
    val currentLeft = minOf(metrics.left[current], metrics.right[current])
    return verticalShift > LINE_SHIFT_FACTOR * max(previousHeight, currentHeight) &&
        currentLeft < previousRight
}

private fun appendSpace(output: StringBuilder) {
    if (output.isNotEmpty() && !output.last().isWhitespace()) output.append(' ')
}

private fun appendLineBreak(output: StringBuilder) {
    while (output.isNotEmpty() && output.last() == ' ') output.deleteCharAt(output.lastIndex)
    if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
}

private fun isVisibleCodePoint(codePoint: Int): Boolean =
    codePoint > 0 && codePoint != 0xFFFE && !Character.isISOControl(codePoint) &&
        !Character.isWhitespace(codePoint) && !isCombiningMark(codePoint)

private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    -> true
    else -> false
}

private const val LINE_SHIFT_FACTOR = 0.55f
private const val MAX_ANSWER_CHARS = 4_000
/** Tab, vertical tab, form feed and space: inline whitespace, collapsed to one space. */
private val INLINE_WHITESPACE_CHARS = charArrayOf('\t', '\u000B', '\u000C', ' ')
