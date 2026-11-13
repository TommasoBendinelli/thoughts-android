// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

/**
 * Turns text selected in a PDF into what Read aloud speaks: one paragraph without the PDF's line
 * breaks, and sentence-boundary chunks small enough for the speech endpoint.
 */
object ReadAloudText {
    /** The first chunk stays short, so the first audio arrives quickly. */
    const val FIRST_CHUNK_MAX_CHARS = 400

    /** Sentences shorter than this ("1. Introduction.") join the next one in the first chunk. */
    private const val FIRST_CHUNK_MIN_CHARS = 60

    /** Later chunks are fetched while earlier ones play, so they can be longer. */
    const val CHUNK_MAX_CHARS = 1200

    private val ligatures = mapOf(
        '\uFB00' to "ff",
        '\uFB01' to "fi",
        '\uFB02' to "fl",
        '\uFB03' to "ffi",
        '\uFB04' to "ffl",
    )

    // Hyphen, soft hyphen, PDFium's line-end hyphen marker (U+0002) or a Unicode hyphen, then a line break.
    private val lineEndHyphen = Regex("([\\p{L}\\p{N}])[-\u00AD\u0002\u2010]\\s*(?:\r\n|\r|\n)\\s*(\\S)")
    private val lineBreak = Regex("\\s*(?:\r\n|\r|\n)\\s*")
    private val whitespace = Regex("[\\s\u00A0\u2000-\u200B\u202F\u205F\u3000]+")

    /**
     * Joins PDF lines with spaces, rejoins words hyphenated at a line end when the next line starts
     * in lowercase ("exam-\nple" → "example"), and collapses whitespace.
     */
    fun prepare(raw: String): String {
        if (raw.isEmpty()) return ""
        val text = buildString(raw.length) {
            raw.forEach { c ->
                val ligature = ligatures[c]
                when {
                    ligature != null -> append(ligature)
                    c != '\u0000' -> append(c)
                }
            }
        }
        val dehyphenated = lineEndHyphen.replace(text) { match ->
            val before = match.groupValues[1]
            val next = match.groupValues[2]
            if (next[0].isLowerCase()) {
                before + next
            } else {
                // "Anti-\nInflammatory", "1990-\n2000": a real hyphen that happened to end the line.
                "$before-$next"
            }
        }
        return dehyphenated
            .replace(lineBreak, " ")
            .replace("\u00AD", "")
            .replace("\u0002", "")
            .replace(whitespace, " ")
            .trim()
    }

    /**
     * Splits prepared [text] at sentence boundaries: the first chunk at most [firstMax] characters
     * (normally one sentence), later ones at most [restMax]. A sentence longer than its limit is
     * split at a clause boundary or a space, and a word longer than the limit is cut.
     */
    fun chunks(
        text: String,
        firstMax: Int = FIRST_CHUNK_MAX_CHARS,
        restMax: Int = CHUNK_MAX_CHARS,
    ): List<String> {
        require(firstMax > 0 && restMax > 0)
        val sentences = sentences(text)
        if (sentences.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val current = StringBuilder()
        fun limit() = if (result.isEmpty()) firstMax else restMax
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
            }
        }
        for (sentence in sentences) {
            // The first chunk is about one sentence, so playback starts as soon as possible.
            if (result.isEmpty() && current.length >= FIRST_CHUNK_MIN_CHARS) flush()
            if (current.isNotEmpty() && current.length + 1 + sentence.length > limit()) flush()
            if (current.isEmpty() && sentence.length > limit()) {
                var rest = sentence
                while (rest.length > limit()) {
                    val cut = splitPoint(rest, limit())
                    result += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) current.append(rest)
                continue
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return result.filter { it.isNotBlank() }
    }

    /** Sentences of [text], each keeping its closing punctuation. */
    internal fun sentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var end = i + 1
                // Closing quotes and brackets belong to the sentence they close.
                while (end < text.length && text[end] in "\"'”’)]") end++
                val atBoundary = end >= text.length ||
                    (text[end] == ' ' && end + 1 < text.length && startsSentence(text[end + 1]))
                if (atBoundary && !isAbbreviation(text, start, i)) {
                    text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
                    start = end
                    i = end
                    continue
                }
            }
            i++
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(sentences::add)
        return sentences
    }

    private fun startsSentence(c: Char): Boolean =
        c.isUpperCase() || c.isDigit() || c in "\"'“‘([" || !c.isLetter()

    private val abbreviations = setOf(
        "e.g", "i.e", "cf", "vs", "etc", "al", "fig", "figs", "eq", "eqs", "sec", "ch",
        "dr", "mr", "mrs", "ms", "prof", "st", "no", "vol", "pp", "approx", "resp", "ref", "refs",
    )

    /** "e.g.", "Fig." or an initial ("J. Smith") rather than the end of a sentence. */
    private fun isAbbreviation(text: String, sentenceStart: Int, dot: Int): Boolean {
        if (text[dot] != '.') return false
        var wordStart = dot
        while (wordStart > sentenceStart && !text[wordStart - 1].isWhitespace() && text[wordStart - 1] != '(') wordStart--
        val word = text.substring(wordStart, dot).trimStart('"', '\'', '“', '‘', '[').lowercase()
        if (word.isEmpty()) return false
        // An initial ("J. Smith") or a listed abbreviation ("e.g.", "Fig.", "et al.").
        return (word.length == 1 && word[0].isLetter()) || word in abbreviations
    }

    /** Where to cut [text] at most [max] characters in: after a clause mark, else at a space. */
    private fun splitPoint(text: String, max: Int): Int {
        val window = text.substring(0, max)
        val clause = maxOf(
            window.lastIndexOf("; "),
            window.lastIndexOf(", "),
            window.lastIndexOf(": "),
            window.lastIndexOf(" – "),
            window.lastIndexOf(" — "),
        )
        if (clause >= max / 2) return clause + 1
        val space = window.lastIndexOf(' ')
        return if (space > 0) space + 1 else max
    }
}
