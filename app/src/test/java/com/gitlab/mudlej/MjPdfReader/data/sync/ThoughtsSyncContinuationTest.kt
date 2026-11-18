// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtsSyncContinuationTest {

    @Test
    fun aBuildStillRunningOnFedoraNoLongerKeepsTheSyncGoing() {
        // 13 November 2026: a Sync spent 44 of its 63 s waiting for a Fedora build. It now ends
        // and RemoteWorkWatch fetches the PDFs; only undelivered source edits continue it.
        assertFalse(needsBackgroundSyncContinuation(sourceRequestsPending = false, explicit = true))
        assertFalse(needsBackgroundSyncContinuation(sourceRequestsPending = false, explicit = false))
    }

    @Test
    fun pendingSourceRequestDoesNotAuthorizeABackgroundContinuation() {
        assertFalse(needsBackgroundSyncContinuation(sourceRequestsPending = true, explicit = false))
    }

    @Test
    fun pendingSourceRequestContinuesAnExplicitSync() {
        assertTrue(needsBackgroundSyncContinuation(sourceRequestsPending = true, explicit = true))
    }

    @Test
    fun supersededArtifactPlansResumeInArtifactLaneWithoutChangingMobileThreshold() {
        val terminalPlan = ArtifactSyncPlan(
            planId = "a".repeat(64),
            libraryRevision = "b".repeat(64),
            sourceBundleSha256 = "c".repeat(64),
            changedPdfCount = 16,
            estimatedTransferBytes = 1_024,
            sourceIndexChanged = true,
            sourceBundleChanged = true,

            postTerminalBuildRefresh = true,
        )

        val ordinaryRefresh = terminalPlan.copy(postTerminalBuildRefresh = false, artifactOnlyRefresh = true)
        assertEquals(TerminalArtifactResumeAction.REPLAN, terminalArtifactResumeAction(ordinaryRefresh, null))
        assertEquals(TerminalArtifactResumeAction.REPLAN, terminalArtifactResumeAction(terminalPlan, null))
        assertEquals(androidx.work.NetworkType.CONNECTED, artifactRequiredNetworkType(ordinaryRefresh, true))
        assertEquals(androidx.work.NetworkType.UNMETERED,
            artifactRequiredNetworkType(ordinaryRefresh.copy(estimatedTransferBytes = 50_000_000), true))
        val state = ArtifactSyncStateStore(java.io.File(kotlin.io.path.createTempDirectory().toFile(), "plan.json"))
        state.publish(ordinaryRefresh)
        assertEquals(true, state.load()?.artifactOnlyRefresh)
        assertEquals(false, state.load()?.postTerminalBuildRefresh)

    }

    @Test
    fun sourceConflictProducesTerminalNeedsAttentionLogInsteadOfSynced() {
        val mutation = PendingSourceMutation(
            requestId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = "docs-template/tex/source/main.tex",
            pdfFilename = "template.pdf",
            state = SourceMutationState.CONFLICT,
            error = "TeX conflict in docs-template/tex/source/main.tex",
            conflictId = "conflict-1",
        )

        assertEquals("Changes need attention", SOURCE_ATTENTION_MESSAGE)
        assertEquals(
            SyncRunPhase.FAILED,
            syncRunPhase(SyncOperationStore.STATUS_ATTENTION, sourceAttentionEvent(listOf(mutation))),
        )
        assertTrue(sourceAttentionEvent(listOf(mutation)).startsWith("Changes need attention."))
        assertTrue(sourceAttentionEvent(listOf(mutation)).contains(mutation.sourceFile))
        assertFalse(isPendingSyncOperationStatus(SyncOperationStore.STATUS_ATTENTION))
        assertFalse(sourceMutationsAllowRender(listOf(mutation)))
        assertTrue(sourceMutationsAllowRender(emptyList()))
    }

    @Test
    fun conflictAppearingDuringArtifactTransferWinsOverTerminalSynced() {
        val conflict = PendingSourceMutation(
            requestId = "3daaf7df-2e77-47cf-8e49-51ab5a761191",
            sourceFile = "docs-template/tex/source/main.tex",
            pdfFilename = "template.pdf",
            state = SourceMutationState.CONFLICT,
            error = "TeX conflict in docs-template/tex/source/main.tex",
            conflictId = "conflict-1",
        )

        assertEquals(
            ArtifactCompletionOutcome.SYNCED,
            artifactCompletionOutcome(sourceAttention = emptyList()),
        )
        assertEquals(
            ArtifactCompletionOutcome.ATTENTION,
            artifactCompletionOutcome(sourceAttention = listOf(conflict)),
        )
    }

    @Test
    fun failuresUseStableUserFacingMessages() {
        assertEquals(
            SyncOperationStore.USER_MESSAGE_TAILSCALE_OFF,
            userFacingSyncFailureMessage(
                UnknownHostException("server.example.ts.net"),
                requiredVpnConnected = false,
            ),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_CANT_CONNECT,
            userFacingSyncFailureMessage(ConnectException("connection refused")),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_REJECTED,
            userFacingSyncFailureMessage(org.tbend.thoughts.sdk.ServerException(503, "unavailable")),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_SYNC_FAILED,
            userFacingSyncFailureMessage(IllegalStateException("bad local state")),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_TEX_SAVED,
            userFacingSyncFailureMessage(
                terminalBuildException(TerminalPdfBuildStatus.FAILED),
            ),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_PDF_BUILD_ATTENTION_TEX_SAVED,
            userFacingSyncFailureMessage(
                terminalBuildException(TerminalPdfBuildStatus.ATTENTION),
            ),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_PDF_BUILD_CANCELLED_TEX_SAVED,
            userFacingSyncFailureMessage(
                java.io.IOException(
                    "wrapped",
                    terminalBuildException(TerminalPdfBuildStatus.CANCELLED),
                ),
            ),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_TAILSCALE_OFF,
            interruptedSyncFailureMessage(requiredVpnConnected = false),
        )
        assertEquals(
            SyncOperationStore.USER_MESSAGE_INTERRUPTED,
            interruptedSyncFailureMessage(requiredVpnConnected = true),
        )
    }

    private fun terminalBuildException(status: TerminalPdfBuildStatus) =
        TerminalPdfBuildException(
            listOf(
                TerminalPdfBuildFailure(
                    buildId = "build-1",
                    status = status,
                    detail = "exact Fedora compiler detail",
                ),
            ),
        )

    @Test
    fun startupReconciliationRestoresPreviousGenerationWithoutNetworkWork() {
        val parent = Files.createTempDirectory("thoughts-library-reconcile").toFile()
        try {
            val root = File(parent, "current_pdfs")
            val previous = File(parent, ".thoughts-library-previous").apply { mkdirs() }
            File(previous, "paper.pdf").writeText("previous")
            val journal = File(parent, ".thoughts-library-activation.json").apply {
                writeText("{\"schema_version\":1,\"targeted\":true}")
            }

            reconcileInterruptedLibraryActivation(root)

            assertTrue(File(root, "paper.pdf").isFile)
            assertFalse(previous.exists())
            assertFalse(journal.exists())
        } finally {
            parent.deleteRecursively()
        }
    }
}
