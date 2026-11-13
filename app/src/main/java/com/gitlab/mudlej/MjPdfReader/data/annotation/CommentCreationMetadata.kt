// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID
import kotlin.math.abs

object CommentCreationMetadata {
    const val THOUGHTS = "thoughts"
    const val LEGACY_OKULAR_ENTER = "okular-enter"
    const val LEGACY_MJ_PDF_READER = "mj-pdf-reader"
    const val CREATED_IN_APP_KEY = "created_in_app"
    const val CANONICAL_POPUP_ICON = "Comment"
    const val DEFAULT_POPUP_COLOR: Int = 0xFFFFFF00.toInt()

    private val plainUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    fun newThoughtsId(): String = "thoughts-${UUID.randomUUID()}"

    fun canonicalPopupIcon(subtype: String): String? =
        CANONICAL_POPUP_ICON.takeIf { subtype == "Text" }

    /**
     * The author the old MJ PDF reader build wrote into its notes (Gradle property
     * `thoughtsLegacyMjPdfAuthor`, like the server's `THOUGHTS_LEGACY_MJ_PDF_AUTHOR`), or null when the
     * build names none: then the plain-UUID rule in [infer] is off.
     */
    val LEGACY_MJ_PDF_AUTHOR: String? =
        com.gitlab.mudlej.MjPdfReader.BuildConfig.LEGACY_MJ_PDF_AUTHOR.trim().takeIf(String::isNotEmpty)

    /**
     * The app that created [record]. A plain-UUID yellow Text note is the old MJ PDF reader's only
     * when its author is [legacyMjAuthor]; without one, such records stay unattributed.
     */
    fun infer(record: JsonObject, legacyMjAuthor: String? = LEGACY_MJ_PDF_AUTHOR): String? {
        val explicit = record.stringValue(CREATED_IN_APP_KEY)
        if (explicit == THOUGHTS || explicit == LEGACY_OKULAR_ENTER || explicit == LEGACY_MJ_PDF_READER) return explicit
        val id = record.stringValue("id")
        if (id.startsWith("thoughts-")) return THOUGHTS
        if (id.startsWith("okular-")) return LEGACY_OKULAR_ENTER
        if (id.startsWith("mjpdf-")) return LEGACY_MJ_PDF_READER
        val flags = record.intValue("flags")
        return if (
            !legacyMjAuthor.isNullOrBlank() &&
            record.stringValue("subtype") == "Text" &&
            plainUuid.matches(id) &&
            record.stringValue("author") == legacyMjAuthor &&
            (flags == 28 || flags == 60) &&
            record.hasLegacyMjColor()
        ) LEGACY_MJ_PDF_READER else null
    }

    fun canonicalPopupColorJson(): JsonArray = JsonArray().apply {
        add(1.0)
        add(1.0)
        add(0.0)
    }

    fun upgradeLegacyRecord(
        record: JsonObject,
        previousSchema: Int,
        currentSchema: Int,
        legacyMjAuthor: String? = LEGACY_MJ_PDF_AUTHOR,
    ) {
        infer(record, legacyMjAuthor)?.let { record.addProperty(CREATED_IN_APP_KEY, it) }
        val deleted = record.get("deleted")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
        }?.asBoolean == true
        if (previousSchema < currentSchema && record.stringValue("subtype") == "Text" && !deleted) {
            record.add("color", canonicalPopupColorJson())
        }
    }

    private fun JsonObject.stringValue(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

    private fun JsonObject.intValue(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0

    private fun JsonObject.hasLegacyMjColor(): Boolean {
        val values = get("color")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        if (values.size() < 3) return false
        fun channel(index: Int): Double {
            val value = values[index].asDouble
            return if (value > 1.0) value / 255.0 else value
        }
        return abs(channel(0) - 250.0 / 255.0) < 0.00001 &&
            abs(channel(1) - 217.0 / 255.0) < 0.00001 &&
            abs(channel(2)) < 0.00001
    }
}
