// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import java.io.IOException

/**
 * Registers this phone's push token with the server, or removes the registration ([PushRegistrar]).
 * Runs at app start, after a server change, a token refresh and a change of "Receive agent notes on
 * this phone"; network required, exponential back-off while the server or Google cannot be reached.
 */
class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val context = applicationContext
        // Instrumented tests replace the agent-notes server; they never register pushes.
        if (AgentNotes.apiOverride != null) return Result.success()
        val server = runCatching { ThoughtsServerSecretStore(context).config()?.baseUrl }.getOrNull()
        val outcome = PushRegistrar(AgentNotes.client(context), AgentNotesPush.recordStore(context), log = { Log.i(AgentNotes.TAG, it) })
            .run(
                hasConfig = AgentNotesPush.buildHasConfig,
                receiving = AgentNotesReceiving.isOn(context),
                server = server,
                app = context.packageName,
                playServices = { AgentNotesPush.playServicesUsable(context) },
                token = { AgentNotesPush.token(context) },
            )
        AgentNotesPush.refresh(context)
        return if (outcome == PushRegistrar.Outcome.RETRY && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        /** 30 s doubling: the tenth try is about four hours after the first; the next app start tries again. */
        const val MAX_ATTEMPTS = 10
    }
}

/**
 * A push said an agent note is waiting: asks the inbox now ([AgentNoteSync.pushCheck]) and hands
 * new notes to [AgentNoteInbox.deliver], the same way as the live loop and the periodic job (same
 * notifications, same "New note" button, each note once). A plain Worker, so a note that was
 * stored is always delivered even when WorkManager stops the work meanwhile.
 */
class AgentNotesPushFetchWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!AgentNotesReceiving.isOn(context)) return Result.success()
        if (AgentNotes.apiOverride == null && runCatching { ThoughtsServerSecretStore(context).config() }.getOrNull() == null) {
            return Result.success()
        }
        val fresh = try {
            AgentNotes.sync(context).pushCheck(liveLoopRunning = AgentNoteInbox.running)
        } catch (error: IOException) {
            Log.i(AgentNotes.TAG, "push: inbox failed ${error.javaClass.simpleName}")
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        Log.i(AgentNotes.TAG, "push: inbox new=${fresh.size}")
        if (fresh.isNotEmpty()) Handler(Looper.getMainLooper()).post { AgentNoteInbox.deliver(context, fresh) }
        return Result.success()
    }

    /** Before Android 12 expedited work runs as a foreground service, which needs a notification. */
    override fun getForegroundInfo(): ForegroundInfo {
        val notification = AgentNoteNotifier.fetchingNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(AgentNoteNotifier.FETCHING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(AgentNoteNotifier.FETCHING_NOTIFICATION_ID, notification)
        }
    }
}

// ThoughtsPushService, which receives the pings, is in src/push: only a build with push has it.
