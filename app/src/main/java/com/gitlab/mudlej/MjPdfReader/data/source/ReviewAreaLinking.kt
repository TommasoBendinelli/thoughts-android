// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

/**
 * An answer area of a card on a source document that cannot be linked to source text. Nothing
 * was saved: a card on a document built from LaTeX or Markdown source is always source-linked,
 * one marker segment per area. [areaIndex] is 0-based, [page] is the area's first page in the
 * card's PDF (1-based, 0 when unknown).
 */
class ReviewAreaLinkingException(
    val areaIndex: Int,
    val areaCount: Int,
    val page: Int,
    val reason: String,
) : IllegalStateException(describe(areaIndex, areaCount, page, reason)) {
    /** The same failure with the area's page, from the 1-based first page of each area. */
    fun withPages(pages: List<Int>): ReviewAreaLinkingException =
        ReviewAreaLinkingException(areaIndex, areaCount, pages.getOrNull(areaIndex) ?: page, reason)

    /** The message with pages shifted by [pageOffset], as the reader shows them (a document within a Project reading). */
    fun describe(pageOffset: Int = 0): String = describe(areaIndex, areaCount, if (page > 0) page + pageOffset else 0, reason)

    companion object {
        fun describe(areaIndex: Int, areaCount: Int, page: Int, reason: String): String {
            val where = if (page > 0) " (page $page)" else ""
            val area = if (areaCount > 1) "Area ${areaIndex + 1} of $areaCount$where" else "The area$where"
            return "$area can't be linked to source text: ${reason.trim().ifBlank { "the source mapping is unavailable." }}"
        }
    }
}

/**
 * One answer area mapped into the current source: the 1-based lines its text came from (empty
 * when only its text can place it), the text itself, and how it was selected.
 */
internal data class MappedSourceArea(
    val lines: List<Int>,
    val text: String,
    /** Drawn as a box rather than selected as text: a box inside a float marks the whole float. */
    val rectangle: Boolean = false,
    /** Some of its characters have no usable source mapping; only a whole float can cover them. */
    val partial: Boolean = false,
    /** From a PDF built before the source last changed: its lines are unknown, its text must place it. */
    val stale: Boolean = false,
)

/** TeX float environments: a box inside one marks the whole environment, \begin through \end. */
internal val TEX_FLOAT_ENVIRONMENTS = setOf(
    "table", "table*", "figure", "figure*", "algorithm", "wrapfigure", "wraptable", "sidewaystable", "sidewaysfigure",
)

internal object TexFloats {
    private val environment = Regex("\\\\(begin|end)\\s*\\{([^}]+)\\}")

    /** TeX text without comments, line for line; escaped characters never count as syntax. */
    fun active(text: String): String = text.split('\n').joinToString("\n") { line ->
        var escaped = false
        val cut = line.indices.firstOrNull { i ->
            val comment = line[i] == '%' && !escaped
            escaped = line[i] == '\\' && !escaped
            comment
        }
        if (cut == null) line else line.substring(0, cut)
    }

    /** The outermost float environments of [source], as 1-based line ranges from \begin to \end. */
    fun ranges(source: String): List<IntRange> {
        val active = active(source)
        val stack = mutableListOf<Pair<String, Int>>()
        val floats = mutableListOf<IntRange>()
        var line = 1
        var counted = 0
        for (match in environment.findAll(active)) {
            for (i in counted until match.range.first) if (active[i] == '\n') line++
            counted = match.range.first
            val name = match.groupValues[2].trim()
            if (match.groupValues[1] == "begin") {
                stack += name to line
                continue
            }
            val open = stack.indexOfLast { it.first == name }
            if (open < 0) continue
            val begin = stack[open].second
            while (stack.size > open) stack.removeAt(stack.lastIndex)
            if (name in TEX_FLOAT_ENVIRONMENTS && stack.none { it.first in TEX_FLOAT_ENVIRONMENTS }) floats += begin..line
        }
        return floats
    }

    /**
     * [lines] widened to the floats its ends lie in, or null when neither end is inside a float.
     * A box over a table marks the whole table, from its \begin line through its \end line.
     */
    fun cover(source: String, lines: IntRange): IntRange? {
        val floats = ranges(source)
        val first = floats.firstOrNull { lines.first in it }
        val last = floats.firstOrNull { lines.last in it }
        if (first == null && last == null) return null
        return minOf(lines.first, first?.first ?: lines.first)..maxOf(lines.last, last?.last ?: lines.last)
    }

    /** The one float holding every line of [lines], if any. */
    fun enclosing(source: String, lines: IntRange): IntRange? =
        ranges(source).firstOrNull { lines.first in it && lines.last in it }
}
