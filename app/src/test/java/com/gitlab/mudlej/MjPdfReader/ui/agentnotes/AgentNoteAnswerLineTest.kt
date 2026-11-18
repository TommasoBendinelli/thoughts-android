// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AGENT_NOTE_ANSWERER_UNKNOWN
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteAgent
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteCoverer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListening
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListeningAnswer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteWake
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
        R.string.agent_note_machine_mac to "the Mac",
        R.string.agent_note_machine_server to "the server",
        R.string.agent_note_wake_fallback to "The study session",
        R.string.agent_note_wake_woken to "%1\$s will be woken on %2\$s",
        R.string.agent_note_wake_fallback_woken to "%1\$s will be woken",
        R.string.agent_note_wake_busy to "%1\$s answers when it finishes its current task",
        R.string.agent_note_wake_offline to "%1\$s is offline; the study session answers at %2\$s if it is still offline",
        R.string.agent_note_wake_offline_later to "%1\$s is offline; the study session answers later if it is still offline",
        R.string.agent_note_answered_by to "Answered by: %1\$s",
        R.string.agent_note_answered_by_nobody to "nobody is listening",
    )
    private val string: (Int, Array<out Any>) -> String = { id, args -> String.format(strings.getValue(id), *args) }

    private val sender = AgentNoteAgent(AgentNoteCoverer.SENDER, "example-mac", "c-1", live = true)
    private val owner = AgentNoteAgent(AgentNoteCoverer.OWNER, "example-server", "c-2", "o-1", "Course notes", live = true)
    private val replyAgent = AgentNoteAgent(AgentNoteCoverer.RESPONDER, "example-server", live = true)

    /** A local time of day, as the screen writes it; UTC here so the tests read the same everywhere. */
    private val time: (Long) -> String = { millis ->
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(millis))
    }

    private fun line(answer: AgentNoteListeningAnswer?, canChange: Boolean = true, reachable: Boolean = true) =
        agentNoteAnswerLine(answer, canChange, reachable, string, time)

    /** The note's own session on the Mac, which the Mac's waker wakes; the fallback session on the server. */
    private val onTheMac = AgentNoteWake(AgentNoteWake.WILL_BE_WOKEN, AgentNoteCoverer.SENDER, "mac", "Chapter draft", "closed")
    private val onTheServer = onTheMac.copy(machine = "example-server", title = "Figure review", sessionState = "idle")
    private val fallback = AgentNoteWake(AgentNoteWake.WILL_BE_WOKEN, AgentNoteCoverer.RESPONDER, "example-server", "The study session", "idle")
    private val macAsleep = onTheMac.copy(state = AgentNoteWake.WAKER_OFFLINE, fallbackAt = "2026-11-15T02:14:00.123456Z")

    @Test
    fun theChipOverTheCommentSaysWhoAnswers() {
        fun chip(answer: AgentNoteListeningAnswer?) = agentNoteAnsweredByChipText(answer, string)
        // Automatic: whoever listens now, else the session a waker wakes.
        assertEquals("Answered by: the agent that sent it",
            chip(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, sender.coverer)))
        assertEquals("Answered by: Chapter draft", chip(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = onTheMac)))
        assertEquals("Answered by: The study session", chip(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = fallback)))
        // A chosen agent is named as chosen, listening or not.
        assertEquals("Answered by: Course notes agent",
            chip(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, sender.coverer, answerBy = owner)))
        assertEquals("Answered by: Course notes agent",
            chip(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, answerBy = owner.copy(live = false))))
        assertEquals("Answered by: nobody is listening", chip(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING)))
        // Nothing to say yet, or a server that cannot tell: no chip.
        assertNull(chip(null))
        assertNull(chip(AgentNoteListeningAnswer.UNKNOWN))
    }

    @Test
    fun onlyALineThatNeedsAttentionShowsUnderTheTitle() {
        // The usual: an agent listens, or one will be woken (the note's own session, or the study session).
        assertTrue(line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer))!!.usual)
        assertTrue(line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = fallback))!!.usual)
        assertTrue(line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = onTheMac))!!.usual)
        // Worth a line: nobody acts on a reply, or the answer comes late because a machine is offline.
        assertFalse(line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING))!!.usual)
        assertFalse(line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = macAsleep))!!.usual)
        assertFalse(line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, answerBy = owner.copy(live = false)))!!.usual)
    }

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

    @Test
    fun aSessionAWakerWakesIsNamedWithItsMachineInTheNormalLook() {
        // The server's own waker listens as the reply agent, so older apps still see an agent listening.
        val responderListens = AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer, wake = onTheMac)
        val mac = line(responderListens)!!
        assertEquals("Chapter draft will be woken on the Mac", mac.text)
        assertEquals(AgentNoteAnswerMark.WOKEN, mac.mark)
        assertFalse(mac.warning)
        assertFalse(mac.muted)
        assertTrue(mac.changeOffered && mac.changeEnabled)
        // No agent listens at all: not "No agent is listening", since the session will be woken.
        val server = line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = onTheServer))!!
        assertEquals("Figure review will be woken on the server", server.text)
        assertFalse(server.warning)
        // The fallback session, the one that answers what has no other home, is named without its machine.
        assertEquals("The study session will be woken", line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer,
            wake = fallback))?.text)
        // A session without a title is named as its agent.
        assertEquals("The agent that sent it will be woken on the Mac",
            line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = onTheMac.copy(title = null)))?.text)
        assertEquals("Course notes agent will be woken on the server", line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING,
            wake = onTheServer.copy(role = AgentNoteCoverer.OWNER, title = null, label = "Course notes")))?.text)
    }

    @Test
    fun aBusySessionAnswersWhenItsTaskIsDone() {
        val busy = line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer, wake = onTheMac.copy(sessionState = "busy")))!!
        assertEquals("Chapter draft answers when it finishes its current task", busy.text)
        assertEquals(AgentNoteAnswerMark.WOKEN, busy.mark)
        assertFalse(busy.warning)
        assertEquals("The study session answers when it finishes its current task",
            line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer, wake = fallback.copy(sessionState = "busy")))?.text)
    }

    @Test
    fun anOfflineMachineSaysWhenAgentStudyAnswersInTheMutedLook() {
        val offline = line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer, wake = macAsleep))!!
        assertEquals("The Mac is offline; the study session answers at 02:14 if it is still offline", offline.text)
        assertEquals(AgentNoteAnswerMark.OFFLINE, offline.mark)
        assertTrue(offline.muted)
        assertFalse(offline.warning)
        assertTrue(offline.changeOffered)
        assertEquals("The server is offline; the study session answers at 02:14 if it is still offline",
            line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = macAsleep.copy(machine = "example-server")))?.text)
        // No time from the server (or one that cannot be read): the line says it without one.
        assertEquals("The Mac is offline; the study session answers later if it is still offline",
            line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, wake = macAsleep.copy(fallbackAt = "soon")))?.text)
    }

    @Test
    fun anAgentThatListensIsStillNamedBeforeTheSessionAWakerWakes() {
        // The note's own agent listens: it hears the reply by itself.
        assertEquals("The agent that sent it is listening",
            line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, sender.coverer, wake = onTheMac))?.text)
        assertEquals("Course notes agent is listening",
            line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, owner.coverer, wake = onTheServer))?.text)
        // The user chose an agent that listens.
        assertEquals("The agent that sent it is listening", line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING,
            replyAgent.coverer, answerBy = sender, wake = onTheMac))?.text)
    }

    @Test
    fun aChosenSessionAWakerWakesIsNamedInsteadOfTheTenMinutes() {
        val chosen = line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING, answerBy = sender.copy(live = false), wake = onTheMac))!!
        assertEquals("Chapter draft will be woken on the Mac", chosen.text)
        assertEquals(AgentNoteAnswerMark.WOKEN, chosen.mark)
        // The user chose the reply agent: the study session is who the server's waker wakes.
        assertEquals("The study session will be woken", line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer,
            answerBy = replyAgent, wake = fallback))?.text)
    }

    @Test
    fun withoutAWakeTheLineIsAsBefore() {
        // An older server, or no waker would wake anyone: the same lines as before.
        val nobody = line(AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING))!!
        assertEquals("No agent is listening right now", nobody.text)
        assertTrue(nobody.warning)
        assertFalse(nobody.muted)
        assertEquals("Thoughts reply agent is listening", line(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, replyAgent.coverer))?.text)
        // A server that cannot tell who listens, but names a session a waker wakes: the line says so.
        assertNull(line(AgentNoteListeningAnswer(AgentNoteListening.UNKNOWN)))
        assertEquals("Chapter draft will be woken on the Mac", line(AgentNoteListeningAnswer(AgentNoteListening.UNKNOWN, wake = onTheMac))?.text)
    }

    @Test
    fun theWakeIsReadFromTheListeningAnswer() {
        fun answer(json: String) = AgentNoteListeningAnswer.fromJson(com.google.gson.JsonParser.parseString(json).asJsonObject)
        val wake = answer("""{"state":"listening","listeners":[],"wake":{"state":"waker_offline","role":"sender",
            "device_id":"example-mac","machine":"mac","conversation_id":"0190a000-0000-7000-8000-00000000000a",
            "title":"  Chapter draft  ","session_state":"closed","owner_id":null,"label":null,"waker_live":false,
            "fallback_at":"2026-11-15T02:14:00Z"}}""").wake
        assertEquals(AgentNoteWake(AgentNoteWake.WAKER_OFFLINE, AgentNoteCoverer.SENDER, "mac", "Chapter draft", "closed",
            fallbackAt = "2026-11-15T02:14:00Z"), wake)
        assertEquals(1_794_708_840_000L, wake?.fallbackAtMillis)
        // An older server has no wake; one this phone cannot say is none either.
        assertNull(answer("""{"state":"not_listening","listeners":[]}""").wake)
        assertNull(answer("""{"state":"not_listening","listeners":[],"wake":null}""").wake)
        assertNull(answer("""{"state":"not_listening","listeners":[],"wake":{"state":"something new","role":"sender","machine":"mac"}}""").wake)
        assertNull(answer("""{"state":"not_listening","listeners":[],"wake":{"state":"will_be_woken","role":"sender"}}""").wake)
        assertNull(answer("""{"state":"not_listening","listeners":[],"wake":{"state":"will_be_woken","role":"other","machine":"mac"}}""").wake)
    }
}
