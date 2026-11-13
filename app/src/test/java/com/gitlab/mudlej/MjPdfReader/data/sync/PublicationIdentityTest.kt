// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.tbend.thoughts.sdk.LibraryEntry

class PublicationIdentityTest {
    @Test
    fun importedPdfDoesNotRequireSourceArtifact() {
        requireValidPublicationIdentity(publication("unlinked", null))
    }

    @Test
    fun sourceLinkedPdfRequiresValidSourceArtifact() {
        requireValidPublicationIdentity(publication("source_linked", HASH))
        for (sourceHash in listOf(null, "", "invalid")) {
            assertRejected(publication("source_linked", sourceHash))
        }
    }

    @Test
    fun missingKindKeepsSourceLinkedValidation() {
        requireValidPublicationIdentity(publication(null, HASH))
        assertRejected(publication(null, null))
        assertRejected(publication("", null))
    }

    @Test
    fun importedPdfStillRejectsMalformedOptionalSourceArtifact() {
        requireValidPublicationIdentity(publication("unlinked", HASH))
        assertRejected(publication("unlinked", "invalid"))
        assertRejected(publication("unlinked", ""))
    }

    @Test
    fun everyKindStillRequiresPdfAndGenerationHashesAndNonnegativeSize() {
        for (kind in listOf("unlinked", "source_linked")) {
            val valid = publication(kind, if (kind == "unlinked") null else HASH)
            assertRejected(valid.copy(sha256 = "invalid"))
            assertRejected(valid.copy(generationId = "invalid"))
            assertRejected(valid.copy(sizeBytes = -1))
        }
    }

    private fun assertRejected(publication: LibraryEntry) {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidPublicationIdentity(publication)
        }
    }

    private fun publication(kind: String?, sourceHash: String?) = LibraryEntry(
        generationId = "55a04845e7eaf0b8cf6ed9cc18cfe797f46adb1137f7ef4bafef411e992b0b5f",
        documentId = "lecture",
        filename = "01_-_Introduction_and_Motivation_db3d665398be.pdf",
        sha256 = "db3d665398be84fa4a6149f163c8d8885af9468a702952d8b5f5f8e2a39f2cb2",
        sizeBytes = 4340573,
        project = "lectures",
        sourceFrontier = 0,
        commentFrontier = 0,
        annotationFrontier = 0,
        placementManifestSha256 = null,
        sourceArtifactSha256 = sourceHash,
        metadata = JsonObject().apply { if (kind != null) addProperty("kind", kind) },
    )

    companion object {
        private val HASH = "a".repeat(64)
    }
}
