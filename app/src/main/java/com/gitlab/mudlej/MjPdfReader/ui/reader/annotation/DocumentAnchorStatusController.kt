// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import android.os.FileObserver
import android.util.Log
import android.view.MenuItem
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogue
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSidecarFlushResult
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryOutbox
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncCommandCoordinator
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderReceipt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DocumentAnchorState {
    HIDDEN,
    HEALTHY,
    PENDING,
    WARNING,
}

internal enum class CommentReaderIssueKind {
    PERSISTENCE,
    RESTORE,
}

internal data class CommentReaderIssue(
    val kind: CommentReaderIssueKind,
    val title: String,
    val details: List<String>,
)

private data class CommentAnchorRefreshSnapshot(
    val buildPending: Boolean,
    val deliveryPending: Boolean,
    val liveComments: Int,
    val unanchoredComments: Int,
)

internal class CommentIssueRegistry {
    private val issues = linkedMapOf<CommentReaderIssueKind, CommentReaderIssue>()

    val hasIssues: Boolean
        get() = issues.isNotEmpty()

    fun update(kind: CommentReaderIssueKind, issue: CommentReaderIssue?) {
        if (issue == null) {
            issues.remove(kind)
        } else {
            issues[kind] = issue
        }
    }

    fun clear() {
        issues.clear()
    }

    fun snapshot(): List<CommentReaderIssue> = issues.values.toList()
}

internal fun documentAnchorState(
    managed: Boolean,
    pending: Boolean,
    unanchoredComments: Int,
    hasReaderIssues: Boolean = false,
): DocumentAnchorState {
    return when {
        hasReaderIssues -> DocumentAnchorState.WARNING
        !managed -> DocumentAnchorState.HIDDEN
        pending -> DocumentAnchorState.PENDING
        unanchoredComments > 0 -> DocumentAnchorState.WARNING
        else -> DocumentAnchorState.HEALTHY
    }
}

class DocumentAnchorStatusController(
    private val activity: android.app.Activity,
    private val scope: CoroutineScope,
    private val currentFile: () -> File?,
    private val invalidateToolbar: () -> Unit,
    private val currentPdfSha256: () -> String? = { null },
    private val onPublishedPdfReady: (PhoneRenderReceipt.PublishedPdf) -> Unit = {},
    private val renderQueue: PhoneRenderQueue = PhoneRenderQueue(),
    private val catalogue: CommentCatalogue = CommentCatalogue(context = activity.applicationContext),
) {
    private var state = DocumentAnchorState.HIDDEN
    private var managed = false
    private var pending = false
    private var buildPending = false
    private var deliveryPending = false
    private var liveComments = 0
    private var unanchoredComments = 0
    private val readerIssues = CommentIssueRegistry()
    private var menuHasReaderIssues = false
    private var receiptObserver: FileObserver? = null
    private var commentObserver: Job? = null
    private var receiptJob: Job? = null
    private var receiptPollJob: Job? = null
    private var publishedPdfWaitJob: Job? = null
    private var confirmedPdfSha256: String? = null
    private val deliveryOutbox = CommentDeliveryOutbox.forContext(activity)

    fun onDocumentLoaded() {
        stopObservers()
        val file = managedCurrentFile()
        if (file == null) {
            managed = false
            pending = false
            buildPending = false
            deliveryPending = false
            liveComments = 0
            unanchoredComments = 0
            recomputeState()
            return
        }
        managed = true
        receiptObserver = runCatching {
            renderQueue.observe(::scheduleReceiptCheck)
        }.getOrNull()
        commentObserver = scope.launch {
            catalogue.changes().collect { refreshState() }
        }
        receiptPollJob = scope.launch {
            while (isActive) {
                delay(RECEIPT_POLL_MS)
                scheduleReceiptCheck()
            }
        }
        scheduleReceiptCheck()
        scope.launch { refreshState() }
    }

    fun onDocumentReset() {
        stopObservers()
        readerIssues.clear()
        menuHasReaderIssues = false
        managed = false
        pending = false
        buildPending = false
        deliveryPending = false
        updateState(DocumentAnchorState.HIDDEN, 0, 0)
    }

    fun close() {
        stopObservers()
    }

    fun onCommentsFlushed(result: CommentSidecarFlushResult) {
        catalogue.invalidate()
        val file = result.documentUri
            .takeIf { it.scheme == "file" }
            ?.path
            ?.let(::File)
            ?.takeIf(renderQueue::canRequestPdf)
            ?: return
        val isCurrentDocument = managedCurrentFile()?.canonicalPath == file.canonicalPath
        if (result.file.nameWithoutExtension != "${file.nameWithoutExtension}_comments") {
            return
        }
        if (isCurrentDocument) {
            scope.launch { refreshState() }
        }
    }

    fun onCommentPersistenceChanged(saved: Boolean, file: File?, error: Throwable?) {
        activity.runOnUiThread {
            if (saved) {
                readerIssues.update(CommentReaderIssueKind.PERSISTENCE, null)
            } else {
                val destination = file?.path
                    ?: activity.getString(R.string.comment_status_unknown_destination)
                val cause = deepestMessage(error)
                Log.e(TAG, "Comment sidecar autosave failed for $destination", error)
                readerIssues.update(
                    CommentReaderIssueKind.PERSISTENCE,
                    CommentReaderIssue(
                        CommentReaderIssueKind.PERSISTENCE,
                        activity.getString(R.string.comment_status_save_issue_title),
                        listOf(
                            activity.getString(
                                R.string.comment_status_save_issue_message,
                                destination,
                            ),
                            cause,
                        ).distinct(),
                    ),
                )
            }
            recomputeState()
        }
    }

    fun onCommentRestoreChanged(failures: List<CommentRestoreFailure>) {
        activity.runOnUiThread {
            val issue = failures.takeIf(List<CommentRestoreFailure>::isNotEmpty)?.let {
                CommentReaderIssue(
                    CommentReaderIssueKind.RESTORE,
                    activity.getString(R.string.comment_status_restore_issue_title),
                    buildList {
                        add(
                            activity.resources.getQuantityString(
                                R.plurals.comment_status_restore_issue_message,
                                failures.size,
                                failures.size,
                            ),
                        )
                        failures.forEach { failure -> add(restoreFailureMessage(failure)) }
                    },
                )
            }
            readerIssues.update(CommentReaderIssueKind.RESTORE, issue)
            recomputeState()
        }
    }

    fun updateMenuItem(item: MenuItem?) {
        if (item == null) {
            return
        }
        item.isVisible = state != DocumentAnchorState.HIDDEN
        if (!item.isVisible) {
            return
        }
        if (readerIssues.hasIssues) {
            item.setIcon(R.drawable.ic_link_warning)
            item.setTitle(R.string.comment_status_problem_title)
            return
        }
        when (state) {
            DocumentAnchorState.HEALTHY -> {
                item.setIcon(R.drawable.ic_link)
                item.setTitle(R.string.comment_anchor_status_healthy_title)
            }
            DocumentAnchorState.PENDING -> {
                item.setIcon(R.drawable.ic_schedule_small)
                item.setTitle(R.string.comment_anchor_status_pending_title)
            }
            DocumentAnchorState.WARNING -> {
                item.setIcon(R.drawable.ic_link_warning)
                item.setTitle(R.string.comment_anchor_status_warning_title)
            }
            DocumentAnchorState.HIDDEN -> Unit
        }
    }

    fun showStatus() {
        if (readerIssues.hasIssues) {
            showDialog(
                R.string.comment_status_title,
                combinedStatusMessage(),
                offerSync = managed && unanchoredComments > 0 && !pending,
            )
            return
        }
        when (state) {
            DocumentAnchorState.HIDDEN -> return
            DocumentAnchorState.HEALTHY -> showDialog(
                R.string.comment_anchor_status_healthy_title,
                activity.getString(
                    R.string.comment_anchor_status_healthy_message,
                    liveComments,
                ),
                offerSync = false,
            )
            DocumentAnchorState.PENDING -> showDialog(
                R.string.comment_anchor_status_pending_title,
                pendingMessage(),
                offerSync = false,
            )
            DocumentAnchorState.WARNING -> showDialog(
                R.string.comment_anchor_status_warning_title,
                activity.resources.getQuantityString(
                    R.plurals.comment_anchor_status_warning_message,
                    unanchoredComments,
                    unanchoredComments,
                ),
                offerSync = true,
            )
        }
    }

    private fun scheduleReceiptCheck() {
        receiptJob?.cancel()
        receiptJob = scope.launch {
            delay(RECEIPT_DEBOUNCE_MS)
            val file = managedCurrentFile() ?: return@launch
            val receipts = withContext(Dispatchers.IO) {
                renderQueue.consumeCompletedReceipts(file.name)
            }
            if (receipts.isEmpty()) {
                refreshState()
                return@launch
            }
            catalogue.invalidate()
            refreshState()
            receipts.forEach { receipt ->
                if (receipt.succeeded) {
                    receipt.publishedPdf?.let(::awaitPublishedPdf)
                }
            }
        }
    }

    private fun awaitPublishedPdf(published: PhoneRenderReceipt.PublishedPdf) {
        if (published.sha256.isBlank()) {
            return
        }
        publishedPdfWaitJob?.cancel()
        publishedPdfWaitJob = scope.launch {
            repeat(PUBLISHED_PDF_WAIT_ATTEMPTS) {
                val file = managedCurrentFile() ?: return@launch
                val matches = withContext(Dispatchers.IO) {
                    renderQueue.matchesPublishedPdf(file, published)
                }
                if (matches) {
                    confirmedPdfSha256 = published.sha256
                    if (currentPdfSha256() != published.sha256) {
                        onPublishedPdfReady(published)
                    }
                    return@launch
                }
                delay(PUBLISHED_PDF_WAIT_MS)
            }
        }
    }

    private suspend fun refreshState() {
        val file = managedCurrentFile()
        if (file == null) {
            managed = false
            pending = false
            buildPending = false
            deliveryPending = false
            liveComments = 0
            unanchoredComments = 0
            recomputeState()
            return
        }
        val snapshot = withContext(Dispatchers.IO) {
            val metadata = catalogue.loadSnapshot().metadataFor(file.nameWithoutExtension)
            CommentAnchorRefreshSnapshot(
                buildPending = renderQueue.hasPendingPdf(file),
                deliveryPending = deliveryOutbox.hasPendingTarget(file.name),
                liveComments = metadata.liveComments,
                unanchoredComments = metadata.unanchoredComments,
            )
        }
        managed = true
        buildPending = snapshot.buildPending
        deliveryPending = snapshot.deliveryPending
        pending = buildPending || deliveryPending
        liveComments = snapshot.liveComments
        unanchoredComments = snapshot.unanchoredComments
        recomputeState()
    }

    private fun recomputeState() {
        val hasReaderIssues = readerIssues.hasIssues
        val issueVisibilityChanged = menuHasReaderIssues != hasReaderIssues
        menuHasReaderIssues = hasReaderIssues
        updateState(
            documentAnchorState(
                managed = managed,
                pending = pending,
                unanchoredComments = unanchoredComments,
                hasReaderIssues = hasReaderIssues,
            ),
            liveComments,
            unanchoredComments,
            forceInvalidate = issueVisibilityChanged,
        )
    }

    private fun combinedStatusMessage(): String {
        val sections = mutableListOf<String>()
        if (managed) {
            val anchorStatus = when {
                pending -> pendingMessage()
                unanchoredComments > 0 -> activity.resources.getQuantityString(
                    R.plurals.comment_anchor_status_warning_message,
                    unanchoredComments,
                    unanchoredComments,
                )
                else -> activity.getString(
                    R.string.comment_anchor_status_healthy_message,
                    liveComments,
                )
            }
            sections += activity.getString(R.string.comment_status_source_section) +
                "\n" + anchorStatus
        }
        val issueText = readerIssues.snapshot().joinToString("\n\n") { issue ->
            buildString {
                append(issue.title)
                issue.details.forEach { detail ->
                    append("\n\u2022 ")
                    append(detail)
                }
            }
        }
        sections += activity.getString(R.string.comment_status_reader_errors_section) +
            "\n" + issueText
        return sections.joinToString("\n\n")
    }

    private fun pendingMessage(): String = activity.getString(
        if (deliveryPending) {
            R.string.comment_anchor_status_sync_pending_message
        } else {
            R.string.comment_anchor_status_pending_message
        }
    )

    private fun restoreFailureMessage(failure: CommentRestoreFailure): String =
        when (failure.kind) {
            CommentRestoreFailureKind.REMOVE_EXISTING -> activity.getString(
                R.string.comment_status_failure_remove,
                failure.commentId,
            )
            CommentRestoreFailureKind.ROOT -> activity.getString(
                R.string.comment_status_failure_root,
                failure.commentId,
            )
            CommentRestoreFailureKind.REPLY -> activity.getString(
                R.string.comment_status_failure_reply,
                failure.commentId,
            )
            CommentRestoreFailureKind.MISSING_PARENT -> activity.getString(
                R.string.comment_status_failure_missing_parent,
                failure.commentId,
                failure.parentId,
            )
        }

    private fun deepestMessage(error: Throwable?): String {
        var cause = error
        val seen = mutableSetOf<Throwable>()
        while (cause?.cause != null && seen.add(cause)) {
            cause = cause.cause
        }
        return cause?.message?.takeIf(String::isNotBlank)
            ?: activity.getString(R.string.comment_status_unknown_error)
    }

    private fun updateState(
        nextState: DocumentAnchorState,
        nextLiveComments: Int,
        nextUnanchoredComments: Int,
        forceInvalidate: Boolean = false,
    ) {
        val changed = forceInvalidate ||
            state != nextState ||
            liveComments != nextLiveComments ||
            unanchoredComments != nextUnanchoredComments
        state = nextState
        liveComments = nextLiveComments
        unanchoredComments = nextUnanchoredComments
        if (changed && !activity.isFinishing && !activity.isDestroyed) {
            invalidateToolbar()
        }
    }

    private fun showWarning(message: String) {
        showDialog(
            R.string.comment_anchor_status_warning_title,
            message,
            offerSync = true,
        )
    }

    private fun showDialog(title: Int, message: String, offerSync: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.ok, null)
            .apply {
                if (offerSync) {
                    setPositiveButton(R.string.document_status_sync) { _, _ ->
                        requestManualSync()
                    }
                }
            }
            .show()
    }

    private fun requestManualSync() {
        managedCurrentFile() ?: return
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SyncCommandCoordinator(activity.applicationContext).start(target = null)
                }
            }
            if (result.isFailure || result.getOrNull()?.let { !it.accepted && !it.reused } == true) {
                showWarning(activity.getString(R.string.comment_anchor_queue_failed))
            } else {
                refreshState()
                scheduleReceiptCheck()
            }
        }
    }

    private fun managedCurrentFile(): File? {
        return currentFile()?.takeIf(renderQueue::canRequestPdf)
    }

    private fun stopObservers() {
        receiptJob?.cancel()
        receiptJob = null
        receiptPollJob?.cancel()
        receiptPollJob = null
        publishedPdfWaitJob?.cancel()
        publishedPdfWaitJob = null
        receiptObserver?.stopWatching()
        receiptObserver = null
        commentObserver?.cancel()
        commentObserver = null
    }

    companion object {
        private const val TAG = "DocumentAnchorStatus"
        private const val RECEIPT_DEBOUNCE_MS = 300L
        private const val RECEIPT_POLL_MS = 5_000L
        private const val PUBLISHED_PDF_WAIT_MS = 1_000L
        private const val PUBLISHED_PDF_WAIT_ATTEMPTS = 120
    }
}
