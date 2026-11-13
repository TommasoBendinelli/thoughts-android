// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMergeVersions
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import com.gitlab.mudlej.MjPdfReader.data.source.sourceMergeResolvable
import com.gitlab.mudlej.MjPdfReader.databinding.ActivitySourceConflictListBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemSourceConflictBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceConflictListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MERGE_FILES_ONLY = "merge_files_only"
    }

    private lateinit var binding: ActivitySourceConflictListBinding
    private lateinit var queue: SourceEditQueue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceConflictListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        title = getString(if (intent.getBooleanExtra(EXTRA_MERGE_FILES_ONLY, false)) {
            R.string.source_merge_list_title
        } else {
            R.string.source_conflict_list_title
        })
        queue = SourceEditQueue(applicationContext, scheduleRequests = false)
    }

    override fun onResume() {
        super.onResume()
        loadConflicts()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadConflicts() {
        binding.sourceConflictListProgress.visibility = View.VISIBLE
        binding.sourceConflictListMessage.apply {
            visibility = View.VISIBLE
            setText(R.string.source_conflict_list_loading)
        }
        lifecycleScope.launch {
            val mergeFilesOnly = intent.getBooleanExtra(EXTRA_MERGE_FILES_ONLY, false)
            val (conflicts, mergeFiles) = withContext(Dispatchers.IO) {
                val mutations = queue.pendingSourceMutations()
                val conflicts = if (mergeFilesOnly) emptyList() else visibleSourceConflicts(mutations)
                val store = OfflineSourceBundleStore(applicationContext)
                conflicts to store.sourceFilesNeedingMerge()
                    .filterNot { path -> conflicts.any { it.sourceFile == path } }
                    .map { path ->
                        SourceMergeFile(path, store.sourceMergeVersions(path), mutations.filter { it.sourceFile == path })
                    }
            }
            if (isFinishing || isDestroyed) return@launch
            binding.sourceConflictListProgress.visibility = View.GONE
            binding.sourceConflictListItems.removeAllViews()
            binding.sourceConflictListMessage.visibility = if (conflicts.isEmpty() && mergeFiles.isEmpty()) {
                binding.sourceConflictListMessage.setText(if (mergeFilesOnly) {
                    R.string.source_merge_list_empty
                } else {
                    R.string.source_conflict_list_empty
                })
                View.VISIBLE
            } else {
                View.GONE
            }
            conflicts.forEach(::addConflict)
            mergeFiles.forEach(::addMergeFile)
        }
    }

    private fun addMergeFile(file: SourceMergeFile) {
        val item = ItemSourceConflictBinding.inflate(LayoutInflater.from(this), binding.sourceConflictListItems, false)
        item.sourceConflictItemTitle.text = file.path
        val state = sourceMergeFileState(file.versions, file.mutations)
        item.sourceConflictItemDetails.text = buildList {
            add(getString(R.string.source_merge_file_reason))
            when (state) {
                SourceMergeFileState.RESOLVABLE -> file.versions?.let { versions ->
                    val lines = differingLineCount(versions.phoneContents, versions.syncedContents)
                    add(resources.getQuantityString(R.plurals.source_merge_differing_lines, lines, lines))
                }
                SourceMergeFileState.SYNC_SENDING -> add(getString(R.string.source_merge_file_sync_sending))
                SourceMergeFileState.CONFLICT -> add(getString(R.string.source_merge_file_conflict))
                SourceMergeFileState.UNREADABLE -> add(getString(R.string.source_merge_file_unreadable))
            }
        }.joinToString("\n")
        val conflictId = file.mutations.firstNotNullOfOrNull { it.conflictId?.takeIf(String::isNotBlank) }
        when (state) {
            SourceMergeFileState.RESOLVABLE -> {
                item.sourceConflictItemResolve.setText(R.string.source_merge_resolve)
                item.sourceConflictItemResolve.setOnClickListener { file.versions?.let(::chooseMergeVersion) }
            }
            SourceMergeFileState.CONFLICT -> {
                item.sourceConflictItemResolve.isEnabled = conflictId != null
                item.sourceConflictItemResolve.setOnClickListener {
                    conflictId?.let { startActivity(SourceEditorActivity.conflictIntent(this, it)) }
                }
            }
            SourceMergeFileState.SYNC_SENDING, SourceMergeFileState.UNREADABLE ->
                item.sourceConflictItemResolve.visibility = View.GONE
        }
        binding.sourceConflictListItems.addView(item.root)
    }

    private fun chooseMergeVersion(versions: SourceMergeVersions) {
        val choices = SourceMergeChoice.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.source_merge_choose_title, versions.sourceFile))
            .setItems(choices.map { getString(it.label) }.toTypedArray()) { _, which ->
                confirmMergeChoice(versions, choices[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmMergeChoice(versions: SourceMergeVersions, choice: SourceMergeChoice) {
        MaterialAlertDialogBuilder(this)
            .setTitle(choice.confirmationTitle)
            .setMessage(getString(choice.confirmationMessage, versions.sourceFile))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(choice.label) { _, _ -> applyMergeChoice(versions, choice) }
            .show()
    }

    private fun applyMergeChoice(versions: SourceMergeVersions, choice: SourceMergeChoice) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (choice) {
                        SourceMergeChoice.KEEP_PHONE -> {
                            queue.keepPhoneVersionForMerge(versions.sourceFile, versions.phoneSha256)
                            true
                        }
                        SourceMergeChoice.USE_SYNCED ->
                            queue.useSyncedVersionForMerge(versions.sourceFile, versions.phoneSha256)
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            val message = result.fold(
                onSuccess = { applied -> getString(if (applied) choice.done else R.string.source_merge_changed) },
                onFailure = { getString(R.string.source_merge_failed, it.message.orEmpty()) },
            )
            Toast.makeText(this@SourceConflictListActivity, message, Toast.LENGTH_LONG).show()
            loadConflicts()
        }
    }

    private fun addConflict(conflict: PendingSourceMutation) {
        val item = ItemSourceConflictBinding.inflate(
            LayoutInflater.from(this),
            binding.sourceConflictListItems,
            false,
        )
        item.sourceConflictItemTitle.text = getString(
            R.string.source_conflict_in_path,
            conflict.sourceFile,
        )
        item.sourceConflictItemDetails.text = conflictDetails(conflict)
        val conflictId = conflict.conflictId
        item.sourceConflictItemResolve.isEnabled = !conflictId.isNullOrBlank()
        item.sourceConflictItemResolve.setOnClickListener {
            if (!conflictId.isNullOrBlank()) {
                startActivity(SourceEditorActivity.conflictIntent(this, conflictId))
            }
        }
        binding.sourceConflictListItems.addView(item.root)
    }

    private fun conflictDetails(conflict: PendingSourceMutation): String = buildList {
        conflict.conflictCount?.takeIf { it > 0 }?.let { count ->
            add(resources.getQuantityString(R.plurals.source_conflict_count, count, count))
        }
        add(conflict.rawError.ifBlank { conflict.error })
    }.filter(String::isNotBlank).joinToString("\n")
}

private data class SourceMergeFile(
    val path: String,
    val versions: SourceMergeVersions?,
    val mutations: List<PendingSourceMutation>,
)

/** The two ways to resolve a file needing a merge on the phone. */
internal enum class SourceMergeChoice(
    @StringRes val label: Int,
    @StringRes val confirmationTitle: Int,
    @StringRes val confirmationMessage: Int,
    @StringRes val done: Int,
) {
    KEEP_PHONE(
        R.string.source_merge_keep_phone,
        R.string.source_merge_keep_phone_title,
        R.string.source_merge_keep_phone_message,
        R.string.source_merge_kept_phone,
    ),
    USE_SYNCED(
        R.string.source_merge_use_synced,
        R.string.source_merge_use_synced_title,
        R.string.source_merge_use_synced_message,
        R.string.source_merge_used_synced,
    ),
}

internal enum class SourceMergeFileState { RESOLVABLE, SYNC_SENDING, CONFLICT, UNREADABLE }

/** What the merge list offers for one file, given its two versions and its queued phone edits. */
internal fun sourceMergeFileState(
    versions: SourceMergeVersions?,
    mutations: List<PendingSourceMutation>,
): SourceMergeFileState = when {
    mutations.any { it.state == SourceMutationState.CONFLICT } -> SourceMergeFileState.CONFLICT
    !sourceMergeResolvable(mutations) -> SourceMergeFileState.SYNC_SENDING
    versions == null -> SourceMergeFileState.UNREADABLE
    else -> SourceMergeFileState.RESOLVABLE
}

/** Lines between the versions' common first and last lines: at most this many differ. */
internal fun differingLineCount(first: String, second: String): Int {
    val a = first.split('\n')
    val b = second.split('\n')
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    var end = 0
    while (end < a.size - start && end < b.size - start && a[a.size - 1 - end] == b[b.size - 1 - end]) end++
    return maxOf(a.size - start - end, b.size - start - end)
}

/**
 * Every source file that needs a merge: TeX conflicts the server preserved for a queued edit, and
 * files whose phone copy and verified source both changed. Each path is counted once.
 */
internal fun sourceFilesNeedingMerge(
    mutations: List<PendingSourceMutation>,
    mergeFiles: List<String>,
): List<String> = (visibleSourceConflicts(mutations).map(PendingSourceMutation::sourceFile) + mergeFiles)
    .distinct()
    .sorted()

/** Reads both conflict stores; call off the main thread. */
internal fun android.content.Context.loadSourceFilesNeedingMerge(): List<String> = sourceFilesNeedingMerge(
    SourceEditQueue(applicationContext, scheduleRequests = false).pendingSourceMutations(),
    OfflineSourceBundleStore(applicationContext).sourceFilesNeedingMerge(),
)

internal fun visibleSourceConflicts(
    mutations: List<PendingSourceMutation>,
): List<PendingSourceMutation> = mutations
    .filter { mutation -> mutation.state == SourceMutationState.CONFLICT }
    .sortedWith(compareBy(PendingSourceMutation::sourceFile, PendingSourceMutation::requestId))
