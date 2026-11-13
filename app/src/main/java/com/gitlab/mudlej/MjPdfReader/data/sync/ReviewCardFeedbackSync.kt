// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback

/** What a full Sync does with pending review card feedback before it sends the outbox. */
internal data class ReviewCardFeedbackOutboxPlan(
    val uploads: List<ReviewCardFeedback.Upload>,
    /** Earlier unsent writes of a record; only its latest revision is worth sending. */
    val superseded: List<String>,
)

/**
 * Sends pending feedback only to servers that accept it ([ReviewCardFeedback.CAPABILITY]) and only
 * on full Syncs; otherwise it stays pending (and shown on this phone) for a later Sync.
 */
internal fun reviewCardFeedbackOutboxPlan(
    capabilities: Collection<String>,
    targeted: Boolean,
    pending: List<ReviewCardFeedback.Upload>,
    undelivered: List<ThoughtsOutboxEntity>,
): ReviewCardFeedbackOutboxPlan {
    if (ReviewCardFeedback.CAPABILITY !in capabilities || targeted) return ReviewCardFeedbackOutboxPlan(emptyList(), emptyList())
    val current = pending.mapTo(hashSetOf()) { it.operationId }
    val stale = undelivered.filter {
        it.entityType == ReviewCardFeedback.ENTITY_TYPE && it.isUndelivered && it.operationId !in current
    }.map { it.operationId }
    return ReviewCardFeedbackOutboxPlan(pending, stale)
}
