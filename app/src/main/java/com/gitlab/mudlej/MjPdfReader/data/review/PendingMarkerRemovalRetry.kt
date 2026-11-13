// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncRunPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.isPendingSyncOperationStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finishes review-card marker removals a deletion left pending. Runs off the main thread after a
 * source scan (the markers' file may now be safe to edit) and after a Review refresh, one run at a
 * time, and never while a Sync is running, which may be replacing those sources.
 */
internal object PendingMarkerRemovalRetry {
    /** Review refreshes are frequent; checking each pending card once a minute is enough. */
    const val REFRESH_INTERVAL_MILLIS = 60_000L

    private val running = AtomicBoolean(false)
    @Volatile private var lastRunAtMillis: Long? = null

    fun run(context: Context, minIntervalMillis: Long = 0L) {
        if (!running.compareAndSet(false, true)) return
        try {
            val now = SystemClock.elapsedRealtime()
            if (!due(lastRunAtMillis, now, minIntervalMillis)) return
            if (syncActive(SyncOperationStore(context.applicationContext).pendingOperations())) return
            lastRunAtMillis = now
            val removed = ReviewStore(context.applicationContext).retryPendingMarkerRemovals()
            if (removed > 0) Log.i(TAG, "Removed the markers of $removed deleted review card(s)")
        } catch (failure: Exception) {
            Log.w(TAG, "Pending review marker removals will retry later", failure)
        } finally {
            running.set(false)
        }
    }

    internal fun due(lastRunAtMillis: Long?, nowMillis: Long, minIntervalMillis: Long): Boolean =
        lastRunAtMillis == null || nowMillis - lastRunAtMillis >= minIntervalMillis

    /**
     * A pending Sync is running, or about to resume, unless it waits for a network or the user;
     * the same rule decides when Sync keeps its foreground protection.
     */
    internal fun syncActive(operations: List<SyncOperationRecord>): Boolean = operations.any {
        isPendingSyncOperationStatus(it.status) && it.phase !in setOf(SyncRunPhase.WAITING_NETWORK, SyncRunPhase.WAITING_USER)
    }

    private const val TAG = "ThoughtsReview"
}
