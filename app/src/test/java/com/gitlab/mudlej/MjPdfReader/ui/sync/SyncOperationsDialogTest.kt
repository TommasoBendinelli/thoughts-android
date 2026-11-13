// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncBuildProgress
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncJournalEvent
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncRunPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.syncRunPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOperationsDialogTest {

    @Test
    fun targetHistoryContainsOnlyExactTargetOperations() {
        val global = record("global", null)
        val target = record("target", "ideas.pdf")
        val other = record("other", "paper.pdf")

        assertEquals(
            listOf(target),
            syncOperationRecordsForTarget(listOf(global, target, other), "ideas.pdf"),
        )
    }

    @Test
    fun globalHistoryPreservesEveryOperation() {
        val records = listOf(
            record("global", null),
            record("target", "ideas.pdf"),
            record("other", "paper.pdf"),
        )

        assertEquals(records, syncOperationRecordsForTarget(records, null))
    }

    @Test
    fun lastPdfLogIsTheNewestOperationAffectingThatPdf() {
        val exactTarget = record("target", "ideas.pdf", updatedAtEpochMillis = 20)
        val newerGlobal = record("global", null, updatedAtEpochMillis = 30)
        val newestOther = record("other", "paper.pdf", updatedAtEpochMillis = 40)

        assertEquals(
            newerGlobal,
            latestRelevantSyncOperationRecord(
                listOf(exactTarget, newerGlobal, newestOther),
                "ideas.pdf",
            ),
        )
    }

    @Test
    fun lastGlobalLogExcludesNewerPdfSpecificOperations() {
        val global = record("global", null, updatedAtEpochMillis = 20)
        val newerTarget = record("target", "ideas.pdf", updatedAtEpochMillis = 30)

        assertEquals(
            global,
            latestRelevantSyncOperationRecord(listOf(global, newerTarget), target = null),
        )
    }

    @Test
    fun retryRetainsWhetherTheOriginalOperationWasABuild() {
        val build = record(
            id = "build",
            target = "ideas.pdf",
            kind = SyncOperationKind.BUILD,
        )
        val sync = record(
            id = "sync",
            target = "ideas.pdf",
            events = listOf("${SyncOperationStore.STATUS_SYNCING} 2026-08-21T12:00:00Z"),
        )

        assertTrue(syncOperationWasBuild(build))
        assertFalse(syncOperationWasBuild(sync))
    }

    @Test
    fun lifecycleTitlesHideQueuedAndTechnicalStatusLabels() {
        val successfulBuild = record("build", "ideas.pdf", kind = SyncOperationKind.BUILD)
        val successfulSync = record("sync", "ideas.pdf")
        val activeBuild = record(
            id = "active-build",
            target = "ideas.pdf",
            kind = SyncOperationKind.BUILD,
            status = SyncOperationStore.STATUS_BUILDING,
        )
        val failedBuild = record(
            id = "failed-build",
            target = "ideas.pdf",
            kind = SyncOperationKind.BUILD,
            status = SyncOperationStore.STATUS_ERROR,
        )

        assertEquals("Build complete", syncOperationStatusLabel(successfulBuild, "Built"))
        assertEquals("Up to date", syncOperationStatusLabel(successfulSync, "Built"))
        assertEquals("Building", syncOperationStatusLabel(activeBuild, "Built"))
        assertEquals(
            "Building",
            syncOperationStatusLabel(
                successfulBuild.copy(status = SyncOperationStore.STATUS_SYNCING),
                "Built",
            ),
        )
        assertEquals("PDF build failed", syncOperationStatusLabel(failedBuild, "Built"))
        assertEquals("PDF build failed", syncOperationStatusLabel(
            failedBuild.copy(kind = SyncOperationKind.SYNC,
                displayMessage = SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED), "Built"))
        assertEquals("PDF build failed", syncOperationStatusLabel(
            failedBuild.copy(displayMessage = SyncOperationStore.USER_MESSAGE_CANT_CONNECT), "Built"))

        assertEquals(
            "PDF build failed",
            syncOperationStatusLabel(
                failedBuild.copy(status = SyncOperationStore.STATUS_ATTENTION),
                "Built",
            ),
        )
        assertEquals(
            "Waiting to build",
            syncOperationStatusLabel(
                successfulBuild.copy(status = SyncOperationStore.STATUS_WAITING),
                "Built",
            ),
        )
        assertEquals(
            "Build interrupted",
            syncOperationStatusLabel(failedBuild.copy(phase = SyncRunPhase.INTERRUPTED), "Built"),
        )
        assertEquals(
            "Syncing…",
            syncOperationStatusLabel(
                successfulSync.copy(status = SyncOperationStore.STATUS_QUEUED),
                "Built",
            ),
        )
        assertEquals(
            "Waiting to sync",
            syncOperationStatusLabel(
                successfulSync.copy(status = SyncOperationStore.STATUS_WAITING),
                "Built",
            ),
        )
        assertEquals(
            "Sync failed",
            syncOperationStatusLabel(
                successfulSync.copy(status = SyncOperationStore.STATUS_ERROR),
                "Built",
            ),
        )
        assertEquals(
            "Sync failed",
            syncOperationStatusLabel(
                successfulSync.copy(status = SyncOperationStore.STATUS_ATTENTION),
                "Built",
            ),
        )
        assertEquals(
            "Sync interrupted",
            syncOperationStatusLabel(
                successfulSync.copy(
                    status = SyncOperationStore.STATUS_ERROR,
                    phase = SyncRunPhase.CANCELLED,
                ),
                "Built",
            ),
        )
    }

    @Test
    fun statusShowLogsOpensTheLocalBuildAtTheDisplayedTimestamp() {
        val older = record(
            id = "older",
            target = null,
            kind = SyncOperationKind.BUILD,
            updatedAtEpochMillis = 10_000,
        )
        val displayed = record(
            id = "displayed",
            target = null,
            kind = SyncOperationKind.BUILD,
            updatedAtEpochMillis = 100_012,
        )

        assertEquals(
            displayed,
            localBuildLogRecordForStatus(
                listOf(older, displayed),
                renderStatus(100_000),
            ),
        )
    }

    @Test
    fun pdfStatusCanResolveToTheGlobalBuildThatProducedIt() {
        val global = record(
            id = "global",
            target = null,
            kind = SyncOperationKind.BUILD,
            updatedAtEpochMillis = 100_012,
        )

        assertEquals(
            global,
            localBuildLogRecordForStatus(
                listOf(global),
                renderStatus(100_000, target = "paper.pdf"),
            ),
        )
    }

    @Test
    fun unrelatedOldBuildIsNotOpenedAsTheCurrentLog() {
        val unrelated = record(
            id = "unrelated",
            target = null,
            kind = SyncOperationKind.BUILD,
            updatedAtEpochMillis = 1,
        )

        assertNull(
            localBuildLogRecordForStatus(
                listOf(unrelated),
                renderStatus(1_000_000),
                toleranceMillis = 1_000,
            ),
        )
    }

    @Test
    fun displayedAndCopiedLogUseTheSameTimestampedText() {
        val record = record(
            id = "build",
            target = null,
            kind = SyncOperationKind.BUILD,
            events = listOf("Queued", "Completed"),
            eventTimestampsEpochMillis = listOf(1_000, 3_000),
            updatedAtEpochMillis = 3_000,
        ).copy(startedAtEpochMillis = 1_000)

        assertEquals(
            "Started: T1000\n" +
                "Elapsed: 2 sec\n" +
                "Uploaded: Not recorded\n" +
                "Downloaded: Not recorded\n" +
                "Trace ID: build\n" +
                "Server stream: Saved log\n\n" +
                "+0 sec · Queued\n+2 sec · Completed",
            syncOperationLogDetails(record, "Time not recorded") { "T$it" },
        )
    }

    @Test
    fun journalShowsTheFullDateOnceAndElapsedTimeForEveryEvent() {
        val record = record(
            id = "sync",
            target = null,
            updatedAtEpochMillis = 4_000,
        ).copy(
            startedAtEpochMillis = 1_000,
            journalEvents = listOf(
                SyncJournalEvent(
                    id = 1,
                    origin = "server",
                    serverSequence = 1,
                    occurredAtEpochMillis = 3_000,
                    receivedAtEpochMillis = 3_000,
                    component = "fedora_worker",
                    phase = "building",
                    state = "running",
                    buildId = "build",
                    message = "Compiling paper.tex",
                    completed = 1,
                    total = 2,
                    bytesCompleted = null,
                    bytesTotal = null,
                    errorCode = "",
                    metadataJson = "",
                ),
            ),
        )

        val details = syncOperationLogDetails(record, "Time not recorded") { "DATE-$it" }

        assertTrue(details.startsWith("Started: DATE-1000\nElapsed: 3 sec"))
        assertTrue(details.contains("+2 sec · Fedora worker · Compiling paper.tex (1 of 2)"))
        assertEquals(1, "DATE-".toRegex().findAll(details).count())
    }

    @Test
    fun mobileJournalOmitsTheRedundantPhoneAndPhaseLabels() {
        val record = record(
            id = "sync",
            target = null,
            updatedAtEpochMillis = 4_000,
        ).copy(
            startedAtEpochMillis = 1_000,
            journalEvents = listOf(
                journalEvent(
                    id = 1,
                    component = "phone",
                    phase = "queued",
                    message = "Queued",
                    occurredAtEpochMillis = 1_000,
                ),
                journalEvent(
                    id = 2,
                    component = "phone",
                    phase = "building",
                    message = "Building",
                    occurredAtEpochMillis = 2_000,
                ),
                journalEvent(
                    id = 3,
                    component = "api",
                    phase = "build_reused",
                    message = "Reusing Fedora build 123.",
                    occurredAtEpochMillis = 3_000,
                ),
            ),
        )

        val details = syncOperationLogDetails(record, "Time not recorded") { "DATE-$it" }

        assertTrue(details.contains("+0 sec · Queued"))
        assertTrue(details.contains("+1 sec · Building"))
        assertTrue(details.contains("+2 sec · API · Reusing Fedora build 123."))
        assertFalse(details.contains("Phone"))
        assertFalse(details.contains("Queued · Queued"))
        assertFalse(details.contains("Building · Building"))
        assertFalse(details.contains("Build reused"))
    }

    @Test
    fun activeBuildLogExplainsWhatFedoraIsDoingWithoutRepeatedPollMessages() {
        val record = record(
            id = "build",
            target = null,
            kind = SyncOperationKind.BUILD,
            status = SyncOperationStore.STATUS_BUILDING,
            events = listOf("Queued", "Building"),
            eventTimestampsEpochMillis = listOf(10, 70_010),
        ).copy(
            startedAtEpochMillis = 10,
            updatedAtEpochMillis = 70_010,
            buildCheckedAtEpochMillis = 70_010,
            buildProgress = listOf(
                SyncBuildProgress(
                    buildId = "fedora-build-id",
                    target = "all",
                    serverStatus = "running",
                    phase = "compiling_sources",
                    completed = 3,
                    total = 17,
                    unit = "files",
                    detail = "Compiling docs-benchmark/tex/source/concepts.tex",
                    documents = listOf("concepts.pdf", "Benchmark-Conf.pdf"),
                ),
            ),
        )

        assertEquals(
            "Started: T10\n" +
                "Elapsed: 1 min 10 sec\n" +
                "Uploaded: Not recorded\n" +
                "Downloaded: Not recorded\n" +
                "Trace ID: build\n" +
                "Server stream: Saved log\n\n" +
                "+0 sec · Queued\n" +
                "+70 sec · Building (last checked)",
            syncOperationLogDetails(record, "Time not recorded") { "T$it" },
        )
    }

    @Test
    fun oldStepsExplicitlySayWhenTheirTimestampWasNotRecorded() {
        val record = record(
            id = "old",
            target = null,
            events = listOf("Downloading"),
        )

        assertEquals(
            "Started: T1\n" +
                "Elapsed: 0 sec\n" +
                "Uploaded: Not recorded\n" +
                "Downloaded: Not recorded\n" +
                "Trace ID: old\n" +
                "Server stream: Saved log\n\n" +
                "Time not recorded · Downloading",
            syncOperationLogDetails(record, "Time not recorded") { "T$it" },
        )
    }

    @Test
    fun activeDirectionalProgressAndElapsedUseTheCurrentTime() {
        val record = record(
            id = "progress",
            target = null,
            status = SyncOperationStore.STATUS_SYNCING,
            updatedAtEpochMillis = 2_000,
        ).copy(
            startedAtEpochMillis = 1_000,
            transferMetricsAvailable = true,
            uploadBytesCompleted = 128,
            uploadBytesTotal = 256,
            downloadBytesCompleted = 1_024,
            downloadBytesTotal = 2_048,
            streamState = SyncOperationStore.STREAM_LIVE,
        )

        assertEquals(
            "Started: T1000\n" +
                "Elapsed: 4 sec\n" +
                "Uploaded: B128 of B256\n" +
                "Downloaded: B1024 of B2048\n" +
                "Trace ID: progress\n" +
                "Server stream: Live",
            syncOperationLogDetails(
                record = record,
                unavailableTimestamp = "Time not recorded",
                nowEpochMillis = 5_000,
                formatTimestamp = { "T$it" },
                formatBytes = { "B$it" },
            ),
        )
    }

    @Test
    fun serverStreamLabelsDescribeTheJournalSource() {
        val base = record("stream", null)

        assertTrue(
            syncOperationLogDetails(
                base.copy(streamState = SyncOperationStore.STREAM_LIVE),
                "Never",
                formatTimestamp = { "DATE-$it" },
            ).contains("Server stream: Live"),
        )
        assertTrue(
            syncOperationLogDetails(
                base.copy(streamState = SyncOperationStore.STREAM_RECONNECTING),
                "Never",
                formatTimestamp = { "DATE-$it" },
            ).contains("Server stream: Reconnecting"),
        )
        assertTrue(
            syncOperationLogDetails(
                base.copy(streamState = SyncOperationStore.STREAM_SAVED),
                "Never",
                formatTimestamp = { "DATE-$it" },
            ).contains("Server stream: Saved log"),
        )
    }

    @Test
    fun measuredZeroByteSuccessIsDifferentFromALegacyLog() {
        val measured = record("measured", null).copy(transferMetricsAvailable = true)
        val legacy = record("legacy", null)

        assertTrue(
            syncOperationLogDetails(measured, "Time not recorded") { "T$it" }
                .contains("Uploaded: 0 bytes\nDownloaded: 0 bytes"),
        )
        assertTrue(
            syncOperationLogDetails(legacy, "Time not recorded") { "T$it" }
                .contains("Uploaded: Not recorded\nDownloaded: Not recorded"),
        )
    }

    @Test
    fun completedFedoraBuildReplacesStaleRunningMessageAfterPhoneInterruption() {
        val record = record(
            id = "trace",
            target = "paper.pdf",
            kind = SyncOperationKind.BUILD,
            status = SyncOperationStore.STATUS_ERROR,
        ).copy(
            phase = SyncRunPhase.INTERRUPTED,
            displayMessage = "Phone disconnected; the server status was last known as running.",
            serverState = "succeeded",
            serverTerminal = true,
        )

        assertEquals(
            "Build completed on the server; local download is pending.",
            syncOperationDisplayMessage(record),
        )
        assertTrue(
            syncOperationLogDetails(record, "Time not recorded") { "T$it" }
                .contains("Build completed on the server; local download is pending."),
        )
    }

    @Test
    fun lateServerReplayDoesNotExtendCompletedSyncDuration() {
        val finished = SyncJournalEvent(
            id = 1, origin = "phone", serverSequence = null,
            occurredAtEpochMillis = 68_363, receivedAtEpochMillis = 68_363,
            component = "phone", phase = SyncRunPhase.SUCCEEDED, state = SyncRunPhase.SUCCEEDED,
            buildId = "", message = "Completed", completed = null, total = null,
            bytesCompleted = null, bytesTotal = null, errorCode = "", metadataJson = "{}",
        )
        val replay = finished.copy(
            id = 2, origin = "server", serverSequence = 42,
            occurredAtEpochMillis = 46_000, receivedAtEpochMillis = 293_447,
            message = "Reusing Fedora build",
        )
        val completed = record("sync", null, updatedAtEpochMillis = 293_447).copy(
            status = SyncOperationStore.STATUS_SYNCED, startedAtEpochMillis = 1_000,
            journalEvents = listOf(finished, replay),
        )
        assertEquals(68_363L, syncOperationElapsedEnd(completed, 600_000))
        assertTrue(syncOperationLogDetails(completed, "unknown") { "$it" }
            .contains("Elapsed: 1 min 7 sec"))
        // Failures also stop locally; a terminal server build is not a terminal phone sync.
        assertEquals(68_363L, syncOperationElapsedEnd(completed.copy(
            status = SyncOperationStore.STATUS_ERROR,
            journalEvents = listOf(finished.copy(state = SyncRunPhase.FAILED), replay),
        ), 600_000))
        assertEquals(600_000L, syncOperationElapsedEnd(completed.copy(
            status = SyncOperationStore.STATUS_SYNCING,
        ), 600_000))
    }

    @Test
    fun historicalCompletedLogUsesItsRecordedCompletionTime() {
        val completed = record("old", null, updatedAtEpochMillis = 293_000).copy(
            status = SyncOperationStore.STATUS_SYNCED, startedAtEpochMillis = 1_000,
            events = listOf("Queued", "Completed"), eventTimestampsEpochMillis = listOf(1_000, 68_000),
        )
        assertEquals(68_000L, syncOperationElapsedEnd(completed, 600_000))
    }

    private fun record(
        id: String,
        target: String?,
        kind: SyncOperationKind = SyncOperationKind.SYNC,
        status: String = SyncOperationStore.STATUS_SYNCED,
        events: List<String> = emptyList(),
        eventTimestampsEpochMillis: List<Long> = emptyList(),
        updatedAtEpochMillis: Long = 1,
    ): SyncOperationRecord = SyncOperationRecord(
        id = id,
        target = target,
        kind = kind,
        status = status,
        startedAtEpochMillis = 1,
        updatedAtEpochMillis = updatedAtEpochMillis,
        events = events,
        eventTimestampsEpochMillis = eventTimestampsEpochMillis,
        phase = syncRunPhase(status, events.lastOrNull().orEmpty()),
    )

    private fun renderStatus(
        completedAtEpochMillis: Long,
        target: String = PhoneRenderQueue.ACTION_ALL,
    ): PhoneRenderJobStatus = PhoneRenderJobStatus(
        requestId = "request",
        target = target,
        stage = PhoneRenderStage.PARTIAL,
        completedAtEpochMillis = completedAtEpochMillis,
    )

    private fun journalEvent(
        id: Long,
        component: String,
        phase: String,
        message: String,
        occurredAtEpochMillis: Long,
    ): SyncJournalEvent = SyncJournalEvent(
        id = id,
        origin = if (component == "phone") "phone" else "server",
        serverSequence = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        receivedAtEpochMillis = occurredAtEpochMillis,
        component = component,
        phase = phase,
        state = phase,
        buildId = "",
        message = message,
        completed = null,
        total = null,
        bytesCompleted = null,
        bytesTotal = null,
        errorCode = "",
        metadataJson = "",
    )
}
