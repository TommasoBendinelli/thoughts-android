// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlReaderModelTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test fun cardKeysComeFromTheSourceAnchorSegments() {
        val anchor = json("""{"version":2,"segments":[{"segment_id":"0","source_file":"a.tex"},{"segment_id":"1"},{"source_file":"x"}]}""")
        assertEquals(listOf("card:c1:0", "card:c1:1"), HtmlReaderModel.cardAnchorKeys("c1", anchor))
        assertEquals(emptyList<String>(), HtmlReaderModel.cardAnchorKeys("c1", json("""{"version":1,"segments":[{"segment_id":"0"}]}""")))
        assertEquals(emptyList<String>(), HtmlReaderModel.cardAnchorKeys("c1", null))
        val items = json("""{"cards":{"c2":{"anchors":["card:c2:answer"]}}}""")
        assertEquals(listOf("card:c2:answer"), HtmlReaderModel.manifestCardKeys(items, "c2"))
        assertEquals(emptyList<String>(), HtmlReaderModel.manifestCardKeys(items, "c3"))
    }

    @Test fun commentKeyUsesTheMarkerId() {
        assertEquals("comment:m1", HtmlReaderModel.commentAnchorKey("thoughts-1", json("""{"scope":{"anchor_id":"m1"}}""")))
        assertEquals("comment:thoughts-1", HtmlReaderModel.commentAnchorKey("thoughts-1", json("""{"scope":{"state":"pdf_only"}}""")))
    }

    @Test fun selectionPayloadIsParsed() {
        val selection = HtmlReaderModel.Selection.parse(json("""{
            "text":"policy close","text_quote":{"exact":"policy close","prefix":"keeps the ","suffix":" to the"},
            "source_range":{"source_file":"docs-a/main.md","start_byte":10,"end_byte":22,"exact":true},
            "source_lines":{"source_file":"docs-a/main.tex","first_line":4,"last_line":5},
            "answer":{"card_id":"c1","text_quote":{"exact":"policy close","prefix":"The KL term keeps the ","suffix":""}},
            "bounds":[10,20,30,40]}""".trimIndent()))!!
        assertEquals("policy close", selection.quote!!.exact)
        assertEquals(HtmlReaderModel.SourceRange("docs-a/main.md", 10, 22, true), selection.sourceRange)
        assertEquals(Triple("docs-a/main.tex", 4, 5), selection.sourceLines)
        assertEquals("c1", selection.answerCardId)
        assertEquals("The KL term keeps the ", selection.answerQuote!!.prefix)
        assertEquals(listOf(10f, 20f, 30f, 40f), selection.bounds)
        assertNull(HtmlReaderModel.Selection.parse(json("""{"empty":true}""")))
    }

    @Test fun selectionFieldsCarryTheSourceRangeWithItsExactnessWhenHashed() {
        val edition = "e".repeat(64)
        val source = "f".repeat(64)
        val exact = HtmlReaderModel.Selection.parse(json("""{"text":"x y","text_quote":{"exact":"x y","prefix":"a ","suffix":" b"},
            "source_range":{"source_file":"docs-a/m.md","start_byte":3,"end_byte":6,"exact":true}}"""))!!
        val fields = HtmlReaderModel.htmlSelectionFields(edition, exact) { if (it == "docs-a/m.md") source else null }
        assertEquals("html", fields["created_in"].asString)
        assertEquals(edition, fields["html_edition_sha256"].asString)
        assertEquals("x y", fields.getAsJsonObject("text_quote")["exact"].asString)
        val range = fields.getAsJsonObject("source_range")
        assertEquals(source, range["source_sha256"].asString)
        assertEquals(3, range["start_byte"].asInt)
        assertEquals(6, range["end_byte"].asInt)
        assertEquals(true, range["exact"].asBoolean)
        assertEquals(setOf("source_file", "source_sha256", "start_byte", "end_byte", "exact"), range.keySet())
        // Unknown source hash: quote only.
        assertFalse(HtmlReaderModel.htmlSelectionFields(edition, exact) { null }.has("source_range"))
        val rounded = HtmlReaderModel.Selection.parse(json("""{"text":"x","text_quote":{"exact":"x","prefix":"","suffix":""},
            "source_range":{"source_file":"docs-a/m.md","start_byte":3,"end_byte":6,"exact":false}}"""))!!
        // A rounded range is sent with exact false (HtmlSourceRange.exact): Fedora places it by the quote.
        assertEquals(false, HtmlReaderModel.htmlSelectionFields(edition, rounded) { source }
            .getAsJsonObject("source_range")["exact"].asBoolean)
    }

    @Test fun placementReportListsUnplacedCards() {
        val report = HtmlReaderModel.Placement.parse(json("""{"cards":{"placed":["a"],"partial":[],
            "unplaced":[{"id":"b","reason":"no_anchor_in_html"}]},"comments":{"placed":[],"unplaced":[{"id":"c","reason":"no_position"}]}}"""))
        assertEquals(setOf("a"), report.placedCards)
        assertEquals(mapOf("b" to "no_anchor_in_html"), report.unplacedCards)
        assertEquals(mapOf("c" to "no_position"), report.unplacedComments)
        assertEquals(HtmlReaderModel.Placement.EMPTY, HtmlReaderModel.Placement.parse(null))
    }

    @Test fun positionNamesItsItem() {
        val position = HtmlReaderModel.Position.parse(json("""{"anchor_key":"card:c9:0","scroll_fraction":0.4}"""))!!
        assertEquals("card" to "c9", position.item())
        assertEquals(0.4f, position.scrollFraction!!, 1e-6f)
        assertEquals("comment" to "m1", HtmlReaderModel.Position("comment:m1", null).item())
        assertNull(HtmlReaderModel.Position(null, 0.1f).item())
    }

    @Test fun thoughtsLinksAreParsed() {
        val pdf = HtmlReaderModel.Link.parse("thoughts-link:cross-pdf?folder=docs-x&key=paper&anchor=fig%3A1&filename=paper.pdf")!!
        assertEquals("cross-pdf", pdf.kind)
        assertEquals("paper.pdf", pdf.filename)
        assertEquals("fig:1", pdf.anchor)
        val source = HtmlReaderModel.Link.parse("thoughts-link:source?path=docs-x/tex/source/a.tex&label=document-start&filename=a.pdf")!!
        assertEquals("a.pdf", source.filename)
        assertEquals("document-start", source.label)
        assertNull(HtmlReaderModel.Link.parse("thoughts-link:pdf?key=../../etc&anchor=document-start")!!.filename)
        assertNull(HtmlReaderModel.Link.parse("thoughts-link:pdf?key=a&anchor=document-start")!!.anchor)
        assertEquals("docs-x/support/a b.html", HtmlReaderModel.Link.parse("thoughts-link:html?path=docs-x/support/a%20b.html")!!.path)
        assertNull(HtmlReaderModel.Link.parse("https://example.org"))
    }

    @Test fun viewerItems() {
        val card = HtmlReaderModel.viewerCard("c", listOf("card:c:0"), HtmlReaderModel.TextQuote("q", "", ""), selected = true)
        assertEquals("""{"id":"c","anchors":["card:c:0"],"text_quote":{"exact":"q","prefix":"","suffix":""},"selected":true}""", card.toString())
        val comment = HtmlReaderModel.viewerComment("m", emptyList(), "selected words", null, strike = false)
        assertTrue(comment.has("selected_text"))
        assertFalse(comment.has("strike"))
        val feedback = HtmlReaderModel.viewerFeedback("f", "c", false, HtmlReaderModel.TextQuote("q", "a", "b"))
        assertEquals("negative", feedback["polarity"].asString)
        assertTrue(JsonObject().apply { add("x", feedback) }.toString().contains("\"card_id\":\"c\""))
    }
}
