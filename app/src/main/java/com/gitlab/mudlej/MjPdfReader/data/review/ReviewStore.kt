// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.sync.unpublishedDocumentIds
import android.content.Context
import android.graphics.RectF
import android.os.Environment
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.gitlab.mudlej.MjPdfReader.data.source.LocalReviewSources
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.SourceOwner
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.gitlab.mudlej.MjPdfReader.data.sync.REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED_MESSAGE
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ReviewRating(val wireName: String) {
    HARD("hard"),
    GOOD("good"),
    EASY("easy"),
    AGAIN("again"),
    DELETE("delete"),
    RETAINED("retained"),
    NEXT_WEEK("next_week"),
    NEXT_DAY("next_day"),
    TEN_MINUTES("ten_minutes"),
    CUSTOM_INTERVAL("custom_interval"),
}

data class ReviewSchedule(
    val dueAt: Instant,
    val intervalDays: Int,
    val deleted: Boolean = false,
    val learningStep: Int? = null,
    val relearning: Boolean = false,
    val previousIntervalDays: Int = 0,
    val retained: Boolean = false,
    val fixedInterval: Boolean = false,
)

internal fun inferredSourceDeletion(event: JsonObject): Boolean {
    if (event.string("rating") != "delete") return false
    if (event.string("origin_id") == "thoughts-source-review" &&
        event.string("reason") == "source_markers_removed") return true
    val evidence = runCatching { event.getAsJsonObject("document_deletion") }.getOrNull()
    return event.string("origin_id") == "thoughts-document-deletion" &&
        event.string("reason") == "document_deleted" &&
        (evidence?.getAsJsonArray("source_files")?.size() ?: 0) > 0 &&
        (evidence?.getAsJsonArray("filenames")?.size() ?: 0) == 0 &&
        evidence?.get("project_document_removed")?.asBoolean != true
}

private fun applyIntervalSelection(event: JsonObject, previous: ReviewSchedule?, selectedAt: Instant): ReviewSchedule? {
    if (event.string("interval_action") != "set") return null
    val unit = event.string("interval_unit")
    val never = unit == "never"
    if (!never && unit !in setOf("minutes", "hours", "days", "weeks", "months", "date")) return null
    if (!never && unit != "date" && ReviewInterval.parseAmount(event.string("interval_amount")) == null) return null
    val due = if (never) selectedAt else event.instant("scheduled_due_at")?.takeIf { it.isAfter(selectedAt) } ?: return null
    val days = if (never) 0 else ChronoUnit.DAYS.between(selectedAt, due).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return (previous ?: ReviewSchedule(due, days)).copy(
        dueAt = due,
        intervalDays = days,
        retained = never,
        fixedInterval = true,
        learningStep = null,
        relearning = false,
    )
}

data class ReviewAnswerRegion(val page: Int, val rect: RectF)

data class ReviewCard(
    val id: String,
    val status: String,
    val pdfStem: String,
    val pdfSha256: String,
    val page: Int,
    val pageVisualSha256: String,
    val question: String,
    val rect: RectF,
    val schedule: ReviewSchedule?,
    val reanchorState: String,
    val revision: Int? = null,
    val originId: String = "",
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val approvedAt: Instant? = null,
    val initialDueAt: Instant? = null,
    val sourceFiles: List<String> = emptyList(),
    val reanchorReason: String = "",
    val note: String = "",
    val skippedUntil: Instant? = null,
    val answerRegions: List<ReviewAnswerRegion> = emptyList(),
    val sourceAnchor: JsonObject? = null,
    val generationMethod: String = "",
    val basicPassageKey: String = "",
    val sourceKind: String = "",
    val sourceLocationState: String = "",
    val markerRemovalPending: Boolean = false,
    val sourceLinkState: String = "",
    val sourceLinkReason: String = "",
    val documentId: String = "",
    val pdfDocumentId: String = "",
    // The answer is the whole PDF: every page is hidden, and no source markers exist.
    val wholeDocument: Boolean = false,
    /** Why Fedora could not place or link the card (reanchor error_code), e.g. review_card_place_again. */
    val reanchorErrorCode: String = "",
    /** Fedora's explanation for [reanchorErrorCode], shown to the person. */
    val reanchorMessage: String = "",
    /**
     * A card made in the HTML view: `{created_in, html_edition_sha256, text_quote, source_range?}`
     * of its primary. Until Fedora links it (adding source markers) its page and rectangle are
     * placeholders and the HTML view finds it by its quote.
     */
    val htmlSelection: JsonObject? = null,
    /** When the retire in effect was made ([reviewRetiredCards]); null for an active or deleted card. */
    val retiredAt: Instant? = null,
    /** The event id of that retire, which the session's Undo names. */
    val retireEventId: String? = null,
    /** [answerRegions] are a desktop edit of the areas (primary.answer_region_override) drawn on this PDF. */
    val answerRegionOverride: Boolean = false,
    /**
     * A card made inside an HTML attachment page (`created_in: "html_attachment"`): the page, its
     * version, the text quote and the page state. It has no PDF geometry and is never linked to
     * source markers (thoughts-html-attachment-cards-SPEC.md); it reviews inside the page.
     */
    val htmlAttachment: com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.Anchor? = null,
) {
    val approved: Boolean get() = status == "approved"
    /** Retired: never offered for review, but kept with its history, markers and schedule. */
    val retired: Boolean get() = retiredAt != null
    /** Made inside an HTML attachment page: reviewed in that page, never in the PDF. */
    val isHtmlAttachmentCard: Boolean get() = htmlAttachment != null
    /** Made in the HTML view and not linked yet: "Placed at next Sync" in the PDF. */
    val htmlPending: Boolean get() = htmlSelection != null && sourceAnchor == null
    /**
     * Whether the card has a place in its PDF. A card made in the HTML view has none (no page,
     * rectangle or regions are stored) until Fedora links it and the next build gives it its areas;
     * then [page] is 0 and [rect] empty, and [regions], [masksByPage] and [pagesLabel] are empty.
     */
    val hasPdfPlacement: Boolean get() = page > 0
    /** The 1-based PDF page, or null without a PDF placement. */
    val pdfPage: Int? get() = page.takeIf { it > 0 }
    val canEditAnswerBox: Boolean get() = sourceAnchor == null && sourceFiles.isEmpty() &&
        (sourceKind == "pdf" || (sourceKind.isBlank() && (answerRegions.isEmpty() || answerRegionOverride)))
    val answerBox: RectF get() {
        val boxes = answerRegions.filter { it.page == page }.map { it.rect }
        if (boxes.isEmpty()) return RectF().also {
            it.left = rect.left
            it.top = rect.top
            it.right = rect.right
            it.bottom = rect.bottom
        }
        return RectF().apply {
            left = boxes.minOf { it.left }
            top = boxes.minOf { it.top }
            right = boxes.maxOf { it.right }
            bottom = boxes.maxOf { it.bottom }
        }
    }
    val canDeleteEnclosedText: Boolean get() = runCatching { sourceAnchor?.let { anchor ->
        anchor.get("version")?.asInt == 2 && anchor.getAsJsonArray("segments")?.let { segments ->
            segments.size() > 0 && segments.all { value ->
                val segment = value.asJsonObject
                segment.get("source_file")?.asString?.let { path ->
                    listOf(".tex", ".md", ".markdown").any { path.endsWith(it) }
                } == true &&
                    !segment.get("segment_id")?.asString.isNullOrBlank() &&
                    segment.get("content_sha256")?.asString?.matches(Regex("[0-9a-f]{64}")) == true
            }
        } == true
    } == true }.getOrDefault(false)

    val regions: List<ReviewAnswerRegion> get() = if (!hasPdfPlacement) emptyList()
        else answerRegions.ifEmpty { listOf(ReviewAnswerRegion(page, rect)) }
    val rects: List<RectF> get() = regions.filter { it.page == page }.map { it.rect }
    val pagesLabel: String get() = regions.map { it.page }.distinct().sorted().joinToString(", ")
    val masksByPage: Map<Int, List<RectF>> get() = regions.groupBy { it.page - 1 }.mapValues { (_, values) -> values.map { it.rect } }
}

enum class ReviewBrowserState {
    SKIPPED,
    RETAINED,
    NEW,
    LEARNING,
    REVIEW,
    FUTURE,
    REPAIRING,
    NEEDS_ATTENTION,
    STALE,
    EXCLUDED,
    /** Retired by the user: kept with its history, never offered for review; wins over every state but DELETED. */
    RETIRED,
    /** Its document is unpublished in its Project: kept with its schedule, not offered, and not a problem of the card. */
    UNPUBLISHED,
    DELETED,
    CANDIDATE,
    REJECTED,
}

data class ReviewHistoryEntry(
    val eventId: String,
    val rating: String?,
    val reviewedAt: Instant?,
    val originId: String,
    val revision: Int?,
    val updatedAt: Instant?,
    val retractedEventId: String?,
    val retracted: Boolean,
    val effective: Boolean,
    val pendingSync: Boolean,
    val sourcePath: String?,
    val reviewAction: String? = null,
    val queueAction: String? = null,
    val scheduledDueAt: Instant? = null,
    val skipUntil: Instant? = null,
    val skipId: String? = null,
    val intervalAction: String? = null,
    val intervalAmount: String? = null,
    val intervalUnit: String? = null,
    val intervalDate: String? = null,
    val reviewTags: List<String> = emptyList(),
    /** The event wrote `review_tags` (even empty), so an empty list means "no tags", not an older answer. */
    val reviewTagsRecorded: Boolean = false,
    /** The answer's notes per tag group, keyed by group id ([ReviewGroupNotes.OTHER_KEY] for Other). */
    val groupNotes: Map<String, String> = emptyMap(),
    /** The section, page and document notes this answer changed, by scope wire name ([ReviewContextNotes]). */
    val contextNotes: Map<String, ReviewContextNotes.Note> = emptyMap(),
    /** An answer given in two steps (before and after the answer was shown); null for any other event. */
    val phases: ReviewPhases? = null,
    /** The retire or restore an undo_retire event cancels. */
    val undoneEventId: String? = null,
    /** The PDF the answer was given on (its `review_version`), or null when it does not say. */
    val pdfSha256: String? = null,
)

data class GeneratedReviewCandidate(
    val page: Int,
    val rect: RectF,
    val question: String,
    val pageVisualSha256: String,
    val cardId: String? = null,
    val note: String = "",
    val duplicateCheck: JsonObject? = null,
    val sourceAnchor: JsonObject? = null,
    val reanchor: JsonObject? = null,
)

data class ReviewBrowserCard(
    val card: ReviewCard,
    val answerText: String = "",
    val projectDisplayName: String = "",
    val documentDisplayName: String = "${card.pdfStem}.pdf",
    val state: ReviewBrowserState,
    val dueAt: Instant?,
    val intervalDays: Int,
    val reviewCount: Int,
    val lapseCount: Int,
    val lastRating: String?,
    val lastReviewedAt: Instant?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val revision: Int?,
    val sourcePath: String?,
    val pendingSync: Boolean,
    val deleteEventId: String?,
    val history: List<ReviewHistoryEntry>,
    val hasIssues: Boolean = state in setOf(ReviewBrowserState.REPAIRING, ReviewBrowserState.NEEDS_ATTENTION, ReviewBrowserState.STALE),
) {
    fun currentTags(): List<String>? = reviewCurrentTags(history)
    fun currentGroupNotes(): Map<String, String> = reviewCurrentGroupNotes(history)
    fun currentMark(): String? = reviewCurrentMark(history)
    /** Listed under Unavailable ([ReviewCard.listedAsUnavailable]). */
    val needsAction: Boolean get() = state !in NOT_UNAVAILABLE_STATES && card.needsAction()
}

// The states browserCards() decides first, from deletion, retirement and publication.
private val NOT_UNAVAILABLE_STATES = setOf(ReviewBrowserState.DELETED, ReviewBrowserState.UNPUBLISHED, ReviewBrowserState.RETIRED)

/**
 * Listed by the card browser under Unavailable and counted by "View N unavailable cards". A
 * retired card is never reviewed, so it is not unavailable.
 */
internal fun ReviewCard.listedAsUnavailable(deleted: Boolean, unpublished: Set<String>): Boolean =
    !deleted && !retired && publicationDocumentId() !in unpublished && needsAction()

/**
 * Problems only the user can resolve. Placement after a PDF rebuild, repositioning in
 * progress, a location not yet checked and pending marker removal settle on their own.
 */
/**
 * Whether a card's marked passages no longer match the markers the source index found for it.
 * Editing the text between a card's markers is not a change: the server follows unique markers
 * whatever their text, so the card stays available while that update is on its way. Cards with
 * several identified passages are compared by segment id, since file order need not follow
 * segment order. A card with one passage only needs its one marker. Older cards with several
 * passages stored without segment ids can be told apart only by content, compared in order.
 */
internal fun reviewPassagesChanged(segments: List<JsonObject>, evidence: List<JsonObject>): Boolean {
    fun id(value: JsonObject) = value.get("segment_id")?.takeUnless { it.isJsonNull }?.asString
    fun content(value: JsonObject) = value.get("content_sha256")?.takeUnless { it.isJsonNull }?.asString
    if (evidence.size != segments.size) return true
    if (segments.size == 1) return id(segments[0])?.let { it != id(evidence[0]) } ?: false
    if (segments.all { id(it) != null }) return segments.map(::id).toSet() != evidence.map(::id).toSet()
    return segments.map(::content) != evidence.map(::content)
}

/**
 * PDF highlights are drawn only from regions verified for the exact PDF on screen. An edited
 * source or a pending re-anchor does not move text in that PDF, so only removed cards hide.
 */
internal fun ReviewBrowserState.hidesPdfHighlight(): Boolean =
    this == ReviewBrowserState.DELETED || this == ReviewBrowserState.EXCLUDED

internal fun ReviewCard.needsAction(): Boolean =
    reanchorState == "needs_attention" ||
        sourceLocationState in setOf("missing", "ambiguous", "failed") ||
        sourceLinkState in setOf("linking_failed", "failed", "ambiguous")

/**
 * A card saved without markers on a PDF whose source the phone could not determine: Fedora links
 * it at the next Sync, or keeps it PDF-only. Not reviewable until then.
 */
internal const val REVIEW_PENDING_SERVER_LINK = "pending_server_link"

/** Fedora's codes for a card it could not link or place: the person places it again from its areas. */
internal val REVIEW_PLACE_AGAIN_ERROR_CODES = setOf(
    "review_card_place_again", "review_source_link_unresolved", "review_source_marker_missing_or_ambiguous",
)

/**
 * A card that should be placed again on its source document: Fedora asked for it, or the card's
 * linking failed on a document built from source ([sourceAvailable] not false).
 */
internal fun ReviewCard.needsPlacingAgain(sourceAvailable: Boolean?): Boolean =
    !wholeDocument && !markerRemovalPending && sourceAvailable != false &&
        ((reanchorState == "needs_attention" && reanchorErrorCode in REVIEW_PLACE_AGAIN_ERROR_CODES) ||
            sourceLinkState == "linking_failed")

/** Why the card must be placed again, as Fedora or the phone recorded it. */
internal fun ReviewCard.placeAgainReason(): String =
    reanchorMessage.ifBlank { sourceLinkReason }
        .replace(Regex("\\s*Place the (start/end )?markers manually for card [0-9a-fA-F-]+\\.\\s*$"), "").trim()

/** One-word summary of [reviewCurrentTags], kept for older views of the review state. */
internal fun reviewCurrentMark(history: List<ReviewHistoryEntry>): String? =
    reviewCurrentTags(history)?.let { ReviewTags.legacyReviewAction(it) ?: it.firstOrNull() }

/**
 * How many cards currently have each tag: one count per card whose [reviewCurrentTags] (the tags of
 * its latest answer in effect, under merged names from [mapping]) include it. [cards] holds one
 * history per card; the caller leaves deleted cards out. An answer moves its card from its old tags
 * to the new ones, undoing it moves the card back, a skip keeps them, and a card never answered
 * counts toward no tag. Tags of answers still in effect in [listed] (every history, deleted cards
 * included) that no card has now are listed with 0, so they stay offered.
 */
internal fun reviewTagUsageOf(
    cards: Collection<List<ReviewHistoryEntry>>,
    mapping: Map<String, String>,
    listed: Collection<List<ReviewHistoryEntry>> = cards,
): Map<String, Int> {
    val usage = HashMap<String, Int>()
    listed.forEach { history ->
        history.filter { it.effective && !it.retracted && it.retractedEventId == null && it.rating != "delete" }
            .forEach { entry -> entry.effectiveTags(mapping).forEach { usage.putIfAbsent(it, 0) } }
    }
    cards.forEach { history ->
        reviewCurrentTags(history, mapping).orEmpty().distinct().forEach { usage[it] = (usage[it] ?: 0) + 1 }
    }
    return usage
}

/**
 * Tags of the latest answer still in effect, under their merged names ([mapping]), or null when
 * the card was never answered; empty when that answer had no tags. A skip only postpones the card,
 * so it keeps the tags of the answer before it; undoing an answer goes back to the one before.
 * A review starts with these tags selected.
 */
internal fun reviewCurrentTags(
    history: List<ReviewHistoryEntry>,
    mapping: Map<String, String> = ReviewTagMerges.mapping(),
): List<String>? = reviewCurrentAnswer(history)?.effectiveTags(mapping)

/** The group notes recorded with the answer that gives the card its current tags. */
internal fun reviewCurrentGroupNotes(history: List<ReviewHistoryEntry>): Map<String, String> =
    reviewCurrentAnswer(history)?.groupNotes.orEmpty()

private fun reviewCurrentAnswer(history: List<ReviewHistoryEntry>): ReviewHistoryEntry? =
    history.filter { it.isAnswerInEffect() }
        .maxWithOrNull(compareBy<ReviewHistoryEntry> { it.reviewedAt }.thenBy { it.eventId })

enum class ReviewCardBucket {
    NEW,
    LEARNING,
    REVIEW,
}

data class ReviewQueueCounts(
    val newCount: Int = 0,
    val learningCount: Int = 0,
    val reviewCount: Int = 0,
) {
    val total: Int get() = newCount + learningCount + reviewCount
}

data class ReviewDeckSummary(
    val pdfStem: String,
    val counts: ReviewQueueCounts,
    val activeCount: Int,
    val nextDueAt: Instant?,
)

data class ReviewLibrarySummary(
    val counts: ReviewQueueCounts = ReviewQueueCounts(),
    val activeCount: Int = 0,
    val nextDueAt: Instant? = null,
    val decks: List<ReviewDeckSummary> = emptyList(),
    val repairingCount: Int = 0,
    val needsAttentionCount: Int = 0,
    val unavailableCount: Int = 0,
    /** Deleted cards whose source markers still wait to be removed. */
    val markerRemovalPendingCount: Int = 0,
)

data class PendingReviewChange(
    val entityType: String,
    val entityId: String,
    val pdfStem: String?,
    val createdAtEpochMillis: Long,
)

private enum class ReviewRecordSource(val precedence: Int) {
    LEGACY(0),
    SERVER(1),
    OUTGOING(2),
}

private data class LoadedReviewObject(
    val payload: JsonObject,
    val file: File,
    val source: ReviewRecordSource = ReviewRecordSource.LEGACY,
)

private data class FoldedReviewHistory(
    val schedule: ReviewSchedule?,
    val reviewCount: Int,
    val lapseCount: Int,
    val lastRating: ReviewRating?,
    val lastReviewedAt: Instant?,
    val deleteEventId: String?,
    val entries: List<ReviewHistoryEntry>,
    val suggestedInterval: ReviewInterval = ReviewInterval.suggested(null, null),
    /** The interval the latest review set; null before the first one or after Never. */
    val currentInterval: ReviewInterval? = null,
)

private data class ReviewFileState(
    val file: File,
    val length: Long,
    val lastModified: Long,
)

private data class ReviewDirectoryState(
    val path: String,
    val lastModified: Long,
)

private data class ReviewFileTree(
    val directories: List<ReviewDirectoryState>,
    val files: List<ReviewFileState>,
)

private data class ReviewSnapshotInventory(
    val legacyCards: ReviewFileTree,
    val incomingCards: ReviewFileTree,
    val outgoingCards: ReviewFileTree,
    val legacyEvents: ReviewFileTree,
    val incomingEvents: ReviewFileTree,
    val outgoingEvents: ReviewFileTree,
    val legacyExclusions: ReviewFileTree,
    val incomingExclusions: ReviewFileTree,
    val skips: ReviewFileTree,
    val sourceLocationRevision: Long = 0,
)

private data class ReviewSnapshot(
    val queueEvents: List<JsonObject>,
    val selectedCardRecords: Map<String, LoadedReviewObject>,
    val histories: Map<String, FoldedReviewHistory>,
    val cards: List<ReviewCard>,
    val cardsById: Map<String, ReviewCard>,
    val exclusions: List<JsonObject>,
    val outgoingCardPayloads: List<JsonObject>,
    val outgoingEventPayloads: List<JsonObject>,
    val outgoingCardIds: Set<String>,
    val outgoingEventCardIds: Set<String>,
)

private data class ReviewDashboard(val cards: List<ReviewCard>, val exclusions: List<JsonObject>)

/** Private, versioned database projection with numeric time encoding, avoiding event replay on restart. */
private object ReviewSnapshotCodec {
    // 3: cached parts hold each card's skip end; the latest skip now decides it.
    // 4: history entries carry review tags and one suggestion replaces correct/error.
    // 5: cards carry Fedora's re-anchor error code and message.
    // 6: history entries carry the section, page and document notes their answer changed.
    // 7: cards carry their retirement (retire, restore and undo_retire events).
    // 8: cards carry a desktop edit of their areas (answer_region_override); 7 was also used for it alone.
    // 9: history entries carry the steps of an answer given in two steps (review_phases).
    // 10: history entries carry the PDF version they were given on (review_version).
    const val VERSION = 10
    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapter(Instant::class.java, object : com.google.gson.TypeAdapter<Instant>() {
            override fun write(out: com.google.gson.stream.JsonWriter, value: Instant?) {
                if (value == null) { out.nullValue(); return }
                out.beginArray().value(value.epochSecond).value(value.nano).endArray()
            }
            override fun read(input: com.google.gson.stream.JsonReader): Instant? {
                if (input.peek() == com.google.gson.stream.JsonToken.NULL) { input.nextNull(); return null }
                input.beginArray()
                val value = Instant.ofEpochSecond(input.nextLong(), input.nextLong())
                input.endArray()
                return value
            }
        })
        .registerTypeAdapter(File::class.java, object : com.google.gson.TypeAdapter<File>() {
            override fun write(out: com.google.gson.stream.JsonWriter, value: File?) { out.value(value?.path) }
            override fun read(input: com.google.gson.stream.JsonReader): File? {
                if (input.peek() == com.google.gson.stream.JsonToken.NULL) { input.nextNull(); return null }
                return File(input.nextString())
            }
        }).create()
    // 3: the latest skip, not the longest, decides when a card returns.
    // 4: cards carry Fedora's re-anchor error code and message.
    // 5: cards carry their retirement.
    // 6: a desktop edit of a card's areas can move it to another page; 5 was also used for it alone.
    const val DASHBOARD_VERSION = 6
    fun encodeDashboard(snapshot: ReviewSnapshot): String = gson.toJson(ReviewDashboard(
        snapshot.cards.map { card ->
            // Construct explicitly: never copy question, answer regions, note, history or transport data.
            ReviewCard(id = card.id, status = card.status, pdfStem = card.pdfStem, pdfSha256 = card.pdfSha256,
                page = card.page, pageVisualSha256 = card.pageVisualSha256, question = "", rect = RectF(),
                schedule = card.schedule, reanchorState = card.reanchorState, initialDueAt = card.initialDueAt,
                skippedUntil = card.skippedUntil, sourceFiles = card.sourceFiles,
                sourceAnchor = card.sourceAnchor?.takeIf { it.get("version")?.asInt == 2 }?.let { anchor ->
                    JsonObject().apply {
                        addProperty("version", 2)
                        add("segments", com.google.gson.JsonArray().apply {
                            anchor.getAsJsonArray("segments").forEach { segment ->
                                add(JsonObject().apply { segment.asJsonObject.get("content_sha256")?.let { add("content_sha256", it) } })
                            }
                        })
                    }
                }, sourceLocationState = card.sourceLocationState, markerRemovalPending = card.markerRemovalPending,
                sourceLinkState = card.sourceLinkState, retiredAt = card.retiredAt, retireEventId = card.retireEventId)
        }, snapshot.exclusions,
    ))
    fun decodeDashboard(payload: String): ReviewDashboard? = runCatching {
        gson.fromJson(payload, ReviewDashboard::class.java).also {
            require(it.cards.size <= 1)
            it.cards.forEach { card ->
                require(card.id.isNotBlank() && card.pdfStem.isNotBlank())
                require(card.sourceFiles != null && card.sourceLocationState != null && card.sourceLinkState != null)
                require(card.status != null && card.reanchorState != null && card.pdfSha256 != null)
                // Projections written before these fields existed must be rebuilt from the records.
                require(card.reanchorErrorCode != null && card.reanchorMessage != null)
                card.schedule?.let { schedule -> require(schedule.dueAt != null) }
            }
            require(it.exclusions != null)
        }
    }.getOrNull()

    fun encode(snapshot: ReviewSnapshot): String = gson.toJson(snapshot)
    @Suppress("SENSELESS_COMPARISON") // Gson can populate null despite Kotlin non-null field declarations.
    fun decode(payload: String): ReviewSnapshot? = runCatching {
        gson.fromJson(payload, ReviewSnapshot::class.java).also {
            // Malformed or older projections must fall back to authoritative records.
            require(it.cardsById.keys == it.cards.map { card -> card.id }.toSet())
            require((it.cards + it.cardsById.values).all { card -> card.reanchorErrorCode != null && card.reanchorMessage != null })
            require(it.histories.values.all { history ->
                history.entries != null && history.suggestedInterval != null && history.entries.all { entry -> entry.reviewTags != null && entry.groupNotes != null && entry.contextNotes != null }
            })
            require(it.selectedCardRecords != null && it.queueEvents != null && it.exclusions != null)
            require(it.outgoingCardPayloads != null && it.outgoingEventPayloads != null)
            require(it.outgoingCardIds != null && it.outgoingEventCardIds != null)
        }
    }.getOrNull()
}

private data class CachedReviewSnapshot(
    val inventory: ReviewSnapshotInventory,
    val snapshot: ReviewSnapshot,
    /** Source locations are resolved while building; a newer source scan needs a new build. */
    val locationRevision: Long = 0,
)

/**
 * Where a deleted card's source markers are looked up and removed. A Context supplies the phone's
 * verified offline sources; tests supply their own.
 */
internal class ReviewMarkerSources(
    val annotationIndex: com.gitlab.mudlej.MjPdfReader.data.source.SourceAnnotationIndex?,
    val removeMarkers: (cardId: String, anchor: JsonObject) -> Boolean,
)

private data class ReviewSnapshotCacheKey(
    val root: String,
    val incomingRoot: String,
    val outgoingEventsRoot: String,
    val outgoingCardsRoot: String,
    val pdfStem: String? = null,
)

private class ReviewSnapshotSlot {
    val monitor = Any()

    @Volatile
    var cached: CachedReviewSnapshot? = null

    var dashboardGeneration = -1L
    var dashboard: ReviewDashboard? = null
    var databaseGeneration = -1L
    var locationRevision = -1L
    var databaseSnapshot: ReviewSnapshot? = null
    val databaseCards = linkedMapOf<String, ReviewSnapshot>()
    val stateWriteCount = AtomicLong()
    val stateWritesSkipped = AtomicLong()
    val dailyOrderKeys = ConcurrentHashMap<String, String>()
    var orderDay = ""
    val buildCount = AtomicLong()
}

/** Serializes outgoing-card read/modify/write and acknowledgement compare/delete in this process. */
internal object ReviewOutgoingCardFileLock {
    private val monitor = Any()

    fun <T> withLock(action: () -> T): T = synchronized(monitor, action)
}

class ReviewStore(
    private val context: Context?,
    private val root: File = defaultRoot(),
    private val incomingRoot: File = defaultIncomingRoot(),
    private val outgoingEventsRoot: File = defaultOutgoingEventsRoot(),
    private val outgoingCardsRoot: File = File(outgoingEventsRoot.parentFile, "outgoing-cards"),
) {
    private val snapshotSlot: ReviewSnapshotSlot by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val key = ReviewSnapshotCacheKey(
            root = root.normalizedPath(),
            incomingRoot = incomingRoot.normalizedPath(),
            outgoingEventsRoot = outgoingEventsRoot.normalizedPath(),
            outgoingCardsRoot = outgoingCardsRoot.normalizedPath(),
        )
        snapshotSlots[key] ?: ReviewSnapshotSlot().let { candidate ->
            snapshotSlots.putIfAbsent(key, candidate) ?: candidate
        }
    }

    private val database by lazy {
        context?.takeIf { incomingRoot == defaultIncomingRoot() && root == defaultRoot() }?.let {
            ReviewDatabase(it).also { db ->
                db.importOnce(listOf(
                    Triple(File(root, "cards"), "cards", 0), Triple(File(root, "events"), "events", 0),
                    Triple(File(root, "exclusions"), "exclusions", 0),
                    Triple(File(incomingRoot, "cards"), "cards", 1), Triple(File(incomingRoot, "events"), "events", 1),
                    Triple(File(incomingRoot, "exclusions"), "exclusions", 1),
                    Triple(outgoingCardsRoot, "cards", 2), Triple(outgoingEventsRoot, "events", 2),
                ))
            }
        }
    }
    /**
     * Stamps new answers and skips with the version of the card's PDF ([ReviewVersionStamp]); the
     * phone's Library by default, none without a context (tests set their own).
     */
    internal var versionStamper: ReviewVersionStamper? = context?.let { ReviewVersionStamper.library() }

    /** The stamp for a new event on [cardId], or null (no PDF on the phone, no stamper); never throws. */
    private fun versionStampOf(cardId: String): JsonObject? = runCatching {
        val stamper = versionStamper ?: return null
        snapshotForCard(cardId).cardsById[cardId]?.let(stamper::stamp)?.toJson()
    }.getOrNull()

    /** Works out [card]'s version stamp ahead of the answer, so a PDF that must be hashed is hashed once, early. */
    fun prepareVersionStamp(card: ReviewCard) {
        runCatching { versionStamper?.stamp(card) }
    }

    /**
     * A person's create or edit of a card record states the PDF version it was made on, like the
     * desktop's (feedback version stamps): [card]'s PDF, or [viewedSha256] when the edit was drawn on
     * the displayed PDF; an unknown version removes an older stamp. Automatic writes are not stamped.
     */
    private fun stampCardWrite(record: JsonObject, card: ReviewCard, viewedSha256: String? = null) {
        ReviewVersionStamp.put(record, runCatching { versionStamper?.stamp(card, viewedSha256) }.getOrNull())
    }

    private val localReviewSources by lazy { context?.let {
        com.gitlab.mudlej.MjPdfReader.data.source.LocalReviewSources(it) { record, file -> persistRecord(file, record) }
    } }
    private var sourceRecoveryChecked = false
    private fun recoverSources() {
        if (sourceRecoveryChecked) return
        sourceRecoveryChecked = true
        try { localReviewSources?.recover(outgoingEventsRoot.parentFile!!) }
        catch (failure: Throwable) { sourceRecoveryChecked = false; throw failure }
    }
    internal fun saveSourceRecord(record: JsonObject, destination: File) { persistRecord(destination, record) }

    private var placerOverride: com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourcePlacer? = null
    /** Places card areas in source and commits them; the phone's verified sources unless a test replaces them. */
    private val sourcePlacer: com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourcePlacer? get() = placerOverride ?: localReviewSources
    internal fun useSourcePlacerForTesting(placer: com.gitlab.mudlej.MjPdfReader.data.source.ReviewSourcePlacer): ReviewStore =
        apply { placerOverride = placer }

    private var markerSourcesOverride: ReviewMarkerSources? = null
    private val defaultMarkerSources by lazy { context?.let {
        ReviewMarkerSources(com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore(it).annotationIndex) { cardId, anchor ->
            localReviewSources?.retryMarkerRemoval(cardId, anchor) == true
        }
    } }
    private val markerSources: ReviewMarkerSources? get() = markerSourcesOverride ?: defaultMarkerSources
    private val annotationIndex get() = markerSources?.annotationIndex

    internal fun useMarkerSourcesForTesting(sources: ReviewMarkerSources): ReviewStore = apply { markerSourcesOverride = sources }

    /** Called by the background source scanner after it installs a complete, verified index. */
    internal fun reconcileSourceAnchors() {
        // Scanner refreshes are presentation-only. Source publication drives server
        // reconciliation; receiving a card must not manufacture another user edit.
    }

    private val projectIndex get() = ReviewProjectIndex.at(incomingRoot)

    private fun snapshotSlot(pdfStem: String?): ReviewSnapshotSlot {
        if (pdfStem == null) return snapshotSlot
        val key = ReviewSnapshotCacheKey(root.normalizedPath(), incomingRoot.normalizedPath(),
            outgoingEventsRoot.normalizedPath(), outgoingCardsRoot.normalizedPath(), pdfStem)
        return snapshotSlots.computeIfAbsent(key) { ReviewSnapshotSlot() }
    }

    private val skipsDirectory = context?.let { File(it.filesDir, "review-skips") } ?: File(root, "skips")
    private val skips = ReviewSkipStore(skipsDirectory)

    /** This phone's stable identity in review records. */
    internal fun originIdentity(): String = originId

    private val originId: String by lazy {
        context?.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)?.let { preferences ->
            preferences.getString(KEY_ORIGIN_ID, null)
                ?: UUID.randomUUID().toString().also {
                    preferences.edit().putString(KEY_ORIGIN_ID, it).apply()
                }
        } ?: "test-phone"
    }

    fun cards(): List<ReviewCard> = snapshot().cards.map(::detachedCard)

    /** The cards of [pdfStem], deleted ones too, each with its history: for the tab's Copy for agent. */
    fun cardsWithHistory(pdfStem: String): List<Pair<ReviewCard, List<ReviewHistoryEntry>>> = snapshot().let { snapshot ->
        snapshot.cards.filter { it.pdfStem == pdfStem }.map { detachedCard(it) to snapshot.histories[it.id]?.entries.orEmpty() }
    }

    fun browserCards(
        now: Instant = Instant.now(),
        canonicalPdfSha256ByFilename: Map<String, String>? = null,
    ): List<ReviewBrowserCard> {
        val snapshot = snapshot()
        val unpublished = unpublishedDocumentIds()
        return snapshot.cards.filter { it.schedule?.deleted != true }.map { cachedCard ->
            val cardId = cachedCard.id
            val history = snapshot.histories[cardId] ?: emptyHistory()
            val excluded = snapshot.exclusions.any { exclusion -> exclusion.matches(cachedCard) }
            val canonical = canonicalPdfSha256ByFilename?.get("${cachedCard.pdfStem}.pdf")
            val stale = canonicalPdfSha256ByFilename != null && (if (cachedCard.isHtmlAttachmentCard) canonical == null
                // A card inside an HTML attachment page follows its document, not one PDF version.
                else !canonical.equals(cachedCard.pdfSha256, ignoreCase = true))
            val state = when {
                history.schedule?.deleted == true -> ReviewBrowserState.DELETED
                cachedCard.retired -> ReviewBrowserState.RETIRED
                cachedCard.publicationDocumentId() in unpublished -> ReviewBrowserState.UNPUBLISHED
                cachedCard.status == "candidate" -> ReviewBrowserState.CANDIDATE
                cachedCard.status == "rejected" -> ReviewBrowserState.REJECTED
                cachedCard.reanchorState == "needs_attention" -> ReviewBrowserState.NEEDS_ATTENTION
                cachedCard.reanchorState in REPAIRING_STATES || cachedCard.status == "needs_reanchor" ->
                    ReviewBrowserState.REPAIRING
                stale -> ReviewBrowserState.STALE
                history.schedule?.retained == true -> ReviewBrowserState.RETAINED
                excluded -> ReviewBrowserState.EXCLUDED
                cachedCard.skippedUntil?.isAfter(now) == true -> ReviewBrowserState.SKIPPED
                history.schedule == null -> ReviewBrowserState.NEW
                history.schedule.fixedInterval && history.schedule.dueAt.isAfter(now) -> ReviewBrowserState.FUTURE
                history.schedule.intervalDays == 0 -> ReviewBrowserState.LEARNING
                history.schedule.dueAt.isAfter(now) -> ReviewBrowserState.FUTURE
                else -> ReviewBrowserState.REVIEW
            }
            ReviewBrowserCard(
                card = detachedCard(cachedCard),
                state = state,
                dueAt = if (history.schedule?.retained == true) null else history.schedule?.dueAt ?: cachedCard.initialDueAt,
                intervalDays = history.schedule?.intervalDays ?: 0,
                reviewCount = history.reviewCount,
                lapseCount = history.lapseCount,
                lastRating = history.lastRating?.wireName,
                lastReviewedAt = history.lastReviewedAt,
                createdAt = cachedCard.createdAt,
                updatedAt = cachedCard.updatedAt,
                revision = cachedCard.revision,
                sourcePath = cachedCard.sourceFiles.firstOrNull(),
                pendingSync = cardId in snapshot.outgoingCardIds ||
                    cardId in snapshot.outgoingEventCardIds,
                deleteEventId = history.deleteEventId,
                history = history.entries.toList(),
                // Not reviewed while its document is unpublished, so its placement is no problem of the card.
                hasIssues = state != ReviewBrowserState.UNPUBLISHED && hasReviewIssue(cachedCard, canonicalPdfSha256ByFilename),
            )
        }
    }

    fun card(cardId: String): ReviewCard? = snapshotForCard(cardId).cardsById[cardId]?.let(::detachedCard)

    fun skip(
        cardId: String,
        scope: ReviewSkipScope,
        until: Instant,
        projectsByFilename: Map<String, String> = emptyMap(),
        now: Instant = Instant.now(),
        dueOnly: Boolean = false,
    ): Result<ReviewSkipBatch> = runCatching {
        withSnapshotMutation {
            val snapshot = if (scope == ReviewSkipScope.CARD) snapshotForCard(cardId) else snapshot()
            val current = snapshot.cardsById[cardId] ?: error("Review card is unavailable")
            val targets = reviewSkipTargets(snapshot.cards, current, scope, projectsByFilename, now, dueOnly)
            // Each skipped card's PDF version, worked out before anything is written.
            val versions = targets.mapNotNull { target -> versionStampOf(target)?.let { target to it } }.toMap()
            if (database == null) {
                val batch = skips.save(targets, until, now, versions)
                migrateLegacySkips()
                batch
            } else {
                require(targets.isNotEmpty() && until.isAfter(now)) { "Choose a future time" }
                until.toEpochMilli()
                val batch = ReviewSkipBatch(UUID.randomUUID().toString(), targets, until)
                targets.forEach { target ->
                    persistEvent(queueEvent(target, batch.id, "skip", now, until).apply {
                        versions[target]?.let { add(ReviewVersionStamp.FIELD, it) }
                    })
                }
                batch
            }
        }
    }

    fun undoSkip(id: String, now: Instant = Instant.now()): Result<Unit> = runCatching {
        UUID.fromString(id)
        withSnapshotMutation {
            val cardIds = snapshot().queueEvents.filter {
                it.string("skip_id") == id && it.string("queue_action") == "skip"
            }.mapTo(hashSetOf()) { it.string("card_id") }
            if (database == null) {
                skips.queueUndo(id, cardIds, now)
                migrateLegacySkips()
            } else {
                require(cardIds.isNotEmpty()) { "The skip is unavailable" }
                cardIds.forEach { persistEvent(queueEvent(it, id, "undo_skip", now, null)) }
            }
        }
    }

    /**
     * Retires [cardId] (protocol/review-card-retire.md): it is never offered for review again but
     * keeps its history, markers and schedule. Returns the retire event's id, which [undoRetire] names.
     */
    fun retire(cardId: String, now: Instant = Instant.now()): Result<String> = retireAction(cardId, ReviewRetire.RETIRE, now)

    /** Restore to review: the card is active again with its old schedule (a past due date is due now). */
    fun restore(cardId: String, now: Instant = Instant.now()): Result<String> = retireAction(cardId, ReviewRetire.RESTORE, now)

    private fun retireAction(cardId: String, action: String, now: Instant): Result<String> = runCatching {
        require(runCatching { UUID.fromString(cardId) }.isSuccess) { "Invalid review card UUID" }
        withSnapshotMutation {
            val snapshot = snapshotForCard(cardId)
            val card = snapshot.cardsById[cardId] ?: error("The review card is unavailable")
            require(snapshot.histories[cardId]?.schedule?.deleted != true) { "The review card is deleted" }
            // Like a skip, the event states the version of the card's PDF.
            val event = reviewRetireEvent(card.id, action, now, originId)
            versionStampOf(cardId)?.let { event.add(ReviewVersionStamp.FIELD, it) }
            persistEvent(event)
            event.string("event_id")
        }
    }

    /** Undoes the retire or restore [eventId] with an undo_retire event; returns the undo's id. */
    fun undoRetire(eventId: String, now: Instant = Instant.now()): Result<String> = runCatching {
        UUID.fromString(eventId)
        withSnapshotMutation {
            val target = snapshot().queueEvents.firstOrNull {
                it.string("event_id") == eventId && it.string("queue_action") in setOf(ReviewRetire.RETIRE, ReviewRetire.RESTORE)
            } ?: error("The retire is unavailable")
            val event = reviewRetireEvent(target.string("card_id"), ReviewRetire.UNDO, now, originId, undoneEventId = eventId)
            persistEvent(event)
            event.string("event_id")
        }
    }

    /** Also called by Sync so old skips migrate even when Review has not been opened. */
    private var legacySkipsMigrated = false
    fun migrateLegacySkips() {
        synchronized(snapshotSlot.monitor) {
            recoverSources()
            if (database != null && legacySkipsMigrated) return
            legacySkipsMigrated = true
            try { skips.migrate(originId) { persistEvent(it) } }
            catch (failure: Throwable) { legacySkipsMigrated = false; throw failure }
        }
    }

    private fun queueEvent(cardId: String, batchId: String, action: String, now: Instant, until: Instant?) = JsonObject().apply {
        addProperty("schema_version", 3)
        addProperty("event_id", UUID.nameUUIDFromBytes("thoughts-queue-v1\n$action\n$batchId\n$cardId".toByteArray()).toString())
        addProperty("card_id", cardId); addProperty("skip_id", batchId)
        addProperty("queue_action", action); addProperty("reviewed_at", now.toString())
        until?.let { addProperty("skip_until", it.toString()) }
        addProperty("origin_id", originId)
    }

    private fun persistRecord(file: File, record: JsonObject) {
        val db = database
        if (db == null) writeAtomically(file, record.toString() + "\n")
        else db.transaction { db.put(file.path, if (record.has("event_id")) "events" else "cards", 2, record) }
    }

    internal fun requireSourceRecoveryComplete() = synchronized(snapshotSlot.monitor) { recoverSources(); localReviewSources?.requireRecoveryComplete() }

    internal fun pendingPayloads(kind: String): List<JsonObject>? {
        migrateLegacySkips()
        return database?.pending(kind)
    }

    /** Return a card to its server state after Fedora refused this device's edit. */
    internal fun discardRejected(operation: com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity) {
        if (operation.entityType != "review_card") return
        val changed = withSnapshotMutation {
            database?.discardRejected("cards", operation.entityId, operation.payloadJson) ?: false
        }
        if (changed) PendingChangesSignal.notifyChanged()
    }

    /** Keep a card whose source link Fedora refused, as [replacement]; Sync then sends it again. */
    internal fun replaceRejected(operation: com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity, replacement: JsonObject): Boolean {
        if (operation.entityType != "review_card") return false
        val changed = withSnapshotMutation {
            database?.replaceRejected("cards", operation.entityId, operation.payloadJson, replacement) ?: false
        }
        if (changed) PendingChangesSignal.notifyChanged()
        return changed
    }

    internal fun acknowledge(operation: com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity) {
        val kind = when (operation.entityType) { "review_card" -> "cards"; "review_event" -> "events"; else -> return }
        val changed = withSnapshotMutation {
            val db = database ?: return@withSnapshotMutation false
            val before = db.generation()
            db.acknowledge(kind, operation.entityId, operation.payloadJson, File(incomingRoot, "$kind/server/${operation.entityId}.json").path)
            before != db.generation()
        }
        if (changed) PendingChangesSignal.notifyChanged()
    }

    internal fun installServerRecords(records: List<Triple<String, String, JsonObject>>, retainedPaths: Set<String> = emptySet()): Boolean {
        val db = database ?: return false
        val changed = withSnapshotMutation {
            val before = db.generation()
            db.replaceServer(records, retainedPaths)
            before != db.generation()
        }
        if (changed) {
            // A newer server card may arrive after the source scanner has already
            // indexed unchanged local bytes; reconcile against that new revision.
            reconcileSourceAnchors()
            PendingChangesSignal.notifyChanged()
        }
        return true
    }

    private fun snapshotForCard(cardId: String): ReviewSnapshot {
        if (database == null) return snapshot(projectIndex.projectForCard(cardId))
        snapshot()
        return synchronized(snapshotSlot.monitor) { snapshotSlot.databaseCards[cardId]?.let(::withCurrentSourceLocations) ?: emptySnapshot() }
    }


    /** Twice the card's latest interval, whatever the answer was tagged. */
    fun suggestedInterval(cardId: String): ReviewInterval =
        snapshotForCard(cardId).histories[cardId]?.suggestedInterval ?: ReviewInterval.suggested(null, null)

    /** The card's current tags and interval, for showing next to the answer bar; a review starts with these tags. */
    fun currentReviewState(cardId: String): Pair<List<String>?, ReviewInterval?> =
        snapshotForCard(cardId).histories[cardId].let { history ->
            reviewCurrentTags(history?.entries.orEmpty()) to history?.currentInterval
        }

    /**
     * What the agent recall prediction is told besides the card itself ([ReviewRecallPrediction]):
     * [cardId]'s history and the answers of the other cards of its document (deleted cards left out).
     */
    fun recallPredictionHistory(cardId: String): Pair<List<ReviewHistoryEntry>, List<ReviewRecallPrediction.OtherCardAnswers>> =
        snapshot().let { snapshot ->
            val card = snapshot.cardsById[cardId]
            val documentKey = card?.let(ReviewContextNotes::documentKey)
            val others = snapshot.histories.mapNotNull { (otherId, history) ->
                val other = snapshot.cardsById[otherId] ?: return@mapNotNull null
                if (otherId == cardId || documentKey == null || other.schedule?.deleted == true ||
                    ReviewContextNotes.documentKey(other) != documentKey) return@mapNotNull null
                ReviewRecallPrediction.OtherCardAnswers(other.question, history.entries)
            }
            snapshot.histories[cardId]?.entries.orEmpty() to others
        }

    /**
     * The current section, page and document notes of [card]'s document: (scope wire name, key) to
     * text ("" when cleared), the newest answer of any of its cards deciding, deleted cards included
     * ([ReviewContextNotes.current]).
     */
    fun contextNotes(card: ReviewCard): Map<Pair<String, String>, String> = snapshot().let { snapshot ->
        val documentKey = ReviewContextNotes.documentKey(card)
        ReviewContextNotes.current(snapshot.histories.asSequence().flatMap { (cardId, history) ->
            val owner = snapshot.cardsById[cardId] ?: return@flatMap emptySequence()
            ReviewContextNotes.answersOf(ReviewContextNotes.documentKey(owner), history.entries)
        }, documentKey)
    }

    /** How many cards, deleted ones aside, currently have each tag; earlier tags at 0 ([reviewTagUsageOf]). */
    fun reviewTagUsage(): Map<String, Int> = snapshot().let { snapshot ->
        reviewTagUsageOf(
            snapshot.cards.filter { it.schedule?.deleted != true }.map { snapshot.histories[it.id]?.entries.orEmpty() },
            ReviewTagMerges.mapping(),
            listed = snapshot.histories.values.map { it.entries },
        )
    }

    fun dueCards(
        now: Instant = Instant.now(),
        pdfStem: String? = null,
        canonicalPdfSha256ByFilename: Map<String, String>? = null,
    ): List<ReviewCard> {
        val snapshot = snapshot(pdfStem)
        val dueIds = database?.dueIds(now.toEpochMilli(), pdfStem)
        val daySeed = now.atZone(ZoneOffset.UTC).toLocalDate().toString()
        val learnAheadLimit = now.plus(LEARN_AHEAD_LIMIT_MINUTES, ChronoUnit.MINUTES)
        val orderKeys = if (database == null) hashMapOf<String, String>() else synchronized(snapshotSlot.monitor) {
            if (snapshotSlot.orderDay != daySeed) { snapshotSlot.dailyOrderKeys.clear(); snapshotSlot.orderDay = daySeed }
            snapshotSlot.dailyOrderKeys
        }
        val candidates = if (dueIds == null) snapshot else snapshot.copy(cards = dueIds.mapNotNull(snapshot.cardsById::get))
        return activeCards(candidates, pdfStem, canonicalPdfSha256ByFilename)
            .filter { dueIds == null || it.id in dueIds }
            .filter { it.skippedUntil?.isAfter(now) != true }
            .filter { card ->
                (card.schedule == null && card.initialDueAt?.isAfter(now) != true) ||
                    (card.schedule != null && !card.schedule.dueAt.isAfter(now)) ||
                    (card.schedule != null && card.bucket == ReviewCardBucket.LEARNING && !card.schedule.fixedInterval &&
                        !card.schedule.dueAt.isAfter(learnAheadLimit))
            }
            // Oldest due first; a skip hides a card but keeps its due date, so it returns to its place.
            // Never-answered cards follow the due ones, and learning steps pulled in early come last.
            .sortedWith(compareBy<ReviewCard> { card ->
                when {
                    card.schedule == null -> 1
                    !card.schedule.dueAt.isAfter(now) -> 0
                    else -> 2
                }
            }.thenBy { card -> card.schedule?.dueAt ?: card.initialDueAt ?: card.createdAt ?: Instant.EPOCH }
                .thenBy { card -> orderKeys.getOrPut(card.id) { sha256("$daySeed${card.id}") } })
            .map(::detachedCard)
    }

    internal fun cardDocumentStems(): Map<String, String> = dashboardSnapshot().cards.associate { it.id to it.pdfStem }

    private fun selectedCardRecordsForReconciliation(): Map<String, LoadedReviewObject> {
        migrateLegacySkips()
        val db = database ?: return snapshot().selectedCardRecords
        return selectCardRecords(db.cardRecords().map {
            LoadedReviewObject(JsonParser.parseString(it.payload).asJsonObject, File(it.path), ReviewRecordSource.entries[it.source])
        })
    }

    /** Cards the server is still placing on a PDF version this device already shows. */
    /**
     * Cards whose place on the shown PDF is still coming. A retired card stays on the version it
     * was retired on and is never placed again while retired, so it never waits.
     */
    internal fun cardsAwaitingPlacement(canonicalPdfSha256ByFilename: Map<String, String>): Set<String> =
        snapshot().cards.filter { card ->
            card.schedule?.deleted != true && !card.retired && card.approved && card.reanchorState != "needs_attention" &&
                (card.reanchorState in REPAIRING_STATES ||
                    canonicalPdfSha256ByFilename["${card.pdfStem}.pdf"]?.equals(card.pdfSha256, ignoreCase = true) == false)
        }.mapTo(hashSetOf()) { it.id }

    fun summary(
        now: Instant = Instant.now(),
        canonicalPdfSha256ByFilename: Map<String, String>? = null,
    ): ReviewLibrarySummary {
        val dashboard = dashboardSnapshot()
        val summary = summarize(dashboard, now, canonicalPdfSha256ByFilename)
        val db = database
        if (summary.unavailableCount == 0 || db == null) return summary
        // The compact dashboard has counted cards the card browser does not list (two cards the
        // server had placed weeks before). "View N unavailable cards" counts the list it opens:
        // each counted card is checked again from its own records, as the card browser reads it.
        val counted = dashboard.cards.filter(::countsAsUnavailable)
        val unpublished = unpublishedDocumentIds()
        val (listed, notListed) = counted.partition { cached ->
            val part = withCurrentSourceLocations(currentCardPart(db, cached.id))
            val card = part.cardsById[cached.id]
            card != null && card.listedAsUnavailable(part.histories[cached.id]?.schedule?.deleted == true, unpublished)
        }
        if (notListed.isNotEmpty()) {
            Log.w(TAG, "Dashboard counted cards the card browser does not list as unavailable: " + notListed.joinToString { card ->
                "${card.id} (reanchor=${card.reanchorState} location=${card.sourceLocationState} link=${card.sourceLinkState})"
            })
        }
        return summary.copy(unavailableCount = listed.size)
    }

    /** One card's snapshot built from its current records, as a changed card is rebuilt. */
    private fun currentCardPart(db: ReviewDatabase, cardId: String): ReviewSnapshot = synchronized(snapshotSlot.monitor) {
        db.transaction { buildCardPart(db.records(cardId)) }
    }

    private fun buildCardPart(records: List<ReviewRecordEntity>): ReviewSnapshot {
        fun loaded(kind: String, source: Int) = records.filter { it.kind == kind && it.source == source }.map {
            LoadedReviewObject(JsonParser.parseString(it.payload).asJsonObject, File(it.path), ReviewRecordSource.entries[source])
        }
        return buildSnapshotRecords(loaded("cards", 0), loaded("cards", 1), loaded("cards", 2),
            loaded("events", 0), loaded("events", 1), loaded("events", 2),
            records.filter { it.kind == "exclusions" }.map { JsonParser.parseString(it.payload).asJsonObject }, resolveLocations = false)
    }

    private fun countsAsUnavailable(card: ReviewCard): Boolean =
        card.listedAsUnavailable(card.schedule?.deleted == true, unpublished = emptySet())

    internal fun fullSummaryForTesting(now: Instant, hashes: Map<String, String>? = null): ReviewLibrarySummary =
        summarize(snapshot(), now, hashes)

    private fun summarize(snapshot: ReviewSnapshot, now: Instant, canonicalPdfSha256ByFilename: Map<String, String>?): ReviewLibrarySummary {
        // Deletion lives in folded review history, not the retained card payload.
        val allCards = snapshot.cards.filter { it.schedule?.deleted != true }
        // A retired card stays on the PDF version it was retired on: neither repairing nor a problem.
        val placedCards = allCards.filterNot(ReviewCard::retired)
        val activeCards = activeCards(
            snapshot = snapshot,
            canonicalPdfSha256ByFilename = canonicalPdfSha256ByFilename,
        )
        val decks = activeCards
            .groupBy(ReviewCard::pdfStem)
            .map { (pdfStem, cards) ->
                ReviewDeckSummary(
                    pdfStem = pdfStem,
                    counts = dueCounts(cards, now),
                    activeCount = cards.size,
                    nextDueAt = nextDueAt(cards, now),
                )
            }
            .sortedWith(
                compareByDescending<ReviewDeckSummary> { it.counts.total }
                    .thenBy { it.pdfStem.lowercase() },
            )
        return ReviewLibrarySummary(
            counts = dueCounts(activeCards, now),
            activeCount = activeCards.size,
            nextDueAt = nextDueAt(activeCards, now),
            decks = decks,
            repairingCount = placedCards.count { card ->
                card.reanchorState in setOf("queued", "retry", "running") ||
                    (card.approved && card.reanchorState != "needs_attention" &&
                        !card.matchesCanonical(canonicalPdfSha256ByFilename))
            },
            needsAttentionCount = placedCards.count { it.reanchorState == "needs_attention" },
            unavailableCount = snapshot.cards.count(::countsAsUnavailable),
            markerRemovalPendingCount = snapshot.cards.count(ReviewCard::markerRemovalPending),
        )
    }

    private fun hasReviewIssue(
        card: ReviewCard,
        canonicalPdfSha256ByFilename: Map<String, String>?,
    ): Boolean = card.markerRemovalPending ||
        (canonicalPdfSha256ByFilename != null && !card.matchesCanonical(canonicalPdfSha256ByFilename)) ||
        card.status == "needs_reanchor" ||
        card.reanchorState in REPAIRING_STATES || card.reanchorState == "needs_attention" ||
        card.sourceLocationState in setOf("missing", "unavailable", "ambiguous", "pending", "failed") ||
        card.sourceLinkState in setOf("linking_failed", "failed", "pending", "unavailable", "ambiguous", REVIEW_PENDING_SERVER_LINK)

    /** The documents unpublished in their Project, from the Library catalogue; none when it cannot be read. */
    private fun unpublishedDocumentIds(): Set<String> = context?.let {
        runCatching { unpublishedDocumentIds(LibraryProjectStore(it).projects()) }.getOrNull()
    }.orEmpty()

    private fun activeCards(
        snapshot: ReviewSnapshot,
        pdfStem: String? = null,
        canonicalPdfSha256ByFilename: Map<String, String>? = null,
    ): List<ReviewCard> {
        val unpublished = unpublishedDocumentIds()
        return snapshot.cards.filter { card ->
            card.approved &&
                card.schedule?.deleted != true &&
                card.schedule?.retained != true &&
                !card.retired &&
                card.publicationDocumentId() !in unpublished &&
                card.reanchorState !in setOf("queued", "retry", "running", "updating", "needs_attention") &&
                !hasReviewIssue(card, canonicalPdfSha256ByFilename) &&
                (pdfStem == null || card.pdfStem == pdfStem) &&
                card.matchesCanonical(canonicalPdfSha256ByFilename) &&
                snapshot.exclusions.none { exclusion ->
                    val page = exclusion.int("page")
                    val visualSha256 = exclusion.string("page_visual_sha256")
                    (visualSha256.isNotEmpty() && visualSha256 == card.pageVisualSha256) ||
                        (exclusion.string("pdf_stem") == card.pdfStem && page == card.page)
                }
        }
    }

    private fun dueCounts(cards: List<ReviewCard>, now: Instant): ReviewQueueCounts {
        val learnAheadLimit = now.plus(LEARN_AHEAD_LIMIT_MINUTES, ChronoUnit.MINUTES)
        val due = cards.filter { it.skippedUntil?.isAfter(now) != true }.filter { card ->
            (card.schedule == null && card.initialDueAt?.isAfter(now) != true) ||
                (card.schedule != null && !card.schedule.dueAt.isAfter(now)) ||
                (card.schedule != null && card.bucket == ReviewCardBucket.LEARNING && !card.schedule.fixedInterval &&
                    !card.schedule.dueAt.isAfter(learnAheadLimit))
        }
        return ReviewQueueCounts(
            newCount = due.count { it.bucket == ReviewCardBucket.NEW },
            learningCount = due.count { it.bucket == ReviewCardBucket.LEARNING },
            reviewCount = due.count { it.bucket == ReviewCardBucket.REVIEW },
        )
    }

    private fun nextDueAt(cards: List<ReviewCard>, now: Instant): Instant? = cards
        .mapNotNull { card ->
            val schedule = card.schedule
            val queueEntryAt = if (schedule == null) card.initialDueAt ?: now
            else if (card.bucket == ReviewCardBucket.LEARNING && !schedule.fixedInterval) {
                schedule.dueAt.minus(LEARN_AHEAD_LIMIT_MINUTES, ChronoUnit.MINUTES)
            } else {
                schedule.dueAt
            }
            maxOf(queueEntryAt, card.skippedUntil ?: Instant.MIN).takeIf { it.isAfter(now) }
        }
        .minOrNull()

    fun record(
        cardId: String,
        rating: ReviewRating,
        reviewedAt: Instant = Instant.now(),
        scheduledDueAt: Instant? = null,
        reviewAction: String? = null,
        deleteSource: JsonObject? = null,
        /** null records no tag list, so older readers derive the tag; a list, even empty, is the answer's tags. */
        reviewTags: List<String>? = null,
        groupNotes: Map<String, String> = emptyMap(),
        /** Section, page and document notes this answer changed; left out when empty. */
        contextNotes: Map<ReviewContextNotes.Scope, ReviewContextNotes.Note> = emptyMap(),
        /** The steps of an answer given in two steps; null for any other answer (the field is left out). */
        reviewPhases: ReviewPhases? = null,
        /** [ReviewEntry.DUE_QUEUE] or [ReviewEntry.REVIEW_NOW]; null leaves the field out. */
        reviewEntry: String? = null,
    ): Result<File> = runCatching {
        require(deleteSource == null || rating == ReviewRating.DELETE)
        require(reviewAction == null || reviewAction in setOf("correct", "error", "wrong", "skip"))
        // Skip is a postponement ([skip]), never an answer's tag.
        val tags = reviewTags.orEmpty().mapNotNull(ReviewTags::normalize).filterNot { it.equals(ReviewTags.SKIP, ignoreCase = true) }.distinct()
        require(tags.isEmpty() || rating != ReviewRating.DELETE) { "Deletions cannot be tagged" }
        val notes = ReviewGroupNotes.toJson(groupNotes)
        require(notes == null || rating != ReviewRating.DELETE) { "Deletions cannot carry notes" }
        val contextField = ReviewContextNotes.toJson(contextNotes)
        require(contextField == null || rating != ReviewRating.DELETE) { "Deletions cannot carry notes" }
        require(reviewPhases == null || rating != ReviewRating.DELETE) { "Deletions are not answers" }
        require(reviewEntry == null || rating != ReviewRating.DELETE) { "Deletions are not answers" }
        require(reviewEntry == null || reviewEntry == ReviewEntry.DUE_QUEUE || reviewEntry == ReviewEntry.REVIEW_NOW)
        val action = reviewAction ?: ReviewTags.legacyReviewAction(tags)
        require(rating != ReviewRating.CUSTOM_INTERVAL || scheduledDueAt?.isAfter(reviewedAt) == true) {
            "Choose a future review time"
        }
        require(runCatching { UUID.fromString(cardId) }.isSuccess) { "Invalid review card UUID" }
        val eventId = UUID.randomUUID().toString()
        val event = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("event_id", eventId)
            addProperty("card_id", cardId)
            addProperty("rating", rating.wireName)
            deleteSource?.let { add("delete_source", it.deepCopy()) }
            action?.let { addProperty("review_action", it) }
            // An answer recorded with no tags keeps an empty list, so it is not read as an older answer.
            if (tags.isNotEmpty() || (reviewTags != null && rating != ReviewRating.DELETE))
                add("review_tags", JsonArray().apply { tags.forEach(::add) })
            notes?.let { add(ReviewGroupNotes.FIELD, it) }
            contextField?.let { add(ReviewContextNotes.FIELD, it) }
            reviewPhases?.let { add(ReviewPhases.FIELD, it.toJson()) }
            reviewEntry?.let { addProperty(ReviewEntry.FIELD, it) }
            // Which version of the PDF the answer was given on; deletions need none.
            if (rating != ReviewRating.DELETE) versionStampOf(cardId)?.let { add(ReviewVersionStamp.FIELD, it) }
            if (rating == ReviewRating.CUSTOM_INTERVAL) addProperty("scheduled_due_at", scheduledDueAt.toString())
            addProperty("scheduler_version", CURRENT_SCHEDULER_VERSION)
            addProperty("reviewed_at", reviewedAt.toString())
            addProperty("updated_at", reviewedAt.toString())
            addProperty("revision", 1)
            addProperty("origin_id", originId)
        }
        if (rating == ReviewRating.DELETE) {
            event.remove("delete_source")
            event.addProperty("source_authority", "client-v1")
            val anchor = card(cardId)?.sourceAnchor
            event.addProperty("marker_removal", if (anchor == null) "not_required" else "pending")
            val edit = if (anchor == null) null else runCatching {
                checkNotNull(localReviewSources) { "Marker removal pending" }.deletion(cardId, anchor, deleteSource != null)
            }.getOrElse { failure ->
                event.addProperty("marker_removal_reason", failure.message ?: "Marker removal pending")
                null
            }
            if (edit != null) {
                event.addProperty("marker_removal", "complete")
                try { withSnapshotMutation { localReviewSources!!.commit(edit, event, eventTransportFile(event)) } }
                catch (failure: Exception) {
                    if (localReviewSources!!.hasRecoveryRecord(eventId)) throw failure
                    event.remove("source_change"); event.addProperty("marker_removal", "pending")
                    event.addProperty("marker_removal_reason", failure.message ?: "Marker removal pending")
                }
            }
        }
        persistEvent(event)
    }

    /** Apply one timing choice to a fixed set of active cards, using the same due time. */
    fun recordScoped(
        cardId: String,
        scope: ReviewSkipScope,
        rating: ReviewRating,
        reviewedAt: Instant,
        scheduledDueAt: Instant?,
        reviewAction: String?,
        projectsByFilename: Map<String, String> = emptyMap(),
        dueOnly: Boolean = false,
        reviewTags: List<String>? = null,
        groupNotes: Map<String, String> = emptyMap(),
        /** Recorded only on [cardId]'s event: they are about that card's section, page and document. */
        contextNotes: Map<ReviewContextNotes.Scope, ReviewContextNotes.Note> = emptyMap(),
        /** Recorded only on [cardId]'s event: the other cards were not reviewed in two steps. */
        reviewPhases: ReviewPhases? = null,
        /** How the session started ([ReviewEntry]), recorded on every target's answer. */
        reviewEntry: String? = null,
    ): Result<Map<String, String>> = runCatching {
        withSnapshotMutation {
            val snapshot = if (scope == ReviewSkipScope.CARD) snapshotForCard(cardId) else snapshot()
            val current = snapshot.cardsById[cardId] ?: error("Review card is unavailable")
            val targets = if (scope == ReviewSkipScope.CARD) setOf(cardId)
                else reviewSkipTargets(snapshot.cards, current, scope, projectsByFilename, reviewedAt, dueOnly) + cardId
            require(current.approved && current.schedule?.deleted != true) { "Review card is unavailable" }
            require(cardId in targets) { "Review card is no longer active" }
            val recorded = linkedMapOf<String, String>()
            try {
                targets.forEach { target ->
                    recorded[target] = record(target, rating, reviewedAt, scheduledDueAt, reviewAction,
                        reviewTags = reviewTags, groupNotes = groupNotes,
                        contextNotes = if (target == cardId) contextNotes else emptyMap(),
                        reviewPhases = reviewPhases.takeIf { target == cardId }, reviewEntry = reviewEntry).getOrThrow().nameWithoutExtension
                }
            } catch (error: Exception) {
                // Compensating events preserve sync history if a write fails partway through.
                retractRatings(recorded).exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            recorded
        }
    }

    fun retractRatings(events: Map<String, String>): Result<Unit> = runCatching {
        withSnapshotMutation {
            val now = Instant.now()
            events.forEach { (cardId, eventId) -> retractRating(cardId, eventId, now).getOrThrow() }
        }
    }

    fun retractRating(
        cardId: String,
        retractedEventId: String,
        retractedAt: Instant = Instant.now(),
    ): Result<File> = runCatching {
        require(runCatching { UUID.fromString(cardId) }.isSuccess) { "Invalid review card UUID" }
        require(runCatching { UUID.fromString(retractedEventId) }.isSuccess) {
            "Invalid review event UUID"
        }
        require(snapshotForCard(cardId).histories[cardId]?.schedule?.deleted != true) {
            "Deleted review cards cannot be restored"
        }
        val eventId = UUID.randomUUID().toString()
        val event = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("event_id", eventId)
            addProperty("card_id", cardId)
            addProperty("retracted_event_id", retractedEventId)
            addProperty("reviewed_at", retractedAt.toString())
            addProperty("updated_at", retractedAt.toString())
            addProperty("revision", 1)
            addProperty("origin_id", originId)
        }
        persistEvent(event)
    }

    fun updateQuestion(
        cardId: String,
        question: String,
        updatedAt: Instant = Instant.now(),
    ): Result<ReviewCard> = runCatching {
        require(runCatching { UUID.fromString(cardId) }.isSuccess) { "Invalid review card UUID" }
        val normalizedQuestion = question.trim()
        require(normalizedQuestion.isNotEmpty() && normalizedQuestion.length <= MAX_QUESTION_LENGTH) {
            "The question must contain between 1 and $MAX_QUESTION_LENGTH characters"
        }
        ReviewOutgoingCardFileLock.withLock {
            val snapshot = snapshotForCard(cardId)
            val selected = snapshot.selectedCardRecords[cardId]
                ?: error("The review card is unavailable")
            val currentHistory = snapshot.histories[cardId] ?: emptyHistory()
            val current = parseCard(selected.payload, currentHistory.schedule)
                ?: error("The review card is invalid")
            if (current.question == normalizedQuestion) return@withLock current

            val timestamp = updatedAt.toString()
            val updated = selected.payload.deepCopy().apply {
                addProperty("revision", (intOrNull("revision") ?: 0) + 1)
                addProperty("updated_at", timestamp)
                addProperty("origin_id", originId)
                val primary = getAsJsonObject("primary")?.deepCopy()
                    ?: error("The review card has no primary payload")
                primary.addProperty("question", normalizedQuestion)
                add("primary", primary)
            }
            stampCardWrite(updated, current)
            val destination = outgoingCardFile(cardId)
            withSnapshotMutation {
                persistRecord(destination, updated)
            }
            context?.let { PendingChangesSignal.notifyChanged() }
            checkNotNull(parseCard(updated, currentHistory.schedule))
        }
    }

    fun updateNote(
        cardId: String,
        note: String,
        updatedAt: Instant = Instant.now(),
        expectedNote: String? = null,
    ): Result<ReviewCard> = runCatching {
        require(runCatching { UUID.fromString(cardId) }.isSuccess) { "Invalid review card UUID" }
        val normalizedNote = note.trim()
        require(normalizedNote.length <= MAX_NOTE_LENGTH) {
            "The note must contain at most $MAX_NOTE_LENGTH characters"
        }
        ReviewOutgoingCardFileLock.withLock {
            val snapshot = snapshotForCard(cardId)
            val selected = snapshot.selectedCardRecords[cardId]
                ?: error("The review card is unavailable")
            val history = snapshot.histories[cardId] ?: emptyHistory()
            val current = parseCard(selected.payload, history.schedule)
                ?: error("The review card is invalid")
            check(expectedNote == null || current.note == expectedNote) {
                "The note changed during editing. Reopen the editor and try again."
            }
            if (current.note == normalizedNote) return@withLock current
            val updated = selected.payload.deepCopy().apply {
                addProperty("revision", (intOrNull("revision") ?: 0) + 1)
                addProperty("updated_at", updatedAt.toString())
                addProperty("origin_id", originId)
                // Card notes are independent of the question and source-derived PDF geometry.
                addProperty("note", normalizedNote)
            }
            stampCardWrite(updated, current)
            withSnapshotMutation {
                persistRecord(outgoingCardFile(cardId), updated)
            }
            context?.let { PendingChangesSignal.notifyChanged() }
            checkNotNull(parseCard(updated, history.schedule))
        }
    }

    fun updateRegion(
        cardId: String,
        expectedPdfSha256: String,
        expectedPage: Int,
        rect: RectF,
        updatedAt: Instant = Instant.now(),
    ): Result<ReviewCard> = updateCardContent(cardId, expectedPdfSha256, expectedPage, rect, null, updatedAt)

    /** Save the question and PDF box as one revision, or leave both unchanged on failure. */
    fun updateCardContent(
        cardId: String,
        expectedPdfSha256: String,
        expectedPage: Int,
        rect: RectF,
        question: String?,
        updatedAt: Instant = Instant.now(),
    ): Result<ReviewCard> = runCatching {
        require(runCatching { UUID.fromString(cardId) }.isSuccess) { "Invalid review card UUID" }
        val normalizedQuestion = question?.trim()
        require(normalizedQuestion == null ||
            (normalizedQuestion.isNotEmpty() && normalizedQuestion.length <= MAX_QUESTION_LENGTH)) {
            "The question must contain between 1 and $MAX_QUESTION_LENGTH characters"
        }
        require(validReviewRect(rect)) { "Invalid review region" }
        ReviewOutgoingCardFileLock.withLock {
            val snapshot = snapshotForCard(cardId)
            val selected = snapshot.selectedCardRecords[cardId]
                ?: error("The review card is unavailable")
            val history = snapshot.histories[cardId] ?: emptyHistory()
            val current = parseCard(selected.payload, history.schedule)
                ?: error("The review card is invalid")
            require(current.pdfSha256.equals(expectedPdfSha256, ignoreCase = true) &&
                current.page == expectedPage) {
                "The PDF location changed. Reopen the card before editing its answer box."
            }
            val previousBox = current.answerBox
            val geometryChanged = previousBox.left != rect.left || previousBox.top != rect.top ||
                previousBox.right != rect.right || previousBox.bottom != rect.bottom
            require(current.canEditAnswerBox || !geometryChanged) {
                "Edit the marked answer text in the source document to change these regions."
            }
            if (!geometryChanged && (normalizedQuestion == null || current.question == normalizedQuestion)) {
                return@withLock current
            }
            val updated = selected.payload.deepCopy().apply {
                addProperty("revision", (intOrNull("revision") ?: 0) + 1)
                addProperty("updated_at", updatedAt.toString())
                addProperty("origin_id", originId)
                normalizedQuestion?.let { getAsJsonObject("primary").addProperty("question", it) }
                if (geometryChanged) {
                    val coordinates = com.google.gson.JsonArray().apply {
                        add(rect.left)
                        add(rect.top)
                        add(rect.right)
                        add(rect.bottom)
                    }
                    val primary = getAsJsonObject("primary")
                    primary.add("rect", coordinates)
                    if (primary.has("regions")) {
                        // Replace this page's mask with the edited box, preserving all other pages.
                        primary.add("regions", com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("page", current.page)
                                add("rect", coordinates.deepCopy())
                            })
                            primary.getAsJsonArray("regions").forEach { region ->
                                if (region.asJsonObject.get("page").asInt != current.page) add(region.deepCopy())
                            }
                        })
                    }
                    // The desktop shows its edit of the areas while it exists: the new box replaces it there too.
                    if (current.answerRegionOverride) primary.add("answer_region_override", reviewAnswerRegionOverrideJson(
                        current.pdfSha256,
                        listOf(ReviewAreaBox(current.page, rect.left, rect.top, rect.right, rect.bottom)) +
                            current.regions.filter { it.page != current.page }.map(ReviewAreaBox::of),
                    ))
                }
            }
            stampCardWrite(updated, current)
            withSnapshotMutation {
                persistRecord(outgoingCardFile(cardId), updated)
            }
            context?.let { PendingChangesSignal.notifyChanged() }
            checkNotNull(parseCard(updated, history.schedule))
        }
    }

    /**
     * Save the question and every answer area of a PDF card as one revision (Edit card with
     * several areas), or leave the card unchanged on failure. [regions] are 1-based pages of the
     * card's PDF, in order; one or more, on any pages.
     *
     * A single area on the card's page keeps a card without stored regions in its old form;
     * anything else is stored as schema-4 regions. The card stays listed under its page while an
     * area remains there, otherwise it moves to the first area's page. Source-linked cards are
     * refused: Fedora re-derives their areas from the source markers.
     */
    fun updateCardAreas(
        cardId: String,
        expectedPdfSha256: String,
        expectedPage: Int,
        regions: List<ReviewAreaBox>,
        question: String?,
        updatedAt: Instant = Instant.now(),
    ): Result<ReviewCard> = runCatching {
        require(runCatching { UUID.fromString(cardId) }.isSuccess) { "Invalid review card UUID" }
        val normalizedQuestion = question?.trim()
        require(normalizedQuestion == null ||
            (normalizedQuestion.isNotEmpty() && normalizedQuestion.length <= MAX_QUESTION_LENGTH)) {
            "The question must contain between 1 and $MAX_QUESTION_LENGTH characters"
        }
        require(regions.isNotEmpty() && regions.size <= 2000 && regions.all(ReviewAreaBox::valid)) {
            "Invalid review region"
        }
        ReviewOutgoingCardFileLock.withLock {
            val snapshot = snapshotForCard(cardId)
            val selected = snapshot.selectedCardRecords[cardId]
                ?: error("The review card is unavailable")
            val history = snapshot.histories[cardId] ?: emptyHistory()
            val current = parseCard(selected.payload, history.schedule)
                ?: error("The review card is invalid")
            require(current.pdfSha256.equals(expectedPdfSha256, ignoreCase = true) &&
                current.page == expectedPage) {
                "The PDF location changed. Reopen the card before editing its answer areas."
            }
            val before = current.regions.map(ReviewAreaBox::of)
            val geometryChanged = before.size != regions.size ||
                before.zip(regions).any { (old, new) -> !old.sameAs(new) }
            require(!geometryChanged || (current.canEditAnswerBox && !current.wholeDocument)) {
                "Edit the marked answer text in the source document to change these regions."
            }
            if (!geometryChanged && (normalizedQuestion == null || current.question == normalizedQuestion)) {
                return@withLock current
            }
            val updated = selected.payload.deepCopy().apply {
                addProperty("revision", (intOrNull("revision") ?: 0) + 1)
                addProperty("updated_at", updatedAt.toString())
                addProperty("origin_id", originId)
                val primary = getAsJsonObject("primary")
                normalizedQuestion?.let { primary.addProperty("question", it) }
                if (geometryChanged) {
                    fun coordinates(box: ReviewAreaBox) = com.google.gson.JsonArray().apply {
                        add(box.left); add(box.top); add(box.right); add(box.bottom)
                    }
                    val page = reviewPrimaryPage(current.page, regions)
                    primary.add("rect", coordinates(reviewPrimaryBox(page, regions)))
                    if (page != current.page) {
                        primary.addProperty("page", page)
                        // The same placeholder a new card gets; Fedora fills in the real fingerprint.
                        primary.addProperty("page_visual_sha256",
                            sha256("pending-page-visual\n${current.pdfSha256.lowercase()}\n$page"))
                        // An imported component locates the card by its page within the component.
                        primary.getAsJsonObject("component_origin")?.let { origin ->
                            origin.get("local_page")?.takeIf { it.isJsonPrimitive }?.asInt?.let { local ->
                                origin.addProperty("local_page", local + page - current.page)
                            }
                        }
                    }
                    val single = regions.singleOrNull()?.takeIf { it.page == page }
                    if (single != null && !primary.has("regions")) {
                        primary.remove("regions")
                    } else {
                        primary.add("regions", com.google.gson.JsonArray().apply {
                            regions.forEach { box ->
                                add(JsonObject().apply {
                                    addProperty("page", box.page)
                                    add("rect", coordinates(box))
                                })
                            }
                        })
                        addProperty("schema_version", 4)
                        // An older PDF card without a kind: say so, so it stays editable with regions.
                        if (current.sourceKind.isBlank()) primary.addProperty("source_kind", "pdf")
                    }
                    // The desktop shows its edit of the areas while it exists: these areas replace it there too.
                    if (current.answerRegionOverride) {
                        primary.add("answer_region_override", reviewAnswerRegionOverrideJson(current.pdfSha256, regions))
                    }
                }
            }
            stampCardWrite(updated, current)
            val parsed = checkNotNull(parseCard(updated, history.schedule)) { "Invalid review region" }
            withSnapshotMutation {
                persistRecord(outgoingCardFile(cardId), updated)
            }
            context?.let { PendingChangesSignal.notifyChanged() }
            parsed
        }
    }

    /**
     * Edit card on a document built from source, and "Place this card again": saves [areas] and
     * the question as one revision whose source markers are placed anew. The card's current
     * markers (every segment) are removed from the working source and each area is marked as its
     * own segment, in area order, by the same mapping as a new card; the source change carries a
     * fresh transaction id. [pdfSha256] is the PDF the areas were drawn on, which may be newer
     * than the card's ([expectedPdfSha256], with [expectedPage], detects a concurrent change).
     *
     * An area that cannot be linked fails with ReviewAreaLinkingException and nothing is saved.
     * Moving markers that already exist needs [replacementSupported] (server capability
     * [REVIEW_SOURCE_REPLACEMENT_CAPABILITY]).
     */
    fun replaceCardAreas(
        cardId: String,
        expectedPdfSha256: String,
        expectedPage: Int,
        pdfSha256: String,
        areas: List<ReviewEditedArea>,
        question: String?,
        updatedAt: Instant = Instant.now(),
        replacementSupported: Boolean = true,
    ): Result<ReviewCard> = runCatching {
        require(runCatching { UUID.fromString(cardId) }.isSuccess) { "Invalid review card UUID" }
        val normalizedQuestion = question?.trim()
        require(normalizedQuestion == null ||
            (normalizedQuestion.isNotEmpty() && normalizedQuestion.length <= MAX_QUESTION_LENGTH)) {
            "The question must contain between 1 and $MAX_QUESTION_LENGTH characters"
        }
        require(areas.size in 1..20) { "A card has between one and twenty areas." }
        val regions = areas.flatMap { it.regions }
        require(areas.all { it.regions.isNotEmpty() } && regions.size <= 2000 && regions.all(ReviewAreaBox::valid)) {
            "Invalid review region"
        }
        val displayedPdf = pdfSha256.lowercase()
        require(displayedPdf.matches(Regex("[0-9a-f]{64}"))) { "Invalid PDF identity" }
        ReviewOutgoingCardFileLock.withLock {
            val snapshot = snapshotForCard(cardId)
            val selected = snapshot.selectedCardRecords[cardId] ?: error("The review card is unavailable")
            val history = snapshot.histories[cardId] ?: emptyHistory()
            val current = parseCard(selected.payload, history.schedule) ?: error("The review card is invalid")
            require(current.pdfSha256.equals(expectedPdfSha256, ignoreCase = true) && current.page == expectedPage) {
                "The card changed. Reopen it before editing its answer areas."
            }
            require(!current.wholeDocument) { "A whole-document card has no areas to edit." }
            val samePdf = current.pdfSha256.equals(displayedPdf, ignoreCase = true)
            val before = current.regions.map(ReviewAreaBox::of)
            val geometryChanged = !samePdf || before.size != regions.size ||
                before.zip(regions).any { (old, new) -> !old.sameAs(new) }
            val linked = current.sourceAnchor != null && current.reanchorState != "needs_attention"
            if (!geometryChanged && linked) {
                if (normalizedQuestion == null || normalizedQuestion == current.question) return@withLock current
                return@withLock updateQuestion(cardId, normalizedQuestion, updatedAt).getOrThrow()
            }
            val placer = checkNotNull(sourcePlacer) { "Verified local source is unavailable." }
            val edit = placer.placement(cardId, "${current.pdfStem}.pdf", displayedPdf, areas.map { area ->
                LocalReviewSources.PlacementArea(area.regions.map(ReviewAreaBox::toRegion), area.text, area.rectangle)
            }, current.sourceAnchor)
            check(replacementSupported || !edit.before.contains(cardId)) { REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED_MESSAGE }
            val page = reviewPrimaryPage(current.page, regions)
            val updated = selected.payload.deepCopy().apply {
                addProperty("revision", (intOrNull("revision") ?: 0) + 1)
                addProperty("updated_at", updatedAt.toString())
                addProperty("origin_id", originId)
                addProperty("schema_version", 4)
                addProperty("source_authority", "client-v1")
                // Fedora places the card from its new markers; an earlier failure no longer applies.
                remove("reanchor"); remove("projection"); remove("source_change")
                val primary = getAsJsonObject("primary")
                normalizedQuestion?.let { primary.addProperty("question", it) }
                fun coordinates(box: ReviewAreaBox) = JsonArray().apply { add(box.left); add(box.top); add(box.right); add(box.bottom) }
                primary.add("rect", coordinates(reviewPrimaryBox(page, regions)))
                if (page != current.page || !samePdf) {
                    primary.addProperty("page", page)
                    primary.addProperty("pdf_sha256", displayedPdf)
                    // The same placeholder a new card gets; Fedora fills in the real fingerprint.
                    primary.addProperty("page_visual_sha256", sha256("pending-page-visual\n$displayedPdf\n$page"))
                }
                primary.add("regions", JsonArray().apply {
                    regions.forEach { box -> add(JsonObject().apply { addProperty("page", box.page); add("rect", coordinates(box)) }) }
                })
                if (primary.get("source_kind")?.takeIf { it.isJsonPrimitive }?.asString == "pdf") primary.remove("source_kind")
                // Placed by its source markers from now on, as the desktop does when it moves a card into a source.
                primary.remove("answer_region_override")
                primary.add("source_anchor", edit.anchor)
                add("source_link", JsonObject().apply { addProperty("state", "linked"); addProperty("source_file", edit.path) })
            }
            // The areas were drawn on the displayed PDF.
            stampCardWrite(updated, current, displayedPdf)
            checkNotNull(parseCard(updated, history.schedule)) { "Invalid review region" }
            withSnapshotMutation {
                if (edit.after == edit.before) persistRecord(outgoingCardFile(cardId), updated)
                else placer.commit(edit, updated, outgoingCardFile(cardId), UUID.randomUUID().toString())
            }
            context?.let { PendingChangesSignal.notifyChanged() }
            checkNotNull(parseCard(updated, history.schedule))
        }
    }

    fun deleteCard(
        cardId: String,
        deletedAt: Instant = Instant.now(),
        deleteEnclosedText: Boolean = false,
    ): Result<File> = runCatching {
        val current = browserCards(now = deletedAt).firstOrNull { it.card.id == cardId }
            ?: error("The review card is unavailable")
        require(current.deleteEventId == null) { "The review card is already deleted" }
        require(!deleteEnclosedText || current.card.canDeleteEnclosedText) {
            "This card has no verified source passage to delete."
        }
        record(cardId, ReviewRating.DELETE, deletedAt,
            deleteSource = if (deleteEnclosedText) current.card.sourceAnchor else null).getOrThrow()
    }

    fun checkManualSourceLink(cardId: String): Result<Unit> = runCatching {
        ReviewOutgoingCardFileLock.withLock {
            val selected = snapshot().selectedCardRecords[cardId] ?: error("The card is unavailable.")
            val card = checkNotNull(parseCard(selected.payload, null))
            check(card.sourceLinkState == "linking_failed") { "The card connection changed. Reopen it." }
            val sourceStore = OfflineSourceBundleStore(checkNotNull(context))
            val filename = "${card.pdfStem}.pdf"
            val pdf = sourceStore.librarySourceMetadata(filename)?.pdfs?.get(filename)
            val projects = (card.sourceFiles + pdf?.segments.orEmpty().filter {
                card.page >= it.startPage && card.page < it.startPage + it.pageCount
            }.flatMap { segment ->
                listOfNotNull(segment.primarySourceFile) + segment.inputs.map { it.sourceFile }
            }).mapNotNull(SourceOwner::of).distinct()
            val locations = projects.mapNotNull { project ->
                sourceStore.annotationIndex.locate("review_card", cardId, project)
                    .takeIf { it.get("state")?.asString == "linked" }
            }
            val path = locations.singleOrNull()?.get("source_file")?.asString
                ?: error("Place one unique start/end marker pair, then wait for the source check to finish.")
            val edit = checkNotNull(localReviewSources).manualLink(cardId, path)
            val updated = selected.payload.deepCopy().apply {
                remove("reanchor"); remove("source_change")
                addProperty("revision", (intOrNull("revision") ?: 0) + 1)
                addProperty("updated_at", Instant.now().toString()); addProperty("origin_id", originId)
                getAsJsonObject("primary").add("source_anchor", edit.anchor)
                add("source_link", JsonObject().apply { addProperty("state", "linked"); addProperty("source_file", path) })
            }
            // The card id may already name its creation's source transaction; each later change needs its own.
            withSnapshotMutation { localReviewSources!!.commit(edit, updated, outgoingCardFile(cardId), UUID.randomUUID().toString()) }
            PendingChangesSignal.notifyChanged()
        }
    }

    /**
     * Removes the markers of deleted cards whose removal was left pending, once the phone's
     * verified source allows the same safe removal a deletion makes. Delete events stay as
     * recorded; a card stops being pending when its markers are gone (withCurrentSourceLocation).
     * Call off the main thread. Returns how many cards' markers were removed.
     */
    internal fun retryPendingMarkerRemovals(): Int {
        val sources = markerSources ?: return 0
        var removed = 0
        for (cardId in dashboardSnapshot().cards.filter(ReviewCard::markerRemovalPending).map(ReviewCard::id)) {
            val anchor = card(cardId)?.takeIf(ReviewCard::markerRemovalPending)?.sourceAnchor ?: continue
            if (runCatching { sources.removeMarkers(cardId, anchor) }.getOrDefault(false)) removed++
        }
        return removed
    }

    fun restoreCard(
        cardId: String,
        restoredAt: Instant = Instant.now(),
    ): Result<File> = Result.failure(IllegalStateException("Deleted review cards cannot be restored"))

    private fun eventTransportFile(event: JsonObject): File {
        val safeOrigin = originId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return File(outgoingEventsRoot, "$safeOrigin/${event.string("event_id")}.json")
    }

    private fun persistEvent(event: JsonObject): File {
        val transportFile = eventTransportFile(event)
        val relativePath = transportFile.relativeTo(outgoingEventsRoot).path
        withSnapshotMutation {
            persistRecord(transportFile, event)
            if (database == null) runCatching {
                writeAtomically(File(root, "events/$relativePath"), event.toString() + "\n")
            }
        }
        context?.let { PendingChangesSignal.notifyChanged() }
        return transportFile
    }

    private fun outgoingCardFile(cardId: String): File {
        val safeOrigin = originId.map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }.joinToString("")
        return File(outgoingCardsRoot, "$safeOrigin/$cardId.json")
    }

    fun pendingChanges(): List<PendingReviewChange> {
        val snapshot = snapshot()
        val cardStems = snapshot.cards.associate { card -> card.id to card.pdfStem }
        val cards = snapshot.outgoingCardPayloads.mapNotNull { payload ->
            val cardId = payload.string("card_id").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            PendingReviewChange(
                entityType = "review_card",
                entityId = cardId,
                pdfStem = payload.getAsJsonObject("primary")?.string("pdf_stem")
                    ?.takeIf(String::isNotBlank),
                createdAtEpochMillis = payload.string("updated_at")
                    .takeIf(String::isNotBlank)
                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    ?: 0L,
            )
        }
        val events = snapshot.outgoingEventPayloads.mapNotNull { payload ->
            val eventId = payload.string("event_id").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            PendingReviewChange(
                entityType = "review_event",
                entityId = eventId,
                pdfStem = payload.string("card_id").let(cardStems::get),
                createdAtEpochMillis = payload.string("reviewed_at")
                    .takeIf(String::isNotBlank)
                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    ?: 0L,
            )
        }
        return (cards + events).distinctBy { change ->
            "${change.entityType}:${change.entityId}"
        }
    }

    private fun selectCardRecords(
        records: List<LoadedReviewObject>,
    ): Map<String, LoadedReviewObject> {
        return records
            .filter { it.payload.string("card_id").isNotBlank() }
            .groupBy { it.payload.string("card_id") }
            .mapValues { (_, candidates) ->
                candidates.maxWith(
                    compareBy<LoadedReviewObject> {
                        it.payload.intOrNull("revision") ?: 0
                    }.thenBy { it.source.precedence }
                        .thenBy { it.payload.string("updated_at") }
                        .thenBy { it.file.path },
                )
            }
    }

    private fun foldHistories(
        loaded: List<LoadedReviewObject>,
        pendingEventIds: Set<String>,
    ): Map<String, FoldedReviewHistory> {
        val unique = loaded
            .filter { it.payload.string("event_id").isNotBlank() }
            .groupBy { it.payload.string("event_id") }
            .mapValues { (_, copies) -> copies.maxBy { it.source.precedence } }
        val validRetractions = unique.values.mapNotNull { retraction ->
            val targetId = retraction.payload.string("retracted_event_id")
                .takeIf(String::isNotBlank) ?: return@mapNotNull null
            val target = unique[targetId] ?: return@mapNotNull null
            val sameCard = retraction.payload.string("card_id").isNotBlank() &&
                retraction.payload.string("card_id") == target.payload.string("card_id")
            val targetIsRating = ReviewRating.entries.any {
                it.wireName == target.payload.string("rating")
            }
            (targetId to retraction.payload.string("event_id")).takeIf {
                sameCard && targetIsRating && target.payload.string("rating") != "delete"
            }
        }
        val retractedEventIds = validRetractions.mapTo(hashSetOf(), Pair<String, String>::first)
        val effectiveRetractionIds = validRetractions.mapTo(hashSetOf(), Pair<String, String>::second)

        return unique.values
            .filterNot { inferredSourceDeletion(it.payload) }
            .groupBy { it.payload.string("card_id") }
            .mapValues { (_, cardEvents) ->
                val reviewedTimes = cardEvents.associate { it.payload.string("event_id") to it.payload.instant("reviewed_at") }
                val ordered = cardEvents.sortedWith(
                    compareBy<LoadedReviewObject> { reviewedTimes[it.payload.string("event_id")] }
                        .thenBy { it.payload.string("event_id") },
                )
                var schedule: ReviewSchedule? = null
                var suggestedInterval = ReviewInterval.suggested(null, null)
                var currentInterval: ReviewInterval? = null
                var reviewCount = 0
                var lapseCount = 0
                var lastRating: ReviewRating? = null
                var lastReviewedAt: Instant? = null
                var deleteEventId: String? = null
                val appliedEventIds = hashSetOf<String>()
                ordered.asSequence()
                    .filterNot { it.payload.string("event_id") in retractedEventIds }
                    .forEach { loadedEvent ->
                        val event = loadedEvent.payload
                        if (event.has("queue_action")) return@forEach
                        val reviewedAt = reviewedTimes[event.string("event_id")] ?: return@forEach
                        if (schedule?.deleted == true) return@forEach
                        if (event.string("interval_action") == "set") {
                            val selected = applyIntervalSelection(event, schedule, reviewedAt) ?: return@forEach
                            schedule = selected
                            appliedEventIds += event.string("event_id")
                            return@forEach
                        }
                        val rating = ReviewRating.entries.firstOrNull {
                            it.wireName == event.string("rating")
                        } ?: return@forEach
                        val scheduledDueAt = event.instant("scheduled_due_at")
                        if (rating == ReviewRating.CUSTOM_INTERVAL && scheduledDueAt?.isAfter(reviewedAt) != true) return@forEach
                        val eventId = event.string("event_id")
                        val previousSchedule = schedule
                        val schedulerVersion = event.int("scheduler_version")
                        schedule = nextSchedule(
                            previousSchedule,
                            rating,
                            reviewedAt,
                            schedulerVersion,
                            scheduledDueAt,
                        )
                        if (rating != ReviewRating.DELETE && rating != ReviewRating.RETAINED) {
                            suggestedInterval = ReviewInterval.suggested(reviewedAt, schedule?.dueAt)
                        }
                        if (rating != ReviewRating.DELETE) currentInterval = ReviewInterval.between(reviewedAt, schedule?.dueAt)
                        appliedEventIds += eventId
                        lastRating = rating
                        lastReviewedAt = reviewedAt
                        if (rating == ReviewRating.DELETE) {
                            deleteEventId = eventId
                        } else {
                            reviewCount += 1
                            if (rating == ReviewRating.AGAIN &&
                                (schedulerVersion < CURRENT_SCHEDULER_VERSION ||
                                    previousSchedule?.intervalDays?.let { it > 0 } == true)
                            ) {
                                lapseCount += 1
                            }
                        }
                    }
                FoldedReviewHistory(
                    schedule = schedule,
                    suggestedInterval = suggestedInterval,
                    currentInterval = currentInterval,
                    reviewCount = reviewCount,
                    lapseCount = lapseCount,
                    lastRating = lastRating,
                    lastReviewedAt = lastReviewedAt,
                    deleteEventId = deleteEventId,
                    entries = ordered.map { loadedEvent ->
                        val event = loadedEvent.payload
                        val eventId = event.string("event_id")
                        ReviewHistoryEntry(
                            eventId = eventId,
                            rating = event.string("rating").takeIf(String::isNotBlank),
                            reviewAction = event.string("review_action").takeIf(String::isNotBlank),
                            queueAction = event.string("queue_action").takeIf(String::isNotBlank),
                            scheduledDueAt = event.instant("scheduled_due_at"),
                            skipUntil = event.instant("skip_until"),
                            skipId = event.string("skip_id").takeIf(String::isNotBlank),
                            intervalAction = event.string("interval_action").takeIf(String::isNotBlank),
                            intervalAmount = event.string("interval_amount").takeIf(String::isNotBlank),
                            intervalUnit = event.string("interval_unit").takeIf(String::isNotBlank),
                            intervalDate = event.string("interval_date").takeIf(String::isNotBlank),
                            reviewTags = event.explicitReviewTags(),
                            reviewTagsRecorded = event.recordsReviewTags(),
                            groupNotes = event.reviewGroupNotes(),
                            contextNotes = ReviewContextNotes.parse(event),
                            phases = ReviewPhases.parse(event),
                            reviewedAt = reviewedTimes[eventId],
                            originId = event.string("origin_id"),
                            revision = event.intOrNull("revision"),
                            updatedAt = event.instant("updated_at"),
                            retractedEventId = event.string("retracted_event_id")
                                .takeIf(String::isNotBlank),
                            undoneEventId = event.string("undone_event_id").takeIf(String::isNotBlank),
                            pdfSha256 = ReviewVersionStamp.of(event)?.pdfSha256,
                            retracted = eventId in retractedEventIds,
                            effective = eventId in appliedEventIds || eventId in effectiveRetractionIds,
                            pendingSync = eventId in pendingEventIds,
                            sourcePath = loadedEvent.file.path,
                        )
                    },
                )
            }
    }

    /**
     * Returns a process-memory projection of the JSON review records.
     *
     * File metadata is checked on every entry to detect atomic sync replacement, copy, deletion,
     * and local writes. A changed tree is parsed once, then checked again before publication so a
     * snapshot built across two different filesystem generations is discarded.
     */
    private fun snapshot(pdfStem: String? = null): ReviewSnapshot {
        migrateLegacySkips()
        database?.let { return databaseSnapshot(it) }
        val snapshotSlot = snapshotSlot(pdfStem)
        var inventory = snapshotInventory(pdfStem)
        var locationRevision = annotationIndex?.revision ?: 0
        snapshotSlot.cached?.takeIf { it.inventory == inventory && it.locationRevision == locationRevision }?.let { return it.snapshot }

        return synchronized(snapshotSlot.monitor) {
            while (true) {
                snapshotSlot.cached
                    ?.takeIf { it.inventory == inventory && it.locationRevision == locationRevision }
                    ?.let { return@synchronized it.snapshot }

                snapshotSlot.buildCount.incrementAndGet()
                val built = buildSnapshot(inventory)
                projectIndex.persist()
                val verifiedInventory = snapshotInventory(pdfStem)
                val verifiedRevision = annotationIndex?.revision ?: 0
                if (verifiedInventory == inventory && verifiedRevision == locationRevision) {
                    snapshotSlot.cached = CachedReviewSnapshot(inventory, built, locationRevision)
                    return@synchronized built
                }
                locationRevision = verifiedRevision
                inventory = verifiedInventory
            }
            error("Unreachable")
        }
    }

    private fun buildSnapshot(inventory: ReviewSnapshotInventory): ReviewSnapshot {
        val legacyCards = loadReviewObjects(inventory.legacyCards.files, ReviewRecordSource.LEGACY)
        val incomingCards = loadReviewObjects(inventory.incomingCards.files, ReviewRecordSource.SERVER)
        val outgoingCards = loadReviewObjects(inventory.outgoingCards.files, ReviewRecordSource.OUTGOING)
        val legacyEvents = loadReviewObjects(inventory.legacyEvents.files, ReviewRecordSource.LEGACY)
        val incomingEvents = loadReviewObjects(inventory.incomingEvents.files, ReviewRecordSource.SERVER)
        val outgoingEvents = loadReviewObjects(inventory.outgoingEvents.files, ReviewRecordSource.OUTGOING)
        val exclusions = (
            loadReviewObjects(inventory.legacyExclusions.files, ReviewRecordSource.LEGACY) +
                loadReviewObjects(inventory.incomingExclusions.files, ReviewRecordSource.SERVER)
            ).map(LoadedReviewObject::payload)
        return buildSnapshotRecords(legacyCards, incomingCards, outgoingCards, legacyEvents, incomingEvents, outgoingEvents, exclusions)
    }

    private fun buildSnapshotRecords(
        legacyCards: List<LoadedReviewObject>, incomingCards: List<LoadedReviewObject>, outgoingCards: List<LoadedReviewObject>,
        legacyEvents: List<LoadedReviewObject>, incomingEvents: List<LoadedReviewObject>, outgoingEvents: List<LoadedReviewObject>,
        exclusions: List<JsonObject>,
        resolveLocations: Boolean = true,
    ): ReviewSnapshot {
        val outgoingEventIds = outgoingEvents
            .mapTo(hashSetOf()) { it.payload.string("event_id") }
        val histories = foldHistories(
            legacyEvents + incomingEvents + outgoingEvents,
            outgoingEventIds,
        )
        val selectedCardRecords = selectCardRecords(legacyCards + incomingCards + outgoingCards)
        val queueEvents = (legacyEvents + incomingEvents + outgoingEvents)
            .map(LoadedReviewObject::payload).filter { it.has("queue_action") }
        val skippedUntilByCardId = reviewSkippedUntil(queueEvents.filter { event ->
            event.string("queue_action") != "skip" || histories[event.string("card_id")]?.lastReviewedAt?.let { last ->
                event.instant("reviewed_at")?.let { !it.isBefore(last) } == true
            } != false
        })
        val pendingMarkerRemovals = (legacyEvents + incomingEvents + outgoingEvents).map(LoadedReviewObject::payload)
            .filter { it.string("rating") == "delete" && it.string("marker_removal") == "pending" }
            .mapTo(hashSetOf()) { it.string("card_id") }
        val retired = reviewRetiredCards((legacyEvents + incomingEvents + outgoingEvents).map(LoadedReviewObject::payload))
        val cards = selectedCardRecords.values.mapNotNull { record ->
            val cardId = record.payload.string("card_id")
            parseCard(record.payload, histories[cardId]?.schedule)?.copy(skippedUntil = skippedUntilByCardId[cardId], markerRemovalPending = cardId in pendingMarkerRemovals,
                retiredAt = retired[cardId]?.retiredAt, retireEventId = retired[cardId]?.retireEventId)?.let { card -> if (resolveLocations) withCurrentSourceLocation(card) else card }
        }

        return ReviewSnapshot(
            queueEvents = queueEvents,
            selectedCardRecords = selectedCardRecords,
            histories = histories,
            cards = cards,
            cardsById = cards.associateBy(ReviewCard::id),
            exclusions = exclusions,
            outgoingCardPayloads = outgoingCards.map(LoadedReviewObject::payload),
            outgoingEventPayloads = outgoingEvents.map(LoadedReviewObject::payload),
            outgoingCardIds = outgoingCards.mapTo(hashSetOf()) { it.payload.string("card_id") },
            outgoingEventCardIds = outgoingEvents.mapTo(hashSetOf()) { it.payload.string("card_id") },
        )
    }

    private fun withCurrentSourceLocation(card: ReviewCard): ReviewCard {
        if (card.sourceAnchor?.get("version")?.asInt != 2) return card
        val location = annotationIndex?.rememberedLocation("review_card", card.id, SourceOwner.of(card.sourceFiles.firstOrNull().orEmpty()).orEmpty(), card.sourceFiles.firstOrNull())
        return when (location?.get("state")?.asString) {
            "linked" -> {
                val path = location.get("source_file").asString
                val evidence = annotationIndex?.reviewEvidence(card.id, SourceOwner.of(path).orEmpty(), path).orEmpty()
                val changed = reviewPassagesChanged(card.sourceAnchor.getAsJsonArray("segments").map { it.asJsonObject }, evidence)
                card.copy(sourceFiles = listOf(path), sourceLocationState = "linked", reanchorState = if (changed) "needs_attention" else card.reanchorState,
                    reanchorReason = if (changed) "source_content_changed_or_unavailable" else card.reanchorReason)
            }
            // A removal left pending is done once no checked source holds the card's markers,
            // whoever removed them. The recorded delete event itself is never rewritten.
            "missing" -> card.copy(sourceLocationState = "missing", markerRemovalPending = false)
            "ambiguous" -> if (card.sourceAnchor.getAsJsonArray("segments").size() == 1)
                card.copy(sourceLocationState = "ambiguous", reanchorState = "needs_attention", reanchorReason = "source_marker_ambiguous") else card.copy(sourceLocationState = "ambiguous")
            "unavailable" -> card.copy(sourceLocationState = "unavailable")
            else -> card
        }
    }

    private fun withCurrentSourceLocations(snapshot: ReviewSnapshot): ReviewSnapshot {
        val cards = snapshot.cards.map(::withCurrentSourceLocation)
        return snapshot.copy(cards = cards, cardsById = cards.associateBy { it.id })
    }

    private fun emptySnapshot() = ReviewSnapshot(emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyList(), emptyList(), emptyList(), emptySet(), emptySet())

    /** Dashboard reads do not deserialize full snapshots or rewrite every ReviewState row. */
    private fun dashboardSnapshot(): ReviewSnapshot {
        migrateLegacySkips()
        val db = database ?: return snapshot()
        return synchronized(snapshotSlot.monitor) {
            db.transaction {
                val generation = db.generation()
                var dashboard = snapshotSlot.dashboard?.takeIf { snapshotSlot.dashboardGeneration == generation }
                if (dashboard == null) {
                    val saved = db.savedDashboards(ReviewSnapshotCodec.DASHBOARD_VERSION).mapNotNull { (id, payload) ->
                        ReviewSnapshotCodec.decodeDashboard(payload)?.takeIf { part -> part.cards.all { it.id == id } }?.let { id to it }
                    }.toMap()
                    val ids = db.cardIds()
                    dashboard = if (db.dirtyCards().isEmpty() && ids.all { it in saved }) {
                        ReviewDashboard(saved.values.flatMap { it.cards }, saved.values.firstOrNull()?.exclusions.orEmpty())
                    } else {
                        // First upgrade, dirty records or disposable-cache corruption: use the authority once.
                        databaseSnapshot(db)
                        snapshotSlot.databaseCards.forEach { (id, part) ->
                            db.saveDashboard(id, ReviewSnapshotCodec.DASHBOARD_VERSION, ReviewSnapshotCodec.encodeDashboard(part))
                        }
                        ReviewDashboard(snapshotSlot.databaseCards.values.flatMap { it.cards }, snapshotSlot.databaseCards.values.firstOrNull()?.exclusions.orEmpty())
                    }
                    snapshotSlot.dashboard = dashboard
                    snapshotSlot.dashboardGeneration = generation
                }
                val current = checkNotNull(dashboard)
                var cards: List<ReviewCard>
                var revision: Long
                do {
                    revision = annotationIndex?.revision ?: 0
                    cards = current.cards.map(::withCurrentSourceLocation)
                } while ((annotationIndex?.revision ?: 0) != revision)
                emptySnapshot().copy(cards = cards, exclusions = current.exclusions)
            }
        }
    }

    private fun databaseSnapshot(db: ReviewDatabase): ReviewSnapshot = synchronized(snapshotSlot.monitor) {
        db.transaction {
            val generation = db.generation()
            val locationRevision = annotationIndex?.revision ?: 0
            val cached = snapshotSlot.databaseSnapshot
            if (cached != null && snapshotSlot.databaseGeneration == generation && snapshotSlot.locationRevision == locationRevision) return@transaction cached
            val full = cached == null || generation < snapshotSlot.databaseGeneration
            if (full) {
                snapshotSlot.databaseCards.clear()
                for ((id, payload) in db.savedSnapshots(ReviewSnapshotCodec.VERSION)) {
                    ReviewSnapshotCodec.decode(payload)?.let { snapshotSlot.databaseCards[id] = it }
                }
            }
            val ids = if (full) ((db.cardIds() - snapshotSlot.databaseCards.keys) + db.dirtyCards()).distinct() else db.dirtyCards()
            val allRecords = if (full && snapshotSlot.databaseCards.isEmpty()) db.records() else emptyList()
            val groups = allRecords.groupBy { it.cardId }
            val exclusions = allRecords.filter { it.kind == "exclusions" }
            for (id in ids) {
                val part = buildCardPart(if (allRecords.isNotEmpty()) groups[id].orEmpty() + exclusions else db.records(id))
                db.saveSnapshot(id, ReviewSnapshotCodec.VERSION, ReviewSnapshotCodec.encode(part))
                db.saveDashboard(id, ReviewSnapshotCodec.DASHBOARD_VERSION, ReviewSnapshotCodec.encodeDashboard(part))
                snapshotSlot.buildCount.incrementAndGet()
                snapshotSlot.databaseCards[id] = part
            }
            // Location changes affect eligibility, not history; never replay every event for a source scan.
            val stateIds = if (full || snapshotSlot.locationRevision != locationRevision) snapshotSlot.databaseCards.keys else ids
            val everyCard = stateIds.size > ids.size
            val rows = linkedMapOf<String, ReviewStateRow>()
            for (id in stateIds) {
                val part = withCurrentSourceLocations(snapshotSlot.databaseCards.getValue(id))
                val card = part.cardsById[id]
                val active = card != null && activeCards(part).any { it.id == id }
                val due = card?.schedule?.let { schedule ->
                    if (card.bucket == ReviewCardBucket.LEARNING && !schedule.fixedInterval)
                        schedule.dueAt.minus(LEARN_AHEAD_LIMIT_MINUTES, ChronoUnit.MINUTES) else schedule.dueAt
                } ?: card?.initialDueAt
                fun millis(value: Instant?) = value?.let { runCatching { it.toEpochMilli() }.getOrDefault(if (it < Instant.EPOCH) Long.MIN_VALUE else Long.MAX_VALUE) } ?: Long.MIN_VALUE
                rows[id] = reviewStateRow(card, active, maxOf(millis(due), millis(card?.skippedUntil)),
                    part.histories[id]?.reviewCount ?: 0, reviewCurrentMark(part.histories[id]?.entries.orEmpty()).orEmpty())
            }
            // After a source change every card's row is computed again, but a source scan moves few
            // cards: writing all of them was one statement per card of the Library. The stored rows
            // are read in one query, in this transaction, and only the others are written
            // ([reviewStatesToWrite]). A few changed cards are written without reading.
            val toWrite = reviewStatesToWrite(rows, ids.toHashSet(), if (everyCard) db.cleanStateRows() else emptyMap())
            for (id in toWrite) db.saveState(id, rows.getValue(id))
            snapshotSlot.stateWriteCount.addAndGet(toWrite.size.toLong())
            snapshotSlot.stateWritesSkipped.addAndGet((rows.size - toWrite.size).toLong())
            val parts = snapshotSlot.databaseCards.values
            val cards = parts.flatMap { it.cards }.map(::withCurrentSourceLocation)
            val result = ReviewSnapshot(parts.flatMap { it.queueEvents }, parts.flatMap { it.selectedCardRecords.entries }.associate { it.toPair() },
                parts.flatMap { it.histories.entries }.associate { it.toPair() }, cards, cards.associateBy { it.id },
                parts.firstOrNull()?.exclusions.orEmpty(), parts.flatMap { it.outgoingCardPayloads }, parts.flatMap { it.outgoingEventPayloads },
                parts.flatMapTo(hashSetOf()) { it.outgoingCardIds }, parts.flatMapTo(hashSetOf()) { it.outgoingEventCardIds })
            if ((annotationIndex?.revision ?: 0) != locationRevision) {
                // A background source check finished during this fold; publish one source revision only.
                snapshotSlot.databaseSnapshot = null
                return@transaction databaseSnapshot(db)
            }
            snapshotSlot.databaseGeneration = generation
            snapshotSlot.locationRevision = locationRevision
            snapshotSlot.databaseSnapshot = result
            result
        }
    }

    private fun snapshotInventory(pdfStem: String? = null): ReviewSnapshotInventory {
        val inventory = ReviewSnapshotInventory(
        legacyCards = reviewFileTree(File(root, "cards")),
        incomingCards = reviewFileTree(File(incomingRoot, "cards")),
        outgoingCards = reviewFileTree(outgoingCardsRoot),
        legacyEvents = reviewFileTree(File(root, "events")),
        incomingEvents = reviewFileTree(File(incomingRoot, "events")),
        outgoingEvents = reviewFileTree(outgoingEventsRoot),
        legacyExclusions = reviewFileTree(File(root, "exclusions")),
        incomingExclusions = reviewFileTree(File(incomingRoot, "exclusions")),
        skips = reviewFileTree(skipsDirectory),
        sourceLocationRevision = annotationIndex?.revision ?: 0,
        )
        if (pdfStem == null) return inventory
        val trees = listOf(inventory.legacyCards, inventory.incomingCards, inventory.outgoingCards,
            inventory.legacyEvents, inventory.incomingEvents, inventory.outgoingEvents,
            inventory.legacyExclusions, inventory.incomingExclusions)
        val paths = projectIndex.filesForProject(trees.flatMap { it.files.map { state -> state.file } }, pdfStem)
        fun scoped(tree: ReviewFileTree) = ReviewFileTree(emptyList(), tree.files.filter { it.file.path in paths })
        return ReviewSnapshotInventory(scoped(inventory.legacyCards), scoped(inventory.incomingCards),
            scoped(inventory.outgoingCards), scoped(inventory.legacyEvents), scoped(inventory.incomingEvents),
            scoped(inventory.outgoingEvents), scoped(inventory.legacyExclusions), scoped(inventory.incomingExclusions),
            ReviewFileTree(emptyList(), emptyList()), inventory.sourceLocationRevision)
    }

    private fun reviewFileTree(directory: File): ReviewFileTree {
        if (!directory.isDirectory) return ReviewFileTree(emptyList(), emptyList())
        val directories = mutableListOf<ReviewDirectoryState>()
        val files = mutableListOf<ReviewFileState>()
        directory.walkTopDown().forEach { entry ->
            when {
                entry.isDirectory -> directories += ReviewDirectoryState(
                    path = entry.path,
                    lastModified = entry.lastModified(),
                )
                entry.isFile && entry.extension.equals("json", ignoreCase = true) ->
                    files += ReviewFileState(
                        file = entry,
                        length = entry.length(),
                        lastModified = entry.lastModified(),
                    )
            }
        }
        return ReviewFileTree(
            directories = directories.sortedBy(ReviewDirectoryState::path),
            files = files.sortedBy { it.file.path },
        )
    }

    private fun loadReviewObjects(
        files: List<ReviewFileState>,
        source: ReviewRecordSource,
    ): List<LoadedReviewObject> = files.mapNotNull { state ->
        runCatching {
            LoadedReviewObject(
                payload = projectIndex.payload(state.file) ?: return@mapNotNull null,
                file = state.file,
                source = source,
            )
        }.getOrNull()
    }

    private fun detachedCard(card: ReviewCard): ReviewCard = card.copy(
        rect = copyReviewRect(card.rect),
        answerRegions = card.answerRegions.map { it.copy(rect = copyReviewRect(it.rect)) },
        sourceFiles = card.sourceFiles.toList(),
    )

    private fun <T> withSnapshotMutation(action: () -> T): T = synchronized(snapshotSlot.monitor) {
        recoverSources()
        val db = database
        if (db != null) {
            try {
                db.transaction {
                    val generation = db.generation()
                    val result = action()
                    // Persist affected schedules in the same transaction as their events. A mutation
                    // that changed nothing (an unchanged Sync) must not load every card's snapshot.
                    if (db.generation() != generation || db.dirtyCards().isNotEmpty()) databaseSnapshot(db)
                    result
                }
            } catch (failure: Throwable) {
                snapshotSlot.databaseSnapshot = null
                snapshotSlot.databaseCards.clear()
                throw failure
            }
        } else {
            snapshotSlot.cached = null
            try { action() } finally { snapshotSlot.cached = null }
        }
    }

    internal fun dropDatabaseCacheForTesting() = synchronized(snapshotSlot.monitor) {
        snapshotSlot.databaseSnapshot = null
        snapshotSlot.databaseCards.clear()
        snapshotSlot.dashboard = null
        snapshotSlot.dashboardGeneration = -1
    }

    internal fun loadedFullCardCountForTesting(): Int = synchronized(snapshotSlot.monitor) { snapshotSlot.databaseCards.size }

    internal fun snapshotBuildCountForTesting(): Long = snapshotSlot.buildCount.get()

    /** Card state rows written and left as they were since the process started, for the Sync timing log. */
    internal fun stateWriteCounts(): Pair<Long, Long> = snapshotSlot.stateWriteCount.get() to snapshotSlot.stateWritesSkipped.get()

    internal fun indexSyncedRecord(file: File, payload: JsonObject) { projectIndex.record(file, payload) }
    internal fun persistProjectIndex() { projectIndex.persist() }
    internal fun projectSnapshotBuildCountForTesting(pdfStem: String): Long = snapshotSlot(pdfStem).buildCount.get()


    fun reviewPdfFile(card: ReviewCard): File? {
        return com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().fileForId(card.pdfDocumentId.ifEmpty { card.documentId })
    }

    fun createBasicCards(
        pdfStem: String,
        pdfSha256: String,
        pageFingerprints: List<String>,
        candidates: com.google.gson.JsonArray,
        createdAt: Instant = Instant.now(),
        documentId: String = "",
    ): Result<BasicReviewResult> = runCatching {
        val registeredDocumentId = documentId.ifEmpty { com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().identifier("$pdfStem.pdf") }
        require(com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore.validId(registeredDocumentId)) { "Invalid document identifier" }
        require(pdfStem.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Invalid PDF identity" }
        require(pdfSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid PDF identity" }
        require(pageFingerprints.isNotEmpty() && pageFingerprints.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "Invalid page fingerprints"
        }
        var added = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        ReviewOutgoingCardFileLock.withLock {
            // Read inside the writer lock so two generation requests cannot create duplicates.
            val existing = snapshot(pdfStem).cards
            val timestamp = createdAt.toString()
            fun passageId(span: JsonObject) = span.string("source_file") + "\n" + span.string("content_sha256")
            val covered = existing.filter { it.pdfStem == pdfStem && it.schedule?.deleted != true }
                .flatMap { it.sourceAnchor?.getAsJsonArray("segments")?.map { s -> passageId(s.asJsonObject) }.orEmpty() }.toSet()
            withSnapshotMutation {
                loop@ for (value in candidates) {
                    val candidate = value.asJsonObject.deepCopy()
                    val sourceAnchor = candidate.getAsJsonObject("source_anchor")
                    val spans = sourceAnchor?.getAsJsonArray("segments")
                    if (sourceAnchor != null && spans?.size() != 1) {
                        errors += "Generate again after Sync: each card must select one continuous source passage."; continue
                    }
                    if (spans != null && spans.size() > 0) {
                        val remaining = spans.filter { passageId(it.asJsonObject) !in covered }
                        if (remaining.isEmpty()) { skipped++; continue }
                        if (remaining.size != spans.size() && remaining.all { it.asJsonObject.has("regions") }) {
                            sourceAnchor.add("segments", com.google.gson.JsonArray().apply { remaining.forEach { add(it) } })
                            candidate.add("regions", com.google.gson.JsonArray().apply {
                                remaining.forEach { span -> span.asJsonObject.getAsJsonArray("regions").forEach { add(it) } }
                            })
                            val identity = "basic-source-subset-v1\n" + remaining.joinToString("") { passageId(it.asJsonObject) + "\n" }
                            candidate.addProperty("key", java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                                .joinToString("") { "%02x".format(it) })
                        }
                    }
                    val regions = candidate.getAsJsonArray("regions")
                    val key = candidate.get("key").asString
                    require(key.matches(Regex("[0-9a-f]{64}")) && regions.size() > 0) { "Invalid passage plan" }
                    regions.forEach {
                        val r = it.asJsonObject
                        val rect = r.getAsJsonArray("rect").map { n -> n.asFloat }
                        require(r.get("page").asInt in 1..pageFingerprints.size && rect.size == 4 &&
                            rect.all { n -> n.isFinite() && n in 0f..1f } && rect[0] < rect[2] && rect[1] < rect[3]) { "Invalid passage location" }
                    }
                    if (existing.any { it.pdfStem == pdfStem && it.basicPassageKey == key }) { skipped++; continue }
                    val first = regions[0].asJsonObject
                    val page = first.get("page").asInt
                    val anchor = candidate.getAsJsonObject("source_anchor")
                    val id = UUID.randomUUID().toString()
                    val record = JsonObject().apply {
                        addProperty("schema_version", 4)
                        addProperty("card_type", "visual_region")
                        addProperty("card_id", id)
                        addProperty("generation_method", if (anchor == null) "basic_passages_v1" else "basic_passages_v2")
                        addProperty("basic_passage_key", key)
                        addProperty("status", "approved")
                        addProperty("revision", 1)
                        addProperty("created_at", timestamp)
                        addProperty("updated_at", timestamp)
                        addProperty("approved_at", timestamp)
                        addProperty("origin_id", originId)
                        add("reanchor", JsonObject().apply {
                            addProperty("state", if (anchor == null) "succeeded" else "pending")
                            addProperty("strategy", if (anchor == null) "basic_pdf_snapshot_v1" else "source_markers_v2")
                            addProperty("target_pdf_sha256", pdfSha256)
                        })
                        add("primary", JsonObject().apply {
                            addProperty("document_id", registeredDocumentId)
                            addProperty("pdf_stem", pdfStem)
                            addProperty("pdf_sha256", pdfSha256)
                            addProperty("page", page)
                            add("rect", first.getAsJsonArray("rect").deepCopy())
                            add("regions", regions.deepCopy())
                            if (anchor == null) {
                                addProperty("source_kind", "pdf")
                                context?.let(::OfflineSourceBundleStore)
                                    ?.reviewPdfComponentOrigin("$pdfStem.pdf", pdfSha256, page)
                                    ?.let { add("component_origin", it) }
                            } else add("source_selection", anchor.deepCopy())
                            addProperty("question", "Recall this section.")
                            addProperty("page_visual_sha256", pageFingerprints[page - 1])
                            addProperty("page_visual_fingerprint_method", BASIC_REVIEW_FINGERPRINT_METHOD)
                        })
                    }
                    try {
                        persistRecord(outgoingCardFile(id), record)
                        added++
                    } catch (error: Exception) {
                        errors += error.message ?: "Could not save the review card."
                        break@loop
                    }
                }
            }
        }
        if (added > 0) context?.let { PendingChangesSignal.notifyChanged() }
        BasicReviewResult(added, skipped, errors)
    }

    fun createManualCard(
        pdfStem: String,
        pdfSha256: String,
        page: Int,
        rect: RectF,
        question: String,
        createdAt: Instant = Instant.now(),
        answerRegions: List<ReviewAnswerRegion> = emptyList(),
        selectedText: String? = null,
        sourceAvailable: Boolean? = null,
        documentId: String = "",
        firstDueDays: Long = 0,
        firstDueAt: Instant? = null,
        // Separately selected areas, each with its own text; two or more link as several passages.
        answerAreas: List<Pair<List<ReviewAnswerRegion>, String?>> = emptyList(),
        // Every page of the PDF is the answer; the card is placed by page count, never by markers.
        wholeDocument: Boolean = false,
        // The person chose to save a single area that could not be linked and place its markers by hand.
        placeMarkersByHand: Boolean = false,
        // The phone cannot tell whether the PDF has source (sourceAvailable null) and an area could not be
        // linked: the person chose to save the card unmarked; Fedora links it, or keeps it PDF-only.
        linkAtNextSync: Boolean = false,
    ): Result<ReviewCard> = runCatching {
        require(!linkAtNextSync || sourceAvailable == null) { "Only a document whose source is unknown can be linked at the next Sync." }
        require(firstDueDays in 0..3650) { "The first review time is invalid" }
        require(firstDueAt == null || firstDueAt.isAfter(createdAt)) { "The first review time must be in the future" }
        val registeredDocumentId = documentId.ifEmpty { com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().identifier("$pdfStem.pdf") }
        require(com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore.validId(registeredDocumentId)) { "Invalid document identifier" }
        val normalizedStem = pdfStem.trim()
        val normalizedPdfSha256 = pdfSha256.lowercase()
        val normalizedQuestion = question.trim()
        require(normalizedStem.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            "Invalid PDF identity"
        }
        require(normalizedPdfSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid PDF identity"
        }
        require(page > 0) { "Invalid PDF page" }
        require(validReviewRect(rect)) { "Invalid review region" }
        require(normalizedQuestion.isNotEmpty() && normalizedQuestion.length <= MAX_QUESTION_LENGTH) {
            "The question must contain between 1 and $MAX_QUESTION_LENGTH characters"
        }
        require(!wholeDocument || answerRegions.isNotEmpty()) { "Invalid review region" }
        require(
            wholeDocument || snapshot(normalizedStem).cards.none { existing ->
                !existing.wholeDocument && existing.pdfStem == normalizedStem && existing.page == page &&
                    intersectionOverUnion(existing.rect, rect) >= 0.5f
            }
        ) { "A review card already covers this region" }

        require(answerRegions.size <= 2000 && answerRegions.all { region ->
            val r = region.rect
            region.page > 0 && listOf(r.left, r.top, r.right, r.bottom).all { it.isFinite() && it in 0f..1f } &&
                r.right > r.left && r.bottom > r.top
        }) { "Invalid review text selection" }
        require(selectedText == null || (selectedText.isNotBlank() && selectedText.length <= 500_000))

        val cardId = UUID.randomUUID().toString()
        val timestamp = createdAt.toString()
        val pageVisualPlaceholder = sha256(
            "pending-page-visual\n$normalizedPdfSha256\n$page"
        )
        val primary = JsonObject().apply {
            addProperty("document_id", registeredDocumentId)
            addProperty("pdf_stem", normalizedStem)
            addProperty("pdf_sha256", normalizedPdfSha256)
            addProperty("page", page)
            add("rect", com.google.gson.JsonArray().apply {
                add(rect.left)
                add(rect.top)
                add(rect.right)
                add(rect.bottom)
            })
            if (answerRegions.isNotEmpty()) add("regions", com.google.gson.JsonArray().apply {
                answerRegions.forEach { region -> add(JsonObject().apply {
                    addProperty("page", region.page)
                    add("rect", com.google.gson.JsonArray().apply {
                        add(region.rect.left); add(region.rect.top); add(region.rect.right); add(region.rect.bottom)
                    })
                }) }
            })
            selectedText?.let { addProperty("selected_text", it) }
            addProperty("question", normalizedQuestion)
            addProperty("page_visual_sha256", pageVisualPlaceholder)
            if (wholeDocument) addProperty("scope", "document")
            else if (sourceAvailable == false) {
                addProperty("source_kind", "pdf")
                context?.let(::OfflineSourceBundleStore)
                    ?.reviewPdfComponentOrigin("$normalizedStem.pdf", normalizedPdfSha256, page)
                    ?.let { add("component_origin", it) }
            }
        }
        val record = JsonObject().apply {
            addProperty("schema_version", if (answerRegions.isEmpty()) SCHEMA_VERSION else 4)
            addProperty("card_type", "visual_region")
            addProperty("card_id", cardId)
            addProperty("status", "approved")
            addProperty("created_at", timestamp)
            (firstDueAt ?: if (firstDueDays > 0) createdAt.plus(firstDueDays, ChronoUnit.DAYS) else null)
                ?.let { addProperty("initial_due_at", it.toString()) }
            addProperty("updated_at", timestamp)
            addProperty("revision", 1)
            addProperty("origin_id", originId)
            add("primary", primary)
            addProperty("source_authority", "client-v1")
        }
        val link = JsonObject()
        // One area per separately selected passage; a single area is the selection or the box.
        val placementAreas = if (answerAreas.size > 1) answerAreas.map { (regions, text) ->
            LocalReviewSources.PlacementArea(regions, text?.takeIf(String::isNotBlank), rectangle = text.isNullOrBlank() && regions.size == 1)
        } else listOf(LocalReviewSources.PlacementArea(answerRegions.ifEmpty { listOf(ReviewAnswerRegion(page, rect)) },
            selectedText?.takeIf(String::isNotBlank), rectangle = selectedText.isNullOrBlank()))
        val areaPages = placementAreas.map { area -> area.regions.minOf { it.page } }
        // Marking by hand places one marker pair, so only a single area may choose it.
        val byHand = placeMarkersByHand && placementAreas.size == 1
        fun refusal(error: Throwable) = error as? com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException
            ?: com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException(0, placementAreas.size, areaPages.first(),
                error.message.orEmpty().ifBlank { "Verified source mapping is unavailable." })
        val edit = if (wholeDocument) {
            link.addProperty("state", "whole_document")
            null
        } else if (sourceAvailable == false) {
            link.addProperty("state", "pdf_without_source")
            null
        } else runCatching {
            // A document built from source: the card is linked, one segment per area, or not saved.
            val sources = checkNotNull(sourcePlacer) { "Verified local source is unavailable." }
            sources.placement(cardId, "$normalizedStem.pdf", normalizedPdfSha256, placementAreas, previous = null)
        }.fold(onSuccess = { result ->
            link.addProperty("state", "linked")
            primary.add("source_anchor", result.anchor)
            result
        }, onFailure = { error ->
            if (linkAtNextSync) {
                // No markers and no PDF-only kind: Fedora's repair links the card from its areas.
                link.addProperty("state", REVIEW_PENDING_SERVER_LINK)
                link.addProperty("reason", refusal(error).reason)
                return@fold null
            }
            if (!byHand) throw refusal(error)
            link.addProperty("state", "linking_failed")
            sourcePlacer?.fallbackSource?.takeIf(String::isNotBlank)?.let { link.addProperty("source_file", it) }
            record.add("reanchor", JsonObject().apply {
                addProperty("state", "needs_attention"); addProperty("strategy", "local_source_v1"); addProperty("reason", "manual_markers_required")
            })
            val reason = (error as? com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException)?.reason ?: error.message
            link.addProperty("reason", "$reason Place the start/end markers manually for card $cardId.")
            null
        })
        record.add("source_link", link)
        // The card was made on the displayed PDF (feedback version stamps, as on the desktop).
        ReviewVersionStamp.put(record, runCatching {
            versionStamper?.stampDocument(registeredDocumentId, normalizedStem, normalizedPdfSha256,
                sourceBuiltFallback = edit != null || sourceAvailable == true)
        }.getOrNull())
        ReviewOutgoingCardFileLock.withLock {
            withSnapshotMutation {
                if (edit != null) {
                    try { sourcePlacer!!.commit(edit, record, outgoingCardFile(cardId)) }
                    catch (failure: Exception) {
                        if (sourcePlacer!!.hasRecoveryRecord(cardId)) throw failure
                        primary.remove("source_anchor"); record.remove("source_change")
                        if (linkAtNextSync) {
                            link.addProperty("state", REVIEW_PENDING_SERVER_LINK)
                            link.addProperty("reason", refusal(failure).reason)
                            persistRecord(outgoingCardFile(cardId), record)
                            return@withSnapshotMutation
                        }
                        // Nothing was saved; the person adjusts the areas or chooses to place markers by hand.
                        if (!byHand) throw refusal(failure)
                        link.addProperty("state", "linking_failed")
                        link.addProperty("reason", "${failure.message} Place the markers manually for card $cardId.")
                        record.add("reanchor", JsonObject().apply { addProperty("state", "needs_attention"); addProperty("strategy", "local_source_v1") })
                        persistRecord(outgoingCardFile(cardId), record)
                    }
                } else persistRecord(outgoingCardFile(cardId), record)
            }
        }
        context?.let { PendingChangesSignal.notifyChanged() }
        checkNotNull(parseCard(record, schedule = null))
    }

    /**
     * A card made from a selection in the HTML view (thoughts-html-view-SPEC.md, "New cards and
     * comments from the HTML view"): no markers are placed on the phone. Its primary records
     * `created_in: "html"`, the edition, the text quote and (exact Markdown selections) the source
     * range; the card waits for Fedora to link it at the next Sync (`pending_server_link`), which
     * inserts the markers, so the next build gives it its PDF areas. Until then it has no PDF
     * geometry at all: no page, rectangle, regions or page fingerprint are stored (always-linked
     * cards: nothing may be mistaken for a PDF placement).
     */
    fun createHtmlCard(
        pdfStem: String,
        pdfSha256: String,
        question: String,
        html: JsonObject,
        selectedText: String,
        createdAt: Instant = Instant.now(),
        documentId: String = "",
        firstDueDays: Long = 0,
        firstDueAt: Instant? = null,
    ): Result<ReviewCard> = runCatching {
        require(html.string("created_in") == "html") { "Not a selection from the HTML view" }
        require(html.string("html_edition_sha256").matches(Regex("[0-9a-f]{64}"))) { "Unknown HTML edition" }
        require(html.getAsJsonObject("text_quote")?.string("exact")?.isNotBlank() == true) { "The selection has no text" }
        require(firstDueDays in 0..3650) { "The first review time is invalid" }
        require(firstDueAt == null || firstDueAt.isAfter(createdAt)) { "The first review time must be in the future" }
        val registeredDocumentId = documentId.ifEmpty { com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().identifier("$pdfStem.pdf") }
        require(com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore.validId(registeredDocumentId)) { "Invalid document identifier" }
        val normalizedStem = pdfStem.trim()
        val normalizedPdfSha256 = pdfSha256.lowercase()
        val normalizedQuestion = question.trim()
        require(normalizedStem.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && normalizedPdfSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid PDF identity"
        }
        require(normalizedQuestion.isNotEmpty() && normalizedQuestion.length <= MAX_QUESTION_LENGTH) {
            "The question must contain between 1 and $MAX_QUESTION_LENGTH characters"
        }
        require(selectedText.isNotBlank() && selectedText.length <= 500_000)
        val cardId = UUID.randomUUID().toString()
        val timestamp = createdAt.toString()
        val primary = JsonObject().apply {
            addProperty("document_id", registeredDocumentId)
            addProperty("pdf_stem", normalizedStem)
            addProperty("pdf_sha256", normalizedPdfSha256)
            addProperty("selected_text", selectedText)
            addProperty("question", normalizedQuestion)
            html.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
        }
        val record = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("card_type", "visual_region")
            addProperty("card_id", cardId)
            addProperty("status", "approved")
            addProperty("created_at", timestamp)
            (firstDueAt ?: if (firstDueDays > 0) createdAt.plus(firstDueDays, ChronoUnit.DAYS) else null)
                ?.let { addProperty("initial_due_at", it.toString()) }
            addProperty("updated_at", timestamp)
            addProperty("revision", 1)
            addProperty("origin_id", originId)
            add("primary", primary)
            addProperty("source_authority", "client-v1")
            add("source_link", JsonObject().apply {
                addProperty("state", REVIEW_PENDING_SERVER_LINK)
                addProperty("reason", "Made in the HTML view; the server places its markers at the next Sync.")
            })
        }
        ReviewVersionStamp.put(record, runCatching {
            versionStamper?.stampDocument(registeredDocumentId, normalizedStem, normalizedPdfSha256, sourceBuiltFallback = true)
        }.getOrNull())
        ReviewOutgoingCardFileLock.withLock {
            withSnapshotMutation { persistRecord(outgoingCardFile(cardId), record) }
        }
        context?.let { PendingChangesSignal.notifyChanged() }
        checkNotNull(parseCard(record, schedule = null))
    }

    /**
     * A card made from a selection inside an HTML attachment page (thoughts-html-attachment-cards-SPEC.md):
     * owned by the document whose PDF link opened the page ([pdfStem], [documentId], [pdfSha256] = the
     * PDF the link was in). [attachment] is [com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.fields]:
     * the page and its version, the text quote and the page state. No PDF geometry is stored, and
     * `source_link.state` is the terminal `html_attachment`: Fedora never links it to source markers.
     */
    fun createHtmlAttachmentCard(
        pdfStem: String,
        pdfSha256: String,
        question: String,
        attachment: JsonObject,
        selectedText: String,
        createdAt: Instant = Instant.now(),
        documentId: String = "",
        firstDueDays: Long = 0,
        firstDueAt: Instant? = null,
    ): Result<ReviewCard> = runCatching {
        val anchor = requireNotNull(com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.Anchor.from(attachment)) {
            "Not a selection from an HTML attachment page"
        }
        require(firstDueDays in 0..3650) { "The first review time is invalid" }
        require(firstDueAt == null || firstDueAt.isAfter(createdAt)) { "The first review time must be in the future" }
        val registeredDocumentId = documentId.ifEmpty { com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().identifier("$pdfStem.pdf") }
        require(com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore.validId(registeredDocumentId)) { "Invalid document identifier" }
        val normalizedStem = pdfStem.trim()
        val normalizedPdfSha256 = pdfSha256.lowercase()
        val normalizedQuestion = question.trim()
        require(normalizedStem.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && normalizedPdfSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid PDF identity"
        }
        require(normalizedQuestion.isNotEmpty() && normalizedQuestion.length <= MAX_QUESTION_LENGTH) {
            "The question must contain between 1 and $MAX_QUESTION_LENGTH characters"
        }
        val text = selectedText.trim().ifBlank { anchor.quote.exact }
        require(text.length <= 500_000) { "The selection is too long" }
        val cardId = UUID.randomUUID().toString()
        val timestamp = createdAt.toString()
        val primary = JsonObject().apply {
            addProperty("document_id", registeredDocumentId)
            addProperty("pdf_stem", normalizedStem)
            addProperty("pdf_sha256", normalizedPdfSha256)
            anchor.toJson().entrySet().forEach { (key, value) -> add(key, value) }
            addProperty("selected_text", text)
            addProperty("question", normalizedQuestion)
        }
        val record = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("card_type", "visual_region")
            addProperty("card_id", cardId)
            addProperty("status", "approved")
            addProperty("created_at", timestamp)
            (firstDueAt ?: if (firstDueDays > 0) createdAt.plus(firstDueDays, ChronoUnit.DAYS) else null)
                ?.let { addProperty("initial_due_at", it.toString()) }
            addProperty("updated_at", timestamp)
            addProperty("revision", 1)
            addProperty("origin_id", originId)
            add("primary", primary)
            addProperty("source_authority", "client-v1")
            add("source_link", JsonObject().apply {
                addProperty("state", com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.SOURCE_LINK_STATE)
            })
        }
        ReviewVersionStamp.put(record, runCatching {
            versionStamper?.stampDocument(registeredDocumentId, normalizedStem, normalizedPdfSha256, sourceBuiltFallback = true)
        }.getOrNull())
        ReviewOutgoingCardFileLock.withLock {
            withSnapshotMutation { persistRecord(outgoingCardFile(cardId), record) }
        }
        context?.let { PendingChangesSignal.notifyChanged() }
        checkNotNull(parseCard(record, schedule = null))
    }

    fun importGeneratedCandidates(
        pdfStem: String,
        pdfSha256: String,
        candidates: List<GeneratedReviewCandidate>,
        createdAt: Instant = Instant.now(),
        documentId: String = "",
    ): Result<List<ReviewCard>> = runCatching {
        val registeredDocumentId = documentId.ifEmpty { com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().identifier("$pdfStem.pdf") }
        require(com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore.validId(registeredDocumentId)) { "Invalid document identifier" }
        val normalizedStem = pdfStem.trim()
        val normalizedPdfSha256 = pdfSha256.lowercase()
        require(normalizedStem.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            "Invalid PDF identity"
        }
        require(normalizedPdfSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid PDF identity"
        }
        require(candidates.size <= 50) { "Too many generated review cards" }
        require(candidates.all { it.sourceAnchor == null || it.sourceAnchor.getAsJsonArray("segments")?.size() == 1 }) {
            "A new card must select one continuous source range."
        }
        require(context == null || candidates.all { it.sourceAnchor?.get("version")?.asInt == 2 }) {
            "The server returned cards without source anchors. Update the server before generating cards."
        }
        val existingCards = snapshot(normalizedStem).cards
        val existingIds = existingCards.map { it.id }.toMutableSet()
        candidates.forEach { candidate ->
            candidate.cardId?.let { require(UUID.fromString(it).toString() == it) { "Invalid card ID" } }
        }
        val occupied = existingCards
            .filter { it.pdfStem == normalizedStem && it.status != "rejected" && it.schedule?.deleted != true }
            .map { it.page to copyReviewRect(it.rect) }
            .toMutableList()
        val accepted = candidates.filter { candidate ->
            (candidate.cardId == null || candidate.cardId !in existingIds) &&
            candidate.page > 0 &&
                candidate.pageVisualSha256.matches(Regex("[0-9a-f]{64}")) &&
                candidate.question.trim().let { it.isNotEmpty() && it.length <= MAX_QUESTION_LENGTH } &&
                validReviewRect(candidate.rect) &&
                (candidate.duplicateCheck != null || occupied.none { (page, rect) ->
                    page == candidate.page && intersectionOverUnion(rect, candidate.rect) >= 0.5f
                }).also { available ->
                    if (available) {
                        occupied.add(candidate.page to copyReviewRect(candidate.rect))
                        candidate.cardId?.let(existingIds::add)
                    }
                }
        }
        val timestamp = createdAt.toString()
        val records = accepted.map { candidate ->
            val cardId = candidate.cardId ?: UUID.randomUUID().toString()
            JsonObject().apply {
                addProperty("schema_version", SCHEMA_VERSION)
                addProperty("card_type", "visual_region")
                addProperty("card_id", cardId)
                addProperty("status", "approved")
                addProperty("approved_at", timestamp)
                addProperty("created_at", timestamp)
                addProperty("updated_at", timestamp)
                addProperty("revision", 1)
                addProperty("origin_id", originId)
                candidate.reanchor?.let { add("reanchor", it.deepCopy()) }
                if (candidate.note.isNotBlank()) addProperty("note", candidate.note)
                candidate.duplicateCheck?.let { add("duplicate_check", it.deepCopy()) }
                add("primary", JsonObject().apply {
                    addProperty("document_id", registeredDocumentId)
                    addProperty("pdf_stem", normalizedStem)
                    addProperty("pdf_sha256", normalizedPdfSha256)
                    addProperty("page", candidate.page)
                    add("rect", com.google.gson.JsonArray().apply {
                        add(candidate.rect.left)
                        add(candidate.rect.top)
                        add(candidate.rect.right)
                        add(candidate.rect.bottom)
                    })
                    addProperty("question", candidate.question.trim())
                    addProperty("page_visual_sha256", candidate.pageVisualSha256)
                    if (candidate.sourceAnchor == null) addProperty("source_kind", "pdf")
                    else add("source_anchor", candidate.sourceAnchor.deepCopy())
                })
            }
        }
        ReviewOutgoingCardFileLock.withLock {
            if (records.isNotEmpty()) {
                withSnapshotMutation {
                    records.forEach { record ->
                        persistRecord(outgoingCardFile(record.string("card_id")), record)
                    }
                }
            }
        }
        if (records.isNotEmpty()) context?.let { PendingChangesSignal.notifyChanged() }
        records.mapNotNull { parseCard(it, schedule = null) }
    }

    companion object {
        private const val SCHEMA_VERSION = 3
        const val MAX_NOTE_LENGTH = 5000
        private const val MAX_QUESTION_LENGTH = 500
        private const val PREFERENCES = "pdf-review"
        private const val KEY_ORIGIN_ID = "origin-id"
        private const val MAX_INTERVAL_DAYS = 365
        private const val CURRENT_SCHEDULER_VERSION = 2
        private const val LEARN_AHEAD_LIMIT_MINUTES = 20L
        private const val FIRST_LEARNING_STEP_MINUTES = 1L
        private const val SECOND_LEARNING_STEP_MINUTES = 10L
        private const val FIRST_LEARNING_HARD_MINUTES = 6L
        private const val RELEARNING_STEP_MINUTES = 10L
        private const val RELEARNING_HARD_MINUTES = 15L
        private const val GRADUATING_INTERVAL_DAYS = 3
        private const val EASY_INTERVAL_DAYS = 7
        private const val RELEARNING_INTERVAL_DAYS = 1
        private val REPAIRING_STATES = setOf("queued", "retry", "running", "updating")
        private const val TAG = "ThoughtsReview"
        private val snapshotSlots = ConcurrentHashMap<ReviewSnapshotCacheKey, ReviewSnapshotSlot>()

        private fun validReviewRect(rect: RectF): Boolean =
            rect.left >= 0f && rect.top >= 0f && rect.right <= 1f && rect.bottom <= 1f &&
                rect.left < rect.right && rect.top < rect.bottom &&
                listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() }

        private fun copyReviewRect(rect: RectF): RectF = RectF().apply {
            left = rect.left
            top = rect.top
            right = rect.right
            bottom = rect.bottom
        }

        fun defaultRoot(): File = if (com.gitlab.mudlej.MjPdfReader.BuildConfig.APPLICATION_ID.endsWith(".sandbox"))
            File(ThoughtsLocalStore.reviewIncoming().parentFile, "legacy")
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "docs-clean-reviews")

        fun defaultIncomingRoot(): File = ThoughtsLocalStore.reviewIncoming()

        fun defaultOutgoingEventsRoot(): File = ThoughtsLocalStore.reviewOutgoing()

        fun defaultOutgoingCardsRoot(): File = ThoughtsLocalStore.reviewOutgoingCards()

        fun nextSchedule(
            previous: ReviewSchedule?,
            rating: ReviewRating,
            reviewedAt: Instant,
            schedulerVersion: Int = CURRENT_SCHEDULER_VERSION,
            scheduledDueAt: Instant? = null,
        ): ReviewSchedule {
            // Explicit choices never grow with past intervals or enter learn-ahead.
            // Old rating events retain their original scheduler semantics.
            when (rating) {
                ReviewRating.CUSTOM_INTERVAL -> {
                    require(scheduledDueAt != null && scheduledDueAt.isAfter(reviewedAt))
                    val days = ChronoUnit.DAYS.between(reviewedAt, scheduledDueAt).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    return ReviewSchedule(scheduledDueAt, days, fixedInterval = true)
                }
                ReviewRating.RETAINED -> return ReviewSchedule(reviewedAt, 0, retained = true)
                ReviewRating.NEXT_WEEK -> return reviewSchedule(reviewedAt, 7).copy(fixedInterval = true)
                ReviewRating.NEXT_DAY -> return reviewSchedule(reviewedAt, 1).copy(fixedInterval = true)
                ReviewRating.TEN_MINUTES -> return ReviewSchedule(
                    reviewedAt.plus(10, ChronoUnit.MINUTES), 0, fixedInterval = true,
                )
                else -> Unit
            }
            if (schedulerVersion < CURRENT_SCHEDULER_VERSION) {
                return legacyNextSchedule(previous, rating, reviewedAt)
            }
            if (rating == ReviewRating.DELETE) {
                return ReviewSchedule(reviewedAt, 0, deleted = true)
            }

            val learningStep = previous?.learningStep
                ?: 0.takeIf { previous != null && previous.intervalDays == 0 }
            val isLearning = learningStep != null
            val isRelearning = previous?.relearning == true
            val previousReviewDays = when {
                previous == null || previous.deleted -> 0
                isLearning -> previous.previousIntervalDays
                else -> previous.intervalDays
            }

            if (previous == null || isLearning) {
                val relearning = previous != null && isRelearning
                return when (rating) {
                    ReviewRating.AGAIN -> learningSchedule(
                        reviewedAt,
                        if (relearning) RELEARNING_STEP_MINUTES else FIRST_LEARNING_STEP_MINUTES,
                        step = 0,
                        relearning = relearning,
                        previousIntervalDays = previousReviewDays,
                    )
                    ReviewRating.HARD -> learningSchedule(
                        reviewedAt,
                        when {
                            relearning -> RELEARNING_HARD_MINUTES
                            learningStep == 1 -> SECOND_LEARNING_STEP_MINUTES
                            else -> FIRST_LEARNING_HARD_MINUTES
                        },
                        step = learningStep ?: 0,
                        relearning = relearning,
                        previousIntervalDays = previousReviewDays,
                    )
                    ReviewRating.GOOD -> when {
                        relearning -> reviewSchedule(reviewedAt, RELEARNING_INTERVAL_DAYS)
                        learningStep == 1 -> reviewSchedule(reviewedAt, GRADUATING_INTERVAL_DAYS)
                        else -> learningSchedule(
                            reviewedAt,
                            SECOND_LEARNING_STEP_MINUTES,
                            step = 1,
                            relearning = false,
                            previousIntervalDays = 0,
                        )
                    }
                    ReviewRating.EASY -> reviewSchedule(
                        reviewedAt,
                        if (relearning && previousReviewDays > 0) {
                            reviewInterval(previousReviewDays, rating)
                        } else {
                            EASY_INTERVAL_DAYS
                        },
                    )
                }
            }

            if (rating == ReviewRating.AGAIN) {
                return learningSchedule(
                    reviewedAt,
                    RELEARNING_STEP_MINUTES,
                    step = 0,
                    relearning = true,
                    previousIntervalDays = previousReviewDays,
                )
            }
            return reviewSchedule(reviewedAt, reviewInterval(previousReviewDays, rating))
        }

        private fun learningSchedule(
            reviewedAt: Instant,
            delayMinutes: Long,
            step: Int,
            relearning: Boolean,
            previousIntervalDays: Int,
        ) = ReviewSchedule(
            dueAt = reviewedAt.plus(delayMinutes, ChronoUnit.MINUTES),
            intervalDays = 0,
            learningStep = step,
            relearning = relearning,
            previousIntervalDays = previousIntervalDays,
        )

        private fun reviewSchedule(reviewedAt: Instant, intervalDays: Int) = ReviewSchedule(
            dueAt = reviewedAt.plus(intervalDays.toLong(), ChronoUnit.DAYS),
            intervalDays = intervalDays,
        )

        private fun reviewInterval(previousDays: Int, rating: ReviewRating): Int = when (rating) {
            ReviewRating.HARD -> max(1, (previousDays * 1.2).roundToInt())
            ReviewRating.GOOD -> max(previousDays + 1, (previousDays * 2.5).roundToInt())
            ReviewRating.EASY -> max(previousDays + 1, (previousDays * 4.0).roundToInt())
            else -> error("Only passing review ratings have day intervals")
        }.coerceAtMost(MAX_INTERVAL_DAYS)

        private fun legacyNextSchedule(
            previous: ReviewSchedule?,
            rating: ReviewRating,
            reviewedAt: Instant,
        ): ReviewSchedule {
            if (rating == ReviewRating.DELETE) {
                return ReviewSchedule(reviewedAt, 0, deleted = true)
            }
            if (rating == ReviewRating.AGAIN) {
                val previousReviewDays = when {
                    previous == null || previous.deleted -> 0
                    previous.intervalDays > 0 -> previous.intervalDays
                    else -> previous.previousIntervalDays
                }
                return ReviewSchedule(
                    dueAt = reviewedAt.plus(10, ChronoUnit.MINUTES),
                    intervalDays = 0,
                    relearning = previousReviewDays > 0,
                    previousIntervalDays = previousReviewDays,
                )
            }
            val previousDays = if (previous?.deleted == false) previous.intervalDays else 0
            val interval = when (rating) {
                ReviewRating.HARD -> if (previousDays <= 0) 1 else max(1, (previousDays * 1.2).roundToInt())
                ReviewRating.GOOD -> if (previousDays <= 0) 3 else max(previousDays + 1, (previousDays * 2.5).roundToInt())
                ReviewRating.EASY -> if (previousDays <= 0) 7 else max(previousDays + 1, (previousDays * 4.0).roundToInt())
                else -> error("handled above")
            }.coerceAtMost(MAX_INTERVAL_DAYS)
            return ReviewSchedule(reviewedAt.plus(interval.toLong(), ChronoUnit.DAYS), interval)
        }

        internal fun foldEvents(events: List<JsonObject>): Map<String, ReviewSchedule> {
            val unique = LinkedHashMap<String, JsonObject>()
            events.forEach { event ->
                event.string("event_id").takeIf(String::isNotEmpty)?.let { unique.putIfAbsent(it, event) }
            }
            val retractedEventIds = unique.values.mapNotNull { retraction ->
                val targetId = retraction.string("retracted_event_id").takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val target = unique[targetId] ?: return@mapNotNull null
                val sameCard = retraction.string("card_id").isNotEmpty() &&
                    retraction.string("card_id") == target.string("card_id")
                val targetIsRating = ReviewRating.entries.any { it.wireName == target.string("rating") }
                targetId.takeIf { sameCard && targetIsRating && target.string("rating") != "delete" }
            }.toSet()
            val schedules = mutableMapOf<String, ReviewSchedule>()
            unique.values.asSequence()
                .filterNot(::inferredSourceDeletion)
                .filterNot { it.string("event_id") in retractedEventIds }
                .sortedWith(compareBy<JsonObject> { it.instant("reviewed_at") }.thenBy { it.string("event_id") })
                .forEach { event ->
                    val cardId = event.string("card_id")
                    val reviewedAt = runCatching { Instant.parse(event.string("reviewed_at")) }.getOrNull()
                        ?: return@forEach
                    if (cardId.isEmpty() || schedules[cardId]?.deleted == true) {
                        return@forEach
                    }
                    if (event.string("interval_action") == "set") {
                        schedules[cardId] = applyIntervalSelection(event, schedules[cardId], reviewedAt) ?: return@forEach
                        return@forEach
                    }
                    val rating = ReviewRating.entries.firstOrNull { it.wireName == event.string("rating") }
                        ?: return@forEach
                    val scheduledDueAt = event.instant("scheduled_due_at")
                    if (rating == ReviewRating.CUSTOM_INTERVAL && scheduledDueAt?.isAfter(reviewedAt) != true) return@forEach
                    schedules[cardId] = nextSchedule(
                        schedules[cardId],
                        rating,
                        reviewedAt,
                        event.int("scheduler_version"),
                        scheduledDueAt,
                    )
                }
            return schedules
        }

        private fun parseCard(record: JsonObject, schedule: ReviewSchedule?): ReviewCard? = runCatching {
            if (record.string("status") == "candidate" || record.get("deleted")?.asBoolean == true) return null
            val version = record.int("schema_version")
            if (version != SCHEMA_VERSION && version != 4) return null
            val primary = record.getAsJsonObject("primary") ?: return null
            if (version == 4 && !primary.has("regions")) return null
            val reanchor = record.getAsJsonObject("projection") ?: record.getAsJsonObject("reanchor")
            val id = record.string("card_id")
            UUID.fromString(id)
            val question = primary.string("question").trim()
            if (question.isEmpty() || question.length > MAX_QUESTION_LENGTH) return null
            // A card made in the HTML view has no PDF geometry until Fedora links it.
            // A card made inside an HTML attachment page never has any (anchored by its quote).
            val attachment = com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.Anchor.from(primary)
            if (primary.string("created_in") == com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.CREATED_IN &&
                (attachment == null || primary.has("rect") || primary.has("page") || primary.has("regions"))) return null
            val htmlUnplaced = (primary.string("created_in") == "html" || attachment != null) && !primary.has("rect") && !primary.has("page")
            val values = if (htmlUnplaced) com.google.gson.JsonArray().apply { add(0f); add(0f); add(0f); add(0f) }
                else primary.getAsJsonArray("rect") ?: return null
            if (values.size() != 4) return null
            val left = values[0].asFloat
            val top = values[1].asFloat
            val right = values[2].asFloat
            val bottom = values[3].asFloat
            if (!htmlUnplaced && (left < 0 || top < 0 || right > 1 || bottom > 1 ||
                left >= right || top >= bottom || !listOf(left, top, right, bottom).all { it.isFinite() })
            ) {
                return null
            }
            val rect = RectF().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            }
            val override = reviewAnswerRegionOverride(primary)?.map(ReviewAreaBox::toRegion)
            val answerRegions = if (override != null) override else if (primary.has("regions")) {
                val encoded = primary.getAsJsonArray("regions") ?: return null
                if (encoded.size() == 0) return null
                encoded.map { value ->
                    val region = value.asJsonObject
                    val page = region.int("page")
                    val coords = region.getAsJsonArray("rect") ?: return null
                    if (page < 1 || coords.size() != 4) return null
                    val box = RectF().apply {
                        this.left = coords[0].asFloat
                        this.top = coords[1].asFloat
                        this.right = coords[2].asFloat
                        this.bottom = coords[3].asFloat
                    }
                    if (!validReviewRect(box)) return null
                    ReviewAnswerRegion(page, box)
                }
            } else emptyList()
            val sourceAnchor = primary.getAsJsonObject("source_anchor")
            val anchoredSourceFiles = sourceAnchor?.getAsJsonArray("segments")?.mapNotNull { value ->
                value.takeIf { it.isJsonObject }?.asJsonObject?.string("source_file")
                    ?.takeIf(String::isNotBlank)
            }.orEmpty()
            val sourceFiles = if (anchoredSourceFiles.isNotEmpty()) anchoredSourceFiles.distinct() else buildList {
                primary.getAsJsonObject("source_selection")?.getAsJsonArray("segments")?.forEach { value ->
                    value.takeIf { it.isJsonObject }?.asJsonObject?.string("source_file")
                        ?.takeIf(String::isNotBlank)?.let(::add)
                }
                sequenceOf(
                    primary.string("source_file"),
                    primary.string("source_path"),
                    primary.string("tex_file"),
                    record.getAsJsonObject("source_link")?.string("source_file").orEmpty(),
                    record.string("source_file"),
                    record.string("source_path"),
                    record.string("tex_file"),
                ).filter(String::isNotBlank).forEach(::add)
            }.distinct()
            val hasSourceAnchor = anchoredSourceFiles.isNotEmpty()
            ReviewCard(
                id = id,
                status = record.string("status"),
                documentId = primary.string("document_id"),
                pdfDocumentId = primary.string("pdf_document_id"),
                wholeDocument = primary.string("scope") == "document",
                pdfStem = primary.string("pdf_stem"),
                pdfSha256 = primary.string("pdf_sha256"),
                // Like the desktop: an edited card is located by its first edited area.
                page = override?.first()?.page ?: primary.int("page"),
                pageVisualSha256 = primary.string("page_visual_sha256"),
                question = question,
                note = record.string("note"),
                rect = override?.first()?.rect ?: rect,
                answerRegions = answerRegions,
                answerRegionOverride = override != null,
                schedule = schedule,
                reanchorState = reanchor?.string("state").orEmpty(),
                revision = record.intOrNull("revision"),
                originId = record.string("origin_id"),
                createdAt = record.instant("created_at"),
                updatedAt = record.instant("updated_at"),
                approvedAt = record.instant("approved_at"),
                initialDueAt = record.instant("initial_due_at"),
                sourceFiles = sourceFiles,
                sourceAnchor = sourceAnchor?.deepCopy(),
                generationMethod = record.string("generation_method"),
                basicPassageKey = record.string("basic_passage_key"),
                sourceKind = primary.string("source_kind"),
                sourceLinkState = if (hasSourceAnchor) "linked" else record.getAsJsonObject("source_link")?.string("state").orEmpty(),
                sourceLinkReason = if (hasSourceAnchor) "" else record.getAsJsonObject("source_link")?.string("reason").orEmpty(),
                reanchorReason = reanchor?.string("reason").orEmpty()
                    .ifBlank { reanchor?.string("error_code").orEmpty() }
                    .ifBlank { record.string("reanchor_error") },
                reanchorErrorCode = reanchor?.string("error_code").orEmpty(),
                reanchorMessage = reanchor?.string("message").orEmpty(),
                htmlSelection = primary.takeIf { it.string("created_in") == "html" }?.let { html ->
                    JsonObject().apply {
                        listOf("created_in", "html_edition_sha256", "text_quote", "source_range").forEach { key ->
                            html.get(key)?.let { add(key, it.deepCopy()) }
                        }
                    }
                },
                htmlAttachment = attachment,
            ).takeIf { card ->
                card.pdfStem.isNotEmpty() && (htmlUnplaced && card.page == 0 && card.answerRegions.isEmpty() ||
                    card.page > 0 && card.pageVisualSha256.matches(Regex("[0-9a-f]{64}")))
            }
        }.getOrNull()

        private fun writeAtomically(file: File, contents: String) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
            FileOutputStream(temporary).use { stream ->
                stream.write(contents.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not publish ${file.path}" }
        }

        private fun sha256(value: String): String = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private fun intersectionOverUnion(first: RectF, second: RectF): Float {
    val intersectionLeft = max(first.left, second.left)
    val intersectionTop = max(first.top, second.top)
    val intersectionRight = min(first.right, second.right)
    val intersectionBottom = min(first.bottom, second.bottom)
    val intersection = max(0f, intersectionRight - intersectionLeft) *
        max(0f, intersectionBottom - intersectionTop)
    if (intersection <= 0f) return 0f
    val firstArea = (first.right - first.left) * (first.bottom - first.top)
    val secondArea = (second.right - second.left) * (second.bottom - second.top)
    val union = firstArea + secondArea - intersection
    return if (union > 0f) intersection / union else 0f
}

private fun ReviewCard.matchesCanonical(canonicalPdfSha256ByFilename: Map<String, String>?): Boolean {
    if (canonicalPdfSha256ByFilename == null) return true
    // A card inside an HTML attachment page has no place in its owner's PDF: it follows the
    // document, whatever PDF version it was made on, while the document is in the Library.
    if (isHtmlAttachmentCard) return canonicalPdfSha256ByFilename.containsKey("$pdfStem.pdf")
    return canonicalPdfSha256ByFilename["$pdfStem.pdf"]?.lowercase() == pdfSha256.lowercase()
}

private fun JsonObject.matches(card: ReviewCard): Boolean {
    val page = int("page")
    val visualSha256 = string("page_visual_sha256")
    return (visualSha256.isNotEmpty() && visualSha256 == card.pageVisualSha256) ||
        (string("pdf_stem") == card.pdfStem && page == card.page)
}

private fun File.normalizedPath(): String = runCatching { canonicalPath }
    .getOrElse { absolutePath }

private fun emptyHistory() = FoldedReviewHistory(
    schedule = null,
    reviewCount = 0,
    lapseCount = 0,
    lastRating = null,
    lastReviewedAt = null,
    deleteEventId = null,
    entries = emptyList(),
)

val ReviewCard.bucket: ReviewCardBucket
    get() = when {
        schedule == null -> ReviewCardBucket.NEW
        schedule.intervalDays == 0 -> ReviewCardBucket.LEARNING
        else -> ReviewCardBucket.REVIEW
    }

private fun JsonObject.string(key: String): String = runCatching {
    get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
}.getOrDefault("")
private fun JsonObject.int(key: String): Int = runCatching {
    get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0
}.getOrDefault(0)
private fun JsonObject.intOrNull(key: String): Int? = runCatching {
    get(key)?.takeIf { !it.isJsonNull }?.asInt
}.getOrNull()
private fun JsonObject.instant(key: String): Instant? = string(key)
    .takeIf(String::isNotBlank)
    ?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** The Project document a card belongs to, for publication: its document, else its PDF's document. */
internal fun ReviewCard.publicationDocumentId(): String = documentId.ifBlank { pdfDocumentId }
