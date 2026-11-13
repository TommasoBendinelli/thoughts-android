// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Called on the UI thread; changes during a load request one follow-up, never overlapping I/O. */
internal class CoalescingReviewLoader<T>(
    private val scope: CoroutineScope,
    private val load: suspend () -> T,
    private val publish: (Result<T>) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null

    fun request() {
        generation += 1
        if (job?.isActive == true) return
        job = scope.launch {
            do {
                val requestedGeneration = generation
                val result = try {
                    Result.success(load())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                coroutineContext.ensureActive()
                if (requestedGeneration == generation) publish(result)
            } while (requestedGeneration != generation)
        }
    }
}
