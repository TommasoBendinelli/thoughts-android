// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Connected source folder names (`docs-<slug>-<hex>`; see the source-folder-names spec).
 *
 * Fedora renames every connected folder once and sends the map M (`source_folder_renames`:
 * `[{folder_id, old, new}]`) with the Project catalogue. This phone never computes names; it
 * applies M with [rewrite] (Rule P), the one rewrite Fedora, the desktop and this phone share.
 */
internal object SourceFolderNames {
    data class Rename(val folderId: String, val old: String, val new: String)

    /** Any connected folder name, old or new (the validators everywhere accept both). */
    private val anyName = Regex("docs-[A-Za-z0-9_.-]+")
    /** The strictest validator: every name Fedora gives a folder matches it. */
    private val newName = Regex("docs-[A-Za-z0-9][A-Za-z0-9_.-]*")

    private fun isNameChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.' || c == '-'

    /**
     * Rule P: every `old` of [renames] that is a whole token (the character before is the start
     * or not in `[A-Za-z0-9_.-]`, the character after is the end or not in that class) becomes
     * its `new`. Case-sensitive, one pass, longest `old` first, output is never scanned again.
     * A token right after the literal `source:` is part of a Project identifier (legacy Projects
     * are called `source:docs-<folder>`, and their default folder is sha256 of that id): it is
     * never rewritten. `project:docs-x` comment scope keys name the folder and are rewritten.
     */
    fun rewrite(text: String, renames: Map<String, String>): String {
        if (renames.isEmpty() || !text.contains("docs-")) return text
        val olds = renames.keys.sortedByDescending(String::length)
        var output: StringBuilder? = null
        var copied = 0
        var index = 0
        while (index < text.length) {
            if (text[index] == 'd' && (index == 0 || !isNameChar(text[index - 1])) && !afterProjectIdPrefix(text, index)) {
                val match = olds.firstOrNull { old ->
                    text.startsWith(old, index) && (index + old.length == text.length || !isNameChar(text[index + old.length]))
                }
                if (match != null) {
                    val builder = output ?: StringBuilder(text.length + 16).also { output = it }
                    builder.append(text, copied, index).append(renames.getValue(match))
                    index += match.length
                    copied = index
                    continue
                }
            }
            index++
        }
        return output?.append(text, copied, text.length)?.toString() ?: text
    }

    /** The literal that starts a legacy Project identifier (`source:docs-<folder>`). */
    const val PROJECT_ID_PREFIX = "source:"

    private fun afterProjectIdPrefix(text: String, index: Int): Boolean =
        index >= PROJECT_ID_PREFIX.length && text.startsWith(PROJECT_ID_PREFIX, index - PROJECT_ID_PREFIX.length)

    fun rewrite(text: String, renames: List<Rename>): String = rewrite(text, toMap(renames))

    /** True when [text] holds an `old` of [renames] as a whole token. */
    fun contains(text: String, renames: Map<String, String>): Boolean = rewrite(text, renames) != text

    fun toMap(renames: List<Rename>): Map<String, String> = renames.associate { it.old to it.new }

    /**
     * Rule P on every string value of [value], except values of keys [skip] selects and
     * multi-line strings (source text is handled by Fedora's link rewrite only). Object keys are
     * rewritten too (maps keyed by a path or a folder). Returns [value] itself when nothing changed.
     */
    fun rewriteJsonValues(
        value: JsonElement,
        renames: Map<String, String>,
        skip: (String) -> Boolean = DefaultFolder.SOURCE_TEXT,
    ): JsonElement = when {
        renames.isEmpty() -> value
        value.isJsonObject -> {
            val source = value.asJsonObject
            var changed = false
            val entries = source.entrySet().map { (key, child) ->
                val newKey = if ('\n' in key) key else rewrite(key, renames)
                val rewritten = if (skip(key)) child else rewriteJsonValues(child, renames, skip)
                if (newKey != key || rewritten !== child) changed = true
                Triple(key, newKey, rewritten)
            }
            if (!changed) source else JsonObject().apply {
                for ((key, newKey, child) in entries) {
                    // A key already present in its new spelling (Fedora's) wins over the old one.
                    if (newKey != key && source.has(newKey)) continue
                    add(newKey, child)
                }
            }
        }
        value.isJsonArray -> {
            val source = value.asJsonArray
            var copy: JsonArray? = null
            source.forEachIndexed { index, child ->
                val rewritten = rewriteJsonValues(child, renames, skip)
                if (rewritten !== child) {
                    if (copy == null) copy = source.deepCopy()
                    copy!!.set(index, rewritten)
                }
            }
            copy ?: source
        }
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
            val text = value.asString
            if ('\n' in text) value else rewrite(text, renames).let { if (it == text) value else JsonPrimitive(it) }
        }
        else -> value
    }

    /**
     * M as Fedora sent it (`source_folder_renames`), or an empty list when absent. Entries that
     * are not folder names are dropped; a map that is not one-to-one (duplicate `old` or `new`,
     * or a `new` that is another entry's `old`) is ignored as a whole, since applying it could
     * not be undone.
     */
    fun parse(value: JsonElement?): List<Rename> {
        val array = value?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyList()
        val renames = array.mapNotNull { element ->
            val entry = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            fun field(name: String) = entry.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val old = field("old")?.takeIf(anyName::matches) ?: return@mapNotNull null
            val new = field("new")?.takeIf(newName::matches) ?: return@mapNotNull null
            if (old == new) return@mapNotNull null
            Rename(field("folder_id").orEmpty(), old, new)
        }.distinct()
        val olds = renames.map(Rename::old)
        val news = renames.map(Rename::new)
        if (olds.toSet().size != olds.size || news.toSet().size != news.size || news.any(olds::contains)) return emptyList()
        return renames
    }

    fun toJson(renames: List<Rename>): JsonArray = JsonArray().apply {
        renames.forEach { rename ->
            add(JsonObject().apply {
                addProperty("folder_id", rename.folderId); addProperty("old", rename.old); addProperty("new", rename.new)
            })
        }
    }

    /**
     * Old name → new name for every folder this phone has moved (or started moving; see
     * SourceFolderNamesMigration). Paths still in an old spelling — a source bundle Fedora built
     * before its rebuild, a queued request, an editor opened before the move, a link in a PDF not
     * rebuilt yet — are read through it.
     */
    @Volatile var localRenames: Map<String, String> = emptyMap()
        private set

    /** [text] in the spelling this phone uses now. */
    fun followLocal(text: String): String = localRenames.let { if (it.isEmpty()) text else rewrite(text, it) }

    /** [value] in the spelling this phone uses now (source text untouched). */
    fun followLocal(value: JsonObject, skip: (String) -> Boolean = DefaultFolder.SOURCE_TEXT): JsonObject =
        localRenames.let { if (it.isEmpty()) value else rewriteJsonValues(value, it, skip).asJsonObject }

    /** Bumped whenever [localRenames] changes, for caches of values read through it. */
    @Volatile var generation: Long = 0
        private set

    fun installLocal(renames: Map<String, String>) {
        if (renames == localRenames) return
        localRenames = renames
        generation++
    }
}
