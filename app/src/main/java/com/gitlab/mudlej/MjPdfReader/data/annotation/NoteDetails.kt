// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonNull
import com.google.gson.JsonObject

/** Note context is independent of PDF anchors and geographic coordinates. */
data class NoteDetails(
    val projectId: String = "",
    val projectName: String = "",
    val checkWhere: String = "",
    val calendarDate: String = "",
    val calendarTime: String = "",
    val calendarZoneId: String = "",
    /** ISO date before which the to-do stays out of the To-dos list; blank means always shown. */
    val visibleFrom: String = "",
) {
    fun hiddenOn(today: java.time.LocalDate): Boolean =
        runCatching { java.time.LocalDate.parse(visibleFrom) }.getOrNull()?.isAfter(today) == true

    fun applyTo(payload: JsonObject): JsonObject = payload.apply {
        require(get("subtype")?.asString == "Note")
        add("project", JsonNull.INSTANCE)
        remove("project_name")
        addProperty("check_where", checkWhere)
        addProperty("calendar_date", calendarDate)
        addProperty("calendar_time", calendarTime)
        addProperty("calendar_zone_id", calendarZoneId)
        addProperty("visible_from", visibleFrom)
    }

    companion object {
        fun fromJson(payload: JsonObject): NoteDetails {
            fun text(key: String) = payload.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
            return NoteDetails("", "", text("check_where"),
                text("calendar_date"), text("calendar_time"), text("calendar_zone_id"), text("visible_from"))
        }
    }
}
