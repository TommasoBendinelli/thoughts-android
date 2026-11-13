// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.tbend.thoughts.sdk.RenderCheckResult

/**
 * Fedora keeps the PDF when a proposal is accepted (the page already shows the accepted text)
 * and rebuilds it about 20 s later, so its SyncTeX names the accepted text. A server without
 * the render-check fix answers "current" until that rebuild is queued: on 2026-10-10 19:49 a
 * Sync that had just uploaded three editor accepts ended "Up to date" one second after the
 * upload, and the rebuilt PDF only arrived with the next Sync two minutes later.
 */
internal const val DEFERRED_ACCEPT_REBUILD_WAIT_MILLIS = 35_000L
internal const val DEFERRED_ACCEPT_REBUILD_POLL_MILLIS = 2_000L

/**
 * After this Sync delivered an Accept that kept the PDF, asks Fedora again until the rebuild
 * the Accept causes is queued, at most [DEFERRED_ACCEPT_REBUILD_WAIT_MILLIS] after delivery.
 * Returns the first answer that is not "current" (normally "building" with that build), or the
 * last "current" one when no rebuild appeared. A fixed server answers "building" at once.
 */
internal fun awaitDeferredAcceptRebuild(
    initial: RenderCheckResult,
    acceptDeliveredAtMillis: Long?,
    nowMillis: () -> Long,
    sleep: (Long) -> Unit,
    recheck: () -> RenderCheckResult,
): RenderCheckResult {
    if (initial.status != "current" || acceptDeliveredAtMillis == null) return initial
    val deadline = acceptDeliveredAtMillis + DEFERRED_ACCEPT_REBUILD_WAIT_MILLIS
    var latest = initial
    while (latest.status == "current") {
        val remaining = deadline - nowMillis()
        if (remaining <= 0) break
        sleep(minOf(DEFERRED_ACCEPT_REBUILD_POLL_MILLIS, remaining))
        latest = recheck()
    }
    return latest
}
