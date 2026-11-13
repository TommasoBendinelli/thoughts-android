// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNoteMessageLookTest {
    @Test fun collapsedShowsOneLineAndOffersTheWholeMessage() {
        val look = agentNoteMessageLook(collapsed = true)
        assertEquals(1, look.maxLines)
        assertEquals(R.drawable.ic_small_arrow_down, look.chevron)
        assertEquals(R.string.agent_note_message_expand, look.action)
        assertEquals(R.string.agent_note_message_collapsed, look.state)
    }

    @Test fun expandedShowsEveryLineAndOffersOneLine() {
        val look = agentNoteMessageLook(collapsed = false)
        assertEquals(Int.MAX_VALUE, look.maxLines)
        assertEquals(R.drawable.ic_small_arrow_up, look.chevron)
        assertEquals(R.string.agent_note_message_collapse, look.action)
        assertEquals(R.string.agent_note_message_expanded, look.state)
    }

    @Test fun onlyAMessageLongerThanOneLineCanBeSwitched() {
        // Collapsed: the one line is cut short (or a line break hides the rest).
        assertTrue(agentNoteMessageIsLong(collapsed = true, lineCount = 1, ellipsized = 42))
        assertTrue(agentNoteMessageIsLong(collapsed = true, lineCount = 2, ellipsized = 0))
        assertFalse(agentNoteMessageIsLong(collapsed = true, lineCount = 1, ellipsized = 0))
        // Expanded: it wraps, or it fits on one line and there is nothing to shrink.
        assertTrue(agentNoteMessageIsLong(collapsed = false, lineCount = 4, ellipsized = 0))
        assertFalse(agentNoteMessageIsLong(collapsed = false, lineCount = 1, ellipsized = 0))
    }
}
