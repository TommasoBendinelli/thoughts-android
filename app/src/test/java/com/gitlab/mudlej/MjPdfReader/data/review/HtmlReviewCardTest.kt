// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cards made in the HTML view have no PDF geometry until Fedora links them (always-linked cards). */
class HtmlReviewCardTest {
    @get:Rule val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val documentId = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
    private val pdf = "a".repeat(64)
    private val edition = "e".repeat(64)

    private fun html() = JsonParser.parseString("""{"created_in":"html","html_edition_sha256":"$edition",
        "text_quote":{"exact":"four main tabs","prefix":"It comprises of ","suffix":": Comments"}}""").asJsonObject

    private fun store(name: String): Pair<ReviewStore, File> {
        val outgoing = temporary.newFolder("$name-out")
        return ReviewStore(null, temporary.newFolder(name), temporary.newFolder("$name-in"),
            temporary.newFolder("$name-events"), outgoing) to outgoing
    }

    @Test fun noPdfGeometryIsWritten() {
        val (store, outgoing) = store("written")
        val card = store.createHtmlCard("manual", pdf, "How many tabs?", html(), "four main tabs",
            createdAt = now, documentId = documentId).getOrThrow()
        val record = JsonParser.parseString(outgoing.walkTopDown().single { it.name == "${card.id}.json" }.readText()).asJsonObject
        val primary = record.getAsJsonObject("primary")
        listOf("page", "rect", "regions", "page_visual_sha256", "source_anchor").forEach { key ->
            assertFalse("primary.$key must be absent", primary.has(key))
        }
        assertEquals("html", primary["created_in"].asString)
        assertEquals(edition, primary["html_edition_sha256"].asString)
        assertEquals("manual", primary["pdf_stem"].asString)
        assertEquals(pdf, primary["pdf_sha256"].asString)
        assertEquals("four main tabs", primary.getAsJsonObject("text_quote")["exact"].asString)
        assertEquals("pending_server_link", record.getAsJsonObject("source_link")["state"].asString)
    }

    @Test fun anUnplacedCardIsNullSafeAndWaitsForLinkingOutsideTheDueQueue() {
        val (store, _) = store("queue")
        val card = store.createHtmlCard("manual", pdf, "How many tabs?", html(), "four main tabs",
            createdAt = now, documentId = documentId).getOrThrow()
        assertTrue(card.htmlPending)
        assertFalse(card.hasPdfPlacement)
        assertNull(card.pdfPage)
        assertEquals(emptyList<ReviewAnswerRegion>(), card.regions)
        assertEquals(emptyList<Any>(), card.rects)
        assertEquals(emptyMap<Int, Any>(), card.masksByPage)
        assertEquals("", card.pagesLabel)
        // Survives a reload and shows in the card browser; like every pending_server_link card it
        // joins the due queue once Fedora has linked it (it can still be opened by hand).
        val reloaded = store.cards().single { it.id == card.id }
        assertFalse(reloaded.hasPdfPlacement)
        assertNotNull(reloaded.htmlSelection)
        assertTrue(store.browserCards().any { it.card.id == card.id })
        assertFalse(store.dueCards(now.plusSeconds(60)).any { it.id == card.id })
    }

    @Test fun onceLinkedTheServersAreasArrive() {
        val (store, _) = store("linked")
        val record = JsonObject().apply {
            addProperty("schema_version", 3); addProperty("card_type", "visual_region")
            addProperty("card_id", "1b0c6c9e-2a39-4c1f-9d55-6a1f4f1f0b11"); addProperty("status", "approved")
            addProperty("revision", 2); addProperty("origin_id", "server")
            add("primary", html().apply {
                addProperty("document_id", documentId); addProperty("pdf_stem", "manual"); addProperty("pdf_sha256", pdf)
                addProperty("question", "How many tabs?"); addProperty("page", 2); addProperty("page_visual_sha256", "b".repeat(64))
                add("rect", JsonParser.parseString("[0.1,0.2,0.9,0.3]"))
                add("source_anchor", JsonParser.parseString("""{"version":2,"segments":[{"segment_id":"0","source_file":"docs-a/main.tex"}]}"""))
            })
        }
        File(temporary.root, "linked-in/cards/server/1b0c6c9e-2a39-4c1f-9d55-6a1f4f1f0b11.json").apply {
            parentFile!!.mkdirs(); writeText(record.toString())
        }
        val card = store.card("1b0c6c9e-2a39-4c1f-9d55-6a1f4f1f0b11")!!
        assertFalse(card.htmlPending)
        assertTrue(card.hasPdfPlacement)
        assertEquals(2, card.pdfPage)
        assertEquals(listOf(1), card.masksByPage.keys.toList())
    }

    @Test fun onlyHtmlCardsMayLackGeometry() {
        val (store, _) = store("strict")
        val id = "2c1d7d0f-3b4a-4d2e-8e66-7b2a5a2a1c22"
        val record = JsonObject().apply {
            addProperty("schema_version", 3); addProperty("card_type", "visual_region"); addProperty("card_id", id)
            addProperty("status", "approved"); addProperty("revision", 1); addProperty("origin_id", "server")
            add("primary", JsonObject().apply {
                addProperty("pdf_stem", "manual"); addProperty("pdf_sha256", pdf); addProperty("question", "No area?")
            })
        }
        File(temporary.root, "strict-in/cards/server/$id.json").apply { parentFile!!.mkdirs(); writeText(record.toString()) }
        assertNull(store.card(id))
    }
}
