// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderFailure
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderReceipt
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferPlan
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRenderStatusBannerTest {

    @Test
    fun mobileDataActionsApplyOnlyToTheMatchingUnapprovedOperation() {
        val operation = SyncOperationRecord(
            id = "11111111-1111-4111-8111-111111111111",
            target = null,
            kind = SyncOperationKind.SYNC,
            status = SyncOperationStore.STATUS_WAITING,
            startedAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            events = listOf("Waiting for mobile-data approval or Wi-Fi"),
        )
        val plan = SyncTransferPlan(
            contractVersion = 2,
            sourceProfile = "reader-v1",
            includeVoiceMedia = true,
            planId = "a".repeat(64),
            serverPlanId = "b".repeat(64),
            serverLibraryRevision = "c".repeat(64),
            remoteObjects = listOf(
                SyncTransferObject(
                    sha256 = "d".repeat(64),
                    sizeBytes = 82,
                    kind = "source",
                    roles = listOf("reverse_lookup"),
                    publications = listOf("manual.pdf"),
                ),
            ),
            operationId = operation.id,
            targetPdfFilename = null,
            building = false,
            uploadBytes = 6_000_000,
            downloadBytes = 0,
            thresholdBytes = 5_000_000,
        )

        assertEquals(plan, pendingMobileDataPlan(operation, plan))
        assertEquals(
            null,
            pendingMobileDataPlan(
                operation.copy(status = SyncOperationStore.STATUS_SYNCING),
                plan,
            ),
        )
        assertEquals(null, pendingMobileDataPlan(operation, plan.copy(meteredApproved = true)))
        assertEquals(
            null,
            pendingMobileDataPlan(
                operation,
                plan.copy(operationId = "22222222-2222-4222-8222-222222222222"),
            ),
        )
    }

    @Test
    fun codexFixCoversCompletedRendererFailuresWithOrWithoutDocumentDetails() {
        val failure = PhoneRenderFailure("Benchmark.pdf", "source.tex", "LaTeX failed")
        assertTrue(
            canFixWithCodex(
                PhoneRenderJobStatus(
                    requestId = "partial",
                    target = PhoneRenderQueue.ACTION_ALL,
                    stage = PhoneRenderStage.PARTIAL,
                    failedDocuments = listOf(failure),
                )
            )
        )
        assertTrue(
            canFixWithCodex(
                PhoneRenderJobStatus(
                    requestId = "renderer",
                    target = "process.pdf",
                    stage = PhoneRenderStage.FAILED,
                    message = "renderer exited with 1",
                )
            )
        )
        assertFalse(
            canFixWithCodex(
                PhoneRenderJobStatus(
                    requestId = "running",
                    target = PhoneRenderQueue.ACTION_ALL,
                    stage = PhoneRenderStage.REBUILDING,
                    failedDocuments = listOf(failure),
                )
            )
        )
    }

    @Test
    fun noChangeCompletionUsesTransientFeedbackWithoutRefreshingLibrary() {
        val receipts = listOf(receipt(changesDetected = false))

        assertTrue(completedReceiptsContainNoChanges(receipts))
        assertFalse(completedReceiptsRequireLibraryRefresh(receipts))
    }

    @Test
    fun changedAndLegacySuccessesRefreshWithoutNoChangeFeedback() {
        val receipts = listOf(
            receipt(changesDetected = true),
            receipt(changesDetected = null),
        )

        assertFalse(completedReceiptsContainNoChanges(receipts))
        assertTrue(completedReceiptsRequireLibraryRefresh(receipts))
    }

    @Test
    fun failuresDoNotShowSuccessFeedbackOrRefreshLibrary() {
        val receipts = listOf(
            receipt(
                changesDetected = null,
                status = PhoneRenderReceipt.STATUS_FAILURE,
            )
        )

        assertFalse(completedReceiptsContainNoChanges(receipts))
        assertFalse(completedReceiptsRequireLibraryRefresh(receipts))
    }

    @Test
    fun partialSuccessRefreshesTheLibrary() {
        val receipts = listOf(
            receipt(
                changesDetected = true,
                status = PhoneRenderReceipt.STATUS_PARTIAL_SUCCESS,
            )
        )
        assertFalse(completedReceiptsContainNoChanges(receipts))
        assertTrue(completedReceiptsRequireLibraryRefresh(receipts))
    }

    private fun status(
        requestId: String,
        stage: PhoneRenderStage,
        activityAt: Long = 0,
        target: String = "$requestId.pdf",
    ): PhoneRenderJobStatus {
        return PhoneRenderJobStatus(
            requestId = requestId,
            target = target,
            stage = stage,
            completedAtEpochMillis = activityAt,
        )
    }

    private fun receipt(
        changesDetected: Boolean?,
        status: String = PhoneRenderReceipt.STATUS_SUCCESS,
    ): PhoneRenderReceipt {
        return PhoneRenderReceipt(
            schemaVersion = 5,
            requestId = "request",
            target = PhoneRenderQueue.ACTION_ALL,
            status = status,
            message = "",
            exitCode = when (status) {
                PhoneRenderReceipt.STATUS_SUCCESS -> 0
                PhoneRenderReceipt.STATUS_PARTIAL_SUCCESS -> 2
                else -> 1
            },
            publishedPdf = null,
            anchorResults = emptyList(),
            changesDetected = changesDetected,
        )
    }
}
