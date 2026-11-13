// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.links.PdfLinkOverrides
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfLinkOverridesSyncTest {
    private val upload = PdfLinkOverrides.Upload(
        "op-current", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 1L, "2026-10-15T10:00:00.000Z", JsonObject(),
    )

    private fun row(id: String, state: String = ThoughtsOutboxEntity.STATE_PENDING, type: String = PdfLinkOverrides.ENTITY_TYPE) =
        ThoughtsOutboxEntity(id, id.hashCode().toLong(), type, upload.entityId, PdfLinkOverrides.COLLECTION,
            null, "2026-10-15T09:00:00.000Z", "{}", 0L, deliveryState = state)

    @Test fun pendingOverridesWaitForAServerThatAcceptsThem() {
        val older = listOf(row("older"))
        for (capabilities in listOf(emptyList(), listOf(REVIEW_TAG_GROUPS_CAPABILITY, REVIEW_TAG_MERGE_CAPABILITY))) {
            val plan = pdfLinkOverridesOutboxPlan(capabilities, targeted = false, pending = listOf(upload), undelivered = older)
            assertEquals(emptyList<PdfLinkOverrides.Upload>(), plan.uploads)
            assertEquals(emptyList<String>(), plan.superseded)
        }
        // Targeted Syncs (one PDF) leave them for the next full Sync.
        assertEquals(emptyList<PdfLinkOverrides.Upload>(),
            pdfLinkOverridesOutboxPlan(listOf(PdfLinkOverrides.CAPABILITY), true, listOf(upload), older).uploads)
    }

    @Test fun onlyTheLatestRevisionOfEachOverrideIsSent() {
        val undelivered = listOf(row("older"), row("blocked", ThoughtsOutboxEntity.STATE_BLOCKED), row(upload.operationId),
            row("groups", type = "review_tag_groups"), row("sent", ThoughtsOutboxEntity.STATE_ACCEPTED))
        val plan = pdfLinkOverridesOutboxPlan(listOf(PdfLinkOverrides.CAPABILITY), false, listOf(upload), undelivered)
        assertEquals(listOf(upload), plan.uploads)
        assertEquals(listOf("older", "blocked"), plan.superseded)
        val none = pdfLinkOverridesOutboxPlan(listOf(PdfLinkOverrides.CAPABILITY), false, emptyList(), undelivered)
        assertEquals(emptyList<PdfLinkOverrides.Upload>(), none.uploads)
        assertEquals(listOf("older", "blocked", upload.operationId), none.superseded)
    }
}
