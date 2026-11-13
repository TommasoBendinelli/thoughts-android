// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAuthor
import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.convertDateString
import com.gitlab.mudlej.MjPdfReader.core.io.openChatGptLink
import com.gitlab.mudlej.MjPdfReader.core.io.formatRelativeDate
import com.gitlab.mudlej.MjPdfReader.core.io.parseCommentTimestamp
import com.gitlab.mudlej.MjPdfReader.core.io.parsePdfDateTime
import com.gitlab.mudlej.MjPdfReader.core.io.pdfDateNow
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCreationMetadata
import com.gitlab.mudlej.MjPdfReader.data.annotation.normalizeChatGptUrl
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PdfCommentMessageRowBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PdfCommentSheetBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorConfig
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorDialog
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorNeutralAction
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorValue
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ReplySheetPresentation {
    THREAD,
    COMPOSER_ONLY,
    THREAD_WITH_IME,
}

internal fun replySheetPresentation(
    imeVisible: Boolean,
    threadExpandedForIme: Boolean,
): ReplySheetPresentation = when {
    !imeVisible -> ReplySheetPresentation.THREAD
    threadExpandedForIme -> ReplySheetPresentation.THREAD_WITH_IME
    else -> ReplySheetPresentation.COMPOSER_ONLY
}

internal fun shouldForwardCommentSheetTouchToPdf(
    rawX: Float,
    rawY: Float,
    pdfLeft: Int,
    pdfTop: Int,
    pdfRight: Int,
    pdfBottom: Int,
    sheetTop: Int,
    blockedByUiControl: Boolean,
): Boolean {
    if (blockedByUiControl) return false
    val visiblePdfBottom = minOf(pdfBottom, sheetTop)
    return rawX >= pdfLeft &&
        rawX < pdfRight &&
        rawY >= pdfTop &&
        rawY < visiblePdfBottom
}

internal fun contentSizedCommentSheetHeight(
    contentHeight: Int,
    fixedHeight: Int,
    availableHeight: Int,
    minimumHeight: Int = 0,
): Int {
    if (availableHeight <= 0) return 0
    val naturalHeight = contentHeight.coerceAtLeast(0) + fixedHeight.coerceAtLeast(0)
    return maxOf(naturalHeight, minimumHeight.coerceAtLeast(0))
        .coerceIn(1, availableHeight)
}

class PdfCommentSheetController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val scope: CoroutineScope,
    private val onAnnotationEdit: (AnnotationEdit) -> Unit,
    private val getCommentMetadata: (String) -> AnnotationController.CommentRootMetadata?,
    private val getCommentChatGptUrl: (String) -> String?,
    private val canMutatePdfComments: () -> Boolean,
    private val documentFilename: () -> String,
) {
    private sealed interface SheetMode {
        data class Logical(
            val selectedCommentId: String,
            val pdfFilename: String,
            val pdfSha256: String,
        ) : SheetMode
    }

    private val logicalCommentStore = LogicalCommentThreadStore(activity.applicationContext, documentFilename)
    private val commentService = com.gitlab.mudlej.MjPdfReader.data.annotation.CommentService(
        activity.applicationContext, documentFilename,
    )
    private var sheetContainer: FrameLayout? = null
    private var sheetBehavior: BottomSheetBehavior<FrameLayout>? = null
    private var sheetCallback: BottomSheetBehavior.BottomSheetCallback? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var sheetBinding: PdfCommentSheetBinding? = null
    private var loadJob: Job? = null
    private var commentChangesJob: Job? = null
    private var sheetMode: SheetMode? = null
    private var logicalThreads: List<LogicalCommentThread> = emptyList()
    private var selectedThreadIndex = 0
    private var pdfPassthroughEnabled = true
    private var forwardingPdfGesture = false
    private var sheetContentGeneration = 0
    private var sheetRequestGeneration = 0

    fun deleteComment(id: String) {
        scope.launch {
            if (!removeComments(listOf(id))) showCommentFailure()
        }
    }

    private fun offerDeletionUndo(result: org.tbend.thoughts.sdk.CommentResult) {
        if (result.historyToken == 0) return
        AppSnackbar.make(binding.root, R.string.delete, Snackbar.LENGTH_LONG)
            .setAction(R.string.overview_undo) {
                scope.launch {
                    val restored = withContext(Dispatchers.IO) { commentService.undo(result.historyToken) }
                    if (!restored.success) showCommentFailure()
                    (activity as? MainActivity)?.applyLogicalCommentOverlays()
                    dismiss()
                }
            }.show()
    }

    fun isShowing(): Boolean = sheetContainer != null

    fun show(comment: PDFView.PdfComment) {
        val hash = (activity as? MainActivity)?.commentDocumentHash() ?: return showCommentFailure()
        showLogical(comment.name.substringBefore("#oe-fragment-"), documentFilename(), hash)
    }

    fun showLogical(commentId: String, pdfFilename: String, pdfSha256: String) {
        (activity as? MainActivity)?.dismissTableOfContentsSheet()
        dismiss()
        val requestedMode = SheetMode.Logical(commentId, pdfFilename, pdfSha256)
        sheetMode = requestedMode
        selectedThreadIndex = 0
        val requestGeneration = ++sheetRequestGeneration
        loadJob = scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                logicalCommentStore.load(commentId, pdfFilename, pdfSha256)
            }
            if (sheetRequestGeneration != requestGeneration || sheetMode != requestedMode) {
                return@launch
            }
            if (snapshot == null || snapshot.threads.isEmpty()) {
                sheetMode = null
                showCommentFailure()
                return@launch
            }
            logicalThreads = snapshot.threads
            selectedThreadIndex = snapshot.selectedThreadIndex
            sheetMode = requestedMode.copy(
                selectedCommentId = logicalThreads[selectedThreadIndex].root.id,
            )
            openSheet()
            renderSelectedThread()
            observeLogicalComments()
        }
    }

    private fun observeLogicalComments() {
        commentChangesJob?.cancel()
        commentChangesJob = scope.launch {
            com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity.applicationContext).changes().collect {
                val mode = sheetMode as? SheetMode.Logical ?: return@collect
                val snapshot = withContext(Dispatchers.IO) {
                    logicalCommentStore.load(mode.selectedCommentId, mode.pdfFilename, mode.pdfSha256, selectFirstIfMissing = true)
                }
                if (sheetMode != mode || sheetContainer == null) return@collect
                if (snapshot == null) { dismiss(); return@collect }
                if (snapshot.threads != logicalThreads) {
                    logicalThreads = snapshot.threads
                    selectedThreadIndex = snapshot.selectedThreadIndex
                    sheetMode = mode.copy(selectedCommentId = logicalThreads[selectedThreadIndex].root.id)
                    renderSelectedThread()
                }
            }
        }
    }

    private fun openSheet(initialFocus: () -> Unit = {}) {
        val host = binding.commentSheetHost
        val newBinding = PdfCommentSheetBinding.inflate(activity.layoutInflater, host, false)
        val newContainer = FrameLayout(activity).apply {
            isClickable = true
            visibility = View.INVISIBLE
        }
        newContainer.addView(
            newBinding.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val newBehavior = BottomSheetBehavior<FrameLayout>().apply {
            isFitToContents = true
            isHideable = true
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        host.visibility = View.VISIBLE
        (activity as? MainActivity)?.setPdfCommentTouchInterceptor { event ->
            if (!pdfPassthroughEnabled) {
                return@setPdfCommentTouchInterceptor false
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val sheetLocation = IntArray(2)
                newContainer.getLocationOnScreen(sheetLocation)
                val pdfBounds = Rect()
                forwardingPdfGesture = binding.pdfView.getGlobalVisibleRect(pdfBounds) &&
                    shouldForwardCommentSheetTouchToPdf(
                        rawX = event.rawX,
                        rawY = event.rawY,
                        pdfLeft = pdfBounds.left,
                        pdfTop = pdfBounds.top,
                        pdfRight = pdfBounds.right,
                        pdfBottom = pdfBounds.bottom,
                        sheetTop = sheetLocation[1],
                        blockedByUiControl = binding.root.hasClickableDescendantAt(
                            rawX = event.rawX,
                            rawY = event.rawY,
                            excludedSubtree = binding.pdfView,
                        ),
                    )
            }
            if (!forwardingPdfGesture) {
                return@setPdfCommentTouchInterceptor false
            }
            val pdfLocation = IntArray(2)
            binding.pdfView.getLocationOnScreen(pdfLocation)
            val forwarded = MotionEvent.obtain(event).apply {
                setLocation(event.rawX - pdfLocation[0], event.rawY - pdfLocation[1])
            }
            binding.pdfView.dispatchTouchEvent(forwarded)
            forwarded.recycle()
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                forwardingPdfGesture = false
            }
            true
        }
        host.addView(
            newContainer,
            CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply {
                behavior = newBehavior
            },
        )

        sheetContainer = newContainer
        sheetBehavior = newBehavior
        sheetBinding = newBinding

        newBinding.previousCommentButton.setOnClickListener {
            selectThread(selectedThreadIndex - 1)
        }
        newBinding.nextCommentButton.setOnClickListener {
            selectThread(selectedThreadIndex + 1)
        }
        newBinding.replyInputLayout.setEndIconOnClickListener { submitReply() }
        newBinding.replyEditText.doAfterTextChanged {
            newBinding.replyInputLayout.isEndIconVisible = !it.isNullOrBlank()
        }
        newBinding.replyEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitReply()
                true
            } else {
                false
            }
        }
        newBinding.replyInputLayout.isEndIconVisible = false

        var imeWasVisible = false
        var threadExpandedForIme = false
        var lastPresentation = ReplySheetPresentation.THREAD
        var requestedSheetState = BottomSheetBehavior.STATE_EXPANDED
        var measuredContentGeneration = -1
        var measuredContentWidth = -1
        var measuredContentHeight = 0
        var revealStarted = false

        fun applyReplyPresentation(presentation: ReplySheetPresentation): Boolean {
            val presentationChanged = presentation != lastPresentation
            val threadVisible = presentation != ReplySheetPresentation.COMPOSER_ONLY
            val expandButtonVisible = presentation != ReplySheetPresentation.THREAD
            newBinding.commentSheetHandle.visibility =
                if (threadVisible) View.VISIBLE else View.GONE
            newBinding.commentNavigationRow.visibility =
                if (threadVisible) View.VISIBLE else View.GONE
            newBinding.commentThreadScroll.visibility =
                if (threadVisible) View.VISIBLE else View.GONE
            newBinding.replyExpandButton.visibility =
                if (expandButtonVisible) View.VISIBLE else View.GONE
            newBinding.replyExpandButton.setImageResource(
                if (presentation == ReplySheetPresentation.THREAD_WITH_IME) {
                    R.drawable.ic_small_arrow_down
                } else {
                    R.drawable.ic_small_arrow_up
                }
            )
            newBinding.replyExpandButton.contentDescription = activity.getString(
                if (presentation == ReplySheetPresentation.THREAD_WITH_IME) {
                    R.string.collapse
                } else {
                    R.string.expand
                }
            )

            val targetState: Int?
            var layoutChanged = presentationChanged
            when (presentation) {
                ReplySheetPresentation.THREAD -> {
                    newBehavior.isDraggable = true
                    targetState = if (presentationChanged) {
                        BottomSheetBehavior.STATE_EXPANDED
                    } else {
                        null
                    }
                }
                ReplySheetPresentation.COMPOSER_ONLY -> {
                    newBehavior.isDraggable = false
                    targetState = BottomSheetBehavior.STATE_EXPANDED
                }
                ReplySheetPresentation.THREAD_WITH_IME -> {
                    newBehavior.isDraggable = true
                    targetState = BottomSheetBehavior.STATE_EXPANDED
                }
            }
            val stateNeedsUpdate = targetState != null && when (newBehavior.state) {
                BottomSheetBehavior.STATE_DRAGGING,
                BottomSheetBehavior.STATE_SETTLING -> requestedSheetState != targetState
                else -> newBehavior.state != targetState
            }
            if (stateNeedsUpdate) {
                layoutChanged = true
            }
            lastPresentation = presentation
            if (layoutChanged) {
                newContainer.requestLayout()
                if (targetState != null) {
                    requestedSheetState = targetState
                    newBehavior.state = targetState
                }
            }
            return layoutChanged
        }

        fun syncVisibleLayout() {
            if (sheetContainer !== newContainer) {
                return
            }
            val imeVisible = ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible && !imeWasVisible) {
                threadExpandedForIme = false
            } else if (!imeVisible && imeWasVisible) {
                threadExpandedForIme = false
            }
            imeWasVisible = imeVisible
            val presentation = replySheetPresentation(imeVisible, threadExpandedForIme)
            if (applyReplyPresentation(presentation)) {
                newContainer.post(::syncVisibleLayout)
                return
            }

            val visibleWindow = Rect()
            binding.root.getWindowVisibleDisplayFrame(visibleWindow)
            val hostLocation = IntArray(2)
            host.getLocationOnScreen(hostLocation)
            val availableHeight = minOf(
                host.height,
                visibleWindow.bottom - hostLocation[1],
            )
            if (host.width <= 0 || availableHeight <= 0) {
                newContainer.postOnAnimation(::syncVisibleLayout)
                return
            }
            val fixedHeight = newBinding.root.paddingTop + newBinding.root.paddingBottom +
                newBinding.commentSheetHandle.outerMeasuredHeight() +
                newBinding.commentNavigationRow.outerMeasuredHeight() +
                newBinding.replyComposerRow.outerMeasuredHeight() +
                newBinding.commentThreadScroll.verticalMargins()
            val contentWidth = (
                host.width - newBinding.root.paddingLeft - newBinding.root.paddingRight -
                    newBinding.commentThreadScroll.paddingLeft -
                    newBinding.commentThreadScroll.paddingRight
                ).coerceAtLeast(1)
            val contentHeight = if (newBinding.commentThreadScroll.visibility == View.VISIBLE) {
                if (measuredContentGeneration != sheetContentGeneration ||
                    measuredContentWidth != contentWidth
                ) {
                    newBinding.commentMessagesContainer.measure(
                        View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                    measuredContentGeneration = sheetContentGeneration
                    measuredContentWidth = contentWidth
                    measuredContentHeight = newBinding.commentMessagesContainer.measuredHeight +
                        newBinding.commentThreadScroll.paddingTop +
                        newBinding.commentThreadScroll.paddingBottom
                }
                measuredContentHeight
            } else {
                0
            }
            val minimumHeight = if (binding.pdfView.isPdfCommentMoveActive) {
                (availableHeight * INITIAL_SHEET_RATIO).toInt()
            } else {
                0
            }
            val desiredHeight = contentSizedCommentSheetHeight(
                contentHeight = contentHeight,
                fixedHeight = fixedHeight,
                availableHeight = availableHeight,
                minimumHeight = minimumHeight,
            )
            if (newContainer.layoutParams.height != desiredHeight) {
                newContainer.layoutParams = newContainer.layoutParams.apply {
                    height = desiredHeight
                }
                newContainer.requestLayout()
                newContainer.postOnAnimation(::syncVisibleLayout)
                return
            }

            val sheetLocation = IntArray(2)
            newContainer.getLocationOnScreen(sheetLocation)
            val visibleHeight = visibleWindow.bottom - sheetLocation[1]
            if (imeVisible && visibleHeight <= 0) {
                newContainer.postOnAnimation(::syncVisibleLayout)
                return
            }
            val pdfLocation = IntArray(2)
            binding.pdfView.getLocationOnScreen(pdfLocation)
            val visiblePdfBottom = (sheetLocation[1] - pdfLocation[1])
                .coerceIn(0, binding.pdfView.height)
            if (binding.pdfView.width > 0 && visiblePdfBottom > 0) {
                binding.pdfView.setContentViewport(
                    RectF(
                        0f,
                        0f,
                        binding.pdfView.width.toFloat(),
                        visiblePdfBottom.toFloat(),
                    )
                )
            }
            if (!revealStarted) {
                revealStarted = true
                initialFocus()
                newContainer.translationY = desiredHeight.toFloat()
                newContainer.visibility = View.VISIBLE
                newContainer.animate()
                    .translationY(0f)
                    .setDuration(SHEET_REVEAL_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        if (sheetContainer === newContainer) {
                            newContainer.translationY = 0f
                            syncVisibleLayout()
                        }
                    }
                    .start()
            }
        }

        newBinding.replyExpandButton.setOnClickListener {
            val imeVisible = ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible) {
                threadExpandedForIme = !threadExpandedForIme
                newBinding.replyEditText.requestFocus()
                WindowCompat.getInsetsController(activity.window, binding.root)
                    .show(WindowInsetsCompat.Type.ime())
                syncVisibleLayout()
            }
        }

        newContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            newContainer.post(::syncVisibleLayout)
        }
        val newCallback = object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    dismiss()
                } else {
                    syncVisibleLayout()
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                syncVisibleLayout()
            }
        }
        newBehavior.addBottomSheetCallback(newCallback)
        sheetCallback = newCallback
        val newGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            syncVisibleLayout()
        }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(newGlobalLayoutListener)
        globalLayoutListener = newGlobalLayoutListener

        newContainer.post(::syncVisibleLayout)

    }

    private fun View.hasClickableDescendantAt(
        rawX: Float,
        rawY: Float,
        excludedSubtree: View,
    ): Boolean {
        if (this === excludedSubtree || !isShown || alpha <= 0f) return false
        val bounds = Rect()
        if (!getGlobalVisibleRect(bounds) ||
            rawX < bounds.left ||
            rawX >= bounds.right ||
            rawY < bounds.top ||
            rawY >= bounds.bottom
        ) {
            return false
        }
        if (this is ViewGroup) {
            for (index in childCount - 1 downTo 0) {
                if (getChildAt(index).hasClickableDescendantAt(rawX, rawY, excludedSubtree)) {
                    return true
                }
            }
        }
        return isClickable || isLongClickable || hasOnClickListeners()
    }

    private fun View.outerMeasuredHeight(): Int {
        if (visibility == View.GONE) return 0
        val margins = layoutParams as? ViewGroup.MarginLayoutParams
        return measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
    }

    private fun View.verticalMargins(): Int {
        if (visibility == View.GONE) return 0
        val margins = layoutParams as? ViewGroup.MarginLayoutParams
        return (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
    }

    fun dismiss(): Boolean {
        commentChangesJob?.cancel()
        commentChangesJob = null
        val activeContainer = sheetContainer
        val hadPendingOpen = loadJob?.isActive == true
        if (activeContainer == null && !hadPendingOpen) return false
        val activeBehavior = sheetBehavior
        val activeCallback = sheetCallback
        val activeGlobalLayoutListener = globalLayoutListener
        sheetRequestGeneration++
        sheetContainer = null
        sheetBehavior = null
        sheetCallback = null
        globalLayoutListener = null
        sheetBinding = null
        loadJob?.cancel()
        loadJob = null
        sheetMode = null
        logicalThreads = emptyList()
        if (activeContainer == null) {
            return true
        }
        activeContainer.animate().cancel()
        if (activeBehavior != null && activeCallback != null) {
            activeBehavior.removeBottomSheetCallback(activeCallback)
        }
        if (activeGlobalLayoutListener != null && binding.root.viewTreeObserver.isAlive) {
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(activeGlobalLayoutListener)
        }
        (activeContainer.parent as? ViewGroup)?.removeView(activeContainer)
        if (binding.commentSheetHost.childCount == 0) {
            (activity as? MainActivity)?.setPdfCommentTouchInterceptor(null)
            forwardingPdfGesture = false
            binding.commentSheetHost.visibility = View.GONE
        }
        binding.pdfView.clearContentViewport()
        binding.pdfView.cancelPdfCommentMove()
        binding.pdfView.clearSelectedPdfComment()
        return true
    }

    fun handleBack(): Boolean {
        if (binding.pdfView.isPdfCommentMoveActive) {
            binding.pdfView.cancelPdfCommentMove()
            sheetContainer?.requestLayout()
            return true
        }
        val currentBinding = sheetBinding ?: return dismiss()
        if (currentBinding.replyEditText.hasFocus()) {
            currentBinding.replyEditText.clearFocus()
            WindowCompat.getInsetsController(activity.window, binding.root)
                .hide(WindowInsetsCompat.Type.ime())
            return true
        }
        return dismiss()
    }

    private fun reloadLogicalThreads(
        preferredCommentId: String,
        focusSelection: Boolean = false,
    ) {
        val expectedContainer = sheetContainer ?: return
        val mode = sheetMode as? SheetMode.Logical ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                logicalCommentStore.load(
                    preferredCommentId,
                    mode.pdfFilename,
                    mode.pdfSha256,
                )
            }
            if (sheetContainer !== expectedContainer || sheetMode != mode) {
                return@launch
            }
            if (snapshot == null || snapshot.threads.isEmpty()) {
                dismiss()
                showCommentFailure()
                return@launch
            }
            logicalThreads = snapshot.threads
            selectedThreadIndex = snapshot.selectedThreadIndex
            sheetMode = mode.copy(selectedCommentId = logicalThreads[selectedThreadIndex].root.id)
            renderSelectedThread()
            if (focusSelection) {
                focusLogicalThread(logicalThreads[selectedThreadIndex])
            }
        }
    }

    private fun selectThread(index: Int) {
        val valid = when (sheetMode) {
            is SheetMode.Logical -> index in logicalThreads.indices
            null -> false
        }
        if (!valid) return
        selectedThreadIndex = index
        renderSelectedThread()
        when (sheetMode) {
            is SheetMode.Logical -> focusLogicalThread(logicalThreads[index])
            null -> Unit
        }
    }

    private fun renderSelectedThread() {
        when (sheetMode) {
            is SheetMode.Logical -> renderSelectedLogicalThread()
            null -> Unit
        }
    }

    private fun renderSelectedLogicalThread() {
        val currentBinding = sheetBinding ?: return
        val thread = logicalThreads.getOrNull(selectedThreadIndex) ?: return
        bindThreadNavigation(currentBinding, logicalThreads.size)
        currentBinding.commentMessagesContainer.removeAllViews()
        addLogicalMessageRow(currentBinding, thread, thread.root, isRoot = true)
        thread.replies.forEach { reply ->
            addLogicalMessageRow(currentBinding, thread, reply, isRoot = false)
        }
        currentBinding.commentThreadScroll.scrollTo(0, 0)
        sheetContentGeneration++
        sheetContainer?.requestLayout()
    }

    private fun bindThreadNavigation(currentBinding: PdfCommentSheetBinding, count: Int) {
        currentBinding.commentPositionText.text = activity.getString(
            R.string.comment_position,
            selectedThreadIndex + 1,
            count,
        )
        currentBinding.previousCommentButton.isEnabled = selectedThreadIndex > 0
        currentBinding.nextCommentButton.isEnabled = selectedThreadIndex < count - 1
        currentBinding.previousCommentButton.alpha =
            if (currentBinding.previousCommentButton.isEnabled) 1f else DISABLED_ALPHA
        currentBinding.nextCommentButton.alpha =
            if (currentBinding.nextCommentButton.isEnabled) 1f else DISABLED_ALPHA
    }

    private fun focusLogicalThread(thread: LogicalCommentThread) {
        val mode = sheetMode as? SheetMode.Logical ?: return
        sheetMode = mode.copy(selectedCommentId = thread.root.id)
        (activity as? MainActivity)?.selectLogicalCommentMarker(thread.root.id, thread.pageIndex)
    }

    private fun addLogicalMessageRow(
        currentBinding: PdfCommentSheetBinding,
        thread: LogicalCommentThread,
        comment: LogicalCommentMessage,
        isRoot: Boolean,
    ) {
        val row = PdfCommentMessageRowBinding.inflate(
            activity.layoutInflater,
            currentBinding.commentMessagesContainer,
            false,
        )
        if (!isRoot) {
            row.root.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(REPLY_INDENT_DP)
            }
        }
        val author = comment.author.ifBlank {
            activity.getString(R.string.unknown_comment_author)
        }
        row.commentAuthorText.text = author
        row.commentAuthorAvatar.text = authorInitials(author)
        row.commentAuthorAvatar.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(avatarColor(author))
        }
        row.commentDateText.text = logicalCommentDate(comment)
        row.commentBodyText.text = comment.contents.ifBlank { comment.payload.get("selected_text")?.asString.orEmpty() }
        row.commentOpenChatGpt.apply {
            val chatGptUrl = normalizeChatGptUrl(comment.chatGptUrl)
            visibility = if (chatGptUrl == null) View.GONE else View.VISIBLE
            isEnabled = chatGptUrl != null
            setOnClickListener {
                if (chatGptUrl != null && !activity.openChatGptLink(chatGptUrl)) {
                    AppSnackbar.make(binding.root, R.string.chatgpt_open_failed, Snackbar.LENGTH_LONG).show()
                }
            }
        }
        row.commentOverflowButton.setOnClickListener {
            showLogicalMessageMenu(row, thread, comment, isRoot)
        }
        currentBinding.commentMessagesContainer.addView(row.root)
    }

    private fun showLogicalMessageMenu(
        row: PdfCommentMessageRowBinding,
        thread: LogicalCommentThread,
        comment: LogicalCommentMessage,
        isRoot: Boolean,
    ) {
        PopupMenu(activity, row.commentOverflowButton).apply {
            menu.add(R.string.edit).setIcon(R.drawable.ic_edit)
            menu.add(R.string.delete).setIcon(R.drawable.ic_delete)
            setForceShowIcon(true)
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    activity.getString(R.string.edit) -> {
                        showLogicalEditor(comment, isRoot)
                        true
                    }
                    activity.getString(R.string.delete) -> {
                        deleteLogicalComment(thread, comment, isRoot)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showLogicalEditor(comment: LogicalCommentMessage, isRoot: Boolean) {
        if (!isRoot) {
            showLogicalReplyEditor(comment)
            return
        }
        pdfPassthroughEnabled = false
        CommentEditorDialog(activity).show(
            CommentEditorConfig(
                titleRes = R.string.edit_comment,
                initialValue = comment.editorValue,
                allowEmptyText = comment.payload.get("subtype")?.asString != "Text",
                neutralAction = CommentEditorNeutralAction(
                    labelRes = R.string.comment_move_on_pdf,
                    isVisible = comment.payload.get("subtype")?.asString == "Text",
                    onSave = { result ->
                        if (result.value != comment.editorValue) updateLogicalComment(comment.id, result.value)
                        binding.pdfView.getAllPdfComments().firstOrNull { it.name == comment.id }
                            ?.let(::startMovingComment)
                        true
                    },
                ),
            ),
            onSave = { result ->
                if (result.value != comment.editorValue) {
                    updateLogicalComment(comment.id, result.value)
                }
                true
            },
            onDismiss = { pdfPassthroughEnabled = true },
        )
    }

    private fun showLogicalReplyEditor(comment: LogicalCommentMessage) {
        pdfPassthroughEnabled = false
        val density = activity.resources.displayMetrics.density
        val editText = newCommentEditText(comment.contents)
        val container = FrameLayout(activity).apply {
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.edit_comment)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val contents = editText.text.toString().trim()
                if (contents.isNotBlank() && contents != comment.contents) {
                    updateLogicalComment(
                        comment.id,
                        comment.editorValue.copy(text = contents),
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .setOnDismissListener { pdfPassthroughEnabled = true }
    }

    private fun updateLogicalComment(commentId: String, value: CommentEditorValue) {
        val mode = sheetMode as? SheetMode.Logical ?: return
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                logicalCommentStore.update(commentId, value)
            }
            if (!success) {
                showCommentFailure()
                return@launch
            }
            (activity as? MainActivity)?.applyLogicalCommentOverlays()
            reloadLogicalThreads(mode.selectedCommentId)
        }
    }

    private fun deleteLogicalComment(
        thread: LogicalCommentThread,
        comment: LogicalCommentMessage,
        isRoot: Boolean,
    ) {
        if (sheetMode !is SheetMode.Logical) return
        val preferredId = when {
            !isRoot -> thread.root.id
            selectedThreadIndex < logicalThreads.lastIndex ->
                logicalThreads[selectedThreadIndex + 1].root.id
            selectedThreadIndex > 0 -> logicalThreads[selectedThreadIndex - 1].root.id
            else -> null
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                commentService.deleteComments(listOf(comment.id))
            }
            if (!result.success) {
                showCommentFailure()
                return@launch
            }
            offerDeletionUndo(result)
            (activity as? MainActivity)?.applyLogicalCommentOverlays()
            if (preferredId == null) {
                dismiss()
            } else {
                reloadLogicalThreads(preferredId, focusSelection = isRoot)
            }
        }
    }

    private fun newCommentEditText(contents: String): EditText = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 3
        gravity = Gravity.TOP or Gravity.START
        hint = activity.getString(R.string.comment_hint)
        setText(contents)
        setSelection(text.length)
    }

    private fun startMovingComment(staleComment: PDFView.PdfComment) {
        if (!canMutatePdfComments()) return
        val comment = resolveComment(staleComment)
        sheetBinding?.replyEditText?.clearFocus()
        WindowCompat.getInsetsController(activity.window, binding.root)
            .hide(WindowInsetsCompat.Type.ime())
        val started = binding.pdfView.startPdfCommentMove(comment) { rect ->
            val current = resolveComment(comment)
            if (!binding.pdfView.setPdfCommentBounds(current, rect)) {
                showCommentFailure()
                false
            } else {
                val metadata = getCommentMetadata(current.name)
                    ?: AnnotationController.CommentRootMetadata(
                        color = current.color,
                        rect = current.pdfBounds,
                    )
                onAnnotationEdit(
                    AnnotationEdit.SetPdfCommentMetadata(
                        page = current.pageIndex,
                        annotationIndex = current.annotationIndex,
                        subtype = current.subtype,
                        name = current.name,
                        priority = metadata.priority,
                        location = metadata.location,
                        modifiedDate = pdfDateNow(),
                        rect = rect,
                    ),
                )
                reloadLogicalThreads(current.name.substringBefore("#oe-fragment-"))
                true
            }
        }
        if (started) {
            sheetBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
            sheetContainer?.requestLayout()
            AppSnackbar.make(binding.root, R.string.comment_move_hint, Snackbar.LENGTH_LONG).show()
        } else {
            showCommentFailure()
        }
    }

    private fun submitReply() = submitLogicalReply()

    private fun submitLogicalReply() {
        val currentBinding = sheetBinding ?: return
        val thread = logicalThreads.getOrNull(selectedThreadIndex) ?: return
        val contents = currentBinding.replyEditText.text?.toString()?.trim().orEmpty()
        if (contents.isBlank()) return
        val mode = sheetMode as? SheetMode.Logical ?: return
        // A double tap, or the send icon plus the keyboard's Send, must not store the reply twice.
        if (replyInFlight) return
        replyInFlight = true
        scope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    logicalCommentStore.addReply(thread.root.id, contents, CommentAuthor.current(activity))
                }
            } finally {
                replyInFlight = false
            }
            if (!success) {
                AppSnackbar.make(binding.root, R.string.reply_failed, Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            currentBinding.replyEditText.text?.clear()
            reloadLogicalThreads(mode.selectedCommentId)
        }
    }

    private var replyInFlight = false

    private suspend fun removeComments(ids: List<String>): Boolean {
        val result = withContext(Dispatchers.IO) { commentService.deleteComments(ids) }
        if (result.success) offerDeletionUndo(result)
        (activity as? MainActivity)?.applyLogicalCommentOverlays()
        return result.success
    }

    private fun resolveComment(comment: PDFView.PdfComment): PDFView.PdfComment =
        binding.pdfView.findPdfComment(
            comment.pageIndex,
            comment.name,
            comment.annotationIndex,
            comment.subtype,
        ) ?: comment

    private fun logicalCommentDate(comment: LogicalCommentMessage): String {
        val rawDate = comment.updatedAt.ifBlank { comment.createdAt }
        if (rawDate.isBlank()) return activity.getString(R.string.comment_date_unavailable)
        return parseCommentTimestamp(rawDate)
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDateTime()
            ?.let { formatRelativeDate(activity, it) }
            ?: rawDate
    }

    private fun authorInitials(author: String): String {
        val parts = author.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val initials = when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts.first().take(2)
            else -> "${parts.first().first()}${parts.last().first()}"
        }
        return initials.uppercase(Locale.getDefault())
    }

    private fun avatarColor(author: String): Int =
        AVATAR_COLORS[Math.floorMod(author.hashCode(), AVATAR_COLORS.size)]

    private fun commentKey(comment: PDFView.PdfComment): String =
        comment.name.ifBlank {
            "${comment.pageIndex}:${comment.annotationIndex}:${comment.subtype}"
        }

    private fun showCommentFailure() {
        AppSnackbar.make(
            binding.root,
            R.string.comment_update_failed,
            Snackbar.LENGTH_SHORT,
        ).show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val INITIAL_SHEET_RATIO = 0.34f
        private const val SHEET_REVEAL_DURATION_MS = 180L
        private const val DISABLED_ALPHA = 0.38f
        private const val REPLY_INDENT_DP = 28
        private val AVATAR_COLORS = intArrayOf(
            Color.rgb(103, 80, 164),
            Color.rgb(0, 121, 107),
            Color.rgb(21, 101, 192),
            Color.rgb(173, 20, 87),
            Color.rgb(239, 108, 0),
        )
    }
}
