// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DateUtilTest {

    @Test
    fun pdfDatesIncludeTheirUtcOffset() {
        assertEquals(
            "D:20260726092114+02'00'",
            formatPdfDate(
                OffsetDateTime.of(2026, 7, 26, 9, 21, 14, 0, ZoneOffset.ofHours(2)),
            ),
        )
        assertEquals(
            "D:20260726072114Z",
            formatPdfDate(
                OffsetDateTime.of(2026, 7, 26, 7, 21, 14, 0, ZoneOffset.UTC),
            ),
        )
        assertEquals(
            "D:20260726015114-05'30'",
            formatPdfDate(
                OffsetDateTime.of(
                    2026,
                    7,
                    26,
                    1,
                    51,
                    14,
                    0,
                    ZoneOffset.ofHoursMinutes(-5, -30),
                ),
            ),
        )
    }

    @Test
    fun timestampParserRespectsOffsetsAndTreatsLegacyDatesAsLocalTime() {
        val previousTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))

            val expected = Instant.parse("2026-07-26T07:21:14Z")
            assertEquals(expected, parseCommentTimestamp("D:20260726092114+02'00'"))
            assertEquals(expected, parseCommentTimestamp("D:20260726072114Z"))
            assertEquals(expected, parseCommentTimestamp("D:20260726092114"))
            assertEquals(expected, parseCommentTimestamp("2026-07-26T09:21:14"))
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }
}
