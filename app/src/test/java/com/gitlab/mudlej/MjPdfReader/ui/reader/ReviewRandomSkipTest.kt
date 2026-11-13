// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class ReviewRandomSkipTest {
    @Test fun neverSkipsLessThanTwoDays() {
        assertEquals(2.0, randomSkipDelayDays(0.0), 0.0)
        listOf(1e-12, 0.01, 0.3, 0.5, 0.9, 0.999999).forEach { assertTrue(randomSkipDelayDays(it) > 2.0) }
    }

    @Test fun ninetyFivePercentEndWithinFourteenDays() {
        assertEquals(ln(20.0) / 12.0, RANDOM_SKIP_RATE_PER_DAY, 0.0)
        assertEquals(14.0, randomSkipDelayDays(0.95), 1e-9)
        // The CDF at 14 days: 1 - exp(-rate * 12) = 0.95.
        assertEquals(0.95, 1 - exp(-RANDOM_SKIP_RATE_PER_DAY * 12.0), 1e-12)
        assertEquals(2.0 + ln(2.0) / RANDOM_SKIP_RATE_PER_DAY, randomSkipDelayDays(0.5), 1e-9)
    }

    /** u giving a delay of exactly [days]. */
    private fun uFor(days: Double) = 1 - exp(-RANDOM_SKIP_RATE_PER_DAY * (days - 2.0))

    @Test fun theIntervalIsTheDelayRoundedToWholeDays() {
        assertEquals(2, randomSkipDays { 0.0 })
        assertEquals(14, randomSkipDays { 0.95 })
        assertEquals(2, randomSkipDays { uFor(2.4) })
        assertEquals(3, randomSkipDays { uFor(2.6) })
        assertEquals(7, randomSkipDays { uFor(6.51) })
        assertEquals(6, randomSkipDays { uFor(6.49) })
        // Never below the minimum, even right at it.
        assertEquals(2, randomSkipDays { uFor(2.0) })
    }

    @Test fun eachDrawUsesTheRandomSource() {
        val draws = ArrayDeque(listOf(0.0, 0.95))
        assertEquals(2, randomSkipDays { draws.removeFirst() })
        assertEquals(14, randomSkipDays { draws.removeFirst() })
        assertTrue(draws.isEmpty())
    }
}
