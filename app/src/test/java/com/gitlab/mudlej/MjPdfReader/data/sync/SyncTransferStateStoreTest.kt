// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class SyncTransferStateStoreTest {
    private lateinit var root: File
    private lateinit var store: SyncTransferStateStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("sync-transfer-state").toFile()
        store = SyncTransferStateStore(File(root, "state.json"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun replacementPlanRetainsCommittedTotalsWithoutReusingApprovalOrInflightUpload() {
        val first = store.publish(plan(upload = 100, download = 100))
        store.approveMetered(first.planId)
        store.recordCommittedUploadProgress(first.planId, 7)
        store.recordUploadProgress(first.planId, 30)
        store.recordMetadataProgress(first.planId, 10)
        val replacement = first.copy(planId = "d".repeat(64), uploadBytes = 0)
        val next = store.publish(replacement)
        assertEquals(7, next.operationUploadBytesCompleted)
        assertEquals(7, next.operationUploadBytesTotal)
        assertEquals(10, next.operationDownloadBytesCompleted)
        assertEquals(110, next.operationDownloadBytesTotal)
        assertFalse(next.meteredApproved)
        assertFalse(next.requiresApproval)
        assertEquals(next, store.load())
        assertEquals(next, store.publish(replacement))
        store.markTransferComplete(next.planId)
        val completed = requireNotNull(store.load())
        assertEquals(7, completed.operationUploadBytesCompleted)
        assertEquals(110, completed.operationDownloadBytesCompleted)
        val otherRun = store.publish(replacement.copy(operationId = "new-operation"))
        assertEquals(0, otherRun.operationUploadBytesCompleted)
        assertEquals(0, otherRun.operationDownloadBytesCompleted)
    }

    @Test
    fun thresholdIsAppliedSeparatelyAndStrictly() {
        assertFalse(plan(upload = 5_000_000, download = 5_000_000).requiresApproval)
        assertTrue(plan(upload = 5_000_001, download = 0).requiresApproval)
        assertTrue(plan(upload = 0, download = 5_000_001).requiresApproval)
    }

    @Test
    fun approvalSurvivesOnlyTheExactPlanAndClearIsOperationScoped() {
        val first = plan(upload = 6_000_000, download = 0)
        store.publish(first)
        store.approveMetered(first.planId)

        assertTrue(store.publish(first).meteredApproved)

        val next = first.copy(
            planId = "d".repeat(64),
            serverPlanId = "e".repeat(64),
            operationId = "22222222-2222-4222-8222-222222222222",
        )
        assertFalse(store.publish(next).meteredApproved)
        store.clearForOperation(first.operationId)
        assertTrue(store.load() != null)
        store.clearForOperation(next.operationId)
        assertNull(store.load())
    }

    @Test
    fun legacyApprovalWithoutExactObjectRecordsIsDiscarded() {
        File(root, "state.json").writeText(
            """{"schema_version":1,"plan_id":"${"a".repeat(64)}","metered_approved":true}""",
        )

        assertNull(store.load())
    }

    @Test
    fun schemaTwoPlanIsUpgradedWithoutLosingExactApproval() {
        val approved = store.publish(plan(upload = 1, download = 100))
        store.approveMetered(approved.planId)
        val stateFile = File(root, "state.json")
        stateFile.writeText(
            stateFile.readText()
                .replace("\"schema_version\":3", "\"schema_version\":2")
                .replace(Regex(",\"metadata_download_bytes\":\\d+"), "")
                .replace(Regex(",\"upload_bytes_completed\":\\d+"), "")
                .replace(Regex(",\"committed_upload_bytes\":\\d+"), "")
                .replace(Regex(",\"metadata_bytes_completed\":\\d+"), "")
                .replace(Regex(",\"remote_object_bytes_completed\":\\{[^}]*}"), ""),
        )

        val restored = store.load()

        assertTrue(requireNotNull(restored).meteredApproved)
        assertEquals(18, restored.metadataDownloadBytes)
        assertEquals(0, restored.downloadBytesCompleted)
        assertFalse(restored.controlTransferCommitted)
    }

    @Test
    fun directionalProgressIsDurableAndRemoteObjectUpdatesAreAbsolute() {
        val published = store.publish(plan(upload = 20, download = 100))
        store.recordMetadataProgress(published.planId, 10)
        assertEquals(10, requireNotNull(store.load()).downloadBytesCompleted)
        store.recordUploadProgress(published.planId, 20)
        assertEquals(20, requireNotNull(store.load()).uploadBytesCompleted)
        store.rollbackUncommittedControlTransfer(published.planId)
        assertEquals(0, requireNotNull(store.load()).uploadBytesCompleted)
        store.recordCommittedUploadProgress(published.planId, 7)
        store.recordUploadProgress(published.planId, 20)
        store.rollbackUncommittedControlTransfer(published.planId)
        assertEquals(7, requireNotNull(store.load()).uploadBytesCompleted)

        val controlComplete = requireNotNull(store.markControlTransferComplete(published.planId))
        assertEquals(7, controlComplete.uploadBytesCompleted)
        assertEquals(7, controlComplete.uploadBytes)
        assertEquals(18, controlComplete.downloadBytesCompleted)
        assertTrue(controlComplete.controlTransferCommitted)
        store.rollbackUncommittedControlTransfer(published.planId)
        assertEquals(7, requireNotNull(store.load()).uploadBytesCompleted)

        store.recordRemoteObjectProgress(published.planId, "f".repeat(64), 41)
        assertEquals(59, requireNotNull(store.load()).downloadBytesCompleted)
        store.recordRemoteObjectProgress(published.planId, "f".repeat(64), 12)
        assertEquals(30, requireNotNull(store.load()).downloadBytesCompleted)

        store.markTransferComplete(published.planId)
        val complete = requireNotNull(store.load())
        assertEquals(7, complete.uploadBytesCompleted)
        assertEquals(100, complete.downloadBytesCompleted)
    }

    @Test
    fun batchedRemoteProgressMatchesSingleUpdatesAndIgnoresUnplannedObjects() {
        val published = store.publish(plan(upload = 20, download = 100))
        store.recordRemoteObjectsProgress(published.planId, mapOf("f".repeat(64) to 41, "0".repeat(64) to 5))
        val batched = requireNotNull(store.load())
        assertEquals(41, batched.downloadBytesCompleted)
        assertEquals(setOf("f".repeat(64)), batched.remoteObjectBytesCompleted.keys)
    }

    private fun waitingRecord(plan: SyncTransferPlan) = SyncOperationRecord(
        id = plan.operationId, target = plan.targetPdfFilename, kind = SyncOperationKind.SYNC,
        status = SyncOperationStore.STATUS_WAITING, startedAtEpochMillis = 0,
        updatedAtEpochMillis = 3_000, events = listOf("Waiting for mobile-data approval or Wi-Fi"),
    )

    @Test
    fun unansweredPromptSurvivesRestartWithoutGrantingMobileApproval() {
        val pending = store.publish(plan(upload = 0, download = 24_438_219))
        store.markPromptShown(pending.planId)
        val restored = requireNotNull(SyncTransferStateStore(File(root, "state.json")).load())
        assertFalse(restored.meteredApproved)
        assertFalse(restored.waitForUnmetered)
        assertTrue(shouldResumeWaitingSyncTransfer(restored, waitingRecord(restored)))
        assertTrue(shouldResumeWaitingSyncTransfer(
            restored.copy(waitForUnmetered = true), waitingRecord(restored),
        ))
    }

    @Test
    fun wifiRecoveryCannotRestartTerminalOrUnrelatedOperations() {
        val pending = plan(upload = 0, download = 24_438_219)
        val record = waitingRecord(pending)
        for (status in listOf(SyncOperationStore.STATUS_SYNCED, SyncOperationStore.STATUS_ERROR,
            SyncOperationStore.STATUS_ATTENTION, SyncOperationStore.STATUS_QUEUED,
            SyncOperationStore.STATUS_SYNCING)) {
            assertFalse(shouldResumeWaitingSyncTransfer(pending, record.copy(status = status)))
        }
        assertFalse(shouldResumeWaitingSyncTransfer(pending, record.copy(id = "different-operation")))
        assertFalse(shouldResumeWaitingSyncTransfer(pending.copy(controlTransferCommitted = true), record))
        assertFalse(shouldResumeWaitingSyncTransfer(pending, record, pending.operationId))
        assertTrue(shouldResumeWaitingSyncTransfer(pending, record, "older-artifact-operation"))
        assertFalse(shouldResumeWaitingSyncTransfer(null, record))
        assertFalse(shouldResumeWaitingSyncTransfer(pending, null))
    }

    @Test
    fun wifiWaitUsesASeparateStableQueueFromMobileApprovedSync() {
        val first = transferWifiWorkName("first")
        assertEquals(first, transferWifiWorkName("first"))
        assertFalse(first == transferWifiWorkName("second"))
        assertFalse(first == ThoughtsSyncScheduler.WORK_NAME)
    }

    @Test
    fun targetedPlanCoversVerifiedObjectsAlreadyFilteredAsKnown() {
        val transfer = plan(upload = 0, download = 0).copy(
            targetPdfFilename = "manual.pdf",
            remoteObjects = emptyList(),
        )
        val artifact = ArtifactSyncPlan(
            planId = "9".repeat(64),
            libraryRevision = "8".repeat(64),
            sourceBundleSha256 = "7".repeat(64),
            changedPdfCount = 0,
            estimatedTransferBytes = 0,
            sourceIndexChanged = false,
            sourceBundleChanged = false,

            targetPdfFilenames = listOf("manual.pdf"),
            targetPublicationSha256 = "6".repeat(64),
            targetPublicationSizeBytes = 123,
            operationId = transfer.operationId,
        )

        assertTrue(transfer.covers(artifact))
        assertFalse(transfer.covers(artifact.copy(targetPdfFilenames = listOf("other.pdf"))))
    }

    @Test
    fun exactPlanGuardRejectsEveryUnplannedDownloadShape() {
        val transfer = plan(upload = 0, download = 82)
        assertEquals(
            "f".repeat(64),
            transfer.requireRemoteObject(
                "f".repeat(64),
                kind = "source",
                role = "reverse_lookup",
                expectedSize = 82,
                publication = "manual.pdf",
            ).sha256,
        )

        listOf(
            { transfer.requireRemoteObject("e".repeat(64), "source", "reverse_lookup") },
            { transfer.requireRemoteObject("f".repeat(64), "source", "raw_synctex_fallback") },
            { transfer.requireRemoteObject("f".repeat(64), "source", "reverse_lookup", 83) },
            {
                transfer.requireRemoteObject(
                    "f".repeat(64),
                    "source",
                    "reverse_lookup",
                    publication = "other.pdf",
                )
            },
        ).forEach { escapedGet ->
            assertThrows(ArtifactPlanSupersededException::class.java) { escapedGet() }
        }
    }

    @Test
    fun sharedStyleResourceRoleSurvivesAProcessRestart() {
        val resourcePlan = plan(upload = 0, download = 82).copy(
            remoteObjects = listOf(
                SyncTransferObject(
                    sha256 = "f".repeat(64),
                    sizeBytes = 82,
                    kind = "source",
                    roles = listOf("source_resource"),
                    publications = listOf("manual.pdf"),
                ),
            ),
        )

        store.publish(resourcePlan)

        assertEquals(
            listOf("source_resource"),
            requireNotNull(store.load()).remoteObjects.single().roles,
        )
    }

    @Test
    fun markdownRendererEvidenceRoleSurvivesAProcessRestart() {
        val resourcePlan = plan(upload = 0, download = 82).copy(
            remoteObjects = listOf(
                SyncTransferObject(
                    sha256 = "f".repeat(64),
                    sizeBytes = 82,
                    kind = "source",
                    roles = listOf("renderer_evidence"),
                    publications = listOf("manual.pdf"),
                ),
            ),
        )

        store.publish(resourcePlan)

        assertEquals(
            listOf("renderer_evidence"),
            requireNotNull(store.load()).remoteObjects.single().roles,
        )
    }

    @Test
    fun pdfDeltaRoleSurvivesAProcessRestart() {
        val deltaPlan = plan(upload = 0, download = 82).copy(
            remoteObjects = listOf(
                SyncTransferObject(
                    sha256 = "f".repeat(64),
                    sizeBytes = 82,
                    kind = "pdf",
                    roles = listOf("pdf_delta"),
                    publications = listOf("manual.pdf"),
                ),
            ),
        )

        store.publish(deltaPlan)

        assertEquals(
            listOf("pdf_delta"),
            requireNotNull(store.load()).remoteObjects.single().roles,
        )
    }

    private fun plan(upload: Long, download: Long) = SyncTransferPlan(
        contractVersion = 2,
        sourceProfile = "reader-v1",
        includeVoiceMedia = true,
        planId = "a".repeat(64),
        serverPlanId = "b".repeat(64),
        serverLibraryRevision = "c".repeat(64),
        remoteObjects = listOf(
            SyncTransferObject(
                sha256 = "f".repeat(64),
                sizeBytes = 82,
                kind = "source",
                roles = listOf("reverse_lookup"),
                publications = listOf("manual.pdf"),
            ),
        ),
        operationId = "11111111-1111-4111-8111-111111111111",
        targetPdfFilename = null,
        building = false,
        metadataDownloadBytes = (download - 82).coerceAtLeast(0),
        uploadBytes = upload,
        downloadBytes = download,
        thresholdBytes = 5_000_000,
    )
}
