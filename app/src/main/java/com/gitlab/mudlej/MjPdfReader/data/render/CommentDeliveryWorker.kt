// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAnchorRequirement
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsNetworkPolicy
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.TimeUnit

object CommentDeliveryScheduler {
    private const val WORK_NAME = "comment-delivery"
    private const val EDIT_DEBOUNCE_MILLIS = 1_000L

    fun schedule(
        context: Context,
        explicit: Boolean = false,
        targetPdfFilename: String? = null,
    ) {
        if (!explicit || !ThoughtsNetworkPolicy.requiredVpnActive(context)) return
        enqueue(context, EDIT_DEBOUNCE_MILLIS, ExistingWorkPolicy.REPLACE, targetPdfFilename)
    }

    fun resume(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    private fun enqueue(
        context: Context,
        delayMillis: Long,
        policy: ExistingWorkPolicy,
        targetPdfFilename: String? = null,
    ) {
        val request = OneTimeWorkRequestBuilder<CommentDeliveryWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(workDataOf(INPUT_TARGET_PDF to targetPdfFilename))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private const val INPUT_TARGET_PDF = "target_pdf"
}

class CommentDeliveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!ThoughtsNetworkPolicy.requiredVpnActive(applicationContext)) {
            return Result.failure()
        }
        val outbox = CommentDeliveryOutbox.forContext(applicationContext)
        val requestedTarget = inputData.getString("target_pdf")
        val batches = outbox.load().filter { requestedTarget == null || it.target == requestedTarget }
        if (batches.isEmpty()) {
            return Result.success()
        }
        val root = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val sourceIndexSnapshot = SourceLinkedPdfIndex(root).snapshot()
        val routedBatches = batches.groupBy(PendingCommentDelivery::target).map { (target, targetBatches) ->
            RoutedCommentDelivery(
                target,
                targetBatches,
                commentDeliveryRoute(target, sourceIndexSnapshot, root),
            )
        }
        val acceptedBatches = routedBatches
            .filter { it.route == CommentDeliveryRoute.SOURCE_LINKED || it.route == CommentDeliveryRoute.UNLINKED }
            .flatMap(RoutedCommentDelivery::batches)
        val rejectedBatchIds = routedBatches
            .filter { it.route == CommentDeliveryRoute.REJECTED }
            .flatMap { delivery -> delivery.batches.map(PendingCommentDelivery::batchId) }
        outbox.remove(rejectedBatchIds)
        if (acceptedBatches.isNotEmpty()) {
            val repository = FedoraRepository(applicationContext)
            val delta = try {
                repository.captureCommentBatches(acceptedBatches)
                val operationStore = SyncOperationStore(applicationContext)
                val operation = requestedTarget
                    ?.let(operationStore::pendingTargetOperation)
                    ?: operationStore.pendingGlobalOperation()
                val operationId = operation?.id
                    ?: operationStore.begin(requestedTarget, building = false)
                if (
                    !SyncTransferCoordinator.prepare(
                        applicationContext,
                        explicit = true,
                        targetPdfFilename = requestedTarget,
                        operationId = operationId,
                        building = operation?.kind == SyncOperationKind.BUILD,
                    )
                ) {
                    return Result.success()
                }
                repository.syncDeltas(requestedTarget)
            } catch (error: Exception) {
                Log.w(
                    COMMENT_DELIVERY_LOG_TAG,
                    "Comment delta sync failed; retaining ${acceptedBatches.size} durable batches",
                    error,
                )
                return Result.retry()
            }
            if (delta.buildFreeAnchoringSupported) {
                val settledBatchIds = settledLogicalDeliveryBatchIds(
                    acceptedBatches,
                    com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSidecarStore.defaultSidecarRoot(),
                )
                outbox.remove(settledBatchIds)
                val unsettled = settledBatchIds.size != acceptedBatches.size
                return if (routedBatches.any { it.route == CommentDeliveryRoute.INDEX_UNAVAILABLE }) {
                    Result.retry()
                } else if (unsettled) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        }
        return deliverWithLegacyRenderPath(routedBatches, outbox)
    }

    private fun deliverWithLegacyRenderPath(
        routedBatches: List<RoutedCommentDelivery>,
        outbox: CommentDeliveryOutbox,
    ): Result {
        val queue = PhoneRenderQueue()
        val root = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        var retryNeeded = false
        var syncReady = false
        val completedBatchIds = mutableListOf<String>()
        routedBatches.forEach { delivery ->
            val batchIds = delivery.batches.map(PendingCommentDelivery::batchId)
            when (delivery.route) {
                CommentDeliveryRoute.REJECTED -> {
                    return@forEach
                }
                CommentDeliveryRoute.INDEX_UNAVAILABLE -> {
                    retryNeeded = true
                    return@forEach
                }
                CommentDeliveryRoute.UNLINKED -> {
                    completedBatchIds += batchIds
                    syncReady = true
                    return@forEach
                }
                CommentDeliveryRoute.SOURCE_LINKED -> check(
                    commentDeliveryNeedsRender(delivery.route, buildFreeAnchoringSupported = false),
                )
            }
            val pdf = File(root, delivery.target)
            if (!pdf.isFile) {
                retryNeeded = true
                return@forEach
            }
            val records = newestRecords(delivery.batches.flatMap(PendingCommentDelivery::records))
            val requirements = records.map {
                CommentAnchorRequirement(it.id, it.revision)
            }
            if (queue.hasPendingPdf(pdf)) {
                if (queue.pendingPdfCovers(pdf, requirements)) {
                    completedBatchIds += batchIds
                    syncReady = true
                } else {
                    retryNeeded = true
                }
                return@forEach
            }
            val published = runCatching {
                queue.enqueuePdf(pdf, requirements, records)
            }
            if (published.isSuccess) {
                completedBatchIds += batchIds
                syncReady = true
            } else {
                retryNeeded = true
            }
        }
        if (syncReady) {
            val scheduled = runCatching {
                ThoughtsSyncScheduler.enqueueBackground(applicationContext)
            }.isSuccess
            if (scheduled) {
                outbox.remove(completedBatchIds)
            } else {
                retryNeeded = true
            }
        }
        if (retryNeeded) {
            Log.w(
                COMMENT_DELIVERY_LOG_TAG,
                "Comment delivery remains incomplete; retained " +
                    "${routedBatches.sumOf { it.batches.size } - completedBatchIds.size} batches",
            )
            return Result.retry()
        }
        return Result.success()
    }
}

private const val COMMENT_DELIVERY_LOG_TAG = "ThoughtsCommentSync"

internal enum class CommentDeliveryRoute {
    SOURCE_LINKED,
    UNLINKED,
    REJECTED,
    INDEX_UNAVAILABLE,
}

internal fun commentDeliveryNeedsRender(
    route: CommentDeliveryRoute,
    buildFreeAnchoringSupported: Boolean,
): Boolean = route == CommentDeliveryRoute.SOURCE_LINKED && !buildFreeAnchoringSupported

internal fun logicalCommentRecordsSettled(
    sidecar: File,
    requirements: Collection<CommentDeliveryRecord>,
): Boolean {
    if (requirements.isEmpty() || !sidecar.isFile) return false
    val records = runCatching {
        sidecar.readLines().mapNotNull { line ->
            line.takeIf(String::isNotBlank)
                ?.let(JsonParser::parseString)
                ?.takeIf { value -> value.isJsonObject }
                ?.asJsonObject
        }
    }.getOrElse { return false }
    val byId = records.groupBy { record ->
        record.get("id")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }.mapValues { (_, candidates) ->
        candidates.maxByOrNull { record ->
            record.get("revision")?.takeUnless { it.isJsonNull }?.asInt ?: 0
        }
    }
    return requirements.all { requirement ->
        val record = byId[requirement.id] ?: return@all false
        val revision = record.get("revision")?.takeUnless { it.isJsonNull }?.asInt ?: return@all false
        revision >= requirement.revision
    }
}

internal fun settledLogicalDeliveryBatchIds(
    batches: Collection<PendingCommentDelivery>,
    sidecarRoot: File,
): List<String> = batches.filter { delivery ->
    logicalCommentRecordsSettled(
        File(sidecarRoot, "${delivery.target.removeSuffix(".pdf")}_comments.jsonl"),
        delivery.records,
    )
}.map(PendingCommentDelivery::batchId)

private data class RoutedCommentDelivery(
    val target: String,
    val batches: List<PendingCommentDelivery>,
    val route: CommentDeliveryRoute,
)

internal fun commentDeliveryRoute(
    target: String,
    sourceIndexSnapshot: SourceLinkedPdfIndexSnapshot,
    canonicalPdfDirectory: File,
): CommentDeliveryRoute {
    if (File(target).name != target || !target.endsWith(".pdf", ignoreCase = true)) {
        return CommentDeliveryRoute.REJECTED
    }
    val pdf = File(canonicalPdfDirectory, target)
    if (sourceIndexSnapshot.libraryAvailable) {
        return when (sourceIndexSnapshot.libraryKindFor(pdf, canonicalPdfDirectory)) {
            LibraryPdfKind.SOURCE_LINKED -> CommentDeliveryRoute.SOURCE_LINKED
            LibraryPdfKind.UNLINKED -> CommentDeliveryRoute.UNLINKED
            null -> CommentDeliveryRoute.REJECTED
        }
    }
    if (!sourceIndexSnapshot.available) {
        return CommentDeliveryRoute.INDEX_UNAVAILABLE
    }
    return when (sourceIndexSnapshot.capabilityFor(pdf, canonicalPdfDirectory)) {
        SourcePdfCapability.SOURCE_LINKED -> CommentDeliveryRoute.SOURCE_LINKED
        SourcePdfCapability.NOT_SOURCE_LINKED -> CommentDeliveryRoute.REJECTED
        SourcePdfCapability.INDEX_UNAVAILABLE -> CommentDeliveryRoute.INDEX_UNAVAILABLE
    }
}

internal fun newestRecords(
    records: Collection<CommentDeliveryRecord>,
): List<CommentDeliveryRecord> {
    return records
        .groupBy(CommentDeliveryRecord::id)
        .map { (_, candidates) ->
            candidates.maxWithOrNull(
                compareBy<CommentDeliveryRecord>(
                    CommentDeliveryRecord::revision,
                    CommentDeliveryRecord::json,
                )
            )!!
        }
        .sortedBy(CommentDeliveryRecord::id)
}
