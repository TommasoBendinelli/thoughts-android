// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folds of protocol/review-card-kinds.md sections 3, 4 and 6 against the protocol's conformance
 * cases (protocol/fixtures/review-card-kinds.json, copied as a test resource), in the given order
 * of events and reversed.
 */
class ReviewCardKindsTest {
    private val fixture = JsonParser.parseString(
        checkNotNull(javaClass.classLoader!!.getResource("review-card-kinds/fixture.json")).readText(),
    ).asJsonObject

    private fun cases(key: String) = fixture.getAsJsonArray(key).map { it.asJsonObject }
    private fun JsonObject.events() = getAsJsonArray("events").map { it.asJsonObject }
    private fun JsonObject.projects() = getAsJsonArray("projects") ?: JsonArray()
    private fun orders(events: List<JsonObject>) = listOf("given" to events, "reversed" to events.reversed())

    @Test fun everyKindCaseFoldsAsExpectedInEitherOrder() {
        val cases = cases("kind_cases")
        assertEquals(25, cases.size)
        cases.forEach { case ->
            val name = case["name"].asString
            val expected = case.getAsJsonObject("expected")
            for ((order, events) in orders(case.events())) {
                val kind = reviewCardKind(case.getAsJsonObject("card"), events, case.projects())
                assertEquals("$name ($order) kind", expected["review_kind"].asString, kind.kind)
                assertEquals("$name ($order) source", expected["source"].asString, kind.source.wire)
                assertEquals("$name ($order) projects", expected.getAsJsonArray("project_ids").map { it.asString }, kind.projectIds)
            }
        }
    }

    @Test fun everyPassageCaseStampsAndComparesAsExpectedInEitherOrder() {
        val cases = cases("passage_cases")
        assertEquals(12, cases.size)
        cases.forEach { case ->
            val name = case["name"].asString
            val card = case.getAsJsonObject("card")
            val expected = case.getAsJsonObject("expected")
            val stamp = expected["stamp"].takeUnless { it.isJsonNull }
            assertEquals("$name stamp", stamp, reviewPassageStampOfRecord(card))
            for ((order, events) in orders(case.events())) {
                assertEquals("$name ($order) changed", expected["passage_changed"].asBoolean, reviewKindFacts(card, events).passageChanged)
            }
        }
    }

    @Test fun everyDueCaseGivesTheExpectedDueTimeInEitherOrder() {
        val cases = cases("due_cases")
        assertEquals(10, cases.size)
        cases.forEach { case ->
            val name = case["name"].asString
            val card = case.getAsJsonObject("card")
            val expected = case.getAsJsonObject("expected")
            val scheduled = case["scheduled_due_at"].takeUnless { it.isJsonNull }?.asString?.let(Instant::parse)
            for ((order, events) in orders(case.events())) {
                val kind = reviewCardKind(card, events, case.projects())
                assertEquals("$name ($order) kind", expected["review_kind"].asString, kind.kind)
                // Shown for project cards only (section 6.3).
                assertEquals("$name ($order) changed", expected["passage_changed"].asBoolean,
                    kind.isProject && reviewKindFacts(card, events).passageChanged)
                assertEquals("$name ($order) due", OffsetDateTime.parse(expected["due_at"].asString).toInstant(),
                    reviewKindDueAt(card, events, case.projects(), scheduled))
            }
        }
    }

    /** The same cases through the phone's card: the kind applied with the catalogue and the capped schedule. */
    @Test fun dueCasesGiveTheSameDueTimeOnTheCardItself() {
        cases("due_cases").forEach { case ->
            val name = case["name"].asString
            val record = case.getAsJsonObject("card")
            val expected = case.getAsJsonObject("expected")
            val scheduled = case["scheduled_due_at"].takeUnless { it.isJsonNull }?.asString?.let(Instant::parse)
            val schedule = scheduled?.let { ReviewSchedule(it, 60) }
            for ((order, events) in orders(case.events())) {
                val facts = reviewKindFacts(record, events)
                val card = ReviewCard(record["card_id"].asString, "approved", "notes-a", "a".repeat(64), 1, "b".repeat(64), "q",
                    android.graphics.RectF(), schedule, "succeeded", knowledgeSchedule = schedule,
                    ownReviewKind = facts.ownKind, latestAnswerAt = facts.latestAnswerAt, passageChanged = facts.passageChanged,
                    passageChangedAt = facts.passageChangedAt, kindDocumentId = reviewKindDocumentId(record))
                    .withReviewKind(ReviewKindCatalogue(case.projects()))
                assertEquals("$name ($order) kind", expected["review_kind"].asString, card.reviewKind)
                assertEquals("$name ($order) changed", expected["passage_changed"].asBoolean, card.changedSinceSeen)
                val due = card.schedule?.dueAt ?: card.initialDueAt
                // A phone card answered with a rating it schedules has a schedule; without one it is
                // new and due now (project_without_schedule is the fold's case above).
                if (scheduled != null) {
                    assertEquals("$name ($order) due", OffsetDateTime.parse(expected["due_at"].asString).toInstant(), due)
                }
                // The knowledge schedule is kept, so swapping back restores it.
                assertEquals("$name ($order) knowledge schedule", schedule, card.knowledgeSchedule)
                assertTrue("$name ($order) again", card === card.withReviewKind(ReviewKindCatalogue(case.projects())))
            }
        }
    }

    @Test fun aKnowledgeCardKeepsItsScheduleObjectUnchanged() {
        val schedule = ReviewSchedule(Instant.parse("2027-01-09T09:00:00Z"), 60, retained = false)
        val card = ReviewCard(UUID.randomUUID().toString(), "approved", "notes-a", "a".repeat(64), 1, "b".repeat(64), "q",
            android.graphics.RectF(), schedule, "succeeded", latestAnswerAt = Instant.parse("2026-11-10T09:00:00Z"))
        val applied = card.withReviewKind(ReviewKindCatalogue.EMPTY)
        assertEquals(schedule, applied.schedule)
        assertEquals(ReviewKindSource.DEFAULT, applied.reviewKindSource)
        assertFalse(applied.isProjectCard)
    }

    @Test fun aRetainedProjectCardComesBackAWeekAfterItsAnswer() {
        val answeredAt = Instant.parse("2026-11-10T09:00:00Z")
        val never = ReviewSchedule(answeredAt, 0, retained = true)
        val capped = reviewProjectSchedule(never, answeredAt, false, null)!!
        assertFalse(capped.retained)
        assertEquals(Instant.parse("2026-11-17T09:00:00Z"), capped.dueAt)
        // A deleted card stays deleted, and a card never answered keeps its schedule.
        val deleted = ReviewSchedule(answeredAt, 0, deleted = true)
        assertEquals(deleted, reviewProjectSchedule(deleted, answeredAt, false, null))
        assertNull(reviewProjectSchedule(null, null, false, null))
        // Without a schedule a card is new and due now, whatever it was answered with.
        assertNull(reviewProjectSchedule(null, answeredAt, false, null))
    }

    @Test fun kindEventsAreQueueActionsWithoutAnswerFields() {
        val cardId = UUID.randomUUID().toString()
        val at = Instant.parse("2026-11-17T15:02:11.204Z")
        for (kind in listOf("knowledge", "project", "follow_project")) {
            val event = reviewKindEvent(cardId, kind, at, "android-test")
            assertEquals(setOf("schema_version", "event_id", "card_id", "queue_action", "review_kind", "reviewed_at", "origin_id"), event.keySet())
            assertEquals(3, event["schema_version"].asInt)
            assertEquals(4, UUID.fromString(event["event_id"].asString).version())
            assertEquals(event["event_id"].asString.lowercase(), event["event_id"].asString)
            assertEquals("set_review_kind", event["queue_action"].asString)
            assertEquals(kind, event["review_kind"].asString)
            assertEquals("2026-11-17T15:02:11.204Z", event["reviewed_at"].asString)
            // No client reads it as an answer.
            assertTrue(reviewAnswers(cardId, listOf(event)).isEmpty())
        }
        assertTrue(runCatching { reviewKindEvent(cardId, "weekly", at, "x") }.isFailure)
        assertTrue(runCatching { reviewKindEvent("not-a-card", "project", at, "x") }.isFailure)
    }

    @Test fun theCatalogueIsAKnowledgeLibraryWithoutReviewKindAndChangesItsFingerprintWithAKind() {
        val projects = JsonParser.parseString("""[{"id":"project-a","name":"Project A","revision":3,"target":"",
            "documents":[{"document_id":"d-1","filename":"notes-a.pdf"}],"unpublished_documents":[]}]""").asJsonArray
        val old = ReviewKindCatalogue(projects)
        assertFalse(old.declaresKind("project-a"))
        projects[0].asJsonObject.addProperty("review_kind", "knowledge")
        val knowledge = ReviewKindCatalogue(projects)
        assertTrue(knowledge.declaresKind("project-a"))
        assertEquals(old.fingerprint, knowledge.fingerprint)
        projects[0].asJsonObject.addProperty("review_kind", "project")
        val project = ReviewKindCatalogue(projects)
        assertTrue(project.fingerprint != knowledge.fingerprint)
        assertEquals(ReviewCardKind("project", ReviewKindSource.PROJECT, listOf("project-a")), project.kind(null, "d-1", "other.pdf"))
        assertEquals(ReviewCardKind("knowledge", ReviewKindSource.CARD, listOf("project-a")), project.kind("knowledge", "", "notes-a.pdf"))
    }
}
