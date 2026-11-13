// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.links

import com.gitlab.mudlej.MjPdfReader.data.links.PdfLinkOverrides.ClickedLink
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class PdfLinkOverridesTest {
    private lateinit var directory: File
    private val previousFile = PdfLinkOverrides.file

    private val source = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val fast = "33333333-3333-4333-8333-333333333333"
    private val paper = "44444444-4444-4444-8444-444444444444"

    @Before fun setUp() {
        directory = Files.createTempDirectory("link-overrides").toFile()
        PdfLinkOverrides.file = { File(directory, "link-overrides.json") }
    }

    @After fun tearDown() {
        PdfLinkOverrides.file = previousFile
        directory.deleteRecursively()
    }

    private fun record(
        id: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        link: JsonObject,
        target: String = fast,
        revision: Int = 1,
        updatedAt: String = "2026-10-15T10:00:00.000Z",
        origin: String = "desktop",
        deleted: Boolean = false,
        sourceId: String = source,
    ): JsonObject = JsonObject().apply {
        addProperty("override_id", id)
        addProperty("source_document_id", sourceId)
        add("link", link)
        addProperty("target_document_id", target)
        addProperty("revision", revision)
        addProperty("updated_at", updatedAt)
        addProperty("origin_id", origin)
        addProperty("deleted", deleted)
    }

    private fun goto(page: Int, x: Double? = null, y: Double? = null, name: String? = null) = JsonObject().apply {
        addProperty("kind", "goto")
        name?.let { addProperty("dest_name", it) }
        addProperty("dest_page", page)
        x?.let { addProperty("dest_x", it) }
        y?.let { addProperty("dest_y", it) }
    }

    private fun uri(value: String) = JsonObject().apply {
        addProperty("kind", "uri")
        addProperty("uri", value)
    }

    private fun parsed(payload: JsonObject) = PdfLinkOverrides.parse(payload)!!

    @Test fun gotoMatchesSamePageWithinFourPoints() {
        val override = parsed(record(link = goto(41, 72.0, 640.5)))
        assertTrue(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 72.0, 640.5)))
        assertTrue(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 74.0, 644.0)))
        assertTrue(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, null, 637.0)))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 72.0, 645.0)))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 80.0, 640.5)))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.GoTo(40, 72.0, 640.5)))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 72.0, null)))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.Uri("https://example.org")))
    }

    @Test fun anOverrideWithoutNameOrYNeverMatchesTheWholePage() {
        val override = parsed(record(link = goto(41, 72.0)))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 72.0, 640.0)))
    }

    @Test fun aDesktopRecordWithANamedDestinationMatchesOnPageAndY() {
        // The phone knows no destination names; the desktop stores the name and the point.
        val override = parsed(record(link = goto(41, 72.0, 640.5, name = "cite.pertsch2025fast")))
        assertTrue(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 71.0, 642.0)))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 71.0, 600.0)))
        // When both sides know the name, the name decides.
        assertTrue(PdfLinkOverrides.matches(override, ClickedLink.GoTo(3, null, 10.0, "cite.pertsch2025fast")))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.GoTo(41, 72.0, 640.5, "cite.other")))
    }

    @Test fun uriLinksMatchTheExactString() {
        val override = parsed(record(link = uri("https://arxiv.org/abs/2501.09747")))
        assertTrue(PdfLinkOverrides.matches(override, ClickedLink.Uri("https://arxiv.org/abs/2501.09747")))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.Uri("https://arxiv.org/abs/2501.09747v2")))
        assertFalse(PdfLinkOverrides.matches(override, ClickedLink.GoTo(0, 0.0, 0.0)))
    }

    @Test fun theLatestUpdateWinsAndTombstonesAndOtherDocumentsAreIgnored() {
        val older = parsed(record(id = "aaaaaaaa-aaaa-4aaa-8aaa-000000000001", link = goto(41, y = 640.0), target = fast,
            revision = 5, updatedAt = "2026-10-15T10:00:00Z"))
        val newer = parsed(record(id = "aaaaaaaa-aaaa-4aaa-8aaa-000000000002", link = goto(41, y = 641.0), target = paper,
            updatedAt = "2026-10-15T11:00:00Z"))
        val removed = parsed(record(id = "aaaaaaaa-aaaa-4aaa-8aaa-000000000003", link = goto(41, y = 640.0), target = other,
            updatedAt = "2026-10-15T12:00:00Z", deleted = true))
        val elsewhere = parsed(record(id = "aaaaaaaa-aaaa-4aaa-8aaa-000000000004", link = goto(41, y = 640.0), target = other,
            updatedAt = "2026-10-15T13:00:00Z", sourceId = other))
        val all = listOf(older, newer, removed, elsewhere)
        assertEquals(newer, PdfLinkOverrides.bestMatch(all, source, ClickedLink.GoTo(41, null, 640.5)))
        assertNull(PdfLinkOverrides.bestMatch(listOf(removed), source, ClickedLink.GoTo(41, null, 640.5)))
    }

    @Test fun clickedLinksExcludeThoughtsOwnLinksAndPointlessJumps() {
        assertEquals(ClickedLink.GoTo(41, 72.0, 640.5), PdfLinkOverrides.clickedLink(null, 41, 72f, 640.5f))
        assertEquals(ClickedLink.GoTo(41, null, 640.5), PdfLinkOverrides.clickedLink("", 41, Float.NaN, 640.5f))
        assertNull(PdfLinkOverrides.clickedLink(null, 41, 72f, Float.NaN))
        assertNull(PdfLinkOverrides.clickedLink(null, null, 72f, 640f))
        assertEquals(ClickedLink.Uri("https://doi.org/10.1/x"), PdfLinkOverrides.clickedLink("https://doi.org/10.1/x", null, null, null))
        assertNull(PdfLinkOverrides.clickedLink("thoughts-pdf-link:a.pdf?page=0", null, null, null))
        assertNull(PdfLinkOverrides.clickedLink("thoughts-html:p/tex/support/html/x.html", null, null, null))
        assertNull(PdfLinkOverrides.clickedLink("thoughts://open/document?id=x", null, null, null))
        assertNull(PdfLinkOverrides.clickedLink("file:///sdcard/a.html", null, null, null))
    }

    @Test fun createdRecordsHaveTheSpecShape() {
        val now = Instant.parse("2026-10-15T09:30:00Z")
        val override = PdfLinkOverrides.create(source, "ab".repeat(32), ClickedLink.GoTo(41, 72.0, 640.5), fast, "phone", now)
        val payload = override.payload
        assertEquals(override.id, payload.get("override_id").asString)
        assertEquals(source, payload.get("source_document_id").asString)
        assertEquals("ab".repeat(32), payload.get("source_pdf_sha256").asString)
        assertEquals(fast, payload.get("target_document_id").asString)
        assertEquals(1, payload.get("revision").asInt)
        assertEquals("2026-10-15T09:30:00.000Z", payload.get("updated_at").asString)
        assertEquals("phone", payload.get("origin_id").asString)
        assertFalse(payload.get("deleted").asBoolean)
        val link = payload.getAsJsonObject("link")
        assertEquals("goto", link.get("kind").asString)
        assertEquals(41, link.get("dest_page").asInt)
        assertEquals(72.0, link.get("dest_x").asDouble, 0.0)
        assertEquals(640.5, link.get("dest_y").asDouble, 0.0)
        assertFalse(link.has("dest_name"))
        assertFalse(link.has("uri"))

        val uriOverride = PdfLinkOverrides.create(source, null, ClickedLink.Uri("https://arxiv.org/abs/1"), fast, "phone", now)
        assertEquals("uri", uriOverride.payload.getAsJsonObject("link").get("kind").asString)
        assertEquals("https://arxiv.org/abs/1", uriOverride.payload.getAsJsonObject("link").get("uri").asString)
        assertFalse(uriOverride.payload.has("source_pdf_sha256"))

        val upload = PdfLinkOverrides.pendingUploads().single { it.entityId == override.id }
        assertNull(upload.baseRevision)
        assertEquals(payload, upload.payload)
        assertEquals("pdf_link_override", PdfLinkOverrides.ENTITY_TYPE)
        assertEquals("link-overrides", PdfLinkOverrides.COLLECTION)
        assertEquals("pdf_link_overrides_v1", PdfLinkOverrides.CAPABILITY)
    }

    @Test fun storeRoundTripsAndEditsRaiseTheRevision() {
        val created = PdfLinkOverrides.create(source, null, ClickedLink.GoTo(41, 72.0, 640.5), fast, "phone",
            Instant.parse("2026-10-15T09:30:00Z"))
        val file = File(directory, "link-overrides.json")
        assertTrue(file.isFile)
        // A fresh read from disk (the cache keys on the file stamp).
        file.setLastModified(file.lastModified() + 2000)
        assertEquals(created, PdfLinkOverrides.find(created.id))
        assertEquals(created, PdfLinkOverrides.overrideFor(source, ClickedLink.GoTo(41, 70.0, 642.0)))

        // The clock went back: the edit is still later.
        val retargeted = PdfLinkOverrides.retarget(created.id, paper, "phone", Instant.parse("2026-10-15T09:00:00Z"))
        assertEquals(2L, retargeted.revision)
        assertEquals("2026-10-15T09:30:00.001Z", retargeted.updatedAt)
        assertEquals(paper, PdfLinkOverrides.overrideFor(source, ClickedLink.GoTo(41, 72.0, 640.5))?.targetDocumentId)
        assertEquals(1L, PdfLinkOverrides.pendingUploads().single().baseRevision)

        val removed = PdfLinkOverrides.remove(created.id, "phone", Instant.parse("2026-10-15T10:00:00Z"))!!
        assertTrue(removed.deleted)
        assertEquals(3L, removed.revision)
        assertNull(PdfLinkOverrides.overrideFor(source, ClickedLink.GoTo(41, 72.0, 640.5)))
        assertTrue(PdfLinkOverrides.pendingUploads().single().payload.get("deleted").asBoolean)
    }

    @Test fun unknownFieldsSurviveEdits() {
        val payload = record(link = goto(41, y = 640.0).apply { addProperty("future_link_field", 7) }).apply {
            addProperty("future_field", "kept")
        }
        PdfLinkOverrides.installServer(listOf(payload))
        val edited = PdfLinkOverrides.retarget(payload.get("override_id").asString, paper, "phone")
        assertEquals("kept", edited.payload.get("future_field").asString)
        assertEquals(7, edited.payload.getAsJsonObject("link").get("future_link_field").asInt)
        assertEquals(2L, edited.revision)
    }

    @Test fun serverRecordsInstallAndSettlePendingEdits() {
        val local = PdfLinkOverrides.create(source, null, ClickedLink.GoTo(41, 72.0, 640.5), fast, "phone",
            Instant.parse("2026-10-15T09:30:00Z"))
        val upload = PdfLinkOverrides.pendingUploads().single()
        // Sent and accepted, projection not pulled yet: still pending, still in effect.
        PdfLinkOverrides.installServer(emptyList(), accepted = setOf(upload.operationId))
        assertEquals(1, PdfLinkOverrides.pendingUploads().size)
        // The server holds the same record: settled.
        PdfLinkOverrides.installServer(listOf(local.payload))
        assertTrue(PdfLinkOverrides.pendingUploads().isEmpty())
        assertEquals(local, PdfLinkOverrides.find(local.id))

        // A later revision from another device replaces it.
        val desktop = record(id = local.id, link = goto(41, 72.0, 640.5, "cite.x"), target = paper, revision = 2,
            updatedAt = "2026-10-15T10:00:00Z")
        PdfLinkOverrides.installServer(listOf(desktop))
        assertEquals(paper, PdfLinkOverrides.find(local.id)?.targetDocumentId)

        // A pending local edit with a higher revision is kept against an older server record.
        val edit = PdfLinkOverrides.retarget(local.id, other, "phone", Instant.parse("2026-10-15T11:00:00Z"))
        PdfLinkOverrides.installServer(listOf(desktop))
        assertEquals(edit, PdfLinkOverrides.find(local.id))
        assertEquals(1, PdfLinkOverrides.pendingUploads().size)

        // A pending edit the server superseded (it holds a tombstone, so no projection): gone.
        PdfLinkOverrides.installServer(emptyList(), superseded = setOf(PdfLinkOverrides.pendingUploads().single().operationId))
        assertNull(PdfLinkOverrides.find(local.id))
    }

    @Test fun deletionsReachTheStoreBothWays() {
        val server = record(link = goto(41, y = 640.0))
        PdfLinkOverrides.installServer(listOf(server))
        val id = server.get("override_id").asString
        assertEquals(fast, PdfLinkOverrides.find(id)?.targetDocumentId)
        // Deleted elsewhere: the projection disappears.
        PdfLinkOverrides.installServer(emptyList())
        assertNull(PdfLinkOverrides.find(id))

        // Deleted here: the tombstone stays pending until the server accepts it.
        PdfLinkOverrides.installServer(listOf(server))
        PdfLinkOverrides.remove(id, "phone")
        val tombstone = PdfLinkOverrides.pendingUploads().single()
        assertTrue(tombstone.payload.get("deleted").asBoolean)
        assertEquals(1L, tombstone.baseRevision)
        PdfLinkOverrides.installServer(listOf(server))
        assertEquals(tombstone.operationId, PdfLinkOverrides.pendingUploads().single().operationId)
        PdfLinkOverrides.installServer(emptyList(), accepted = setOf(tombstone.operationId))
        assertNull(PdfLinkOverrides.find(id))
        assertTrue(PdfLinkOverrides.pendingUploads().isEmpty())
    }

    @Test fun uploadsHaveStableOperationIdsPerRevision() {
        val created = PdfLinkOverrides.create(source, null, ClickedLink.Uri("https://a.example"), fast, "phone")
        val first = PdfLinkOverrides.pendingUploads().single().operationId
        assertEquals(first, PdfLinkOverrides.pendingUploads().single().operationId)
        PdfLinkOverrides.retarget(created.id, paper, "phone")
        assertNotEquals(first, PdfLinkOverrides.pendingUploads().single().operationId)
    }

    @Test fun invalidRecordsAreRefused() {
        assertNull(PdfLinkOverrides.parse(record(id = "not-a-uuid", link = goto(1, y = 1.0))))
        assertNull(PdfLinkOverrides.parse(record(link = JsonObject().apply { addProperty("kind", "launch") })))
        assertNull(PdfLinkOverrides.parse(record(link = uri("x"), target = "")))
        // A tombstone needs no target.
        assertTrue(PdfLinkOverrides.parse(record(link = uri("x"), target = "", deleted = true))!!.deleted)
        // Garbage on disk reads as no overrides.
        File(directory, "link-overrides.json").writeText("{")
        assertTrue(PdfLinkOverrides.all().isEmpty())
        assertTrue(JsonParser.parseString(record(link = uri("x")).toString()).isJsonObject)
    }
}
