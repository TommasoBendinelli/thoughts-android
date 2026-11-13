// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations
import com.gitlab.mudlej.MjPdfReader.data.links.PdfLinkOverrides
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentConversationsSyncTest {
    private val upload = AgentConversations.Upload(
        "op-current", "ef8e44d466d11197fa75b69c419f74b574068655d7b0dd3ffeced7c341db3f4d", 3L,
        "2026-10-26T12:00:00.000Z", JsonObject(),
    )

    private fun row(id: String, state: String = ThoughtsOutboxEntity.STATE_PENDING, type: String = AgentConversations.ENTITY_TYPE) =
        ThoughtsOutboxEntity(id, id.hashCode().toLong(), type, upload.entityId, AgentConversations.COLLECTION,
            3L, "2026-10-26T11:00:00.000Z", "{}", 0L, deliveryState = state)

    @Test fun removalsWaitForAServerThatAcceptsThem() {
        val older = listOf(row("older"))
        for (capabilities in listOf(emptyList(), listOf(PdfLinkOverrides.CAPABILITY, REVIEW_TAG_GROUPS_CAPABILITY))) {
            val plan = agentConversationsOutboxPlan(capabilities, targeted = false, pending = listOf(upload), undelivered = older)
            assertEquals(emptyList<AgentConversations.Upload>(), plan.uploads)
            assertEquals(emptyList<String>(), plan.superseded)
        }
    }

    @Test fun targetedSyncsLeaveThemForTheNextFullSync() {
        val plan = agentConversationsOutboxPlan(listOf(AgentConversations.CAPABILITY), true, listOf(upload), listOf(row("older")))
        assertEquals(emptyList<AgentConversations.Upload>(), plan.uploads)
        assertEquals(emptyList<String>(), plan.superseded)
    }

    @Test fun onlyTheLatestRevisionOfEachRecordIsSent() {
        val undelivered = listOf(row("older"), row("blocked", ThoughtsOutboxEntity.STATE_BLOCKED), row(upload.operationId),
            row("override", type = PdfLinkOverrides.ENTITY_TYPE), row("sent", ThoughtsOutboxEntity.STATE_ACCEPTED),
            row("overtaken", ThoughtsOutboxEntity.STATE_SUPERSEDED))
        val plan = agentConversationsOutboxPlan(listOf(AgentConversations.CAPABILITY), false, listOf(upload), undelivered)
        assertEquals(listOf(upload), plan.uploads)
        assertEquals(listOf("older", "blocked"), plan.superseded)
        val none = agentConversationsOutboxPlan(listOf(AgentConversations.CAPABILITY), false, emptyList(), undelivered)
        assertEquals(emptyList<AgentConversations.Upload>(), none.uploads)
        assertEquals(listOf("older", "blocked", upload.operationId), none.superseded)
    }
}
