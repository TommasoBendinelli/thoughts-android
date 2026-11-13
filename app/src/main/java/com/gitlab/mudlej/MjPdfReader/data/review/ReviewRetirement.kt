// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonObject
import java.time.Instant
import java.util.UUID

/**
 * Retired review cards (protocol/review-card-retire.md). Retire, Restore to review and their undo
 * are immutable review events with a `queue_action` and no rating, like skips. A card's retired
 * state is a pure function of its events (section 4), so arrival order does not matter, and they
 * never change its schedule: the answer fold skips every queue action.
 */
internal object ReviewRetire {
    const val RETIRE = "retire"
    const val RESTORE = "restore"
    const val UNDO = "undo_retire"
    val ACTIONS = setOf(RETIRE, RESTORE, UNDO)
}

enum class ReviewRetireState { ACTIVE, RETIRED, DELETED }

/** A card's state by section 4; [retiredAt] and [retireEventId] name the retire in effect. */
data class ReviewRetirement(
    val state: ReviewRetireState,
    val retiredAt: Instant? = null,
    val retireEventId: String? = null,
) {
    companion object {
        val ACTIVE = ReviewRetirement(ReviewRetireState.ACTIVE)
        val DELETED = ReviewRetirement(ReviewRetireState.DELETED)
    }
}

private fun JsonObject.text(key: String): String? =
    runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()

/** A deliberate delete: an answer-like delete event that is not an inferred source deletion. */
private fun deliberatelyDeleted(event: JsonObject): Boolean =
    event.text("rating") == "delete" && !event.has("queue_action") && !inferredSourceDeletion(event)

/**
 * The retired cards among the cards of [events] (any cards, any order, duplicates allowed), by
 * card id. Deleted cards are left out: deletion wins (section 4.1).
 */
internal fun reviewRetiredCards(events: Iterable<JsonObject>): Map<String, ReviewRetirement> {
    val unique = LinkedHashMap<String, JsonObject>()
    events.forEach { event -> event.text("event_id")?.takeIf(String::isNotEmpty)?.let { unique.putIfAbsent(it, event) } }
    val deleted = unique.values.filter(::deliberatelyDeleted).mapNotNullTo(hashSetOf()) { it.text("card_id") }
    // An undo cancels only an action of its own card (section 4.3).
    val undone = unique.values.filter { it.text("queue_action") == ReviewRetire.UNDO }
        .mapNotNullTo(hashSetOf()) { undo -> undo.text("undone_event_id")?.let { (undo.text("card_id") to it) } }
    data class Action(val eventId: String, val at: Instant, val retire: Boolean)
    val latest = HashMap<String, Action>()
    unique.forEach { (eventId, event) ->
        val action = event.text("queue_action")
        if (action != ReviewRetire.RETIRE && action != ReviewRetire.RESTORE) return@forEach
        val cardId = event.text("card_id")?.takeIf(String::isNotEmpty) ?: return@forEach
        val at = event.text("reviewed_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@forEach
        if ((cardId to eventId) in undone) return@forEach
        val candidate = Action(eventId, at, action == ReviewRetire.RETIRE)
        val current = latest[cardId]
        // The order of review history: by action time, then by event id.
        if (current == null || compareValuesBy(candidate, current, Action::at, Action::eventId) > 0) latest[cardId] = candidate
    }
    return latest.filter { (cardId, action) -> action.retire && cardId !in deleted }
        .mapValues { (_, action) -> ReviewRetirement(ReviewRetireState.RETIRED, action.at, action.eventId) }
}

/** [cardId]'s state from [events] (section 4); events of other cards are ignored. */
internal fun reviewRetirement(cardId: String, events: Iterable<JsonObject>): ReviewRetirement {
    val own = events.filter { it.text("card_id") == cardId }
    if (own.any(::deliberatelyDeleted)) return ReviewRetirement.DELETED
    return reviewRetiredCards(own)[cardId] ?: ReviewRetirement.ACTIVE
}

/**
 * A retire or restore event (section 2): schema 3, a random event id, no rating. Older clients
 * read it as neither an answer nor a skip.
 */
internal fun reviewRetireEvent(
    cardId: String,
    action: String,
    at: Instant,
    originId: String,
    undoneEventId: String? = null,
): JsonObject {
    require(action in ReviewRetire.ACTIONS)
    UUID.fromString(cardId)
    require((action == ReviewRetire.UNDO) == (undoneEventId != null)) { "Only an undo names the event it undoes" }
    undoneEventId?.let(UUID::fromString)
    return JsonObject().apply {
        addProperty("schema_version", 3)
        addProperty("event_id", UUID.randomUUID().toString())
        addProperty("card_id", cardId.lowercase())
        addProperty("queue_action", action)
        undoneEventId?.let { addProperty("undone_event_id", it.lowercase()) }
        addProperty("reviewed_at", at.toString())
        addProperty("origin_id", originId)
    }
}
