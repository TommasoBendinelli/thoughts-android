// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LogicalCommentArtifactTest {

    @Test
    fun mergeKeepsOneEntityAndUnionsLocations() {
        val first = logicalArtifact("Concepts.pdf", "a", 6)
        val second = logicalArtifact("SDPO.pdf", "b", 803)

        val merged = mergeLogicalCommentArtifacts(first, second)

        assertEquals("shared", merged.get("comment_id").asString)
        assertEquals(
            listOf("Concepts.pdf", "SDPO.pdf"),
            merged.getAsJsonArray("locations").map {
                it.asJsonObject.get("pdf_filename").asString
            },
        )
    }

    @Test
    fun mergeIgnoresDocumentDerivedSourceMetadata() {
        val first = logicalArtifact("Concepts.pdf", "a", 6).apply {
            addProperty("source_file", "docs-concepts/tex/source/main.tex")
            addProperty("source_line", 42)
            addProperty("source_sha256", "c".repeat(64))
        }
        val second = logicalArtifact("SDPO.pdf", "b", 803)

        val merged = mergeLogicalCommentArtifacts(first, second)

        assertEquals(2, merged.getAsJsonArray("locations").size())
    }

    @Test
    fun mergeStillRejectsCanonicalPayloadDisagreement() {
        val first = logicalArtifact("Concepts.pdf", "a", 6)
        val second = logicalArtifact("SDPO.pdf", "b", 803).apply {
            addProperty("contents", "different")
        }

        assertThrows(IllegalStateException::class.java) {
            mergeLogicalCommentArtifacts(first, second)
        }
    }

    private fun logicalArtifact(filename: String, shaCharacter: String, page: Int) =
        JsonParser.parseString(
            """
                {
                  "schema_version": 1,
                  "comment_id": "shared",
                  "thread_root_id": "shared",
                  "revision": 2,
                  "contents": "same",
                  "locations": [{
                    "pdf_filename": "$filename",
                    "pdf_sha256": "${shaCharacter.repeat(64)}",
                    "page": $page,
                    "status": "page_only"
                  }]
                }
            """.trimIndent(),
        ).asJsonObject
}
