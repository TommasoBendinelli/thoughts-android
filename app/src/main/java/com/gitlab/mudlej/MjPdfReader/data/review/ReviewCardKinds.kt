// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Knowledge cards and project cards (protocol/review-card-kinds.md). Every card is a knowledge
 * card (today's spaced repetition) unless it, or a Library Project holding it, is a project card:
 * then it comes back at least once a week, at once when its passage changed, and can be retired
 * with its Project's other project cards.
 *
 * A card's kind is a pure function of its review events, its record and the Library catalogue
 * (section 3), so arrival order does not matter. Choosing a card's kind is an immutable
 * `set_review_kind` queue action; a Project's kind is the `review_kind` of its catalogue entry.
 */
internal object ReviewKinds {
    const val SET_REVIEW_KIND = "set_review_kind"
    /** The field of a kind event, of a card record (its kind when made) and of a catalogue entry. */
    const val FIELD = "review_kind"
    const val KNOWLEDGE = "knowledge"
    const val PROJECT = "project"
    const val FOLLOW_PROJECT = "follow_project"
    /** The passage an answer was given on (section 6.3). */
    const val PASSAGE = "passage"
    val KINDS = setOf(KNOWLEDGE, PROJECT)
    val EVENT_KINDS = setOf(KNOWLEDGE, PROJECT, FOLLOW_PROJECT)
    /** At most this long between reviews of a project card (section 6.2). */
    val PROJECT_MAX_INTERVAL: Duration = Duration.ofDays(7)
}

/** Where a card's kind comes from (section 3): its own choice, the Projects that hold it, or neither. */
enum class ReviewKindSource(val wire: String) { CARD("card"), PROJECT("project"), DEFAULT("default") }

/** A card's kind, its source and the catalogue Projects that hold it, in catalogue order. */
data class ReviewCardKind(val kind: String, val source: ReviewKindSource, val projectIds: List<String>) {
    val isProject: Boolean get() = kind == ReviewKinds.PROJECT
}

/**
 * What a card's own records say about its kind and its passage (sections 3 and 6): everything the
 * fold needs besides the catalogue, so it can be worked out once per change of the card's records.
 */
data class ReviewKindFacts(
    /** The card's own kind (`knowledge` or `project`), or null when it follows its Projects. */
    val ownKind: String? = null,
    /** The latest answer's `reviewed_at` (section 6.1), or null for a card never answered. */
    val latestAnswerAt: Instant? = null,
    /** The passage differs from the one the latest answer was given on (section 6.3). */
    val passageChanged: Boolean = false,
    /** The moment of that change: the record's `updated_at` when later than the answer, else the answer's time. */
    val passageChangedAt: Instant? = null,
)

private fun JsonObject.text(key: String): String? = get(key)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

/** An ISO 8601 time with a zone ("…Z" or "…+01:00", any precision), or null. */
internal fun reviewKindTime(value: JsonElement?): Instant? = runCatching {
    value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(String::isNotEmpty)
        ?.let { OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }
}.getOrNull()

/** Copies of one event are one event: the first copy counts. */
private fun uniqueEvents(events: Iterable<JsonObject>): Collection<JsonObject> {
    val unique = LinkedHashMap<String, JsonObject>()
    events.forEach { event -> event.text("event_id")?.takeIf(String::isNotEmpty)?.let { unique.putIfAbsent(it, event) } }
    return unique.values
}

/** The order of review history: by `reviewed_at` as an instant, then by event id. */
private val historyOrder = compareBy<JsonObject>({ reviewKindTime(it["reviewed_at"]) }, { it.text("event_id").orEmpty() })

/** Section 3 step 1: [cardId]'s latest valid kind event, or null. */
internal fun reviewLatestKindEvent(cardId: String, events: Iterable<JsonObject>): JsonObject? =
    uniqueEvents(events).filter { event ->
        event.text("queue_action") == ReviewKinds.SET_REVIEW_KIND && event.text("card_id") == cardId &&
            reviewKindTime(event["reviewed_at"]) != null && event.text(ReviewKinds.FIELD) in ReviewKinds.EVENT_KINDS
    }.maxWithOrNull(historyOrder)

/**
 * Section 3 steps 1 to 4: the card's own kind, from its latest kind event, else from the kind it
 * was made with ([record]'s `review_kind`); null when it follows its Projects (also after
 * `follow_project`, which drops the record's kind too).
 */
internal fun reviewOwnKind(record: JsonObject?, cardId: String, events: Iterable<JsonObject>): String? {
    val event = reviewLatestKindEvent(cardId, events)
    if (event != null) return event.text(ReviewKinds.FIELD)?.takeIf { it in ReviewKinds.KINDS }
    return record?.text(ReviewKinds.FIELD)?.takeIf { it in ReviewKinds.KINDS }
}

/** Section 6.1: [cardId]'s answers that no retraction took back, oldest first. */
internal fun reviewAnswers(cardId: String, events: Iterable<JsonObject>): List<JsonObject> {
    val own = uniqueEvents(events).filter { it.text("card_id") == cardId }
    val retracted = own.mapNotNullTo(hashSetOf()) { it.text("retracted_event_id") }
    return own.filter { event ->
        val rating = event.text("rating")
        !rating.isNullOrEmpty() && rating != "delete" && !event.has("queue_action") &&
            event.text("event_id") !in retracted && reviewKindTime(event["reviewed_at"]) != null
    }.sortedWith(historyOrder)
}

internal fun reviewLatestAnswer(cardId: String, events: Iterable<JsonObject>): JsonObject? = reviewAnswers(cardId, events).lastOrNull()

private fun JsonObject.primary(): JsonObject? = get("primary")?.takeIf { it.isJsonObject }?.asJsonObject

/**
 * Section 6.3: the passage an answer on a card with [sourceAnchor] (its `primary.source_anchor`)
 * is given on: its version-2 segments in order, each as `segment_id` and `content_sha256`. Null
 * when the card has no version-2 segments or a segment lacks either string.
 */
internal fun reviewPassageStamp(sourceAnchor: JsonObject?): JsonObject? {
    val anchor = sourceAnchor ?: return null
    val version = anchor["version"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    if (version != 2.0) return null
    val segments = anchor["segments"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    if (segments.size() == 0) return null
    val stamp = JsonArray()
    for (value in segments) {
        val segment = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = segment.text("segment_id") ?: return null
        val content = segment.text("content_sha256") ?: return null
        stamp.add(JsonObject().apply { addProperty("segment_id", id); addProperty("content_sha256", content) })
    }
    return JsonObject().apply { add("segments", stamp) }
}

/** The passage an answer on the card [record] would carry now (section 6.3). */
internal fun reviewPassageStampOfRecord(record: JsonObject): JsonObject? =
    reviewPassageStamp(record.primary()?.get("source_anchor")?.takeIf { it.isJsonObject }?.asJsonObject)

/** A stamp's segments as a map from segment id to content hash; null when it has none or a bad one. */
private fun segmentMap(stamp: JsonElement?): Map<String, String>? {
    val segments = stamp?.takeIf { it.isJsonObject }?.asJsonObject?.get("segments")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: return null
    if (segments.size() == 0) return null
    val result = LinkedHashMap<String, String>()
    for (value in segments) {
        val segment = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        result[segment.text("segment_id") ?: return null] = segment.text("content_sha256") ?: return null
    }
    return result
}

/**
 * Sections 3 and 6 for one card from its own [record] and [events] (other cards' events are
 * ignored): its own kind and what its passage stamps say.
 */
internal fun reviewKindFacts(record: JsonObject, events: Iterable<JsonObject>): ReviewKindFacts {
    val cardId = record.text("card_id").orEmpty()
    val own = events.filter { it.text("card_id") == cardId }
    val answer = reviewLatestAnswer(cardId, own)
    val answeredAt = answer?.let { reviewKindTime(it["reviewed_at"]) }
    val seen = answer?.let { segmentMap(it[ReviewKinds.PASSAGE]) }
    val current = segmentMap(reviewPassageStampOfRecord(record))
    val changed = seen != null && current != null && seen != current
    val changedAt = if (!changed) null else {
        val updatedAt = reviewKindTime(record["updated_at"])
        if (updatedAt != null && answeredAt != null && updatedAt.isAfter(answeredAt)) updatedAt else answeredAt
    }
    return ReviewKindFacts(reviewOwnKind(record, cardId, own), answeredAt, changed, changedAt)
}

/**
 * Sections 6.2 and 6.3: a project card's due time from [scheduledDueAt], what the knowledge
 * schedule gives (null: not scheduled). A card never answered keeps it.
 */
internal fun reviewProjectDueAt(
    latestAnswerAt: Instant?,
    passageChanged: Boolean,
    passageChangedAt: Instant?,
    scheduledDueAt: Instant?,
): Instant? {
    if (latestAnswerAt == null) return scheduledDueAt
    var due = latestAnswerAt.plus(ReviewKinds.PROJECT_MAX_INTERVAL)
    if (scheduledDueAt != null && scheduledDueAt.isBefore(due)) due = scheduledDueAt
    if (passageChanged && passageChangedAt != null && passageChangedAt.isBefore(due)) due = passageChangedAt
    return due
}

/**
 * The Library catalogue as the kind fold reads it (sections 2.3 and 4): each Project's kind and
 * the documents (published and unpublished), files and combined reading PDF that hold a card.
 */
class ReviewKindCatalogue(projects: JsonArray) {
    data class Project(
        val id: String,
        val name: String,
        /** `project` or `knowledge`; anything else, or no value, is `knowledge`. */
        val kind: String,
        /** The catalogue entry has `review_kind`: the server has `review_card_kinds_v1`. */
        val declaresKind: Boolean,
        internal val documentIds: Set<String>,
        internal val filenames: Set<String>,
        internal val target: String,
    ) {
        val isProject: Boolean get() = kind == ReviewKinds.PROJECT
    }

    val projects: List<Project> = projects.mapNotNull { value ->
        val project = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val documents = listOf("documents", "unpublished_documents").flatMap { key ->
            project[key]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
        }
        val declared = project.text(ReviewKinds.FIELD)
        Project(
            id = project.text("id").orEmpty(),
            name = project.text("name").orEmpty(),
            kind = if (declared == ReviewKinds.PROJECT) ReviewKinds.PROJECT else ReviewKinds.KNOWLEDGE,
            declaresKind = project.has(ReviewKinds.FIELD),
            documentIds = documents.mapNotNullTo(hashSetOf()) { it.text("document_id")?.takeIf(String::isNotEmpty) },
            filenames = documents.mapNotNullTo(hashSetOf()) { it.text("filename")?.takeIf(String::isNotEmpty) },
            target = project.text("target").orEmpty(),
        )
    }

    /** Changes whenever a card's kind or Projects could change, so cached due times are worked out again. */
    val fingerprint: String = this.projects.joinToString("\n") { project ->
        listOf(project.id, project.kind, project.target, project.documentIds.sorted().joinToString(","),
            project.filenames.sorted().joinToString(",")).joinToString("|")
    }

    /** Whether the Project [projectId] can be given a kind here (its catalogue entry has `review_kind`). */
    fun declaresKind(projectId: String): Boolean = projects.any { it.id == projectId && it.declaresKind }

    fun project(projectId: String): Project? = projects.firstOrNull { it.id == projectId }

    /** Section 4: the Projects that hold a card on [documentId] (may be blank) and the PDF file [pdfFilename]. */
    fun holding(documentId: String, pdfFilename: String): List<Project> = projects.filter { project ->
        (documentId.isNotEmpty() && documentId in project.documentIds) ||
            (pdfFilename.isNotEmpty() && (pdfFilename == project.target || pdfFilename in project.filenames))
    }

    /** Section 3 step 5 after steps 1 to 4 gave [ownKind] (null: the card follows its Projects). */
    fun kind(ownKind: String?, documentId: String, pdfFilename: String): ReviewCardKind {
        val held = holding(documentId, pdfFilename)
        val ids = held.map(Project::id)
        if (ownKind in ReviewKinds.KINDS) return ReviewCardKind(ownKind!!, ReviewKindSource.CARD, ids)
        if (held.isEmpty()) return ReviewCardKind(ReviewKinds.KNOWLEDGE, ReviewKindSource.DEFAULT, ids)
        return ReviewCardKind(if (held.any(Project::isProject)) ReviewKinds.PROJECT else ReviewKinds.KNOWLEDGE, ReviewKindSource.PROJECT, ids)
    }

    companion object {
        val EMPTY = ReviewKindCatalogue(JsonArray())
    }
}

/** The card's document ID for section 4: `primary.pdf_document_id` if present, else `primary.document_id`. */
internal fun reviewKindDocumentId(record: JsonObject): String {
    val primary = record.primary()
    return primary?.text("pdf_document_id")?.takeIf(String::isNotEmpty) ?: primary?.text("document_id").orEmpty()
}

/** The card's PDF file for section 4: `primary.pdf_stem` (else the record's) plus ".pdf", or "". */
internal fun reviewKindPdfFilename(record: JsonObject): String =
    (record.primary()?.text("pdf_stem")?.takeIf(String::isNotEmpty) ?: record.text("pdf_stem")?.takeIf(String::isNotEmpty))
        ?.let { "$it.pdf" }.orEmpty()

/** Section 3 for a card record, its events and the catalogue [projects]. */
internal fun reviewCardKind(record: JsonObject, events: Iterable<JsonObject>, projects: JsonArray): ReviewCardKind =
    ReviewKindCatalogue(projects).kind(reviewOwnKind(record, record.text("card_id").orEmpty(), events),
        reviewKindDocumentId(record), reviewKindPdfFilename(record))

/** The card's due time by its kind: knowledge cards keep [scheduledDueAt]; project cards follow section 6. */
internal fun reviewKindDueAt(record: JsonObject, events: Iterable<JsonObject>, projects: JsonArray, scheduledDueAt: Instant?): Instant? {
    val list = events.toList()
    if (!reviewCardKind(record, list, projects).isProject) return scheduledDueAt
    val facts = reviewKindFacts(record, list)
    return reviewProjectDueAt(facts.latestAnswerAt, facts.passageChanged, facts.passageChangedAt, scheduledDueAt)
}

/**
 * A kind event (section 2.1): schema 3, a random event id, the card, the kind (`knowledge`,
 * `project` or `follow_project`), the moment of the choice and this device. No rating, tags,
 * skip, interval or retraction, so no client reads it as an answer, a skip or a retraction.
 */
internal fun reviewKindEvent(cardId: String, kind: String, at: Instant, originId: String): JsonObject {
    require(kind in ReviewKinds.EVENT_KINDS) { "Choose a knowledge card, a project card or the Project's kind" }
    UUID.fromString(cardId)
    return JsonObject().apply {
        addProperty("schema_version", 3)
        addProperty("event_id", UUID.randomUUID().toString())
        addProperty("card_id", cardId.lowercase())
        addProperty("queue_action", ReviewKinds.SET_REVIEW_KIND)
        addProperty(ReviewKinds.FIELD, kind)
        addProperty("reviewed_at", at.toString())
        addProperty("origin_id", originId)
    }
}

/**
 * The card's kind applied with [catalogue] (sections 3, 4 and 6.2–6.3): its kind, source and
 * Projects, and for a project card the capped due time in [ReviewCard.schedule]. Always worked out
 * from the knowledge schedule ([ReviewCard.knowledgeSchedule]), so applying it again, or with
 * another catalogue, is safe: a knowledge card keeps exactly its schedule.
 */
internal fun ReviewCard.withReviewKind(catalogue: ReviewKindCatalogue): ReviewCard {
    val follows = catalogue.kind(null, kindDocumentId, "$pdfStem.pdf")
    val kind = if (ownReviewKind in ReviewKinds.KINDS) ReviewCardKind(ownReviewKind!!, ReviewKindSource.CARD, follows.projectIds) else follows
    val followed = follows.kind.takeIf { follows.projectIds.isNotEmpty() }
    val base = knowledgeSchedule ?: schedule
    val effective = if (kind.isProject) reviewProjectSchedule(base, latestAnswerAt, passageChanged, passageChangedAt) else base
    if (kind.kind == reviewKind && kind.source == reviewKindSource && kind.projectIds == reviewProjectIds &&
        followed == followedKind && base == knowledgeSchedule && effective == schedule) return this
    return copy(reviewKind = kind.kind, reviewKindSource = kind.source, reviewProjectIds = kind.projectIds,
        followedKind = followed, knowledgeSchedule = base, schedule = effective)
}

/**
 * A project card's schedule: the knowledge one with its due time capped (section 6.2) and brought
 * forward to a change of its passage (6.3). "Never" (retained) is not scheduled, so the card comes
 * back a week after that answer.
 */
internal fun reviewProjectSchedule(
    base: ReviewSchedule?,
    latestAnswerAt: Instant?,
    passageChanged: Boolean,
    passageChangedAt: Instant?,
): ReviewSchedule? {
    // Without a schedule the card is new, due now (also one answered only with a rating this phone
    // does not schedule: earlier than the week the protocol allows, never later).
    if (base == null || base.deleted) return base
    val due = reviewProjectDueAt(latestAnswerAt, passageChanged, passageChangedAt, if (base.retained) null else base.dueAt)
        ?: return base
    if (!base.retained && due == base.dueAt) return base
    return base.copy(dueAt = due, retained = false)
}
