// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditingGate
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.TextPaint
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.CharacterStyle
import android.text.style.LineBackgroundSpan
import android.text.style.UpdateAppearance
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.SourceConflictBundle
import com.gitlab.mudlej.MjPdfReader.data.source.isWorkspaceStyleResourcePath
import com.gitlab.mudlej.MjPdfReader.data.source.SourceReceipt
import com.gitlab.mudlej.MjPdfReader.data.source.SourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSourceOpenWorker
import com.gitlab.mudlej.MjPdfReader.databinding.ActivitySourceEditorBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySourceEditorBinding
    private lateinit var queue: SourceEditQueue
    private lateinit var openRequestId: String
    private lateinit var cacheKey: String
    private lateinit var pdfFilename: String
    private lateinit var pdfSha256: String
    private lateinit var selectedPdfText: String
    private var conflictId: String = ""
    private var conflictBundle: SourceConflictBundle? = null
    private var conflictMergedDraft: String = ""
    private var conflictView: ConflictView = ConflictView.MERGED
    private var conflictPresentation = SourceConflictPresentation(emptyList(), emptyList())
    private var activeConflictIndex = 0
    private var restoredConflictDraft: String? = null
    private var restoredConflictView: ConflictView? = null
    private var showRawConflictText = false
    private var initialConflictTotal = 0
    private var conflictsRemainingBefore = -1
    private var conflictSavedThisSession = false
    private var shownConflictSaveStatus: ConflictSaveStatus? = null
    private var shownConflictKey: String? = null
    private var programmaticTabChange = false
    private var discardConfirmation: androidx.appcompat.app.AlertDialog? = null
    private val conflictEditorState: ConflictEditorViewModel by viewModels()
    private var snapshot: SourceSnapshot? = null
    private var latestServerSnapshot: SourceSnapshot? = null
    private var openResolutionJob: Job? = null
    private var editorLoaded = false
    private var saving = false
    private var lastSavedContents: String? = null
    private val editTracker = SourceEditorEditTracker()
    private val texHighlightChanges = SourceTexHighlightChanges()
    private var removedLineBreaksBeforeChange = 0
    private var programmaticEditorChange = false
    private var renderedLineCount = 1
    private var sourceContentBaseBottomPadding = 0
    private var sourceSelectionActionMode: ActionMode? = null
    private var showKeyboardWhenWindowFocused = false
    private lateinit var sourceSelectionStartHandle: ImageView
    private lateinit var sourceSelectionEndHandle: ImageView
    private var sourceSelectionHandleDragOffsetX = 0f
    private var sourceSelectionHandleDragOffsetY = 0f
    private val sourceSelectionHandlePreDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateSourceSelectionHandles()
        true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        sourceContentBaseBottomPadding = binding.sourceContent.paddingBottom
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.edit_tex_source)
        queue = SourceEditQueue(applicationContext, scheduleRequests = false)
        openRequestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        conflictId = intent.getStringExtra(EXTRA_CONFLICT_ID).orEmpty()
        restoredConflictDraft = conflictEditorState.draft
        restoredConflictView = conflictEditorState.viewName
            ?.let { name -> runCatching { ConflictView.valueOf(name) }.getOrNull() }
        activeConflictIndex = conflictEditorState.conflictIndex
        showRawConflictText = conflictEditorState.showRawText
        conflictSavedThisSession = conflictEditorState.savedThisSession
        cacheKey = intent.getStringExtra(EXTRA_CACHE_KEY).orEmpty()
        pdfFilename = intent.getStringExtra(EXTRA_PDF_FILENAME).orEmpty()
        pdfSha256 = intent.getStringExtra(EXTRA_PDF_SHA256).orEmpty()
        selectedPdfText = intent.getStringExtra(EXTRA_SELECTED_TEXT).orEmpty()
        if (conflictId.isBlank() &&
            (cacheKey.isBlank() || pdfFilename.isBlank() || pdfSha256.isBlank())
        ) {
            finish()
            return
        }

        if (conflictId.isBlank()) setupProseEditor()
        else binding.sourceEditor.typeface = android.graphics.Typeface.MONOSPACE
        setupSourceSelectionHandles()
        binding.sourceEditor.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {
                    removedLineBreaksBeforeChange = text.countLineBreaks(start, count)
                }

                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    if (conflictId.isBlank()) texHighlightChanges.changed(start, before, count)
                    if (editorLoaded && !programmaticEditorChange) {
                        dismissSourceSelectionActions()
                    }
                    val lineCountChanged = editTracker.applyTextChange(
                        removedLineBreaks = removedLineBreaksBeforeChange,
                        insertedLineBreaks = text.countLineBreaks(start, count),
                        isUserEdit = editorLoaded && !programmaticEditorChange,
                    )
                    removedLineBreaksBeforeChange = 0
                    if (lineCountChanged) {
                        updateLineNumbers(editTracker.lineCount)
                    }
                    updateSaveEnabled()
                }

                override fun afterTextChanged(text: Editable?) {
                    if (editorLoaded && !programmaticEditorChange) SourceEditingGate.shared.noteInput()
                    if (conflictId.isBlank()) {
                        binding.sourceEditor.removeCallbacks(highlightTex)
                        binding.sourceEditor.postDelayed(highlightTex, 150)
                    }
                    if (text != null && conflictId.isNotBlank() &&
                        conflictView == ConflictView.MERGED && editorLoaded &&
                        !programmaticEditorChange
                    ) {
                        // Parsing and re-highlighting the whole file is too much per keystroke.
                        binding.sourceEditor.removeCallbacks(renderEditedConflict)
                        conflictRenderPending = true
                        binding.sourceEditor.postDelayed(renderEditedConflict, 150)
                    }
                }
            },
        )
        binding.sourceSave.setOnClickListener { saveLocally() }
        binding.sourceDiscardConflict.setOnClickListener { confirmDiscardConflict() }
        binding.sourcePreviousConflict.setOnClickListener { navigateConflict(-1) }
        binding.sourceNextConflict.setOnClickListener { navigateConflict(1) }
        binding.sourceUseFedora.setOnClickListener {
            useConflictSide(ConflictTextRole.FEDORA_CURRENT)
        }
        binding.sourceUsePhone.setOnClickListener {
            useConflictSide(ConflictTextRole.PHONE_INCOMING)
        }
        binding.sourceKeepBoth.setOnClickListener { keepBothConflictSides() }
        binding.sourceEditConflict.setOnClickListener { editActiveConflictManually() }
        binding.sourceConflictManualCancel.setOnClickListener { closeManualConflictEditor() }
        binding.sourceConflictManualApply.setOnClickListener { applyManualConflictText() }
        binding.sourceShowRawText.setOnCheckedChangeListener { _, checked ->
            if (checked != showRawConflictText) setShowRawConflictText(checked)
        }
        binding.sourceConflictTabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (!programmaticTabChange) showConflictView(ConflictView.entries[tab.position])
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
        binding.sourceEditor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) dismissSourceSelectionActions()
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = requestClose()
            },
        )

        if (conflictId.isNotBlank()) {
            supportActionBar?.title = getString(R.string.source_conflict_resolve)
            setupConflictLayout()
            binding.sourceEditor.keyListener = null
            binding.sourceEditor.isCursorVisible = false
            binding.sourceEditor.isSaveEnabled = false
            binding.sourceEditor.contentDescription =
                getString(R.string.source_conflict_editor_description)
            binding.sourceSave.text = getString(R.string.source_conflict_save_resolution)
            setStatus(getString(R.string.source_conflict_loading))
            loadConflict()
            return
        }

        queue.cachedSnapshot(cacheKey)?.let {
            val status = if (it.bundleId != null) {
                getString(R.string.source_editor_offline_status)
            } else {
                getString(R.string.source_editor_cached_status)
            }
            showSnapshot(it, status, replaceContents = true)
        } ?: setStatus(getString(R.string.source_editor_resolving))
        lifecycleScope.launch {
            val recoveredSnapshot = withContext(Dispatchers.IO) {
                queue.recoverDeliveredRequests()
                queue.cachedSnapshot(cacheKey)
            }
            if (!editorLoaded && recoveredSnapshot != null) {
                showSnapshot(
                    recoveredSnapshot,
                    getString(R.string.source_editor_cached_status),
                    replaceContents = true,
                )
            }
        }
        pollReceipts()
        resolveOpenRequest()
    }

    override fun onSupportNavigateUp(): Boolean {
        requestClose()
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dismissSourceSelectionActions()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun pollReceipts() {
        lifecycleScope.launch {
            while (isActive) {
                val openReceipt = openRequestId.takeIf(String::isNotBlank)?.let { requestId ->
                    withContext(Dispatchers.IO) { queue.receipt(requestId) }
                }
                if (openReceipt != null && openReceipt.requestId == openRequestId) {
                    handleOpenReceipt(openReceipt)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun resolveOpenRequest() {
        val requestId = openRequestId.takeIf(String::isNotBlank) ?: return
        if (openResolutionJob?.isActive == true) return
        ThoughtsSourceOpenWorker.enqueue(applicationContext, requestId)
        setStatus(getString(R.string.source_editor_resolving))
        openResolutionJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    FedoraRepository(applicationContext).resolveSourceOpenRequest(requestId)
                }
            }
            if (openRequestId != requestId) return@launch
            result.fold(
                onSuccess = ::handleOpenReceipt,
                onFailure = { error ->
                    val status = when {
                        ThoughtsServerSecretStore(applicationContext).config() == null ->
                            R.string.source_editor_sync_not_configured
                        error.message.orEmpty().contains("SHA-256 verification") ->
                            R.string.source_editor_verification_failed
                        else -> R.string.source_editor_fetch_failed
                    }
                    setStatus(getString(status))
                },
            )
        }
    }

    private fun handleOpenReceipt(receipt: SourceReceipt) {
        if (receipt.status == SourceEditQueue.STATUS_SUCCESS && receipt.snapshot != null) {
            val shouldReplace = !editorLoaded || binding.sourceEditor.text.toString() == snapshot?.contents
            if (shouldReplace) {
                showSnapshot(receipt.snapshot, getString(R.string.source_editor_ready), replaceContents = true)
            } else {
                latestServerSnapshot = receipt.snapshot
                setStatus(getString(R.string.source_editor_newer_server_preserved))
            }
            lifecycleScope.launch(Dispatchers.IO) {
                queue.cacheSnapshot(cacheKey, receipt.snapshot)
                queue.acknowledge(receipt.requestId)
            }
            openRequestId = ""
        } else {
            setStatus(receipt.message.ifBlank { getString(R.string.source_editor_resolution_failed) })
            lifecycleScope.launch(Dispatchers.IO) { queue.acknowledge(receipt.requestId) }
            openRequestId = ""
        }
    }

    private fun showSnapshot(value: SourceSnapshot, status: String, replaceContents: Boolean) {
        snapshot = value
        supportActionBar?.title = getString(
            if (value.sourceFile.endsWith(".md") || value.sourceFile.endsWith(".markdown")) {
                R.string.edit_markdown_source
            } else if (value.sourceFile.endsWith("/tex/support/manual_style.sty")) {
                R.string.edit_shared_tex_style
            } else {
                R.string.edit_tex_source
            },
        )
        supportActionBar?.subtitle = value.sourceFile
        if (replaceContents) {
            val displayedContents = queue.localDraftContents(value) ?: value.contents
            sourceSelectionActionMode?.finish()
            programmaticEditorChange = true
            try {
                binding.sourceEditor.setText(displayedContents)
            } finally {
                programmaticEditorChange = false
            }
            editTracker.markCurrentRevisionSaved()
            lastSavedContents = displayedContents
            val matchedSelection = findSourceTextSelection(
                displayedContents,
                selectedPdfText,
                value.sourceLine,
            )
            val targetOffset = matchedSelection?.start
                ?: displayedContents.lineStartOffset(value.sourceLine)
            val openKeyboard = shouldOpenSourceEditorKeyboard(
                resolvingConflict = conflictId.isNotBlank(),
                editorAlreadyLoaded = editorLoaded,
            )
            binding.sourceEditor.showSoftInputOnFocus = true
            binding.sourceEditor.requestFocus()
            if (matchedSelection != null) {
                binding.sourceEditor.setSelection(
                    matchedSelection.start,
                    matchedSelection.endExclusive,
                )
            } else {
                binding.sourceEditor.setSelection(targetOffset.coerceIn(0, binding.sourceEditor.length()))
            }
            if (openKeyboard) requestSourceEditorKeyboard()
            binding.sourceEditor.post {
                if (binding.sourceEditor.restoredFromDisk) return@post
                val layout = binding.sourceEditor.layout ?: return@post
                val safeOffset = targetOffset.coerceIn(0, binding.sourceEditor.length())
                val targetLine = layout.getLineForOffset(safeOffset)
                val selectionEndOffset = matchedSelection
                    ?.endExclusive
                    ?.minus(1)
                    ?.coerceIn(safeOffset, binding.sourceEditor.length())
                    ?: safeOffset
                val selectionEndLine = layout.getLineForOffset(selectionEndOffset)
                val selectionTop = editorTopInScroll() +
                    binding.sourceEditor.totalPaddingTop +
                    layout.getLineTop(targetLine)
                val selectionBottom = editorTopInScroll() +
                    binding.sourceEditor.totalPaddingTop +
                    layout.getLineBottom(selectionEndLine)
                val trailingSpace = maxOf(
                    sourceContentBaseBottomPadding,
                    binding.sourceScroll.height / 2,
                )
                binding.sourceContent.setPaddingRelative(
                    binding.sourceContent.paddingStart,
                    binding.sourceContent.paddingTop,
                    binding.sourceContent.paddingEnd,
                    trailingSpace,
                )
                binding.sourceContent.post {
                    binding.sourceScroll.scrollTo(
                        0,
                        centeredSourceSelectionScrollY(
                            selectionTop,
                            selectionBottom,
                            binding.sourceScroll.height,
                        ),
                    )
                    if (matchedSelection != null) {
                        binding.sourceEditor.postOnAnimation(::showSourceSelectionActions)
                    }
                }
            }
        }
        editorLoaded = true
        val localProblem = queue.localSaveProblem(value)
        setStatus(
            localProblem?.message?.takeIf(String::isNotBlank)
                ?: when (localProblem?.status) {
                    SourceEditQueue.STATUS_CONFLICT -> getString(R.string.source_editor_conflict)
                    SourceEditQueue.STATUS_STALE -> getString(R.string.source_editor_stale)
                    SourceEditQueue.STATUS_FAILURE -> getString(R.string.source_editor_save_failed)
                    else -> status
                },
        )
        updateLineNumbers(editTracker.lineCount)
        updateSaveEnabled()
    }

    private fun loadConflict() {
        val requestedConflictId = conflictId
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SourceConflictBackend.current.load(applicationContext, requestedConflictId)
                }
            }
            if (conflictId != requestedConflictId || isFinishing || isDestroyed) return@launch
            result.fold(
                onSuccess = ::showConflict,
                onFailure = { error ->
                    setStatus(
                        getString(
                            R.string.source_conflict_load_failed,
                            error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                        ),
                    )
                },
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && showKeyboardWhenWindowFocused) showRequestedSourceEditorKeyboard()
    }

    private fun requestSourceEditorKeyboard() {
        showKeyboardWhenWindowFocused = true
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        binding.sourceEditor.showSoftInputOnFocus = true
        binding.sourceEditor.requestFocus()
        binding.sourceEditor.post(::showRequestedSourceEditorKeyboard)
    }

    private fun showRequestedSourceEditorKeyboard() {
        if (!showKeyboardWhenWindowFocused || !binding.sourceEditor.hasWindowFocus()) return
        showKeyboardWhenWindowFocused = false
        binding.sourceEditor.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(
            binding.sourceEditor,
            InputMethodManager.SHOW_IMPLICIT,
        )
    }

    private fun setupSourceSelectionHandles() {
        val handleAttributes = theme.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.textSelectHandleLeft,
                android.R.attr.textSelectHandleRight,
            ),
        )
        try {
            sourceSelectionStartHandle = createSourceSelectionHandle(
                handleAttributes.getDrawable(0),
                getString(R.string.source_selection_start_handle),
            )
            sourceSelectionEndHandle = createSourceSelectionHandle(
                handleAttributes.getDrawable(1),
                getString(R.string.source_selection_end_handle),
            )
        } finally {
            handleAttributes.recycle()
        }
        sourceSelectionStartHandle.setOnTouchListener(sourceSelectionHandleTouchListener(true))
        sourceSelectionEndHandle.setOnTouchListener(sourceSelectionHandleTouchListener(false))
        val handleSize = (SOURCE_SELECTION_HANDLE_TOUCH_DP * resources.displayMetrics.density)
            .toInt()
        addContentView(
            sourceSelectionStartHandle,
            android.view.ViewGroup.LayoutParams(handleSize, handleSize),
        )
        addContentView(
            sourceSelectionEndHandle,
            android.view.ViewGroup.LayoutParams(handleSize, handleSize),
        )
        binding.root.viewTreeObserver.addOnPreDrawListener(sourceSelectionHandlePreDrawListener)
    }

    private fun createSourceSelectionHandle(
        drawable: android.graphics.drawable.Drawable?,
        description: String,
    ): ImageView = ImageView(this).apply {
        setImageDrawable(drawable)
        contentDescription = description
        isClickable = true
        isFocusable = false
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        visibility = View.GONE
    }

    private fun sourceSelectionHandleTouchListener(
        draggingStart: Boolean,
    ) = View.OnTouchListener { handle, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissSourceSelectionActions()
                val currentOffset = if (draggingStart) {
                    binding.sourceEditor.selectionStart
                } else {
                    binding.sourceEditor.selectionEnd
                }
                val caret = sourceEditorCaretPosition(currentOffset)
                    ?: return@OnTouchListener false
                sourceSelectionHandleDragOffsetX = event.rawX - caret.screenX
                sourceSelectionHandleDragOffsetY = event.rawY - caret.lineCenterScreenY
                handle.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                moveSourceSelectionHandle(event, draggingStart)
                true
            }
            MotionEvent.ACTION_UP -> {
                moveSourceSelectionHandle(event, draggingStart)
                handle.parent?.requestDisallowInterceptTouchEvent(false)
                handle.performClick()
                binding.sourceEditor.postOnAnimation(::showSourceSelectionActions)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                handle.parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
    }

    private fun moveSourceSelectionHandle(event: MotionEvent, draggingStart: Boolean) {
        val editor = binding.sourceEditor
        val layout = editor.layout ?: return
        val editorLocation = IntArray(2)
        editor.getLocationOnScreen(editorLocation)
        val localX = event.rawX - sourceSelectionHandleDragOffsetX -
            editorLocation[0] - editor.totalPaddingLeft
        val localY = event.rawY - sourceSelectionHandleDragOffsetY -
            editorLocation[1] - editor.totalPaddingTop
        val line = layout.getLineForVertical(
            localY.toInt().coerceIn(0, (layout.height - 1).coerceAtLeast(0)),
        )
        val candidate = layout.getOffsetForHorizontal(line, localX)
        val current = SourceTextSelection(
            minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0),
            maxOf(editor.selectionStart, editor.selectionEnd).coerceAtMost(editor.length()),
        )
        val updated = draggedSourceTextSelection(
            current = current,
            candidateOffset = candidate,
            draggingStart = draggingStart,
            textLength = editor.length(),
        )
        editor.setSelection(updated.start, updated.endExclusive)
        updateSourceSelectionHandles()
    }

    private fun updateSourceSelectionHandles() {
        if (!::sourceSelectionStartHandle.isInitialized) return
        val editor = binding.sourceEditor
        val start = minOf(editor.selectionStart, editor.selectionEnd)
        val end = maxOf(editor.selectionStart, editor.selectionEnd)
        val show = editor.hasFocus() && conflictId.isBlank() && start >= 0 && end > start
        if (!show) {
            sourceSelectionStartHandle.visibility = View.GONE
            sourceSelectionEndHandle.visibility = View.GONE
            return
        }
        positionSourceSelectionHandle(sourceSelectionStartHandle, start, isStart = true)
        positionSourceSelectionHandle(sourceSelectionEndHandle, end, isStart = false)
    }

    private fun positionSourceSelectionHandle(
        handle: ImageView,
        offset: Int,
        isStart: Boolean,
    ) {
        val caret = sourceEditorCaretPosition(offset) ?: run {
            handle.visibility = View.GONE
            return
        }
        val visibleEditor = Rect()
        if (!binding.sourceEditor.getGlobalVisibleRect(visibleEditor) ||
            !visibleEditor.contains(caret.screenX.toInt(), caret.lineCenterScreenY.toInt())
        ) {
            handle.visibility = View.GONE
            return
        }
        val parent = handle.parent as? View ?: return
        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)
        val inset = SOURCE_SELECTION_HANDLE_INSET_DP * resources.displayMetrics.density
        handle.x = caret.screenX - parentLocation[0] - if (isStart) {
            handle.width - inset
        } else {
            inset
        }
        handle.y = caret.lineBottomScreenY - parentLocation[1] - inset
        handle.visibility = View.VISIBLE
    }

    private fun sourceEditorCaretPosition(offset: Int): SourceEditorCaretPosition? {
        val editor = binding.sourceEditor
        val layout = editor.layout ?: return null
        val safeOffset = offset.coerceIn(0, editor.length())
        val line = layout.getLineForOffset(safeOffset)
        val editorLocation = IntArray(2)
        editor.getLocationOnScreen(editorLocation)
        val screenX = editorLocation[0] + editor.totalPaddingLeft +
            layout.getPrimaryHorizontal(safeOffset)
        val lineTop = editorLocation[1] + editor.totalPaddingTop + layout.getLineTop(line)
        val lineBottom = editorLocation[1] + editor.totalPaddingTop + layout.getLineBottom(line)
        return SourceEditorCaretPosition(
            screenX = screenX,
            lineCenterScreenY = lineTop + (lineBottom - lineTop) / 2f,
            lineBottomScreenY = lineBottom.toFloat(),
        )
    }

    private fun setupConflictLayout() {
        binding.sourceConflictHeader.visibility = View.VISIBLE
        binding.sourceSaveDivider.visibility = View.VISIBLE
        binding.sourceSaveHelper.visibility = View.VISIBLE
        binding.sourceConflictNavigator.visibility = View.GONE
        binding.sourceConflictProgressLabel.visibility = View.GONE
        binding.sourceConflictProgress.visibility = View.GONE
        binding.sourceConflictTabDescription.setText(R.string.source_conflict_tab_resolve_description)
        // Long TeX lines wrap; numbers mark where each source line starts.
        binding.sourceEditor.setHorizontallyScrolling(false)
        binding.sourceLineNumbers.attach(binding.sourceEditor)
        binding.sourceContent.visibility = View.GONE
        binding.sourceShowRawText.isChecked = showRawConflictText
        binding.sourceSave.text = getString(R.string.source_conflict_save_resolution)
        updateSaveEnabled()
    }

    private fun showConflict(bundle: SourceConflictBundle) {
        conflictBundle = bundle
        conflictMergedDraft = restoredConflictDraft ?: bundle.mergedContents
        lastSavedContents = bundle.mergedContents
        initialConflictTotal = parseSourceConflictPresentation(bundle.mergedContents)
            .navigationOffsets.size
        supportActionBar?.title = getString(
            if (isWorkspaceStyleResourcePath(bundle.sourceFile)) {
                R.string.source_style_conflict_resolve
            } else {
                R.string.source_conflict_resolve
            },
        )
        supportActionBar?.subtitle = bundle.sourceFile
        setStatus("")
        binding.sourceDiscardConflict.visibility = View.VISIBLE
        showConflictVersionTimes(bundle)
        val initialView = restoredConflictView ?: ConflictView.MERGED
        showConflictView(initialView, force = true)
        selectConflictTab(initialView)
        editTracker.markCurrentRevisionSaved()
        if (conflictMergedDraft != bundle.mergedContents) {
            editTracker.applyTextChange(
                removedLineBreaks = 0,
                insertedLineBreaks = 0,
                isUserEdit = true,
            )
        }
        editorLoaded = true
        restoredConflictDraft = null
        restoredConflictView = null
        updateSaveEnabled()
    }

    private fun selectConflictTab(view: ConflictView) {
        val tab = binding.sourceConflictTabs.getTabAt(view.ordinal) ?: return
        if (tab.isSelected) return
        programmaticTabChange = true
        try {
            tab.select()
        } finally {
            programmaticTabChange = false
        }
    }

    private fun showConflictVersionTimes(bundle: SourceConflictBundle) {
        binding.sourceFedoraModifiedAt.text = conflictSavedAtLabel(bundle.currentModifiedAt)
        binding.sourcePhoneModifiedAt.text = conflictSavedAtLabel(bundle.incomingModifiedAt)
    }

    private fun conflictTimeText(value: String?): String? =
        sourceConflictTimestampEpochMillis(value)?.let { epochMillis ->
            val sameYear = java.util.Calendar.getInstance().run {
                val thisYear = get(java.util.Calendar.YEAR)
                timeInMillis = epochMillis
                get(java.util.Calendar.YEAR) == thisYear
            }
            val flags = when {
                DateUtils.isToday(epochMillis) -> DateUtils.FORMAT_SHOW_TIME
                sameYear -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_ABBREV_MONTH
                else -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
            }
            DateUtils.formatDateTime(this, epochMillis, flags)
        }

    private fun conflictSavedAtLabel(value: String?): String = conflictTimeText(value)
        ?.let { getString(R.string.source_conflict_saved_at, it) }
        ?: getString(R.string.source_conflict_time_not_recorded)

    private fun conflictSavedAtInline(value: String?): String = conflictTimeText(value)
        ?.let { getString(R.string.source_conflict_saved_at_inline, it) }
        ?: getString(R.string.source_conflict_time_not_recorded_inline)

    private fun showConflictView(view: ConflictView, force: Boolean = false) {
        val bundle = conflictBundle ?: return
        if (!force && conflictView == view) return
        if (conflictView == ConflictView.MERGED && editorLoaded) {
            conflictMergedDraft = binding.sourceEditor.text?.toString().orEmpty()
        }
        // A pending render belongs to the merged text being replaced here.
        binding.sourceEditor.removeCallbacks(renderEditedConflict)
        conflictRenderPending = false
        conflictView = view
        val contents = when (view) {
            ConflictView.MERGED -> conflictMergedDraft
            ConflictView.FEDORA -> bundle.currentContents
            ConflictView.PHONE -> bundle.incomingContents
        }
        sourceSelectionActionMode?.finish()
        programmaticEditorChange = true
        try {
            binding.sourceEditor.setText(contents)
        } finally {
            programmaticEditorChange = false
        }
        if (view == ConflictView.MERGED) {
            // TeX is not prose: no spelling underlines or suggestions in the conflict editor.
            binding.sourceEditor.inputType = CONFLICT_EDITOR_INPUT_TYPE
        } else {
            binding.sourceEditor.keyListener = null
        }
        binding.sourceEditor.isCursorVisible = view == ConflictView.MERGED
        binding.sourceEditor.isEnabled = true
        binding.sourceEditor.isLongClickable = true
        updateLineNumbers(editTracker.lineCount)
        binding.sourceConflictTabDescription.text = when (view) {
            ConflictView.MERGED -> getString(R.string.source_conflict_tab_resolve_description)
            ConflictView.FEDORA -> getString(
                R.string.source_conflict_tab_fedora_description,
                conflictSavedAtInline(bundle.currentModifiedAt),
            )
            ConflictView.PHONE -> getString(
                R.string.source_conflict_tab_phone_description,
                conflictSavedAtInline(bundle.incomingModifiedAt),
            )
        }
        if (view == ConflictView.MERGED) {
            renderConflictPresentation(
                checkNotNull(binding.sourceEditor.text),
                requestedIndex = activeConflictIndex,
                scrollToConflict = true,
            )
        } else {
            binding.sourceEditor.clearFocus()
            binding.sourceConflictGuide.visibility = View.GONE
            binding.sourceConflictNavigator.visibility = View.GONE
            binding.sourceConflictProgressLabel.visibility = View.GONE
            binding.sourceConflictProgress.visibility = View.GONE
            binding.sourceContent.visibility = View.VISIBLE
            binding.sourceScroll.scrollTo(0, 0)
            binding.sourceEditor.contentDescription = getString(
                when (view) {
                    ConflictView.FEDORA -> R.string.source_conflict_fedora_description
                    ConflictView.PHONE -> R.string.source_conflict_phone_description
                    ConflictView.MERGED -> error("Merged conflict view handled above")
                },
            )
        }
        updateSaveEnabled()
    }

    private fun renderConflictPresentation(
        text: Editable,
        requestedIndex: Int? = null,
        scrollToConflict: Boolean = false,
    ) {
        val selection = binding.sourceEditor.selectionStart.coerceAtLeast(0)
        val presentation = parseSourceConflictPresentation(text)
        applyConflictHighlights(text, presentation)
        conflictPresentation = presentation

        val offsets = presentation.navigationOffsets
        val progress = conflictProgress(initialConflictTotal, offsets.size)
        binding.sourceConflictGuide.visibility = View.VISIBLE
        val progressVisibility = if (progress.total > 0) View.VISIBLE else View.GONE
        binding.sourceConflictProgressLabel.visibility = progressVisibility
        binding.sourceConflictProgress.visibility = progressVisibility
        binding.sourceConflictProgressLabel.text = resources.getQuantityString(
            R.plurals.source_conflict_progress,
            progress.total,
            progress.resolved,
            progress.total,
        )
        binding.sourceConflictProgress.max = progress.total.coerceAtLeast(1)
        binding.sourceConflictProgress.setProgressCompat(
            if (progress.total == 0) 1 else progress.resolved,
            false,
        )
        val resolvedNow = offsets.isEmpty() && conflictsRemainingBefore > 0
        conflictsRemainingBefore = offsets.size
        if (offsets.isEmpty()) {
            activeConflictIndex = 0
            closeManualConflictEditor()
            binding.sourceConflictNavigator.visibility = View.GONE
            binding.sourceConflictCard.visibility = View.GONE
            binding.sourceConflictDoneCard.visibility = View.VISIBLE
            binding.sourceShowRawText.visibility = View.GONE
            binding.sourceShowRawTextHint.visibility = View.GONE
            binding.sourceContent.visibility = View.VISIBLE
            binding.sourceEditor.contentDescription =
                getString(R.string.source_conflict_editor_resolved_description)
            if (resolvedNow && !conflictActionInProgress) {
                showConflictSnackbar(getString(R.string.source_conflict_all_resolved))
            }
            updateSaveEnabled()
            return
        }

        val rawShown = showRawConflictText
        activeConflictIndex = requestedIndex
            ?.coerceIn(0, offsets.lastIndex)
            ?: if (rawShown) {
                offsets.indexOfLast { it <= selection }.coerceAtLeast(0)
            } else {
                activeConflictIndex.coerceIn(0, offsets.lastIndex)
            }
        binding.sourceConflictNavigator.visibility = View.VISIBLE
        binding.sourceConflictCard.visibility = View.VISIBLE
        binding.sourceConflictDoneCard.visibility = View.GONE
        binding.sourceShowRawText.visibility = View.VISIBLE
        binding.sourceShowRawTextHint.visibility = View.VISIBLE
        binding.sourceContent.visibility = if (rawShown) View.VISIBLE else View.GONE
        updateConflictControls()
        if (scrollToConflict) {
            scrollToActiveConflict()
        }
        updateSaveEnabled()
    }

    private fun applyConflictHighlights(
        text: Editable,
        presentation: SourceConflictPresentation,
    ) {
        text.getSpans(0, text.length, ConflictLineBackgroundSpan::class.java)
            .forEach(text::removeSpan)
        text.getSpans(0, text.length, ConflictTextColorSpan::class.java)
            .forEach(text::removeSpan)

        presentation.highlights.forEach { highlight ->
            if (highlight.range.endExclusive <= highlight.range.start) return@forEach
            val colors = conflictColors(highlight)
            text.setSpan(
                ConflictLineBackgroundSpan(colors.background),
                highlight.range.start,
                highlight.range.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.setSpan(
                ConflictTextColorSpan(colors.foreground),
                highlight.range.start,
                highlight.range.endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun conflictColors(highlight: ConflictHighlightRange): ConflictColors {
        val resources = when (highlight.role) {
            ConflictTextRole.FEDORA_CURRENT ->
                R.color.source_conflict_fedora_background to R.color.source_conflict_fedora_text
            ConflictTextRole.PHONE_INCOMING ->
                R.color.source_conflict_phone_background to R.color.source_conflict_phone_text
            ConflictTextRole.SHARED_BASE ->
                R.color.source_conflict_shared_background to R.color.source_conflict_shared_text
            ConflictTextRole.MALFORMED_MARKER ->
                R.color.md_theme_errorContainer to R.color.md_theme_onErrorContainer
            ConflictTextRole.MARKER -> when (highlight.markerKind) {
                ConflictMarkerKind.START ->
                    R.color.source_conflict_fedora_marker to
                        R.color.source_conflict_fedora_on_marker
                ConflictMarkerKind.BASE ->
                    R.color.source_conflict_shared_background to
                        R.color.source_conflict_shared_text
                ConflictMarkerKind.SEPARATOR,
                ConflictMarkerKind.END,
                null -> R.color.source_conflict_phone_marker to
                    R.color.source_conflict_phone_on_marker
            }
        }
        return ConflictColors(
            background = ContextCompat.getColor(this, resources.first),
            foreground = ContextCompat.getColor(this, resources.second),
        )
    }

    /** The conflict block the navigator points at, if its markers are complete. */
    private fun activeConflictBlock(): SourceConflictBlock? {
        val activeOffset = conflictPresentation.navigationOffsets
            .getOrNull(activeConflictIndex)
            ?: return null
        return conflictPresentation.blocks.firstOrNull {
            it.startOffset == activeOffset && it.complete
        }
    }

    private fun updateConflictControls() {
        val offsets = conflictPresentation.navigationOffsets
        if (offsets.isEmpty()) return
        activeConflictIndex = activeConflictIndex.coerceIn(0, offsets.lastIndex)
        conflictEditorState.conflictIndex = activeConflictIndex
        val position = getString(
            R.string.source_conflict_position,
            activeConflictIndex + 1,
            offsets.size,
        )
        if (binding.sourceConflictPosition.text.toString() != position) {
            binding.sourceConflictPosition.text = position
        }
        val editorDescription = getString(
            R.string.source_conflict_editor_at_position,
            activeConflictIndex + 1,
            offsets.size,
        )
        if (binding.sourceEditor.contentDescription?.toString() != editorDescription) {
            binding.sourceEditor.contentDescription = editorDescription
        }
        val text = binding.sourceEditor.text ?: return
        val activeOffset = offsets[activeConflictIndex]
        val block = activeConflictBlock()
        val key = "$activeOffset:${text.length}"
        if (key != shownConflictKey) {
            shownConflictKey = key
            binding.sourceConflictLocation.text = getString(
                R.string.source_conflict_location,
                text.lineIndexOf(activeOffset) + 1,
            )
        }
        val resolvable = block != null
        binding.sourceConflictMalformed.visibility = if (resolvable) View.GONE else View.VISIBLE
        binding.sourceConflictFedoraPanel.visibility = if (resolvable) View.VISIBLE else View.GONE
        binding.sourceConflictPhonePanel.visibility = if (resolvable) View.VISIBLE else View.GONE
        binding.sourceKeepBothHint.visibility = if (resolvable) View.VISIBLE else View.GONE
        binding.sourceUseFedora.isEnabled = resolvable
        binding.sourceUsePhone.isEnabled = resolvable
        binding.sourceKeepBoth.isEnabled = resolvable
        if (block != null) {
            renderConflictSides(
                block.selectedSide(text, ConflictTextRole.FEDORA_CURRENT).orEmpty(),
                block.selectedSide(text, ConflictTextRole.PHONE_INCOMING).orEmpty(),
            )
        }
        val multiple = offsets.size > 1
        binding.sourcePreviousConflict.isEnabled = multiple
        binding.sourceNextConflict.isEnabled = multiple
    }

    private var shownFedoraSide: String? = null
    private var shownPhoneSide: String? = null

    private fun renderConflictSides(fedora: String, phone: String) {
        if (fedora == shownFedoraSide && phone == shownPhoneSide) return
        shownFedoraSide = fedora
        shownPhoneSide = phone
        val fedoraShown = conflictSideDisplayText(fedora)
        val phoneShown = conflictSideDisplayText(phone)
        // Word matching is quadratic in the worst case; huge blocks are shown without it.
        val diff = if (fedoraShown.length + phoneShown.length <= MAX_WORD_DIFF_CHARS) {
            changedWordRanges(fedoraShown, phoneShown)
        } else {
            WordDiffRanges(emptyList(), emptyList())
        }
        binding.sourceConflictFedoraText.text = conflictSideText(
            fedoraShown,
            diff.oldRanges,
            R.color.source_conflict_fedora_word,
        )
        binding.sourceConflictPhoneText.text = conflictSideText(
            phoneShown,
            diff.newRanges,
            R.color.source_conflict_phone_word,
        )
    }

    private fun conflictSideText(
        text: String,
        changed: List<ConflictTextRange>,
        highlightColor: Int,
    ): CharSequence {
        if (text.isEmpty()) {
            return SpannableString(getString(R.string.source_conflict_side_empty)).apply {
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                    0,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        val color = ContextCompat.getColor(this, highlightColor)
        return SpannableString(text).apply {
            changed.forEach { range ->
                setSpan(
                    BackgroundColorSpan(color),
                    range.start,
                    range.endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun navigateConflict(delta: Int) {
        flushConflictRender()
        val count = conflictPresentation.navigationOffsets.size
        if (count == 0) return
        closeManualConflictEditor()
        activeConflictIndex = (activeConflictIndex + delta).mod(count)
        updateConflictControls()
        scrollToActiveConflict()
    }

    /** Where the editor's first row sits inside the scrolling page. */
    private fun editorTopInScroll(): Int = binding.sourceContent.top + binding.sourceEditor.top

    private fun scrollToActiveConflict() {
        val targetOffset = conflictPresentation.navigationOffsets
            .getOrNull(activeConflictIndex)
            ?.coerceIn(0, binding.sourceEditor.length())
            ?: return
        binding.sourceEditor.setSelection(targetOffset)
        if (binding.sourceContent.visibility != View.VISIBLE) {
            // The comparison is at the top of the page.
            binding.sourceScroll.post { binding.sourceScroll.smoothScrollTo(0, 0) }
            return
        }
        binding.sourceEditor.post {
            val layout = binding.sourceEditor.layout ?: return@post
            val targetLine = layout.getLineForOffset(targetOffset)
            val targetTop = editorTopInScroll() +
                binding.sourceEditor.totalPaddingTop +
                layout.getLineTop(targetLine)
            binding.sourceScroll.scrollTo(
                0,
                (targetTop - binding.sourceScroll.height / 3).coerceAtLeast(0),
            )
        }
    }

    private fun setShowRawConflictText(show: Boolean) {
        showRawConflictText = show
        conflictEditorState.showRawText = show
        if (binding.sourceShowRawText.isChecked != show) binding.sourceShowRawText.isChecked = show
        if (conflictView != ConflictView.MERGED) return
        flushConflictRender()
        if (conflictPresentation.navigationOffsets.isEmpty()) return
        binding.sourceContent.visibility = if (show) View.VISIBLE else View.GONE
        if (show) scrollToActiveConflict()
    }

    private var conflictActionInProgress = false

    /**
     * Replaces the active, complete conflict block with [replacement] and offers Undo. The editor
     * text stays the single source of truth, so Save, recovery and the marker checks are unchanged.
     */
    private fun replaceActiveConflict(
        replacement: (SourceConflictBlock, CharSequence) -> String?,
        doneMessage: Int,
    ) {
        if (conflictView != ConflictView.MERGED) return
        flushConflictRender()
        val text = binding.sourceEditor.text ?: return
        val block = activeConflictBlock() ?: return
        val newText = replacement(block, text) ?: return
        val conflictNumber = activeConflictIndex + 1
        val start = block.range.start
        val original = text.subSequence(start, block.range.endExclusive).toString()
        val nextIndex = activeConflictIndex
        conflictActionInProgress = true
        try {
            text.replace(start, block.range.endExclusive, newText)
            flushConflictRender()
        } finally {
            conflictActionInProgress = false
        }
        if (conflictPresentation.navigationOffsets.isNotEmpty()) {
            activeConflictIndex = nextIndex.coerceAtMost(
                conflictPresentation.navigationOffsets.lastIndex,
            )
            updateConflictControls()
            scrollToActiveConflict()
        }
        val message = buildString {
            append(getString(doneMessage, conflictNumber))
            if (conflictPresentation.navigationOffsets.isEmpty()) {
                append(' ')
                append(getString(R.string.source_conflict_all_resolved_short))
            }
        }
        showConflictSnackbar(message) {
            undoConflictReplacement(start, newText, original, nextIndex)
        }
    }

    private fun undoConflictReplacement(start: Int, replaced: String, original: String, index: Int) {
        if (conflictView != ConflictView.MERGED) return
        flushConflictRender()
        val text = binding.sourceEditor.text ?: return
        val end = start + replaced.length
        // Only undo if the replaced text is still exactly where it was put.
        if (end > text.length || text.subSequence(start, end).toString() != replaced) return
        text.replace(start, end, original)
        flushConflictRender()
        activeConflictIndex = index.coerceIn(
            0,
            (conflictPresentation.navigationOffsets.size - 1).coerceAtLeast(0),
        )
        updateConflictControls()
        scrollToActiveConflict()
    }

    private fun useConflictSide(role: ConflictTextRole) = replaceActiveConflict(
        replacement = { block, text -> block.selectedSide(text, role) },
        doneMessage = if (role == ConflictTextRole.FEDORA_CURRENT) {
            R.string.source_conflict_kept_fedora
        } else {
            R.string.source_conflict_kept_phone
        },
    )

    private fun keepBothConflictSides() = replaceActiveConflict(
        replacement = { block, text -> block.bothSides(text) },
        doneMessage = R.string.source_conflict_kept_both,
    )

    private fun editActiveConflictManually() {
        if (conflictView != ConflictView.MERGED) return
        flushConflictRender()
        val text = binding.sourceEditor.text ?: return
        val block = activeConflictBlock()
        if (block == null) {
            // Incomplete markers cannot be split into versions: fix them in the raw text.
            setShowRawConflictText(true)
            binding.sourceEditor.requestFocus()
            requestSourceEditorKeyboard()
            return
        }
        val phone = block.selectedSide(text, ConflictTextRole.PHONE_INCOMING).orEmpty()
        binding.sourceConflictManualText.setText(conflictSideDisplayText(phone))
        binding.sourceConflictManualEditor.visibility = View.VISIBLE
        binding.sourceConflictMoreActions.visibility = View.GONE
        binding.sourceKeepBothHint.visibility = View.GONE
        binding.sourceConflictManualText.requestFocus()
        binding.sourceConflictManualText.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(
                binding.sourceConflictManualText,
                InputMethodManager.SHOW_IMPLICIT,
            )
            binding.sourceScroll.smoothScrollTo(
                0,
                binding.sourceConflictGuide.top + binding.sourceConflictCard.top +
                    binding.sourceConflictManualEditor.top,
            )
        }
    }

    private fun closeManualConflictEditor() {
        if (binding.sourceConflictManualEditor.visibility != View.VISIBLE) return
        binding.sourceConflictManualEditor.visibility = View.GONE
        binding.sourceConflictMoreActions.visibility = View.VISIBLE
        binding.sourceKeepBothHint.visibility =
            if (activeConflictBlock() != null) View.VISIBLE else View.GONE
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(
            binding.sourceConflictManualText.windowToken,
            0,
        )
        binding.sourceConflictManualText.clearFocus()
    }

    private fun applyManualConflictText() {
        val edited = binding.sourceConflictManualText.text?.toString().orEmpty()
        closeManualConflictEditor()
        replaceActiveConflict(
            replacement = { block, text ->
                manualConflictReplacement(edited, block.trailingLineBreak(text))
            },
            doneMessage = R.string.source_conflict_kept_manual,
        )
        // Done typing: don't hand the keyboard on to the full-text editor.
        binding.sourceEditor.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(
            binding.root.windowToken,
            0,
        )
    }

    private fun showConflictSnackbar(message: String, undo: (() -> Unit)? = null) {
        com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar.make(
            binding.sourceEditorRoot,
            message,
            Snackbar.LENGTH_LONG,
        ).apply {
            anchorView = binding.sourceSaveActions
            if (undo != null) setAction(R.string.source_conflict_undo) { undo() }
        }.show()
    }

    private fun showSourceSelectionActions() {
        val editor = binding.sourceEditor
        if (!editor.hasSelection()) return
        sourceSelectionActionMode?.finish()
        sourceSelectionActionMode = editor.startActionMode(
            SourceSelectionActionModeCallback(editor) { finishedMode ->
                if (sourceSelectionActionMode === finishedMode) {
                    sourceSelectionActionMode = null
                }
            },
            ActionMode.TYPE_FLOATING,
        )
    }

    private fun dismissSourceSelectionActions() {
        sourceSelectionActionMode?.finish()
        sourceSelectionActionMode = null
    }

    private fun saveLocally(confirmedProposalRemoval: Boolean = false) {
        if (conflictId.isNotBlank()) {
            saveConflictResolution()
            return
        }
        val current = snapshot ?: return
        if (saving) return
        val contents = binding.sourceEditor.text?.toString().orEmpty()
        // A broken AI proposal makes Fedora refuse to build the PDF and can no longer be reviewed.
        if (introducedProposalIssues(current.sourceFile, lastSavedContents ?: current.contents, contents).isNotEmpty()) {
            setStatus(getString(R.string.source_editor_proposal_markers_broken))
            updateSaveEnabled()
            return
        }
        // Removing a wrapper by hand is not a decision (agent-proposal spec section 10.5): warn.
        if (!confirmedProposalRemoval && com.gitlab.mudlej.MjPdfReader.data.source.ProposalDecisionClaims
                .removedWithoutDecision(current.sourceFile, lastSavedContents ?: current.contents, contents).isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.source_editor_proposal_removed_title)
                .setMessage(R.string.source_editor_proposal_removed_message)
                .setPositiveButton(R.string.source_editor_proposal_removed_save) { _, _ -> saveLocally(confirmedProposalRemoval = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val revisionToSave = editTracker.revision
        saving = true
        binding.sourceSave.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    queue.saveForNextSync(current, pdfFilename, pdfSha256, contents)
                }
            }
            if (result.isSuccess) {
                lastSavedContents = contents
                editTracker.markRevisionSaved(revisionToSave)
                saving = false
                setStatus(getString(R.string.source_editor_draft_saved))
                updateSaveEnabled()
            } else {
                saving = false
                updateSaveEnabled()
                setStatus(getString(R.string.source_editor_save_failed))
            }
        }
    }

    private fun saveConflictResolution() {
        val bundle = conflictBundle ?: return
        if (saving || conflictView != ConflictView.MERGED) return
        val contents = binding.sourceEditor.text?.toString().orEmpty()
        if (contents.hasUnresolvedSourceConflictMarkers()) {
            setStatus(getString(R.string.source_conflict_markers_remaining))
            updateSaveEnabled()
            return
        }
        val revisionToSave = editTracker.revision
        saving = true
        setStatus("")
        updateSaveEnabled()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SourceConflictBackend.current.saveResolution(applicationContext, bundle, contents)
                }
            }
            saving = false
            result.fold(
                onSuccess = {
                    conflictMergedDraft = contents
                    lastSavedContents = contents
                    editTracker.markRevisionSaved(revisionToSave)
                    conflictSavedThisSession = true
                    conflictEditorState.savedThisSession = true
                    showConflictSnackbar(getString(R.string.source_conflict_resolution_saved))
                },
                onFailure = { error ->
                    setStatus(
                        getString(
                            R.string.source_conflict_resolution_save_failed,
                            error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                        ),
                    )
                },
            )
            updateSaveEnabled()
        }
    }

    private fun confirmDiscardConflict() {
        val bundle = conflictBundle ?: return
        if (saving) return
        discardConfirmation?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.source_conflict_discard_title)
            .setMessage(
                getString(
                    R.string.source_conflict_discard_message,
                    bundle.sourceFile.substringAfterLast('/'),
                    conflictSavedAtInline(bundle.incomingModifiedAt),
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.source_conflict_discard_confirm) { _, _ ->
                discardConflict(bundle)
            }
            .show()
        discardConfirmation = dialog
        // The confirming button is destructive; say so with the error color.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(
            MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError),
        )
    }

    private fun discardConflict(bundle: SourceConflictBundle) {
        saving = true
        binding.sourceDiscardConflict.isEnabled = false
        setStatus("")
        updateSaveEnabled()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SourceConflictBackend.current.discard(
                        applicationContext,
                        bundle.conflictId,
                        bundle.currentSha256,
                    )
                }
            }
            saving = false
            result.fold(
                onSuccess = { discarded ->
                    if (discarded) {
                        finish()
                    } else {
                        binding.sourceDiscardConflict.isEnabled = true
                        setStatus(
                            getString(
                                R.string.source_conflict_discard_failed,
                                getString(R.string.source_conflict_discard_local_failed),
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    binding.sourceDiscardConflict.isEnabled = true
                    setStatus(
                        getString(
                            R.string.source_conflict_discard_failed,
                            error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                        ),
                    )
                },
            )
            updateSaveEnabled()
        }
    }

    private fun requestClose() {
        if (saving) return
        if (!hasUnsavedDifference()) {
            finish()
            return
        }
        if (conflictId.isNotBlank() && conflictView != ConflictView.MERGED) {
            selectConflictTab(ConflictView.MERGED)
            showConflictView(ConflictView.MERGED)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.source_editor_unsaved_title)
            .setMessage(R.string.source_editor_unsaved_message)
            .setPositiveButton(R.string.save) { _, _ -> saveLocally() }
            .setNegativeButton(R.string.source_editor_discard) { _, _ -> finish() }
            .show()
    }

    private fun hasUnsavedDifference(): Boolean {
        val saved = lastSavedContents ?: return false
        val displayed = if (conflictId.isNotBlank() && conflictView != ConflictView.MERGED) {
            conflictMergedDraft
        } else {
            binding.sourceEditor.text
        }
        return displayed.differsFrom(saved)
    }

    private fun setupProseEditor() {
        val editor = binding.sourceEditor
        editor.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        editor.textSize = 16f
        editor.minWidth = 0
        editor.minimumWidth = 0
        editor.setHorizontallyScrolling(false)
        binding.sourceLineNumbers.visibility = View.GONE
        (binding.sourceSave.parent as android.view.ViewGroup).removeView(binding.sourceSave)
        binding.sourceToolbar.addView(binding.sourceSave, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = (12 * resources.displayMetrics.density).toInt() })
        binding.sourceSaveActions.visibility = View.GONE
        binding.sourceSave.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(
                com.google.android.material.color.MaterialColors.getColor(editor, com.google.android.material.R.attr.colorOnPrimary),
                com.google.android.material.color.MaterialColors.getColor(editor, com.google.android.material.R.attr.colorOnSurfaceVariant),
            ),
        ))
        binding.sourceSave.backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(
                com.google.android.material.color.MaterialColors.getColor(editor, androidx.appcompat.R.attr.colorPrimary),
                com.google.android.material.color.MaterialColors.getColor(editor, com.google.android.material.R.attr.colorSurfaceVariant),
            ),
        )
    }

    private var conflictRenderPending = false
    private val renderEditedConflict = Runnable {
        conflictRenderPending = false
        binding.sourceEditor.text?.let { renderConflictPresentation(it) }
    }

    /** Conflict navigation reads the presentation; bring it up to date with the last edit first. */
    private fun flushConflictRender() {
        if (!conflictRenderPending) return
        binding.sourceEditor.removeCallbacks(renderEditedConflict)
        renderEditedConflict.run()
    }

    private val highlightTex = Runnable {
        val text = binding.sourceEditor.text ?: return@Runnable
        val range = texHighlightChanges.takeRange(text) ?: return@Runnable
        val color = com.google.android.material.color.MaterialColors.getColor(
            binding.sourceEditor, com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        applySourceTexHighlights(text, range, color)
    }

    private fun updateSaveEnabled() {
        if (editTracker.isDirty && lastSavedContents?.length == binding.sourceEditor.length() && !hasUnsavedDifference()) {
            editTracker.markCurrentRevisionSaved()
        }
        val enabled = if (conflictId.isBlank()) {
            editorLoaded && !saving && editTracker.isDirty
        } else {
            conflictResolutionCanSave(
                editorLoaded = editorLoaded,
                saving = saving,
                mergedViewSelected = conflictView == ConflictView.MERGED,
                dirty = editTracker.isDirty,
                contents = binding.sourceEditor.text ?: "",
            )
        }
        if (binding.sourceSave.isEnabled != enabled) {
            binding.sourceSave.isEnabled = enabled
        }
        if (conflictId.isNotBlank()) updateConflictSaveHelper(enabled)
    }

    /** Say why Save is (not) available; runs per keystroke, so it only compares and sets text. */
    private fun updateConflictSaveHelper(canSave: Boolean) {
        val status = conflictSaveStatus(
            canSave = canSave,
            editorLoaded = editorLoaded,
            saving = saving,
            mergedViewSelected = conflictView == ConflictView.MERGED,
            dirty = editTracker.isDirty,
            remainingConflicts = conflictPresentation.navigationOffsets.size,
            malformed = conflictPresentation.hasMalformedMarkers,
            savedThisSession = conflictSavedThisSession,
        )
        if (status == shownConflictSaveStatus) return
        shownConflictSaveStatus = status
        binding.sourceSaveHelper.text = status.helperText(resources)
        binding.sourceSaveHelper.setTextColor(
            MaterialColors.getColor(
                binding.sourceSaveHelper,
                if (status.enabled) {
                    com.google.android.material.R.attr.colorOnSurface
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                },
            ),
        )
        binding.sourceDiscardConflict.isEnabled = conflictBundle != null && !saving
    }

    private fun setStatus(value: String) {
        val displayed = if (conflictId.isBlank() && value == getString(R.string.source_editor_offline_status)) {
            getString(R.string.source_editor_offline_compact)
        } else value
        if (binding.sourceStatus.text.toString() != displayed) {
            binding.sourceStatus.text = displayed
        }
        if (conflictId.isNotBlank()) {
            // The conflict screen explains itself elsewhere; this row is for loading and errors.
            binding.sourceToolbar.visibility = if (displayed.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun updateLineNumbers(lineCount: Int) {
        if (conflictId.isBlank()) return
        if (lineCount == renderedLineCount) return
        renderedLineCount = lineCount
        binding.sourceLineNumbers.setLineCount(lineCount)
    }

    override fun onResume() {
        super.onResume()
        SourceEditingGate.shared.editorResumed()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SourceEditingGate.shared.noteInput()
    }

    override fun onPause() {
        SourceEditingGate.shared.editorPaused()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) binding.sourceEditor.discardRecoveryState()
        binding.sourceEditor.removeCallbacks(highlightTex)
        binding.sourceEditor.removeCallbacks(renderEditedConflict)
        discardConfirmation?.dismiss()
        dismissSourceSelectionActions()
        if (binding.root.viewTreeObserver.isAlive) {
            binding.root.viewTreeObserver.removeOnPreDrawListener(
                sourceSelectionHandlePreDrawListener,
            )
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (conflictId.isNotBlank()) {
            conflictEditorState.draft = if (conflictView == ConflictView.MERGED) {
                binding.sourceEditor.text?.toString().orEmpty()
            } else {
                conflictMergedDraft
            }
            conflictEditorState.viewName = conflictView.name
            conflictEditorState.showRawText = showRawConflictText
        }
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "source_request_id"
        private const val EXTRA_CONFLICT_ID = "source_conflict_id"
        private const val EXTRA_CACHE_KEY = "source_cache_key"
        private const val EXTRA_PDF_FILENAME = "source_pdf_filename"
        private const val EXTRA_PDF_SHA256 = "source_pdf_sha256"
        private const val EXTRA_SELECTED_TEXT = "source_selected_text"
        private const val POLL_INTERVAL_MS = 500L
        private const val SOURCE_SELECTION_HANDLE_TOUCH_DP = 40
        private const val SOURCE_SELECTION_HANDLE_INSET_DP = 8
        private const val MAX_WORD_DIFF_CHARS = 20_000
        private const val CONFLICT_EDITOR_INPUT_TYPE = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        fun intent(
            context: Context,
            requestId: String,
            cacheKey: String,
            pdfFilename: String,
            pdfSha256: String,
            selectedText: String,
        ) = ProposalSourceActivity.sourceIntent(context, requestId, cacheKey, pdfFilename, pdfSha256, selectedText)

        fun conflictIntent(context: Context, conflictId: String): Intent {
            require(conflictId.isNotBlank())
            return Intent(context, SourceEditorActivity::class.java).apply {
                putExtra(EXTRA_CONFLICT_ID, conflictId)
            }
        }
    }
}

// ViewModelProvider constructs this class reflectively, so both the class and its
// zero-argument constructor must remain publicly accessible on the JVM.
class ConflictEditorViewModel : ViewModel() {
    var draft: String? = null
    var viewName: String? = null
    var conflictIndex: Int = 0
    var showRawText: Boolean = false
    var savedThisSession: Boolean = false
}

private data class ConflictColors(
    val background: Int,
    val foreground: Int,
)

private class ConflictLineBackgroundSpan(
    private val color: Int,
) : LineBackgroundSpan {
    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val previousColor = paint.color
        paint.color = color
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        paint.color = previousColor
    }
}

private class ConflictTextColorSpan(
    private val color: Int,
) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.color = color
    }
}

/** The conflict screen's tabs, in tab order. */
private enum class ConflictView {
    MERGED,
    FEDORA,
    PHONE,
}

internal fun sourceConflictTimestampEpochMillis(value: String?): Long? {
    val candidate = value?.takeIf(String::isNotBlank) ?: return null
    return runCatching { Instant.parse(candidate).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(candidate).toInstant().toEpochMilli() }.getOrNull()
}

internal fun centeredSourceSelectionScrollY(
    selectionTop: Int,
    selectionBottom: Int,
    viewportHeight: Int,
): Int {
    if (viewportHeight <= 0) return 0
    val safeTop = selectionTop.coerceAtLeast(0)
    val safeBottom = selectionBottom.coerceAtLeast(safeTop)
    val selectionCenter = safeTop + (safeBottom - safeTop) / 2
    return (selectionCenter - viewportHeight / 2).coerceAtLeast(0)
}

internal fun shouldOpenSourceEditorKeyboard(
    resolvingConflict: Boolean,
    editorAlreadyLoaded: Boolean,
): Boolean = !resolvingConflict && !editorAlreadyLoaded

private data class SourceEditorCaretPosition(
    val screenX: Float,
    val lineCenterScreenY: Float,
    val lineBottomScreenY: Float,
)

private class SourceSelectionActionModeCallback(
    private val editor: EditText,
    private val onDestroyed: (ActionMode) -> Unit,
) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.title = null
        mode.subtitle = null
        mode.titleOptionalHint = true
        addAction(menu, android.R.id.cut, android.R.string.cut, order = 1)
        addAction(menu, android.R.id.copy, android.R.string.copy, order = 2)
        val clipboard = editor.context.getSystemService(ClipboardManager::class.java)
        if (clipboard?.hasPrimaryClip() == true) {
            addAction(menu, android.R.id.paste, android.R.string.paste, order = 3)
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId !in TEXT_ACTION_IDS) return false
        val handled = editor.onTextContextMenuItem(item.itemId)
        if (handled) mode.finish()
        return handled
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        onDestroyed(mode)
    }

    override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: Rect) {
        val layout = editor.layout ?: return super.onGetContentRect(mode, view, outRect)
        val selectionStart = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val selectionEnd = maxOf(editor.selectionStart, editor.selectionEnd)
            .minus(1)
            .coerceAtLeast(selectionStart)
        val startLine = layout.getLineForOffset(selectionStart)
        val endLine = layout.getLineForOffset(selectionEnd)
        val visible = Rect()
        if (!editor.getLocalVisibleRect(visible)) {
            return super.onGetContentRect(mode, view, outRect)
        }
        val top = editor.totalPaddingTop + layout.getLineTop(startLine)
        val bottom = editor.totalPaddingTop + layout.getLineBottom(endLine)
        val left: Int
        val right: Int
        if (startLine == endLine) {
            left = editor.totalPaddingLeft + layout.getPrimaryHorizontal(selectionStart).toInt()
            right = editor.totalPaddingLeft + layout.getPrimaryHorizontal(selectionEnd + 1).toInt()
        } else {
            left = visible.left
            right = visible.right
        }
        outRect.set(
            minOf(left, right).coerceIn(visible.left, visible.right),
            top.coerceIn(visible.top, visible.bottom),
            maxOf(left, right).coerceIn(visible.left, visible.right),
            bottom.coerceIn(visible.top, visible.bottom),
        )
    }

    private fun addAction(menu: Menu, id: Int, title: Int, order: Int) {
        menu.add(Menu.NONE, id, order, title)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    private companion object {
        val TEXT_ACTION_IDS = setOf(android.R.id.cut, android.R.id.copy, android.R.id.paste)
    }
}

internal class SourceEditorEditTracker {
    var lineCount: Int = 1
        private set
    var revision: Long = 0
        private set
    private var savedRevision: Long = 0

    val isDirty: Boolean
        get() = revision != savedRevision

    fun applyTextChange(
        removedLineBreaks: Int,
        insertedLineBreaks: Int,
        isUserEdit: Boolean,
    ): Boolean {
        require(removedLineBreaks >= 0)
        require(insertedLineBreaks >= 0)
        val previousLineCount = lineCount
        lineCount = (lineCount - removedLineBreaks + insertedLineBreaks).coerceAtLeast(1)
        if (isUserEdit) {
            revision += 1
        }
        return lineCount != previousLineCount
    }

    fun markCurrentRevisionSaved() {
        savedRevision = revision
    }

    fun markRevisionSaved(saved: Long) {
        require(saved in 0..revision)
        savedRevision = saved
    }
}

internal fun CharSequence?.countLineBreaks(start: Int, count: Int): Int {
    if (this == null || count <= 0) return 0
    val from = start.coerceIn(0, length)
    val to = from + count.coerceAtMost(length - from)
    var lineBreaks = 0
    for (index in from until to) {
        if (this[index] == '\n') {
            lineBreaks += 1
        }
    }
    return lineBreaks
}

internal fun CharSequence?.differsFrom(saved: String): Boolean {
    if (this == null) return saved.isNotEmpty()
    return length != saved.length || !saved.contentEquals(this)
}

/**
 * Git conflict markers are structural only when they occupy the beginning of a source line.
 * A TeX sentence that merely mentions a marker must remain editable and saveable.
 */
internal fun CharSequence.hasUnresolvedSourceConflictMarkers(): Boolean {
    // Runs on every edit of the conflict editor: scan in place instead of allocating each line.
    // Lines end at \r\n, \n or \r, as in lineSequence().
    var start = 0
    while (true) {
        var end = start
        while (end < length && this[end] != '\n' && this[end] != '\r') end++
        fun startsWith(prefix: String) = end - start >= prefix.length && regionMatches(start, prefix, 0, prefix.length)
        if (startsWith("<<<<<<<") || startsWith("|||||||") || startsWith(">>>>>>>") ||
            (end - start == 7 && startsWith("======="))
        ) return true
        if (end >= length) return false
        start = if (this[end] == '\r' && end + 1 < length && this[end + 1] == '\n') end + 2 else end + 1
    }
}

internal fun CharSequence.firstSourceConflictMarkerOffset(): Int =
    Regex("(?m)^<<<<<<<").find(this)?.range?.first ?: 0

internal fun conflictResolutionCanSave(
    editorLoaded: Boolean,
    saving: Boolean,
    mergedViewSelected: Boolean,
    dirty: Boolean,
    contents: CharSequence,
): Boolean = editorLoaded && !saving && mergedViewSelected && dirty &&
    !contents.hasUnresolvedSourceConflictMarkers()

internal data class SourceTextSelection(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0)
        require(endExclusive > start)
    }
}

internal fun draggedSourceTextSelection(
    current: SourceTextSelection,
    candidateOffset: Int,
    draggingStart: Boolean,
    textLength: Int,
): SourceTextSelection {
    require(textLength >= current.endExclusive)
    val candidate = candidateOffset.coerceIn(0, textLength)
    return if (draggingStart) {
        SourceTextSelection(
            start = candidate.coerceAtMost(current.endExclusive - 1),
            endExclusive = current.endExclusive,
        )
    } else {
        SourceTextSelection(
            start = current.start,
            endExclusive = candidate.coerceAtLeast(current.start + 1),
        )
    }
}

internal fun findSourceTextSelection(
    contents: String,
    selectedText: String,
    mappedLine: Int,
): SourceTextSelection? {
    val needle = selectedText.trim { it.isMatchingWhitespace() }
    if (needle.length < MIN_SOURCE_SELECTION_LENGTH || contents.isEmpty()) return null

    val exactMatches = findTextMatches(contents, needle) ?: return null
    if (exactMatches.isNotEmpty()) {
        return exactMatches.uniqueClosestToLine(contents, mappedLine)
    }

    val normalizedNeedle = needle.collapseMatchingWhitespace()
    if (normalizedNeedle.length < MIN_SOURCE_SELECTION_LENGTH) return null
    val projection = contents.whitespaceProjection()
    val projectedMatches = findTextMatches(projection.text, normalizedNeedle) ?: return null
    val sourceMatches = projectedMatches.map { match ->
        SourceTextSelection(
            start = projection.sourceOffsets[match.start],
            endExclusive = if (match.endExclusive < projection.text.length) {
                projection.sourceOffsets[match.endExclusive]
            } else {
                contents.length
            },
        )
    }
    return sourceMatches.uniqueClosestToLine(contents, mappedLine)
}

private data class WhitespaceProjection(
    val text: String,
    val sourceOffsets: IntArray,
)

private fun findTextMatches(
    contents: String,
    needle: String,
): List<SourceTextSelection>? {
    val matches = mutableListOf<SourceTextSelection>()
    var fromIndex = 0
    while (fromIndex <= contents.length - needle.length) {
        val start = contents.indexOf(needle, fromIndex)
        if (start < 0) break
        matches += SourceTextSelection(start, start + needle.length)
        if (matches.size > MAX_SOURCE_SELECTION_CANDIDATES) return null
        fromIndex = start + needle.length
    }
    return matches
}

private fun List<SourceTextSelection>.uniqueClosestToLine(
    contents: String,
    mappedLine: Int,
): SourceTextSelection? {
    if (size == 1) return single()
    if (isEmpty()) return null
    val lineStarts = contents.lineStartOffsets()
    val targetLine = mappedLine.coerceAtLeast(1)
    val ranked = map { match ->
        val found = lineStarts.binarySearch(match.start)
        val line = if (found >= 0) found + 1 else -found - 1
        match to kotlin.math.abs(line - targetLine)
    }
    val closestDistance = ranked.minOf { it.second }
    return ranked.singleOrNull { it.second == closestDistance }?.first
}

private fun String.whitespaceProjection(): WhitespaceProjection {
    val projected = StringBuilder(length)
    val sourceOffsets = IntArray(length)
    var projectedIndex = 0
    var inWhitespace = false
    forEachIndexed { sourceIndex, character ->
        if (character.isMatchingWhitespace()) {
            if (!inWhitespace) {
                projected.append(' ')
                sourceOffsets[projectedIndex++] = sourceIndex
            }
            inWhitespace = true
        } else {
            projected.append(character)
            sourceOffsets[projectedIndex++] = sourceIndex
            inWhitespace = false
        }
    }
    return WhitespaceProjection(projected.toString(), sourceOffsets)
}

private fun String.collapseMatchingWhitespace(): String {
    val collapsed = StringBuilder(length)
    var inWhitespace = false
    forEach { character ->
        if (character.isMatchingWhitespace()) {
            if (!inWhitespace) collapsed.append(' ')
            inWhitespace = true
        } else {
            collapsed.append(character)
            inWhitespace = false
        }
    }
    return collapsed.toString()
}

private fun Char.isMatchingWhitespace(): Boolean =
    isWhitespace() || Character.isSpaceChar(this)

private fun String.lineStartOffsets(): IntArray {
    val starts = IntArray(count { it == '\n' } + 1)
    var next = 1
    forEachIndexed { index, character ->
        if (character == '\n') starts[next++] = index + 1
    }
    return starts
}

private fun String.lineStartOffset(oneBasedLine: Int): Int {
    if (oneBasedLine <= 1) return 0
    var line = 1
    forEachIndexed { index, character ->
        if (character == '\n' && ++line == oneBasedLine) return index + 1
    }
    return length
}

private const val MIN_SOURCE_SELECTION_LENGTH = 3
private const val MAX_SOURCE_SELECTION_CANDIDATES = 1_024
