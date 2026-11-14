// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date

internal fun Context.formatFedoraBuiltTime(
    builtAtEpochMillis: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
): String = when (val presentation = builtTimePresentation(builtAtEpochMillis, nowEpochMillis)) {
    BuiltTimePresentation(BuiltTimeKind.NOT_BUILT) -> getString(R.string.home_fedora_not_built)
    BuiltTimePresentation(BuiltTimeKind.JUST_NOW) -> getString(R.string.home_fedora_built_just_now)
    else -> when (presentation.kind) {
        BuiltTimeKind.SECONDS -> resources.getQuantityString(
            R.plurals.home_fedora_built_seconds_ago,
            presentation.value.toInt(),
            presentation.value,
        )
        BuiltTimeKind.MINUTES -> resources.getQuantityString(
            R.plurals.home_fedora_built_minutes_ago,
            presentation.value.toInt(),
            presentation.value,
        )
        BuiltTimeKind.HOURS -> resources.getQuantityString(
            R.plurals.home_fedora_built_hours_ago,
            presentation.value.toInt(),
            presentation.value,
        )
        BuiltTimeKind.EXACT -> getString(
            R.string.home_fedora_built_exact,
            DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)
                .format(Date(builtAtEpochMillis)),
        )
        BuiltTimeKind.NOT_BUILT,
        BuiltTimeKind.JUST_NOW -> error("Handled above")
    }
}

internal fun Context.formatFedoraObservedTime(observedAtEpochMillis: Long): String =
    getString(
        R.string.home_fedora_status_fetched,
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG)
            .format(Date(observedAtEpochMillis)),
    )

class SyncStatusDialog(
    private val activity: AppCompatActivity,
    private val onSyncNow: () -> Unit,
    private val onStatusAction: (DocumentStatusAction, PhoneRenderJobStatus) -> Unit,
    private val onShowLastLog: (() -> Unit)? = null,
) {
    fun show(
        status: PhoneRenderJobStatus?,
        targetLabel: String,
        statusLabel: String? = null,
        observedAtEpochMillis: Long = status?.completedAtEpochMillis ?: 0,
        builtAtEpochMillis: Long = status?.lastSuccessfulBuildAtEpochMillis ?: 0,
    ) {
        val message = if (status == null) {
            activity.getString(R.string.sync_status_no_pending, targetLabel)
        } else {
            buildString {
                append(activity.getString(R.string.sync_status_target, targetLabel))
                append('\n')
                append(activity.getString(R.string.sync_status_state, statusLabel ?: stageLabel(status)))
                append('\n')
                append(activity.formatFedoraBuiltTime(builtAtEpochMillis))
                if (observedAtEpochMillis > 0) {
                    append('\n')
                    append(activity.formatFedoraObservedTime(observedAtEpochMillis))
                }
                status.message.takeIf(String::isNotBlank)?.let {
                    append("\n\n")
                    append(it)
                }
            }
        }
        val action = syncDialogPrimaryAction(status)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sync)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                onShowLastLog?.let { showLastLog ->
                    setNeutralButton(R.string.sync_status_show_last_log) { _, _ ->
                        showLastLog()
                    }
                }
                when (action) {
                    DocumentStatusAction.SYNC_NOW ->
                        setPositiveButton(actionLabel(action)) { _, _ -> onSyncNow() }
                    DocumentStatusAction.SHOW_ERRORS ->
                        setPositiveButton(actionLabel(action)) { _, _ ->
                            onStatusAction(action, requireNotNull(status))
                        }
                    DocumentStatusAction.NONE -> Unit
                }
            }
            .show()
    }

    private fun actionLabel(action: DocumentStatusAction): Int = when (action) {
        DocumentStatusAction.SHOW_ERRORS -> R.string.home_render_show_errors
        DocumentStatusAction.SYNC_NOW -> R.string.document_status_sync
        DocumentStatusAction.NONE -> R.string.ok
    }

    private fun stageLabel(status: PhoneRenderJobStatus): String = activity.getString(
        documentStatusLabelRes(status.stage, status.verifiedAtEpochMillis),
    )
}
