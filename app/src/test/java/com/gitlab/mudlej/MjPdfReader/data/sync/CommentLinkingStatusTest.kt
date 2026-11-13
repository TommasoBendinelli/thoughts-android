// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentLinkingStatusTest {
    private fun comment(status: String, collection: String = "paper", extra: String = "") =
        """{"origin":{"collection":"$collection"},"linking":{"status":"$status"}$extra}"""

    @Test fun countsUnresolvedRootsInTargetOnly() {
        val values = listOf(comment("needs_attention"), comment("failed"), comment("succeeded"),
            comment("failed", "other"), comment("failed", extra = ",\"parent_id\":\"parent\""),
            comment("failed", extra = ",\"deleted\":true"))
        assertEquals(2, unresolvedLinkingCount(values, "paper.pdf"))
        assertEquals(3, unresolvedLinkingCount(values, "all"))
    }

    @Test fun handlesNotesLegacyScopesAndInvalidRecords() {
        assertEquals(1, unresolvedLinkingCount(listOf("{}", "null", "invalid",
            """{"origin":null,"scope":null,"linking":null}""",
            """{"origin":{"collection":"paper"},"scope":{"state":"needs_attention"}}"""), "paper.pdf"))
    }

    @Test fun notesNeverCountAsNeedingLinkingEvenWithStaleMetadata() {
        val note = """{"subtype":"Note","origin":null,"scope":{"state":"standalone"},"linking":{"status":"failed"}}"""
        assertEquals(0, unresolvedLinkingCount(listOf(note), "all"))
        assertEquals(0, unresolvedLinkingCount(listOf(note), null))
    }
}
