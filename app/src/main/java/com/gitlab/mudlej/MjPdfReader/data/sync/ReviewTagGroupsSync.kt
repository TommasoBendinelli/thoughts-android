// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups

/** What a full Sync does with the tag group layout before it sends the outbox. */
internal data class ReviewTagGroupsOutboxPlan(
    val upload: ReviewTagGroups.Upload?,
    /** Older layouts still waiting to be sent; only the latest one is worth sending. */
    val superseded: List<String>,
)

/**
 * Sends the pending layout only to servers that accept it ([REVIEW_TAG_GROUPS_CAPABILITY]) and only
 * on full Syncs; otherwise the edit stays pending for a later Sync, as tag merges do. A layout with
 * slider groups (`scale`) also needs [ReviewTagGroups.SCALES_CAPABILITY], one with sections
 * (`section`) [ReviewTagGroups.SECTIONS_CAPABILITY], and one with phases (`phase`)
 * [ReviewTagGroups.PHASES_CAPABILITY].
 */
internal fun reviewTagGroupsOutboxPlan(
    capabilities: Collection<String>,
    targeted: Boolean,
    pending: ReviewTagGroups.Upload?,
    undelivered: List<ThoughtsOutboxEntity>,
): ReviewTagGroupsOutboxPlan {
    if (REVIEW_TAG_GROUPS_CAPABILITY !in capabilities || targeted) return ReviewTagGroupsOutboxPlan(null, emptyList())
    // A layout with sliders waits for a server that keeps `scale`; older ones refuse the whole batch.
    if (pending != null && reviewTagGroupsUseScales(pending) && ReviewTagGroups.SCALES_CAPABILITY !in capabilities) {
        return ReviewTagGroupsOutboxPlan(null, emptyList())
    }
    // Likewise a layout with sections waits for a server that keeps `section`.
    if (pending != null && reviewTagGroupsUseSections(pending) && ReviewTagGroups.SECTIONS_CAPABILITY !in capabilities) {
        return ReviewTagGroupsOutboxPlan(null, emptyList())
    }
    // And a layout with phases (review in two steps) for a server that keeps `phase`.
    if (pending != null && reviewTagGroupsUsePhases(pending) && ReviewTagGroups.PHASES_CAPABILITY !in capabilities) {
        return ReviewTagGroupsOutboxPlan(null, emptyList())
    }
    val stale = undelivered.filter {
        it.entityType == ReviewTagGroups.ENTITY_TYPE && it.isUndelivered && it.operationId != pending?.operationId
    }.map { it.operationId }
    return ReviewTagGroupsOutboxPlan(pending, stale)
}

/** True when an upload carries a slider group, i.e. a group with a `scale` field. */
internal fun reviewTagGroupsUseScales(upload: ReviewTagGroups.Upload): Boolean =
    upload.payload.getAsJsonArray("groups")?.any { it.isJsonObject && it.asJsonObject.has("scale") } == true

/** True when an upload puts a group under a section, i.e. a group with a non-null `section` field. */
internal fun reviewTagGroupsUseSections(upload: ReviewTagGroups.Upload): Boolean =
    upload.payload.getAsJsonArray("groups")?.any { group ->
        group.isJsonObject && group.asJsonObject.get("section")?.isJsonNull == false
    } == true

/** True when an upload says when a group is asked, i.e. a group with a non-null `phase` field (any value). */
internal fun reviewTagGroupsUsePhases(upload: ReviewTagGroups.Upload): Boolean =
    upload.payload.getAsJsonArray("groups")?.any { group ->
        group.isJsonObject && group.asJsonObject.get("phase")?.isJsonNull == false
    } == true
