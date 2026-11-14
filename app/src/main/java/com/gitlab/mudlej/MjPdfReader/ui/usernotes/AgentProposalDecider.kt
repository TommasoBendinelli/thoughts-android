// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecision
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecisionPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.ui.source.ProposalTexExcerpt

/**
 * Deciding an agent proposal from the PDF view: the proposal sheet's Accept and Reject and the
 * ✓ on the page take the same checks and the same way to Fedora.
 */
internal object AgentProposalDecider {

    /** The proposal's wrapper in the source on this phone ([excerpt], null if absent), and whether that source changed. */
    data class SourceCheck(val excerpt: ProposalTexExcerpt?, val changed: Boolean)

    /** Reads the proposal's source as the sheet shows it. Blocking: call off the main thread. */
    fun checkSource(context: Context, proposal: OfflineAgentProposal): SourceCheck? {
        val queue = com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue(context, scheduleRequests = false)
        val snapshot = queue.openOfflineSource(proposal.sourceFile, proposal.line)?.cachedSnapshot
        val raw = snapshot?.let { queue.localDraftContents(it) ?: it.contents }
        val draft = com.gitlab.mudlej.MjPdfReader.ui.source.ProposalReviewJournal(
            java.io.File(context.filesDir, "proposal-review"), proposal.sourceFile,
        ).read()
        val unsavedReview = draft != null && draft.get("source") != draft.get("savedSource")
        val changed = unsavedReview || (raw != null &&
            com.gitlab.mudlej.MjPdfReader.ui.source.ProposalSourceActivity.digest(raw) != proposal.sourceSha256)
        return SourceCheck(raw?.let { com.gitlab.mudlej.MjPdfReader.ui.source.proposalTexExcerpt(proposal.sourceFile, it, proposal.id) }, changed)
    }

    /**
     * Whether the proposal can be decided here now: its wrapper is in the source on this phone,
     * that source is the one the proposal was made on, its object is known, and no decision of
     * it waits for Sync or was applied. Otherwise the sheet says why.
     */
    fun decidable(objectSha256: String, sourceFound: Boolean, sourceChanged: Boolean, decision: AgentProposalDecision?): Boolean =
        sourceFound && !sourceChanged && objectSha256.matches(SHA256) &&
            decision?.phase !in setOf(AgentProposalDecisionPhase.PENDING, AgentProposalDecisionPhase.APPLIED)

    /** Queues [action] ("accept" or "disregard") for Sync, which sends it to Fedora; returns its operation ID. */
    suspend fun decide(context: Context, proposal: OfflineAgentProposal, action: String): String =
        FedoraRepository(context.applicationContext).resolveAgentProposals(
            proposal.bundleId, action, mapOf(proposal.id to proposal.objectSha256),
            proposal.pdfFilenames + proposal.correspondence?.locations.orEmpty().map { it.pdfFilename },
        )

    /** Queues the undo of the decision [operationId]: Fedora takes the decision back and the proposal is open again. */
    suspend fun undo(context: Context, operationId: String): String =
        FedoraRepository(context.applicationContext).undoAgentProposalResolution(operationId)

    private val SHA256 = Regex("[a-f0-9]{64}")
}
