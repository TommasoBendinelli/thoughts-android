// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "ThoughtsSyncState")
data class ThoughtsSyncStateEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val nextDeviceSequence: Long = 1,
    val serverCursor: Long = 0,
    @ColumnInfo(defaultValue = "") val libraryRevision: String = "",
    @ColumnInfo(defaultValue = "") val artifactRevision: String = "",
    @ColumnInfo(defaultValue = "") val sourceBundleSha256: String = "",
    @ColumnInfo(defaultValue = "0") val projectionBootstrapped: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lastSyncAttemptEpochMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastSyncSuccessEpochMillis: Long = 0,
    @ColumnInfo(defaultValue = "") val lastSyncErrorCode: String = "",
    @ColumnInfo(defaultValue = "") val lastSyncErrorMessage: String = "",
    @ColumnInfo(defaultValue = "0") val blockedOperationCount: Int = 0,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "ThoughtsOutbox",
    indices = [Index(value = ["deviceSequence"], unique = true), Index(value = ["entityType", "collection", "proposalSourceFile"])],
)
data class ThoughtsOutboxEntity(
    @androidx.room.PrimaryKey val operationId: String,
    val deviceSequence: Long,
    val entityType: String,
    val entityId: String,
    val collection: String,
    val baseRevision: Long?,
    val clientTimestamp: String,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "'pending'") val deliveryState: String = STATE_PENDING,
    val serverSequence: Long? = null,
    @ColumnInfo(defaultValue = "") val failureCode: String = "",
    @ColumnInfo(defaultValue = "") val failureDetail: String = "",
    @ColumnInfo(defaultValue = "0") val lastAttemptEpochMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val acceptedAtEpochMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val proposalSourceFile: String =
        if (entityType == "proposal_event" && collection == "proposals") proposalHistorySourceFile(payloadJson) else "",
) {
    val isUndelivered: Boolean
        get() = deliveryState == STATE_PENDING || deliveryState == STATE_BLOCKED

    companion object {
        const val STATE_SUPERSEDED = "superseded"
        const val STATE_RECONCILED = "reconciled"
        const val STATE_PENDING = "pending"
        const val STATE_ACCEPTED = "accepted"
        const val STATE_BLOCKED = "blocked"
    }
}

/** One latest local operation per comment, independent of retained delivery history. */
@Entity(tableName = "ThoughtsLocalCommentState")
data class ThoughtsLocalCommentStateEntity(
    @PrimaryKey val entityId: String,
    val operationId: String,
    val revision: Long,
    val deviceSequence: Long,
    val deleted: Boolean,
    val payloadJson: String,
)

@Entity(
    tableName = "ThoughtsProjection",
    primaryKeys = ["entityType", "collection", "entityId"],
    indices = [Index(value = ["entityType", "collection", "proposalSourceFile"])],
)
data class ThoughtsProjectionEntity(
    val entityType: String,
    val collection: String,
    val entityId: String,
    val revision: Long,
    val deleted: Boolean,
    val payloadJson: String,
    val serverSequence: Long,
    @ColumnInfo(defaultValue = "''") val proposalSourceFile: String =
        if (entityType == "proposal_event" && collection == "proposals") proposalHistorySourceFile(payloadJson) else "",
)

data class ReviewProjectionIdentity(val entityType: String, val entityId: String)

@Entity(tableName = "ThoughtsSourceSnapshot")
data class ThoughtsSourceSnapshotEntity(
    @androidx.room.PrimaryKey val path: String,
    val revision: Long,
    val sha256: String,
    val objectSha256: String,
    val resolutionId: String,
)

@Entity(
    tableName = "ThoughtsSourceRequest",
    indices = [Index(value = ["deviceSequence"], unique = true)],
)
data class ThoughtsSourceRequestEntity(
    @androidx.room.PrimaryKey val requestId: String,
    val deviceSequence: Long,
)

@Entity(tableName = "ThoughtsBuildRequest")
data class ThoughtsBuildRequestEntity(
    @androidx.room.PrimaryKey val requestId: String,
    val jobId: String,
    val target: String,
)

@Entity(
    tableName = "sync_runs",
    indices = [
        Index(value = ["traceId"], unique = true),
        Index(value = ["state", "updatedAtEpochMillis"]),
        Index(value = ["target", "updatedAtEpochMillis"]),
    ],
)
data class ThoughtsSyncRunEntity(
    @PrimaryKey val id: String,
    val traceId: String,
    val retryOf: String? = null,
    val target: String? = null,
    val kind: String,
    val state: String,
    val phase: String,
    val displayStatus: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val displayMessage: String = "",
    val eventsJson: String = "[]",
    val eventTimestampsJson: String = "[]",
    val buildProgressJson: String = "[]",
    val buildCheckedAtEpochMillis: Long = 0,
    val attempt: Int = 0,
    val planJson: String = "",
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val transferMetricsAvailable: Boolean = false,
    val uploadBytesCompleted: Long = 0,
    val uploadBytesTotal: Long = 0,
    val downloadBytesCompleted: Long = 0,
    val downloadBytesTotal: Long = 0,
    val transferPlanId: String = "",
    val schedulerKind: String = "",
    val schedulerId: String = "",
    val serverState: String = "",
    val serverTerminal: Boolean = false,
    val lastServerSequence: Long = 0,
    val streamState: String = "offline",
    val legacyEventsImported: Boolean = false,
)

@Entity(
    tableName = "sync_steps",
    primaryKeys = ["runId", "ordinal"],
    indices = [Index(value = ["runId", "state"])],
)
data class ThoughtsSyncStepEntity(
    val runId: String,
    val ordinal: Int,
    val phase: String,
    val state: String,
    val attempt: Int,
    val requestId: String = "",
    val startedAtEpochMillis: Long = 0,
    val updatedAtEpochMillis: Long = 0,
    val deadlineEpochMillis: Long = 0,
    val factsJson: String = "",
)

@Entity(
    tableName = "sync_events",
    indices = [
        Index(value = ["runId", "createdAtEpochMillis"]),
        Index(value = ["runId", "origin", "serverSequence"], unique = true),
    ],
)
data class ThoughtsSyncEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val phase: String,
    val state: String,
    val attempt: Int,
    val message: String,
    val errorCode: String = "",
    val createdAtEpochMillis: Long,
    val origin: String = "phone",
    val serverSequence: Long? = null,
    val serverTimestampEpochMillis: Long = 0,
    val receivedTimestampEpochMillis: Long = 0,
    val component: String = "phone",
    val buildId: String = "",
    val completed: Long? = null,
    val total: Long? = null,
    val bytesCompleted: Long? = null,
    val bytesTotal: Long? = null,
    val metadataJson: String = "{}",
)

/** Exact ownership and thread references; independent of free-form comment text. */
@Entity(tableName = "ThoughtsCommentScope", primaryKeys = ["scopeKey", "entityId", "recordSource"])
data class ThoughtsCommentScopeEntity(val scopeKey: String, val entityId: String, val recordSource: String)
