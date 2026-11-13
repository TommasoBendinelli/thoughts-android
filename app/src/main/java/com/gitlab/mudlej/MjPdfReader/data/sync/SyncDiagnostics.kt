// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.os.Debug
import java.util.UUID

/** Small numeric samples only: no heap walks, document text, or exception messages. */
internal fun syncMemorySample(): Map<String, Long> {
    val runtime = Runtime.getRuntime()
    return mapOf(
        "java_heap_used_bytes" to runtime.totalMemory() - runtime.freeMemory(),
        "java_heap_committed_bytes" to runtime.totalMemory(),
        "java_heap_limit_bytes" to runtime.maxMemory(),
        "native_heap_allocated_bytes" to Debug.getNativeHeapAllocatedSize(),
    )
}

internal data class SyncDiagnosticEvent(
    val stage: String,
    val state: String,
    val values: Map<String, Any>,
    val occurredAtEpochMillis: Long = System.currentTimeMillis(),
)

/** Bounded background journal writes; instrumentation cannot fail the operation. */
internal class SyncDiagnostics(
    private val write: (SyncDiagnosticEvent) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val memory: () -> Map<String, Long> = ::syncMemorySample,
    private val logTimings: Boolean = false,
) {
    private val recorderId = UUID.randomUUID().toString()
    private var writeNanos = 0L
    private var writeCount = 0L

    constructor(context: Context, operationId: String?) : this(
        write = diagnosticWriter(context.applicationContext, operationId),
        logTimings = true,
    )

    internal data class Span(val id: String, val stage: String, val started: Long)

    fun begin(stage: String): Span {
        val id = UUID.randomUUID().toString()
        record(stage, "started", mapOf("span_id" to id) + sample())
        return Span(id, stage, clock())
    }

    fun end(span: Span, failed: Boolean = false) {
        val elapsed = (clock() - span.started).coerceAtLeast(0)
        // Stage names are fixed call-site codes; logcat makes Sync timing measurable on release builds.
        if (logTimings && elapsed >= 20_000_000) runCatching {
            android.util.Log.i("ThoughtsSyncTiming", "${span.stage} ${elapsed / 1_000_000} ms${if (failed) " failed" else ""}")
        }
        record(span.stage, if (failed) "failed" else "finished", mapOf(
            "span_id" to span.id,
            "duration_ns" to elapsed,
            "recorder_enqueue_ns" to writeNanos,
            "recorder_event_count" to writeCount,
        ) + sample())
    }

    fun count(stage: String, vararg values: Pair<String, Long>) = count(stage, false, *values)

    /** [log] also writes the counters to logcat, to explain a slow span on a release build. */
    fun count(stage: String, log: Boolean, vararg values: Pair<String, Long>) {
        if (log && logTimings && values.isNotEmpty()) runCatching {
            android.util.Log.i("ThoughtsSyncTiming", "$stage ${values.joinToString(" ") { (key, value) -> "$key=$value" }}")
        }
        record(stage, "counters", values.toMap())
    }

    // Reasons are fixed call-site codes, never exception text or server payloads.
    fun decision(stage: String, reason: String, vararg values: Pair<String, Long>) =
        record(stage, "decision", mapOf("reason" to reason.take(96)) + values.toMap())

    fun <T> measure(stage: String, block: () -> T): T {
        val span = begin(stage)
        try {
            return block().also { end(span) }
        } catch (error: Throwable) {
            end(span, failed = true)
            throw error
        }
    }

    suspend fun <T> measureSuspending(stage: String, block: suspend () -> T): T {
        val span = begin(stage)
        try {
            return block().also { end(span) }
        } catch (error: Throwable) {
            end(span, failed = true)
            throw error
        }
    }

    private fun sample(): Map<String, Long> = runCatching(memory).getOrDefault(emptyMap())

    private fun record(stage: String, state: String, values: Map<String, Any>) {
        val started = clock()
        runCatching { write(SyncDiagnosticEvent(stage.take(160), state, mapOf("recorder_id" to recorderId) + values)) }
        writeNanos += (clock() - started).coerceAtLeast(0)
        writeCount++
    }
}

/** Carries diagnostics through existing progress callbacks without adding visible rows. */
internal class DiagnosticSyncProgress(
    val diagnostics: SyncDiagnostics,
    private val visible: (String) -> Unit,
) : (String) -> Unit {
    override fun invoke(message: String) = visible(message)
}

internal fun syncDiagnosticProgress(context: Context, operationId: String?): DiagnosticSyncProgress {
    val store = SyncOperationStore(context)
    return DiagnosticSyncProgress(SyncDiagnostics(context, operationId)) { message ->
        store.append(operationId, SyncOperationStore.STATUS_SYNCING, message)
    }
}

/**
 * Progress for one item of a long loop. Its stage messages are dropped, since each one is a
 * durable operation-log write, but its diagnostic spans are kept.
 */
internal fun ((String) -> Unit)?.withoutStageMessages(): ((String) -> Unit)? =
    diagnostics()?.let { diagnostics -> DiagnosticSyncProgress(diagnostics) {} }

internal fun ((String) -> Unit)?.diagnostics(): SyncDiagnostics? =
    (this as? DiagnosticSyncProgress)?.diagnostics

internal fun <T> measureSyncDiagnostic(stage: String, progress: ((String) -> Unit)?, block: () -> T): T {
    val diagnostics = progress.diagnostics()
    return if (diagnostics != null) diagnostics.measure(stage, block) else block()
}

internal suspend fun <T> kotlinx.coroutines.sync.Mutex.withDiagnosticLock(
    diagnostics: SyncDiagnostics, stage: String, block: suspend () -> T,
): T {
    diagnostics.measureSuspending("$stage wait") { lock() }
    try {
        return diagnostics.measureSuspending("$stage held") { block() }
    } finally {
        unlock()
    }
}

private fun diagnosticWriter(context: Context, operationId: String?): (SyncDiagnosticEvent) -> Unit {
    if (operationId.isNullOrBlank()) return {}
    val store by lazy { SyncOperationStore(context) }
    val batcher = SyncDiagnosticBatcher { events -> store.appendDiagnostics(operationId, events) }
    return batcher::append
}
