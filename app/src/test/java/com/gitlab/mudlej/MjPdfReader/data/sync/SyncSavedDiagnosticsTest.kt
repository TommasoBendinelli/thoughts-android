// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class SyncSavedDiagnosticsTest {
    @Test fun rawEventsSurviveRestartWithoutTruncationAndRetryHasSeparateArchive() {
        val dir = Files.createTempDirectory("sync-diagnostics-test").toFile()
        try {
            val id = UUID.randomUUID().toString()
            val retry = UUID.randomUUID().toString()
            val longMessage = "x".repeat(20000) + "complete-tail"
            SyncDiagnosticArchive(dir).append(id, "server_event", longMessage)
            SyncDiagnosticArchive(dir).append(retry, "run_started", mapOf("retryOf" to id))
            assertTrue(SyncDiagnosticArchive(dir).read(id).contains(longMessage))
            assertTrue(SyncDiagnosticArchive(dir).read(retry).contains(id))
            assertFalse(SyncDiagnosticArchive(dir).read(id).contains(retry))
        } finally { dir.deleteRecursively() }
    }
    @Test fun offlineBundleContainsCompleteBuildLogAndReportsMissingLogs() {
        val id = UUID.randomUUID().toString()
        val ref = "server-object:" + "a".repeat(64)
        val missing = "server-object:" + "b".repeat(64)
        val r = SyncOperationRecord(id, null, SyncOperationKind.SYNC, "Error", 1, 2, listOf(missing))
        val saved = SavedSyncDetails(builds = listOf(SyncBuildProgress("build", "all", "failed", "", 0, 0, "", "",
            logReference = ref)), rawArchive = "raw-event-tail")
        val output = savedSyncDiagnosticBundle(r, saved) { if (it == ref) "full compiler log\nlast line" else null }
        assertTrue(output.contains("full compiler log\nlast line"))
        assertTrue(output.contains("INCOMPLETE: this build log"))
        assertTrue(output.contains("raw-event-tail"))
    }
    @Test fun retriesFindTheirOriginalBuildReceiptWithoutIncludingOtherProjects() {
        val r = SyncOperationRecord("retry", null, SyncOperationKind.SYNC, "Error", 1, 2,
            listOf("Server PDF build old-build failed"))
        val same = JsonParser.parseString("""{"build_id":"old-build","terminal_operation_id":"original"}""").asJsonObject
        val other = JsonParser.parseString("""{"build_id":"unrelated","terminal_operation_id":"other"}""").asJsonObject
        assertEquals(listOf(same), relevantSyncReceipts(r, listOf(same, other)))
    }
}
