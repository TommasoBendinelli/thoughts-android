// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Fedora's Sync state as [FedoraRepository.remoteWork] reads it. */
internal data class RemoteWork(val key: String, val cursorBehind: Boolean)

internal data class RemoteWorkDecision(val sync: Boolean, val baseline: String?, val triggered: String?)

/**
 * The rule of [RemoteWorkWatch]. [baseline] is the server state seen once the last Sync had
 * finished; [triggered] the one the last automatic Sync was started for.
 * - Right after a Sync (or before any was seen) the current state becomes the baseline; a Sync
 *   starts only if Fedora's operations are still ahead of the phone.
 * - Otherwise a state other than the baseline is work, but one state starts at most one automatic
 *   Sync, so a Sync that cannot finish is not repeated.
 */
internal fun remoteWorkDecision(
    remote: RemoteWork,
    syncJustFinished: Boolean,
    baseline: String?,
    triggered: String?,
): RemoteWorkDecision = when {
    syncJustFinished || baseline == null -> {
        val sync = remote.cursorBehind && remote.key != triggered
        RemoteWorkDecision(sync, remote.key, if (sync) remote.key else triggered)
    }
    remote.key == baseline || remote.key == triggered -> RemoteWorkDecision(false, baseline, triggered)
    else -> RemoteWorkDecision(true, baseline, remote.key)
}

/**
 * An agent's proposals and new PDF builds reach Fedora without this phone knowing, and the phone
 * synced only when asked. While Thoughts is on screen this asks Fedora every 20 seconds and runs
 * a Sync when it holds work the phone has not pulled; the Sync also sends local changes.
 */
object RemoteWorkWatch : Application.ActivityLifecycleCallbacks {
    private const val TAG = "ThoughtsRemoteWork"
    private const val INTERVAL_MILLIS = 20_000L
    private const val PREFS = "thoughts_remote_work_watch"
    private const val KEY_BASELINE = "baseline_state_key"
    private const val KEY_TRIGGERED = "triggered_state_key"

    private var application: Application? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var startedActivities = 0
    private var job: Job? = null

    fun install(application: Application) {
        this.application = application
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (job?.isActive != true) start()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            job?.cancel()
            job = null
        }
    }

    private fun start() {
        val app = application ?: return
        job = scope.launch {
            com.gitlab.mudlej.MjPdfReader.data.StartupReadiness.awaitMaintenanceWindow()
            val repository = FedoraRepository(app)
            // The first idle check after the app comes up records a baseline.
            var syncWasRunning = true
            while (isActive) {
                try {
                    syncWasRunning = check(app, repository, syncWasRunning)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "remote work check failed: ${error.javaClass.simpleName}")
                }
                delay(INTERVAL_MILLIS)
            }
        }
    }

    /** One check; returns whether a Sync is running. */
    private suspend fun check(app: Application, repository: FedoraRepository, syncWasRunning: Boolean): Boolean {
        if (SyncOperationStore(app).pendingOperations().isNotEmpty()) return true
        // Without Tailscale the server cannot be asked, and a Sync would only record that.
        if (!ThoughtsNetworkPolicy.requiredVpnActive(app)) return false
        val remote = repository.remoteWork() ?: return false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val decision = remoteWorkDecision(
            remote,
            syncJustFinished = syncWasRunning,
            baseline = prefs.getString(KEY_BASELINE, null),
            triggered = prefs.getString(KEY_TRIGGERED, null),
        )
        prefs.edit().putString(KEY_BASELINE, decision.baseline).putString(KEY_TRIGGERED, decision.triggered).apply()
        if (!decision.sync || startedActivities == 0) return false
        Log.i(TAG, "Fedora holds new work (${remote.key}); starting a Sync")
        ThoughtsSyncScheduler.enqueue(app, origin = ExplicitSyncOrigin.BACKGROUND_COMMAND)
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
