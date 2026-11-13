// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.SearchResult
import com.gitlab.mudlej.MjPdfReader.pdf.grantPdfReadAccess
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.search.SearchActivity
import com.gitlab.mudlej.MjPdfReader.ui.search.SearchCoordinator
import com.gitlab.mudlej.MjPdfReader.ui.search.SearchSessionCache
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.text.NormalizedTextMapper
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import kotlin.math.max

class SearchNavigationController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val pdf: DocumentState,
    private val pref: Preferences,
    private val historyManager: ReaderHistoryManager,
    private val launchSearch: (Intent) -> Unit,
) {

    private var hits: List<SearchSessionCache.Hit> = emptyList()
    private var currentPosition = -1
    private var subscribedToActiveSearch = false
    private var query = ""
    private var ignoreAccents = false
    private var sessionKey: String? = null
    /** 1-based page the first shown hit should be on (global search), or 0 for the first hit. */
    private var preferredPage = 0
    private var snackbar: Snackbar? = null
    private var counterView: TextView? = null
    private var messageView: TextView? = null
    private var previousButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private val renderUri get() = pdf.renderUri ?: pdf.uri

    private val activeSearchListener = object : SearchCoordinator.Listener {
        override fun onProgress(pagesScanned: Int, pageCount: Int) = Unit

        override fun onResults(results: List<SearchResult>, finished: Boolean) {
            if (!subscribedToActiveSearch) return
            val firstResult = currentPosition < 0
            hits = SearchCoordinator.cacheHits(results)
            if (finished) subscribedToActiveSearch = false
            // With a preferred page, wait until a hit on it (or the end of the scan) arrives.
            val start = preferredHitIndex(hits, preferredPage, finished)
            if (firstResult && start != null) {
                currentPosition = start
                historyManager.recordJump(ReaderHistoryManager.Origin.SEARCH, hits[start].pageNumber - 1)
                showCurrentHit()
            } else {
                updateControls()
            }
        }
    }

    val isActive: Boolean get() = snackbar != null

    fun search(searchQuery: String, preferredPage: Int = 0) {
        reset()
        this.preferredPage = preferredPage.coerceAtLeast(0)
        query = searchQuery.trim()
        if (query.isBlank()) return
        ignoreAccents = pref.getSearchIgnoreAccents()
        sessionKey = "${pdf.fileHash}:${renderUri}"
        showSnackbar()
        val cached = SearchSessionCache.get(sessionKey, query, ignoreAccents)
        if (cached != null) {
            hits = cached.hits
            val start = preferredHitIndex(hits, this.preferredPage, finished = true)
            if (start != null) {
                currentPosition = start
                historyManager.recordJump(ReaderHistoryManager.Origin.SEARCH, hits[start].pageNumber - 1)
                showCurrentHit()
            }
        } else {
            subscribedToActiveSearch = true
            SearchCoordinator.startOrSubscribe(activity, renderUri.toString(), pdf.password,
                sessionKey, query, ignoreAccents, activeSearchListener)
        }
        updateControls()
    }

    fun start(searchResult: SearchResult, resultQuery: String?, resultIgnoreAccents: Boolean) {
        unsubscribeFromActiveSearch()
        clearHighlight()
        query = resultQuery?.trim().takeUnless { it.isNullOrBlank() } ?: pdf.lastQuery.orEmpty()
        ignoreAccents = resultIgnoreAccents
        sessionKey = "${pdf.fileHash}:${renderUri}"
        hits = SearchSessionCache.get(sessionKey, query, ignoreAccents)?.hits
            ?: SearchCoordinator.cacheHits(listOf(searchResult))
        currentPosition = hits.indexOfFirst { it.resultIndex == searchResult.searchResultIndexInList }
            .takeIf { it >= 0 } ?: 0
        subscribedToActiveSearch = SearchCoordinator.subscribeIfRunning(
            sessionKey, query, ignoreAccents, activeSearchListener)
        historyManager.recordJump(ReaderHistoryManager.Origin.SEARCH, searchResult.pageNumber - 1)
        showSnackbar()
        showCurrentHit()
    }

    private fun unsubscribeFromActiveSearch() {
        subscribedToActiveSearch = false
        SearchCoordinator.unsubscribe(activeSearchListener)
    }

    fun clearHighlight() {
        binding.pdfView.clearSearchHighlights()
    }

    fun reset() {
        SearchCoordinator.cancel(sessionKey, query, ignoreAccents)
        unsubscribeFromActiveSearch()
        clearHighlight()
        binding.pdfView.setSearchBottomInset(0f)
        val oldBar = snackbar
        snackbar = null
        oldBar?.dismiss()
        counterView = null
        messageView = null
        previousButton = null
        nextButton = null
        hits = emptyList()
        currentPosition = -1
    }

    private fun showSnackbar() {
        if (snackbar != null) {
            return
        }
        val bar = AppSnackbar.make(binding.root, activity.getString(R.string.results), Snackbar.LENGTH_INDEFINITE)
        bar.setAction(activity.getString(R.string.done)) {
            reset()
        }
        bar.addCallback(object : Snackbar.Callback() {
            override fun onShown(shown: Snackbar?) {
                if (snackbar !== shown) return
                val barLocation = IntArray(2)
                val pdfLocation = IntArray(2)
                bar.view.getLocationOnScreen(barLocation)
                binding.pdfView.getLocationOnScreen(pdfLocation)
                binding.pdfView.setSearchBottomInset(
                    (pdfLocation[1] + binding.pdfView.height - barLocation[1]).coerceAtLeast(0).toFloat()
                        + 8 * activity.resources.displayMetrics.density)
                showCurrentHit()
            }

            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (snackbar === transientBottomBar) {
                    unsubscribeFromActiveSearch()
                    clearHighlight()
                    binding.pdfView.setSearchBottomInset(0f)
                    snackbar = null
                    counterView = null
                    messageView = null
                    previousButton = null
                    nextButton = null
                }
            }
        })
        val textView = bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.text = query
        textView.maxLines = 1
        textView.ellipsize = android.text.TextUtils.TruncateAt.END
        messageView = textView
        val density = activity.resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val highlight = ColorStateList.valueOf(
            MaterialColors.getColor(binding.root, android.R.attr.colorControlHighlight)
        )

        fun borderlessRipple(cornerSize: Float): RippleDrawable {
            val shape = ShapeAppearanceModel.builder().setAllCornerSizes(cornerSize).build()
            return RippleDrawable(highlight, null, MaterialShapeDrawable(shape))
        }

        (textView.parent as? LinearLayout)?.let { content ->
            val action = bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)
            content.removeView(textView)
            content.removeView(action)
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                addView(textView, LinearLayout.LayoutParams(-1, -2))
            }
            val navigation = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            panel.addView(navigation, LinearLayout.LayoutParams(-1, -2))
            content.addView(panel)
            val resultsLink = TextView(activity).apply {
                text = activity.getString(R.string.results)
                setTextColor(onSurface)
                textSize = 12f
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                setOnClickListener { openResultsList() }
            }
            navigation.addView(resultsLink, LinearLayout.LayoutParams(0, -2, 1f))
            val buttonSize = (40 * density).toInt()

            fun navigationButton(
                iconRes: Int,
                descriptionRes: Int,
                marginStartDp: Int,
                marginEndDp: Int,
                onClick: () -> Unit,
            ): ImageButton {
                return ImageButton(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = (marginStartDp * density).toInt()
                        marginEnd = (marginEndDp * density).toInt()
                    }
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(onSurface)
                    background = borderlessRipple(buttonSize / 2f)
                    contentDescription = activity.getString(descriptionRes)
                    setOnClickListener { onClick() }
                }
            }

            val previous = navigationButton(R.drawable.ic_chevron_left, R.string.previous_search_result, 0, 0, ::showPrevious)
            val counter = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = (4 * density).toInt()
                    marginEnd = (4 * density).toInt()
                }
                minWidth = (48 * density).toInt()
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                gravity = Gravity.CENTER
                setTextColor(onSurface)
                textSize = 14f
            }
            val next = navigationButton(R.drawable.ic_chevron_right, R.string.next_search_result, 0, 0, ::showNext)

            navigation.addView(previous)
            navigation.addView(counter)
            navigation.addView(next)
            navigation.addView(action)
            previousButton = previous
            counterView = counter
            nextButton = next
        }
        snackbar = bar
        bar.show()
    }

    private fun showPrevious() {
        if (currentPosition > 0) {
            currentPosition--
            showCurrentHit()
        }
    }

    private fun showNext() {
        if (currentPosition < hits.lastIndex) {
            currentPosition++
            showCurrentHit()
        }
    }

    private fun showCurrentHit() {
        val hit = hits.getOrNull(currentPosition) ?: return
        val rawText = binding.pdfView.getPageRawText(hit.pageNumber)
        fun boundsFor(index: Int, length: Int): List<RectF> {
            val range = NormalizedTextMapper.toRawRange(rawText, index, length) ?: return emptyList()
            return binding.pdfView.getSearchTextBounds(hit.pageNumber - 1, range.first, range.last + 1 - range.first)
        }
        // Find every occurrence on this page, even while the document search is still running.
        val normalizedText = binding.pdfView.getPageText(hit.pageNumber - 1)
        val pageBounds = SearchCoordinator.matchRanges(normalizedText, query, ignoreAccents)
            .flatMap { boundsFor(it.first, it.last + 1 - it.first) }
        val activeBounds = boundsFor(hit.originalIndex, hit.matchLength.takeIf { it > 0 } ?: query.length)
        binding.pdfView.setSearchHighlights(hit.pageNumber - 1, pageBounds, activeBounds)
        if (activeBounds.isEmpty()) {
            binding.pdfView.jumpUsingPageNumber(hit.pageNumber)
            messageView?.text = activity.getString(R.string.failed_to_highlight_search_result)
        } else {
            messageView?.text = query
            val targetZoom = if (pref.getSearchZoomToResult()) {
                max(binding.pdfView.zoom, binding.pdfView.midZoom).coerceAtMost(binding.pdfView.maxZoom)
            } else binding.pdfView.zoom
            val union = RectF(activeBounds.minOf { it.left }, activeBounds.maxOf { it.top },
                activeBounds.maxOf { it.right }, activeBounds.minOf { it.bottom })
            if (binding.pdfView.focusOnSearchResult(hit.pageNumber - 1, union, targetZoom) == null) {
                binding.pdfView.jumpUsingPageNumber(hit.pageNumber)
            }
        }
        updateControls()
    }

    private fun updateControls() {
        counterView?.text = when {
            hits.isEmpty() && subscribedToActiveSearch -> activity.getString(R.string.searching)
            hits.isEmpty() -> activity.getString(R.string.search_no_matches)
            else -> activity.getString(R.string.search_result_counter, currentPosition + 1, hits.size) +
                if (subscribedToActiveSearch) "+" else ""
        }
        setButtonEnabled(previousButton, currentPosition > 0)
        setButtonEnabled(nextButton, currentPosition < hits.lastIndex)
    }

    private fun setButtonEnabled(button: ImageButton?, enabled: Boolean) {
        button?.isEnabled = enabled
        button?.alpha = if (enabled) 1f else 0.35f
    }

    private fun openResultsList() {
        Intent(activity, SearchActivity::class.java).also { searchIntent ->
            searchIntent.putExtra(PDF.filePathKey, renderUri.toString())
            searchIntent.putExtra(PDF.passwordKey, pdf.password)
            searchIntent.putExtra(PDF.fileHashKey, sessionKey)
            searchIntent.putExtra(PDF.searchQueryKey, query)
            hits.getOrNull(currentPosition)?.let { hit ->
                searchIntent.putExtra(PDF.resultPositionInListKey, hit.resultIndex)
            }
            searchIntent.grantPdfReadAccess(renderUri.toString())
            launchSearch(searchIntent)
        }
    }
}

/**
 * Which of [hits] to show first: the first one on [preferredPage] (1-based; 0 means none), or,
 * once the scan is [finished] without one there, the first hit after it, else the first hit.
 * Null while the hit on the preferred page may still arrive.
 */
internal fun preferredHitIndex(hits: List<SearchSessionCache.Hit>, preferredPage: Int, finished: Boolean): Int? {
    if (hits.isEmpty()) return null
    if (preferredPage <= 0) return 0
    hits.indexOfFirst { it.pageNumber == preferredPage }.takeIf { it >= 0 }?.let { return it }
    if (!finished && hits.last().pageNumber < preferredPage) return null
    if (!finished) return hits.indexOfFirst { it.pageNumber > preferredPage }.takeIf { it >= 0 }
    return hits.indexOfFirst { it.pageNumber > preferredPage }.takeIf { it >= 0 } ?: 0
}
