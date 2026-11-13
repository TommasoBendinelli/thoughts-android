// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.links.PdfLinkOverrides

/** What a full Sync does with pending PDF link overrides before it sends the outbox. */
internal data class PdfLinkOverridesOutboxPlan(
    val uploads: List<PdfLinkOverrides.Upload>,
    /** Earlier unsent edits of an override; only its latest revision is worth sending. */
    val superseded: List<String>,
)

/**
 * Sends pending overrides only to servers that accept them ([PdfLinkOverrides.CAPABILITY]) and only
 * on full Syncs; otherwise they stay pending for a later Sync, as tag groups do.
 */
internal fun pdfLinkOverridesOutboxPlan(
    capabilities: Collection<String>,
    targeted: Boolean,
    pending: List<PdfLinkOverrides.Upload>,
    undelivered: List<ThoughtsOutboxEntity>,
): PdfLinkOverridesOutboxPlan {
    if (PdfLinkOverrides.CAPABILITY !in capabilities || targeted) return PdfLinkOverridesOutboxPlan(emptyList(), emptyList())
    val current = pending.mapTo(hashSetOf()) { it.operationId }
    val stale = undelivered.filter {
        it.entityType == PdfLinkOverrides.ENTITY_TYPE && it.isUndelivered && it.operationId !in current
    }.map { it.operationId }
    return PdfLinkOverridesOutboxPlan(pending, stale)
}
