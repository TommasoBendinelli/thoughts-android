// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewFeedbackAnchoring.State
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec conformance C4: every shared vector of thoughts-server
 * protocol/fixtures/review-card-feedback-anchoring.json (copied as a test resource) gives exactly
 * the expected state and UTF-16 range, as on the desktop and in the server's reference resolver.
 */
class ReviewCardFeedbackAnchoringTest {
    private val fixture = JsonParser.parseString(
        checkNotNull(javaClass.classLoader!!.getResource("review-card-feedback/anchoring.json")).readText(),
    ).asJsonObject

    private fun JsonElement?.intOrNull(): Int? = this?.takeUnless { it.isJsonNull }?.asInt

    @Test fun sharedVectorsMatchTheReferenceAlgorithm() {
        val cases = fixture.getAsJsonArray("cases")
        assertTrue(cases.size() >= 10)
        cases.forEach { element ->
            val case = element.asJsonObject
            val quote = case.getAsJsonObject("quote")
            val expected = case.getAsJsonObject("expected")
            val resolution = ReviewFeedbackAnchoring.resolve(
                case.get("answer_text").asString,
                quote.get("exact").asString,
                quote.get("prefix").asString,
                quote.get("suffix").asString,
                samePdf = case.get("same_pdf").asBoolean,
            )
            val name = case.get("name").asString
            assertEquals(name, expected.get("state").asString, resolution.state.wire)
            assertEquals(name, expected.get("start").intOrNull(), resolution.start)
            assertEquals(name, expected.get("end").intOrNull(), resolution.end)
        }
    }

    @Test fun theSpecCasesByName() {
        val text = "New first sentence. In practice, the KL term keeps the policy close."
        // (a) same sha
        assertEquals(State.EXACT, ReviewFeedbackAnchoring.resolve(text, "anything", samePdf = true).state)
        // (b) shifted
        val shifted = ReviewFeedbackAnchoring.resolve(text, "the KL term", "In practice, ", " keeps")
        assertEquals("the KL term", text.substring(shifted.start!!, shifted.end!!))
        // (c) context picks the second occurrence
        val twice = "the gap is small. Later the gap is small again."
        val second = ReviewFeedbackAnchoring.resolve(twice, "the gap is small", "Later ", " again")
        assertEquals(twice.lastIndexOf("the gap"), second.start)
        // (e) only the new PDF hyphenates at the line end
        val hyphenated = ReviewFeedbackAnchoring.resolve("keeps the refer-\nence model", "reference model")
        assertEquals(State.RELOCATED, hyphenated.state)
        assertEquals(10, hyphenated.start)
        // (f) edited quote; an empty skeleton
        assertEquals(State.ORPHANED, ReviewFeedbackAnchoring.resolve(text, "the entropy bonus").state)
        assertEquals(State.ORPHANED, ReviewFeedbackAnchoring.resolve(text, " ­​ ").state)
    }

    @Test fun skeletonDropsSpacesMarksAndFormatCharactersButKeepsSourceRanges() {
        val skeleton = ReviewFeedbackAnchoring.skeleton("é ﬁ­𝒫")
        assertEquals(listOf('e'.code, 'f'.code, 'i'.code, 'P'.code), skeleton.codePoints.toList())
        assertEquals(listOf(0, 3, 3, 5), skeleton.starts.toList())
        assertEquals(listOf(2, 4, 4, 7), skeleton.ends.toList())
    }
}
