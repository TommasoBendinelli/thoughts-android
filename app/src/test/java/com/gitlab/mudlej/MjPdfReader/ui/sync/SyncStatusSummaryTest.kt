// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import com.gitlab.mudlej.MjPdfReader.data.sync.*
import org.junit.Assert.*
import org.junit.Test
import com.google.gson.JsonParser

class SyncStatusSummaryTest {
    private fun record() = SyncOperationRecord("a", null, SyncOperationKind.SYNC, SyncOperationStore.STATUS_ERROR,
        1, 2, listOf("Trace ID secret; /srv/secret hash"),
        displayMessage = SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED)
    private fun build() = SyncBuildProgress("id", "all", "failed", "failed", 0, 1, "steps", "private debug details",
        failedDocuments = listOf(SyncProjectFailure("paper.pdf", "latex_error /srv/private/main.tex:246"),
            SyncProjectFailure("notes.pdf", "renderer exited 1 log object deadbeef")))

    @Test fun namesOnlyFailedProjectsWithoutLeakingLogs() {
        val view = syncStatusSummary(record(), SavedSyncDetails(builds = listOf(build())))
        assertEquals("PDF build failed", view.title)
        assertTrue(view.message.contains("paper\nA formatting error"))
        assertTrue(view.message.contains("notes\nThe PDF could not be built"))
        listOf("/srv", "deadbeef", "renderer", "Trace ID", "246", "private").forEach {
            assertFalse(view.toString().contains(it))
        }
    }
    @Test fun historicalGenerationEventDoesNotAppearInSyncSummary() {
        val r = record().copy(events = listOf("course-notes: the selected PDF changed before card generation started"))
        val view = syncStatusSummary(r, SavedSyncDetails(builds = listOf(build().copy(
            failedDocuments = listOf(SyncProjectFailure("paper.pdf", "latex_error"))))))
        assertEquals("PDF build failed", view.title)
        assertTrue(view.message.contains("1 PDF failed to build"))
        assertFalse(view.message.contains("Card generation"))
        assertFalse(view.message.contains("selected PDF"))
    }
    @Test fun genericFailureNeverClaimsChangesWereSynced() {
        val view = syncStatusSummary(record().copy(displayMessage = "java.IO /private/error"), SavedSyncDetails())
        assertEquals("Sync failed", view.title)
        assertFalse(view.message.contains("java.IO"))
    }
    @Test fun currentAndSavedOlderBuildFailedMessagesBothMeanTheChangesSynced() {
        assertFalse(SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED.contains("Fedora"))
        listOf(
            SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED,
            "PDF build failed. Your changes are synced with Fedora. The last successful PDF remains available.",
        ).forEach { message ->
            val saved = record().copy(displayMessage = message)
            assertTrue(message, isPdfBuildFailedSyncedDisplayMessage(message))
            assertTrue(message, isTerminalPdfBuildFailure(saved))
            val view = syncStatusSummary(saved, SavedSyncDetails())
            assertEquals(message, "PDF build failed", view.title)
            assertTrue(message, view.message.contains("Your changes are synced"))
        }
    }
    @Test fun unknownProjectIsNotGuessedFromAllBuildTargets() {
        val view = syncStatusSummary(record(), SavedSyncDetails(builds = listOf(build().copy(
            failedDocuments = emptyList(), documents = listOf("healthy.pdf", "other.pdf")))))
        assertFalse(view.message.contains("healthy"))
        assertTrue(view.message.contains("affected project was not recorded"))
    }

    @Test fun savedSingleDocumentFailureRecoversProjectAndReadableReason() {
        val view = syncStatusSummary(record(), SavedSyncDetails(builds = listOf(build().copy(
            failedDocuments = emptyList(), documents = listOf("course-notes.pdf"),
            detail = "renderer exited with 1: strict review correspondence gate failed: proposal:private-id; log object secret",
        ))))
        assertEquals("PDF build failed", view.title)
        assertEquals("course-notes\nA proposed edit could not be matched to its location in the updated PDF.\n\n" +
            "Your changes are synced · 1 PDF failed to build.", view.message)
    }

    @Test fun documentScopeDoesNotTurnSuccessfulBuildsOrCardJobsIntoPdfFailures() {
        val singleDocument = build().copy(failedDocuments = emptyList(), documents = listOf("paper.pdf"))
        val view = syncStatusSummary(record().copy(displayMessage = "", status = SyncOperationStore.STATUS_SYNCED),
            SavedSyncDetails(builds = listOf(singleDocument.copy(serverStatus = "succeeded"),
                singleDocument.copy(target = "paper.pdf", workKind = "review_cards"))))
        assertEquals("Up to date", view.title)
        assertEquals("", view.message)
    }

    @Test fun explicitFailureInventoryTakesPrecedenceOverDocumentScope() {
        val view = syncStatusSummary(record(), SavedSyncDetails(builds = listOf(build().copy(
            documents = listOf("healthy.pdf"),
            failedDocuments = listOf(SyncProjectFailure("failed.pdf", "failed")),
        ))))
        assertTrue(view.message.startsWith("failed\n"))
        assertFalse(view.message.contains("healthy"))
    }

    @Test fun unsafeSavedDocumentDoesNotBecomeAProjectName() {
        val view = syncStatusSummary(record(), SavedSyncDetails(builds = listOf(build().copy(
            failedDocuments = emptyList(), documents = listOf("../private.pdf"),
        ))))
        assertFalse(view.message.contains("private"))
        assertTrue(view.message.contains("affected project was not recorded"))
    }
    @Test fun successfulBuildAloneDoesNotClaimSyncCompletion() {
        val view = syncStatusSummary(record().copy(kind = SyncOperationKind.BUILD,
            displayMessage = "", status = SyncOperationStore.STATUS_SYNCED), SavedSyncDetails())
        assertEquals("Build complete", view.title)
        assertTrue(view.message.contains("Sync status is tracked separately"))
    }
    @Test fun importedBuildsUseOwningProjectAndCountItOnce() {
        val projects = JsonParser.parseString("""[{"id":"models-id","name":"Models","documents":[
            {"filename":"import-first.pdf","name":"OLMo 3"},
            {"filename":"import-second.pdf","name":"Qwen 3"}]}]""").asJsonArray
        val details = SavedSyncDetails(builds = listOf(build().copy(failedDocuments = listOf(
            SyncProjectFailure("import-first.pdf", "latex_error"),
            SyncProjectFailure("import-second.pdf", "unresolved_tex_link: docs-concepts/qwen3.tex is not selected")))))
        val summary = syncStatusSummary(record(), details, projects)
        assertEquals("PDF build failed", summary.title)
        assertTrue(summary.message.contains("Models\nDocuments: OLMo 3"))
        assertTrue(summary.message.contains("Models\nDocuments: Qwen 3"))
        assertTrue(summary.message.contains("Check the destination in Manage documents"))
        assertFalse(summary.message.contains("import-first"))
        assertFalse(summary.message.contains("docs-concepts"))
    }

    @Test fun unownedImportDoesNotInventAProject() {
        val details = SavedSyncDetails(builds = listOf(build().copy(failedDocuments = listOf(
            SyncProjectFailure("import-opaque.pdf", "error")))))
        val summary = syncStatusSummary(record(), details)
        assertEquals("PDF build failed", summary.title)
        assertTrue(summary.message.startsWith("Imported documents\n"))
        assertFalse(summary.message.contains("import-opaque"))
    }

}
