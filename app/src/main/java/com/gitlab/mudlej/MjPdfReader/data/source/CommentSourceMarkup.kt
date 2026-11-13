// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

/** Small, conservative parser: marker identities and source bodies are never rewritten by inference. */
internal object CommentSourceMarkup {
    data class Marker(val id: String, val start: Int, val end: Int, val textStart: Int, val textEnd: Int,
                      val contents: String, val body: String?, val line: Int, val markdown: Boolean = false)
    private val command = Regex("\\\\(CommentAnchor|ReviewAnchor|CommentHighlight|CommentStrikethrough)(?![A-Za-z@])")
    private val escapes = linkedMapOf('\\' to "\\textbackslash{}", '^' to "\\textasciicircum{}", '~' to "\\textasciitilde{}",
        '{' to "\\{", '}' to "\\}", '%' to "\\%", '#' to "\\#", '&' to "\\&", '$' to "\\$", '_' to "\\_")
    fun encode(value: String) = value.map { escapes[it] ?: it.toString() }.joinToString("")
    fun decode(value: String): String {
        val result = StringBuilder(); var i = 0
        while (i < value.length) {
            val match = escapes.entries.firstOrNull { value.startsWith(it.value, i) }
            if (match != null) { result.append(match.key); i += match.value.length } else result.append(value[i++])
        }
        return result.toString()
    }
    private fun argument(source: String, position: Int): IntRange? {
        var i = position
        while (i < source.length && source[i].isWhitespace()) i++
        if (source.getOrNull(i) != '{') return null
        val start = ++i; var depth = 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> { i += 2; continue }
                '{' -> depth++
                '}' -> if (--depth == 0) return start until i
            }
            i++
        }
        return null
    }
    private fun hiddenRanges(source: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        // Live wrappers per the agent-proposal spec: definitions, mentions, comments and verbatim
        // text are not wrappers. A wrapper hides its ID, explanation and original; its replacement
        // stays visible because that is the text the PDF shows.
        val parsed = AgentProposals.parse(AgentProposals.Format.TEX, source)
        val wrapperEnds = parsed.proposals.associate { it.start to it.groups[2].end } +
            parsed.issues.associate { it.start to (it.groups.getOrNull(2)?.end ?: it.end) }
        val tokens = Regex("\\\\(?:agentProposedEdit|begin|code|detokenize|url)(?![A-Za-z@])")
        for (token in tokens.findAll(source)) {
            if (ranges.any { token.range.first in it }) continue
            var end = token.range.last + 1
            when (token.value) {
                "\\agentProposedEdit" -> end = wrapperEnds[token.range.first] ?: continue
                "\\begin" -> {
                    val name = argument(source, end) ?: continue
                    val environment = source.substring(name)
                    if (environment !in setOf("verbatim", "Verbatim", "lstlisting", "minted", "DocCode")) continue
                    val closing = "\\end{$environment}"
                    end = source.indexOf(closing, name.last + 2).takeIf { it >= 0 }?.plus(closing.length) ?: source.length
                }
                else -> end = argument(source, end)?.last?.plus(2) ?: source.length
            }
            ranges += token.range.first until end
        }
        return ranges
    }
    fun markers(source: String, markdown: Boolean = false): List<Marker> {
        if (markdown) return markdownMarkers(source)

        val hidden = hiddenRanges(source)
        return command.findAll(source).mapNotNull { match ->
        val lineStart = source.lastIndexOf('\n', match.range.first - 1) + 1
        val prefix = source.substring(lineStart, match.range.first)
        if (Regex("(?<!\\\\)%").containsMatchIn(prefix) || prefix.contains("\\newcommand") || prefix.contains("\\providecommand")) return@mapNotNull null
        if (hidden.any { match.range.first in it }) return@mapNotNull null
        val id = argument(source, match.range.last + 1) ?: return@mapNotNull null
        val text = argument(source, id.last + 2) ?: return@mapNotNull null
        val body = if (match.groupValues[1] in setOf("CommentHighlight", "CommentStrikethrough"))
            argument(source, text.last + 2) ?: return@mapNotNull null else null
        Marker(source.substring(id), match.range.first, (body ?: text).last + 2, text.first, text.last + 1,
            decode(source.substring(text)), body?.let(source::substring), source.take(match.range.first).count { it == '\n' } + 1)
    }.toList()
    }
    /** anchor, highlight, strikethrough, review_anchor (TeX) or markdown: which command wrote [marker]. */
    fun kind(source: String, marker: Marker): String = when {
        marker.markdown -> "markdown"
        source.startsWith("\\CommentHighlight", marker.start) -> "highlight"
        source.startsWith("\\CommentStrikethrough", marker.start) -> "strikethrough"
        source.startsWith("\\ReviewAnchor", marker.start) -> "review_anchor"
        else -> "anchor"
    }

    fun update(source: String, marker: Marker, contents: String): String {
        if (marker.markdown) {
            val metadata = com.google.gson.JsonParser.parseString(source.substring(marker.textStart, marker.textEnd)).asJsonObject
            metadata.addProperty("contents", contents)
            return source.replaceRange(marker.textStart, marker.textEnd, metadata.toString())
        }
        return source.replaceRange(marker.textStart, marker.textEnd, encode(contents))
    }
    fun remove(source: String, marker: Marker): String {
        if (!marker.markdown) return source.replaceRange(marker.start, marker.end, marker.body.orEmpty())
        val metadata = com.google.gson.JsonParser.parseString(source.substring(marker.textStart, marker.textEnd)).asJsonObject
        val key = metadata.get("key").asString
        val tokens = Regex("<!--\\s*thoughts:v1:(start|end|point)\\s+${Regex.escape(key)}\\s*-->")
        val ranges = markdownVisibleLines(source).mapNotNull { (offset, line) ->
            tokens.findAll(line).map { match -> (offset + match.range.first) until (offset + match.range.last + 1) }.toList()
        }.flatten() + listOf(marker.start until marker.end)
        var result = source
        for (range in ranges.sortedByDescending { it.first }) result = result.removeRange(range.first, range.last + 1)
        return result
    }

    internal fun markdownVisibleLines(source: String): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var fence: Char? = null
        var fenceLength = 0
        var offset = 0
        for (line in source.split('\n')) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val length = trimmed.takeWhile { it == trimmed.first() }.length
                if (fence == null) { fence = trimmed.first(); fenceLength = length }
                else if (fence == trimmed.first() && length >= fenceLength && trimmed.drop(length).isBlank()) fence = null
            } else if (fence == null && line.length - trimmed.length <= 3 && '`' !in line) result += offset to line
            offset += line.length + 1
        }
        return result
    }

    private fun markdownMarkers(source: String): List<Marker> {
        val visible = markdownVisibleLines(source)
        val tokens = Regex("<!--\\s*thoughts:v1:(start|end|point)\\s+(comment:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)\\s*-->")
        val actions = visible.flatMap { tokens.findAll(it.second).toList() }.groupBy { it.groupValues[2] }
        val liveKeys = actions.filterValues { matches ->
            matches.count { it.groupValues[1] == "point" } == 1 ||
                matches.count { it.groupValues[1] == "start" } == 1 && matches.count { it.groupValues[1] == "end" } == 1
        }.keys
        return visible.mapNotNull { (offset, line) ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("<!-- thoughts:v1:meta ") || !trimmed.endsWith("-->")) return@mapNotNull null
            val start = line.indexOf('{'); val end = line.lastIndexOf('}') + 1
            val metadata = if (start >= 0 && end > start) runCatching {
                com.google.gson.JsonParser.parseString(line.substring(start, end)).asJsonObject
            }.getOrNull() else null
            val key = metadata?.get("key")?.asString.orEmpty()
            if (key !in liveKeys) return@mapNotNull null
            Marker(key.split(':')[1], offset + line.indexOf("<!--"), offset + line.length,
                offset + start, offset + end, metadata?.get("contents")?.asString.orEmpty(), null,
                source.take(offset).count { it == '\n' } + 1, true)
        }
    }

    private val markdownId = Regex("[A-Za-z0-9_-]+")

    /** The two-line Markdown point marker, as the desktop and server write it. */
    private fun markdownPoint(id: String, contents: String): String {
        val key = "comment:$id:s1"
        // `<` and `>` are escaped so the metadata can never close its HTML comment early.
        val metadata = com.google.gson.JsonObject().apply { addProperty("key", key); addProperty("contents", contents) }
            .toString().replace("<", "\\u003c").replace(">", "\\u003e")
        return "<!-- thoughts:v1:meta $metadata -->\n<!-- thoughts:v1:point $key -->\n"
    }

    /** Inserts a Markdown point marker before [line] when that line is outside fenced or indented code. */
    fun insertMarkdownPoint(source: String, line: Int, id: String, contents: String): String? {
        if (!markdownId.matches(id) || source.contains(id)) return null
        val lines = source.split('\n')
        if (line !in 1..lines.size) return null
        val start = lines.take(line - 1).sumOf { it.length + 1 }
        if (markdownVisibleLines(source).none { it.first == start }) return null
        return source.substring(0, start) + markdownPoint(id, contents) + source.substring(start)
    }

    /** The last resort: a point marker at the end of a Markdown file. */
    fun appendMarkdownPoint(source: String, id: String, contents: String): String? {
        if (!markdownId.matches(id) || source.contains(id)) return null
        val separator = if (source.isEmpty() || source.endsWith('\n')) "" else "\n"
        return source + separator + markdownPoint(id, contents)
    }

    /** The last resort: a point anchor before `\end{document}`, or at the end of the file. */
    fun insertPointAtEnd(source: String, id: String, contents: String): String? {
        if (!Regex("[A-Za-z0-9._:-]+").matches(id) || source.contains(id)) return null
        val anchor = "\\CommentAnchor{$id}{${encode(contents)}}%\n"
        val lines = source.split('\n')
        val hidden = hiddenRanges(source)
        var offset = 0
        for (text in lines) {
            if (text.trimStart().startsWith("\\end{document}") && hidden.none { offset in it }) {
                return source.substring(0, offset) + anchor + source.substring(offset)
            }
            offset += text.length + 1
        }
        val separator = if (source.isEmpty() || source.endsWith('\n')) "" else "\n"
        return source + separator + anchor
    }

    fun insert(source: String, line: Int, id: String, contents: String, subtype: String, selectedText: String): String? {
        if (!Regex("[A-Za-z0-9._:-]+").matches(id) || source.contains(id)) return null
        val lines = source.split('\n')
        if (line !in 1..lines.size) return null
        val start = lines.take(line - 1).sumOf { it.length + 1 }
        val text = lines[line - 1]
        if (hiddenRanges(source).any { start in it } || text.contains("\\begin") || text.contains("\\end") || text.contains('%')) return null
        if (subtype in setOf("Highlight", "StrikeOut", "Underline", "Squiggly")) {
            if (selectedText.isBlank() || selectedText.any { it in "\\{}%" } || text.contains("\\Comment")) return null
            val offset = text.indexOf(selectedText)
            if (offset < 0 || text.indexOf(selectedText, offset + 1) >= 0) return null
            if (Regex("\\\\[A-Za-z@]*$").containsMatchIn(text.substring(0, offset))) return null
            if (hiddenRanges(source).any { start + offset in it }) return null
            val macro = if (subtype == "StrikeOut") "CommentStrikethrough" else "CommentHighlight"
            return source.replaceRange(start + offset, start + offset + selectedText.length,
                "\\$macro{$id}{${encode(contents)}}{$selectedText}")
        }
        if (text.isBlank() || text.trimStart().startsWith("\\") && !text.trimStart().startsWith("\\item ")) return null
        return source.substring(0, start) + "\\CommentAnchor{$id}{${encode(contents)}}%\n" + source.substring(start)
    }
}
