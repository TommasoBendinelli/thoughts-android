// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

data class ArtifactSyncPlan(
    val planId: String,
    val libraryRevision: String,
    val sourceBundleSha256: String,
    val changedPdfCount: Int,
    val estimatedTransferBytes: Long,
    val thresholdBytes: Long =
        Preferences.synchronizationThresholdMegabytesDefault * 1_000_000L,
    val sourceIndexChanged: Boolean,
    val sourceBundleChanged: Boolean,
    val postTerminalBuildRefresh: Boolean = false,
    val artifactOnlyRefresh: Boolean = false,
    val planRefreshCount: Int = 0,
    val terminalNetworkPolicyApplied: Boolean = false,
    val buildsActive: Boolean = false,
    val targetPdfFilenames: List<String> = emptyList(),
    val targetPublicationSha256: String = "",
    val targetPublicationSizeBytes: Long = -1,
    val operationId: String? = null,
    val explicitRequest: Boolean = false,
    val promptShown: Boolean = false,
    val meteredApproved: Boolean = false,
    val waitForUnmetered: Boolean = false,
    val status: String = STATUS_WAITING_NETWORK,
    val error: String = "",
) {
    val requiresBulkTransfer: Boolean
        get() = estimatedTransferBytes > 0 || sourceIndexChanged || sourceBundleChanged

    val canRunOnMeteredWithoutApproval: Boolean
        get() = estimatedTransferBytes <= thresholdBytes

    companion object {
        const val STATUS_WAITING_NETWORK = "waiting_network"
        const val STATUS_TRANSFERRING = "transferring"
        const val STATUS_QUEUED = "queued"
        const val STATUS_RETRYING = "retrying"
        const val STATUS_FAILED = "failed"
        internal const val LEGACY_STATUS_WAITING = "waiting"
    }
}

internal fun activatedPdfUpdates(
    previous: Map<String, String>,
    current: Map<String, String>,
): Map<String, String> = current
    .toSortedMap()
    .filter { (filename, sha256) -> previous[filename] != sha256 }

internal fun shouldReplaceArtifactWork(
    previousPlanId: String?,
    nextPlanId: String,
    forceReplaceWork: Boolean,
): Boolean = forceReplaceWork || previousPlanId != nextPlanId

internal fun startsNewExplicitArtifactAttempt(
    current: ArtifactSyncPlan?,
    candidate: ArtifactSyncPlan,
): Boolean = current?.planId == candidate.planId &&
    candidate.explicitRequest &&
    candidate.operationId != null &&
    candidate.operationId != current.operationId

internal fun shouldResumeArtifactWork(plan: ArtifactSyncPlan): Boolean =
    !plan.postTerminalBuildRefresh && !plan.artifactOnlyRefresh && plan.status in setOf(
        ArtifactSyncPlan.STATUS_WAITING_NETWORK,
        ArtifactSyncPlan.STATUS_QUEUED,
        ArtifactSyncPlan.STATUS_TRANSFERRING,
        ArtifactSyncPlan.STATUS_RETRYING,
    )

class ArtifactSyncStateStore internal constructor(
    private val stateFile: File,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "thoughts-sync/artifact-plan.json"),
    )

    @Synchronized
    fun load(): ArtifactSyncPlan? {
        if (!stateFile.isFile || stateFile.length() !in 1..MAX_STATE_BYTES) return null
        return runCatching {
            val value = JsonParser.parseString(stateFile.readText(StandardCharsets.UTF_8)).asJsonObject
            check(value.int("schema_version") == SCHEMA_VERSION)
            ArtifactSyncPlan(
                planId = value.string("plan_id").also { require(it.matches(SHA256)) },
                libraryRevision = value.string("library_revision"),
                sourceBundleSha256 = value.string("source_bundle_sha256"),
                changedPdfCount = value.int("changed_pdf_count").coerceAtLeast(0),
                estimatedTransferBytes = value.long("estimated_transfer_bytes").coerceAtLeast(0),
                thresholdBytes = value.long("threshold_bytes").takeIf { it > 0 }
                    ?: Preferences.synchronizationThresholdMegabytesDefault * 1_000_000L,
                sourceIndexChanged = value.boolean("source_index_changed"),
                sourceBundleChanged = value.boolean("source_bundle_changed"),
                postTerminalBuildRefresh = value.boolean("post_terminal_build_refresh"),
                artifactOnlyRefresh = value.boolean("artifact_only_refresh"),
                planRefreshCount = value.get("plan_refresh_count")?.asInt ?: 0,
                terminalNetworkPolicyApplied = value.boolean("terminal_network_policy_applied"),
                buildsActive = value.boolean("builds_active"),
                targetPdfFilenames = (value.getAsJsonArray("target_pdf_filenames") ?: JsonArray())
                    .mapNotNull { element ->
                        element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                            ?.asString
                            ?.takeIf(::isSafePdfFilename)
                    }
                    .distinct(),
                targetPublicationSha256 = value.string("target_publication_sha256")
                    .takeIf(SHA256::matches)
                    .orEmpty(),
                targetPublicationSizeBytes = value.long("target_publication_size_bytes")
                    .takeIf { it >= 0 }
                    ?: -1,
                operationId = value.string("operation_id")
                    .takeIf { runCatching { UUID.fromString(it) }.isSuccess },
                explicitRequest = value.boolean("explicit_request"),
                promptShown = value.boolean("prompt_shown"),
                meteredApproved = value.boolean("metered_approved"),
                waitForUnmetered = value.boolean("wait_for_unmetered"),
                status = when (val status = value.string("status")) {
                    ArtifactSyncPlan.LEGACY_STATUS_WAITING ->
                        ArtifactSyncPlan.STATUS_WAITING_NETWORK
                    ArtifactSyncPlan.STATUS_WAITING_NETWORK,
                    ArtifactSyncPlan.STATUS_QUEUED,
                    ArtifactSyncPlan.STATUS_TRANSFERRING,
                    ArtifactSyncPlan.STATUS_RETRYING,
                    ArtifactSyncPlan.STATUS_FAILED -> status
                    else -> ArtifactSyncPlan.STATUS_WAITING_NETWORK
                },
                error = value.string("error").take(600),
            )
        }.getOrNull()
    }

    @Synchronized
    fun publish(candidate: ArtifactSyncPlan): ArtifactSyncPlan {
        val current = load()?.takeIf { it.planId == candidate.planId }
        val newExplicitAttempt = startsNewExplicitArtifactAttempt(current, candidate)
        val merged = candidate.copy(
            explicitRequest = candidate.explicitRequest || current?.explicitRequest == true,
            promptShown = if (newExplicitAttempt) {
                candidate.promptShown
            } else {
                current?.promptShown == true
            },
            meteredApproved = if (newExplicitAttempt) {
                candidate.meteredApproved
            } else {
                candidate.meteredApproved || current?.meteredApproved == true
            },
            terminalNetworkPolicyApplied = if (newExplicitAttempt) {
                candidate.terminalNetworkPolicyApplied
            } else {
                candidate.terminalNetworkPolicyApplied ||
                    current?.terminalNetworkPolicyApplied == true
            },
            waitForUnmetered = if (newExplicitAttempt) {
                candidate.waitForUnmetered
            } else {
                current?.waitForUnmetered == true
            },
            status = if (newExplicitAttempt) candidate.status else current?.status ?: candidate.status,
            error = if (newExplicitAttempt) candidate.error else current?.error.orEmpty(),
        )
        write(merged)
        return merged
    }

    @Synchronized
    fun markPromptShown(planId: String): ArtifactSyncPlan? = update(planId) {
        it.copy(promptShown = true)
    }

    @Synchronized
    fun approveMetered(planId: String): ArtifactSyncPlan? = update(planId) {
        it.copy(
            meteredApproved = true,
            waitForUnmetered = false,
            promptShown = true,
            status = ArtifactSyncPlan.STATUS_QUEUED,
            error = "",
        )
    }

    @Synchronized
    fun markWaiting(planId: String): ArtifactSyncPlan? = update(planId) {
        it.copy(
            promptShown = true,
            meteredApproved = false,
            waitForUnmetered = true,
            status = ArtifactSyncPlan.STATUS_WAITING_NETWORK,
            error = "",
        )
    }

    @Synchronized
    fun markAwaitingMeteredDecision(planId: String): ArtifactSyncPlan? = update(planId) {
        it.copy(
            promptShown = true,
            status = ArtifactSyncPlan.STATUS_WAITING_NETWORK,
            error = "",
        )
    }

    @Synchronized
    fun markQueued(planId: String): ArtifactSyncPlan? = update(planId) {
        it.copy(status = ArtifactSyncPlan.STATUS_QUEUED, error = "")
    }

    @Synchronized
    fun markTransferring(
        planId: String,
        preserveError: Boolean = false,
    ): ArtifactSyncPlan? = update(planId) {
        it.copy(
            status = ArtifactSyncPlan.STATUS_TRANSFERRING,
            error = if (preserveError) it.error else "",
        )
    }

    @Synchronized
    fun markRetrying(planId: String, error: String): ArtifactSyncPlan? = update(planId) {
        it.copy(status = ArtifactSyncPlan.STATUS_RETRYING, error = error.take(600))
    }

    @Synchronized
    fun markTerminalNetworkPolicyApplied(planId: String): ArtifactSyncPlan? = update(planId) {
        it.copy(terminalNetworkPolicyApplied = true)
    }

    @Synchronized
    fun markFailed(planId: String, error: String): ArtifactSyncPlan? = update(planId) {
        it.copy(status = ArtifactSyncPlan.STATUS_FAILED, error = error.take(600))
    }

    @Synchronized
    fun clear(planId: String): Boolean {
        val current = load() ?: return true
        if (current.planId != planId) return false
        return !stateFile.exists() || stateFile.delete()
    }

    private fun update(planId: String, transform: (ArtifactSyncPlan) -> ArtifactSyncPlan): ArtifactSyncPlan? {
        val current = load()?.takeIf { it.planId == planId } ?: return null
        return transform(current).also(::write)
    }

    private fun write(plan: ArtifactSyncPlan) {
        val value = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("plan_id", plan.planId)
            addProperty("library_revision", plan.libraryRevision)
            addProperty("source_bundle_sha256", plan.sourceBundleSha256)
            addProperty("changed_pdf_count", plan.changedPdfCount)
            addProperty("estimated_transfer_bytes", plan.estimatedTransferBytes)
            addProperty("threshold_bytes", plan.thresholdBytes)
            addProperty("source_index_changed", plan.sourceIndexChanged)
            addProperty("source_bundle_changed", plan.sourceBundleChanged)
            addProperty("post_terminal_build_refresh", plan.postTerminalBuildRefresh)
            addProperty("artifact_only_refresh", plan.artifactOnlyRefresh)
            addProperty("plan_refresh_count", plan.planRefreshCount)
            addProperty("terminal_network_policy_applied", plan.terminalNetworkPolicyApplied)
            addProperty("builds_active", plan.buildsActive)
            add("target_pdf_filenames", JsonArray().apply {
                plan.targetPdfFilenames.distinct().sorted().forEach(::add)
            })
            plan.targetPublicationSha256.takeIf(String::isNotBlank)?.let {
                require(it.matches(SHA256))
                addProperty("target_publication_sha256", it)
                addProperty("target_publication_size_bytes", plan.targetPublicationSizeBytes)
            }
            plan.operationId?.let { addProperty("operation_id", it) }
            addProperty("explicit_request", plan.explicitRequest)
            addProperty("prompt_shown", plan.promptShown)
            addProperty("metered_approved", plan.meteredApproved)
            addProperty("wait_for_unmetered", plan.waitForUnmetered)
            addProperty("status", plan.status)
            addProperty("error", plan.error)
        }
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, ".${stateFile.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write((value.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(stateFile)) { "Could not publish the pending artifact update" }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_STATE_BYTES = 100_000L
        val SHA256 = Regex("^[0-9a-f]{64}$")

        fun JsonObject.string(name: String): String = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            .orEmpty()

        fun JsonObject.int(name: String): Int = get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        fun JsonObject.long(name: String): Long = get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0
        fun JsonObject.boolean(name: String): Boolean =
            get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean == true

        fun isSafePdfFilename(filename: String): Boolean =
            filename.endsWith(".pdf", ignoreCase = true) &&
                filename.length > 4 &&
                filename == File(filename).name &&
                !filename.contains('/') &&
                !filename.contains('\\')
    }
}

object ThoughtsNetworkPolicy {
    /**
     * False only when the saved Thoughts server is a Tailscale (`*.ts.net`) address and no VPN
     * transport is active; a public HTTPS server needs no VPN precheck.
     */
    fun requiredVpnActive(context: Context): Boolean =
        ThoughtsServerAddress.vpnPrecheckPasses(
            ThoughtsServerSecretStore(context).baseUrl(),
        ) { hasActiveVpn(context) }

    fun hasActiveVpn(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

object ArtifactSyncCoordinator {
    const val ACTION_STATE_CHANGED = "org.tbend.thoughts.action.ARTIFACT_SYNC_STATE_CHANGED"
    const val ACTION_PDFS_ACTIVATED = "org.tbend.thoughts.action.PDFS_ACTIVATED"
    const val EXTRA_PLAN_ID = "artifact_plan_id"
    const val EXTRA_SHOW_PROMPT = "show_artifact_prompt"
    const val EXTRA_PDF_FILENAMES = "activated_pdf_filenames"
    const val EXTRA_PDF_SHA256 = "activated_pdf_sha256"

    private const val CHANNEL_ID = "thoughts-artifact-updates"
    private const val NOTIFICATION_ID = 0x4d4c

    fun publish(
        context: Context,
        candidate: ArtifactSyncPlan,
        downloadNow: Boolean = false,
        forceReplaceWork: Boolean = false,
    ) {
        val appContext = context.applicationContext
        val store = ArtifactSyncStateStore(appContext)
        val previousPlan = store.load()
        val newExplicitAttempt = startsNewExplicitArtifactAttempt(previousPlan, candidate)
        val previousPlanId = previousPlan?.planId
        val threshold = Preferences(
            PreferenceManager.getDefaultSharedPreferences(appContext),
        ).getSynchronizationThresholdBytes()
        val transferPlan = SyncTransferStateStore(appContext).load()
        val coveredByControlPlan = transferPlan?.covers(candidate) == true &&
            transferPlan.canRunOnMetered
        var plan = store.publish(
            candidate.copy(
                thresholdBytes = threshold,
                estimatedTransferBytes = transferPlan?.takeIf { it.covers(candidate) }?.let {
                    maxOf(it.uploadBytes, it.downloadBytes)
                } ?: candidate.estimatedTransferBytes,
                meteredApproved = candidate.meteredApproved || coveredByControlPlan,
            ),
        )
        val replaceExistingWork = shouldReplaceArtifactWork(
            previousPlanId,
            plan.planId,
            forceReplaceWork || newExplicitAttempt,
        )
        if (downloadNow) {
            plan = store.approveMetered(plan.planId) ?: plan
            SyncOperationStore(appContext).append(
                plan.operationId,
                SyncOperationStore.STATUS_QUEUED,
                "Artifact update queued",
            )
            ThoughtsSyncScheduler.enqueueArtifacts(
                appContext,
                allowMetered = true,
                replace = true,
            )
            broadcast(appContext, plan, showPrompt = false)
            return
        }
        if (previousPlanId == plan.planId && plan.status == ArtifactSyncPlan.STATUS_FAILED) {
            broadcast(appContext, plan, showPrompt = false)
            return
        }
        val unmetered = ThoughtsNetworkPolicy.isUnmetered(appContext)
        if (unmetered || plan.canRunOnMeteredWithoutApproval || plan.meteredApproved) {
            if (plan.status == ArtifactSyncPlan.STATUS_WAITING_NETWORK) {
                plan = store.markQueued(plan.planId) ?: plan
                SyncOperationStore(appContext).append(
                    plan.operationId,
                    SyncOperationStore.STATUS_QUEUED,
                    "Artifact update queued",
                )
            }
            ThoughtsSyncScheduler.enqueueArtifacts(
                appContext,
                allowMetered = plan.canRunOnMeteredWithoutApproval || plan.meteredApproved,
                replace = replaceExistingWork,
            )
            broadcast(appContext, plan, showPrompt = false)
            return
        }

        val showPrompt = !plan.promptShown
        plan = store.markAwaitingMeteredDecision(plan.planId) ?: plan
        ThoughtsSyncScheduler.enqueueArtifacts(
            appContext,
            allowMetered = false,
            replace = replaceExistingWork,
        )
        SyncOperationStore(appContext).append(
            plan.operationId,
            SyncOperationStore.STATUS_WAITING,
            "Waiting for an unmetered network",
        )
        broadcast(appContext, plan, showPrompt = showPrompt && plan.explicitRequest)
        if (!plan.waitForUnmetered) showWaitingNotification(appContext, plan)
    }

    /**
     * Persists a terminal build's artifact frontier when the exact download plan could not yet be
     * refreshed. A dedicated artifact-only worker will re-plan without re-entering render control.
     */
    fun publishAwaitingTerminalReplan(context: Context, candidate: ArtifactSyncPlan) {
        require(candidate.postTerminalBuildRefresh || candidate.artifactOnlyRefresh)
        val appContext = context.applicationContext
        val threshold = Preferences(
            PreferenceManager.getDefaultSharedPreferences(appContext),
        ).getSynchronizationThresholdBytes()
        val store = ArtifactSyncStateStore(appContext)
        val persisted = store.publish(candidate.copy(thresholdBytes = threshold))
        val plan = store.markQueued(persisted.planId) ?: persisted
        ThoughtsSyncScheduler.enqueueTerminalArtifactRefresh(appContext, plan.planId)
        broadcast(appContext, plan, showPrompt = false)
    }

    fun showWaitingNotification(context: Context, plan: ArtifactSyncPlan) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.artifact_sync_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            plan.planId.hashCode(),
            Intent(context, HomeActivity::class.java).putExtra(EXTRA_SHOW_PROMPT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val useMobile = PendingIntent.getBroadcast(
            context,
            plan.planId.hashCode() xor 0x41,
            Intent(context, ArtifactSyncActionReceiver::class.java)
                .setAction(ArtifactSyncActionReceiver.ACTION_USE_MOBILE)
                .putExtra(EXTRA_PLAN_ID, plan.planId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val wait = PendingIntent.getBroadcast(
            context,
            plan.planId.hashCode() xor 0x42,
            Intent(context, ArtifactSyncActionReceiver::class.java)
                .setAction(ArtifactSyncActionReceiver.ACTION_WAIT_UNMETERED)
                .putExtra(EXTRA_PLAN_ID, plan.planId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(context.getString(R.string.artifact_sync_waiting_title))
            .setContentText(context.getString(R.string.artifact_sync_waiting_message))
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.artifact_sync_use_mobile_once), useMobile)
            .addAction(0, context.getString(R.string.artifact_sync_wait_for_wifi), wait)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun dismissNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun broadcast(context: Context, plan: ArtifactSyncPlan?, showPrompt: Boolean) {
        context.sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_PLAN_ID, plan?.planId)
                .putExtra(EXTRA_SHOW_PROMPT, showPrompt),
        )
    }

    fun broadcastActivatedPdfs(context: Context, updates: Map<String, String>) {
        if (updates.isEmpty()) return
        val entries = updates.toSortedMap().entries
        context.sendBroadcast(
            Intent(ACTION_PDFS_ACTIVATED)
                .setPackage(context.packageName)
                .putStringArrayListExtra(
                    EXTRA_PDF_FILENAMES,
                    ArrayList(entries.map { it.key }),
                )
                .putStringArrayListExtra(
                    EXTRA_PDF_SHA256,
                    ArrayList(entries.map { it.value }),
                ),
        )
    }
}

internal fun nextArtifactPlanRefreshCount(previous: Int): Int {
    check(previous < 3) {
        "Sync stopped after repeated download-plan changes. Please update the server and retry Sync."
    }
    return previous + 1
}
