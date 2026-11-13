// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.sync.reviewCardCaptureMetadata
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Edit card with several areas: one revision carrying every region, durable and ready to sync. */
class ReviewCardAreasStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-10-13T12:00:00Z")

    private class Folders(val root: File, val incoming: File, val events: File, val outgoing: File) {
        fun store() = ReviewStore(null, root, incoming, events, outgoing)
        fun outgoingPayloads(): List<JsonObject> = outgoing.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .map { JsonParser.parseString(it.readText()).asJsonObject }.toList()
    }

    private fun folders(name: String) = Folders(temporary.newFolder("$name-root"), temporary.newFolder("$name-in"),
        temporary.newFolder("$name-events"), temporary.newFolder("$name-out"))

    private fun box(page: Int, left: Float, top: Float, right: Float, bottom: Float) =
        ReviewAreaBox(page, left, top, right, bottom)

    @Test
    fun `adding an area on another page saves one schema-4 revision that survives a restart`() {
        val f = folders("add")
        val id = writeCard(f.root, page = 3, revision = 5, sourceKind = "pdf")
        writeEvent(f.root, id, now.minus(1, ChronoUnit.DAYS))
        val store = f.store()
        val original = store.card(id)!!
        assertTrue(original.canEditAnswerBox)
        val session = ReviewAreaEditSession(reviewEditableAreas(original.regions.map(ReviewAreaBox::of)))
        session.added()
        val added = box(5, .3f, .4f, .6f, .5f)
        val regions = session.regions(session.initialBoxes + added)

        val edited = store.updateCardAreas(id, original.pdfSha256, 3, regions, "Two parts?", now).getOrThrow()
        assertEquals(6, edited.revision)
        assertEquals("Two parts?", edited.question)
        assertEquals(listOf(3, 5), edited.regions.map { it.page })
        assertEquals(setOf(2, 4), edited.masksByPage.keys)

        // One outgoing record: the whole card, regions included, as the next revision.
        val payload = f.outgoingPayloads().single()
        assertEquals(4, payload.get("schema_version").asInt)
        assertEquals(6, payload.get("revision").asInt)
        val primary = payload.getAsJsonObject("primary")
        assertEquals(3, primary.get("page").asInt)
        assertEquals(2, primary.getAsJsonArray("regions").size())
        assertEquals(5, primary.getAsJsonArray("regions")[1].asJsonObject.get("page").asInt)
        val metadata = reviewCardCaptureMetadata(payload)!!
        assertEquals(6, metadata.revision)
        assertEquals(5L, metadata.baseRevision)
        assertEquals(listOf("review_card" to id), store.pendingChanges().map { it.entityType to it.entityId })

        // After an app restart the review masks come from the saved regions.
        val reopened = f.store().browserCards(now).single()
        assertTrue(reopened.pendingSync)
        assertEquals(1, reopened.reviewCount)
        assertEquals(listOf(3, 5), reopened.card.regions.map { it.page })
        assertEquals(.3f, reopened.card.masksByPage.getValue(4).single().left, 1e-6f)
        assertEquals(.8f, reopened.card.masksByPage.getValue(2).single().right, 1e-6f)
        assertTrue(reopened.card.canEditAnswerBox)
    }

    @Test
    fun `an older PDF card without a kind becomes an explicit PDF card and stays editable`() {
        val f = folders("legacy")
        val id = writeCard(f.root, page = 2)
        val store = f.store()
        val original = store.card(id)!!
        assertTrue(original.canEditAnswerBox)
        val edited = store.updateCardAreas(id, original.pdfSha256, 2,
            listOf(box(2, .1f, .2f, .8f, .4f), box(2, .1f, .6f, .5f, .7f)), null, now).getOrThrow()
        assertEquals("pdf", edited.sourceKind)
        assertTrue(edited.canEditAnswerBox)
        assertEquals(2, edited.answerRegions.size)
        assertEquals(.7f, edited.answerBox.bottom, 1e-6f)
    }

    @Test
    fun `a single area keeps a card without regions in its old form`() {
        val f = folders("single")
        val id = writeCard(f.root, page = 2, revision = 1)
        val store = f.store()
        val edited = store.updateCardAreas(id, "a".repeat(64), 2, listOf(box(2, .2f, .3f, .6f, .5f)), null, now)
            .getOrThrow()
        assertTrue(edited.answerRegions.isEmpty())
        assertEquals(.6f, edited.rect.right, 1e-6f)
        val payload = f.outgoingPayloads().single()
        assertEquals(3, payload.get("schema_version").asInt)
        assertFalse(payload.getAsJsonObject("primary").has("regions"))
        assertFalse(payload.getAsJsonObject("primary").has("source_kind"))
    }

    @Test
    fun `removing every area on the card's page moves the card to the first remaining page`() {
        val f = folders("move")
        val id = writeCard(f.root, page = 3, revision = 2, sourceKind = "pdf",
            regions = """[{"page":3,"rect":[0.1,0.2,0.8,0.4]},{"page":6,"rect":[0.2,0.1,0.7,0.2]}]""",
            componentOrigin = """{"source_id":"x","component_pdf_sha256":"${"c".repeat(64)}","local_page":2}""")
        val store = f.store()
        val edited = store.updateCardAreas(id, "a".repeat(64), 3, listOf(box(6, .2f, .1f, .7f, .2f)), null, now)
            .getOrThrow()
        assertEquals(6, edited.page)
        assertEquals(.7f, edited.rect.right, 1e-6f)
        assertEquals(listOf(6), edited.regions.map { it.page })
        assertTrue(edited.rects.isNotEmpty())
        val primary = f.outgoingPayloads().single().getAsJsonObject("primary")
        assertEquals(5, primary.getAsJsonObject("component_origin").get("local_page").asInt)
        val visual = primary.get("page_visual_sha256").asString
        assertTrue(visual.matches(Regex("[0-9a-f]{64}")))
        assertFalse(visual == "b".repeat(64))
    }

    @Test
    fun `unchanged areas and question write nothing, stale or invalid edits are refused`() {
        val f = folders("noop")
        val id = writeCard(f.root, page = 3, revision = 5, sourceKind = "pdf",
            regions = """[{"page":3,"rect":[0.1,0.2,0.8,0.3]},{"page":3,"rect":[0.1,0.3,0.8,0.4]}]""")
        val store = f.store()
        val card = store.card(id)!!
        val same = card.regions.map(ReviewAreaBox::of)
        assertEquals(5, store.updateCardAreas(id, card.pdfSha256, 3, same, card.question, now).getOrThrow().revision)
        assertTrue(f.outgoingPayloads().isEmpty())
        val other = listOf(box(3, .2f, .2f, .4f, .4f))
        assertTrue(store.updateCardAreas(id, "c".repeat(64), 3, other, null, now).isFailure)
        assertTrue(store.updateCardAreas(id, card.pdfSha256, 4, other, null, now).isFailure)
        assertTrue(store.updateCardAreas(id, card.pdfSha256, 3, emptyList(), null, now).isFailure)
        assertTrue(store.updateCardAreas(id, card.pdfSha256, 3, listOf(box(3, .5f, .2f, .4f, .4f)), null, now).isFailure)
        assertTrue(store.updateCardAreas(id, card.pdfSha256, 3, other, " ", now).isFailure)
        assertTrue(f.outgoingPayloads().isEmpty())
    }

    @Test
    fun `source-linked and whole-document cards keep their areas, but their question can change`() {
        val f = folders("linked")
        val linked = writeCard(f.root, page = 3, revision = 5,
            anchor = """{"version":2,"segments":[{"source_file":"paper.tex","segment_id":"s1","content_sha256":"${"d".repeat(64)}"}]}""")
        val whole = writeCard(f.root, page = 1, revision = 1, scope = "document",
            regions = """[{"page":1,"rect":[0,0,1,1]},{"page":2,"rect":[0,0,1,1]}]""")
        val store = f.store()
        val linkedCard = store.card(linked)!!
        assertFalse(linkedCard.canEditAnswerBox)
        val second = linkedCard.regions.map(ReviewAreaBox::of) + box(4, .1f, .1f, .3f, .2f)
        assertTrue(store.updateCardAreas(linked, linkedCard.pdfSha256, 3, second, null, now).isFailure)
        val wholeCard = store.card(whole)!!
        assertTrue(store.updateCardAreas(whole, wholeCard.pdfSha256, 1,
            listOf(box(1, .1f, .1f, .5f, .5f)), null, now).isFailure)
        assertTrue(f.outgoingPayloads().isEmpty())

        val renamed = store.updateCardAreas(linked, linkedCard.pdfSha256, 3,
            linkedCard.regions.map(ReviewAreaBox::of), "Renamed?", now).getOrThrow()
        assertEquals("Renamed?", renamed.question)
        assertEquals(linkedCard.sourceAnchor, renamed.sourceAnchor)
    }

    /** A desktop edit of the areas, as the desktop stores it (primary.answer_region_override). */
    private fun desktopEdit(pdfSha256: String = "a".repeat(64), regions: String = """[{"page":2,"rect":[0.1,0.2,0.8,0.25]}]""") =
        """{"pdf_sha256":"$pdfSha256","regions":$regions}"""

    @Test
    fun `a desktop edit of the areas on the card's PDF replaces its rectangle`() {
        val f = folders("desktop-edit")
        val id = writeCard(f.root, page = 2, sourceKind = "pdf", regionOverride = desktopEdit())
        val card = f.store().card(id)!!
        assertTrue(card.answerRegionOverride)
        assertEquals(2, card.page)
        assertEquals(.25f, card.rect.bottom, 1e-6f)
        assertEquals(.25f, card.answerBox.bottom, 1e-6f)
        assertEquals(.25f, card.masksByPage.getValue(1).single().bottom, 1e-6f)
        assertTrue(card.canEditAnswerBox)
    }

    @Test
    fun `a desktop edit made on another PDF or with invalid areas is ignored`() {
        val f = folders("desktop-edit-stale")
        val stale = writeCard(f.root, page = 2, sourceKind = "pdf", regionOverride = desktopEdit(pdfSha256 = "c".repeat(64)))
        val invalid = writeCard(f.root, page = 2, sourceKind = "pdf",
            regionOverride = desktopEdit(regions = """[{"page":2,"rect":[0.8,0.2,0.1,0.25]}]"""))
        val store = f.store()
        for (id in listOf(stale, invalid)) {
            val card = store.card(id)!!
            assertFalse(card.answerRegionOverride)
            assertEquals(.4f, card.answerBox.bottom, 1e-6f)
        }
    }

    @Test
    fun `editing the areas on the phone replaces the desktop edit, so the desktop shows them too`() {
        val f = folders("desktop-edit-areas")
        val id = writeCard(f.root, page = 2, revision = 4, sourceKind = "pdf", regionOverride = desktopEdit())
        val store = f.store()
        val areas = listOf(box(2, .1f, .2f, .8f, .3f), box(3, .2f, .1f, .7f, .2f))
        val edited = store.updateCardAreas(id, "a".repeat(64), 2, areas, null, now).getOrThrow()
        assertEquals(listOf(.3f, .2f), edited.regions.map { it.rect.bottom })

        val primary = f.outgoingPayloads().single().getAsJsonObject("primary")
        val override = primary.getAsJsonObject("answer_region_override")
        assertEquals("a".repeat(64), override.get("pdf_sha256").asString)
        assertEquals(listOf(2, 3), override.getAsJsonArray("regions").map { it.asJsonObject.get("page").asInt })
        assertEquals(.3f, override.getAsJsonArray("regions")[0].asJsonObject.getAsJsonArray("rect")[3].asFloat, 1e-6f)
        assertEquals(listOf(.3f, .2f), f.store().card(id)!!.regions.map { it.rect.bottom })
    }

    @Test
    fun `resizing the box of a desktop-edited card replaces the desktop edit`() {
        val f = folders("desktop-edit-box")
        val id = writeCard(f.root, page = 2, revision = 4, sourceKind = "pdf", regionOverride = desktopEdit())
        val store = f.store()
        val rect = android.graphics.RectF().apply { left = .1f; top = .2f; right = .8f; bottom = .35f }
        val edited = store.updateCardContent(id, "a".repeat(64), 2, rect, null, now).getOrThrow()
        assertEquals(.35f, edited.answerBox.bottom, 1e-6f)
        val primary = f.outgoingPayloads().single().getAsJsonObject("primary")
        assertFalse(primary.has("regions"))
        val regions = primary.getAsJsonObject("answer_region_override").getAsJsonArray("regions")
        assertEquals(.35f, regions.single().asJsonObject.getAsJsonArray("rect")[3].asFloat, 1e-6f)
    }

    private fun writeCard(
        root: File,
        page: Int,
        revision: Int? = null,
        sourceKind: String? = null,
        regions: String? = null,
        componentOrigin: String? = null,
        anchor: String? = null,
        scope: String? = null,
        regionOverride: String? = null,
    ): String {
        val cardId = UUID.randomUUID().toString()
        val cards = File(root, "cards").apply { mkdirs() }
        val extra = listOfNotNull(
            sourceKind?.let { """"source_kind":"$it"""" },
            regions?.let { """"regions":$it""" },
            componentOrigin?.let { """"component_origin":$it""" },
            anchor?.let { """"source_anchor":$it""" },
            scope?.let { """"scope":"$it"""" },
            regionOverride?.let { """"answer_region_override":$it""" },
        ).joinToString("") { ",$it" }
        val schema = if (regions != null) 4 else 3
        val revisionJson = revision?.let { ""","revision":$it""" }.orEmpty()
        File(cards, "$cardId.json").writeText(
            """{"schema_version":$schema,"card_id":"$cardId","status":"approved"$revisionJson,"primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":$page,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"What is hidden?"$extra}}""",
        )
        return cardId
    }

    private fun writeEvent(root: File, cardId: String, reviewedAt: Instant) {
        val eventId = UUID.randomUUID().toString()
        val events = File(root, "events/test").apply { mkdirs() }
        File(events, "$eventId.json").writeText(
            """{"schema_version":3,"event_id":"$eventId","card_id":"$cardId","rating":"${ReviewRating.GOOD.wireName}","reviewed_at":"$reviewedAt","origin_id":"test"}""",
        )
    }
}
