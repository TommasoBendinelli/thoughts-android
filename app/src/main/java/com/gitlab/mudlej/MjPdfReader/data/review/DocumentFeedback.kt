// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Feedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.NewAnchor
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Upload
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Document feedback (thoughts-server protocol/document-feedback.md): a passage of a Library PDF
 * marked liked or disliked while reading, with an optional comment. It is review card feedback
 * ([ReviewCardFeedback]) without a card: each highlight is a `document_feedback` record
 * (collection `document-feedback`, entity id = `feedback_id`) that belongs to the document
 * (`anchor.pdf.document_id`) and to the exact PDF bytes that were displayed
 * (`anchor.pdf.pdf_sha256`). Same priority, sticky delete and Undo as card feedback; changes wait
 * for a server that advertises [CAPABILITY] and are created and shown locally before that.
 *
 * The record shape, priority and timestamps are shared with [ReviewCardFeedback] (a record is a
 * [ReviewCardFeedback.Feedback] whose `cardId` is empty); only the rules that differ live here.
 *
 * A drawing (spec §10) and a text box (§11) are made on the Mac: this phone stores and shows them
 * and never writes one.
 */
object DocumentFeedback {
    const val ENTITY_TYPE = "document_feedback"
    const val COLLECTION = "document-feedback"
    const val CAPABILITY = "document_feedback_v1"
    /** Records with a `kind` wait for it: an older server refuses the whole batch (spec §9). */
    const val KINDS_CAPABILITY = "document_feedback_kinds_v1"
    /** Fixed by the first write (spec §3). */
    private val IMMUTABLE_FIELDS = listOf("anchor", "created_at", "created_origin_id", "view")
    private const val MAX_REGIONS = 200
    private const val MAX_STROKES = 200
    private const val MAX_STROKE_POINTS = 5000
    /** A text box's font height, as a share of the page's height (spec §11). */
    private const val MIN_FONT_SIZE = 0.004
    private const val MAX_FONT_SIZE = 0.2

    // ---- Pure rules -------------------------------------------------------------------------

    /**
     * Reads a record from the server or from storage; one that breaks the record rules is refused:
     * a `card_id` (that is card feedback), no `anchor.pdf` or no region, a view other than PDF, a
     * drawing without valid strokes, or a text box without a valid place, without text or resolved.
     */
    fun parse(payload: JsonObject): Feedback? = runCatching {
        require(!payload.has("card_id"))
        val feedback = Feedback(payload.deepCopy())
        require(ReviewCardFeedback.validUuid4(feedback.id))
        val polarity = Polarity.of(payload.get("polarity")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
        require(polarity != null && (polarity == Polarity.NEUTRAL) == (feedback.kind != null))
        require(!feedback.isDrawing || feedback.strokes.isNotEmpty())
        // A text box has its place and its text, and only a text box has a place (spec §11).
        require(feedback.isTextBox == (payload.get("text_box")?.isJsonNull == false))
        require(!feedback.isTextBox || (feedback.textBox != null && feedback.comment.isNotBlank() && feedback.resolvedAt == null))
        require(payload.get("view")?.let { it.isJsonPrimitive && it.asString == ReviewCardFeedback.VIEW_PDF } ?: true)
        require(feedback.exact.isNotBlank())
        require(feedback.pdfDocumentId.isNotBlank() && feedback.pdfSha256.isNotBlank())
        require(feedback.regions.isNotEmpty() && feedback.regions.size <= MAX_REGIONS)
        require(feedback.createdAt.isNotBlank() && feedback.updatedAt.isNotBlank())
        feedback
    }.getOrNull()

    /** Whether [incoming] replaces [stored] on the server (card feedback's priority, spec §3). */
    fun wins(incoming: Feedback, stored: Feedback?): Boolean = ReviewCardFeedback.wins(incoming, stored)

    /** The record in effect after [incoming] is applied to [stored], with the fields the first write fixed. */
    fun merge(stored: Feedback?, incoming: Feedback): Feedback {
        if (stored == null) return incoming
        if (!wins(incoming, stored)) return stored
        val payload = incoming.payload.deepCopy()
        IMMUTABLE_FIELDS.forEach { key ->
            val fixed = stored.payload.get(key)
            if (fixed != null) payload.add(key, fixed.deepCopy()) else payload.remove(key)
        }
        return Feedback(payload)
    }

    /**
     * The strokes of a drawing's payload (spec §10), each flattened to `[x0, y0, x1, y1, …]`; empty
     * when the payload has none or one breaks the rules, as the server would refuse it.
     */
    internal fun strokesOf(payload: JsonObject): List<FloatArray> = runCatching {
        val strokes = payload.getAsJsonObject("drawing").getAsJsonArray("strokes")
        require(strokes.size() in 1..MAX_STROKES)
        strokes.map { stroke ->
            val points = stroke.asJsonObject.getAsJsonArray("points")
            require(points.size() in 1..MAX_STROKE_POINTS)
            val flat = FloatArray(points.size() * 2)
            points.forEachIndexed { index, point ->
                val pair = point.asJsonArray
                require(pair.size() == 2)
                val x = pair[0].asDouble
                val y = pair[1].asDouble
                require(x in 0.0..1.0 && y in 0.0..1.0)
                flat[index * 2] = x.toFloat()
                flat[index * 2 + 1] = y.toFloat()
            }
            flat
        }
    }.getOrDefault(emptyList())

    /**
     * Where a text box is on its page (spec §11): its rect `[left, top, right, bottom]` in normalized
     * top-left page coordinates, where the text starts at the top left and wraps at the width, and the
     * height of its font as a share of the page's height.
     */
    data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val fontSize: Float)

    /** The text box of a payload; null when it has none or it breaks the rules, as the server would refuse it. */
    internal fun textBoxOf(payload: JsonObject): TextBox? = runCatching {
        val box = payload.getAsJsonObject("text_box")
        val rect = box.getAsJsonArray("rect")
        require(rect.size() == 4 && rect.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber })
        val (left, top, right, bottom) = rect.map { it.asDouble }
        require(0.0 <= left && left < right && right <= 1.0 && 0.0 <= top && top < bottom && bottom <= 1.0)
        val fontSize = box.get("font_size")
        require(fontSize.isJsonPrimitive && fontSize.asJsonPrimitive.isNumber && fontSize.asDouble in MIN_FONT_SIZE..MAX_FONT_SIZE)
        TextBox(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), fontSize.asFloat)
    }.getOrNull()

    /**
     * Whether [record] shows on the PDF with SHA-256 [displayedSha256] (spec §9, §10, §11): a like, a
     * dislike, a question, a drawing or a text box on the version it was made on (a question only
     * until it is resolved); a comment (kind other) follows the text on every version until resolved.
     */
    fun shownOn(record: Feedback, displayedSha256: String): Boolean = when (record.kind) {
        ReviewCardFeedback.Kind.OTHER -> record.resolvedAt == null
        ReviewCardFeedback.Kind.QUESTION -> record.resolvedAt == null && record.pdfSha256.equals(displayedSha256, ignoreCase = true)
        else -> record.pdfSha256.equals(displayedSha256, ignoreCase = true)
    }

    /**
     * Whether [record] is one of the "N from earlier versions": hidden on [displayedSha256] and drawn
     * once the reader asks. Not a drawing or a text box, which is never drawn on another version
     * (spec §10, §11), and this phone has no list to offer it in.
     */
    fun offeredAsEarlier(record: Feedback, displayedSha256: String): Boolean =
        !record.isPageMark && !shownOn(record, displayedSha256)

    /** Why [anchor] would be refused by the server, or null when it is valid. */
    fun anchorProblem(anchor: NewAnchor): String? = when {
        anchor.htmlEditionSha256 != null -> "Feedback on the HTML view is not supported."
        anchor.regions.isEmpty() || anchor.regions.size > MAX_REGIONS -> "The selection has no usable area."
        else -> ReviewCardFeedback.anchorProblem(anchor)
    }

    internal fun uploadOf(feedback: Feedback): Upload = ReviewCardFeedback.uploadOf(feedback, ENTITY_TYPE)

    /** A document's records in effect, oldest first; deleted ones are left out. */
    fun visible(records: Collection<Feedback>, documentId: String): List<Feedback> =
        records.filter { !it.deleted && it.pdfDocumentId.equals(documentId, ignoreCase = true) }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))

    // ---- Local record -----------------------------------------------------------------------

    /** [feedback] is the record in effect; [pending] while a local write has not reached the server. */
    private data class Entry(val feedback: Feedback, val pending: Boolean, val serverKnown: Boolean)

    /** Overridable for tests. */
    @Volatile var file: () -> File = { ThoughtsLocalStore.documentFeedback() }
    @Volatile private var cached: Triple<File, Long, Map<String, Entry>>? = null

    /** Every record in effect, tombstones included. */
    fun all(): List<Feedback> = load().values.map { it.feedback }

    fun find(id: String): Feedback? = load()[id]?.feedback

    /** The document's non-deleted feedback, oldest first. */
    fun forDocument(documentId: String): List<Feedback> = visible(all(), documentId)

    /** Local writes still to be sent. */
    fun pendingUploads(): List<Upload> = load().values.filter { it.pending }.map { uploadOf(it.feedback) }

    @Synchronized
    fun create(
        polarity: Polarity,
        comment: String,
        anchor: NewAnchor,
        originId: String,
        now: Instant = Instant.now(),
        /** The version of the PDF the passage was marked on; left out when unknown. */
        version: ReviewVersionStamp? = null,
        /** A question or another comment: [polarity] is then neutral. */
        kind: Kind? = null,
    ): Feedback {
        anchorProblem(anchor)?.let { throw IllegalArgumentException(it) }
        require((polarity == Polarity.NEUTRAL) == (kind != null)) { "A kind goes with a neutral polarity." }
        require(kind != Kind.DRAWING && kind != Kind.TEXT) { "Drawings and text boxes are made on the Mac." }
        val id = UUID.randomUUID().toString()
        val time = ReviewCardFeedback.timestamp(now.truncatedTo(ChronoUnit.MILLIS))
        val payload = ReviewCardFeedback.newPayload(id, null, polarity, ReviewCardFeedback.cleanComment(comment), anchor, time, originId)
        kind?.let { payload.addProperty("kind", it.wire) }
        val feedback = Feedback(ReviewVersionStamp.put(payload, version))
        write(load() + (id to Entry(feedback, pending = true, serverKnown = false)))
        return feedback
    }

    /** Saves a new polarity, kind and comment; null when nothing changed (no new revision is written). */
    @Synchronized
    fun edit(
        id: String,
        polarity: Polarity,
        comment: String,
        originId: String,
        now: Instant = Instant.now(),
        version: ReviewVersionStamp? = null,
        kind: Kind? = null,
    ): Feedback? {
        require((polarity == Polarity.NEUTRAL) == (kind != null)) { "A kind goes with a neutral polarity." }
        val entry = requireNotNull(load()[id]) { "No such feedback" }
        require(!entry.feedback.deleted) { "This feedback was deleted." }
        require(!entry.feedback.isPageMark && kind != Kind.DRAWING && kind != Kind.TEXT) {
            "Drawings and text boxes are changed on the Mac."
        }
        val text = ReviewCardFeedback.cleanComment(comment)
        if (entry.feedback.polarity == polarity && entry.feedback.kind == kind && entry.feedback.comment == text) return null
        val next = ReviewCardFeedback.nextRevision(entry.feedback, now, originId) {
            addProperty("polarity", polarity.wire)
            addProperty("comment", text)
            if (kind != null) addProperty("kind", kind.wire)
            else {
                remove("kind")
                remove("resolved_at") // Only a question or another comment is resolved.
            }
            ReviewVersionStamp.put(this, version)
        }
        write(load() + (id to entry.copy(feedback = next, pending = true)))
        return next
    }

    /** Closes a question or another comment, or opens it again; null when nothing changes. */
    @Synchronized
    fun resolve(id: String, resolved: Boolean, originId: String, now: Instant = Instant.now()): Feedback? {
        val entry = requireNotNull(load()[id]) { "No such feedback" }
        require(entry.feedback.kind != null && !entry.feedback.isPageMark) { "Only a question or another comment is resolved." }
        if ((entry.feedback.resolvedAt != null) == resolved) return null
        val next = ReviewCardFeedback.nextRevision(entry.feedback, now, originId) {
            if (resolved) addProperty("resolved_at", ReviewCardFeedback.timestamp(now.truncatedTo(ChronoUnit.MILLIS)))
            else remove("resolved_at")
        }
        write(load() + (id to entry.copy(feedback = next, pending = true)))
        return next
    }

    /** Deletes a highlight everywhere: the tombstone carries the whole record and syncs. */
    @Synchronized
    fun remove(id: String, originId: String, now: Instant = Instant.now()): Feedback? {
        val entry = load()[id] ?: return null
        if (entry.feedback.deleted) return entry.feedback
        val next = ReviewCardFeedback.nextRevision(entry.feedback, now, originId) {
            addProperty("deleted", true)
            addProperty("deleted_at", ReviewCardFeedback.timestamp(now.truncatedTo(ChronoUnit.MILLIS)))
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
        val current = entry?.feedback?.takeIf { ReviewCardFeedback.priority(it, tombstone) >= 0 } ?: tombstone
        if (!current.deleted) return current
        val next = ReviewCardFeedback.nextRevision(current, now, originId) {
            addProperty("deleted", false)
            remove("deleted_at")
        }
        write(entries + (tombstone.id to Entry(next, pending = true, serverKnown = entry?.serverKnown ?: true)))
        return next
    }

    /**
     * Moves highlight [id] to another passage. The anchor is fixed by the first write (spec §3), so
     * a new record takes [anchor] with [polarity], [kind] and [comment] (and the old one's resolved
     * state), and the old one is deleted, in one write: both sync like any create and delete.
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
        kind: Kind? = null,
    ): ReviewCardFeedback.Replacement {
        anchorProblem(anchor)?.let { throw IllegalArgumentException(it) }
        require((polarity == Polarity.NEUTRAL) == (kind != null)) { "A kind goes with a neutral polarity." }
        val entries = load()
        val entry = requireNotNull(entries[id]) { "No such feedback" }
        require(!entry.feedback.deleted) { "This feedback was deleted." }
        require(!entry.feedback.isPageMark && kind != Kind.DRAWING && kind != Kind.TEXT) { "Drawings and text boxes are changed on the Mac." }
        val newId = UUID.randomUUID().toString()
        val time = ReviewCardFeedback.timestamp(now.truncatedTo(ChronoUnit.MILLIS))
        val payload = ReviewCardFeedback.newPayload(newId, null, polarity, ReviewCardFeedback.cleanComment(comment), anchor, time, originId)
        kind?.let { payload.addProperty("kind", it.wire) }
        if (kind != null) entry.feedback.payload.get("resolved_at")?.let { payload.add("resolved_at", it.deepCopy()) }
        val created = Feedback(ReviewVersionStamp.put(payload, version))
        val tombstone = ReviewCardFeedback.nextRevision(entry.feedback, now, originId) {
            addProperty("deleted", true)
            addProperty("deleted_at", time)
        }
        write(entries + (id to entry.copy(feedback = tombstone, pending = true)) +
            (newId to Entry(created, pending = true, serverKnown = false)))
        return ReviewCardFeedback.Replacement(created, tombstone)
    }

    /** Undo of [replacePassage]: the new record is deleted and the old one comes back, in one write. */
    @Synchronized
    fun undoReplacement(replacement: ReviewCardFeedback.Replacement, originId: String, now: Instant = Instant.now()): Feedback {
        val entries = load()
        val createdEntry = entries[replacement.created.id]
        val removed = createdEntry?.feedback?.takeUnless { it.deleted }?.let { current ->
            replacement.created.id to createdEntry.copy(feedback = ReviewCardFeedback.nextRevision(current, now, originId) {
                addProperty("deleted", true)
                addProperty("deleted_at", ReviewCardFeedback.timestamp(now.truncatedTo(ChronoUnit.MILLIS)))
            }, pending = true)
        }
        val tombstone = replacement.tombstone
        val oldEntry = entries[tombstone.id]
        val current = oldEntry?.feedback?.takeIf { ReviewCardFeedback.priority(it, tombstone) >= 0 } ?: tombstone
        val restored = if (!current.deleted) null else tombstone.id to Entry(ReviewCardFeedback.nextRevision(current, now, originId) {
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
     * writes. A pending local write stays until the server holds the same or a winning record, so
     * records made before the server supports document feedback are kept.
     */
    @Synchronized
    fun installServer(payloads: List<JsonObject>, accepted: Set<String> = emptySet(), superseded: Set<String> = emptySet()) {
        val server = payloads.mapNotNull(::parse).filterNot { it.deleted }.groupBy { it.id }
            .mapValues { (_, records) -> records.maxWith { a, b -> ReviewCardFeedback.priority(a, b) } }
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
                // Accepted but no longer live: a write that ranks higher replaced it on the server.
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
}
