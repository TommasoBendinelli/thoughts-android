// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.usage

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.time.LocalDate
import java.util.Collections
import java.util.IdentityHashMap

data class UsageStatsSnapshot(
    val todayMillis: Long,
    val lastSevenDaysMillis: Long,
    val totalMillis: Long,
)

internal interface UsageStatsPersistence {
    fun totalMillis(): Long

    fun dayMillis(epochDay: Long): Long

    fun add(epochDay: Long, durationMillis: Long)

    fun pruneDaysBefore(earliestEpochDay: Long)
}

internal class UsageAccumulator(
    private val persistence: UsageStatsPersistence,
    private val elapsedRealtime: () -> Long,
    private val currentEpochDay: () -> Long,
) {
    private var activeSinceElapsed: Long? = null
    private var activeEpochDay: Long = currentEpochDay()

    val isActive: Boolean
        @Synchronized get() = activeSinceElapsed != null

    @Synchronized
    fun start() {
        if (activeSinceElapsed != null) return
        activeSinceElapsed = elapsedRealtime()
        activeEpochDay = currentEpochDay()
    }

    @Synchronized
    fun checkpoint() {
        accrueActiveInterval()
    }

    @Synchronized
    fun stop() {
        accrueActiveInterval()
        activeSinceElapsed = null
    }

    @Synchronized
    fun snapshot(): UsageStatsSnapshot {
        accrueActiveInterval()
        val today = currentEpochDay()
        val earliest = today - (ROLLING_DAYS - 1)
        persistence.pruneDaysBefore(earliest)
        return UsageStatsSnapshot(
            todayMillis = persistence.dayMillis(today),
            lastSevenDaysMillis = (earliest..today).sumOf(persistence::dayMillis),
            totalMillis = persistence.totalMillis(),
        )
    }

    private fun accrueActiveInterval() {
        val started = activeSinceElapsed ?: return
        val now = elapsedRealtime()
        val today = currentEpochDay()
        val duration = (now - started).coerceAtLeast(0L)
        if (duration > 0L) {
            persistence.add(activeEpochDay, duration)
        }
        persistence.pruneDaysBefore(today - (ROLLING_DAYS - 1))
        activeSinceElapsed = now
        activeEpochDay = today
    }

    private companion object {
        const val ROLLING_DAYS = 7L
    }
}

internal class FocusedUsageController(
    private val accumulator: UsageAccumulator,
) {
    private val resumedActivities = Collections.newSetFromMap(
        IdentityHashMap<Any, Boolean>(),
    )
    private var interactive = false
    private var locked = true

    val isTracking: Boolean get() = accumulator.isActive

    fun activityResumed(activity: Any) {
        resumedActivities += activity
        updateTracking()
    }

    fun activityPaused(activity: Any) {
        resumedActivities -= activity
        updateTracking()
    }

    fun activityDestroyed(activity: Any) {
        resumedActivities -= activity
        updateTracking()
    }

    fun setDeviceState(interactive: Boolean, locked: Boolean) {
        this.interactive = interactive
        this.locked = locked
        updateTracking()
    }

    fun checkpoint() = accumulator.checkpoint()

    fun snapshot(): UsageStatsSnapshot = accumulator.snapshot()

    private fun updateTracking() {
        if (resumedActivities.isNotEmpty() && interactive && !locked) {
            accumulator.start()
        } else {
            accumulator.stop()
        }
    }
}

internal class ReadingUsageAccumulator(
    private val persist: (ReadingUsageInterval) -> Unit,
    private val elapsedRealtime: () -> Long,
    private val currentEpochDay: () -> Long,
    private val currentTimeMillis: () -> Long,
) {
    private var activeContext: ReadingUsageContext? = null
    private var activeSinceElapsed: Long? = null
    private var activeEpochDay: Long = currentEpochDay()

    val isActive: Boolean
        @Synchronized get() = activeContext != null && activeSinceElapsed != null

    @Synchronized
    fun setContext(context: ReadingUsageContext?) {
        if (activeContext == context) return
        accrueActiveInterval()
        activeContext = context
        activeSinceElapsed = context?.let { elapsedRealtime() }
        activeEpochDay = currentEpochDay()
    }

    @Synchronized
    fun checkpoint() {
        accrueActiveInterval()
    }

    private fun accrueActiveInterval() {
        val context = activeContext ?: return
        val started = activeSinceElapsed ?: return
        val now = elapsedRealtime()
        val duration = (now - started).coerceAtLeast(0L)
        if (duration > 0L) {
            persist(
                ReadingUsageInterval(
                    context = context,
                    epochDay = activeEpochDay,
                    durationMillis = duration,
                    recordedAtMillis = currentTimeMillis(),
                )
            )
        }
        activeSinceElapsed = now
        activeEpochDay = currentEpochDay()
    }
}

internal class FocusedReadingUsageController(
    private val accumulator: ReadingUsageAccumulator,
) {
    private val resumedActivities = Collections.newSetFromMap(
        IdentityHashMap<Any, Boolean>(),
    )
    private val contexts = IdentityHashMap<Any, ReadingUsageContext>()
    private var interactive = false
    private var locked = true

    val isTracking: Boolean get() = accumulator.isActive

    fun activityResumed(activity: Any) {
        resumedActivities += activity
        updateTracking()
    }

    fun activityPaused(activity: Any) {
        resumedActivities -= activity
        updateTracking()
    }

    fun activityDestroyed(activity: Any) {
        resumedActivities -= activity
        contexts.remove(activity)
        updateTracking()
    }

    fun setContext(activity: Any, context: ReadingUsageContext?) {
        if (context == null) contexts.remove(activity) else contexts[activity] = context
        updateTracking()
    }

    fun setDeviceState(interactive: Boolean, locked: Boolean) {
        this.interactive = interactive
        this.locked = locked
        updateTracking()
    }

    fun checkpoint() = accumulator.checkpoint()

    private fun updateTracking() {
        val context = if (interactive && !locked) {
            resumedActivities.firstNotNullOfOrNull(contexts::get)
        } else {
            null
        }
        accumulator.setContext(context)
    }
}

class AppUsageTracker(
    private val application: Application,
) : Application.ActivityLifecycleCallbacks {
    private val powerManager = application.getSystemService(PowerManager::class.java)
    private val keyguardManager = application.getSystemService(KeyguardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val readingUsageService = ReadingUsageService.getInstance(application)
    private val controller = FocusedUsageController(
        UsageAccumulator(
            SharedPreferencesUsageStatsPersistence(application),
            SystemClock::elapsedRealtime,
        ) { LocalDate.now().toEpochDay() },
    )
    private val readingController = FocusedReadingUsageController(
        ReadingUsageAccumulator(
            persist = readingUsageService::record,
            elapsedRealtime = SystemClock::elapsedRealtime,
            currentEpochDay = { LocalDate.now().toEpochDay() },
            currentTimeMillis = System::currentTimeMillis,
        )
    )
    private var installed = false

    private val checkpoint = object : Runnable {
        override fun run() {
            if (!controller.isTracking) return
            controller.checkpoint()
            readingController.checkpoint()
            handler.postDelayed(this, CHECKPOINT_INTERVAL_MILLIS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateDeviceState()
        }
    }

    fun start() {
        if (installed) return
        installed = true
        updateDeviceState()
        application.registerActivityLifecycleCallbacks(this)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            application.registerReceiver(screenReceiver, screenFilter)
        }
    }

    fun snapshot(): UsageStatsSnapshot = controller.snapshot()

    suspend fun readingSnapshot(): ReadingUsageSnapshot {
        readingController.checkpoint()
        return readingUsageService.snapshot()
    }

    fun setReadingContext(activity: Activity, context: ReadingUsageContext?) {
        readingController.setContext(activity, context)
    }

    override fun onActivityResumed(activity: Activity) = updateTracking {
        controller.activityResumed(activity)
        readingController.activityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) = updateTracking {
        controller.activityPaused(activity)
        readingController.activityPaused(activity)
    }

    override fun onActivityDestroyed(activity: Activity) = updateTracking {
        controller.activityDestroyed(activity)
        readingController.activityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun updateDeviceState() = updateTracking()

    private inline fun updateTracking(change: () -> Unit = {}) {
        val wasTracking = controller.isTracking
        change()
        controller.setDeviceState(
            interactive = powerManager.isInteractive,
            locked = keyguardManager.isDeviceLocked,
        )
        readingController.setDeviceState(
            interactive = powerManager.isInteractive,
            locked = keyguardManager.isDeviceLocked,
        )
        when {
            !wasTracking && controller.isTracking -> {
                handler.removeCallbacks(checkpoint)
                handler.postDelayed(checkpoint, CHECKPOINT_INTERVAL_MILLIS)
            }
            wasTracking && !controller.isTracking -> handler.removeCallbacks(checkpoint)
        }
    }

    private companion object {
        const val CHECKPOINT_INTERVAL_MILLIS = 60_000L
    }
}

private class SharedPreferencesUsageStatsPersistence(
    context: Context,
) : UsageStatsPersistence {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun totalMillis(): Long = safeLong(TOTAL_MILLIS_KEY)

    override fun dayMillis(epochDay: Long): Long = safeLong(dayKey(epochDay))

    override fun add(epochDay: Long, durationMillis: Long) {
        if (durationMillis <= 0L) return
        preferences.edit()
            .putLong(TOTAL_MILLIS_KEY, saturatedAdd(totalMillis(), durationMillis))
            .putLong(dayKey(epochDay), saturatedAdd(dayMillis(epochDay), durationMillis))
            .apply()
    }

    override fun pruneDaysBefore(earliestEpochDay: Long) {
        val staleKeys = preferences.all.keys.filter { key ->
            key.startsWith(DAY_KEY_PREFIX) &&
                key.removePrefix(DAY_KEY_PREFIX).toLongOrNull()?.let { it < earliestEpochDay } == true
        }
        if (staleKeys.isEmpty()) return
        preferences.edit().also { editor -> staleKeys.forEach(editor::remove) }.apply()
    }

    private fun safeLong(key: String): Long {
        return try {
            preferences.getLong(key, 0L).coerceAtLeast(0L)
        } catch (exception: ClassCastException) {
            preferences.edit().remove(key).apply()
            0L
        }
    }

    private fun dayKey(epochDay: Long): String = "$DAY_KEY_PREFIX$epochDay"

    private fun saturatedAdd(first: Long, second: Long): Long {
        return if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
    }

    private companion object {
        const val PREFERENCES_NAME = "mjpdf_usage_stats"
        const val TOTAL_MILLIS_KEY = "total_millis"
        const val DAY_KEY_PREFIX = "day_"
    }
}
