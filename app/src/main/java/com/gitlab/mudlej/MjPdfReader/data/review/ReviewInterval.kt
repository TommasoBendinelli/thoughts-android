// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ReviewIntervalUnit { MINUTES, HOURS, DAYS, WEEKS, MONTHS }

data class ReviewInterval(
    val amount: BigDecimal = BigDecimal.ONE,
    val unit: ReviewIntervalUnit = ReviewIntervalUnit.DAYS,
    val date: LocalDate? = null,
) {
    constructor(amount: Long, unit: ReviewIntervalUnit = ReviewIntervalUnit.DAYS, date: LocalDate? = null) :
        this(BigDecimal.valueOf(amount), unit, date)

    fun dueAt(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant {
        require(amount.signum() > 0) { "Enter a positive number" }
        val local = now.atZone(zone)
        val due = if (date != null) date.atTime(local.toLocalTime()).atZone(zone).toInstant()
        else when (unit) {
            ReviewIntervalUnit.MINUTES -> now.plusMillis(amount.multiply(BigDecimal(60_000)).setScale(0, RoundingMode.HALF_UP).longValueExact())
            ReviewIntervalUnit.HOURS -> now.plusMillis(amount.multiply(BigDecimal(3_600_000)).setScale(0, RoundingMode.HALF_UP).longValueExact())
            ReviewIntervalUnit.DAYS, ReviewIntervalUnit.WEEKS -> {
                val days = if (unit == ReviewIntervalUnit.WEEKS) amount.multiply(BigDecimal(7)) else amount
                val whole = days.toBigInteger().longValueExact()
                val fraction = days.subtract(BigDecimal.valueOf(whole))
                local.plusDays(whole).toInstant().plusMillis(fraction.multiply(BigDecimal(86_400_000)).setScale(0, RoundingMode.HALF_UP).longValueExact())
            }
            ReviewIntervalUnit.MONTHS -> {
                val whole = amount.toBigInteger().longValueExact()
                val base = local.plusMonths(whole).toInstant()
                val fraction = amount.subtract(BigDecimal.valueOf(whole))
                if (fraction.signum() == 0) base else {
                    // Fractions interpolate between calendar-month anniversaries, including month-end clamping.
                    val next = local.plusMonths(Math.addExact(whole, 1)).toInstant()
                    base.plusMillis(fraction.multiply(BigDecimal.valueOf(Duration.between(base, next).toMillis())).setScale(0, RoundingMode.HALF_UP).longValueExact())
                }
            }
        }
        due.toEpochMilli() // Keep persisted times inside the range supported by Android date controls.
        require(due.isAfter(now)) { "Choose a future review time" }
        return due
    }

    companion object {
        fun suggested(
            reviewedAt: Instant?,
            dueAt: Instant?,
            zone: ZoneId = ZoneId.systemDefault(),
        ): ReviewInterval = scaled(reviewedAt, dueAt, zone, 2) ?: ReviewInterval(2, ReviewIntervalUnit.WEEKS)

        /** The interval a review set, from its time to its due time; null without one. */
        fun between(reviewedAt: Instant?, dueAt: Instant?, zone: ZoneId = ZoneId.systemDefault()): ReviewInterval? =
            scaled(reviewedAt, dueAt, zone, 1)

        private fun scaled(reviewedAt: Instant?, dueAt: Instant?, zone: ZoneId, factor: Int): ReviewInterval? {
            if (reviewedAt == null || dueAt == null || !dueAt.isAfter(reviewedAt)) return null
            // Calendar intervals keep their local length across daylight-saving changes.
            val localDuration = Duration.between(
                reviewedAt.atZone(zone).toLocalDateTime(), dueAt.atZone(zone).toLocalDateTime(),
            )
            val duration = if (localDuration >= Duration.ofDays(1)) localDuration
                else Duration.between(reviewedAt, dueAt)
            // Whole minutes keep odd server due times from showing a long decimal amount.
            val scaledMillis = BigDecimal.valueOf(duration.toMillis()).multiply(BigDecimal(factor))
                .divide(BigDecimal(60_000), 0, RoundingMode.HALF_UP).max(BigDecimal.ONE).multiply(BigDecimal(60_000))
            return readable(scaledMillis)
        }

        /**
         * A length of time as a person says it: from two days on in whole days, or weeks when it is
         * a whole number of them, so 838 hours is 5 weeks; below that as exact as it is. The desktop
         * does the same.
         */
        fun readable(millis: BigDecimal): ReviewInterval {
            val day = BigDecimal.valueOf(86_400_000L)
            if (millis >= day.multiply(BigDecimal(2))) {
                val days = millis.divide(day, 0, RoundingMode.HALF_UP).toLong()
                return if (days % 7 == 0L) ReviewInterval(days / 7, ReviewIntervalUnit.WEEKS) else ReviewInterval(days, ReviewIntervalUnit.DAYS)
            }
            if (millis.compareTo(day) == 0) return ReviewInterval(1, ReviewIntervalUnit.DAYS)
            for ((unit, unitMillis) in listOf(ReviewIntervalUnit.HOURS to 3_600_000L, ReviewIntervalUnit.MINUTES to 60_000L)) {
                val amount = runCatching { millis.divide(BigDecimal.valueOf(unitMillis)).stripTrailingZeros() }
                    .getOrNull() ?: continue
                if (amount >= BigDecimal.ONE && amount.scale() <= 3) return ReviewInterval(amount.setScale(amount.scale().coerceAtLeast(0)), unit)
            }
            return ReviewInterval(millis.divide(BigDecimal(60_000), 12, RoundingMode.HALF_UP), ReviewIntervalUnit.MINUTES)
        }

        fun parseAmount(text: String): BigDecimal? {
            val normalized = text.trim().replace(',', '.')
            if (!normalized.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
            // Keep "10" as 10 rather than 1E+1, so equal amounts compare equal.
            return normalized.toBigDecimalOrNull()?.stripTrailingZeros()?.takeIf { it.signum() > 0 }
                ?.let { if (it.scale() < 0) it.setScale(0) else it }
        }
    }
}
