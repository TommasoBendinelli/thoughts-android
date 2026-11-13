// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

/**
 * A half-open range into the exact source text supplied to
 * [parseSourceConflictPresentation].
 */
internal data class ConflictTextRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0)
        require(endExclusive >= start)
    }
}

internal enum class ConflictTextRole {
    MARKER,
    FEDORA_CURRENT,
    SHARED_BASE,
    PHONE_INCOMING,
    MALFORMED_MARKER,
}

internal enum class ConflictMarkerKind {
    START,
    BASE,
    SEPARATOR,
    END,
}

/** A styled range; it contains offsets only and never owns or rewrites source text. */
internal data class ConflictHighlightRange(
    val range: ConflictTextRange,
    val role: ConflictTextRole,
    val markerKind: ConflictMarkerKind? = null,
)

internal data class SourceConflictBlock(
    val range: ConflictTextRange,
    val complete: Boolean,
    val highlights: List<ConflictHighlightRange>,
) {
    val startOffset: Int
        get() = range.start

    fun rangesFor(role: ConflictTextRole): List<ConflictTextRange> = highlights
        .asSequence()
        .filter { it.role == role }
        .map { it.range }
        .toList()

    fun selectedSide(source: CharSequence, role: ConflictTextRole): String? {
        if (!complete || role !in SELECTABLE_SIDE_ROLES) return null
        val ranges = rangesFor(role)
        return buildString {
            ranges.forEach { range ->
                append(source.subSequence(range.start, range.endExclusive))
            }
        }
    }

    private companion object {
        val SELECTABLE_SIDE_ROLES = setOf(
            ConflictTextRole.FEDORA_CURRENT,
            ConflictTextRole.PHONE_INCOMING,
        )
    }
}

internal enum class ConflictNavigationDirection {
    NEXT,
    PREVIOUS,
}

internal data class SourceConflictPresentation(
    val blocks: List<SourceConflictBlock>,
    val orphanMarkers: List<ConflictHighlightRange>,
) {
    /**
     * Every block start plus every orphan structural marker, in source order.
     * These are the offsets to which conflict navigation should move the editor.
     */
    val navigationOffsets: List<Int> = (
        blocks.map(SourceConflictBlock::startOffset) +
            orphanMarkers.map { it.range.start }
        ).distinct().sorted()

    val highlights: List<ConflictHighlightRange> = (
        blocks.flatMap(SourceConflictBlock::highlights) + orphanMarkers
        ).sortedWith(compareBy({ it.range.start }, { it.range.endExclusive }))

    val hasMalformedMarkers: Boolean = blocks.any { block ->
        !block.complete || block.highlights.any { it.role == ConflictTextRole.MALFORMED_MARKER }
    } || orphanMarkers.isNotEmpty()

    fun conflictOffset(
        fromOffset: Int,
        direction: ConflictNavigationDirection,
        wrap: Boolean = true,
    ): Int? {
        if (navigationOffsets.isEmpty()) return null
        return when (direction) {
            ConflictNavigationDirection.NEXT -> {
                navigationOffsets.firstOrNull { it > fromOffset }
                    ?: navigationOffsets.firstOrNull().takeIf { wrap }
            }

            ConflictNavigationDirection.PREVIOUS -> {
                navigationOffsets.lastOrNull { it < fromOffset }
                    ?: navigationOffsets.lastOrNull().takeIf { wrap }
            }
        }
    }
}

/**
 * Parses Git two-way and diff3 conflict markers into presentation-only ranges.
 *
 * Only markers beginning in column zero are structural. The returned model refers to the
 * original [source] using half-open offsets, so applying colors or moving the cursor cannot
 * change even one source character. Malformed and incomplete blocks remain visible instead
 * of being silently discarded.
 */
internal fun parseSourceConflictPresentation(source: CharSequence): SourceConflictPresentation {
    val blocks = mutableListOf<SourceConflictBlock>()
    val orphanMarkers = mutableListOf<ConflictHighlightRange>()
    var active: MutableConflictBlock? = null

    source.forEachStructuralConflictMarker { marker ->
        val current = active
        if (current != null) {
            current.closeSideRange(marker.lineStart)
        }

        when (marker.kind) {
            ConflictMarkerKind.START -> {
                if (current != null) {
                    blocks += current.finish(marker.lineStart, complete = false)
                }
                active = MutableConflictBlock(marker)
            }

            ConflictMarkerKind.BASE -> {
                if (current == null) {
                    orphanMarkers += marker.asMalformedHighlight()
                } else {
                    current.acceptBase(marker)
                }
            }

            ConflictMarkerKind.SEPARATOR -> {
                if (current == null) {
                    orphanMarkers += marker.asMalformedHighlight()
                } else {
                    current.acceptSeparator(marker)
                }
            }

            ConflictMarkerKind.END -> {
                if (current == null) {
                    orphanMarkers += marker.asMalformedHighlight()
                } else {
                    val structurallyComplete = current.acceptEnd(marker)
                    blocks += current.finish(marker.lineEndExclusive, structurallyComplete)
                    active = null
                }
            }
        }
    }

    active?.let { current ->
        current.closeSideRange(source.length)
        blocks += current.finish(source.length, complete = false)
    }

    return SourceConflictPresentation(
        blocks = blocks,
        orphanMarkers = orphanMarkers,
    )
}

private enum class ConflictParseSide(val role: ConflictTextRole) {
    FEDORA(ConflictTextRole.FEDORA_CURRENT),
    BASE(ConflictTextRole.SHARED_BASE),
    PHONE(ConflictTextRole.PHONE_INCOMING),
}

private class MutableConflictBlock(start: StructuralConflictMarker) {
    private val startOffset = start.lineStart
    private val highlights = mutableListOf(start.asHighlight())
    private var currentSide = ConflictParseSide.FEDORA
    private var sideStart = start.lineEndExclusive
    private var sawBase = false
    private var sawSeparator = false
    private var malformed = false

    fun closeSideRange(endExclusive: Int) {
        if (endExclusive > sideStart) {
            highlights += ConflictHighlightRange(
                range = ConflictTextRange(sideStart, endExclusive),
                role = currentSide.role,
            )
        }
    }

    fun acceptBase(marker: StructuralConflictMarker) {
        val valid = currentSide == ConflictParseSide.FEDORA && !sawBase && !sawSeparator
        highlights += if (valid) marker.asHighlight() else marker.asMalformedHighlight()
        if (valid) {
            sawBase = true
            currentSide = ConflictParseSide.BASE
        } else {
            malformed = true
        }
        sideStart = marker.lineEndExclusive
    }

    fun acceptSeparator(marker: StructuralConflictMarker) {
        val valid = currentSide != ConflictParseSide.PHONE && !sawSeparator
        highlights += if (valid) marker.asHighlight() else marker.asMalformedHighlight()
        if (valid) {
            sawSeparator = true
            currentSide = ConflictParseSide.PHONE
        } else {
            malformed = true
        }
        sideStart = marker.lineEndExclusive
    }

    fun acceptEnd(marker: StructuralConflictMarker): Boolean {
        val valid = currentSide == ConflictParseSide.PHONE && sawSeparator
        highlights += if (valid) marker.asHighlight() else marker.asMalformedHighlight()
        if (!valid) malformed = true
        sideStart = marker.lineEndExclusive
        return valid && !malformed
    }

    fun finish(endExclusive: Int, complete: Boolean): SourceConflictBlock = SourceConflictBlock(
        range = ConflictTextRange(startOffset, endExclusive.coerceAtLeast(startOffset)),
        complete = complete && !malformed,
        highlights = highlights.toList(),
    )
}

private data class StructuralConflictMarker(
    val kind: ConflictMarkerKind,
    val lineStart: Int,
    val markerEndExclusive: Int,
    val lineEndExclusive: Int,
) {
    fun asHighlight() = ConflictHighlightRange(
        range = ConflictTextRange(lineStart, markerEndExclusive),
        role = ConflictTextRole.MARKER,
        markerKind = kind,
    )

    fun asMalformedHighlight() = ConflictHighlightRange(
        range = ConflictTextRange(lineStart, markerEndExclusive),
        role = ConflictTextRole.MALFORMED_MARKER,
        markerKind = kind,
    )
}

private inline fun CharSequence.forEachStructuralConflictMarker(
    action: (StructuralConflictMarker) -> Unit,
) {
    var lineStart = 0
    while (lineStart < length) {
        var newline = lineStart
        while (newline < length && this[newline] != '\n') newline += 1
        val markerEnd = if (newline > lineStart && this[newline - 1] == '\r') {
            newline - 1
        } else {
            newline
        }
        markerKindAt(lineStart, markerEnd)?.let { kind ->
            action(
                StructuralConflictMarker(
                    kind = kind,
                    lineStart = lineStart,
                    markerEndExclusive = markerEnd,
                    lineEndExclusive = if (newline < length) newline + 1 else newline,
                ),
            )
        }
        lineStart = if (newline < length) newline + 1 else length
    }
}

private fun CharSequence.markerKindAt(start: Int, endExclusive: Int): ConflictMarkerKind? = when {
    startsWithAt(start, endExclusive, "<<<<<<<") -> ConflictMarkerKind.START
    startsWithAt(start, endExclusive, "|||||||") -> ConflictMarkerKind.BASE
    regionEquals(start, endExclusive, "=======") -> ConflictMarkerKind.SEPARATOR
    startsWithAt(start, endExclusive, ">>>>>>>") -> ConflictMarkerKind.END
    else -> null
}

private fun CharSequence.startsWithAt(start: Int, endExclusive: Int, prefix: String): Boolean {
    if (endExclusive - start < prefix.length) return false
    return prefix.indices.all { index -> this[start + index] == prefix[index] }
}

private fun CharSequence.regionEquals(start: Int, endExclusive: Int, expected: String): Boolean =
    endExclusive - start == expected.length &&
        expected.indices.all { index -> this[start + index] == expected[index] }
