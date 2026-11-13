// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewFilterDropdownTest {
    @Test
    fun contentHashesInFileNamesAreShortened() {
        assertEquals(
            "517f9b9c…-Paper-Conference",
            compactHashRuns("517f9b9c227b9dd51dba4560f2714e5d-Paper-Conference"),
        )
    }

    @Test
    fun ordinaryNamesAndShortNumbersAreKept() {
        assertEquals("2402.03300v3", compactHashRuns("2402.03300v3"))
        assertEquals("Release RL", compactHashRuns("Release RL"))
    }
}
