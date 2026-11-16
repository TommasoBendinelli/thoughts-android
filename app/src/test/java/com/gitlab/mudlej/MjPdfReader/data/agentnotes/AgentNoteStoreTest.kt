// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentNoteStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = AgentNoteStore.under(folder.root)

    private fun note(id: String, sequence: Long, mediaType: String = "image/png", createdAt: String = "2026-10-15T12:00:0${sequence}Z") =
        AgentNote(noteId = id, sequence = sequence, title = "Note $id", mediaType = mediaType, createdAt = createdAt,
            documentId = "doc-1", reviewCardId = "card-$id")

    private fun reply(id: String, noteId: String, created: Long = 1) = LocalAgentNoteReply(
        replyId = id, noteId = noteId, createdAtMillis = created, mediaType = "image/png",
        imageWidth = 10, imageHeight = 20, strokeCount = 2, message = "comment", sent = false,
    )

    private fun leftovers(): List<File> = folder.root.walkTopDown().filter { it.name.endsWith(".tmp") }.toList()

    @Test fun livesUnderFilesDirThoughtsAgentNotes() {
        store().setInboxCursor(3)
        assertTrue(File(folder.root, "thoughts/agent-notes/state.json").isFile)
    }

    @Test fun inboxCursorPersists() {
        assertNull(store().inboxCursor())
        store().setInboxCursor(12)
        assertEquals(12L, store().inboxCursor())
        store().setInboxCursor(40)
        assertEquals(40L, AgentNoteStore.under(folder.root).inboxCursor())
        assertTrue(leftovers().isEmpty())
    }

    @Test fun savesNotesWithTheirImages() {
        val store = store()
        assertFalse(store.hasNote("a"))
        store.saveNote(note("a", 1), byteArrayOf(1, 2, 3))
        store.saveNote(note("b", 2, mediaType = "image/jpeg"), byteArrayOf(4, 5))
        assertTrue(store.hasNote("a"))
        assertEquals("image.png", store.imageFile("a")?.name)
        assertEquals("image.jpg", store.imageFile("b")?.name)
        assertArrayEquals(byteArrayOf(4, 5), store.imageFile("b")?.readBytes())
        assertEquals(listOf("b", "a"), store.notes().map { it.noteId })
        assertEquals("card-a", store.note("a")?.reviewCardId)
        assertTrue(File(folder.root, "thoughts/agent-notes/notes/a/note.json").isFile)

        // A refreshed record keeps the image.
        assertTrue(store.updateNote(note("a", 1).copy(openedAt = "2026-10-15T13:00:00Z")))
        assertEquals("2026-10-15T13:00:00Z", store.note("a")?.openedAt)
        assertTrue(store.hasNote("a"))
        // Unknown notes are not created by an update.
        assertFalse(store.updateNote(note("c", 3)))
        assertFalse(store.hasNote("c"))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun anUnchangedRecordIsNotRewritten() {
        val store = store()
        store.saveNote(note("a", 1), byteArrayOf(1))
        val record = File(folder.root, "thoughts/agent-notes/notes/a/note.json")
        record.setLastModified(1_000_000L)

        assertTrue(store.updateNote(note("a", 1)))
        assertEquals(1_000_000L, record.lastModified())

        assertTrue(store.updateNote(note("a", 1).copy(openedAt = "2026-10-15T13:00:00Z")))
        assertTrue(record.lastModified() != 1_000_000L)
    }

    @Test fun repliesAreGroupedByNoteOldestFirst() {
        val store = store()
        store.queueReply(reply("r2", "a", created = 2), byteArrayOf(1), null)
        store.queueReply(reply("r1", "a", created = 1), byteArrayOf(1), null)
        store.queueReply(reply("r3", "b"), byteArrayOf(1), null)

        val grouped = store.repliesByNote()

        assertEquals(listOf("r1", "r2"), grouped["a"]?.map { it.replyId })
        assertEquals(store.replies("b"), grouped["b"])
        assertNull(grouped["c"])
    }

    @Test fun aNoteWithoutItsRecordIsNotStored() {
        val store = store()
        // An interrupted save leaves the image but no note.json: the inbox downloads it again.
        File(folder.root, "thoughts/agent-notes/notes/x").mkdirs()
        File(folder.root, "thoughts/agent-notes/notes/x/image.png").writeBytes(byteArrayOf(1))
        assertFalse(store.hasNote("x"))
        assertTrue(store.notes().isEmpty())
    }

    @Test fun draftsAreKeptUntilCleared() {
        val store = store()
        assertNull(store.draft("a"))
        store.saveDraft("a", """{"strokes":{}}""")
        assertEquals("""{"strokes":{}}""", store.draft("a"))
        store.clearDraft("a")
        assertNull(store.draft("a"))
    }

    @Test fun outboxKeepsRepliesUntilTheServerConfirms() {
        val store = store()
        store.saveNote(note("a", 1), byteArrayOf(1))
        store.queueReply(reply("r1", "a", created = 1), byteArrayOf(9, 9), """{"schema_version":1}""")
        store.queueReply(reply("r2", "a", created = 2), byteArrayOf(8), null)
        assertEquals(listOf("r1", "r2"), store.pendingReplies().map { it.replyId })
        assertArrayEquals(byteArrayOf(9, 9), store.replyImageFile("r1").readBytes())
        assertEquals("""{"schema_version":1}""", store.replyStrokesFile("r1").readText())
        assertFalse(store.replyStrokesFile("r2").exists())

        // A network failure keeps it queued and counts the attempt.
        store.markReplyFailed("r1", "SocketTimeoutException", permanent = false)
        assertEquals(1, store.reply("r1")?.attempts)
        assertTrue(store.reply("r1")!!.pending)

        store.markReplySent("r1", AgentNoteReply(replyId = "r1", noteId = "a", sequence = 5))
        assertTrue(store.reply("r1")!!.sent)
        assertEquals(5L, store.reply("r1")?.server?.sequence)
        assertEquals(listOf("r2"), store.pendingReplies().map { it.replyId })

        // A permanent refusal leaves the outbox but stays listed.
        store.markReplyFailed("r2", "HTTP 409 agent_note_conflict", permanent = true)
        assertTrue(store.pendingReplies().isEmpty())
        assertTrue(store.reply("r2")!!.rejected)
        assertEquals(listOf("r1", "r2"), store.replies("a").map { it.replyId })
        assertTrue(leftovers().isEmpty())
    }

    @Test fun downloadedRepliesAreSavedForViewing() {
        val store = store()
        store.saveDownloadedReply(AgentNoteReply(replyId = "r9", noteId = "a", message = "hi", imageWidth = 3), byteArrayOf(7))
        val saved = store.reply("r9")!!
        assertTrue(saved.sent)
        assertEquals("hi", saved.message)
        assertTrue(store.pendingReplies().isEmpty())
        assertArrayEquals(byteArrayOf(7), store.replyImageFile("r9").readBytes())
    }

    @Test fun deletingANoteRemovesItsDraftAndReplies() {
        val store = store()
        store.saveNote(note("a", 1), byteArrayOf(1))
        store.saveNote(note("b", 2), byteArrayOf(2))
        store.saveDraft("a", "{}")
        store.queueReply(reply("r1", "a"), byteArrayOf(1), "{}")
        store.queueReply(reply("r2", "b"), byteArrayOf(1), "{}")
        store.deleteNote("a")
        assertFalse(store.hasNote("a"))
        assertNull(store.draft("a"))
        assertNull(store.reply("r1"))
        assertFalse(store.replyImageFile("r1").exists())
        assertNotNull(store.reply("r2"))
        assertTrue(store.hasNote("b"))
    }

    @Test fun aQueuedReplyKeepsItsSourcesAndAnOlderOneHasNone() {
        val store = store()
        store.queueReply(reply("r1", "a").copy(sources = AgentNoteReplySources.NONE.apiValue), byteArrayOf(1), null)
        store.queueReply(reply("r2", "a"), byteArrayOf(1), null)
        val reopened = AgentNoteStore.under(folder.root)
        assertEquals("none", reopened.reply("r1")?.sources)
        assertEquals(AgentNoteReplySources.NONE, reopened.pendingReplies().first { it.replyId == "r1" }.sourcesChoice)
        // A reply written before the choice: its record has no field, and it stays without.
        assertNull(reopened.reply("r2")?.sources)
        assertFalse(File(folder.root, "thoughts/agent-notes/replies/r2.json").readText().contains("sources"))
        // Marked sent with the server's record, the reply still says what it was written with.
        reopened.markReplySent("r1", AgentNoteReply("r1", "a", sequence = 5, sources = "none"))
        assertEquals("none", reopened.reply("r1")?.sources)
        assertEquals("none", reopened.reply("r1")?.server?.sources)

        // A reply only the server has (written on another device) keeps the server's value.
        reopened.saveDownloadedReply(AgentNoteReply("r3", "a", sources = "library"), byteArrayOf(1))
        assertEquals(AgentNoteReplySources.LIBRARY, reopened.reply("r3")?.sourcesChoice)
    }

    @Test fun atomicWritesReplaceWholeFiles() {
        val target = File(folder.root, "x/file.json")
        AgentNoteStore.writeAtomically(target, "first".toByteArray())
        AgentNoteStore.writeAtomically(target, "second, longer".toByteArray())
        assertEquals("second, longer", target.readText())
        assertEquals(listOf("file.json"), target.parentFile!!.list()!!.toList())
    }

    @Test fun idsBecomeSafeFileNames() {
        assertEquals("3f2a-uuid", AgentNoteStore.safeName("3f2a-uuid"))
        assertEquals("_etc_passwd", AgentNoteStore.safeName("../etc/passwd").trimStart('.'))
        assertFalse(AgentNoteStore.safeName("../x").contains('/'))
    }
}
