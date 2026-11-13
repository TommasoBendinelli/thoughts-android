// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes.Answer
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes.Note
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes.OutlineEntry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes.Scope
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes.Target
import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewContextNotesTest {
    // 0-based pages: Introduction p.1, Method p.3 with Data p.3 (lower) and Loss p.5 (Margin p.6),
    // then a part without a destination whose Results start halfway down p.8.
    private val outline = listOf(
        OutlineEntry("1  Introduction", 0),
        OutlineEntry("2 Method", 2, children = listOf(
            OutlineEntry("2.1 Data", 2, top = 0.6f),
            OutlineEntry("2.2\n Loss", 4, children = listOf(OutlineEntry("Margin", 5))),
        )),
        OutlineEntry("Part\u00a0II", -1, children = listOf(OutlineEntry("3 Results", 7, top = 0.5f))),
    )

    @Test fun theSectionIsThePathToTheLastHeadingAtOrBeforeTheCard() {
        assertEquals(Target("1 Introduction", "1 Introduction"), ReviewContextNotes.sectionOf(outline, 1, 0.5f))
        // Method starts at the top of page 3; Data lower on it, at 0.6.
        assertEquals(Target("2 Method", "2 Method"), ReviewContextNotes.sectionOf(outline, 2, 0.3f))
        assertEquals(Target("2 Method › 2.1 Data", "2.1 Data"), ReviewContextNotes.sectionOf(outline, 2, 0.6f))
        assertEquals(Target("2 Method › 2.1 Data", "2.1 Data"), ReviewContextNotes.sectionOf(outline, 3, 0f))
        // Titles collapse their blanks (a no-break space too).
        assertEquals(Target("2 Method › 2.2 Loss", "2.2 Loss"), ReviewContextNotes.sectionOf(outline, 4, 0.1f))
        assertEquals(Target("2 Method › 2.2 Loss › Margin", "Margin"), ReviewContextNotes.sectionOf(outline, 6, 0.9f))
        // A part without a destination starts where its first section does.
        assertEquals(Target("2 Method › 2.2 Loss › Margin", "Margin"), ReviewContextNotes.sectionOf(outline, 7, 0.2f))
        assertEquals(Target("Part II › 3 Results", "3 Results"), ReviewContextNotes.sectionOf(outline, 9, 0f))
        // Of entries starting at the same point, the later one; then only its own entries count.
        val twins = listOf(OutlineEntry("A", 1, children = listOf(OutlineEntry("A.1", 1))), OutlineEntry("B", 1))
        assertEquals(Target("B", "B"), ReviewContextNotes.sectionOf(twins, 3, 0f))
    }

    @Test fun aCardBeforeTheFirstHeadingOrWithoutOutlineHasNoSection() {
        val late = listOf(OutlineEntry("Intro", 1), OutlineEntry("Body", 2, top = 0.4f))
        assertNull(ReviewContextNotes.sectionOf(late, 0, 0.9f))
        assertEquals(Target("Intro", "Intro"), ReviewContextNotes.sectionOf(late, 1, 0f))
        assertEquals(Target("Intro", "Intro"), ReviewContextNotes.sectionOf(late, 2, 0.39f))
        assertNull(ReviewContextNotes.sectionOf(emptyList(), 3, 0.5f))
        // Entries without a destination never count.
        assertNull(ReviewContextNotes.sectionOf(listOf(OutlineEntry("Nowhere", -1)), 3, 0.5f))
    }

    @Test fun headingsOnTheCardsPageGetTheirHeightFromThePageText() {
        val located = ReviewContextNotes.withTops(outline, 2) { title -> if (title == "2 Method") 0.1f else null }
        assertEquals(0.1f, located[1].top)
        // An entry with a known height keeps it; entries on other pages are not looked up.
        assertEquals(0.6f, located[1].children[0].top)
        assertNull(located[0].top)
        assertNull(located[1].children[1].top)
        assertNull(located[2].top)
        // A card above the heading on that page is in the section before it.
        val above = listOf(OutlineEntry("A", 0), OutlineEntry("B", 2, top = 0.5f))
        assertEquals("A", ReviewContextNotes.sectionOf(above, 2, 0.2f)?.key)
        assertEquals("B", ReviewContextNotes.sectionOf(above, 2, 0.5f)?.key)
    }

    @Test fun aHeadingIsFoundWhereItStartsALine() {
        val raw = "Running head: the Loss\r\nWe define the Loss below.\r\n2.2 Loss\r\nThe loss is\r\n"
        val range = ReviewContextNotes.headingRange(raw, "Loss")!!
        assertEquals("Loss", raw.substring(range))
        assertEquals(raw.indexOf("2.2 Loss") + 4, range.first)
        // Blanks are compared collapsed; the case is kept.
        assertEquals("Related  work", "x\nRelated  work\n".substring(ReviewContextNotes.headingRange("x\nRelated  work\n", "Related work")!!))
        assertNull(ReviewContextNotes.headingRange("we define the loss", "Loss"))
        assertNull(ReviewContextNotes.headingRange("in the Loss section", "Loss"))
        assertEquals(0..3, ReviewContextNotes.headingRange("Loss\nmore", "Loss"))
        assertEquals("Appendix", "A Appendix\n".substring(ReviewContextNotes.headingRange("A Appendix\n", "Appendix")!!))
    }

    @Test fun theEventFieldHoldsOnlyChangedScopesAndKeepsClearedOnes() {
        assertNull(ReviewContextNotes.toJson(emptyMap()))
        val field = ReviewContextNotes.toJson(mapOf(
            Scope.PAGE to Note("7", "Page 7", " see figure 3 "),
            Scope.SECTION to Note("3 Method › 3.2 Loss", "3.2 Loss", ""),
            Scope.DOCUMENT to Note("", "Paper", "x".repeat(600)),
        ))
        assertEquals(JsonParser.parseString(
            """{"section":{"key":"3 Method › 3.2 Loss","label":"3.2 Loss","text":""},""" +
                """"page":{"key":"7","label":"Page 7","text":"see figure 3"},""" +
                """"document":{"key":"","label":"Paper","text":"${"x".repeat(500)}"}}"""), field)
        val event = JsonParser.parseString("""{"review_context_notes":$field}""").asJsonObject
        assertEquals(mapOf(
            "section" to Note("3 Method › 3.2 Loss", "3.2 Loss", ""),
            "page" to Note("7", "Page 7", "see figure 3"),
            "document" to Note("", "Paper", "x".repeat(500)),
        ), ReviewContextNotes.parse(event))
    }

    @Test fun malformedScopesAreIgnored() {
        val event = JsonParser.parseString(
            """{"review_context_notes":{"page":{"key":7,"text":"n"},"chapter":{"key":"1","text":"n"},""" +
                """"section":{"key":"S","text":" kept "},"document":"text"}}""").asJsonObject
        assertEquals(mapOf("section" to Note("S", "", "kept")), ReviewContextNotes.parse(event))
        assertEquals(emptyMap<String, Note>(), ReviewContextNotes.parse(JsonParser.parseString("""{"review_context_notes":[1]}""").asJsonObject))
        assertEquals(emptyMap<String, Note>(), ReviewContextNotes.parse(JsonParser.parseString("{}").asJsonObject))
    }

    @Test fun theNewestAnswerOfAnyCardOfTheDocumentWins() {
        val t = Instant.parse("2026-10-21T10:00:00Z")
        fun answer(document: String, id: String, at: Instant?, vararg notes: Pair<String, Note>) = Answer(document, id, at, notes.toMap())
        val answers = sequenceOf(
            answer("doc", "e1", t, "page" to Note("7", "Page 7", "first"), "section" to Note("S", "S", "old section")),
            // Another card of the same document, later: replaces page 7 only.
            answer("doc", "e2", t.plusSeconds(60), "page" to Note("7", "Page 7", "second")),
            // Another page is another note.
            answer("doc", "e3", t.plusSeconds(120), "page" to Note("8", "Page 8", "eight")),
            // Same time: the larger event id wins.
            answer("doc", "e5", t.plusSeconds(180), "document" to Note("", "Paper", "e5")),
            answer("doc", "e4", t.plusSeconds(180), "document" to Note("", "Paper", "e4")),
            // A cleared section note reads "".
            answer("doc", "e6", t.plusSeconds(240), "section" to Note("S", "S", "")),
            // Another document never counts, however new.
            answer("other", "e9", t.plusSeconds(999), "page" to Note("7", "Page 7", "elsewhere")),
        )
        assertEquals(mapOf(
            ("page" to "7") to "second",
            ("page" to "8") to "eight",
            ("document" to "") to "e5",
            ("section" to "S") to "",
        ), ReviewContextNotes.current(answers, "doc"))
    }

    @Test fun undoneAnswersAndDeletionsDoNotCount() {
        fun entry(id: String, rating: String = "custom_interval", retracted: Boolean = false, retractedEventId: String? = null) =
            ReviewHistoryEntry(id, rating, Instant.EPOCH, "o", 1, null, retractedEventId, retracted, true, false, null,
                contextNotes = mapOf("page" to Note("1", "Page 1", id)))
        val answers = ReviewContextNotes.answersOf("doc", listOf(
            entry("kept"), entry("undone", retracted = true), entry("undo", retractedEventId = "undone"), entry("gone", "delete"),
        ))
        assertEquals(listOf("kept"), answers.map { it.eventId }.toList())
    }

    @Test fun aCardsDocumentIsItsDocumentIdThenItsPdfStem() {
        val card = ReviewCard("c", "approved", "paper", "a".repeat(64), 1, "b".repeat(64), "q", android.graphics.RectF(), null, "")
        assertEquals("paper", ReviewContextNotes.documentKey(card))
        // Only the card's document_id counts, as on the desktop.
        assertEquals("paper", ReviewContextNotes.documentKey(card.copy(pdfDocumentId = "pdf-id")))
        assertEquals("doc-id", ReviewContextNotes.documentKey(card.copy(documentId = "doc-id", pdfDocumentId = "pdf-id")))
    }
}
