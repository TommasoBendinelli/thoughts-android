// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.agentnotes

import android.app.ActivityManager
import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.AGENT_NOTE_TAB_SCHEME
import com.gitlab.mudlej.MjPdfReader.ui.reader.CurrentDocumentSnapshot
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentTabsHost
import com.gitlab.mudlej.MjPdfReader.ui.reader.OpenDocumentTabsController
import android.app.Application
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteLink
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.ui.links.EXTRA_REMOTE_PDF_LINK
import com.gitlab.mudlej.MjPdfReader.ui.links.installedRemotePdf
import com.gitlab.mudlej.MjPdfReader.ui.links.parseRemotePdfLink
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.AgentNotePlace
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.EXTRA_AGENT_NOTE_PLACE
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderNoteHistory
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNote
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteDraft
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteInbox
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteUnseen
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteCoverer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AGENT_NOTE_ANSWER_BY_CAPABILITY
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteAgent
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListening
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListeningAnswer
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteListeningCache
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteReplySources
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteStore
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteServerCapabilities
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteWake
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotesHttpException
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteNotifier
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNotes
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentStudyListenerService
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.AnnotationDocument
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.LocalAgentNoteReply
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.agentNoteReplyForConversation
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.glasses.GlassesCamera
import com.gitlab.mudlej.MjPdfReader.data.glasses.GlassesCameraException
import com.gitlab.mudlej.MjPdfReader.data.glasses.GlassesCameraProblem
import com.gitlab.mudlej.MjPdfReader.data.glasses.GlassesPhoto
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.agentNoteSampleSize
import com.gitlab.mudlej.MjPdfReader.data.agentnotes.arrowNotes
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityAgentNoteBinding
import com.gitlab.mudlej.MjPdfReader.ui.agents.AgentConversationLinks
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the note screen shows. */
sealed interface AgentNoteScreenState {
    data object Loading : AgentNoteScreenState
    data class Failed(val message: String) : AgentNoteScreenState
    class Ready(
        val note: AgentNote,
        val bitmap: Bitmap,
        val document: AnnotationDocument,
        val readOnly: Boolean,
        /** Read-only reply: its comment. */
        val replyMessage: String? = null,
        /** Read-only reply: what the answering agent was allowed to use, when the reply says. */
        val replySources: AgentNoteReplySources? = null,
    ) : AgentNoteScreenState
    /**
     * A reply on its way: [takingPicture] while Send with picture waits for the glasses' photo, then
     * while it is saved and sent. The note stays usable meanwhile (the reply took the drawing and
     * comment as they were when Send was pressed); only Send waits.
     */
    class Sending(val ready: Ready, val takingPicture: Boolean = false) : AgentNoteScreenState
    /**
     * A reply went out (or is queued): the note stays open, drawings kept, ready for another reply.
     * [listening]: whether an agent heard it (the server's answer); [comment] is what was sent;
     * [wake]: the session a reply waker wakes for it (the same answer).
     */
    class Sent(
        val ready: Ready,
        val delivered: Boolean,
        val listening: AgentNoteListening = AgentNoteListening.UNKNOWN,
        val comment: String? = null,
        val coverer: AgentNoteCoverer? = null,
        val wake: AgentNoteWake? = null,
    ) : AgentNoteScreenState
}

/** What the note screen says when Send with picture got no picture (nothing was sent). */
fun agentNotePictureProblemText(problem: GlassesCameraProblem): Int = when (problem) {
    GlassesCameraProblem.NOT_SET_UP -> R.string.agent_note_picture_not_set_up
    GlassesCameraProblem.NOT_CONNECTED -> R.string.agent_note_picture_not_connected
    GlassesCameraProblem.NOT_ALLOWED -> R.string.agent_note_picture_not_allowed
    GlassesCameraProblem.CAMERA_BUSY -> R.string.agent_note_picture_busy
    GlassesCameraProblem.NO_PHOTO -> R.string.agent_note_picture_no_photo
}

/** The subtitle after Send: queued, or sent and whether an agent hears it. */
fun agentNoteSentStatus(delivered: Boolean, listening: AgentNoteListening): Int = when {
    !delivered -> R.string.agent_note_queued
    listening == AgentNoteListening.LISTENING -> R.string.agent_note_sent_waiting
    listening == AgentNoteListening.NOT_LISTENING -> R.string.agent_note_sent_not_listening
    else -> R.string.agent_note_sent_plain
}

/** Holds the decoded note and the strokes across rotation; the draft file covers process death. */
class AgentNoteViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow<AgentNoteScreenState>(AgentNoteScreenState.Loading)
    val state: StateFlow<AgentNoteScreenState> = mutableState
    private var started = false

    private val mutableListening = MutableStateFlow(AgentNoteListening.UNKNOWN)
    /** Whether the agent that sent the note listens for the reply (last answer; UNKNOWN until one, or offline). */
    val listening: StateFlow<AgentNoteListening> = mutableListening
    private val mutableCoverer = MutableStateFlow<AgentNoteCoverer?>(null)
    /** Who answers a reply while [listening] (the sender's agent, a topic owner or the reply agent); null when unnamed. */
    val coverer: StateFlow<AgentNoteCoverer?> = mutableCoverer
    private val mutableAnswer = MutableStateFlow<AgentNoteListeningAnswer?>(null)
    /** The server's last answer about who listens, with the user's choice and the agents to choose from; null before any. */
    val answer: StateFlow<AgentNoteListeningAnswer?> = mutableAnswer
    private val mutableCanChangeAnswerer = MutableStateFlow(false)
    /** Whether the server lets the user choose who answers ([AGENT_NOTE_ANSWER_BY_CAPABILITY]). */
    val canChangeAnswerer: StateFlow<Boolean> = mutableCanChangeAnswerer
    private val mutableServerReachable = MutableStateFlow(true)
    /** False once a request about who listens did not reach the server, until one does; "Change" needs the server. */
    val serverReachable: StateFlow<Boolean> = mutableServerReachable

    private fun showAnswer(answer: AgentNoteListeningAnswer) {
        mutableCoverer.value = answer.coverer
        mutableListening.value = answer.state
        mutableAnswer.value = answer
    }

    /**
     * Follows whether an agent listens for replies to [noteId] until the caller is cancelled (its
     * screen is no longer visible): the answer kept for the note during the last minute or the
     * server's at once, then the server's every minute ([AgentNoteListeningCache.follow]). Keeps
     * the last answer when offline.
     */
    suspend fun followListening(noteId: String) {
        val app = getApplication<Application>()
        // Asked once per server and kept: an older server shows the line without "Change".
        suspend fun learnWhetherTheChoiceIsOffered() = withContext(Dispatchers.IO) {
            runCatching {
                AgentNoteServerCapabilities.shared.has(AGENT_NOTE_ANSWER_BY_CAPABILITY, AgentNotes.api(app)::capabilities)
            }.onSuccess { mutableCanChangeAnswerer.value = it }
        }
        kotlinx.coroutines.coroutineScope {
            // Beside the first answer, which may be a kept one that asks the server nothing.
            launch { learnWhetherTheChoiceIsOffered() }
            AgentNoteListeningCache.shared.follow(
                noteId,
                ask = {
                    withContext(Dispatchers.IO) { AgentNotes.api(app).listeningAnswer(noteId) }.also {
                        mutableServerReachable.value = true
                        if (!mutableCanChangeAnswerer.value) learnWhetherTheChoiceIsOffered()
                    }
                },
                show = ::showAnswer,
                failed = {
                    if (it !is AgentNotesHttpException) mutableServerReachable.value = false
                    Log.i(AgentNotes.TAG, "listening check failed: ${it.javaClass.simpleName}")
                },
            )
        }
    }

    /**
     * Sends the user's choice of who answers replies to [noteId] ([agent]; null is automatic). The
     * line and the answer kept for the note follow the server's answer. Returns the failure, or
     * null: after a failure the choice is as it was.
     */
    suspend fun chooseAnswerer(noteId: String, agent: AgentNoteAgent?): Exception? {
        val app = getApplication<Application>()
        return try {
            showAnswer(withContext(Dispatchers.IO) {
                AgentNoteListeningCache.shared.choose(noteId, agent) { AgentNotes.api(app).setAnswerBy(noteId, it) }
            })
            mutableServerReachable.value = true
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.i(AgentNotes.TAG, "answer-by choice failed: ${error.javaClass.simpleName}")
            // A transport failure means the server cannot be reached now; a refusal does not.
            if (error !is AgentNotesHttpException) mutableServerReachable.value = false
            error
        }
    }

    var message = ""
    var messageDirty = false
    /** A new note opens in Scroll, so one finger moves through it until a drawing tool is picked. */
    var mode = AnnotationCanvasView.Mode.SCROLL
    var color = INK

    /** The tool and comment a page from the reader opens with; ignored once the note has loaded. */
    fun startWith(tool: AnnotationCanvasView.Mode?, comment: String?) {
        if (started) return
        tool?.let { mode = it }
        if (!comment.isNullOrBlank()) {
            message = comment
            messageDirty = true
        }
    }

    fun load(noteId: String, replyId: String?) {
        if (started) return
        started = true
        AgentNotes.ioScope.launch {
            mutableState.value = try {
                loadState(noteId, replyId)
            } catch (error: Exception) {
                Log.w(AgentNotes.TAG, "agent note load failed: ${error.javaClass.simpleName}")
                AgentNoteScreenState.Failed(
                    getApplication<Application>().getString(
                        if (error is IOException) R.string.agent_note_load_failed_offline else R.string.agent_note_load_failed,
                    ),
                )
            }
        }
    }

    private fun loadState(noteId: String, replyId: String?): AgentNoteScreenState.Ready {
        val app = getApplication<Application>()
        val store = AgentNotes.store(app)
        var note = store.note(noteId)
        if (note == null || store.imageFile(noteId) == null) {
            val api = AgentNotes.api(app)
            note = api.note(noteId)
            store.saveNote(note, api.downloadImage(note))
        }
        if (replyId != null) {
            val file = store.replyImageFile(replyId)
            if (!file.isFile) {
                val api = AgentNotes.api(app)
                val reply = note.replies.firstOrNull { it.replyId == replyId }
                    ?: api.note(noteId).replies.first { it.replyId == replyId }
                store.saveDownloadedReply(reply, api.downloadReplyImage(reply))
            }
            val decoded = decodeBounded(file)
            val local = store.reply(replyId)
            val fromServer = note.replies.firstOrNull { it.replyId == replyId }
            return AgentNoteScreenState.Ready(
                note, decoded.bitmap, AnnotationDocument(decoded.bitmap.width, decoded.bitmap.height), true,
                local?.message ?: fromServer?.message, local?.sourcesChoice ?: fromServer?.sourcesChoice,
            )
        }
        val decoded = decodeBounded(requireNotNull(store.imageFile(noteId)))
        val bitmap = decoded.bitmap
        val draft = store.draft(noteId)?.let(AgentNoteDraft::fromJson)
        val restored = draft?.let { runCatching { AnnotationDocument.fromJson(it.strokesJson) }.getOrNull() }?.let {
            if (it.imageWidth == bitmap.width && it.imageHeight == bitmap.height) it
            else it.scaledTo(bitmap.width, bitmap.height, decoded.sourceWidth, decoded.sourceHeight)
        }
        if (draft != null) {
            message = draft.message
            mode = AnnotationCanvasView.Mode.entries.firstOrNull { it.name.equals(draft.tool, ignoreCase = true) }
                ?: AnnotationCanvasView.Mode.PEN
        }
        val document = restored ?: AnnotationDocument(bitmap.width, bitmap.height, decoded.sourceWidth, decoded.sourceHeight)
        document.markSaved()
        if (note.openedAt == null && !note.isPhonePage) {
            AgentNotes.ioScope.launch {
                runCatching { AgentNotes.api(app).receipt(noteId, "opened") }
                    .onSuccess { store.updateNote(it) }
                    .onFailure { Log.i(AgentNotes.TAG, "opened receipt failed: ${it.javaClass.simpleName}") }
            }
        }
        return AgentNoteScreenState.Ready(note, bitmap, document, false)
    }

    /** Keeps the strokes (and comment, tool) on disk until sent. */
    fun saveDraft() {
        val ready = currentReady() ?: return
        if (ready.readOnly || (!ready.document.dirty && !messageDirty)) return
        val noteId = ready.note.noteId
        val store = AgentNotes.store(getApplication())
        if (ready.document.isEmpty && message.isBlank()) {
            AgentNotes.ioScope.launch { store.clearDraft(noteId) }
        } else {
            val json = AgentNoteDraft(ready.document.toJson(), message, mode.name.lowercase(),
                AnnotationDocument.colorHex(color)).toJson()
            AgentNotes.ioScope.launch { runCatching { store.saveDraft(noteId, json) } }
        }
        ready.document.markSaved()
        messageDirty = false
    }

    /** Renders the composite, queues the reply in the outbox, and posts it. */
    private fun currentReady(): AgentNoteScreenState.Ready? = when (val state = mutableState.value) {
        is AgentNoteScreenState.Ready -> state
        is AgentNoteScreenState.Sent -> state.ready
        // Drawing goes on while a reply is on its way: its draft is kept like any other.
        is AgentNoteScreenState.Sending -> state.ready
        else -> null
    }

    /**
     * Sends the reply; [withPicture] first takes a picture with the glasses and sends it with the
     * reply. It runs in the background: the reply takes the drawing and the comment as they are now
     * (the comment field empties at once), and the note stays usable while the picture is taken and
     * the reply is sent. When no picture comes nothing is sent ([pictureFailure] says why) and the
     * comment comes back unless a new one was typed. Whether a send started.
     */
    fun send(withPicture: Boolean = false): Boolean {
        // One reply at a time: Send waits while the previous one is on its way.
        if (mutableState.value is AgentNoteScreenState.Sending) return false
        val ready = currentReady() ?: return false
        if (ready.readOnly) return false
        val picture = withPicture && !ready.note.isPhonePage
        mutableState.value = AgentNoteScreenState.Sending(ready, takingPicture = picture)
        val app = getApplication<Application>()
        val comment = message.trim().takeIf(String::isNotEmpty)
        val strokes = ready.document.toJson()
        val revision = ready.document.revision
        val strokeCount = ready.document.strokes.size
        val markerCount = ready.document.markers.size
        // The drawing as it is now: drawing on goes into the next reply, not this one.
        val drawing = runCatching { AnnotationDocument.fromJson(strokes) }.getOrNull()?.takeIf {
            it.imageWidth == ready.document.imageWidth && it.imageHeight == ready.document.imageHeight
        } ?: ready.document
        // The comment goes with this reply; the field is free for the next one at once.
        message = ""
        messageDirty = false
        /** The screen still shows this note (it may have moved on while the reply was on its way). */
        fun stillShown() = currentReady() === ready
        /**
         * The reply holds the drawing: its draft goes, unless more was drawn or typed while it was on
         * its way (that stays a draft, saved as usual).
         */
        fun settleDraft(store: AgentNoteStore) {
            if (ready.document.revision != revision || message.isNotBlank()) return
            store.clearDraft(ready.note.noteId)
            ready.document.markSaved()
        }
        /** Nothing was sent: the comment comes back unless a new one was typed meanwhile. */
        fun keepComment() {
            if (comment != null && message.isBlank()) {
                message = comment
                messageDirty = true
            }
        }
        AgentNotes.ioScope.launch {
            val photo: GlassesPhoto? = if (!picture) null else try {
                glassesCamera(app).takePhoto()
            } catch (error: GlassesCameraException) {
                Log.w(AgentNotes.TAG, "glasses picture failed: ${error.problem}")
                if (stillShown()) {
                    keepComment()
                    mutableState.value = ready
                    pictureFailure.value = error.problem
                }
                return@launch
            }
            if (photo != null && stillShown()) mutableState.value = AgentNoteScreenState.Sending(ready)
            val result = try {
                val composite = AnnotationCanvasView.renderComposite(ready.bitmap, drawing)
                val png = ByteArrayOutputStream().use { output ->
                    composite.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
                if (ready.note.isPhonePage) {
                    // A page from the reader: Send creates a note from this phone (each later Send a new one).
                    val first = ready.note.isUnsentPhonePage
                    val page = ready.note.copy(
                        noteId = if (first) ready.note.noteId else UUID.randomUUID().toString(),
                        message = comment, imageWidth = composite.width, imageHeight = composite.height,
                    )
                    composite.recycle()
                    val created = try {
                        AgentNotes.api(app).createNote(page, png, strokeCount)
                    } catch (error: IOException) {
                        Log.w(AgentNotes.TAG, "page note not sent: ${error.javaClass.simpleName}")
                        if (stillShown()) {
                            keepComment()
                            mutableState.value = ready
                            pageSendFailure.value = true
                        }
                        return@launch
                    }
                    val store = AgentNotes.store(app)
                    if (first) store.updateNote(created)
                    settleDraft(store)
                    Log.i(AgentNotes.TAG, "page note sent bytes=${png.size} strokes=$strokeCount")
                    if (stillShown()) mutableState.value = AgentNoteScreenState.Sent(ready, delivered = true)
                    return@launch
                }
                val local = LocalAgentNoteReply(
                    replyId = UUID.randomUUID().toString(),
                    noteId = ready.note.noteId,
                    createdAtMillis = System.currentTimeMillis(),
                    mediaType = "image/png",
                    imageWidth = composite.width,
                    imageHeight = composite.height,
                    strokeCount = strokeCount,
                    message = comment,
                    sent = false,
                    photoMediaType = photo?.mediaType,
                    photoCapture = photo?.capture,
                    // What an agent may use is chosen when its session starts (New session), not per reply.
                    sources = null,
                )
                composite.recycle()
                val store = AgentNotes.store(app)
                store.queueReply(local, png, strokes, photo?.bytes)
                settleDraft(store)
                Log.i(AgentNotes.TAG, "agent note reply queued bytes=${png.size} strokes=$strokeCount markers=$markerCount " +
                    "photo=${photo?.bytes?.size ?: 0}")
                val server = AgentNotes.sync(app).sendReply(local)
                if (server != null && (server.listening != AgentNoteListening.UNKNOWN || server.wake != null)) {
                    mutableCoverer.value = server.coverer
                    mutableListening.value = server.listening
                    // What the server said when it took the reply is the newest answer for this note.
                    AgentNoteListeningCache.shared.keepAfterReply(ready.note.noteId, server.listening, server.coverer, server.wake)
                        ?.let { mutableAnswer.value = it }
                }
                AgentNoteScreenState.Sent(ready, server != null, server?.listening ?: AgentNoteListening.UNKNOWN, comment,
                    server?.coverer, server?.wake)
            } catch (error: Exception) {
                // Rendering or saving failed (memory, disk): nothing was queued; stay on the note.
                Log.w(AgentNotes.TAG, "agent note reply could not be saved: ${error.javaClass.simpleName}")
                null
            }
            if (!stillShown()) return@launch
            if (result != null) {
                mutableState.value = result
            } else {
                keepComment()
                mutableState.value = ready
                sendFailure.value = true
            }
        }
        return true
    }

    /** Set when saving the reply itself failed (not a network failure). */
    val sendFailure = MutableStateFlow(false)
    /** Set when Send with picture got no picture from the glasses; nothing was sent. */
    val pictureFailure = MutableStateFlow<GlassesCameraProblem?>(null)

    private fun glassesCamera(app: Application) = GlassesCamera(
        baseUrl = { Preferences(PreferenceManager.getDefaultSharedPreferences(app)).getGlassesCameraUrl() },
        log = { Log.i(AgentNotes.TAG, it) },
    )
    /** Set when a page from the reader could not reach the server; the marks and comment are kept. */
    val pageSendFailure = MutableStateFlow(false)

    class Decoded(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

    companion object {
        /** The one drawing colour, black (0xRRGGBB); the screen has no colour choice. */
        const val INK = 0x000000
        const val MAX_DECODED_PIXELS = 12_000_000L

        fun decodeBounded(file: File): Decoded {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("The note image cannot be read")
            val options = BitmapFactory.Options().apply {
                inSampleSize = agentNoteSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODED_PIXELS)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.path, options)
                ?: throw IllegalStateException("The note image cannot be read")
            return Decoded(bitmap, bounds.outWidth, bounds.outHeight)
        }
    }
}

/**
 * An agent note: the agent's title and message, the image to draw on (pen, highlighter, eraser,
 * numbered markers, undo/redo), a comment typed in the field under it, and Send. With [EXTRA_REPLY_ID] it shows a past reply read-only.
 */
/**
 * Agent notes, one window for all of them (launchMode singleTask): a note that arrives, or is
 * opened from the list, replaces the one on screen, and the bar's arrows step through all notes in
 * the order they were sent, whichever chat sent them. Each note keeps its own view model, so its drawing and comment stay
 * when you step away and back. The window is one tab next to the reader's documents, and shows
 * their tab strip too, so switching between the note and a PDF is one tap either way.
 */
class AgentNoteActivity : AppCompatActivity(), AnnotationCanvasView.Listener, DocumentTabsHost {
    private lateinit var binding: ActivityAgentNoteBinding
    private lateinit var viewModel: AgentNoteViewModel
    private val noteId: String get() = intent.getStringExtra(EXTRA_NOTE_ID).orEmpty()
    private val replyId: String? get() = intent.getStringExtra(EXTRA_REPLY_ID)
    private var announcedSent: AgentNoteScreenState.Sent? = null
    private var boundReady: AgentNoteScreenState.Ready? = null
    /** The note's message shows one line (true) or all of it: the user's last choice, kept for every note. */
    private var messageCollapsed = Preferences.agentNoteMessageCollapsedDefault
    /** Whether the message on screen is longer than one line, so a tap on it changes something. */
    private var messageLong = false
    /** Set while the comment field is filled from the view model, so that is not a user edit. */
    private var fillingComment = false
    private var noteTitle: String? = null
    /** All notes, oldest first (the arrows' order), and this note's place in them. */
    private var conversation: List<AgentNote> = emptyList()
    private var position = -1
    /** Notes that arrived and were not opened yet, oldest first: the New note button. */
    private var waiting: List<AgentNote> = emptyList()
    private var statusText: String? = null
    private var stateJob: Job? = null
    /** The reader's tabs; this window is one of them, titled by the note on screen. */
    private var tabs: OpenDocumentTabsController? = null

    /** The note on screen, so a second arrival of it does not open it again. */
    val shownNoteId: String? get() = noteId.takeIf { replyId == null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15+ draws the app under the status and navigation bars: keep the title bar and tools clear of them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The keyboard pushes the comment field and tools up (the note above shrinks) instead of covering them.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setSupportActionBar(binding.agentNoteToolbar)
        // Home on the left, the note's title, and the arrows to the previous and next note sent on the right.
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_home)
        if (noteId.isBlank()) {
            finish()
            return
        }
        tabs = OpenDocumentTabsController(this, this, binding.agentNoteTabs) {
            // While a note loads, its tab reads "Agent note" rather than the note's id.
            CurrentDocumentSnapshot(uri = intent.data?.toString(), title = noteTitle ?: getString(R.string.agent_note))
        }
        binding.agentNoteCanvas.listener = this
        if (savedInstanceState == null && !intent.hasExtra(EXTRA_AGENT_NOTE_PLACE)) ReaderNoteHistory.forget()
        binding.agentNoteMessageRow.setOnClickListener {
            if (!messageLong) return@setOnClickListener
            messageCollapsed = !messageCollapsed
            preferences().setAgentNoteMessageCollapsed(messageCollapsed)
            applyMessageLook()
        }
        // A new width (rotation, split screen) can make the message fit on one line, or no longer fit.
        binding.agentNoteMessage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMessageToggle() }
        setUpTools()
        showNote()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentNoteInbox.waiting.collect { notes ->
                    waiting = notes.filter { it.noteId != noteId }
                    // A note that arrives is one arrow away as soon as it is here.
                    boundReady?.let { loadConversation(it.note) }
                    invalidateOptionsMenu()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_NOTE_ID).isNullOrBlank()) return
        switchTo(intent)
    }

    /** Puts the note of [next] on screen in place of the current one, whose draft is kept. */
    private fun switchTo(next: Intent) {
        // Anything but a reader's Back or Forward is a new way here: no arrow leads back into the reader.
        if (!next.hasExtra(EXTRA_AGENT_NOTE_PLACE)) ReaderNoteHistory.forget()
        if (next.getStringExtra(EXTRA_NOTE_ID) == noteId && next.getStringExtra(EXTRA_REPLY_ID) == replyId) {
            // The note is on screen as it was left; only the arrows change.
            invalidateOptionsMenu()
            return
        }
        hideKeyboard()
        viewModel.saveDraft()
        setIntent(next)
        showNote()
    }

    /** Shows the note the intent names, with its own view model (keyed by note and reply). */
    private fun showNote() {
        viewModel = ViewModelProvider(this)["$noteId/${replyId.orEmpty()}", AgentNoteViewModel::class.java]
        // A page from the reader starts with the tool picked there and the comment typed there.
        intent.getStringExtra(EXTRA_START_TOOL)?.let { name ->
            viewModel.startWith(AnnotationCanvasView.Mode.entries.firstOrNull { it.name == name }, intent.getStringExtra(EXTRA_START_MESSAGE))
            intent.removeExtra(EXTRA_START_TOOL)
            intent.removeExtra(EXTRA_START_MESSAGE)
        }
        announcedSent = null
        boundReady = null
        noteTitle = null
        statusText = null
        messageCollapsed = preferences().getAgentNoteMessageCollapsed()
        applyMessageLook()
        binding.agentNoteTools.visibility = View.GONE
        supportActionBar?.title = getString(R.string.agent_note)
        updateSubtitle()
        AgentNoteNotifier.cancel(this, noteId)
        AgentNoteInbox.opened(noteId)
        AgentNoteUnseen.opened(this, noteId)
        if (replyId == null) AgentStudyListenerService.start(this)
        viewModel.load(noteId, replyId)
        stateJob?.cancel()
        val shown = viewModel
        stateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { shown.state.collect(::render) }
                launch { shown.answer.collect { updateAnswerLine() } }
                launch { shown.canChangeAnswerer.collect { updateAnswerLine() } }
                launch { shown.serverReachable.collect { updateAnswerLine() } }
                launch {
                    // An agent's note (not a page, not a past reply): ask once each time its screen
                    // comes up, unless the note was asked about in the last minute, and then every
                    // minute while it stays on screen, so the line under the title follows an agent
                    // that starts or stops listening. Leaving the screen ends it (repeatOnLifecycle).
                    val loaded = when (val first = shown.state.first { it !is AgentNoteScreenState.Loading }) {
                        is AgentNoteScreenState.Ready -> first
                        is AgentNoteScreenState.Sending -> first.ready
                        is AgentNoteScreenState.Sent -> first.ready
                        is AgentNoteScreenState.Failed, AgentNoteScreenState.Loading -> null
                    } ?: return@launch
                    if (loaded.readOnly || loaded.note.isPhonePage) return@launch
                    shown.followListening(loaded.note.noteId)
                }
                launch {
                    shown.pageSendFailure.collect { failed ->
                        if (failed) {
                            shown.pageSendFailure.value = false
                            fillComment(shown.message)
                            AppSnackbar.make(binding.root, R.string.agent_note_page_send_failed, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
                launch {
                    shown.pictureFailure.collect { problem ->
                        if (problem != null) {
                            shown.pictureFailure.value = null
                            fillComment(shown.message)
                            AppSnackbar.make(binding.root, agentNotePictureProblemText(problem), Snackbar.LENGTH_LONG)
                                .setAction(R.string.agent_note_send_without_picture) {
                                    if (shown.send(withPicture = false)) fillComment("")
                                }
                                .show()
                        }
                    }
                }
                launch {
                    shown.sendFailure.collect { failed ->
                        if (failed) {
                            shown.sendFailure.value = false
                            fillComment(shown.message)
                            AppSnackbar.make(binding.root, R.string.agent_note_send_failed, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    /** Finds the notes for the arrows: all notes on the phone, in the order they were sent. */
    private fun loadConversation(note: AgentNote) {
        val shownId = noteId
        lifecycleScope.launch {
            val notes = withContext(Dispatchers.IO) { arrowNotes(AgentNotes.store(applicationContext).notes(), note) }
            if (shownId != noteId) return@launch
            conversation = notes
            position = notes.indexOfFirst { it.noteId == note.noteId }
            updateSubtitle()
            invalidateOptionsMenu()
        }
    }

    /** "41 / 57 · Sent ✓ · waiting for the agent": the place among all notes in sending order, then the note's state. */
    private fun updateSubtitle() {
        val place = if (conversation.size > 1 && position >= 0) getString(R.string.agent_note_position, position + 1, conversation.size) else null
        supportActionBar?.subtitle = listOfNotNull(place, statusText).joinToString(" · ").ifBlank { null }
    }

    /** The oldest note that waits: several are read in the order they came. */
    private fun openWaitingNote() {
        val next = waiting.firstOrNull() ?: return
        switchTo(intent(this, next.noteId))
    }

    private fun step(by: Int) {
        val target = conversation.getOrNull(position + by) ?: return
        switchTo(intent(this, target.noteId))
    }

    private fun setUpTools() {
        binding.agentNoteCommentField.doAfterTextChanged { text ->
            if (fillingComment) return@doAfterTextChanged
            viewModel.message = text?.toString().orEmpty()
            viewModel.messageDirty = true
        }
        binding.agentNoteToolGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.mode = when (checkedId) {
                R.id.agentNoteScroll -> AnnotationCanvasView.Mode.SCROLL
                R.id.agentNoteHighlighter -> AnnotationCanvasView.Mode.HIGHLIGHTER
                R.id.agentNoteEraser -> AnnotationCanvasView.Mode.ERASER
                R.id.agentNoteNumber -> AnnotationCanvasView.Mode.NUMBER
                else -> AnnotationCanvasView.Mode.PEN
            }
            applyTool()
        }
        binding.agentNoteUndo.setOnClickListener {
            boundReady?.document?.undo()
            binding.agentNoteCanvas.documentChanged()
            updateUndoRedo()
        }
        binding.agentNoteRedo.setOnClickListener {
            boundReady?.document?.redo()
            binding.agentNoteCanvas.documentChanged()
            updateUndoRedo()
        }
        binding.agentNoteSend.setOnClickListener {
            hideKeyboard()
            if (viewModel.send()) fillComment("")
        }
        binding.agentNoteSendWithPicture.setOnClickListener {
            hideKeyboard()
            if (viewModel.send(withPicture = true)) fillComment("")
        }
        binding.agentNoteOpenConversation.setOnClickListener {
            boundReady?.note?.let { openConversation(it, viewModel.message, replied = false) }
        }
        binding.agentNoteAnswerChange.setOnClickListener { showAnswerChoice() }
        binding.agentNoteAnsweredByRow.setOnClickListener { showAnswerChoice() }
        // The chip only shows the row's state (Chip makes itself focusable: the row is the one stop), but its
        // arrow takes its own touches: it opens the choice too.
        binding.agentNoteAnsweredBy.isFocusable = false
        binding.agentNoteAnsweredBy.setOnCloseIconClickListener {
            if (binding.agentNoteAnsweredByRow.isEnabled) binding.agentNoteAnsweredByRow.performClick()
        }
        // A screen reader hears the row as the button it is, with its description (what it does, who answers now).
        ViewCompat.setAccessibilityDelegate(binding.agentNoteAnsweredByRow, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
    }

    private fun applyTool() {
        binding.agentNoteCanvas.mode = viewModel.mode
        binding.agentNoteCanvas.color = viewModel.color
        val checked = when (viewModel.mode) {
            AnnotationCanvasView.Mode.SCROLL -> R.id.agentNoteScroll
            AnnotationCanvasView.Mode.PEN -> R.id.agentNotePen
            AnnotationCanvasView.Mode.HIGHLIGHTER -> R.id.agentNoteHighlighter
            AnnotationCanvasView.Mode.ERASER -> R.id.agentNoteEraser
            AnnotationCanvasView.Mode.NUMBER -> R.id.agentNoteNumber
        }
        if (binding.agentNoteToolGroup.checkedButtonId != checked) binding.agentNoteToolGroup.check(checked)
    }

    private fun render(state: AgentNoteScreenState) {
        when (state) {
            AgentNoteScreenState.Loading -> {
                binding.agentNoteProgress.visibility = View.VISIBLE
                binding.agentNoteStatus.visibility = View.GONE
            }
            is AgentNoteScreenState.Failed -> {
                binding.agentNoteProgress.visibility = View.GONE
                binding.agentNoteStatus.visibility = View.VISIBLE
                binding.agentNoteStatus.text = state.message
            }
            is AgentNoteScreenState.Ready -> {
                bind(state, sending = false)
                showSending(null)
            }
            is AgentNoteScreenState.Sending -> {
                bind(state.ready, sending = true)
                showSending(state)
            }
            is AgentNoteScreenState.Sent -> {
                // The note stays open with the drawing until the agent answers (a new note opens on top).
                bind(state.ready, sending = false)
                statusText = agentNoteSentText(state.delivered, state.listening, state.coverer, ::getString, state.wake)
                updateSubtitle()
                if (announcedSent !== state) {
                    announcedSent = state
                    // Sending cleared the comment (anything typed since rotation is kept in the view model).
                    fillComment(viewModel.message)
                    AgentStudyListenerService.start(this)
                    if (state.delivered && state.listening == AgentNoteListening.NOT_LISTENING && state.wake == null &&
                        !state.ready.note.isPhonePage
                    ) {
                        // Stored, but no agent will act on it (none listens, no waker wakes one): say so, and offer the
                        // conversation that sent the note.
                        val snackbar = AppSnackbar.make(binding.root, R.string.agent_note_sent_nobody_listening, Snackbar.LENGTH_LONG)
                        if (state.ready.note.hasConversation) {
                            snackbar.setAction(R.string.proposal_open_conversation) {
                                openConversation(state.ready.note, state.comment, replied = true)
                            }
                        }
                        snackbar.show()
                    } else {
                        Toast.makeText(
                            applicationContext,
                            if (state.delivered) R.string.agent_note_sent else R.string.agent_note_queued,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    private fun bind(ready: AgentNoteScreenState.Ready, sending: Boolean) {
        binding.agentNoteProgress.visibility = View.GONE
        binding.agentNoteStatus.visibility = View.GONE
        if (boundReady !== ready) {
            boundReady = ready
            invalidateOptionsMenu()
            val title = ready.note.title.ifBlank { getString(R.string.agent_note) }
            if (title != noteTitle) {
                noteTitle = title
                supportActionBar?.title = title
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(title))
                tabs?.refresh(isFullscreen = false)
            }
            statusText = if (!ready.readOnly) null else listOfNotNull(
                getString(R.string.agent_note_your_reply),
                agentNoteReplySourcesNote(ready.replySources)?.let(::getString),
            ).joinToString(" · ")
            updateSubtitle()
            loadConversation(ready.note)
            val text = if (ready.readOnly) ready.replyMessage else ready.note.message
            binding.agentNoteMessage.text = text
            binding.agentNoteMessageRow.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.agentNoteMessage.post { updateMessageToggle() }
            binding.agentNoteCanvas.readOnly = ready.readOnly
            binding.agentNoteCanvas.setContent(ready.bitmap, ready.document)
            // Shown again by a reader's Back: zoomed and scrolled as when its link was followed.
            AgentNotePlace.fromJson(intent.getStringExtra(EXTRA_AGENT_NOTE_PLACE))
                ?.takeIf { it.noteId == ready.note.noteId && it.replyId == replyId }?.let { place ->
                    place.zoom?.let { binding.agentNoteCanvas.viewport = AnnotationCanvasView.Viewport(it, place.left, place.top) }
                }
            intent.removeExtra(EXTRA_AGENT_NOTE_PLACE)
            // Links are fractions of the picture, so they hold at any decoded size; a reply is drawn on the
            // note's picture, so they hold there too while its shape is the note's.
            binding.agentNoteCanvas.links = ready.note.links.takeIf {
                ready.note.imageWidth <= 0 || ready.note.imageHeight <= 0 || ready.bitmap.width <= 0 ||
                    kotlin.math.abs(ready.bitmap.height.toFloat() / ready.bitmap.width -
                        ready.note.imageHeight.toFloat() / ready.note.imageWidth) < 0.01f
            }.orEmpty()
            binding.agentNoteTools.visibility = if (ready.readOnly) View.GONE else View.VISIBLE
            // A page from the reader is the picture already; an agent's note can get one from the glasses.
            binding.agentNoteSendWithPicture.visibility = if (ready.note.isPhonePage) View.GONE else View.VISIBLE
            fillComment(viewModel.message)
            applyTool()
            updateAnswerLine()
        }
        // A reply on its way runs in the background: the note stays usable, only Send waits for it.
        binding.agentNoteSend.isEnabled = !sending
        binding.agentNoteSendWithPicture.isEnabled = !sending
        updateUndoRedo()
    }

    private fun preferences() = Preferences(PreferenceManager.getDefaultSharedPreferences(this))

    /** One line or the whole message, as [messageCollapsed] says; the picture below gets the room either way. */
    private fun applyMessageLook() {
        binding.agentNoteMessage.maxLines = agentNoteMessageLook(messageCollapsed).maxLines
        // The text is laid out again with the new line limit before its length can be judged.
        binding.agentNoteMessage.post { updateMessageToggle() }
    }

    /** The chevron and the tap only for a message longer than one line; a short one just reads. */
    private fun updateMessageToggle() {
        val message = binding.agentNoteMessage
        val layout = message.layout ?: return
        val lines = layout.lineCount
        messageLong = lines > 0 && agentNoteMessageIsLong(messageCollapsed, lines, layout.getEllipsisCount(lines - 1))
        val look = agentNoteMessageLook(messageCollapsed)
        binding.agentNoteMessageToggle.setImageResource(look.chevron)
        binding.agentNoteMessageToggle.visibility = if (messageLong) View.VISIBLE else View.INVISIBLE
        val row = binding.agentNoteMessageRow
        row.isClickable = messageLong
        ViewCompat.setStateDescription(row, if (messageLong) getString(look.state) else null)
        if (messageLong) {
            ViewCompat.replaceAccessibilityAction(row, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, getString(look.action), null)
        }
    }

    /**
     * The reply on its way, in the subtitle: "Taking a picture with the glasses…" while Send with
     * picture waits for it, then "Sending…"; [sending] null takes either away.
     */
    private fun showSending(sending: AgentNoteScreenState.Sending?) {
        val texts = listOf(getString(R.string.agent_note_taking_picture), getString(R.string.agent_note_sending))
        val text = when {
            sending == null -> statusText.takeUnless { it in texts }
            sending.takingPicture -> texts[0]
            else -> texts[1]
        }
        if (text == statusText) return
        statusText = text
        updateSubtitle()
    }

    /**
     * The line under the title of an agent's note (not a page, not a past reply): whether an agent
     * listens for a reply, which one, and "Change" ([agentNoteAnswerLine]). No line while the
     * server has not said, or cannot tell.
     */
    private fun updateAnswerLine() {
        updateAnsweredByChip()
        val ready = boundReady
        val timeFormat = android.text.format.DateFormat.getTimeFormat(this)
        val line = if (ready == null || ready.readOnly || ready.note.isPhonePage) null else agentNoteAnswerLine(
            viewModel.answer.value, viewModel.canChangeAnswerer.value, viewModel.serverReachable.value, ::getString,
        ) { timeFormat.format(java.util.Date(it)) }
        // Only a line that needs attention shows (nobody listens, its machine is offline); the usual
        // case takes no room, and who answers is the chip over the comment.
        binding.agentNoteAnswerLine.visibility = if (line == null || line.usual) View.GONE else View.VISIBLE
        if (line == null || ready == null) return
        val foreground = MaterialColors.getColor(
            binding.agentNoteAnswerLine,
            if (line.warning) com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnSurface,
        )
        binding.agentNoteAnswerLine.setBackgroundColor(
            if (line.warning) MaterialColors.getColor(binding.agentNoteAnswerLine, com.google.android.material.R.attr.colorErrorContainer)
            else android.graphics.Color.TRANSPARENT,
        )
        val muted = MaterialColors.getColor(binding.agentNoteAnswerLine, com.google.android.material.R.attr.colorOnSurfaceVariant)
        binding.agentNoteAnswerText.text = line.text
        binding.agentNoteAnswerText.setTextColor(if (line.muted) muted else foreground)
        binding.agentNoteAnswerMark.setImageResource(
            when (line.mark) {
                AgentNoteAnswerMark.LISTENING, AgentNoteAnswerMark.WOKEN -> R.drawable.ic_overview_filter_check
                AgentNoteAnswerMark.OFFLINE -> R.drawable.ic_schedule_small
                AgentNoteAnswerMark.WAITING, AgentNoteAnswerMark.NOBODY -> R.drawable.ic_warning
            },
        )
        binding.agentNoteAnswerMark.imageTintList = android.content.res.ColorStateList.valueOf(
            when (line.mark) {
                AgentNoteAnswerMark.LISTENING, AgentNoteAnswerMark.WOKEN ->
                    MaterialColors.getColor(binding.agentNoteAnswerLine, androidx.appcompat.R.attr.colorPrimary)
                AgentNoteAnswerMark.OFFLINE, AgentNoteAnswerMark.WAITING -> muted
                AgentNoteAnswerMark.NOBODY -> foreground
            },
        )
        binding.agentNoteAnswerMark.contentDescription = getString(
            when (line.mark) {
                AgentNoteAnswerMark.LISTENING -> R.string.agent_note_answer_mark_listening
                AgentNoteAnswerMark.WOKEN -> R.string.agent_note_answer_mark_woken
                AgentNoteAnswerMark.OFFLINE -> R.string.agent_note_answer_mark_offline
                AgentNoteAnswerMark.WAITING, AgentNoteAnswerMark.NOBODY -> R.string.agent_note_answer_mark_not_listening
            },
        )
        // While nobody listens, the conversation that sent the note is one tap away, as on the old bar.
        binding.agentNoteOpenConversation.visibility = if (line.warning && ready.note.hasConversation) View.VISIBLE else View.GONE
        binding.agentNoteOpenConversation.setTextColor(foreground)
        binding.agentNoteAnswerChange.visibility = if (line.changeOffered) View.VISIBLE else View.GONE
        binding.agentNoteAnswerChange.isEnabled = line.changeEnabled
        binding.agentNoteAnswerChange.setTextColor(if (line.changeEnabled) foreground else androidx.core.graphics.ColorUtils.setAlphaComponent(foreground, 97))
        // Offline the choice cannot be sent: the button says why it is off.
        binding.agentNoteAnswerChange.contentDescription = getString(
            if (line.changeEnabled) R.string.agent_note_answer_change else R.string.agent_note_answer_change_needs_server,
        )
    }

    /**
     * "Change": Automatic and the agents that could answer now, the current choice checked. A
     * choice goes to the server; the line follows its answer, and on failure a Snackbar says so and
     * the choice stays as it was.
     */
    private fun showAnswerChoice() {
        val ready = boundReady ?: return
        val answer = viewModel.answer.value ?: return
        val choices = agentNoteAnswerChoices(answer, ::getString)
        val entries = choices.map { choice ->
            if (choice.detail == null) choice.title else android.text.SpannableStringBuilder(choice.title).apply {
                append("\n")
                val start = length
                append(choice.detail)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val shown = viewModel
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_note_answer_choice_title)
            .setSingleChoiceItems(entries.toTypedArray<CharSequence>(), choices.indexOfFirst { it.checked }) { dialog, index ->
                dialog.dismiss()
                val choice = choices[index]
                if (choice.checked) return@setSingleChoiceItems
                lifecycleScope.launch {
                    val failure = shown.chooseAnswerer(ready.note.noteId, choice.agent) ?: return@launch
                    if (isFinishing || isDestroyed) return@launch
                    AppSnackbar.make(binding.root, agentNoteAnswerChangeFailure(failure), Snackbar.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The chip over the comment: "Answered by: …" ([agentNoteAnsweredByChipText]). It opens the choice of
     * who answers when the server lets the user choose and was reached; otherwise it only says who.
     */
    private fun updateAnsweredByChip() {
        val ready = boundReady
        val text = if (ready == null || ready.readOnly || ready.note.isPhonePage) null
            else agentNoteAnsweredByChipText(viewModel.answer.value) { id, args -> getString(id, *args) }
        binding.agentNoteAnsweredByRow.visibility = if (text == null) View.GONE else View.VISIBLE
        text ?: return
        val canChoose = viewModel.canChangeAnswerer.value && viewModel.serverReachable.value && viewModel.answer.value != null
        binding.agentNoteAnsweredBy.text = text
        binding.agentNoteAnsweredBy.isCloseIconVisible = canChoose
        binding.agentNoteAnsweredByRow.isEnabled = canChoose
        binding.agentNoteAnsweredByRow.contentDescription =
            if (canChoose) getString(R.string.agent_note_answered_by_chip_description, text) else text
    }

    /**
     * Opens the conversation that sent [note], with the comment on the clipboard (and which note it
     * answers), so pasting it there wakes that conversation with the user's words.
     */
    private fun openConversation(note: AgentNote, comment: String?, replied: Boolean) {
        val text = agentNoteReplyForConversation(note.title, note.noteId, comment, replied)
        if (text != null) {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.agent_note), text))
            Toast.makeText(applicationContext, R.string.agent_note_reply_copied, Toast.LENGTH_SHORT).show()
        }
        AgentConversationLinks.openOrExplain(this, note)
    }

    private fun updateUndoRedo() {
        val document = boundReady?.document
        binding.agentNoteUndo.isEnabled = document?.canUndo == true
        binding.agentNoteUndo.alpha = if (document?.canUndo == true) 1f else 0.38f
        binding.agentNoteRedo.isEnabled = document?.canRedo == true
        binding.agentNoteRedo.alpha = if (document?.canRedo == true) 1f else 0.38f
    }

    override fun onDocumentChanged() = updateUndoRedo()

    /**
     * A tapped link of the note: a Library PDF page opens in the reader, a web page in the browser.
     * The reader keeps this note in its history, so its Back arrow comes back here.
     */
    override fun onLinkTapped(link: AgentNoteLink) {
        val remote = parseRemotePdfLink(link.href)
        if (remote != null) {
            val file = installedRemotePdf(ThoughtsLocalStore.currentPdfs(), remote.filename)
            if (file == null) {
                AppSnackbar.make(binding.root, R.string.remote_pdf_link_unavailable, Snackbar.LENGTH_LONG).show()
                return
            }
            // Following a link starts a new way on from this note.
            ReaderNoteHistory.forget()
            invalidateOptionsMenu()
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.fromFile(file)
                putExtra(EXTRA_REMOTE_PDF_LINK, link.href)
                putExtra(EXTRA_AGENT_NOTE_PLACE, placeOnScreen().toJson())
            })
            return
        }
        if (!link.href.startsWith("https://", ignoreCase = true) && !link.href.startsWith("http://", ignoreCase = true)) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.href)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            AppSnackbar.make(binding.root, R.string.agent_note_link_no_app, Snackbar.LENGTH_LONG).show()
        }
    }

    /** This note as a place of the reader's history: which note, and how its picture is zoomed and scrolled. */
    private fun placeOnScreen(): AgentNotePlace {
        val viewport = binding.agentNoteCanvas.viewport
        return AgentNotePlace(noteId, replyId, noteTitle, viewport?.zoom, viewport?.left ?: 0f, viewport?.top ?: 0f)
    }

    /**
     * Back or Forward on a note that a reader tab's arrow showed: that tab comes to the front and
     * takes the step from the note's place in its history.
     */
    private fun stepReaderHistory(back: Boolean) {
        val shown = ReaderNoteHistory.shownFor(noteId, replyId) ?: return
        val task = runCatching {
            getSystemService(ActivityManager::class.java).appTasks.firstOrNull { appTaskId(it) == shown.readerTaskId }
        }.getOrNull()
        if (task != null) {
            hideKeyboard()
            ReaderNoteHistory.request(shown.readerTaskId, back)
            try {
                task.moveToFront()
                return
            } catch (error: RuntimeException) {
                Log.w(AgentNotes.TAG, "reader tab not shown: ${error.javaClass.simpleName}")
                ReaderNoteHistory.returned(shown.readerTaskId)
            }
        }
        // The tab was closed: its history went with it.
        ReaderNoteHistory.forget()
        invalidateOptionsMenu()
    }

    @Suppress("DEPRECATION")
    private fun appTaskId(task: ActivityManager.AppTask): Int? = runCatching {
        val info = task.taskInfo
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) info.taskId else info.id
    }.getOrNull()

    override fun onLimitReached() {
        AppSnackbar.make(binding.root, R.string.agent_note_too_many_strokes, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // A note that just arrived waits here instead of replacing the one being read.
        if (waiting.isNotEmpty()) {
            val label = if (waiting.size == 1) getString(R.string.agent_note_new) else getString(R.string.agent_notes_new, waiting.size)
            menu.add(Menu.NONE, MENU_NEW_NOTE, Menu.NONE, label).apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                actionView = layoutInflater.inflate(R.layout.view_agent_note_new, binding.agentNoteToolbar, false).also { button ->
                    (button as TextView).text = label
                    button.setOnClickListener { openWaitingNote() }
                }
            }
        }
        // Back and Forward of the reader tab whose arrow showed this note, as in the reader: straight arrows.
        ReaderNoteHistory.shownFor(noteId, replyId)?.let { shown ->
            if (shown.canGoBack) {
                menu.add(Menu.NONE, MENU_HISTORY_BACK, Menu.NONE, R.string.nav_back).apply {
                    setIcon(R.drawable.ic_nav_back)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
            if (shown.canGoForward) {
                menu.add(Menu.NONE, MENU_HISTORY_FORWARD, Menu.NONE, R.string.nav_forward).apply {
                    setIcon(R.drawable.ic_nav_forward)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
        }
        // The arrows: the previous and next note of the same conversation, shown once it has two.
        if (conversation.size > 1 && position >= 0) {
            menu.add(Menu.NONE, MENU_PREVIOUS, Menu.NONE, R.string.agent_note_previous).apply {
                setIcon(R.drawable.ic_chevron_left)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                isEnabled = position > 0
                icon?.mutate()?.alpha = if (isEnabled) 255 else 97
            }
            menu.add(Menu.NONE, MENU_NEXT, Menu.NONE, R.string.agent_note_next).apply {
                setIcon(R.drawable.ic_chevron_right)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                isEnabled = position < conversation.lastIndex
                icon?.mutate()?.alpha = if (isEnabled) 255 else 97
            }
        }
        // The conversation that sent the note: study conversations are found here, not under a Project.
        if (boundReady?.note?.hasConversation == true) {
            menu.add(Menu.NONE, MENU_OPEN_CONVERSATION, Menu.NONE, R.string.proposal_open_conversation)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return menu.size() > 0
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            startActivity(Intent(this, HomeActivity::class.java))
            true
        }
        MENU_HISTORY_BACK -> {
            stepReaderHistory(back = true)
            true
        }
        MENU_HISTORY_FORWARD -> {
            stepReaderHistory(back = false)
            true
        }
        MENU_PREVIOUS -> {
            step(-1)
            true
        }
        MENU_NEXT -> {
            step(1)
            true
        }
        MENU_OPEN_CONVERSATION -> {
            boundReady?.note?.let { AgentConversationLinks.openOrExplain(this, it) }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun fillComment(text: String) {
        val field = binding.agentNoteCommentField
        if (field.text?.toString() == text) return
        fillingComment = true
        field.setText(text)
        field.setSelection(field.length())
        fillingComment = false
    }

    private fun hideKeyboard() {
        val field = binding.agentNoteCommentField
        if (!field.hasFocus()) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }

    override fun onResume() {
        super.onResume()
        tabs?.refresh(isFullscreen = false)
        // The reader may have taken its step back since: the arrows follow.
        invalidateOptionsMenu()
    }

    override fun onPause() {
        super.onPause()
        if (::viewModel.isInitialized) viewModel.saveDraft()
    }

    override fun onDestroy() {
        tabs?.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NOTE_ID = "agent_note_id"
        const val EXTRA_REPLY_ID = "agent_note_reply_id"
        private const val EXTRA_START_TOOL = "agent_note_start_tool"
        private const val EXTRA_START_MESSAGE = "agent_note_start_message"
        private const val MENU_OPEN_CONVERSATION = 1
        private const val MENU_PREVIOUS = 2
        private const val MENU_NEXT = 3
        private const val MENU_NEW_NOTE = 4
        private const val MENU_HISTORY_BACK = 5
        private const val MENU_HISTORY_FORWARD = 6
        // Every note opens in the one notes window (launchMode singleTask); the data tells notes and replies apart.
        fun intent(context: Context, noteId: String): Intent =
            Intent(context, AgentNoteActivity::class.java)
                .setData(tabUri(noteId))
                .putExtra(EXTRA_NOTE_ID, noteId)

        /** A page from the reader (a note from this phone, not sent yet), opened with [tool] and the [message] typed so far. */
        fun pageIntent(context: Context, noteId: String, tool: AnnotationCanvasView.Mode, message: String?): Intent =
            intent(context, noteId)
                .putExtra(EXTRA_START_TOOL, tool.name)
                .putExtra(EXTRA_START_MESSAGE, message)

        /** The note (or reply) of [place], shown by a reader's Back or Forward as it was when its link was followed. */
        fun historyIntent(context: Context, place: AgentNotePlace): Intent =
            (place.replyId?.let { replyIntent(context, place.noteId, it) } ?: intent(context, place.noteId))
                .putExtra(EXTRA_AGENT_NOTE_PLACE, place.toJson())

        fun replyIntent(context: Context, noteId: String, replyId: String): Intent =
            Intent(context, AgentNoteActivity::class.java)
                .setData(tabUri(noteId).buildUpon().appendPath("reply").appendPath(replyId).build())
                .putExtra(EXTRA_NOTE_ID, noteId)
                .putExtra(EXTRA_REPLY_ID, replyId)

        private fun tabUri(noteId: String): Uri =
            Uri.Builder().scheme(AGENT_NOTE_TAB_SCHEME).authority("note").appendPath(noteId).build()
    }
}
