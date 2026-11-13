// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewCardCsvExportTest {

    @Test
    fun `csv quotes every field and keeps commas, quotes and line breaks inside cells`() {
        assertEquals(
            "\"Question\",\"Answer\"\r\n" +
                "\"What is \"\"GRPO\"\"?\",\"Group-relative,\nno critic\"\r\n",
            reviewCardCsv(
                listOf("Question", "Answer"),
                listOf(listOf("What is \"GRPO\"?", "Group-relative,\nno critic")),
            ),
        )
    }

    @Test
    fun `file name names the project and date and drops characters pickers reject`() {
        val date = LocalDate.of(2026, 10, 4)
        assertEquals("Thoughts cards - 2026-10-04.csv", reviewCardCsvFileName(null, date))
        assertEquals("Thoughts cards - 2026-10-04.csv", reviewCardCsvFileName("  ", date))
        assertEquals(
            "Thoughts cards - Benchmark_followup - 2026-10-04.csv",
            reviewCardCsvFileName("Benchmark/followup", date),
        )
    }
}
