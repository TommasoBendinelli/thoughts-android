// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageTrackerTest {
    private var elapsed = 0L
    private var epochDay = 100L
    private val persistence = InMemoryUsageStatsPersistence()
    private val accumulator = UsageAccumulator(persistence, { elapsed }, { epochDay })

    @Test
    fun onlyFocusedInteractiveUnlockedTimeIsCounted() {
        val controller = FocusedUsageController(accumulator)
        val activity = Any()

        controller.setDeviceState(interactive = true, locked = false)
        controller.activityResumed(activity)
        elapsed += 5_000L
        controller.activityResumed(activity)
        elapsed += 2_000L
        controller.activityPaused(activity)
        elapsed += 9_000L

        assertEquals(7_000L, controller.snapshot().totalMillis)
        assertFalse(controller.isTracking)
    }

    @Test
    fun activityTransitionsDoNotStopOrDoubleCountUsage() {
        val controller = FocusedUsageController(accumulator)
        val home = Any()
        val reader = Any()
        controller.setDeviceState(interactive = true, locked = false)

        controller.activityResumed(home)
        elapsed += 3_000L
        controller.activityResumed(reader)
        controller.activityPaused(home)
        elapsed += 4_000L
        controller.activityPaused(reader)

        assertEquals(7_000L, controller.snapshot().totalMillis)
    }

    @Test
    fun screenOffAndLockPauseUntilTheDeviceIsUsableAgain() {
        val controller = FocusedUsageController(accumulator)
        val activity = Any()
        controller.setDeviceState(interactive = true, locked = false)
        controller.activityResumed(activity)
        elapsed += 2_000L

        controller.setDeviceState(interactive = false, locked = true)
        elapsed += 8_000L
        controller.setDeviceState(interactive = true, locked = true)
        elapsed += 5_000L
        controller.setDeviceState(interactive = true, locked = false)
        assertTrue(controller.isTracking)
        elapsed += 3_000L
        controller.activityPaused(activity)

        assertEquals(5_000L, controller.snapshot().totalMillis)
    }

    @Test
    fun snapshotIncludesTodaySevenDaysAndPersistentAllTime() {
        accumulator.start()
        elapsed += 4_000L
        accumulator.stop()

        epochDay = 94L
        accumulator.start()
        elapsed += 2_000L
        accumulator.stop()

        epochDay = 100L
        val restored = UsageAccumulator(persistence, { elapsed }, { epochDay })
        restored.start()
        elapsed += 3_000L
        val snapshot = restored.snapshot()

        assertEquals(7_000L, snapshot.todayMillis)
        assertEquals(9_000L, snapshot.lastSevenDaysMillis)
        assertEquals(9_000L, snapshot.totalMillis)
    }

    @Test
    fun dailyCheckpointMovesSubsequentUsageToTheNewDay() {
        accumulator.start()
        elapsed += 5_000L
        accumulator.checkpoint()
        epochDay++
        accumulator.checkpoint()
        elapsed += 2_000L
        accumulator.checkpoint()

        assertEquals(5_000L, persistence.dayMillis(100L))
        assertEquals(2_000L, persistence.dayMillis(101L))
    }

    @Test
    fun checkpointsPruneDailyBucketsOlderThanSevenDays() {
        epochDay = 90L
        accumulator.start()
        elapsed += 1_000L
        accumulator.stop()

        epochDay = 100L
        accumulator.start()
        elapsed += 1_000L
        accumulator.checkpoint()

        assertEquals(0L, persistence.dayMillis(90L))
        assertEquals(2_000L, persistence.totalMillis())
    }
}

private class InMemoryUsageStatsPersistence : UsageStatsPersistence {
    private var total = 0L
    private val days = mutableMapOf<Long, Long>()

    override fun totalMillis(): Long = total

    override fun dayMillis(epochDay: Long): Long = days[epochDay] ?: 0L

    override fun add(epochDay: Long, durationMillis: Long) {
        total += durationMillis
        days[epochDay] = dayMillis(epochDay) + durationMillis
    }

    override fun pruneDaysBefore(earliestEpochDay: Long) {
        days.keys.removeAll { it < earliestEpochDay }
    }
}
