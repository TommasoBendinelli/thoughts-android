// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Kept out of backups: another installation/device needs its own Android decision. */
internal class SyncBatteryPromptState(private val marker: File) {
    /** Only once a Thoughts server is set up: before that there is nothing to Sync. */
    fun shouldOffer(exempt: Boolean, serverConfigured: Boolean = true): Boolean =
        serverConfigured && !exempt && !marker.exists()
    fun markShown() {
        marker.parentFile?.mkdirs()
        marker.writeText("shown\n")
    }
}

object SyncBatterySetup {
    fun isExempt(context: Context): Boolean = context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

    private fun state(context: Context) = SyncBatteryPromptState(
        File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1"),
    )

    private fun serverConfigured(context: Context): Boolean =
        ThoughtsServerSecretStore(context).baseUrl() != null

    fun statusText(context: Context): String = context.getString(
        if (isExempt(context)) R.string.sync_battery_allowed else R.string.sync_battery_restricted,
    )

    fun offerOnFirstUse(activity: AppCompatActivity) {
        // Let navigation/storage permission UI finish first. An obscured window does not
        // consume the one-time offer; a subsequent resume can present it.
        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !activity.hasWindowFocus() ||
                !state(activity).shouldOffer(isExempt(activity), serverConfigured(activity))) return@post
            showExplanation(activity)
        }
    }

    fun showExplanation(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (isExempt(activity)) {
            openAndroidSettings(activity)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sync_battery_title)
            .setMessage(R.string.sync_battery_explanation)
            .setNegativeButton(R.string.sync_battery_not_now, null)
            .setPositiveButton(R.string.sync_battery_allow) { _, _ -> requestExemption(activity) }
            .show()
        state(activity).markShown()
    }

    private fun requestExemption(activity: AppCompatActivity) {
        if (isExempt(activity)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${activity.packageName}"))
        if (runCatching { activity.startActivity(intent) }.isFailure) openAndroidSettings(activity)
    }

    private fun openAndroidSettings(activity: AppCompatActivity) {
        val opened = runCatching {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.isSuccess
        if (!opened) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activity.packageName}")))
            }.onFailure {
                Toast.makeText(activity, R.string.sync_battery_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}
