// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.hidesPdfHighlight
import com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReadingContext

/**
 * The approved, placeable review cards of the document shown: the PDF itself, or every PDF of a
 * project reading. A retired card stays on the PDF version it was retired on, so the PDF shown
 * neither lists nor draws it, even when that version is the one shown ([retiredDocumentReviewCards]).
 */
internal fun documentReviewCards(
    cards: List<ReviewBrowserCard>,
    filename: String?,
    reading: ProjectReadingContext?,
    pdfSha256: String,
): List<ReviewBrowserCard> = cards.filter {
    // A card inside an HTML attachment page follows its document, whatever PDF version it was made on.
    val sameVersion = { sha: String -> it.card.isHtmlAttachmentCard || it.card.pdfSha256.equals(sha, ignoreCase = true) }
    (if (reading == null) it.card.pdfStem + ".pdf" == filename && sameVersion(pdfSha256)
     else reading.documents.any { child -> child.filename == it.card.pdfStem + ".pdf" && sameVersion(child.reading.pdfSha256) }) &&
        it.card.approved && !it.card.retired && !it.state.hidesPdfHighlight()
}

/**
 * The document's retired cards, on whatever PDF version they were retired on, most recently
 * retired first: the Review Cards sheet lists them apart so they stay findable. The phone shows
 * only the current version, so they are listed without a place.
 */
internal fun retiredDocumentReviewCards(
    cards: List<ReviewBrowserCard>,
    filename: String?,
    reading: ProjectReadingContext?,
): List<ReviewBrowserCard> {
    val filenames = reading?.documents?.map { it.filename }?.toSet() ?: setOfNotNull(filename)
    return cards.filter {
        it.card.pdfStem + ".pdf" in filenames && it.card.approved && it.card.retired && it.state != ReviewBrowserState.DELETED
    }.sortedByDescending { it.card.retiredAt }
}

/** Where a project reading's PDF starts, as a zero-based page offset; 0 outside a project reading. */
internal fun documentReviewPageOffset(reading: ProjectReadingContext?, pdfStem: String): Int =
    reading?.documents?.firstOrNull { it.filename == "$pdfStem.pdf" }?.reading?.firstPage ?: 0
