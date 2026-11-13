// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.R

/**
 * How the note's message above the picture looks: one line while [collapsed], all of it otherwise.
 * [chevron] points where a tap takes it, [action] names that tap and [state] says what is shown (TalkBack).
 */
data class AgentNoteMessageLook(
    val maxLines: Int,
    @DrawableRes val chevron: Int,
    @StringRes val action: Int,
    @StringRes val state: Int,
)

fun agentNoteMessageLook(collapsed: Boolean): AgentNoteMessageLook =
    if (collapsed) {
        AgentNoteMessageLook(1, R.drawable.ic_small_arrow_down, R.string.agent_note_message_expand, R.string.agent_note_message_collapsed)
    } else {
        AgentNoteMessageLook(Int.MAX_VALUE, R.drawable.ic_small_arrow_up, R.string.agent_note_message_collapse, R.string.agent_note_message_expanded)
    }

/**
 * Whether the message is longer than one line, so a tap changes something: cut short while collapsed
 * ([ellipsized] characters hidden on its one line), or more than one line while expanded.
 */
fun agentNoteMessageIsLong(collapsed: Boolean, lineCount: Int, ellipsized: Int): Boolean =
    if (collapsed) ellipsized > 0 || lineCount > 1 else lineCount > 1
