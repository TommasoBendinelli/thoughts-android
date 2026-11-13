// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Region
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewFeedbackAnchoring
import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics

/** One page's characters and its size in PDF points. */
internal class FeedbackPageText(val metrics: PageCharMetrics, val width: Float, val height: Float)

/**
 * A card's answer text (protocol/review-card-feedback.md §4.1): the text of each of the card's
 * regions, in list order, joined with "\n". Each UTF-16 unit keeps its 1-based page and glyph box
 * (normalized, top-left origin), or page 0 and no box for an inserted space or line break.
 */
internal class FeedbackAnswerText(val text: String, private val pages: IntArray, private val boxes: List<FloatArray?>) {

    /** The glyph boxes of [start, end), merged into one rectangle per line, in reading order. */
    fun lineRegions(start: Int, end: Int): List<Region> {
        val lines = ArrayList<Region>()
        var page = 0
        var box: FloatArray? = null
        fun flush() {
            box?.let { lines += Region(page, it[0].toDouble(), it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) }
            box = null
        }
        for (index in start.coerceAtLeast(0) until end.coerceAtMost(text.length)) {
            val glyph = boxes[index]
            if (text[index] == '\n') flush()
            if (glyph == null) continue
            val current = box
            if (current != null && (pages[index] != page || !sameLine(current, glyph))) flush()
            val merged = box
            if (merged == null) {
                page = pages[index]
                box = glyph.copyOf()
            } else {
                merged[0] = minOf(merged[0], glyph[0])
                merged[1] = minOf(merged[1], glyph[1])
                merged[2] = maxOf(merged[2], glyph[2])
                merged[3] = maxOf(merged[3], glyph[3])
            }
        }
        flush()
        return lines
    }

    /** A glyph whose vertical centre lies outside the line so far starts another line. */
    private fun sameLine(line: FloatArray, glyph: FloatArray): Boolean {
        val centre = (glyph[1] + glyph[3]) / 2f
        return centre >= line[1] && centre <= line[3]
    }

    /**
     * Up to [length] characters of answer text before and after the characters whose glyph centre
     * lies in [selection] (spec §3 quote.prefix / quote.suffix); empty strings when none does.
     */
    fun context(selection: List<Region>, length: Int = ReviewCardFeedback.CONTEXT_LENGTH): Pair<String, String> {
        var first = -1
        var last = -1
        for (index in text.indices) {
            val glyph = boxes[index] ?: continue
            val x = (glyph[0] + glyph[2]) / 2.0
            val y = (glyph[1] + glyph[3]) / 2.0
            if (selection.any { it.page == pages[index] && x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }) {
                if (first < 0) first = index
                last = index
            }
        }
        if (first < 0) return "" to ""
        var prefixStart = (first - length).coerceAtLeast(0)
        if (prefixStart in 1 until text.length && Character.isLowSurrogate(text[prefixStart])) prefixStart++
        var suffixEnd = (last + 1 + length).coerceAtMost(text.length)
        if (suffixEnd in 1 until text.length && Character.isLowSurrogate(text[suffixEnd])) suffixEnd--
        val afterLast = if (last + 1 < text.length && Character.isLowSurrogate(text[last + 1])) last + 2 else last + 1
        return text.substring(prefixStart, first) to text.substring(afterLast.coerceAtMost(suffixEnd), suffixEnd)
    }

    companion object {
        fun build(regions: List<Region>, pageText: (Int) -> FeedbackPageText?): FeedbackAnswerText {
            val text = StringBuilder()
            val pages = ArrayList<Int>()
            val boxes = ArrayList<FloatArray?>()
            regions.forEachIndexed { index, region ->
                if (index > 0) {
                    text.append('\n')
                    pages += 0
                    boxes += null
                }
                val page = pageText(region.page) ?: return@forEachIndexed
                val glyphs = reviewAnswerGlyphText(page.metrics, page.width, page.height, ReviewAnswerRegion(
                    region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat()))
                text.append(glyphs.text)
                glyphs.boxes.forEach { box ->
                    pages += if (box == null) 0 else region.page
                    boxes += box
                }
            }
            return FeedbackAnswerText(text.toString(), pages.toIntArray(), boxes)
        }
    }
}

/** Where one feedback record is drawn on the displayed PDF (spec §4.2), and how it was found. */
internal data class FeedbackPlacement(
    val feedback: ReviewCardFeedback.Feedback,
    val state: ReviewFeedbackAnchoring.State,
    val regions: List<Region>,
    /** Where the passage starts in the answer text, for ordering the list; null when not found. */
    val position: Int?,
    /**
     * Null for the card's own feedback. For feedback made on another card of the same document
     * whose passage lies inside this card's answer: that card's question ("" when it has none).
     */
    val sharedFrom: String? = null,
) {
    val shared: Boolean get() = sharedFrom != null
}

/** The card under review, as feedback sharing needs it: its id and its document's identities. */
internal data class FeedbackCardIdentity(val cardId: String, val documentIds: Set<String>, val pdfStem: String)

/** The card a feedback record was made on ("home card"), when known locally. */
internal data class FeedbackHomeCard(val question: String, val deleted: Boolean, val documentIds: Set<String>, val pdfStem: String)

/** A record of another card that may be shown on the card under review, with its home card's question. */
internal data class SharedFeedback(val feedback: ReviewCardFeedback.Feedback, val homeQuestion: String)

/** The valid (UUIDv4, lowercase) document identities among [values]. */
internal fun feedbackDocumentIds(vararg values: String?): Set<String> =
    values.mapNotNull { it?.lowercase()?.takeIf(ReviewCardFeedback::validUuid4) }.toSet()

/**
 * Feedback of other cards that belongs to the document of [card] (answer feedback is shared across
 * the cards of a document): non-deleted, made on another card, whose anchor `document_id` is one of
 * the card's document identities. An anchor without a usable document id falls back to its home
 * card's identity (document ids, else the PDF stem). Feedback whose home card is deleted or unknown
 * locally is left out (spec §5 cascade). Whether the passage lies inside the answer is decided by
 * [placeSharedFeedback].
 */
internal fun sharedFeedbackCandidates(
    records: Collection<ReviewCardFeedback.Feedback>,
    card: FeedbackCardIdentity,
    homeCard: (String) -> FeedbackHomeCard?,
): List<SharedFeedback> = records.asSequence()
    .filter { !it.deleted && it.cardId != card.cardId }
    .mapNotNull { feedback ->
        val anchorDocument = feedback.pdfDocumentId.lowercase().takeIf(ReviewCardFeedback::validUuid4)
        if (anchorDocument != null && card.documentIds.isNotEmpty() && anchorDocument !in card.documentIds) return@mapNotNull null
        val home = homeCard(feedback.cardId)?.takeIf { !it.deleted } ?: return@mapNotNull null
        val sameDocument = when {
            anchorDocument != null && card.documentIds.isNotEmpty() -> true
            home.documentIds.isNotEmpty() && card.documentIds.isNotEmpty() -> home.documentIds.any { it in card.documentIds }
            else -> home.pdfStem.isNotEmpty() && home.pdfStem == card.pdfStem
        }
        if (sameDocument) SharedFeedback(feedback, home.question) else null
    }
    .sortedWith(compareBy({ it.feedback.createdAt }, { it.feedback.id }))
    .toList()

/**
 * Where feedback of other cards is drawn on this card's answer, or nothing: on the PDF it was made
 * on, its line rectangles must qualify inside [answerRegions] as a new selection would (§6.2);
 * on another PDF its quote must be found in this card's [answer] text (§4.2). A record that is not
 * inside this answer is left out, never listed as not found.
 */
internal fun placeSharedFeedback(
    records: List<SharedFeedback>,
    displayedPdfSha256: String,
    answer: FeedbackAnswerText?,
    answerRegions: List<Region>,
): List<FeedbackPlacement> = records.mapNotNull { shared ->
    val feedback = shared.feedback
    val samePdf = displayedPdfSha256.isNotEmpty() && feedback.pdfSha256.equals(displayedPdfSha256, ignoreCase = true)
    if (samePdf) {
        if (!feedbackSelectionQualifies(feedback.regions, answerRegions)) return@mapNotNull null
        val start = answer?.let {
            ReviewFeedbackAnchoring.resolve(it.text, feedback.exact, feedback.prefix, feedback.suffix).start
        }
        return@mapNotNull FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.EXACT, feedback.regions, start, shared.homeQuestion)
    }
    val text = answer ?: return@mapNotNull null
    val resolution = ReviewFeedbackAnchoring.resolve(text.text, feedback.exact, feedback.prefix, feedback.suffix)
    val start = resolution.start ?: return@mapNotNull null
    val end = resolution.end ?: return@mapNotNull null
    if (resolution.state != ReviewFeedbackAnchoring.State.RELOCATED) return@mapNotNull null
    val regions = text.lineRegions(start, end).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.RELOCATED, regions, start, shared.homeQuestion)
}

/** Finds each record in the answer: its own regions on the PDF it was made on, else its quote in [answer]. */
internal fun placeFeedback(
    records: List<ReviewCardFeedback.Feedback>,
    displayedPdfSha256: String,
    answer: FeedbackAnswerText?,
): List<FeedbackPlacement> = records.map { feedback ->
    val samePdf = displayedPdfSha256.isNotEmpty() && feedback.pdfSha256.equals(displayedPdfSha256, ignoreCase = true)
    if (samePdf) {
        val start = answer?.let {
            ReviewFeedbackAnchoring.resolve(it.text, feedback.exact, feedback.prefix, feedback.suffix).start
        }
        return@map FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.EXACT, feedback.regions, start)
    }
    val text = answer ?: return@map FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.ORPHANED, emptyList(), null)
    val resolution = ReviewFeedbackAnchoring.resolve(text.text, feedback.exact, feedback.prefix, feedback.suffix)
    val start = resolution.start
    val end = resolution.end
    if (resolution.state != ReviewFeedbackAnchoring.State.RELOCATED || start == null || end == null) {
        FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.ORPHANED, emptyList(), null)
    } else {
        val regions = text.lineRegions(start, end)
        if (regions.isEmpty()) FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.ORPHANED, emptyList(), null)
        else FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.RELOCATED, regions, start)
    }
}

/** The order of the card's feedback list (spec §6.3): by position in the answer, not found last. */
internal fun feedbackListOrder(placements: List<FeedbackPlacement>): List<FeedbackPlacement> =
    placements.sortedWith(compareBy<FeedbackPlacement>(
        { it.state == ReviewFeedbackAnchoring.State.ORPHANED },
        { it.position ?: Int.MAX_VALUE },
        { it.regions.firstOrNull()?.page ?: Int.MAX_VALUE },
        { it.regions.firstOrNull()?.top ?: Double.MAX_VALUE },
        { it.feedback.createdAt },
    ))

/**
 * Whether a text selection may get feedback (spec §6.2): the centre of every selected line
 * rectangle lies inside one of the card's answer regions on its page, each grown by [margin].
 */
internal fun feedbackSelectionQualifies(selection: List<Region>, answer: List<Region>, margin: Double = 0.01): Boolean =
    selection.isNotEmpty() && selection.all { line ->
        val x = (line.left + line.right) / 2.0
        val y = (line.top + line.bottom) / 2.0
        answer.any { area ->
            area.page == line.page && x >= area.left - margin && x <= area.right + margin &&
                y >= area.top - margin && y <= area.bottom + margin
        }
    }
