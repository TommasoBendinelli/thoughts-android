// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agents

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNote
import com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations

/**
 * What a tap on an agent conversation does, the same in the Library pane and on a review card
 * (thoughts-agent-conversations-SPEC.md §7): a Claude Code conversation with a claude.ai link
 * opens; Codex threads and Claude sessions without Remote Control explain why they cannot.
 */
object AgentConversationLinks {
    fun agentName(context: Context, record: AgentConversations.Record): String = context.getString(
        if (record.agent == AgentConversations.AGENT_CODEX) R.string.agent_conversation_codex else R.string.agent_conversation_claude)

    /** The conversation's title, or the start of its id when it has none. */
    fun title(record: AgentConversations.Record): String = record.title.ifBlank { record.conversationId.take(8) }

    /** Opens [record]'s claude.ai link, or shows why it cannot be opened on the phone. */
    fun openOrExplain(context: Context, record: AgentConversations.Record) {
        val url = record.claudeUrl
        if (url != null) open(context, url)
        else Toast.makeText(context, if (record.agent == AgentConversations.AGENT_CODEX)
            R.string.agent_conversation_codex_only_mac else R.string.agent_conversation_no_link, Toast.LENGTH_SHORT).show()
    }

    /** Opens the conversation that sent [note] (its origin), or shows why it cannot be opened on the phone. */
    fun openOrExplain(context: Context, note: AgentNote) {
        val url = note.conversationUrl
        when {
            url != null && url.startsWith(AgentConversations.CLAUDE_URL_PREFIX) -> open(context, url)
            url != null || note.conversationAgent == AgentConversations.AGENT_CODEX ->
                Toast.makeText(context, R.string.agent_conversation_codex_only_mac, Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(context, R.string.agent_conversation_no_link, Toast.LENGTH_SHORT).show()
        }
    }

    /** Package of the Claude app, which handles claude.ai/code session links. */
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    private val CLAUDE_CODE_URL = Regex("^https://claude\\.ai/code/(?:session|cse)_[A-Za-z0-9_-]+$")

    /** True for a Claude Code conversation link (https://claude.ai/code/session_<id> or cse_<id>). */
    fun isClaudeCodeUrl(url: String): Boolean = CLAUDE_CODE_URL.matches(url)

    /**
     * The intent that opens [url]: a Claude Code link goes straight to the Claude app when it is
     * installed (a plain VIEW would show the chooser, since browsers match too), anything else and
     * a phone without the app keep the browsable VIEW.
     */
    fun viewIntent(context: Context, url: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (!isClaudeCodeUrl(url)) return intent
        val inApp = Intent(intent).setPackage(CLAUDE_PACKAGE)
        val resolved = try { context.packageManager.resolveActivity(inApp, 0) } catch (_: RuntimeException) { null }
        return if (resolved != null) inApp else intent
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(viewIntent(context, url))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.proposal_open_conversation_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun copyLink(context: Context, url: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Agent conversation link", url))
        Toast.makeText(context, R.string.agent_conversation_link_copied, Toast.LENGTH_SHORT).show()
    }
}
