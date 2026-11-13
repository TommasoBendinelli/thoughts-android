// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import java.text.Normalizer

/**
 * Finding a feedback passage again in a card's current answer text (protocol/review-card-feedback.md
 * §4). The same algorithm runs on the desktop and in the server's reference implementation
 * (review_feedback_anchor.py); the shared vectors in review-card-feedback-anchoring.json keep them in
 * step. Offsets are UTF-16 code units, end exclusive.
 */
object ReviewFeedbackAnchoring {
    enum class State(val wire: String) { EXACT("exact"), RELOCATED("relocated"), ORPHANED("orphaned") }

    data class Resolution(val state: State, val start: Int? = null, val end: Int? = null)

    /** Comparable code points of a text and, for each, the UTF-16 range of the code point it came from. */
    internal class Skeleton(val codePoints: IntArray, val starts: IntArray, val ends: IntArray) {
        val size: Int get() = codePoints.size
    }

    private val HYPHENS = setOf('-'.code, 0x2010, 0x2011)

    private fun dropped(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.NON_SPACING_MARK.toInt(),
        -> true
        else -> false
    }

    internal fun skeleton(text: String, dropHyphens: Boolean = false): Skeleton {
        val codePoints = ArrayList<Int>(text.length)
        val starts = ArrayList<Int>(text.length)
        val ends = ArrayList<Int>(text.length)
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val width = Character.charCount(codePoint)
            if (Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() && ends.isNotEmpty()) {
                // A combining mark belongs to the character before it (its glyph is drawn with it).
                ends[ends.lastIndex] = offset + width
                offset += width
                continue
            }
            val decomposed = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFKD)
            var index = 0
            while (index < decomposed.length) {
                val part = decomposed.codePointAt(index)
                index += Character.charCount(part)
                if (dropped(part) || (dropHyphens && part in HYPHENS)) continue
                codePoints += part
                starts += offset
                ends += offset + width
            }
            offset += width
        }
        return Skeleton(codePoints.toIntArray(), starts.toIntArray(), ends.toIntArray())
    }

    /**
     * Where the passage [exact] (with its [prefix] and [suffix] context) is in [answerText].
     * [samePdf]: the anchor's PDF is the one displayed, so its stored regions are drawn as they are.
     */
    fun resolve(answerText: String, exact: String, prefix: String = "", suffix: String = "", samePdf: Boolean = false): Resolution {
        if (samePdf) return Resolution(State.EXACT)
        for (dropHyphens in listOf(false, true)) {
            val needle = skeleton(exact, dropHyphens).codePoints
            if (needle.isEmpty()) return Resolution(State.ORPHANED)
            val haystack = skeleton(answerText, dropHyphens)
            val hits = occurrences(haystack.codePoints, needle)
            if (hits.isEmpty()) continue
            val before = skeleton(prefix, dropHyphens).codePoints
            val after = skeleton(suffix, dropHyphens).codePoints
            var best = hits.first()
            var bestScore = -1
            for (hit in hits) {
                val score = commonSuffix(before, haystack.codePoints, hit) +
                    commonPrefix(after, haystack.codePoints, hit + needle.size)
                if (score > bestScore) { // strictly greater: the first hit wins a tie
                    best = hit
                    bestScore = score
                }
            }
            return Resolution(State.RELOCATED, haystack.starts[best], haystack.ends[best + needle.size - 1])
        }
        return Resolution(State.ORPHANED)
    }

    private fun occurrences(haystack: IntArray, needle: IntArray): List<Int> {
        val hits = ArrayList<Int>()
        if (needle.size > haystack.size) return hits
        outer@ for (start in 0..haystack.size - needle.size) {
            for (index in needle.indices) if (haystack[start + index] != needle[index]) continue@outer
            hits += start
        }
        return hits
    }

    /** Common suffix of [context] and haystack[0, end). */
    private fun commonSuffix(context: IntArray, haystack: IntArray, end: Int): Int {
        var count = 0
        while (count < context.size && count < end && context[context.size - 1 - count] == haystack[end - 1 - count]) count++
        return count
    }

    /** Common prefix of [context] and haystack[start, size). */
    private fun commonPrefix(context: IntArray, haystack: IntArray, start: Int): Int {
        var count = 0
        while (count < context.size && start + count < haystack.size && context[count] == haystack[start + count]) count++
        return count
    }
}
