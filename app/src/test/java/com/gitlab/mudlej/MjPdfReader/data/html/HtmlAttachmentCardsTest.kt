// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card data of review cards inside HTML attachment pages (thoughts-html-attachment-cards-SPEC.md, "Card data"). */
class HtmlAttachmentCardsTest {
    private val path = "docs-ideas/tex/support/html/explainer.html"
    private val sha = "c".repeat(64)

    private fun selection(hash: String?) = HtmlReaderModel.Selection.parse(JsonParser.parseString("""{
        "text": "Simulator runs",
        "text_quote": {"exact": "Simulator runs", "prefix": "The ", "suffix": " offline."},
        ${if (hash != null) "\"page_state\": {\"hash\": ${com.google.gson.JsonPrimitive(hash)}}," else ""}
        "bounds": [1, 2, 3, 4]}""").asJsonObject)!!

    @Test fun selectionCarriesThePageState() {
        assertEquals("#simulator", selection("#simulator").pageHash)
        assertNull(selection(null).pageHash)
    }

    @Test fun fieldsHaveTheSpecShape() {
        val fields = HtmlAttachmentCards.fields(path, sha.uppercase(), selection("#simulator"))
        assertEquals("html_attachment", fields["created_in"].asString)
        assertEquals(path, fields.getAsJsonObject("html_attachment")["path"].asString)
        assertEquals(sha, fields.getAsJsonObject("html_attachment")["sha256"].asString)
        assertEquals("Simulator runs", fields.getAsJsonObject("text_quote")["exact"].asString)
        assertEquals("The ", fields.getAsJsonObject("text_quote")["prefix"].asString)
        assertEquals(" offline.", fields.getAsJsonObject("text_quote")["suffix"].asString)
        assertEquals("#simulator", fields.getAsJsonObject("page_state")["hash"].asString)
        listOf("page", "rect", "regions", "page_visual_sha256", "html_edition_sha256", "source_range").forEach {
            assertFalse("no $it", fields.has(it))
        }
        assertTrue(HtmlAttachmentCards.validFields(fields))
        val anchor = HtmlAttachmentCards.Anchor.from(fields)!!
        assertEquals("#simulator", anchor.pageHash)
        assertEquals(sha, anchor.sha256)
    }

    @Test fun pageStatesThatCannotBeStoredBecomeTheFirstState() {
        assertEquals("", HtmlAttachmentCards.fields(path, sha, selection(null)).getAsJsonObject("page_state")["hash"].asString)
        assertEquals("", HtmlAttachmentCards.fields(path, sha, selection("#")).getAsJsonObject("page_state")["hash"].asString)
        assertEquals("", HtmlAttachmentCards.fields(path, sha, selection("#" + "x".repeat(600))).getAsJsonObject("page_state")["hash"].asString)
        assertEquals("", HtmlAttachmentCards.fields(path, sha, selection("simulator")).getAsJsonObject("page_state")["hash"].asString)
        assertEquals("#" + "x".repeat(511), HtmlAttachmentCards.normalizeHash("#" + "x".repeat(511)))
        assertNull(HtmlAttachmentCards.normalizeHash("#a\u0000b"))
    }

    @Test fun invalidAttachmentsAreRefused() {
        assertTrue(runCatching { HtmlAttachmentCards.fields("docs-ideas/../x.html", sha, selection("")) }.isFailure)
        assertTrue(runCatching { HtmlAttachmentCards.fields("notes.html", sha, selection("")) }.isFailure)
        assertTrue(runCatching { HtmlAttachmentCards.fields(path, "1234", selection("")) }.isFailure)
        val noQuote = HtmlReaderModel.Selection.parse(JsonParser.parseString("""{"text":"x"}""").asJsonObject)!!
        assertTrue(runCatching { HtmlAttachmentCards.fields(path, sha, noQuote) }.isFailure)
        // A primary that is not an attachment card, or a broken one.
        assertNull(HtmlAttachmentCards.Anchor.from(JsonParser.parseString("""{"created_in":"html"}""").asJsonObject))
        assertNull(HtmlAttachmentCards.Anchor.from(JsonParser.parseString("""{"created_in":"html_attachment",
            "html_attachment":{"path":"$path","sha256":"$sha"}}""").asJsonObject))
        assertNull(HtmlAttachmentCards.Anchor.from(JsonParser.parseString("""{"created_in":"html_attachment",
            "html_attachment":{"path":"$path","sha256":"$sha"},"text_quote":{"exact":"a"},"page_state":{"hash":"no-hash"}}""").asJsonObject))
        assertNotNull(HtmlAttachmentCards.Anchor.from(JsonParser.parseString("""{"created_in":"html_attachment",
            "html_attachment":{"path":"$path","sha256":"$sha"},"text_quote":{"exact":"a"}}""").asJsonObject))
    }

    @Test fun ownerAndServedName() {
        assertTrue(HtmlAttachmentCards.Owner("d", "ideas.pdf", sha).valid)
        assertEquals("ideas", HtmlAttachmentCards.Owner("d", "ideas.pdf", sha).pdfStem)
        assertFalse(HtmlAttachmentCards.Owner("d", "../ideas.pdf", sha).valid)
        assertFalse(HtmlAttachmentCards.Owner("d", "ideas.pdf", "x").valid)
        assertEquals("explainer.html", HtmlAttachmentCards.servedName(path))
        assertEquals("kept", HtmlAttachmentCards.storedAnswer(null, " kept "))
    }

    @Test fun viewerCardCarriesItsStyle() {
        val card = HtmlReaderModel.viewerCard("id", emptyList(), HtmlReaderModel.TextQuote("a", "", ""), style = "card")
        assertEquals("card", card["style"].asString)
        assertEquals(0, card.getAsJsonArray("anchors").size())
        assertFalse(HtmlReaderModel.viewerCard("id", emptyList()).has("style"))
    }
}
