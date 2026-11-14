// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * How long a running Sync may go without any sign of progress before it is ended as failed. A
 * Sync records progress with every step, every message of its log and every stretch of bytes it
 * sends or receives, so a long download is not a stalled Sync; one whose worker runs and records
 * nothing for this long hangs, and until now it stayed "Syncing…" for good.
 */
internal const val SYNC_STALL_DEADLINE_MILLIS = 10 * 60_000L

/** How often a running Sync is looked at ([SyncStallWatchdog]). */
internal const val SYNC_WATCHDOG_INTERVAL_MILLIS = 30_000L

/** A Sync that is at work now: neither queued nor waiting for a network, the user or a retry. */
internal fun isActiveSyncOperation(record: SyncOperationRecord): Boolean =
    record.status == SyncOperationStore.STATUS_SYNCING || record.status == SyncOperationStore.STATUS_BUILDING

/**
 * Tells a Sync that hangs from one that waits or was put to sleep. It is asked at every look of
 * [SyncStallWatchdog] with the Syncs whose worker or job is executing in this process right now,
 * and counts for each the time it was looked at without having recorded anything. Only time the
 * watchdog itself was running counts (at most [longestCountedGapMillis] per look), so the hours a
 * sleeping phone or a frozen process skips are not held against a Sync; and a Sync whose work is
 * merely scheduled (waiting for a network, or for Android to run it again) is not counted at all.
 */
internal class SyncStallObserver(
    private val deadlineMillis: Long = SYNC_STALL_DEADLINE_MILLIS,
    private val longestCountedGapMillis: Long = 2 * SYNC_WATCHDOG_INTERVAL_MILLIS,
) {
    private class Seen(var updatedAtEpochMillis: Long, var idleMillis: Long, var lookedAtMillis: Long)

    private val seen = hashMapOf<String, Seen>()

    /** The Syncs among [executing] that recorded nothing for the deadline; [nowMillis] is a clock that does not jump. */
    @Synchronized
    fun stalled(executing: List<SyncOperationRecord>, nowMillis: Long): Set<String> {
        val atWork = executing.filter(::isActiveSyncOperation)
        seen.keys.retainAll(atWork.mapTo(hashSetOf(), SyncOperationRecord::id))
        val stalled = linkedSetOf<String>()
        for (record in atWork) {
            val known = seen[record.id]
            if (known == null || known.updatedAtEpochMillis != record.updatedAtEpochMillis) {
                seen[record.id] = Seen(record.updatedAtEpochMillis, 0, nowMillis)
                continue
            }
            known.idleMillis += (nowMillis - known.lookedAtMillis).coerceIn(0, longestCountedGapMillis)
            known.lookedAtMillis = nowMillis
            if (known.idleMillis >= deadlineMillis) stalled += record.id
        }
        return stalled
    }
}

/** What the user reads when a Sync was ended because it stalled. */
internal fun stalledSyncFailureMessage(deadlineMillis: Long = SYNC_STALL_DEADLINE_MILLIS): String =
    "Sync stopped: nothing happened for ${deadlineMillis / 60_000} minutes. Tap Sync to try again."

/** What one look at the pending Syncs does with each of them. */
internal enum class SyncOperationReconciliation {
    /** Left as it is. */
    KEEP,
    /** Its log says once that it goes on with scheduled work. */
    ANNOUNCE_CONTINUATION,
    /** No work is scheduled or running for it any more: "Sync was interrupted". */
    INTERRUPTED,
    /** Its work runs but recorded nothing for the deadline: ended as failed, and the work cancelled. */
    STALLED,
}

/**
 * What to do with the pending Sync [record]. A [stalled] Sync ([SyncStallObserver]) is ended
 * although work is running for it: that work is what hangs. Otherwise, as before, a Sync without
 * scheduled or running work is interrupted once [shouldMarkSyncOperationInterrupted] says so, and
 * one with such work is announced as continuing, once, when [announceContinuation] is set.
 */
internal fun syncOperationReconciliation(
    record: SyncOperationRecord,
    nowEpochMillis: Long,
    hasExecutionCounterpart: Boolean,
    announceContinuation: Boolean,
    stalled: Boolean = false,
): SyncOperationReconciliation = when {
    stalled && isActiveSyncOperation(record) -> SyncOperationReconciliation.STALLED
    hasExecutionCounterpart && announceContinuation && SYNC_CONTINUATION_EVENT !in record.events ->
        SyncOperationReconciliation.ANNOUNCE_CONTINUATION
    shouldMarkSyncOperationInterrupted(record, nowEpochMillis, hasExecutionCounterpart) ->
        SyncOperationReconciliation.INTERRUPTED
    else -> SyncOperationReconciliation.KEEP
}

internal const val SYNC_CONTINUATION_EVENT = "Continuing the sync you started"

/**
 * Looks at the pending Syncs while one is at work, wherever the app is: on screen, behind another
 * app, or with no screen at all. Until now only the Library screen looked, so a Sync left without
 * its worker stayed "Syncing…" (and held back every automatic Sync) until that screen was opened.
 * It starts with every Sync and when the app starts with one pending, and stops when none is at
 * work; a queued or waiting Sync starts it again when its work begins.
 */
internal object SyncStallWatchdog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val observer = SyncStallObserver()

    @Synchronized
    fun ensureRunning(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            while (isActive) {
                val atWork = runCatching {
                    ThoughtsSyncScheduler.reconcileInterruptedOperations(
                        appContext, announceContinuation = false, stallObserver = observer,
                    )
                    SyncOperationStore(appContext).pendingOperations().any(::isActiveSyncOperation)
                }.onFailure { Log.w("ThoughtsSync", "Could not look at the pending Syncs: ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
                if (!atWork) break
                delay(SYNC_WATCHDOG_INTERVAL_MILLIS)
            }
        }
    }
}
