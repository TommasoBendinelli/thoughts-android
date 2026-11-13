// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.absoluteValue

val appDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
val appDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val pdfDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss")

fun pdfDateNow(): String = formatPdfDate(OffsetDateTime.now())

internal fun formatPdfDate(dateTime: OffsetDateTime): String {
    val base = dateTime.format(pdfDateFormatter)
    val offsetSeconds = dateTime.offset.totalSeconds
    if (offsetSeconds == 0) return "${base}Z"

    val offsetMinutes = offsetSeconds.absoluteValue / 60
    val sign = if (offsetSeconds < 0) "-" else "+"
    return String.format(
        Locale.ROOT,
        "%s%s%02d'%02d'",
        base,
        sign,
        offsetMinutes / 60,
        offsetMinutes % 60,
    )
}

fun formatRelativeDate(context: Context, dateTime: LocalDateTime): String {
    val date = dateTime.toLocalDate()
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0L -> context.getString(R.string.home_relative_today)
        days == 1L -> context.getString(R.string.home_relative_yesterday)
        days < 7L -> context.getString(R.string.home_relative_days_ago, days)
        days < 30L -> {
            val weeks = (days / 7L).toInt()
            context.resources.getQuantityString(R.plurals.home_relative_weeks_ago, weeks, weeks)
        }
        days < 365L -> {
            val months = ChronoUnit.MONTHS.between(date, today).toInt().coerceAtLeast(1)
            context.resources.getQuantityString(R.plurals.home_relative_months_ago, months, months)
        }
        else -> {
            val years = ChronoUnit.YEARS.between(date, today).toInt().coerceAtLeast(1)
            context.resources.getQuantityString(R.plurals.home_relative_years_ago, years, years)
        }
    }
}

fun convertDateString(input: String?): String? {
    val parsed = parsePdfDateTimeWithPrecision(input) ?: return null
    val formatter = if (parsed.second) appDateTimeFormatter else appDateFormatter
    return parsed.first.format(formatter)
}

fun parsePdfDateTime(input: String?): LocalDateTime? = parsePdfDateTimeWithPrecision(input)?.first

/**
 * Parses sidecar timestamps to an instant. PDF dates without an explicit offset are legacy local
 * wall-clock values, as required by the PDF convention; new writers always include an offset.
 */
fun parseCommentTimestamp(input: String?): Instant? {
    if (input.isNullOrBlank()) return null
    val value = input.trim()
    if (value.startsWith("D:")) {
        val parsed = parsePdfDateParts(value) ?: return null
        return parsed.offset?.let(parsed.dateTime::toInstant)
            ?: parsed.dateTime.atZone(ZoneId.systemDefault()).toInstant()
    }
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant()
        }.getOrNull()
}

private data class ParsedPdfDate(
    val dateTime: LocalDateTime,
    val hasTime: Boolean,
    val offset: ZoneOffset?,
)

private fun parsePdfDateTimeWithPrecision(input: String?): Pair<LocalDateTime, Boolean>? {
    val parsed = parsePdfDateParts(input) ?: return null
    val localized = if (parsed.offset != null && parsed.hasTime) {
        parsed.dateTime
            .atOffset(parsed.offset)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
    } else {
        parsed.dateTime
    }
    return localized to parsed.hasTime
}

private fun parsePdfDateParts(input: String?): ParsedPdfDate? {
    if (input.isNullOrBlank()) return null
    return try {
        val value = input.trim().removePrefix("D:")
        val digits = value.takeWhile { it.isDigit() }
        if (digits.length < 4) return null

        fun component(start: Int, end: Int, default: Int): Int =
            if (digits.length >= end) digits.substring(start, end).toInt() else default

        val year = digits.substring(0, 4).toInt()
        val month = component(4, 6, 1).coerceIn(1, 12)
        val day = component(6, 8, 1).coerceIn(1, YearMonth.of(year, month).lengthOfMonth())
        val hour = component(8, 10, 0).coerceIn(0, 23)
        val minute = component(10, 12, 0).coerceIn(0, 59)
        val second = component(12, 14, 0).coerceIn(0, 59)
        val hasTime = digits.length >= 10

        val dateTime = LocalDateTime.of(year, month, day, hour, minute, second)
        val offset = parsePdfUtcOffset(value.substring(digits.length))
        ParsedPdfDate(dateTime, hasTime, offset)
    }
    catch (throwable: Throwable) {
        null
    }
}

private fun parsePdfUtcOffset(zone: String): ZoneOffset? {
    val trimmed = zone.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("Z")) return ZoneOffset.UTC

    val sign = when (trimmed.first()) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }
    val numbers = trimmed.drop(1).filter { it.isDigit() }
    if (numbers.length < 2) return null

    val hours = numbers.substring(0, 2).toIntOrNull() ?: return null
    val minutes = if (numbers.length >= 4) numbers.substring(2, 4).toIntOrNull() ?: 0 else 0
    return runCatching { ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes) }.getOrNull()
}
