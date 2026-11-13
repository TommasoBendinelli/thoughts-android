// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Decision claims (agent-proposal spec 10.4.2) travel with the phone's durable source requests. */
class SourceEditQueueProposalDecisionsTest {
    private lateinit var root: File
    private lateinit var requests: File
    private lateinit var cache: File
    private lateinit var queue: SourceEditQueue
    private val path = "docs-template/tex/source/main.tex"

    @Before fun setUp() {
        root = Files.createTempDirectory("source-claims").toFile()
        requests = File(root, "requests"); cache = File(root, "cache")
        queue = newQueue()
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun newQueue() = SourceEditQueue(requests, File(root, "receipts"), cache, {}, {}, null) {}

    private fun claim(n: Int, proposal: String = "agent-$n", action: String = "accept", source: String = path) =
        ProposalDecisionClaim("00000000-0000-4000-8000-%012d".format(n), proposal, action, "a".repeat(64), source, "2026-10-11T08:59:12.345Z")

    private fun request(id: String): JsonObject = JsonParser.parseString(File(requests, "$id.json").readText()).asJsonObject
    private fun draft(): JsonObject = JsonParser.parseString(cache.resolve("drafts").listFiles()!!.single().readText()).asJsonObject
    private fun claimsOf(value: JsonObject) = ProposalDecisionClaims.read(value.get(ProposalDecisionClaims.REQUEST_MEMBER)).map { it.decisionId.takeLast(2) }

    private fun plainSnapshot() = SourceSnapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191", path, 42, "c".repeat(64), "source\n")

    private fun mergeSnapshot(): SourceSnapshot {
        val base = "base\n"
        return SourceSnapshot("3daaf7df-2e77-47cf-8e49-51ab5a761191", path, 42, ProposalDecisionClaims.sha256(base), base,
            bundleId = "d".repeat(64), baseContents = base, bundlePdfFilename = "template.pdf", bundlePdfSha256 = "b".repeat(64))
    }

    @Test fun claimsAreWrittenWithTheDraftAndTheRequestAndAccumulateUntilAnswered() {
        val first = queue.saveForNextSync(plainSnapshot(), "template.pdf", "b".repeat(64), "one\n", listOf(claim(1)))
        assertEquals(listOf("01"), claimsOf(request(first)))
        assertEquals(listOf("01"), claimsOf(draft()))
        // A save without new decisions keeps the unanswered claims; a new decision adds one.
        assertEquals(first, queue.saveForNextSync(plainSnapshot(), "template.pdf", "b".repeat(64), "two\n"))
        assertEquals(listOf("01"), claimsOf(request(first)))
        queue.saveForNextSync(plainSnapshot(), "template.pdf", "b".repeat(64), "three\n", listOf(claim(2)))
        assertEquals(listOf("01", "02"), claimsOf(request(first)))
        // A later decision on the same proposal replaces its claim.
        queue.saveForNextSync(plainSnapshot(), "template.pdf", "b".repeat(64), "four\n", listOf(claim(3, proposal = "agent-1", action = "reject")))
        assertEquals(listOf("02", "03"), claimsOf(request(first)))
        // Claims for another source never ride on this one.
        queue.saveForNextSync(plainSnapshot(), "template.pdf", "b".repeat(64), "five\n", listOf(claim(4, source = "docs-template/tex/source/other.tex")))
        assertEquals(listOf("02", "03"), queue.requestProposalDecisions(request(first)).map { it.decisionId.takeLast(2) })
    }

    /** The proposals whose queued PDF-view decision a save makes redundant (2026-10-16). */
    @Test fun theDecidedProposalsAreTheClaimsTheUploadWillCarry() {
        assertTrue(queue.editorDecidedProposalIds(path).isEmpty())
        queue.saveForNextSync(plainSnapshot(), "template.pdf", "b".repeat(64), "accepted\n", listOf(claim(1), claim(2, action = "reject")))
        assertEquals(setOf("agent-1", "agent-2"), queue.editorDecidedProposalIds(path))
        // Editing the accepted text again keeps the claims.
        queue.saveForNextSync(plainSnapshot(), "template.pdf", "b".repeat(64), "accepted, edited\n")
        assertEquals(setOf("agent-1", "agent-2"), queue.editorDecidedProposalIds(path))
        assertTrue(queue.editorDecidedProposalIds("docs-template/tex/source/other.tex").isEmpty())
        // Undo put agent-1's wrapper back: its claim no longer travels.
        val restored = "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{agent-1}{Why.}{Old.}{New.}\n"
        queue.saveForNextSync(plainSnapshot(), "template.pdf", "b".repeat(64), restored)
        assertEquals(setOf("agent-2"), queue.editorDecidedProposalIds(path))
    }

    @Test fun answeredClaimsExpireButARequestInFlightKeepsItsOwn() {
        val inFlight = queue.saveForNextSync(mergeSnapshot(), "template.pdf", "b".repeat(64), "decided\n", listOf(claim(1)))
        queue.markDeliveryStarted(inFlight)
        val successor = queue.saveForNextSync(mergeSnapshot(), "template.pdf", "b".repeat(64), "decided more\n", listOf(claim(2)))
        assertNotEquals(inFlight, successor)
        assertEquals(listOf("01", "02"), claimsOf(request(successor)))

        // Fedora answered the request in flight: its claims expire everywhere they wait.
        queue.expireProposalDecisions(path, setOf(claim(1).decisionId))
        assertEquals(listOf("01"), claimsOf(request(inFlight)))
        assertEquals(listOf("02"), claimsOf(request(successor)))
        assertEquals(listOf("02"), claimsOf(draft()))

        queue.expireProposalDecisions(path, setOf(claim(2).decisionId))
        assertTrue(claimsOf(request(successor)).isEmpty())
        assertTrue(!draft().has(ProposalDecisionClaims.REQUEST_MEMBER))
    }

    @Test fun aRequestRebuiltFromItsDraftKeepsItsClaims() {
        val requestId = queue.saveForNextSync(mergeSnapshot(), "template.pdf", "b".repeat(64), "decided\n", listOf(claim(1)))
        assertTrue(File(requests, "$requestId.json").delete())
        val restarted = newQueue()
        assertEquals(1, restarted.recoverStrandedMutationRequests())
        assertEquals(listOf("01"), claimsOf(request(requestId)))
    }
}
