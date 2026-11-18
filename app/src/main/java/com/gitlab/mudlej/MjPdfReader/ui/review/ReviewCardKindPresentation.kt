// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKindSource
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKinds
import java.time.Instant

/*
 * How knowledge cards and project cards show (protocol/review-card-kinds.md section 5): the card
 * menus' choices, the "Project card" mark with "Changed since you last saw it", and History.
 */

/** The kind "Make project card" / "Make knowledge card" chooses for [card]: the other one. */
internal fun reviewSwappedKind(card: ReviewCard): String =
    if (card.isProjectCard) ReviewKinds.KNOWLEDGE else ReviewKinds.PROJECT

/** "Make knowledge card" on a project card, "Make project card" on a knowledge card. */
internal fun reviewKindMenuTitle(card: ReviewCard): Int =
    if (card.isProjectCard) R.string.review_kind_make_knowledge else R.string.review_kind_make_project

/** Whether the menu offers "Follow the Project": the card has a kind of its own and a Project holds it. */
internal fun reviewCanFollowProject(card: ReviewCard): Boolean =
    card.reviewKindSource == ReviewKindSource.CARD && card.followedKind != null

/** "Follow the Project (Knowledge)" or "(Project)", naming the kind the card's Projects give it. */
internal fun reviewFollowProjectTitle(context: Context, card: ReviewCard): String = context.getString(
    R.string.review_kind_follow_project,
    context.getString(if (card.followedKind == ReviewKinds.PROJECT) R.string.review_kind_project else R.string.review_kind_knowledge),
)

/** What a kind event says, in History and in the bar after the choice. */
internal fun reviewKindChoiceText(kind: String?): Int = when (kind) {
    ReviewKinds.PROJECT -> R.string.review_history_made_project
    ReviewKinds.KNOWLEDGE -> R.string.review_history_made_knowledge
    ReviewKinds.FOLLOW_PROJECT -> R.string.review_history_follows_project
    else -> R.string.review_history_review
}

/** "Project card", with "Changed since you last saw it" when its passage changed; null on a knowledge card. */
internal fun reviewKindMark(context: Context, card: ReviewCard): String? {
    if (!card.isProjectCard) return null
    return listOfNotNull(
        context.getString(R.string.review_kind_project_mark),
        context.getString(R.string.review_kind_changed).takeIf { card.changedSinceSeen },
    ).joinToString(" · ")
}

/** The card's details: its kind and where it comes from. */
internal fun reviewKindDetails(context: Context, card: ReviewCard): String = context.getString(when {
    card.isProjectCard && card.reviewKindSource == ReviewKindSource.CARD -> R.string.review_card_details_kind_own_project
    card.isProjectCard -> R.string.review_card_details_kind_project
    card.reviewKindSource == ReviewKindSource.CARD -> R.string.review_card_details_kind_own_knowledge
    else -> R.string.review_card_details_kind_knowledge
})

/** [this] shown card with the kind, Projects and schedule of [updated], its newer read; everything else as shown. */
internal fun ReviewCard.withKindOf(updated: ReviewCard): ReviewCard = copy(
    ownReviewKind = updated.ownReviewKind, reviewKind = updated.reviewKind, reviewKindSource = updated.reviewKindSource,
    reviewProjectIds = updated.reviewProjectIds, followedKind = updated.followedKind, passageChanged = updated.passageChanged,
    passageChangedAt = updated.passageChangedAt, schedule = updated.schedule, knowledgeSchedule = updated.knowledgeSchedule,
)

/** Whether [interval] from [now] ends later than a project card comes back (a week, section 6.2). */
internal fun reviewIntervalExceedsProjectCap(interval: ReviewInterval, now: Instant = Instant.now()): Boolean =
    runCatching { interval.dueAt(now).isAfter(now.plus(ReviewKinds.PROJECT_MAX_INTERVAL)) }.getOrDefault(false)
