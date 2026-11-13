// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.HeldOutsideChange
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The source files of the shared folder that another app changed. Sync does not queue such a
 * change by itself: here the user uploads it or discards it, file by file.
 */
internal class OutsideSourceChangesDialog(
    private val activity: AppCompatActivity,
    private val queue: () -> SourceEditQueue = { SourceEditQueue(activity.applicationContext, scheduleRequests = false) },
) {
    fun show() {
        activity.lifecycleScope.launch {
            val held = withContext(Dispatchers.IO) { queue().heldOutsideChanges() }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when (held.size) {
                0 -> Toast.makeText(activity, R.string.sync_outside_changes_none, Toast.LENGTH_SHORT).show()
                1 -> showChange(held.single())
                else -> MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.sync_outside_changes_title)
                    .setItems(held.map { change ->
                        activity.getString(R.string.sync_outside_change_item, change.sourceFile, change.addedLines, change.removedLines)
                    }.toTypedArray()) { _, index -> showChange(held[index]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showChange(change: HeldOutsideChange) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(File(change.sourceFile).name)
            .setMessage(activity.getString(
                R.string.sync_outside_change_message, change.sourceFile, change.addedLines, change.removedLines,
            ))
            .setPositiveButton(R.string.sync_outside_change_upload) { _, _ -> decide(change, upload = true) }
            .setNegativeButton(R.string.sync_outside_change_discard) { _, _ -> decide(change, upload = false) }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun decide(change: HeldOutsideChange, upload: Boolean) {
        activity.lifecycleScope.launch {
            val (done, left) = withContext(Dispatchers.IO) {
                val edits = queue()
                val done = runCatching {
                    if (upload) edits.uploadOutsideChange(change) else edits.discardOutsideChange(change)
                }.getOrDefault(false)
                done to edits.heldOutsideChanges().size
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            Toast.makeText(
                activity,
                when {
                    !done -> R.string.sync_outside_change_changed
                    upload -> R.string.sync_outside_change_uploaded
                    else -> R.string.sync_outside_change_discarded
                },
                Toast.LENGTH_LONG,
            ).show()
            if (left > 0) show()
        }
    }
}
