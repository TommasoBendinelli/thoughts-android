// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewQaPdfExporterTest {

    @Test
    fun `export includes only active cards from the selected deck in source order`() {
        val cards = listOf(
            browserCard("later", "concepts", page = 4, top = 0.2f, ReviewBrowserState.FUTURE),
            browserCard("stale", "concepts", page = 1, top = 0.1f, ReviewBrowserState.STALE),
            browserCard("lower", "concepts", page = 3, top = 0.7f, ReviewBrowserState.REVIEW),
            browserCard("other", "other-deck", page = 1, top = 0.1f, ReviewBrowserState.NEW),
            browserCard("upper", "concepts", page = 1, top = 0.2f, ReviewBrowserState.NEW),
            browserCard("middle", "concepts", page = 2, top = 0.4f, ReviewBrowserState.LEARNING),
        )

        assertEquals(
            listOf("upper", "middle", "lower", "later"),
            reviewQaExportCards(cards, "concepts").map { item -> item.card.id },
        )
    }

    @Test
    fun `export filename states when the source PDF is appended`() {
        assertEquals(
            "concepts.questions-and-answers.pdf",
            reviewQaExportFileName(ReviewQaExportRequest("concepts", appendSourcePdf = false)),
        )
        assertEquals(
            "concepts_with_notes.questions-and-answers.with-source.pdf",
            reviewQaExportFileName(
                ReviewQaExportRequest("concepts:with/notes", appendSourcePdf = true),
            ),
        )
    }

    private fun browserCard(
        id: String,
        pdfStem: String,
        page: Int,
        top: Float,
        state: ReviewBrowserState,
    ) = ReviewBrowserCard(
        card = ReviewCard(
            id = id,
            status = "approved",
            pdfStem = pdfStem,
            pdfSha256 = "a".repeat(64),
            page = page,
            pageVisualSha256 = "b".repeat(64),
            question = "Question $id",
            rect = RectF(0.1f, top, 0.9f, top + 0.1f),
            schedule = null,
            reanchorState = "current",
        ),
        answerText = "Answer $id",
        state = state,
        dueAt = null,
        intervalDays = 0,
        reviewCount = 0,
        lapseCount = 0,
        lastRating = null,
        lastReviewedAt = null,
        createdAt = null,
        updatedAt = null,
        revision = null,
        sourcePath = null,
        pendingSync = false,
        deleteEventId = null,
        history = emptyList(),
    )
}
