// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import com.gitlab.mudlej.MjPdfReader.data.render.ExplicitPdfRenderStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncBuildProgress
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncProjectFailure
import com.gitlab.mudlej.MjPdfReader.ui.home.BuiltTimeKind
import com.gitlab.mudlej.MjPdfReader.ui.home.BuiltTimePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.thoughts.sdk.ServerStorageUsage

class SyncBuildDialogPresentationTest {

    @Test
    fun syncIsAvailableForEveryIdleDocumentStatus() {
        val synced = availability(PhoneRenderStage.UPDATED)
        val syncNeeded = availability(PhoneRenderStage.SYNC_NEEDED)
        val buildNeeded = availability(PhoneRenderStage.OUT_OF_SYNC)
        val failed = availability(PhoneRenderStage.FAILED)
        val partial = availability(PhoneRenderStage.PARTIAL)

        assertTrue(synced.syncEnabled)
        assertTrue(syncNeeded.syncEnabled)
        assertTrue(buildNeeded.syncEnabled)
        assertTrue(failed.syncEnabled)
        assertTrue(partial.syncEnabled)
    }

    @Test
    fun activeOrUnconfiguredDocumentsDisableSync() {
        val localActive = syncBuildActionAvailability(
            status(PhoneRenderStage.OUT_OF_SYNC),
            localOperationActive = true,
            serverConfigured = true,
        )
        val remoteActive = availability(PhoneRenderStage.REBUILDING)
        val unconfigured = syncBuildActionAvailability(
            status(PhoneRenderStage.OUT_OF_SYNC),
            localOperationActive = false,
            serverConfigured = false,
        )

        listOf(localActive, remoteActive, unconfigured).forEach { actions ->
            assertFalse(actions.syncEnabled)
            assertTrue(actions.logsEnabled)
        }
    }

    @Test
    fun localQueuedBuildCanBeRecoveredWithSync() {
        val queued = availability(PhoneRenderStage.QUEUED)

        assertTrue(queued.syncEnabled)
        assertFalse(syncBuildRemoteRenderActive(status(PhoneRenderStage.QUEUED)))
        assertTrue(syncBuildRemoteRenderActive(status(PhoneRenderStage.FEDORA_QUEUED)))
        assertTrue(syncBuildRemoteRenderActive(status(PhoneRenderStage.REBUILDING)))
    }

    @Test
    fun buildTimeSurvivesALocalStatusThatHasNoBuildMetadata() {
        val localPending = status(PhoneRenderStage.SYNC_NEEDED)
        val cached = ExplicitPdfRenderStatus(
            filename = "paper.pdf",
            status = "out_of_sync",
            checkedAtEpochMillis = 1_000,
            lastSuccessfulBuildAtEpochMillis = 900,
        )

        assertEquals(900L, syncBuildLastSuccessfulBuildAt(localPending, cached))
        assertEquals(
            1_100L,
            syncBuildLastSuccessfulBuildAt(
                localPending.copy(lastSuccessfulBuildAtEpochMillis = 1_100),
                cached,
            ),
        )
    }

    @Test
    fun activeStatusUpdatesPreserveTheLatestSuccessfulTimestamps() {
        val previous = dialogState(
            status = status(PhoneRenderStage.OUT_OF_SYNC),
            lastSynced = 800,
            lastBuilt = 900,
        )
        val active = dialogState(
            status = status(PhoneRenderStage.REBUILDING),
            lastSynced = 0,
            lastBuilt = 0,
        )

        val merged = mergeSyncBuildDialogState(previous, active)

        assertEquals(800L, merged.lastSuccessfulSyncAtEpochMillis)
        assertEquals(900L, merged.lastSuccessfulBuildAtEpochMillis)
        assertEquals(PhoneRenderStage.REBUILDING, merged.status?.stage)
    }

    @Test
    fun transientRefreshFailurePreservesTheLatestServerStorageReading() {
        val storage = ServerStorageUsage(
            totalBytes = 1_000,
            usedBytes = 600,
            availableBytes = 400,
        )
        val previous = dialogState(
            status = status(PhoneRenderStage.UPDATED),
            lastSynced = 800,
            lastBuilt = 900,
        ).copy(serverStorage = storage)
        val refreshed = dialogState(
            status = status(PhoneRenderStage.UPDATED),
            lastSynced = 800,
            lastBuilt = 900,
        )

        assertEquals(storage, mergeSyncBuildDialogState(previous, refreshed).serverStorage)
        assertEquals(
            null,
            mergeSyncBuildDialogState(previous, refreshed.copy(serverConfigured = false)).serverStorage,
        )
    }

    @Test
    fun queuedActionRemainsVisibleUntilTheObservedOperationFinishes() {
        val pending = SyncBuildInteractionState()
            .begin(SyncBuildPendingAction.SYNC)
            .onQueueResult(queued = true)

        assertEquals(SyncBuildPendingAction.SYNC, pending.pendingAction)
        assertTrue(pending.pendingActionObserved)

        val active = pending.onStatusUpdated(
            localOperationActive = true,
            renderStatusActive = false,
        )
        assertEquals(SyncBuildPendingAction.SYNC, active.pendingAction)
        assertTrue(active.pendingActionObserved)

        assertEquals(
            SyncBuildInteractionState(),
            active.onStatusUpdated(
                localOperationActive = false,
                renderStatusActive = false,
            ),
        )
    }

    @Test
    fun syncRemainsPendingWhileFedoraFinishesItsConditionalBuild() {
        val pendingSync = SyncBuildInteractionState()
            .begin(SyncBuildPendingAction.SYNC)
            .onQueueResult(queued = true)

        assertEquals(
            pendingSync,
            pendingSync.onStatusUpdated(
                localOperationActive = false,
                renderStatusActive = true,
            ),
        )
    }

    @Test
    fun latestCompletedOperationProvidesAConsistentStatusFallback() {
        assertEquals(
            SyncOperationStore.STATUS_SYNCED,
            syncBuildLatestOperationLabel(
                status = SyncOperationStore.STATUS_SYNCED,
                kind = SyncOperationKind.SYNC,
                builtLabel = "Built",
            ),
        )
        assertEquals(
            SyncOperationStore.STATUS_SYNCED,
            syncBuildLatestOperationLabel(
                status = SyncOperationStore.STATUS_SYNCED,
                kind = SyncOperationKind.BUILD,
                builtLabel = "Built",
            ),
        )
        assertEquals(
            null,
            syncBuildLatestOperationLabel(
                status = null,
                kind = null,
                builtLabel = "Built",
            ),
        )
    }

    @Test
    fun queueFailureImmediatelyRestoresTheIdleDialogState() {
        val failed = SyncBuildInteractionState()
            .begin(SyncBuildPendingAction.SYNC)
            .onQueueResult(queued = false)

        assertEquals(SyncBuildInteractionState(), failed)
    }

    @Test
    fun relativeTimeUsesAMomentUntilTheFirstFullMinute() {
        val now = 100_000_000L

        listOf(0L, 1L, 59_999L).forEach { age ->
            assertEquals(
                BuiltTimePresentation(BuiltTimeKind.JUST_NOW),
                syncBuildTimePresentation(now - age, now),
            )
        }
        assertEquals(
            BuiltTimePresentation(BuiltTimeKind.MINUTES, 1),
            syncBuildTimePresentation(now - 60_000L, now),
        )
        assertEquals(
            BuiltTimePresentation(BuiltTimeKind.MINUTES, 1),
            syncBuildTimePresentation(now - 119_999L, now),
        )
        assertEquals(
            BuiltTimePresentation(BuiltTimeKind.MINUTES, 2),
            syncBuildTimePresentation(now - 120_000L, now),
        )
    }

    @Test
    fun relativeTimeRetainsHourAndExactDateBoundaries() {
        val now = 100_000_000L

        assertEquals(
            BuiltTimePresentation(BuiltTimeKind.MINUTES, 59),
            syncBuildTimePresentation(now - 3_599_999L, now),
        )
        assertEquals(
            BuiltTimePresentation(BuiltTimeKind.HOURS, 1),
            syncBuildTimePresentation(now - 3_600_000L, now),
        )
        assertEquals(
            BuiltTimePresentation(BuiltTimeKind.HOURS, 23),
            syncBuildTimePresentation(now - 86_399_999L, now),
        )
        assertEquals(
            BuiltTimePresentation(BuiltTimeKind.EXACT),
            syncBuildTimePresentation(now - 86_400_000L, now),
        )
    }

    @Test
    fun refreshDelayTargetsOnlyTheNextVisibleBoundary() {
        val now = 100_000_000L

        assertEquals(60_000L, nextSyncBuildTimeRefreshDelay(now, now))
        assertEquals(1L, nextSyncBuildTimeRefreshDelay(now - 59_999L, now))
        assertEquals(60_000L, nextSyncBuildTimeRefreshDelay(now - 60_000L, now))
        assertEquals(1L, nextSyncBuildTimeRefreshDelay(now - 119_999L, now))
        assertEquals(3_600_000L, nextSyncBuildTimeRefreshDelay(now - 3_600_000L, now))
        assertEquals(null, nextSyncBuildTimeRefreshDelay(now - 86_400_000L, now))
        assertEquals(null, nextSyncBuildTimeRefreshDelay(0L, now))
    }

    @Test
    fun projectScopedFailureIgnoresAnotherProjectsFailure() {
        val otherProject = record(
            target = null,
            buildProgress = listOf(
                progress(
                    documents = listOf("other.pdf"),
                    failedDocuments = listOf(SyncProjectFailure("other.pdf", "LaTeX error")),
                ),
            ),
        )
        assertEquals(null, projectScopedSyncFailureDetail(listOf(otherProject), "paper.pdf", "Built"))
    }

    @Test
    fun projectScopedFailureReportsThisDocumentsExplicitBuildFailure() {
        val record = record(
            target = null,
            buildProgress = listOf(
                progress(
                    documents = listOf("other.pdf", "paper.pdf"),
                    failedDocuments = listOf(SyncProjectFailure("paper.pdf", "LaTeX error")),
                ),
            ),
        )
        assertEquals(
            "PDF build failed" to "A formatting error prevented the PDF from building.",
            projectScopedSyncFailureDetail(listOf(record), "paper.pdf", "Built"),
        )
    }

    @Test
    fun projectScopedFailureReportsATargetedTerminalFailure() {
        val record = record(target = "paper.pdf")
        assertEquals(
            "PDF build failed" to SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED,
            projectScopedSyncFailureDetail(listOf(record), "paper.pdf", "Built"),
        )
    }

    @Test
    fun projectScopedFailureOnlyClaimsAGlobalBuildForItsOwnDocument() {
        val onlyThisDocument = record(
            target = null,
            buildProgress = listOf(progress(documents = listOf("paper.pdf"))),
        )
        assertEquals(
            "PDF build failed" to SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED,
            projectScopedSyncFailureDetail(listOf(onlyThisDocument), "paper.pdf", "Built"),
        )

        val multiDocument = record(
            target = null,
            buildProgress = listOf(progress(documents = listOf("other.pdf", "paper.pdf"))),
        )
        assertEquals(null, projectScopedSyncFailureDetail(listOf(multiDocument), "paper.pdf", "Built"))
    }

    private fun record(
        target: String?,
        buildProgress: List<SyncBuildProgress> = emptyList(),
    ): SyncOperationRecord = SyncOperationRecord(
        id = "op",
        target = target,
        kind = SyncOperationKind.BUILD,
        status = SyncOperationStore.STATUS_ERROR,
        startedAtEpochMillis = 1_000,
        updatedAtEpochMillis = 2_000,
        events = listOf(SyncOperationStore.STATUS_ERROR),
        displayMessage = SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED,
        buildProgress = buildProgress,
    )

    private fun progress(
        documents: List<String>,
        failedDocuments: List<SyncProjectFailure> = emptyList(),
    ): SyncBuildProgress = SyncBuildProgress(
        buildId = "build-1",
        target = documents.firstOrNull().orEmpty(),
        serverStatus = "failed",
        phase = "failed",
        completed = 0,
        total = documents.size,
        unit = "pdf",
        detail = "",
        documents = documents,
        failedDocuments = failedDocuments,
    )

    private fun availability(stage: PhoneRenderStage): SyncBuildActionAvailability =
        syncBuildActionAvailability(
            status(stage),
            localOperationActive = false,
            serverConfigured = true,
        )

    private fun status(stage: PhoneRenderStage): PhoneRenderJobStatus = PhoneRenderJobStatus(
        requestId = "request:$stage",
        target = "paper.pdf",
        stage = stage,
    )

    private fun dialogState(
        status: PhoneRenderJobStatus,
        lastSynced: Long,
        lastBuilt: Long,
    ): SyncBuildDialogState = SyncBuildDialogState(
        target = "paper.pdf",
        status = status,
        lastSuccessfulSyncAtEpochMillis = lastSynced,
        lastSuccessfulBuildAtEpochMillis = lastBuilt,
        serverConfigured = true,
        localOperationActive = status.isActive,
    )
}
