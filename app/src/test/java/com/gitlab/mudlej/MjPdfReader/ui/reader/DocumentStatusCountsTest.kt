// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.review.PendingReviewChange
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.ui.usernotes.DocumentCommentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentStatusCountsTest {

    @Test
    fun cardsCountOnlyThisDocumentsKeptCards() {
        val cards = listOf(
            card("a", ReviewBrowserState.NEW),
            card("b", ReviewBrowserState.REVIEW),
            card("c", ReviewBrowserState.NEEDS_ATTENTION, reanchorState = "needs_attention"),
            card("deleted", ReviewBrowserState.DELETED),
            card("rejected", ReviewBrowserState.REJECTED),
            card("candidate", ReviewBrowserState.CANDIDATE, status = "candidate"),
            card("other", ReviewBrowserState.NEW, stem = "other"),
        )

        val counts = documentCardCounts(cards, "manual.pdf", dueCount = 2)

        assertEquals(DocumentCardCounts(total = 3, due = 2, new = 1, needsAttention = 1), counts)
    }

    @Test
    fun commentsLeaveOutRepliesAndCountOnlyOpenProposals() {
        val items = listOf(
            DocumentCommentItem(comment("c1"), isAgentProposal = false),
            DocumentCommentItem(comment("c2", anchorStatus = "orphaned"), isAgentProposal = false),
            DocumentCommentItem(comment("reply", parentId = "c1"), isAgentProposal = false),
            DocumentCommentItem(comment("agent-1"), isAgentProposal = true),
            DocumentCommentItem(comment("agent-remove-1"), isAgentProposal = true),
            DocumentCommentItem(comment("agent-decided"), isAgentProposal = true),
        )

        val counts = documentCommentCounts(items, activeProposalIds = setOf("agent-1"))

        assertEquals(DocumentCommentCounts(total = 2, withIssues = 1, pendingProposals = 1), counts)
    }

    @Test
    fun waitingCountsKeepOnlyThisDocumentsChanges() {
        val rows = listOf(
            outbox("1", "comment", "e1", "manual"),
            outbox("2", "annotation", "e2", "other"),
            outbox("3", "logical_comment", "c1", "comments"),
            outbox("4", "logical_comment", "elsewhere", "comments"),
            outbox("5", "comment", "e5", "manual", ThoughtsOutboxEntity.STATE_SUPERSEDED),
        )
        val reviews = listOf(
            PendingReviewChange("review_card", "k1", "manual", 0),
            PendingReviewChange("review_event", "e1", "manual", 0),
            PendingReviewChange("review_event", "e2", "manual", 0),
            PendingReviewChange("review_card", "k2", "other", 0),
        )
        val sources = listOf(
            mutation("tex/manual.tex", "manual.pdf"),
            mutation("tex/other.tex", "other.pdf"),
        )

        val waiting = documentWaitingCounts(
            filename = "manual.pdf",
            commentIds = setOf("c1"),
            outboxRows = rows,
            commentDeliveries = 3,
            reviewChanges = reviews,
            sourceMutations = sources,
            pendingProposalDecisions = 1,
        )

        assertEquals(DocumentWaitingCounts(commentChanges = 5, cardChanges = 1, reviewEvents = 2, sourceEdits = 1, proposalDecisions = 1), waiting)
    }

    @Test
    fun nothingWaitingIsEmpty() {
        assertTrue(DocumentWaitingCounts().isEmpty)
    }

    private fun card(
        id: String,
        state: ReviewBrowserState,
        stem: String = "manual",
        status: String = "approved",
        reanchorState: String = "ok",
    ) = ReviewBrowserCard(
        card = ReviewCard(
            id = id,
            status = status,
            pdfStem = stem,
            pdfSha256 = "a".repeat(64),
            page = 1,
            pageVisualSha256 = "b".repeat(64),
            question = "Question $id",
            rect = RectF(),
            schedule = null,
            reanchorState = reanchorState,
        ),
        state = state,
        dueAt = null,
        intervalDays = 0,
        reviewCount = 0,
        lapseCount = 0,
        lastRating = null,
        lastReviewedAt = null,
        createdAt = null,
        updatedAt = null,
        revision = null,
        sourcePath = null,
        pendingSync = false,
        deleteEventId = null,
        history = emptyList(),
    )

    private fun comment(id: String, parentId: String = "", anchorStatus: String = "anchored") =
        OverviewComment("manual", id, parentId, "Comment", "", "", "", 0, anchorStatus, 0, "Text", "", "", 0)

    private fun outbox(
        id: String,
        type: String,
        entityId: String,
        collection: String,
        state: String = ThoughtsOutboxEntity.STATE_PENDING,
    ) = ThoughtsOutboxEntity(
        operationId = id, deviceSequence = id.toLong(), entityType = type, entityId = entityId,
        collection = collection, baseRevision = null, clientTimestamp = "2026-10-16T09:00:00Z",
        payloadJson = "{}", createdAtEpochMillis = 0, deliveryState = state,
    )

    private fun mutation(sourceFile: String, pdf: String) = PendingSourceMutation(
        requestId = sourceFile,
        sourceFile = sourceFile,
        pdfFilename = pdf,
        state = SourceMutationState.QUEUED,
    )
}
