// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.source.SourceAnnotationMarkup
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundlePdf

internal data class ReviewPlacementRange(
    val sourceFile: String,
    val startLine: Int,
    val endLine: Int,
)

internal fun reviewPlacementSourceFiles(hints: List<String>, pdf: OfflineBundlePdf?, page: Int): List<String> =
    (hints + pdf?.segments.orEmpty().filter { page >= it.startPage && page < it.startPage + it.pageCount }.flatMap { segment ->
        listOfNotNull(segment.primarySourceFile) + segment.inputs.map { it.sourceFile }
    }).filter { path ->
        path.endsWith(".tex", ignoreCase = true) || path.endsWith(".md", ignoreCase = true) ||
            path.endsWith(".markdown", ignoreCase = true)
    }.distinct()

/** Returns null if any supplied source cannot be scanned safely. */
internal fun reviewPlacementRanges(cardId: String, sources: Map<String, String>): List<ReviewPlacementRange>? {
    val ranges = mutableListOf<ReviewPlacementRange>()
    for ((path, contents) in sources) {
        val markdown = path.endsWith(".md", ignoreCase = true) ||
            path.endsWith(".markdown", ignoreCase = true)
        val scanned = SourceAnnotationMarkup.scan(contents, markdown)
        if (scanned.get("valid")?.asBoolean != true) return null
        scanned.getAsJsonObject("locations")?.getAsJsonArray("review_card\n$cardId")
            ?.forEach { value ->
                val location = value.asJsonObject
                ranges += ReviewPlacementRange(
                    sourceFile = path,
                    startLine = location.get("start_line").asInt,
                    endLine = location.get("end_line").asInt,
                )
            }
    }
    return ranges.sortedWith(compareBy(ReviewPlacementRange::sourceFile, ReviewPlacementRange::startLine))
}

/** The first source file and 1-based line naming [cardId], for a marker pair that does not scan as complete. */
internal fun reviewCardMarkerLine(cardId: String, sources: Map<String, String>): Pair<String, Int>? =
    sources.entries.sortedBy { it.key }.firstNotNullOfOrNull { (path, contents) ->
        contents.lineSequence().indexOfFirst { cardId in it }.takeIf { it >= 0 }?.let { path to it + 1 }
    }

internal fun reviewPlacementLocationsText(ranges: List<ReviewPlacementRange>): String =
    ranges.groupBy(ReviewPlacementRange::sourceFile).entries.joinToString("; ") { (path, locations) ->
        "$path (lines " + locations.joinToString(" and ") { range ->
            "${range.startLine}–${range.endLine}"
        } + ")"
    }

/**
 * A linked card's several marked passages, one per area, all in one source file: the file and
 * their lines ("12–14, 30–31"). Null unless every range is one of the card's segments in that file.
 */
internal fun reviewLinkedSegmentsText(card: com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard, ranges: List<ReviewPlacementRange>?): Pair<String, String>? {
    if (ranges == null || ranges.size < 2 || card.sourceAnchor == null) return null
    val file = ranges.map { it.sourceFile }.distinct().singleOrNull() ?: return null
    val segments = card.sourceAnchor.getAsJsonArray("segments")?.size() ?: return null
    // A valid file never repeats a segment, so several ranges in one file are the card's segments.
    if (segments != ranges.size) return null
    return file to ranges.sortedBy { it.startLine }.joinToString(", ") { "${it.startLine}–${it.endLine}" }
}
