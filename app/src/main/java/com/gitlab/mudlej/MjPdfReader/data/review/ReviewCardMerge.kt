// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerConfig
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Merge cards on the phone: review cards of one document whose answers are marked in one source
 * file become one card, as the Mac's Merge cards… does. The phone has no source outbox, so the
 * server merges (POST /v1/review-card-merges, capability review_card_merge_v1): it makes the
 * records the Mac would send and applies them in one transaction; the next Sync brings them here.
 * The kept card keeps its ID, schedule and history and takes every card's passage; the others are
 * deleted with their history.
 */
object ReviewCardMerge {
    const val CAPABILITY = "review_card_merge_v1"
    const val PATH = "/v1/review-card-merges"
    const val MIN_CARDS = 2
    const val MAX_CARDS = 20
    const val MAX_QUESTION_LENGTH = 500

    /** Why the selected cards cannot be merged here; nothing is sent. */
    enum class Refusal { TOO_FEW, TOO_MANY, RETIRED, NOT_IN_SOURCE, SEVERAL_SOURCES }

    /** What the server needs: the cards, the one source file holding their markers, the version read. */
    data class Plan(val cardIds: List<String>, val source: String, val expectedSourceSha256: String)

    sealed class Check {
        data class Ready(val plan: Plan) : Check()
        data class Refused(val refusal: Refusal) : Check()
    }

    /**
     * Whether [cards] can be merged, from what the phone knows of them: two to twenty cards, none
     * retired, each with its answer marked in the source, all in one source file. The server checks
     * the markers themselves.
     */
    fun check(cards: List<ReviewCard>): Check {
        if (cards.map { it.id }.distinct().size < MIN_CARDS) return Check.Refused(Refusal.TOO_FEW)
        if (cards.size > MAX_CARDS) return Check.Refused(Refusal.TOO_MANY)
        if (cards.any { it.retired }) return Check.Refused(Refusal.RETIRED)
        val files = linkedSetOf<String>()
        val versions = mutableListOf<String>()
        for (card in cards) {
            val segments = segments(card)
            if (card.wholeDocument || segments.isEmpty()) return Check.Refused(Refusal.NOT_IN_SOURCE)
            for (segment in segments) {
                val file = segment.string("source_file") ?: return Check.Refused(Refusal.NOT_IN_SOURCE)
                files += file
                segment.string("source_sha256")?.takeIf { SHA256.matches(it) }?.let(versions::add)
            }
        }
        if (files.size != 1) return Check.Refused(Refusal.SEVERAL_SOURCES)
        // The version most passages were read from; the server names the current one if it moved on.
        val version = versions.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: return Check.Refused(Refusal.NOT_IN_SOURCE)
        return Check.Ready(Plan(cards.map { it.id }, files.single(), version))
    }

    /** The card kept when the user does not choose: the most reviewed, the first in reading order on a tie. */
    fun defaultKept(cardIdsInOrder: List<String>, reviewCounts: Map<String, Int>): String? =
        cardIdsInOrder.maxByOrNull { reviewCounts[it] ?: 0 }

    private fun segments(card: ReviewCard): List<JsonObject> =
        card.sourceAnchor?.get("segments")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)

    private val SHA256 = Regex("[0-9a-f]{64}")
}

/** The server's answer to a merge. */
data class ReviewCardMergeResult(
    val keptCardId: String,
    val deletedCardIds: List<String>,
    val question: String,
    val passages: Int,
    val replayed: Boolean,
)

/** A merge the server refused or could not take; nothing was changed. [code] is the server's error_code. */
class ReviewCardMergeException(
    val status: Int,
    val code: String?,
    message: String,
    /** With source_conflict: the source version the server holds now. */
    val currentSourceSha256: String? = null,
) : IOException(message)

/** The server cannot merge cards yet (no review_card_merge_v1): nothing was sent. */
class ReviewCardMergeUnsupportedException : IOException("The server cannot merge review cards yet")

/**
 * HTTP client for POST /v1/review-card-merges with the device token the app uses for every /v1
 * call. Call it off the main thread. [log] receives status and timing, never contents.
 */
class ReviewCardMergeClient(
    private val config: () -> ThoughtsServerConfig?,
    private val connectionFactory: (URL) -> URLConnection = { it.openConnection() },
    private val log: (String) -> Unit = {},
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Asks the server to merge [plan]'s cards into [keepCardId] with [question]. When the source
     * moved on since the phone read it, the server names its current version: the merge is asked
     * once more on that version (the server checks every card's markers there again).
     */
    fun merge(plan: ReviewCardMerge.Plan, keepCardId: String, question: String): ReviewCardMergeResult {
        val capabilities = requestJson("GET", "/v1/health", null, "capabilities").get("capabilities")
            ?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            .orEmpty()
        if (ReviewCardMerge.CAPABILITY !in capabilities) throw ReviewCardMergeUnsupportedException()
        return try {
            post(plan, keepCardId, question, plan.expectedSourceSha256)
        } catch (error: SourceMovedOn) {
            post(plan, keepCardId, question, error.current)
        }
    }

    private class SourceMovedOn(val current: String) : Exception()

    private fun post(plan: ReviewCardMerge.Plan, keepCardId: String, question: String, sha256: String): ReviewCardMergeResult {
        val body = JsonObject().apply {
            addProperty("request_id", newRequestId())
            add("card_ids", JsonArray().apply { plan.cardIds.forEach(::add) })
            addProperty("keep_card_id", keepCardId)
            addProperty("question", question.trim().take(ReviewCardMerge.MAX_QUESTION_LENGTH))
            addProperty("source", plan.source)
            addProperty("expected_source_sha256", sha256)
        }
        val json = try {
            requestJson("POST", ReviewCardMerge.PATH, body.toString().toByteArray(StandardCharsets.UTF_8), "merge")
        } catch (error: ReviewCardMergeException) {
            val current = error.currentSourceSha256
            if (error.code == "source_conflict" && current != null && current != sha256) throw SourceMovedOn(current)
            throw error
        }
        if (json.get("accepted")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) {
            throw ReviewCardMergeException(200, "write_failed", "The server did not accept the merge")
        }
        return ReviewCardMergeResult(
            keptCardId = json.string("kept_card_id") ?: keepCardId,
            deletedCardIds = json.get("deleted_card_ids")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
            question = json.string("question") ?: question,
            passages = json.get("passages")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            replayed = json.get("replayed")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
        )
    }

    private fun requestJson(method: String, path: String, body: ByteArray?, label: String): JsonObject {
        val server = config() ?: throw ReviewCardMergeException(0, "not_configured", "No Thoughts server is set up")
        val started = System.nanoTime()
        val connection = connectionFactory(URI.create(server.baseUrl.trimEnd('/') + path).toURL()) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${server.token}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = runCatching { connection.errorStream?.use(::readLimited)?.let(::parseJson) }.getOrNull()
                val code = error?.string("error_code")
                log("card-merge $label failed status=$status code=$code ms=${(System.nanoTime() - started) / 1_000_000}")
                throw ReviewCardMergeException(status, code, error?.string("detail") ?: "The server answered HTTP $status",
                    error?.string("current_source_sha256"))
            }
            val json = connection.inputStream.use(::readLimited).let(::parseJson)
            log("card-merge $label ok status=$status ms=${(System.nanoTime() - started) / 1_000_000}")
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJson(bytes: ByteArray): JsonObject = try {
        JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
    } catch (error: RuntimeException) {
        throw IOException("The server's answer is not a JSON object", error)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > 1_000_000) throw IOException("The server's answer is too large")
        }
        return output.toByteArray()
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
}
