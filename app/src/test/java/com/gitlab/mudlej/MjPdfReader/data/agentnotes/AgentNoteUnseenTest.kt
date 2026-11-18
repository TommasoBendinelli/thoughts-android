// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class AgentNoteUnseenTest {
    @Test fun notesArriveAndLeaveWhenOpenedOrGone() {
        val arrived = unseenAfter(emptySet()) { it + listOf("a", "b", "") }
        // A blank ID never gets a dot.
        assertEquals(setOf("a", "b"), arrived)
        assertEquals(setOf("b"), unseenAfter(arrived) { it - "a" })
        // Only notes still stored keep their dot.
        assertEquals(setOf("b"), unseenAfter(arrived) { it intersect setOf("b", "c") })
    }

    @Test fun theStoredSetIsANewOne() {
        val before = setOf("a")
        val after = unseenAfter(before) { it }
        assertEquals(before, after)
        assertNotSame(before, after)
    }
}
