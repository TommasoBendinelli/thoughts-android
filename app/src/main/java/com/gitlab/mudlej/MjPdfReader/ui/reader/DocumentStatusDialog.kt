// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryOutbox
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.review.PendingReviewChange
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import com.gitlab.mudlej.MjPdfReader.data.source.matchesPdfTarget
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecisionPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.activeAgentProposals
import com.gitlab.mudlej.MjPdfReader.data.sync.agentProposalDecisionStates
import com.gitlab.mudlej.MjPdfReader.ui.home.DocumentStatusCode
import com.gitlab.mudlej.MjPdfReader.ui.home.OVERVIEW_ISSUE_STATUSES
import com.gitlab.mudlej.MjPdfReader.ui.home.documentStatusPresentation
import com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBuildDialogState
import com.gitlab.mudlej.MjPdfReader.ui.sync.SyncOperationsDialog
import com.gitlab.mudlej.MjPdfReader.ui.sync.formatSyncBuildTime
import com.gitlab.mudlej.MjPdfReader.ui.sync.loadSyncBuildDialogState
import com.gitlab.mudlej.MjPdfReader.ui.sync.projectScopedSyncFailureDetail
import com.gitlab.mudlej.MjPdfReader.ui.usernotes.DocumentCommentItem
import com.gitlab.mudlej.MjPdfReader.ui.usernotes.documentComments
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The PDF the status describes: in a Project reading, the document on the current page. */
internal data class DocumentStatusDocument(
    val filename: String,
    val uri: Uri?,
    val pdfSha256: String?,
)

internal data class DocumentCardCounts(
    val total: Int,
    val due: Int,
    val new: Int,
    val needsAttention: Int,
)

internal data class DocumentCommentCounts(
    val total: Int,
    val withIssues: Int,
    val pendingProposals: Int,
)

internal data class DocumentWaitingCounts(
    val commentChanges: Int = 0,
    val cardChanges: Int = 0,
    val reviewEvents: Int = 0,
    val sourceEdits: Int = 0,
    val proposalDecisions: Int = 0,
) {
    val isEmpty: Boolean
        get() = commentChanges + cardChanges + reviewEvents + sourceEdits + proposalDecisions == 0
}

internal data class DocumentStatusInfo(
    val filename: String,
    val projectName: String?,
    val sourceFiles: List<String>,
    val sync: SyncBuildDialogState?,
    val cards: DocumentCardCounts?,
    val comments: DocumentCommentCounts?,
    val waiting: DocumentWaitingCounts,
    val problems: List<String>,
)

/** Cards of this PDF that the user studies: approved and not deleted, retired or rejected, on any PDF version. */
internal fun documentCardCounts(
    cards: List<ReviewBrowserCard>,
    filename: String,
    dueCount: Int,
): DocumentCardCounts {
    val stem = filename.removeSuffix(".pdf")
    val kept = cards.filter {
        it.card.pdfStem.equals(stem, ignoreCase = true) && it.card.approved &&
            it.state !in setOf(ReviewBrowserState.DELETED, ReviewBrowserState.RETIRED, ReviewBrowserState.UNPUBLISHED,
                ReviewBrowserState.REJECTED, ReviewBrowserState.CANDIDATE)
    }
    return DocumentCardCounts(
        total = kept.size,
        due = dueCount,
        new = kept.count { it.state == ReviewBrowserState.NEW },
        needsAttention = kept.count { it.needsAction },
    )
}

/** User comments are top-level threads; replies and agent proposals are not counted as comments. */
internal fun documentCommentCounts(
    items: List<DocumentCommentItem>,
    activeProposalIds: Set<String>,
): DocumentCommentCounts {
    val comments = items.filter { !it.isAgentProposal && !it.comment.isReply }
    val proposals = items.filter { it.isAgentProposal }
        .map { it.comment.id.removePrefix("agent-remove-") }
        .filter { it in activeProposalIds }
        .distinct()
    return DocumentCommentCounts(
        total = comments.size,
        withIssues = comments.count { it.comment.anchorStatus in OVERVIEW_ISSUE_STATUSES },
        pendingProposals = proposals.size,
    )
}

/** Local changes of this PDF that the next Sync sends. */
internal fun documentWaitingCounts(
    filename: String,
    commentIds: Set<String>,
    outboxRows: List<ThoughtsOutboxEntity>,
    commentDeliveries: Int,
    reviewChanges: List<PendingReviewChange>,
    sourceMutations: List<PendingSourceMutation>,
    pendingProposalDecisions: Int,
): DocumentWaitingCounts {
    val stem = filename.removeSuffix(".pdf")
    val undelivered = outboxRows.filter { it.isUndelivered }
    val commentRows = undelivered.count { row ->
        when (row.entityType) {
            "annotation", "comment" -> row.collection.equals(stem, ignoreCase = true)
            "logical_comment" -> row.entityId in commentIds
            else -> false
        }
    }
    val documentReviewChanges = reviewChanges.filter { it.pdfStem.equals(stem, ignoreCase = true) }
    return DocumentWaitingCounts(
        commentChanges = commentRows + commentDeliveries,
        cardChanges = documentReviewChanges.count { it.entityType == "review_card" },
        reviewEvents = documentReviewChanges.count { it.entityType == "review_event" },
        sourceEdits = sourceMutations.count { it.matchesPdfTarget(filename) },
        proposalDecisions = pendingProposalDecisions,
    )
}

internal suspend fun loadDocumentStatus(
    context: Context,
    document: DocumentStatusDocument,
): DocumentStatusInfo {
    val appContext = context.applicationContext
    val filename = document.filename
    val sync = runCatching {
        val base = loadSyncBuildDialogState(appContext, filename, document.uri, includeServerStorage = false)
        val failure = withContext(Dispatchers.IO) {
            projectScopedSyncFailureDetail(
                records = SyncOperationStore(appContext).summaries(),
                target = filename,
                builtLabel = appContext.getString(R.string.operations_built),
            )
        }
        base.copy(latestFailureLabel = failure?.first, latestFailureDetail = failure?.second)
    }.onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w(TAG, "Sync status unavailable for $filename", it) }
        .getOrNull()
    return withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()
        val projectName = runCatching { projectNameFor(appContext, filename) }.getOrNull()
        val sourceFiles = runCatching {
            OfflineSourceBundleStore(appContext).librarySourceMetadata(filename)?.pdfs?.get(filename)?.segments.orEmpty()
                .flatMap { segment -> listOfNotNull(segment.primarySourceFile) }
                .distinct()
        }.getOrDefault(emptyList())

        val cards = runCatching {
            val store = ReviewStore(appContext)
            documentCardCounts(store.browserCards(), filename, store.dueCards(pdfStem = filename.removeSuffix(".pdf")).size)
        }.onFailure { Log.w(TAG, "Review cards unavailable for $filename", it) }.getOrNull()

        val outboxRows = runCatching { AppDatabase.getInstance(appContext).thoughtsSyncDao().allOutbox() }
            .getOrDefault(emptyList())
        var commentIds = emptySet<String>()
        var pendingDecisions = 0
        val comments = runCatching {
            val snapshot = LogicalCommentStore(appContext).snapshot(filename)
            val sha = document.pdfSha256 ?: computeSha256(appContext, document.uri)
            val items = documentComments(filename, emptyList(), snapshot, sha)
            commentIds = items.mapTo(mutableSetOf()) { it.comment.id }
            val proposals = snapshot.proposals.filter { proposal ->
                proposal.pdfFilenames.any { it.equals(filename, ignoreCase = true) }
            }
            val accepted = runCatching {
                SourceEditQueue(appContext, scheduleRequests = false)
                    .editorProposalDecisions(proposals.map { it.sourceFile }).accepted
            }.getOrDefault(emptySet())
            val open = proposals.filter { it.id !in accepted }
            val decisions = agentProposalDecisionStates(outboxRows, open)
            pendingDecisions = decisions.values.count { it.phase == AgentProposalDecisionPhase.PENDING }
            val failedDecisions = decisions.values.count { it.phase == AgentProposalDecisionPhase.FAILED }
            if (failedDecisions > 0) {
                problems += appContext.resources.getQuantityString(
                    R.plurals.document_status_problem_proposal_decisions, failedDecisions, failedDecisions,
                )
            }
            documentCommentCounts(items, activeAgentProposals(open, outboxRows).mapTo(mutableSetOf()) { it.id })
        }.onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "Comments unavailable for $filename", it) }
            .getOrNull()

        val sourceMutations = runCatching {
            SourceEditQueue(appContext, scheduleRequests = false).pendingSourceMutations()
                .filter { it.matchesPdfTarget(filename) }
        }.getOrDefault(emptyList())
        sourceMutations.filter { it.state == SourceMutationState.CONFLICT || it.state == SourceMutationState.FAILED }
            .forEach { mutation ->
                problems += appContext.getString(
                    if (mutation.state == SourceMutationState.CONFLICT) {
                        R.string.document_status_problem_source_conflict
                    } else {
                        R.string.document_status_problem_source_failed
                    },
                    mutation.sourceFile,
                ) + mutation.error.trim().takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()
            }
        val commentDeliveries = runCatching {
            CommentDeliveryOutbox.forContext(appContext).load()
                .filter { it.target == filename }
                .sumOf { it.records.size }
        }.getOrDefault(0)
        val stem = filename.removeSuffix(".pdf")
        outboxRows.filter { row ->
            row.deliveryState == ThoughtsOutboxEntity.STATE_BLOCKED && row.failureDetail.isNotBlank() &&
                (row.collection.equals(stem, ignoreCase = true) || row.entityId in commentIds)
        }.mapTo(problems) { it.failureDetail.trim() }

        val waiting = documentWaitingCounts(
            filename = filename,
            commentIds = commentIds,
            outboxRows = outboxRows,
            commentDeliveries = commentDeliveries,
            reviewChanges = runCatching { ReviewStore(appContext).pendingChanges() }.getOrDefault(emptyList()),
            sourceMutations = sourceMutations,
            pendingProposalDecisions = pendingDecisions,
        )
        DocumentStatusInfo(
            filename = filename,
            projectName = projectName,
            sourceFiles = sourceFiles,
            sync = sync,
            cards = cards,
            comments = comments,
            waiting = waiting,
            problems = problems.distinct(),
        )
    }
}

private fun projectNameFor(context: Context, filename: String): String? =
    LibraryProjectStore(context).projects()
        .mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
        .firstOrNull { project ->
            project.getAsJsonArray("documents")?.any { document ->
                document.isJsonObject &&
                    document.asJsonObject.get("filename")?.takeIf { it.isJsonPrimitive }?.asString == filename
            } == true
        }
        ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf(String::isNotBlank)

/** Shows what the phone knows about one document: build, Sync, cards, comments and problems. */
internal class DocumentStatusDialog(private val activity: MainActivity) {

    private var dialog: AlertDialog? = null

    fun show(document: DocumentStatusDocument) {
        dialog?.dismiss()
        val shown = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.document_status_title)
            .setMessage(R.string.document_status_loading)
            .setNeutralButton(R.string.sync_status_show_last_log) { _, _ ->
                SyncOperationsDialog(activity).showLatest(target = document.filename)
            }
            .setNegativeButton(R.string.close, null)
            .show()
        dialog = shown
        shown.setOnDismissListener { if (dialog === shown) dialog = null }
        activity.lifecycleScope.launch {
            val message = try {
                message(loadDocumentStatus(activity, document))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Document status failed for ${document.filename}", error)
                activity.getString(R.string.document_status_load_failed, error.message.orEmpty())
            }
            if (dialog === shown && shown.isShowing) shown.setMessage(message)
        }
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun message(info: DocumentStatusInfo): CharSequence = SpannableStringBuilder().apply {
        val res = activity.resources
        fun heading(text: String) {
            if (isNotEmpty()) append("\n\n")
            val start = length
            append(text)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun line(text: String) {
            if (isNotEmpty()) append('\n')
            append(text)
        }

        line(info.filename)
        info.projectName?.let { line(activity.getString(R.string.document_status_project, it)) }
        info.sourceFiles.forEach { line(activity.getString(R.string.document_status_source, it)) }

        heading(activity.getString(R.string.document_status_heading_pdf))
        val sync = info.sync
        if (sync == null) {
            line(activity.getString(R.string.document_status_unavailable))
        } else {
            sync.status?.let { line(activity.getString(R.string.sync_status_state, statusLabel(it))) }
            line(
                if (sync.lastSuccessfulBuildAtEpochMillis > 0) {
                    activity.getString(
                        R.string.document_status_built,
                        activity.formatSyncBuildTime(sync.lastSuccessfulBuildAtEpochMillis),
                    )
                } else {
                    activity.getString(R.string.document_status_built_unknown)
                },
            )
            line(
                if (sync.lastSuccessfulSyncAtEpochMillis > 0) {
                    activity.getString(
                        R.string.pdf_status_last_synced,
                        activity.formatSyncBuildTime(sync.lastSuccessfulSyncAtEpochMillis),
                    )
                } else {
                    activity.getString(R.string.pdf_status_last_synced_never)
                },
            )
        }

        heading(activity.getString(R.string.review_cards_title))
        val cards = info.cards
        when {
            cards == null -> line(activity.getString(R.string.document_status_unavailable))
            cards.total == 0 -> line(activity.getString(R.string.document_status_cards_none))
            else -> {
                line(res.getQuantityString(R.plurals.document_status_cards_total, cards.total, cards.total))
                line(res.getQuantityString(R.plurals.document_status_cards_due, cards.due, cards.due))
                if (cards.new > 0) line(res.getQuantityString(R.plurals.document_status_cards_new, cards.new, cards.new))
                if (cards.needsAttention > 0) {
                    line(res.getQuantityString(R.plurals.document_status_cards_attention, cards.needsAttention, cards.needsAttention))
                }
            }
        }

        heading(activity.getString(R.string.comments_title))
        val comments = info.comments
        if (comments == null) {
            line(activity.getString(R.string.document_status_unavailable))
        } else {
            line(
                if (comments.total == 0) {
                    activity.getString(R.string.document_status_comments_none)
                } else {
                    res.getQuantityString(R.plurals.document_status_comments_total, comments.total, comments.total)
                },
            )
            if (comments.withIssues > 0) {
                line(res.getQuantityString(R.plurals.document_status_comments_issues, comments.withIssues, comments.withIssues))
            }
            line(
                if (comments.pendingProposals == 0) {
                    activity.getString(R.string.document_status_proposals_none)
                } else {
                    res.getQuantityString(R.plurals.document_status_proposals, comments.pendingProposals, comments.pendingProposals)
                },
            )
        }

        heading(activity.getString(R.string.document_status_heading_waiting))
        val waiting = info.waiting
        if (waiting.isEmpty) {
            line(activity.getString(R.string.document_status_waiting_none))
        } else {
            if (waiting.commentChanges > 0) {
                line(res.getQuantityString(R.plurals.document_status_pending_comments, waiting.commentChanges, waiting.commentChanges))
            }
            if (waiting.cardChanges > 0) {
                line(res.getQuantityString(R.plurals.document_status_pending_cards, waiting.cardChanges, waiting.cardChanges))
            }
            if (waiting.reviewEvents > 0) {
                line(res.getQuantityString(R.plurals.document_status_pending_events, waiting.reviewEvents, waiting.reviewEvents))
            }
            if (waiting.sourceEdits > 0) {
                line(res.getQuantityString(R.plurals.document_status_pending_source_edits, waiting.sourceEdits, waiting.sourceEdits))
            }
            if (waiting.proposalDecisions > 0) {
                line(res.getQuantityString(R.plurals.document_status_pending_proposal_decisions, waiting.proposalDecisions, waiting.proposalDecisions))
            }
        }

        val problems = buildList {
            sync?.let { state ->
                (state.latestFailureDetail ?: state.status?.message?.takeIf {
                    documentStatusPresentation(state.status.stage, state.status.verifiedAtEpochMillis).code == DocumentStatusCode.ERROR
                })?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
            addAll(info.problems)
        }.distinct()
        if (problems.isNotEmpty()) {
            heading(activity.getString(R.string.document_status_heading_problems))
            problems.forEach { line("• $it") }
        }
    }

    private fun statusLabel(status: PhoneRenderJobStatus): String = activity.getString(
        when (documentStatusPresentation(status.stage, status.verifiedAtEpochMillis).code) {
            DocumentStatusCode.SYNC_NEEDED -> R.string.document_status_sync_needed
            DocumentStatusCode.SYNCING -> R.string.document_status_syncing
            DocumentStatusCode.SYNCED -> R.string.document_status_synced
            DocumentStatusCode.ERROR -> R.string.document_status_error
        },
    )
}

private const val TAG = "DocumentStatus"
