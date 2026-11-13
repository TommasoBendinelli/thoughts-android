// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.LibraryBuildFailure
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentStatusPresentationTest {
    @Test
    fun actionableStatusesExposeOnlyTheirExactAction() {
        val expected = mapOf(
            PhoneRenderStage.SYNC_NEEDED to (DocumentStatusCode.SYNC_NEEDED to DocumentStatusAction.SYNC_NOW),
            PhoneRenderStage.SOURCE_SYNC_NEEDED to (DocumentStatusCode.SYNC_NEEDED to DocumentStatusAction.SYNC_NOW),
            PhoneRenderStage.SYNC_PENDING to (DocumentStatusCode.SYNC_NEEDED to DocumentStatusAction.SYNC_NOW),
            PhoneRenderStage.OUT_OF_SYNC to (DocumentStatusCode.SYNC_NEEDED to DocumentStatusAction.SYNC_NOW),
            PhoneRenderStage.FAILED to (DocumentStatusCode.ERROR to DocumentStatusAction.SYNC_NOW),
        )

        expected.forEach { (stage, statusAndAction) ->
            val presentation = documentStatusPresentation(stage)
            assertEquals(statusAndAction.first, presentation.code)
            assertEquals(statusAndAction.second, presentation.action)
            assertTrue(presentation.showOnDocumentRow)
        }
    }

    @Test
    fun everyStatusActionDispatchesToOneExactCommand() {
        val expected = mapOf(
            DocumentStatusAction.NONE to DocumentStatusCommand.NONE,
            DocumentStatusAction.SYNC_NOW to DocumentStatusCommand.REQUEST_MANUAL_SYNC,
            DocumentStatusAction.SHOW_ERRORS to DocumentStatusCommand.SHOW_ERRORS,
        )

        assertEquals(DocumentStatusAction.entries.toSet(), expected.keys)
        expected.forEach { (action, command) ->
            assertEquals(command, documentStatusCommand(action))
        }
    }

    @Test
    fun actionableStatusDialogsUseCancelAsTheirOnlyDismissAction() {
        assertEquals(R.string.ok, documentStatusDismissLabel(DocumentStatusAction.NONE))
        listOf(
            DocumentStatusAction.SYNC_NOW,
            DocumentStatusAction.SHOW_ERRORS,
        ).forEach { action ->
            assertEquals(R.string.cancel, documentStatusDismissLabel(action))
        }
    }

    @Test
    fun syncedDocumentsStayOutOfRowsBecauseTheSummaryOwnsSuccess() {
        val unverified = documentStatusPresentation(PhoneRenderStage.UPDATED)
        val verified = documentStatusPresentation(PhoneRenderStage.UPDATED, verifiedAtEpochMillis = 123)

        assertFalse(unverified.showOnDocumentRow)
        assertFalse(unverified.compactVerifiedTick)
        assertFalse(verified.showOnDocumentRow)
        assertFalse(verified.compactVerifiedTick)
        assertEquals(DocumentStatusAction.NONE, verified.action)
    }

    @Test
    fun activeWorkAppearsOnAffectedDocumentRows() {
        listOf(
            PhoneRenderStage.QUEUED,
            PhoneRenderStage.FEDORA_QUEUED,
            PhoneRenderStage.CHECKING,
            PhoneRenderStage.REBUILDING,
            PhoneRenderStage.WAITING_FOR_SYNC,
        ).forEach { stage ->
            val presentation = documentStatusPresentation(stage)
            assertTrue(presentation.showOnDocumentRow)
            if (stage == PhoneRenderStage.REBUILDING) {
                assertEquals(DocumentStatusCode.SYNCING, presentation.code)
            }
        }
    }

    @Test
    fun eachActivatedPdfCanBeVerifiedIndependently() {
        val snapshot = SourceLinkedPdfIndexSnapshot(
            libraryPdfSha256ByFilename = mapOf("paper.pdf" to "a".repeat(64)),
            activatedPdfSha256ByFilename = mapOf("paper.pdf" to "a".repeat(64)),
            libraryRevision = "b".repeat(64),
            generationCurrent = true,
        )

        val status = verifiedLibraryDocumentStatus("paper.pdf", snapshot, 123)

        assertEquals(PhoneRenderStage.UPDATED, status?.stage)
        assertEquals(123L, status?.verifiedAtEpochMillis)
        assertEquals(null, verifiedLibraryDocumentStatus("missing.pdf", snapshot, 123))
        assertEquals(null, verifiedLibraryDocumentStatus("paper.pdf", snapshot, 0))
        assertEquals(
            null,
            verifiedLibraryDocumentStatus(
                "paper.pdf",
                snapshot.copy(
                    staleBuildsByFilename = mapOf(
                        "paper.pdf" to LibraryBuildFailure("build", "failed"),
                    ),
                ),
                123,
            ),
        )
    }
}
