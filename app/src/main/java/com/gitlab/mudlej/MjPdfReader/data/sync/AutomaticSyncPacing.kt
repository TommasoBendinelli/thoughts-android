// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

/** The spacing an automatic Sync keeps from the previous one the first time it repeats. */
internal const val AUTOMATIC_SYNC_FIRST_WAIT_MILLIS = 20_000L

/** The longest spacing while automatic Syncs succeed. */
internal const val AUTOMATIC_SYNC_LONGEST_WAIT_MILLIS = 2 * 60_000L

/** The longest spacing while the previous automatic Sync did not succeed. */
internal const val AUTOMATIC_SYNC_LONGEST_FAILING_WAIT_MILLIS = 10 * 60_000L

/** The time without an automatic Sync being wanted after which the spacing starts again. */
internal const val AUTOMATIC_SYNC_QUIET_MILLIS = 5 * 60_000L

/**
 * How the Syncs that [RemoteWorkWatch] starts for a changed server state are spaced. Times are
 * wall-clock milliseconds, as the Sync records keep them.
 */
internal data class AutomaticSyncPace(
    /** When the previous automatic Sync ended; null before any. */
    val lastEndedAtMillis: Long? = null,
    /** The spacing the next automatic Sync keeps from that end. */
    val waitMillis: Long = AUTOMATIC_SYNC_FIRST_WAIT_MILLIS,
    /** Since when an automatic Sync is wanted but held back; null when none is. */
    val heldSinceMillis: Long? = null,
    /** Whether the previous automatic Sync succeeded; a failing one lets the spacing grow further. */
    val previousSucceeded: Boolean = true,
)

internal data class AutomaticSyncStart(
    val startNow: Boolean,
    /** When a held Sync may start; the present when it starts now. */
    val notBeforeMillis: Long,
    val pace: AutomaticSyncPace,
)

/** The longest spacing [pace] may reach: 2 minutes, or 10 while the previous automatic Sync failed. */
private fun longestWaitMillis(pace: AutomaticSyncPace): Long =
    if (pace.previousSucceeded) AUTOMATIC_SYNC_LONGEST_WAIT_MILLIS else AUTOMATIC_SYNC_LONGEST_FAILING_WAIT_MILLIS

/** When the Sync that [pace] holds back may start; null when none is held. */
internal fun automaticSyncHeldUntilMillis(pace: AutomaticSyncPace): Long? {
    if (pace.heldSinceMillis == null) return null
    val ended = pace.lastEndedAtMillis ?: return null
    return ended + pace.waitMillis.coerceIn(AUTOMATIC_SYNC_FIRST_WAIT_MILLIS, longestWaitMillis(pace))
}

/**
 * Whether an automatic Sync for a changed server state, wanted at [nowMillis], starts now. The
 * user looks at the phone while this decides: nobody should wait minutes for a proposal.
 * - The first one after a quiet period starts at once.
 * - One wanted sooner than the current spacing after the previous automatic Sync ended waits
 *   until that spacing has passed. While automatic Syncs succeed, each such wait doubles the
 *   spacing for the next: 20 s, 40 s, 80 s and at most 2 minutes.
 * - While the previous automatic Sync did not succeed, each further one that fails doubles the
 *   spacing ([afterAutomaticSync]), up to 10 minutes; the first that succeeds brings it back to
 *   at most 2 minutes.
 * - When no automatic Sync was wanted for 5 minutes after the previous one ended, the spacing
 *   starts again at 20 seconds.
 * A Sync the user starts never passes through here: it is neither delayed nor counted. Nor does
 * the Sync that fetches the PDFs of finished builds: it starts at once, and only its end counts.
 */
internal fun automaticSyncStart(pace: AutomaticSyncPace, nowMillis: Long): AutomaticSyncStart {
    // A clock set back would hold a Sync for an unknown time: treat it as a quiet period.
    val ended = pace.lastEndedAtMillis?.takeIf { it <= nowMillis }
        ?: return AutomaticSyncStart(true, nowMillis, AutomaticSyncPace(previousSucceeded = pace.previousSucceeded))
    val wantedAt = pace.heldSinceMillis?.takeIf { it in ended..nowMillis } ?: nowMillis
    if (wantedAt - ended >= AUTOMATIC_SYNC_QUIET_MILLIS) {
        return AutomaticSyncStart(true, nowMillis, AutomaticSyncPace(ended, previousSucceeded = pace.previousSucceeded))
    }
    val longest = longestWaitMillis(pace)
    val wait = pace.waitMillis.coerceIn(AUTOMATIC_SYNC_FIRST_WAIT_MILLIS, longest)
    val notBefore = ended + wait
    if (nowMillis < notBefore) {
        return AutomaticSyncStart(false, notBefore, pace.copy(waitMillis = wait, heldSinceMillis = wantedAt))
    }
    // After a failure the spacing has already grown when that Sync ended; it is not doubled twice.
    val nextWait = if (wantedAt < notBefore && pace.previousSucceeded) (wait * 2).coerceAtMost(longest) else wait
    return AutomaticSyncStart(true, nowMillis, pace.copy(waitMillis = nextWait, heldSinceMillis = null))
}

/**
 * The pace once an automatic Sync has ended at [endedAtMillis]. One that succeeded brings the
 * spacing back to at most 2 minutes; one that did not doubles it, up to 10 minutes, so a Sync that
 * keeps failing is tried less and less often.
 */
internal fun AutomaticSyncPace.afterAutomaticSync(endedAtMillis: Long, succeeded: Boolean = true): AutomaticSyncPace = copy(
    lastEndedAtMillis = endedAtMillis,
    heldSinceMillis = null,
    previousSucceeded = succeeded,
    waitMillis = if (succeeded) {
        waitMillis.coerceIn(AUTOMATIC_SYNC_FIRST_WAIT_MILLIS, AUTOMATIC_SYNC_LONGEST_WAIT_MILLIS)
    } else {
        (waitMillis.coerceAtLeast(AUTOMATIC_SYNC_FIRST_WAIT_MILLIS) * 2).coerceAtMost(AUTOMATIC_SYNC_LONGEST_FAILING_WAIT_MILLIS)
    },
)

/** The pace when a check wanted no automatic Sync: nothing is held any more. */
internal fun AutomaticSyncPace.withNothingHeld(): AutomaticSyncPace =
    if (heldSinceMillis == null) this else copy(heldSinceMillis = null)

/**
 * How long the watch pauses before its next check: its interval, or less when the Sync it holds
 * back may start sooner, so that a held Sync starts when its time has come and not up to one
 * interval later.
 */
internal fun nextRemoteCheckDelayMillis(pace: AutomaticSyncPace, nowMillis: Long, intervalMillis: Long): Long {
    val heldUntil = automaticSyncHeldUntilMillis(pace) ?: return intervalMillis
    return (heldUntil - nowMillis).coerceIn(SHORTEST_CHECK_PAUSE_MILLIS.coerceAtMost(intervalMillis), intervalMillis)
}

private const val SHORTEST_CHECK_PAUSE_MILLIS = 1_000L
