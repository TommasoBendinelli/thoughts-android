// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.tbend.thoughts.sdk.ServerException

object ThoughtsSyncTrigger {
    @Volatile private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun enqueue() {
        appContext?.let(ThoughtsSyncScheduler::enqueueBackground)
    }
}

internal fun userInitiatedSyncJobId(operationId: String): Int =
    0x40000000 or (UUID.fromString(operationId).hashCode() and 0x0fffffff)

internal fun shouldFallbackFromUserInitiatedJob(scheduleResult: Int): Boolean =
    scheduleResult != JobScheduler.RESULT_SUCCESS

internal fun syncOperationTag(operationId: String): String = "thoughts-sync-operation:$operationId"

private const val SYNC_INPUT_RUN_IN_FOREGROUND = "run_in_foreground"

enum class ExplicitSyncOrigin {
    USER_ACTION,
    BACKGROUND_COMMAND,
    BACKGROUND_CONTINUATION,
    BACKGROUND_WORK,
}

internal data class ExplicitSyncExecutionPolicy(
    val scheduleUserInitiatedJob: Boolean,
    val expediteWorkManager: Boolean,
    val runWorkerInForeground: Boolean,
)

internal fun explicitSyncExecutionPolicy(
    origin: ExplicitSyncOrigin,
    sdkInt: Int,
): ExplicitSyncExecutionPolicy {
    val directlyUserInitiated = origin == ExplicitSyncOrigin.USER_ACTION
    // An exported command can be expedited, but it cannot claim the visible-app
    // exemptions required by UIDT jobs or foreground-service promotion.
    return ExplicitSyncExecutionPolicy(
        scheduleUserInitiatedJob = directlyUserInitiated &&
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        expediteWorkManager = directlyUserInitiated ||
            origin == ExplicitSyncOrigin.BACKGROUND_COMMAND,
        runWorkerInForeground = directlyUserInitiated,
    )
}

internal suspend fun <T> runSyncWorkerWithForegroundPolicy(
    runInForeground: Boolean,
    promoteToForeground: suspend () -> Unit,
    runSync: suspend () -> T,
): T {
    if (runInForeground) promoteToForeground()
    return runSync()
}

internal fun isForegroundSyncStartRestricted(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause?.takeUnless { cause -> cause === it } }
        .take(16)
        .any { it.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException" }

object ThoughtsSyncScheduler {
    const val ACTION_FINISHED = "com.gitlab.mudlej.MjPdfReader.action.THOUGHTS_SYNC_FINISHED"
    // Retained for jobs scheduled by builds installed before per-operation IDs.
    const val JOB_ID = 0x4d4a53
    const val WORK_NAME = "thoughts-server-manual"
    const val ARTIFACT_WORK_NAME = "thoughts-artifact-sync"
    const val TERMINAL_ARTIFACT_REFRESH_WORK_NAME = "thoughts-terminal-artifact-refresh"
    const val CHANNEL_ID = "thoughts-manual-sync"
    const val NOTIFICATION_ID = 0x4d4a
    const val ARTIFACT_NOTIFICATION_ID = 0x4d4b

    private const val INPUT_EXPLICIT = "explicit"
    private const val INPUT_PLAN_ID = "plan_id"
    private const val INPUT_TARGET_PDF = "target_pdf"
    private const val INPUT_OPERATION_ID = "operation_id"
    private const val INPUT_BUILDING = "building"
    private const val LEGACY_LIBRARY_HEAD_WORK_NAME = "thoughts-library-head-refresh"
    private val runningOperationIds = ConcurrentHashMap.newKeySet<String>()

    internal fun markRunning(operationId: String?) {
        operationId?.let(runningOperationIds::add)
    }

    internal fun markStopped(operationId: String?) {
        operationId?.let(runningOperationIds::remove)
    }

    fun resume(context: Context) {
        val appContext = context.applicationContext
        SyncTransferCoordinator.resume(appContext)
        val artifactPlan = ArtifactSyncStateStore(appContext).load()
        val transferPlan = SyncTransferStateStore(appContext).load()
        if (SyncOperationStore(appContext).pendingOperations().isNotEmpty()) {
            showRunningNotification(appContext)
        }
        ArtifactSyncCoordinator.broadcast(
            appContext,
            artifactPlan,
            showPrompt = false,
        )
        SyncTransferCoordinator.broadcast(
            appContext,
            transferPlan,
            showPrompt = false,
        )
        if (artifactPlan != null && !artifactPlan.waitForUnmetered &&
            transferPlan?.waitForUnmetered != true &&
            PdfDeltaFallbackStore(appContext).reconsiderLegacyRejection(artifactPlan.operationId)
        ) {
            SyncOperationStore(appContext).append(
                artifactPlan.operationId, SyncOperationStore.STATUS_QUEUED,
                "Rechecking the small PDF patch after correcting network-error handling",
            )
            enqueueExplicitContinuation(appContext, artifactPlan.targetPdfFilenames.singleOrNull(),
                artifactPlan.operationId, building = false)
            return
        }
        if (artifactPlan != null && shouldResumeArtifactWork(artifactPlan)) {
            enqueueArtifacts(
                appContext,
                allowMetered = ThoughtsNetworkPolicy.isUnmetered(appContext) ||
                    artifactPlan.canRunOnMeteredWithoutApproval || artifactPlan.meteredApproved,
                // Replace work left with an UNMETERED constraint by the old source-artifact rule.
                replace = artifactPlan.status == ArtifactSyncPlan.STATUS_WAITING_NETWORK &&
                    !artifactPlan.waitForUnmetered && artifactPlan.canRunOnMeteredWithoutApproval,
            )
        }
        when (terminalArtifactResumeAction(artifactPlan, transferPlan)) {
            TerminalArtifactResumeAction.NONE -> Unit
            TerminalArtifactResumeAction.REPLAN -> enqueueTerminalArtifactRefresh(
                appContext,
                requireNotNull(artifactPlan).planId,
                replace = shouldReplaceTerminalArtifactWorkOnResume(requireNotNull(artifactPlan)),
            )
            TerminalArtifactResumeAction.DOWNLOAD -> enqueueArtifacts(
                appContext,
                allowMetered = requireNotNull(artifactPlan).let { plan ->
                    ThoughtsNetworkPolicy.isUnmetered(appContext) ||
                        plan.canRunOnMeteredWithoutApproval || plan.meteredApproved
                },
                replace = shouldReplaceTerminalArtifactWorkOnResume(requireNotNull(artifactPlan)),
            )
        }
    }

    fun enqueue(
        context: Context,
        targetPdfFilename: String? = null,
        building: Boolean = false,
        preparedOperationId: String? = null,
        retryOperationId: String? = null,
        origin: ExplicitSyncOrigin = ExplicitSyncOrigin.USER_ACTION,
    ) {
        require(targetPdfFilename == null || isSafePdfFilename(targetPdfFilename))
        val appContext = context.applicationContext
        val operationStore = SyncOperationStore(appContext)
        val operationId = preparedOperationId
            ?: retryOperationId?.let(operationStore::beginRetry)
            ?: operationStore.begin(targetPdfFilename, building)
        if (!ThoughtsNetworkPolicy.requiredVpnActive(appContext)) {
            // Tailscale is off; if the public address works, the next Sync uses it instead.
            PublicServerMigration.startInBackground(appContext)
            operationStore.fail(
                operationId,
                SyncOperationStore.USER_MESSAGE_TAILSCALE_OFF,
                "No active VPN transport was found; the Thoughts server uses Tailscale",
            )
            broadcastFinished(appContext, operationId)
            return
        }
        showRunningNotification(appContext)
        if (origin == ExplicitSyncOrigin.USER_ACTION) {
            ThoughtsSyncForegroundService.start(appContext)
        }
        if (retryOperationId != null) {
            val artifactStore = ArtifactSyncStateStore(appContext)
            val failedArtifact = artifactStore.load()?.takeIf {
                it.operationId == retryOperationId &&
                    it.status == ArtifactSyncPlan.STATUS_FAILED
            }
            if (failedArtifact != null) {
                artifactStore.publish(failedArtifact.copy(operationId = operationId))
                val canRunNow = ThoughtsNetworkPolicy.isUnmetered(appContext) ||
                    failedArtifact.canRunOnMeteredWithoutApproval ||
                    failedArtifact.meteredApproved
                val updated = if (canRunNow) {
                    artifactStore.markQueued(failedArtifact.planId)
                } else {
                    artifactStore.markWaiting(failedArtifact.planId)
                } ?: return
                operationStore.append(
                    operationId,
                    if (canRunNow) SyncOperationStore.STATUS_QUEUED else
                        SyncOperationStore.STATUS_WAITING,
                    if (canRunNow) "Retry queued" else "Waiting for an unmetered network",
                )
                enqueueArtifacts(
                    appContext,
                    allowMetered = updated.canRunOnMeteredWithoutApproval ||
                        updated.meteredApproved,
                    replace = true,
                )
                ArtifactSyncCoordinator.broadcast(appContext, updated, showPrompt = false)
                return
            }
            operationStore.append(
                operationId,
                SyncOperationStore.STATUS_QUEUED,
                "Retry requested",
            )
        }
        try {
            scheduleExplicitSync(
                appContext,
                targetPdfFilename,
                operationId,
                building,
                NetworkType.NOT_REQUIRED,
                origin,
            )
        } catch (error: Throwable) {
            SyncOperationStore(appContext).fail(
                operationId,
                SyncOperationStore.USER_MESSAGE_INTERRUPTED,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
            throw error
        }
    }

    // Local edits remain queued until the user explicitly starts Sync.
    fun enqueueBackground(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    fun enqueueExplicitContinuation(
        context: Context,
        targetPdfFilename: String? = null,
        operationId: String? = null,
        building: Boolean = false,
        requiredNetworkType: NetworkType = NetworkType.NOT_REQUIRED,
    ) {
        val appContext = context.applicationContext
        showRunningNotification(appContext)
        try {
            scheduleExplicitSync(
                appContext,
                targetPdfFilename,
                operationId,
                building,
                requiredNetworkType,
                ExplicitSyncOrigin.BACKGROUND_CONTINUATION,
            )
        } catch (error: Throwable) {
            SyncOperationStore(appContext).fail(
                operationId,
                SyncOperationStore.USER_MESSAGE_INTERRUPTED,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
            broadcastFinished(appContext, operationId)
            throw error
        }
    }

    fun restartPendingOperation(
        context: Context,
        record: SyncOperationRecord,
        origin: ExplicitSyncOrigin,
    ) {
        val appContext = context.applicationContext
        showRunningNotification(appContext)
        val building = record.kind == SyncOperationKind.BUILD
        if (origin == ExplicitSyncOrigin.USER_ACTION) {
            ThoughtsSyncForegroundService.start(appContext)
        }
        SyncOperationStore(appContext).append(
            record.id,
            SyncOperationStore.STATUS_QUEUED,
            "Restarted the pending sync",
        )
        enqueueWorkManager(
            appContext,
            ExistingWorkPolicy.REPLACE,
            explicit = true,
            targetPdfFilename = record.target,
            operationId = record.id,
            building = building,
            requiredNetworkType = NetworkType.NOT_REQUIRED,
            executionPolicy = explicitSyncExecutionPolicy(origin, Build.VERSION.SDK_INT),
        )
    }

    private fun scheduleExplicitSync(
        context: Context,
        targetPdfFilename: String?,
        operationId: String?,
        building: Boolean,
        requiredNetworkType: NetworkType,
        origin: ExplicitSyncOrigin,
    ) {
        val executionPolicy = explicitSyncExecutionPolicy(origin, Build.VERSION.SDK_INT)
        val acceptedByJobScheduler = if (
            executionPolicy.scheduleUserInitiatedJob && operationId != null
        ) {
            try {
                scheduleUserInitiatedJob(
                    context,
                    targetPdfFilename,
                    operationId,
                    building,
                    requiredNetworkType,
                )
            } catch (error: Exception) {
                Log.w(
                    SYNC_LOG_TAG,
                    error.syncFailureMessage(
                        "Android could not schedule the user-initiated sync; using WorkManager",
                    ),
                )
                false
            }
        } else {
            false
        }
        if (acceptedByJobScheduler) return

        enqueueWorkManager(
            context,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            explicit = true,
            targetPdfFilename = targetPdfFilename,
            operationId = operationId,
            building = building,
            requiredNetworkType = requiredNetworkType,
            executionPolicy = executionPolicy,
        )
    }

    /**
     * Settles the pending Syncs that cannot go on ([syncOperationReconciliation]): one without
     * scheduled or running work is "interrupted", and one whose work runs without progress for
     * [SYNC_STALL_DEADLINE_MILLIS] ([stallObserver]) is ended as failed and its work cancelled,
     * so that either can be started again. [SyncStallWatchdog] looks often, without announcing.
     */
    internal fun reconcileInterruptedOperations(
        context: Context,
        nowEpochMillis: Long = System.currentTimeMillis(),
        announceContinuation: Boolean = true,
        stallObserver: SyncStallObserver? = null,
    ) {
        val appContext = context.applicationContext
        SyncTransferCoordinator.resume(appContext)
        val store = SyncOperationStore(appContext)
        val pending = store.pendingOperations(nowEpochMillis)
        if (pending.isEmpty()) return

        val scheduledOperationIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val syncService = ComponentName(appContext, ThoughtsSyncJobService::class.java)
            appContext.getSystemService(JobScheduler::class.java).allPendingJobs
                .asSequence()
                .filter { it.service == syncService }
                .mapNotNull { it.extras.getString(INPUT_OPERATION_ID) }
                .toMutableSet()
        } else {
            mutableSetOf()
        }
        scheduledOperationIds += runningOperationIds
        // The Syncs whose job or worker is executing in this process right now.
        val executingOperationIds = runningOperationIds.toMutableSet()
        val workManager = WorkManager.getInstance(appContext)
        pending.forEach { record ->
            val states = runCatching {
                workManager.getWorkInfosByTag(syncOperationTag(record.id))
                    .get(10, TimeUnit.SECONDS)
                    .map { it.state }
            }.getOrElse { error ->
                Log.w(SYNC_LOG_TAG, error.syncFailureMessage("Could not inspect sync work"))
                return
            }
            if (states.any { !it.isFinished }) scheduledOperationIds += record.id
            if (states.any { it == androidx.work.WorkInfo.State.RUNNING }) executingOperationIds += record.id
        }
        val stalledOperationIds = stallObserver
            ?.stalled(pending.filter { it.id in executingOperationIds }, SystemClock.uptimeMillis())
            .orEmpty()

        pending.sortedBy(SyncOperationRecord::updatedAtEpochMillis).forEach { record ->
            when (
                syncOperationReconciliation(
                    record, nowEpochMillis, record.id in scheduledOperationIds, announceContinuation,
                    stalled = record.id in stalledOperationIds,
                )
            ) {
                SyncOperationReconciliation.KEEP -> Unit
                SyncOperationReconciliation.ANNOUNCE_CONTINUATION ->
                    store.append(record.id, record.status, SYNC_CONTINUATION_EVENT)
                SyncOperationReconciliation.INTERRUPTED -> {
                    val serverMayContinue = !record.serverTerminal &&
                        record.serverState in setOf("queued", "running", "building")
                    store.fail(
                        record.id,
                        if (serverMayContinue) {
                            "Phone disconnected; the server status was last known as ${record.serverState}."
                        } else {
                            interruptedSyncFailureMessage(
                                ThoughtsNetworkPolicy.requiredVpnActive(appContext),
                            )
                        },
                        "Phone execution was interrupted because no scheduled or running sync work was found after app startup",
                    )
                    store.markStreamState(record.id, SyncOperationStore.STREAM_SAVED)
                    // Nothing runs for it any more: the running notification must not outlive it.
                    broadcastFinished(appContext, record.id)
                }
                SyncOperationReconciliation.STALLED -> {
                    val detail = "No progress was recorded for ${SYNC_STALL_DEADLINE_MILLIS / 60_000} minutes; " +
                        "the Sync was ended and its work cancelled so that it can be started again"
                    Log.w(SYNC_LOG_TAG, "Sync ${record.id} stalled in phase ${record.phase}; ending it")
                    store.fail(record.id, stalledSyncFailureMessage(), detail)
                    store.markStreamState(record.id, SyncOperationStore.STREAM_SAVED)
                    // A download it left half done is offered again by Retry, like any failed one.
                    runCatching {
                        val artifacts = ArtifactSyncStateStore(appContext)
                        artifacts.load()?.takeIf { it.operationId == record.id }?.let { plan ->
                            artifacts.markFailed(plan.planId, detail)?.let {
                                ArtifactSyncCoordinator.broadcast(appContext, it, showPrompt = false)
                            }
                        }
                    }
                    // Cancels the worker or job that hangs, and takes the running notification down.
                    broadcastFinished(appContext, record.id)
                }
            }
        }
    }

    fun broadcastFinished(context: Context, operationId: String? = null) {
        val appContext = context.applicationContext
        dismissRunningNotificationIfIdle(appContext)
        appContext.sendBroadcast(
            Intent(ACTION_FINISHED).setPackage(appContext.packageName),
        )
        val terminal = operationId?.let { id ->
            SyncOperationStore(appContext).operation(id)
                ?.takeIf { isTerminalSyncOperationStatus(it.status) }
        }
        if (terminal != null) {
            // HTML editions follow every Sync in the background; they never lengthen it.
            runCatching { com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionSyncWorker.enqueue(appContext) }
            WorkManager.getInstance(appContext).cancelAllWorkByTag(syncOperationTag(terminal.id))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                appContext.getSystemService(JobScheduler::class.java)
                    .cancel(userInitiatedSyncJobId(terminal.id))
            }
            markStopped(terminal.id)
        }
    }

    fun cancelLegacyReadOnlyRefresh(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(LEGACY_LIBRARY_HEAD_WORK_NAME)
    }

    /** A separate gate cannot block a subsequent mobile-approved sync in WORK_NAME. */
    fun enqueueTransferWifiWait(context: Context, plan: SyncTransferPlan) {
        val operationId = plan.operationId
        val request = OneTimeWorkRequestBuilder<SyncTransferWifiWorker>()
            .setConstraints(androidx.work.Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInputData(workDataOf(INPUT_OPERATION_ID to operationId))
            .addTag(syncOperationTag(operationId))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            transferWifiWorkName(operationId), ExistingWorkPolicy.KEEP, request,
        ).result.get(10, TimeUnit.SECONDS)
    }

    fun cancelTransferWifiWait(context: Context, operationId: String?) {
        operationId ?: return
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(transferWifiWorkName(operationId))
    }

    internal fun continueTransferAfterWifi(context: Context, plan: SyncTransferPlan) {
        enqueueWorkManager(
            context.applicationContext,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            explicit = true,
            targetPdfFilename = plan.targetPdfFilename,
            operationId = plan.operationId,
            building = plan.building,
            requiredNetworkType = NetworkType.UNMETERED,
            executionPolicy = explicitSyncExecutionPolicy(
                ExplicitSyncOrigin.BACKGROUND_CONTINUATION, Build.VERSION.SDK_INT,
            ),
        ).result.get(10, TimeUnit.SECONDS)
    }

    private fun enqueueWorkManager(
        context: Context,
        policy: ExistingWorkPolicy,
        explicit: Boolean,
        targetPdfFilename: String?,
        operationId: String?,
        building: Boolean,
        requiredNetworkType: NetworkType = NetworkType.CONNECTED,
        executionPolicy: ExplicitSyncExecutionPolicy,
    ): androidx.work.Operation {
        val request = OneTimeWorkRequestBuilder<ThoughtsSyncWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(requiredNetworkType)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    SYNC_ENQUEUED_NANOS to SystemClock.elapsedRealtimeNanos(),
                    SYNC_ENQUEUED_BOOT to syncBootCount(context),
                    INPUT_EXPLICIT to explicit,
                    INPUT_TARGET_PDF to targetPdfFilename,
                    INPUT_OPERATION_ID to operationId,
                    INPUT_BUILDING to building,
                    SYNC_INPUT_RUN_IN_FOREGROUND to executionPolicy.runWorkerInForeground,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .apply { operationId?.let { addTag(syncOperationTag(it)) } }
            .apply {
                if (executionPolicy.expediteWorkManager) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
        operationId?.let {
            SyncOperationStore(context).recordScheduler(it, "work_manager", request.id.toString())
        }
        return SyncDiagnostics(context, operationId).measure("sync_enqueue_work_manager") {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }

    private fun scheduleUserInitiatedJob(
        context: Context,
        targetPdfFilename: String?,
        operationId: String,
        building: Boolean,
        requiredNetworkType: NetworkType,
    ): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val jobNetworkType = if (requiredNetworkType == NetworkType.UNMETERED) {
            JobInfo.NETWORK_TYPE_UNMETERED
        } else {
            JobInfo.NETWORK_TYPE_ANY
        }
        val info = JobInfo.Builder(
            userInitiatedSyncJobId(operationId),
            ComponentName(context, ThoughtsSyncJobService::class.java),
        )
            .setRequiredNetworkType(jobNetworkType)
            .setUserInitiated(true)
            .setEstimatedNetworkBytes(
                JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
            )
            .setExtras(PersistableBundle().apply {
                putLong(SYNC_ENQUEUED_NANOS, SystemClock.elapsedRealtimeNanos())
                putInt(SYNC_ENQUEUED_BOOT, syncBootCount(context))
                putBoolean(INPUT_EXPLICIT, true)
                targetPdfFilename?.let { putString(INPUT_TARGET_PDF, it) }
                putString(INPUT_OPERATION_ID, operationId)
                putBoolean(INPUT_BUILDING, building)
            })
            .build()
        val scheduleResult = SyncDiagnostics(context, operationId).measure("sync_enqueue_job_scheduler") {
            scheduler.schedule(info)
        }
        if (shouldFallbackFromUserInitiatedJob(scheduleResult)) {
            Log.w(
                SYNC_LOG_TAG,
                "Android declined the user-initiated Thoughts sync; using WorkManager",
            )
            return false
        }
        SyncOperationStore(context).recordScheduler(
            operationId,
            "job_scheduler",
            userInitiatedSyncJobId(operationId).toString(),
        )
        return true
    }

    fun enqueueArtifacts(
        context: Context,
        allowMetered: Boolean,
        replace: Boolean = false,
    ) {
        val plan = ArtifactSyncStateStore(context).load() ?: return
        showRunningNotification(context)
        val meteredAllowed = allowMetered &&
            (plan.canRunOnMeteredWithoutApproval || plan.meteredApproved)
        val request = OneTimeWorkRequestBuilder<ThoughtsArtifactSyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(
                        artifactRequiredNetworkType(plan, meteredAllowed),
                    )
                    .build(),
            )
            .setInputData(
                workDataOf(
                    INPUT_PLAN_ID to plan.planId,
                    TERMINAL_ARTIFACT_NETWORK_POLICY_INPUT to if (
                        plan.postTerminalBuildRefresh
                    ) {
                        TERMINAL_ARTIFACT_NETWORK_POLICY_VERSION
                    } else {
                        0
                    },
                ),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .apply { plan.operationId?.let { addTag(syncOperationTag(it)) } }
            .build()
        plan.operationId?.let {
            SyncOperationStore(context).recordScheduler(it, "work_manager", request.id.toString())
        }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ARTIFACT_WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueTerminalArtifactRefresh(
        context: Context,
        planId: String,
        replace: Boolean = true,
    ) {
        val request = OneTimeWorkRequestBuilder<ThoughtsTerminalArtifactRefreshWorker>()
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(TERMINAL_ARTIFACT_WORK_NETWORK_TYPE)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    INPUT_PLAN_ID to planId,
                    TERMINAL_ARTIFACT_NETWORK_POLICY_INPUT to
                        TERMINAL_ARTIFACT_NETWORK_POLICY_VERSION,
                ),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            TERMINAL_ARTIFACT_REFRESH_WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun notification(
        context: Context,
        running: Boolean = true,
        @Suppress("UNUSED_PARAMETER") building: Boolean = false,
    ): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Server updates", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(if (running) "Syncing…" else "Synced")
            .setContentText(if (running) "Thoughts is syncing with the server" else "")
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    fun artifactNotification(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Server updates", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            2,
            Intent(context, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle("Syncing…")
            .setContentText("Thoughts is syncing with the server")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    fun showRunningNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).notify(
            ARTIFACT_NOTIFICATION_ID,
            artifactNotification(context),
        )
        // Every Sync that starts or goes on is watched, and takes older leftovers with it.
        SyncStallWatchdog.ensureRunning(context)
    }

    fun dismissRunningNotificationIfIdle(context: Context) {
        if (SyncOperationStore(context).pendingOperations().isEmpty()) {
            ThoughtsSyncForegroundService.stop(context)
            context.getSystemService(NotificationManager::class.java)
                .cancel(ARTIFACT_NOTIFICATION_ID)
        }
    }
}

internal fun artifactRequiredNetworkType(
    plan: ArtifactSyncPlan,
    allowMetered: Boolean,
): NetworkType = when {
    plan.postTerminalBuildRefresh -> TERMINAL_ARTIFACT_WORK_NETWORK_TYPE
    allowMetered && (plan.canRunOnMeteredWithoutApproval || plan.meteredApproved) ->
        NetworkType.CONNECTED
    else -> NetworkType.UNMETERED
}

internal val TERMINAL_ARTIFACT_WORK_NETWORK_TYPE = NetworkType.NOT_REQUIRED
internal const val TERMINAL_ARTIFACT_NETWORK_POLICY_VERSION = 1
internal const val TERMINAL_ARTIFACT_NETWORK_POLICY_INPUT =
    "terminal_artifact_network_policy_version"

internal fun appliesTerminalArtifactNetworkPolicy(inputVersion: Int): Boolean =
    inputVersion == TERMINAL_ARTIFACT_NETWORK_POLICY_VERSION

internal enum class TerminalArtifactResumeAction {
    NONE,
    REPLAN,
    DOWNLOAD,
}

internal fun terminalArtifactResumeAction(
    artifactPlan: ArtifactSyncPlan?,
    transferPlan: SyncTransferPlan?,
): TerminalArtifactResumeAction = when {
    artifactPlan == null || artifactPlan.status == ArtifactSyncPlan.STATUS_FAILED ||
        (!artifactPlan.postTerminalBuildRefresh && !artifactPlan.artifactOnlyRefresh) ->
        TerminalArtifactResumeAction.NONE
    transferPlan?.operationId == artifactPlan.operationId && transferPlan?.covers(artifactPlan) == true ->
        TerminalArtifactResumeAction.DOWNLOAD
    else -> TerminalArtifactResumeAction.REPLAN
}

internal fun shouldReplaceTerminalArtifactWorkOnResume(plan: ArtifactSyncPlan): Boolean =
    plan.postTerminalBuildRefresh && !plan.terminalNetworkPolicyApplied

internal fun artifactTransferNeedsUnmeteredApproval(
    plan: ArtifactSyncPlan,
    unmetered: Boolean,
): Boolean = !unmetered && !plan.canRunOnMeteredWithoutApproval && !plan.meteredApproved

/**
 * Whether an explicit Sync goes on in the background: only while source edits it sent are still
 * undelivered. A PDF that Fedora is still building no longer keeps it going (13 November 2026: a
 * Sync waited 44 of its 63 s for one); the Sync ends, saying how many PDFs are still building, and
 * [RemoteWorkWatch] fetches them once Fedora has finished.
 */
internal fun needsBackgroundSyncContinuation(
    sourceRequestsPending: Boolean,
    explicit: Boolean,
): Boolean = explicit && sourceRequestsPending

internal enum class ArtifactCompletionOutcome {
    ATTENTION,
    SYNCED,
}

internal fun artifactCompletionOutcome(
    sourceAttention: List<PendingSourceMutation>,
): ArtifactCompletionOutcome = when {
    sourceAttention.isNotEmpty() -> ArtifactCompletionOutcome.ATTENTION
    else -> ArtifactCompletionOutcome.SYNCED
}

/** Ends the Sync [operationId] as Completed, naming the PDFs Fedora is still building. */
internal fun completeSyncOperation(context: Context, operationId: String?) {
    val stillBuilding = runCatching { PendingFedoraBuildStore(context).pdfCount() }.getOrDefault(0)
    SyncOperationStore(context).completeSync(operationId, stillBuilding)
}

internal const val SOURCE_ATTENTION_MESSAGE = SyncOperationStore.STATUS_ATTENTION

internal fun sourceAttentionEvent(mutations: List<PendingSourceMutation>, installationProblems: List<String> = emptyList()): String =
    "$SOURCE_ATTENTION_MESSAGE. ${sourceAttentionDetail(mutations)}" +
        installationProblems.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = " ").orEmpty()

internal fun isRetryableSyncFailure(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { current ->
        current.cause?.takeUnless { it === current }
    }.take(16).toList().let { causes ->
        if (causes.any { it is TerminalPdfBuildException }) return@let false
        causes.any { cause ->
            when (cause) {
                is ServerException ->
                    cause.statusCode in setOf(408, 425, 429) || cause.statusCode >= 500
                is IOException -> true
                else -> false
            }
        }
    }

internal fun shouldRetrySyncFailure(error: Throwable, runAttemptCount: Int): Boolean =
    isRetryableSyncFailure(error) && runAttemptCount < MAX_SYNC_RUN_ATTEMPTS - 1

internal fun shouldRetryTerminalArtifactWork(
    error: Throwable,
    runAttemptCount: Int,
): Boolean {
    val causes = generateSequence(error as Throwable?) { current ->
        current.cause?.takeUnless { it === current }
    }.take(16).toList()
    if (causes.any { it is TerminalPdfBuildException }) return false
    if (causes.any { it is ArtifactPlanSupersededException }) {
        return runAttemptCount < MAX_SYNC_RUN_ATTEMPTS - 1
    }
    if (
        causes.any {
            it is UnknownHostException ||
                it is ConnectException ||
                it is NoRouteToHostException ||
                it is SocketTimeoutException ||
                it is SocketException
        }
    ) {
        return true
    }
    return shouldRetrySyncFailure(error, runAttemptCount)
}

internal fun transientRetryDelayMillis(retryNumber: Int, jitterMillis: Long): Long {
    require(retryNumber in 1..TRANSIENT_RETRY_BACKOFF_MILLIS.size)
    require(jitterMillis in 0..TRANSIENT_RETRY_JITTER_MILLIS)
    return TRANSIENT_RETRY_BACKOFF_MILLIS[retryNumber - 1] + jitterMillis
}

private suspend fun <T> withTransientSyncRetries(
    operationStore: SyncOperationStore,
    operationId: String?,
    block: suspend () -> T,
): T {
    var retryNumber = 0
    while (true) {
        try {
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!isRetryableSyncFailure(error) || retryNumber == TRANSIENT_RETRY_BACKOFF_MILLIS.size) {
                throw error
            }
            retryNumber += 1
            val waitMillis = transientRetryDelayMillis(
                retryNumber,
                Random.nextLong(TRANSIENT_RETRY_JITTER_MILLIS + 1),
            )
            operationStore.recordTransientRetry(
                operationId,
                retryNumber,
                waitMillis,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
            delay(waitMillis)
        }
    }
}

internal fun interruptedSyncFailureMessage(requiredVpnConnected: Boolean): String =
    if (requiredVpnConnected) {
        SyncOperationStore.USER_MESSAGE_INTERRUPTED
    } else {
        SyncOperationStore.USER_MESSAGE_TAILSCALE_OFF
    }

internal fun userFacingSyncFailureMessage(
    error: Throwable,
    requiredVpnConnected: Boolean = true,
): String {
    val causes = generateSequence(error as Throwable?) { current ->
        current.cause?.takeUnless { it === current }
    }.take(16).toList()
    causes.filterIsInstance<TerminalPdfBuildException>().firstOrNull()?.let { terminal ->
        return when (terminal.primaryStatus) {
            TerminalPdfBuildStatus.ATTENTION ->
                SyncOperationStore.USER_MESSAGE_PDF_BUILD_ATTENTION_TEX_SAVED
            TerminalPdfBuildStatus.CANCELLED ->
                SyncOperationStore.USER_MESSAGE_PDF_BUILD_CANCELLED_TEX_SAVED
            TerminalPdfBuildStatus.FAILED ->
                SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_TEX_SAVED
        }
    }
    causes.filterIsInstance<LibraryMutationConflictException>().firstOrNull()?.let { return it.userMessage }
    if (causes.any { it is ServerException }) return SyncOperationStore.USER_MESSAGE_REJECTED
    val connectionFailure = causes.any {
        it is ConnectException ||
            it is NoRouteToHostException ||
            it is SocketException ||
            it is SocketTimeoutException ||
            it is UnknownHostException ||
            it is SSLException
    }
    if (connectionFailure && !requiredVpnConnected) {
        return SyncOperationStore.USER_MESSAGE_TAILSCALE_OFF
    }
    if (connectionFailure) {
        return SyncOperationStore.USER_MESSAGE_CANT_CONNECT
    }
    return SyncOperationStore.USER_MESSAGE_SYNC_FAILED
}

class ThoughtsSyncJobService : JobService() {
    private var scope: CoroutineScope? = null
    private var runningOperationId: String? = null

    override fun onStartJob(params: JobParameters): Boolean {
        runningOperationId = params.extras.getString("operation_id")
        recordSyncWorkerEntry(applicationContext, runningOperationId, "job_scheduler",
            params.extras.getLong(SYNC_ENQUEUED_NANOS, 0), params.extras.getInt(SYNC_ENQUEUED_BOOT, -1))
        ThoughtsSyncScheduler.markRunning(runningOperationId)
        val building = params.extras.getBoolean("building", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setNotification(
                params,
                ThoughtsSyncScheduler.NOTIFICATION_ID,
                ThoughtsSyncScheduler.notification(
                    this,
                    building = building,
                ),
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
        val active = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = active
        active.launch {
            val explicit = params.extras.getBoolean("explicit", false)
            val targetPdfFilename = params.extras.getString("target_pdf")
            val operationId = params.extras.getString("operation_id")
            val operationStore = SyncOperationStore(applicationContext)
            fun continueAfterTransientFailure(error: Exception): Boolean {
                if (!isRetryableSyncFailure(error)) return false
                val detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
                operationStore.append(
                    operationId,
                    SyncOperationStore.STATUS_RETRYING,
                    "Temporary network problem; continuing sync in the background: ${detail.take(300)}",
                )
                return runCatching {
                    ThoughtsSyncScheduler.enqueueExplicitContinuation(
                        applicationContext,
                        targetPdfFilename,
                        operationId,
                        building,
                    )
                }.onSuccess {
                    ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
                }.isSuccess
            }
            var artifactTransferPending = false
            var syncFailed = false
            var buildsActive = false
            var sourceRequestsPending = false
            val transferAllowed = try {
                withTransientSyncRetries(operationStore, operationId) {
                    SyncTransferCoordinator.prepare(
                        applicationContext,
                        explicit,
                        targetPdfFilename,
                        operationId,
                        building,
                    )
                }
            } catch (error: Exception) {
                Log.e(SYNC_LOG_TAG, error.syncFailureMessage("Fedora sync preflight failed"))
                if (continueAfterTransientFailure(error)) {
                    finishJob(params, false)
                    return@launch
                }
                operationStore.fail(
                    operationId,
                    userFacingSyncFailureMessage(
                        error,
                        ThoughtsNetworkPolicy.requiredVpnActive(applicationContext),
                    ),
                    error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                )
                ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
                finishJob(params, false)
                return@launch
            }
            if (!transferAllowed) {
                finishJob(params, false)
                return@launch
            }
            operationStore.append(
                operationId,
                SyncOperationStore.STATUS_SYNCING,
                if (building) {
                    "Submitting the build request and syncing changes"
                } else {
                    "Syncing changes"
                },
            )
            val continueInBackground = try {
                val result = withTransientSyncRetries(operationStore, operationId) {
                    FedoraRepository(applicationContext).updateFromFedora(
                        explicit = explicit,
                        targetPdfFilename = targetPdfFilename,
                        operationId = operationId,
                    )
                }
                SyncTransferCoordinator.markControlTransferComplete(applicationContext, operationId)
                val installationProblems = OfflineSourceBundleStore(applicationContext).materializationProblems()
                if (result.sourceAttention.isNotEmpty() || (result.artifactPlan == null && installationProblems.isNotEmpty())) {
                    operationStore.attention(operationId, sourceAttentionEvent(result.sourceAttention, installationProblems))
                    syncFailed = true
                    ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
                    false
                } else {
                    artifactTransferPending = result.artifactPlan != null
                    result.artifactPlan?.let { plan ->
                        operationStore.recordTransfer(operationId, plan, completed = false)
                    }
                    buildsActive = result.buildsActive
                    sourceRequestsPending = result.scopeTargetPdfFilename == null &&
                        SourceEditQueue(applicationContext).hasUndeliveredRequests()
                    if (buildsActive || result.buildProgress.isNotEmpty()) {
                        // Kept with the operation; the phone does not wait for a running build.
                        operationStore.recordBuildProgress(
                            operationId,
                            result.buildProgress,
                            buildsActive = false,
                        )
                    }
                    val terminalBuild = result.terminalBuildFailures
                        .takeIf(List<TerminalPdfBuildFailure>::isNotEmpty)
                        ?.let(::TerminalPdfBuildException)
                    if (terminalBuild != null) operationStore.deferBuildFailure(operationId, terminalBuild)
                    needsBackgroundSyncContinuation(sourceRequestsPending, explicit)
                }
            } catch (error: CancellationException) {
                Log.w(SYNC_LOG_TAG, error.syncFailureMessage("Fedora sync job cancelled"))
                throw error
            } catch (error: Exception) {
                Log.e(SYNC_LOG_TAG, error.syncFailureMessage("Fedora sync job failed"))
                if (continueAfterTransientFailure(error)) {
                    finishJob(params, false)
                    return@launch
                }
                operationStore.fail(
                    operationId,
                    userFacingSyncFailureMessage(
                        error,
                        ThoughtsNetworkPolicy.requiredVpnActive(applicationContext),
                    ),
                    error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                )
                syncFailed = true
                ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
                false
            }
            if (continueInBackground) {
                operationStore.append(
                    operationId,
                    SyncOperationStore.STATUS_WAITING,
                    "Waiting for pending source changes",
                )
                finishJob(params, true)
                return@launch
            }
            if (!artifactTransferPending && !syncFailed) {
                SyncTransferStateStore(applicationContext).clearForOperation(operationId)
                completeSyncOperation(applicationContext, operationId)
                ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
            }
            finishJob(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope?.cancel()
        scope = null
        val operationId = params.extras.getString("operation_id")
        ThoughtsSyncScheduler.markStopped(operationId)
        runningOperationId = null
        return if (params.stopReason == JobParameters.STOP_REASON_USER) {
            SyncOperationStore(applicationContext).fail(
                operationId,
                SyncOperationStore.USER_MESSAGE_INTERRUPTED,
                "Sync execution was stopped by the user",
            )
            ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
            false
        } else {
            SyncOperationStore(applicationContext).append(
                operationId,
                SyncOperationStore.STATUS_QUEUED,
                "Android stopped sync execution; waiting to resume (reason ${params.stopReason})",
            )
            true
        }
    }

    override fun onNetworkChanged(params: JobParameters) = Unit

    override fun onDestroy() {
        ThoughtsSyncScheduler.markStopped(runningOperationId)
        runningOperationId = null
        scope?.cancel()
        super.onDestroy()
    }

    private fun finishJob(params: JobParameters, reschedule: Boolean) {
        ThoughtsSyncScheduler.markStopped(runningOperationId)
        runningOperationId = null
        jobFinished(params, reschedule)
    }
}

internal fun transferWifiWorkName(operationId: String): String = "thoughts-transfer-wifi-$operationId"

class SyncTransferWifiWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val operationId = inputData.getString("operation_id") ?: return Result.success()
        val store = SyncOperationStore(applicationContext)
        val plan = SyncTransferStateStore(applicationContext).load()
            ?.takeIf { it.operationId == operationId }
        if (!shouldResumeWaitingSyncTransfer(
            plan, store.operation(operationId), ArtifactSyncStateStore(applicationContext).load()?.operationId,
        )) return Result.success()
        // Recheck the live network: the Wi-Fi constraint may have changed after dispatch.
        if (!ThoughtsNetworkPolicy.isUnmetered(applicationContext)) return Result.retry()
        return try {
            store.append(operationId, SyncOperationStore.STATUS_QUEUED, "Wi-Fi available; resuming the waiting sync")
            ThoughtsSyncScheduler.continueTransferAfterWifi(applicationContext, requireNotNull(plan))
            SyncTransferCoordinator.dismissNotification(applicationContext)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(SYNC_LOG_TAG, "Could not schedule the waiting Wi-Fi sync", error)
            store.append(operationId, SyncOperationStore.STATUS_WAITING, "Waiting to retry Wi-Fi continuation scheduling")
            Result.retry()
        }
    }
}

class ThoughtsSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        ThoughtsSyncScheduler.NOTIFICATION_ID,
        ThoughtsSyncScheduler.notification(
            applicationContext,
            building = inputData.getBoolean("building", false),
        ),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        recordSyncWorkerEntry(applicationContext, inputData.getString("operation_id"), "work_manager",
            inputData.getLong(SYNC_ENQUEUED_NANOS, 0), inputData.getInt(SYNC_ENQUEUED_BOOT, -1))
        ThoughtsSyncScheduler.showRunningNotification(applicationContext)
        return try {
            runSyncWorkerWithForegroundPolicy(
                runInForeground = inputData.getBoolean(SYNC_INPUT_RUN_IN_FOREGROUND, false),
                promoteToForeground = { setForeground(getForegroundInfo()) },
                runSync = { runSyncPass() },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(SYNC_LOG_TAG, error.syncFailureMessage("Fedora sync worker failed"))
            val operationId = inputData.getString("operation_id")
            val operationStore = SyncOperationStore(applicationContext)
            val detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            if (shouldRetrySyncFailure(error, runAttemptCount)) {
                operationStore.append(
                    operationId,
                    SyncOperationStore.STATUS_RETRYING,
                    "Temporary network problem; retrying sync: ${detail.take(300)}",
                )
                ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
                Result.retry()
            } else {
                operationStore.fail(
                    operationId,
                    userFacingSyncFailureMessage(
                        error,
                        ThoughtsNetworkPolicy.requiredVpnActive(applicationContext),
                    ),
                    detail,
                )
                ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
                Result.failure()
            }
        }
    }

    private suspend fun runSyncPass(): Result {
        val operationId = inputData.getString("operation_id")
        val operationStore = SyncOperationStore(applicationContext)
        val transferAllowed = withTransientSyncRetries(operationStore, operationId) {
            SyncTransferCoordinator.prepare(
                applicationContext,
                inputData.getBoolean("explicit", false),
                inputData.getString("target_pdf"),
                operationId,
                inputData.getBoolean("building", false),
            )
        }
        if (!transferAllowed) {
            ThoughtsSyncScheduler.dismissRunningNotificationIfIdle(applicationContext)
            return Result.success()
        }
        operationStore.append(
            operationId,
            SyncOperationStore.STATUS_SYNCING,
            if (runAttemptCount > 0) {
                "Sync pass ${runAttemptCount + 1}"
            } else if (inputData.getBoolean("building", false)) {
                "Submitting the build request and syncing changes"
            } else {
                "Syncing changes"
            },
        )
        val result = withTransientSyncRetries(operationStore, operationId) {
            FedoraRepository(applicationContext).updateFromFedora(
                explicit = inputData.getBoolean("explicit", false),
                targetPdfFilename = inputData.getString("target_pdf"),
                operationId = operationId,
            )
        }
        SyncTransferCoordinator.markControlTransferComplete(applicationContext, operationId)
        val installationProblems = OfflineSourceBundleStore(applicationContext).materializationProblems()
        if (result.sourceAttention.isNotEmpty() || (result.artifactPlan == null && installationProblems.isNotEmpty())) {
            operationStore.attention(operationId, sourceAttentionEvent(result.sourceAttention, installationProblems))
            ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
            return Result.success()
        }
        val sourceRequestsPending = inputData.getBoolean("explicit", false) &&
            result.scopeTargetPdfFilename == null &&
            SourceEditQueue(applicationContext).hasUndeliveredRequests()
        result.artifactPlan?.let { plan ->
            operationStore.recordTransfer(operationId, plan, completed = false)
        }
        if (result.buildsActive || result.buildProgress.isNotEmpty()) {
            // Kept with the operation; the phone does not wait for a running build.
            operationStore.recordBuildProgress(
                operationId,
                result.buildProgress,
                buildsActive = false,
            )
        }
        result.terminalBuildFailures
            .takeIf(List<TerminalPdfBuildFailure>::isNotEmpty)
            ?.let(::TerminalPdfBuildException)
            ?.let { terminalBuild ->
                operationStore.deferBuildFailure(operationId, terminalBuild)
            }
        return if (sourceRequestsPending) {
            operationStore.append(
                operationId,
                SyncOperationStore.STATUS_WAITING,
                "Waiting for pending source changes",
            )
            Result.retry()
        } else {
            if (result.artifactPlan == null) {
                SyncTransferStateStore(applicationContext).clearForOperation(operationId)
                completeSyncOperation(applicationContext, operationId)
                ThoughtsSyncScheduler.broadcastFinished(applicationContext, operationId)
            }
            Result.success()
        }
    }
}

class ArtifactSyncActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val planId = intent?.getStringExtra(ArtifactSyncCoordinator.EXTRA_PLAN_ID) ?: return
        val store = ArtifactSyncStateStore(context)
        when (intent.action) {
            ACTION_USE_MOBILE -> {
                val plan = store.approveMetered(planId) ?: return
                SyncOperationStore(context).append(
                    plan.operationId,
                    SyncOperationStore.STATUS_QUEUED,
                    "Artifact update queued",
                )
                ArtifactSyncCoordinator.dismissNotification(context)
                ThoughtsSyncScheduler.enqueueArtifacts(context, allowMetered = true, replace = true)
                ArtifactSyncCoordinator.broadcast(context, plan, showPrompt = false)
            }
            ACTION_WAIT_UNMETERED -> {
                val plan = store.markWaiting(planId) ?: return
                SyncOperationStore(context).append(
                    plan.operationId,
                    SyncOperationStore.STATUS_WAITING,
                    "Waiting for an unmetered network",
                )
                ArtifactSyncCoordinator.dismissNotification(context)
                ThoughtsSyncScheduler.enqueueArtifacts(context, allowMetered = false)
                ArtifactSyncCoordinator.broadcast(context, plan, showPrompt = false)
            }
        }
    }

    companion object {
        const val ACTION_USE_MOBILE = "org.tbend.thoughts.action.USE_MOBILE_FOR_ARTIFACTS"
        const val ACTION_WAIT_UNMETERED = "org.tbend.thoughts.action.WAIT_UNMETERED_FOR_ARTIFACTS"
    }
}

class ThoughtsArtifactSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        ThoughtsSyncScheduler.ARTIFACT_NOTIFICATION_ID,
        ThoughtsSyncScheduler.artifactNotification(applicationContext),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    override suspend fun doWork(): Result {
        ThoughtsSyncScheduler.showRunningNotification(applicationContext)
        return try {
            runArtifactSync()
        } finally {
            ThoughtsSyncScheduler.dismissRunningNotificationIfIdle(applicationContext)
        }
    }

    private suspend fun runArtifactSync(): Result {
        val planId = inputData.getString("plan_id") ?: return Result.success()
        val store = ArtifactSyncStateStore(applicationContext)
        val plan = store.load()?.takeIf { it.planId == planId } ?: return Result.success()
        if (
            plan.postTerminalBuildRefresh &&
            appliesTerminalArtifactNetworkPolicy(
                inputData.getInt(TERMINAL_ARTIFACT_NETWORK_POLICY_INPUT, 0),
            )
        ) {
            store.markTerminalNetworkPolicyApplied(planId)
        }
        val sourceAttention = SourceEditQueue(applicationContext).blockingSourceMutations(
            plan.targetPdfFilenames.singleOrNull(),
        )
        if (!sourceMutationsAllowRender(sourceAttention)) {
            val detail = sourceAttentionEvent(sourceAttention)
            store.markFailed(planId, detail)?.let {
                ArtifactSyncCoordinator.broadcast(applicationContext, it, showPrompt = false)
            }
            SyncOperationStore(applicationContext).attention(plan.operationId, detail)
            ThoughtsSyncScheduler.broadcastFinished(applicationContext, plan.operationId)
            return Result.success()
        }
        // The artifact discovery estimate may only contain PDF bytes. The exact
        // matching transfer plan also includes source objects and metadata.
        val exactTransfer = SyncTransferStateStore(applicationContext).load()?.takeIf { it.covers(plan) }
        val networkPlan = exactTransfer?.let {
            plan.copy(
                estimatedTransferBytes = maxOf(it.uploadBytes, it.downloadBytes),
                thresholdBytes = it.thresholdBytes,
                meteredApproved = plan.meteredApproved || it.meteredApproved,
            )
        } ?: plan
        if (
            artifactTransferNeedsUnmeteredApproval(
                networkPlan,
                unmetered = ThoughtsNetworkPolicy.isUnmetered(applicationContext),
            )
        ) {
            store.markWaiting(planId)?.let {
                ArtifactSyncCoordinator.broadcast(applicationContext, it, showPrompt = false)
            }
            SyncOperationStore(applicationContext).append(
                plan.operationId,
                SyncOperationStore.STATUS_WAITING,
                "Waiting for an unmetered network",
            )
            return Result.retry()
        }
        return try {
            // WorkManager restores this protection on every attempt, including
            // after process death. A notification alone does not protect a worker.
            setForeground(getForegroundInfo())
            store.markTransferring(
                planId,
                preserveError = plan.postTerminalBuildRefresh,
            )?.let {
                ArtifactSyncCoordinator.broadcast(applicationContext, it, showPrompt = false)
            }
            SyncOperationStore(applicationContext).append(
                plan.operationId,
                SyncOperationStore.STATUS_SYNCING,
                "Downloading the artifact update",
            )
            val operationStore = SyncOperationStore(applicationContext)
            val diagnostics = SyncDiagnostics(applicationContext, plan.operationId)
            diagnostics.count("artifact_attempt", "attempt" to runAttemptCount.toLong(),
                "target_documents" to plan.targetPdfFilenames.size.toLong())
            diagnostics.measureSuspending("artifact_repository_sync") {
                withTransientSyncRetries(operationStore, plan.operationId) {
                    FedoraRepository(applicationContext).syncArtifacts(planId)
                }
            }
            diagnostics.measure("mark_transfer_complete") {
                SyncTransferCoordinator.markTransferComplete(applicationContext, plan.operationId)
                operationStore.recordTransfer(plan.operationId, plan, completed = true)
                SyncTransferStateStore(applicationContext).clearForOperation(plan.operationId)
            }
            val completionSourceAttention = diagnostics.measure("check_completion_source_queue") {
                SourceEditQueue(applicationContext).blockingSourceMutations(plan.targetPdfFilenames.singleOrNull())
            }
            val installationProblems = diagnostics.measure("check_source_installation_receipts") {
                OfflineSourceBundleStore(applicationContext).materializationProblems()
            }
            val completion = if (installationProblems.isNotEmpty()) ArtifactCompletionOutcome.ATTENTION else artifactCompletionOutcome(completionSourceAttention)
            withContext(Dispatchers.IO) { SyncDiagnosticBatcher.flushAll() }
            when (completion) {
                ArtifactCompletionOutcome.ATTENTION -> {
                    operationStore.attention(
                        plan.operationId,
                        sourceAttentionEvent(completionSourceAttention, installationProblems),
                    )
                }
                // Builds still running on Fedora do not keep this Sync going: RemoteWorkWatch
                // fetches their PDFs when they are done ([needsBackgroundSyncContinuation]).
                ArtifactCompletionOutcome.SYNCED -> diagnostics.measure("complete_operation") {
                    completeSyncOperation(applicationContext, plan.operationId)
                }
            }
            ThoughtsSyncScheduler.broadcastFinished(applicationContext, plan.operationId)
            ArtifactSyncCoordinator.dismissNotification(applicationContext)
            Result.success()
        } catch (error: ArtifactPlanSupersededException) {
            Log.w(SYNC_LOG_TAG, "Artifact plan changed during transfer; refreshing it", error)
            store.markQueued(planId)?.let {
                ArtifactSyncCoordinator.broadcast(applicationContext, it, showPrompt = false)
            }
            val refreshPlan = store.publish(plan.copy(artifactOnlyRefresh = true))
            SyncOperationStore(applicationContext).append(
                plan.operationId,
                SyncOperationStore.STATUS_QUEUED,
                "Refreshing the artifact download plan without rebuilding",
            )
            ThoughtsSyncScheduler.enqueueTerminalArtifactRefresh(applicationContext, refreshPlan.planId)
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isForegroundSyncStartRestricted(error)) {
                SyncOperationStore(applicationContext).append(
                    plan.operationId, SyncOperationStore.STATUS_RETRYING,
                    "Android deferred foreground sync; retrying when execution is allowed",
                )
                return Result.retry()
            }
            val detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            val shouldRetry = if (plan.postTerminalBuildRefresh) {
                shouldRetryTerminalArtifactWork(error, runAttemptCount)
            } else {
                shouldRetrySyncFailure(error, runAttemptCount)
            }
            if (shouldRetry) {
                val updated = store.markRetrying(planId, detail)
                Log.w(
                    SYNC_LOG_TAG,
                    "Artifact download remains queued after a temporary problem: $detail",
                    error,
                )
                updated?.let {
                    ArtifactSyncCoordinator.broadcast(applicationContext, it, showPrompt = false)
                }
                SyncOperationStore(applicationContext).append(
                    plan.operationId,
                    SyncOperationStore.STATUS_RETRYING,
                    if (plan.postTerminalBuildRefresh) {
                        "Healthy artifact download will retry: ${detail.take(300)}"
                    } else {
                        "Temporary network problem; retrying artifact update: ${detail.take(300)}"
                    },
                )
                ThoughtsSyncScheduler.broadcastFinished(applicationContext, plan.operationId)
                return Result.retry()
            }
            Log.e(SYNC_LOG_TAG, error.syncFailureMessage("Fedora artifact sync failed"))
            val updated = store.markFailed(planId, detail)
            updated?.let {
                ArtifactSyncCoordinator.broadcast(applicationContext, it, showPrompt = false)
            }
            SyncOperationStore(applicationContext).fail(
                plan.operationId,
                userFacingSyncFailureMessage(
                    error,
                    ThoughtsNetworkPolicy.requiredVpnActive(applicationContext),
                ),
                detail,
            )
            ThoughtsSyncScheduler.broadcastFinished(applicationContext, plan.operationId)
            Result.failure()
        }
    }
}

class ThoughtsTerminalArtifactRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val planId = inputData.getString("plan_id") ?: return Result.success()
        val artifactStore = ArtifactSyncStateStore(applicationContext)
        val plan = artifactStore.load()?.takeIf {
            it.planId == planId && (it.postTerminalBuildRefresh || it.artifactOnlyRefresh)
        } ?: return Result.success()
        if (
            plan.postTerminalBuildRefresh && appliesTerminalArtifactNetworkPolicy(
                inputData.getInt(TERMINAL_ARTIFACT_NETWORK_POLICY_INPUT, 0),
            )
        ) {
            artifactStore.markTerminalNetworkPolicyApplied(planId)
        }
        val operationStore = SyncOperationStore(applicationContext)
        return try {
            val refreshed = FedoraRepository(applicationContext).refreshTerminalArtifactPlan(plan)
            refreshed?.let {
                operationStore.recordTransfer(it.operationId, it, completed = false)
            }
            if (refreshed == null) completeSyncOperation(applicationContext, plan.operationId)
            ThoughtsSyncScheduler.broadcastFinished(applicationContext, plan.operationId)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            if (shouldRetryTerminalArtifactWork(error, runAttemptCount)) {
                Log.w(
                    SYNC_LOG_TAG,
                    "Terminal artifact replan remains queued: $detail",
                    error,
                )
                artifactStore.markRetrying(planId, detail)?.let {
                    ArtifactSyncCoordinator.broadcast(applicationContext, it, showPrompt = false)
                }
                operationStore.append(
                    plan.operationId,
                    SyncOperationStore.STATUS_QUEUED,
                    "Healthy artifact refresh will retry: ${detail.take(300)}",
                )
                Result.retry()
            } else {
                artifactStore.markFailed(planId, detail)?.let {
                    ArtifactSyncCoordinator.broadcast(applicationContext, it, showPrompt = false)
                }
                operationStore.fail(
                    plan.operationId,
                    userFacingSyncFailureMessage(
                        error,
                        ThoughtsNetworkPolicy.requiredVpnActive(applicationContext),
                    ),
                    detail,
                )
                ThoughtsSyncScheduler.broadcastFinished(applicationContext, plan.operationId)
                Result.failure()
            }
        }
    }
}

private const val SYNC_LOG_TAG = "ThoughtsSync"
// Android caches failed DNS lookups for about ten seconds, so the final retry
// must occur after that negative-cache window instead of repeating the same miss.
private val TRANSIENT_RETRY_BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L, 12_000L)
private const val TRANSIENT_RETRY_JITTER_MILLIS = 250L
private const val MAX_SYNC_RUN_ATTEMPTS = 4

private fun Throwable.syncFailureMessage(prefix: String): String =
    "$prefix: ${javaClass.name}: ${message.orEmpty()}\n${stackTraceToString()}"

private fun isSafePdfFilename(filename: String): Boolean =
    filename.endsWith(".pdf", ignoreCase = true) &&
        filename.length > 4 &&
        filename == File(filename).name &&
        !filename.contains('/') &&
        !filename.contains('\\')
