// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNotesListActivity

/**
 * Keeps the agent-notes long-poll running while Thoughts is in the background (the user is
 * reading the agent's answer on the Mac). Started only by user actions in the foreground (Copy for
 * agent, opening a note, sending a reply), never while [AgentNotesReceiving] is off; stops itself [LISTEN_MILLIS] after the last of them, or
 * from its notification's Stop action. In [AgentStudyMode] it has no time limit.
 */
class AgentStudyListenerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expiryCheck = Runnable { checkExpiry() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground after startForegroundService, even when stopping at once.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.onFailure { Log.w(AgentNotes.TAG, "study listener refused: ${it.javaClass.simpleName}") }.isSuccess
        if (intent?.action == ACTION_STOP) AgentStudyMode.clear(this)
        // A start already queued when the user turned "Receive agent notes on this phone" off.
        if (intent?.action == ACTION_STOP || !foreground || !AgentNotesReceiving.isOn(this)) {
            listenUntilElapsed = 0L
            stop()
            return START_NOT_STICKY
        }
        val studyMode = AgentStudyMode.isOn(this)
        AgentNoteInbox.setListening(true)
        handler.removeCallbacks(expiryCheck)
        if (studyMode) {
            listenUntilElapsed = Long.MAX_VALUE
            return START_STICKY
        }
        handler.postDelayed(expiryCheck, (listenUntilElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1_000L))
        return START_NOT_STICKY
    }

    private fun checkExpiry() {
        val remaining = listenUntilElapsed - SystemClock.elapsedRealtime()
        if (remaining <= 0) stop() else handler.postDelayed(expiryCheck, remaining)
    }

    private fun stop() {
        handler.removeCallbacks(expiryCheck)
        AgentNoteInbox.setListening(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15 ends dataSync services after their daily allowance. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        listenUntilElapsed = 0L
        stop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(expiryCheck)
        AgentNoteInbox.setListening(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.agent_notes_listening_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentStudyListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            2,
            AgentNotesListActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_agent_note)
            .setContentTitle(getString(R.string.agent_notes_listening))
            .setContentText(getString(
                if (AgentStudyMode.isOn(this)) R.string.agent_notes_study_mode_listening_detail else R.string.agent_notes_listening_detail,
            ))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop, getString(R.string.agent_notes_listening_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "agent_notes_listening"
        private const val NOTIFICATION_ID = 7151
        private const val ACTION_STOP = "org.tbend.thoughts.agentnotes.STOP_LISTENING"
        const val LISTEN_MILLIS = 30 * 60 * 1000L

        @Volatile private var listenUntilElapsed = 0L

        /**
         * Starts listening, or extends it to [LISTEN_MILLIS] from now. Call only from a user action
         * in the foreground; Android refuses a background start, and then only the foreground loop runs.
         */
        fun start(context: Context) {
            if (!AgentNotesReceiving.isOn(context)) {
                Log.i(AgentNotes.TAG, "study listener not started: this phone does not receive agent notes")
                return
            }
            listenUntilElapsed = if (AgentStudyMode.isOn(context)) Long.MAX_VALUE else SystemClock.elapsedRealtime() + LISTEN_MILLIS
            try {
                ContextCompat.startForegroundService(context, Intent(context, AgentStudyListenerService::class.java))
            } catch (error: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
                Log.w(AgentNotes.TAG, "study listener not started: ${error.javaClass.simpleName}")
            } catch (error: SecurityException) {
                Log.w(AgentNotes.TAG, "study listener not started: ${error.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            listenUntilElapsed = 0L
            context.stopService(Intent(context, AgentStudyListenerService::class.java))
        }
    }
}
