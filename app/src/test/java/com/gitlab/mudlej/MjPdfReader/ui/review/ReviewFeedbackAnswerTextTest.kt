// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Region
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewFeedbackAnchoring.State
import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The answer text of a card (spec §4.1) on a synthetic 100 × 100 pt page, where the passage is
 * drawn (§4.2 step 6), the quote's context (§3) and which selections qualify (§6.2, C6).
 * Glyphs are 4 pt wide on lines at y = 80 ("first line"), 60 ("second line text") and 20 ("outside").
 */
class ReviewFeedbackAnswerTextTest {
    private data class Glyph(val codePoint: Int, val left: Float, val bottom: Float, val right: Float, val top: Float)

    private fun line(text: String, y: Float): List<Glyph> {
        var x = 10f
        return text.map { character ->
            if (character == ' ') Glyph(' '.code, x, y - 4f, x + 2f, y + 4f).also { x += 3f }
            else Glyph(character.code, x, y - 4f, x + 4f, y + 4f).also { x += 5f }
        }
    }

    private fun newline() = listOf(Glyph('\n'.code, 0f, 0f, 0f, 0f))

    private fun metrics(glyphs: List<Glyph>): PageCharMetrics {
        val values = DoubleArray(glyphs.size * 9)
        glyphs.forEachIndexed { index, glyph ->
            val base = index * 9
            values[base] = glyph.left.toDouble()
            values[base + 1] = glyph.bottom.toDouble()
            values[base + 2] = glyph.right.toDouble()
            values[base + 3] = glyph.top.toDouble()
            values[base + 4] = 8.0
            values[base + 6] = glyph.codePoint.toDouble()
        }
        return PageCharMetrics.fromRaw(values)!!
    }

    private val page = FeedbackPageText(
        metrics(line("first line", 80f) + newline() + line("second line text", 60f) + newline() + line("outside", 20f)),
        100f, 100f,
    )
    /** The card's answer: the two upper lines (y = 80 and 60 pt are 0.2 and 0.4 from the top). */
    private val answerRegions = listOf(Region(1, 0.0, 0.1, 1.0, 0.5))
    private val answer = FeedbackAnswerText.build(answerRegions) { if (it == 1) page else null }

    @Test fun answerTextIsTheRegionsTextWithLineBreaks() {
        assertEquals("first line\nsecond line text", answer.text)
        val twoRegions = FeedbackAnswerText.build(listOf(Region(1, 0.0, 0.3, 1.0, 0.5), Region(1, 0.0, 0.1, 1.0, 0.3))) { page }
        // List order, not reading order.
        assertEquals("second line text\nfirst line", twoRegions.text)
    }

    @Test fun aRangeIsDrawnAsOneRectanglePerLine() {
        val start = answer.text.indexOf("line\nsecond")
        val end = start + "line\nsecond".length
        val lines = answer.lineRegions(start, end)
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals(1, first.page)
        // "line" starts at x = 38 pt on the first line.
        assertTrue(first.toString(), abs(first.top - 0.16) < 1e-4 && abs(first.bottom - 0.24) < 1e-4)
        assertTrue(first.toString(), first.right > first.left && first.left > 0.3)
        assertTrue(lines[1].toString(), abs(lines[1].top - 0.36) < 1e-4 && abs(lines[1].left - 0.10) < 1e-4)
    }

    @Test fun feedbackOfAnotherVersionIsListedApartAndAnUnknownVersionIsRelocated() {
        fun feedback(exact: String) = ReviewCardFeedback.parse(JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("feedback_id", "5b0f6c1e-2a47-4d1b-9c35-7e0a4f2b9d13")
            addProperty("card_id", "card")
            addProperty("polarity", "positive")
            addProperty("comment", "")
            add("anchor", JsonObject().apply {
                addProperty("version", 1)
                add("quote", JsonObject().apply { addProperty("exact", exact); addProperty("prefix", ""); addProperty("suffix", "") })
                add("pdf", JsonObject().apply {
                    addProperty("document_id", "c1e62ecc-2dfb-4259-97cc-e319b1186848")
                    addProperty("pdf_sha256", "a".repeat(64))
                    add("regions", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("page", 1)
                            add("rect", JsonArray().apply { listOf(0.1, 0.1, 0.2, 0.2).forEach(::add) })
                        })
                    })
                })
            })
            addProperty("revision", 1)
            addProperty("created_at", "2026-10-20T10:00:00.000Z")
            addProperty("updated_at", "2026-10-20T10:00:00.000Z")
            addProperty("created_origin_id", "a")
            addProperty("origin_id", "a")
            addProperty("deleted", false)
        })!!
        // Another PDF version: likes and dislikes belong to the version they were given on, so none is
        // drawn or relocated by its quote; each is listed apart.
        val elsewhere = placeFeedback(listOf(feedback("line second"), feedback("outside"), feedback("first")), "b".repeat(64), answer)
        assertEquals(listOf(true, true, true), elsewhere.map { it.earlierVersion })
        assertEquals(listOf(emptyList<Region>(), emptyList(), emptyList()), elsewhere.map { it.regions })
        // A version that cannot be told (no PDF on screen): found by its quote, as before.
        val placed = placeFeedback(listOf(feedback("line second"), feedback("outside"), feedback("first")), "", answer)
        assertEquals(listOf(State.RELOCATED, State.ORPHANED, State.RELOCATED), placed.map { it.state })
        assertEquals(2, placed[0].regions.size)
        assertEquals(emptyList<Region>(), placed[1].regions)
        // The PDF the feedback was made on: its own regions, unchanged.
        val exact = placeFeedback(listOf(feedback("outside")), "a".repeat(64), answer).single()
        assertEquals(State.EXACT, exact.state)
        assertEquals(listOf(Region(1, 0.1, 0.1, 0.2, 0.2)), exact.regions)
        // The list: by position in the answer, not found last.
        assertEquals(listOf("first", "line second", "outside"), feedbackListOrder(placed).map { it.feedback.exact })
    }

    @Test fun contextIsTheAnswerTextAroundTheSelection() {
        // "second" on the second line: x 10–39 pt, y 56–64 pt from the bottom.
        val selection = listOf(Region(1, 0.09, 0.35, 0.40, 0.45))
        assertEquals("first line\n" to " line text", answer.context(selection))
        assertEquals("ne\n" to " li", answer.context(selection, length = 3))
        assertEquals("" to "", answer.context(listOf(Region(1, 0.0, 0.7, 1.0, 0.9))))
    }

    @Test fun onlySelectionsInsideTheAnswerQualify() {
        val inside = listOf(Region(1, 0.1, 0.16, 0.5, 0.24), Region(1, 0.1, 0.36, 0.9, 0.44))
        assertTrue(feedbackSelectionQualifies(inside, answerRegions))
        // A line just past the region edge still counts (0.01 margin)...
        assertTrue(feedbackSelectionQualifies(listOf(Region(1, 0.1, 0.495, 0.5, 0.515)), answerRegions))
        // ...but a selection reaching the "outside" line does not, nor one on another page.
        assertFalse(feedbackSelectionQualifies(inside + Region(1, 0.1, 0.76, 0.4, 0.84), answerRegions))
        assertFalse(feedbackSelectionQualifies(listOf(Region(2, 0.1, 0.16, 0.5, 0.24)), answerRegions))
        assertFalse(feedbackSelectionQualifies(emptyList(), answerRegions))
        // Multi-page answers accept selections across their pages.
        val twoPages = answerRegions + Region(2, 0.0, 0.0, 1.0, 0.3)
        assertTrue(feedbackSelectionQualifies(listOf(Region(1, 0.1, 0.36, 0.9, 0.44), Region(2, 0.1, 0.1, 0.5, 0.2)), twoPages))
    }
}
