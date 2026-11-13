// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** At most 128 pending events per writer and 100 ms before dispatch under normal load. */
internal class SyncDiagnosticBatcher(private val persist: (List<SyncDiagnosticEvent>) -> Unit) {
    private val pending = mutableListOf<SyncDiagnosticEvent>()
    private var scheduled = false
    private var previousBatchNanos = 0L
    private var persistedEvents = 0L

    @Synchronized
    fun append(event: SyncDiagnosticEvent) {
        if (pending.size < 127) {
            pending += event
        } else {
            val dropped = if (pending.size == 128) (pending.last().values["dropped_events"] as Long) + 1 else 1L
            val marker = SyncDiagnosticEvent("diagnostic_buffer_limit", "limit_reached", mapOf("dropped_events" to dropped))
            if (pending.size == 128) pending[127] = marker else pending += marker
        }
        if (!scheduled) {
            scheduled = true
            active.add(this)
            executor.schedule({ drain() }, 100, TimeUnit.MILLISECONDS)
        }
    }

    private fun drain() {
        val batch = synchronized(this) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            scheduled = false
            active.remove(this)
            copy
        }
        val started = System.nanoTime()
        runCatching {
            persist(batch.map { it.copy(values = it.values + mapOf(
                "previous_batch_persist_ns" to previousBatchNanos,
                "previously_persisted_events" to persistedEvents,
            )) })
        }
        previousBatchNanos = System.nanoTime() - started
        persistedEvents += batch.size
    }

    companion object {
        private val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ThoughtsSyncDiagnostics").apply { isDaemon = true }
        }
        private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<SyncDiagnosticBatcher>()

        /** Call off the UI thread at completion/export; never changes a sync result on failure. */
        fun flushAll() {
            runCatching { executor.submit { active.toList().forEach { it.drain() } }.get(5, TimeUnit.SECONDS) }
        }
    }
}
