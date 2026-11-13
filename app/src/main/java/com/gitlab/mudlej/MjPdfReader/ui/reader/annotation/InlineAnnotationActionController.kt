// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAuthor
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.browserLinkIntent
import com.gitlab.mudlej.MjPdfReader.core.io.linkIntent
import com.gitlab.mudlej.MjPdfReader.core.io.pdfDateNow
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationFragment
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCreationMetadata
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorConfig
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorDialog
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorValue
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationSettings
import com.gitlab.mudlej.MjPdfReader.data.translation.TranslationUrlBuilder
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.copyToClipboard
import com.gitlab.mudlej.MjPdfReader.ui.reader.share.showShareQuoteDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

internal enum class SelectionSecondaryAction {
    TRANSLATE,
    OPEN_SOURCE,
}

internal fun selectionSecondaryAction(canOpenSource: Boolean): SelectionSecondaryAction =
    if (canOpenSource) SelectionSecondaryAction.OPEN_SOURCE else SelectionSecondaryAction.TRANSLATE

internal fun shouldPlaceSelectionActionsAtTop(
    selectionCenterY: Float?,
    pdfHeight: Int,
    bottomOverlayVisible: Boolean,
): Boolean = bottomOverlayVisible ||
    (selectionCenterY != null && selectionCenterY > pdfHeight * 0.65f)

/**
 * Lowest point of the selection actions card, in root coordinates: above the navigation bar the
 * reader is drawn under (Android 15+ edge-to-edge) and above the topmost bottom overlay.
 */
internal fun selectionActionsAreaBottom(
    rootHeight: Int,
    navigationBarHeight: Int,
    overlayTop: Int?,
    margin: Int,
): Int = minOf(rootHeight - navigationBarHeight, overlayTop ?: rootHeight) - margin

/**
 * Top of the selection actions card, in the coordinates of [areaTop] and [areaBottom].
 * It stays on its preferred edge unless it would cover the selection; then it takes the
 * other edge, or, when the selection reaches both, the edge that covers less of it.
 */
internal fun selectionActionsTop(
    selectionTop: Float?,
    selectionBottom: Float?,
    cardHeight: Int,
    areaTop: Int,
    areaBottom: Int,
    preferTop: Boolean,
): Int {
    val topEdge = areaTop
    val bottomEdge = (areaBottom - cardHeight).coerceAtLeast(areaTop)
    val edges = if (preferTop) listOf(topEdge, bottomEdge) else listOf(bottomEdge, topEdge)
    if (selectionTop == null || selectionBottom == null) return edges.first()
    fun covered(top: Int) = (minOf(top + cardHeight.toFloat(), selectionBottom) - maxOf(top.toFloat(), selectionTop)).coerceAtLeast(0f)
    return edges.minBy(::covered)
}

internal fun selectionSourceRequest(
    annotation: PDFView.HighlightAnnotation?,
    request: PDFView.HighlightRequest?,
): PDFView.HighlightRequest? {
    if (request != null && request.pageIndex >= 0 && request.pdfRects.isNotEmpty()) {
        return request
    }
    val bounds = annotation?.pdfBounds ?: return null
    return PDFView.HighlightRequest(
        annotation.pageIndex,
        listOf(bounds),
        annotation.quote,
    )
}

/**
 * Where a live selection starts and ends, as one-rectangle requests the source lookup understands:
 * its first fragment's first rectangle and its last fragment's last one. Null without geometry.
 */
internal fun selectionSourceEndpoints(
    request: PDFView.HighlightRequest?,
): Pair<PDFView.HighlightRequest, PDFView.HighlightRequest>? {
    val fragments = request?.fragments.orEmpty().filter { it.pageIndex >= 0 && it.pdfRects.isNotEmpty() }
    val first = fragments.firstOrNull() ?: return null
    val last = fragments.last()
    val start = PDFView.HighlightRequest(first.pageIndex, listOf(first.pdfRects.first()), first.selectedText)
    if (first === last && first.pdfRects.size == 1) return start to start
    return start to PDFView.HighlightRequest(last.pageIndex, listOf(last.pdfRects.last()), last.selectedText)
}

class InlineAnnotationActionController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    scope: CoroutineScope,
    private val clearActiveSearchResultHighlight: () -> Unit,
    private val onAnnotationEdit: (AnnotationEdit) -> Unit,
    private val updateSaveUiPosition: () -> Unit,
    private val isDetectExistingHighlightsEnabled: () -> Boolean,
    private val getHighlightColors: () -> List<Int>,
    /** The PDF's filename, including its extension. */
    private val getDocumentName: () -> String,
    private val getTranslationSettings: () -> TranslationSettings,
    private val onDefineWord: (String, () -> Unit) -> Boolean,
    private val onOpenSettings: () -> Unit,
    private val getCommentMetadata: (String) -> AnnotationController.CommentRootMetadata?,
    private val getCommentChatGptUrl: (String) -> String?,
    private val canOpenSource: (PDFView.HighlightRequest) -> Boolean,
    private val onEditSource: (PDFView.HighlightRequest) -> Unit,
    private val canAddReviewCard: () -> Boolean,
    private val onAddReviewCard: (Float, Float) -> Unit,
    private val onAddReviewFromSelection: (PDFView.HighlightRequest) -> Unit,
    private val canMutatePdfComments: () -> Boolean,
    private val persistCommentEdits: (List<AnnotationEdit>) -> Boolean,
    /** "Copy for agent" on the copy button: the selected text, its 1-based page and the live selection, if any. */
    private val onCopyForAgent: (String, Int, PDFView.HighlightRequest?) -> Unit,
    /** "Read aloud" on the selection toolbar: the selected text, as PDF text with its line breaks. */
    private val onReadAloud: (String) -> Unit,
    private val toggleReaderChrome: () -> Unit,
) {
    private val pdfCommentSheetController = PdfCommentSheetController(
        activity,
        binding,
        scope,
        onAnnotationEdit,
        getCommentMetadata,
        getCommentChatGptUrl,
        canMutatePdfComments,
        getDocumentName,
    )
    /** Shows a link menu when a long press at a view point hits a link it handles (PDF link overrides). */
    var onLinkLongPress: ((Float, Float) -> Boolean)? = null
    /**
     * Review card feedback: whether a selection lies in the revealed answer of the card under
     * review (then Like, Dislike and Comment… lead the selection actions), what they do, and what
     * a tap on a drawn feedback highlight opens.
     */
    var reviewFeedback: ReviewFeedbackActions? = null
    /**
     * Document feedback (protocol/document-feedback.md §5): Like, Dislike and Comment… on any
     * selection of a Library PDF when card feedback does not take it, and the editor of a drawn
     * document feedback highlight. One selection never creates both kinds.
     */
    var documentFeedback: ReviewFeedbackActions? = null
    private var lastFeedbackTapDownTime = Long.MIN_VALUE
    private var activeHighlightAnnotation: PDFView.HighlightAnnotation? = null
    private var selectionBoundsInPdf: RectF? = null
    /**
     * A selection that takes feedback starts with one row (the feedback, Copy and ⋯); ⋯ shows the
     * colours and every other action until the selection goes.
     */
    private var selectionMenuFull = false
    private var selectionActionsPreferTop = false
    private var longPressPopup: PopupMenu? = null
    private var longPressAnchor: View? = null
    private var copyForAgentPopup: PopupMenu? = null
    private var lastOpenedCommentTapDownTime = Long.MIN_VALUE
    private val whitespaceRegex = Regex("\\s+")

    fun configure(onSaveClicked: () -> Unit) {
        binding.textSelectionCopyButton.setOnClickListener {
            if (copySelectedText()) {
                dismissCard()
            }
        }
        // A tap copies the raw text; a long press offers the text a coding agent needs.
        binding.textSelectionCopyButton.setOnLongClickListener { button ->
            showCopyForAgentMenu(button)
            true
        }
        ViewCompat.replaceAccessibilityAction(
            binding.textSelectionCopyButton,
            AccessibilityActionCompat.ACTION_LONG_CLICK,
            activity.getString(R.string.copy_document_for_agent),
            null,
        )
        binding.textSelectionFeedbackLikeButton.setOnClickListener { giveFeedback(ReviewFeedbackActions.Action.LIKE) }
        binding.textSelectionFeedbackDislikeButton.setOnClickListener { giveFeedback(ReviewFeedbackActions.Action.DISLIKE) }
        binding.textSelectionFeedbackQuestionButton.setOnClickListener { giveFeedback(ReviewFeedbackActions.Action.QUESTION) }
        binding.textSelectionFeedbackCommentButton.setOnClickListener { giveFeedback(ReviewFeedbackActions.Action.COMMENT) }
        binding.textSelectionSavePassageButton.setOnClickListener { saveFeedbackPassage() }
        binding.textSelectionMoreButton.setOnClickListener {
            selectionMenuFull = true
            showSelectionActions(selectionBoundsInPdf?.let(::RectF))
        }
        binding.textSelectionCancelPassageButton.setOnClickListener { binding.pdfView.clearTextSelection() }
        binding.textSelectionAddReviewButton.setOnClickListener {
            val request = selectionSourceRequest(activeHighlightAnnotation, binding.pdfView.getHighlightRequest())
            if (canAddReviewCard() && request != null && request.selectedText.isNotBlank()) {
                onAddReviewFromSelection(request)
            }
        }
        binding.textSelectionShareButton.setOnClickListener { shareSelectedQuote() }
        binding.textSelectionReadAloudButton.setOnClickListener { readSelectedTextAloud() }
        binding.textSelectionNoteButton.setOnClickListener { handleNoteClicked() }
        binding.textSelectionStrikethroughButton.setOnClickListener {
            val request = binding.pdfView.getHighlightRequest()
            if (request != null && request.selectedText.isNotBlank()) {
                addMarkupComment(request, CommentEditorValue(text = "", color = 0xFFD32F2F.toInt()), PDFView.PdfComment.SUBTYPE_STRIKEOUT)
            }
        }
        configureSecondaryAction(null, null)
        rebuildHighlightSwatches()
        binding.textSelectionDeleteHighlightButton.setOnClickListener { deleteActiveHighlightAnnotation() }
        binding.saveAnnotationsFab.setOnClickListener { onSaveClicked() }
    }

    private val swatchIds = mutableListOf<Int>()

    fun rebuildHighlightSwatches() {
        val container = binding.textSelectionActionContent
        val density = container.resources.displayMetrics.density
        swatchIds.forEach { id -> container.findViewById<View>(id)?.let(container::removeView) }
        swatchIds.clear()
        getHighlightColors().forEach { color ->
            val swatchButton = FrameLayout(container.context).apply {
                id = View.generateViewId()
                layoutParams = ConstraintLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                val backgroundValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)
                setBackgroundResource(backgroundValue.resourceId)
                isClickable = true
                isFocusable = true
                HighlightPalette.fromColor(color)?.let { contentDescription = context.getString(it.labelRes) }
                setOnClickListener { applyHighlightColor(color) }
            }
            val swatchCard = MaterialCardView(container.context).apply {
                layoutParams = FrameLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt(), Gravity.CENTER)
                radius = 18 * density
                cardElevation = 0f
                setCardBackgroundColor(color)
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
                strokeWidth = density.toInt().coerceAtLeast(1)
            }
            swatchButton.addView(swatchCard)
            container.addView(swatchButton)
            swatchIds.add(swatchButton.id)
        }
        binding.textSelectionSwatchFlow.referencedIds = swatchIds.toIntArray()
    }

    fun handleImmediatePdfTap(event: MotionEvent): Boolean {
        // A liked or disliked passage of the revealed answer opens its editor (it lies above PDF comments).
        val feedbackId = reviewFeedback?.let { binding.pdfView.findReviewFeedbackAt(event.x, event.y) }
        if (feedbackId != null) {
            if (lastFeedbackTapDownTime != event.downTime) {
                lastFeedbackTapDownTime = event.downTime
                clearActiveSearchResultHighlight()
                binding.pdfView.clearTextSelection()
                hideActions()
                reviewFeedback?.open(feedbackId)
            }
            return true
        }
        // A liked or disliked passage of the document read normally (not drawn during a review).
        val documentFeedbackId = documentFeedback?.let { binding.pdfView.findDocumentFeedbackAt(event.x, event.y) }
        if (documentFeedbackId != null) {
            if (lastFeedbackTapDownTime != event.downTime) {
                lastFeedbackTapDownTime = event.downTime
                clearActiveSearchResultHighlight()
                binding.pdfView.clearTextSelection()
                hideActions()
                documentFeedback?.open(documentFeedbackId)
            }
            return true
        }
        val pdfComment = binding.pdfView.findPdfCommentAt(event.x, event.y)
        if (pdfComment != null) {
            clearActiveSearchResultHighlight()
            binding.pdfView.clearTextSelection()
            hideActions()
            if (lastOpenedCommentTapDownTime != event.downTime) {
                lastOpenedCommentTapDownTime = event.downTime
                pdfCommentSheetController.show(pdfComment)
            }
            return true
        }
        val annotation = binding.pdfView.findHighlightAnnotationAt(event.x, event.y) ?: return false
        clearActiveSearchResultHighlight()
        binding.pdfView.clearTextSelection()
        if (annotation.quote.isBlank() && annotation.note.isNotBlank()) {
            if (lastOpenedCommentTapDownTime != event.downTime) {
                lastOpenedCommentTapDownTime = event.downTime
                hideActions()
                showNoteViewDialog(annotation)
            }
            return true
        }
        showHighlightAnnotationActions(annotation)
        return true
    }

    fun openPdfComment(commentId: String, tapDownTime: Long? = null): Boolean {
        val comment = binding.pdfView.getAllPdfComments()
            .firstOrNull { it.name == commentId }
            ?: return false
        clearActiveSearchResultHighlight()
        binding.pdfView.clearTextSelection()
        hideActions()
        tapDownTime?.let { lastOpenedCommentTapDownTime = it }
        pdfCommentSheetController.show(comment)
        return true
    }

    fun openLogicalComment(
        commentId: String,
        pdfFilename: String,
        pdfSha256: String,
    ) {
        clearActiveSearchResultHighlight()
        binding.pdfView.clearTextSelection()
        hideActions()
        pdfCommentSheetController.showLogical(commentId, pdfFilename, pdfSha256)
    }

    fun handlePdfLongPress(event: MotionEvent) {
        clearActiveSearchResultHighlight()
        val pdfComment = binding.pdfView.findPdfCommentAt(event.x, event.y)
        if (pdfComment != null) {
            binding.pdfView.clearTextSelection()
            hideActions()
            lastOpenedCommentTapDownTime = event.downTime
            pdfCommentSheetController.show(pdfComment)
            return
        }
        if (onLinkLongPress?.invoke(event.x, event.y) == true) {
            // The press started a text selection first; the link menu replaces it.
            binding.pdfView.clearTextSelection()
            dismissLongPressMenu()
            hideActions()
            return
        }
        if (binding.pdfView.hasTextSelection()) {
            dismissLongPressMenu()
            return
        }
        val annotation = binding.pdfView.findHighlightAnnotationAt(event.x, event.y)
        val request = if (annotation == null) {
            val selectionRequest = binding.pdfView.getHighlightRequest()
            val markerRequest = binding.pdfView.createCommentRequestAt(
                event.x,
                event.y,
                COMMENT_MARKER_SIZE_DP,
            )
            when {
                selectionRequest == null -> markerRequest
                markerRequest == null -> selectionRequest
                selectionRequest.pageIndex == markerRequest.pageIndex -> selectionRequest
                else -> markerRequest
            }
        } else {
            null
        }
        if (annotation == null && request == null && !canAddReviewCard()) {
            return
        }
        showLongPressMenu(event, annotation, request)
    }

    private fun showLongPressMenu(
        event: MotionEvent,
        annotation: PDFView.HighlightAnnotation?,
        request: PDFView.HighlightRequest?,
    ) {
        dismissLongPressMenu()
        val root = binding.root
        val pdfLocation = IntArray(2)
        val rootLocation = IntArray(2)
        binding.pdfView.getLocationOnScreen(pdfLocation)
        root.getLocationOnScreen(rootLocation)
        val anchorSize = activity.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        val anchorX = (pdfLocation[0] - rootLocation[0] + event.x.toInt())
            .coerceIn(0, (root.width - anchorSize).coerceAtLeast(0))
        val anchorY = (pdfLocation[1] - rootLocation[1] + event.y.toInt())
            .coerceIn(0, (root.height - anchorSize).coerceAtLeast(0))
        val anchor = View(activity).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(anchorSize, anchorSize).apply {
                startToStart = ConstraintSet.PARENT_ID
                topToTop = ConstraintSet.PARENT_ID
                marginStart = anchorX
                topMargin = anchorY
            }
        }
        root.addView(anchor)

        val popup = PopupMenu(activity, anchor).apply {
            if (annotation != null || request != null) {
                menu.add(
                    0,
                    MENU_COMMENT,
                    0,
                    if (annotation?.note.isNullOrBlank()) {
                        R.string.add_comment
                    } else {
                        R.string.edit_comment
                    }
                ).setIcon(if (annotation?.note.isNullOrBlank()) R.drawable.ic_comment_add else R.drawable.ic_comment)
                selectionSourceRequest(annotation, request)
                    ?.takeIf(canOpenSource)
                    ?.let {
                        menu.add(0, MENU_EDIT_SOURCE, 1, R.string.edit_tex_source)
                            .setIcon(R.drawable.ic_edit)
                    }
            }
            if (canAddReviewCard()) {
                menu.add(0, MENU_ADD_REVIEW_CARD, 2, R.string.manual_review_add_card)
                    .setIcon(R.drawable.ic_comment_add)
            }
            setForceShowIcon(true)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_COMMENT -> showCommentEditorDialog(annotation, request)
                    MENU_EDIT_SOURCE -> selectionSourceRequest(annotation, request)?.let(onEditSource)
                    MENU_ADD_REVIEW_CARD -> onAddReviewCard(event.x, event.y)
                }
                true
            }
            setOnDismissListener {
                if (longPressPopup === this) {
                    longPressPopup = null
                    longPressAnchor = null
                }
                (anchor.parent as? ViewGroup)?.removeView(anchor)
            }
        }
        longPressAnchor = anchor
        longPressPopup = popup
        anchor.post {
            if (longPressPopup === popup && !activity.isFinishing && !activity.isDestroyed) {
                popup.show()
            }
        }
    }

    private fun dismissLongPressMenu() {
        val popup = longPressPopup
        val anchor = longPressAnchor
        longPressPopup = null
        longPressAnchor = null
        popup?.setOnDismissListener(null)
        popup?.dismiss()
        (anchor?.parent as? ViewGroup)?.removeView(anchor)
    }

    fun handleEmptyTap() {
        if (activeHighlightAnnotation != null) {
            hideActions()
        }
        toggleReaderChrome()
    }

    fun showSelectionActions(viewBounds: RectF?) {
        // Changing a feedback highlight's passage: the selection is the new passage, whatever lies under it.
        if (changingPassage() != null) {
            showPassageChangeActions(viewBounds)
            return
        }
        showPassageChangeButtons(false)
        val matchingAnnotation = findAnnotationMatchingSelection()
        if (matchingAnnotation != null) {
            binding.pdfView.clearTextSelection()
            showHighlightAnnotationActions(matchingAnnotation)
            return
        }

        activeHighlightAnnotation = null
        binding.pdfView.clearSelectedHighlightAnnotation()
        val feedbackActions = binding.pdfView.getHighlightRequest()?.let(::feedbackActionsFor)
        showReviewFeedbackActions(feedbackActions != null, feedbackActions?.offersQuestion == true)
        binding.textSelectionStrikethroughButton.visibility = View.VISIBLE
        binding.textSelectionCopyButton.visibility = View.VISIBLE
        binding.textSelectionShareButton.visibility = View.VISIBLE
        binding.textSelectionAddReviewButton.visibility = if (canAddReviewCard()) View.VISIBLE else View.GONE
        binding.textSelectionNoteButton.visibility = View.VISIBLE
        binding.textSelectionTranslateButton.visibility = View.VISIBLE
        binding.textSelectionReadAloudButton.visibility = View.VISIBLE
        binding.textSelectionDeleteHighlightButton.visibility = View.GONE
        val compact = feedbackActions != null && !selectionMenuFull
        if (compact) {
            listOf(
                binding.textSelectionStrikethroughButton, binding.textSelectionShareButton, binding.textSelectionAddReviewButton,
                binding.textSelectionNoteButton, binding.textSelectionTranslateButton, binding.textSelectionReadAloudButton,
            ).forEach { it.visibility = View.GONE }
            swatchIds.forEach { id -> binding.textSelectionActionContent.findViewById<View>(id)?.visibility = View.GONE }
        }
        binding.textSelectionMoreButton.visibility = if (compact) View.VISIBLE else View.GONE
        configureSecondaryAction(null, binding.pdfView.getHighlightRequest())
        applyNoteButtonState(hasNote = false, isComment = false)
        showCard(viewBounds)
    }

    private fun showReviewFeedbackActions(visible: Boolean, question: Boolean = false) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.textSelectionFeedbackLikeButton.visibility = visibility
        binding.textSelectionFeedbackDislikeButton.visibility = visibility
        binding.textSelectionFeedbackQuestionButton.visibility = if (visible && question) View.VISIBLE else View.GONE
        binding.textSelectionFeedbackCommentButton.visibility = visibility
    }

    /** Only Save passage and Cancel while a feedback highlight's passage is being changed. */
    private fun showPassageChangeActions(viewBounds: RectF?) {
        activeHighlightAnnotation = null
        binding.pdfView.clearSelectedHighlightAnnotation()
        showReviewFeedbackActions(false)
        listOf(
            binding.textSelectionCopyButton, binding.textSelectionStrikethroughButton, binding.textSelectionShareButton,
            binding.textSelectionAddReviewButton, binding.textSelectionNoteButton, binding.textSelectionTranslateButton,
            binding.textSelectionReadAloudButton, binding.textSelectionDeleteHighlightButton,
        ).forEach { it.visibility = View.GONE }
        binding.textSelectionMoreButton.visibility = View.GONE
        showPassageChangeButtons(true)
        showCard(viewBounds)
    }

    private fun showPassageChangeButtons(visible: Boolean) {
        binding.textSelectionSavePassageButton.visibility = if (visible) View.VISIBLE else View.GONE
        binding.textSelectionCancelPassageButton.visibility = if (visible) View.VISIBLE else View.GONE
        // The highlight colours have no meaning for a feedback passage.
        swatchIds.forEach { id ->
            binding.textSelectionActionContent.findViewById<View>(id)?.visibility = if (visible) View.GONE else View.VISIBLE
        }
    }

    /** The card or document feedback whose highlight is having its passage changed, if any. */
    private fun changingPassage(): ReviewFeedbackActions? =
        listOfNotNull(reviewFeedback, documentFeedback).firstOrNull { it.isChangingPassage() }

    private fun saveFeedbackPassage() {
        val actions = changingPassage() ?: return
        val request = binding.pdfView.getHighlightRequest() ?: return
        if (!actions.isChangingPassage() || request.selectedText.isBlank()) return
        // Saved (the selection goes) or refused (the selection stays for another try).
        actions.savePassage(request)
        if (!actions.isChangingPassage()) binding.pdfView.clearTextSelection()
    }

    /** The PDF text selection went away: a passage being changed keeps its old place. */
    fun handleTextSelectionCleared() {
        changingPassage()?.cancelPassageChange()
        hideActions()
    }

    /**
     * Who takes Like / Dislike / Comment… on [request]: card feedback when the selection lies in
     * the revealed answer of the card under review, else document feedback; null when neither.
     */
    private fun feedbackActionsFor(request: PDFView.HighlightRequest): ReviewFeedbackActions? {
        if (request.selectedText.isBlank()) return null
        reviewFeedback?.takeIf { it.qualifies(request) }?.let { return it }
        return documentFeedback?.takeIf { it.qualifies(request) }
    }

    private fun giveFeedback(action: ReviewFeedbackActions.Action) {
        val request = binding.pdfView.getHighlightRequest() ?: return
        val actions = feedbackActionsFor(request) ?: return
        actions.give(request, action)
        // Like and Dislike are saved at once; Comment… saves from its editor. Either way the selection goes.
        binding.pdfView.clearTextSelection()
    }

    private fun findAnnotationMatchingSelection(): PDFView.HighlightAnnotation? {
        if (!isDetectExistingHighlightsEnabled()) {
            return null
        }
        val request = binding.pdfView.getHighlightRequest() ?: return null
        return binding.pdfView.findHighlightAnnotationMatching(request)
    }

    fun hideActions() {
        dismissLongPressMenu()
        copyForAgentPopup?.dismiss()
        selectionMenuFull = false
        activeHighlightAnnotation = null
        binding.pdfView.clearSelectedHighlightAnnotation()
        binding.textSelectionActionCard.visibility = View.GONE
        updateSaveUiPosition()
    }

    fun dismissPdfCommentSheet(): Boolean = pdfCommentSheetController.dismiss()

    fun isPdfCommentSheetShowing(): Boolean = pdfCommentSheetController.isShowing()

    fun handlePdfCommentSheetBack(): Boolean = pdfCommentSheetController.handleBack()

    fun isCardAtBottom(): Boolean {
        val card = binding.textSelectionActionCard
        if (card.visibility != View.VISIBLE) {
            return false
        }
        return card.top + card.translationY + card.height / 2f >= binding.root.height / 2f
    }

    private fun showHighlightAnnotationActions(annotation: PDFView.HighlightAnnotation) {
        activeHighlightAnnotation = annotation
        binding.pdfView.setSelectedHighlightAnnotation(annotation)
        showReviewFeedbackActions(false)
        showPassageChangeButtons(false)
        binding.textSelectionMoreButton.visibility = View.GONE
        val textActionVisibility = if (annotation.quote.isBlank()) View.GONE else View.VISIBLE
        binding.textSelectionStrikethroughButton.visibility = View.GONE
        binding.textSelectionCopyButton.visibility = textActionVisibility
        binding.textSelectionShareButton.visibility = textActionVisibility
        binding.textSelectionAddReviewButton.visibility = if (canAddReviewCard()) textActionVisibility else View.GONE
        binding.textSelectionTranslateButton.visibility = textActionVisibility
        binding.textSelectionReadAloudButton.visibility = textActionVisibility
        binding.textSelectionNoteButton.visibility = View.VISIBLE
        binding.textSelectionDeleteHighlightButton.visibility = View.VISIBLE
        configureSecondaryAction(annotation, null)
        applyNoteButtonState(
            hasNote = annotation.note.isNotBlank(),
            isComment = annotation.quote.isBlank(),
        )
        showCard(annotation.viewBounds)
    }

    private fun configureSecondaryAction(
        annotation: PDFView.HighlightAnnotation?,
        request: PDFView.HighlightRequest?,
    ) {
        val sourceRequest = selectionSourceRequest(annotation, request)
        when (selectionSecondaryAction(sourceRequest?.let(canOpenSource) == true)) {
            SelectionSecondaryAction.OPEN_SOURCE -> {
                binding.textSelectionTranslateButton.setIconResource(R.drawable.ic_edit)
                binding.textSelectionTranslateButton.contentDescription =
                    activity.getString(R.string.open_tex_source)
                binding.textSelectionTranslateButton.setOnClickListener {
                    sourceRequest?.let(onEditSource)
                    dismissCard()
                }
            }
            SelectionSecondaryAction.TRANSLATE -> {
                binding.textSelectionTranslateButton.setIconResource(R.drawable.ic_translate)
                binding.textSelectionTranslateButton.contentDescription =
                    activity.getString(R.string.translate)
                binding.textSelectionTranslateButton.setOnClickListener {
                    if (translateSelectedText()) {
                        dismissCard()
                    }
                }
            }
        }
    }

    private fun showCard(viewBounds: RectF?) {
        val card = binding.textSelectionActionCard
        val params = card.layoutParams as ConstraintLayout.LayoutParams
        if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val bottomOverlayVisible = binding.reviewCard.visibility == View.VISIBLE ||
            binding.manualReviewEditorCard.visibility == View.VISIBLE
        val placeAtTop = shouldPlaceSelectionActionsAtTop(
            selectionCenterY = viewBounds?.centerY(),
            pdfHeight = binding.pdfView.height,
            bottomOverlayVisible = bottomOverlayVisible,
        )
        // Laid out at the top; translationY then moves it clear of the selection.
        params.verticalBias = 0f
        card.layoutParams = params
        if (card.visibility != View.VISIBLE) {
            card.translationY = 0f
            card.alpha = 0f
        }
        card.visibility = View.VISIBLE
        card.bringToFront()
        selectionBoundsInPdf = viewBounds?.let(::RectF)
        selectionActionsPreferTop = placeAtTop
        refreshCardRendering()
    }

    private fun positionCard() {
        val card = binding.textSelectionActionCard
        if (card.visibility != View.VISIBLE) return
        card.alpha = 1f
        if (card.height == 0) return
        val density = activity.resources.displayMetrics.density
        val rootLocation = IntArray(2).also(binding.root::getLocationInWindow)
        fun topInRoot(view: View) = IntArray(2).also(view::getLocationInWindow)[1] - rootLocation[1]
        val margin = (16 * density).toInt()
        val overlayTop = listOf(binding.reviewCard, binding.manualReviewEditorCard, binding.studyPageBar.root)
            .filter { it.visibility == View.VISIBLE && it.height > 0 }
            .minOfOrNull(::topInRoot)
        val navigationBarHeight = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val pdfTop = topInRoot(binding.pdfView)
        val bounds = selectionBoundsInPdf
        val top = selectionActionsTop(
            selectionTop = bounds?.let { pdfTop + it.top },
            // The selection handles hang below the selected text.
            selectionBottom = bounds?.let { pdfTop + it.bottom + SELECTION_HANDLE_DP * density },
            cardHeight = card.height,
            areaTop = card.top,
            areaBottom = selectionActionsAreaBottom(binding.root.height, navigationBarHeight, overlayTop, margin),
            preferTop = selectionActionsPreferTop,
        )
        card.translationY = (top - card.top).toFloat()
    }

    private fun dismissCard() {
        if (activeHighlightAnnotation != null) {
            hideActions()
        } else {
            binding.pdfView.clearTextSelection()
        }
    }

    private fun applyHighlightColor(color: Int) {
        val annotation = activeHighlightAnnotation
        if (annotation != null) {
            updateActiveHighlightAnnotationColor(annotation, color)
        } else {
            addInlineHighlight(color)
        }
    }

    private fun addInlineHighlight(color: Int) {
        val request = binding.pdfView.getHighlightRequest() ?: return
        addMarkupComment(request, CommentEditorValue(text = "", color = color))
    }

    private fun handleNoteClicked() {
        val annotation = activeHighlightAnnotation
        if (annotation != null && annotation.note.isNotBlank()) {
            showNoteViewDialog(annotation)
        } else {
            showNoteEditorDialog(annotation)
        }
    }

    private fun applyNoteButtonState(hasNote: Boolean, isComment: Boolean) {
        binding.textSelectionNoteButton.setIconResource(if (hasNote) R.drawable.ic_comment else R.drawable.ic_comment_add)
        binding.textSelectionNoteButton.contentDescription = activity.getString(
            when {
                isComment && hasNote -> R.string.view_comment
                isComment -> R.string.add_comment
                hasNote -> R.string.view_comment
                else -> R.string.add_comment
            }
        )
    }

    private fun showNoteEditorDialog(annotation: PDFView.HighlightAnnotation?) {
        showAnnotationEditorDialog(annotation, if (annotation == null) binding.pdfView.getHighlightRequest() else null)
    }

    private fun showCommentEditorDialog(annotation: PDFView.HighlightAnnotation?, request: PDFView.HighlightRequest?) {
        showAnnotationEditorDialog(annotation, request)
    }

    private fun showAnnotationEditorDialog(annotation: PDFView.HighlightAnnotation?, request: PDFView.HighlightRequest?) {
        val metadata = annotation?.let { getCommentMetadata(it.groupKey) }
        val initialColor = metadata?.color ?: if (request?.selectedText?.isNotBlank() == true || annotation?.quote?.isNotBlank() == true) {
            getHighlightColors().firstOrNull() ?: HighlightPalette.YELLOW.colorValue
        } else CommentCreationMetadata.DEFAULT_POPUP_COLOR
        binding.textSelectionActionCard.visibility = View.GONE
        CommentEditorDialog(activity).show(
            CommentEditorConfig(
                titleRes = R.string.comment,
                initialValue = CommentEditorValue(
                    text = annotation?.note.orEmpty(), color = initialColor,
                    priority = metadata?.priority ?: CommentPriority.NORMAL,
                    location = metadata?.location, needsAction = metadata?.needsAction ?: false,
                ),
                allowEmptyText = annotation != null || request?.selectedText?.isNotBlank() == true,
            ),
            onSave = { result ->
                val value = result.value
                if (annotation != null) {
                    if (value.text.isBlank() && annotation.quote.isBlank()) {
                        removeHighlightAnnotation(annotation)
                        true
                    } else {
                        val date = pdfDateNow()
                        val saved = persistCommentEdits(listOf(
                            AnnotationEdit.SetPdfCommentContents(annotation.pageIndex, annotation.annotationIndex,
                                PDFView.PdfComment.SUBTYPE_HIGHLIGHT, annotation.groupKey, value.text, date),
                            AnnotationEdit.SetPdfCommentMetadata(annotation.pageIndex, annotation.annotationIndex,
                                PDFView.PdfComment.SUBTYPE_HIGHLIGHT, annotation.groupKey, value.priority,
                                value.location, date, color = value.color, needsAction = value.needsAction),
                        ))
                        if (saved) hideActions()
                        else AppSnackbar.make(binding.root, R.string.comment_failed, Snackbar.LENGTH_SHORT).show()
                        saved
                    }
                } else if (request != null) {
                    // Keep the dialog, and the typed text, open when the comment could not be added.
                    addComment(request, value)
                } else false
            },
            onDismiss = { refreshCardRendering() },
        )
    }

    private fun showNoteViewDialog(annotation: PDFView.HighlightAnnotation) {
        val isComment = annotation.quote.isBlank()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.comment)
            .setMessage(annotation.note)
            .setPositiveButton(R.string.edit) { _, _ ->
                if (isComment) {
                    showCommentEditorDialog(annotation, null)
                } else {
                    showNoteEditorDialog(annotation)
                }
            }
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.delete) { _, _ ->
                if (isComment) {
                    removeHighlightAnnotation(annotation)
                } else {
                    applyNoteToAnnotation(annotation, "")
                }
            }
            .show()
    }

    private fun applyNoteToAnnotation(annotation: PDFView.HighlightAnnotation, note: String) {
        if (note.isBlank() && annotation.note.isBlank()) {
            return
        }
        if (note.isBlank() && annotation.quote.isBlank()) {
            removeHighlightAnnotation(annotation)
            return
        }
        val date = pdfDateNow()
        val updated = binding.pdfView.setHighlightAnnotationNote(annotation, note, date)
        if (!updated) {
            AppSnackbar.make(binding.root, R.string.note_failed, Snackbar.LENGTH_SHORT).show()
            return
        }
        hideActions()
        onAnnotationEdit(AnnotationEdit.SetPdfCommentContents(annotation.pageIndex, 0, PDFView.PdfComment.SUBTYPE_HIGHLIGHT, annotation.groupKey, note, date))
    }

    private fun addComment(request: PDFView.HighlightRequest, value: CommentEditorValue): Boolean =
        if (request.selectedText.isNotBlank()) {
            addMarkupComment(request, value)
        } else {
            addPointComment(request, value)
        }

    private fun addPointComment(request: PDFView.HighlightRequest, value: CommentEditorValue): Boolean {
        if (!canMutatePdfComments()) return false
        val name = CommentCreationMetadata.newThoughtsId()
        val createdDate = pdfDateNow()
        val rect = request.pdfRects.firstOrNull()
        if (rect == null || !binding.pdfView.addPdfComment(
                request.pageIndex,
                rect,
                value.color,
                name,
                value.text,
                commentAuthor,
                createdDate,
                createdDate,
            )
        ) {
            AppSnackbar.make(binding.root, R.string.comment_failed, Snackbar.LENGTH_SHORT).show()
            return false
        }

        onAnnotationEdit(
            AnnotationEdit.AddPdfComment(
                page = request.pageIndex,
                name = name,
                rect = rect,
                contents = value.text,
                author = commentAuthor,
                createdDate = createdDate,
                modifiedDate = createdDate,
                priority = value.priority,
                location = value.location,
                needsAction = value.needsAction,
                color = value.color,
            )
        )
        binding.pdfView.clearTextSelection()
        refreshCardRendering()
        return true
    }

    private fun addMarkupComment(request: PDFView.HighlightRequest, value: CommentEditorValue, subtype: Int = PDFView.PdfComment.SUBTYPE_HIGHLIGHT): Boolean {
        if (!canMutatePdfComments()) return false
        val name = CommentCreationMetadata.newThoughtsId()
        val createdDate = pdfDateNow()
        if (!binding.pdfView.addPdfMarkupComment(
                request,
                value.color,
                name,
                value.text,
                commentAuthor,
                createdDate,
                createdDate,
                subtype,
            )
        ) {
            AppSnackbar.make(binding.root, R.string.comment_failed, Snackbar.LENGTH_SHORT).show()
            return false
        }

        onAnnotationEdit(
            AnnotationEdit.AddPdfMarkupComment(
                subtype = subtype,
                page = request.pageIndex,
                name = name,
                rects = request.pdfRects,
                color = value.color,
                contents = value.text,
                author = commentAuthor,
                createdDate = createdDate,
                modifiedDate = createdDate,
                selectedText = request.selectedText,
                fragments = request.fragments.map {
                    AnnotationFragment(it.pageIndex, it.pdfRects, it.selectedText)
                },
                priority = value.priority,
                location = value.location,
                needsAction = value.needsAction,
            )
        )
        binding.pdfView.clearTextSelection()
        refreshCardRendering()
        return true
    }

    private fun updateActiveHighlightAnnotationColor(annotation: PDFView.HighlightAnnotation, color: Int) {
        val updated = binding.pdfView.setHighlightAnnotationColor(annotation, color)
        if (!updated) {
            AppSnackbar.make(binding.root, R.string.highlight_update_failed, Snackbar.LENGTH_SHORT).show()
            return
        }

        hideActions()
        val metadata = getCommentMetadata(annotation.groupKey)
        onAnnotationEdit(AnnotationEdit.SetPdfCommentMetadata(annotation.pageIndex, 0, PDFView.PdfComment.SUBTYPE_HIGHLIGHT,
            annotation.groupKey, metadata?.priority ?: CommentPriority.NORMAL, metadata?.location, pdfDateNow(), color = color))
    }

    private fun deleteActiveHighlightAnnotation() {
        val annotation = activeHighlightAnnotation ?: return
        removeHighlightAnnotation(annotation)
    }

    private fun removeHighlightAnnotation(annotation: PDFView.HighlightAnnotation) {
        pdfCommentSheetController.deleteComment(annotation.groupKey)
        hideActions()
    }

    private fun copySelectedText(): Boolean {
        val text = selectedText()
        if (text.isBlank()) {
            return false
        }
        copyToClipboard(activity, activity.getString(R.string.selected_text), text)
        return true
    }

    private fun showCopyForAgentMenu(anchor: View) {
        if (selectedText().isBlank()) return
        copyForAgentPopup?.dismiss()
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, COPY_FOR_AGENT_ITEM, 0, R.string.copy_document_for_agent)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId != COPY_FOR_AGENT_ITEM) return@setOnMenuItemClickListener false
            copySelectionForAgent()
            true
        }
        popup.setOnDismissListener { if (copyForAgentPopup === it) copyForAgentPopup = null }
        copyForAgentPopup = popup
        popup.show()
    }

    private fun copySelectionForAgent() {
        val text = selectedText()
        if (text.isBlank()) return
        val annotation = activeHighlightAnnotation
        // A saved highlight keeps only its bounds, too coarse to name its source lines.
        val live = if (annotation == null) binding.pdfView.getHighlightRequest() else null
        val pageIndex = annotation?.pageIndex ?: live?.pageIndex?.takeIf { it >= 0 } ?: binding.pdfView.currentPage
        onCopyForAgent(text, pageIndex + 1, live)
        dismissCard()
    }

    private fun selectedText(): String {
        return activeHighlightAnnotation?.quote ?: binding.pdfView.getSelectedText()
    }

    private fun readSelectedTextAloud() {
        val text = selectedText()
        if (text.isBlank()) return
        onReadAloud(text)
        dismissCard()
    }

    private fun shareSelectedQuote() {
        val text = selectedText()
        if (text.isBlank()) {
            return
        }
        val meta = binding.pdfView.documentMeta
        val bookName = meta?.title?.takeIf { it.isNotBlank() } ?: getDocumentName().substringBeforeLast('.')
        showShareQuoteDialog(activity, text, bookName, meta?.author.orEmpty())
    }

    private fun translateSelectedText(): Boolean {
        val text = selectedText().replace(whitespaceRegex, " ").trim()
        if (text.isBlank()) {
            return false
        }
        val settings = getTranslationSettings()
        val word = dictionaryCandidate(text)
        if (word != null && onDefineWord(word) { launchTranslator(text, settings) }) {
            return true
        }
        return launchTranslator(text, settings)
    }

    private fun dictionaryCandidate(text: String): String? {
        val word = text.trim { !it.isLetterOrDigit() }
        if (word.isBlank() || word.length > 48) {
            return null
        }
        if (word.any { it.isWhitespace() } || word.none { it.isLetter() }) {
            return null
        }
        return word
    }

    private fun launchTranslator(text: String, settings: TranslationSettings): Boolean {
        if (settings.mode == Preferences.translationModeApps) {
            return launchProcessTextTranslator(text)
        }
        val url = TranslationUrlBuilder.build(settings, text)
        if (url == null) {
            AppSnackbar.make(binding.root, R.string.translation_custom_url_invalid, Snackbar.LENGTH_LONG).show()
            return false
        }
        if (settings.engine.forceBrowser) {
            try {
                activity.startActivity(browserLinkIntent(url))
                return true
            } catch (e: ActivityNotFoundException) {
            }
        }
        return try {
            activity.startActivity(linkIntent(url))
            true
        } catch (e: ActivityNotFoundException) {
            AppSnackbar.make(binding.root, activity.getString(R.string.no_app_to_open_link), Snackbar.LENGTH_LONG).show()
            false
        }
    }

    private fun launchProcessTextTranslator(text: String): Boolean {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val handlers = activity.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != activity.packageName }
        return when {
            handlers.isEmpty() -> {
                showNoTranslationAppDialog()
                false
            }
            handlers.size == 1 -> {
                val info = handlers.first().activityInfo
                try {
                    activity.startActivity(intent.setClassName(info.packageName, info.name))
                    true
                } catch (e: ActivityNotFoundException) {
                    AppSnackbar.make(binding.root, R.string.no_translate_app_found, Snackbar.LENGTH_LONG).show()
                    false
                }
            }
            else -> {
                activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.translate)))
                true
            }
        }
    }

    private fun showNoTranslationAppDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.no_translate_app_found)
            .setMessage(R.string.no_translate_app_message)
            .setPositiveButton(R.string.settings) { _, _ -> onOpenSettings() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshCardRendering() {
        val card = binding.textSelectionActionCard
        card.requestLayout()
        card.invalidate()
        binding.root.invalidate()
        card.doOnLayout { positionCard() }
        card.post {
            card.requestLayout()
            card.invalidate()
            binding.root.invalidate()
            updateSaveUiPosition()
        }
    }

    /** Read per comment, so a name changed in Settings applies at once. */
    private val commentAuthor: String
        get() = CommentAuthor.current(activity)

    companion object {
        private const val COMMENT_MARKER_SIZE_DP = 28f
        private const val SELECTION_HANDLE_DP = 32f
        private const val MENU_COMMENT = 1
        private const val MENU_EDIT_SOURCE = 2
        private const val MENU_ADD_REVIEW_CARD = 3
        private const val COPY_FOR_AGENT_ITEM = 1
    }
}

/** Review card feedback on text selections and drawn highlights; see [InlineAnnotationActionController.reviewFeedback]. */
interface ReviewFeedbackActions {
    enum class Action { LIKE, DISLIKE, QUESTION, COMMENT }

    /** Questions are comments on a document; a card's answer takes likes, dislikes and comments. */
    val offersQuestion: Boolean get() = false

    fun qualifies(request: PDFView.HighlightRequest): Boolean
    fun give(request: PDFView.HighlightRequest, action: Action)
    fun open(feedbackId: String)

    /** A highlight's passage is being changed: the selection offers only Save passage and Cancel. */
    fun isChangingPassage(): Boolean = false

    /** Save passage: the highlight being changed moves to [request]'s text. */
    fun savePassage(request: PDFView.HighlightRequest) {}

    /** Cancel, or the selection went away: the highlight keeps its passage. */
    fun cancelPassageChange() {}
}
