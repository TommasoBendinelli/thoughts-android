// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySyncStatusPresentationTest {

    @Test
    fun topIconStatesKeepFailureAndActiveSyncDistinct() {
        assertEquals(
            LibrarySyncIconState.CURRENT,
            LibrarySyncStatusPresentation("Current", "Last synced").iconState(),
        )
        assertEquals(
            LibrarySyncIconState.PENDING,
            LibrarySyncStatusPresentation(
                "2 documents need sync",
                "Last synced",
                hasPendingChanges = true,
            ).iconState(),
        )
        assertEquals(
            LibrarySyncIconState.SYNCING,
            LibrarySyncStatusPresentation(
                "Syncing",
                "9 seconds elapsed",
                isSyncing = true,
            ).iconState(),
        )
        assertEquals(
            LibrarySyncIconState.ERROR,
            LibrarySyncStatusPresentation(
                "No pending changes",
                "Last sync failed",
                hasSyncFailure = true,
                isSyncing = true,
            ).iconState(),
        )
    }

    @Test fun pendingChangesDoNotHideBuildFailureOrClaimToBeSynced() {
        val record = com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord(
            "a", null, com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind.SYNC,
            com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore.STATUS_ERROR, 1, 2, emptyList(),
            displayMessage = com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore.USER_MESSAGE_PDF_BUILD_FAILED_SYNCED)
        assertEquals("PDF build failed", syncFailureLabel(record, 0, false))
        assertEquals("PDF build failed", syncFailureLabel(record, 1, false))
        assertEquals("Sync failed", syncFailureLabel(record, 1, true))
        assertEquals("PDF build failed", syncFailureLabel(record.copy(kind = com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind.BUILD), 0, false))
    }

    @Test fun mergeNeededOutranksFailuresAndOffersOnlyTheMerge() {
        assertEquals(null, mergeNeededStatus(0))
        val status = mergeNeededStatus(2)!!
        assertEquals("Merge needed", status.label)
        assertEquals("2 source files need a merge", status.timestamp)
        assertEquals(true, status.needsMerge)
        assertEquals(LibrarySyncIconState.ERROR, status.iconState())
        assertEquals("1 source file needs a merge", SyncStatusLabels.sourceFilesNeedMerge(1))
        assertEquals("Show merge", SyncStatusLabels.SHOW_MERGE)
    }

    @Test fun eachSourceFileNeedingAMergeIsCountedOnce() {
        fun mutation(path: String, state: com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState) =
            com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation("r-$path-$state", path, "paper.pdf", state)
        val files = com.gitlab.mudlej.MjPdfReader.ui.source.sourceFilesNeedingMerge(
            listOf(
                mutation("tex/intro.tex", com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState.CONFLICT),
                mutation("tex/method.tex", com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState.FAILED),
            ),
            listOf("tex/results.tex", "tex/intro.tex"),
        )
        assertEquals(listOf("tex/intro.tex", "tex/results.tex"), files)
    }

    @Test
    fun refusedChangesAloneAreNotASyncFailure() {
        val status = refusedChangesStatus(refusedChangeCount = 1, hasOtherBlockedLocalChanges = false, lastSync = "Last sync today")
        assertEquals(SyncStatusLabels.CHANGE_REFUSED, status?.label)
        assertEquals("1 change was refused by the server · Last sync today", status?.timestamp)
        // The issue mark stays on the Sync button until the user has retried or discarded the change.
        assertEquals(LibrarySyncIconState.ERROR, status?.iconState())
        assertEquals(
            "3 changes were refused by the server · Last sync today",
            refusedChangesStatus(3, hasOtherBlockedLocalChanges = false, lastSync = "Last sync today")?.timestamp,
        )
    }

    @Test
    fun anotherBlockedChangeKeepsTheSyncFailedStatus() {
        assertEquals(null, refusedChangesStatus(2, hasOtherBlockedLocalChanges = true, lastSync = "Last sync today"))
        assertEquals(null, refusedChangesStatus(0, hasOtherBlockedLocalChanges = false, lastSync = "Last sync today"))
    }
}
