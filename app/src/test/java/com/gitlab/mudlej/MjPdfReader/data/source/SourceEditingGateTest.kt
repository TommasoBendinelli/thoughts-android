// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEditingGateTest {
    private var now = 1_000_000_000L
    private val second = 1_000_000_000L
    private val gate = SourceEditingGate(clock = { now }, activeWindowNanos = 20 * second)

    @Test
    fun editingMeansAForegroundEditorWithRecentInput() {
        assertFalse(gate.isEditing())
        gate.editorResumed()
        assertTrue(gate.isEditing())
        now += 19 * second
        assertTrue(gate.isEditing())
        now += 2 * second
        assertFalse("an editor left open without input is idle", gate.isEditing())
        gate.noteInput()
        assertTrue(gate.isEditing())
        gate.editorPaused()
        assertFalse("input in the background does not count", gate.isEditing())
        gate.editorPaused()
        gate.editorResumed()
        assertTrue("unbalanced pauses never hide a resumed editor", gate.isEditing())
    }

    @Test
    fun cleanupWaitsForATypingPause() = runBlocking {
        gate.editorResumed()
        var waited = 0L
        val idle = gate.awaitNotEditing(maxWaitNanos = 600 * second) { millis ->
            waited += millis
            now += millis * 1_000_000
            // Keep typing for the first ten seconds of the wait.
            if (waited <= 10_000) gate.noteInput()
        }
        assertTrue(idle)
        assertEquals(30_000L, waited)
    }

    @Test
    fun cleanupGivesUpWaitingAfterTheDeadline() = runBlocking {
        gate.editorResumed()
        var waited = 0L
        val idle = gate.awaitNotEditing(maxWaitNanos = 60 * second) { millis ->
            waited += millis
            now += millis * 1_000_000
            gate.noteInput()
        }
        assertFalse(idle)
        assertEquals(60_000L, waited)
    }

    @Test
    fun cleanupYieldsOnlyWhileSomeoneIsTyping() {
        val sleeps = mutableListOf<Long>()
        gate.yieldWhileEditing(sleeps::add)
        gate.editorResumed()
        gate.yieldWhileEditing(sleeps::add)
        gate.editorPaused()
        gate.yieldWhileEditing(sleeps::add)
        assertEquals(1, sleeps.size)
    }
}
