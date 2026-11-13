// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewCommentDocument
import com.gitlab.mudlej.MjPdfReader.data.source.MissingCommentMarker
import com.gitlab.mudlej.MjPdfReader.data.source.MissingCommentResolver

/** "Comment missing": a marker in a managed source whose comment no longer exists (desktop docs/comment-missing.md). */
internal const val COMMENT_MISSING_STATUS = "comment_missing"

/**
 * Missing-comment markers as Overview rows. Like "Anchor removed" comments they are listed only under
 * Issues. Each row shows the marker's text and stands on the first document the source builds that
 * is in the Library; without one it is listed with no project.
 */
internal fun missingCommentRows(
    markers: List<MissingCommentMarker>,
    inLibrary: (String) -> Boolean = { false },
): List<OverviewComment> = markers.map { marker ->
    val stems = marker.targets.map { it.removeSuffix(".pdf") }
    OverviewComment(
        documentStem = stems.firstOrNull(inLibrary) ?: stems.firstOrNull().orEmpty(),
        id = marker.rowId,
        parentId = "",
        contents = marker.contents,
        selectedText = marker.selectedText.orEmpty(),
        author = "",
        sourceFile = marker.sourceFile,
        sourceLine = marker.line,
        anchorStatus = COMMENT_MISSING_STATUS,
        pageIndex = -1,
        subtype = when (marker.kind) { "highlight" -> "Highlight"; "strikethrough" -> "StrikeOut"; else -> "Text" },
        created = "",
        modified = "",
        activityMillis = 0,
        sourceContext = "${marker.sourceFile}:${marker.line}",
    )
}

/** Adds the missing-comment rows to their documents (by stem), keeping every other row as it is. */
internal fun withMissingCommentMarkers(
    documents: List<OverviewCommentDocument>,
    markers: List<MissingCommentMarker>,
    inLibrary: (String) -> Boolean = { false },
): List<OverviewCommentDocument> {
    if (markers.isEmpty()) return documents
    val rows = missingCommentRows(markers, inLibrary).groupBy { it.documentStem }
    val existing = documents.associateBy { it.stem }
    val merged = documents.map { document ->
        rows[document.stem]?.let { document.copy(comments = document.comments + it) } ?: document
    }
    return merged + rows.filterKeys { it !in existing }.map { (stem, comments) -> OverviewCommentDocument(stem, comments) }
}

/** "main.tex:12", as the row names its source. */
internal fun missingCommentLocation(marker: MissingCommentMarker): String {
    val file = marker.sourceFile.substringAfterLast('/')
    return if (marker.line > 0) "$file:${marker.line}" else file
}

/** The per-marker result of Remove marker or Restore comment: success, or the reason for a refusal. */
internal fun missingCommentOutcomeMessage(context: Context, outcome: MissingCommentResolver.Outcome): String {
    val file = outcome.marker.sourceFile.substringAfterLast('/')
    return when (outcome.code) {
        MissingCommentResolver.Code.REMOVED -> context.getString(R.string.overview_comment_missing_removed, file) +
            " " + context.getString(R.string.overview_comment_missing_publish)
        MissingCommentResolver.Code.RESTORED -> context.getString(R.string.overview_comment_missing_restored, file) +
            " " + context.getString(R.string.overview_comment_missing_publish)
        MissingCommentResolver.Code.RECREATED -> context.getString(R.string.overview_comment_missing_recreated, file) +
            " " + context.getString(R.string.overview_comment_missing_publish)
        MissingCommentResolver.Code.NO_ID, MissingCommentResolver.Code.INVALID_ID ->
            context.getString(R.string.overview_comment_missing_invalid_id)
        MissingCommentResolver.Code.NOT_MANAGED -> context.getString(R.string.overview_comment_missing_not_managed, file)
        MissingCommentResolver.Code.SOURCES_UNAVAILABLE -> context.getString(R.string.overview_comment_missing_sources_unavailable)
        MissingCommentResolver.Code.EXISTS_AGAIN -> context.getString(R.string.overview_comment_missing_exists_again, file)
        MissingCommentResolver.Code.ALREADY_EXISTS -> context.getString(R.string.overview_comment_missing_already_exists, file)
        MissingCommentResolver.Code.MARKER_GONE -> context.getString(R.string.overview_comment_missing_marker_gone, file)
        MissingCommentResolver.Code.MARKER_DUPLICATED -> context.getString(R.string.overview_comment_missing_duplicated, outcome.count, file)
        MissingCommentResolver.Code.NOT_VERIFIED -> context.getString(R.string.overview_comment_missing_not_verified, file)
        MissingCommentResolver.Code.DOCUMENT_DELETED -> context.getString(R.string.overview_comment_missing_document_deleted)
        MissingCommentResolver.Code.NO_REVISION -> context.getString(R.string.overview_comment_missing_no_revision)
        MissingCommentResolver.Code.NOT_IN_LIBRARY -> context.getString(R.string.overview_comment_missing_not_in_library, file)
        MissingCommentResolver.Code.SAVE_FAILED -> context.getString(R.string.overview_comment_missing_save_failed)
    }
}
