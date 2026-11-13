// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReviewIntervalTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test fun suggestionsStartAtTwoWeeksAndDoubleThePreviousDelay() {
        val now = Instant.parse("2026-09-06T09:00:00Z")
        assertEquals(ReviewInterval(2, ReviewIntervalUnit.WEEKS), ReviewInterval.suggested(null, null, paris))
        assertEquals(ReviewInterval(5, ReviewIntervalUnit.WEEKS),
            ReviewInterval.suggested(now, now.plusSeconds(17 * 86400 + 12 * 3600), paris))
        assertEquals(ReviewInterval(20, ReviewIntervalUnit.MINUTES),
            ReviewInterval.suggested(now, now.plusSeconds(600), paris))
        assertEquals(ReviewInterval(3, ReviewIntervalUnit.HOURS),
            ReviewInterval.suggested(now, now.plusSeconds(5400), paris))
        assertEquals(ReviewInterval(2, ReviewIntervalUnit.WEEKS), ReviewInterval.suggested(now, now, paris))
    }

    @Test fun currentIntervalIsTheDelayTheLastReviewSet() {
        val now = Instant.parse("2026-09-06T09:00:00Z")
        assertEquals(null, ReviewInterval.between(null, null, paris))
        assertEquals(null, ReviewInterval.between(now, now, paris))
        assertEquals(ReviewInterval(4, ReviewIntervalUnit.DAYS), ReviewInterval.between(now, now.plusSeconds(4 * 86400), paris))
        assertEquals(ReviewInterval(10, ReviewIntervalUnit.MINUTES), ReviewInterval.between(now, now.plusSeconds(600), paris))
    }

    @Test fun longIntervalsReadAsDaysAndWeeks() {
        // 419 hours by the clock, as a card had when the clocks changed: not 838 h next.
        val reviewed = Instant.parse("2026-10-07T14:52:39Z")
        val due = Instant.parse("2026-10-25T02:52:39Z")
        assertEquals(ReviewInterval(17, ReviewIntervalUnit.DAYS), ReviewInterval.between(reviewed, due, paris))
        assertEquals(ReviewInterval(5, ReviewIntervalUnit.WEEKS), ReviewInterval.suggested(reviewed, due, paris))
        assertEquals(ReviewInterval(153, ReviewIntervalUnit.DAYS), ReviewInterval.readable(java.math.BigDecimal(3673 * 3_600_000L)))
        // Below two days it stays exact.
        assertEquals(ReviewInterval(30, ReviewIntervalUnit.HOURS), ReviewInterval.readable(java.math.BigDecimal(30 * 3_600_000L)))
        assertEquals(ReviewInterval(1, ReviewIntervalUnit.DAYS), ReviewInterval.readable(java.math.BigDecimal(86_400_000L)))
    }

    @Test fun suggestionsKeepCalendarWeeksAcrossDaylightSaving() {
        val now = Instant.parse("2026-03-28T11:00:00Z")
        val due = ReviewInterval(2, ReviewIntervalUnit.WEEKS).dueAt(now, paris)
        assertEquals(ReviewInterval(4, ReviewIntervalUnit.WEEKS), ReviewInterval.suggested(now, due, paris))
    }

    @Test fun monthsClampToTheLastDayAndPreserveLocalTime() {
        assertEquals(Instant.parse("2028-02-29T11:00:00Z"),
            ReviewInterval(1, ReviewIntervalUnit.MONTHS).dueAt(Instant.parse("2028-01-31T11:00:00Z"), paris))
    }

    @Test fun calendarDaysAndWeeksPreserveLocalTimeAcrossDaylightSaving() {
        val now = Instant.parse("2026-03-28T11:00:00Z")
        assertEquals(Instant.parse("2026-03-29T10:00:00Z"), ReviewInterval().dueAt(now, paris))
        assertEquals(Instant.parse("2026-04-04T10:00:00Z"), ReviewInterval(1, ReviewIntervalUnit.WEEKS).dueAt(now, paris))
        assertEquals(now.plusSeconds(86400), ReviewInterval(24, ReviewIntervalUnit.HOURS).dueAt(now, paris))
        assertEquals(now.plusSeconds(180), ReviewInterval(3, ReviewIntervalUnit.MINUTES).dueAt(now, paris))
    }

    @Test fun explicitDatePreservesLocalTimeAndRejectsTodayOrPastDates() {
        val now = Instant.parse("2026-09-06T11:23:00Z")
        assertEquals(Instant.parse("2026-11-09T12:23:00Z"),
            ReviewInterval(date = LocalDate.parse("2026-11-09")).dueAt(now, paris))
        for (date in listOf("2026-09-06", "2026-09-05")) {
            assertThrows(IllegalArgumentException::class.java) {
                ReviewInterval(date = LocalDate.parse(date)).dueAt(now, paris)
            }
        }
    }

    @Test fun fractionalWeeksAndHoursKeepTheFraction() {
        val now = Instant.parse("2026-09-06T09:00:00Z")
        assertEquals(now.plusSeconds(10 * 86400 + 12 * 3600),
            ReviewInterval(BigDecimal("1.5"), ReviewIntervalUnit.WEEKS).dueAt(now, paris))
        assertEquals(now.plusSeconds(5400), ReviewInterval(BigDecimal("1.5"), ReviewIntervalUnit.HOURS).dueAt(now, paris))
        assertEquals(now.plusSeconds(30), ReviewInterval(BigDecimal("0.5"), ReviewIntervalUnit.MINUTES).dueAt(now, paris))
        assertEquals(now.plusMillis(741), ReviewInterval(BigDecimal("0.012345"), ReviewIntervalUnit.MINUTES).dueAt(now, paris))
    }

    @Test fun fractionsOfMonthsUseCalendarAnniversaries() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        assertEquals(Instant.parse("2026-02-15T12:00:00Z"),
            ReviewInterval(BigDecimal("1.5"), ReviewIntervalUnit.MONTHS).dueAt(now, ZoneId.of("UTC")))
    }

    @Test fun parsesCommaAndDotWithoutSilentlyTruncatingOrAcceptingGarbage() {
        for (text in listOf("1.5", "1,5", " 1.500 ")) assertEquals(BigDecimal("1.5"), ReviewInterval.parseAmount(text))
        assertEquals(BigDecimal("0.5"), ReviewInterval.parseAmount(".5"))
        assertEquals(BigDecimal("0.5"), ReviewInterval.parseAmount(",5"))
        for (text in listOf("", "0", "-1", "NaN", "1,2.3", "1/2", "1e9")) assertNull(ReviewInterval.parseAmount(text))
    }

    @Test fun invalidAndOverflowingAmountsAreRejected() {
        for (amount in listOf(0L, -1L, Long.MAX_VALUE)) {
            assertThrows(RuntimeException::class.java) {
                ReviewInterval(amount, ReviewIntervalUnit.MONTHS).dueAt(Instant.now(), paris)
            }
        }
    }
}
