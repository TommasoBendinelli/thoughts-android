// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An HTML card not linked yet, reviewed in the PDF view: no mask, the "Placed at next Sync" notice. */
class HtmlReviewNoticeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
    private val pdf = "a".repeat(64)

    private fun store() = ReviewStore(null, temporary.newFolder(), temporary.newFolder(), temporary.newFolder(), temporary.newFolder())

    @Test fun unlinkedHtmlCardGetsTheNoticeOnlyInThePdfView() {
        val store = store()
        val html = store.createHtmlCard("manual", pdf, "How many tabs?", JsonParser.parseString(
            """{"created_in":"html","html_edition_sha256":"${"e".repeat(64)}","text_quote":{"exact":"four main tabs","prefix":"","suffix":""}}""")
            .asJsonObject, "four main tabs", createdAt = now, documentId = documentId).getOrThrow()
        assertTrue(reviewNeedsHtmlViewNotice(html, htmlShown = false))
        assertFalse(reviewNeedsHtmlViewNotice(html, htmlShown = true))
        // It opens (no "stale geometry") although it has no PDF areas.
        assertEquals(ReviewOpenOutcome.SHOW, reviewOpenOutcome(pdf, 3, pdf,
            html.rects.isNotEmpty() || (html.htmlPending && !html.hasPdfPlacement), pdf, false, false, false))
        assertTrue(html.masksByPage.isEmpty())

        val pdfCard = store.createManualCard("manual", pdf, 1, RectF().apply { left = .1f; top = .1f; right = .9f; bottom = .3f },
            "Ordinary?", createdAt = now, sourceAvailable = false, documentId = documentId).getOrThrow()
        assertFalse(reviewNeedsHtmlViewNotice(pdfCard, htmlShown = false))
        assertEquals(listOf(0), pdfCard.masksByPage.keys.toList())
    }
}
