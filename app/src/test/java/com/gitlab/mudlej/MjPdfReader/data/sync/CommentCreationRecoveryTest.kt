// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.tbend.thoughts.sdk.ProjectionRecord

class CommentCreationRecoveryTest {
    // Shapes observed on a phone: the recapture carried an empty fragment set and a newer scope mirror.
    private fun recaptured(): JsonObject = JsonParser.parseString(
        """{"schema_version":1,"comment_id":"thoughts-comment","thread_root_id":"thoughts-comment","parent_id":null,"revision":1,"created_at":"2026-09-29T04:48:22.018Z","updated_at":"2026-09-29T04:48:22.018Z","origin_id":"thoughts-android","deleted":false,"contents":"Show the iteration and the improvement over time","author":"Author","subtype":"Text","selected_text":"","color":[1.0,1.0,0.0],"priority":"normal","chatgpt_url":"","scope":{"state":"needs_attention","reason":"pdf_revision_unavailable"},"origin":{"collection":"applications_nvidia","document_id":"943daca6-d04b-4770-8a63-d069f5b440a8","pdf_sha256":"f0abefacb0ffcc22442b91c110150a766d2edc33fc003fa2d863f2a9ba4bb962","page_index":1,"rect":[],"fragments":[],"geometry_format":"pdf_points_bottom_left","raw_pdf_geometry":{"page":2,"rect":[270.9804,430.81467,291.2363,410.5531],"fragments":[]}},"source_authority":"client-v1"}"""
    ).asJsonObject
    private fun accepted(): JsonObject = JsonParser.parseString(
        """{"schema_version":1,"comment_id":"thoughts-comment","thread_root_id":"thoughts-comment","parent_id":null,"revision":1,"created_at":"D:20260929064821+02'00'","updated_at":"2026-09-29T04:48:22.018Z","origin_id":"thoughts-android","deleted":false,"contents":"Show the iteration and the improvement over time","author":"Author","subtype":"Text","color":[1.0,1.0,0.0],"priority":"normal","scope":{"state":"needs_attention","reason":"awaiting_client_source","anchor_id":"thoughts-comment"},"origin":{"collection":"applications_nvidia","document_id":"943daca6-d04b-4770-8a63-d069f5b440a8","pdf_sha256":"f0abefacb0ffcc22442b91c110150a766d2edc33fc003fa2d863f2a9ba4bb962","page_index":1,"rect":[],"fragments":[],"geometry_format":"pdf_points_bottom_left","raw_pdf_geometry":{"page":2,"rect":[270.9804,430.81467,291.2363,410.5531]}},"needs_action":true,"source_authority":"client-v1","content_updated_at":"2026-09-29T04:48:22.018Z"}"""
    ).asJsonObject
    private fun operation(payload: JsonObject = recaptured(), detail: String = "logical comment origin is immutable") =
        ThoughtsOutboxEntity("op", 7031, "logical_comment", "thoughts-comment", "comments", 0,
            "2026-09-29T04:48:22.018Z", payload.toString(), 1, deliveryState = "blocked",
            failureCode = "http_409", failureDetail = detail)
    private fun records(payload: JsonObject = accepted(), deleted: Boolean = false) = mapOf(
        "thoughts-comment" to ProjectionRecord("logical_comment", "thoughts-comment", "comments", 1, deleted, payload),
    )

    @Test fun recapturedCommentAlreadyOnFedoraCanBeReconciled() {
        assertEquals(1L, confirmedDuplicateCreation(operation(), records())?.revision?.toLong())
        assertEquals("thoughts-comment", confirmedDuplicateCreation(
            operation(detail = "logical comment revision conflict: expected base_revision 1, received 0"), records(),
        )?.entityId)
    }

    @Test fun differentOrUnconfirmedCommentsRemainBlocked() {
        assertNull(confirmedDuplicateCreation(operation(), emptyMap()))
        assertNull(confirmedDuplicateCreation(operation(), records(deleted = true)))
        for (field in listOf("contents", "author", "subtype", "priority", "selected_text", "parent_id")) {
            val changed = recaptured().apply { addProperty(field, "different") }
            assertNull(field, confirmedDuplicateCreation(operation(changed), records()))
        }
        val moved = recaptured().apply {
            getAsJsonObject("origin").getAsJsonObject("raw_pdf_geometry").addProperty("page", 3)
        }
        assertNull(confirmedDuplicateCreation(operation(moved), records()))
        val fragments = recaptured().apply {
            getAsJsonObject("origin").getAsJsonObject("raw_pdf_geometry")
                .add("fragments", JsonArray().apply { add(JsonArray().apply { add(1); add(2) }) })
        }
        assertNull(confirmedDuplicateCreation(operation(fragments), records()))
        assertNull(confirmedDuplicateCreation(operation(recaptured().apply { addProperty("deleted", true) }), records()))
        assertNull(confirmedDuplicateCreation(operation(recaptured().apply { addProperty("revision", 2) }), records()))
        assertNull(confirmedDuplicateCreation(operation(detail = "logical comment reply references a missing parent"), records()))
        assertNull(confirmedDuplicateCreation(operation().copy(baseRevision = 1), records()))
        assertNull(confirmedDuplicateCreation(operation().copy(deliveryState = "pending"), records()))
        assertNull(confirmedDuplicateCreation(operation().copy(payloadJson = "invalid"), records()))
    }
}
