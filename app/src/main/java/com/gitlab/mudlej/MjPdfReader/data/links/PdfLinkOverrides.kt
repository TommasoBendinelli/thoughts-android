// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.links

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs

/**
 * PDF link overrides: a link of a Library PDF (an internal jump such as a citation, or a web URI)
 * opens a chosen Library document instead. One override covers every link of that PDF with the
 * same destination. Overrides sync as `pdf_link_override` entities (collection `link-overrides`),
 * last write wins on `revision`; removal is a tombstone (`deleted: true`).
 *
 * The phone (PDFium) knows a jump's destination page and point only, so it stores
 * `dest_page`/`dest_x`/`dest_y`; the desktop also stores `dest_name`. Matching follows the shared
 * spec so an override made on either device matches on the other.
 */
object PdfLinkOverrides {
    const val ENTITY_TYPE = "pdf_link_override"
    const val COLLECTION = "link-overrides"
    /** Servers that accept the entity; clients hold their changes until the server advertises it. */
    const val CAPABILITY = "pdf_link_overrides_v1"
    /** Destinations within this many PDF points are the same destination. */
    const val POINT_TOLERANCE = 4.0

    const val KIND_GOTO = "goto"
    const val KIND_URI = "uri"

    /** A link as the reader finds it under a tap; [name] is null on the phone (PDFium does not report it). */
    sealed class ClickedLink {
        data class GoTo(val page: Int, val x: Double?, val y: Double?, val name: String? = null) : ClickedLink()
        data class Uri(val uri: String) : ClickedLink()
    }

    /** One record; [payload] is kept whole so fields this client does not know survive edits. */
    class Override internal constructor(val payload: JsonObject) {
        val id: String get() = payload.string("override_id")
        val sourceDocumentId: String get() = payload.string("source_document_id")
        val targetDocumentId: String get() = payload.string("target_document_id")
        val revision: Long get() = payload.get("revision")?.takeIf { it.isNumber() }?.asLong ?: 1L
        val updatedAt: String get() = payload.string("updated_at")
        val originId: String get() = payload.string("origin_id")
        val deleted: Boolean get() = payload.get("deleted")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        private val link: JsonObject? get() = payload.get("link")?.takeIf { it.isJsonObject }?.asJsonObject
        val kind: String get() = link?.string("kind").orEmpty()
        val destName: String? get() = link?.string("dest_name")?.takeIf(String::isNotEmpty)
        val destPage: Int? get() = link?.get("dest_page")?.takeIf { it.isNumber() }?.asInt
        val destX: Double? get() = link?.get("dest_x")?.takeIf { it.isNumber() }?.asDouble?.takeIf(Double::isFinite)
        val destY: Double? get() = link?.get("dest_y")?.takeIf { it.isNumber() }?.asDouble?.takeIf(Double::isFinite)
        val uri: String? get() = link?.string("uri")?.takeIf(String::isNotEmpty)

        override fun equals(other: Any?): Boolean = other is Override && other.payload == payload
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

    /**
     * The link the reader found, or null when it can never be overridden: Thoughts' own links
     * (thoughts:, thoughts-pdf-link, thoughts-html, …), local files, and jumps whose
     * destination point has no y (an override for them would match the whole page).
     */
    fun clickedLink(uri: String?, destPage: Int?, destX: Float?, destY: Float?): ClickedLink? {
        if (!uri.isNullOrEmpty()) {
            val scheme = uri.substringBefore(':', "").lowercase()
            if (scheme.isEmpty() || scheme.startsWith("thoughts") || scheme == "file" || scheme == "content") return null
            return ClickedLink.Uri(uri)
        }
        if (destPage == null || destPage < 0) return null
        val y = destY?.toDouble()?.takeIf(Double::isFinite) ?: return null
        return ClickedLink.GoTo(destPage, destX?.toDouble()?.takeIf(Double::isFinite), y)
    }

    fun matches(override: Override, clicked: ClickedLink): Boolean = when (clicked) {
        is ClickedLink.Uri -> override.kind == KIND_URI && override.uri == clicked.uri
        is ClickedLink.GoTo -> override.kind == KIND_GOTO && run {
            val name = override.destName
            if (name != null && clicked.name != null) return@run name == clicked.name
            if (override.destPage != clicked.page) return@run false
            // Neither name nor y: matching the page alone would be too broad.
            val y = override.destY ?: return@run false
            val clickedY = clicked.y ?: return@run false
            if (abs(y - clickedY) > POINT_TOLERANCE) return@run false
            val x = override.destX
            x == null || clicked.x == null || abs(x - clicked.x) <= POINT_TOLERANCE
        }
    }

    /** The override in effect for [clicked] in document [sourceDocumentId]: the latest `updated_at` wins. */
    fun bestMatch(overrides: Collection<Override>, sourceDocumentId: String, clicked: ClickedLink): Override? =
        overrides.filter { !it.deleted && it.sourceDocumentId == sourceDocumentId && matches(it, clicked) }
            .maxWithOrNull { a, b -> compareTimes(a.updatedAt, b.updatedAt).takeIf { it != 0 } ?: priority(a, b) }

    /** Server order: revision, then `updated_at`, then origin id (last write wins). */
    internal fun priority(a: Override, b: Override): Int {
        a.revision.compareTo(b.revision).takeIf { it != 0 }?.let { return it }
        compareTimes(a.updatedAt, b.updatedAt).takeIf { it != 0 }?.let { return it }
        return a.originId.compareTo(b.originId)
    }

    private fun compareTimes(a: String, b: String): Int {
        val first = runCatching { Instant.parse(a) }.getOrNull()
        val second = runCatching { Instant.parse(b) }.getOrNull()
        return if (first != null && second != null) first.compareTo(second) else a.compareTo(b)
    }

    /** Reads a record from the server or from storage; one without identity or link is refused. */
    fun parse(payload: JsonObject): Override? = runCatching {
        val override = Override(payload.deepCopy())
        require(validId(override.id) && override.sourceDocumentId.isNotBlank())
        if (!override.deleted) {
            require(override.targetDocumentId.isNotBlank())
            when (override.kind) {
                KIND_URI -> require(override.uri != null)
                KIND_GOTO -> require(override.destName != null || override.destPage != null)
                else -> error("unknown link kind")
            }
        }
        override
    }.getOrNull()

    fun linkPayload(clicked: ClickedLink): JsonObject = JsonObject().apply {
        when (clicked) {
            is ClickedLink.Uri -> {
                addProperty("kind", KIND_URI)
                addProperty("uri", clicked.uri)
            }
            is ClickedLink.GoTo -> {
                addProperty("kind", KIND_GOTO)
                clicked.name?.let { addProperty("dest_name", it) }
                addProperty("dest_page", clicked.page)
                clicked.x?.let { addProperty("dest_x", it) }
                clicked.y?.let { addProperty("dest_y", it) }
            }
        }
    }

    internal fun newPayload(
        id: String,
        sourceDocumentId: String,
        sourcePdfSha256: String?,
        clicked: ClickedLink,
        targetDocumentId: String,
        updatedAt: String,
        originId: String,
    ): JsonObject = JsonObject().apply {
        addProperty("override_id", id)
        addProperty("source_document_id", sourceDocumentId)
        sourcePdfSha256?.takeIf(String::isNotBlank)?.let { addProperty("source_pdf_sha256", it) }
        add("link", linkPayload(clicked))
        addProperty("target_document_id", targetDocumentId)
        addProperty("revision", 1)
        addProperty("updated_at", updatedAt)
        addProperty("origin_id", originId)
        addProperty("deleted", false)
    }

    /** The record [previous] becomes after a local edit: next revision, later `updated_at`, this origin. */
    internal fun nextRevision(previous: Override, now: Instant, originId: String, edit: JsonObject.() -> Unit): Override {
        val payload = previous.payload.deepCopy()
        payload.edit()
        payload.addProperty("revision", previous.revision + 1)
        payload.addProperty("updated_at", timestamp(laterThan(previous.updatedAt, now)))
        payload.addProperty("origin_id", originId)
        return Override(payload)
    }

    internal fun uploadOf(override: Override): Upload {
        val payload = override.payload.deepCopy()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val operationId = UUID.nameUUIDFromBytes(
            listOf(ENTITY_TYPE, override.id, override.revision.toString(), digest).joinToString("\u0000")
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()
        return Upload(operationId, override.id, (override.revision - 1).takeIf { it > 0 }, override.updatedAt, payload)
    }

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    internal fun timestamp(instant: Instant): String = TIMESTAMP.format(instant)

    /** Always after [previous], even if the clock went back. */
    private fun laterThan(previous: String, now: Instant): Instant {
        val last = runCatching { Instant.parse(previous) }.getOrNull() ?: return now
        return if (now.isAfter(last)) now else last.plusMillis(1)
    }

    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id); id.length == 36 }.getOrDefault(false)

    // ---- Local record -----------------------------------------------------------------------

    /** [override] is the record in effect; [pending] while a local edit has not reached the server. */
    private data class Entry(val override: Override, val pending: Boolean, val serverKnown: Boolean)

    /** Overridable for tests. */
    @Volatile var file: () -> File = { ThoughtsLocalStore.linkOverrides() }
    @Volatile private var cached: Triple<File, Long, Map<String, Entry>>? = null

    /** Every record in effect, tombstones included. */
    fun all(): List<Override> = load().values.map { it.override }

    fun find(id: String): Override? = load()[id]?.override

    /** The override in effect for [clicked] in document [sourceDocumentId], or null. */
    fun overrideFor(sourceDocumentId: String, clicked: ClickedLink): Override? =
        bestMatch(all(), sourceDocumentId, clicked)

    /** Local edits still to be sent, oldest record first. */
    fun pendingUploads(): List<Upload> = load().values.filter { it.pending }.map { uploadOf(it.override) }

    @Synchronized
    fun create(
        sourceDocumentId: String,
        sourcePdfSha256: String?,
        clicked: ClickedLink,
        targetDocumentId: String,
        originId: String,
        now: Instant = Instant.now(),
    ): Override {
        require(sourceDocumentId.isNotBlank() && targetDocumentId.isNotBlank() && sourceDocumentId != targetDocumentId)
        val id = UUID.randomUUID().toString()
        val override = Override(newPayload(id, sourceDocumentId, sourcePdfSha256, clicked, targetDocumentId, timestamp(now), originId))
        write(load() + (id to Entry(override, pending = true, serverKnown = false)))
        return override
    }

    /** Points an existing override at another Library document. */
    @Synchronized
    fun retarget(id: String, targetDocumentId: String, originId: String, now: Instant = Instant.now()): Override {
        val entry = requireNotNull(load()[id]) { "No such link override" }
        require(targetDocumentId.isNotBlank() && targetDocumentId != entry.override.sourceDocumentId)
        val next = nextRevision(entry.override, now, originId) {
            addProperty("target_document_id", targetDocumentId)
            addProperty("deleted", false)
        }
        write(load() + (id to entry.copy(override = next, pending = true)))
        return next
    }

    /** Removes an override everywhere: a tombstone is kept and synced. */
    @Synchronized
    fun remove(id: String, originId: String, now: Instant = Instant.now()): Override? {
        val entry = load()[id] ?: return null
        if (entry.override.deleted) return entry.override
        val next = nextRevision(entry.override, now, originId) { addProperty("deleted", true) }
        write(load() + (id to entry.copy(override = next, pending = true)))
        return next
    }

    /**
     * Installs the overrides Sync holds: [payloads] are the server's live records (a tombstone
     * removes the projection, so a record the server knew of and no longer lists was deleted there).
     * [accepted] and [superseded] are the outbox outcomes of operations sent for pending local edits.
     * A pending local edit is kept until the server holds the same or a later revision.
     */
    @Synchronized
    fun installServer(payloads: List<JsonObject>, accepted: Set<String> = emptySet(), superseded: Set<String> = emptySet()) {
        val server = payloads.mapNotNull(::parse).filterNot { it.deleted }.groupBy { it.id }
            .mapValues { (_, records) -> records.maxWith { a, b -> priority(a, b) } }
        val current = load()
        val next = linkedMapOf<String, Entry>()
        for ((id, entry) in current) {
            val remote = server[id]
            if (remote != null) {
                next[id] = if (entry.pending && priority(entry.override, remote) > 0) entry.copy(serverKnown = true)
                    else Entry(remote, pending = false, serverKnown = true)
                continue
            }
            val operationId = if (entry.pending) uploadOf(entry.override).operationId else null
            val gone = when {
                operationId != null && operationId in superseded -> true // the server keeps a later state: a tombstone
                operationId != null -> operationId in accepted && entry.override.deleted
                else -> entry.serverKnown // known to the server once and no longer listed: deleted there
            }
            if (!gone) next[id] = entry
        }
        for ((id, remote) in server) if (id !in next) next[id] = Entry(remote, pending = false, serverKnown = true)
        // Settled tombstones need not be kept.
        val pruned = next.filterValues { !(it.override.deleted && !it.pending) }
        if (pruned != current) write(pruned)
    }

    private fun load(): Map<String, Entry> {
        val target = runCatching(file).getOrNull() ?: return emptyMap()
        val stamp = if (target.isFile) target.lastModified() xor target.length() else -1L
        cached?.takeIf { it.first == target && it.second == stamp }?.let { return it.third }
        val value = runCatching {
            JsonParser.parseString(target.readText()).asJsonObject.getAsJsonArray("overrides").mapNotNull { element ->
                val saved = element.asJsonObject
                val override = saved.get("record")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::parse)
                    ?: return@mapNotNull null
                override.id to Entry(
                    override,
                    pending = saved.get("pending")?.asBoolean == true,
                    serverKnown = saved.get("server_known")?.asBoolean == true,
                )
            }.toMap(linkedMapOf())
        }.getOrDefault(emptyMap())
        cached = Triple(target, stamp, value)
        return value
    }

    private fun write(entries: Map<String, Entry>) {
        val target = file()
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(JsonObject().apply {
            addProperty("schema_version", 1)
            add("overrides", JsonArray().apply {
                entries.values.forEach { entry ->
                    add(JsonObject().apply {
                        add("record", entry.override.payload)
                        addProperty("pending", entry.pending)
                        addProperty("server_known", entry.serverKnown)
                    })
                }
            })
        }.toString())
        check(temporary.renameTo(target)) { "Could not save the link override." }
        cached = null
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonElement.isNumber(): Boolean = isJsonPrimitive && asJsonPrimitive.isNumber
}
