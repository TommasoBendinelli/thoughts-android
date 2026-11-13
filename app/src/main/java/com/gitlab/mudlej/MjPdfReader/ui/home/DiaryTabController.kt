// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.annotation.DiaryEntry
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchQuery
import com.gitlab.mudlej.MjPdfReader.data.annotation.DiaryPayload
import com.gitlab.mudlej.MjPdfReader.data.annotation.DiaryRow
import com.gitlab.mudlej.MjPdfReader.data.annotation.DiaryStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.diaryDayLabel
import com.gitlab.mudlej.MjPdfReader.data.annotation.diaryRows
import com.gitlab.mudlej.MjPdfReader.data.annotation.diarySearchRows
import com.gitlab.mudlej.MjPdfReader.data.annotation.diaryTime
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

/**
 * The Diary tab: a field that is always at the top, and the entries below it, newest first
 * under one header per day. Entries are notes; saving one is local and joins the next Sync.
 */
internal class DiaryTabController(
    private val activity: AppCompatActivity,
    private val onPendingChangesChanged: () -> Unit,
    private val onHideSearch: () -> Unit,
) {
    private val store = DiaryStore(activity.applicationContext)
    private val adapter = DiaryAdapter(::entryClicked, ::entryLongClicked)
    private val searchAdapter = DiaryAdapter(
        onClick = { entry, view -> onHideSearch(); entryClicked(entry, view) },
        onLongClick = { entry, view -> onHideSearch(); entryLongClicked(entry, view) },
    )
    private val draft = activity.getSharedPreferences(DRAFT_PREFERENCES, Context.MODE_PRIVATE)
    private var page: SwipeRefreshLayout? = null
    private var input: TextInputEditText? = null
    private var loaded = false
    private var searchQuery = ""
    /** Entries as the comment store lists them, tombstones included. */
    private var stored: Map<String, DiaryEntry> = emptyMap()
    /** This tab's own writes, shown until the comment store has caught up with them. */
    private val local = linkedMapOf<String, DiaryEntry>()

    fun attach(swipeRefresh: SwipeRefreshLayout) {
        page = swipeRefresh
        val list = swipeRefresh.findViewById<RecyclerView>(R.id.pageRecyclerView)
        if (list.adapter !== adapter) {
            list.layoutManager = LinearLayoutManager(list.context)
            list.adapter = adapter
        }
        // The field sits above the list, so pull-to-refresh asks the list, not the page.
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
        val field = swipeRefresh.findViewById<TextInputEditText>(R.id.diaryInput)
        val send = swipeRefresh.findViewById<View>(R.id.diarySendButton)
        if (input !== field) {
            input = field
            // Multi-line on screen, but the keyboard's Enter key is a Send key.
            field.setHorizontallyScrolling(false)
            field.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
            field.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            if (field.text.isNullOrEmpty()) field.setText(draft.getString(DRAFT_TEXT, "").orEmpty())
            send.isEnabled = !field.text.isNullOrBlank()
            field.doAfterTextChanged { text ->
                send.isEnabled = !text.isNullOrBlank()
                draft.edit().putString(DRAFT_TEXT, text?.toString().orEmpty()).apply()
            }
            field.setOnEditorActionListener { _, actionId, event ->
                when {
                    actionId == EditorInfo.IME_ACTION_SEND -> { save(); true }
                    // A hardware Enter arrives as IME_NULL, once down and once up; Shift+Enter adds a line.
                    event?.keyCode == KeyEvent.KEYCODE_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN) save()
                        true
                    }
                    else -> false
                }
            }
            send.setOnClickListener { save() }
        }
        renderViews()
    }

    /** Diary entries holding every word of [query], newest first (the Home global search). */
    fun globalSearchEntries(query: GlobalSearchQuery): List<DiaryEntry> =
        entries().filter { !it.deleted && query.matches(it.text) }.sortedByDescending { it.at }

    fun openGlobalSearchEntry(entry: DiaryEntry) = showEditor(current(entry.id) ?: entry)

    fun attachSearch(recyclerView: RecyclerView) {
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.adapter = searchAdapter
        renderSearch()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query.trim()
        renderSearch()
    }

    /** [logicalComments] is Home's current comment snapshot; diary entries are picked out of it. */
    fun render(logicalComments: List<OfflineLogicalComment>, sourceLoaded: Boolean) {
        stored = logicalComments.mapNotNull { DiaryPayload.entry(it.payloadJson, it.deleted) }.associateBy(DiaryEntry::id)
        local.entries.removeAll { (id, entry) -> (stored[id]?.revision ?: -1) >= entry.revision }
        loaded = loaded || sourceLoaded
        renderViews()
    }

    private fun entries(): List<DiaryEntry> = (stored.values.filter { it.id !in local } + local.values)

    private fun renderViews(onCommitted: Runnable? = null) {
        val all = entries()
        adapter.submitRows(
            diaryRows(all, LocalDate.now(), activity.getString(R.string.diary_today), activity.getString(R.string.diary_yesterday)),
            searching = false,
            loading = !loaded && all.isEmpty(),
            committed = onCommitted,
        )
        renderSearch(all)
    }

    private fun renderSearch(all: List<DiaryEntry> = entries()) {
        searchAdapter.submitRows(
            diarySearchRows(all, searchQuery, LocalDate.now(),
                activity.getString(R.string.diary_today), activity.getString(R.string.diary_yesterday)),
            searching = searchQuery.isNotBlank(),
            loading = !loaded && all.isEmpty(),
        )
    }

    private fun save() {
        val field = input ?: return
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val at = ZonedDateTime.now()
        val id = UUID.randomUUID().toString()
        local[id] = requireNotNull(DiaryPayload.entry(DiaryPayload.newPayload(id, text, at)))
        field.text?.clear()
        draft.edit().remove(DRAFT_TEXT).apply()
        renderViews { page?.findViewById<RecyclerView>(R.id.pageRecyclerView)?.scrollToPosition(0) }
        write(onFailure = {
            local.remove(id)
            // Hand the words back rather than lose them.
            if (input?.text.isNullOrEmpty()) input?.setText(text)
            message(R.string.diary_save_failed)
        }) { store.add(text, at, id) }
    }

    private fun entryClicked(entry: DiaryEntry, @Suppress("UNUSED_PARAMETER") view: View) {
        showEditor(entry)
    }

    private fun entryLongClicked(entry: DiaryEntry, view: View) {
        PopupMenu(activity, view).apply {
            menu.add(0, ACTION_EDIT, 0, R.string.edit)
            menu.add(0, ACTION_COPY, 1, R.string.copy)
            menu.add(0, ACTION_DELETE, 2, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ACTION_EDIT -> showEditor(entry)
                    ACTION_COPY -> activity.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.home_tab_diary), entry.text))
                    ACTION_DELETE -> delete(entry)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun showEditor(entry: DiaryEntry) {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val editor = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            setText(entry.text)
            setSelection(length())
        }
        val field = TextInputLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(editor)
        }
        val title = activity.getString(
            R.string.diary_edit_title,
            diaryDayLabel(entry.day, LocalDate.now(), activity.getString(R.string.diary_today), activity.getString(R.string.diary_yesterday)),
            diaryTime(entry),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(field)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete) { _, _ -> delete(current(entry.id) ?: entry) }
            .setPositiveButton(R.string.save) { _, _ -> edit(current(entry.id) ?: entry, editor.text?.toString()?.trim().orEmpty()) }
            .create()
            .apply {
                window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                show()
            }
        editor.requestFocus()
    }

    private fun current(id: String): DiaryEntry? = local[id] ?: stored[id]

    private fun edit(entry: DiaryEntry, text: String) {
        if (text == entry.text) return
        if (text.isEmpty()) {
            delete(entry)
            return
        }
        local[entry.id] = entry.copy(text = text, revision = entry.revision + 1)
        renderViews()
        write(onFailure = {
            local.remove(entry.id)
            message(R.string.diary_save_failed)
        }) { store.edit(entry.id, text) }
    }

    private fun delete(entry: DiaryEntry) {
        local[entry.id] = entry.copy(deleted = true, revision = entry.revision + 1)
        renderViews()
        write(onFailure = {
            local.remove(entry.id)
            message(R.string.diary_delete_failed)
        }) { store.delete(entry.id) }
        page?.let { view ->
            AppSnackbar.make(view, R.string.diary_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.overview_undo) { restore(entry) }
                .show()
        }
    }

    private fun restore(entry: DiaryEntry) {
        val deleted = current(entry.id) ?: return
        local[entry.id] = deleted.copy(text = entry.text, deleted = false, revision = deleted.revision + 1)
        renderViews()
        write(onFailure = {
            local.remove(entry.id)
            message(R.string.diary_save_failed)
        }) { store.restore(entry.id) }
    }

    /** Runs [action] after earlier diary writes; its result replaces the optimistic row. */
    private fun write(onFailure: () -> Unit, action: suspend () -> DiaryEntry) {
        DiaryStore.writes.launch {
            val result = runCatching { DiaryStore.inOrder { action() } }
            withContext(Dispatchers.Main) {
                result.onSuccess { saved ->
                    // A later local change of the same entry stays on top until it is written too.
                    if ((local[saved.id]?.revision ?: -1) <= saved.revision) local[saved.id] = saved
                    onPendingChangesChanged()
                }.onFailure { error ->
                    android.util.Log.e(TAG, "Unable to write a diary entry", error)
                    if (activity.isDestroyed) return@withContext
                    onFailure()
                }
                if (!activity.isDestroyed) renderViews()
            }
        }
    }

    private fun message(text: Int) {
        page?.let { AppSnackbar.make(it, text, Snackbar.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "DiaryTab"
        private const val DRAFT_PREFERENCES = "diary_draft"
        private const val DRAFT_TEXT = "text"
        private const val ACTION_EDIT = 1
        private const val ACTION_COPY = 2
        private const val ACTION_DELETE = 3
    }
}
