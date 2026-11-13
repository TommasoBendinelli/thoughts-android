// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.load

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.PDFView.Configurator
import com.github.barteksc.pdfviewer.model.CropMargins
import com.github.barteksc.pdfviewer.util.Constants
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.OnlineDocumentStore
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.data.HistoryPolicy
import com.gitlab.mudlej.MjPdfReader.data.PdfRepository
import com.gitlab.mudlej.MjPdfReader.data.entity.PdfRecord
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedCommentIdentity
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.PdfWarmCache
import com.gitlab.mudlej.MjPdfReader.data.render.VerifiedPdfActivationStore
import com.gitlab.mudlej.MjPdfReader.data.render.VerifiedPdfSelection
import com.gitlab.mudlej.MjPdfReader.data.render.sourceLinkedLogicalFile
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderUi
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReaderViewModel
import com.gitlab.mudlej.MjPdfReader.ui.reader.controls.readingdirection.ReadingDirectionResolver
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.DocumentIdentity
import com.gitlab.mudlej.MjPdfReader.core.io.computeFreshDocumentIdentity
import com.gitlab.mudlej.MjPdfReader.core.io.computeHash
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.core.io.getFileName
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.shockwave.pdfium.PdfPasswordException
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentLoader(
    private val binding: ActivityMainBinding,
    private val vm: ReaderViewModel,
    private val pref: Preferences,
    private val pdfRepository: PdfRepository,
    private val historyPolicy: HistoryPolicy,
    private val readingDirectionResolver: ReadingDirectionResolver,
    private val scope: CoroutineScope,
    private val ui: ReaderUi,
    private val decorateConfigurator: (Configurator) -> Configurator,
    private val sourceLinkedPdfIndex: SourceLinkedPdfIndex = SourceLinkedPdfIndex(),
    private val verifiedPdfActivationStore: VerifiedPdfActivationStore =
        VerifiedPdfActivationStore(binding.root.context),
) {

    var state: LoadState = LoadState.Idle
        private set

    private val listeners = mutableListOf<DocumentListener>()
    private var hashErrorShownForToken = -1L
    private var updatePendingNoticeKey: String? = null
    private var sourcePreparationToken = 0L
    private val doc get() = vm.doc
    private val context get() = binding.root.context

    fun subscribe(listener: DocumentListener) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: DocumentListener) {
        listeners.remove(listener)
    }

    private inline fun emit(action: (DocumentListener) -> Unit) {
        listeners.forEach(action)
    }

    fun prepareNewDocument(uri: Uri) {
        if (doc.uri == uri) {
            return
        }
        state = LoadState.Idle
        emit { it.onDocumentReset() }
        vm.beginNewDocument(uri, pref.getAlwaysHideMargins())
        ui.updateDirtyUi()
    }

    fun initPdf(uri: Uri) {
        prepareNewDocument(uri)
    }

    fun displayFromUri(uri: Uri?, savePassword: Boolean = false) {
        if (uri == null) {
            ui.updateActionBar()
            return
        }

        prepareNewDocument(uri)

        doc.name = getFileName(context, uri)
        ui.updateActionBar()
        ui.updateTitle()
        doc.resetLength()

        loadCurrentDocument(savePassword)
    }

    fun loadCurrentDocument(savePassword: Boolean = false) {
        val uri = doc.uri ?: return
        val loadToken = vm.currentLoadToken
        val preparationToken = ++sourcePreparationToken
        state = LoadState.Loading
        scope.launch {
            val prepared = prepareDocumentSource(uri)
            if (!vm.isCurrent(loadToken, uri) || preparationToken != sourcePreparationToken) return@launch
            withContext(Dispatchers.Main) {
                if (!vm.isCurrent(loadToken, uri) || preparationToken != sourcePreparationToken) return@withContext
                showUpdatePendingNotice(prepared.selection)
                doc.renderUri = prepared.renderFile?.let(Uri::fromFile) ?: uri
                val configurator = prepared.renderFile
                    ?.let(binding.pdfView::fromFile)
                    ?: binding.pdfView.fromUri(uri)
                initPdfViewAndLoad(
                    configurator,
                    savePassword = savePassword,
                    identityFile = prepared.renderFile,
                    trustedPdfSha256 = prepared.selection?.activePdfSha256,
                )
            }
        }
    }

    fun reloadAfterExternalUpdate() {
        val uri = doc.uri ?: return
        state = LoadState.Idle
        emit { it.onDocumentReset() }
        vm.beginExternalDocumentReload()
        displayFromUri(uri)
    }

    fun initPdfViewAndLoad(
        viewConfigurator: Configurator,
        savePassword: Boolean = false,
        identityFile: File? = null,
        trustedPdfSha256: String? = null,
    ) {
        val loadToken = vm.currentLoadToken
        val documentUri = doc.uri
        if (identityFile != null) {
            doc.renderUri = Uri.fromFile(identityFile)
        } else if (doc.renderUri == null) {
            doc.renderUri = documentUri
        }
        val viewState = vm.pendingViewState
        state = LoadState.Loading
        scope.launch {
            val trustedSha256 = trustedPdfSha256?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            val renderSizeBytes = identityFile?.length()
            val pageGeometry = trustedSha256?.let { sha256 ->
                renderSizeBytes?.let { size -> PdfWarmCache.loadGeometry(context, sha256, size) }
            }
            val identity = if (identityFile != null && trustedSha256 != null) {
                withContext(Dispatchers.IO) {
                    DocumentIdentity(
                        historyHash = computeHash(identityFile),
                        annotationPdfSha256 = trustedSha256,
                    )
                }
            } else if (identityFile != null) {
                withContext(Dispatchers.IO) {
                    computeFreshDocumentIdentity(
                        identityFile,
                        historyHasher = ::computeHash,
                        annotationHasher = ::computeSha256,
                    )
                }
            } else {
                computeFreshDocumentIdentity(
                    doc.uri,
                    historyHasher = { computeHash(context, it) },
                    annotationHasher = { computeSha256(context, it) },
                )
            }
            val projectReading = withContext(Dispatchers.IO) {
                val path = identityFile ?: doc.renderUri?.takeIf { it.scheme == "file" }?.path?.let(::File)
                path?.takeIf { it.name.endsWith(".thoughts-project") }?.let(com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReadingContext::load)
            }
            val hash = identity.historyHash
            val annotationPdfSha256 = identity.annotationPdfSha256
            val documentId = withContext(Dispatchers.IO) {
                documentUri?.let { sourceLinkedLogicalFile(context, it) }?.let {
                    com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().forFile(it)?.get("document_id")?.asString
                }
            }
            val sourceLinkedCommentIdentity = resolveSourceLinkedCommentIdentity(
                documentUri,
                annotationPdfSha256,
            )
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }
            if (hash == null && doc.pageNumber == 0) {
                val failedUri = doc.uri
                val readable = failedUri != null && isUriReadable(failedUri)
                if (!vm.isCurrent(loadToken, documentUri)) {
                    return@launch
                }
                if (readable) {
                    showFailedToComputeHashError()
                } else {
                    val recoveredFile = failedUri?.let { unreadableUri ->
                        withContext(Dispatchers.IO) { UriCanonicalizer.canonicalize(context, unreadableUri) }
                    }
                    if (!vm.isCurrent(loadToken, documentUri)) {
                        return@launch
                    }
                    val recoveredUri = recoveredFile?.let(Uri::fromFile)
                    if (recoveredUri != null && recoveredUri != doc.uri) {
                        withContext(Dispatchers.Main) {
                            displayFromUri(recoveredUri, savePassword)
                        }
                    } else {
                        val failure = DocumentUnreachableException(failedUri)
                        state = LoadState.Failed(failure)
                        withContext(Dispatchers.Main) {
                            hideProgressBar(loadToken, documentUri)
                            emit { it.onLoadFailed(failure) }
                        }
                    }
                    return@launch
                }
            }

            doc.fileHash = hash
            doc.documentId = documentId
            doc.projectReading = projectReading
            doc.annotationPdfSha256 = annotationPdfSha256
            doc.sourceLinkedCommentIdentity = sourceLinkedCommentIdentity

            resolveAnnotationDestination(documentUri)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }

            val pageNumber = if (doc.pageNumber == 0 && hash != null && !pref.getAlwaysOpenAtFirstPage()) {
                val reading = doc.projectReading
                if (reading == null) pdfRepository.findPageNumber(hash)
                else {
                    val saved = context.getSharedPreferences("project-reading-position", android.content.Context.MODE_PRIVATE)
                    val id = saved.getString(reading.manifest.id + "/document", null)
                    val local = saved.getInt(reading.manifest.id + "/page", 0)
                    id?.let { reading.manifest.projectPage(it, local) } ?: 0
                }
            } else {
                doc.pageNumber
            }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }

            val autoScrollSpeed = hash?.let { pdfRepository.findAutoScrollSpeed(it) }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }

            val readingDirectionState = readingDirectionResolver.resolve(hash, documentUri)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }

            val cachedCropMargins = findCachedCropMargins(hash)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }
            vm.setPage(pageNumber)
            doc.autoScrollSpeed = doc.autoScrollSpeed ?: autoScrollSpeed
            doc.readingDirectionOverride = readingDirectionState.overrideDirection
            doc.detectedReadingDirection = readingDirectionState.detectedDirection
            doc.effectiveReadingDirection = readingDirectionState.effectiveDirection
            withContext(Dispatchers.Main) {
                if (vm.isCurrent(loadToken, documentUri)) {
                    initPdfViewAndLoad(
                        viewConfigurator,
                        pageNumber,
                        savePassword,
                        cachedCropMargins,
                        hash,
                        loadToken,
                        documentUri,
                        readingDirectionState.effectiveDirection.isRightToLeft,
                        viewState,
                        renderSizeBytes = renderSizeBytes,
                        pageGeometry = pageGeometry,
                    )
                }
            }
        }
    }

    fun refreshSourceLinkedCommentIdentity() {
        val loadToken = vm.currentLoadToken
        val documentUri = doc.uri
        val annotationPdfSha256 = doc.annotationPdfSha256
        scope.launch {
            val identity = resolveSourceLinkedCommentIdentity(documentUri, annotationPdfSha256)
            if (vm.isCurrent(loadToken, documentUri) && doc.annotationPdfSha256 == annotationPdfSha256) {
                doc.sourceLinkedCommentIdentity = identity
            }
        }
    }

    fun refreshSourceLinkedPublication(onVerifiedUpdateReady: () -> Unit) {
        val loadToken = vm.currentLoadToken
        val documentUri = doc.uri ?: return
        val currentPdfSha256 = doc.annotationPdfSha256
        scope.launch {
            val refresh = withContext(Dispatchers.IO) {
                val logicalFile = sourceLinkedLogicalFile(context, documentUri)
                    ?: return@withContext SourcePublicationRefresh()
                val snapshot = sourceLinkedPdfIndex.snapshot()
                val expectedHash = snapshot.pdfSha256ByFilename[logicalFile.name]
                SourcePublicationRefresh(
                    updateReady = verifiedPdfActivationStore.hasVerifiedUpdateReady(
                        logicalFile,
                        currentPdfSha256,
                        snapshot,
                    ),
                    expectedPdfSha256 = expectedHash,
                    keepingVerifiedVersion = expectedHash != null &&
                        expectedHash != currentPdfSha256 &&
                        currentPdfSha256 != null &&
                        verifiedPdfActivationStore.isPreviouslyVerified(logicalFile, currentPdfSha256),
                )
            }
            if (!vm.isCurrent(loadToken, documentUri) || doc.annotationPdfSha256 != currentPdfSha256) {
                return@launch
            }
            if (refresh.keepingVerifiedVersion && !refresh.updateReady) {
                showUpdatePendingNotice(
                    VerifiedPdfSelection(
                        renderFile = null,
                        activePdfSha256 = currentPdfSha256,
                        expectedPdfSha256 = refresh.expectedPdfSha256,
                        updatePending = true,
                    ),
                )
            }
            if (refresh.updateReady) {
                onVerifiedUpdateReady()
            } else {
                refreshSourceLinkedCommentIdentity()
            }
        }
    }

    private suspend fun resolveSourceLinkedCommentIdentity(
        uri: Uri?,
        annotationPdfSha256: String?,
    ): SourceLinkedCommentIdentity = withContext(Dispatchers.IO) {
        val file = uri?.let { sourceLinkedLogicalFile(context, it) }
            ?: return@withContext SourceLinkedCommentIdentity.NOT_SOURCE_LINKED
        verifiedPdfActivationStore.commentIdentity(
            sourceLinkedPdfIndex,
            file,
            annotationPdfSha256,
        )
    }

    private suspend fun prepareDocumentSource(uri: Uri): PreparedDocumentSource {
        return withContext(Dispatchers.IO) {
            val downloaded = OnlineDocumentStore.fileFor(context, uri.toString())
            if (downloaded != null) {
                return@withContext PreparedDocumentSource(downloaded)
            }
            val project = uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.takeIf { it.name.endsWith(".thoughts-project") }
            if (project != null) {
                val pinned = project.canonicalFile
                com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReadingContext.load(pinned)
                return@withContext PreparedDocumentSource(pinned)
            }
            val logicalFile = sourceLinkedLogicalFile(context, uri)
                ?: return@withContext PreparedDocumentSource()
            val selection = verifiedPdfActivationStore.selectForOpen(
                logicalFile,
                sourceLinkedPdfIndex.snapshot(),
            )
            PreparedDocumentSource(selection.renderFile, selection)
        }
    }

    private fun showUpdatePendingNotice(selection: VerifiedPdfSelection?) {
        val noticeKey = selection
            ?.takeIf { it.updatePending }
            ?.expectedPdfSha256
        if (noticeKey == null) {
            updatePendingNoticeKey = null
            return
        }
        if (noticeKey == updatePendingNoticeKey) return
        updatePendingNoticeKey = noticeKey
        AppSnackbar.make(binding.root, R.string.source_pdf_update_pending, Snackbar.LENGTH_LONG).show()
    }

    fun reloadWithCropMargins(
        configurator: Configurator,
        pageNumber: Int,
        cropMargins: CropMargins,
        viewState: PDFView.ViewState?,
    ) {
        val zoomDisabled = binding.pdfView.isZoomDisabled
        val horizontalSwipeDisabled = binding.pdfView.isHorizontalSwipeDisabled
        initPdfViewAndLoad(
            configurator,
            pageNumber,
            savePassword = false,
            cachedCropMargins = cropMargins,
            fileHash = doc.fileHash,
            loadToken = vm.currentLoadToken,
            documentUri = doc.uri,
            readingDirectionRtl = doc.effectiveReadingDirection.isRightToLeft,
            viewState = viewState,
            applyDocumentLoadDefaults = false,
            zoomDisabled = zoomDisabled,
            horizontalSwipeDisabled = horizontalSwipeDisabled,
        )
    }

    fun applyTileRenderingPreferences() {
        val partSize = pref.getPartSize()
        Constants.PART_SIZE = partSize
        val tilePixels = partSize * partSize
        Constants.Cache.CACHE_SIZE =
            (TILE_CACHE_PIXEL_BUDGET / tilePixels).toInt().coerceIn(MIN_TILE_CACHE_SIZE, MAX_TILE_CACHE_SIZE)
    }

    fun showFailedToComputeHashError() {
        val loadToken = vm.currentLoadToken
        if (hashErrorShownForToken == loadToken) {
            return
        }
        hashErrorShownForToken = loadToken
        val message = context.getString(R.string.hash_failed_notice)
        AppSnackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Log.e(TAG, "showFailedToComputeHashError: $message", RuntimeException())
    }

    private suspend fun isUriReadable(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).canRead() } == true
            } else {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { } != null
                }.getOrDefault(false)
            }
        }
    }

    private suspend fun resolveAnnotationDestination(documentUri: Uri?) {
        if (documentUri == null) {
            vm.annotationSaveDestinationUri = null
            vm.annotationSaveDestinationDurable = false
            return
        }
        val destination = pdfRepository.findAnnotationSaveDestinationByDestinationUri(documentUri.toString())
        val destinationUri = destination?.destinationUri?.toUri()
        vm.annotationSaveDestinationUri = destinationUri
        vm.annotationSaveDestinationDurable = destinationUri != null
    }

    private suspend fun findCachedCropMargins(fileHash: String?): CropMargins? {
        if (!vm.cropMarginsEnabled || fileHash == null) {
            return null
        }
        val storedCropMargins = pdfRepository.findCropMargins(fileHash, CropMargins.STORAGE_VERSION)
            ?: return null
        return CropMargins.fromStorageString(storedCropMargins)
    }

    private fun initPdfViewAndLoad(
        viewConfigurator: Configurator,
        pageNumber: Int,
        savePassword: Boolean,
        cachedCropMargins: CropMargins?,
        fileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
        readingDirectionRtl: Boolean,
        viewState: PDFView.ViewState? = null,
        applyDocumentLoadDefaults: Boolean = true,
        zoomDisabled: Boolean = false,
        horizontalSwipeDisabled: Boolean = false,
        renderSizeBytes: Long? = null,
        pageGeometry: FloatArray? = null,
    ) {
        val pdfView = binding.pdfView
        applyTileRenderingPreferences()
        pdfView.useBestQuality(pref.getHighQuality())
        pdfView.minZoom = Preferences.minZoomDefault
        pdfView.midZoom = Preferences.midZoomDefault
        pdfView.maxZoom = pref.getMaxZoom()
        val spacing = if (pref.getSpaceBetweenPages()) Preferences.spacingDefault else 0
        val browserScrollMode = pref.getBrowserScrollMode() && !pref.getHorizontalScroll()
        val dualPageMode = pref.getDualPageMode() && !pref.getHorizontalScroll()

        val configurator = viewConfigurator
            .defaultPage(pageNumber)
            .defaultViewState(viewState)
            .onPageChange { page: Int, pageCount: Int -> setCurrentPage(page, pageCount, fileHash, loadToken, documentUri) }
            .enableAnnotationRendering(
                Preferences.annotationRenderingDefault &&
                    doc.sourceLinkedCommentIdentity == SourceLinkedCommentIdentity.NOT_SOURCE_LINKED
            )
            .enableAntialiasing(pref.getAntiAliasing())
            .renderDuringScale(true)
            .debugChecks(BuildConfig.DEBUG)
            .spacing(spacing)
            .onError { exception: Throwable ->
                state = if (exception is PdfPasswordException) LoadState.PasswordRequired else LoadState.Failed(exception)
                hideProgressBar(loadToken, documentUri)
                emit { it.onLoadFailed(exception) }
            }
            .onPageError { page: Int, error: Throwable -> reportLoadPageError(page, error) }
            .pageFitPolicy(FitPolicy.WIDTH)
            .password(doc.password)
            .swipeHorizontal(pref.getHorizontalScroll())
            .horizontalReadingDirectionRtl(pref.getHorizontalScroll() && readingDirectionRtl)
            .disableHorizontalSwipe(horizontalSwipeDisabled)
            .zoomDisabled(zoomDisabled)
            .autoSpacing(pref.getHorizontalScroll())
            .pagesPerRow(if (dualPageMode) 2 else 1)
            .firstPageAlone(pref.getDualPageFirstPageAlone())
            .pageSnap(pref.getPageSnap() && !browserScrollMode)
            .pageFling(pref.getPageFling() && !browserScrollMode)
            .freeScrollMode(browserScrollMode)
            .enableTextSelection(pref.getInlineTextSelection())
            .textSelectionColor(MaterialColors.getColor(binding.root, R.attr.colorPrimary))
            .cropMargins(vm.cropMarginsEnabled)
            .cachedCropMargins(cachedCropMargins)
            .onLoad { pageCount ->
                if (vm.pendingViewState === viewState) {
                    vm.pendingViewState = null
                }
                state = LoadState.Loaded(pageCount)
                hideProgressBar(loadToken, documentUri)
                createPdfRecord(savePassword, fileHash, loadToken, documentUri)
                val event = DocumentLoadedEvent(
                    pageCount,
                    cachedCropMargins,
                    fileHash,
                    loadToken,
                    documentUri,
                    applyDocumentLoadDefaults,
                )
                emit { it.onDocumentLoaded(event) }
            }

        if (pageGeometry != null) {
            configurator.pageGeometry(pageGeometry)
        }

        if (fileHash != null) {
            PreviewDiskCoordinator.prepare(
                configurator = configurator,
                cacheDir = context.cacheDir,
                fileIdentity = doc.annotationPdfSha256 ?: fileHash,
                sizeBytes = renderSizeBytes,
                hasPassword = doc.password != null,
            )
        }
        decorateConfigurator(configurator).load()

        pdfView.performTap()
    }

    private fun createPdfRecord(
        savePassword: Boolean,
        expectedFileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
    ) {
        scope.launch {
            persistRecord(savePassword, expectedFileHash, loadToken, documentUri)
        }
    }

    fun persistCurrentDocument() {
        val documentUri = doc.uri ?: return
        val loadToken = vm.currentLoadToken
        scope.launch {
            persistRecord(false, doc.fileHash, loadToken, documentUri)
            val fileHash = doc.fileHash
            if (fileHash != null && vm.isCurrent(loadToken, documentUri) && historyPolicy.canRecord()) {
                pdfRepository.setPageNumber(fileHash, doc.pageNumber)
            }
        }
    }

    private suspend fun persistRecord(
        savePassword: Boolean,
        expectedFileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
    ) {
        val password = if (savePassword) doc.password else null
        val documentTitle = runCatching { binding.pdfView.documentMeta?.title }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }

        if (doc.fileHash == null && expectedFileHash == null) {
            val computedHash = computeHash(context, doc.uri)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            doc.fileHash = computedHash
        }
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }

        val fileHash = expectedFileHash ?: doc.fileHash
        if (fileHash == null) {
            Log.e(TAG, "createPdfRecord: Failed to compute fileHash while creating PdfRecord")
            return
        }
        doc.fileHash = fileHash

        if (!historyPolicy.canRecord()) {
            emit { it.onRecordAvailable(fileHash) }
            return
        }

        if (pdfRepository.hasRecord(fileHash)) {
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            pdfRepository.setLastOpened(fileHash, LocalDateTime.now())
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            updateRecordUri(fileHash)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            if (documentTitle != null) {
                pdfRepository.setDocumentTitle(fileHash, documentTitle)
            }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            if (password != null) {
                pdfRepository.setPassword(fileHash, password)
            }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            doc.autoScrollSpeed?.let { pdfRepository.setAutoScrollSpeed(fileHash, it) }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            readingDirectionResolver.saveState(fileHash)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            emit { it.onRecordAvailable(fileHash) }
        }
        else {
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            val record = doc.toPdfRecord(fileHash, password)
            pdfRepository.saveRecordInBackground(record)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            if (documentTitle != null) {
                pdfRepository.setDocumentTitle(fileHash, documentTitle)
            }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            updateRecordUri(fileHash)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            doc.autoScrollSpeed?.let { pdfRepository.setAutoScrollSpeed(fileHash, it) }
            if (!vm.isCurrent(loadToken, documentUri)) {
                return
            }
            emit { it.onRecordAvailable(fileHash) }
        }
    }

    private suspend fun updateRecordUri(fileHash: String) {
        val currentUri = doc.uri ?: return
        val canonicalFile = withContext(Dispatchers.IO) { UriCanonicalizer.canonicalize(context, currentUri) }
        val durableUri = canonicalFile?.let(Uri::fromFile) ?: currentUri
        val storedUri = pdfRepository.findRecord(fileHash)?.uri
        if (storedUri?.toString() == durableUri.toString()) {
            return
        }
        if (canonicalFile == null && storedUri != null && isUriReadable(storedUri)) {
            return
        }
        pdfRepository.updateRecordIdentity(
            fileHash,
            durableUri,
            doc.name.removeSuffix(".pdf"),
            LocalDateTime.now(),
        )
    }

    private fun setCurrentPage(
        pageNumber: Int,
        pageCount: Int,
        expectedFileHash: String?,
        loadToken: Long,
        documentUri: Uri?,
    ) {
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        vm.setPage(pageNumber)
        doc.pageRangeEnd = binding.pdfView.getRowLastPage(pageNumber)
        doc.initPdfLength(pageCount)
        ui.updateTitle()
        emit { it.onPageChanged(pageNumber) }
        val announcement = if (doc.pageRangeEnd > pageNumber) {
            context.getString(R.string.pages_x_to_y_of_z, pageNumber + 1, doc.pageRangeEnd + 1, pageCount)
        } else {
            context.getString(R.string.page_x_of_y, pageNumber + 1, pageCount)
        }
        binding.pdfView.announceForAccessibility(announcement)

        scope.launch {
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }
            val hash = doc.fileHash ?: expectedFileHash ?: computeHash(context, doc.uri)
            if (!vm.isCurrent(loadToken, documentUri)) {
                return@launch
            }
            if (hash != null) {
                doc.fileHash = hash
                emit { it.onFileHashComputed() }
                attachPreviewDiskIfCurrent(hash, loadToken, documentUri)
                if (historyPolicy.canRecord()) {
                    pdfRepository.setPageNumber(hash, pageNumber)
                    doc.projectReading?.let { reading ->
                        reading.locate(pageNumber)?.let { child ->
                            context.getSharedPreferences("project-reading-position", android.content.Context.MODE_PRIVATE).edit()
                                .putString(reading.manifest.id + "/document", child.reading.id)
                                .putInt(reading.manifest.id + "/page", pageNumber - child.reading.firstPage).apply()
                        }
                    }
                }
            }
            else {
                showFailedToComputeHashError()
            }
        }
    }

    private suspend fun attachPreviewDiskIfCurrent(fileHash: String, loadToken: Long, documentUri: Uri?) {
        val sizeBytes = resolveDocumentSize(doc.uri)
        if (!vm.isCurrent(loadToken, documentUri)) {
            return
        }
        PreviewDiskCoordinator.attach(
            pdfView = binding.pdfView,
            cacheDir = context.cacheDir,
            fileHash = doc.annotationPdfSha256 ?: fileHash,
            pageCount = binding.pdfView.getPageCount(),
            sizeBytes = sizeBytes,
            hasPassword = doc.password != null,
        )
    }

    private suspend fun resolveDocumentSize(uri: Uri?): Long? {
        if (uri == null) {
            return null
        }
        return withContext(Dispatchers.IO) {
            OnlineDocumentStore.fileFor(context, uri.toString())?.let { return@withContext it.length() }
            runCatching {
                when (uri.scheme) {
                    ContentResolver.SCHEME_CONTENT ->
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                        }
                    ContentResolver.SCHEME_FILE -> uri.path?.let { path -> File(path).length().takeIf { it > 0 } }
                    else -> null
                }
            }.getOrNull()
        }
    }

    private fun reportLoadPageError(page: Int, error: Throwable) {
        val message = context.resources.getString(R.string.cannot_load_page) + page + " " + error
        AppSnackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Log.e(TAG, message)
    }

    private fun hideProgressBar(loadToken: Long, documentUri: Uri?) {
        if (vm.isCurrent(loadToken, documentUri)) {
            ui.hideProgress()
        }
    }

    private companion object {
        const val TAG = "DocumentLoader"
        const val TILE_CACHE_PIXEL_BUDGET = 2 * 120 * 256 * 256
        const val MIN_TILE_CACHE_SIZE = 24
        const val MAX_TILE_CACHE_SIZE = 480
    }
}

private data class PreparedDocumentSource(
    val renderFile: File? = null,
    val selection: VerifiedPdfSelection? = null,
)

private data class SourcePublicationRefresh(
    val updateReady: Boolean = false,
    val expectedPdfSha256: String? = null,
    val keepingVerifiedVersion: Boolean = false,
)
