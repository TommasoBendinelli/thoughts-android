// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gitlab.mudlej.MjPdfReader.R

class OfflineHtmlService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Offline HTML", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(this, 0, Intent(this, OfflineHtmlService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle("Offline HTML is available")
            .setContentText("Keeps your downloaded pages available in the browser.")
            .addAction(0, "Close pages", stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7142, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7142, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) stopSelf()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        synchronized(pages) { pages.forEach { runCatching { it.close() } }; pages.clear() }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "offline-html"
        private const val STOP = "close-html"
        private val pages = mutableListOf<OfflineHtmlServer>()
        internal fun serve(html: java.io.File): String = synchronized(pages) {
            // Keep already opened pages alive; bound the number of listening sockets.
            check(pages.size < 16) { "Close the existing offline pages from the notification before opening more." }
            val server = OfflineHtmlServer(html)
            pages.add(server)
            server.url
        }
    }
}
