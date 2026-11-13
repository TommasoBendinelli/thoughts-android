// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.source.LocalReviewSources
import com.gitlab.mudlej.MjPdfReader.data.source.MappedSourceArea
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourceMarkup
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourcePlacer
import com.gitlab.mudlej.MjPdfReader.data.sync.REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED_MESSAGE
import com.gitlab.mudlej.MjPdfReader.data.sync.reviewCardCaptureMetadata
import com.gitlab.mudlej.MjPdfReader.ui.review.canReview
import com.gitlab.mudlej.MjPdfReader.ui.review.cardStatusLabel
import com.gitlab.mudlej.MjPdfReader.ui.review.linkingLabelRes
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cards on documents built from source are always linked: creation saves nothing when an area
 * cannot be linked, and Edit card / Place this card again moves only the card's own markers.
 */
class LinkedCardPlacementStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
    private val path = "docs-test/tex/source/paper.tex"
    private val source = listOf(
        "\\section{Results}",
        "",
        "The first answer paragraph is here.",
        "",
        "\\begin{table}[t]",
        "  \\input{generated/results}",
        "  \\caption{Accuracy for each method.}",
        "\\end{table}",
        "",
        "The second answer paragraph is there.",
        "",
        "A third paragraph with more words.",
        "",
    ).joinToString("\n")

    /** The phone's verified source, in memory: areas map to source lines by their page (page n → line [lines]). */
    private class FakeSources(var contents: String, val path: String) : ReviewSourcePlacer {
        lateinit var store: ReviewStore
        var lines: (LocalReviewSources.PlacementArea) -> MappedSourceArea = { error("unmapped") }
        val objects = mutableMapOf<String, String>()
        var placements = 0
        override val fallbackSource: String get() = path
        override fun placement(id: String, filename: String, hash: String, areas: List<LocalReviewSources.PlacementArea>, previous: JsonObject?): LocalReviewSources.Edit {
            placements++
            val pages = areas.map { area -> area.regions.minOf { it.page } }
            return try {
                ReviewSourceMarkup.placeAreas(path, contents, id, areas.mapIndexed { index, area ->
                    try { lines(area) } catch (error: IllegalStateException) {
                        throw ReviewAreaLinkingException(index, areas.size, 0, error.message.orEmpty())
                    }
                }).copy(base = contents)
            } catch (error: ReviewAreaLinkingException) { throw error.withPages(pages) }
        }
        override fun commit(edit: LocalReviewSources.Edit, record: JsonObject, destination: File, transactionId: String?) {
            check(edit.before == contents) { "The source changed before the review edit." }
            val id = transactionId ?: record.get("event_id")?.asString ?: record.get("card_id").asString
            val before = LocalReviewSources.digest(edit.before.toByteArray())
            val after = LocalReviewSources.digest(edit.after.toByteArray())
            objects[before] = edit.before; objects[after] = edit.after
            record.addProperty("source_authority", "client-v1")
            record.add("source_change", JsonObject().apply {
                addProperty("transaction_id", id); addProperty("base_sha256", before)
                addProperty("path", edit.path); addProperty("before_sha256", before); addProperty("after_sha256", after)
            })
            contents = edit.after
            store.saveSourceRecord(record, destination)
        }
        override fun hasRecoveryRecord(id: String): Boolean = false
    }

    private class Folders(val root: File, val incoming: File, val events: File, val outgoing: File) {
        fun outgoingPayloads(): List<JsonObject> = outgoing.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { JsonParser.parseString(it.readText()).asJsonObject }.toList()
    }

    private fun setup(name: String, contents: String = source): Triple<Folders, ReviewStore, FakeSources> {
        val f = Folders(temporary.newFolder("$name-root"), temporary.newFolder("$name-in"),
            temporary.newFolder("$name-events"), temporary.newFolder("$name-out"))
        val sources = FakeSources(contents, path)
        val store = ReviewStore(null, f.root, f.incoming, f.events, f.outgoing).useSourcePlacerForTesting(sources)
        sources.store = store
        return Triple(f, store, sources)
    }

    /** Page 1 → the first paragraph (line 3), page 2 → the second (line 10), page 3 → the table caption (a box), page 4 → the third paragraph. */
    private fun byPage(contents: () -> String): (LocalReviewSources.PlacementArea) -> MappedSourceArea = { area ->
        val needle = when (area.regions.minOf { it.page }) {
            1 -> "first answer"; 2 -> "second answer"; 3 -> "\\caption"; 4 -> "third paragraph"; else -> error("No editable text was found in the selection.")
        }
        val line = contents().split('\n').indexOfFirst { it.contains(needle) } + 1
        MappedSourceArea(listOf(line), area.text ?: when (needle) {
            "first answer" -> "The first answer paragraph is here."
            "second answer" -> "The second answer paragraph is there."
            "third paragraph" -> "A third paragraph with more words."
            else -> "Table 1: Accuracy for each method."
        }, rectangle = area.rectangle, partial = needle == "\\caption")
    }

    private fun rect(top: Float) = RectF().apply { left = .1f; this.top = top; right = .9f; bottom = top + .1f }
    private fun region(page: Int, top: Float = .2f) = ReviewAnswerRegion(page, rect(top))
    private fun box(page: Int, top: Float = .2f) = ReviewAreaBox(page, .1f, top, .9f, top + .1f)

    private fun create(store: ReviewStore, areas: List<Pair<List<ReviewAnswerRegion>, String?>>, byHand: Boolean = false) =
        store.createManualCard("paper", "a".repeat(64), areas.first().first.first().page, areas.first().first.first().rect,
            "What is shown?", createdAt = now, answerRegions = areas.flatMap { it.first },
            answerAreas = if (areas.size > 1) areas else emptyList(), selectedText = areas.singleOrNull()?.second,
            documentId = documentId, placeMarkersByHand = byHand)

    @Test
    fun `several areas of a new card become one linked segment each`() {
        val (f, store, sources) = setup("create")
        sources.lines = byPage { sources.contents }
        val card = create(store, listOf(listOf(region(2)) to null, listOf(region(3)) to null, listOf(region(1)) to "first answer paragraph")).getOrThrow()
        assertEquals("linked", card.sourceLinkState)
        val payload = f.outgoingPayloads().single()
        assertEquals(card.id, payload.getAsJsonObject("source_change").get("transaction_id").asString)
        val segments = payload.getAsJsonObject("primary").getAsJsonObject("source_anchor").getAsJsonArray("segments")
        assertEquals(listOf("0", "1", "2"), segments.map { it.asJsonObject.get("segment_id").asString })
        // The box over the table marks the whole table.
        assertTrue(sources.contents.contains("\\ReviewCardMaskStart{${card.id}}{1}%\n\\begin{table}[t]"))
        assertTrue(sources.contents.contains("\\end{table}\n\\ReviewCardMaskEnd{${card.id}}{1}%"))
        assertEquals("linked", payload.getAsJsonObject("source_link").get("state").asString)
    }

    @Test
    fun `a new card with an area that cannot be linked is not saved, not even PDF-only`() {
        val (f, store, sources) = setup("refuse")
        sources.lines = byPage { sources.contents }
        val result = create(store, listOf(listOf(region(1)) to null, listOf(region(5)) to null, listOf(region(2)) to null))
        val error = result.exceptionOrNull() as ReviewAreaLinkingException
        assertEquals(1, error.areaIndex)
        assertEquals(3, error.areaCount)
        assertEquals(5, error.page)
        assertEquals("Area 2 of 3 (page 5) can't be linked to source text: No editable text was found in the selection.", error.message)
        assertTrue(f.outgoingPayloads().isEmpty())
        assertTrue(store.cards().isEmpty())
        assertEquals(source, sources.contents)
        // Marking by hand is a single-area choice; several areas are still refused.
        assertTrue(create(store, listOf(listOf(region(1)) to null, listOf(region(5)) to null), byHand = true).isFailure)
        assertTrue(f.outgoingPayloads().isEmpty())
    }

    @Test
    fun `a single area that cannot be linked is saved for markers by hand only when chosen`() {
        val (f, store, sources) = setup("single")
        sources.lines = byPage { sources.contents }
        val refused = create(store, listOf(listOf(region(6)) to null)).exceptionOrNull() as ReviewAreaLinkingException
        assertEquals("The area (page 6) can't be linked to source text: No editable text was found in the selection.", refused.message)
        assertTrue(f.outgoingPayloads().isEmpty())
        val card = create(store, listOf(listOf(region(6)) to null), byHand = true).getOrThrow()
        assertEquals("linking_failed", card.sourceLinkState)
        assertTrue(card.sourceLinkReason.startsWith("No editable text was found in the selection."))
        assertEquals(path, f.outgoingPayloads().single().getAsJsonObject("source_link").get("source_file").asString)
    }

    @Test
    fun `a document without source keeps PDF-only cards`() {
        val (f, store, sources) = setup("pdf-only")
        val card = store.createManualCard("paper", "a".repeat(64), 1, rect(.2f), "What is shown?", createdAt = now,
            answerRegions = listOf(region(1), region(2)), answerAreas = listOf(listOf(region(1)) to null, listOf(region(2)) to null),
            sourceAvailable = false, documentId = documentId).getOrThrow()
        assertEquals("pdf_without_source", card.sourceLinkState)
        assertEquals(0, sources.placements)
        assertFalse(f.outgoingPayloads().single().has("source_change"))
    }

    @Test
    fun `editing a linked card's areas moves only its markers, with a fresh transaction`() {
        val (f, store, sources) = setup("replace")
        sources.lines = byPage { sources.contents }
        val card = create(store, listOf(listOf(region(1)) to null, listOf(region(2)) to null)).getOrThrow()
        val created = f.outgoingPayloads().single()
        val before = sources.contents
        // Another card's markers stay where they are.
        val other = UUID.randomUUID().toString()
        sources.contents = ReviewSourceMarkup.placeAreas(path, before, other, listOf(
            MappedSourceArea(listOf(sources.contents.split('\n').indexOfFirst { it.contains("third paragraph") } + 1), "A third paragraph with more words."),
        )).after
        val withOther = sources.contents

        val edited = store.replaceCardAreas(card.id, card.pdfSha256, card.page, "a".repeat(64),
            listOf(ReviewEditedArea(listOf(box(3))), ReviewEditedArea(listOf(box(2)))), "Which table?", now).getOrThrow()
        assertEquals("Which table?", edited.question)
        assertEquals("linked", edited.sourceLinkState)
        assertEquals(listOf(3, 2), edited.regions.map { it.page })
        val payload = f.outgoingPayloads().single()
        assertEquals(created.get("revision").asInt + 1, payload.get("revision").asInt)
        assertEquals(4, payload.get("schema_version").asInt)
        val change = payload.getAsJsonObject("source_change")
        val transaction = change.get("transaction_id").asString
        assertNotEquals(card.id, transaction)
        assertEquals(transaction, UUID.fromString(transaction).toString())
        // Fedora's rule: "before" holds the card's old markers; stripping the card's markers from
        // before and after gives the same text.
        val sentBefore = sources.objects.getValue(change.get("before_sha256").asString)
        val sentAfter = sources.objects.getValue(change.get("after_sha256").asString)
        assertEquals(withOther, sentBefore)
        assertTrue(sentBefore.contains(card.id))
        assertEquals(ReviewSourceMarkup.withoutCardMarkers(path, sentBefore, card.id).trimEnd('\n'),
            ReviewSourceMarkup.withoutCardMarkers(path, sentAfter, card.id).trimEnd('\n'))
        assertTrue(sentAfter.contains("\\ReviewCardMaskStart{${card.id}}{0}%\n\\begin{table}"))
        assertTrue(sentAfter.contains("{$other}{0}A third paragraph"))
        assertFalse(sentAfter.contains("{${card.id}}{0}The first answer"))
        val anchor = payload.getAsJsonObject("primary").getAsJsonObject("source_anchor").getAsJsonArray("segments")
        assertEquals(listOf("0", "1"), anchor.map { it.asJsonObject.get("segment_id").asString })
        assertFalse(payload.has("reanchor"))
        // The outbox sends it as the next revision of the existing card.
        assertEquals(created.get("revision").asLong, reviewCardCaptureMetadata(payload)!!.baseRevision)
    }

    @Test
    fun `an edit that cannot link an area saves nothing`() {
        val (f, store, sources) = setup("replace-refused")
        sources.lines = byPage { sources.contents }
        val card = create(store, listOf(listOf(region(1)) to null)).getOrThrow()
        val saved = f.outgoingPayloads().single()
        val linkedSource = sources.contents
        val error = store.replaceCardAreas(card.id, card.pdfSha256, card.page, "a".repeat(64),
            listOf(ReviewEditedArea(listOf(box(1))), ReviewEditedArea(listOf(box(9)))), null, now).exceptionOrNull()
        assertTrue(error is ReviewAreaLinkingException)
        assertEquals("Area 2 of 2 (page 9) can't be linked to source text: No editable text was found in the selection.", error!!.message)
        assertEquals(saved, f.outgoingPayloads().single())
        assertEquals(linkedSource, sources.contents)
    }

    @Test
    fun `moving markers waits for a server that accepts it`() {
        val (f, store, sources) = setup("replace-unsupported")
        sources.lines = byPage { sources.contents }
        val card = create(store, listOf(listOf(region(1)) to null)).getOrThrow()
        val saved = f.outgoingPayloads().single()
        val result = store.replaceCardAreas(card.id, card.pdfSha256, card.page, "a".repeat(64),
            listOf(ReviewEditedArea(listOf(box(2)))), null, now, replacementSupported = false)
        assertEquals(REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED_MESSAGE, result.exceptionOrNull()?.message)
        assertEquals(saved, f.outgoingPayloads().single())
        // The question alone still changes without touching the source.
        val renamed = store.replaceCardAreas(card.id, card.pdfSha256, card.page, "a".repeat(64),
            card.regions.map { listOf(ReviewAreaBox.of(it)) }.map { ReviewEditedArea(it) }, "Renamed?", now, replacementSupported = false).getOrThrow()
        assertEquals("Renamed?", renamed.question)
        assertEquals(saved.getAsJsonObject("source_change"), f.outgoingPayloads().single().getAsJsonObject("source_change"))
    }

    @Test
    fun `a card Fedora asks to place again is linked from its areas on the current PDF`() {
        val (f, store, sources) = setup("place-again")
        sources.lines = byPage { sources.contents }
        val cardId = UUID.randomUUID().toString()
        File(f.incoming, "cards").mkdirs()
        File(f.incoming, "cards/$cardId.json").writeText(JsonObject().apply {
            addProperty("schema_version", 4); addProperty("card_id", cardId); addProperty("status", "approved")
            addProperty("revision", 7); addProperty("source_authority", "client-v1")
            add("primary", JsonParser.parseString("""{"pdf_stem":"paper","pdf_sha256":"${"c".repeat(64)}","page":2,
                "rect":[0.1,0.2,0.9,0.3],"regions":[{"page":2,"rect":[0.1,0.2,0.9,0.3]}],"source_kind":"pdf",
                "page_visual_sha256":"${"b".repeat(64)}","question":"Old PDF-only card?"}"""))
            add("source_link", JsonParser.parseString("""{"state":"linking_failed","reason":"No passage matched"}"""))
            add("reanchor", JsonParser.parseString("""{"state":"needs_attention","strategy":"manual_source_link_v1",
                "error_code":"review_card_place_again","message":"The answer text was not found in the source."}"""))
        }.toString())
        val card = store.card(cardId)!!
        assertTrue(card.needsPlacingAgain(sourceAvailable = true))
        assertFalse(card.needsPlacingAgain(sourceAvailable = false))
        assertEquals("The answer text was not found in the source.", card.placeAgainReason())
        // The areas are drawn on the PDF shown now, newer than the card's.
        val placed = store.replaceCardAreas(cardId, "c".repeat(64), 2, "a".repeat(64),
            listOf(ReviewEditedArea(listOf(box(2)))), null, now).getOrThrow()
        assertEquals("linked", placed.sourceLinkState)
        assertEquals("a".repeat(64), placed.pdfSha256)
        assertEquals("", placed.sourceKind)
        assertFalse(placed.needsPlacingAgain(true))
        val payload = f.outgoingPayloads().single()
        assertEquals(8, payload.get("revision").asInt)
        assertFalse(payload.has("reanchor"))
        assertNotEquals("b".repeat(64), payload.getAsJsonObject("primary").get("page_visual_sha256").asString)
        assertNotEquals(cardId, payload.getAsJsonObject("source_change").get("transaction_id").asString)
        assertNull(payload.getAsJsonObject("source_link").get("reason"))
    }

    @Test
    fun `a card on a document whose source is unknown can be saved for the server to link at the next Sync`() {
        val (f, store, sources) = setup("next-sync")
        sources.lines = byPage { sources.contents }
        val areas = listOf(listOf(region(1)) to null, listOf(region(7)) to null)
        fun save(sourceAvailable: Boolean?, linkAtNextSync: Boolean) = store.createManualCard("paper", "a".repeat(64), 1, rect(.2f),
            "What is shown?", createdAt = now, answerRegions = areas.flatMap { it.first }, answerAreas = areas,
            sourceAvailable = sourceAvailable, documentId = documentId, linkAtNextSync = linkAtNextSync)
        // Not chosen: nothing is saved. Only an unknown source allows it.
        assertTrue(save(null, false).exceptionOrNull() is ReviewAreaLinkingException)
        assertTrue(save(true, true).isFailure)
        assertTrue(f.outgoingPayloads().isEmpty())

        val card = save(null, true).getOrThrow()
        assertEquals(REVIEW_PENDING_SERVER_LINK, card.sourceLinkState)
        assertEquals("No editable text was found in the selection.", card.sourceLinkReason)
        val payload = f.outgoingPayloads().single()
        assertEquals(REVIEW_PENDING_SERVER_LINK, payload.getAsJsonObject("source_link").get("state").asString)
        assertFalse(payload.has("source_change"))
        assertFalse(payload.has("reanchor"))
        assertFalse(payload.getAsJsonObject("primary").has("source_kind"))
        assertFalse(payload.getAsJsonObject("primary").has("source_anchor"))
        assertEquals(2, payload.getAsJsonObject("primary").getAsJsonArray("regions").size())
        assertEquals(source, sources.contents)
        // "Link pending", never offered for review, and not a card the person must place again.
        assertTrue(store.dueCards(now).isEmpty())
        val browser = store.browserCards(now).single()
        assertTrue(browser.hasIssues)
        assertFalse(browser.canReview())
        assertEquals("Link pending", browser.cardStatusLabel())
        assertEquals(com.gitlab.mudlej.MjPdfReader.R.string.review_link_pending_server,
            browser.linkingLabelRes(null))
        assertFalse(card.needsPlacingAgain(null))
        assertEquals(0, store.summary(now).counts.total)

        // Fedora found no editable source: its PDF-only revision makes the card reviewable.
        val server = payload.deepCopy().apply {
            addProperty("revision", 2)
            add("source_link", JsonParser.parseString("""{"state":"pdf_without_source"}"""))
            getAsJsonObject("primary").addProperty("source_kind", "pdf")
        }
        File(f.incoming, "cards").mkdirs()
        File(f.incoming, "cards/${card.id}.json").writeText(server.toString())
        f.outgoing.deleteRecursively()
        val reviewable = ReviewStore(null, f.root, f.incoming, f.events, f.outgoing)
        assertEquals(listOf(card.id), reviewable.dueCards(now).map { it.id })
        assertEquals("PDF only", reviewable.browserCards(now).single().cardStatusLabel())
    }
}
