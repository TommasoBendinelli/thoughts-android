// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * When nothing is listening live: every 15 minutes (network required) asks the inbox without
 * waiting, stores and announces new notes, and sends unsent replies. With [AgentNotesReceiving]
 * off it only sends unsent replies.
 */
class AgentNotesWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (AgentNotes.apiOverride == null && runCatching { ThoughtsServerSecretStore(context).config() }.getOrNull() == null) {
            return@withContext Result.success()
        }
        val sync = AgentNotes.sync(context)
        try {
            val (sent, fresh) = sync.periodicCheck(receiving = AgentNotesReceiving.isOn(context))
            if (sent > 0 || fresh.isNotEmpty()) Log.i(AgentNotes.TAG, "agent-notes worker sent=$sent new=${fresh.size}")
            if (fresh.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post { AgentNoteInbox.deliver(context, fresh) }
            }
        } catch (error: IOException) {
            Log.i(AgentNotes.TAG, "agent-notes worker failed ${error.javaClass.simpleName}")
        }
        Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "agent-notes-inbox"

        /** Idempotent (KEEP); call off the main thread. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AgentNotesWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
