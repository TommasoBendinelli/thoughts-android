// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SingleFlightTest {
    @Test fun concurrentCallersShareTheNextRunAndRunsNeverOverlap() {
        val flight = SingleFlight<Int>()
        val runs = AtomicInteger()
        val active = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block(): Int {
            check(active.incrementAndGet() == 1) { "runs overlapped" }
            val run = runs.incrementAndGet()
            if (run == 1) { firstStarted.countDown(); release.await() }
            Thread.sleep(20)
            active.decrementAndGet()
            return run
        }
        val results = IntArray(5)
        val first = thread { results[0] = flight.run(::block) }
        firstStarted.await()
        // Four callers arrive during the first scan (as on 2026-10-10 22:46:18).
        val waiting = (1..4).map { i -> thread { results[i] = flight.run(::block) } }
        Thread.sleep(50)
        release.countDown()
        (waiting + first).forEach { it.join() }
        assertEquals(2, runs.get())
        assertEquals(listOf(1, 2, 2, 2, 2), results.toList())
    }

    @Test fun failuresReachEveryCallerOfThatRun() {
        val flight = SingleFlight<Int>()
        assertThrows(IllegalStateException::class.java) { flight.run { error("scan failed") } }
        assertEquals(3, flight.run { 3 })
    }
}
