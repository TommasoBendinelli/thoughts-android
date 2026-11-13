// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** The tag sheet's counts: cards whose current tags include each tag, under merged names. */
class ReviewTagUsageTest {
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
    ) = ReviewHistoryEntry(
        eventId = id, rating = rating, reviewedAt = start.plusSeconds(60 * minute), originId = "phone",
        revision = 1, updatedAt = null, retractedEventId = retractedEventId, retracted = retracted,
        effective = effective, pendingSync = false, sourcePath = null, queueAction = queueAction,
        scheduledDueAt = start.plusSeconds(60 * minute + 86400),
        reviewTags = tags.toList(), reviewTagsRecorded = recorded,
    )

    private fun usage(vararg cards: List<ReviewHistoryEntry>, mapping: Map<String, String> = emptyMap()) =
        reviewTagUsageOf(cards.toList(), mapping)

    @Test fun eachCardCountsOnceForEveryCurrentTag() {
        assertEquals(mapOf("correct" to 2, "notation" to 2, "hard" to 1), usage(
            listOf(answer("a", 1, "correct", "notation")),
            listOf(answer("b", 1, "correct")),
            listOf(answer("c", 1, "notation", "hard", "notation")),
        ))
    }

    @Test fun onlyTheLatestAnswerCountsNotTheHistory() {
        // Answered "wrong" three times, then "correct": the card is correct now; wrong, which no
        // card has now, is still listed.
        assertEquals(mapOf("correct" to 1, "wrong" to 0), usage(listOf(
            answer("a", 1, "wrong"), answer("b", 2, "wrong"), answer("c", 3, "wrong"), answer("d", 4, "correct"),
        )))
        // The latest answer had no tags: the card counts toward none.
        assertEquals(mapOf("correct" to 0), usage(listOf(answer("a", 1, "correct"), answer("b", 2))))
    }

    @Test fun undoingAnAnswerMovesTheCardBackToTheTagsBefore() {
        val first = answer("a", 1, "wrong")
        val second = answer("b", 2, "correct")
        assertEquals(mapOf("correct" to 1, "wrong" to 0), usage(listOf(first, second)))
        assertEquals(mapOf("wrong" to 1), usage(listOf(first, second.copy(retracted = true),
            answer("undo-b", 3, rating = null, retractedEventId = "b"))))
        // Undoing the only answer: the card is unanswered again and counts for nothing.
        assertEquals(emptyMap<String, Int>(), usage(listOf(first.copy(retracted = true),
            answer("undo-a", 3, rating = null, retractedEventId = "a"))))
        // An answer no longer in effect (superseded on the server) does not count either.
        assertEquals(mapOf("wrong" to 1), usage(listOf(first, second.copy(effective = false))))
    }

    @Test fun aSkipKeepsTheCardsTags() {
        assertEquals(mapOf("hard" to 1), usage(listOf(
            answer("a", 1, "hard"),
            answer("skip", 2, rating = null, queueAction = "skip"),
            // An earlier skip recorded as a "skip" answer.
            answer("old-skip", 3, "skip"),
        )))
    }

    @Test fun aCardNeverAnsweredCountsForNothing() {
        assertEquals(emptyMap<String, Int>(), usage(emptyList(), listOf(answer("s", 1, rating = null, queueAction = "skip"))))
        assertEquals(emptyMap<String, Int>(), reviewTagUsageOf(emptyList(), mapOf("Good" to "correct")))
    }

    @Test fun answersFromBeforeTagsCountByTheirRating() {
        assertEquals(mapOf("wrong" to 1, "correct" to 1), usage(
            listOf(answer("a", 1, rating = "again", recorded = false)),
            listOf(answer("b", 1, rating = "good", recorded = false)),
        ))
    }

    @Test fun aDeletedCardCountsForNothingButItsTagsStayListed() {
        val kept = listOf(answer("a", 1, "correct"))
        val deleted = listOf(answer("b", 1, "basil"), answer("c", 2, rating = "delete"))
        assertEquals(mapOf("correct" to 1, "basil" to 0), reviewTagUsageOf(listOf(kept), emptyMap(), listed = listOf(kept, deleted)))
    }

    @Test fun mergedTagsCountUnderTheirNewNameAndOnlyOncePerCard() {
        assertEquals(mapOf("correct" to 3, "hard" to 1), usage(
            listOf(answer("a", 1, "Good")),
            listOf(answer("b", 1, "Good", "correct")),
            listOf(answer("c", 1, "correct")),
            listOf(answer("d", 1, "Tough")),
            mapping = mapOf("Good" to "correct", "Tough" to "hard"),
        ))
    }
}
