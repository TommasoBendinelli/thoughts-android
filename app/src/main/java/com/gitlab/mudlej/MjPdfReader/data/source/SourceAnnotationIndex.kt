// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** One rebuildable location index per workspace, independent of PDF placement and annotation history. */
internal class SourceAnnotationIndex private constructor(private val file: File) {
    private var index = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
        ?.takeIf { it.get("schema_version")?.asInt == 1 } ?: JsonObject()
    private var reviewFiles = JsonObject()
    @Volatile var revision: Long = 0; private set
    private var checkedThisProcess = false
    init { extractReviewEvidence() }

    private fun extractReviewEvidence() {
        reviewFiles = JsonObject()
        for ((path, entry) in index.getAsJsonObject("files")?.entrySet().orEmpty()) {
            entry.asJsonObject.remove("reviews")?.let { reviewFiles.add(path, it) }
        }
    }

    /** Saved locations may render immediately; mutations still require a complete check this process. */
    @Synchronized fun rememberedLocation(type: String, id: String, project: String, sourceFile: String? = null): JsonObject = locate(index, type, id, project, sourceFile)

    /** Reuse one scan for every annotation type only when the exact current bytes match. */
    @Synchronized fun cachedScan(path: String, sha256: String): JsonObject? {
        if (index.get("scanner_version")?.asInt != SCANNER_VERSION) return null
        val entry = index.getAsJsonObject("files")?.getAsJsonObject(path) ?: return null
        if (entry.get("sha256")?.asString != sha256) return null
        return entry.deepCopy().apply { reviewFiles.get(path)?.let { add("reviews", it.deepCopy()) } }
    }

    @Synchronized fun invalidate() { checkedThisProcess = false; index.add("projects", JsonObject()); revision++ }
    @Synchronized fun install(value: JsonObject): Boolean {
        val normalized = value.deepCopy()
        normalized.addProperty("scanner_version", SCANNER_VERSION)
        for ((_, entry) in normalized.getAsJsonObject("files").entrySet()) entry.asJsonObject.remove("reviews")
        val wasChecked = checkedThisProcess
        checkedThisProcess = true
        val evidence = JsonObject().apply {
            for ((path, entry) in value.getAsJsonObject("files").entrySet()) entry.asJsonObject.get("reviews")?.let { add(path, it.deepCopy()) }
        }
        if (index == normalized && reviewFiles == evidence) {
            if (!wasChecked) revision++
            return !wasChecked
        }
        index = normalized; reviewFiles = evidence; revision++
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            val persisted = index.deepCopy()
            for ((path, evidence) in reviewFiles.entrySet()) persisted.getAsJsonObject("files").getAsJsonObject(path).add("reviews", evidence)
            FileOutputStream(temporary).use { it.write(persisted.toString().toByteArray()); it.fd.sync() }
            check(temporary.renameTo(file))
        }
        return true
    }
    @Synchronized fun locate(type: String, id: String, project: String, sourceFile: String? = null): JsonObject =
        if (checkedThisProcess) locate(index, type, id, project, sourceFile) else JsonObject().apply { addProperty("state", "unavailable") }

    /** Candidate locations are exposed only after a complete, valid project check. */
    @Synchronized fun locations(type: String, id: String, project: String): List<JsonObject>? {
        if (!checkedThisProcess || project.isBlank() || index.getAsJsonObject("projects")?.get(project)?.asBoolean != true) return null
        val found = mutableListOf<JsonObject>()
        for ((path, value) in index.getAsJsonObject("files")?.entrySet().orEmpty()) {
            val file = value.asJsonObject
            if (file.get("project")?.asString != project) continue
            if (file.get("valid")?.asBoolean != true) return null
            for (entry in file.getAsJsonObject("locations")?.getAsJsonArray("$type\n$id") ?: JsonArray()) {
                found += entry.asJsonObject.deepCopy().apply { addProperty("source_file", path) }
            }
        }
        return found
    }

    @Synchronized fun reviewEvidence(id: String, project: String, sourceFile: String? = null): List<JsonObject> = reviewFiles.entrySet()
        .filter { SourceOwner.of(it.key) == project && (reviewFiles.getAsJsonObject(sourceFile)?.has(id) != true || it.key == sourceFile) }
        .flatMap { it.value.asJsonObject.getAsJsonArray(id)?.map { entry -> entry.asJsonObject.deepCopy() }.orEmpty() }

    companion object {
        /** 2: each file scan also lists its comment markers ("markers"), for "Comment missing". */
        const val SCANNER_VERSION = 2
        private val instances = mutableMapOf<String, SourceAnnotationIndex>()
        internal fun reloadForTesting(file: File) = SourceAnnotationIndex(file)
        @Synchronized fun at(file: File): SourceAnnotationIndex = instances.getOrPut(file.absolutePath) { SourceAnnotationIndex(file) }
        fun locate(index: JsonObject, type: String, id: String, project: String, sourceFile: String? = null): JsonObject {
            fun state(value: String) = JsonObject().apply { addProperty("state", value) }
            if (project.isBlank() || index.getAsJsonObject("projects")?.get(project)?.asBoolean != true) return state("unavailable")
            val found = mutableListOf<JsonObject>()
            var mentioned = false
            val hasPreferred = index.getAsJsonObject("files")?.getAsJsonObject(sourceFile)?.getAsJsonObject("locations")?.has("$type\n$id") == true
            for ((path, value) in index.getAsJsonObject("files")?.entrySet().orEmpty()) {
                if (hasPreferred && path != sourceFile) continue
                val file = value.asJsonObject
                if (file.get("project")?.asString != project) continue
                if (file.get("valid")?.asBoolean != true) return state("unavailable")
                mentioned = mentioned || file.getAsJsonArray("mentioned")?.any { it.asString == id } == true
                for (entry in file.getAsJsonObject("locations")?.getAsJsonArray("$type\n$id") ?: JsonArray()) {
                    found += entry.asJsonObject.deepCopy().apply {
                        addProperty("source_file", path); add("source_sha256", file.get("sha256"))
                    }
                }
            }
            if (found.isEmpty()) return state(if (mentioned) "unavailable" else "missing")
            // A valid file never repeats a card's segment, so one card's several passages in a
            // single file are its segments, not ambiguity. The first passage locates the card.
            val severalSegments = type == "review_card" && found.map { it.get("source_file")?.asString }.distinct().size == 1
            if (found.size != 1 && !severalSegments) return state("ambiguous")
            return found.minBy { it.get("start_line")?.asInt ?: Int.MAX_VALUE }.apply {
                addProperty("state", "linked")
                if (found.size > 1) addProperty("segment_count", found.size)
            }
        }
    }
}

internal object SourceAnnotationMarkup {
    private val MENTION = Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}|agent-[a-zA-Z0-9._:-]+")
    private val UUID_ID = Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
    /** Whether a scan's "mentioned" list proves this identity's raw text absent. */
    fun mentionsDetectable(id: String) = UUID_ID.matches(id)
    fun commentType(subtype: String) = when (subtype) {
        "StrikeOut" -> "strikethrough"
        "Highlight", "Underline", "Squiggly" -> "highlight"
        else -> "comment"
    }

    fun scan(source: String, markdown: Boolean, comments: List<CommentSourceMarkup.Marker> = CommentSourceMarkup.markers(source, markdown)): JsonObject {
        val locations = JsonObject()
        var valid = true
        fun line(at: Int) = source.take(at).count { it == '\n' } + 1
        fun add(type: String, id: String, start: Int, end: Int) {
            val key = "$type\n$id"
            val entries = locations.getAsJsonArray(key) ?: JsonArray().also { locations.add(key, it) }
            entries.add(JsonObject().apply { addProperty("start_line", line(start)); addProperty("end_line", line(end.coerceAtLeast(start))) })
        }
        val visible = source.toCharArray()
        fun mask(first: Int, end: Int) { for (i in first until end.coerceAtMost(source.length)) if (source[i] != '\n' && source[i] != '\r') visible[i] = ' ' }
        fun skip(from: Int): Int {
            var i = from
            while (i < source.length) {
                if (source[i].isWhitespace()) i++
                else if (source[i] == '%') i = source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length
                else break
            }
            return i
        }
        fun group(from: Int): Pair<Int, Int>? {
            var i = skip(from)
            if (source.getOrNull(i) != '{') return null
            val start = ++i; var depth = 1
            while (i < source.length) {
                when (source[i]) {
                    '\\' -> { i += 2; continue }
                    '%' -> { i = source.indexOf('\n', i).takeIf { it >= 0 } ?: return null; continue }
                    '{' -> depth++
                    '}' -> if (--depth == 0) return start to i + 1
                }
                i++
            }
            return null
        }
        if (markdown) {
            var fence: Char? = null; var count = 0; var offset = 0
            for (raw in source.split('\n')) {
                val text = raw.trim()
                var hidden = fence != null || raw.startsWith("    ") || raw.startsWith('\t') || '`' in raw
                if (text.startsWith("```") || text.startsWith("~~~")) {
                    hidden = true
                    val n = text.takeWhile { it == text.first() }.length
                    if (fence == null) { fence = text.first(); count = n }
                    else if (fence == text.first() && n >= count && text.drop(n).isEmpty()) fence = null
                }
                if (hidden) mask(offset, offset + raw.length)
                offset += raw.length + 1
            }
        } else {
            class Wrapper(val id: String?, val end: Int, val groups: List<AgentProposals.Group>)
            val parsed = AgentProposals.parse(AgentProposals.Format.TEX, source)
            val wrappers = parsed.proposals.associate { it.start to Wrapper(it.id, it.end, it.groups) } +
                parsed.issues.associate { it.start to Wrapper(it.id, it.end, it.groups) }
            var i = 0
            while (i < source.length) {
                if (source[i] == '%') {
                    val end = source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length
                    mask(i, end); i = end; continue
                }
                if (source[i] != '\\') { i++; continue }
                val start = i++
                if (source.getOrNull(i)?.isLetter() != true) { i++; continue }
                while (source.getOrNull(i)?.let { it.isLetter() || it == '@' } == true) i++
                when (source.substring(start + 1, i)) {
                    "def", "gdef", "edef", "xdef", "newcommand", "renewcommand", "providecommand", "DeclareRobustCommand" -> {
                        val definition = source.substring(start + 1, i) in setOf("def", "gdef", "edef", "xdef")
                        var cursor = skip(i)
                        if (source.getOrNull(cursor) == '*') cursor = skip(cursor + 1)
                        if (!definition && source.getOrNull(cursor) == '{') cursor = group(cursor)?.second ?: cursor
                        else if (source.getOrNull(cursor) == '\\') {
                            cursor++
                            while (source.getOrNull(cursor)?.let { it.isLetter() || it == '@' } == true) cursor++
                        }
                        cursor = skip(cursor)
                        if (definition) {
                            while (cursor < source.length && source[cursor] != '{') cursor++
                        } else while (source.getOrNull(cursor) == '[') {
                            val close = source.indexOf(']', cursor + 1)
                            if (close < 0) break
                            cursor = skip(close + 1)
                        }
                        group(cursor)?.let { mask(start, it.second); i = it.second }
                    }
                    "begin" -> {
                        val g = group(i) ?: continue
                        val name = source.substring(g.first, g.second - 1)
                        if (name in setOf("DocCode", "verbatim", "Verbatim", "lstlisting", "minted")) {
                            val closing = "\\end{$name}"
                            val end = source.indexOf(closing, g.second).takeIf { it >= 0 }?.plus(closing.length) ?: source.length
                            mask(start, end); i = end
                        }
                    }
                    "code", "url", "detokenize" -> group(i)?.let { mask(start, it.second); i = it.second }
                    "agentProposedEdit" -> {
                        // Wrapper boundaries come from the spec reader; a position it does not
                        // report is a mention, a definition or verbatim text, not a wrapper.
                        val wrapper = wrappers[start] ?: continue
                        if (wrapper.groups.size == 4) {
                            // Broken wrappers with all arguments (invalid metadata or ID, nesting,
                            // duplicate IDs) stay locatable; they are not reviewable.
                            wrapper.id?.let { add("proposal", it, start, wrapper.end - 1) }
                            i = wrapper.groups[3].contentStart
                            mask(start, wrapper.groups[2].end) // Keep the live replacement visible to review markers.
                        } else {
                            valid = false
                            mask(start, wrapper.end)
                            i = wrapper.end
                        }
                    }
                }
            }
        }
        // Every visible comment marker with its text and kind, as comment linking sees it ("Comment missing").
        val markers = JsonObject()
        fun listMarker(marker: CommentSourceMarkup.Marker) {
            val entries = markers.getAsJsonArray(marker.id) ?: JsonArray().also { markers.add(marker.id, it) }
            entries.add(JsonObject().apply {
                addProperty("line", marker.line); addProperty("contents", marker.contents)
                addProperty("kind", CommentSourceMarkup.kind(source, marker))
                marker.body?.let { addProperty("selected_text", CommentSourceMarkup.decode(it)) }
            })
        }
        for (marker in if (markdown) emptyList() else comments) {
            if (visible.getOrNull(marker.start) != '\\') continue
            val type = if (markdown || marker.body == null) "comment" else if (source.startsWith("\\CommentStrikethrough", marker.start)) "strikethrough" else "highlight"
            add(type, marker.id, marker.start, if (marker.body == null) marker.start else marker.end - 1)
            listMarker(marker)
        }
        if (markdown) comments.forEach(::listMarker)
        if (markdown) {
            val known = comments.map { it.id }.toSet()
            val ids = Regex("thoughts:v1:(?:start|end|point)\\s+comment:([A-Za-z0-9_-]+):").findAll(String(visible))
            if (ids.any { it.groupValues[1] !in known }) valid = false
        }
        if (markdown) for (comment in comments) {
            val token = Regex("<!--\\s*thoughts:v1:(start|end|point)\\s+comment:${Regex.escape(comment.id)}:[A-Za-z0-9_-]+\\s*-->")
            var start: Int? = null; var count = 0
            for (match in token.findAll(String(visible))) when (match.groupValues[1]) {
                "point" -> { add("comment", comment.id, match.range.first, match.range.first); count++ }
                "start" -> { if (start != null) valid = false; start = match.range.first }
                "end" -> { val first = start; if (first == null) valid = false else { add("comment", comment.id, first, match.range.last); start = null; count++ } }
            }
            if (start != null || count == 0) valid = false
        }
        val marker = if (markdown) Regex("<!--\\s*thoughts:v1:(start|end)\\s+review:([A-Za-z0-9_-]+):answer:([0-9]+)\\s*-->")
            else Regex("\\\\ReviewCardMask(Start|End)\\s*\\{([^}]+)\\}\\s*\\{([0-9]+)\\}")
        val starts = mutableMapOf<String, Int>(); val bodies = mutableMapOf<String, Int>(); val seen = mutableSetOf<String>(); val reviews = JsonObject()
        for (match in marker.findAll(String(visible))) {
            val id = match.groupValues[2]; val key = "$id:${match.groupValues[3]}"
            if (match.groupValues[1].equals("start", true)) {
                if (key in starts || key in seen) valid = false
                starts[key] = match.range.first
                var bodyStart = match.range.last + 1
                if (markdown) {
                    val first = source.lastIndexOf('\n', match.range.first - 1) + 1
                    val end = source.indexOf('\n', bodyStart)
                    if (end >= 0 && source.substring(first, match.range.first).isBlank() && source.substring(bodyStart, end).isBlank()) bodyStart = end + 1
                }
                bodies[key] = bodyStart
            } else {
                val start = starts.remove(key)
                if (start == null) valid = false else {
                    add("review_card", id, start, match.range.last); seen += key
                    val evidence = reviews.getAsJsonArray(id) ?: JsonArray().also { reviews.add(id, it) }
                    val body = source.substring(bodies.remove(key)!!, match.range.first).toByteArray()
                    evidence.add(JsonObject().apply {
                        addProperty("segment_id", match.groupValues[3])
                        addProperty("content_sha256", MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) })
                    })
                }
            }
        }
        if (starts.isNotEmpty()) valid = false
        if (!markdown && Regex("\\\\ReviewCardMask(?:Start|End)").findAll(String(visible)).count() != seen.size * 2) valid = false
        val mentioned = MENTION.findAll(source).map { it.value }.toSet()
        return JsonObject().apply {
            addProperty("sha256", MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) })
            addProperty("valid", valid); add("locations", locations); add("reviews", reviews); add("markers", markers)
            add("mentioned", JsonArray().apply { mentioned.forEach(::add) })
        }
    }
}
