// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredRefreshTest {
    @Test
    fun theSyncGoesOnWhileTheStatusIsFetchedAndItIsShownWhenItArrives() = runBlocking {
        val shown = mutableListOf<Result<String>>()
        val server = CompletableDeferred<String>()
        val steps = mutableListOf<String>()
        val refresh = DeferredRefresh(this)
        val job = refresh.start(fetch = { steps += "asked"; server.await() }, show = { shown += it; steps += "shown" })
        yield()
        // The Sync ends without the answer.
        steps += "sync ended"
        assertTrue(shown.isEmpty())
        server.complete("current")
        job.join()
        assertEquals(listOf("asked", "sync ended", "shown"), steps)
        assertEquals(listOf(Result.success("current")), shown)
    }

    @Test
    fun anAnswerOvertakenByALaterFetchIsNotShown() = runBlocking {
        val shown = mutableListOf<String>()
        val slow = CompletableDeferred<String>()
        val refresh = DeferredRefresh(this)
        val first = refresh.start(fetch = { slow.await() }, show = { shown += it.getOrThrow() })
        val second = refresh.start(fetch = { "newer" }, show = { shown += it.getOrThrow() })
        second.join()
        slow.complete("older")
        first.join()
        assertEquals(listOf("newer"), shown)
    }

    @Test
    fun aFetchGivenUpByItsSyncIsNotShown() = runBlocking {
        val shown = mutableListOf<String>()
        val server = CompletableDeferred<String>()
        val refresh = DeferredRefresh(this)
        val job = refresh.start(fetch = { server.await() }, show = { shown += it.getOrThrow() })
        // The Sync failed and shows that itself.
        refresh.supersede()
        server.complete("current")
        job.join()
        assertTrue(shown.isEmpty())
    }

    @Test
    fun aDisplayThatFailsDoesNotFailWhoeverStartedTheFetch() = runBlocking {
        val refresh = DeferredRefresh(this)
        refresh.start(fetch = { "current" }, show = { error("the status could not be stored") }).join()
        // The next fetch is shown as usual.
        val shown = mutableListOf<String>()
        refresh.start(fetch = { "newer" }, show = { shown += it.getOrThrow() }).join()
        assertEquals(listOf("newer"), shown)
    }

    @Test
    fun aFailedFetchIsShownAsAFailureAndACancelledOneNotAtAll() = runBlocking {
        val shown = mutableListOf<Result<String>>()
        val refresh = DeferredRefresh(this)
        refresh.start<String>(fetch = { error("unreachable") }, show = { shown += it }).join()
        assertEquals("unreachable", shown.single().exceptionOrNull()?.message)
        val never = CompletableDeferred<String>()
        val job = refresh.start(fetch = { never.await() }, show = { shown += it })
        yield()
        job.cancelAndJoin()
        assertEquals(1, shown.size)
    }
}
