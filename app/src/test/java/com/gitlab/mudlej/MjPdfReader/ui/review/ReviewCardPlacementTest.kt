// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundleInput
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundlePdf
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewCardPlacementTest {
    @Test fun fileChoicesIncludeDocumentInputsBeyondTheRememberedFile() {
        val pdf = OfflineBundlePdf("proposal.pdf", "hash", 2, listOf(
            OfflineBundleSegment(1, 1, true, "project/tex/main.tex", null, null,
                listOf(OfflineBundleInput("chapter.tex", "project/tex/chapter.tex", "hash"))),
            OfflineBundleSegment(2, 1, true, "other/markdown/notes.md", null, null, emptyList()),
        ))
        assertEquals(listOf("project/tex/old.tex", "project/tex/main.tex", "project/tex/chapter.tex"),
            reviewPlacementSourceFiles(listOf("project/tex/old.tex", "project/image.png"), pdf, 1))
    }

    @Test fun separateMarkerRangesStaySelectable() {
        val source = "\\ReviewCardMaskStart{card}{0}%\nFirst.\n\\ReviewCardMaskEnd{card}{0}%\n" +
            "Gap.\n\\ReviewCardMaskStart{card}{1}%\nSecond.\n\\ReviewCardMaskEnd{card}{1}%\n"
        assertEquals(
            listOf(
                ReviewPlacementRange("docs-test/tex/a.tex", 1, 3),
                ReviewPlacementRange("docs-test/tex/a.tex", 5, 7),
            ),
            reviewPlacementRanges("card", mapOf("docs-test/tex/a.tex" to source)),
        )
        assertEquals(emptyList<ReviewPlacementRange>(),
            reviewPlacementRanges("other", mapOf("docs-test/tex/a.tex" to source)))
        assertEquals("docs-test/tex/a.tex (lines 1–3 and 5–7)",
            reviewPlacementLocationsText(reviewPlacementRanges("card", mapOf("docs-test/tex/a.tex" to source))!!))
    }

    @Test fun incompleteMarkerPairNeverProducesANavigationTarget() {
        assertNull(reviewPlacementRanges("card", mapOf("docs-test/tex/a.tex" to
            "\\ReviewCardMaskStart{card}{0}%\nAnswer.\n")))
    }

    @Test fun aHalfPlacedPairStillOpensAtThatCardsMarker() {
        val id = "0cd8138d-f45c-4e28-a72d-409752f75dc3"
        val sources = mapOf("concepts/tex/source/grpo.tex" to "Intro.\n\\ReviewCardMaskStart{$id}{0}The answer.\nAfter.\n")
        assertNull(reviewPlacementRanges(id, sources))
        assertEquals("concepts/tex/source/grpo.tex" to 2, reviewCardMarkerLine(id, sources))
        assertNull(reviewCardMarkerLine("other", sources))
    }

    @Test fun aLinkedCardWithSeveralAreasIsLinkedNotAmbiguous() {
        val anchor = com.google.gson.JsonParser.parseString(
            """{"version":2,"segments":[{"segment_id":"0","source_file":"a.tex"},{"segment_id":"1","source_file":"a.tex"}]}""").asJsonObject
        val card = com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard("c", "approved", "paper", "a".repeat(64), 1, "b".repeat(64),
            "Q?", android.graphics.RectF(), null, "succeeded", sourceAnchor = anchor)
        val ranges = listOf(ReviewPlacementRange("a.tex", 30, 32), ReviewPlacementRange("a.tex", 10, 12))
        org.junit.Assert.assertEquals("a.tex" to "10–12, 30–32", reviewLinkedSegmentsText(card, ranges))
        // Passages in two files, or more passages than segments, are still an error.
        org.junit.Assert.assertNull(reviewLinkedSegmentsText(card, listOf(ReviewPlacementRange("a.tex", 1, 2), ReviewPlacementRange("b.tex", 1, 2))))
        org.junit.Assert.assertNull(reviewLinkedSegmentsText(card, ranges + ReviewPlacementRange("a.tex", 40, 41)))
        org.junit.Assert.assertNull(reviewLinkedSegmentsText(card.copy(sourceAnchor = null), ranges))
        org.junit.Assert.assertNull(reviewLinkedSegmentsText(card, ranges.take(1)))
    }
}
