// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.FileObserver
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.PDFView.Configurator
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnTextSelectionChangeListener
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.github.barteksc.pdfviewer.scroll.ScrollHandle
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.App
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.grantPdfReadAccess
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.annotation.SourceKey
import com.gitlab.mudlej.MjPdfReader.data.usage.ReadingUsageContext
import com.gitlab.mudlej.MjPdfReader.data.usage.ReadingUsageService
import com.gitlab.mudlej.MjPdfReader.data.signature.SignatureStore
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationEngine
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationSettings
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.ui.about.AboutActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ConfigurableActionResolver
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.FullScreenButtonController
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.PageTextCopier
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.PrintController
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ReaderMenu
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ScreenshotController
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ShortcutBarController
import com.gitlab.mudlej.MjPdfReader.ui.reader.actions.ToolbarActionController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.AnnotationController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.AnnotationSaveController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.DocumentAnchorStatusController
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceSelection
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedCommentIdentity
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.ui.source.SourceEditorActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.FormFieldController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.InlineAnnotationActionController
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.selectionSourceEndpoints
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.SignatureController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.AutoScrollManager
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.AutoScrollSpeedStore
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.BrightnessController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.CropMarginsController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.FullScreenOptionsManager
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.FullscreenController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.PdfThemeController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.ZoomSwipeLockController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.readingdirection.ReadingDirectionController
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.readingdirection.ReadingDirectionResolver
import com.gitlab.mudlej.MjPdfReader.ui.reader.input.MousePager
import com.gitlab.mudlej.MjPdfReader.ui.reader.input.TapDispatcher
import com.gitlab.mudlej.MjPdfReader.ui.reader.input.VolumeKeyPager
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentListener
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentLoadedEvent
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.DocumentLoader
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.LoadState
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderHistoryManager
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderNavigationController
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.UserBookmarkController
import com.gitlab.mudlej.MjPdfReader.ui.settings.SettingsActivity
import com.gitlab.mudlej.MjPdfReader.ui.gotopage.showGoToPageDialog
import com.gitlab.mudlej.MjPdfReader.ui.text_mode.TextModeActivity
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.io.PersistedGrantKeeper
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.computeHash
import com.gitlab.mudlej.MjPdfReader.core.io.navIntent
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.reader.load.TemporaryCopyImporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun isRelevantSourceLinkedPdfActivationPath(
    changedPath: String?,
    currentPdfFilename: String,
): Boolean = changedPath == currentPdfFilename || changedPath in setOf(
    SourceLinkedPdfIndex.INDEX_FILENAME,
    SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME,
    SourceLinkedPdfIndex.GENERATION_FILENAME,
)

internal fun shouldDeferVerifiedPdfReload(
    hasUnsavedAnnotations: Boolean,
    isSavingAnnotations: Boolean,
    isPdfCommentSheetShowing: Boolean,
    isManualReviewEditorShowing: Boolean,
): Boolean = hasUnsavedAnnotations ||
    isSavingAnnotations ||
    isPdfCommentSheetShowing ||
    isManualReviewEditorShowing

class ReaderComposition(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val vm: ReaderViewModel,
    private val pref: Preferences,
) {

    private val ui: ReaderUi = activity
    private val doc = vm.doc
    private val scope = activity.lifecycleScope
    private val appUsageTracker = (activity.application as App).usageTracker
    private val readingUsageService = ReadingUsageService.getInstance(activity)
    private var currentUsageDocumentKey: String? = null
    private val sourceLinkedPdfIndex = SourceLinkedPdfIndex()
    private var sourceLinkedPdfIndexObserver: FileObserver? = null
    private var verifiedSourceReloadPending = false

    private val pdfPickerLauncher: ActivityResultLauncher<Array<String>> = activity.registerForActivityResult(OpenDocument()) { selectedDocumentUri ->
        val exitOnCancel = pickerOpenedByBackButton
        pickerOpenedByBackButton = false
        if (selectedDocumentUri != null) {
            PersistedGrantKeeper.takeReadGrant(activity, selectedDocumentUri)
            openSelectedDocument(selectedDocumentUri)
        } else if (exitOnCancel) {
            activity.finish()
        }
    }

    private var pickerOpenedByBackButton = false

    private var pendingRelocateRecord: PdfRecord? = null

    private val relocatePickerLauncher: ActivityResultLauncher<Array<String>> = activity.registerForActivityResult(OpenDocument()) { pickedUri ->
        val record = pendingRelocateRecord
        pendingRelocateRecord = null
        if (pickedUri == null) {
            exitAfterFailedRecovery()
            return@registerForActivityResult
        }
        if (record == null) {
            PersistedGrantKeeper.takeReadGrant(activity, pickedUri)
            activity.displayFromUri(pickedUri, savePassword = true)
            return@registerForActivityResult
        }
        scope.launch {
            val pickedHash = computeHash(activity, pickedUri)
            if (pickedHash == record.hash) {
                PersistedGrantKeeper.takeReadGrant(activity, pickedUri)
                val canonicalFile = withContext(Dispatchers.IO) { UriCanonicalizer.canonicalize(activity, pickedUri) }
                val durableUri = canonicalFile?.let(Uri::fromFile) ?: pickedUri
                if (historyPolicy.canRecord()) {
                    migrateReadingUsage(record, durableUri)
                    pdfRepository.updateRecordIdentity(record.hash, durableUri, record.fileName, record.lastOpened)
                }
                activity.displayFromUri(durableUri)
            } else {
                showRelocateMismatchDialog(pickedUri)
            }
        }
    }

    val readFileErrorPermissionLauncher: ActivityResultLauncher<String> = activity.registerForActivityResult(RequestPermission()) { granted ->
        activity.restartAppIfGranted(granted)
    }

    private var alwaysHideMarginsWhenSettingsOpened = false

    private val settingsLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) {
        val alwaysHideMargins = pref.getAlwaysHideMargins()
        if (alwaysHideMargins != alwaysHideMarginsWhenSettingsOpened) {
            setCropMarginsEnabled(alwaysHideMargins)
        }
        activity.displayFromUri(doc.uri)
    }

    private val updateAnnotationDestinationLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            annotationSaveController.handleDestinationResult(result.data)
        } else {
            annotationSaveController.clearPendingRequests()
        }
    }

    private val createAnnotationDestinationLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            annotationSaveController.handleDestinationResult(result.data)
        } else {
            annotationSaveController.clearPendingRequests()
        }
    }

    private val userBookmarksLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        userBookmarkController.reload()
        readerNavigationController.handleUserBookmarksResult(result.resultCode, result.data)
    }

    private val commentsExportLauncher = activity.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        if (destination != null) scope.launch {
            val text = readerNavigationController.commentsExportText()
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openOutputStream(destination, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            AppSnackbar.make(binding.root, if (written) R.string.export_done else R.string.export_failed,
                Snackbar.LENGTH_SHORT).show()
        }
    }

    private val userHighlightsLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleUserHighlightsResult(result.resultCode, result.data)
    }

    private val navigationHistoryLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleNavigationHistoryResult(result.resultCode, result.data)
    }

    private val linksLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleLinksResult(result.resultCode, result.data)
    }

    private val searchLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleSearchResult(result.resultCode, result.data)
    }

    private val goToPageGridLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleGoToPageGridResult(result.resultCode, result.data)
    }

    private val textModeLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(StartActivityForResult()) { result ->
        ui.hideProgress()
        readerNavigationController.handleTextModeResult(result.resultCode, result.data)
    }

    val pdfRepository: PdfRepository = PdfRepository(AppDatabase.getInstance(activity.applicationContext))
    val historyPolicy: HistoryPolicy = HistoryPolicy()
    val autoScrollSpeedStore = AutoScrollSpeedStore(doc, pdfRepository, historyPolicy, scope, backgroundSaveScope)
    val autoScrollManager: AutoScrollManager =
        AutoScrollManager(binding, vm, pref, autoScrollSpeedStore::onSpeedChanged)
    val fullScreenOptionsManager: FullScreenOptionsManager =
        FullScreenOptionsManager(binding, vm, pref.getHideDelay().toLong(), pref)
    val zoomSwipeLockController = ZoomSwipeLockController(binding, ::drawableOf)
    val brightnessController = BrightnessController(activity, binding, vm)
    val pdfThemeController = PdfThemeController(activity, binding, pref)
    val volumeKeyPager = VolumeKeyPager(binding, doc, pref)
    val mousePager = MousePager(binding, doc, pref)
    val printController = PrintController(activity, binding, doc, scope, ::shareCurrentPdf)
    val pageTextCopier = PageTextCopier(activity, binding, doc, scope)
    val screenshotController: ScreenshotController = ScreenshotController(
        activity,
        binding,
        doc,
        { fullScreenOptionsManager.showAllTemporarilyOrHide() },
        { uri -> activity.shareFile(uri, asImage = true) },
    )

    val documentAnchorStatusController = DocumentAnchorStatusController(
        activity,
        scope,
        {
            doc.uri
                ?.takeIf { it.scheme == "file" }
                ?.path
                ?.let(::File)
        },
        activity::updateActionBar,
        { doc.annotationPdfSha256 },
        {
            fun reloadWhenSafe() {
                if (binding.pdfView.hasTextSelection() || vm.isSavingAnnotations) {
                    binding.root.postDelayed(::reloadWhenSafe, 250L)
                    return
                }
                vm.captureViewStateForSave(binding.pdfView.captureViewState())
                documentLoader.reloadAfterExternalUpdate()
            }
            binding.root.post(::reloadWhenSafe)
        },
    )
    val annotationController: AnnotationController = AnnotationController(
        activity,
        binding,
        vm,
        historyPolicy,
        scope,
        documentAnchorStatusController::onCommentPersistenceChanged,
        documentAnchorStatusController::onCommentRestoreChanged,
        documentAnchorStatusController::onCommentsFlushed,
    )
    val formFieldController = FormFieldController(activity, binding, ::onAnnotationEdit)
    val signatureController: SignatureController = SignatureController(
        activity,
        binding,
        vm,
        SignatureStore(activity),
        annotationController,
        ::onAnnotationEdit,
        ui::updateDirtyUi,
    )
    val dictionaryDefinitionController: DictionaryDefinitionController = DictionaryDefinitionController(
        activity,
        scope,
    ) { pref.getDictionaryDefineWords() }
    private val sourceEditQueue = SourceEditQueue(activity.applicationContext)
    private var sourceOpenJob: Job? = null
    private var copyForAgentJob: Job? = null
    val inlineAnnotationActionController: InlineAnnotationActionController = InlineAnnotationActionController(
        activity,
        binding,
        scope,
        { readerNavigationController.clearActiveSearchResultHighlight() },
        ::onAnnotationEdit,
        ui::updateDirtyUiPosition,
        { pref.getDetectExistingHighlights() },
        { pref.getHighlightColors() },
        // Comment lookups match stored filenames, which keep the ".pdf" extension.
        { doc.name },
        {
            TranslationSettings(
                pref.getTranslationMode(),
                TranslationEngine.fromId(pref.getTranslationEngine()),
                pref.getTranslationCustomUrl(),
                pref.getTranslationTargetLanguage(),
            )
        },
        dictionaryDefinitionController::defineWord,
        ::openSettings,
        annotationController::commentMetadata,
        annotationController::commentChatGptUrl,
        ::canOpenSource,
        ::openSourceEditor,
        activity::canCreateManualReviewCard,
        activity::startManualReviewCard,
        activity::startManualReviewFromSelection,
        ::canMutatePdfComments,
        { edits -> annotationController.recordCommentEdits(edits).also { ui.updateDirtyUi() } },
        ::copySelectionForAgent,
        { text -> readAloudBarController.read(text) },
    ) { fullScreenOptionsManager.showAllTemporarilyOrHide() }
    val readAloudBarController = ReadAloudBarController(activity, binding)
    val annotationSaveController: AnnotationSaveController = AnnotationSaveController(
        activity,
        binding,
        doc,
        annotationController,
        pdfRepository,
        historyPolicy,
        vm,
        scope,
        backgroundSaveScope,
        updateAnnotationDestinationLauncher,
        createAnnotationDestinationLauncher,
        { readerNavigationController.clearActiveSearchResultHighlight() },
        ui::updateDirtyUi,
        { signatureController.commitPendingSignature() },
    ) {
        activity.refreshOpenDocumentTabs()
        ui.updateTitle()
        updateReadingUsageContext()
    }
    val cropMarginsController: CropMarginsController = CropMarginsController(
        activity,
        binding,
        pdfRepository,
        historyPolicy,
        doc,
        scope,
        { vm.cropMarginsEnabled },
        ::setCropMarginsEnabled,
        vm::isCurrent,
        { configurator, pageNumber, cropMargins, viewState ->
            documentLoader.reloadWithCropMargins(configurator, pageNumber, cropMargins, viewState)
        },
    )
    val readingDirectionResolver = ReadingDirectionResolver(activity, doc, pref, pdfRepository)

    val readerHistory: ReaderHistoryManager = ReaderHistoryManager(
        { binding.pdfView },
        ::onHistoryChanged,
        { doc.uri?.toString() },
        { entry, commit -> activity.openHistoryDocument(entry, commit) },
        { entry, canGoBack, canGoForward -> activity.showAgentNoteFromHistory(entry, canGoBack, canGoForward) },
    )
    val userBookmarkController: UserBookmarkController = UserBookmarkController(
        binding,
        vm,
        pdfRepository,
        historyPolicy,
        scope,
        ui,
        ::refreshActions,
    )
    val readerNavigationController: ReaderNavigationController = ReaderNavigationController(
        activity,
        binding,
        doc,
        pref,
        readerHistory,
        userBookmarkController::onPageDisplayed,
        ui::updateTitle,
        scope,
        { inlineAnnotationActionController.dismissPdfCommentSheet() },
        { intent -> userBookmarksLauncher.launch(intent) },
        activity::previewCommentFromPanel,
        activity::openCommentThreadFromPanel,
        { commentsExportLauncher.launch("${doc.name.removeSuffix(".pdf")} - Comments.txt") },
        { intent -> userHighlightsLauncher.launch(intent) },
        { intent -> navigationHistoryLauncher.launch(intent) },
        { intent -> linksLauncher.launch(intent) },
        { intent -> searchLauncher.launch(intent) },
        { intent -> goToPageGridLauncher.launch(intent) },
        activity::loadDocumentReviewCards,
        activity::highlightReviewCardFromSheet,
        activity::reviewCardFromSheet,
        activity::setReviewCardsSheetOpen,
        activity::openRetiredReviewCard,
    )
    val pdfLinkOverrideController = com.gitlab.mudlej.MjPdfReader.ui.reader.links.PdfLinkOverrideController(
        activity,
        binding,
        doc,
    ) { event -> readerNavigationController.followOriginalLink(event) }.also { controller ->
        readerNavigationController.linkOverrideHandler = controller::handleTap
        inlineAnnotationActionController.onLinkLongPress = controller::handleLongPress
    }

    val libraryImportController = com.gitlab.mudlej.MjPdfReader.ui.reader.library.LibraryImportController(activity, binding.root)
    val actionResolver: ConfigurableActionResolver = ConfigurableActionResolver(
        doc::hasFile,
        pref::getHorizontalScroll,
        { vm.cropMarginsEnabled },
        pref::getDualPageMode,
        { pdfThemeController.effectivePdfDarkTheme() },
        { pref.getPdfPagesTheme() == Preferences.themeSystem },
        { readerHistory.canGoBack() },
        { readerHistory.canGoForward() },
        { userBookmarkController.isCurrentPageBookmarked },
        { libraryImportController.canImport(doc.uri, doc.sourceLinkedCommentIdentity) },
        createHandlers(),
    )
    val toolbarActionController = ToolbarActionController(
        actionResolver,
        pref::getPrimaryButtonAction,
        pref::getSecondaryButtonAction,
    )
    val fullScreenButtonController: FullScreenButtonController = FullScreenButtonController(
        activity,
        binding,
        pref,
        actionResolver,
        fullScreenOptionsManager,
        autoScrollManager,
    ) { brightnessController.hideControl() }
    val shortcutBarController: ShortcutBarController = ShortcutBarController(
        activity,
        binding,
        pref,
        actionResolver,
    ) { vm.isFullScreenToggled }
    val readerMenu: ReaderMenu = ReaderMenu(activity, actionResolver, doc::hasFile)
    val fullscreenController: FullscreenController = FullscreenController(
        activity,
        binding,
        vm,
        pref,
        fullScreenOptionsManager,
        autoScrollManager,
        zoomSwipeLockController,
        brightnessController,
    ) { shortcutBarController.updateVisibility() }


    val tapDispatcher = TapDispatcher(listOf(
        { event -> activity.handleLogicalCommentMarkerTap(event) },
        { event -> activity.handleAgentProposalTap(event) },
        { event -> inlineAnnotationActionController.handleImmediatePdfTap(event) },
        { event -> formFieldController.handlePdfTap(event) },
        { _ ->
            inlineAnnotationActionController.handleEmptyTap()
            true
        },
    ))
    /** Long presses that belong to a marker (a proposal's ✓ opens its sheet) instead of starting a text selection. */
    val longPressDispatcher = TapDispatcher(listOf(
        { event -> activity.handleAgentProposalLongPress(event) },
    ))
    val documentLoader: DocumentLoader = DocumentLoader(
        binding,
        vm,
        pref,
        pdfRepository,
        historyPolicy,
        readingDirectionResolver,
        scope,
        ui,
        ::decorateConfigurator,
        sourceLinkedPdfIndex,
    )
    val readingDirectionController: ReadingDirectionController = ReadingDirectionController(
        activity,
        doc,
        vm,
        pref,
        pdfRepository,
        historyPolicy,
        scope,
        readingDirectionResolver,
        documentLoader,
    )
    val temporaryCopyImporter: TemporaryCopyImporter = TemporaryCopyImporter(
        activity.applicationContext,
        pdfRepository,
        historyPolicy,
        pref,
        { doc.uri to doc.name },
        backgroundSaveScope,
    ) { fileHash, messageRes ->
        binding.root.post {
            if (!activity.isFinishing && !activity.isDestroyed && doc.fileHash == fileHash) {
                AppSnackbar.make(binding.root, messageRes, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private var historyNavState = false to false

    init {
        inlineAnnotationActionController.configure { annotationSaveController.saveHighlights() }
        readAloudBarController.configure()
        vm.onSaveComplete = { outcome ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                annotationSaveController.deliverSaveOutcome(outcome)
            }
        }
        subscribeDocumentListeners()
        sourceLinkedPdfIndexObserver = sourceLinkedPdfIndex.observePaths { changedPath ->
            if (!isRelevantSourceLinkedPdfActivationPath(changedPath, doc.name)) {
                return@observePaths
            }
            binding.root.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    refreshVerifiedSourcePublication()
                }
            }
        }
    }

    fun onActivityDestroyed() {
        readerNavigationController.dismissTableOfContents()
        readerNavigationController.resetSearchResultState()
        appUsageTracker.setReadingContext(activity, null)
        currentUsageDocumentKey = null
        inlineAnnotationActionController.dismissPdfCommentSheet()
        readAloudBarController.onActivityDestroyed()
        documentAnchorStatusController.close()
        annotationController.close()
        sourceLinkedPdfIndexObserver?.stopWatching()
        sourceLinkedPdfIndexObserver = null
        if (activity.isFinishing) {
            vm.onSaveComplete = null
        }
    }

    fun onVerifiedPdfArtifactsActivated(updatedPdfFilenames: Set<String> = emptySet()) {
        // A Project reading is named after the Project, never after the documents a Sync updated.
        if (updatedPdfFilenames.isNotEmpty() && doc.name !in updatedPdfFilenames && doc.projectReading == null) return
        refreshVerifiedSourcePublication()
    }

    private fun refreshVerifiedSourcePublication() {
        if (documentLoader.state !is LoadState.Loaded) return
        sourceLinkedPdfIndex.invalidate()
        doc.projectReading?.let { refreshProjectReading(it); return }
        documentLoader.refreshSourceLinkedPublication(::scheduleVerifiedSourceReload)
    }

    /**
     * Sync rewrites a Project's reading file in place. The Library-PDF check above ignores
     * readings, so an open Project kept showing the text from before the Sync.
     */
    private fun refreshProjectReading(open: com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReadingContext) {
        val file = doc.renderUri?.takeIf { it.scheme == "file" }?.path?.let(::File) ?: return
        val uri = doc.uri
        scope.launch {
            val changed = withContext(Dispatchers.IO) {
                runCatching {
                    file.isFile && com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReadingContext.load(file).revision != open.revision
                }.getOrDefault(false)
            }
            if (changed && doc.uri == uri && doc.projectReading === open) scheduleVerifiedSourceReload()
        }
    }

    private fun scheduleVerifiedSourceReload() {
        if (verifiedSourceReloadPending) return
        verifiedSourceReloadPending = true
        val scheduledDocumentUri = doc.uri
        fun reloadWhenSafe() {
            if (activity.isFinishing || activity.isDestroyed) {
                verifiedSourceReloadPending = false
                return
            }
            if (doc.uri != scheduledDocumentUri) {
                verifiedSourceReloadPending = false
                return
            }
            if (shouldDeferVerifiedPdfReload(
                    annotationController.hasUnsavedAnnotations,
                    vm.isSavingAnnotations,
                    inlineAnnotationActionController.isPdfCommentSheetShowing(),
                    binding.manualReviewEditorCard.visibility == View.VISIBLE,
                )
            ) {
                binding.root.postDelayed(::reloadWhenSafe, 250L)
                return
            }
            verifiedSourceReloadPending = false
            binding.pdfView.clearTextSelection()
            inlineAnnotationActionController.hideActions()
            vm.captureViewStateForSave(binding.pdfView.captureViewState())
            documentLoader.reloadAfterExternalUpdate()
        }
        binding.root.post(::reloadWhenSafe)
    }

    private fun decorateConfigurator(configurator: Configurator): Configurator {
        return configurator
            .onDocumentInteraction { motionEvent -> autoScrollManager.handleUserInteraction(motionEvent) }
            .onTap { motionEvent -> tapDispatcher.dispatch(motionEvent) }
            .onTapUp { motionEvent -> inlineAnnotationActionController.handleImmediatePdfTap(motionEvent) }
            .onLongPressFirst { motionEvent -> longPressDispatcher.dispatch(motionEvent) }
            .onLongPress { motionEvent -> inlineAnnotationActionController.handlePdfLongPress(motionEvent) }
            .linkHandler(readerNavigationController.createLinkHandler())
            .scrollHandle(createScrollHandle())
            .nightMode(pdfThemeController.effectivePdfDarkTheme())
            .onTextSelectionChange(object : OnTextSelectionChangeListener {
                override fun onTextSelectionChanged(viewBounds: RectF?, pageIndex: Int) {
                    inlineAnnotationActionController.showSelectionActions(viewBounds)
                }

                override fun onTextSelectionCleared() {
                    inlineAnnotationActionController.handleTextSelectionCleared()
                }
            })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createScrollHandle(): ScrollHandle {
        val handle = DefaultScrollHandle(activity, false, pref.getShowScrollHandlePageCount())
        val fullScreenTouchListener = fullScreenOptionsManager.getOnTouchListener()
        handle.setOnTouchListener { view, motionEvent ->
            autoScrollManager.handleUserInteraction(motionEvent)
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> fullScreenOptionsManager.onHandleDragStarted()
                MotionEvent.ACTION_MOVE -> fullScreenOptionsManager.refreshInfo()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fullScreenOptionsManager.onHandleDragEnded()
            }
            fullScreenTouchListener.onTouch(view, motionEvent)
        }
        handle.setOnClickListener { goToPage() }
        return handle
    }

    private fun subscribeDocumentListeners() {
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                appUsageTracker.setReadingContext(activity, null)
                currentUsageDocumentKey = null
            }

            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                updateReadingUsageContext()
                // Keep the open-document tab strip in step with in-place document swaps.
                activity.refreshOpenDocumentTabs()
            }

            override fun onPageChanged(pageIndex: Int) {
                updateReadingUsageContext()
            }

            override fun onLoadFailed(reason: Throwable) {
                appUsageTracker.setReadingContext(activity, null)
            }
        })
        documentLoader.subscribe(temporaryCopyImporter)
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                autoScrollSpeedStore.flushPendingSave()
                pageTextCopier.resetForNewDocument()
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                activity.resetAgentProposalOverlays()
            }

            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                pdfThemeController.configureTheme()
                activity.applyAgentProposalOverlays()
                activity.applyLogicalCommentOverlays()
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                if (event.applyDocumentLoadDefaults) {
                    fullscreenController.checkAutoFullScreen()
                    activity.checkAlwaysHorizontal()
                    openTextModeByDefault()
                    configureButtonsLabels()
                }
                if (doc.uri != null) {
                    shortcutBarController.configure()
                }
                fullScreenButtonController.configure()
                fullscreenController.reapplyStateAfterLoad()
                autoScrollManager.setSpeed(doc.autoScrollSpeed ?: pref.getScrollSpeed())
                if (event.pageCount == 1) {
                    fullScreenOptionsManager.permanentlyHidePageHandle()
                }
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                readerNavigationController.resetSearchResultState()
                readerNavigationController.resetTableOfContentsState()
                readerHistory.onDocumentReset()
            }

            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                readerNavigationController.onDocumentLoaded()
            }

            override fun onPageChanged(pageIndex: Int) {
                readerNavigationController.onPageChanged(pageIndex)
            }

            override fun onFileHashComputed() {
                readerNavigationController.onFileHashComputed()
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                cropMarginsController.cancel()
            }

            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                cropMarginsController.startIfNeeded(
                    event.cachedCropMargins,
                    event.fileHash,
                    event.loadToken,
                    event.documentUri,
                    event.pageCount,
                )
            }

            override fun onRecordAvailable(fileHash: String) {
                scope.launch { cropMarginsController.onRecordAvailable(fileHash) }
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onDocumentReset() {
                inlineAnnotationActionController.hideActions()
                inlineAnnotationActionController.dismissPdfCommentSheet()
                signatureController.cancelPlacement()
                documentAnchorStatusController.onDocumentReset()
                annotationController.resetForDocument()
                activity.resetReviewSessionForDocument()
                activity.htmlReader.onDocumentReset()
                activity.attachmentReader.onDocumentReset()
            }

            override fun onDocumentLoaded(event: DocumentLoadedEvent) {
                documentAnchorStatusController.onDocumentLoaded()
                scope.launch {
                    if (!vm.isCurrent(event.loadToken, event.documentUri)) {
                        return@launch
                    }
                    annotationController.refreshCanonicalComments(doc.name)
                    if (!vm.isCurrent(event.loadToken, event.documentUri)) {
                        return@launch
                    }
                    // The HTML view first: a card opened next reviews in the view the document is read in.
                    activity.htmlReader.onDocumentLoaded()
                    activity.openReviewBrowserPageIfRequested()
                    activity.openOverviewCommentIfRequested()
                    activity.openReviewCardIfRequested()
                    activity.openCitationPageIfRequested()
                    activity.openRemotePdfLinkIfRequested()
                    activity.openHtmlLinkDestinationIfRequested()
                    activity.openLinkOverrideTargetIfRequested()
                    activity.maybeRestoreAnnotations(event.documentUri, event.loadToken)
                    signatureController.resumeRestoredPlacementIfNeeded()
                }
            }
        })
        documentLoader.subscribe(object : DocumentListener {
            override fun onLoadFailed(reason: Throwable) {
                activity.handleFileOpeningError(reason)
            }
        })
    }

    fun wireViews() {
        binding.pdfView.setPreviewTagSource(annotationController.previewTagSource)
        binding.pdfView.setOnStampPlacementDiscardListener { signatureController.cancelPlacement() }
        binding.exitFullScreenButton.setOnClickListener { fullscreenController.exitFullscreen() }
        autoScrollManager.setup()
        brightnessController.attachSeekbarListener()
        binding.apply {
            rotateScreenButton.setOnClickListener { activity.rotateScreen() }
            brightnessButton.setOnClickListener { brightnessController.toggleControlVisibility() }
            screenshotButton.setOnClickListener { screenshotController.takeScreenshot() }
            toggleHorizontalSwipeButton.setOnClickListener { zoomSwipeLockController.toggleHorizontalSwipeLock() }
            toggleZoomLockButton.setOnClickListener { zoomSwipeLockController.toggleZoomLock() }
            toggleLabelButton.setOnClickListener { toggleLabels() }
            pickFileButton.setOnClickListener { pickFile() }
            discardAnnotationsFab.setOnClickListener { activity.confirmDiscardAnnotations() }
        }
        fullScreenButtonController.configure()
        // Several reader controllers read the Library index on the main thread; parse it once here off it.
        activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { sourceLinkedPdfIndex.snapshot() } }
        binding.root.post { readerMenu.prewarmIcons() }
    }

    fun onResume() {
        fullScreenButtonController.configure()
        if (doc.hasFile()) {
            shortcutBarController.configure()
        } else {
            binding.secondBarScrollView.visibility = View.GONE
        }
        inlineAnnotationActionController.rebuildHighlightSwatches()
        updateReadingUsageContext()
        refreshVerifiedSourcePublication()
    }

    private fun updateReadingUsageContext() {
        val uri = doc.uri
        val context = if (
            uri != null &&
            historyPolicy.canRecord() &&
            documentLoader.state is LoadState.Loaded &&
            doc.length > 0
        ) {
            val firstPage = doc.pageNumber.coerceIn(0, doc.length - 1)
            val lastPage = doc.pageRangeEnd.coerceIn(firstPage, doc.length - 1)
            ReadingUsageContext(
                documentKey = SourceKey.of(uri),
                uri = uri.toString(),
                title = doc.getTitle().ifBlank { doc.name },
                pageCount = doc.length,
                visiblePages = (firstPage..lastPage).toList(),
            )
        } else {
            null
        }
        if (context != null) {
            val oldDocumentKey = currentUsageDocumentKey
            if (oldDocumentKey != null && oldDocumentKey != context.documentKey) {
                scope.launch { readingUsageService.mergeDocument(oldDocumentKey, context) }
            }
            currentUsageDocumentKey = context.documentKey
        }
        appUsageTracker.setReadingContext(activity, context)
    }

    private suspend fun migrateReadingUsage(record: PdfRecord, newUri: Uri) {
        readingUsageService.mergeDocument(
            oldDocumentKey = SourceKey.of(record.uri),
            newContext = ReadingUsageContext(
                documentKey = SourceKey.of(newUri),
                uri = newUri.toString(),
                title = record.documentTitle?.takeIf { it.isNotBlank() } ?: record.fileName,
                pageCount = record.length.coerceAtLeast(0),
                visiblePages = emptyList(),
            ),
        )
    }

    fun refreshActions() {
        ui.updateActionBar()
        fullScreenButtonController.configure()
        if (doc.hasFile()) {
            shortcutBarController.configure()
        } else {
            binding.secondBarScrollView.visibility = View.GONE
        }
    }

    fun setCropMarginsEnabled(enabled: Boolean) {
        vm.cropMarginsEnabled = enabled
        refreshActions()
    }

    fun pickFile() {
        ui.runAfterDirtyAnnotationPrompt {
            pickerOpenedByBackButton = false
            launchPdfPicker()
        }
    }

    fun pickFileOnBackPressed() {
        pickerOpenedByBackButton = true
        launchPdfPicker()
    }

    fun openTextModeByDefault() {
        if (pref.getDefaultTextMode()) {
            navToTextMode()
        }
    }

    fun configureButtonsLabels() {
        if (pref.getHideButtonsLabels() == fullScreenOptionsManager.isLabelsVisible()) {
            fullScreenOptionsManager.toggleLabelVisibility(::drawableOf, activity::getString)
        }
    }

    private fun createHandlers(): ConfigurableActionResolver.Handlers {
        return ConfigurableActionResolver.Handlers(
            toggleFullscreen = { fullscreenController.toggleFullscreen() },
            exitFullscreen = { fullscreenController.exitFullscreen() },
            rotate = activity::rotateScreen,
            toggleHorizontalLock = { zoomSwipeLockController.toggleHorizontalSwipeLock() },
            readingDirection = ::showReadingDirectionDialog,
            toggleZoomLock = { zoomSwipeLockController.toggleZoomLock() },
            toggleCropMargins = activity::toggleCropMargins,
            toggleDualPage = ::toggleDualPageMode,
            screenshot = { screenshotController.takeScreenshot() },
            switchTheme = ::switchPdfTheme,
            navigateBack = { readerHistory.goBack() },
            navigateForward = { readerHistory.goForward() },
            showNavigationHistory = ::showNavigationHistory,
            reload = activity::reloadPdf,
            openLocal = ::pickFile,
            putInLibrary = { libraryImportController.putInLibrary(doc.uri, doc.name) },
            search = { showSearchDialog(activity, doc, readerNavigationController::search) },
            goToPage = ::goToPage,
            extractText = { pageTextCopier.copyPageText() },
            textMode = ::navToTextMode,
            share = ::shareCurrentPdf,
            settings = ::openSettings,
            fileMetadata = activity::showFileMetadata,
            about = { activity.startActivity(navIntent(activity, AboutActivity::class.java)) },
            tableOfContents = { readerNavigationController.showTableOfContents() },
            toggleBookmark = { userBookmarkController.toggleCurrentPageBookmark() },
            userBookmarks = ::showUserBookmarks,
            userNotes = ::showUserNotes,
            reviewCards = ::showReviewCards,
            agentEdits = ::showAgentEdits,
            userHighlights = ::showUserHighlights,
            linksInFile = { readerNavigationController.showLinks() },
            print = ::printFile,
            addSignature = { signatureController.showSignatureDialog() },
        )
    }

    private fun toggleDualPageMode() {
        if (!doc.hasFile()) {
            return
        }
        pref.setDualPageMode(!pref.getDualPageMode())
        vm.captureViewStateForSave(binding.pdfView.captureViewState())
        refreshActions()
        activity.displayFromUri(doc.uri)
    }

    private fun shareCurrentPdf() {
        activity.shareFile(
            doc.renderUri ?: doc.uri,
            citationSourceUri = doc.uri,
            embeddedComments = binding.pdfView.getAllPdfCommentsIncludingFragments(),
        )
    }

    private fun openSelectedDocument(selectedDocumentUri: Uri?) {
        if (selectedDocumentUri == null) {
            return
        }
        if (doc.uri == null || selectedDocumentUri == doc.uri) {
            try {
                documentLoader.initPdf(selectedDocumentUri)
                activity.displayFromUri(doc.uri, true)
            } catch (e: Throwable) {
                Log.e(TAG, "openSelectedDocument: ", e)
                AppSnackbar.make(binding.root, R.string.file_opening_error, Snackbar.LENGTH_LONG).show()
            }
        } else {
            val intent = Intent(activity, activity.javaClass)
            intent.data = selectedDocumentUri
            activity.startActivity(intent)
        }
    }

    fun startStaleDocumentRecovery(failedUri: Uri) {
        scope.launch {
            val knownHash = activity.intent.getStringExtra(HomeActivity.EXTRA_RECORD_HASH)
            val record = knownHash?.let { pdfRepository.findRecord(it) }
                ?: pdfRepository.findRecordByUri(failedUri.toString())
            if (activity.isFinishing || activity.isDestroyed) {
                return@launch
            }
            if (record == null) {
                showTemporaryFileGoneDialog()
                return@launch
            }
            if (record.uri != failedUri && isUriReadable(record.uri)) {
                activity.displayFromUri(record.uri)
                return@launch
            }
            showRelocateDialog(record)
        }
    }

    private fun showRelocateDialog(record: PdfRecord) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_relocate_title)
            .setMessage(activity.getString(R.string.home_relocate_message, record.fileName))
            .setPositiveButton(R.string.home_relocate_action) { _, _ ->
                pendingRelocateRecord = record
                relocatePickerLauncher.launch(arrayOf(PDF.FILE_TYPE))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> exitAfterFailedRecovery() }
            .setOnCancelListener { exitAfterFailedRecovery() }
            .show()
    }

    private fun showTemporaryFileGoneDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.stale_shared_title)
            .setMessage(R.string.stale_shared_message)
            .setPositiveButton(R.string.stale_shared_locate) { _, _ ->
                pendingRelocateRecord = null
                relocatePickerLauncher.launch(arrayOf(PDF.FILE_TYPE))
            }
            .setNegativeButton(R.string.cancel) { _, _ -> exitAfterFailedRecovery() }
            .setOnCancelListener { exitAfterFailedRecovery() }
            .show()
    }

    private fun showRelocateMismatchDialog(pickedUri: Uri) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.home_relocate_mismatch_title)
            .setMessage(R.string.home_relocate_mismatch_message)
            .setPositiveButton(R.string.home_open_anyway) { _, _ ->
                PersistedGrantKeeper.takeReadGrant(activity, pickedUri)
                activity.displayFromUri(pickedUri, savePassword = true)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> exitAfterFailedRecovery() }
            .setOnCancelListener { exitAfterFailedRecovery() }
            .show()
    }

    private fun exitAfterFailedRecovery() {
        if (activity.intent.getBooleanExtra(HomeActivity.EXTRA_FROM_HOME, false) && !pref.getHomeDisabled()) {
            activity.startActivity(Intent(activity, HomeActivity::class.java))
            activity.finish()
        } else if (activity.intent.data != null) {
            activity.finish()
        } else {
            pickFile()
        }
    }

    private suspend fun isUriReadable(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).canRead() } == true
            } else {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use { } != null
                }.getOrDefault(false)
            }
        }
    }

    private fun launchPdfPicker() {
        try {
            pdfPickerLauncher.launch(arrayOf(PDF.FILE_TYPE))
        } catch (e: ActivityNotFoundException) {
            AppSnackbar.make(binding.root, R.string.toast_pick_file_error, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun onAnnotationEdit(edit: AnnotationEdit) {
        if (!annotationController.recordEdit(edit)) {
            documentLoader.reloadAfterExternalUpdate()
            return
        }
        ui.updateDirtyUi()
    }

    private fun canMutatePdfComments(): Boolean {
        val identity = doc.sourceLinkedCommentIdentity
        if (identity.allowsMutation) {
            return true
        }
        val message = when (identity) {
            SourceLinkedCommentIdentity.HASH_MISMATCH -> R.string.source_comment_pdf_mismatch
            SourceLinkedCommentIdentity.INDEX_UNAVAILABLE -> R.string.source_comment_index_unavailable
            SourceLinkedCommentIdentity.VERIFYING -> R.string.source_comment_identity_verifying
            SourceLinkedCommentIdentity.PUBLICATION_UNVERIFIED -> R.string.source_comment_publication_unverified
            SourceLinkedCommentIdentity.NOT_SOURCE_LINKED,
            SourceLinkedCommentIdentity.VERIFIED -> return true
        }
        AppSnackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.reload_pdf) {
                sourceLinkedPdfIndex.invalidate()
                documentLoader.reloadAfterExternalUpdate()
            }
            .show()
        return false
    }

    private fun onHistoryChanged() {
        val navState = readerHistory.canGoBack() to readerHistory.canGoForward()
        if (navState != historyNavState) {
            historyNavState = navState
            refreshActions()
        }
    }

    private fun toggleSecondBar() {
        pref.setSecondBarEnabled(binding.secondBarScrollView.visibility != View.VISIBLE)
        shortcutBarController.updateVisibility()
    }

    private fun switchPdfTheme() {
        pdfThemeController.switchPdfTheme { ui.checkHasFile() }
        refreshActions()
    }

    private fun showReadingDirectionDialog() {
        if (!ui.checkHasFile()) {
            return
        }
        readingDirectionController.showDialog()
    }

    private fun showUserBookmarks() {
        if (!ui.checkHasFile()) {
            return
        }
        if (doc.fileHash == null) {
            AppSnackbar.make(binding.root, R.string.bookmark_hash_unavailable, Snackbar.LENGTH_SHORT).show()
            return
        }
        readerNavigationController.showUserBookmarks()
    }

    private fun showNavigationHistory() {
        if (!ui.checkHasFile()) {
            return
        }
        readerNavigationController.showNavigationHistory()
    }

    private fun showReviewCards() {
        if (!ui.checkHasFile()) return
        readerNavigationController.showReviewCards()
    }

    private fun showUserNotes() {
        if (!ui.checkHasFile()) {
            return
        }
        activity.runAfterAnnotationSaveGate {
            readerNavigationController.showUserNotes()
        }
    }

    private fun showAgentEdits() {
        if (!ui.checkHasFile()) return
        activity.runAfterAnnotationSaveGate {
            readerNavigationController.showAgentEdits()
        }
    }

    private fun showUserHighlights() {
        if (!ui.checkHasFile()) {
            return
        }
        activity.runAfterAnnotationSaveGate {
            readerNavigationController.showUserHighlights()
        }
    }

    private fun printFile() {
        if (!ui.checkHasFile()) {
            return
        }
        printController.printFile()
    }

    private fun navToTextMode() {
        if (!ui.checkHasFile()) {
            return
        }
        val currentPageIndex = currentPdfViewPageIndex()
        Intent(activity, TextModeActivity::class.java).also {
            val renderUri = doc.renderUri ?: doc.uri
            it.putExtra(PDF.filePathKey, renderUri.toString())
            it.putExtra(PDF.passwordKey, doc.password)
            it.putExtra(PDF.pageNumberKey, currentPageIndex)
            doc.fileHash?.let { fileHash -> it.putExtra(PDF.fileHashKey, fileHash) }
            it.grantPdfReadAccess(renderUri.toString())
            textModeLauncher.launch(it)
        }
    }

    private fun openSettings() {
        alwaysHideMarginsWhenSettingsOpened = pref.getAlwaysHideMargins()
        val settingsIntent = Intent(activity, SettingsActivity::class.java)
        settingsLauncher.launch(settingsIntent)
    }

    private fun currentPdfViewPageIndex(): Int {
        val currentPage = binding.pdfView.currentPage.coerceAtLeast(0)
        val pageCount = binding.pdfView.pageCount
        return if (pageCount > 0) currentPage.coerceAtMost(pageCount - 1) else currentPage
    }

    private fun goToPage() {
        showGoToPageDialog(
            activity,
            binding.root,
            doc.pageNumber,
            doc.length,
            doc.renderUri ?: doc.uri,
            doc.password,
            goToPageFunc = { pageIndex ->
                readerHistory.recordJump(ReaderHistoryManager.Origin.GO_TO, pageIndex)
                binding.pdfView.jumpTo(pageIndex)
            },
            showAllPages = { readerNavigationController.showGoToPageGrid() },
        )
    }

    private fun toggleLabels() {
        fullScreenOptionsManager.toggleLabelVisibility(::drawableOf, activity::getString)
        pref.setHideButtonsLabels(!pref.getHideButtonsLabels())
    }

    private fun canOpenSource(request: PDFView.HighlightRequest): Boolean =
        resolveSourceSelection(request) != null

    private fun resolveSourceSelection(request: PDFView.HighlightRequest): SourceSelection? {
        val uri = doc.uri
        val file = uri
            ?.takeIf { it.scheme == "file" }
            ?.path
            ?.let(::File)
        val child = doc.projectReading?.locate(request.pageIndex)
        val pdfSha256 = child?.reading?.pdfSha256 ?: doc.annotationPdfSha256
        val bounds = request.pdfRects.firstOrNull()
        if (file == null || pdfSha256.isNullOrBlank() || bounds == null ||
            request.pageIndex < 0 ||
            (child == null && (doc.sourceLinkedCommentIdentity != SourceLinkedCommentIdentity.VERIFIED ||
            !sourceLinkedPdfIndex.isSourceLinked(file))) ||
            (child != null && child.sourcePackage.get("kind")?.asString != "thoughts-document-source-artifact")
        ) {
            return null
        }
        val sourcePoint = binding.pdfView.getSourcePoint(request.pageIndex, bounds) ?: return null
        return SourceSelection(
            pdfFilename = child?.filename ?: file.name,
            pdfSha256 = pdfSha256,
            page = request.pageIndex + 1 - (child?.reading?.firstPage ?: 0),
            x = sourcePoint[0],
            y = sourcePoint[1],
            selectedText = request.selectedText,
            contextText = binding.pdfView.getSourceContext(request.pageIndex, bounds),
        )
    }

    /**
     * The selection toolbar's "Copy for agent": the tab menu's text at the selection's [page], naming
     * the source lines of a [live] selection when this phone can map both of its ends. The lookup is
     * local and normally instant; a busy source store must not hold back the copy, so it gets a budget.
     */
    private fun copySelectionForAgent(selectedText: String, page: Int, live: PDFView.HighlightRequest?) {
        val endpoints = selectionSourceEndpoints(live)?.let { (start, end) ->
            val first = resolveSourceSelection(start) ?: return@let null
            val last = if (end === start) first else resolveSourceSelection(end) ?: return@let null
            first to last
        }
        copyForAgentJob?.cancel()
        copyForAgentJob = scope.launch {
            val location = endpoints?.let { (start, end) ->
                val lookup = scope.async(Dispatchers.IO) {
                    val first = sourceEditQueue.sourceLine(start)
                    agentSourceLocation(first, if (end === start) first else sourceEditQueue.sourceLine(end))
                }
                withTimeoutOrNull(SOURCE_LOCATION_BUDGET_MS) { lookup.await() }
            }
            copyAgentText(activity, activity.agentSelectionDescription(page, selectedText, location))
        }
    }

    private fun openSourceEditor(request: PDFView.HighlightRequest) {
        if (sourceOpenJob?.isActive == true) return
        val selection = resolveSourceSelection(request) ?: run {
            AppSnackbar.make(binding.root, R.string.source_editor_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        sourceOpenJob = scope.launch {
            val loading = AppSnackbar.make(binding.root, R.string.source_open_loading, Snackbar.LENGTH_INDEFINITE)
            loading.show()
            try {
                val handle = runCatching {
                    withContext(Dispatchers.IO) { sourceEditQueue.enqueueOpen(selection) }
                }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    AppSnackbar.make(binding.root, R.string.source_editor_queue_failed, Snackbar.LENGTH_LONG).show()
                    return@launch
                }
                activity.startActivity(
                    SourceEditorActivity.intent(
                        activity,
                        handle.requestId,
                        handle.cacheKey,
                        selection.pdfFilename,
                        selection.pdfSha256,
                        selection.selectedText,
                    )
                )
            } finally {
                loading.dismiss()
            }
        }
    }

    private fun drawableOf(id: Int): Drawable? {
        return AppCompatResources.getDrawable(activity, id)
    }

    companion object {
        private const val TAG = "ReaderComposition"
        private const val SOURCE_LOCATION_BUDGET_MS = 1_500L
        private val backgroundSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
