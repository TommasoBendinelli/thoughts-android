// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import kotlin.math.ln
import kotlin.math.roundToInt

/** The Skip dialog's "Random" never postpones by less than this many days. */
internal const val RANDOM_SKIP_MINIMUM_DAYS = 2.0

/** Exponential rate per day beyond the minimum: 95% of random skips end within 14 days. */
internal val RANDOM_SKIP_RATE_PER_DAY = ln(20.0) / 12.0

/**
 * The delay "Random" skips by, in days, for [u] uniform in [0, 1): 2 + E with E exponential
 * (rate ln(20)/12 per day), sampled as -ln(1 - u)/rate. The desktop uses the same formula.
 */
internal fun randomSkipDelayDays(u: Double): Double {
    require(u >= 0.0 && u < 1.0) { "u must be in [0, 1)" }
    return RANDOM_SKIP_MINIMUM_DAYS - ln(1.0 - u) / RANDOM_SKIP_RATE_PER_DAY
}

/**
 * The whole number of days "Random" puts in the Skip dialog's interval fields: the sampled delay
 * rounded to the nearest day, at least [RANDOM_SKIP_MINIMUM_DAYS]. [uniform] returns u in [0, 1).
 */
internal fun randomSkipDays(uniform: () -> Double = { kotlin.random.Random.nextDouble() }): Int =
    randomSkipDelayDays(uniform()).roundToInt().coerceAtLeast(RANDOM_SKIP_MINIMUM_DAYS.toInt())
