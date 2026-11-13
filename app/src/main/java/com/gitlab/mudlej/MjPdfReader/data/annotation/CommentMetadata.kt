// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonObject
import java.net.URI

fun normalizeChatGptUrl(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val host = parsed.host?.lowercase().orEmpty()
    val chatGptHost = host == "chatgpt.com" || host.endsWith(".chatgpt.com")
    if (!parsed.scheme.equals("https", ignoreCase = true) || !chatGptHost || parsed.rawUserInfo != null) {
        return null
    }
    return parsed.toASCIIString()
}

enum class CommentPriority(val wireValue: String) {
    NORMAL("normal"),
    HIGH("high");

    companion object {
        fun fromWireValue(value: String?): CommentPriority =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: NORMAL
    }
}

data class CommentLocation(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val googlePlaceId: String = "",
    val source: String = SOURCE_GOOGLE_MAPS,
    val accuracyMeters: Double? = null,
    val confirmedAt: String = "",
) {
    fun isValid(): Boolean = label.isNotBlank() &&
        latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(KEY_LABEL, label)
        addProperty(KEY_LATITUDE, latitude)
        addProperty(KEY_LONGITUDE, longitude)
        googlePlaceId.takeIf(String::isNotBlank)?.let {
            addProperty(KEY_GOOGLE_PLACE_ID, it)
        }
        addProperty(KEY_SOURCE, source)
        accuracyMeters?.takeIf(Double::isFinite)?.let {
            addProperty(KEY_ACCURACY_METERS, it)
        }
        confirmedAt.takeIf(String::isNotBlank)?.let {
            addProperty(KEY_CONFIRMED_AT, it)
        }
    }

    companion object {
        const val SOURCE_GOOGLE_MAPS = "google_maps"
        const val SOURCE_MAP_PIN = "google_maps_pin"
        const val SOURCE_CURRENT_LOCATION = "current_location"

        private const val KEY_LABEL = "label"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_GOOGLE_PLACE_ID = "google_place_id"
        private const val KEY_SOURCE = "source"
        private const val KEY_ACCURACY_METERS = "accuracy_meters"
        private const val KEY_CONFIRMED_AT = "confirmed_at"

        fun fromJson(json: JsonObject?): CommentLocation? {
            if (json == null) return null
            val location = runCatching {
                CommentLocation(
                    label = json.get(KEY_LABEL)?.asString.orEmpty(),
                    latitude = json.get(KEY_LATITUDE)?.asDouble ?: Double.NaN,
                    longitude = json.get(KEY_LONGITUDE)?.asDouble ?: Double.NaN,
                    googlePlaceId = json.get(KEY_GOOGLE_PLACE_ID)?.asString.orEmpty(),
                    source = json.get(KEY_SOURCE)?.asString.orEmpty()
                        .ifBlank { SOURCE_GOOGLE_MAPS },
                    accuracyMeters = json.get(KEY_ACCURACY_METERS)
                        ?.takeUnless { it.isJsonNull }
                        ?.asDouble,
                    confirmedAt = json.get(KEY_CONFIRMED_AT)?.asString.orEmpty(),
                )
            }.getOrNull()
            return location?.takeIf(CommentLocation::isValid)
        }
    }
}

/**
 * Writes an edit's [location] under [key] of a stored comment record. A place can only be kept or
 * removed in the app, never chosen or changed, so an edit normally carries the place the record
 * already has: that one stays exactly as stored, with every field and in the stored form, instead
 * of being written again from what this version reads of it. Without a place, the key is removed
 * ([removeWhenAbsent]) or set to JSON null.
 */
internal fun JsonObject.keepOrReplaceCommentLocation(key: String, location: CommentLocation?, removeWhenAbsent: Boolean) {
    val stored = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    when {
        location == null -> if (removeWhenAbsent) remove(key) else add(key, com.google.gson.JsonNull.INSTANCE)
        CommentLocation.fromJson(stored) == location -> Unit
        else -> add(key, location.toJson())
    }
}
