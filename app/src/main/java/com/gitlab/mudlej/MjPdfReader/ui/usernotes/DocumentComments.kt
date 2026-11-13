// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewCommentDocument
import com.gitlab.mudlej.MjPdfReader.data.annotation.isAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.annotation.projectDocumentComments
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot

internal enum class DocumentCommentFilter {
    ALL,
    USER_COMMENTS,
    AGENT_PROPOSALS,
}

internal data class DocumentCommentItem(
    val comment: OverviewComment,
    val isAgentProposal: Boolean,
)

internal fun documentComments(
    pdfFilename: String,
    comments: List<OverviewComment>,
    sourceSnapshot: OfflineOverviewSourceSnapshot,
    pdfSha256: String? = null,
): List<DocumentCommentItem> {
    val stem = pdfFilename.removePdfSuffix()
    val proposals = sourceSnapshot.proposals
        .filter { proposal -> proposal.pdfFilenames.any { it.equals(pdfFilename, ignoreCase = true) } }
        .map { proposal -> proposal.copy(pdfFilenames = listOf(pdfFilename)) }
    val sourceComments = sourceSnapshot.reviewComments.filter { comment ->
        comment.pdfFilenames.any { it.equals(pdfFilename, ignoreCase = true) }
    }
    val hasSourceCommentProjection = sourceSnapshot.reviewCommentDocuments.any {
        it.equals(pdfFilename, ignoreCase = true)
    }
    val projection = projectDocumentComments(
        commentDocuments = listOf(OverviewCommentDocument(stem, comments)),
        proposals = proposals,
        sourceComments = sourceComments,
        sourceCommentDocuments = if (hasSourceCommentProjection) setOf(pdfFilename) else emptySet(),
        logicalComments = sourceSnapshot.logicalComments,
        logicalDocumentFilename = pdfFilename,
        logicalDocumentPdfSha256 = pdfSha256,
    )
    val proposalIds = proposals.mapTo(linkedSetOf()) { it.id }
    return projection.documents
        .firstOrNull { it.stem.equals(stem, ignoreCase = true) }
        ?.comments
        .orEmpty()
        .map { comment ->
            DocumentCommentItem(comment, isAgentProposal(comment, proposalIds))
        }
        .sortedWith(
            compareBy<DocumentCommentItem> { it.comment.pageIndex }
                .thenBy { it.comment.activityMillis }
                .thenBy { it.comment.id },
        )
}

internal fun filterDocumentComments(
    comments: List<DocumentCommentItem>,
    filter: DocumentCommentFilter,
    query: String,
): List<DocumentCommentItem> = comments.filter { item ->
    val typeMatches = when (filter) {
        DocumentCommentFilter.ALL -> true
        DocumentCommentFilter.USER_COMMENTS -> !item.isAgentProposal
        DocumentCommentFilter.AGENT_PROPOSALS -> item.isAgentProposal
    }
    typeMatches && (query.isBlank() || item.comment.searchText.contains(query, ignoreCase = true))
}

private fun String.removePdfSuffix(): String =
    if (endsWith(".pdf", ignoreCase = true)) dropLast(4) else this
