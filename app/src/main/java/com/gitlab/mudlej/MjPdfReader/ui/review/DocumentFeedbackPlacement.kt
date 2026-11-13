// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Region
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewFeedbackAnchoring

/** How far around the anchor's pages a relocated quote is searched (protocol/document-feedback.md §4). */
internal const val DOCUMENT_FEEDBACK_PAGE_WINDOW = 3

/**
 * The pages of the displayed PDF searched for a document feedback quote (spec §4.2), 1-based and
 * ascending: the anchor's own pages, or, when [widened], those plus up to
 * [DOCUMENT_FEEDBACK_PAGE_WINDOW] pages before and after each. Pages outside [pageCount] are left out.
 */
internal fun documentFeedbackSearchPages(anchorPages: Collection<Int>, pageCount: Int, widened: Boolean): List<Int> {
    val window = if (widened) DOCUMENT_FEEDBACK_PAGE_WINDOW else 0
    return anchorPages.flatMap { page -> (page - window)..(page + window) }
        .filter { it in 1..pageCount }.distinct().sorted()
}

/**
 * Where one document feedback record is drawn on the displayed PDF (spec §4): its own regions
 * when it was made on these PDF bytes ([displayedPdfSha256]); otherwise its quote searched (card
 * feedback's skeleton match, hyphen retry and context scoring) in the text of its anchor's pages,
 * then of the pages around them. [pageText] gives the text of whole pages, in order, joined with
 * line breaks ([FeedbackAnswerText.build] over full-page regions); it is only called when a search
 * is needed. Not found is [ReviewFeedbackAnchoring.State.ORPHANED], with no regions, and so is a
 * drawing or a text box on another PDF: it has no passage to find (spec §10, §11).
 */
internal fun placeDocumentFeedback(
    feedback: ReviewCardFeedback.Feedback,
    displayedPdfSha256: String,
    pageCount: Int,
    pageText: (List<Int>) -> FeedbackAnswerText?,
): FeedbackPlacement {
    if (displayedPdfSha256.isNotEmpty() && feedback.pdfSha256.equals(displayedPdfSha256, ignoreCase = true)) {
        return FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.EXACT, feedback.regions, null)
    }
    if (feedback.isPageMark) return FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.ORPHANED, emptyList(), null)
    val anchorPages = feedback.regions.map { it.page }.toSet()
    var searched = emptyList<Int>()
    for (widened in listOf(false, true)) {
        val pages = documentFeedbackSearchPages(anchorPages, pageCount, widened)
        if (pages.isEmpty() || pages == searched) continue
        searched = pages
        val text = pageText(pages) ?: continue
        val resolution = ReviewFeedbackAnchoring.resolve(text.text, feedback.exact, feedback.prefix, feedback.suffix)
        val start = resolution.start
        val end = resolution.end
        if (resolution.state != ReviewFeedbackAnchoring.State.RELOCATED || start == null || end == null) continue
        val regions = text.lineRegions(start, end)
        if (regions.isNotEmpty()) return FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.RELOCATED, regions, start)
    }
    return FeedbackPlacement(feedback, ReviewFeedbackAnchoring.State.ORPHANED, emptyList(), null)
}

/** Full-page regions of [pages], for building the text of whole pages with [FeedbackAnswerText.build]. */
internal fun wholePages(pages: List<Int>): List<Region> = pages.map { Region(it, 0.0, 0.0, 1.0, 1.0) }
