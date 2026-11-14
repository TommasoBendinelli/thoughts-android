// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationJournalBatchTest {
    private val root: File = Files.createTempDirectory("verification-journal").toFile()
    private val journal = File(root, "journal.tsv")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A file's identity without Android's stat: enough to tell the test's files apart. */
    private fun identity(file: File): String? = if (file.isFile) "${file.name}:${file.length()}:${file.lastModified()}" else null

    private fun files(count: Int, directory: String): List<File> = (1..count).map { number ->
        File(root, "$directory/object-$number").apply { parentFile.mkdirs(); writeText("object $number") }
    }

    @Test
    fun aBatchWritesItsVerificationsOnceAndLosesNone() {
        val cache = FileVerificationCache(journal, ::identity)
        val objects = files(40, "kept")
        cache.batchingJournal {
            objects.forEach(cache::sha256)
            // Nothing is appended file by file.
            assertFalse(journal.exists())
        }
        assertEquals(40, journal.readLines().size)
        // A restart reads them back: no file is hashed again.
        var hashed = 0
        val restarted = FileVerificationCache(journal) { file -> identity(file) }
        objects.forEach { assertTrue(restarted.knownSha256(it) != null).also { hashed++ } }
        assertEquals(40, hashed)
    }

    @Test
    fun withoutABatchEveryVerificationIsAppendedAsBefore() {
        val cache = FileVerificationCache(journal, ::identity)
        val objects = files(3, "kept")
        cache.sha256(objects[0])
        assertEquals(1, journal.readLines().size)
        cache.sha256(objects[1])
        cache.sha256(objects[2])
        assertEquals(3, journal.readLines().size)
    }

    @Test
    fun aBatchThatFailsStillWritesWhatItVerified() {
        val cache = FileVerificationCache(journal, ::identity)
        val objects = files(2, "kept")
        runCatching {
            cache.batchingJournal {
                cache.sha256(objects[0])
                error("the move failed")
            }
        }
        assertEquals(1, journal.readLines().size)
        // And later verifications are appended one by one again.
        cache.sha256(objects[1])
        assertEquals(2, journal.readLines().size)
    }

    @Test
    fun nestedBatchesWriteAtTheEndOfTheOutermost() {
        val cache = FileVerificationCache(journal, ::identity)
        val objects = files(4, "kept")
        cache.batchingJournal {
            cache.batchingJournal { objects.take(2).forEach(cache::sha256) }
            assertFalse(journal.exists())
            objects.drop(2).forEach(cache::sha256)
        }
        assertEquals(4, journal.readLines().size)
    }
}
