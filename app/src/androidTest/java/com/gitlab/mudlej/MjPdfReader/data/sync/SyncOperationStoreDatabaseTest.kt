// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncOperationStoreDatabaseTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var store: SyncOperationStore
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("thoughts-sync-operations-v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback(
                { sql, _ -> queries += sql },
                Executor(Runnable::run),
            )
            .build()
        store = SyncOperationStore(context, database)
        queries.clear()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun statusDetailsDoNotLoadRawArchiveUnlessExportIsRequested() = runBlocking {
        val id = store.begin(null, false)
        SyncDiagnosticArchive(context).append(id, "phone_message", "diagnostic-fixture")
        val record = requireNotNull(store.operation(id))
        assertEquals("", loadSavedSyncDetails(context, record).rawArchive)
        assertTrue(loadSavedSyncDetails(context, record, includeRawArchive = true)
            .rawArchive.contains("diagnostic-fixture"))
    }

    @Test
    fun largeHistoryReadsOnlyRecentEventsWithoutDeletingEvidence() = runBlocking {
        val id = store.begin(null, false)
        val dao = database.thoughtsSyncDao()
        dao.appendSyncEvents((1..5000).map { index ->
            ThoughtsSyncEventEntity(
                runId = id, phase = "sync", state = "running", attempt = 1,
                message = "Event $index", createdAtEpochMillis = index.toLong(),
            )
        })
        val recent = dao.recentSyncEvents(id, 1000)
        assertEquals(1000, recent.size)
        assertEquals("Event 4001", recent.first().message)
        assertEquals("Event 5000", recent.last().message)
        assertEquals(1000, dao.observeSyncEvents(id).first().size)
        assertTrue(dao.syncEvents(id).size >= 5000)
        queries.clear()
        store.operationWithEvents(id)
        assertTrue(queries.any { it.contains("LIMIT", ignoreCase = true) && it.contains("sync_events") })
    }

    @Test
    fun failedBuildWaitsForTransfersAndSurvivesStoreRecreation() = runBlocking {
        val id = store.begin(null, false)
        store.deferBuildFailure(id, TerminalPdfBuildException(listOf(
            TerminalPdfBuildFailure("fixture-build", TerminalPdfBuildStatus.FAILED, "Missing dollar", "fixture-log"),
        )))
        assertFalse(isTerminalSyncOperationStatus(store.operation(id)!!.status))
        val restored = SyncOperationStore(context, database)
        restored.append(id, SyncOperationStore.STATUS_SYNCING, "Downloading remaining sources")
        assertFalse(isTerminalSyncOperationStatus(restored.operation(id)!!.status))
        restored.append(id, SyncOperationStore.STATUS_SYNCED, "Completed")
        val completed = restored.operation(id)!!
        assertTrue(isTerminalPdfBuildFailure(completed))
        assertTrue(completed.displayMessage.contains("synced with the server"))
        val events = database.thoughtsSyncDao().syncEvents(id)
        assertTrue(events.any { it.errorCode == "pdf_build_pending" && it.message.contains("fixture-log") })
        assertEquals("pdf_build_failed", events.last().errorCode)
    }

    @Test
    fun transferFailureTakesPriorityOverDeferredCompilerFailure() = runBlocking {
        val id = store.begin(null, false)
        store.deferBuildFailure(id, TerminalPdfBuildException(listOf(
            TerminalPdfBuildFailure("fixture-build", TerminalPdfBuildStatus.FAILED, "compiler failed"),
        )))
        store.fail(id, SyncOperationStore.USER_MESSAGE_CANT_CONNECT, "artifact download failed")
        store.append(id, SyncOperationStore.STATUS_SYNCED, "Late artifact callback")
        assertFalse(isTerminalPdfBuildFailure(store.operation(id)!!))
        assertEquals(SyncOperationStore.USER_MESSAGE_CANT_CONNECT, store.operation(id)!!.displayMessage)
        assertEquals("sync_failed", database.thoughtsSyncDao().syncEvents(id).last().errorCode)
    }

    @Test
    fun blockedUploadPreventsClaimingChangesSyncedAfterBuildFailure() = runBlocking {
        val id = store.begin(null, false)
        store.deferBuildFailure(id, TerminalPdfBuildException(listOf(
            TerminalPdfBuildFailure("fixture-build", TerminalPdfBuildStatus.FAILED, "compiler failed"),
        )))
        database.thoughtsSyncDao().insertOutboxRow(ThoughtsOutboxEntity(
            operationId = "blocked-fixture", deviceSequence = 1,
            entityType = "note", entityId = "fixture", collection = "test",
            baseRevision = null, clientTimestamp = "2026-09-13T00:00:00Z",
            payloadJson = "{}", createdAtEpochMillis = 1,
            deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED,
        ))
        store.append(id, SyncOperationStore.STATUS_SYNCED, "Completed")
        val result = store.operation(id)!!
        assertEquals(SyncOperationStore.STATUS_ERROR, result.status)
        assertFalse(isTerminalPdfBuildFailure(result))
        assertEquals("sync_failed", database.thoughtsSyncDao().syncEvents(id).last().errorCode)
    }

    @Test
    fun legacyBuildFailureCannotHideLaterTransferFailure() = runBlocking {
        val id = store.begin(null, false)
        store.fail(id, SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_TEX_SAVED, "compiler failed")
        store.fail(id, SyncOperationStore.USER_MESSAGE_CANT_CONNECT, "download failed")
        assertFalse(isTerminalPdfBuildFailure(store.operation(id)!!))
        assertEquals("sync_failed", database.thoughtsSyncDao().syncEvents(id).last().errorCode)
    }

    @Test
    fun pendingSummaryDoesNotLoadJournalEvents() = runBlocking {
        val now = System.currentTimeMillis()
        seedRuns(now, 500)
        queries.clear()

        val pending = store.pendingGlobalOperation(now)

        assertEquals("run-0", pending?.id)
        assertFalse(queries.any { it.contains("sync_events", ignoreCase = true) })
        assertFalse(queries.any { it.contains("LIMIT 500", ignoreCase = true) })
    }

    @Test
    fun appendUpdatesOneRunAndAppendsOneEventWithoutScanningHistory() = runBlocking {
        val now = System.currentTimeMillis()
        seedRuns(now, 500)
        val untouchedBefore = database.thoughtsSyncDao().syncRun("run-1")
        queries.clear()

        store.append("run-0", SyncOperationStore.STATUS_WAITING, "Waiting for Wi-Fi")
        val appendQueries = queries.toList()

        val changed = database.thoughtsSyncDao().syncRun("run-0")
        val untouchedAfter = database.thoughtsSyncDao().syncRun("run-1")
        val events = database.thoughtsSyncDao().syncEvents("run-0")
        assertEquals(SyncOperationStore.STATUS_WAITING, changed?.displayStatus)
        assertEquals(untouchedBefore, untouchedAfter)
        assertEquals(2, events.size)
        assertEquals("Waiting for Wi-Fi", events.last().message)
        assertFalse(appendQueries.any { it.contains("startedAtEpochMillis >=", ignoreCase = true) })
        assertEquals(
            1,
            appendQueries.count {
                it.contains("FROM sync_runs", ignoreCase = true) &&
                    it.contains("WHERE id =", ignoreCase = true)
            },
        )
    }

    @Test
    fun detailedHistoryLoadsAllJournalEventsWithOneBatchedQuery() = runBlocking {
        val now = System.currentTimeMillis()
        seedRuns(now, 500)
        queries.clear()

        val records = store.records(now)

        assertEquals(500, records.size)
        assertTrue(records.all { it.journalEvents.size == 1 })
        assertEquals(
            1,
            queries.count {
                it.contains("FROM sync_events", ignoreCase = true) &&
                    it.contains(" IN ", ignoreCase = true)
            },
        )
    }

    @Test
    fun directionalProgressUpdatesOneRunWithoutAppendingJournalEvents() = runBlocking {
        val operationId = store.begin(target = null, building = false)
        val beforeEvents = database.thoughtsSyncDao().syncEvents(operationId).size
        val plan = SyncTransferPlan(
            contractVersion = 2,
            sourceProfile = "reader-v1",
            includeVoiceMedia = true,
            planId = "a".repeat(64),
            serverPlanId = "b".repeat(64),
            serverLibraryRevision = "c".repeat(64),
            remoteObjects = emptyList(),
            operationId = operationId,
            targetPdfFilename = null,
            building = false,
            metadataDownloadBytes = 400,
            uploadBytes = 200,
            downloadBytes = 400,
            thresholdBytes = 5_000_000,
        )

        store.recordTransferPlan(operationId, plan)
        store.recordTransferProgress(operationId, plan.planId, 125, 300)

        val updated = requireNotNull(store.operation(operationId))
        assertTrue(updated.transferMetricsAvailable)
        assertEquals(125, updated.uploadBytesCompleted)
        assertEquals(200, updated.uploadBytesTotal)
        assertEquals(300, updated.downloadBytesCompleted)
        assertEquals(400, updated.downloadBytesTotal)
        assertEquals(425, updated.transferredBytes)
        assertEquals(600, updated.totalBytes)

        store.recordTransferPlan(
            operationId,
            plan.copy(uploadBytesCompleted = 50, metadataBytesCompleted = 100),
        )
        val rolledBack = requireNotNull(store.operation(operationId))
        assertEquals(50, rolledBack.uploadBytesCompleted)
        assertEquals(100, rolledBack.downloadBytesCompleted)
        assertEquals(150, rolledBack.transferredBytes)
        assertEquals(beforeEvents, database.thoughtsSyncDao().syncEvents(operationId).size)
    }

    @Test
    fun terminalCompilerFailureSurvivesHealthyArtifactActivation() = runBlocking {
        val operationId = store.begin(target = null, building = false)
        val exactDisplay = SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_TEX_SAVED
        val exactTechnical = "course-notes.pdf: Undefined control sequence"
        val artifactPlan = ArtifactSyncPlan(
            planId = "a".repeat(64),
            libraryRevision = "b".repeat(64),
            sourceBundleSha256 = "c".repeat(64),
            changedPdfCount = 16,
            estimatedTransferBytes = 1234,
            sourceIndexChanged = true,
            sourceBundleChanged = true,

            postTerminalBuildRefresh = true,
            operationId = operationId,
            explicitRequest = true,
        )

        store.fail(operationId, exactDisplay, exactTechnical)
        store.fail(operationId, exactDisplay, exactTechnical)
        store.recordTransfer(operationId, artifactPlan, completed = false)
        store.recordTransfer(operationId, artifactPlan, completed = true)
        store.append(operationId, SyncOperationStore.STATUS_SYNCED, "Completed")

        val completed = requireNotNull(store.operationWithEvents(operationId))
        assertEquals(SyncOperationStore.STATUS_ERROR, completed.status)
        assertEquals(SyncRunPhase.FAILED, completed.phase)
        assertEquals(exactDisplay, completed.displayMessage)
        assertEquals(1, completed.journalEvents.count { it.message == exactTechnical })
        assertFalse(completed.journalEvents.any { it.message == "Completed" })
        assertEquals(1234, completed.totalBytes)
        assertTrue(completed.planJson.contains(artifactPlan.planId))
    }

    @Test
    fun proposalObserverReadsOnlyProposalOutboxRows() = runBlocking {
        val dao = database.thoughtsSyncDao()
        dao.insertOutbox(outbox("proposal-operation", AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE, 1))
        dao.insertOutbox(outbox("comment-operation", "comment", 2))
        queries.clear()

        val rows = dao.observeByEntityType(AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE).first()

        assertEquals(listOf("proposal-operation"), rows.map(ThoughtsOutboxEntity::operationId))
        assertTrue(queries.any { it.contains("FROM ThoughtsOutbox", ignoreCase = true) })
        assertFalse(queries.any { it.contains("sync_events", ignoreCase = true) })
        assertFalse(queries.any { it.contains("sync_runs", ignoreCase = true) })
    }

    @Test
    fun retiringVoiceQueuePreservesRequestsAndUnrelatedPendingWork() = runBlocking {
        val dao = database.thoughtsSyncDao()
        val voice = outbox("old-voice", "voice_capture", 1).copy(payloadJson = "{\"saved\":true}")
        val assignment = outbox("old-assignment", "voice_assignment", 2).copy(deliveryState = "blocked")
        val comment = outbox("comment", "annotation", 3)
        listOf(voice, assignment, comment).forEach { dao.insertOutboxRow(it) }
        assertEquals(2, dao.retireVoiceOperations())
        assertEquals(0, dao.retireVoiceOperations())
        assertEquals(listOf("comment"), dao.undelivered().map { it.operationId })
        assertEquals(3, dao.allOutbox().size)
        val retained = dao.operation("old-voice")!!
        assertEquals(voice.payloadJson, retained.payloadJson)
        assertEquals("superseded", retained.deliveryState)
        assertEquals("feature_retired", retained.failureCode)
        assertEquals(null, retained.serverSequence)
    }

    private fun outbox(
        operationId: String,
        entityType: String,
        deviceSequence: Long,
    ) = ThoughtsOutboxEntity(
        operationId = operationId,
        deviceSequence = deviceSequence,
        entityType = entityType,
        entityId = operationId,
        collection = "test",
        baseRevision = null,
        clientTimestamp = "2026-08-28T00:00:00Z",
        payloadJson = "{}",
        createdAtEpochMillis = deviceSequence,
    )

    private suspend fun seedRuns(now: Long, count: Int) {
        val runs = List(count) { index ->
            ThoughtsSyncRunEntity(
                id = "run-$index",
                traceId = "run-$index",
                target = null,
                kind = "sync",
                state = SyncRunPhase.DOWNLOADING,
                phase = SyncRunPhase.DOWNLOADING,
                displayStatus = SyncOperationStore.STATUS_SYNCING,
                startedAtEpochMillis = now - index,
                updatedAtEpochMillis = now - index,
                eventsJson = "[\"Syncing\"]",
                eventTimestampsJson = "[${now - index}]",
                legacyEventsImported = true,
            )
        }
        database.thoughtsSyncDao().putSyncRuns(runs)
        database.thoughtsSyncDao().appendSyncEvents(
            runs.map { run ->
                ThoughtsSyncEventEntity(
                    runId = run.id,
                    phase = run.phase,
                    state = run.state,
                    attempt = 0,
                    message = "Initial",
                    createdAtEpochMillis = run.startedAtEpochMillis,
                )
            },
        )
    }
}
