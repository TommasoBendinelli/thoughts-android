// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import java.time.LocalDate

private val UNSAFE_FILENAME_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

/** "Thoughts cards - <project> - <date>.csv", with characters file pickers reject replaced. */
internal fun reviewCardCsvFileName(scope: String?, date: LocalDate): String {
    val label = scope?.trim()?.replace(UNSAFE_FILENAME_CHARACTERS, "_")?.trim(' ', '.')?.takeIf(String::isNotBlank)
    return listOfNotNull("Thoughts cards", label, date.toString()).joinToString(" - ") + ".csv"
}

/** RFC 4180 CSV: every field quoted, CRLF line ends, so spreadsheets keep multi-line answers in one cell. */
internal fun reviewCardCsv(header: List<String>, rows: List<List<String>>): String = buildString {
    (listOf(header) + rows).forEach { row ->
        row.joinTo(this, ",") { field -> "\"" + field.replace("\"", "\"\"") + "\"" }
        append("\r\n")
    }
}
