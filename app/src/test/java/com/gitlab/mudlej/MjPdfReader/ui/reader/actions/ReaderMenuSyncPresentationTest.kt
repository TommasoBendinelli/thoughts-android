// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMenuSyncPresentationTest {

    @Test
    fun everyDocumentOffersOneTapSyncWhileIdle() {
        val presentation = readerSyncActionPresentation(
            operationActive = false,
            startRequested = false,
        )

        assertTrue(presentation.visible)
        assertTrue(presentation.canStart)
        assertFalse(presentation.inProgress)
    }

    @Test
    fun startingOrRunningSyncShowsProgressAndBlocksDuplicateTaps() {
        listOf(
            readerSyncActionPresentation(
                operationActive = false,
                startRequested = true,
            ),
            readerSyncActionPresentation(
                operationActive = true,
                startRequested = false,
            ),
        ).forEach { presentation ->
            assertTrue(presentation.visible)
            assertTrue(presentation.inProgress)
            assertFalse(presentation.canStart)
        }
    }
}
