// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

/** Knowledge cards and project cards in the store (protocol/review-card-kinds.md sections 2, 3, 6 and 7). */
class ReviewCardKindsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-11-10T09:00:00Z")
    private val day = 86_400L
    private val hashOne = "1".repeat(64)
    private val hashTwo = "2".repeat(64)

    @Test fun everyAnswerCarriesThePassageItWasGivenOnAndADeleteNone() {
        val fixture = fixture(knowledgeProject())
        val linked = fixture.addCard("paper", anchorHash = hashOne)
        val plain = fixture.addCard("paper")
        val store = fixture.store()
        val answer = store.record(linked, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(3 * day), reviewTags = listOf("Recall: 3")).getOrThrow()
        assertEquals(JsonParser.parseString("""{"segments":[{"segment_id":"0","content_sha256":"$hashOne"},{"segment_id":"1","content_sha256":"${"3".repeat(64)}"}]}"""),
            read(answer)["passage"])
        // Several targets (Next for the document) each carry their own card's passage, or none.
        val scoped = store.recordScoped(plain, ReviewSkipScope.CARD, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(10), now.plusSeconds(3 * day), null).getOrThrow()
        assertFalse(read(fixture.eventFile(scoped.getValue(plain))).has("passage"))
        val deletion = store.record(linked, ReviewRating.DELETE, now.plusSeconds(20)).getOrThrow()
        assertFalse(read(deletion).has("passage"))
    }

    @Test fun aProjectCardIsDueAWeekAfterItsAnswerAndAKnowledgeCardKeepsItsSchedule() {
        val fixture = fixture(projectProject())
        val project = fixture.addCard("paper", documentId = DOCUMENT)
        val knowledge = fixture.addCard("notes")
        val fresh = fixture.addCard("paper", documentId = DOCUMENT)
        val store = fixture.store()
        listOf(project, knowledge).forEach { store.record(it, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(60 * day)).getOrThrow() }
        val later = now.plusSeconds(8 * day)

        val cards = fixture.store().browserCards(later).associateBy { it.card.id }
        assertTrue(cards.getValue(project).card.isProjectCard)
        assertEquals(listOf("project-a"), cards.getValue(project).card.reviewProjectIds)
        assertEquals(ReviewKindSource.PROJECT, cards.getValue(project).card.reviewKindSource)
        assertEquals(now.plusSeconds(7 * day), cards.getValue(project).dueAt)
        assertEquals(ReviewBrowserState.REVIEW, cards.getValue(project).state)
        // The knowledge schedule is kept underneath: swapping back restores it.
        assertEquals(now.plusSeconds(60 * day), cards.getValue(project).card.knowledgeSchedule?.dueAt)
        assertFalse(cards.getValue(knowledge).card.isProjectCard)
        assertEquals(ReviewKindSource.DEFAULT, cards.getValue(knowledge).card.reviewKindSource)
        assertEquals(now.plusSeconds(60 * day), cards.getValue(knowledge).dueAt)
        assertEquals(ReviewBrowserState.FUTURE, cards.getValue(knowledge).state)

        val due = fixture.store().dueCards(later).map { it.id }
        assertEquals(setOf(project, fresh), due.toSet())
        // The two review entries, and Projects narrowed to one Project.
        assertEquals(emptyList<String>(), fixture.store().dueCards(later, kind = ReviewKinds.KNOWLEDGE).map { it.id })
        assertEquals(setOf(project, fresh), fixture.store().dueCards(later, kind = ReviewKinds.PROJECT, projectId = "project-a").map { it.id }.toSet())
        assertEquals(emptyList<String>(), fixture.store().dueCards(later, kind = ReviewKinds.PROJECT, projectId = "project-b").map { it.id })
        // A document's session keeps every card of its scope.
        assertEquals(setOf(project, fresh), fixture.store().dueCards(later, pdfStem = "paper").map { it.id }.toSet())

        val summary = fixture.store().summary(later)
        assertEquals(2, summary.counts.total)
        assertEquals(0, summary.knowledgeCounts.total)
        assertEquals(ReviewQueueCounts(newCount = 1, reviewCount = 1), summary.projectCounts)
        assertEquals(listOf(ReviewProjectSummary("project-a", "Project A", ReviewQueueCounts(newCount = 1, reviewCount = 1), 2)), summary.projects)
        assertEquals(summary, fixture.store().fullSummaryForTesting(later))
        // Before the week is over, the project card is not due and its next time is the capped one.
        val early = fixture.store().summary(now.plusSeconds(day))
        assertEquals(ReviewQueueCounts(newCount = 1), early.projectCounts)
    }

    /** Nothing changes for cards without kind events in knowledge Projects. */
    @Test fun knowledgeProjectsAndAnOldServerChangeNothing() {
        val withoutCatalogue = fixture(null)
        val knowledgeLibrary = fixture(knowledgeProject(), withoutCatalogue)
        val oldServer = fixture(knowledgeProject(kind = null), withoutCatalogue)
        val first = withoutCatalogue.addCard("paper", documentId = DOCUMENT, anchorHash = hashOne)
        val second = withoutCatalogue.addCard("notes")
        withoutCatalogue.store().record(first, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(60 * day)).getOrThrow()
        withoutCatalogue.store().record(second, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(2 * day)).getOrThrow()
        // The passage changes after the answer: a knowledge card is never marked.
        withoutCatalogue.rewriteAnchor(first, hashTwo, now.plusSeconds(day))
        for (at in listOf(now, now.plusSeconds(3 * day), now.plusSeconds(61 * day))) {
            val expected = withoutCatalogue.store().summary(at)
            for (fixture in listOf(knowledgeLibrary, oldServer)) {
                val summary = fixture.store().summary(at)
                assertEquals(expected.counts, summary.counts)
                assertEquals(expected.counts, summary.knowledgeCounts)
                assertEquals(ReviewQueueCounts(), summary.projectCounts)
                assertEquals(expected.nextDueAt, summary.nextDueAt)
                assertEquals(expected.decks, summary.decks)
                assertEquals(withoutCatalogue.store().dueCards(at).map { it.id }, fixture.store().dueCards(at).map { it.id })
                assertEquals(withoutCatalogue.store().browserCards(at).map { it.dueAt to it.state },
                    fixture.store().browserCards(at).map { it.dueAt to it.state })
            }
        }
        val card = knowledgeLibrary.store().card(first)!!
        assertTrue(card.passageChanged)
        assertFalse(card.changedSinceSeen)
        assertEquals(ReviewKindSource.PROJECT, card.reviewKindSource)
    }

    @Test fun aProjectCardWhosePassageChangedIsDueAtOnceUntilItIsAnswered() {
        val fixture = fixture(projectProject())
        val card = fixture.addCard("paper", documentId = DOCUMENT, anchorHash = hashOne)
        fixture.store().record(card, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(3 * day)).getOrThrow()
        assertFalse(fixture.store().card(card)!!.changedSinceSeen)
        // An edit of the passage reaches the card's record a day later.
        val changedAt = now.plusSeconds(day)
        fixture.rewriteAnchor(card, hashTwo, changedAt)
        val store = fixture.store()
        val changed = store.card(card)!!
        assertTrue(changed.changedSinceSeen)
        assertEquals(changedAt, changed.schedule?.dueAt)
        assertEquals(listOf(card), store.dueCards(changedAt.plusSeconds(60)).map { it.id })
        assertEquals(changedAt, store.browserCards(changedAt).single().dueAt)
        // Answering records the current passage: the mark goes and the 7-day rule applies again.
        val answeredAt = changedAt.plusSeconds(3600)
        store.record(card, ReviewRating.CUSTOM_INTERVAL, answeredAt, answeredAt.plusSeconds(30 * day)).getOrThrow()
        val seen = fixture.store().card(card)!!
        assertFalse(seen.passageChanged)
        assertEquals(answeredAt.plusSeconds(7 * day), seen.schedule?.dueAt)
    }

    @Test fun choosingACardsKindWritesAKindEventAndTheCardFollowsIt() {
        val fixture = fixture(knowledgeProject())
        val card = fixture.addCard("paper", documentId = DOCUMENT)
        val store = fixture.store()
        store.record(card, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(30 * day)).getOrThrow()
        assertEquals(ReviewKindSource.PROJECT, store.card(card)!!.reviewKindSource)

        val made = store.setReviewKind(card, ReviewKinds.PROJECT, now.plusSeconds(60)).getOrThrow()
        val event = read(fixture.eventFile(made))
        assertEquals(setOf("schema_version", "event_id", "card_id", "queue_action", "review_kind", "reviewed_at", "origin_id"), event.keySet())
        assertEquals("set_review_kind", event["queue_action"].asString)
        assertEquals("project", event["review_kind"].asString)
        val project = fixture.store().card(card)!!
        assertTrue(project.isProjectCard)
        assertEquals(ReviewKindSource.CARD, project.reviewKindSource)
        assertEquals(ReviewKinds.PROJECT, project.ownReviewKind)
        assertEquals(now.plusSeconds(7 * day), project.schedule?.dueAt)
        val history = fixture.store().browserCards(now).single().history
        assertEquals(ReviewKinds.PROJECT, history.last().reviewKind)
        assertFalse(history.last().isAnswerInEffect())
        assertTrue(fixture.store().pendingChanges().any { it.entityType == "review_event" && it.entityId == made })

        fixture.store().setReviewKind(card, ReviewKinds.FOLLOW_PROJECT, now.plusSeconds(120)).getOrThrow()
        val follows = fixture.store().card(card)!!
        assertFalse(follows.isProjectCard)
        assertNull(follows.ownReviewKind)
        assertEquals(ReviewKindSource.PROJECT, follows.reviewKindSource)
        // Kind events never change the knowledge schedule.
        assertEquals(now.plusSeconds(30 * day), follows.schedule?.dueAt)

        assertTrue(fixture.store().setReviewKind(card, "weekly").isFailure)
        fixture.store().record(card, ReviewRating.DELETE, now.plusSeconds(180)).getOrThrow()
        assertTrue(fixture.store().setReviewKind(card, ReviewKinds.PROJECT).isFailure)
    }

    @Test fun aCardMadeAsAProjectCardIsOneInAKnowledgeProject() {
        val fixture = fixture(knowledgeProject())
        val card = fixture.addCard("paper", documentId = DOCUMENT, recordKind = "project")
        val read = fixture.store().card(card)!!
        assertTrue(read.isProjectCard)
        assertEquals(ReviewKindSource.CARD, read.reviewKindSource)
    }

    @Test fun retiringAProjectsProjectCardsRetiresEachActiveOneAtOneTime() {
        val fixture = fixture(projectProject())
        val first = fixture.addCard("paper", documentId = DOCUMENT)
        val second = fixture.addCard("paper")
        val knowledge = fixture.addCard("paper", documentId = DOCUMENT)
        val retired = fixture.addCard("paper", documentId = DOCUMENT)
        val elsewhere = fixture.addCard("notes", recordKind = "project")
        val store = fixture.store()
        store.setReviewKind(knowledge, ReviewKinds.KNOWLEDGE, now).getOrThrow()
        store.retire(retired, now).getOrThrow()

        val active = fixture.store().activeProjectCards("project-a").map { it.id }.toSet()
        assertEquals(setOf(first, second), active)
        val events = fixture.store().retireAll(active, now.plusSeconds(60)).getOrThrow()
        assertEquals(active, events.keys)
        val after = fixture.store().browserCards(now.plusSeconds(120)).associateBy { it.card.id }
        active.forEach {
            assertEquals(ReviewBrowserState.RETIRED, after.getValue(it).state)
            assertEquals(now.plusSeconds(60), after.getValue(it).card.retiredAt)
        }
        assertTrue(after.getValue(knowledge).state != ReviewBrowserState.RETIRED)
        assertTrue(after.getValue(elsewhere).state != ReviewBrowserState.RETIRED)
        assertEquals(emptyList<String>(), fixture.store().activeProjectCards("project-a").map { it.id })
        // Restore to review brings one card back, as today.
        fixture.store().restore(first, now.plusSeconds(180)).getOrThrow()
        assertEquals(listOf(first), fixture.store().activeProjectCards("project-a").map { it.id })
    }

    @Test fun aProjectsNewKindCountsAtOnceForTheCardsThatFollowIt() {
        val projects = knowledgeProject()
        val fixture = fixture(projects)
        val card = fixture.addCard("paper", documentId = DOCUMENT)
        val own = fixture.addCard("paper", documentId = DOCUMENT)
        val store = fixture.store()
        store.record(card, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(60 * day)).getOrThrow()
        store.setReviewKind(own, ReviewKinds.KNOWLEDGE, now).getOrThrow()
        assertFalse(store.card(card)!!.isProjectCard)
        // The catalogue changes (a queued set_review_kind, or Sync): the same store reads it at once.
        projects[0].asJsonObject.addProperty("review_kind", "project")
        assertTrue(store.card(card)!!.isProjectCard)
        assertFalse(store.card(own)!!.isProjectCard)
        assertEquals(now.plusSeconds(7 * day), store.browserCards(now).first { it.card.id == card }.dueAt)
        assertEquals(ReviewQueueCounts(newCount = 1), store.summary(now).knowledgeCounts)
    }

    private fun read(file: File): JsonObject = JsonParser.parseString(file.readText()).asJsonObject

    private fun knowledgeProject(kind: String? = "knowledge") = catalogue(kind)
    private fun projectProject() = catalogue("project")
    private fun catalogue(kind: String?): JsonArray = JsonParser.parseString("""[
        {"id":"project-a","name":"Project A","revision":3,"target":"project-a-reading.pdf",
         "documents":[{"document_id":"$DOCUMENT","filename":"paper.pdf","id":"paper.pdf"}],"unpublished_documents":[]
         ${kind?.let { ""","review_kind":"$it"""" }.orEmpty()}},
        {"id":"project-b","name":"Project B","revision":1,"target":"",
         "documents":[{"document_id":"${"c".repeat(8)}-0000-4000-8000-00000000000c","filename":"other.pdf","id":"other.pdf"}],
         "unpublished_documents":[]${kind?.let { ""","review_kind":"knowledge"""" }.orEmpty()}}
    ]""").asJsonArray

    private fun fixture(projects: JsonArray?, share: Fixture? = null) = share?.copy(projects = projects)
        ?: Fixture(temporary.newFolder(), temporary.newFolder(), temporary.newFolder(), temporary.newFolder(), projects)

    private data class Fixture(val root: File, val incoming: File, val events: File, val outgoing: File, val projects: JsonArray?) {
        fun store() = ReviewStore(null, root, incoming, events, outgoing).apply {
            kindCatalogueSource = projects?.let { catalogue -> { ReviewKindCatalogue(catalogue) } }
        }

        fun eventFile(eventId: String): File = checkNotNull(events.walkTopDown().firstOrNull { it.name == "$eventId.json" })

        fun addCard(stem: String, documentId: String? = null, anchorHash: String? = null, recordKind: String? = null): String {
            val id = UUID.randomUUID().toString()
            write(id, stem, documentId, anchorHash, recordKind, revision = 1, updatedAt = "2026-11-01T08:00:00Z")
            return id
        }

        /** The card's record follows an edit of its passage: another hash for segment 0. */
        fun rewriteAnchor(id: String, hash: String, updatedAt: Instant) {
            val record = JsonParser.parseString(File(incoming, "cards/server/$id.json").readText()).asJsonObject
            val primary = record.getAsJsonObject("primary")
            val file = File(incoming, "cards/server/$id.json")
            val before = file.lastModified()
            write(id, primary["pdf_stem"].asString, primary["document_id"]?.asString, hash, record["review_kind"]?.asString,
                revision = record["revision"].asInt + 1, updatedAt = updatedAt.toString())
            // As Sync replaces a record: seen as a new file even within the same millisecond.
            file.setLastModified(before + 2_000)
        }

        private fun write(id: String, stem: String, documentId: String?, anchorHash: String?, recordKind: String?, revision: Int, updatedAt: String) {
            val anchor = anchorHash?.let {
                ""","source_anchor":{"version":2,"segments":[{"segment_id":"0","source_file":"docs-fixture/tex/source/notes.tex","content_sha256":"$it","line_start":10,"line_end":12},{"segment_id":"1","source_file":"docs-fixture/tex/source/notes.tex","content_sha256":"${"3".repeat(64)}","line_start":14,"line_end":15}]}"""
            }.orEmpty()
            val document = documentId?.let { ""","document_id":"$it","pdf_document_id":"$it"""" }.orEmpty()
            val kind = recordKind?.let { ""","review_kind":"$it"""" }.orEmpty()
            File(incoming, "cards/server/$id.json").apply {
                parentFile.mkdirs()
                writeText("""{"schema_version":3,"card_type":"visual_region","card_id":"$id","status":"approved","revision":$revision,"origin_id":"server","created_at":"2026-11-01T08:00:00Z","updated_at":"$updatedAt"$kind,"primary":{"pdf_stem":"$stem"$document,"pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"Saved question"$anchor},"reanchor":{"state":"succeeded","strategy":"source_markers_v2"}}""")
            }
        }
    }

    private companion object {
        const val DOCUMENT = "aaaaaaaa-0000-4000-8000-00000000000a"
    }
}
