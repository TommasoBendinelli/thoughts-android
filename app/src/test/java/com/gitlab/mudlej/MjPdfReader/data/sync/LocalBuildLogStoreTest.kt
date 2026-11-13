// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalBuildLogStoreTest {
    private lateinit var root: java.io.File
    private lateinit var store: LocalBuildLogStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("local-build-logs").toFile()
        store = LocalBuildLogStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun cachesAndReadsVerifiedLogWithoutARepositoryOrClient() {
        val contents = "Fedora compiler output\n".toByteArray()
        val reference = reference(contents)

        store.cache(reference, contents)

        assertTrue(store.isAvailable(reference))
        assertEquals("Fedora compiler output\n", store.read(reference))
    }

    @Test
    fun rejectsContentsThatDoNotMatchTheImmutableReference() {
        val reference = reference("expected".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            store.cache(reference, "different".toByteArray())
        }
        assertFalse(store.isAvailable(reference))
    }

    @Test
    fun corruptLocalFileIsNeverPresentedAsAValidLog() {
        val contents = "verified".toByteArray()
        val reference = reference(contents)
        store.cache(reference, contents)
        root.listFiles().orEmpty().single().writeText("corrupt")

        assertFalse(store.isAvailable(reference))
        assertNull(store.read(reference))
    }

    private fun reference(contents: ByteArray): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(contents)
            .joinToString("") { "%02x".format(it) }
        return "server-object:$sha256"
    }
}
