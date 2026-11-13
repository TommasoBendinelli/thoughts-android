// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Whether someone is typing in a source editor right now. Background maintenance
 * (post-Sync source cleanup) waits for a pause and yields while it lasts, so it never
 * competes with the editor for CPU, memory bandwidth and GC.
 *
 * Editing means a source editor is in the foreground and received input (a key, touch or
 * text change) within [activeWindowNanos]. An editor left open without input is idle.
 */
internal class SourceEditingGate(
    private val clock: () -> Long = System::nanoTime,
    private val activeWindowNanos: Long = ACTIVE_WINDOW_NANOS,
) {
    private val foregroundEditors = AtomicInteger()
    @Volatile private var lastInputNanos = 0L
    @Volatile private var anyInput = false

    fun editorResumed() {
        foregroundEditors.incrementAndGet()
        noteInput()
    }

    fun editorPaused() {
        foregroundEditors.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun noteInput() {
        lastInputNanos = clock()
        anyInput = true
    }

    fun isEditing(): Boolean =
        foregroundEditors.get() > 0 && anyInput && clock() - lastInputNanos < activeWindowNanos

    /**
     * Suspends until nobody is editing, for at most [maxWaitNanos]. Returns false when it
     * gave up waiting; the caller then proceeds at background priority with yields.
     */
    suspend fun awaitNotEditing(
        maxWaitNanos: Long = MAX_DEFERRAL_NANOS,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        val started = clock()
        while (isEditing()) {
            if (clock() - started >= maxWaitNanos) return false
            pause(POLL_MILLIS)
        }
        return true
    }

    /** Called between units of background work while holding no UI resources. */
    fun yieldWhileEditing(sleep: (Long) -> Unit = Thread::sleep) {
        if (isEditing()) sleep(YIELD_MILLIS)
    }

    companion object {
        private const val ACTIVE_WINDOW_NANOS = 20_000_000_000L
        private const val MAX_DEFERRAL_NANOS = 10L * 60 * 1_000_000_000L
        private const val POLL_MILLIS = 1_000L
        private const val YIELD_MILLIS = 30L

        /** The process-wide gate the editors report to. */
        val shared = SourceEditingGate()
    }
}
