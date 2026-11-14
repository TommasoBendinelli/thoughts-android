// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.RefusedLocalChange
import com.gitlab.mudlej.MjPdfReader.data.sync.refusedLocalChanges
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The local changes the server refused. Sync keeps such a change aside instead of sending it
 * again and failing on it every time: here the user sees the server's reason and retries or
 * discards the change, one by one.
 */
internal class RefusedChangesDialog(
    private val activity: AppCompatActivity,
    private val load: () -> List<RefusedLocalChange> = { refusedLocalChanges(activity.applicationContext) },
    private val startSync: (AppCompatActivity) -> Unit = {
        com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler.enqueue(it.applicationContext)
    },
) {
    fun show() {
        activity.lifecycleScope.launch {
            val refused = withContext(Dispatchers.IO) { runCatching(load).getOrDefault(emptyList()) }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when (refused.size) {
                0 -> Toast.makeText(activity, R.string.sync_refused_changes_none, Toast.LENGTH_SHORT).show()
                1 -> showChange(refused.single())
                else -> MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.sync_refused_changes_title)
                    .setItems(refused.map(::title).toTypedArray()) { _, index -> showChange(refused[index]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun title(change: RefusedLocalChange): String =
        change.projectChange?.description ?: change.removal?.filename.orEmpty()

    private fun message(change: RefusedLocalChange): String {
        val project = change.projectChange
        return if (project != null) {
            activity.getString(R.string.sync_refused_project_change_message, project.description, project.detail)
        } else {
            val removal = requireNotNull(change.removal)
            activity.getString(R.string.sync_refused_removal_message, removal.filename, removal.failureDetail)
        }
    }

    private fun showChange(change: RefusedLocalChange) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sync_refused_changes_title)
            .setMessage(message(change))
            .setPositiveButton(R.string.sync_refused_change_retry) { _, _ -> decide(change, retry = true) }
            .setNegativeButton(R.string.sync_refused_change_discard) { _, _ -> decide(change, retry = false) }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun decide(change: RefusedLocalChange, retry: Boolean) {
        activity.lifecycleScope.launch {
            val (done, left) = withContext(Dispatchers.IO) {
                val done = runCatching { if (retry) change.retry() else change.discard() }.getOrDefault(false)
                done to runCatching(load).getOrDefault(emptyList()).size
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            Toast.makeText(
                activity,
                when {
                    !done -> R.string.sync_refused_change_gone
                    retry -> R.string.sync_refused_change_retried
                    else -> R.string.sync_refused_change_discarded
                },
                Toast.LENGTH_LONG,
            ).show()
            // The Library still shows the change until a Sync has run: a retried change is sent by
            // it, and after a discard it brings back what the server has. One Sync once the last
            // refused change is decided, started like any Sync the user asks for.
            if (done) decided = true
            if (left > 0) {
                show()
            } else if (decided) {
                decided = false
                runCatching { startSync(activity) }
            }
        }
    }

    private var decided = false
}
