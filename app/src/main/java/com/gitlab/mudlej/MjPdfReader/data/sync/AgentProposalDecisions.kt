// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import java.io.File

internal const val AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE = "agent_proposal_resolution"
internal const val AGENT_PROPOSAL_UNDO_ENTITY_TYPE = "agent_proposal_undo"

internal enum class AgentProposalDecisionPhase {
    PENDING,
    APPLIED,
    FAILED,
}

internal data class AgentProposalDecision(
    val operationId: String,
    val deviceSequence: Long,
    val artifactSha256: String,
    val action: String,
    val proposalIds: List<String>,
    val pdfFilenames: List<String>,
    val phase: AgentProposalDecisionPhase,
    val error: String,
)

internal sealed interface AgentProposalServerDisposition {
    data object Applied : AgentProposalServerDisposition
    data object Pending : AgentProposalServerDisposition
    data class Failed(val error: String) : AgentProposalServerDisposition
}

internal fun agentProposalServerDisposition(
    status: String,
    error: String?,
): AgentProposalServerDisposition = when (status) {
    "applied" -> AgentProposalServerDisposition.Applied
    "queued", "validating" -> AgentProposalServerDisposition.Pending
    "failed" -> AgentProposalServerDisposition.Failed(
        error.orEmpty().ifBlank { "The server rejected the proposal decision." },
    )
    else -> error("Unexpected agent proposal resolution status: $status")
}

internal fun ThoughtsOutboxEntity.agentProposalDecisionOrNull(): AgentProposalDecision? {
    if (entityType != AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE) return null
    val payload = runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrNull()
        ?: return null
    val artifactSha256 = payload.get("artifact_sha256")
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        .orEmpty()
    val action = payload.get("action")
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        .orEmpty()
    val proposalIds = payload.get("proposals")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { proposal ->
            proposal.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("id")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.takeIf(String::isNotBlank)
        }
        .orEmpty()
        .distinct()
    if (artifactSha256.isBlank() || action !in setOf("accept", "disregard") || proposalIds.isEmpty()) {
        return null
    }
    val pdfFilenames = payload.get("pdf_filenames")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { filename ->
            filename.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.takeIf {
                    it == File(it).name && it.endsWith(".pdf", ignoreCase = true)
                }
        }
        .orEmpty()
        .distinct()
    val phase = when (deliveryState) {
        ThoughtsOutboxEntity.STATE_ACCEPTED -> AgentProposalDecisionPhase.APPLIED
        ThoughtsOutboxEntity.STATE_BLOCKED -> AgentProposalDecisionPhase.FAILED
        else -> AgentProposalDecisionPhase.PENDING
    }
    return AgentProposalDecision(
        operationId = operationId,
        deviceSequence = deviceSequence,
        artifactSha256 = artifactSha256,
        action = action,
        proposalIds = proposalIds,
        pdfFilenames = pdfFilenames,
        phase = phase,
        error = failureDetail.ifBlank { failureCode },
    )
}

internal fun agentProposalDecisionStates(
    rows: List<ThoughtsOutboxEntity>,
    artifactSha256: String?,
): Map<String, AgentProposalDecision> {
    if (artifactSha256.isNullOrBlank()) return emptyMap()
    val states = linkedMapOf<String, AgentProposalDecision>()
    rows.asSequence()
        .filter { it.deliveryState != ThoughtsOutboxEntity.STATE_SUPERSEDED }
        .mapNotNull(ThoughtsOutboxEntity::agentProposalDecisionOrNull)
        .filter { it.artifactSha256 == artifactSha256 }
        .sortedBy(AgentProposalDecision::deviceSequence)
        .forEach { decision ->
            decision.proposalIds.forEach { proposalId -> states[proposalId] = decision }
        }
    // The other half of a move pair is decided with its half (spec section 12), even when the
    // decision was made on another document's artifact: its ID exists only once in the Library.
    rows.asSequence()
        .filter { it.deliveryState != ThoughtsOutboxEntity.STATE_SUPERSEDED }
        .mapNotNull(ThoughtsOutboxEntity::agentProposalDecisionOrNull)
        .sortedBy(AgentProposalDecision::deviceSequence)
        .forEach { decision ->
            decision.proposalIds.mapNotNull(com.gitlab.mudlej.MjPdfReader.data.source.ProposalMovePairs::partner)
                .filter { it !in decision.proposalIds }
                .forEach { partner -> states[partner] = decision }
        }
    return states
}

internal fun ThoughtsOutboxEntity.hasRetryableProposalManifestFailure(target: String?): Boolean {
    val decision = agentProposalDecisionOrNull() ?: return false
    return decision.phase == AgentProposalDecisionPhase.FAILED &&
        failureCode == "http_409" &&
        failureDetail == "source bundle manifest is unavailable" &&
        (target == null || target in decision.pdfFilenames)
}

/** The Library index can contain proposals from several document artifacts. */
internal fun agentProposalDecisionStates(
    rows: List<ThoughtsOutboxEntity>,
    proposals: Collection<com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal>,
): Map<String, AgentProposalDecision> = proposals.groupBy { it.bundleId }
    .flatMap { (artifact, artifactProposals) ->
        val states = agentProposalDecisionStates(rows, artifact)
        artifactProposals.mapNotNull { proposal ->
            states[proposal.id]?.let { proposal.id to it }
        }
    }.toMap()

/** Only a newer, server-applied user decision can retire an older failed decision. */
internal fun supersededProposalDecisionIds(
    rows: List<ThoughtsOutboxEntity>,
    applied: ThoughtsOutboxEntity,
): List<String> {
    val replacement = applied.agentProposalDecisionOrNull() ?: return emptyList()
    if (replacement.phase != AgentProposalDecisionPhase.APPLIED) return emptyList()
    return rows.mapNotNull { row ->
        val previous = row.agentProposalDecisionOrNull() ?: return@mapNotNull null
        row.operationId.takeIf {
            previous.phase == AgentProposalDecisionPhase.FAILED &&
                previous.deviceSequence < replacement.deviceSequence &&
                previous.pdfFilenames.isNotEmpty() &&
                previous.pdfFilenames.toSet() == replacement.pdfFilenames.toSet() &&
                replacement.proposalIds.containsAll(previous.proposalIds)
        }
    }
}

/**
 * A failed decision is shown only on the artifact it was made on. Once every one of its
 * PDFs has a different active artifact, it can neither apply nor be seen, so it must not
 * keep Sync failed. An open proposal reappears undecided on its new artifact.
 */
internal fun replacedFailedProposalDecisionIds(
    rows: List<ThoughtsOutboxEntity>,
    activeArtifacts: Map<String, String>,
): List<String> = rows.mapNotNull { row ->
    val decision = row.agentProposalDecisionOrNull() ?: return@mapNotNull null
    row.operationId.takeIf {
        decision.phase == AgentProposalDecisionPhase.FAILED &&
            decision.pdfFilenames.isNotEmpty() &&
            decision.pdfFilenames.all { pdf ->
                activeArtifacts[pdf].let { it != null && it != decision.artifactSha256 }
            }
    }
}

/** Retire repeated taps only after the identical decision has been applied on Fedora. */
internal fun duplicatePendingProposalDecisionIds(
    rows: List<ThoughtsOutboxEntity>,
    applied: ThoughtsOutboxEntity,
): List<String> {
    val decision = applied.agentProposalDecisionOrNull() ?: return emptyList()
    if (decision.phase != AgentProposalDecisionPhase.APPLIED) return emptyList()
    val appliedPayload = JsonParser.parseString(applied.payloadJson)
    return rows.asSequence()
        .filter { it.deviceSequence > applied.deviceSequence }
        .sortedBy { it.deviceSequence }
        .mapNotNull { row -> row.agentProposalDecisionOrNull()?.let { row to it } }
        .filter { (_, later) -> later.proposalIds.any(decision.proposalIds::contains) }
        // A different intervening choice must keep its place in the user's decision history.
        .takeWhile { (row, _) ->
            row.deliveryState == ThoughtsOutboxEntity.STATE_PENDING &&
                JsonParser.parseString(row.payloadJson) == appliedPayload
        }
        .map { (row, _) -> row.operationId }
        .toList()
}

/** Hide resolved proposals only in the artifact on which the decision was made. */
internal fun activeAgentProposals(
    proposals: Collection<com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal>,
    rows: List<ThoughtsOutboxEntity>,
): List<com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal> {
    val states = agentProposalDecisionStates(rows, proposals)
    return proposals.filter { states[it.id]?.phase != AgentProposalDecisionPhase.APPLIED }
}

/**
 * Proposals to mark on the page. The PDF already shows the proposed text, so an Accept saved
 * on this device stops marking it before Sync applies it. A pending or failed Reject stays
 * marked, because the page still shows text the user turned down until the PDF is rebuilt.
 */
internal fun highlightedAgentProposals(
    proposals: Collection<com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal>,
    rows: List<ThoughtsOutboxEntity>,
): List<com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal> {
    val states = agentProposalDecisionStates(rows, proposals)
    return proposals.filter { proposal ->
        val decision = states[proposal.id]
        decision?.phase != AgentProposalDecisionPhase.APPLIED &&
            !(decision?.phase == AgentProposalDecisionPhase.PENDING && decision.action == "accept")
    }
}

/**
 * Proposals the open document stops marking because this device accepted them in the source
 * editor. The editor's claim expires when Fedora answers the upload, which can be before this
 * PDF's metadata or the PDF itself is replaced, so an ID stays hidden ([previous]) until the
 * loaded proposals no longer list it or its wrapper is back in the source's latest draft.
 */
internal fun locallyAcceptedProposalIds(
    previous: Set<String>,
    editor: com.gitlab.mudlej.MjPdfReader.data.source.EditorProposalDecisions,
    loadedIds: Set<String>,
): Set<String> = ((previous - editor.carried) + editor.accepted).intersect(loadedIds)

/**
 * A decision made in the PDF view or Overview, as the source editor shows it. [materialize]: an
 * Accept not sent yet whose every proposal is in the open source; the editor shows its accepted
 * text as editable source text, and a save carries the decision ([group] is its outbox row, which
 * [decisionsCarriedBySourceEdit] then retires).
 */
internal data class EditorDecisionLock(
    val action: String,
    val applied: Boolean,
    val materialize: Boolean = false,
    val group: String = "",
)

/**
 * Proposals of [idsInSource] the source editor must not offer again, because the PDF view or
 * Overview already decided them (2026-10-10: an Accept waiting for Sync, and one Fedora had
 * applied, both still showed Accept/Reject in the editor).
 *
 * The newest decision per proposal counts. One waiting for Sync always locks. One Fedora applied
 * locks while the source on this phone still carries the wrapper and the published proposals
 * ([publishedArtifactByProposal]: proposal ID to document artifact) either no longer list it or
 * still list it on the artifact the decision was made on; listed on another artifact, the
 * proposal was published again (an undo elsewhere) and is open. A failed decision locks nothing:
 * the PDF view shows its failure and the proposal is open again.
 */
internal fun editorDecisionLocks(
    rows: List<ThoughtsOutboxEntity>,
    idsInSource: Set<String>,
    publishedArtifactByProposal: Map<String, String>,
): Map<String, EditorDecisionLock> {
    if (idsInSource.isEmpty()) return emptyMap()
    val live = rows.filter { it.deliveryState != ThoughtsOutboxEntity.STATE_SUPERSEDED }
    val undone = live.filter { it.entityType == AGENT_PROPOSAL_UNDO_ENTITY_TYPE }.mapTo(hashSetOf()) { it.entityId }
    val latest = linkedMapOf<String, AgentProposalDecision>()
    live.asSequence()
        .filter { it.operationId !in undone }
        .mapNotNull(ThoughtsOutboxEntity::agentProposalDecisionOrNull)
        .sortedBy(AgentProposalDecision::deviceSequence)
        .forEach { decision ->
            val decided = decision.proposalIds + decision.proposalIds.mapNotNull(com.gitlab.mudlej.MjPdfReader.data.source.ProposalMovePairs::partner)
            decided.filter(idsInSource::contains).forEach { latest[it] = decision }
        }
    return latest.mapNotNull { (id, decision) ->
        val action = if (decision.action == "accept") "accept" else "reject"
        when (decision.phase) {
            AgentProposalDecisionPhase.PENDING -> id to EditorDecisionLock(
                action, applied = false,
                // A move pair is applied by Fedora in both of its files, never by an editor save.
                materialize = action == "accept" && decision.proposalIds.all(idsInSource::contains) &&
                    decision.proposalIds.none(com.gitlab.mudlej.MjPdfReader.data.source.ProposalMovePairs::isHalf),
                group = decision.operationId,
            )
            AgentProposalDecisionPhase.APPLIED -> publishedArtifactByProposal[id].let { published ->
                if (published == null || published == decision.artifactSha256) {
                    id to EditorDecisionLock(action, applied = true)
                } else null
            }
            AgentProposalDecisionPhase.FAILED -> null
        }
    }.toMap()
}

/** A PDF-view decision on [filename] that has not left the phone yet: the document has changes to sync. */
internal fun proposalDecisionAwaitsSync(row: ThoughtsOutboxEntity, filename: String): Boolean {
    val decision = row.agentProposalDecisionOrNull() ?: return false
    return decision.phase == AgentProposalDecisionPhase.PENDING && filename in decision.pdfFilenames
}

/**
 * PDF-view and Overview decisions that a source-editor save now carries (2026-10-16: accept in the
 * PDF view, then edit the accepted text). [decidedInEditor] are the proposals whose decision
 * claims ride on the source's queued upload. A resolution not applied yet (waiting for Sync or failed)
 * whose every proposal is among them must not be sent as well: Fedora would apply it first and the
 * upload would then meet a source that moved under it. The claim is the newer decision.
 */
internal fun decisionsCarriedBySourceEdit(
    rows: List<ThoughtsOutboxEntity>,
    decidedInEditor: Set<String>,
): List<String> {
    if (decidedInEditor.isEmpty()) return emptyList()
    return rows.mapNotNull { row ->
        val decision = row.agentProposalDecisionOrNull() ?: return@mapNotNull null
        row.operationId.takeIf {
            row.deliveryState in setOf(ThoughtsOutboxEntity.STATE_PENDING, ThoughtsOutboxEntity.STATE_BLOCKED) &&
                decision.proposalIds.all(decidedInEditor::contains)
        }
    }
}
