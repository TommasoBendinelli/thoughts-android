// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations
import com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations.CardConversation
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.agents.AgentConversationLinks
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The agent conversations linked to the card under review (thoughts-card-agent-conversations-SPEC.md
 * §5): the newest one as a line above Flip | Reveal | Set knowledge, all of them in a sheet opened
 * from the card's menu. The phone only reads them and removes them from the card; the Mac links.
 *
 * The line never appears or disappears by itself while the user is setting knowledge ([frozen]):
 * a Sync that adds the first or removes the last conversation then waits until the answer is
 * hidden again or the next card is shown. [ReviewSessionController] calls [show] when a card is
 * shown, [hide] when none is, [refresh] after a Sync and [update] whenever its buttons change.
 */
internal class ReviewAgentConversations(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val originId: () -> String,
    /** The line may be shown: a card is under review and not being edited. */
    private val allowed: () -> Boolean,
    /** Set knowledge is open: keep the line's presence as it is. */
    private val frozen: () -> Boolean,
    /** Off the main thread: the card's conversations, newest first. Overridable for tests. */
    private val load: suspend (Context, String) -> List<CardConversation> = ::defaultCardConversations,
) {
    private var cardId: String? = null
    /** What the line and the menu show. */
    var conversations: List<CardConversation> = emptyList()
        private set
    /** Loaded while [frozen] and changing whether the line is there; shown at the next [update]. */
    private var deferred: List<CardConversation>? = null
    private var generation = 0
    private var job: Job? = null
    private var dialog: BottomSheetDialog? = null

    init {
        binding.reviewAgentConversation.setOnClickListener {
            conversations.firstOrNull()?.let { AgentConversationLinks.openOrExplain(activity, it.record) }
        }
        binding.reviewAgentConversation.setOnLongClickListener {
            conversations.firstOrNull()?.let(::conversationMenu)
            true
        }
    }

    /** A card is shown (or shown again): its conversations replace the previous card's at once. */
    fun show(cardId: String) {
        if (cardId != this.cardId) {
            this.cardId = cardId
            conversations = emptyList()
            deferred = null
            dialog?.dismiss()
            dialog = null
        }
        update()
        reload(cardId, force = true)
    }

    fun hide() {
        cardId = null
        generation++
        job?.cancel()
        job = null
        conversations = emptyList()
        deferred = null
        dialog?.dismiss()
        dialog = null
        binding.reviewAgentConversation.visibility = View.GONE
    }

    /** A Sync finished: the Mac may have linked or unlinked conversations. */
    fun refresh() {
        cardId?.let { reload(it, force = false) }
    }

    /** Shows what was held back while setting knowledge, and the line as the buttons now allow. */
    fun update() {
        if (!frozen()) deferred?.let { conversations = it; deferred = null }
        render()
    }

    private fun reload(cardId: String, force: Boolean) {
        val token = ++generation
        job?.cancel()
        job = activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try { load(activity.applicationContext, cardId) } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    android.util.Log.w(TAG, "Could not read the card's agent conversations", error)
                    null
                }
            } ?: return@launch
            if (token != generation || this@ReviewAgentConversations.cardId != cardId) return@launch
            if (!force && frozen() && loaded.isEmpty() != conversations.isEmpty()) {
                deferred = loaded
                return@launch
            }
            deferred = null
            if (loaded != conversations) {
                conversations = loaded
                render()
            }
        }
    }

    private fun render() {
        val newest = conversations.firstOrNull()
        val line = binding.reviewAgentConversation
        if (newest == null || cardId == null || !allowed()) {
            line.visibility = View.GONE
            return
        }
        val record = newest.record
        binding.reviewAgentConversationTitle.text = AgentConversationLinks.title(record)
        binding.reviewAgentConversationDetails.text = details(record, newest.link, relative = true)
        line.alpha = if (record.claudeUrl == null) 0.5f else 1f
        line.contentDescription = activity.getString(R.string.agent_conversation_newest_description,
            AgentConversationLinks.title(record))
        line.visibility = View.VISIBLE
    }

    /** "Claude · alex-61 · 5 min ago" on the line, "Claude · alex-61 · 26 Oct 17:46" in the list. */
    private fun details(record: AgentConversations.Record, link: AgentConversations.CardLink, relative: Boolean): String =
        listOfNotNull(
            AgentConversationLinks.agentName(activity, record),
            record.sessionName.takeIf(String::isNotBlank),
            AgentConversations.instant(link.lastActiveAt)?.toEpochMilli()?.let { millis ->
                if (relative) DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                else DateUtils.formatDateTime(activity, millis,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
            },
        ).joinToString(" · ")

    /** The card menu's "Agent conversations (N)": every conversation, newest first. */
    fun showList() {
        if (conversations.isEmpty()) return
        dialog?.dismiss()
        val sheet = BottomSheetDialog(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }
        column.addView(TextView(activity).apply {
            text = activity.getString(R.string.agent_conversations_title)
            textSize = 20f
            setPadding(0, dp(8), 0, dp(12))
        })
        conversations.forEach { conversation -> column.addView(listRow(sheet, conversation)) }
        sheet.setContentView(ScrollView(activity).apply { addView(column) })
        sheet.setOnDismissListener { if (dialog === sheet) dialog = null }
        dialog = sheet
        sheet.show()
    }

    private fun listRow(sheet: BottomSheetDialog, conversation: CardConversation): View {
        val record = conversation.record
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            if (record.claudeUrl == null) alpha = 0.5f
        }
        row.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_agent_conversation)
            contentDescription = AgentConversationLinks.agentName(activity, record)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(activity).apply {
            text = AgentConversationLinks.title(record)
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(activity).apply {
            text = details(record, conversation.link, relative = false)
            textSize = 12f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            if (record.claudeUrl != null) sheet.dismiss()
            AgentConversationLinks.openOrExplain(activity, record)
        }
        row.setOnLongClickListener {
            sheet.dismiss()
            conversationMenu(conversation)
            true
        }
        return row
    }

    /** Long-press: Open in Claude, Copy link, Remove from this card. */
    private fun conversationMenu(conversation: CardConversation) {
        val record = conversation.record
        val actions = buildList<Pair<String, () -> Unit>> {
            record.claudeUrl?.let { url ->
                add(activity.getString(R.string.agent_conversation_open_in_claude) to { AgentConversationLinks.open(activity, url) })
            }
            record.conversationUrl.takeIf(String::isNotEmpty)?.let { url ->
                add(activity.getString(R.string.agent_conversation_copy_link) to { AgentConversationLinks.copyLink(activity, url) })
            }
            add(activity.getString(R.string.agent_conversation_remove_from_card) to { confirmRemove(conversation) })
        }
        dialog?.dismiss()
        val sheet = BottomSheetDialog(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }
        column.addView(TextView(activity).apply {
            text = AgentConversationLinks.title(record)
            textSize = 20f
            setPadding(0, dp(8), 0, dp(12))
        })
        actions.forEach { (title, action) ->
            column.addView(MaterialButton(activity, null, android.R.attr.borderlessButtonStyle).apply {
                text = title
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(52)
                setOnClickListener { sheet.dismiss(); action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }
        sheet.setContentView(column)
        sheet.setOnDismissListener { if (dialog === sheet) dialog = null }
        dialog = sheet
        sheet.show()
    }

    private fun confirmRemove(conversation: CardConversation) {
        val cardId = cardId ?: return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_conversation_remove_title)
            .setMessage(activity.getString(R.string.agent_conversation_remove_from_card_message,
                AgentConversationLinks.agentName(activity, conversation.record)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.agent_conversation_remove_confirm) { _, _ -> remove(cardId, conversation) }
            .show()
    }

    /** Stores the removal pending (sent at the next full Sync) and drops the row at once. */
    private fun remove(cardId: String, conversation: CardConversation) {
        try {
            AgentConversations.removeFromCard(conversation.record, cardId, originId())
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Could not remove an agent conversation from the card", error)
            Toast.makeText(activity, R.string.agent_conversation_remove_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (this.cardId != cardId) return
        // The user asked for it, so the line may change even while setting knowledge.
        conversations = conversations.filterNot { it.record.linkId == conversation.record.linkId }
        deferred = null
        render()
        Toast.makeText(activity, R.string.agent_conversation_removed, Toast.LENGTH_SHORT).show()
        reload(cardId, force = true)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ThoughtsAgentConversations"
    }
}

/** The server's agent conversations with this phone's pending writes laid over them, for one card. */
private suspend fun defaultCardConversations(context: Context, cardId: String): List<CardConversation> {
    val payloads = AppDatabase.getInstance(context.applicationContext).thoughtsSyncDao().agentConversationProjections()
        .mapNotNull { runCatching { JsonParser.parseString(it.payloadJson).asJsonObject }.getOrNull() }
    return AgentConversations.cardConversations(cardId, payloads, AgentConversations.pending())
}
