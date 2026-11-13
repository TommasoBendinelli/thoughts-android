// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCommandPolicyTest {
    @Test
    fun forceRebuildDoesNotReuseAnOrdinarySyncOrOverlapABuild() {
        for (kind in listOf(SyncOperationKind.SYNC, SyncOperationKind.BUILD)) {
            val pending = record("active", null, kind, 20)
            val result = selectSyncCommandDisposition(listOf(pending), null, forceRebuild = true)
            assertNull(result.equivalent)
            assertEquals("active", result.blocker?.id)
        }
        val result = selectSyncCommandDisposition(emptyList(), null, forceRebuild = true)
        assertNull(result.equivalent)
        assertNull(result.blocker)
    }

    @Test
    fun globalCommandReusesOnlyGlobalSync() {
        val target = record("target", "paper.pdf", SyncOperationKind.SYNC, 20)
        val global = record("global", null, SyncOperationKind.SYNC, 10)

        val result = selectSyncCommandDisposition(listOf(target, global), null)

        assertEquals("global", result.equivalent?.id)
        assertNull(result.blocker)
    }

    @Test
    fun targetedCommandIsCoveredByGlobalSync() {
        val global = record("global", null, SyncOperationKind.SYNC, 20)

        val result = selectSyncCommandDisposition(listOf(global), "paper.pdf")

        assertEquals("global", result.equivalent?.id)
    }

    @Test
    fun differentTargetIsAnExplicitBlocker() {
        val other = record("other", "other.pdf", SyncOperationKind.SYNC, 20)

        val result = selectSyncCommandDisposition(listOf(other), "paper.pdf")

        assertNull(result.equivalent)
        assertEquals("other", result.blocker?.id)
    }

    @Test
    fun buildIsNotMistakenForEquivalentSync() {
        val build = record("build", null, SyncOperationKind.BUILD, 20)

        val result = selectSyncCommandDisposition(listOf(build), null)

        assertNull(result.equivalent)
        assertEquals("build", result.blocker?.id)
    }

    @Test
    fun terminalRecordsDoNotBlock() {
        val complete = record(
            "complete",
            null,
            SyncOperationKind.SYNC,
            20,
            SyncOperationStore.STATUS_SYNCED,
        )

        val result = selectSyncCommandDisposition(listOf(complete), null)

        assertNull(result.equivalent)
        assertNull(result.blocker)
    }

    @Test
    fun targetValidationAcceptsOnlyPdfBasenames() {
        assertTrue(isValidSyncCommandTarget(null))
        assertTrue(isValidSyncCommandTarget("paper.pdf"))
        assertTrue(isValidSyncCommandTarget("PAPER.PDF"))
        assertFalse(isValidSyncCommandTarget(""))
        assertFalse(isValidSyncCommandTarget("paper.tex"))
        assertFalse(isValidSyncCommandTarget("../paper.pdf"))
        assertFalse(isValidSyncCommandTarget("folder/paper.pdf"))
    }

    @Test
    fun wireStateCollapsesInternalPhases() {
        assertEquals("queued", syncCommandState(record("a", null, phase = SyncRunPhase.QUEUED)))
        assertEquals("waiting", syncCommandState(record("b", null, phase = SyncRunPhase.WAITING_USER)))
        assertEquals("building", syncCommandState(record("c", null, phase = SyncRunPhase.BUILDING)))
        assertEquals("syncing", syncCommandState(record("d", null, phase = SyncRunPhase.DOWNLOADING)))
        assertEquals("succeeded", syncCommandState(record("e", null, phase = SyncRunPhase.SUCCEEDED)))
        assertEquals("failed", syncCommandState(record("f", null, phase = SyncRunPhase.INTERRUPTED)))
    }

    private fun record(
        id: String,
        target: String?,
        kind: SyncOperationKind = SyncOperationKind.SYNC,
        updatedAt: Long = 1,
        status: String = SyncOperationStore.STATUS_QUEUED,
        phase: String = SyncRunPhase.QUEUED,
    ) = SyncOperationRecord(
        id = id,
        target = target,
        kind = kind,
        status = status,
        startedAtEpochMillis = updatedAt,
        updatedAtEpochMillis = updatedAt,
        events = listOf(status),
        phase = phase,
    )
}
