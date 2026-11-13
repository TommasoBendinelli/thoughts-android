// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenControllerTest {

    @Test
    fun screenStaysAwakeForFullscreenOrExplicitPreference() {
        assertFalse(
            shouldKeepReaderScreenOn(
                preferenceEnabled = false,
                isFullscreen = false,
            )
        )
        assertTrue(
            shouldKeepReaderScreenOn(
                preferenceEnabled = true,
                isFullscreen = false,
            )
        )
        assertTrue(
            shouldKeepReaderScreenOn(
                preferenceEnabled = false,
                isFullscreen = true,
            )
        )
        assertTrue(
            shouldKeepReaderScreenOn(
                preferenceEnabled = true,
                isFullscreen = true,
            )
        )
    }
}
