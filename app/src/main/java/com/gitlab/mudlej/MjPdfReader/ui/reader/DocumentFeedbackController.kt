// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.Dialog
import android.graphics.RectF
import android.text.InputFilter
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Feedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Kind
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Region
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewFeedbackAnchoring
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewVersionStamp
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewVersionStamper
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.DialogReviewFeedbackBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.ReviewFeedbackActions
import com.gitlab.mudlej.MjPdfReader.ui.review.DOCUMENT_FEEDBACK_PAGE_WINDOW
import com.gitlab.mudlej.MjPdfReader.ui.review.FeedbackAnswerText
import com.gitlab.mudlej.MjPdfReader.ui.review.FeedbackPlacement
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewAnswerTextLoader
import com.gitlab.mudlej.MjPdfReader.ui.review.placeDocumentFeedback
import com.gitlab.mudlej.MjPdfReader.ui.review.wholePages
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Document feedback in normal reading (thoughts-server protocol/document-feedback.md §5, §9): Like,
 * Dislike, Question… and Comment… on any text selection of a Library PDF with a known document id, saved as
 * `document_feedback` records tied to the document and the exact PDF that was displayed; the
 * liked and disliked passages drawn on every page; a tap on one opens the editor (Save, Delete
 * with Undo). Card feedback ([ReviewFeedbackController]) takes precedence on a selection that
 * qualifies for it, and during a review [PDFView] does not draw these highlights in the card's answer.
 *
 * A like or a dislike shows on the PDF it was made on, a question or another comment until it is
 * resolved; the rest is offered as "N from earlier versions" (§9). Records made on this PDF are drawn
 * from their own regions. Records made on another version are found again by their quote (spec §4) on demand: the page text is read off the main thread, only
 * for records whose anchor lies near the page being read, and each result is kept for the
 * displayed PDF.
 *
 * A drawing from the Mac (§10) is drawn as its strokes and a text box (§11) as its text, only on the
 * PDF it was made on, and neither is ever relocated; a tap shows one without the kinds or anything
 * to change.
 *
 * Change passage (the editor of a highlight drawn on this PDF) selects its passage with handles; Save
 * passage moves the highlight there with its kind and comment ([DocumentFeedback.replacePassage]).
 */
internal class DocumentFeedbackController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val document: () -> DocumentState,
    private val originId: () -> String,
    /**
     * An earlier version opened from History: its Library document and its own PDF version, since
     * its cached file is not the Library's. Its feedback, and its cards' answer feedback (read
     * only), are drawn from their regions; nothing from other versions is offered.
     */
    private val historyVersion: () -> Pair<String, String>? = { null },
) : ReviewFeedbackActions {
    /** The displayed document's records in effect (not deleted), oldest first. */
    private var records: List<Feedback> = emptyList()
    /** Where each record is drawn on the displayed PDF, by feedback id (the anchor never changes). */
    private val placements = HashMap<String, FeedbackPlacement>()
    /** The reader asked for what section 9 hides, for this PDF. */
    private var showEarlier = false
    private var offeredEarlierFor: String? = null
    /** The PDF the placements belong to: document id, SHA-256 and path. */
    private var placementsFor: String? = null
    private var generation = 0
    private var loadJob: Job? = null
    private var relocateJob: Job? = null
    private var dialog: Dialog? = null
    private var currentPage = 0

    /** The highlight whose passage is being changed, with the polarity, kind and comment it keeps. */
    private class PassageChange(val feedback: Feedback, val polarity: Polarity, val kind: Kind?, val comment: String)
    private var passageChange: PassageChange? = null

    /** A document finished loading, a Sync finished, or a record changed: read the records again. */
    fun refresh() {
        val displayed = displayed()
        if (displayed == null) {
            clear()
            return
        }
        if (placementsFor != displayed.key) {
            placements.clear()
            placementsFor = displayed.key
            showEarlier = false
        }
        val token = ++generation
        loadJob?.cancel()
        relocateJob?.cancel()
        relocateJob = null
        loadJob = activity.lifecycleScope.launch {
            val inHistory = historyVersion() != null
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    DocumentFeedback.forDocument(displayed.documentId) + if (!inHistory) emptyList() else
                        ReviewCardFeedback.all().filter { record ->
                            !record.deleted && record.cardId.isNotEmpty() &&
                                record.pdfDocumentId.equals(displayed.documentId, ignoreCase = true) &&
                                record.pdfSha256.equals(displayed.sha256, ignoreCase = true)
                        }
                }
                    .onFailure { android.util.Log.w(TAG, "Could not read the document's feedback", it) }
                    .getOrDefault(emptyList())
            }
            if (token != generation) return@launch
            records = loaded
            // Same PDF: the stored regions as they are (spec §4.1), no text needed.
            loaded.filter { it.id !in placements && it.pdfSha256.equals(displayed.sha256, ignoreCase = true) }.forEach {
                placements[it.id] = placeDocumentFeedback(it, displayed.sha256, Int.MAX_VALUE) { null }
            }
            push()
            relocateNearCurrentPage()
            offerEarlier(displayed)
        }
    }

    /** What shows on [displayed]: everything once the reader asked for the earlier ones. */
    private fun shown(displayed: Displayed): List<Feedback> = records.filter {
        DocumentFeedback.shownOn(it, displayed.sha256) || (showEarlier && DocumentFeedback.offeredAsEarlier(it, displayed.sha256))
    }

    /** Offers what section 9 hides, once per PDF opened. */
    private fun offerEarlier(displayed: Displayed) {
        // An earlier version opened from History shows its own feedback only.
        if (offeredEarlierFor == displayed.key || showEarlier || historyVersion() != null) return
        val hidden = records.count { DocumentFeedback.offeredAsEarlier(it, displayed.sha256) }
        if (hidden == 0) return
        offeredEarlierFor = displayed.key
        AppSnackbar.make(binding.root, activity.resources.getQuantityString(R.plurals.review_feedback_earlier, hidden, hidden), Snackbar.LENGTH_LONG)
            .setAction(R.string.review_feedback_show) {
                showEarlier = true
                push()
                relocateNearCurrentPage()
            }
            .show()
    }

    fun onDocumentLoaded() {
        currentPage = document().pageNumber
        refresh()
    }

    /** The reader moved to [pageIndex] (zero-based): find nearby records made on another PDF version. */
    fun onPageChanged(pageIndex: Int) {
        currentPage = pageIndex
        relocateNearCurrentPage()
    }

    /** The document is being replaced: nothing of it is drawn or kept. */
    fun onDocumentReset() = clear()

    private fun clear() {
        if (passageChange != null) {
            passageChange = null
            binding.pdfView.clearTextSelection()
        }
        generation++
        loadJob?.cancel()
        relocateJob?.cancel()
        loadJob = null
        relocateJob = null
        records = emptyList()
        placements.clear()
        placementsFor = null
        binding.pdfView.clearDocumentFeedback()
        dialog?.dismiss()
        dialog = null
    }

    /**
     * Off the main thread, for records made on another PDF version whose anchor lies within a few
     * pages of the page being read: searches their quote (spec §4.2), then redraws.
     */
    private fun relocateNearCurrentPage() {
        if (relocateJob?.isActive == true) return
        val displayed = displayed() ?: return
        if (placementsFor != displayed.key) return
        val page = currentPage + 1
        val reach = DOCUMENT_FEEDBACK_PAGE_WINDOW + RELOCATE_LOOKAHEAD
        val pending = shown(displayed).filter { record ->
            record.id !in placements && record.regions.any { kotlin.math.abs(it.page - page) <= reach }
        }
        if (pending.isEmpty()) return
        val pdf = displayed.pdf
        val token = generation
        relocateJob = activity.lifecycleScope.launch {
            val placed = withContext(Dispatchers.IO) {
                runCatching {
                    if (pdf == null) return@runCatching pending.associate { it.id to orphaned(it) }
                    ReviewAnswerTextLoader(activity.applicationContext).withPages(pdf) { pageCount, pageText ->
                        pending.associate { record ->
                            record.id to placeDocumentFeedback(record, displayed.sha256, pageCount) { pages ->
                                FeedbackAnswerText.build(wholePages(pages), pageText)
                            }
                        }
                    } ?: pending.associate { it.id to orphaned(it) }
                }.onFailure { android.util.Log.w(TAG, "Could not find the document's feedback", it) }
                    .getOrDefault(pending.associate { it.id to orphaned(it) })
            }
            if (token != generation || placementsFor != displayed.key) return@launch
            placements.putAll(placed)
            push()
            // The reader may have moved on meanwhile.
            relocateJob = null
            relocateNearCurrentPage()
        }
    }

    private fun orphaned(record: Feedback) = FeedbackPlacement(record, ReviewFeedbackAnchoring.State.ORPHANED, emptyList(), null)

    /** Draws the placed records, oldest first so the newest is on top; orphaned ones are not drawn (spec §5.2). */
    private fun push() {
        val displayed = displayed() ?: return
        // The highlight whose passage is being changed gives way to the selection.
        val changing = passageChange?.feedback?.id
        val highlights = shown(displayed).filter { it.id != changing }.mapNotNull { record ->
            val placement = placements[record.id]
                ?.takeIf { it.state != ReviewFeedbackAnchoring.State.ORPHANED && it.regions.isNotEmpty() } ?: return@mapNotNull null
            // The strokes say where a drawing is; its region only names the page.
            if (record.isDrawing) {
                return@mapNotNull PDFView.ReviewFeedbackHighlight(record.id, record.comment.isNotEmpty(), placement.regions.first().page - 1, record.strokes)
            }
            // A text box's region only names the page too: its text box says where the text is.
            if (record.isTextBox) {
                val box = record.textBox ?: return@mapNotNull null
                return@mapNotNull PDFView.ReviewFeedbackHighlight(record.id, placement.regions.first().page - 1,
                    RectF(box.left, box.top, box.right, box.bottom), record.comment, box.fontSize)
            }
            PDFView.ReviewFeedbackHighlight(
                record.id,
                record.polarity == Polarity.POSITIVE,
                record.kind != null,
                record.comment.isNotEmpty(),
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
        if (highlights.isEmpty()) binding.pdfView.clearDocumentFeedback() else binding.pdfView.setDocumentFeedback(highlights)
    }

    /** The displayed Library PDF: its permanent document id, SHA-256 and file (the bytes that SHA-256 names). */
    private class Displayed(val documentId: String, val sha256: String, val pdf: File?, val name: String) {
        val key: String get() = "$documentId\u0000$sha256\u0000${pdf?.path}"
    }

    private fun displayed(): Displayed? {
        val state = document()
        // A project read as one PDF numbers its pages across documents; v1 anchors one document's PDF.
        if (state.projectReading != null) return null
        val version = historyVersion()
        val documentId = (state.documentId ?: version?.first)?.lowercase()?.takeIf(ReviewCardFeedback::validUuid4) ?: return null
        val sha = (version?.second ?: state.annotationPdfSha256)?.lowercase()?.takeIf { it.matches(SHA256) && it != UNKNOWN_SHA256 } ?: return null
        // The PDF whose bytes annotationPdfSha256 names (DocumentLoader).
        val pdf = (state.renderUri ?: state.uri)?.takeIf { it.scheme == "file" }?.path?.let(::File)
        return Displayed(documentId, sha, pdf, state.name)
    }

    // ---- Creating (selection actions) ----------------------------------------------------------

    private fun selectionRegions(request: PDFView.HighlightRequest): List<Region> =
        reviewSelectionRegions(request) { binding.pdfView.getPagePointSize(it) }.map {
            Region(it.page, it.rect.left.toDouble(), it.rect.top.toDouble(), it.rect.right.toDouble(), it.rect.bottom.toDouble())
        }

    override val offersQuestion: Boolean get() = true

    /** Any non-empty selection of a Library PDF with a known document id and PDF version (spec §5.1). */
    override fun qualifies(request: PDFView.HighlightRequest): Boolean {
        if (displayed() == null || request.selectedText.isBlank()) return false
        val regions = selectionRegions(request)
        return regions.isNotEmpty() && regions.size <= 200
    }

    override fun give(request: PDFView.HighlightRequest, action: ReviewFeedbackActions.Action) {
        val displayed = displayed() ?: return
        val selection = selectionRegions(request)
        val exact = request.selectedText.trim()
        if (selection.isEmpty() || exact.isEmpty()) return
        val anchor = anchorFor(displayed, selection, exact)
        when (action) {
            ReviewFeedbackActions.Action.LIKE -> save {
                DocumentFeedback.create(Polarity.POSITIVE, "", anchor(), originId(), version = versionOf(displayed))
            }
            ReviewFeedbackActions.Action.DISLIKE -> save {
                DocumentFeedback.create(Polarity.NEGATIVE, "", anchor(), originId(), version = versionOf(displayed))
            }
            ReviewFeedbackActions.Action.QUESTION, ReviewFeedbackActions.Action.COMMENT -> {
                val kind = if (action == ReviewFeedbackActions.Action.QUESTION) Kind.QUESTION else Kind.OTHER
                showEditor(null, exact, kind) { polarity, chosen, comment ->
                    save { DocumentFeedback.create(polarity, comment, anchor(), originId(), version = versionOf(displayed), kind = chosen) }
                }
            }
        }
    }

    /** What a selection on [displayed] records about its passage; made off the main thread on save. */
    private fun anchorFor(displayed: Displayed, selection: List<Region>, exact: String): suspend () -> ReviewCardFeedback.NewAnchor = {
        withContext(Dispatchers.IO) {
            // The quote's context comes from the text of the selected pages (card feedback §3, page text for §4).
            val (prefix, suffix) = displayed.pdf?.let { pdf ->
                runCatching {
                    ReviewAnswerTextLoader(activity.applicationContext).withPages(pdf) { _, pageText ->
                        FeedbackAnswerText.build(wholePages(selection.map { it.page }.distinct().sorted()), pageText).context(selection)
                    }
                }.getOrNull()
            } ?: ("" to "")
            ReviewCardFeedback.NewAnchor(exact.take(ReviewCardFeedback.MAX_QUOTE_LENGTH), prefix, suffix,
                displayed.documentId, displayed.sha256, selection)
        }
    }

    private val versionStamper by lazy { runCatching { ReviewVersionStamper.library() }.getOrNull() }

    /** The version of the displayed PDF (review_version), off the main thread; null when unknown. */
    private fun versionOf(displayed: Displayed): ReviewVersionStamp? =
        versionStamper?.stampDocument(displayed.documentId, displayed.name.removeSuffix(".pdf"), viewedSha256 = displayed.sha256)

    /** Runs a store write off the main thread, then redraws and schedules Sync (which waits for server support). */
    private fun save(onSaved: (Feedback?) -> Unit = {}, write: suspend () -> Feedback?) {
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
            refresh()
        }
    }

    // ---- Opening, editing, deleting ------------------------------------------------------------

    override fun open(feedbackId: String) {
        val record = records.firstOrNull { it.id == feedbackId } ?: return
        if (record.isPageMark) {
            showPageMark(record)
            return
        }
        // A card's answer feedback shown in an earlier version: read only, it is changed during a review.
        if (record.cardId.isNotEmpty()) {
            showCardAnswerFeedback(record)
            return
        }
        val placement = placements[record.id]?.takeIf { it.state != ReviewFeedbackAnchoring.State.ORPHANED && it.regions.isNotEmpty() }
        val changePassage: ((Polarity, Kind?, String) -> Unit)? = placement?.let { placed ->
            { polarity, kind, comment -> startPassageChange(record, placed, polarity, kind, comment) }
        }
        showEditor(record, record.exact, record.kind, changePassage) { polarity, kind, comment ->
            // Only a changed polarity, kind or comment writes a new revision.
            val displayed = displayed()
            save { DocumentFeedback.edit(record.id, polarity, comment, originId(), version = displayed?.let(::versionOf), kind = kind) }
        }
    }

    /** A card's answer feedback in an earlier version: what it says, read only. */
    private fun showCardAnswerFeedback(record: Feedback) {
        val label = activity.getString(if (record.polarity == Polarity.NEGATIVE) R.string.review_feedback_disliked else R.string.review_feedback_liked)
        val quote = activity.getString(R.string.review_feedback_quote, record.exact.replace(Regex("\\s+"), " ").trim())
        val created = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.document_history_card_feedback)
            .setMessage(listOf("$label · $quote", record.comment, dates(record)).filter(String::isNotEmpty).joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog?.dismiss()
        dialog = created
        created.setOnDismissListener { if (dialog === created) dialog = null }
        created.show()
    }

    /** A drawing or a text box, read-only: its comment (a text box's text) and dates. It is changed on the Mac. */
    private fun showPageMark(record: Feedback) {
        val created = MaterialAlertDialogBuilder(activity)
            .setTitle(if (record.isTextBox) R.string.review_feedback_text_box else R.string.review_feedback_drawing)
            .setMessage(listOf(record.comment, dates(record)).filter(String::isNotEmpty).joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog?.dismiss()
        dialog = created
        created.setOnDismissListener { if (dialog === created) dialog = null }
        created.show()
    }

    /**
     * The RCF §6.3 editor with the four kinds of §9, for [existing], or for a new record saved by [onSave]
     * once it has text. Closing it saves it; Delete and Change passage are at its foot.
     */
    private fun showEditor(
        existing: Feedback?,
        quote: String,
        kind: Kind?,
        /** Change passage, with the choices as they stand in the editor; null hides it. */
        onChangePassage: ((Polarity, Kind?, String) -> Unit)? = null,
        onSave: (Polarity, Kind?, String) -> Unit,
    ) {
        val editor = DialogReviewFeedbackBinding.inflate(activity.layoutInflater)
        editor.reviewFeedbackQuote.text = activity.getString(R.string.review_feedback_quote, quote.replace(Regex("\\s+"), " ").trim())
        editor.reviewFeedbackLike.backgroundTintList =
            ContextCompat.getColorStateList(activity, R.color.review_feedback_positive_toggle_background)
        editor.reviewFeedbackDislike.backgroundTintList =
            ContextCompat.getColorStateList(activity, R.color.review_feedback_negative_toggle_background)
        // Four kinds fill the row: they share it, without icons.
        val choices = listOf(editor.reviewFeedbackLike, editor.reviewFeedbackDislike, editor.reviewFeedbackQuestion, editor.reviewFeedbackOther)
        editor.reviewFeedbackPolarity.layoutParams = editor.reviewFeedbackPolarity.layoutParams.apply { width = android.view.ViewGroup.LayoutParams.MATCH_PARENT }
        choices.forEach { button ->
            button.visibility = View.VISIBLE
            button.icon = null
            button.setPadding(0, button.paddingTop, 0, button.paddingBottom)
            button.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // "Question" and "Comment" fit their quarter of the row: the label shrinks rather than being cut.
            button.maxLines = 1
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                button, 10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        editor.reviewFeedbackPolarity.check(when {
            kind == Kind.QUESTION -> R.id.reviewFeedbackQuestion
            kind == Kind.OTHER -> R.id.reviewFeedbackOther
            existing?.polarity == Polarity.NEGATIVE -> R.id.reviewFeedbackDislike
            else -> R.id.reviewFeedbackLike
        })
        editor.reviewFeedbackCommentLayout.hint = activity.getString(R.string.review_feedback_comment_hint_any)
        if (existing?.kind != null) {
            editor.reviewFeedbackResolve.visibility = View.VISIBLE
            editor.reviewFeedbackResolve.setText(if (existing.resolvedAt == null) R.string.review_feedback_resolve else R.string.review_feedback_reopen)
        }
        editor.reviewFeedbackComment.filters = arrayOf(InputFilter.LengthFilter(ReviewCardFeedback.MAX_COMMENT_LENGTH))
        editor.reviewFeedbackComment.setText(existing?.comment.orEmpty())
        editor.reviewFeedbackChangePassage.visibility = if (onChangePassage != null) View.VISIBLE else View.GONE
        existing?.let {
            editor.reviewFeedbackDates.text = dates(it)
            editor.reviewFeedbackDates.visibility = View.VISIBLE
        }
        editor.reviewFeedbackActions.visibility = if (existing != null) View.VISIBLE else View.GONE
        val created = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.review_feedback_title)
            .setView(editor.root)
            .create()
        dialog?.dismiss()
        dialog = created
        /** The polarity, kind and comment chosen in the editor; null (with the reason shown unless [quiet]) when not valid. */
        fun choices(quiet: Boolean = false): Triple<Polarity, Kind?, String>? {
            val checked = editor.reviewFeedbackPolarity.checkedButtonId
            val chosen = when (checked) {
                R.id.reviewFeedbackQuestion -> Kind.QUESTION
                R.id.reviewFeedbackOther -> Kind.OTHER
                else -> null
            }
            val polarity = when {
                chosen != null -> Polarity.NEUTRAL
                checked == R.id.reviewFeedbackDislike -> Polarity.NEGATIVE
                else -> Polarity.POSITIVE
            }
            val comment = editor.reviewFeedbackComment.text?.toString().orEmpty()
            // A question or a comment is its text.
            if (chosen != null && comment.isBlank()) {
                if (!quiet) editor.reviewFeedbackCommentLayout.error = activity.getString(R.string.review_feedback_needs_text)
                return null
            }
            return Triple(polarity, chosen, comment)
        }
        // There is no Save or Cancel: closing the editor saves it, unless Delete, Resolve or Change passage took over.
        var handled = false
        created.setOnDismissListener {
            if (dialog === created) dialog = null
            if (handled) return@setOnDismissListener
            handled = true
            // A new comment is kept once it has text; closing an empty one saves nothing.
            if (existing == null && editor.reviewFeedbackComment.text.isNullOrBlank()) return@setOnDismissListener
            val (polarity, chosen, comment) = choices(quiet = true) ?: run {
                AppSnackbar.make(binding.root, R.string.review_feedback_not_saved_needs_text, Snackbar.LENGTH_LONG).show()
                return@setOnDismissListener
            }
            if (existing != null && polarity == existing.polarity && chosen == existing.kind &&
                ReviewCardFeedback.cleanComment(comment) == existing.comment) return@setOnDismissListener
            onSave(polarity, chosen, comment)
        }
        editor.reviewFeedbackChangePassage.setOnClickListener {
            val (polarity, chosen, comment) = choices() ?: return@setOnClickListener
            handled = true
            created.dismiss()
            onChangePassage?.invoke(polarity, chosen, comment)
        }
        // Resolve (or Reopen) keeps the editor's changes, then closes the question or comment.
        editor.reviewFeedbackResolve.setOnClickListener {
            val record = existing ?: return@setOnClickListener
            val (polarity, chosen, comment) = choices() ?: return@setOnClickListener
            handled = true
            created.dismiss()
            val displayed = displayed()
            save {
                val edited = DocumentFeedback.edit(record.id, polarity, comment, originId(), version = displayed?.let(::versionOf), kind = chosen)
                if (chosen != null) DocumentFeedback.resolve(record.id, record.resolvedAt == null, originId()) ?: edited else edited
            }
        }
        editor.reviewFeedbackDelete.setOnClickListener {
            val record = existing ?: return@setOnClickListener
            handled = true
            created.dismiss()
            delete(record)
        }
        created.show()
    }

    // ---- Changing a highlight's passage --------------------------------------------------------

    /** Selects [placement]'s passage with handles; the selection actions become Save passage and Cancel. */
    private fun startPassageChange(record: Feedback, placement: FeedbackPlacement, polarity: Polarity, kind: Kind?, comment: String) {
        if (displayed() == null) return
        binding.pdfView.clearTextSelection()
        val pages = placement.regions.map { it.page - 1 }
        val rects = placement.regions.map { region ->
            RectF(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat())
        }
        if (!binding.pdfView.isReviewRegionOnScreen(pages.first(), rects.first())) {
            binding.pdfView.focusOnReviewRegion(pages.first(), rects.first(), binding.pdfView.height.toFloat())
        }
        passageChange = PassageChange(record, polarity, kind, comment)
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
        val displayed = displayed() ?: return cancelPassageChange()
        if (!qualifies(request)) {
            AppSnackbar.make(binding.root, R.string.review_feedback_passage_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        val selection = selectionRegions(request)
        val exact = request.selectedText.trim()
        if (selection.isEmpty() || exact.isEmpty()) return
        passageChange = null
        val old = change.feedback
        if (exact == old.exact && displayed.sha256.equals(old.pdfSha256, ignoreCase = true)) {
            // The same passage: only an edited polarity, kind or comment is saved.
            save { DocumentFeedback.edit(old.id, change.polarity, change.comment, originId(), version = versionOf(displayed), kind = change.kind) }
            return
        }
        val anchor = anchorFor(displayed, selection, exact)
        var replacement: ReviewCardFeedback.Replacement? = null
        save(onSaved = {
            val done = replacement ?: return@save
            AppSnackbar.make(binding.root, R.string.review_feedback_passage_changed, UNDO_DURATION_MS)
                .setAction(R.string.review_feedback_undo) { save { DocumentFeedback.undoReplacement(done, originId()) } }
                .show()
        }) {
            DocumentFeedback.replacePassage(old.id, change.polarity, change.comment, anchor(), originId(),
                version = versionOf(displayed), kind = change.kind).also { replacement = it }.created
        }
    }

    override fun cancelPassageChange() {
        if (passageChange == null) return
        passageChange = null
        push()
    }

    /** Deletes at once, without confirmation; "Feedback deleted · Undo" for 5 s. */
    private fun delete(feedback: Feedback) {
        save(onSaved = { tombstone ->
            if (tombstone != null) {
                AppSnackbar.make(binding.root, R.string.review_feedback_deleted, UNDO_DURATION_MS)
                    .setAction(R.string.review_feedback_undo) { save { DocumentFeedback.undo(tombstone, originId()) } }
                    .show()
            }
        }) { DocumentFeedback.remove(feedback.id, originId()) }
    }

    private fun dates(record: Feedback): String {
        val createdText = formatTime(record.createdAt)
        val editedText = formatTime(record.updatedAt)
        return if (record.updatedAt != record.createdAt && editedText != createdText)
            activity.getString(R.string.review_feedback_created_edited, createdText, editedText)
        else activity.getString(R.string.review_feedback_created, createdText)
    }

    private fun formatTime(value: String): String = runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    }.getOrDefault(value)

    companion object {
        private const val TAG = "ThoughtsDocumentFeedback"
        private const val UNDO_DURATION_MS = 5_000
        /** Pages beyond the search window whose records are found ahead of the reader. */
        private const val RELOCATE_LOOKAHEAD = 2
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val UNKNOWN_SHA256 = "0".repeat(64)
    }
}
