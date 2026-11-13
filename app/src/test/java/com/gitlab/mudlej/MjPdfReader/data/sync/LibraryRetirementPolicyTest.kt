// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryRetirementPolicyTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("library-retirement").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun retiresOnlyPreviouslyManagedUnlinkedPdfWithMatchingHash() {
        val retired = root.resolve("retired.pdf").apply { writeText("retired") }
        val linked = root.resolve("linked.pdf").apply { writeText("linked") }
        val changed = root.resolve("changed.pdf").apply { writeText("changed-now") }
        val active = root.resolve("active.pdf").apply { writeText("active") }
        val index = libraryIndex(
            "retired.pdf" to ("unlinked" to hash("retired")),
            "linked.pdf" to ("source_linked" to hash("linked")),
            "changed.pdf" to ("unlinked" to hash("changed-before")),
            "active.pdf" to ("unlinked" to hash("active")),
        )

        assertEquals(
            listOf("retired.pdf"),
            retireManagedUnlinkedPublications(root, index, setOf("active.pdf")),
        )
        assertFalse(retired.exists())
        assertTrue(linked.exists())
        assertTrue(changed.exists())
        assertTrue(active.exists())
    }

    @Test
    fun malformedOrMissingIndexNeverDeletesAnything() {
        val pdf = root.resolve("retired.pdf").apply { writeText("retired") }
        assertEquals(emptyList<String>(), retireManagedUnlinkedPublications(root, null, emptySet()))
        assertEquals(emptyList<String>(), retireManagedUnlinkedPublications(root, "not-json", emptySet()))
        assertTrue(pdf.exists())
    }

    private fun libraryIndex(vararg entries: Pair<String, Pair<String, String>>): String =
        entries.joinToString(
            prefix = "{\"schema_version\":1,\"pdfs\":[",
            postfix = "]}",
        ) { (filename, identity) ->
            "{\"filename\":\"$filename\",\"kind\":\"${identity.first}\",\"pdf_sha256\":\"${identity.second}\"}"
        }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
