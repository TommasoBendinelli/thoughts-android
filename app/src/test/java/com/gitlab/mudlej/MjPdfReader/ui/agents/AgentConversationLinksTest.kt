// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationLinksTest {
    @Test
    fun claudeCodeSessionLinksGoToTheClaudeApp() {
        assertTrue(AgentConversationLinks.isClaudeCodeUrl("https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv"))
        assertTrue(AgentConversationLinks.isClaudeCodeUrl("https://claude.ai/code/cse_01AbC-d_9"))
    }

    @Test
    fun otherLinksKeepTheBrowsableView() {
        listOf(
            "https://claude.ai/code/",
            "https://claude.ai/code/session_",
            "https://claude.ai/code/other_01abc",
            "https://claude.ai/chat/session_01abc",
            "http://claude.ai/code/session_01abc",
            "https://claude.ai.evil.com/code/session_01abc",
            "https://claudeXai/code/session_01abc",
            "https://claude.ai/code/session_01abc/extra",
            "https://claude.ai/code/session_01abc?x=1",
            "codex://threads/0199",
            "",
        ).forEach { assertFalse(it, AgentConversationLinks.isClaudeCodeUrl(it)) }
    }
}
