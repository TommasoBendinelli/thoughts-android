// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import java.util.concurrent.ConcurrentHashMap

/**
 * Coalesces blocking calls per key. Runs never overlap. A caller that arrives while a run is in
 * progress waits for the following run, which starts after its call, and receives that run's
 * result; every caller arriving meanwhile shares the same following run.
 */
internal class SingleFlight<T> {
    private val lock = Object()
    private var running = false
    private var started = 0L
    private var finished = 0L
    private var lastResult: Result<T>? = null

    fun run(block: () -> T): T {
        val needed: Long
        synchronized(lock) {
            needed = started + 1
            while (true) {
                if (finished >= needed) return lastResult!!.getOrThrow()
                if (!running) {
                    running = true
                    started += 1
                    break
                }
                lock.wait()
            }
        }
        val result = runCatching(block)
        synchronized(lock) {
            lastResult = result
            finished = started
            running = false
            lock.notifyAll()
        }
        return result.getOrThrow()
    }

    companion object {
        private val flights = ConcurrentHashMap<String, SingleFlight<Int>>()
        fun forKey(key: String): SingleFlight<Int> = flights.getOrPut(key) { SingleFlight() }
    }
}
