// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStatsDialogTest {
    @Test
    fun usageDurationsUseReadableMinuteAndHourBoundaries() {
        assertEquals(
            UsageDurationParts(UsageDurationKind.MINUTES, minutes = 0),
            usageDurationParts(0L),
        )
        assertEquals(
            UsageDurationParts(UsageDurationKind.UNDER_MINUTE),
            usageDurationParts(59_999L),
        )
        assertEquals(
            UsageDurationParts(UsageDurationKind.MINUTES, minutes = 1),
            usageDurationParts(60_000L),
        )
        assertEquals(
            UsageDurationParts(UsageDurationKind.HOURS_MINUTES, hours = 2, minutes = 5),
            usageDurationParts(7_500_000L),
        )
    }

    @Test
    fun detailedReadingDurationsIncludeSeconds() {
        assertEquals(
            DetailedUsageDurationParts(DetailedUsageDurationKind.SECONDS, seconds = 0),
            detailedUsageDurationParts(0L),
        )
        assertEquals(
            DetailedUsageDurationParts(DetailedUsageDurationKind.UNDER_SECOND),
            detailedUsageDurationParts(999L),
        )
        assertEquals(
            DetailedUsageDurationParts(DetailedUsageDurationKind.SECONDS, seconds = 30),
            detailedUsageDurationParts(30_000L),
        )
        assertEquals(
            DetailedUsageDurationParts(
                DetailedUsageDurationKind.MINUTES_SECONDS,
                minutes = 12,
                seconds = 34,
            ),
            detailedUsageDurationParts(754_000L),
        )
        assertEquals(
            DetailedUsageDurationParts(
                DetailedUsageDurationKind.HOURS_MINUTES,
                hours = 2,
                minutes = 5,
            ),
            detailedUsageDurationParts(7_500_000L),
        )
    }
}
