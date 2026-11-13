// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel
import com.gitlab.mudlej.MjPdfReader.ui.reader.documentReviewCards
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cards made inside HTML attachment pages: no PDF geometry, the terminal html_attachment link state, owned by the document. */
class HtmlAttachmentReviewCardTest {
    @get:Rule val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-10-27T12:00:00Z")
    private val documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
    private val pdf = "a".repeat(64)
    private val attachmentSha = "c".repeat(64)
    private val path = "docs-ideas/tex/support/html/explainer.html"

    private fun fields(hash: String = "#simulator"): JsonObject = HtmlAttachmentCards.fields(path, attachmentSha,
        HtmlReaderModel.Selection.parse(JsonParser.parseString("""{"text":"Simulator runs","text_quote":{"exact":"Simulator runs",
            "prefix":"The ","suffix":" offline."},"page_state":{"hash":"$hash"}}""").asJsonObject)!!)

    private fun store(name: String): Pair<ReviewStore, File> {
        val outgoing = temporary.newFolder("$name-out")
        return ReviewStore(null, temporary.newFolder(name), temporary.newFolder("$name-in"),
            temporary.newFolder("$name-events"), outgoing) to outgoing
    }

    @Test fun theRecordHasTheSpecPrimaryAndATerminalLinkState() {
        val (store, outgoing) = store("written")
        val card = store.createHtmlAttachmentCard("ideas", pdf, "What does the simulator do?", fields(), "Simulator runs",
            createdAt = now, documentId = documentId).getOrThrow()
        val record = JsonParser.parseString(outgoing.walkTopDown().single { it.name == "${card.id}.json" }.readText()).asJsonObject
        val primary = record.getAsJsonObject("primary")
        listOf("page", "rect", "regions", "page_visual_sha256", "source_anchor", "html_edition_sha256").forEach { key ->
            assertFalse("primary.$key must be absent", primary.has(key))
        }
        assertEquals("html_attachment", primary["created_in"].asString)
        assertEquals(documentId, primary["document_id"].asString)
        assertEquals("ideas", primary["pdf_stem"].asString)
        assertEquals(pdf, primary["pdf_sha256"].asString)
        assertEquals(path, primary.getAsJsonObject("html_attachment")["path"].asString)
        assertEquals(attachmentSha, primary.getAsJsonObject("html_attachment")["sha256"].asString)
        assertEquals("Simulator runs", primary.getAsJsonObject("text_quote")["exact"].asString)
        assertEquals("#simulator", primary.getAsJsonObject("page_state")["hash"].asString)
        assertEquals("Simulator runs", primary["selected_text"].asString)
        assertEquals("What does the simulator do?", primary["question"].asString)
        assertEquals("client-v1", record["source_authority"].asString)
        assertEquals("html_attachment", record.getAsJsonObject("source_link")["state"].asString)
        assertFalse(record.getAsJsonObject("source_link").has("reason"))
    }

    @Test fun theCardIsAnAttachmentCardWithoutPdfPlace() {
        val (store, _) = store("parsed")
        val card = store.createHtmlAttachmentCard("ideas", pdf, "What does the simulator do?", fields(), "Simulator runs",
            createdAt = now, documentId = documentId).getOrThrow()
        val reloaded = store.cards().single { it.id == card.id }
        assertTrue(reloaded.isHtmlAttachmentCard)
        assertFalse(reloaded.htmlPending)
        assertNull(reloaded.htmlSelection)
        assertFalse(reloaded.hasPdfPlacement)
        assertEquals(emptyList<ReviewAnswerRegion>(), reloaded.regions)
        assertEquals("html_attachment", reloaded.sourceLinkState)
        assertFalse(reloaded.needsAction())
        assertEquals("#simulator", reloaded.htmlAttachment!!.pageHash)
        assertEquals(path, reloaded.htmlAttachment!!.path)
    }

    @Test fun itIsDueAndFollowsItsDocumentAcrossPdfVersions() {
        val (store, _) = store("due")
        val card = store.createHtmlAttachmentCard("ideas", pdf, "What does the simulator do?", fields(), "Simulator runs",
            createdAt = now, documentId = documentId).getOrThrow()
        assertTrue(store.dueCards(now.plusSeconds(60)).any { it.id == card.id })
        // The owner PDF was rebuilt: the card does not depend on its geometry.
        val rebuilt = mapOf("ideas.pdf" to "b".repeat(64))
        assertTrue(store.dueCards(now.plusSeconds(60), canonicalPdfSha256ByFilename = rebuilt).any { it.id == card.id })
        val browser = store.browserCards(now.plusSeconds(60), rebuilt).single { it.card.id == card.id }
        assertNotEquals(ReviewBrowserState.STALE, browser.state)
        assertEquals(listOf(card.id), documentReviewCards(store.browserCards(now.plusSeconds(60)), "ideas.pdf", null, "b".repeat(64)).map { it.card.id })
        // A document no longer in the Library: not due.
        assertFalse(store.dueCards(now.plusSeconds(60), canonicalPdfSha256ByFilename = mapOf("other.pdf" to pdf)).any { it.id == card.id })
        assertEquals(ReviewBrowserState.STALE, store.browserCards(now.plusSeconds(60), mapOf("other.pdf" to pdf)).single().state)
    }

    @Test fun invalidAttachmentFieldsAreRefused() {
        val (store, _) = store("refused")
        val html = fields().apply { addProperty("created_in", "html") }
        assertTrue(store.createHtmlAttachmentCard("ideas", pdf, "Q?", html, "x", createdAt = now, documentId = documentId).isFailure)
        val noQuote = fields().apply { remove("text_quote") }
        assertTrue(store.createHtmlAttachmentCard("ideas", pdf, "Q?", noQuote, "x", createdAt = now, documentId = documentId).isFailure)
        assertTrue(store.createHtmlAttachmentCard("ideas", pdf, " ", fields(), "x", createdAt = now, documentId = documentId).isFailure)
        assertTrue(store.createHtmlAttachmentCard("ideas", "bad", "Q?", fields(), "x", createdAt = now, documentId = documentId).isFailure)
    }

    @Test fun anAttachmentPrimaryWithPdfGeometryIsRefused() {
        val (store, _) = store("strict")
        fun write(id: String, primary: JsonObject) {
            val record = JsonObject().apply {
                addProperty("schema_version", 3); addProperty("card_type", "visual_region"); addProperty("card_id", id)
                addProperty("status", "approved"); addProperty("revision", 1); addProperty("origin_id", "server")
                add("primary", primary)
                add("source_link", JsonParser.parseString("""{"state":"html_attachment"}"""))
            }
            File(temporary.root, "strict-in/cards/server/$id.json").apply { parentFile!!.mkdirs(); writeText(record.toString()) }
        }
        val base = fields().apply {
            addProperty("document_id", documentId); addProperty("pdf_stem", "ideas"); addProperty("pdf_sha256", pdf)
            addProperty("question", "Q?")
        }
        val good = "3d2e8e1f-4c5b-4e3f-9f77-8c3b6b3b2d33"
        val placed = "4e3f9f2a-5d6c-4f4a-8a88-9d4c7c4c3e44"
        val broken = "5f4a0a3b-6e7d-4a5b-9b99-ae5d8d5d4f55"
        write(good, base.deepCopy())
        write(placed, base.deepCopy().apply { addProperty("page", 1); add("rect", JsonParser.parseString("[0.1,0.1,0.5,0.5]")) })
        write(broken, base.deepCopy().apply { getAsJsonObject("html_attachment").addProperty("sha256", "nope") })
        assertTrue(store.card(good)!!.isHtmlAttachmentCard)
        assertNull(store.card(placed))
        assertNull(store.card(broken))
    }
}
