// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMergeVersions
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceMergeResolutionPresentationTest {
    private val versions = SourceMergeVersions("docs-paper/main.tex", "phone\n", "a".repeat(64), "synced\n", "b".repeat(64))

    private fun mutation(state: SourceMutationState) = PendingSourceMutation(
        requestId = "request", sourceFile = versions.sourceFile, pdfFilename = "paper.pdf", state = state,
    )

    @Test
    fun mergeFileIsResolvableUnlessSyncAlreadyHandlesThePhoneEdit() {
        assertEquals(SourceMergeFileState.RESOLVABLE, sourceMergeFileState(versions, emptyList()))
        // Only queued or failed: the phone edit can still be withdrawn or rebased.
        assertEquals(SourceMergeFileState.RESOLVABLE, sourceMergeFileState(versions, listOf(mutation(SourceMutationState.QUEUED))))
        assertEquals(SourceMergeFileState.RESOLVABLE, sourceMergeFileState(versions, listOf(mutation(SourceMutationState.FAILED))))
        assertEquals(SourceMergeFileState.SYNC_SENDING, sourceMergeFileState(versions, listOf(mutation(SourceMutationState.DELIVERING))))
        assertEquals(SourceMergeFileState.SYNC_SENDING,
            sourceMergeFileState(versions, listOf(mutation(SourceMutationState.RESOLUTION_QUEUED))))
        assertEquals(SourceMergeFileState.CONFLICT, sourceMergeFileState(versions, listOf(mutation(SourceMutationState.CONFLICT))))
        assertEquals(SourceMergeFileState.UNREADABLE, sourceMergeFileState(null, emptyList()))
    }

    @Test
    fun choicesAreExplicitAndConfirmedWithTheirOwnWording() {
        assertEquals(listOf(SourceMergeChoice.KEEP_PHONE, SourceMergeChoice.USE_SYNCED), SourceMergeChoice.entries.toList())
        assertEquals(R.string.source_merge_keep_phone, SourceMergeChoice.KEEP_PHONE.label)
        assertEquals(R.string.source_merge_keep_phone_message, SourceMergeChoice.KEEP_PHONE.confirmationMessage)
        assertEquals(R.string.source_merge_use_synced, SourceMergeChoice.USE_SYNCED.label)
        assertEquals(R.string.source_merge_use_synced_message, SourceMergeChoice.USE_SYNCED.confirmationMessage)
    }

    @Test
    fun differingLinesIgnoreTheVersionsCommonStartAndEnd() {
        assertEquals(1, differingLineCount("a\nb\nc\n", "a\nB\nc\n"))
        assertEquals(2, differingLineCount("a\nb\nc\n", "a\nx\ny\nc\n"))
        assertEquals(3, differingLineCount("a\nb\nc\nd\n", "a\nB\nc\nD\n"))
        assertEquals(1, differingLineCount("a", "a\n"))
        assertEquals(0, differingLineCount("same\n", "same\n"))
    }
}
