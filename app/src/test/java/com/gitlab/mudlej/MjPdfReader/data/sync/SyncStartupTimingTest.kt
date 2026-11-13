// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.*
import org.junit.Test

class SyncStartupTimingTest {
    @Test fun measuresSameBootElapsedTime() {
        assertEquals(125L, syncStartupElapsedNanos(100, 225, 3, 3))
    }
    @Test fun rebootMissingIdentityAndInvalidClocksCannotProduceADuration() {
        assertNull(syncStartupElapsedNanos(100, 225, 2, 3))
        assertNull(syncStartupElapsedNanos(100, 225, -1, -1))
        assertNull(syncStartupElapsedNanos(0, 225, 3, 3))
        assertNull(syncStartupElapsedNanos(300, 225, 3, 3))
    }
}
