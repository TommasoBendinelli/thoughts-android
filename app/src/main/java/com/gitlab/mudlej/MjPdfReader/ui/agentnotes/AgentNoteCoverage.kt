// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteAgent
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteCoverer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListening
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListeningAnswer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteWake

/**
 * Who answers the reply, as the note screen names it: "Thoughts reply agent", a topic owner's
 * label ("Course notes agent"), or the agent that sent the note. [string] resolves a string
 * resource with its arguments (Context.getString).
 */
fun agentNoteCovererName(coverer: AgentNoteCoverer, string: (Int, Array<out Any>) -> String): String =
    when (coverer.role) {
        AgentNoteCoverer.RESPONDER -> string(R.string.agent_note_coverer_reply_agent, emptyArray())
        AgentNoteCoverer.OWNER -> coverer.label?.let { string(R.string.agent_note_coverer_topic, arrayOf(it)) }
            ?: string(R.string.agent_note_coverer_topic_unnamed, emptyArray())
        else -> string(R.string.agent_note_coverer_sender, emptyArray())
    }

/** The line beside the reply field while an agent listens: "Answered by: …"; null when nobody is named. */
fun agentNoteAnsweredByText(
    listening: AgentNoteListening,
    coverer: AgentNoteCoverer?,
    string: (Int, Array<out Any>) -> String,
): String? = coverer?.takeIf { listening == AgentNoteListening.LISTENING }
    ?.let { string(R.string.agent_note_answered_by, arrayOf(agentNoteCovererName(it, string))) }

/** [agentNoteCovererName] as the start of a sentence or a list entry: "The agent that sent it". */
fun agentNoteAgentName(coverer: AgentNoteCoverer, string: (Int, Array<out Any>) -> String): String =
    agentNoteCovererName(coverer, string).replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }

/** The mark at the start of the line that says who listens. */
enum class AgentNoteAnswerMark {
    /** An agent is listening and answers a reply. */
    LISTENING,
    /** A reply waker wakes the session that answers a reply (or messages it while it runs). */
    WOKEN,
    /** The machine of the session that answers is offline; the study session answers later instead. */
    OFFLINE,
    /** The chosen agent is not listening; the Thoughts reply agent answers after a while. */
    WAITING,
    /** Nobody listens: a reply is kept, but no agent acts on it. */
    NOBODY,
}

/**
 * The machine a reply waker runs on, as the screen names it: "the Mac", else "the server" (there
 * are two wakers, and a text never names a host).
 */
fun agentNoteMachineName(machine: String, string: (Int, Array<out Any>) -> String): String =
    string(if (machine.equals(AgentNoteWake.MAC, ignoreCase = true)) R.string.agent_note_machine_mac else R.string.agent_note_machine_server, emptyArray())

/**
 * The session a reply waker wakes, as the screen names it: the study session for the fallback session
 * (role responder), else the session's title, or the agent's name when it has none.
 */
fun agentNoteWokenName(wake: AgentNoteWake, string: (Int, Array<out Any>) -> String): String = when {
    wake.role == AgentNoteCoverer.RESPONDER -> string(R.string.agent_note_wake_fallback, emptyArray())
    else -> wake.title ?: agentNoteAgentName(AgentNoteCoverer(wake.role, wake.label), string)
}

/**
 * What the line says about [wake]: "<title> will be woken on the Mac", "The study session will be woken",
 * "<title> answers when it finishes its current task" while it runs a task, or for an offline
 * machine "The Mac is offline; the study session answers at <[time] of fallback_at> if it is still offline".
 */
fun agentNoteWakeText(wake: AgentNoteWake, time: (Long) -> String, string: (Int, Array<out Any>) -> String): String {
    val name = agentNoteWokenName(wake, string)
    return when {
        !wake.willBeWoken -> {
            val machine = agentNoteMachineName(wake.machine, string).replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
            wake.fallbackAtMillis?.let { string(R.string.agent_note_wake_offline, arrayOf(machine, time(it))) }
                ?: string(R.string.agent_note_wake_offline_later, arrayOf(machine))
        }
        wake.busy -> string(R.string.agent_note_wake_busy, arrayOf(name))
        wake.role == AgentNoteCoverer.RESPONDER -> string(R.string.agent_note_wake_fallback_woken, arrayOf(name))
        else -> string(R.string.agent_note_wake_woken, arrayOf(name, agentNoteMachineName(wake.machine, string)))
    }
}

/**
 * Whether the screen names the listener rather than [wake]: an agent listens ([listening]) and it
 * is not only the responder. The server's own waker listens as the responder so that older apps
 * still see an agent listening; while it does, the session the waker wakes is who answers.
 */
private fun namesTheListener(listening: AgentNoteListening, named: AgentNoteCoverer?, wake: AgentNoteWake?): Boolean =
    listening == AgentNoteListening.LISTENING && (wake == null || named?.role != AgentNoteCoverer.RESPONDER)

/**
 * The line under a note's title that says plainly whether an agent listens for a reply and which
 * one, with "Change" at its end. It replaces the bar that only appeared when nobody listened and
 * the helper text under the reply field.
 */
data class AgentNoteAnswerLine(
    val mark: AgentNoteAnswerMark,
    val text: String,
    /** The warning look the bar had: nobody will act on a reply. */
    val warning: Boolean,
    /** The muted look: the machine that answers is offline, so the answer comes later. */
    val muted: Boolean = false,
    /** "Change" is offered: the server lets the user choose who answers. */
    val changeOffered: Boolean,
    /** "Change" can be used now: the server answered the last check. */
    val changeEnabled: Boolean,
) {
    /**
     * The usual case, nothing to act on: an agent listens or will be woken. The note screen then shows
     * no line under the title (saying the usual on every note took room); who answers is in the note's
     * menu ("Who answers your reply…"), and Send still says who will answer.
     */
    val usual: Boolean get() = mark == AgentNoteAnswerMark.LISTENING || mark == AgentNoteAnswerMark.WOKEN
}

/**
 * What the line says for [answer], or null for no line (no answer yet, or a server that cannot
 * tell). [canChange]: the server has `agent_note_answer_by_v1`; an older one gets the line without
 * "Change", from the fields it sends. [reachable]: the last check reached the server. [time]
 * writes a moment (milliseconds since the epoch) as a local time of day.
 *
 * An agent that listens is named as before. Otherwise, when a reply waker would wake the session
 * that answers (`wake`), the line names it ("… will be woken on the Mac"), or says that its machine
 * is offline and when the study session answers instead. Without `wake` (an older server, or no waker
 * would wake anyone) the line is as before.
 */
fun agentNoteAnswerLine(
    answer: AgentNoteListeningAnswer?,
    canChange: Boolean,
    reachable: Boolean,
    string: (Int, Array<out Any>) -> String,
    time: (Long) -> String = { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) },
): AgentNoteAnswerLine? {
    answer ?: return null
    val chosen = answer.answerBy
    val wake = answer.wake
    // With a choice the server says "listening" exactly while the chosen agent is live.
    val named = chosen?.coverer ?: answer.coverer
    fun line(mark: AgentNoteAnswerMark, text: String, warning: Boolean = false, muted: Boolean = false) =
        AgentNoteAnswerLine(mark, text, warning, muted, changeOffered = canChange, changeEnabled = canChange && reachable)
    return when {
        namesTheListener(answer.state, named, wake) -> line(
            AgentNoteAnswerMark.LISTENING,
            named?.let { string(R.string.agent_note_answer_listening, arrayOf(agentNoteAgentName(it, string))) }
                ?: string(R.string.agent_note_answer_listening_unnamed, emptyArray()),
        )
        wake != null && wake.willBeWoken -> line(AgentNoteAnswerMark.WOKEN, agentNoteWakeText(wake, time, string))
        wake != null -> line(AgentNoteAnswerMark.OFFLINE, agentNoteWakeText(wake, time, string), muted = true)
        answer.state == AgentNoteListening.NOT_LISTENING && chosen != null -> line(
            AgentNoteAnswerMark.WAITING,
            string(R.string.agent_note_answer_chosen_not_listening, arrayOf(agentNoteAgentName(chosen.coverer, string))),
        )
        answer.state == AgentNoteListening.NOT_LISTENING ->
            line(AgentNoteAnswerMark.NOBODY, string(R.string.agent_note_answer_nobody, emptyArray()), warning = true)
        else -> null
    }
}

/**
 * The chip over a note's comment: "Answered by: …" with who answers a reply now. A chosen agent is
 * named as chosen; on Automatic, the agent that listens, else the session a reply waker wakes. Null
 * while the server has not said, or cannot tell.
 */
fun agentNoteAnsweredByChipText(answer: AgentNoteListeningAnswer?, string: (Int, Array<out Any>) -> String): String? {
    answer ?: return null
    val chosen = answer.answerBy
    val wake = answer.wake
    val named = chosen?.coverer ?: answer.coverer
    val who = when {
        namesTheListener(answer.state, named, wake) && named != null -> agentNoteCovererName(named, string)
        wake != null -> agentNoteWokenName(wake, string)
        chosen != null -> agentNoteCovererName(chosen.coverer, string)
        answer.state == AgentNoteListening.NOT_LISTENING -> string(R.string.agent_note_answered_by_nobody, emptyArray())
        else -> return null
    }
    return string(R.string.agent_note_answered_by, arrayOf(who))
}

/** One entry of the list "Change" opens: [agent] null is Automatic. */
data class AgentNoteAnswerChoice(
    val agent: AgentNoteAgent?,
    val title: String,
    /** A line under the title; only Automatic has one. */
    val detail: String?,
    val checked: Boolean,
)

/**
 * The single-choice list behind "Change": Automatic first, then every agent that could answer
 * now, by the names the line uses; the current choice is checked. A chosen agent that is not
 * listening (so not among the candidates) is listed too, marked as such, so the list always
 * shows what is chosen. Two agents that would read the same get a number.
 */
fun agentNoteAnswerChoices(
    answer: AgentNoteListeningAnswer,
    string: (Int, Array<out Any>) -> String,
): List<AgentNoteAnswerChoice> {
    val chosen = answer.answerBy
    val agents = answer.candidates.fold(mutableListOf<AgentNoteAgent>()) { distinct, agent ->
        if (distinct.none(agent::isSameAgent)) distinct += agent
        distinct
    }
    val names = hashMapOf<String, Int>()
    fun numbered(name: String): String {
        val count = (names[name] ?: 0) + 1
        names[name] = count
        return if (count == 1) name else "$name ($count)"
    }
    return buildList {
        add(AgentNoteAnswerChoice(
            agent = null,
            title = string(R.string.agent_note_answer_automatic, emptyArray()),
            detail = string(R.string.agent_note_answer_automatic_detail, emptyArray()),
            checked = chosen == null,
        ))
        agents.forEach { agent ->
            add(AgentNoteAnswerChoice(agent, numbered(agentNoteAgentName(agent.coverer, string)), null, agent.isSameAgent(chosen)))
        }
        if (chosen != null && agents.none(chosen::isSameAgent)) {
            add(AgentNoteAnswerChoice(
                agent = chosen,
                title = string(R.string.agent_note_answer_choice_not_listening, arrayOf(agentNoteAgentName(chosen.coverer, string))),
                detail = null,
                checked = true,
            ))
        }
    }
}

/** What the Snackbar says when a choice could not be saved; the choice stays as it was. */
fun agentNoteAnswerChangeFailure(error: Throwable): Int =
    if ((error as? com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesHttpException)?.errorCode ==
        com.gitlab.mudlej.MjPdfReader.data.agentnotes.AGENT_NOTE_ANSWERER_UNKNOWN
    ) R.string.agent_note_answer_agent_unknown else R.string.agent_note_answer_change_failed

/**
 * The subtitle after Send: [agentNoteSentStatus], naming who answers when the server said: the
 * agent that listens, or the session a reply waker wakes ([wake]: "Sent ✓ · <title> will be woken").
 */
fun agentNoteSentText(
    delivered: Boolean,
    listening: AgentNoteListening,
    coverer: AgentNoteCoverer?,
    string: (Int, Array<out Any>) -> String,
    wake: AgentNoteWake? = null,
): String {
    if (delivered && wake != null && !namesTheListener(listening, coverer, wake)) {
        return string(R.string.agent_note_sent_will_be_woken, arrayOf(agentNoteWokenName(wake, string)))
    }
    val status = agentNoteSentStatus(delivered, listening)
    return if (status == R.string.agent_note_sent_waiting && coverer != null) {
        string(R.string.agent_note_sent_answered_by, arrayOf(agentNoteCovererName(coverer, string)))
    } else {
        string(status, emptyArray())
    }
}
