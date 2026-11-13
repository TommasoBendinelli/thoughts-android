// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.glasses

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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A picture from the glasses: JPEG bytes and the glasses' capture name (IMG_…). */
class GlassesPhoto(val bytes: ByteArray, val capture: String, val mediaType: String = "image/jpeg")

/** Why no picture came; each has its own message for the user. */
enum class GlassesCameraProblem {
    /** No glasses camera address in Settings. */
    NOT_SET_UP,
    /** The glasses did not answer (off, asleep, no network, wrong address). */
    NOT_CONNECTED,
    /** The glasses answered 403: this phone is not on their gallery allowlist. */
    NOT_ALLOWED,
    /** The glasses refused the shot (camera busy, e.g. recording or streaming). */
    CAMERA_BUSY,
    /** The shot was asked for, but no new photo appeared in time. */
    NO_PHOTO,
}

class GlassesCameraException(val problem: GlassesCameraProblem, message: String) : IOException(message)

/**
 * Takes a picture with Mentra Live glasses running the ASG client's camera server (HTTP on the
 * glasses, port 8089), as the Mac companion does: note the captures there, `GET /api/take-picture`,
 * wait for a new `IMG_…` capture, download its `base.jpg`. Another device may archive the photo and
 * move it to the glasses' trash within seconds, so the trash is watched and read too. Blocking: call
 * it off the main thread. [log] gets one line per step, never image contents.
 */
class GlassesCamera(
    private val baseUrl: () -> String?,
    private val connectionFactory: (URL) -> URLConnection = { it.openConnection() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val photoTimeoutMillis: Long = 20_000,
    private val pollMillis: Long = 500,
    private val connectTimeoutMillis: Int = 4_000,
    private val log: (String) -> Unit = {},
) {
    fun takePhoto(): GlassesPhoto {
        val base = normalizedBaseUrl(baseUrl())
            ?: throw GlassesCameraException(GlassesCameraProblem.NOT_SET_UP, "No glasses camera address is set")
        val before = captures(base).keys
        val shot = request(base, "/api/take-picture", 10_000)
        if (shot.status == 503 || shot.status == 409) {
            throw GlassesCameraException(GlassesCameraProblem.CAMERA_BUSY, "The glasses camera is busy (HTTP ${shot.status})")
        }
        checkStatus(shot.status)
        log("glasses take-picture ok captures_before=${before.size}")
        val deadline = clock() + photoTimeoutMillis
        while (clock() < deadline) {
            sleep(pollMillis)
            val fresh = captures(base).filterKeys { it !in before }.toSortedMap()
            for ((capture, location) in fresh) {
                val bytes = download(base, capture, location) ?: continue
                log("glasses photo $capture bytes=${bytes.size}")
                return GlassesPhoto(bytes, capture)
            }
        }
        throw GlassesCameraException(GlassesCameraProblem.NO_PHOTO, "No photo arrived from the glasses in ${photoTimeoutMillis / 1000} s")
    }

    /** Where a photo capture's file is: in the gallery (its file name) or in the trash. */
    private class Location(val file: String?, val trashed: Boolean)

    /** IMG_ captures in the gallery and the trash; an older glasses build without a trash list has none there. */
    private fun captures(base: String): Map<String, Location> {
        val found = linkedMapOf<String, Location>()
        val gallery = request(base, "/api/gallery", 10_000)
        checkStatus(gallery.status)
        data(gallery)?.get("photos")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEach { element ->
            val name = element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("name")
                ?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val capture = name.substringBefore('/')
            val file = name.substringAfter('/', "")
            if (CAPTURE.matches(capture) && PHOTO_FILE.matches(file) && (capture !in found || file.startsWith("base."))) {
                found[capture] = Location(file, trashed = false)
            }
        }
        val trash = runCatching { request(base, "/api/v3/trash", 10_000) }.getOrNull()
        if (trash != null && trash.status in 200..299) {
            data(trash)?.get("captures")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEach { element ->
                val capture = element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("capture_id")
                    ?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (CAPTURE.matches(capture) && capture !in found) found[capture] = Location(null, trashed = true)
            }
        }
        return found
    }

    /** The photo's JPEG from the gallery, else the trash; null while it is not readable yet. */
    private fun download(base: String, capture: String, location: Location): ByteArray? {
        val file = location.file ?: "base.jpg"
        val paths = if (location.trashed) listOf("$TRASH/$capture/$file") else listOf("$capture/$file", "$TRASH/$capture/$file")
        for (path in paths) {
            val answer = request(base, "/api/download?file=" + URLEncoder.encode(path, "UTF-8").replace("%2F", "/"), 30_000, readBody = true)
            val bytes = answer.body
            if (answer.status in 200..299 && bytes != null && isJpeg(bytes)) return bytes
        }
        return null
    }

    private class Answer(val status: Int, val body: ByteArray?)

    private fun request(base: String, path: String, readTimeout: Int, readBody: Boolean = true): Answer {
        val connection = try {
            connectionFactory(URI.create(base + path).toURL()) as HttpURLConnection
        } catch (error: IllegalArgumentException) {
            throw GlassesCameraException(GlassesCameraProblem.NOT_SET_UP, "The glasses camera address is not a URL")
        }
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeout
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val body = if (readBody && status in 200..299) connection.inputStream.use { readLimited(it) } else null
            return Answer(status, body)
        } catch (error: GlassesCameraException) {
            throw error
        } catch (error: IOException) {
            log("glasses ${path.substringBefore('?')} failed ${error.javaClass.simpleName}")
            throw GlassesCameraException(GlassesCameraProblem.NOT_CONNECTED, "The glasses did not answer")
        } finally {
            connection.disconnect()
        }
    }

    private fun checkStatus(status: Int) {
        when {
            status in 200..299 -> Unit
            status == 401 || status == 403 ->
                throw GlassesCameraException(GlassesCameraProblem.NOT_ALLOWED, "The glasses do not let this phone in (HTTP $status)")
            else -> throw GlassesCameraException(GlassesCameraProblem.NOT_CONNECTED, "The glasses answered HTTP $status")
        }
    }

    private fun data(answer: Answer): JsonObject? = runCatching {
        JsonParser.parseString(String(answer.body ?: return null, StandardCharsets.UTF_8)).asJsonObject
            .get("data")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
    }.getOrNull()

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > MAX_PHOTO_BYTES) throw IOException("The glasses' answer is too large")
        }
        return output.toByteArray()
    }

    companion object {
        /** The server's image limit (agent notes): a larger photo could not be sent anyway. */
        const val MAX_PHOTO_BYTES = 20 * 1024 * 1024
        private const val TRASH = "_gallery_trash"
        private val CAPTURE = Regex("^IMG_[0-9A-Za-z_]+$")
        private val PHOTO_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.(?i:jpe?g)$")

        fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

        /** "http://host:8089" from what the user typed (scheme added, trailing slash dropped), or null. */
        fun normalizedBaseUrl(value: String?): String? {
            val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val withScheme = (if ("://" in text) text else "http://$text").trimEnd('/')
            val uri = runCatching { URI.create(withScheme) }.getOrNull() ?: return null
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
            if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawUserInfo != null) return null
            val authority = if (uri.port == -1) "${uri.rawAuthority}:$DEFAULT_PORT" else uri.rawAuthority
            return "${uri.scheme}://$authority"
        }

        /** The ASG camera server's port. */
        const val DEFAULT_PORT = 8089
    }
}
