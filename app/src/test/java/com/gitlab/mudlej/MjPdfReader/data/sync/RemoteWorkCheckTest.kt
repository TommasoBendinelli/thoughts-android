// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkCheckTest {
    private val second = 1_000L
    private val minute = 60_000L
    private val interval = 20 * second

    private fun key(sequence: Long, revision: Char = 'a') = "$sequence/" + revision.toString().repeat(64) + "/1"

    /** A phone and a server as one check sees them; time moves only when a test moves it. */
    private class FakePorts(var now: Long = 1_000_000L) : RemoteWorkPorts {
        var state = RemoteWatchState()
        var saves = 0
        var remote: RemoteWork? = null
        var reachable = true
        var visible = true
        var builds = listOf<PendingFedoraBuild>()
        var finishedBuildIds: Set<String>? = emptySet()
        /** Operation id to its end; null while it runs. */
        val operations = linkedMapOf<String, AutomaticSyncEnd?>()
        var userSyncRunning = false
        val started = mutableListOf<Long>()
        val reasons = mutableListOf<String>()
        val lines = mutableListOf<String>()

        override fun nowMillis() = now
        override fun loadState() = state
        override fun saveState(state: RemoteWatchState) { this.state = state; saves++ }
        override fun syncPending() = userSyncRunning || operations.values.any { it == null }
        override fun automaticSyncEnd(operationId: String) = operations[operationId]
        override fun serverReachable() = reachable
        override fun appVisible() = visible
        override fun pendingBuilds() = builds
        override suspend fun finishedBuilds(buildIds: List<String>) = finishedBuildIds
        override fun forgetBuilds(buildIds: Set<String>) { builds = builds.filter { it.buildId !in buildIds } }
        override suspend fun remoteWork() = remote
        override fun startAutomaticSync(reason: String): String {
            started += now
            reasons += reason
            return "operation-${started.size}".also { operations[it] = null }
        }
        override fun log(message: String) { lines += message }

        fun endAutomaticSync(succeeded: Boolean = true, endedAt: Long = now) {
            val running = operations.entries.single { it.value == null }.key
            operations[running] = AutomaticSyncEnd(endedAt, succeeded)
        }
    }

    private fun check(ports: FakePorts, syncWasRunning: Boolean) = runBlocking { remoteWorkCheck(ports, syncWasRunning) }

    /**
     * The watch's own loop for [durationMillis]: a check, then the pause it would make. The server
     * state differs at every check and leaves the phone behind; each Sync takes 10 s and ends as
     * [succeeds] says. Returns when each automatic Sync started, from the beginning.
     */
    private fun flood(durationMillis: Long, succeeds: Boolean): List<Long> {
        val ports = FakePorts()
        val begin = ports.now
        var sequence = 100L
        var syncWasRunning = true
        var runningSince: Long? = null
        while (ports.now - begin <= durationMillis) {
            ports.remote = RemoteWork(key(++sequence), cursorBehind = true)
            runningSince?.takeIf { ports.now - it >= 10 * second }?.let {
                ports.endAutomaticSync(succeeded = succeeds, endedAt = it + 10 * second)
                runningSince = null
            }
            val before = ports.started.size
            syncWasRunning = check(ports, syncWasRunning)
            if (ports.started.size > before) runningSince = ports.now
            ports.now += nextRemoteCheckDelayMillis(ports.state.pace, ports.now, interval)
        }
        return ports.started.map { it - begin }
    }

    @Test
    fun whileSyncsSucceedTheSpacingGoesFromTwentySecondsToTwoMinutesAndNoFurther() {
        var pace = AutomaticSyncPace()
        var now = 5_000_000L
        val waits = mutableListOf<Long>()
        repeat(6) {
            // The Sync ends, and 5 s later another is wanted.
            pace = pace.afterAutomaticSync(now, succeeded = true)
            val ended = now
            now += 5 * second
            val held = automaticSyncStart(pace, now)
            assertFalse(held.startNow)
            waits += held.notBeforeMillis - ended
            // One millisecond early it still waits; at the time it starts.
            assertFalse(automaticSyncStart(held.pace, held.notBeforeMillis - 1).startNow)
            val start = automaticSyncStart(held.pace, held.notBeforeMillis)
            assertTrue(start.startNow)
            assertNull(start.pace.heldSinceMillis)
            pace = start.pace
            now = held.notBeforeMillis + 10 * second
        }
        assertEquals(listOf(20L, 40L, 80L, 120L, 120L, 120L).map { it * second }, waits)
    }

    @Test
    fun onlyWhileSyncsFailDoesTheSpacingGrowToTenMinutesAndTheFirstSuccessBringsItBack() {
        var pace = AutomaticSyncPace()
        val waits = mutableListOf<Long>()
        repeat(7) { round ->
            pace = pace.afterAutomaticSync(round * 1_000_000L, succeeded = false)
            waits += pace.waitMillis
        }
        assertEquals(listOf(40L, 80L, 160L, 320L, 600L, 600L, 600L).map { it * second }, waits)
        // A held Sync after a failure waits out that spacing and does not double it once more.
        val ended = pace.lastEndedAtMillis!!
        val held = automaticSyncStart(pace, ended + 30 * second)
        assertEquals(ended + 10 * minute, held.notBeforeMillis)
        assertEquals(10 * minute, automaticSyncStart(held.pace, held.notBeforeMillis).pace.waitMillis)
        // The first automatic Sync that succeeds: at most two minutes again.
        val recovered = pace.afterAutomaticSync(ended + 11 * minute, succeeded = true)
        assertEquals(2 * minute, recovered.waitMillis)
        assertTrue(recovered.previousSucceeded)
        // A first failure doubles the spacing from where it stands.
        assertEquals(40 * second, AutomaticSyncPace().afterAutomaticSync(1, succeeded = false).waitMillis)
    }

    @Test
    fun theFirstAutomaticSyncAndOneAfterFiveQuietMinutesStartAtOnce() {
        assertTrue(automaticSyncStart(AutomaticSyncPace(), 1L).startNow)
        val climbed = AutomaticSyncPace(lastEndedAtMillis = 100 * minute, waitMillis = 2 * minute)
        // Wanted inside the spacing: held.
        assertFalse(automaticSyncStart(climbed, 101 * minute).startNow)
        // Wanted after the spacing but before five quiet minutes: at once, and the spacing stays.
        val later = automaticSyncStart(climbed, 103 * minute)
        assertTrue(later.startNow)
        assertEquals(2 * minute, later.pace.waitMillis)
        // Five minutes without an automatic Sync being wanted: at once, and the spacing starts again.
        val quiet = automaticSyncStart(climbed, 105 * minute)
        assertTrue(quiet.startNow)
        assertEquals(AUTOMATIC_SYNC_FIRST_WAIT_MILLIS, quiet.pace.waitMillis)
        assertEquals(20 * second, AUTOMATIC_SYNC_FIRST_WAIT_MILLIS)
    }

    @Test
    fun aSyncHeldThroughTheLongestSpacingDoesNotCountAsAQuietPeriod() {
        val failing = AutomaticSyncPace(lastEndedAtMillis = 0, waitMillis = 10 * minute, previousSucceeded = false)
        val held = automaticSyncStart(failing, 20 * second)
        assertFalse(held.startNow)
        val start = automaticSyncStart(held.pace, 10 * minute)
        assertTrue(start.startNow)
        assertEquals(10 * minute, start.pace.waitMillis)
    }

    @Test
    fun aClockSetBackHoldsNothing() {
        val future = AutomaticSyncPace(lastEndedAtMillis = 900 * minute, waitMillis = 2 * minute)
        val start = automaticSyncStart(future, 5 * minute)
        assertTrue(start.startNow)
        assertEquals(AutomaticSyncPace(), start.pace)
    }

    @Test
    fun theWatchWakesWhenAHeldSyncMayStart() {
        val held = AutomaticSyncPace(lastEndedAtMillis = 100 * second, waitMillis = 20 * second, heldSinceMillis = 105 * second)
        assertEquals(15 * second, nextRemoteCheckDelayMillis(held, 105 * second, interval))
        // Never a busy loop, never longer than the usual pause.
        assertEquals(1 * second, nextRemoteCheckDelayMillis(held, 125 * second, interval))
        assertEquals(interval, nextRemoteCheckDelayMillis(held.copy(waitMillis = 2 * minute), 105 * second, interval))
        assertEquals(interval, nextRemoteCheckDelayMillis(AutomaticSyncPace(lastEndedAtMillis = 100 * second), 105 * second, interval))
    }

    /**
     * 13 November 2026: the server's state differed at every 20-second check and each Sync left
     * the phone behind; 78 full Syncs started in 41 minutes. With Syncs that succeed the same 41
     * minutes now start 21: a few quick ones, then one every two minutes.
     */
    @Test
    fun aStateThatChangesAtEveryCheckStartsASyncEveryTwoMinutesNotAtEveryCheck() {
        val starts = flood(41 * minute, succeeds = true)
        assertEquals(21, starts.size)
        assertTrue(starts.size <= 25)
        assertEquals(listOf(0L, 30L, 80L, 170L, 300L, 430L), starts.take(6).map { it / second })
        // Each Sync takes 10 s; then the two-minute spacing.
        assertTrue(starts.drop(4).zipWithNext { a, b -> b - a }.all { it == 130 * second })
    }

    /** What 13 November was in fact: every one of those Syncs failed before it pulled anything. */
    @Test
    fun aSyncThatKeepsFailingIsTriedLessAndLessOften() {
        val starts = flood(41 * minute, succeeds = false)
        assertEquals(listOf(0L, 50L, 140L, 310L, 640L, 1250L, 1860L), starts.map { it / second })
    }

    /**
     * The user reads a proposal and waits for its PDF: the proposal's Sync starts at once, the
     * PDF's Sync starts at once although an automatic Sync ended seconds before, and a further
     * proposal right after it waits 20 s at most.
     */
    @Test
    fun aProposalAndThenItsPdfWithinAMinuteAreNotKeptWaiting() {
        val ports = FakePorts()
        val begin = ports.now
        ports.remote = RemoteWork(key(1), cursorBehind = false)
        assertFalse(check(ports, syncWasRunning = true))
        // A proposal arrives: its Sync starts at the check that sees it.
        ports.now = begin + 20 * second
        ports.remote = RemoteWork(key(2), cursorBehind = true)
        assertTrue(check(ports, syncWasRunning = false))
        assertEquals(listOf(begin + 20 * second), ports.started)
        ports.now = begin + 28 * second
        ports.endAutomaticSync()
        // A build an earlier Sync left pending finishes 4 s after that Sync ended.
        ports.remote = RemoteWork(key(2, 'b'), cursorBehind = false)
        ports.builds = listOf(PendingFedoraBuild("build-a", listOf("a.pdf")))
        ports.finishedBuildIds = setOf("build-a")
        ports.now = begin + 32 * second
        assertTrue(check(ports, syncWasRunning = true))
        assertEquals(begin + 32 * second, ports.started.last())
        assertTrue(ports.reasons.last().contains("finished 1 of 1 pending PDF build"))
        assertTrue(ports.builds.isEmpty())
        // Fetching it did not lengthen the spacing.
        assertEquals(AUTOMATIC_SYNC_FIRST_WAIT_MILLIS, ports.state.pace.waitMillis)
        ports.now = begin + 40 * second
        ports.endAutomaticSync()
        val fetched = ports.now
        // Another proposal 3 s after the PDF arrived: held, for 17 s.
        ports.remote = RemoteWork(key(3, 'b'), cursorBehind = true)
        ports.now = fetched + 3 * second
        check(ports, syncWasRunning = true)
        assertEquals(2, ports.started.size)
        assertTrue(ports.lines.last().contains("the Sync waits 17 s"))
        val pause = nextRemoteCheckDelayMillis(ports.state.pace, ports.now, interval)
        assertEquals(17 * second, pause)
        ports.now += pause
        assertTrue(check(ports, syncWasRunning = true))
        assertEquals(3, ports.started.size)
        assertEquals(20 * second, ports.started.last() - fetched)
        // From the first proposal's Sync to the last: 40 s for three automatic Syncs.
        assertEquals(40 * second, ports.started.last() - ports.started.first())
    }

    @Test
    fun theSyncForFinishedBuildsIsNeverHeldBackButItsEndCounts() {
        val ports = FakePorts()
        // Automatic Syncs in a row have lengthened the spacing to 80 s, and one ended 5 s ago.
        ports.state = RemoteWatchState(
            baseline = key(1), triggered = key(1),
            pace = AutomaticSyncPace(lastEndedAtMillis = ports.now - 5 * second, waitMillis = 80 * second),
        )
        ports.remote = RemoteWork(key(1), cursorBehind = false)
        ports.builds = listOf(PendingFedoraBuild("build-a", listOf("a.pdf")), PendingFedoraBuild("build-b", listOf("b.pdf")))
        ports.finishedBuildIds = setOf("build-a")
        assertTrue(check(ports, syncWasRunning = false))
        assertEquals(listOf(ports.now), ports.started)
        // Each finished build starts one Sync and is then forgotten; the other stays pending.
        assertEquals(listOf("build-b"), ports.builds.map { it.buildId })
        ports.now += 10 * second
        ports.endAutomaticSync()
        val ended = ports.now
        ports.finishedBuildIds = emptySet()
        // A changed state right after it keeps the spacing, counted from the end of that fetch.
        ports.remote = RemoteWork(key(2), cursorBehind = true)
        ports.now += 5 * second
        check(ports, syncWasRunning = true)
        assertEquals(1, ports.started.size)
        assertEquals(ended + 80 * second, automaticSyncHeldUntilMillis(ports.state.pace))
    }

    @Test
    fun aSyncTheUserStartsIsNeitherDelayedNorCounted() {
        val ports = FakePorts()
        ports.remote = RemoteWork(key(1), cursorBehind = false)
        assertFalse(check(ports, syncWasRunning = true))
        // New work: the first automatic Sync starts at once.
        ports.remote = RemoteWork(key(2), cursorBehind = true)
        assertTrue(check(ports, syncWasRunning = false))
        ports.now += 10 * second
        ports.endAutomaticSync()
        ports.remote = RemoteWork(key(2), cursorBehind = false)
        ports.now += 10 * second
        assertFalse(check(ports, syncWasRunning = true))
        val paceAfterAutomatic = ports.state.pace
        // The user Syncs twice; the watch only sees a Sync running, then the state after it.
        repeat(2) {
            ports.userSyncRunning = true
            ports.now += 5 * second
            assertTrue(check(ports, syncWasRunning = false))
            ports.userSyncRunning = false
            ports.remote = RemoteWork(key(3 + it.toLong()), cursorBehind = false)
            ports.now += 5 * second
            assertFalse(check(ports, syncWasRunning = true))
        }
        assertEquals(1, ports.started.size)
        assertEquals(paceAfterAutomatic, ports.state.pace)
    }

    @Test
    fun aHeldSyncIsDroppedWhenTheUsersSyncAlreadyFetchedTheWork() {
        val ports = FakePorts()
        ports.remote = RemoteWork(key(1), cursorBehind = true)
        assertTrue(check(ports, syncWasRunning = true))
        ports.now += 10 * second
        ports.endAutomaticSync()
        ports.remote = RemoteWork(key(1), cursorBehind = false)
        ports.now += 2 * second
        assertFalse(check(ports, syncWasRunning = true))
        // New work 5 s after the automatic Sync ended: held.
        ports.remote = RemoteWork(key(2), cursorBehind = true)
        ports.now += 3 * second
        assertFalse(check(ports, syncWasRunning = false))
        assertEquals(1, ports.started.size)
        assertEquals(ports.now, ports.state.pace.heldSinceMillis)
        assertTrue(ports.lines.last().contains("the Sync waits 15 s"))
        // The user Syncs; afterwards nothing is ahead, and nothing stays held.
        ports.userSyncRunning = true
        ports.now += 5 * second
        assertTrue(check(ports, syncWasRunning = false))
        ports.userSyncRunning = false
        ports.remote = RemoteWork(key(2), cursorBehind = false)
        ports.now += 5 * second
        assertFalse(check(ports, syncWasRunning = true))
        assertNull(ports.state.pace.heldSinceMillis)
        ports.now += minute
        assertFalse(check(ports, syncWasRunning = false))
        assertEquals(1, ports.started.size)
        // That held Sync never ran, so the spacing did not double.
        assertEquals(AUTOMATIC_SYNC_FIRST_WAIT_MILLIS, ports.state.pace.waitMillis)
    }

    @Test
    fun aHeldSyncKeepsNothingOfTheDecisionAndStartsOnceItsTimeHasCome() {
        val ports = FakePorts()
        ports.remote = RemoteWork(key(1), cursorBehind = false)
        assertFalse(check(ports, syncWasRunning = true))
        ports.remote = RemoteWork(key(2), cursorBehind = false)
        assertTrue(check(ports, syncWasRunning = false))
        ports.now += 10 * second
        ports.endAutomaticSync()
        val ended = ports.now
        ports.now += 2 * second
        assertFalse(check(ports, syncWasRunning = true))
        assertEquals(key(2), ports.state.baseline)
        // Another change 5 s after the end.
        ports.remote = RemoteWork(key(3), cursorBehind = false)
        ports.now = ended + 5 * second
        assertFalse(check(ports, syncWasRunning = false))
        // While it is held the state it is for is not marked as handled.
        assertEquals(key(2), ports.state.triggered)
        assertEquals(key(2), ports.state.baseline)
        ports.now = ended + 19 * second
        assertFalse(check(ports, syncWasRunning = false))
        ports.now = ended + 20 * second
        assertTrue(check(ports, syncWasRunning = false))
        assertEquals(listOf(ended + 20 * second), ports.started.drop(1))
        assertEquals(key(3), ports.state.triggered)
        assertEquals(40 * second, ports.state.pace.waitMillis)
    }

    @Test
    fun anAutomaticSyncThatFailedIsNoReasonForTheNextOne() {
        val ports = FakePorts()
        ports.remote = RemoteWork(key(1), cursorBehind = false)
        assertFalse(check(ports, syncWasRunning = true))
        ports.remote = RemoteWork(key(2), cursorBehind = true)
        assertTrue(check(ports, syncWasRunning = false))
        // It fails before pulling; meanwhile the state moved again.
        ports.now += 10 * second
        ports.endAutomaticSync(succeeded = false)
        ports.remote = RemoteWork(key(3), cursorBehind = true)
        ports.now += 20 * minute
        // Long after any spacing: still no Sync, because nothing changed after the failed one.
        assertFalse(check(ports, syncWasRunning = true))
        assertEquals(key(3), ports.state.baseline)
        assertFalse(ports.state.pace.previousSucceeded)
        assertFalse(check(ports, syncWasRunning = false))
        assertEquals(1, ports.started.size)
        // A later change is work again.
        ports.remote = RemoteWork(key(4), cursorBehind = true)
        assertTrue(check(ports, syncWasRunning = false))
        assertEquals(2, ports.started.size)
    }

    @Test
    fun operationsThatArrivedDuringASuccessfulSyncArePulledTwentySecondsAfterIt() {
        val ports = FakePorts()
        ports.remote = RemoteWork(key(1), cursorBehind = false)
        assertFalse(check(ports, syncWasRunning = true))
        ports.remote = RemoteWork(key(2), cursorBehind = true)
        assertTrue(check(ports, syncWasRunning = false))
        ports.now += 10 * second
        ports.endAutomaticSync()
        val ended = ports.now
        // An agent's proposal arrived after that Sync had pulled.
        ports.remote = RemoteWork(key(3), cursorBehind = true)
        ports.now += 10 * second
        // Held: the check still counts as the one after a Sync, so the next one decides the same.
        assertTrue(check(ports, syncWasRunning = true))
        assertEquals(1, ports.started.size)
        ports.now += nextRemoteCheckDelayMillis(ports.state.pace, ports.now, interval)
        assertEquals(ended + 20 * second, ports.now)
        assertTrue(check(ports, syncWasRunning = true))
        assertEquals(2, ports.started.size)
    }

    @Test
    fun theSpacingAndAHeldSyncSurviveARestartOfTheApp() {
        val ports = FakePorts()
        ports.remote = RemoteWork(key(1), cursorBehind = true)
        assertTrue(check(ports, syncWasRunning = true))
        ports.now += 10 * second
        ports.endAutomaticSync(succeeded = false)
        val failedAt = ports.now
        ports.now += 2 * second
        assertFalse(check(ports, syncWasRunning = true))
        ports.remote = RemoteWork(key(2), cursorBehind = true)
        ports.now += 3 * second
        assertFalse(check(ports, syncWasRunning = false))
        // Everything the next check needs is in what was saved: the app may be closed here.
        val saved = ports.state
        assertEquals(40 * second, saved.pace.waitMillis)
        assertFalse(saved.pace.previousSucceeded)
        assertEquals(ports.now, saved.pace.heldSinceMillis)
        val restarted = FakePorts(now = ports.now + 20 * second).apply {
            state = saved
            remote = RemoteWork(key(2), cursorBehind = true)
        }
        // 25 s after the failed Sync, with 40 s to keep: still held after the restart.
        check(restarted, syncWasRunning = true)
        assertTrue(restarted.started.isEmpty())
        restarted.now += nextRemoteCheckDelayMillis(restarted.state.pace, restarted.now, interval)
        check(restarted, syncWasRunning = true)
        assertEquals(listOf(failedAt + 40 * second), restarted.started)
    }

    @Test
    fun anIdleCheckWritesNothing() {
        val ports = FakePorts()
        ports.remote = RemoteWork(key(1), cursorBehind = false)
        assertFalse(check(ports, syncWasRunning = true))
        val saves = ports.saves
        repeat(5) {
            ports.now += interval
            assertFalse(check(ports, syncWasRunning = false))
        }
        assertEquals(saves, ports.saves)
    }

    @Test
    fun nothingStartsWhileTheServerCannotBeAskedOrASyncRuns() {
        val ports = FakePorts()
        ports.remote = RemoteWork(key(1), cursorBehind = true)
        ports.reachable = false
        assertFalse(check(ports, syncWasRunning = true))
        ports.reachable = true
        ports.userSyncRunning = true
        assertTrue(check(ports, syncWasRunning = false))
        ports.userSyncRunning = false
        ports.remote = null
        assertFalse(check(ports, syncWasRunning = true))
        assertTrue(ports.started.isEmpty())
    }
}
