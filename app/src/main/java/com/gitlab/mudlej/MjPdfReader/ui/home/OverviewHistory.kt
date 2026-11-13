// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewCommentDocument
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class OverviewHistory<T> {
    private val undo = mutableListOf<T>()
    private val redo = mutableListOf<T>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val isEmpty: Boolean get() = undo.isEmpty() && redo.isEmpty()

    fun record(value: T) {
        undo += value
        redo.clear()
    }

    fun peekUndo(): T? = undo.lastOrNull()

    fun peekRedo(): T? = redo.lastOrNull()

    fun completeUndo() {
        undo.removeLastOrNull()?.let(redo::add)
    }

    fun completeRedo() {
        redo.removeLastOrNull()?.let(undo::add)
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    fun removeIf(predicate: (T) -> Boolean): Boolean {
        val removedUndo = undo.removeAll(predicate)
        val removedRedo = redo.removeAll(predicate)
        return removedUndo || removedRedo
    }
}

internal fun overviewCommentKey(
    comment: OverviewComment,
): String = if (comment.logicalPayloadJson.isNotBlank()) {
    "logical\u0000${comment.id}"
} else {
    "${comment.documentStem}\u0000${comment.id}"
}

internal fun availableOverviewDocuments(
    documents: List<OverviewCommentDocument>,
    pdfAvailabilityByStem: Map<String, Boolean>,
): List<OverviewCommentDocument> = documents.mapNotNull { document ->
    val comments = document.comments.filter { comment ->
        if (comment.isNote || comment.anchorStatus == PROPOSAL_REMOVED_STATUS || comment.anchorStatus == COMMENT_MISSING_STATUS) {
            // A removed proposal or a marker without its comment is about its source, not a PDF.
            true
        } else if (comment.logicalLocations.isEmpty()) {
            pdfAvailabilityByStem[document.stem.lowercase()] == true
        } else {
            true
        }
    }
    document.copy(comments = comments).takeIf { comments.isNotEmpty() }
}

internal fun overviewCommentComparator(sortOrder: OverviewSortOrder): Comparator<OverviewComment> {
    val newest = compareByDescending<OverviewComment> { it.activityMillis }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.documentStem }
        .thenBy { it.pageIndex }
        .thenBy { it.id }
    return when (sortOrder) {
        OverviewSortOrder.NEWEST -> newest
        OverviewSortOrder.OLDEST -> compareBy<OverviewComment> { it.activityMillis }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.documentStem }
            .thenBy { it.pageIndex }
            .thenBy { it.id }
        OverviewSortOrder.DOCUMENT -> compareBy<OverviewComment, String>(
            String.CASE_INSENSITIVE_ORDER,
        ) { it.documentStem }
            .thenBy { it.pageIndex }
            .then(newest)
        OverviewSortOrder.PAGE -> compareBy<OverviewComment> { it.pageIndex }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.documentStem }
            .then(newest)
    }
}

internal val overviewCommentComparator = overviewCommentComparator(OverviewSortOrder.NEWEST)

internal enum class OverviewPdfClass { TEX_LINKED, OTHER, UNKNOWN }

internal fun overviewPdfClass(
    documentStem: String,
    sourceLinkedStems: Set<String>,
    sourceIndexAvailable: Boolean,
): OverviewPdfClass = when {
    !sourceIndexAvailable -> OverviewPdfClass.UNKNOWN
    sourceLinkedStems.any { it.equals(documentStem, ignoreCase = true) } ->
        OverviewPdfClass.TEX_LINKED
    else -> OverviewPdfClass.OTHER
}

internal const val ANCHOR_REMOVED_STATUS = "anchor_removed"
/** The Issues filter: comments whose source link needs the user's attention. */
internal val OVERVIEW_ISSUE_STATUSES = setOf("needs_anchor", "needs_attention", "orphaned", ANCHOR_REMOVED_STATUS, PROPOSAL_REMOVED_STATUS,
    COMMENT_MISSING_STATUS)
/** Listed only under Issues, never in the main flow. */
internal val OVERVIEW_ISSUE_ONLY_STATUSES = setOf(ANCHOR_REMOVED_STATUS, PROPOSAL_REMOVED_STATUS, COMMENT_MISSING_STATUS)

internal const val OVERVIEW_SOURCE_ONLY = "__overview_source_only__"
internal const val OVERVIEW_NO_PROJECT = "__overview_no_project__"

internal fun filterOverviewComments(
    comments: List<OverviewComment>,
    documentStem: String,
    anchorFilter: OverviewAnchorFilter,
    pdfFilter: OverviewPdfFilter,
    sourceLinkedStems: Set<String>,
    sourceIndexAvailable: Boolean,
    priorityFilter: OverviewPriorityFilter = OverviewPriorityFilter.ALL,
    locationFilter: OverviewLocationFilter? = null,
): List<OverviewComment> = comments.filter { comment ->
    val documentMatches = when (documentStem) {
        "" -> true
        OVERVIEW_SOURCE_ONLY -> !comment.isNote && comment.pageIndex < 0 && comment.logicalLocations.isEmpty()
        else -> comment.documentStem == documentStem
    }
    // "Anchor removed" comments and removed proposals are listed only under Issues, never in the main flow.
    val anchorMatches = when (anchorFilter) {
        OverviewAnchorFilter.ALL -> comment.anchorStatus !in OVERVIEW_ISSUE_ONLY_STATUSES
        OverviewAnchorFilter.ANCHORED -> comment.anchorStatus in setOf("anchored", "source_linked")
        OverviewAnchorFilter.NEEDS_ANCHOR -> comment.anchorStatus in OVERVIEW_ISSUE_STATUSES
        OverviewAnchorFilter.PDF_ONLY ->
            comment.anchorStatus !in setOf("anchored", "source_linked", "needs_anchor", "needs_attention") + OVERVIEW_ISSUE_ONLY_STATUSES
    }
    val pdfMatches = when {
        !sourceIndexAvailable || pdfFilter == OverviewPdfFilter.ALL -> true
        pdfFilter == OverviewPdfFilter.TEX_LINKED ->
            overviewPdfClass(comment.documentStem, sourceLinkedStems, true) ==
                OverviewPdfClass.TEX_LINKED
        else -> overviewPdfClass(comment.documentStem, sourceLinkedStems, true) ==
            OverviewPdfClass.OTHER
    }
    val priorityMatches = when (priorityFilter) {
        OverviewPriorityFilter.ALL -> true
        OverviewPriorityFilter.NORMAL -> comment.priority == CommentPriority.NORMAL
        OverviewPriorityFilter.HIGH -> comment.priority == CommentPriority.HIGH
    }
    val locationMatches = locationFilter == null || comment.location?.let { location ->
        distanceKm(
            locationFilter.latitude,
            locationFilter.longitude,
            location.latitude,
            location.longitude,
        ) <= locationFilter.radiusKm
    } == true
    documentMatches && anchorMatches && pdfMatches && priorityMatches && locationMatches
}

/**
 * The places that [comments] carry, each once, by name: what the location filter offers to
 * measure the distance from. Places can no longer be chosen on a map, only kept on comments.
 */
internal fun overviewCommentPlaces(comments: List<OverviewComment>): List<CommentLocation> = comments
    .mapNotNull(OverviewComment::location)
    .distinctBy { Triple(it.label, it.latitude, it.longitude) }
    .sortedBy { it.label.lowercase() }

internal fun distanceKm(
    firstLatitude: Double,
    firstLongitude: Double,
    secondLatitude: Double,
    secondLongitude: Double,
): Double {
    val latitudeDelta = Math.toRadians(secondLatitude - firstLatitude)
    val longitudeDelta = Math.toRadians(secondLongitude - firstLongitude)
    val firstLatitudeRadians = Math.toRadians(firstLatitude)
    val secondLatitudeRadians = Math.toRadians(secondLatitude)
    val haversine = sin(latitudeDelta / 2).let { it * it } +
        cos(firstLatitudeRadians) * cos(secondLatitudeRadians) *
        sin(longitudeDelta / 2).let { it * it }
    return 2 * EARTH_RADIUS_KM * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_KM = 6371.0088

internal data class OverviewCommentGeometry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun overviewTopCommentGeometry(
    pageWidth: Float,
    pageHeight: Float,
): OverviewCommentGeometry? {
    if (pageWidth <= 0f || pageHeight <= 0f) return null
    val markerWidth = minOf(28f, pageWidth)
    val markerHeight = minOf(28f, pageHeight)
    val left = minOf(24f, (pageWidth - markerWidth).coerceAtLeast(0f))
    val topInset = minOf(24f, (pageHeight - markerHeight).coerceAtLeast(0f))
    val top = pageHeight - topInset
    return OverviewCommentGeometry(left, top, left + markerWidth, top - markerHeight)
}

internal fun overviewTopCommentRect(pageWidth: Float, pageHeight: Float): RectF? =
    overviewTopCommentGeometry(pageWidth, pageHeight)?.let {
        RectF(it.left, it.top, it.right, it.bottom)
    }

internal fun expandOverviewDeletion(
    allComments: List<OverviewComment>,
    selectedKeys: Set<String>,
): List<OverviewComment> {
    val expanded = selectedKeys.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        allComments.forEach { candidate ->
            val parentKey = if (candidate.logicalPayloadJson.isNotBlank()) {
                "logical\u0000${candidate.parentId}"
            } else {
                "${candidate.documentStem}\u0000${candidate.parentId}"
            }
            if (candidate.parentId.isNotBlank() && parentKey in expanded &&
                expanded.add(overviewCommentKey(candidate))
            ) {
                changed = true
            }
        }
    } while (changed)
    return allComments.filter { overviewCommentKey(it) in expanded }
}
