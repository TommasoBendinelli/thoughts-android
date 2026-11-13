// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.google.gson.JsonObject

/** What a full Sync does with pending document feedback before it sends the outbox. */
internal data class DocumentFeedbackOutboxPlan(
    val uploads: List<ReviewCardFeedback.Upload>,
    /** Earlier unsent writes of a record; only its latest revision is worth sending. */
    val superseded: List<String>,
)

/**
 * Sends pending document feedback only to servers that accept it ([DocumentFeedback.CAPABILITY])
 * and only on full Syncs; otherwise it stays pending (and shown on this phone) for a later Sync.
 * Records with a `kind` also wait for [DocumentFeedback.KINDS_CAPABILITY], the rest go without them.
 */
internal fun documentFeedbackOutboxPlan(
    capabilities: Collection<String>,
    targeted: Boolean,
    pending: List<ReviewCardFeedback.Upload>,
    undelivered: List<ThoughtsOutboxEntity>,
): DocumentFeedbackOutboxPlan {
    if (DocumentFeedback.CAPABILITY !in capabilities || targeted) return DocumentFeedbackOutboxPlan(emptyList(), emptyList())
    val current = pending.mapTo(hashSetOf()) { it.operationId }
    val stale = undelivered.filter {
        it.entityType == DocumentFeedback.ENTITY_TYPE && it.isUndelivered && it.operationId !in current
    }.map { it.operationId }
    val kinds = DocumentFeedback.KINDS_CAPABILITY in capabilities
    return DocumentFeedbackOutboxPlan(pending.filter { kinds || !it.payload.has("kind") }, stale)
}

/**
 * Whether a `document_feedback` record belongs to the document whose permanent id is
 * [targetDocumentId] (a document Sync's scope, spec §1: `anchor.pdf.document_id`).
 */
internal fun documentFeedbackBelongsTo(payload: JsonObject, targetDocumentId: String?): Boolean {
    if (targetDocumentId.isNullOrBlank()) return false
    val documentId = runCatching {
        payload.getAsJsonObject("anchor")?.getAsJsonObject("pdf")?.get("document_id")
            ?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull() ?: return false
    return documentId.equals(targetDocumentId, ignoreCase = true)
}
