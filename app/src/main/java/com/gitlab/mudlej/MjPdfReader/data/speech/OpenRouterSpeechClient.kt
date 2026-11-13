// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Turns one chunk of text into audio, or throws. */
fun interface SpeechSynthesizer {
    @Throws(IOException::class)
    fun synthesize(text: String): ByteArray
}

/** Why the cloud voice gave no audio; the reader then falls back to the system voice. */
enum class SpeechFailureReason {
    /** No OpenRouter key is saved on this phone (or it can no longer be decrypted). */
    NO_KEY,
    /** HTTP 401/403: OpenRouter does not accept the key. */
    KEY_REJECTED,
    /** HTTP 402: the OpenRouter account has no credits for the model. */
    NO_CREDITS,
    /** HTTP 429. */
    RATE_LIMITED,
    /** HTTP 5xx. */
    PROVIDER_ERROR,
    /** Any other HTTP status. */
    REQUEST_REFUSED,
    /** HTTP 200 without audio (a captive portal, an error page). */
    NOT_AUDIO,
    TIMED_OUT,
    NO_CONNECTION,
}

/**
 * The cloud voice gave no audio. [detail] may carry OpenRouter's own error message, shortened; it
 * never contains the key or the text, and it is shown to the user, never logged.
 */
class SpeechUnavailableException(
    val reason: SpeechFailureReason,
    val statusCode: Int? = null,
    val detail: String = "",
) : IOException(
    buildString {
        append(reason.name)
        if (statusCode != null) append(" HTTP ").append(statusCode)
        if (detail.isNotEmpty()) append(": ").append(detail)
    },
)

/**
 * OpenRouter's text-to-speech request, as the Thoughts server's `speech.py` (feature/read-aloud
 * 11b4c2c) sent it: the free Fish Audio model first, then the paid model of the same family when
 * the free tier refuses or fails, with Fish's "Ethan" voice so every sentence of a passage sounds
 * the same.
 */
object OpenRouterSpeech {
    const val URL = "https://openrouter.ai/api/v1/audio/speech"
    const val MODEL = "fish-audio/s2.1-pro-free:free"
    const val FALLBACK_MODEL = "fish-audio/s2.1-pro"

    /** Fish Audio voice library: "Ethan" by Fish Official (male, calm, clear narration). */
    const val VOICE = "536d3a5e000945adb7038665781a4aca"
    const val INSTRUCTIONS = "Read calmly and naturally, like an audiobook narrator reading an essay."
    const val MAX_TEXT_CHARS = 4000
    const val MAX_AUDIO_BYTES = 32 * 1024 * 1024

    /** Only these model families honour `instructions` (OpenRouter TTS guide). */
    private val INSTRUCTION_MODEL_PREFIXES = listOf("fish-audio/", "openai/gpt-4o-mini-tts", "google/gemini")

    /** The free tier is "low-volume": a refusal or an outage there moves to the paid model. */
    private val FALLBACK_STATUSES = setOf(402, 429)

    fun normalize(text: String): String {
        val normalized = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        require(normalized.length in 1..MAX_TEXT_CHARS) { "Speech text must be 1..$MAX_TEXT_CHARS characters" }
        return normalized
    }

    fun modelAcceptsInstructions(model: String) = INSTRUCTION_MODEL_PREFIXES.any { model.startsWith(it) }

    /** The ways to read [text], in the order they are tried. */
    fun syntheses(text: String): List<Synthesis> = listOf(MODEL, FALLBACK_MODEL).map { model ->
        Synthesis(
            model = model,
            voice = VOICE,
            instructions = INSTRUCTIONS.takeIf { modelAcceptsInstructions(model) },
            text = text,
        )
    }

    /** No answer, a refusal of the free tier, or a provider error: the next model may still read it. */
    fun shouldTryNextModel(statusCode: Int?): Boolean =
        statusCode == null || statusCode in FALLBACK_STATUSES || statusCode >= 500

    fun reasonFor(statusCode: Int): SpeechFailureReason = when {
        statusCode == 401 || statusCode == 403 -> SpeechFailureReason.KEY_REJECTED
        statusCode == 402 -> SpeechFailureReason.NO_CREDITS
        statusCode == 429 -> SpeechFailureReason.RATE_LIMITED
        statusCode >= 500 -> SpeechFailureReason.PROVIDER_ERROR
        else -> SpeechFailureReason.REQUEST_REFUSED
    }

    private val WHITESPACE = Regex("\\s+")
}

/** One way to read a passage: everything that shapes the audio. */
data class Synthesis(
    val model: String,
    val voice: String?,
    val instructions: String?,
    val text: String,
) {
    fun requestBody(): JsonObject = JsonObject().apply {
        addProperty("model", model)
        addProperty("input", text)
        addProperty("response_format", "mp3")
        voice?.let { addProperty("voice", it) }
        instructions?.let { addProperty("instructions", it) }
    }

    /** Names the audio in the local cache; the key is not part of it, so a new key reuses old audio. */
    fun cacheKey(): String {
        val identity = JsonObject().apply {
            addProperty("format", "mp3")
            addProperty("instructions", instructions ?: "")
            addProperty("model", model)
            addProperty("text", text)
            addProperty("voice", voice ?: "")
        }.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Reads chunks with OpenRouter's text-to-speech, straight from the phone with the key the user
 * saved ([apiKey]); no Thoughts server is involved. Answers are kept in [cache] because readers
 * replay the same sentences; [readCache] false asks OpenRouter even for cached text (Test voice).
 * Blocking; call it off the main thread.
 *
 * [log] receives one line per request with sizes, model, status and timing; never the text, the
 * key or OpenRouter's error message.
 */
class OpenRouterSpeechClient(
    private val apiKey: () -> String?,
    private val cache: SpeechAudioCache? = null,
    private val readCache: Boolean = true,
    private val endpoint: String = OpenRouterSpeech.URL,
    private val connectionFactory: (URL) -> URLConnection = { it.openConnection() },
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 60_000,
    private val log: (String) -> Unit = {},
) : SpeechSynthesizer {

    /** The model whose audio the last successful [synthesize] returned. */
    @Volatile
    var lastModel: String? = null
        private set

    override fun synthesize(text: String): ByteArray {
        val normalized = OpenRouterSpeech.normalize(text)
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SpeechUnavailableException(SpeechFailureReason.NO_KEY)
        val syntheses = OpenRouterSpeech.syntheses(normalized)
        val cache = cache
        if (readCache && cache != null) {
            for (synthesis in syntheses) {
                val cached = runCatching { cache.get(synthesis.cacheKey()) }.getOrNull() ?: continue
                log("speech ok cache=hit model=${synthesis.model} chars=${normalized.length} bytes=${cached.size}")
                lastModel = synthesis.model
                return cached
            }
        }
        var failure: SpeechUnavailableException? = null
        for (synthesis in syntheses) {
            try {
                val audio = request(synthesis, key)
                runCatching { cache?.put(synthesis.cacheKey(), audio) }
                lastModel = synthesis.model
                return audio
            } catch (error: SpeechUnavailableException) {
                failure = error
                if (!OpenRouterSpeech.shouldTryNextModel(error.statusCode)) break
            }
        }
        throw checkNotNull(failure)
    }

    private fun request(synthesis: Synthesis, key: String): ByteArray {
        val started = System.nanoTime()
        val chars = synthesis.text.length
        val connection = connectionFactory(URI.create(endpoint).toURL()) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.useCaches = false
            // The key goes to the configured endpoint only, not to where it redirects.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/mpeg")
            connection.setRequestProperty("X-Title", "Thoughts")
            val body = synthesis.requestBody().toString().toByteArray(StandardCharsets.UTF_8)
            // Not streamed: a few KB at most, and a streamed body hides the error answer of a 401
            // from HttpURLConnection on the JVM.
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val message = runCatching { connection.errorStream?.use { errorMessage(it) } }.getOrNull().orEmpty()
                log("speech failed model=${synthesis.model} status=$status chars=$chars ms=${elapsedMs(started)}")
                throw SpeechUnavailableException(OpenRouterSpeech.reasonFor(status), status, message)
            }
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (contentType.startsWith("application/json") || contentType.startsWith("text/")) {
                log("speech failed model=${synthesis.model} status=200 type=$contentType chars=$chars")
                throw SpeechUnavailableException(SpeechFailureReason.NOT_AUDIO, status)
            }
            val audio = connection.inputStream.use { readLimited(it, OpenRouterSpeech.MAX_AUDIO_BYTES) }
            if (audio.isEmpty()) {
                log("speech failed model=${synthesis.model} status=200 empty chars=$chars")
                throw SpeechUnavailableException(SpeechFailureReason.NOT_AUDIO, status)
            }
            log(
                "speech ok cache=miss model=${synthesis.model} chars=$chars bytes=${audio.size} " +
                    "ms=${elapsedMs(started)}",
            )
            return audio
        } catch (error: SpeechUnavailableException) {
            throw error
        } catch (error: IOException) {
            // A cancelled reading interrupts this thread; that is not a failure of the voice.
            if (error is InterruptedIOException && error !is SocketTimeoutException) throw error
            if (Thread.currentThread().isInterrupted) throw error
            log("speech failed model=${synthesis.model} ${error.javaClass.simpleName} chars=$chars ms=${elapsedMs(started)}")
            val reason = if (error is SocketTimeoutException) {
                SpeechFailureReason.TIMED_OUT
            } else {
                SpeechFailureReason.NO_CONNECTION
            }
            throw SpeechUnavailableException(reason)
        } finally {
            connection.disconnect()
        }
    }

    /** OpenRouter's own `{"error": {"message": …}}`, shortened; empty when there is none. */
    private fun errorMessage(input: InputStream): String {
        val body = String(readLimited(input, MAX_ERROR_BYTES), StandardCharsets.UTF_8)
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("error").get("message").asString
        }.getOrNull() ?: return ""
        return message.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(200)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > limit) throw SpeechUnavailableException(SpeechFailureReason.NOT_AUDIO, 200, "too large")
        }
        return output.toByteArray()
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000

    private companion object {
        const val MAX_ERROR_BYTES = 64 * 1024
    }
}
