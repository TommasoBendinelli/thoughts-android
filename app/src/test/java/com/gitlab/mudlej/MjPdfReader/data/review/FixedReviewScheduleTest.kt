// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FixedReviewScheduleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-09-05T11:00:00Z")

    @Test fun explicitDelaysStayExactForNewLearningAndMatureCards() {
        val previous = listOf(null, ReviewSchedule(now, 365),
            ReviewSchedule(now, 0, learningStep = 1, relearning = true, previousIntervalDays = 30))
        for ((rating, seconds) in listOf(ReviewRating.NEXT_WEEK to 604800L,
            ReviewRating.NEXT_DAY to 86400L, ReviewRating.TEN_MINUTES to 600L)) {
            for (old in previous) {
                val result = ReviewStore.nextSchedule(old, rating, now)
                assertEquals(now.plusSeconds(seconds), result.dueAt)
                assertEquals(now.plusSeconds(seconds), ReviewStore.nextSchedule(result, rating, now).dueAt)
                assertTrue(result.fixedInterval)
                assertFalse(result.deleted)
                assertFalse(result.retained)
            }
        }
    }

    @Test fun tenMinutesDoesNotReturnEarlyThroughLearnAheadOrDueCounts() {
        val fixture = fixture()
        fixture.store.record(fixture.id, ReviewRating.TEN_MINUTES, now).getOrThrow()
        val store = fixture.reopen()
        assertTrue(store.dueCards(now.plusSeconds(599)).isEmpty())
        assertEquals(0, store.summary(now).counts.total)
        assertEquals(now.plusSeconds(600), store.summary(now).nextDueAt)
        assertEquals(ReviewBrowserState.FUTURE, store.browserCards(now).single().state)
        assertEquals(fixture.id, store.dueCards(now.plusSeconds(600)).single().id)
    }

    @Test fun retainedSurvivesReloadAndSyncedEventReplayAndCanBeUndone() {
        val fixture = fixture()
        val event = fixture.store.record(fixture.id, ReviewRating.RETAINED, now).getOrThrow()
        val store = fixture.reopen()
        assertTrue(store.dueCards(now.plusSeconds(86400L * 3650)).isEmpty())
        assertEquals(0, store.summary(now).activeCount)
        val card = store.browserCards(now).single()
        assertEquals(ReviewBrowserState.RETAINED, card.state)
        assertNull(card.dueAt)
        assertFalse(card.card.schedule!!.deleted)
        assertEquals("Saved question", card.card.question)
        val remote = fixture()
        File(remote.incoming, "cards/server/${remote.id}.json").delete()
        File(remote.incoming, "cards/server/${fixture.id}.json").writeText(fixture.cardFile.readText())
        File(remote.incoming, "events/server/${event.name}").apply { parentFile.mkdirs(); writeText(event.readText()) }
        assertEquals(ReviewBrowserState.RETAINED, remote.reopen().browserCards(now).single().state)
        assertTrue(remote.reopen().dueCards(now.plusSeconds(86400L * 3650)).isEmpty())
        store.retractRating(fixture.id, event.nameWithoutExtension, now.plusSeconds(1)).getOrThrow()
        assertEquals(fixture.id, fixture.reopen().dueCards(now.plusSeconds(1)).single().id)
    }

    @Test fun fixedDelayEventsRoundTripWithoutChangingPreviousRatingSemantics() {
        val fixture = fixture()
        val event = fixture.store.record(fixture.id, ReviewRating.NEXT_WEEK, now).getOrThrow()
        assertEquals("next_week", JsonParser.parseString(event.readText()).asJsonObject["rating"].asString)
        assertEquals(now.plusSeconds(604800), fixture.reopen().card(fixture.id)!!.schedule!!.dueAt)
        assertTrue(fixture.reopen().dueCards(now.plusSeconds(604799)).isEmpty())
        assertEquals(1, fixture.reopen().dueCards(now.plusSeconds(604800)).size)
        assertEquals(3, ReviewStore.nextSchedule(null, ReviewRating.GOOD, now, 1).intervalDays)
        assertEquals(7, ReviewStore.nextSchedule(null, ReviewRating.EASY, now, 2).intervalDays)
    }

    @Test fun neverKeepsEachMarkAndIssuesAcrossReloadAndUndo() {
        for (mark in listOf("correct", "wrong", "skip")) {
            // Skip carries no tag, so a card only ever skipped has no mark.
            val expected = mark.takeUnless { it == "skip" }
            val fixture = fixture()
            val event = fixture.store.record(fixture.id, ReviewRating.RETAINED, now, reviewAction = mark).getOrThrow()
            val browser = fixture.reopen().browserCards(now).single()
            assertEquals(expected, browser.currentMark())
            assertNull(browser.dueAt)
            assertTrue(browser.card.schedule!!.retained)
            assertTrue(fixture.reopen().dueCards(now.plusSeconds(86400L * 3650)).isEmpty())
            val withIssue = fixture.reopen().browserCards(now, mapOf("${browser.card.pdfStem}.pdf" to "c".repeat(64))).single()
            assertTrue(withIssue.hasIssues)
            assertEquals(expected, withIssue.currentMark())
            assertTrue(withIssue.card.schedule!!.retained)
            fixture.store.retractRating(fixture.id, event.nameWithoutExtension, now.plusSeconds(1)).getOrThrow()
            assertNull(fixture.reopen().browserCards(now).single().currentMark())
        }
    }

    @Test fun finiteIntervalReschedulesNeverAndPreservesHistory() {
        val fixture = fixture()
        fixture.store.record(fixture.id, ReviewRating.RETAINED, now).getOrThrow()
        val due = now.plusSeconds(3600)
        fixture.store.record(fixture.id, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(1), due, "skip").getOrThrow()
        val browser = fixture.reopen().browserCards(now).single()
        // The older skip answer reschedules the card but keeps the mark of the answer before it.
        assertEquals("correct", browser.currentMark())
        assertEquals(due, browser.dueAt)
        assertFalse(browser.card.schedule!!.retained)
        assertEquals(2, browser.history.size)
        assertEquals(fixture.id, fixture.reopen().dueCards(due).single().id)
        val events = fixture.events.walkTopDown().filter { it.isFile }.map {
            JsonParser.parseString(it.readText()).asJsonObject
        }.toList()
        assertFalse(ReviewStore.foldEvents(events)[fixture.id]!!.retained)
    }

    @Test fun intervalSelectionFromDesktopChangesDueWithoutAddingAReview() {
        val fixture = fixture()
        fixture.store.record(fixture.id, ReviewRating.CUSTOM_INTERVAL, now, now.plus(14, java.time.temporal.ChronoUnit.DAYS), "correct").getOrThrow()
        fun addSelection(id: String, at: Instant, unit: String, amount: Int?, due: Instant?) {
            val payload = com.google.gson.JsonObject().apply {
                addProperty("schema_version", 3)
                addProperty("event_id", id)
                addProperty("card_id", fixture.id)
                addProperty("interval_action", "set")
                addProperty("interval_unit", unit)
                if (amount != null) addProperty("interval_amount", amount)
                if (due != null) addProperty("scheduled_due_at", due.toString())
                addProperty("reviewed_at", at.toString())
            }
            File(fixture.incoming, "events/server/$id.json").apply { parentFile.mkdirs(); writeText(payload.toString()) }
        }
        val later = now.plusMillis(1)
        val due = later.plus(21, java.time.temporal.ChronoUnit.DAYS)
        addSelection(UUID.randomUUID().toString(), later, "weeks", 3, due)
        var browser = fixture.reopen().browserCards(now).single()
        assertEquals(1, browser.reviewCount)
        assertEquals("correct", browser.currentMark())
        assertEquals(due, browser.dueAt)
        assertEquals("set", browser.history.last().intervalAction)
        assertTrue(browser.history.last().effective)

        addSelection(UUID.randomUUID().toString(), later.plusMillis(1), "never", null, null)
        browser = fixture.reopen().browserCards(now).single()
        assertEquals(1, browser.reviewCount)
        assertEquals("correct", browser.currentMark())
        assertTrue(browser.card.schedule!!.retained)
        assertNull(browser.dueAt)

        val finalDue = later.plusMillis(2).plus(2, java.time.temporal.ChronoUnit.DAYS)
        addSelection(UUID.randomUUID().toString(), later.plusMillis(2), "days", 2, finalDue)
        browser = fixture.reopen().browserCards(now).single()
        assertEquals(1, browser.reviewCount)
        assertEquals(finalDue, browser.dueAt)
        assertFalse(browser.card.schedule!!.retained)
    }

    @Test fun intervalOnlyHistoryDoesNotBecomeACorrectMark() {
        val wrong = ReviewHistoryEntry("wrong", "again", now, "android", 1, now, null,
            retracted = false, effective = true, pendingSync = false, sourcePath = null)
        val interval = ReviewHistoryEntry("interval", null, now.plusSeconds(1), "desktop", 1, now.plusSeconds(1), null,
            retracted = false, effective = true, pendingSync = false, sourcePath = null, intervalAction = "set")
        assertNull(reviewCurrentMark(listOf(interval)))
        assertEquals("wrong", reviewCurrentMark(listOf(wrong, interval)))
    }

    @Test fun customScheduleSurvivesReloadSyncAndUndoWithoutLearnAhead() {
        val fixture = fixture()
        val due = now.plusSeconds(37 * 60)
        val event = fixture.store.record(fixture.id, ReviewRating.CUSTOM_INTERVAL, now, due).getOrThrow()
        val payload = JsonParser.parseString(event.readText()).asJsonObject
        assertEquals(due.toString(), payload["scheduled_due_at"].asString)
        assertEquals(due, fixture.reopen().card(fixture.id)!!.schedule!!.dueAt)
        assertEquals(due, ReviewStore.foldEvents(listOf(payload))[fixture.id]!!.dueAt)
        assertTrue(fixture.reopen().dueCards(due.minusSeconds(1)).isEmpty())
        assertEquals(ReviewBrowserState.FUTURE, fixture.reopen().browserCards(now).single().state)
        assertEquals(fixture.id, fixture.reopen().dueCards(due).single().id)
        val remote = fixture()
        File(remote.incoming, "cards/server/${remote.id}.json").delete()
        File(remote.incoming, "cards/server/${fixture.id}.json").writeText(fixture.cardFile.readText())
        File(remote.incoming, "events/server/${event.name}").apply { parentFile.mkdirs(); writeText(event.readText()) }
        assertEquals(due, remote.reopen().card(fixture.id)!!.schedule!!.dueAt)
        fixture.store.retractRating(fixture.id, event.nameWithoutExtension, now.plusSeconds(1)).getOrThrow()
        assertNull(fixture.reopen().card(fixture.id)!!.schedule)
    }

    @Test fun invalidCustomEventsCannotCreateAnAccidentalSchedule() {
        val fixture = fixture()
        assertTrue(fixture.store.record(fixture.id, ReviewRating.CUSTOM_INTERVAL, now).isFailure)
        assertTrue(fixture.store.record(fixture.id, ReviewRating.CUSTOM_INTERVAL, now, now).isFailure)
        val event = fixture.store.record(fixture.id, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(60)).getOrThrow()
        val payload = JsonParser.parseString(event.readText()).asJsonObject
        payload.remove("scheduled_due_at")
        event.writeText(payload.toString())
        assertNull(fixture.reopen().card(fixture.id)!!.schedule)
        assertTrue(ReviewStore.foldEvents(listOf(payload)).isEmpty())
    }

    @Test fun suggestionsArePerCardAndFollowReloadedHistoryAndUndo() {
        val fixture = fixture()
        val fresh = ReviewInterval(2, ReviewIntervalUnit.WEEKS)
        assertEquals(fresh, fixture.store.suggestedInterval(fixture.id))
        val firstDue = fresh.dueAt(now)
        fixture.store.record(fixture.id, ReviewRating.CUSTOM_INTERVAL, now, firstDue).getOrThrow()
        assertEquals(ReviewInterval(4, ReviewIntervalUnit.WEEKS), fixture.reopen().suggestedInterval(fixture.id))
        assertEquals(fresh, fixture.reopen().suggestedInterval(UUID.randomUUID().toString()))
        val later = firstDue.plusSeconds(86400 * 3)
        val customDue = ReviewInterval(java.math.BigDecimal("2.5"), ReviewIntervalUnit.WEEKS).dueAt(later)
        val event = fixture.store.record(fixture.id, ReviewRating.CUSTOM_INTERVAL, later, customDue).getOrThrow()
        assertEquals(ReviewInterval(5, ReviewIntervalUnit.WEEKS), fixture.reopen().suggestedInterval(fixture.id))
        fixture.store.retractRating(fixture.id, event.nameWithoutExtension, later.plusSeconds(1)).getOrThrow()
        assertEquals(ReviewInterval(4, ReviewIntervalUnit.WEEKS), fixture.reopen().suggestedInterval(fixture.id))
    }

    @Test fun scopedTimingAppliesToFutureCardsAndOnlyTheChosenDocumentOrProjectAndUndoesTogether() {
        for (scope in ReviewSkipScope.entries) {
            val fixture = fixture()
            fun add(stem: String): String {
                val id = UUID.randomUUID().toString()
                File(fixture.cardFile.parentFile, "$id.json").writeText(
                    fixture.cardFile.readText().replace(fixture.id, id).replace("paper", stem))
                return id
            }
            val sameDocument = add("paper")
            val sameProject = add("appendix")
            val otherProject = add("other")
            fixture.store.record(sameDocument, ReviewRating.NEXT_WEEK, now.minusSeconds(1)).getOrThrow()
            val expected = when (scope) {
                ReviewSkipScope.CARD -> setOf(fixture.id)
                ReviewSkipScope.DOCUMENT -> setOf(fixture.id, sameDocument)
                ReviewSkipScope.PROJECT -> setOf(fixture.id, sameDocument, sameProject)
            }
            val due = now.plusSeconds(1200)
            val batch = fixture.store.recordScoped(fixture.id, scope, ReviewRating.CUSTOM_INTERVAL,
                now, due, "error", mapOf("paper.pdf" to "project", "appendix.pdf" to "project",
                    "other.pdf" to "other-project")).getOrThrow()
            assertEquals(expected, batch.keys)
            expected.forEach { assertEquals(due, fixture.reopen().card(it)!!.schedule!!.dueAt) }
            assertNull(fixture.reopen().card(otherProject)!!.schedule)
            assertFalse(fixture.reopen().dueCards(now).any { it.id in expected })
            fixture.store.retractRatings(batch).getOrThrow()
            assertNull(fixture.reopen().card(fixture.id)!!.schedule)
            assertEquals(now.minusSeconds(1).plusSeconds(604800), fixture.reopen().card(sameDocument)!!.schedule!!.dueAt)
        }
    }

    @Test fun missingProjectFailsBeforeWritingAndNeverCanBeUndoneForWholeDocument() {
        val fixture = fixture()
        val second = UUID.randomUUID().toString()
        File(fixture.cardFile.parentFile, "$second.json").writeText(fixture.cardFile.readText().replace(fixture.id, second))
        assertTrue(fixture.store.recordScoped(fixture.id, ReviewSkipScope.PROJECT,
            ReviewRating.RETAINED, now, null, "correct").isFailure)
        assertNull(fixture.reopen().card(fixture.id)!!.schedule)
        val batch = fixture.store.recordScoped(fixture.id, ReviewSkipScope.DOCUMENT,
            ReviewRating.RETAINED, now, null, "correct").getOrThrow()
        assertEquals(setOf(fixture.id, second), batch.keys)
        assertTrue(fixture.reopen().dueCards(now).isEmpty())
        assertTrue(fixture.reopen().card(second)!!.schedule!!.retained)
        fixture.store.retractRatings(batch).getOrThrow()
        assertEquals(2, fixture.reopen().dueCards(now).size)
    }

    @Test fun scopedNeverCanLeaveFutureCardsUntouched() {
        val fixture = fixture()
        val future = UUID.randomUUID().toString()
        File(fixture.cardFile.parentFile, "$future.json").writeText(
            fixture.cardFile.readText().replace(fixture.id, future))
        val futureDue = now.plusSeconds(7 * 86400)
        fixture.store.record(future, ReviewRating.CUSTOM_INTERVAL, now, futureDue).getOrThrow()

        val dueOnly = fixture.store.recordScoped(fixture.id, ReviewSkipScope.DOCUMENT,
            ReviewRating.RETAINED, now, null, "skip", dueOnly = true).getOrThrow()
        assertEquals(setOf(fixture.id), dueOnly.keys)
        assertEquals(futureDue, fixture.reopen().card(future)!!.schedule!!.dueAt)
        fixture.store.retractRatings(dueOnly).getOrThrow()

        val all = fixture.store.recordScoped(fixture.id, ReviewSkipScope.DOCUMENT,
            ReviewRating.RETAINED, now, null, "skip", dueOnly = false).getOrThrow()
        assertEquals(setOf(fixture.id, future), all.keys)
    }

    private fun fixture(): Fixture {
        val incoming = temporary.newFolder()
        val id = UUID.randomUUID().toString()
        val cardFile = File(incoming, "cards/server/$id.json").apply {
            parentFile.mkdirs()
            writeText("""{"schema_version":3,"card_type":"visual_region","card_id":"$id","status":"approved","revision":1,"origin_id":"server","primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"Saved question"},"reanchor":{"state":"succeeded","strategy":"source_markers_v2"}}""")
        }
        return Fixture(temporary.newFolder(), incoming, temporary.newFolder(), temporary.newFolder(), id, cardFile)
    }
    private data class Fixture(val root: File, val incoming: File, val events: File,
        val outgoing: File, val id: String, val cardFile: File) {
        val store get() = reopen()
        fun reopen() = ReviewStore(null, root, incoming, events, outgoing)
    }
}
