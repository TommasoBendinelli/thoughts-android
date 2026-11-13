// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.app.job.JobScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOperationStorePolicyTest {

    @Test
    fun buildAndSyncRequestsStartQueuedUntilNetworkWorkActuallyBegins() {
        assertEquals(SyncOperationStore.STATUS_QUEUED, initialSyncOperationStatus())
    }

    @Test
    fun newestCompletedGlobalOperationSupersedesStaleActiveHistory() {
        val completed = record("new", null, SyncOperationStore.STATUS_SYNCED, 20)
        val stale = record("old", null, SyncOperationStore.STATUS_SYNCING, 10)

        assertNull(activeGlobalSyncOperation(listOf(completed, stale)))
    }

    @Test
    fun completedAttentionAndFailedOperationsAreTerminal() {
        assertTrue(isTerminalSyncOperationStatus(SyncOperationStore.STATUS_SYNCED))
        assertTrue(isTerminalSyncOperationStatus(SyncOperationStore.STATUS_ATTENTION))
        assertTrue(isTerminalSyncOperationStatus(SyncOperationStore.STATUS_ERROR))
        assertFalse(isTerminalSyncOperationStatus(SyncOperationStore.STATUS_RETRYING))
    }

    @Test
    fun newestActiveGlobalOperationDrivesTheLiveTimer() {
        val targeted = record("target", "paper.pdf", SyncOperationStore.STATUS_SYNCED, 30)
        val active = record("global", null, SyncOperationStore.STATUS_SYNCING, 20)

        assertEquals(active, activeGlobalSyncOperation(listOf(targeted, active)))
    }

    @Test
    fun queuedOrRetryingGlobalOperationDoesNotKeepTheElapsedTimerRunning() {
        val stale = record("stale", null, SyncOperationStore.STATUS_SYNCING, 10)
        val queued = record("queued", null, SyncOperationStore.STATUS_QUEUED, 20)
        val retrying = record("retrying", null, SyncOperationStore.STATUS_RETRYING, 30)

        assertNull(activeGlobalSyncOperation(listOf(queued, stale)))
        assertNull(activeGlobalSyncOperation(listOf(retrying, queued, stale)))
        assertEquals(retrying, pendingGlobalSyncOperation(listOf(retrying, queued, stale)))
    }

    @Test
    fun pendingSelectionBlocksDuplicateTargetRequestsWithoutStartingTheTimer() {
        val queued = record("queued", "paper.pdf", SyncOperationStore.STATUS_QUEUED, 20)

        assertEquals(queued, pendingTargetSyncOperation(listOf(queued), "paper.pdf"))
        assertNull(activeTargetSyncOperation(listOf(queued), "paper.pdf"))
    }

    @Test
    fun orphanedPendingOperationIsInterruptedOnlyAfterGracePeriod() {
        val queued = record("queued", null, SyncOperationStore.STATUS_QUEUED, 1_000)

        assertFalse(
            shouldMarkSyncOperationInterrupted(
                queued,
                nowEpochMillis = 60_999,
                hasExecutionCounterpart = false,
            ),
        )
        assertTrue(
            shouldMarkSyncOperationInterrupted(
                queued,
                nowEpochMillis = 61_000,
                hasExecutionCounterpart = false,
            ),
        )
        assertFalse(
            shouldMarkSyncOperationInterrupted(
                queued,
                nowEpochMillis = 100_000,
                hasExecutionCounterpart = true,
            ),
        )
    }

    @Test
    fun reconciliationRunsAtGraceAndStopsAfterWorkFinishes() {
        val queued = record("queued", null, SyncOperationStore.STATUS_QUEUED, 1_000)
        val completed = record("completed", null, SyncOperationStore.STATUS_SYNCED, 2_000)

        assertEquals(
            40_000L,
            nextSyncOperationReconciliationDelayMillis(listOf(queued), nowEpochMillis = 21_000),
        )
        assertEquals(
            0L,
            nextSyncOperationReconciliationDelayMillis(listOf(queued), nowEpochMillis = 61_000),
        )
        assertNull(
            nextSyncOperationReconciliationDelayMillis(listOf(completed), nowEpochMillis = 61_000),
        )
    }

    @Test
    fun userInitiatedJobsUseStableDistinctIdsPerOperation() {
        val first = "3392cde0-d453-4d95-bb74-c6fcc7f531d6"
        val second = "1e10c8d6-d6da-472c-b3fc-cceb2b39ff6d"

        assertEquals(userInitiatedSyncJobId(first), userInitiatedSyncJobId(first))
        assertNotEquals(userInitiatedSyncJobId(first), userInitiatedSyncJobId(second))
        assertTrue(userInitiatedSyncJobId(first) > 0)
    }

    @Test
    fun refusedUserInitiatedJobFallsBackToWorkManager() {
        assertFalse(shouldFallbackFromUserInitiatedJob(JobScheduler.RESULT_SUCCESS))
        assertTrue(shouldFallbackFromUserInitiatedJob(JobScheduler.RESULT_FAILURE))
    }

    @Test
    fun backgroundCommandUsesExpeditedWorkWithoutAForegroundService() {
        val policy = explicitSyncExecutionPolicy(
            ExplicitSyncOrigin.BACKGROUND_COMMAND,
            sdkInt = 36,
        )

        assertFalse(policy.scheduleUserInitiatedJob)
        assertTrue(policy.expediteWorkManager)
        assertFalse(policy.runWorkerInForeground)
    }

    @Test
    fun backgroundWorkerBypassesForegroundServiceAndReachesRepository() = runBlocking {
        var foregroundAttempted = false
        var repositoryReached = false

        val result = runSyncWorkerWithForegroundPolicy(
            runInForeground = false,
            promoteToForeground = {
                foregroundAttempted = true
                error("Background command must not start a foreground service")
            },
            runSync = {
                repositoryReached = true
                "applied"
            },
        )

        assertEquals("applied", result)
        assertFalse(foregroundAttempted)
        assertTrue(repositoryReached)
    }

    @Test
    fun visibleWorkerPromotesBeforeReachingRepository() = runBlocking {
        val events = mutableListOf<String>()

        runSyncWorkerWithForegroundPolicy(
            runInForeground = true,
            promoteToForeground = { events += "foreground" },
            runSync = { events += "repository" },
        )

        assertEquals(listOf("foreground", "repository"), events)
    }

    @Test
    fun backgroundContinuationNeverClaimsAUserInitiatedExemption() {
        val policy = explicitSyncExecutionPolicy(
            ExplicitSyncOrigin.BACKGROUND_CONTINUATION,
            sdkInt = 34,
        )

        assertFalse(policy.scheduleUserInitiatedJob)
        assertFalse(policy.expediteWorkManager)
        assertFalse(policy.runWorkerInForeground)
    }

    @Test
    fun visibleUserActionKeepsNotificationAndFastSchedulingPolicy() {
        val modern = explicitSyncExecutionPolicy(
            ExplicitSyncOrigin.USER_ACTION,
            sdkInt = 36,
        )
        val legacy = explicitSyncExecutionPolicy(
            ExplicitSyncOrigin.USER_ACTION,
            sdkInt = 33,
        )

        assertTrue(modern.scheduleUserInitiatedJob)
        assertTrue(modern.expediteWorkManager)
        assertTrue(modern.runWorkerInForeground)
        assertFalse(legacy.scheduleUserInitiatedJob)
        assertTrue(legacy.expediteWorkManager)
        assertTrue(legacy.runWorkerInForeground)
    }

    @Test
    fun targetActivityUsesOnlyTheNewestMatchingDocument() {
        val other = record("other", "other.pdf", SyncOperationStore.STATUS_SYNCED, 40)
        val older = record("old", "paper.pdf", SyncOperationStore.STATUS_ERROR, 10)
        val newest = record("new", "paper.pdf", SyncOperationStore.STATUS_SYNCING, 30)

        assertEquals(newest, latestTargetSyncOperation(listOf(other, older, newest), "paper.pdf"))
        assertEquals(newest, activeTargetSyncOperation(listOf(other, older, newest), "paper.pdf"))
        assertNull(latestTargetSyncOperation(listOf(other), "paper.pdf"))
    }

    @Test
    fun lastSuccessfulDocumentSyncIncludesGlobalSyncAndIgnoresLaterFailures() {
        val targetSuccess = record("target-success", "paper.pdf", SyncOperationStore.STATUS_SYNCED, 10)
            .copy(updatedAtEpochMillis = 20)
        val laterFailure = record("target-failure", "paper.pdf", SyncOperationStore.STATUS_ERROR, 30)
            .copy(updatedAtEpochMillis = 40)
        val otherSuccess = record("other-success", "other.pdf", SyncOperationStore.STATUS_SYNCED, 50)
            .copy(updatedAtEpochMillis = 60)
        val globalSuccess = record("global-success", null, SyncOperationStore.STATUS_SYNCED, 70)
            .copy(updatedAtEpochMillis = 80)

        assertEquals(
            80L,
            lastSuccessfulDocumentSyncAt(
                listOf(targetSuccess, laterFailure, otherSuccess, globalSuccess),
                "paper.pdf",
            ),
        )
        assertEquals(
            0L,
            lastSuccessfulDocumentSyncAt(listOf(otherSuccess, laterFailure), "missing.pdf"),
        )
    }

    @Test
    fun legacyOperationKindIsInferredWhileStoredKindTakesPriority() {
        assertEquals(
            SyncOperationKind.BUILD,
            storedSyncOperationKind(
                storedKind = "",
                status = SyncOperationStore.STATUS_SYNCED,
                events = listOf("${SyncOperationStore.STATUS_BUILDING} 2026-08-21T12:00:00Z"),
            ),
        )
        assertEquals(
            SyncOperationKind.SYNC,
            storedSyncOperationKind(
                storedKind = "",
                status = SyncOperationStore.STATUS_SYNCED,
                events = listOf("${SyncOperationStore.STATUS_SYNCING} 2026-08-21T12:00:00Z"),
            ),
        )
        assertEquals(
            SyncOperationKind.BUILD,
            storedSyncOperationKind(
                storedKind = "build",
                status = SyncOperationStore.STATUS_SYNCED,
                events = emptyList(),
            ),
        )
    }

    @Test
    fun everyNewEventKeepsAnAlignedTimestamp() {
        val initial = SyncOperationRecord(
            id = "operation",
            target = null,
            kind = SyncOperationKind.SYNC,
            status = SyncOperationStore.STATUS_QUEUED,
            startedAtEpochMillis = 10,
            updatedAtEpochMillis = 10,
            events = listOf("Queued"),
            eventTimestampsEpochMillis = listOf(10),
        )

        val updated = appendTimestampedSyncOperationEvent(
            initial,
            event = "Completed",
            timestampEpochMillis = 20,
            maxEvents = 100,
        )

        assertEquals(listOf("Queued", "Completed"), updated.events)
        assertEquals(listOf(10L, 20L), updated.eventTimestampsEpochMillis)
    }

    @Test
    fun repeatedBuildChecksUpdateOneBuildingEntryInsteadOfAppendingPolls() {
        val initial = SyncOperationRecord(
            id = "operation",
            target = "paper.pdf",
            kind = SyncOperationKind.BUILD,
            status = SyncOperationStore.STATUS_QUEUED,
            startedAtEpochMillis = 10,
            updatedAtEpochMillis = 10,
            events = listOf(SyncOperationStore.STATUS_QUEUED),
            eventTimestampsEpochMillis = listOf(10),
        )
        val first = recordSyncBuildProgress(
            initial,
            listOf(buildProgress(detail = "Compiling paper.tex", completed = 1)),
            checkedAtEpochMillis = 20,
            buildsActive = true,
        )
        val second = recordSyncBuildProgress(
            first,
            listOf(buildProgress(detail = "Validating paper.pdf", completed = 2)),
            checkedAtEpochMillis = 30,
            buildsActive = true,
        )

        assertEquals(listOf("Queued", "Building"), second.events)
        assertEquals(listOf(10L, 30L), second.eventTimestampsEpochMillis)
        assertEquals("Validating paper.pdf", second.buildProgress.single().detail)
        assertEquals(SyncOperationStore.STATUS_BUILDING, second.status)
    }

    @Test
    fun terminalSourceAttentionCannotBeDowngradedByLateArtifactCompletion() {
        assertTrue(
            preservesTerminalSourceAttention(
                SyncOperationStore.STATUS_ATTENTION,
                SyncOperationStore.STATUS_SYNCED,
            ),
        )
        assertTrue(
            preservesTerminalSourceAttention(
                SyncOperationStore.STATUS_ATTENTION,
                SyncOperationStore.STATUS_BUILDING,
            ),
        )
        assertFalse(
            preservesTerminalSourceAttention(
                SyncOperationStore.STATUS_SYNCING,
                SyncOperationStore.STATUS_SYNCED,
            ),
        )

        val attention = record(
            "attention",
            "paper.pdf",
            SyncOperationStore.STATUS_ATTENTION,
            10,
        ).copy(phase = SyncRunPhase.FAILED)
        val polled = recordSyncBuildProgress(
            attention,
            listOf(buildProgress(detail = "Late build poll")),
            checkedAtEpochMillis = 20,
            buildsActive = true,
        )

        assertEquals(SyncOperationStore.STATUS_ATTENTION, polled.status)
        assertEquals(SyncRunPhase.FAILED, polled.phase)
        assertEquals("Late build poll", polled.buildProgress.single().detail)
    }

    @Test
    fun terminalCompilerFailureCannotBeDowngradedByALateBuildPoll() {
        val failure = record(
            "compiler-failure",
            "paper.pdf",
            SyncOperationStore.STATUS_ERROR,
            10,
        ).copy(
            phase = SyncRunPhase.FAILED,
            displayMessage = SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_TEX_SAVED,
        )

        assertTrue(isTerminalPdfBuildFailure(failure))
        val polled = recordSyncBuildProgress(
            failure,
            listOf(buildProgress(detail = "Late network callback")),
            checkedAtEpochMillis = 20,
            buildsActive = true,
        )

        assertEquals(SyncOperationStore.STATUS_ERROR, polled.status)
        assertEquals(SyncRunPhase.FAILED, polled.phase)
        assertEquals(
            SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_TEX_SAVED,
            polled.displayMessage,
        )
        assertEquals("Late network callback", polled.buildProgress.single().detail)
    }

    @Test
    fun completedBuildHasOnlyQueuedBuildingAndCompletedLifecycleEntries() {
        val noisy = SyncOperationRecord(
            id = "operation",
            target = "paper.pdf",
            kind = SyncOperationKind.BUILD,
            status = SyncOperationStore.STATUS_BUILDING,
            startedAtEpochMillis = 10,
            updatedAtEpochMillis = 30,
            events = listOf(
                "Queued",
                "Syncing pending changes and render requests",
                "Fedora is building the requested PDFs",
                "Waiting for the Thoughts server to finish building",
            ),
            eventTimestampsEpochMillis = listOf(10, 15, 20, 30),
            buildProgress = listOf(buildProgress()),
            buildCheckedAtEpochMillis = 30,
        )

        val completed = completeSyncBuildOperation(noisy, completedAtEpochMillis = 40)

        assertEquals(listOf("Queued", "Building", "Completed"), completed.events)
        assertEquals(listOf(10L, 30L, 40L), completed.eventTimestampsEpochMillis)
        assertEquals(SyncOperationStore.STATUS_SYNCED, completed.status)
    }

    @Test
    fun legacyFirstEventTimestampIsPreservedWithoutInventingLaterTimes() {
        val legacy = SyncOperationRecord(
            id = "operation",
            target = null,
            kind = SyncOperationKind.SYNC,
            status = SyncOperationStore.STATUS_SYNCED,
            startedAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            events = listOf(
                "Queued 2026-08-21T12:00:00Z",
                "Completed",
            ),
        )

        val lines = syncOperationEventLines(legacy)

        assertEquals("Queued", lines[0].message)
        assertEquals(1_787_313_600_000L, lines[0].timestampEpochMillis)
        assertEquals("Completed", lines[1].message)
        assertEquals(0L, lines[1].timestampEpochMillis)
    }

    private fun record(
        id: String,
        target: String?,
        status: String,
        startedAt: Long,
    ) = SyncOperationRecord(
        id = id,
        target = target,
        kind = SyncOperationKind.SYNC,
        status = status,
        startedAtEpochMillis = startedAt,
        updatedAtEpochMillis = startedAt,
        events = emptyList(),
    )

    private fun buildProgress(
        detail: String = "Compiling paper.tex",
        completed: Int = 1,
    ) = SyncBuildProgress(
        buildId = "build-id",
        target = "paper.pdf",
        serverStatus = "running",
        phase = "compiling_sources",
        completed = completed,
        total = 2,
        unit = "files",
        detail = detail,
        documents = listOf("paper.pdf"),
    )
}
