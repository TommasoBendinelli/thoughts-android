// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerAddress
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * The agent recall prediction (review in two steps): when a card comes up, the phone asks a service
 * on Fedora which step of the recall group ([ReviewTagGroups.recallGroup]) the user will remember,
 * from the card, its answer and history and the latest answers of the document's other cards. The
 * prediction is shown once the answer is shown and recorded with the answer ([ReviewAgentPrediction]).
 * One request per card shown; any failure shows and records nothing. The service is configured by
 * the build ([Config.fromBuild]); without it nothing is requested.
 */
object ReviewRecallPrediction {
    const val MAX_ANSWER_LENGTH = 3000
    const val MAX_HISTORY = 30
    const val MAX_RELATED = 20
    val RELATED_WINDOW: Duration = Duration.ofDays(14)
    const val TIMEOUT_MILLIS = 60_000
    const val TOKEN_HEADER = "X-Recall-Token"

    /** Where the service is; never logged (the token is a secret). */
    class Config(val url: String, val token: String) {
        override fun toString(): String = "ReviewRecallPrediction.Config(url=$url)"

        companion object {
            /** Both values set, else null (the feature is off). */
            fun of(url: String?, token: String?): Config? {
                val address = url?.trim().orEmpty()
                val secret = token?.trim().orEmpty()
                if (address.isEmpty() || secret.isEmpty()) return null
                if (!address.startsWith("http://") && !address.startsWith("https://")) return null
                return Config(address, secret)
            }

            /** From gradle properties `thoughtsRecallPredictorUrl` / `thoughtsRecallPredictorToken` (BuildConfig). */
            fun fromBuild(): Config? = of(
                com.gitlab.mudlej.MjPdfReader.BuildConfig.RECALL_PREDICTOR_URL,
                com.gitlab.mudlej.MjPdfReader.BuildConfig.RECALL_PREDICTOR_TOKEN,
            )
        }
    }

    /** Another card of the document, with its question and history ([ReviewStore.recallPredictionHistory]). */
    data class OtherCardAnswers(val question: String, val history: List<ReviewHistoryEntry>)

    /**
     * The request: the card, its [answer] text (cut to [MAX_ANSWER_LENGTH]), the [document]'s display
     * name, the recall group's [scale] and id; `history` = the card's answers in effect, oldest first,
     * at most [MAX_HISTORY] (with `before_tags` for an answer given in two steps); `related` = the
     * newest answers of [others] from the last [RELATED_WINDOW], at most [MAX_RELATED], newest first.
     * Tags read under their merged names ([mapping]).
     */
    fun requestBody(
        cardId: String,
        question: String,
        answer: String,
        document: String,
        scale: List<String>,
        recallGroupId: String,
        history: List<ReviewHistoryEntry>,
        others: List<OtherCardAnswers>,
        now: Instant,
        mapping: Map<String, String> = emptyMap(),
    ): JsonObject = JsonObject().apply {
        addProperty("card_id", cardId)
        addProperty("question", question)
        addProperty("answer", answer.take(MAX_ANSWER_LENGTH))
        addProperty("document", document)
        add("scale", strings(scale))
        addProperty("recall_group_id", recallGroupId)
        add("history", JsonArray().apply {
            answersInEffect(history).takeLast(MAX_HISTORY).forEach { entry ->
                add(JsonObject().apply {
                    addProperty("reviewed_at", ReviewTagGroups.timestamp(checkNotNull(entry.reviewedAt)))
                    add("tags", strings(entry.effectiveTags(mapping)))
                    add("notes", JsonObject().apply { entry.groupNotes.forEach(::addProperty) })
                    entry.phases?.let { add("before_tags", strings(it.beforeTags.map { tag -> mapping[tag] ?: tag })) }
                })
            }
        })
        val since = now.minus(RELATED_WINDOW)
        add("related", JsonArray().apply {
            others.flatMap { other -> answersInEffect(other.history).map { other.question to it } }
                .filter { (_, entry) -> !checkNotNull(entry.reviewedAt).isBefore(since) }
                .sortedWith(compareByDescending<Pair<String, ReviewHistoryEntry>> { it.second.reviewedAt }.thenBy { it.second.eventId })
                .take(MAX_RELATED)
                .forEach { (otherQuestion, entry) ->
                    add(JsonObject().apply {
                        addProperty("question", otherQuestion)
                        addProperty("reviewed_at", ReviewTagGroups.timestamp(checkNotNull(entry.reviewedAt)))
                        add("tags", strings(entry.effectiveTags(mapping)))
                    })
                }
        })
        addProperty("now", ReviewTagGroups.timestamp(now))
    }

    /**
     * The service's answer, or null when it is not a prediction for [scale] (no prediction id, or a
     * tag that is not one of the steps). [requestedAt]: when the request was sent.
     */
    fun parseResponse(response: JsonObject, scale: List<String>, groupId: String, requestedAt: Instant): ReviewAgentPrediction? {
        fun text(name: String) = ReviewPhases.stringOf(response.get(name))?.trim().orEmpty()
        val tag = text("tag").takeIf { it in scale } ?: return null
        val predictionId = text("prediction_id").takeIf(String::isNotEmpty) ?: return null
        return ReviewAgentPrediction(
            tag = tag,
            groupId = groupId,
            reason = text("reason"),
            model = text("model"),
            predictionId = predictionId,
            requestedAt = ReviewTagGroups.timestamp(requestedAt),
            madeAt = text("made_at"),
        )
    }

    private fun answersInEffect(history: List<ReviewHistoryEntry>): List<ReviewHistoryEntry> =
        history.filter { it.isAnswerInEffect() }.sortedWith(compareBy<ReviewHistoryEntry> { it.reviewedAt }.thenBy { it.eventId })

    private fun strings(values: List<String>) = JsonArray().apply { values.forEach(::add) }

    /**
     * Whether the token and the card's contents may be sent to [url]. `https` always; plain `http`
     * only to this device itself (a loopback address) or to an address that is reached through the
     * VPN ([ThoughtsServerAddress.requiresVpn]) while the app's VPN check passes, so that neither
     * crosses a network unencrypted.
     */
    fun transportAllowed(url: String, vpnActive: () -> Boolean): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val host = ThoughtsServerAddress.hostOf(url) ?: return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> true
            "http" -> ThoughtsServerAddress.isLoopbackHost(host) ||
                (ThoughtsServerAddress.requiresVpn(url) && ThoughtsServerAddress.vpnPrecheckPasses(url, vpnActive))
            else -> false
        }
    }

    /**
     * Sends [body] to the service and reads its JSON answer. Blocking: call it off the main thread.
     * Anything but a 200 with a JSON object is an [IOException], and so is an address that
     * [transportAllowed] refuses ([vpnActive]: whether the phone's VPN is up); nothing is sent then.
     * [log] gets the status and timing, never the token or contents.
     */
    fun request(
        config: Config,
        body: JsonObject,
        connectionFactory: (URL) -> URLConnection = { it.openConnection() },
        log: (String) -> Unit = {},
        vpnActive: () -> Boolean,
    ): JsonObject {
        if (!transportAllowed(config.url, vpnActive)) {
            throw IOException("Recall prediction refused: plain http is only used on this device or through the VPN")
        }
        val started = System.nanoTime()
        val connection = connectionFactory(URL(config.url)) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            // The token and the card go to the configured address only, not to where it redirects.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(TOKEN_HEADER, config.token)
            val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            log("recall prediction status=$status bytes=${bytes.size} ms=${(System.nanoTime() - started) / 1_000_000}")
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Recall prediction failed: HTTP $status")
            val text = connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            return JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IOException("Recall prediction failed: not a JSON object")
        } finally {
            connection.disconnect()
        }
    }
}
