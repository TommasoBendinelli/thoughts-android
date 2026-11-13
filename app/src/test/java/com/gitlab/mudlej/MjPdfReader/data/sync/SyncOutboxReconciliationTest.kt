// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.thoughts.sdk.ChangeRecord

class SyncOutboxReconciliationTest {
    @Test
    fun identicalAppliedChangeMatchesEvenWithDifferentDeviceSequenceAndLegacyEntityType() {
        val operation = operation()
        val change = change(deviceSequence = 19, entityType = "comment")

        assertTrue(outboxMatchesServerChange(operation, change))
    }

    @Test
    fun changedPayloadDoesNotMatch() {
        val operation = operation()
        val change = change(payloadJson = """{"revision":1,"contents":"different"}""")

        assertFalse(outboxMatchesServerChange(operation, change))
    }

    @Test
    fun changedTimestampDoesNotMatch() {
        val operation = operation()
        val change = change(clientTimestamp = "2026-08-16T10:00:01Z")

        assertFalse(outboxMatchesServerChange(operation, change))
    }

    @Test
    fun annotationRetryAllowsOnlyDerivedPdfIdentityToAdvance() {
        val existing = """{"contents":"same","pdf_sha256":"${"a".repeat(64)}"}"""
        val candidate = JsonParser.parseString(
            """{"contents":"same","pdf_sha256":"${"b".repeat(64)}"}"""
        ).asJsonObject

        assertTrue(locallyEquivalentOperationPayload("annotation", existing, candidate))
        candidate.addProperty("contents", "different")
        assertFalse(locallyEquivalentOperationPayload("annotation", existing, candidate))
        assertFalse(
            locallyEquivalentOperationPayload(
                "review_card",
                existing,
                JsonParser.parseString(
                    """{"contents":"same","pdf_sha256":"${"b".repeat(64)}"}"""
                ).asJsonObject,
            )
        )
    }

    private fun operation() = ThoughtsOutboxEntity(
        operationId = "02630878-15a4-32bc-8baa-da6a2e488bd5",
        deviceSequence = 22,
        entityType = "annotation",
        entityId = "thoughts-89b78c76-0000-0000-0000-000000000000",
        collection = "thoughts",
        baseRevision = null,
        clientTimestamp = "2026-08-16T10:00:00Z",
        payloadJson = """{"revision":1,"contents":"same"}""",
        createdAtEpochMillis = 1,
    )

    private fun change(
        deviceSequence: Long = 22,
        entityType: String = "annotation",
        clientTimestamp: String = "2026-08-16T10:00:00Z",
        payloadJson: String = """{"revision":1,"contents":"same"}""",
    ) = ChangeRecord(
        serverSequence = 27_934,
        operationId = "02630878-15a4-32bc-8baa-da6a2e488bd5",
        deviceId = "thoughts-android",
        deviceSequence = deviceSequence,
        entityType = entityType,
        entityId = "thoughts-89b78c76-0000-0000-0000-000000000000",
        collection = "thoughts",
        baseRevision = null,
        clientTimestamp = clientTimestamp,
        payload = JsonParser.parseString(payloadJson).asJsonObject,
        outcome = "applied",
        entityRevision = 1,
        provenance = null,
    )
}
