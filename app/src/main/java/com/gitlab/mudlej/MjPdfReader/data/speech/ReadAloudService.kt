// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps Read aloud playing while the screen is off or another app is in front, with Pause/Resume
 * and Stop in its notification. It follows [ReadAloudSession.state] and stops itself when the
 * reading ends.
 */
class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val session = ReadAloudSession.get(this)
        // Android requires startForeground after startForegroundService, even when the reading
        // ended in the meantime; the observer below then stops the service at once.
        startInForeground(notification(session.state.value))
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> session.togglePause()
            ACTION_STOP -> session.stop()
        }
        if (observer == null) {
            observer = scope.launch {
                session.state.collect { state ->
                    if (state.active) {
                        startInForeground(notification(state))
                    } else {
                        ServiceCompat.stopForeground(this@ReadAloudService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelfResult(lastStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(notification: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
        }.onFailure { Log.w(ReadAloudSession.TAG, "foreground service refused: ${it.javaClass.simpleName}") }
    }

    private fun notification(state: ReadAloudState): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.read_aloud), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, ReadAloudService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, ReadAloudService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            state.paused -> R.string.read_aloud_paused
            state.systemVoice -> R.string.read_aloud_reading_system_voice
            else -> R.string.read_aloud_reading
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_read_aloud)
            .setContentTitle(getString(title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (state.paused) R.drawable.ic_play_arrow else R.drawable.ic_pause,
                getString(if (state.paused) R.string.read_aloud_resume else R.string.read_aloud_pause),
                toggle,
            )
            .addAction(R.drawable.ic_stop, getString(R.string.read_aloud_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 7143
        private const val ACTION_TOGGLE_PAUSE = "org.tbend.thoughts.readaloud.TOGGLE_PAUSE"
        private const val ACTION_STOP = "org.tbend.thoughts.readaloud.STOP"

        /** Called by [ReadAloudSession] when a reading starts; the service stops itself when it ends. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
            }.onFailure {
                // Playback still works while the reader is visible; only screen-off playback is lost.
                Log.w(ReadAloudSession.TAG, "foreground service not started: ${it.javaClass.simpleName}")
            }
        }
    }
}
