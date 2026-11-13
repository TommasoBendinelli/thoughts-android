// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment

internal fun overviewCommentLinkingLabel(
    comment: OverviewComment,
    sourceLinkedStems: Set<String>,
    sourceIndexAvailable: Boolean,
): Int {
    if (comment.anchorStatus == ANCHOR_REMOVED_STATUS) return R.string.overview_anchor_removed
    if (comment.anchorStatus == PROPOSAL_REMOVED_STATUS) return R.string.overview_removed_proposal
    if (comment.anchorStatus == COMMENT_MISSING_STATUS) return R.string.overview_comment_missing_status
    val correspondence = comment.reviewCorrespondence
    if (comment.anchorStatus == "source_linked") return if (comment.logicalLocations.any { it.canDisplayExactPlacement }) R.string.overview_linking_linked
        else R.string.overview_linking_location_unavailable
    val state = correspondence?.state ?: comment.anchorStatus
    if (state in setOf("exact_text", "exact_point", "anchored", "source_linked", "source_projected")) {
        return R.string.overview_linking_linked
    }
    // A source target is evidence of markup even when PDF placement failed.
    if (correspondence?.sourceTargets?.isNotEmpty() == true || state == "source_only" ||
        correspondence?.reason == "source marker produced no exact PDF placement") {
        return if (correspondence?.kind == "agent_edit") R.string.overview_linking_proposal_location_unavailable
        else R.string.overview_linking_location_unavailable
    }
    if (comment.anchorStatus == "needs_attention") return R.string.overview_linking_failed
    if (state == "no_source") return R.string.overview_linking_without_source
    if (correspondence?.reason == "source anchor has not been established") {
        return R.string.overview_linking_no_annotation
    }
    if (sourceIndexAvailable && comment.documentStem.lowercase() !in sourceLinkedStems) {
        return R.string.overview_linking_without_source
    }
    // Report the source link established for this item, independently of repair progress.
    return if (comment.sourceFile.isNotBlank() || comment.documentStem.lowercase() in sourceLinkedStems)
        R.string.overview_linking_no_annotation
    else R.string.overview_linking_failed
}
