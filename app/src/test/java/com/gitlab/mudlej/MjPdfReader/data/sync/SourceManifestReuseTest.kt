// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceManifestReuseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reuseRequiresMatchingInputsActiveManifestAndInstalledObjects() {
        val objects = temporary.newFolder()
        val bytes = "verified manifest".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = File(objects, digest).apply { writeBytes(bytes) }
        val source = File(objects, "a".repeat(64)).apply { writeText("source") }
        val artifacts = listOf("b.pdf" to "b".repeat(64), "a.pdf" to "c".repeat(64))
        val inputs = sourceManifestInputs(artifacts, emptyMap())
        val receipt = sourceManifestReceipt(inputs, digest, objects)
        assertTrue(canReuseSourceManifest(receipt, sourceManifestInputs(artifacts.reversed(), emptyMap()), digest, objects))
        assertFalse(canReuseSourceManifest(receipt, sourceManifestInputs(artifacts, mapOf("b.pdf" to true)), digest, objects))
        assertFalse(canReuseSourceManifest(receipt, sourceManifestInputs(artifacts.drop(1), emptyMap()), digest, objects))
        assertFalse(canReuseSourceManifest(receipt, inputs, "f".repeat(64), objects))
        val modified = manifest.lastModified()
        manifest.writeText("untrusted content")
        manifest.setLastModified(modified)
        assertFalse(canReuseSourceManifest(receipt, inputs, digest, objects))
        manifest.writeBytes(bytes)
        manifest.setLastModified(modified)
        source.delete()
        assertFalse(canReuseSourceManifest(receipt, inputs, digest, objects))
    }
}
