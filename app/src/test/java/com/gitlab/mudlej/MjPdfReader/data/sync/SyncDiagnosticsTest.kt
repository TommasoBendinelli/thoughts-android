// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class SyncDiagnosticsTest {
    @Test fun stageRecordsMatchingIdsDurationAndMemory() {
        val events = mutableListOf<SyncDiagnosticEvent>()
        var now = 0L
        val diagnostics = SyncDiagnostics({ events += it }, { now }, { mapOf("java_heap_used_bytes" to 123L) })
        assertEquals(42, diagnostics.measure("parse") { now = 5_000_000; 42 })
        assertEquals(listOf("started", "finished"), events.map { it.state })
        assertEquals(events[0].values["span_id"], events[1].values["span_id"])
        assertEquals(5_000_000L, events[1].values["duration_ns"])
        assertEquals(123L, events[1].values["java_heap_used_bytes"])
    }

    @Test fun exceptionsAreRecordedWithoutTheirSensitiveMessageAndRethrownUnchanged() {
        val events = mutableListOf<SyncDiagnosticEvent>()
        val diagnostics = SyncDiagnostics({ events += it }, memory = { emptyMap() })
        val failure = IllegalArgumentException("private document or credential")
        assertSame(failure, runCatching { diagnostics.measure("parse") { throw failure } }.exceptionOrNull())
        assertEquals("failed", events.last().state)
        assertFalse(events.toString().contains(failure.message!!))
    }

    @Test fun brokenLoggerAndMemoryProbeCannotFailWork() {
        val diagnostics = SyncDiagnostics({ error("database unavailable") }, memory = { error("sample unavailable") })
        assertEquals(7, diagnostics.measure("stage") { 7 })
        val original = IllegalStateException("work failed")
        assertSame(original, runCatching { diagnostics.measure("stage") { throw original } }.exceptionOrNull())
    }

    @Test fun nullableResultIsNeverExecutedTwice() {
        var calls = 0
        val progress = DiagnosticSyncProgress(SyncDiagnostics({}, memory = { emptyMap() })) {}
        assertNull(measureSyncDiagnostic("nullable", progress) { calls++; null })
        assertEquals(1, calls)
    }

    @Test fun mutexReleasesAfterFailureAndRecordsWaitSeparately() = runBlocking {
        val events = mutableListOf<SyncDiagnosticEvent>()
        val diagnostics = SyncDiagnostics({ events += it }, memory = { emptyMap() })
        val mutex = Mutex()
        val failure = IllegalStateException("failed")
        assertSame(failure, runCatching { mutex.withDiagnosticLock(diagnostics, "sync") { throw failure } }.exceptionOrNull())
        assertFalse(mutex.isLocked)
        assertEquals(listOf("sync wait", "sync wait", "sync held", "sync held"), events.map { it.stage })
        assertEquals("failed", events.last().state)
    }

    @Test fun existingProgressGetsDiagnosticsWithoutExtraVisibleRows() {
        val visible = mutableListOf<String>()
        val events = mutableListOf<SyncDiagnosticEvent>()
        val progress = DiagnosticSyncProgress(SyncDiagnostics({ events += it }, memory = { emptyMap() })) { visible += it }
        assertEquals(1, measureSyncStage("Stage", progress, { 0L }) { 1 })
        assertEquals(listOf("Stage", "Stage: 0.000 s"), visible)
        assertEquals(2, events.size)
    }
}
