// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Retire card, Restore to review and their undo in the store (protocol/review-card-retire.md). */
class ReviewRetireStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-10-27T10:00:00Z")
    private val day = 86_400L

    @Test fun retiredCardLeavesEveryQueueAndCountButKeepsItsScheduleAndHistory() {
        val fixture = fixture()
        val retired = fixture.addCard("paper")
        val other = fixture.addCard("paper")
        val elsewhere = fixture.addCard("notes")
        val due = now.minusSeconds(2 * day)
        fixture.store().record(retired, ReviewRating.CUSTOM_INTERVAL, now.minusSeconds(10 * day), due, reviewTags = listOf("Recall: 2")).getOrThrow()
        val before = fixture.store().browserCards(now).single { it.card.id == retired }
        assertEquals(3, fixture.store().summary(now).counts.total)

        val eventId = fixture.store().retire(retired, now).getOrThrow()

        val store = fixture.store()
        // Not offered: library-wide and per-document sessions, due counts, decks, next due.
        assertEquals(setOf(other, elsewhere), store.dueCards(now).map { it.id }.toSet())
        assertEquals(listOf(other), store.dueCards(now, pdfStem = "paper").map { it.id })
        val summary = store.summary(now)
        assertEquals(2, summary.counts.total)
        assertEquals(2, summary.activeCount)
        assertEquals(1, summary.decks.single { it.pdfStem == "paper" }.activeCount)
        assertEquals(summary, store.fullSummaryForTesting(now))
        // The card itself keeps its schedule, history and tags, and shows as Retired.
        val card = store.card(retired)!!
        assertEquals(now, card.retiredAt)
        assertEquals(eventId, card.retireEventId)
        assertEquals(before.card.schedule, card.schedule)
        val after = store.browserCards(now).single { it.card.id == retired }
        assertEquals(ReviewBrowserState.RETIRED, after.state)
        assertEquals(before.dueAt, after.dueAt)
        assertEquals(before.reviewCount, after.reviewCount)
        assertEquals(before.lastReviewedAt, after.lastReviewedAt)
        assertEquals(listOf("Recall: 2"), after.currentTags())
        assertEquals(before.history, after.history.dropLast(1))
        val entry = after.history.last()
        assertEquals("retire", entry.queueAction)
        assertNull(entry.rating)
        assertEquals(now, entry.reviewedAt)
        assertFalse(entry.isAnswerInEffect())
        assertEquals(1, store.reviewTagUsage()["Recall: 2"])
        // The event is waiting for Sync.
        assertTrue(store.pendingChanges().any { it.entityType == "review_event" && it.entityId == eventId })
    }

    /**
     * 13 Nov: Fedora no longer places a retired card on a newer PDF: it stays on the version it
     * was retired on. It must not wait for placement (the follow-up after Sync would poll for it
     * until its time ran out), nor count as being placed or as needing attention.
     */
    @Test fun aCardRetiredOnAnOlderPdfVersionNeitherWaitsForPlacementNorCountsAsAProblem() {
        val fixture = fixture()
        val retired = fixture.addCard("paper")
        val stuck = fixture.addCard("paper", reanchor = "needs_attention")
        val active = fixture.addCard("paper")
        val retireEvent = fixture.store().retire(retired, now).getOrThrow()
        fixture.store().retire(stuck, now).getOrThrow()
        // The PDF on this phone is a newer version than the one the cards were placed on.
        val current = mapOf("paper.pdf" to "c".repeat(64))

        val store = fixture.store()
        assertEquals(setOf(active), store.cardsAwaitingPlacement(current))
        val summary = store.summary(now, current)
        assertEquals("Only the active card is being placed", 1, summary.repairingCount)
        assertEquals("A retired card is no problem to solve", 0, summary.needsAttentionCount)
        assertEquals(0, summary.unavailableCount)
        assertEquals(summary, store.fullSummaryForTesting(now, current))

        // Restored, it waits for its place on the current version like any card.
        store.undoRetire(retireEvent).getOrThrow()
        assertEquals(setOf(retired, active), fixture.store().cardsAwaitingPlacement(current))
        assertEquals(2, fixture.store().summary(now, current).repairingCount)
    }

    @Test fun retireEventIsWrittenToTheOutboxAsTheProtocolSays() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val eventId = fixture.store().retire(id, now).getOrThrow()
        val file = fixture.events.walkTopDown().single { it.isFile && it.nameWithoutExtension == eventId }
        val event = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(3, event["schema_version"].asInt)
        assertEquals(4, UUID.fromString(eventId).version())
        assertEquals(id, event["card_id"].asString)
        assertEquals("retire", event["queue_action"].asString)
        assertEquals(now.toString(), event["reviewed_at"].asString)
        assertEquals("test-phone", event["origin_id"].asString)
        assertFalse(event.has("rating"))
        assertFalse(event.has("skip_id"))
    }

    @Test fun restoreBringsTheCardBackWithItsOldScheduleAndAPastDueDateIsDueNow() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val due = now.plusSeconds(3 * day)
        fixture.store().record(id, ReviewRating.CUSTOM_INTERVAL, now.minusSeconds(4 * day), due).getOrThrow()
        val schedule = fixture.store().card(id)!!.schedule
        fixture.store().retire(id, now).getOrThrow()
        // Retired past its due date: still not offered.
        val later = now.plusSeconds(10 * day)
        assertTrue(fixture.store().dueCards(later).isEmpty())
        assertEquals(0, fixture.store().summary(later).counts.total)
        assertNull(fixture.store().summary(later).nextDueAt)

        fixture.store().restore(id, later).getOrThrow()

        val store = fixture.store()
        assertNull(store.card(id)!!.retiredAt)
        assertEquals(schedule, store.card(id)!!.schedule)
        assertEquals(listOf(id), store.dueCards(later).map { it.id })
        assertEquals(1, store.summary(later).counts.reviewCount)
        val browser = store.browserCards(later).single()
        assertEquals(ReviewBrowserState.REVIEW, browser.state)
        assertEquals(listOf("retire", "restore"), browser.history.mapNotNull { it.queueAction })
        assertEquals(1, browser.reviewCount)
    }

    @Test fun undoRetireBringsTheCardBackAndUndoRestoreRetiresItAgain() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val retireId = fixture.store().retire(id, now).getOrThrow()
        assertTrue(fixture.store().dueCards(now).isEmpty())

        val undoId = fixture.store().undoRetire(retireId, now.plusSeconds(5)).getOrThrow()

        assertEquals(listOf(id), fixture.store().dueCards(now.plusSeconds(5)).map { it.id })
        val undo = fixture.store().browserCards(now).single().history.last()
        assertEquals("undo_retire", undo.queueAction)
        assertEquals(retireId, undo.undoneEventId)
        assertEquals(undoId, undo.eventId)

        val secondRetire = fixture.store().retire(id, now.plusSeconds(10)).getOrThrow()
        val restore = fixture.store().restore(id, now.plusSeconds(20)).getOrThrow()
        assertEquals(ReviewBrowserState.NEW, fixture.store().browserCards(now.plusSeconds(20)).single().state)
        fixture.store().undoRetire(restore, now.plusSeconds(30)).getOrThrow()
        val card = fixture.store().card(id)!!
        assertEquals(secondRetire, card.retireEventId)
        assertEquals(now.plusSeconds(10), card.retiredAt)
        // Only retires and restores can be undone this way.
        assertTrue(fixture.store().undoRetire(UUID.randomUUID().toString()).isFailure)
    }

    @Test fun skipAllDueLeavesRetiredCardsOut() {
        val fixture = fixture()
        val current = fixture.addCard("paper")
        val retired = fixture.addCard("paper")
        val active = fixture.addCard("paper")
        fixture.store().retire(retired, now).getOrThrow()
        val cards = fixture.store().cards()
        val targets = reviewSkipTargets(cards, cards.single { it.id == current }, ReviewSkipScope.DOCUMENT, emptyMap(), now, dueOnly = true)
        assertEquals(setOf(current, active), targets)
        assertEquals(setOf(current, active), reviewSkipTargets(cards, cards.single { it.id == current }, ReviewSkipScope.DOCUMENT, emptyMap(), now))
        val batch = fixture.store().skip(current, ReviewSkipScope.DOCUMENT, now.plusSeconds(600), now = now, dueOnly = true).getOrThrow()
        assertEquals(setOf(current, active), batch.cardIds)
        // An answer given to all due cards of the document leaves it out too.
        val recorded = fixture.store().recordScoped(current, ReviewSkipScope.DOCUMENT, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(700),
            now.plusSeconds(7 * day), null, dueOnly = false).getOrThrow()
        assertFalse(retired in recorded.keys)
    }

    @Test fun deletionWinsOverRetirement() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        fixture.store().retire(id, now).getOrThrow()
        fixture.store().deleteCard(id, now.plusSeconds(60)).getOrThrow()
        val store = fixture.store()
        assertTrue(store.browserCards(now).isEmpty())
        assertNull(store.card(id)!!.retiredAt)
        assertTrue(store.restore(id, now.plusSeconds(120)).isFailure)
    }

    @Test fun retireFromAnotherDeviceArrivingFromTheServerRetiresTheCard() {
        val fixture = fixture()
        val id = fixture.addCard("paper")
        val eventId = UUID.randomUUID().toString()
        File(fixture.incoming, "events/server/$eventId.json").apply {
            parentFile.mkdirs()
            writeText("""{"schema_version":3,"event_id":"$eventId","card_id":"$id","queue_action":"retire","reviewed_at":"2026-10-26T08:00:00Z","origin_id":"desktop"}""")
        }
        val store = fixture.store()
        assertEquals(ReviewBrowserState.RETIRED, store.browserCards(now).single().state)
        assertEquals(Instant.parse("2026-10-26T08:00:00Z"), store.card(id)!!.retiredAt)
        assertTrue(store.dueCards(now).isEmpty())
        // The session's Undo can name an event from any device.
        store.undoRetire(eventId, now).getOrThrow()
        assertEquals(listOf(id), fixture.store().dueCards(now).map { it.id })
    }

    private fun fixture() = Fixture(temporary.newFolder(), temporary.newFolder(), temporary.newFolder(), temporary.newFolder())

    private data class Fixture(val root: File, val incoming: File, val events: File, val outgoing: File) {
        fun store() = ReviewStore(null, root, incoming, events, outgoing)
        fun addCard(stem: String, reanchor: String = "succeeded"): String {
            val id = UUID.randomUUID().toString()
            File(incoming, "cards/server/$id.json").apply {
                parentFile.mkdirs()
                writeText("""{"schema_version":3,"card_type":"visual_region","card_id":"$id","status":"approved","revision":1,"origin_id":"server","primary":{"pdf_stem":"$stem","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"Saved question"},"reanchor":{"state":"$reanchor","strategy":"source_markers_v2"}}""")
            }
            return id
        }
    }
}
