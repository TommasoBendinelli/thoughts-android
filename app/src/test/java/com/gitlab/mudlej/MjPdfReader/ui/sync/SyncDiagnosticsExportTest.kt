// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import com.gitlab.mudlej.MjPdfReader.data.sync.*
import org.junit.Assert.*
import org.junit.Test

class SyncDiagnosticsExportTest {
    @Test fun detailedEventsAreExportedButHiddenFromOrdinaryLog() {
        val record = SyncOperationRecord("trace", null, SyncOperationKind.SYNC, "synced", 1, 2, emptyList(),
            journalEvents = listOf(SyncJournalEvent(1, "diagnostic", null, 2, 2, "phone_diagnostics_v1",
                "diagnostic", "finished", "", "parse_document_manifest", null, null, null, null, "", "{\"duration_ns\":100}")))
        val summary = syncOperationLogDetails(record, "unknown") { it.toString() }
        assertFalse(summary.contains("parse_document_manifest"))
        val export = syncOperationDiagnosticDetails(record, summary)
        assertTrue(export.contains("trace"))
        assertTrue(export.contains("parse_document_manifest"))
        assertTrue(export.contains("duration_ns"))
        assertTrue(export.contains("do not sum"))
    }
}
