// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VerifiedFileCacheTest {
    @Test fun sameSizeReplacementAndCorruptedObjectInvalidateVerification() {
        val file = Files.createTempFile("verified-file", ".bin").toFile()
        try {
            var generation = "original"
            val cache = FileVerificationCache { generation }
            file.writeText("good")
            val original = cache.sha256(file)
            assertArrayEquals("good".toByteArray(), cache.read(file, original, 100))
            file.writeText("evil")
            generation = "replaced"
            assertNotEquals(original, cache.sha256(file))
            assertNull(cache.read(file, original, 100))
            assertNull(cache.read(file, cache.sha256(file), 3))
        } finally { file.delete() }
    }

    @Test fun unavailablePreciseIdentityNeverCachesAFile() {
        val file = Files.createTempFile("verified-file", ".bin").toFile()
        try {
            val cache = FileVerificationCache { null }
            file.writeText("good")
            val original = cache.sha256(file)
            file.writeText("evil")
            assertNotEquals(original, cache.sha256(file))
            assertNull(cache.read(file, original, 100))
        } finally { file.delete() }
    }

    @Test fun journalSurvivesARestartButNotAChangedIdentity() {
        val file = Files.createTempFile("verified-file", ".bin").toFile()
        val journal = Files.createTempFile("verified-journal", ".tsv").toFile().apply { delete() }
        try {
            var generation = "original"
            file.writeText("good")
            val first = FileVerificationCache(journal) { generation }
            val original = first.sha256(file)
            // A restarted process trusts the journal while the identity is unchanged.
            file.writeText("evil")
            val restarted = FileVerificationCache(journal) { generation }
            assertEquals(original, restarted.sha256(file))
            generation = "replaced"
            val afterWrite = FileVerificationCache(journal) { generation }
            assertNotEquals(original, afterWrite.sha256(file))
        } finally { file.delete(); journal.delete() }
    }

    @Test fun corruptJournalLinesAreIgnored() {
        val file = Files.createTempFile("verified-file", ".bin").toFile()
        val journal = Files.createTempFile("verified-journal", ".tsv").toFile()
        try {
            file.writeText("good")
            journal.writeText("original\tnot-a-hash\nbroken line\n")
            val cache = FileVerificationCache(journal) { "original" }
            assertEquals(FileVerificationCache { null }.sha256(file), cache.sha256(file))
        } finally { file.delete(); journal.delete() }
    }
}
