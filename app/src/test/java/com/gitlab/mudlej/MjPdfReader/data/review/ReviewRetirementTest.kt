// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonParser
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold of protocol/review-card-retire.md section 4 against the protocol's conformance cases
 * (protocol/fixtures/review-card-retire.json, copied as a test resource), in the given order and reversed.
 */
class ReviewRetirementTest {
    private val fixture = JsonParser.parseString(
        checkNotNull(javaClass.classLoader!!.getResource("review-card-retire/fixture.json")).readText(),
    ).asJsonObject

    @Test fun everyConformanceCaseFoldsAsExpectedInEitherOrder() {
        val cases = fixture.getAsJsonArray("cases").map { it.asJsonObject }
        assertEquals(24, cases.size)
        cases.forEach { case ->
            val name = case["name"].asString
            val cardId = case["card_id"].asString
            val events = case.getAsJsonArray("events").map { it.asJsonObject }
            val expected = case.getAsJsonObject("expected")
            val state = when (expected["state"].asString) {
                "active" -> ReviewRetireState.ACTIVE
                "retired" -> ReviewRetireState.RETIRED
                "deleted" -> ReviewRetireState.DELETED
                else -> error("unknown state in $name")
            }
            val retiredAt = expected["retired_at"].takeUnless { it.isJsonNull }?.asString?.let(Instant::parse)
            val eventId = expected["retire_event_id"].takeUnless { it.isJsonNull }?.asString
            for ((order, list) in listOf("given" to events, "reversed" to events.reversed())) {
                assertEquals("$name ($order)", ReviewRetirement(state, retiredAt, eventId), reviewRetirement(cardId, list))
                // The Library-wide fold agrees for this card.
                assertEquals("$name ($order) library", if (state == ReviewRetireState.RETIRED) ReviewRetirement(state, retiredAt, eventId) else null,
                    reviewRetiredCards(list)[cardId])
            }
        }
    }

    @Test fun retiredAtKeepsTheEventsOwnTimeText() {
        val case = fixture.getAsJsonArray("cases").map { it.asJsonObject }.single { it["name"].asString == "fractional_seconds_order" }
        val folded = reviewRetirement(case["card_id"].asString, case.getAsJsonArray("events").map { it.asJsonObject })
        assertEquals(case.getAsJsonObject("expected")["retired_at"].asString, folded.retiredAt.toString())
    }

    @Test fun retireEventsAreQueueActionsWithoutAnswerFields() {
        val cardId = UUID.randomUUID().toString()
        val at = Instant.parse("2026-10-27T14:02:11.204Z")
        val retire = reviewRetireEvent(cardId, ReviewRetire.RETIRE, at, "android-test")
        assertEquals(3, retire["schema_version"].asInt)
        assertEquals(4, UUID.fromString(retire["event_id"].asString).version())
        assertEquals(retire["event_id"].asString.lowercase(), retire["event_id"].asString)
        assertEquals(cardId, retire["card_id"].asString)
        assertEquals("retire", retire["queue_action"].asString)
        assertEquals("2026-10-27T14:02:11.204Z", retire["reviewed_at"].asString)
        assertEquals("android-test", retire["origin_id"].asString)
        listOf("rating", "review_tags", "skip_id", "skip_until", "interval_action", "retracted_event_id", "undone_event_id")
            .forEach { assertFalse(it, retire.has(it)) }
        val undo = reviewRetireEvent(cardId, ReviewRetire.UNDO, at.plusSeconds(5), "android-test", retire["event_id"].asString)
        assertEquals("undo_retire", undo["queue_action"].asString)
        assertEquals(retire["event_id"].asString, undo["undone_event_id"].asString)
        assertTrue(runCatching { reviewRetireEvent(cardId, ReviewRetire.UNDO, at, "x") }.isFailure)
        assertTrue(runCatching { reviewRetireEvent(cardId, ReviewRetire.RETIRE, at, "x", retire["event_id"].asString) }.isFailure)
        assertTrue(runCatching { reviewRetireEvent("not-a-card", ReviewRetire.RETIRE, at, "x") }.isFailure)
        assertEquals(ReviewRetireState.ACTIVE, reviewRetirement(cardId, listOf(retire, undo)).state)
    }

    @Test fun retireEventsAreNeverAnswersInTheHistory() {
        val at = Instant.parse("2026-10-27T10:00:00Z")
        fun entry(action: String) = ReviewHistoryEntry("e-$action", null, at, "phone", null, null, null,
            retracted = false, effective = false, pendingSync = false, sourcePath = null, queueAction = action)
        val answer = ReviewHistoryEntry("answer", "good", at.minusSeconds(60), "phone", 1, null, null,
            retracted = false, effective = true, pendingSync = false, sourcePath = null,
            reviewTags = listOf("Recall: 3"), reviewTagsRecorded = true)
        val history = listOf(answer, entry("retire"), entry("restore"), entry("undo_retire"))
        history.drop(1).forEach { assertFalse(it.isAnswerInEffect()) }
        assertEquals(listOf("Recall: 3"), reviewCurrentTags(history, emptyMap()))
        assertEquals(mapOf("Recall: 3" to 1), reviewTagUsageOf(listOf(history), emptyMap()))
    }
}
