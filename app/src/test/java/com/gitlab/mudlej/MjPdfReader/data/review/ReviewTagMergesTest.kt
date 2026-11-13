// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class ReviewTagMergesTest {
    private lateinit var directory: File
    private val previous = ReviewTagMerges.file

    @Before fun setUp() {
        directory = Files.createTempDirectory("tag-merges").toFile()
        ReviewTagMerges.file = { File(directory, "tag-merges.json") }
    }

    @After fun tearDown() {
        ReviewTagMerges.file = previous
        directory.deleteRecursively()
    }

    private fun entry(id: String, rating: String, tags: List<String> = emptyList()) = ReviewHistoryEntry(
        eventId = id, rating = rating, reviewedAt = Instant.EPOCH, originId = "test", revision = 1, updatedAt = null,
        retractedEventId = null, retracted = false, effective = true, pendingSync = false, sourcePath = null,
        reviewTags = tags,
    )

    private fun merge(id: String, at: String, to: String, vararg from: String) =
        ReviewTagMerges.Merge(id, from.toList(), to, at, "test")

    @Test fun laterMergesCarryEarlierOnesAlongAndNeverLoop() {
        val mapping = ReviewTagMerges.mappingOf(listOf(
            merge("1", "2026-10-05T10:00:00Z", "good", "Good", "correct"),
            merge("2", "2026-10-05T11:00:00Z", "known", "good"),
            // "Good" already reads as "known", so merging "known" into it changes nothing and cannot loop.
            merge("3", "2026-10-05T12:00:00Z", "Good", "known"),
        ))
        assertEquals(mapOf("Good" to "known", "correct" to "known", "good" to "known"), mapping)
        assertTrue(mapping.values.none { it in mapping.keys })
    }

    @Test fun recordedMergesApplyToAnswersAndSurviveReloading() {
        ReviewTagMerges.record(listOf("Good", "correct", "good"), "good", "phone", Instant.parse("2026-10-05T10:00:00Z"))
        assertEquals(listOf("good", "skip"), ReviewTagMerges.apply(listOf("Good", "skip", "correct")))
        assertEquals(listOf("good"), entry("e", "custom", listOf("correct")).effectiveTags())
        // Answers recorded before tags existed derive "correct", which reads merged too.
        assertEquals(listOf("good"), entry("old", "good").effectiveTags())
        val saved = JsonParser.parseString(File(directory, "tag-merges.json").readText()).asJsonObject
        assertEquals(1, saved.getAsJsonArray("merges").size())
    }

    @Test fun serverMergesAreAddedOnceAndBadOnesIgnored() {
        val payload = merge("server-1", "2026-10-05T10:00:00Z", "wrong", "oops").payload()
        ReviewTagMerges.installServer(listOf(payload, payload, JsonParser.parseString("{\"merge_id\":\"x\"}").asJsonObject))
        ReviewTagMerges.installServer(listOf(payload))
        assertEquals(listOf("server-1"), ReviewTagMerges.merges().map { it.id })
        assertEquals("wrong", ReviewTagMerges.resolve("oops"))
    }
}
