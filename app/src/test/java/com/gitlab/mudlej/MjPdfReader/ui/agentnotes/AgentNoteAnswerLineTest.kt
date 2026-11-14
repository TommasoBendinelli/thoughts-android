// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AGENT_NOTE_ANSWERER_UNKNOWN
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteAgent
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteCoverer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListening
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListeningAnswer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesHttpException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNoteAnswerLineTest {
    /** Resource names with their arguments, so the tests read like the screen. */
    private val strings = mapOf(
        R.string.agent_note_coverer_reply_agent to "Thoughts reply agent",
        R.string.agent_note_coverer_sender to "the agent that sent it",
        R.string.agent_note_coverer_topic to "%1\$s agent",
        R.string.agent_note_coverer_topic_unnamed to "the topic's agent",
        R.string.agent_note_answer_listening to "%1\$s is listening",
        R.string.agent_note_answer_listening_unnamed to "An agent is listening",
        R.string.agent_note_answer_nobody to "No agent is listening right now",
        R.string.agent_note_answer_chosen_not_listening to
            "%1\$s is not listening right now. The Thoughts reply agent answers after 10 minutes.",
        R.string.agent_note_answer_automatic to "Automatic",
        R.string.agent_note_answer_automatic_detail to
            "The agent that sent the note answers first, then the Thoughts reply agent",
        R.string.agent_note_answer_choice_not_listening to "%1\$s (not listening)",
    )
    private val string: (Int, Array<out Any>) -> String = { id, args -> String.format(strings.getValue(id), *args) }

    private val sender = AgentNoteAgent(AgentNoteCoverer.SENDER, "example-mac", "c-1", live = true)
    private val owner = AgentNoteAgent(AgentNoteCoverer.OWNER, "example-server", "c-2", "o-1", "Course notes", live = true)
    private val replyAgent = AgentNoteAgent(AgentNoteCoverer.RESPONDER, "example-server", live = true)

    private fun line(answer: AgentNoteListeningAnswer?, canChange: Boolean = true, reachable: Boolean = true) =
        agentNoteAnswerLine(answer, canChange, reachable, string)

    @Test
    fun theLineNamesTheAgentThatListens() {
        fun listening(coverer: AgentNoteCoverer?) = line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, coverer))
        assertEquals("Thoughts reply agent is listening", listening(replyAgent.coverer)?.text)
        assertEquals("Course notes agent is listening", listening(owner.coverer)?.text)
        // A sentence starts with a capital.
        assertEquals("The agent that sent it is listening", listening(sender.coverer)?.text)
        assertEquals("The topic's agent is listening", listening(AgentNoteCoverer(AgentNoteCoverer.OWNER))?.text)
        // Listening, but the server names nobody.
        assertEquals("An agent is listening", listening(null)?.text)
        val line = listening(replyAgent.coverer)!!
        assertEquals(AgentNoteAnswerMark.LISTENING, line.mark)
        assertFalse(line.warning)
    }

    @Test
    fun withAChoiceTheChosenAgentIsNamedNotTheFirstListener() {
        // The sender's agent also listens, but the user chose the reply agent, and it is live.
        val answer = AgentNoteListeningAnswer(AgentNoteListening.LISTENING, sender.coverer, answerBy = replyAgent,
            candidates = listOf(sender, replyAgent))
        assertEquals("Thoughts reply agent is listening", line(answer)?.text)
    }

    @Test
    fun nobodyListeningHasTheWarningLookOfTheOldBar() {
        val line = line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING))!!
        assertEquals("No agent is listening right now", line.text)
        assertEquals(AgentNoteAnswerMark.NOBODY, line.mark)
        assertTrue(line.warning)
    }

    @Test
    fun aChosenAgentThatIsNotLiveSaysWhoAnswersInstead() {
        val line = line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, answerBy = owner.copy(live = false)))!!
        assertEquals("Course notes agent is not listening right now. The Thoughts reply agent answers after 10 minutes.", line.text)
        assertEquals(AgentNoteAnswerMark.WAITING, line.mark)
        assertFalse(line.warning)
        assertEquals("The agent that sent it is not listening right now. The Thoughts reply agent answers after 10 minutes.",
            line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, answerBy = sender.copy(live = false)))?.text)
    }

    @Test
    fun aServerThatCannotTellOrHasNotAnsweredGetsNoLine() {
        assertNull(line(null))
        assertNull(line(AgentNoteListeningAnswer.UNKNOWN))
        assertNull(line(AgentNoteListeningAnswer(AgentNoteListening.UNKNOWN, replyAgent.coverer, answerBy = replyAgent)))
    }

    @Test
    fun changeIsOfferedOnlyByAServerThatCanAndUsableOnlyWhileItIsReached() {
        val answer = AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer)
        // An older server: the same line from the fields it sends, without "Change".
        val older = line(answer, canChange = false)!!
        assertEquals("Thoughts reply agent is listening", older.text)
        assertFalse(older.changeOffered)
        assertFalse(older.changeEnabled)
        val current = line(answer, canChange = true)!!
        assertTrue(current.changeOffered && current.changeEnabled)
        // Offline: the line stays as last known, "Change" is shown but off.
        val offline = line(answer, canChange = true, reachable = false)!!
        assertTrue(offline.changeOffered)
        assertFalse(offline.changeEnabled)
        for (state in listOf(AgentNoteListening.LISTENING, AgentNoteListening.NOT_LISTENING)) {
            assertTrue(line(AgentNoteListeningAnswer(state, answerBy = owner))!!.changeOffered)
        }
    }

    @Test
    fun theListOffersAutomaticAndEveryAgentThatCouldAnswerWithTheChoiceChecked() {
        val automatic = agentNoteAnswerChoices(
            AgentNoteListeningAnswer(AgentNoteListening.LISTENING, sender.coverer, candidates = listOf(sender, owner, replyAgent)), string)
        assertEquals(listOf("Automatic", "The agent that sent it", "Course notes agent", "Thoughts reply agent"), automatic.map { it.title })
        assertEquals("The agent that sent the note answers first, then the Thoughts reply agent", automatic.first().detail)
        assertTrue(automatic.drop(1).all { it.detail == null })
        assertNull(automatic.first().agent)
        assertEquals(listOf(true, false, false, false), automatic.map { it.checked })
        // The reply agent is chosen: it is the checked one, and what is sent for a choice is the agent.
        val chosen = agentNoteAnswerChoices(
            AgentNoteListeningAnswer(AgentNoteListening.LISTENING, answerBy = replyAgent, candidates = listOf(sender, owner, replyAgent)), string)
        assertEquals(listOf(false, false, false, true), chosen.map { it.checked })
        assertEquals(replyAgent, chosen.last().agent)
        assertEquals(1, chosen.count { it.checked })
    }

    @Test
    fun aChosenAgentThatIsNotListeningIsStillInTheListAndChecked() {
        val asleep = owner.copy(live = false)
        val choices = agentNoteAnswerChoices(
            AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, answerBy = asleep, candidates = listOf(replyAgent)), string)
        assertEquals(listOf("Automatic", "Thoughts reply agent", "Course notes agent (not listening)"), choices.map { it.title })
        assertEquals(listOf(false, false, true), choices.map { it.checked })
        // Nobody could answer and nothing is chosen: Automatic alone, checked.
        val alone = agentNoteAnswerChoices(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING), string)
        assertEquals(listOf("Automatic"), alone.map { it.title })
        assertTrue(alone.single().checked)
    }

    @Test
    fun twoAgentsThatReadTheSameAreToldApartAndTheSameAgentIsListedOnce() {
        val second = sender.copy(deviceId = "example-phone", conversationId = "c-9")
        val choices = agentNoteAnswerChoices(
            AgentNoteListeningAnswer(AgentNoteListening.LISTENING, candidates = listOf(sender, sender.copy(label = "x"), second)), string)
        assertEquals(listOf("Automatic", "The agent that sent it", "The agent that sent it (2)"), choices.map { it.title })
        assertEquals(second, choices.last().agent)
    }

    @Test
    fun aChoiceThatCouldNotBeSavedSaysWhy() {
        assertEquals(R.string.agent_note_answer_agent_unknown,
            agentNoteAnswerChangeFailure(AgentNotesHttpException(409, AGENT_NOTE_ANSWERER_UNKNOWN, "That agent is not known")))
        assertEquals(R.string.agent_note_answer_change_failed, agentNoteAnswerChangeFailure(AgentNotesHttpException(503, null, "busy")))
        assertEquals(R.string.agent_note_answer_change_failed, agentNoteAnswerChangeFailure(IOException("no route")))
    }
}
