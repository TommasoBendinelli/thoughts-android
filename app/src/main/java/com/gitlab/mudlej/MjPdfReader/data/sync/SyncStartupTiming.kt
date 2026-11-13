// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

internal const val SYNC_ENQUEUED_NANOS = "sync_enqueued_elapsed_nanos"
internal const val SYNC_ENQUEUED_BOOT = "sync_enqueued_boot_count"

internal fun syncBootCount(context: Context): Int = runCatching {
    Settings.Global.getInt(context.contentResolver, "boot_count", -1)
}.getOrDefault(-1)

/** Wall-clock changes and a reboot must not become fabricated dispatch durations. */
internal fun syncStartupElapsedNanos(enqueued: Long, now: Long, boot: Int, currentBoot: Int): Long? =
    if (enqueued > 0 && now >= enqueued && boot >= 0 && boot == currentBoot) now - enqueued else null

internal fun recordSyncWorkerEntry(
    context: Context, operationId: String?, executor: String, enqueued: Long, boot: Int,
) {
    val now = SystemClock.elapsedRealtimeNanos()
    val diagnostics = SyncDiagnostics(context, operationId)
    diagnostics.decision("sync_worker_entry", executor)
    val elapsed = syncStartupElapsedNanos(enqueued, now, boot, syncBootCount(context))
    if (elapsed != null) {
        // On a resumed job this is time since original submission, including any retry wait.
        diagnostics.count("sync_submission_to_worker", "duration_ns" to elapsed)
    } else {
        diagnostics.decision("sync_submission_to_worker", "clock_identity_unavailable")
    }
}
