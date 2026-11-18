// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import com.gitlab.mudlej.MjPdfReader.ui.reader.reviewTagLabel
import com.gitlab.mudlej.MjPdfReader.ui.reader.reviewTagLabels
import java.time.Instant
import java.util.Locale

enum class ReviewCardBrowserFilter {
    SEEN, NEVER,
    SKIPPED,
    ACTIVE,
    ALL,
    DUE,
    NEW,
    LEARNING,
    REVIEW,
    FUTURE,
    UNAVAILABLE,
    EXCLUDED,
    RETIRED,
}

enum class ReviewCardBrowserSort {
    DUE,
    QUESTION,
    DECK,
    PAGE,
    UPDATED,
    INTERVAL,
    REVIEWS,
    LAPSES,
    CREATED,
    LAST_REVIEWED,
    PROJECT,
    SOURCE,
    STATE,
    CARD_STATUS,
}

data class ReviewCardBrowserResult(
    val cards: List<ReviewBrowserCard>,
    val totalInScope: Int,
)

internal fun reviewCardBrowserResult(
    allCards: List<ReviewBrowserCard>,
    deckScope: String?,
    query: String,
    filter: ReviewCardBrowserFilter,
    sort: ReviewCardBrowserSort,
    descending: Boolean = sort.defaultDescending(),
    tags: Set<String> = emptySet(),
    stateText: (ReviewBrowserCard) -> String = { it.state.name },
): ReviewCardBrowserResult {
    val scoped = allCards.filter { item ->
        deckScope == null || item.card.pdfStem == deckScope
    }
    val normalizedQuery = query.trim()
    val displayed = scoped.asSequence()
        .filter { item -> filter.matches(item) }
        // A card shows when its latest review carries any selected tag.
        .filter { item -> tags.isEmpty() || item.currentTags().orEmpty().any(tags::contains) }
        .filter { item -> normalizedQuery.isEmpty() || item.matches(normalizedQuery) }
        .sortedWith(sort.comparator(descending, stateText))
        .toList()
    return ReviewCardBrowserResult(displayed, scoped.size)
}

// Filter names retired when the location states collapsed into UNAVAILABLE. Restored state saved
// by an older build still carries them, so they must not fall through to the default filter.
private val LEGACY_UNAVAILABLE_FILTER_NAMES = setOf(
    "REPAIRING",
    "NEEDS_ATTENTION",
    "STALE",
)

internal fun reviewCardBrowserFilterFromStoredName(name: String?): ReviewCardBrowserFilter? = when {
    name == null -> null
    name == "DELETED" -> ReviewCardBrowserFilter.ALL
    name in LEGACY_UNAVAILABLE_FILTER_NAMES || name == "WITH_ISSUES" -> ReviewCardBrowserFilter.UNAVAILABLE
    // Free-form tags replaced the fixed Correct/Skip/Wrong marks, and their filters with them.
    name in setOf("SKIPPED", "CURRENT_GOOD", "CURRENT_SKIP", "CURRENT_WRONG") -> ReviewCardBrowserFilter.ALL
    name == "RETAINED" -> ReviewCardBrowserFilter.NEVER
    name in setOf("ACTIVE", "LEARNING", "REVIEW", "FUTURE", "EXCLUDED") -> ReviewCardBrowserFilter.ALL
    else -> runCatching { ReviewCardBrowserFilter.valueOf(name) }.getOrNull()
}

internal fun ReviewBrowserState.labelRes(): Int = when (this) {
    ReviewBrowserState.SKIPPED -> R.string.review_skipped
    ReviewBrowserState.RETAINED -> R.string.review_retained
    ReviewBrowserState.NEW -> R.string.review_new
    ReviewBrowserState.LEARNING -> R.string.review_learning
    ReviewBrowserState.REVIEW -> R.string.review_review
    ReviewBrowserState.FUTURE -> R.string.review_card_browser_future
    ReviewBrowserState.REPAIRING -> R.string.review_card_browser_repairing
    ReviewBrowserState.NEEDS_ATTENTION -> R.string.review_card_browser_needs_attention
    ReviewBrowserState.STALE -> R.string.review_card_browser_stale
    ReviewBrowserState.EXCLUDED -> R.string.review_card_browser_excluded
    ReviewBrowserState.RETIRED -> R.string.review_card_browser_retired
    ReviewBrowserState.UNPUBLISHED -> R.string.review_card_browser_unpublished
    ReviewBrowserState.DELETED -> R.string.review_card_browser_deleted
    ReviewBrowserState.CANDIDATE -> R.string.review_card_browser_candidate
    ReviewBrowserState.REJECTED -> R.string.review_card_browser_rejected
}

internal val reviewCardBrowserVisibleFilters = listOf(
    ReviewCardBrowserFilter.ALL, ReviewCardBrowserFilter.NEW, ReviewCardBrowserFilter.DUE,
    ReviewCardBrowserFilter.SEEN, ReviewCardBrowserFilter.NEVER, ReviewCardBrowserFilter.RETIRED,
    ReviewCardBrowserFilter.UNAVAILABLE,
)

internal fun ReviewCardBrowserFilter.labelRes(): Int = when (this) {
    ReviewCardBrowserFilter.SEEN -> R.string.review_seen
    ReviewCardBrowserFilter.NEVER -> R.string.review_action_never
    ReviewCardBrowserFilter.SKIPPED -> R.string.review_skipped
    ReviewCardBrowserFilter.ACTIVE -> R.string.review_card_browser_active
    ReviewCardBrowserFilter.UNAVAILABLE -> R.string.review_card_browser_unavailable
    ReviewCardBrowserFilter.ALL -> R.string.review_card_browser_all
    ReviewCardBrowserFilter.DUE -> R.string.review_card_browser_due_now
    ReviewCardBrowserFilter.NEW -> R.string.review_new
    ReviewCardBrowserFilter.LEARNING -> R.string.review_learning
    ReviewCardBrowserFilter.REVIEW -> R.string.review_review
    ReviewCardBrowserFilter.FUTURE -> R.string.review_card_browser_future
    ReviewCardBrowserFilter.EXCLUDED -> R.string.review_card_browser_excluded
    // Retired cards and cards of an unpublished document: neither is reviewed.
    ReviewCardBrowserFilter.RETIRED -> R.string.review_card_browser_retired_or_unpublished
}

internal fun ReviewCardBrowserSort.labelRes(): Int = when (this) {
    ReviewCardBrowserSort.DUE -> R.string.review_card_browser_sort_due
    ReviewCardBrowserSort.QUESTION -> R.string.review_card_browser_sort_question
    ReviewCardBrowserSort.DECK -> R.string.review_card_browser_document
    ReviewCardBrowserSort.PAGE -> R.string.review_card_browser_sort_page
    ReviewCardBrowserSort.UPDATED -> R.string.review_card_browser_sort_updated
    ReviewCardBrowserSort.INTERVAL -> R.string.review_card_browser_sort_interval
    ReviewCardBrowserSort.REVIEWS -> R.string.review_card_browser_sort_reps
    ReviewCardBrowserSort.LAPSES -> R.string.review_card_browser_sort_lapses
    ReviewCardBrowserSort.CREATED -> R.string.review_card_browser_created
    ReviewCardBrowserSort.LAST_REVIEWED -> R.string.review_card_browser_last_reviewed
    ReviewCardBrowserSort.PROJECT -> R.string.review_card_browser_deck
    ReviewCardBrowserSort.SOURCE -> R.string.review_card_browser_source
    ReviewCardBrowserSort.STATE -> R.string.review_card_browser_state
    ReviewCardBrowserSort.CARD_STATUS -> R.string.review_card_browser_repetitions
}

internal fun ReviewBrowserCard.canReview(): Boolean = !hasIssues && (state in ACTIVE_STATES || state == ReviewBrowserState.RETAINED)

internal fun ReviewBrowserCard.detailSourceFiles(): List<String> =
    (card.sourceFiles + listOfNotNull(sourcePath))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

internal fun ReviewBrowserCard.normalizedRegionText(): String = String.format(
    Locale.ROOT,
    "left=%.6f, top=%.6f, right=%.6f, bottom=%.6f",
    card.rect.left,
    card.rect.top,
    card.rect.right,
    card.rect.bottom,
)

internal fun ReviewHistoryEntry.technicalText(): String = buildList {
    add("event_id=$eventId")
    rating?.let { add("rating=$it") }
    reviewAction?.let { add("review_action=$it") }
    reviewTags.takeIf { it.isNotEmpty() }?.let { add("review_tags=${it.joinToString(",")}") }
    groupNotes.takeIf { it.isNotEmpty() }?.let { notes ->
        add("review_group_notes=${notes.entries.joinToString(",") { (group, note) -> "$group:$note" }}")
    }
    phases?.let { steps ->
        add("review_phases=before:${steps.beforeTags.joinToString(",")};after:${steps.afterTags.joinToString(",")}" +
            steps.agentPrediction?.let { ";agent:${it.tag}" }.orEmpty())
    }
    queueAction?.let { add("queue_action=$it") }
    intervalAction?.let { add("interval_action=$it") }
    intervalAmount?.let { add("interval_amount=$it") }
    intervalUnit?.let { add("interval_unit=$it") }
    intervalDate?.let { add("interval_date=$it") }
    skipUntil?.let { add("skip_until=$it") }
    skipId?.let { add("skip_id=$it") }
    scheduledDueAt?.let { add("scheduled_due_at=$it") }
    reviewedAt?.let { add("reviewed_at=$it") }
    add("origin_id=${originId.ifBlank { "unavailable" }}")
    revision?.let { add("revision=$it") }
    updatedAt?.let { add("updated_at=$it") }
    retractedEventId?.let { add("retracted_event_id=$it") }
    undoneEventId?.let { add("undone_event_id=$it") }
    reviewKind?.let { add("review_kind=$it") }
    add("retracted=$retracted")
    add("effective=$effective")
    add("pending_sync=$pendingSync")
    sourcePath?.takeIf(String::isNotBlank)?.let { add("source=$it") }
}.joinToString(" · ")

private fun ReviewCardBrowserFilter.matches(item: ReviewBrowserCard): Boolean = with(item) { when (this@matches) {
    ReviewCardBrowserFilter.SEEN -> currentTags() != null
    // A retired card is not studied: it leaves the schedule filters for Retired.
    ReviewCardBrowserFilter.NEVER -> card.schedule?.retained == true && state !in OUT_OF_REVIEW_STATES
    ReviewCardBrowserFilter.SKIPPED -> state == ReviewBrowserState.SKIPPED
    ReviewCardBrowserFilter.ACTIVE -> state in ACTIVE_STATES
    ReviewCardBrowserFilter.UNAVAILABLE -> needsAction
    ReviewCardBrowserFilter.ALL -> true
    ReviewCardBrowserFilter.DUE -> state in DUE_STATES
    ReviewCardBrowserFilter.NEW -> currentTags() == null && state !in OUT_OF_REVIEW_STATES
    ReviewCardBrowserFilter.LEARNING -> state == ReviewBrowserState.LEARNING
    ReviewCardBrowserFilter.REVIEW -> state == ReviewBrowserState.REVIEW
    ReviewCardBrowserFilter.FUTURE -> state == ReviewBrowserState.FUTURE
    ReviewCardBrowserFilter.EXCLUDED -> state == ReviewBrowserState.EXCLUDED
    ReviewCardBrowserFilter.RETIRED -> state in OUT_OF_REVIEW_STATES
} }

/** Kept with their history but never reviewed: listed together under Retired / Unpublished. */
private val OUT_OF_REVIEW_STATES = setOf(ReviewBrowserState.RETIRED, ReviewBrowserState.UNPUBLISHED)

private fun ReviewBrowserCard.matches(query: String): Boolean =
    card.question.contains(query, ignoreCase = true) ||
        documentDisplayName.contains(query, ignoreCase = true) ||
        projectDisplayName.contains(query, ignoreCase = true) ||
        card.pdfStem.contains(query, ignoreCase = true) ||
        card.pagesLabel.contains(query) ||
        card.id.contains(query, ignoreCase = true) ||
        sourcePath?.contains(query, ignoreCase = true) == true ||
        state.name.replace('_', ' ').contains(query, ignoreCase = true)

internal fun ReviewCardBrowserSort.defaultDescending(): Boolean = this in setOf(
    ReviewCardBrowserSort.UPDATED, ReviewCardBrowserSort.INTERVAL,
    ReviewCardBrowserSort.REVIEWS, ReviewCardBrowserSort.LAPSES,
)

private fun ReviewCardBrowserSort.comparator(
    descending: Boolean,
    stateText: (ReviewBrowserCard) -> String,
): Comparator<ReviewBrowserCard> {
    fun date(item: ReviewBrowserCard): Instant? = when (this) {
        ReviewCardBrowserSort.CREATED -> item.createdAt
        ReviewCardBrowserSort.LAST_REVIEWED -> item.lastReviewedAt
        ReviewCardBrowserSort.UPDATED -> item.updatedAt ?: item.createdAt
        else -> if (item.state == ReviewBrowserState.NEW) Instant.MIN else item.dueAt
    }
    val byValue: Comparator<ReviewBrowserCard> = when (this) {
        ReviewCardBrowserSort.CREATED, ReviewCardBrowserSort.LAST_REVIEWED, ReviewCardBrowserSort.UPDATED, ReviewCardBrowserSort.DUE ->
            compareBy<ReviewBrowserCard> { date(it) }
        ReviewCardBrowserSort.QUESTION -> compareBy { it.card.question.lowercase(Locale.ROOT) }
        ReviewCardBrowserSort.PROJECT -> compareBy { it.projectDisplayName.lowercase(Locale.ROOT) }
        ReviewCardBrowserSort.DECK -> compareBy { it.documentDisplayName.lowercase(Locale.ROOT) }
        ReviewCardBrowserSort.PAGE -> compareBy { it.card.page }
        ReviewCardBrowserSort.SOURCE -> compareBy { item -> item.detailSourceFiles()
            .map { it.substringAfterLast('/') }.distinct().joinToString("\n").lowercase(Locale.ROOT) }
        ReviewCardBrowserSort.STATE -> compareBy { stateText(it).lowercase(Locale.ROOT) }
        ReviewCardBrowserSort.CARD_STATUS -> compareBy { it.cardStatusLabel().lowercase(Locale.ROOT) }
        ReviewCardBrowserSort.INTERVAL -> compareBy { it.intervalDays }
        ReviewCardBrowserSort.REVIEWS -> compareBy { it.reviewCount }
        ReviewCardBrowserSort.LAPSES -> compareBy { it.lapseCount }
    }
    val ordered = if (descending) byValue.reversed() else byValue
    return Comparator { left, right ->
        // Unavailable dates remain last in either direction; ties have a stable order.
        val missing = if (this in setOf(ReviewCardBrowserSort.CREATED, ReviewCardBrowserSort.LAST_REVIEWED,
                ReviewCardBrowserSort.UPDATED, ReviewCardBrowserSort.DUE))
            compareValues(date(left) == null, date(right) == null) else 0
        if (missing != 0) missing else ordered.compare(left, right).takeIf { it != 0 }
            ?: left.card.id.compareTo(right.card.id)
    }
}

private val ACTIVE_STATES = setOf(
    ReviewBrowserState.SKIPPED,
    ReviewBrowserState.NEW,
    ReviewBrowserState.LEARNING,
    ReviewBrowserState.REVIEW,
    ReviewBrowserState.FUTURE,
)

private val DUE_STATES = setOf(
    ReviewBrowserState.NEW,
    ReviewBrowserState.LEARNING,
    ReviewBrowserState.REVIEW,
)

private val UNAVAILABLE_STATES = setOf(
    ReviewBrowserState.REPAIRING,
    ReviewBrowserState.NEEDS_ATTENTION,
    ReviewBrowserState.STALE,
)

internal fun ReviewBrowserCard.labelRes(): Int = if (card.reanchorReason == "basic_content_changed" && state == ReviewBrowserState.NEEDS_ATTENTION)
    R.string.review_content_changed else state.labelRes()

internal fun ReviewBrowserCard.linkingLabelRes(sourceAvailable: Boolean?): Int {
    if (card.markerRemovalPending) return R.string.review_marker_removal_pending
    if (card.sourceLinkState == "linking_failed") return R.string.overview_linking_failed
    if (card.sourceLinkState == com.gitlab.mudlej.MjPdfReader.data.review.REVIEW_PENDING_SERVER_LINK) return R.string.review_link_pending_server
    if (card.isHtmlAttachmentCard) return R.string.html_attachment_card_linking
    if (card.wholeDocument) return R.string.review_whole_document
    val hasMarkup = card.sourceAnchor != null
    if (hasMarkup) {
        if (state in UNAVAILABLE_STATES ||
            card.reanchorState !in setOf("succeeded", "current", "ok", "exact_text", "exact_point"))
            return R.string.review_linking_location_failed
        return when (card.sourceLocationState) {
            "unavailable" -> R.string.review_linked_source_unavailable
            "ambiguous" -> R.string.review_linked_source_ambiguous
            else -> R.string.overview_linking_linked
        }
    }
    if (card.sourceKind == "pdf") return R.string.overview_linking_without_source
    if (card.sourceLocationState in setOf("unavailable", "ambiguous")) return R.string.annotation_source_unavailable
    return if (card.sourceKind == "pdf" ||
        (sourceAvailable == false && detailSourceFiles().isEmpty()))
        R.string.overview_linking_without_source
    else if (sourceAvailable == null) R.string.library_document_source_unknown
    else R.string.overview_linking_no_annotation
}

internal fun ReviewHistoryEntry.historyMarkRes(): Int = when {
    // Kind choices are queue actions too (protocol/review-card-kinds.md section 5).
    queueAction == com.gitlab.mudlej.MjPdfReader.data.review.ReviewKinds.SET_REVIEW_KIND -> reviewKindChoiceText(reviewKind)
    // Retirement events are queue actions, never answers (protocol/review-card-retire.md).
    queueAction == com.gitlab.mudlej.MjPdfReader.data.review.ReviewRetire.RETIRE -> R.string.review_history_retire
    queueAction == com.gitlab.mudlej.MjPdfReader.data.review.ReviewRetire.RESTORE -> R.string.review_history_restore
    queueAction == com.gitlab.mudlej.MjPdfReader.data.review.ReviewRetire.UNDO -> R.string.review_history_undo_retire
    intervalAction == "set" -> R.string.review_history_interval_set
    queueAction == "skip" || reviewAction == "skip" -> R.string.review_history_skip
    queueAction == "undo_skip" -> R.string.review_history_undo_skip
    retractedEventId != null -> R.string.review_history_undo_review
    rating == "delete" -> R.string.review_history_delete
    reviewTagsRecorded && reviewTags.isEmpty() -> R.string.review_tags_none
    reviewAction in setOf("wrong", "error") || rating in setOf("again", "ten_minutes") -> R.string.review_history_error
    reviewAction == "correct" || rating in setOf("good", "custom_interval", "retained") -> R.string.review_history_correct
    else -> R.string.review_history_review
}

/**
 * The selected cards the browser can retire (approved, active) and restore to review (retired),
 * in the order shown.
 */
internal fun reviewRetireSelection(shown: List<ReviewBrowserCard>, selected: Set<String>): Pair<List<String>, List<String>> {
    val picked = shown.filter { it.card.id in selected && it.card.approved && it.deleteEventId == null && it.state != ReviewBrowserState.DELETED }
    return picked.filter { it.state != ReviewBrowserState.RETIRED }.map { it.card.id } to
        picked.filter { it.state == ReviewBrowserState.RETIRED }.map { it.card.id }
}

/** The card menus' Retire card, or Restore to review on a retired card. */
internal fun reviewRetireMenuTitle(retired: Boolean): Int =
    if (retired) R.string.review_card_restore_menu else R.string.review_card_retire_menu

/** "2026-10-27": the local day a card was retired, as the browser shows dates. */
internal fun reviewRetiredDate(retiredAt: Instant, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(retiredAt.atZone(zone).toLocalDate())

/** "Retired 2026-10-27" for a retired card, null otherwise. */
internal fun ReviewBrowserCard.retiredLabel(context: android.content.Context): String? =
    card.retiredAt?.takeIf { state == ReviewBrowserState.RETIRED }
        ?.let { context.getString(R.string.review_retired_on, reviewRetiredDate(it)) }

internal fun ReviewBrowserCard.stateLabel(context: android.content.Context): String =
    (retiredLabel(context)?.let { "$it · " } ?: "") + currentTags().let { tags ->
        when {
            tags == null -> context.getString(R.string.review_new)
            tags.isEmpty() -> context.getString(R.string.review_current_tags, context.getString(R.string.review_tags_none))
            tags == listOf(ReviewTags.CORRECT) -> context.getString(R.string.review_current_good)
            tags == listOf(ReviewTags.SKIP) -> context.getString(R.string.review_current_skip)
            tags == listOf(ReviewTags.WRONG) -> context.getString(R.string.review_current_wrong)
            else -> context.getString(R.string.review_current_tags, reviewTagLabels(context, tags))
        }
    } + " · " + context.getString(R.string.review_card_browser_shown_count, reviewCount)

/** Tag names for answers, the fixed history label for everything else. */
internal fun ReviewHistoryEntry.historyMark(context: android.content.Context): String =
    reviewTags.takeIf { it.isNotEmpty() && retractedEventId == null }
        ?.let { reviewTagLabels(context, com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges.apply(it)) }
        ?: context.getString(historyMarkRes())

/**
 * The answer's group notes as "<Group>: <note>" lines, named after the current groups. For an answer
 * given in two steps the before note and the note are in its [phaseLines] instead.
 */
internal fun ReviewHistoryEntry.groupNoteLines(context: android.content.Context): List<String> =
    if (retractedEventId != null) emptyList()
    else reviewGroupNoteLines(context, if (phases == null) groupNotes else groupNotes - REVIEW_PHASE_NOTE_KEYS)

/** "Before: <tags> — <before note>", "After: <tags> — <note>", "Agent predicted: <tag>"; nothing for other answers. */
internal fun ReviewHistoryEntry.phaseLines(context: android.content.Context): List<String> =
    phases?.takeIf { retractedEventId == null }?.historyLines(
        groupNotes,
        label = { reviewTagLabel(context, com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges.resolve(it)) },
        none = context.getString(R.string.review_tags_none),
        before = context.getString(R.string.review_history_before),
        after = context.getString(R.string.review_history_after),
        agent = context.getString(R.string.review_history_agent),
    ).orEmpty()

private val REVIEW_PHASE_NOTE_KEYS = setOf(
    com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes.BEFORE_NOTE_KEY,
    com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes.ANSWER_NOTE_KEY,
)

internal fun reviewGroupNoteLines(context: android.content.Context, notes: Map<String, String>): List<String> =
    if (notes.isEmpty()) emptyList() else com.gitlab.mudlej.MjPdfReader.data.review.ReviewGroupNotes.lines(
        notes, com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.groups(),
        context.getString(R.string.review_tag_groups_other),
        context.getString(R.string.review_answer_note),
        context.getString(R.string.review_before_note_hint),
    )

internal fun ReviewBrowserCard.cardStatusLabel(): String {
    val location = card.sourceLocationState
    val reanchor = card.reanchorState
    val pdfOnly = card.sourceKind == "pdf" && card.sourceAnchor == null
    val placed = (reanchor in setOf("succeeded", "current", "ok", "exact_text", "exact_point") ||
        (pdfOnly && reanchor.isEmpty())) &&
        state !in UNAVAILABLE_STATES
    if (pdfOnly && placed) return "PDF only"
    if (card.isHtmlAttachmentCard) return "HTML attachment"
    if (card.wholeDocument) return if (placementPending()) "Placement pending"
        else if (state in UNAVAILABLE_STATES) "Placement failed" else "Whole document"
    if (location == "missing") return "Link removed"
    if (location in setOf("unavailable", "ambiguous")) return "Source unavailable"
    if (card.sourceLinkState in setOf("linking_failed", "failed")) return "Link failed"
    if (card.sourceLinkState in setOf("pending", "running", com.gitlab.mudlej.MjPdfReader.data.review.REVIEW_PENDING_SERVER_LINK) ||
        (card.sourceAnchor == null && !pdfOnly)) return "Link pending"
    if (placementPending())
        return "Placement pending"
    if (!placed) return "Placement failed"
    return "Linked"
}

internal fun ReviewBrowserCard.placementPending(): Boolean =
    card.reanchorState in setOf("queued", "retry", "running", "updating") ||
        card.reanchorReason == "pdf_placement_pending" ||
        (state == ReviewBrowserState.STALE && card.reanchorState != "needs_attention")

/**
 * How long the "retired" bar with its Undo stays (milliseconds): a card is retired with one tap and no
 * question, so the bar gives time to notice a stray tap and take it back.
 */
internal const val REVIEW_RETIRE_UNDO_MILLIS = 8_000
