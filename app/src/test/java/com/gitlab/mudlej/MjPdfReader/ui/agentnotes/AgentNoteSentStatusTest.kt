// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListening
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentNoteSentStatusTest {
    @Test fun waitingForTheAgentOnlyWhenAnAgentListens() {
        assertEquals(R.string.agent_note_sent_waiting, agentNoteSentStatus(true, AgentNoteListening.LISTENING))
        assertEquals(R.string.agent_note_sent_not_listening, agentNoteSentStatus(true, AgentNoteListening.NOT_LISTENING))
        // An older server, or agents that never say: no claim either way.
        assertEquals(R.string.agent_note_sent_plain, agentNoteSentStatus(true, AgentNoteListening.UNKNOWN))
        for (listening in AgentNoteListening.entries) {
            assertEquals(R.string.agent_note_queued, agentNoteSentStatus(false, listening))
        }
    }

    @Test fun statesFromTheServer() {
        assertEquals(AgentNoteListening.LISTENING, AgentNoteListening.fromState("listening"))
        assertEquals(AgentNoteListening.NOT_LISTENING, AgentNoteListening.fromState("not_listening"))
        assertEquals(AgentNoteListening.UNKNOWN, AgentNoteListening.fromState("unknown"))
        assertEquals(AgentNoteListening.UNKNOWN, AgentNoteListening.fromState(null))
        assertEquals(AgentNoteListening.UNKNOWN, AgentNoteListening.fromState("something new"))
    }
}
