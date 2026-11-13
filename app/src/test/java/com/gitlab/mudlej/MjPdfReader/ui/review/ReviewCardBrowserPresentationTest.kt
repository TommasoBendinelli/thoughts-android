// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewCardBrowserPresentationTest {

    @Test fun `selected project includes shared PDFs and excludes other PDFs`() {
        val project = ReviewBrowserProject("first", "First", setOf("shared.pdf", "first.pdf"))
        assertTrue(project.contains(browserCard("shared-card", deck = "shared")))
        assertTrue(project.contains(browserCard("first-card", deck = "first")))
        assertTrue(!project.contains(browserCard("other-card", deck = "other")))
    }

    @Test fun `filters overlap by latest mark interval and issues and preserve visible order`() {
        val at = Instant.parse("2026-09-22T10:00:00Z")
        val mark = ReviewHistoryEntry("mark", "retained", at, "desktop", 1, at, null,
            false, true, false, null, reviewAction = "wrong")
        val base = browserCard("marked", state = ReviewBrowserState.RETAINED)
        val marked = base.copy(card = base.card.copy(schedule = com.gitlab.mudlej.MjPdfReader.data.review.ReviewSchedule(at, 0, retained = true)),
            history = listOf(mark), hasIssues = true)
        val attention = browserCard("fresh", state = ReviewBrowserState.NEEDS_ATTENTION)
        val fresh = attention.copy(card = attention.card.copy(reanchorState = "needs_attention"))
        fun ids(filter: ReviewCardBrowserFilter) = reviewCardBrowserResult(listOf(marked, fresh), null, "", filter,
            ReviewCardBrowserSort.DUE).cards.map { it.card.id }.toSet()
        assertEquals(setOf("marked"), ids(ReviewCardBrowserFilter.SEEN))
        assertEquals(setOf("marked"), ids(ReviewCardBrowserFilter.NEVER))
        assertEquals(setOf("fresh"), ids(ReviewCardBrowserFilter.NEW))
        // Only cards needing the user's action are unavailable, not every card with a transient issue.
        assertEquals(setOf("fresh"), ids(ReviewCardBrowserFilter.UNAVAILABLE))
        assertEquals(listOf("ALL", "NEW", "DUE", "SEEN", "NEVER", "RETIRED", "UNAVAILABLE"),
            reviewCardBrowserVisibleFilters.map { it.name })
        // A skip only postpones: the card keeps the preceding mark, with or without Undo.
        val skip = mark.copy(eventId = "skip", rating = null, reviewAction = null, reviewedAt = at.plusSeconds(1),
            effective = false, queueAction = "skip", skipId = "batch", skipUntil = at.plusSeconds(600))
        assertEquals("wrong", marked.copy(history = listOf(mark, skip)).currentMark())
        val undo = skip.copy(eventId = "undo", queueAction = "undo_skip", reviewedAt = at.plusSeconds(2))
        assertEquals("wrong", marked.copy(history = listOf(mark, skip, undo)).currentMark())
    }

    @Test
    fun `active default includes due and future but not unavailable lifecycle states`() {
        val cards = ReviewBrowserState.entries.mapIndexed { index, state ->
            browserCard(id = index.toString(), state = state)
        }

        val result = reviewCardBrowserResult(
            cards,
            deckScope = null,
            query = "",
            filter = ReviewCardBrowserFilter.ACTIVE,
            sort = ReviewCardBrowserSort.DUE,
        )

        assertEquals(
            setOf(
                ReviewBrowserState.NEW,
                ReviewBrowserState.LEARNING,
                ReviewBrowserState.REVIEW,
                ReviewBrowserState.FUTURE,
                ReviewBrowserState.SKIPPED,
            ),
            result.cards.map { it.state }.toSet(),
        )
        assertEquals(cards.size, result.totalInScope)
    }

    @Test
    fun `search uses question deck page source and stable id within deck scope`() {
        val first = browserCard(
            id = "alpha-id",
            question = "What is answer matching?",
            deck = "concepts",
            page = 17,
            sourcePath = "docs-concepts/tex/source/evaluation.tex",
        )
        val second = browserCard(
            id = "beta-id",
            question = "What is distillation?",
            deck = "ideas",
            page = 3,
        )
        val inputs = listOf(first.copy(answerText = "answer-only-token", projectDisplayName = "My Project"), second)

        fun matches(query: String) = reviewCardBrowserResult(
            inputs,
            deckScope = "concepts",
            query = query,
            filter = ReviewCardBrowserFilter.ALL,
            sort = ReviewCardBrowserSort.DUE,
        ).cards.map { it.card.id }

        assertEquals(emptyList<String>(), matches("answer-only-token"))
        assertEquals(listOf("alpha-id"), matches("My Project"))
        assertEquals(listOf("alpha-id"), matches("matching"))
        assertEquals(listOf("alpha-id"), matches("concepts"))
        assertEquals(listOf("alpha-id"), matches("17"))
        assertEquals(listOf("alpha-id"), matches("evaluation.tex"))
        assertEquals(listOf("alpha-id"), matches("alpha-id"))
        assertEquals(emptyList<String>(), matches("distillation"))
    }

    @Test
    fun `due sort orders actual dates with new first and stable ties`() {
        val cards = listOf(
            browserCard("future", state = ReviewBrowserState.FUTURE, dueAt = Instant.parse("2026-09-02T00:00:00Z")),
            browserCard("review-b", state = ReviewBrowserState.REVIEW, dueAt = Instant.parse("2026-08-29T00:00:00Z")),
            browserCard("new", state = ReviewBrowserState.NEW),
            browserCard("learning", state = ReviewBrowserState.LEARNING, dueAt = Instant.parse("2026-08-30T00:00:00Z")),
            browserCard("review-a", state = ReviewBrowserState.REVIEW, dueAt = Instant.parse("2026-08-29T00:00:00Z")),
        )

        val result = reviewCardBrowserResult(
            cards,
            deckScope = null,
            query = "",
            filter = ReviewCardBrowserFilter.ALL,
            sort = ReviewCardBrowserSort.DUE,
        )

        assertEquals(
            listOf("new", "review-a", "review-b", "learning", "future"),
            result.cards.map { it.card.id },
        )
    }

    @Test
    fun `specific status filters preserve every browser state`() {
        val cards = ReviewBrowserState.entries.mapIndexed { index, state ->
            browserCard(id = "card-$index", state = state)
        }
        val filters = mapOf(
            ReviewCardBrowserFilter.SKIPPED to ReviewBrowserState.SKIPPED,
            ReviewCardBrowserFilter.LEARNING to ReviewBrowserState.LEARNING,
            ReviewCardBrowserFilter.REVIEW to ReviewBrowserState.REVIEW,
            ReviewCardBrowserFilter.FUTURE to ReviewBrowserState.FUTURE,
            ReviewCardBrowserFilter.EXCLUDED to ReviewBrowserState.EXCLUDED,
        )

        filters.forEach { (filter, expected) ->
            val result = reviewCardBrowserResult(
                cards,
                deckScope = null,
                query = "",
                filter = filter,
                sort = ReviewCardBrowserSort.DUE,
            )
            assertEquals(listOf(expected), result.cards.map { it.state })
        }
        // Retired / Unpublished: both are kept but never reviewed.
        val outOfReview = reviewCardBrowserResult(cards, deckScope = null, query = "",
            filter = ReviewCardBrowserFilter.RETIRED, sort = ReviewCardBrowserSort.DUE)
        assertEquals(setOf(ReviewBrowserState.RETIRED, ReviewBrowserState.UNPUBLISHED), outOfReview.cards.map { it.state }.toSet())
    }

    @Test
    fun `unavailable filter lists only cards that need the user`() {
        val lifecycle = ReviewBrowserState.entries.mapIndexed { index, state ->
            browserCard(id = "card-$index", state = state)
        }
        val base = browserCard("base")
        val actionable = listOf(
            base.copy(card = base.card.copy(id = "attention", reanchorState = "needs_attention")),
            base.copy(card = base.card.copy(id = "unlinked", sourceLinkState = "linking_failed")),
            base.copy(card = base.card.copy(id = "missing", sourceLocationState = "missing")),
            base.copy(card = base.card.copy(id = "ambiguous", sourceLocationState = "ambiguous")),
        )
        val settling = listOf(
            base.copy(card = base.card.copy(id = "repositioning", reanchorState = "running"), state = ReviewBrowserState.REPAIRING),
            base.copy(card = base.card.copy(id = "rebuilt"), state = ReviewBrowserState.STALE, hasIssues = true),
            base.copy(card = base.card.copy(id = "unchecked", sourceLocationState = "unavailable")),
            base.copy(card = base.card.copy(id = "removing", markerRemovalPending = true)),
        )

        val result = reviewCardBrowserResult(
            lifecycle + actionable + settling,
            deckScope = null,
            query = "",
            filter = ReviewCardBrowserFilter.UNAVAILABLE,
            sort = ReviewCardBrowserSort.DUE,
        )

        assertEquals(setOf("attention", "unlinked", "missing", "ambiguous"), result.cards.map { it.card.id }.toSet())
    }

    @Test
    fun `legacy location filter names restore as unavailable`() {
        assertEquals(
            ReviewCardBrowserFilter.UNAVAILABLE,
            reviewCardBrowserFilterFromStoredName("REPAIRING"),
        )
        assertEquals(
            ReviewCardBrowserFilter.UNAVAILABLE,
            reviewCardBrowserFilterFromStoredName("NEEDS_ATTENTION"),
        )
        assertEquals(
            ReviewCardBrowserFilter.UNAVAILABLE,
            reviewCardBrowserFilterFromStoredName("STALE"),
        )
        assertEquals(ReviewCardBrowserFilter.UNAVAILABLE, reviewCardBrowserFilterFromStoredName("WITH_ISSUES"))
    }

    @Test fun `tag filter shows cards whose latest review carries the tag`() {
        val at = Instant.parse("2026-10-02T10:00:00Z")
        fun tagged(id: String, vararg tags: String, earlier: List<String> = emptyList()): ReviewBrowserCard {
            val entries = listOf(earlier, tags.toList()).filter { it.isNotEmpty() }.mapIndexed { i, t ->
                ReviewHistoryEntry("$id-$i", "review", at.plusSeconds(i.toLong()), "phone", 1, at, null,
                    false, true, false, null, reviewTags = t)
            }
            return browserCard(id).copy(history = entries)
        }
        val cards = listOf(
            tagged("a", "proof", "correct"),
            tagged("b", "proof"),
            tagged("c", "correct", earlier = listOf("proof")),
            browserCard("new"),
        )
        fun ids(vararg tags: String, filter: ReviewCardBrowserFilter = ReviewCardBrowserFilter.ALL) =
            reviewCardBrowserResult(cards, null, "", filter, ReviewCardBrowserSort.DUE, tags = tags.toSet())
                .cards.map { it.card.id }.toSet()
        assertEquals(setOf("a", "b"), ids("proof"))
        assertEquals(setOf("a", "c"), ids("correct"))
        assertEquals(setOf("a", "b", "c", "new"), ids())
        // Several tags show cards carrying any of them; the state filter still applies.
        assertEquals(setOf("a", "b", "c"), ids("proof", "correct"))
        assertEquals(emptySet<String>(), ids("proof", filter = ReviewCardBrowserFilter.NEW))
    }

    @Test
    fun `retired mark filter names restore as all cards`() {
        listOf("SKIPPED", "CURRENT_GOOD", "CURRENT_SKIP", "CURRENT_WRONG").forEach { name ->
            assertEquals(ReviewCardBrowserFilter.ALL, reviewCardBrowserFilterFromStoredName(name))
        }
    }

    @Test
    fun `stored filter names keep current values and reject unknown ones`() {
        reviewCardBrowserVisibleFilters.forEach { filter ->
            assertEquals(filter, reviewCardBrowserFilterFromStoredName(filter.name))
        }
        assertEquals(null, reviewCardBrowserFilterFromStoredName("NO_SUCH_FILTER"))
        assertEquals(null, reviewCardBrowserFilterFromStoredName(""))
        assertEquals(null, reviewCardBrowserFilterFromStoredName(null))
    }

    @Test
    fun `detail formatting preserves every source region and event field`() {
        val event = ReviewHistoryEntry(
            eventId = "event-1",
            rating = "again",
            reviewedAt = Instant.parse("2026-08-30T10:15:00Z"),
            originId = "phone-1",
            revision = 4,
            updatedAt = Instant.parse("2026-08-30T10:16:00Z"),
            retractedEventId = "event-0",
            retracted = true,
            effective = false,
            pendingSync = true,
            sourcePath = "incoming/events/event-1.json",
        )
        val original = browserCard(
            id = "detail",
            sourcePath = "docs-concepts/tex/source/fallback.tex",
        )
        val item = original.copy(
            card = original.card.copy(
                sourceFiles = listOf(
                    "docs-concepts/tex/source/main.tex",
                    "docs-concepts/tex/source/appendix.tex",
                ),
                rect = RectF().apply {
                    left = 0.1f
                    top = 0.2f
                    right = 0.75f
                    bottom = 0.9f
                },
            ),
            history = listOf(event),
        )

        assertEquals(
            listOf(
                "docs-concepts/tex/source/main.tex",
                "docs-concepts/tex/source/appendix.tex",
                "docs-concepts/tex/source/fallback.tex",
            ),
            item.detailSourceFiles(),
        )
        assertEquals(
            "left=0.100000, top=0.200000, right=0.750000, bottom=0.900000",
            item.normalizedRegionText(),
        )
        val skip = event.copy(rating = null, retractedEventId = null,
            queueAction = "skip", skipUntil = Instant.parse("2026-08-31T10:15:00Z"), skipId = "skip-1")
        assertEquals(com.gitlab.mudlej.MjPdfReader.R.string.review_history_skip, skip.historyMarkRes())
        assertEquals(com.gitlab.mudlej.MjPdfReader.R.string.review_history_undo_skip, skip.copy(queueAction = "undo_skip").historyMarkRes())
        val untagged = event.copy(rating = "custom_interval", retractedEventId = null, reviewTags = emptyList(),
            reviewTagsRecorded = true)
        assertEquals(com.gitlab.mudlej.MjPdfReader.R.string.review_tags_none, untagged.historyMarkRes())
        assertEquals(com.gitlab.mudlej.MjPdfReader.R.string.review_history_correct,
            untagged.copy(reviewTagsRecorded = false).historyMarkRes())
        assertTrue(skip.technicalText().contains("skip_until=2026-08-31T10:15:00Z"))
        assertTrue(skip.technicalText().contains("queue_action=skip"))
        val historyText = event.technicalText()
        listOf(
            "event_id=event-1",
            "rating=again",
            "reviewed_at=2026-08-30T10:15:00Z",
            "origin_id=phone-1",
            "revision=4",
            "updated_at=2026-08-30T10:16:00Z",
            "retracted_event_id=event-0",
            "retracted=true",
            "effective=false",
            "pending_sync=true",
            "source=incoming/events/event-1.json",
        ).forEach { expected -> assertTrue(historyText.contains(expected)) }
    }

    @Test fun linkingExplainsStaleAndPendingLocations() {
        assertEquals(R.string.overview_linking_no_annotation,
            browserCard("a", state = ReviewBrowserState.STALE).linkingLabelRes(true))
        assertEquals(R.string.overview_linking_no_annotation,
            browserCard("a", state = ReviewBrowserState.REPAIRING).linkingLabelRes(true))
    }

    @Test fun linkingDoesNotInferMissingMarkupFromMissingMetadata() {
        assertEquals(R.string.overview_linking_no_annotation, browserCard("a").linkingLabelRes(true))
        assertEquals(R.string.overview_linking_without_source, browserCard("a").linkingLabelRes(false))
        val item = browserCard("a")
        assertEquals(R.string.overview_linking_no_annotation, item.copy(card = item.card.copy(
            reanchorState = "needs_attention", reanchorReason = "review_source_marker_missing_or_ambiguous"
        )).linkingLabelRes(true))
    }

    @Test fun sourceMarkupDistinguishesLinkedFromFailedPlacement() {
        val item = browserCard("a")
        val anchored = item.card.copy(sourceAnchor = com.google.gson.JsonObject(), reanchorState = "succeeded")
        assertEquals(R.string.overview_linking_linked,
            item.copy(card = anchored).linkingLabelRes(true))
        assertEquals(R.string.review_linked_source_unavailable,
            item.copy(card = anchored.copy(sourceLocationState = "unavailable")).linkingLabelRes(true))
        assertEquals(R.string.review_linked_source_ambiguous,
            item.copy(card = anchored.copy(sourceLocationState = "ambiguous")).linkingLabelRes(true))
        assertEquals(R.string.review_linking_location_failed,
            item.copy(card = anchored.copy(reanchorState = "needs_attention")).linkingLabelRes(true))
        assertEquals(R.string.review_linking_location_failed,
            item.copy(card = anchored, state = ReviewBrowserState.STALE).linkingLabelRes(true))
        assertEquals(R.string.review_linking_location_failed,
            item.copy(card = anchored.copy(sourceLocationState = "unavailable"),
                state = ReviewBrowserState.STALE).linkingLabelRes(true))
    }

    @Test fun cardStatusUsesVerifiedSourceAndPlacementEvidence() {
        val item = browserCard("status")
        val anchored = item.card.copy(sourceAnchor = com.google.gson.JsonObject(), reanchorState = "succeeded")
        assertEquals("Linked", item.copy(card = anchored).cardStatusLabel())
        assertEquals("PDF only", item.copy(card = item.card.copy(sourceKind = "pdf")).cardStatusLabel())
        assertEquals("PDF only", item.copy(card = item.card.copy(sourceKind = "pdf", reanchorState = "")).cardStatusLabel())
        assertEquals("Link pending", item.copy(card = item.card.copy(sourceLinkState = "pending")).cardStatusLabel())
        assertEquals("Link failed", item.copy(card = item.card.copy(sourceLinkState = "linking_failed")).cardStatusLabel())
        assertEquals("Link removed", item.copy(card = anchored.copy(sourceLocationState = "missing")).cardStatusLabel())
        assertEquals("Source unavailable", item.copy(card = anchored.copy(sourceLocationState = "unavailable")).cardStatusLabel())
        assertEquals("Placement pending", item.copy(card = anchored.copy(reanchorState = "updating")).cardStatusLabel())
        assertEquals("Placement failed", item.copy(card = anchored.copy(reanchorState = "needs_attention")).cardStatusLabel())
        val failed = item.copy(card = anchored.copy(reanchorState = "needs_attention"), state = ReviewBrowserState.NEEDS_ATTENTION)
        assertFalse(failed.placementPending())
        assertFalse(failed.canReview())
        val stale = item.copy(card = anchored, state = ReviewBrowserState.STALE)
        assertTrue(stale.placementPending())
        assertEquals("Placement pending", stale.cardStatusLabel())
        assertFalse(stale.canReview())
    }

    @Test fun linkingLabelsPreserveUnknownSourceAvailability() {
        val allowed = setOf(R.string.overview_linking_linked, R.string.overview_linking_without_source,
            R.string.overview_linking_no_annotation, R.string.review_linking_location_failed, R.string.library_document_source_unknown)
        for (state in ReviewBrowserState.values()) {
            for (source in listOf(true, false, null)) {
                assertTrue(browserCard("a", state = state).linkingLabelRes(source) in allowed)
            }
        }
        assertEquals(R.string.library_document_source_unknown, browserCard("unknown").linkingLabelRes(null))
        val item = browserCard("pdf", state = ReviewBrowserState.STALE)
        assertEquals(R.string.overview_linking_without_source,
            item.copy(card = item.card.copy(sourceKind = "pdf")).linkingLabelRes(true))
        assertEquals(R.string.overview_linking_without_source,
            item.copy(card = item.card.copy(sourceKind = "pdf", sourceLocationState = "unavailable"))
                .linkingLabelRes(true))
    }

    @Test
    fun `header sorts use displayed names and numeric values in both directions`() {
        val low = browserCard("low", question = "apple", deck = "z-file", page = 2, sourcePath = "/z/alpha.tex")
            .copy(projectDisplayName = "Alpha project", documentDisplayName = "Alpha document", reviewCount = 2,
                lastReviewedAt = Instant.parse("2026-09-20T09:00:00Z"),
                createdAt = Instant.parse("2026-09-20T08:00:00Z"), state = ReviewBrowserState.FUTURE,
                dueAt = Instant.parse("2026-09-20T08:00:00Z"))
        val high = browserCard("high", question = "Zebra", deck = "a-file", page = 10, sourcePath = "/a/zebra.tex")
            .copy(projectDisplayName = "Zebra project", documentDisplayName = "Zebra document", reviewCount = 10,
                lastReviewedAt = Instant.parse("2026-09-22T09:00:00Z"),
                createdAt = Instant.parse("2026-09-22T08:00:00Z"), state = ReviewBrowserState.REVIEW,
                dueAt = Instant.parse("2026-09-22T08:00:00Z"))
            .let { it.copy(card = it.card.copy(sourceLocationState = "unavailable")) }
        for (column in ReviewCardBrowserColumn.entries) {
            for (descending in listOf(false, true)) {
                val result = reviewCardBrowserResult(listOf(high, low), null, "", ReviewCardBrowserFilter.ALL,
                    column.sort(), descending) { if (it.card.id == "low") "Alpha state" else "Zebra state" }
                assertEquals("$column descending=$descending", if (descending) listOf("high", "low") else listOf("low", "high"),
                    result.cards.map { it.card.id })
            }
        }
        assertEquals(ReviewCardBrowserColumn.CREATED, ReviewCardBrowserColumn.entries.first())
    }

    @Test
    fun `creation sorting keeps missing dates last and equal timestamps stable`() {
        val date = Instant.parse("2026-09-20T08:00:00Z")
        val cards = listOf(browserCard("missing"), browserCard("b").copy(createdAt = date), browserCard("a").copy(createdAt = date))
        for (descending in listOf(false, true)) {
            val result = reviewCardBrowserResult(cards, null, "", ReviewCardBrowserFilter.ALL, ReviewCardBrowserSort.CREATED, descending)
            assertEquals(listOf("a", "b", "missing"), result.cards.map { it.card.id })
        }
    }

    private fun browserCard(
        id: String,
        state: ReviewBrowserState = ReviewBrowserState.NEW,
        question: String = "Question $id",
        deck: String = "deck",
        page: Int = 1,
        dueAt: Instant? = null,
        sourcePath: String? = null,
    ): ReviewBrowserCard {
        val card = ReviewCard(
            id = id,
            status = "approved",
            pdfStem = deck,
            pdfSha256 = "a".repeat(64),
            page = page,
            pageVisualSha256 = "b".repeat(64),
            question = question,
            rect = RectF(),
            schedule = null,
            reanchorState = "ok",
        )
        return ReviewBrowserCard(
            card = card,
            state = state,
            dueAt = dueAt,
            intervalDays = 0,
            reviewCount = 0,
            lapseCount = 0,
            lastRating = null,
            lastReviewedAt = null,
            createdAt = null,
            updatedAt = null,
            revision = null,
            sourcePath = sourcePath,
            pendingSync = false,
            deleteEventId = null,
            history = emptyList(),
        )
    }
}
