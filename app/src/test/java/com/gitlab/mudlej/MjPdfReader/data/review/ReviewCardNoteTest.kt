// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.RectF
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewCardNoteTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-09-05T10:00:00Z")

    @Test
    fun `legacy card has no note and oversized notes do not change the card`() {
        val fixture = fixture()
        assertEquals("", fixture.store.card(fixture.id)!!.note)
        assertTrue(fixture.store.updateNote(fixture.id, "x".repeat(ReviewStore.MAX_NOTE_LENGTH + 1)).isFailure)
        assertTrue(fixture.store.updateNote("invalid-id", "A note").isFailure)
        assertEquals(5, fixture.store.card(fixture.id)!!.revision)
        assertFalse(fixture.outgoing.walkTopDown().any { it.isFile })
    }

    @Test
    fun `note survives reload and sync projection without changing question geometry or schedule`() {
        val fixture = fixture()
        fixture.store.record(fixture.id, ReviewRating.GOOD, now.minusSeconds(60)).getOrThrow()
        val original = fixture.store.card(fixture.id)!!
        val note = "Additional context.\nSecond line: è utile."
        fixture.store.updateNote(fixture.id, "  $note  ", now).getOrThrow()
        val edited = fixture.reopen().card(fixture.id)!!
        assertEquals(note, edited.note)
        assertEquals(original.question, edited.question)
        assertEquals(original.schedule, edited.schedule)
        assertEquals(6, edited.revision)
        val payload = fixture.outgoing.walkTopDown().single { it.isFile }.readText()
        val json = JsonParser.parseString(payload).asJsonObject
        val source = JsonParser.parseString(fixture.serverCard.readText()).asJsonObject
        assertEquals(source.get("primary"), json.get("primary"))
        assertEquals(source.get("reanchor"), json.get("reanchor"))
        assertEquals(source.get("server_extension"), json.get("server_extension"))
        // Model the acknowledged payload returning in a fresh server projection.
        val receivedRoot = temporary.newFolder()
        File(receivedRoot, "cards/server/${fixture.id}.json").apply {
            parentFile!!.mkdirs()
            writeText(payload)
        }
        assertEquals(note, ReviewStore(null, temporary.newFolder(), receivedRoot,
            temporary.newFolder(), temporary.newFolder()).card(fixture.id)!!.note)
        fixture.store.updateQuestion(fixture.id, "A revised question", now.plusSeconds(1)).getOrThrow()
        fixture.store.updateRegion(fixture.id, "a".repeat(64), 1,
            RectF().apply { left = 0.2f; top = 0.3f; right = 0.6f; bottom = 0.5f },
            now.plusSeconds(2)).getOrThrow()
        assertEquals(note, fixture.reopen().card(fixture.id)!!.note)
    }

    @Test
    fun `clearing a note persists an empty value over the older synced note`() {
        val fixture = fixture()
        fixture.store.updateNote(fixture.id, "Keep this detail", now).getOrThrow()
        fixture.serverCard.writeText(fixture.outgoing.walkTopDown().single { it.isFile }.readText())
        fixture.store.updateNote(fixture.id, " \n ", now.plusSeconds(1)).getOrThrow()
        val cleared = fixture.reopen().card(fixture.id)!!
        assertEquals("", cleared.note)
        assertEquals(7, cleared.revision)
        fixture.store.updateNote(fixture.id, "", now.plusSeconds(2)).getOrThrow()
        assertEquals(7, fixture.reopen().card(fixture.id)!!.revision)
    }

    @Test
    fun `delete permanently hides card while retaining internal history`() {
        val fixture = fixture()
        fixture.store.updateNote(fixture.id, "Extra context", now).getOrThrow()
        assertTrue(fixture.store.dueCards(now).any { it.id == fixture.id })
        fixture.store.deleteCard(fixture.id, now.plusSeconds(1)).getOrThrow()
        assertFalse(fixture.reopen().dueCards(now.plusSeconds(2)).any { it.id == fixture.id })
        assertTrue(fixture.reopen().browserCards(now.plusSeconds(2)).isEmpty())
        assertTrue(fixture.store.restoreCard(fixture.id, now.plusSeconds(3)).isFailure)
        assertFalse(fixture.reopen().dueCards(now.plusSeconds(4)).any { it.id == fixture.id })
        assertEquals("Extra context", fixture.reopen().card(fixture.id)!!.note)
    }

    @Test
    fun `stale note editor cannot overwrite a newer saved note`() {
        val fixture = fixture()
        fixture.store.updateNote(fixture.id, "Newer note", now, expectedNote = "").getOrThrow()
        assertTrue(fixture.store.updateNote(fixture.id, "Stale note", now.plusSeconds(1), expectedNote = "").isFailure)
        assertEquals("Newer note", fixture.reopen().card(fixture.id)!!.note)
        fixture.store.updateNote(fixture.id, "", now.plusSeconds(2), expectedNote = "Newer note").getOrThrow()
        assertEquals("", fixture.reopen().card(fixture.id)!!.note)
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val incoming = temporary.newFolder()
        val events = temporary.newFolder()
        val outgoing = temporary.newFolder()
        val id = UUID.randomUUID().toString()
        val file = File(incoming, "cards/server/$id.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"schema_version":3,"card_type":"visual_region","card_id":"$id","status":"approved","revision":5,"origin_id":"server","primary":{"pdf_stem":"paper","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"What is hidden?"},"reanchor":{"state":"succeeded","strategy":"source_markers_v2"},"server_extension":{"keep":true}}""")
        }
        return Fixture(root, incoming, events, outgoing, id, file)
    }

    private data class Fixture(val root: File, val incoming: File, val events: File,
        val outgoing: File, val id: String, val serverCard: File) {
        val store = reopen()
        fun reopen() = ReviewStore(null, root, incoming, events, outgoing)
    }
}
