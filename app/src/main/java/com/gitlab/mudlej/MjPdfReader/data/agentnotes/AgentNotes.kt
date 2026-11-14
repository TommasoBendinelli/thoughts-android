// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNoteActivity
import com.google.android.material.snackbar.Snackbar
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Entry points shared by the live loop, the worker and the screens. */
object AgentNotes {
    const val TAG = "ThoughtsAgentNotes"

    /** Replaces the server client in instrumented tests. */
    @VisibleForTesting
    @Volatile
    var apiOverride: AgentNotesApi? = null

    /** Background work that must outlive a screen (draft saves, receipts). */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun store(context: Context): AgentNoteStore = AgentNoteStore.under(context.applicationContext.filesDir)

    fun api(context: Context): AgentNotesApi = apiOverride ?: client(context)

    fun client(context: Context): AgentNotesClient {
        val secrets = ThoughtsServerSecretStore(context.applicationContext)
        return AgentNotesClient({ secrets.config() }, log = { Log.i(TAG, it) })
    }

    fun sync(context: Context, api: AgentNotesApi = api(context)) =
        AgentNoteSync(api, store(context), log = { Log.i(TAG, it) })
}

/** "Agent note: <title>" notifications (high importance): how a new note is announced outside the note screen. */
object AgentNoteNotifier {
    private const val CHANNEL = "agent_notes"
    private const val NOTIFICATION_TAG = "agent-note"

    /** Whether the notification could be posted. */
    fun notify(context: Context, note: AgentNote): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED)
        ) {
            Log.i(AgentNotes.TAG, "agent note notification skipped: notifications are off")
            return false
        }
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            note.noteId.hashCode(),
            AgentNoteActivity.intent(context, note.noteId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = note.message?.takeIf(String::isNotBlank) ?: note.reviewQuestion?.takeIf(String::isNotBlank)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_agent_note)
            .setContentTitle(context.getString(R.string.agent_note_notification_title, note.title))
            .apply { text?.let { setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        return runCatching { manager.notify(NOTIFICATION_TAG, note.noteId.hashCode(), notification) }
            .onFailure { Log.w(AgentNotes.TAG, "agent note notification failed: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    fun cancel(context: Context, noteId: String) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, noteId.hashCode())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.agent_notes), NotificationManager.IMPORTANCE_HIGH),
        )
    }
}

/**
 * The live channel's single owner. Tracks whether a Thoughts activity is started (foreground) or
 * resumed, and runs [AgentNoteInboxLoop] on Dispatchers.IO while an activity is started or the
 * study listener service runs, and only while [AgentNotesReceiving] is on. New notes never open by
 * themselves: they wait in [waiting] for the note screen's New note button, and notify elsewhere.
 * The user's own pages sent from another device are not announced at all ([agentNoteArrival]).
 */
object AgentNoteInbox : Application.ActivityLifecycleCallbacks {
    private val waitingNotes = MutableStateFlow<List<AgentNote>>(emptyList())

    /** Notes that arrived and were not opened yet, oldest first. */
    val waiting: StateFlow<List<AgentNote>> = waitingNotes.asStateFlow()

    fun opened(noteId: String) {
        waitingNotes.update { notes -> notes.filterNot { it.noteId == noteId } }
    }

    private const val STOP_DELAY_MILLIS = 3_000L

    private var application: Application? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var startedActivities = 0
    @Volatile private var listening = false
    @Volatile private var resumed: WeakReference<Activity>? = null
    @Volatile private var studyContext: AgentStudyContext? = null
    private var job: Job? = null
    private var client: AgentNotesClient? = null
    private val stopRunnable = Runnable { stopNow() }

    /** Registers the lifecycle callbacks; cheap, nothing runs until an activity starts. */
    fun install(application: Application) {
        this.application = application
        application.registerActivityLifecycleCallbacks(this)
    }

    val running: Boolean get() = job?.isActive == true

    private fun shouldRun(): Boolean {
        val app = application ?: return false
        return agentNotesInboxShouldRun(AgentNotesReceiving.isOn(app), startedActivities, listening)
    }

    /** [AgentNotesReceiving] changed: off stops the loop now (aborting the open request), on starts it. */
    fun receivingChanged() {
        main.post { if (shouldRun()) update() else stopNow() }
    }

    /** The review card shown in the reader, reported with each inbox request. */
    fun setStudyContext(reviewCardId: String?, documentId: String?) {
        studyContext = if (reviewCardId == null && documentId == null) null else AgentStudyContext(reviewCardId, documentId)
    }

    fun clearStudyContext(reviewCardId: String) {
        if (studyContext?.reviewCardId == reviewCardId) studyContext = null
    }

    /** Called by the study listener service as it starts and stops. */
    fun setListening(value: Boolean) {
        listening = value
        main.post(::update)
    }

    private fun update() {
        if (shouldRun()) {
            main.removeCallbacks(stopRunnable)
            if (job?.isActive != true) start()
        } else if (job?.isActive == true) {
            // A rotation stops the activity before its replacement starts: do not drop the poll for that.
            main.removeCallbacks(stopRunnable)
            main.postDelayed(stopRunnable, STOP_DELAY_MILLIS)
        }
    }

    private fun start() {
        val app = application ?: return
        val apiClient = AgentNotes.apiOverride ?: AgentNotes.client(app).also { client = it }
        val loop = AgentNoteInboxLoop(
            AgentNotes.sync(app, apiClient),
            shouldRun = ::shouldRun,
            context = { studyContext },
            onNewNotes = { notes -> main.post { deliver(app, notes) } },
            log = { Log.i(AgentNotes.TAG, it) },
        )
        Log.i(AgentNotes.TAG, "agent-notes live loop start")
        job = scope.launch {
            try {
                // Cold start: the first request waits until Home has loaded its data (at most 10 s).
                com.gitlab.mudlej.MjPdfReader.data.StartupReadiness.awaitMaintenanceWindow()
                loop.run()
            } finally {
                Log.i(AgentNotes.TAG, "agent-notes live loop end")
            }
        }
    }

    private fun stopNow() {
        if (shouldRun()) return
        job?.cancel()
        job = null
        client?.abortInFlight()
        client = null
    }

    /** Announces new notes without leaving the screen the user is on. Main thread. */
    fun deliver(context: Context, notes: List<AgentNote>) {
        // A note from the agent shows that Study mode is in use: the listener goes on from here.
        if (notes.isNotEmpty()) AgentStudyListenerService.studyModeUsed(context)
        val activity = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        val noteScreen = activity as? AgentNoteActivity
        val arrival = agentNoteArrival(notes, onNoteScreen = noteScreen != null, shownNoteId = noteScreen?.shownNoteId)
        waitingNotes.update { waiting -> (waiting + arrival.waiting).distinctBy { it.noteId }.sortedBy { it.sequence } }
        arrival.notify.forEach { note ->
            // With notifications off, Thoughts on screen still says so.
            if (!AgentNoteNotifier.notify(context, note) && activity != null) offer(activity, note)
        }
    }

    private fun offer(activity: Activity, note: AgentNote) {
        val anchor = activity.findViewById<View>(android.R.id.content) ?: return
        AppSnackbar.make(anchor, activity.getString(R.string.agent_note_notification_title, note.title), Snackbar.LENGTH_LONG)
            .setAction(R.string.agent_note_open) { activity.startActivity(AgentNoteActivity.intent(activity, note.noteId)) }
            .show()
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        // Study mode outlives the process (reinstall, reboot) and pauses when unused: resume
        // listening when Thoughts opens, and count the opening as a use while it listens.
        if (!listening && AgentStudyMode.isOn(activity)) AgentStudyListenerService.start(activity)
        else AgentStudyListenerService.studyModeUsed(activity)
        update()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        update()
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
