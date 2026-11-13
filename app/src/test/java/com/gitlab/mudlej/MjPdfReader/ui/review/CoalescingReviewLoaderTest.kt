// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescingReviewLoaderTest {
    @Test fun changesDuringLoadRunOneFollowUpWithoutCancellingOrPublishingOldData() = runBlocking {
        val firstLoad = CompletableDeferred<Unit>()
        val published = mutableListOf<Int>()
        var calls = 0
        var completed = 0
        val loader = CoalescingReviewLoader(this, {
            val value = ++calls
            if (value == 1) firstLoad.await()
            completed++
            value
        }, { published += it.getOrThrow() })
        loader.request()
        yield()
        repeat(20) { loader.request() }
        yield()
        assertEquals(1, calls)
        firstLoad.complete(Unit)
        yield()
        assertEquals(2, calls)
        assertEquals(2, completed)
        assertEquals(listOf(2), published)
        loader.request()
        yield()
        assertEquals(listOf(2, 3), published)
    }

    @Test fun closingScreenCancelsPendingWorkWithoutPublishingFailure() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val pending = CompletableDeferred<Unit>()
        val results = mutableListOf<Result<Int>>()
        val loader = CoalescingReviewLoader(scope, { pending.await(); 1 }, results::add)
        loader.request()
        yield()
        scope.cancel()
        yield()
        assertTrue(results.isEmpty())
    }

    @Test fun failedLoadCanBeRetried() = runBlocking {
        var calls = 0
        val results = mutableListOf<Result<Int>>()
        val loader = CoalescingReviewLoader(this, {
            if (++calls == 1) error("Read failed")
            calls
        }, results::add)
        loader.request()
        yield()
        loader.request()
        yield()
        assertTrue(results.first().isFailure)
        assertEquals(2, results.last().getOrThrow())
    }
}
