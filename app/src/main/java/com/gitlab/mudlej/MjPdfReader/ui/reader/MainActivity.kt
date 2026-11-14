// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNotesListActivity
import com.gitlab.mudlej.MjPdfReader.ui.agentnotes.StudyModeControls
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderHistoryManager
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.util.Patterns
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.AdapterView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.debug.MainThreadStallWatchdog
import com.gitlab.mudlej.MjPdfReader.core.ui.confirmDialog
import com.gitlab.mudlej.MjPdfReader.data.*
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PdfWarmCacheWorker
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.logicalReadScope
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.highlightedAgentProposals
import com.gitlab.mudlej.MjPdfReader.data.sync.agentProposalDecisionStates
import com.gitlab.mudlej.MjPdfReader.ui.usernotes.AgentProposalDecider
import com.gitlab.mudlej.MjPdfReader.data.sync.locallyAcceptedProposalIds
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.data.sync.ArtifactSyncCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.ArtifactSyncPlan
import com.gitlab.mudlej.MjPdfReader.data.sync.ArtifactSyncStateStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncCommandCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsNetworkPolicy
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferCoordinator
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncTransferStateStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PasswordDialogBinding
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewGroupNoteLines
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardDetailsActivity
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardReaderContract
import com.gitlab.mudlej.MjPdfReader.ui.source.ProposalSourceActivity
import com.gitlab.mudlej.MjPdfReader.ui.review.boundedReviewBrowserPageIndex
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.core.io.imageShareIntent
import com.gitlab.mudlej.MjPdfReader.core.io.plainTextShareIntent
import com.gitlab.mudlej.MjPdfReader.core.io.publicDownloadsCopy
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.ColorUtil
import com.gitlab.mudlej.MjPdfReader.core.ui.showOptionalIcons
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.PdfPropertiesSummary
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentUnreachableException
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.LogicalCommentMarkerOpenTarget
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.logicalCommentMarkerOpenTarget
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.logicalCommentOverlaySpecs
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.commentPageSizes
import com.gitlab.mudlej.MjPdfReader.ui.share.PdfShareController
import com.gitlab.mudlej.MjPdfReader.ui.share.thoughtsCitationContextFor
import com.gitlab.mudlej.MjPdfReader.ui.links.ThoughtsCitationLinkActivity
import com.gitlab.mudlej.MjPdfReader.ui.links.EXTRA_REMOTE_PDF_LINK
import com.gitlab.mudlej.MjPdfReader.ui.links.parseRemotePdfLink
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.AgentNotePlace
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.EXTRA_AGENT_NOTE_PLACE
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderNoteHistory
import com.gitlab.mudlej.MjPdfReader.ui.sync.showSyncTransferPrompt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.shockwave.pdfium.PdfPasswordException
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long Undo stays offered after a tap on a proposal's ✓ accepted it. */
private const val MARKER_ACCEPT_UNDO_MS = 8_000

class MainActivity : AppCompatActivity(), ReaderUi, DocumentTabsHost {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var pref: Preferences
    private lateinit var reader: ReaderComposition
    private lateinit var openDocumentTabsController: OpenDocumentTabsController
    private lateinit var reviewSessionController: ReviewSessionController
    /** Liked and disliked passages of the document read normally (document_feedback records). */
    private lateinit var documentFeedbackController: DocumentFeedbackController
    /** The PDF | HTML switch and the HTML view over the PDF (thoughts-html-view-SPEC.md). */
    internal lateinit var htmlReader: HtmlReaderController
    internal val htmlReaderOrNull: HtmlReaderController? get() = if (::htmlReader.isInitialized) htmlReader else null
    /** HTML attachment pages over the reader, their cards and reviews (thoughts-html-attachment-cards-SPEC.md). */
    internal lateinit var attachmentReader: HtmlAttachmentReaderController
    internal val attachmentReaderOrNull: HtmlAttachmentReaderController? get() = if (::attachmentReader.isInitialized) attachmentReader else null
    internal val attachmentCardStatus by lazy { com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCardStatus.of(this) }

    private val vm: ReaderViewModel by viewModels()
    private val pdf: DocumentState get() = vm.doc
    /** Study mode: the agent-note tools for the page on screen. */
    private lateinit var studyPageBar: com.gitlab.mudlej.MjPdfReader.ui.agentnotes.StudyPageBar
    private var selectedAgentProposalId: String? = null
    private var activeLogicalCommentMarkerId: String? = null
    private var logicalCommentOverlayJob: Job? = null
    private val agentProposalRequestGate = ReaderAsyncRequestGate()
    private var agentProposalDecisionRows: List<ThoughtsOutboxEntity> = emptyList()
    private var agentProposalOverlayJob: Job? = null
    private var agentProposalLoadingFilename: String? = null
    private var loadedAgentProposalFilename: String? = null
    private var loadedAgentProposals: List<OfflineAgentProposal> = emptyList()
    /** Accepted in the source editor here; see [locallyAcceptedProposalIds]. */
    private var locallyAcceptedAgentProposalIds: Set<String> = emptySet()

    private lateinit var actionBarMenu: Menu
    private lateinit var appTitle: TextView
    private lateinit var appTitlePageNumber: TextView

    private var doubleBackToExitPressedOnce = false
    private var savingProgressVisible = false
    private var taskDescriptionName: String? = null
    private var readerDisplayTitle: String? = null
    private val stallWatchdog by lazy { MainThreadStallWatchdog() }
    private var pdfCommentTouchInterceptor: ((MotionEvent) -> Boolean)? = null
    private var statusReceiverRegistered = false
    private var overviewCommentOpenJob: Job? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ArtifactSyncCoordinator.ACTION_STATE_CHANGED -> {
                    if (intent.getBooleanExtra(ArtifactSyncCoordinator.EXTRA_SHOW_PROMPT, false)) {
                        ArtifactSyncStateStore(this@MainActivity).load()?.let(::showArtifactSyncPrompt)
                    }
                }
                ArtifactSyncCoordinator.ACTION_PDFS_ACTIVATED -> {
                    reader.onVerifiedPdfArtifactsActivated(
                        intent.getStringArrayListExtra(ArtifactSyncCoordinator.EXTRA_PDF_FILENAMES)
                            ?.toSet()
                            .orEmpty(),
                    )
                    applyLogicalCommentOverlays()
                }
                SyncTransferCoordinator.ACTION_STATE_CHANGED -> {
                    if (intent.getBooleanExtra(SyncTransferCoordinator.EXTRA_SHOW_PROMPT, false)) {
                        SyncTransferStateStore(this@MainActivity).load()?.let {
                            showSyncTransferPrompt(this@MainActivity, it)
                        }
                    }
                }
                SyncOperationStore.ACTION_STATE_CHANGED -> Unit
                com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionSyncWorker.ACTION_EDITIONS_UPDATED -> {
                    if (::htmlReader.isInitialized) htmlReader.onEditionsUpdated()
                }
                ThoughtsSyncScheduler.ACTION_FINISHED -> {
                    reader.onVerifiedPdfArtifactsActivated()
                    applyLogicalCommentOverlays()
                    if (::htmlReader.isInitialized) htmlReader.onLibraryChanged()
                    // A Sync can replace only the proposal metadata and keep the PDF (an Accept);
                    // the proposals marked on the page must follow it without a reload.
                    if (loadedAgentProposalFilename != null) loadAgentProposalOverlays(force = true, keepVisible = true)
                    if (::reviewSessionController.isInitialized) reviewSessionController.onSyncFinished()
                    // Feedback made on another device, or settled by this Sync.
                    if (::documentFeedbackController.isInitialized) documentFeedbackController.refresh()
                }
            }
            if (::reader.isInitialized) reader.readerMenu.refreshSyncState()
        }
    }

    private val annotationController get() = reader.annotationController
    private val annotationSaveController get() = reader.annotationSaveController
    private val signatureController get() = reader.signatureController
    private val cropMarginsController get() = reader.cropMarginsController
    private val documentLoader get() = reader.documentLoader
    private val readerNavigationController get() = reader.readerNavigationController
    private val readerHistory get() = reader.readerHistory
    private val fullscreenController get() = reader.fullscreenController
    private val inlineAnnotationActionController get() = reader.inlineAnnotationActionController
    private val pdfRepository get() = reader.pdfRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PdfWarmCacheWorker.cancel(this)
        pref = Preferences(PreferenceManager.getDefaultSharedPreferences(this))

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        openDocumentTabsController = OpenDocumentTabsController(
            this,
            this,
            binding.openDocumentTabs,
            readerPlace = ::agentReaderPlace,
        ) {
            val place = agentReaderPlace()
            CurrentDocumentSnapshot(uri = pdf.uri?.toString(), title = pdf.name,
                titleTarget = if (pdf.uri == intent.data) ReaderTitleTarget(
                    intent.getStringExtra(EXTRA_DISPLAY_DOCUMENT_ID), intent.getStringExtra(EXTRA_DISPLAY_PROJECT_ID),
                    intent.getStringExtra(EXTRA_DISPLAY_TITLE)) else ReaderTitleTarget(),
                pdfDocumentId = pdf.documentId, page = place.page, pages = place.pages)
        }
        setCustomActionBar()
        ColorUtil.colorize(this, window, supportActionBar)

        // To avoid FileUriExposedException, (https://stackoverflow.com/questions/38200282/)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())

        reader = ReaderComposition(this, binding, vm, pref)
        htmlReader = HtmlReaderController(this, binding, { pdf })
        attachmentReader = HtmlAttachmentReaderController(this, binding, { pdf })
        reviewSessionController = ReviewSessionController(this, binding, pdf)
        reviewSessionController.restoreState(savedInstanceState)
        studyPageBar = com.gitlab.mudlej.MjPdfReader.ui.agentnotes.StudyPageBar(this, binding.studyPageBar,
            documentOpen = { pdf.uri != null }, currentPage = ::studyPageOnScreen)
        inlineAnnotationActionController.reviewFeedback = object : com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReviewFeedbackActions {
            // Through the field: tests may swap the controller.
            override fun qualifies(request: com.github.barteksc.pdfviewer.PDFView.HighlightRequest) =
                reviewSessionController.feedbackActions.qualifies(request)
            override fun give(request: com.github.barteksc.pdfviewer.PDFView.HighlightRequest,
                              action: com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReviewFeedbackActions.Action) =
                reviewSessionController.feedbackActions.give(request, action)
            override fun open(feedbackId: String) = reviewSessionController.feedbackActions.open(feedbackId)
            override fun isChangingPassage() = reviewSessionController.feedbackActions.isChangingPassage()
            override fun savePassage(request: com.github.barteksc.pdfviewer.PDFView.HighlightRequest) =
                reviewSessionController.feedbackActions.savePassage(request)
            override fun cancelPassageChange() = reviewSessionController.feedbackActions.cancelPassageChange()
        }
        documentFeedbackController = DocumentFeedbackController(this, binding, { pdf }, { ReviewStore(this).originIdentity() }) {
            // An earlier version opened from History: the document it belongs to and its own version.
            val id = intent.getStringExtra(DocumentHistoryController.EXTRA_HISTORY_DOCUMENT_ID)
            val sha = intent.getStringExtra(DocumentHistoryController.EXTRA_HISTORY_VERSION_SHA)
            if (id != null && sha != null) id to sha else null
        }
        inlineAnnotationActionController.documentFeedback = object : com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReviewFeedbackActions {
            // Through the field: tests may swap the controller.
            override fun qualifies(request: com.github.barteksc.pdfviewer.PDFView.HighlightRequest) =
                documentFeedbackController.qualifies(request)
            override fun give(request: com.github.barteksc.pdfviewer.PDFView.HighlightRequest,
                              action: com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReviewFeedbackActions.Action) =
                documentFeedbackController.give(request, action)
            override fun open(feedbackId: String) = documentFeedbackController.open(feedbackId)
            override fun isChangingPassage() = documentFeedbackController.isChangingPassage()
            override fun savePassage(request: com.github.barteksc.pdfviewer.PDFView.HighlightRequest) =
                documentFeedbackController.savePassage(request)
            override fun cancelPassageChange() = documentFeedbackController.cancelPassageChange()
        }
        documentLoader.subscribe(object : com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentListener {
            override fun onDocumentReset() = documentFeedbackController.onDocumentReset()
            override fun onDocumentLoaded(event: com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentLoadedEvent) {
                documentFeedbackController.onDocumentLoaded()
                // An earlier version opened from History shows the page of the comment that was tapped.
                val page = intent.getIntExtra(DocumentHistoryController.EXTRA_HISTORY_PAGE, 0)
                if (page > 0) {
                    intent.removeExtra(DocumentHistoryController.EXTRA_HISTORY_PAGE)
                    val area = intent.getFloatArrayExtra(DocumentHistoryController.EXTRA_HISTORY_AREA)
                    intent.removeExtra(DocumentHistoryController.EXTRA_HISTORY_AREA)
                    if (page <= binding.pdfView.pageCount) documentHistory.showPassage(page, area)
                }
            }
            override fun onPageChanged(pageIndex: Int) = documentFeedbackController.onPageChanged(pageIndex)
        })
        wireManualReviewEditor()
        keepDocumentEndAboveBottomCards()
        documentLoader.applyTileRenderingPreferences()

        openInitialDocument(savedInstanceState)
        reader.wireViews()
        describeAgentProposalMarkers()
        lifecycleScope.launch {
            AppDatabase.getInstance(applicationContext).thoughtsSyncDao()
                .observeAgentProposalDecisions().collect { rows ->
                    agentProposalDecisionRows = rows
                    loadedAgentProposalFilename?.let { renderAgentProposalOverlays(it, loadedAgentProposals) }
                }
        }
        overrideOnBackButtonPressed()
    }

    private fun openInitialDocument(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState)
        }
        else {
            val intentUri = intent.data
            if (intentUri == null) {
                if (!intent.getBooleanExtra(ReaderSessionNavigation.EXTRA_SHOW_EMPTY_VIEWER, false)) {
                    reader.pickFile()
                }
            } else {
                documentLoader.prepareNewDocument(intentUri)
                // A new tab for an agent note's link: Back shows the note again.
                takeAgentNotePlace(intent)?.let(readerHistory::recordAgentNote)
            }
        }

        displayFromUri(pdf.uri, true)
    }

    /** The agent note an intent's link was followed from, taken off the intent so it counts once. */
    private fun takeAgentNotePlace(intent: Intent): AgentNotePlace? =
        AgentNotePlace.fromJson(intent.getStringExtra(EXTRA_AGENT_NOTE_PLACE)).also { intent.removeExtra(EXTRA_AGENT_NOTE_PLACE) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleReaderIntent(intent)
    }

    /**
     * Handles an intent for this tab, delivered by Android or, for links followed in the reader,
     * passed directly so that the linked document always opens in this same tab.
     */
    internal fun handleReaderIntent(intent: Intent) {
        setIntent(intent)
        refreshOpenDocumentTabs()
        val citationUri = intent.data?.takeIf {
            intent.hasExtra(ThoughtsCitationLinkActivity.EXTRA_CITATION_PAGE_INDEX) ||
                intent.hasExtra(EXTRA_REMOTE_PDF_LINK) ||
                intent.hasExtra(com.gitlab.mudlej.MjPdfReader.ui.reader.links.EXTRA_LINK_OVERRIDE_TARGET)
        }
        if (citationUri != null) {
            // A link of an agent note: the note goes in this tab's history, so Back shows it again.
            val fromNote = takeAgentNotePlace(intent)
            if (pdf.uri == citationUri) {
                if (fromNote == null) {
                    readerHistory.recordJump(ReaderHistoryManager.Origin.LINK)
                } else {
                    readerHistory.recordAgentNoteLink(fromNote, sameDocument = true,
                        parseRemotePdfLink(intent.getStringExtra(EXTRA_REMOTE_PDF_LINK))?.pageIndex)
                }
                binding.root.post {
                    openCitationPageIfRequested()
                    openRemotePdfLinkIfRequested()
                    openHtmlLinkDestinationIfRequested()
                    openLinkOverrideTargetIfRequested()
                }
            } else {
                runAfterDirtyAnnotationPrompt {
                    // A link to another document is a history step: Back returns here, at the link.
                    if (fromNote != null) {
                        readerHistory.recordAgentNoteLink(fromNote, sameDocument = false, null)
                    } else if (pdf.uri != null) {
                        readerHistory.recordLeavingDocument(ReaderHistoryManager.Origin.LINK)
                    }
                    readerHistory.keepingHistory { documentLoader.prepareNewDocument(citationUri) }
                    displayFromUri(citationUri, true)
                }
            }
            return
        }
        val reviewUri = intent.data?.takeIf {
            intent.hasExtra(HomeActivity.EXTRA_REVIEW_CARD_ID)
        }
        if (reviewUri != null) {
            reviewSessionController.beginReviewSession()
            if (pdf.uri == reviewUri) {
                binding.root.post(::openReviewCardIfRequested)
            } else {
                runAfterDirtyAnnotationPrompt {
                    documentLoader.prepareNewDocument(reviewUri)
                    displayFromUri(reviewUri, true)
                }
            }
            return
        }
        val reviewBrowserUri = intent.data?.takeIf {
            intent.hasExtra(ReviewCardReaderContract.EXTRA_PAGE_INDEX)
        }
        if (reviewBrowserUri != null) {
            if (pdf.uri == reviewBrowserUri) {
                binding.root.post(::openReviewBrowserPageIfRequested)
            } else {
                runAfterDirtyAnnotationPrompt {
                    documentLoader.prepareNewDocument(reviewBrowserUri)
                    displayFromUri(reviewBrowserUri, true)
                }
            }
            return
        }
        val overviewUri = intent.data?.takeIf {
            intent.hasExtra(HomeActivity.EXTRA_COMMENT_ID)
        }
        if (overviewUri != null) {
            if (pdf.uri == overviewUri) {
                openOverviewCommentWhenReady()
            } else {
                runAfterDirtyAnnotationPrompt {
                    documentLoader.prepareNewDocument(overviewUri)
                    displayFromUri(overviewUri, true)
                }
            }
            return
        }
    }

    override fun onStart() {
        super.onStart()
        returnFromAgentNote()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter().apply {
                addAction(ArtifactSyncCoordinator.ACTION_STATE_CHANGED)
                addAction(ArtifactSyncCoordinator.ACTION_PDFS_ACTIVATED)
                addAction(SyncOperationStore.ACTION_STATE_CHANGED)
                addAction(ThoughtsSyncScheduler.ACTION_FINISHED)
                addAction(com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionSyncWorker.ACTION_EDITIONS_UPDATED)
                addAction(SyncTransferCoordinator.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        statusReceiverRegistered = true
        // The receiver misses a Sync that finished while the reader was stopped.
        if (::reader.isInitialized) reader.onVerifiedPdfArtifactsActivated()
    }

    /** Reloads the open Library PDF when Sync has installed a newer verified version. */
    internal fun reloadVerifiedPdfUpdate() {
        if (::reader.isInitialized) reader.onVerifiedPdfArtifactsActivated()
    }

    fun isDisplayingUri(uri: String): Boolean {
        return pdf.uri?.toString() == uri
    }

    fun openOverviewCommentIfRequested() {
        if (!tryOpenOverviewCommentIfRequested()) {
            openOverviewCommentWhenReady()
        }
    }

    private fun tryOpenOverviewCommentIfRequested(): Boolean {
        val commentId = intent.getStringExtra(HomeActivity.EXTRA_COMMENT_ID) ?: return false
        val logicalStatus = intent.getStringExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_STATUS)
        if (logicalStatus != null) {
            if (pdf.annotationPdfSha256 == null || binding.pdfView.pageCount == 0) return false
            focusLogicalCommentFromStore(commentId)
            consumeOverviewCommentRequest()
            return true
        }
        binding.pdfView.clearLogicalCommentMarker()
        activeLogicalCommentMarkerId = null
        intent.removeExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_CONTENTS)
        if (selectAgentProposal(commentId.removePrefix("agent-remove-"))) {
            val page = intent.getIntExtra(HomeActivity.EXTRA_COMMENT_PAGE, -1)
            if (page in 0 until binding.pdfView.pageCount) binding.pdfView.jumpTo(page)
            reader.readerNavigationController.showAgentProposal(commentId)
            consumeOverviewCommentRequest()
            return true
        }
        if (inlineAnnotationActionController.openPdfComment(commentId)) {
            consumeOverviewCommentRequest()
            return true
        }
        return false
    }

    private fun consumeOverviewCommentRequest() {
        intent.removeExtra(HomeActivity.EXTRA_COMMENT_ID)
        intent.removeExtra(HomeActivity.EXTRA_COMMENT_PAGE)
        intent.removeExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_STATUS)
        intent.removeExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_POINT)
        intent.removeExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_REGIONS)
        intent.removeExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_CONTENTS)
    }

    private fun openOverviewCommentWhenReady() {
        overviewCommentOpenJob?.cancel()
        val requestedId = intent.getStringExtra(HomeActivity.EXTRA_COMMENT_ID) ?: return
        overviewCommentOpenJob = lifecycleScope.launch {
            ThoughtsSyncScheduler.enqueueBackground(applicationContext)
            val retryDelays = longArrayOf(0L, 250L, 750L, 1_500L, 2_500L)
            for (retryDelay in retryDelays) {
                if (intent.getStringExtra(HomeActivity.EXTRA_COMMENT_ID) != requestedId) {
                    return@launch
                }
                if (retryDelay > 0) {
                    delay(retryDelay)
                }
                annotationController.refreshCanonicalComments(pdf.name)
                if (tryOpenOverviewCommentIfRequested()) {
                    return@launch
                }
            }
            val fallbackPage = intent.getIntExtra(HomeActivity.EXTRA_COMMENT_PAGE, 0)
                .coerceIn(0, (binding.pdfView.pageCount - 1).coerceAtLeast(0))
            binding.pdfView.jumpTo(fallbackPage)
            AppSnackbar.make(
                binding.root,
                R.string.overview_comment_not_ready,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    internal fun previewCommentFromPanel(
        comment: com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment,
        isProposal: Boolean,
    ) {
        if (comment.pageIndex !in 0 until binding.pdfView.pageCount) return
        binding.pdfView.jumpTo(comment.pageIndex)
        if (isProposal) {
            val id = comment.id.removePrefix("agent-remove-")
            selectAgentProposal(id)
            val proposal = loadedAgentProposals.firstOrNull { it.id == id } ?: return
            val quads = proposal.quads.filter { it.pdfFilename == pdf.name }
            // Focus the source's own occurrence, not a contents entry or running head LaTeX copied.
            val primary = quads.filterNot { it.duplicate }.ifEmpty { quads }
            val first = primary.firstOrNull { it.role == "after" } ?: primary.firstOrNull() ?: return
            val page = first.page - 1
            if (page !in 0 until binding.pdfView.pageCount) return
            val size = binding.pdfView.getPagePointSize(page) ?: return
            val regions = primary.filter { it.page == first.page && it.role == first.role }.map { quad ->
                val width = if (quad.normalized) size.width else 1f
                val height = if (quad.normalized) size.height else 1f
                android.graphics.RectF(quad.left * width, size.height - quad.bottom * height,
                    quad.right * width, size.height - quad.top * height)
            }
            val bounds = android.graphics.RectF(regions.first()).apply { regions.drop(1).forEach { union(it) } }
            binding.pdfView.jumpTo(page)
            binding.pdfView.focusOnPdfRect(page, bounds,
                binding.pdfView.zoom)
        } else if (comment.logicalPayloadJson.isNotBlank()) {
            selectLogicalCommentMarker(comment.id, comment.pageIndex)
        }
    }

    internal fun openCommentThreadFromPanel(
        comment: com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment,
    ): Boolean {
        if (inlineAnnotationActionController.openPdfComment(comment.id)) return true
        val hash = pdf.annotationPdfSha256
        if (comment.logicalPayloadJson.isNotBlank() && hash != null) {
            val child = pdf.projectReading?.locate(comment.pageIndex)
            inlineAnnotationActionController.openLogicalComment(comment.id, child?.filename ?: pdf.name, child?.reading?.pdfSha256 ?: hash)
            return true
        }
        AppSnackbar.make(binding.root, R.string.overview_comment_not_ready, Snackbar.LENGTH_LONG).show()
        return false
    }

    internal fun openCommentFromList(
        commentId: String,
        pageIndex: Int,
        logicalStatus: String? = null,
        logicalPoint: FloatArray? = null,
        logicalRegions: FloatArray? = null,
        logicalContents: String? = null,
    ) {
        intent.putExtra(HomeActivity.EXTRA_COMMENT_ID, commentId)
        intent.putExtra(HomeActivity.EXTRA_COMMENT_PAGE, pageIndex)
        intent.putExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_STATUS, logicalStatus)
        intent.putExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_POINT, logicalPoint)
        intent.putExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_REGIONS, logicalRegions)
        intent.putExtra(HomeActivity.EXTRA_LOGICAL_COMMENT_CONTENTS, logicalContents)
        if (!tryOpenOverviewCommentIfRequested()) {
            openOverviewCommentWhenReady()
        }
    }

    internal fun handleLogicalCommentMarkerTap(event: MotionEvent): Boolean {
        val commentId = binding.pdfView.logicalCommentAt(event.x, event.y)
            ?: activeLogicalCommentMarkerId?.takeIf {
                binding.pdfView.hitLogicalCommentMarker(event)
            }
            ?: return false
        if (activeLogicalCommentMarkerId != commentId) {
            activeLogicalCommentMarkerId = commentId
            applyLogicalCommentOverlays()
        }
        val pdfThreadOpened = inlineAnnotationActionController.openPdfComment(
            commentId,
            event.downTime,
        )
        when (logicalCommentMarkerOpenTarget(commentId, pdfThreadOpened)) {
            LogicalCommentMarkerOpenTarget.PDF_THREAD -> Unit
            LogicalCommentMarkerOpenTarget.LOGICAL_THREAD -> {
                val pdfSha256 = pdf.annotationPdfSha256
                if (pdfSha256 == null) {
                    AppSnackbar.make(
                        binding.root,
                        R.string.overview_comment_not_ready,
                        Snackbar.LENGTH_LONG,
                    ).show()
                } else {
                    inlineAnnotationActionController.openLogicalComment(
                        commentId,
                        pdf.name,
                        pdfSha256,
                    )
                }
            }
            LogicalCommentMarkerOpenTarget.UNAVAILABLE -> {
                AppSnackbar.make(
                    binding.root,
                    R.string.overview_comment_not_ready,
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
        return true
    }

    internal fun commentDocumentHash(): String? = pdf.annotationPdfSha256

    private val highlightPreferences by lazy { getSharedPreferences("pdf_highlights", MODE_PRIVATE) }
    private var reviewHighlightsJob: Job? = null
    /** The Review Cards sheet shows every card's area, as the desktop does, whatever the Highlights option says. */
    private var reviewCardsSheetOpen = false

    internal fun setReviewCardsSheetOpen(open: Boolean) {
        if (reviewCardsSheetOpen == open) return
        reviewCardsSheetOpen = open
        applyHighlightOptions()
    }

    internal fun enableReviewMarkup() {
        highlightPreferences.edit().putBoolean("comments", true).putBoolean("agent_edits", true).apply()
        applyHighlightOptions()
    }

    internal fun showHighlightOptions() {
        val keys = arrayOf("comments", "agent_edits", "review")
        val labels = arrayOf(getString(R.string.comments_title), getString(R.string.agent_proposal_type), getString(R.string.pdf_highlights_review))
        val checked = keys.map { highlightPreferences.getBoolean(it, it != "review") }.toBooleanArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.pdf_highlights)
            .setMultiChoiceItems(labels, checked) { _, index, enabled ->
                highlightPreferences.edit().putBoolean(keys[index], enabled).apply()
                applyHighlightOptions()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun applyHighlightOptions() {
        binding.pdfView.setHighlightVisibility(
            highlightPreferences.getBoolean("comments", true),
            highlightPreferences.getBoolean("agent_edits", true),
        )
        reviewHighlightsJob?.cancel()
        binding.pdfView.setReviewHighlights(emptyMap())
        binding.pdfView.setReviewCardMarkers(emptyList()) { cardId ->
            startActivity(ReviewCardDetailsActivity.intent(this, cardId))
        }
        if (!highlightPreferences.getBoolean("review", false) && !reviewCardsSheetOpen) return
        val filename = pdf.name
        val reading = pdf.projectReading
        val hash = pdf.annotationPdfSha256 ?: return
        reviewHighlightsJob = lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) {
                // A card made in the HTML view has no PDF area until Fedora links it ("Placed at next Sync").
                documentReviewCards(ReviewStore(applicationContext).browserCards(), filename, reading, hash)
                    .filterNot { it.card.htmlPending }
            }
            if (pdf.name == filename && pdf.annotationPdfSha256 == hash) {
                fun offset(stem: String): Int = documentReviewPageOffset(reading, stem)
                val regions = cards.flatMap { item ->
                    item.card.regions.groupBy { it.page - 1 + offset(item.card.pdfStem) }.map { (page, values) -> page to values.map { it.rect } }
                }.groupBy({ it.first }, { it.second })
                binding.pdfView.setReviewHighlights(regions)
                binding.pdfView.setReviewCardMarkers(cards.flatMap { item ->
                    item.card.regions.groupBy { it.page - 1 + offset(item.card.pdfStem) }.map { (page, regions) ->
                        val first = regions.minWith(compareBy({ it.rect.top }, { it.rect.left }))
                        com.github.barteksc.pdfviewer.PDFView.ReviewCardMarker(item.card.id, page, first.rect)
                    }
                }) { cardId -> startActivity(ReviewCardDetailsActivity.intent(this@MainActivity, cardId)) }
            }
        }
    }

    /**
     * The Review Cards sheet: this document's cards in page order, and apart its retired cards,
     * which stay on the PDF version they were retired on.
     */
    internal suspend fun loadDocumentReviewCards(): ReviewCardsSheetContent {
        val filename = pdf.name
        val reading = pdf.projectReading
        val hash = pdf.annotationPdfSha256 ?: return ReviewCardsSheetContent(emptyList())
        return withContext(Dispatchers.IO) {
            val all = ReviewStore(applicationContext).browserCards()
            val retired = retiredDocumentReviewCards(all, filename, reading).map { item ->
                ReviewCardsSheetItem(
                    cardId = item.card.id,
                    question = item.card.question,
                    pageIndex = (item.card.pdfPage ?: 1) - 1 + documentReviewPageOffset(reading, item.card.pdfStem),
                    metadata = reviewCardsSheetMetadata(this@MainActivity, 0, item.currentTags(), item.dueAt,
                        item.reviewCount, retiredAt = item.card.retiredAt,
                        place = getString(R.string.review_cards_sheet_retired_place)),
                    reviewable = false,
                    retired = true,
                )
            }
            val cards = documentReviewCards(all, filename, reading, hash)
                .map { item ->
                    // A card made in the HTML view has no page until Fedora links it: listed first.
                    val pageIndex = (item.card.pdfPage ?: 1) - 1 + documentReviewPageOffset(reading, item.card.pdfStem)
                    val firstRegion = item.card.regions.minWithOrNull(compareBy({ it.page }, { it.rect.top }, { it.rect.left }))
                    Triple(item, pageIndex, firstRegion?.rect?.top ?: 0f)
                }
                .sortedWith(compareBy({ it.second }, { it.third }))
                .map { (item, pageIndex, _) ->
                    val attachment = item.card.htmlAttachment
                    ReviewCardsSheetItem(
                        cardId = item.card.id,
                        // A card made in the HTML view has no PDF area until Fedora links it.
                        regions = if (item.card.htmlPending) emptyMap() else item.card.regions
                            .groupBy({ it.page - 1 + documentReviewPageOffset(reading, item.card.pdfStem) }, { it.rect }),
                        question = item.card.question,
                        pageIndex = pageIndex,
                        metadata = reviewCardsSheetMetadata(this@MainActivity, pageIndex, item.currentTags(),
                            item.dueAt, item.reviewCount,
                            groupNotes = reviewGroupNoteLines(this@MainActivity, item.currentGroupNotes()),
                            retiredAt = item.card.retiredAt,
                            place = attachment?.let { anchor ->
                                if (attachmentCardStatus.notSent(item.card.id) != null) getString(R.string.html_attachment_card_not_sent)
                                else if (attachmentCardStatus.isNotFound(item.card.id)) getString(R.string.html_attachment_answer_not_found)
                                else getString(R.string.html_attachment_card_placement, anchor.path.substringAfterLast('/'))
                            }),
                        reviewable = attachment != null || item.card.pdfSha256.equals(hash, ignoreCase = true),
                    )
                }
            ReviewCardsSheetContent(cards, retired)
        }
    }

    /** A retired card picked in the Review Cards sheet: it has no place on this PDF, so its details. */
    internal fun openRetiredReviewCard(item: ReviewCardsSheetItem) {
        startActivity(ReviewCardDetailsActivity.intent(this, item.cardId))
    }

    /**
     * Highlights the answer of a card picked in the Review Cards sheet and brings it into the space
     * above the sheet, as the desktop sidebar does; null clears it when the sheet closes.
     */
    internal fun highlightReviewCardFromSheet(item: ReviewCardsSheetItem?) {
        if (item == null) {
            binding.pdfView.clearSelectedReviewHighlight()
            return
        }
        binding.pdfView.setSelectedReviewHighlight(item.regions)
        val first = item.regions.entries.filter { it.key in 0 until binding.pdfView.pageCount && it.value.isNotEmpty() }
            .minByOrNull { it.key }
        if (first == null) {
            if (item.pageIndex in 0 until binding.pdfView.pageCount) binding.pdfView.jumpTo(item.pageIndex)
            return
        }
        val bounds = android.graphics.RectF(first.value.first()).apply { first.value.drop(1).forEach { union(it) } }
        binding.pdfView.focusOnReviewRegion(first.key, bounds, binding.pdfView.height.toFloat())
    }

    /** Reviews a card picked in the Review Cards sheet here; the session goes on with this PDF's due cards. */
    internal fun reviewCardFromSheet(item: ReviewCardsSheetItem) {
        if (!item.reviewable) {
            binding.pdfView.jumpTo(item.pageIndex)
            return
        }
        intent.removeExtra(ReviewCardReaderContract.EXTRA_SINGLE_CARD_SESSION)
        intent.putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, item.cardId)
        pdf.name.removeSuffix(".pdf").takeIf { it.isNotEmpty() }?.let { intent.putExtra(HomeActivity.EXTRA_REVIEW_PDF_STEM, it) }
        reviewSessionController.beginReviewSession(inPlace = true)
        reviewSessionController.openIfRequested()
    }

    /** [loaded] is a snapshot the caller just built for this document, which saves building another. */
    internal fun applyLogicalCommentOverlays(loaded: com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot? = null) {
        applyHighlightOptions()
        val reading = pdf.projectReading
        val filename = pdf.name.takeIf { reading != null || it.endsWith(".pdf", ignoreCase = true) }
        val pdfSha256 = pdf.annotationPdfSha256
        logicalCommentOverlayJob?.cancel()
        if (filename == null || pdfSha256 == null) {
            binding.pdfView.setLogicalCommentOverlays(emptyList())
            return
        }
        val pageSizes = binding.pdfView.commentPageSizes()
        val selectedId = activeLogicalCommentMarkerId
        logicalCommentOverlayJob = lifecycleScope.launch {
            val specs = withContext(Dispatchers.IO) {
                val snapshot = loaded ?: com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(applicationContext).snapshot(if (reading == null) filename else null)
                if (reading != null) reading.documents.flatMap { document ->
                    logicalCommentOverlaySpecs(snapshot.logicalComments, document.filename, document.reading.pdfSha256, selectedId,
                        pageSize = { pageSizes[it + document.reading.firstPage] })
                        .map { it.copy(pageIndex = it.pageIndex + document.reading.firstPage) }
                } else logicalCommentOverlaySpecs(
                    snapshot.logicalComments,
                    filename,
                    pdfSha256,
                    selectedId,
                    pageSize = { pageSizes[it] },
                )
            }
            if (pdf.name != filename || pdf.annotationPdfSha256 != pdfSha256) {
                return@launch
            }
            if (activeLogicalCommentMarkerId != null && specs.none { it.commentId == activeLogicalCommentMarkerId }) {
                activeLogicalCommentMarkerId = null
                binding.pdfView.clearLogicalCommentMarker()
            }
            binding.pdfView.setLogicalCommentOverlays(
                specs.map { overlay ->
                    com.github.barteksc.pdfviewer.PDFView.LogicalCommentOverlay(
                        overlay.commentId,
                        overlay.pageIndex,
                        overlay.left,
                        overlay.top,
                        overlay.right,
                        overlay.bottom,
                        overlay.selected,
                        overlay.isPoint,
                        overlay.sourceX ?: Float.NaN,
                        overlay.sourceY ?: Float.NaN,
                        overlay.hasThread,
                        overlay.color,
                    ).also { it.subtype = overlay.subtype }
                },
            )
        }
    }

    internal fun selectLogicalCommentMarker(commentId: String, pageIndex: Int) {
        activeLogicalCommentMarkerId = commentId
        binding.pdfView.jumpTo(pageIndex)
        applyLogicalCommentOverlays()
    }

    private fun focusLogicalCommentFromStore(commentId: String) {
        val filename = pdf.name
        val hash = pdf.annotationPdfSha256 ?: return
        val reading = pdf.projectReading
        val pageSizes = binding.pdfView.commentPageSizes()
        binding.pdfView.clearLogicalCommentMarker()
        activeLogicalCommentMarkerId = null
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(applicationContext).snapshot(if (reading == null) filename else null)
            }
            if (pdf.name != filename || pdf.annotationPdfSha256 != hash) return@launch
            val comment = snapshot.logicalComments.firstOrNull { it.id == commentId && !it.deleted }
            val root = comment?.let { selected -> snapshot.logicalComments.firstOrNull { it.id == selected.threadRootId && !it.deleted } }
            if (root == null) {
                applyLogicalCommentOverlays()
                AppSnackbar.make(binding.root, R.string.overview_comment_not_ready, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val documents = reading?.documents?.map { Triple(it.filename, it.reading.pdfSha256, it.reading.firstPage) }
                ?: listOf(Triple(filename, hash, 0))
            for ((childFilename, childHash, offset) in documents) {
                val marker = logicalCommentOverlaySpecs(snapshot.logicalComments, childFilename, childHash, root.id,
                    pageSize = { pageSizes[it + offset] }).firstOrNull { it.commentId == root.id }
                if (marker != null) {
                    selectLogicalCommentMarker(root.id, marker.pageIndex + offset)
                    return@launch
                }
                val location = root.locations.firstOrNull { it.pdfFilename == childFilename && it.pdfSha256 == childHash && it.status == "page_only" }
                if (location != null) {
                    binding.pdfView.jumpTo(location.pageIndex + offset)
                    inlineAnnotationActionController.openLogicalComment(commentId, childFilename, childHash)
                    return@launch
                }
            }
            if (reading == null) inlineAnnotationActionController.openLogicalComment(commentId, filename, hash)
            else AppSnackbar.make(binding.root, R.string.overview_comment_not_ready, Snackbar.LENGTH_LONG).show()
        }
    }

    internal fun applyAgentProposalOverlays() {
        loadAgentProposalOverlays(force = true)
    }

    internal fun resetAgentProposalOverlays() {
        agentProposalRequestGate.invalidate()
        agentProposalOverlayJob?.cancel()
        agentProposalOverlayJob = null
        agentProposalLoadingFilename = null
        loadedAgentProposalFilename = null
        loadedAgentProposals = emptyList()
        locallyAcceptedAgentProposalIds = emptySet()
        selectedAgentProposalId = null
        binding.pdfView.setAgentProposalOverlays(emptyList())
    }

    /**
     * After the source editor closes: an Accept saved there stops marking its proposal at once,
     * since the page already shows the accepted text (2026-10-10: they stayed marked until a
     * rebuilt PDF arrived with a later Sync).
     */
    private fun refreshLocallyAcceptedAgentProposals() {
        val filename = loadedAgentProposalFilename ?: return
        val proposals = loadedAgentProposals
        if (proposals.isEmpty()) return
        val requestToken = agentProposalRequestGate.current()
        lifecycleScope.launch {
            val editor = withContext(Dispatchers.IO) {
                runCatching {
                    SourceEditQueue(applicationContext, scheduleRequests = false)
                        .editorProposalDecisions(proposals.map { it.sourceFile })
                }.getOrNull()
            } ?: return@launch
            if (!agentProposalRequestGate.isCurrent(requestToken) || loadedAgentProposalFilename != filename) return@launch
            val updated = locallyAcceptedProposalIds(
                locallyAcceptedAgentProposalIds, editor, proposals.mapTo(hashSetOf()) { it.id },
            )
            if (updated == locallyAcceptedAgentProposalIds) return@launch
            locallyAcceptedAgentProposalIds = updated
            renderAgentProposalOverlays(filename, loadedAgentProposals)
        }
    }

    private fun loadAgentProposalOverlays(force: Boolean, keepVisible: Boolean = false) {
        val filename = pdf.name.takeIf { it.endsWith(".pdf", ignoreCase = true) || pdf.projectReading != null }
        if (filename == null) {
            resetAgentProposalOverlays()
            return
        }
        if (!force) {
            if (loadedAgentProposalFilename == filename) return
            if (agentProposalLoadingFilename == filename && agentProposalOverlayJob?.isActive == true) {
                return
            }
        }
        val documentUri = pdf.uri
        val loadToken = vm.currentLoadToken
        val requestToken = agentProposalRequestGate.begin()
        agentProposalOverlayJob?.cancel()
        agentProposalLoadingFilename = filename
        // A refresh of the same document keeps its marks until the new list replaces them.
        if (!(keepVisible && loadedAgentProposalFilename == filename)) {
            loadedAgentProposalFilename = null
            loadedAgentProposals = emptyList()
            binding.pdfView.setAgentProposalOverlays(emptyList())
        }
        agentProposalOverlayJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = OfflineSourceBundleStore(applicationContext)
                    val reading = pdf.projectReading
                    if (reading == null) store.agentProposals(filename)
                    else reading.documents.flatMap { document ->
                        if (store.librarySourceMetadata(document.filename)?.pdfs?.get(document.filename)?.pdfSha256 != document.reading.pdfSha256) return@flatMap emptyList()
                        store.agentProposals(document.filename).map { proposal ->
                            proposal.copy(pdfFilenames = listOf(filename), quads = proposal.quads
                                .filter { it.pdfFilename == document.filename }
                                .map { it.copy(pdfFilename = filename, page = it.page + document.reading.firstPage) })
                        }
                    }.groupBy { it.id }.values.map { entries -> entries.first().copy(quads = entries.flatMap { it.quads }) }
                }.map { proposals ->
                    proposals to runCatching {
                        SourceEditQueue(applicationContext, scheduleRequests = false)
                            .editorProposalDecisions(proposals.map { it.sourceFile })
                    }.getOrNull()
                }
            }
            if (
                !agentProposalRequestGate.isCurrent(requestToken) ||
                !vm.isCurrent(loadToken, documentUri) ||
                pdf.name != filename
            ) {
                return@launch
            }
            agentProposalOverlayJob = null
            agentProposalLoadingFilename = null
            result.onFailure { error ->
                Log.w(TAG, "Unable to load agent proposal overlays for $filename", error)
                binding.pdfView.setAgentProposalOverlays(emptyList())
                return@launch
            }
            val (proposals, editor) = result.getOrThrow()
            loadedAgentProposalFilename = filename
            loadedAgentProposals = proposals
            locallyAcceptedAgentProposalIds = locallyAcceptedProposalIds(
                locallyAcceptedAgentProposalIds,
                editor ?: com.gitlab.mudlej.MjPdfReader.data.source.EditorProposalDecisions(emptySet(), emptySet()),
                proposals.mapTo(hashSetOf()) { it.id },
            )
            if (loadedAgentProposals.none { it.id == selectedAgentProposalId }) {
                selectedAgentProposalId = null
            }
            renderAgentProposalOverlays(filename, loadedAgentProposals)
        }
    }

    private fun renderAgentProposalOverlays(
        filename: String,
        proposals: List<OfflineAgentProposal>,
    ) {
        if (pdf.name != filename || loadedAgentProposalFilename != filename) return
        val overlays = highlightedAgentProposals(proposals, agentProposalDecisionRows)
            .filter { it.id !in locallyAcceptedAgentProposalIds }
            .flatMap { proposal ->
            proposal.quads.filter { it.pdfFilename == filename && it.page - 1 in 0 until binding.pdfView.pageCount }.mapNotNull { quad ->
                val pageSize = binding.pdfView.getPagePointSize(quad.page - 1) ?: return@mapNotNull null
                val width = pageSize.width.takeIf { it > 0f } ?: 1f
                val height = pageSize.height.takeIf { it > 0f } ?: 1f
                com.github.barteksc.pdfviewer.PDFView.AgentProposalOverlay(
                    proposal.id,
                    quad.role,
                    quad.page - 1,
                    if (quad.normalized) quad.left * width else quad.left,
                    if (quad.normalized) quad.top * height else quad.top,
                    if (quad.normalized) quad.right * width else quad.right,
                    if (quad.normalized) quad.bottom * height else quad.bottom,
                    proposal.id == selectedAgentProposalId,
                    quad.duplicate,
                )
            }
        }
        binding.pdfView.setAgentProposalOverlays(overlays)
    }

    /**
     * A tap on a proposal's ✓ accepts it, as Accept in its sheet does; one that cannot be decided
     * here opens in the sheet, which says why. Only the ✓ acts: highlighted text keeps normal text
     * interaction.
     */
    internal fun handleAgentProposalTap(event: MotionEvent): Boolean {
        val proposalId = binding.pdfView.agentProposalMarkerAt(event.x, event.y) ?: return false
        val proposal = loadedAgentProposal(proposalId) ?: return false
        binding.pdfView.clearTextSelection()
        // A tap that stopped the page while it still moved, or saw it move, only stops the page.
        if (!binding.pdfView.isStillMarkerTap()) return true
        acceptAgentProposalFromMarker(proposal)
        return true
    }

    /** A long press on a proposal's ✓ opens it in the sheet, with all its options. */
    internal fun handleAgentProposalLongPress(event: MotionEvent): Boolean {
        val proposalId = binding.pdfView.agentProposalMarkerAt(event.x, event.y) ?: return false
        val proposal = loadedAgentProposal(proposalId) ?: return false
        binding.pdfView.clearTextSelection()
        openAgentProposal(proposal)
        return true
    }

    /** What the ✓ marks say to accessibility services, and what their actions do. */
    private fun describeAgentProposalMarkers() {
        binding.pdfView.setAgentProposalMarkerAccessibility(
            getString(R.string.agent_proposal_marker_description),
            object : com.github.barteksc.pdfviewer.PDFView.AgentProposalMarkerActions {
                override fun onAgentProposalMarkerTap(proposalId: String): Boolean =
                    loadedAgentProposal(proposalId)?.let { acceptAgentProposalFromMarker(it); true } ?: false

                override fun onAgentProposalMarkerLongPress(proposalId: String): Boolean =
                    loadedAgentProposal(proposalId)?.let { openAgentProposal(it); true } ?: false
            },
        )
    }

    private fun loadedAgentProposal(proposalId: String): OfflineAgentProposal? {
        val filename = pdf.name.takeIf { it.endsWith(".pdf", ignoreCase = true) || pdf.projectReading != null } ?: return null
        if (loadedAgentProposalFilename != filename) return null
        return loadedAgentProposals.firstOrNull { it.id == proposalId }
    }

    private fun openAgentProposal(proposal: OfflineAgentProposal) {
        val filename = loadedAgentProposalFilename ?: return
        selectedAgentProposalId = proposal.id
        renderAgentProposalOverlays(filename, loadedAgentProposals)
        reader.readerNavigationController.showAgentProposal(proposal.id)
    }

    /** Proposals whose ✓ was tapped and whose accept is being checked and queued (one at a time each). */
    private val acceptingFromMarker = mutableSetOf<String>()

    /**
     * Accepts [proposal] the way its sheet's Accept does (the same checks, queued for Sync, which
     * sends it to Fedora), then offers Undo; one that cannot be decided here opens in the sheet.
     */
    private fun acceptAgentProposalFromMarker(proposal: OfflineAgentProposal) {
        if (!acceptingFromMarker.add(proposal.id)) return
        val filename = loadedAgentProposalFilename
        lifecycleScope.launch {
            val operationId = try {
                val check = withContext(Dispatchers.IO) {
                    runCatching { AgentProposalDecider.checkSource(applicationContext, proposal) }.getOrNull()
                }
                val decision = agentProposalDecisionStates(agentProposalDecisionRows, proposal.bundleId)[proposal.id]
                if (check != null && proposal.id !in locallyAcceptedAgentProposalIds &&
                    AgentProposalDecider.decidable(proposal.objectSha256, check.excerpt != null, check.changed, decision)) {
                    withContext(Dispatchers.IO) { AgentProposalDecider.decide(applicationContext, proposal, "accept") }
                } else null
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not accept the proposal from its marker", error)
                AppSnackbar.make(binding.root, getString(R.string.agent_proposal_failed, error.message.orEmpty()),
                    Snackbar.LENGTH_LONG).show()
                return@launch
            } finally {
                acceptingFromMarker.remove(proposal.id)
            }
            if (operationId == null) {
                // Cannot be decided here (source changed or missing, a decision waiting): the sheet says why.
                if (loadedAgentProposalFilename == filename && loadedAgentProposal(proposal.id) != null) openAgentProposal(proposal)
                return@launch
            }
            // The decision rows update the page: the ✓ goes once the accept is queued.
            // Long enough to reach Undo after seeing the ✓ go.
            AppSnackbar.make(binding.root, R.string.agent_proposal_accepted_on_page, Snackbar.LENGTH_LONG)
                .setDuration(MARKER_ACCEPT_UNDO_MS)
                .setAction(R.string.overview_undo) { undoAgentProposalAccept(operationId) }
                .show()
        }
    }

    /** Undo after a ✓: the existing undo of a resolution, queued after the accept, so Fedora takes it back in order. */
    private fun undoAgentProposalAccept(operationId: String) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { AgentProposalDecider.undo(applicationContext, operationId) } }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.w(TAG, "Could not undo the proposal accept", error)
                    AppSnackbar.make(binding.root, getString(R.string.agent_proposal_undo_failed, error.message.orEmpty()),
                        Snackbar.LENGTH_LONG).show()
                }
        }
    }

    private fun selectAgentProposal(proposalId: String): Boolean {
        val filename = pdf.name.takeIf { it.endsWith(".pdf", ignoreCase = true) || pdf.projectReading != null } ?: return false
        if (loadedAgentProposalFilename != filename) {
            loadAgentProposalOverlays(force = false)
            return false
        }
        if (loadedAgentProposals.none { it.id == proposalId }) {
            return false
        }
        selectedAgentProposalId = proposalId
        renderAgentProposalOverlays(filename, loadedAgentProposals)
        return true
    }

    fun openReviewCardIfRequested() {
        reviewSessionController.openIfRequested()
    }

    fun openReviewBrowserPageIfRequested() {
        if (!intent.hasExtra(ReviewCardReaderContract.EXTRA_PAGE_INDEX)) return
        val targetPageIndex = boundedReviewBrowserPageIndex(
            intent.getIntExtra(ReviewCardReaderContract.EXTRA_PAGE_INDEX, 0),
            binding.pdfView.pageCount,
        ) ?: return
        intent.removeExtra(ReviewCardReaderContract.EXTRA_PAGE_INDEX)
        binding.pdfView.jumpTo(targetPageIndex)
        // Global search: highlight the matched word on that page with the reader's own search.
        val highlight = intent.getStringExtra(EXTRA_SEARCH_HIGHLIGHT)?.trim().orEmpty()
        intent.removeExtra(EXTRA_SEARCH_HIGHLIGHT)
        if (highlight.isNotEmpty()) readerNavigationController.search(highlight, targetPageIndex + 1)
    }

    /**
     * Shows a history place of another document in this tab, at its saved page, zoom and scroll.
     * [commit] moves the history to that place; it runs only if the document is really replaced.
     */
    internal fun openHistoryDocument(entry: ReaderHistoryManager.Entry, commit: () -> Unit) {
        val uri = entry.document?.let(Uri::parse) ?: return
        runAfterDirtyAnnotationPrompt {
            commit()
            readerHistory.keepingHistory { documentLoader.prepareNewDocument(uri) }
            vm.setPage(entry.pageIndex)
            vm.pendingViewState = entry.viewState
            displayFromUri(uri, true)
        }
    }

    /**
     * A Back or Forward step reached the agent note a link was followed from: the notes window
     * shows it as it was left, with the arrows that go on from it in this tab's history.
     */
    internal fun showAgentNoteFromHistory(entry: ReaderHistoryManager.Entry, canGoBack: Boolean, canGoForward: Boolean): Boolean {
        val note = entry.note ?: return false
        dismissPdfCommentSheet()
        ReaderNoteHistory.show(ReaderNoteHistory.Shown(taskId, note, canGoBack, canGoForward))
        return try {
            startActivity(com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNoteActivity.historyIntent(this, note))
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not show the agent note of the history", error)
            ReaderNoteHistory.returned(taskId)
            false
        }
    }

    /**
     * This tab is shown again: if a history step had shown an agent note in its place, carry out
     * the arrow pressed on the note (if any). Results of activities arrive after onStart, so a step
     * made from the history list is not undone before the note shows.
     */
    private fun returnFromAgentNote() {
        if (!::reader.isInitialized) return
        readerHistory.returnFromAgentNote(ReaderNoteHistory.returned(taskId))
    }

    fun openRemotePdfLinkIfRequested() {
        val link = parseRemotePdfLink(intent.getStringExtra(EXTRA_REMOTE_PDF_LINK)) ?: return
        intent.removeExtra(EXTRA_REMOTE_PDF_LINK)
        val pageIndex = boundedReviewBrowserPageIndex(link.pageIndex, binding.pdfView.pageCount) ?: return
        val destination = com.shockwave.pdfium.PdfDocument.Link(android.graphics.RectF(), pageIndex, null,
            android.graphics.PointF(link.x, link.y))
        if (!binding.pdfView.focusOnLinkDestination(destination)) binding.pdfView.jumpTo(pageIndex)
    }

    /** A document link followed in the HTML view: its named destination (doc-anchor or source label), when the PDF has it. */
    fun openHtmlLinkDestinationIfRequested() {
        val destination = intent.getStringExtra(HtmlReaderController.EXTRA_HTML_LINK_DESTINATION) ?: return
        intent.removeExtra(HtmlReaderController.EXTRA_HTML_LINK_DESTINATION)
        runCatching { binding.pdfView.focusOnNamedDestination(destination) }
    }

    // ---- The HTML view's hooks into the reader (HtmlReaderController) ----------------------

    /** Pending proposals marked on this PDF; the HTML view does not show them (phase 5). */
    internal fun pendingProposalCount(): Int = if (loadedAgentProposalFilename == pdf.name) loadedAgentProposals.size else 0

    internal fun inlineAnnotationActionsHidden() {
        inlineAnnotationActionController.hideActions()
        binding.pdfView.clearTextSelection()
    }

    /** The PDF or HTML view was switched: a card under review moves to the view shown. */
    internal fun reviewViewChanged() {
        if (::reviewSessionController.isInitialized) reviewSessionController.onViewModeChanged()
    }

    internal fun openLogicalCommentFromHtml(commentId: String, filename: String, pdfSha256: String) =
        inlineAnnotationActionController.openLogicalComment(commentId, filename, pdfSha256)

    internal fun openReviewFeedback(feedbackId: String) = reviewSessionController.feedbackActions.open(feedbackId)

    internal fun readAloud(text: String) = reader.readAloudBarController.read(text)

    internal fun giveHtmlReviewFeedback(quote: com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel.TextQuote,
                                        htmlEditionSha256: String, action: HtmlReaderController.ReviewFeedbackAction) =
        reviewSessionController.giveHtmlFeedback(quote, htmlEditionSha256, action)

    /** A selection in the HTML view becomes a card: the same editor as a PDF selection, saved by [createManualReviewCard]. */
    private var htmlCardSelection: Pair<com.google.gson.JsonObject, String>? = null

    internal fun startHtmlReviewCard(fields: com.google.gson.JsonObject, selectedText: String) {
        if (binding.manualReviewEditorCard.visibility == View.VISIBLE || pdf.annotationPdfSha256 == null) return
        dismissManualReviewEditor()
        htmlCardSelection = fields to selectedText
        resetManualReviewFields(R.string.selection_review_instructions)
        // One passage per card from the HTML view: Fedora places it from its quote.
        binding.manualReviewAnotherAreaButton.visibility = View.GONE
    }

    private fun createHtmlReviewCard(question: String, firstDueDays: Long, firstDueAt: Instant?) {
        val (fields, selectedText) = htmlCardSelection ?: return
        val filename = pdf.name
        val hash = pdf.annotationPdfSha256 ?: return
        binding.manualReviewAddButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val card = ReviewStore(applicationContext).createHtmlCard(
                        pdfStem = filename.removeSuffix(".pdf"), pdfSha256 = hash, question = question, html = fields,
                        selectedText = selectedText,
                        documentId = pdf.documentId.orEmpty(), firstDueDays = firstDueDays, firstDueAt = firstDueAt,
                    ).getOrThrow()
                    runCatching { FedoraRepository(applicationContext).captureReviewCards() }
                        .onFailure { Log.w(TAG, "HTML review card remains durable for the next sync", it) }
                    card
                }
            }
            result.onSuccess {
                dismissManualReviewEditor()
                ThoughtsSyncScheduler.enqueueBackground(applicationContext)
                AppSnackbar.make(binding.root, R.string.html_view_card_saved, Snackbar.LENGTH_LONG).show()
                htmlReader.onLibraryChanged()
            }.onFailure { error ->
                binding.manualReviewAddButton.isEnabled = true
                AppSnackbar.make(binding.root, getString(R.string.manual_review_failed,
                    error.message.orEmpty().ifBlank { "Unknown error" }), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * A selection inside an HTML attachment page becomes a card of [owner] (the document whose
     * link opened the page): the same editor, saved by [createAttachmentReviewCard].
     */
    private var attachmentCardSelection: Triple<com.google.gson.JsonObject, com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.Owner, String>? = null

    internal fun startAttachmentReviewCard(fields: com.google.gson.JsonObject,
                                           owner: com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.Owner, selectedText: String) {
        if (binding.manualReviewEditorCard.visibility == View.VISIBLE || !owner.valid) return
        if (!com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards.validFields(fields)) return
        dismissManualReviewEditor()
        attachmentCardSelection = Triple(fields, owner, selectedText)
        resetManualReviewFields(R.string.selection_review_instructions)
        // One passage per card, anchored by its text in the page.
        binding.manualReviewAnotherAreaButton.visibility = View.GONE
    }

    private fun createAttachmentReviewCard(question: String, firstDueDays: Long, firstDueAt: Instant?) {
        val (fields, owner, selectedText) = attachmentCardSelection ?: return
        binding.manualReviewAddButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val card = ReviewStore(applicationContext).createHtmlAttachmentCard(
                        pdfStem = owner.pdfStem, pdfSha256 = owner.pdfSha256, question = question, attachment = fields,
                        selectedText = selectedText, documentId = owner.documentId,
                        firstDueDays = firstDueDays, firstDueAt = firstDueAt,
                    ).getOrThrow()
                    runCatching { FedoraRepository(applicationContext).captureReviewCards() }
                        .onFailure { Log.w(TAG, "Attachment review card remains durable for the next sync", it) }
                    card
                }
            }
            result.onSuccess {
                dismissManualReviewEditor()
                ThoughtsSyncScheduler.enqueueBackground(applicationContext)
                AppSnackbar.make(binding.root, R.string.html_attachment_card_saved, Snackbar.LENGTH_LONG).show()
                attachmentReader.refreshItems()
            }.onFailure { error ->
                binding.manualReviewAddButton.isEnabled = true
                AppSnackbar.make(binding.root, getString(R.string.manual_review_failed,
                    error.message.orEmpty().ifBlank { "Unknown error" }), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** A link override opens its Library document at the first page. */
    fun openLinkOverrideTargetIfRequested() {
        val extra = com.gitlab.mudlej.MjPdfReader.ui.reader.links.EXTRA_LINK_OVERRIDE_TARGET
        if (!intent.hasExtra(extra)) return
        intent.removeExtra(extra)
        binding.pdfView.jumpTo(0)
    }

    fun openCitationPageIfRequested() {
        if (!intent.hasExtra(ThoughtsCitationLinkActivity.EXTRA_CITATION_PAGE_INDEX)) return
        val requestedPageIndex = intent.getIntExtra(
            ThoughtsCitationLinkActivity.EXTRA_CITATION_PAGE_INDEX,
            0,
        )
        val versionChanged = intent.getBooleanExtra(
            ThoughtsCitationLinkActivity.EXTRA_CITATION_VERSION_CHANGED,
            false,
        )
        intent.removeExtra(ThoughtsCitationLinkActivity.EXTRA_CITATION_PAGE_INDEX)
        intent.removeExtra(ThoughtsCitationLinkActivity.EXTRA_CITATION_VERSION_CHANGED)

        val lastPageIndex = (binding.pdfView.pageCount - 1).coerceAtLeast(0)
        val targetPageIndex = requestedPageIndex.coerceIn(0, lastPageIndex)
        binding.pdfView.jumpTo(targetPageIndex)
        val message = when {
            versionChanged && requestedPageIndex != targetPageIndex -> getString(
                R.string.citation_page_clamped_and_version_changed,
                requestedPageIndex + 1,
                targetPageIndex + 1,
            )
            versionChanged -> getString(
                R.string.citation_version_changed,
                targetPageIndex + 1,
            )
            requestedPageIndex != targetPageIndex -> getString(
                R.string.citation_page_clamped,
                requestedPageIndex + 1,
                targetPageIndex + 1,
            )
            else -> null
        }
        message?.let {
            AppSnackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
        }
    }

    fun resetReviewSessionForDocument() {
        reviewSessionController.onDocumentReset()
    }

    private fun setCustomActionBar() {
        val actionBar = supportActionBar
        // Disable the default and enable the custom
        actionBar?.setDisplayShowTitleEnabled(false)
        actionBar?.setDisplayShowCustomEnabled(true)
        actionBar?.elevation = 0F

        updateHomeUpIndicator()

        val customView: View = layoutInflater.inflate(R.layout.actionbar_title, null)
        appTitlePageNumber = customView.findViewById(R.id.actionbarPageNumber)
        appTitle = customView.findViewById(R.id.actionbarTitle)

        fun titleClickListener() {
            val title = pdf.getTitle()
            if (title.isNotBlank()) {
                AppSnackbar.make(binding.root, title, Snackbar.LENGTH_LONG).show()
            }
        }
        appTitle.setOnClickListener { titleClickListener() }
        appTitlePageNumber.setOnClickListener { titleClickListener() }
        customView.findViewById<View>(R.id.actionbarAgentNotes).setOnClickListener {
            dismissPdfCommentSheet()
            startActivity(AgentNotesListActivity.intent(this))
        }

        // Apply the custom view
        actionBar?.customView = customView
    }

    /** The document on screen; in a Project reading, the document on the current page. */
    internal fun currentStatusDocument(): DocumentStatusDocument {
        val child = pdf.projectReading?.locate(pdf.pageNumber)
        return DocumentStatusDocument(
            filename = child?.filename ?: pdf.name,
            uri = pdf.uri,
            pdfSha256 = child?.reading?.pdfSha256 ?: pdf.annotationPdfSha256,
        )
    }

    private val documentStatusDialog by lazy { DocumentStatusDialog(this) }

    /** The reader menu's Study mode tile: the same controls as the Home menu. */
    internal fun showStudyMode() = StudyModeControls.showPanel(this, pref)

    private val documentHistory by lazy { DocumentHistoryController(this, binding) }

    /** History in the menu: the document's versions with their comments. */
    internal fun showDocumentHistory() = documentHistory.show(pdf, intent)

    internal fun showDocumentStatus() {
        val document = currentStatusDocument()
        if (!document.filename.endsWith(".pdf", ignoreCase = true)) return
        documentStatusDialog.show(document)
    }

    /**
     * Rebuilds the open-document tabs so an in-place document swap (for example, advancing to the
     * next review card in another PDF) renames the current tab instead of leaving the previous title.
     */
    internal fun refreshOpenDocumentTabs() {
        if (::openDocumentTabsController.isInitialized) {
            openDocumentTabsController.refresh(vm.isFullScreenToggled)
        }
    }

    private fun updateHomeUpIndicator() {
        val actionBar = supportActionBar
        val homeEnabled = !pref.getHomeDisabled()
        actionBar?.setDisplayHomeAsUpEnabled(homeEnabled)
        if (homeEnabled) {
            actionBar?.setHomeAsUpIndicator(R.drawable.ic_home)
        }
    }

    override fun runAfterDirtyAnnotationPrompt(discardAction: () -> Unit) {
        if (!annotationController.hasUnsavedAnnotations) {
            discardAction()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_highlights)
            .setMessage(R.string.unsaved_highlights_prompt)
            .setPositiveButton(R.string.save_highlights) { _, _ ->
                annotationSaveController.saveHighlights(postSaveAction = discardAction)
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                clearUnsavedAnnotationState()
                discardAction()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    internal fun runAfterAnnotationSaveGate(action: () -> Unit) {
        if (!annotationController.hasUnsavedAnnotations) {
            action()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_highlights)
            .setMessage(R.string.unsaved_highlights_prompt)
            .setPositiveButton(R.string.save_highlights) { _, _ ->
                annotationSaveController.saveHighlights(postSaveAction = action)
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                clearUnsavedAnnotationState()
                reloadPdf()
                action()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun clearUnsavedAnnotationState() {
        signatureController.cancelPlacement()
        annotationSaveController.clearPendingRequests()
        annotationController.clearJournal()
        updateDirtyUi()
    }

    internal fun confirmDiscardAnnotations() {
        confirmDialog(
            this,
            R.string.discard_unsaved_highlights_title,
            getString(R.string.discard_unsaved_highlights_message),
            R.string.discard,
        ) {
            clearUnsavedAnnotationState()
            reloadPdf()
        }
    }

    fun displayFromUri(uri: Uri?, savePassword: Boolean = false) {
        dismissManualReviewEditor()
        documentLoader.displayFromUri(uri, savePassword)
    }

    // A review in progress does not block adding a card: the editor takes the review card's
    // place at the bottom and gives it back when it closes.
    internal fun canCreateManualReviewCard(): Boolean =
        binding.manualReviewEditorCard.visibility != View.VISIBLE && currentManualReviewDocument() != null
    private var reviewCardHiddenForEditor = false

    private var manualReviewTextSelection: com.github.barteksc.pdfviewer.PDFView.HighlightRequest? = null
    private var manualReviewCustomDueAt: Instant? = null
    // Areas kept with "Add another area"; pages are 1-based in the displayed PDF, like the selection.
    private val manualReviewKeptRegions = mutableListOf<com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion>()
    private val manualReviewKeptTexts = mutableListOf<String>()
    private var manualReviewKeptAreas = 0
    // The same kept areas one by one, each with its own selected text, for linking separate passages.
    private val manualReviewKeptAreaGroups = mutableListOf<Pair<List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion>, String?>>()
    // After an area could not be linked: every area is a box to adjust (Adjust areas), with the reason shown.
    private var manualReviewAreaSession: com.gitlab.mudlej.MjPdfReader.data.review.ReviewAreaEditSession? = null
    private var manualReviewAreaProblem: String? = null

    /** Opening the editor for the next area keeps the question and due date typed so far. */
    private fun resetManualReviewFields(instructions: Int) {
        if (manualReviewKeptRegions.isNotEmpty()) {
            binding.manualReviewInstructions.text =
                getString(R.string.manual_review_areas_selected, manualReviewKeptAreas + 1)
        } else {
            binding.manualReviewInstructions.setText(instructions)
            binding.manualReviewQuestion.text?.clear()
            binding.manualReviewFirstDue.setSelection(2)
            manualReviewCustomDueAt = null
            binding.manualReviewCustomDueButton.setText(R.string.manual_review_choose_custom_due)
        }
        binding.manualReviewQuestionLayout.error = null
        binding.manualReviewAddButton.isEnabled = true
        if (binding.reviewCard.visibility == View.VISIBLE) {
            binding.reviewCard.visibility = View.GONE
            reviewCardHiddenForEditor = true
        }
        binding.manualReviewEditorCard.visibility = View.VISIBLE
    }

    /** The areas the editor's Add would save: kept ones plus the current selection or box. */
    private fun currentManualReviewRegions(): List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion> {
        val text = manualReviewTextSelection?.let { reviewSelectionRegions(it) { page -> binding.pdfView.getPagePointSize(page) } }.orEmpty()
        val box = binding.pdfView.getReviewRegionSelection()?.takeIf { text.isEmpty() }?.let {
            listOf(com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion(it.pageIndex + 1, it.normalizedRect))
        }.orEmpty()
        return text + box
    }

    private fun keepManualReviewAreaAndSelectNext() {
        manualReviewAreaSession?.let { session ->
            val bottom = manualReviewVisibleBottom()
            val index = binding.pdfView.addReviewRegionSelectionAt(binding.pdfView.width / 2f, bottom / 2f)
            if (index < 0) AppSnackbar.make(binding.root, R.string.review_area_not_added, Snackbar.LENGTH_SHORT).show()
            else session.added()
            updateManualReviewAreaControls()
            return
        }
        val current = currentManualReviewRegions()
        if (current.isEmpty()) {
            AppSnackbar.make(binding.root, R.string.manual_review_select_region, Snackbar.LENGTH_LONG).show()
            return
        }
        manualReviewKeptRegions += current
        manualReviewKeptAreaGroups += current to manualReviewTextSelection?.selectedText
        manualReviewKeptAreas++
        manualReviewTextSelection?.selectedText?.takeIf(String::isNotBlank)?.let(manualReviewKeptTexts::add)
        binding.pdfView.clearReviewRegionSelection()
        if (manualReviewTextSelection != null) binding.pdfView.clearTextSelection()
        manualReviewTextSelection = null
        binding.manualReviewEditorCard.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.manualReviewQuestion.windowToken, 0)
        AppSnackbar.make(binding.root, getString(R.string.manual_review_next_area, manualReviewKeptAreas),
            Snackbar.LENGTH_LONG).show()
    }

    internal fun startManualReviewFromSelection(request: com.github.barteksc.pdfviewer.PDFView.HighlightRequest) {
        if (!canCreateManualReviewCard() || request.selectedText.isBlank()) return
        val regions = reviewSelectionRegions(request) { binding.pdfView.getPagePointSize(it) }
        if (regions.isEmpty()) return
        manualReviewTextSelection = request
        inlineAnnotationActionController.hideActions()
        resetManualReviewFields(R.string.selection_review_instructions)
    }

    internal fun startManualReviewCard(viewX: Float, viewY: Float) {
        manualReviewTextSelection = null
        if (currentManualReviewDocument() == null) {
            AppSnackbar.make(binding.root, R.string.manual_review_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        inlineAnnotationActionController.hideActions()
        binding.pdfView.clearTextSelection()
        if (!binding.pdfView.startReviewRegionSelectionAt(viewX, viewY)) {
            AppSnackbar.make(binding.root, R.string.manual_review_select_region, Snackbar.LENGTH_LONG).show()
            return
        }
        resetManualReviewFields(R.string.manual_review_region_instructions)
    }

    /** The page on screen, for the study bar's page to the agent; null before the PDF has loaded. */
    private fun studyPageOnScreen(): com.gitlab.mudlej.MjPdfReader.ui.agentnotes.StudyPage? {
        val uri = pdf.uri ?: return null
        val pages = binding.pdfView.pageCount
        if (pages <= 0) return null
        val fields = runCatching { agentDocumentFields() }.getOrDefault(emptyMap())
        return com.gitlab.mudlej.MjPdfReader.ui.agentnotes.StudyPage(
            uri = uri, password = pdf.password, index = binding.pdfView.currentPage.coerceIn(0, pages - 1), pages = pages,
            documentName = fields["document_name"]?.takeIf(String::isNotBlank) ?: pdf.name,
            documentId = pdf.documentId ?: fields["document_id"]?.takeIf(String::isNotBlank),
            documentFilename = fields["pdf"]?.takeIf(String::isNotBlank),
            reviewCardId = reviewSessionController.agentCard()?.card?.id,
        )
    }

    /** The page shown, the page count and the text selection, for the tab menu's "Copy for agent". */
    private fun agentReaderPlace(): AgentReaderPlace {
        val pages = binding.pdfView.pageCount
        if (pages <= 0) return AgentReaderPlace()
        return AgentReaderPlace(page = binding.pdfView.currentPage + 1, pages = pages,
            selectedText = runCatching { binding.pdfView.getSelectedText() }.getOrNull())
    }

    /** The tab menu's "Copy for agent" text for this document, viewing 1-based [page] and no selection. */
    internal fun agentDocumentDescriptionAt(page: Int): String =
        openDocumentTabsController.currentDocumentDescription(AgentReaderPlace(page = page, pages = binding.pdfView.pageCount))

    /** The current document's placeholders for the review card prompts, with its page count as pdf_pages. */
    internal fun agentDocumentFields(): Map<String, String> =
        openDocumentTabsController.currentDocumentFields() +
            ("pdf_pages" to binding.pdfView.pageCount.takeIf { it > 0 }?.toString().orEmpty())

    /** What this reader shows, for the agent API (agent/AgentCommands.kt); main thread, null before setup. */
    internal fun agentReaderState(): com.gitlab.mudlej.MjPdfReader.agent.AgentReaderState? {
        if (!::binding.isInitialized || !::openDocumentTabsController.isInitialized) return null
        val pages = binding.pdfView.pageCount.coerceAtLeast(0)
        return com.gitlab.mudlej.MjPdfReader.agent.AgentReaderState(
            uri = pdf.uri,
            fields = runCatching { agentDocumentFields() }.getOrDefault(emptyMap()),
            page = if (pages > 0) binding.pdfView.currentPage + 1 else 0,
            pages = pages,
            review = if (::reviewSessionController.isInitialized) reviewSessionController.agentCard() else null,
        )
    }

    /** The agent API's reveal: Reveal on the review card shown; false when there is none. */
    internal fun agentRevealAnswer(): Boolean =
        ::reviewSessionController.isInitialized && reviewSessionController.agentRevealAnswer()

    /** The tab menu's "Copy for agent" text for a selection on 1-based [page], with its source lines when known. */
    internal fun agentSelectionDescription(page: Int, selectedText: String, sourceLocation: AgentSourceLocation?): String =
        openDocumentTabsController.currentDocumentDescription(
            AgentReaderPlace(page, binding.pdfView.pageCount, selectedText, sourceLocation),
        )

    private fun wireManualReviewEditor() {
        binding.manualReviewCancelButton.setOnClickListener { dismissManualReviewEditor() }
        binding.manualReviewAnotherAreaButton.setOnClickListener { keepManualReviewAreaAndSelectNext() }
        binding.manualReviewAddButton.setOnClickListener { createManualReviewCard() }
        binding.manualReviewRemoveAreaButton.setOnClickListener { removeManualReviewArea() }
        binding.manualReviewPreviousAreaButton.setOnClickListener { stepManualReviewArea(-1) }
        binding.manualReviewNextAreaButton.setOnClickListener { stepManualReviewArea(1) }
        binding.manualReviewCustomDueButton.setOnClickListener { showManualReviewCustomDuePicker() }
        binding.manualReviewFirstDue.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.manualReviewCustomDueButton.visibility = if (position == 5) View.VISIBLE else View.GONE
                if (position == 5 && manualReviewCustomDueAt == null && binding.manualReviewEditorCard.visibility == View.VISIBLE) {
                    showManualReviewCustomDuePicker()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showManualReviewCustomDuePicker() {
        val zone = ZoneId.systemDefault()
        val suggested = (manualReviewCustomDueAt ?: Instant.now().plus(2, ChronoUnit.DAYS)).atZone(zone)
        val datePicker = DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val dueAt = LocalDateTime.of(year, month + 1, day, hour, minute).atZone(zone).toInstant()
                if (!dueAt.isAfter(Instant.now())) {
                    AppSnackbar.make(binding.root, R.string.manual_review_custom_due_invalid, Snackbar.LENGTH_LONG).show()
                } else {
                    manualReviewCustomDueAt = dueAt
                    binding.manualReviewCustomDueButton.text = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(zone).format(dueAt)
                }
            }, suggested.hour, suggested.minute, android.text.format.DateFormat.is24HourFormat(this)).show()
        }, suggested.year, suggested.monthValue - 1, suggested.dayOfMonth)
        datePicker.datePicker.minDate = System.currentTimeMillis()
        datePicker.show()
    }

    private fun createManualReviewCard(placeMarkersByHand: Boolean = false, linkAtNextSync: Boolean = false) {
        val question = binding.manualReviewQuestion.text?.toString()?.trim().orEmpty()
        val customDue = binding.manualReviewFirstDue.selectedItemPosition == 5
        val firstDueAt = if (customDue) manualReviewCustomDueAt else null
        val firstDueDays = if (customDue) 0 else longArrayOf(0, 1, 2, 7, 30)[binding.manualReviewFirstDue.selectedItemPosition.coerceIn(0, 4)]
        if (question.isEmpty()) {
            binding.manualReviewQuestionLayout.error = getString(R.string.manual_review_question_required)
            binding.manualReviewQuestion.requestFocus()
            return
        }
        if (customDue && firstDueAt?.isAfter(Instant.now()) != true) {
            AppSnackbar.make(binding.root, R.string.manual_review_custom_due_invalid, Snackbar.LENGTH_LONG).show()
            return
        }
        if (htmlCardSelection != null) {
            createHtmlReviewCard(question, firstDueDays, firstDueAt)
            return
        }
        if (attachmentCardSelection != null) {
            createAttachmentReviewCard(question, firstDueDays, firstDueAt)
            return
        }
        val boxSession = manualReviewAreaSession
        // Every area as (regions, selected text), pages 1-based in the displayed PDF.
        val allAreas: List<Pair<List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion>, String?>> = if (boxSession != null) {
            val edited = runCatching { boxSession.areas(manualReviewBoxes()) }.getOrElse {
                AppSnackbar.make(binding.root, R.string.manual_review_select_region, Snackbar.LENGTH_LONG).show()
                return
            }
            edited.map { area -> area.regions.map { it.toRegion() } to area.text }
        } else emptyList()
        val selection = if (boxSession != null) null else binding.pdfView.getReviewRegionSelection()
        val textSelection = if (boxSession != null) null else manualReviewTextSelection
        val multipleAreas = if (boxSession != null) allAreas.size > 1 else manualReviewKeptRegions.isNotEmpty()
        val regions = when {
            boxSession != null -> allAreas.flatMap { it.first }
            multipleAreas -> (manualReviewKeptRegions + currentManualReviewRegions()).distinct()
            else -> textSelection?.let { reviewSelectionRegions(it) { page -> binding.pdfView.getPagePointSize(page) } }.orEmpty()
        }
        val selectedText = when {
            boxSession != null -> allAreas.mapNotNull { it.second }.filter(String::isNotBlank).joinToString("\n").ifBlank { null }
            multipleAreas -> (manualReviewKeptTexts + listOfNotNull(textSelection?.selectedText))
                .filter(String::isNotBlank).joinToString("\n").ifBlank { null }
            else -> textSelection?.selectedText
        }
        val areaGroups = when {
            boxSession != null -> if (allAreas.size > 1) allAreas else emptyList()
            multipleAreas -> manualReviewKeptAreaGroups + listOfNotNull(currentManualReviewRegions().takeIf { it.isNotEmpty() }?.let { it to textSelection?.selectedText })
            else -> emptyList()
        }
        val firstRegion = regions.firstOrNull()
        // The areas the editor shows again if one cannot be linked.
        val editorAreas = when {
            areaGroups.isNotEmpty() -> areaGroups
            firstRegion != null -> listOf(regions to selectedText)
            selection != null -> listOf(listOf(com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion(selection.pageIndex + 1, selection.normalizedRect)) to null)
            else -> emptyList()
        }
        val document = currentManualReviewDocument(firstRegion?.page?.minus(1) ?: selection?.pageIndex ?: pdf.pageNumber)
        if ((selection == null && firstRegion == null) || document == null) {
            AppSnackbar.make(binding.root, R.string.manual_review_unavailable, Snackbar.LENGTH_LONG).show()
            dismissManualReviewEditor()
            return
        }
        if (regions.any { it.page - 1 < document.firstPage || it.page - 1 - document.firstPage >= document.pageCount }) {
            AppSnackbar.make(binding.root, "Select a review card within one document.", Snackbar.LENGTH_LONG).show()
            return
        }
        binding.manualReviewQuestionLayout.error = null
        binding.manualReviewAddButton.isEnabled = false
        // Whether the document has source: null when this device cannot tell yet.
        var documentSourceAvailable: Boolean? = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (com.gitlab.mudlej.MjPdfReader.data.sync.verifiedFileCache.sha256(document.file) != document.pdfSha256) {
                        error(getString(R.string.manual_review_pdf_changed))
                    }
                    val selectedPages = regions.map { it.page - document.firstPage }.ifEmpty { listOf(selection!!.pageIndex + 1 - document.firstPage) }
                    val identity = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().forFile(document.file)
                    // A document built from source always gets a linked card: one passage per area, or nothing saved.
                    val sourceAvailable = if (identity?.get("kind")?.asString == "unlinked") false else com.gitlab.mudlej.MjPdfReader.data.source.reviewSourceAvailability(
                        com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore(applicationContext)
                            .librarySourceMetadata("${document.pdfStem}.pdf")?.pdfs.orEmpty(),
                        "${document.pdfStem}.pdf", document.pdfSha256, selectedPages,
                    )
                    documentSourceAvailable = sourceAvailable
                    val card = ReviewStore(applicationContext).createManualCard(
                        pdfStem = document.pdfStem,
                        pdfSha256 = document.pdfSha256,
                        page = (firstRegion?.page ?: (selection!!.pageIndex + 1)) - document.firstPage,
                        rect = firstRegion?.rect ?: selection!!.normalizedRect,
                        answerRegions = regions.map { it.copy(page = it.page - document.firstPage) },
                        answerAreas = areaGroups.map { (area, text) -> area.map { it.copy(page = it.page - document.firstPage) } to text },
                        sourceAvailable = sourceAvailable,
                        selectedText = selectedText,
                        question = question,
                        firstDueDays = firstDueDays,
                        firstDueAt = firstDueAt,
                        placeMarkersByHand = placeMarkersByHand,
                        linkAtNextSync = linkAtNextSync && sourceAvailable == null,
                    ).getOrThrow()
                    try {
                        FedoraRepository(applicationContext).captureReviewCards()
                    } catch (captureError: Throwable) {
                        Log.w(TAG, "Manual review card remains durable for the next sync", captureError)
                    }
                    Result.success(card)
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
            if (result.isSuccess) {
                dismissManualReviewEditor()
                ThoughtsSyncScheduler.enqueueBackground(applicationContext)
                val card = result.getOrThrow()
                val snackbar = AppSnackbar.make(binding.root,
                    if (card.sourceLinkState == "linking_failed") "Linking failed. Place the source markers manually; the card and question are saved."
                    else if (card.sourceLinkState == com.gitlab.mudlej.MjPdfReader.data.review.REVIEW_PENDING_SERVER_LINK) getString(R.string.review_link_pending_server)
                    else "Review card created", Snackbar.LENGTH_LONG)
                card.sourceAnchor?.getAsJsonArray("segments")?.firstOrNull()?.asJsonObject?.let { segment ->
                    val path = segment.get("source_file")?.asString.orEmpty()
                    val line = segment.get("line_start")?.asInt ?: 0
                    if (card.sourceLinkState == "linked" && path.isNotBlank() && line > 0) {
                        snackbar.setAction(R.string.review_card_check_source_edits) {
                            startActivity(ProposalSourceActivity.intent(this@MainActivity, path, line, ""))
                        }
                    }
                }
                snackbar.show()
            } else {
                binding.manualReviewAddButton.isEnabled = true
                val error = result.exceptionOrNull()
                if (error is com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException) {
                    showManualReviewLinkingFailure(error, editorAreas, document.firstPage,
                        sourceUnknown = documentSourceAvailable == null)
                    return@launch
                }
                AppSnackbar.make(
                    binding.root,
                    getString(
                        R.string.manual_review_failed,
                        error?.message.orEmpty().ifBlank { "Unknown error" },
                    ),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * An area of a card on a source document could not be linked, and nothing was saved. Adjust
     * areas shows every area as a box (the failing one selected) with the question and due date
     * kept; Cancel drops the card. A single area may instead be saved for markers placed by hand.
     * When this device cannot tell whether the document has source ([sourceUnknown]), "Save; link at
     * next Sync" saves the card unmarked for the server to link (or keep PDF-only).
     */
    private fun showManualReviewLinkingFailure(
        error: com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException,
        areas: List<Pair<List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion>, String?>>,
        pageOffset: Int,
        sourceUnknown: Boolean = false,
    ): androidx.appcompat.app.AlertDialog? {
        if (binding.manualReviewEditorCard.visibility != View.VISIBLE) return null
        val message = error.describe(pageOffset)
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_area_link_failed_title)
        if (sourceUnknown) {
            // A fourth choice beside Adjust, Cancel and (one area) by hand: below the message.
            val density = resources.displayMetrics.density
            val content = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
                addView(TextView(this@MainActivity).apply {
                    text = message
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                })
                addView(TextView(this@MainActivity).apply {
                    setText(R.string.review_area_link_next_sync_hint)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setPadding(0, (8 * density).toInt(), 0, 0)
                })
                addView(com.google.android.material.button.MaterialButton(this@MainActivity, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = R.id.manualReviewLinkAtNextSyncButton
                    setText(R.string.review_area_link_next_sync)
                    setOnClickListener {
                        dialog?.dismiss()
                        createManualReviewCard(linkAtNextSync = true)
                    }
                })
            }
            builder.setView(content)
        } else builder.setMessage(message)
        dialog = builder
            .setPositiveButton(R.string.review_area_link_adjust) { _, _ ->
                enterManualReviewAreaEditing(areas, error.areaIndex, message)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> dismissManualReviewEditor() }
            .apply {
                if (areas.size == 1) setNeutralButton(R.string.review_area_link_by_hand) { _, _ ->
                    createManualReviewCard(placeMarkersByHand = true)
                }
            }
            .show()
        return dialog
    }

    /** Every area of the card being added becomes a box to move, resize or remove; [focus] is selected. */
    private fun enterManualReviewAreaEditing(
        areas: List<Pair<List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion>, String?>>,
        focus: Int,
        problem: String,
    ) {
        if (areas.isEmpty() || binding.manualReviewEditorCard.visibility != View.VISIBLE) return
        val editable = areas.map { (regions, text) ->
            val stored = regions.map(com.gitlab.mudlej.MjPdfReader.data.review.ReviewAreaBox::of)
            val firstPage = stored.filter { it.page == stored.first().page }
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewEditableArea(
                com.gitlab.mudlej.MjPdfReader.data.review.ReviewAreaBox.union(firstPage), stored, text,
            )
        }
        if (manualReviewTextSelection != null) binding.pdfView.clearTextSelection()
        manualReviewTextSelection = null
        val boxes = editable.map { com.github.barteksc.pdfviewer.PDFView.ReviewRegionSelection(it.box.page - 1, it.box.toRegion().rect) }
        val selected = focus.coerceIn(0, boxes.size - 1)
        if (!binding.pdfView.startReviewRegionSelections(boxes, selected)) {
            AppSnackbar.make(binding.root, R.string.manual_review_select_region, Snackbar.LENGTH_LONG).show()
            return
        }
        manualReviewKeptRegions.clear()
        manualReviewKeptAreaGroups.clear()
        manualReviewKeptTexts.clear()
        manualReviewKeptAreas = 0
        manualReviewAreaSession = com.gitlab.mudlej.MjPdfReader.data.review.ReviewAreaEditSession(editable)
        manualReviewAreaProblem = problem + "\n" + getString(R.string.review_area_link_failed_hint)
        binding.manualReviewAreaActions.visibility = View.VISIBLE
        updateManualReviewAreaControls()
        binding.manualReviewEditorCard.post { focusManualReviewArea() }
    }

    /** The boxes of the card being added, 1-based pages of the displayed PDF. */
    private fun manualReviewBoxes(): List<com.gitlab.mudlej.MjPdfReader.data.review.ReviewAreaBox> =
        binding.pdfView.reviewRegionSelections.orEmpty().map { selection ->
            val rect = selection.normalizedRect
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewAreaBox(selection.pageIndex + 1, rect.left, rect.top, rect.right, rect.bottom)
        }

    /** Touching a box selects it: keeps "Area 2 of 3 · page 5" and Remove area in step. */
    internal fun onManualReviewAreasChanged() {
        if (manualReviewAreaSession != null) updateManualReviewAreaControls()
    }

    private fun updateManualReviewAreaControls() {
        val session = manualReviewAreaSession ?: return
        val boxes = manualReviewBoxes()
        val index = binding.pdfView.selectedReviewRegionIndex.coerceIn(0, (boxes.size - 1).coerceAtLeast(0))
        val position = boxes.getOrNull(index)?.let { getString(R.string.manual_review_area_position, index + 1, boxes.size, it.page) }
        binding.manualReviewInstructions.text = listOfNotNull(manualReviewAreaProblem, position).joinToString("\n")
        binding.manualReviewRemoveAreaButton.isEnabled = session.canRemove && boxes.size > 1
        val several = boxes.size > 1
        binding.manualReviewPreviousAreaButton.visibility = if (several) View.VISIBLE else View.GONE
        binding.manualReviewNextAreaButton.visibility = if (several) View.VISIBLE else View.GONE
    }

    private fun removeManualReviewArea() {
        val session = manualReviewAreaSession ?: return
        val index = binding.pdfView.selectedReviewRegionIndex
        if (index < 0 || !session.canRemove) return
        if (binding.pdfView.removeSelectedReviewRegion()) session.remove(index)
        updateManualReviewAreaControls()
        focusManualReviewArea()
    }

    private fun stepManualReviewArea(step: Int) {
        val count = manualReviewBoxes().size
        if (manualReviewAreaSession == null || count < 2) return
        binding.pdfView.selectReviewRegion(Math.floorMod(binding.pdfView.selectedReviewRegionIndex + step, count))
        focusManualReviewArea()
    }

    private fun focusManualReviewArea() {
        val box = manualReviewBoxes().getOrNull(binding.pdfView.selectedReviewRegionIndex) ?: return
        binding.pdfView.focusOnReviewRegion(box.page - 1, box.toRegion().rect, manualReviewVisibleBottom())
        updateManualReviewAreaControls()
    }

    /** The bottom of the PDF left visible above the card editor, in PDF view coordinates. */
    /**
     * The review card and the Add-card editor float over the bottom of the PDF. Without extra
     * scroll room, the end of the document stays hidden behind them.
     */
    private fun keepDocumentEndAboveBottomCards() {
        val gap = 12 * resources.displayMetrics.density
        val pdfLocation = IntArray(2)
        val cardLocation = IntArray(2)
        var lastInset = -1f
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            if (::studyPageBar.isInitialized) {
                studyPageBar.steppedAside = binding.reviewCard.visibility == View.VISIBLE &&
                    binding.reviewBeforePanel.visibility == View.VISIBLE &&
                    binding.reviewBeforeGroups.visibility == View.VISIBLE
                studyPageBar.inReview = binding.reviewCard.visibility == View.VISIBLE
            }
            binding.pdfView.getLocationInWindow(pdfLocation)
            val pdfBottom = pdfLocation[1] + binding.pdfView.height
            var inset = 0f
            for (card in listOf(binding.reviewCard, binding.manualReviewEditorCard)) {
                if (card.visibility != View.VISIBLE || card.height == 0) continue
                card.getLocationInWindow(cardLocation)
                inset = maxOf(inset, pdfBottom - cardLocation[1] + gap)
            }
            inset = inset.coerceIn(0f, binding.pdfView.height.toFloat())
            if (inset != lastInset) {
                lastInset = inset
                binding.pdfView.setOverlayBottomInset(inset)
            }
        }
    }

    private fun manualReviewVisibleBottom(): Float {
        val pdfLocation = IntArray(2)
        val editorLocation = IntArray(2)
        binding.pdfView.getLocationInWindow(pdfLocation)
        binding.manualReviewEditorCard.getLocationInWindow(editorLocation)
        val bottom = if (binding.manualReviewEditorCard.visibility == View.VISIBLE) editorLocation[1] - pdfLocation[1] - 12 * resources.displayMetrics.density
            else binding.pdfView.height.toFloat()
        return bottom.coerceIn(1f, binding.pdfView.height.toFloat().coerceAtLeast(1f))
    }

    private fun dismissManualReviewEditor() {
        if (!::binding.isInitialized) return
        htmlCardSelection = null
        attachmentCardSelection = null
        manualReviewAreaSession = null
        manualReviewAreaProblem = null
        binding.manualReviewAreaActions.visibility = View.GONE
        manualReviewKeptRegions.clear()
        manualReviewKeptAreaGroups.clear()
        manualReviewKeptTexts.clear()
        manualReviewKeptAreas = 0
        binding.manualReviewAnotherAreaButton.visibility = View.VISIBLE
        binding.pdfView.clearReviewRegionSelection()
        if (manualReviewTextSelection != null) binding.pdfView.clearTextSelection()
        manualReviewTextSelection = null
        binding.manualReviewEditorCard.visibility = View.GONE
        if (reviewCardHiddenForEditor) {
            reviewCardHiddenForEditor = false
            if (::reviewSessionController.isInitialized && reviewSessionController.isActive) {
                binding.reviewCard.visibility = View.VISIBLE
            }
        }
        binding.manualReviewQuestionLayout.error = null
        binding.manualReviewQuestion.text?.clear()
        binding.manualReviewAddButton.isEnabled = true
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.manualReviewQuestion.windowToken, 0)
    }

    private fun currentManualReviewDocument(page: Int = pdf.pageNumber): ManualReviewDocument? {
        pdf.projectReading?.let { reading ->
            val child = reading.locate(page) ?: return null
            return ManualReviewDocument(child.file, child.filename.removeSuffix(".pdf"), child.reading.pdfSha256, child.reading.firstPage, child.reading.pages)
        }
        val uri = pdf.uri ?: return null
        val file = UriCanonicalizer.canonicalize(this, uri) ?: return null
        val identity = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().forFile(file) ?: return null
        return ManualReviewDocument(
            file = file,
            pdfStem = identity.get("filename").asString.substringBeforeLast('.'),
            pdfSha256 = identity.get("pdf_sha256").asString,
        )
    }

    /** The document's name as the reader shows it (its Library display name when it has one). */
    internal fun shownDocumentTitle(): String = readerDisplayTitle ?: pdf.getTitle()

    override fun applyReaderDisplayTitle(title: String?) {
        readerDisplayTitle = title
        updateTitle()
    }

    override fun updateTitle() {
        val title = readerDisplayTitle ?: pdf.getTitle()
        if (title.isNotBlank() && taskDescriptionName != title) {
            taskDescriptionName = title
            setTaskDescription(ActivityManager.TaskDescription(title))
        }
        appTitle.text = title
        appTitlePageNumber.text = pdf.getPageCounterText()
        val reviewing = ::reviewSessionController.isInitialized && reviewSessionController.isActive
        appTitlePageNumber.visibility = if (!reviewing && pref.getShowAppBarPageCount() && pdf.hasFile() && pdf.length > 0) {
            View.VISIBLE
        } else {
            View.GONE
        }
        reader.fullScreenOptionsManager.refreshInfo()
    }

    override fun updateActionBar() {
        if (!::actionBarMenu.isInitialized) {
            return
        }

        reader.toolbarActionController.update(actionBarMenu)
        if (::reviewSessionController.isInitialized) {
            if (reviewSessionController.isActive) {
                // Keep room for the review labels beside the permanent navigation arrows.
                actionBarMenu.findItem(R.id.toolbarPrimaryActionOption)?.isVisible = false
                actionBarMenu.findItem(R.id.toolbarSecondaryActionOption)?.isVisible = false
            }
            reviewSessionController.updateToolbar()
        }
    }

    internal fun checkAlwaysHorizontal() {
        if (pref.getAlwaysHorizontal() && vm.isPortrait) {
            rotateScreen()
        }
        if (!pref.getAlwaysHorizontal() && !vm.isPortrait) {
            rotateScreen()
        }
    }

    internal fun maybeRestoreAnnotations(documentUri: Uri?, loadToken: Long) {
        val uri = documentUri ?: return
        lifecycleScope.launch {
            val hasJournal = withContext(Dispatchers.IO) { annotationController.hasJournal(uri) }
            if (!hasJournal || !vm.isCurrent(loadToken, uri)) {
                return@launch
            }
            if (annotationController.isSessionOwned(uri)) {
                replayAnnotations(uri, loadToken)
            } else {
                promptRestoreAnnotations(uri, loadToken)
            }
        }
    }

    private fun promptRestoreAnnotations(documentUri: Uri, loadToken: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_unsaved_highlights_title)
            .setMessage(R.string.restore_unsaved_highlights_message)
            .setCancelable(false)
            .setPositiveButton(R.string.restore) { _, _ ->
                lifecycleScope.launch { replayAnnotations(documentUri, loadToken) }
            }
            .setNegativeButton(R.string.discard) { _, _ ->
                annotationController.clearJournal(documentUri)
                updateDirtyUi()
            }
            .show()
    }

    private suspend fun replayAnnotations(documentUri: Uri, loadToken: Long) {
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        annotationController.replayJournal()
        updateDirtyUi()
    }

    override fun updateDirtyUi() {
        val visible = annotationController.hasUnsavedAnnotations
        val saving = annotationController.isSaving
        binding.saveAnnotationsFab.visibility = if (visible) View.VISIBLE else View.GONE
        binding.discardAnnotationsFab.visibility = if (visible) View.VISIBLE else View.GONE
        binding.saveAnnotationsFab.isEnabled = visible && !saving
        binding.discardAnnotationsFab.isEnabled = visible && !saving
        binding.saveAnnotationsFab.alpha = if (saving) 0.5f else 1f
        binding.discardAnnotationsFab.alpha = if (saving) 0.5f else 1f
        if (saving && !savingProgressVisible) {
            savingProgressVisible = true
            binding.progressBar.isIndeterminate = true
            binding.progressBar.visibility = View.VISIBLE
        } else if (!saving && savingProgressVisible) {
            savingProgressVisible = false
            hideProgress()
        }
        updateDirtyUiPosition()
    }

    override fun updateDirtyUiPosition() {
        val params = binding.saveAnnotationsFab.layoutParams as ConstraintLayout.LayoutParams
        val defaultBottomMargin = (24 * resources.displayMetrics.density).toInt()
        val cardAtBottom = reader.inlineAnnotationActionController.isCardAtBottom()
        val bottomMargin = if (cardAtBottom && binding.textSelectionActionCard.height > 0) {
            binding.textSelectionActionCard.height + (32 * resources.displayMetrics.density).toInt()
        } else {
            defaultBottomMargin
        }
        if (params.bottomMargin != bottomMargin) {
            params.bottomMargin = bottomMargin
            binding.saveAnnotationsFab.layoutParams = params
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    internal fun rotateScreen() {
        requestedOrientation = if (vm.isPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        vm.togglePortrait()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatterySetup.offerOnFirstUse(this)
        fullscreenController.refreshOnWindowFocus(hasFocus)
    }

    public override fun onResume() {
        super.onResume()
        // An arrow pressed on the note while this tab stayed visible (split screen): no onStart came.
        if (ReaderNoteHistory.hasRequest(taskId)) returnFromAgentNote()
        // Intents come before onResume: a link from the note just left was recorded by now.
        if (::reader.isInitialized) readerHistory.settle()
        if (::studyPageBar.isInitialized) studyPageBar.refresh()
        ReaderSessionNavigation.remember(this)
        com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatterySetup.offerOnFirstUse(this)
        if (::actionBarMenu.isInitialized) {
            updateActionBar()
        }
        updateHomeUpIndicator()
        reader.onResume()
        applyLogicalCommentOverlays()
        refreshLocallyAcceptedAgentProposals()
        openDocumentTabsController.refresh(vm.isFullScreenToggled)

        // check if there is a pdf at first

        if (pdf.uri != null) {
            binding.pickFileButton.visibility = View.GONE
        }
        else {
            binding.pickFileButton.visibility = View.VISIBLE
        }

        // restore the full screen mode if was toggled On
        fullscreenController.restoreFullScreenIfNeeded()
        fullscreenController.refreshKeepScreenOn()

        if (BuildConfig.DEBUG) {
            stallWatchdog.start()
        }
    }

    override fun onPause() {
        if (::openDocumentTabsController.isInitialized) {
            openDocumentTabsController.rememberPlace()
        }
        if (BuildConfig.DEBUG) {
            stallWatchdog.stop()
        }
        super.onPause()
    }

    private fun exportProjectReading(captured: com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReadingContext, documentIds: List<String>?) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Export combined PDF").setMessage("Exporting the project…")
            .setNegativeButton("Close", null).create()
        val job = lifecycleScope.launch {
            try {
                val (file, older) = FedoraRepository(applicationContext).exportProjectPdf(captured.exportDescription(), documentIds) { message ->
                    withContext(Dispatchers.Main) { dialog.setMessage(message) }
                }
                dialog.dismiss()
                if (older) android.widget.Toast.makeText(this@MainActivity, "The server has a newer version of this project; this export is the last synced one.", android.widget.Toast.LENGTH_LONG).show()
                val exported = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                startShareIntent(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, exported)
                    clipData = android.content.ClipData.newRawUri("Project PDF", exported)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Save or share combined PDF"))
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { dialog.dismiss(); AppSnackbar.make(binding.root, error.message ?: "Export failed", Snackbar.LENGTH_LONG).show() }
        }
        dialog.setOnCancelListener { job.cancel() }
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener { job.cancel(); dialog.dismiss() } }
        dialog.show()
    }

    internal fun shareFile(
        uri: Uri?,
        asImage: Boolean = false,
        citationSourceUri: Uri? = pdf.uri,
        embeddedComments: List<com.github.barteksc.pdfviewer.PDFView.PdfComment>? = null,
    ) {
        if (uri == null) {
            checkHasFile()  // only to show the message
            return
        }
        if (!asImage && pdf.projectReading != null) {
            val captured = requireNotNull(pdf.projectReading)
            com.gitlab.mudlej.MjPdfReader.ui.home.chooseProjectExportDocuments(this, captured.documents.map {
                com.gitlab.mudlej.MjPdfReader.ui.home.ProjectExportDocument(it.reading.id, it.reading.title)
            }) { chosen -> exportProjectReading(captured, chosen) }
            return
        }
        if (uri.scheme != null && uri.scheme!!.startsWith("http")) {
            val localCopy = OnlineDocumentStore.fileFor(this, pdf.uri?.toString())
                ?: publicDownloadsCopy(pdf.name)
            if (localCopy == null) {
                startShareIntent(plainTextShareIntent(getString(R.string.share_file), pdf.uri.toString()))
            } else {
                pdfShareController.show(
                    Uri.fromFile(localCopy),
                    pdf.name,
                    embeddedComments,
                    shareableLink = pdf.uri.toString(),
                    citationContext = thoughtsCitationContextFor(citationSourceUri),
                    pageCount = binding.pdfView.pageCount,
                    password = pdf.password,
                    documentSourceUri = citationSourceUri ?: uri,
                    currentPageIndex = binding.pdfView.currentPage,
                    expectedSourceSha256 = pdf.annotationPdfSha256,
                )
            }
            return
        }
        if (asImage) {
            startShareIntent(imageShareIntent(getString(R.string.share_file), pdf.name, uri))
            return
        }

        pdfShareController.show(
            uri,
            pdf.name,
            embeddedComments,
            citationContext = thoughtsCitationContextFor(citationSourceUri),
            pageCount = binding.pdfView.pageCount,
            password = pdf.password,
            documentSourceUri = citationSourceUri ?: uri,
            currentPageIndex = binding.pdfView.currentPage,
            expectedSourceSha256 = pdf.annotationPdfSha256,
        )
    }

    private val pdfShareController by lazy {
        PdfShareController(
            this,
            lifecycleScope,
            ::startShareIntent,
        ) {
            AppSnackbar.make(binding.root, R.string.share_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun startShareIntent(sharingIntent: Intent) {
        try {
            startActivity(sharingIntent)
        }
        catch (e: Throwable) {
            AppSnackbar.make(binding.root, R.string.share_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    internal fun handleFileOpeningError(exception: Throwable) {
        val fileHash = pdf.fileHash
        if (exception is PdfPasswordException && fileHash != null) {
            if (pdf.password != null) {
                AppSnackbar.make(binding.root, R.string.wrong_password, Snackbar.LENGTH_SHORT).show()
                pdf.password = null         // prevent the toast if the user rotates the screen
            }

            lifecycleScope.launch {
                pdf.password = pdfRepository.findPdfPassword(fileHash)
                withContext(Dispatchers.Main) {
                    if (pdf.password != null) {
                        displayFromUri(pdf.uri)
                    }
                    else {
                        askForPdfPassword()
                    }
                }
            }
        }
        else if (couldNotOpenFileDueToMissingPermission(exception)) {
            reader.readFileErrorPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        else if (shouldReturnToHomeForRelocate(exception)) {
            returnToHomeForRelocate()
        }
        else if (isStaleDocumentFailure(exception) && hasRecoverableUri()) {
            reader.startStaleDocumentRecovery(pdf.uri ?: return)
        }
        else {
            AppSnackbar.make(binding.root, R.string.file_opening_error, Snackbar.LENGTH_LONG).show()
            Log.e(TAG, getString(R.string.file_opening_error), exception)
        }
    }

    private fun shouldReturnToHomeForRelocate(exception: Throwable): Boolean {
        if (pref.getHomeDisabled()) {
            return false
        }
        if (!intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false)) {
            return false
        }
        if (intent.getStringExtra(HomeActivity.EXTRA_RECORD_HASH) == null) {
            return false
        }
        return isStaleDocumentFailure(exception)
    }

    private fun isStaleDocumentFailure(exception: Throwable): Boolean {
        var cause: Throwable? = exception
        while (cause != null) {
            if (cause is SecurityException || cause is FileNotFoundException || cause is DocumentUnreachableException) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun hasRecoverableUri(): Boolean {
        val scheme = pdf.uri?.scheme
        return scheme == "content" || scheme == "file"
    }

    private fun returnToHomeForRelocate() {
        Intent(this, HomeActivity::class.java).also { homeIntent ->
            homeIntent.putExtra(
                HomeActivity.EXTRA_RELOCATE_HASH,
                intent.getStringExtra(HomeActivity.EXTRA_RECORD_HASH)
            )
            startActivity(homeIntent)
        }
        finish()
    }

    private fun couldNotOpenFileDueToMissingPermission(e: Throwable): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) return false
        val exceptionMessage = e.message
        return e is FileNotFoundException && exceptionMessage != null
            && exceptionMessage.contains(getString(R.string.permission_denied))
    }

    internal fun restartAppIfGranted(isPermissionGranted: Boolean) {
        if (isPermissionGranted) {
            // This is a quick and dirty way to make the system restart the current activity *and the current app process*.
            // This is needed because on Android 6 storage permission grants do not take effect until
            // the app process is restarted.
            exitProcess(0)
        }
        else {
            AppSnackbar.make(binding.root, R.string.file_opening_error, Snackbar.LENGTH_LONG).show()
        }
    }

    internal fun reloadPdf() {
        if (checkHasFile()) {
            recreate()
        }
    }

    internal fun toggleCropMargins() {
        if (!checkHasFile()) {
            return
        }
        val enableCropMargins = !vm.cropMarginsEnabled
        reader.setCropMarginsEnabled(enableCropMargins)
        if (enableCropMargins) {
            cropMarginsController.startOrApply(
                pdf.fileHash,
                vm.currentLoadToken,
                pdf.uri,
                binding.pdfView.pageCount,
            )
        } else {
            cropMarginsController.cancel()
            recreate()
        }
    }

    override fun onStop() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver)
            statusReceiverRegistered = false
        }
        if (::reader.isInitialized) {
            reader.autoScrollSpeedStore.flushPendingSave()
        }
        super.onStop()
    }

    override fun onDestroy() {
        // A closed tab: the notes window no longer leads back into it.
        if (isFinishing) ReaderNoteHistory.returned(taskId)
        if (::studyPageBar.isInitialized) studyPageBar.release()
        if (::openDocumentTabsController.isInitialized) {
            openDocumentTabsController.release()
        }
        if (::reader.isInitialized) {
            reader.autoScrollSpeedStore.flushPendingSave()
            cropMarginsController.cancel()
            reader.inlineAnnotationActionController.hideActions()
            reader.onActivityDestroyed()
        }
        if (::htmlReader.isInitialized) htmlReader.destroy()
        if (::attachmentReader.isInitialized) attachmentReader.destroy()
        super.onDestroy()
    }

    override fun hideProgress() {
        binding.progressBar.visibility = View.GONE
        binding.progressBar.isIndeterminate = true
        binding.progressBar.progress = 0
    }

    private fun askForPdfPassword() {
        if (isFinishing || isDestroyed) {
            return
        }
        val dialogBinding = PasswordDialogBinding.inflate(layoutInflater)
        showAskForPasswordDialog(this, pdf, dialogBinding, ::displayFromUri)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        this.actionBarMenu = menu
        menu.showOptionalIcons(this)
        updateActionBar()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> navigateHome()
            R.id.toolbarNavigationBackOption,
            R.id.toolbarNavigationForwardOption,
            R.id.toolbarPrimaryActionOption,
            R.id.toolbarSecondaryActionOption -> if (!reader.toolbarActionController.handle(item)) return super.onOptionsItemSelected(item)
            R.id.readerActionsOption -> reader.readerMenu.show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun navigateHome() {
        dismissPdfCommentSheet()
        startActivity(Intent(this, HomeActivity::class.java))
    }

    internal fun dismissTableOfContentsSheet() {
        readerNavigationController.dismissTableOfContents()
    }

    override fun dismissPdfCommentSheet() {
        if (::reader.isInitialized) {
            inlineAnnotationActionController.dismissPdfCommentSheet()
        }
    }

    private fun updateFromFedora(
        onQueued: (Boolean) -> Unit = {},
    ) {
        if (ThoughtsServerSecretStore(this).config() == null) {
            AppSnackbar.make(binding.root, R.string.fedora_update_not_configured, Snackbar.LENGTH_LONG).show()
            onQueued(false)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SyncCommandCoordinator(applicationContext).start(target = null)
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
                    Log.w(TAG, "Unable to queue global Fedora update: ${result.message}")
                    AppSnackbar.make(
                        binding.root,
                        R.string.fedora_update_retrying,
                        Snackbar.LENGTH_LONG,
                    ).show()
                    onQueued(false)
                }
            }
        }
    }

    internal fun syncLibrary(onQueued: (Boolean) -> Unit = {}) {
        updateFromFedora(onQueued)
    }

    internal fun globalSyncActive(onResult: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val active = withContext(Dispatchers.IO) {
                SyncOperationStore(applicationContext).pendingGlobalOperation() != null
            }
            if (!isFinishing && !isDestroyed) {
                onResult(active)
            }
        }
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
                    SyncOperationStore(applicationContext).append(
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
                val updated = ArtifactSyncStateStore(this).markWaiting(plan.planId)
                    ?: return@setNegativeButton
                SyncOperationStore(applicationContext).append(
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
                SyncOperationStore(applicationContext).append(
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
                SyncOperationStore(applicationContext).append(
                    updated.operationId,
                    SyncOperationStore.STATUS_WAITING,
                    "Waiting for an unmetered network",
                )
                ThoughtsSyncScheduler.enqueueArtifacts(this, allowMetered = false)
                ArtifactSyncCoordinator.broadcast(this, updated, showPrompt = false)
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F5 && event?.repeatCount == 0) {
            updateFromFedora()
            return true
        }
        if (reader.volumeKeyPager.handleKeyDown(keyCode)) {
            return true
        }
        if (reader.mousePager.handleKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (reader.mousePager.handleGenericMotionEvent(ev)) {
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (pdfCommentTouchInterceptor?.invoke(ev) == true) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    fun setPdfCommentTouchInterceptor(interceptor: ((MotionEvent) -> Boolean)?) {
        pdfCommentTouchInterceptor = interceptor
    }

    internal fun showFileMetadata() {
        if (!checkHasFile()) {
            return
        }

        val uri = pdf.uri
        lifecycleScope.launch {
            val fileSizeBytes = withContext(Dispatchers.IO) { queryFileSizeBytes(uri) }
            val filePath = withContext(Dispatchers.IO) { resolveDisplayFilePath(uri) }
            val pageSize = withContext(Dispatchers.Default) {
                PdfPropertiesSummary.formatPageSizes(binding.pdfView, getString(R.string.pdf_page_size_mixed))
            }
            val fonts = withContext(Dispatchers.Default) {
                PdfPropertiesSummary.formatFonts(
                    binding.pdfView,
                    getString(R.string.font_embedded),
                    getString(R.string.font_not_embedded),
                )
            }
            showMetaDialog(this@MainActivity, binding.pdfView.documentMeta, pdf.name, fileSizeBytes, pageSize, fonts, filePath)
        }
    }

    private fun resolveDisplayFilePath(uri: Uri?): String? {
        if (uri == null) {
            return null
        }
        if (uri.scheme?.startsWith("http") == true) {
            return publicDownloadsCopy(pdf.name)?.absolutePath
        }
        return UriCanonicalizer.canonicalize(this, uri)?.absolutePath
    }

    private fun queryFileSizeBytes(uri: Uri?): Long? {
        if (uri == null) {
            return null
        }
        val heldFile = OnlineDocumentStore.fileFor(this, uri.toString())
        if (heldFile != null) {
            return heldFile.length()
        }
        return runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                fd.statSize.takeIf { it >= 0 }
            }
        }.getOrNull()
    }

    override fun checkHasFile(): Boolean {
        if (!pdf.hasFile()) {
            AppSnackbar.make(
                binding.root, getString(R.string.no_pdf_in_app),
                Snackbar.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        vm.captureViewStateForSave(binding.pdfView.captureViewState())
        signatureController.capturePlacementForState()
        readerNavigationController.saveState(outState)
        readerHistory.saveState(outState)
        reviewSessionController.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun restoreInstanceState(savedState: Bundle) {
        readerNavigationController.restoreState(savedState)
        readerHistory.restoreState(savedState)
        updateDirtyUi()
    }

    private fun overrideOnBackButtonPressed() {
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (reviewSessionController.cancelRegionEdit()) return
                if (binding.manualReviewEditorCard.visibility == View.VISIBLE) {
                    dismissManualReviewEditor()
                    return
                }
                if (attachmentReader.handleBack()) return
                if (readerNavigationController.handleTableOfContentsBack()) return
                if (inlineAnnotationActionController.handlePdfCommentSheetBack()) {
                    return
                }
                if (annotationController.hasUnsavedAnnotations) {
                    runAfterDirtyAnnotationPrompt { leaveReader(this) }
                    return
                }
                if (!pref.getDoubleTapToExitEnabled()
                    || intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false)
                    || doubleBackToExitPressedOnce
                ) {
                    leaveReader(this)
                } else {
                    AppSnackbar.make(binding.root, getString(R.string.press_back_again), Snackbar.LENGTH_LONG).show()
                    doubleBackToExitPressedOnce = true

                    lifecycleScope.launch {
                        delay(2500)
                        doubleBackToExitPressedOnce = false
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun leaveReader(callback: OnBackPressedCallback) {
        val launchedByPickerFlow = pref.getHomeDisabled()
                && intent.action == null
                && intent.data == null
        if (launchedByPickerFlow && pdf.uri != null) {
            doubleBackToExitPressedOnce = false
            reader.pickFileOnBackPressed()
        } else if (intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false)) {
            navigateHome()
        } else {
            callback.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private data class ManualReviewDocument(
        val file: File,
        val pdfStem: String,
        val pdfSha256: String,
        val firstPage: Int = 0,
        val pageCount: Int = Int.MAX_VALUE,
    )

}
