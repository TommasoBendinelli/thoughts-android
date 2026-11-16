// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pause of the watch ([RemoteStatePause]) in its loop ([watchRemoteWork]), against a fake server and clock. */
class RemoteStatePauseTest {
    private val second = 1_000L
    private val interval = 20 * second

    /**
     * A server and a clock that moves only while the watch waits. The server holds a Sync state
     * request as the Thoughts server does; a check reads the state as FedoraRepository.remoteWork
     * does: what a held request just confirmed, else one request.
     */
    private class World(var holds: Boolean = true) : RemoteStatePausePorts {
        var now = 0L
        var version = 1
        /** When the server's state changes. */
        val changesAt = ArrayDeque<Long>()
        /** When each request was sent, and which: "plain" or "held". */
        val requests = mutableListOf<Pair<Long, String>>()
        val waits = mutableListOf<Long>()
        val sleeps = mutableListOf<Long>()
        /** When each check ran and the state version it read. */
        val checks = mutableListOf<Pair<Long, Int>>()
        /** What the next held requests end with instead of being held. */
        val refusals = ArrayDeque<SyncStateWait>()
        private val kept = ConditionalAnswer<JsonObject>()
        private val key = "https://thoughts.example.org"

        private fun advanceTo(time: Long) {
            now = time
            while (changesAt.firstOrNull()?.let { it <= now } == true) {
                changesAt.removeFirst()
                version++
            }
        }

        private fun answer(): JsonObject =
            JsonObject().apply { addProperty("version", version) }.also { kept.keep(key, "\"v$version\"", it) }

        override fun elapsedMillis() = now

        override suspend fun sleep(millis: Long) {
            sleeps += millis
            advanceTo(now + millis)
        }

        override suspend fun stateWaitsAvailable() = holds

        override suspend fun awaitStateChange(waitMillis: Long): SyncStateWait {
            requests += now to "held"
            waits += waitMillis
            refusals.removeFirstOrNull()?.let {
                advanceTo(now + 50)
                return it
            }
            val keptVersion = kept.kept(key)?.second?.get("version")?.asInt
            val change = changesAt.firstOrNull()
            val outcome = when {
                keptVersion != version -> SyncStateWait.CHANGED.also { answer() }
                change != null && change <= now + waitMillis -> {
                    // The server's watch reads the state within its spacing of the change.
                    advanceTo(change + 250)
                    answer()
                    SyncStateWait.CHANGED
                }
                else -> {
                    advanceTo(now + waitMillis)
                    SyncStateWait.UNCHANGED
                }
            }
            kept.confirm(key, now)
            return outcome
        }

        fun check(): Boolean {
            val state = kept.takeConfirmed(key, now, 3_000) ?: run {
                requests += now to "plain"
                answer()
            }
            checks += now to state.get("version").asInt
            return false
        }
    }

    /** The watch's loop until [untilMillis]: a check, then the pause it would make. */
    private fun run(world: World, untilMillis: Long, pauseMillis: () -> Long = { interval }) = runBlocking {
        val pause = RemoteStatePause(world, interval)
        try {
            watchRemoteWork(
                check = { _ ->
                    world.check().also { if (world.now >= untilMillis) throw CancellationException("the end of the test") }
                },
                pause = { syncRunning -> pause.pause(pauseMillis(), mayHold = !syncRunning) },
            )
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun aChangeDuringTheWaitStartsTheCheckWithinASecond() {
        val world = World()
        world.changesAt += 47_300L
        run(world, untilMillis = 100 * second)
        val (seenAt, version) = world.checks.first { it.second == 2 }
        assertEquals(2, version)
        assertTrue("checked ${seenAt - 47_300} ms after the change", seenAt - 47_300 < second)
        // The check took the held request's answer: no plain request after the first check.
        assertEquals(listOf(0L to "plain"), world.requests.filter { it.second == "plain" })
    }

    @Test
    fun withoutChangesThereIsOneRequestPer25SecondsInsteadOfOnePer20() {
        val holding = World(holds = true)
        run(holding, untilMillis = 300 * second)
        val polling = World(holds = false)
        run(polling, untilMillis = 300 * second)

        val held = holding.requests.drop(1)
        assertTrue(held.all { it.second == "held" })
        assertEquals(setOf(25 * second), held.zipWithNext { a, b -> b.first - a.first }.toSet())
        assertEquals(setOf(SYNC_STATE_LONGEST_WAIT_MILLIS), holding.waits.toSet())
        assertTrue(polling.requests.all { it.second == "plain" })
        assertEquals(setOf(20 * second), polling.requests.zipWithNext { a, b -> b.first - a.first }.toSet())
        // In five minutes: 1 + 12 requests instead of 1 + 15.
        assertEquals(13, holding.requests.size)
        assertEquals(16, polling.requests.size)
    }

    @Test
    fun withoutTheCapabilityTheWatchBehavesAsBefore() {
        val world = World(holds = false)
        world.changesAt += 47_300L
        run(world, untilMillis = 100 * second)
        assertTrue(world.waits.isEmpty())
        assertEquals(setOf(interval), world.sleeps.toSet())
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L, 80_000L, 100_000L), world.checks.map { it.first })
        // The change is seen at the next check, as before.
        assertEquals(60_000L to 2, world.checks.first { it.second == 2 })
    }

    @Test
    fun aFailedOrUnheldRequestGivesPlainPausesForTwoMinutes() {
        for (refusal in listOf(SyncStateWait.FAILED, SyncStateWait.NOT_HELD)) {
            val world = World()
            world.refusals += refusal
            run(world, untilMillis = 200 * second)
            val (failedAt, kind) = world.requests[1]
            assertEquals(0L to "held", failedAt to kind)
            // The pause in which it failed ends as a plain one would: 20 s after it began.
            assertEquals(20_000L, world.checks[1].first)
            val held = world.requests.filter { it.second == "held" }.map { it.first }
            // No held request again until two minutes after the failure.
            assertTrue(held.drop(1).all { it >= 50 + 120_000 })
            assertTrue(held.size > 1)
            assertEquals(setOf(interval - 50), world.sleeps.take(1).toSet())
        }
    }

    @Test
    fun aHeldRequestThatThrowsIsAFailureAndThePauseStaysPlain() = runBlocking {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        var asked = 0
        val ports = object : RemoteStatePausePorts {
            override fun elapsedMillis() = now
            override suspend fun sleep(millis: Long) {
                sleeps += millis
                now += millis
            }
            override suspend fun stateWaitsAvailable() = true
            override suspend fun awaitStateChange(waitMillis: Long): SyncStateWait {
                asked++
                throw IllegalStateException("no server configured")
            }
        }
        val pause = RemoteStatePause(ports, interval)
        repeat(3) { pause.pause(interval, mayHold = true) }
        assertEquals(1, asked)
        assertEquals(listOf(interval, interval, interval), sleeps)
    }

    @Test
    fun aHeldSyncDueSoonerEndsTheWaitWhenItMayStart() = runBlocking {
        val world = World()
        world.check()
        RemoteStatePause(world, interval).pause(pauseMillis = 7 * second, mayHold = true)
        assertEquals(listOf(7 * second), world.waits)
        assertEquals(7 * second, world.now)
    }

    @Test
    fun whileASyncRunsThePauseIsPlain() = runBlocking {
        val world = World()
        RemoteStatePause(world, interval).pause(pauseMillis = interval, mayHold = false)
        assertTrue(world.waits.isEmpty())
        assertEquals(listOf(interval), world.sleeps)
    }

    @Test
    fun leavingTheScreenEndsTheHeldRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val ports = object : RemoteStatePausePorts {
            override fun elapsedMillis() = System.nanoTime() / 1_000_000
            override suspend fun sleep(millis: Long) = kotlinx.coroutines.delay(millis)
            override suspend fun stateWaitsAvailable() = true
            override suspend fun awaitStateChange(waitMillis: Long): SyncStateWait {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val pause = RemoteStatePause(ports, interval)
        var checks = 0
        val watch = launch(Dispatchers.Default) {
            watchRemoteWork(check = { checks++; false }, pause = { pause.pause(interval, mayHold = !it) })
        }
        withTimeout(5_000) { started.await() }
        val cancelledAt = System.nanoTime()
        // What RemoteWorkWatch does when the last activity stops.
        watch.cancel()
        withTimeout(1_000) { watch.join() }
        assertTrue((System.nanoTime() - cancelledAt) / 1_000_000 < 1_000)
        assertTrue(cancelled)
        assertEquals(1, checks)
        assertFalse(watch.isActive)
    }
}
