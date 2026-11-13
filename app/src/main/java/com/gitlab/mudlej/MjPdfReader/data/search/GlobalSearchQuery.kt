// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.search

import java.text.Normalizer

/**
 * One global search query: its words, folded (lower case, no accents) and reduced to a stem
 * that every word form shares ("generalized" and "generalize" both search "generaliz").
 * A text matches when every stem is the start of one of its words, in any order.
 */
class GlobalSearchQuery private constructor(
    val raw: String,
    /** The words as typed, folded. */
    val words: List<String>,
    /** One prefix per word, matched against the start of the text's words. */
    val stems: List<String>,
) {
    val isEmpty: Boolean get() = stems.isEmpty()

    /** The SQLite FTS3/4 MATCH expression: every stem as a quoted prefix term (AND). */
    val matchExpression: String get() = stems.joinToString(" ") { "\"$it*\"" }

    /** Whether every stem starts a word of [text]. */
    fun matches(text: String): Boolean {
        if (isEmpty || text.isEmpty()) return false
        val tokens = tokens(text)
        return stems.all { stem -> tokens.any { it.startsWith(stem) } }
    }

    /** How many distinct stems start a word of [text]. */
    fun matchedStemCount(text: String): Int {
        if (isEmpty || text.isEmpty()) return 0
        val tokens = tokens(text)
        return stems.count { stem -> tokens.any { it.startsWith(stem) } }
    }

    /** Ranges of [text] whose words start with a stem, for highlighting. */
    fun matchRanges(text: String): List<IntRange> = wordRanges(text).filter { range ->
        val token = fold(text.substring(range.first, range.last + 1))
        stems.any { token.startsWith(it) }
    }

    /**
     * A one-line excerpt of [text] around its densest cluster of matched words, at most
     * [maxChars] long, with the matched ranges inside the excerpt.
     */
    fun snippet(text: String, maxChars: Int = 160): Snippet {
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.isEmpty()) return Snippet("", emptyList())
        val ranges = matchRanges(flat)
        if (ranges.isEmpty() || flat.length <= maxChars) {
            val cut = flat.take(maxChars)
            return Snippet(if (cut.length < flat.length) "$cut…" else cut, ranges.filter { it.last < cut.length })
        }
        // The window with the most distinct stems, then the most matches, wins.
        var bestStart = ranges.first().first
        var bestScore = -1
        for (anchor in ranges) {
            val windowEnd = anchor.first + maxChars
            val inside = ranges.filter { it.first >= anchor.first && it.last < windowEnd }
            val distinct = inside.map { range ->
                val token = fold(flat.substring(range.first, range.last + 1))
                stems.indexOfFirst { token.startsWith(it) }
            }.distinct().size
            val score = distinct * 1000 + inside.size
            if (score > bestScore) {
                bestScore = score
                bestStart = anchor.first
            }
        }
        var start = (bestStart - maxChars / 4).coerceAtLeast(0)
        if (start > 0) flat.lastIndexOf(' ', start).takeIf { it >= 0 && bestStart - it < maxChars / 2 }?.let { start = it + 1 }
        val end = (start + maxChars).coerceAtMost(flat.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        val body = flat.substring(start, end)
        val shifted = ranges.filter { it.first >= start && it.last < end }
            .map { (it.first - start + prefix.length)..(it.last - start + prefix.length) }
        return Snippet(prefix + body + suffix, shifted)
    }

    /** The typed word to highlight in a PDF page: the longest one whose stem occurs in [text]. */
    fun highlightWord(text: String): String? {
        val tokens = tokens(text)
        return words.indices
            .filter { index -> tokens.any { it.startsWith(stems[index]) } }
            .maxByOrNull { stems[it].length }
            ?.let { index ->
                // The reader searches a contiguous string; the stem finds every form of the word.
                stems[index]
            }
    }

    data class Snippet(val text: String, val matches: List<IntRange>)

    companion object {
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
        private val WORD = Regex("[\\p{L}\\p{N}]+")
        private val WHITESPACE = Regex("\\s+")
        private val MARKS = Regex("\\p{Mn}+")
        private val SUFFIXES = listOf("ing", "ies", "ied", "ed", "es", "s", "e")
        private const val MIN_STEM = 4

        fun parse(query: String): GlobalSearchQuery {
            val words = fold(query).split(SEPARATORS).filter(String::isNotEmpty).distinct()
            // One-letter words match nearly everything; they count only when nothing else is typed.
            val useful = words.filter { it.length > 1 }.ifEmpty { words }
            return GlobalSearchQuery(query, useful, useful.map(::stem).distinct())
        }

        /** Lower case without accents (NFD, combining marks dropped), like unicode61 remove_diacritics. */
        fun fold(text: String): String {
            val lower = text.lowercase()
            if (lower.all { it.code < 128 }) return lower
            return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        }

        /** A light English stemmer: one common suffix off words of five letters or more. */
        fun stem(word: String): String {
            if (word.length < MIN_STEM + 1 || word.any(Char::isDigit)) return word
            for (suffix in SUFFIXES) {
                if (word.endsWith(suffix) && word.length - suffix.length >= MIN_STEM) {
                    return word.dropLast(suffix.length)
                }
            }
            return word
        }

        fun tokens(text: String): Set<String> =
            fold(text).split(SEPARATORS).filterTo(HashSet(), String::isNotEmpty)

        private fun wordRanges(text: String): List<IntRange> = WORD.findAll(text).map { it.range }.toList()
    }
}
