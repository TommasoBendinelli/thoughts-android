// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecision
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecisionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks the ✓ on the page and the sheet's Accept share: a tap accepts only what Accept would. */
class AgentProposalDeciderTest {

    @Test
    fun anOpenProposalWhoseSourceIsHereAndUnchangedCanBeDecided() {
        assertTrue(AgentProposalDecider.decidable(OBJECT, sourceFound = true, sourceChanged = false, decision = null))
        assertTrue("A failed decision can be tried again",
            AgentProposalDecider.decidable(OBJECT, true, false, decision(AgentProposalDecisionPhase.FAILED)))
    }

    @Test
    fun whatTheSheetWouldNotAcceptOpensTheSheetInstead() {
        assertFalse("Source not on this phone", AgentProposalDecider.decidable(OBJECT, false, false, null))
        assertFalse("Source changed since the proposal", AgentProposalDecider.decidable(OBJECT, true, true, null))
        assertFalse("Unknown object", AgentProposalDecider.decidable("not-a-hash", true, false, null))
        assertFalse("A decision waits for Sync",
            AgentProposalDecider.decidable(OBJECT, true, false, decision(AgentProposalDecisionPhase.PENDING)))
        assertFalse("Already applied",
            AgentProposalDecider.decidable(OBJECT, true, false, decision(AgentProposalDecisionPhase.APPLIED)))
    }

    private fun decision(phase: AgentProposalDecisionPhase) = AgentProposalDecision(
        operationId = "operation-1", deviceSequence = 1, artifactSha256 = "a".repeat(64), action = "accept",
        proposalIds = listOf("agent-1"), pdfFilenames = listOf("paper.pdf"), phase = phase, error = "",
    )

    private companion object {
        val OBJECT = "d".repeat(64)
    }
}
