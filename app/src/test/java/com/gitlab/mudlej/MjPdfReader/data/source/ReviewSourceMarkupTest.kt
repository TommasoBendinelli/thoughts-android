// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ReviewSourceMarkupTest {
    private val id = "20000000-0000-4000-8000-000000000001"
    @Test fun texAndMarkdownRoundTripWithoutChangingTheAnswer() {
        for (extension in listOf("tex", "md", "markdown")) {
            val path = "docs-test/source/main.$extension"
            val before = "Before.\n\nA complete answer with café.\n\nAfter.\n"
            val edit = ReviewSourceMarkup.create(path, before, "A complete answer with café.", id)
            val segment = edit.anchor!!.getAsJsonArray("segments").single().asJsonObject
            assertEquals(before, ReviewSourceMarkup.remove(path, edit.after, id, segment, false).after)
            assertEquals("Before.\n\n\nAfter.\n", ReviewSourceMarkup.remove(path, edit.after, id, segment, true).after)
            val updated = edit.after.replace("complete", "edited")
            assertEquals(before.replace("complete", "edited"), ReviewSourceMarkup.remove(path, updated, id, segment, false).after)
            assertTrue(runCatching { ReviewSourceMarkup.remove(path, updated, id, segment, true) }.isFailure)
        }
    }
    @Test fun texMarkersFollowTheHighlightedWordsAcrossSourceLines() {
        val path = "docs-test/source/main.tex"
        val before = "Before.\n\nAlpha beta gamma\ndelta epsilon zeta.\n\nAfter.\n"
        val edit = ReviewSourceMarkup.create(path, before, "beta gamma delta epsilon", id, listOf(3, 4))
        assertTrue(edit.after.contains("Alpha \\ReviewCardMaskStart{$id}{0}beta gamma\ndelta epsilon\\ReviewCardMaskEnd{$id}{0} zeta."))
        val segment = edit.anchor!!.getAsJsonArray("segments").single().asJsonObject
        assertEquals(before, ReviewSourceMarkup.remove(path, edit.after, id, segment, false).after)
        assertEquals("Before.\n\nAlpha  zeta.\n\nAfter.\n", ReviewSourceMarkup.remove(path, edit.after, id, segment, true).after)
        assertEquals(3, segment.get("line_start").asInt)
        assertEquals(4, segment.get("line_end").asInt)
    }
    @Test fun repeatedPassagesAndWrongMappedLinesRemainUnlinked() {
        val path = "docs-test/source/main.tex"
        val before = "Alpha beta gamma.\n\nAlpha beta gamma.\n"
        assertTrue(runCatching { ReviewSourceMarkup.create(path, before, "beta gamma", id) }.isFailure)
        assertTrue(runCatching { ReviewSourceMarkup.create(path, "Alpha beta gamma.\n", "beta gamma", id, listOf(12)) }.isFailure)
    }
    @Test fun ambiguityPartialTextHiddenAndMalformedSourceRequireManualMarkers() {
        for ((source, selected) in listOf(
            "Answer.\n\nAnswer.\n" to "Answer.",
            "\\begin{verbatim}\n\nAnswer.\n\n\\end{verbatim}" to "Answer.",
            "\\newcommand{\\example}{\n\nAnswer.\n\n}" to "Answer.",
            "```\n\nAnswer.\n\n```" to "Answer.",
        )) {
            val path = if (source.startsWith("```")) "docs-test/main.md" else "docs-test/main.tex"
            assertTrue(source, runCatching { ReviewSourceMarkup.create(path, source, selected, id) }.isFailure)
        }
    }
    @Test fun repeatedOrIncompleteMarkersCannotAuthorizeDeletion() {
        val path = "docs-test/main.tex"
        val edit = ReviewSourceMarkup.create(path, "Answer.\n", "Answer.", id)
        val segment = edit.anchor!!.getAsJsonArray("segments").single().asJsonObject
        for (invalid in listOf(edit.after + edit.after, edit.after.replace("ReviewCardMaskEnd", "MissingEnd"))) {
            assertTrue(runCatching { ReviewSourceMarkup.remove(path, invalid, id, segment, false) }.isFailure)
        }
    }
    @Test fun nestedOtherCardCannotBeErasedWithEnclosedText() {
        val path = "docs-test/main.tex"
        val edit = ReviewSourceMarkup.create(path, "Answer.\n", "Answer.", id)
        val other = "20000000-0000-4000-8000-000000000002"
        val nested = "\\ReviewCardMaskStart{$other}{0}%\n${edit.after}\\ReviewCardMaskEnd{$other}{0}%\n"
        val segment = edit.anchor!!.getAsJsonArray("segments").single().asJsonObject
        assertTrue(runCatching { ReviewSourceMarkup.remove(path, nested, id, segment, true) }.isFailure)
        assertTrue(ReviewSourceMarkup.remove(path, nested, id, segment, false).after.contains(other))
    }

    private val other = "20000000-0000-4000-8000-000000000002"
    private val latex = listOf(
        "Intro.",                                                   // 1
        "",                                                         // 2
        "\\paragraph{On policy distillation}",                      // 3
        "\\ReviewCardMaskStart{$other}{0}%",                        // 4
        "The rollout is sampled from the student's policy.",       // 5
        "This was introduced by \\citet{agarwal2024onpolicy}.",    // 6
        "A \\SourceLink{tex/source/divergence.tex}{doc:kl}{divergence} trains the student.", // 7
        "\\ReviewCardMaskEnd{$other}{0}%",                          // 8
        "",                                                         // 9
        "After.",                                                   // 10
        "",
    ).joinToString("\n")
    // PDF text of the same passage: rendered citation, link text and a curly apostrophe.
    private val pdfText = "On policy distillation The rollout is sampled from the student\u2019s policy. " +
        "This was introduced by Agarwal et al. [1]. A divergence trains the student."

    @Test fun sourceMapLinksLatexPassageThatDiffersFromItsPdfText() {
        val path = "docs-test/tex/source/main.tex"
        val edit = ReviewSourceMarkup.create(path, latex, pdfText, id, listOf(3, 7))
        val lines = edit.after.split("\n")
        // The new card wraps the heading and the other card's complete marker pair.
        assertEquals("\\ReviewCardMaskStart{$id}{0}%", lines[2])
        assertEquals("\\paragraph{On policy distillation}", lines[3])
        assertEquals("\\ReviewCardMaskEnd{$other}{0}%", lines[8])
        assertEquals("\\ReviewCardMaskEnd{$id}{0}%", lines[9])
        val segment = edit.anchor!!.getAsJsonArray("segments").single().asJsonObject
        assertEquals(3, segment.get("line_start").asInt)
        assertEquals(10, segment.get("line_end").asInt)
        assertEquals(latex, ReviewSourceMarkup.remove(path, edit.after, id, segment, false).after)
    }

    @Test fun sourceMapNestsInsideAnotherCardAndRejectsPartialOverlap() {
        val path = "docs-test/tex/source/main.tex"
        val inner = ReviewSourceMarkup.create(path, latex, "sampled from the student\u2019s policy. introduced by Agarwal", id, listOf(5, 6))
        val lines = inner.after.split("\n")
        assertEquals("\\ReviewCardMaskStart{$id}{0}%", lines[4])
        assertEquals("\\ReviewCardMaskEnd{$id}{0}%", lines[7])
        assertEquals(latex, ReviewSourceMarkup.remove(path, inner.after, id,
            inner.anchor!!.getAsJsonArray("segments").single().asJsonObject, false).after)
        // Intro through the middle of the other card's passage crosses its markers.
        assertTrue(runCatching { ReviewSourceMarkup.create(path, latex, "Intro. On policy distillation The", id, listOf(1, 5)) }.isFailure)
    }

    @Test fun sourceMapRefusesToSplitCommandsAndExpandsMarkdownBlocks() {
        val tex = "Text with a note\\footnote{spanning\nseveral lines}.\nMore.\n"
        assertTrue(runCatching { ReviewSourceMarkup.create("docs-test/main.tex", tex, "Text with a note1", id, listOf(1)) }.isFailure)
        val md = "# Title\n\n#### Response\nWe thank the reviewer.\nWe are happy that it\u2019s *useful*.\n\nNext.\n"
        val edit = ReviewSourceMarkup.create("docs-test/reviews.md", md, "Response We thank the reviewer. useful", id, listOf(4))
        val lines = edit.after.split("\n")
        assertEquals("<!-- thoughts:v1:start review:$id:answer:0 -->", lines[2])
        assertEquals("#### Response", lines[3])
        assertEquals("<!-- thoughts:v1:end review:$id:answer:0 -->", lines[6])
        assertEquals(md, ReviewSourceMarkup.remove("docs-test/reviews.md", edit.after, id,
            edit.anchor!!.getAsJsonArray("segments").single().asJsonObject, false).after)
    }

    @Test fun lineMapFollowsAddedAndRemovedCardMarkersOnly() {
        val base = "Intro.\n\nFirst line.\nSecond line.\n\nAfter.\n"
        // A card created since the build added own-line markers around lines 3-4.
        val added = "Intro.\n\n\\ReviewCardMaskStart{$id}{0}%\nFirst line.\nSecond line.\n\\ReviewCardMaskEnd{$id}{0}%\n\nAfter.\n"
        assertEquals(listOf(1, 2, 4, 5, 7, 8, 9), ReviewSourceMarkup.lineMapIgnoringMarkers(base, added)!!.toList())
        // Inline markers keep every line where it was.
        val inline = "Intro.\n\nFirst \\ReviewCardMaskStart{$id}{0}line.\nSecond\\ReviewCardMaskEnd{$id}{0} line.\n\nAfter.\n"
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), ReviewSourceMarkup.lineMapIgnoringMarkers(base, inline)!!.toList())
        // A deleted card's marker lines disappear without moving the text.
        assertEquals(listOf(1, 2, 2, 3, 4, 4, 5, 6, 7), ReviewSourceMarkup.lineMapIgnoringMarkers(added, base)!!.toList())
        // Markdown comment markers behave the same way.
        val md = "Intro.\n\n<!-- thoughts:v1:start review:$id:answer:0 -->\nFirst line.\nSecond line.\n<!-- thoughts:v1:end review:$id:answer:0 -->\n\nAfter.\n"
        assertEquals(4, ReviewSourceMarkup.lineMapIgnoringMarkers(base, md)!![2])
        // Real edits still mean the PDF no longer matches its source.
        assertNull(ReviewSourceMarkup.lineMapIgnoringMarkers(base, base.replace("First line", "First edited line")))
        assertNull(ReviewSourceMarkup.lineMapIgnoringMarkers(base, base + "Appended.\n"))
        assertNull(ReviewSourceMarkup.lineMapIgnoringMarkers(null, base))
    }
    @Test fun oneCardMarksSeveralPassagesOfOneFileAsSegments() {
        for (extension in listOf("tex", "md")) {
            val path = "docs-test/source/main.$extension"
            val before = "First answer.\n\nBetween.\n\nSecond answer.\n"
            // Areas are marked from the bottom up, so the upper passage keeps its line numbers.
            val lower = ReviewSourceMarkup.create(path, before, "Second answer.", id, segmentId = "1")
            val both = ReviewSourceMarkup.create(path, lower.after, "First answer.", id, segmentId = "0", otherSegments = true)
            val anchor = ReviewSourceMarkup.anchor(path, both.after, id)
            val segments = anchor.getAsJsonArray("segments").map { it.asJsonObject }
            assertEquals(listOf("0", "1"), segments.map { it.get("segment_id").asString })
            assertTrue(segments[0].get("line_end").asInt < segments[1].get("line_start").asInt)
            var unmarked = both.after
            for (segment in segments) unmarked = ReviewSourceMarkup.remove(path, unmarked, id, segment, false).after
            assertEquals(before, unmarked)
            var deleted = both.after
            for (segment in segments) deleted = ReviewSourceMarkup.remove(path, deleted, id, segment, true).after
            assertFalse(deleted.contains("answer"))
            assertTrue(deleted.contains("Between."))
            // A segment cannot be marked twice.
            assertTrue(runCatching { ReviewSourceMarkup.create(path, both.after, "Between.", id, segmentId = "1", otherSegments = true) }.isFailure)
        }
    }
    @Test fun passagesOfOneCardCannotOverlapInTheSource() {
        val path = "docs-test/source/main.tex"
        val before = "\\section{A}\nFirst \\emph{answer}.\nSecond line.\n"
        val first = ReviewSourceMarkup.create(path, before, "First answer", id, listOf(2), segmentId = "1")
        assertTrue(runCatching {
            ReviewSourceMarkup.create(path, first.after, "Second line", id, listOf(2, 3), segmentId = "0", otherSegments = true)
        }.isFailure)
    }

    @Test fun hyphenatedPdfTextAcrossParagraphsStillFindsItsUniqueSource() {
        val path = "docs-test/tex/source/main.tex"
        val source = "\\section{Intro}\n\nThe first paragraph explains a rather complicated idea.\nIt continues on a second source line.\n\n" +
            "The second paragraph describes the workflow in detail.\n\n\\section{Next}\n"
        // As a PDF reader extracts it: justified lines, line-end hyphens and curly punctuation.
        val pdfText = "The first paragraph explains a rather compli-\ncated idea. It continues on a second source line.\n" +
            "The second paragraph describes the work-\nflow in detail."
        val edit = ReviewSourceMarkup.create(path, source, pdfText, id)
        assertEquals(source.replace("The first", "\\ReviewCardMaskStart{$id}{0}The first")
            .replace("in detail.", "in detail.\\ReviewCardMaskEnd{$id}{0}"), edit.after)
    }

    @Test fun hyphenTolerantMatchingStillRefusesRepeatedOrShortText() {
        val path = "docs-test/tex/source/main.tex"
        val repeated = "A passage that appears twice in the file.\n\nA passage that appears twice in the file.\n"
        assertTrue(runCatching { ReviewSourceMarkup.create(path, repeated, "A passage that ap-\npears twice in the file.", id) }.isFailure)
        // Too few letters to trust a match that ignores spacing and punctuation.
        assertTrue(runCatching { ReviewSourceMarkup.create(path, "Short com-\nplex words.\n".replace("-\n", ""), "Short com- plex", id) }.isFailure)
    }
}
