// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.hidesPdfHighlight
import com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReadingContext

/** The approved, placeable review cards of the document shown: the PDF itself, or every PDF of a project reading. */
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
        it.card.approved && !it.state.hidesPdfHighlight()
}

/** Where a project reading's PDF starts, as a zero-based page offset; 0 outside a project reading. */
internal fun documentReviewPageOffset(reading: ProjectReadingContext?, pdfStem: String): Int =
    reading?.documents?.firstOrNull { it.filename == "$pdfStem.pdf" }?.reading?.firstPage ?: 0
