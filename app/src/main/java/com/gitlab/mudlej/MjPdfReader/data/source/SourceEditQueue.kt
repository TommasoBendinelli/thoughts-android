// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.content.Context
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncTrigger
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import java.io.File
import java.io.FileReader
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class SourceSelection(
    val pdfFilename: String,
    val pdfSha256: String,
    val page: Int,
    val x: Float,
    val y: Float,
    val selectedText: String,
    val contextText: String = "",
) {
    fun cacheKey(): String = sha256(
        listOf("reference-synctex-v1", pdfFilename, pdfSha256, page, x, y, selectedText).joinToString("\u001f")
    )
}

data class SourceSnapshot(
    val resolutionId: String,
    val sourceFile: String,
    val sourceLine: Int,
    val sourceSha256: String,
    val contents: String,
    val bundleId: String? = null,
    val baseContents: String? = null,
    val bundlePdfFilename: String? = null,
    val bundlePdfSha256: String? = null,
    val bundlePdfFilenames: List<String> = emptyList(),
    // Exact authored bytes for the displayed PDF; sourceLine belongs to this revision.
    // Never substitute the current editable source when this evidence is unavailable.
    val navigationBaseContents: String? = null,
    val navigationContext: String = "",
)

data class SourceReceipt(
    val requestId: String,
    val action: String,
    val status: String,
    val message: String,
    val applied: Boolean,
    val buildSucceeded: Boolean?,
    val publishedPdfSha256: String?,
    val snapshot: SourceSnapshot?,
    val draftRevision: Int? = null,
    val bundleId: String? = null,
    val conflictPath: String? = null,
    val conflictCount: Int? = null,
    val expectedRevision: Long? = null,
    val currentSha256: String? = null,
    val incomingSha256: String? = null,
    val mergedObjectSha256: String? = null,
    val currentModifiedAt: String? = null,
    val incomingModifiedAt: String? = null,
)

enum class SourceMutationState {
    QUEUED,
    DELIVERING,
    CONFLICT,
    FAILED,
    RESOLUTION_QUEUED,
    APPLIED,
}

data class PendingSourceMutation(
    val requestId: String,
    val sourceFile: String,
    val pdfFilename: String,
    val state: SourceMutationState,
    val error: String = "",
    val rawError: String = "",
    val conflictId: String? = null,
    val conflictCount: Int? = null,
    val expectedRevision: Long? = null,
    val currentSha256: String? = null,
    val incomingSha256: String? = null,
    val mergedObjectSha256: String? = null,
    val currentModifiedAt: String? = null,
    val incomingModifiedAt: String? = null,
    val pdfFilenames: List<String> = emptyList(),
)

internal fun PendingSourceMutation.scopePdfFilenames(): List<String> =
    pdfFilenames.ifEmpty { listOf(pdfFilename) }
        .filter(::isSafeSourcePdfFilename)
        .distinct()
        .sorted()

internal fun PendingSourceMutation.matchesPdfTarget(targetPdfFilename: String?): Boolean =
    targetPdfFilename == null || targetPdfFilename in scopePdfFilenames()

data class SourceConflictBundle(
    val conflictId: String,
    val requestId: String,
    val sourceFile: String,
    val pdfFilename: String,
    val sourceLine: Int,
    val resolutionId: String,
    val conflictCount: Int,
    val expectedRevision: Long,
    val currentSha256: String,
    val incomingSha256: String,
    val mergedObjectSha256: String,
    val currentModifiedAt: String? = null,
    val incomingModifiedAt: String? = null,
    val currentContents: String,
    val incomingContents: String,
    val mergedContents: String,
)

internal data class SourceConflictDraftContext(
    val requestId: String,
    val sourceFile: String,
    val pdfFilename: String,
    val sourceLine: Int,
    val resolutionId: String,
)

data class ReverseSyncTexPoint(val x: Float, val y: Float)

internal fun reverseSyncTexPoint(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    pageHeight: Float,
): ReverseSyncTexPoint? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() ||
        !bottom.isFinite() || !pageHeight.isFinite() || pageHeight <= 0f
    ) {
        return null
    }
    val centerX = (left + right) / 2f
    val pdfCenterY = (top + bottom) / 2f
    return ReverseSyncTexPoint(
        centerX.coerceAtLeast(0f),
        (pageHeight - pdfCenterY).coerceIn(0f, pageHeight),
    )
}

/**
 * A source file whose working copy in the shared folder was changed by another app:
 * [addedLines] and [removedLines] against the synced version.
 */
data class HeldOutsideChange(
    val sourceFile: String,
    val contentsSha256: String,
    val addedLines: Int,
    val removedLines: Int,
)

/** How many lines [changed] has that [base] lacks, and the other way round (a changed line counts in both). */
internal fun changedLines(base: String, changed: String): Pair<Int, Int> {
    val remaining = base.lines().groupingBy { it }.eachCount().toMutableMap()
    var added = 0
    for (line in changed.lines()) {
        val left = remaining[line] ?: 0
        if (left > 0) remaining[line] = left - 1 else added += 1
    }
    return added to remaining.values.sum()
}

data class SourceOpenHandle(
    val requestId: String,
    val cacheKey: String,
    val cachedSnapshot: SourceSnapshot?,
)

class SourceEditQueue internal constructor(
    private val requestDirectory: File,
    private val receiptDirectory: File,
    private val cacheDirectory: File,
    private val requestScan: (String) -> Unit,
    private val directorySync: (File?) -> Unit,
    private val offlineStore: OfflineSourceBundleStore? = null,
    private val sourceSynced: (SourceSnapshot) -> Unit = {},
    private val requestPublished: () -> Unit = {},
) {
    internal constructor(
        requestDirectory: File,
        receiptDirectory: File,
        cacheDirectory: File,
        requestScan: (String) -> Unit,
        directorySync: (File?) -> Unit,
    ) : this(
        requestDirectory,
        receiptDirectory,
        cacheDirectory,
        requestScan,
        directorySync,
        null,
        {},
    )

    constructor(context: Context) : this(context, scheduleRequests = true)

    internal constructor(context: Context, scheduleRequests: Boolean) : this(
        defaultRequestDirectory(),
        defaultReceiptDirectory(),
        File(context.filesDir, "source-editor"),
        {},
        ::syncSourceDirectory,
        OfflineSourceBundleStore(context),
        { snapshot ->
            com.gitlab.mudlej.MjPdfReader.ui.source.ProposalReviewJournal(
                File(context.filesDir, "proposal-review"), snapshot.sourceFile,
            ).sourceSynced(snapshot.contents)
        },
        {
            PendingChangesSignal.notifyChanged()
            if (scheduleRequests) ThoughtsSyncTrigger.enqueue()
        },
    )

    fun enqueueOpen(selection: SourceSelection): SourceOpenHandle {
        require(selection.pdfFilename == File(selection.pdfFilename).name && selection.pdfFilename.endsWith(".pdf"))
        require(selection.pdfSha256.matches(SHA256_REGEX))
        require(selection.page > 0 && selection.x >= 0f && selection.y >= 0f)
        val cacheKey = selection.cacheKey()
        offlineStore?.resolve(selection)?.let { snapshot ->
            cacheSnapshot(cacheKey, snapshot)
            return SourceOpenHandle("", cacheKey, snapshot)
        }
        val requestId = UUID.randomUUID().toString()
        val payload = basePayload(requestId, ACTION_OPEN, selection.pdfFilename, selection.pdfSha256).apply {
            addProperty("page", selection.page)
            addProperty("x", selection.x)
            addProperty("y", selection.y)
            addProperty("selected_text", selection.selectedText.take(MAX_SELECTED_TEXT))
        }
        publishRequest(requestId, payload)
        return SourceOpenHandle(requestId, cacheKey, cachedSnapshot(cacheKey))
    }

    /** The synced source file and line a selection point maps to, if this phone can tell exactly. */
    internal fun sourceLine(selection: SourceSelection): Pair<String, Int>? =
        runCatching { offlineStore?.sourceLine(selection) }.getOrNull()

    fun openOfflineSource(sourceFile: String, sourceLine: Int): SourceOpenHandle? {
        val snapshot = offlineStore?.sourceSnapshot(sourceFile, sourceLine) ?: return null
        val cacheKey = sha256("source\u001f${snapshot.bundleId}\u001f$sourceFile")
        cacheSnapshot(cacheKey, snapshot)
        return SourceOpenHandle("", cacheKey, snapshot)
    }

    /** Opens the one canonical shared TeX style from the verified offline source bundle. */
    fun enqueueSave(
        snapshot: SourceSnapshot,
        pdfFilename: String,
        pdfSha256: String,
        contents: String,
    ): String = enqueueSave(snapshot, pdfFilename, pdfSha256, contents, notifySync = true)

    /**
     * Durably records an edit without authorizing any Fedora network transfer. [proposalDecisions]
     * are the decision claims of this save (agent-proposal spec section 10.4.2); they are written
     * with the draft and the request, before the decided text, and wait with the source's other
     * unanswered claims until Fedora answers an upload that carries them.
     */
    internal fun saveForNextSync(
        snapshot: SourceSnapshot,
        pdfFilename: String,
        pdfSha256: String,
        contents: String,
        proposalDecisions: List<ProposalDecisionClaim> = emptyList(),
    ): String = enqueueSave(snapshot, pdfFilename, pdfSha256, contents, notifySync = false, proposalDecisions)

    private fun enqueueSave(
        snapshot: SourceSnapshot,
        pdfFilename: String,
        pdfSha256: String,
        contents: String,
        notifySync: Boolean,
        proposalDecisions: List<ProposalDecisionClaim> = emptyList(),
    ): String {
        // An editor opened before this phone's default-folder migration still holds the
        // documents/ path; its save belongs to the renamed default/ file.
        if (DefaultFolder.localStateMigrated && DefaultFolder.containsLegacy(snapshot.sourceFile)) {
            return enqueueSave(
                snapshot.copy(sourceFile = DefaultFolder.rewriteLegacyDefaultFolder(snapshot.sourceFile)),
                pdfFilename,
                pdfSha256,
                contents,
                notifySync,
                proposalDecisions.map { it.copy(sourceFile = DefaultFolder.rewriteLegacyDefaultFolder(it.sourceFile)) },
            )
        }
        // Likewise an editor opened before this phone moved its connected folders (source-folder-names).
        val followed = SourceFolderNames.followLocal(snapshot.sourceFile)
        if (followed != snapshot.sourceFile) {
            return enqueueSave(
                snapshot.copy(sourceFile = followed),
                pdfFilename,
                pdfSha256,
                contents,
                notifySync,
                proposalDecisions.map { it.copy(sourceFile = SourceFolderNames.followLocal(it.sourceFile)) },
            )
        }
        require(snapshot.resolutionId.isResolutionId())
        require(snapshot.sourceSha256.matches(SHA256_REGEX))
        require(snapshot.sourceLine > 0)
        require(pdfFilename == File(pdfFilename).name && pdfFilename.endsWith(".pdf"))
        require(pdfSha256.matches(SHA256_REGEX))
        require(contents.toByteArray(StandardCharsets.UTF_8).size <= MAX_SOURCE_BYTES)
        if (snapshot.bundleId != null && snapshot.baseContents != null) {
            return enqueueMergeSave(snapshot, contents, notifySync, proposalDecisions)
        }
        val draft = parseObject(draftFile(snapshot.sourceFile))
        val claims = outstandingClaims(draft, snapshot.sourceFile, proposalDecisions)
        val previousRequestId = draft?.string("request_id")?.takeIf(String::isUuid)
        val reusableRequestId = draft
            ?.takeIf { it.int("schema_version") == SCHEMA_VERSION }
            ?.takeIf { it.string("source_file") == snapshot.sourceFile }
            ?.takeIf { it.string("base_sha256") == snapshot.sourceSha256 }
            ?.string("request_id")
            ?.takeIf(String::isUuid)
            ?.takeIf { requestId ->
                File(requestDirectory, "$requestId.json").isFile &&
                    receipt(requestId) == null &&
                    !deliveryStarted(requestId)
            }
        val requestId = reusableRequestId ?: UUID.randomUUID().toString()
        val predecessorRequestId = previousRequestId
            ?.takeIf { it != requestId }
            ?.takeIf { requestId ->
                deliveryStarted(requestId) || receipt(requestId)?.let {
                    it.confirmsAppliedMutation()
                } == true
            }
        val revision = (draft?.int("draft_revision") ?: 0) + 1
        val buildAfterSync = parseObject(File(requestDirectory, "$requestId.json"))
            ?.optionalBoolean("build_after_sync")
            ?: false
        val payload = basePayload(requestId, ACTION_SAVE, pdfFilename, pdfSha256).apply {
            addSourcePdfFilenames(snapshot.sourcePdfFilenames(pdfFilename))
            addProperty("build_after_sync", buildAfterSync)
            addProperty("resolution_id", snapshot.resolutionId)
            addProperty("source_file", snapshot.sourceFile)
            addProperty("source_line", snapshot.sourceLine)
            addProperty("base_sha256", snapshot.sourceSha256)
            addProperty("contents", contents)
            addProperty("draft_revision", revision)
            predecessorRequestId?.let { addProperty("predecessor_request_id", it) }
            putClaims(claims)
        }
        saveDraft(
            snapshot,
            pdfFilename,
            pdfSha256,
            contents,
            requestId,
            revision,
            buildAfterSync,
            predecessorRequestId,
            payload.string("created_at"),
            claims,
        )
        publishRequest(requestId, payload, notifySync)
        predecessorRequestId
            ?.let(::receipt)
            ?.takeIf { it.confirmsAppliedMutation() }
            ?.let(::rebaseSuccessor)
        return requestId
    }

    fun saveLocalDraft(
        snapshot: SourceSnapshot,
        pdfFilename: String,
        pdfSha256: String,
        contents: String,
    ) {
        require(snapshot.resolutionId.isResolutionId())
        require(snapshot.sourceLine > 0)
        require(pdfFilename == File(pdfFilename).name && pdfFilename.endsWith(".pdf"))
        require(pdfSha256.matches(SHA256_REGEX))
        require(contents.toByteArray(StandardCharsets.UTF_8).size <= MAX_SOURCE_BYTES)
        val payload = JsonObject().apply {
            addProperty("schema_version", if (snapshot.bundleId == null) SCHEMA_VERSION else MERGE_SCHEMA_VERSION)
            addProperty("saved_at", Instant.now().toString())
            addProperty("resolution_id", snapshot.resolutionId)
            addProperty("source_file", snapshot.sourceFile)
            addProperty("source_line", snapshot.sourceLine)
            addProperty("base_sha256", snapshot.sourceSha256)
            addProperty("pdf_filename", pdfFilename)
            addProperty("pdf_sha256", pdfSha256)
            addSourcePdfFilenames(snapshot.sourcePdfFilenames(pdfFilename))
            addProperty("contents", contents)
            snapshot.bundleId?.let { addProperty("bundle_id", it) }
            snapshot.baseContents?.let { addProperty("base_contents", it) }
        }
        atomicWrite(draftFile(snapshot.sourceFile), payload.toString())
    }

    fun localDraftContents(snapshot: SourceSnapshot): String? {
        val draft = parseObject(draftFile(snapshot.sourceFile)) ?: return null
        if (draft.string("source_file") != snapshot.sourceFile ||
            draft.string("base_sha256") != snapshot.sourceSha256
        ) {
            return null
        }
        return draft.stringOrNull("contents")
    }

    fun localSaveProblem(snapshot: SourceSnapshot): SourceReceipt? {
        val requestId = parseObject(draftFile(snapshot.sourceFile))
            ?.string("request_id")
            ?.takeIf(String::isUuid)
            ?: return null
        return receipt(requestId)?.takeIf {
            it.action == ACTION_SAVE && !it.confirmsAppliedMutation()
        }
    }

    /**
     * Returns the authoritative, durable source mutations which still need either Fedora or
     * the user. The projection is derived from the draft, request, delivery marker, and receipt
     * together so a process restart cannot turn a conflict into a clean local state.
     */
    fun pendingSourceMutations(sourceFile: String? = null): List<PendingSourceMutation> =
        File(cacheDirectory, "drafts")
            .listFiles { file ->
                file.isFile && file.extension.equals("json", ignoreCase = true)
            }
            .orEmpty()
            .mapNotNull { draftFile ->
                val draft = parseObject(draftFile) ?: return@mapNotNull null
                val path = draft.string("source_file").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                if (sourceFile != null && path != sourceFile) return@mapNotNull null
                val originalRequestId = draft.string("request_id").takeIf(String::isUuid)
                    ?: return@mapNotNull null
                val resolutionRequestId = draft.string("resolution_request_id")
                    .takeIf(String::isUuid)
                val activeRequestId = resolutionRequestId ?: originalRequestId
                val delivered = receipt(activeRequestId)
                val expectedAction = if (resolutionRequestId != null) {
                    ACTION_RESOLVE_CONFLICT
                } else {
                    ACTION_SAVE
                }
                val requestReady = publishedMutationRequest(activeRequestId, expectedAction)
                val state = when {
                    delivered?.confirmsAppliedMutation() == true ->
                        SourceMutationState.APPLIED
                    delivered?.status == STATUS_CONFLICT -> SourceMutationState.CONFLICT
                    delivered != null -> SourceMutationState.FAILED
                    !requestReady -> SourceMutationState.FAILED
                    resolutionRequestId != null -> SourceMutationState.RESOLUTION_QUEUED
                    deliveryStarted(activeRequestId) -> SourceMutationState.DELIVERING
                    else -> SourceMutationState.QUEUED
                }
                if (state == SourceMutationState.APPLIED) return@mapNotNull null
                val conflictReceipt = delivered?.takeIf { it.status == STATUS_CONFLICT }
                    ?: receipt(originalRequestId)?.takeIf { it.status == STATUS_CONFLICT }
                val rawError = delivered?.message ?: if (!requestReady) {
                    "The durable TeX request is missing or invalid and could not be restored."
                } else {
                    ""
                }
                PendingSourceMutation(
                    requestId = activeRequestId,
                    sourceFile = path,
                    pdfFilename = draft.string("pdf_filename"),
                    state = state,
                    error = sourceMutationDisplayError(state, rawError, path),
                    rawError = rawError,
                    conflictId = conflictReceipt?.conflictPath,
                    conflictCount = conflictReceipt?.conflictCount,
                    expectedRevision = conflictReceipt?.expectedRevision,
                    currentSha256 = conflictReceipt?.currentSha256,
                    incomingSha256 = conflictReceipt?.incomingSha256,
                    mergedObjectSha256 = conflictReceipt?.mergedObjectSha256,
                    currentModifiedAt = conflictReceipt?.currentModifiedAt,
                    incomingModifiedAt = conflictReceipt?.incomingModifiedAt,
                    pdfFilenames = draft.sourcePdfFilenames(),
                )
            }
            .sortedWith(compareBy(PendingSourceMutation::sourceFile, PendingSourceMutation::requestId))

    fun blockingSourceMutations(targetPdfFilename: String? = null): List<PendingSourceMutation> =
        pendingSourceMutations().filter { mutation ->
            mutation.matchesPdfTarget(targetPdfFilename) &&
                mutation.state in setOf(SourceMutationState.CONFLICT, SourceMutationState.FAILED)
        }

    /** Workspace style changes always require a global conditional render check and artifact plan. */
    internal fun hasPendingWorkspaceStyleMutation(): Boolean = pendingSourceMutations().any { mutation ->
        isWorkspaceStyleResourcePath(mutation.sourceFile)
    }

    /**
     * Remains true after Fedora accepts a style edit, until one global source-artifact closure is
     * activated locally. This prevents a new targeted operation from mixing a newly built style
     * with cached artifacts that still describe the previous canonical style.
     */
    internal fun requiresGlobalWorkspaceStyleRefresh(): Boolean =
        workspaceStyleRefreshMarker().isFile || hasPendingWorkspaceStyleMutation()

    internal fun clearGlobalWorkspaceStyleRefresh() {
        workspaceStyleRefreshMarker().delete()
    }

    internal fun conflictDraftContext(conflictId: String): SourceConflictDraftContext? {
        val pending = pendingSourceMutations().singleOrNull { it.conflictId == conflictId }
            ?: return null
        val draft = parseObject(draftFile(pending.sourceFile)) ?: return null
        return SourceConflictDraftContext(
            requestId = pending.requestId,
            sourceFile = pending.sourceFile,
            pdfFilename = pending.pdfFilename.takeIf {
                it == File(it).name && it.endsWith(".pdf")
            } ?: return null,
            sourceLine = draft.int("source_line").takeIf { it > 0 } ?: return null,
            resolutionId = draft.string("resolution_id").takeIf(String::isResolutionId)
                ?: return null,
        )
    }

    /**
     * Explicit user Sync authorizes retrying terminal deliveries with the same operation ID.
     * The old receipt is archived byte-for-byte first, preserving both response-loss
     * idempotency and the exact earlier failure for diagnosis.
     */
    fun prepareFailedMutationsForExplicitSync(targetPdfFilename: String? = null): Int {
        var prepared = 0
        pendingSourceMutations()
            .filter { mutation ->
                mutation.state == SourceMutationState.FAILED &&
                    mutation.matchesPdfTarget(targetPdfFilename)
            }
            .forEach { mutation ->
                val draftFile = draftFile(mutation.sourceFile)
                val draft = parseObject(draftFile) ?: return@forEach
                val requestId = mutation.requestId
                val receiptFile = File(receiptDirectory, "$requestId.json")
                val expectedAction = if (draft.string("resolution_request_id") == requestId) {
                    ACTION_RESOLVE_CONFLICT
                } else {
                    ACTION_SAVE
                }
                if (!publishedMutationRequest(requestId, expectedAction)) {
                    val restored = mutationRequestFromDraft(draft, requestId)
                        ?: return@forEach
                    restored.addProperty("recovered_from_failure", true)
                    publishRequest(requestId, restored, notifySync = false)
                }
                if (!archiveReceipt(requestId, receiptFile)) return@forEach
                clearDeliveryStarted(requestId)
                prepared += 1
            }
        if (prepared > 0) PendingChangesSignal.notifyChanged()
        return prepared
    }

    /** Saves a conflict resolution durably and offline. Network delivery occurs on explicit Sync. */
    fun saveConflictResolution(bundle: SourceConflictBundle, contents: String): String {
        require(contents.toByteArray(StandardCharsets.UTF_8).size <= MAX_SOURCE_BYTES)
        require(!containsConflictMarkers(contents)) {
            "Resolve every TeX conflict marker before saving."
        }
        val draftFile = draftFile(bundle.sourceFile)
        val draft = parseObject(draftFile)
            ?: error("The local TeX draft for this conflict no longer exists.")
        val pending = pendingSourceMutations(bundle.sourceFile).singleOrNull()
            ?: error("The TeX conflict is no longer pending on this phone.")
        require(pending.conflictId == bundle.conflictId) {
            "The TeX conflict changed before this resolution was saved."
        }
        require(pending.currentSha256 == null || pending.currentSha256 == bundle.currentSha256) {
            "The server's source identity changed before this resolution was saved."
        }
        val previousResolutionId = draft.string("resolution_request_id").takeIf(String::isUuid)
        val reusableId = previousResolutionId
            ?.takeIf { receipt(it) == null && !deliveryStarted(it) }
        val requestId = reusableId ?: UUID.randomUUID().toString()
        val revision = (draft.int("draft_revision").takeIf { it > 0 } ?: 1) + 1
        val createdAt = Instant.now().toString()
        val contentsSha256 = sha256(contents)
        val payload = JsonObject().apply {
            addProperty("schema_version", CONFLICT_SCHEMA_VERSION)
            addProperty("request_id", requestId)
            addProperty("created_at", createdAt)
            addProperty("action", ACTION_RESOLVE_CONFLICT)
            addProperty("conflict_id", bundle.conflictId)
            addProperty("conflict_count", bundle.conflictCount)
            addProperty("expected_revision", bundle.expectedRevision)
            addProperty("expected_sha256", bundle.currentSha256)
            addProperty("current_sha256", bundle.currentSha256)
            addProperty("incoming_sha256", bundle.incomingSha256)
            addProperty("merged_object_sha256", bundle.mergedObjectSha256)
            bundle.currentModifiedAt?.let { addProperty("current_modified_at", it) }
            bundle.incomingModifiedAt?.let { addProperty("incoming_modified_at", it) }
            addProperty("source_file", bundle.sourceFile)
            addProperty("source_line", bundle.sourceLine)
            addProperty("resolution_id", bundle.resolutionId)
            addProperty("pdf_filename", bundle.pdfFilename)
            addSourcePdfFilenames(draft.sourcePdfFilenames())
            addProperty("contents", contents)
            addProperty("content_sha256", contentsSha256)
            addProperty("draft_revision", revision)
            addProperty("conflict_request_id", pending.requestId)
            // Claims held back by the conflict travel with its resolution (section 10.8).
            putClaims(outstandingClaims(draft, bundle.sourceFile, emptyList()))
        }
        draft.apply {
            addProperty("resolution_request_id", requestId)
            addProperty("resolution_created_at", createdAt)
            addProperty("resolution_saved_at", createdAt)
            addProperty("draft_revision", revision)
            addProperty("contents", contents)
            addProperty("content_sha256", contentsSha256)
            addProperty("conflict_id", bundle.conflictId)
            addProperty("conflict_count", bundle.conflictCount)
            addProperty("expected_revision", bundle.expectedRevision)
            addProperty("expected_sha256", bundle.currentSha256)
            addProperty("current_sha256", bundle.currentSha256)
            addProperty("incoming_sha256", bundle.incomingSha256)
            addProperty("merged_object_sha256", bundle.mergedObjectSha256)
            bundle.currentModifiedAt?.let { addProperty("current_modified_at", it) }
            bundle.incomingModifiedAt?.let { addProperty("incoming_modified_at", it) }
            addProperty("conflict_request_id", pending.requestId)
        }
        atomicWrite(draftFile, draft.toString())
        offlineStore?.writeWorkingContents(bundle.sourceFile, contents)
        offlineStore?.repairQueuedIfWorkingMatches(
            bundle.sourceFile,
            requestId,
            revision,
            contentsSha256,
        )
        publishRequest(requestId, payload, notifySync = false)
        PendingChangesSignal.notifyChanged()
        return requestId
    }

    fun cacheConflictBundle(bundle: SourceConflictBundle) {
        require(bundle.currentContents.toByteArray(StandardCharsets.UTF_8).size <= MAX_SOURCE_BYTES)
        require(bundle.incomingContents.toByteArray(StandardCharsets.UTF_8).size <= MAX_SOURCE_BYTES)
        require(bundle.mergedContents.toByteArray(StandardCharsets.UTF_8).size <= MAX_SOURCE_BYTES)
        require(bundle.currentSha256 == sha256(bundle.currentContents))
        require(bundle.incomingSha256 == sha256(bundle.incomingContents))
        require(bundle.mergedObjectSha256 == sha256(bundle.mergedContents))
        val payload = JsonObject().apply {
            addProperty("schema_version", CONFLICT_SCHEMA_VERSION)
            addProperty("conflict_id", bundle.conflictId)
            addProperty("request_id", bundle.requestId)
            addProperty("source_file", bundle.sourceFile)
            addProperty("pdf_filename", bundle.pdfFilename)
            addProperty("source_line", bundle.sourceLine)
            addProperty("resolution_id", bundle.resolutionId)
            addProperty("conflict_count", bundle.conflictCount)
            addProperty("expected_revision", bundle.expectedRevision)
            addProperty("current_sha256", bundle.currentSha256)
            addProperty("incoming_sha256", bundle.incomingSha256)
            addProperty("merged_object_sha256", bundle.mergedObjectSha256)
            bundle.currentModifiedAt?.let { addProperty("current_modified_at", it) }
            bundle.incomingModifiedAt?.let { addProperty("incoming_modified_at", it) }
            addProperty("current_contents", bundle.currentContents)
            addProperty("incoming_contents", bundle.incomingContents)
            addProperty("merged_contents", bundle.mergedContents)
        }
        atomicWrite(conflictBundleFile(bundle.conflictId), payload.toString())
    }

    fun cachedConflictBundle(conflictId: String): SourceConflictBundle? {
        val value = parseObject(conflictBundleFile(conflictId), MAX_CONFLICT_BUNDLE_BYTES) ?: return null
        if (value.string("conflict_id") != conflictId) return null
        val currentContents = value.stringOrNull("current_contents") ?: return null
        val incomingContents = value.stringOrNull("incoming_contents") ?: return null
        val mergedContents = value.stringOrNull("merged_contents") ?: return null
        if (
            currentContents.toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_BYTES ||
            incomingContents.toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_BYTES ||
            mergedContents.toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_BYTES
        ) {
            return null
        }
        val currentSha256 = value.string("current_sha256")
        val incomingSha256 = value.string("incoming_sha256")
        val mergedSha256 = value.string("merged_object_sha256")
        if (
            currentSha256 != sha256(currentContents) ||
            incomingSha256 != sha256(incomingContents) ||
            mergedSha256 != sha256(mergedContents)
        ) {
            return null
        }
        return SourceConflictBundle(
            conflictId = conflictId,
            requestId = value.string("request_id").takeIf(String::isUuid) ?: return null,
            sourceFile = value.string("source_file").takeIf(String::isNotBlank) ?: return null,
            pdfFilename = value.string("pdf_filename").takeIf {
                it == File(it).name && it.endsWith(".pdf")
            } ?: return null,
            sourceLine = value.int("source_line").takeIf { it > 0 } ?: return null,
            resolutionId = value.string("resolution_id").takeIf(String::isResolutionId) ?: return null,
            conflictCount = value.int("conflict_count").takeIf { it > 0 } ?: return null,
            expectedRevision = value.long("expected_revision").takeIf { it >= 0 } ?: return null,
            currentSha256 = currentSha256,
            incomingSha256 = incomingSha256,
            mergedObjectSha256 = mergedSha256,
            currentModifiedAt = value.string("current_modified_at").takeIf(String::isNotBlank),
            incomingModifiedAt = value.string("incoming_modified_at").takeIf(String::isNotBlank),
            currentContents = currentContents,
            incomingContents = incomingContents,
            mergedContents = mergedContents,
        )
    }

    /** Called only after Fedora has guarded and durably discarded the matching conflict. */
    fun discardConfirmedConflict(conflictId: String, expectedSha256: String): Boolean {
        require(expectedSha256.matches(SHA256_REGEX))
        val pending = pendingSourceMutations().singleOrNull {
            it.conflictId == conflictId &&
                (it.currentSha256 == null || it.currentSha256 == expectedSha256)
        } ?: return false
        val draft = parseObject(draftFile(pending.sourceFile)) ?: return false
        val requestIds = buildSet {
            draft.string("request_id").takeIf(String::isUuid)?.let(::add)
            draft.string("resolution_request_id").takeIf(String::isUuid)?.let(::add)
        }
        if (offlineStore?.discardWorkingChanges(pending.sourceFile) == false) return false
        requestIds.forEach { requestId ->
            val requestFile = File(requestDirectory, "$requestId.json")
            if (requestFile.exists() && !requestFile.delete()) return false
            clearDeliveryStarted(requestId)
            requestScan("docs-source-requests/${requestFile.name}")
        }
        if (!draftFile(pending.sourceFile).delete()) return false
        conflictBundleFile(conflictId).delete()
        directorySync(requestDirectory)
        PendingChangesSignal.notifyChanged()
        return true
    }

    private fun enqueueMergeSave(
        snapshot: SourceSnapshot,
        contents: String,
        notifySync: Boolean,
        proposalDecisions: List<ProposalDecisionClaim> = emptyList(),
    ): String {
        val bundleId = requireNotNull(snapshot.bundleId).also { require(it.matches(SHA256_REGEX)) }
        val baseContents = requireNotNull(snapshot.baseContents)
        require(sha256(baseContents) == snapshot.sourceSha256)
        val bundlePdfFilename = requireNotNull(snapshot.bundlePdfFilename)
        val bundlePdfSha256 = requireNotNull(snapshot.bundlePdfSha256)
        val bundlePdfFilenames = snapshot.sourcePdfFilenames(bundlePdfFilename)
        require(bundlePdfFilename == File(bundlePdfFilename).name && bundlePdfFilename.endsWith(".pdf"))
        require(bundlePdfSha256.matches(SHA256_REGEX))
        val draftFile = draftFile(snapshot.sourceFile)
        val previous = parseObject(draftFile)
        val claims = outstandingClaims(previous, snapshot.sourceFile, proposalDecisions)
        val reusable = previous
            ?.takeIf { it.int("schema_version") == MERGE_SCHEMA_VERSION }
            ?.takeIf { it.string("bundle_id") == bundleId }
            ?.takeIf { it.string("source_file") == snapshot.sourceFile }
        val queuedState = offlineStore?.queueState(snapshot.sourceFile)
        val previousRequestId = reusable?.string("request_id")?.takeIf(String::isUuid)
            ?: queuedState?.requestId
        val requestId = reusable?.string("request_id")
            ?.takeIf(String::isUuid)
            ?.takeIf {
                File(requestDirectory, "$it.json").isFile &&
                    receipt(it) == null &&
                    !deliveryStarted(it)
            }
            ?: queuedState?.requestId?.takeIf {
                File(requestDirectory, "$it.json").isFile &&
                    receipt(it) == null &&
                    !deliveryStarted(it)
            }
            ?: UUID.randomUUID().toString()
        val predecessorRequestId = previousRequestId
            ?.takeIf { it != requestId }
            ?.takeIf { requestId ->
                deliveryStarted(requestId) || receipt(requestId)?.let {
                    it.confirmsAppliedMutation()
                } == true
            }
        val buildAfterSync = parseObject(File(requestDirectory, "$requestId.json"))
            ?.optionalBoolean("build_after_sync")
            ?: false
        val revision = maxOf(
            reusable?.int("draft_revision") ?: 0,
            queuedState?.draftRevision ?: 0,
        ) + 1
        val payload = JsonObject().apply {
            addProperty("schema_version", MERGE_SCHEMA_VERSION)
            addProperty("request_id", requestId)
            addProperty("created_at", Instant.now().toString())
            addProperty("action", ACTION_SAVE)
            addProperty("build_after_sync", buildAfterSync)
            addProperty("pdf_filename", bundlePdfFilename)
            addProperty("pdf_sha256", bundlePdfSha256)
            addSourcePdfFilenames(bundlePdfFilenames)
            addProperty("resolution_id", snapshot.resolutionId)
            addProperty("bundle_id", bundleId)
            addProperty("source_file", snapshot.sourceFile)
            addProperty("source_line", snapshot.sourceLine)
            addProperty("base_sha256", snapshot.sourceSha256)
            addProperty("base_contents", baseContents)
            addProperty("contents", contents)
            addProperty("draft_revision", revision)
            predecessorRequestId?.let { addProperty("predecessor_request_id", it) }
            putClaims(claims)
        }
        if (isWorkspaceStyleResourcePath(snapshot.sourceFile)) {
            markGlobalWorkspaceStyleRefresh(snapshot.sourceFile, requestId)
        }
        atomicWrite(draftFile, payload.toString())
        offlineStore?.writeWorkingContents(snapshot.sourceFile, contents)
        offlineStore?.recordQueued(
            snapshot.sourceFile,
            requestId,
            revision,
            sha256(contents),
        )
        publishRequest(requestId, payload, notifySync)
        predecessorRequestId
            ?.let(::receipt)
            ?.takeIf { it.confirmsAppliedMutation() }
            ?.let(::rebaseSuccessor)
        return requestId
    }

    /**
     * Callers on several threads (Home refresh, document status, Sync preflight) used to scan the
     * working files concurrently (five 0.3-0.7 s scans at once, 2026-10-10), racing on the same
     * working files. Runs for one request directory are serialized, and a caller that
     * arrives during a run shares the next run, which starts after its call and so sees its files.
     */
    fun captureExternalEdits(): Int {
        offlineStore ?: return 0
        return SingleFlight.forKey(requestDirectory.absolutePath).run(::captureExternalEditsNow)
    }

    private fun captureExternalEditsNow(): Int {
        val store = offlineStore ?: return 0
        fun <T> timed(stage: String, block: () -> T): T {
            val started = System.nanoTime()
            return block().also {
                val millis = (System.nanoTime() - started) / 1_000_000
                if (millis >= 20) android.util.Log.i("ThoughtsSyncTiming", "capture_external_edits.$stage $millis ms")
            }
        }
        timed("recover_requests") { recoverDeliveredRequests() }
        timed("import_staged") { store.importStagedLatest() }
        timed("auto_merge") { store.autoMergePendingSources() }
        var queued = 0
        for (edit in timed("scan_working_files") { store.externalEdits() }) {
            // A change another app made in the shared folder is not queued until the user confirms it.
            if (edit.outside) continue
            enqueueMergeSave(edit.snapshot, edit.contents, notifySync = false)
            queued += 1
        }
        if (queued > 0) PendingChangesSignal.notifyChanged()
        return queued
    }

    /**
     * Source files in the shared folder with a change that was not made in Thoughts. Sync leaves
     * them alone until the user uploads ([uploadOutsideChange]) or discards ([discardOutsideChange])
     * the change.
     */
    fun heldOutsideChanges(): List<HeldOutsideChange> = offlineStore?.heldOutsideChanges().orEmpty().map { edit ->
        val (added, removed) = changedLines(edit.snapshot.baseContents.orEmpty(), edit.contents)
        HeldOutsideChange(edit.snapshot.sourceFile, sha256(edit.contents), added, removed)
    }

    /** Queues the outside change the user was shown for the next Sync. False when the file changed meanwhile. */
    fun uploadOutsideChange(change: HeldOutsideChange): Boolean {
        val store = offlineStore ?: return false
        if (!store.confirmOutsideChange(change.sourceFile, change.contentsSha256)) return false
        captureExternalEdits()
        return true
    }

    /** Puts back what Thoughts had instead of the outside change the user was shown. False when the file changed meanwhile. */
    fun discardOutsideChange(change: HeldOutsideChange): Boolean =
        offlineStore?.discardOutsideChange(change.sourceFile, change.contentsSha256) == true

    fun pendingRequestId(snapshot: SourceSnapshot): String? =
        snapshot.bundleId?.let { offlineStore?.queueState(snapshot.sourceFile)?.requestId }

    /** True when a local source mutation still has to reach Fedora. */
    fun hasPendingSave(): Boolean = requestDirectory.listFiles { file ->
        file.isFile && file.extension.equals("json", ignoreCase = true) &&
            file.length() in 1..MAX_REQUEST_BYTES
    }.orEmpty().any { requestFile ->
        runCatching {
            FileReader(requestFile).use { input ->
                JsonReader(input).use { json ->
                    json.beginObject()
                    while (json.hasNext()) {
                        if (json.nextName() == "action") {
                            return@runCatching json.nextString() in
                                setOf(ACTION_SAVE, ACTION_RESOLVE_CONFLICT)
                        }
                        json.skipValue()
                    }
                    false
                }
            }
        }.getOrDefault(false)
    }

    /** PDFs with a durable source mutation which still needs Fedora or user attention. */
    fun pendingSavePdfFilenames(): Set<String> = pendingSourceMutations()
        .flatMap(PendingSourceMutation::scopePdfFilenames)
        .toSet()

    /** Preserves legacy build-after-sync authorization for already-issued durable requests. */
    fun authorizeBuildAfterSync(pdfFilename: String? = null): Int {
        require(pdfFilename == null ||
            (pdfFilename == File(pdfFilename).name && pdfFilename.endsWith(".pdf")))
        var changed = 0
        requestDirectory.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true) &&
                file.length() in 1..MAX_REQUEST_BYTES
        }.orEmpty().forEach { requestFile ->
            val requestId = requestFile.nameWithoutExtension.takeIf(String::isUuid)
                ?: return@forEach
            if (receipt(requestId) != null) return@forEach
            val payload = parseObject(requestFile) ?: return@forEach
            if (payload.string("action") != ACTION_SAVE ||
                (pdfFilename != null && pdfFilename !in payload.sourcePdfFilenames()) ||
                payload.optionalBoolean("build_after_sync") == true
            ) {
                return@forEach
            }
            payload.addProperty("build_after_sync", true)
            atomicWrite(requestFile, payload.toString())
            directorySync(requestDirectory)
            requestScan("docs-source-requests/${requestFile.name}")
            changed += 1
        }
        return changed
    }

    /** True when Fedora has not yet written a terminal receipt for a durable request. */
    fun hasUndeliveredRequests(): Boolean = requestDirectory.listFiles { file ->
        file.isFile && file.extension.equals("json", ignoreCase = true) &&
            file.length() in 1..MAX_REQUEST_BYTES
    }.orEmpty().any { requestFile ->
        val requestId = requestFile.nameWithoutExtension.takeIf(String::isUuid)
            ?: return@any false
        receipt(requestId) == null
    }

    fun undeliveredRequestIds(): Set<String> = requestDirectory.listFiles { file ->
        file.isFile && file.extension.equals("json", ignoreCase = true) &&
            file.length() in 1..MAX_REQUEST_BYTES
    }.orEmpty().mapNotNullTo(linkedSetOf()) { requestFile ->
        val requestId = requestFile.nameWithoutExtension.takeIf(String::isUuid)
            ?: return@mapNotNullTo null
        requestId.takeIf { receipt(requestId) == null }
    }

    /** Durable source-open requests that can be recovered without running the full sync. */
    fun pendingOpenRequestIds(): List<String> = requestDirectory.listFiles { file ->
        file.isFile && file.extension.equals("json", ignoreCase = true) &&
            file.length() in 1..MAX_REQUEST_BYTES
    }.orEmpty()
        .sortedBy(File::lastModified)
        .mapNotNull { requestFile ->
            val requestId = requestFile.nameWithoutExtension.takeIf(String::isUuid)
                ?: return@mapNotNull null
            if (receipt(requestId) != null) return@mapNotNull null
            val payload = parseObject(requestFile) ?: return@mapNotNull null
            requestId.takeIf {
                payload.string("request_id") == requestId && payload.string("action") == ACTION_OPEN
            }
        }

    fun applyAppliedReceipt(snapshot: SourceSnapshot, deliveredRevision: Int?) {
        offlineStore?.applySuccessfulReceipt(snapshot, deliveredRevision)
        sourceSynced(snapshot)
    }

    fun receipt(requestId: String): SourceReceipt? {
        if (!requestId.isUuid()) return null
        val file = File(receiptDirectory, "$requestId.json")
        if (!file.isFile) return null
        return runCatching {
            val value = JsonParser.parseString(file.readText()).asJsonObject
            val schemaVersion = value.int("schema_version")
            if (schemaVersion !in setOf(SCHEMA_VERSION, MERGE_SCHEMA_VERSION, CONFLICT_SCHEMA_VERSION) ||
                value.string("request_id") != requestId
            ) {
                return@runCatching null
            }
            val status = value.string("status")
            if (status !in setOf(STATUS_SUCCESS, STATUS_FAILURE, STATUS_STALE, STATUS_CONFLICT)) {
                return@runCatching null
            }
            val parsedSnapshot = value.toSnapshot()
            val enrichedSnapshot = if (schemaVersion == MERGE_SCHEMA_VERSION && parsedSnapshot != null) {
                enrichFromDraft(parsedSnapshot)
            } else {
                parsedSnapshot
            }
            SourceReceipt(
                requestId = requestId,
                action = value.string("action"),
                status = status,
                message = value.string("message"),
                applied = value.boolean("applied"),
                buildSucceeded = value.optionalBoolean("build_succeeded"),
                publishedPdfSha256 = value.string("published_pdf_sha256")
                    .takeIf { it.matches(SHA256_REGEX) },
                snapshot = enrichedSnapshot,
                draftRevision = value.int("draft_revision").takeIf { it > 0 },
                bundleId = value.string("bundle_id").takeIf { it.matches(SHA256_REGEX) },
                conflictPath = value.string("conflict_path").takeIf(String::isNotBlank),
                conflictCount = value.int("conflict_count").takeIf { it > 0 },
                expectedRevision = value.long("expected_revision").takeIf { it >= 0 },
                currentSha256 = value.string("current_sha256").takeIf { it.matches(SHA256_REGEX) },
                incomingSha256 = value.string("incoming_sha256").takeIf { it.matches(SHA256_REGEX) },
                mergedObjectSha256 = value.string("merged_object_sha256")
                    .takeIf { it.matches(SHA256_REGEX) },
                currentModifiedAt = value.string("current_modified_at").takeIf(String::isNotBlank),
                incomingModifiedAt = value.string("incoming_modified_at").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    fun acknowledge(requestId: String) {
        if (!requestId.isUuid()) return
        val requestFile = File(requestDirectory, "$requestId.json")
        if (!requestFile.exists() || requestFile.delete()) {
            directorySync(requestDirectory)
            requestScan("docs-source-requests/${requestFile.name}")
            PendingChangesSignal.notifyChanged()
        }
        clearDeliveryStarted(requestId)
    }

    fun markDeliveryStarted(requestId: String) {
        if (!requestId.isUuid()) return
        atomicWrite(
            deliveryFile(requestId),
            JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("started_at", Instant.now().toString())
            }.toString(),
        )
    }

    /** Recreates crash-interrupted save requests from their authoritative durable drafts. */
    internal fun recoverStrandedMutationRequests(): Int {
        var recovered = 0
        File(cacheDirectory, "drafts")
            .listFiles { file ->
                file.isFile && file.extension.equals("json", ignoreCase = true)
            }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { draftFile ->
                val draft = parseObject(draftFile) ?: return@forEach
                val originalRequestId = draft.string("request_id").takeIf(String::isUuid)
                    ?: return@forEach
                val resolutionRequestId = draft.string("resolution_request_id")
                    .takeIf(String::isUuid)
                val requestId = resolutionRequestId ?: originalRequestId
                if (receipt(requestId) != null) return@forEach
                val action = if (resolutionRequestId != null) {
                    ACTION_RESOLVE_CONFLICT
                } else {
                    ACTION_SAVE
                }
                if (!publishedMutationRequest(requestId, action)) {
                    val restored = mutationRequestFromDraft(draft, requestId)
                        ?: return@forEach
                    restored.addProperty("recovered_after_restart", true)
                    publishRequest(requestId, restored, notifySync = false)
                    recovered += 1
                }
                val contents = draft.stringOrNull("contents") ?: return@forEach
                val revision = draft.int("draft_revision").takeIf { it > 0 } ?: return@forEach
                offlineStore?.repairQueuedIfWorkingMatches(
                    draft.string("source_file"),
                    requestId,
                    revision,
                    sha256(contents),
                )
            }
        if (recovered > 0) PendingChangesSignal.notifyChanged()
        return recovered
    }

    fun recoverDeliveredRequests(): Int {
        recoverStrandedMutationRequests()
        val requests = requestDirectory.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty()
        var recovered = 0
        for (requestFile in requests) {
            val requestId = requestFile.nameWithoutExtension.takeIf(String::isUuid) ?: continue
            val delivered = receipt(requestId) ?: continue
            val payload = parseObject(requestFile) ?: continue
            if (delivered.action == ACTION_OPEN && delivered.snapshot != null) {
                val selection = payload.toSourceSelection() ?: continue
                cacheSnapshot(selection.cacheKey(), delivered.snapshot)
            } else if (
                delivered.action in setOf(ACTION_SAVE, ACTION_RESOLVE_CONFLICT) &&
                delivered.snapshot != null
            ) {
                if (delivered.confirmsAppliedMutation()) {
                    applyAppliedReceipt(delivered.snapshot, delivered.draftRevision)
                    rebaseSuccessor(delivered)
                    if (delivered.action == ACTION_RESOLVE_CONFLICT) {
                        acknowledgeResolvedConflictLineage(delivered.snapshot, requestId)
                    }
                    clearDraft(delivered.snapshot, delivered.draftRevision)
                }
            }
            if (
                delivered.action == ACTION_OPEN ||
                delivered.confirmsAppliedMutation()
            ) {
                acknowledge(requestId)
                recovered += 1
            }
        }
        return recovered
    }

    fun cacheSnapshot(cacheKey: String, snapshot: SourceSnapshot) {
        if (!cacheKey.matches(CACHE_KEY_REGEX)) return
        val payload = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("resolution_id", snapshot.resolutionId)
            addProperty("source_file", snapshot.sourceFile)
            addProperty("source_line", snapshot.sourceLine)
            addProperty("source_sha256", snapshot.sourceSha256)
            addProperty("contents", snapshot.contents)
            addProperty("navigation_context", snapshot.navigationContext)
            snapshot.navigationBaseContents?.let {
                val digest = sha256(it)
                // One immutable copy per build, shared by all selections in it.
                // Keep source-open JSON below its existing size limit.
                atomicWrite(File(cacheDirectory, "navigation-bases/$digest.tex"), it)
                addProperty("navigation_base_object", digest)
            }
            snapshot.bundleId?.let { addProperty("bundle_id", it) }
            snapshot.baseContents?.let { addProperty("base_contents", it) }
            snapshot.bundlePdfFilename?.let { addProperty("bundle_pdf_filename", it) }
            snapshot.bundlePdfSha256?.let { addProperty("bundle_pdf_sha256", it) }
            addSourcePdfFilenames(
                snapshot.bundlePdfFilenames,
                key = "bundle_pdf_filenames",
            )
        }
        atomicWrite(File(cacheDirectory, "snapshots/$cacheKey.json"), payload.toString())
    }

    fun cachedSnapshot(cacheKey: String): SourceSnapshot? {
        if (!cacheKey.matches(CACHE_KEY_REGEX)) return null
        val payload = parseObject(File(cacheDirectory, "snapshots/$cacheKey.json")) ?: return null
        val snapshot = payload.toSnapshot() ?: return null
        val digest = payload.string("navigation_base_object").takeIf { it.matches(SHA256_REGEX) }
        val base = digest?.let {
            val file = File(cacheDirectory, "navigation-bases/$it.tex")
            if (!file.isFile || file.length() > MAX_SOURCE_BYTES) null
            else runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()?.takeIf { sha256(it) == digest }
        }
        return if (digest == null) snapshot else snapshot.copy(navigationBaseContents = base, navigationContext = payload.string("navigation_context"))
    }

    fun clearDraft(snapshot: SourceSnapshot, deliveredRevision: Int? = null) {
        val draft = draftFile(snapshot.sourceFile)
        if (deliveredRevision != null) {
            val currentRevision = parseObject(draft)?.int("draft_revision") ?: return
            if (currentRevision > deliveredRevision) return
        }
        draft.delete()
    }

    /** Removes an edit only while Fedora has not accepted it, restoring the local base. */
    fun discardLocalChanges(snapshot: SourceSnapshot): Boolean {
        if (pendingSourceMutations(snapshot.sourceFile).any { it.conflictId != null }) {
            return false
        }
        var removed = withdrawQueuedSaves(snapshot.sourceFile) ?: return false
        if (snapshot.bundleId != null && offlineStore?.discardWorkingChanges(snapshot.sourceFile) == false) {
            removed = false
        }
        if (
            removed && isWorkspaceStyleResourcePath(snapshot.sourceFile) &&
            !hasPendingWorkspaceStyleMutation()
        ) {
            clearGlobalWorkspaceStyleRefresh()
        }
        return removed
    }

    /**
     * Deletes the queued requests and draft of [sourceFile]. Null, deleting nothing, once Fedora
     * applied one of them; otherwise whether everything was deleted.
     */
    private fun withdrawQueuedSaves(sourceFile: String): Boolean? {
        val draft = parseObject(draftFile(sourceFile))
        val requestFiles = requestDirectory.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true) &&
                file.length() in 1..MAX_REQUEST_BYTES
        }.orEmpty().filter { requestFile ->
            val payload = parseObject(requestFile)
            payload != null &&
                payload.string("action") in setOf(ACTION_SAVE, ACTION_RESOLVE_CONFLICT) &&
                payload.string("source_file") == sourceFile
        }
        val requestIds = buildSet {
            draft?.string("request_id")?.takeIf(String::isUuid)?.let(::add)
            requestFiles.mapNotNullTo(this) { it.nameWithoutExtension.takeIf(String::isUuid) }
        }
        if (requestIds.any { requestId ->
                receipt(requestId)?.applied == true
            }
        ) {
            return null
        }
        var removed = true
        requestFiles.forEach { requestFile ->
            if (requestFile.exists() && !requestFile.delete()) {
                removed = false
            } else {
                requestScan("docs-source-requests/${requestFile.name}")
            }
        }
        requestIds.forEach(::clearDeliveryStarted)
        if (requestFiles.isNotEmpty()) directorySync(requestDirectory)
        val localDraft = draftFile(sourceFile)
        if (localDraft.exists() && !localDraft.delete()) removed = false
        return removed
    }

    /**
     * Resolves a file needing a merge by keeping its phone version: the phone copy is rebased
     * on the synced version and queued for the next Sync, which then replaces the synced
     * version. [expectedPhoneSha256] names the phone version the user chose. Returns the
     * request ID; throws when the file changed or Sync is already handling it.
     */
    fun keepPhoneVersionForMerge(sourceFile: String, expectedPhoneSha256: String): String {
        val store = checkNotNull(offlineStore) { "The phone's source copy is unavailable." }
        check(sourceMergeResolvable(pendingSourceMutations(sourceFile))) { SYNC_SENDING_MERGE_FILE }
        val snapshot = store.keepPhoneVersion(sourceFile, expectedPhoneSha256)
            ?: error("The file changed or no longer needs a merge.")
        val requestId = enqueueMergeSave(snapshot, snapshot.contents, notifySync = false)
        PendingChangesSignal.notifyChanged()
        return requestId
    }

    /**
     * Resolves a file needing a merge by using its synced version. A queued save of the phone
     * version is withdrawn, and the phone version is kept on the phone for recovery.
     * Returns false when the file changed or Sync already applied the phone version.
     */
    fun useSyncedVersionForMerge(sourceFile: String, expectedPhoneSha256: String): Boolean {
        val store = checkNotNull(offlineStore) { "The phone's source copy is unavailable." }
        val pending = pendingSourceMutations(sourceFile)
        check(sourceMergeResolvable(pending)) { SYNC_SENDING_MERGE_FILE }
        if (store.sourceMergeVersions(sourceFile)?.phoneSha256 != expectedPhoneSha256) return false
        if (pending.isNotEmpty() && withdrawQueuedSaves(sourceFile) != true) return false
        val replaced = store.useSyncedVersion(sourceFile, expectedPhoneSha256)
        if (replaced && isWorkspaceStyleResourcePath(sourceFile) && !hasPendingWorkspaceStyleMutation()) {
            clearGlobalWorkspaceStyleRefresh()
        }
        PendingChangesSignal.notifyChanged()
        return replaced
    }

    private fun draftFile(sourceFile: String): File =
        File(cacheDirectory, "drafts/${sha256(sourceFile)}.json")

    private fun conflictBundleFile(conflictId: String): File =
        File(cacheDirectory, "conflicts/${sha256(conflictId)}.json")

    private fun deliveryFile(requestId: String): File =
        File(cacheDirectory, "deliveries/$requestId.json")

    private fun workspaceStyleRefreshMarker(): File =
        File(cacheDirectory, "workspace-style-refresh-needed.json")

    private fun markGlobalWorkspaceStyleRefresh(sourceFile: String, requestId: String) {
        atomicWrite(
            workspaceStyleRefreshMarker(),
            JsonObject().apply {
                addProperty("schema_version", 1)
                addProperty("source_file", sourceFile)
                addProperty("request_id", requestId)
                addProperty("created_at", Instant.now().toString())
            }.toString(),
        )
    }

    private fun archiveReceipt(requestId: String, receiptFile: File): Boolean {
        if (!receiptFile.isFile) return true
        val history = File(cacheDirectory, "receipt-history")
        history.mkdirs()
        val destination = File(
            history,
            "$requestId.${System.currentTimeMillis()}.${UUID.randomUUID()}.json",
        )
        val bytes = runCatching { receiptFile.readBytes() }.getOrNull() ?: return false
        atomicWrite(destination, bytes.toString(StandardCharsets.UTF_8))
        if (!receiptFile.delete()) return false
        directorySync(receiptDirectory)
        return true
    }

    private fun deliveryStarted(requestId: String): Boolean = deliveryFile(requestId).isFile

    private fun clearDeliveryStarted(requestId: String) {
        deliveryFile(requestId).delete()
    }

    private fun acknowledgeResolvedConflictLineage(snapshot: SourceSnapshot, resolutionRequestId: String) {
        val draft = parseObject(draftFile(snapshot.sourceFile)) ?: return
        if (draft.string("resolution_request_id") != resolutionRequestId) return
        draft.string("request_id")
            .takeIf(String::isUuid)
            ?.takeIf { it != resolutionRequestId }
            ?.let(::acknowledge)
    }

    private fun rebaseSuccessor(delivered: SourceReceipt) {
        val snapshot = delivered.snapshot ?: return
        val draftFile = draftFile(snapshot.sourceFile)
        val draft = parseObject(draftFile) ?: return
        if (draft.string("predecessor_request_id") != delivered.requestId) {
            return
        }
        val successorId = draft.string("request_id").takeIf(String::isUuid) ?: return
        val requestFile = File(requestDirectory, "$successorId.json")
        val request = parseObject(requestFile) ?: return
        if (receipt(successorId) != null) return
        request.addProperty("resolution_id", snapshot.resolutionId)
        request.addProperty("base_sha256", snapshot.sourceSha256)
        if (request.int("schema_version") == MERGE_SCHEMA_VERSION) {
            request.addProperty("base_contents", snapshot.contents)
        }
        request.remove("predecessor_request_id")
        draft.addProperty("resolution_id", snapshot.resolutionId)
        draft.addProperty("base_sha256", snapshot.sourceSha256)
        if (draft.int("schema_version") == MERGE_SCHEMA_VERSION) {
            draft.addProperty("base_contents", snapshot.contents)
        }
        draft.remove("predecessor_request_id")
        atomicWrite(requestFile, request.toString())
        atomicWrite(draftFile, draft.toString())
        directorySync(requestDirectory)
        requestScan("docs-source-requests/${requestFile.name}")
    }

    private fun enrichFromDraft(snapshot: SourceSnapshot): SourceSnapshot {
        val draft = parseObject(draftFile(snapshot.sourceFile)) ?: return snapshot
        val bundleId = draft.string("bundle_id").takeIf { it.matches(SHA256_REGEX) } ?: return snapshot
        val baseContents = draft.stringOrNull("base_contents") ?: return snapshot
        val baseSha = draft.string("base_sha256").takeIf { it.matches(SHA256_REGEX) } ?: return snapshot
        if (sha256(baseContents) != baseSha) return snapshot
        return snapshot.copy(
            bundleId = bundleId,
            baseContents = baseContents,
            bundlePdfFilename = draft.string("pdf_filename"),
            bundlePdfSha256 = draft.string("pdf_sha256"),
            bundlePdfFilenames = draft.sourcePdfFilenames(),
        )
    }

    // FedoraRepository sets applied only after the returned canonical bytes are SHA-verified;
    // idempotent duplicate outcomes pass through that same verification before reaching here.
    private fun SourceReceipt.confirmsAppliedMutation(): Boolean =
        action in setOf(ACTION_SAVE, ACTION_RESOLVE_CONFLICT) &&
            status == STATUS_SUCCESS &&
            applied &&
            snapshot != null

    private fun publishedMutationRequest(requestId: String, action: String): Boolean {
        val payload = parseObject(File(requestDirectory, "$requestId.json")) ?: return false
        return validMutationRequest(payload, requestId, action)
    }

    private fun mutationRequestFromDraft(draft: JsonObject, requestId: String): JsonObject? {
        val resolutionRequestId = draft.string("resolution_request_id").takeIf(String::isUuid)
        val action = if (resolutionRequestId == requestId) ACTION_RESOLVE_CONFLICT else ACTION_SAVE
        val contents = draft.stringOrNull("contents") ?: return null
        if (contents.toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_BYTES) return null
        val createdAt = if (action == ACTION_RESOLVE_CONFLICT) {
            draft.string("resolution_created_at").ifBlank {
                draft.string("resolution_saved_at")
            }
        } else {
            draft.string("created_at").ifBlank { draft.string("saved_at") }
        }.ifBlank { Instant.now().toString() }
        val payload = JsonObject().apply {
            addProperty(
                "schema_version",
                if (action == ACTION_RESOLVE_CONFLICT) CONFLICT_SCHEMA_VERSION
                else draft.int("schema_version"),
            )
            addProperty("request_id", requestId)
            addProperty("created_at", createdAt)
            addProperty("action", action)
            addProperty("source_file", draft.string("source_file"))
            addProperty("source_line", draft.int("source_line"))
            addProperty("resolution_id", draft.string("resolution_id"))
            addProperty("pdf_filename", draft.string("pdf_filename"))
            addSourcePdfFilenames(draft.sourcePdfFilenames())
            addProperty("contents", contents)
            addProperty("draft_revision", draft.int("draft_revision"))
            putClaims(outstandingClaims(draft, draft.string("source_file"), emptyList()))
            if (action == ACTION_SAVE) {
                addProperty("build_after_sync", draft.optionalBoolean("build_after_sync") ?: false)
                addProperty("pdf_sha256", draft.string("pdf_sha256"))
                addProperty("base_sha256", draft.string("base_sha256"))
                draft.string("bundle_id").takeIf(String::isNotBlank)?.let {
                    addProperty("bundle_id", it)
                }
                draft.stringOrNull("base_contents")?.let { addProperty("base_contents", it) }
                draft.string("predecessor_request_id").takeIf(String::isUuid)?.let {
                    addProperty("predecessor_request_id", it)
                }
            } else {
                val contentsSha256 = sha256(contents)
                val storedContentsSha256 = draft.string("content_sha256")
                if (storedContentsSha256.isNotBlank() && storedContentsSha256 != contentsSha256) {
                    return null
                }
                addProperty("conflict_id", draft.string("conflict_id"))
                addProperty("expected_revision", draft.long("expected_revision"))
                addProperty("expected_sha256", draft.string("expected_sha256"))
                addProperty("content_sha256", contentsSha256)
                draft.int("conflict_count").takeIf { it > 0 }?.let {
                    addProperty("conflict_count", it)
                }
                draft.string("current_sha256").takeIf { it.matches(SHA256_REGEX) }?.let {
                    addProperty("current_sha256", it)
                }
                draft.string("incoming_sha256").takeIf { it.matches(SHA256_REGEX) }?.let {
                    addProperty("incoming_sha256", it)
                }
                draft.string("merged_object_sha256").takeIf { it.matches(SHA256_REGEX) }?.let {
                    addProperty("merged_object_sha256", it)
                }
                draft.string("current_modified_at").takeIf(String::isNotBlank)?.let {
                    addProperty("current_modified_at", it)
                }
                draft.string("incoming_modified_at").takeIf(String::isNotBlank)?.let {
                    addProperty("incoming_modified_at", it)
                }
                draft.string("conflict_request_id").takeIf(String::isUuid)
                    ?.let { addProperty("conflict_request_id", it) }
                    ?: draft.string("request_id").takeIf(String::isUuid)?.let {
                        addProperty("conflict_request_id", it)
                    }
            }
        }
        return payload.takeIf { validMutationRequest(it, requestId, action) }
    }

    private fun validMutationRequest(payload: JsonObject, requestId: String, action: String): Boolean {
        if (payload.string("request_id") != requestId || payload.string("action") != action) return false
        if (payload.string("source_file").isBlank()) return false
        val pdfFilename = payload.string("pdf_filename")
        if (pdfFilename != File(pdfFilename).name || !pdfFilename.endsWith(".pdf")) return false
        if (pdfFilename !in payload.sourcePdfFilenames()) return false
        if (!payload.string("resolution_id").isResolutionId()) return false
        if (payload.int("source_line") <= 0 || payload.int("draft_revision") <= 0) return false
        val contents = payload.stringOrNull("contents") ?: return false
        if (contents.toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_BYTES) return false
        if (action == ACTION_RESOLVE_CONFLICT) {
            return payload.int("schema_version") == CONFLICT_SCHEMA_VERSION &&
                payload.string("conflict_id").isNotBlank() &&
                payload.long("expected_revision") >= 0 &&
                payload.string("expected_sha256").matches(SHA256_REGEX) &&
                payload.string("content_sha256") == sha256(contents)
        }
        if (action != ACTION_SAVE || payload.int("schema_version") !in
            setOf(SCHEMA_VERSION, MERGE_SCHEMA_VERSION)
        ) {
            return false
        }
        if (!payload.string("pdf_sha256").matches(SHA256_REGEX) ||
            !payload.string("base_sha256").matches(SHA256_REGEX)
        ) {
            return false
        }
        if (payload.int("schema_version") == MERGE_SCHEMA_VERSION) {
            val baseContents = payload.stringOrNull("base_contents") ?: return false
            if (!payload.string("bundle_id").matches(SHA256_REGEX) ||
                sha256(baseContents) != payload.string("base_sha256")
            ) {
                return false
            }
        }
        return true
    }

    private fun saveDraft(
        snapshot: SourceSnapshot,
        pdfFilename: String,
        pdfSha256: String,
        contents: String,
        requestId: String,
        draftRevision: Int,
        buildAfterSync: Boolean,
        predecessorRequestId: String?,
        createdAt: String,
        claims: List<ProposalDecisionClaim>,
    ) {
        val payload = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("saved_at", Instant.now().toString())
            addProperty("created_at", createdAt)
            addProperty("resolution_id", snapshot.resolutionId)
            addProperty("source_file", snapshot.sourceFile)
            addProperty("source_line", snapshot.sourceLine)
            addProperty("base_sha256", snapshot.sourceSha256)
            addProperty("pdf_filename", pdfFilename)
            addProperty("pdf_sha256", pdfSha256)
            addSourcePdfFilenames(snapshot.sourcePdfFilenames(pdfFilename))
            addProperty("contents", contents)
            addProperty("request_id", requestId)
            addProperty("draft_revision", draftRevision)
            addProperty("build_after_sync", buildAfterSync)
            predecessorRequestId?.let { addProperty("predecessor_request_id", it) }
            putClaims(claims)
        }
        atomicWrite(File(cacheDirectory, "drafts/${sha256(snapshot.sourceFile)}.json"), payload.toString())
    }

    /** The source's unanswered decision claims plus [added]; a newer claim replaces one for the same ID. */
    private fun outstandingClaims(
        draft: JsonObject?,
        sourceFile: String,
        added: List<ProposalDecisionClaim>,
    ): List<ProposalDecisionClaim> = ProposalDecisionClaims.merge(
        ProposalDecisionClaims.read(draft?.get(ProposalDecisionClaims.REQUEST_MEMBER)).filter { it.sourceFile == sourceFile },
        added.filter { it.sourceFile == sourceFile },
    )

    private fun JsonObject.putClaims(claims: List<ProposalDecisionClaim>) {
        if (claims.isEmpty()) remove(ProposalDecisionClaims.REQUEST_MEMBER)
        else add(ProposalDecisionClaims.REQUEST_MEMBER, ProposalDecisionClaims.toJson(claims))
    }

    /**
     * Accepts made in the source editor that Fedora has not answered yet, and the proposals
     * whose wrapper the latest draft of their source still carries. The reader stops marking an
     * accepted proposal at once (the PDF already shows its text); a restored wrapper is marked again.
     */
    internal fun editorProposalDecisions(sourceFiles: Collection<String>): EditorProposalDecisions {
        val accepted = hashSetOf<String>()
        val carried = hashSetOf<String>()
        for (sourceFile in sourceFiles.toSet()) {
            val draft = parseObject(draftFile(sourceFile)) ?: continue
            if (draft.string("source_file") != sourceFile) continue
            val contents = draft.string("contents")
            carried += ProposalDecisionClaims.presentIds(sourceFile, contents)
            val claims = ProposalDecisionClaims.read(draft.get(ProposalDecisionClaims.REQUEST_MEMBER))
            ProposalDecisionClaims.forUpload(claims, sourceFile, contents).send
                .filter { it.action == "accept" }
                .mapTo(accepted) { it.proposalId }
        }
        return EditorProposalDecisions(accepted, carried)
    }

    /** Proposals of [sourceFile] whose decision claims (any action) its queued upload will carry. */
    internal fun editorDecidedProposalIds(sourceFile: String): Set<String> {
        val draft = parseObject(draftFile(sourceFile)) ?: return emptySet()
        if (draft.string("source_file") != sourceFile) return emptySet()
        val claims = ProposalDecisionClaims.read(draft.get(ProposalDecisionClaims.REQUEST_MEMBER))
        return ProposalDecisionClaims.forUpload(claims, sourceFile, draft.string("contents")).send
            .mapTo(hashSetOf()) { it.proposalId }
    }

    /** The decision claims a durable request carries (section 10.4.2). */
    internal fun requestProposalDecisions(request: JsonObject): List<ProposalDecisionClaim> =
        ProposalDecisionClaims.read(request.get(ProposalDecisionClaims.REQUEST_MEMBER))
            .filter { it.sourceFile == request.string("source_file") }

    /**
     * Fedora answered an upload that carried (or dropped) these claims: they expire. They leave the
     * draft and every request of the source that has not started delivery; a request in flight
     * keeps its claims, so its retries send the same request.
     */
    fun expireProposalDecisions(sourceFile: String, decisionIds: Set<String>) {
        if (decisionIds.isEmpty()) return
        val draftFile = draftFile(sourceFile)
        val draft = parseObject(draftFile) ?: return
        fun prune(value: JsonObject): Boolean {
            val claims = ProposalDecisionClaims.read(value.get(ProposalDecisionClaims.REQUEST_MEMBER))
            val kept = claims.filterNot { it.decisionId in decisionIds }
            if (kept.size == claims.size) return false
            value.putClaims(kept)
            return true
        }
        listOf(draft.string("request_id"), draft.string("resolution_request_id"))
            .filter(String::isUuid)
            .distinct()
            .filter { receipt(it) == null && !deliveryStarted(it) }
            .forEach { requestId ->
                val requestFile = File(requestDirectory, "$requestId.json")
                val request = parseObject(requestFile) ?: return@forEach
                if (request.string("source_file") == sourceFile && prune(request)) atomicWrite(requestFile, request.toString())
            }
        if (prune(draft)) atomicWrite(draftFile, draft.toString())
    }

    private fun publishRequest(requestId: String, payload: JsonObject, notifySync: Boolean = true) {
        val requestFile = File(requestDirectory, "$requestId.json")
        atomicWrite(requestFile, payload.toString())
        requestScan("docs-source-requests/${requestFile.name}")
        // The request is already durable. Scheduling failure must never make callers
        // believe publication failed and overwrite or discard their local draft.
        if (notifySync) runCatching(requestPublished)
    }

    private fun basePayload(requestId: String, action: String, pdfFilename: String, pdfSha256: String) =
        JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("request_id", requestId)
            addProperty("created_at", Instant.now().toString())
            addProperty("action", action)
            addProperty("pdf_filename", pdfFilename)
            addProperty("pdf_sha256", pdfSha256)
        }

    private fun parseObject(file: File, maxBytes: Long = MAX_REQUEST_BYTES): JsonObject? = runCatching {
        if (!file.isFile || file.length() > maxBytes) return@runCatching null
        JsonParser.parseString(file.readText()).asJsonObject
    }.getOrNull()

    private fun atomicWrite(destination: File, contents: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(contents.toByteArray(StandardCharsets.UTF_8))
            stream.fd.sync()
        }
        check(temporary.renameTo(destination)) { "Could not publish ${destination.path}" }
        directorySync(destination.parentFile)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MERGE_SCHEMA_VERSION = 2
        const val CONFLICT_SCHEMA_VERSION = 3
        const val ACTION_OPEN = "open"
        const val ACTION_SAVE = "save"
        const val ACTION_RESOLVE_CONFLICT = "resolve_conflict"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILURE = "failure"
        const val STATUS_STALE = "stale"
        const val STATUS_CONFLICT = "conflict"
        const val MAX_SOURCE_BYTES = 2_000_000
        const val MAX_REQUEST_BYTES = 4_500_000L
        const val MAX_CONFLICT_BUNDLE_BYTES = 13_000_000L
        const val MAX_SELECTED_TEXT = 100_000

        fun defaultRequestDirectory(): File = ThoughtsLocalStore.sourceRequests()

        fun defaultReceiptDirectory(): File = ThoughtsLocalStore.sourceReceipts()
    }
}

private fun syncSourceDirectory(directory: File?) {
    if (directory == null) return
    val descriptor = try {
        Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    } catch (_: ErrnoException) {
        return
    }
    try {
        Os.fsync(descriptor)
    } catch (_: ErrnoException) {
        return
    } finally {
        Os.close(descriptor)
    }
}

private fun JsonObject.toSnapshot(): SourceSnapshot? {
    val resolutionId = string("resolution_id").takeIf(String::isResolutionId) ?: return null
    val sourceFile = string("source_file").takeIf(String::isNotBlank) ?: return null
    val sourceLine = int("source_line").takeIf { it > 0 } ?: return null
    val sourceSha256 = string("source_sha256").takeIf { it.matches(SHA256_REGEX) } ?: return null
    val contents = get("contents")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    if (contents.toByteArray(StandardCharsets.UTF_8).size > SourceEditQueue.MAX_SOURCE_BYTES) return null
    val bundleId = string("bundle_id").takeIf { it.matches(SHA256_REGEX) }
    val baseContents = stringOrNull("base_contents")
    val bundlePdfFilename = string("bundle_pdf_filename").takeIf(String::isNotBlank)
    val bundlePdfSha256 = string("bundle_pdf_sha256").takeIf { it.matches(SHA256_REGEX) }
    val bundlePdfFilenames = sourcePdfFilenames(
        primaryKey = "bundle_pdf_filename",
        listKey = "bundle_pdf_filenames",
    )
    val navigationBase = stringOrNull("navigation_base_contents")?.takeIf {
        it.toByteArray(StandardCharsets.UTF_8).size <= SourceEditQueue.MAX_SOURCE_BYTES &&
            sha256(it) == string("navigation_base_sha256")
    }
    return SourceSnapshot(
        resolutionId,
        sourceFile,
        sourceLine,
        sourceSha256,
        contents,
        bundleId,
        baseContents,
        bundlePdfFilename,
        bundlePdfSha256,
        bundlePdfFilenames,
        navigationBase,
    )
}

private fun JsonObject.toSourceSelection(): SourceSelection? {
    if (string("action") != SourceEditQueue.ACTION_OPEN) return null
    val pdfFilename = string("pdf_filename")
    val pdfSha256 = string("pdf_sha256")
    val page = int("page")
    val x = float("x")
    val y = float("y")
    val selectedText = string("selected_text")
    if (pdfFilename != File(pdfFilename).name || !pdfFilename.endsWith(".pdf") ||
        !pdfSha256.matches(SHA256_REGEX) || page < 1 ||
        !x.isFinite() || x < 0f || !y.isFinite() || y < 0f
    ) {
        return null
    }
    return SourceSelection(pdfFilename, pdfSha256, page, x, y, selectedText)
}

private fun JsonObject.string(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.int(key: String): Int = runCatching { get(key)?.asInt ?: 0 }.getOrDefault(0)
private fun JsonObject.long(key: String): Long = runCatching { get(key)?.asLong ?: -1L }.getOrDefault(-1L)
private fun JsonObject.float(key: String): Float = runCatching { get(key)?.asFloat ?: Float.NaN }.getOrDefault(Float.NaN)
private fun JsonObject.boolean(key: String): Boolean = runCatching { get(key)?.asBoolean ?: false }.getOrDefault(false)
private fun JsonObject.optionalBoolean(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun SourceSnapshot.sourcePdfFilenames(fallback: String): List<String> =
    bundlePdfFilenames.ifEmpty { listOfNotNull(bundlePdfFilename, fallback) }
        .filter(::isSafeSourcePdfFilename)
        .distinct()
        .sorted()

private fun JsonObject.sourcePdfFilenames(
    primaryKey: String = "pdf_filename",
    listKey: String = "pdf_filenames",
): List<String> {
    val listed = getAsJsonArray(listKey)
        ?.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf(::isSafeSourcePdfFilename)
        }
        .orEmpty()
    return listed.ifEmpty {
        listOfNotNull(string(primaryKey).takeIf(::isSafeSourcePdfFilename))
    }.distinct().sorted()
}

private fun JsonObject.addSourcePdfFilenames(
    filenames: List<String>,
    key: String = "pdf_filenames",
) {
    val safe = filenames.filter(::isSafeSourcePdfFilename).distinct().sorted()
    if (safe.isEmpty()) return
    add(key, JsonArray().apply { safe.forEach(::add) })
}

private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess

private fun isSafeSourcePdfFilename(value: String): Boolean =
    value == File(value).name && value.endsWith(".pdf") && !value.contains('/') && !value.contains('\\')

private fun String.isResolutionId(): Boolean =
    length in 1..256 && all { character ->
        character.isLetterOrDigit() || character in setOf('-', '_', '.', ':')
    }

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
private val CACHE_KEY_REGEX = SHA256_REGEX
private val CONFLICT_MARKER = Regex("(?m)^(<<<<<<<|\\|\\|\\|\\|\\|\\|\\||=======|>>>>>>>)(?: .*)?$")

fun containsConflictMarkers(contents: String): Boolean = CONFLICT_MARKER.containsMatchIn(contents)

private const val SYNC_SENDING_MERGE_FILE = "Sync is sending this file's phone edit. Resolve it after Sync finishes."

/**
 * Whether a file needing a merge can be resolved on the phone now: its phone edit is not queued,
 * or still only queued (or failed) and not yet being delivered or resolved as a TeX conflict.
 */
internal fun sourceMergeResolvable(mutations: List<PendingSourceMutation>): Boolean =
    mutations.all { it.state == SourceMutationState.QUEUED || it.state == SourceMutationState.FAILED }

private fun sourceMutationDisplayError(
    state: SourceMutationState,
    rawError: String,
    sourceFile: String,
): String {
    if (state == SourceMutationState.CONFLICT) return "TeX conflict in $sourceFile"
    if (state != SourceMutationState.FAILED) return ""
    val trimmed = rawError.trim()
    val emptyServerDetail = runCatching {
        val parsed = JsonParser.parseString(trimmed).asJsonObject
        parsed.entrySet().isEmpty() || parsed.string("detail").isBlank()
    }.getOrDefault(false)
    return if (trimmed.isBlank() || emptyServerDetail) {
        "The server did not confirm whether this TeX change was applied. Sync needs attention."
    } else {
        trimmed
    }
}

/** See [SourceEditQueue.editorProposalDecisions]. */
internal data class EditorProposalDecisions(
    val accepted: Set<String>,
    val carried: Set<String>,
)
