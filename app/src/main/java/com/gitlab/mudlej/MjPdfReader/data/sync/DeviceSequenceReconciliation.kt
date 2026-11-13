// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

internal enum class DeviceSequenceReservationKind {
    OUTBOX,
    SOURCE_REQUEST,
}

internal data class DeviceSequenceReservation(
    val kind: DeviceSequenceReservationKind,
    val id: String,
    val deviceSequence: Long,
)

internal data class DeviceSequenceReconciliationPlan(
    val assignments: List<DeviceSequenceReservation>,
    val nextDeviceSequence: Long,
)

internal fun planDeviceSequenceReconciliation(
    remoteNextDeviceSequence: Long,
    localNextDeviceSequence: Long,
    allOutbox: List<ThoughtsOutboxEntity>,
    allSourceRequests: List<ThoughtsSourceRequestEntity>,
    activeSourceRequestIds: Set<String>,
): DeviceSequenceReconciliationPlan {
    require(remoteNextDeviceSequence >= 1)
    require(localNextDeviceSequence >= 1)
    val candidates = buildList {
        allOutbox
            .filter { it.isUndelivered }
            .forEach {
                add(
                    DeviceSequenceReservation(
                        DeviceSequenceReservationKind.OUTBOX,
                        it.operationId,
                        it.deviceSequence,
                    ),
                )
            }
        allSourceRequests
            .filter { it.requestId in activeSourceRequestIds }
            .forEach {
                add(
                    DeviceSequenceReservation(
                        DeviceSequenceReservationKind.SOURCE_REQUEST,
                        it.requestId,
                        it.deviceSequence,
                    ),
                )
            }
    }.sortedWith(compareBy(DeviceSequenceReservation::deviceSequence, DeviceSequenceReservation::kind, DeviceSequenceReservation::id))
    val highestAllocated = (allOutbox.asSequence().map { it.deviceSequence } +
        allSourceRequests.asSequence().map { it.deviceSequence }).maxOrNull() ?: 0L
    if (candidates.none { it.deviceSequence < remoteNextDeviceSequence }) {
        return DeviceSequenceReconciliationPlan(
            assignments = emptyList(),
            nextDeviceSequence = maxOf(
                remoteNextDeviceSequence,
                localNextDeviceSequence,
                highestAllocated + 1,
            ),
        )
    }
    val firstSequence = maxOf(
        remoteNextDeviceSequence,
        localNextDeviceSequence,
        highestAllocated + 1,
    )
    return DeviceSequenceReconciliationPlan(
        assignments = candidates.mapIndexed { index, reservation ->
            reservation.copy(deviceSequence = firstSequence + index)
        },
        nextDeviceSequence = firstSequence + candidates.size,
    )
}

internal fun ThoughtsOutboxEntity.afterDeviceSequenceReconciliation(
    deviceSequence: Long,
): ThoughtsOutboxEntity {
    val sequenceConflict = deliveryState == ThoughtsOutboxEntity.STATE_BLOCKED &&
        failureCode == "http_409" &&
        failureDetail.contains("device sequence", ignoreCase = true) &&
        failureDetail.contains("assigned", ignoreCase = true)
    return copy(
        deviceSequence = deviceSequence,
        deliveryState = if (sequenceConflict) ThoughtsOutboxEntity.STATE_PENDING else deliveryState,
        failureCode = if (sequenceConflict) "" else failureCode,
        failureDetail = if (sequenceConflict) "" else failureDetail,
    )
}
