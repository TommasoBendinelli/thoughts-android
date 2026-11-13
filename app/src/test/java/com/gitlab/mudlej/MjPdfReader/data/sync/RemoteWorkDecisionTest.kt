// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkDecisionTest {
    private val a = "10/" + "a".repeat(64) + "/7"
    private val b = "11/" + "b".repeat(64) + "/8"
    private val c = "12/" + "c".repeat(64) + "/8"

    @Test
    fun firstCheckRecordsTheBaselineAndSyncsOnlyWhenOperationsAreAhead() {
        val quiet = remoteWorkDecision(RemoteWork(a, cursorBehind = false), syncJustFinished = false, baseline = null, triggered = null)
        assertFalse(quiet.sync)
        assertEquals(a, quiet.baseline)
        val behind = remoteWorkDecision(RemoteWork(a, cursorBehind = true), syncJustFinished = false, baseline = null, triggered = null)
        assertTrue(behind.sync)
        assertEquals(a, behind.triggered)
    }

    @Test
    fun aNewServerStateStartsOneSyncAndNeverTwo() {
        val first = remoteWorkDecision(RemoteWork(b, cursorBehind = false), syncJustFinished = false, baseline = a, triggered = null)
        assertTrue(first.sync)
        assertEquals(a, first.baseline)
        assertEquals(b, first.triggered)
        // The automatic Sync failed and nothing changed on Fedora: no second Sync for that state.
        val again = remoteWorkDecision(RemoteWork(b, cursorBehind = true), syncJustFinished = false, baseline = a, triggered = b)
        assertFalse(again.sync)
        assertFalse(remoteWorkDecision(RemoteWork(a, cursorBehind = false), syncJustFinished = false, baseline = a, triggered = b).sync)
        // Fedora moved on again: one more.
        assertTrue(remoteWorkDecision(RemoteWork(c, cursorBehind = false), syncJustFinished = false, baseline = a, triggered = b).sync)
    }

    @Test
    fun aFinishedSyncMovesTheBaselineSoItsOwnUploadsStartNoSync() {
        val after = remoteWorkDecision(RemoteWork(b, cursorBehind = false), syncJustFinished = true, baseline = a, triggered = null)
        assertFalse(after.sync)
        assertEquals(b, after.baseline)
        // Operations that arrived during that Sync are still pulled, once.
        val behind = remoteWorkDecision(RemoteWork(c, cursorBehind = true), syncJustFinished = true, baseline = a, triggered = null)
        assertTrue(behind.sync)
        assertEquals(c, behind.baseline)
        assertFalse(remoteWorkDecision(RemoteWork(c, cursorBehind = true), syncJustFinished = true, baseline = c, triggered = c).sync)
    }
}
