// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.sync.hasLegacyNoteSchemaFailure
import android.Manifest
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.gitlab.mudlej.MjPdfReader.App
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityHomeBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogPdfBuildErrorBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogReviewQaExportBinding
import com.gitlab.mudlej.MjPdfReader.data.entity.ReadingStatus
import com.gitlab.mudlej.MjPdfReader.data.HistoryCleaner
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationJournal
import com.gitlab.mudlej.MjPdfReader.data.annotation.firstPrimaryOrNull
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogue
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSidecarStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogueSnapshot
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewCommentDocument
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryOutbox
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryScheduler
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingFedoraBuildStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.isPendingSyncOperationStatus
import com.gitlab.mudlej.MjPdfReader.data.sync.nextSyncOperationReconciliationDelayMillis
import com.gitlab.mudlej.MjPdfReader.data.sync.ArtifactSyncCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.ArtifactSyncPlan
import com.gitlab.mudlej.MjPdfReader.data.sync.ArtifactSyncStateStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsNetworkPolicy
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferPlan
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferStateStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryHeadStateStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryRemovalStore
import com.gitlab.mudlej.MjPdfReader.data.sync.reconcileRemoteLibraryStatus
import com.gitlab.mudlej.MjPdfReader.data.sync.supersededExplicitPdfRenderStatusFilenames
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderReceipt
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusHistory
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderSyncHealth
import com.gitlab.mudlej.MjPdfReader.data.render.mergeExplicitPdfRenderStatuses
import com.gitlab.mudlej.MjPdfReader.data.render.currentArtifactSyncStatus
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.pendingSourceRenderStatuses
import com.gitlab.mudlej.MjPdfReader.data.render.sourceMutationStatusDetails
import com.gitlab.mudlej.MjPdfReader.data.review.PendingMarkerRemovalRetry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewLibrarySummary
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.sync.LocalBuildLogStore
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecisionPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesFileObserver
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncCommandCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.agentProposalDecisionOrNull
import com.google.gson.JsonParser
import com.gitlab.mudlej.MjPdfReader.data.usage.ReadingUsageService
import com.gitlab.mudlej.MjPdfReader.core.PermissionManager
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.ui.about.WhatsNewActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardBrowserActivity
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardBrowserFilter
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewQaExportRequest
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewQaPdfExporter
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewQaExportFileName
import com.gitlab.mudlej.MjPdfReader.ui.intro.MainIntroActivity
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsActivity
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsPage
import com.gitlab.mudlej.MjPdfReader.ui.source.SourceConflictListActivity
import com.gitlab.mudlej.MjPdfReader.ui.source.SourceEditorActivity
import com.gitlab.mudlej.MjPdfReader.ui.source.loadSourceFilesNeedingMerge
import com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBuildDialog
import com.gitlab.mudlej.MjPdfReader.ui.sync.SyncOperationsDialog
import com.gitlab.mudlej.MjPdfReader.ui.sync.SyncStatusLabels
import com.gitlab.mudlej.MjPdfReader.ui.sync.LibrarySyncIconState
import com.gitlab.mudlej.MjPdfReader.ui.sync.LibrarySyncStatusPresentation
import com.gitlab.mudlej.MjPdfReader.ui.sync.iconState
import com.gitlab.mudlej.MjPdfReader.ui.sync.librarySyncStatusPresentation
import com.gitlab.mudlej.MjPdfReader.ui.sync.localBuildLogRecordForStatus
import com.gitlab.mudlej.MjPdfReader.ui.sync.showSyncTransferPrompt
import com.gitlab.mudlej.MjPdfReader.ui.sync.loadSyncBuildDialogState
import com.gitlab.mudlej.MjPdfReader.core.io.PersistedGrantKeeper
import com.gitlab.mudlej.MjPdfReader.core.text.StringUtil.formatEnumToTitle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun pendingMobileDataPlan(
    operation: SyncOperationRecord,
    plan: SyncTransferPlan?,
): SyncTransferPlan? = plan?.takeIf {
    operation.status == SyncOperationStore.STATUS_WAITING &&
        it.operationId == operation.id &&
        !it.canRunOnMetered
}

internal class HomeTabRenderTracker {
    private var generation = 0L
    private val dataGenerations = LongArray(HomeTab.entries.size)
    private val renderedGenerations = LongArray(HomeTab.entries.size) { -1L }

    fun markDirty(vararg tabs: HomeTab) {
        generation += 1
        tabs.forEach { tab -> dataGenerations[tab.ordinal] = generation }
    }

    fun needsRender(tab: HomeTab): Boolean =
        renderedGenerations[tab.ordinal] < dataGenerations[tab.ordinal]

    fun markRendered(tab: HomeTab) {
        renderedGenerations[tab.ordinal] = dataGenerations[tab.ordinal]
    }
}

internal fun shouldRefreshHomeSearch(
    adapterInitialized: Boolean,
    searchShowing: Boolean,
): Boolean = adapterInitialized && searchShowing

internal fun reviewDueRefreshDelayMillis(
    nextDueAt: Instant?,
    nowEpochMillis: Long,
): Long? = nextDueAt?.toEpochMilli()?.let { dueAt ->
    (dueAt - nowEpochMillis).coerceAtLeast(1L)
}

class HomeActivity : AppCompatActivity(), HomeItemFunctions {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var pref: Preferences
    private lateinit var pdfRepository: PdfRepository
    private lateinit var historyPolicy: HistoryPolicy
    private lateinit var historyCleaner: HistoryCleaner
    private lateinit var permissionManager: PermissionManager
    private lateinit var coverCache: CoverCache
    private lateinit var libraryController: HomeLibraryController
    private lateinit var commentCatalogue: CommentCatalogue
    private lateinit var phoneRenderQueue: PhoneRenderQueue
    private lateinit var sourcePdfIndex: SourceLinkedPdfIndex
    private lateinit var phoneRenderStatusHistory: PhoneRenderStatusHistory
    private lateinit var selectionController: HomeSelectionController
    private lateinit var relocateController: RelocateController
    private lateinit var menuDialog: HomeMenuDialog
    private lateinit var recordOptionsDialog: RecordOptionsDialog
    private lateinit var searchResultsAdapter: LibraryAdapter
    private val globalSearchAdapter = GlobalSearchAdapter()
    private lateinit var globalSearch: HomeGlobalSearch
    private var globalSearchIndexJob: Job? = null
    private lateinit var overviewTab: OverviewTabController
    private lateinit var notesTab: OverviewTabController
    private lateinit var diaryTab: DiaryTabController
    private lateinit var libraryTab: LibraryTabController
    private lateinit var reviewTab: ReviewTabController
    private lateinit var abTestingTab: AbTestingTabController
    private lateinit var reviewStore: ReviewStore
    private lateinit var syncOperationStore: SyncOperationStore
    private lateinit var pendingChangesObserver: PendingChangesFileObserver
    private var pendingChangesObserverStartJob: Job? = null

    private var allItems: List<HomeItem> = emptyList()
    private var allRecordItems: List<HomeItem> = emptyList()
    private var overviewDocuments: List<OverviewCommentDocument> = emptyList()
    private var pendingLibraryRemovalFilenames: Set<String> = emptySet()
    private var overviewSourceSnapshot = OfflineOverviewSourceSnapshot()
    private var overviewSourceSnapshotLoaded = false
    private var overviewSourceSnapshotComplete = false
    private var sourceIndexSnapshot = SourceLinkedPdfIndexSnapshot()
    private var homeDataLoaded = false
    private var refreshQueued = false
    private var refreshLoopJob: Job? = null
    private val refreshCompletionCallbacks = mutableListOf<() -> Unit>()
    private var overviewSourceLoadJob: Job? = null
    private var overviewSourceLoadGeneration = 0L
    private var pendingOverviewSourceIndexSnapshot = SourceLinkedPdfIndexSnapshot()
    private var reviewSummaryLoadJob: Job? = null
    private var reviewSummaryLoadGeneration = 0L
    private var pendingReviewSourceIndexSnapshot = SourceLinkedPdfIndexSnapshot()
    private var reviewDueRefreshJob: Job? = null
    private var sourceIndexRefreshJob: Job? = null
    private var commentValidationJob: Job? = null
    private var commentSourcesValidated = false
    private val homeOpenedAt = SystemClock.elapsedRealtime()
    private var firstLibrarySubmissionLogged = false
    private var firstLibraryLayoutScheduled = false
    private var commentObserver: FileObserver? = null
    private var logicalCommentObserver: kotlinx.coroutines.Job? = null
    private var commentRefreshJob: Job? = null
    private var renderReceiptObserver: FileObserver? = null
    private var sourceIndexObserver: FileObserver? = null
    private var renderReceiptJob: Job? = null
    private var renderStatusPollJob: Job? = null
    private val renderStatusIdleGate = com.gitlab.mudlej.MjPdfReader.data.IdleRefreshGate(RENDER_STATUS_IDLE_BACKSTOP_MS)
    private val renderStatusRefreshMutex = Mutex()
    private val renderStatusRequestGeneration = AtomicLong()
    private var renderStatusCompletedGeneration = 0L
    private var syncElapsedRefreshJob: Job? = null
    private var syncBuildRefreshJob: Job? = null
    private var syncOperationReconciliationJob: Job? = null
    private var syncBuildDialog: SyncBuildDialog? = null
    private var syncBuildUri: Uri? = null
    private var renderStatusObserved = false
    private var renderStatusSnapshot = PhoneRenderStatusSnapshot.EMPTY
    private var latestGlobalOperationSnapshot: SyncOperationRecord? = null
    private var lastSuccessfulSyncAtEpochMillis: Long = 0
    /** Source files that need a merge; Merge needed outranks every other Sync failure. */
    private var sourceMergeFileCount = 0
    private var syncStatusButtonAnimator: ObjectAnimator? = null
    private var syncFinishedReceiverRegistered = false
    private var pendingSyncAfterNotificationPermission: (() -> Unit)? = null
    private var pendingReviewQaExport: ReviewQaExportRequest? = null
    private val tabRenderTracker = HomeTabRenderTracker()
    private var commentIssuesPage: SwipeRefreshLayout? = null
    private var commentIssuesNeedRender = true

    private val syncFinishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ThoughtsSyncScheduler.ACTION_FINISHED -> {
                    Log.d(TAG, "Fedora sync finished; refreshing Home state")
                    restartSourceIndexObserver()
                    sourcePdfIndex.invalidate()
                    refresh()
                    scheduleRenderStatusRefresh()
                    refreshOpenSyncBuild()
                }
                SyncOperationStore.ACTION_STATE_CHANGED -> {
                    applyCachedFedoraStatus(renderStatusSnapshot)
                    refreshOpenSyncBuild()
                    scheduleSyncOperationReconciliation()
                }
                ArtifactSyncCoordinator.ACTION_STATE_CHANGED -> {
                    refreshOpenSyncBuild()
                    if (intent.getBooleanExtra(ArtifactSyncCoordinator.EXTRA_SHOW_PROMPT, false)) {
                        ArtifactSyncStateStore(this@HomeActivity).load()?.let(::showArtifactSyncPrompt)
                    }
                }
                SyncTransferCoordinator.ACTION_STATE_CHANGED -> {
                    if (intent.getBooleanExtra(SyncTransferCoordinator.EXTRA_SHOW_PROMPT, false)) {
                        SyncTransferStateStore(this@HomeActivity).load()?.let {
                            showSyncTransferPrompt(this@HomeActivity, it)
                        }
                    }
                }
            }
        }
    }

    private val reviewBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            reviewTab.goBack()
        }
    }

    private val commentIssuesBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            hideCommentIssues()
        }
    }

    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            hideHomeSearch()
        }
    }

    private val introLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            showWhatsNewOnFirstRun()
        }

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            PersistedGrantKeeper.takeReadGrant(this, uri)
            openInReader(uri)
        }
    }

    private val projectPdfPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { PersistedGrantKeeper.takeReadGrant(this, it) }
        libraryTab.importPdfs(uris)
    }

    private val projectHtmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        libraryTab.importHtml(uri)
    }

    private val reviewQaExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(PDF.FILE_TYPE),
    ) { destinationUri ->
        val request = pendingReviewQaExport
        pendingReviewQaExport = null
        if (destinationUri != null && request != null) {
            exportReviewQaPdf(request, destinationUri)
        }
    }

    private val syncNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val pendingSync = pendingSyncAfterNotificationPermission
        pendingSyncAfterNotificationPermission = null
        pendingSync?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log("home onCreate")
        super.onCreate(savedInstanceState)
        pref = Preferences(PreferenceManager.getDefaultSharedPreferences(this))
        pendingReviewQaExport = savedInstanceState
            ?.getString(STATE_REVIEW_QA_EXPORT_PDF_STEM)
            ?.takeIf(String::isNotBlank)
            ?.let { pdfStem ->
                ReviewQaExportRequest(
                    pdfStem = pdfStem,
                    appendSourcePdf = savedInstanceState.getBoolean(
                        STATE_REVIEW_QA_EXPORT_APPEND_SOURCE,
                    ),
                )
            }

        val launchIntro = pref.getFirstInstall()
        if (launchIntro) {
            pref.setFirstInstall(false)
            // A new install sees the intro only; "What's new" is for upgrades.
            pref.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
            introLauncher.launch(Intent(this, MainIntroActivity::class.java))
        }

        if (pref.getHomeDisabled()) {
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(0, 0)
            finish()
            return
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pdfRepository = PdfRepository(AppDatabase.getInstance(applicationContext))
        coverCache = CoverCache.getInstance(applicationContext)
        historyPolicy = HistoryPolicy()
        historyCleaner = HistoryCleaner(
            pdfRepository,
            AnnotationJournal(applicationContext),
            coverCache,
            ReadingUsageService.getInstance(applicationContext),
        )
        permissionManager = PermissionManager(this) { onStorageAccessChanged() }
        commentCatalogue = CommentCatalogue(
            persistentCacheFile = File(filesDir, COMMENT_SNAPSHOT_CACHE),
            context = applicationContext,
        )
        sourcePdfIndex = SourceLinkedPdfIndex()
        phoneRenderQueue = PhoneRenderQueue(sourcePdfIndex = sourcePdfIndex)
        reviewStore = ReviewStore(this)
        syncOperationStore = SyncOperationStore(applicationContext)
        pendingChangesObserver = PendingChangesFileObserver(
            listOf(
                File(filesDir, "comment-delivery-outbox"),
                File(filesDir, "comment-sidecar-pending"),
                ReviewStore.defaultOutgoingEventsRoot(),
                ReviewStore.defaultOutgoingCardsRoot(),
                SourceEditQueue.defaultRequestDirectory(),
            ),
        )
        phoneRenderStatusHistory = PhoneRenderStatusHistory(applicationContext)
        libraryController = HomeLibraryController(pdfRepository, pref, commentCatalogue)

        recordOptionsDialog = RecordOptionsDialog(
            this,
            pdfRepository,
            coverCache,
            historyPolicy,
            historyCleaner,
            lifecycleScope,
            sourcePdfIndex,
            canShowSyncBuild = { item ->
                item.uri.scheme == "file" &&
                    item.uri.path?.let { phoneRenderQueue.canRequestPdf(File(it)) } == true
            },
            onShowSyncBuild = ::showSyncBuild,
            onChanged = ::refresh,
            onPendingChangesChanged = ::scheduleRenderStatusRefresh,
        )
        // Registered first so the comment list's own selection Back runs before this one.
        onBackPressedDispatcher.addCallback(this, commentIssuesBackCallback)
        // The Comments tab is gone; its list only opens as "Comment issues" from the Sync dialog.
        overviewTab = OverviewTabController(
            this,
            lifecycleScope,
            commentCatalogue,
            phoneRenderQueue,
            pref,
            showSearchAction = false,
            onOpenComment = ::openOverviewComment,
            onDataChanged = ::refresh,
            onPendingChangesChanged = ::scheduleRenderStatusRefresh,
            onHideSearch = ::hideHomeSearch,
            onShowSearch = ::showHomeSearch,
        )
        diaryTab = DiaryTabController(
            this,
            onPendingChangesChanged = ::scheduleRenderStatusRefresh,
            onHideSearch = ::hideHomeSearch,
        )
        abTestingTab = AbTestingTabController(this)
        notesTab = OverviewTabController(
            this,
            lifecycleScope,
            commentCatalogue,
            phoneRenderQueue,
            pref,
            notesOnly = true,
            onOpenComment = ::openOverviewComment,
            onDataChanged = ::refresh,
            onPendingChangesChanged = ::scheduleRenderStatusRefresh,
            onHideSearch = ::hideHomeSearch,
            onShowSearch = ::showHomeSearch,
        )
        libraryTab = LibraryTabController(
            this,
            pref,
            coverCache,
            lifecycleScope,
            this,
            selection = { selectionController.selectedHashes },
            libraryController = libraryController,
            hasFullAccess = { permissionManager.hasFullAccess() },
            onGrantAccessClicked = { permissionManager.requestFullAccess() },
            sourcePdfIndex = sourcePdfIndex,
            onImportDocuments = { projectPdfPicker.launch(arrayOf("application/pdf")) },
            onImportHtml = { projectHtmlPicker.launch(arrayOf("text/html")) },
        )
        observeLatestGlobalOperation()
        applyCachedFedoraStatus(renderStatusSnapshot)
        reviewTab = ReviewTabController(
            onStartReview = ::startPdfReview,
            onBrowseCards = { pdfStem ->
                startActivity(ReviewCardBrowserActivity.intent(this, pdfStem))
            },
            onBrowseUnavailable = {
                startActivity(ReviewCardBrowserActivity.intent(
                    this, initialFilter = com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardBrowserFilter.UNAVAILABLE,
                ))
            },
            onExportQaPdf = ::showReviewQaExport,
            onNavigationChanged = ::updateReviewBackState,
            onHideSearch = ::hideHomeSearch,
        )

        selectionController = HomeSelectionController(
            this,
            binding,
            currentItems = ::selectableItems,
            currentContext = ::selectionContext,
            onSelectionChanged = ::notifySelectionChanged,
            onStatusBatch = ::statusBatch,
            onHideBatch = ::hideBatch,
            onDeleteBatch = ::deleteBatch,
        )
        relocateController = RelocateController(
            this,
            pdfRepository,
            lifecycleScope,
            onOpen = ::openInReader,
            onHealed = ::refresh,
        )

        setupPager()
        handleCommentIssuesIntent(intent)
        handleNotesIntent(intent)
        setupSearch()
        if (savedInstanceState?.getBoolean(STATE_HOME_SEARCH_EXPANDED) == true) {
            binding.root.post { showHomeSearch() }
        }
        menuDialog = HomeMenuDialog(
            this,
            pref,
            currentTab = { currentTab() },
            onLibraryDetailsChanged = ::refresh,
            onShowStats = {
                lifecycleScope.launch {
                    val usageTracker = (application as App).usageTracker
                    val usageStats = usageTracker.snapshot()
                    val readingUsage = usageTracker.readingSnapshot()
                    if (!isFinishing && !isDestroyed) {
                        showLibraryStatsDialog(
                            this@HomeActivity,
                            allItems,
                            usageStats,
                            readingUsage,
                        )
                    }
                }
            },
            onSyncNow = ::requestManualSync,
            onShowOperations = ::showOperations,
        )
        onBackPressedDispatcher.addCallback(this, reviewBackCallback)
        onBackPressedDispatcher.addCallback(this, searchBackCallback)
        binding.homeSearchButton.setOnClickListener { showHomeSearch() }
        binding.homeMenuButton.setOnClickListener { menuDialog.show() }
        binding.syncStatusButton.setOnClickListener { showSyncMenu() }
        binding.openPdfFab.setOnClickListener {
            when (currentTab()) {
                HomeTab.NOTES -> notesTab.showActions(binding.openPdfFab)
                HomeTab.LIBRARY -> com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderSessionNavigation.open(this)
                HomeTab.DIARY, HomeTab.REVIEW, HomeTab.AB_TESTING -> Unit
            }
        }
        binding.addNoteFab.setOnClickListener { openQuickNote() }
        updateHomeFab()
        handleArtifactPromptIntent(intent)
        handleSyncTransferPromptIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    PendingChangesSignal.generation,
                    // The server's PDF status arrives beside a Sync, possibly after it ended.
                    com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSignal.generation,
                    AppDatabase.getInstance(applicationContext).thoughtsSyncDao().observeUndelivered(),
                ) { _, _, _ -> Unit }
                    .collectLatest {
                        delay(PENDING_CHANGES_DEBOUNCE_MS)
                        refreshRenderStatus()
                    }
            }
        }

        if (!launchIntro) {
            if (pref.getLastSeenVersionCode() < BuildConfig.VERSION_CODE) {
                pref.setShowFeaturesDialog(true)
                pref.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
            }
            showWhatsNewOnFirstRun()
        }
        handleRelocateIntent(intent)
        handleCitationMessage(intent)
    }

    private fun setupPager() {
        val tabs = visibleHomeTabs(AbTestingTabController.isConfigured)
        binding.homePager.adapter = HomeTabsAdapter(tabs) { tab, swipeRefresh ->
            val recyclerView = swipeRefresh.findViewById<androidx.recyclerview.widget.RecyclerView>(
                R.id.pageRecyclerView
            )
            when (tab) {
                HomeTab.NOTES -> notesTab.attach(swipeRefresh)
                HomeTab.DIARY -> diaryTab.attach(swipeRefresh)
                HomeTab.LIBRARY -> libraryTab.attach(recyclerView)
                HomeTab.REVIEW -> reviewTab.attach(swipeRefresh)
                HomeTab.AB_TESTING -> abTestingTab.attach(swipeRefresh)
            }
            setupPullToRefresh(tab, swipeRefresh)
        }
        binding.homePager.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        (binding.homePager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.layoutManager
            ?.isItemPrefetchEnabled = false

        TabLayoutMediator(binding.homeTabs, binding.homePager) { tab, position ->
            tab.setText(
                when (HomeTab.entries[position]) {
                    HomeTab.NOTES -> R.string.note_type_plural
                    HomeTab.DIARY -> R.string.home_tab_diary
                    HomeTab.LIBRARY -> R.string.home_tab_library
                    HomeTab.REVIEW -> R.string.home_tab_review
                    HomeTab.AB_TESTING -> R.string.home_tab_ab_testing
                }
            )
        }.attach()

        val initialTab = initialHomeTab(pref.getHomeTab(), tabs)
        binding.homePager.setCurrentItem(initialTab.ordinal, false)
        binding.homePager.isUserInputEnabled = initialTab != HomeTab.AB_TESTING
        binding.homePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pref.setHomeTab(HomeTab.entries[position])
                // The rating page has sliders and sentence marks, so a sideways drag stays in the page.
                binding.homePager.isUserInputEnabled = HomeTab.entries[position] != HomeTab.AB_TESTING
                if (HomeTab.entries[position] == HomeTab.AB_TESTING) abTestingTab.onSelected()
                selectionController.finish()
                notesTab.finishSelection()
                updateReviewBackState()
                updateHomeFab()
                renderSelectedTab()
                if (shouldRefreshHomeSearch(::searchResultsAdapter.isInitialized, isHomeSearchShowing())) {
                    updateSearchContext()
                }
            }
        })
    }

    private fun updateHomeFab() {
        val tab = currentTab()
        // The Diary's field is on the page itself, so it needs neither button.
        val covered = isHomeSearchShowing() || isCommentIssuesShowing()
        binding.addNoteFab.visibility = if (!covered && tab == HomeTab.NOTES) View.VISIBLE else View.GONE
        binding.openPdfFab.visibility = if (!covered && tab == HomeTab.LIBRARY) View.VISIBLE else View.GONE
        binding.openPdfFab.setImageResource(R.drawable.ic_elevated_pdf)
        binding.openPdfFab.contentDescription = getString(R.string.home_open_pdf_viewer)
        binding.openPdfFab.setOnLongClickListener(null)
        androidx.core.view.ViewCompat.removeAccessibilityAction(
            binding.openPdfFab,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK.id,
        )
        if (tab == HomeTab.LIBRARY) {
            binding.openPdfFab.setOnLongClickListener {
                showLibraryCreateMenu()
                true
            }
            androidx.core.view.ViewCompat.replaceAccessibilityAction(
                binding.openPdfFab,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                getString(R.string.library_add_project),
            ) { _, _ ->
                showLibraryCreateMenu()
                true
            }
        }
    }

    private fun openQuickNote() {
        startActivity(Intent(this, com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity::class.java))
    }

    private fun showLibraryCreateMenu() {
        PopupMenu(this, binding.openPdfFab).apply {
            menu.add(R.string.library_add_project).setOnMenuItemClickListener {
                libraryTab.addProject()
                true
            }
            show()
        }
    }

    private fun showOpenPdfMenu() {
        pdfPicker.launch(arrayOf(PDF.FILE_TYPE))
    }

    private fun updateReviewBackState() {
        reviewBackCallback.isEnabled =
            currentTab() == HomeTab.REVIEW && reviewTab.canGoBack()
    }

    private fun currentTab(): HomeTab = HomeTab.entries[binding.homePager.currentItem]

    private fun showWhatsNewOnFirstRun() {
        if (pref.getShowFeaturesDialog()) {
            lifecycleScope.launch {
                delay(500)
                if (!isFinishing) {
                    startActivity(Intent(this@HomeActivity, WhatsNewActivity::class.java))
                }
            }
            pref.setShowFeaturesDialog(false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_HOME_SEARCH_EXPANDED, isHomeSearchShowing())
        pendingReviewQaExport?.let { request ->
            outState.putString(STATE_REVIEW_QA_EXPORT_PDF_STEM, request.pdfStem)
            outState.putBoolean(
                STATE_REVIEW_QA_EXPORT_APPEND_SOURCE,
                request.appendSourcePdf,
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        pendingChangesObserverStartJob?.cancel()
        pendingChangesObserverStartJob = lifecycleScope.launch(Dispatchers.IO) {
            pendingChangesObserver.start()
        }
        restartSourceIndexObserver()
        ContextCompat.registerReceiver(
            this,
            syncFinishedReceiver,
            IntentFilter().apply {
                addAction(ThoughtsSyncScheduler.ACTION_FINISHED)
                addAction(SyncOperationStore.ACTION_STATE_CHANGED)
                addAction(ArtifactSyncCoordinator.ACTION_STATE_CHANGED)
                addAction(SyncTransferCoordinator.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        syncFinishedReceiverRegistered = true
        applyCachedFedoraStatus(renderStatusSnapshot)
        refreshOpenSyncBuild()
        commentCatalogue.invalidate()
        commentSourcesValidated = false
        logicalCommentObserver?.cancel()
        logicalCommentObserver = lifecycleScope.launch {
            // onStart already invalidated and onResume refreshes, so only later changes matter.
            commentCatalogue.changes(emitInitialState = false).collect {
                commentSourcesValidated = false
                commentRefreshJob?.cancel()
                commentRefreshJob = lifecycleScope.launch {
                    delay(COMMENT_REFRESH_DEBOUNCE_MS)
                    refresh()
                }
            }
        }
        startRenderReceiptObserver()
        startRenderStatusPolling()
        scheduleSyncOperationReconciliation()
    }

    override fun onStop() {
        pendingChangesObserverStartJob?.cancel()
        pendingChangesObserverStartJob = null
        commentRefreshJob?.cancel()
        commentValidationJob?.cancel()
        commentValidationJob = null
        logicalCommentObserver?.cancel()
        logicalCommentObserver = null
        commentObserver?.stopWatching()
        commentObserver = null
        renderReceiptJob?.cancel()
        renderReceiptObserver?.stopWatching()
        renderReceiptObserver = null
        renderStatusPollJob?.cancel()
        renderStatusPollJob = null
        syncElapsedRefreshJob?.cancel()
        syncElapsedRefreshJob = null
        syncStatusButtonAnimator?.cancel()
        syncStatusButtonAnimator = null
        syncBuildRefreshJob?.cancel()
        syncBuildRefreshJob = null
        syncOperationReconciliationJob?.cancel()
        syncOperationReconciliationJob = null
        sourceIndexObserver?.stopWatching()
        sourceIndexObserver = null
        if (syncFinishedReceiverRegistered) {
            unregisterReceiver(syncFinishedReceiver)
            syncFinishedReceiverRegistered = false
        }
        sourceIndexRefreshJob?.cancel()
        sourceIndexRefreshJob = null
        overviewSourceLoadJob?.cancel()
        overviewSourceLoadJob = null
        reviewSummaryLoadJob?.cancel()
        reviewSummaryLoadJob = null
        reviewDueRefreshJob?.cancel()
        reviewDueRefreshJob = null
        pendingChangesObserver.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommentIssuesIntent(intent)
        handleNotesIntent(intent)
        handleRelocateIntent(intent)
        handleCitationMessage(intent)
        handleArtifactPromptIntent(intent)
        handleSyncTransferPromptIntent(intent)
        reopenBackgroundedNote(intent)
    }

    /** Reopens a note whose editor the launcher icon cleared from this singleTask task. */
    private fun reopenBackgroundedNote(intent: Intent) {
        if (intent.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return
        val draftId = com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity.backgroundedDraftId ?: return
        com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity.backgroundedDraftId = null
        if (com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDrafts.unfinished(this).none { it.id == draftId }) return
        startActivity(Intent(this, com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity::class.java)
            .putExtra(com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity.EXTRA_DRAFT_ID, draftId))
    }

    private fun handleCommentIssuesIntent(intent: Intent) {
        if (!intent.getBooleanExtra("showCommentIssues", false)) return
        intent.removeExtra("showCommentIssues")
        showCommentIssues()
    }

    private fun isCommentIssuesShowing(): Boolean = binding.commentIssuesPanel.visibility == View.VISIBLE

    /** The comments that need attention, over Home; Back returns to the tab underneath. */
    private fun showCommentIssues() {
        hideHomeSearch()
        if (commentIssuesPage == null) {
            val page = layoutInflater.inflate(R.layout.page_home_overview, binding.commentIssuesContainer, false) as SwipeRefreshLayout
            binding.commentIssuesContainer.addView(page)
            page.isEnabled = false
            commentIssuesPage = page
            binding.commentIssuesToolbar.setNavigationOnClickListener { hideCommentIssues() }
            binding.commentIssuesToolbar.menu.add(0, R.id.commentIssuesActions, 0, R.string.overview_actions).apply {
                setIcon(R.drawable.ic_more_horizontal)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            binding.commentIssuesToolbar.setOnMenuItemClickListener {
                overviewTab.showActions(binding.commentIssuesToolbar.findViewById(R.id.commentIssuesActions) ?: binding.commentIssuesToolbar)
                true
            }
            overviewTab.attach(page)
        }
        binding.commentIssuesPanel.visibility = View.VISIBLE
        commentIssuesBackCallback.isEnabled = true
        commentIssuesNeedRender = true
        renderCommentIssues()
        overviewTab.showCommentIssues()
        updateHomeFab()
    }

    private fun hideCommentIssues() {
        if (!isCommentIssuesShowing()) return
        overviewTab.finishSelection()
        binding.commentIssuesPanel.visibility = View.GONE
        commentIssuesBackCallback.isEnabled = false
        updateHomeFab()
    }

    private fun renderCommentIssues() {
        if (!homeDataLoaded || !isCommentIssuesShowing() || !commentIssuesNeedRender) return
        renderCommentList(overviewTab)
        commentIssuesNeedRender = false
    }

    /** Comment data changed: the comment-backed tabs and the Comment issues list render again. */
    private fun markCommentTabsDirty(vararg others: HomeTab) {
        tabRenderTracker.markDirty(HomeTab.NOTES, HomeTab.DIARY, *others)
        commentIssuesNeedRender = true
    }

    private fun handleNotesIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_SHOW_NOTES, false)) return
        intent.removeExtra(EXTRA_SHOW_NOTES)
        binding.homePager.setCurrentItem(HomeTab.NOTES.ordinal, false)
        notesTab.showNotes()
    }

    private fun handleRelocateIntent(intent: Intent) {
        val relocateHash = intent.getStringExtra(EXTRA_RELOCATE_HASH) ?: return
        intent.removeExtra(EXTRA_RELOCATE_HASH)
        relocateController.handleMissingFile(relocateHash)
    }

    private fun handleCitationMessage(intent: Intent) {
        val messageRes = intent.getIntExtra(EXTRA_CITATION_MESSAGE, 0)
        intent.removeExtra(EXTRA_CITATION_MESSAGE)
        if (messageRes != 0) {
            binding.root.post {
                AppSnackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatterySetup.offerOnFirstUse(this)
    }

    override fun onResume() {
        super.onResume()
        refreshSourceMergeFiles()
        com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatterySetup.offerOnFirstUse(this)
        sourcePdfIndex.invalidate()
        permissionManager.recheck()
        libraryTab.applyProgressStyle()
        libraryTab.applyTitleStyle()
        searchResultsAdapter.applyTitleStyle()
        if (::notesTab.isInitialized) notesTab.refreshCalendar()
        refresh()
    }

    private fun setupSearch() {
        searchResultsAdapter =
            LibraryAdapter(coverCache, lifecycleScope, this, pref) { selectionController.selectedHashes }
        globalSearch = HomeGlobalSearch(
            this,
            lifecycleScope,
            globalSearchAdapter,
            documents = { allItems },
            comments = overviewTab,
            todos = notesTab,
            diary = diaryTab,
            openDocument = ::openGlobalSearchDocument,
            openPage = ::openGlobalSearchPage,
            openCard = { id ->
                startActivity(com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardDetailsActivity.intent(this, id))
            },
            onOpen = {},
        )
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecyclerView.adapter = globalSearchAdapter

        binding.homeSearchBackButton.setOnClickListener { hideHomeSearch() }
        binding.homeSearchClearButton.setOnClickListener { binding.homeSearchInput.text?.clear() }
        binding.homeSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                binding.homeSearchInput.clearFocus()
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(binding.homeSearchInput.windowToken, 0)
                true
            } else false
        }
        binding.homeSearchInput.doOnTextChanged { text, _, _, _ ->
            binding.homeSearchClearButton.visibility =
                if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            submitSearchResults()
        }
        updateSearchContext()
    }

    private fun isHomeSearchShowing(): Boolean = binding.searchResultsPanel.visibility == View.VISIBLE

    private fun showHomeSearch() {
        if (isHomeSearchShowing()) return
        binding.homeDefaultActions.visibility = View.GONE
        binding.homeExpandedSearch.visibility = View.VISIBLE
        binding.searchResultsPanel.visibility = View.VISIBLE
        searchBackCallback.isEnabled = true
        updateSearchContext()
        updateHomeFab()
        binding.homeSearchInput.requestFocus()
        binding.homeSearchInput.post {
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(binding.homeSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideHomeSearch() {
        if (!isHomeSearchShowing()) return
        selectionController.finish()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.homeSearchInput.windowToken, 0)
        binding.homeSearchInput.text?.clear()
        binding.homeSearchInput.clearFocus()
        binding.searchResultsPanel.visibility = View.GONE
        binding.homeExpandedSearch.visibility = View.GONE
        binding.homeDefaultActions.visibility = View.VISIBLE
        searchBackCallback.isEnabled = false
        updateHomeFab()
    }

    private fun submitSearchResults() {
        globalSearch.setQuery(binding.homeSearchInput.text?.toString().orEmpty())
    }

    private fun updateSearchContext() {
        // One search for every tab: names, PDF text, cards, comments and to-dos.
        binding.homeSearchInput.hint = getString(R.string.global_search_hint)
        if (binding.searchResultsRecyclerView.adapter !== globalSearchAdapter) {
            binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
            binding.searchResultsRecyclerView.adapter = globalSearchAdapter
        }
        if (isHomeSearchShowing()) {
            renderCommentTabsForSearch()
            globalSearch.reloadCards()
            globalSearch.refreshIndex()
        }
        submitSearchResults()
    }

    /** Comments, to-dos and the diary load per tab; the search needs them all even when no tab was shown. */
    private fun renderCommentTabsForSearch() {
        if (!homeDataLoaded) return
        renderCommentList(overviewTab)
        val tab = currentTab()
        if (tab != HomeTab.NOTES && tabRenderTracker.needsRender(HomeTab.NOTES)) {
            renderCommentList(notesTab)
            tabRenderTracker.markRendered(HomeTab.NOTES)
        }
        if (tab != HomeTab.DIARY && tabRenderTracker.needsRender(HomeTab.DIARY)) {
            diaryTab.render(overviewSourceSnapshot.logicalComments, sourceLoaded = overviewSourceSnapshotLoaded)
            tabRenderTracker.markRendered(HomeTab.DIARY)
        }
    }

    /** Reads new or changed PDFs into the search index once Home has settled. */
    private fun scheduleGlobalSearchIndex() {
        globalSearchIndexJob?.cancel()
        globalSearchIndexJob = lifecycleScope.launch {
            delay(GLOBAL_SEARCH_INDEX_DELAY_MS)
            globalSearch.refreshIndex()
        }
    }

    private fun openGlobalSearchDocument(item: HomeItem) {
        if (item.available) openInReader(item.uri, item.hash.takeUnless { item.isScanOnly }) else onItemClicked(item)
    }

    private fun openGlobalSearchPage(item: HomeItem, pageIndex: Int, highlight: String?) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            data = item.uri
            putExtra(EXTRA_FROM_HOME, true)
            if (!item.isScanOnly) putExtra(EXTRA_RECORD_HASH, item.hash)
            putExtra(com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardReaderContract.EXTRA_PAGE_INDEX, pageIndex)
            highlight?.let { putExtra(com.gitlab.mudlej.MjPdfReader.ui.reader.EXTRA_SEARCH_HIGHLIGHT, it) }
        })
    }

    private fun onStorageAccessChanged() {
        if (permissionManager.hasFullAccess()) {
            startRenderReceiptObserver()
        } else {
            renderReceiptJob?.cancel()
            renderReceiptObserver?.stopWatching()
            renderReceiptObserver = null
        }
        libraryTab.onCoversChanged()
        searchResultsAdapter.notifyDataSetChanged()
        refresh()
    }

    private fun refresh(onComplete: (() -> Unit)? = null) {
        if (Log.isLoggable(STARTUP_TAG, Log.INFO)) {
            // Name the caller so repeated startup refreshes can be traced to their trigger.
            val callers = Throwable().stackTrace.drop(1).filterNot { it.methodName.startsWith("refresh") }.take(3)
            com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log("home refresh requested by " +
                callers.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
        }
        onComplete?.let(refreshCompletionCallbacks::add)
        refreshQueued = true
        if (refreshLoopJob?.isActive == true) {
            return
        }
        refreshLoopJob = lifecycleScope.launch {
            try {
                do {
                    delay(REFRESH_COALESCE_MS)
                    refreshQueued = false
                    try {
                        renderTabs()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.e(TAG, "Unable to refresh the home catalogue", error)
                    }
                } while (refreshQueued)
            } finally {
                refreshLoopJob = null
                val callbacks = refreshCompletionCallbacks.toList()
                refreshCompletionCallbacks.clear()
                callbacks.forEach { it() }
                if (refreshQueued) {
                    refresh()
                }
            }
        }
    }

    private suspend fun renderTabs() {
        val startedAt = SystemClock.elapsedRealtime()
        val probe = AvailabilityProbe(applicationContext, permissionManager.hasFullAccess())
        val sourceSnapshot = withContext(Dispatchers.IO) { sourcePdfIndex.snapshot() }
        sourceIndexSnapshot = sourceSnapshot
        // Review depends on canonical PDF identity, not the all-document comment catalogue.
        scheduleReviewSummaryLoad(sourceSnapshot)
        if (currentTab() == HomeTab.REVIEW) reviewSummaryLoadJob?.join()
        val libraryData = libraryController.loadLibraryData(
            probe,
            getString(R.string.home_title_annotated),
        )
        pendingLibraryRemovalFilenames = withContext(Dispatchers.IO) {
            // The Library draws its Projects on the main thread: read their catalogue here, so
            // that drawing finds it parsed instead of reading and parsing the state file there.
            LibraryProjectStore(applicationContext).warm()
            LibraryRemovalStore(applicationContext).matchingFilenames(
                sourceSnapshot.libraryPdfSha256ByFilename,
            )
        }
        allRecordItems = libraryData.items.filterNot(::isPendingLibraryRemoval)
        allItems = allRecordItems.filter { it.availability != Availability.MISSING }
        overviewDocuments = libraryData.comments.overviewDocuments.filterNot { document ->
            "${document.stem}.pdf" in pendingLibraryRemovalFilenames
        }
        if (!overviewSourceSnapshotLoaded) {
            // Show the catalogue's notes and comments now; the full snapshot adds proposals and geometry.
            libraryData.comments.logicalComments?.let { comments ->
                overviewSourceSnapshot = OfflineOverviewSourceSnapshot(logicalComments = comments)
                overviewSourceSnapshotLoaded = true
            }
        }
        sourceIndexSnapshot = sourceSnapshot
        homeDataLoaded = true
        markCommentTabsDirty(HomeTab.LIBRARY)
        renderSelectedTab()
        binding.homePager.doOnPreDraw { com.gitlab.mudlej.MjPdfReader.data.StartupReadiness.ready() }
        if (isHomeSearchShowing()) {
            renderCommentTabsForSearch()
            submitSearchResults()
        }
        scheduleGlobalSearchIndex()
        Log.d(
            TAG,
            "Refreshed home data and selected tab in " +
                "${SystemClock.elapsedRealtime() - startedAt} ms",
        )
        com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log(
            "home rendered ${currentTab()} (${allItems.size} documents, ${overviewDocuments.size} with comments) " +
                "after ${SystemClock.elapsedRealtime() - startedAt} ms")
        scheduleOverviewSourceLoad(sourceSnapshot)
        validateCommentSources(libraryData.comments)
    }

    private fun scheduleDeferredTabDataLoad(sourceSnapshot: SourceLinkedPdfIndexSnapshot) {
        scheduleReviewSummaryLoad(sourceSnapshot)
        scheduleOverviewSourceLoad(sourceSnapshot)
    }

    private fun scheduleReviewSummaryLoad(sourceSnapshot: SourceLinkedPdfIndexSnapshot) {
        reviewSummaryLoadGeneration += 1
        pendingReviewSourceIndexSnapshot = sourceSnapshot
        reviewDueRefreshJob?.cancel()
        reviewDueRefreshJob = null
        reviewTab.beginLoad()
        if (reviewSummaryLoadJob?.isActive == true) {
            return
        }
        reviewSummaryLoadJob = lifecycleScope.launch {
            try {
                while (isActive) {
                    val generation = reviewSummaryLoadGeneration
                    val requestedSourceSnapshot = pendingReviewSourceIndexSnapshot
                    val startedAt = SystemClock.elapsedRealtime()
                    try {
                        val currentReviewSummary = withContext(Dispatchers.IO) {
                            reviewStore.summary(
                                canonicalPdfSha256ByFilename = canonicalReviewPdfHashes(
                                    requestedSourceSnapshot,
                                ),
                            )
                        }
                        if (generation != reviewSummaryLoadGeneration) {
                            continue
                        }
                        reviewTab.render(currentReviewSummary)
                        scheduleReviewDueRefresh(currentReviewSummary, generation)
                        if (currentReviewSummary.markerRemovalPendingCount > 0) {
                            // A removal skipped during Sync gets another chance once Sync has finished.
                            val context = applicationContext
                            lifecycleScope.launch(Dispatchers.IO) {
                                PendingMarkerRemovalRetry.run(context, PendingMarkerRemovalRetry.REFRESH_INTERVAL_MILLIS)
                            }
                        }
                        Log.d(
                            TAG,
                            "Refreshed Review data in " +
                                "${SystemClock.elapsedRealtime() - startedAt} ms",
                        )
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (generation != reviewSummaryLoadGeneration) {
                            continue
                        }
                        reviewTab.renderLoadError()
                        Log.e(TAG, "Unable to refresh Review data", error)
                        break
                    }
                }
            } finally {
                if (reviewSummaryLoadJob === coroutineContext[Job]) {
                    reviewSummaryLoadJob = null
                }
            }
        }
    }

    private fun scheduleReviewDueRefresh(
        summary: ReviewLibrarySummary,
        summaryGeneration: Long,
    ) {
        reviewDueRefreshJob?.cancel()
        val delayMillis = reviewDueRefreshDelayMillis(
            summary.nextDueAt,
            System.currentTimeMillis(),
        ) ?: run {
            reviewDueRefreshJob = null
            return
        }
        reviewDueRefreshJob = lifecycleScope.launch {
            delay(delayMillis)
            if (summaryGeneration != reviewSummaryLoadGeneration) {
                return@launch
            }
            reviewDueRefreshJob = null
            scheduleReviewSummaryLoad(sourceIndexSnapshot)
        }
    }

    private fun scheduleOverviewSourceLoad(sourceSnapshot: SourceLinkedPdfIndexSnapshot) {
        overviewSourceLoadGeneration += 1
        pendingOverviewSourceIndexSnapshot = sourceSnapshot
        if (overviewSourceLoadJob?.isActive == true) {
            return
        }
        overviewSourceLoadJob = lifecycleScope.launch {
            try {
                while (isActive) {
                    val generation = overviewSourceLoadGeneration
                    while (
                        generation == overviewSourceLoadGeneration &&
                        currentTab() == HomeTab.REVIEW &&
                        reviewSummaryLoadJob?.isActive == true
                    ) {
                        delay(REVIEW_LOAD_PRIORITY_POLL_MS)
                    }
                    if (generation != overviewSourceLoadGeneration) {
                        continue
                    }
                    val startedAt = SystemClock.elapsedRealtime()
                    com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log("full comments snapshot load started (generation $generation)")
                    try {
                        val currentOverviewSourceSnapshot = withContext(Dispatchers.IO) {
                            overviewTab.loadSourceSnapshot()
                        }
                        com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log(
                            "full comments snapshot loaded in ${SystemClock.elapsedRealtime() - startedAt} ms (generation $generation, now $overviewSourceLoadGeneration)")
                        // A newer request only means a newer load follows; show this result meanwhile.
                        overviewSourceSnapshot = currentOverviewSourceSnapshot
                        overviewSourceSnapshotLoaded = true
                        overviewSourceSnapshotComplete = true
                        markCommentTabsDirty()
                        renderSelectedTab()
                        if (isHomeSearchShowing()) {
                            renderCommentTabsForSearch()
                            submitSearchResults()
                        }
                        if (generation != overviewSourceLoadGeneration) {
                            continue
                        }
                        Log.d(
                            TAG,
                            "Refreshed deferred Comments data in " +
                                "${SystemClock.elapsedRealtime() - startedAt} ms",
                        )
                        com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log(
                            "full comments snapshot shown after ${SystemClock.elapsedRealtime() - startedAt} ms")
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (generation != overviewSourceLoadGeneration) {
                            continue
                        }
                        Log.e(TAG, "Unable to refresh deferred Comments data", error)
                        // Nothing more is coming; stop showing Comments as loading.
                        if (!overviewSourceSnapshotComplete) {
                            overviewSourceSnapshotComplete = true
                            markCommentTabsDirty()
                            renderSelectedTab()
                        }
                        break
                    }
                }
            } finally {
                if (overviewSourceLoadJob === coroutineContext[Job]) {
                    overviewSourceLoadJob = null
                }
            }
        }
    }

    private fun validateCommentSources(displayedSnapshot: CommentCatalogueSnapshot) {
        if (commentSourcesValidated || commentValidationJob?.isActive == true) {
            return
        }
        commentValidationJob = lifecycleScope.launch {
            val validated = commentCatalogue.loadSnapshot()
            commentSourcesValidated = true
            if (validated !== displayedSnapshot) {
                refresh()
            }
        }
    }

    private fun renderSelectedTab() {
        if (!homeDataLoaded || !::binding.isInitialized) {
            return
        }
        renderCommentIssues()
        val tab = currentTab()
        if (!tabRenderTracker.needsRender(tab)) {
            return
        }
        when (tab) {
            HomeTab.NOTES -> renderCommentList(notesTab)
            HomeTab.DIARY -> diaryTab.render(
                overviewSourceSnapshot.logicalComments,
                sourceLoaded = overviewSourceSnapshotLoaded,
            )
            HomeTab.LIBRARY -> {
                libraryTab.render(
                    allItems,
                    sourceIndexSnapshot,
                    pendingLibraryRemovalFilenames,
                )
                logFirstLibraryRender()
            }
            HomeTab.REVIEW -> reviewTab.renderCurrentState()
            HomeTab.AB_TESTING -> Unit
        }
        tabRenderTracker.markRendered(tab)
    }

    private fun renderCommentList(controller: OverviewTabController) {
        val commentDestinations = overviewCommentDestinationEntries(
            sourceIndexSnapshot,
            PhoneRenderQueue.defaultCanonicalPdfDirectory(),
        ).filterNot { destination ->
            destination.entry.path.let(::File).name in pendingLibraryRemovalFilenames
        }.map { destination ->
            val item = HomeItem.fromScan(destination.entry).copy(
                libraryDisplayName = destination.label,
                sortKey = destination.label.lowercase(),
            )
            OverviewCommentDestination(item, destination.kind, destination.label)
        }
        controller.render(
            overviewDocuments,
            sourceIndexSnapshot,
            commentDestinations,
            overviewSourceSnapshot,
            sourceLoaded = overviewSourceSnapshotLoaded,
            sourceComplete = overviewSourceSnapshotComplete,
        )
    }

    private fun isPendingLibraryRemoval(item: HomeItem): Boolean {
        val file = item.uri.takeIf { it.scheme == "file" }?.path?.let(::File) ?: return false
        return isPendingLibraryRemoval(file)
    }

    private fun isPendingLibraryRemoval(file: File): Boolean {
        if (file.name !in pendingLibraryRemovalFilenames) return false
        val canonicalRoot = runCatching {
            PhoneRenderQueue.defaultCanonicalPdfDirectory().canonicalFile
        }.getOrElse { PhoneRenderQueue.defaultCanonicalPdfDirectory().absoluteFile }
        val parent = runCatching { file.canonicalFile.parentFile }
            .getOrElse { file.absoluteFile.parentFile }
        return parent == canonicalRoot
    }

    private fun refreshSourceIndex() {
        sourceIndexRefreshJob?.cancel()
        sourceIndexRefreshJob = lifecycleScope.launch {
            val currentSourceSnapshot = withContext(Dispatchers.IO) {
                sourcePdfIndex.snapshot()
            }
            sourceIndexSnapshot = currentSourceSnapshot
            markCommentTabsDirty(HomeTab.LIBRARY)
            renderSelectedTab()
            scheduleDeferredTabDataLoad(currentSourceSnapshot)
        }
    }

    private fun restartSourceIndexObserver() {
        sourceIndexObserver?.stopWatching()
        sourceIndexObserver = sourcePdfIndex.observe {
            runOnUiThread { refreshSourceIndex() }
        }
    }

    private fun logFirstLibraryRender() {
        if (!firstLibrarySubmissionLogged) {
            firstLibrarySubmissionLogged = true
            Log.d(
                TAG,
                "Submitted first complete Library list after " +
                    "${SystemClock.elapsedRealtime() - homeOpenedAt} ms",
            )
        }
        if (!firstLibraryLayoutScheduled) {
            firstLibraryLayoutScheduled = true
            binding.homePager.doOnPreDraw {
                Log.d(
                    TAG,
                    "Library first frame ready after " +
                        "${SystemClock.elapsedRealtime() - homeOpenedAt} ms",
                )
            }
        }
    }

    private fun setupPullToRefresh(tab: HomeTab, swipeRefresh: SwipeRefreshLayout) {
        swipeRefresh.setColorSchemeColors(
            MaterialColors.getColor(swipeRefresh, androidx.appcompat.R.attr.colorPrimary, 0)
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            MaterialColors.getColor(swipeRefresh, com.google.android.material.R.attr.colorSurface, 0)
        )
        swipeRefresh.setOnRefreshListener {
            when (pullToRefreshAction(tab)) {
                PullToRefreshAction.SYNC -> {
                    requestManualSync {
                        swipeRefresh.isRefreshing = false
                    }
                }
                PullToRefreshAction.RELOAD_PAGE -> abTestingTab.reload {
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun openInReader(uri: Uri, hash: String? = null) {
        Intent(this, MainActivity::class.java).also { intent ->
            intent.data = uri
            intent.putExtra(EXTRA_FROM_HOME, true)
            hash?.let { intent.putExtra(EXTRA_RECORD_HASH, it) }
            startActivity(intent)
        }
    }

    private fun startPdfReview(pdfStem: String? = null) {
        lifecycleScope.launch {
            val card = withContext(Dispatchers.IO) {
                reviewStore.dueCards(
                    pdfStem = pdfStem,
                    canonicalPdfSha256ByFilename = canonicalReviewPdfHashes(sourceIndexSnapshot),
                ).firstOrNull()
            }
            if (card == null) {
                AppSnackbar.make(binding.root, R.string.review_none_due, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val file = reviewStore.reviewPdfFile(card)
            if (file == null || !file.isFile) {
                AppSnackbar.make(binding.root, R.string.review_missing_pdf, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            Intent(this@HomeActivity, MainActivity::class.java).also { reviewIntent ->
                reviewIntent.data = Uri.fromFile(file)
                reviewIntent.putExtra(EXTRA_FROM_HOME, true)
                reviewIntent.putExtra(EXTRA_REVIEW_CARD_ID, card.id)
                pdfStem?.let { reviewIntent.putExtra(EXTRA_REVIEW_PDF_STEM, it) }
                startActivity(reviewIntent)
            }
        }
    }

    private fun canonicalReviewPdfHashes(
        snapshot: com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot,
    ): Map<String, String>? = if (snapshot.libraryAvailable && snapshot.generationCurrent) {
        snapshot.libraryPdfSha256ByFilename
    } else {
        null
    }

    private fun showReviewQaExport(pdfStem: String) {
        val dialogBinding = DialogReviewQaExportBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_qa_export_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.review_qa_export_action) { _, _ ->
                ReviewQaExportRequest(
                    pdfStem = pdfStem,
                    appendSourcePdf = dialogBinding.reviewQaAppendSource.isChecked,
                ).let { request ->
                    pendingReviewQaExport = request
                    reviewQaExportLauncher.launch(reviewQaExportFileName(request))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportReviewQaPdf(
        request: ReviewQaExportRequest,
        destinationUri: Uri,
    ) {
        val progress = AppSnackbar.make(
            binding.root,
            R.string.review_qa_exporting,
            Snackbar.LENGTH_INDEFINITE,
        )
        progress.show()
        lifecycleScope.launch {
            val result = runCatching {
                ReviewQaPdfExporter(
                    context = applicationContext,
                    reviewStore = reviewStore,
                    sourceIndex = sourcePdfIndex,
                ).export(request, destinationUri)
            }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            progress.dismiss()
            result.onSuccess { exported ->
                AppSnackbar.make(
                    binding.root,
                    getString(R.string.review_qa_export_complete, exported.cardCount),
                    Snackbar.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                Log.e(TAG, "Could not export review questions and answers", error)
                val detail = error.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    .orEmpty()
                    .ifBlank { error.javaClass.simpleName }
                AppSnackbar.make(
                    binding.root,
                    getString(R.string.review_qa_export_failed, detail),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openOverviewComment(item: HomeItem, comment: com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment) {
        Intent(this, MainActivity::class.java).also { intent ->
            intent.data = item.uri
            intent.putExtra(EXTRA_FROM_HOME, true)
            if (!item.isScanOnly) intent.putExtra(EXTRA_RECORD_HASH, item.hash)
            intent.putExtra(EXTRA_COMMENT_ID, comment.id)
            intent.putExtra(EXTRA_COMMENT_PAGE, comment.pageIndex)
            comment.logicalLocations.firstPrimaryOrNull()?.takeIf {
                comment.logicalPayloadJson.isNotBlank()
            }?.let { location ->
                intent.putExtra(EXTRA_LOGICAL_COMMENT_STATUS, location.status)
                intent.putExtra(EXTRA_LOGICAL_COMMENT_POINT, location.point.toFloatArray())
                intent.putExtra(EXTRA_LOGICAL_COMMENT_REGIONS, location.regions.flatten().toFloatArray())
                intent.putExtra(EXTRA_LOGICAL_COMMENT_CONTENTS, comment.contents)
            }
            startActivity(intent)
        }
    }

    override fun onProjectDocumentClicked(item: HomeItem, pageIndex: Int, documentId: String?, displayTitle: String?, projectId: String?) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            data = item.uri
            putExtra(EXTRA_FROM_HOME, true)
            putExtra(com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardReaderContract.EXTRA_PAGE_INDEX, pageIndex)
            putExtra(com.gitlab.mudlej.MjPdfReader.ui.reader.EXTRA_DISPLAY_DOCUMENT_ID, documentId)
            putExtra(com.gitlab.mudlej.MjPdfReader.ui.reader.EXTRA_DISPLAY_PROJECT_ID, projectId)
            putExtra(com.gitlab.mudlej.MjPdfReader.ui.reader.EXTRA_DISPLAY_TITLE, displayTitle)
        })
    }

    override fun onItemClicked(item: HomeItem) {
        if (selectionController.active) {
            selectionController.toggle(item)
            return
        }
        if (isHomeSearchShowing()) {
            hideHomeSearch()
        }
        if (!item.available) {
            if (item.availability == Availability.LOCKED) {
                permissionManager.requestFullAccess()
            } else {
                relocateController.handleMissingFile(item.hash)
            }
            return
        }
        if (isMissingFile(item)) {
            relocateController.handleMissingFile(item.hash)
            return
        }
        openInReader(item.uri, item.hash.takeUnless { item.isScanOnly })
    }

    private fun isMissingFile(item: HomeItem): Boolean {
        if (item.isScanOnly || item.uri.scheme != "file") {
            return false
        }
        val path = item.uri.path ?: return false
        return !File(path).canRead()
    }

    override fun onItemLongClicked(item: HomeItem): Boolean {
        return selectionController.begin(item)
    }

    private fun selectableItems(): List<HomeItem> {
        if (isHomeSearchShowing()) {
            return searchResultsAdapter.currentList
        }
        return when (currentTab()) {
            HomeTab.DIARY, HomeTab.NOTES -> emptyList()
            HomeTab.LIBRARY -> libraryTab.currentGridItems()
            HomeTab.REVIEW, HomeTab.AB_TESTING -> emptyList()
        }
    }

    private fun selectionContext(): SelectionContext {
        if (isHomeSearchShowing()) {
            return SelectionContext.SEARCH
        }
        return when (currentTab()) {
            HomeTab.DIARY, HomeTab.NOTES, HomeTab.AB_TESTING -> SelectionContext.OVERVIEW
            HomeTab.LIBRARY -> SelectionContext.LIBRARY
            HomeTab.REVIEW -> SelectionContext.REVIEW
        }
    }

    private fun notifySelectionChanged() {
        libraryTab.notifySelectionChanged()
        searchResultsAdapter.notifySelectionChanged()
    }

    override fun onItemOptionsClicked(item: HomeItem) {
        recordOptionsDialog.show(item)
    }

    override fun onRenderStatusClicked(status: PhoneRenderJobStatus) {
        showRenderStatusDetails(status)
    }

    private fun showSyncBuild(item: HomeItem) {
        val file = item.uri.takeIf { it.scheme == "file" }?.path?.let(::File) ?: return
        val target = file.name
        lifecycleScope.launch {
            val state = loadSyncBuildDialogState(applicationContext, target, item.uri)
            if (isFinishing || isDestroyed || !phoneRenderQueue.canRequestPdf(file)) return@launch
            syncBuildDialog?.dismiss()
            lateinit var statusDialog: SyncBuildDialog
            statusDialog = SyncBuildDialog(
                activity = this@HomeActivity,
                target = target,
                onSync = { onQueued -> requestManualSync(onQueued) },
                onShowLastLog = { SyncOperationsDialog(this@HomeActivity).showLatest(target = null) },
                onRefreshRequested = ::refreshOpenSyncBuild,
                onDismissed = {
                    if (syncBuildDialog === statusDialog) {
                        syncBuildRefreshJob?.cancel()
                        syncBuildRefreshJob = null
                        syncBuildDialog = null
                        syncBuildUri = null
                    }
                },
            )
            syncBuildUri = item.uri
            syncBuildDialog = statusDialog
            statusDialog.show(state)
        }
    }

    private fun refreshOpenSyncBuild() {
        val statusDialog = syncBuildDialog?.takeIf(SyncBuildDialog::isShowing) ?: return
        val uri = syncBuildUri ?: run {
            statusDialog.dismiss()
            return
        }
        val target = statusDialog.target
        syncBuildRefreshJob?.cancel()
        syncBuildRefreshJob = lifecycleScope.launch {
            val state = loadSyncBuildDialogState(applicationContext, target, uri)
            if (syncBuildDialog === statusDialog && syncBuildUri == uri) {
                statusDialog.update(state)
            }
        }
    }

    private fun dispatchQueuedRenderRequest(
        targetPdfFilename: String? = null,
        onQueued: (Boolean) -> Unit = {},
    ) {
        scheduleRenderStatusRefresh()
        if (ThoughtsServerSecretStore(this).config() == null) {
            showServerConfigurationRequired(R.string.home_rebuild_server_not_configured)
            onQueued(false)
            return
        }
        runAfterSyncNotificationPermission {
            startQueuedRenderRequest(targetPdfFilename, onQueued)
        }
    }

    private fun startQueuedRenderRequest(
        targetPdfFilename: String?,
        onQueued: (Boolean) -> Unit,
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CommentDeliveryScheduler.schedule(
                        applicationContext,
                        explicit = true,
                        targetPdfFilename = targetPdfFilename,
                    )
                    ThoughtsSyncScheduler.enqueue(
                        applicationContext,
                        targetPdfFilename,
                        building = true,
                    )
                }
            }
            result.onSuccess { onQueued(true) }
                .onFailure {
                    AppSnackbar.make(
                        binding.root,
                        R.string.home_rebuild_sync_start_failed,
                        Snackbar.LENGTH_LONG,
                    ).show()
                    onQueued(false)
                }
        }
    }

    private fun requestManualSync(
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (ThoughtsServerSecretStore(this).config() == null) {
            showServerConfigurationRequired(R.string.home_sync_server_not_configured)
            onComplete(false)
            return
        }
        runAfterSyncNotificationPermission {
            startManualSync(onComplete)
        }
    }

    private fun startManualSync(onComplete: (Boolean) -> Unit, forceRebuild: Boolean = false) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SyncCommandCoordinator(applicationContext).start(target = null, forceRebuild = forceRebuild)
            }
            when {
                result.reused || result.errorCode == "busy" -> AppSnackbar.make(
                    binding.root,
                    R.string.home_sync_already_queued,
                    Snackbar.LENGTH_LONG,
                ).show()
                !result.accepted || result.state == "failed" -> AppSnackbar.make(
                    binding.root,
                    R.string.home_sync_start_failed,
                    Snackbar.LENGTH_LONG,
                ).show()
            }
            applyCachedFedoraStatus(renderStatusSnapshot)
            onComplete(result.accepted && !result.reused && result.state != "failed")
        }
    }

    private fun runAfterSyncNotificationPermission(action: () -> Unit) {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingSyncAfterNotificationPermission = action
        syncNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestPdfSync(
        targetPdfFilename: String,
        onQueued: (Boolean) -> Unit = {},
    ) {
        if (ThoughtsServerSecretStore(this).config() == null) {
            showServerConfigurationRequired(R.string.home_sync_server_not_configured)
            onQueued(false)
            return
        }
        runAfterSyncNotificationPermission {
            startPdfSync(targetPdfFilename, onQueued)
        }
    }

    private fun startPdfSync(
        targetPdfFilename: String,
        onQueued: (Boolean) -> Unit,
    ) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SyncCommandCoordinator(applicationContext).start(targetPdfFilename)
            }
            when {
                result.reused || result.errorCode == "busy" -> {
                    AppSnackbar.make(
                        binding.root,
                        R.string.home_sync_already_queued,
                        Snackbar.LENGTH_LONG,
                    ).show()
                    onQueued(false)
                }
                result.accepted && result.state != "failed" -> onQueued(true)
                else -> {
                    AppSnackbar.make(
                        binding.root,
                        R.string.home_sync_start_failed,
                        Snackbar.LENGTH_LONG,
                    ).show()
                    onQueued(false)
                }
            }
        }
    }

    private fun scheduleSyncOperationReconciliation() {
        syncOperationReconciliationJob?.cancel()
        syncOperationReconciliationJob = lifecycleScope.launch {
            var firstCheck = true
            while (isActive) {
                val pending = withContext(Dispatchers.IO) { syncOperationStore.pendingOperations() }
                val nextDelay = nextSyncOperationReconciliationDelayMillis(
                    pending, System.currentTimeMillis(),
                ) ?: break
                delay(if (firstCheck) nextDelay else maxOf(nextDelay, SyncOperationStore.INTERRUPTION_GRACE_MILLIS))
                firstCheck = false
                withContext(Dispatchers.IO) {
                    ThoughtsSyncScheduler.reconcileInterruptedOperations(applicationContext)
                }
            }
        }
    }

    private fun showSyncMenu() {
        val latestOperation = latestGlobalOperationSnapshot
        // Merge needed offers only the merge; the Sync's log stays in Sync history.
        if (sourceMergeFileCount > 0 && (latestOperation == null || !isPendingSyncOperationStatus(latestOperation.status))) {
            MaterialAlertDialogBuilder(this)
                .setTitle(SyncStatusLabels.MERGE_NEEDED)
                .setMessage(SyncStatusLabels.sourceFilesNeedMerge(sourceMergeFileCount))
                .setPositiveButton(SyncStatusLabels.SHOW_MERGE) { _, _ ->
                    startActivity(Intent(this, SourceConflictListActivity::class.java))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val terminalOperation = latestOperation?.takeIf { operation ->
            operation.status in setOf(
                SyncOperationStore.STATUS_ERROR,
                SyncOperationStore.STATUS_ATTENTION,
            )
        }
        if (terminalOperation != null) {
            lifecycleScope.launch {
                val hasSourceAttention = withContext(Dispatchers.IO) {
                    SourceEditQueue(applicationContext, scheduleRequests = false)
                        .blockingSourceMutations()
                        .isNotEmpty()
                }
                if (hasSourceAttention) {
                    showLocalChanges(pendingPhoneSyncFiles(renderStatusSnapshot))
                } else {
                    showOperationLog(terminalOperation.id)
                }
            }
            return
        }
        if (latestOperation != null && isPendingSyncOperationStatus(latestOperation.status)) {
            showPendingSyncMenu(latestOperation)
            return
        }
        val activeStatus = currentArtifactSyncStatus(applicationContext)
        if (activeStatus != null) {
            SyncStatusDialog(
                activity = this,
                onSyncNow = { requestManualSync() },
                onStatusAction = ::executeDocumentStatusAction,
            ).show(activeStatus, getString(R.string.home_render_status_all))
            return
        }
        val status = idleLibrarySyncStatus(renderStatusSnapshot)
        val localChanges = pendingPhoneSyncFiles(renderStatusSnapshot)
        val dialog = MaterialAlertDialogBuilder(this)
            // The title is the shared status label; the message carries its details.
            .setTitle(
                when {
                    localChanges.isNotEmpty() && !status.hasPendingChanges && !status.hasSyncFailure ->
                        getString(R.string.home_pending_syncs)
                    else -> status.label
                },
            )
            .setMessage(status.timestamp)
            .setNeutralButton(R.string.home_local_changes) { _, _ ->
                showLocalChanges(localChanges)
            }
            .setNegativeButton(R.string.home_force_rebuild) { _, _ ->
                runAfterSyncNotificationPermission {
                    startManualSync(onComplete = {}, forceRebuild = true)
                }
            }
            .setPositiveButton(R.string.document_status_sync) { _, _ ->
                requestManualSync()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).apply {
                isEnabled = localChanges.isNotEmpty()
                alpha = if (isEnabled) 1f else 0.35f
            }
        }
        dialog.show()
    }

    private fun showLocalChanges(changes: List<PendingPhoneSyncFile>) {
        lifecycleScope.launch {
            val sourceAttention = withContext(Dispatchers.IO) {
                SourceEditQueue(applicationContext, scheduleRequests = false)
                    .blockingSourceMutations()
            }
            val sourceAttentionPdfs = sourceAttention.mapTo(hashSetOf()) { it.pdfFilename }
            val regularChanges = changes
                .filterNot { change -> change.filename in sourceAttentionPdfs }
                .joinToString("\n\n") { change ->
                    val details = change.details.ifEmpty {
                        listOf(getString(R.string.home_pending_sync))
                    }.joinToString("\n") { "  $it" }
                    "• ${change.filename}\n$details"
                }
            val sourceProblems = sourceAttention.joinToString("\n\n") { mutation ->
                sourceMutationStatusDetails(
                    mutation,
                    conflictTitle = { path ->
                        getString(R.string.source_conflict_in_path, path)
                    },
                    failureTitle = { path ->
                        getString(R.string.source_failed_in_path, path)
                    },
                ).mapIndexed { index, detail ->
                    if (index == 0) "• $detail" else "  $detail"
                }.joinToString("\n")
            }
            val message = listOf(regularChanges, sourceProblems)
                .filter(String::isNotBlank)
                .joinToString("\n\n")
                .ifBlank { getString(R.string.home_no_local_changes) }
            val refusedChanges = withContext(Dispatchers.IO) {
                runCatching { com.gitlab.mudlej.MjPdfReader.data.sync.refusedChangeNotices(applicationContext).size }.getOrDefault(0)
            }
            val builder = MaterialAlertDialogBuilder(this@HomeActivity)
                .setTitle(R.string.home_local_changes)
                .setMessage(message)
            val actions = sourceAttentionActions(sourceAttention)
            // Changes the server refused are retried or discarded from here; a merge comes first.
            if (refusedChanges > 0 && !(actions.resolveConflicts && actions.retryFailures)) {
                builder.setNeutralButton(R.string.sync_refused_changes_review) { _, _ ->
                    com.gitlab.mudlej.MjPdfReader.ui.sync.RefusedChangesDialog(this@HomeActivity).show()
                }
            }
            if (actions.resolveConflicts) {
                builder
                    .setNegativeButton(R.string.close, null)
                    .setPositiveButton(R.string.source_conflict_resolve) { _, _ ->
                        startActivity(Intent(this@HomeActivity, SourceConflictListActivity::class.java))
                    }
                if (actions.retryFailures) {
                    builder.setNeutralButton(R.string.document_status_sync) { _, _ ->
                        requestManualSync()
                    }
                }
            } else if (actions.retryFailures) {
                builder
                    .setNegativeButton(R.string.close, null)
                    .setPositiveButton(R.string.document_status_sync) { _, _ ->
                        requestManualSync()
                    }
            } else {
                builder.setPositiveButton(R.string.close, null)
            }
            builder.show()
        }
    }

    private fun showOperations() {
        SyncOperationsDialog(this).show()
    }

    private fun showOperationLog(operationId: String) {
        lifecycleScope.launch {
            val operation = withContext(Dispatchers.IO) {
                syncOperationStore.operationWithEvents(operationId)
            } ?: return@launch
            if (!isFinishing && !isDestroyed) {
                SyncOperationsDialog(this@HomeActivity).showRecord(operation)
            }
        }
    }

    private fun showPendingSyncMenu(operation: SyncOperationRecord) {
        val transferPlan = pendingMobileDataPlan(
            operation,
            SyncTransferStateStore(applicationContext).load(),
        )
        if (transferPlan == null) {
            showOperationLog(operation.id)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(operation.status)
            .setMessage(SyncTransferCoordinator.promptMessage(this, transferPlan))
            .setPositiveButton(R.string.sync_transfer_continue_mobile) { _, _ ->
                SyncTransferCoordinator.approve(this, transferPlan.planId)
            }
            .setNegativeButton(R.string.sync_transfer_wait_wifi) { _, _ ->
                SyncTransferCoordinator.waitForWifi(this, transferPlan.planId)
            }
            .setNeutralButton(R.string.operations_current_log) { _, _ ->
                showOperationLog(operation.id)
            }
            .show()
    }

    private fun showServerConfigurationRequired(messageRes: Int) {
        AppSnackbar.make(binding.root, messageRes, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.home_configure_server) {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_PAGE, SettingsPage.SYNCHRONIZATION.name)
                )
            }
            .show()
    }

    private fun scheduleReceiptCheck() {
        renderReceiptJob?.cancel()
        renderReceiptJob = lifecycleScope.launch {
            delay(RENDER_RECEIPT_DEBOUNCE_MS)
            val receipts = withContext(Dispatchers.IO) {
                phoneRenderQueue.consumeCompletedReceipts()
            }
            refreshRenderStatus()
            if (receipts.isEmpty()) {
                return@launch
            }
            if (completedReceiptsRequireLibraryRefresh(receipts)) {
                refresh()
            }
        }
    }

    private fun startRenderReceiptObserver() {
        if (renderReceiptObserver != null || !permissionManager.hasFullAccess()) {
            return
        }
        renderReceiptObserver = runCatching {
            phoneRenderQueue.observe(::scheduleReceiptCheck)
        }.getOrNull()
        if (renderReceiptObserver != null) {
            scheduleReceiptCheck()
        }
    }

    /**
     * While nothing runs, Home read the whole status again every 10 seconds: every queued change,
     * every review card record, every PDF of the Library. Every writer of those already asks for a
     * refresh when it writes; this check now only looks whether the files and the database behind
     * the status were written since the last read ([renderStatusInputStamp]) and reads again when
     * they were, or after [RENDER_STATUS_IDLE_BACKSTOP_MS]. With jobs running it reads every
     * second, as before.
     */
    private fun startRenderStatusPolling() {
        renderStatusPollJob?.cancel()
        // The first check after Home comes up reads everything, as before.
        renderStatusIdleGate.reset()
        renderStatusPollJob = lifecycleScope.launch {
            var skipped = 0
            while (isActive) {
                val stamp = withContext(Dispatchers.IO) { renderStatusInputStamp() }
                val now = android.os.SystemClock.elapsedRealtime()
                if (renderStatusSnapshot.activeJobs.isNotEmpty() || renderStatusIdleGate.shouldRefresh(stamp, now)) {
                    refreshRenderStatus()
                    renderStatusIdleGate.refreshed(stamp, now)
                    Log.d(TAG, "Status poll read everything in ${android.os.SystemClock.elapsedRealtime() - now} ms " +
                        "after $skipped unchanged checks")
                    skipped = 0
                } else {
                    skipped++
                }
                delay(
                    if (renderStatusSnapshot.activeJobs.isNotEmpty()) {
                        RENDER_STATUS_ACTIVE_POLL_MS
                    } else {
                        RENDER_STATUS_IDLE_POLL_MS
                    }
                )
            }
        }
    }

    /** What [refreshRenderStatusOnce] reads from disk, as a stamp ([pathsStamp]); blank when it cannot be taken. */
    private fun renderStatusInputStamp(): String = runCatching {
        val pdfs = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val database = AppDatabase.getInstance(applicationContext).openHelper.writableDatabase.path?.let(::File)
        com.gitlab.mudlej.MjPdfReader.data.pathsStamp(
            paths = listOfNotNull(
                pdfs,
                File(pdfs, SourceLinkedPdfIndex.GENERATION_FILENAME),
                File(pdfs, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME),
                File(pdfs, SourceLinkedPdfIndex.INDEX_FILENAME),
                File(noBackupFilesDir, "thoughts-sync/library-head.json"),
                PhoneRenderQueue.defaultRequestDirectory(),
                PhoneRenderQueue.defaultReceiptDirectory(),
                com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue.defaultRequestDirectory(),
                com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore.sourceReceipts(),
                com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore.comments(),
                // Every write to the database changes one of these two files.
                database,
                database?.let { File(it.path + "-wal") },
            ),
            extra = listOf(
                "pending:${PendingChangesSignal.generation.value}",
                "status:${com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSignal.generation.value}",
            ),
        )
    }.getOrDefault("")

    private fun scheduleRenderStatusRefresh() {
        lifecycleScope.launch {
            refreshRenderStatus()
        }
    }

    private suspend fun refreshRenderStatus() {
        // Status badges must not compete with the first useful catalogue query.
        withContext(Dispatchers.IO) { com.gitlab.mudlej.MjPdfReader.data.StartupReadiness.awaitMaintenanceWindow() }
        val requestGeneration = renderStatusRequestGeneration.incrementAndGet()
        renderStatusRefreshMutex.withLock {
            if (requestGeneration <= renderStatusCompletedGeneration) {
                return@withLock
            }
            do {
                val targetGeneration = renderStatusRequestGeneration.get()
                refreshRenderStatusOnce()
                renderStatusCompletedGeneration = targetGeneration
            } while (renderStatusRequestGeneration.get() > renderStatusCompletedGeneration)
        }
    }

    private suspend fun refreshRenderStatusOnce() {
        val rawSnapshot = if (permissionManager.hasFullAccess()) {
            withContext(Dispatchers.IO) {
                val syncState = AppDatabase.getInstance(applicationContext)
                    .thoughtsSyncDao()
                    .state()
                val localLibrary = sourcePdfIndex.snapshot()
                val remoteHead = LibraryHeadStateStore(applicationContext).load()
                val reconciled = reconcileRemoteLibraryStatus(
                    snapshot = phoneRenderQueue.loadStatusSnapshot(
                        syncHealth = PhoneRenderSyncHealth(
                            lastAttemptEpochMillis = syncState?.lastSyncAttemptEpochMillis ?: 0,
                            lastSuccessEpochMillis = syncState?.lastSyncSuccessEpochMillis ?: 0,
                            errorCode = syncState?.lastSyncErrorCode.orEmpty(),
                            errorMessage = syncState?.lastSyncErrorMessage.orEmpty(),
                        ),
                    ),
                    remoteHead = remoteHead,
                    localLibrary = localLibrary,
                )
                val explicitStatuses = phoneRenderStatusHistory.pdfRenderStatuses()
                val superseded = supersededExplicitPdfRenderStatusFilenames(
                    remoteHead,
                    localLibrary,
                    explicitStatuses,
                )
                phoneRenderStatusHistory.removePdfRenderStatuses(superseded)
                val remote = mergeExplicitPdfRenderStatuses(
                    reconciled,
                    explicitStatuses.filterNot { it.filename in superseded },
                    localLibrary,
                )
                val activeTargets = syncOperationStore.pendingOperations()
                    .filter { operation -> operation.target != null }
                    .distinctBy { it.target }
                    .map { operation ->
                        PhoneRenderJobStatus(
                            requestId = "active-sync:${operation.id}",
                            target = requireNotNull(operation.target),
                            stage = PhoneRenderStage.WAITING_FOR_SYNC,
                            message = operation.status,
                            bannerEligible = false,
                        )
                    }
                PhoneRenderStatusSnapshot(
                    remote.jobs + activeTargets + pendingDocumentSyncStatuses(),
                )
            }
        } else {
            PhoneRenderStatusSnapshot(
                withContext(Dispatchers.IO) { pendingDocumentSyncStatuses() },
            )
        }
        val snapshot = phoneRenderStatusHistory.apply(rawSnapshot)
        applyCachedFedoraStatus(snapshot)
        refreshOpenSyncBuild()
        if (snapshot == renderStatusSnapshot) {
            return
        }
        val previous = renderStatusSnapshot
        val baseline = !renderStatusObserved
        renderStatusObserved = true
        renderStatusSnapshot = snapshot
        libraryTab.applyRenderStatus(snapshot)
        searchResultsAdapter.applyRenderStatus(snapshot)
        // The first status after launch is a baseline: Home's first load already read the PDFs
        // those jobs updated, and treating them all as new reloaded Home on start.
        val newlyUpdated = !baseline && snapshot.jobs.any { current ->
            current.stage == PhoneRenderStage.UPDATED &&
                previous.jobs.none {
                    it.requestId == current.requestId &&
                        it.stage == PhoneRenderStage.UPDATED
                }
        }
        if (newlyUpdated) {
            refresh()
        }
    }

    private fun observeLatestGlobalOperation() {
        lifecycleScope.launch {
            combine(
                syncOperationStore.observeLatestGlobalOperation(),
                syncOperationStore.observeLastSuccessfulSyncAt(),
            ) { operation, lastSuccessfulSyncAt -> operation to lastSuccessfulSyncAt }
                .collectLatest { (operation, lastSuccessfulSyncAt) ->
                    latestGlobalOperationSnapshot = operation
                    lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAt
                    sourceMergeFileCount = withContext(Dispatchers.IO) { loadSourceFilesNeedingMerge().size }
                    applyCachedFedoraStatus(renderStatusSnapshot)
                    refreshOpenSyncBuild()
                }
        }
    }

    private fun refreshSourceMergeFiles() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { loadSourceFilesNeedingMerge().size }
            if (count != sourceMergeFileCount) {
                sourceMergeFileCount = count
                applyCachedFedoraStatus(renderStatusSnapshot)
            }
        }
    }

    /** As of the last list of local changes ([pendingDocumentSyncStatuses]), which the status follows. */
    @Volatile private var refusedLocalChangeCount = 0
    @Volatile private var otherBlockedLocalChanges = false

    private fun applyCachedFedoraStatus(snapshot: PhoneRenderStatusSnapshot) {
        val presentation = librarySyncStatusPresentation(
            operation = latestGlobalOperationSnapshot,
            lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAtEpochMillis,
            pendingFileCount = pendingPhoneSyncFileCount(snapshot),
            hasBlockedLocalChanges = snapshot.jobs.any {
                it.requestId.startsWith("local-sync:") && it.stage == PhoneRenderStage.BLOCKED
            },
            mergeFileCount = sourceMergeFileCount,
            pdfsStillBuilding = PendingFedoraBuildStore(this).pdfCount(),
            refusedChangeCount = refusedLocalChangeCount,
            hasOtherBlockedLocalChanges = otherBlockedLocalChanges,
        )
        bindTopSyncStatus(presentation)
        val startedAt = presentation.elapsedStartedAtEpochMillis
        if (startedAt != null) {
            scheduleSyncElapsedRefresh(startedAt)
        } else {
            syncElapsedRefreshJob?.cancel()
            syncElapsedRefreshJob = null
        }
    }

    private fun idleLibrarySyncStatus(snapshot: PhoneRenderStatusSnapshot): LibrarySyncStatusPresentation =
        librarySyncStatusPresentation(
            operation = null,
            lastSuccessfulSyncAtEpochMillis = lastSuccessfulSyncAtEpochMillis,
            pendingFileCount = pendingPhoneSyncFileCount(snapshot),
            hasBlockedLocalChanges = snapshot.jobs.any {
                it.requestId.startsWith("local-sync:") && it.stage == PhoneRenderStage.BLOCKED
            },
            mergeFileCount = sourceMergeFileCount,
            pdfsStillBuilding = PendingFedoraBuildStore(this).pdfCount(),
            refusedChangeCount = refusedLocalChangeCount,
            hasOtherBlockedLocalChanges = otherBlockedLocalChanges,
        )

    private fun bindTopSyncStatus(presentation: LibrarySyncStatusPresentation) {
        val state = presentation.iconState()
        binding.syncStatusButton.setImageResource(
            when (state) {
                LibrarySyncIconState.CURRENT -> R.drawable.ic_sync_done
                LibrarySyncIconState.PENDING,
                LibrarySyncIconState.SYNCING -> R.drawable.ic_refresh
                LibrarySyncIconState.ERROR -> R.drawable.ic_sync_problem
            },
        )
        val colorAttr = when (state) {
            LibrarySyncIconState.ERROR -> androidx.appcompat.R.attr.colorError
            LibrarySyncIconState.PENDING,
            LibrarySyncIconState.SYNCING -> androidx.appcompat.R.attr.colorPrimary
            LibrarySyncIconState.CURRENT -> com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        binding.syncStatusButton.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(binding.syncStatusButton, colorAttr),
        )
        binding.syncStatusButton.contentDescription = getString(
            R.string.home_sync_status_content_description,
            presentation.label,
            presentation.timestamp,
        )
        if (state == LibrarySyncIconState.SYNCING) {
            if (syncStatusButtonAnimator?.isRunning != true) {
                syncStatusButtonAnimator = ObjectAnimator.ofFloat(
                    binding.syncStatusButton,
                    View.ROTATION,
                    0f,
                    360f,
                ).apply {
                    duration = 900L
                    interpolator = LinearInterpolator()
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.RESTART
                    start()
                }
            }
        } else {
            syncStatusButtonAnimator?.cancel()
            syncStatusButtonAnimator = null
            binding.syncStatusButton.rotation = 0f
        }
    }

    private fun scheduleSyncElapsedRefresh(startedAtEpochMillis: Long) {
        syncElapsedRefreshJob?.cancel()
        val elapsedMillis = (System.currentTimeMillis() - startedAtEpochMillis).coerceAtLeast(0L)
        val refreshDelay = 1_000L - (elapsedMillis % 1_000L)
        syncElapsedRefreshJob = lifecycleScope.launch {
            delay(refreshDelay)
            applyCachedFedoraStatus(renderStatusSnapshot)
        }
    }

    private suspend fun pendingDocumentSyncStatuses(): List<PhoneRenderJobStatus> {
        val canonicalPdfs = PhoneRenderQueue.defaultCanonicalPdfDirectory()
            .listFiles { file -> file.isFile && file.extension.equals("pdf", ignoreCase = true) }
            .orEmpty()

        val dao = AppDatabase.getInstance(applicationContext).thoughtsSyncDao()
        val allOperations = dao.allOutbox()
        val operations = allOperations.filter { it.isUndelivered }
        val capturedIdentities = allOperations.mapTo(hashSetOf()) { operation ->
            "${operation.entityType}:${operation.entityId}"
        }
        val cardStems = mutableMapOf<String, String>()

        fun reviewCardStem(payloadJson: String): String? = runCatching {
            JsonParser.parseString(payloadJson)
                .asJsonObject
                .getAsJsonObject("primary")
                ?.get("pdf_stem")
                ?.asString
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

        dao.reviewCardProjections()
            .asSequence()
            .forEach { projection ->
                reviewCardStem(projection.payloadJson)?.let { cardStems[projection.entityId] = it }
            }
        operations
            .asSequence()
            .filter { it.entityType == "review_card" }
            .forEach { operation ->
                reviewCardStem(operation.payloadJson)?.let { cardStems[operation.entityId] = it }
            }
        ReviewStore(applicationContext).cardDocumentStems().forEach { (id, stem) -> cardStems.putIfAbsent(id, stem) }

        val countsByFilename = mutableMapOf<String, PendingDocumentSyncCounts>()
        operations.forEach { operation ->
            val payload = runCatching { JsonParser.parseString(operation.payloadJson).asJsonObject }
                .getOrNull()
            val proposalDecision = operation.agentProposalDecisionOrNull()
            if (proposalDecision != null) {
                val proposalCount = proposalDecision.proposalIds.size
                val detail = if (proposalDecision.phase == AgentProposalDecisionPhase.FAILED) {
                    getString(
                        if (proposalDecision.action == "accept") {
                            R.string.document_status_failed_proposal_accept
                        } else {
                            R.string.document_status_failed_proposal_reject
                        },
                        proposalDecision.error.ifBlank { getString(R.string.home_sync_start_failed) },
                    )
                } else {
                    resources.getQuantityString(
                        if (proposalDecision.action == "accept") {
                            R.plurals.document_status_pending_proposal_accept
                        } else {
                            R.plurals.document_status_pending_proposal_reject
                        },
                        proposalCount,
                        proposalCount,
                    )
                }
                proposalDecision.pdfFilenames.forEach { filename ->
                    val counts = countsByFilename.getOrPut(filename) {
                        PendingDocumentSyncCounts()
                    }
                    counts.proposalDetails += detail
                    counts.latestEpochMillis = maxOf(
                        counts.latestEpochMillis,
                        operation.createdAtEpochMillis,
                    )
                    if (proposalDecision.phase == AgentProposalDecisionPhase.FAILED) {
                        counts.blocked = true
                    }
                }
                return@forEach
            }
            val stem = when (operation.entityType) {
                "annotation", "comment" -> operation.collection
                "review_card" -> payload
                    ?.getAsJsonObject("primary")
                    ?.get("pdf_stem")
                    ?.asString
                "review_event" -> payload
                    ?.get("card_id")
                    ?.asString
                    ?.let(cardStems::get)
                else -> null
            }?.takeIf(String::isNotBlank)
            val filename = stem?.let {
                if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
            } ?: OTHER_PENDING_CHANGES_TARGET
            val counts = countsByFilename.getOrPut(filename) { PendingDocumentSyncCounts() }
            when (operation.entityType) {
                "annotation", "comment", "logical_comment" -> counts.comments += 1
                "review_card" -> counts.cards += 1
                "review_event" -> counts.events += 1
                else -> counts.other += 1
            }
            counts.latestEpochMillis = maxOf(counts.latestEpochMillis, operation.createdAtEpochMillis)
            if (operation.deliveryState == "blocked") {
                counts.blocked = true
                if (operation.hasLegacyNoteSchemaFailure()) {
                    counts.failures += getString(R.string.sync_note_server_update_required)
                } else {
                    operation.failureDetail.takeIf(String::isNotBlank)?.let(counts.failures::add)
                }
            }
        }

        val commentStore = CommentSidecarStore(applicationContext)
        val commentDeliveryOutbox = CommentDeliveryOutbox.forContext(applicationContext)
        val countedCommentJournalFiles = mutableSetOf<String>()
        canonicalPdfs.forEach { file ->
            if (commentStore.hasPending(Uri.fromFile(file))) {
                countedCommentJournalFiles += file.name
                val counts = countsByFilename.getOrPut(file.name) { PendingDocumentSyncCounts() }
                if (counts.comments == 0) counts.comments = 1
            }
        }
        commentDeliveryOutbox.load().forEach { batch ->
            val counts = countsByFilename.getOrPut(batch.target) { PendingDocumentSyncCounts() }
            counts.comments = maxOf(counts.comments, batch.records.size)
            counts.latestEpochMillis = maxOf(counts.latestEpochMillis, batch.createdAtEpochMillis)
        }
        val unmappedJournals = commentStore.pendingJournalCount() - countedCommentJournalFiles.size
        if (unmappedJournals > 0) {
            countsByFilename.getOrPut(OTHER_PENDING_CHANGES_TARGET) { PendingDocumentSyncCounts() }
                .comments += unmappedJournals
        }

        reviewStore.pendingChanges()
            .filterNot { change -> "${change.entityType}:${change.entityId}" in capturedIdentities }
            .forEach { change ->
                val filename = change.pdfStem
                    ?.let { stem -> if (stem.endsWith(".pdf", true)) stem else "$stem.pdf" }
                    ?: OTHER_PENDING_CHANGES_TARGET
                val counts = countsByFilename.getOrPut(filename) { PendingDocumentSyncCounts() }
                if (change.entityType == "review_card") counts.cards += 1 else counts.events += 1
                counts.latestEpochMillis = maxOf(
                    counts.latestEpochMillis,
                    change.createdAtEpochMillis,
                )
            }

        val projectChanges = com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore(applicationContext).pendingCount()
        if (projectChanges > 0) countsByFilename.getOrPut(OTHER_PENDING_CHANGES_TARGET) { PendingDocumentSyncCounts() }.other += projectChanges

        // A change the server refused is not sent again by itself. It stays in this list as a
        // blocked change, so the Sync button keeps its issue mark until the user has retried or
        // discarded it (Local changes, or the Sync details): a refused PDF import cannot go unnoticed.
        com.gitlab.mudlej.MjPdfReader.data.sync.refusedChangeNotices(applicationContext).forEach { notice ->
            val counts = countsByFilename.getOrPut(notice.pdfFilename ?: OTHER_PENDING_CHANGES_TARGET) {
                PendingDocumentSyncCounts()
            }
            counts.refused += 1
            if (notice.removal) counts.libraryRemoval = true
            counts.failures += getString(R.string.sync_refused_change_notice, notice.description, notice.detail)
            counts.latestEpochMillis = maxOf(counts.latestEpochMillis, notice.refusedAtEpochMillis)
        }

        LibraryRemovalStore(applicationContext).toSend().forEach { removal ->
            val counts = countsByFilename.getOrPut(removal.filename) {
                PendingDocumentSyncCounts()
            }
            counts.libraryRemoval = true
            counts.latestEpochMillis = maxOf(
                counts.latestEpochMillis,
                removal.createdAtEpochMillis,
            )
            removal.failureDetail.takeIf(String::isNotBlank)?.let { failure ->
                counts.blocked = true
                counts.failures += failure
            }
        }

        runCatching { LibraryMutationStore(applicationContext).operations() }
            .onFailure { error ->
                countsByFilename.getOrPut(OTHER_PENDING_CHANGES_TARGET) { PendingDocumentSyncCounts() }.apply {
                    other += 1
                    blocked = true
                    failures += "The Library changes saved on this phone could not be read: ${error.message.orEmpty()}"
                }
            }
            .getOrDefault(emptyList())
            .let(::pendingLibraryChanges)
            .forEach { change ->
                val counts = countsByFilename.getOrPut(change.filename) { PendingDocumentSyncCounts() }
                counts.libraryChanges += getString(change.label)
                counts.latestEpochMillis = maxOf(counts.latestEpochMillis, change.createdAtEpochMillis)
                change.failure?.let { failure ->
                    counts.blocked = true
                    counts.failures += failure
                }
            }

        // For the Sync status: refused changes alone do not make it "Sync failed".
        refusedLocalChangeCount = countsByFilename.values.sumOf(PendingDocumentSyncCounts::refused)
        otherBlockedLocalChanges = countsByFilename.values.any(PendingDocumentSyncCounts::blocked)
        val pendingSync = countsByFilename.map { (filename, counts) ->
            val details = buildList {
                if (counts.libraryRemoval) {
                    add(getString(R.string.document_status_pending_library_removal))
                }
                addAll(counts.libraryChanges.distinct())
                if (counts.comments > 0) {
                    add(resources.getQuantityString(
                        R.plurals.document_status_pending_comments,
                        counts.comments,
                        counts.comments,
                    ))
                }
                if (counts.cards > 0) {
                    add(resources.getQuantityString(
                        R.plurals.document_status_pending_cards,
                        counts.cards,
                        counts.cards,
                    ))
                }
                if (counts.events > 0) {
                    add(resources.getQuantityString(
                        R.plurals.document_status_pending_events,
                        counts.events,
                        counts.events,
                    ))
                }
                if (counts.other > 0) {
                    add(resources.getQuantityString(
                        R.plurals.document_status_pending_other_changes,
                        counts.other,
                        counts.other,
                    ))
                }
                addAll(counts.proposalDetails.distinct())
                addAll(counts.failures.distinct())
            }
            PhoneRenderJobStatus(
                requestId = "local-sync:$filename",
                target = filename,
                stage = if (counts.blocked || counts.refused > 0) {
                    PhoneRenderStage.BLOCKED
                } else {
                    PhoneRenderStage.SYNC_NEEDED
                },
                message = details.joinToString("\n"),
                createdAtEpochMillis = counts.latestEpochMillis,
                bannerEligible = false,
            )
        }
        val sourceQueue = SourceEditQueue(applicationContext, scheduleRequests = false)
        sourceQueue.captureExternalEdits()
        val pendingSource = pendingSourceRenderStatuses(
            mutations = sourceQueue.pendingSourceMutations(),
            pendingMessage = getString(R.string.document_status_local_tex_pending),
            conflictTitle = { path -> getString(R.string.source_conflict_in_path, path) },
            failureTitle = { path -> getString(R.string.source_failed_in_path, path) },
        )
        return pendingSync + pendingSource
    }

    private fun handleArtifactPromptIntent(intent: Intent) {
        if (!intent.getBooleanExtra(ArtifactSyncCoordinator.EXTRA_SHOW_PROMPT, false)) return
        intent.removeExtra(ArtifactSyncCoordinator.EXTRA_SHOW_PROMPT)
        ArtifactSyncStateStore(this).load()?.let(::showArtifactSyncPrompt)
    }

    private fun handleSyncTransferPromptIntent(intent: Intent) {
        if (!intent.getBooleanExtra(SyncTransferCoordinator.EXTRA_SHOW_PROMPT, false)) return
        intent.removeExtra(SyncTransferCoordinator.EXTRA_SHOW_PROMPT)
        SyncTransferStateStore(this).load()?.let { showSyncTransferPrompt(this, it) }
    }

    private fun showArtifactSyncPrompt(plan: ArtifactSyncPlan) {
        if (
            isFinishing ||
            plan.status == ArtifactSyncPlan.STATUS_TRANSFERRING ||
            plan.status == ArtifactSyncPlan.STATUS_QUEUED ||
            plan.status == ArtifactSyncPlan.STATUS_RETRYING
        ) return
        if (plan.status == ArtifactSyncPlan.STATUS_FAILED) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.artifact_sync_status_failed)
                .setMessage(plan.error.ifBlank { getString(R.string.home_sync_start_failed) })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.operations_retry) { _, _ ->
                    val store = ArtifactSyncStateStore(this)
                    val canRunNow = ThoughtsNetworkPolicy.isUnmetered(this) ||
                        plan.canRunOnMeteredWithoutApproval || plan.meteredApproved
                    val updated = if (canRunNow) {
                        store.markQueued(plan.planId)
                    } else {
                        store.markWaiting(plan.planId)
                    } ?: return@setPositiveButton
                    syncOperationStore.append(
                        updated.operationId,
                        if (canRunNow) SyncOperationStore.STATUS_QUEUED else
                            SyncOperationStore.STATUS_WAITING,
                        if (canRunNow) "Retry queued" else "Waiting for an unmetered network",
                    )
                    ThoughtsSyncScheduler.enqueueArtifacts(
                        this,
                        allowMetered = updated.canRunOnMeteredWithoutApproval ||
                            updated.meteredApproved,
                        replace = true,
                    )
                    ArtifactSyncCoordinator.broadcast(this, updated, showPrompt = false)
                }
                .show()
            return
        }
        val transfer = if (plan.estimatedTransferBytes > 0) {
            getString(
                R.string.artifact_sync_size_known,
                Formatter.formatShortFileSize(this, plan.estimatedTransferBytes),
            )
        } else {
            getString(R.string.artifact_sync_size_unknown)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.artifact_sync_prompt_title)
            .setMessage(getString(R.string.artifact_sync_prompt_message, transfer))
            .setNegativeButton(R.string.artifact_sync_wait_for_wifi) { _, _ ->
                val updated = ArtifactSyncStateStore(this).markWaiting(plan.planId) ?: return@setNegativeButton
                syncOperationStore.append(
                    updated.operationId,
                    SyncOperationStore.STATUS_WAITING,
                    "Waiting for an unmetered network",
                )
                ArtifactSyncCoordinator.dismissNotification(this)
                ThoughtsSyncScheduler.enqueueArtifacts(this, allowMetered = false)
                ArtifactSyncCoordinator.broadcast(this, updated, showPrompt = false)
            }
            .setPositiveButton(R.string.artifact_sync_use_mobile_once) { _, _ ->
                val updated = ArtifactSyncStateStore(this).approveMetered(plan.planId)
                    ?: return@setPositiveButton
                syncOperationStore.append(
                    updated.operationId,
                    SyncOperationStore.STATUS_QUEUED,
                    "Artifact update queued",
                )
                ArtifactSyncCoordinator.dismissNotification(this)
                ThoughtsSyncScheduler.enqueueArtifacts(this, allowMetered = true, replace = true)
                ArtifactSyncCoordinator.broadcast(this, updated, showPrompt = false)
            }
            .setOnCancelListener {
                val updated = ArtifactSyncStateStore(this).markWaiting(plan.planId)
                    ?: return@setOnCancelListener
                syncOperationStore.append(
                    updated.operationId,
                    SyncOperationStore.STATUS_WAITING,
                    "Waiting for an unmetered network",
                )
                ThoughtsSyncScheduler.enqueueArtifacts(this, allowMetered = false)
                ArtifactSyncCoordinator.broadcast(this, updated, showPrompt = false)
            }
            .show()
    }

    private fun renderStatusTarget(status: PhoneRenderJobStatus): String {
        return if (status.affectsAll) {
            getString(R.string.home_render_status_all)
        } else {
            status.target
        }
    }

    private fun renderStatusStage(status: PhoneRenderJobStatus): String {
        return getString(
            documentStatusLabelRes(status.stage, status.verifiedAtEpochMillis),
        )
    }

    private fun showRenderStatusDetails(status: PhoneRenderJobStatus) {
        val presentation = documentStatusPresentation(status.stage, status.verifiedAtEpochMillis)
        val cause = status.message.trim().takeIf(String::isNotEmpty)
        val message = listOfNotNull(
            renderStatusTarget(status).takeUnless { status.affectsAll },
            cause,
        ).joinToString("\n").ifBlank { renderStatusTarget(status) }
        MaterialAlertDialogBuilder(this)
            .setTitle(renderStatusStage(status))
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
            .apply {
                when (presentation.action) {
                    DocumentStatusAction.SYNC_NOW ->
                        setPositiveButton(R.string.document_status_sync) { _, _ ->
                            executeDocumentStatusAction(presentation.action, status)
                        }
                    DocumentStatusAction.SHOW_ERRORS ->
                        setPositiveButton(R.string.home_render_show_errors) { _, _ ->
                            executeDocumentStatusAction(presentation.action, status)
                        }
                    DocumentStatusAction.NONE -> Unit
                }
            }
            .show()
    }

    private fun executeDocumentStatusAction(
        action: DocumentStatusAction,
        status: PhoneRenderJobStatus,
    ) {
        when (documentStatusCommand(action)) {
            DocumentStatusCommand.REQUEST_MANUAL_SYNC -> {
                if (status.affectsAll) requestManualSync() else requestPdfSync(status.target)
            }
            DocumentStatusCommand.SHOW_ERRORS -> showLocalLogsForStatus(status)
            DocumentStatusCommand.NONE -> Unit
        }
    }

    private fun showLocalLogsForStatus(status: PhoneRenderJobStatus) {
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) {
                localBuildLogRecordForStatus(syncOperationStore.records(), status)
            }
            if (isFinishing || isDestroyed) return@launch
            val dialog = SyncOperationsDialog(this@HomeActivity)
            if (record == null) dialog.show() else dialog.showRecord(record)
        }
    }

    private fun showPdfBuildErrorDialog(pages: List<PdfBuildErrorPage>) {
        if (pages.isEmpty()) {
            return
        }
        val localBuildLogs = LocalBuildLogStore()
        val errorBinding = DialogPdfBuildErrorBinding.inflate(layoutInflater)
        var pageIndex = 0
        lateinit var dialog: AlertDialog

        fun renderPage() {
            val page = pages[pageIndex]
            val navigation = pdfBuildErrorNavigation(pageIndex, pages.size)
            errorBinding.errorTitle.text = getString(
                R.string.home_render_error_title,
                navigation.displayIndex,
                navigation.total,
            )
            errorBinding.errorDetails.text = renderPdfBuildErrorDetails(page)
            val hasLog = localBuildLogs.isAvailable(page.logReference)
            errorBinding.errorShowLog.visibility = if (hasLog) View.VISIBLE else View.GONE
            errorBinding.errorPrevious.visibility =
                if (navigation.canGoBack) View.VISIBLE else View.GONE
            errorBinding.errorNext.visibility =
                if (navigation.canGoNext) View.VISIBLE else View.GONE
            errorBinding.errorNavigation.visibility =
                if (hasLog || navigation.total > 1) View.VISIBLE else View.GONE
            val canAct = canActOnPdfBuildError(page)
            errorBinding.errorRetry.visibility = if (canAct) View.VISIBLE else View.GONE
            errorBinding.errorActions.visibility =
                if (canAct) View.VISIBLE else View.GONE
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(errorBinding.root)
            .create()
        dialog.setOnShowListener {
            errorBinding.errorClose.setOnClickListener { dialog.dismiss() }
            errorBinding.errorPrevious.setOnClickListener {
                pageIndex -= 1
                renderPage()
            }
            errorBinding.errorNext.setOnClickListener {
                pageIndex += 1
                renderPage()
            }
            errorBinding.errorShowLog.setOnClickListener {
                val page = pages[pageIndex]
                startActivity(
                    PdfBuildLogActivity.intent(
                        this,
                        page.logReference,
                        page.pdfFilename.orEmpty(),
                        page.source,
                        page.message,
                    )
                )
            }
            errorBinding.errorRetry.setOnClickListener {
                val page = pages[pageIndex]
                if (canActOnPdfBuildError(page)) {
                    dialog.dismiss()
                    retryPdfBuildError(page)
                }
            }
            renderPage()
        }
        dialog.show()
    }

    private fun renderPdfBuildErrorDetails(page: PdfBuildErrorPage): String {
        val lines = mutableListOf<String>()
        page.pdfFilename?.let {
            lines.add(getString(R.string.home_render_error_pdf, it))
        } ?: page.actionTarget?.let { target ->
            lines.add(
                getString(
                    R.string.home_render_status_target,
                    if (target == PhoneRenderQueue.ACTION_ALL) {
                        getString(R.string.home_render_status_all)
                    } else {
                        target
                    },
                )
            )
        }
        page.source.takeIf(String::isNotBlank)?.let {
            lines.add(getString(R.string.home_render_error_source, it))
        }
        if (lines.isNotEmpty()) {
            lines.add("")
        }
        lines.add(
            page.message.takeIf(String::isNotBlank)
                ?: getString(R.string.home_render_error_no_details)
        )
        page.logReference.takeIf(String::isNotBlank)?.let {
            lines.add("")
            lines.add(getString(R.string.home_render_status_log_reference, it))
        }
        return lines.joinToString("\n")
    }

    private fun canActOnPdfBuildError(page: PdfBuildErrorPage): Boolean {
        val target = page.actionTarget ?: return false
        return target == PhoneRenderQueue.ACTION_ALL || phoneRenderQueue.canRequestPdf(
            File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), target)
        )
    }

    private fun retryPdfBuildError(page: PdfBuildErrorPage) {
        val target = page.actionTarget ?: return
        if (target == PhoneRenderQueue.ACTION_ALL) {
            requestManualSync()
        } else {
            requestPdfSync(target)
        }
    }

    private fun formatRenderStatusTime(epochMillis: Long): String {
        return DateUtils.formatDateTime(
            this,
            epochMillis,
            DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

    private fun statusBatch(items: List<HomeItem>) {
        val labels = ReadingStatus.entries
            .map { it.name.formatEnumToTitle() }
            .toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_set_status)
            .setItems(labels) { _, index ->
                lifecycleScope.launch {
                    val hashes = items.mapNotNull { recordOptionsDialog.ensureRecordHash(it) }
                    if (hashes.size < items.size && !historyPolicy.canRecord()) {
                        Toast.makeText(this@HomeActivity, R.string.history_action_blocked, Toast.LENGTH_SHORT).show()
                    }
                    pdfRepository.setReadingBatch(hashes, ReadingStatus.entries[index])
                    selectionController.finish()
                    refresh()
                }
            }
            .show()
    }

    private fun hideBatch(items: List<HomeItem>) {
        lifecycleScope.launch {
            val hashes = items.mapNotNull { recordOptionsDialog.ensureRecordHash(it) }
            if (hashes.size < items.size && !historyPolicy.canRecord()) {
                Toast.makeText(this@HomeActivity, R.string.history_action_blocked, Toast.LENGTH_SHORT).show()
            }
            hashes.forEach { pdfRepository.setHidden(it, true) }
            selectionController.finish()
            refresh()
        }
    }

    private fun deleteBatch(items: List<HomeItem>) {
        val containsManagedLibraryPdf = items.any { item ->
            val file = item.uri.takeIf { it.scheme == "file" }?.path?.let(::File)
            file != null && sourceIndexSnapshot.libraryKindFor(
                file,
                PhoneRenderQueue.defaultCanonicalPdfDirectory(),
            ) != null
        }
        if (containsManagedLibraryPdf) {
            Toast.makeText(
                this,
                R.string.home_remove_from_library_one_at_a_time,
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_dialog_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.home_delete_batch_confirm_message, items.size, items.size
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        items.filter { item ->
                            if (item.uri.scheme == "file") {
                                item.uri.path?.let { File(it).delete() } ?: false
                            } else {
                                runCatching {
                                    DocumentsContract.deleteDocument(contentResolver, item.uri)
                                }.getOrDefault(false)
                            }
                        }
                    }
                    deleted.forEach { item ->
                        historyCleaner.deleteDocument(item.hash)
                        coverCache.invalidate(item.coverKey)
                    }
                    if (deleted.size < items.size) {
                        Toast.makeText(this@HomeActivity, R.string.home_delete_failed, Toast.LENGTH_SHORT).show()
                    }
                    selectionController.finish()
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val GLOBAL_SEARCH_INDEX_DELAY_MS = 5_000L
        private const val TAG = "HomePerformance"
        private const val STARTUP_TAG = "ThoughtsStartup"
        private const val COMMENT_SNAPSHOT_CACHE = "comment-catalogue-v1.json"
        const val EXTRA_FROM_HOME = "fromHome"
        const val EXTRA_SHOW_NOTES = "showNotes"
        const val EXTRA_RECORD_HASH = "recordHash"
        const val EXTRA_RELOCATE_HASH = "relocateHash"
        const val EXTRA_COMMENT_ID = "overviewCommentId"
        const val EXTRA_COMMENT_PAGE = "overviewCommentPage"
        const val EXTRA_LOGICAL_COMMENT_STATUS = "overviewLogicalCommentStatus"
        const val EXTRA_LOGICAL_COMMENT_POINT = "overviewLogicalCommentPoint"
        const val EXTRA_LOGICAL_COMMENT_REGIONS = "overviewLogicalCommentRegions"
        const val EXTRA_LOGICAL_COMMENT_CONTENTS = "overviewLogicalCommentContents"
        const val EXTRA_REVIEW_CARD_ID = "pdfReviewCardId"
        const val EXTRA_REVIEW_PDF_STEM = "pdfReviewPdfStem"
        const val EXTRA_CITATION_MESSAGE = "citationMessage"
        private const val COMMENT_REFRESH_DEBOUNCE_MS = 120L
        private const val REFRESH_COALESCE_MS = 16L
        private const val REVIEW_LOAD_PRIORITY_POLL_MS = 32L
        private const val RENDER_RECEIPT_DEBOUNCE_MS = 250L
        private const val PENDING_CHANGES_DEBOUNCE_MS = 100L
        private const val RENDER_STATUS_ACTIVE_POLL_MS = 1_000L
        private const val RENDER_STATUS_IDLE_POLL_MS = 10_000L
        /** With nothing written, Home still reads its whole status once in this time. */
        private const val RENDER_STATUS_IDLE_BACKSTOP_MS = 5 * 60_000L
        private const val OTHER_PENDING_CHANGES_TARGET = "Other changes"
        private const val STATE_REVIEW_QA_EXPORT_PDF_STEM = "reviewQaExportPdfStem"
        private const val STATE_REVIEW_QA_EXPORT_APPEND_SOURCE = "reviewQaExportAppendSource"
        private const val STATE_HOME_SEARCH_EXPANDED = "homeSearchExpanded"
    }
}

private data class PendingDocumentSyncCounts(
    var comments: Int = 0,
    var cards: Int = 0,
    var events: Int = 0,
    var other: Int = 0,
    var libraryRemoval: Boolean = false,
    val libraryChanges: MutableList<String> = mutableListOf(),
    val proposalDetails: MutableList<String> = mutableListOf(),
    var latestEpochMillis: Long = 0,
    var blocked: Boolean = false,
    /** Changes the server refused; they block the row like [blocked], under their own Sync status. */
    var refused: Int = 0,
    val failures: MutableList<String> = mutableListOf(),
)

internal fun completedReceiptsContainNoChanges(
    receipts: Collection<PhoneRenderReceipt>,
): Boolean = receipts.any { it.succeeded && it.changesDetected == false }

internal fun completedReceiptsRequireLibraryRefresh(
    receipts: Collection<PhoneRenderReceipt>,
): Boolean = receipts.any { it.succeeded && it.changesDetected != false }
