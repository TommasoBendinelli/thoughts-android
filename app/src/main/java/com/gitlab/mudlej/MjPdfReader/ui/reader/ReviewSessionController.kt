// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.doOnLayout
import android.text.InputFilter
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.review.reviewProjectsByFilename
import com.gitlab.mudlej.MjPdfReader.ui.home.ReviewGenerationProjects
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewSkipScope
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerPhases
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewEntry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewPhases
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewRecallPrediction
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewIntervalUnit
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewRating
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAreaBox
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAreaEditSession
import com.gitlab.mudlej.MjPdfReader.data.review.reviewEditableAreas
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogReviewCardTextEditBinding
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardDetailsActivity
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardReaderContract
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewPlacementEditor
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewAnswerTextLoader
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewPlacementSourceFiles
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.basicPdfSha256
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.ReviewAreaLinkingException
import com.gitlab.mudlej.MjPdfReader.ui.review.finishAfterReviewBrowserRating
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewCanFollowProject
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewFollowProjectTitle
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewIntervalExceedsProjectCap
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewKindChoiceText
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewKindMark
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewKindMenuTitle
import com.gitlab.mudlej.MjPdfReader.ui.review.reviewSwappedKind
import com.gitlab.mudlej.MjPdfReader.ui.review.withKindOf
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ReviewSessionHistoryEntry {
    val cardId: String
    val singleCardSession: Boolean

    data class Rating(
        override val cardId: String,
        val ratingEventId: String,
        override val singleCardSession: Boolean = false,
        val ratingEvents: Map<String, String> = mapOf(cardId to ratingEventId),
    ) : ReviewSessionHistoryEntry

    data class Skip(
        override val cardId: String,
        val batchId: String,
        override val singleCardSession: Boolean = false,
    ) : ReviewSessionHistoryEntry

    /** Retire card; Undo writes undo_retire for [retireEventId] and brings the card back. */
    data class Retire(
        override val cardId: String,
        val retireEventId: String,
        override val singleCardSession: Boolean = false,
    ) : ReviewSessionHistoryEntry
}

internal class ReviewSessionHistory {
    private val entries = mutableListOf<ReviewSessionHistoryEntry>()

    val canUndo: Boolean get() = entries.isNotEmpty()

    fun record(cardId: String, ratingEventId: String, singleCardSession: Boolean = false) {
        entries += ReviewSessionHistoryEntry.Rating(cardId, ratingEventId, singleCardSession)
    }

    fun recordBatch(cardId: String, ratingEvents: Map<String, String>, singleCardSession: Boolean) {
        entries += ReviewSessionHistoryEntry.Rating(cardId, ratingEvents.getValue(cardId), singleCardSession, ratingEvents)
    }

    fun recordSkip(cardId: String, batchId: String, singleCardSession: Boolean = false) {
        entries += ReviewSessionHistoryEntry.Skip(cardId, batchId, singleCardSession)
    }

    fun recordRetire(cardId: String, retireEventId: String, singleCardSession: Boolean = false) {
        entries += ReviewSessionHistoryEntry.Retire(cardId, retireEventId, singleCardSession)
    }

    fun discardDeletedCard(cardId: String) {
        entries.removeAll { it.cardId == cardId ||
            (it is ReviewSessionHistoryEntry.Rating && cardId in it.ratingEvents) }
    }

    fun previous(): ReviewSessionHistoryEntry? = entries.lastOrNull()

    fun removePrevious(): ReviewSessionHistoryEntry? = entries.removeLastOrNull()
}

/** The review card's ⋮ menu, in order; [id] is the menu item id. */
internal enum class ReviewSessionMenuAction(val id: Int) {
    FOCUS(7), MORE_INFO(5), COPY_FOR_AGENT(13), EDIT(1), AGENT_CONVERSATIONS(14),
    /** "Make project card" or "Make knowledge card" (protocol/review-card-kinds.md section 5). */
    KIND(18),
    /** "Follow the Project (Knowledge)": only for a card with a kind of its own that a Project holds. */
    FOLLOW_PROJECT(19),
    SKIP(12), FLIP(17), UNDO(3), HIDE_AGAIN(6), BACK_TO_BEFORE(16), RETIRE(15), DELETE(4), END(10),
}

/**
 * The ⋮ menu's items: the card's actions while a card is shown, Undo and End review always.
 * Flip (cover the rest, show the answer, to test the other way round) only while the answer is
 * covered; the review bar has a single Reveal. Retire card sits directly above Delete card, its
 * permanent alternative. [beforeLocked]: the before step was answered and locked, so it can be gone back to.
 */
internal fun reviewSessionMenuActions(
    hasCard: Boolean,
    answerVisible: Boolean,
    beforeLocked: Boolean = false,
    /** The card has a kind of its own and a Project holds it ([com.gitlab.mudlej.MjPdfReader.ui.review.reviewCanFollowProject]). */
    followProject: Boolean = false,
): List<ReviewSessionMenuAction> = buildList {
    if (hasCard) addAll(listOf(ReviewSessionMenuAction.FOCUS, ReviewSessionMenuAction.MORE_INFO,
        ReviewSessionMenuAction.COPY_FOR_AGENT, ReviewSessionMenuAction.EDIT, ReviewSessionMenuAction.AGENT_CONVERSATIONS,
        ReviewSessionMenuAction.KIND))
    if (hasCard && followProject) add(ReviewSessionMenuAction.FOLLOW_PROJECT)
    // Skip postpones (interval and scope) and keeps the card's tags, so it needs no revealed answer.
    if (hasCard) add(ReviewSessionMenuAction.SKIP)
    if (hasCard && !answerVisible) add(ReviewSessionMenuAction.FLIP)
    add(ReviewSessionMenuAction.UNDO)
    if (hasCard && answerVisible) add(ReviewSessionMenuAction.HIDE_AGAIN)
    if (hasCard && beforeLocked) add(ReviewSessionMenuAction.BACK_TO_BEFORE)
    if (hasCard) addAll(listOf(ReviewSessionMenuAction.RETIRE, ReviewSessionMenuAction.DELETE))
    add(ReviewSessionMenuAction.END)
}

private const val STALE_RELOAD_TIMEOUT_MS = 5_000L
private const val TAG = "ReviewSession"
private const val PRESENTATION_STATE_KEY = "review_presentation"
/** Whether the review card shows its details (otherwise it is compact). */
private const val KEY_DETAILS_SHOWN = "review_card_details_shown"
private const val COMPACT_QUESTION_LINES = 2
/** Skip asks for a short postponement by default, independent of the bar's interval. */
private val SKIP_DEFAULT_INTERVAL = ReviewInterval(java.math.BigDecimal.TEN, ReviewIntervalUnit.MINUTES)

private data class ReviewCardOpenState(
    val card: ReviewCard?,
    val interval: ReviewInterval,
    val tagUsage: Map<String, Int>,
    /** Verified hash of the installed Library PDF the reader shows, if it is one. */
    val installedPdfSha256: String? = null,
    val currentTags: List<String>? = null,
    val currentInterval: ReviewInterval? = null,
    val before: KeptBeforeStep? = null,
)

/** As the card was opened: its last answer ([ReviewStore.lastAnsweredAt]) and the before step kept since ([ReviewBeforeDrafts]). */
private data class KeptBeforeStep(val answeredAt: Instant?, val draft: ReviewBeforeDrafts.Draft?)

class ReviewSessionController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val document: DocumentState,
    private val store: ReviewStore = ReviewStore(activity),
) {
    /** The interval Next records; each card starts at the suggestion (twice the last interval). */
    private var suggestedInterval = ReviewInterval.suggested(null, null)
    private var tagOrder = ReviewTags.BUILT_IN
    /**
     * The tags Next records, possibly none. A card starts with the tags of its last answer still
     * in effect, and with none if it was never answered.
     */
    private var selectedTags = emptyList<String>()
    /**
     * The tag sheet's open group and the group notes Next records with the tags; one per answer. In a
     * review in two steps also the before panel's picks; then [selectedTags] are the after step's.
     */
    private var answerDraft = ReviewAnswerDraft()
    /**
     * The before step of each card, kept until its answer is recorded, so that it survives the card
     * dropping out of the review and the reader or app restarting ([ReviewBeforeDrafts]).
     */
    private val beforeDrafts = ReviewBeforeDrafts(activity)
    /** The card shown and its last answer when it was shown: the answer its kept before step belongs to. */
    private var beforeDraftAnswer: Pair<String, Instant?>? = null
    /** Review in two steps: the groups asked before the answer, under the question. */
    private val beforePanel = ReviewBeforePanel(activity, binding, { store.originIdentity() }, { reopenBeforeStep() },
        onDraftChanged = ::keepBeforeStep) { onTagPhasesChanged() }
    /** The agent recall prediction service; null when this build has none (nothing is asked). */
    private val recallPredictor by lazy { ReviewRecallPrediction.Config.fromBuild() }
    /** The agent's recall prediction per card: asked once per card shown in the session. */
    private val recallPredictions = mutableMapOf<String, ReviewRecallPredictionState>()
    private var sessionTagOrder: List<String>? = null
    private var sessionInPlace = false
    // Re-parsed only when the Library index files change, so picking the next card stays fast.
    private val sourceIndex = SourceLinkedPdfIndex()
    private val placementEditor = ReviewPlacementEditor(activity)
    private val tagPreferences = ReviewTagPreferences(activity)
    /** Liked and disliked passages of the card's answer, drawn while the answer is visible. */
    private val feedback = ReviewFeedbackController(activity, binding, document, { store.originIdentity() }) { store.card(it) }

    /** The compact card shows the question in two lines and folds its details away; the choice is kept. */
    private val cardPreferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity)
    private var detailsShown = cardPreferences.getBoolean(KEY_DETAILS_SHOWN, false)
    /** Like, Dislike and Comment… on a selection in the revealed answer; taps on drawn feedback. */
    val feedbackActions: com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReviewFeedbackActions get() = feedback
    /** The agent conversations linked to the card: the newest above the buttons, all in the menu. */
    private val agentConversations = ReviewAgentConversations(activity, binding, { store.originIdentity() },
        allowed = { currentCard != null && !editingRegion }, frozen = { answerRevealed })
    private var currentCard: ReviewCard? = null
        set(value) {
            val previous = field
            field = value
            if (value?.id != previous?.id) feedback.hide()
            if (value == null) agentConversations.hide()
            // The agent-notes inbox reports the card being studied with each request.
            if (value != null) {
                com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteInbox.setStudyContext(
                    value.id, value.documentId.ifBlank { value.pdfDocumentId }.ifBlank { null })
            } else if (previous != null) {
                com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentNoteInbox.clearStudyContext(previous.id)
            }
        }
    /** The card and installed PDF version a reload was last requested for. */
    private var staleReloadFor: Pair<String, String>? = null
    private val history = ReviewSessionHistory()
    private val requestGate = ReaderAsyncRequestGate()
    private var openRequestJob: Job? = null
    private var operationInFlight = false
    private var editingRegion = false
    /** Edit card: which stored regions each box stands for, mirrored as boxes are added and removed. */
    private var areaSession: ReviewAreaEditSession? = null
    /** Why the last save could not link an area; shown above the boxes until the edit ends. */
    private var areaProblem: String? = null
    /** Edit card opened by "Place this card again" (Card details). */
    private var placingAgain = false
    /** Set knowledge: the answer is framed and the tag/interval/Next bar is shown. */
    private var answerRevealed = false
    /** Reveal: the answer is framed, without the bar. */
    private var answerShown = false
    private val answerVisible get() = answerRevealed || answerShown
    /** The card's tags and interval before this review, shown above the bar. */
    private var currentReviewState: Pair<List<String>?, ReviewInterval?> = null to null
    /** The answer areas in reading order, for the ‹ › buttons. */
    private var answerAreas: List<android.util.Pair<Int, android.graphics.RectF>> = emptyList()
    private var answerAreasCardId: String? = null
    private var answerAreaIndex = 0
    /** Where the reader was when the answer was revealed; Hide again returns there. */
    private var viewBeforeReveal: Any? = null
    /** The answer is covered on the PDF, or in the HTML view while that is shown. */
    private val pdfPresenter: ReviewMaskPresenter = PdfReviewMaskPresenter(binding)
    private var presenter: ReviewMaskPresenter = pdfPresenter
    /** Set while the review itself switches the reader to the PDF (a card not shown in HTML). */
    private var switchingView = false
    private var maskFlipped = false
    var isActive = false
        private set
    private var reviewHidden = false
    /**
     * The card and answer in progress to give back when that card is shown again: saved with the
     * activity, or kept while the reader reloads the same PDF. Consumed by the next [showCard].
     */
    private var pendingPresentation: ReviewPresentationState? = null
    /** The document (URI and verified hash) the current card was shown on. */
    private var shownForDocument: Pair<Any?, String?>? = null
    /** The card whose open waits for the reader to finish loading its PDF, and until when. */
    private var documentWaitFor: Pair<String, Long>? = null
    private var reviewMarkupInitialized = false
    private var cardDialog: android.app.Dialog? = null
    private var tagSheetOpening = false

    init {
        binding.reviewOptionsButton.setOnClickListener(::showOptions)
        binding.reviewDetailsToggle.setOnClickListener { toggleDetails() }
        binding.reviewQuestion.setOnClickListener { if (!editingRegion) toggleDetails() }
        binding.reviewRegionCancelButton.setOnClickListener { cancelRegionEdit() }
        binding.reviewRegionSaveButton.setOnClickListener { saveRegion() }
        binding.reviewAddAreaButton.setOnClickListener { addArea() }
        binding.reviewRemoveAreaButton.setOnClickListener { removeSelectedArea() }
        // Touching a box selects it; keep the area label and Remove area in step.
        binding.pdfView.setOnReviewRegionsChangeListener { _, _ ->
            if (editingRegion) updateAreaControls() else activity.onManualReviewAreasChanged()
        }
        binding.reviewQuestionToggleButton.setOnClickListener {
            if (!editingRegion || operationInFlight) return@setOnClickListener
            val expanded = binding.reviewQuestionEditLayout.visibility == View.VISIBLE
            if (expanded) hideKeyboard()
            showTextFields(!expanded)
        }
        binding.reviewQuestionEdit.doAfterTextChanged { text ->
            if (!text.isNullOrBlank()) binding.reviewQuestionEditLayout.error = null
        }
        // One Reveal: the answer and the bar to say how well it was known (Hide again and Flip are in ⋮).
        binding.reviewRevealButton.setOnClickListener(::reveal)
        binding.reviewPreviousAnswerButton.setOnClickListener { focusAnswerArea(-1) }
        binding.reviewNextAnswerButton.setOnClickListener { focusAnswerArea(1) }
        binding.reviewDoneButton.setOnClickListener { if (sessionInPlace) endReview() else activity.finish() }
        // The HTML view reports its answer blocks later (‹ ›), or that it cannot show the card.
        activity.htmlReaderOrNull?.let { html ->
            html.onReviewBlocks = { if (presenter !== pdfPresenter && currentCard != null) updateNavigationActions() }
            activity.attachmentReaderOrNull?.let { attachment ->
                attachment.onReviewBlocks = { if (presenter === attachment.reviewPresenter && currentCard != null) updateNavigationActions() }
                attachment.onReviewUnavailable = { card, reason ->
                    android.util.Log.i(TAG, "review card=${card.id} attachment page unavailable: $reason")
                    if (currentCard?.id == card.id) AppSnackbar.make(binding.root, reason, Snackbar.LENGTH_LONG).show()
                }
            }
            html.onReviewUnavailable = { card, reason ->
                android.util.Log.i(TAG, "review card=${card.id} not shown in HTML: $reason")
                if (currentCard?.id == card.id && presenter !== pdfPresenter) {
                    switchingView = true
                    try { html.showPdf(remember = false) } finally { switchingView = false }
                    presenter = pdfPresenter
                    AppSnackbar.make(binding.root, R.string.html_view_review_in_pdf, Snackbar.LENGTH_LONG).show()
                    if (!setCardMasks(card)) AppSnackbar.make(binding.root, R.string.review_stale_geometry, Snackbar.LENGTH_LONG).show()
                    updateNavigationActions()
                }
            }
        }
        updateIntervalButton()
        binding.reviewTagButton.setOnClickListener { showTagSheet() }
        binding.reviewIntervalButton.setOnClickListener { showIntervalPicker() }
        binding.reviewNextButton.setTone(MaterialColors.getColor(binding.reviewNextButton, androidx.appcompat.R.attr.colorPrimary))
        // With no tag selected, Next records an answer without tags.
        binding.reviewNextButton.setOnClickListener { recordAnswer() }

    }

    fun saveState(state: Bundle) {
        state.putBoolean("review_markup_initialized", reviewMarkupInitialized)
        // A reader still reopening the card saves what it is about to give back.
        state.putString(PRESENTATION_STATE_KEY, (presentationState() ?: pendingPresentation)?.encode())
    }

    fun restoreState(state: Bundle?) {
        reviewMarkupInitialized = state?.getBoolean("review_markup_initialized") == true
        pendingPresentation = ReviewPresentationState.decode(state?.getString(PRESENTATION_STATE_KEY))
    }

    /** The shown card and the answer in progress; null without a card. */
    private fun presentationState(): ReviewPresentationState? {
        val card = currentCard ?: return null
        return ReviewPresentationState(
            cardId = card.id,
            hidden = reviewHidden,
            answerRevealed = answerRevealed,
            answerShown = answerShown,
            maskFlipped = maskFlipped,
            selectedTags = selectedTags,
            interval = suggestedInterval,
            draft = answerDraft.takeIf { it.cardId == card.id }?.snapshot() ?: ReviewAnswerDraft(card.id).snapshot(),
        )
    }

    /** Keeps the shown card's answer in progress if the review still asks for that card. */
    private fun keepPresentationForReopen() {
        val state = presentationState() ?: return
        if (activity.intent.getStringExtra(HomeActivity.EXTRA_REVIEW_CARD_ID) == state.cardId) pendingPresentation = state
    }

    private fun hideKeyboard() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.reviewQuestionEdit.clearFocus()
        binding.reviewBeforeNote.clearFocus()
    }

    /** Edit card's question field and the button that shows or hides it. */
    private fun showTextFields(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        binding.reviewQuestionEditLayout.visibility = visibility
        binding.reviewQuestionToggleButton.setText(if (visible) R.string.review_hide_question else R.string.review_edit_question)
    }

    /**
     * The reader is replacing its document. A reload of the same PDF (Sync, resume) reopens the
     * card afterwards, so the answer in progress is kept for it.
     */
    /** A Sync finished: the conversations linked to the card may have changed on the Mac. */
    fun onSyncFinished() {
        if (!isActive) return
        agentConversations.refresh()
        // The Sync may have brought a layout that asks other groups before the answer.
        refreshBeforeStep()
    }

    fun onDocumentReset() {
        keepPresentationForReopen()
        resetPresentation()
    }

    private fun resetPresentation() {
        hideKeyboard()
        showTextFields(false)
        cardDialog?.dismiss()
        cardDialog = null
        editingRegion = false
        areaSession = null
        answerRevealed = false
        answerShown = false
        viewBeforeReveal = null
        maskFlipped = false
        binding.pdfView.clearReviewRegionSelection()
        binding.reviewRegionActions.visibility = View.GONE
        binding.reviewAreaActions.visibility = View.GONE
        requestGate.invalidate()
        openRequestJob?.cancel()
        openRequestJob = null
        clearMasks()
        binding.reviewCard.visibility = View.GONE
        binding.reviewQuestion.text = ""
        beforePanel.hide()
        currentCard = null
        shownForDocument = null
        operationInFlight = false
        isActive = false
        reviewHidden = false
        refreshFeedback()
        activity.updateActionBar()
        activity.updateTitle()
    }

    /** [inPlace]: started from the reader itself, so Done ends the review instead of closing the reader. */
    fun beginReviewSession(inPlace: Boolean = false) {
        reviewMarkupInitialized = false
        sessionTagOrder = null
        sessionInPlace = inPlace
        recallPredictions.clear()
    }

    fun openIfRequested() {
        val cardId = activity.intent.getStringExtra(HomeActivity.EXTRA_REVIEW_CARD_ID) ?: return
        val requestedDocumentUri = document.uri
        val requestedPdfSha256 = document.annotationPdfSha256
        // Coming back to the reader must not start the card again: it is already shown, with its
        // revealed answer and the tags, interval and notes chosen so far.
        if (reviewCardAlreadyShown(
                shownCardId = currentCard?.id,
                requestedCardId = cardId,
                reviewActive = isActive,
                openPending = openRequestJob != null,
                shownForDocument = shownForDocument,
                currentDocument = requestedDocumentUri to requestedPdfSha256,
                pageCount = binding.pdfView.pageCount,
                placingAgain = activity.intent.getBooleanExtra(ReviewCardReaderContract.EXTRA_PLACE_AGAIN, false),
            )) {
            android.util.Log.i(TAG, "review-open card=$cardId already shown")
            // As it was, except for a layout synced since it was drawn.
            refreshBeforeStep()
            return
        }
        // The same card on a reloaded document comes back as it was.
        if (currentCard?.id == cardId) keepPresentationForReopen()
        val requestToken = requestGate.begin()
        openRequestJob?.cancel()
        clearMasks()
        binding.reviewCard.visibility = View.GONE
        currentCard = null
        shownForDocument = null
        operationInFlight = true
        updateNavigationActions()
        openRequestJob = activity.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                val card = store.card(cardId)
                // The quick-tag order is fixed for the session, so usage (cards per current tag) is
                // only counted once.
                val tagUsage = if (sessionTagOrder == null) store.reviewTagUsage() else emptyMap()
                // The same expected version VerifiedPdfActivationStore reloads to.
                val installed = requestedDocumentUri?.takeIf { it.scheme == "file" }?.path?.let(::File)?.let { file ->
                    sourceIndex.snapshot().let { it.libraryPdfSha256ByFilename[file.name] ?: it.pdfSha256ByFilename[file.name] }
                }
                val (currentTags, currentInterval) = store.currentReviewState(cardId)
                val answeredAt = store.lastAnsweredAt(cardId)
                ReviewCardOpenState(card, store.suggestedInterval(cardId), tagUsage, installed, currentTags, currentInterval,
                    KeptBeforeStep(answeredAt, beforeDrafts.get(cardId, answeredAt)))
            }
            if (
                !requestGate.isCurrent(requestToken) ||
                activity.intent.getStringExtra(HomeActivity.EXTRA_REVIEW_CARD_ID) != cardId ||
                document.uri != requestedDocumentUri ||
                document.annotationPdfSha256 != requestedPdfSha256
            ) {
                return@launch
            }
            openRequestJob = null
            operationInFlight = false
            val card = state.card
            val placeAgainRequested = activity.intent.getBooleanExtra(ReviewCardReaderContract.EXTRA_PLACE_AGAIN, false)
            // A retired card is never offered for review; placing it again still works.
            if (card == null || !card.approved || card.schedule?.deleted == true || (card.retired && !placeAgainRequested)) {
                android.util.Log.i(TAG, "review-open card=$cardId unavailable (missing, unapproved, deleted or retired)")
                clearReviewRequest()
                resetPresentation()
                return@launch
            }
            val actualHash = document.annotationPdfSha256.orEmpty().lowercase()
            val pageCount = binding.pdfView.pageCount
            val installedHash = state.installedPdfSha256?.lowercase()
            val placeAgain = activity.intent.getBooleanExtra(ReviewCardReaderContract.EXTRA_PLACE_AGAIN, false) && !card.wholeDocument &&
                !card.isHtmlAttachmentCard
            val outcome = reviewOpenOutcome(
                shownPdfSha256 = actualHash,
                pageCount = pageCount,
                // A card inside an HTML attachment page follows its document, not one PDF version.
                cardPdfSha256 = if (card.isHtmlAttachmentCard && actualHash.isNotEmpty()) actualHash else card.pdfSha256,
                // A card made in the HTML view has no PDF areas until Fedora links it; it opens with its notice.
                // One made inside an HTML attachment page never has any: it reviews in the page.
                cardHasAreas = card.rects.isNotEmpty() || (card.htmlPending && !card.hasPdfPlacement) || card.isHtmlAttachmentCard,
                installedPdfSha256 = installedHash,
                reloadAlreadyRequested = installedHash != null && staleReloadFor == card.id to installedHash,
                alreadyWaited = documentWaitFor?.let { (waiting, until) ->
                    waiting == card.id && android.os.SystemClock.uptimeMillis() >= until
                } == true,
                placingAgain = placeAgain,
            )
            when (outcome) {
                // The reader is still loading or reloading its PDF (resume, Sync): the load opens
                // the card when it ends. Wait for it rather than calling a valid card unavailable.
                ReviewOpenOutcome.WAIT_FOR_DOCUMENT -> {
                    if (documentWaitFor?.first != card.id) {
                        documentWaitFor = card.id to android.os.SystemClock.uptimeMillis() + STALE_RELOAD_TIMEOUT_MS
                    }
                    android.util.Log.i(TAG, "review-open card=${card.id} waiting for the PDF " +
                        "(hash=${actualHash.take(12).ifEmpty { "none" }} pages=$pageCount)")
                    operationInFlight = true
                    updateNavigationActions()
                    binding.root.postDelayed({
                        if (documentWaitFor?.first == card.id && currentCard == null && openRequestJob == null &&
                            activity.intent.getStringExtra(HomeActivity.EXTRA_REVIEW_CARD_ID) == card.id) {
                            openIfRequested()
                        }
                    }, STALE_RELOAD_TIMEOUT_MS)
                    return@launch
                }
                // Sync replaces a Library PDF in place, so a reader opened before it still shows the
                // old version. Reload the installed one and reopen the card instead of dropping it.
                ReviewOpenOutcome.RELOAD_INSTALLED -> {
                    val expected = checkNotNull(installedHash)
                    staleReloadFor = card.id to expected
                    android.util.Log.i(TAG, "review-open card=${card.id} reloading the installed PDF ${expected.take(12)}")
                    activity.reloadVerifiedPdfUpdate()
                    // A reload that cannot start yet must not leave the session without a card.
                    binding.root.postDelayed({
                        if (staleReloadFor == card.id to expected && currentCard == null &&
                            activity.intent.getStringExtra(HomeActivity.EXTRA_REVIEW_CARD_ID) == card.id) {
                            openIfRequested()
                        }
                    }, STALE_RELOAD_TIMEOUT_MS)
                    return@launch
                }
                ReviewOpenOutcome.STALE -> {
                    android.util.Log.i(TAG, "review-open card=${card.id} stale: pdf=${actualHash.take(12)} " +
                        "card=${card.pdfSha256.take(12)} installed=${installedHash?.take(12)} " +
                        "pages=$pageCount areas=${card.rects.size}")
                    clearMasks()
                    binding.reviewCard.visibility = View.GONE
                    clearReviewRequest()
                    resetPresentation()
                    AppSnackbar.make(
                        binding.root,
                        R.string.review_stale_geometry,
                        Snackbar.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                ReviewOpenOutcome.SHOW -> Unit
            }
            documentWaitFor = null
            currentReviewState = state.currentTags to state.currentInterval
            showCard(card, state.interval, state.tagUsage, state.before)
            if (placeAgain) {
                activity.intent.removeExtra(ReviewCardReaderContract.EXTRA_PLACE_AGAIN)
                editRegion(card)
                placingAgain = editingRegion
                updateAreaControls()
            }
        }
    }

    /** A card shown without looking up its kept before step (tests show cards so): nothing is kept for it either. */
    private fun showCard(card: ReviewCard, interval: ReviewInterval, tagUsage: Map<String, Int>) =
        showCard(card, interval, tagUsage, null)

    /**
     * [kept]: the card's last answer, which its before step is kept for from now on, and the step kept
     * since that answer ([ReviewBeforeDrafts]); null when they were not looked up.
     */
    private fun showCard(card: ReviewCard, interval: ReviewInterval, tagUsage: Map<String, Int>, kept: KeptBeforeStep?) {
        editingRegion = false
        areaSession = null
        binding.reviewAreaActions.visibility = View.GONE
        // The answer in progress when the reader was left or reloaded, for this card only.
        val restored = pendingPresentation?.takeIf { it.cardId == card.id }
        pendingPresentation = null
        reviewHidden = restored?.hidden == true
        answerRevealed = restored?.answerRevealed == true
        answerShown = restored?.answerShown == true
        viewBeforeReveal = null
        maskFlipped = restored?.maskFlipped == true
        binding.pdfView.clearReviewRegionSelection()
        binding.reviewRegionActions.visibility = View.GONE
        hideKeyboard()
        currentCard = card
        agentConversations.show(card.id)
        shownForDocument = document.uri to document.annotationPdfSha256
        // Answers record which version of the PDF they were given on; a PDF the Library does not
        // vouch for is hashed now, off the main thread, so Next does not wait for it.
        activity.lifecycleScope.launch(Dispatchers.IO) { store.prepareVersionStamp(card) }
        suggestedInterval = restored?.interval ?: interval
        // Keep the tag order for the whole session so the picker's list doesn't move.
        tagOrder = sessionTagOrder ?: ReviewTags.byPopularity(tagUsage, tagPreferences.created())
            .also { sessionTagOrder = it }
        // Only slider steps: the card's free tags from before are not carried into the new answer, nor
        // the steps of groups asked only before the answer (asked afresh in the before panel).
        val groups = ReviewTagGroups.groups()
        val mapping = ReviewTagMerges.mapping()
        selectedTags = restored?.selectedTags?.let { com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagScales.onlySteps(it, groups, mapping) }
            ?: ReviewAnswerPhases.afterStart(currentReviewState.first.orEmpty(), groups, mapping)
        // A tag answered elsewhere since the session began is still offered in the picker.
        selectedTags.filterNot(tagOrder::contains).takeIf { it.isNotEmpty() }?.let { missing ->
            tagOrder = tagOrder + missing
            sessionTagOrder = tagOrder
        }
        // Notes typed for this card stay while it is shown again (e.g. after a reload).
        beforeDraftAnswer = kept?.let { card.id to it.answeredAt }
        var keptLocked = false
        if (restored != null) answerDraft = ReviewAnswerDraft.restored(card.id, restored.draft)
        else if (answerDraft.cardId != card.id) answerDraft = ReviewAnswerDraft(card.id).also { draft ->
            // The card left the review before its answer was recorded (unavailable for a while, skipped,
            // the reader or app closed): its before step comes back as it was left.
            kept?.draft?.let { draft.restoreBefore(it); keptLocked = draft.beforeLocked }
        }
        // An answer already on screen (saved before answers were given in two steps) locks the before step.
        if ((answerRevealed || answerShown) && !answerDraft.beforeLocked) answerDraft.answerShownAt = Instant.now()
        // Kept locked (its answer had been shown): the after step starts where Reveal started it then.
        if (keptLocked) selectedTags = ReviewAnswerPhases.startAtPrediction(selectedTags, answerDraft.beforeTags, groups, mapping)
        beforePanel.show(answerDraft)
        keepBeforeStep()
        requestRecallPrediction(card)
        showRecallPrediction()
        updateIntervalButton()
        updateTagButton()
        showTextFields(false)
        binding.reviewQuestion.text = card.question
        binding.reviewQuestion.visibility = View.VISIBLE
        showKindMark(card)
        binding.reviewRelatedContext.visibility = View.GONE
        showPagesProgress(card)
        binding.reviewRevealButton.visibility = if (answerRevealed) View.GONE else View.VISIBLE
        binding.reviewDoneButton.visibility = View.GONE
        binding.reviewRatingScroll.visibility = if (answerRevealed) View.VISIBLE else View.GONE
        updateCurrentState()
        setRatingButtonsEnabled(answerRevealed)
        if (!setCardMasks(card)) {
            android.util.Log.i(TAG, "review-open card=${card.id} stale: masks do not fit the PDF")
            clearReviewRequest()
            resetPresentation()
            AppSnackbar.make(binding.root, R.string.review_stale_geometry, Snackbar.LENGTH_LONG).show()
            return
        }
        if (!reviewMarkupInitialized) {
            activity.enableReviewMarkup()
            reviewMarkupInitialized = true
        }
        if (reviewHidden) clearMasks()
        isActive = true
        binding.reviewCard.visibility = if (reviewHidden) View.GONE else View.VISIBLE
        updateNavigationActions()
    }

    /** "Answer on pages 3, 4" for a card spread over several pages; hidden otherwise. */
    private fun showPagesProgress(card: ReviewCard?) {
        val multiPage = (card?.regions?.map { it.page }?.distinct()?.size ?: 0) > 1
        binding.reviewProgress.visibility = if (multiPage) View.VISIBLE else View.GONE
        if (card?.wholeDocument == true) binding.reviewProgress.setText(R.string.review_whole_document)
        else if (multiPage) binding.reviewProgress.text = activity.getString(R.string.review_answer_pages, card!!.pagesLabel)
    }

    /** Covers the answer, or frames it once revealed. */
    private fun setCardMasks(card: ReviewCard): Boolean {
        val attachment = activity.attachmentReaderOrNull
        if (card.isHtmlAttachmentCard && attachment != null) {
            // Made inside an HTML attachment page: reviewed in that page, over whichever view is shown.
            val next = attachment.reviewPresenter
            if (next !== presenter) presenter.clear()
            presenter = next
            pdfPresenter.clear()
            if (!presenter.present(card, maskFlipped, answerVisible)) return false
            answerAreas = emptyList()
            if (answerAreasCardId != card.id) answerAreaIndex = 0
            answerAreasCardId = card.id
            return true
        }
        val html = activity.htmlReaderOrNull
        val useHtml = html != null && html.isActive && html.canPresent(card)
        if (html != null && html.isActive && !useHtml && !switchingView) {
            // "Not shown in HTML view": the card reviews in the PDF.
            switchingView = true
            try { html.showPdf(remember = false) } finally { switchingView = false }
            AppSnackbar.make(binding.root, R.string.html_view_review_in_pdf, Snackbar.LENGTH_LONG).show()
        }
        val next = if (useHtml) html!!.reviewPresenter else pdfPresenter
        if (next !== presenter) presenter.clear()
        presenter = next
        if (reviewNeedsHtmlViewNotice(card, useHtml)) {
            // No PDF placement yet: nothing is masked; the HTML view can show it.
            pdfPresenter.clear()
            answerAreas = emptyList()
            answerAreasCardId = card.id
            showPlacedAtNextSyncNotice(card)
            return true
        }
        if (!presenter.present(card, maskFlipped, answerVisible)) return false
        answerAreas = if (presenter === pdfPresenter) binding.pdfView.reviewMaskBlocks() else emptyList()
        if (answerAreasCardId != card.id) answerAreaIndex = 0
        answerAreasCardId = card.id
        return true
    }

    private var placedNoticeFor: String? = null

    /** "Placed at next Sync — review it in the HTML view", with Skip and the switch to the HTML view. */
    private fun showPlacedAtNextSyncNotice(card: ReviewCard) {
        if (placedNoticeFor == card.id && cardDialog?.isShowing == true) return
        placedNoticeFor = card.id
        cardDialog?.dismiss()
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.html_view_placed_next_sync_title)
            .setMessage(R.string.html_view_card_placed_next_sync)
            .setPositiveButton(R.string.html_view_show_html) { _, _ ->
                if (currentCard?.id == card.id) activity.htmlReaderOrNull?.toggle()
            }
            .setNegativeButton(R.string.review_skip) { _, _ -> if (currentCard?.id == card.id) showSkipMenu() }
            .setNeutralButton(R.string.close, null)
            .create()
        cardDialog = dialog
        dialog.setOnDismissListener { if (cardDialog === dialog) cardDialog = null }
        dialog.show()
    }

    /** Covers nothing in either view. */
    private fun clearMasks() {
        pdfPresenter.clear()
        activity.htmlReaderOrNull?.reviewPresenter?.clear()
        activity.attachmentReaderOrNull?.reviewPresenter?.clear()
    }

    /** The reader switched between the PDF and the HTML view: the card under review follows. */
    fun onViewModeChanged() {
        if (switchingView) return
        val card = currentCard ?: return
        if (!isActive || editingRegion || reviewHidden || operationInFlight) return
        viewBeforeReveal = null
        if (!setCardMasks(card)) {
            AppSnackbar.make(binding.root, R.string.review_stale_geometry, Snackbar.LENGTH_LONG).show()
        }
        updateNavigationActions()
    }

    /** Answer blocks for ‹ ›: the PDF's mask blocks, or the HTML viewer's count. */
    private fun answerAreaCount(): Int = if (presenter === pdfPresenter) answerAreas.size else presenter.blockCount()

    /** Like, Dislike or Comment… on a selection inside the revealed answer in the HTML view. */
    internal fun giveHtmlFeedback(
        quote: com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel.TextQuote,
        htmlEditionSha256: String,
        action: HtmlReaderController.ReviewFeedbackAction,
    ) {
        if (currentCard == null || !answerVisible) return
        feedback.giveFromHtml(quote.exact, quote.prefix, quote.suffix, htmlEditionSha256, action)
    }

    /** Moves to the previous or next answer area of a card with several. */
    private fun focusAnswerArea(step: Int) {
        if (editingRegion) {
            focusEditArea(step)
            return
        }
        val card = currentCard ?: return
        val count = answerAreaCount()
        if (answerAreasCardId != card.id || count < 2 || editingRegion) return
        answerAreaIndex = Math.floorMod(answerAreaIndex + step, count)
        if (presenter !== pdfPresenter) {
            presenter.focusBlock(answerAreaIndex, visibleBottomAboveCard())
            return
        }
        val area = answerAreas[answerAreaIndex]
        binding.pdfView.focusOnReviewRegion(area.first, area.second, visibleBottomAboveCard())
    }

    /** The bottom of the PDF area the review card, and the study bar on top of it, leave uncovered. */
    private fun visibleBottomAboveCard(): Float {
        val pdfLocation = IntArray(2)
        val cardLocation = IntArray(2)
        binding.pdfView.getLocationInWindow(pdfLocation)
        val overlay = binding.studyPageBar.root.takeIf { it.visibility == View.VISIBLE } ?: binding.reviewCard
        overlay.getLocationInWindow(cardLocation)
        return (cardLocation[1] - pdfLocation[1] - 12 * activity.resources.displayMetrics.density)
            .coerceIn(1f, binding.pdfView.height.toFloat())
    }

    private fun updateCurrentState() {
        val (tags, interval) = currentReviewState
        val parts = listOfNotNull(
            tags?.joinToString { reviewTagLabel(activity, it) }?.ifEmpty { activity.getString(R.string.review_tags_none) },
            interval?.label(activity),
        )
        binding.reviewCurrentState.text = activity.getString(R.string.review_current_state,
            parts.joinToString(" · ").ifEmpty { activity.getString(R.string.review_current_new) })
        binding.reviewCurrentState.visibility = binding.reviewRatingScroll.visibility
    }

    fun updateToolbar() {
        val toolbar = activity.supportActionBar?.customView ?: return
        toolbar.findViewById<View>(R.id.reviewToolbarActions).visibility = if (isActive) View.VISIBLE else View.GONE
        if (isActive) toolbar.findViewById<View>(R.id.actionbarPageNumber).visibility = View.GONE
        toolbar.findViewById<com.google.android.material.button.MaterialButton>(R.id.reviewVisibilityButton).apply {
            setText(if (reviewHidden) R.string.review_action_focus else R.string.review_action_hide)
            isEnabled = isActive && !operationInFlight && !editingRegion
            setOnClickListener { if (reviewHidden) focusReview() else toggleReviewVisibility() }
        }
    }

    private fun toggleReviewVisibility() {
        if (!isActive || operationInFlight || editingRegion) return
        if (reviewHidden) {
            val card = currentCard
            if (card != null && !setCardMasks(card)) {
                AppSnackbar.make(binding.root, R.string.review_stale_geometry, Snackbar.LENGTH_LONG).show()
                return
            }
        }
        hideKeyboard()
        reviewHidden = !reviewHidden
        if (reviewHidden) clearMasks()
        binding.reviewCard.visibility = if (reviewHidden) View.GONE else View.VISIBLE
        updateNavigationActions()
    }

    private fun focusReview() {
        val card = currentCard ?: return
        if (!isActive || operationInFlight || editingRegion) return
        if (!setCardMasks(card)) {
            AppSnackbar.make(binding.root, R.string.review_stale_geometry, Snackbar.LENGTH_LONG).show()
            return
        }
        hideKeyboard()
        reviewHidden = false
        binding.reviewCard.visibility = View.VISIBLE
        updateNavigationActions()
        binding.reviewCard.doOnLayout {
            if (currentCard?.id != card.id || reviewHidden || !isActive || editingRegion) return@doOnLayout
            if (presenter !== pdfPresenter) {
                presenter.focusAnswer(card, visibleBottomAboveCard())
                return@doOnLayout
            }
            val region = card.regions.minWithOrNull(compareBy({ it.page != card.page }, { it.page }, { it.rect.top }, { it.rect.left }))
                ?: return@doOnLayout
            binding.pdfView.focusOnReviewRegion(region.page - 1, region.rect, visibleBottomAboveCard())
        }
    }

    private fun endReview() {
        if (operationInFlight || editingRegion) return
        clearReviewRequest()
        resetPresentation()
    }

    /** The card shown, for the agent API (agent/AgentCommands.kt). */
    internal fun agentCard(): com.gitlab.mudlej.MjPdfReader.agent.AgentReviewCardState? =
        currentCard?.let { com.gitlab.mudlej.MjPdfReader.agent.AgentReviewCardState(it, answerVisible) }

    /** The agent API's reveal: what the Reveal button does; true once the answer is visible. */
    internal fun agentRevealAnswer(): Boolean {
        currentCard ?: return false
        showAnswer()
        return answerVisible
    }

    /** Reveal: frame the answer, without recording anything yet. */
    private fun showAnswer() {
        currentCard ?: return
        if (operationInFlight || editingRegion || answerVisible) return
        hideKeyboard()
        lockBeforeStep()
        answerShown = true
        viewBeforeReveal = presenter.captureView()
        presenter.setRevealed(true)
        updateNavigationActions()
    }

    /** Flip: cover the rest and show the answer (test the other way round); Unflip puts it back. Before the answer only. */
    private fun flip() {
        if (currentCard == null || operationInFlight || editingRegion || answerVisible) return
        maskFlipped = !maskFlipped
        presenter.setInverted(maskFlipped)
    }

    /** Reveal: frame the answer and show the tag/interval/Next bar. */
    private fun reveal(@Suppress("UNUSED_PARAMETER") view: View) {
        currentCard ?: return
        if (operationInFlight || editingRegion) return
        hideKeyboard()
        lockBeforeStep()
        if (!answerVisible) viewBeforeReveal = presenter.captureView()
        answerRevealed = true
        presenter.setRevealed(true)
        binding.reviewRevealButton.visibility = View.GONE
        binding.reviewRatingScroll.visibility = View.VISIBLE
        updateCurrentState()
        setRatingButtonsEnabled(true)
        updateNavigationActions()
    }

    /**
     * The answer is shown for the first time (Reveal or Set knowledge): the before panel locks, so
     * later picks are never "before", and each before-and-after slider (Recall) of the after step
     * starts at its predicted step. The agent's prediction may show from now on. After "Back to before
     * step" this runs again on the new picks, and the time the answer was first shown stays.
     */
    private fun lockBeforeStep() {
        val draft = answerDraft
        if (draft.cardId != currentCard?.id || draft.beforeLocked) return
        if (draft.answerShownAt == null) draft.answerShownAt = Instant.now()
        draft.beforeReopened = false
        if (draft.beforeShown) {
            selectedTags = ReviewAnswerPhases.startAtPrediction(selectedTags, draft.beforeTags,
                ReviewTagGroups.groups(), ReviewTagMerges.mapping())
            updateTagButton()
        }
        beforePanel.show(draft)
        showRecallPrediction()
        keepBeforeStep()
    }

    /**
     * Keeps the before step of the card shown as it is now ([ReviewBeforeDrafts]): after each pick,
     * each change of the note, Reveal and "Back to before step". Kept for the answer the card had
     * when it was shown; nothing for a step with no pick and no note.
     */
    private fun keepBeforeStep() {
        val draft = answerDraft
        val (cardId, answeredAt) = beforeDraftAnswer ?: return
        if (draft.cardId != cardId || !draft.beforeShown) return
        beforeDrafts.put(cardId, ReviewBeforeDrafts.of(draft, answeredAt))
    }

    /** "Ask" changed in the before panel: it shows the new layout, and a new recall group is asked for. */
    private fun onTagPhasesChanged() {
        val card = currentCard ?: return
        if (answerDraft.cardId != card.id || editingRegion) return
        beforePanel.show(answerDraft)
        requestRecallPrediction(card)
        showRecallPrediction()
    }

    /** [onTagPhasesChanged] for a layout from elsewhere: only when its before groups differ from those drawn. */
    private fun refreshBeforeStep() {
        val card = currentCard ?: return
        if (answerDraft.cardId != card.id || editingRegion) return
        if (!beforePanel.showIfChanged(answerDraft)) return
        requestRecallPrediction(card)
        showRecallPrediction()
    }

    /**
     * Asks the agent, in the background, which step of the recall group ([ReviewTagGroups.recallGroup])
     * the user will remember: once per card in the session, as it comes up; only when this build has
     * the service and the layout has a recall group. Shown once the answer is shown; a failure shows
     * and records nothing.
     */
    private fun requestRecallPrediction(card: ReviewCard) {
        val config = recallPredictor ?: return
        if (recallPredictions.containsKey(card.id)) return
        val mapping = ReviewTagMerges.mapping()
        val recall = ReviewTagGroups.recallGroup(ReviewTagGroups.resolved(ReviewTagGroups.groups(), mapping)) ?: return
        val steps = recall.scale.orEmpty()
        recallPredictions[card.id] = ReviewRecallPredictionState.Pending
        val title = activity.shownDocumentTitle().ifBlank { card.pdfStem }
        val readerPdf = document.uri?.takeIf { it.scheme == "file" }?.path?.let(::File)
        activity.lifecycleScope.launch {
            val prediction = withContext(Dispatchers.IO) {
                runCatching {
                    val (history, others) = store.recallPredictionHistory(card.id)
                    val answer = cardAnswerText(card, readerPdf)
                    val requestedAt = Instant.now()
                    val body = ReviewRecallPrediction.requestBody(card.id, card.question, answer, title, steps, recall.id,
                        history, others, requestedAt, mapping)
                    val response = ReviewRecallPrediction.request(config, body, log = { android.util.Log.i(TAG, "card=${card.id} $it") },
                        vpnActive = { com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsNetworkPolicy.hasActiveVpn(activity) })
                    ReviewRecallPrediction.parseResponse(response, steps, recall.id, requestedAt)
                }.onFailure { error ->
                    android.util.Log.i(TAG, "card=${card.id} recall prediction unavailable: ${error.javaClass.simpleName} ${error.message}")
                }.getOrNull()
            }
            recallPredictions[card.id] = prediction?.let(ReviewRecallPredictionState::Ready) ?: ReviewRecallPredictionState.Failed
            if (currentCard?.id == card.id) showRecallPrediction()
        }
    }

    /** The agent's prediction for the card shown, in the review card and in the tag sheet if it is open. */
    private fun showRecallPrediction() {
        val card = currentCard ?: return
        val text = reviewRecallPredictionText(activity, recallPredictions[card.id])
            ?.takeIf { answerDraft.cardId == card.id && answerDraft.beforeLocked }
        beforePanel.showPrediction(text)
        cardDialog?.findViewById<android.widget.TextView>(R.id.reviewTagSheetAgent)?.let { line ->
            line.text = text
            line.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * Next: records the answer. Given in two steps, its tags are the before-only groups' and the after
     * step's ([ReviewAnswerPhases.recordedTags]) and it carries [ReviewPhases], with the agent's
     * prediction if it arrived before now.
     */
    private fun recordAnswer() {
        val card = currentCard ?: return
        val draft = answerDraft
        val twoSteps = draft.cardId == card.id && draft.beforeShown
        val groups = ReviewTagGroups.groups()
        val mapping = ReviewTagMerges.mapping()
        val prediction = (recallPredictions[card.id] as? ReviewRecallPredictionState.Ready)?.prediction
        val tags = if (twoSteps) ReviewAnswerPhases.recordedTags(draft.beforeTags, selectedTags, groups, mapping) else selectedTags
        val phases = if (!twoSteps) null
            else ReviewAnswerPhases.phases(draft.beforeTags, selectedTags, draft.answerShownAt, prediction, groups, mapping)
        record(ReviewRating.CUSTOM_INTERVAL, suggestedInterval, tags, groupNotes = draft.recordedNotes(),
            contextNotes = draft.recordedContextNotes(), reviewPhases = phases)
    }

    private fun hideAgain() {
        val card = currentCard ?: return
        if (operationInFlight || editingRegion || !answerVisible) return
        val wasRevealed = answerRevealed
        val wasShown = answerShown
        answerRevealed = false
        answerShown = false
        if (!setCardMasks(card)) {
            answerRevealed = wasRevealed
            answerShown = wasShown
            AppSnackbar.make(binding.root, R.string.review_stale_geometry, Snackbar.LENGTH_LONG).show()
            return
        }
        hideKeyboard()
        // Reading a long answer scrolls away from the question; return to where it was asked.
        viewBeforeReveal?.let(presenter::restoreView)
        viewBeforeReveal = null
        binding.reviewRevealButton.visibility = View.VISIBLE
        binding.reviewRatingScroll.visibility = View.GONE
        updateCurrentState()
        setRatingButtonsEnabled(false)
        updateNavigationActions()
    }

    /** "Back to before step": the answer hidden again and the before panel open on its picks. */
    private fun reopenBeforeStep() {
        if (!canReopenBeforeStep()) return
        hideAgain()
        if (answerVisible) return
        answerDraft.beforeReopened = true
        beforePanel.show(answerDraft)
        keepBeforeStep()
    }

    private fun canReopenBeforeStep() =
        answerDraft.cardId == currentCard?.id && answerDraft.beforeShown && answerDraft.beforeLocked

    private fun showOptions(anchor: View) {
        if (operationInFlight || editingRegion) return
        val card = currentCard
        PopupMenu(activity, anchor).apply {
            val conversationCount = agentConversations.conversations.size
            // Items are added in menu order ([reviewSessionMenuActions]).
            reviewSessionMenuActions(card != null, answerVisible, canReopenBeforeStep(),
                followProject = card != null && reviewCanFollowProject(card)).forEach { action ->
                val item = when (action) {
                    ReviewSessionMenuAction.FOCUS -> menu.add(0, action.id, 0, R.string.review_action_focus)
                    ReviewSessionMenuAction.KIND -> menu.add(0, action.id, 0, reviewKindMenuTitle(card!!))
                    ReviewSessionMenuAction.FOLLOW_PROJECT -> menu.add(0, action.id, 0, reviewFollowProjectTitle(activity, card!!))
                    ReviewSessionMenuAction.MORE_INFO -> menu.add(0, action.id, 0, R.string.review_card_more_info)
                    // Paste into Codex or Claude: the document and the card, not its answer (Settings > Agent notes and prompts).
                    // Physical studying (Mentra photos) is study mode's prompt now: Home ⋮ > Study mode.
                    ReviewSessionMenuAction.COPY_FOR_AGENT -> menu.add(0, action.id, 0, R.string.copy_document_for_agent)
                    ReviewSessionMenuAction.EDIT -> menu.add(0, action.id, 0, R.string.review_edit)
                    ReviewSessionMenuAction.AGENT_CONVERSATIONS -> menu.add(0, action.id, 0,
                        activity.getString(R.string.agent_conversations_group, conversationCount))
                    ReviewSessionMenuAction.SKIP -> menu.add(0, action.id, 0, R.string.review_skip)
                    ReviewSessionMenuAction.FLIP -> menu.add(0, action.id, 0, if (maskFlipped) R.string.review_unflip else R.string.review_flip)
                    ReviewSessionMenuAction.UNDO -> menu.add(0, action.id, 0, R.string.review_undo)
                    ReviewSessionMenuAction.HIDE_AGAIN -> menu.add(0, action.id, 0, R.string.review_hide_again)
                    ReviewSessionMenuAction.BACK_TO_BEFORE -> menu.add(0, action.id, 0, R.string.review_back_to_before)
                    ReviewSessionMenuAction.RETIRE -> menu.add(0, action.id, 0, R.string.review_card_retire_menu)
                    ReviewSessionMenuAction.DELETE -> menu.add(0, action.id, 0, R.string.review_card_delete_menu)
                    ReviewSessionMenuAction.END -> menu.add(0, action.id, 0, R.string.review_action_end)
                }
                if (action == ReviewSessionMenuAction.AGENT_CONVERSATIONS) item.isEnabled = conversationCount > 0
                if (action == ReviewSessionMenuAction.UNDO) item.isEnabled = history.canUndo
            }
            setOnMenuItemClickListener { item ->
                if (currentCard?.id != card?.id || operationInFlight) return@setOnMenuItemClickListener false
                when (item.itemId) {
                    7 -> focusReview()
                    5 -> card?.let { activity.startActivity(ReviewCardDetailsActivity.intent(activity, it.id)) }
                    13 -> card?.let { copyForAgent(it) }
                    14 -> agentConversations.showList()
                    1 -> card?.let { editCard(it) }
                    12 -> showSkipMenu()
                    17 -> flip()
                    3 -> undoPrevious()
                    6 -> hideAgain()
                    16 -> reopenBeforeStep()
                    15 -> card?.let { retire(it) }
                    18 -> card?.let { changeKind(it, reviewSwappedKind(it)) }
                    19 -> card?.let { changeKind(it, com.gitlab.mudlej.MjPdfReader.data.review.ReviewKinds.FOLLOW_PROJECT) }
                    4 -> card?.let { confirmDelete(it) }
                    10 -> endReview()
                }
                true
            }
            show()
        }
    }

    /**
     * "Copy for agent": the saved template (Settings > Agent notes and prompts) filled in for the card
     * and its document. The answer text is read only for a template that shows it.
     */
    private fun copyForAgent(card: ReviewCard) {
        // The agent may answer with a note to draw on: listen for it while the user is in the agent app
        // (not while push delivers notes; AgentStudyListenerService.start says no then).
        com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentStudyListenerService.start(activity)
        val pages = card.regions.map { it.page }.filter { it > 0 }
        val documentFields = activity.agentDocumentFields()
        val readerPdf = document.uri?.takeIf { it.scheme == "file" }?.path?.let(::File)
        val preferences = com.gitlab.mudlej.MjPdfReader.data.Preferences(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity))
        val agentTemplate = preferences.getAgentPromptTemplate(AgentPromptTemplates.Kind.AGENT)
        val withAnswerText = AgentPromptTemplates.usesPlaceholder(agentTemplate, "answer_text")
        val displayed = feedback.displayed()
        activity.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val passages = runCatching { cardSourcePassages(card) }
                    .onFailure { android.util.Log.w("ReviewSession", "Could not read the card's source", it) }
                    .getOrDefault(emptyList())
                val pdfText = if (passages.isNotEmpty() || !withAnswerText) null else cardPdfAnswerText(card, readerPdf)
                val answerFeedback = runCatching { feedback.agentSection(card, displayed) }
                    .onFailure { android.util.Log.w("ReviewSession", "Could not read the card's feedback", it) }
                    .getOrDefault("")
                val fields = reviewCardPromptFields(documentFields, card.id, card.question, "",
                    pages.ifEmpty { listOfNotNull(card.pdfPage) }, passages, pdfText, answerFeedback)
                AgentPromptTemplates.renderForCard(fields, agentTemplate)
            }
            copyAgentText(activity, text)
        }
    }

    /** The PDF text in the card's answer areas, from the reader's PDF or the Library's when it is the card's version. */
    private fun cardPdfAnswerText(card: ReviewCard, readerPdf: File?): String? {
        val pdf = listOfNotNull(readerPdf, File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "${card.pdfStem}.pdf"))
            .firstOrNull { file -> file.isFile && runCatching { basicPdfSha256(file) }.getOrNull()
                ?.equals(card.pdfSha256, ignoreCase = true) == true }
        return pdf?.let { ReviewAnswerTextLoader(activity.applicationContext).regionsText(it, card.regions) }
    }

    /** The card's answer as Copy for agent gives it: its source passages, else the PDF text in its areas. Blocking. */
    private fun cardAnswerText(card: ReviewCard, readerPdf: File?): String {
        val passages = runCatching { cardSourcePassages(card) }.getOrDefault(emptyList())
        return reviewCardPromptFields(emptyMap(), card.id, card.question, "", emptyList(), passages,
            if (passages.isEmpty()) runCatching { cardPdfAnswerText(card, readerPdf) }.getOrNull() else null)
            .getValue("answer_text")
    }

    /** The source passages inside the card's mask markers, from the phone's working copies of its sources. */
    private fun cardSourcePassages(card: ReviewCard): List<ReviewCardSourcePassage> {
        val anchored = card.sourceAnchor?.getAsJsonArray("segments")?.mapNotNull { segment ->
            segment.takeIf { it.isJsonObject }?.asJsonObject?.get("source_file")
                ?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
        }.orEmpty()
        val filename = "${card.pdfStem}.pdf"
        val store = OfflineSourceBundleStore(activity.applicationContext)
        val candidates = reviewPlacementSourceFiles((anchored + card.sourceFiles).distinct(),
            store.librarySourceMetadata(filename)?.pdfs?.get(filename), card.pdfPage ?: 1)
        val sources = candidates.mapNotNull { path ->
            runCatching { store.sourceSnapshot(path, 1)?.contents }.getOrNull()?.let { path to it }
        }
        return reviewCardSourcePassages(card.id, sources)
    }

    /**
     * Edit card. A card whose answer is marked in the source is placed by editing the source, so
     * it gets its question and note with Edit placement, like a whole-document card without it.
     * Other cards edit their areas on the page.
     */
    private fun editCard(card: ReviewCard, showText: Boolean = false) {
        if (card.wholeDocument || card.placedInSource) editCardText(card) else editRegion(card, showText)
    }

    private val ReviewCard.placedInSource get() = !wholeDocument && sourceAnchor != null

    /** Edit card without areas: its question in a dialog, and Edit placement for a source-marked card. */
    private fun editCardText(card: ReviewCard) {
        val editor = DialogReviewCardTextEditBinding.inflate(activity.layoutInflater)
        editor.reviewCardTextQuestion.setText(card.question)
        editor.reviewCardTextQuestion.setSelection(card.question.length)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.review_card_edit_text_title)
            .setView(editor.root)
            .setPositiveButton(R.string.review_card_edit_save, null)
            .setNegativeButton(R.string.cancel, null)
            .apply { if (card.placedInSource) setNeutralButton(R.string.review_card_edit_placement, null) }
            .create()
        cardDialog = dialog
        dialog.setOnDismissListener { if (cardDialog === dialog) cardDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                placementEditor.edit(card)
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val question = editor.reviewCardTextQuestion.text?.toString()?.trim().orEmpty()
                if (question.isEmpty() || question.length > 500) {
                    editor.reviewCardTextQuestionLayout.error = activity.getString(
                        if (question.isEmpty()) R.string.review_card_edit_empty else R.string.review_card_edit_too_long,
                    )
                    return@setOnClickListener
                }
                editor.reviewCardTextQuestionLayout.error = null
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                saveEdit(card, R.string.review_card_saved,
                    operation = { store.updateQuestion(card.id, question) },
                    onSuccess = { dialog.dismiss() },
                    onFailure = { _ ->
                        editor.reviewCardTextQuestionLayout.error = activity.getString(R.string.review_card_text_save_retry)
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dialog.setCancelable(true)
                    },
                )
            }
        }
        dialog.show()
    }

    /**
     * Make project card, Make knowledge card or Follow the Project: a kind event; the card stays on
     * screen with its new mark, and the bar's Undo writes its previous choice. The next card of a
     * Study or Projects session is of that entry's kind again.
     */
    private fun changeKind(card: ReviewCard, kind: String, undo: Boolean = false) {
        if (operationInFlight || editingRegion) return
        val previous = card.ownReviewKind ?: com.gitlab.mudlej.MjPdfReader.data.review.ReviewKinds.FOLLOW_PROJECT
        operationInFlight = true
        updateNavigationActions()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { store.setReviewKind(card.id, kind).map { store.card(card.id) } }
            operationInFlight = false
            result.onSuccess { updated ->
                ThoughtsSyncScheduler.enqueueBackground(activity.applicationContext)
                val shown = currentCard
                if (updated != null && shown?.id == card.id) currentCard = shown.withKindOf(updated)
                currentCard?.let(::showKindMark)
                if (!undo && updated != null) AppSnackbar.make(binding.root, reviewKindChoiceText(kind), Snackbar.LENGTH_LONG).apply {
                    setAction(R.string.review_undo) { currentCard?.takeIf { it.id == card.id }?.let { changeKind(it, previous, undo = true) } }
                }.show()
            }.onFailure { error ->
                AppSnackbar.make(binding.root, error.message.orEmpty(), Snackbar.LENGTH_LONG).show()
            }
            updateNavigationActions()
        }
    }

    /** "Project card · Changed since you last saw it" under the question; nothing on a knowledge card. */
    private fun showKindMark(card: ReviewCard) {
        val mark = reviewKindMark(activity, card)
        binding.reviewKindMark.text = mark
        binding.reviewKindMark.visibility = if (mark == null) View.GONE else View.VISIBLE
        updateIntervalButton()
    }

    /** Retire card: like Delete it leaves the session, but keeps the card and its history; Undo restores it. */
    private fun retire(card: ReviewCard) {
        if (operationInFlight || editingRegion || currentCard?.id != card.id) return
        record(ReviewRating.CUSTOM_INTERVAL, retire = true)
    }

    private fun confirmDelete(card: ReviewCard) {
        if (operationInFlight || editingRegion || currentCard?.id != card.id) return
        val sourceDeletionSupported = true // The client performs and verifies the source edit locally.
        val dialog = createReviewDeleteDialog(activity, card.canDeleteEnclosedText, sourceDeletionSupported) { deleteEnclosedText ->
            if (currentCard?.id == card.id) record(ReviewRating.DELETE, deleteEnclosedText = deleteEnclosedText)
        }
        cardDialog = dialog
        dialog.setOnDismissListener { if (cardDialog === dialog) cardDialog = null }
        dialog.show()
    }

    /** Edit card: its areas on the page, and (behind a toggle, or open with [showText]) its question and note. */
    private fun editRegion(card: ReviewCard, showText: Boolean = false) {
        if (card.wholeDocument) {
            editCardText(card)
            return
        }
        if (card.isHtmlAttachmentCard) {
            // Anchored by its text inside an HTML attachment page: only the question and note change.
            editCardText(card)
            return
        }
        if (!card.hasPdfPlacement) {
            // Made in the HTML view and not linked yet: it has no PDF areas to edit; text only.
            AppSnackbar.make(binding.root, R.string.html_view_card_placed_next_sync, Snackbar.LENGTH_LONG).show()
            editCardText(card)
            return
        }
        // Areas are edited on the PDF's pages.
        if (activity.htmlReaderOrNull?.isActive == true) {
            switchingView = true
            try { activity.htmlReader.showPdf(remember = false) } finally { switchingView = false }
            presenter.clear()
            presenter = pdfPresenter
        }
        // Every area of the card is a box; lines of one selected passage stay one area. A card
        // placed again on a newer PDF starts from its last areas, kept within the pages shown.
        val pages = binding.pdfView.pageCount.coerceAtLeast(1)
        val areas = reviewEditableAreas(card.regions.map(ReviewAreaBox::of).map { it.copy(page = it.page.coerceIn(1, pages)) })
        val boxes = areas.map { area ->
            com.github.barteksc.pdfviewer.PDFView.ReviewRegionSelection(area.box.page - 1, area.box.toRegion().rect)
        }
        val firstIndex = areas.indexOfFirst { it.box.page == card.page }.coerceAtLeast(0)
        if (!binding.pdfView.startReviewRegionSelections(boxes, firstIndex)) return
        areaSession = ReviewAreaEditSession(areas)
        hideKeyboard()
        editingRegion = true
        clearMasks()
        binding.reviewQuestion.setText(R.string.review_edit_card)
        binding.reviewQuestionEdit.setText(card.question)
        binding.reviewQuestionEditLayout.error = null
        showTextFields(showText)
        binding.reviewRelatedContext.visibility = View.VISIBLE
        binding.reviewRevealButton.visibility = View.GONE
        binding.reviewRatingScroll.visibility = View.GONE
        binding.reviewCurrentState.visibility = View.GONE
        beforePanel.hide()
        binding.reviewAreaActions.visibility = View.VISIBLE
        binding.reviewRegionActions.visibility = View.VISIBLE
        updateNavigationActions()
        updateAreaControls()
        // Scroll to the selected area once the taller editing panel is laid out.
        binding.reviewCard.post { if (editingRegion && currentCard?.id == card.id) focusSelectedEditArea() }
    }

    /** The area label, Remove area, the ‹ › buttons and the instructions, for the boxes being edited. */
    private fun updateAreaControls() {
        if (!editingRegion) return
        val boxes = editBoxes()
        val count = boxes.size
        val index = binding.pdfView.selectedReviewRegionIndex.coerceIn(0, (count - 1).coerceAtLeast(0))
        binding.reviewRelatedContext.text = areaProblem ?: activity.getString(when {
            placingAgain -> R.string.review_place_again_instructions
            currentCard?.canEditAnswerBox == false -> R.string.review_edit_card_linked_instructions
            count > 1 -> R.string.review_edit_card_areas_instructions
            else -> R.string.review_edit_card_instructions
        })
        val several = count > 1
        val severalPages = boxes.map { it.page }.distinct().size > 1
        binding.reviewProgress.visibility = if (several || severalPages) View.VISIBLE else View.GONE
        boxes.getOrNull(index)?.let {
            binding.reviewProgress.text = activity.getString(R.string.review_edit_area_position, index + 1, count, it.page)
        }
        binding.reviewRemoveAreaButton.isEnabled = !operationInFlight && areaSession?.canRemove == true && several
        binding.reviewAddAreaButton.isEnabled = !operationInFlight
        binding.reviewPreviousAnswerButton.visibility = if (several) View.VISIBLE else View.GONE
        binding.reviewNextAnswerButton.visibility = if (several) View.VISIBLE else View.GONE
        binding.reviewPreviousAnswerButton.contentDescription = activity.getString(R.string.review_previous_edit_area)
        binding.reviewNextAnswerButton.contentDescription = activity.getString(R.string.review_next_edit_area)
    }

    /** The boxes being edited, 1-based pages; empty if one cannot be measured. */
    private fun editBoxes(): List<ReviewAreaBox> =
        binding.pdfView.reviewRegionSelections.orEmpty().map { selection ->
            val rect = selection.normalizedRect
            ReviewAreaBox(selection.pageIndex + 1, rect.left, rect.top, rect.right, rect.bottom)
        }

    /** A default-size box in the middle of the PDF left visible above the panel, on the page shown there. */
    private fun addArea() {
        val session = areaSession ?: return
        if (!editingRegion || operationInFlight) return
        val index = binding.pdfView.addReviewRegionSelectionAt(binding.pdfView.width / 2f, visibleBottomAboveCard() / 2f)
        if (index < 0) {
            AppSnackbar.make(binding.root, R.string.review_area_not_added, Snackbar.LENGTH_SHORT).show()
            return
        }
        session.added()
        updateAreaControls()
    }

    /** Removes the selected box; the last one stays. */
    private fun removeSelectedArea() {
        val session = areaSession ?: return
        if (!editingRegion || operationInFlight) return
        val index = binding.pdfView.selectedReviewRegionIndex
        if (index < 0 || !session.canRemove) return
        if (binding.pdfView.removeSelectedReviewRegion()) session.remove(index)
        updateAreaControls()
        focusSelectedEditArea()
    }

    /** Selects the previous or next box and scrolls to it, on whichever page it is. */
    private fun focusEditArea(step: Int) {
        val count = editBoxes().size
        if (count < 2 || operationInFlight) return
        val index = Math.floorMod(binding.pdfView.selectedReviewRegionIndex + step, count)
        binding.pdfView.selectReviewRegion(index)
        focusSelectedEditArea()
    }

    private fun focusSelectedEditArea() {
        val box = editBoxes().getOrNull(binding.pdfView.selectedReviewRegionIndex) ?: return
        binding.pdfView.focusOnReviewRegion(box.page - 1, box.toRegion().rect, visibleBottomAboveCard())
        updateAreaControls()
    }

    fun cancelRegionEdit(): Boolean {
        if (!editingRegion) return false
        if (operationInFlight) return true
        finishRegionEdit()
        return true
    }

    private fun finishRegionEdit() {
        hideKeyboard()
        areaProblem = null
        placingAgain = false
        showTextFields(false)
        editingRegion = false
        areaSession = null
        binding.pdfView.clearReviewRegionSelection()
        binding.reviewRegionActions.visibility = View.GONE
        binding.reviewAreaActions.visibility = View.GONE
        binding.reviewQuestion.text = currentCard?.question
        binding.reviewRelatedContext.visibility = View.GONE
        binding.reviewPreviousAnswerButton.contentDescription = activity.getString(R.string.review_previous_answer_area)
        binding.reviewNextAnswerButton.contentDescription = activity.getString(R.string.review_next_answer_area)
        showPagesProgress(currentCard)
        binding.reviewRevealButton.visibility = if (answerRevealed) View.GONE else View.VISIBLE
        binding.reviewRatingScroll.visibility = if (answerRevealed) View.VISIBLE else View.GONE
        updateCurrentState()
        if (currentCard != null && answerDraft.cardId == currentCard?.id) beforePanel.show(answerDraft)
        currentCard?.let { setCardMasks(it) }
        updateNavigationActions()
        setRatingButtonsEnabled(answerRevealed && !operationInFlight)
    }

    private fun saveRegion() {
        val card = currentCard ?: return
        val session = areaSession ?: return
        if (!editingRegion) return
        val boxes = editBoxes()
        val areas = runCatching { session.areas(boxes) }.getOrElse { error ->
            AppSnackbar.make(binding.root, activity.getString(R.string.review_card_action_failed, error.message.orEmpty()),
                Snackbar.LENGTH_LONG).show()
            return
        }
        val regions = areas.flatMap { it.regions }
        val question = binding.reviewQuestionEdit.text?.toString()?.trim().orEmpty()
        if (question.isEmpty() || question.length > 500) {
            showTextFields(true)
            binding.reviewQuestionEditLayout.error = activity.getString(
                if (question.isEmpty()) R.string.review_card_edit_empty else R.string.review_card_edit_too_long,
            )
            return
        }
        val displayedPdf = document.annotationPdfSha256.orEmpty().lowercase()
        binding.reviewQuestionEditLayout.error = null
        hideKeyboard()
        val context = activity.applicationContext
        saveEdit(card, R.string.review_card_saved,
            operation = {
                // On a document built from source a card is always linked: its areas are marked again.
                val filename = "${card.pdfStem}.pdf"
                val sourceAvailable = runCatching {
                    com.gitlab.mudlej.MjPdfReader.data.source.reviewSourceAvailability(
                        OfflineSourceBundleStore(context).librarySourceMetadata(filename)?.pdfs.orEmpty(),
                        filename, displayedPdf, regions.map { it.page }.distinct(),
                    )
                }.getOrNull()
                val saved = if (card.canEditAnswerBox && sourceAvailable != true) {
                    store.updateCardAreas(card.id, card.pdfSha256, card.page, regions, question)
                } else {
                    store.replaceCardAreas(card.id, card.pdfSha256, card.page, displayedPdf, areas, question,
                        replacementSupported = com.gitlab.mudlej.MjPdfReader.data.sync.ReviewSourceCapabilityStore(context).replacementSupported())
                }
                saved
            },
            onSuccess = { finishRegionEdit() },
            onFailure = { error ->
                if (error is ReviewAreaLinkingException) showAreaLinkingFailure(card, error)
            },
        )
    }

    /** An area could not be linked: nothing was saved. Adjust keeps the editor open at that area. */
    private fun showAreaLinkingFailure(card: ReviewCard, error: ReviewAreaLinkingException) {
        if (!editingRegion || currentCard?.id != card.id) return
        areaProblem = error.message + "\n" + activity.getString(R.string.review_area_link_failed_hint)
        updateAreaControls()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.review_area_link_failed_title)
            .setMessage(error.message)
            .setPositiveButton(R.string.review_area_link_adjust) { _, _ ->
                if (editingRegion && currentCard?.id == card.id) {
                    binding.pdfView.selectReviewRegion(error.areaIndex.coerceIn(0, (editBoxes().size - 1).coerceAtLeast(0)))
                    focusSelectedEditArea()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> if (currentCard?.id == card.id) cancelRegionEdit() }
            .create()
        cardDialog?.dismiss()
        cardDialog = dialog
        dialog.setOnDismissListener { if (cardDialog === dialog) cardDialog = null }
        dialog.show()
    }

    private fun saveEdit(
        card: ReviewCard,
        message: Int,
        operation: () -> Result<ReviewCard>,
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ) {
        if (operationInFlight || currentCard?.id != card.id) return
        val requestedUri = document.uri
        val requestedHash = document.annotationPdfSha256
        val token = requestGate.begin()
        operationInFlight = true
        updateNavigationActions()
        setRatingButtonsEnabled(false)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { operation() }
            if (!requestGate.isCurrent(token) || document.uri != requestedUri ||
                document.annotationPdfSha256 != requestedHash || currentCard?.id != card.id) return@launch
            operationInFlight = false
            result.onSuccess { updated ->
                currentCard = updated
                binding.reviewQuestion.text = updated.question
                onSuccess()
                ThoughtsSyncScheduler.enqueueBackground(activity.applicationContext)
                AppSnackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }.onFailure { error ->
                onFailure(error)
                // An area that cannot be linked explains itself in a dialog.
                if (error !is ReviewAreaLinkingException) AppSnackbar.make(binding.root, activity.getString(
                    R.string.review_card_action_failed, error.message.orEmpty(),
                ), Snackbar.LENGTH_LONG).show()
            }
            updateNavigationActions()
            setRatingButtonsEnabled(answerRevealed && !editingRegion)
        }
    }

    private fun updateIntervalButton() {
        val chosen = suggestedInterval
        // A project card comes back within a week, whatever the interval (review-card-kinds section 6.2):
        // the cell shows that time, and the interval chosen is still recorded for its knowledge schedule.
        val capped = currentCard?.isProjectCard == true && reviewIntervalExceedsProjectCap(chosen)
        val interval = if (capped) ReviewInterval(1, ReviewIntervalUnit.WEEKS) else chosen
        binding.reviewIntervalButton.showField(activity.getString(R.string.review_field_interval), when (interval.unit) {
            ReviewIntervalUnit.MINUTES -> activity.getString(R.string.review_action_minutes_short, interval.amountText())
            ReviewIntervalUnit.HOURS -> activity.getString(R.string.review_action_hours_short, interval.amountText())
            else -> interval.label(activity)
        })
        binding.reviewIntervalButton.contentDescription =
            activity.getString(R.string.review_tags_change_interval, if (capped)
                activity.getString(R.string.review_kind_interval_capped, chosen.label(activity)) else interval.label(activity))
    }

    private fun updateTagButton() {
        // Slider steps first ("I get it · Recalled"), then the other tags.
        val shown = com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagScales.summaryTags(
            selectedTags,
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.groups(),
            com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges.mapping(),
        )
        val label = reviewTagCellText(shown, { reviewTagShortLabel(activity, it) }, activity.getString(R.string.review_tags_none)) { text, more ->
            activity.getString(R.string.review_tags_more, text, more)
        }
        binding.reviewTagButton.showField(activity.getString(R.string.review_field_tag), label)
        val labels = selectedTags.joinToString { reviewTagLabel(activity, it) }
        binding.reviewTagButton.contentDescription = labels.ifEmpty { label }
        binding.reviewNextButton.contentDescription = activity.getString(
            R.string.review_tags_record, labels.ifEmpty { label }, suggestedInterval.label(activity))
        binding.reviewNextButton.isEnabled = binding.reviewIntervalButton.isEnabled
    }

    /** Only the tags; the interval has its own cell. */
    private fun showTagSheet() {
        val card = currentCard ?: return
        if (operationInFlight || editingRegion || !answerRevealed || tagSheetOpening) return
        tagSheetOpening = true
        // What "About this…" is about, from the PDF on screen; worked out once per answer.
        val draft = answerDraft
        val contextTargets = if (draft.context == null) runCatching { contextTargets(card) }
            .onFailure { android.util.Log.w(TAG, "Context note targets unavailable for ${card.id}", it) }.getOrNull() else null
        activity.lifecycleScope.launch {
            // Counted once per opening, off the main thread; the bar itself never waits for it. The
            // card's current tags let the chips show the counts this answer would leave.
            val (usage, current) = try {
                withContext(Dispatchers.IO) {
                    runCatching { store.reviewTagUsage() to store.currentReviewState(card.id).first.orEmpty() }
                        .getOrDefault(emptyMap<String, Int>() to emptyList<String>())
                }
            } finally {
                tagSheetOpening = false
            }
            // The notes as every card of the document now shares them.
            val contextCurrent = if (contextTargets == null) null else withContext(Dispatchers.IO) {
                runCatching { store.contextNotes(card) }
                    .onFailure { android.util.Log.w(TAG, "Context notes unavailable for ${card.id}", it) }.getOrNull()
            }
            if (currentCard?.id != card.id || operationInFlight || editingRegion || !answerRevealed) return@launch
            if (contextTargets != null && contextCurrent != null && answerDraft === draft) {
                draft.settleContext(contextTargets, contextTargets.mapValues { (scope, target) ->
                    contextCurrent[scope.wire to target.key].orEmpty()
                })
            }
            val agentLine = reviewRecallPredictionText(activity, recallPredictions[card.id])?.takeIf { answerDraft.beforeLocked }
            val dialog = createReviewTagSheet(
                activity, tagOrder, selectedTags, store.originIdentity(), answerDraft, usage, current, onTagCreated = ::addCreatedTag,
                agentPrediction = agentLine,
            ) { tags ->
                if (currentCard?.id != card.id) return@createReviewTagSheet
                selectedTags = tags
                updateTagButton()
            }
            cardDialog?.dismiss()
            cardDialog = dialog
            dialog.setOnDismissListener { if (cardDialog === dialog) cardDialog = null }
            dialog.show()
        }
    }

    private fun showIntervalPicker() {
        val card = currentCard ?: return
        if (operationInFlight || editingRegion || !answerRevealed) return
        val dialog = createReviewIntervalDialog(activity, suggestedInterval) { interval ->
            if (currentCard?.id != card.id) return@createReviewIntervalDialog
            suggestedInterval = interval
            updateIntervalButton()
            updateTagButton()
        }
        cardDialog?.dismiss()
        cardDialog = dialog
        dialog.setOnDismissListener { if (cardDialog === dialog) cardDialog = null }
        dialog.show()
    }

    /** Skip with an interval and scope (this card, its document or its project). */
    private fun showSkipMenu() {
        val card = currentCard ?: return
        if (operationInFlight || editingRegion) return
        val requestedDocumentUri = document.uri
        activity.lifecycleScope.launch {
            val project = withContext(Dispatchers.IO) {
                reviewProjectMembership()["${card.pdfStem}.pdf"]
            }
            if (currentCard?.id != card.id || document.uri != requestedDocumentUri || operationInFlight || editingRegion) return@launch
            val dialog = createReviewIntervalDialog(activity, SKIP_DEFAULT_INTERVAL,
                ReviewSkipDialogOptions("${card.pdfStem}.pdf", project) { interval, scope, dueOnly ->
                    if (currentCard?.id == card.id && !operationInFlight && !editingRegion)
                        record(ReviewRating.CUSTOM_INTERVAL, interval, scope = scope, dueOnly = dueOnly, skip = true)
                }) {}
            cardDialog?.dismiss()
            cardDialog = dialog
            dialog.setOnDismissListener { if (cardDialog === dialog) cardDialog = null }
            dialog.show()
        }
    }

    /**
     * What each "About this…" scope is about for [card], from the PDF on screen: the section of the
     * card's primary region (its highest region on its first page, as on the desktop) in the PDF
     * outline, that page, and the document. Pdfium gives an outline entry's page but not its height on the page, so an entry
     * on the card's own page is placed at its heading's line when that line is found in the page
     * text ([ReviewContextNotes.headingRange]; one line above it, roughly where a LaTeX destination
     * sits), else at the top of the page.
     */
    private fun contextTargets(card: ReviewCard): Map<ReviewContextNotes.Scope, ReviewContextNotes.Target> {
        if (!card.hasPdfPlacement) {
            // A card made in the HTML view and not linked yet has only its document.
            return mapOf(ReviewContextNotes.Scope.DOCUMENT to
                ReviewContextNotes.Target("", activity.shownDocumentTitle().ifBlank { card.pdfStem }))
        }
        val primary = card.regions.filter { it.page >= 1 }
            .minWithOrNull(compareBy<ReviewAnswerRegion>({ it.page }, { minOf(it.rect.top, it.rect.bottom) }))
            ?: ReviewAnswerRegion(card.page, card.rect)
        val pageIndex = primary.page - 1
        val pdfView = binding.pdfView
        var raw: String? = null
        fun headingTop(title: String): Float? {
            val text = raw ?: pdfView.getPageRawText(primary.page).also { raw = it }
            val range = ReviewContextNotes.headingRange(text, title) ?: return null
            val size = pdfView.getPagePointSize(pageIndex)?.takeIf { it.height > 0 } ?: return null
            val lines = pdfView.getSearchTextBounds(pageIndex, range.first, range.last + 1 - range.first)
            val first = lines.maxByOrNull { maxOf(it.top, it.bottom) } ?: return null
            val headingTop = 1f - maxOf(first.top, first.bottom) / size.height
            val lineHeight = kotlin.math.abs(first.top - first.bottom) / size.height
            return (headingTop - lineHeight).coerceIn(0f, 1f)
        }
        val outline = ReviewContextNotes.withTops(reviewOutline(pdfView.tableOfContents), pageIndex, ::headingTop)
        val section = ReviewContextNotes.sectionOf(outline, pageIndex, minOf(primary.rect.top, primary.rect.bottom))
        return buildMap {
            section?.let { put(ReviewContextNotes.Scope.SECTION, it) }
            put(ReviewContextNotes.Scope.PAGE, ReviewContextNotes.Target(ReviewContextNotes.pageKey(primary.page), "Page ${primary.page}"))
            put(ReviewContextNotes.Scope.DOCUMENT, ReviewContextNotes.Target("", activity.shownDocumentTitle().ifBlank { card.pdfStem }))
        }
    }

    private fun reviewOutline(bookmarks: List<com.shockwave.pdfium.PdfDocument.Bookmark>): List<ReviewContextNotes.OutlineEntry> =
        bookmarks.map { bookmark ->
            ReviewContextNotes.OutlineEntry(bookmark.title.orEmpty(), bookmark.pageIdx.toInt(), children = reviewOutline(bookmark.children))
        }

    private fun addCreatedTag(tag: String) {
        tagPreferences.add(tag)
        if (tag !in tagOrder) tagOrder = tagOrder + tag
        sessionTagOrder = tagOrder
    }

    private fun reviewProjectMembership(): Map<String, String> {
        val projects = LibraryProjectStore(activity).projects()
        return reviewProjectsByFilename(projects)
    }

    private fun record(rating: ReviewRating, interval: ReviewInterval? = null, tags: List<String> = emptyList(),
                       deleteEnclosedText: Boolean = false, scope: ReviewSkipScope = ReviewSkipScope.CARD,
                       dueOnly: Boolean = false, skip: Boolean = false, retire: Boolean = false,
                       groupNotes: Map<String, String> = emptyMap(),
                       contextNotes: Map<ReviewContextNotes.Scope, ReviewContextNotes.Note> = emptyMap(),
                       reviewPhases: ReviewPhases? = null) {
        if (operationInFlight || editingRegion) return
        val card = currentCard ?: return
        hideKeyboard()
        val singleCardSession = isSingleCardSession()
        val requestedDocumentUri = document.uri
        val requestToken = requestGate.begin()
        openRequestJob?.cancel()
        openRequestJob = null
        operationInFlight = true
        updateNavigationActions()
        setRatingButtonsEnabled(false)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (rating == ReviewRating.DELETE) {
                    store.deleteCard(card.id, deleteEnclosedText = deleteEnclosedText)
                        .map { mapOf(card.id to it.nameWithoutExtension) }
                } else if (retire) {
                    // A queue action: no answer is recorded and the schedule is kept.
                    store.retire(card.id).map { mapOf(card.id to it) }
                } else if (skip) runCatching {
                    // A postponement: no answer is recorded, so the card keeps its tags.
                    val now = Instant.now()
                    val projects = if (scope == ReviewSkipScope.PROJECT)
                        reviewProjectMembership() else emptyMap()
                    val batch = store.skip(card.id, scope, checkNotNull(interval).dueAt(now), projects, now, dueOnly).getOrThrow()
                    mapOf(card.id to batch.id)
                } else runCatching {
                    val now = Instant.now()
                    val projects = if (scope == ReviewSkipScope.PROJECT)
                        reviewProjectMembership() else emptyMap()
                    store.recordScoped(card.id, scope, rating, now, interval?.dueAt(now),
                        null, projects, dueOnly, reviewTags = tags, groupNotes = groupNotes, contextNotes = contextNotes,
                        reviewPhases = reviewPhases, reviewEntry = ReviewEntry.of(singleCardSession)).getOrThrow()
                }
            }
            if (!requestGate.isCurrent(requestToken) || document.uri != requestedDocumentUri) {
                return@launch
            }
            result.onFailure { error ->
                operationInFlight = false
                updateNavigationActions()
                setRatingButtonsEnabled(answerRevealed)
                AppSnackbar.make(binding.root, error.message.orEmpty(), Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val ratingEvent = result.getOrThrow()
            // The next card starts without notes, even if this one comes back.
            answerDraft = ReviewAnswerDraft()
            // Its answer is recorded (or the card deleted): the before step kept for it is done with. A
            // skip or a retirement records no answer, so the card comes back with its before step.
            if (!skip && !retire) beforeDrafts.remove(card.id)
            ThoughtsSyncScheduler.enqueueBackground(activity.applicationContext)
            if (rating == ReviewRating.DELETE) history.discardDeletedCard(card.id)
            else if (retire) {
                history.recordRetire(card.id, ratingEvent.getValue(card.id), singleCardSession)
                showRetired(history.previous())
            } else if (skip) history.recordSkip(card.id, ratingEvent.getValue(card.id), singleCardSession)
            else history.recordBatch(card.id, ratingEvent, singleCardSession)
            clearMasks()
            currentCard = null
            if (finishAfterReviewBrowserRating(singleCardSession)) {
                clearReviewRequest()
                activity.finish()
                return@launch
            }
            val queuePdfStem = activity.intent.getStringExtra(HomeActivity.EXTRA_REVIEW_PDF_STEM)
            // A session started from Study or Projects offers only that entry's cards (review-card-kinds section 7).
            val queueKind = activity.intent.getStringExtra(HomeActivity.EXTRA_REVIEW_KIND).takeIf { queuePdfStem == null }
            val queueProject = activity.intent.getStringExtra(HomeActivity.EXTRA_REVIEW_PROJECT_ID).takeIf { queuePdfStem == null }
            val next = withContext(Dispatchers.IO) {
                val index = sourceIndex.snapshot()
                val canonical = if (index.libraryAvailable && index.generationCurrent) index.libraryPdfSha256ByFilename else null
                store.dueCards(pdfStem = queuePdfStem, canonicalPdfSha256ByFilename = canonical, kind = queueKind,
                    projectId = queueProject).firstOrNull()
                    ?.let { card -> card to store.reviewPdfFile(card)?.takeIf { it.isFile } }
            }
            if (!requestGate.isCurrent(requestToken) || document.uri != requestedDocumentUri) {
                return@launch
            }
            operationInFlight = false
            if (next == null) {
                showCompletion()
                return@launch
            }
            openCard(next.first, next.second)
        }
    }

    private fun undoPrevious() {
        if (operationInFlight || editingRegion) return
        val entry = history.previous() ?: return
        hideKeyboard()
        val requestedDocumentUri = document.uri
        val requestToken = requestGate.begin()
        openRequestJob?.cancel()
        openRequestJob = null
        operationInFlight = true
        updateNavigationActions()
        setRatingButtonsEnabled(false)
        activity.lifecycleScope.launch {
            val previous = withContext(Dispatchers.IO) {
                store.card(entry.cardId)?.takeIf { candidate ->
                    candidate.approved && candidate.schedule?.deleted != true && candidate.rects.isNotEmpty()
                }?.let { candidate -> store.reviewPdfFile(candidate)?.takeIf { it.isFile }?.let { candidate to it } }
            }
            val card = previous?.first
            if (!requestGate.isCurrent(requestToken) || document.uri != requestedDocumentUri) {
                return@launch
            }
            if (card == null) {
                operationInFlight = false
                updateNavigationActions()
                if (binding.reviewRatingScroll.visibility == View.VISIBLE) {
                    setRatingButtonsEnabled(true)
                }
                AppSnackbar.make(
                    binding.root,
                    R.string.review_previous_unavailable,
                    Snackbar.LENGTH_LONG,
                ).show()
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                when (entry) {
                    is ReviewSessionHistoryEntry.Rating -> store.retractRatings(entry.ratingEvents)
                    is ReviewSessionHistoryEntry.Skip -> store.undoSkip(entry.batchId)
                    is ReviewSessionHistoryEntry.Retire -> store.undoRetire(entry.retireEventId).map { }
                }
            }
            if (!requestGate.isCurrent(requestToken) || document.uri != requestedDocumentUri) {
                return@launch
            }
            result.onFailure { error ->
                operationInFlight = false
                updateNavigationActions()
                if (binding.reviewRatingScroll.visibility == View.VISIBLE) {
                    setRatingButtonsEnabled(true)
                }
                AppSnackbar.make(binding.root, error.message.orEmpty(), Snackbar.LENGTH_LONG).show()
                return@launch
            }
            history.removePrevious()
            activity.intent.putExtra(ReviewCardReaderContract.EXTRA_SINGLE_CARD_SESSION, entry.singleCardSession)
            clearMasks()
            currentCard = null
            operationInFlight = false
            openCard(card, previous.second)
        }
    }

    /** "Card retired", with Undo while the retire is still the session's last action. */
    private fun showRetired(entry: ReviewSessionHistoryEntry?) {
        AppSnackbar.make(binding.root, R.string.review_card_retired, Snackbar.LENGTH_LONG).apply {
            if (entry is ReviewSessionHistoryEntry.Retire) {
                setAction(R.string.review_undo) { if (history.previous() === entry) undoPrevious() }
                duration = com.gitlab.mudlej.MjPdfReader.ui.review.REVIEW_RETIRE_UNDO_MILLIS
            }
        }.show()
    }

    private fun showCompletion() {
        isActive = true
        reviewHidden = false
        clearMasks()
        currentCard = null
        clearReviewRequest()
        binding.reviewQuestion.setText(R.string.review_session_complete)
        binding.reviewQuestion.visibility = View.VISIBLE
        binding.reviewKindMark.visibility = View.GONE
        beforePanel.hide()
        binding.reviewRelatedContext.visibility = View.GONE
        binding.reviewProgress.visibility = View.GONE
        binding.reviewRevealButton.visibility = View.GONE
        binding.reviewRatingScroll.visibility = View.GONE
        binding.reviewCurrentState.visibility = View.GONE
        binding.reviewDoneButton.visibility = View.VISIBLE
        updateNavigationActions()
        binding.reviewCard.visibility = View.VISIBLE
    }

    private fun clearReviewRequest() {
        reviewMarkupInitialized = false
        pendingPresentation = null
        documentWaitFor = null
        activity.intent.removeExtra(HomeActivity.EXTRA_REVIEW_CARD_ID)
        activity.intent.removeExtra(ReviewCardReaderContract.EXTRA_SINGLE_CARD_SESSION)
    }

    private fun isSingleCardSession(): Boolean = activity.intent.getBooleanExtra(
        ReviewCardReaderContract.EXTRA_SINGLE_CARD_SESSION,
        false,
    )

    private fun toggleDetails() {
        detailsShown = !detailsShown
        cardPreferences.edit().putBoolean(KEY_DETAILS_SHOWN, detailsShown).apply()
        applyDetails()
    }

    /** Compact: the question in two lines, the details folded away. Edit card always shows everything. */
    private fun applyDetails() {
        val shown = detailsShown || editingRegion
        binding.reviewDetails.visibility = if (shown) View.VISIBLE else View.GONE
        binding.reviewCurrentDetails.visibility = if (shown) View.VISIBLE else View.GONE
        binding.reviewQuestion.maxLines = if (shown) Int.MAX_VALUE else COMPACT_QUESTION_LINES
        binding.reviewQuestion.ellipsize = if (shown) null else android.text.TextUtils.TruncateAt.END
        binding.reviewDetailsToggle.visibility = if (editingRegion) View.GONE else View.VISIBLE
        binding.reviewDetailsToggle.setImageResource(if (shown) R.drawable.ic_small_arrow_down else R.drawable.ic_small_arrow_up)
        binding.reviewDetailsToggle.contentDescription =
            activity.getString(if (shown) R.string.review_hide_details else R.string.review_show_details)
    }

    private fun updateNavigationActions() {
        applyDetails()
        binding.reviewOptionsButton.visibility = if ((currentCard != null || history.canUndo) && !editingRegion) View.VISIBLE else View.GONE
        binding.reviewOptionsButton.isEnabled = !operationInFlight
        binding.reviewRegionSaveButton.isEnabled = !operationInFlight
        binding.reviewRegionCancelButton.isEnabled = !operationInFlight
        binding.reviewAddAreaButton.isEnabled = !operationInFlight
        binding.reviewRemoveAreaButton.isEnabled = !operationInFlight && (areaSession?.canRemove == true)
        binding.reviewRevealButton.isEnabled = !operationInFlight
        binding.reviewQuestionEdit.isEnabled = !operationInFlight
        binding.reviewQuestionToggleButton.visibility = if (editingRegion) View.VISIBLE else View.GONE
        binding.reviewQuestionToggleButton.isEnabled = !operationInFlight
        val severalAreas = currentCard != null && answerAreasCardId == currentCard?.id && answerAreaCount() > 1 && !editingRegion
        if (!editingRegion) {
            binding.reviewPreviousAnswerButton.visibility = if (severalAreas) View.VISIBLE else View.GONE
            binding.reviewNextAnswerButton.visibility = if (severalAreas) View.VISIBLE else View.GONE
        }
        binding.reviewDoneButton.isEnabled = !operationInFlight
        agentConversations.update()
        refreshFeedback()
        activity.updateActionBar()
    }

    /**
     * Review card feedback is drawn and counted only for the card under review and only while its
     * answer is visible (Reveal or Set knowledge), never while masked, edited or hidden.
     */
    private fun refreshFeedback() {
        val card = currentCard
        if (card != null && isActive && answerVisible && !editingRegion && !reviewHidden) feedback.show(card) else feedback.hide()
    }

    private fun setRatingButtonsEnabled(enabled: Boolean) {
        binding.reviewTagButton.isEnabled = enabled
        binding.reviewIntervalButton.isEnabled = enabled
        binding.reviewNextButton.isEnabled = enabled
    }

    private fun openCard(card: ReviewCard, pdf: File?) {
        if (pdf == null) {
            binding.reviewCard.visibility = View.GONE
            AppSnackbar.make(binding.root, R.string.review_missing_pdf, Snackbar.LENGTH_LONG).show()
            return
        }
        activity.intent.putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, card.id)
        val uri = Uri.fromFile(pdf)
        if (document.uri == uri) {
            openIfRequested()
        } else {
            activity.displayFromUri(uri, true)
        }
    }
}
