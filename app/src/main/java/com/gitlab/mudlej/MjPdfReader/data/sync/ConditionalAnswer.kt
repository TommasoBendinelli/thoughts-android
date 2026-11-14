// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.tbend.thoughts.sdk.RenderStateIfChangedCall
import org.tbend.thoughts.sdk.RenderStateRecord

/**
 * The last answer of one route with the ETag the server gave it, kept in memory. The next request
 * sends the ETag as `If-None-Match`; the server then answers 304 without a body while nothing
 * changed, and the kept answer stands. [key] names what was asked (the server, the target), so an
 * answer is never used for another question.
 */
internal class ConditionalAnswer<T : Any> {
    private data class Kept<T>(val key: String, val etag: String, val value: T)

    @Volatile private var kept: Kept<T>? = null

    /** The ETag and the answer kept for [key], read together. */
    fun kept(key: String): Pair<String, T>? = kept?.takeIf { it.key == key }?.let { it.etag to it.value }

    /** Keeps [value] under the ETag header [etagHeader]; an answer without an ETag is not kept. */
    fun keep(key: String, etagHeader: String?, value: T) {
        val etag = etagHeader.orEmpty().trim().removePrefix("W/").trim().trim('"')
        kept = if (etag.isBlank()) null else Kept(key, etag, value)
    }

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
    val body = connection.inputStream.use { it.readBytes() }
    if (body.size > 64 * 1024) return null
    val state = JsonParser.parseString(body.toString(StandardCharsets.UTF_8)).asJsonObject
    kept.keep(key, connection.getHeaderField("ETag"), state)
    return state
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
