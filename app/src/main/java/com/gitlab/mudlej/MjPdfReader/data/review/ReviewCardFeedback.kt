// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

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

/**
 * Review card feedback (thoughts-server protocol/review-card-feedback.md): a passage of a card's
 * answer marked liked or disliked during a review, with an optional comment. Each highlight is a
 * `review_card_feedback` record (collection `review-feedback`, entity id = `feedback_id`) synced
 * like PDF link overrides: last write wins on (revision, updated_at, origin_id), a delete is a
 * sticky tombstone, and changes wait for a server that advertises [CAPABILITY].
 *
 * The anchor, card, creation time and creating origin are fixed by the first write; later writes
 * change only the polarity, the comment and deletion. Payloads are kept whole so fields this
 * client does not know survive edits. Creates and edits carry the top-level `review_version`
 * stamp ([ReviewVersionStamp]); deletes and Undo keep whatever stamp the record had.
 */
object ReviewCardFeedback {
    const val ENTITY_TYPE = "review_card_feedback"
    const val COLLECTION = "review-feedback"
    const val CAPABILITY = "review_card_feedback_v1"
    const val MAX_COMMENT_LENGTH = 4000
    const val MAX_QUOTE_LENGTH = 4000
    /** Characters of answer text kept before and after the quote (the server accepts up to 200). */
    const val CONTEXT_LENGTH = 32
    const val VIEW_PDF = "pdf"
    const val VIEW_HTML = "html"
    private val IMMUTABLE_FIELDS = listOf("card_id", "anchor", "created_at", "created_origin_id")

    enum class Polarity(val wire: String) {
        POSITIVE("positive"), NEGATIVE("negative"),
        /** A question or another comment on a document (document-feedback.md §9); never card feedback. */
        NEUTRAL("neutral");

        companion object {
            fun of(value: String): Polarity? = entries.firstOrNull { it.wire == value }
        }
    }

    /** What a neutral record is (document-feedback.md §9, §10, §11). */
    enum class Kind(val wire: String) {
        QUESTION("question"), OTHER("other"),
        /** Pencil strokes on a page; this phone only shows them. */
        DRAWING("drawing"),
        /** Text typed on a page; this phone only shows it. */
        TEXT("text");

        companion object {
            fun of(value: String): Kind? = entries.firstOrNull { it.wire == value }
        }
    }

    /** One rectangle of the anchor: 1-based [page], normalized top-left `[left, top, right, bottom]`. */
    data class Region(val page: Int, val left: Double, val top: Double, val right: Double, val bottom: Double)

    /** What creation records about the passage (spec §3). */
    data class NewAnchor(
        val exact: String,
        val prefix: String,
        val suffix: String,
        val documentId: String,
        val pdfSha256: String,
        val regions: List<Region>,
        val segmentId: String? = null,
        val segmentContentSha256: String? = null,
        /** The HTML edition the passage was selected in (view "html"); its regions may be empty. */
        val htmlEditionSha256: String? = null,
    ) {
        val view: String get() = if (htmlEditionSha256 != null) VIEW_HTML else VIEW_PDF
    }

    class Feedback internal constructor(val payload: JsonObject) {
        val id: String get() = payload.string("feedback_id")
        val cardId: String get() = payload.string("card_id")
        val polarity: Polarity get() = Polarity.of(payload.string("polarity")) ?: Polarity.POSITIVE
        val comment: String get() = payload.string("comment")
        val kind: Kind? get() = Kind.of(payload.string("kind"))
        val isDrawing: Boolean get() = kind == Kind.DRAWING
        val isTextBox: Boolean get() = kind == Kind.TEXT
        /**
         * A drawing or a text box (document-feedback.md §10, §11): made over a page of one PDF, whose
         * region only names the page. Shown on that PDF alone, never relocated, never resolved.
         */
        val isPageMark: Boolean get() = isDrawing || isTextBox
        /** A drawing's strokes (document-feedback.md §10), each `[x0, y0, x1, y1, …]`; empty if it has none. */
        val strokes: List<FloatArray> by lazy { DocumentFeedback.strokesOf(payload) }
        /** Where a text box's text is on its page (document-feedback.md §11); null if it has no valid one. */
        val textBox: DocumentFeedback.TextBox? by lazy { DocumentFeedback.textBoxOf(payload) }
        val resolvedAt: String? get() = payload.string("resolved_at").takeIf(String::isNotEmpty)
        val revision: Long get() = payload.get("revision")?.takeIf { it.isNumber() }?.asLong ?: 1L
        val createdAt: String get() = payload.string("created_at")
        val updatedAt: String get() = payload.string("updated_at")
        val createdOriginId: String get() = payload.string("created_origin_id")
        val originId: String get() = payload.string("origin_id")
        val deleted: Boolean get() = payload.get("deleted")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        private val anchor: JsonObject? get() = payload.obj("anchor")
        private val quote: JsonObject? get() = anchor?.obj("quote")
        private val pdf: JsonObject? get() = anchor?.obj("pdf")
        val exact: String get() = quote?.string("exact").orEmpty()
        val prefix: String get() = quote?.string("prefix").orEmpty()
        val suffix: String get() = quote?.string("suffix").orEmpty()
        val pdfDocumentId: String get() = pdf?.string("document_id").orEmpty()
        val pdfSha256: String get() = pdf?.string("pdf_sha256").orEmpty()
        val segmentId: String? get() = anchor?.string("segment_id")?.takeIf(String::isNotEmpty)
        val segmentContentSha256: String? get() = anchor?.string("segment_content_sha256")?.takeIf(String::isNotEmpty)
        /** Where the passage was selected: "pdf" (default) or "html". */
        val view: String get() = payload.string("view").ifEmpty { VIEW_PDF }
        val htmlEditionSha256: String? get() = payload.string("html_edition_sha256").takeIf(String::isNotEmpty)
        val regions: List<Region> get() = pdf?.get("regions")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { value ->
            val region = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val page = region.get("page")?.takeIf { it.isNumber() }?.asInt ?: return@mapNotNull null
            val rect = region.get("rect")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.takeIf { it.size() == 4 && it.all { number -> number.isNumber() } } ?: return@mapNotNull null
            Region(page, rect[0].asDouble, rect[1].asDouble, rect[2].asDouble, rect[3].asDouble)
        }.orEmpty()

        override fun equals(other: Any?): Boolean = other is Feedback && other.payload == payload
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

    /** Reads a record from the server or from storage; one that breaks the record rules is refused. */
    fun parse(payload: JsonObject): Feedback? = runCatching {
        val feedback = Feedback(payload.deepCopy())
        require(validUuid4(feedback.id) && feedback.cardId.isNotBlank() && feedback.cardId.length <= 128)
        require(Polarity.of(payload.string("polarity")).let { it != null && it != Polarity.NEUTRAL })
        // Feedback given in the HTML view is anchored by its quote alone.
        require(feedback.exact.isNotBlank() && (feedback.regions.isNotEmpty() || feedback.view == VIEW_HTML))
        require(feedback.createdAt.isNotBlank() && feedback.updatedAt.isNotBlank())
        feedback
    }.getOrNull()

    /**
     * Server write priority (spec §5): revision, then deleted (a delete ranks above a non-delete),
     * then `updated_at` and origin id as the server stores them. At equal revision a delete
     * therefore beats an edit whatever their times or arrival order, and an edit revives a deleted
     * record only with a strictly higher revision (Undo = tombstone revision + 1).
     */
    internal fun priority(a: Feedback, b: Feedback): Int =
        compareValuesBy(a, b, { it.revision }, { it.deleted }, { it.updatedAt }, { it.originId })

    /** Whether [incoming] replaces [stored] on the server. */
    fun wins(incoming: Feedback, stored: Feedback?): Boolean = stored == null || priority(incoming, stored) > 0

    /** The record in effect after [incoming] is applied to [stored], with the fields the first write fixed. */
    fun merge(stored: Feedback?, incoming: Feedback): Feedback {
        if (stored == null) return incoming
        if (!wins(incoming, stored)) return stored
        val payload = incoming.payload.deepCopy()
        IMMUTABLE_FIELDS.forEach { key -> stored.payload.get(key)?.let { payload.add(key, it.deepCopy()) } }
        return Feedback(payload)
    }

    /** A first write; [cardId] is left out for document feedback ([DocumentFeedback]). */
    internal fun newPayload(
        id: String,
        cardId: String?,
        polarity: Polarity,
        comment: String,
        anchor: NewAnchor,
        timestamp: String,
        originId: String,
    ): JsonObject = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("feedback_id", id)
        cardId?.let { addProperty("card_id", it) }
        addProperty("polarity", polarity.wire)
        addProperty("comment", comment)
        add("anchor", JsonObject().apply {
            addProperty("version", 1)
            add("quote", JsonObject().apply {
                addProperty("exact", anchor.exact)
                addProperty("prefix", anchor.prefix)
                addProperty("suffix", anchor.suffix)
            })
            add("pdf", JsonObject().apply {
                addProperty("document_id", anchor.documentId)
                addProperty("pdf_sha256", anchor.pdfSha256)
                add("regions", JsonArray().apply {
                    anchor.regions.forEach { region ->
                        add(JsonObject().apply {
                            addProperty("page", region.page)
                            add("rect", JsonArray().apply {
                                listOf(region.left, region.top, region.right, region.bottom).forEach { add(round(it)) }
                            })
                        })
                    }
                })
            })
            anchor.segmentId?.let { addProperty("segment_id", it) }
            anchor.segmentContentSha256?.let { addProperty("segment_content_sha256", it) }
        })
        anchor.htmlEditionSha256?.let {
            addProperty("view", VIEW_HTML)
            addProperty("html_edition_sha256", it)
        }
        addProperty("revision", 1)
        addProperty("created_at", timestamp)
        addProperty("updated_at", timestamp)
        addProperty("created_origin_id", originId)
        addProperty("origin_id", originId)
        addProperty("deleted", false)
    }

    /** Six decimals keep a rectangle exact to well under a PDF point. */
    private fun round(value: Double): Double = Math.round(value.coerceIn(0.0, 1.0) * 1_000_000.0) / 1_000_000.0

    /** Why [anchor] would be refused by the server, or null when it is valid. */
    fun anchorProblem(anchor: NewAnchor): String? = when {
        anchor.exact.isBlank() -> "The selection has no text."
        anchor.exact.length > MAX_QUOTE_LENGTH -> "The selection is too long."
        !validUuid4(anchor.documentId) -> "The document has no Library identity."
        !anchor.pdfSha256.matches(SHA256) -> "The PDF version is unknown."
        anchor.htmlEditionSha256 != null && !anchor.htmlEditionSha256.matches(SHA256) -> "The HTML edition is unknown."
        (anchor.regions.isEmpty() && anchor.htmlEditionSha256 == null) || anchor.regions.size > 200 -> "The selection has no usable area."
        anchor.regions.any { it.page < 1 || it.left > it.right || it.top > it.bottom ||
            listOf(it.left, it.top, it.right, it.bottom).any { v -> !v.isFinite() || v < 0.0 || v > 1.0 } } ->
            "The selection has no usable area."
        anchor.prefix.length > 200 || anchor.suffix.length > 200 -> "The context is too long."
        anchor.segmentId != null && anchor.segmentId.length !in 1..64 -> "The source segment is invalid."
        anchor.segmentContentSha256 != null && !anchor.segmentContentSha256.matches(SHA256) -> "The source segment is invalid."
        else -> null
    }

    /** The record [previous] becomes after a local write: next revision, later `updated_at`, this origin. */
    internal fun nextRevision(previous: Feedback, now: Instant, originId: String, edit: JsonObject.() -> Unit): Feedback {
        val payload = previous.payload.deepCopy()
        payload.edit()
        payload.addProperty("revision", previous.revision + 1)
        payload.addProperty("updated_at", timestamp(laterThan(previous.updatedAt, now)))
        payload.addProperty("origin_id", originId)
        return Feedback(payload)
    }

    /** The operation for [feedback]'s current revision; its id is derived from [entityType] and the payload. */
    internal fun uploadOf(feedback: Feedback, entityType: String = ENTITY_TYPE): Upload {
        val payload = feedback.payload.deepCopy()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val operationId = UUID.nameUUIDFromBytes(
            listOf(entityType, feedback.id, feedback.revision.toString(), digest).joinToString("\u0000")
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()
        return Upload(operationId, feedback.id, (feedback.revision - 1).takeIf { it > 0 }, feedback.updatedAt, payload)
    }

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun timestamp(instant: Instant): String = TIMESTAMP.format(instant)

    /** Always after [previous], even if the clock went back. */
    private fun laterThan(previous: String, now: Instant): Instant {
        val last = runCatching { Instant.parse(previous) }.getOrNull() ?: return now
        val truncated = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        return if (truncated.isAfter(last)) truncated else last.plusMillis(1)
    }

    fun validUuid4(id: String): Boolean = id.length == 36 && id == id.lowercase() &&
        runCatching { UUID.fromString(id).version() == 4 }.getOrDefault(false)

    /** The comment as saved: trimmed at both ends, at most [MAX_COMMENT_LENGTH] characters. */
    fun cleanComment(comment: String): String = comment.trim().take(MAX_COMMENT_LENGTH)

    /** A card's records in effect, oldest first; deleted ones are left out. */
    fun visible(records: Collection<Feedback>, cardId: String): List<Feedback> =
        records.filter { it.cardId == cardId && !it.deleted }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    // ---- Local record -----------------------------------------------------------------------

    /** [feedback] is the record in effect; [pending] while a local write has not reached the server. */
    private data class Entry(val feedback: Feedback, val pending: Boolean, val serverKnown: Boolean)

    /** Overridable for tests. */
    @Volatile var file: () -> File = { ThoughtsLocalStore.reviewCardFeedback() }
    @Volatile private var cached: Triple<File, Long, Map<String, Entry>>? = null

    /** Every record in effect, tombstones included. */
    fun all(): List<Feedback> = load().values.map { it.feedback }

    fun find(id: String): Feedback? = load()[id]?.feedback

    /** The card's non-deleted feedback, oldest first. */
    fun forCard(cardId: String): List<Feedback> = visible(all(), cardId)

    /** Local writes still to be sent. */
    fun pendingUploads(): List<Upload> = load().values.filter { it.pending }.map { uploadOf(it.feedback) }

    @Synchronized
    fun create(
        cardId: String,
        polarity: Polarity,
        comment: String,
        anchor: NewAnchor,
        originId: String,
        now: Instant = Instant.now(),
        /** The version of the card's PDF the passage was marked on; left out when unknown. */
        version: ReviewVersionStamp? = null,
    ): Feedback {
        require(cardId.isNotBlank() && cardId.length <= 128) { "The card has no identity." }
        anchorProblem(anchor)?.let { throw IllegalArgumentException(it) }
        val id = UUID.randomUUID().toString()
        val time = timestamp(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        val payload = newPayload(id, cardId, polarity, cleanComment(comment), anchor, time, originId)
        val feedback = Feedback(ReviewVersionStamp.put(payload, version))
        write(load() + (id to Entry(feedback, pending = true, serverKnown = false)))
        return feedback
    }

    /**
     * Saves a new polarity and comment; null when neither changed (no new revision is written).
     * [version] (the PDF version the edit was made on) replaces the record's stamp; when unknown an
     * older stamp is removed.
     */
    @Synchronized
    fun edit(
        id: String,
        polarity: Polarity,
        comment: String,
        originId: String,
        now: Instant = Instant.now(),
        version: ReviewVersionStamp? = null,
    ): Feedback? {
        val entry = requireNotNull(load()[id]) { "No such feedback" }
        require(!entry.feedback.deleted) { "This feedback was deleted." }
        val text = cleanComment(comment)
        if (entry.feedback.polarity == polarity && entry.feedback.comment == text) return null
        val next = nextRevision(entry.feedback, now, originId) {
            addProperty("polarity", polarity.wire)
            addProperty("comment", text)
            ReviewVersionStamp.put(this, version)
        }
        write(load() + (id to entry.copy(feedback = next, pending = true)))
        return next
    }

    /** Deletes a highlight everywhere: the tombstone carries the whole record and syncs. */
    @Synchronized
    fun remove(id: String, originId: String, now: Instant = Instant.now()): Feedback? {
        val entry = load()[id] ?: return null
        if (entry.feedback.deleted) return entry.feedback
        val next = nextRevision(entry.feedback, now, originId) {
            addProperty("deleted", true)
            addProperty("deleted_at", timestamp(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
        }
        write(load() + (id to entry.copy(feedback = next, pending = true)))
        return next
    }

    /**
     * Undo of [tombstone] (as [remove] returned it): revision + 1, not deleted, no `deleted_at`.
     * Works even if Sync settled and dropped the tombstone meanwhile.
     */
    @Synchronized
    fun undo(tombstone: Feedback, originId: String, now: Instant = Instant.now()): Feedback {
        val entries = load()
        val entry = entries[tombstone.id]
        val current = entry?.feedback?.takeIf { priority(it, tombstone) >= 0 } ?: tombstone
        if (!current.deleted) return current
        val next = nextRevision(current, now, originId) {
            addProperty("deleted", false)
            remove("deleted_at")
        }
        write(entries + (tombstone.id to Entry(next, pending = true, serverKnown = entry?.serverKnown ?: true)))
        return next
    }

    /** A highlight moved to another passage: the new record and the old one's tombstone. */
    data class Replacement(val created: Feedback, val tombstone: Feedback)

    /**
     * Moves highlight [id] to another passage. The anchor is fixed by the first write (spec §2), so
     * a new record on the same card takes [anchor], [polarity] and [comment], and the old one is
     * deleted, in one write: both sync like any create and delete.
     */
    @Synchronized
    fun replacePassage(
        id: String,
        polarity: Polarity,
        comment: String,
        anchor: NewAnchor,
        originId: String,
        now: Instant = Instant.now(),
        version: ReviewVersionStamp? = null,
    ): Replacement {
        val entries = load()
        val entry = requireNotNull(entries[id]) { "No such feedback" }
        require(!entry.feedback.deleted) { "This feedback was deleted." }
        anchorProblem(anchor)?.let { throw IllegalArgumentException(it) }
        val newId = UUID.randomUUID().toString()
        val time = timestamp(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        val created = Feedback(ReviewVersionStamp.put(
            newPayload(newId, entry.feedback.cardId, polarity, cleanComment(comment), anchor, time, originId), version))
        val tombstone = nextRevision(entry.feedback, now, originId) {
            addProperty("deleted", true)
            addProperty("deleted_at", time)
        }
        write(entries + (id to entry.copy(feedback = tombstone, pending = true)) +
            (newId to Entry(created, pending = true, serverKnown = false)))
        return Replacement(created, tombstone)
    }

    /** Undo of [replacePassage]: the new record is deleted and the old one comes back, in one write. */
    @Synchronized
    fun undoReplacement(replacement: Replacement, originId: String, now: Instant = Instant.now()): Feedback {
        val entries = load()
        val createdEntry = entries[replacement.created.id]
        val removed = createdEntry?.feedback?.takeUnless { it.deleted }?.let { current ->
            replacement.created.id to createdEntry.copy(feedback = nextRevision(current, now, originId) {
                addProperty("deleted", true)
                addProperty("deleted_at", timestamp(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
            }, pending = true)
        }
        val tombstone = replacement.tombstone
        val oldEntry = entries[tombstone.id]
        val current = oldEntry?.feedback?.takeIf { priority(it, tombstone) >= 0 } ?: tombstone
        val restored = if (!current.deleted) null else tombstone.id to Entry(nextRevision(current, now, originId) {
            addProperty("deleted", false)
            remove("deleted_at")
        }, pending = true, serverKnown = oldEntry?.serverKnown ?: true)
        write(entries + listOfNotNull(removed, restored))
        return restored?.second?.feedback ?: current
    }

    /**
     * Installs the feedback Sync holds: [payloads] are the server's live records (a tombstone
     * removes the projection, so a record the server knew and no longer lists was deleted there).
     * [accepted] and [superseded] are the outbox outcomes of operations sent for pending local
     * writes. A pending local write stays until the server holds the same or a winning record.
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
                next[id] = if (entry.pending && wins(entry.feedback, remote)) entry.copy(serverKnown = true)
                    else Entry(remote, pending = false, serverKnown = true)
                continue
            }
            val operationId = if (entry.pending) uploadOf(entry.feedback).operationId else null
            val gone = when {
                operationId != null && operationId in superseded -> true // the server keeps a tombstone that wins
                // Accepted but no longer live: a write that ranks higher (a delete of the same
                // revision, spec §5) replaced it on the server.
                operationId != null -> operationId in accepted
                else -> entry.serverKnown // known to the server once and no longer listed: deleted there
            }
            if (!gone) next[id] = entry
        }
        for ((id, remote) in server) if (id !in next) next[id] = Entry(remote, pending = false, serverKnown = true)
        // Settled tombstones need not be kept: the server holds them.
        val pruned = next.filterValues { !(it.feedback.deleted && !it.pending) }
        if (pruned != current) write(pruned)
    }

    private fun load(): Map<String, Entry> {
        val target = runCatching(file).getOrNull() ?: return emptyMap()
        val stamp = if (target.isFile) target.lastModified() xor target.length() else -1L
        cached?.takeIf { it.first == target && it.second == stamp }?.let { return it.third }
        val value = runCatching {
            JsonParser.parseString(target.readText()).asJsonObject.getAsJsonArray("feedback").mapNotNull { element ->
                val saved = element.asJsonObject
                val feedback = saved.get("record")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::parse)
                    ?: return@mapNotNull null
                feedback.id to Entry(
                    feedback,
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
            add("feedback", JsonArray().apply {
                entries.values.forEach { entry ->
                    add(JsonObject().apply {
                        add("record", entry.feedback.payload)
                        addProperty("pending", entry.pending)
                        addProperty("server_known", entry.serverKnown)
                    })
                }
            })
        }.toString())
        check(temporary.renameTo(target)) { "Could not save the feedback." }
        cached = null
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.isNumber(): Boolean = isJsonPrimitive && asJsonPrimitive.isNumber
}
