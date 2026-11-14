// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import android.content.DialogInterface
import android.text.format.Formatter
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticArchive
import com.gitlab.mudlej.MjPdfReader.data.sync.LocalBuildLogStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.ui.source.loadSourceFilesNeedingMerge
import com.google.gson.JsonArray
import com.gitlab.mudlej.MjPdfReader.data.sync.savedSyncDiagnosticBundle
import com.gitlab.mudlej.MjPdfReader.data.sync.SavedSyncDetails
import com.gitlab.mudlej.MjPdfReader.data.sync.loadSavedSyncDetails
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncCommandCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnosticBatcher
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncJournalEvent
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncRunPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncBuildProgress
import com.gitlab.mudlej.MjPdfReader.data.sync.isPendingSyncOperationStatus
import com.gitlab.mudlej.MjPdfReader.data.sync.syncOperationEventLines
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun syncOperationRecordsForTarget(
    records: List<SyncOperationRecord>,
    target: String?,
): List<SyncOperationRecord> = if (target == null) {
    records
} else {
    records.filter { it.target == target }
}

internal fun latestRelevantSyncOperationRecord(
    records: List<SyncOperationRecord>,
    target: String?,
): SyncOperationRecord? = records
    .asSequence()
    .filter { record ->
        if (target == null) record.target == null else record.target == null || record.target == target
    }
    .maxByOrNull(SyncOperationRecord::updatedAtEpochMillis)

internal fun syncOperationWasBuild(record: SyncOperationRecord): Boolean =
    record.kind == SyncOperationKind.BUILD

internal fun syncOperationStatusLabel(
    record: SyncOperationRecord,
    @Suppress("UNUSED_PARAMETER")
    builtLabel: String,
): String {
    val interrupted = record.phase in setOf(SyncRunPhase.INTERRUPTED, SyncRunPhase.CANCELLED)
    val failed = record.status in setOf(
        SyncOperationStore.STATUS_ERROR,
        SyncOperationStore.STATUS_ATTENTION,
    )
    if (com.gitlab.mudlej.MjPdfReader.data.sync.isTerminalPdfBuildFailure(record)) return SyncStatusLabels.PDF_BUILD_FAILED
    return if (record.kind == SyncOperationKind.BUILD) {
        when {
            record.status == SyncOperationStore.STATUS_SYNCED &&
                com.gitlab.mudlej.MjPdfReader.data.sync.pdfsStillBuildingAfter(record) > 0 -> "Building on the server"
            record.status == SyncOperationStore.STATUS_SYNCED -> "Build complete"
            failed && interrupted -> "Build interrupted"
            failed -> SyncStatusLabels.PDF_BUILD_FAILED
            record.status == SyncOperationStore.STATUS_WAITING -> "Waiting to build"
            else -> "Building"
        }
    } else {
        when {
            record.status == SyncOperationStore.STATUS_SYNCED -> SyncStatusLabels.UP_TO_DATE
            failed && interrupted -> "Sync interrupted"
            failed -> SyncStatusLabels.SYNC_FAILED
            record.status == SyncOperationStore.STATUS_WAITING -> "Waiting to sync"
            else -> SyncStatusLabels.SYNCING
        }
    }
}

internal fun localBuildLogRecordForStatus(
    records: List<SyncOperationRecord>,
    status: PhoneRenderJobStatus,
    toleranceMillis: Long = LOCAL_BUILD_LOG_MATCH_TOLERANCE_MILLIS,
): SyncOperationRecord? {
    val matchingTarget = records.filter { record ->
        record.kind == SyncOperationKind.BUILD && when {
            status.affectsAll -> record.target == null
            else -> record.target == status.target || record.target == null
        }
    }
    if (matchingTarget.isEmpty()) return null
    val referenceTime = status.activityAtEpochMillis
        .takeIf { it > 0 }
        ?: status.lastSuccessfulBuildAtEpochMillis.takeIf { it > 0 }
        ?: return matchingTarget.maxByOrNull(SyncOperationRecord::updatedAtEpochMillis)
    val nearest = matchingTarget.minWithOrNull(
        compareBy<SyncOperationRecord> { kotlin.math.abs(it.updatedAtEpochMillis - referenceTime) }
            .thenBy { if (it.target == status.target) 0 else 1 }
            .thenByDescending(SyncOperationRecord::updatedAtEpochMillis)
    ) ?: return null
    return nearest.takeIf {
        kotlin.math.abs(it.updatedAtEpochMillis - referenceTime) <= toleranceMillis
    }
}

private const val LOCAL_BUILD_LOG_MATCH_TOLERANCE_MILLIS = 10L * 60L * 1000L

/** Completion belongs to the phone's workflow, not the arrival of server log messages. */
internal fun syncOperationElapsedEnd(record: SyncOperationRecord, nowEpochMillis: Long): Long {
    if (isPendingSyncOperationStatus(record.status)) return nowEpochMillis
    val terminalPhases = setOf(
        SyncRunPhase.SUCCEEDED, SyncRunPhase.FAILED,
        SyncRunPhase.INTERRUPTED, SyncRunPhase.CANCELLED,
    )
    val localCompletion = record.journalEvents.asSequence()
        .filter { it.origin == "phone" && it.state in terminalPhases }
        .map { it.occurredAtEpochMillis }
        .filter { it > 0 }
        .maxOrNull()
    if (localCompletion != null) return localCompletion
    // Older records may only have the timestamped display log. Do not confuse
    // an imported phase assigned to every legacy entry with actual completion.
    return syncOperationEventLines(record).asSequence()
        .filter { it.message == "Completed" || it.message == SyncOperationStore.STATUS_SYNCED }
        .map { it.timestampEpochMillis }
        .filter { it > 0 }
        .maxOrNull()
        ?: record.updatedAtEpochMillis
}

internal fun syncOperationMetricsDetails(
    record: SyncOperationRecord,
    unavailableTimestamp: String,
    nowEpochMillis: Long,
    formatTimestamp: (Long) -> String,
    formatBytes: (Long) -> String,
): String = buildString {
    append("Started: ")
    append(
        record.startedAtEpochMillis.takeIf { it > 0 }
            ?.let(formatTimestamp)
            ?: unavailableTimestamp,
    )
    append("\nElapsed: ")
    val elapsedAt = syncOperationElapsedEnd(record, nowEpochMillis)
    append(
        if (record.startedAtEpochMillis > 0) formatBuildElapsed(elapsedAt - record.startedAtEpochMillis)
        else unavailableTimestamp,
    )
    append("\nUploaded: ")
    append(formatDirectionalTransfer(record, upload = true, formatBytes))
    append("\nDownloaded: ")
    append(formatDirectionalTransfer(record, upload = false, formatBytes))
}

internal fun syncOperationLogDetails(
    record: SyncOperationRecord,
    unavailableTimestamp: String,
    formatTimestamp: (Long) -> String,
): String = syncOperationLogDetails(
    record = record,
    unavailableTimestamp = unavailableTimestamp,
    nowEpochMillis = record.updatedAtEpochMillis,
    formatTimestamp = formatTimestamp,
    formatBytes = { bytes -> "$bytes bytes" },
)

internal fun syncOperationLogDetails(
    record: SyncOperationRecord,
    unavailableTimestamp: String,
    nowEpochMillis: Long,
    formatTimestamp: (Long) -> String,
    formatBytes: (Long) -> String,
): String = buildList {
    val eventTimestamp = { timestamp: Long ->
        when {
            timestamp <= 0 -> unavailableTimestamp
            record.startedAtEpochMillis <= 0 -> formatTimestamp(timestamp)
            else -> formatOperationEventOffset(timestamp - record.startedAtEpochMillis)
        }
    }
    add(
        buildString {
            append(syncOperationMetricsDetails(
                record, unavailableTimestamp, nowEpochMillis, formatTimestamp, formatBytes,
            ))
            append("\nTrace ID: ")
            append(record.id)
            append("\nServer stream: ")
            append(
                when (record.streamState) {
                    SyncOperationStore.STREAM_LIVE -> "Live"
                    SyncOperationStore.STREAM_RECONNECTING -> "Reconnecting"
                    else -> "Saved log"
                },
            )
        },
    )
    syncOperationDisplayMessage(record).takeIf(String::isNotBlank)?.let(::add)
    val lines = if (record.journalEvents.isNotEmpty()) {
        record.journalEvents.filter { it.origin != "diagnostic" }.map { event ->
            syncOperationJournalEventText(
                event = event,
                timestamp = eventTimestamp(event.occurredAtEpochMillis),
            )
        }
    } else syncOperationEventLines(record).map { event ->
        val timestamp = eventTimestamp(event.timestampEpochMillis)
        when {
            (record.kind == SyncOperationKind.BUILD || record.buildCheckedAtEpochMillis > 0) &&
                event.message == "Building" ->
                "$timestamp · Building (last checked)"
            else -> "$timestamp · ${event.message}"
        }
    }
    if (lines.isNotEmpty()) add(lines.joinToString("\n"))
}.joinToString("\n\n")

internal fun syncOperationDiagnosticDetails(record: SyncOperationRecord, summary: String): String =
    summary + "\n\nDeveloper diagnostics v1 (durations can overlap; do not sum nested stages)\n" +
        "Run scheduler: ${record.schedulerKind}; attempt: ${record.attempt}\n" +
        record.journalEvents.filter { it.origin == "diagnostic" }.joinToString("\n") { event ->
            "${event.occurredAtEpochMillis} · ${event.state} · ${event.message} · ${event.metadataJson}"
        }.ifEmpty { "No detailed diagnostics recorded for this run (older app or uninstrumented path)." }

internal fun syncOperationJournalEventText(
    event: SyncJournalEvent,
    timestamp: String,
): String = buildString {
    append(timestamp)
    syncOperationRemoteComponentLabel(event.component)?.let { component ->
        append(" · ")
        append(component)
    }
    append(" · ")
    append(
        event.message.takeIf(String::isNotBlank)
            ?: event.phase.replace('_', ' ').replaceFirstChar { it.uppercase() },
    )
    if (event.total != null && event.total > 0 && event.completed != null) {
        append(" (")
        append(event.completed)
        append(" of ")
        append(event.total)
        append(')')
    }
    event.errorCode.takeIf(String::isNotBlank)?.let {
        append(" [")
        append(it)
        append(']')
    }
}

private fun syncOperationRemoteComponentLabel(component: String): String? = when (component) {
    "phone" -> null
    "api" -> "API"
    else -> component.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun formatDirectionalTransfer(
    record: SyncOperationRecord,
    upload: Boolean,
    formatBytes: (Long) -> String,
): String {
    if (!record.transferMetricsAvailable) return "Not recorded"
    val completed = if (upload) record.uploadBytesCompleted else record.downloadBytesCompleted
    val total = if (upload) record.uploadBytesTotal else record.downloadBytesTotal
    val terminalSuccess = record.status == SyncOperationStore.STATUS_SYNCED
    return when {
        terminalSuccess || total == 0L -> formatBytes(completed)
        else -> "${formatBytes(completed)} of ${formatBytes(total)}"
    }
}

internal fun syncOperationDisplayMessage(record: SyncOperationRecord): String = when {
    record.serverTerminal &&
        record.serverState == "succeeded" &&
        record.phase == SyncRunPhase.INTERRUPTED &&
        record.kind == SyncOperationKind.BUILD ->
        "Build completed on the server; local download is pending."

    record.serverTerminal &&
        record.serverState == "succeeded" &&
        record.phase == SyncRunPhase.INTERRUPTED ->
        "Synchronization with the server completed; local activation is pending."

    else -> record.displayMessage
}

internal fun syncOperationBuildDetails(
    record: SyncOperationRecord,
    formatEventTimestamp: (Long) -> String,
): String {
    if (record.buildProgress.isEmpty() && record.buildCheckedAtEpochMillis <= 0) return ""
    val builds = record.buildProgress
    val documents = builds.flatMap(SyncBuildProgress::documents).distinct().sorted()
    val requestedTargets = builds.map(SyncBuildProgress::target)
        .filter(String::isNotBlank)
        .distinct()
    val what = when {
        documents.size == 1 -> documents.single()
        documents.isNotEmpty() -> "${documents.size} PDFs"
        requestedTargets.size == 1 && requestedTargets.single() != "all" -> requestedTargets.single()
        record.target != null -> record.target
        builds.size > 1 -> "${builds.size} build jobs"
        else -> "All requested PDFs"
    }
    return buildString {
        append(
            when (record.status) {
                SyncOperationStore.STATUS_SYNCED -> "Built: "
                SyncOperationStore.STATUS_ERROR -> "Build target: "
                else -> "Building: "
            },
        )
        append(what)
        if (documents.size > 1) {
            append("\nPDFs: ")
            append(documents.joinToString(", "))
        }
        builds.forEachIndexed { index, build ->
            if (builds.size > 1) {
                append("\n\n")
                append(build.target.ifBlank { "Build ${index + 1}" })
            }
            append("\nServer: ")
            append(build.serverStatus.userFacingBuildState())
            build.detail.takeIf(String::isNotBlank)?.let { detail ->
                append("\nCurrent step: ")
                append(detail)
            } ?: build.phase.takeIf(String::isNotBlank)?.let { phase ->
                append("\nCurrent phase: ")
                append(phase.replace('_', ' '))
            }
            if (build.total > 0 && build.completed in 0..build.total) {
                append("\nProgress: ")
                append(build.completed)
                append(" of ")
                append(build.total)
                build.unit.takeIf(String::isNotBlank)?.let { unit ->
                    append(' ')
                    append(unit)
                }
            }
            build.buildId.takeIf(String::isNotBlank)?.let { buildId ->
                append("\nBuild ID: ")
                append(buildId)
            }
        }
        if (record.buildCheckedAtEpochMillis > 0) {
            append("\nLast checked: ")
            append(formatEventTimestamp(record.buildCheckedAtEpochMillis))
            val elapsedMillis = record.buildCheckedAtEpochMillis - record.startedAtEpochMillis
            if (elapsedMillis >= 1_000) {
                append("\nElapsed: ")
                append(formatBuildElapsed(elapsedMillis))
            }
        }
    }
}

private fun String.userFacingBuildState(): String = when (this) {
    "queued" -> "Waiting in the build queue"
    "running" -> "Running"
    "succeeded" -> "Completed"
    "attention" -> "Completed with attention needed"
    "failed" -> "Failed"
    "cancelled" -> "Cancelled"
    else -> replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun formatBuildElapsed(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1_000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes <= 0 -> "$seconds sec"
        seconds == 0L -> "$minutes min"
        else -> "$minutes min $seconds sec"
    }
}

private fun formatOperationEventOffset(elapsedMillis: Long): String =
    "+${(elapsedMillis / 1_000).coerceAtLeast(0)} sec"

internal class SyncOperationsDialog(
    private val activity: AppCompatActivity,
    private val loadIssueCounts: suspend () -> SyncIssueCounts = { loadSyncIssueCounts(activity) },
    private val requestSync: (SyncOperationRecord) -> Unit = { record ->
        val result = SyncCommandCoordinator(activity.applicationContext).start(record.target)
        check((result.accepted && result.state != "failed") || result.errorCode == "busy")
    },
) {
    fun showLatest(target: String? = null) {
        loadRecords { records ->
            val record = latestRelevantSyncOperationRecord(records, target)
            if (record == null) showRecords(records, target) else showDetails(record)
        }
    }

    fun show(target: String? = null) {
        loadRecords { records -> showRecords(records, target) }
    }

    private fun loadRecords(onLoaded: (List<SyncOperationRecord>) -> Unit) {
        activity.lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                SyncOperationStore(activity.applicationContext).summaries()
            }
            if (!activity.isFinishing && !activity.isDestroyed) {
                onLoaded(records)
            }
        }
    }

    private fun showRecords(allRecords: List<SyncOperationRecord>, target: String?) {
        val records = syncOperationRecordsForTarget(
            allRecords,
            target,
        )
        val title = target?.let {
            activity.getString(R.string.operations_target_title, it)
        } ?: activity.getString(R.string.home_operations)
        if (records.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(
                    target?.let {
                        activity.getString(R.string.operations_empty_target, it)
                    } ?: activity.getString(R.string.operations_empty),
                )
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val labels = records.map { record ->
            val status = syncOperationStatusLabel(
                record,
                activity.getString(R.string.operations_built),
            )
            val recordTarget = record.target ?: activity.getString(R.string.home_render_status_all)
            val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(record.updatedAtEpochMillis))
            "$status · $recordTarget\n$time"
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setItems(labels) { _, index -> showDetails(records[index]) }
            .show()
    }

    fun showRecord(record: SyncOperationRecord) {
        showDetails(record)
    }

    private fun showDetails(record: SyncOperationRecord) {
        activity.lifecycleScope.launch {
            val (saved, projects) = withContext(Dispatchers.IO) {
                loadSavedSyncDetails(activity.applicationContext, record) to
                    LibraryProjectStore(activity.applicationContext).projects()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            showSavedDetails(record, saved, projects)
        }
    }

    private fun showSavedDetails(
        record: SyncOperationRecord,
        initialSaved: SavedSyncDetails,
        projects: JsonArray,
    ) {
        var currentRecord = record
        var currentSummary = syncStatusSummary(record, initialSaved, projects)
        val timestampFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        fun message(): String = listOf(syncOperationMetricsDetails(
            record = currentRecord,
            unavailableTimestamp = activity.getString(R.string.operations_timestamp_unavailable),
            nowEpochMillis = System.currentTimeMillis(),
            formatTimestamp = { timestampFormat.format(Date(it)) },
            formatBytes = { Formatter.formatFileSize(activity, it) },
        ), currentSummary.message).filter { it.isNotBlank() }.joinToString("\n\n")
        val issueLinks = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(issueLinks)
            .setTitle(currentSummary.title)
            .setMessage(message())
            .setPositiveButton(R.string.sync, null)
            .setNegativeButton(R.string.operations_copy_diagnostics, null)
            .create()
        // A long list of failed documents scrolls; the issue links and buttons stay on screen.
        dialog.scrollMessageAboveCustomView()
        val jobs = mutableListOf<Job>()
        dialog.setOnShowListener {
            jobs += activity.lifecycleScope.launch {
                val counts = withContext(Dispatchers.IO) { loadIssueCounts() }
                if (!dialog.isShowing) return@launch
                fun addIssueLink(count: Int, singular: String, plural: String, open: () -> Unit) {
                    if (count == 0) return
                    issueLinks.addView(android.widget.Button(activity).apply {
                        text = if (count == 1) singular else "$count $plural"
                        setOnClickListener { dialog.dismiss(); open() }
                    })
                }
                addIssueLink(counts.comments, "1 comment has an issue", "comments have issues") {
                    activity.startActivity(android.content.Intent(activity, com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("showCommentIssues", true))
                }
                addIssueLink(counts.reviewCards, "1 review card has an issue", "review cards have issues") {
                    activity.startActivity(com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardBrowserActivity.intent(activity,
                        initialFilter = com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardBrowserFilter.UNAVAILABLE))
                }
                addIssueLink(counts.sourceMerges, SyncStatusLabels.sourceFilesNeedMerge(1), "source files need a merge") {
                    activity.startActivity(android.content.Intent(activity,
                        com.gitlab.mudlej.MjPdfReader.ui.source.SourceConflictListActivity::class.java))
                }
                // Shared folder only: changes another app made wait here for the user's decision.
                addIssueLink(counts.outsideChanges, activity.getString(R.string.sync_outside_changes_one),
                    activity.getString(R.string.sync_outside_changes_many)) {
                    OutsideSourceChangesDialog(activity).show()
                }
                // Changes the server refused are kept aside: here the user retries or discards them.
                addIssueLink(counts.refusedChanges, activity.getString(R.string.sync_refused_changes_one),
                    activity.getString(R.string.sync_refused_changes_many)) {
                    RefusedChangesDialog(activity).show()
                }
            }

            val syncButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            var submitting = false
            var hasPendingSync = true
            fun updateSyncButton() {
                syncButton.visibility = if (hasPendingSync || submitting) View.GONE else View.VISIBLE
            }
            updateSyncButton()
            syncButton.setOnClickListener {
                if (submitting || hasPendingSync) return@setOnClickListener
                submitting = true
                updateSyncButton()
                jobs += activity.lifecycleScope.launch {
                    try {
                        val scheduled = withContext(Dispatchers.IO) {
                            // Recheck at the action boundary, including work started from another screen.
                            if (SyncOperationStore(activity.applicationContext).pendingOperations().isNotEmpty()) false
                            else { requestSync(currentRecord); true }
                        }
                        if (scheduled) dialog.dismiss()
                    } catch (error: Exception) {
                        Toast.makeText(activity, R.string.sync_summary_start_failed, Toast.LENGTH_LONG).show()
                    } finally {
                        submitting = false
                        updateSyncButton()
                    }
                }
            }
            jobs += activity.lifecycleScope.launch {
                SyncOperationStore(activity.applicationContext).observeHasPendingOperations().collectLatest {
                    hasPendingSync = it
                    updateSyncButton()
                }
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { button ->
                button.isEnabled = false
                jobs += activity.lifecycleScope.launch {
                    try {
                        val diagnostics = withContext(Dispatchers.IO) {
                            SyncDiagnosticBatcher.flushAll()
                            val store = SyncOperationStore(activity.applicationContext)
                            val seen = mutableSetOf<String>()
                            val bundles = mutableListOf<String>()
                            var selected: SyncOperationRecord? = store.operationWithEvents(record.id) ?: currentRecord
                            while (selected != null && seen.add(selected.id)) {
                                val run = selected
                                val saved = loadSavedSyncDetails(activity.applicationContext, run, includeRawArchive = true)
                                bundles += savedSyncDiagnosticBundle(
                                    run, saved, LocalBuildLogStore()::read)
                                selected = run.retryOf?.let(store::operationWithEvents)
                                if (run.retryOf != null && selected == null) {
                                    bundles += "INCOMPLETE: original retry diagnostics are unavailable: ${run.retryOf}"
                                }
                            }
                            bundles.joinToString("\n\n--- Previous attempt ---\n\n").also {
                                SyncDiagnosticArchive(activity.applicationContext)
                                    .saveBundle(record.id, it)
                            }
                        }
                        val copy = clipboardDiagnostics(diagnostics)
                        val copied = runCatching {
                            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
                            val clip = android.content.ClipData.newPlainText("Thoughts diagnostics", copy.text)
                            clip.description.extras = android.os.PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                            clipboard.setPrimaryClip(clip)
                        }.onFailure {
                            Log.w(DIAGNOSTICS_LOG_TAG, "Copying ${copy.text.length} diagnostic characters failed", it)
                        }.isSuccess
                        when {
                            copied && !copy.trimmed -> Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
                            else -> {
                                Toast.makeText(
                                    activity,
                                    if (copied) R.string.sync_diagnostics_copied_trimmed else R.string.sync_diagnostics_copy_failed_sharing,
                                    Toast.LENGTH_LONG,
                                ).show()
                                shareDiagnosticsFile(activity, record.id, diagnostics)
                            }
                        }
                    } catch (error: Exception) {
                        Log.w(DIAGNOSTICS_LOG_TAG, "Preparing sync diagnostics failed", error)
                        Toast.makeText(activity, R.string.sync_diagnostics_copy_failed, Toast.LENGTH_LONG).show()
                    } finally {
                        button.isEnabled = true
                    }
                }
            }
            jobs += activity.lifecycleScope.launch {
                SyncOperationStore(activity.applicationContext).observeRecord(record.id).collectLatest { updated ->
                    if (updated == null) return@collectLatest
                    currentRecord = updated
                    val (saved, currentProjects) = withContext(Dispatchers.IO) {
                        loadSavedSyncDetails(activity.applicationContext, updated) to
                            LibraryProjectStore(activity.applicationContext).projects()
                    }
                    currentSummary = syncStatusSummary(updated, saved, currentProjects)
                    dialog.setTitle(currentSummary.title)
                    dialog.setMessage(message())
                }
            }
        }
        jobs += activity.lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                if (isPendingSyncOperationStatus(currentRecord.status)) {
                    dialog.setMessage(message())
                }
            }
        }
        dialog.setOnDismissListener { jobs.forEach(Job::cancel) }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

}

internal data class SyncIssueCounts(
    val comments: Int,
    val reviewCards: Int,
    val sourceMerges: Int,
    /** Source files of the shared folder changed by another app, held until the user uploads or discards the change. */
    val outsideChanges: Int = 0,
    /** Project changes and Library removals the server refused, kept until the user retries or discards them. */
    val refusedChanges: Int = 0,
)

private suspend fun loadSyncIssueCounts(activity: AppCompatActivity): SyncIssueCounts {
    val comments = com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity).catalogue().overviewDocuments
        .flatMap { it.comments }.filter { !it.isNote && it.anchorStatus in com.gitlab.mudlej.MjPdfReader.ui.home.OVERVIEW_ISSUE_STATUSES }
        .distinctBy { it.id }.size +
        // "Comment missing" markers count as comment issues too.
        com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.missingCommentMarkers().size
    val sourceIndex = com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex().snapshot()
    val canonicalHashes = sourceIndex.libraryPdfSha256ByFilename.takeIf { sourceIndex.libraryAvailable && sourceIndex.generationCurrent }
    val cards = com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore(activity)
        .browserCards(canonicalPdfSha256ByFilename = canonicalHashes)
        .count { it.needsAction }
    val mergeFiles = activity.loadSourceFilesNeedingMerge().size
    val outsideChanges = runCatching {
        com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue(activity.applicationContext, scheduleRequests = false)
            .heldOutsideChanges().size
    }.getOrDefault(0)
    val refusedChanges = runCatching {
        com.gitlab.mudlej.MjPdfReader.data.sync.refusedLocalChanges(activity.applicationContext).size
    }.getOrDefault(0)
    return SyncIssueCounts(comments, cards, mergeFiles, outsideChanges, refusedChanges)
}

private const val DIAGNOSTICS_LOG_TAG = "ThoughtsDiagnostics"

/** Offers the full report as a text file, for reports the clipboard cannot hold. */
private suspend fun shareDiagnosticsFile(activity: AppCompatActivity, operationId: String, report: String) {
    runCatching {
        val file = withContext(Dispatchers.IO) {
            val directory = java.io.File(activity.cacheDir, "share").apply { mkdirs() }
            directory.listFiles { it -> it.name.startsWith("thoughts-diagnostics-") }.orEmpty().forEach { it.delete() }
            java.io.File(directory, "thoughts-diagnostics-${operationId.filter { it.isLetterOrDigit() || it == '-' }.take(64)}.txt")
                .apply { writeText(report) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(android.content.Intent.createChooser(send, activity.getString(R.string.sync_diagnostics_share_title)))
    }.onFailure { Log.w(DIAGNOSTICS_LOG_TAG, "Sharing sync diagnostics failed", it) }
}
