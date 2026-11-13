// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SyncDiagnosticBatcherTest {
    @Test fun batchesAreBoundedAndOverflowIsExplicit() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = SyncDiagnosticBatcher { started.countDown(); release.await(3, TimeUnit.SECONDS) }
        blocker.append(SyncDiagnosticEvent("blocking", "started", emptyMap()))
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val saved = mutableListOf<SyncDiagnosticEvent>()
        try {
            val batcher = SyncDiagnosticBatcher { saved += it }
            repeat(1000) { batcher.append(SyncDiagnosticEvent("stage", "counters", mapOf("count" to it))) }
        } finally {
            release.countDown()
            SyncDiagnosticBatcher.flushAll()
        }
        assertEquals(128, saved.size)
        assertEquals("limit_reached", saved.last().state)
        assertEquals(873L, saved.last().values["dropped_events"])
    }

    @Test fun persistenceFailureDoesNotBlockLaterBatches() {
        val broken = SyncDiagnosticBatcher { error("unavailable") }
        broken.append(SyncDiagnosticEvent("stage", "started", emptyMap()))
        SyncDiagnosticBatcher.flushAll()
        val saved = mutableListOf<SyncDiagnosticEvent>()
        SyncDiagnosticBatcher { saved += it }.append(SyncDiagnosticEvent("later", "finished", emptyMap()))
        SyncDiagnosticBatcher.flushAll()
        assertEquals("later", saved.single().stage)
    }
}
