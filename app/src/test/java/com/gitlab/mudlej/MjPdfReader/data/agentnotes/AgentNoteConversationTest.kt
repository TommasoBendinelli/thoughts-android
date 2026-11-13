// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNoteConversationTest {
    private fun note(origin: String?): AgentNote = AgentNote.fromJson(JsonParser.parseString(
        """{"note_id": "6a1f0c1e-7c39-4f55-a4b0-2b1d8f1e2c3d", "title": "Study"${if (origin != null) ", \"origin\": $origin" else ""}}""",
    ).asJsonObject)

    @Test
    fun claudeLinkFromTheOrigin() {
        val note = note("""{"agent": "mcp", "conversation_agent": "claude", "conversation_id": "6e2bf78a-e90c-4039-8405-a5a697a2f88c",
            "conversation_url": "https://claude.ai/code/session_01AbCdEfGhJkLmNpQrStUvWx", "conversation_title": "Study"}""")
        assertEquals("https://claude.ai/code/session_01AbCdEfGhJkLmNpQrStUvWx", note.conversationUrl)
        assertEquals("claude", note.conversationAgent)
        assertTrue(note.hasConversation)
        // Survives the phone's cache.
        assertEquals(note.conversationUrl, AgentNote.fromJson(note.toJson()).conversationUrl)
    }

    @Test
    fun codexLinkAndIdOnly() {
        val codex = note("""{"conversation_agent": "codex", "conversation_id": "01900000-0000-7000-8000-000000000002",
            "conversation_url": "codex://threads/01900000-0000-7000-8000-000000000002"}""")
        assertEquals("codex://threads/01900000-0000-7000-8000-000000000002", codex.conversationUrl)
        // Older notes name the conversation without a link.
        val old = note("""{"agent": "mcp", "conversation_id": "conv-1"}""")
        assertNull(old.conversationUrl)
        assertTrue(old.hasConversation)
    }

    @Test
    fun noOrJunkConversation() {
        assertFalse(note(null).hasConversation)
        assertFalse(note("""{"agent": "mcp"}""").hasConversation)
        val junk = note("""{"conversation_url": "https://evil.example/code/session_1"}""")
        assertNull(junk.conversationUrl)
        assertFalse(junk.hasConversation)
    }
}
