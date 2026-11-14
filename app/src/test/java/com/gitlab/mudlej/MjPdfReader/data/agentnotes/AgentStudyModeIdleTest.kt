// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStudyModeIdleTest {
    private val minute = 60_000L
    private val evening = 1_800_000_000_000L

    @Test
    fun theListenerGoesOnForSixtyMinutesAfterTheLastUse() {
        assertEquals(60 * minute, STUDY_MODE_IDLE_MILLIS)
        assertEquals(60 * minute, studyListenerRemainingMillis(evening, evening))
        assertEquals(1 * minute, studyListenerRemainingMillis(evening, evening + 59 * minute))
        assertEquals(0, studyListenerRemainingMillis(evening, evening + 60 * minute))
        assertEquals(0, studyListenerRemainingMillis(evening, evening + 8 * 60 * minute))
    }

    @Test
    fun aCaptureAReplyANoteOrTheScreenComingOnStartsTheHourAgain() {
        // Used at 21:00 and again at 21:50: at 22:30 it still listens, at 22:50 it does not.
        val secondUse = evening + 50 * minute
        assertEquals(20 * minute, studyListenerRemainingMillis(secondUse, evening + 90 * minute))
        assertEquals(0, studyListenerRemainingMillis(secondUse, evening + 110 * minute))
    }

    @Test
    fun neverUsedOrAClockSetBackNeverKeepsItLonger() {
        assertEquals(0, studyListenerRemainingMillis(0, evening))
        // The clock was set back a day after the last use: an hour at most, not a day and an hour.
        assertEquals(60 * minute, studyListenerRemainingMillis(evening, evening - 24 * 60 * minute))
    }

    /**
     * The night of 13 November 2026: last used at 23:00, screen off, and the listener held a
     * 25-second long poll until Android's six-hour cap. It now stops after the idle hour.
     */
    @Test
    fun aNightWithTheScreenOffPollsForAnHourNotForSixHours() {
        val pollMillis = 25_000L
        val sixHourCap = 6 * 60 * minute
        fun polls(listens: (sinceLastUse: Long) -> Boolean): Int {
            var count = 0
            var sinceLastUse = 0L
            while (sinceLastUse < sixHourCap && listens(sinceLastUse)) {
                count++
                sinceLastUse += pollMillis
            }
            return count
        }
        val before = polls { true }
        val now = polls { since -> studyListenerRemainingMillis(evening, evening + since) > 0 }
        assertEquals(864, before)
        assertEquals(144, now)
    }
}
