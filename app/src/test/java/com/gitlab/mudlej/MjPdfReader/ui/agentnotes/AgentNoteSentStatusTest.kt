// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteCoverer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListening
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListeningAnswer
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** Resource names with their arguments, so the tests read like the screen. */
    private val strings = mapOf(
        R.string.agent_note_sent_answered_by to "Sent ✓ · answered by %1\$s",
        R.string.agent_note_answered_by to "Answered by: %1\$s",
        R.string.agent_note_coverer_reply_agent to "Thoughts reply agent",
        R.string.agent_note_coverer_sender to "the agent that sent it",
        R.string.agent_note_coverer_topic to "%1\$s agent",
        R.string.agent_note_coverer_topic_unnamed to "the topic's agent",
        R.string.agent_note_sent_waiting to "Sent ✓ · waiting for the agent",
        R.string.agent_note_sent_not_listening to "Sent ✓ · no agent is listening",
        R.string.agent_note_sent_plain to "Sent ✓",
        R.string.agent_note_queued to "Queued",
    )
    private val string: (Int, Array<out Any>) -> String = { id, args -> String.format(strings.getValue(id), *args) }

    private fun answer(json: String) = AgentNoteListeningAnswer.fromJson(JsonParser.parseString(json).asJsonObject)

    @Test fun theListenerThatAnswersFirstIsNamed() {
        val responder = """{"device_id":"example-server","conversation_id":null,"note_id":null,"responder":true,"role":"responder","owner_id":null,"label":null}"""
        val owner = """{"device_id":"example-server","conversation_id":"c-2","note_id":null,"responder":false,"role":"owner","owner_id":"o-1","label":"Course notes"}"""
        val sender = """{"device_id":"thoughts-mac","conversation_id":"c-1","note_id":null,"responder":false,"role":"sender","owner_id":null,"label":null}"""
        // The reply agent waits for the note's own agent and for topic owners (protocol "Who answers").
        assertEquals(AgentNoteCoverer(AgentNoteCoverer.RESPONDER), answer("""{"state":"listening","listeners":[$responder]}""").coverer)
        assertEquals(AgentNoteCoverer(AgentNoteCoverer.OWNER, "Course notes"),
            answer("""{"state":"listening","listeners":[$responder,$owner]}""").coverer)
        assertEquals(AgentNoteCoverer(AgentNoteCoverer.SENDER), answer("""{"state":"listening","listeners":[$responder,$owner,$sender]}""").coverer)
        // A server before topic owners: no role; responder true is the reply agent.
        assertEquals(AgentNoteCoverer(AgentNoteCoverer.RESPONDER),
            answer("""{"state":"listening","listeners":[{"device_id":"example-server","responder":true}]}""").coverer)
        assertEquals(AgentNoteCoverer(AgentNoteCoverer.SENDER),
            answer("""{"state":"listening","listeners":[{"device_id":"thoughts-mac","conversation_id":"c-1"}]}""").coverer)
        // Nobody listening, or a server that cannot tell: nobody is named.
        assertNull(answer("""{"state":"not_listening","listeners":[]}""").coverer)
        assertNull(answer("""{"state":"listening","listeners":[{"role":"something new"}]}""").coverer)
        assertNull(AgentNoteListeningAnswer.fromJson(null).coverer)
        assertEquals(AgentNoteListening.LISTENING, answer("""{"state":"listening","listeners":[$owner]}""").state)
    }

    @Test fun theScreenSaysWhoAnswers() {
        val replyAgent = AgentNoteCoverer(AgentNoteCoverer.RESPONDER)
        val owner = AgentNoteCoverer(AgentNoteCoverer.OWNER, "Course notes")
        val sender = AgentNoteCoverer(AgentNoteCoverer.SENDER)
        assertEquals("Answered by: Thoughts reply agent", agentNoteAnsweredByText(AgentNoteListening.LISTENING, replyAgent, string))
        assertEquals("Answered by: Course notes agent", agentNoteAnsweredByText(AgentNoteListening.LISTENING, owner, string))
        assertEquals("Answered by: the topic's agent",
            agentNoteAnsweredByText(AgentNoteListening.LISTENING, AgentNoteCoverer(AgentNoteCoverer.OWNER), string))
        assertEquals("Answered by: the agent that sent it", agentNoteAnsweredByText(AgentNoteListening.LISTENING, sender, string))
        assertNull(agentNoteAnsweredByText(AgentNoteListening.NOT_LISTENING, replyAgent, string))
        assertNull(agentNoteAnsweredByText(AgentNoteListening.LISTENING, null, string))

        assertEquals("Sent ✓ · answered by Thoughts reply agent", agentNoteSentText(true, AgentNoteListening.LISTENING, replyAgent, string))
        assertEquals("Sent ✓ · waiting for the agent", agentNoteSentText(true, AgentNoteListening.LISTENING, null, string))
        assertEquals("Sent ✓ · no agent is listening", agentNoteSentText(true, AgentNoteListening.NOT_LISTENING, null, string))
        assertEquals("Queued", agentNoteSentText(false, AgentNoteListening.LISTENING, replyAgent, string))
    }
}
