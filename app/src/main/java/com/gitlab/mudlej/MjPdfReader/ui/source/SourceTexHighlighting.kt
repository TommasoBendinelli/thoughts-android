// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import android.text.Editable
import android.text.NoCopySpan
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

internal data class SourceSyntaxRange(val start: Int, val endExclusive: Int)

/** Pending offsets follow edits until the debounced highlighting pass consumes them. */
internal class SourceTexHighlightChanges {
    private var pending: SourceSyntaxRange? = null

    fun changed(start: Int, before: Int, count: Int) {
        require(start >= 0 && before >= 0 && count >= 0)
        val previous = pending
        val delta = count - before
        val shiftedStart = previous?.start?.let {
            when {
                it <= start -> it
                it >= start + before -> it + delta
                else -> start
            }
        } ?: start
        val shiftedEnd = previous?.endExclusive?.let {
            when {
                it < start -> it
                it >= start + before -> it + delta
                else -> start + count
            }
        } ?: start + count
        pending = SourceSyntaxRange(minOf(start, shiftedStart), maxOf(start + count, shiftedEnd))
    }

    fun takeRange(text: CharSequence): SourceSyntaxRange? {
        val dirty = pending ?: return null
        pending = null
        var start = dirty.start.coerceIn(0, text.length)
        var end = dirty.endExclusive.coerceIn(start, text.length)
        // This lexer has no state across newlines. Include complete affected lines so
        // editing a command or joining/splitting lines produces the same colors as a full scan.
        while (start > 0 && text[start - 1] != '\n') start--
        while (end < text.length && text[end] != '\n') end++
        if (end < text.length) end++
        return SourceSyntaxRange(start, end)
    }
}

private val SOURCE_TEX_SYNTAX = Pattern.compile("\\\\(?:[A-Za-z@]+\\*?|[^\\r\\n])|[{}%]")

internal fun sourceTexSyntaxRanges(text: CharSequence, range: SourceSyntaxRange): List<SourceSyntaxRange> {
    require(range.start >= 0 && range.endExclusive in range.start..text.length)
    // Copy only the affected characters, never a Spanned subsequence. Reuse one Matcher:
    // Kotlin Regex.findAll(Editable) resets the matcher and copies the full text for each match on ART.
    val snapshot = String(CharArray(range.endExclusive - range.start) { text[range.start + it] })
    val matcher = SOURCE_TEX_SYNTAX.matcher(snapshot)
    val ranges = ArrayList<SourceSyntaxRange>()
    while (matcher.find()) {
        ranges.add(SourceSyntaxRange(range.start + matcher.start(), range.start + matcher.end()))
    }
    return ranges
}

internal fun applySourceTexHighlights(text: Editable, range: SourceSyntaxRange, color: Int) {
    val replacements = sourceTexSyntaxRanges(text, range)
    for (span in text.getSpans(range.start, range.endExclusive, SourceTexColorSpan::class.java)) {
        // getSpans also returns spans touching a boundary; leave the neighboring line intact.
        if (text.getSpanStart(span) < range.endExclusive && text.getSpanEnd(span) > range.start) {
            text.removeSpan(span)
        }
    }
    for (replacement in replacements) {
        text.setSpan(SourceTexColorSpan(color), replacement.start, replacement.endExclusive,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

// These are regenerated from the source, not draft data. Copying them into saved
// text would deserialize them as generic color spans and leave stale duplicates.
private class SourceTexColorSpan(color: Int) : ForegroundColorSpan(color), NoCopySpan
