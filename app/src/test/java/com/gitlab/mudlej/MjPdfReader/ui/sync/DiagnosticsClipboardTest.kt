// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsClipboardTest {
    @Test fun shortReportsAreCopiedWhole() {
        val copy = clipboardDiagnostics("summary\nevents", limit = 100)
        assertFalse(copy.trimmed)
        assertEquals("summary\nevents", copy.text)
    }

    @Test fun longReportsKeepTheirStartAndEnd() {
        val report = "S".repeat(50) + "m".repeat(1_000) + "E".repeat(70)
        val copy = clipboardDiagnostics(report, limit = 100)
        assertTrue(copy.trimmed)
        assertTrue(copy.text.startsWith("S".repeat(40)))
        assertTrue(copy.text.endsWith("E".repeat(60)))
        assertTrue(copy.text.contains("1020 characters omitted"))
        assertTrue(copy.text.contains("full report is saved on this device"))
    }
}
