// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentMetadataTest {

    @Test
    fun normalizesHttpsChatGptConversationAndShareLinks() {
        assertEquals(
            "https://chatgpt.com/share/01234567-89ab-cdef-0123-456789abcdef",
            normalizeChatGptUrl("  https://chatgpt.com/share/01234567-89ab-cdef-0123-456789abcdef  "),
        )
        assertEquals(
            "https://chatgpt.com/c/01234567-89ab-cdef-0123-456789abcdef",
            normalizeChatGptUrl("https://chatgpt.com/c/01234567-89ab-cdef-0123-456789abcdef"),
        )
        assertEquals(
            "https://team.chatgpt.com/c/example",
            normalizeChatGptUrl("https://team.chatgpt.com/c/example"),
        )
    }

    @Test
    fun rejectsBlankInsecureCredentialedAndForeignLinks() {
        assertNull(normalizeChatGptUrl(null))
        assertNull(normalizeChatGptUrl(" "))
        assertNull(normalizeChatGptUrl("http://chatgpt.com/share/example"))
        assertNull(normalizeChatGptUrl("https://user@chatgpt.com/share/example"))
        assertNull(normalizeChatGptUrl("https://chatgpt.com.example.org/share/example"))
        assertNull(normalizeChatGptUrl("https://example.org/chatgpt"))
    }
}
