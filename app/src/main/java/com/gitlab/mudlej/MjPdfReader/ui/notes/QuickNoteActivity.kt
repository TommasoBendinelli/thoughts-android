// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageButton
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doAfterTextChanged
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.NoteAttachmentStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDrafts
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Each launch owns one new text note; configuration changes preserve that identity. */
class QuickNoteActivity : AppCompatActivity() {
    private lateinit var editor: TextInputEditText
    private lateinit var status: TextView
    private lateinit var noteDetails: NoteDetailsFields
    private lateinit var done: MaterialButton
    private var noteId = ""
    private var finishRequested = false
    private var restoringDraft = false
    private var saving = false
    private var loadingDraft = true
    private var leaveWhenLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resumedDraftId = savedInstanceState?.getString("note_id")
            ?: intent.getStringExtra(EXTRA_DRAFT_ID)?.takeIf { id -> NoteDrafts.unfinished(this).any { it.id == id } }
        // A brand-new note has nothing stored yet, so it can be typed and saved immediately.
        val freshNote = resumedDraftId == null
        noteId = resumedDraftId ?: UUID.randomUUID().toString()
        if (!NoteDrafts.begin(this, noteId)) {
            Toast.makeText(this, R.string.quick_note_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        finishRequested = savedInstanceState?.getBoolean("finish_requested") == true || NoteDrafts.saveRequested(this, noteId)
        title = getString(R.string.note_quick)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val padding = dp(20)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(padding + bars.left, dp(8) + bars.top, padding + bars.right, padding + maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun headerButton(icon: Int, label: Int, action: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(label)
            val background = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId)
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setOnClickListener { action() }
        }
        header.addView(headerButton(androidx.appcompat.R.drawable.abc_ic_ab_back_material, R.string.note_leave_draft, ::leaveDraft), LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply { text = getString(R.string.note_quick); textSize = 24f }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(headerButton(R.drawable.ic_delete, R.string.note_discard_draft, ::confirmDiscard), LinearLayout.LayoutParams(dp(48), dp(48)))
        layout.addView(header)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = NestedScrollView(this).apply { isFillViewport = true; addView(content) }
        layout.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        val field = TextInputLayout(this).apply { hint = getString(R.string.quick_note_hint) }
        editor = TextInputEditText(this).apply {
            id = android.R.id.edit
            gravity = Gravity.TOP
            minLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // A resumed draft shows its text at once, so early typing cannot overwrite it.
            setText(savedInstanceState?.getString("note_text")
                ?: NoteDrafts.localText(this@QuickNoteActivity, noteId).orEmpty())
            if (savedInstanceState == null) setSelection(length())
        }
        field.addView(editor)
        val initialDetails = if (savedInstanceState == null) {
            NoteDrafts.localDetails(this, noteId) ?: com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails()
        } else com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails(
            savedInstanceState.getString("note_project_id").orEmpty(),
            savedInstanceState.getString("note_project_name").orEmpty(),
            savedInstanceState.getString("note_check_where").orEmpty(),
            savedInstanceState.getString("note_calendar_date").orEmpty(),
            savedInstanceState.getString("note_calendar_time").orEmpty(),
            savedInstanceState.getString("note_calendar_zone").orEmpty(),
            savedInstanceState.getString("note_visible_from").orEmpty(),
        )
        noteDetails = NoteDetailsFields(this, initialDetails) { saveDraft() }
        content.addView(noteDetails)
        content.addView(MaterialButton(this).apply {
            setText(R.string.note_attachments)
            isAllCaps = false
            setOnClickListener {
                if (!loadingDraft && !saving && !finishRequested && saveDraft()) {
                    startActivity(Intent(this@QuickNoteActivity, NoteAttachmentsActivity::class.java)
                        .putExtra("note_id", noteId).putExtra("draft", true))
                }
            }
        })
        content.addView(field, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
            setText(R.string.note_draft_only)
        }
        layout.addView(status)
        done = MaterialButton(this).apply {
            id = R.id.quickNoteDone
            isEnabled = false
            setText(R.string.note_done)
            textSize = 28f
            isAllCaps = false
            cornerRadius = dp(22)
            insetTop = 0
            insetBottom = 0
            strokeWidth = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary))
            setOnClickListener {
                if (saving || finishRequested || !saveDraft()) return@setOnClickListener
                if (!NoteDrafts.requestSave(applicationContext, noteId)) {
                    status.setText(R.string.quick_note_failed)
                    return@setOnClickListener
                }
                finishRequested = true
                isEnabled = false
                completeAndReturn()
            }
        }
        layout.addView(done, LinearLayout.LayoutParams(-1, dp(88)))
        setContentView(layout)
        ViewCompat.requestApplyInsets(layout)
        onBackPressedDispatcher.addCallback(this) { leaveDraft() }
        editor.doAfterTextChanged { if (!restoringDraft && !saving) saveDraft() }
        editor.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editor.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        if (freshNote) {
            // Earlier finished notes are delivered in the background without holding up this one.
            NoteDrafts.scope.launch { runCatching { NoteDrafts.flush(applicationContext) } }
            loadingDraft = false
            done.isEnabled = !finishRequested
            if (finishRequested) completeAndReturn()
            return
        }
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { NoteDrafts.flush(applicationContext) } }
            val beforeDetails = noteDetails.value
            val storedDetails = runCatching { withContext(Dispatchers.IO) { NoteDrafts.details(applicationContext, noteId) } }.getOrNull()
            if (storedDetails != null && savedInstanceState == null && noteDetails.value == beforeDetails) noteDetails.restore(storedDetails)
            val restored = runCatching { withContext(Dispatchers.IO) { NoteDrafts.text(applicationContext, noteId) } }.getOrNull()
            if (restored != null && editor.text.isNullOrEmpty()) {
                restoringDraft = true
                editor.setText(restored)
                restoringDraft = false
            }
            loadingDraft = false
            done.isEnabled = !finishRequested
            if (leaveWhenLoaded) leaveDraft()
            else if (finishRequested) completeAndReturn()
        }
    }

    override fun onStart() {
        super.onStart()
        backgroundedDraftId = null
        lifecycleScope.launch {
            val before = editor.text?.toString().orEmpty()
            val restored = runCatching { withContext(Dispatchers.IO) { NoteDrafts.text(applicationContext, noteId) } }.getOrNull()
            // Only replace the text when it changed elsewhere, so the cursor stays where it was.
            if (restored != null && restored != before && editor.text?.toString() == before) {
                restoringDraft = true
                editor.setText(restored)
                editor.setSelection(restored.length)
                restoringDraft = false
            }
        }
    }

    private fun saveDraft(): Boolean {
        val text = editor.text?.toString().orEmpty()
        if (!NoteDrafts.keep(applicationContext, noteId, text, noteDetails.value)) {
            status.setText(R.string.quick_note_failed)
            return false
        }
        showDraftStatus()
        return true
    }

    private fun showDraftStatus() {
        if (!saving) status.setText(R.string.note_draft_only)
    }

    private fun completeAndReturn() {
        if (saving || isFinishing || !finishRequested) return
        if (!NoteDrafts.ready(applicationContext, noteId)) {
            finishRequested = false
            done.isEnabled = true
            status.setText(R.string.quick_note_failed)
            return
        }
        saving = true
        status.setText(R.string.note_saving)
        NoteDrafts.scope.launch {
            val saved = runCatching { NoteDrafts.flush(applicationContext, requiredId = noteId) }.isSuccess
            withContext(Dispatchers.Main) {
                if (!isDestroyed) {
                    if (saved) returnToNotes() else {
                        saving = false
                        finishRequested = false
                        done.isEnabled = true
                        status.setText(R.string.quick_note_failed)
                    }
                }
            }
        }
    }

    private fun leaveDraft() {
        if (loadingDraft) { leaveWhenLoaded = true; return }
        if (saving || finishRequested) return
        if (editor.text.isNullOrBlank() && noteDetails.value == com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails()) {
            // Leaving an empty note drops it instead of piling up blank "Unfinished note" drafts.
            saving = true
            done.isEnabled = false
            lifecycleScope.launch {
                val empty = withContext(Dispatchers.IO) { NoteAttachmentStore(applicationContext).draft(noteId).isEmpty() }
                if (empty) runCatching { NoteDrafts.discard(applicationContext, noteId) }
                else saveDraft()
                returnToNotes()
            }
            return
        }
        if (!saveDraft()) return
        returnToNotes()
    }

    private fun confirmDiscard() {
        if (saving || finishRequested) return
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.note_discard_question)
            .setNegativeButton(R.string.note_keep_editing, null)
            .setPositiveButton(R.string.note_discard_draft) { _, _ ->
                saving = true
                done.isEnabled = false
                lifecycleScope.launch {
                    val discarded = runCatching { NoteDrafts.discard(applicationContext, noteId) }.isSuccess
                    if (discarded) returnToNotes()
                    else { saving = false; done.isEnabled = true; status.setText(R.string.quick_note_failed) }
                }
            }.show()
    }

    private fun returnToNotes() {
        backgroundedDraftId = null
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(HomeActivity.EXTRA_SHOW_NOTES, true))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("note_id", noteId)
        outState.putBoolean("finish_requested", finishRequested)
        outState.putString("note_text", editor.text?.toString())
        outState.putString("note_project_id", noteDetails.value.projectId)
        outState.putString("note_project_name", noteDetails.value.projectName)
        outState.putString("note_check_where", noteDetails.value.checkWhere)
        outState.putString("note_calendar_date", noteDetails.value.calendarDate)
        outState.putString("note_calendar_time", noteDetails.value.calendarTime)
        outState.putString("note_calendar_zone", noteDetails.value.calendarZoneId)
        outState.putString("note_visible_from", noteDetails.value.visibleFrom)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        super.onStop()
        // Home is singleTask, so tapping the launcher icon clears this editor from the task.
        if (!isFinishing && !isChangingConfigurations) backgroundedDraftId = noteId
    }

    companion object {
        const val EXTRA_DRAFT_ID = "unfinishedNoteId"

        /** The draft whose editor was open when the app went to the background, if any. */
        @Volatile var backgroundedDraftId: String? = null
    }
}
