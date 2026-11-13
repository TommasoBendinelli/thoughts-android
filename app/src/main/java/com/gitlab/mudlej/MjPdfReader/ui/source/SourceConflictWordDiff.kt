// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

/**
 * Word-level comparison for the two versions of one TeX conflict.
 *
 * A Kotlin port of the proposal review's `wordDiff.js` (the agent-proposed-edit-review VS Code extension):
 * the same tokens (words, TeX control sequences, single punctuation characters; whitespace is
 * ignored) and the same Myers matching, so the phone highlights the words a reviewer would see
 * highlighted in a proposal. Results are half-open offsets into the compared strings.
 */
internal data class WordDiffToken(val start: Int, val end: Int, val text: String)

internal data class WordDiffRanges(
    val oldRanges: List<ConflictTextRange>,
    val newRanges: List<ConflictTextRange>,
)

private const val MAX_MYERS_DISTANCE = 1024

internal fun tokenizeWordDiff(text: String): List<WordDiffToken> {
    val tokens = mutableListOf<WordDiffToken>()
    var cursor = 0
    while (cursor < text.length) {
        val point = text.codePointAt(cursor)
        val width = Character.charCount(point)
        if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
            cursor += width
            continue
        }
        val start = cursor
        if (text[cursor] == '\\') {
            cursor += 1
            if (cursor < text.length && text[cursor].isTexLetter()) {
                while (cursor < text.length && text[cursor].isTexLetter()) cursor += 1
                if (cursor < text.length && text[cursor] == '*') cursor += 1
            } else if (cursor < text.length) {
                cursor += Character.charCount(text.codePointAt(cursor))
            }
            while (text.startsWith("{}", cursor)) cursor += 2
        } else if (isWordCodePoint(point)) {
            cursor += width
            while (cursor < text.length) {
                val next = text.codePointAt(cursor)
                val nextWidth = Character.charCount(next)
                if (isWordCodePoint(next)) {
                    cursor += nextWidth
                    continue
                }
                if ((next == '\''.code || next == '’'.code) && cursor + nextWidth < text.length) {
                    val after = text.codePointAt(cursor + nextWidth)
                    if (isWordCodePoint(after)) {
                        cursor += nextWidth + Character.charCount(after)
                        continue
                    }
                }
                break
            }
        } else {
            cursor += width
        }
        tokens += WordDiffToken(start, cursor, text.substring(start, cursor))
    }
    return tokens
}

/** The changed token runs of each side; unchanged words are not reported. */
internal fun changedWordRanges(oldText: String, newText: String): WordDiffRanges {
    val oldTokens = tokenizeWordDiff(oldText)
    val newTokens = tokenizeWordDiff(newText)
    val matches = matchingTokenPairs(oldTokens, newTokens)
    val matchedOld = HashSet<Int>(matches.size * 2)
    val matchedNew = HashSet<Int>(matches.size * 2)
    matches.forEach { (oldIndex, newIndex) ->
        matchedOld += oldIndex
        matchedNew += newIndex
    }
    return WordDiffRanges(
        oldRanges = changedRunRanges(oldTokens, matchedOld),
        newRanges = changedRunRanges(newTokens, matchedNew),
    )
}

internal fun matchingTokenPairs(
    oldTokens: List<WordDiffToken>,
    newTokens: List<WordDiffToken>,
): List<Pair<Int, Int>> {
    if (oldTokens.isEmpty() || newTokens.isEmpty()) return emptyList()
    val oldLength = oldTokens.size
    val newLength = newTokens.size
    val maximum = oldLength + newLength
    // Frontier for diagonal k is stored at k + offset; the JS Map treats a missing entry as -∞.
    val offset = minOf(maximum, MAX_MYERS_DISTANCE) + 1
    val frontier = IntArray(2 * offset + 1) { Int.MIN_VALUE }
    frontier[1 + offset] = 0
    // Round d only reads diagonals -d-1..d+1, so keep just that slice (index = diagonal + d + 1).
    val trace = ArrayList<IntArray>()
    for (distance in 0..maximum) {
        if (distance > MAX_MYERS_DISTANCE) return edgeTokenMatches(oldTokens, newTokens)
        trace += frontier.copyOfRange(offset - distance - 1, offset + distance + 2)
        var diagonal = -distance
        while (diagonal <= distance) {
            val left = frontier[diagonal - 1 + offset]
            val down = frontier[diagonal + 1 + offset]
            var oldIndex = if (diagonal == -distance || (diagonal != distance && left < down)) down else {
                if (left == Int.MIN_VALUE) Int.MIN_VALUE else left + 1
            }
            if (oldIndex == Int.MIN_VALUE) oldIndex = 0
            var newIndex = oldIndex - diagonal
            while (oldIndex < oldLength && newIndex < newLength &&
                oldTokens[oldIndex].text == newTokens[newIndex].text
            ) {
                oldIndex += 1
                newIndex += 1
            }
            frontier[diagonal + offset] = oldIndex
            if (oldIndex >= oldLength && newIndex >= newLength) {
                return backtrack(trace, distance, oldIndex, newIndex)
            }
            diagonal += 2
        }
    }
    return emptyList()
}

private fun backtrack(
    trace: List<IntArray>,
    finalDistance: Int,
    endOld: Int,
    endNew: Int,
): List<Pair<Int, Int>> {
    val matches = ArrayList<Pair<Int, Int>>()
    var oldIndex = endOld
    var newIndex = endNew
    for (step in finalDistance downTo 0) {
        val previous = trace[step]
        val offset = step + 1
        val currentDiagonal = oldIndex - newIndex
        val previousLeft = previous.getOrElse(currentDiagonal - 1 + offset) { Int.MIN_VALUE }
        val previousDown = previous.getOrElse(currentDiagonal + 1 + offset) { Int.MIN_VALUE }
        val previousDiagonal = if (currentDiagonal == -step ||
            (currentDiagonal != step && previousLeft < previousDown)
        ) {
            currentDiagonal + 1
        } else {
            currentDiagonal - 1
        }
        val stored = previous.getOrElse(previousDiagonal + offset) { Int.MIN_VALUE }
        val previousOld = if (stored == Int.MIN_VALUE) 0 else stored
        val previousNew = previousOld - previousDiagonal
        while (oldIndex > previousOld && newIndex > previousNew) {
            matches += (oldIndex - 1) to (newIndex - 1)
            oldIndex -= 1
            newIndex -= 1
        }
        oldIndex = previousOld
        newIndex = previousNew
    }
    matches.reverse()
    return matches
}

private fun edgeTokenMatches(
    oldTokens: List<WordDiffToken>,
    newTokens: List<WordDiffToken>,
): List<Pair<Int, Int>> {
    val matches = ArrayList<Pair<Int, Int>>()
    var prefix = 0
    while (prefix < oldTokens.size && prefix < newTokens.size &&
        oldTokens[prefix].text == newTokens[prefix].text
    ) {
        matches += prefix to prefix
        prefix += 1
    }
    var oldSuffix = oldTokens.size - 1
    var newSuffix = newTokens.size - 1
    val suffix = ArrayList<Pair<Int, Int>>()
    while (oldSuffix >= prefix && newSuffix >= prefix &&
        oldTokens[oldSuffix].text == newTokens[newSuffix].text
    ) {
        suffix += oldSuffix to newSuffix
        oldSuffix -= 1
        newSuffix -= 1
    }
    suffix.reverse()
    return matches + suffix
}

private fun changedRunRanges(
    tokens: List<WordDiffToken>,
    matched: Set<Int>,
): List<ConflictTextRange> {
    val ranges = mutableListOf<ConflictTextRange>()
    var runStart = -1
    var runEnd = -1
    tokens.forEachIndexed { index, token ->
        if (index in matched) {
            if (runStart >= 0) ranges += ConflictTextRange(runStart, runEnd)
            runStart = -1
        } else if (runStart >= 0) {
            runEnd = token.end
        } else {
            runStart = token.start
            runEnd = token.end
        }
    }
    if (runStart >= 0) ranges += ConflictTextRange(runStart, runEnd)
    return ranges
}

private fun Char.isTexLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this == '@'

private fun isWordCodePoint(point: Int): Boolean = point == '_'.code ||
    Character.isLetter(point) || Character.isDigit(point) ||
    Character.getType(point).let { type ->
        type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.LETTER_NUMBER.toInt() ||
            type == Character.OTHER_NUMBER.toInt()
    }
