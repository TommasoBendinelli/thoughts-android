// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CommentSnapshotCacheTest {
    @Test fun concurrentLoadsAndRepeatedRenderingReuseOneSnapshot() = runBlocking {
        var loads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = CommentCatalogueSnapshot(mapOf("paper" to CommentDocumentMetadata(openThreads = 3)))
        val cache = CommentSnapshotCache {
            loads++
            entered.complete(Unit)
            release.await()
            result
        }
        withTimeout(5_000) {
            val readers = List(20) { async { cache.load() } }
            entered.await()
            // Rendering remains available even while the database loader is suspended.
            repeat(100) { assertEquals(0, cache.current().metadataFor("paper").openThreads) }
            release.complete(Unit)
            readers.awaitAll().forEach { assertSame(result, it) }
            repeat(100) { assertSame(result, cache.current()); assertSame(result, cache.load()) }
            assertEquals(1, loads)
        }
    }

    @Test fun invalidationDuringLoadDiscardsObsoleteResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = CommentCatalogueSnapshot(mapOf("paper" to CommentDocumentMetadata(openThreads = 1)))
        val updated = CommentCatalogueSnapshot()
        var loads = 0
        val cache = CommentSnapshotCache {
            if (++loads == 1) {
                entered.complete(Unit)
                release.await()
                old
            } else updated
        }
        withTimeout(5_000) {
            val loading = async { cache.load() }
            entered.await()
            cache.invalidate()
            release.complete(Unit)
            assertSame(updated, loading.await())
            assertSame(updated, cache.current())
            assertSame(updated, cache.load())
            assertEquals(2, loads)
        }
    }

    @Test fun failedRefreshRetainsLastSnapshotAndCanRetry() = runBlocking {
        var fail = false
        val initial = CommentCatalogueSnapshot(mapOf("paper" to CommentDocumentMetadata(openThreads = 2)))
        val cache = CommentSnapshotCache {
            check(!fail) { "Database unavailable" }
            initial
        }
        assertSame(initial, cache.load())
        cache.invalidate()
        fail = true
        assertTrue(runCatching { cache.load() }.isFailure)
        assertSame(initial, cache.current())
        fail = false
        assertSame(initial, cache.load())
    }
}
