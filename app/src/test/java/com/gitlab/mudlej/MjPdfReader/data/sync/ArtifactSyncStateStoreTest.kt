// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.tbend.thoughts.sdk.ServerException
import androidx.work.NetworkType

class ArtifactSyncStateStoreTest {
    private lateinit var root: File
    private lateinit var store: ArtifactSyncStateStore

    @Test
    fun terminalReplanForcesArtifactWorkEvenWhenPlanIdentityIsUnchanged() {
        val planId = "a".repeat(64)

        assertFalse(shouldReplaceArtifactWork(planId, planId, forceReplaceWork = false))
        assertTrue(shouldReplaceArtifactWork(planId, planId, forceReplaceWork = true))
        assertTrue(shouldReplaceArtifactWork(planId, "b".repeat(64), forceReplaceWork = false))
    }

    @Test
    fun refreshLimitSurvivesWorkerAndStoreRecreation() {
        var candidate = plan("a".repeat(64))
        repeat(3) {
            candidate = candidate.copy(planRefreshCount = nextArtifactPlanRefreshCount(candidate.planRefreshCount))
            store.publish(candidate)
            candidate = requireNotNull(ArtifactSyncStateStore(File(root, "state.json")).load())
        }
        assertEquals(3, candidate.planRefreshCount)
        assertTrue(runCatching { nextArtifactPlanRefreshCount(candidate.planRefreshCount) }.isFailure)
        assertEquals(1, nextArtifactPlanRefreshCount(0))
    }

    @Test
    fun failedArtifactRefreshStaysStoppedAcrossApplicationResume() {
        for (terminalBuild in listOf(false, true)) {
            val failed = plan("a".repeat(64)).copy(
                artifactOnlyRefresh = true,
                postTerminalBuildRefresh = terminalBuild,
                status = ArtifactSyncPlan.STATUS_FAILED,
                planRefreshCount = 3,
            )
            store.publish(failed)
            val restored = ArtifactSyncStateStore(File(root, "state.json")).load()
            assertEquals(TerminalArtifactResumeAction.NONE, terminalArtifactResumeAction(restored, null))
        }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("artifact-sync-state").toFile()
        store = ArtifactSyncStateStore(File(root, "state.json"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun oneTimeApprovalSurvivesSamePlanRetriesButNotNextPlan() {
        val first = plan("a".repeat(64)).copy(targetPdfFilenames = listOf("manual.pdf"))
        store.publish(first)
        store.approveMetered(first.planId)

        val retried = store.publish(first.copy(explicitRequest = true))

        assertTrue(retried.meteredApproved)
        assertTrue(retried.explicitRequest)
        assertFalse(retried.waitForUnmetered)
        assertEquals(listOf("manual.pdf"), store.load()?.targetPdfFilenames)

        val next = store.publish(plan("b".repeat(64)))

        assertFalse(next.meteredApproved)
        assertFalse(next.promptShown)
    }

    @Test
    fun waitChoiceIsDurableAndClearIsScopedToExactPlan() {
        val plan = plan("c".repeat(64))
        store.publish(plan)
        store.markWaiting(plan.planId)

        val loaded = store.load()

        assertTrue(loaded?.waitForUnmetered == true)
        assertTrue(loaded?.promptShown == true)
        assertFalse(store.clear("d".repeat(64)))
        assertEquals(plan.planId, store.load()?.planId)
        assertTrue(store.clear(plan.planId))
        assertNull(store.load())
    }

    @Test
    fun meteredDecisionRestoresWaitingUiAfterBackgroundRetry() {
        val plan = plan("d".repeat(64)).copy(
            postTerminalBuildRefresh = true,
            explicitRequest = true,
            sourceBundleChanged = true,
        )
        store.publish(plan)
        store.markRetrying(plan.planId, "Fedora is temporarily unreachable")

        val waiting = requireNotNull(store.markAwaitingMeteredDecision(plan.planId))

        assertEquals(ArtifactSyncPlan.STATUS_WAITING_NETWORK, waiting.status)
        assertEquals("", waiting.error)
        assertTrue(waiting.promptShown)
        assertFalse(waiting.waitForUnmetered)
    }

    @Test
    fun terminalTransferPreservesLastRetryDetailUntilItCompletes() {
        val plan = plan("c".repeat(64)).copy(postTerminalBuildRefresh = true)
        val retryDetail = "Unable to resolve host server.example.ts.net"
        store.publish(plan)
        store.markRetrying(plan.planId, retryDetail)

        val transferring = requireNotNull(
            store.markTransferring(plan.planId, preserveError = true),
        )

        assertEquals(ArtifactSyncPlan.STATUS_TRANSFERRING, transferring.status)
        assertEquals(retryDetail, transferring.error)
        assertEquals(
            "",
            requireNotNull(store.markTransferring(plan.planId)).error,
        )
    }

    @Test
    fun thresholdIsStrictRegardlessOfSourceArtifactChanges() {
        assertTrue(plan("e".repeat(64)).canRunOnMeteredWithoutApproval)
        assertTrue(
            plan("f".repeat(64)).copy(estimatedTransferBytes = 5_000_000)
                .canRunOnMeteredWithoutApproval,
        )
        assertFalse(
            plan("0".repeat(64)).copy(estimatedTransferBytes = 5_000_001)
                .canRunOnMeteredWithoutApproval,
        )
        for (indexChanged in listOf(false, true)) {
            for (bundleChanged in listOf(false, true)) {
                val sourcePlan = plan("1".repeat(64)).copy(
                    estimatedTransferBytes = 380_166,
                    sourceIndexChanged = indexChanged,
                    sourceBundleChanged = bundleChanged,
                )
                assertTrue(sourcePlan.canRunOnMeteredWithoutApproval)
                assertEquals(NetworkType.CONNECTED, artifactRequiredNetworkType(sourcePlan, allowMetered = true))
                assertFalse(artifactTransferNeedsUnmeteredApproval(sourcePlan, unmetered = false))
                assertFalse(sourcePlan.copy(estimatedTransferBytes = 5_000_001).canRunOnMeteredWithoutApproval)
            }
        }
    }

    @Test
    fun artifactConstraintRequiresUnmeteredUnlessExactPlanAllowsMobileData() {
        val bulk = plan("4".repeat(64)).copy(estimatedTransferBytes = 5_000_001)

        assertEquals(NetworkType.UNMETERED, artifactRequiredNetworkType(bulk, allowMetered = false))
        assertEquals(NetworkType.UNMETERED, artifactRequiredNetworkType(bulk, allowMetered = true))
        assertEquals(
            NetworkType.CONNECTED,
            artifactRequiredNetworkType(bulk.copy(meteredApproved = true), allowMetered = true),
        )
        assertEquals(
            NetworkType.CONNECTED,
            artifactRequiredNetworkType(plan("5".repeat(64)), allowMetered = true),
        )
        val terminal = bulk.copy(postTerminalBuildRefresh = true)
        assertEquals(
            NetworkType.NOT_REQUIRED,
            artifactRequiredNetworkType(terminal, allowMetered = false),
        )
        assertEquals(NetworkType.NOT_REQUIRED, TERMINAL_ARTIFACT_WORK_NETWORK_TYPE)
        assertFalse(appliesTerminalArtifactNetworkPolicy(0))
        assertTrue(
            appliesTerminalArtifactNetworkPolicy(TERMINAL_ARTIFACT_NETWORK_POLICY_VERSION),
        )
        assertFalse(appliesTerminalArtifactNetworkPolicy(2))
        assertTrue(artifactTransferNeedsUnmeteredApproval(terminal, unmetered = false))
        assertFalse(
            artifactTransferNeedsUnmeteredApproval(
                terminal.copy(meteredApproved = true),
                unmetered = false,
            ),
        )
        assertFalse(artifactTransferNeedsUnmeteredApproval(terminal, unmetered = true))
    }

    @Test
    fun restartRequeuesTerminalArtifactLaneWithoutRenderControl() {
        val operationId = "11111111-1111-4111-8111-111111111111"
        val artifact = plan("9".repeat(64)).copy(
            postTerminalBuildRefresh = true,
            operationId = operationId,
        )
        val transfer = SyncTransferPlan(
            contractVersion = 2,
            sourceProfile = "reader-v1",
            includeVoiceMedia = false,
            planId = "a".repeat(64),
            serverPlanId = "b".repeat(64),
            serverLibraryRevision = artifact.libraryRevision,
            remoteObjects = emptyList(),
            operationId = operationId,
            targetPdfFilename = null,
            building = false,
            uploadBytes = 0,
            downloadBytes = 0,
            thresholdBytes = 5_000_000,
        )

        assertEquals(
            TerminalArtifactResumeAction.NONE,
            terminalArtifactResumeAction(artifact.copy(postTerminalBuildRefresh = false), transfer),
        )
        assertEquals(
            TerminalArtifactResumeAction.REPLAN,
            terminalArtifactResumeAction(artifact, null),
        )
        assertEquals(
            TerminalArtifactResumeAction.DOWNLOAD,
            terminalArtifactResumeAction(artifact, transfer),
        )
        assertEquals(
            TerminalArtifactResumeAction.REPLAN,
            terminalArtifactResumeAction(
                artifact,
                transfer.copy(serverLibraryRevision = "c".repeat(64)),
            ),
        )
        assertTrue(shouldReplaceTerminalArtifactWorkOnResume(artifact))
        assertFalse(
            shouldReplaceTerminalArtifactWorkOnResume(
                artifact.copy(terminalNetworkPolicyApplied = true),
            ),
        )
    }

    @Test
    fun rediscoveringSamePlanDoesNotEraseTerminalFailure() {
        val operationId = "11111111-1111-4111-8111-111111111111"
        val plan = plan("6".repeat(64)).copy(operationId = operationId)
        store.publish(plan)
        store.markFailed(plan.planId, "Missing exact source index")

        val rediscovered = store.publish(plan.copy(explicitRequest = true))

        assertEquals(ArtifactSyncPlan.STATUS_FAILED, rediscovered.status)
        assertEquals("Missing exact source index", rediscovered.error)
        assertTrue(rediscovered.explicitRequest)
    }

    @Test
    fun newExplicitOperationRestartsSameFailedArtifactPlan() {
        val firstOperationId = "11111111-1111-4111-8111-111111111111"
        val nextOperationId = "22222222-2222-4222-8222-222222222222"
        val plan = plan("a".repeat(64)).copy(
            operationId = firstOperationId,
            explicitRequest = true,
        )
        store.publish(plan)
        store.approveMetered(plan.planId)
        store.markFailed(plan.planId, "Unable to resolve Fedora")

        val nextAttempt = plan.copy(operationId = nextOperationId)
        assertTrue(startsNewExplicitArtifactAttempt(store.load(), nextAttempt))
        val restarted = store.publish(nextAttempt)

        assertEquals(nextOperationId, restarted.operationId)
        assertEquals(ArtifactSyncPlan.STATUS_WAITING_NETWORK, restarted.status)
        assertEquals("", restarted.error)
        assertFalse(restarted.promptShown)
        assertFalse(restarted.meteredApproved)
        assertFalse(restarted.waitForUnmetered)
        assertFalse(restarted.terminalNetworkPolicyApplied)
    }

    @Test
    fun appRestartRecoversOnlyNonterminalOrdinaryArtifactWork() {
        val plan = plan("b".repeat(64))

        assertTrue(shouldResumeArtifactWork(plan))
        assertTrue(shouldResumeArtifactWork(plan.copy(status = ArtifactSyncPlan.STATUS_QUEUED)))
        assertTrue(shouldResumeArtifactWork(plan.copy(status = ArtifactSyncPlan.STATUS_TRANSFERRING)))
        assertTrue(shouldResumeArtifactWork(plan.copy(status = ArtifactSyncPlan.STATUS_RETRYING)))
        assertFalse(shouldResumeArtifactWork(plan.copy(status = ArtifactSyncPlan.STATUS_FAILED)))
        assertFalse(shouldResumeArtifactWork(plan.copy(postTerminalBuildRefresh = true)))
    }

    @Test
    fun retryPolicyRejectsProtocolErrorsAndBoundsTransientRetries() {
        assertFalse(shouldRetrySyncFailure(IllegalStateException("bad manifest"), 0))
        assertFalse(shouldRetrySyncFailure(ServerException(404, "missing object"), 0))
        assertTrue(shouldRetrySyncFailure(ServerException(503, "unavailable"), 0))
        assertTrue(shouldRetrySyncFailure(IOException("connection reset"), 2))
        assertFalse(shouldRetrySyncFailure(IOException("connection reset"), 3))
        assertTrue(shouldRetryTerminalArtifactWork(ArtifactPlanSupersededException(), 0))
        assertFalse(shouldRetryTerminalArtifactWork(ArtifactPlanSupersededException(), 3))
        assertTrue(shouldRetryTerminalArtifactWork(UnknownHostException("offline"), 99))
        assertFalse(shouldRetryTerminalArtifactWork(IOException("generic"), 3))
        val compilerFailure = TerminalPdfBuildException(
            listOf(
                TerminalPdfBuildFailure(
                    buildId = "build-1",
                    status = TerminalPdfBuildStatus.FAILED,
                    detail = "renderer exited with 1",
                    logReference = "server-object:${"a".repeat(64)}",
                ),
            ),
        )
        assertFalse(shouldRetrySyncFailure(compilerFailure, 0))
        assertFalse(
            shouldRetrySyncFailure(
                IOException("transport wrapper", compilerFailure),
                0,
            ),
        )
        assertEquals(1_000L, transientRetryDelayMillis(1, 0))
        assertEquals(2_125L, transientRetryDelayMillis(2, 125))
        assertEquals(12_250L, transientRetryDelayMillis(3, 250))
    }

    @Test
    fun sameArtifactPlanKeepsTheNewestBuildActivityPhase() {
        val plan = plan("7".repeat(64))
        store.publish(plan.copy(buildsActive = true))

        val updated = store.publish(plan.copy(buildsActive = false))

        assertFalse(updated.buildsActive)
        assertFalse(store.load()?.buildsActive ?: true)
    }

    @Test
    fun terminalBuildArtifactRefreshModeSurvivesRestart() {
        val plan = plan("8".repeat(64)).copy(postTerminalBuildRefresh = true)
        val retryDetail = "Unable to resolve host server.example.ts.net"

        store.publish(plan)
        store.markRetrying(plan.planId, retryDetail)
        store.markTerminalNetworkPolicyApplied(plan.planId)
        store.publish(plan.copy(explicitRequest = true))

        val loaded = requireNotNull(store.load())
        assertTrue(loaded.postTerminalBuildRefresh)
        assertTrue(loaded.terminalNetworkPolicyApplied)
        assertEquals(ArtifactSyncPlan.STATUS_RETRYING, loaded.status)
        assertEquals(retryDetail, loaded.error)
    }

    @Test
    fun activatedPdfUpdatesContainOnlyNewlyInstalledVersions() {
        val updates = activatedPdfUpdates(
            previous = mapOf(
                "current.pdf" to "a".repeat(64),
                "unchanged.pdf" to "b".repeat(64),
            ),
            current = mapOf(
                "current.pdf" to "c".repeat(64),
                "new.pdf" to "d".repeat(64),
                "unchanged.pdf" to "b".repeat(64),
            ),
        )

        assertEquals(
            mapOf(
                "current.pdf" to "c".repeat(64),
                "new.pdf" to "d".repeat(64),
            ),
            updates,
        )
    }

    private fun plan(id: String) = ArtifactSyncPlan(
        planId = id,
        libraryRevision = "2".repeat(64),
        sourceBundleSha256 = "3".repeat(64),
        changedPdfCount = 0,
        estimatedTransferBytes = 0,
        sourceIndexChanged = false,
        sourceBundleChanged = false,

    )
}
