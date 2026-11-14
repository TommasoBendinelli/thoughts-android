// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.isPendingSyncOperationStatus
import java.text.DateFormat
import java.util.Date

internal data class LibrarySyncStatusPresentation(
    val label: String,
    val timestamp: String,
    val hasSyncFailure: Boolean = false,
    val hasPendingChanges: Boolean = false,
    val isSyncing: Boolean = false,
    val elapsedStartedAtEpochMillis: Long? = null,
    val needsMerge: Boolean = false,
)

/** Merge needed outranks Sync failed and PDF build failed; only a running Sync is shown first. */
internal fun mergeNeededStatus(mergeFileCount: Int): LibrarySyncStatusPresentation? =
    if (mergeFileCount <= 0) {
        null
    } else {
        LibrarySyncStatusPresentation(
            label = SyncStatusLabels.MERGE_NEEDED,
            timestamp = SyncStatusLabels.sourceFilesNeedMerge(mergeFileCount),
            hasSyncFailure = true,
            needsMerge = true,
        )
    }

/**
 * The state while the only blocked local changes are ones the server refused: the issue mark
 * stays (the user must retry or discard them), but the label does not call a Sync failed.
 * Null when nothing was refused or something else is blocked too.
 */
internal fun refusedChangesStatus(
    refusedChangeCount: Int,
    hasOtherBlockedLocalChanges: Boolean,
    lastSync: String,
): LibrarySyncStatusPresentation? =
    if (refusedChangeCount <= 0 || hasOtherBlockedLocalChanges) {
        null
    } else {
        LibrarySyncStatusPresentation(
            label = SyncStatusLabels.CHANGE_REFUSED,
            timestamp = "${SyncStatusLabels.changesRefused(refusedChangeCount)} · $lastSync",
            hasSyncFailure = true,
            hasPendingChanges = true,
        )
    }

internal enum class LibrarySyncIconState {
    CURRENT,
    PENDING,
    SYNCING,
    ERROR,
}

internal fun LibrarySyncStatusPresentation.iconState(): LibrarySyncIconState = when {
    hasSyncFailure -> LibrarySyncIconState.ERROR
    isSyncing -> LibrarySyncIconState.SYNCING
    hasPendingChanges -> LibrarySyncIconState.PENDING
    else -> LibrarySyncIconState.CURRENT
}

internal fun Context.librarySyncStatusPresentation(
    operation: SyncOperationRecord?,
    lastSuccessfulSyncAtEpochMillis: Long,
    pendingFileCount: Int,
    nowEpochMillis: Long = System.currentTimeMillis(),
    hasBlockedLocalChanges: Boolean = false,
    mergeFileCount: Int = 0,
    /** PDFs Fedora was still building when Sync ended (Sync does not wait; they arrive by themselves). */
    pdfsStillBuilding: Int = 0,
    /** Changes the server refused, kept until the user retries or discards them. */
    refusedChangeCount: Int = 0,
    /** A local change is blocked for another reason than a refusal by the server. */
    hasOtherBlockedLocalChanges: Boolean = hasBlockedLocalChanges,
): LibrarySyncStatusPresentation {
    if (
        operation?.status == SyncOperationStore.STATUS_SYNCING ||
        operation?.status == SyncOperationStore.STATUS_BUILDING
    ) {
        val elapsedSeconds = ((nowEpochMillis - operation.startedAtEpochMillis)
            .coerceAtLeast(0L) / 1_000L)
        return LibrarySyncStatusPresentation(
            label = getString(R.string.document_status_syncing),
            timestamp = resources.getQuantityString(
                R.plurals.home_sync_elapsed_seconds,
                elapsedSeconds.toInt(),
                elapsedSeconds,
            ),
            isSyncing = true,
            elapsedStartedAtEpochMillis = operation.startedAtEpochMillis,
        )
    }

    mergeNeededStatus(mergeFileCount)?.let { return it }

    val pendingSummary = pendingChangesSummary(pendingFileCount)
    if (
        operation?.status == SyncOperationStore.STATUS_ERROR ||
        operation?.status == SyncOperationStore.STATUS_ATTENTION
    ) {
        val attemptedAt = operation.startedAtEpochMillis
            .takeIf { it > 0 }
            ?: operation.updatedAtEpochMillis.takeIf { it > 0 }
        return LibrarySyncStatusPresentation(
            label = syncFailureLabel(operation, pendingFileCount, hasBlockedLocalChanges),
            timestamp = (attemptedAt?.let { timestamp ->
                getString(
                    R.string.home_last_sync_failed,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(timestamp)),
                )
            } ?: getString(R.string.home_last_sync_failed_time_unknown)).let { attempt ->
                // The label names the state; the detail says whether the changes themselves arrived.
                val buildOnly = !hasBlockedLocalChanges &&
                    com.gitlab.mudlej.MjPdfReader.data.sync.isTerminalPdfBuildFailure(operation)
                when {
                    buildOnly && pendingFileCount > 0 -> "${SyncStatusLabels.CHANGES_PENDING} · $attempt"
                    buildOnly -> "${SyncStatusLabels.CHANGES_SYNCED_DETAIL} · $attempt"
                    else -> attempt
                }
            },
            hasSyncFailure = true,
            hasPendingChanges = pendingFileCount > 0,
        )
    }

    if (operation != null && isPendingSyncOperationStatus(operation.status)) {
        return LibrarySyncStatusPresentation(
            label = getString(R.string.document_status_syncing),
            timestamp = operation.events.lastOrNull().orEmpty(),
            isSyncing = true,
        )
    }

    val lastSync = if (lastSuccessfulSyncAtEpochMillis > 0) {
        getString(
            R.string.home_last_sync,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(lastSuccessfulSyncAtEpochMillis)),
        )
    } else {
        getString(R.string.home_last_sync_never)
    }

    if (hasBlockedLocalChanges) {
        // Only refused changes are blocked: the Syncs succeed, so the state is not "Sync failed".
        refusedChangesStatus(refusedChangeCount, hasOtherBlockedLocalChanges, lastSync)?.let { return it }
        return LibrarySyncStatusPresentation(
            label = SyncStatusLabels.SYNC_FAILED,
            timestamp = getString(R.string.sync_blocked_uploads_details),
            hasSyncFailure = true,
            hasPendingChanges = true,
        )
    }
    val building = if (pdfsStillBuilding > 0) {
        com.gitlab.mudlej.MjPdfReader.data.sync.pdfsStillBuildingMessage(pdfsStillBuilding) + " · "
    } else {
        ""
    }
    return LibrarySyncStatusPresentation(
        label = when {
            pendingFileCount > 0 -> SyncStatusLabels.CHANGES_PENDING
            lastSuccessfulSyncAtEpochMillis > 0 -> SyncStatusLabels.UP_TO_DATE
            else -> pendingSummary
        },
        timestamp = building + if (pendingFileCount > 0) "$pendingSummary · $lastSync" else lastSync,
        hasPendingChanges = pendingFileCount > 0,
    )
}

private fun Context.pendingChangesSummary(pendingFileCount: Int): String =
    if (pendingFileCount == 0) {
        getString(R.string.home_no_pending_syncs)
    } else {
        resources.getQuantityString(
            R.plurals.home_files_have_pending_changes,
            pendingFileCount,
            pendingFileCount,
        )
    }

internal fun syncFailureLabel(operation: SyncOperationRecord, @Suppress("UNUSED_PARAMETER") pendingFileCount: Int, blocked: Boolean): String {
    if (blocked || !com.gitlab.mudlej.MjPdfReader.data.sync.isTerminalPdfBuildFailure(operation)) return SyncStatusLabels.SYNC_FAILED
    return SyncStatusLabels.PDF_BUILD_FAILED
}
