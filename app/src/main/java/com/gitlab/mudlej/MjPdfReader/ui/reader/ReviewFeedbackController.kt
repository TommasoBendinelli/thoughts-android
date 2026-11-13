// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.Dialog
import android.graphics.RectF
import android.text.InputFilter
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Region
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewFeedbackAnchoring
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewVersionStamp
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewVersionStamper
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogReviewFeedbackBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReviewFeedbackActions
import com.gitlab.mudlej.MjPdfReader.ui.review.FeedbackAnswerText
import com.gitlab.mudlej.MjPdfReader.ui.review.FeedbackCardIdentity
import com.gitlab.mudlej.MjPdfReader.ui.review.FeedbackHomeCard
import com.gitlab.mudlej.MjPdfReader.ui.review.FeedbackPlacement
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewAnswerTextLoader
import com.gitlab.mudlej.MjPdfReader.ui.review.feedbackDocumentIds
import com.gitlab.mudlej.MjPdfReader.ui.review.feedbackListOrder
import com.gitlab.mudlej.MjPdfReader.ui.review.feedbackSelectionQualifies
import com.gitlab.mudlej.MjPdfReader.ui.review.placeFeedback
import com.gitlab.mudlej.MjPdfReader.ui.review.placeSharedFeedback
import com.gitlab.mudlej.MjPdfReader.ui.review.sharedFeedbackCandidates
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Review card feedback on the review screen (protocol/review-card-feedback.md §6): the liked and
 * disliked passages of the card under review, drawn only while its answer is visible; Like,
 * Dislike and Comment… on a selection inside the answer; the editor, the card's feedback list and
 * Delete with Undo. [ReviewSessionController] says when the answer is visible ([show]) and when not
 * ([hide]).
 *
 * Feedback is shared across the cards of a document: besides its own records, a card shows the
 * records made on other cards of its document whose passage lies inside its answer, labelled
 * "From another card". Editing or deleting one changes that same record.
 *
 * Change passage (the editor of an own highlight on the PDF) selects its passage with handles; Save
 * passage moves the highlight there with its polarity and comment ([ReviewCardFeedback.replacePassage]).
 */
internal class ReviewFeedbackController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val document: DocumentState,
    private val originId: () -> String,
    /** Off the main thread: a card by id (deleted ones included), or null when unknown locally. */
    private val cardLookup: (String) -> ReviewCard? = { null },
) : ReviewFeedbackActions {
    /** The card whose answer is visible, or null. */
    private var card: ReviewCard? = null
    private var placements: List<FeedbackPlacement> = emptyList()
    private var loaded = false
    private var generation = 0
    private var loadJob: Job? = null
    private var dialog: Dialog? = null
    /** The answer text of the displayed PDF, kept per card, PDF and areas. */
    @Volatile private var answerCache: Pair<String, FeedbackAnswerText?>? = null
    /** Each record's last placement, for the agent text's "(not found in the current answer)". */
    private val lastStates = ConcurrentHashMap<String, ReviewFeedbackAnchoring.State>()

    /** The highlight whose passage is being changed, with the polarity and comment it keeps. */
    private class PassageChange(val cardId: String, val feedback: ReviewCardFeedback.Feedback, val polarity: Polarity, val comment: String)
    private var passageChange: PassageChange? = null

    val isShowing: Boolean get() = card != null

    /** The answer of [card] is visible: draw its feedback (loaded once per showing). */
    fun show(card: ReviewCard) {
        val shown = this.card
        if (shown != null && shown.id == card.id && shown.regions == card.regions && shown.pdfSha256 == card.pdfSha256) {
            this.card = card
            if (loaded) push()
            return
        }
        this.card = card
        reload()
    }

    /** The answer is masked, or no card is under review: nothing is drawn or listed. */
    fun hide() {
        if (passageChange != null) {
            passageChange = null
            binding.pdfView.clearTextSelection()
        }
        if (card == null && placements.isEmpty()) return
        card = null
        generation++
        loadJob?.cancel()
        loadJob = null
        loaded = false
        placements = emptyList()
        binding.pdfView.clearReviewFeedback()
        activity.htmlReaderOrNull?.setFeedback(null, emptyList())
        binding.reviewFeedbackSummary.visibility = View.GONE
        dialog?.dismiss()
        dialog = null
    }

    private fun reload() {
        val card = card ?: return
        val token = ++generation
        loadJob?.cancel()
        val displayed = displayed()
        loadJob = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { placementsFor(card, displayed) }
                    .onFailure { android.util.Log.w(TAG, "Could not load the card's feedback", it) }.getOrDefault(emptyList())
            }
            if (token != generation || this@ReviewFeedbackController.card?.id != card.id) return@launch
            placements = result
            result.filterNot { it.shared }.forEach { lastStates[it.feedback.id] = it.state }
            loaded = true
            push()
        }
    }

    /** The PDF on screen: its SHA-256 and file. Read on the main thread. */
    class Displayed internal constructor(internal val sha256: String, internal val pdf: File?, internal val documentId: String?)

    fun displayed(): Displayed = Displayed(displayedPdfSha256(), displayedPdf(), document.documentId)

    /**
     * Off the main thread: where the card's own feedback and the shared feedback of its document's
     * other cards are drawn on [displayed]. Own records keep their placement (orphaned ones listed);
     * shared ones appear only when inside this card's answer.
     */
    private fun placementsFor(card: ReviewCard, displayed: Displayed): List<FeedbackPlacement> {
        val own = ReviewCardFeedback.forCard(card.id)
        val shared = sharedRecords(card, displayed)
        if (own.isEmpty() && shared.isEmpty()) return emptyList()
        val answer = answerText(card, displayed.sha256, displayed.pdf)
        return placeFeedback(own, displayed.sha256, answer) +
            placeSharedFeedback(shared, displayed.sha256, answer, card.regions.map { it.toFeedbackRegion() })
    }

    /** Off the main thread: the records of other cards of [card]'s document (not yet placed). */
    private fun sharedRecords(card: ReviewCard, displayed: Displayed) = run {
        val homes = HashMap<String, FeedbackHomeCard?>()
        sharedFeedbackCandidates(ReviewCardFeedback.all(),
            FeedbackCardIdentity(card.id, feedbackDocumentIds(displayed.documentId, card.pdfDocumentId, card.documentId), card.pdfStem),
        ) { cardId ->
            homes.getOrPut(cardId) {
                runCatching { cardLookup(cardId) }.getOrNull()?.let { home ->
                    FeedbackHomeCard(home.question, home.schedule?.deleted == true,
                        feedbackDocumentIds(home.pdfDocumentId, home.documentId), home.pdfStem)
                }
            }
        }
    }

    /** Draws the placed highlights (oldest first, so the newest is on top) and the count. */
    private fun push() {
        // The highlight whose passage is being changed gives way to the selection.
        val changing = passageChange?.feedback?.id
        val highlights = placements.filter { it.state != ReviewFeedbackAnchoring.State.ORPHANED && it.regions.isNotEmpty() && it.feedback.id != changing }
            .sortedWith(compareBy({ it.feedback.createdAt }, { it.feedback.id }))
            .map { placement ->
                PDFView.ReviewFeedbackHighlight(
                    placement.feedback.id,
                    placement.feedback.polarity == Polarity.POSITIVE,
                    placement.feedback.comment.isNotEmpty(),
                    placement.regions.map { it.page - 1 },
                    placement.regions.map { region ->
                        RectF().apply {
                            left = region.left.toFloat()
                            top = region.top.toFloat()
                            right = region.right.toFloat()
                            bottom = region.bottom.toFloat()
                        }
                    },
                )
            }
        binding.pdfView.setReviewFeedback(highlights)
        // The HTML view places the same records by their quotes inside the card's answer.
        card?.let { shown -> activity.htmlReaderOrNull?.setFeedback(shown.id, placements.map { it.feedback }) }
        val summary = feedbackSummary(activity, placements)
        binding.reviewFeedbackSummary.text = summary
        binding.reviewFeedbackSummary.visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE
        binding.reviewFeedbackSummary.setOnClickListener { showList() }
    }

    private fun displayedPdfSha256(): String = document.annotationPdfSha256.orEmpty().lowercase()

    private fun displayedPdf(): File? = document.uri?.takeIf { it.scheme == "file" }?.path?.let(::File)

    /** Off the main thread: the card's answer text on the displayed PDF, cached. */
    private fun answerText(card: ReviewCard, displayedSha: String, pdf: File?): FeedbackAnswerText? {
        val regions = card.regions.map { it.toFeedbackRegion() }
        val key = "${card.id}\u0000$displayedSha\u0000${pdf?.path}\u0000$regions"
        answerCache?.takeIf { it.first == key }?.let { return it.second }
        val text = pdf?.let { ReviewAnswerTextLoader(activity.applicationContext).feedbackAnswerText(it, regions) }
        answerCache = key to text
        return text
    }

    // ---- Creating (selection actions) ----------------------------------------------------------

    private fun selectionRegions(request: PDFView.HighlightRequest): List<Region> =
        reviewSelectionRegions(request) { binding.pdfView.getPagePointSize(it) }.map { it.toFeedbackRegion() }

    override fun qualifies(request: PDFView.HighlightRequest): Boolean {
        val card = card ?: return false
        if (request.selectedText.isBlank()) return false
        return feedbackSelectionQualifies(selectionRegions(request), card.regions.map { it.toFeedbackRegion() })
    }

    override fun give(request: PDFView.HighlightRequest, action: ReviewFeedbackActions.Action) {
        val card = card ?: return
        val selection = selectionRegions(request)
        val exact = request.selectedText.trim()
        if (selection.isEmpty() || exact.isEmpty()) return
        val anchor = pdfAnchor(card, selection, exact)
        when (action) {
            ReviewFeedbackActions.Action.LIKE -> save(card) {
                ReviewCardFeedback.create(card.id, Polarity.POSITIVE, "", anchor(), originId(), version = versionOf(card))
            }
            ReviewFeedbackActions.Action.DISLIKE -> save(card) {
                ReviewCardFeedback.create(card.id, Polarity.NEGATIVE, "", anchor(), originId(), version = versionOf(card))
            }
            ReviewFeedbackActions.Action.QUESTION, ReviewFeedbackActions.Action.COMMENT -> showEditor(card, null, exact, anchor)
        }
    }

    /** What a selection on the displayed PDF records about its passage; made off the main thread on save. */
    private fun pdfAnchor(card: ReviewCard, selection: List<Region>, exact: String): suspend () -> ReviewCardFeedback.NewAnchor {
        val displayedSha = displayedPdfSha256()
        val pdf = displayedPdf()
        val documentId = listOfNotNull(document.documentId, card.pdfDocumentId, card.documentId)
            .firstOrNull(ReviewCardFeedback::validUuid4).orEmpty()
        return {
            withContext(Dispatchers.IO) {
                val (prefix, suffix) = answerText(card, displayedSha, pdf)?.context(selection) ?: ("" to "")
                val segment = singleSegment(card)
                ReviewCardFeedback.NewAnchor(exact.take(ReviewCardFeedback.MAX_QUOTE_LENGTH), prefix, suffix, documentId,
                    displayedSha, selection, segment?.first, segment?.second)
            }
        }
    }

    /**
     * Like, Dislike or Comment… on a selection in the revealed answer of the HTML view: anchored by
     * its quote alone (context from the answer, as the viewer gives it), with `view: "html"` and the
     * edition, next to the PDF version stamp.
     */
    fun giveFromHtml(exact: String, prefix: String, suffix: String, htmlEditionSha256: String,
                     action: HtmlReaderController.ReviewFeedbackAction) {
        val card = card ?: return
        val quote = exact.trim()
        if (quote.isEmpty()) return
        val displayedSha = displayedPdfSha256()
        val documentId = listOfNotNull(document.documentId, card.pdfDocumentId, card.documentId)
            .firstOrNull(ReviewCardFeedback::validUuid4).orEmpty()
        val anchor: suspend () -> ReviewCardFeedback.NewAnchor = {
            val segment = singleSegment(card)
            ReviewCardFeedback.NewAnchor(quote.take(ReviewCardFeedback.MAX_QUOTE_LENGTH),
                prefix.takeLast(ReviewCardFeedback.CONTEXT_LENGTH), suffix.take(ReviewCardFeedback.CONTEXT_LENGTH),
                documentId, displayedSha, emptyList(), segment?.first, segment?.second, htmlEditionSha256 = htmlEditionSha256)
        }
        when (action) {
            HtmlReaderController.ReviewFeedbackAction.LIKE -> save(card) {
                ReviewCardFeedback.create(card.id, Polarity.POSITIVE, "", anchor(), originId(), version = versionOf(card))
            }
            HtmlReaderController.ReviewFeedbackAction.DISLIKE -> save(card) {
                ReviewCardFeedback.create(card.id, Polarity.NEGATIVE, "", anchor(), originId(), version = versionOf(card))
            }
            HtmlReaderController.ReviewFeedbackAction.COMMENT -> showEditor(card, null, quote, anchor)
        }
    }

    /** The segment of a source-linked card with exactly one (spec §3); null otherwise. */
    private fun singleSegment(card: ReviewCard): Pair<String, String>? = runCatching {
        val anchor = card.sourceAnchor ?: return null
        if (anchor.get("version")?.asInt != 2) return null
        val segment = anchor.getAsJsonArray("segments")?.singleOrNull()?.asJsonObject ?: return null
        val id = segment.get("segment_id")?.asString?.takeIf { it.length in 1..64 } ?: return null
        val sha = segment.get("content_sha256")?.asString?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        id to sha
    }.getOrNull()

    /** Works out the card's PDF version like its answers do (ReviewStore); cached hashes, called off the main thread. */
    private val versionStamper by lazy { runCatching { ReviewVersionStamper.library() }.getOrNull() }

    /** The version of [card]'s PDF the feedback is given on (spec: review_version), or null. */
    private fun versionOf(card: ReviewCard): ReviewVersionStamp? = versionStamper?.stamp(card)

    /** Runs a store write off the main thread, then redraws the card's feedback and schedules Sync. */
    private fun save(card: ReviewCard, onSaved: (ReviewCardFeedback.Feedback?) -> Unit = {}, write: suspend () -> ReviewCardFeedback.Feedback?) {
        activity.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { write() } }
            result.onSuccess { saved ->
                onSaved(saved)
                if (saved != null) ThoughtsSyncScheduler.enqueueBackground(activity.applicationContext)
            }.onFailure { error ->
                android.util.Log.w(TAG, "Could not save the feedback", error)
                AppSnackbar.make(binding.root, activity.getString(R.string.review_feedback_failed, error.message.orEmpty()),
                    Snackbar.LENGTH_LONG).show()
            }
            if (this@ReviewFeedbackController.card?.id == card.id) reload()
        }
    }

    // ---- Opening, editing, deleting ------------------------------------------------------------

    override fun open(feedbackId: String) {
        val card = card ?: return
        val placement = placements.firstOrNull { it.feedback.id == feedbackId } ?: return
        showEditor(card, placement.feedback, placement.feedback.exact, null, placement.sharedFrom)
    }

    /**
     * The editor for [existing], or for a new record whose anchor [newAnchor] makes once it has text (Like
     * preselected). Closing it saves it; Delete and Change passage are at its foot.
     */
    private fun showEditor(
        card: ReviewCard,
        existing: ReviewCardFeedback.Feedback?,
        quote: String,
        newAnchor: (suspend () -> ReviewCardFeedback.NewAnchor)?,
        /** The home card's question when [existing] was made on another card of the document. */
        sharedFrom: String? = null,
    ) {
        val editor = DialogReviewFeedbackBinding.inflate(activity.layoutInflater)
        editor.reviewFeedbackQuote.text = activity.getString(R.string.review_feedback_quote, quote.replace(Regex("\\s+"), " ").trim())
        if (existing != null && sharedFrom != null) {
            editor.reviewFeedbackSharedFrom.text = feedbackSharedLabel(activity, sharedFrom)
            editor.reviewFeedbackSharedFrom.visibility = View.VISIBLE
        }
        // The chosen polarity is filled in its colour; the other stays outlined (res/color/review_feedback_*_toggle_*).
        editor.reviewFeedbackLike.backgroundTintList =
            ContextCompat.getColorStateList(activity, R.color.review_feedback_positive_toggle_background)
        editor.reviewFeedbackDislike.backgroundTintList =
            ContextCompat.getColorStateList(activity, R.color.review_feedback_negative_toggle_background)
        editor.reviewFeedbackPolarity.check(
            if (existing?.polarity == Polarity.NEGATIVE) R.id.reviewFeedbackDislike else R.id.reviewFeedbackLike)
        editor.reviewFeedbackComment.filters = arrayOf(InputFilter.LengthFilter(ReviewCardFeedback.MAX_COMMENT_LENGTH))
        editor.reviewFeedbackComment.setText(existing?.comment.orEmpty())
        val movable = existing?.let { record -> placements.firstOrNull { it.feedback.id == record.id } }?.takeIf(::canChangePassage)
        editor.reviewFeedbackChangePassage.visibility = if (movable != null) View.VISIBLE else View.GONE
        fun chosenPolarity() = if (editor.reviewFeedbackPolarity.checkedButtonId == R.id.reviewFeedbackDislike) Polarity.NEGATIVE
            else Polarity.POSITIVE
        existing?.let {
            val createdText = formatTime(it.createdAt)
            val editedText = formatTime(it.updatedAt)
            editor.reviewFeedbackDates.text = if (it.updatedAt != it.createdAt && editedText != createdText)
                activity.getString(R.string.review_feedback_created_edited, createdText, editedText)
            else activity.getString(R.string.review_feedback_created, createdText)
            editor.reviewFeedbackDates.visibility = View.VISIBLE
        }
        editor.reviewFeedbackActions.visibility = if (existing != null) View.VISIBLE else View.GONE
        val created = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.review_feedback_title)
            .setView(editor.root)
            .create()
        dialog?.dismiss()
        dialog = created
        fun comment() = editor.reviewFeedbackComment.text?.toString().orEmpty()
        // There is no Save or Cancel: closing the editor saves it, unless Delete or Change passage took over.
        var handled = false
        created.setOnDismissListener {
            if (dialog === created) dialog = null
            if (handled) return@setOnDismissListener
            handled = true
            val polarity = chosenPolarity()
            val text = comment()
            if (existing == null) {
                // A new comment is kept once it has text; closing an empty one saves nothing.
                val anchor = newAnchor ?: return@setOnDismissListener
                if (text.isBlank()) return@setOnDismissListener
                save(card) { ReviewCardFeedback.create(card.id, polarity, text, anchor(), originId(), version = versionOf(card)) }
            } else if (polarity != existing.polarity || ReviewCardFeedback.cleanComment(text) != existing.comment) {
                save(card) { ReviewCardFeedback.edit(existing.id, polarity, text, originId(), version = versionOf(card)) }
            }
        }
        editor.reviewFeedbackDelete.setOnClickListener {
            val record = existing ?: return@setOnClickListener
            handled = true
            created.dismiss()
            delete(card, record)
        }
        // The polarity and comment as they stand in the editor go with the new passage.
        editor.reviewFeedbackChangePassage.setOnClickListener {
            val placement = movable ?: return@setOnClickListener
            handled = true
            created.dismiss()
            startPassageChange(card, placement, chosenPolarity(), comment())
        }
        created.show()
    }

    /** Deletes at once, without confirmation; "Feedback deleted · Undo" for 5 s. */
    private fun delete(card: ReviewCard, feedback: ReviewCardFeedback.Feedback) {
        save(card, onSaved = { tombstone ->
            if (tombstone != null) {
                AppSnackbar.make(binding.root, R.string.review_feedback_deleted, UNDO_DURATION_MS)
                    .setAction(R.string.review_feedback_undo) {
                        save(card) { ReviewCardFeedback.undo(tombstone, originId()) }
                    }
                    .show()
            }
        }) { ReviewCardFeedback.remove(feedback.id, originId()) }
    }

    // ---- Changing a highlight's passage --------------------------------------------------------

    /** An own highlight drawn on the PDF (not the HTML view) can take another passage. */
    private fun canChangePassage(placement: FeedbackPlacement): Boolean = !placement.shared &&
        placement.state != ReviewFeedbackAnchoring.State.ORPHANED && placement.regions.isNotEmpty() &&
        activity.htmlReaderOrNull?.isActive != true

    /** Selects [placement]'s passage with handles; the selection actions become Save passage and Cancel. */
    private fun startPassageChange(card: ReviewCard, placement: FeedbackPlacement, polarity: Polarity, comment: String) {
        if (this.card?.id != card.id) return
        binding.pdfView.clearTextSelection()
        val pages = placement.regions.map { it.page - 1 }
        val rects = placement.regions.map { region ->
            RectF(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat())
        }
        // Opened from the feedback list, the passage may be elsewhere in the document.
        if (!binding.pdfView.isReviewRegionOnScreen(pages.first(), rects.first())) {
            binding.pdfView.focusOnReviewRegion(pages.first(), rects.first(), visibleBottomAboveCard())
        }
        passageChange = PassageChange(card.id, placement.feedback, polarity, comment)
        if (!binding.pdfView.startTextSelectionCovering(pages, rects)) {
            passageChange = null
            AppSnackbar.make(binding.root, R.string.review_feedback_passage_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        push()
        AppSnackbar.make(binding.root, R.string.review_feedback_change_passage_hint, Snackbar.LENGTH_LONG).show()
    }

    override fun isChangingPassage(): Boolean = passageChange != null

    override fun savePassage(request: PDFView.HighlightRequest) {
        val change = passageChange ?: return
        val card = card?.takeIf { it.id == change.cardId } ?: return cancelPassageChange()
        if (!qualifies(request)) {
            AppSnackbar.make(binding.root, R.string.review_feedback_passage_outside, Snackbar.LENGTH_LONG).show()
            return
        }
        val selection = selectionRegions(request)
        val exact = request.selectedText.trim()
        if (selection.isEmpty() || exact.isEmpty()) return
        passageChange = null
        val old = change.feedback
        if (exact == old.exact && displayedPdfSha256() == old.pdfSha256) {
            // The same passage: only an edited polarity or comment is saved.
            save(card) { ReviewCardFeedback.edit(old.id, change.polarity, change.comment, originId(), version = versionOf(card)) }
            return
        }
        val anchor = pdfAnchor(card, selection, exact)
        var replacement: ReviewCardFeedback.Replacement? = null
        save(card, onSaved = {
            val done = replacement ?: return@save
            AppSnackbar.make(binding.root, R.string.review_feedback_passage_changed, UNDO_DURATION_MS)
                .setAction(R.string.review_feedback_undo) {
                    save(card) { ReviewCardFeedback.undoReplacement(done, originId()) }
                }
                .show()
        }) {
            ReviewCardFeedback.replacePassage(old.id, change.polarity, change.comment, anchor(), originId(), version = versionOf(card))
                .also { replacement = it }.created
        }
    }

    override fun cancelPassageChange() {
        if (passageChange == null) return
        passageChange = null
        if (card != null) push()
    }

    /** The bottom of the PDF area left visible by the review card. */
    private fun visibleBottomAboveCard(): Float {
        val pdfLocation = IntArray(2).also(binding.pdfView::getLocationInWindow)
        val cardLocation = IntArray(2).also(binding.reviewCard::getLocationInWindow)
        if (binding.reviewCard.visibility != View.VISIBLE) return binding.pdfView.height.toFloat()
        return (cardLocation[1] - pdfLocation[1] - 12 * activity.resources.displayMetrics.density)
            .coerceIn(1f, binding.pdfView.height.toFloat())
    }

    /** The card's feedback by position in the answer; those not found last, under their heading. */
    private fun showList() {
        val card = card ?: return
        if (placements.isEmpty()) return
        val context = activity
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
        }
        var shownHeading = false
        val selectable = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        lateinit var listDialog: Dialog
        feedbackListOrder(placements).forEach { placement ->
            if (placement.state == ReviewFeedbackAnchoring.State.ORPHANED && !shownHeading) {
                shownHeading = true
                list.addView(TextView(context).apply {
                    setText(R.string.review_feedback_not_found)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                    setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
                })
            }
            val feedback = placement.feedback
            list.addView(TextView(context).apply {
                text = placement.sharedFrom?.let { question ->
                    android.text.SpannableStringBuilder(feedbackListLine(context, feedback)).append('\n').also { line ->
                        val start = line.length
                        line.append(feedbackSharedLabel(context, question))
                        line.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, line.length, 0)
                        line.setSpan(android.text.style.ForegroundColorSpan(secondaryTextColor(context)), start, line.length, 0)
                    }
                } ?: feedbackListLine(context, feedback)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                maxLines = if (placement.shared) 5 else 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(selectable)
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    if (feedback.polarity == Polarity.POSITIVE) R.drawable.ic_feedback_like else R.drawable.ic_feedback_dislike, 0, 0, 0)
                compoundDrawablePadding = (12 * density).toInt()
                compoundDrawablesRelative[0]?.setTint(ContextCompat.getColor(context,
                    if (feedback.polarity == Polarity.POSITIVE) R.color.review_feedback_positive else R.color.review_feedback_negative))
                setOnClickListener {
                    listDialog.dismiss()
                    showEditor(card, feedback, feedback.exact, null, placement.sharedFrom)
                }
            })
        }
        listDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.review_feedback_list_title)
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog?.dismiss()
        dialog = listDialog
        listDialog.setOnDismissListener { if (dialog === listDialog) dialog = null }
        listDialog.show()
    }

    private fun formatTime(value: String): String = runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    }.getOrDefault(value)

    // ---- Copy for agent ------------------------------------------------------------------------

    /**
     * Off the main thread: the card's "Answer feedback:" section for Copy for agent, or "": its own
     * records, plus those of other cards of the document whose passage lies inside its answer on
     * [displayed] (read on the main thread with [displayed]).
     */
    fun agentSection(card: ReviewCard, displayed: Displayed): String {
        val own = ReviewCardFeedback.forCard(card.id).map { feedback ->
            ReviewCardFeedbackLine(feedback.polarity == Polarity.POSITIVE, feedback.exact, feedback.comment, feedback.createdAt,
                orphaned = lastStates[feedback.id] == ReviewFeedbackAnchoring.State.ORPHANED)
        }
        val sharedCandidates = sharedRecords(card, displayed)
        val shared = if (sharedCandidates.isEmpty()) emptyList() else placeSharedFeedback(sharedCandidates, displayed.sha256,
            answerText(card, displayed.sha256, displayed.pdf), card.regions.map { it.toFeedbackRegion() }).map { placement ->
            val feedback = placement.feedback
            ReviewCardFeedbackLine(feedback.polarity == Polarity.POSITIVE, feedback.exact, feedback.comment, feedback.createdAt,
                orphaned = false, sharedFrom = placement.sharedFrom)
        }
        return reviewCardFeedbackSection(own + shared)
    }

    private fun com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion.toFeedbackRegion() =
        Region(page, rect.left.toDouble(), rect.top.toDouble(), rect.right.toDouble(), rect.bottom.toDouble())

    companion object {
        private const val TAG = "ThoughtsCardFeedback"
        private const val UNDO_DURATION_MS = 5_000
    }
}

/** "2 liked · 1 disliked (1 not found)"; empty when the card has no feedback. */
internal fun feedbackSummary(context: android.content.Context, placements: List<FeedbackPlacement>): String {
    if (placements.isEmpty()) return ""
    val liked = placements.count { it.feedback.polarity == Polarity.POSITIVE }
    val disliked = placements.size - liked
    val missing = placements.count { it.state == ReviewFeedbackAnchoring.State.ORPHANED }
    val counts = listOfNotNull(
        context.getString(R.string.review_feedback_liked_count, liked).takeIf { liked > 0 },
        context.getString(R.string.review_feedback_disliked_count, disliked).takeIf { disliked > 0 },
    ).joinToString(" · ")
    return if (missing > 0) "$counts ${context.getString(R.string.review_feedback_not_found_count, missing)}" else counts
}

/** "From another card · “question”" (or just "From another card" when it has no question). */
private fun feedbackSharedLabel(context: android.content.Context, question: String): String {
    val text = question.replace(Regex("\\s+"), " ").trim()
    return if (text.isEmpty()) context.getString(R.string.review_feedback_from_another_card)
    else context.getString(R.string.review_feedback_from_another_card_question, text)
}

private fun secondaryTextColor(context: android.content.Context): Int = com.google.android.material.color.MaterialColors.getColor(
    context, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.GRAY)

/** "Liked · “quote” — comment" for the card's feedback list. */
private fun feedbackListLine(context: android.content.Context, feedback: ReviewCardFeedback.Feedback): String {
    val label = context.getString(if (feedback.polarity == Polarity.POSITIVE) R.string.review_feedback_liked else R.string.review_feedback_disliked)
    val quote = context.getString(R.string.review_feedback_quote, feedback.exact.replace(Regex("\\s+"), " ").trim())
    val comment = feedback.comment.replace(Regex("\\s+"), " ").trim()
    return if (comment.isEmpty()) "$label · $quote" else "$label · $quote — $comment"
}
