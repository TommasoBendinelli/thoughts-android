// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import com.gitlab.mudlej.MjPdfReader.data.sync.AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE
import com.gitlab.mudlej.MjPdfReader.data.sync.proposalDecisionAwaitsSync
import android.content.Context
import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSidecarStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryHeadStateStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ArtifactSyncPlan
import com.gitlab.mudlej.MjPdfReader.data.sync.ArtifactSyncStateStore
import com.gitlab.mudlej.MjPdfReader.data.sync.reconcileRemoteLibraryStatus
import com.gitlab.mudlej.MjPdfReader.data.sync.supersededExplicitPdfRenderStatusFilenames
import com.google.gson.JsonParser

/** Shared Android status projection used by the Home and reader Sync controls. */
class DocumentSyncStatusLoader(private val context: Context) {

    suspend fun load(target: String? = null, currentUri: Uri? = null): PhoneRenderStatusSnapshot =
        loadForTargets(
            targets = target?.let(::setOf),
            currentUriByTarget = if (target != null && currentUri != null) {
                mapOf(target to currentUri)
            } else {
                emptyMap()
            },
        )

    /**
     * Projects can publish more than one PDF. [targets] selects the project's owned
     * filenames so pending and in-flight work stays scoped to that project; null keeps
     * the original "no target filter" behaviour for the library-wide status surface.
     */
    suspend fun loadForTargets(
        targets: Set<String>?,
        currentUriByTarget: Map<String, Uri> = emptyMap(),
    ): PhoneRenderStatusSnapshot {
        val dao = AppDatabase.getInstance(context.applicationContext).thoughtsSyncDao()
        val syncState = dao.state()
        val localLibrary = SourceLinkedPdfIndex().snapshot()
        val remoteHead = LibraryHeadStateStore(context.applicationContext).load()
        val reconciled = reconcileRemoteLibraryStatus(
            snapshot = PhoneRenderQueue().loadStatusSnapshot(
                syncHealth = PhoneRenderSyncHealth(
                    lastAttemptEpochMillis = syncState?.lastSyncAttemptEpochMillis ?: 0,
                    lastSuccessEpochMillis = syncState?.lastSyncSuccessEpochMillis ?: 0,
                    errorCode = syncState?.lastSyncErrorCode.orEmpty(),
                    errorMessage = syncState?.lastSyncErrorMessage.orEmpty(),
                ),
            ),
            remoteHead = remoteHead,
            localLibrary = localLibrary,
        )
        val history = PhoneRenderStatusHistory(context.applicationContext)
        val explicit = history.pdfRenderStatuses()
        val superseded = supersededExplicitPdfRenderStatusFilenames(
            remoteHead,
            localLibrary,
            explicit,
        )
        history.removePdfRenderStatuses(superseded)
        val remote = mergeExplicitPdfRenderStatuses(
            reconciled,
            explicit.filterNot { it.filename in superseded },
            localLibrary,
        )
        val sourceQueue = SourceEditQueue(context.applicationContext, scheduleRequests = false)
        sourceQueue.captureExternalEdits()
        val pendingSource = pendingSourceRenderStatuses(
            mutations = sourceQueue.pendingSourceMutations(),
            target = null,
            pendingMessage = "A local TeX change has not been synced to the server.",
        ).filter { targets == null || it.target in targets }
        val operationStore = SyncOperationStore(context.applicationContext)
        // Card edits and ratings are saved as review files before explicit Sync
        // collects them into the database upload queue.
        val pendingReviewFilenames = ReviewStore(context.applicationContext).pendingChanges()
            .mapNotNull { it.pdfStem?.let { stem -> "$stem.pdf" } }.toSet()
        val pendingSync = (targets ?: pendingReviewFilenames).mapNotNull { filename ->
            if (filename !in pendingReviewFilenames && !hasPendingDocumentSync(filename, currentUriByTarget[filename])) {
                return@mapNotNull null
            }
            PhoneRenderJobStatus(
                requestId = "local-sync:$filename",
                target = filename,
                stage = PhoneRenderStage.SYNC_NEEDED,
                message = "This document has changes waiting to sync.",
                bannerEligible = false,
            )
        }
        val activeSync = targets.orEmpty().mapNotNull { filename ->
            operationStore.pendingTargetOperation(filename)?.let { operation ->
                PhoneRenderJobStatus(
                    requestId = "active-sync:${operation.id}",
                    target = requireNotNull(operation.target),
                    stage = PhoneRenderStage.WAITING_FOR_SYNC,
                    message = operation.status,
                    bannerEligible = false,
                )
            }
        }
        return history.apply(
            PhoneRenderStatusSnapshot(
                remote.jobs + pendingSource + activeSync + pendingSync + listOfNotNull(
                    currentArtifactSyncStatus(context.applicationContext),
                ),
            ),
        )
    }

    private suspend fun hasPendingDocumentSync(filename: String, currentUri: Uri?): Boolean {
        if (CommentDeliveryOutbox.forContext(context.applicationContext).hasPendingTarget(filename)) {
            return true
        }
        if (currentUri != null && CommentSidecarStore(context.applicationContext).hasPending(currentUri)) {
            return true
        }
        val stem = filename.removeSuffix(".pdf")
        return AppDatabase.getInstance(context.applicationContext)
            .thoughtsSyncDao()
            .undelivered()
            .any { operation ->
                when (operation.entityType) {
                    "annotation", "comment" -> operation.collection == stem
                    "review_card" -> runCatching {
                        JsonParser.parseString(operation.payloadJson)
                            .asJsonObject
                            .getAsJsonObject("primary")
                            ?.get("pdf_stem")
                            ?.asString == stem
                    }.getOrDefault(false)
                    // A proposal decision from the PDF view waits for Sync like a comment. Without
                    // this the document said "Up to date" while its Accept had not left the phone.
                    AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE -> proposalDecisionAwaitsSync(operation, filename)
                    else -> false
                }
            }
    }
}

fun currentArtifactSyncStatus(context: Context): PhoneRenderJobStatus? {
    val plan = ArtifactSyncStateStore(context.applicationContext).load() ?: return null
    return PhoneRenderJobStatus(
        requestId = "artifact-sync:${plan.planId}",
        target = PhoneRenderQueue.ACTION_ALL,
        stage = when (plan.status) {
            ArtifactSyncPlan.STATUS_TRANSFERRING -> PhoneRenderStage.WAITING_FOR_SYNC
            ArtifactSyncPlan.STATUS_FAILED -> PhoneRenderStage.BLOCKED
            else -> PhoneRenderStage.SYNC_PENDING
        },
        message = when (plan.status) {
            ArtifactSyncPlan.STATUS_TRANSFERRING -> "Syncing PDF and source updates."
            ArtifactSyncPlan.STATUS_FAILED -> plan.error
            else -> "A verified PDF update will be activated by Sync."
        },
        bannerEligible = false,
    )
}
