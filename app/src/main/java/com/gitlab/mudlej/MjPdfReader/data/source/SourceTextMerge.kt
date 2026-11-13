// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import java.nio.charset.StandardCharsets

/** Bounded three-way text merge shared by source installation and the editor. */
internal object SourceTextMerge {
    private data class Edit(val start: Int, val end: Int, val text: List<String>)
    private fun lines(text: String): List<String> = Regex("[^\\n]*\\n|[^\\n]+$").findAll(text).map { it.value }.toList()

    private fun edits(base: List<String>, changed: List<String>): List<Edit> {
        var prefix = 0
        while (prefix < minOf(base.size, changed.size) && base[prefix] == changed[prefix]) prefix++
        var end = base.size
        var otherEnd = changed.size
        while (end > prefix && otherEnd > prefix && base[end - 1] == changed[otherEnd - 1]) { end--; otherEnd-- }
        val n = end - prefix; val m = otherEnd - prefix
        if (n == 0 && m == 0) return emptyList()
        // Bound memory and work for unusually large rewrites; a wider conflict is safe.
        if ((n + 1L) * (m + 1L) > 4_000_000) return listOf(Edit(prefix, end, changed.subList(prefix, otherEnd)))
        val lengths = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lengths[i][j] = if (base[prefix + i] == changed[prefix + j]) lengths[i + 1][j + 1] + 1
                else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
        val result = mutableListOf<Edit>()
        var i = 0; var j = 0
        while (i < n || j < m) {
            if (i < n && j < m && base[prefix + i] == changed[prefix + j]) { i++; j++; continue }
            val start = i; val replacement = mutableListOf<String>()
            while (i < n || j < m) {
                if (i < n && j < m && base[prefix + i] == changed[prefix + j]) break
                if (j < m && (i == n || lengths[i][j + 1] >= lengths[i + 1][j])) replacement.add(changed[prefix + j++])
                else i++
            }
            result.add(Edit(prefix + start, prefix + i, replacement))
        }
        return result
    }

    fun merge(baseText: String, localText: String, currentText: String): String =
        mergeText(baseText, localText, currentText, false)

    private fun mergeText(baseText: String, localText: String, currentText: String, characters: Boolean): String {
        if (localText == baseText || localText == currentText) return currentText
        if (currentText == baseText) return localText
        fun tokens(text: String) = if (characters) text.map { it.toString() } else lines(text)
        val base = tokens(baseText)
        val local = edits(base, tokens(localText)); val current = edits(base, tokens(currentText))
        val all = (local.map { it to true } + current.map { it to false }).sortedWith(compareBy({ it.first.start }, { it.first.end }))
        val output = StringBuilder(); var position = 0; var index = 0
        fun render(start: Int, end: Int, changes: List<Edit>): String = buildString {
            var cursor = start
            for (edit in changes) { append(base.subList(cursor, edit.start).joinToString("")); append(edit.text.joinToString("")); cursor = edit.end }
            append(base.subList(cursor, end).joinToString(""))
        }
        fun version(label: String, text: String) { output.append(label).append('\n').append(text); if (text.isNotEmpty() && !text.endsWith('\n')) output.append('\n') }
        while (index < all.size) {
            val group = mutableListOf(all[index++]); val start = group[0].first.start; var end = group[0].first.end
            while (index < all.size) {
                val next = all[index].first
                // Adjacent replacements are independent; competing insertions at one point are not.
                if (next.start >= end && !(next.start == start && next.end == start && end == start)) break
                group.add(all[index++]); end = maxOf(end, next.end)
            }
            output.append(base.subList(position, start).joinToString(""))
            val left = group.filter { it.second }.map { it.first }; val right = group.filter { !it.second }.map { it.first }
            val a = render(start, end, left); val b = render(start, end, right)
            when {
                left.isEmpty() -> output.append(b)
                right.isEmpty() || a == b -> output.append(a)
                else -> {
                    val original = base.subList(start, end).joinToString("")
                    // Separate edits within one prose line can also merge without a conflict.
                    val refined = if (!characters && lines(original).size <= 1 && lines(a).size <= 1 && lines(b).size <= 1)
                        mergeText(original, a, b, true) else null
                    if (refined != null && !com.gitlab.mudlej.MjPdfReader.data.source.containsConflictMarkers(refined)) {
                        output.append(refined)
                        position = end
                        continue
                    }
                    if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
                    version("<<<<<<< Your draft", a)
                    version("||||||| Previous source", base.subList(start, end).joinToString(""))
                    version("=======", b)
                    output.append(">>>>>>> Synced source\n")
                }
            }
            position = end
        }
        output.append(base.subList(position, base.size).joinToString(""))
        return output.toString()
    }

}

internal fun cleanSourceMaterializationMerge(base: String, local: String, remote: String, path: String): String? {
    return runCatching {
        val merged = SourceTextMerge.merge(base, local, remote)
        if (containsConflictMarkers(merged) ||
            merged.toByteArray(StandardCharsets.UTF_8).size > SourceEditQueue.MAX_SOURCE_BYTES) return null
        val markdown = path.endsWith(".md") || path.endsWith(".markdown")
        if (SourceAnnotationMarkup.scan(merged, markdown).get("valid")?.asBoolean != true) return null
        merged
    }.getOrNull()
}
