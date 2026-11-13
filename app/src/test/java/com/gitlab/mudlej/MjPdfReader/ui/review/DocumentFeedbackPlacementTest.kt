// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Region
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewFeedbackAnchoring.State
import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * protocol/document-feedback.md §4: same PDF → the stored regions; another PDF → the quote is
 * searched on the anchor's pages, then up to 3 pages around them; not found → orphaned.
 * Synthetic 200 × 100 pt pages with one line of text each at y = 80 pt.
 */
class DocumentFeedbackPlacementTest {
    private val displayedSha = "b".repeat(64)
    private val otherSha = "a".repeat(64)

    private fun metrics(text: String): PageCharMetrics {
        val values = DoubleArray(text.length * 9)
        var x = 10.0
        text.forEachIndexed { index, character ->
            val base = index * 9
            val width = if (character == ' ') 2.0 else 4.0
            values[base] = x
            values[base + 1] = 76.0
            values[base + 2] = x + width
            values[base + 3] = 84.0
            values[base + 4] = 8.0
            values[base + 6] = character.code.toDouble()
            x += width + 1.0
        }
        return PageCharMetrics.fromRaw(values)!!
    }

    /** Ten pages; page 9 holds the passage, every other page filler. */
    private val pages = (1..10).associateWith { page ->
        FeedbackPageText(metrics(if (page == 9) "the quiet passage here" else "filler page $page"), 200f, 100f)
    }

    private fun record(exact: String, page: Int, sha: String, prefix: String = "", suffix: String = ""): ReviewCardFeedback.Feedback =
        DocumentFeedback.parse(JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("feedback_id", "9d2f4a51-7c0e-4b8a-a6f1-3e5b2c7d8e90")
            addProperty("polarity", "positive")
            addProperty("comment", "")
            add("anchor", JsonObject().apply {
                addProperty("version", 1)
                add("quote", JsonObject().apply {
                    addProperty("exact", exact); addProperty("prefix", prefix); addProperty("suffix", suffix)
                })
                add("pdf", JsonObject().apply {
                    addProperty("document_id", "c1e62ecc-2dfb-4259-97cc-e319b1186848")
                    addProperty("pdf_sha256", sha)
                    add("regions", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("page", page)
                            add("rect", JsonArray().apply { listOf(0.1, 0.15, 0.6, 0.25).forEach(::add) })
                        })
                    })
                })
            })
            addProperty("revision", 1)
            addProperty("created_at", "2026-10-28T08:52:03.512Z")
            addProperty("updated_at", "2026-10-28T08:52:03.512Z")
            addProperty("created_origin_id", "test")
            addProperty("origin_id", "test")
            addProperty("deleted", false)
        })!!

    private val searches = mutableListOf<List<Int>>()

    private fun place(feedback: ReviewCardFeedback.Feedback) = placeDocumentFeedback(feedback, displayedSha, pages.size) { searched ->
        searches += searched
        FeedbackAnswerText.build(wholePages(searched)) { pages[it] }
    }

    @Test fun samePdfDrawsTheStoredRegionsWithoutReadingText() {
        val feedback = record("anything at all", 2, displayedSha.uppercase())
        val placement = place(feedback)
        assertEquals(State.EXACT, placement.state)
        assertEquals(feedback.regions, placement.regions)
        assertEquals(emptyList<List<Int>>(), searches)
    }

    @Test fun anotherPdfFindsTheQuoteOnTheAnchorsPage() {
        val placement = place(record("quiet passage", 9, otherSha))
        assertEquals(State.RELOCATED, placement.state)
        assertEquals(listOf(listOf(9)), searches)
        val line = placement.regions.single()
        assertEquals(9, line.page)
        // "the " is 4 glyphs (3 × 5 pt + a 3 pt space) from x = 10 pt: the passage starts at 28 of 200 pt.
        assertEquals(0.14, line.left, 1e-5)
        assertEquals(0.16, line.top, 1e-5)
        assertEquals(0.24, line.bottom, 1e-5)
    }

    @Test fun anotherPdfWidensTheSearchToThreePagesAround() {
        // The passage moved from page 7 to page 9 in the displayed version.
        val placement = place(record("quiet passage", 7, otherSha))
        assertEquals(State.RELOCATED, placement.state)
        assertEquals(listOf(listOf(7), listOf(4, 5, 6, 7, 8, 9, 10)), searches)
        assertEquals(9, placement.regions.single().page)
    }

    @Test fun aQuoteFurtherAwayOrEditedIsOrphaned() {
        assertEquals(State.ORPHANED, place(record("quiet passage", 2, otherSha)).state)
        assertEquals(listOf(listOf(2), listOf(1, 2, 3, 4, 5)), searches)
        val edited = place(record("loud passage", 9, otherSha))
        assertEquals(State.ORPHANED, edited.state)
        assertTrue(edited.regions.isEmpty())
    }

    @Test fun anAnchorBeyondTheDisplayedPagesSearchesOnlyTheNearbyPagesThatExist() {
        val placement = place(record("quiet passage", 12, otherSha))
        assertEquals(State.RELOCATED, placement.state)
        assertEquals(listOf(listOf(9, 10)), searches) // page 12 does not exist: only the widened search runs
    }

    @Test fun searchPages() {
        assertEquals(listOf(3, 8), documentFeedbackSearchPages(listOf(8, 3, 8), 10, widened = false))
        assertEquals((1..10).toList(), documentFeedbackSearchPages(listOf(3, 8), 10, widened = true))
        assertEquals(listOf(1, 2, 3, 4), documentFeedbackSearchPages(listOf(1), 10, widened = true))
        assertEquals(emptyList<Int>(), documentFeedbackSearchPages(listOf(1), 0, widened = true))
    }

    @Test fun aDrawingIsNeverSearchedFor() {
        val drawing = DocumentFeedback.parse(record("the quiet passage here", 9, otherSha).payload.deepCopy().apply {
            addProperty("polarity", "neutral")
            addProperty("kind", "drawing")
            add("drawing", com.google.gson.JsonParser.parseString("""{"strokes":[{"points":[[0.2,0.3]]}]}"""))
        })!!
        var searched = false
        val elsewhere = placeDocumentFeedback(drawing, displayedSha, 10) { searched = true; null }
        assertEquals(State.ORPHANED, elsewhere.state)
        assertEquals(emptyList<Region>(), elsewhere.regions)
        assertEquals(false, searched)
        // On its own PDF the region names the page.
        val own = placeDocumentFeedback(drawing, otherSha, 10) { searched = true; null }
        assertEquals(State.EXACT, own.state)
        assertEquals(9, own.regions.single().page)
        assertEquals(false, searched)
    }

    @Test fun aTextBoxIsNeverSearchedFor() {
        val text = DocumentFeedback.parse(record("the quiet passage here", 9, otherSha).payload.deepCopy().apply {
            addProperty("polarity", "neutral")
            addProperty("kind", "text")
            addProperty("comment", "the quiet passage here")
            add("text_box", com.google.gson.JsonParser.parseString("""{"rect":[0.1,0.2,0.4,0.25],"font_size":0.015}"""))
        })!!
        var searched = false
        // Its text is on the page too, and still it is not looked for: it is drawn on its own PDF only.
        val elsewhere = placeDocumentFeedback(text, displayedSha, 10) { pages ->
            searched = true
            FeedbackAnswerText.build(wholePages(pages)) { this.pages[it] }
        }
        assertEquals(State.ORPHANED, elsewhere.state)
        assertEquals(emptyList<Region>(), elsewhere.regions)
        assertEquals(false, searched)
        // On its own PDF the region names the page.
        val own = placeDocumentFeedback(text, otherSha, 10) { searched = true; null }
        assertEquals(State.EXACT, own.state)
        assertEquals(9, own.regions.single().page)
        assertEquals(false, searched)
    }

    @Test fun wholePagesCoverEveryCharacter() {
        val text = FeedbackAnswerText.build(wholePages(listOf(8, 9))) { pages[it] }
        assertEquals("filler page 8\nthe quiet passage here", text.text)
        assertEquals(listOf(Region(8, 0.0, 0.0, 1.0, 1.0)), wholePages(listOf(8)))
    }
}
