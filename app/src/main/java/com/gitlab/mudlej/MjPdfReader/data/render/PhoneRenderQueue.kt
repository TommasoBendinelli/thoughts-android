// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import android.os.FileObserver
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAnchorRequirement
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class PhoneRenderReceipt(
    val schemaVersion: Int,
    val requestId: String,
    val target: String,
    val status: String,
    val message: String,
    val exitCode: Int,
    val publishedPdf: PublishedPdf?,
    val anchorResults: List<PhoneAnchorResult>,
    val startedAtEpochMillis: Long = 0,
    val completedAtEpochMillis: Long = 0,
    val publishedPdfs: List<PublishedPdf> = emptyList(),
    val affectedPdfFilenames: Set<String> = emptySet(),
    val attentionRequired: Boolean = false,
    val attentionPdfs: List<PhonePdfAttention> = emptyList(),
    val hasAttentionPdfInventory: Boolean = false,
    val progress: PhoneRenderProgress? = null,
    val changesDetected: Boolean? = null,
    val failedDocuments: List<PhoneRenderFailure> = emptyList(),
    val logReference: String = "",
    val repairMode: String = PhoneRenderQueue.REPAIR_NONE,
    val buildId: String = "",
) {
    val succeeded: Boolean
        get() = status == STATUS_SUCCESS || status == STATUS_PARTIAL_SUCCESS

    val requiresAttention: Boolean
        get() = attentionRequired ||
            failedDocuments.isNotEmpty() ||
            anchorResults.any(PhoneAnchorResult::requiresAttention)

    data class PublishedPdf(
        val filename: String,
        val modifiedEpochMillis: Long,
        val sizeBytes: Long,
        val sha256: String,
    )

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_PARTIAL_SUCCESS = "partial_success"
        const val STATUS_FAILURE = "failure"
    }
}

data class PhoneRenderFailure(
    val pdfFilename: String,
    val source: String,
    val message: String,
)

data class PhonePdfAttention(
    val filename: String,
    val commentCount: Int,
)

data class PhoneRenderProgress(
    val phase: String,
    val completed: Int,
    val total: Int,
    val unit: String,
    val detail: String,
    val updatedAtEpochMillis: Long,
) {
    val hasCount: Boolean
        get() = total > 0 && completed in 0..total
}

enum class PhoneRenderStage {
    SYNC_NEEDED,
    SOURCE_SYNC_NEEDED,
    QUEUED,
    FEDORA_QUEUED,
    BLOCKED,
    CHECKING,
    REBUILDING,
    WAITING_FOR_SYNC,
    SYNC_PENDING,
    UPDATED,
    OUT_OF_SYNC,
    ATTENTION,
    PARTIAL,
    UNVERIFIED,
    FAILED,
}

data class PhoneRenderSyncHealth(
    val lastAttemptEpochMillis: Long = 0,
    val lastSuccessEpochMillis: Long = 0,
    val errorCode: String = "",
    val errorMessage: String = "",
) {
    fun blocks(requestCreatedAtEpochMillis: Long): Boolean =
        errorMessage.isNotBlank() &&
            lastAttemptEpochMillis > lastSuccessEpochMillis &&
            lastAttemptEpochMillis >= requestCreatedAtEpochMillis
}

data class PhoneRenderJobStatus(
    val requestId: String,
    val target: String,
    val stage: PhoneRenderStage,
    val message: String = "",
    val createdAtEpochMillis: Long = 0,
    val startedAtEpochMillis: Long = 0,
    val completedAtEpochMillis: Long = 0,
    val lastSuccessfulBuildAtEpochMillis: Long = 0,
    val verifiedAtEpochMillis: Long = 0,
    val publishedPdfCount: Int = 0,
    val syncedPdfCount: Int = 0,
    val progress: PhoneRenderProgress? = null,
    val bannerEligible: Boolean = true,
    val publishedPdfFilenames: Set<String> = emptySet(),
    val pendingPdfFilenames: Set<String> = emptySet(),
    val affectedPdfFilenames: Set<String> = emptySet(),
    val attentionPdfs: List<PhonePdfAttention> = emptyList(),
    val hasAttentionPdfInventory: Boolean = false,
    val changesDetected: Boolean? = null,
    val failedDocuments: List<PhoneRenderFailure> = emptyList(),
    val logReference: String = "",
    val buildId: String = "",
    val repairMode: String = PhoneRenderQueue.REPAIR_NONE,
    val conditionalCheck: Boolean = false,
) {
    val affectsAll: Boolean
        get() = target == PhoneRenderQueue.ACTION_ALL

    val isActive: Boolean
        get() = stage == PhoneRenderStage.QUEUED ||
            stage == PhoneRenderStage.FEDORA_QUEUED ||
            stage == PhoneRenderStage.CHECKING ||
            stage == PhoneRenderStage.REBUILDING ||
            stage == PhoneRenderStage.WAITING_FOR_SYNC

    val activityAtEpochMillis: Long
        get() = maxOf(
            verifiedAtEpochMillis,
            completedAtEpochMillis,
            startedAtEpochMillis,
            createdAtEpochMillis,
        )
}

data class PhoneRenderStatusSnapshot(
    val jobs: List<PhoneRenderJobStatus> = emptyList(),
) {
    fun statusForPdf(filename: String): PhoneRenderJobStatus? {
        val latestUnattributedFailure = jobs
            .filter { it.affectsAll && it.stage == PhoneRenderStage.FAILED }
            .maxByOrNull(PhoneRenderJobStatus::activityAtEpochMillis)
        val status = jobs
            .filter {
                it.target == filename ||
                    it.appliesToPdf(filename)
            }
            .maxWithOrNull(
                compareBy<PhoneRenderJobStatus> { it.presentationPriority() }
                    .thenBy { it.activityAtEpochMillis }
                    .thenBy { if (it.target == filename) 1 else 0 }
            )
        if (latestUnattributedFailure != null &&
            latestUnattributedFailure.activityAtEpochMillis >
            (status?.activityAtEpochMillis ?: 0)
        ) {
            return null
        }
        return status?.forPdf(filename)
    }

    val primary: PhoneRenderJobStatus?
        get() {
            return (displayJobs.ifEmpty { jobs })
                .maxWithOrNull(
                    compareBy<PhoneRenderJobStatus> { it.presentationPriority() }
                        .thenBy(PhoneRenderJobStatus::activityAtEpochMillis)
                )
        }

    val activeJobs: List<PhoneRenderJobStatus>
        get() = jobs.filter(PhoneRenderJobStatus::isActive)

    val attentionJobs: List<PhoneRenderJobStatus>
        get() = jobs.filter {
            it.stage == PhoneRenderStage.ATTENTION ||
                it.stage == PhoneRenderStage.PARTIAL ||
                it.stage == PhoneRenderStage.BLOCKED ||
                it.stage == PhoneRenderStage.FAILED
        }

    val displayJobs: List<PhoneRenderJobStatus>
        get() = activeJobs.ifEmpty { attentionJobs }

    companion object {
        val EMPTY = PhoneRenderStatusSnapshot()
    }
}

private fun PhoneRenderJobStatus.presentationPriority(): Int = when {
    requestId.startsWith("remote-status:") && isActive -> 110
    isActive && stage != PhoneRenderStage.QUEUED -> 105
    requestId.startsWith("local-source:") -> 102
    isActive && stage == PhoneRenderStage.QUEUED -> 100
    requestId.startsWith("local-sync:") -> 100
    requestId.startsWith("remote-status:") -> 95
    stage in setOf(
        PhoneRenderStage.BLOCKED,
        PhoneRenderStage.ATTENTION,
        PhoneRenderStage.PARTIAL,
        PhoneRenderStage.FAILED,
    ) -> 80
    stage == PhoneRenderStage.QUEUED -> 70
    stage == PhoneRenderStage.OUT_OF_SYNC -> 60
    stage == PhoneRenderStage.SOURCE_SYNC_NEEDED -> 90
    stage == PhoneRenderStage.SYNC_NEEDED -> 50
    stage == PhoneRenderStage.SYNC_PENDING -> 40
    stage == PhoneRenderStage.UPDATED -> 30
    else -> 5
}

private fun PhoneRenderJobStatus.appliesToPdf(filename: String): Boolean {
    if (!affectsAll || stage == PhoneRenderStage.UNVERIFIED ||
        stage == PhoneRenderStage.PARTIAL
    ) {
        return false
    }
    if (failedDocuments.any { it.pdfFilename == filename }) {
        return true
    }
    if (stage == PhoneRenderStage.FAILED) {
        return false
    }
    if (conditionalCheck && affectedPdfFilenames.isEmpty()) {
        return false
    }
    if (affectedPdfFilenames.isNotEmpty()) {
        return filename in affectedPdfFilenames
    }
    return publishedPdfFilenames.isEmpty() || filename in publishedPdfFilenames
}

private fun PhoneRenderJobStatus.forPdf(filename: String): PhoneRenderJobStatus? {
    if (target == filename) {
        return this
    }
    if (!affectsAll) {
        return null
    }
    val failure = failedDocuments.firstOrNull { it.pdfFilename == filename }
    if (failure != null) {
        return copy(
            target = filename,
            stage = if (stage == PhoneRenderStage.FAILED) {
                PhoneRenderStage.FAILED
            } else {
                PhoneRenderStage.PARTIAL
            },
            message = failure.message,
            createdAtEpochMillis = 0,
            startedAtEpochMillis = 0,
            publishedPdfCount = 0,
            syncedPdfCount = 0,
            progress = null,
            bannerEligible = false,
            publishedPdfFilenames = emptySet(),
            pendingPdfFilenames = emptySet(),
            affectedPdfFilenames = emptySet(),
            attentionPdfs = emptyList(),
            hasAttentionPdfInventory = false,
            failedDocuments = listOf(failure),
        )
    }
    val pdfStage = if (
        stage == PhoneRenderStage.WAITING_FOR_SYNC &&
        pendingPdfFilenames.isNotEmpty() &&
        filename !in pendingPdfFilenames
    ) {
        PhoneRenderStage.UPDATED
    } else {
        stage
    }
    val hasPublishedIdentity = filename in publishedPdfFilenames
    return copy(
        target = filename,
        stage = pdfStage,
        message = if (stage == PhoneRenderStage.BLOCKED) message else "",
        createdAtEpochMillis = 0,
        startedAtEpochMillis = 0,
        publishedPdfCount = if (hasPublishedIdentity) 1 else 0,
        syncedPdfCount = if (
            hasPublishedIdentity && pdfStage == PhoneRenderStage.UPDATED
        ) {
            1
        } else {
            0
        },
        progress = null,
        bannerEligible = false,
        publishedPdfFilenames = if (hasPublishedIdentity) setOf(filename) else emptySet(),
        pendingPdfFilenames = if (filename in pendingPdfFilenames) {
            setOf(filename)
        } else {
            emptySet()
        },
        affectedPdfFilenames = if (filename in affectedPdfFilenames) {
            setOf(filename)
        } else {
            emptySet()
        },
        attentionPdfs = emptyList(),
        hasAttentionPdfInventory = false,
        failedDocuments = emptyList(),
        logReference = "",
    )
}

data class PhoneAnchorResult(
    val id: String,
    val revision: Int,
    val status: String,
    val confidence: String,
) {
    val requiresAttention: Boolean
        get() = status !in setOf(STATUS_ANCHORED, STATUS_DELETED)

    companion object {
        const val STATUS_ANCHORED = "anchored"
        const val STATUS_NEEDS_ANCHOR = "needs_anchor"
        const val STATUS_ORPHANED = "orphaned"
        const val STATUS_DELETED = "deleted"
        const val STATUS_MISSING = "missing"
        val supportedStatuses = setOf(
            STATUS_ANCHORED,
            STATUS_NEEDS_ANCHOR,
            STATUS_ORPHANED,
            STATUS_DELETED,
            STATUS_MISSING,
        )
    }
}

data class RenderEnqueueResult(
    val requestId: String,
    val alreadyPending: Boolean,
)

private data class PhoneRenderRequestSnapshot(
    val requestId: String,
    val action: String,
    val target: String,
    val createdAtEpochMillis: Long,
    val repairMode: String,
)

class PhoneRenderQueue(
    private val requestDirectory: File = defaultRequestDirectory(),
    private val receiptDirectory: File = defaultReceiptDirectory(),
    private val canonicalPdfDirectory: File = defaultCanonicalPdfDirectory(),
    private val sourcePdfIndex: SourceLinkedPdfIndex = SourceLinkedPdfIndex(canonicalPdfDirectory),
    private val requestScan: (String) -> Unit = {},
    private val directorySync: (File) -> Unit = ::syncRenderDirectoryDurably,
) {

    private val matchCache = mutableMapOf<String, Boolean>()

    fun canRequestPdf(file: File): Boolean {
        return pdfCapability(file) == SourcePdfCapability.SOURCE_LINKED
    }

    fun pdfCapability(file: File): SourcePdfCapability {
        return sourcePdfIndex.capabilityFor(file)
    }

    fun canonicalPdfSha256(file: File): String? {
        return sourcePdfIndex.canonicalPdfSha256(file)
    }

    fun enqueuePdf(
        file: File,
        requiredComments: Collection<CommentAnchorRequirement> = emptyList(),
        commentRecords: Collection<CommentDeliveryRecord> = emptyList(),
        sourceFrontier: Long? = null,
        inputSnapshotSha256: String? = null,
    ): RenderEnqueueResult {
        require(canRequestPdf(file)) { "PDF is not a canonical docs-clean-pdfs entry" }
        return enqueue(
            ACTION_PDF,
            file.name,
            requiredComments,
            commentRecords,
            sourceFrontier,
            inputSnapshotSha256,
            REPAIR_NONE,
        )
    }

    fun enqueueAll(): RenderEnqueueResult = enqueue(
        ACTION_ALL,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        REPAIR_NONE,
    )

    fun enqueueCheckAll(): RenderEnqueueResult = enqueue(
        ACTION_CHECK_ALL,
        null,
        emptyList(),
        emptyList(),
        null,
        null,
        REPAIR_NONE,
    )

    fun enqueueCodexRepair(target: String): RenderEnqueueResult {
        return if (target == ACTION_ALL) {
            enqueue(ACTION_ALL, null, emptyList(), emptyList(), null, null, REPAIR_CODEX)
        } else {
            val file = File(canonicalPdfDirectory, target)
            require(canRequestPdf(file)) { "PDF is not a canonical docs-clean-pdfs entry" }
            enqueue(ACTION_PDF, target, emptyList(), emptyList(), null, null, REPAIR_CODEX)
        }
    }

    fun hasPendingPdf(file: File): Boolean {
        if (!canRequestPdf(file)) {
            return false
        }
        return requestFiles().any { requestFile ->
            if (receiptIsTerminal(File(receiptDirectory, requestFile.name))) {
                return@any false
            }
            val payload = parseObject(requestFile) ?: return@any false
            payload.string("action") == ACTION_PDF &&
                payload.string("pdf_filename") == file.name
        }
    }

    fun pendingPdfCovers(
        file: File,
        requiredComments: Collection<CommentAnchorRequirement>,
    ): Boolean {
        if (!canRequestPdf(file)) {
            return false
        }
        val normalized = normalizeRequirements(requiredComments)
        return findPending(ACTION_PDF, file.name, normalized) != null
    }

    fun matchesPublishedPdf(file: File, published: PhoneRenderReceipt.PublishedPdf): Boolean {
        if (file.name != published.filename || !file.isFile) {
            return false
        }
        if (published.sha256.isBlank()) {
            return file.length() == published.sizeBytes
        }
        if (file.length() != published.sizeBytes) {
            return false
        }
        val cacheKey = listOf(
            canonicalPath(file).orEmpty(),
            file.length().toString(),
            file.lastModified().toString(),
            published.sha256,
        ).joinToString("|")
        synchronized(matchCache) {
            matchCache[cacheKey]?.let { return it }
        }
        val matches = sha256(file) == published.sha256
        if (matches) {
            synchronized(matchCache) {
                matchCache[cacheKey] = true
                if (matchCache.size > MATCH_CACHE_LIMIT) {
                    matchCache.keys.firstOrNull()?.let(matchCache::remove)
                }
            }
        }
        return matches
    }

    fun matchesOrSupersedesPublishedPdf(
        file: File,
        published: PhoneRenderReceipt.PublishedPdf,
    ): Boolean {
        return matchesPublishedPdf(file, published) || isNewerThanPublished(file, published)
    }

    fun loadStatusSnapshot(
        verifiedAtByRequest: Map<String, Long> = emptyMap(),
        syncHealth: PhoneRenderSyncHealth = PhoneRenderSyncHealth(),
    ): PhoneRenderStatusSnapshot {
        val requests = loadRequests().associateBy(PhoneRenderRequestSnapshot::requestId)
        val receipts = loadReceipts().associateBy(PhoneRenderReceipt::requestId)
        val resolvedJobs = (requests.keys + receipts.keys)
            .mapNotNull { requestId ->
                val request = requests[requestId]
                val receipt = receipts[requestId]
                val target = receipt?.target ?: request?.target ?: return@mapNotNull null
                if (target != ACTION_ALL &&
                    pdfCapability(File(canonicalPdfDirectory, target)) !=
                    SourcePdfCapability.SOURCE_LINKED
                ) {
                    return@mapNotNull null
                }
                val published = when {
                    receipt == null -> emptyList()
                    receipt.publishedPdfs.isNotEmpty() -> receipt.publishedPdfs
                    receipt.publishedPdf != null -> listOf(receipt.publishedPdf)
                    else -> emptyList()
                }
                val identities = if (
                    receipt?.status in setOf(
                        PhoneRenderReceipt.STATUS_SUCCESS,
                        PhoneRenderReceipt.STATUS_PARTIAL_SUCCESS,
                    ) &&
                    receipt?.changesDetected != false
                ) {
                    published.map { identity ->
                        val file = File(canonicalPdfDirectory, identity.filename)
                        when {
                            matchesPublishedPdf(file, identity) ->
                                PublishedIdentityState.MATCHED
                            isNewerThanPublished(file, identity) ->
                                PublishedIdentityState.SUPERSEDED
                            else -> PublishedIdentityState.PENDING
                        }
                    }
                } else {
                    emptyList()
                }
                val synced = identities.count { it == PublishedIdentityState.MATCHED }
                val pendingPdfFilenames = published.zip(identities)
                    .filter { (_, state) -> state == PublishedIdentityState.PENDING }
                    .map { (identity, _) -> identity.filename }
                    .toSet()
                var visible = true
                val blockingSyncFailure = request?.takeIf {
                    receipt == null && syncHealth.blocks(it.createdAtEpochMillis)
                }
                val stage = when (receipt?.status) {
                    null -> if (blockingSyncFailure != null) {
                        PhoneRenderStage.BLOCKED
                    } else {
                        PhoneRenderStage.QUEUED
                    }
                    PhoneRenderReceipt.STATUS_RUNNING -> if (
                        receipt.progress?.phase == "queued"
                    ) {
                        PhoneRenderStage.FEDORA_QUEUED
                    } else if (receipt.progress?.phase == "checking") {
                        PhoneRenderStage.CHECKING
                    } else {
                        PhoneRenderStage.REBUILDING
                    }
                    PhoneRenderReceipt.STATUS_FAILURE -> PhoneRenderStage.FAILED
                    PhoneRenderReceipt.STATUS_PARTIAL_SUCCESS -> when {
                        identities.any { it == PublishedIdentityState.PENDING } ->
                            PhoneRenderStage.WAITING_FOR_SYNC
                        else -> PhoneRenderStage.PARTIAL
                    }
                    PhoneRenderReceipt.STATUS_SUCCESS -> when {
                        receipt.changesDetected == false -> {
                            visible = false
                            PhoneRenderStage.UPDATED
                        }
                        published.isEmpty() -> {
                            visible = request != null
                            PhoneRenderStage.UNVERIFIED
                        }
                        identities.any { it == PublishedIdentityState.PENDING } ->
                            PhoneRenderStage.WAITING_FOR_SYNC
                        receipt.requiresAttention && target != ACTION_ALL ->
                            PhoneRenderStage.ATTENTION
                        identities.any { it == PublishedIdentityState.SUPERSEDED } -> {
                            visible = false
                            PhoneRenderStage.UPDATED
                        }
                        else -> PhoneRenderStage.UPDATED
                    }
                    else -> PhoneRenderStage.UNVERIFIED
                }
                ResolvedPhoneRenderJob(
                    PhoneRenderJobStatus(
                        requestId = requestId,
                        target = target,
                        stage = stage,
                        message = if (blockingSyncFailure != null) {
                            syncHealth.errorMessage
                        } else {
                            receipt?.message.orEmpty()
                        },
                        createdAtEpochMillis = request?.createdAtEpochMillis ?: 0,
                        startedAtEpochMillis = receipt?.startedAtEpochMillis ?: 0,
                        completedAtEpochMillis = receipt?.completedAtEpochMillis ?: 0,
                        verifiedAtEpochMillis = verifiedAtByRequest[requestId] ?: 0,
                        publishedPdfCount = published.size,
                        syncedPdfCount = synced,
                        progress = receipt?.progress,
                        publishedPdfFilenames = published.map { it.filename }.toSet(),
                        pendingPdfFilenames = pendingPdfFilenames,
                        affectedPdfFilenames = receipt?.affectedPdfFilenames.orEmpty(),
                        attentionPdfs = receipt?.attentionPdfs.orEmpty(),
                        hasAttentionPdfInventory = receipt?.hasAttentionPdfInventory == true,
                        changesDetected = receipt?.changesDetected,
                        failedDocuments = receipt?.failedDocuments.orEmpty(),
                        logReference = receipt?.logReference.orEmpty(),
                        buildId = receipt?.buildId.orEmpty(),
                        repairMode = receipt?.repairMode ?: request?.repairMode ?: REPAIR_NONE,
                        conditionalCheck = request?.action == ACTION_CHECK_ALL,
                    ),
                    visible,
                )
            }
        val latestJobs = resolvedJobs
            .groupBy { it.status.target }
            .mapNotNull { (_, targetJobs) ->
                // Requests created in the same millisecond tie on activity; the later
                // completion is the newer job.
                targetJobs
                    .maxWithOrNull(compareBy<ResolvedPhoneRenderJob>({ it.status.activityAtEpochMillis },
                        { it.status.completedAtEpochMillis }, { it.status.startedAtEpochMillis }))
                    ?.takeIf(ResolvedPhoneRenderJob::visible)
                    ?.status
            }
            .sortedByDescending(PhoneRenderJobStatus::activityAtEpochMillis)
        val authoritativeAll = resolvedJobs
            .map(ResolvedPhoneRenderJob::status)
            .filter {
                it.affectsAll &&
                    it.stage == PhoneRenderStage.UPDATED &&
                    it.hasAttentionPdfInventory
            }
            .maxByOrNull(PhoneRenderJobStatus::activityAtEpochMillis)
        val jobsAfterAttentionInventory = if (authoritativeAll == null) {
            latestJobs
        } else {
            val retained = latestJobs.filterNot { job ->
                !job.affectsAll &&
                    job.target in authoritativeAll.publishedPdfFilenames &&
                    job.activityAtEpochMillis <= authoritativeAll.activityAtEpochMillis &&
                    (job.stage == PhoneRenderStage.ATTENTION ||
                        job.stage == PhoneRenderStage.FAILED)
            }
            val currentAttention = authoritativeAll.attentionPdfs.mapNotNull { attention ->
                if (pdfCapability(File(canonicalPdfDirectory, attention.filename)) !=
                    SourcePdfCapability.SOURCE_LINKED
                ) {
                    return@mapNotNull null
                }
                PhoneRenderJobStatus(
                    requestId = "${authoritativeAll.requestId}:${attention.filename}",
                    target = attention.filename,
                    stage = PhoneRenderStage.ATTENTION,
                    bannerEligible = false,
                    message = "${attention.commentCount} comment(s) could not be anchored.",
                    createdAtEpochMillis = authoritativeAll.createdAtEpochMillis,
                    startedAtEpochMillis = authoritativeAll.startedAtEpochMillis,
                    completedAtEpochMillis = authoritativeAll.completedAtEpochMillis,
                    verifiedAtEpochMillis = authoritativeAll.verifiedAtEpochMillis,
                    publishedPdfCount = 1,
                    syncedPdfCount = 1,
                )
            }
            (retained + currentAttention)
                .sortedByDescending(PhoneRenderJobStatus::activityAtEpochMillis)
        }
        val latestCurrentAll = resolvedJobs
            .map(ResolvedPhoneRenderJob::status)
            .filter {
                it.affectsAll &&
                    it.stage == PhoneRenderStage.UPDATED &&
                    it.changesDetected == false
            }
            .maxByOrNull(PhoneRenderJobStatus::activityAtEpochMillis)
        val jobsAfterAttention = if (latestCurrentAll == null) {
            jobsAfterAttentionInventory
        } else {
            jobsAfterAttentionInventory.filterNot { job ->
                !job.affectsAll &&
                    job.stage == PhoneRenderStage.FAILED &&
                    job.activityAtEpochMillis <= latestCurrentAll.activityAtEpochMillis
            }
        }
        val latestPartial = resolvedJobs
            .map(ResolvedPhoneRenderJob::status)
            .filter {
                it.affectsAll &&
                    it.stage == PhoneRenderStage.PARTIAL &&
                    it.failedDocuments.isNotEmpty()
            }
            .maxByOrNull(PhoneRenderJobStatus::activityAtEpochMillis)
        val authoritativePartial = latestPartial?.takeIf { partial ->
            listOfNotNull(authoritativeAll, latestCurrentAll).all { completedAll ->
                partial.activityAtEpochMillis > completedAll.activityAtEpochMillis
            }
        }
        val jobs = if (authoritativePartial == null) {
            jobsAfterAttention
        } else {
            val coveredFilenames = authoritativePartial.publishedPdfFilenames +
                authoritativePartial.failedDocuments.map(PhoneRenderFailure::pdfFilename)
            val retained = jobsAfterAttention.filterNot { job ->
                !job.affectsAll &&
                    job.target in coveredFilenames &&
                    job.activityAtEpochMillis <= authoritativePartial.activityAtEpochMillis
            }
            val updated = authoritativePartial.publishedPdfFilenames.map { filename ->
                PhoneRenderJobStatus(
                    requestId = "${authoritativePartial.requestId}:$filename:updated",
                    target = filename,
                    stage = PhoneRenderStage.UPDATED,
                    message = "",
                    completedAtEpochMillis = authoritativePartial.completedAtEpochMillis,
                    verifiedAtEpochMillis = authoritativePartial.verifiedAtEpochMillis,
                    bannerEligible = false,
                    publishedPdfCount = 1,
                    syncedPdfCount = 1,
                    changesDetected = true,
                )
            }
            val failed = authoritativePartial.failedDocuments.map { failure ->
                PhoneRenderJobStatus(
                    requestId = "${authoritativePartial.requestId}:${failure.pdfFilename}:failed",
                    target = failure.pdfFilename,
                    stage = PhoneRenderStage.PARTIAL,
                    message = failure.message,
                    completedAtEpochMillis = authoritativePartial.completedAtEpochMillis,
                    verifiedAtEpochMillis = authoritativePartial.verifiedAtEpochMillis,
                    bannerEligible = false,
                    failedDocuments = listOf(failure),
                    logReference = authoritativePartial.logReference,
                    buildId = authoritativePartial.buildId,
                    changesDetected = true,
                )
            }
            (retained + updated + failed)
                .sortedByDescending(PhoneRenderJobStatus::activityAtEpochMillis)
        }
        return PhoneRenderStatusSnapshot(jobs)
    }

    private fun isNewerThanPublished(
        file: File,
        published: PhoneRenderReceipt.PublishedPdf,
    ): Boolean {
        return file.isFile &&
            published.modifiedEpochMillis > 0 &&
            file.lastModified() >
            published.modifiedEpochMillis + PUBLISHED_MTIME_TOLERANCE_MILLIS
    }

    fun consumeCompletedReceipts(targetFilename: String? = null): List<PhoneRenderReceipt> {
        if (!receiptDirectory.isDirectory) {
            return emptyList()
        }
        return requestFiles().mapNotNull { requestFile ->
            val receiptFile = File(receiptDirectory, requestFile.name)
            if (!receiptFile.isFile) {
                return@mapNotNull null
            }
            val receipt = parseReceipt(receiptFile) ?: return@mapNotNull null
            if (receipt.requestId != requestFile.nameWithoutExtension) {
                return@mapNotNull null
            }
            if (receipt.status != PhoneRenderReceipt.STATUS_SUCCESS &&
                receipt.status != PhoneRenderReceipt.STATUS_PARTIAL_SUCCESS &&
                receipt.status != PhoneRenderReceipt.STATUS_FAILURE
            ) {
                return@mapNotNull null
            }
            if (targetFilename != null && receipt.target != targetFilename) {
                return@mapNotNull null
            }
            if (!requestFile.delete()) {
                return@mapNotNull null
            }
            receipt
        }
    }

    fun receiptForRequest(requestId: String): PhoneRenderReceipt? {
        val validId = runCatching { UUID.fromString(requestId).toString() }.getOrNull()
            ?: return null
        val receipt = parseReceipt(File(receiptDirectory, "$validId.$JSON_EXTENSION"))
            ?: return null
        return receipt.takeIf { it.requestId == validId }
    }

    fun isRequestSettled(requestId: String): Boolean {
        val receipt = receiptForRequest(requestId) ?: return false
        if (receipt.status == PhoneRenderReceipt.STATUS_RUNNING) return false
        if (receipt.status == PhoneRenderReceipt.STATUS_FAILURE) return true
        if (receipt.changesDetected == false) return true
        val published = receipt.publishedPdfs.ifEmpty {
            listOfNotNull(receipt.publishedPdf)
        }
        if (receipt.status == PhoneRenderReceipt.STATUS_PARTIAL_SUCCESS && published.isEmpty()) {
            return true
        }
        return published.isNotEmpty() && published.all { identity ->
            matchesOrSupersedesPublishedPdf(
                File(canonicalPdfDirectory, identity.filename),
                identity,
            )
        }
    }

    fun observe(onChanged: () -> Unit): FileObserver {
        receiptDirectory.mkdirs()
        return object : FileObserver(
            receiptDirectory,
            CREATE or MOVED_TO or CLOSE_WRITE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path?.endsWith(".$JSON_EXTENSION", ignoreCase = true) == true) {
                    onChanged()
                }
            }
        }.also(FileObserver::startWatching)
    }

    private fun enqueue(
        action: String,
        pdfFilename: String?,
        requiredComments: Collection<CommentAnchorRequirement>,
        commentRecords: Collection<CommentDeliveryRecord>,
        sourceFrontier: Long?,
        inputSnapshotSha256: String?,
        repairMode: String,
    ): RenderEnqueueResult {
        requestDirectory.mkdirs()
        val normalizedRequirements = normalizeRequirements(requiredComments)
        val normalizedRecords = commentRecords
            .filter { it.id.isNotBlank() && it.revision > 0 }
            .groupBy(CommentDeliveryRecord::id)
            .map { (_, records) -> records.maxBy(CommentDeliveryRecord::revision) }
            .sortedBy(CommentDeliveryRecord::id)
        require(normalizedRecords.map(CommentDeliveryRecord::id).toSet() ==
            normalizedRequirements.map(CommentAnchorRequirement::id).toSet()
        ) {
            "Comment records must exactly match required comments"
        }
        normalizedRecords.forEach { record ->
            require(
                normalizedRequirements.any {
                    it.id == record.id && it.revision == record.revision
                }
            ) {
                "Comment record revisions must match required comments"
            }
        }
        findPending(
            action,
            pdfFilename,
            normalizedRequirements,
            sourceFrontier,
            inputSnapshotSha256,
            repairMode,
        )?.let {
            return RenderEnqueueResult(it, alreadyPending = true)
        }

        val requestId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("request_id", requestId)
            addProperty("created_at", Instant.now().toString())
            addProperty("action", action)
            addProperty("repair_mode", repairMode)
            if (pdfFilename != null) {
                addProperty("pdf_filename", pdfFilename)
            }
            sourceFrontier?.let { addProperty("source_frontier", it) }
            inputSnapshotSha256?.let { addProperty("input_snapshot_sha256", it) }
            add("required_comments", JsonArray().apply {
                normalizedRequirements.forEach { requirement ->
                    add(JsonObject().apply {
                        addProperty("id", requirement.id)
                        addProperty("revision", requirement.revision)
                    })
                }
            })
            add("comment_records", JsonArray().apply {
                normalizedRecords.forEach { record ->
                    add(JsonParser.parseString(record.json))
                }
            })
        }
        val requestFile = File(requestDirectory, "$requestId.$JSON_EXTENSION")
        atomicWrite(requestFile, payload.toString())
        requestScan(requestFile.name)
        return RenderEnqueueResult(requestId, alreadyPending = false)
    }

    private fun normalizeRequirements(
        requiredComments: Collection<CommentAnchorRequirement>,
    ): List<CommentAnchorRequirement> {
        return requiredComments
            .filter { it.id.isNotBlank() && it.revision > 0 }
            .groupBy(CommentAnchorRequirement::id)
            .map { (id, requirements) ->
                CommentAnchorRequirement(id, requirements.maxOf(CommentAnchorRequirement::revision))
            }
            .sortedBy(CommentAnchorRequirement::id)
    }

    private fun findPending(
        action: String,
        pdfFilename: String?,
        requiredComments: List<CommentAnchorRequirement>,
        sourceFrontier: Long? = null,
        inputSnapshotSha256: String? = null,
        repairMode: String = REPAIR_NONE,
    ): String? {
        return requestFiles().firstNotNullOfOrNull { requestFile ->
            if (receiptIsTerminal(File(receiptDirectory, requestFile.name))) {
                return@firstNotNullOfOrNull null
            }
            val payload = parseObject(requestFile) ?: return@firstNotNullOfOrNull null
            val pendingAction = payload.string("action")
            val sameAction = pendingAction == action ||
                (action == ACTION_CHECK_ALL && pendingAction == ACTION_ALL)
            val samePdf = if (action == ACTION_PDF) {
                payload.string("pdf_filename") == pdfFilename
            } else {
                true
            }
            val sameSourceFrontier = sourceFrontier == null ||
                payload.get("source_frontier")?.asLong == sourceFrontier
            val sameInputSnapshot = inputSnapshotSha256 == null ||
                payload.string("input_snapshot_sha256") == inputSnapshotSha256
            val sameRepairMode = payload.string("repair_mode").orEmpty()
                .ifBlank { REPAIR_NONE } == repairMode
            val pendingRequirements = payload.get("required_comments")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { value ->
                    val entry = value.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@mapNotNull null
                    val id = entry.string("id") ?: return@mapNotNull null
                    val revision = entry.int("revision") ?: return@mapNotNull null
                    CommentAnchorRequirement(id, revision)
                }
                .orEmpty()
                .associate { it.id to it.revision }
            val coversRequirements = requiredComments.isEmpty() ||
                requiredComments.all { requirement ->
                    (pendingRequirements[requirement.id] ?: 0) >= requirement.revision
                }
            payload.string("request_id").takeIf {
                sameAction && samePdf && sameSourceFrontier && sameInputSnapshot &&
                    sameRepairMode && coversRequirements
            }
        }
    }

    private fun parseReceipt(file: File): PhoneRenderReceipt? {
        return runCatching {
            val payload = parseObject(file) ?: return@runCatching null
            val schemaVersion = payload.int("schema_version") ?: return@runCatching null
            if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
                return@runCatching null
            }
            val requestId = payload.string("request_id") ?: return@runCatching null
            val target = payload.string("target") ?: return@runCatching null
            val status = payload.string("status") ?: return@runCatching null
            val message = payload.string("message").orEmpty()
            val exitCode = payload.int("exit_code") ?: -1
            val published = payload.get("published_pdf")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.toPublishedPdf()
                ?.takeIf { isCanonicalPublishedPdfFilename(it.filename) }
            val publishedPdfs = payload.get("published_pdfs")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { value ->
                    value.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.toPublishedPdf()
                        ?.takeIf { isCanonicalPublishedPdfFilename(it.filename) }
                }
                .orEmpty()
            val anchorResults = payload.get("anchor_results")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { value ->
                    val result = value.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@mapNotNull null
                    val id = result.string("id") ?: return@mapNotNull null
                    val revision = result.int("revision") ?: 0
                    val anchorStatus = result.string("status")
                        ?.takeIf { it in PhoneAnchorResult.supportedStatuses }
                        ?: return@mapNotNull null
                    PhoneAnchorResult(
                        id,
                        revision,
                        anchorStatus,
                        result.string("confidence").orEmpty(),
                    )
                }
                .orEmpty()
            val progress = payload.get("progress")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.toPhoneRenderProgress()
            val affectedPdfFilenames = payload.get("affected_pdfs")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { value ->
                    value.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.takeIf(::isCanonicalPublishedPdfFilename)
                }
                .orEmpty()
                .toSet()
            val attentionPdfElement = payload.get("attention_pdfs")
            val hasAttentionPdfInventory = attentionPdfElement?.isJsonArray == true
            val attentionPdfs = attentionPdfElement
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { value ->
                    val entry = value.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@mapNotNull null
                    val filename = entry.string("filename")
                        ?.takeIf(::isCanonicalPublishedPdfFilename)
                        ?: return@mapNotNull null
                    val commentCount = entry.int("comment_count")
                        ?.takeIf { it > 0 }
                        ?: return@mapNotNull null
                    PhonePdfAttention(filename, commentCount)
                }
                .orEmpty()
            val failedDocuments = payload.get("failed_documents")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { value ->
                    val entry = value.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@mapNotNull null
                    val pdfFilename = entry.string("pdf_filename")
                        ?.takeIf(::isCanonicalPublishedPdfFilename)
                        ?: return@mapNotNull null
                    val source = entry.string("source")
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    val message = entry.string("message")
                        ?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    PhoneRenderFailure(pdfFilename, source, message)
                }
                .orEmpty()
            val completedAt = payload.instantMillis("completed_at")
            PhoneRenderReceipt(
                schemaVersion,
                requestId,
                target,
                status,
                message,
                exitCode,
                published,
                anchorResults,
                startedAtEpochMillis = payload.instantMillis("started_at"),
                completedAtEpochMillis = when {
                    completedAt > 0 -> completedAt
                    status != PhoneRenderReceipt.STATUS_RUNNING -> file.lastModified()
                    else -> 0
                },
                publishedPdfs = publishedPdfs,
                affectedPdfFilenames = affectedPdfFilenames,
                attentionRequired = payload.boolean("attention_required") ?: false,
                attentionPdfs = attentionPdfs,
                hasAttentionPdfInventory = hasAttentionPdfInventory,
                progress = progress,
                changesDetected = payload.boolean("changes_detected"),
                failedDocuments = failedDocuments,
                logReference = payload.string("log_reference").orEmpty(),
                repairMode = payload.string("repair_mode").orEmpty().ifBlank { REPAIR_NONE },
                buildId = payload.string("build_id").orEmpty(),
            )
        }.getOrNull()
    }

    private fun loadRequests(): List<PhoneRenderRequestSnapshot> {
        return requestFiles().mapNotNull { file ->
            val payload = parseObject(file) ?: return@mapNotNull null
            val requestId = payload.string("request_id")
                ?.takeIf { it == file.nameWithoutExtension }
                ?: return@mapNotNull null
            val action = payload.string("action") ?: return@mapNotNull null
            val target = if (action == ACTION_ALL || action == ACTION_CHECK_ALL) {
                ACTION_ALL
            } else {
                payload.string("pdf_filename") ?: return@mapNotNull null
            }
            PhoneRenderRequestSnapshot(
                requestId,
                action,
                target,
                payload.instantMillis("created_at").takeIf { it > 0 } ?: file.lastModified(),
                payload.string("repair_mode").orEmpty().ifBlank { REPAIR_NONE },
            )
        }
    }

    private fun loadReceipts(): List<PhoneRenderReceipt> {
        return receiptDirectory.listFiles { file ->
            file.isFile && file.extension.equals(JSON_EXTENSION, ignoreCase = true)
        }.orEmpty().mapNotNull(::parseReceipt)
    }

    private fun requestFiles(): List<File> {
        return requestDirectory.listFiles { file ->
            file.isFile && file.extension.equals(JSON_EXTENSION, ignoreCase = true)
        }.orEmpty().toList()
    }

    private fun receiptIsTerminal(file: File): Boolean {
        if (!file.isFile) {
            return false
        }
        return parseReceipt(file)?.status != PhoneRenderReceipt.STATUS_RUNNING
    }

    private fun parseObject(file: File): JsonObject? {
        return runCatching {
            JsonParser.parseString(file.readText()).asJsonObject
        }.getOrNull()
    }

    private fun atomicWrite(destination: File, contents: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(
            destination.parentFile,
            ".${destination.name}.${UUID.randomUUID()}.tmp",
        )
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write((contents + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            check(temporary.renameTo(destination)) {
                "Could not publish render request"
            }
            destination.parentFile?.let(directorySync)
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(file: File): String? = runCatching {
        FileInputStream(file).use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val amountRead = stream.read(buffer)
                if (amountRead == -1) break
                digest.update(buffer, 0, amountRead)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }.getOrNull()

    private fun canonicalPath(file: File?): String? {
        return file?.let { runCatching { it.canonicalPath }.getOrNull() }
    }

    companion object {
        const val SCHEMA_VERSION = 7
        val SUPPORTED_SCHEMA_VERSIONS = (1..SCHEMA_VERSION).toSet()
        const val ACTION_PDF = "pdf"
        const val ACTION_ALL = "all"
        const val ACTION_CHECK_ALL = "check_all"
        const val REPAIR_NONE = "none"
        const val REPAIR_CODEX = "codex"
        private const val JSON_EXTENSION = "json"
        private const val PDF_EXTENSION = ".pdf"
        private const val MATCH_CACHE_LIMIT = 256
        private const val PUBLISHED_MTIME_TOLERANCE_MILLIS = 1_000L

        fun defaultCanonicalPdfDirectory(): File = ThoughtsLocalStore.currentPdfs()

        fun defaultRequestDirectory(): File = ThoughtsLocalStore.renderRequests()

        fun defaultReceiptDirectory(): File = ThoughtsLocalStore.renderReceipts()
    }
}

private enum class PublishedIdentityState {
    MATCHED,
    SUPERSEDED,
    PENDING,
}

private data class ResolvedPhoneRenderJob(
    val status: PhoneRenderJobStatus,
    val visible: Boolean,
)

private fun syncRenderDirectoryDurably(directory: File) {
    val descriptor = try {
        Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    } catch (error: ErrnoException) {
        throw IllegalStateException("Could not open render request directory", error)
    }
    try {
        Os.fsync(descriptor)
    } catch (error: ErrnoException) {
        throw IllegalStateException("Could not durability-sync render request directory", error)
    } finally {
        Os.close(descriptor)
    }
}

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

internal fun isCanonicalPublishedPdfFilename(filename: String): Boolean {
    val lower = filename.lowercase()
    return filename.isNotBlank() &&
        filename == File(filename).name &&
        lower.endsWith(".pdf") &&
        !filename.startsWith(".") &&
        ".sync-conflict-" !in lower &&
        !lower.endsWith(".tmp") &&
        !lower.endsWith(".saving")
}

private fun JsonObject.string(name: String): String? {
    return get(name)?.takeUnless { it.isJsonNull }?.asString
}

private fun JsonObject.int(name: String): Int? {
    return runCatching { get(name)?.asInt }.getOrNull()
}

private fun JsonObject.long(name: String): Long? {
    return runCatching { get(name)?.asLong }.getOrNull()
}

private fun JsonObject.boolean(name: String): Boolean? {
    return runCatching { get(name)?.asBoolean }.getOrNull()
}

private fun JsonObject.instantMillis(name: String): Long {
    return string(name)
        ?.let { value -> runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() }
        ?: 0
}

private fun JsonObject.toPublishedPdf(): PhoneRenderReceipt.PublishedPdf? {
    val filename = string("filename") ?: return null
    val modified = long("modified_epoch_ms") ?: return null
    val size = long("size_bytes") ?: return null
    val sha256 = string("sha256")
        ?.takeIf(SHA256_REGEX::matches)
        .orEmpty()
    return PhoneRenderReceipt.PublishedPdf(filename, modified, size, sha256)
}

private fun JsonObject.toPhoneRenderProgress(): PhoneRenderProgress? {
    val phase = string("phase")
        ?.takeIf { it in SUPPORTED_PROGRESS_PHASES }
        ?: return null
    val completed = int("completed")?.takeIf { it >= 0 } ?: return null
    val total = int("total")?.takeIf { it >= 0 } ?: return null
    if (total > 0 && completed > total) {
        return null
    }
    val unit = string("unit")
        ?.takeIf { it in SUPPORTED_PROGRESS_UNITS }
        ?: return null
    return PhoneRenderProgress(
        phase,
        completed,
        total,
        unit,
        string("detail").orEmpty(),
        instantMillis("updated_at"),
    )
}

private val SUPPORTED_PROGRESS_PHASES = setOf(
    "queued",
    "checking",
    "preparing_comments",
    "compiling",
    "resolving_links",
    "publishing",
    "validating",
    "codex_repair",
)

private val SUPPORTED_PROGRESS_UNITS = setOf("steps", "files", "projects", "pdfs")
