// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtsSyncPullPolicyTest {
    @Test
    fun placementFailureAndPendingStateArriveWithoutInstallingOldGeometry() {
        for (state in listOf("needs_attention", "queued", "retry", "running", "updating")) {
            val projection = """{"primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}"},"reanchor":{"state":"$state","target_pdf_sha256":"${"b".repeat(64)}"}}"""
            assertTrue(reviewCardProjectionMatchesActivatedPdf(projection, mapOf("paper.pdf" to "b".repeat(64))))
        }
    }

    @Test
    fun unchangedLibraryFrontierSkipsTheFinalPull() {
        assertFalse(shouldPullFinalChanges(42, 42))
        assertFalse(shouldPullFinalChanges(null, 42))
    }

    @Test
    fun advancedLibraryFrontierRequiresTheFinalPull() {
        assertTrue(shouldPullFinalChanges(43, 42))
    }

    @Test
    fun reviewCardProjectionWaitsForItsExactPdfGeneration() {
        val oldHash = "a".repeat(64)
        val newHash = "b".repeat(64)
        val projection = reviewCardProjection("paper", newHash)

        assertFalse(
            reviewCardProjectionMatchesActivatedPdf(
                projection,
                mapOf("paper.pdf" to oldHash),
            ),
        )
        assertTrue(
            reviewCardProjectionMatchesActivatedPdf(
                projection,
                mapOf("paper.pdf" to newHash),
            ),
        )
    }

    @Test
    fun invalidReviewCardProjectionNeverReplacesInstalledState() {
        assertFalse(
            reviewCardProjectionMatchesActivatedPdf(
                """{"schema_version":3,"primary":{"pdf_stem":"paper"}}""",
                mapOf("paper.pdf" to "a".repeat(64)),
            ),
        )
    }

    @Test
    fun basicCardsAlsoRequireTheCurrentPdfGeneration() {
        for (method in listOf("basic_half_page", "basic_passages_v1")) {
            val sha = "a".repeat(64)
            val projection = """{"generation_method":"$method","primary":{"pdf_stem":"paper","pdf_sha256":"$sha"}}"""
            assertFalse(reviewCardProjectionMatchesActivatedPdf(projection, mapOf("paper.pdf" to "b".repeat(64))))
            assertTrue(reviewCardProjectionMatchesActivatedPdf(projection, mapOf("paper.pdf" to sha)))
        }
    }

    private fun reviewCardProjection(pdfStem: String, pdfSha256: String): String =
        """{"schema_version":3,"primary":{"pdf_stem":"$pdfStem","pdf_sha256":"$pdfSha256"}}"""
}
