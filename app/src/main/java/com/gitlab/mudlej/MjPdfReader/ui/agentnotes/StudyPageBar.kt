// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNote
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotes
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentStudyListenerService
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentPageTools
import com.gitlab.mudlej.MjPdfReader.databinding.ViewStudyPageBarBinding
import com.gitlab.mudlej.MjPdfReader.pdf.createPdfExtractor
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** The page on screen in the reader, for a page sent to the agent. */
internal data class StudyPage(
    val uri: Uri,
    val password: String?,
    /** 0-based. */
    val index: Int,
    val pages: Int,
    val documentName: String,
    val documentId: String?,
    val documentFilename: String?,
    val reviewCardId: String?,
)

/** Pages the user sends from the reader to their agent: notes from this phone (`origin.kind` "phone_page"). */
internal object StudyPages {
    /** Wide enough to read small print and draw on, small enough for a quick upload. */
    const val WIDTH_PX = 1600

    /** Renders [page] and keeps it on the phone as an unsent page note, with [message]; returns it and its PNG. */
    fun prepare(activity: Activity, page: StudyPage, message: String?): Pair<AgentNote, ByteArray> {
        val extractor = createPdfExtractor(activity, page.uri, page.password)
        val bitmap = try {
            extractor.renderPageThumbnail(page.index, WIDTH_PX)
        } finally {
            extractor.close()
        } ?: throw IllegalStateException("The page could not be rendered")
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        val note = AgentNote(
            noteId = UUID.randomUUID().toString(),
            createdAt = Instant.now().toString(),
            title = activity.getString(R.string.study_page_title, page.documentName, page.index + 1),
            message = message,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            imageBytes = png.size.toLong(),
            documentId = page.documentId,
            documentFilename = page.documentFilename,
            reviewCardId = page.reviewCardId,
            origin = JsonObject().apply {
                addProperty("kind", AgentNote.PHONE_PAGE)
                addProperty("page", page.index + 1)
                addProperty("pages", page.pages)
                addProperty("document_name", page.documentName)
            },
        )
        bitmap.recycle()
        AgentNotes.store(activity).saveNote(note, png)
        return note to png
    }
}

/**
 * The agent tools under the page on screen ([AgentPageTools]; Study mode showed them until push). A drawing tool renders
 * the page and opens it in the note screen with that tool (the comment goes along); Send sends the
 * page as it is, with the comment, to the agent as a note from this phone.
 *
 * During a review the bar folds into one Agent button above the card, so the page keeps its room;
 * the button opens the full bar, which folds again after Send, a drawing tool or its arrow.
 */
internal class StudyPageBar(
    private val activity: AppCompatActivity,
    private val bar: ViewStudyPageBarBinding,
    /** True while the reader shows a PDF. */
    private val documentOpen: () -> Boolean,
    /** The page on screen, or null before the PDF has loaded. */
    private val currentPage: () -> StudyPage?,
) {
    private var busy = false
    private val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private val studyModeChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    /** The reader opened the folded bar (during a review). */
    private var opened = false
    /** The bar's own background, taken away while it is folded into its button. */
    private val fullBackground = bar.root.background

    init {
        // Over the navigation bar: keep the tools clear of it.
        ViewCompat.setOnApplyWindowInsetsListener(bar.root) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom
            view.updatePadding(bottom = bottom + (4 * view.resources.displayMetrics.density).toInt())
            insets
        }
        bar.studyPageToolGroup.check(R.id.studyPageScroll)
        bar.studyPageToolGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || checkedId == R.id.studyPageScroll) return@addOnButtonCheckedListener
            val tool = when (checkedId) {
                R.id.studyPageHighlighter -> AnnotationCanvasView.Mode.HIGHLIGHTER
                R.id.studyPageEraser -> AnnotationCanvasView.Mode.ERASER
                R.id.studyPageNumber -> AnnotationCanvasView.Mode.NUMBER
                else -> AnnotationCanvasView.Mode.PEN
            }
            // The hand stays the reader's tool: the page opens to be marked up, and the bar is ready again after.
            bar.studyPageToolGroup.check(R.id.studyPageScroll)
            markUp(tool)
        }
        bar.studyPageSend.setOnClickListener { sendAsIs() }
        bar.studyPageExpand.setOnClickListener {
            opened = true
            refresh()
        }
        bar.studyPageCollapse.setOnClickListener { fold() }
        preferences.registerOnSharedPreferenceChangeListener(studyModeChanged)
        refresh()
    }

    /** True while the review's before step is asked: the page needs the room, the bar is back at the answer. */
    var steppedAside = false
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }

    /** True while a review card is on screen: the bar is folded into its button unless [opened]. */
    var inReview = false
        set(value) {
            if (field == value) return
            field = value
            // Each review starts folded.
            opened = false
            refresh()
        }


    /** Shown while the agent tools under the page are on and a PDF is open, unless [steppedAside]; folded during a review. */
    fun refresh() {
        bar.root.visibility = if (AgentPageTools.isOn(activity) && documentOpen() && !steppedAside) View.VISIBLE else View.GONE
        val folded = inReview && !opened
        bar.studyPageFull.visibility = if (folded) View.GONE else View.VISIBLE
        bar.studyPageCollapsedRow.visibility = if (folded) View.VISIBLE else View.GONE
        bar.studyPageCollapse.visibility = if (inReview) View.VISIBLE else View.GONE
        // Folded, only the button takes touches: the page beside it stays usable.
        bar.root.background = if (folded) null else fullBackground
        bar.root.isClickable = !folded
    }

    private fun fold() {
        if (!opened) return
        hideKeyboard()
        opened = false
        refresh()
    }

    fun release() = preferences.unregisterOnSharedPreferenceChangeListener(studyModeChanged)

    private fun comment(): String? = bar.studyPageCommentField.text?.toString()?.trim()?.takeIf(String::isNotEmpty)

    private fun markUp(tool: AnnotationCanvasView.Mode) {
        val page = currentPage() ?: return
        if (busy) return
        busy = true
        val message = comment()
        activity.lifecycleScope.launch {
            val note = try {
                // The comment goes to the note screen's field, not into the page note itself.
                withContext(Dispatchers.IO) { StudyPages.prepare(activity, page, null).first }
            } catch (error: Exception) {
                Log.w(AgentNotes.TAG, "study page not prepared: ${error.javaClass.simpleName}")
                Toast.makeText(activity, R.string.study_page_open_failed, Toast.LENGTH_LONG).show()
                null
            }
            busy = false
            if (note == null) return@launch
            hideKeyboard()
            bar.studyPageCommentField.text = null
            fold()
            activity.startActivity(AgentNoteActivity.pageIntent(activity, note.noteId, tool, message))
        }
    }

    private fun sendAsIs() {
        val page = currentPage() ?: return
        if (busy) return
        busy = true
        bar.studyPageSend.isEnabled = false
        val message = comment()
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                try {
                    val (note, png) = StudyPages.prepare(activity, page, message)
                    val created = AgentNotes.api(app).createNote(note, png, 0)
                    AgentNotes.store(app).updateNote(created)
                    true
                } catch (error: IOException) {
                    Log.w(AgentNotes.TAG, "study page not sent: ${error.javaClass.simpleName}")
                    false
                } catch (error: IllegalStateException) {
                    Log.w(AgentNotes.TAG, "study page not rendered: ${error.javaClass.simpleName}")
                    false
                }
            }
            busy = false
            bar.studyPageSend.isEnabled = true
            if (sent) {
                hideKeyboard()
                bar.studyPageCommentField.text = null
                fold()
                // Listens for the answer for 30 minutes, unless push delivers it anyway.
                AgentStudyListenerService.start(activity)
            }
            Toast.makeText(activity, if (sent) R.string.agent_note_sent else R.string.agent_note_page_send_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val field = bar.studyPageCommentField
        if (!field.hasFocus()) return
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }
}
