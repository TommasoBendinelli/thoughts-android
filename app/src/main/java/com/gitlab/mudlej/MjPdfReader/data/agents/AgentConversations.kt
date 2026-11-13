// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agents

import com.gitlab.mudlej.MjPdfReader.data.source.AgentProposals
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Agent conversations: the Claude Code and Codex conversations that worked on a Library Project
 * (spec thoughts-agent-conversations-SPEC.md, protocol/agent-conversations.md). They sync as
 * `agent_conversation` entities (collection `agent-conversations`), one record per (Project,
 * conversation), last write wins on `(revision, updated_at, origin_id)`.
 *
 * The desktop's Thoughts MCP links conversations; the phone only shows them and removes them
 * (a `removed` flag, the record is kept). A removal waits in a small file until a full Sync sends
 * it to a server that advertises [CAPABILITY].
 */
object AgentConversations {
    const val ENTITY_TYPE = "agent_conversation"
    const val COLLECTION = "agent-conversations"
    /** Servers that accept the entity; the phone holds its removals until the server advertises it. */
    const val CAPABILITY = "agent_conversations_v1"
    const val SCHEMA_VERSION = 1

    const val AGENT_CLAUDE = "claude"
    const val AGENT_CODEX = "codex"
    const val CLAUDE_URL_PREFIX = "https://claude.ai/code/session_"
    const val CODEX_URL_PREFIX = "codex://threads/"

    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val CONVERSATION_ID = Regex("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
    private val LAST_TOOL = Regex("^[a-z_]{0,64}$")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")
    private val AGENTS = setOf(AGENT_CLAUDE, AGENT_CODEX)
    private val TITLE_SOURCES = setOf("agent", "app", "prompt", "fallback")
    private val LINKED_BY = setOf("agent", "tool", "backfill")

    /** `sha256_hex(utf8(project_id + "\n" + agent + ":" + conversation_id))`. */
    fun linkId(projectId: String, agent: String, conversationId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$projectId\n$agent:$conversationId".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** One record; [payload] is kept whole so fields this client does not know survive a removal. */
    class Record internal constructor(val payload: JsonObject) {
        val linkId: String get() = payload.string("link_id")
        val projectId: String get() = payload.string("project_id")
        val agent: String get() = payload.string("agent")
        val conversationId: String get() = payload.string("conversation_id")
        val conversationUrl: String get() = payload.string("conversation_url")
        val title: String get() = payload.string("title")
        val sessionName: String get() = payload.string("session_name")
        val deviceName: String get() = payload.string("device_name")
        val lastActiveAt: String get() = payload.string("last_active_at")
        val updatedAt: String get() = payload.string("updated_at")
        val originId: String get() = payload.string("origin_id")
        val revision: Long get() = payload.get("revision")?.takeIf { it.isNumber() }?.asLong ?: 1L
        val removed: Boolean get() = payload.flag("removed")
        val deleted: Boolean get() = payload.flag("deleted")
        val visible: Boolean get() = !removed && !deleted

        /** The claude.ai link a tap opens, or null (Codex threads and Claude sessions without Remote Control). */
        val claudeUrl: String?
            get() = conversationUrl.takeIf {
                agent == AGENT_CLAUDE && it.startsWith(CLAUDE_URL_PREFIX) && AgentProposals.validConversationUrl(it)
            }

        override fun equals(other: Any?): Boolean = other is Record && other.payload == payload
        override fun hashCode(): Int = payload.hashCode()
        override fun toString(): String = payload.toString()
    }

    data class Upload(
        val operationId: String,
        val entityId: String,
        val baseRevision: Long?,
        val clientTimestamp: String,
        val payload: JsonObject,
    )

    // ---- Pure rules -------------------------------------------------------------------------

    /** Why [payload] is not a valid schema-1 record (spec §2), or null when it is. */
    fun validationError(payload: JsonObject): String? {
        fun text(name: String): String? = payload.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun timestamp(name: String): String? = text(name)?.takeIf(::validTimestamp)
        val version = payload.get("schema_version")
        if (version == null || !version.isNumber() || version.asJsonPrimitive.asString != "1") return "schema_version must be 1"
        val linkId = text("link_id") ?: return "link_id is missing"
        if (!HEX64.matches(linkId)) return "link_id must be 64 lowercase hex characters"
        val projectId = text("project_id") ?: return "project_id is missing"
        if (projectId.isEmpty() || projectId.length > 512 || CONTROL.containsMatchIn(projectId)) return "project_id is invalid"
        val agent = text("agent")?.takeIf { it in AGENTS } ?: return "agent must be claude or codex"
        val conversationId = text("conversation_id")?.takeIf(CONVERSATION_ID::matches) ?: return "conversation_id must be a UUID"
        if (linkId != linkId(projectId, agent, conversationId)) return "link_id does not match project_id, agent and conversation_id"
        val url = text("conversation_url") ?: return "conversation_url is missing"
        if (url.isNotEmpty()) {
            val valid = if (agent == AGENT_CLAUDE) url.startsWith(CLAUDE_URL_PREFIX) && AgentProposals.validConversationUrl(url)
                else url == CODEX_URL_PREFIX + conversationId
            if (!valid) return "conversation_url is not a valid link to this conversation"
        }
        val title = text("title") ?: return "title is missing"
        if (CONTROL.containsMatchIn(title)) return "title must be a single line without control characters"
        if (title.trim().length !in 1..200) return "title must have 1 to 200 characters"
        if (text("title_source") !in TITLE_SOURCES) return "title_source is invalid"
        if ((text("session_name") ?: return "session_name is missing").length > 100) return "session_name is too long"
        if (text("linked_by") !in LINKED_BY) return "linked_by is invalid"
        if (!LAST_TOOL.matches(text("last_tool") ?: return "last_tool is missing")) return "last_tool is invalid"
        if ((text("device_name") ?: return "device_name is missing").length > 128) return "device_name is too long"
        for (name in listOf("created_at", "last_active_at", "updated_at")) {
            timestamp(name) ?: return "$name must be an RFC 3339 timestamp"
        }
        val revision = payload.get("revision")
        if (revision == null || !revision.isNumber() || revision.asJsonPrimitive.asString.toLongOrNull()?.let { it >= 1 } != true) {
            return "revision must be an integer of at least 1"
        }
        val origin = text("origin_id") ?: return "origin_id is missing"
        if (origin.length !in 1..128) return "origin_id is invalid"
        val removed = payload.get("removed")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            ?: return "removed must be true or false"
        val removedAt = payload.get("removed_at")
        val hasRemovedAt = removedAt != null && !removedAt.isJsonNull
        if (hasRemovedAt && timestamp("removed_at") == null) return "removed_at must be an RFC 3339 timestamp"
        if (removed != hasRemovedAt) return "removed_at is required on removed records and only allowed there"
        payload.get("deleted")?.let { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) return "deleted must be true or false" }
        return null
    }

    /**
     * Reads a record from the server or from storage. Only identity is required to show it; the
     * server validated the rest. Records without link_id, project, agent or conversation are refused.
     */
    fun parse(payload: JsonObject): Record? = runCatching {
        val record = Record(payload.deepCopy())
        require(HEX64.matches(record.linkId) && record.projectId.isNotEmpty())
        require(record.agent in AGENTS && CONVERSATION_ID.matches(record.conversationId))
        record
    }.getOrNull()

    /** Server order: revision, then `updated_at`, then origin id (last write wins). */
    internal fun priority(a: Record, b: Record): Int {
        a.revision.compareTo(b.revision).takeIf { it != 0 }?.let { return it }
        compareTimes(a.updatedAt, b.updatedAt).takeIf { it != 0 }?.let { return it }
        return a.originId.compareTo(b.originId)
    }

    /**
     * Spec §3.3: the stored record copied with `removed` true, `removed_at` now, the next revision,
     * an `updated_at` strictly after the stored one and this device's origin. `last_active_at` stays.
     */
    fun removedRecord(stored: Record, originId: String, now: Instant = Instant.now()): Record {
        require(originId.length in 1..128) { "Invalid origin id" }
        val payload = stored.payload.deepCopy()
        payload.addProperty("removed", true)
        payload.addProperty("removed_at", timestamp(now))
        payload.addProperty("revision", stored.revision + 1)
        payload.addProperty("updated_at", timestamp(laterThan(stored.updatedAt, now)))
        payload.addProperty("origin_id", originId)
        return Record(payload)
    }

    internal fun uploadOf(record: Record): Upload {
        val payload = record.payload.deepCopy()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val operationId = UUID.nameUUIDFromBytes(
            listOf(ENTITY_TYPE, record.linkId, record.revision.toString(), digest).joinToString("\u0000")
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()
        return Upload(operationId, record.linkId, (record.revision - 1).takeIf { it > 0 }, record.updatedAt, payload)
    }

    /**
     * Visible conversations of every Project: the server's records with pending local writes laid
     * over them, without removed or deleted ones, newest activity first, then by link id.
     */
    fun visibleByProject(projections: List<JsonObject>, pending: Collection<Record>): Map<String, List<Record>> {
        return merged(projections, pending).filter { it.visible }
            .sortedWith(Comparator<Record> { a, b -> compareTimes(b.lastActiveAt, a.lastActiveAt) }.thenBy { it.linkId })
            .groupBy { it.projectId }
    }

    fun visibleFor(projectId: String, projections: List<JsonObject>, pending: Collection<Record>): List<Record> =
        visibleByProject(projections, pending)[projectId].orEmpty()

    /** The server's records, the latest of each link id, with pending local writes laid over them. */
    private fun merged(projections: List<JsonObject>, pending: Collection<Record>): Collection<Record> {
        val merged = linkedMapOf<String, Record>()
        projections.mapNotNull(::parse).forEach { record ->
            val previous = merged[record.linkId]
            if (previous == null || priority(record, previous) > 0) merged[record.linkId] = record
        }
        pending.forEach { merged[it.linkId] = it }
        return merged.values
    }

    // ---- Review cards (thoughts-card-agent-conversations-SPEC.md §1, §2) --------------------

    /** The payload field holding the review cards a conversation is linked to. */
    const val REVIEW_CARDS = "review_cards"
    private val CARD_ID = Regex("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$")

    /** One well-formed `review_cards` entry. The phone never writes these except `removed`. */
    data class CardLink(
        val cardId: String,
        val linkedAt: String,
        val lastActiveAt: String,
        val linkedBy: String,
        val removed: Boolean,
    )

    /** A conversation shown on a review card: the whole record and its entry for that card. */
    data class CardConversation(val record: Record, val link: CardLink)

    /**
     * The entry [element] stands for, or null when it is malformed: not an object, a card id that
     * is not a lowercase UUID, missing or unreadable timestamps, a non-boolean `removed`. A missing
     * `removed` reads as false. Readers skip malformed entries instead of rejecting the record.
     */
    private fun cardLinkOf(element: JsonElement): CardLink? {
        if (!element.isJsonObject) return null
        val entry = element.asJsonObject
        fun text(name: String): String? = entry.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val cardId = text("card_id")?.takeIf(CARD_ID::matches) ?: return null
        val linkedAt = text("linked_at")?.takeIf(::validTimestamp) ?: return null
        val lastActiveAt = text("last_active_at")?.takeIf(::validTimestamp) ?: return null
        val removed = when (val value = entry.get("removed")) {
            null, is com.google.gson.JsonNull -> false
            else -> value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: return null
        }
        return CardLink(cardId, linkedAt, lastActiveAt, text("linked_by").orEmpty(), removed)
    }

    /** The record's well-formed card entries; for a card listed twice, the first one counts. */
    fun cardLinks(record: Record): List<CardLink> {
        val array = record.payload.get(REVIEW_CARDS)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val seen = mutableSetOf<String>()
        return array.mapNotNull(::cardLinkOf).filter { seen.add(it.cardId) }
    }

    fun cardLink(record: Record, cardId: String): CardLink? {
        val id = cardId.lowercase()
        return cardLinks(record).firstOrNull { it.cardId == id }
    }

    /**
     * The conversations listed on review card [cardId] (card spec §2): every record that is not
     * deleted, the server's with pending local writes laid over them, whose entry for the card is
     * not removed. A Project-level removal does not hide it here. Newest entry `last_active_at`
     * first, then the record's `last_active_at`, then link id.
     */
    fun cardConversations(cardId: String, projections: List<JsonObject>, pending: Collection<Record>): List<CardConversation> =
        merged(projections, pending).asSequence()
            .filterNot { it.deleted }
            .mapNotNull { record -> cardLink(record, cardId)?.takeUnless { it.removed }?.let { CardConversation(record, it) } }
            .sortedWith(
                Comparator<CardConversation> { a, b -> compareTimes(b.link.lastActiveAt, a.link.lastActiveAt) }
                    .then(Comparator { a, b -> compareTimes(b.record.lastActiveAt, a.record.lastActiveAt) })
                    .thenBy { it.record.linkId },
            )
            .toList()

    /**
     * Card spec §2 "Remove from card": the stored record copied with every entry for [cardId]
     * marked `removed`, the next revision, an `updated_at` strictly after the stored one and this
     * device's origin. Project-level fields (`removed`, `removed_at`, `last_active_at`) and every
     * other field, entry and unknown key stay as they are. Null when the card has no live entry.
     */
    fun cardRemovedRecord(stored: Record, cardId: String, originId: String, now: Instant = Instant.now()): Record? {
        require(originId.length in 1..128) { "Invalid origin id" }
        val link = cardLink(stored, cardId) ?: return null
        if (link.removed) return null
        val payload = stored.payload.deepCopy()
        payload.getAsJsonArray(REVIEW_CARDS).forEach { element ->
            if (cardLinkOf(element)?.cardId == link.cardId) element.asJsonObject.addProperty("removed", true)
        }
        payload.addProperty("revision", stored.revision + 1)
        payload.addProperty("updated_at", timestamp(laterThan(stored.updatedAt, now)))
        payload.addProperty("origin_id", originId)
        return Record(payload)
    }

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** The §2 timestamp format: `yyyy-MM-ddTHH:mm:ss.SSSZ` in UTC. */
    internal fun timestamp(instant: Instant): String = TIMESTAMP.format(instant)

    fun instant(value: String): Instant? = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()

    private fun validTimestamp(value: String): Boolean = instant(value) != null

    private fun compareTimes(a: String, b: String): Int {
        val first = instant(a)
        val second = instant(b)
        return when {
            first != null && second != null -> first.compareTo(second)
            first != null -> 1
            second != null -> -1
            else -> a.compareTo(b)
        }
    }

    /** Always after [previous], even if the clock went back. */
    private fun laterThan(previous: String, now: Instant): Instant {
        val last = instant(previous) ?: return now
        // Compare at the written millisecond precision.
        val nowMillis = Instant.ofEpochMilli(now.toEpochMilli())
        return if (nowMillis.isAfter(last)) nowMillis else Instant.ofEpochMilli(last.toEpochMilli()).plusMillis(1)
    }

    // ---- Pending local writes ---------------------------------------------------------------

    /** Overridable for tests. */
    @Volatile var file: () -> File = { ThoughtsLocalStore.agentConversations() }
    @Volatile private var cached: Triple<File, Long, Map<String, Record>>? = null

    /** Local removals still to be sent, by link id. */
    fun pending(): List<Record> = load().values.toList()

    fun pendingUploads(): List<Upload> = load().values.map(::uploadOf)

    /**
     * Removes [stored] from its Project (spec §3.3). The removal is kept locally and sent at the
     * next full Sync. Returns the removed record; refuses a record that would not validate.
     */
    @Synchronized
    fun remove(stored: Record, originId: String, now: Instant = Instant.now()): Record {
        val current = load()[stored.linkId]?.takeIf { priority(it, stored) >= 0 } ?: stored
        if (current.removed) return current
        val next = removedRecord(current, originId, now)
        validationError(next.payload)?.let { error("This conversation link cannot be removed: $it") }
        write(load() + (next.linkId to next))
        return next
    }

    /**
     * Removes [stored] from review card [cardId] (card spec §2), on top of a pending local write of
     * the same record when that is as new. Kept locally and sent at the next full Sync, like a
     * Project removal. Returns the record as it now stands (unchanged when the card had no live
     * entry); refuses a record that would not validate.
     */
    @Synchronized
    fun removeFromCard(stored: Record, cardId: String, originId: String, now: Instant = Instant.now()): Record {
        val current = load()[stored.linkId]?.takeIf { priority(it, stored) >= 0 } ?: stored
        val next = cardRemovedRecord(current, cardId, originId, now) ?: return current
        validationError(next.payload)?.let { error("This conversation cannot be removed from the card: $it") }
        write(load() + (next.linkId to next))
        return next
    }

    /**
     * Settles pending removals against the records Sync holds: one is dropped once the server holds
     * the same or a later state, or when its operation was accepted or superseded.
     */
    @Synchronized
    fun installServer(payloads: List<JsonObject>, accepted: Set<String> = emptySet(), superseded: Set<String> = emptySet()) {
        val server = payloads.mapNotNull(::parse).groupBy { it.linkId }
            .mapValues { (_, records) -> records.maxWith { a, b -> priority(a, b) } }
        val current = load()
        val next = current.filter { (id, record) ->
            val operationId = uploadOf(record).operationId
            val remote = server[id]
            when {
                operationId in superseded -> false
                remote != null -> priority(record, remote) > 0
                else -> operationId !in accepted
            }
        }
        if (next != current) write(next)
    }

    private fun load(): Map<String, Record> {
        val target = runCatching(file).getOrNull() ?: return emptyMap()
        val stamp = if (target.isFile) target.lastModified() xor target.length() else -1L
        cached?.takeIf { it.first == target && it.second == stamp }?.let { return it.third }
        val value = runCatching {
            JsonParser.parseString(target.readText()).asJsonObject.getAsJsonArray("pending").mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parse)?.let { it.linkId to it }
            }.toMap(linkedMapOf())
        }.getOrDefault(emptyMap())
        cached = Triple(target, stamp, value)
        return value
    }

    private fun write(entries: Map<String, Record>) {
        val target = file()
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(JsonObject().apply {
            addProperty("schema_version", 1)
            add("pending", JsonArray().apply { entries.values.forEach { add(it.payload) } })
        }.toString())
        check(temporary.renameTo(target)) { "Could not save the conversation removal." }
        cached = null
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.flag(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

    private fun JsonElement.isNumber(): Boolean = isJsonPrimitive && asJsonPrimitive.isNumber
}
