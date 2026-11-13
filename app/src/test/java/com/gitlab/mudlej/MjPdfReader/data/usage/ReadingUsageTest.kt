// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.usage

import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsageDocument
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsagePageDay
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingUsagePageTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingUsageTest {
    @Test
    fun durationIsSplitExactlyAcrossDistinctVisiblePages() {
        val shares = splitReadingDuration(1_001L, listOf(6, 5, 6, -1))

        assertEquals(mapOf(5 to 501L, 6 to 500L), shares)
        assertEquals(1_001L, shares.values.sum())
        assertTrue(splitReadingDuration(0L, listOf(1)).isEmpty())
        assertTrue(splitReadingDuration(1_000L, emptyList()).isEmpty())
    }

    @Test
    fun changingPagesCheckpointsThePreviousReadingContext() {
        var elapsed = 1_000L
        var epochDay = 100L
        var wallClock = 10_000L
        val intervals = mutableListOf<ReadingUsageInterval>()
        val accumulator = ReadingUsageAccumulator(
            persist = intervals::add,
            elapsedRealtime = { elapsed },
            currentEpochDay = { epochDay },
            currentTimeMillis = { wallClock },
        )
        val pageOne = context(visiblePages = listOf(0))
        val pageTwo = context(visiblePages = listOf(1))

        accumulator.setContext(pageOne)
        elapsed += 2_500L
        accumulator.setContext(pageOne)
        elapsed += 500L
        wallClock += 3_000L
        accumulator.setContext(pageTwo)
        elapsed += 4_000L
        epochDay++
        wallClock += 4_000L
        accumulator.setContext(null)

        assertEquals(2, intervals.size)
        assertEquals(pageOne, intervals[0].context)
        assertEquals(3_000L, intervals[0].durationMillis)
        assertEquals(100L, intervals[0].epochDay)
        assertEquals(pageTwo, intervals[1].context)
        assertEquals(4_000L, intervals[1].durationMillis)
        assertEquals(100L, intervals[1].epochDay)
        assertFalse(accumulator.isActive)
    }

    @Test
    fun controllerCountsOnlyResumedInteractiveUnlockedReaderTime() {
        var elapsed = 0L
        val intervals = mutableListOf<ReadingUsageInterval>()
        val accumulator = ReadingUsageAccumulator(
            persist = intervals::add,
            elapsedRealtime = { elapsed },
            currentEpochDay = { 100L },
            currentTimeMillis = { 1_000L + elapsed },
        )
        val controller = FocusedReadingUsageController(accumulator)
        val reader = Any()
        controller.setContext(reader, context(visiblePages = listOf(0, 1)))

        controller.setDeviceState(interactive = true, locked = false)
        controller.activityResumed(reader)
        elapsed += 5_000L
        controller.setDeviceState(interactive = false, locked = true)
        elapsed += 9_000L
        controller.setDeviceState(interactive = true, locked = false)
        elapsed += 3_000L
        controller.activityPaused(reader)

        assertEquals(2, intervals.size)
        assertEquals(8_000L, intervals.sumOf { it.durationMillis })
        assertFalse(controller.isTracking)
    }

    @Test
    fun snapshotBuildsDocumentAndPageDurationsForAllRanges() {
        val snapshot = buildReadingUsageSnapshot(
            documents = listOf(
                ReadingUsageDocument("a", "uri:a", "Alpha", 3, 50L),
                ReadingUsageDocument("b", "uri:b", "Beta", 1, 40L),
            ),
            totals = listOf(
                ReadingUsagePageTotal("a", 0, 9_000L),
                ReadingUsagePageTotal("a", 1, 7_000L),
                ReadingUsagePageTotal("b", 0, 2_000L),
            ),
            days = listOf(
                ReadingUsagePageDay("a", 0, 100L, 3_000L),
                ReadingUsagePageDay("a", 0, 99L, 2_000L),
                ReadingUsagePageDay("a", 1, 94L, 4_000L),
                ReadingUsagePageDay("b", 0, 100L, 1_000L),
            ),
            todayEpochDay = 100L,
        )

        val alpha = snapshot.documents.first { it.documentKey == "a" }
        assertEquals(listOf(0, 1), alpha.pages.map { it.pageIndex })
        assertEquals(3_000L, alpha.duration(ReadingUsageRange.TODAY))
        assertEquals(9_000L, alpha.duration(ReadingUsageRange.LAST_SEVEN_DAYS))
        assertEquals(16_000L, alpha.duration(ReadingUsageRange.ALL_TIME))
        assertEquals(5_000L, alpha.pages[0].lastSevenDaysMillis)
        assertEquals(4_000L, alpha.pages[1].lastSevenDaysMillis)
    }

    private fun context(visiblePages: List<Int>) = ReadingUsageContext(
        documentKey = "document",
        uri = "content://document",
        title = "Document",
        pageCount = 10,
        visiblePages = visiblePages,
    )
}
