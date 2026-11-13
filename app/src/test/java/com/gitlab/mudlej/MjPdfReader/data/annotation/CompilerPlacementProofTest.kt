// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilerPlacementProofTest {
    private fun proof(kind: String = "point") = ReviewPlacementProof(
        resolverVersion = 3, method = "tex_shipout_$kind", sourceSha256 = "a".repeat(64),
        sourceBundleSha256 = "b".repeat(64), buildSha256 = "c".repeat(64), pdfSha256 = "d".repeat(64),
        extractedTextSha256 = "", candidateCount = 1, uniqueness = "unique", verified = true,
        reason = "Compiler marker", humanConfirmed = false, occurrence = 1,
        markerIds = if (kind == "point") listOf("thoughts-shipout:comment:root:point:1")
            else listOf("thoughts-shipout:comment:root:span-0-start:1", "thoughts-shipout:comment:root:span-0-end:1"),
    )

    @Test fun acceptsCompleteObservedPointsAndSpans() {
        assertTrue(proof().hasCompilerEvidence("comment:root", "exact_point"))
        assertTrue(proof("text").hasCompilerEvidence("comment:root", "exact_text"))
    }

    @Test fun refusesGuessedCoordinatesAndMismatchedMarkerIdentities() {
        assertFalse(proof().copy(method = "synctex_exact_point").hasCompilerEvidence("comment:root", "exact_point"))
        assertFalse(proof().hasCompilerEvidence("comment:other", "exact_point"))
        assertFalse(proof().copy(occurrence = 2).hasCompilerEvidence("comment:root", "exact_point"))
        assertFalse(proof().copy(markerIds = emptyList()).hasCompilerEvidence("comment:root", "exact_point"))
        assertFalse(proof().copy(verified = false).hasCompilerEvidence("comment:root", "exact_point"))
        assertFalse(proof("text").copy(markerIds = listOf("thoughts-shipout:comment:root:span-0-start:1", "thoughts-shipout:comment:root:span-1-end:1"))
            .hasCompilerEvidence("comment:root", "exact_text"))
    }
}
