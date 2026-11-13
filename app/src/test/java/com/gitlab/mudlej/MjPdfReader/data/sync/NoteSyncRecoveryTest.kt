// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.annotation.newNotePayload
import org.junit.Assert.*
import org.junit.Test

class NoteSyncRecoveryTest {
    private val failure = """{"detail":[{"type":"model_type","loc":["body","operations",0,"origin"],"input":null,"ctx":{"class_name":"LogicalCommentOrigin"}},{"type":"literal_error","loc":["body","operations",0,"scope","state"],"input":"standalone","ctx":{"expected":"'pdf_only', 'source_linked' or 'needs_attention'"}}]}"""

    private fun note(id: String, sequence: Long, deleted: Boolean = false): ThoughtsOutboxEntity {
        val payload = newNotePayload(id, "Saved text.", "2026-09-09T12:00:00Z").apply {
            addProperty("deleted", deleted)
            addProperty("revision", sequence)
        }
        return ThoughtsOutboxEntity(
            "operation-$sequence", sequence, "logical_comment", id, "comments", sequence - 1,
            "2026-09-09T12:00:00Z", payload.toString(), 0,
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED,
            failureCode = "http_422", failureDetail = failure,
        )
    }

    @Test fun onlyConfirmedServerSupportUnlocksTheLegacyRejection() {
        val row = note("note", 1)
        assertTrue(row.hasLegacyNoteSchemaFailure())
        assertTrue(recoverableNoteOperationIds(listOf(row), emptyList()).isEmpty())
        assertTrue(recoverableNoteOperationIds(listOf(row), listOf("logical_comments_v1")).isEmpty())
        assertEquals(listOf(row.operationId), recoverableNoteOperationIds(listOf(row), listOf(STANDALONE_NOTES_CAPABILITY)))
    }

    @Test fun recoveryRetainsEveryRevisionAndDeletionWithoutChangingIdentityOrContent() {
        val rows = listOf(note("first", 1), note("first", 2), note("first", 3, true), note("second", 4))
        val before = rows.map { it.copy() }
        assertEquals(rows.map { it.operationId }, recoverableNoteOperationIds(rows, listOf(STANDALONE_NOTES_CAPABILITY)))
        assertEquals(before, rows)
    }

    @Test fun otherFailuresAndCompletedOperationsRemainUntouched() {
        val original = note("note", 1)
        val excluded = listOf(
            original.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED),
            original.copy(deliveryState = ThoughtsOutboxEntity.STATE_PENDING),
            original.copy(failureCode = "http_409"),
            original.copy(entityType = "annotation"),
            original.copy(collection = "paper"),
            original.copy(payloadJson = original.payloadJson.replace("standalone", "pdf_only")),
            original.copy(failureDetail = failure.replace("LogicalCommentOrigin", "AnotherType")),
            original.copy(failureDetail = failure.replace("needs_attention", "standalone")),
            original.copy(failureDetail = "{broken"),
            original.copy(payloadJson = "{broken"),
        )
        assertTrue(recoverableNoteOperationIds(excluded, listOf(STANDALONE_NOTES_CAPABILITY)).isEmpty())
    }

    @Test fun anAdditionalValidationErrorIsNotMistakenForACompatibilityFailure() {
        val extra = failure.replace("]}", ",{" + "\"type\":\"missing\",\"loc\":[\"contents\"]}]}")
        assertFalse(note("note", 1).copy(failureDetail = extra).hasLegacyNoteSchemaFailure())
    }
}
