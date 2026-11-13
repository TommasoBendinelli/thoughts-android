// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

enum class ReviewSkipScope { CARD, DOCUMENT, PROJECT }

data class ReviewSkipBatch(val id: String, val cardIds: Set<String>, val until: Instant)

/** Durable staging for queue actions, including migration of older phone-local skips. */
internal class ReviewSkipStore(private val directory: File) {
    /** [versions]: each card's [ReviewVersionStamp] field, carried onto its skip event. */
    fun save(cardIds: Set<String>, until: Instant, now: Instant, versions: Map<String, JsonObject> = emptyMap()): ReviewSkipBatch {
        require(cardIds.isNotEmpty()) { "No cards to skip" }
        require(until.isAfter(now)) { "Choose a future time" }
        until.toEpochMilli()
        cardIds.forEach(UUID::fromString)
        val batch = ReviewSkipBatch(UUID.randomUUID().toString(), cardIds, until)
        val payload = JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("skip_id", batch.id)
            addProperty("skipped_at", now.toString())
            addProperty("skip_until", until.toString())
            add("card_ids", JsonArray().apply { cardIds.sorted().forEach { add(it) } })
            versions.filterKeys { it in cardIds }.takeIf { it.isNotEmpty() }?.let { stamps ->
                add("review_versions", JsonObject().apply { stamps.forEach { (cardId, stamp) -> add(cardId, stamp.deepCopy()) } })
            }
        }
        check(directory.mkdirs() || directory.isDirectory) { "Could not save skip" }
        val temporary = File(directory, ".${batch.id}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(File(directory, "${batch.id}.json"))) { "Could not save skip" }
        } finally {
            temporary.delete()
        }
        return batch
    }

    fun queueUndo(id: String, cardIds: Set<String>, now: Instant) {
        UUID.fromString(id)
        require(cardIds.isNotEmpty()) { "The skip is unavailable" }
        cardIds.forEach(UUID::fromString)
        val manifestId = UUID.nameUUIDFromBytes("undo-skip:$id".toByteArray(Charsets.UTF_8)).toString()
        val payload = JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("skip_id", id)
            addProperty("queue_action", "undo_skip")
            addProperty("skipped_at", now.toString())
            add("card_ids", JsonArray().apply { cardIds.sorted().forEach { add(it) } })
        }
        check(directory.mkdirs() || directory.isDirectory) { "Could not save undo skip" }
        val temporary = File(directory, ".$manifestId.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(File(directory, "$manifestId.json"))) { "Could not save undo skip" }
        } finally {
            temporary.delete()
        }
    }

    fun migrate(originId: String, persist: (JsonObject) -> Unit) {
        directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.forEach { file ->
            val events = runCatching {
                val payload = JsonParser.parseString(file.readText()).asJsonObject
                require(payload["schema_version"].asInt == 1)
                val id = UUID.fromString(payload["skip_id"].asString).toString()
                val action = payload["queue_action"]?.asString ?: "skip"
                require(action == "skip" || action == "undo_skip")
                val at = Instant.parse(payload["skipped_at"].asString)
                val until = if (action == "skip") Instant.parse(payload["skip_until"].asString) else null
                require(until == null || until.isAfter(at))
                until?.toEpochMilli()
                val ids = payload.getAsJsonArray("card_ids").map { UUID.fromString(it.asString).toString() }
                val versions = payload.get("review_versions")?.takeIf { it.isJsonObject }?.asJsonObject
                require(ids.isNotEmpty())
                ids.distinct().sorted().map { cardId ->
                    JsonObject().apply {
                        addProperty("schema_version", 3)
                        addProperty("event_id", UUID.nameUUIDFromBytes("thoughts-queue-v1\n$action\n$id\n$cardId".toByteArray(Charsets.UTF_8)).toString())
                        addProperty("card_id", cardId)
                        addProperty("skip_id", id)
                        addProperty("queue_action", action)
                        addProperty("reviewed_at", at.toString())
                        until?.let { addProperty("skip_until", it.toString()) }
                        addProperty("origin_id", originId)
                        versions?.get(cardId)?.takeIf { it.isJsonObject }?.let { add(ReviewVersionStamp.FIELD, it.deepCopy()) }
                    }
                }
            }.getOrNull() ?: return@forEach
            // Keep the original manifest until every stable event is durable.
            // Retrying an interrupted migration produces the same event IDs.
            events.forEach(persist)
            val archive = File(directory, "migrated")
            check(archive.mkdirs() || archive.isDirectory) { "Could not archive migrated skip" }
            check(file.renameTo(File(archive, file.name))) { "Could not finish skip migration" }
        }
    }

}

/**
 * Order-independent queue projection; undo only cancels its own card/batch pair.
 * The most recent remaining skip decides: skipping again replaces an earlier, longer
 * skip instead of the longest skip hiding the card.
 */
internal fun reviewSkippedUntil(events: List<JsonObject>): Map<String, Instant> {
    data class Action(val key: Pair<String, String>, val undo: Boolean, val until: Instant?, val at: Instant)
    val actions = events.mapNotNull { event ->
        runCatching {
            require(event["schema_version"].asInt == 3)
            UUID.fromString(event["event_id"].asString)
            val cardId = UUID.fromString(event["card_id"].asString).toString()
            val skipId = UUID.fromString(event["skip_id"].asString).toString()
            val action = event["queue_action"].asString
            require(action == "skip" || action == "undo_skip")
            val at = Instant.parse(event["reviewed_at"].asString)
            val until = if (action == "skip") Instant.parse(event["skip_until"].asString) else null
            require(until == null || until.isAfter(at))
            until?.toEpochMilli()
            Action(cardId to skipId, action == "undo_skip", until, at)
        }.getOrNull()
    }
    val undone = actions.filter { it.undo }.mapTo(hashSetOf()) { it.key }
    return actions.filter { !it.undo && it.key !in undone }
        .groupBy { it.key.first }
        .mapValues { (_, skips) ->
            checkNotNull(skips.maxWith(compareBy<Action>({ it.at }, { it.key.second }, { it.until })).until)
        }
}

internal fun reviewSkipTargets(
    cards: List<ReviewCard>,
    current: ReviewCard,
    scope: ReviewSkipScope,
    projectsByFilename: Map<String, String>,
    now: Instant = Instant.now(),
    dueOnly: Boolean = false,
): Set<String> {
    val project = projectsByFilename["${current.pdfStem}.pdf"]?.takeIf(String::isNotBlank)
    require(scope != ReviewSkipScope.PROJECT || project != null) { "Project information is unavailable for this document" }
    return cards.asSequence()
        .filter { it.approved && it.schedule?.deleted != true && it.schedule?.retained != true && !it.retired }
        .filter { card ->
            card.id == current.id || !dueOnly ||
                (card.skippedUntil?.isAfter(now) != true &&
                    (card.schedule?.dueAt ?: card.initialDueAt)?.isAfter(now) != true)
        }
        .filter { card ->
            when (scope) {
                ReviewSkipScope.CARD -> card.id == current.id
                ReviewSkipScope.DOCUMENT -> card.pdfStem == current.pdfStem
                ReviewSkipScope.PROJECT -> projectsByFilename["${card.pdfStem}.pdf"] == project
            }
        }.map(ReviewCard::id).toSet()
}
