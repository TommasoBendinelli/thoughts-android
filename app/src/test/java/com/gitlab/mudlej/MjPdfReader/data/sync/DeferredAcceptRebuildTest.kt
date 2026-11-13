// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.tbend.thoughts.sdk.BuildRecord
import org.tbend.thoughts.sdk.RenderCheckResult

/**
 * 2026-10-10, Fedora journal (CEST): 19:49:35 POST /v1/sources/apply carried three editor
 * accepts; 19:49:36 POST /v1/render-checks answered "current" (the kept PDF matches the source
 * through its accept equivalence, the rebuild waited out a 20 s idle window), and the Sync ended
 * "Up to date" at 19:49:42. The rebuilt PDF only arrived with the next Sync at 19:51:36.
 */
class DeferredAcceptRebuildTest {
    private fun build(id: String, status: String) = BuildRecord(
        id = id, target = "template_thoughts_internals.pdf", status = status, command = null, sourceFrontier = 162105,
        sourceSnapshotSha256 = "a".repeat(64), inputSnapshotSha256 = null, commentFrontier = 0, annotationFrontier = 0,
        toolchainKey = "toolchain", progress = JsonObject(), result = JsonObject(), error = null, cancellationRequested = false,
    )

    private val current = RenderCheckResult("current", 162105, build("previous-build", "succeeded"))
    private val building = RenderCheckResult("building", 162105, build("accepted-pdf-build", "queued"))

    private class Clock(var now: Long) {
        val sleeps = mutableListOf<Long>()
        fun sleep(millis: Long) { sleeps += millis; now += millis }
    }

    @Test fun anOldServerIsAskedAgainUntilTheAcceptRebuildIsQueued() {
        val delivered = 1_000L // sources/apply answered at 19:49:35
        val clock = Clock(delivered + 1_000L) // render-checks at 19:49:36
        var checks = 0
        val result = awaitDeferredAcceptRebuild(current, delivered, { clock.now }, clock::sleep) {
            checks++
            // The worker queues the rebuild once the 20 s idle window has passed.
            if (clock.now >= delivered + 21_000L) building else current
        }
        assertSame(building, result)
        assertEquals(10, checks)
        assertEquals(clock.sleeps.size, checks)
    }

    @Test fun aFixedServerAnswersWithTheRebuildAtOnce() {
        val clock = Clock(2_000L)
        val result = awaitDeferredAcceptRebuild(building, 1_000L, { clock.now }, clock::sleep) {
            error("must not ask again")
        }
        assertSame(building, result)
        assertEquals(emptyList<Long>(), clock.sleeps)
    }

    @Test fun withoutAnAcceptInThisSyncCurrentIsFinal() {
        val clock = Clock(2_000L)
        val result = awaitDeferredAcceptRebuild(current, null, { clock.now }, clock::sleep) {
            error("must not ask again")
        }
        assertSame(current, result)
    }

    @Test fun waitingIsBoundedWhenNoRebuildFollows() {
        // A newer build already replaced the kept PDF, so Fedora skips the accept rebuild.
        val delivered = 1_000L
        val clock = Clock(delivered + 1_000L)
        val result = awaitDeferredAcceptRebuild(current, delivered, { clock.now }, clock::sleep) { current }
        assertSame(current, result)
        assertEquals(delivered + DEFERRED_ACCEPT_REBUILD_WAIT_MILLIS, clock.now)
    }
}
