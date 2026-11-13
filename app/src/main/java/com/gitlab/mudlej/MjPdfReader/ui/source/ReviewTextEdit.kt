// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * One undo step of the source review editor, stored as the changed span instead of two full
 * copies of the file. `before = after[0, start) + removed + after[start + inserted.length, ...)`.
 * The same format is produced by `textEdit` in assets/proposal-review/engine.js.
 *
 * Spans never split a UTF-16 surrogate pair: a lone surrogate would not survive UTF-8 encoding.
 */
internal data class ReviewTextEdit(val start: Int, val removed: String, val inserted: String, val afterLength: Int) {
    val beforeLength get() = afterLength - inserted.length + removed.length

    fun undo(after: String): String {
        require(matchesAfter(after)) { "Undo revision no longer matches this source." }
        return after.substring(0, start) + removed + after.substring(start + inserted.length)
    }

    fun redo(before: String): String {
        require(matchesBefore(before)) { "Redo revision no longer matches this source." }
        return before.substring(0, start) + inserted + before.substring(start + removed.length)
    }

    fun matchesAfter(text: String) = text.length == afterLength && text.startsWith(inserted, start)
    fun matchesBefore(text: String) = text.length == beforeLength && text.startsWith(removed, start)

    fun toJson(event: JsonElement? = null, typing: Boolean = false) = JsonObject().apply {
        addProperty("start", start)
        addProperty("removed", removed)
        addProperty("inserted", inserted)
        addProperty("afterLength", afterLength)
        add("event", event ?: JsonNull.INSTANCE)
        if (typing) addProperty("typing", true)
    }

    companion object {
        fun of(before: String, after: String): ReviewTextEdit {
            val max = minOf(before.length, after.length)
            var start = 0
            while (start < max && before[start] == after[start]) start++
            if (start > 0 && before[start - 1].isHighSurrogate()) start--
            var tail = 0
            while (tail < max - start && before[before.length - 1 - tail] == after[after.length - 1 - tail]) tail++
            if (tail > 0 && before[before.length - tail].isLowSurrogate()) tail--
            return ReviewTextEdit(
                start,
                before.substring(start, before.length - tail),
                after.substring(start, after.length - tail),
                after.length,
            )
        }

        fun fromJson(value: JsonObject): ReviewTextEdit? {
            fun int(key: String) = value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            fun text(key: String) = value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val edit = ReviewTextEdit(
                int("start") ?: return null, text("removed") ?: return null,
                text("inserted") ?: return null, int("afterLength") ?: return null,
            )
            return edit.takeIf { it.start >= 0 && it.start + it.inserted.length <= it.afterLength }
        }

        /** FNV-1a over UTF-16 code units; `textHash` in ui.js computes the same value. */
        fun hash(text: String): Long {
            var h = 0x811c9dc5.toInt()
            for (c in text) {
                h = h xor c.code
                h *= 0x01000193
            }
            return h.toLong() and 0xffffffffL
        }
    }
}
