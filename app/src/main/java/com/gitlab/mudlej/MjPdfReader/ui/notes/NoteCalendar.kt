// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun NoteDetails.calendarLabel(): String {
    val date = runCatching { LocalDate.parse(calendarDate) }.getOrNull() ?: return ""
    val time = runCatching { LocalTime.parse(calendarTime) }.getOrNull()
    return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) +
        (time?.let { " · " + it.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) } ?: "")
}

internal fun NoteDetails.visibleFromLabel(): String =
    runCatching { LocalDate.parse(visibleFrom) }.getOrNull()
        ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)).orEmpty()
