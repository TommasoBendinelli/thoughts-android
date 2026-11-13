// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import androidx.appcompat.app.AppCompatActivity
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferPlan
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferStateStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun showSyncTransferPrompt(activity: AppCompatActivity, plan: SyncTransferPlan) {
    if (activity.isFinishing || plan.canRunOnMetered) return
    val current = SyncTransferStateStore(activity).load()
        ?.takeIf { it.planId == plan.planId && !it.canRunOnMetered }
        ?: return
    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.sync_transfer_prompt_title)
        .setMessage(SyncTransferCoordinator.promptMessage(activity, current))
        .setNegativeButton(R.string.sync_transfer_wait_wifi) { _, _ ->
            SyncTransferCoordinator.waitForWifi(activity, current.planId)
        }
        .setPositiveButton(R.string.sync_transfer_continue_mobile) { _, _ ->
            SyncTransferCoordinator.approve(activity, current.planId)
        }
        .setOnCancelListener {
            SyncTransferCoordinator.waitForWifi(activity, current.planId)
        }
        .show()
}
