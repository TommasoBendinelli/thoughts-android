// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.Group
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewGroupNotesTest {
    @Test fun notesAreTrimmedCappedAndEmptyOnesDropped() {
        val long = "x".repeat(600)
        assertEquals(
            mapOf("g1" to "vague second step", "other" to "x".repeat(500)),
            ReviewGroupNotes.normalize(mapOf("g1" to "  vague second step \n", "g2" to "   ", "other" to long, " " to "no group")),
        )
    }

    @Test fun theEventFieldIsOmittedWhenEveryNoteIsEmpty() {
        assertNull(ReviewGroupNotes.toJson(emptyMap()))
        assertNull(ReviewGroupNotes.toJson(mapOf("g1" to " ", "other" to "")))
        assertEquals(JsonParser.parseString("""{"g1":"Fair","other":"see p. 4"}"""),
            ReviewGroupNotes.toJson(mapOf("g1" to "Fair ", "g2" to "", "other" to "see p. 4")))
    }

    @Test fun eventNotesAreReadAndMalformedOnesIgnored() {
        val event = JsonParser.parseString(
            """{"review_group_notes":{"g1":" Fair ","g2":"","other":"two\nlines","bad":{"x":1}}}""").asJsonObject
        assertEquals(mapOf("g1" to "Fair", "other" to "two\nlines"), event.reviewGroupNotes())
        assertEquals(emptyMap<String, String>(), JsonParser.parseString("""{"review_group_notes":["g1"]}""").asJsonObject.reviewGroupNotes())
        assertEquals(emptyMap<String, String>(), JsonParser.parseString("""{}""").asJsonObject.reviewGroupNotes())
    }

    @Test fun linesUseTheCurrentGroupNamesInGroupOrderThenOtherThenUnknownIds() {
        val groups = listOf(Group("g1", "Understanding", emptyList()), Group("g2", "Interest", emptyList()))
        val notes = linkedMapOf("gone" to "old group", "other" to "misc", "g2" to "cool", "g1" to "vague")
        assertEquals(listOf("Understanding: vague", "Interest: cool", "Other: misc", "gone: old group"),
            ReviewGroupNotes.lines(notes, groups))
        assertEquals(listOf("Andere: misc"), ReviewGroupNotes.lines(mapOf("other" to "misc"), groups, "Andere"))
        assertEquals("other", ReviewGroupNotes.keyOf(null))
        assertEquals("g1", ReviewGroupNotes.keyOf(groups[0]))
    }

    @Test fun theAnswerNoteIsListedLastAsNoteNeverAsAGroup() {
        assertEquals("note", ReviewGroupNotes.ANSWER_NOTE_KEY)
        val groups = listOf(Group("g1", "Understanding", emptyList()), Group("g2", "Interest", emptyList()))
        val notes = linkedMapOf("note" to "ask about the proof", "gone" to "old group", "other" to "misc", "g1" to "vague")
        assertEquals(listOf("Understanding: vague", "Other: misc", "gone: old group", "Note: ask about the proof"),
            ReviewGroupNotes.lines(notes, groups))
        assertEquals(listOf("Note: only this"), ReviewGroupNotes.lines(mapOf("note" to "only this"), groups))
        assertEquals(listOf("Interest: cool", "Notiz: x"),
            ReviewGroupNotes.lines(linkedMapOf("note" to "x", "g2" to "cool"), groups, "Andere", "Notiz"))
        // "note" is reserved: even a group record carrying that id does not name it or move it.
        val clash = groups + Group("note", "Clash", emptyList())
        assertEquals(listOf("Interest: cool", "Note: x"), ReviewGroupNotes.lines(linkedMapOf("note" to "x", "g2" to "cool"), clash))
        // Recorded like any group note: trimmed, capped, left out when empty.
        assertEquals(mapOf("note" to "y".repeat(500)), ReviewGroupNotes.normalize(mapOf("note" to " " + "y".repeat(600))))
        assertNull(ReviewGroupNotes.toJson(mapOf("note" to "  ")))
        assertEquals(JsonParser.parseString("""{"g1":"Fair","note":"free text"}"""),
            ReviewGroupNotes.toJson(mapOf("g1" to "Fair", "note" to " free text ")))
        assertEquals(mapOf("note" to "free text"),
            JsonParser.parseString("""{"review_group_notes":{"note":"free text"}}""").asJsonObject.reviewGroupNotes())
    }

    @Test fun theCurrentNotesAreThoseOfTheAnswerGivingTheCurrentTags() {
        val at = java.time.Instant.parse("2026-10-10T10:00:00Z")
        fun entry(id: String, seconds: Long, tags: List<String>, notes: Map<String, String> = emptyMap(),
                  queueAction: String? = null) = ReviewHistoryEntry(id, if (queueAction == null) "custom_interval" else null,
            at.plusSeconds(seconds), "phone", 1, at, null, false, true, false, null,
            queueAction = queueAction, reviewTags = tags, groupNotes = notes)
        val history = listOf(
            entry("a", 0, listOf("correct"), mapOf("g1" to "first")),
            entry("b", 10, listOf("wrong"), mapOf("g1" to "second", "other" to "misc")),
            // A skip keeps the previous answer's tags and notes.
            entry("c", 20, emptyList(), queueAction = "skip"),
        )
        assertEquals(listOf("wrong"), reviewCurrentTags(history))
        assertEquals(mapOf("g1" to "second", "other" to "misc"), reviewCurrentGroupNotes(history))
        assertEquals(emptyMap<String, String>(), reviewCurrentGroupNotes(emptyList()))
    }
}
