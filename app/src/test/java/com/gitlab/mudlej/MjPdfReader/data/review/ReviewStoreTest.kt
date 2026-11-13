// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.RectF
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-08-05T12:00:00Z")

    @Test
    fun `manual PDF-only card is immediately reviewable and survives reload`() {
        val root = temporary.newFolder("pdf-only")
        val incoming = temporary.newFolder("pdf-in")
        val events = temporary.newFolder("pdf-events")
        val outgoing = temporary.newFolder("pdf-out")
        val store = ReviewStore(null, root, incoming, events, outgoing)
        val card = store.createManualCard("paper", "a".repeat(64), 3,
            RectF().apply { left = 0.1f; top = 0.1f; right = 0.9f; bottom = 0.9f },
            "What is hidden?", createdAt = now, sourceAvailable = false, documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22").getOrThrow()
        assertEquals("pdf", card.sourceKind)
        assertEquals(listOf(card.id), store.dueCards(now).map { it.id })
        assertEquals("pdf", store.cards().single().sourceKind)
        assertEquals(0.9f, ReviewStore(null, root, incoming, events, outgoing).cards().single().rect.bottom)
    }

    @Test
    fun `whole-document card hides every page without source markers or overlap checks`() {
        val root = temporary.newFolder("whole")
        val incoming = temporary.newFolder("whole-in")
        val events = temporary.newFolder("whole-events")
        val outgoing = temporary.newFolder("whole-out")
        val store = ReviewStore(null, root, incoming, events, outgoing)
        val full = RectF().apply { left = 0f; top = 0f; right = 1f; bottom = 1f }
        val documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
        store.createManualCard("paper", "a".repeat(64), 1,
            RectF().apply { left = 0.05f; top = 0.05f; right = 0.95f; bottom = 0.95f },
            "What is on page one?", createdAt = now, sourceAvailable = false, documentId = documentId).getOrThrow()
        val card = store.createManualCard("paper", "a".repeat(64), 1, full, "household rules", createdAt = now,
            answerRegions = (1..3).map { ReviewAnswerRegion(it, full) }, documentId = documentId,
            wholeDocument = true).getOrThrow()
        assertTrue(card.wholeDocument)
        assertEquals("whole_document", card.sourceLinkState)
        assertNull(card.sourceAnchor)
        assertFalse(card.canEditAnswerBox)
        assertEquals(setOf(0, 1, 2), card.masksByPage.keys)
        // A later ordinary card on a page the whole-document card covers is still allowed.
        store.createManualCard("paper", "a".repeat(64), 2,
            RectF().apply { left = 0.1f; top = 0.1f; right = 0.9f; bottom = 0.9f },
            "Another question?", createdAt = now, sourceAvailable = false, documentId = documentId).getOrThrow()
        val reloaded = ReviewStore(null, root, incoming, events, outgoing).cards().single { it.id == card.id }
        assertTrue(reloaded.wholeDocument)
        assertEquals(3, reloaded.answerRegions.size)
        assertEquals(3, store.dueCards(now).size)
        val record = outgoing.walkTopDown().filter { it.isFile && it.readText().contains(card.id) }
            .map { com.google.gson.JsonParser.parseString(it.readText()).asJsonObject }.single()
        assertEquals("document", record.getAsJsonObject("primary").get("scope").asString)
        assertFalse(record.has("source_change"))
    }

    @Test
    fun `manual card can wait two days for its first review`() {
        val store = ReviewStore(null, temporary.newFolder("deferred"), temporary.newFolder("deferred-in"),
            temporary.newFolder("deferred-events"), temporary.newFolder("deferred-out"))
        val card = store.createManualCard("paper", "a".repeat(64), 3,
            RectF().apply { left = 0.2f; top = 0.3f; right = 0.6f; bottom = 0.55f },
            "What is hidden?", createdAt = now,
            sourceAvailable = false, documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22",
            firstDueDays = 2).getOrThrow()
        assertEquals(now.plus(2, ChronoUnit.DAYS), store.cards().single().initialDueAt)
        assertEquals(ReviewBrowserState.NEW, store.browserCards(now).single().state)
        assertEquals(now.plus(2, ChronoUnit.DAYS), store.browserCards(now).single().dueAt)
        assertTrue(store.dueCards(now).isEmpty())
        assertEquals(0, store.summary(now).counts.newCount)
        assertEquals(now.plus(2, ChronoUnit.DAYS), store.summary(now).nextDueAt)
        assertEquals(listOf(card.id), store.dueCards(now.plus(2, ChronoUnit.DAYS)).map { it.id })
    }

    @Test
    fun `manual card can use a custom first due time`() {
        val store = ReviewStore(null, temporary.newFolder("custom-due"), temporary.newFolder("custom-due-in"),
            temporary.newFolder("custom-due-events"), temporary.newFolder("custom-due-out"))
        val dueAt = now.plus(5, ChronoUnit.HOURS).plus(23, ChronoUnit.MINUTES)
        val rect = RectF().apply { left = 0.2f; top = 0.3f; right = 0.6f; bottom = 0.55f }
        val card = store.createManualCard("paper", "a".repeat(64), 3, rect,
            "What is hidden?", createdAt = now, sourceAvailable = false,
            documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22", firstDueAt = dueAt).getOrThrow()
        assertEquals(dueAt, store.cards().single().initialDueAt)
        assertEquals(dueAt, store.browserCards(now).single().dueAt)
        assertTrue(store.dueCards(dueAt.minusSeconds(1)).isEmpty())
        assertEquals(listOf(card.id), store.dueCards(dueAt).map { it.id })
        assertTrue(store.createManualCard("paper", "a".repeat(64), 4, rect,
            "Past time?", createdAt = now, sourceAvailable = false,
            documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22",
            firstDueAt = now.minusSeconds(1)).isFailure)
    }

    @Test
    fun `split boundaries do not create zero height float regions`() {
        val candidates = JsonParser.parseString("""[{"key":"original","regions":[{"page":1,"rect":[0,0.403124988,1,0.701562464]},{"page":1,"rect":[0,0.701562464,1,1]}]}]""").asJsonArray
        val parts = limitBasicCardExtent(candidates, "a".repeat(64))
        assertEquals(2, parts.size())
        assertTrue(parts.all { part -> part.asJsonObject.getAsJsonArray("regions").all {
            val rect = it.asJsonObject.getAsJsonArray("rect")
            rect[1].asFloat < rect[3].asFloat
        } })
        val store = ReviewStore(null, temporary.newFolder("rounded"), temporary.newFolder("rounded-in"), temporary.newFolder("rounded-events"), temporary.newFolder("rounded-out"))
        assertEquals(BasicReviewResult(2, 0), store.createBasicCards("paper", "a".repeat(64), listOf("b".repeat(64)), parts, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow())
    }

    @Test
    fun `source passages preserve all pages and invalid source ranges never become PDF only`() {
        val candidates = JsonParser.parseString("""[{"key":"original","source_anchor":{"kind":"basic_source_passages","segments":[{"source_file":"docs-paper/main.tex","content_sha256":"answer"}]},"regions":[{"page":1,"rect":[0,0,1,0.5]},{"page":1,"rect":[0,0.5,1,1]},{"page":2,"rect":[0,0,1,1]}]}]""").asJsonArray
        candidates[0].asJsonObject.addProperty("key", "d".repeat(64))
        assertEquals(candidates, limitBasicCardExtent(candidates, "a".repeat(64)))
        val store = ReviewStore(null, temporary.newFolder("source"), temporary.newFolder("source-in"), temporary.newFolder("source-events"), temporary.newFolder("source-out"))
        assertEquals(BasicReviewResult(1, 0), store.createBasicCards("paper", "a".repeat(64), listOf("b".repeat(64), "c".repeat(64)), candidates, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow())
        assertEquals(3, store.cards().single().regions.size)
        assertEquals("basic_passages_v2", store.cards().single().generationMethod)
        val anchor = candidates[0].asJsonObject.getAsJsonObject("source_anchor")
        anchor.getAsJsonArray("segments").add(anchor.getAsJsonArray("segments")[0].deepCopy())
        assertEquals(candidates, limitBasicCardExtent(candidates, "a".repeat(64)))
        assertTrue(store.createBasicCards("paper", "a".repeat(64), listOf("b".repeat(64), "c".repeat(64)), candidates, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow().errors.isNotEmpty())
        assertEquals(1, store.cards().size)
    }

    @Test
    fun `oversized PDF only plans become separate half page snapshot cards`() {
        val candidates = JsonParser.parseString("""[{"key":"original","regions":[{"page":1,"rect":[0,0,1,0.5]},{"page":1,"rect":[0,0.5,1,1]},{"page":2,"rect":[0,0,1,1]}]}]""").asJsonArray
        val parts = limitBasicCardExtent(candidates, "a".repeat(64))
        assertEquals(4, parts.size())
        assertEquals(4, parts.map { it.asJsonObject.get("key").asString }.toSet().size)
        for (part in parts) {
            assertFalse(part.asJsonObject.has("source_anchor"))
            val regions = part.asJsonObject.getAsJsonArray("regions")
            assertEquals(1, regions.size())
            val rect = regions[0].asJsonObject.getAsJsonArray("rect")
            assertEquals(.5, rect[3].asDouble - rect[1].asDouble, 1e-9)
        }
        assertEquals(parts, limitBasicCardExtent(candidates, "a".repeat(64)))
        assertEquals(parts, limitBasicCardExtent(parts, "a".repeat(64)))
        val store = ReviewStore(null, temporary.newFolder("bounded"), temporary.newFolder("in"), temporary.newFolder("events"), temporary.newFolder("out"))
        assertEquals(BasicReviewResult(4, 0), store.createBasicCards("paper", "a".repeat(64), listOf("b".repeat(64), "c".repeat(64)), parts, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow())
        assertEquals(BasicReviewResult(0, 4), store.createBasicCards("paper", "a".repeat(64), listOf("b".repeat(64), "c".repeat(64)), parts, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow())
    }

    @Test
    fun `basic passages preserve cross page answers and deduplicate after reload`() {
        val root = temporary.newFolder("basic")
        val incoming = temporary.newFolder("basic-incoming")
        val events = temporary.newFolder("basic-events")
        val outgoing = temporary.newFolder("basic-outgoing")
        val store = ReviewStore(null, root, incoming, events, outgoing)
        val fingerprints = listOf("b".repeat(64), "c".repeat(64))
        val candidates = JsonParser.parseString("""[{"key":"${"e".repeat(64)}","regions":[{"page":1,"rect":[0.1,0.7,0.9,0.9]},{"page":2,"rect":[0.1,0.1,0.9,0.3]}]}]""").asJsonArray
        assertEquals(BasicReviewResult(1, 0), store.createBasicCards("paper", "a".repeat(64), fingerprints, candidates, now, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow())
        val card = store.cards().single()
        assertEquals(listOf(1,2), card.regions.map { it.page })
        assertEquals("succeeded", card.reanchorState)
        val reloaded = ReviewStore(null, root, incoming, events, outgoing)
        assertEquals(BasicReviewResult(0, 1), reloaded.createBasicCards("paper", "a".repeat(64), fingerprints, candidates, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow())
        assertEquals(BasicReviewResult(1, 0), reloaded.createBasicCards("appendix", "a".repeat(64), fingerprints, candidates, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow())
        assertTrue(reloaded.createBasicCards("paper", "bad", fingerprints, candidates, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").isFailure)
        assertEquals(2, reloaded.cards().size)
    }

    @Test
    fun `basic whitespace cuts avoid text and preserve pages without a paragraph gap`() {
        assertEquals(listOf(0f,1f), basicWhitespaceCuts(BooleanArray(1000)))
        val rows = BooleanArray(1000) { it in 450..469 }
        assertEquals(listOf(0f,.46f,1f), basicWhitespaceCuts(rows))
        assertEquals(listOf(0f,1f), basicWhitespaceCuts(BooleanArray(1000) { true }))
    }

    @Test
    fun `manual text review preserves exact regions and quote across reload`() {
        val root = temporary.newFolder("text-review")
        val incoming = temporary.newFolder("text-incoming")
        val events = temporary.newFolder("text-events")
        val outgoing = temporary.newFolder("text-outgoing")
        val store = ReviewStore(null, root, incoming, events, outgoing)
        val regions = listOf(ReviewAnswerRegion(1, RectF().apply { left = .2f; top = .1f; right = .8f; bottom = .15f }),
            ReviewAnswerRegion(2, RectF().apply { left = .1f; top = .1f; right = .5f; bottom = .15f }))
        val card = store.createManualCard("paper", "a".repeat(64), 1, regions[0].rect,
            "What is selected?", answerRegions = regions, selectedText = "Exact selected words", documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22",
            placeMarkersByHand = true).getOrThrow()
        val payload = JsonParser.parseString(outgoing.walkTopDown().first { it.extension == "json" }.readText()).asJsonObject
        assertEquals(4, payload.get("schema_version").asInt)
        assertEquals("Exact selected words", payload.getAsJsonObject("primary").get("selected_text").asString)
        val restored = ReviewStore(null, root, incoming, events, outgoing).card(card.id)!!
        assertEquals(listOf(1, 2), restored.regions.map { it.page })
        assertEquals(.5f, restored.regions[1].rect.right, .0001f)
    }

    @Test
    fun `unsafe local deletion preserves pending removal without showing deleted card`() {
        for (extension in listOf("tex", "md", "markdown")) {
            val root = temporary.newFolder("source-delete-$extension")
            val incoming = temporary.newFolder("source-delete-incoming-$extension")
            val events = temporary.newFolder("source-delete-events-$extension")
            val outgoing = temporary.newFolder("source-delete-cards-$extension")
            val id = writeCard(root, "paper", page = 1, revision = 1)
            val file = File(root, "cards/$id.json")
            val payload = JsonParser.parseString(file.readText()).asJsonObject
            val anchor = JsonParser.parseString("""{"version":2,"segments":[{"segment_id":"0","source_file":"docs-paper/main.$extension","content_sha256":"${"a".repeat(64)}"}]}""").asJsonObject
            payload.getAsJsonObject("primary").add("source_anchor", anchor)
            file.writeText(payload.toString())
            val store = ReviewStore(null, root, incoming, events, outgoing)
            assertTrue(store.card(id)!!.canDeleteEnclosedText)
            val event = store.deleteCard(id, now, deleteEnclosedText = true).getOrThrow()
            val persisted = JsonParser.parseString(event.readText()).asJsonObject
            assertEquals("delete", persisted.get("rating").asString)
            assertFalse(persisted.has("delete_source"))
            assertEquals("pending", persisted.get("marker_removal").asString)
            val restored = ReviewStore(null, root, incoming, events, outgoing)
            assertTrue(restored.card(id)!!.markerRemovalPending)
            assertTrue(restored.browserCards(now = now).isEmpty())
            assertTrue(restored.deleteCard(id, now, deleteEnclosedText = true).isFailure)
            assertEquals(1, events.walkTopDown().count { it.isFile })
            assertEquals(payload, JsonParser.parseString(file.readText()).asJsonObject)
        }
    }

    @Test
    fun `pending marker removal counts as done once the markers are removed by hand`() {
        for (extension in listOf("tex", "md")) {
            val root = temporary.newFolder("by-hand-$extension")
            val incoming = temporary.newFolder("by-hand-incoming-$extension")
            val events = temporary.newFolder("by-hand-events-$extension")
            val outgoing = temporary.newFolder("by-hand-cards-$extension")
            val id = writeCard(root, "paper", page = 1, revision = 1)
            val path = "docs-paper/main.$extension"
            val body = "Intro.\nThe answer.\nOutro.\n"
            val marked = com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourceMarkup
                .create(path, body, "The answer.", id).after
            val file = File(root, "cards/$id.json")
            val payload = JsonParser.parseString(file.readText()).asJsonObject
            payload.getAsJsonObject("primary").add("source_anchor",
                com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourceMarkup.anchor(path, marked, id))
            file.writeText(payload.toString())
            val index = com.gitlab.mudlej.MjPdfReader.data.source.SourceAnnotationIndex
                .reloadForTesting(File(temporary.newFolder("by-hand-index-$extension"), "index.json"))
            index.install(sourceIndex(path, marked))
            var attempts = 0
            fun store() = ReviewStore(null, root, incoming, events, outgoing)
                .useMarkerSourcesForTesting(ReviewMarkerSources(index) { _, _ -> attempts++; false })

            val event = store().deleteCard(id, now).getOrThrow()
            val recorded = JsonParser.parseString(event.readText()).asJsonObject
            assertEquals("pending", recorded.get("marker_removal").asString)
            assertTrue(recorded.get("marker_removal_reason").asString.isNotBlank())
            assertTrue(store().card(id)!!.markerRemovalPending)
            assertEquals(1, store().summary(now).markerRemovalPendingCount)
            // Unsafe now: the retry leaves the card pending.
            assertEquals(0, store().retryPendingMarkerRemovals())
            assertEquals(1, attempts)
            assertTrue(store().card(id)!!.markerRemovalPending)

            // Someone removes the markers by hand; the next source check sees none.
            index.install(sourceIndex(path, body))
            assertFalse(store().card(id)!!.markerRemovalPending)
            assertFalse(store().cards().single().markerRemovalPending)
            assertEquals(0, store().summary(now).markerRemovalPendingCount)
            assertEquals(0, store().retryPendingMarkerRemovals())
            assertEquals(1, attempts)
            // The delete event stays exactly as recorded and synced.
            assertEquals(recorded, JsonParser.parseString(event.readText()).asJsonObject)
            // A source that is unavailable proves nothing; the card is pending again.
            index.invalidate()
            index.install(JsonParser.parseString("""{"schema_version":1,"files":{},"projects":{}}""").asJsonObject)
            assertTrue(store().card(id)!!.markerRemovalPending)
        }
    }

    @Test
    fun `retry counts only removals that succeed and skips cards no longer pending`() {
        val root = temporary.newFolder("retry")
        val incoming = temporary.newFolder("retry-incoming")
        val events = temporary.newFolder("retry-events")
        val outgoing = temporary.newFolder("retry-cards")
        val id = writeCard(root, "paper", page = 1, revision = 1)
        val path = "docs-paper/main.tex"
        val body = "Intro.\nThe answer.\nOutro.\n"
        var source = com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourceMarkup.create(path, body, "The answer.", id).after
        val file = File(root, "cards/$id.json")
        val payload = JsonParser.parseString(file.readText()).asJsonObject
        payload.getAsJsonObject("primary").add("source_anchor",
            com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourceMarkup.anchor(path, source, id))
        file.writeText(payload.toString())
        val index = com.gitlab.mudlej.MjPdfReader.data.source.SourceAnnotationIndex
            .reloadForTesting(File(temporary.newFolder("retry-index"), "index.json"))
        index.install(sourceIndex(path, source))
        val removedIds = mutableListOf<String>()
        fun store() = ReviewStore(null, root, incoming, events, outgoing)
            .useMarkerSourcesForTesting(ReviewMarkerSources(index) { cardId, anchor ->
                assertEquals(path, anchor.getAsJsonArray("segments")[0].asJsonObject.get("source_file").asString)
                removedIds += cardId
                source = body
                true
            })
        store().deleteCard(id, now).getOrThrow()
        assertTrue(store().card(id)!!.markerRemovalPending)
        assertEquals(1, store().retryPendingMarkerRemovals())
        assertEquals(listOf(id), removedIds)
        // The scanner rechecks the edited source.
        index.install(sourceIndex(path, source))
        assertFalse(store().card(id)!!.markerRemovalPending)
        assertEquals(0, store().retryPendingMarkerRemovals())
        assertEquals(listOf(id), removedIds)
    }

    @Test
    fun `marker removal retry runs only when due and no Sync is running`() {
        assertTrue(PendingMarkerRemovalRetry.due(null, 5, 60_000))
        assertFalse(PendingMarkerRemovalRetry.due(1_000, 30_000, 60_000))
        assertTrue(PendingMarkerRemovalRetry.due(1_000, 61_000, 60_000))
        assertTrue(PendingMarkerRemovalRetry.due(1_000, 1_001, 0))
        fun operation(status: String, phase: String) = com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord(
            "id", null, com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationKind.SYNC, status, 0, 0, emptyList(), phase = phase)
        val store = com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
        val phase = com.gitlab.mudlej.MjPdfReader.data.sync.SyncRunPhase
        assertFalse(PendingMarkerRemovalRetry.syncActive(emptyList()))
        assertTrue(PendingMarkerRemovalRetry.syncActive(listOf(operation(store.STATUS_SYNCING, phase.UPLOADING))))
        assertTrue(PendingMarkerRemovalRetry.syncActive(listOf(operation(store.STATUS_BUILDING, phase.BUILDING))))
        assertFalse(PendingMarkerRemovalRetry.syncActive(listOf(operation(store.STATUS_WAITING, phase.WAITING_NETWORK))))
        assertFalse(PendingMarkerRemovalRetry.syncActive(listOf(operation(store.STATUS_SYNCED, phase.SUCCEEDED))))
    }

    @Test
    fun `PDF only card cannot delete source but ordinary deletion still works`() {
        val root = temporary.newFolder("pdf-delete")
        val events = temporary.newFolder("pdf-delete-events")
        val id = writeCard(root, "paper", page = 1, revision = 1)
        val store = ReviewStore(null, root, temporary.newFolder("pdf-delete-incoming"),
            events, temporary.newFolder("pdf-delete-cards"))
        assertFalse(store.card(id)!!.canDeleteEnclosedText)
        assertTrue(store.deleteCard(id, now, deleteEnclosedText = true).isFailure)
        assertEquals(0, events.walkTopDown().count { it.isFile })
        val event = store.deleteCard(id, now).getOrThrow()
        assertFalse(JsonParser.parseString(event.readText()).asJsonObject.has("delete_source"))
    }


    @Test
    fun `one suggestion follows the latest interval whatever the tags`() {
        val root = temporary.newFolder("timing-root")
        val incoming = temporary.newFolder("timing-incoming")
        val events = temporary.newFolder("timing-events")
        val cards = temporary.newFolder("timing-cards")
        val id = writeCard(root, "paper", page = 1, revision = 1)
        val store = ReviewStore(null, root, incoming, events, cards)
        assertEquals(ReviewInterval(2, ReviewIntervalUnit.WEEKS), store.suggestedInterval(id))
        val correctAt = now
        store.record(id, ReviewRating.CUSTOM_INTERVAL, correctAt,
            correctAt.plusSeconds(17 * 86400 + 43200), reviewTags = listOf(ReviewTags.CORRECT)).getOrThrow()
        val failedAt = now.plusSeconds(20 * 86400)
        val failure = store.record(id, ReviewRating.CUSTOM_INTERVAL, failedAt,
            failedAt.plusSeconds(20 * 60), reviewTags = listOf(ReviewTags.WRONG, "notation")).getOrThrow()
        val restored = ReviewStore(null, root, incoming, events, cards)
        assertEquals(ReviewInterval(40, ReviewIntervalUnit.MINUTES), restored.suggestedInterval(id))
        restored.retractRating(id, failure.nameWithoutExtension).getOrThrow()
        assertEquals(ReviewInterval(5, ReviewIntervalUnit.WEEKS), restored.suggestedInterval(id))
        val never = restored.record(id, ReviewRating.RETAINED, failedAt.plusSeconds(1), reviewAction = "error").getOrThrow()
        assertEquals("error", JsonParser.parseString(never.readText()).asJsonObject.get("review_action").asString)
        assertTrue(restored.card(id)!!.schedule!!.retained)
        restored.retractRating(id, never.nameWithoutExtension).getOrThrow()
        assertFalse(restored.card(id)!!.schedule!!.retained)
        assertEquals(ReviewInterval(5, ReviewIntervalUnit.WEEKS), restored.suggestedInterval(id))
    }

    @Test
    fun `tags are written with a compatible action and counted with older answers`() {
        val root = temporary.newFolder("tags-root")
        val incoming = temporary.newFolder("tags-incoming")
        val events = temporary.newFolder("tags-events")
        val cards = temporary.newFolder("tags-cards")
        val first = writeCard(root, "paper", page = 1, revision = 1)
        val second = writeCard(root, "paper", page = 2, revision = 1)
        val store = ReviewStore(null, root, incoming, events, cards)
        // A different due time: identical answers at one instant count once, as a scoped batch.
        val tagged = store.record(first, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(700),
            reviewTags = listOf(" Skip ", "confused  notation", "skip")).getOrThrow()
        val payload = JsonParser.parseString(tagged.readText()).asJsonObject
        // Skip is a postponement, never an answer's tag.
        assertEquals(listOf("confused notation"), payload.getAsJsonArray("review_tags").map { it.asString })
        assertFalse(payload.has("review_action"))
        val custom = store.record(second, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600),
            reviewTags = listOf("confused notation")).getOrThrow()
        assertFalse(JsonParser.parseString(custom.readText()).asJsonObject.has("review_action"))
        store.record(second, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(1), now.plusSeconds(900), "error").getOrThrow()
        val restored = ReviewStore(null, root, incoming, events, cards)
        // Cards per current tag: the second card's later "error" answer moved it to wrong.
        assertEquals(mapOf("confused notation" to 1, "wrong" to 1), restored.reviewTagUsage())
        val browser = restored.browserCards(now).associateBy { it.card.id }
        assertEquals(listOf("confused notation"), browser.getValue(first).currentTags())
        assertEquals(listOf("wrong"), browser.getValue(second).currentTags())
        restored.retractRating(first, tagged.nameWithoutExtension).getOrThrow()
        // The second card's earlier answer keeps "confused notation" listed, with no card.
        assertEquals(mapOf("confused notation" to 0, "wrong" to 1), restored.reviewTagUsage())
    }

    @Test
    fun `group notes are recorded with the answer and omitted when empty`() {
        val root = temporary.newFolder("notes-root")
        val incoming = temporary.newFolder("notes-incoming")
        val events = temporary.newFolder("notes-events")
        val cards = temporary.newFolder("notes-cards")
        val first = writeCard(root, "paper", page = 1, revision = 1)
        val second = writeCard(root, "paper", page = 2, revision = 1)
        val store = ReviewStore(null, root, incoming, events, cards)
        val noted = store.recordScoped(first, ReviewSkipScope.CARD, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600),
            null, reviewTags = listOf("correct", "fair"),
            groupNotes = mapOf("g1" to " second step vague ", "g2" to "  ", "other" to "see p. 4")).getOrThrow()
        val payload = JsonParser.parseString(File(noted.getValue(first).let { id ->
            (events.walkTopDown() + cards.walkTopDown() + root.walkTopDown()).first { it.isFile && it.nameWithoutExtension == id }.path
        }).readText()).asJsonObject
        assertEquals(listOf("correct", "fair"), payload.getAsJsonArray("review_tags").map { it.asString })
        assertEquals(JsonParser.parseString("""{"g1":"second step vague","other":"see p. 4"}"""),
            payload.get("review_group_notes"))
        val plain = store.record(second, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600),
            reviewTags = listOf("correct"), groupNotes = mapOf("g1" to " ")).getOrThrow()
        assertFalse(JsonParser.parseString(plain.readText()).asJsonObject.has("review_group_notes"))
        val restored = ReviewStore(null, root, incoming, events, cards).browserCards(now).associateBy { it.card.id }
        assertEquals(mapOf("g1" to "second step vague", "other" to "see p. 4"),
            restored.getValue(first).history.single { it.rating == "custom_interval" }.groupNotes)
        assertEquals(mapOf("g1" to "second step vague", "other" to "see p. 4"), restored.getValue(first).currentGroupNotes())
        assertEquals(emptyMap<String, String>(), restored.getValue(second).currentGroupNotes())
    }

    @Test
    fun `context notes are shared by the cards of a document and the newest answer wins`() {
        val root = temporary.newFolder("context-root")
        val incoming = temporary.newFolder("context-incoming")
        val events = temporary.newFolder("context-events")
        val cards = temporary.newFolder("context-cards")
        val document = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
        val first = writeCard(root, "paper", page = 3, revision = 1, documentId = document)
        val second = writeCard(root, "paper", page = 3, revision = 1, documentId = document)
        val elsewhere = writeCard(root, "other", page = 3, revision = 1, documentId = "0b5c0a43-6a3e-4f43-9a55-3f7d4d1b2c11")
        val store = ReviewStore(null, root, incoming, events, cards)
        fun payload(file: File) = JsonParser.parseString(file.readText()).asJsonObject
        // A scoped answer carries the notes on the answered card's event only.
        val noted = store.recordScoped(first, ReviewSkipScope.CARD, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600),
            null, reviewTags = emptyList(), contextNotes = mapOf(
                ReviewContextNotes.Scope.PAGE to ReviewContextNotes.Note("3", "Page 3", " see figure 2 "),
                ReviewContextNotes.Scope.SECTION to ReviewContextNotes.Note("2 Method › 2.1 Data", "2.1 Data", "the data split"),
            )).getOrThrow()
        val notedPayload = payload((events.walkTopDown() + cards.walkTopDown()).first { it.isFile && it.nameWithoutExtension == noted.getValue(first) })
        assertEquals(JsonParser.parseString(
            """{"section":{"key":"2 Method › 2.1 Data","label":"2.1 Data","text":"the data split"},""" +
                """"page":{"key":"3","label":"Page 3","text":"see figure 2"}}"""),
            notedPayload.get("review_context_notes"))
        // An answer that changed no note leaves the field out.
        val plain = store.record(second, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(1), now.plusSeconds(600), reviewTags = emptyList()).getOrThrow()
        assertFalse(payload(plain).has("review_context_notes"))
        val secondCard = store.card(second)!!
        // The other card of the document shows them; another document has none.
        assertEquals(mapOf(("page" to "3") to "see figure 2", ("section" to "2 Method › 2.1 Data") to "the data split"),
            store.contextNotes(secondCard))
        assertEquals(emptyMap<Pair<String, String>, String>(), store.contextNotes(store.card(elsewhere)!!))
        // A later answer on the second card replaces the page note and clears the section note.
        val later = store.record(second, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(60), now.plusSeconds(900), reviewTags = emptyList(),
            contextNotes = mapOf(
                ReviewContextNotes.Scope.PAGE to ReviewContextNotes.Note("3", "Page 3", "figure 2 is mislabelled"),
                ReviewContextNotes.Scope.SECTION to ReviewContextNotes.Note("2 Method › 2.1 Data", "2.1 Data", ""),
            )).getOrThrow()
        // The desktop's answer on the first card, received from Fedora, sets the document note.
        val desktopEvent = UUID.randomUUID().toString()
        File(root, "events/desktop").apply { mkdirs() }.resolve("$desktopEvent.json").writeText(
            """{"schema_version":3,"event_id":"$desktopEvent","card_id":"$first","rating":"custom_interval",""" +
                """"scheduled_due_at":"${now.plusSeconds(7200)}","reviewed_at":"${now.plusSeconds(120)}","origin_id":"desktop",""" +
                """"review_tags":[],"review_context_notes":{"document":{"key":"","label":"Paper","text":"read with the appendix"}}}""")
        val restored = ReviewStore(null, root, incoming, events, cards)
        val expected = mapOf(
            ("page" to "3") to "figure 2 is mislabelled",
            ("section" to "2 Method › 2.1 Data") to "",
            ("document" to "") to "read with the appendix",
        )
        assertEquals(expected, restored.contextNotes(secondCard))
        assertEquals(expected, restored.contextNotes(restored.card(first)!!))
        val history = restored.browserCards(now).first { it.card.id == first }.history
        assertEquals(mapOf("document" to ReviewContextNotes.Note("", "Paper", "read with the appendix")),
            history.single { it.eventId == desktopEvent }.contextNotes)
        // Undoing the later answer gives back the first card's notes.
        restored.retractRating(second, later.nameWithoutExtension).getOrThrow()
        assertEquals(mapOf(
            ("page" to "3") to "see figure 2",
            ("section" to "2 Method › 2.1 Data") to "the data split",
            ("document" to "") to "read with the appendix",
        ), restored.contextNotes(secondCard))
        // A deleted card's notes stay with the document.
        restored.deleteCard(first, now.plusSeconds(300)).getOrThrow()
        assertEquals("read with the appendix", restored.contextNotes(secondCard)["document" to ""])
        assertFalse(runCatching {
            restored.record(second, ReviewRating.DELETE, now.plusSeconds(400), contextNotes = mapOf(
                ReviewContextNotes.Scope.PAGE to ReviewContextNotes.Note("3", "Page 3", "x"))).getOrThrow()
        }.isSuccess)
    }

    @Test
    fun `a desktop conversion skip carries the document note and is no answer`() {
        val root = temporary.newFolder("conversion-root")
        val incoming = temporary.newFolder("conversion-incoming")
        val events = temporary.newFolder("conversion-events")
        val cards = temporary.newFolder("conversion-cards")
        val document = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
        val other = writeCard(root, "rules", page = 2, revision = 1, documentId = document)
        // An old whole-document card, as the phone and desktop used to create them.
        val whole = UUID.randomUUID().toString()
        val full = "[0.0,0.0,1.0,1.0]"
        File(root, "cards/$whole.json").writeText(
            """{"schema_version":4,"card_id":"$whole","status":"approved","revision":1,"source_link":{"state":"whole_document"},""" +
                """"primary":{"pdf_stem":"rules","document_id":"$document","scope":"document","pdf_sha256":"${"a".repeat(64)}",""" +
                """"page":1,"rect":$full,"regions":[{"page":1,"rect":$full},{"page":2,"rect":$full}],""" +
                """"page_visual_sha256":"${"b".repeat(64)}","question":"household rules"}}""")
        val store = ReviewStore(null, root, incoming, events, cards)
        val before = store.browserCards(now).associateBy { it.card.id }
        assertTrue(before.getValue(whole).card.wholeDocument)
        // The desktop's conversion: a one-minute skip carrying the note, then the deletion.
        val skip = UUID.randomUUID().toString()
        val deletion = UUID.randomUUID().toString()
        val desktop = File(root, "events/desktop").apply { mkdirs() }
        desktop.resolve("$skip.json").writeText(
            """{"schema_version":3,"event_id":"$skip","card_id":"$whole","skip_id":"$skip","queue_action":"skip",""" +
                """"skip_until":"${now.plusSeconds(60)}","reviewed_at":"$now","updated_at":"$now","origin_id":"desktop",""" +
                """"review_version":{"pdf_sha256":"${"c".repeat(64)}","source_built":true},""" +
                """"review_context_notes":{"document":{"key":"","label":"Rules","text":"household rules\nkeep the fridge list"}}}""")
        val skipped = ReviewStore(null, root, incoming, events, cards)
        // The skip is no answer: the card has no tags, answers or interval from it.
        val converted = skipped.browserCards(now).associateBy { it.card.id }.getValue(whole)
        assertEquals(0, converted.reviewCount)
        assertNull(converted.currentTags())
        assertEquals(emptyMap<String, Int>(), skipped.reviewTagUsage())
        assertEquals("skip", converted.history.single { it.eventId == skip }.queueAction)
        assertEquals(mapOf(("document" to "") to "household rules\nkeep the fridge list"),
            skipped.contextNotes(skipped.card(other)!!))
        desktop.resolve("$deletion.json").writeText(
            """{"schema_version":3,"event_id":"$deletion","card_id":"$whole","rating":"delete","reviewed_at":"$now",""" +
                """"updated_at":"$now","origin_id":"desktop","marker_removal":"not_required"}""")
        val restored = ReviewStore(null, root, incoming, events, cards)
        // Deleted, the card leaves the browser; the document's other card still shows the note.
        assertEquals(listOf(other), restored.browserCards(now).map { it.card.id })
        assertEquals(mapOf(("document" to "") to "household rules\nkeep the fridge list"),
            restored.contextNotes(restored.card(other)!!))
    }

    @Test
    fun `answers and skips record the version of the card's PDF, deletions do not`() {
        val root = temporary.newFolder("version-root")
        val incoming = temporary.newFolder("version-incoming")
        val events = temporary.newFolder("version-events")
        val cards = temporary.newFolder("version-cards")
        val pdfs = temporary.newFolder("version-pdfs")
        val built = writeCard(root, "notes", page = 1, revision = 1)
        val imported = writeCard(root, "paper", page = 1, revision = 1)
        val missing = writeCard(root, "gone", page = 1, revision = 1)
        File(pdfs, "notes.pdf").writeText("%PDF notes v2")
        File(pdfs, "paper.pdf").writeText("%PDF imported")
        fun sha(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun stamper() = ReviewVersionStamper(
            pdfFile = { card -> File(pdfs, "${card.pdfStem}.pdf") },
            trustedSha256 = { null },
            sourceBuilt = { card, _ -> card.pdfStem == "notes" },
        )
        val store = ReviewStore(null, root, incoming, events, cards).apply { versionStamper = stamper() }
        fun payload(file: File) = JsonParser.parseString(file.readText()).asJsonObject
        fun stampOf(file: File) = ReviewVersionStamp.of(payload(file))
        // An answer on a source-built PDF and one on an imported PDF.
        val answer = store.record(built, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600), reviewTags = emptyList()).getOrThrow()
        assertEquals(ReviewVersionStamp(sha("%PDF notes v2"), true), stampOf(answer))
        val importedAnswer = store.recordScoped(imported, ReviewSkipScope.CARD, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600),
            null, reviewTags = listOf("fair")).getOrThrow().getValue(imported)
        val importedFile = events.walkTopDown().first { it.isFile && it.nameWithoutExtension == importedAnswer }
        assertEquals(ReviewVersionStamp(sha("%PDF imported"), false), stampOf(importedFile))
        // No PDF on the phone: the answer is recorded without the stamp.
        val unstamped = store.record(missing, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600), reviewTags = emptyList()).getOrThrow()
        assertFalse(payload(unstamped).has("review_version"))
        // A skip carries the stamp of the skipped card's PDF.
        val batch = store.skip(built, ReviewSkipScope.CARD, now.plusSeconds(3600), now = now.plusSeconds(1)).getOrThrow()
        val skipFile = (events.walkTopDown() + cards.walkTopDown()).first { file ->
            file.isFile && file.extension == "json" && runCatching { payload(file) }.getOrNull()
                ?.let { it.get("queue_action")?.asString == "skip" && it.get("skip_id")?.asString == batch.id } == true
        }
        assertEquals(ReviewVersionStamp(sha("%PDF notes v2"), true), stampOf(skipFile))
        // A deletion needs none.
        val deletion = store.deleteCard(imported, now.plusSeconds(2)).getOrThrow()
        assertFalse(payload(deletion).has("review_version"))
        // Read back, the events keep their stamps and the answers are as before.
        val restored = ReviewStore(null, root, incoming, events, cards)
        val history = restored.browserCards(now).first { it.card.id == built }.history
        assertEquals(listOf(answer.nameWithoutExtension), history.filter { it.rating == "custom_interval" }.map { it.eventId })
        assertEquals(ReviewVersionStamp(sha("%PDF notes v2"), true), stampOf(answer))
        // Without a stamper (no PDF known), answering still works.
        val plain = ReviewStore(null, root, incoming, events, cards)
            .record(built, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(5), now.plusSeconds(900), reviewTags = emptyList()).getOrThrow()
        assertFalse(payload(plain).has("review_version"))
        // A stamper that fails never fails the answer.
        val failing = ReviewStore(null, root, incoming, events, cards).apply {
            versionStamper = ReviewVersionStamper({ error("library unreadable") }, { null }, { _, _ -> true })
        }
        assertFalse(payload(failing.record(built, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(6), now.plusSeconds(900),
            reviewTags = emptyList()).getOrThrow()).has("review_version"))
        ReviewVersionStamper.forgetHashesForTesting()
    }

    @Test
    fun `tags, slider steps, group notes, the answer note and context notes carry the answer's version`() {
        val root = temporary.newFolder("feedback-root")
        val incoming = temporary.newFolder("feedback-incoming")
        val events = temporary.newFolder("feedback-events")
        val cards = temporary.newFolder("feedback-cards")
        val card = writeCard(root, "notes", page = 1, revision = 1)
        val stamp = ReviewVersionStamp("d".repeat(64), true)
        val pdf = temporary.newFile("notes-feedback.pdf").apply { writeText("%PDF") }
        val store = ReviewStore(null, root, incoming, events, cards).apply {
            versionStamper = ReviewVersionStamper({ pdf }, { stamp.pdfSha256 }, { _, _ -> true })
        }
        // They are all fields of the one answer event (no record of their own), so its stamp covers them.
        val answer = store.record(card, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600),
            reviewTags = listOf("I get it", "Pen and paper"),
            groupNotes = mapOf("understanding" to "Mostly", ReviewGroupNotes.ANSWER_NOTE_KEY to "Check eq. 3"),
            contextNotes = mapOf(ReviewContextNotes.Scope.PAGE to ReviewContextNotes.Note("1", "Page 1", "Dense page")),
        ).getOrThrow()
        val payload = JsonParser.parseString(answer.readText()).asJsonObject
        assertEquals(stamp, ReviewVersionStamp.of(payload))
        assertEquals("Check eq. 3", payload.getAsJsonObject(ReviewGroupNotes.FIELD).get("note").asString)
        assertEquals("Dense page", payload.getAsJsonObject(ReviewContextNotes.FIELD).getAsJsonObject("page").get("text").asString)
        assertEquals(listOf("I get it", "Pen and paper"), payload.getAsJsonArray("review_tags").map { it.asString })
    }

    @Test
    fun `a person's card creates and edits record the PDF version and keep unknown fields`() {
        val root = temporary.newFolder("card-stamp")
        val outgoing = temporary.newFolder("card-stamp-out")
        val pdf = temporary.newFile("card-stamp.pdf").apply { writeText("%PDF") }
        // A card from the server, written by another client: its stamp and a field this client does not know.
        val serverCard = writeCard(root, "paper", page = 3, revision = 1)
        val serverFile = File(root, "cards/$serverCard.json")
        serverFile.writeText(JsonParser.parseString(serverFile.readText()).asJsonObject.apply {
            add("review_version", ReviewVersionStamp("f".repeat(64), true).toJson())
            addProperty("future_field", "kept")
        }.toString())
        var known = true
        val store = ReviewStore(null, root, temporary.newFolder("card-stamp-in"), temporary.newFolder("card-stamp-events"), outgoing).apply {
            versionStamper = ReviewVersionStamper({ pdf.takeIf { known } }, { "e".repeat(64) }, { _, _ -> true })
        }
        fun record(cardId: String) = outgoing.walkTopDown().filter { it.isFile && it.extension == "json" && it.readText().contains(cardId) }
            .map { JsonParser.parseString(it.readText()).asJsonObject }.single()
        fun stamp(cardId: String) = ReviewVersionStamp.of(record(cardId))
        // Made on the displayed PDF (an imported one: not source-built).
        val rect = RectF().apply { left = 0.1f; top = 0.1f; right = 0.5f; bottom = 0.5f }
        val card = store.createManualCard("paper", "a".repeat(64), 1, rect, "What is hidden?", createdAt = now,
            sourceAvailable = false, documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22").getOrThrow()
        assertEquals(ReviewVersionStamp("a".repeat(64), false), stamp(card.id))
        // Edits replace the stamp with the card PDF's current version; the unknown field survives.
        store.updateQuestion(serverCard, "What is shown?", now.plusSeconds(1)).getOrThrow()
        assertEquals(ReviewVersionStamp("e".repeat(64), true), stamp(serverCard))
        assertEquals("kept", record(serverCard).get("future_field").asString)
        store.updateNote(card.id, "See figure 2", now.plusSeconds(2)).getOrThrow()
        assertEquals(ReviewVersionStamp("e".repeat(64), true), stamp(card.id))
        // An edit whose PDF version is unknown removes the older stamp (it would name another version).
        known = false
        store.updateRegion(card.id, "a".repeat(64), 1, RectF().apply { left = 0.2f; top = 0.2f; right = 0.6f; bottom = 0.6f },
            now.plusSeconds(3)).getOrThrow()
        assertNull(stamp(card.id))
        known = true
        store.updateCardAreas(card.id, "a".repeat(64), 1, listOf(ReviewAreaBox(1, 0.1f, 0.1f, 0.3f, 0.3f)), null,
            now.plusSeconds(4)).getOrThrow()
        assertEquals(ReviewVersionStamp("e".repeat(64), true), stamp(card.id))
        assertEquals("kept", record(serverCard).get("future_field").asString)
        ReviewVersionStamper.forgetHashesForTesting()
    }

    @Test
    fun `skip postpones a card and keeps its tags`() {
        val root = temporary.newFolder("skip-root")
        val incoming = temporary.newFolder("skip-incoming")
        val events = temporary.newFolder("skip-events")
        val cards = temporary.newFolder("skip-cards")
        val id = writeCard(root, "paper", page = 1, revision = 1)
        val store = ReviewStore(null, root, incoming, events, cards)
        store.record(id, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(60), reviewTags = listOf("correct")).getOrThrow()
        val until = now.plusSeconds(4 * 86400L)
        store.skip(id, ReviewSkipScope.CARD, until, now = now.plusSeconds(120)).getOrThrow()
        val restored = ReviewStore(null, root, incoming, events, cards)
        assertEquals(listOf("correct"), restored.browserCards(now.plusSeconds(180)).single().currentTags())
        assertTrue(restored.dueCards(now.plusSeconds(86400)).isEmpty())
        assertEquals(mapOf("correct" to 1), restored.reviewTagUsage())
    }

    @Test
    fun `tag counts are the cards that have each tag now, deleted cards aside`() {
        val root = temporary.newFolder("counts-root")
        val incoming = temporary.newFolder("counts-incoming")
        val events = temporary.newFolder("counts-events")
        val cards = temporary.newFolder("counts-cards")
        val first = writeCard(root, "paper", page = 1, revision = 1)
        val second = writeCard(root, "paper", page = 2, revision = 1)
        val third = writeCard(root, "paper", page = 3, revision = 1)
        writeCard(root, "paper", page = 4, revision = 1)
        val store = ReviewStore(null, root, incoming, events, cards)
        store.record(first, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600), reviewTags = listOf("wrong")).getOrThrow()
        val again = store.record(first, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(60), now.plusSeconds(900),
            reviewTags = listOf("correct", "notation")).getOrThrow()
        store.record(second, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600), reviewTags = listOf("correct")).getOrThrow()
        store.record(third, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(600), reviewTags = listOf("correct")).getOrThrow()
        // The fourth card was never answered and counts for nothing; the first is no longer wrong,
        // which stays listed with no card.
        assertEquals(mapOf("correct" to 3, "notation" to 1, "wrong" to 0), ReviewStore(null, root, incoming, events, cards).reviewTagUsage())
        store.record(third, ReviewRating.DELETE, now.plusSeconds(120)).getOrThrow()
        assertEquals(mapOf("correct" to 2, "notation" to 1, "wrong" to 0), ReviewStore(null, root, incoming, events, cards).reviewTagUsage())
        // Undoing the first card's latest answer moves it back to wrong; notation was only on that answer.
        store.retractRating(first, again.nameWithoutExtension, now.plusSeconds(180)).getOrThrow()
        assertEquals(mapOf("correct" to 1, "wrong" to 1), ReviewStore(null, root, incoming, events, cards).reviewTagUsage())
    }

    @Test
    fun `a review starts with the last answer's tags and a new card with none`() {
        val root = temporary.newFolder("start-root")
        val incoming = temporary.newFolder("start-incoming")
        val events = temporary.newFolder("start-events")
        val cards = temporary.newFolder("start-cards")
        val id = writeCard(root, "paper", page = 1, revision = 1)
        val store = ReviewStore(null, root, incoming, events, cards)
        assertNull(store.currentReviewState(id).first)
        val tagged = store.record(id, ReviewRating.CUSTOM_INTERVAL, now, now.plusSeconds(60),
            reviewTags = listOf("hard", "notation")).getOrThrow()
        assertEquals(listOf("hard", "notation"), store.currentReviewState(id).first)
        // Next with no tag selected records an answer without tags, not an older "correct" answer.
        val untagged = store.record(id, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(120), now.plusSeconds(600),
            reviewTags = emptyList()).getOrThrow()
        val payload = JsonParser.parseString(untagged.readText()).asJsonObject
        assertEquals(0, payload.getAsJsonArray("review_tags").size())
        assertFalse(payload.has("review_action"))
        val restored = ReviewStore(null, root, incoming, events, cards)
        assertEquals(emptyList<String>(), restored.currentReviewState(id).first)
        // The card's current answer has no tags, so it counts toward none; the earlier ones stay listed.
        assertEquals(mapOf("hard" to 0, "notation" to 0), restored.reviewTagUsage())
        assertEquals(emptyList<String>(), restored.browserCards(now.plusSeconds(180)).single().currentTags())
        // A skip keeps them; undoing an answer goes back to the one before, then to none.
        restored.skip(id, ReviewSkipScope.CARD, now.plusSeconds(4 * 86400L), now = now.plusSeconds(180)).getOrThrow()
        assertEquals(emptyList<String>(), restored.currentReviewState(id).first)
        restored.retractRating(id, untagged.nameWithoutExtension, now.plusSeconds(240)).getOrThrow()
        assertEquals(listOf("hard", "notation"), restored.currentReviewState(id).first)
        restored.retractRating(id, tagged.nameWithoutExtension, now.plusSeconds(300)).getOrThrow()
        assertNull(ReviewStore(null, root, incoming, events, cards).currentReviewState(id).first)
        // Calls without a tag list still write none, so readers derive the tag as for older answers.
        val legacy = restored.record(id, ReviewRating.GOOD, now.plusSeconds(360)).getOrThrow()
        assertFalse(JsonParser.parseString(legacy.readText()).asJsonObject.has("review_tags"))
        assertEquals(listOf("correct"), restored.currentReviewState(id).first)
    }

    @Test
    fun `a document answer gives each card it covered its tags`() {
        val root = temporary.newFolder("scoped-root")
        val incoming = temporary.newFolder("scoped-incoming")
        val events = temporary.newFolder("scoped-events")
        val cards = temporary.newFolder("scoped-cards")
        val first = writeCard(root, "paper", page = 1, revision = 1)
        val second = writeCard(root, "paper", page = 2, revision = 1)
        val other = writeCard(root, "other", page = 1, revision = 1)
        val store = ReviewStore(null, root, incoming, events, cards)
        val recorded = store.recordScoped(first, ReviewSkipScope.DOCUMENT, ReviewRating.CUSTOM_INTERVAL, now,
            now.plusSeconds(600), null, reviewTags = listOf("wrong", "notation")).getOrThrow()
        assertEquals(setOf(first, second), recorded.keys)
        val restored = ReviewStore(null, root, incoming, events, cards)
        assertEquals(listOf("wrong", "notation"), restored.currentReviewState(first).first)
        assertEquals(listOf("wrong", "notation"), restored.currentReviewState(second).first)
        assertNull(restored.currentReviewState(other).first)
        // A later answer on one card changes only that card's tags.
        restored.record(second, ReviewRating.CUSTOM_INTERVAL, now.plusSeconds(60), now.plusSeconds(900),
            reviewTags = listOf("correct")).getOrThrow()
        assertEquals(listOf("wrong", "notation"), restored.currentReviewState(first).first)
        assertEquals(listOf("correct"), restored.currentReviewState(second).first)
    }

    @Test
    fun `tags sort by use and built ins match their names`() {
        assertEquals(listOf("correct", "wrong"), ReviewTags.byPopularity(emptyMap()))
        assertEquals(listOf("hard", "correct", "wrong", "later"),
            ReviewTags.byPopularity(mapOf("hard" to 5, "skip" to 2), extra = listOf("later")))
        assertEquals("wrong", ReviewTags.normalize(" WRONG "))
        assertEquals("correct", ReviewTags.normalize("good", mapOf("correct" to "Good")))
        assertEquals("two words", ReviewTags.normalize(" two \t words "))
        assertNull(ReviewTags.normalize("   "))
        assertEquals("wrong", ReviewTags.legacyReviewAction(listOf("skip", "wrong")))
        assertNull(ReviewTags.legacyReviewAction(listOf("hard")))
    }

    @Test
    fun `multi page answer survives question edits and rejects partial invalid masks`() {
        val root = temporary.newFolder("multi-page")
        val incoming = temporary.newFolder("multi-page-incoming")
        val events = temporary.newFolder("multi-page-events")
        val outgoing = temporary.newFolder("multi-page-outgoing")
        val id = writeCard(root, "paper", page = 7, revision = 1)
        val file = File(root, "cards/$id.json")
        val payload = JsonParser.parseString(file.readText()).asJsonObject
        payload.addProperty("schema_version", 4)
        payload.getAsJsonObject("primary").addProperty("source_kind", "source")
        payload.getAsJsonObject("primary").add("regions", JsonParser.parseString(
            """[{"page":7,"rect":[0.1,0.8,0.8,0.9]},{"page":8,"rect":[0.1,0.1,0.8,0.2]}]""",
        ))
        file.writeText(payload.toString())
        val store = ReviewStore(null, root, incoming, events, outgoing)
        val card = store.cards().single()
        assertEquals("7, 8", card.pagesLabel)
        assertEquals(setOf(6, 7), card.masksByPage.keys)
        val edited = store.updateQuestion(id, "An edited question?").getOrThrow()
        assertEquals(listOf(7, 8), edited.regions.map { it.page })
        assertEquals(0.1f, edited.masksByPage.getValue(7).single().top)
        assertTrue(store.updateCardContent(id, card.pdfSha256, 7,
            reviewRect(.2f, .2f, .6f, .4f), null).isFailure)

        val badRoot = temporary.newFolder("invalid-multi-page")
        File(badRoot, "cards").mkdirs()
        payload.getAsJsonObject("primary").getAsJsonArray("regions")[1].asJsonObject.addProperty("page", 0)
        File(badRoot, "cards/$id.json").writeText(payload.toString())
        assertTrue(ReviewStore(null, badRoot, temporary.newFolder("invalid-incoming"),
            temporary.newFolder("invalid-events"), temporary.newFolder("invalid-outgoing")).cards().isEmpty())
    }

    @Test
    fun `manual card preserves a visible linking failure without a server job`() {
        // Not chosen: nothing is saved.
        val refused = ReviewStore(null, temporary.newFolder("manual-refused"), temporary.newFolder("manual-refused-in"),
            temporary.newFolder("manual-refused-events"), temporary.newFolder("manual-refused-out"))
        assertTrue(refused.createManualCard("paper", "a".repeat(64), 3, RectF().apply { left = 0.2f; top = 0.3f; right = 0.6f; bottom = 0.55f },
            "What is hidden here?", createdAt = now, documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22")
            .exceptionOrNull() is com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException)
        assertTrue(refused.cards().isEmpty())

        val root = temporary.newFolder("manual-reviews")
        val incoming = temporary.newFolder("manual-incoming")
        val outgoingEvents = temporary.newFolder("manual-outgoing-events")
        val outgoingCards = temporary.newFolder("manual-outgoing-cards")
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)

        val card = store.createManualCard(
            documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22",
            pdfStem = "paper",
            pdfSha256 = "a".repeat(64),
            page = 3,
            rect = RectF().apply {
                left = 0.2f
                top = 0.3f
                right = 0.6f
                bottom = 0.55f
            },
            question = "What is hidden here?",
            createdAt = now,
            // Without a verified source the area cannot be linked; the person chose markers by hand.
            placeMarkersByHand = true,
        ).getOrThrow()

        assertEquals("approved", card.status)
        assertEquals("needs_attention", card.reanchorState)
        assertEquals("linking_failed", card.sourceLinkState)
        assertEquals(1, outgoingCards.walkTopDown().count { it.isFile && it.extension == "json" })
        assertEquals(card.id, store.cards().single().id)
        assertTrue(store.dueCards(now).isEmpty())
    }

    @Test
    fun `generated cards enter the review queue immediately`() {
        val root = temporary.newFolder("generated-reviews")
        val incoming = temporary.newFolder("generated-incoming")
        val outgoingEvents = temporary.newFolder("generated-outgoing-events")
        val outgoingCards = temporary.newFolder("generated-outgoing-cards")
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)
        val candidates = store.importGeneratedCandidates(
            pdfStem = "paper",
            pdfSha256 = "a".repeat(64),
            candidates = listOf(
                GeneratedReviewCandidate(
                    page = 2,
                    rect = reviewRect(0.1f, 0.2f, 0.6f, 0.5f),
                    question = "What is the central result?",
                    pageVisualSha256 = "b".repeat(64),
                    cardId = "10000000-0000-4000-8000-000000000001",
                    note = "Possible duplicate of an earlier question.",
                    duplicateCheck = com.google.gson.JsonObject().apply {
                        addProperty("duplicate_of", "existing-card")
                    },
                ),
                GeneratedReviewCandidate(
                    page = 3,
                    rect = reviewRect(0.2f, 0.3f, 0.7f, 0.6f),
                    question = "Which assumption is required?",
                    pageVisualSha256 = "c".repeat(64),
                ),
            ),
            createdAt = now, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow()

        assertEquals("10000000-0000-4000-8000-000000000001", candidates[0].id)
        assertEquals("Possible duplicate of an earlier question.", candidates[0].note)
        assertEquals(listOf("approved", "approved"), candidates.map(ReviewCard::status))
        assertEquals(listOf("pdf", "pdf"), candidates.map(ReviewCard::sourceKind))
        assertTrue(store.cards().all { it.sourceKind == "pdf" })
        assertEquals(now, candidates[0].approvedAt)
        assertEquals(candidates.map(ReviewCard::id).toSet(), store.dueCards(now).map(ReviewCard::id).toSet())

    }

    @Test
    fun `generated card import drops overlapping regions`() {
        val root = temporary.newFolder("generated-dedup-reviews")
        val incoming = temporary.newFolder("generated-dedup-incoming")
        val outgoingEvents = temporary.newFolder("generated-dedup-events")
        val outgoingCards = temporary.newFolder("generated-dedup-cards")
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)
        val region = reviewRect(0.1f, 0.2f, 0.6f, 0.5f)
        store.importGeneratedCandidates(
            "paper",
            "a".repeat(64),
            listOf(GeneratedReviewCandidate(2, region, "First question?", "b".repeat(64))),
            now, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow()

        val duplicate = store.importGeneratedCandidates(
            "paper",
            "a".repeat(64),
            listOf(
                GeneratedReviewCandidate(
                    2,
                    reviewRect(region.left, region.top, region.right, region.bottom),
                    "Duplicate?",
                    "b".repeat(64),
                ),
            ),
            now.plusSeconds(1), documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow()

        assertTrue(duplicate.isEmpty())
        assertEquals(1, store.cards().size)
        val flagged = store.importGeneratedCandidates(
            "paper", "a".repeat(64),
            listOf(GeneratedReviewCandidate(
                2, region, "Rephrased first question?", "b".repeat(64),
                "10000000-0000-4000-8000-000000000002", "Possible duplicate.",
                com.google.gson.JsonObject().apply { addProperty("duplicate_of", "existing") },
            )), now.plusSeconds(2), documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow()
        assertEquals(1, flagged.size)
        assertEquals("Possible duplicate.", flagged.single().note)
    }

    private fun reviewRect(left: Float, top: Float, right: Float, bottom: Float) =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    @Test
    fun `uses Anki style learning and relearning steps`() {
        val again = ReviewStore.nextSchedule(null, ReviewRating.AGAIN, now)
        assertEquals(now.plusSeconds(60), again.dueAt)
        assertEquals(0, again.learningStep)
        assertFalse(again.relearning)

        val hard = ReviewStore.nextSchedule(null, ReviewRating.HARD, now)
        assertEquals(now.plusSeconds(6 * 60), hard.dueAt)
        assertEquals(0, hard.learningStep)

        val good = ReviewStore.nextSchedule(null, ReviewRating.GOOD, now)
        assertEquals(now.plusSeconds(10 * 60), good.dueAt)
        assertEquals(1, good.learningStep)
        val repeatedFinalStep = ReviewStore.nextSchedule(good, ReviewRating.HARD, now.plusSeconds(10 * 60))
        assertEquals(now.plusSeconds(20 * 60), repeatedFinalStep.dueAt)
        val graduated = ReviewStore.nextSchedule(good, ReviewRating.GOOD, now.plusSeconds(10 * 60))
        assertEquals(3, graduated.intervalDays)
        assertEquals(now.plus(3, ChronoUnit.DAYS).plusSeconds(10 * 60), graduated.dueAt)

        assertEquals(7, ReviewStore.nextSchedule(null, ReviewRating.EASY, now).intervalDays)

        val establishedReview = ReviewSchedule(now, 20)
        val relearning = ReviewStore.nextSchedule(establishedReview, ReviewRating.AGAIN, now)
        assertEquals(now.plusSeconds(10 * 60), relearning.dueAt)
        assertTrue(relearning.relearning)
        assertEquals(20, relearning.previousIntervalDays)
        assertEquals(
            now.plusSeconds(15 * 60),
            ReviewStore.nextSchedule(relearning, ReviewRating.HARD, now).dueAt,
        )
        assertEquals(1, ReviewStore.nextSchedule(relearning, ReviewRating.GOOD, now).intervalDays)

        assertEquals(365, ReviewStore.nextSchedule(ReviewSchedule(now, 200), ReviewRating.EASY, now).intervalDays)
    }

    @Test
    fun `legacy events retain their original fixed schedules`() {
        assertEquals(
            1,
            ReviewStore.nextSchedule(null, ReviewRating.HARD, now, schedulerVersion = 0).intervalDays,
        )
        assertEquals(
            3,
            ReviewStore.nextSchedule(null, ReviewRating.GOOD, now, schedulerVersion = 0).intervalDays,
        )
        assertEquals(
            7,
            ReviewStore.nextSchedule(null, ReviewRating.EASY, now, schedulerVersion = 0).intervalDays,
        )
        assertEquals(
            now.plusSeconds(10 * 60),
            ReviewStore.nextSchedule(null, ReviewRating.AGAIN, now, schedulerVersion = 0).dueAt,
        )

        val legacyNewLearning = ReviewStore.nextSchedule(
            null,
            ReviewRating.AGAIN,
            now,
            schedulerVersion = 0,
        )
        assertEquals(
            1,
            ReviewStore.nextSchedule(legacyNewLearning, ReviewRating.GOOD, now).learningStep,
        )
        val legacyReviewLapse = ReviewStore.nextSchedule(
            ReviewSchedule(now, 20),
            ReviewRating.AGAIN,
            now,
            schedulerVersion = 0,
        )
        assertTrue(legacyReviewLapse.relearning)
        assertEquals(1, ReviewStore.nextSchedule(legacyReviewLapse, ReviewRating.GOOD, now).intervalDays)
    }

    @Test
    fun `delete is terminal`() {
        val deleted = ReviewStore.nextSchedule(ReviewSchedule(now, 3), ReviewRating.DELETE, now)
        assertTrue(deleted.deleted)
        assertEquals(0, deleted.intervalDays)
    }

    @Test
    fun `summarizes due cards by state and pdf deck`() {
        val root = temporary.newFolder("summary-reviews")
        val incoming = temporary.newFolder("summary-incoming")
        val outgoing = temporary.newFolder("summary-outgoing")
        val newCard = writeCard(root, "paper")
        val learningCard = writeCard(root, "paper")
        val reviewCard = writeCard(root, "other")
        val futureCard = writeCard(root, "other")
        val deletedCard = writeCard(root, "deleted")
        writeCard(root, "ignored", status = "candidate")
        writeEvent(root, learningCard, ReviewRating.AGAIN, now.minus(20, ChronoUnit.MINUTES))
        writeEvent(root, reviewCard, ReviewRating.GOOD, now.minus(4, ChronoUnit.DAYS))
        writeEvent(root, futureCard, ReviewRating.GOOD, now.minus(1, ChronoUnit.DAYS))
        writeEvent(root, deletedCard, ReviewRating.DELETE, now.minus(1, ChronoUnit.DAYS))

        val store = ReviewStore(null, root, incoming, outgoing)
        val summary = store.summary(now)

        assertEquals(ReviewQueueCounts(1, 1, 1), summary.counts)
        assertEquals(4, summary.activeCount)
        assertEquals(now.plus(2, ChronoUnit.DAYS), summary.nextDueAt)
        assertEquals(listOf("paper", "other"), summary.decks.map { it.pdfStem })
        assertEquals(ReviewQueueCounts(1, 1, 0), summary.decks.first().counts)
        assertEquals(2, store.dueCards(now, "paper").size)
        assertEquals(setOf(newCard, learningCard, reviewCard), store.dueCards(now).map { it.id }.toSet())
    }

    @Test
    fun `review projections share one process snapshot while files are unchanged`() {
        val root = temporary.newFolder("snapshot-reviews")
        val incoming = temporary.newFolder("snapshot-incoming")
        val outgoingEvents = temporary.newFolder("snapshot-outgoing-events")
        val outgoingCards = temporary.newFolder("snapshot-outgoing-cards")
        val cardId = writeCard(root, "paper")
        writeEvent(root, cardId, ReviewRating.GOOD, now.minus(4, ChronoUnit.DAYS))
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)

        assertEquals(1, store.summary(now).counts.reviewCount)
        val buildsAfterFirstProjection = store.snapshotBuildCountForTesting()
        assertEquals(1L, buildsAfterFirstProjection)

        assertEquals(cardId, store.card(cardId)?.id)
        assertEquals(1, store.cards().size)
        assertEquals(1, store.browserCards(now).size)
        assertEquals(1, store.dueCards(now).size)
        assertTrue(store.pendingChanges().isEmpty())

        val secondStore = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)
        assertEquals(cardId, secondStore.card(cardId)?.id)
        assertEquals(buildsAfterFirstProjection, store.snapshotBuildCountForTesting())
        assertEquals(buildsAfterFirstProjection, secondStore.snapshotBuildCountForTesting())
    }

    @Test
    fun `snapshot detects externally added review files`() {
        val root = temporary.newFolder("external-snapshot-reviews")
        val incoming = temporary.newFolder("external-snapshot-incoming")
        val outgoingEvents = temporary.newFolder("external-snapshot-outgoing-events")
        val outgoingCards = temporary.newFolder("external-snapshot-outgoing-cards")
        writeCard(root, "first")
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)

        assertEquals(1, store.cards().size)
        val buildsBeforeExternalChange = store.snapshotBuildCountForTesting()
        val secondCardId = writeCard(incoming, "second")

        assertEquals(setOf("first", "second"), store.cards().map(ReviewCard::pdfStem).toSet())
        assertEquals(secondCardId, store.card(secondCardId)?.id)
        assertTrue(store.snapshotBuildCountForTesting() > buildsBeforeExternalChange)
    }

    @Test
    fun `due counts advance with time without rebuilding the review snapshot`() {
        val root = temporary.newFolder("clock-snapshot-reviews")
        val incoming = temporary.newFolder("clock-snapshot-incoming")
        val outgoingEvents = temporary.newFolder("clock-snapshot-outgoing-events")
        val outgoingCards = temporary.newFolder("clock-snapshot-outgoing-cards")
        val cardId = writeCard(root, "paper")
        writeEvent(root, cardId, ReviewRating.GOOD, now)
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)

        assertEquals(0, store.summary(now).counts.reviewCount)
        val buildsBeforeDueTime = store.snapshotBuildCountForTesting()

        assertEquals(1, store.summary(now.plus(4, ChronoUnit.DAYS)).counts.reviewCount)
        assertEquals(buildsBeforeDueTime, store.snapshotBuildCountForTesting())
    }

    @Test
    fun `concurrent review readers publish only one snapshot`() {
        val root = temporary.newFolder("concurrent-snapshot-reviews")
        val incoming = temporary.newFolder("concurrent-snapshot-incoming")
        val outgoingEvents = temporary.newFolder("concurrent-snapshot-outgoing-events")
        val outgoingCards = temporary.newFolder("concurrent-snapshot-outgoing-cards")
        repeat(12) { writeCard(root, "paper-$it") }
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)
        val executor = Executors.newFixedThreadPool(6)

        try {
            val results = List(24) {
                executor.submit<ReviewLibrarySummary> { store.summary(now) }
            }.map { future -> future.get() }

            assertTrue(results.all { it.activeCount == 12 })
            assertEquals(1L, store.snapshotBuildCountForTesting())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cards returned to callers cannot mutate the cached rectangle`() {
        val root = temporary.newFolder("immutable-snapshot-reviews")
        val incoming = temporary.newFolder("immutable-snapshot-incoming")
        val outgoingEvents = temporary.newFolder("immutable-snapshot-outgoing-events")
        val outgoingCards = temporary.newFolder("immutable-snapshot-outgoing-cards")
        val cardId = writeCard(root, "paper")
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)

        store.card(cardId)?.rect?.left = 0.45f

        assertEquals(0.1f, store.card(cardId)?.rect?.left ?: -1f, 0f)
        assertEquals(1L, store.snapshotBuildCountForTesting())
    }

    @Test
    fun `learning queue uses Anki twenty minute lookahead`() {
        val root = temporary.newFolder("learn-ahead-reviews")
        val incoming = temporary.newFolder("learn-ahead-incoming")
        val outgoing = temporary.newFolder("learn-ahead-outgoing")
        val newCard = writeCard(root, "paper")
        val nearLearningCard = writeCard(root, "paper")
        val laterLearningCard = writeCard(root, "paper")
        val dueLearningCard = writeCard(root, "paper")
        val overdueReviewCard = writeCard(root, "paper")
        writeEvent(root, nearLearningCard, ReviewRating.GOOD, now, schedulerVersion = 2)
        writeEvent(
            root,
            laterLearningCard,
            ReviewRating.GOOD,
            now.plus(11, ChronoUnit.MINUTES),
            schedulerVersion = 2,
        )
        writeEvent(
            root,
            dueLearningCard,
            ReviewRating.AGAIN,
            now.minus(1, ChronoUnit.MINUTES),
            schedulerVersion = 2,
        )
        writeEvent(root, overdueReviewCard, ReviewRating.GOOD, now.minus(5, ChronoUnit.DAYS))

        val store = ReviewStore(null, root, incoming, outgoing)

        assertEquals(
            // A learning step that just came due waits behind the older overdue review.
            listOf(overdueReviewCard, dueLearningCard, newCard, nearLearningCard),
            store.dueCards(now).map(ReviewCard::id),
        )
        assertEquals(
            ReviewQueueCounts(newCount = 1, learningCount = 2, reviewCount = 1),
            store.summary(now).counts,
        )
        assertEquals(
            ReviewBrowserState.LEARNING,
            store.browserCards(now).single { it.card.id == nearLearningCard }.state,
        )
        assertEquals(
            ReviewBrowserState.LEARNING,
            store.browserCards(now).single { it.card.id == laterLearningCard }.state,
        )
    }

    @Test
    fun `next due time is when learning card enters the lookahead window`() {
        val root = temporary.newFolder("learning-refresh-reviews")
        val incoming = temporary.newFolder("learning-refresh-incoming")
        val outgoing = temporary.newFolder("learning-refresh-outgoing")
        val cardId = writeCard(root, "paper")
        writeEvent(root, cardId, ReviewRating.GOOD, now, schedulerVersion = 2)
        val summaryNow = now.minus(20, ChronoUnit.MINUTES)

        val summary = ReviewStore(null, root, incoming, outgoing).summary(summaryNow)

        assertEquals(ReviewQueueCounts(), summary.counts)
        assertEquals(now.minus(10, ChronoUnit.MINUTES), summary.nextDueAt)
    }

    @Test
    fun `learning card already inside lookahead has no future count transition`() {
        val root = temporary.newFolder("learning-lookahead-reviews")
        val incoming = temporary.newFolder("learning-lookahead-incoming")
        val outgoing = temporary.newFolder("learning-lookahead-outgoing")
        val cardId = writeCard(root, "paper")
        writeEvent(root, cardId, ReviewRating.GOOD, now, schedulerVersion = 2)

        val summary = ReviewStore(null, root, incoming, outgoing).summary(now)

        assertEquals(ReviewQueueCounts(learningCount = 1), summary.counts)
        assertEquals(null, summary.nextDueAt)
    }

    @Test
    fun `delete event preserves the source card and removes it from due queues`() {
        val root = temporary.newFolder("delete-reviews")
        val incoming = temporary.newFolder("delete-incoming")
        val outgoing = temporary.newFolder("delete-outgoing")
        val cardId = writeCard(root, "paper")
        val source = File(root, "cards/$cardId.json")
        val original = source.readText()
        val store = ReviewStore(null, root, incoming, outgoing)

        store.record(cardId, ReviewRating.DELETE, now).getOrThrow()

        assertEquals(original, source.readText())
        assertTrue(store.card(cardId)?.schedule?.deleted == true)
        assertTrue(store.dueCards(now.plus(1, ChronoUnit.DAYS)).isEmpty())
        assertEquals(1, outgoing.walkTopDown().count { it.isFile && it.extension == "json" })
    }

    @Test
    fun `deletion cannot be retracted and stays hidden after reopening`() {
        val root = temporary.newFolder("retraction-reviews")
        val incoming = temporary.newFolder("retraction-incoming")
        val outgoing = temporary.newFolder("retraction-outgoing")
        val cardId = writeCard(root, "paper")
        val store = ReviewStore(null, root, incoming, outgoing)
        val deleted = store.record(cardId, ReviewRating.DELETE, now).getOrThrow()
        assertTrue(store.retractRating(cardId, deleted.nameWithoutExtension, now.plusSeconds(1)).isFailure)
        val reopened = ReviewStore(null, root, incoming, outgoing)
        assertTrue(reopened.card(cardId)?.schedule?.deleted == true)
        assertTrue(reopened.browserCards(now).isEmpty())
        assertTrue(reopened.dueCards(now.plus(10, ChronoUnit.DAYS)).isEmpty())
    }

    @Test
    fun `inferred source deletion is ignored while a deliberate deletion remains final`() {
        val root = temporary.newFolder("inferred-deletion")
        val incoming = temporary.newFolder("inferred-deletion-incoming")
        val outgoing = temporary.newFolder("inferred-deletion-outgoing")
        val cardId = writeCard(root, "paper")
        val legacy = JsonParser.parseString(
            """{"schema_version":3,"event_id":"${UUID.randomUUID()}","card_id":"$cardId","rating":"delete","origin_id":"thoughts-source-review","reason":"source_markers_removed","reviewed_at":"$now"}""",
        ).asJsonObject
        File(root, "events/test").apply { mkdirs() }
            .resolve("${legacy.get("event_id").asString}.json").writeText(legacy.toString())
        val fileDeletion = JsonParser.parseString(
            """{"schema_version":3,"event_id":"${UUID.randomUUID()}","card_id":"$cardId","rating":"delete","origin_id":"thoughts-document-deletion","reason":"document_deleted","document_deletion":{"source_files":["docs-paper/tex/source/main.tex"],"filenames":[],"project_document_removed":false},"reviewed_at":"${now.plusSeconds(1)}"}""",
        ).asJsonObject
        File(root, "events/test/${fileDeletion.get("event_id").asString}.json").writeText(fileDeletion.toString())
        val store = ReviewStore(null, root, incoming, outgoing)
        assertFalse(store.card(cardId)?.schedule?.deleted == true)
        assertFalse(ReviewStore.foldEvents(listOf(legacy, fileDeletion))[cardId]?.deleted == true)
        store.record(cardId, ReviewRating.DELETE, now.plusSeconds(2)).getOrThrow()
        assertTrue(ReviewStore(null, root, incoming, outgoing).card(cardId)?.schedule?.deleted == true)
    }

    @Test
    fun `retraction cannot cancel a rating from another card`() {
        val firstCard = UUID.randomUUID().toString()
        val secondCard = UUID.randomUUID().toString()
        val ratingId = UUID.randomUUID().toString()
        val retractionId = UUID.randomUUID().toString()
        val events = listOf(
            com.google.gson.JsonParser.parseString(
                """{"event_id":"$ratingId","card_id":"$firstCard","rating":"good","reviewed_at":"$now"}""",
            ).asJsonObject,
            com.google.gson.JsonParser.parseString(
                """{"event_id":"$retractionId","card_id":"$secondCard","retracted_event_id":"$ratingId","reviewed_at":"${now.plusSeconds(1)}"}""",
            ).asJsonObject,
        )

        val schedules = ReviewStore.foldEvents(events)

        assertEquals(3, schedules[firstCard]?.intervalDays)
    }

    @Test
    fun `records to the established phone to mac transport`() {
        val dedicated = temporary.newFolder("docs-clean-reviews")
        val incoming = temporary.newFolder("mac-to-phone")
        val outgoing = temporary.newFolder("phone-to-mac")
        val cardId = UUID.randomUUID().toString()

        val file = ReviewStore(null, dedicated, incoming, outgoing)
            .record(cardId, ReviewRating.GOOD, now)
            .getOrThrow()

        assertTrue(file.canonicalPath.startsWith(outgoing.canonicalPath))
        assertTrue(file.isFile)
        assertEquals(2, JsonParser.parseString(file.readText()).asJsonObject["scheduler_version"].asInt)
        assertTrue(java.io.File(dedicated, "events/test-phone/${file.name}").isFile)
    }

    @Test
    fun `pending rating keeps its document identity even when the pdf is not open`() {
        val root = temporary.newFolder("pending-reviews")
        val incoming = temporary.newFolder("pending-incoming")
        val outgoing = temporary.newFolder("pending-outgoing")
        val cardId = writeCard(root, "archived-paper")
        val store = ReviewStore(null, root, incoming, outgoing)

        val event = store.record(cardId, ReviewRating.EASY, now).getOrThrow()

        assertEquals(
            PendingReviewChange(
                entityType = "review_event",
                entityId = event.nameWithoutExtension,
                pdfStem = "archived-paper",
                createdAtEpochMillis = now.toEpochMilli(),
            ),
            store.pendingChanges().single(),
        )
    }

    @Test
    fun `loads schema three visual region cards with questions only`() {
        val root = temporary.newFolder("visual-reviews")
        val incoming = temporary.newFolder("visual-incoming")
        val outgoing = temporary.newFolder("visual-outgoing")
        val cardId = UUID.randomUUID().toString()
        val cards = File(root, "cards").apply { mkdirs() }
        File(cards, "$cardId.json").writeText(
            """{"schema_version":3,"card_id":"$cardId","status":"approved","primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":7,"rect":[0.1,0.2,0.8,0.6],"page_visual_sha256":"${"b".repeat(64)}","question":"What does this result demonstrate?"}}""",
        )

        val loaded = ReviewStore(null, root, incoming, outgoing).cards()

        assertEquals(1, loaded.size)
        assertEquals(7, loaded.single().page)
        assertEquals("b".repeat(64), loaded.single().pageVisualSha256)
        assertEquals("What does this result demonstrate?", loaded.single().question)
        assertEquals(1, loaded.single().rects.size)
    }

    @Test
    fun `loads visual regions larger than half a page`() {
        val root = temporary.newFolder("oversized-reviews")
        val incoming = temporary.newFolder("oversized-incoming")
        val outgoing = temporary.newFolder("oversized-outgoing")
        val cardId = UUID.randomUUID().toString()
        val cards = File(root, "cards").apply { mkdirs() }
        File(cards, "$cardId.json").writeText(
            """{"schema_version":3,"card_id":"$cardId","status":"approved","primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":7,"rect":[0.0,0.0,1.0,0.6],"page_visual_sha256":"${"b".repeat(64)}","question":"What is the result?"}}""",
        )

        val loaded = ReviewStore(null, root, incoming, outgoing).cards()
        assertEquals(1, loaded.size)
        assertEquals(0.6f, loaded.single().rect.bottom)
    }

    @Test
    fun `rejects cards with missing blank or overlong questions`() {
        val root = temporary.newFolder("question-reviews")
        val incoming = temporary.newFolder("question-incoming")
        val outgoing = temporary.newFolder("question-outgoing")
        val cards = File(root, "cards").apply { mkdirs() }
        val primaryPrefix = """"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":7,"rect":[0.1,0.2,0.8,0.6],"page_visual_sha256":"${"b".repeat(64)}""""
        listOf(null, "   ", "x".repeat(501)).forEach { question ->
            val questionJson = question?.let { ",\"question\":\"$it\"" }.orEmpty()
            File(cards, "${UUID.randomUUID()}.json").writeText(
                """{"schema_version":3,"card_id":"${UUID.randomUUID()}","status":"approved","primary":{$primaryPrefix$questionJson}}""",
            )
        }

        assertTrue(ReviewStore(null, root, incoming, outgoing).cards().isEmpty())
    }

    @Test
    fun `does not import legacy schema two cards`() {
        val root = temporary.newFolder("legacy-reviews")
        val incoming = temporary.newFolder("legacy-incoming")
        val outgoing = temporary.newFolder("legacy-outgoing")
        val cardId = UUID.randomUUID().toString()
        val cards = File(root, "cards").apply { mkdirs() }
        File(cards, "$cardId.json").writeText(
            """{"schema_version":2,"card_id":"$cardId","status":"approved","primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":7,"rect":[0.1,0.2,0.8,0.6],"page_visual_sha256":"${"b".repeat(64)}","question":"What is the result?"}}""",
        )

        assertTrue(ReviewStore(null, root, incoming, outgoing).cards().isEmpty())
    }

    @Test
    fun `server card projection overrides legacy public storage duplicate`() {
        val root = temporary.newFolder("precedence-reviews")
        val incoming = temporary.newFolder("precedence-incoming")
        val outgoing = temporary.newFolder("precedence-outgoing")
        val cardId = UUID.randomUUID().toString()
        writeCard(root, "paper", cardId = cardId, pdfSha256 = "a".repeat(64), page = 1)
        writeCard(incoming, "paper", cardId = cardId, pdfSha256 = "d".repeat(64), page = 9)

        val card = ReviewStore(null, root, incoming, outgoing).cards().single()

        assertEquals("d".repeat(64), card.pdfSha256)
        assertEquals(9, card.page)
    }

    @Test
    fun `canonical hash excludes stale cards and reports repair state`() {
        val root = temporary.newFolder("canonical-reviews")
        val incoming = temporary.newFolder("canonical-incoming")
        val outgoing = temporary.newFolder("canonical-outgoing")
        writeCard(root, "paper", pdfSha256 = "a".repeat(64))
        writeCard(
            incoming,
            "queued",
            status = "needs_reanchor",
            reanchorState = "queued",
        )
        writeCard(
            incoming,
            "attention",
            status = "needs_reanchor",
            reanchorState = "needs_attention",
        )
        val store = ReviewStore(null, root, incoming, outgoing)
        val canonical = mapOf("paper.pdf" to "b".repeat(64))

        val summary = store.summary(now, canonical)

        assertTrue(store.dueCards(now, canonicalPdfSha256ByFilename = canonical).isEmpty())
        assertEquals(0, summary.activeCount)
        assertEquals(2, summary.repairingCount)
        assertEquals(1, summary.needsAttentionCount)
    }

    @Test
    fun `only cards the server is still placing on a PDF on this device await placement`() {
        val root = temporary.newFolder("awaiting-placement")
        val incoming = temporary.newFolder("awaiting-placement-incoming")
        val outgoing = temporary.newFolder("awaiting-placement-outgoing")
        val current = "c".repeat(64)
        val placed = writeCard(incoming, "rebuilt", pdfSha256 = current, reanchorState = "succeeded")
        val rebuilt = writeCard(incoming, "rebuilt", pdfSha256 = "a".repeat(64), reanchorState = "succeeded")
        val running = writeCard(incoming, "rebuilt", pdfSha256 = current, reanchorState = "running")
        writeCard(incoming, "rebuilt", pdfSha256 = "a".repeat(64), reanchorState = "needs_attention")
        writeCard(incoming, "retired", pdfSha256 = "a".repeat(64), reanchorState = "succeeded")
        writeCard(incoming, "rebuilt", status = "candidate", pdfSha256 = "a".repeat(64))
        val store = ReviewStore(null, root, incoming, outgoing)

        val waiting = store.cardsAwaitingPlacement(mapOf("rebuilt.pdf" to current))

        assertEquals(setOf(rebuilt, running), waiting)
        assertTrue(placed !in waiting)
        // Waiting cards are not unavailable; only the card needing attention is.
        assertEquals(1, store.summary(now, mapOf("rebuilt.pdf" to current)).unavailableCount)
    }

    @Test
    fun `pending link stays out of review without counting as unavailable but a failed link counts`() {
        val root = temporary.newFolder("unavailable-source-link")
        val incoming = temporary.newFolder("unavailable-source-incoming")
        val outgoing = temporary.newFolder("unavailable-source-outgoing")
        writeCard(incoming, "paper", sourceLinkState = "pending")
        writeCard(incoming, "paper")
        val store = ReviewStore(null, root, incoming, outgoing)

        assertEquals(1, store.browserCards(now).count { it.hasIssues })
        assertEquals(0, store.summary(now).unavailableCount)
        assertEquals(1, store.summary(now).counts.total)
        assertEquals(1, store.dueCards(now).size)

        writeCard(incoming, "paper", sourceLinkState = "linking_failed")
        val updated = ReviewStore(null, root, incoming, outgoing)
        assertEquals(1, updated.summary(now).unavailableCount)
        assertEquals(1, updated.browserCards(now).count { it.needsAction })
    }

    @Test
    fun `a retired card is neither counted nor listed as unavailable`() {
        val root = temporary.newFolder("unavailable-retired")
        val incoming = temporary.newFolder("unavailable-retired-incoming")
        val outgoing = temporary.newFolder("unavailable-retired-outgoing")
        val retired = writeCard(incoming, "paper", reanchorState = "needs_attention")
        val broken = writeCard(incoming, "paper", sourceLinkState = "linking_failed")
        val store = ReviewStore(null, root, incoming, outgoing)
        assertEquals(setOf(retired, broken), store.browserCards(now).filter { it.needsAction }.map { it.card.id }.toSet())
        assertEquals(2, store.summary(now).unavailableCount)

        store.retire(retired, now).getOrThrow()

        assertEquals(listOf(broken), store.browserCards(now).filter { it.needsAction }.map { it.card.id })
        assertEquals(1, store.summary(now).unavailableCount)
    }

    @Test
    fun `verified source anchor supersedes an earlier failed link to another file`() {
        val root = temporary.newFolder("anchored-link")
        val incoming = temporary.newFolder("anchored-link-incoming")
        val outgoing = temporary.newFolder("anchored-link-outgoing")
        val id = writeCard(incoming, "paper", reanchorState = "succeeded", sourceLinkState = "linking_failed")
        val file = File(incoming, "cards/$id.json")
        val record = JsonParser.parseString(file.readText()).asJsonObject
        record.getAsJsonObject("primary").add("source_anchor", JsonParser.parseString(
            """{"version":2,"kind":"source_markers","segments":[{"source_file":"docs-paper/tex/abstract.tex","line_start":2,"line_end":9}]}""",
        ))
        record.getAsJsonObject("source_link").apply {
            addProperty("source_file", "docs-paper/tex/benchmark.tex")
            addProperty("reason", "The selection covers only part of a source line.")
        }
        file.writeText(record.toString())
        val store = ReviewStore(null, root, incoming, outgoing)

        val card = store.browserCards(now).single()
        assertEquals(listOf("docs-paper/tex/abstract.tex"), card.card.sourceFiles)
        assertEquals("linked", card.card.sourceLinkState)
        assertEquals("", card.card.sourceLinkReason)
        assertFalse(card.hasIssues)
        assertEquals(0, store.summary(now).unavailableCount)
        assertEquals(listOf(id), store.dueCards(now).map { it.id })
        assertEquals("linking_failed", JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonObject("source_link").get("state").asString)
    }

    @Test
    fun `saved basic PDF never makes stale cards available`() {
        val root = temporary.newFolder("saved-basic")
        val store = ReviewStore(null, root, temporary.newFolder("saved-in"), temporary.newFolder("saved-events"), temporary.newFolder("saved-out"))
        val pdf = File(temporary.newFolder("original-pdf"), "paper.pdf").apply { writeText("original PDF bytes") }
        val sha = basicPdfSha256(pdf)
        val candidates = JsonParser.parseString("""[{"key":"${"e".repeat(64)}","regions":[{"page":1,"rect":[0,0,1,0.5]}]}]""").asJsonArray
        store.createBasicCards("paper", sha, listOf("b".repeat(64)), candidates, now, documentId = "047d798c-07c0-4bfb-902a-fc5b9d78a170").getOrThrow()
        val canonical = mapOf("paper.pdf" to "c".repeat(64))
        assertEquals(1, store.summary(now, canonical).repairingCount)
        assertEquals(0, store.summary(now, canonical).activeCount)

        File(root, "pdfs/$sha/paper.pdf").apply {
            parentFile!!.mkdirs()
            pdf.copyTo(this)
        }
        assertEquals(1, store.summary(now, canonical).repairingCount)
        assertEquals(0, store.summary(now, canonical).activeCount)
        assertTrue(store.dueCards(now, canonicalPdfSha256ByFilename = canonical).isEmpty())

        File(root, "pdfs/$sha/paper.pdf").writeText("corrupted PDF bytes")
        assertEquals(1, store.summary(now, canonical).repairingCount)
        assertEquals(0, store.summary(now, canonical).activeCount)
    }

    @Test
    fun `deleted cards never count as unavailable even after a PDF update`() {
        val root = temporary.newFolder("deleted-unavailable")
        val incoming = temporary.newFolder("deleted-unavailable-incoming")
        val outgoing = temporary.newFolder("deleted-unavailable-outgoing")
        val store = ReviewStore(null, root, incoming, outgoing)
        val canonical = mapOf("paper.pdf" to "b".repeat(64))
        val ids = listOf(null, "queued", "retry", "running", "needs_attention").map { state ->
            UUID.randomUUID().toString().also { id ->
                writeCard(incoming, "paper", cardId = id, pdfSha256 = "a".repeat(64), reanchorState = state)
            }
        }
        assertEquals(4, store.summary(now, canonical).repairingCount)
        assertEquals(1, store.summary(now, canonical).needsAttentionCount)
        ids.forEach { store.record(it, ReviewRating.DELETE, now).getOrThrow() }
        val reopened = ReviewStore(null, root, incoming, outgoing)
        val summary = reopened.summary(now, canonical)
        assertEquals(0, summary.repairingCount)
        assertEquals(0, summary.needsAttentionCount)
        assertEquals(0, summary.activeCount)
        assertTrue(reopened.dueCards(now, canonicalPdfSha256ByFilename = canonical).isEmpty())
        assertTrue(reopened.browserCards(now).isEmpty())
        assertEquals(ids.size, reopened.cards().size)
    }

    @Test
    fun `daily queue ordering uses the shared UTC date seed`() {
        val root = temporary.newFolder("utc-order-reviews")
        val incoming = temporary.newFolder("utc-order-incoming")
        val outgoing = temporary.newFolder("utc-order-outgoing")
        val cardIds = listOf(
            "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000005",
            "00000000-0000-4000-8000-000000000008",
        )
        cardIds.forEach { cardId -> writeCard(incoming, "paper", cardId = cardId) }

        val ordered = ReviewStore(null, root, incoming, outgoing)
            .dueCards(Instant.parse("2026-08-20T23:30:00Z"))
            .map(ReviewCard::id)

        assertEquals(
            listOf(
                "00000000-0000-4000-8000-000000000005",
                "00000000-0000-4000-8000-000000000008",
                "00000000-0000-4000-8000-000000000001",
            ),
            ordered,
        )
    }

    @Test
    fun `browser snapshot exposes metadata effective history and pending delete`() {
        val root = temporary.newFolder("browser-history-reviews")
        val incoming = temporary.newFolder("browser-history-incoming")
        val outgoing = temporary.newFolder("browser-history-outgoing")
        val cardId = UUID.randomUUID().toString()
        val cards = File(incoming, "cards/server").apply { mkdirs() }
        File(cards, "$cardId.json").writeText(
            """{"schema_version":3,"card_type":"visual_region","card_id":"$cardId","status":"approved","revision":7,"origin_id":"fedora-system","created_at":"2026-08-01T10:00:00Z","updated_at":"2026-08-04T10:00:00Z","approved_at":"2026-08-02T10:00:00Z","primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":7,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"What is hidden?","source_anchor":{"version":2,"segments":[{"segment_id":"answer","source_file":"docs-paper/tex/source/main.tex"}]}},"reanchor":{"state":"succeeded","reason":"verified"}}""",
        )
        val goodId = writeEvent(root, cardId, ReviewRating.GOOD, now.minus(4, ChronoUnit.DAYS))
        val againId = writeEvent(outgoing, cardId, ReviewRating.AGAIN, now.minus(20, ChronoUnit.MINUTES))
        val deleteId = writeEvent(outgoing, cardId, ReviewRating.DELETE, now.minusSeconds(30))
        val store = ReviewStore(null, root, incoming, outgoing)

        assertTrue(store.browserCards(now).isEmpty())
        assertTrue(store.card(cardId)?.schedule?.deleted == true)
        assertTrue(store.restoreCard(cardId, now).isFailure)
        assertTrue(store.browserCards(now).isEmpty())
        // An old client may upload a restore before or after its delete event.
        val retract = com.google.gson.JsonObject().apply {
            addProperty("event_id", UUID.randomUUID().toString())
            addProperty("card_id", cardId)
            addProperty("retracted_event_id", deleteId)
            addProperty("reviewed_at", now.toString())
        }
        File(outgoing, "legacy-restore.json").writeText(retract.toString())
        val reopened = ReviewStore(null, root, incoming, outgoing)
        assertTrue(reopened.browserCards(now).isEmpty())
        assertTrue(reopened.card(cardId)?.schedule?.deleted == true)
    }

    @Test
    fun `question edit deep copies repaired server payload and advances revision`() {
        val root = temporary.newFolder("edit-reviews")
        val incoming = temporary.newFolder("edit-incoming")
        val outgoingEvents = temporary.newFolder("edit-outgoing-events")
        val outgoingCards = temporary.newFolder("edit-outgoing-cards")
        val cardId = UUID.randomUUID().toString()
        val serverCards = File(incoming, "cards/server").apply { mkdirs() }
        File(serverCards, "$cardId.json").writeText(
            """{"schema_version":3,"card_type":"visual_region","card_id":"$cardId","status":"approved","revision":5,"origin_id":"fedora-system","created_at":"2026-08-01T10:00:00Z","updated_at":"2026-08-04T10:00:00Z","primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":9,"rect":[0.2,0.3,0.6,0.5],"page_visual_sha256":"${"b".repeat(64)}","question":"Server repaired question"},"reanchor":{"state":"succeeded","strategy":"source_markers_v2"},"server_extension":{"keep":true}}""",
        )
        val staleOutgoing = File(outgoingCards, "old/$cardId.json").apply { parentFile?.mkdirs() }
        staleOutgoing.writeText(
            """{"schema_version":3,"card_type":"visual_region","card_id":"$cardId","status":"approved","revision":4,"origin_id":"phone","created_at":"2026-08-01T10:00:00Z","updated_at":"2026-08-03T10:00:00Z","primary":{"pdf_stem":"paper","pdf_sha256":"${"c".repeat(64)}","page":2,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"d".repeat(64)}","question":"Stale local question"}}""",
        )
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)

        assertEquals(9, store.card(cardId)?.page)
        val edited = store.updateQuestion(
            cardId,
            "  Updated on the phone  ",
            Instant.parse("2026-08-05T13:00:00Z"),
        ).getOrThrow()

        assertEquals("Updated on the phone", edited.question)
        assertEquals(6, edited.revision)
        assertEquals(9, edited.page)
        val payload = outgoingCards.walkTopDown()
            .filter { it.isFile && it.name == "$cardId.json" }
            .map { JsonParser.parseString(it.readText()).asJsonObject }
            .maxBy { it.get("revision").asInt }
        assertTrue(payload.getAsJsonObject("server_extension").get("keep").asBoolean)
        assertEquals("source_markers_v2", payload.getAsJsonObject("reanchor").get("strategy").asString)
        assertEquals("test-phone", payload.get("origin_id").asString)
        assertTrue(store.browserCards(now).single().pendingSync)
    }

    @Test
    fun `same revision pending question wins until a higher server repair arrives`() {
        val root = temporary.newFolder("pending-precedence-reviews")
        val incoming = temporary.newFolder("pending-precedence-incoming")
        val outgoingEvents = temporary.newFolder("pending-precedence-events")
        val outgoingCards = temporary.newFolder("pending-precedence-cards")
        val cardId = UUID.randomUUID().toString()
        writeCard(incoming, "paper", cardId = cardId, page = 3, revision = 8, question = "Server")
        writeCard(outgoingCards, "paper", cardId = cardId, page = 3, revision = 8, question = "Pending")
        val store = ReviewStore(null, root, incoming, outgoingEvents, outgoingCards)

        assertEquals("Pending", store.card(cardId)?.question)

        writeCard(incoming, "paper", cardId = cardId, page = 11, revision = 9, question = "Repaired")
        assertEquals("Repaired", store.card(cardId)?.question)
        assertEquals(11, store.card(cardId)?.page)
        assertTrue(store.browserCards(now).single().pendingSync)
    }

    @Test
    fun `region edit survives reopening and preserves newer question and review history`() {
        val root = temporary.newFolder("region-reviews")
        val incoming = temporary.newFolder("region-incoming")
        val events = temporary.newFolder("region-events")
        val cards = temporary.newFolder("region-cards")
        val id = writeCard(incoming, "paper", page = 3, revision = 5)
        writeEvent(root, id, ReviewRating.GOOD, now.minus(1, ChronoUnit.DAYS))
        val store = ReviewStore(null, root, incoming, events, cards)
        val schedule = store.card(id)!!.schedule
        store.updateQuestion(id, "A newer question", now).getOrThrow()
        val rect = RectF().apply { left = 0.2f; top = 0.3f; right = 0.7f; bottom = 0.6f }

        store.updateRegion(id, "a".repeat(64), 3, rect, now.plusSeconds(1)).getOrThrow()

        val reopened = ReviewStore(null, root, incoming, events, cards).browserCards(now).single()
        assertEquals("A newer question", reopened.card.question)
        assertEquals(7, reopened.revision)
        assertEquals(schedule, reopened.card.schedule)
        assertEquals(1, reopened.reviewCount)
        assertTrue(reopened.pendingSync)
        assertEquals(0.2f, reopened.card.rect.left, 0.00001f)
        assertEquals(0.6f, reopened.card.rect.bottom, 0.00001f)
        assertEquals("b".repeat(64), reopened.card.pageVisualSha256)
    }

    @Test
    fun `PDF-only region boxes resize and move durably without losing other pages or history`() {
        val root = temporary.newFolder("pdf-box-root")
        val incoming = temporary.newFolder("pdf-box-in")
        val events = temporary.newFolder("pdf-box-events")
        val outgoing = temporary.newFolder("pdf-box-out")
        val id = writeCard(root, "paper", page = 3, revision = 5)
        val file = File(root, "cards/$id.json")
        val payload = JsonParser.parseString(file.readText()).asJsonObject
        payload.addProperty("schema_version", 4)
        payload.getAsJsonObject("primary").apply {
            addProperty("source_kind", "pdf")
            add("regions", JsonParser.parseString(
                """[{"page":3,"rect":[0.1,0.2,0.7,0.3]},
                    {"page":3,"rect":[0.1,0.3,0.8,0.4]},
                    {"page":4,"rect":[0.2,0.1,0.7,0.2]}]"""))
        }
        file.writeText(payload.toString())
        writeEvent(root, id, ReviewRating.GOOD, now.minus(1, ChronoUnit.DAYS))
        val store = ReviewStore(null, root, incoming, events, outgoing)
        val original = store.card(id)!!
        assertTrue(original.canEditAnswerBox)
        assertEquals(0.8f, original.answerBox.right)
        assertEquals(0.4f, original.answerBox.bottom)
        val questionOnly = store.updateCardContent(id, original.pdfSha256, 3,
            original.answerBox, "New question", now).getOrThrow()
        assertEquals(3, questionOnly.answerRegions.size)
        val box = reviewRect(.2f, .4f, .9f, .7f)
        store.updateCardContent(id, original.pdfSha256, 3, box, "New question", now.plusSeconds(1)).getOrThrow()
        val reopened = ReviewStore(null, root, incoming, events, outgoing).browserCards(now).single()
        assertEquals(7, reopened.revision)
        assertEquals(original.schedule, reopened.card.schedule)
        assertEquals(1, reopened.reviewCount)
        assertTrue(reopened.pendingSync)
        assertEquals("New question", reopened.card.question)
        assertEquals(listOf(3, 4), reopened.card.regions.map { it.page })
        assertEquals(.2f, reopened.card.masksByPage.getValue(2).single().left)
        assertEquals(.7f, reopened.card.masksByPage.getValue(2).single().bottom)
        assertEquals(.1f, reopened.card.masksByPage.getValue(3).single().top)
        assertEquals(.7f, reopened.card.rect.bottom)
        assertFalse(original.copy(sourceKind = "source").canEditAnswerBox)
        assertFalse(original.copy(sourceAnchor = com.google.gson.JsonObject()).canEditAnswerBox)
        assertFalse(original.copy(sourceFiles = listOf("source.tex")).canEditAnswerBox)
        val linkedPayload = JsonParser.parseString(file.readText()).asJsonObject
        linkedPayload.addProperty("revision", 20)
        linkedPayload.getAsJsonObject("primary").add("source_anchor", com.google.gson.JsonObject())
        file.writeText(linkedPayload.toString())
        val linkedStore = ReviewStore(null, root, incoming, events, outgoing)
        val before = outgoing.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
        assertTrue(linkedStore.updateRegion(id, original.pdfSha256, 3, box).isFailure)
        assertEquals(before, outgoing.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() })
    }

    @Test
    fun `region edit rejects stale PDF or page and invalid boxes without writing`() {
        val root = temporary.newFolder("invalid-region-reviews")
        val incoming = temporary.newFolder("invalid-region-incoming")
        val events = temporary.newFolder("invalid-region-events")
        val cards = temporary.newFolder("invalid-region-cards")
        val id = writeCard(incoming, "paper", page = 3, revision = 5)
        val store = ReviewStore(null, root, incoming, events, cards)
        fun rect(left: Float = 0.2f, right: Float = 0.7f) = RectF().apply {
            this.left = left; top = 0.3f; this.right = right; bottom = 0.6f
        }
        assertTrue(store.updateRegion(id, "c".repeat(64), 3, rect()).isFailure)
        assertTrue(store.updateRegion(id, "a".repeat(64), 2, rect()).isFailure)
        assertTrue(store.updateRegion(id, "a".repeat(64), 3, rect(Float.NaN)).isFailure)
        assertTrue(store.updateRegion(id, "a".repeat(64), 3, rect(-0.1f)).isFailure)
        assertTrue(store.updateRegion(id, "a".repeat(64), 3, rect(0.8f, 0.7f)).isFailure)
        assertTrue(store.updateRegion(id, "a".repeat(64), 3, rect(right = Float.POSITIVE_INFINITY)).isFailure)
        assertFalse(cards.walkTopDown().any { it.isFile })
        assertEquals(5, store.card(id)!!.revision)
    }

    @Test
    fun `combined edit writes one revision and rejects invalid or stale changes atomically`() {
        val root = temporary.newFolder("combined-reviews")
        val incoming = temporary.newFolder("combined-incoming")
        val events = temporary.newFolder("combined-events")
        val cards = temporary.newFolder("combined-cards")
        val id = writeCard(incoming, "paper", page = 3, revision = 5)
        val store = ReviewStore(null, root, incoming, events, cards)
        store.updateNote(id, "Keep this note", now).getOrThrow()
        val rect = RectF().apply { left = 0.2f; top = 0.3f; right = 0.7f; bottom = 0.6f }
        val edited = store.updateCardContent(id, "a".repeat(64), 3, rect, "  New question  ", now).getOrThrow()
        assertEquals(7, edited.revision)
        assertEquals("New question", edited.question)
        assertEquals("Keep this note", edited.note)
        assertEquals(0.6f, edited.rect.bottom, 0.00001f)
        val saved = cards.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
        assertTrue(store.updateCardContent(id, "a".repeat(64), 3, rect, " ", now).isFailure)
        assertTrue(store.updateCardContent(id, "c".repeat(64), 3, rect, "Rejected", now).isFailure)
        assertTrue(store.updateCardContent(id, "a".repeat(64), 4, rect, "Rejected", now).isFailure)
        assertEquals(saved, cards.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() })
        val reopened = ReviewStore(null, root, incoming, events, cards).card(id)!!
        assertEquals(edited.question, reopened.question)
        assertEquals(edited.revision, reopened.revision)
        assertEquals(0.2f, reopened.rect.left, 0.00001f)
        assertEquals(7, store.updateCardContent(id, "a".repeat(64), 3, rect, "New question", now).getOrThrow().revision)
    }

    @Test
    fun `project deck cache ignores other projects changing or becoming unreadable`() {
        val root = temporary.newFolder("isolated-reviews")
        val incoming = temporary.newFolder("isolated-incoming")
        val outgoing = temporary.newFolder("isolated-outgoing")
        val alpha = writeCard(root, "alpha")
        val beta = writeCard(root, "beta")
        val store = ReviewStore(null, root, incoming, outgoing)
        assertEquals(listOf(alpha), store.dueCards(now, "alpha").map { it.id })
        val count = store.projectSnapshotBuildCountForTesting("alpha")
        val betaFile = File(root, "cards/$beta.json")
        betaFile.writeText("{broken unrelated project")
        assertEquals(listOf(alpha), store.dueCards(now, "alpha").map { it.id })
        assertEquals(count, store.projectSnapshotBuildCountForTesting("alpha"))
        writeEvent(root, alpha, ReviewRating.DELETE, now)
        assertTrue(store.dueCards(now, "alpha").isEmpty())
        assertEquals("{broken unrelated project", betaFile.readText())
    }

    private fun sourceIndex(path: String, source: String) = com.google.gson.JsonObject().apply {
        val scanned = com.gitlab.mudlej.MjPdfReader.data.source.SourceAnnotationMarkup
            .scan(source, !path.endsWith(".tex")).apply { addProperty("project", path.substringBefore('/')) }
        addProperty("schema_version", 1)
        add("files", com.google.gson.JsonObject().apply { add(path, scanned) })
        add("projects", com.google.gson.JsonObject().apply { addProperty(path.substringBefore('/'), true) })
    }

    private fun writeCard(
        root: File,
        pdfStem: String,
        status: String = "approved",
        cardId: String = UUID.randomUUID().toString(),
        pdfSha256: String = "a".repeat(64),
        page: Int = 1,
        reanchorState: String? = null,
        sourceLinkState: String? = null,
        revision: Int? = null,
        question: String = "What is hidden?",
        documentId: String? = null,
    ): String {
        val cards = File(root, "cards").apply { mkdirs() }
        val documentJson = documentId?.let { ""","document_id":"$it"""" }.orEmpty()
        val reanchor = reanchorState?.let { ""","reanchor":{"state":"$it"}""" }.orEmpty()
        val sourceLink = sourceLinkState?.let { ""","source_link":{"state":"$it"}""" }.orEmpty()
        val revisionJson = revision?.let { ""","revision":$it""" }.orEmpty()
        File(cards, "$cardId.json").writeText(
            """{"schema_version":3,"card_id":"$cardId","status":"$status"$revisionJson,"primary":{"pdf_stem":"$pdfStem"$documentJson,"pdf_sha256":"$pdfSha256","page":$page,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"$question"}$reanchor$sourceLink}""",
        )
        return cardId
    }

    private fun writeEvent(
        root: File,
        cardId: String,
        rating: ReviewRating,
        reviewedAt: Instant,
        schedulerVersion: Int? = null,
    ): String {
        val eventId = UUID.randomUUID().toString()
        val events = File(root, "events/test").apply { mkdirs() }
        val schedulerVersionJson = schedulerVersion?.let { ""","scheduler_version":$it""" }.orEmpty()
        File(events, "$eventId.json").writeText(
            """{"schema_version":3,"event_id":"$eventId","card_id":"$cardId","rating":"${rating.wireName}"$schedulerVersionJson,"reviewed_at":"$reviewedAt","origin_id":"test"}""",
        )
        return eventId
    }
}
