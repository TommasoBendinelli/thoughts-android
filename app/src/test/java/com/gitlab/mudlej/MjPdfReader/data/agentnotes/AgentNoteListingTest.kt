// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNoteListingTest {
    private fun note(id: String, sequence: Long, card: String? = "card-1", document: String? = "doc-1", replies: List<AgentNoteReply> = emptyList()) =
        AgentNote(noteId = id, sequence = sequence, createdAt = "2026-10-15T12:00:0${sequence}Z", title = id,
            reviewCardId = card, documentId = document, replies = replies)

    private fun local(id: String, noteId: String, sent: Boolean, rejected: Boolean = false) = LocalAgentNoteReply(
        replyId = id, noteId = noteId, createdAtMillis = 1, mediaType = "image/png", imageWidth = 1, imageHeight = 1,
        strokeCount = 1, message = null, sent = sent, rejected = rejected,
    )

    private fun from(conversation: String?, url: String? = null) = JsonObject().apply {
        conversation?.let { addProperty("conversation_id", it) }
        url?.let { addProperty("conversation_url", it) }
    }

    @Test fun theArrowsStepThroughTheConversationsNotesOldestFirst() {
        val study = from("conv-1")
        val notes = listOf(
            note("c", 3).copy(origin = study),
            note("other", 4).copy(origin = from("conv-2")),
            note("a", 1).copy(origin = study),
            note("gone", 2).copy(origin = study, deleted = true),
            note("b", 2).copy(origin = study),
            note("none", 5),
            note("none-earlier", 1),
        )
        assertEquals(listOf("a", "b", "c"), conversationNotes(notes, notes.first { it.noteId == "b" }).map { it.noteId })
        // Notes naming no conversation (sent from a chat through the connector) step through each other.
        assertEquals(listOf("none-earlier", "none"), conversationNotes(notes, notes.first { it.noteId == "none" }).map { it.noteId })
        // A note not stored yet still counts.
        assertEquals(listOf("a", "b", "c", "new"),
            conversationNotes(notes, note("new", 9).copy(origin = study)).map { it.noteId })
        // A conversation known only by its link groups by the link.
        val link = "https://claude.ai/code/session_01BcDeFgHjKmNpQrStUvWxYz"
        val linked = listOf(note("x", 1).copy(origin = from(null, link)), note("y", 2).copy(origin = from(null, link)))
        assertEquals(listOf("x", "y"), conversationNotes(linked, linked.last()).map { it.noteId })
    }

    @Test fun mergesServerAndCacheNewestFirst() {
        val cached = listOf(note("a", 1), note("b", 2))
        val server = listOf(note("b", 2, replies = listOf(AgentNoteReply("r-mac", "b"))), note("c", 3))
        val merged = mergeAgentNotes(cached, server, emptyMap(), AgentNoteFilter())
        assertEquals(listOf("c", "b", "a"), merged.map { it.note.noteId })
        assertTrue(merged.first { it.note.noteId == "c" }.serverOnly)
        assertFalse(merged.first { it.note.noteId == "a" }.serverOnly)
        // The server's record wins: b has its reply now.
        assertEquals(AgentNoteAnswerState.ANSWERED, merged.first { it.note.noteId == "b" }.state)
        assertEquals(AgentNoteAnswerState.NOT_ANSWERED, merged.first { it.note.noteId == "a" }.state)
    }

    @Test fun statesFollowTheOutbox() {
        val notes = listOf(note("a", 1), note("b", 2), note("c", 3))
        val replies = mapOf(
            "a" to listOf(local("r1", "a", sent = false)),
            "b" to listOf(local("r2", "b", sent = true), local("r3", "b", sent = false)),
            "c" to listOf(local("r4", "c", sent = false, rejected = true)),
        )
        val merged = mergeAgentNotes(notes, null, replies, AgentNoteFilter()).associateBy { it.note.noteId }
        assertEquals(AgentNoteAnswerState.NOT_SENT_YET, merged.getValue("a").state)
        assertEquals(AgentNoteAnswerState.ANSWERED, merged.getValue("b").state)
        assertEquals(AgentNoteAnswerState.NOT_ANSWERED, merged.getValue("c").state)
        // The phone's replies are listed once even when the server has them too.
        val withServer = mergeAgentNotes(listOf(note("b", 2, replies = listOf(AgentNoteReply("r2", "b")))), null, replies, AgentNoteFilter())
        assertEquals(listOf("r2", "r3"), withServer.single().replies.map { it.replyId })
    }

    @Test fun theListSaysWhatEachReplyLetTheAgentUse() {
        val replies = mapOf("a" to listOf(local("r1", "a", sent = false).copy(sources = "linked_pdf"), local("r2", "a", sent = true)))
        val fromServer = listOf(AgentNoteReply("r3", "a", sources = "none"), AgentNoteReply("r4", "a"))
        val entry = mergeAgentNotes(listOf(note("a", 1, replies = fromServer)), null, replies, AgentNoteFilter()).single()
        assertEquals(
            listOf("r1" to AgentNoteReplySources.LINKED_PDF, "r2" to null, "r3" to AgentNoteReplySources.NONE, "r4" to null),
            entry.replies.map { it.replyId to it.sources },
        )
    }

    @Test fun filtersByCardOrDocument() {
        val notes = listOf(note("a", 1, card = "card-1"), note("b", 2, card = "card-2"), note("c", 3, card = null, document = "doc-2"))
        assertEquals(listOf("a"), mergeAgentNotes(notes, null, emptyMap(), AgentNoteFilter(reviewCardId = "card-1")).map { it.note.noteId })
        assertEquals(listOf("b", "a"), mergeAgentNotes(notes, null, emptyMap(), AgentNoteFilter(documentId = "doc-1")).map { it.note.noteId })
        assertEquals(3, mergeAgentNotes(notes, null, emptyMap(), AgentNoteFilter()).size)
    }

    @Test fun notesDeletedElsewhereDisappearWhenTheServerListIsComplete() {
        val cached = listOf(note("a", 1), note("b", 2), note("c", 3))
        val replies = mapOf("c" to listOf(local("r", "c", sent = false)))
        val server = listOf(note("a", 1))
        assertEquals(listOf("c", "b", "a"), mergeAgentNotes(cached, server, replies, AgentNoteFilter()).map { it.note.noteId })
        // c keeps its unsent reply.
        assertEquals(listOf("c", "a"), mergeAgentNotes(cached, server, replies, AgentNoteFilter(), serverComplete = true).map { it.note.noteId })
        assertEquals(emptyList<String>(),
            mergeAgentNotes(listOf(note("a", 1)), listOf(note("a", 1).copy(deleted = true)), emptyMap(), AgentNoteFilter()).map { it.note.noteId })
    }

    @Test fun sampleSizeKeepsDecodedImagesUnderTheLimit() {
        assertEquals(1, agentNoteSampleSize(1200, 8192, 12_000_000))
        assertEquals(2, agentNoteSampleSize(8192, 4883, 12_000_000))
        assertEquals(2, agentNoteSampleSize(6000, 6000, 12_000_000))
        assertEquals(4, agentNoteSampleSize(8192, 8192, 4_200_000))
        assertEquals(8, agentNoteSampleSize(8192, 8192, 4_000_000))
        assertEquals(1, agentNoteSampleSize(1, 1, 1))
    }
}
