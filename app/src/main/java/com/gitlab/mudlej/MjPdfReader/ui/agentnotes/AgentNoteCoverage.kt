// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteAgent
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteCoverer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListening
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListeningAnswer

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
    /** The chosen agent is not listening; the Thoughts reply agent answers after a while. */
    WAITING,
    /** Nobody listens: a reply is kept, but no agent acts on it. */
    NOBODY,
}

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
    /** "Change" is offered: the server lets the user choose who answers. */
    val changeOffered: Boolean,
    /** "Change" can be used now: the server answered the last check. */
    val changeEnabled: Boolean,
)

/**
 * What the line says for [answer], or null for no line (no answer yet, or a server that cannot
 * tell). [canChange]: the server has `agent_note_answer_by_v1`; an older one gets the line without
 * "Change", from the fields it sends. [reachable]: the last check reached the server.
 */
fun agentNoteAnswerLine(
    answer: AgentNoteListeningAnswer?,
    canChange: Boolean,
    reachable: Boolean,
    string: (Int, Array<out Any>) -> String,
): AgentNoteAnswerLine? {
    answer ?: return null
    val chosen = answer.answerBy
    return when (answer.state) {
        AgentNoteListening.UNKNOWN -> null
        AgentNoteListening.LISTENING -> AgentNoteAnswerLine(
            mark = AgentNoteAnswerMark.LISTENING,
            // With a choice the server says "listening" exactly while the chosen agent is live.
            text = (chosen?.coverer ?: answer.coverer)
                ?.let { string(R.string.agent_note_answer_listening, arrayOf(agentNoteAgentName(it, string))) }
                ?: string(R.string.agent_note_answer_listening_unnamed, emptyArray()),
            warning = false,
            changeOffered = canChange,
            changeEnabled = canChange && reachable,
        )
        AgentNoteListening.NOT_LISTENING -> if (chosen != null) {
            AgentNoteAnswerLine(
                mark = AgentNoteAnswerMark.WAITING,
                text = string(R.string.agent_note_answer_chosen_not_listening, arrayOf(agentNoteAgentName(chosen.coverer, string))),
                warning = false,
                changeOffered = canChange,
                changeEnabled = canChange && reachable,
            )
        } else {
            AgentNoteAnswerLine(
                mark = AgentNoteAnswerMark.NOBODY,
                text = string(R.string.agent_note_answer_nobody, emptyArray()),
                warning = true,
                changeOffered = canChange,
                changeEnabled = canChange && reachable,
            )
        }
    }
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

/** The subtitle after Send: [agentNoteSentStatus], naming who answers when the server said. */
fun agentNoteSentText(
    delivered: Boolean,
    listening: AgentNoteListening,
    coverer: AgentNoteCoverer?,
    string: (Int, Array<out Any>) -> String,
): String {
    val status = agentNoteSentStatus(delivered, listening)
    return if (status == R.string.agent_note_sent_waiting && coverer != null) {
        string(R.string.agent_note_sent_answered_by, arrayOf(agentNoteCovererName(coverer, string)))
    } else {
        string(status, emptyArray())
    }
}
