// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.source.SourceAnnotationMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewManualMarkersTest {
    private val id = "0cd8138d-f45c-4e28-a72d-409752f75dc3"

    @Test fun texMarkersPastedAroundAnAnswerScanAsOneCardSegment() {
        val markers = ReviewManualMarkers.forCard(id, "concepts/tex/source/grpo.tex")
        // Pasted mid-line, the start marker must not comment out the answer.
        val scanned = SourceAnnotationMarkup.scan("Intro.\n${markers.start}The answer.\n${markers.end}\nAfter.\n", false)
        assertTrue(scanned.get("valid").asBoolean)
        assertEquals(1, scanned.getAsJsonObject("reviews").getAsJsonArray(id).size())
    }

    @Test fun markdownSourcesGetMarkdownMarkers() {
        val markers = ReviewManualMarkers.forCard(id, "notes/markdown/source/grpo.md")
        val scanned = SourceAnnotationMarkup.scan("Intro.\n\n${markers.start}\nThe answer.\n${markers.end}\n", true)
        assertTrue(scanned.get("valid").asBoolean)
        assertEquals(1, scanned.getAsJsonObject("reviews").getAsJsonArray(id).size())
    }

    @Test fun placementSentenceIsDroppedFromTheShownReason() {
        assertEquals("The source changed since this PDF was built.", ReviewManualMarkers.withoutPlacementHint(
            "The source changed since this PDF was built. Place the start/end markers manually for card $id."))
        assertEquals("Busy.", ReviewManualMarkers.withoutPlacementHint("Busy. Place the markers manually for card $id."))
    }
}
