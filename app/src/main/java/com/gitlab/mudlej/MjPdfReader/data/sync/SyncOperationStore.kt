// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.tbend.thoughts.sdk.TraceEventRecord

enum class SyncOperationKind {
    SYNC,
    BUILD,
}

data class SyncOperationRecord(
    val id: String,
    val target: String?,
    val kind: SyncOperationKind,
    val status: String,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val events: List<String>,
    val eventTimestampsEpochMillis: List<Long> = emptyList(),
    val displayMessage: String = "",
    val buildProgress: List<SyncBuildProgress> = emptyList(),
    val buildCheckedAtEpochMillis: Long = 0,
    val retryOf: String? = null,
    val phase: String = SyncRunPhase.QUEUED,
    val attempt: Int = 0,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val transferMetricsAvailable: Boolean = false,
    val uploadBytesCompleted: Long = 0,
    val uploadBytesTotal: Long = 0,
    val downloadBytesCompleted: Long = 0,
    val downloadBytesTotal: Long = 0,
    val transferPlanId: String = "",
    val planJson: String = "",
    val schedulerKind: String = "",
    val schedulerId: String = "",
    val serverState: String = "",
    val serverTerminal: Boolean = false,
    val lastServerSequence: Long = 0,
    val streamState: String = "offline",
    val legacyEventsImported: Boolean = false,
    val journalEvents: List<SyncJournalEvent> = emptyList(),
)

data class SyncJournalEvent(
    val id: Long,
    val origin: String,
    val serverSequence: Long?,
    val occurredAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val component: String,
    val phase: String,
    val state: String,
    val buildId: String,
    val message: String,
    val completed: Long?,
    val total: Long?,
    val bytesCompleted: Long?,
    val bytesTotal: Long?,
    val errorCode: String,
    val metadataJson: String,
)

object SyncRunPhase {
    const val QUEUED = "queued"
    const val PREFLIGHT = "preflight"
    const val UPLOADING = "uploading"
    const val WAITING_NETWORK = "waiting_network"
    const val WAITING_USER = "waiting_user"
    const val BUILDING = "building"
    const val DOWNLOADING = "downloading"
    const val VERIFYING = "verifying"
    const val ACTIVATING = "activating"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val INTERRUPTED = "interrupted"
    const val CANCELLED = "cancelled"
}

internal fun syncRunPhase(status: String, event: String = ""): String {
    val detail = event.lowercase()
    return when {
        status == SyncOperationStore.STATUS_SYNCED -> SyncRunPhase.SUCCEEDED
        status == SyncOperationStore.STATUS_ATTENTION -> SyncRunPhase.FAILED
        status == SyncOperationStore.STATUS_ERROR && "interrupt" in detail ->
            SyncRunPhase.INTERRUPTED
        status == SyncOperationStore.STATUS_ERROR -> SyncRunPhase.FAILED
        status == SyncOperationStore.STATUS_BUILDING || "build" in detail -> SyncRunPhase.BUILDING
        status == SyncOperationStore.STATUS_WAITING &&
            ("approval" in detail || "mobile data" in detail || "metered" in detail) ->
            SyncRunPhase.WAITING_USER
        status == SyncOperationStore.STATUS_WAITING || "network" in detail ->
            SyncRunPhase.WAITING_NETWORK
        "preflight" in detail || "preparing" in detail -> SyncRunPhase.PREFLIGHT
        "upload" in detail || "publish" in detail || "pending changes" in detail ->
            SyncRunPhase.UPLOADING
        "verif" in detail -> SyncRunPhase.VERIFYING
        "activat" in detail || "install" in detail -> SyncRunPhase.ACTIVATING
        "artifact" in detail || "download" in detail || "transfer" in detail ->
            SyncRunPhase.DOWNLOADING
        else -> SyncRunPhase.QUEUED
    }
}

internal fun nextSyncRunPhase(record: SyncOperationRecord, status: String, event: String): String {
    val inferred = syncRunPhase(status, event)
    return if (inferred == SyncRunPhase.QUEUED && status != SyncOperationStore.STATUS_QUEUED) {
        record.phase
    } else {
        inferred
    }
}

internal fun directionalBytes(first: Long, second: Long): Long =
    if (Long.MAX_VALUE - first.coerceAtLeast(0) < second.coerceAtLeast(0)) {
        Long.MAX_VALUE
    } else {
        first.coerceAtLeast(0) + second.coerceAtLeast(0)
    }

data class SyncProjectFailure(val filename: String, val reason: String)

data class SyncBuildProgress(
    val buildId: String,
    val target: String,
    val serverStatus: String,
    val phase: String,
    val completed: Int,
    val total: Int,
    val unit: String,
    val detail: String,
    val documents: List<String> = emptyList(),
    val failedDocuments: List<SyncProjectFailure>? = null,
    val logReference: String? = null,
    val workKind: String? = null,
)

data class SyncOperationEventLine(
    val message: String,
    val timestampEpochMillis: Long,
)

internal fun syncOperationEventLines(record: SyncOperationRecord): List<SyncOperationEventLine> =
    record.events.mapIndexed { index, storedEvent ->
        val storedTimestamp = record.eventTimestampsEpochMillis.getOrNull(index)?.takeIf { it > 0 }
        val legacy = if (storedTimestamp == null) parseLegacySyncOperationEvent(storedEvent) else null
        SyncOperationEventLine(
            message = legacy?.first ?: storedEvent,
            timestampEpochMillis = storedTimestamp ?: legacy?.second ?: 0,
        )
    }

internal fun appendTimestampedSyncOperationEvent(
    record: SyncOperationRecord,
    event: String,
    timestampEpochMillis: Long,
    maxEvents: Int,
): SyncOperationRecord {
    val alignedTimestamps = List(record.events.size) { index ->
        record.eventTimestampsEpochMillis.getOrNull(index) ?: 0L
    }
    return record.copy(
        events = (record.events + event).takeLast(maxEvents),
        eventTimestampsEpochMillis =
            (alignedTimestamps + timestampEpochMillis).takeLast(maxEvents),
    )
}

internal fun preservesTerminalSourceAttention(currentStatus: String, requestedStatus: String): Boolean =
    currentStatus == SyncOperationStore.STATUS_ATTENTION &&
        requestedStatus != SyncOperationStore.STATUS_ATTENTION

internal fun isTerminalPdfBuildDisplayMessage(message: String?): Boolean = message in setOf(
    SyncOperationStore.USER_MESSAGE_PDF_BUILD_ATTENTION_TEX_SAVED,
    SyncOperationStore.USER_MESSAGE_PDF_BUILD_CANCELLED_TEX_SAVED,
    SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_TEX_SAVED,
)

internal fun isTerminalPdfBuildFailure(record: SyncOperationRecord): Boolean =
    record.status == SyncOperationStore.STATUS_ERROR && (
        record.journalEvents.lastOrNull { it.origin == "phone" && it.errorCode.isNotBlank() }?.errorCode == "pdf_build_failed" ||
        isPdfBuildFailedSyncedDisplayMessage(record.displayMessage) ||
        isTerminalPdfBuildDisplayMessage(record.displayMessage)
    )

/** The changes reached the server and only the PDF build failed, in the current or the saved older wording. */
internal fun isPdfBuildFailedSyncedDisplayMessage(message: String?): Boolean =
    message == SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED ||
        message == SyncOperationStore.LEGACY_USER_MESSAGE_PDF_BUILD_FAILED_SYNCED

internal fun completeSyncWithBuildFailure(record: SyncOperationRecord, now: Long): SyncOperationRecord =
    appendTimestampedSyncOperationEvent(record,
        SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED, now, 100).copy(
        status = SyncOperationStore.STATUS_ERROR,
        phase = SyncRunPhase.FAILED,
        updatedAtEpochMillis = now,
        displayMessage = SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED,
    )

internal fun recordSyncBuildProgress(
    record: SyncOperationRecord,
    progress: List<SyncBuildProgress>,
    checkedAtEpochMillis: Long,
    buildsActive: Boolean,
): SyncOperationRecord {
    if (isTerminalSyncOperationStatus(record.status)) {
        return record.copy(
            updatedAtEpochMillis = checkedAtEpochMillis,
            buildProgress = progress,
            buildCheckedAtEpochMillis = checkedAtEpochMillis,
        )
    }
    val eventLines = syncOperationEventLines(record)
    val queuedAt = eventLines
        .firstOrNull { it.message == SyncOperationStore.STATUS_QUEUED }
        ?.timestampEpochMillis
        ?.takeIf { it > 0 }
        ?: record.startedAtEpochMillis
    val completed = eventLines.firstOrNull { it.message == BUILD_COMPLETED_EVENT }
    val events = buildList {
        add(SyncOperationStore.STATUS_QUEUED)
        add(BUILDING_EVENT)
        completed?.let { add(BUILD_COMPLETED_EVENT) }
    }
    val timestamps = buildList {
        add(queuedAt)
        add(checkedAtEpochMillis)
        completed?.let { add(it.timestampEpochMillis) }
    }
    return record.copy(
        status = if (buildsActive) SyncOperationStore.STATUS_BUILDING else record.status,
        phase = if (buildsActive) SyncRunPhase.BUILDING else record.phase,
        updatedAtEpochMillis = checkedAtEpochMillis,
        events = events,
        eventTimestampsEpochMillis = timestamps,
        buildProgress = progress,
        buildCheckedAtEpochMillis = checkedAtEpochMillis,
    )
}

/** How many PDFs were still building on the server when [record] ended (0 unless it ended synced). */
internal fun pdfsStillBuildingAfter(record: SyncOperationRecord?): Int =
    if (record == null || record.status != SyncOperationStore.STATUS_SYNCED) 0
    else pendingFedoraPdfCount(pendingFedoraBuildsAfter(emptyList(), record.buildProgress, wholeLibrary = true))

internal fun completeSyncBuildOperation(
    record: SyncOperationRecord,
    completedAtEpochMillis: Long,
): SyncOperationRecord {
    val eventLines = syncOperationEventLines(record)
    val queuedAt = eventLines
        .firstOrNull { it.message == SyncOperationStore.STATUS_QUEUED }
        ?.timestampEpochMillis
        ?.takeIf { it > 0 }
        ?: record.startedAtEpochMillis
    val building = eventLines.lastOrNull { it.message == BUILDING_EVENT }
    val events = buildList {
        add(SyncOperationStore.STATUS_QUEUED)
        if (building != null || record.buildProgress.isNotEmpty()) add(BUILDING_EVENT)
        add(BUILD_COMPLETED_EVENT)
    }
    val timestamps = buildList {
        add(queuedAt)
        if (building != null || record.buildProgress.isNotEmpty()) {
            add(
                record.buildCheckedAtEpochMillis.takeIf { it > 0 }
                    ?: building?.timestampEpochMillis?.takeIf { it > 0 }
                    ?: completedAtEpochMillis,
            )
        }
        add(completedAtEpochMillis)
    }
    return record.copy(
        status = SyncOperationStore.STATUS_SYNCED,
        phase = SyncRunPhase.SUCCEEDED,
        updatedAtEpochMillis = completedAtEpochMillis,
        events = events,
        eventTimestampsEpochMillis = timestamps,
        displayMessage = "",
    )
}

private const val BUILDING_EVENT = "Building"
private const val BUILD_COMPLETED_EVENT = "Completed"

private fun parseLegacySyncOperationEvent(event: String): Pair<String, Long>? {
    val match = LEGACY_EVENT_TIMESTAMP.matchEntire(event) ?: return null
    val timestamp = runCatching { Instant.parse(match.groupValues[2]).toEpochMilli() }.getOrNull()
        ?: return null
    return match.groupValues[1] to timestamp
}

private val LEGACY_EVENT_TIMESTAMP = Regex("^(.*) (\\d{4}-\\d{2}-\\d{2}T[^ ]+Z)$")

internal fun storedSyncOperationKind(
    storedKind: String,
    status: String,
    events: List<String>,
): SyncOperationKind = when (storedKind.lowercase()) {
    "build" -> SyncOperationKind.BUILD
    "sync" -> SyncOperationKind.SYNC
    else -> if (
        status == SyncOperationStore.STATUS_BUILDING ||
        events.firstOrNull()?.startsWith(SyncOperationStore.STATUS_BUILDING) == true
    ) {
        SyncOperationKind.BUILD
    } else {
        SyncOperationKind.SYNC
    }
}

internal fun activeGlobalSyncOperation(
    records: List<SyncOperationRecord>,
): SyncOperationRecord? = latestGlobalSyncOperation(records)
    ?.takeIf { it.status == SyncOperationStore.STATUS_SYNCING || it.status == SyncOperationStore.STATUS_BUILDING }

internal fun activeTargetSyncOperation(
    records: List<SyncOperationRecord>,
    target: String,
): SyncOperationRecord? = latestTargetSyncOperation(records, target)
    ?.takeIf { it.status == SyncOperationStore.STATUS_SYNCING || it.status == SyncOperationStore.STATUS_BUILDING }

internal fun latestGlobalSyncOperation(
    records: List<SyncOperationRecord>,
): SyncOperationRecord? = records
    .filter { it.target == null }
    .maxByOrNull { it.updatedAtEpochMillis }

internal fun isPendingSyncOperationStatus(status: String): Boolean = status in setOf(
    SyncOperationStore.STATUS_SYNCING,
    SyncOperationStore.STATUS_BUILDING,
    SyncOperationStore.STATUS_WAITING,
    SyncOperationStore.STATUS_QUEUED,
    SyncOperationStore.STATUS_RETRYING,
)

internal fun isTerminalSyncOperationStatus(status: String): Boolean = status in setOf(
    SyncOperationStore.STATUS_SYNCED,
    SyncOperationStore.STATUS_ATTENTION,
    SyncOperationStore.STATUS_ERROR,
)

internal fun pendingGlobalSyncOperation(
    records: List<SyncOperationRecord>,
): SyncOperationRecord? = latestGlobalSyncOperation(records)
    ?.takeIf { isPendingSyncOperationStatus(it.status) }

internal fun pendingTargetSyncOperation(
    records: List<SyncOperationRecord>,
    target: String,
): SyncOperationRecord? = latestTargetSyncOperation(records, target)
    ?.takeIf { isPendingSyncOperationStatus(it.status) }

internal fun latestTargetSyncOperation(
    records: List<SyncOperationRecord>,
    target: String,
): SyncOperationRecord? = records
    .filter { it.target == target }
    .maxByOrNull { it.updatedAtEpochMillis }

internal fun lastSuccessfulDocumentSyncAt(
    records: List<SyncOperationRecord>,
    target: String,
): Long = records
    .asSequence()
    .filter { record ->
        record.status == SyncOperationStore.STATUS_SYNCED &&
            (record.target == null || record.target == target)
    }
    .maxOfOrNull(SyncOperationRecord::updatedAtEpochMillis)
    ?: 0L

internal fun initialSyncOperationStatus(): String = SyncOperationStore.STATUS_QUEUED

internal fun shouldMarkSyncOperationInterrupted(
    record: SyncOperationRecord,
    nowEpochMillis: Long,
    hasExecutionCounterpart: Boolean,
    graceMillis: Long = SyncOperationStore.INTERRUPTION_GRACE_MILLIS,
): Boolean = isPendingSyncOperationStatus(record.status) &&
    nowEpochMillis - record.updatedAtEpochMillis >= graceMillis &&
    !hasExecutionCounterpart

internal fun nextSyncOperationReconciliationDelayMillis(
    records: List<SyncOperationRecord>,
    nowEpochMillis: Long,
    graceMillis: Long = SyncOperationStore.INTERRUPTION_GRACE_MILLIS,
): Long? = records
    .asSequence()
    .filter { isPendingSyncOperationStatus(it.status) }
    .minOfOrNull { record ->
        (graceMillis - (nowEpochMillis - record.updatedAtEpochMillis)).coerceAtLeast(0L)
    }

private data class LocalSyncTransition(
    val phase: String,
    val status: String,
    val message: String,
    val errorCode: String = "",
    val persistStep: Boolean = true,
)

class SyncOperationStore(
    context: Context,
    databaseOverride: AppDatabase? = null,
) {
    private val appContext = context.applicationContext
    // Read only by the one-time import below, never where the store is created.
    private val preferences by lazy { appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    private val gson = Gson()
    private val database = databaseOverride ?: AppDatabase.getInstance(appContext)
    private val dao = database.thoughtsSyncDao()

    init {
        // Creating a store imported the Sync history of older versions on the spot: a preferences
        // file and a database query on whichever thread created it, the main thread at start.
        // The import now runs once per process and database on its own thread; the methods that
        // read or change the history wait for it ([awaitLegacyImport]), so they see what they saw.
        LEGACY_IMPORT.start(database) {
            ensureLegacyImported()
            ensureRoomEventsImported()
        }
    }

    private fun awaitLegacyImport() = LEGACY_IMPORT.await(database)

    @Synchronized
    fun begin(target: String?, building: Boolean): String {
        return beginRecord(target, building, retryOf = null)
    }

    @Synchronized
    fun beginRetry(previousId: String): String {
        val previous = operation(previousId)
            ?: error("The sync operation to retry no longer exists")
        return beginRecord(
            target = previous.target,
            building = previous.kind == SyncOperationKind.BUILD,
            retryOf = previous.id,
        )
    }

    private fun beginRecord(target: String?, building: Boolean, retryOf: String?): String {
        awaitLegacyImport()
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        // The request is only syncing after its worker or JobService enters the
        // network phase.  Until then it is safely queued on this phone.
        val status = initialSyncOperationStatus()
        val record = SyncOperationRecord(
            id = id,
            target = target,
            kind = if (building) SyncOperationKind.BUILD else SyncOperationKind.SYNC,
            status = status,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            events = listOf(status),
            eventTimestampsEpochMillis = listOf(now),
            displayMessage = "",
            retryOf = retryOf,
            phase = SyncRunPhase.QUEUED,
            legacyEventsImported = true,
        )
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                dao.putSyncRun(runEntity(record))
                SyncDiagnosticArchive(appContext).append(id, "run_started", record)
                val transition = LocalSyncTransition(
                    phase = SyncRunPhase.QUEUED,
                    status = status,
                    message = retryOf?.let { "Retry of $it" } ?: status,
                )
                dao.appendSyncEvent(localEventEntity(record, transition, now))
                dao.putSyncStep(stepEntity(record, transition.phase, transition.status, now))
                dao.pruneSyncRuns(now - RETENTION_MILLIS)
                dao.pruneDiagnostics(now - RETENTION_MILLIS)
            }
        }
        broadcastChanged(id)
        return id
    }

    @Synchronized
    fun append(
        id: String?,
        status: String,
        event: String,
        displayMessage: String? = null,
        errorCode: String = "",
    ) {
        if (id.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (!id.isNullOrBlank()) SyncDiagnosticArchive(appContext).append(id, "phone_message", event)
        val safeEvent = event.lineSequence().joinToString(" ") { it.trim() }.trim().take(600)
        val changed = updateOperation(
            id = id,
            now = now,
            skipTransitionWhenUnchanged = true,
            transform = { record ->
                val linkingCount = if (status == STATUS_SYNCED) unresolvedLinkingCount(dao.linkingStatusPayloads(), record.target) else 0
                if (isTerminalSyncOperationStatus(record.status) && !(
                        isTerminalPdfBuildFailure(record) && status in setOf(STATUS_ERROR, STATUS_ATTENTION) &&
                            !isTerminalPdfBuildDisplayMessage(displayMessage)
                    )) {
                    record
                } else if (status == STATUS_SYNCED && dao.blockedOperationCount() > 0) {
                    appendTimestampedSyncOperationEvent(record, "Some local changes could not be synchronized.", now, MAX_EVENTS).copy(
                        status = STATUS_ERROR, phase = SyncRunPhase.FAILED, updatedAtEpochMillis = now,
                        displayMessage = "Sync failed. Some local changes could not be synchronized.",
                    )
                } else if (status == STATUS_SYNCED && dao.attachmentCardsNotSentSince(record.startedAtEpochMillis) > 0) {
                    val count = dao.attachmentCardsNotSentSince(record.startedAtEpochMillis)
                    val message = if (count == 1) "Synced; 1 review card was not sent: the server does not know the version of the " +
                        "HTML page it was made in. It stays on this phone."
                    else "Synced; $count review cards were not sent: the server does not know the version of the HTML pages " +
                        "they were made in. They stay on this phone."
                    appendTimestampedSyncOperationEvent(record, message, now, MAX_EVENTS).copy(
                        status = STATUS_SYNCED, phase = SyncRunPhase.SUCCEEDED, updatedAtEpochMillis = now,
                        displayMessage = message,
                    )
                } else if (status == STATUS_SYNCED && dao.pendingPdfBuildEvent(record.id) != null) {
                    completeSyncWithBuildFailure(record, now)
                } else if (status == STATUS_SYNCED && linkingCount > 0) {
                    val count = linkingCount
                    val message = if (count == 1) "Synced; 1 comment needs linking" else "Synced; $count comments need linking"
                    appendTimestampedSyncOperationEvent(record, message, now, MAX_EVENTS).copy(
                        status = STATUS_SYNCED, phase = SyncRunPhase.SUCCEEDED, updatedAtEpochMillis = now,
                        displayMessage = "",
                    )
                } else if (
                    status == STATUS_SYNCING &&
                    (record.kind == SyncOperationKind.BUILD || record.buildCheckedAtEpochMillis > 0)
                ) {
                    // A build still running on Fedora does not make the phone's work "Building":
                    // Sync no longer waits for it (RemoteWorkWatch fetches its PDFs when it is done).
                    appendTimestampedSyncOperationEvent(record, safeEvent, now, MAX_EVENTS).copy(
                        status = STATUS_SYNCING,
                        updatedAtEpochMillis = now,
                        displayMessage = "",
                        phase = nextSyncRunPhase(record, status, safeEvent),
                    )
                } else if (
                    (record.kind == SyncOperationKind.BUILD || record.buildCheckedAtEpochMillis > 0) &&
                    status == STATUS_SYNCED &&
                    safeEvent == BUILD_COMPLETED_EVENT
                ) {
                    completeSyncBuildOperation(record, now)
                } else {
                    appendTimestampedSyncOperationEvent(
                        record = record,
                        event = safeEvent,
                        timestampEpochMillis = now,
                        maxEvents = MAX_EVENTS,
                    ).copy(
                        status = status,
                        updatedAtEpochMillis = now,
                        phase = nextSyncRunPhase(record, status, safeEvent),
                        displayMessage = if (status in setOf(STATUS_ERROR, STATUS_ATTENTION)) {
                            displayMessage?.sanitizeDisplayMessage()
                                ?: record.displayMessage.ifBlank { USER_MESSAGE_SYNC_FAILED }
                        } else {
                            ""
                        },
                    )
                }
            },
            transition = { record ->
                LocalSyncTransition(
                    phase = record.phase,
                    status = record.status,
                    message = if (status == STATUS_SYNCED && isTerminalPdfBuildFailure(record)) record.displayMessage else safeEvent,
                    errorCode = when {
                        status == STATUS_SYNCED && isTerminalPdfBuildFailure(record) -> "pdf_build_failed"
                        errorCode.isNotBlank() -> errorCode
                        record.status == STATUS_ERROR -> "sync_failed"
                        record.status == STATUS_ATTENTION -> "source_attention"
                        else -> ""
                    },
                )
            },
        )
        if (changed == null) return
        broadcastChanged(id)
    }

    /**
     * Ends a Sync: "Completed", or "Completed; 2 PDFs still building on the server" when the server was still
     * building [pdfsStillBuilding] PDFs; Sync does not wait for them.
     */
    fun completeSync(id: String?, pdfsStillBuilding: Int) = append(
        id,
        STATUS_SYNCED,
        if (pdfsStillBuilding > 0) "$BUILD_COMPLETED_EVENT; ${pdfsStillBuildingMessage(pdfsStillBuilding)}"
        else BUILD_COMPLETED_EVENT,
    )

    @Synchronized
    fun recordTransientRetry(id: String?, attempt: Int, delayMillis: Long, detail: String) {
        if (id.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val event = "Transient failure; retry $attempt in ${delayMillis}ms: ${detail.take(300)}"
        updateOperation(
            id = id,
            now = now,
            skipTransitionWhenUnchanged = true,
            transform = { record ->
                if (isTerminalSyncOperationStatus(record.status)) {
                    record
                } else {
                    appendTimestampedSyncOperationEvent(record, event, now, MAX_EVENTS).copy(
                        status = STATUS_QUEUED,
                        updatedAtEpochMillis = now,
                        attempt = attempt,
                        displayMessage = "",
                    )
                }
            },
            transition = { record ->
                if (isTerminalSyncOperationStatus(record.status)) {
                    null
                } else {
                    LocalSyncTransition(record.phase, STATUS_QUEUED, event)
                }
            },
        ) ?: return
        broadcastChanged(id)
    }

    @Synchronized
    fun recordTransfer(id: String?, plan: ArtifactSyncPlan, completed: Boolean) {
        if (id.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val total = plan.estimatedTransferBytes.coerceAtLeast(0L)
        val phase = if (completed) SyncRunPhase.ACTIVATING else SyncRunPhase.DOWNLOADING
        val message = if (completed) "Artifacts verified and activated" else "Artifact transfer planned"
        updateOperation(
            id = id,
            now = now,
            skipTransitionWhenUnchanged = true,
            transform = { record ->
                val legacyTransferred = if (completed) total else
                    record.transferredBytes.coerceAtMost(total)
                record.copy(
                    transferredBytes = if (record.transferMetricsAvailable) {
                        directionalBytes(record.uploadBytesCompleted, record.downloadBytesCompleted)
                    } else {
                        legacyTransferred
                    },
                    totalBytes = if (record.transferMetricsAvailable) {
                        directionalBytes(record.uploadBytesTotal, record.downloadBytesTotal)
                    } else {
                        total
                    },
                    planJson = gson.toJson(plan),
                    updatedAtEpochMillis = now,
                    phase = if (isTerminalSyncOperationStatus(record.status)) record.phase else phase,
                )
            },
            transition = { record ->
                if (isTerminalSyncOperationStatus(record.status)) {
                    null
                } else {
                    LocalSyncTransition(phase, STATUS_SYNCING, message)
                }
            },
        ) ?: return
        broadcastChanged(id)
    }

    @Synchronized
    fun recordTransferPlan(id: String?, plan: SyncTransferPlan) {
        if (id.isNullOrBlank() || id != plan.operationId) return
        val now = System.currentTimeMillis()
        updateOperation(
            id = id,
            now = now,
            transform = { record ->
                // The durable plan owns absolute progress. Accepting a decrease is necessary
                // when an uncommitted HTTP upload is retried from byte zero.
                val uploadCompleted = plan.operationUploadBytesCompleted
                val downloadCompleted = plan.operationDownloadBytesCompleted
                record.copy(
                    transferMetricsAvailable = true,
                    uploadBytesCompleted = uploadCompleted,
                    uploadBytesTotal = plan.operationUploadBytesTotal,
                    downloadBytesCompleted = downloadCompleted,
                    downloadBytesTotal = plan.operationDownloadBytesTotal,
                    transferPlanId = plan.planId,
                    transferredBytes = directionalBytes(uploadCompleted, downloadCompleted),
                    totalBytes = directionalBytes(plan.operationUploadBytesTotal, plan.operationDownloadBytesTotal),
                    updatedAtEpochMillis = now,
                )
            },
            transition = { null },
        ) ?: return
        broadcastChanged(id)
    }

    @Synchronized
    fun recordTransferProgress(
        id: String?,
        planId: String,
        uploadCompleted: Long,
        downloadCompleted: Long,
    ) {
        if (id.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        updateOperation(
            id = id,
            now = now,
            transform = { record ->
                if (!record.transferMetricsAvailable || record.transferPlanId != planId) {
                    return@updateOperation record
                }
                val safeUpload = uploadCompleted.coerceIn(0, record.uploadBytesTotal)
                val safeDownload = downloadCompleted.coerceIn(0, record.downloadBytesTotal)
                record.copy(
                    uploadBytesCompleted = safeUpload,
                    downloadBytesCompleted = safeDownload,
                    transferredBytes = directionalBytes(safeUpload, safeDownload),
                    totalBytes = directionalBytes(record.uploadBytesTotal, record.downloadBytesTotal),
                    updatedAtEpochMillis = now,
                )
            },
            transition = { null },
        ) ?: return
        broadcastChanged(id)
    }

    @Synchronized
    fun recordScheduler(id: String?, kind: String, schedulerId: String) {
        if (id.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        updateOperation(
            id = id,
            now = now,
            transform = { record ->
                record.copy(
                    schedulerKind = kind.take(64),
                    schedulerId = schedulerId.take(128),
                    updatedAtEpochMillis = now,
                )
            },
            transition = {
                LocalSyncTransition(
                    phase = SyncRunPhase.QUEUED,
                    status = STATUS_QUEUED,
                    message = "Scheduled with $kind",
                    persistStep = false,
                )
            },
        ) ?: return
        broadcastChanged(id)
    }

    @Synchronized
    fun recordBuildProgress(
        id: String?,
        progress: List<SyncBuildProgress>,
        buildsActive: Boolean,
    ) {
        if (id.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        SyncDiagnosticArchive(appContext).append(id, "builds", progress)
        val safeProgress = progress.take(MAX_BUILDS).map { item ->
            item.copy(
                buildId = item.buildId.take(128),
                target = item.target.take(512),
                serverStatus = item.serverStatus.take(64),
                phase = item.phase.take(128),
                completed = item.completed.coerceAtLeast(0),
                total = item.total.coerceAtLeast(0),
                unit = item.unit.take(64),
                detail = item.detail.lineSequence()
                    .joinToString(" ") { it.trim() }
                    .trim()
                    .take(600),
                documents = item.documents.distinct().take(MAX_BUILD_DOCUMENTS)
                    .map { it.take(512) },
            )
        }
        updateOperation(
            id = id,
            now = now,
            transform = { record ->
                recordSyncBuildProgress(
                    record,
                    safeProgress.ifEmpty { record.buildProgress },
                    now,
                    buildsActive,
                )
            },
            transition = { record ->
                if (buildsActive) {
                    LocalSyncTransition(SyncRunPhase.BUILDING, record.status, BUILDING_EVENT)
                } else {
                    null
                }
            },
        ) ?: return
        broadcastChanged(id)
    }

    internal fun deferBuildFailure(id: String?, failure: TerminalPdfBuildException) {
        append(id, STATUS_SYNCING, failure.message.orEmpty(), errorCode = "pdf_build_pending")
    }

    @Synchronized
    fun fail(id: String?, displayMessage: String, technicalEvent: String) {
        append(id, STATUS_ERROR, technicalEvent, displayMessage)
    }

    @Synchronized
    fun attention(id: String?, technicalEvent: String) {
        append(id, STATUS_ATTENTION, technicalEvent, STATUS_ATTENTION)
    }

    @Synchronized
    fun activeGlobalOperation(
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): SyncOperationRecord? = latestGlobalOperation(nowEpochMillis)
        ?.takeIf { it.status == STATUS_SYNCING || it.status == STATUS_BUILDING }

    @Synchronized
    fun activeTargetOperation(
        target: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): SyncOperationRecord? = latestTargetOperation(target, nowEpochMillis)
        ?.takeIf { it.status == STATUS_SYNCING || it.status == STATUS_BUILDING }

    @Synchronized
    fun latestGlobalOperation(
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): SyncOperationRecord? = runBlocking(Dispatchers.IO) {
        awaitLegacyImport()
        dao.latestGlobalSyncRun(nowEpochMillis - RETENTION_MILLIS)?.let(::storedRunRecord)
    }

    @Synchronized
    fun pendingGlobalOperation(
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): SyncOperationRecord? = latestGlobalOperation(nowEpochMillis)
        ?.takeIf { isPendingSyncOperationStatus(it.status) }

    @Synchronized
    fun pendingTargetOperation(
        target: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): SyncOperationRecord? = latestTargetOperation(target, nowEpochMillis)
        ?.takeIf { isPendingSyncOperationStatus(it.status) }

    @Synchronized
    fun latestTargetOperation(
        target: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): SyncOperationRecord? = runBlocking(Dispatchers.IO) {
        awaitLegacyImport()
        dao.latestTargetSyncRun(nowEpochMillis - RETENTION_MILLIS, target)?.let(::storedRunRecord)
    }

    fun observeLatestGlobalOperation(
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Flow<SyncOperationRecord?> = dao
        .observeLatestGlobalSyncRun(nowEpochMillis - RETENTION_MILLIS)
        .map { it?.let(::storedRunRecord) }

    fun observeLastSuccessfulSyncAt(): Flow<Long> = dao.observeState()
        .map { it?.lastSyncSuccessEpochMillis ?: 0L }

    fun pendingOperations(
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): List<SyncOperationRecord> = runBlocking(Dispatchers.IO) {
        awaitLegacyImport()
        dao.pendingSyncRuns(
            cutoff = nowEpochMillis - RETENTION_MILLIS,
            statuses = PENDING_STATUSES,
            limit = MAX_RECORDS,
        ).map(::storedRunRecord)
    }

    fun observeHasPendingOperations(): Flow<Boolean> = dao
        .observeHasPendingSyncRuns(System.currentTimeMillis() - RETENTION_MILLIS, PENDING_STATUSES)
        .distinctUntilChanged()

    fun operation(id: String): SyncOperationRecord? = runBlocking(Dispatchers.IO) {
        awaitLegacyImport()
        dao.syncRun(id)?.let(::storedRunRecord)
    }

    fun operationWithEvents(id: String): SyncOperationRecord? = runBlocking(Dispatchers.IO) {
        awaitLegacyImport()
        dao.syncRun(id)?.let { run -> storedRunRecord(run, dao.recentSyncEvents(id, MAX_EVENTS)) }
    }

    fun summaries(nowEpochMillis: Long = System.currentTimeMillis()): List<SyncOperationRecord> {
        val cutoff = nowEpochMillis - RETENTION_MILLIS
        return runBlocking(Dispatchers.IO) {
            awaitLegacyImport()
            dao.syncRuns(cutoff, MAX_RECORDS).map(::storedRunRecord)
        }
    }

    @Synchronized
    fun records(nowEpochMillis: Long = System.currentTimeMillis()): List<SyncOperationRecord> {
        val cutoff = nowEpochMillis - RETENTION_MILLIS
        return runBlocking(Dispatchers.IO) {
            awaitLegacyImport()
            val runs = dao.syncRuns(cutoff, MAX_RECORDS)
            if (runs.isEmpty()) return@runBlocking emptyList()
            val eventsByRun = dao.syncEventsForRuns(runs.map(ThoughtsSyncRunEntity::id))
                .groupBy(ThoughtsSyncEventEntity::runId)
            runs.map { run ->
                storedRunRecord(run, eventsByRun[run.id].orEmpty())
            }
        }
    }

    fun observeRecord(id: String): Flow<SyncOperationRecord?> =
        combine(dao.observeSyncRun(id).distinctUntilChanged(), dao.observeSyncEvents(id).distinctUntilChanged()) { run, events ->
            run?.let { storedRunRecord(it, events) }
        }

    fun serverCursor(id: String): Long = runBlocking(Dispatchers.IO) {
        dao.syncRun(id)?.lastServerSequence ?: 0L
    }

    fun markStreamState(id: String?, state: String) {
        if (id.isNullOrBlank()) return
        runBlocking(Dispatchers.IO) { dao.updateStreamState(id, state.take(32)) }
        broadcastChanged(id)
    }

    internal fun appendDiagnostic(id: String?, event: SyncDiagnosticEvent) = appendDiagnostics(id, listOf(event))

    internal fun appendDiagnostics(id: String?, events: List<SyncDiagnosticEvent>) {
        if (id.isNullOrBlank() || events.isEmpty()) return
        SyncDiagnosticArchive(appContext).append(id, "diagnostics", events)
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val attempt = dao.diagnosticRunAttempt(id) ?: return@withTransaction
                val count = dao.diagnosticEventCount(id)
                if (count >= 1000) return@withTransaction
                dao.appendSyncEvents(events.take(1000 - count).mapIndexed { index, event ->
                    val limited = count + index == 999
                    ThoughtsSyncEventEntity(
                        runId = id,
                        phase = "diagnostic",
                        state = if (limited) "limit_reached" else event.state,
                        attempt = attempt,
                        message = if (limited) "Diagnostic event limit reached" else event.stage,
                        createdAtEpochMillis = event.occurredAtEpochMillis,
                        origin = "diagnostic",
                        component = "phone_diagnostics_v1",
                        metadataJson = if (limited) "{}" else gson.toJson(event.values),
                    )
                })
            }
        }
        // Do not change run status, elapsed time, or broadcast a UI refresh.
    }

    fun persistServerEvent(id: String, event: TraceEventRecord) {
        require(event.traceId == id && event.sequence > 0) {
            "The server returned a trace event for a different run"
        }
        SyncDiagnosticArchive(appContext).append(id, "server_event", event)
        val receivedAt = System.currentTimeMillis()
        val occurredAt = runCatching { Instant.parse(event.timestamp).toEpochMilli() }
            .getOrDefault(receivedAt)
        runBlocking(Dispatchers.IO) {
            dao.persistServerEvent(
                ThoughtsSyncEventEntity(
                    runId = id,
                    phase = event.phase.take(64),
                    state = event.state.take(32),
                    attempt = dao.syncRun(id)?.attempt ?: 0,
                    message = event.message.lineSequence().joinToString(" ") { it.trim() }.take(4000),
                    errorCode = event.errorCode.orEmpty().take(128),
                    createdAtEpochMillis = occurredAt,
                    origin = "server",
                    serverSequence = event.sequence,
                    serverTimestampEpochMillis = occurredAt,
                    receivedTimestampEpochMillis = receivedAt,
                    component = event.component.take(64),
                    buildId = event.buildId.orEmpty().take(128),
                    completed = event.completed,
                    total = event.total,
                    bytesCompleted = event.bytesCompleted,
                    bytesTotal = event.bytesTotal,
                    metadataJson = gson.toJson(event.metadata).take(16_000),
                ),
                event.terminal,
            )
        }
        broadcastChanged(id)
    }

    private fun updateOperation(
        id: String,
        now: Long,
        skipTransitionWhenUnchanged: Boolean = false,
        transform: suspend (SyncOperationRecord) -> SyncOperationRecord,
        transition: (SyncOperationRecord) -> LocalSyncTransition?,
    ): SyncOperationRecord? = runBlocking(Dispatchers.IO) {
        awaitLegacyImport()
        database.withTransaction {
            val current = dao.syncRun(id)?.let(::storedRunRecord)
                ?: return@withTransaction null
            val transformed = transform(current)
            if (skipTransitionWhenUnchanged && transformed == current) {
                return@withTransaction null
            }
            val updated = transformed.copy(legacyEventsImported = true)
            dao.putSyncRun(runEntity(updated))
            transition(updated)?.let { localTransition ->
                SyncDiagnosticArchive(appContext).append(id, "phone_event", localEventEntity(updated, localTransition, now))
                dao.appendSyncEvent(localEventEntity(updated, localTransition, now))
                if (localTransition.persistStep) {
                    dao.putSyncStep(
                        stepEntity(
                            updated,
                            localTransition.phase,
                            localTransition.status,
                            now,
                        ),
                    )
                }
            }
            updated
        }
    }

    private fun localEventEntity(
        record: SyncOperationRecord,
        transition: LocalSyncTransition,
        timestamp: Long,
    ): ThoughtsSyncEventEntity = ThoughtsSyncEventEntity(
        runId = record.id,
        phase = transition.phase,
        state = transition.phase,
        attempt = record.attempt,
        message = transition.message,
        errorCode = transition.errorCode,
        createdAtEpochMillis = timestamp,
    )

    private fun stepEntity(
        record: SyncOperationRecord,
        phase: String,
        state: String,
        timestamp: Long,
    ): ThoughtsSyncStepEntity = ThoughtsSyncStepEntity(
        runId = record.id,
        ordinal = phaseOrdinal(phase),
        phase = phase,
        state = state,
        attempt = record.attempt,
        startedAtEpochMillis = timestamp,
        updatedAtEpochMillis = timestamp,
    )

    private fun legacyRecords(nowEpochMillis: Long = System.currentTimeMillis()): List<SyncOperationRecord> {
        val cutoff = nowEpochMillis - RETENTION_MILLIS
        val parsed = runCatching {
            JsonParser.parseString(preferences.getString(KEY_RECORDS, "[]")).asJsonArray.mapNotNull { value ->
                val item = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = item.string("id").takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                    ?: return@mapNotNull null
                val started = item.long("started_at")
                if (started < cutoff) return@mapNotNull null
                val status = item.string("status").takeIf { it in STATUSES } ?: STATUS_ERROR
                val events = (item.getAsJsonArray("events") ?: JsonArray())
                    .mapNotNull { it.takeIf { event -> event.isJsonPrimitive }?.asString?.take(600) }
                    .takeLast(MAX_EVENTS)
                val storedEventTimestamps = (item.getAsJsonArray("event_timestamps") ?: JsonArray())
                    .map { value ->
                        runCatching { value.takeIf { it.isJsonPrimitive }?.asLong }
                            .getOrNull()
                            ?: 0L
                    }
                    .takeLast(MAX_EVENTS)
                val eventTimestamps = List(events.size) { index ->
                    storedEventTimestamps.getOrNull(index)?.coerceAtLeast(0L) ?: 0L
                }
                SyncOperationRecord(
                    id = id,
                    target = item.string("target").ifBlank { null },
                    kind = storedSyncOperationKind(item.string("kind"), status, events),
                    status = status,
                    startedAtEpochMillis = started,
                    updatedAtEpochMillis = item.long("updated_at").coerceAtLeast(started),
                    events = events,
                    eventTimestampsEpochMillis = eventTimestamps,
                    displayMessage = item.string("display_message").take(300),
                    buildProgress = (item.getAsJsonArray("build_progress") ?: JsonArray())
                        .mapNotNull { value ->
                            val build = value.takeIf { it.isJsonObject }?.asJsonObject
                                ?: return@mapNotNull null
                            SyncBuildProgress(
                                buildId = build.string("build_id").take(128),
                                target = build.string("target").take(512),
                                serverStatus = build.string("server_status").take(64),
                                phase = build.string("phase").take(128),
                                completed = build.int("completed").coerceAtLeast(0),
                                total = build.int("total").coerceAtLeast(0),
                                unit = build.string("unit").take(64),
                                detail = build.string("detail").take(600),
                                documents = (build.getAsJsonArray("documents") ?: JsonArray())
                                    .mapNotNull { document ->
                                        document.takeIf { it.isJsonPrimitive }
                                            ?.asString
                                            ?.take(512)
                                    }
                                    .distinct()
                                    .take(MAX_BUILD_DOCUMENTS),
                            )
                        }
                        .take(MAX_BUILDS),
                    buildCheckedAtEpochMillis = item.long("build_checked_at")
                        .coerceAtLeast(0),
                    phase = syncRunPhase(status, events.lastOrNull().orEmpty()),
                )
            }
        }.getOrDefault(emptyList()).sortedByDescending(SyncOperationRecord::startedAtEpochMillis)
        if (parsed.size > MAX_RECORDS) return parsed.take(MAX_RECORDS)
        return parsed
    }

    private fun ensureLegacyImported() {
        if (preferences.getBoolean(KEY_ROOM_IMPORTED, false)) return
        val artifactPlan = ArtifactSyncStateStore(appContext).load()
        val imported = legacyRecords().map { record ->
            if (artifactPlan?.operationId == record.id) {
                record.copy(
                    planJson = gson.toJson(artifactPlan),
                    totalBytes = artifactPlan.estimatedTransferBytes.coerceAtLeast(0L),
                    legacyEventsImported = true,
                )
            } else {
                record.copy(legacyEventsImported = true)
            }
        }
        runBlocking(Dispatchers.IO) {
            if (imported.isNotEmpty()) {
                dao.putSyncRuns(imported.map(::runEntity))
                imported.forEach { record ->
                    syncOperationEventLines(record).forEach { event ->
                        dao.appendSyncEvent(
                            ThoughtsSyncEventEntity(
                                runId = record.id,
                                phase = record.phase,
                                state = record.phase,
                                attempt = record.attempt,
                                message = event.message,
                                createdAtEpochMillis = event.timestampEpochMillis
                                    .takeIf { it > 0 }
                                    ?: record.updatedAtEpochMillis,
                            ),
                        )
                    }
                }
            }
        }
        preferences.edit().putBoolean(KEY_ROOM_IMPORTED, true).commit()
    }

    private fun ensureRoomEventsImported() {
        runBlocking(Dispatchers.IO) {
            dao.unimportedSyncRuns(MAX_RECORDS)
                .forEach { run ->
                    val existing = dao.syncEvents(run.id)
                        .map { it.message to it.createdAtEpochMillis }
                        .toSet()
                    val messages = runCatching {
                        gson.fromJson(run.eventsJson, Array<String>::class.java).toList()
                    }.getOrDefault(emptyList())
                    val timestamps = runCatching {
                        gson.fromJson(run.eventTimestampsJson, LongArray::class.java).toList()
                    }.getOrDefault(emptyList())
                    messages.forEachIndexed { index, message ->
                        val timestamp = timestamps.getOrNull(index)?.takeIf { it > 0 }
                            ?: run.updatedAtEpochMillis
                        if ((message to timestamp) !in existing) {
                            dao.appendSyncEvent(
                                ThoughtsSyncEventEntity(
                                    runId = run.id,
                                    phase = run.phase,
                                    state = run.state,
                                    attempt = run.attempt,
                                    message = message.take(4000),
                                    createdAtEpochMillis = timestamp,
                                    origin = "legacy",
                                    receivedTimestampEpochMillis = timestamp,
                                    component = "phone",
                                ),
                            )
                        }
                    }
                    dao.markLegacyEventsImported(run.id)
                }
        }
    }

    private fun runEntity(record: SyncOperationRecord): ThoughtsSyncRunEntity =
        ThoughtsSyncRunEntity(
            id = record.id,
            traceId = record.id,
            retryOf = record.retryOf,
            target = record.target,
            kind = record.kind.name.lowercase(),
            state = record.phase,
            phase = record.phase,
            displayStatus = record.status,
            startedAtEpochMillis = record.startedAtEpochMillis,
            updatedAtEpochMillis = record.updatedAtEpochMillis,
            displayMessage = record.displayMessage,
            eventsJson = gson.toJson(record.events.takeLast(MAX_EVENTS)),
            eventTimestampsJson = gson.toJson(
                List(record.events.size) { index ->
                    record.eventTimestampsEpochMillis.getOrNull(index) ?: 0L
                }.takeLast(MAX_EVENTS),
            ),
            buildProgressJson = gson.toJson(record.buildProgress.take(MAX_BUILDS)),
            buildCheckedAtEpochMillis = record.buildCheckedAtEpochMillis,
            attempt = record.attempt,
            transferredBytes = record.transferredBytes,
            totalBytes = record.totalBytes,
            transferMetricsAvailable = record.transferMetricsAvailable,
            uploadBytesCompleted = record.uploadBytesCompleted,
            uploadBytesTotal = record.uploadBytesTotal,
            downloadBytesCompleted = record.downloadBytesCompleted,
            downloadBytesTotal = record.downloadBytesTotal,
            transferPlanId = record.transferPlanId,
            planJson = record.planJson,
            schedulerKind = record.schedulerKind,
            schedulerId = record.schedulerId,
            serverState = record.serverState,
            serverTerminal = record.serverTerminal,
            lastServerSequence = record.lastServerSequence,
            streamState = record.streamState,
            legacyEventsImported = record.legacyEventsImported,
        )

    private fun storedRunRecord(
        run: ThoughtsSyncRunEntity,
        storedEvents: List<ThoughtsSyncEventEntity> = emptyList(),
    ): SyncOperationRecord {
        val events = runCatching {
            gson.fromJson(run.eventsJson, Array<String>::class.java).toList()
        }.getOrDefault(emptyList())
        val timestamps = runCatching {
            gson.fromJson(run.eventTimestampsJson, LongArray::class.java).toList()
        }.getOrDefault(emptyList())
        val progress = runCatching {
            gson.fromJson(run.buildProgressJson, Array<SyncBuildProgress>::class.java).toList()
        }.getOrDefault(emptyList())
        return SyncOperationRecord(
            id = run.id,
            target = run.target,
            kind = storedSyncOperationKind(run.kind, run.displayStatus, events),
            status = run.displayStatus,
            startedAtEpochMillis = run.startedAtEpochMillis,
            updatedAtEpochMillis = run.updatedAtEpochMillis,
            events = events,
            eventTimestampsEpochMillis = timestamps,
            displayMessage = run.displayMessage,
            buildProgress = progress,
            buildCheckedAtEpochMillis = run.buildCheckedAtEpochMillis,
            retryOf = run.retryOf,
            phase = run.phase,
            attempt = run.attempt,
            transferredBytes = run.transferredBytes,
            totalBytes = run.totalBytes,
            transferMetricsAvailable = run.transferMetricsAvailable,
            uploadBytesCompleted = run.uploadBytesCompleted,
            uploadBytesTotal = run.uploadBytesTotal,
            downloadBytesCompleted = run.downloadBytesCompleted,
            downloadBytesTotal = run.downloadBytesTotal,
            transferPlanId = run.transferPlanId,
            planJson = run.planJson,
            schedulerKind = run.schedulerKind,
            schedulerId = run.schedulerId,
            serverState = run.serverState,
            serverTerminal = run.serverTerminal,
            lastServerSequence = run.lastServerSequence,
            streamState = run.streamState,
            legacyEventsImported = run.legacyEventsImported,
            journalEvents = storedEvents.map { event ->
                SyncJournalEvent(
                    id = event.id,
                    origin = event.origin,
                    serverSequence = event.serverSequence,
                    occurredAtEpochMillis = event.serverTimestampEpochMillis.takeIf { it > 0 }
                        ?: event.createdAtEpochMillis,
                    receivedAtEpochMillis = event.receivedTimestampEpochMillis.takeIf { it > 0 }
                        ?: event.createdAtEpochMillis,
                    component = event.component,
                    phase = event.phase,
                    state = event.state,
                    buildId = event.buildId,
                    message = event.message,
                    completed = event.completed,
                    total = event.total,
                    bytesCompleted = event.bytesCompleted,
                    bytesTotal = event.bytesTotal,
                    errorCode = event.errorCode,
                    metadataJson = event.metadataJson,
                )
            }.sortedWith(compareBy(SyncJournalEvent::occurredAtEpochMillis).thenBy(SyncJournalEvent::id)),
        )
    }

    private fun phaseOrdinal(phase: String): Int = when (phase) {
        SyncRunPhase.QUEUED -> 0
        SyncRunPhase.PREFLIGHT -> 1
        SyncRunPhase.UPLOADING -> 2
        SyncRunPhase.WAITING_NETWORK, SyncRunPhase.WAITING_USER -> 3
        SyncRunPhase.BUILDING -> 4
        SyncRunPhase.DOWNLOADING -> 5
        SyncRunPhase.VERIFYING -> 6
        SyncRunPhase.ACTIVATING -> 7
        else -> 8
    }

    private fun broadcastChanged(id: String) {
        appContext.sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(appContext.packageName)
                .putExtra(EXTRA_OPERATION_ID, id),
        )
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    private fun JsonObject.long(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0

    private fun JsonObject.int(name: String): Int =
        runCatching { get(name)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull() ?: 0

    private fun String.sanitizeDisplayMessage(): String =
        lineSequence().joinToString(" ") { it.trim() }.trim().take(300)

    companion object {
        const val ACTION_STATE_CHANGED =
            "com.gitlab.mudlej.MjPdfReader.action.THOUGHTS_SYNC_STATE_CHANGED"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val STATUS_SYNCING = "Syncing…"
        const val STATUS_BUILDING = "Building…"
        const val STATUS_WAITING = "Waiting"
        const val STATUS_QUEUED = "Queued"
        const val STATUS_RETRYING = "Retrying"
        const val STATUS_SYNCED = "Synced"
        const val STATUS_ATTENTION = "Changes need attention"
        const val STATUS_ERROR = "Error"
        const val STREAM_LIVE = "live"
        const val STREAM_RECONNECTING = "reconnecting"
        // Keep the persisted value for existing rows; this state means that the complete
        // Fedora journal is saved locally, not that the phone is offline.
        const val STREAM_SAVED = "offline"
        const val USER_MESSAGE_TAILSCALE_OFF =
            "Tailscale is off. Open Tailscale, connect it, then retry."
        const val USER_MESSAGE_CANT_CONNECT =
            "Can’t reach the Thoughts server. Check the internet connection, then retry."
        const val USER_MESSAGE_REJECTED = "Sync rejected by the Thoughts server."
        const val USER_MESSAGE_INTERRUPTED = "Sync was interrupted."
        const val USER_MESSAGE_SYNC_FAILED = "Sync failed."
        const val USER_MESSAGE_PDF_BUILD_FAILED_SYNCED =
            "PDF build failed. Your changes are synced with the server. The last successful PDF remains available."
        // The same message as saved by builds before the server was renamed in user-visible text;
        // runs stored with it still count as synced with a PDF build failure. Never shown for new runs.
        const val LEGACY_USER_MESSAGE_PDF_BUILD_FAILED_SYNCED =
            "PDF build failed. Your changes are synced with Fedora. The last successful PDF remains available."
        const val USER_MESSAGE_PDF_BUILD_ATTENTION_TEX_SAVED =
            "PDF build needs attention. Your TeX changes were saved."
        const val USER_MESSAGE_PDF_BUILD_CANCELLED_TEX_SAVED =
            "PDF build was cancelled. Your TeX changes were saved."
        const val USER_MESSAGE_PDF_BUILD_FAILED_TEX_SAVED =
            "PDF build failed. Your TeX changes were saved."
        const val INTERRUPTION_GRACE_MILLIS = 60_000L
        private val STATUSES = setOf(
            STATUS_SYNCING,
            STATUS_BUILDING,
            STATUS_WAITING,
            STATUS_QUEUED,
            STATUS_RETRYING,
            STATUS_SYNCED,
            STATUS_ATTENTION,
            STATUS_ERROR,
        )
        private val PENDING_STATUSES = listOf(
            STATUS_SYNCING,
            STATUS_BUILDING,
            STATUS_WAITING,
            STATUS_QUEUED,
            STATUS_RETRYING,
        )
        private const val PREFERENCES = "thoughts-sync-operations-v1"
        /** The one-time import of older versions' Sync history, once per process and database. */
        private val LEGACY_IMPORT = com.gitlab.mudlej.MjPdfReader.data.BackgroundOnce("thoughts-sync-history-import") { error ->
            runCatching { android.util.Log.w("ThoughtsSync", "Could not import the older Sync history", error) }
        }
        private const val KEY_RECORDS = "records"
        private const val KEY_ROOM_IMPORTED = "room_v1_imported"
        private const val MAX_RECORDS = 500
        private const val MAX_EVENTS = 1000
        private const val MAX_BUILDS = 25
        private const val MAX_BUILD_DOCUMENTS = 200
        private const val RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}


internal fun unresolvedLinkingCount(payloads: List<String>, target: String?): Int = payloads.count { raw ->
    runCatching {
        val value = JsonParser.parseString(raw).asJsonObject
        fun string(obj: JsonObject?, key: String): String? = obj?.get(key)?.takeUnless { it.isJsonNull }?.asString
        fun obj(key: String): JsonObject? = value.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        val collection = string(obj("origin"), "collection")
        value.get("deleted")?.takeUnless { it.isJsonNull }?.asBoolean != true &&
            string(value, "subtype") != "Note" &&
            string(value, "parent_id").isNullOrBlank() &&
            (target.isNullOrBlank() || target == "all" || target.removeSuffix(".pdf") == collection) &&
            // "Anchor removed" is not a linking failure; Issues counts it.
            !com.gitlab.mudlej.MjPdfReader.data.source.CommentAnchorState.isAnchorRemoved(obj("scope")) &&
            (string(obj("linking"), "status") in setOf("needs_attention", "failed") ||
                string(obj("scope"), "state") == "needs_attention")
    }.getOrDefault(false)
}
