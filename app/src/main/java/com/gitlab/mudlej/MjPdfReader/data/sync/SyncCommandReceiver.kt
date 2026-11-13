// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryScheduler
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class SyncCommandResult(
    @SerializedName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    val accepted: Boolean,
    val reused: Boolean = false,
    @SerializedName("operation_id") val operationId: String? = null,
    val scope: String,
    val target: String? = null,
    val state: String,
    val terminal: Boolean,
    val message: String = "",
    @SerializedName("build_id") val buildId: String? = null,
    @SerializedName("blocking_operation_id") val blockingOperationId: String? = null,
    @SerializedName("error_code") val errorCode: String? = null,
    /** Status only: the operation log with offsets, then phone diagnostic stages by total time. */
    val timings: List<String>? = null,
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal data class SyncCommandDisposition(
    val equivalent: SyncOperationRecord? = null,
    val blocker: SyncOperationRecord? = null,
)

internal fun isValidSyncCommandTarget(target: String?): Boolean = target == null || (
    target.isNotBlank() &&
        File(target).name == target &&
        !target.contains('/') &&
        !target.contains('\\') &&
        target.endsWith(".pdf", ignoreCase = true)
    )

internal fun selectSyncCommandDisposition(
    pending: List<SyncOperationRecord>,
    target: String?,
    forceRebuild: Boolean = false,
): SyncCommandDisposition {
    val ordered = pending
        .filter { isPendingSyncOperationStatus(it.status) }
        .sortedByDescending(SyncOperationRecord::updatedAtEpochMillis)
    val equivalent = ordered.firstOrNull { record ->
        !forceRebuild && record.kind == SyncOperationKind.SYNC && when {
            target == null -> record.target == null
            record.target == null -> true
            else -> record.target == target
        }
    }
    return SyncCommandDisposition(
        equivalent = equivalent,
        blocker = if (equivalent == null) ordered.firstOrNull() else null,
    )
}

internal fun syncCommandState(record: SyncOperationRecord): String = when (record.phase) {
    SyncRunPhase.QUEUED -> "queued"
    SyncRunPhase.WAITING_NETWORK, SyncRunPhase.WAITING_USER -> "waiting"
    SyncRunPhase.BUILDING -> "building"
    SyncRunPhase.SUCCEEDED -> "succeeded"
    SyncRunPhase.FAILED, SyncRunPhase.INTERRUPTED, SyncRunPhase.CANCELLED -> "failed"
    else -> "syncing"
}

internal fun syncCommandResult(
    record: SyncOperationRecord,
    reused: Boolean = false,
): SyncCommandResult {
    val state = syncCommandState(record)
    val message = record.displayMessage.ifBlank {
        record.journalEvents.lastOrNull()?.message
            ?: record.events.lastOrNull().orEmpty()
    }
    val buildId = record.buildProgress.lastOrNull { it.buildId.isNotBlank() }?.buildId
        ?: record.journalEvents.lastOrNull { it.buildId.isNotBlank() }?.buildId
    return SyncCommandResult(
        accepted = true,
        reused = reused,
        operationId = record.id,
        scope = if (record.target == null) "all" else "target",
        target = record.target,
        state = state,
        terminal = state == "succeeded" || state == "failed",
        message = message,
        buildId = buildId,
    )
}

/** Operation log lines with their offset from the start, then the slowest diagnostic stages. */
internal fun syncTimingSummary(record: SyncOperationRecord, diagnosticsJsonl: String): List<String> {
    val lines = record.events.mapIndexed { index, event ->
        val at = record.eventTimestampsEpochMillis.getOrNull(index)
        if (at == null) event else "+${(at - record.startedAtEpochMillis) / 100 / 10.0}s $event"
    }.takeLast(200)
    val totals = linkedMapOf<String, LongArray>()
    val counters = linkedMapOf<String, String>()
    diagnosticsJsonl.lineSequence().filter(String::isNotBlank).forEach { line ->
        runCatching {
            val entry = com.google.gson.JsonParser.parseString(line).asJsonObject
            val value = entry.get("value")
            val events = if (value.isJsonArray) value.asJsonArray else return@runCatching
            events.forEach { element ->
                val event = element.asJsonObject
                if (event.get("state")?.asString == "counters" && counters.size < 40) {
                    val stage = event.get("stage").asString
                    counters.putIfAbsent(stage, event.getAsJsonObject("values")?.entrySet()
                        ?.joinToString(" ") { (key, value) -> "$key=$value" }.orEmpty())
                }
                if (event.get("state")?.asString != "finished") return@forEach
                val nanos = event.getAsJsonObject("values")?.get("duration_ns")?.asLong ?: return@forEach
                // Per-document stages share one row.
                val stage = event.get("stage").asString.substringBefore(':')
                val total = totals.getOrPut(stage) { LongArray(2) }
                total[0] += nanos
                total[1]++
            }
        }
    }
    val stages = totals.entries.sortedByDescending { it.value[0] }.take(60).map { (stage, total) ->
        "stage $stage ${total[0] / 1_000_000} ms (${total[1]}x)"
    }
    return lines + stages + counters.map { (stage, values) -> "count $stage $values" }
}

class SyncCommandCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val operationStore = SyncOperationStore(appContext)

    fun start(
        target: String?,
        origin: ExplicitSyncOrigin = ExplicitSyncOrigin.USER_ACTION,
        forceRebuild: Boolean = false,
    ): SyncCommandResult {
        if (!isValidSyncCommandTarget(target) || (forceRebuild && target != null)) {
            return rejected(
                target,
                "invalid_request",
                "Target must be an exact PDF filename.",
            )
        }

        ThoughtsSyncScheduler.reconcileInterruptedOperations(appContext)
        val disposition = selectSyncCommandDisposition(operationStore.pendingOperations(), target, forceRebuild)
        disposition.equivalent?.let { existing ->
            if (existing.phase in setOf(SyncRunPhase.QUEUED, SyncRunPhase.WAITING_NETWORK)) {
                ThoughtsSyncScheduler.restartPendingOperation(appContext, existing, origin)
            } else if (origin == ExplicitSyncOrigin.USER_ACTION) {
                ThoughtsSyncScheduler.showRunningNotification(appContext)
                ThoughtsSyncForegroundService.start(appContext)
            }
            return syncCommandResult(
                operationStore.operation(existing.id) ?: existing,
                reused = true,
            )
        }
        disposition.blocker?.let { blocker ->
            return SyncCommandResult(
                accepted = false,
                operationId = null,
                scope = if (target == null) "all" else "target",
                target = target,
                state = "busy",
                terminal = false,
                message = "Another Thoughts sync or build is already active.",
                blockingOperationId = blocker.id,
                errorCode = "busy",
            )
        }

        if (ThoughtsServerSecretStore(appContext).config() == null) {
            return rejected(target, "not_configured", "Thoughts server is not configured.")
        }
        if (!ThoughtsNetworkPolicy.requiredVpnActive(appContext)) {
            PublicServerMigration.startInBackground(appContext)
            return rejected(target, "transport_unavailable", "No active VPN transport was found.")
        }

        val recordingStarted = SystemClock.elapsedRealtimeNanos()
        val operationId = operationStore.begin(target, building = forceRebuild)
        val diagnostics = SyncDiagnostics(appContext, operationId)
        diagnostics.count("sync_record_operation", "duration_ns" to
            (SystemClock.elapsedRealtimeNanos() - recordingStarted).coerceAtLeast(0))
        return try {
            diagnostics.measure("sync_prepare_scheduler") {
                if (forceRebuild) PhoneRenderQueue().enqueueAll()
                CommentDeliveryScheduler.schedule(
                    appContext,
                    explicit = true,
                    targetPdfFilename = target,
                )
                ThoughtsSyncScheduler.enqueue(
                    appContext,
                    targetPdfFilename = target,
                    preparedOperationId = operationId,
                    building = forceRebuild,
                    origin = origin,
                )
                operationStore.operation(operationId)?.let(::syncCommandResult)
                    ?: rejected(target, "operation_unavailable", "Sync operation was not recorded.")
            }
        } catch (error: Throwable) {
            operationStore.fail(
                operationId,
                SyncOperationStore.USER_MESSAGE_INTERRUPTED,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
            operationStore.operation(operationId)?.let(::syncCommandResult)
                ?: rejected(target, "start_failed", "Sync could not be started.")
        }
    }

    fun status(operationId: String?): SyncCommandResult {
        if (operationId.isNullOrBlank()) {
            return rejected(null, "invalid_request", "An operation ID is required.")
        }
        return operationStore.operationWithEvents(operationId)?.let { record ->
            // Summarizing reads the whole diagnostic archive; do it once the run has finished,
            // not on every poll while it is still running.
            syncCommandResult(record).let { result ->
                if (!result.terminal) result
                else result.copy(timings = syncTimingSummary(record, SyncDiagnosticArchive(appContext).read(record.id)))
            }
        } ?: rejected(null, "unknown_operation", "Sync operation was not found.")
    }

    private fun rejected(target: String?, code: String, message: String) = SyncCommandResult(
        accepted = false,
        scope = if (target == null) "all" else "target",
        target = target,
        state = "failed",
        terminal = true,
        message = message,
        errorCode = code,
    )
}

/**
 * Sync commands from adb (tools/thoughts-mobilectl). The manifest lets only senders holding
 * android.permission.DUMP reach it (the adb shell; ordinary apps cannot hold it). A sender that
 * Android names (Android 14, shared identity) must also pass the agent API's caller rule.
 */
class SyncCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val refusal = senderRefusal(context)
        if (refusal != null) {
            val json = SyncCommandResult(
                accepted = false,
                scope = "all",
                state = "failed",
                terminal = true,
                message = refusal,
                errorCode = "not_allowed",
            ).toJson()
            resultCode = RESULT_FAILED
            resultData = json
            setResultExtras(Bundle().apply { putString(EXTRA_RESULT_JSON, json) })
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching {
                val coordinator = SyncCommandCoordinator(context)
                when (intent.action) {
                    ACTION_SYNC_NOW -> coordinator.start(
                        intent.getStringExtra(EXTRA_TARGET),
                        origin = ExplicitSyncOrigin.BACKGROUND_COMMAND,
                    )
                    ACTION_SYNC_STATUS -> coordinator.status(intent.getStringExtra(EXTRA_OPERATION_ID))
                    else -> SyncCommandResult(
                        accepted = false,
                        scope = "all",
                        state = "failed",
                        terminal = true,
                        message = "Unknown Thoughts sync command.",
                        errorCode = "invalid_request",
                    )
                }
            }.getOrElse { error ->
                SyncCommandResult(
                    accepted = false,
                    scope = "all",
                    state = "failed",
                    terminal = true,
                    message = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                    errorCode = "command_failed",
                )
            }
            val json = result.toJson()
            pending.setResultCode(result.broadcastResultCode())
            pending.setResultData(json)
            pending.setResultExtras(Bundle().apply { putString(EXTRA_RESULT_JSON, json) })
            pending.finish()
        }
    }

    /** Why the sender may not send commands, or null when it may. */
    private fun senderRefusal(context: Context): String? {
        val sender = if (android.os.Build.VERSION.SDK_INT >= 34) sentFromUid else -1
        if (sender < 0) return null // Not named: Android has checked the manifest permission.
        return runCatching { org.tbend.agent.AgentCallers.check(context, sender) }.exceptionOrNull()
            ?.let { it.message ?: "This app may not send Thoughts sync commands." }
    }

    private fun SyncCommandResult.broadcastResultCode(): Int = when {
        accepted -> RESULT_ACCEPTED
        errorCode == "busy" -> RESULT_BUSY
        errorCode == "invalid_request" || errorCode == "unknown_operation" -> RESULT_INVALID
        errorCode == "not_configured" || errorCode == "transport_unavailable" -> RESULT_UNAVAILABLE
        else -> RESULT_FAILED
    }

    companion object {
        const val ACTION_SYNC_NOW = "org.tbend.thoughts.action.SYNC_NOW"
        const val ACTION_SYNC_STATUS = "org.tbend.thoughts.action.SYNC_STATUS"
        const val EXTRA_TARGET = "target"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val EXTRA_RESULT_JSON = "result_json"

        const val RESULT_ACCEPTED = -1
        const val RESULT_INVALID = 2
        const val RESULT_UNAVAILABLE = 3
        const val RESULT_FAILED = 4
        const val RESULT_BUSY = 6
    }
}
