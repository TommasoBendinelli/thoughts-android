// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSourceBundlesTest {
    private val firstDigest = "add7ff5e0c19dcbc" + "0".repeat(48)
    private val secondDigest = "c71cfd3558787b46" + "0".repeat(48)

    private fun bundle(id: String, digest: String, filename: String, quads: List<OfflineProposalQuad> = emptyList()) =
        OfflineSourceBundle(
            bundleId = id,
            pdfs = emptyMap(),
            sources = emptyMap(),
            resources = emptyMap(),
            proposals = mapOf(
                "probtrace-method-20260901-protocol-regimes" to OfflineAgentProposal(
                    bundleId = id,
                    objectSha256 = digest,
                    id = "probtrace-method-20260901-protocol-regimes",
                    kind = "edit",
                    comment = "Explain the protocol regimes.",
                    sourceFile = "fragments/appendix/protocol_metrics.tex",
                    sourceSha256 = "a".repeat(64),
                    line = 15,
                    bodyPreview = "body",
                    beforePreview = "before",
                    afterPreview = "after",
                    parentId = null,
                    suppressed = false,
                    pdfFilenames = listOf(filename),
                    quads = quads,
                )
            ),
            reviewComments = emptyMap(),
            reviewCommentDocuments = emptySet(),
        )

    @Test fun twoRevisionsOfOneProposalMergeInsteadOfCrashing() {
        // The two Library entries carried the same authored proposal observed at
        // different revisions. That must not throw on the sync worker.
        val merged = combineProjectBundles(
            "bundle",
            listOf(
                bundle("b1", firstDigest, "import-194018d8.pdf") to false,
                bundle("b2", secondDigest, "import-b0574283.pdf") to false,
            )
        )

        val proposal = merged.proposals.getValue("probtrace-method-20260901-protocol-regimes")
        assertEquals("the same authored content was kept", "Explain the protocol regimes.", proposal.comment)
        assertEquals(
            "one revision wins so clients still see a well-formed digest",
            setOf(firstDigest, secondDigest).single { it == proposal.objectSha256 },
            proposal.objectSha256,
        )
        assertEquals(
            "both placements are retained",
            listOf("import-194018d8.pdf", "import-b0574283.pdf"),
            proposal.pdfFilenames,
        )
    }

    @Test fun repeatedMergesChooseTheSameRevision() {
        val forward = combineProjectBundles(
            "bundle",
            listOf(
                bundle("b1", firstDigest, "a.pdf") to false,
                bundle("b2", secondDigest, "b.pdf") to false,
            )
        ).proposals.getValue("probtrace-method-20260901-protocol-regimes")

        val reversed = combineProjectBundles(
            "bundle",
            listOf(
                bundle("b2", secondDigest, "b.pdf") to false,
                bundle("b1", firstDigest, "a.pdf") to false,
            )
        ).proposals.getValue("probtrace-method-20260901-protocol-regimes")

        assertEquals(forward.objectSha256, reversed.objectSha256)
    }

    @Test fun identicalRevisionsStillMergePlacements() {
        val merged = combineProjectBundles(
            "bundle",
            listOf(
                bundle("b1", firstDigest, "a.pdf") to false,
                bundle("b2", firstDigest, "b.pdf") to false,
            )
        )

        val proposal = merged.proposals.getValue("probtrace-method-20260901-protocol-regimes")
        assertEquals(firstDigest, proposal.objectSha256)
        assertEquals(listOf("a.pdf", "b.pdf"), proposal.pdfFilenames)
    }

    @Test fun differingAuthoredContentDoesNotThrow() {
        val left = bundle("b1", firstDigest, "a.pdf")
        val target = "probtrace-method-20260901-protocol-regimes"
        val rightProposal = left.proposals.getValue(target).copy(comment = "A different revision.")
        val right = left.copy(bundleId = "b2", proposals = mapOf(target to rightProposal))

        val merged = combineProjectBundles("bundle", listOf(left to false, right to false))

        val proposal = merged.proposals.getValue(target)
        assertTrue(
            "the merged proposal keeps one of the two authored revisions",
            proposal.comment == "Explain the protocol regimes." || proposal.comment == "A different revision.",
        )
        assertTrue("the digest stays a well-formed sha256", proposal.objectSha256.matches(Regex("[a-f0-9]{64}")))
    }
}
