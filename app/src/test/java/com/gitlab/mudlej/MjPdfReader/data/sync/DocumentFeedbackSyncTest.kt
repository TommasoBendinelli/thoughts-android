// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** protocol/document-feedback.md §1: uploads wait for document_feedback_v1 and full Syncs; document Sync scope. */
class DocumentFeedbackSyncTest {
    private val upload = ReviewCardFeedback.Upload(
        "op-current", "9d2f4a51-7c0e-4b8a-a6f1-3e5b2c7d8e90", 1L, "2026-10-28T10:00:00.000Z", JsonObject(),
    )

    private fun row(id: String, state: String = ThoughtsOutboxEntity.STATE_PENDING, type: String = DocumentFeedback.ENTITY_TYPE) =
        ThoughtsOutboxEntity(id, id.hashCode().toLong(), type, upload.entityId, DocumentFeedback.COLLECTION,
            null, "2026-10-28T09:00:00.000Z", "{}", 0L, deliveryState = state)

    @Test fun pendingFeedbackWaitsForAServerThatAcceptsIt() {
        val older = listOf(row("older"))
        // Card feedback support is not enough: document feedback has its own capability.
        for (capabilities in listOf(emptyList(), listOf(ReviewCardFeedback.CAPABILITY, "pdf_link_overrides_v1"))) {
            val plan = documentFeedbackOutboxPlan(capabilities, targeted = false, pending = listOf(upload), undelivered = older)
            assertEquals(emptyList<ReviewCardFeedback.Upload>(), plan.uploads)
            assertEquals(emptyList<String>(), plan.superseded)
        }
        // Targeted Syncs (one PDF) leave it for the next full Sync.
        assertEquals(emptyList<ReviewCardFeedback.Upload>(),
            documentFeedbackOutboxPlan(listOf(DocumentFeedback.CAPABILITY), true, listOf(upload), older).uploads)
        // Once the server advertises the capability it is sent.
        assertEquals(listOf(upload),
            documentFeedbackOutboxPlan(listOf(DocumentFeedback.CAPABILITY), false, listOf(upload), emptyList()).uploads)
    }

    @Test fun onlyTheLatestRevisionOfEachRecordIsSent() {
        val undelivered = listOf(row("older"), row("blocked", ThoughtsOutboxEntity.STATE_BLOCKED), row(upload.operationId),
            row("card", type = ReviewCardFeedback.ENTITY_TYPE), row("sent", ThoughtsOutboxEntity.STATE_ACCEPTED))
        val plan = documentFeedbackOutboxPlan(listOf(DocumentFeedback.CAPABILITY), false, listOf(upload), undelivered)
        assertEquals(listOf(upload), plan.uploads)
        assertEquals(listOf("older", "blocked"), plan.superseded)
    }

    @Test fun documentSyncScopesFeedbackByTheAnchorsDocumentId() {
        val documentId = "c1e62ecc-2dfb-4259-97cc-e319b1186848"
        val payload = JsonParser.parseString(
            """{"anchor":{"pdf":{"document_id":"$documentId","pdf_sha256":"${"a".repeat(64)}","regions":[]}}}""",
        ).asJsonObject
        assertTrue(documentFeedbackBelongsTo(payload, documentId))
        assertTrue(documentFeedbackBelongsTo(payload, documentId.uppercase()))
        assertFalse(documentFeedbackBelongsTo(payload, "0b5c4c4e-7a8e-4d7b-9a51-1f1f2f3f4f5f"))
        assertFalse(documentFeedbackBelongsTo(payload, null))
        assertFalse(documentFeedbackBelongsTo(JsonObject(), documentId))
        assertFalse(documentFeedbackBelongsTo(JsonParser.parseString("""{"anchor":{"pdf":[]}}""").asJsonObject, documentId))
    }

    @Test fun aRecordWithAKindWaitsForTheKindsCapabilityAndHoldsNothingElseBack() {
        val question = upload.copy(operationId = "op-question", payload = JsonObject().apply { addProperty("kind", "question") })
        val like = upload.copy(operationId = "op-like")
        val pending = listOf(question, like)
        val older = documentFeedbackOutboxPlan(listOf(DocumentFeedback.CAPABILITY), false, pending, listOf(row("op-question")))
        assertEquals(listOf(like), older.uploads)
        assertEquals(emptyList<String>(), older.superseded) // held, not stale
        assertEquals(pending, documentFeedbackOutboxPlan(listOf(DocumentFeedback.CAPABILITY, DocumentFeedback.KINDS_CAPABILITY), false, pending, emptyList()).uploads)
    }
}
