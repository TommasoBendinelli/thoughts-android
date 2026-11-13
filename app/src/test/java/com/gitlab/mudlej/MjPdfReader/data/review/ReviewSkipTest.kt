// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewSkipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-09-08T10:00:00Z")
    private val tomorrow = now.plusSeconds(86400)

    @Test fun newCardSkipPersistsAndExpiresWithoutCreatingAReview() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val before = fixture.store().browserCards(now).single()
        val skip = fixture.store().skip(id, ReviewSkipScope.CARD, tomorrow, now = now).getOrThrow()
        val store = fixture.store()
        assertTrue(store.dueCards(tomorrow.minusSeconds(1)).isEmpty())
        assertEquals(0, store.summary(now).counts.total)
        assertEquals(tomorrow, store.summary(now).nextDueAt)
        val after = store.browserCards(now).single()
        assertEquals(ReviewBrowserState.SKIPPED, after.state)
        assertEquals(before.dueAt, after.dueAt)
        assertNull(after.card.schedule)
        assertEquals(before.history, after.history.dropLast(1))
        assertEquals("skip", after.history.last().queueAction)
        assertEquals(now, after.history.last().reviewedAt)
        assertEquals(tomorrow, after.history.last().skipUntil)
        assertNull(after.history.last().rating)
        assertEquals(0, after.reviewCount)
        assertNull(after.lastReviewedAt)
        assertEquals(1, store.pendingChanges().size)
        assertEquals(ReviewInterval(2, ReviewIntervalUnit.WEEKS), store.suggestedInterval(id))
        assertEquals(id, store.dueCards(tomorrow).single().id)
        assertEquals(ReviewBrowserState.NEW, store.browserCards(tomorrow).single().state)
        assertEquals(1, store.summary(tomorrow).counts.newCount)
        store.undoSkip(skip.id).getOrThrow()
        assertEquals(id, fixture.store().dueCards(now).single().id)
    }

    @Test fun reviewedCardAppendsSkipHistoryAndKeepsItsScheduleAndDoubledSuggestion() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val reviewedAt = now.minusSeconds(14 * 86400)
        fixture.store().record(id, ReviewRating.CUSTOM_INTERVAL, reviewedAt, now).getOrThrow()
        val before = fixture.store().browserCards(now).single()
        fixture.store().skip(id, ReviewSkipScope.CARD, tomorrow, now = now).getOrThrow()
        val after = fixture.store().browserCards(now).single()
        assertEquals(before.card.schedule, after.card.schedule)
        assertEquals(before.history, after.history.dropLast(1))
        assertEquals("skip", after.history.last().queueAction)
        assertEquals(now, after.history.last().reviewedAt)
        assertEquals(tomorrow, after.history.last().skipUntil)
        assertNull(after.history.last().rating)
        assertEquals(before.reviewCount, after.reviewCount)
        assertEquals(before.lastReviewedAt, after.lastReviewedAt)
        assertEquals(before.lapseCount, after.lapseCount)
        assertEquals(ReviewInterval(4, ReviewIntervalUnit.WEEKS), fixture.store().suggestedInterval(id))
        assertEquals(2, fixture.events.walkTopDown().count { it.extension == "json" })
    }

    @Test fun learningCardCannotReturnThroughLearnAheadBeforeSkipExpires() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        fixture.store().record(id, ReviewRating.AGAIN, now).getOrThrow()
        fixture.store().skip(id, ReviewSkipScope.CARD, tomorrow, now = now).getOrThrow()
        val store = fixture.store()
        assertTrue(store.dueCards(tomorrow.minusSeconds(1)).isEmpty())
        assertEquals(0, store.summary(tomorrow.minusSeconds(1)).counts.total)
        assertEquals(tomorrow, store.summary(now).nextDueAt)
        assertEquals(id, store.dueCards(tomorrow).single().id)
    }

    @Test fun queueStartsWithTheOldestDueCardAndNewCardsFollow() {
        val fixture = fixture()
        val fresh = fixture.addCard("paper")
        val dueToday = fixture.addCard("paper")
        val dueLastWeek = fixture.addCard("paper")
        val learning = fixture.addCard("paper")
        fixture.store().record(dueToday, ReviewRating.CUSTOM_INTERVAL, now.minusSeconds(3 * 86400), now.minusSeconds(3600)).getOrThrow()
        fixture.store().record(dueLastWeek, ReviewRating.CUSTOM_INTERVAL, now.minusSeconds(30 * 86400), now.minusSeconds(7 * 86400)).getOrThrow()
        fixture.store().record(learning, ReviewRating.AGAIN, now.minusSeconds(3600)).getOrThrow()
        val learningDue = checkNotNull(fixture.store().card(learning)!!.schedule).dueAt
        assertTrue(learningDue.isAfter(now.minusSeconds(3600)) && !learningDue.isAfter(now))

        assertEquals(listOf(dueLastWeek, dueToday, learning, fresh), fixture.store().dueCards(now).map { it.id })
    }

    @Test fun skippedCardKeepsItsDueDateAndReturnsToItsPlace() {
        val fixture = fixture()
        val older = fixture.addCard("paper")
        val newer = fixture.addCard("paper")
        val olderDue = now.minusSeconds(5 * 86400)
        fixture.store().record(older, ReviewRating.CUSTOM_INTERVAL, now.minusSeconds(20 * 86400), olderDue).getOrThrow()
        fixture.store().record(newer, ReviewRating.CUSTOM_INTERVAL, now.minusSeconds(20 * 86400), now.minusSeconds(86400)).getOrThrow()
        val later = now.plusSeconds(600)
        fixture.store().skip(older, ReviewSkipScope.CARD, later, now = now).getOrThrow()

        val store = fixture.store()
        assertEquals(listOf(newer), store.dueCards(now).map { it.id })
        val skipped = store.browserCards(now).single { it.card.id == older }
        assertEquals(ReviewBrowserState.SKIPPED, skipped.state)
        assertEquals(olderDue, skipped.dueAt)
        assertEquals(olderDue, store.card(older)!!.schedule!!.dueAt)
        assertEquals(listOf(older, newer), store.dueCards(later).map { it.id })
    }

    @Test fun skipDoesNotBringAFutureReviewForward() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val later = now.plusSeconds(14 * 86400)
        fixture.store().record(id, ReviewRating.CUSTOM_INTERVAL, now, later).getOrThrow()
        fixture.store().skip(id, ReviewSkipScope.CARD, tomorrow, now = now).getOrThrow()
        assertEquals(later, fixture.store().summary(now).nextDueAt)
        assertTrue(fixture.store().dueCards(tomorrow).isEmpty())
        assertEquals(later, fixture.store().browserCards(now).single().dueAt)
        assertEquals(id, fixture.store().dueCards(later).single().id)
    }

    @Test fun libraryProjectSkipIncludesCombinedPdfAndImportedDocumentsOnly() {
        val fixture = fixture()
        val combined = fixture.addCard("project_course")
        val lecture = fixture.addCard("lecture")
        val exercise = fixture.addCard("exercise")
        val other = fixture.addCard("other")
        val projects = reviewProjectsByFilename(com.google.gson.JsonParser.parseString("""[
          {"id":"course","target":"project_course.pdf","documents":[
            {"filename":"lecture.pdf"},{"filename":"exercise.pdf"}]},
          {"id":"other","target":"other.pdf","documents":[]}
        ]""").asJsonArray)
        val batch = fixture.store().skip(lecture, ReviewSkipScope.PROJECT, tomorrow, projects, now).getOrThrow()
        assertEquals(setOf(combined, lecture, exercise), batch.cardIds)
        assertEquals(listOf(other), fixture.store().dueCards(now).map { it.id })
        fixture.store().undoSkip(batch.id).getOrThrow()
        assertEquals(setOf(combined, lecture, exercise, other), fixture.store().dueCards(now).map { it.id }.toSet())
    }

    @Test fun documentAndProjectScopesStayWithinTheirExactBoundaries() {
        val fixture = fixture()
        val a = fixture.addCard("paper")
        val b = fixture.addCard("paper")
        val appendix = fixture.addCard("appendix")
        val other = fixture.addCard("other")
        val unmapped = fixture.addCard("unmapped")
        val retained = fixture.addCard("paper")
        fixture.store().record(retained, ReviewRating.RETAINED, now).getOrThrow()
        fixture.addCard("paper", "candidate")
        val projects = mapOf("paper.pdf" to "project-a", "appendix.pdf" to "project-a", "other.pdf" to "project-b")
        val document = fixture.store().skip(a, ReviewSkipScope.DOCUMENT, tomorrow, projects, now).getOrThrow()
        assertEquals(setOf(a, b), document.cardIds)
        assertEquals(setOf(appendix, other, unmapped), fixture.store().dueCards(now).map { it.id }.toSet())
        fixture.store().undoSkip(document.id).getOrThrow()
        val project = fixture.store().skip(a, ReviewSkipScope.PROJECT, tomorrow, projects, now).getOrThrow()
        assertEquals(setOf(a, b, appendix), project.cardIds)
        assertEquals(setOf(other, unmapped), fixture.store().dueCards(now).map { it.id }.toSet())
        assertTrue(fixture.store().skip(unmapped, ReviewSkipScope.PROJECT, tomorrow, projects, now).isFailure)
        fixture.store().undoSkip(project.id).getOrThrow()
        assertEquals(setOf(a, b, appendix, other, unmapped), fixture.store().dueCards(now).map { it.id }.toSet())
    }

    @Test fun scopedSkipCanLimitTargetsToCardsDueNow() {
        val fixture = fixture()
        val current = fixture.addCard("paper")
        val due = fixture.addCard("paper")
        val future = fixture.addCard("paper")
        val otherDocument = fixture.addCard("other")
        fixture.store().record(future, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(7 * 86400)).getOrThrow()

        val dueBatch = fixture.store().skip(current, ReviewSkipScope.DOCUMENT, tomorrow,
            now = now, dueOnly = true).getOrThrow()
        assertEquals(setOf(current, due), dueBatch.cardIds)
        assertEquals(now.plusSeconds(7 * 86400), fixture.store().card(future)!!.schedule!!.dueAt)
        fixture.store().undoSkip(dueBatch.id, now).getOrThrow()

        val allBatch = fixture.store().skip(current, ReviewSkipScope.DOCUMENT, tomorrow,
            now = now, dueOnly = false).getOrThrow()
        assertEquals(setOf(current, due, future), allBatch.cardIds)
        assertFalse(otherDocument in allBatch.cardIds)
    }

    @Test fun overlappingSkipsAndUndoPreserveTheOtherSkip() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val first = fixture.store().skip(id, ReviewSkipScope.CARD, tomorrow, now = now).getOrThrow()
        val second = fixture.store().skip(id, ReviewSkipScope.CARD, tomorrow.plusSeconds(86400), now = now).getOrThrow()
        fixture.store().undoSkip(second.id).getOrThrow()
        assertEquals(tomorrow, fixture.store().summary(now).nextDueAt)
        fixture.store().undoSkip(first.id).getOrThrow()
        assertEquals(id, fixture.store().dueCards(now).single().id)
    }

    @Test fun invalidSkipCannotHideCardsOrWriteAnyBatch() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        assertTrue(fixture.store().skip(id, ReviewSkipScope.CARD, now, now = now).isFailure)
        assertTrue(fixture.store().skip(UUID.randomUUID().toString(), ReviewSkipScope.CARD, tomorrow, now = now).isFailure)
        assertTrue(fixture.store().undoSkip("../incoming").isFailure)
        assertEquals(id, fixture.store().dueCards(now).single().id)
        assertFalse(File(fixture.root, "skips").exists())
    }

    @Test fun skipsAndUndoRoundTripThroughAnotherDevicesIncomingEvents() {
        val first = fixture()
        val id = first.addCard("paper")
        val second = fixture()
        first.incoming.copyRecursively(second.incoming, overwrite = true)
        val batch = first.store().skip(id, ReviewSkipScope.CARD, tomorrow, now = now).getOrThrow()
        first.events.copyRecursively(File(second.incoming, "events"), overwrite = true)
        assertEquals(0, second.store().summary(now).counts.newCount)
        assertNull(second.store().card(id)!!.schedule)
        assertEquals(ReviewBrowserState.SKIPPED, second.store().browserCards(now).single().state)
        second.store().undoSkip(batch.id, now.plusSeconds(1)).getOrThrow()
        second.events.copyRecursively(File(first.incoming, "events"), overwrite = true)
        assertEquals(1, first.store().summary(now).counts.newCount)
        assertEquals(1, second.store().summary(now).counts.newCount)
        val history = first.store().browserCards(now).single().history
        assertEquals(listOf("skip", "undo_skip"), history.map { it.queueAction })
        assertEquals(listOf(now, now.plusSeconds(1)), history.map { it.reviewedAt })
        assertEquals(tomorrow, history.first().skipUntil)
        assertEquals(batch.id, history.first().skipId)
        assertEquals(batch.id, history.last().skipId)
        assertEquals(0, first.store().browserCards(now).single().reviewCount)
    }

    @Test fun existingPhoneSkipsMigrateOnceAndRemainAfterAcknowledgement() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val legacy = ReviewSkipStore(File(fixture.root, "skips"))
        val batch = legacy.save(setOf(id), tomorrow, now)
        fixture.store().migrateLegacySkips()
        val firstEvents = fixture.events.walkTopDown().filter { it.extension == "json" }.map { it.readText() }.toList()
        assertEquals(1, firstEvents.size)
        fixture.store().migrateLegacySkips()
        assertEquals(firstEvents, fixture.events.walkTopDown().filter { it.extension == "json" }.map { it.readText() }.toList())
        fixture.events.copyRecursively(File(fixture.incoming, "events"), overwrite = true)
        fixture.events.deleteRecursively()
        assertEquals(0, fixture.store().summary(now).counts.newCount)
        assertTrue(fixture.store().pendingChanges().isEmpty())
        assertFalse(File(fixture.root, "skips/${batch.id}.json").exists())
        assertTrue(File(fixture.root, "skips/migrated/${batch.id}.json").exists())
        fixture.store().undoSkip(batch.id, now.plusSeconds(2)).getOrThrow()
        assertEquals(1, fixture.store().summary(now).counts.newCount)
        assertEquals(1, fixture.store().pendingChanges().size)
    }

    @Test fun interruptedMigrationRetriesTheSameStableEvents() {
        val directory = temporary.newFolder()
        val legacy = ReviewSkipStore(directory)
        val ids = setOf(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val batch = legacy.save(ids, tomorrow, now)
        val written = mutableListOf<String>()
        assertTrue(runCatching {
            legacy.migrate("phone") {
                written += it.toString()
                error("Interrupted disk write")
            }
        }.isFailure)
        assertTrue(File(directory, "${batch.id}.json").exists())
        val replay = mutableListOf<String>()
        legacy.migrate("phone") { replay += it.toString() }
        assertEquals(written.first(), replay.first())
        assertEquals(2, replay.size)
    }

    @Test fun outOfOrderDuplicateAndUnrelatedUndoDoNotChangeOtherSkips() {
        val directory = temporary.newFolder()
        val legacy = ReviewSkipStore(directory)
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val batch = legacy.save(setOf(id), tomorrow, now)
        val events = mutableListOf<com.google.gson.JsonObject>()
        legacy.migrate("phone") { events += it }
        legacy.queueUndo(batch.id, setOf(other), now.plusSeconds(1))
        legacy.migrate("other") { events += it }
        assertEquals(tomorrow, reviewSkippedUntil(events.reversed() + events)[id])
        legacy.queueUndo(batch.id, setOf(id), now.plusSeconds(2))
        legacy.migrate("other") { events += it }
        assertTrue(reviewSkippedUntil(events.reversed() + events).isEmpty())
    }

    @Test fun laterShorterSkipReplacesAnEarlierLongerSkip() {
        // Observed on the phone: skipped for three weeks, then a minute later for two days.
        val card = UUID.randomUUID().toString()
        fun skip(at: String, until: String) = com.google.gson.JsonObject().apply {
            addProperty("schema_version", 3)
            addProperty("event_id", UUID.randomUUID().toString())
            addProperty("card_id", card)
            addProperty("skip_id", UUID.randomUUID().toString())
            addProperty("queue_action", "skip")
            addProperty("reviewed_at", at)
            addProperty("skip_until", until)
        }
        val long = skip("2026-09-23T07:16:00Z", "2026-10-14T07:16:00Z")
        val short = skip("2026-09-23T07:17:00Z", "2026-09-25T07:17:00Z")
        val expected = java.time.Instant.parse("2026-09-25T07:17:00Z")
        assertEquals(expected, reviewSkippedUntil(listOf(long, short))[card])
        assertEquals(expected, reviewSkippedUntil(listOf(short, long))[card])
    }

    private fun fixture() = Fixture(temporary.newFolder(), temporary.newFolder(), temporary.newFolder(), temporary.newFolder())

    private data class Fixture(val root: File, val incoming: File, val events: File, val outgoing: File) {
        fun store() = ReviewStore(null, root, incoming, events, outgoing)
        fun addCard(stem: String, status: String = "approved"): String {
            val id = UUID.randomUUID().toString()
            File(incoming, "cards/server/$id.json").apply {
                parentFile.mkdirs()
                writeText("""{"schema_version":3,"card_type":"visual_region","card_id":"$id","status":"$status","revision":1,"origin_id":"server","primary":{"pdf_stem":"$stem","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"Saved question"},"reanchor":{"state":"succeeded","strategy":"source_markers_v2"}}""")
            }
            return id
        }
    }
}
