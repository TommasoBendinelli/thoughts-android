// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.lifecycle.lifecycleScope
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.browserLinkIntent
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.copyToClipboard
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCardStatus
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentFile
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionStore
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel
import com.gitlab.mudlej.MjPdfReader.data.html.attachmentCardSupport
import com.gitlab.mudlej.MjPdfReader.data.html.setAttachmentCardSupport
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.html.HtmlAttachmentActivity
import com.gitlab.mudlej.MjPdfReader.ui.html.HtmlAttachmentView
import com.gitlab.mudlej.MjPdfReader.ui.html.HtmlEditionView
import com.gitlab.mudlej.MjPdfReader.ui.review.ReviewCardDetailsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HTML attachment pages inside the reader (thoughts-html-attachment-cards-SPEC.md, "Android"):
 * a `thoughts-html:` link of the PDF (or of its HTML view) opens the page over the reader, full
 * size, with its own scripts, tabs and `#hash` navigation. The bar above it has Open in browser
 * (the old behaviour) and Close; Back closes it too.
 *
 * - The document shown is the **owner**: a selection becomes a card of that document
 *   (`created_in: "html_attachment"`, New review card, only when the server takes them).
 * - Existing cards of the page are highlighted by their text quotes.
 * - Reviews: [ReviewSessionController] presents an attachment card through [reviewPresenter]: the
 *   page of the card opens, its stored `location.hash` is restored, the answer is masked once the
 *   page has settled; when the text is not in the page any more, the review panel says so and
 *   shows the stored answer on Reveal.
 */
internal class HtmlAttachmentReaderController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val document: () -> DocumentState,
) {
    /** What is shown: the page and the document whose card it is (null: no cards can be made). */
    data class Shown(val attachment: HtmlAttachmentFile, val owner: HtmlAttachmentCards.Owner?)

    private var container: LinearLayout? = null
    private var view: HtmlAttachmentView? = null
    private var titleView: TextView? = null
    private var closeButton: View? = null
    var shown: Shown? = null
        private set
    private var ready = false
    private val whenReady = mutableListOf<() -> Unit>()
    private var cards: List<ReviewCard> = emptyList()
    private var placement = HtmlReaderModel.Placement.EMPTY
    private var lastSelection: HtmlReaderModel.Selection? = null
    private var itemsJob: Job? = null
    private var openJob: Job? = null
    private var review: ReviewState? = null

    /** The card presented, and what the page showed of it. */
    private class ReviewState(val card: ReviewCard, var inverted: Boolean, var revealed: Boolean) {
        var blocks = 0
        /** Null while the page is still settling; then whether the answer is in the page. */
        var found: Boolean? = null
        var attempts = 0
    }

    /** The review store cards are read from (tests use their own). */
    internal var reviewStore: () -> ReviewStore = { ReviewStore(activity.applicationContext) }

    /** Finds the attachment file: path, owner PDF filename, the card's version as a fallback (tests replace it). */
    internal var resolver: (String, String?, String?) -> HtmlAttachmentFile? = { path, pdf, fallback ->
        OfflineSourceBundleStore(activity.applicationContext).htmlAttachmentFile(path, pdf, fallback)
    }

    /** Whether the server takes cards made in attachments (`html_attachment_cards_v1`), as last seen. */
    internal var cardsSupported: () -> Boolean = { HtmlEditionStore.default(activity).attachmentCardSupport() }

    internal var status: HtmlAttachmentCardStatus = HtmlAttachmentCardStatus.of(activity)

    val isActive: Boolean get() = shown != null

    /** Tests: the page's WebView, whether the viewer is ready, and the last highlight report. */
    internal val webView: HtmlAttachmentView? get() = view
    internal val isReady: Boolean get() = ready
    internal val lastPlacement: HtmlReaderModel.Placement get() = placement
    /** Tests: whether the card under review was found in the page (null while settling). */
    internal val reviewFound: Boolean? get() = review?.found

    /** The document shown as the owner of cards made in its attachments; null in a project reading. */
    fun currentOwner(): HtmlAttachmentCards.Owner? {
        val doc = document()
        if (doc.projectReading != null) return null
        val sha = doc.annotationPdfSha256?.lowercase() ?: return null
        return HtmlAttachmentCards.Owner(doc.documentId.orEmpty(), doc.name, sha).takeIf { it.valid }
    }

    // ---- Opening and closing ------------------------------------------------------------------

    /** A `thoughts-html:` link of the document shown: opens [path] in the reader. */
    fun openLink(path: String) {
        val owner = currentOwner()
        val pdf = owner?.pdfFilename ?: document().name.takeIf { it.endsWith(".pdf", true) }
        open(path, owner, pdf, fallbackSha256 = null) { message ->
            AppSnackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
        refreshSupport()
    }

    private fun open(requested: String, owner: HtmlAttachmentCards.Owner?, pdfFilename: String?, fallbackSha256: String?,
                     then: (() -> Unit)? = null, failed: (String) -> Unit) {
        // A link or card from before this phone moved its connected folders names the old folder.
        val path = com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.followLocal(requested)
        openJob?.cancel()
        openJob = activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { resolver(path, pdfFilename, fallbackSha256) }.getOrNull() }
            if (file == null) {
                failed(activity.getString(R.string.html_attachment_not_downloaded))
                return@launch
            }
            show(Shown(file, owner))
            then?.invoke()
        }
    }

    private fun show(target: Shown) {
        val webView = ensureView()
        val same = shown?.attachment?.sha256 == target.attachment.sha256 && shown?.attachment?.path == target.attachment.path && ready
        shown = target
        titleView?.text = HtmlAttachmentCards.servedName(target.attachment.path)
        container?.visibility = View.VISIBLE
        closeButton?.visibility = if (review != null) View.GONE else View.VISIBLE
        activity.inlineAnnotationActionsHidden()
        if (same) {
            refreshItems()
            return
        }
        ready = false
        whenReady.clear()
        placement = HtmlReaderModel.Placement.EMPTY
        lastSelection = null
        webView.show(target.attachment, JsonObject().apply { addProperty("theme", "system") })
        refreshItems()
    }

    /** Close (bar button, Back): the reader shows its document again. */
    fun close() {
        if (!isActive || review != null) return
        hide()
    }

    private fun hide() {
        closeButton?.visibility = View.VISIBLE
        shown = null
        ready = false
        whenReady.clear()
        review = null
        itemsJob?.cancel()
        openJob?.cancel()
        hideNotFound()
        container?.visibility = View.GONE
        view?.clear()
    }

    /** Back: closes the page, except during its review (the review's own controls move on). */
    fun handleBack(): Boolean {
        if (!isActive) return false
        if (review != null) return false
        close()
        return true
    }

    fun onDocumentReset() {
        if (review == null) hide()
    }

    fun openInBrowser() {
        val target = shown ?: return
        HtmlAttachmentActivity.openInBrowser(activity, target.attachment.path, target.owner?.pdfFilename)
    }

    private fun ensureView(): HtmlAttachmentView = view ?: run {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val surface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        val onSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            isClickable = true
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(4), dp(2))
        }
        val title = TextView(activity).apply {
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val browser = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(R.string.html_attachment_open_in_browser)
            setOnClickListener { openInBrowser() }
        }
        bar.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val closeIcon = AppCompatImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = activity.getString(R.string.close)
            background = null
            setColorFilter(onSurface)
            setOnClickListener { close() }
        }
        bar.addView(closeIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
        val webView = HtmlAttachmentView(activity).apply {
            listener = HtmlAttachmentView.Listener(::onMessage)
            selectionMenu = this@HtmlAttachmentReaderController.selectionMenu
            onExternalLink = { uri -> openExternal(uri) }
        }
        layout.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val parent = binding.pdfView.parent as ViewGroup
        parent.addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layout.visibility = View.GONE
        container = layout
        titleView = title
        closeButton = closeIcon
        view = webView
        webView
    }

    private fun openExternal(uri: Uri) {
        runCatching { activity.startActivity(browserLinkIntent(uri.toString())) }.onFailure {
            AppSnackbar.make(binding.root, R.string.no_app_to_open_link, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun afterReady(action: () -> Unit) {
        if (ready) action() else whenReady += action
    }

    /** Asks the server in the background whether it takes attachment cards (for the next selection). */
    private fun refreshSupport() {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val remote = FedoraRepository(activity.applicationContext).htmlEditionRemote() ?: return@runCatching
                HtmlEditionStore.default(activity).setAttachmentCardSupport(HtmlAttachmentCards.CAPABILITY in remote.capabilities())
            }.onFailure { Log.i(TAG, "capabilities not refreshed: ${it.message}") }
        }
    }

    // ---- Highlights ---------------------------------------------------------------------------

    fun refreshItems() {
        val target = shown ?: return
        itemsJob?.cancel()
        itemsJob = activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    reviewStore().browserCards().map { it.card }.filter { card ->
                        card.approved && card.schedule?.deleted != true && card.htmlAttachment?.path == target.attachment.path
                    }
                }.getOrDefault(emptyList())
            }
            if (shown !== target) return@launch
            cards = loaded
            afterReady(::pushItems)
        }
    }

    private fun pushItems(then: (() -> Unit)? = null) {
        val webView = view ?: return
        val reviewed = review?.card
        val items = JsonArray().apply {
            cards.forEach { card ->
                val quote = card.htmlAttachment?.quote ?: return@forEach
                add(HtmlReaderModel.viewerCard(card.id, emptyList(), quote, selected = card.id == reviewed?.id, style = "card"))
            }
            if (reviewed != null && cards.none { it.id == reviewed.id }) reviewed.htmlAttachment?.quote?.let { quote ->
                add(HtmlReaderModel.viewerCard(reviewed.id, emptyList(), quote, selected = true, style = "card"))
            }
        }
        webView.call("setItems", JsonArray().apply {
            add(items); add(JsonArray()); add(JsonArray())
            add(JsonObject().apply { addProperty("show_feedback", "never") })
        }) { result ->
            placement = HtmlReaderModel.Placement.parse(result.getOrNull())
            then?.invoke()
        }
    }

    // ---- Reviews ------------------------------------------------------------------------------

    /** The review controller learns here that the presented card's page could not be opened. */
    var onReviewUnavailable: ((ReviewCard, String) -> Unit)? = null
    /** The answer's block count changed (‹ ›). */
    var onReviewBlocks: ((Int) -> Unit)? = null

    val reviewPresenter: ReviewMaskPresenter = object : ReviewMaskPresenter {
        override fun present(card: ReviewCard, inverted: Boolean, revealed: Boolean): Boolean {
            val anchor = card.htmlAttachment ?: return false
            val previous = review
            val state = ReviewState(card, inverted, revealed)
            review = state
            hideNotFound()
            // The review's own controls (Next, Done) leave the page.
            closeButton?.visibility = View.GONE
            val owner = HtmlAttachmentCards.Owner(card.documentId, "${card.pdfStem}.pdf", card.pdfSha256.lowercase())
            val showing = shown
            if (showing != null && showing.attachment.path == anchor.path && ready && previous?.card?.id == card.id) {
                // Hide again on the same card: the page stays; mask the answer again, or keep
                // "Answer not found" (the page did not change since).
                if (previous.found == false) {
                    state.found = false
                    showNotFound(state)
                    return true
                }
                afterReady { presentInPage(state) }
                return true
            }
            open(anchor.path, owner.takeIf { it.valid }, owner.pdfFilename, anchor.sha256, then = {
                if (review === state) afterReady { restoreAndPresent(state) }
            }) { message ->
                if (review !== state) return@open
                review = null
                hide()
                onReviewUnavailable?.invoke(card, message)
            }
            return true
        }

        override fun setInverted(inverted: Boolean) {
            val state = review ?: return
            state.inverted = inverted
            afterReady { if (review === state && state.found == true) view?.callWith("setReviewInverted", inverted) }
        }

        override fun setRevealed(revealed: Boolean) {
            val state = review ?: return
            if (state.revealed == revealed) return
            state.revealed = revealed
            if (state.found == false) {
                showNotFound(state)
                return
            }
            if (revealed) afterReady { if (review === state) view?.call("reveal") }
            else present(state.card, state.inverted, false)
        }

        override fun clear() {
            if (review == null && !isActive) return
            val wasReviewing = review != null
            review = null
            hideNotFound()
            if (wasReviewing) {
                // The session moved on (another card, Done): the page closes with its review.
                hide()
            }
        }

        override fun blockCount(): Int = review?.takeIf { it.found == true }?.blocks ?: 0

        override fun focusBlock(index: Int, visibleBottom: Float) {
            view?.callWith("focusReviewMaskBlock", index)
        }

        override fun focusAnswer(card: ReviewCard, visibleBottom: Float) {
            view?.callWith("focusReviewMaskBlock", 0)
        }

        override fun captureView(): Any? = null

        override fun restoreView(state: Any?) {
            afterReady { view?.callWith("focusReviewMaskBlock", 0) }
        }
    }

    /** Puts the page in the card's state (`location.hash`), then masks the answer once it is there. */
    private fun restoreAndPresent(state: ReviewState) {
        if (review !== state) return
        val hash = state.card.htmlAttachment?.pageHash.orEmpty()
        view?.call("setPageState", JsonArray().apply { add(JsonObject().apply { addProperty("hash", hash) }) }) { result ->
            if (review !== state) return@call
            if (result.isFailure) {
                // A viewer without setPageState: set the hash in the page; its hashchange re-renders.
                view?.evaluateJavascript("(function(){var h=${com.google.gson.JsonPrimitive(hash)};" +
                    "if(location.hash!==h&&!(h===''&&location.hash==='')){location.hash=h;}})()") {
                    binding.root.postDelayed({ presentInPage(state) }, OLD_VIEWER_SETTLE_MS)
                }
            } else presentInPage(state)
        }
    }

    private fun presentInPage(state: ReviewState) {
        if (review !== state) return
        pushItems {
            if (review !== state) return@pushItems
            val options = JsonObject().apply {
                addProperty("inverted", state.inverted && !state.revealed)
                addProperty("scroll", true)
                state.card.htmlAttachment?.quote?.let { add("text_quote", it.toJson()) }
            }
            view?.call("presentReview", JsonArray().apply { add(state.card.id); add(options) }) { result ->
                if (review !== state) return@call
                result.onSuccess { value ->
                    val json = value.takeIf { it.isJsonObject }?.asJsonObject
                    val blocks = json?.get("blocks")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                    // `pending`: not in the page yet; the viewer retries after each reindex and reports `reviewMask`.
                    if (blocks > 0 && json?.get("pending")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) found(state, blocks)
                }.onFailure {
                    // Not placed yet (the page is still rendering), or a viewer that does not retry.
                    if (++state.attempts * RETRY_MS >= NOT_FOUND_AFTER_MS) notFound(state)
                    else binding.root.postDelayed({ presentInPage(state) }, RETRY_MS)
                }
            }
        }
    }

    private fun found(state: ReviewState, blocks: Int) {
        state.blocks = blocks
        if (state.found != true) {
            state.found = true
            hideNotFound()
            shown?.attachment?.sha256?.let { status.record(state.card.id, it, found = true) }
        }
        if (state.revealed) view?.call("reveal")
        onReviewBlocks?.invoke(blocks)
    }

    private fun notFound(state: ReviewState) {
        if (state.found == false) return
        state.found = false
        state.blocks = 0
        view?.call("clearReview")
        shown?.attachment?.sha256?.let { status.record(state.card.id, it, found = false) }
        Log.i(TAG, "review card=${state.card.id} answer not found in ${shown?.attachment?.path}")
        showNotFound(state)
        onReviewBlocks?.invoke(0)
    }

    /** "Answer not found in the current page", and on Reveal the answer saved with the card. */
    private fun showNotFound(state: ReviewState) {
        if (review !== state) return
        val text = activity.getString(R.string.html_attachment_answer_not_found_review)
        binding.reviewRelatedContext.text = if (!state.revealed) text else
            text + "\n\n" + activity.getString(R.string.html_attachment_stored_answer,
                HtmlAttachmentCards.storedAnswer(state.card.htmlAttachment, ""))
        binding.reviewRelatedContext.visibility = View.VISIBLE
        notFoundShown = true
    }

    private var notFoundShown = false

    private fun hideNotFound() {
        if (!notFoundShown) return
        notFoundShown = false
        binding.reviewRelatedContext.visibility = View.GONE
    }

    /** Tests and the review panel: the text shown instead of a mask, or null. */
    internal val notFoundText: String? get() = binding.reviewRelatedContext.text?.toString()?.takeIf { notFoundShown }

    // ---- Viewer messages ----------------------------------------------------------------------

    private fun onMessage(type: String, message: JsonObject) {
        when (type) {
            "ready" -> {
                ready = true
                val pending = whenReady.toList()
                whenReady.clear()
                pushItems()
                pending.forEach { it() }
            }
            "selection" -> lastSelection = HtmlReaderModel.Selection.parse(message)
            "reviewMask" -> {
                val state = review ?: return
                if (message.get("card_id")?.takeIf { it.isJsonPrimitive }?.asString != state.card.id) return
                val blocks = message.get("blocks")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val maskState = message.get("state")?.takeIf { it.isJsonPrimitive }?.asString
                val inPage = message.get("found")?.takeIf { it.isJsonPrimitive }?.asBoolean
                when {
                    // The page settled without the answer (viewer 0.2.0 ends its review then).
                    maskState == "not_found" -> notFound(state)
                    blocks > 0 && inPage != false -> found(state, blocks)
                    // Pending, or a found answer that left the screen (another tab): the mask comes back with it.
                    state.found == true -> {
                        state.blocks = blocks
                        onReviewBlocks?.invoke(blocks)
                    }
                }
            }
            "placement" -> placement = HtmlReaderModel.Placement.parse(message)
            "itemTapped" -> {
                val id = message.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                // Only one of this page's cards opens its details (the message is a hint from the page).
                if (review == null && cards.any { it.id == id }) {
                    activity.startActivity(ReviewCardDetailsActivity.intent(activity, id))
                }
            }
            "error" -> Log.w(TAG, "viewer error ${message.get("where")}: ${message.get("message")}")
        }
    }

    // ---- Selection actions --------------------------------------------------------------------

    /** Whether New review card is offered: an owner document, a server that takes them, no review. */
    fun canMakeCards(): Boolean = review == null && shown?.owner?.valid == true && cardsSupported()

    private val selectionMenu = object : HtmlEditionView.SelectionMenu {
        override fun populate(menu: Menu) {
            menu.add(0, ITEM_COPY, 0, R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (canMakeCards()) {
                menu.add(0, ITEM_CARD, 1, R.string.html_attachment_new_review_card).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
        }

        override fun onItem(item: MenuItem): Boolean {
            when (item.itemId) {
                ITEM_COPY -> copySelection()
                ITEM_CARD -> withSelection(::startCard)
                else -> return false
            }
            return true
        }
    }

    /** The viewer's current selection (asked now, after the person's tap), then clears it. */
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

    /** The attachment fields of a card made from [selection] in the page shown, or null. */
    fun selectionFields(selection: HtmlReaderModel.Selection): JsonObject? {
        val target = shown ?: return null
        if (selection.quote == null) return null
        return runCatching { HtmlAttachmentCards.fields(target.attachment.path, target.attachment.sha256, selection) }.getOrNull()
    }

    private fun startCard(selection: HtmlReaderModel.Selection) {
        if (!canMakeCards()) return
        val owner = shown?.owner ?: return
        val fields = selectionFields(selection) ?: return
        activity.startAttachmentReviewCard(fields, owner, selection.quote!!.exact)
    }

    fun destroy() {
        view?.destroy()
        container?.let { (it.parent as? ViewGroup)?.removeView(it) }
        container = null
        view = null
    }

    companion object {
        private const val TAG = "ThoughtsHtmlAttachment"
        private const val ITEM_COPY = 201
        private const val ITEM_CARD = 202
        private const val RETRY_MS = 300L
        /** How long a page may take to render the answer before it counts as not found. */
        private const val NOT_FOUND_AFTER_MS = 3_600L
        private const val OLD_VIEWER_SETTLE_MS = 250L
    }
}
