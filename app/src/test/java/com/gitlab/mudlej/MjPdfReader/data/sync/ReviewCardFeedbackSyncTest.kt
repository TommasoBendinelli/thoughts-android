// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec conformance C2: uploads wait for review_card_feedback_v1 and full Syncs. */
class ReviewCardFeedbackSyncTest {
    private val upload = ReviewCardFeedback.Upload(
        "op-current", "5b0f6c1e-2a47-4d1b-9c35-7e0a4f2b9d13", 1L, "2026-10-20T10:00:00.000Z", JsonObject(),
    )

    private fun row(id: String, state: String = ThoughtsOutboxEntity.STATE_PENDING, type: String = ReviewCardFeedback.ENTITY_TYPE) =
        ThoughtsOutboxEntity(id, id.hashCode().toLong(), type, upload.entityId, ReviewCardFeedback.COLLECTION,
            null, "2026-10-20T09:00:00.000Z", "{}", 0L, deliveryState = state)

    @Test fun pendingFeedbackWaitsForAServerThatAcceptsIt() {
        val older = listOf(row("older"))
        for (capabilities in listOf(emptyList(), listOf(REVIEW_TAG_GROUPS_CAPABILITY, "pdf_link_overrides_v1"))) {
            val plan = reviewCardFeedbackOutboxPlan(capabilities, targeted = false, pending = listOf(upload), undelivered = older)
            assertEquals(emptyList<ReviewCardFeedback.Upload>(), plan.uploads)
            assertEquals(emptyList<String>(), plan.superseded)
        }
        // Targeted Syncs (one PDF) leave it for the next full Sync.
        assertEquals(emptyList<ReviewCardFeedback.Upload>(),
            reviewCardFeedbackOutboxPlan(listOf(ReviewCardFeedback.CAPABILITY), true, listOf(upload), older).uploads)
        // Once the server advertises the capability it is sent.
        assertEquals(listOf(upload),
            reviewCardFeedbackOutboxPlan(listOf(ReviewCardFeedback.CAPABILITY), false, listOf(upload), emptyList()).uploads)
    }

    @Test fun onlyTheLatestRevisionOfEachRecordIsSent() {
        val undelivered = listOf(row("older"), row("blocked", ThoughtsOutboxEntity.STATE_BLOCKED), row(upload.operationId),
            row("links", type = "pdf_link_override"), row("sent", ThoughtsOutboxEntity.STATE_ACCEPTED))
        val plan = reviewCardFeedbackOutboxPlan(listOf(ReviewCardFeedback.CAPABILITY), false, listOf(upload), undelivered)
        assertEquals(listOf(upload), plan.uploads)
        assertEquals(listOf("older", "blocked"), plan.superseded)
    }
}
