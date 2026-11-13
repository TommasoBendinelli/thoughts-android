// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.*
import org.junit.Test

class SyncStageTimingTest {
    @Test fun reportsFastStageDurationAndResult() {
        val events = mutableListOf<String>()
        val times = listOf(5_000_000_000L, 5_012_000_000L).iterator()
        val value = measureSyncStage("Installing sources", { events.add(it) }, { times.next() }) { 42 }
        assertEquals(42, value)
        assertEquals(listOf("Installing sources", "Installing sources: 0.012 s"), events)
    }

    @Test fun preservesFailureWithoutSuccessfulCompletion() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("original failure")
        val caught = runCatching {
            measureSyncStage("Validation", { events.add(it) }, { 1L }) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(listOf("Validation", "Validation failed after: 0.000 s"), events)
    }

    @Test fun loggingFailureDoesNotAlterResultOrOriginalError() {
        assertEquals(42, measureSyncStage("Stage", { error("log unavailable") }) { 42 })
        val failure = IllegalStateException("work failed")
        assertSame(failure, runCatching {
            measureSyncStage("Stage", { error("log unavailable") }) { throw failure }
        }.exceptionOrNull())
    }
}
