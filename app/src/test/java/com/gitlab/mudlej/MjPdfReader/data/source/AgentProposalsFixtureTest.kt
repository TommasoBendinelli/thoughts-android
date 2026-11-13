// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The shared agent-proposal fixtures (thoughts-server protocol/fixtures/agent-proposals/cases.json,
 * copied to src/test/resources) against the native reader, strictly: proposals, issue codes and
 * spans (code points), and refusal codes. The WebView model runs the same file in
 * scripts/test-proposal-review.cjs.
 */
class AgentProposalsFixtureTest {
    private val corpus: JsonObject = JsonParser.parseString(
        (javaClass.classLoader!!.getResource("agent-proposals/cases.json")?.readText()
            ?: File("src/test/resources/agent-proposals/cases.json").readText())
    ).asJsonObject

    private fun span(source: String, start: Int, end: Int) = JsonObject().apply {
        addProperty("start", source.codePointCount(0, start)); addProperty("end", source.codePointCount(0, end))
    }

    private fun decide(path: String, source: String, ids: List<String>, action: String): JsonObject {
        val parsed = AgentProposals.parse(path, source)
        val result = JsonObject()
        val chosen = mutableListOf<AgentProposals.Proposal>()
        for (id in ids) {
            val code = parsed.refusalCode(id)
            if (code != null) return result.apply { addProperty("refused", code) }
            val proposal = parsed.proposals.single { it.id == id }
            if (proposal !in chosen) chosen += proposal
        }
        var output = source
        for (p in chosen.sortedByDescending { it.start }) {
            output = output.substring(0, p.start) + (if (action == "accept") p.replacement else p.original) + output.substring(p.end)
        }
        return result.apply { addProperty("output", output) }
    }

    @Test fun everyFixtureCasePassesStrictly() {
        val cases = corpus.getAsJsonArray("cases")
        var passed = 0
        for (element in cases) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            val path = case.get("path").asString
            val source = case.get("source").asString
            val parsed = AgentProposals.parse(path, source)
            val proposals = JsonArray().apply {
                for (p in parsed.proposals) add(JsonObject().apply {
                    addProperty("id", p.id); addProperty("created_at", p.createdAt); addProperty("conversation_id", p.conversationId)
                    p.conversationUrl?.let { addProperty("conversation_url", it) }
                    addProperty("explanation", p.explanation); addProperty("original", p.original); addProperty("replacement", p.replacement)
                    add("span", span(source, p.start, p.end))
                })
            }
            val issues = JsonArray().apply {
                for (issue in parsed.issues) add(JsonObject().apply {
                    addProperty("code", issue.code); add("span", span(source, issue.start, issue.end))
                    issue.id?.let { addProperty("id", it) }
                })
            }
            val expect = case.getAsJsonObject("expect")
            assertEquals(id, expect.get("proposals"), proposals)
            assertEquals(id, expect.get("issues"), issues)
            for (d in case.getAsJsonArray("decisions")) {
                val decision = d.asJsonObject
                val ids = decision.getAsJsonArray("ids").map { it.asString }
                assertEquals("$id ${decision.get("action").asString} $ids", decision.get("expect"),
                    decide(path, source, ids, decision.get("action").asString))
            }
            passed++
        }
        assertEquals(171, passed)
    }

    @Test fun timestampsAreRealUtcInstants() {
        for (valid in listOf("2024-02-29T00:00:00Z", "0001-01-01T00:00:00.1Z", "9999-12-31T23:59:59.123456789Z"))
            assertEquals(valid, true, AgentProposals.validTimestamp(valid))
        for (invalid in listOf("2023-02-29T00:00:00Z", "1900-02-29T00:00:00Z", "0000-01-01T00:00:00Z", "2026-04-31T00:00:00Z",
            "2026-10-10T24:00:00Z", "2026-10-10T12:00:60Z", "2026-10-10T12:00:00", "2026-10-10t12:00:00z",
            "2026-10-10T12:00:00+02:00", "2026-10-10", "2026-10-10T12:00:00.Z", "2026-10-10T12:00:00.1234567890Z", "２０２６-10-10T12:00:00Z"))
            assertEquals(invalid, false, AgentProposals.validTimestamp(invalid))
    }
}
