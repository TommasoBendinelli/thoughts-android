// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations

/** What a full Sync does with pending agent conversation removals before it sends the outbox. */
internal data class AgentConversationsOutboxPlan(
    val uploads: List<AgentConversations.Upload>,
    /** Earlier unsent writes of a record; only its latest revision is worth sending. */
    val superseded: List<String>,
)

/**
 * Sends pending removals only to servers that accept agent conversations
 * ([AgentConversations.CAPABILITY]) and only on full Syncs; otherwise they stay pending for a later
 * Sync, as PDF link overrides do. An older server would reject the whole operation batch.
 */
internal fun agentConversationsOutboxPlan(
    capabilities: Collection<String>,
    targeted: Boolean,
    pending: List<AgentConversations.Upload>,
    undelivered: List<ThoughtsOutboxEntity>,
): AgentConversationsOutboxPlan {
    if (AgentConversations.CAPABILITY !in capabilities || targeted) return AgentConversationsOutboxPlan(emptyList(), emptyList())
    val current = pending.mapTo(hashSetOf()) { it.operationId }
    val stale = undelivered.filter {
        it.entityType == AgentConversations.ENTITY_TYPE && it.isUndelivered && it.operationId !in current
    }.map { it.operationId }
    return AgentConversationsOutboxPlan(pending, stale)
}
