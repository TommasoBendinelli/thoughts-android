// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentNoteListeningCacheTest {
    private var now = 1_000_000L
    private val cache = AgentNoteListeningCache(nowMillis = { now })
    private val asked = mutableListOf<String>()
    private var server = AgentNoteListeningAnswer(AgentNoteListening.LISTENING, AgentNoteCoverer(AgentNoteCoverer.SENDER))

    /** One visit of a note's screen. */
    private fun visit(noteId: String): AgentNoteListeningAnswer = cache.forVisit(noteId) { asked += noteId; server }

    @Test
    fun pagingBackAndForthThroughNotesAsksOncePerNote() {
        // Five notes, paged through three times within a minute: fifteen visits.
        repeat(3) {
            for (note in 1..5) {
                assertEquals(AgentNoteListening.LISTENING, visit("note-$note").state)
                now += 2_000
            }
        }
        assertEquals(listOf("note-1", "note-2", "note-3", "note-4", "note-5"), asked)
    }

    @Test
    fun anAnswerIsKeptForOneMinuteAndThenAskedForAgain() {
        visit("note-1")
        now += AgentNoteListeningCache.KEEP_MILLIS - 1
        visit("note-1")
        assertEquals(1, asked.size)
        now += 1
        server = AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING)
        assertEquals(AgentNoteListening.NOT_LISTENING, visit("note-1").state)
        assertEquals(2, asked.size)
        assertEquals(60_000L, AgentNoteListeningCache.KEEP_MILLIS)
    }

    @Test
    fun anAnswerThatSaysNothingIsNotKept() {
        server = AgentNoteListeningAnswer.UNKNOWN
        assertEquals(AgentNoteListening.UNKNOWN, visit("note-1").state)
        server = AgentNoteListeningAnswer(AgentNoteListening.LISTENING)
        assertEquals(AgentNoteListening.LISTENING, visit("note-1").state)
        assertEquals(2, asked.size)
    }

    @Test
    fun aFailedRequestKeepsNothingAndTheNextVisitAsksAgain() {
        val failed = runCatching { cache.forVisit("note-1") { error("offline") } }
        assertEquals("offline", failed.exceptionOrNull()?.message)
        assertNull(cache.fresh("note-1"))
        visit("note-1")
        assertEquals(listOf("note-1"), asked)
    }

    @Test
    fun theServersAnswerToAReplyIsTheNewestAnswerForItsNote() {
        visit("note-1")
        cache.keep("note-1", AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING))
        assertEquals(AgentNoteListening.NOT_LISTENING, visit("note-1").state)
        assertEquals(1, asked.size)
        // Each note has its own answer.
        assertNull(cache.fresh("note-2"))
    }

    /** The screen of one note: [follow] until [minutes] of rechecks have passed, with the clock moving as it sleeps. */
    private fun stayOn(noteId: String, minutes: Int, shown: MutableList<AgentNoteListening>, failed: MutableList<String> = mutableListOf()) {
        var sleeps = 0
        val left = runCatching {
            kotlinx.coroutines.runBlocking {
                cache.follow(
                    noteId,
                    ask = { asked += noteId; server.also { if (it.state == AgentNoteListening.UNKNOWN) error("offline") } },
                    show = { shown += it.state },
                    failed = { failed += it.message.orEmpty() },
                    sleep = { millis ->
                        if (sleeps++ == minutes) throw kotlinx.coroutines.CancellationException("the screen is no longer visible")
                        now += millis
                        whileOnScreen(sleeps)
                    },
                )
            }
        }.exceptionOrNull()
        // Following ends only by being cancelled.
        assertEquals(true, left is kotlinx.coroutines.CancellationException)
    }

    private var whileOnScreen: (minute: Int) -> Unit = {}

    @Test
    fun aNoteThatStaysOpenAsksTheServerEveryMinuteAndFollowsAnAgentThatStarts() {
        server = AgentNoteListeningAnswer(AgentNoteListening.NOT_LISTENING)
        // An agent is started on another device during the third minute.
        whileOnScreen = { minute -> if (minute == 3) server = AgentNoteListeningAnswer(AgentNoteListening.LISTENING) }
        val shown = mutableListOf<AgentNoteListening>()
        stayOn("note-1", minutes = 5, shown = shown)
        val no = AgentNoteListening.NOT_LISTENING
        val yes = AgentNoteListening.LISTENING
        // Opening it, then once a minute: the bar goes without reopening the note.
        assertEquals(listOf(no, no, no, yes, yes, yes), shown)
        // Six requests in five minutes; at 20 s there were sixteen.
        assertEquals(6, asked.size)
        assertEquals(60_000L, AgentNoteListeningCache.RECHECK_MILLIS)
    }

    @Test
    fun theMinutelyCheckGoesToTheServerAlthoughAnAnswerIsKept() {
        val shown = mutableListOf<AgentNoteListening>()
        // Asked about 10 s ago on another visit: opening it uses the kept answer ...
        visit("note-1")
        now += 10_000
        stayOn("note-1", minutes = 2, shown = shown)
        // ... and each minutely check asks all the same (the kept one would be 50 s old at the first).
        assertEquals(listOf("note-1", "note-1", "note-1"), asked)
        assertEquals(3, shown.size)
        // What the last check learnt is kept for paging: coming back within a minute asks nothing.
        now += 5_000
        visit("note-1")
        assertEquals(3, asked.size)
    }

    @Test
    fun aCheckThatFailsLeavesWhatIsShownAndTheNextOneAsksAgain() {
        val shown = mutableListOf<AgentNoteListening>()
        val failed = mutableListOf<String>()
        whileOnScreen = { minute ->
            server = if (minute == 1) AgentNoteListeningAnswer.UNKNOWN else AgentNoteListeningAnswer(AgentNoteListening.LISTENING)
        }
        stayOn("note-1", minutes = 2, shown = shown, failed = failed)
        assertEquals(listOf(AgentNoteListening.LISTENING, AgentNoteListening.LISTENING), shown)
        assertEquals(listOf("offline"), failed)
        assertEquals(3, asked.size)
    }

    private val sender = AgentNoteAgent(AgentNoteCoverer.SENDER, "example-mac", "c-1", live = true)
    private val replyAgent = AgentNoteAgent(AgentNoteCoverer.RESPONDER, "example-server", live = true)

    @Test
    fun aChoiceIsKeptAsTheNotesAnswerAndARefusedOneLeavesWhatWasKept() {
        server = AgentNoteListeningAnswer(AgentNoteListening.LISTENING, candidates = listOf(sender, replyAgent))
        visit("note-1")
        val puts = mutableListOf<AgentNoteAgent?>()
        val chosen = cache.choose("note-1", replyAgent) { agent ->
            puts += agent
            AgentNoteListeningAnswer(AgentNoteListening.LISTENING, answerBy = agent, candidates = listOf(sender, replyAgent))
        }
        assertEquals(replyAgent, chosen.answerBy)
        assertEquals(listOf<AgentNoteAgent?>(replyAgent), puts)
        // The line and paging read the choice from the kept answer, without asking again.
        assertEquals(replyAgent, visit("note-1").answerBy)
        assertEquals(1, asked.size)
        // The server refuses (or cannot be reached): the kept answer, and so the choice, stays.
        val refused = runCatching { cache.choose("note-1", null) { error("409") } }
        assertEquals("409", refused.exceptionOrNull()?.message)
        assertEquals(replyAgent, cache.fresh("note-1")?.answerBy)
    }

    @Test
    fun theAnswerToAReplyKeepsTheChoiceAndTheAgentsToChooseFrom() {
        cache.keep("note-1", AgentNoteListeningAnswer(AgentNoteListening.LISTENING, AgentNoteCoverer(AgentNoteCoverer.RESPONDER),
            answerBy = replyAgent, candidates = listOf(sender, replyAgent)))
        val after = cache.keepAfterReply("note-1", AgentNoteListening.NOT_LISTENING, null)
        assertEquals(AgentNoteListening.NOT_LISTENING, after?.state)
        assertEquals(replyAgent, after?.answerBy)
        assertEquals(listOf(sender, replyAgent), cache.fresh("note-1")?.candidates)
        // A reply's answer that says nothing changes nothing; one for a note not asked about is kept as it is.
        assertNull(cache.keepAfterReply("note-1", AgentNoteListening.UNKNOWN, null))
        assertEquals(AgentNoteListeningAnswer(AgentNoteListening.LISTENING, AgentNoteCoverer(AgentNoteCoverer.SENDER)),
            cache.keepAfterReply("note-2", AgentNoteListening.LISTENING, AgentNoteCoverer(AgentNoteCoverer.SENDER)))
    }

    @Test
    fun theSessionAWakerWakesIsKeptWithTheAnswer() {
        val wake = AgentNoteWake(AgentNoteWake.WILL_BE_WOKEN, AgentNoteCoverer.SENDER, "mac", "Chapter draft", "closed")
        cache.keep("note-1", AgentNoteListeningAnswer(AgentNoteListening.LISTENING, AgentNoteCoverer(AgentNoteCoverer.RESPONDER),
            answerBy = replyAgent, candidates = listOf(sender, replyAgent)))
        val after = cache.keepAfterReply("note-1", AgentNoteListening.LISTENING, AgentNoteCoverer(AgentNoteCoverer.RESPONDER), wake)
        assertEquals(wake, after?.wake)
        assertEquals(replyAgent, cache.fresh("note-1")?.answerBy)
        // The next reply's answer names nobody to wake: none is kept.
        assertNull(cache.keepAfterReply("note-1", AgentNoteListening.LISTENING, null)?.wake)
        // An answer that cannot tell who listens is still kept when it names a session to wake.
        cache.keep("note-3", AgentNoteListeningAnswer(AgentNoteListening.UNKNOWN, wake = wake))
        assertEquals(wake, cache.fresh("note-3")?.wake)
        assertEquals(wake, cache.keepAfterReply("note-4", AgentNoteListening.UNKNOWN, null, wake)?.wake)
        cache.keep("note-5", AgentNoteListeningAnswer.UNKNOWN)
        assertNull(cache.fresh("note-5"))
    }

    @Test
    fun theServersCapabilitiesAreAskedOnceAndAgainLaterOnlyWhileOneIsMissing() {
        var clock = 0L
        var fetches = 0
        var served = setOf("agent_note_listening_v1")
        val capabilities = AgentNoteServerCapabilities(keepMillis = 600_000, nowMillis = { clock })
        fun has() = capabilities.has(AGENT_NOTE_ANSWER_BY_CAPABILITY) { fetches++; served }
        // An older server: no "Change", and it is not asked again at every check.
        assertEquals(false, has())
        clock += 60_000
        assertEquals(false, has())
        assertEquals(1, fetches)
        // It is updated; ten minutes after the first answer the phone asks again and learns it.
        served = served + AGENT_NOTE_ANSWER_BY_CAPABILITY
        clock += 600_000
        assertEquals(true, has())
        assertEquals(2, fetches)
        // Once it can, it is not asked again.
        clock += 6_000_000
        assertEquals(true, has())
        assertEquals(2, fetches)
        // A request that fails keeps nothing.
        val offline = AgentNoteServerCapabilities(nowMillis = { clock })
        assertEquals("offline", runCatching { offline.has(AGENT_NOTE_ANSWER_BY_CAPABILITY) { error("offline") } }.exceptionOrNull()?.message)
        assertEquals(true, offline.has(AGENT_NOTE_ANSWER_BY_CAPABILITY) { served })
    }

    @Test
    fun aClockThatWentBackKeepsNothing() {
        visit("note-1")
        now -= 5_000
        assertNull(cache.fresh("note-1"))
    }
}
