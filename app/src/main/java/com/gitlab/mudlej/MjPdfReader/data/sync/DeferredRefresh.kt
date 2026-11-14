// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fetches something a Sync only shows, beside the Sync: the Sync starts the fetch and goes on, and
 * the answer is shown when it arrives, also after the Sync has ended. Only the newest fetch is
 * shown: one overtaken by a later fetch, or given up ([supersede]), is dropped when it arrives.
 */
internal class DeferredRefresh(private val scope: CoroutineScope) {
    private val newest = AtomicLong()

    fun <T> start(fetch: suspend () -> T, show: (Result<T>) -> Unit): Job {
        val mine = newest.incrementAndGet()
        return scope.launch {
            val result = try {
                Result.success(fetch())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            // What is only shown must never fail the process that fetched it.
            if (newest.get() == mine) runCatching { show(result) }
        }
    }

    /** Drops the answer of a fetch still under way, for a caller that shows something newer itself. */
    fun supersede() {
        newest.incrementAndGet()
    }
}
