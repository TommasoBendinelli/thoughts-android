// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.sync.AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE
import com.gitlab.mudlej.MjPdfReader.data.sync.AGENT_PROPOSAL_UNDO_ENTITY_TYPE
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecision
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecisionPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.activeAgentProposals
import com.gitlab.mudlej.MjPdfReader.data.sync.agentProposalDecisionStates
import com.gitlab.mudlej.MjPdfReader.data.sync.duplicatePendingProposalDecisionIds
import com.gitlab.mudlej.MjPdfReader.data.sync.supersededProposalDecisionIds
import com.gitlab.mudlej.MjPdfReader.ui.source.ProposalSourceActivity
import com.gitlab.mudlej.MjPdfReader.ui.source.proposalTexExcerpt
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Bulk Accept and Reject of the Agent edits sheet: each selected proposal takes the single
 * decision's path (one queued resolution each), undecidable ones are named, and Undo takes all
 * of them back, newest first. All content is made up.
 */
class AgentProposalBulkDeciderTest {

    private val meta = "[created=2026-11-15T09:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]"
    private fun wrapper(id: String, before: String, after: String) = "\\agentProposedEdit$meta{$id}{reason}{$before}{$after}"

    /** Two adjacent proposals in one source, line after line, and one in another source. */
    private val introPath = "docs-garden/tex/source/intro.tex"
    private val intro = "\\section{Tomatoes}\n" +
        wrapper("agent-first", "Tomatoes like shade.", "Tomatoes like sun.") + "\n" +
        wrapper("agent-second", "Water them weekly.", "Water them daily.") + "\n" +
        "The end.\n"
    private val methodsPath = "docs-garden/tex/source/methods.tex"
    private val methods = wrapper("agent-third", "Use sand.", "Use compost.") + "\n"

    private val bundle = "a".repeat(64)

    private fun proposal(id: String, path: String, source: String, objectSha256: String = "c".repeat(64)) = OfflineAgentProposal(
        bundleId = bundle, objectSha256 = objectSha256, id = id, kind = "edit", comment = "reason",
        sourceFile = path, sourceSha256 = ProposalSourceActivity.digest(source), line = 2,
        bodyPreview = "", beforePreview = "", afterPreview = "", parentId = null, suppressed = false,
        pdfFilenames = listOf("garden.pdf"), quads = emptyList(),
    )

    private val first = proposal("agent-first", introPath, intro)
    private val second = proposal("agent-second", introPath, intro)
    private val third = proposal("agent-third", methodsPath, methods)

    /** The phone as the decider sees it: its sources, and an outbox queued as FedoraRepository queues it. */
    private inner class Phone(
        val sources: MutableMap<String, String> = mutableMapOf(introPath to intro, methodsPath to methods),
    ) : AgentProposalBulkDecider.Ports {
        val outbox = mutableListOf<ThoughtsOutboxEntity>()
        val draftChanged = mutableSetOf<String>()
        val refuse = mutableMapOf<String, String>()
        var refuseUndo: String? = null
        private var nextSequence = 1L

        override fun decision(proposal: OfflineAgentProposal): AgentProposalDecision? =
            agentProposalDecisionStates(outbox, proposal.bundleId)[proposal.id]

        // AgentProposalDecider.checkSource without Android: the wrapper in this phone's source, and
        // whether that source is the one the proposal was made on.
        override fun checkSource(proposal: OfflineAgentProposal): AgentProposalDecider.SourceCheck? {
            val raw = sources[proposal.sourceFile] ?: return AgentProposalDecider.SourceCheck(null, false)
            val changed = proposal.sourceFile in draftChanged || ProposalSourceActivity.digest(raw) != proposal.sourceSha256
            return AgentProposalDecider.SourceCheck(proposalTexExcerpt(proposal.sourceFile, raw, proposal.id), changed)
        }

        override suspend fun decide(proposal: OfflineAgentProposal, action: String): String {
            refuse[proposal.id]?.let { throw IllegalStateException(it) }
            val operationId = UUID.randomUUID().toString()
            outbox += row(operationId, AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE, operationId, JsonObject().apply {
                addProperty("artifact_sha256", proposal.bundleId)
                addProperty("action", action)
                add("pdf_filenames", JsonArray().apply { proposal.pdfFilenames.forEach(::add) })
                add("proposals", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("id", proposal.id)
                        addProperty("object_sha256", proposal.objectSha256)
                    })
                })
            })
            return operationId
        }

        override suspend fun undo(operationId: String): String {
            refuseUndo?.let { if (operationId == it) throw IllegalStateException("database is locked") }
            val undoId = UUID.randomUUID().toString()
            outbox += row(undoId, AGENT_PROPOSAL_UNDO_ENTITY_TYPE, operationId, JsonObject().apply {
                addProperty("resolution_id", operationId)
            })
            return undoId
        }

        private fun row(operationId: String, type: String, entityId: String, payload: JsonObject) = ThoughtsOutboxEntity(
            operationId = operationId, deviceSequence = nextSequence++, entityType = type, entityId = entityId,
            collection = "agent-proposals", baseRevision = null, clientTimestamp = "2026-11-15T09:00:00Z",
            payloadJson = payload.toString(), createdAtEpochMillis = 0,
        )

        /** The server applied [operationId] (Sync marks it accepted). */
        fun applied(operationId: String) {
            val index = outbox.indexOfFirst { it.operationId == operationId }
            outbox[index] = outbox[index].copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED)
        }

        fun resolutions() = outbox.filter { it.entityType == AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE }
        fun proposalIdsOf(row: ThoughtsOutboxEntity): List<String> =
            JsonParser.parseString(row.payloadJson).asJsonObject.getAsJsonArray("proposals").map { it.asJsonObject["id"].asString }
        fun actionOf(row: ThoughtsOutboxEntity): String = JsonParser.parseString(row.payloadJson).asJsonObject["action"].asString
    }

    @Test
    fun eachSelectedProposalIsQueuedAsItsOwnDecisionInTheChosenOrder() = runBlocking {
        val phone = Phone()
        val outcome = AgentProposalBulkDecider.decideAll(listOf(third, first, second), "accept", phone)

        assertEquals(3, outcome.decidedCount)
        assertEquals(emptyList<AgentProposalBulkDecider.Skipped>(), outcome.skipped)
        val rows = phone.resolutions()
        assertEquals(listOf(listOf("agent-third"), listOf("agent-first"), listOf("agent-second")), rows.map(phone::proposalIdsOf))
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.deviceSequence })
        assertEquals(outcome.decided.map { it.operationId }, rows.map { it.operationId })
        val states = agentProposalDecisionStates(phone.outbox, bundle)
        assertEquals(setOf("agent-first", "agent-second", "agent-third"), states.keys)
        assertTrue(states.values.all { it.phase == AgentProposalDecisionPhase.PENDING && it.action == "accept" })
        assertEquals("3 proposals accepted — sent with the next Sync.", bulkDecisionSummary(outcome, resourceWords))
    }

    @Test
    fun aProposalSelectedTwiceIsDecidedOnce() = runBlocking {
        val phone = Phone()
        val outcome = AgentProposalBulkDecider.decideAll(listOf(first, first), "disregard", phone)
        assertEquals(1, phone.resolutions().size)
        assertEquals("Proposal rejected — sent with the next Sync.", bulkDecisionSummary(outcome, resourceWords))
    }

    @Test
    fun whatCannotBeDecidedHereIsLeftUndecidedWithItsReason() = runBlocking {
        val phone = Phone()
        val missing = proposal("agent-missing", "docs-garden/tex/source/gone.tex", "nothing")
        // Its wrapper is in this phone's source, but that source is not the one it was made on.
        val notesPath = "docs-garden/tex/source/notes.tex"
        phone.sources[notesPath] = wrapper("agent-changed", "Rain.", "Sun.") + "\nA line added on this phone.\n"
        val changed = proposal("agent-changed", notesPath, wrapper("agent-changed", "Rain.", "Sun.") + "\n")
        val unknown = proposal("agent-first", introPath, intro, objectSha256 = "not-a-hash")
        AgentProposalBulkDecider.decideAll(listOf(second), "accept", phone)
        phone.refuse["agent-third"] = "disk full"

        val outcome = AgentProposalBulkDecider.decideAll(listOf(missing, changed, second, unknown, third), "accept", phone)

        assertEquals(0, outcome.decidedCount)
        assertEquals(
            listOf(
                "agent-missing" to AgentProposalBulkDecider.Skip.SOURCE_MISSING,
                "agent-changed" to AgentProposalBulkDecider.Skip.SOURCE_CHANGED,
                "agent-second" to AgentProposalBulkDecider.Skip.ALREADY_DECIDED,
                "agent-first" to AgentProposalBulkDecider.Skip.UNKNOWN_OBJECT,
                "agent-third" to AgentProposalBulkDecider.Skip.NOT_SAVED,
            ),
            outcome.skipped.map { it.proposalId to it.reason },
        )
        assertEquals("Only the earlier decision was queued", 1, phone.resolutions().size)
        assertEquals(
            "None accepted · 5 left undecided: already decided (1), not known to this phone yet (1), " +
                "source not on this phone (1), source changed on this phone (1), could not be saved: disk full (1)",
            bulkDecisionSummary(outcome, resourceWords),
        )
    }

    @Test
    fun theOthersAreStillDecidedAndTheSummaryNamesTheSkippedOne() = runBlocking {
        val phone = Phone()
        phone.draftChanged += methodsPath
        val outcome = AgentProposalBulkDecider.decideAll(listOf(first, second, third), "accept", phone)
        assertEquals(2, outcome.decidedCount)
        assertEquals(listOf("agent-first", "agent-second"), phone.resolutions().flatMap(phone::proposalIdsOf))
        assertEquals("2 proposals accepted · 1 left undecided: source changed on this phone", bulkDecisionSummary(outcome, resourceWords))
    }

    @Test
    fun aFailedDecisionCanBeDecidedAgainInBulk() = runBlocking {
        val phone = Phone()
        val operation = AgentProposalBulkDecider.decideAll(listOf(first), "accept", phone).decided.single().operationId
        val index = phone.outbox.indexOfFirst { it.operationId == operation }
        phone.outbox[index] = phone.outbox[index].copy(deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED, failureDetail = "refused")
        val outcome = AgentProposalBulkDecider.decideAll(listOf(first), "disregard", phone)
        assertEquals(1, outcome.decidedCount)
        assertEquals("disregard", agentProposalDecisionStates(phone.outbox, bundle).getValue("agent-first").action)
    }

    @Test
    fun undoTakesBackEveryDecisionNewestFirst() = runBlocking {
        val phone = Phone()
        val outcome = AgentProposalBulkDecider.decideAll(listOf(first, second, third), "accept", phone)
        val undone = AgentProposalBulkDecider.undoAll(outcome, phone)

        assertEquals(AgentProposalBulkDecider.UndoOutcome("accept", 3, null), undone)
        val undos = phone.outbox.filter { it.entityType == AGENT_PROPOSAL_UNDO_ENTITY_TYPE }
        assertEquals(outcome.decided.map { it.operationId }.reversed(), undos.map { it.entityId })
        assertTrue("Each undo is queued after every decision", undos.all { it.deviceSequence > 3 })
        assertEquals("Undone decisions count no more: all three are open again",
            emptyMap<String, AgentProposalDecision>(), agentProposalDecisionStates(phone.outbox, bundle))
        assertEquals(3, activeAgentProposals(listOf(first, second, third), phone.outbox).size)
    }

    @Test
    fun anUndoThatCannotBeQueuedIsReportedAndTheOthersAreQueued() = runBlocking {
        val phone = Phone()
        val outcome = AgentProposalBulkDecider.decideAll(listOf(first, second, third), "disregard", phone)
        phone.refuseUndo = outcome.decided[1].operationId
        val undone = AgentProposalBulkDecider.undoAll(outcome, phone)
        assertEquals(2, undone.restoredCount)
        assertEquals("database is locked", undone.failure)
        assertEquals(setOf("agent-second"), agentProposalDecisionStates(phone.outbox, bundle).keys)
    }

    /**
     * Two adjacent proposals of one source, both accepted: two decisions, sent in order. The
     * server decides each by its ID against the current source (the second still reads as
     * published after the first changed the file), and the first one applied retires neither the
     * other nor its own later duplicates of a different proposal.
     */
    @Test
    fun twoAdjacentProposalsInOneSourceBothAccepted() = runBlocking {
        val phone = Phone()
        val outcome = AgentProposalBulkDecider.decideAll(listOf(first, second), "accept", phone)
        val (firstRow, secondRow) = phone.resolutions()
        assertEquals(listOf("accept", "accept"), listOf(phone.actionOf(firstRow), phone.actionOf(secondRow)))
        assertTrue(firstRow.deviceSequence < secondRow.deviceSequence)
        // The second wrapper is still exactly in this phone's source after the first was decided.
        assertEquals(ProposalTexExcerptOf("Water them weekly.", "Water them daily."), phone.checkSource(second)?.excerpt?.let { ProposalTexExcerptOf(it.before, it.after) })

        phone.applied(firstRow.operationId)
        val applied = phone.outbox.first { it.operationId == firstRow.operationId }
        assertEquals(emptyList<String>(), supersededProposalDecisionIds(phone.outbox, applied))
        assertEquals(emptyList<String>(), duplicatePendingProposalDecisionIds(phone.outbox, applied))
        val states = agentProposalDecisionStates(phone.outbox, bundle)
        assertEquals(AgentProposalDecisionPhase.APPLIED, states.getValue("agent-first").phase)
        assertEquals(AgentProposalDecisionPhase.PENDING, states.getValue("agent-second").phase)
        assertEquals("The applied one leaves the list, the other waits", listOf(second), activeAgentProposals(listOf(first, second), phone.outbox))

        phone.applied(secondRow.operationId)
        assertEquals(emptyList<OfflineAgentProposal>(), activeAgentProposals(listOf(first, second), phone.outbox))
        // Undo: the second's undo goes first, so each finds its source as its decision left it.
        AgentProposalBulkDecider.undoAll(outcome, phone)
        assertEquals(listOf(secondRow.operationId, firstRow.operationId),
            phone.outbox.filter { it.entityType == AGENT_PROPOSAL_UNDO_ENTITY_TYPE }.map { it.entityId })
    }

    /** One accepted and the next rejected in one source: two decisions with their own actions. */
    @Test
    fun twoAdjacentProposalsInOneSourceOneAcceptedOneRejected() = runBlocking {
        val phone = Phone()
        val accepted = AgentProposalBulkDecider.decideAll(listOf(first), "accept", phone)
        val rejected = AgentProposalBulkDecider.decideAll(listOf(second), "disregard", phone)
        assertEquals(1, accepted.decidedCount)
        assertEquals(1, rejected.decidedCount)
        val states = agentProposalDecisionStates(phone.outbox, bundle)
        assertEquals("accept", states.getValue("agent-first").action)
        assertEquals("disregard", states.getValue("agent-second").action)

        val (acceptRow, rejectRow) = phone.resolutions()
        phone.applied(acceptRow.operationId)
        val applied = phone.outbox.first { it.operationId == acceptRow.operationId }
        assertEquals(emptyList<String>(), supersededProposalDecisionIds(phone.outbox, applied))
        assertEquals(emptyList<String>(), duplicatePendingProposalDecisionIds(phone.outbox, applied))
        assertEquals(AgentProposalDecisionPhase.PENDING, agentProposalDecisionStates(phone.outbox, bundle).getValue("agent-second").phase)
        assertEquals(rejectRow.operationId, rejected.decided.single().operationId)
    }

    @Test
    fun aMovePairIsOneDecisionAndTheOtherHalfIsTriedWhenTheFirstCannotBe() = runBlocking {
        val move = "agent-0f0e0d0c-1111-4222-8333-444455556666"
        val cutPath = "docs-garden/tex/source/cut.tex"
        val pastePath = "docs-garden/tex/source/paste.tex"
        val cutSource = wrapper("$move.cut", "Card text.", "") + "\n"
        val pasteSource = wrapper("$move.paste", "", "Card text.") + "\n"
        val cut = proposal("$move.cut", cutPath, cutSource)
        val paste = proposal("$move.paste", pastePath, pasteSource)

        val both = Phone(mutableMapOf(cutPath to cutSource, pastePath to pasteSource))
        val outcome = AgentProposalBulkDecider.decideAll(listOf(cut, paste), "accept", both)
        assertEquals(2, outcome.decidedCount)
        assertEquals(listOf(listOf("$move.cut")), both.resolutions().map(both::proposalIdsOf))
        assertEquals("The server decides the other half with it", setOf("$move.cut", "$move.paste"),
            agentProposalDecisionStates(both.outbox, bundle).keys)

        val pasteOnly = Phone(mutableMapOf(pastePath to pasteSource))
        val second = AgentProposalBulkDecider.decideAll(listOf(cut, paste), "accept", pasteOnly)
        assertEquals(2, second.decidedCount)
        assertEquals(emptyList<AgentProposalBulkDecider.Skipped>(), second.skipped)
        assertEquals(listOf(listOf("$move.paste")), pasteOnly.resolutions().map(pasteOnly::proposalIdsOf))

        val neither = Phone(mutableMapOf())
        val none = AgentProposalBulkDecider.decideAll(listOf(cut, paste), "accept", neither)
        assertEquals(0, none.decidedCount)
        assertEquals(2, none.skipped.size)
        assertEquals("None accepted · 2 left undecided: source not on this phone", bulkDecisionSummary(none, resourceWords))
    }

    @Test
    fun summariesReadAsOnTheMac() {
        fun outcome(action: String, decided: Int, vararg skipped: AgentProposalBulkDecider.Skip) = AgentProposalBulkDecider.Outcome(
            action,
            (1..decided).map { AgentProposalBulkDecider.Decided(listOf("agent-$it"), "op-$it") },
            skipped.mapIndexed { index, reason -> AgentProposalBulkDecider.Skipped("agent-skip-$index", reason) },
        )
        assertEquals("4 proposals accepted · 1 left undecided: source changed on this phone",
            bulkDecisionSummary(outcome("accept", 4, AgentProposalBulkDecider.Skip.SOURCE_CHANGED), resourceWords))
        assertEquals("1 proposal rejected · 2 left undecided: already decided",
            bulkDecisionSummary(outcome("disregard", 1, AgentProposalBulkDecider.Skip.ALREADY_DECIDED, AgentProposalBulkDecider.Skip.ALREADY_DECIDED), resourceWords))
        assertEquals("2 proposals rejected — sent with the next Sync.", bulkDecisionSummary(outcome("disregard", 2), resourceWords))
    }

    @Test
    fun theSharedChecksGiveTheReasonTheSheetShows() {
        val hash = "d".repeat(64)
        assertNull(AgentProposalDecider.undecidableReason(hash, sourceFound = true, sourceChanged = false, decision = null))
        assertEquals(AgentProposalDecider.Undecidable.SOURCE_MISSING, AgentProposalDecider.undecidableReason(hash, false, false, null))
        assertEquals(AgentProposalDecider.Undecidable.SOURCE_CHANGED, AgentProposalDecider.undecidableReason(hash, true, true, null))
        assertEquals(AgentProposalDecider.Undecidable.UNKNOWN_OBJECT, AgentProposalDecider.undecidableReason("x", true, false, null))
    }

    private data class ProposalTexExcerptOf(val before: String, val after: String)

    /** The production summary words ([AgentEditsBulkWords]) read from res/values, as Android would (English plurals). */
    private val resourceWords: BulkDecisionWords by lazy {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/comments_sheet_strings.xml"))
        fun names(type: Class<*>) = type.fields.associate { it.getInt(null) to it.name }
        val stringNames = names(R.string::class.java)
        val pluralNames = names(R.plurals::class.java)
        fun android(text: String) = text.trim().removeSurrounding("\"")
        fun string(name: String): String {
            val nodes = document.getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("name") == name }.textContent.let(::android)
        }
        fun plural(name: String, count: Int): String {
            val nodes = document.getElementsByTagName("plurals")
            val plurals = (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("name") == name }
            val items = plurals.getElementsByTagName("item")
            val quantity = if (count == 1) "one" else "other"
            return (0 until items.length).map { items.item(it) as Element }.single { it.getAttribute("quantity") == quantity }.textContent.let(::android)
        }
        AgentEditsBulkWords(
            { id, args -> String.format(string(stringNames.getValue(id)), *args) },
            { id, count, args -> String.format(plural(pluralNames.getValue(id), count), *args) },
        )
    }
}
