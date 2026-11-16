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
import kotlinx.coroutines.currentCoroutineContext
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
 *   starts only if Fedora's operations are still ahead of the phone. The state is the one seen
 *   after that Sync: when the Sync was this watch's own and did not succeed
 *   ([automaticSyncStalled]), the operations are ahead because it could not pull them, not because
 *   something new arrived, so it is no reason for another Sync. Only a later change is.
 * - Otherwise a state other than the baseline is work, but one state starts at most one automatic
 *   Sync, so a Sync that cannot finish is not repeated.
 * - While builds that a Sync left running are pending ([buildsPending]), the state changes as they
 *   run (a document's build status is part of the Library): such a change only moves the baseline,
 *   since the watch follows those builds themselves ([pendingBuildsDecision]). New operations
 *   (an agent's proposal, another device's change) still start a Sync.
 */
internal fun remoteWorkDecision(
    remote: RemoteWork,
    syncJustFinished: Boolean,
    baseline: String?,
    triggered: String?,
    buildsPending: Boolean = false,
    automaticSyncStalled: Boolean = false,
): RemoteWorkDecision = when {
    syncJustFinished || baseline == null -> {
        val sync = remote.cursorBehind && remote.key != triggered && !automaticSyncStalled
        RemoteWorkDecision(sync, remote.key, if (sync) remote.key else triggered)
    }
    remote.key == baseline || remote.key == triggered -> RemoteWorkDecision(false, baseline, triggered)
    buildsPending && !remote.cursorBehind -> RemoteWorkDecision(false, remote.key, triggered)
    else -> RemoteWorkDecision(true, baseline, remote.key)
}

/**
 * Which pending builds start a Sync now: those Fedora has finished ([finished], null when Fedora
 * could not be asked). Each finished build starts one Sync and is then forgotten, so a Sync that
 * fails does not start another for it.
 */
internal fun pendingBuildsDecision(pending: List<PendingFedoraBuild>, finished: Set<String>?): Set<String> =
    finished.orEmpty().intersect(pending.mapTo(hashSetOf(), PendingFedoraBuild::buildId))

/** What [RemoteWorkWatch] keeps between checks (and across restarts of the app). */
internal data class RemoteWatchState(
    val baseline: String? = null,
    val triggered: String? = null,
    val pace: AutomaticSyncPace = AutomaticSyncPace(),
    /** The Sync this watch started and has not yet seen end. */
    val automaticOperationId: String? = null,
    /** The last Sync this watch started did not succeed. */
    val automaticSyncStalled: Boolean = false,
)

internal data class AutomaticSyncEnd(val endedAtMillis: Long, val succeeded: Boolean)

/** What one check of [RemoteWorkWatch] reads and does, so the rule runs against fakes in tests. */
internal interface RemoteWorkPorts {
    fun nowMillis(): Long
    fun loadState(): RemoteWatchState
    fun saveState(state: RemoteWatchState)
    /** Whether a Sync is queued or running on this phone. */
    fun syncPending(): Boolean
    /** How the Sync [operationId] ended; null while it is queued or running. */
    fun automaticSyncEnd(operationId: String): AutomaticSyncEnd?
    fun serverReachable(): Boolean
    fun appVisible(): Boolean
    fun pendingBuilds(): List<PendingFedoraBuild>
    suspend fun finishedBuilds(buildIds: List<String>): Set<String>?
    fun forgetBuilds(buildIds: Set<String>)
    suspend fun remoteWork(): RemoteWork?
    /** Starts a Sync and returns its operation. */
    fun startAutomaticSync(reason: String): String
    fun log(message: String) = Unit
}

/**
 * One check of [RemoteWorkWatch]: whether Fedora holds work ([remoteWorkDecision],
 * [pendingBuildsDecision]) and whether the Sync for it starts now. Returns what the next check
 * takes as "a Sync was running".
 *
 * The Sync that fetches the PDFs of finished builds starts at once: the user waits for those
 * PDFs, each build this phone left pending starts at most one, and so they cannot multiply. Only
 * its end counts for the spacing of the others. A Sync for a changed server state is spaced
 * ([automaticSyncStart]). One that is held back changes nothing but the pace: the next check
 * decides again, so a Sync the user starts meanwhile, or work that went away, leaves nothing to
 * start.
 */
internal suspend fun remoteWorkCheck(ports: RemoteWorkPorts, syncWasRunning: Boolean): Boolean {
    var state = ports.loadState()
    fun save(next: RemoteWatchState) {
        if (next != state) ports.saveState(next)
        state = next
    }
    val now = ports.nowMillis()
    state.automaticOperationId?.let { id ->
        val end = ports.automaticSyncEnd(id) ?: return@let
        save(
            state.copy(
                automaticOperationId = null,
                automaticSyncStalled = !end.succeeded,
                pace = state.pace.afterAutomaticSync(end.endedAtMillis.coerceAtMost(now), end.succeeded),
            ),
        )
    }
    if (ports.syncPending()) return true
    // Without Tailscale the server cannot be asked, and a Sync would only record that.
    if (!ports.serverReachable()) return false

    /** Starts the automatic Sync; [before] is saved first, so its end is counted even if queueing fails. */
    fun start(reason: String, before: RemoteWatchState) {
        ports.log("$reason; starting a Sync")
        save(before)
        save(state.copy(automaticOperationId = ports.startAutomaticSync(reason)))
    }

    val pending = ports.pendingBuilds()
    if (pending.isNotEmpty()) {
        val finished = pendingBuildsDecision(pending, ports.finishedBuilds(pending.map(PendingFedoraBuild::buildId)))
        if (finished.isNotEmpty() && ports.appVisible()) {
            // Never held back. It also pulls whatever a held Sync was waiting to pull.
            ports.forgetBuilds(finished)
            start(
                "Fedora finished ${finished.size} of ${pending.size} pending PDF build(s)",
                state.copy(pace = state.pace.withNothingHeld(), automaticSyncStalled = false),
            )
            return true
        }
    }
    val remote = ports.remoteWork() ?: return false
    val decision = remoteWorkDecision(
        remote,
        syncJustFinished = syncWasRunning,
        baseline = state.baseline,
        triggered = state.triggered,
        buildsPending = pending.isNotEmpty(),
        automaticSyncStalled = state.automaticSyncStalled,
    )
    val decided = state.copy(baseline = decision.baseline, triggered = decision.triggered, automaticSyncStalled = false)
    if (!decision.sync || !ports.appVisible()) {
        save(decided.copy(pace = state.pace.withNothingHeld()))
        return false
    }
    val reason = "Fedora holds new work (${remote.key})"
    val pacing = automaticSyncStart(state.pace, now)
    if (!pacing.startNow) {
        if (pacing.pace != state.pace) {
            ports.log(
                "$reason; the Sync waits ${(pacing.notBeforeMillis - now + 999) / 1000} s " +
                    "(${pacing.pace.waitMillis / 1000} s after the previous automatic Sync)",
            )
        }
        // Held: nothing of the decision is kept, so the next check takes it again.
        save(state.copy(pace = pacing.pace))
        return syncWasRunning
    }
    start(reason, decided.copy(pace = pacing.pace))
    return true
}

/** What [RemoteStatePause] needs, so it runs against fakes in tests. */
internal interface RemoteStatePausePorts {
    /** A monotonic clock. */
    fun elapsedMillis(): Long
    suspend fun sleep(millis: Long)
    /** Whether the server holds Sync state requests ([SYNC_STATE_WAIT_CAPABILITY]). */
    suspend fun stateWaitsAvailable(): Boolean
    /** One held `GET /v2/sync/state?wait_ms=` with the kept ETag ([waitForSyncState]). */
    suspend fun awaitStateChange(waitMillis: Long): SyncStateWait
    fun log(message: String) = Unit
}

/**
 * The pause of [RemoteWorkWatch] between two checks when it does not wait on a pending build.
 *
 * When the server holds Sync state requests, the pause is one such request: the server answers
 * as soon as its state changes (a build another device caused has finished, a proposal arrived),
 * and the next check runs at once, instead of up to 20 s later. Nothing changing, the server
 * answers after [holdMillis] (25 s) and the check takes that answer without asking again
 * ([ConditionalAnswer.takeConfirmed]): one request per 25 s instead of one per 20 s.
 *
 * Everything else stays as it was. The request is held only while no Sync runs ([pause] gets
 * `mayHold` false then). When a held-back Sync may start sooner than the interval, the request
 * ends then, so that Sync starts on time. Without the capability, or once a held request failed or
 * was answered at once (an older server, one that holds too many or is stopping), the pause is a
 * plain [intervalMillis] for [plainAfterFailureMillis], as it always was.
 */
internal class RemoteStatePause(
    private val ports: RemoteStatePausePorts,
    private val intervalMillis: Long,
    private val holdMillis: Long = SYNC_STATE_LONGEST_WAIT_MILLIS,
    private val plainAfterFailureMillis: Long = 2 * 60_000L,
) {
    private var plainUntil = Long.MIN_VALUE

    /** Waits until the next check: at most [pauseMillis] when the pause is plain or a held Sync is due. */
    suspend fun pause(pauseMillis: Long, mayHold: Boolean) {
        val started = ports.elapsedMillis()
        if (mayHold && started >= plainUntil) {
            val outcome = try {
                if (ports.stateWaitsAvailable()) {
                    ports.awaitStateChange(if (pauseMillis < intervalMillis) pauseMillis else holdMillis)
                } else {
                    null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SyncStateWait.FAILED
            }
            when (outcome) {
                null -> Unit
                SyncStateWait.CHANGED, SyncStateWait.UNCHANGED -> return
                SyncStateWait.NOT_HELD, SyncStateWait.FAILED -> {
                    plainUntil = ports.elapsedMillis() + plainAfterFailureMillis
                    ports.log("held Sync state request ${outcome.name.lowercase()}; plain pauses for a while")
                }
            }
        }
        val remaining = pauseMillis - (ports.elapsedMillis() - started)
        if (remaining > 0) ports.sleep(remaining)
    }
}

/**
 * The loop of [RemoteWorkWatch] until it is cancelled (Thoughts leaves the screen): a [check]
 * ([remoteWorkCheck]), which says whether a Sync was running, then the [pause] before the next. A
 * check that fails is reported to [failed] and the loop goes on.
 */
internal suspend fun watchRemoteWork(
    check: suspend (syncWasRunning: Boolean) -> Boolean,
    pause: suspend (syncRunning: Boolean) -> Unit,
    failed: (Exception) -> Unit = {},
) {
    // The first idle check after the app comes up records a baseline.
    var syncWasRunning = true
    while (currentCoroutineContext().isActive) {
        try {
            syncWasRunning = check(syncWasRunning)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed(error)
        }
        pause(syncWasRunning)
    }
}

/**
 * An agent's proposals and new PDF builds reach Fedora without this phone knowing, and the phone
 * synced only when asked. While Thoughts is on screen this asks Fedora every 20 seconds and runs
 * a Sync when it holds work the phone has not pulled; the Sync also sends local changes. When the
 * server can hold the question until its state changes ([RemoteStatePause]), the pause between
 * two checks is that one held request (up to 25 s), and a change starts the check at once.
 *
 * Sync does not wait for Fedora's PDF builds: it leaves the builds still running in
 * [PendingFedoraBuildStore], and this watch asks Fedora about each of them on every check
 * (`GET /v2/builds/{id}`) and runs the Sync that fetches their PDFs as soon as one has finished.
 *
 * The Syncs for a changed state are spaced ([automaticSyncStart]): on 13 November 2026 a state
 * that changed with every check started 78 full Syncs in 41 minutes. The first after a quiet
 * period starts at once; one wanted sooner than the current spacing after the previous automatic
 * Sync waits for it (20 s, then 40 s, 80 s, at most 2 minutes while automatic Syncs succeed, up
 * to 10 minutes while they fail), and the watch wakes when that wait is over. The Sync that
 * fetches a finished build's PDFs is never held back, and a Sync the user starts is neither
 * delayed nor counted.
 */
object RemoteWorkWatch : Application.ActivityLifecycleCallbacks {
    private const val TAG = "ThoughtsRemoteWork"
    private const val INTERVAL_MILLIS = 20_000L
    private const val PREFS = "thoughts_remote_work_watch"
    private const val KEY_BASELINE = "baseline_state_key"
    private const val KEY_TRIGGERED = "triggered_state_key"
    private const val KEY_AUTOMATIC_ENDED_AT = "automatic_sync_ended_at"
    private const val KEY_AUTOMATIC_WAIT = "automatic_sync_wait_millis"
    private const val KEY_AUTOMATIC_HELD_SINCE = "automatic_sync_held_since"
    private const val KEY_AUTOMATIC_OPERATION = "automatic_sync_operation"
    private const val KEY_AUTOMATIC_STALLED = "automatic_sync_stalled"
    private const val KEY_AUTOMATIC_SUCCEEDED = "automatic_sync_previous_succeeded"
    private const val LEFTOVER_CHECK_MILLIS = 60_000L
    @Volatile private var leftoversSettledAt = -LEFTOVER_CHECK_MILLIS

    private var application: Application? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var startedActivities = 0
    private var job: Job? = null
    /** The pending build whose end last cut the pause short ([awaitNextCheck]). */
    private var endedEarlyFor: String? = null

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
            val statePause = RemoteStatePause(StatePausePorts(repository), INTERVAL_MILLIS)
            watchRemoteWork(
                check = { syncWasRunning -> check(app, repository, syncWasRunning) },
                pause = { syncRunning -> awaitNextCheck(app, repository, statePause, syncRunning) },
                failed = { error -> Log.w(TAG, "remote work check failed: ${error.javaClass.simpleName}") },
            )
        }
    }

    /**
     * The pause before the next check: [INTERVAL_MILLIS], or less when a held Sync may start
     * sooner ([nextRemoteCheckDelayMillis]). It is spent waiting on Fedora for a pending build to
     * end while one is pending and no Sync runs, so its PDF is fetched as soon as it is built (the
     * next check, at once, sees it finished) rather than up to 20 s later. A build ends the pause
     * early once: should that check not start its Sync, the next pause is not spent on it. Any
     * other pause is a held Sync state request where the server offers one ([RemoteStatePause]).
     */
    private suspend fun awaitNextCheck(
        app: Application,
        repository: FedoraRepository,
        statePause: RemoteStatePause,
        syncRunning: Boolean,
    ) {
        val started = android.os.SystemClock.elapsedRealtime()
        val pause = runCatching {
            nextRemoteCheckDelayMillis(Ports(app, repository).loadState().pace, System.currentTimeMillis(), INTERVAL_MILLIS)
        }.getOrDefault(INTERVAL_MILLIS)
        val pending = if (syncRunning) null else runCatching { PendingFedoraBuildStore(app).builds().firstOrNull() }.getOrNull()
        val reachable = ThoughtsNetworkPolicy.requiredVpnActive(app)
        if (pending == null || pending.buildId == endedEarlyFor || !reachable) {
            statePause.pause(pause, mayHold = !syncRunning && reachable)
            return
        }
        if (runCatching { repository.awaitBuildEnd(pending.buildId, pause.toInt()) }.getOrNull() == true) {
            endedEarlyFor = pending.buildId
            return
        }
        val remaining = pause - (android.os.SystemClock.elapsedRealtime() - started)
        if (remaining > 0) delay(remaining)
    }

    private class StatePausePorts(private val repository: FedoraRepository) : RemoteStatePausePorts {
        override fun elapsedMillis(): Long = android.os.SystemClock.elapsedRealtime()
        override suspend fun sleep(millis: Long) = delay(millis)
        override suspend fun stateWaitsAvailable(): Boolean = repository.holdsStateWaits()
        override suspend fun awaitStateChange(waitMillis: Long): SyncStateWait = repository.awaitRemoteChange(waitMillis)
        override fun log(message: String) {
            Log.i(TAG, message)
        }
    }

    /** One check ([remoteWorkCheck]); returns what the next check takes as "a Sync was running". */
    private suspend fun check(app: Application, repository: FedoraRepository, syncWasRunning: Boolean): Boolean =
        remoteWorkCheck(Ports(app, repository), syncWasRunning)

    private class Ports(private val app: Application, private val repository: FedoraRepository) : RemoteWorkPorts {
        private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val pendingBuilds = PendingFedoraBuildStore(app)

        override fun nowMillis(): Long = System.currentTimeMillis()

        override fun loadState() = RemoteWatchState(
            baseline = prefs.getString(KEY_BASELINE, null),
            triggered = prefs.getString(KEY_TRIGGERED, null),
            pace = AutomaticSyncPace(
                lastEndedAtMillis = prefs.getLong(KEY_AUTOMATIC_ENDED_AT, -1L).takeIf { it >= 0 },
                waitMillis = prefs.getLong(KEY_AUTOMATIC_WAIT, AUTOMATIC_SYNC_FIRST_WAIT_MILLIS),
                heldSinceMillis = prefs.getLong(KEY_AUTOMATIC_HELD_SINCE, -1L).takeIf { it >= 0 },
                previousSucceeded = prefs.getBoolean(KEY_AUTOMATIC_SUCCEEDED, true),
            ),
            automaticOperationId = prefs.getString(KEY_AUTOMATIC_OPERATION, null),
            automaticSyncStalled = prefs.getBoolean(KEY_AUTOMATIC_STALLED, false),
        )

        override fun saveState(state: RemoteWatchState) {
            prefs.edit()
                .putString(KEY_BASELINE, state.baseline)
                .putString(KEY_TRIGGERED, state.triggered)
                .putLong(KEY_AUTOMATIC_ENDED_AT, state.pace.lastEndedAtMillis ?: -1L)
                .putLong(KEY_AUTOMATIC_WAIT, state.pace.waitMillis)
                .putLong(KEY_AUTOMATIC_HELD_SINCE, state.pace.heldSinceMillis ?: -1L)
                .putBoolean(KEY_AUTOMATIC_SUCCEEDED, state.pace.previousSucceeded)
                .putString(KEY_AUTOMATIC_OPERATION, state.automaticOperationId)
                .putBoolean(KEY_AUTOMATIC_STALLED, state.automaticSyncStalled)
                .apply()
        }

        override fun syncPending(): Boolean {
            val store = SyncOperationStore(app)
            if (store.pendingOperations().isEmpty()) return false
            // A Sync left without its worker would hold every automatic Sync back for good: settle
            // such leftovers here too, on whichever screen the user is, at most once a minute.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - leftoversSettledAt < LEFTOVER_CHECK_MILLIS) return true
            leftoversSettledAt = now
            runCatching { ThoughtsSyncScheduler.reconcileInterruptedOperations(app, announceContinuation = false) }
            return store.pendingOperations().isNotEmpty()
        }

        override fun automaticSyncEnd(operationId: String): AutomaticSyncEnd? {
            val record = SyncOperationStore(app).operation(operationId)
                ?: return AutomaticSyncEnd(nowMillis(), succeeded = false)
            if (isPendingSyncOperationStatus(record.status)) return null
            return AutomaticSyncEnd(record.updatedAtEpochMillis, record.status == SyncOperationStore.STATUS_SYNCED)
        }

        override fun serverReachable(): Boolean = ThoughtsNetworkPolicy.requiredVpnActive(app)
        override fun appVisible(): Boolean = startedActivities > 0
        override fun pendingBuilds(): List<PendingFedoraBuild> = pendingBuilds.builds()
        override suspend fun finishedBuilds(buildIds: List<String>): Set<String>? = repository.finishedBuilds(buildIds)
        override fun forgetBuilds(buildIds: Set<String>) = pendingBuilds.remove(buildIds)
        override suspend fun remoteWork(): RemoteWork? = repository.remoteWork()

        override fun startAutomaticSync(reason: String): String {
            val operationId = SyncOperationStore(app).begin(null, building = false)
            // A Sync that cannot be queued is recorded as failed by the scheduler itself.
            runCatching {
                ThoughtsSyncScheduler.enqueue(
                    app,
                    preparedOperationId = operationId,
                    origin = ExplicitSyncOrigin.BACKGROUND_COMMAND,
                )
            }.onFailure { Log.w(TAG, "automatic Sync was not queued: ${it.javaClass.simpleName}") }
            return operationId
        }

        override fun log(message: String) {
            Log.i(TAG, message)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
