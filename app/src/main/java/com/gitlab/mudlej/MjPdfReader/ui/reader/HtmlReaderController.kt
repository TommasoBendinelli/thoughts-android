// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.browserLinkIntent
import com.gitlab.mudlej.MjPdfReader.core.io.pdfDateNow
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.copyToClipboard
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAuthor
import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAvailability
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionDownloadPolicy
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionManifest
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionStore
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionSync
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorConfig
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorDialog
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorValue
import com.gitlab.mudlej.MjPdfReader.ui.html.HtmlEditionView
import com.gitlab.mudlej.MjPdfReader.ui.links.EXTRA_REMOTE_PDF_LINK
import com.gitlab.mudlej.MjPdfReader.ui.links.installedRemotePdf
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardDetailsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The reader's HTML view (thoughts-html-view-SPEC.md, phase 3): a [HtmlEditionView] stacked over
 * the PDF view, for documents with an HTML edition. The PDF stays loaded underneath, so the PDF's
 * identity, cards, comments and review state are the same in both views.
 *
 * - PDF | HTML is chosen in the reader menu and remembered per document on this phone. Documents
 *   read as HTML get their edition downloaded after every Sync, on any network; others only on
 *   Wi-Fi ([HtmlEditionDownloadPolicy]), or right here when the reader switches them to HTML.
 * - Cards, comments and the reviewed card's answer feedback are highlighted with `setItems`;
 *   cards without anchors in the edition are listed as "Not shown in HTML view".
 * - Reviews: [ReviewSessionController] presents its card through [reviewPresenter] while this view
 *   is shown (mask, Reveal, flip, ‹ ›).
 * - Selections: New review card / Comment (created_in "html"), Read Aloud, Copy, and Like / Dislike /
 *   Comment… inside a revealed answer.
 */
internal class HtmlReaderController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val document: () -> DocumentState,
    private val store: HtmlEditionStore = HtmlEditionStore.default(activity),
) {
    private val preferences = activity.getSharedPreferences(HtmlEditionDownloadPolicy.PREFERENCES, Context.MODE_PRIVATE)
    private var view: HtmlEditionView? = null
    /** The document (filename, PDF hash) the HTML view is shown for; null while the PDF is shown. */
    private var shownFor: Pair<String, String>? = null
    private var ready = false
    private val whenReady = mutableListOf<() -> Unit>()
    private var placement = HtmlReaderModel.Placement.EMPTY
    private var cards: List<ReviewCard> = emptyList()
    private var comments: List<Pair<String, JsonObject>> = emptyList()
    /** The reviewed card's feedback: (feedback, shown on this card) for setItems. */
    private var feedback: Pair<String, List<ReviewCardFeedback.Feedback>>? = null
    private var itemsJob: Job? = null
    private var lastSelection: HtmlReaderModel.Selection? = null
    private var lastPosition: HtmlReaderModel.Position? = null
    /** The viewer's last `position` message, which its `scrollTo` takes back. */
    private var lastPositionJson: JsonObject? = null
    private var review: ReviewState? = null
    private var notShownSnackbarFor: String? = null
    /** An edition being downloaded because the reader switched its document to HTML. */
    private var downloading: Job? = null

    /** The card presented in the HTML view, and what the viewer reported about its mask. */
    private data class ReviewState(val cardId: String, var inverted: Boolean, var revealed: Boolean, var blocks: Int = 0)

    /** Whether the HTML view is on screen. */
    val isActive: Boolean get() = shownFor != null

    /** The edition shown, for records made in the HTML view. */
    val edition: HtmlEditionManifest? get() = view?.edition?.takeIf { isActive }

    /** The review store cards are read from (tests use their own). */
    internal var reviewStore: () -> ReviewStore = { ReviewStore(activity.applicationContext) }

    /** Tests: the WebView of the HTML view. */
    internal val webView: HtmlEditionView? get() = view

    /** Tests: the last placement report of `setItems`. */
    internal val lastPlacement: HtmlReaderModel.Placement get() = placement

    /** Tests: whether the viewer reported `ready` for the edition shown. */
    internal val isReady: Boolean get() = ready

    // ---- Mode ------------------------------------------------------------------------------

    private fun documentKey(doc: DocumentState = document()): String? =
        doc.documentId?.takeIf(String::isNotBlank) ?: doc.name.takeIf(String::isNotBlank)

    /** The view remembered for the open document on this phone: HTML or PDF (the default). */
    fun prefersHtml(doc: DocumentState = document()): Boolean =
        documentKey(doc)?.let {
            preferences.getString(HtmlEditionDownloadPolicy.modeKey(it), null) == HtmlEditionDownloadPolicy.MODE_HTML
        } == true

    private fun rememberMode(html: Boolean, doc: DocumentState = document()) {
        val key = HtmlEditionDownloadPolicy.modeKey(documentKey(doc) ?: return)
        preferences.edit().apply { if (html) putString(key, HtmlEditionDownloadPolicy.MODE_HTML) else remove(key) }.apply()
    }

    /** Off the main thread: what the HTML view can show for the open document. */
    fun availability(doc: DocumentState = document()): HtmlAvailability {
        if (doc.projectReading != null) return HtmlAvailability.Unavailable("a project reading combines several documents")
        val file = doc.uri?.takeIf { it.scheme == "file" }?.path?.let(::File)
            ?: return HtmlAvailability.Unavailable("this PDF is not in the Library")
        val index = runCatching { SourceLinkedPdfIndex(ThoughtsLocalStore.currentPdfs()).snapshot() }.getOrNull()
        val kind = index?.libraryKindsByFilename?.get(file.name)
        if (kind == null && file.parentFile?.canonicalPath != ThoughtsLocalStore.currentPdfs().canonicalPath) {
            return HtmlAvailability.Unavailable("this PDF is not in the Library")
        }
        return store.availability(doc.annotationPdfSha256, hasSource = kind != LibraryPdfKind.UNLINKED)
    }

    /** The reader menu's "HTML view" / "PDF view". */
    fun toggle() {
        if (isActive) {
            showPdf(remember = true)
            return
        }
        val doc = document()
        activity.lifecycleScope.launch {
            val availability = withContext(Dispatchers.IO) { availability(doc) }
            if (doc !== document() || doc.uri != document().uri) return@launch
            when (availability) {
                is HtmlAvailability.Ready -> {
                    rememberMode(true)
                    showHtml(availability.edition, mapPdfPosition = true)
                }
                HtmlAvailability.Building -> {
                    // Remembered, so the next Sync downloads it on any network once it is built.
                    rememberMode(true, doc)
                    explain(activity.getString(R.string.html_view_building))
                }
                HtmlAvailability.NotDownloaded -> downloadAndShow(doc, mapPdfPosition = true, failed = ::explain)
                is HtmlAvailability.Unavailable -> explain(activity.getString(R.string.html_view_unavailable, availability.reason))
            }
        }
    }

    /**
     * Downloads the open document's edition now (no Sync downloaded it: it was left for Wi-Fi), with
     * a progress note, then shows it; [failed] gets "HTML not available: <reason>" otherwise.
     */
    private fun downloadAndShow(doc: DocumentState, mapPdfPosition: Boolean, failed: (String) -> Unit) {
        if (downloading?.isActive == true) return
        val filename = doc.uri?.takeIf { it.scheme == "file" }?.path?.let(::File)?.name
        val sha = doc.annotationPdfSha256
        if (filename == null || sha == null) {
            failed(activity.getString(R.string.html_view_unavailable, "this PDF is not a Library version"))
            return
        }
        val progress = AppSnackbar.make(binding.root, activity.getString(R.string.html_view_downloading), Snackbar.LENGTH_INDEFINITE)
        progress.show()
        downloading = activity.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val remote = FedoraRepository(activity.applicationContext).htmlEditionRemote()
                            ?: return@runCatching HtmlAvailability.Unavailable("no Thoughts server is set up")
                        HtmlEditionSync(store, remote).downloadNow(filename, sha)
                    }.getOrElse { HtmlAvailability.Unavailable(it.message ?: it.javaClass.simpleName) }
                }
            } finally {
                progress.dismiss()
            }
            if (doc !== document() || doc.uri != document().uri) return@launch
            when (result) {
                is HtmlAvailability.Ready -> {
                    rememberMode(true, doc)
                    showHtml(result.edition, mapPdfPosition)
                }
                HtmlAvailability.Building -> {
                    rememberMode(true, doc)
                    failed(activity.getString(R.string.html_view_building))
                }
                HtmlAvailability.NotDownloaded -> failed(activity.getString(R.string.html_view_unavailable, "the download did not complete"))
                is HtmlAvailability.Unavailable -> failed(activity.getString(R.string.html_view_unavailable, result.reason))
            }
        }
    }

    private fun explain(message: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.html_view)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** A PDF finished loading: show it as HTML when that is what this document was last read in. */
    fun onDocumentLoaded() {
        val doc = document()
        if (!prefersHtml(doc)) {
            if (isActive) showPdf(remember = false)
            return
        }
        activity.lifecycleScope.launch {
            val availability = withContext(Dispatchers.IO) { availability(doc) }
            if (doc.uri != document().uri || doc.annotationPdfSha256 != document().annotationPdfSha256) return@launch
            if (availability == HtmlAvailability.NotDownloaded) {
                // Read as HTML, so it is downloaded on any network; no Sync has done it yet.
                if (isActive) showPdf(remember = false)
                downloadAndShow(doc, mapPdfPosition = false) { reason ->
                    AppSnackbar.make(binding.root, activity.getString(R.string.html_view_showing_pdf, reason), Snackbar.LENGTH_LONG).show()
                }
            } else if (availability is HtmlAvailability.Ready) {
                if (shownFor != doc.name to doc.annotationPdfSha256.orEmpty() || view?.edition?.sha256 != availability.edition.sha256) {
                    showHtml(availability.edition, mapPdfPosition = false)
                } else refreshItems()
            } else {
                if (isActive) showPdf(remember = false)
                val reason = when (availability) {
                    HtmlAvailability.Building -> activity.getString(R.string.html_view_building)
                    HtmlAvailability.NotDownloaded -> ""
                    is HtmlAvailability.Unavailable -> activity.getString(R.string.html_view_unavailable, availability.reason)
                    is HtmlAvailability.Ready -> ""
                }
                AppSnackbar.make(binding.root, activity.getString(R.string.html_view_showing_pdf, reason), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** The reader is replacing its document: back to the PDF until the next one has loaded. */
    fun onDocumentReset() {
        hideView()
    }

    /** Cards, comments or feedback may have changed (Sync, a new card or comment). */
    fun onLibraryChanged() {
        if (isActive) refreshItems()
    }

    /** New editions were downloaded after a Sync: a document waiting for its HTML can show it now. */
    fun onEditionsUpdated() {
        val doc = document()
        if (doc.uri == null) return
        if (!isActive && prefersHtml(doc)) onDocumentLoaded()
    }

    private fun ensureView(): HtmlEditionView = view ?: HtmlEditionView(activity).also { created ->
        created.listener = HtmlEditionView.Listener(::onMessage)
        created.selectionMenu = selectionMenu
        val parent = binding.pdfView.parent as ViewGroup
        parent.addView(created, parent.indexOfChild(binding.pdfView) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        created.visibility = View.GONE
        view = created
    }

    private fun showHtml(edition: HtmlEditionManifest, mapPdfPosition: Boolean) {
        val doc = document()
        val target = if (mapPdfPosition) pdfPositionTarget() else null
        val webView = ensureView()
        shownFor = doc.name to doc.annotationPdfSha256.orEmpty()
        ready = false
        whenReady.clear()
        placement = HtmlReaderModel.Placement.EMPTY
        lastSelection = null
        lastPosition = null
        webView.visibility = View.VISIBLE
        binding.pdfView.visibility = View.INVISIBLE
        activity.inlineAnnotationActionsHidden()
        webView.show(store, edition, JsonObject().apply {
            addProperty("theme", theme())
            addProperty("textSize", textSize())
            addProperty("contextMenu", contextMenuMode)
        })
        target?.let { scroll -> afterReady { webView.call("scrollTo", JsonArray().apply { add(scroll) }) } }
        refreshItems()
        activity.reviewViewChanged()
        activity.updateActionBar()
    }

    /** Back to the PDF; [remember] makes PDF this document's view again. */
    fun showPdf(remember: Boolean) {
        if (!isActive) return
        if (remember) rememberMode(false)
        val position = lastPosition
        hideView()
        position?.let(::applyPositionToPdf)
        activity.reviewViewChanged()
        activity.updateActionBar()
    }

    private fun hideView() {
        val wasActive = isActive
        shownFor = null
        ready = false
        whenReady.clear()
        review = null
        itemsJob?.cancel()
        view?.apply {
            visibility = View.GONE
            clear()
        }
        binding.pdfView.visibility = View.VISIBLE
        if (wasActive) binding.root.requestLayout()
    }

    private fun afterReady(action: () -> Unit) {
        if (ready) action() else whenReady += action
    }

    private fun theme(): String {
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    fun onConfigurationChanged() {
        if (ready) view?.callWith("setTheme", theme())
    }

    // ---- Text size ---------------------------------------------------------------------------

    fun textSize(): Int = preferences.getInt(TEXT_SIZE, 100).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)

    fun showTextSizeDialog() {
        val density = activity.resources.displayMetrics.density
        val slider = Slider(activity).apply {
            valueFrom = MIN_TEXT_SIZE.toFloat()
            valueTo = MAX_TEXT_SIZE.toFloat()
            stepSize = 10f
            value = (textSize() / 10 * 10).toFloat().coerceIn(valueFrom, valueTo)
            setLabelFormatter { "${it.toInt()} %" }
            addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                preferences.edit().putInt(TEXT_SIZE, value.toInt()).apply()
                if (ready) view?.callWith("setTextSize", value.toInt())
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.html_view_text_size)
            .setView(FrameLayout(activity).apply {
                setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), 0)
                addView(slider)
            })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---- Items (cards, comments, feedback) --------------------------------------------------

    fun refreshItems() {
        if (!isActive) return
        val doc = document()
        val filename = doc.name
        val hash = doc.annotationPdfSha256 ?: return
        itemsJob?.cancel()
        itemsJob = activity.lifecycleScope.launch {
            val (loadedCards, loadedComments) = withContext(Dispatchers.IO) {
                val cards = runCatching {
                    // Cards inside HTML attachment pages are shown in those pages, not in the edition.
                    documentReviewCards(reviewStore().browserCards(), filename, null, hash).map { it.card }
                        .filterNot { it.isHtmlAttachmentCard }
                }.getOrDefault(emptyList())
                val comments = runCatching {
                    LogicalCommentStore(activity.applicationContext).snapshot(filename).logicalComments
                        .filter { !it.deleted && it.parentId == null && it.originCollection + ".pdf" == filename }
                        .map { it.id to JsonParser.parseString(it.payloadJson).asJsonObject }
                }.getOrDefault(emptyList())
                cards to comments
            }
            if (document().name != filename || !isActive) return@launch
            cards = loadedCards
            comments = loadedComments
            afterReady(::pushItems)
        }
    }

    /** The reviewed card's feedback (own and shared records) while its answer is visible; null hides it. */
    fun setFeedback(cardId: String?, records: List<ReviewCardFeedback.Feedback>) {
        feedback = cardId?.let { it to records }
        if (ready) pushItems()
    }

    private fun cardKeys(card: ReviewCard): List<String> =
        HtmlReaderModel.cardAnchorKeys(card.id, card.sourceAnchor)

    private fun cardQuote(card: ReviewCard): HtmlReaderModel.TextQuote? =
        card.htmlSelection?.takeIf { card.htmlPending }?.getAsJsonObject("text_quote")?.let(HtmlReaderModel.TextQuote::from)

    private fun pushItems() {
        val webView = view ?: return
        val reviewed = review?.cardId
        val cardItems = JsonArray().apply {
            cards.forEach { card -> add(HtmlReaderModel.viewerCard(card.id, cardKeys(card), cardQuote(card), selected = card.id == reviewed)) }
            // A card under review that is not one of this PDF's highlighted cards (e.g. another version).
            if (reviewed != null && cards.none { it.id == reviewed }) reviewCard?.let { card ->
                add(HtmlReaderModel.viewerCard(card.id, cardKeys(card), cardQuote(card), selected = true))
            }
        }
        val commentItems = JsonArray().apply {
            comments.forEach { (id, payload) ->
                val linked = payload.getAsJsonObject("scope")?.get("state")?.asString == "source_linked"
                val origin = payload.getAsJsonObject("origin")
                val quote = origin?.getAsJsonObject("text_quote")?.let(HtmlReaderModel.TextQuote::from)
                add(HtmlReaderModel.viewerComment(id,
                    if (linked) listOf(HtmlReaderModel.commentAnchorKey(id, payload)) else emptyList(),
                    payload.get("selected_text")?.takeIf { it.isJsonPrimitive }?.asString, quote,
                    strike = payload.get("subtype")?.asString?.equals("StrikeOut", ignoreCase = true) == true))
            }
        }
        val feedbackItems = JsonArray().apply {
            feedback?.let { (cardId, records) ->
                records.forEach { record ->
                    add(HtmlReaderModel.viewerFeedback(record.id, cardId, record.polarity == ReviewCardFeedback.Polarity.POSITIVE,
                        HtmlReaderModel.TextQuote(record.exact, record.prefix, record.suffix)))
                }
            }
        }
        webView.call("setItems", JsonArray().apply {
            add(cardItems); add(commentItems); add(feedbackItems)
            add(JsonObject().apply { addProperty("show_feedback", "revealed") })
        }) { result ->
            placement = HtmlReaderModel.Placement.parse(result.getOrNull())
            announceNotShown()
        }
    }

    /** Cards of this document the HTML edition cannot show (no anchor); they review in the PDF. */
    fun notShownCards(): List<ReviewCard> = cards.filter { it.id in placement.unplacedCards }

    private fun announceNotShown() {
        val missing = notShownCards()
        val key = "${shownFor}:${missing.size}"
        if (missing.isEmpty() || notShownSnackbarFor == key) return
        notShownSnackbarFor = key
        AppSnackbar.make(binding.root, activity.resources.getQuantityString(R.plurals.html_view_cards_not_shown,
            missing.size, missing.size), Snackbar.LENGTH_LONG)
            .setAction(R.string.html_view_show_list) { showNotShownList() }
            .show()
    }

    /** "Not shown in HTML view": a card opens in the PDF at its page. */
    fun showNotShownList() {
        val missing = notShownCards()
        if (missing.isEmpty()) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.html_view_not_shown_title)
            .setItems(missing.map { it.question }.toTypedArray()) { _, index ->
                val card = missing[index]
                showPdf(remember = false)
                card.pdfPage?.let { binding.pdfView.jumpTo(it - 1) }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---- Reviews -----------------------------------------------------------------------------

    private var reviewCard: ReviewCard? = null
    /** The review controller learns here when the viewer could not show a presented card. */
    var onReviewUnavailable: ((ReviewCard, String) -> Unit)? = null
    /** The viewer's count of answer blocks changed (mask relayout). */
    var onReviewBlocks: ((Int) -> Unit)? = null

    /** Whether [card] can be reviewed in the HTML view shown: it has anchors (or a pending quote) in the edition. */
    fun canPresent(card: ReviewCard): Boolean {
        if (!isActive) return false
        if (card.id in placement.unplacedCards) return false
        val keys = cardKeys(card)
        val anchors = edition?.anchors.orEmpty()
        return when {
            keys.isEmpty() -> cardQuote(card) != null
            // The manifest lists the edition's marker keys; an older manifest may list none.
            anchors.isEmpty() -> true
            else -> keys.any { it in anchors }
        }
    }

    val reviewPresenter: ReviewMaskPresenter = object : ReviewMaskPresenter {
        override fun present(card: ReviewCard, inverted: Boolean, revealed: Boolean): Boolean {
            if (!canPresent(card)) return false
            reviewCard = card
            val state = ReviewState(card.id, inverted, revealed)
            review = state
            if (cards.none { it.id == card.id }) refreshItems()
            afterReady {
                if (review !== state) return@afterReady
                pushItems()
                val options = JsonObject().apply {
                    addProperty("inverted", state.inverted && !state.revealed)
                    addProperty("scroll", true)
                }
                view?.call("presentReview", JsonArray().apply { add(card.id); add(options) }) { result ->
                    if (review !== state) return@call
                    result.onSuccess { value ->
                        state.blocks = value.asJsonObject?.get("blocks")?.asInt ?: 0
                        if (state.revealed) view?.call("reveal")
                        onReviewBlocks?.invoke(state.blocks)
                    }.onFailure { error ->
                        review = null
                        onReviewUnavailable?.invoke(card, error.message.orEmpty())
                    }
                }
            }
            return true
        }

        override fun setInverted(inverted: Boolean) {
            val state = review ?: return
            state.inverted = inverted
            afterReady { if (review === state) view?.callWith("setReviewInverted", inverted) }
        }

        override fun setRevealed(revealed: Boolean) {
            val state = review ?: return
            if (state.revealed == revealed) return
            state.revealed = revealed
            if (revealed) afterReady { if (review === state) view?.call("reveal") }
            else reviewCard?.let { present(it, state.inverted, false) }
        }

        override fun clear() {
            if (review == null) return
            review = null
            reviewCard = null
            if (ready) {
                view?.call("clearReview")
                pushItems()
            }
        }

        override fun blockCount(): Int = review?.blocks ?: 0

        override fun focusBlock(index: Int, visibleBottom: Float) {
            view?.callWith("focusReviewMaskBlock", index)
        }

        override fun focusAnswer(card: ReviewCard, visibleBottom: Float) {
            view?.callWith("focusReviewMaskBlock", 0)
        }

        override fun captureView(): Any? = lastPositionJson?.deepCopy()

        override fun restoreView(state: Any?) {
            val position = state as? JsonObject ?: return
            afterReady { view?.callWith("scrollTo", position) }
        }
    }

    // ---- Viewer messages ---------------------------------------------------------------------

    private fun onMessage(type: String, message: JsonObject) {
        when (type) {
            "ready" -> {
                ready = true
                if (activity.pendingProposalCount() > 0) {
                    AppSnackbar.make(binding.root, R.string.html_view_pending_proposals, Snackbar.LENGTH_LONG).show()
                }
                Log.i(TAG, "ready anchors=${message.getAsJsonArray("anchors")?.size()} source_map=${message.get("source_map")}")
                val pending = whenReady.toList()
                whenReady.clear()
                pushItems()
                pending.forEach { it() }
            }
            "selection" -> lastSelection = HtmlReaderModel.Selection.parse(message)
            "position" -> {
                lastPosition = HtmlReaderModel.Position.parse(message)
                lastPositionJson = message
            }
            "reviewMask" -> {
                val state = review ?: return
                if (message.get("card_id")?.takeIf { it.isJsonPrimitive }?.asString != state.cardId) return
                state.blocks = message.get("blocks")?.asInt ?: state.blocks
                onReviewBlocks?.invoke(state.blocks)
            }
            "itemTapped" -> onItemTapped(message.get("kind")?.asString.orEmpty(), message.get("id")?.asString.orEmpty())
            "link" -> onLink(message)
            "error" -> Log.w(TAG, "viewer error ${message.get("where")}: ${message.get("message")}")
        }
    }

    private fun onItemTapped(kind: String, id: String) {
        if (id.isBlank()) return
        when (kind) {
            "card", "question" -> if (review == null) activity.startActivity(ReviewCardDetailsActivity.intent(activity, id))
            "comment" -> {
                val hash = document().annotationPdfSha256 ?: return
                activity.openLogicalCommentFromHtml(id, document().name, hash)
            }
            "feedback" -> activity.openReviewFeedback(id)
        }
    }

    private fun onLink(message: JsonObject) {
        val kind = message.get("kind")?.asString.orEmpty()
        val target = message.get("target")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        when (kind) {
            "web", "mailto" -> runCatching { activity.startActivity(browserLinkIntent(target)) }.onFailure {
                AppSnackbar.make(binding.root, R.string.no_app_to_open_link, Snackbar.LENGTH_LONG).show()
            }
            "thoughts" -> openThoughtsLink(HtmlReaderModel.Link.parse(target))
            // "internal" links are scrolled to by the viewer.
        }
    }

    /** The same targets the PDF's document links open: another Library PDF, or an HTML attachment. */
    private fun openThoughtsLink(link: HtmlReaderModel.Link?) {
        if (link == null) return
        if (link.kind == "html") {
            // Over the reader, owned by the document shown (its PDF has the same link).
            link.path?.takeIf(com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink::isPath)?.let { activity.attachmentReader.openLink(it) }
            return
        }
        val filename = link.filename
        val file = filename?.let { installedRemotePdf(ThoughtsLocalStore.currentPdfs(), it) }
        if (file == null) {
            AppSnackbar.make(binding.root, activity.getString(R.string.html_view_link_missing, filename ?: link.kind),
                Snackbar.LENGTH_LONG).show()
            return
        }
        val destination = link.destination ?: link.anchor
        if (file.name == document().name) {
            // A link into this document: its source label is an element id of the edition.
            link.label?.let { label -> view?.evaluateJavascript(
                "(function(){var e=document.getElementById(${com.google.gson.JsonPrimitive("source-label:$label")});" +
                    "if(e){e.scrollIntoView();}})()", null) }
            return
        }
        activity.handleReaderIntent(Intent(Intent.ACTION_VIEW, Uri.fromFile(file))
            .putExtra(EXTRA_REMOTE_PDF_LINK, "thoughts-pdf-link:${file.name}?page=0")
            .apply { destination?.let { putExtra(EXTRA_HTML_LINK_DESTINATION, it) } })
    }

    // ---- Selection actions -------------------------------------------------------------------

    private val selectionMenu = object : HtmlEditionView.SelectionMenu {
        override fun populate(menu: Menu) {
            val selection = lastSelection
            val inAnswer = selection?.answerCardId != null && review?.revealed == true
            var order = 0
            if (inAnswer) {
                menu.add(0, ITEM_LIKE, order++, R.string.review_feedback_like).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, ITEM_DISLIKE, order++, R.string.review_feedback_dislike).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, ITEM_FEEDBACK_COMMENT, order++, R.string.review_feedback_comment).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
            menu.add(0, ITEM_COPY, order++, R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (review == null) menu.add(0, ITEM_CARD, order++, R.string.selection_add_review).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, ITEM_COMMENT, order++, R.string.add_comment).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, ITEM_READ_ALOUD, order, R.string.read_aloud).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }

        override fun onItem(item: MenuItem): Boolean {
            when (item.itemId) {
                ITEM_COPY -> copySelection()
                ITEM_CARD -> withSelection { startCard(it) }
                ITEM_COMMENT -> withSelection { startComment(it) }
                ITEM_READ_ALOUD -> withSelection { activity.readAloud(it.text) }
                ITEM_LIKE -> withSelection { giveFeedback(it, ReviewFeedbackAction.LIKE) }
                ITEM_DISLIKE -> withSelection { giveFeedback(it, ReviewFeedbackAction.DISLIKE) }
                ITEM_FEEDBACK_COMMENT -> withSelection { giveFeedback(it, ReviewFeedbackAction.COMMENT) }
                else -> return false
            }
            return true
        }
    }

    enum class ReviewFeedbackAction { LIKE, DISLIKE, COMMENT }

    /** The viewer's current selection (fresh, not the debounced message), then clears it. */
    fun withSelection(action: (HtmlReaderModel.Selection) -> Unit) {
        val webView = view ?: return
        webView.call("getSelection") { result ->
            val selection = result.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?.let(HtmlReaderModel.Selection::parse) ?: lastSelection
            if (selection == null || selection.text.isBlank()) return@call
            webView.call("clearSelection")
            action(selection)
        }
    }

    private fun copySelection() {
        view?.call("copySelection") { result ->
            val text = result.getOrNull()?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (text.isNotBlank()) copyToClipboard(activity, activity.getString(R.string.selected_text), text)
            view?.call("clearSelection")
        }
    }

    /** The `created_in: "html"` fields of a card or comment made from [selection]. */
    fun selectionFields(selection: HtmlReaderModel.Selection): JsonObject? {
        val edition = edition ?: return null
        if (selection.quote == null) return null
        val filename = document().name
        return HtmlReaderModel.htmlSelectionFields(edition.sha256, selection) { path ->
            runCatching {
                val inputs = OfflineSourceBundleStore(activity.applicationContext).librarySourceMetadata(filename)
                    ?.pdfs?.get(filename)?.takeIf { it.pdfSha256.equals(document().annotationPdfSha256, true) }
                    ?.segments?.flatMap { it.inputs }.orEmpty()
                (inputs.singleOrNull { it.sourceFile == path } ?: inputs.singleOrNull { it.sourceFile.endsWith("/$path") })?.sourceSha256
            }.getOrNull()
        }
    }

    private fun startCard(selection: HtmlReaderModel.Selection) {
        val fields = selectionFields(selection) ?: return
        activity.startHtmlReviewCard(fields, selection.quote!!.exact)
    }

    private fun startComment(selection: HtmlReaderModel.Selection) {
        val fields = selectionFields(selection) ?: return
        val doc = document()
        val filename = doc.name
        val hash = doc.annotationPdfSha256 ?: return
        val color = com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.HighlightPalette.YELLOW.colorValue
        CommentEditorDialog(activity).show(
            CommentEditorConfig(titleRes = R.string.comment, initialValue = CommentEditorValue(text = "", color = color),
                allowEmptyText = true),
            onSave = { result ->
                val value = result.value
                activity.lifecycleScope.launch {
                    val saved = runCatching {
                        LogicalCommentStore(activity.applicationContext).createHtmlComment(filename, hash, fields, value.text,
                            CommentAuthor.current(activity), value.color, pdfDateNow(), selection.quote!!.exact, value.priority,
                            value.location, value.needsAction)
                    }
                    saved.onSuccess {
                        ThoughtsSyncScheduler.enqueueBackground(activity.applicationContext)
                        AppSnackbar.make(binding.root, R.string.html_view_comment_saved, Snackbar.LENGTH_LONG).show()
                        refreshItems()
                    }.onFailure { error ->
                        Log.w(TAG, "Could not save the comment", error)
                        AppSnackbar.make(binding.root, R.string.comment_failed, Snackbar.LENGTH_LONG).show()
                    }
                }
                true
            },
        )
    }

    private fun giveFeedback(selection: HtmlReaderModel.Selection, action: ReviewFeedbackAction) {
        val edition = edition ?: return
        val quote = selection.answerQuote ?: return
        val cardId = selection.answerCardId ?: return
        if (review?.cardId != cardId) return
        activity.giveHtmlReviewFeedback(quote, edition.sha256, action)
    }

    // ---- Position mapping --------------------------------------------------------------------

    /** Where the HTML view should open: the anchor of the card or comment on the PDF page shown, else the same fraction. */
    private fun pdfPositionTarget(): JsonElement? {
        val pdfView = binding.pdfView
        if (pdfView.pageCount <= 0) return null
        val page = pdfView.currentPage + 1
        val card = cards.filter { it.regions.any { region -> region.page == page } && cardKeys(it).isNotEmpty() }
            .minByOrNull { it.regions.filter { region -> region.page == page }.minOf { region -> region.rect.top } }
        card?.let { return com.google.gson.JsonPrimitive(cardKeys(it).first()) }
        val fraction = pdfView.positionOffset.takeIf { it.isFinite() } ?: return null
        return JsonObject().apply { addProperty("scroll_fraction", fraction.coerceIn(0f, 1f)) }
    }

    private fun applyPositionToPdf(position: HtmlReaderModel.Position) {
        val pdfView = binding.pdfView
        if (pdfView.pageCount <= 0) return
        val item = position.item()
        if (item?.first == "card") {
            cards.firstOrNull { it.id == item.second && it.hasPdfPlacement }?.let { card ->
                val region = card.regions.minWithOrNull(compareBy({ it.page }, { it.rect.top })) ?: return
                binding.root.post { pdfView.focusOnReviewRegion(region.page - 1, region.rect, pdfView.height.toFloat()) }
                return
            }
        }
        position.scrollFraction?.let { fraction -> binding.root.post { pdfView.setPositionOffset(fraction) } }
    }

    fun destroy() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        view = null
    }

    companion object {
        private const val TAG = "ThoughtsHtmlReader"
        private const val TEXT_SIZE = "text_size"
        const val MIN_TEXT_SIZE = 50
        const val MAX_TEXT_SIZE = 200
        /** "native" keeps Android's long-press selection; the viewer then reports, never cancels, the menu. */
        var contextMenuMode = "native"
        const val EXTRA_HTML_LINK_DESTINATION = "org.tbend.thoughts.HTML_LINK_DESTINATION"
        private const val ITEM_COPY = 101
        private const val ITEM_CARD = 102
        private const val ITEM_COMMENT = 103
        private const val ITEM_READ_ALOUD = 104
        private const val ITEM_LIKE = 105
        private const val ITEM_DISLIKE = 106
        private const val ITEM_FEEDBACK_COMMENT = 107
    }
}

/** How a review covers and reveals its card's answer: on the PDF, or in the HTML view. */
internal interface ReviewMaskPresenter {
    /** Covers [card]'s answer (flipped when [inverted]; framed when [revealed]); false when it cannot be shown. */
    fun present(card: ReviewCard, inverted: Boolean, revealed: Boolean): Boolean
    fun setInverted(inverted: Boolean)
    fun setRevealed(revealed: Boolean)
    fun clear()
    /** Answer blocks for ‹ › (known asynchronously in the HTML view). */
    fun blockCount(): Int
    fun focusBlock(index: Int, visibleBottom: Float)
    fun focusAnswer(card: ReviewCard, visibleBottom: Float)
    /** Where the reader is, so Hide again can return there. */
    fun captureView(): Any?
    fun restoreView(state: Any?)
}

/** The PDF view's review mask ([ReviewSessionController] before the HTML view existed). */
internal class PdfReviewMaskPresenter(private val binding: ActivityMainBinding) : ReviewMaskPresenter {
    override fun present(card: ReviewCard, inverted: Boolean, revealed: Boolean): Boolean {
        if (!binding.pdfView.setReviewMasks(card.masksByPage)) return false
        binding.pdfView.setReviewMaskInverted(inverted)
        binding.pdfView.setReviewMaskRevealed(revealed)
        return true
    }
    override fun setInverted(inverted: Boolean) = binding.pdfView.setReviewMaskInverted(inverted)
    override fun setRevealed(revealed: Boolean) = binding.pdfView.setReviewMaskRevealed(revealed)
    override fun clear() = binding.pdfView.clearReviewMask()
    override fun blockCount(): Int = binding.pdfView.reviewMaskBlocks().size
    override fun focusBlock(index: Int, visibleBottom: Float) {
        val block = binding.pdfView.reviewMaskBlocks().getOrNull(index) ?: return
        binding.pdfView.focusOnReviewRegion(block.first, block.second, visibleBottom)
    }
    override fun focusAnswer(card: ReviewCard, visibleBottom: Float) {
        val region = card.regions.minWithOrNull(compareBy({ it.page != card.page }, { it.page }, { it.rect.top }, { it.rect.left })) ?: return
        binding.pdfView.focusOnReviewRegion(region.page - 1, region.rect, visibleBottom)
    }
    override fun captureView(): Any? = binding.pdfView.captureViewState()
    override fun restoreView(state: Any?) {
        (state as? com.github.barteksc.pdfviewer.PDFView.ViewState)?.let(binding.pdfView::applyViewState)
    }
}
