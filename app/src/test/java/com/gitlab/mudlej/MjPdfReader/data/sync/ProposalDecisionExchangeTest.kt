// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.ProposalDecisionClaim
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.tbend.thoughts.sdk.ConflictResolveRequest
import org.tbend.thoughts.sdk.SourceApplyRequest
import org.tbend.thoughts.sdk.SourceApplyResult

class ProposalDecisionExchangeTest {
    private val claim = ProposalDecisionClaim("00000000-0000-4000-8000-000000000001", "agent-1", "partial",
        "a".repeat(64), "docs-x/tex/source/a.tex", "2026-10-11T08:59:12.345Z")
    private val apply = SourceApplyRequest("op", 7, "docs-x/tex/source/a.tex", 3, "b".repeat(64), "c".repeat(64), "d".repeat(64), "t")

    @Test fun theUploadCarriesTheClaimsAndTheAnswerTheirOutcomes() {
        val exchange = ProposalDecisionExchange(Gson(), listOf(claim))
        val sent = JsonParser.parseString(exchange.gson.toJson(apply)).asJsonObject
        assertEquals(JsonParser.parseString(Gson().toJson(apply)).asJsonObject.apply {
            add("proposal_decisions", com.google.gson.JsonArray().apply { add(claim.toJson()) })
        }, sent)
        assertEquals("phone_editor", sent.getAsJsonArray("proposal_decisions")[0].asJsonObject.get("origin").asString)
        val resolve = JsonParser.parseString(exchange.gson.toJson(ConflictResolveRequest("op", 7, 3, "c".repeat(64), "t"))).asJsonObject
        assertEquals(1, resolve.getAsJsonArray("proposal_decisions").size())

        val answer = """{"outcome":"applied","path":"docs-x/tex/source/a.tex","revision":4,"sha256":"${"e".repeat(64)}",
            "object_sha256":"${"e".repeat(64)}","server_sequence":9,"conflict_id":null,
            "proposal_decisions":[{"decision_id":"${claim.decisionId}","proposal_id":"agent-1","outcome":"matched"}]}"""
        val result = exchange.gson.fromJson(answer, SourceApplyResult::class.java)
        assertEquals("applied", result.outcome)
        assertEquals(4L, result.revision)
        assertEquals(listOf(ProposalDecisionExchange.Outcome(claim.decisionId, "agent-1", "matched")), exchange.outcomes)
    }

    @Test fun noClaimsLeaveTheRequestUnchanged() {
        val exchange = ProposalDecisionExchange(Gson(), emptyList())
        assertFalse(JsonParser.parseString(exchange.gson.toJson(apply)).asJsonObject.has("proposal_decisions"))
        exchange.gson.fromJson("""{"outcome":"applied","path":"p","revision":1,"sha256":"s","object_sha256":"o","server_sequence":null,"conflict_id":null}""",
            SourceApplyResult::class.java)
        assertEquals(emptyList<ProposalDecisionExchange.Outcome>(), exchange.outcomes)
    }
}
