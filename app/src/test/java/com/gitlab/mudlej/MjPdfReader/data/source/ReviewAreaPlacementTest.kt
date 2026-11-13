// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** One marker segment per answer area, floats marked whole, and re-placement that only moves a card's markers. */
class ReviewAreaPlacementTest {
    private val id = "20000000-0000-4000-8000-000000000002"
    private val other = "30000000-0000-4000-8000-000000000003"
    private val tex = "docs-test/source/main.tex"

    /**
     * Fedora's acceptance rule, written independently of the app's own marker code: removing every
     * marker of the card (own-line markers with their line, inline ones alone) from "before" and
     * from "after" must give the same text, ignoring trailing newlines.
     */
    private fun stripCard(source: String, card: String): String {
        val ownLine = Regex("^[ \\t]*(\\\\ReviewCardMask(Start|End)\\{$card\\}\\{[0-9]+\\}%?|<!-- thoughts:v1:(start|end) review:$card:answer:[0-9]+ -->)[ \\t]*\\n", RegexOption.MULTILINE)
        val inline = Regex("\\\\ReviewCardMask(Start|End)\\{$card\\}\\{[0-9]+\\}")
        return source.replace(ownLine, "").replace(inline, "").trimEnd('\n', '\r')
    }

    private fun assertOnlyCardMarkersMoved(before: String, after: String) {
        assertEquals(stripCard(before, id), stripCard(after, id))
        assertFalse(stripCard(after, id).contains(id))
    }

    private fun failure(block: () -> Unit): ReviewAreaLinkingException {
        try { block() } catch (error: ReviewAreaLinkingException) { return error }
        fail("Expected the area to be refused")
        error("unreachable")
    }

    private val document = listOf(
        "\\section{Results}",                       // 1
        "",                                         // 2
        "The first answer paragraph is here.",      // 3
        "",                                         // 4
        "\\begin{table}[t]",                        // 5
        "  \\centering",                            // 6
        "  \\input{generated/results-table}",       // 7
        "  \\caption{Accuracy for each method.}",   // 8
        "  \\label{tab:results}",                   // 9
        "\\end{table}",                             // 10
        "",                                         // 11
        "The second answer paragraph is there.",    // 12
        "",                                         // 13
    ).joinToString("\n")

    @Test fun floatRangesIgnoreCommentsAndNestedFloats() {
        val source = "% \\begin{figure}\nText.\n\\begin{figure*}\n\\begin{subfigure}{x}\n\\end{subfigure}\n" +
            "\\begin{table}\n\\end{table}\n\\end{figure*}\n\\begin{itemize}\n\\item a\n\\end{itemize}\n\\begin{wraptable}{r}{4cm}\nx\n\\end{wraptable}\n"
        assertEquals(listOf(3..8, 12..14), TexFloats.ranges(source))
        assertEquals(3..8, TexFloats.cover(source, 5..5))
        assertEquals(2..8, TexFloats.cover(source, 2..4))
        assertNull(TexFloats.cover(source, 9..11))
        assertEquals(12..14, TexFloats.enclosing(source, 13..13))
        assertNull(TexFloats.enclosing(source, 8..12))
    }

    @Test fun aBoxInsideATableMarksTheWholeTable() {
        // The caption maps to line 8; the table body comes from a generated file (partial).
        val edit = ReviewSourceMarkup.placeAreas(tex, document, id, listOf(
            MappedSourceArea(listOf(8, 8), "Method A 91.2 Method B 88.4 Table 1: Accuracy for each method.", rectangle = true, partial = true),
        ))
        val lines = edit.after.split('\n')
        assertEquals("\\ReviewCardMaskStart{$id}{0}%", lines[4])
        assertEquals("\\begin{table}[t]", lines[5])
        assertEquals("\\end{table}", lines[10])
        assertEquals("\\ReviewCardMaskEnd{$id}{0}%", lines[11])
        val segment = edit.anchor!!.getAsJsonArray("segments").single().asJsonObject
        assertEquals("0", segment.get("segment_id").asString)
        assertEquals(tex, segment.get("source_file").asString)
        assertOnlyCardMarkersMoved(document, edit.after)
    }

    @Test fun onlyABoxIsWidenedToItsFloatAndOnlyAFloatCoversUnmappedText() {
        // A text selection in the caption is not widened, and markers inside a float are refused.
        val selection = failure {
            ReviewSourceMarkup.placeAreas(tex, document, id, listOf(MappedSourceArea(listOf(8, 8), "Accuracy for each method.")))
        }
        assertEquals(0, selection.areaIndex)
        // Outside a float, characters without a source line cannot be covered.
        val partial = failure {
            ReviewSourceMarkup.placeAreas(tex, document, id, listOf(
                MappedSourceArea(listOf(3), "The first answer paragraph is here.", rectangle = true),
                MappedSourceArea(listOf(12), "The second answer paragraph is there. [image]", rectangle = true, partial = true),
            ))
        }
        assertEquals(1, partial.areaIndex)
        assertEquals(2, partial.areaCount)
        assertTrue(partial.reason, partial.reason.contains("no source text"))
    }

    @Test fun severalAreasBecomeSegmentsInAreaOrderWhateverTheirPositionInTheFile() {
        val edit = ReviewSourceMarkup.placeAreas(tex, document, id, listOf(
            MappedSourceArea(listOf(12), "The second answer paragraph is there.", rectangle = true),
            MappedSourceArea(listOf(9), "", rectangle = true),
            MappedSourceArea(listOf(3), "The first answer paragraph is here."),
        ))
        val segments = edit.anchor!!.getAsJsonArray("segments").map { it.asJsonObject }
        assertEquals(listOf("0", "1", "2"), segments.map { it.get("segment_id").asString })
        val starts = segments.associate { it.get("segment_id").asString to it.get("line_start").asInt }
        assertTrue(starts.getValue("2") < starts.getValue("1") && starts.getValue("1") < starts.getValue("0"))
        assertTrue(edit.after.contains("\\ReviewCardMaskStart{$id}{0}The second answer paragraph is there.\\ReviewCardMaskEnd{$id}{0}"))
        assertOnlyCardMarkersMoved(document, edit.after)
    }

    @Test fun overlappingAreasNameTheLaterArea() {
        val error = failure {
            ReviewSourceMarkup.placeAreas(tex, document, id, listOf(
                MappedSourceArea(listOf(3), "The first answer paragraph is here.", rectangle = true),
                MappedSourceArea(listOf(12), "The second answer paragraph is there.", rectangle = true),
                MappedSourceArea(listOf(3), "first answer", rectangle = true),
            ))
        }
        assertEquals(2, error.areaIndex)
        assertTrue(error.reason, error.reason.contains("area 1"))
        assertEquals("Area 3 of 3 (page 5) can't be linked to source text: ${error.reason}",
            error.withPages(listOf(4, 4, 5)).message)
        assertEquals("Area 3 of 3 (page 7) can't be linked to source text: ${error.reason}",
            error.withPages(listOf(4, 4, 5)).describe(pageOffset = 2))
    }

    @Test fun placingACardAgainMovesOnlyItsOwnMarkers() {
        // The card had two passages; another card's passage sits between them.
        val first = ReviewSourceMarkup.placeAreas(tex, document, id, listOf(
            MappedSourceArea(listOf(3), "The first answer paragraph is here."),
            MappedSourceArea(listOf(12), "The second answer paragraph is there."),
        )).after
        val withOther = ReviewSourceMarkup.placeAreas(tex, first, other, listOf(
            MappedSourceArea(listOf(9), "", rectangle = true),
        )).after
        // Lines are mapped in the current source, which still holds the old markers.
        val lines = withOther.split('\n')
        val captionLine = lines.indexOfFirst { it.contains("\\caption") } + 1
        val secondLine = lines.indexOfFirst { it.contains("second answer") } + 1
        val again = ReviewSourceMarkup.placeAreas(tex, withOther, id, listOf(
            MappedSourceArea(listOf(secondLine), "second answer paragraph", rectangle = false),
            MappedSourceArea(listOf(captionLine), "Table 1", rectangle = true, partial = true),
        ))
        assertOnlyCardMarkersMoved(withOther, again.after)
        // The other card's markers are untouched, and nested inside this card's table segment.
        assertEquals(2, Regex("\\{$other\\}").findAll(again.after).count())
        assertFalse(again.after.contains("first answer paragraph is here.\\ReviewCardMaskEnd"))
        val segments = again.anchor!!.getAsJsonArray("segments").map { it.asJsonObject }
        assertEquals(listOf("0", "1"), segments.map { it.get("segment_id").asString })
        assertEquals(withOther, again.before)
    }

    @Test fun incompleteOldMarkersRefuseRePlacement() {
        val first = ReviewSourceMarkup.placeAreas(tex, document, id, listOf(MappedSourceArea(listOf(3), "The first answer paragraph is here."))).after
        val broken = first.replace("\\ReviewCardMaskEnd{$id}{0}", "")
        val error = failure {
            ReviewSourceMarkup.placeAreas(tex, broken, id, listOf(MappedSourceArea(listOf(12), "The second answer paragraph is there.")))
        }
        assertTrue(error.reason, error.reason.contains("incomplete"))
    }

    @Test fun staleAreasArePlacedByTheirTextAndMarkdownAreasByBlocks() {
        val markdown = "# Title\n\nFirst paragraph of the answer.\n\nUnrelated text.\n\nSecond paragraph of the answer.\n"
        val path = "docs-test/source/notes.md"
        val edit = ReviewSourceMarkup.placeAreas(path, markdown, id, listOf(
            MappedSourceArea(emptyList(), "Second paragraph of the answer.", stale = true),
            MappedSourceArea(listOf(3), "First paragraph of the answer.", rectangle = true),
        ))
        assertTrue(edit.after.contains("<!-- thoughts:v1:start review:$id:answer:0 -->\nSecond paragraph"))
        assertTrue(edit.after.contains("<!-- thoughts:v1:start review:$id:answer:1 -->\nFirst paragraph"))
        assertOnlyCardMarkersMoved(markdown, edit.after)
        val missing = failure {
            ReviewSourceMarkup.placeAreas(path, markdown, id, listOf(MappedSourceArea(emptyList(), "Text that is not there.", stale = true)))
        }
        assertTrue(missing.reason.contains("changed since this PDF was built"))
        assertEquals("The area (page 2) can't be linked to source text: ${missing.reason}", missing.withPages(listOf(2)).message)
    }
}
