// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStallWatchdogTest {
    private val minute = 60_000L

    private fun record(
        id: String = "0190a000-0000-7000-8000-00000000000a",
        status: String = SyncOperationStore.STATUS_SYNCING,
        updatedAt: Long = 1_000,
        events: List<String> = emptyList(),
    ) = SyncOperationRecord(
        id = id, target = null, kind = SyncOperationKind.SYNC, status = status,
        startedAtEpochMillis = 1_000, updatedAtEpochMillis = updatedAt, events = events,
    )

    /** Looks every 30 s, as the watchdog does, and returns the look at which [id] was first stalled. */
    private fun firstStalledLook(observer: SyncStallObserver, looks: Int, at: (Int) -> List<SyncOperationRecord>): Int? {
        for (look in 0 until looks) {
            if (observer.stalled(at(look), look * SYNC_WATCHDOG_INTERVAL_MILLIS).isNotEmpty()) return look
        }
        return null
    }

    @Test
    fun aRunningSyncThatRecordsNothingForTenMinutesIsStalled() {
        val observer = SyncStallObserver()
        val stalledAt = firstStalledLook(observer, looks = 40) { listOf(record()) }
        // The first look starts the count; twenty more of 30 s make ten minutes.
        assertEquals(20, stalledAt)
        assertEquals(10 * minute, SYNC_STALL_DEADLINE_MILLIS)
    }

    @Test
    fun aSlowDownloadThatKeepsRecordingProgressIsNeverStalled() {
        val observer = SyncStallObserver()
        // Two hours, with a progress record every four minutes.
        val stalledAt = firstStalledLook(observer, looks = 240) { look -> listOf(record(updatedAt = 1_000L + look / 8)) }
        assertEquals(null, stalledAt)
    }

    @Test
    fun timeThePhoneSleptOrTheProcessWasFrozenIsNotHeldAgainstASync() {
        val observer = SyncStallObserver()
        assertTrue(observer.stalled(listOf(record()), 0).isEmpty())
        // Six hours later the watchdog looks again: that counts as one look, not as six hours.
        assertTrue(observer.stalled(listOf(record()), 6 * 60 * minute).isEmpty())
        var now = 6 * 60 * minute
        var looks = 0
        while (observer.stalled(listOf(record()), now).isEmpty()) {
            now += SYNC_WATCHDOG_INTERVAL_MILLIS
            looks++
        }
        // One minute was counted for the gap; nine more minutes of looks were needed.
        assertEquals(18, looks)
    }

    @Test
    fun aSyncWhoseWorkOnlyWaitsToRunIsNotCounted() {
        val observer = SyncStallObserver()
        // Its worker is not executing (it waits for a network): the caller does not pass it.
        repeat(30) { look -> assertTrue(observer.stalled(emptyList(), look * SYNC_WATCHDOG_INTERVAL_MILLIS).isEmpty()) }
        // When it runs again the count starts from nothing.
        val stalledAt = firstStalledLook(observer, looks = 40) { listOf(record()) }
        assertEquals(20, stalledAt)
    }

    @Test
    fun onlyASyncAtWorkCanStall() {
        val observer = SyncStallObserver()
        val waiting = listOf(
            record(id = "0190a000-0000-7000-8000-00000000000b", status = SyncOperationStore.STATUS_WAITING),
            record(id = "0190a000-0000-7000-8000-00000000000c", status = SyncOperationStore.STATUS_QUEUED),
            record(id = "0190a000-0000-7000-8000-00000000000d", status = SyncOperationStore.STATUS_RETRYING),
        )
        assertEquals(null, firstStalledLook(observer, looks = 60) { waiting })
        assertTrue(isActiveSyncOperation(record(status = SyncOperationStore.STATUS_BUILDING)))
        assertFalse(isActiveSyncOperation(record(status = SyncOperationStore.STATUS_SYNCED)))
    }

    @Test
    fun twoSyncsAreCountedSeparately() {
        val observer = SyncStallObserver()
        val hung = record(id = "0190a000-0000-7000-8000-00000000000e")
        var stalled = emptySet<String>()
        for (look in 0..20) {
            val busy = record(id = "0190a000-0000-7000-8000-00000000000f", updatedAt = 5_000L + look)
            stalled = observer.stalled(listOf(hung, busy), look * SYNC_WATCHDOG_INTERVAL_MILLIS)
        }
        assertEquals(setOf(hung.id), stalled)
    }

    @Test
    fun aStalledSyncIsEndedAlthoughItsWorkIsStillThere() {
        val running = record(updatedAt = 1_000)
        assertEquals(
            SyncOperationReconciliation.STALLED,
            syncOperationReconciliation(running, nowEpochMillis = 20 * minute, hasExecutionCounterpart = true,
                announceContinuation = false, stalled = true),
        )
        // Without the observer's verdict a Sync with work is left alone, however old its last record.
        assertEquals(
            SyncOperationReconciliation.KEEP,
            syncOperationReconciliation(running, nowEpochMillis = 20 * minute, hasExecutionCounterpart = true,
                announceContinuation = false),
        )
        // A Sync that has ended its work in the meantime is not stalled.
        assertEquals(
            SyncOperationReconciliation.KEEP,
            syncOperationReconciliation(record(status = SyncOperationStore.STATUS_WAITING), 20 * minute, true, false, stalled = true),
        )
    }

    @Test
    fun aSyncWithoutAnyWorkIsInterruptedAfterItsGraceAsBefore() {
        val orphan = record(status = SyncOperationStore.STATUS_QUEUED, updatedAt = 1_000)
        assertEquals(SyncOperationReconciliation.KEEP,
            syncOperationReconciliation(orphan, nowEpochMillis = 60_999, hasExecutionCounterpart = false, announceContinuation = true))
        assertEquals(SyncOperationReconciliation.INTERRUPTED,
            syncOperationReconciliation(orphan, nowEpochMillis = 61_000, hasExecutionCounterpart = false, announceContinuation = true))
        // The watchdog, which never announces, interrupts it just the same.
        assertEquals(SyncOperationReconciliation.INTERRUPTED,
            syncOperationReconciliation(orphan, nowEpochMillis = 61_000, hasExecutionCounterpart = false, announceContinuation = false))
    }

    @Test
    fun aSyncThatGoesOnIsAnnouncedOnceAndOnlyWhereItWasBefore() {
        val scheduled = record(status = SyncOperationStore.STATUS_QUEUED)
        assertEquals(SyncOperationReconciliation.ANNOUNCE_CONTINUATION,
            syncOperationReconciliation(scheduled, 5 * minute, hasExecutionCounterpart = true, announceContinuation = true))
        assertEquals(SyncOperationReconciliation.KEEP,
            syncOperationReconciliation(record(status = SyncOperationStore.STATUS_QUEUED, events = listOf(SYNC_CONTINUATION_EVENT)),
                5 * minute, hasExecutionCounterpart = true, announceContinuation = true))
        // The watchdog looks every 30 s and writes nothing into the log of a Sync that runs.
        assertEquals(SyncOperationReconciliation.KEEP,
            syncOperationReconciliation(scheduled, 5 * minute, hasExecutionCounterpart = true, announceContinuation = false))
    }

    @Test
    fun theMessageSaysWhatHappenedAndWhatToDo() {
        assertEquals("Sync stopped: nothing happened for 10 minutes. Tap Sync to try again.", stalledSyncFailureMessage())
    }

    @Test
    fun theForegroundServiceAnswersAndroidsTimeLimit() {
        val service = ThoughtsSyncForegroundService::class.java
        // Android 15 calls the two-argument form for a data-sync service, Android 14 the one-argument form.
        assertNotNull(service.getDeclaredMethod("onTimeout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        assertNotNull(service.getDeclaredMethod("onTimeout", Int::class.javaPrimitiveType))
    }
}
