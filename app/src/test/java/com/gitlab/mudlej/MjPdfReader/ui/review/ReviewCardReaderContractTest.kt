// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewCardReaderContractTest {

    @Test
    fun pageTargetUsesZeroBasedIndexWithoutChangingValidPages() {
        assertEquals(0, boundedReviewBrowserPageIndex(0, 12))
        assertEquals(7, boundedReviewBrowserPageIndex(7, 12))
        assertEquals(11, boundedReviewBrowserPageIndex(11, 12))
    }

    @Test
    fun pageTargetClampsStaleBrowserMetadataToLoadedDocument() {
        assertEquals(0, boundedReviewBrowserPageIndex(-4, 12))
        assertEquals(11, boundedReviewBrowserPageIndex(20, 12))
    }

    @Test
    fun pageTargetWaitsUntilTheDocumentHasPages() {
        assertNull(boundedReviewBrowserPageIndex(3, 0))
    }

    @Test
    fun browserSingleCardSessionReturnsAfterTheSuccessfulRating() {
        assertEquals(true, finishAfterReviewBrowserRating(singleCardSession = true))
    }

    @Test
    fun existingReviewQueuesContinueAfterTheSuccessfulRating() {
        assertEquals(false, finishAfterReviewBrowserRating(singleCardSession = false))
    }
}
