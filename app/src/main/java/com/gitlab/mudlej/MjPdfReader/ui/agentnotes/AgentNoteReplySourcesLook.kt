// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNote
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplySources

/** How the reply box, the choice and a past reply name what the answering agent may use. */
@StringRes
fun agentNoteReplySourcesLabel(sources: AgentNoteReplySources): Int = when (sources) {
    AgentNoteReplySources.NONE -> R.string.agent_note_sources_none
    AgentNoteReplySources.LINKED_PDF -> R.string.agent_note_sources_linked_pdf
    AgentNoteReplySources.LIBRARY -> R.string.agent_note_sources_library
    AgentNoteReplySources.LIBRARY_AND_MENTAL_MODEL -> R.string.agent_note_sources_library_and_mental_model
}

/** One entry of the choice: its label, the line that explains it, and whether it is the current one. */
data class AgentNoteReplySourcesChoice(
    val sources: AgentNoteReplySources,
    val title: String,
    val detail: String,
    val checked: Boolean,
)

/**
 * The four choices in their order, [current] checked. "Linked PDF only" names the PDF of [note]
 * when the note says which, and says so when it is linked to none. [string] resolves a string
 * resource with its arguments (Context.getString).
 */
fun agentNoteReplySourcesChoices(
    current: AgentNoteReplySources,
    note: AgentNote?,
    string: (Int, Array<out Any>) -> String,
): List<AgentNoteReplySourcesChoice> = AgentNoteReplySources.entries.map { sources ->
    val filename = note?.documentFilename?.trim()?.takeIf(String::isNotEmpty)
    val linked = filename != null || !note?.documentId.isNullOrBlank()
    val detail = when (sources) {
        AgentNoteReplySources.NONE -> string(R.string.agent_note_sources_none_detail, emptyArray())
        AgentNoteReplySources.LINKED_PDF -> when {
            filename != null -> string(R.string.agent_note_sources_linked_pdf_named_detail, arrayOf(filename))
            linked || note == null -> string(R.string.agent_note_sources_linked_pdf_detail, emptyArray())
            else -> string(R.string.agent_note_sources_linked_pdf_none_detail, emptyArray())
        }
        AgentNoteReplySources.LIBRARY -> string(R.string.agent_note_sources_library_detail, emptyArray())
        AgentNoteReplySources.LIBRARY_AND_MENTAL_MODEL ->
            string(R.string.agent_note_sources_library_and_mental_model_detail, emptyArray())
    }
    AgentNoteReplySourcesChoice(sources, string(agentNoteReplySourcesLabel(sources), emptyArray()), detail, sources == current)
}

/** The reply box's chip: "Sources: All documents + Mental Model". */
fun agentNoteReplySourcesChipText(current: AgentNoteReplySources, string: (Int, Array<out Any>) -> String): String =
    string(R.string.agent_note_sources_chip, arrayOf(string(agentNoteReplySourcesLabel(current), emptyArray())))

/** What the chip says to a screen reader: what it does, and the choice now. */
fun agentNoteReplySourcesChipDescription(current: AgentNoteReplySources, string: (Int, Array<out Any>) -> String): String =
    string(R.string.agent_note_sources_chip_description, arrayOf(string(agentNoteReplySourcesLabel(current), emptyArray())))

/**
 * The short note on a past reply ("Linked PDF only"), for a choice other than the default; null
 * for the default, and for a reply that does not say (older ones, older servers).
 */
@StringRes
fun agentNoteReplySourcesNote(sources: AgentNoteReplySources?): Int? =
    sources?.takeIf { it != AgentNoteReplySources.DEFAULT }?.let(::agentNoteReplySourcesLabel)
