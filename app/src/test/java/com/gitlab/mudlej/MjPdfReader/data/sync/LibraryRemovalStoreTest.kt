// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryRemovalStoreTest {
    private lateinit var root: File
    private lateinit var stateFile: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("library-removal-store").toFile()
        stateFile = File(root, "removals.json")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun pendingRemovalSurvivesRestartAndIsIdentityScoped() {
        val digest = "a".repeat(64)
        LibraryRemovalStore(stateFile).enqueue(
            filename = "paper.pdf",
            sha256 = digest,
            createdAtEpochMillis = 123,
            requestId = "11111111-1111-4111-8111-111111111111",
        )

        val restarted = LibraryRemovalStore(stateFile)

        assertEquals(setOf("paper.pdf"), restarted.matchingFilenames(mapOf("paper.pdf" to digest)))
        assertEquals(emptySet<String>(), restarted.matchingFilenames(mapOf("paper.pdf" to "b".repeat(64))))
        assertEquals(123, restarted.pending().single().createdAtEpochMillis)
    }

    @Test
    fun exactTombstoneRemainsUntilActivatedGenerationDropsItsIdentity() {
        val digest = "a".repeat(64)
        val store = LibraryRemovalStore(stateFile)
        store.enqueue(
            filename = "paper.pdf",
            sha256 = digest,
            requestId = "22222222-2222-4222-8222-222222222222",
        )

        assertEquals(0, store.clearActivated(mapOf("paper.pdf" to digest)))
        assertEquals(1, store.pending().size)
        assertEquals(1, store.clearActivated(emptyMap()))
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun deliveryFailureIsDurableWithoutDiscardingRequest() {
        val store = LibraryRemovalStore(stateFile)
        val removal = store.enqueue(
            filename = "paper.pdf",
            sha256 = "a".repeat(64),
            requestId = "33333333-3333-4333-8333-333333333333",
        )

        store.recordFailure(removal.requestId, "server unavailable")

        assertEquals("server unavailable", LibraryRemovalStore(stateFile).pending().single().failureDetail)
        store.clearFailure(removal.requestId)
        assertEquals("", LibraryRemovalStore(stateFile).pending().single().failureDetail)
    }
}
