// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.tbend.thoughts.sdk.ObjectTransferDirection
import org.tbend.thoughts.sdk.ObjectTransferProgressListener

data class SyncTransferObject(
    val sha256: String,
    val sizeBytes: Long,
    val kind: String,
    val roles: List<String>,
    val publications: List<String>,
)

data class SyncTransferEstimate(
    val contractVersion: Int,
    val sourceProfile: String,
    val includeVoiceMedia: Boolean,
    val planId: String,
    val serverPlanId: String,
    val serverLibraryRevision: String,
    val targetPdfFilename: String?,
    val remoteObjects: List<SyncTransferObject>,
    val metadataDownloadBytes: Long = 0,
    val uploadBytes: Long,
    val downloadBytes: Long,
) {
    val remoteObjectSha256s: List<String>
        get() = remoteObjects.map(SyncTransferObject::sha256)
}

data class SyncTransferPlan(
    val contractVersion: Int,
    val sourceProfile: String,
    val includeVoiceMedia: Boolean,
    val planId: String,
    val serverPlanId: String,
    val serverLibraryRevision: String,
    val remoteObjects: List<SyncTransferObject>,
    val operationId: String,
    val targetPdfFilename: String?,
    val building: Boolean,
    val metadataDownloadBytes: Long = 0,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val thresholdBytes: Long,
    val promptShown: Boolean = false,
    val meteredApproved: Boolean = false,
    val waitForUnmetered: Boolean = false,
    val uploadBytesCompleted: Long = 0,
    val committedUploadBytes: Long = 0,
    val metadataBytesCompleted: Long = 0,
    val remoteObjectBytesCompleted: Map<String, Long> = emptyMap(),
    val controlTransferCommitted: Boolean = false,
    val previousUploadBytes: Long = 0,
    val previousDownloadBytes: Long = 0,
) {
    val operationUploadBytesCompleted: Long
        get() = directionalBytes(previousUploadBytes, uploadBytesCompleted.coerceIn(0, uploadBytes))
    val operationDownloadBytesCompleted: Long
        get() = directionalBytes(previousDownloadBytes, downloadBytesCompleted)
    val operationUploadBytesTotal: Long
        get() = directionalBytes(previousUploadBytes, uploadBytes)
    val operationDownloadBytesTotal: Long
        get() = directionalBytes(previousDownloadBytes, downloadBytes)
    val remoteObjectSha256s: List<String>
        get() = remoteObjects.map(SyncTransferObject::sha256)

    val requiresApproval: Boolean
        get() = uploadBytes > thresholdBytes || downloadBytes > thresholdBytes

    val canRunOnMetered: Boolean
        get() = !requiresApproval || meteredApproved

    val downloadBytesCompleted: Long
        get() = directionalBytes(
            metadataBytesCompleted.coerceIn(0, metadataDownloadBytes),
            remoteObjects.sumOf { remote ->
                remoteObjectBytesCompleted[remote.sha256]
                    ?.coerceIn(0, remote.sizeBytes)
                    ?: 0L
            },
        ).coerceAtMost(downloadBytes)

    fun covers(candidate: ArtifactSyncPlan): Boolean {
        if (candidate.operationId != operationId) return false
        if (candidate.targetPdfFilenames.isEmpty()) {
            return candidate.libraryRevision == serverLibraryRevision
        }
        return targetPdfFilename != null && candidate.targetPdfFilenames == listOf(targetPdfFilename)
    }
}

/**
 * How many bytes of PDFs added on this phone a Sync may upload before planning: all of them on
 * Wi-Fi or after mobile-data approval, otherwise up to the mobile-data threshold. A larger import
 * waits, and its size makes the transfer plan ask for approval like any other upload.
 */
internal fun libraryUploadBudgetBytes(unmetered: Boolean, meteredApproved: Boolean, thresholdBytes: Long): Long =
    if (unmetered || meteredApproved) Long.MAX_VALUE else thresholdBytes.coerceAtLeast(0)

internal fun carriesMeteredApproval(approved: SyncTransferPlan, next: SyncTransferPlan): Boolean =
    approved.meteredApproved && approved.operationId == next.operationId &&
        next.uploadBytes <= approved.uploadBytes && next.downloadBytes <= approved.downloadBytes

/** Only an unfinished control transfer may be restarted by its persisted Wi-Fi wait. */
internal fun shouldResumeWaitingSyncTransfer(
    plan: SyncTransferPlan?,
    record: SyncOperationRecord?,
    artifactOperationId: String? = null,
): Boolean =
    plan != null && record != null && plan.operationId == record.id &&
        record.status == SyncOperationStore.STATUS_WAITING && !plan.controlTransferCommitted &&
        plan.operationId != artifactOperationId

class SyncTransferStateStore internal constructor(
    private val stateFile: File,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "thoughts-sync/transfer-plan.json"),
    )

    @Synchronized
    fun load(): SyncTransferPlan? {
        if (!stateFile.isFile || stateFile.length() !in 1..MAX_STATE_BYTES) return null
        return runCatching {
            val value = JsonParser.parseString(stateFile.readText(StandardCharsets.UTF_8)).asJsonObject
            val schemaVersion = value.int("schema_version")
            check(schemaVersion in setOf(2, SCHEMA_VERSION))
            val remoteObjects = (value.getAsJsonArray("remote_objects") ?: error("Missing objects"))
                .map { element ->
                    val objectValue = element.asJsonObject
                    SyncTransferObject(
                        sha256 = objectValue.requiredString("sha256").also {
                            require(it.matches(SHA256))
                        },
                        sizeBytes = objectValue.long("size_bytes").also { require(it >= 0) },
                        kind = objectValue.requiredString("kind").also {
                            require(it in REMOTE_KINDS)
                        },
                        roles = objectValue.stringList("roles").also {
                            require(it.isNotEmpty() && it.all(REMOTE_ROLES::contains))
                        },
                        publications = objectValue.stringList("publications").also { publications ->
                            require(publications.all { isSafePdfFilename(it) || it.matches(Regex("project_[0-9a-f]{24}\\.thoughts-project")) })
                        },
                    )
                }
                .also { objects ->
                    require(objects.distinctBy(SyncTransferObject::sha256).size == objects.size)
                }
                .sortedBy(SyncTransferObject::sha256)
            val downloadBytes = value.long("download_bytes").coerceAtLeast(0)
            val metadataDownloadBytes = if (schemaVersion >= 3) {
                value.long("metadata_download_bytes")
            } else {
                downloadBytes - remoteObjects.sumOf(SyncTransferObject::sizeBytes)
            }.also { require(it >= 0) }
            val progress = if (schemaVersion >= 3) {
                value.getAsJsonObject("remote_object_bytes_completed")
                    ?.entrySet()
                    .orEmpty()
                    .associate { (sha256, bytes) ->
                        require(sha256.matches(SHA256))
                        sha256 to bytes.asLong.also { require(it >= 0) }
                    }
            } else {
                emptyMap()
            }
            require(progress.keys.all { sha256 -> remoteObjects.any { it.sha256 == sha256 } })
            SyncTransferPlan(
                contractVersion = value.int("contract_version").also { require(it == 2) },
                sourceProfile = value.requiredString("source_profile").also {
                    require(it == READER_PROFILE)
                },
                includeVoiceMedia = value.boolean("include_voice_media"),
                planId = value.requiredString("plan_id").also { require(it.matches(SHA256)) },
                serverPlanId = value.requiredString("server_plan_id").also { require(it.matches(SHA256)) },
                serverLibraryRevision = value.requiredString("server_library_revision")
                    .also { require(it.matches(SHA256)) },
                remoteObjects = remoteObjects,
                operationId = value.requiredString("operation_id")
                    .also { UUID.fromString(it) },
                targetPdfFilename = value.optionalString("target_pdf_filename")
                    ?.also { require(isSafePdfFilename(it)) },
                building = value.boolean("building"),
                metadataDownloadBytes = metadataDownloadBytes,
                uploadBytes = value.long("upload_bytes").coerceAtLeast(0),
                downloadBytes = downloadBytes,
                thresholdBytes = value.long("threshold_bytes").coerceAtLeast(1),
                promptShown = value.boolean("prompt_shown"),
                meteredApproved = value.boolean("metered_approved"),
                waitForUnmetered = value.boolean("wait_for_unmetered"),
                uploadBytesCompleted = value.long("upload_bytes_completed").coerceAtLeast(0),
                committedUploadBytes = value.long("committed_upload_bytes").coerceAtLeast(0),
                metadataBytesCompleted = value.long("metadata_bytes_completed").coerceAtLeast(0),
                remoteObjectBytesCompleted = progress,
                controlTransferCommitted = value.boolean("control_transfer_committed"),
                previousUploadBytes = value.long("previous_upload_bytes").coerceAtLeast(0),
                previousDownloadBytes = value.long("previous_download_bytes").coerceAtLeast(0),
            )
        }.getOrNull()
    }

    @Synchronized
    fun publish(candidate: SyncTransferPlan): SyncTransferPlan {
        val priorOperationPlan = load()?.takeIf { it.operationId == candidate.operationId }
        val previous = priorOperationPlan?.takeIf { it.planId == candidate.planId }
        return candidate.copy(
            previousUploadBytes = previous?.previousUploadBytes
                ?: priorOperationPlan?.let { directionalBytes(it.previousUploadBytes, it.committedUploadBytes) }
                ?: candidate.previousUploadBytes,
            previousDownloadBytes = previous?.previousDownloadBytes
                ?: priorOperationPlan?.operationDownloadBytesCompleted
                ?: candidate.previousDownloadBytes,
            promptShown = previous?.promptShown == true,
            meteredApproved = previous?.meteredApproved == true,
            waitForUnmetered = previous?.waitForUnmetered == true,
            uploadBytesCompleted = previous?.uploadBytesCompleted ?: candidate.uploadBytesCompleted,
            committedUploadBytes = previous?.committedUploadBytes
                ?: candidate.committedUploadBytes,
            metadataBytesCompleted = previous?.metadataBytesCompleted ?: candidate.metadataBytesCompleted,
            remoteObjectBytesCompleted = previous?.remoteObjectBytesCompleted
                ?: candidate.remoteObjectBytesCompleted,
            controlTransferCommitted = previous?.controlTransferCommitted
                ?: candidate.controlTransferCommitted,
        ).also(::write)
    }

    @Synchronized
    fun markPromptShown(planId: String): SyncTransferPlan? = update(planId) {
        it.copy(promptShown = true)
    }

    @Synchronized
    fun approveMetered(planId: String): SyncTransferPlan? = update(planId) {
        it.copy(promptShown = true, meteredApproved = true, waitForUnmetered = false)
    }

    @Synchronized
    fun markWaiting(planId: String): SyncTransferPlan? = update(planId) {
        it.copy(promptShown = true, meteredApproved = false, waitForUnmetered = true)
    }

    @Synchronized
    fun markControlTransferComplete(planId: String): SyncTransferPlan? = update(planId) {
        it.copy(
            uploadBytes = it.committedUploadBytes,
            uploadBytesCompleted = it.committedUploadBytes,
            metadataBytesCompleted = it.metadataDownloadBytes,
            controlTransferCommitted = true,
        )
    }

    @Synchronized
    fun rollbackUncommittedControlTransfer(planId: String): SyncTransferPlan? = update(planId) {
        if (it.controlTransferCommitted) {
            it
        } else {
            it.copy(uploadBytesCompleted = it.committedUploadBytes.coerceIn(0, it.uploadBytes))
        }
    }

    @Synchronized
    fun recordRemoteObjectProgress(
        planId: String,
        sha256: String,
        bytesCompleted: Long,
    ): SyncTransferPlan? = recordRemoteObjectsProgress(planId, mapOf(sha256 to bytesCompleted))

    /** Records several objects in one write; each write rewrites the whole plan. */
    @Synchronized
    fun recordRemoteObjectsProgress(
        planId: String,
        bytesCompleted: Map<String, Long>,
    ): SyncTransferPlan? = update(planId) { plan ->
        val sizes = plan.remoteObjects.associate { it.sha256 to it.sizeBytes }
        val known = bytesCompleted.mapNotNull { (sha256, bytes) ->
            sizes[sha256]?.let { size -> sha256 to bytes.coerceIn(0, size) }
        }
        if (known.isEmpty()) plan else plan.copy(remoteObjectBytesCompleted = plan.remoteObjectBytesCompleted + known)
    }

    @Synchronized
    fun recordUploadProgress(planId: String, bytesCompleted: Long): SyncTransferPlan? =
        update(planId) { plan ->
            plan.copy(uploadBytesCompleted = bytesCompleted.coerceIn(0, plan.uploadBytes))
        }

    @Synchronized
    fun recordCommittedUploadProgress(planId: String, bytesCommitted: Long): SyncTransferPlan? =
        update(planId) { plan ->
            val committed = bytesCommitted.coerceIn(0, plan.uploadBytes)
            plan.copy(
                committedUploadBytes = committed,
                uploadBytesCompleted = maxOf(plan.uploadBytesCompleted, committed),
            )
        }

    @Synchronized
    fun recordMetadataProgress(planId: String, bytesCompleted: Long): SyncTransferPlan? =
        update(planId) { plan ->
            plan.copy(
                metadataBytesCompleted = bytesCompleted.coerceIn(0, plan.metadataDownloadBytes),
            )
        }

    @Synchronized
    fun markTransferComplete(planId: String): SyncTransferPlan? = update(planId) { plan ->
        plan.copy(
            uploadBytes = plan.committedUploadBytes,
            uploadBytesCompleted = plan.committedUploadBytes,
            metadataBytesCompleted = plan.metadataDownloadBytes,
            remoteObjectBytesCompleted = plan.remoteObjects.associate { it.sha256 to it.sizeBytes },
            controlTransferCommitted = true,
        )
    }

    @Synchronized
    fun clearForOperation(operationId: String?) {
        if (operationId != null && load()?.operationId == operationId) stateFile.delete()
    }

    private fun update(
        planId: String,
        transform: (SyncTransferPlan) -> SyncTransferPlan,
    ): SyncTransferPlan? {
        val current = load()?.takeIf { it.planId == planId } ?: return null
        return transform(current).also(::write)
    }

    private fun write(plan: SyncTransferPlan) {
        val value = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("contract_version", plan.contractVersion)
            addProperty("source_profile", plan.sourceProfile)
            addProperty("include_voice_media", plan.includeVoiceMedia)
            addProperty("plan_id", plan.planId)
            addProperty("server_plan_id", plan.serverPlanId)
            addProperty("server_library_revision", plan.serverLibraryRevision)
            add("remote_objects", JsonArray().apply {
                plan.remoteObjects.distinctBy(SyncTransferObject::sha256)
                    .sortedBy(SyncTransferObject::sha256)
                    .forEach { remote ->
                        add(JsonObject().apply {
                            addProperty("sha256", remote.sha256)
                            addProperty("size_bytes", remote.sizeBytes)
                            addProperty("kind", remote.kind)
                            add("roles", JsonArray().apply { remote.roles.distinct().sorted().forEach(::add) })
                            add(
                                "publications",
                                JsonArray().apply {
                                    remote.publications.distinct().sorted().forEach(::add)
                                },
                            )
                        })
                    }
            })
            addProperty("operation_id", plan.operationId)
            plan.targetPdfFilename?.let { addProperty("target_pdf_filename", it) }
            addProperty("building", plan.building)
            addProperty("metadata_download_bytes", plan.metadataDownloadBytes)
            addProperty("upload_bytes", plan.uploadBytes)
            addProperty("download_bytes", plan.downloadBytes)
            addProperty("upload_bytes_completed", plan.uploadBytesCompleted)
            addProperty("committed_upload_bytes", plan.committedUploadBytes)
            addProperty("metadata_bytes_completed", plan.metadataBytesCompleted)
            addProperty("control_transfer_committed", plan.controlTransferCommitted)
            addProperty("previous_upload_bytes", plan.previousUploadBytes)
            addProperty("previous_download_bytes", plan.previousDownloadBytes)
            add("remote_object_bytes_completed", JsonObject().apply {
                plan.remoteObjectBytesCompleted.toSortedMap().forEach { (sha256, bytes) ->
                    addProperty(sha256, bytes)
                }
            })
            addProperty("threshold_bytes", plan.thresholdBytes)
            addProperty("prompt_shown", plan.promptShown)
            addProperty("metered_approved", plan.meteredApproved)
            addProperty("wait_for_unmetered", plan.waitForUnmetered)
        }
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, ".${stateFile.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write((value.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(stateFile)) { "Could not publish the pending transfer plan" }
    }

    private companion object {
        const val SCHEMA_VERSION = 3
        const val MAX_STATE_BYTES = 2_000_000L
        const val READER_PROFILE = "reader-v1"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val REMOTE_KINDS = setOf("pdf", "source", "source_index", "voice")
        val REMOTE_ROLES = setOf(
            "pdf",
            "pdf_delta",
            "source_index",
            "source_manifest",
            "source_snapshot",
            "reverse_lookup",
            "raw_synctex_fallback",
            "source_resource",
            "renderer_evidence",
            "project_reading",
            "document_pdf",
            "document_reading_package",
            "project_composition",
            "document_component_package",
            "component_review",
            "voice",
        )

        fun JsonObject.requiredString(name: String): String = get(name).asString
        fun JsonObject.optionalString(name: String): String? = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        fun JsonObject.int(name: String): Int = get(name)?.asInt ?: 0
        fun JsonObject.long(name: String): Long = get(name)?.asLong ?: 0
        fun JsonObject.boolean(name: String): Boolean = get(name)?.asBoolean == true
        fun JsonObject.stringList(name: String): List<String> =
            (getAsJsonArray(name) ?: JsonArray()).map { it.asString }.distinct().sorted()

        fun isSafePdfFilename(filename: String): Boolean =
            filename.endsWith(".pdf", ignoreCase = true) &&
                filename.length > 4 && filename == File(filename).name &&
                !filename.contains('/') && !filename.contains('\\')
    }
}

internal class SyncTransferProgressReporter(
    context: Context,
    initialPlan: SyncTransferPlan,
    private val publishIntervalMillis: Long = 250,
) : ObjectTransferProgressListener {
    private val stateStore = SyncTransferStateStore(context)
    private val operationStore = SyncOperationStore(context)
    private val operationId = initialPlan.operationId
    private val planId = initialPlan.planId
    private val remoteSizes = initialPlan.remoteObjects.associate { it.sha256 to it.sizeBytes }
    // Upload requests restart from byte zero after process death or an HTTP retry. Keep
    // only request/object payloads that received a successful server response as baseline;
    // replaying persisted in-flight bytes would otherwise count the same request twice.
    private var committedUploadBytes = if (initialPlan.controlTransferCommitted) {
        initialPlan.uploadBytes
    } else {
        initialPlan.committedUploadBytes.coerceIn(0, initialPlan.uploadBytes)
    }
    private val uploadObjects = mutableMapOf<String, Long>()
    private var logicalUploadBytes = 0L
    private val metadataBaseline = initialPlan.metadataBytesCompleted
        .coerceIn(0, initialPlan.metadataDownloadBytes)
    private var metadataRequestBytes = 0L
    @Volatile private var lastPublishedAt = 0L
    private val pendingDownloads = linkedMapOf<String, Long>()
    private val publishingDownloads = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        if (initialPlan.uploadBytesCompleted != committedUploadBytes) {
            stateStore.recordUploadProgress(planId, committedUploadBytes)?.let { resetPlan ->
                operationStore.recordTransferPlan(operationId, resetPlan)
            }
        }
    }

    override fun onProgress(
        direction: ObjectTransferDirection,
        sha256: String,
        bytesCompleted: Long,
        bytesTotal: Long,
    ) {
        if (direction == ObjectTransferDirection.DOWNLOAD) {
            if (sha256 !in remoteSizes) return
            // Progress is display-only, and every write rewrites the whole plan and operation
            // row. Parallel downloads report every 8 KiB; they record and continue, and only
            // the thread that wins the publish gate writes, without making the others wait.
            synchronized(pendingDownloads) { pendingDownloads[sha256] = bytesCompleted }
            if (System.currentTimeMillis() - lastPublishedAt < publishIntervalMillis) return
            if (!publishingDownloads.compareAndSet(false, true)) return
            try {
                publish(direction, sha256, bytesCompleted, bytesTotal)
            } finally {
                publishingDownloads.set(false)
            }
            return
        }
        publish(direction, sha256, bytesCompleted, bytesTotal)
    }

    @Synchronized
    private fun publish(
        direction: ObjectTransferDirection,
        sha256: String,
        bytesCompleted: Long,
        bytesTotal: Long,
    ) {
        val now = System.currentTimeMillis()
        val force = bytesCompleted == 0L || remoteSizes[sha256] == bytesCompleted ||
            (bytesTotal >= 0 && bytesCompleted == bytesTotal)
        if (direction == ObjectTransferDirection.UPLOAD && !force && now - lastPublishedAt < publishIntervalMillis) return
        val updated = when (direction) {
            ObjectTransferDirection.DOWNLOAD -> {
                val batch = synchronized(pendingDownloads) {
                    pendingDownloads.toMap().also { pendingDownloads.clear() }
                }
                if (batch.isEmpty()) return
                stateStore.recordRemoteObjectsProgress(planId, batch)
            }
            ObjectTransferDirection.UPLOAD -> {
                uploadObjects[sha256] = bytesCompleted.coerceAtLeast(0)
                val streamed = uploadObjects.values.fold(0L, ::directionalBytes)
                val completed = directionalBytes(
                    directionalBytes(committedUploadBytes, logicalUploadBytes),
                    streamed,
                )
                stateStore.recordUploadProgress(planId, completed)
            }
        } ?: return
        lastPublishedAt = now
        operationStore.recordTransferPlan(operationId, updated)
    }

    @Synchronized
    fun recordLogicalUpload(bytes: Long) {
        logicalUploadBytes = directionalBytes(logicalUploadBytes, bytes)
        committedUploadBytes = directionalBytes(committedUploadBytes, logicalUploadBytes)
        logicalUploadBytes = 0
        uploadObjects.clear()
        val committedPlan = stateStore.recordCommittedUploadProgress(
            planId,
            committedUploadBytes,
        ) ?: return
        committedUploadBytes = committedPlan.committedUploadBytes
        val updated = stateStore.recordUploadProgress(planId, committedUploadBytes)
            ?: committedPlan
        operationStore.recordTransferPlan(operationId, updated)
    }

    @Synchronized
    fun recordCommittedObjectUpload(sha256: String, bytes: Long) {
        uploadObjects.remove(sha256)
        committedUploadBytes = directionalBytes(committedUploadBytes, bytes)
        val committedPlan = stateStore.recordCommittedUploadProgress(
            planId,
            committedUploadBytes,
        ) ?: return
        committedUploadBytes = committedPlan.committedUploadBytes
        val streamedObjects = uploadObjects.values.fold(0L, ::directionalBytes)
        val completed = directionalBytes(committedUploadBytes, streamedObjects)
        val updated = stateStore.recordUploadProgress(planId, completed) ?: return
        operationStore.recordTransferPlan(operationId, updated)
    }

    @Synchronized
    fun recordMetadataDownload(bytes: Long) {
        metadataRequestBytes = directionalBytes(metadataRequestBytes, bytes)
        val completed = directionalBytes(metadataBaseline, metadataRequestBytes)
        val updated = stateStore.recordMetadataProgress(planId, completed) ?: return
        operationStore.recordTransferPlan(operationId, updated)
    }
}

object SyncTransferCoordinator {
    const val ACTION_STATE_CHANGED = "org.tbend.thoughts.action.SYNC_TRANSFER_STATE_CHANGED"
    const val EXTRA_PLAN_ID = "sync_transfer_plan_id"
    const val EXTRA_SHOW_PROMPT = "show_sync_transfer_prompt"

    private const val CHANNEL_ID = "thoughts-mobile-transfer"
    private const val NOTIFICATION_ID = 0x4d4d

    suspend fun prepare(
        context: Context,
        explicit: Boolean,
        targetPdfFilename: String?,
        operationId: String?,
        building: Boolean,
    ): Boolean {
        val stableOperationId = operationId ?: return true
        val diagnostics = SyncDiagnostics(context, operationId)
        val store = SyncTransferStateStore(context)
        val existing = store.load()?.takeIf { it.operationId == stableOperationId }
        val requestedScope = if (existing != null && existing.targetPdfFilename == null) {
            null
        } else {
            targetPdfFilename
        }
        val threshold = Preferences(
            PreferenceManager.getDefaultSharedPreferences(context),
        ).getSynchronizationThresholdBytes()
        val estimate = diagnostics.measureSuspending("sync_transfer_preflight") {
            FedoraRepository(context).estimateSyncTransfer(
                requestedScope,
                operationId,
                deliverPendingLibraryRemovals = shouldDeliverPendingLibraryRemovals(
                    explicit = explicit,
                    targetPdfFilename = requestedScope,
                    artifactOnly = false,
                ),
                libraryUploadBudgetBytes = libraryUploadBudgetBytes(
                    unmetered = ThoughtsNetworkPolicy.isUnmetered(context),
                    meteredApproved = existing?.meteredApproved == true,
                    thresholdBytes = threshold,
                ),
            )
        }
        var plan = diagnostics.measure("preflight_persist_transfer_plan") {
            val published = store.publish(
                SyncTransferPlan(
                    contractVersion = estimate.contractVersion,
                    sourceProfile = estimate.sourceProfile,
                    includeVoiceMedia = estimate.includeVoiceMedia,
                    planId = estimate.planId,
                    serverPlanId = estimate.serverPlanId,
                    serverLibraryRevision = estimate.serverLibraryRevision,
                    remoteObjects = estimate.remoteObjects,
                    operationId = stableOperationId,
                    targetPdfFilename = estimate.targetPdfFilename,
                    building = building,
                    metadataDownloadBytes = estimate.metadataDownloadBytes,
                    uploadBytes = estimate.uploadBytes,
                    downloadBytes = estimate.downloadBytes,
                    thresholdBytes = threshold,
                ),
            )
            // Sending an approved Library import changes the plan's identity; the approval still
            // covers a plan that moves no more data than the one the user approved.
            val carried = if (
                !published.meteredApproved && existing != null &&
                carriesMeteredApproval(existing, published)
            ) {
                store.approveMetered(published.planId) ?: published
            } else {
                published
            }
            SyncOperationStore(context).recordTransferPlan(stableOperationId, carried)
            carried
        }
        if (ThoughtsNetworkPolicy.isUnmetered(context)) return true
        if (plan.canRunOnMetered) return true
        SyncOperationStore(context).append(
            stableOperationId,
            SyncOperationStore.STATUS_WAITING,
            "Waiting for mobile-data approval or Wi-Fi",
        )
        val shouldPrompt = explicit && !plan.promptShown
        if (shouldPrompt) plan = store.markPromptShown(plan.planId) ?: plan
        ThoughtsSyncScheduler.enqueueTransferWifiWait(context, plan)
        broadcast(context, plan, showPrompt = shouldPrompt)
        if (!plan.waitForUnmetered) showWaitingNotification(context, plan)
        return false
    }

    /**
     * A build can publish healthy PDFs after the control-phase transfer plan was calculated.
     * Re-plan downloads only, against that terminal Library frontier, so artifact activation does
     * not re-enter the control sync or submit another render request.
     */
    suspend fun refreshForTerminalBuildArtifacts(
        context: Context,
        artifactPlan: ArtifactSyncPlan,
        /**
         * True only for the hand-off from a Sync's own control pass: the plan that Sync got before
         * it is kept when the Library and the phone's inventory are as it was computed for. A plan
         * refreshed because a download found it superseded is always asked for again.
         */
        reuseControlPassPlan: Boolean = false,
    ): SyncTransferPlan {
        val operationId = requireNotNull(artifactPlan.operationId) {
            "A terminal-build artifact refresh requires its original sync operation"
        }
        val store = SyncTransferStateStore(context)
        val target = artifactPlan.targetPdfFilenames.singleOrNull()
        val estimate = FedoraRepository(context).estimateSyncTransfer(
            targetPdfFilename = target,
            operationId = operationId,
            artifactOnly = true,
            reusePlanForLibraryRevision = artifactPlan.libraryRevision.takeIf { reuseControlPassPlan && target == null },
        )
        check(estimate.uploadBytes == 0L && !estimate.includeVoiceMedia) {
            "The server returned a non-artifact transfer plan for a terminal build"
        }
        if (estimate.targetPdfFilename != target) throw ArtifactPlanSupersededException()
        if (target == null) {
            if (estimate.serverLibraryRevision != artifactPlan.libraryRevision) {
                throw ArtifactPlanSupersededException()
            }
        }
        val previous = store.load()?.takeIf { it.operationId == operationId }
        val threshold = Preferences(
            PreferenceManager.getDefaultSharedPreferences(context),
        ).getSynchronizationThresholdBytes()
        val refreshed = store.publish(
            SyncTransferPlan(
                contractVersion = estimate.contractVersion,
                sourceProfile = estimate.sourceProfile,
                includeVoiceMedia = estimate.includeVoiceMedia,
                planId = estimate.planId,
                serverPlanId = estimate.serverPlanId,
                serverLibraryRevision = estimate.serverLibraryRevision,
                remoteObjects = estimate.remoteObjects,
                operationId = operationId,
                targetPdfFilename = estimate.targetPdfFilename,
                building = previous?.building == true,
                metadataDownloadBytes = estimate.metadataDownloadBytes,
                uploadBytes = 0,
                downloadBytes = estimate.downloadBytes,
                thresholdBytes = threshold,
            ),
        )
        if (!refreshed.covers(artifactPlan)) throw ArtifactPlanSupersededException()
        SyncOperationStore(context).recordTransferPlan(operationId, refreshed)
        return refreshed
    }

    fun markControlTransferComplete(context: Context, operationId: String?) {
        if (operationId.isNullOrBlank()) return
        val store = SyncTransferStateStore(context)
        val current = store.load()?.takeIf { it.operationId == operationId } ?: return
        val updated = store.markControlTransferComplete(current.planId) ?: return
        SyncOperationStore(context).recordTransferPlan(operationId, updated)
    }

    fun rollbackUncommittedControlTransfer(context: Context, operationId: String?) {
        if (operationId.isNullOrBlank()) return
        val store = SyncTransferStateStore(context)
        val current = store.load()?.takeIf { it.operationId == operationId } ?: return
        val updated = store.rollbackUncommittedControlTransfer(current.planId) ?: return
        SyncOperationStore(context).recordTransferPlan(operationId, updated)
    }

    fun markTransferComplete(context: Context, operationId: String?) {
        if (operationId.isNullOrBlank()) return
        val store = SyncTransferStateStore(context)
        val current = store.load()?.takeIf { it.operationId == operationId } ?: return
        val updated = store.markTransferComplete(current.planId) ?: return
        SyncOperationStore(context).recordTransferPlan(operationId, updated)
    }

    fun approve(context: Context, planId: String): SyncTransferPlan? {
        val plan = SyncTransferStateStore(context).approveMetered(planId) ?: return null
        ThoughtsSyncScheduler.cancelTransferWifiWait(context, plan.operationId)
        SyncOperationStore(context).append(
            plan.operationId,
            SyncOperationStore.STATUS_QUEUED,
            "Synchronization approved for mobile data",
        )
        dismissNotification(context)
        runCatching {
            ThoughtsSyncScheduler.enqueueExplicitContinuation(
                context,
                plan.targetPdfFilename,
                plan.operationId,
                plan.building,
                requiredNetworkType = androidx.work.NetworkType.CONNECTED,
            )
        }
        broadcast(context, plan, showPrompt = false)
        return plan
    }

    fun waitForWifi(context: Context, planId: String): SyncTransferPlan? {
        val plan = SyncTransferStateStore(context).markWaiting(planId) ?: return null
        SyncOperationStore(context).append(
            plan.operationId,
            SyncOperationStore.STATUS_WAITING,
            "Waiting for Wi-Fi",
        )
        dismissNotification(context)
        ThoughtsSyncScheduler.enqueueTransferWifiWait(context, plan)
        broadcast(context, plan, showPrompt = false)
        return plan
    }

    fun resume(context: Context) {
        val plan = SyncTransferStateStore(context).load() ?: return
        val record = SyncOperationStore(context).operation(plan.operationId)
        if (!shouldResumeWaitingSyncTransfer(plan, record, ArtifactSyncStateStore(context).load()?.operationId)) return
        ThoughtsSyncScheduler.enqueueTransferWifiWait(context, plan)
    }

    fun broadcast(context: Context, plan: SyncTransferPlan?, showPrompt: Boolean) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_PLAN_ID, plan?.planId)
                .putExtra(EXTRA_SHOW_PROMPT, showPrompt),
        )
    }

    fun promptMessage(context: Context, plan: SyncTransferPlan): String {
        val upload = Formatter.formatShortFileSize(context, plan.uploadBytes)
        val download = Formatter.formatShortFileSize(context, plan.downloadBytes)
        return when {
            plan.uploadBytes > plan.thresholdBytes && plan.downloadBytes > plan.thresholdBytes ->
                context.getString(R.string.sync_transfer_prompt_both, upload, download)
            plan.uploadBytes > plan.thresholdBytes ->
                context.getString(R.string.sync_transfer_prompt_upload, upload)
            else -> context.getString(R.string.sync_transfer_prompt_download, download)
        }
    }

    private fun showWaitingNotification(context: Context, plan: SyncTransferPlan) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.sync_transfer_channel),
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
            plan.planId.hashCode() xor 0x51,
            Intent(context, SyncTransferActionReceiver::class.java)
                .setAction(SyncTransferActionReceiver.ACTION_USE_MOBILE)
                .putExtra(EXTRA_PLAN_ID, plan.planId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val wait = PendingIntent.getBroadcast(
            context,
            plan.planId.hashCode() xor 0x52,
            Intent(context, SyncTransferActionReceiver::class.java)
                .setAction(SyncTransferActionReceiver.ACTION_WAIT_WIFI)
                .putExtra(EXTRA_PLAN_ID, plan.planId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(context.getString(R.string.sync_transfer_prompt_title))
            .setContentText(promptMessage(context, plan))
            .setStyle(NotificationCompat.BigTextStyle().bigText(promptMessage(context, plan)))
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.sync_transfer_continue_mobile), useMobile)
            .addAction(0, context.getString(R.string.sync_transfer_wait_wifi), wait)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun dismissNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

class SyncTransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val planId = intent?.getStringExtra(SyncTransferCoordinator.EXTRA_PLAN_ID) ?: return
        when (intent.action) {
            ACTION_USE_MOBILE -> SyncTransferCoordinator.approve(context, planId)
            ACTION_WAIT_WIFI -> SyncTransferCoordinator.waitForWifi(context, planId)
        }
    }

    companion object {
        const val ACTION_USE_MOBILE = "org.tbend.thoughts.action.USE_MOBILE_FOR_SYNC"
        const val ACTION_WAIT_WIFI = "org.tbend.thoughts.action.WAIT_WIFI_FOR_SYNC"
    }
}
