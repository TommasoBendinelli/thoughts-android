// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.tbend.thoughts.sdk.RenderStateIfChangedCall
import org.tbend.thoughts.sdk.RenderStateRecord

/**
 * The last answer of one route with the ETag the server gave it, kept in memory. The next request
 * sends the ETag as `If-None-Match`; the server then answers 304 without a body while nothing
 * changed, and the kept answer stands. [key] names what was asked (the server, the target), so an
 * answer is never used for another question.
 */
internal class ConditionalAnswer<T : Any> {
    private data class Kept<T>(val key: String, val etag: String, val value: T, val confirmedAtMillis: Long? = null)

    @Volatile private var kept: Kept<T>? = null

    /** The ETag and the answer kept for [key], read together. */
    fun kept(key: String): Pair<String, T>? = kept?.takeIf { it.key == key }?.let { it.etag to it.value }

    /** Keeps [value] under the ETag header [etagHeader]; an answer without an ETag is not kept. */
    @Synchronized
    fun keep(key: String, etagHeader: String?, value: T) {
        val etag = etagHeader.orEmpty().trim().removePrefix("W/").trim().trim('"')
        kept = if (etag.isBlank()) null else Kept(key, etag, value)
    }

    /**
     * The server said at [atMillis] (a monotonic clock) that the answer kept for [key] is current,
     * at the end of a request it held ([waitForSyncState]): the next read may take it from here.
     */
    @Synchronized
    fun confirm(key: String, atMillis: Long) {
        kept = kept?.let { if (it.key == key) it.copy(confirmedAtMillis = atMillis) else it }
    }

    /**
     * The answer kept for [key] if the server confirmed it at most [withinMillis] before [nowMillis];
     * each confirmation is taken once, so only the read right after a held request is spared.
     */
    @Synchronized
    fun takeConfirmed(key: String, nowMillis: Long, withinMillis: Long): T? {
        val current = kept?.takeIf { it.key == key } ?: return null
        val confirmed = current.confirmedAtMillis ?: return null
        kept = current.copy(confirmedAtMillis = null)
        return current.value.takeIf { nowMillis - confirmed in 0..withinMillis }
    }

    @Synchronized
    fun clear() {
        kept = null
    }
}

/**
 * `GET /v2/sync/state` on [connection]. The watch asks every 20 seconds, mostly while nothing
 * changes: with the ETag of the answer in [kept] the server answers 304 and that answer is
 * returned. Null when the server cannot be asked or answers something else.
 */
internal fun readSyncState(
    connection: HttpURLConnection,
    token: String,
    key: String,
    kept: ConditionalAnswer<JsonObject>,
): JsonObject? {
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Accept", "application/json")
    val before = kept.kept(key)
    before?.let { (etag, _) -> connection.setRequestProperty("If-None-Match", "\"$etag\"") }
    val status = connection.responseCode
    if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return before?.second
    if (status !in 200..299) return null
    return keepSyncState(connection, key, kept)
}

/** The state in the body of [connection]'s 200 answer, kept in [kept] with its ETag; null when it is not one. */
private fun keepSyncState(
    connection: HttpURLConnection,
    key: String,
    kept: ConditionalAnswer<JsonObject>,
): JsonObject? {
    val body = connection.inputStream.use { it.readBytes() }
    if (body.size > 64 * 1024) return null
    val state = JsonParser.parseString(body.toString(StandardCharsets.UTF_8)).asJsonObject
    kept.keep(key, connection.getHeaderField("ETag"), state)
    return state
}

/** The server's capability: it holds `GET /v2/sync/state?wait_ms=` until the state changes. */
internal const val SYNC_STATE_WAIT_CAPABILITY = "sync_state_wait_v1"

/** The longest the server holds a Sync state request (`wait_ms`). */
internal const val SYNC_STATE_LONGEST_WAIT_MILLIS = 25_000L

/** How a held Sync state request ([waitForSyncState]) ended. */
internal enum class SyncStateWait {
    /** The state changed: the new one is kept, and the next read takes it without asking. */
    CHANGED,

    /** Nothing changed during the whole wait; the next read takes the kept state without asking. */
    UNCHANGED,

    /**
     * The server answered "unchanged" at once: it did not hold the request (it holds too many, it
     * is stopping, or it is older).
     */
    NOT_HELD,

    /** No answer, another answer, or no kept state to wait on. */
    FAILED,
}

/** A monotonic clock in milliseconds, for [ConditionalAnswer.confirm]. */
internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

/**
 * `GET /v2/sync/state?wait_ms=` ([url] carries it) with the ETag of the answer in [kept]: the
 * server answers when the state differs from that answer, or "unchanged" after [waitMillis]. So
 * a change reaches the phone at once instead of at its next check, with one request per wait.
 * The read timeout is above the wait. Cancelling the caller (the app leaves the screen) closes
 * the connection, so the request ends then and not when the server answers.
 */
internal suspend fun waitForSyncState(
    url: URL,
    open: (URL) -> HttpURLConnection,
    token: String,
    key: String,
    kept: ConditionalAnswer<JsonObject>,
    waitMillis: Long,
    nowMillis: () -> Long = ::monotonicMillis,
): SyncStateWait {
    val (etag, _) = kept.kept(key) ?: return SyncStateWait.FAILED
    val started = nowMillis()
    val connection = open(url)
    return try {
        cancellableRequest(connection) {
            connection.connectTimeout = 10_000
            connection.readTimeout = (waitMillis + 15_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("If-None-Match", "\"$etag\"")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    val now = nowMillis()
                    kept.confirm(key, now)
                    // Held to (about) its end, or answered at once by a server that did not hold it.
                    val held = now - started >= waitMillis - waitMillis / 5
                    if (held) SyncStateWait.UNCHANGED else SyncStateWait.NOT_HELD
                }
                in 200..299 -> if (keepSyncState(connection, key, kept) != null) {
                    kept.confirm(key, nowMillis())
                    SyncStateWait.CHANGED
                } else {
                    SyncStateWait.FAILED
                }
                else -> SyncStateWait.FAILED
            }
        }.getOrDefault(SyncStateWait.FAILED)
    } finally {
        connection.disconnect()
    }
}

/**
 * [request] on [connection] in an IO thread, its failure as a [Result]. Cancelling the caller
 * disconnects [connection]: a blocking read ends with an error at once, so a request the server
 * holds does not keep the caller (and its thread) for the rest of the wait.
 */
internal suspend fun <T> cancellableRequest(
    connection: HttpURLConnection,
    request: () -> T,
): Result<T> = coroutineScope {
    val call = async(Dispatchers.IO) { runCatching(request) }
    try {
        call.await()
    } catch (cancelled: CancellationException) {
        connection.disconnect()
        throw cancelled
    }
}

/**
 * The render state to show after [call]: the server's answer, or, when it answered "unchanged",
 * the answer kept from before as observed now (the server's clock when it said so, else
 * [nowEpochMillis]), so the status still says when it was last checked. Null when the server said
 * "unchanged" about an answer that is no longer kept.
 */
internal fun renderStateToShow(
    call: RenderStateIfChangedCall,
    kept: RenderStateRecord?,
    nowEpochMillis: Long,
): RenderStateRecord? = call.state
    ?: kept?.copy(observedAt = Instant.ofEpochMilli(call.serverDateEpochMillis ?: nowEpochMillis).toString())
