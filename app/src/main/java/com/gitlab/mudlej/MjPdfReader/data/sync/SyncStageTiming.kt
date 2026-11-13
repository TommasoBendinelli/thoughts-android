// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.util.Locale

/** Durations use a monotonic clock; logging failures must not change sync results. */
internal fun syncStageDuration(label: String, startedNanos: Long, nowNanos: Long = System.nanoTime()): String =
    String.format(Locale.ROOT, "%s: %.3f s", label, (nowNanos - startedNanos) / 1_000_000_000.0)

internal inline fun <T> measureSyncStage(
    label: String,
    noinline emit: ((String) -> Unit)?,
    clock: () -> Long = System::nanoTime,
    block: () -> T,
): T {
    if (emit == null) return block()
    runCatching { emit(label) }
    val diagnosticSpan = runCatching { emit.diagnostics()?.begin(label) }.getOrNull()
    val started = clock()
    try {
        val result = block()
        runCatching { diagnosticSpan?.let { emit.diagnostics()?.end(it) } }
        runCatching { emit(syncStageDuration(label, started, clock())) }
        return result
    } catch (error: Throwable) {
        runCatching { diagnosticSpan?.let { emit.diagnostics()?.end(it, failed = true) } }
        runCatching { emit(syncStageDuration("$label failed after", started, clock())) }
        throw error
    }
}
