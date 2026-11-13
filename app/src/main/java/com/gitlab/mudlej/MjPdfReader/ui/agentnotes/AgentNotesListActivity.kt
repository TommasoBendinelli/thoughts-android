// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteAnswerState
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteEntry
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteFilter
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplyEntry
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotes
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesHttpException
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.agentNoteSampleSize
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.mergeAgentNotes
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityAgentNotesListBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemAgentNoteBinding
import com.gitlab.mudlej.MjPdfReader.ui.agents.AgentConversationLinks
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent notes of a review card, of a document, or all of them: the server's list merged with the
 * phone's cache. Tap opens a note; its replies are listed under it and open read-only; long-press
 * opens the conversation that sent the note (when it names one) or deletes the note (on the server and here).
 */
class AgentNotesListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAgentNotesListBinding
    private val adapter = Adapter()
    private val filter by lazy {
        AgentNoteFilter(intent.getStringExtra(EXTRA_REVIEW_CARD_ID), intent.getStringExtra(EXTRA_DOCUMENT_ID))
    }
    private var loadJob: Job? = null
    private val thumbnails = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentNotesListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15+ draws the app under the status and navigation bars: keep the title bar and list clear of them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setSupportActionBar(binding.agentNotesToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.agent_notes)
        supportActionBar?.subtitle = intent.getStringExtra(EXTRA_SUBTITLE)?.takeIf(String::isNotBlank)
        binding.agentNotesList.layoutManager = LinearLayoutManager(this)
        binding.agentNotesList.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        loadJob?.cancel()
        val store = AgentNotes.store(this)
        val api = AgentNotes.api(this)
        loadJob = lifecycleScope.launch {
            // The cache first, so the list is there at once; then the server's list.
            val started = SystemClock.elapsedRealtime()
            val local = withContext(Dispatchers.IO) { localEntries() }
            show(local, offline = false, loading = true)
            Log.i(AgentNotes.TAG, "agent-notes cached list notes=${local.size} ms=${SystemClock.elapsedRealtime() - started}")
            val server = withContext(Dispatchers.IO) {
                runCatching { api.list(filter.documentId, filter.reviewCardId, LIST_LIMIT) }
                    .onFailure { Log.i(AgentNotes.TAG, "agent notes list offline: ${it.javaClass.simpleName}") }
                    .getOrNull()
            }
            val merging = SystemClock.elapsedRealtime()
            val merged = withContext(Dispatchers.IO) {
                server?.forEach { store.updateNote(it) }
                val notes = store.notes()
                val replies = store.repliesByNote()
                val complete = server != null && server.size < LIST_LIMIT
                if (complete) {
                    // Deleted on another device: forget the cached copy (unless a reply still waits).
                    val listed = server.map { it.noteId }.toSet()
                    notes.filter { filter.matches(it) && it.noteId !in listed && replies[it.noteId].orEmpty().none { r -> r.pending } }
                        .forEach { store.deleteNote(it.noteId) }
                }
                mergeAgentNotes(notes, server, replies, filter, complete)
            }
            show(merged, offline = server == null, loading = false)
            Log.i(AgentNotes.TAG, "agent-notes merged list notes=${merged.size} ms=${SystemClock.elapsedRealtime() - merging}")
            // Notes this phone never stored (delivered elsewhere): fetch their images for thumbnails.
            val missing = merged.filter { it.serverOnly }.take(MAX_BACKGROUND_DOWNLOADS)
            if (missing.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    missing.forEach { entry ->
                        runCatching { store.saveNote(entry.note, api.downloadImage(entry.note)) }
                    }
                }
                show(withContext(Dispatchers.IO) {
                    val notes = store.notes()
                    mergeAgentNotes(notes, server, store.repliesByNote(), filter,
                        server != null && server.size < LIST_LIMIT)
                }, offline = server == null, loading = false)
            }
        }
    }

    private fun localEntries(): List<AgentNoteEntry> {
        val store = AgentNotes.store(this)
        val notes = store.notes()
        return mergeAgentNotes(notes, null, store.repliesByNote(), filter)
    }

    private fun show(entries: List<AgentNoteEntry>, offline: Boolean, loading: Boolean) {
        adapter.submit(entries)
        binding.agentNotesProgress.visibility = if (loading && entries.isEmpty()) View.VISIBLE else View.GONE
        binding.agentNotesEmpty.visibility = if (!loading && entries.isEmpty()) View.VISIBLE else View.GONE
        binding.agentNotesOffline.visibility = if (offline) View.VISIBLE else View.GONE
    }

    /** Long-press on a note: open or copy its conversation's link when it has one, or delete it. */
    private fun showNoteOptions(entry: AgentNoteEntry) {
        val note = entry.note
        if (!note.hasConversation) {
            confirmDelete(entry)
            return
        }
        val url = note.conversationUrl
        val actions = buildList {
            add(getString(R.string.proposal_open_conversation) to { AgentConversationLinks.openOrExplain(this@AgentNotesListActivity, note) })
            if (url != null) add(getString(R.string.agent_conversation_copy_link) to { AgentConversationLinks.copyLink(this@AgentNotesListActivity, url) })
            add(getString(R.string.delete) to { confirmDelete(entry) })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(note.title.ifBlank { getString(R.string.agent_note) })
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun confirmDelete(entry: AgentNoteEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_note_delete_title)
            .setMessage(getString(R.string.agent_note_delete_message, entry.note.title))
            .setPositiveButton(R.string.delete) { _, _ -> delete(entry) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(entry: AgentNoteEntry) {
        val api = AgentNotes.api(this)
        val store = AgentNotes.store(this)
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val result = runCatching { api.delete(entry.note.noteId) }
                val gone = result.isSuccess ||
                    (result.exceptionOrNull() as? AgentNotesHttpException)?.statusCode == 404
                if (gone) store.deleteNote(entry.note.noteId)
                gone
            }
            if (!deleted) {
                AppSnackbar.make(binding.root, R.string.agent_note_delete_failed, Snackbar.LENGTH_LONG).show()
            }
            load()
        }
    }

    private fun thumbnailFile(row: Row): File? {
        val store = AgentNotes.store(this)
        return when (row) {
            is Row.Note -> store.imageFile(row.entry.note.noteId)
            is Row.Reply -> store.replyImageFile(row.reply.replyId).takeIf(File::isFile)
        }
    }

    private fun bindThumbnail(holder: Holder, row: Row) {
        val key = row.key
        holder.binding.agentNoteThumbnail.tag = key
        thumbnails.get(key)?.let {
            holder.binding.agentNoteThumbnail.setImageBitmap(it)
            return
        }
        holder.binding.agentNoteThumbnail.setImageDrawable(null)
        val size = (56 * resources.displayMetrics.density).toInt()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                thumbnailFile(row)?.let { decodeThumbnail(it, size) }
            } ?: return@launch
            thumbnails.put(key, bitmap)
            if (holder.binding.agentNoteThumbnail.tag == key) holder.binding.agentNoteThumbnail.setImageBitmap(bitmap)
        }
    }

    private fun relativeTime(createdAt: String, millis: Long?): String {
        val time = millis ?: runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrNull() ?: return ""
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    private sealed interface Row {
        val key: String
        class Note(val entry: AgentNoteEntry) : Row {
            override val key get() = "note:" + entry.note.noteId
        }
        class Reply(val noteId: String, val reply: AgentNoteReplyEntry) : Row {
            override val key get() = "reply:" + reply.replyId
        }
    }

    private class Holder(val binding: ItemAgentNoteBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var rows: List<Row> = emptyList()

        fun submit(entries: List<AgentNoteEntry>) {
            rows = entries.flatMap { entry ->
                listOf<Row>(Row.Note(entry)) + entry.replies.map { Row.Reply(entry.note.noteId, it) }
            }
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemAgentNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val context = holder.itemView.context
            val indent = (if (row is Row.Reply) 40 else 0) * context.resources.displayMetrics.density
            holder.itemView.setPaddingRelative((16 * context.resources.displayMetrics.density + indent).toInt(),
                holder.itemView.paddingTop, holder.itemView.paddingEnd, holder.itemView.paddingBottom)
            when (row) {
                is Row.Note -> {
                    val note = row.entry.note
                    holder.binding.agentNoteTitle.text = note.title.ifBlank { context.getString(R.string.agent_note) }
                    val state = context.getString(when (row.entry.state) {
                        AgentNoteAnswerState.ANSWERED -> R.string.agent_note_answered
                        AgentNoteAnswerState.NOT_ANSWERED -> R.string.agent_note_not_answered
                        AgentNoteAnswerState.NOT_SENT_YET -> R.string.agent_note_not_sent_yet
                    })
                    holder.binding.agentNoteSubtitle.text = listOf(relativeTime(note.createdAt, null), state)
                        .filter(String::isNotBlank).joinToString(" · ")
                    holder.itemView.setOnClickListener {
                        startActivity(AgentNoteActivity.intent(this@AgentNotesListActivity, note.noteId))
                    }
                    holder.itemView.setOnLongClickListener {
                        showNoteOptions(row.entry)
                        true
                    }
                }
                is Row.Reply -> {
                    val reply = row.reply
                    holder.binding.agentNoteTitle.text = context.getString(
                        if (reply.pending) R.string.agent_note_reply_not_sent else R.string.agent_note_your_reply,
                    )
                    holder.binding.agentNoteSubtitle.text = listOf(relativeTime(reply.createdAt, reply.createdAtMillis),
                        reply.message.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
                    holder.itemView.setOnClickListener {
                        startActivity(AgentNoteActivity.replyIntent(this@AgentNotesListActivity, row.noteId, reply.replyId))
                    }
                    holder.itemView.setOnLongClickListener(null)
                    holder.itemView.isLongClickable = false
                }
            }
            bindThumbnail(holder, row)
        }
    }

    companion object {
        const val EXTRA_REVIEW_CARD_ID = "agent_notes_review_card_id"
        const val EXTRA_DOCUMENT_ID = "agent_notes_document_id"
        const val EXTRA_SUBTITLE = "agent_notes_subtitle"
        private const val LIST_LIMIT = 200
        private const val MAX_BACKGROUND_DOWNLOADS = 20

        /** All notes, or those of a review card or a document; [subtitle] names what is filtered. */
        fun intent(context: Context, reviewCardId: String? = null, documentId: String? = null, subtitle: String? = null): Intent =
            Intent(context, AgentNotesListActivity::class.java)
                .putExtra(EXTRA_REVIEW_CARD_ID, reviewCardId?.takeIf(String::isNotBlank))
                .putExtra(EXTRA_DOCUMENT_ID, documentId?.takeIf(String::isNotBlank))
                .putExtra(EXTRA_SUBTITLE, subtitle)

        fun decodeThumbnail(file: File, sizePx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // Center-cropped into a square: decode to at least the square's side along the short edge.
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            sample = maxOf(sample, agentNoteSampleSize(bounds.outWidth, bounds.outHeight, 4_000_000))
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
