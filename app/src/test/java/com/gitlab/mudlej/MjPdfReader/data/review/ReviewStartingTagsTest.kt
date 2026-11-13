// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A review starts with the tags of the card's last answer still in effect, or with none. */
class ReviewStartingTagsTest {
    private val start = Instant.parse("2026-10-10T10:00:00Z")

    private fun answer(
        id: String,
        minute: Long,
        vararg tags: String,
        rating: String? = "custom_interval",
        recorded: Boolean = true,
        retracted: Boolean = false,
        effective: Boolean = true,
        retractedEventId: String? = null,
        queueAction: String? = null,
        reviewAction: String? = null,
        intervalAction: String? = null,
    ) = ReviewHistoryEntry(
        eventId = id, rating = rating, reviewedAt = start.plusSeconds(60 * minute), originId = "phone",
        revision = 1, updatedAt = null, retractedEventId = retractedEventId, retracted = retracted,
        effective = effective, pendingSync = false, sourcePath = null, queueAction = queueAction,
        reviewAction = reviewAction, intervalAction = intervalAction,
        reviewTags = tags.toList(), reviewTagsRecorded = recorded,
    )

    private fun starting(vararg history: ReviewHistoryEntry, mapping: Map<String, String> = emptyMap()) =
        reviewCurrentTags(history.toList(), mapping)

    @Test fun aCardNeverAnsweredStartsWithNoTag() {
        assertNull(starting())
        // Only skips, undone answers, undo events and deletions: still never answered.
        assertNull(starting(
            answer("skip", 1, rating = null, queueAction = "skip"),
            answer("undone", 2, "correct", retracted = true),
            answer("undo", 3, rating = null, retractedEventId = "undone"),
            answer("superseded", 4, "correct", effective = false),
            answer("delete", 5, rating = "delete", recorded = false),
            answer("interval", 6, rating = null, intervalAction = "set", recorded = false),
        ))
    }

    @Test fun aCardAnsweredStartsWithItsLastAnswersTags() {
        assertEquals(listOf("wrong", "notation"), starting(
            answer("b", 20, "wrong", "notation"),
            answer("a", 10, "correct"),
        ))
        // The last answer had no tags: the card starts with none, not with an earlier answer's.
        assertEquals(emptyList<String>(), starting(
            answer("a", 10, "correct"),
            answer("b", 20),
        ))
    }

    @Test fun undoingTheLastAnswerGoesBackToTheOneBefore() {
        assertEquals(listOf("correct"), starting(
            answer("a", 10, "correct"),
            answer("b", 20, "wrong", retracted = true),
            answer("undo-b", 21, rating = null, retractedEventId = "b"),
        ))
        assertNull(starting(
            answer("a", 10, "correct", retracted = true),
            answer("undo-a", 11, rating = null, retractedEventId = "a"),
        ))
    }

    @Test fun mergedTagsStartUnderTheirNewName() {
        assertEquals(listOf("correct", "hard"), starting(
            answer("a", 10, "Good", "correct", "Tough"),
            mapping = mapOf("Good" to "correct", "Tough" to "hard"),
        ))
    }

    @Test fun skipsKeepTheTagsOfTheAnswerBefore() {
        assertEquals(listOf("hard"), starting(
            answer("a", 10, "hard"),
            answer("skip", 20, rating = null, queueAction = "skip"),
            answer("undo-skip", 21, rating = null, queueAction = "undo_skip"),
            // Earlier skips were recorded as a "skip" answer or tag.
            answer("legacy-action", 22, rating = "good", reviewAction = "skip", recorded = false),
            answer("legacy-tag", 23, "skip"),
        ))
    }

    @Test fun answersFromBeforeTagsReadTheirTagFromTheRating() {
        assertEquals(listOf("wrong"), starting(answer("a", 10, rating = "again", recorded = false)))
        assertEquals(listOf("correct"), starting(answer("a", 10, rating = "good", recorded = false)))
    }

    @Test fun eachCardOfAScopedAnswerStartsWithItsTags() {
        // A document or project answer records one event per card, sharing instant, due time and tags.
        val due = start.plusSeconds(7 * 86400)
        fun batch(card: String) = answer("batch-$card", 10, "wrong", "notation").copy(scheduledDueAt = due)
        assertEquals(listOf("wrong", "notation"), starting(batch("1")))
        assertEquals(listOf("wrong", "notation"), starting(answer("earlier", 5, "correct"), batch("2")))
    }

    @Test fun anEmptyTagListIsRecordedAsChosenAndAMissingOneIsNot() {
        assertTrue(JsonParser.parseString("""{"review_tags":[]}""").asJsonObject.recordsReviewTags())
        assertFalse(JsonParser.parseString("""{"rating":"good"}""").asJsonObject.recordsReviewTags())
        // An untagged answer counts toward no tag.
        assertEquals(emptyMap<String, Int>(), reviewTagUsageOf(listOf(listOf(answer("a", 10))), emptyMap()))
    }
}
