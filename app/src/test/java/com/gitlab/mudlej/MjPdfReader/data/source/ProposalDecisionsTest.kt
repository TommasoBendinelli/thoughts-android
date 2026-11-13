// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Section 10 of the agent-proposal spec (thoughts-server protocol/agent-proposals.md) on the phone:
 * the `attach` and `restore` groups of fixtures/agent-proposals/decisions.json (copied to
 * src/test/resources) strictly, and the claims the source editor derives from its review journal.
 */
class ProposalDecisionsTest {
    private val corpus: JsonObject = JsonParser.parseString(
        (javaClass.classLoader!!.getResource("agent-proposals/decisions.json")?.readText()
            ?: File("src/test/resources/agent-proposals/decisions.json").readText())
    ).asJsonObject

    @Test fun everyAttachFixturePasses() {
        val cases = corpus.getAsJsonArray("attach")
        for (element in cases) {
            val case = element.asJsonObject
            val claims = ProposalDecisionClaims.read(case.getAsJsonArray("claims"))
            assertEquals(case.getAsJsonArray("claims").size(), claims.size)
            val sent = mutableListOf<String>(); val dropped = mutableListOf<String>()
            for ((path, contents) in case.getAsJsonObject("upload").entrySet()) {
                val upload = ProposalDecisionClaims.forUpload(claims, path, contents.asString)
                sent += upload.send.map { it.decisionId }; dropped += upload.dropped.map { it.decisionId }
            }
            val expect = case.getAsJsonObject("expect")
            val id = case.get("id").asString
            assertEquals(id, expect.getAsJsonArray("sent").map { it.asString }, sent)
            assertEquals(id, expect.getAsJsonArray("dropped").map { it.asString }, dropped)
        }
        assertEquals(4, cases.size())
    }

    @Test fun everyRestoreFixturePasses() {
        val cases = corpus.getAsJsonArray("restore")
        for (element in cases) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            val source = case.get("source").takeUnless { it.isJsonNull }?.asString
            val snapshot = requireNotNull(ProposalRestore.Snapshot.fromJson(case.getAsJsonObject("snapshot")))
            val result = ProposalRestore.restore(case.get("path").asString, source, snapshot, case.get("line").asInt)
            val actual = JsonObject().apply {
                when (result) {
                    is ProposalRestore.Result.Restored -> addProperty("output", result.output)
                    is ProposalRestore.Result.AlreadyPresent -> addProperty("already_present", true)
                    is ProposalRestore.Result.Refused -> addProperty("refused", result.code)
                }
            }
            assertEquals(id, case.getAsJsonObject("expect"), actual)
        }
        assertEquals(21, cases.size())
    }

    @Test fun restoreReportsTheRestoredLineAndTheMatchedText() {
        val snapshot = ProposalRestore.Snapshot("agent-1", "a.tex", "Why.\n", "Old.\n", "New.\n",
            "2026-10-10T12:00:00.000Z", "0b6c5b7e-1d2a-4c3b-9f8e-112233445566")
        val result = ProposalRestore.restore("a.tex", "A\nB\nOld.\nC\n", snapshot, 3) as ProposalRestore.Result.Restored
        assertEquals(3, result.line)
        assertEquals("before", result.matched)
        assertEquals("A\nB\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{agent-1}{Why.\n}{Old.\n}{New.\n}C\n", result.output)
    }

    // ------------------------------------------------------------------ claim derivation

    private val path = "docs-x/tex/source/a.tex"
    private val wrapper = "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]" +
        "{agent-1}{Clarify.\n}{Old line.\n}{New line.\n}"
    private val base = "Intro.\n${wrapper}Outro.\n"
    private val accepted = "Intro.\nNew line.\nOutro.\n"
    private var ids = 0
    private fun nextId() = "00000000-0000-4000-8000-%012d".format(++ids)

    private fun event(id: String, kind: String, at: String, related: String? = null, review: Boolean = kind in DECISIONS) = JsonObject().apply {
        addProperty("event_id", id); addProperty("proposal_id", "agent-1"); addProperty("kind", kind)
        addProperty("observed_at", at); if (review) { addProperty("decision_at", at); add("review", JsonObject()) }
        related?.let { addProperty("related_event_id", it) }
    }

    private fun derive(events: List<JsonObject>, saved: String = accepted, claimed: Set<String> = emptySet(), from: String = base) =
        ProposalDecisionClaims.derive(path, from, saved, events, claimed, ::nextId)

    @Test fun aWholeDecisionClaimsTheWrapperOfTheSaveBase() {
        val derived = derive(listOf(event("e0", "first_seen", "2026-10-11T08:00:00.000Z"), event("e1", "accepted", "2026-10-11T08:59:12.345Z")))
        val claim = derived.single().claim
        assertEquals("agent-1", claim.proposalId)
        assertEquals("accept", claim.action)
        assertEquals("252bfd0570e87e771344faa0a2033f6d46454250eaf6f11d2ea7e8644f76dd8e", claim.wrapperSha256)
        assertEquals(path, claim.sourceFile)
        assertEquals("2026-10-11T08:59:12.345Z", claim.decidedAt)
        assertEquals("phone_editor", claim.origin)
        assertEquals("e1", derived.single().activationEventId)
        // The claim reads back as valid wire JSON.
        assertEquals(claim, ProposalDecisionClaim.fromJson(claim.toJson()))
        assertEquals("reject", derive(listOf(event("e1", "rejected", "2026-10-11T09:00:00.000Z")), "Intro.\nOld line.\nOutro.\n").single().claim.action)
    }

    @Test fun aRemovalAfterPartialStepsIsPartial() {
        val events = listOf(
            event("e1", "partially_accepted", "2026-10-11T08:00:00.000Z"),
            event("e2", "accepted", "2026-10-11T08:01:00.000Z"),
        )
        assertEquals("partial", derive(events).single().claim.action)
    }

    @Test fun handRemovalIsNotADecision() {
        // No decision at all.
        assertTrue(derive(listOf(event("e0", "first_seen", "2026-10-11T08:00:00.000Z"))).isEmpty())
        // The last decision was undone, then the wrapper was deleted by hand.
        assertTrue(derive(listOf(
            event("e1", "accepted", "2026-10-11T08:00:00.000Z"),
            event("u1", "review_undone", "2026-10-11T08:01:00.000Z", "e1"),
        )).isEmpty())
        // A partial step keeps a wrapper; deleting the rest by hand is not a decision.
        assertTrue(derive(listOf(event("e1", "partially_accepted", "2026-10-11T08:00:00.000Z"))).isEmpty())
        // A decision already claimed by an earlier save (the wrapper was restored since).
        assertTrue(derive(listOf(event("e1", "accepted", "2026-10-11T08:00:00.000Z")), claimed = setOf("e1")).isEmpty())
    }

    @Test fun redoIsANewDecision() {
        val events = listOf(
            event("e1", "accepted", "2026-10-11T08:00:00.000Z"),
            event("u1", "review_undone", "2026-10-11T08:01:00.000Z", "e1"),
            event("r1", "review_redone", "2026-10-11T08:02:00.000Z", "e1"),
        )
        val derived = derive(events, claimed = setOf("e1")).single()
        assertEquals("r1", derived.activationEventId)
        assertEquals("2026-10-11T08:02:00.000Z", derived.claim.decidedAt)
        assertTrue(derive(events, claimed = setOf("e1", "r1")).isEmpty())
    }

    @Test fun onlyWrappersPresentInTheBaseAndAbsentFromTheSaveClaim() {
        val decided = listOf(event("e1", "accepted", "2026-10-11T08:00:00.000Z"))
        // Still present (undone in the buffer, or never removed).
        assertTrue(derive(decided, saved = base).isEmpty())
        // Broken in the saved text: an issue carries the ID.
        assertTrue(derive(decided, saved = "Intro.\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{agent-1}{x}{y}").isEmpty())
        // Not a valid wrapper in the base (already removed by an earlier save).
        assertTrue(derive(decided, from = accepted).isEmpty())
    }

    @Test fun laterClaimsReplaceEarlierOnesForTheSameProposal() {
        val first = ProposalDecisionClaim(nextId(), "agent-1", "accept", "a".repeat(64), path, "t1")
        val other = ProposalDecisionClaim(nextId(), "agent-2", "reject", "b".repeat(64), path, "t1")
        val second = first.copy(decisionId = nextId(), action = "reject", decidedAt = "t2")
        assertEquals(listOf(other, second), ProposalDecisionClaims.merge(listOf(first, other), listOf(second)))
        val json = ProposalDecisionClaims.toJson(listOf(other, second))
        assertEquals(listOf(other, second), ProposalDecisionClaims.read(json))
        assertTrue(ProposalDecisionClaims.read(JsonArray().apply { add(JsonObject()) }).isEmpty())
        assertNull(ProposalDecisionClaim.fromJson(first.toJson().apply { addProperty("action", "maybe") }))
    }

    @Test fun plainEditsThatRemoveAWrapperAreReported() {
        assertEquals(listOf("agent-1"), ProposalDecisionClaims.removedWithoutDecision(path, base, accepted))
        assertTrue(ProposalDecisionClaims.removedWithoutDecision(path, base, base.replace("Outro", "End")).isEmpty())
        // Breaking a wrapper is refused elsewhere; it is not a removal.
        assertTrue(ProposalDecisionClaims.removedWithoutDecision(path, base, base.dropLast(9)).isEmpty())
    }

    /**
     * 2026-10-16: an Accept from the PDF view is shown in the editor as source text (the editor
     * records an ordinary `accepted` event), the user edits that text and saves. The save claims
     * the wrapper as accepted and asks nothing; a later save of further edits claims nothing new.
     */
    @Test fun editingTheTextOfAnAcceptShownFromThePdfViewSavesAsThatDecision() {
        val markdownPath = "docs-x/notes/a.md"
        val markdownWrapper = requireNotNull(ProposalRestore.write(AgentProposals.Format.MARKDOWN, ProposalRestore.Snapshot(
            "agent-1", markdownPath, "Clarify.", "Old line.\n", "New line.\n",
            "2026-10-10T12:00:00.000Z", "0b6c5b7e-1d2a-4c3b-9f8e-112233445566",
        )))
        for ((file, from) in listOf(path to base, markdownPath to "Intro.\n${markdownWrapper}Outro.\n")) {
            assertEquals(listOf("agent-1"), AgentProposals.parse(file, from).proposals.map { it.id })
            val edited = "Intro.\nNew line, edited.\nOutro.\n"
            val events = listOf(event("e1", "accepted", "2026-10-16T09:00:00.000Z"))
            val derived = ProposalDecisionClaims.derive(file, from, edited, events, emptySet(), ::nextId)
            assertEquals("accept", derived.single().claim.action)
            assertEquals(ProposalDecisionClaims.sha256(from.substring(
                AgentProposals.parse(file, from).proposals.single().start, AgentProposals.parse(file, from).proposals.single().end,
            )), derived.single().claim.wrapperSha256)
            // Not a removal by hand: no confirmation, and the marker guard has nothing to refuse.
            assertEquals(listOf("agent-1"), ProposalDecisionClaims.removedWithoutDecision(file, from, edited))
            assertTrue((ProposalDecisionClaims.removedWithoutDecision(file, from, edited) - derived.map { it.claim.proposalId }.toSet()).isEmpty())
            // The next save starts from the saved text: no wrapper, so no new claim and no removal.
            val later = ProposalDecisionClaims.derive(file, edited, "$edited More.\n", events, setOf("e1"), ::nextId)
            assertTrue(later.isEmpty())
            assertTrue(ProposalDecisionClaims.removedWithoutDecision(file, edited, "$edited More.\n").isEmpty())
        }
    }

    private companion object { val DECISIONS = setOf("accepted", "rejected", "partially_accepted", "partially_rejected") }
}
