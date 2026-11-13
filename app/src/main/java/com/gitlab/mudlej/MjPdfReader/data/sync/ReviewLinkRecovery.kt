// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.LocalReviewSources
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourceMarkup
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

private val REJECTED_OVERLAPPING_REVIEW_LINKS = setOf(
    "Another review overlaps this passage. Edit the source instead",
    "This passage contains other review markers",
)

private const val CHANGED_REVIEW_PASSAGE = "The review passage or its markers changed. Sync and reopen the card"

/**
 * A new card whose passage another edit changed before it arrived. Fedora now merges its
 * markers onto the current source and lets the card follow them, so the same transaction
 * can succeed when retried.
 */
internal fun ThoughtsOutboxEntity.isRetryableChangedReviewPassage(): Boolean = runCatching {
    deliveryState == ThoughtsOutboxEntity.STATE_BLOCKED && entityType == "review_card" &&
        collection == "cards" && failureCode == "http_409" && failureDetail == CHANGED_REVIEW_PASSAGE &&
        JsonParser.parseString(payloadJson).asJsonObject.get("source_change")?.isJsonObject == true
}.getOrDefault(false)

/** Fedora refuses a card passage that overlaps another card; retrying the same transaction cannot succeed. */
internal fun ThoughtsOutboxEntity.isRejectedOverlappingReviewLink(): Boolean = runCatching {
    deliveryState == ThoughtsOutboxEntity.STATE_BLOCKED && entityType == "review_card" &&
        collection == "cards" && failureCode == "http_409" &&
        failureDetail in REJECTED_OVERLAPPING_REVIEW_LINKS &&
        JsonParser.parseString(payloadJson).asJsonObject.get("source_change")?.isJsonObject == true
}.getOrDefault(false)

private val UNMERGEABLE_REVIEW_LINKS = setOf(
    "Concurrent source edits conflict with the local card operation; both remain pending",
    CHANGED_REVIEW_PASSAGE,
)

/**
 * A card link whose markers Fedora could not merge onto the current source, or that it
 * still refused after a retry. Resending the same transaction cannot succeed.
 */
internal fun ThoughtsOutboxEntity.isUnmergeableReviewLink(): Boolean = runCatching {
    deliveryState == ThoughtsOutboxEntity.STATE_BLOCKED && entityType == "review_card" &&
        collection == "cards" && failureCode == "http_409" && failureDetail in UNMERGEABLE_REVIEW_LINKS &&
        JsonParser.parseString(payloadJson).asJsonObject.get("source_change")?.isJsonObject == true
}.getOrDefault(false)

/**
 * The card without its source transaction, marked like a card whose linking failed, so it can be
 * placed again. [reason] (Fedora's refusal) is kept for Card details.
 */
internal fun unlinkedReviewCardPayload(payload: JsonObject, reason: String = ""): JsonObject = payload.deepCopy().apply {
    remove("source_change")
    add("source_link", JsonObject().apply {
        addProperty("state", "linking_failed")
        if (reason.isNotBlank()) addProperty("reason", reason)
    })
    getAsJsonObject("primary")?.remove("source_anchor")
}

/**
 * A card update whose source change moves markers the card already has (Edit card or "Place this
 * card again" on a linked card): its "before" source, kept in [objects], holds the card's id.
 */
internal fun movesReviewMarkers(payload: JsonObject, objects: File): Boolean = runCatching {
    val cardId = payload.get("card_id")?.asString?.takeIf(String::isNotBlank) ?: return@runCatching false
    val before = payload.getAsJsonObject("source_change")?.get("before_sha256")?.asString ?: return@runCatching false
    if (!before.matches(Regex("[0-9a-f]{64}"))) return@runCatching false
    File(objects, before).takeIf(File::isFile)?.readText()?.contains(cardId) == true
}.getOrDefault(false)

/**
 * Puts the card's previous markers back in the working copy after Fedora refused to move them:
 * only when the copy still is exactly the refused result.
 */
internal fun restoreReplacedReviewMarkers(sources: OfflineSourceBundleStore, payload: JsonObject, objects: File): Boolean = runCatching {
    val change = payload.getAsJsonObject("source_change")
    val path = change.get("path").asString
    val before = File(objects, change.get("before_sha256").asString).readBytes()
    val working = sources.sourceWorkingFile(path)?.takeIf { it.isFile } ?: return@runCatching false
    if (LocalReviewSources.digest(working.readBytes()) != change.get("after_sha256").asString) return@runCatching false
    check(LocalReviewSources.digest(before) == change.get("before_sha256").asString)
    sources.writeWorkingContents(path, before.toString(Charsets.UTF_8))
}.getOrDefault(false)

/** Removes a refused card's markers from the working copy; false when they could not be removed. */
internal fun removeUnsentReviewMarkers(sources: OfflineSourceBundleStore, payload: JsonObject): Boolean = runCatching {
    val path = payload.getAsJsonObject("source_change").get("path").asString
    val cardId = payload.get("card_id").asString
    val segments = payload.getAsJsonObject("primary").getAsJsonObject("source_anchor").getAsJsonArray("segments")
        .map { it.asJsonObject }.filter { it.get("source_file")?.asString == path }
    val working = sources.sourceWorkingFile(path)?.takeIf { it.isFile } ?: return@runCatching true
    var contents = working.readText()
    if (!contents.contains(cardId)) return@runCatching true
    segments.forEach { contents = ReviewSourceMarkup.remove(path, contents, cardId, it, false).after }
    !contents.contains(cardId) && sources.writeWorkingContents(path, contents)
}.getOrDefault(false)
