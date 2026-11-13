// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentMutationPayload
import com.gitlab.mudlej.MjPdfReader.data.annotation.newNotePayload
import com.gitlab.mudlej.MjPdfReader.data.annotation.projectDocumentComments
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import org.junit.Assert.*
import org.junit.Test

class OverviewLogicalSnapshotTest {
    private val note = newNotePayload("note", "A note.", "2026-09-11T10:00:00Z")
    private val original = OfflineOverviewSourceSnapshot().withLogicalPayloads(listOf(note))

    @Test fun delayedSnapshotCannotHideCreationOrResurrectDeletion() {
        val created = OfflineOverviewSourceSnapshot().retainingNewerLogicalComments(original.logicalComments)
        assertEquals(listOf("note"), visible(created))
        val deletion = logicalCommentMutationPayload(note, "note", true)!!
        val deleted = original.withLogicalPayloads(listOf(deletion))
        val stale = original.retainingNewerLogicalComments(deleted.logicalComments)
        assertTrue(visible(stale).isEmpty())
        assertTrue(stale.logicalComments.single().deleted)
        assertEquals(2L, stale.logicalComments.single().revision)
    }

    @Test fun newerRemoteEditOrRestoreStillWins() {
        val deletion = logicalCommentMutationPayload(note, "note", true)!!
        val deleted = original.withLogicalPayloads(listOf(deletion))
        val restored = logicalCommentMutationPayload(deletion, "note", false, contents = "Restored remotely.")!!
        val remote = original.withLogicalPayloads(listOf(restored))
            .retainingNewerLogicalComments(deleted.logicalComments)
        assertEquals(listOf("note"), visible(remote))
        assertEquals(3L, remote.logicalComments.single().revision)
        assertTrue(remote.logicalComments.single().payloadJson.contains("Restored remotely."))
    }

    private fun visible(snapshot: OfflineOverviewSourceSnapshot) = projectDocumentComments(
        emptyList(), emptyList(), emptyList(), emptySet(), snapshot.logicalComments,
    ).documents.flatMap { it.comments }.map { it.id }
}
