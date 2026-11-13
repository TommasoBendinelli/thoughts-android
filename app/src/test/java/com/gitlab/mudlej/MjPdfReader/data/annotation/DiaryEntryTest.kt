// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class DiaryEntryTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private val savedAt = ZonedDateTime.of(2026, 10, 24, 14, 3, 27, 123_456_789, zurich)

    @Test fun payloadStampsTheMomentWithOffsetAndZone() {
        val payload = DiaryPayload.newPayload("entry-1", "The light on the lake.", savedAt)
        assertEquals("Note", payload.get("subtype").asString)
        assertEquals("diary", payload.get("entry_kind").asString)
        assertEquals("2026-10-24T14:03:27+02:00", payload.get("diary_at").asString)
        assertEquals("Europe/Zurich", payload.get("diary_zone_id").asString)
        assertEquals("The light on the lake.", payload.get("contents").asString)
        assertEquals("standalone", payload.getAsJsonObject("scope").get("state").asString)
        assertTrue(payload.get("origin").isJsonNull)
        assertEquals(savedAt.toInstant().toString(), payload.get("created_at").asString)
        // Whole seconds print as such, and a zero offset stays numeric.
        val winter = ZonedDateTime.of(2026, 12, 1, 9, 0, 0, 0, ZoneId.of("UTC"))
        assertEquals("2026-12-01T09:00:00+00:00", DiaryPayload.timestamp(winter))
    }

    @Test fun payloadRoundTripsThroughStorageAndEdits() {
        val payload = DiaryPayload.newPayload("entry-1", "First words.", savedAt)
        val stored = JsonParser.parseString(Gson().toJson(payload)).asJsonObject
        val entry = DiaryPayload.entry(stored)!!
        assertEquals("entry-1", entry.id)
        assertEquals("First words.", entry.text)
        assertEquals(OffsetDateTime.parse("2026-10-24T14:03:27+02:00"), entry.at)
        assertEquals(LocalDate.of(2026, 10, 24), entry.day)
        assertEquals("Europe/Zurich", entry.zoneId)
        assertEquals(1L, entry.revision)
        assertFalse(entry.deleted)

        val edited = logicalCommentMutationPayload(restoreNoteNulls(stored), "entry-1", deleted = false, contents = "Better words.")!!
        val editedEntry = DiaryPayload.entry(edited)!!
        assertEquals("Better words.", editedEntry.text)
        assertEquals("the timestamp stays when the text changes", entry.at, editedEntry.at)
        assertEquals("diary", edited.get("entry_kind").asString)
        assertEquals(2L, editedEntry.revision)

        val deleted = DiaryPayload.entry(logicalCommentMutationPayload(edited, "entry-1", deleted = true)!!)!!
        assertTrue(deleted.deleted)
        assertTrue(currentDiaryEntries(listOf(editedEntry, deleted)).isEmpty())
    }

    @Test fun missingDiaryAtFallsBackToCreationTimeInTheEntryZone() {
        val payload = DiaryPayload.newPayload("entry-1", "Words.", savedAt).apply { remove("diary_at") }
        assertEquals(OffsetDateTime.parse("2026-10-24T14:03:27.123456789+02:00"), DiaryPayload.entry(payload)!!.at)
    }

    @Test fun diaryAndTodosNeverMix() {
        val diary = DiaryPayload.newPayload("entry-1", "A diary line.", savedAt)
        val todo = newNotePayload("todo-1", "Buy bread.", "2026-10-24T12:00:00Z")
        val quoting = newNotePayload("todo-2", "Write \"entry_kind\": \"diary\" in the spec.", "2026-10-24T12:00:00Z")
        val otherKind = newNotePayload("todo-3", "Later.", "2026-10-24T12:00:00Z").apply { addProperty("entry_kind", "todo") }
        val comments = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(),
            OfflineOverviewSourceSnapshot().withLogicalPayloads(listOf(diary, todo, quoting, otherKind)).logicalComments,
        ).documents.flatMap { it.comments }
        assertEquals(setOf("entry-1"), comments.filter { it.isDiaryEntry }.map { it.id }.toSet())
        assertEquals(setOf("todo-1", "todo-2", "todo-3"), comments.filter { it.isNote && !it.isDiaryEntry }.map { it.id }.toSet())
        assertNull(DiaryPayload.entry(todo))
        assertNull(DiaryPayload.entry(quoting.toString(), deleted = false))
        assertNull(DiaryPayload.entry(otherKind))
        assertNotNull(DiaryPayload.entry(diary.toString(), deleted = false))
    }

    @Test fun entriesAreGroupedNewestFirstUnderDayHeaders() {
        fun entry(id: String, at: String) = DiaryPayload.entry(
            DiaryPayload.newPayload(id, "Text $id", ZonedDateTime.parse(at)))!!
        val today = LocalDate.of(2026, 10, 24)
        val entries = listOf(
            entry("a", "2026-10-23T09:15:00+02:00[Europe/Zurich]"),
            entry("b", "2026-10-24T14:03:27+02:00[Europe/Zurich]"),
            entry("c", "2026-10-24T08:00:00+02:00[Europe/Zurich]"),
            entry("d", "2026-10-22T23:59:59+02:00[Europe/Zurich]"),
            entry("e", "2025-12-31T10:00:00+01:00[Europe/Zurich]"),
        )
        val rows = diaryRows(entries, today, "Today", "Yesterday", Locale.UK)
        assertEquals(listOf(
            "Today", "14:03 Text b", "08:00 Text c",
            "Yesterday", "09:15 Text a",
            "Thu 22 Oct", "23:59 Text d",
            "Wed 31 Dec 2025", "10:00 Text e",
        ), rows.map { row ->
            when (row) {
                is DiaryRow.Day -> row.label
                is DiaryRow.Entry -> "${row.time} ${row.entry.text}"
            }
        })
        assertTrue(rows.filterIsInstance<DiaryRow.Entry>().all { it.dateLabel == null })
    }

    @Test fun anEntryStaysOnTheDayWhereItWasWritten() {
        // Written at 23:30 in Zurich; read in New York the instant is still the 24th in Zurich.
        val entry = DiaryPayload.entry(DiaryPayload.newPayload("late", "Late thought.",
            ZonedDateTime.of(2026, 10, 24, 23, 30, 0, 0, zurich)))!!
        assertEquals(LocalDate.of(2026, 10, 24), entry.day)
        assertEquals("23:30", diaryTime(entry))
    }

    @Test fun searchNeedsEveryWordIgnoringCaseAndAccents() {
        fun entry(id: String, text: String) = DiaryPayload.entry(DiaryPayload.newPayload(id, text, savedAt))!!
        val entries = listOf(
            entry("1", "Caffè con Élodie al lago"),
            entry("2", "Lago di Como, freddo"),
            entry("3", "Nothing here"),
        )
        assertEquals(listOf("1"), diarySearch(entries, "elodie CAFFE").map { it.id })
        assertEquals(listOf("1", "2"), diarySearch(entries, " lago ").map { it.id })
        assertTrue(diarySearch(entries, "lago nothing").isEmpty())
        assertEquals(3, diarySearch(entries, "   ").size)
        val hits = diarySearchRows(entries, "como", LocalDate.of(2026, 10, 25), "Today", "Yesterday", Locale.UK)
        assertEquals(listOf("Yesterday"), hits.map { (it as DiaryRow.Entry).dateLabel })
    }
}
