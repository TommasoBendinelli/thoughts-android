// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The plain data of the HTML reader: marker keys of cards and comments, the viewer's selection
 * payload and the records a selection turns into (thoughts-html-view-SPEC.md, "Placement in the
 * HTML view" and "New cards and comments from the HTML view").
 */
object HtmlReaderModel {
    /** `card:<card>:<segment>` for each segment of a v2 source anchor (the edition's `data-key`). */
    fun cardAnchorKeys(cardId: String, sourceAnchor: JsonObject?): List<String> = runCatching {
        if (sourceAnchor?.get("version")?.asInt != 2) return emptyList()
        sourceAnchor.getAsJsonArray("segments").orEmptyArray().mapNotNull { value ->
            value.takeIf { it.isJsonObject }?.asJsonObject?.text("segment_id")?.takeIf(String::isNotBlank)
                ?.let { "card:$cardId:$it" }
        }.distinct()
    }.getOrDefault(emptyList())

    /** `comment:<id>` of a source-linked comment (its marker id is `scope.anchor_id`, else the comment id). */
    fun commentAnchorKey(commentId: String, payload: JsonObject?): String {
        val anchor = payload?.getAsJsonObject("scope")?.text("anchor_id").orEmpty()
        return "comment:${anchor.ifBlank { commentId }}"
    }

    /** The manifest's per-card keys (`items.cards.<id>.anchors`), for cards without segment ids. */
    fun manifestCardKeys(manifestItems: JsonObject?, cardId: String): List<String> = runCatching {
        manifestItems?.getAsJsonObject("cards")?.getAsJsonObject(cardId)?.getAsJsonArray("anchors").orEmptyArray()
            .mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
    }.getOrDefault(emptyList())

    data class TextQuote(val exact: String, val prefix: String, val suffix: String) {
        fun toJson() = JsonObject().apply {
            addProperty("exact", exact); addProperty("prefix", prefix); addProperty("suffix", suffix)
        }

        companion object {
            const val MAX_CONTEXT = 64

            fun from(value: JsonObject?): TextQuote? {
                val exact = value?.text("exact")?.takeIf(String::isNotBlank) ?: return null
                return TextQuote(exact, value.text("prefix").takeLast(MAX_CONTEXT), value.text("suffix").take(MAX_CONTEXT))
            }
        }
    }

    data class SourceRange(val sourceFile: String, val startByte: Long, val endByte: Long, val exact: Boolean)

    /** The viewer's `selection` payload (README "Selection payload"). */
    data class Selection(
        val text: String,
        val quote: TextQuote?,
        val sourceRange: SourceRange?,
        val sourceLines: Triple<String, Int, Int>?,
        /** Inside the revealed answer of the card under review: its id and the answer-context quote. */
        val answerCardId: String?,
        val answerQuote: TextQuote?,
        /** Bounds in viewport CSS pixels: left, top, width, height. */
        val bounds: List<Float>,
        /** `page_state.hash` (attachment mode): the page's `location.hash` at selection time, null when not sent. */
        val pageHash: String? = null,
    ) {
        companion object {
            fun parse(payload: JsonObject?): Selection? {
                if (payload == null || payload.get("empty")?.asBoolean == true) return null
                val quote = TextQuote.from(payload.obj("text_quote"))
                val text = payload.text("text")
                if (text.isBlank() && quote == null) return null
                val range = payload.obj("source_range")?.let { r ->
                    val file = r.text("source_file")
                    val start = r.get("start_byte")?.takeIf { it.isJsonPrimitive }?.asLong
                    val end = r.get("end_byte")?.takeIf { it.isJsonPrimitive }?.asLong
                    if (file.isBlank() || start == null || end == null || end <= start || start < 0) null
                    else SourceRange(file, start, end, r.get("exact")?.asBoolean == true)
                }
                val lines = payload.obj("source_lines")?.let { l ->
                    val file = l.text("source_file")
                    val first = l.get("first_line")?.asInt ?: 0
                    val last = l.get("last_line")?.asInt ?: 0
                    if (file.isBlank() || first < 1) null else Triple(file, first, maxOf(first, last))
                }
                val answer = payload.obj("answer")
                return Selection(
                    text = text.ifBlank { quote?.exact.orEmpty() },
                    quote = quote,
                    sourceRange = range,
                    sourceLines = lines,
                    answerCardId = answer?.text("card_id")?.takeIf(String::isNotBlank),
                    answerQuote = TextQuote.from(answer?.obj("text_quote")),
                    bounds = payload.getAsJsonArray("bounds").orEmptyArray().mapNotNull {
                        it.takeIf(JsonElement::isJsonPrimitive)?.asFloat
                    },
                    pageHash = payload.obj("page_state")?.get("hash")?.takeIf { it.isJsonPrimitive }?.asString,
                )
            }
        }
    }

    /**
     * What a card or comment made from an HTML selection stores (`primary` of a card, `origin` of a
     * comment): `created_in`, the edition, the quote and, when the viewer mapped the selection to
     * source bytes (Markdown) and the source file's hash is known, `source_range` with `exact`
     * (false when the viewer rounded a boundary to a whole source fragment; Fedora then places by
     * the quote).
     */
    fun htmlSelectionFields(editionSha256: String, selection: Selection, sourceSha256: (String) -> String?): JsonObject {
        require(isSha256(editionSha256)) { "unknown HTML edition" }
        val quote = requireNotNull(selection.quote) { "the selection has no text" }
        return JsonObject().apply {
            addProperty("created_in", "html")
            addProperty("html_edition_sha256", editionSha256)
            add("text_quote", quote.toJson())
            val range = selection.sourceRange
            val hash = range?.let { sourceSha256(it.sourceFile) }?.lowercase()?.takeIf(::isSha256)
            if (range != null && hash != null) add("source_range", JsonObject().apply {
                addProperty("source_file", range.sourceFile)
                addProperty("source_sha256", hash)
                addProperty("start_byte", range.startByte)
                addProperty("end_byte", range.endByte)
                addProperty("exact", range.exact)
            })
        }
    }

    /** One card for the viewer's `setItems` (README "Items"). */
    fun viewerCard(id: String, anchors: List<String>, quote: TextQuote? = null, selected: Boolean = false, style: String? = null): JsonObject =
        JsonObject().apply {
            addProperty("id", id)
            add("anchors", JsonArray().apply { anchors.forEach(::add) })
            quote?.let { add("text_quote", it.toJson()) }
            if (selected) addProperty("selected", true)
            style?.let { addProperty("style", it) }
        }

    fun viewerComment(id: String, anchors: List<String>, selectedText: String?, quote: TextQuote?, strike: Boolean): JsonObject =
        JsonObject().apply {
            addProperty("id", id)
            add("anchors", JsonArray().apply { anchors.forEach(::add) })
            selectedText?.takeIf(String::isNotBlank)?.let { addProperty("selected_text", it) }
            quote?.let { add("text_quote", it.toJson()) }
            if (strike) addProperty("strike", true)
        }

    fun viewerFeedback(id: String, cardId: String, positive: Boolean, quote: TextQuote): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("card_id", cardId)
        addProperty("polarity", if (positive) "positive" else "negative")
        add("text_quote", quote.toJson())
    }

    /** The placement report of `setItems`: ids of cards placed, and the unplaced with their reason. */
    data class Placement(val placedCards: Set<String>, val unplacedCards: Map<String, String>, val unplacedComments: Map<String, String>) {
        companion object {
            val EMPTY = Placement(emptySet(), emptyMap(), emptyMap())

            fun parse(report: JsonElement?): Placement {
                val json = report?.takeIf { it.isJsonObject }?.asJsonObject ?: return EMPTY
                fun unplaced(kind: String) = json.obj(kind)?.getAsJsonArray("unplaced").orEmptyArray().mapNotNull { value ->
                    val item = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    item.text("id").takeIf(String::isNotBlank)?.let { it to item.text("reason") }
                }.toMap()
                val placed = json.obj("cards")?.getAsJsonArray("placed").orEmptyArray()
                    .mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }.toSet()
                return Placement(placed, unplaced("cards"), unplaced("comments"))
            }
        }
    }

    /** Where to put the reader when switching views: the nearest anchor, else the same fraction. */
    data class Position(val anchorKey: String?, val scrollFraction: Float?) {
        companion object {
            fun parse(message: JsonObject?): Position? {
                message ?: return null
                return Position(
                    message.text("anchor_key").takeIf(String::isNotBlank),
                    message.get("scroll_fraction")?.takeIf { it.isJsonPrimitive }?.asFloat?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
                )
            }
        }

        /** The card id of a `card:<card>:<segment>` anchor, or the comment id of `comment:<id>`. */
        fun item(): Pair<String, String>? {
            val key = anchorKey ?: return null
            return when {
                key.startsWith("card:") -> key.removePrefix("card:").substringBefore(':').takeIf(String::isNotBlank)?.let { "card" to it }
                key.startsWith("comment:") -> key.removePrefix("comment:").takeIf(String::isNotBlank)?.let { "comment" to it }
                else -> null
            }
        }
    }

    /** A `thoughts-link:<kind>?…` target of the edition (the PDF link's target data). */
    data class Link(val kind: String, val params: Map<String, String>) {
        val filename: String? get() = params["filename"]?.takeIf { it.matches(SAFE_PDF) }
            ?: params["key"]?.let { "$it.pdf" }?.takeIf { it.matches(SAFE_PDF) }
        val anchor: String? get() = params["anchor"]?.takeIf { it.isNotBlank() && it != "document-start" }
        val destination: String? get() = params["destination"]?.takeIf(String::isNotBlank)
        val label: String? get() = params["label"]?.takeIf(String::isNotBlank)
        val path: String? get() = params["path"]?.takeIf(String::isNotBlank)

        companion object {
            private val SAFE_PDF = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf")
            private val PATTERN = Regex("^thoughts-link:([A-Za-z0-9_-]+)(?:\\?(.*))?$")

            fun parse(value: String?): Link? {
                val match = PATTERN.matchEntire(value ?: return null) ?: return null
                val params = match.groupValues[2].split('&').filter(String::isNotEmpty).associate { pair ->
                    val name = pair.substringBefore('=')
                    val raw = if ('=' in pair) pair.substringAfter('=') else ""
                    decode(name) to decode(raw)
                }
                return Link(match.groupValues[1], params)
            }

            private fun decode(value: String): String =
                runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
        }
    }

    private fun JsonObject.text(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray()
}
