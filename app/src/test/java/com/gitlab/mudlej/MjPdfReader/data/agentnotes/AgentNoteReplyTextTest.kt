// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentNoteReplyTextTest {
    private val id = "3212c09d-d3bc-43d9-97f6-1b1b80adff15"

    @Test fun aSentReplyCarriesTheWordsTheNoteAndHowToReadThePicture() {
        assertEquals(
            "My reply on your agent note \"New drafter\" (note $id):\nGive me a new version\n\n" +
                "Read my reply with its picture: get_note_reply(note_id=\"$id\").",
            agentNoteReplyForConversation("New drafter", id, "  Give me a new version ", replied = true),
        )
        assertEquals(
            "I replied on your agent note \"Agent note\" (note $id) with a drawing. " +
                "Read my reply with its picture: get_note_reply(note_id=\"$id\").",
            agentNoteReplyForConversation("", id, " ", replied = true),
        )
    }

    @Test fun beforeSendingOnlyTypedWordsAreCopied() {
        assertEquals("About your agent note \"New drafter\" (note $id):\nRedo it", agentNoteReplyForConversation("New drafter", id, "Redo it", replied = false))
        assertNull(agentNoteReplyForConversation("New drafter", id, null, replied = false))
        assertNull(agentNoteReplyForConversation("New drafter", id, "", replied = false))
    }
}
