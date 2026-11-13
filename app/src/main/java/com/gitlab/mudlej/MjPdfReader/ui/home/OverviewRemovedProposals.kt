// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.parseCommentTimestamp
import com.gitlab.mudlej.MjPdfReader.data.annotation.AGENT_PROPOSAL_SUBTYPE
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewCommentDocument
import com.gitlab.mudlej.MjPdfReader.data.source.RemovedOutsideProposal

/** "Removed outside Thoughts": a proposal whose markup vanished without a decision (spec section 10). */
internal const val PROPOSAL_REMOVED_STATUS = "proposal_removed_outside"

/**
 * Removed proposals as Overview rows. Like "Anchor removed" comments they are listed only under
 * Issues; each row names the source location it was removed from.
 */
internal fun removedProposalComments(removed: List<RemovedOutsideProposal>): List<OverviewComment> = removed.map { proposal ->
    val stem = proposal.pdfFilenames.firstOrNull()?.removeSuffix(".pdf").orEmpty()
    val change = "${proposal.before.trim()} → ${proposal.after.trim().ifBlank { "[delete]" }}"
    OverviewComment(
        documentStem = stem,
        id = proposal.id,
        parentId = "",
        contents = proposal.comment.trim(),
        selectedText = change,
        author = "Agent",
        sourceFile = proposal.sourceFile,
        sourceLine = proposal.line,
        anchorStatus = PROPOSAL_REMOVED_STATUS,
        pageIndex = -1,
        subtype = AGENT_PROPOSAL_SUBTYPE,
        created = proposal.createdAt,
        modified = proposal.removedAt,
        activityMillis = parseCommentTimestamp(proposal.removedAt)?.toEpochMilli()
            ?: parseCommentTimestamp(proposal.createdAt)?.toEpochMilli() ?: 0,
        sourceContext = change,
    )
}

/** Adds removed-proposal rows to their documents (by stem), keeping every other row as it is. */
internal fun withRemovedProposals(
    documents: List<OverviewCommentDocument>,
    removed: List<RemovedOutsideProposal>,
): List<OverviewCommentDocument> {
    if (removed.isEmpty()) return documents
    val rows = removedProposalComments(removed).groupBy { it.documentStem }
    val existing = documents.associateBy { it.stem }
    val merged = documents.map { document ->
        rows[document.stem]?.let { document.copy(comments = document.comments + it) } ?: document
    }
    return merged + rows.filterKeys { it !in existing }.map { (stem, comments) -> OverviewCommentDocument(stem, comments) }
}

/** The user-facing reason of a refused Restore Proposal (spec section 10.6). */
internal fun removedProposalRefusalMessage(refusal: String): Int = when (refusal) {
    "text_changed" -> R.string.overview_restore_proposal_text_changed
    "ambiguous" -> R.string.overview_restore_proposal_ambiguous
    "id_in_source" -> R.string.overview_restore_proposal_id_in_source
    "source_missing" -> R.string.overview_restore_proposal_source_missing
    "sources_unavailable" -> R.string.overview_restore_anchor_sources_unavailable
    else -> R.string.overview_restore_proposal_not_restorable
}

/** The removal's hint (section 10.3 `looks_like`), never a decision. */
internal fun removedProposalLooksLike(looksLike: String): Int? = when (looksLike) {
    "accept" -> R.string.overview_removed_proposal_looks_accepted
    "reject" -> R.string.overview_removed_proposal_looks_rejected
    "edited" -> R.string.overview_removed_proposal_looks_edited
    "file_deleted" -> R.string.overview_removed_proposal_file_deleted
    else -> null
}
