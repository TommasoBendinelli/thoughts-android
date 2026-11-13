// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewAnswerTextLoaderTest {

    @Test
    fun `extracts text only from the normalized answer region`() {
        val metrics = metrics(
            line("TOP", y = 85f) +
                control('\n') +
                line("Answer text", y = 55f) +
                control('\n') +
                line("BOTTOM", y = 15f),
        )

        assertEquals(
            "Answer text",
            extractReviewAnswerText(
                metrics,
                pageWidth = 100f,
                pageHeight = 100f,
                normalizedRegion = ReviewAnswerRegion(0f, 0.35f, 1f, 0.55f),
            ),
        )
    }

    @Test
    fun `interprets normalized vertical coordinates from the top of the page`() {
        val metrics = metrics(
            line("Top answer", y = 90f) + control('\n') + line("Bottom", y = 10f),
        )

        assertEquals(
            "Top answer",
            extractReviewAnswerText(
                metrics,
                pageWidth = 100f,
                pageHeight = 100f,
                normalizedRegion = ReviewAnswerRegion(0f, 0f, 1f, 0.2f),
            ),
        )
    }

    @Test
    fun `preserves line breaks within a multi-line answer`() {
        val metrics = metrics(line("first", y = 60f) + control('\n') + line("second", y = 40f))

        assertEquals(
            "first\nsecond",
            extractReviewAnswerText(
                metrics,
                pageWidth = 100f,
                pageHeight = 100f,
                normalizedRegion = ReviewAnswerRegion(0f, 0.25f, 1f, 0.75f),
            ),
        )
    }

    private data class Glyph(
        val codePoint: Int,
        val left: Float,
        val bottom: Float,
        val right: Float,
        val top: Float,
    )

    private fun line(text: String, y: Float): List<Glyph> {
        var x = 5f
        return text.map { character ->
            if (character == ' ') {
                x += 4f
                Glyph(character.code, 0f, 0f, 0f, 0f)
            } else {
                Glyph(character.code, x, y - 4f, x + 4f, y + 4f).also { x += 5f }
            }
        }
    }

    private fun control(character: Char): List<Glyph> =
        listOf(Glyph(character.code, 0f, 0f, 0f, 0f))

    private fun metrics(glyphs: List<Glyph>): PageCharMetrics = PageCharMetrics(
        count = glyphs.size,
        left = glyphs.map(Glyph::left).toFloatArray(),
        bottom = glyphs.map(Glyph::bottom).toFloatArray(),
        right = glyphs.map(Glyph::right).toFloatArray(),
        top = glyphs.map(Glyph::top).toFloatArray(),
        fontSize = FloatArray(glyphs.size) { 10f },
        fontWeight = IntArray(glyphs.size) { 400 },
        codePoint = glyphs.map(Glyph::codePoint).toIntArray(),
        angleRadians = FloatArray(glyphs.size),
        fontFlags = IntArray(glyphs.size),
    )
}
