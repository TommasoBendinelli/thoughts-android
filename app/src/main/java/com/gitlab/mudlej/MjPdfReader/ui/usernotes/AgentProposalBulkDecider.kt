// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.source.ProposalMovePairs
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecision
import kotlinx.coroutines.CancellationException

/**
 * Accept or Reject for several agent proposals at once (the Agent edits sheet's selection).
 *
 * Each proposal takes the single decision's path ([AgentProposalDecider]): the same checks, and
 * one queued resolution of its own, which Sync sends to the server in queue order. One that cannot
 * be decided here is left undecided, with its reason, and the others are still decided.
 *
 * Order: the server decides each resolution by its proposal ID against the current source, so
 * proposals of one source can be decided one after the other in any order. Undo is not like that:
 * the server undoes a resolution only while its source is still exactly as that resolution left
 * it, so [undoAll] queues the undos newest first.
 */
internal object AgentProposalBulkDecider {

    /** Why a selected proposal was left undecided. */
    enum class Skip { ALREADY_DECIDED, UNKNOWN_OBJECT, SOURCE_MISSING, SOURCE_CHANGED, NOT_SAVED }

    /** One queued resolution ([operationId]) and the selected proposals it decides (a move pair's halves together). */
    data class Decided(val proposalIds: List<String>, val operationId: String)

    data class Skipped(val proposalId: String, val reason: Skip, val detail: String = "")

    data class Outcome(val action: String, val decided: List<Decided>, val skipped: List<Skipped>) {
        val decidedCount: Int get() = decided.sumOf { it.proposalIds.size }

        /** The skipped proposals per reason, in the order of [Skip]. */
        val skippedByReason: Map<Skip, Int>
            get() = Skip.entries.associateWith { reason -> skipped.count { it.reason == reason } }.filterValues { it > 0 }
    }

    data class UndoOutcome(val action: String, val restoredCount: Int, val failure: String?)

    /** What deciding needs from the phone; [AgentProposalDecider] in the app, fakes in tests. */
    interface Ports {
        /** The decision this phone holds for [proposal] (waiting for Sync, applied or failed), if any. */
        fun decision(proposal: OfflineAgentProposal): AgentProposalDecision?

        /** The proposal's source on this phone; null when it could not be read. Blocking. */
        fun checkSource(proposal: OfflineAgentProposal): AgentProposalDecider.SourceCheck?

        /** Queues [action] for [proposal]; returns the operation ID. */
        suspend fun decide(proposal: OfflineAgentProposal, action: String): String

        /** Queues the undo of the decision [operationId]. */
        suspend fun undo(operationId: String): String
    }

    /**
     * Decides [chosen] (in this order) with [action], "accept" or "disregard". The two halves of a
     * move pair are one decision: the server decides the other half with the first, so only one
     * is queued, and when the first cannot be decided here the other is tried.
     */
    suspend fun decideAll(chosen: List<OfflineAgentProposal>, action: String, ports: Ports): Outcome {
        require(action == "accept" || action == "disregard") { "Invalid proposal action" }
        val decided = mutableListOf<Decided>()
        val skipped = mutableListOf<Skipped>()
        for (group in decisionGroups(chosen.distinctBy { it.id })) {
            val reasons = mutableListOf<Skipped>()
            var operation: Decided? = null
            for (proposal in group) {
                val reason = skipReason(proposal, ports)
                if (reason != null) {
                    reasons += Skipped(proposal.id, reason)
                    continue
                }
                val operationId = try {
                    ports.decide(proposal, action)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reasons += Skipped(proposal.id, Skip.NOT_SAVED, error.message.orEmpty())
                    continue
                }
                operation = Decided(group.map(OfflineAgentProposal::id), operationId)
                break
            }
            if (operation != null) decided += operation else skipped += reasons
        }
        return Outcome(action, decided, skipped)
    }

    /**
     * Queues the undo of every decision of [outcome], newest first, so that each finds its source
     * as it left it. An undo that cannot be queued is reported; the others are still queued.
     */
    suspend fun undoAll(outcome: Outcome, ports: Ports): UndoOutcome {
        var restored = 0
        var failure: String? = null
        for (decision in outcome.decided.asReversed()) {
            try {
                ports.undo(decision.operationId)
                restored += decision.proposalIds.size
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (failure == null) failure = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            }
        }
        return UndoOutcome(outcome.action, restored, failure)
    }

    private fun skipReason(proposal: OfflineAgentProposal, ports: Ports): Skip? {
        val decision = ports.decision(proposal)
        // What needs no file read first: a decided proposal is not read again.
        AgentProposalDecider.undecidableReason(proposal.objectSha256, sourceFound = true, sourceChanged = false, decision)
            ?.let { return it.skip() }
        val check = runCatching { ports.checkSource(proposal) }.getOrNull()
        return AgentProposalDecider.undecidableReason(
            proposal.objectSha256, check?.excerpt != null, check?.changed == true, decision,
        )?.skip()
    }

    private fun AgentProposalDecider.Undecidable.skip(): Skip = when (this) {
        AgentProposalDecider.Undecidable.ALREADY_DECIDED -> Skip.ALREADY_DECIDED
        AgentProposalDecider.Undecidable.UNKNOWN_OBJECT -> Skip.UNKNOWN_OBJECT
        AgentProposalDecider.Undecidable.SOURCE_MISSING -> Skip.SOURCE_MISSING
        AgentProposalDecider.Undecidable.SOURCE_CHANGED -> Skip.SOURCE_CHANGED
    }

    /** [proposals] in order, a move pair's selected halves in one group, every other proposal alone. */
    private fun decisionGroups(proposals: List<OfflineAgentProposal>): List<List<OfflineAgentProposal>> {
        val groups = linkedMapOf<String, MutableList<OfflineAgentProposal>>()
        for (proposal in proposals) {
            val key = ProposalMovePairs.partner(proposal.id)?.let { minOf(it, proposal.id) } ?: proposal.id
            groups.getOrPut(key) { mutableListOf() } += proposal
        }
        return groups.values.toList()
    }
}

/** The words of a bulk decision's summary; Android resources in the app ([AgentEditsBulkWords]). */
internal interface BulkDecisionWords {
    /** "4 proposals accepted" / "1 proposal rejected" / "None accepted". */
    fun decided(action: String, count: Int): String

    /** The summary when nothing was left undecided, e.g. "4 proposals accepted — sent with the next Sync." */
    fun allDecided(action: String, count: Int): String

    /** "1 left undecided: <reasons>". */
    fun undecided(count: Int, reasons: String): String

    fun reason(reason: AgentProposalBulkDecider.Skip, detail: String): String

    /** One reason among several: "source changed on this phone (2)". */
    fun reasonWithCount(reason: String, count: Int): String

    val separator: String
}

/**
 * The short summary of [outcome]: "4 proposals accepted · 1 left undecided: source changed on this
 * phone", or the all-decided sentence when nothing was left.
 */
internal fun bulkDecisionSummary(outcome: AgentProposalBulkDecider.Outcome, words: BulkDecisionWords): String {
    if (outcome.skipped.isEmpty()) return words.allDecided(outcome.action, outcome.decidedCount)
    val byReason = outcome.skippedByReason
    val reasons = if (byReason.size == 1) {
        val reason = byReason.keys.single()
        words.reason(reason, outcome.skipped.first { it.reason == reason }.detail)
    } else byReason.entries.joinToString(", ") { (reason, count) ->
        words.reasonWithCount(words.reason(reason, outcome.skipped.first { it.reason == reason }.detail), count)
    }
    return words.decided(outcome.action, outcome.decidedCount) + words.separator +
        words.undecided(outcome.skipped.size, reasons)
}
