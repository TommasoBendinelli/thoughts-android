// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
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
