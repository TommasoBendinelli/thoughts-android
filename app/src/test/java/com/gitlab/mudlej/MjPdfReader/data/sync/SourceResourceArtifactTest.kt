// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceResourceArtifactTest {
    @Test
    fun sharedStyleIsDeduplicatedAndRetainsEveryConsumer() {
        val resource = sharedStyle("a".repeat(64))

        val first = mergeSourceResourceDocuments(null, resource, "alpha.pdf")
        val merged = mergeSourceResourceDocuments(first, resource, "beta.pdf")

        assertEquals(
            listOf("alpha.pdf", "beta.pdf"),
            merged.getAsJsonArray("pdf_filenames").map { it.asString },
        )
        assertEquals("project_style", merged.get("kind").asString)
        assertEquals("docs-paper/tex/support/manual_style.sty", merged.get("path").asString)
    }

    @Test
    fun artifactsCannotDisagreeAboutSharedStyleBytes() {
        val first = mergeSourceResourceDocuments(
            null,
            sharedStyle("a".repeat(64)),
            "alpha.pdf",
        )

        assertThrows(IllegalStateException::class.java) {
            mergeSourceResourceDocuments(
                first,
                sharedStyle("b".repeat(64)),
                "beta.pdf",
            )
        }
    }

    @Test
    fun healthySharedStyleWinsOverStaleLastGoodArtifactInEitherOrder() {
        val stale = sharedStyle("a".repeat(64))
        val healthy = sharedStyle("b".repeat(64))

        val staleThenHealthy = mergeSourceResourceDocuments(
            mergeSourceResourceDocuments(
                null,
                stale,
                "failed.pdf",
                publicationStale = true,
            ),
            healthy,
            "healthy.pdf",
            publicationStale = false,
        )
        val healthyThenStale = mergeSourceResourceDocuments(
            mergeSourceResourceDocuments(
                null,
                healthy,
                "healthy.pdf",
                publicationStale = false,
            ),
            stale,
            "failed.pdf",
            publicationStale = true,
        )

        listOf(staleThenHealthy, healthyThenStale).forEach { merged ->
            assertEquals("b".repeat(64), merged.get("sha256").asString)
            assertEquals(
                listOf("failed.pdf", "healthy.pdf"),
                merged.getAsJsonArray("pdf_filenames").map { it.asString },
            )
        }
    }

    @Test
    fun healthySharedStyleWinsAfterMultipleDifferentStaleArtifacts() {
        val staleA = sharedStyle("a".repeat(64))
        val staleB = sharedStyle("b".repeat(64))
        val healthy = sharedStyle("c".repeat(64))

        val merged = mergeSourceResourceDocuments(
            mergeSourceResourceDocuments(
                mergeSourceResourceDocuments(
                    null,
                    staleA,
                    "failed-a.pdf",
                    publicationStale = true,
                ),
                staleB,
                "failed-b.pdf",
                publicationStale = true,
            ),
            healthy,
            "healthy.pdf",
            publicationStale = false,
        )

        assertEquals("c".repeat(64), merged.get("sha256").asString)
        assertEquals(
            listOf("failed-a.pdf", "failed-b.pdf", "healthy.pdf"),
            merged.getAsJsonArray("pdf_filenames").map { it.asString },
        )
    }

    @Test
    fun stalePublicationCannotOverrideOrExcuseAProjectSpecificOverrideConflict() {
        val stale = sharedStyle("a".repeat(64)).apply {
            addProperty("kind", "project_style_override")
            addProperty("path", "docs-paper/tex/support/manual_style_override.sty")
        }
        val healthy = stale.deepCopy().apply {
            addProperty("sha256", "b".repeat(64))
            addProperty("object", "b".repeat(64))
        }
        val first = mergeSourceResourceDocuments(
            null,
            stale,
            "paper.pdf",
            publicationStale = true,
        )

        assertThrows(IllegalStateException::class.java) {
            mergeSourceResourceDocuments(
                first,
                healthy,
                "paper.pdf",
                publicationStale = false,
            )
        }
    }

    private fun sharedStyle(digest: String) = JsonObject().apply {
        addProperty("kind", "project_style")
        addProperty("path", "docs-paper/tex/support/manual_style.sty")
        addProperty("sha256", digest)
        addProperty("object", digest)
        addProperty("bytes", 42)
        add("dependencies", JsonArray())
    }
}
