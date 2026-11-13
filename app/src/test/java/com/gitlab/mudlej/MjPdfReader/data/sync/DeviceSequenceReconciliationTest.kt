// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSequenceReconciliationTest {
    @Test
    fun rebasesUndeliveredRowsFromTheFedoraFrontier() {
        val first = outbox("first", 1, ThoughtsOutboxEntity.STATE_BLOCKED)
        val second = outbox("second", 2)

        val plan = planDeviceSequenceReconciliation(
            remoteNextDeviceSequence = 167,
            localNextDeviceSequence = 3,
            allOutbox = listOf(first, second),
            allSourceRequests = emptyList(),
            activeSourceRequestIds = emptySet(),
        )

        assertEquals(listOf(167L, 168L), plan.assignments.map { it.deviceSequence })
        assertEquals(listOf("first", "second"), plan.assignments.map { it.id })
        assertEquals(169L, plan.nextDeviceSequence)
    }

    @Test
    fun keepsValidReservationsAndOnlyAdvancesTheLocalAllocator() {
        val plan = planDeviceSequenceReconciliation(
            remoteNextDeviceSequence = 167,
            localNextDeviceSequence = 169,
            allOutbox = listOf(outbox("first", 167), outbox("second", 168)),
            allSourceRequests = emptyList(),
            activeSourceRequestIds = emptySet(),
        )

        assertEquals(emptyList<DeviceSequenceReservation>(), plan.assignments)
        assertEquals(169L, plan.nextDeviceSequence)
    }

    @Test
    fun preservesGlobalReservationOrderAcrossOutboxAndSourceSaves() {
        val plan = planDeviceSequenceReconciliation(
            remoteNextDeviceSequence = 20,
            localNextDeviceSequence = 4,
            allOutbox = listOf(outbox("comment", 1), outbox("proposal", 3)),
            allSourceRequests = listOf(ThoughtsSourceRequestEntity("source", 2)),
            activeSourceRequestIds = setOf("source"),
        )

        assertEquals(listOf("comment", "source", "proposal"), plan.assignments.map { it.id })
        assertEquals(listOf(20L, 21L, 22L), plan.assignments.map { it.deviceSequence })
        assertEquals(23L, plan.nextDeviceSequence)
    }

    @Test
    fun retriesOnlySequenceCollisionFailuresAfterRebasing() {
        val collision = outbox("collision", 1, ThoughtsOutboxEntity.STATE_BLOCKED).copy(
            failureCode = "http_409",
            failureDetail = "device sequence 1 is already assigned to another operation",
        )
        val stale = outbox("stale", 2, ThoughtsOutboxEntity.STATE_BLOCKED).copy(
            failureCode = "http_409",
            failureDetail = "canonical source changed",
        )

        val retried = collision.afterDeviceSequenceReconciliation(167)
        val preserved = stale.afterDeviceSequenceReconciliation(168)

        assertEquals(ThoughtsOutboxEntity.STATE_PENDING, retried.deliveryState)
        assertEquals("", retried.failureCode)
        assertEquals(ThoughtsOutboxEntity.STATE_BLOCKED, preserved.deliveryState)
        assertEquals("canonical source changed", preserved.failureDetail)
    }

    private fun outbox(
        id: String,
        sequence: Long,
        state: String = ThoughtsOutboxEntity.STATE_PENDING,
    ) = ThoughtsOutboxEntity(
        operationId = id,
        deviceSequence = sequence,
        entityType = "logical_comment",
        entityId = id,
        collection = "comments",
        baseRevision = 0,
        clientTimestamp = "2026-08-29T00:00:00Z",
        payloadJson = "{}",
        createdAtEpochMillis = 1,
        deliveryState = state,
    )
}
