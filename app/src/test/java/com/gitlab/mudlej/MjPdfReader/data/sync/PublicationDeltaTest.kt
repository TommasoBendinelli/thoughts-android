// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.github.luben.zstd.Zstd
import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.tbend.thoughts.sdk.LibraryEntry

class PublicationDeltaTest {
    @Test
    fun detectsServerSideLibraryRemovalAsArtifactChange() {
        val retained = publication("retained.pdf", "a".repeat(64), 100)
        val local = mapOf(
            retained.filename to (retained.sha256 to retained.sizeBytes),
            "removed.pdf" to ("b".repeat(64) to 200L),
        )

        val delta = libraryPublicationDelta(local, listOf(retained))

        assertEquals(emptyList<LibraryEntry>(), delta.changedPublications)
        assertEquals(setOf("removed.pdf"), delta.removedFilenames)
        assertEquals(1, delta.changedPdfCount)
    }

    @Test
    fun targetedSnapshotNeverInfersGlobalLibraryRemoval() {
        val retained = publication("retained.pdf", "a".repeat(64), 100)
        val local = mapOf(
            retained.filename to (retained.sha256 to retained.sizeBytes),
            "unrelated.pdf" to ("b".repeat(64) to 200L),
        )

        val delta = libraryPublicationDelta(local, listOf(retained), retained.filename)

        assertEquals(emptySet<String>(), delta.removedFilenames)
        assertEquals(0, delta.changedPdfCount)
    }

    @Test
    fun removalDeliveryRequiresExplicitGlobalControlSync() {
        assertEquals(true, shouldDeliverPendingLibraryRemovals(true, null, false))
        assertEquals(false, shouldDeliverPendingLibraryRemovals(false, null, false))
        assertEquals(false, shouldDeliverPendingLibraryRemovals(true, "manual.pdf", false))
        assertEquals(false, shouldDeliverPendingLibraryRemovals(true, null, true))
    }

    @Test
    fun reconstructsExactPublicationFromDictionaryPatch() {
        val base = ("unchanged PDF page data\n".repeat(20_000) + "old ending\n").toByteArray()
        val result = ("unchanged PDF page data\n".repeat(20_000) + "new ending\n").toByteArray()
        val patch = ByteArray(Zstd.compressBound(result.size.toLong()).toInt())
        val patchSize = Zstd.compressUsingDict(
            patch,
            0,
            result,
            0,
            result.size,
            base,
            3,
        )
        val encoded = patch.copyOf(patchSize.toInt())

        assertArrayEquals(result, decodeZstdPublicationDelta(base, encoded, result.size))
    }

    @Test
    fun rejectsCorruptPatch() {
        assertNull(decodeZstdPublicationDelta("base".toByteArray(), "bad".toByteArray(), 100))
    }

    @Test
    fun estimatesPatchOnlyForExactVerifiedBase() {
        val base = "a".repeat(64)
        val result = "b".repeat(64)
        val patch = "c".repeat(64)
        val publication = LibraryEntry(
            generationId = "d".repeat(64),
            documentId = "document",
            filename = "manual.pdf",
            sha256 = result,
            sizeBytes = 6_538_294,
            project = "docs-manual",
            sourceFrontier = 1,
            commentFrontier = 1,
            annotationFrontier = 1,
            placementManifestSha256 = null,
            metadata = JsonObject().apply {
                addProperty("kind", "source_linked")
                add("delta", JsonObject().apply {
                    addProperty("format", "zstd-patch-v1")
                    addProperty("base_sha256", base)
                    addProperty("object_sha256", patch)
                    addProperty("size_bytes", 102_702)
                    addProperty("result_sha256", result)
                    addProperty("result_size_bytes", 6_538_294)
                })
            },
        )

        val unrelated = "e".repeat(64)
        assertEquals(setOf(base), requiredPdfReuseHashes(listOf(publication), setOf(base, unrelated), true))
        assertEquals(setOf(base, result), requiredPdfReuseHashes(listOf(publication), setOf(base, result, unrelated), true))
        assertEquals(setOf(result), requiredPdfReuseHashes(listOf(publication), setOf(base, result), false))
        assertEquals(emptySet<String>(), requiredPdfReuseHashes(emptyList(), setOf(base, result), true))
        assertEquals(emptySet<String>(), requiredPdfReuseHashes(listOf(publication), setOf(unrelated), true))

        assertEquals(102_702L, estimatedPublicationTransferBytes(publication, setOf(base), true))
        assertEquals(6_538_294L, estimatedPublicationTransferBytes(publication, emptySet(), true))
        assertEquals(6_538_294L, estimatedPublicationTransferBytes(publication, setOf(base), false))
        assertEquals(0L, estimatedPublicationTransferBytes(publication, setOf(base, result), true))
    }

    private fun publication(filename: String, sha256: String, sizeBytes: Long) = LibraryEntry(
        generationId = "d".repeat(64),
        documentId = filename,
        filename = filename,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        project = "docs-manual",
        sourceFrontier = 1,
        commentFrontier = 1,
        annotationFrontier = 1,
        placementManifestSha256 = null,
        metadata = JsonObject().apply { addProperty("kind", "unlinked") },
    )
}
