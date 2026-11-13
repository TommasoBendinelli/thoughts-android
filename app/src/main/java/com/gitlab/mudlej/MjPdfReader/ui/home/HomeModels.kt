// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.text.TextUtils
import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.render.ExplicitPdfRenderStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceMutationState
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutation
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore

/** DIARY took the place of the Comments tab ("OVERVIEW"), which Preferences migrates to it. */
enum class HomeTab { DIARY, NOTES, LIBRARY, REVIEW, AB_TESTING }

/**
 * The tabs Home shows, in pager order (a tab's position is its ordinal): AB_TESTING, the last one,
 * only when the build names a rating page (Gradle property `thoughtsAbTestingUrl`).
 */
fun visibleHomeTabs(abTestingConfigured: Boolean): List<HomeTab> =
    HomeTab.entries.filter { it != HomeTab.AB_TESTING || abTestingConfigured }

/** The tab Home opens on: the saved one while it is shown, else DIARY. */
fun initialHomeTab(saved: HomeTab, visible: List<HomeTab>): HomeTab =
    saved.takeIf { it in visible } ?: HomeTab.DIARY

enum class PullToRefreshAction { SYNC, RELOAD_PAGE }

enum class CachedFedoraStatusCode { SYNC_NEEDED, SYNCING, SYNCED, ERROR }

data class CachedFedoraStatusSummary(
    val code: CachedFedoraStatusCode,
    val count: Int,
    val total: Int,
    val filenames: Set<String> = emptySet(),
    val counts: Map<CachedFedoraStatusCode, Int> = emptyMap(),
)

enum class BuiltTimeKind { NOT_BUILT, JUST_NOW, SECONDS, MINUTES, HOURS, EXACT }

data class BuiltTimePresentation(
    val kind: BuiltTimeKind,
    val value: Long = 0,
)

fun syncElapsedSeconds(
    startedAtEpochMillis: Long,
    nowEpochMillis: Long,
): Long = ((nowEpochMillis - startedAtEpochMillis).coerceAtLeast(0L) / 1_000L)

fun builtTimePresentation(
    builtAtEpochMillis: Long,
    nowEpochMillis: Long,
): BuiltTimePresentation {
    if (builtAtEpochMillis <= 0) return BuiltTimePresentation(BuiltTimeKind.NOT_BUILT)
    val age = (nowEpochMillis - builtAtEpochMillis).coerceAtLeast(0)
    return when {
        age == 0L -> BuiltTimePresentation(BuiltTimeKind.JUST_NOW)
        age < 60_000L -> BuiltTimePresentation(BuiltTimeKind.SECONDS, maxOf(1, age / 1_000L))
        age < 3_600_000L -> BuiltTimePresentation(BuiltTimeKind.MINUTES, age / 60_000L)
        age < 86_400_000L -> BuiltTimePresentation(BuiltTimeKind.HOURS, age / 3_600_000L)
        else -> BuiltTimePresentation(BuiltTimeKind.EXACT)
    }
}

fun nextBuiltTimeRefreshDelay(
    builtAtEpochMillis: Long,
    nowEpochMillis: Long,
): Long? {
    if (builtAtEpochMillis <= 0) return null
    val age = (nowEpochMillis - builtAtEpochMillis).coerceAtLeast(0)
    return when {
        age < 60_000L -> 1_000L - (age % 1_000L)
        age < 3_600_000L -> 60_000L - (age % 60_000L)
        age < 86_400_000L -> 3_600_000L - (age % 3_600_000L)
        else -> null
    }
}

fun cachedFedoraStatusSummary(
    snapshot: PhoneRenderStatusSnapshot,
    remoteStatuses: List<ExplicitPdfRenderStatus>,
    globalStatus: String?,
): CachedFedoraStatusSummary {
    val filenames = remoteStatuses.mapTo(linkedSetOf(), ExplicitPdfRenderStatus::filename)
    if (filenames.isEmpty()) {
        snapshot.jobs
            .asSequence()
            .filter { !it.affectsAll && it.target.endsWith(".pdf", ignoreCase = true) }
            .mapTo(filenames) { it.target }
    }
    if (filenames.isEmpty()) {
        return CachedFedoraStatusSummary(
            cachedFedoraStatusCode(snapshot.primary?.stage, globalStatus),
            count = 0,
            total = 0,
        )
    }
    val remoteByFilename = remoteStatuses.associateBy(ExplicitPdfRenderStatus::filename)
    val byCode = filenames.groupBy { filename ->
        val localStage = snapshot.statusForPdf(filename)?.stage
        val remoteStatus = remoteByFilename[filename]?.status
        cachedFedoraStatusCode(
            localStage,
            when (remoteStatus) {
                "synced" -> "current"
                "out_of_sync" -> "changed"
                "building" -> "building"
                "attention", "error" -> "attention"
                else -> null
            },
        )
    }
    val code = listOf(
        CachedFedoraStatusCode.ERROR,
        CachedFedoraStatusCode.SYNCING,
        CachedFedoraStatusCode.SYNC_NEEDED,
        CachedFedoraStatusCode.SYNCED,
    ).first { !byCode[it].isNullOrEmpty() }
    val selected = byCode.getValue(code).toSet()
    return CachedFedoraStatusSummary(
        code,
        selected.size,
        filenames.size,
        selected,
        byCode.mapValues { it.value.size },
    )
}

fun cachedFedoraStatusCode(
    stage: PhoneRenderStage?,
    globalStatus: String?,
): CachedFedoraStatusCode = when (stage) {
    PhoneRenderStage.UPDATED -> CachedFedoraStatusCode.SYNCED
    PhoneRenderStage.SYNC_NEEDED,
    PhoneRenderStage.SOURCE_SYNC_NEEDED,
    PhoneRenderStage.SYNC_PENDING -> CachedFedoraStatusCode.SYNC_NEEDED
    PhoneRenderStage.OUT_OF_SYNC -> CachedFedoraStatusCode.SYNC_NEEDED
    PhoneRenderStage.QUEUED -> CachedFedoraStatusCode.SYNC_NEEDED
    PhoneRenderStage.FEDORA_QUEUED,
    PhoneRenderStage.CHECKING,
    PhoneRenderStage.WAITING_FOR_SYNC -> CachedFedoraStatusCode.SYNCING
    PhoneRenderStage.REBUILDING -> CachedFedoraStatusCode.SYNCING
    PhoneRenderStage.BLOCKED,
    PhoneRenderStage.ATTENTION,
    PhoneRenderStage.PARTIAL,
    PhoneRenderStage.FAILED -> CachedFedoraStatusCode.ERROR
    PhoneRenderStage.UNVERIFIED -> CachedFedoraStatusCode.SYNC_NEEDED
    null -> when (globalStatus) {
        "current" -> CachedFedoraStatusCode.SYNCED
        "changed" -> CachedFedoraStatusCode.SYNC_NEEDED
        "building" -> CachedFedoraStatusCode.SYNCING
        "attention" -> CachedFedoraStatusCode.ERROR
        else -> CachedFedoraStatusCode.SYNC_NEEDED
    }
}

data class PendingPhoneSyncFile(
    val filename: String,
    val details: List<String>,
)

fun terminalLibrarySyncStatus(
    operationStatus: String,
    pendingChangesSummary: String,
    failedSyncSummary: String,
): HomeSection.FedoraStatus? = if (
    operationStatus == SyncOperationStore.STATUS_ERROR ||
    operationStatus == SyncOperationStore.STATUS_ATTENTION
) {
    HomeSection.FedoraStatus(
        label = pendingChangesSummary,
        timestamp = failedSyncSummary,
        hasSyncFailure = true,
    )
} else {
    null
}

fun pendingPhoneSyncFiles(snapshot: PhoneRenderStatusSnapshot): List<PendingPhoneSyncFile> =
    snapshot.jobs
        .asSequence()
        .filter { status ->
            !status.affectsAll && (
                status.requestId.startsWith("local-sync:") ||
                    status.requestId.startsWith("local-source:") ||
                    status.requestId.startsWith("active-sync:")
                )
        }
        .groupBy(PhoneRenderJobStatus::target)
        .map { (filename, statuses) ->
            PendingPhoneSyncFile(
                filename = filename,
                details = statuses
                    .flatMap { it.message.lines() }
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct(),
            )
        }
        .sortedBy { it.filename.lowercase() }

/** One Library change saved on this phone, as the pending-changes list shows it. */
internal data class PendingLibraryChange(
    val filename: String,
    @StringRes val label: Int,
    val createdAtEpochMillis: Long,
    /** Set when Fedora refused the change; the change then blocks until the user acts. */
    val failure: String? = null,
)

/** Library changes still waiting for Sync: queued ones and imports Fedora refused. */
internal fun pendingLibraryChanges(changes: List<LibraryMutation>): List<PendingLibraryChange> =
    changes.filter(LibraryMutation::awaitsSync).map { change ->
        PendingLibraryChange(
            filename = change.filename,
            label = when (change.type) {
                LibraryMutation.Type.ADD -> R.string.document_status_pending_library_add
                LibraryMutation.Type.RENAME -> R.string.document_status_pending_library_rename
                LibraryMutation.Type.ARCHIVE -> R.string.document_status_pending_library_archive
            },
            createdAtEpochMillis = change.createdAtEpochMillis,
            failure = change.failureDetail
                .takeIf { change.state == LibraryMutation.State.CONFLICT }
                ?.let { "Not added to the shared Library: $it" },
        )
    }

fun pendingPhoneSyncFilenames(snapshot: PhoneRenderStatusSnapshot): Set<String> =
    pendingPhoneSyncFiles(snapshot).mapTo(linkedSetOf(), PendingPhoneSyncFile::filename)

fun pendingPhoneSyncFileCount(snapshot: PhoneRenderStatusSnapshot): Int =
    pendingPhoneSyncFilenames(snapshot).size

fun pendingPhoneSyncNeedsAttention(snapshot: PhoneRenderStatusSnapshot): Boolean =
    snapshot.jobs.any { status ->
        (status.requestId.startsWith("local-sync:") ||
            status.requestId.startsWith("local-source:")) &&
            status.stage in setOf(PhoneRenderStage.BLOCKED, PhoneRenderStage.FAILED)
    }

data class SourceAttentionActions(
    val resolveConflicts: Boolean,
    val retryFailures: Boolean,
)

fun sourceAttentionActions(mutations: List<PendingSourceMutation>): SourceAttentionActions =
    SourceAttentionActions(
        resolveConflicts = mutations.any { it.state == SourceMutationState.CONFLICT },
        retryFailures = mutations.any { it.state == SourceMutationState.FAILED },
    )

/** The A/B testing tab shows a web page that has nothing to sync: pulling loads the page again (a new pilot round). */
fun pullToRefreshAction(tab: HomeTab): PullToRefreshAction =
    if (tab == HomeTab.AB_TESTING) PullToRefreshAction.RELOAD_PAGE else PullToRefreshAction.SYNC

enum class DocumentStatusCode(val wireId: String) {
    SYNC_NEEDED("sync_needed"),
    SYNCING("syncing"),
    SYNCED("synced"),
    ERROR("error"),
}

enum class DocumentStatusAction {
    NONE,
    SYNC_NOW,
    SHOW_ERRORS,
}

enum class DocumentStatusCommand {
    NONE,
    REQUEST_MANUAL_SYNC,
    SHOW_ERRORS,
}

fun documentStatusCommand(action: DocumentStatusAction): DocumentStatusCommand = when (action) {
    DocumentStatusAction.SYNC_NOW -> DocumentStatusCommand.REQUEST_MANUAL_SYNC
    DocumentStatusAction.SHOW_ERRORS -> DocumentStatusCommand.SHOW_ERRORS
    DocumentStatusAction.NONE -> DocumentStatusCommand.NONE
}

@StringRes
fun documentStatusDismissLabel(action: DocumentStatusAction): Int =
    if (action == DocumentStatusAction.NONE) R.string.ok else R.string.cancel

fun syncDialogPrimaryAction(status: com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus?): DocumentStatusAction {
    val statusAction = status?.let {
        documentStatusPresentation(it.stage, it.verifiedAtEpochMillis).action
    }
    return statusAction?.takeIf { it != DocumentStatusAction.NONE }
        ?: when (status?.stage) {
            null,
            PhoneRenderStage.UPDATED,
            PhoneRenderStage.UNVERIFIED -> DocumentStatusAction.SYNC_NOW
            else -> DocumentStatusAction.NONE
        }
}

data class DocumentStatusPresentation(
    val code: DocumentStatusCode,
    val action: DocumentStatusAction,
    val showOnDocumentRow: Boolean,
    val compactVerifiedTick: Boolean = false,
)

fun documentStatusPresentation(
    stage: com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage,
    verifiedAtEpochMillis: Long = 0,
): DocumentStatusPresentation = when (stage) {
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.SYNC_NEEDED ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNC_NEEDED,
            DocumentStatusAction.SYNC_NOW,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.QUEUED ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNC_NEEDED,
            DocumentStatusAction.SYNC_NOW,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.FEDORA_QUEUED ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNCING,
            DocumentStatusAction.NONE,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.SOURCE_SYNC_NEEDED ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNC_NEEDED,
            DocumentStatusAction.SYNC_NOW,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.OUT_OF_SYNC ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNC_NEEDED,
            DocumentStatusAction.SYNC_NOW,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.SYNC_PENDING ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNC_NEEDED,
            DocumentStatusAction.SYNC_NOW,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.BLOCKED ->
        DocumentStatusPresentation(
            DocumentStatusCode.ERROR,
            DocumentStatusAction.SYNC_NOW,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.ATTENTION ->
        DocumentStatusPresentation(
            DocumentStatusCode.ERROR,
            DocumentStatusAction.SHOW_ERRORS,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.PARTIAL,
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.FAILED ->
        DocumentStatusPresentation(
            DocumentStatusCode.ERROR,
            DocumentStatusAction.SYNC_NOW,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.UPDATED ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNCED,
            DocumentStatusAction.NONE,
            showOnDocumentRow = false,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.CHECKING,
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.WAITING_FOR_SYNC ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNCING,
            DocumentStatusAction.NONE,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.REBUILDING ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNCING,
            DocumentStatusAction.NONE,
            showOnDocumentRow = true,
        )
    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage.UNVERIFIED ->
        DocumentStatusPresentation(
            DocumentStatusCode.SYNC_NEEDED,
            DocumentStatusAction.SYNC_NOW,
            showOnDocumentRow = true,
        )
}


enum class HomeTitleEllipsize(@StringRes val labelRes: Int, val truncateAt: TextUtils.TruncateAt) {
    START(R.string.home_title_ellipsize_start, TextUtils.TruncateAt.START),
    MIDDLE(R.string.home_title_ellipsize_middle, TextUtils.TruncateAt.MIDDLE),
    END(R.string.home_title_ellipsize_end, TextUtils.TruncateAt.END),
}

enum class HomeProgressStyle { RING, BAR }



enum class OverviewSortOrder { NEWEST, OLDEST, DOCUMENT, PAGE }

enum class OverviewAnchorFilter { ALL, ANCHORED, NEEDS_ANCHOR, PDF_ONLY }

enum class OverviewPdfFilter { ALL, TEX_LINKED, OTHER }

enum class OverviewPriorityFilter { ALL, NORMAL, HIGH }

data class OverviewLocationFilter(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val googlePlaceId: String = "",
    val radiusKm: Int = 10,
)

enum class ListFilter { RECENT, ALL, FAVORITE, TO_READ, READING, ON_HOLD, COMPLETED, ABANDONED }
