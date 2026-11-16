// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNote
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplySources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentNoteReplySourcesLookTest {
    /** The texts of values/strings.xml, so the tests read like the screen. */
    private val strings = mapOf(
        R.string.agent_note_sources_chip to "Sources: %1\$s",
        R.string.agent_note_sources_chip_description to "Choose what the agent may use to answer. Now: %1\$s",
        R.string.agent_note_sources_none to "No Thoughts documents",
        R.string.agent_note_sources_none_detail to "Only this note and your reply, nothing from your Library",
        R.string.agent_note_sources_linked_pdf to "Linked PDF only",
        R.string.agent_note_sources_linked_pdf_detail to "Only the PDF this note is linked to",
        R.string.agent_note_sources_linked_pdf_named_detail to "Only %1\$s, the PDF this note is linked to",
        R.string.agent_note_sources_linked_pdf_none_detail to "Only the PDF a note is linked to; this one is linked to none",
        R.string.agent_note_sources_library to "All documents",
        R.string.agent_note_sources_library_detail to "Every document of your Library, without the Mental Model",
        R.string.agent_note_sources_library_and_mental_model to "All documents + Mental Model",
        R.string.agent_note_sources_library_and_mental_model_detail to "Your Library and your Mental Model, as agents answer without a choice",
    )
    private val string: (Int, Array<out Any>) -> String = { id, args -> String.format(strings.getValue(id), *args) }

    @Test fun theChipSaysTheChoiceAndAScreenReaderHearsWhatItDoes() {
        assertEquals("Sources: All documents + Mental Model", agentNoteReplySourcesChipText(AgentNoteReplySources.DEFAULT, string))
        assertEquals("Sources: Linked PDF only", agentNoteReplySourcesChipText(AgentNoteReplySources.LINKED_PDF, string))
        assertEquals(
            "Choose what the agent may use to answer. Now: No Thoughts documents",
            agentNoteReplySourcesChipDescription(AgentNoteReplySources.NONE, string),
        )
    }

    @Test fun theFourChoicesInOrderWithTheCurrentOneChecked() {
        val note = AgentNote("n-1", documentId = "doc-1", documentFilename = "Example paper.pdf")
        val choices = agentNoteReplySourcesChoices(AgentNoteReplySources.LIBRARY, note, string)
        assertEquals(
            listOf("No Thoughts documents", "Linked PDF only", "All documents", "All documents + Mental Model"),
            choices.map { it.title },
        )
        assertEquals(listOf(AgentNoteReplySources.LIBRARY), choices.filter { it.checked }.map { it.sources })
        assertEquals("Only Example paper.pdf, the PDF this note is linked to", choices[1].detail)
        assertEquals("Your Library and your Mental Model, as agents answer without a choice", choices[3].detail)
    }

    @Test fun linkedPdfOnlySaysWhenTheNoteIsLinkedToNone() {
        fun detail(note: AgentNote?) = agentNoteReplySourcesChoices(AgentNoteReplySources.DEFAULT, note, string)
            .single { it.sources == AgentNoteReplySources.LINKED_PDF }.detail
        assertEquals("Only the PDF this note is linked to", detail(AgentNote("n-1", documentId = "doc-1")))
        assertEquals("Only the PDF a note is linked to; this one is linked to none", detail(AgentNote("n-2")))
        // Before the note has loaded nothing is claimed about it.
        assertEquals("Only the PDF this note is linked to", detail(null))
    }

    @Test fun aPastReplyNotesOnlyAChoiceOtherThanTheDefault() {
        assertEquals(R.string.agent_note_sources_linked_pdf, agentNoteReplySourcesNote(AgentNoteReplySources.LINKED_PDF))
        assertEquals(R.string.agent_note_sources_none, agentNoteReplySourcesNote(AgentNoteReplySources.NONE))
        assertEquals(R.string.agent_note_sources_library, agentNoteReplySourcesNote(AgentNoteReplySources.LIBRARY))
        assertNull(agentNoteReplySourcesNote(AgentNoteReplySources.LIBRARY_AND_MENTAL_MODEL))
        // Older replies and older servers say nothing: no note either.
        assertNull(agentNoteReplySourcesNote(null))
    }
}
