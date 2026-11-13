// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutation
import com.gitlab.mudlej.MjPdfReader.data.sync.carriesMeteredApproval
import com.gitlab.mudlej.MjPdfReader.data.sync.libraryUploadBudgetBytes
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferPlan
import com.gitlab.mudlej.MjPdfReader.data.sync.userFacingSyncFailureMessage
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutationConflictException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFirstLibraryActionsTest {
    private fun change(type: LibraryMutation.Type, filename: String, state: LibraryMutation.State, detail: String = "") =
        LibraryMutation(
            requestId = "11111111-1111-4111-8111-${filename.hashCode().toUInt().toString().padStart(12, '0').take(12)}",
            type = type,
            filename = filename,
            sha256 = "a".repeat(64),
            createdAtEpochMillis = 5,
            state = state,
            failureDetail = detail,
        )

    @Test
    fun queuedAndRefusedChangesArePendingButDeliveredOnesAreNot() {
        val pending = pendingLibraryChanges(
            listOf(
                change(LibraryMutation.Type.ADD, "added.pdf", LibraryMutation.State.PENDING),
                change(LibraryMutation.Type.RENAME, "renamed.pdf", LibraryMutation.State.PENDING),
                change(LibraryMutation.Type.ARCHIVE, "archived.pdf", LibraryMutation.State.PENDING),
                change(LibraryMutation.Type.RENAME, "sent.pdf", LibraryMutation.State.DELIVERED),
                change(LibraryMutation.Type.ADD, "clash.pdf", LibraryMutation.State.CONFLICT, "another PDF already uses this filename there"),
            ),
        )
        assertEquals(listOf("added.pdf", "renamed.pdf", "archived.pdf", "clash.pdf"), pending.map { it.filename })
        assertEquals(
            listOf(
                R.string.document_status_pending_library_add,
                R.string.document_status_pending_library_rename,
                R.string.document_status_pending_library_archive,
                R.string.document_status_pending_library_add,
            ),
            pending.map { it.label },
        )
        assertNull(pending.first().failure)
        assertEquals("Not added to the shared Library: another PDF already uses this filename there", pending.last().failure)
    }

    /** Import, rename, archive and remove change the phone's Library only; the user starts Sync. */
    @Test
    fun libraryActionsNeitherCallFedoraNorStartASync() {
        listOf(
            "src/main/java/com/gitlab/mudlej/MjPdfReader/ui/reader/library/LibraryImportController.kt",
            "src/main/java/com/gitlab/mudlej/MjPdfReader/ui/home/RecordOptionsDialog.kt",
            "src/main/java/com/gitlab/mudlej/MjPdfReader/data/sync/LibraryImportWorker.kt",
        ).forEach { path ->
            val source = File(path).readText()
            assertFalse("$path starts a Sync", source.contains("ThoughtsSyncScheduler"))
            assertFalse("$path calls Fedora directly", source.contains("FedoraRepository"))
            assertFalse("$path calls Fedora directly", source.contains("SyncClient"))
        }
    }

    @Test
    fun mobileDataBudgetFollowsTheSyncTransferRule() {
        assertEquals(Long.MAX_VALUE, libraryUploadBudgetBytes(unmetered = true, meteredApproved = false, thresholdBytes = 10))
        assertEquals(Long.MAX_VALUE, libraryUploadBudgetBytes(unmetered = false, meteredApproved = true, thresholdBytes = 10))
        assertEquals(10, libraryUploadBudgetBytes(unmetered = false, meteredApproved = false, thresholdBytes = 10))
    }

    @Test
    fun approvalCarriesToAPlanThatMovesNoMoreData() {
        fun plan(planId: Char, upload: Long, download: Long, approved: Boolean) = SyncTransferPlan(
            contractVersion = 2, sourceProfile = "reader-v1", includeVoiceMedia = false,
            planId = planId.toString().repeat(64), serverPlanId = "e".repeat(64), serverLibraryRevision = "f".repeat(64),
            remoteObjects = emptyList(), operationId = "22222222-2222-4222-8222-222222222222", targetPdfFilename = null,
            building = false, uploadBytes = upload, downloadBytes = download, thresholdBytes = 100,
            meteredApproved = approved,
        )
        val approved = plan('a', upload = 5_000, download = 300, approved = true)
        assertTrue(carriesMeteredApproval(approved, plan('b', upload = 0, download = 300, approved = false)))
        assertFalse(carriesMeteredApproval(approved, plan('b', upload = 0, download = 301, approved = false)))
        assertFalse(carriesMeteredApproval(plan('a', 5_000, 300, approved = false), plan('b', 0, 0, approved = false)))
    }

    @Test
    fun refusedLibraryChangeIsTheSyncFailureMessage() {
        val message = "“clash.pdf” was not added to the shared Library: another PDF already uses this filename there. It stays on this phone."
        assertEquals(message, userFacingSyncFailureMessage(LibraryMutationConflictException(message)))
    }
}
