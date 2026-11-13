// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.DocumentSyncStatusLoader
import com.gitlab.mudlej.MjPdfReader.data.render.ExplicitPdfRenderStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusHistory
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.isTerminalPdfBuildFailure
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.data.sync.lastSuccessfulDocumentSyncAt
import com.gitlab.mudlej.MjPdfReader.ui.home.BuiltTimeKind
import com.gitlab.mudlej.MjPdfReader.ui.home.BuiltTimePresentation
import com.gitlab.mudlej.MjPdfReader.ui.home.DocumentStatusCode
import com.gitlab.mudlej.MjPdfReader.ui.home.builtTimePresentation
import com.gitlab.mudlej.MjPdfReader.ui.home.documentStatusPresentation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tbend.thoughts.sdk.ServerStorageUsage

internal data class SyncBuildActionAvailability(
    val syncEnabled: Boolean,
    val logsEnabled: Boolean,
)

internal data class SyncBuildDialogState(
    val target: String,
    val status: PhoneRenderJobStatus?,
    val lastSuccessfulSyncAtEpochMillis: Long,
    val lastSuccessfulBuildAtEpochMillis: Long,
    val serverConfigured: Boolean,
    val localOperationActive: Boolean,
    val localOperationStatus: String? = null,
    val latestOperationStatus: String? = null,
    val latestOperationKind: SyncOperationKind? = null,
    val latestFailureLabel: String? = null,
    val latestFailureDetail: String? = null,
    val serverStorage: ServerStorageUsage? = null,
)

internal enum class SyncBuildPendingAction {
    SYNC,
}

internal data class SyncBuildInteractionState(
    val pendingAction: SyncBuildPendingAction? = null,
    val pendingActionObserved: Boolean = false,
) {
    fun begin(action: SyncBuildPendingAction): SyncBuildInteractionState = copy(
        pendingAction = action,
        pendingActionObserved = false,
    )

    fun onQueueResult(queued: Boolean): SyncBuildInteractionState =
        if (queued) copy(pendingActionObserved = true) else SyncBuildInteractionState()

    fun onStatusUpdated(
        localOperationActive: Boolean,
        renderStatusActive: Boolean,
    ): SyncBuildInteractionState {
        val pendingActionActive = when (pendingAction) {
            SyncBuildPendingAction.SYNC -> localOperationActive || renderStatusActive
            null -> false
        }
        return when {
            pendingAction == null -> this
            pendingActionActive -> copy(pendingActionObserved = true)
            pendingActionObserved -> SyncBuildInteractionState()
            else -> this
        }
    }
}

internal fun syncBuildActionAvailability(
    status: PhoneRenderJobStatus?,
    localOperationActive: Boolean,
    serverConfigured: Boolean,
): SyncBuildActionAvailability {
    val operationActive = localOperationActive || syncBuildRemoteRenderActive(status)
    return SyncBuildActionAvailability(
        syncEnabled = serverConfigured && !operationActive,
        logsEnabled = true,
    )
}

/**
 * QUEUED means the request still exists only on this phone. It must not disable Sync,
 * because Sync is the recovery action that submits the request to Fedora.
 */
internal fun syncBuildRemoteRenderActive(status: PhoneRenderJobStatus?): Boolean =
    status?.isActive == true && status.stage != PhoneRenderStage.QUEUED

internal fun syncBuildLastSuccessfulBuildAt(
    status: PhoneRenderJobStatus?,
    cachedStatus: ExplicitPdfRenderStatus?,
): Long = maxOf(
    status?.lastSuccessfulBuildAtEpochMillis ?: 0L,
    cachedStatus?.lastSuccessfulBuildAtEpochMillis ?: 0L,
)

internal fun syncBuildLatestOperationLabel(
    status: String?,
    @Suppress("UNUSED_PARAMETER") kind: SyncOperationKind?,
    @Suppress("UNUSED_PARAMETER") builtLabel: String,
): String? = when {
    status == null -> null
    status == SyncOperationStore.STATUS_BUILDING -> SyncOperationStore.STATUS_SYNCING
    else -> status
}

internal fun mergeSyncBuildDialogState(
    previous: SyncBuildDialogState?,
    updated: SyncBuildDialogState,
): SyncBuildDialogState = updated.copy(
    lastSuccessfulSyncAtEpochMillis = maxOf(
        previous?.lastSuccessfulSyncAtEpochMillis ?: 0L,
        updated.lastSuccessfulSyncAtEpochMillis,
    ),
    lastSuccessfulBuildAtEpochMillis = maxOf(
        previous?.lastSuccessfulBuildAtEpochMillis ?: 0L,
        updated.lastSuccessfulBuildAtEpochMillis,
    ),
    serverStorage = if (updated.serverConfigured) {
        updated.serverStorage ?: previous?.serverStorage
    } else {
        null
    },
)

internal suspend fun loadSyncBuildDialogState(
    context: Context,
    target: String,
    currentUri: Uri?,
    includeServerStorage: Boolean = true,
): SyncBuildDialogState = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val history = PhoneRenderStatusHistory(appContext)
    val cachedStatusBeforeRefresh = history.pdfRenderStatus(target)
    val snapshot = DocumentSyncStatusLoader(appContext).load(target, currentUri)
    val status = snapshot.statusForPdf(target)
    val operationStore = SyncOperationStore(appContext)
    val operations = operationStore.summaries()
    val latestOperation = latestRelevantSyncOperationRecord(operations, target)
    val cachedStatusAfterRefresh = history.pdfRenderStatus(target)
    val activeOperation = operationStore.pendingGlobalOperation()
        ?: operationStore.pendingTargetOperation(target)
    val serverConfigured = ThoughtsServerSecretStore(appContext).config() != null
    val serverStorage = if (serverConfigured && includeServerStorage) {
        runCatching { FedoraRepository(appContext).serverStorageUsage() }.getOrNull()
    } else {
        null
    }
    SyncBuildDialogState(
        target = target,
        status = status,
        lastSuccessfulSyncAtEpochMillis = lastSuccessfulDocumentSyncAt(operations, target),
        lastSuccessfulBuildAtEpochMillis = maxOf(
            syncBuildLastSuccessfulBuildAt(status, cachedStatusBeforeRefresh),
            cachedStatusAfterRefresh?.lastSuccessfulBuildAtEpochMillis ?: 0L,
        ),
        serverConfigured = serverConfigured,
        localOperationActive = activeOperation != null,
        localOperationStatus = activeOperation?.status,
        latestOperationStatus = latestOperation?.status,
        latestOperationKind = latestOperation?.kind,
        latestFailureLabel = latestOperation?.takeIf {
            it.status in setOf(SyncOperationStore.STATUS_ERROR, SyncOperationStore.STATUS_ATTENTION)
        }?.let { syncOperationStatusLabel(it, "Built") },
        latestFailureDetail = latestOperation?.displayMessage?.takeIf(String::isNotBlank),
        serverStorage = serverStorage,
    )
}

/**
 * Attributes the most recent failure to one project's PDF.
 *
 * [latestRelevantSyncOperationRecord] also matches global (`target == null`) runs, which
 * may have failed for a different project. A reader badge for one project must not report
 * another project's failure, so a global failure is only claimed when its own build
 * evidence names this document.
 */
internal fun projectScopedSyncFailureDetail(
    records: List<SyncOperationRecord>,
    target: String,
    builtLabel: String,
): Pair<String, String>? {
    val lastSuccess = lastSuccessfulDocumentSyncAt(records, target)
    val failed = records
        .filter {
            it.status in setOf(SyncOperationStore.STATUS_ERROR, SyncOperationStore.STATUS_ATTENTION) &&
                it.updatedAtEpochMillis >= lastSuccess
        }
        .sortedByDescending(SyncOperationRecord::updatedAtEpochMillis)
    failed.forEach { record ->
        val documentFailures = record.buildProgress
            .flatMap { it.failedDocuments.orEmpty() }
            .filter { it.filename == target }
        if (documentFailures.isNotEmpty()) {
            return syncOperationStatusLabel(record, builtLabel) to
                readableBuildFailure(documentFailures.first().reason)
        }
        if (record.target == target) {
            return syncOperationStatusLabel(record, builtLabel) to record.displayMessage
        }
        if (record.target == null && isTerminalPdfBuildFailure(record) && buildScopeIsDocument(record, target)) {
            return syncOperationStatusLabel(record, builtLabel) to record.displayMessage
        }
    }
    return null
}

private fun buildScopeIsDocument(record: SyncOperationRecord, target: String): Boolean {
    val documents = record.buildProgress.flatMap { it.documents }.distinct()
    return documents == listOf(target)
}

internal fun syncBuildTimePresentation(
    timestamp: Long,
    nowEpochMillis: Long,
): BuiltTimePresentation {
    val presentation = builtTimePresentation(timestamp, nowEpochMillis)
    return if (presentation.kind == BuiltTimeKind.SECONDS) {
        BuiltTimePresentation(BuiltTimeKind.JUST_NOW)
    } else {
        presentation
    }
}

internal fun nextSyncBuildTimeRefreshDelay(
    timestamp: Long,
    nowEpochMillis: Long,
): Long? {
    if (timestamp <= 0) return null
    val age = (nowEpochMillis - timestamp).coerceAtLeast(0L)
    return when {
        age < 60_000L -> 60_000L - age
        age < 3_600_000L -> 60_000L - (age % 60_000L)
        age < 86_400_000L -> 3_600_000L - (age % 3_600_000L)
        else -> null
    }
}

internal class SyncBuildDialog(
    private val activity: AppCompatActivity,
    val target: String,
    private val onSync: ((Boolean) -> Unit) -> Unit,
    private val onShowLastLog: () -> Unit,
    private val onRefreshRequested: () -> Unit,
    private val onDismissed: () -> Unit,
    private val projectName: String = "",
) {
    private var state: SyncBuildDialogState? = null
    private var interactionState = SyncBuildInteractionState()
    private var relativeTimeRefreshJob: Job? = null
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private var lastRenderedMessage: String? = null

    fun show(state: SyncBuildDialogState) {
        this.state = state
        val shownDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sync)
            .setMessage(message(state, pendingAction = null))
            .setNeutralButton(R.string.sync_status_show_last_log, null)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.document_status_sync, null)
            .create()
        dialog = shownDialog
        shownDialog.setOnShowListener {
            shownDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (currentAvailability().syncEnabled) {
                    beginAction(SyncBuildPendingAction.SYNC)
                    onSync(::onActionQueued)
                }
            }
            shownDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                if (currentAvailability().logsEnabled) {
                    onShowLastLog()
                }
            }
            render()
        }
        shownDialog.setOnDismissListener {
            relativeTimeRefreshJob?.cancel()
            relativeTimeRefreshJob = null
            dialog = null
            lastRenderedMessage = null
            onDismissed()
        }
        shownDialog.setCancelable(true)
        shownDialog.setCanceledOnTouchOutside(true)
        shownDialog.show()
        scheduleNextRelativeTimeRefresh()
    }

    fun update(updatedState: SyncBuildDialogState) {
        if (updatedState.target != target || !isShowing()) return
        interactionState = interactionState.onStatusUpdated(
            localOperationActive = updatedState.localOperationActive,
            renderStatusActive = syncBuildRemoteRenderActive(updatedState.status),
        )
        state = mergeSyncBuildDialogState(state, updatedState)
        render()
        scheduleNextRelativeTimeRefresh()
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    fun isShowing(): Boolean = dialog?.isShowing == true

    private fun beginAction(action: SyncBuildPendingAction) {
        interactionState = interactionState.begin(action)
        render()
    }

    private fun onActionQueued(queued: Boolean) {
        if (!isShowing()) return
        interactionState = interactionState.onQueueResult(queued)
        onRefreshRequested()
        render()
    }

    private fun currentAvailability(): SyncBuildActionAvailability {
        val currentState = state ?: return SyncBuildActionAvailability(false, true)
        return syncBuildActionAvailability(
            currentState.status,
            currentState.localOperationActive || interactionState.pendingAction != null,
            currentState.serverConfigured,
        )
    }

    private fun render() {
        val currentState = state ?: return
        val currentDialog = dialog ?: return
        val renderedMessage = message(currentState, interactionState.pendingAction)
        if (renderedMessage != lastRenderedMessage) {
            currentDialog.setMessage(renderedMessage)
            lastRenderedMessage = renderedMessage
        }
        if (!currentDialog.isShowing) return
        val availability = currentAvailability()
        currentDialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = availability.logsEnabled
        currentDialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = availability.syncEnabled
    }

    private fun scheduleNextRelativeTimeRefresh() {
        relativeTimeRefreshJob?.cancel()
        relativeTimeRefreshJob = null
        val currentDialog = dialog?.takeIf { it.isShowing } ?: return
        val currentState = state ?: return
        val now = System.currentTimeMillis()
        val refreshDelay = nextSyncBuildTimeRefreshDelay(
            currentState.lastSuccessfulSyncAtEpochMillis,
            now,
        ) ?: return
        relativeTimeRefreshJob = activity.lifecycleScope.launch {
            delay(refreshDelay.coerceAtLeast(1L))
            if (dialog === currentDialog && currentDialog.isShowing) {
                render()
                scheduleNextRelativeTimeRefresh()
            }
        }
    }

    private fun message(
        state: SyncBuildDialogState,
        pendingAction: SyncBuildPendingAction?,
    ): String = buildString {
        if (projectName.isNotBlank()) {
            append(activity.getString(R.string.reader_project_sync_details_project, projectName))
            append('\n')
        }
        append(activity.getString(R.string.sync_status_target, state.target))
        append('\n')
        append(
            activity.getString(
                R.string.sync_status_state,
                operationStatusLabel(state, pendingAction),
            ),
        )
        append('\n')
        append(lastSyncedLabel(state.lastSuccessfulSyncAtEpochMillis))
        state.serverStorage?.let { storage ->
            append('\n')
            append(
                activity.getString(
                    R.string.sync_status_fedora_storage,
                    Formatter.formatShortFileSize(activity, storage.usedBytes),
                    Formatter.formatShortFileSize(activity, storage.totalBytes),
                    Formatter.formatShortFileSize(activity, storage.availableBytes),
                ),
            )
        }
        (state.latestFailureDetail ?: state.status?.message)
            ?.takeIf { pendingAction == null }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { reason ->
                append("\n\n")
                append(reason)
            }
        if (!state.serverConfigured) {
            append("\n\n")
            append(activity.getString(R.string.fedora_update_not_configured))
        }
    }

    private fun operationStatusLabel(
        state: SyncBuildDialogState,
        pendingAction: SyncBuildPendingAction?,
    ): String = when {
        pendingAction == SyncBuildPendingAction.SYNC ->
            activity.getString(R.string.document_status_syncing)
        state.localOperationActive &&
            state.localOperationStatus in setOf(
                SyncOperationStore.STATUS_SYNCING,
                SyncOperationStore.STATUS_BUILDING,
            ) ->
            activity.getString(R.string.document_status_syncing)
        state.localOperationActive && state.localOperationStatus != null -> state.localOperationStatus
        state.latestFailureLabel != null -> state.latestFailureLabel
        state.status != null -> statusLabel(state.status)
        else -> syncBuildLatestOperationLabel(
            status = state.latestOperationStatus,
            kind = state.latestOperationKind,
            builtLabel = activity.getString(R.string.operations_built),
        ) ?: activity.getString(R.string.pdf_status_not_checked)
    }

    private fun lastSyncedLabel(timestamp: Long): String = if (timestamp <= 0) {
        activity.getString(R.string.pdf_status_last_synced_never)
    } else {
        activity.getString(R.string.pdf_status_last_synced, activity.formatSyncBuildTime(timestamp))
    }

    private fun statusLabel(status: PhoneRenderJobStatus): String = activity.getString(
        when (documentStatusPresentation(status.stage, status.verifiedAtEpochMillis).code) {
            DocumentStatusCode.SYNC_NEEDED -> R.string.document_status_sync_needed
            DocumentStatusCode.SYNCING -> R.string.document_status_syncing
            DocumentStatusCode.SYNCED -> R.string.document_status_synced
            DocumentStatusCode.ERROR -> R.string.document_status_error
        },
    )
}

internal fun Context.formatSyncBuildTime(
    timestamp: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String = when (val presentation = syncBuildTimePresentation(timestamp, nowEpochMillis)) {
    BuiltTimePresentation(BuiltTimeKind.JUST_NOW) ->
        getString(R.string.pdf_status_time_moment_ago)
    else -> when (presentation.kind) {
        BuiltTimeKind.MINUTES -> resources.getQuantityString(
            R.plurals.pdf_status_time_minutes_ago,
            presentation.value.toInt(),
            presentation.value,
        )
        BuiltTimeKind.HOURS -> resources.getQuantityString(
            R.plurals.pdf_status_time_hours_ago,
            presentation.value.toInt(),
            presentation.value,
        )
        BuiltTimeKind.EXACT -> DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
            .format(Date(timestamp))
        BuiltTimeKind.NOT_BUILT -> error("The caller handles missing timestamps")
        BuiltTimeKind.JUST_NOW -> error("Handled above")
        BuiltTimeKind.SECONDS -> error("Sync collapses seconds into a moment ago")
    }
}
