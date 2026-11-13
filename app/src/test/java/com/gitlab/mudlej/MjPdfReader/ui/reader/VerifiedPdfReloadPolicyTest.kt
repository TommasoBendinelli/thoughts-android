// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedPdfReloadPolicyTest {

    @Test
    fun everyActivatedGenerationPathCanTriggerReconciliation() {
        assertTrue(isRelevantSourceLinkedPdfActivationPath("paper.pdf", "paper.pdf"))
        assertTrue(
            isRelevantSourceLinkedPdfActivationPath(
                SourceLinkedPdfIndex.INDEX_FILENAME,
                "paper.pdf",
            ),
        )
        assertTrue(
            isRelevantSourceLinkedPdfActivationPath(
                SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME,
                "paper.pdf",
            ),
        )
        assertTrue(
            isRelevantSourceLinkedPdfActivationPath(
                SourceLinkedPdfIndex.GENERATION_FILENAME,
                "paper.pdf",
            ),
        )
        assertFalse(isRelevantSourceLinkedPdfActivationPath("other.pdf", "paper.pdf"))
    }

    @Test
    fun transientTextSelectionDoesNotBlockARefresh() {
        assertFalse(
            shouldDeferVerifiedPdfReload(
                hasUnsavedAnnotations = false,
                isSavingAnnotations = false,
                isPdfCommentSheetShowing = false,
                isManualReviewEditorShowing = false,
            ),
        )
    }

    @Test
    fun editsAndOpenEditorsDelayRefreshUntilSafe() {
        assertTrue(shouldDeferVerifiedPdfReload(true, false, false, false))
        assertTrue(shouldDeferVerifiedPdfReload(false, true, false, false))
        assertTrue(shouldDeferVerifiedPdfReload(false, false, true, false))
        assertTrue(shouldDeferVerifiedPdfReload(false, false, false, true))
    }
}
