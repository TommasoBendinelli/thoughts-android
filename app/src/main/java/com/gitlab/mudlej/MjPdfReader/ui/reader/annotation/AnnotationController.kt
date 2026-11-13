// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore
import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.preview.TagSource
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationJournal
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSidecarStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCreationMetadata
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSidecarFlushResult
import com.gitlab.mudlej.MjPdfReader.data.annotation.SourceKey
import com.gitlab.mudlej.MjPdfReader.data.annotation.sourceAnchorUnresolvedCommentNames
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedCommentIdentity
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

enum class CommentRestoreFailureKind {
    REMOVE_EXISTING,
    ROOT,
    REPLY,
    MISSING_PARENT,
}

data class CommentRestoreFailure(
    val kind: CommentRestoreFailureKind,
    val commentId: String,
    val parentId: String = "",
)

internal enum class CommentOpenSource {
    SIDECAR,
    EMBEDDED_IMPORT,
    BLOCKED,
}

internal fun commentOpenSource(
    sidecarExists: Boolean,
    identity: SourceLinkedCommentIdentity,
): CommentOpenSource = when {
    sidecarExists -> CommentOpenSource.SIDECAR
    identity == SourceLinkedCommentIdentity.VERIFIED -> CommentOpenSource.SIDECAR
    identity == SourceLinkedCommentIdentity.NOT_SOURCE_LINKED -> CommentOpenSource.EMBEDDED_IMPORT
    else -> CommentOpenSource.BLOCKED
}

class AnnotationController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val vm: ReaderViewModel,
    private val historyPolicy: HistoryPolicy,
    private val scope: CoroutineScope,
    private val onCommentPersistenceChanged: (Boolean, File?, Throwable?) -> Unit,
    private val onCommentRestoreChanged: (List<CommentRestoreFailure>) -> Unit,
    private val onCommentsFlushed: (CommentSidecarFlushResult) -> Unit,
) {
    data class CommentRootMetadata(
        val priority: CommentPriority = CommentPriority.NORMAL,
        val location: CommentLocation? = null,
        val color: Int = DEFAULT_COMMENT_COLOR,
        val rect: RectF? = null,
        val needsAction: Boolean = false,
    )

    private val logicalStore = com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(context)
    private var logicalChangesJob: Job? = null
    private val journal = AnnotationJournal(context)
    private val commentSidecars = CommentSidecarStore(context)
    private val pdf get() = vm.doc
    private var watchedCommentUri: Uri? = null
    private var watchedDocumentName: String? = null
    private var loadedCommentFingerprint: String? = null

    private val pageContentTags = ConcurrentHashMap<Int, Int>()
    private val commentRootMetadata = ConcurrentHashMap<String, CommentRootMetadata>()
    private val commentChatGptUrls = ConcurrentHashMap<String, String>()

    val previewTagSource = TagSource { page -> pageContentTags[page] ?: 0 }

    val currentSaveDestinationUri: Uri?
        get() = vm.annotationSaveDestinationUri
    val currentSaveDestinationDurable: Boolean
        get() = vm.annotationSaveDestinationDurable
    val hasUnsavedAnnotations: Boolean
        get() = vm.hasUnsavedAnnotations
    val isSaving: Boolean
        get() = vm.isSavingAnnotations

    fun setCurrentSaveDestination(uri: Uri?, durable: Boolean = true) {
        vm.annotationSaveDestinationUri = uri
        vm.annotationSaveDestinationDurable = uri != null && durable
    }

    fun markDirty() {
        vm.hasUnsavedAnnotations = true
    }

    fun clearDirty() {
        vm.hasUnsavedAnnotations = false
    }

    fun setSaving(saving: Boolean) {
        vm.isSavingAnnotations = saving
    }

    fun recordCommentEdits(edits: List<AnnotationEdit>): Boolean {
        val uri = pdf.uri ?: return false
        val hash = pdf.annotationPdfSha256 ?: return false
        if (!canPersistCommentEdit(pdf.sourceLinkedCommentIdentity, true)) return false
        return runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val reading = pdf.projectReading
                if (reading == null) logicalStore.edit(pdf.name, hash, edits)
                else {
                    val batches = edits.groupBy { requireNotNull(reading.locate(it.page)) { "The comment has no document location" } }
                    val mapped = batches.map { (document, commands) ->
                        document to commands.map { it.forReadingDocument(document.reading) }
                    }
                    logicalStore.editDocuments(mapped.map { (document, commands) ->
                        LogicalCommentStore.DocumentEdits(document.filename, document.reading.pdfSha256, commands)
                    })
                }
            }
            edits.forEach(::updateCommentMetadata)
            vm.sessionOwnedAnnotationKeys.add(SourceKey.of(uri))
            // The comment watcher reloads once this write lands.
            onCommentPersistenceChanged(true, null, null)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Could not save logical comment", error)
            onCommentPersistenceChanged(false, null, error)
            false
        }
    }

    fun recordEdit(edit: AnnotationEdit): Boolean {
        if (edit.isPdfCommentEdit()) return recordCommentEdits(listOf(edit))
        val uri = pdf.uri ?: return false
        applyContentTag(edit)
        if (historyPolicy.canRecord()) journal.append(uri, edit)
        vm.sessionOwnedAnnotationKeys.add(SourceKey.of(uri))
        vm.hasUnsavedAnnotations = true
        return true
    }

    fun commentMetadata(name: String): CommentRootMetadata? =
        commentRootMetadata[name]

    fun commentChatGptUrl(name: String): String? = commentChatGptUrls[name]

    private fun updateCommentMetadata(edit: AnnotationEdit) {
        when (edit) {
            is AnnotationEdit.AddPdfComment -> commentRootMetadata[edit.name] =
                CommentRootMetadata(edit.priority, edit.location, edit.color, RectF(edit.rect), edit.needsAction)
            is AnnotationEdit.AddPdfMarkupComment -> commentRootMetadata[edit.name] =
                CommentRootMetadata(edit.priority, edit.location, edit.color, needsAction = edit.needsAction)
            is AnnotationEdit.SetPdfCommentMetadata -> {
                val prior = commentRootMetadata[edit.name] ?: CommentRootMetadata()
                commentRootMetadata[edit.name] = prior.copy(
                    priority = edit.priority,
                    location = edit.location,
                    color = edit.color ?: prior.color,
                    rect = edit.rect?.let(::RectF) ?: prior.rect,
                    needsAction = edit.needsAction ?: prior.needsAction,
                )
            }
            is AnnotationEdit.DeletePdfComment -> {
                commentRootMetadata.remove(edit.name)
                commentChatGptUrls.remove(edit.name)
            }
            else -> Unit
        }
    }

    suspend fun refreshCanonicalComments(documentName: String?): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        val uri = pdf.uri ?: return false
        val openedHash = pdf.annotationPdfSha256
        val reading = pdf.projectReading
        // Watch before reading, so a change made during this load still triggers a reload and
        // the watch needs no initial emission (which used to repeat the whole load).
        observeLogicalComments(uri, documentName)
        val sidecarExists = withContext(Dispatchers.IO) {
            commentSidecars.sidecarFile(uri, documentName).isFile
        }
        val source = commentOpenSource(sidecarExists, pdf.sourceLinkedCommentIdentity)
        if (reading == null && source == CommentOpenSource.BLOCKED) {
            withContext(Dispatchers.Main) {
                binding.pdfView.setManagedPdfComments(emptyList())
            }
            Log.i(
                TAG,
                "managed-comment-open source=$source active=0 elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            return false
        }
        // Reading every annotation in a large PDF takes over a second, so it is done once per file version.
        val scanEmbedded = source == CommentOpenSource.EMBEDDED_IMPORT || reading != null
        val embeddedImported = scanEmbedded && openedHash != null && documentName != null && withContext(Dispatchers.IO) {
            !commentSidecars.hasPending(uri) && logicalStore.embeddedImportDone(documentName, openedHash)
        }
        val embedded = if (scanEmbedded && !embeddedImported) {
            withContext(Dispatchers.Default) {
                binding.pdfView.getAllPdfCommentsIncludingFragments()
            }
        } else {
            emptyList()
        }
        if (openedHash == null || documentName == null) return false
        val snapshot = runCatching { withContext(Dispatchers.IO) {
            // Finish pre-upgrade journals before retiring their active storage path.
            if (commentSidecars.hasPending(uri)) {
                commentSidecars.flush(uri, documentName, embedded, documentName).getOrThrow()
            }
            if (reading == null) {
                if (!embeddedImported) logicalStore.importDocument(uri, documentName, openedHash, embedded)
            } else for (document in reading.documents) {
                val offset = document.reading.firstPage
                val imported = document.sourcePackage.get("kind")?.asString == "thoughts-document-reading-package"
                val localComments = if (!imported) emptyList() else embedded.filter {
                    it.pageIndex in offset until offset + document.reading.pages
                }.map { comment -> PDFView.PdfComment(comment.pageIndex - offset, comment.annotationIndex,
                    comment.subtype, comment.name, comment.viewBounds, comment.pdfBounds, comment.color,
                    comment.contents, comment.author, comment.creationDate, comment.modifiedDate,
                    comment.inReplyToName, comment.anchorStatus).withPdfRects(comment.pdfRects) }

                logicalStore.importDocument(Uri.fromFile(document.file), document.filename, document.reading.pdfSha256, localComments)
            }
            if (scanEmbedded && !embeddedImported) logicalStore.markEmbeddedImported(documentName, openedHash)
            logicalStore.snapshot(if (reading == null) documentName else null)
        } }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "Could not restore canonical comments", error)
            withContext(Dispatchers.Main) { onCommentPersistenceChanged(false, commentSidecars.sidecarFile(uri, documentName), error) }
            return false
        }
        if (pdf.uri != uri || pdf.annotationPdfSha256 != openedHash) return false
        val pageSizes = withContext(Dispatchers.Main) { binding.pdfView.commentPageSizes() }
        val renderableComments = withContext(Dispatchers.Default) {
            if (reading == null) resolveStoredPdfComments(emptyList(), snapshot.logicalComments, documentName, openedHash) { pageSizes[it] }
            else reading.documents.flatMap { document ->
                val offset = document.reading.firstPage
                resolveStoredPdfComments(emptyList(), snapshot.logicalComments, document.filename, document.reading.pdfSha256) { pageSizes[it + offset] }
                    .map { comment -> comment.copy(pageIndex = comment.pageIndex + offset,
                        fragments = comment.fragments.map { it.copy(pageIndex = it.pageIndex + offset) }) }
            }
        }
        val renderingFingerprint = "${openedHash.orEmpty()}:${snapshot.bundleId}:${renderableComments.hashCode()}:${snapshot.logicalComments.hashCode()}"
        val restoreFailures = withContext(Dispatchers.Main) {
            if (pdf.uri != uri || pdf.annotationPdfSha256 != openedHash) return@withContext listOf(CommentRestoreFailure(CommentRestoreFailureKind.ROOT, "stale-generation"))
            binding.pdfView.setSourceAnchorUnresolvedCommentNames(
                sourceAnchorUnresolvedCommentNames(renderableComments)
            )
            if (renderingFingerprint == loadedCommentFingerprint) {
                emptyList<CommentRestoreFailure>()
            } else {
                binding.pdfView.setManagedPdfComments(managedPdfCommentRecords(renderableComments))
                if (binding.pdfView.isAnnotationRendering) {
                    binding.pdfView.enableAnnotationRendering(false)
                    binding.pdfView.reloadPages()
                }
                resetContentTags()
                emptyList<CommentRestoreFailure>()
            }
        }
        val applied = restoreFailures.isEmpty()
        if (applied) {
            // One payload parse per root, off the main thread; this scaled with roots × comments.
            val rootMetadata = withContext(Dispatchers.Default) {
                val logicalById = snapshot.logicalComments.associateBy { it.id }
                renderableComments.filter { it.inReplyTo.isBlank() }.associate { comment ->
                    comment.id to CommentRootMetadata(
                        priority = comment.priority,
                        location = comment.location,
                        color = comment.color,
                        rect = comment.rects.firstOrNull()?.let(::RectF),
                        needsAction = logicalById[comment.id]?.let {
                            com.google.gson.JsonParser.parseString(it.payloadJson).asJsonObject.get("needs_action")?.asBoolean
                        } == true,
                    )
                }
            }
            commentRootMetadata.clear()
            commentChatGptUrls.clear()
            renderableComments.forEach { comment ->
                comment.chatGptUrl.takeIf(String::isNotBlank)?.let { url ->
                    commentChatGptUrls[comment.id] = url
                }
            }
            commentRootMetadata.putAll(rootMetadata)
        }
        if (applied) {
            loadedCommentFingerprint = renderingFingerprint
            // Reuse this load's snapshot when it has the scope the overlays read (the whole document).
            val reusable = snapshot.takeIf { reading != null || documentName == pdf.name }
            withContext(Dispatchers.Main) { (context as? com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity)?.applyLogicalCommentOverlays(reusable) }
        }
        onCommentPersistenceChanged(true, null, null)
        onCommentRestoreChanged(restoreFailures)
        Log.i(
            TAG,
            "managed-comment-open source=$source active=${renderableComments.size} stored=${snapshot.logicalComments.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
        return applied
    }

    private fun observeLogicalComments(uri: Uri, documentName: String?) {
        if (
            watchedCommentUri == uri &&
            watchedDocumentName == documentName &&
            logicalChangesJob != null
        ) {
            return
        }
        stopObservingLogicalComments()
        watchedCommentUri = uri
        watchedDocumentName = documentName
        logicalChangesJob?.cancel()
        logicalChangesJob = scope.launch {
            // One edit lands as several transactions; reload once after they settle, not per transaction.
            logicalStore.changes(emitInitialState = false).collectLatest {
                kotlinx.coroutines.delay(COMMENT_REFRESH_DEBOUNCE_MS)
                if (pdf.uri == uri) refreshCanonicalComments(documentName)
            }
        }
    }

    fun resetForDocument() {
        stopObservingLogicalComments()
        loadedCommentFingerprint = null
        commentRootMetadata.clear()
        commentChatGptUrls.clear()
        binding.pdfView.clearManagedPdfComments()
        binding.pdfView.setSourceAnchorUnresolvedCommentNames(emptySet())
        resetContentTags()
    }

    fun close() { stopObservingLogicalComments() }

    private fun stopObservingLogicalComments() {
        logicalChangesJob?.cancel()
        logicalChangesJob = null
        watchedCommentUri = null
        watchedDocumentName = null
    }

    fun resetContentTags() {
        pageContentTags.clear()
    }

    private fun applyContentTag(edit: AnnotationEdit) {
        val hash = renderAffectingHash(edit) ?: return
        val pages = when (edit) {
            is AnnotationEdit.Add -> edit.fragments.map { it.page }
            is AnnotationEdit.AddPdfMarkupComment -> edit.fragments.map { it.page }
            else -> listOf(edit.page)
        }
        pages.distinct().forEach { page ->
            val previous = pageContentTags[page] ?: 0
            pageContentTags[page] = 31 * previous + hash
        }
    }

    private fun renderAffectingHash(edit: AnnotationEdit): Int? = when (edit) {
        is AnnotationEdit.AddPdfComment,
        is AnnotationEdit.AddPdfMarkupComment,
        is AnnotationEdit.AddPdfReply,
        is AnnotationEdit.DeletePdfComment,
        is AnnotationEdit.AddSignature,
        is AnnotationEdit.SetFieldText,
        is AnnotationEdit.SetFieldChecked -> edit.toJsonLine().hashCode()
        is AnnotationEdit.SetPdfCommentMetadata ->
            if (edit.color != null || edit.rect != null) edit.toJsonLine().hashCode() else null
        else -> null
    }

    fun hasJournal(uri: Uri?): Boolean {
        return uri != null && journal.hasRecords(uri)
    }

    fun isSessionOwned(uri: Uri?): Boolean {
        return uri != null && SourceKey.of(uri) in vm.sessionOwnedAnnotationKeys
    }

    fun markSessionOwned(uri: Uri?) {
        uri?.let { vm.sessionOwnedAnnotationKeys.add(SourceKey.of(it)) }
    }

    fun clearJournal(uri: Uri? = pdf.uri) {
        uri?.let(journal::delete)
        pageContentTags.clear()
        vm.hasUnsavedAnnotations = false
    }

    suspend fun replayJournal(): Boolean {
        val uri = pdf.uri ?: return false
        val edits = withContext(Dispatchers.IO) { journal.readAll(uri) }
        if (edits.isEmpty()) {
            return false
        }
        withContext(Dispatchers.Main) {
            pageContentTags.clear()
            edits.forEach(::applyEdit)
        }
        // Native replay is only an input decoder for old journals. Drawing and panels then reload
        // from canonical state, which also prevents an old restore from undoing a deletion.
        val hash = pdf.annotationPdfSha256
        if (hash != null) {
            val recovered = withContext(Dispatchers.Default) { binding.pdfView.getAllPdfCommentsIncludingFragments() }
            withContext(Dispatchers.IO) { logicalStore.importRecoveredComments(pdf.name, hash, recovered) }
            refreshCanonicalComments(pdf.name)
        }
        markSessionOwned(uri)
        vm.hasUnsavedAnnotations = true
        return true
    }

    private fun applyEdit(edit: AnnotationEdit) {
        applyContentTag(edit)
        val pdfView = binding.pdfView
        val applied = when (edit) {
            is AnnotationEdit.Add ->
                pdfView.addHighlight(
                    PDFView.HighlightRequest(
                        edit.fragments.map {
                            PDFView.HighlightFragment(it.page, it.rects, it.selectedText)
                        },
                    ),
                    edit.color,
                    edit.group,
                    edit.createdDate,
                )
            is AnnotationEdit.Recolor ->
                pdfView.setHighlightAnnotationColor(highlightReference(edit.page, edit.group), edit.color)
            is AnnotationEdit.SetNote ->
                pdfView.setHighlightAnnotationNote(highlightReference(edit.page, edit.group), edit.note, edit.modifiedDate)
            is AnnotationEdit.Delete ->
                pdfView.removeHighlightAnnotation(highlightReference(edit.page, edit.group))
            is AnnotationEdit.AddPdfComment ->
                pdfView.addPdfComment(
                    edit.page,
                    edit.rect,
                    edit.color,
                    edit.name,
                    edit.contents,
                    edit.author,
                    edit.createdDate,
                    edit.modifiedDate,
                )
            is AnnotationEdit.AddPdfMarkupComment ->
                pdfView.addPdfMarkupComment(
                    PDFView.HighlightRequest(
                        edit.fragments.map {
                            PDFView.HighlightFragment(it.page, it.rects, it.selectedText)
                        },
                    ),
                    edit.color,
                    edit.name,
                    edit.contents,
                    edit.author,
                    edit.createdDate,
                    edit.modifiedDate,
                    edit.subtype,
                )
            is AnnotationEdit.AddPdfReply ->
                pdfView.addPdfReply(
                    edit.page,
                    edit.rect,
                    edit.parentName,
                    edit.parentAnnotationIndex,
                    edit.parentSubtype,
                    edit.name,
                    edit.contents,
                    edit.author,
                    edit.createdDate,
                    edit.modifiedDate,
                )
            is AnnotationEdit.SetPdfCommentContents ->
                pdfView.setPdfCommentContents(
                    pdfCommentReference(
                        edit.page,
                        edit.annotationIndex,
                        edit.subtype,
                        edit.name,
                    ),
                    edit.contents,
                    edit.modifiedDate,
                )
            is AnnotationEdit.SetPdfCommentMetadata -> {
                val reference = pdfCommentReference(
                    edit.page,
                    edit.annotationIndex,
                    edit.subtype,
                    edit.name,
                )
                val colorApplied = edit.color?.let { pdfView.setPdfCommentColor(reference, it) } ?: true
                val rectApplied = edit.rect?.let { pdfView.setPdfCommentBounds(reference, it) } ?: true
                colorApplied && rectApplied
            }
            is AnnotationEdit.DeletePdfComment ->
                pdfView.removePdfComment(
                    pdfCommentReference(
                        edit.page,
                        edit.annotationIndex,
                        edit.subtype,
                        edit.name,
                    )
                )
            is AnnotationEdit.SetFieldText ->
                pdfView.setFormFieldText(edit.page, edit.fieldIndex, edit.text)
            is AnnotationEdit.SetFieldChecked ->
                pdfView.setFormFieldChecked(edit.page, edit.fieldIndex, edit.checked)
            is AnnotationEdit.AddSignature ->
                pdfView.addSignature(edit.page, edit.rect, edit.strokes.toTypedArray(), edit.color, edit.strokeWidth)
        }
        if (!applied) {
            Log.w(TAG, "applyEdit: skipped ${edit.javaClass.simpleName} on page ${edit.page}")
        }
    }

    private fun highlightReference(page: Int, group: String): PDFView.HighlightAnnotation {
        return PDFView.HighlightAnnotation(page, -1, group, null, "")
    }

    private fun pdfCommentReference(
        page: Int,
        annotationIndex: Int,
        subtype: Int,
        name: String,
    ): PDFView.PdfComment {
        return binding.pdfView.findPdfComment(page, name, annotationIndex, subtype)
            ?: PDFView.PdfComment(
                page,
                annotationIndex,
                subtype,
                name,
                null,
                null,
                "",
                "",
                "",
                "",
                "",
            )
    }

    companion object {
        private const val DEFAULT_COMMENT_COLOR = CommentCreationMetadata.DEFAULT_POPUP_COLOR
        private const val TAG = "AnnotationController"
        private const val COMMENT_RETRY_INITIAL_DELAY_MS = 1_000L
        private const val COMMENT_RETRY_MAX_DELAY_MS = 30_000L
        private const val COMMENT_REFRESH_DEBOUNCE_MS = 120L

        fun sourceKey(uri: Uri): String = SourceKey.of(uri)
    }
}

internal fun canPersistCommentEdit(
    identity: SourceLinkedCommentIdentity,
    sourceLinkedCommentEdit: Boolean,
): Boolean = !sourceLinkedCommentEdit || identity.allowsMutation
