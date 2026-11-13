// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPlacementProofTest {
    @Test fun evidenceCannotBeReusedForAnotherCommentOrFormat() {
        val proof = ReviewPlacementProof(4, "markdown_text_fragment", "a".repeat(64), "b".repeat(64),
            "c".repeat(64), "d".repeat(64), "e".repeat(64), 1, "unique", true, "", false,
            listOf("comment:c1:s1"), 1, "markdown", "f".repeat(64), "thoughts-md-fragment-1")
        assertTrue(proof.hasCompilerEvidence("comment:c1", "exact_text"))
        assertFalse(proof.hasCompilerEvidence("comment:c2", "exact_text"))
        assertFalse(proof.hasCompilerEvidence("comment:c1", "exact_point"))
        assertFalse(proof.copy(rendererEvidenceSha256 = "").hasCompilerEvidence("comment:c1", "exact_text"))
        assertFalse(proof.copy(resolverVersion = 3).hasCompilerEvidence("comment:c1", "exact_text"))
    }
}
