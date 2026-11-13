// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The desktop's ReviewDocumentToolsTest::agentPromptTemplatesRender.
class AgentPromptTemplatesTest {
    private val fields = mapOf(
        "card_id" to "card-1",
        "question" to "Explain GRPO",
        "note" to "   ",
        "answer_text" to "Line one\nLine two\n\n",
    )

    @Test fun linesWithAnEmptyPlaceholderAreLeftOutAndOtherBracesStay() {
        assertEquals(
            "Card card-1: \"Explain GRPO\"\nKeep \\frac{a}{b} and {unknown}\nLine one\nLine two\nEnd\n",
            AgentPromptTemplates.render(
                "Card {card_id}: \"{question}\"\r\nNote: {note}\nPages: {answer_pages}\n" +
                    "Keep \\frac{a}{b} and {unknown}\n{answer_text}\nEnd\n\n\n",
                fields,
            ),
        )
        assertEquals("", AgentPromptTemplates.render("{note}", fields))
    }

    @Test fun theCardTextEndsWithFeedbackItsTemplateLeftOut() {
        val withFeedback = fields + ("answer_feedback" to "Answer feedback: liked \"x\"\n")
        assertEquals("Card card-1\nAnswer feedback: liked \"x\"\n",
            AgentPromptTemplates.renderForCard(withFeedback, "Card {card_id}\n"))
        assertEquals("Card card-1\n", AgentPromptTemplates.renderForCard(fields, "Card {card_id}\n"))
    }

    @Test fun theStudyModePromptNamesTheMentraFolder() {
        assertEquals("Photos in /m/photos\n", AgentPromptTemplates.studyModePrompt("Photos in {mentra_folder}/photos\n", "/m"))
        val prompt = AgentPromptTemplates.studyModePrompt(AgentPromptTemplates.DEFAULT_STUDY_MODE_TEMPLATE,
            "/home/alex/glasses")
        assertTrue(prompt, prompt.startsWith("Agent study mode is on in Thoughts on my phone.\n"))
        assertTrue(prompt, prompt.contains("photos in /home/alex/glasses/photos as IMG_YYYYMMDD_HHMMSS_*.jpg, videos in " +
            "/home/alex/glasses/videos as VID_YYYYMMDD_HHMMSS_*.mp4."))
        val byDefault = AgentPromptTemplates.studyModePrompt(AgentPromptTemplates.DEFAULT_STUDY_MODE_TEMPLATE,
            AgentPromptTemplates.DEFAULT_MENTRA_FOLDER)
        assertTrue(byDefault, byDefault.contains("photos in ${AgentPromptTemplates.DEFAULT_MENTRA_FOLDER}/photos as "))
        assertTrue(prompt, prompt.contains("transcribe its audio with `~/.local/bin/transcribe <video>`"))
        assertFalse(prompt, prompt.contains("Copy for physical studying"))
    }

    @Test fun defaultsUseTheirOwnPlaceholdersAndLeaveTheAnswerOut() {
        for (kind in AgentPromptTemplates.Kind.entries) {
            val known = AgentPromptTemplates.placeholders(kind).map { it.first }.toSet()
            val template = AgentPromptTemplates.defaultTemplate(kind)
            Regex("\\{([a-z_]+)\\}").findAll(template).forEach { assertTrue(it.value, it.groupValues[1] in known) }
            assertFalse(AgentPromptTemplates.usesPlaceholder(template, "answer_text"))
        }
        assertEquals(listOf("mentra_folder"),
            AgentPromptTemplates.placeholders(AgentPromptTemplates.Kind.STUDY_MODE).map { it.first })
        val card = AgentPromptTemplates.renderForCard(fields, AgentPromptTemplates.DEFAULT_AGENT_TEMPLATE)
        assertTrue(card, card.startsWith("Review card: card-1\nQuestion: \"Explain GRPO\"\n"))
        assertFalse(card, card.contains("Line one"))
    }
}
