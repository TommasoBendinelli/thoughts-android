// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

/** Recover protection for an existing sync while Android permits a visible-app start. */
internal class SyncForegroundRecovery : Application.ActivityLifecycleCallbacks {
    @Volatile private var resumedActivity: Activity? = null

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        Thread({
            val context = activity.applicationContext
            val pending = runCatching {
                SyncOperationStore(context).pendingOperations().any {
                    it.phase !in setOf(SyncRunPhase.WAITING_NETWORK, SyncRunPhase.WAITING_USER)
                }
            }
                .getOrDefault(false)
            if (!pending) return@Thread
            activity.runOnUiThread {
                if (resumedActivity !== activity || activity.isFinishing || activity.isDestroyed) {
                    return@runOnUiThread
                }
                runCatching { ThoughtsSyncForegroundService.start(context) }
                    .onFailure { Log.w("ThoughtsSync", "Could not restore foreground sync protection", it) }
            }
        }, "thoughts-visible-sync-recovery").start()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
