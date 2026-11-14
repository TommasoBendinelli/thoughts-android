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

    @Test fun theStudyModePromptExplainsAndNeverGrades() {
        val prompt = AgentPromptTemplates.DEFAULT_STUDY_MODE_TEMPLATE
        // Study mode explains and never grades: no ✓/✗ or colour marks.
        for (grading in listOf("grade it", "grade against", "you grade", "green = right", "red = fix", "what is right and what is wrong")) {
            assertFalse(grading, prompt.contains(grading))
        }
        assertTrue(prompt, prompt.contains("Explain, never grade"))
        assertTrue(prompt, prompt.contains("name it in one plain line and show the right step"))
        assertTrue(prompt, prompt.contains("[p. N](<Library filename>.pdf#page=N), my own notes first"))
        assertTrue(prompt, prompt.contains("knowledge_profile"))
        assertTrue(prompt, prompt.contains("\"Answering rules for agent notes\""))
        // Capture watching and reply watching stay.
        assertTrue(prompt, prompt.contains("Watch both folders for the rest of this conversation"))
        assertTrue(prompt, prompt.contains("run `~/.local/bin/thoughts-watch-replies` as a background monitor"))
        assertTrue(prompt, prompt.contains("answer each reply to your own notes"))
        assertTrue(prompt, prompt.contains("the Thoughts reply agent answers the replies no running agent covers"))
        assertTrue(prompt, prompt.contains("PAGE line"))
        // The study session owns the glasses captures, so the Thoughts reply agent does not answer them too.
        assertTrue(prompt, prompt.contains("call own_topic with captures set to true, a label for what we are studying, " +
            "and the document (document_ids) and card (review_card_ids) we are studying, so the Thoughts reply agent " +
            "leaves my captures to you."))
        assertTrue(prompt, prompt.contains("Keep your reply watcher running (below), because the claim lasts only while you listen."))
        assertTrue(prompt, prompt.contains("When we move to another card or document, call own_topic again for it."))
        assertTrue(prompt, prompt.contains("Call release_topic when study mode ends."))
        assertTrue(prompt, prompt.contains("add `--conversation <your conversation id>`, the id you pass to own_topic"))
        // Ownership comes before the captures it covers.
        assertTrue(prompt.indexOf("own_topic") < prompt.indexOf("Read every new photo"))
        assertFalse(AgentPromptTemplates.PREVIOUS_DEFAULT_STUDY_MODE_TEMPLATE.contains("own_topic"))
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
