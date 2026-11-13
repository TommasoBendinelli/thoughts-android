// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentAnchorStatusControllerTest {

    @Test
    fun unmanagedDocumentsHideTheStatus() {
        assertEquals(
            DocumentAnchorState.HIDDEN,
            documentAnchorState(
                managed = false,
                pending = true,
                unanchoredComments = 4,
            ),
        )
    }

    @Test
    fun pendingTakesPrecedenceOverCurrentSidecarStatus() {
        assertEquals(
            DocumentAnchorState.PENDING,
            documentAnchorState(
                managed = true,
                pending = true,
                unanchoredComments = 1,
            ),
        )
    }

    @Test
    fun managedDocumentsDistinguishHealthyAndWarningStates() {
        assertEquals(
            DocumentAnchorState.HEALTHY,
            documentAnchorState(
                managed = true,
                pending = false,
                unanchoredComments = 0,
            ),
        )
        assertEquals(
            DocumentAnchorState.WARNING,
            documentAnchorState(
                managed = true,
                pending = false,
                unanchoredComments = 1,
            ),
        )
    }

    @Test
    fun readerIssuesTakePrecedenceOverAnchorStateAndRemainVisibleForUnmanagedPdfs() {
        assertEquals(
            DocumentAnchorState.WARNING,
            documentAnchorState(
                managed = true,
                pending = true,
                unanchoredComments = 0,
                hasReaderIssues = true,
            ),
        )
        assertEquals(
            DocumentAnchorState.WARNING,
            documentAnchorState(
                managed = false,
                pending = false,
                unanchoredComments = 0,
                hasReaderIssues = true,
            ),
        )
    }

    @Test
    fun readerIssueRegistryTracksIndependentProblemsAndClearsThem() {
        val registry = CommentIssueRegistry()
        val persistence = CommentReaderIssue(
            CommentReaderIssueKind.PERSISTENCE,
            "Save failed",
            listOf("Disk error"),
        )
        val restore = CommentReaderIssue(
            CommentReaderIssueKind.RESTORE,
            "Display incomplete",
            listOf("Reply missing"),
        )

        registry.update(CommentReaderIssueKind.PERSISTENCE, persistence)
        registry.update(CommentReaderIssueKind.RESTORE, restore)
        assertEquals(true, registry.hasIssues)
        assertEquals(listOf(persistence, restore), registry.snapshot())

        registry.update(CommentReaderIssueKind.PERSISTENCE, null)
        assertEquals(listOf(restore), registry.snapshot())

        registry.clear()
        assertEquals(false, registry.hasIssues)
        assertEquals(emptyList<CommentReaderIssue>(), registry.snapshot())
    }
}
