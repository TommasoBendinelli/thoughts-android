// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/** Keeps a user-started sync alive after its activity moves to the background. */
class ThoughtsSyncForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = ThoughtsSyncScheduler.artifactNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ThoughtsSyncScheduler.ARTIFACT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(ThoughtsSyncScheduler.ARTIFACT_NOTIFICATION_ID, notification)
        }
        // Completion can race a visible activity's asynchronous recovery check.
        Thread({
            if (SyncOperationStore(this).pendingOperations().isEmpty()) stopSelfResult(startId)
        }, "thoughts-foreground-idle-check").start()
        return START_NOT_STICKY
    }

    /**
     * Android allows a data-sync foreground service a limited time a day (six hours since Android
     * 15) and then calls this; a service still in the foreground a few seconds later takes the
     * whole app down with it. The service only protects a Sync that runs elsewhere (WorkManager or
     * a job): it steps down at once, and the Sync goes on without that protection or is settled by
     * [SyncStallWatchdog] and the reconciliation at the next start.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stepDownAfterTimeout(startId)
    }

    /** The same call as Android 14 makes it, for a service type with a short limit. */
    override fun onTimeout(startId: Int) {
        stepDownAfterTimeout(startId)
    }

    private fun stepDownAfterTimeout(startId: Int) {
        Log.w("ThoughtsSync", "Android ended the Sync foreground service at its time limit (start $startId)")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        // Off the main thread: the service must be gone before anything else is done.
        Thread({
            runCatching {
                val store = SyncOperationStore(applicationContext)
                store.pendingOperations().forEach { record ->
                    store.append(record.id, record.status, FOREGROUND_TIMEOUT_EVENT)
                }
            }
        }, "thoughts-foreground-timeout").start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val FOREGROUND_TIMEOUT_EVENT =
            "Android ended the foreground protection of this Sync at its daily limit; the Sync goes on in the background"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ThoughtsSyncForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ThoughtsSyncForegroundService::class.java))
        }
    }
}
