// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProposalDecisionsTest {

    @Test
    fun libraryDecisionsUseEachProposalsDocumentArtifact() {
        val first = proposal("first", "a".repeat(64))
        val second = proposal("second", "b".repeat(64))
        val rows = listOf(
            row(1, first.bundleId, "accept", listOf(first.id)),
            row(2, second.bundleId, "disregard", listOf(second.id),
                deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED, failureDetail = "Source changed"),
            row(3, "c".repeat(64), "accept", listOf("unrelated")),
        )

        val states = agentProposalDecisionStates(rows, listOf(first, second))

        assertEquals(setOf("first", "second"), states.keys)
        assertEquals(AgentProposalDecisionPhase.PENDING, states.getValue("first").phase)
        assertEquals("accept", states.getValue("first").action)
        assertEquals(AgentProposalDecisionPhase.FAILED, states.getValue("second").phase)
        assertEquals("Source changed", states.getValue("second").error)
    }

    @Test
    fun aMoveHalfShowsTheDecisionMadeOnItsPartnerInAnotherDocument() {
        // Agent-proposal spec section 12: the phone sends the half its PDF shows; Fedora decides both.
        val move = "agent-0d1e2f30-4152-4637-8899-aabbccddeeff"
        val cut = proposal("$move.cut", "a".repeat(64))
        val paste = proposal("$move.paste", "b".repeat(64))
        val rows = listOf(row(1, cut.bundleId, "accept", listOf(cut.id)))

        val states = agentProposalDecisionStates(rows, listOf(cut, paste))
        assertEquals(setOf(cut.id, paste.id), states.keys)
        assertEquals("operation-1", states.getValue(paste.id).operationId)
        // The destination's own PDF view stops marking the inserted half too.
        assertEquals("operation-1", agentProposalDecisionStates(rows, paste.bundleId).getValue(paste.id).operationId)
        assertTrue(highlightedAgentProposals(listOf(cut, paste), rows).isEmpty())

        // The source editor locks the other half, and never turns the pair into an editor save.
        val locks = editorDecisionLocks(rows, setOf(cut.id, paste.id), emptyMap())
        assertEquals(setOf(cut.id, paste.id), locks.keys)
        assertTrue(locks.values.none(EditorDecisionLock::materialize))
        assertTrue(editorDecisionLocks(listOf(row(2, "c".repeat(64), "accept", listOf("other"))), setOf("other"), emptyMap())
            .getValue("other").materialize)
    }

    @Test
    fun libraryDecisionsIgnoreOtherVersionsOfTheSameProposal() {
        val current = proposal("one", "a".repeat(64))
        val currentDecision = row(1, current.bundleId, "accept", listOf(current.id))
        val otherVersion = row(2, "b".repeat(64), "disregard", listOf(current.id),
            deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)

        val states = agentProposalDecisionStates(listOf(currentDecision, otherVersion), listOf(current))

        assertEquals(currentDecision.operationId, states.getValue(current.id).operationId)
        assertEquals(AgentProposalDecisionPhase.PENDING, states.getValue(current.id).phase)
    }

    /**
     * 2026-10-10: the abstract proposal (agent-97ca49d3) was accepted in the PDF view and applied
     * by Fedora at 20:43:53; the sdpo one (agent-1223c0e3) was accepted at ~20:45 and waited for
     * the next Sync. The source editor offered Accept/Reject for both.
     */
    @Test
    fun theSourceEditorDoesNotOfferProposalsDecidedInThePdfView() {
        val abstractArtifact = "7".repeat(64)
        val sdpoArtifact = "f".repeat(64)
        val rows = listOf(
            row(1, abstractArtifact, "accept", listOf("agent-97ca49d3"), deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED),
            row(2, sdpoArtifact, "accept", listOf("agent-1223c0e3")),
            row(3, sdpoArtifact, "disregard", listOf("agent-failed"), deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED),
        )
        val ids = setOf("agent-97ca49d3", "agent-1223c0e3", "agent-failed", "agent-open")

        // Fedora's refreshed artifact no longer lists the applied one; this copy still has its wrapper.
        val locks = editorDecisionLocks(rows, ids, mapOf("agent-1223c0e3" to sdpoArtifact, "agent-failed" to sdpoArtifact))

        assertEquals(EditorDecisionLock("accept", applied = true), locks["agent-97ca49d3"])
        assertEquals(EditorDecisionLock("accept", applied = false, materialize = true, group = "operation-2"), locks["agent-1223c0e3"])
        assertEquals(setOf("agent-97ca49d3", "agent-1223c0e3"), locks.keys) // A failure or no decision stays open.
    }

    @Test
    fun anAppliedDecisionStopsLockingOnceTheProposalIsPublishedAgain() {
        val rows = listOf(row(1, "a".repeat(64), "accept", listOf("one"), deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED))
        // Still listed on the artifact it was decided on: the metadata refresh has not arrived yet.
        assertEquals(setOf("one"), editorDecisionLocks(rows, setOf("one"), mapOf("one" to "a".repeat(64))).keys)
        // Listed on a newer artifact: undone elsewhere and published again, so it is open.
        assertTrue(editorDecisionLocks(rows, setOf("one"), mapOf("one" to "b".repeat(64))).isEmpty())
        // Not in this source any more: nothing to lock.
        assertTrue(editorDecisionLocks(rows, emptySet(), emptyMap()).isEmpty())
    }

    @Test
    fun theNewestDecisionCountsAndAnUndoOrSupersededRowUnlocks() {
        val accept = row(1, "a".repeat(64), "accept", listOf("one"), deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
        val reject = row(2, "a".repeat(64), "disregard", listOf("one"))
        assertEquals(EditorDecisionLock("reject", applied = false, group = "operation-2"), editorDecisionLocks(listOf(accept, reject), setOf("one"), emptyMap())["one"])
        val undo = ThoughtsOutboxEntity(
            operationId = "undo-1", deviceSequence = 3, entityType = AGENT_PROPOSAL_UNDO_ENTITY_TYPE,
            entityId = accept.operationId, collection = "agent-proposals", baseRevision = null,
            clientTimestamp = "2026-10-10T18:50:00Z", createdAtEpochMillis = 0, payloadJson = "{\"resolution_id\":\"${accept.operationId}\"}",
        )
        assertTrue(editorDecisionLocks(listOf(accept, undo), setOf("one"), emptyMap()).isEmpty())
        val superseded = reject.copy(deliveryState = ThoughtsOutboxEntity.STATE_SUPERSEDED)
        assertTrue(editorDecisionLocks(listOf(superseded), setOf("one"), emptyMap()).isEmpty())
    }

    /**
     * 2026-10-16: an Accept made in the PDF view kept the proposal a read-only card in the source
     * editor until Sync. Only an unsent Accept whose every proposal is in the open source is shown
     * as editable text; a Reject, an applied Accept or one spanning other sources stays locked.
     */
    @Test
    fun onlyAnUnsentAcceptWhollyInThisSourceIsShownAsEditableText() {
        val rows = listOf(
            row(1, "a".repeat(64), "accept", listOf("one", "two")),
            row(2, "a".repeat(64), "accept", listOf("three", "elsewhere")),
            row(3, "a".repeat(64), "disregard", listOf("four")),
            row(4, "a".repeat(64), "accept", listOf("five"), deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED),
        )
        val locks = editorDecisionLocks(rows, setOf("one", "two", "three", "four", "five"), emptyMap())
        assertEquals(EditorDecisionLock("accept", applied = false, materialize = true, group = "operation-1"), locks["one"])
        assertEquals(EditorDecisionLock("accept", applied = false, materialize = true, group = "operation-1"), locks["two"])
        assertEquals(false, locks.getValue("three").materialize)
        assertEquals(false, locks.getValue("four").materialize)
        assertEquals(false, locks.getValue("five").materialize)
    }

    /** A save that carries the decision as a claim retires the queued PDF-view decision, never more. */
    @Test
    fun aSourceEditCarryingTheDecisionRetiresTheQueuedResolution() {
        val rows = listOf(
            row(1, "a".repeat(64), "accept", listOf("one", "two")),
            row(2, "a".repeat(64), "accept", listOf("one", "other")),
            row(3, "a".repeat(64), "disregard", listOf("three"), deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED),
            row(4, "a".repeat(64), "accept", listOf("four"), deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED),
            row(5, "a".repeat(64), "accept", listOf("five"), deliveryState = ThoughtsOutboxEntity.STATE_SUPERSEDED),
            row(6, "a".repeat(64), "accept", listOf("six")),
        )
        assertEquals(
            listOf("operation-1", "operation-3"),
            decisionsCarriedBySourceEdit(rows, setOf("one", "two", "three", "four", "five")),
        )
        assertTrue(decisionsCarriedBySourceEdit(rows, emptySet()).isEmpty())
    }

    @Test
    fun aDecisionWaitingForSyncMarksItsDocumentAsHavingChangesToSync() {
        val waiting = row(1, "f".repeat(64), "accept", listOf("agent-1223c0e3"), pdfFilenames = listOf("concepts_sdpo.pdf"))
        assertTrue(proposalDecisionAwaitsSync(waiting, "concepts_sdpo.pdf"))
        assertTrue(!proposalDecisionAwaitsSync(waiting, "other.pdf"))
        assertTrue(!proposalDecisionAwaitsSync(waiting.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED), "concepts_sdpo.pdf"))
    }

    private fun proposal(id: String, artifact: String) =
        com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal(
            bundleId = artifact, objectSha256 = "d".repeat(64), id = id, kind = "edit",
            comment = "Reason.", sourceFile = "docs-sample/tex/main.tex", sourceSha256 = "e".repeat(64),
            line = 1, bodyPreview = "Old.", parentId = null, suppressed = false,
            pdfFilenames = listOf("paper.pdf"), beforePreview = "Old.", afterPreview = "New.", quads = emptyList(),
        )

    @Test
    fun repeatedTapsAreRetiredOnlyAfterTheSameDecisionIsApplied() {
        val first = row(1, "a".repeat(64), "accept", listOf("one"))
        val duplicates = (2L..12L).map { sequence ->
            row(sequence, "a".repeat(64), "accept", listOf("one"))
        }
        assertTrue(duplicatePendingProposalDecisionIds(duplicates, first).isEmpty())
        assertTrue(duplicatePendingProposalDecisionIds(duplicates,
            first.copy(deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED)).isEmpty())
        assertEquals(duplicates.map { it.operationId }, duplicatePendingProposalDecisionIds(
            duplicates.reversed(), first.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)))
    }

    @Test
    fun duplicateRecoveryPreservesInterveningChoicesAndChangedProposalObjects() {
        val applied = row(1, "a".repeat(64), "accept", listOf("one"),
            deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
        val duplicate = row(2, "a".repeat(64), "accept", listOf("one"))
        val reject = row(3, "a".repeat(64), "disregard", listOf("one"))
        val acceptAgain = row(4, "a".repeat(64), "accept", listOf("one"))
        assertEquals(listOf(duplicate.operationId), duplicatePendingProposalDecisionIds(
            listOf(duplicate, reject, acceptAgain), applied))
        val changed = duplicate.copy(payloadJson = duplicate.payloadJson.replace("e".repeat(64), "f".repeat(64)))
        assertTrue(duplicatePendingProposalDecisionIds(listOf(changed, acceptAgain), applied).isEmpty())
        val unrelated = row(3, "b".repeat(64), "accept", listOf("other"))
        assertEquals(listOf(duplicate.operationId, acceptAgain.operationId),
            duplicatePendingProposalDecisionIds(listOf(duplicate, unrelated, acceptAgain), applied))
    }

    @Test
    fun activeReviewKeepsPendingAndFailedButHidesAppliedForMatchingArtifact() {
        val artifact = "a".repeat(64)
        val proposal = com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal(
            bundleId = artifact, objectSha256 = "b".repeat(64), id = "one", kind = "edit",
            comment = "Reason.", sourceFile = "docs-sample/tex/main.tex", sourceSha256 = "c".repeat(64),
            line = 1, bodyPreview = "Old.", parentId = null, suppressed = false,
            pdfFilenames = listOf("paper.pdf"), beforePreview = "Old.", afterPreview = "New.", quads = emptyList(),
        )
        val pending = row(1, artifact, "accept", listOf("one"))
        assertEquals(listOf(proposal), activeAgentProposals(listOf(proposal), listOf(pending)))
        val failed = pending.copy(deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED)
        assertEquals(listOf(proposal), activeAgentProposals(listOf(proposal), listOf(failed)))
        val applied = pending.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
        assertTrue(activeAgentProposals(listOf(proposal), listOf(applied)).isEmpty())
        val restored = proposal.copy(bundleId = "d".repeat(64))
        assertEquals(listOf(restored), activeAgentProposals(listOf(restored), listOf(applied)))
    }

    @Test
    fun pageStopsMarkingAProposalOnceItsAcceptIsSavedLocally() {
        val proposal = proposal("one", "a".repeat(64))
        val accept = row(1, proposal.bundleId, "accept", listOf("one"))
        assertTrue(highlightedAgentProposals(listOf(proposal), listOf(accept)).isEmpty())
        assertEquals(listOf(proposal), highlightedAgentProposals(listOf(proposal),
            listOf(accept.copy(deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED))))
        assertTrue(highlightedAgentProposals(listOf(proposal),
            listOf(accept.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED))).isEmpty())
        val reject = row(1, proposal.bundleId, "disregard", listOf("one"))
        assertEquals(listOf(proposal), highlightedAgentProposals(listOf(proposal), listOf(reject)))
        val acceptAfterReject = row(2, proposal.bundleId, "accept", listOf("one"))
        assertTrue(highlightedAgentProposals(listOf(proposal), listOf(reject, acceptAfterReject)).isEmpty())
        assertEquals(listOf(proposal), highlightedAgentProposals(listOf(proposal), listOf(acceptAfterReject, reject.copy(deviceSequence = 3))))
    }


    @Test
    fun manifestRetryIsLimitedToExactFailureAndDocument() {
        val blocked = row(1, "a".repeat(64), "accept", listOf("one"),
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED,
            failureDetail = "source bundle manifest is unavailable").copy(failureCode = "http_409")
        assertTrue(blocked.hasRetryableProposalManifestFailure(null))
        assertTrue(blocked.hasRetryableProposalManifestFailure("paper.pdf"))
        assertEquals(false, blocked.hasRetryableProposalManifestFailure("other.pdf"))
        assertEquals(false, blocked.copy(failureDetail = "source changed").hasRetryableProposalManifestFailure(null))
        assertEquals(false, blocked.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
            .hasRetryableProposalManifestFailure(null))
    }

    @Test
    fun onlyAppliedReplacementOfEverySelectedProposalRetiresFailure() {
        val failed = row(1, "a".repeat(64), "accept", listOf("one", "two"),
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED)
        val newer = row(2, "b".repeat(64), "disregard", listOf("one", "two"))
        assertTrue(supersededProposalDecisionIds(listOf(failed), newer).isEmpty())
        val applied = newer.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
        assertEquals(listOf(failed.operationId), supersededProposalDecisionIds(listOf(failed), applied))
        val partial = row(3, "b".repeat(64), "accept", listOf("one"),
            deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
        assertTrue(supersededProposalDecisionIds(listOf(failed), partial).isEmpty())
        assertTrue(supersededProposalDecisionIds(listOf(failed), applied.copy(deviceSequence = 0)).isEmpty())
        val otherDocument = row(4, "b".repeat(64), "accept", listOf("one", "two"),
            pdfFilenames = listOf("other.pdf"), deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
        assertTrue(supersededProposalDecisionIds(listOf(failed), otherDocument).isEmpty())
        assertTrue(agentProposalDecisionStates(
            listOf(failed.copy(deliveryState = ThoughtsOutboxEntity.STATE_SUPERSEDED)), "a".repeat(64),
        ).isEmpty())
    }

    @Test
    fun pendingDecisionRetainsItsProposalAndDocumentIdentities() {
        val artifact = "a".repeat(64)
        val decision = row(
            deviceSequence = 4,
            artifactSha256 = artifact,
            action = "disregard",
            proposalIds = listOf("proposal-one", "proposal-two"),
            pdfFilenames = listOf("paper.pdf", "appendix.pdf"),
        ).agentProposalDecisionOrNull()

        requireNotNull(decision)
        assertEquals(AgentProposalDecisionPhase.PENDING, decision.phase)
        assertEquals("disregard", decision.action)
        assertEquals(listOf("proposal-one", "proposal-two"), decision.proposalIds)
        assertEquals(listOf("paper.pdf", "appendix.pdf"), decision.pdfFilenames)
    }

    @Test
    fun latestDecisionWinsAndBlockedErrorIsPreservedExactly() {
        val artifact = "b".repeat(64)
        val rows = listOf(
            row(
                deviceSequence = 2,
                artifactSha256 = artifact,
                action = "accept",
                proposalIds = listOf("proposal-one"),
            ),
            row(
                deviceSequence = 3,
                artifactSha256 = artifact,
                action = "accept",
                proposalIds = listOf("proposal-one"),
                deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED,
                failureDetail = "source changed after bundle publication",
            ),
        )

        val state = agentProposalDecisionStates(rows.reversed(), artifact).getValue("proposal-one")

        assertEquals(AgentProposalDecisionPhase.FAILED, state.phase)
        assertEquals("source changed after bundle publication", state.error)
    }

    @Test
    fun appliedDecisionOnlySuppressesTheMatchingArtifact() {
        val originalArtifact = "c".repeat(64)
        val refreshedArtifact = "d".repeat(64)
        val accepted = row(
            deviceSequence = 5,
            artifactSha256 = originalArtifact,
            action = "accept",
            proposalIds = listOf("stable-proposal"),
            deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED,
        )

        assertEquals(
            AgentProposalDecisionPhase.APPLIED,
            agentProposalDecisionStates(listOf(accepted), originalArtifact)
                .getValue("stable-proposal")
                .phase,
        )
        assertTrue(agentProposalDecisionStates(listOf(accepted), refreshedArtifact).isEmpty())
    }

    @Test
    fun serverDispositionRequiresTerminalAppliedBeforeDelivery() {
        assertEquals(
            AgentProposalServerDisposition.Pending,
            agentProposalServerDisposition("queued", null),
        )
        assertEquals(
            AgentProposalServerDisposition.Pending,
            agentProposalServerDisposition("validating", null),
        )
        assertEquals(
            AgentProposalServerDisposition.Applied,
            agentProposalServerDisposition("applied", null),
        )
        assertEquals(
            AgentProposalServerDisposition.Failed("exact Fedora error"),
            agentProposalServerDisposition("failed", "exact Fedora error"),
        )
    }

    @Test
    fun failedDecisionsAreRetiredOnlyWhenEveryPdfHasANewerArtifact() {
        val old = "a".repeat(64)
        val current = "b".repeat(64)
        val replaced = row(1, old, "accept", listOf("one"),
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED, failureDetail = "stale")
        val stillShown = row(2, current, "accept", listOf("two"),
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED)
        val pending = row(3, old, "accept", listOf("three"))
        val applied = row(4, old, "accept", listOf("four"), deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
        val partlyCurrent = row(5, old, "disregard", listOf("five"), pdfFilenames = listOf("paper.pdf", "other.pdf"),
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED)
        val unknownPdf = row(6, old, "accept", listOf("six"), pdfFilenames = listOf("missing.pdf"),
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED)
        val noPdf = row(7, old, "accept", listOf("seven"), pdfFilenames = emptyList(),
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED)
        val rows = listOf(replaced, stillShown, pending, applied, partlyCurrent, unknownPdf, noPdf)

        assertEquals(listOf(replaced.operationId), replacedFailedProposalDecisionIds(
            rows, mapOf("paper.pdf" to current, "other.pdf" to old)))
        // Without positive evidence of a newer artifact nothing is retired.
        assertTrue(replacedFailedProposalDecisionIds(rows, emptyMap()).isEmpty())
    }

    @Test
    fun malformedLocalPayloadIsIgnoredWithoutInventingPendingState() {
        val malformed = row(
            deviceSequence = 8,
            artifactSha256 = "f".repeat(64),
            action = "accept",
            proposalIds = listOf("proposal-one"),
        ).copy(payloadJson = "{\"proposals\":\"not-an-array\"}")

        assertEquals(null, malformed.agentProposalDecisionOrNull())
        assertTrue(agentProposalDecisionStates(listOf(malformed), "f".repeat(64)).isEmpty())
    }

    private fun row(
        deviceSequence: Long,
        artifactSha256: String,
        action: String,
        proposalIds: List<String>,
        pdfFilenames: List<String> = listOf("paper.pdf"),
        deliveryState: String = ThoughtsOutboxEntity.STATE_PENDING,
        failureDetail: String = "",
    ) = ThoughtsOutboxEntity(
        operationId = "operation-$deviceSequence",
        deviceSequence = deviceSequence,
        entityType = AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE,
        entityId = "operation-$deviceSequence",
        collection = "agent-proposals",
        baseRevision = null,
        clientTimestamp = "2026-08-28T00:00:00Z",
        payloadJson = buildString {
            append("{\"artifact_sha256\":\"")
            append(artifactSha256)
            append("\",\"action\":\"")
            append(action)
            append("\",\"proposals\":[")
            append(proposalIds.joinToString(",") { id ->
                "{\"id\":\"$id\",\"object_sha256\":\"${"e".repeat(64)}\"}"
            })
            append("],\"pdf_filenames\":[")
            append(pdfFilenames.joinToString(",") { filename -> "\"$filename\"" })
            append("]}")
        },
        createdAtEpochMillis = deviceSequence,
        deliveryState = deliveryState,
        failureDetail = failureDetail,
    )
}
