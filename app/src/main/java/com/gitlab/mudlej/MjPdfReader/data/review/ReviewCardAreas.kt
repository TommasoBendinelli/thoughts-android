// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import android.graphics.RectF
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An answer box in normalized, top-left page coordinates; [page] is 1-based within the card's
 * PDF. Plain floats so the editing rules work without Android geometry classes.
 */
data class ReviewAreaBox(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val valid: Boolean get() = page > 0 &&
        listOf(left, top, right, bottom).all { it.isFinite() && it >= 0f && it <= 1f } &&
        left < right && top < bottom

    fun sameAs(other: ReviewAreaBox): Boolean = page == other.page &&
        abs(left - other.left) <= SAME_TOLERANCE && abs(top - other.top) <= SAME_TOLERANCE &&
        abs(right - other.right) <= SAME_TOLERANCE && abs(bottom - other.bottom) <= SAME_TOLERANCE

    fun toRegion(): ReviewAnswerRegion = ReviewAnswerRegion(page, RectF().also {
        it.left = left; it.top = top; it.right = right; it.bottom = bottom
    })

    companion object {
        /** Below what a drag can produce, above float noise from the page-point round trip. */
        private const val SAME_TOLERANCE = 1e-4f

        fun of(region: ReviewAnswerRegion): ReviewAreaBox =
            ReviewAreaBox(region.page, region.rect.left, region.rect.top, region.rect.right, region.rect.bottom)

        fun union(boxes: List<ReviewAreaBox>): ReviewAreaBox = ReviewAreaBox(
            boxes.first().page, boxes.minOf { it.left }, boxes.minOf { it.top },
            boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
        )
    }
}

/**
 * One area of the Edit card editor: the box shown, and the stored regions it stands for. While
 * the box is not moved or resized, saving keeps those regions exactly (e.g. the line-by-line
 * mask of a text selection); once changed, the area becomes the box itself.
 */
data class ReviewEditableArea(
    val box: ReviewAreaBox,
    val stored: List<ReviewAreaBox> = listOf(box),
    /** The text selected for this area when the card was being created, if any. */
    val text: String? = null,
)

/**
 * One answer area as saved: its regions (possibly several lines of one passage, on any pages), the
 * text selected for it, and whether it was drawn as a box. Each becomes one source segment.
 */
data class ReviewEditedArea(val regions: List<ReviewAreaBox>, val text: String? = null, val rectangle: Boolean = text == null)

/** The areas Edit card shows for a card's stored regions. */
fun reviewEditableAreas(regions: List<ReviewAreaBox>): List<ReviewEditableArea> {
    val groups = mutableListOf<MutableList<ReviewAreaBox>>()
    for (region in regions) {
        val last = groups.lastOrNull()
        if (last != null && continuesPassage(last.last(), region)) last += region else groups += mutableListOf(region)
    }
    return groups.map { ReviewEditableArea(ReviewAreaBox.union(it), it.toList()) }
}

/**
 * The next line of one selected passage: same page, directly below (or overlapping) and sharing
 * some width. Separately drawn boxes rarely touch like this, so they stay separate areas.
 */
private fun continuesPassage(previous: ReviewAreaBox, next: ReviewAreaBox): Boolean =
    previous.page == next.page &&
        next.top - previous.bottom <= LINE_GAP && previous.top - next.bottom <= LINE_GAP &&
        min(previous.right, next.right) - max(previous.left, next.left) > 0f &&
        // Side by side on one line is two areas (e.g. two columns), not a passage.
        next.top >= previous.top

private const val LINE_GAP = 0.012f

/**
 * The editor's list of areas while boxes are added and removed. The PDF view holds the boxes'
 * geometry in the same order; this keeps which stored regions each box stands for.
 */
class ReviewAreaEditSession(initial: List<ReviewEditableArea>) {
    // null: an area added in this session.
    private val origins: MutableList<ReviewEditableArea?> = initial.toMutableList()
    private val initialRegions = initial.flatMap { it.stored }

    val count: Int get() = origins.size
    val canRemove: Boolean get() = origins.size > 1
    val initialBoxes: List<ReviewAreaBox> get() = origins.mapNotNull { it?.box }

    fun added() {
        origins += null
    }

    /** The last area is never removed. */
    fun remove(index: Int): Boolean {
        if (!canRemove || index !in origins.indices) return false
        origins.removeAt(index)
        return true
    }

    /** The regions to save, given the editor's boxes in order. */
    fun regions(boxes: List<ReviewAreaBox>): List<ReviewAreaBox> {
        require(boxes.size == origins.size && boxes.isNotEmpty()) { "The answer areas changed while saving." }
        require(boxes.all(ReviewAreaBox::valid)) { "Invalid review region" }
        return boxes.zip(origins).flatMap { (box, origin) ->
            if (origin != null && box.sameAs(origin.box)) origin.stored else listOf(box)
        }
    }

    /**
     * The areas to save, one per box in order: an untouched area keeps its stored regions and
     * text; a moved, resized or added one is its box.
     */
    fun areas(boxes: List<ReviewAreaBox>): List<ReviewEditedArea> {
        require(boxes.size == origins.size && boxes.isNotEmpty()) { "The answer areas changed while saving." }
        require(boxes.all(ReviewAreaBox::valid)) { "Invalid review region" }
        return boxes.zip(origins).map { (box, origin) ->
            if (origin != null && box.sameAs(origin.box)) {
                ReviewEditedArea(origin.stored, origin.text, rectangle = origin.text == null && origin.stored.size == 1)
            } else ReviewEditedArea(listOf(box), null, rectangle = true)
        }
    }

    /** Whether saving [boxes] would change the card's answer regions. */
    fun changesRegions(boxes: List<ReviewAreaBox>): Boolean {
        val regions = regions(boxes)
        return regions.size != initialRegions.size || regions.zip(initialRegions).any { (a, b) -> !a.sameAs(b) }
    }
}

/** The page a card is listed under after an edit: kept while it still has an area, else the first area's. */
fun reviewPrimaryPage(currentPage: Int, regions: List<ReviewAreaBox>): Int =
    if (regions.any { it.page == currentPage }) currentPage else regions.first().page

/** The primary box: every area on the primary page, as one box (like [ReviewCard.answerBox]). */
fun reviewPrimaryBox(page: Int, regions: List<ReviewAreaBox>): ReviewAreaBox =
    ReviewAreaBox.union(regions.filter { it.page == page })

/**
 * The areas of a desktop edit stored on a card (primary.answer_region_override), or null when there is
 * none or it was drawn on another PDF than the card's; then the card's regions or rectangle apply. The
 * same rule as the desktop's effectiveReviewCardRegions.
 */
fun reviewAnswerRegionOverride(primary: JsonObject): List<ReviewAreaBox>? = runCatching {
    val edited = primary.getAsJsonObject("answer_region_override") ?: return null
    val pdfSha256 = primary.get("pdf_sha256")?.asString.orEmpty()
    if (pdfSha256.isEmpty() || edited.get("pdf_sha256")?.asString != pdfSha256) return null
    val regions = edited.getAsJsonArray("regions") ?: return null
    regions.map { value ->
        val region = value.asJsonObject
        val rect = region.getAsJsonArray("rect")?.takeIf { it.size() == 4 } ?: return null
        ReviewAreaBox(region.get("page").asInt, rect[0].asFloat, rect[1].asFloat, rect[2].asFloat, rect[3].asFloat)
    }.takeIf { it.isNotEmpty() && it.all(ReviewAreaBox::valid) }
}.getOrNull()

/** primary.answer_region_override for [regions] drawn on [pdfSha256], as the desktop writes it. */
fun reviewAnswerRegionOverrideJson(pdfSha256: String, regions: List<ReviewAreaBox>): JsonObject = JsonObject().apply {
    addProperty("pdf_sha256", pdfSha256)
    add("regions", JsonArray().apply {
        regions.forEach { box ->
            add(JsonObject().apply {
                addProperty("page", box.page)
                add("rect", JsonArray().apply { add(box.left); add(box.top); add(box.right); add(box.bottom) })
            })
        }
    })
}
