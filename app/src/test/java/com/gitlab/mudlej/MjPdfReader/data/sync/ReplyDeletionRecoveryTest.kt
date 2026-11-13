// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.tbend.thoughts.sdk.ProjectionRecord

class ReplyDeletionRecoveryTest {
    private fun payload(parent: String? = "root", deleted: Boolean = true) = JsonParser.parseString(
        """{"comment_id":"reply","parent_id":${parent?.let { "\"$it\"" } ?: "null"},"thread_root_id":"root","origin":null,"subtype":"Note","deleted":$deleted}"""
    ).asJsonObject
    private fun operation() = ThoughtsOutboxEntity("op", 1, "logical_comment", "reply", "comments", 1,
        "2026-09-13T00:00:00Z", payload().toString(), 1, deliveryState = "blocked",
        failureCode = "http_409", failureDetail = "logical comment reply references a missing parent")
    private fun records(): Map<String, ProjectionRecord> = mapOf(
        "reply" to ProjectionRecord("logical_comment", "reply", "comments", 2, true, payload()),
        "root" to ProjectionRecord("logical_comment", "root", "comments", 2, true,
            payload(null).apply { addProperty("comment_id", "root") }),
    )
    @Test fun matchingCanonicalDeletionCanBeReconciled() {
        assertEquals("reply", confirmedReplyDeletion(operation(), records())?.entityId)
    }
    @Test fun unknownRestoredOrChangedIdentitiesRemainBlocked() {
        val original = records()
        assertNull(confirmedReplyDeletion(operation(), original - "root"))
        assertNull(confirmedReplyDeletion(operation(), original - "reply"))
        for (id in listOf("reply", "root")) {
            assertNull(confirmedReplyDeletion(operation(), original + (id to original.getValue(id).copy(deleted = false))))
        }
        for (field in listOf("comment_id", "parent_id", "thread_root_id", "origin", "subtype")) {
            val changed = original.getValue("reply").payload.deepCopy().apply { addProperty(field, "different") }
            assertNull(confirmedReplyDeletion(operation(), original + ("reply" to original.getValue("reply").copy(payload = changed))))
        }
        assertNull(confirmedReplyDeletion(operation().copy(deliveryState = "pending"), original))
        assertNull(confirmedReplyDeletion(operation().copy(payloadJson = "invalid"), original))
        assertNull(confirmedReplyDeletion(operation().copy(payloadJson = payload(deleted = false).toString()), original))
    }
}
