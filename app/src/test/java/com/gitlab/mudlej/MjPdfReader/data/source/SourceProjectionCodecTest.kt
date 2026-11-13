// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewPlacementProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceProjectionCodecTest {
    private data class MapThenReferences(val hashes: Map<String, String>, val repeated: List<String>)

    @Test fun preMarkdownCachedProofsRemainHashableAndKeepTheirLatexEvidence() {
        val hash = "a".repeat(64)
        val proof = ReviewPlacementProof(3, "tex_shipout_point", hash, hash, hash, hash, "", 1,
            "unique", true, "", false, listOf("thoughts-shipout:comment:root:point:1"), 1)
        val legacy = com.google.gson.Gson().toJsonTree(proof).asJsonObject.apply {
            remove("sourceFormat")
            remove("rendererEvidenceSha256")
            remove("fragmentId")
        }.toString()
        // Both legacy JSON and compact projections existed before Markdown support.
        for (decoder in listOf(com.google.gson.Gson(), sourceProjectionGson())) {
            val decoded = decoder.fromJson(legacy, ReviewPlacementProof::class.java)
            val location = LogicalCommentPdfLocation("sample.pdf", hash, 1, "exact", proof = decoded)
            assertEquals(1, listOf(location, location.copy()).distinct().size)
            assertTrue(decoded.hasCompilerEvidence("comment:root", "exact_point"))
            // Missing Markdown evidence must never become an accepted Markdown proof.
            assertFalse(decoded.copy(resolverVersion = 4, sourceFormat = "markdown")
                .hasCompilerEvidence("comment:root", "exact_point"))
        }
    }

    @Test fun concreteMapImplementationsKeepReferencesCorrectForFieldsAfterTheMap() {
        for (map in listOf<Map<String, String>>(linkedMapOf("first.pdf" to "hash1", "second.pdf" to "hash2"),
            sortedMapOf("first.pdf" to "hash1", "second.pdf" to "hash2"))) {
            val original = MapThenReferences(map, listOf("hash1", "first.pdf", "hash2", "second.pdf"))
            assertEquals(original, sourceProjectionGson().fromJson(sourceProjectionGson().toJson(original), MapThenReferences::class.java))
        }
    }

    @Test fun roundTripPreservesProofsMarkersPayloadsAndMapKeysWhileSharingRepeatedValues() {
        val hash = "a".repeat(64)
        val locations = (1..100).map { index ->
            LogicalCommentPdfLocation("sample.pdf", hash, 1, "exact", regions = listOf(listOf(0.1f, 0.2f, 0.3f, 0.4f)),
                sourceSha256 = hash, sourceBundleSha256 = hash, buildSha256 = hash, role = "after", placementKind = "exact_text",
                proof = ReviewPlacementProof(3, "tex_shipout_text", hash, hash, hash, hash, index.toString(), 1,
                    "unique", true, "", false, listOf("start-$index", "end-$index"), index))
        }
        val correspondence = ReviewCorrespondence("comment:id", "comment", "id", "id", "active", "exact_text", "",
            hash, emptyList(), locations)
        val original = OfflineOverviewSourceSnapshot(bundleId = hash,
            reviewComments = listOf(OfflineSourceReviewComment("id", "0", "source.tex", hash, 1, listOf("sample.pdf"))),
            logicalComments = listOf(OfflineLogicalComment("id", "id", null, 1, false, "active", "source.tex", 1,
                "sample.pdf", 0, "{\"text\":\"é \\\"quoted\\\"\"}", correspondence)),
            reviewCommentDocuments = setOf("sample.pdf"), pdfSha256ByFilename = linkedMapOf("sample.pdf" to hash, hash to "0"))
        val encoded = sourceProjectionGson().toJson(original)
        val restored = sourceProjectionGson().fromJson(encoded, OfflineOverviewSourceSnapshot::class.java)
        assertEquals(original, restored)
        assertEquals(100, restored.logicalComments.single().locations.map { it.proof!!.markerIds }.toSet().size)
        val first = restored.logicalComments.single().locations.first()
        assertSame(first.pdfSha256, first.proof!!.buildSha256)
        assertSame(first.pdfFilename, restored.logicalComments.single().locations.last().pdfFilename)
        assertTrue(encoded.length < com.google.gson.Gson().toJson(original).length / 2)
    }

    @Test fun rowsStoredBeforeDuplicateMarkingDecodeAsSourceOccurrences() {
        val hash = "a".repeat(64)
        val location = LogicalCommentPdfLocation("sample.pdf", hash, 1, "exact",
            regions = listOf(listOf(0.1f, 0.2f, 0.3f, 0.4f)), role = "after", placementKind = "exact_text")
        val quad = OfflineProposalQuad("after", "sample.pdf", 1, 0.1f, 0.2f, 0.3f, 0.4f, normalized = true)
        val proposal = OfflineAgentProposal(hash, hash, "heading-20261007", "edit", "", "main.tex", hash, 1,
            "", "", "", null, false, listOf("sample.pdf"), listOf(quad),
            ReviewCorrespondence("proposal:heading-20261007", "agent_edit", "heading-20261007", "", "active",
                "exact_text", "", hash, emptyList(), listOf(location)))
        val legacy = com.google.gson.Gson().toJsonTree(proposal).asJsonObject.apply {
            getAsJsonArray("quads").forEach { it.asJsonObject.remove("duplicate") }
            getAsJsonObject("correspondence").getAsJsonArray("locations").forEach { it.asJsonObject.remove("duplicate") }
        }.toString()
        assertFalse(legacy.contains("duplicate"))
        for (decoder in listOf(com.google.gson.Gson(), sourceProjectionGson())) {
            val decoded = decoder.fromJson(legacy, OfflineAgentProposal::class.java)
            assertEquals(proposal, decoded)
            assertFalse(decoded.quads.single().duplicate)
            assertFalse(decoded.correspondence!!.locations.single().duplicate)
        }

        val marked = proposal.copy(quads = listOf(quad.copy(duplicate = true)),
            correspondence = proposal.correspondence!!.copy(locations = listOf(location.copy(duplicate = true))))
        assertEquals(marked, sourceProjectionGson().fromJson(sourceProjectionGson().toJson(marked), OfflineAgentProposal::class.java))
        val root = java.nio.file.Files.createTempDirectory("projection-duplicate").toFile()
        try {
            val file = java.io.File(root, "projection.json").apply { writeText("generation") }
            val snapshot = OfflineOverviewSourceSnapshot(bundleId = hash, proposals = listOf(marked))
            SourceProjectionDiskCache.write(file, snapshot)
            assertEquals(snapshot, SourceProjectionDiskCache.read(file, hash))
        } finally { root.deleteRecursively() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownStringReferenceIsRejected() {
        sourceProjectionGson().fromJson("{\"bundleId\":999}", OfflineOverviewSourceSnapshot::class.java)
    }
}
