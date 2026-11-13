// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Free-form review labels. Tags only describe an answer; the chosen interval alone
 * schedules the card. The built-ins keep stable wire IDs and localized names. Skip is
 * not a tag: it postpones a card (a queue skip) and keeps the tags it had.
 */
object ReviewTags {
    const val CORRECT = "correct"
    const val WRONG = "wrong"
    const val SKIP = "skip"
    val BUILT_IN = listOf(CORRECT, WRONG)
    /** Only a guard against runaway input; screens shorten long tags, the stored tag stays whole. */
    private const val MAX_LENGTH = 1000

    /** Trims and collapses whitespace; built-in names match case-insensitively. */
    fun normalize(text: String, builtInLabels: Map<String, String> = emptyMap()): String? {
        val tag = text.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim()
        if (tag.isEmpty()) return null
        return BUILT_IN.firstOrNull { id ->
            id.equals(tag, ignoreCase = true) || builtInLabels[id]?.equals(tag, ignoreCase = true) == true
        } ?: tag
    }

    /** Most-used first; unused built-ins keep their familiar order ahead of other tags. */
    fun byPopularity(usage: Map<String, Int>, extra: Collection<String> = emptyList()): List<String> =
        (BUILT_IN + usage.keys + extra).distinct().filterNot { it == SKIP }.sortedWith(
            compareByDescending<String> { usage[it] ?: 0 }
                .thenBy { BUILT_IN.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .thenBy { it.lowercase() },
        )

    /** Older clients read `review_action`; keep writing it while they are in use. */
    fun legacyReviewAction(tags: Collection<String>): String? = when {
        WRONG in tags -> "wrong"
        CORRECT in tags -> "correct"
        SKIP in tags -> "skip"
        else -> null
    }
}

internal fun JsonObject.explicitReviewTags(): List<String> = runCatching {
    (get("review_tags") as? JsonArray)?.mapNotNull { element ->
        runCatching { ReviewTags.normalize(element.asString) }.getOrNull()
    }?.distinct()
}.getOrNull().orEmpty()

/** An answer that wrote `review_tags`, even empty, chose its tags; one without it predates tags. */
internal fun JsonObject.recordsReviewTags(): Boolean = get("review_tags") is JsonArray

/**
 * Tags written before tagging existed are derived from the action and rating they recorded.
 * Merged tags read under their new name.
 */
internal fun ReviewHistoryEntry.effectiveTags(): List<String> = ReviewTagMerges.apply(recordedTags())

/** [effectiveTags] with the merges already read ([mapping]: old tag -> new name), each tag once. */
internal fun ReviewHistoryEntry.effectiveTags(mapping: Map<String, String>): List<String> =
    recordedTags().map { mapping[it] ?: it }.distinct()

/**
 * Earlier skips were recorded as a "skip" answer or tag; they carry no tags now. An answer
 * recorded with an empty tag list has no tags; only one without the list reads its tag from
 * the action and rating.
 */
private fun ReviewHistoryEntry.recordedTags(): List<String> =
    reviewTags.filterNot { it.equals(ReviewTags.SKIP, ignoreCase = true) }
        .ifEmpty { if (reviewTagsRecorded) emptyList() else legacyTags() }

/**
 * Whether this is an answer still in effect: not undone, not an undo, not a skip (also not an
 * earlier one recorded as a "skip" answer or tag), not a deletion and not an interval change.
 */
internal fun ReviewHistoryEntry.isAnswerInEffect(): Boolean =
    reviewedAt != null && !retracted && retractedEventId == null && effective &&
        queueAction == null && intervalAction == null && rating != null && rating != "delete" &&
        !reviewAction.equals(ReviewTags.SKIP, ignoreCase = true) &&
        !(reviewTags.isNotEmpty() && reviewTags.all { it.equals(ReviewTags.SKIP, ignoreCase = true) })

private fun ReviewHistoryEntry.legacyTags(): List<String> =
    when {
        reviewTags.isNotEmpty() || queueAction == "skip" || reviewAction == "skip" -> emptyList()
        queueAction != null || intervalAction != null || retractedEventId != null ||
            rating == null || rating == "delete" -> emptyList()
        reviewAction in setOf("wrong", "error") || rating in setOf("again", "ten_minutes") -> listOf(ReviewTags.WRONG)
        else -> listOf(ReviewTags.CORRECT)
    }
