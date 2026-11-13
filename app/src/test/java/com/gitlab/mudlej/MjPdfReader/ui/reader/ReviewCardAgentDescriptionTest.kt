// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewCardAgentDescriptionTest {
    private val document = mapOf(
        "document_name" to "Paper", "source" to "docs-paper/main.tex", "source_name" to "main.tex",
        "source_format" to "LaTeX", "pdf" to "current_pdfs/paper.pdf", "pdf_pages" to "30",
    )
    private val header = "Thoughts document: \"Paper\"\n" +
        "Source: docs-paper/main.tex  (LaTeX)\n" +
        "To change main.tex, create proposals with the Thoughts create_proposals tool (several edits at once) or " +
        "`thoughtsctl create-proposals`; don't edit the source directly unless asked, and never edit the PDF.\n" +
        "Built PDF: current_pdfs/paper.pdf  (30 pages)\n"
    private val id = "6f1c2d3e-0000-4000-8000-000000000001"
    private val answerTemplate = "Answer source: {answer_source}\nAnswer text:\n{answer_text}\n"

    private fun agentText(fields: Map<String, String>, template: String = AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE) =
        AgentPromptTemplates.render(template, fields)

    @Test fun sourceLinkedSingleSegmentReportsTheLinesInsideTheMarkers() {
        val tex = "\\section{Intro}\n" + // 1
            "\\ReviewCardMaskStart{$id}{0}%\n" + // 2
            "The answer   starts here,  \n" + // 3
            "\n" + // 4
            "  and ends here.\t\n" + // 5
            "\\ReviewCardMaskEnd{$id}{0}%\n" + // 6
            "After.\n"
        val passages = reviewCardSourcePassages(id, listOf("docs-paper/main.tex" to tex))
        assertEquals(listOf(ReviewCardSourcePassage("docs-paper/main.tex", 3, 5,
            "The answer   starts here,\n\n  and ends here.")), passages)
        val fields = reviewCardPromptFields(document, id, "  What does\n the intro   say? ", "Check the\tsecond clause",
            listOf(19, 18, 19), passages, pdfAnswerText = "ignored when source-linked")
        // The default names where the answer is but never quotes it: the user is studying the card.
        assertEquals(
            header +
                "Review card: $id\n" +
                "Question: \"What does the intro say?\"\n" +
                "Note: \"Check the second clause\"\n" +
                "Answer pages: 18–19\n" +
                "Answer source: docs-paper/main.tex, lines 3–5\n",
            agentText(fields),
        )
        // {answer_text} brings it back for a template that asks for it.
        assertEquals(
            "Answer source: docs-paper/main.tex, lines 3–5\nAnswer text:\nThe answer   starts here,\n\n  and ends here.\n",
            agentText(fields, answerTemplate),
        )
    }

    @Test fun sourceLinkedSegmentsAreListedInOrderAndJoinedWithABlankLine() {
        val md = "# Notes\n" + // 1
            "<!-- thoughts:v1:start review:$id:answer:0 -->\n" + // 2
            "First passage.\n" + // 3
            "<!-- thoughts:v1:end review:$id:answer:0 -->\n" + // 4
            "Between.\n" + // 5
            "<!-- thoughts:v1:start review:$id:answer:1 -->\n" + // 6
            "Second,\n" + // 7
            "two lines.\n" + // 8
            "<!-- thoughts:v1:end review:$id:answer:1 -->\n" + // 9
            "<!-- thoughts:v1:start review:other:answer:0 -->\nNot this card.\n<!-- thoughts:v1:end review:other:answer:0 -->\n"
        val passages = reviewCardSourcePassages(id, listOf("docs-notes/notes.md" to md))
        val fields = reviewCardPromptFields(emptyMap(), id, "Q", "", listOf(6), passages, null)
        assertEquals(
            "Review card: $id\n" +
                "Question: \"Q\"\n" +
                "Answer pages: 6\n" +
                "Answer source: docs-notes/notes.md, lines 3–3; 7–8\n",
            agentText(fields),
        )
        assertEquals(
            "Answer source: docs-notes/notes.md, lines 3–3; 7–8\nAnswer text:\nFirst passage.\n\nSecond,\ntwo lines.\n",
            agentText(fields, answerTemplate),
        )
    }

    @Test fun inlineMarkersReportTheirOwnLineAndOnlyTheTextBetweenThem() {
        val tex = "Intro.\nWe know \\ReviewCardMaskStart{$id}{0}that it holds\nfor all n\\ReviewCardMaskEnd{$id}{0} here.\n"
        assertEquals(listOf(ReviewCardSourcePassage("a/main.tex", 2, 3, "that it holds\nfor all n")),
            reviewCardSourcePassages(id, listOf("a/main.tex" to tex)))
    }

    @Test fun passagesInSeveralFilesNameEachFile() {
        assertEquals("a/one.tex, lines 3–4; 9–9; a/two.tex, lines 1–2",
            reviewCardAnswerSourceLabel(listOf(
                ReviewCardSourcePassage("a/one.tex", 3, 4, "x"),
                ReviewCardSourcePassage("a/one.tex", 9, 9, "y"),
                ReviewCardSourcePassage("a/two.tex", 1, 2, "z"),
            )))
    }

    @Test fun pdfTextIsTheFallbackWithWhitespaceCollapsedPerLine() {
        val fields = reviewCardPromptFields(document, id, "Q", "  ", listOf(18),
            reviewCardSourcePassages(id, listOf("docs-paper/main.tex" to "No markers here.\n")),
            "  Line  one of\tthe answer \n\n line two  ")
        assertEquals(header + "Review card: $id\nQuestion: \"Q\"\nAnswer pages: 18\n", agentText(fields))
        assertEquals("Answer text:\nLine one of the answer\nline two\n", agentText(fields, answerTemplate))
    }

    @Test fun missingQuestionNoteAndAnswerAreLeftOut() {
        assertEquals(
            header + "Review card: $id\nAnswer pages: 2–4\n",
            agentText(reviewCardPromptFields(document, id, "", " \n", listOf(4, 2, 3), emptyList(), "  \n ")),
        )
        assertEquals("Review card: $id\n",
            agentText(reviewCardPromptFields(emptyMap(), id, "", "", emptyList(), emptyList(), null)))
    }

    @Test fun answerPagesUseAnEnDashForRanges() {
        assertEquals("6", reviewCardAnswerPagesLabel(listOf(6)))
        assertEquals("6", reviewCardAnswerPagesLabel(listOf(6, 6)))
        assertEquals("18–19", reviewCardAnswerPagesLabel(listOf(19, 18)))
        assertEquals("", reviewCardAnswerPagesLabel(listOf(0)))
    }

    @Test fun unmatchedOrEmptyMarkersGiveNoPassage() {
        assertEquals(emptyList<ReviewCardSourcePassage>(), reviewCardSourcePassages(id, listOf(
            "a/main.tex" to "\\ReviewCardMaskStart{$id}{0}%\nNo end.\n",
            "a/empty.tex" to "\\ReviewCardMaskStart{$id}{0}%\n  \n\\ReviewCardMaskEnd{$id}{0}%\n",
        )))
    }

    // Review card feedback (protocol/review-card-feedback.md §6.5, conformance C9) ------------------

    private val feedbackLines = listOf(
        ReviewCardFeedbackLine(false, "β is set\n  to 0.1", "Should be 0.05\nin our setup.", "2026-10-20T10:20:00.000Z", orphaned = true),
        ReviewCardFeedbackLine(true, "the KL term keeps the policy close", "Clear explanation.", "2026-10-20T10:14:03.512Z", orphaned = false),
        ReviewCardFeedbackLine(true, "x".repeat(301), "", "2026-10-20T10:30:00.000Z", orphaned = false),
    )

    @Test fun feedbackSectionHasTheSpecFormat() {
        assertEquals("", reviewCardFeedbackSection(emptyList()))
        assertEquals(
            "Answer feedback:\n" +
                "- Liked: \"the KL term keeps the policy close\" — Clear explanation.\n" +
                "- Disliked: \"β is set to 0.1\" — Should be 0.05 in our setup. (not found in the current answer)\n" +
                "- Liked: \"" + "x".repeat(300) + "…\"\n",
            reviewCardFeedbackSection(feedbackLines),
        )
    }

    @Test fun copyForAgentAddsTheFeedbackAfterTheCardLines() {
        val section = reviewCardFeedbackSection(feedbackLines.take(2))
        val fields = reviewCardPromptFields(document, id, "Q?", "", listOf(3), emptyList(), null, section)
        val text = AgentPromptTemplates.renderForCard(fields, AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE)
        assertEquals(
            header +
                "Review card: $id\n" +
                "Question: \"Q?\"\n" +
                "Answer pages: 3\n" +
                "Answer feedback:\n" +
                "- Liked: \"the KL term keeps the policy close\" — Clear explanation.\n" +
                "- Disliked: \"β is set to 0.1\" — Should be 0.05 in our setup. (not found in the current answer)\n",
            text,
        )
        // Without feedback the section and its line are left out.
        val plain = AgentPromptTemplates.renderForCard(
            reviewCardPromptFields(document, id, "Q?", "", listOf(3), emptyList(), null), AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE)
        assertEquals(header + "Review card: $id\nQuestion: \"Q?\"\nAnswer pages: 3\n", plain)
        // A template saved before {answer_feedback} existed still ends with it.
        val custom = AgentPromptTemplates.renderForCard(fields, "Card {card_id}\n")
        assertEquals("Card $id\n" + section, custom)
    }
}
