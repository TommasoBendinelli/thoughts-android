// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Native reader of agent proposals (thoughts-server protocol/agent-proposals.md, version 1) for
 * the code that runs outside the WebView: the plain source editor's save check, the PDF sheet's
 * excerpt and the source indexes. It mirrors assets/proposal-review/proposal-model.js, the shared
 * JavaScript model, and both are tested against the same fixtures (AgentProposalsFixtureTest,
 * scripts/test-proposal-review.cjs). Offsets are UTF-16 indices, like Kotlin strings.
 */
internal object AgentProposals {
    enum class Format { TEX, MARKDOWN }

    /** A braced LaTeX argument: [open] is the `{`, [end] is after the `}`. */
    data class Group(val open: Int, val contentStart: Int, val contentEnd: Int, val end: Int)

    data class Proposal(
        val id: String, val createdAt: String, val conversationId: String, val explanation: String,
        val original: String, val replacement: String, val start: Int, val end: Int,
        val replacementStart: Int, val replacementEnd: Int, val groups: List<Group>,
        /** Spec 3.6: the link to the conversation, null when the proposal has none. */
        val conversationUrl: String? = null,
    )

    /** A broken wrapper. [groups] are the complete LaTeX arguments that were read. */
    data class Issue(val code: String, val start: Int, val end: Int, val id: String?, val groups: List<Group> = emptyList())

    data class Parsed(val format: Format?, val proposals: List<Proposal>, val issues: List<Issue>) {
        /** Null when [id] names exactly one valid proposal, else the spec refusal code. */
        fun refusalCode(id: String): String? =
            if (proposals.count { it.id == id } == 1) null else issues.firstOrNull { it.id == id }?.code ?: "unknown_id"
    }

    const val COMMAND = "\\agentProposedEdit"
    private const val COMMAND_NAME = "agentProposedEdit"
    private const val ARGUMENTS = 4
    private val ID = Regex("[a-z0-9][a-z0-9._:-]{0,127}")
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val TIMESTAMP = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.[0-9]{1,9})?Z")
    // Mandatory since 2026-10-10 (spec 4.3): both values, in this order, then the optional link.
    private val TEX_METADATA = Regex("created=([^,]*),conversation_id=([^,]*)(?:,conversation_url=(.*))?", RegexOption.DOT_MATCHES_ALL)
    // Spec 3.6: a Claude Code session or a Codex thread.
    private val CONVERSATION_URL = Regex("https://claude\\.ai/code/session_[A-Za-z0-9]{1,64}|codex://threads/" + UUID.pattern)

    fun validConversationUrl(value: String?): Boolean = value != null && CONVERSATION_URL.matches(value)
    private const val ASCII_WS = " \t\n\r\u000c\u000b"
    private val VERBATIM_ENVIRONMENTS = setOf("verbatim", "verbatim*", "Verbatim", "Verbatim*", "BVerbatim", "BVerbatim*",
        "LVerbatim", "LVerbatim*", "lstlisting", "minted", "DocCode")
    private val DEFINERS = setOf("def", "gdef", "edef", "xdef", "let", "newcommand", "renewcommand", "providecommand",
        "DeclareRobustCommand", "NewDocumentCommand", "RenewDocumentCommand", "ProvideDocumentCommand",
        "DeclareDocumentCommand", "newrobustcmd", "renewrobustcmd", "providerobustcmd")

    fun format(path: String): Format? {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".tex") -> Format.TEX
            lower.endsWith(".md") || lower.endsWith(".markdown") -> Format.MARKDOWN
            else -> null
        }
    }

    fun parse(path: String, text: String): Parsed = parse(format(path), text)

    fun parse(format: Format?, text: String): Parsed = when (format) {
        Format.TEX -> parseTex(text)
        Format.MARKDOWN -> parseMarkdown(text)
        null -> Parsed(null, emptyList(), emptyList())
    }

    // ---------------------------------------------------------------- common

    private fun Char.asciiSpace() = this in ASCII_WS
    private fun String.trimAscii() = trim { it in ASCII_WS }
    private fun blank(value: String) = value.trimAscii().isEmpty()

    fun validTimestamp(value: String): Boolean {
        val m = TIMESTAMP.matchEntire(value) ?: return false
        val (year, month, day, hour, minute, second) = m.groupValues.drop(1).map { it.toInt() }
        if (year == 0 || month !in 1..12) return false
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
        return day in 1..days && hour <= 23 && minute <= 59 && second <= 59
    }
    private operator fun <T> List<T>.component6() = this[5]

    private fun findWithin(text: String, needle: String, from: Int, hi: Int): Int {
        val at = text.indexOf(needle, from)
        return if (at >= 0 && at + needle.length <= hi) at else -1
    }

    private class Wrapper(
        val code: String?, val start: Int, val end: Int, val id: String? = null,
        val orphanEnd: Boolean = false, val proposal: Proposal? = null, val groups: List<Group> = emptyList(),
    )

    private fun finish(format: Format, wrappers: List<Wrapper>): Parsed {
        val counts = wrappers.filter { it.id != null && !it.orphanEnd }.groupingBy { it.id!! }.eachCount()
        val proposals = mutableListOf<Proposal>()
        val issues = mutableListOf<Issue>()
        for (w in wrappers) {
            val code = w.code ?: if ((counts[w.id] ?: 0) > 1) "duplicate_id" else null
            if (code == null) proposals += w.proposal!! else issues += Issue(code, w.start, w.end, w.id, w.groups)
        }
        return Parsed(format, proposals, issues.sortedBy { it.start })
    }

    // ---------------------------------------------------------------- LaTeX

    private fun texLetter(c: Char?) = c != null && (c in 'A'..'Z' || c in 'a'..'z' || c == '@')

    private fun escaped(text: String, i: Int): Boolean {
        var count = 0
        while (i - 1 - count >= 0 && text[i - 1 - count] == '\\') count++
        return count % 2 == 1
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i].asciiSpace()) i++
            else if (text[i] == '%' && !escaped(text, i)) {
                val newline = text.indexOf('\n', i)
                i = if (newline < 0) text.length else newline + 1
            } else break
        }
        return i
    }

    private fun group(text: String, open: Int): Group? {
        var depth = 1
        var j = open + 1
        while (j < text.length) {
            when (text[j]) {
                '\\' -> { j += 2; continue }
                '%' -> {
                    val newline = text.indexOf('\n', j)
                    if (newline < 0) return null
                    j = newline + 1
                    continue
                }
                '{' -> depth++
                '}' -> if (--depth == 0) return Group(open, open + 1, j, j + 1)
            }
            j++
        }
        return null
    }

    private fun isDefinition(text: String, start: Int): Boolean {
        var k = start
        fun back() { while (k > 0 && text[k - 1].asciiSpace()) k-- }
        back()
        if (k > 0 && text[k - 1] == '{') { k--; back() }
        if (k > 0 && text[k - 1] == '*') { k--; back() }
        var j = k
        while (j > 0 && texLetter(text[j - 1])) j--
        return j < k && j > 0 && text[j - 1] == '\\' && !escaped(text, j - 1) && text.substring(j, k) in DEFINERS
    }

    /** Start and end-of-name of the next live wrapper candidate in [from, hi). */
    private fun nextCandidate(text: String, from: Int, hi: Int): Pair<Int, Int>? {
        var i = from
        while (i < hi) {
            val c = text[i]
            if (c == '%') {
                val newline = findWithin(text, "\n", i, hi)
                i = if (newline < 0) hi else newline + 1
                continue
            }
            if (c != '\\') { i++; continue }
            var after = i + 1
            while (after < hi && texLetter(text[after])) after++
            if (after == i + 1) { i += 2; continue }
            val word = text.substring(i + 1, after)
            if (word == "begin") {
                var k = after
                while (k < hi && (text[k] == ' ' || text[k] == '\t')) k++
                if (k < hi && text[k] == '{') {
                    val close = findWithin(text, "}", k + 1, hi)
                    val name = if (close >= 0) text.substring(k + 1, close) else ""
                    if (name in VERBATIM_ENVIRONMENTS) {
                        val closing = "\\end{$name}"
                        val end = findWithin(text, closing, close + 1, hi)
                        i = if (end < 0) hi else end + closing.length
                        continue
                    }
                }
            }
            if (word == "verb" || word == "lstinline") {
                val k = after + if (word == "verb" && after < hi && text[after] == '*') 1 else 0
                if (k < hi) {
                    val codePoint = text.codePointAt(k)
                    val delimiter = String(Character.toChars(codePoint))
                    if (!(delimiter.length == 1 && delimiter[0].asciiSpace()) && delimiter != "[" && delimiter != "{" &&
                        !Character.isLetter(codePoint)) {
                        val close = findWithin(text, delimiter, k + delimiter.length, hi)
                        val newline = findWithin(text, "\n", k + 1, hi)
                        if (close >= 0 && (newline < 0 || close < newline)) { i = close + delimiter.length; continue }
                    }
                }
            }
            if (word == COMMAND_NAME && !isDefinition(text, i)) {
                val k = skipSpace(text, after)
                if (k < text.length && (text[k] == '{' || text[k] == '[')) return i to after
            }
            i = after
        }
        return null
    }

    private fun parseTex(text: String): Parsed {
        val wrappers = mutableListOf<Wrapper>()
        val n = text.length
        var i = 0
        while (true) {
            val (start, nameEnd) = nextCandidate(text, i, n) ?: break
            var cursor = nameEnd
            var ident: String? = null
            var metadataError = true // a wrapper without metadata is invalid_metadata (spec 4.3)
            var created = ""
            var conversation = ""
            var conversationUrl: String? = null
            var k = skipSpace(text, cursor)
            if (text[k] == '[') {
                val close = text.indexOf(']', k + 1)
                if (close < 0) { wrappers += Wrapper("unclosed_group", start, n); break }
                val m = TEX_METADATA.matchEntire(text.substring(k + 1, close).trimAscii())
                val link = m?.groups?.get(3)?.value
                if (m != null && validTimestamp(m.groupValues[1]) && UUID.matches(m.groupValues[2]) &&
                    (link == null || validConversationUrl(link))) {
                    metadataError = false
                    created = m.groupValues[1]
                    conversation = m.groupValues[2]
                    conversationUrl = link
                }
                cursor = close + 1
            }
            val groups = mutableListOf<Group>()
            var unclosed = false
            for (argument in 0 until ARGUMENTS) {
                k = skipSpace(text, cursor)
                if (k >= n || text[k] != '{') break
                val g = group(text, k)
                if (g == null) { unclosed = true; break }
                groups += g
                cursor = g.end
                if (groups.size == 1) ident = text.substring(g.contentStart, g.contentEnd).trimAscii()
            }
            if (unclosed) { wrappers += Wrapper("unclosed_group", start, n, ident, groups = groups); break }
            i = cursor
            if (groups.size < ARGUMENTS) { wrappers += Wrapper("missing_argument", start, cursor, ident, groups = groups); continue }
            fun arg(index: Int) = text.substring(groups[index].contentStart, groups[index].contentEnd)
            val code = when {
                metadataError || blank(arg(1)) -> "invalid_metadata"
                !ID.matches(ident!!) -> "invalid_id"
                nextCandidate(text, groups[0].contentStart, groups[ARGUMENTS - 1].contentEnd) != null -> "nested_proposal"
                else -> null
            }
            if (code != null) { wrappers += Wrapper(code, start, cursor, ident, groups = groups); continue }
            val explanation = arg(1)
            wrappers += Wrapper(null, start, cursor, ident, groups = groups, proposal = Proposal(
                ident!!, created, conversation, explanation, arg(2), arg(3), start, cursor,
                groups[3].contentStart, groups[3].contentEnd, groups, conversationUrl,
            ))
        }
        return finish(Format.TEX, wrappers)
    }

    // ---------------------------------------------------------------- Markdown

    private const val PREFIX = "<!-- thoughts:agent-proposal:"
    private const val V2_START = "<!-- thoughts:agent-proposal:v2:start"
    private val V1_START = Regex("<!-- thoughts:agent-proposal:v1:start ([^ ]*) -->")
    private val END = Regex("<!-- thoughts:agent-proposal:(v1|v2):end (.*) -->", RegexOption.DOT_MATCHES_ALL)

    private data class Line(val content: String, val after: Int, val terminated: Boolean)
    private fun line(text: String, s: Int): Line {
        val newline = text.indexOf('\n', s)
        return if (newline < 0) Line(text.substring(s), text.length, false) else Line(text.substring(s, newline), newline + 1, true)
    }

    private fun nextMarker(text: String, from: Int, hi: Int): Int {
        var i = from
        while (true) {
            val s = findWithin(text, PREFIX, i, hi)
            if (s < 0) return -1
            if (s == 0 || text[s - 1] == '\n') return s
            i = s + 1
        }
    }

    private fun findEnd(text: String, version: String, ident: String, from: Int): IntRange? {
        val marker = "<!-- thoughts:agent-proposal:$version:end $ident -->\n"
        var i = from
        while (true) {
            val e = text.indexOf(marker, i)
            if (e < 0) return null
            if (e == 0 || text[e - 1] == '\n') return e until e + marker.length
            i = e + 1
        }
    }

    private class Header(val ident: String?, val created: String? = null, val conversation: String? = null,
                         val explanation: String? = null, val original: String? = null,
                         val url: String? = null, val urlPresent: Boolean = false) {
        val complete get() = created != null && conversation != null && explanation != null && original != null
        /** Spec 3.6: an absent link is fine; a present one must be valid (an empty or non-string one is not). */
        val urlValid get() = !urlPresent || validConversationUrl(url)
    }

    private fun decodeBlock(lines: List<String>, from: Int, label: String): Pair<String, Int>? {
        var position = from
        if (position >= lines.size || lines[position] != label) return null
        position++
        val parts = mutableListOf<String>()
        while (position < lines.size && lines[position].startsWith("| ")) {
            parts += lines[position].substring(2).replace("--&gt;", "-->").replace("&amp;", "&")
            position++
        }
        return parts.joinToString("\n") to position
    }

    private fun v2Header(lines: List<String>): Header {
        val ident = lines.firstOrNull()?.takeIf { it.startsWith("id: ") }?.substring(4)
        if (lines.size < 3 || !lines[1].startsWith("created_at: ") || !lines[2].startsWith("conversation_id: ")) return Header(ident)
        var position = 3
        val url = lines.getOrNull(position)?.takeIf { it.startsWith("conversation_url: ") }?.removePrefix("conversation_url: ")
        if (url != null) position++
        if (position < lines.size && lines[position].startsWith("replacement_length: ")) position++
        val (explanation, afterExplanation) = decodeBlock(lines, position, "explanation:") ?: return Header(ident)
        val (original, end) = decodeBlock(lines, afterExplanation, "original_text:") ?: return Header(ident)
        if (end != lines.size) return Header(ident)
        return Header(ident, lines[1].removePrefix("created_at: "), lines[2].removePrefix("conversation_id: "), explanation, original,
            url, url != null)
    }

    private fun v1Header(encoded: String): Header {
        if (!Regex("[A-Za-z0-9_-]+").matches(encoded)) return Header(null)
        val data: JsonObject = try {
            val raw = Base64.getUrlDecoder().decode(encoded)
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(raw) != encoded) return Header(null)
            val json = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
            val reader = JsonReader(StringReader(json)).apply { strictness = Strictness.STRICT }
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT || !element.isJsonObject) return Header(null)
            element.asJsonObject
        } catch (_: Exception) { return Header(null) }
        fun string(name: String) = data.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return Header(string("id"), string("created_at"), string("conversation_id"), string("explanation"), string("original_text"),
            string("conversation_url"), data.has("conversation_url"))
    }

    private fun parseMarkdown(text: String): Parsed {
        val wrappers = mutableListOf<Wrapper>()
        val n = text.length
        var i = 0
        while (true) {
            val s = nextMarker(text, i, n)
            if (s < 0) break
            val first = line(text, s)
            val version: String
            var headerEnd: Int
            val header: Header
            val v1 = if (first.terminated) V1_START.matchEntire(first.content) else null
            val end = if (first.terminated) END.matchEntire(first.content) else null
            if (first.terminated && first.content == V2_START) {
                version = "v2"
                var j = first.after
                val lines = mutableListOf<String>()
                headerEnd = -1
                while (j < n) {
                    val l = line(text, j)
                    if (l.content == "-->" && l.terminated) { headerEnd = l.after; break }
                    lines += l.content
                    j = l.after
                }
                if (headerEnd < 0) { wrappers += Wrapper("unclosed_group", s, n); break }
                header = v2Header(lines)
            } else if (v1 != null) {
                version = "v1"
                headerEnd = first.after
                header = v1Header(v1.groupValues[1])
            } else if (end != null) {
                // The ID names the issue for refusals but does not count towards duplicates.
                wrappers += Wrapper("unmatched_marker", s, first.after, end.groupValues[2], orphanEnd = true)
                i = first.after
                continue
            } else {
                wrappers += Wrapper("malformed_marker", s, first.after)
                i = first.after
                continue
            }
            val ident = header.ident
            if (ident.isNullOrEmpty()) {
                wrappers += Wrapper("invalid_metadata", s, headerEnd)
                i = headerEnd
                continue
            }
            val closing = findEnd(text, version, ident, headerEnd)
            if (closing == null) {
                wrappers += Wrapper("unmatched_marker", s, headerEnd, ident)
                i = headerEnd
                continue
            }
            val spanEnd = closing.last + 1
            i = spanEnd
            val code = when {
                !header.complete || !validTimestamp(header.created!!) || !UUID.matches(header.conversation!!) ||
                    blank(header.explanation!!) || !header.urlValid -> "invalid_metadata"
                !ID.matches(ident) -> "invalid_id"
                nextMarker(text, headerEnd, closing.first) >= 0 -> "nested_proposal"
                else -> null
            }
            wrappers += if (code != null) Wrapper(code, s, spanEnd, ident) else Wrapper(null, s, spanEnd, ident, proposal = Proposal(
                ident, header.created!!, header.conversation!!, header.explanation!!, header.original!!,
                text.substring(headerEnd, closing.first), s, spanEnd, headerEnd, closing.first, emptyList(), header.url,
            ))
        }
        return finish(Format.MARKDOWN, wrappers)
    }
}
