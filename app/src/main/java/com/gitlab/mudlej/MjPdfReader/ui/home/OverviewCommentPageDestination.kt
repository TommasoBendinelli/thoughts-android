// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation

/** One user-visible page, retaining its individual placements until the PDF revision is checked. */
internal data class OverviewCommentPageDestination(
    val locations: List<LogicalCommentPdfLocation>,
) {
    val labelLocation: LogicalCommentPdfLocation
        get() = locations.firstOrNull { it.status != "unavailable" } ?: locations.first()

    fun navigationLocation(pdfSha256: String?): LogicalCommentPdfLocation? {
        if (pdfSha256.isNullOrBlank()) return null
        val matching = locations.filter { it.pdfSha256 == pdfSha256 && it.status != "unavailable" }
        val exact = matching.filter { it.isExact }
        val first = exact.firstOrNull() ?: matching.firstOrNull() ?: return null
        // This is a transient navigation target; original placements and their proofs stay intact.
        return first.copy(
            regions = exact.flatMap { it.regions }.distinct(),
            point = exact.firstOrNull { it.point.isNotEmpty() }?.point ?: first.point,
        )
    }
}

/**
 * Pages to offer for a comment or proposal. LaTeX-made copies (contents
 * entries, running heads) are offered only when no page shows the source's own
 * occurrence, so a heading edit opens at the heading rather than the contents.
 */
internal fun overviewCommentPageDestinations(
    locations: List<LogicalCommentPdfLocation>,
): List<OverviewCommentPageDestination> = locations
    .filter { it.page > 0 }
    .let { placed -> placed.filterNot { it.duplicate }.ifEmpty { placed } }
    .groupBy { it.pdfFilename to it.page }
    .values
    .map(::OverviewCommentPageDestination)
