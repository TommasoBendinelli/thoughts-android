// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.os.SystemClock
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesFileObserver
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityReviewCardBrowserBinding
import com.gitlab.mudlej.MjPdfReader.databinding.ItemReviewBrowserCardBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.reviewTagLabel
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReviewCardBrowserActivity : AppCompatActivity() {

    private data class CardBrowserLoad(
        val cards: List<ReviewBrowserCard>,
        val projects: List<ReviewBrowserProject>,
    )

    private lateinit var binding: ActivityReviewCardBrowserBinding
    private lateinit var reviewStore: ReviewStore
    private val sourceIndex = SourceLinkedPdfIndex()
    private lateinit var pendingObserver: PendingChangesFileObserver
    private var sourceObserver: FileObserver? = null
    private var pendingObserverStartJob: Job? = null
    private val sourceBundles by lazy { OfflineSourceBundleStore(applicationContext) }
    private val cardLoader by lazy {
        CoalescingReviewLoader(lifecycleScope, ::readCards) { result ->
            binding.reviewCardBrowserProgress.visibility = View.GONE
            result.onSuccess { result ->
                loaded = true
                allCards = result.cards
                projectChoices = result.projects
                if (projectChoices.none { it.id == selectedProjectId }) selectedProjectId = null
                updateControlLabels()
                renderCards()
            }.onFailure(::showLoadFailure)
        }
    }
    private var allCards: List<ReviewBrowserCard> = emptyList()
    private var loaded = false
    private val selectedIds = mutableSetOf<String>()
    /** The list on screen and its count; see [ReviewCardBrowserDisplay]. */
    private val display by lazy { ReviewCardBrowserDisplay { cards, shown -> adapter.submitList(cards, shown) } }
    private val shownCards: List<ReviewBrowserCard> get() = display.shownCards
    private var deleting = false
    private var deckScope: String? = null
    private var selectedProjectId: String? = null
    private var projectChoices: List<ReviewBrowserProject> = emptyList()
    private var query = ""
    private var searchExpanded = false
    private var searchMenuItem: MenuItem? = null
    private fun toggleSelection(item: ReviewBrowserCard) {
        if (deleting) return
        if (!selectedIds.add(item.card.id)) selectedIds.remove(item.card.id)
        updateSelection()
    }

    /** Back ends a selection before it leaves the browser. */
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectedIds.clear()
            updateSelection()
        }
    }
    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (searchMenuItem?.collapseActionView() != true) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }
    private var filter = ReviewCardBrowserFilter.ALL
    /** Shows only cards whose latest review carries any of these tags, together with [filter]. */
    private var sort = ReviewCardBrowserSort.DUE
    private var sortDescending = false

    private var visibleColumns = ReviewCardBrowserColumn.entries.filter { it in ReviewCardBrowserColumn.defaults }
    private var columnOrder = ReviewCardBrowserColumn.entries.toList()
    private val columnsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val names = result.data?.getStringArrayListExtra(ManageReviewColumnsActivity.EXTRA_SELECTED_COLUMNS)
            ?: return@registerForActivityResult
        val columns = names.mapNotNull { name -> ReviewCardBrowserColumn.entries.firstOrNull { it.name == name } }
            .distinct()
        if (columns.isEmpty()) return@registerForActivityResult
        val orderedNames = result.data?.getStringArrayListExtra(ManageReviewColumnsActivity.EXTRA_COLUMN_ORDER).orEmpty()
        val ordered = orderedNames.mapNotNull { name -> ReviewCardBrowserColumn.entries.firstOrNull { it.name == name } }
            .distinct()
        columnOrder = ordered + ReviewCardBrowserColumn.entries.filter { it !in ordered }
        visibleColumns = columns
        getSharedPreferences(COLUMN_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putStringSet(VISIBLE_COLUMNS, columns.map { it.name }.toSet())
            .putString(COLUMN_ORDER, columnOrder.joinToString(",") { it.name })
            .apply()
        updateColumnVisibility()
        updateControlLabels()
    }

    /** The cards and file name chosen when Export was tapped, written once a destination is picked. */
    private var pendingExport: List<ReviewBrowserCard>? = null
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val cards = pendingExport
        pendingExport = null
        if (uri != null && cards != null) exportCards(cards, uri)
    }

    private val adapter by lazy {
        // A tap opens a card; a long press starts selecting, and taps then add or remove cards.
        ReviewCardBrowserAdapter(::dueText, onCardClicked = { item ->
            if (selectedIds.isEmpty()) openDetails(item) else toggleSelection(item)
        }, onCardLongClicked = ::toggleSelection)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewCardBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        onBackPressedDispatcher.addCallback(this, searchBackCallback)
        onBackPressedDispatcher.addCallback(this, selectionBackCallback)

        deckScope = intent.getStringExtra(EXTRA_PDF_STEM)?.takeIf(String::isNotBlank)
        selectedProjectId = savedInstanceState?.getString(STATE_PROJECT)
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        searchExpanded = savedInstanceState?.getBoolean(STATE_SEARCH_EXPANDED) ?: query.isNotEmpty()
        selectedIds.addAll(savedInstanceState?.getStringArrayList(STATE_SELECTION).orEmpty())
        filter = reviewCardBrowserFilterFromStoredName(savedInstanceState?.getString(STATE_FILTER))
            ?: reviewCardBrowserFilterFromStoredName(intent.getStringExtra(EXTRA_FILTER))
            ?: ReviewCardBrowserFilter.ALL
        sort = savedInstanceState?.getString(STATE_SORT)
            ?.let { runCatching { ReviewCardBrowserSort.valueOf(it) }.getOrNull() }
            ?: ReviewCardBrowserSort.DUE
        sortDescending = savedInstanceState?.getBoolean(STATE_SORT_DESCENDING, sort.defaultDescending()) ?: sort.defaultDescending()

        title = deckScope?.let { getString(R.string.review_card_browser_deck_title, it) }
            ?: getString(R.string.review_card_browser_title)
        reviewStore = ReviewStore(applicationContext)
        pendingObserver = PendingChangesFileObserver(
            listOf(
                ReviewStore.defaultRoot(),
                ReviewStore.defaultIncomingRoot(),
                ReviewStore.defaultOutgoingEventsRoot(),
                ReviewStore.defaultOutgoingCardsRoot(),
            ),
        )

        binding.reviewCardBrowserList.apply {
            layoutManager = LinearLayoutManager(this@ReviewCardBrowserActivity)
            adapter = this@ReviewCardBrowserActivity.adapter
            itemAnimator = null
        }
        binding.reviewCardBrowserColumnHeader.visibility = if (
            resources.getBoolean(R.bool.review_card_browser_table_layout)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.reviewCardBrowserHeaderDivider.visibility = binding.reviewCardBrowserColumnHeader.visibility
        val columnPreferences = getSharedPreferences(COLUMN_PREFERENCES, Context.MODE_PRIVATE)
        val savedColumns = columnPreferences.getStringSet(VISIBLE_COLUMNS, null)
        val savedOrder = columnPreferences.getString(COLUMN_ORDER, null)
            ?.split(',')?.mapNotNull { name -> ReviewCardBrowserColumn.entries.firstOrNull { it.name == name } }
            ?.distinct().orEmpty()
        columnOrder = savedOrder + ReviewCardBrowserColumn.entries.filter { it !in savedOrder }
        val selected = ReviewCardBrowserColumn.entries
            .filter { if (savedColumns == null) it in ReviewCardBrowserColumn.defaults else it.name in savedColumns }
            .ifEmpty { ReviewCardBrowserColumn.entries.filter { it in ReviewCardBrowserColumn.defaults } }
        visibleColumns = columnOrder.filter { it in selected }
        columnHeaders().forEach { (column, header) ->
            header.isFocusable = true
            header.setOnClickListener {
                val next = column.sort()
                sortDescending = if (sort == next) !sortDescending else false
                sort = next
                updateControlLabels()
                renderCards(preserveScroll = true)
            }
            header.setOnLongClickListener {
                showColumnsDialog()
                true
            }
        }
        binding.reviewCardBrowserColumnHeader.setOnLongClickListener {
            showColumnsDialog()
            true
        }
        updateColumnVisibility()
        binding.reviewCardBrowserRetry.setOnClickListener { loadCards(showProgress = true) }
        binding.reviewCardBrowserSelectAll.setOnClickListener {
            val ids = shownCards.map { it.card.id }
            if (selectedIds.containsAll(ids)) selectedIds.clear() else selectedIds.addAll(ids)
            updateSelection()
        }
        binding.reviewCardBrowserDelete.setOnClickListener { confirmDeleteSelection() }
        binding.reviewCardBrowserRetire.setOnClickListener { retireSelection(retire = true) }
        binding.reviewCardBrowserRestore.setOnClickListener { retireSelection(retire = false) }
        updateSelection()
        updateControlLabels()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PendingChangesSignal.generation.drop(1).collectLatest {
                    delay(INVALIDATION_DEBOUNCE_MS)
                    loadCards(showProgress = false)
                }
            }
        }
        loadCards(showProgress = true)
    }

    override fun onStart() {
        super.onStart()
        pendingObserverStartJob?.cancel()
        pendingObserverStartJob = lifecycleScope.launch(Dispatchers.IO) {
            pendingObserver.start()
        }
        sourceObserver = sourceIndex.observe {
            PendingChangesSignal.notifyChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        if (loaded) loadCards(showProgress = false)
    }

    override fun onStop() {
        pendingObserverStartJob?.cancel()
        pendingObserverStartJob = null
        sourceObserver?.stopWatching()
        sourceObserver = null
        pendingObserver.stop()
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (searchExpanded) {
            searchMenuItem?.collapseActionView()
            return true
        }
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.review_card_browser_menu, menu)
        val searchItem = menu.findItem(R.id.reviewCardBrowserSearchAction)
        searchMenuItem = searchItem
        val searchView = searchItem.actionView as SearchView
        searchView.id = R.id.reviewCardBrowserSearch
        searchView.queryHint = getString(R.string.search)
        searchView.maxWidth = Int.MAX_VALUE
        searchView.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                query = newText
                renderCards()
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchExpanded = true
                updateSearchChrome()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchExpanded = false
                query = ""
                updateSearchChrome()
                renderCards()
                return true
            }
        })
        if (searchExpanded) {
            searchItem.expandActionView()
            searchView.setQuery(query, false)
            searchView.clearFocus()
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.reviewCardBrowserManageColumns -> {
            showColumnsDialog()
            true
        }
        R.id.reviewCardBrowserFilterAction -> {
            showFilterDialog()
            true
        }
        R.id.reviewCardBrowserExportAction -> {
            startExport()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_SELECTION, ArrayList(selectedIds))
        outState.putString(STATE_QUERY, query)
        outState.putBoolean(STATE_SEARCH_EXPANDED, searchExpanded)
        outState.putString(STATE_FILTER, filter.name)
        outState.putString(STATE_PROJECT, selectedProjectId)
        outState.putString(STATE_SORT, sort.name)
        outState.putBoolean(STATE_SORT_DESCENDING, sortDescending)
        super.onSaveInstanceState(outState)
    }

    private fun loadCards(showProgress: Boolean) {
        if (!loaded) {
            binding.reviewCardBrowserProgress.visibility = View.VISIBLE
            binding.reviewCardBrowserMessagePanel.visibility = View.VISIBLE
            binding.reviewCardBrowserMessage.setText(R.string.review_card_browser_loading)
            binding.reviewCardBrowserRetry.visibility = View.GONE
        } else if (showProgress) {
            binding.reviewCardBrowserProgress.visibility = View.VISIBLE
        }
        cardLoader.request()
    }

    private suspend fun readCards(): CardBrowserLoad = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        // snapshot() checks the index identities; forced invalidation discarded that cache.
        val snapshot = sourceIndex.snapshot()
        val canonicalHashes = snapshot.libraryPdfSha256ByFilename.takeIf {
            snapshot.libraryAvailable && snapshot.generationCurrent
        }
        val indexed = SystemClock.elapsedRealtime()
        val cards = reviewStore.browserCards(canonicalPdfSha256ByFilename = canonicalHashes)
            .filter { deckScope == null || it.card.pdfStem == deckScope }
        coroutineContext.ensureActive()
        val cardsRead = SystemClock.elapsedRealtime()
        val filenames = cards.mapTo(hashSetOf()) { "${it.card.pdfStem}.pdf" }
        val documentNames = ReviewDocumentNames(
            LibraryProjectStore(applicationContext).projects(),
            sourceBundles.librarySourceMetadataFor(filenames)?.pdfs.orEmpty(),
            snapshot.libraryDisplayNamesByFilename,
        )
        val projects = documentNames.projectsFor(filenames)
        coroutineContext.ensureActive()
        val metadataRead = SystemClock.elapsedRealtime()
        val namedCards = cards.map { item ->
            coroutineContext.ensureActive()
            item.copy(documentDisplayName = documentNames.name(item.card),
                projectDisplayName = documentNames.projectName(item.card))
        }
        Log.d("CardBrowserPerformance", "Loaded ${cards.size} cards from ${filenames.size} PDFs: " +
            "index=${indexed - started}ms records=${cardsRead - indexed}ms " +
            "metadata=${metadataRead - cardsRead}ms names=${SystemClock.elapsedRealtime() - metadataRead}ms " +
            "total=${SystemClock.elapsedRealtime() - started}ms")
        CardBrowserLoad(namedCards, projects)
    }

    private fun renderCards(preserveScroll: Boolean = false) {
        if (!loaded) return
        val list = binding.reviewCardBrowserList
        val layoutManager = list.layoutManager as LinearLayoutManager
        val firstVisiblePosition = if (preserveScroll) {
            layoutManager.findFirstVisibleItemPosition()
        } else RecyclerView.NO_POSITION
        val firstVisibleOffset = if (firstVisiblePosition != RecyclerView.NO_POSITION) {
            layoutManager.findViewByPosition(firstVisiblePosition)?.top?.minus(list.paddingTop) ?: 0
        } else 0
        val result = reviewCardBrowserResult(projectCards(), deckScope, query, filter, sort, sortDescending) {
            it.stateLabel(this)
        }
        val activeFilter = listOfNotNull(
            selectedProject()?.let(::projectLabel),
            filter.takeIf { it != ReviewCardBrowserFilter.ALL }?.let { getString(it.labelRes()) },
        ).joinToString(" · ")
        // The count, the empty message, the column widths and the selection change when the list
        // shows these cards, not when it is handed them: until then the previous cards stay on screen.
        display.show(result) {
            updateColumnWidths()
            selectedIds.retainAll(shownCards.map { it.card.id }.toSet())
            if (firstVisiblePosition != RecyclerView.NO_POSITION && result.cards.isNotEmpty()) {
                layoutManager.scrollToPositionWithOffset(
                    firstVisiblePosition.coerceAtMost(result.cards.lastIndex),
                    firstVisibleOffset,
                )
            }
            updateSelection()
            // The count sits under the title in the toolbar.
            supportActionBar?.subtitle = if (activeFilter.isEmpty()) {
                getString(R.string.review_card_browser_shown, result.cards.size, result.totalInScope)
            } else {
                getString(R.string.review_card_browser_shown_filtered, result.cards.size, result.totalInScope, activeFilter)
            }
            val message = when {
                result.cards.isNotEmpty() -> null
                result.totalInScope == 0 -> getString(R.string.review_card_browser_empty)
                else -> getString(R.string.review_card_browser_no_matches)
            }
            binding.reviewCardBrowserMessagePanel.visibility = if (message == null) View.GONE else View.VISIBLE
            binding.reviewCardBrowserMessage.text = message
            binding.reviewCardBrowserRetry.visibility = View.GONE
        }
    }

    private fun updateSelection() {
        adapter.setSelectedIds(selectedIds)
        selectionBackCallback.isEnabled = selectedIds.isNotEmpty()
        binding.reviewCardBrowserSelectAll.apply {
            // Selection actions appear only while cards are selected.
            visibility = if (selectedIds.isEmpty()) View.GONE else View.VISIBLE
            isEnabled = shownCards.isNotEmpty() && !deleting
            setText(if (shownCards.isNotEmpty() && selectedIds.size == shownCards.size)
                R.string.review_card_browser_clear_selection else R.string.review_card_browser_select_all)
        }
        binding.reviewCardBrowserSelectionCount.text = getString(
            R.string.review_card_browser_selected_count, selectedIds.size)
        binding.reviewCardBrowserSelectionCount.visibility = if (selectedIds.isEmpty()) View.GONE else View.VISIBLE
        binding.reviewCardBrowserDelete.visibility = if (selectedIds.isEmpty()) View.GONE else View.VISIBLE
        binding.reviewCardBrowserDelete.isEnabled = !deleting && shownCards.any {
            it.card.id in selectedIds && it.deleteEventId == null
        }
        // Retire for the selected active cards, Restore to review for the selected retired ones.
        val (retireIds, restoreIds) = reviewRetireSelection(shownCards, selectedIds)
        binding.reviewCardBrowserRetire.visibility = if (retireIds.isEmpty()) View.GONE else View.VISIBLE
        binding.reviewCardBrowserRetire.isEnabled = !deleting
        binding.reviewCardBrowserRestore.visibility = if (restoreIds.isEmpty()) View.GONE else View.VISIBLE
        binding.reviewCardBrowserRestore.isEnabled = !deleting
        updateSearchChrome()
    }

    private fun updateSearchChrome() {
        searchBackCallback.isEnabled = searchExpanded
        binding.reviewCardBrowserSelectionControls.visibility =
            if (searchExpanded && selectedIds.isEmpty()) View.GONE else View.VISIBLE
        binding.reviewCardBrowserSelectionCount.visibility =
            if (selectedIds.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmDeleteSelection() {
        val ids = shownCards.filter { it.card.id in selectedIds && it.deleteEventId == null }.map { it.card.id }
        if (ids.isEmpty() || deleting) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.review_card_browser_delete_title, ids.size))
            .setMessage(R.string.review_card_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleting = true
                updateSelection()
                lifecycleScope.launch {
                    try {
                        val results = withContext(Dispatchers.IO) {
                            ids.map { id -> id to reviewStore.deleteCard(id) }
                        }
                        selectedIds.removeAll(results.filter { it.second.isSuccess }.map { it.first }.toSet())
                        val failures = results.count { it.second.isFailure }
                        if (failures > 0) {
                            MaterialAlertDialogBuilder(this@ReviewCardBrowserActivity)
                                .setMessage(getString(R.string.review_card_browser_delete_failed, failures))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        loadCards(showProgress = false)
                    } finally {
                        deleting = false
                        updateSelection()
                    }
                }
            }
            .show()
    }

    /** Retires (or restores) the selected cards at once; no confirmation, since either can be undone. */
    private fun retireSelection(retire: Boolean) {
        val (retireIds, restoreIds) = reviewRetireSelection(shownCards, selectedIds)
        val ids = if (retire) retireIds else restoreIds
        if (ids.isEmpty() || deleting) return
        deleting = true
        updateSelection()
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    ids.map { id -> id to (if (retire) reviewStore.retire(id) else reviewStore.restore(id)) }
                }
                selectedIds.removeAll(results.filter { it.second.isSuccess }.map { it.first }.toSet())
                val failures = results.count { it.second.isFailure }
                if (failures > 0) {
                    MaterialAlertDialogBuilder(this@ReviewCardBrowserActivity)
                        .setMessage(getString(R.string.review_card_browser_retire_failed, failures))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    AppSnackbar.make(binding.root, getString(
                        if (retire) R.string.review_card_browser_retired_count else R.string.review_card_browser_restored_count,
                        ids.size), Snackbar.LENGTH_LONG).show()
                }
                loadCards(showProgress = false)
            } finally {
                deleting = false
                updateSelection()
            }
        }
    }

    private fun showLoadFailure(error: Throwable) {
        loaded = false
        display.clear()
        selectedIds.clear()
        updateSelection()
        supportActionBar?.subtitle = null
        binding.reviewCardBrowserMessagePanel.visibility = View.VISIBLE
        binding.reviewCardBrowserMessage.text = getString(
            R.string.review_card_browser_load_failed_detail,
            error.message?.lineSequence()?.firstOrNull().orEmpty()
                .ifBlank { error.javaClass.simpleName },
        )
        binding.reviewCardBrowserRetry.visibility = View.VISIBLE
    }

    private fun columnHeaders() = mapOf(
        ReviewCardBrowserColumn.CREATED to binding.reviewCardBrowserHeaderCreated,
        ReviewCardBrowserColumn.LAST_REVIEWED to binding.reviewCardBrowserHeaderLastReviewed,
        ReviewCardBrowserColumn.QUESTION to binding.reviewCardBrowserHeaderQuestion,
        ReviewCardBrowserColumn.DUE to binding.reviewCardBrowserHeaderDue,
        ReviewCardBrowserColumn.DECK to binding.reviewCardBrowserHeaderDeck,
        ReviewCardBrowserColumn.DOCUMENT to binding.reviewCardBrowserHeaderDocument,
        ReviewCardBrowserColumn.PAGE to binding.reviewCardBrowserHeaderPage,
        ReviewCardBrowserColumn.SOURCE to binding.reviewCardBrowserHeaderSource,
        ReviewCardBrowserColumn.STATE to binding.reviewCardBrowserHeaderState,
        ReviewCardBrowserColumn.REPETITIONS to binding.reviewCardBrowserHeaderRepetitions,
    )

    private fun updateColumnVisibility() {
        val headers = columnHeaders()
        val container = binding.reviewCardBrowserColumnHeader
        visibleColumns.forEach { column ->
            val header = headers.getValue(column)
            container.removeView(header)
            container.addView(header)
        }
        headers.forEach { (column, header) ->
            header.visibility = if (column in visibleColumns) View.VISIBLE else View.GONE
        }
        adapter.setVisibleColumns(visibleColumns)
        updateColumnWidths()
    }

    private var measuringRow: ItemReviewBrowserCardBinding? = null

    /**
     * Sizes each visible column to its longest word, so a header or value never breaks mid-word,
     * and lets short values stay on one line up to [COLUMN_TARGET_DP]. Columns that fit share the
     * spare width; otherwise the table keeps these widths and scrolls sideways.
     */
    private fun updateColumnWidths() {
        val table = binding.reviewCardBrowserTable
        if (table.width == 0) {
            table.post { if (table.width > 0) updateColumnWidths() }
            return
        }
        val row = measuringRow ?: ItemReviewBrowserCardBinding.inflate(layoutInflater).also { measuringRow = it }
        val cells = mapOf(
            ReviewCardBrowserColumn.CREATED to row.reviewCardCreated,
            ReviewCardBrowserColumn.LAST_REVIEWED to row.reviewCardLastReviewed,
            ReviewCardBrowserColumn.QUESTION to row.reviewCardQuestion,
            ReviewCardBrowserColumn.DUE to row.reviewCardDue,
            ReviewCardBrowserColumn.DECK to row.reviewCardDeck,
            ReviewCardBrowserColumn.DOCUMENT to row.reviewCardDocument,
            ReviewCardBrowserColumn.PAGE to row.reviewCardPage,
            ReviewCardBrowserColumn.SOURCE to row.reviewCardSource,
            ReviewCardBrowserColumn.STATE to row.reviewCardState,
            ReviewCardBrowserColumn.REPETITIONS to row.reviewCardRepetitions,
        )
        val density = resources.displayMetrics.density
        val headers = columnHeaders()
        val widths = visibleColumns.associateWith { column ->
            val header = headers.getValue(column)
            val cell = cells.getValue(column)
            val target = (if (column == ReviewCardBrowserColumn.QUESTION) QUESTION_TARGET_DP else COLUMN_TARGET_DP) * density
            fun width(view: android.widget.TextView, text: String) =
                reviewColumnTextWidth(text, view.paint::measureText, target - view.paddingStart - view.paddingEnd) +
                    view.paddingStart + view.paddingEnd
            val values = shownCards.maxOfOrNull { width(cell, adapter.cellText(this, it, column)) } ?: 0f
            kotlin.math.ceil(maxOf(width(header, header.text.toString()), values)).toInt() + 1
        }
        val stretch = widths.values.sum() <= table.width
        binding.reviewCardBrowserTableContent.layoutParams = binding.reviewCardBrowserTableContent.layoutParams.apply {
            width = if (stretch) table.width else widths.values.sum()
        }
        headers.forEach { (column, header) -> ReviewCardBrowserAdapter.applyColumnWidth(header, widths[column], stretch) }
        adapter.setColumnWidths(widths, stretch)
    }

    private fun showColumnsDialog() {
        val sample = shownCards.firstOrNull()
        val previews = ReviewCardBrowserColumn.entries.map { column ->
            if (sample == null) "" else when (column) {
                ReviewCardBrowserColumn.CREATED -> sample.createdAt?.atZone(ZoneId.systemDefault())
                    ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "—"
                ReviewCardBrowserColumn.LAST_REVIEWED -> sample.lastReviewedAt?.atZone(ZoneId.systemDefault())
                    ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "—"
                ReviewCardBrowserColumn.QUESTION -> sample.card.question
                ReviewCardBrowserColumn.DUE -> dueText(sample)
                ReviewCardBrowserColumn.DECK -> sample.projectDisplayName.ifBlank { "—" }
                ReviewCardBrowserColumn.DOCUMENT -> sample.documentDisplayName
                ReviewCardBrowserColumn.PAGE -> sample.card.pagesLabel
                ReviewCardBrowserColumn.SOURCE -> sample.detailSourceFiles()
                    .map { it.substringAfterLast('/') }.distinct().joinToString(", ").ifBlank { "—" }
                ReviewCardBrowserColumn.STATE -> sample.stateLabel(this)
                ReviewCardBrowserColumn.REPETITIONS -> sample.cardStatusLabel()
            }
        }
        columnsLauncher.launch(ManageReviewColumnsActivity.intent(this, visibleColumns, columnOrder, previews))
    }

    private fun selectedProject(): ReviewBrowserProject? = projectChoices.firstOrNull { it.id == selectedProjectId }

    private fun projectCards(projectId: String? = selectedProjectId): List<ReviewBrowserCard> {
        val project = projectChoices.firstOrNull { it.id == projectId }
        return if (project == null) allCards else allCards.filter(project::contains)
    }

    /** A project and one card state, chosen together and applied by Select. */
    private fun showFilterDialog() {
        var chosenProjectId = selectedProjectId
        var chosenFilter = filter
        val view = layoutInflater.inflate(R.layout.dialog_review_card_filter, null)
        // A browser opened on one document has no project to choose.
        val choices = projectChoices
        view.findViewById<View>(R.id.reviewFilterProjectLayout).visibility =
            if (deckScope == null && choices.isNotEmpty()) View.VISIBLE else View.GONE
        view.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.reviewFilterProject).apply {
            val labels = listOf(getString(R.string.review_card_browser_all_projects)) + choices.map(::projectLabel)
            setAdapter(ArrayAdapter(this@ReviewCardBrowserActivity, android.R.layout.simple_dropdown_item_1line, labels))
            setText(labels[choices.indexOfFirst { it.id == chosenProjectId } + 1], false)
            setOnItemClickListener { _, _, position, _ -> chosenProjectId = choices.getOrNull(position - 1)?.id }
        }
        val states = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.reviewFilterStates)
        reviewCardBrowserVisibleFilters.forEach { choice ->
            states.addView(com.google.android.material.chip.Chip(this).apply {
                id = View.generateViewId()
                text = getString(choice.labelRes())
                isCheckable = true
                isChecked = choice == chosenFilter
                setOnCheckedChangeListener { _, checked -> if (checked) chosenFilter = choice }
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_card_browser_filter_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.review_card_browser_select) { _, _ ->
                selectedProjectId = chosenProjectId
                filter = chosenFilter
                renderCards()
            }
            .show()
    }

    /** Exports the selected cards, or every card the search and filters show, as CSV. */
    private fun startExport() {
        val cards = shownCards.filter { selectedIds.isEmpty() || it.card.id in selectedIds }
        if (cards.isEmpty()) {
            AppSnackbar.make(binding.root, R.string.review_card_browser_export_empty, Snackbar.LENGTH_SHORT).show()
            return
        }
        pendingExport = cards
        val scope = deckScope ?: selectedProject()?.let(::projectLabel)
        exportLauncher.launch(reviewCardCsvFileName(scope, LocalDate.now()))
    }

    private fun exportCards(cards: List<ReviewBrowserCard>, destination: Uri) {
        val progress = AppSnackbar.make(
            binding.root,
            getString(R.string.review_card_browser_exporting, cards.size),
            Snackbar.LENGTH_INDEFINITE,
        )
        progress.show()
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = sourceIndex.snapshot()
                    val canonicalHashes = snapshot.libraryPdfSha256ByFilename.takeIf {
                        snapshot.libraryAvailable && snapshot.generationCurrent
                    }
                    val withAnswers = ReviewAnswerTextLoader(applicationContext).populate(cards, canonicalHashes)
                    val csv = reviewCardCsv(
                        listOf(
                            R.string.review_card_browser_question, R.string.review_card_browser_answer,
                            R.string.review_card_browser_deck, R.string.review_card_browser_document,
                            R.string.review_card_browser_page, R.string.review_card_browser_due,
                            R.string.review_card_browser_state, R.string.review_card_browser_repetitions,
                            R.string.review_card_browser_tags, R.string.review_card_browser_card_id,
                        ).map(::getString),
                        withAnswers.map { item ->
                            listOf(
                                item.card.question.trim(),
                                item.answerText.trim(),
                                item.projectDisplayName,
                                item.documentDisplayName,
                                item.card.pdfPage?.toString().orEmpty(),
                                dueText(item),
                                item.stateLabel(this@ReviewCardBrowserActivity),
                                item.cardStatusLabel(),
                                item.currentTags().orEmpty().joinToString(", ") {
                                    reviewTagLabel(this@ReviewCardBrowserActivity, it)
                                },
                                item.card.id,
                            )
                        },
                    )
                    (contentResolver.openOutputStream(destination, "wt")
                        ?: contentResolver.openOutputStream(destination, "w")
                        ?: throw IOException("Could not open the selected export destination"))
                        .use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            progress.dismiss()
            result.onSuccess {
                AppSnackbar.make(
                    binding.root,
                    getString(R.string.review_card_browser_export_complete, cards.size),
                    Snackbar.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                Log.e(TAG, "Could not export cards", error)
                val detail = error.message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { error.javaClass.simpleName }
                AppSnackbar.make(
                    binding.root,
                    getString(R.string.review_card_browser_export_failed, detail),
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun projectLabel(project: ReviewBrowserProject): String =
        if (projectChoices.count { it.name == project.name } > 1) "${project.name} (${project.id})"
        else project.name

    private fun updateControlLabels() {
        columnHeaders().forEach { (column, header) ->
            val active = column.sort() == sort
            val label = getString(column.labelRes)
            header.text = label + if (active) { if (sortDescending) " ↓" else " ↑" } else ""
            header.contentDescription = getString(
                if (!active) R.string.review_sort_column else if (sortDescending) R.string.review_sorted_descending else R.string.review_sorted_ascending,
                label,
            )
        }
    }

    private fun dueText(item: ReviewBrowserCard): String = when {
        item.state == ReviewBrowserState.RETIRED -> item.retiredLabel(this).orEmpty()
        item.card.schedule?.retained == true -> getString(R.string.review_action_never)
        item.currentMark() == null && item.dueAt == null -> getString(R.string.review_card_browser_new_due)
        item.dueAt == null -> getString(R.string.review_card_browser_no_due)
        else -> DATE_FORMATTER.format(item.dueAt.atZone(ZoneId.systemDefault()).toLocalDate())
    }

    private fun openDetails(item: ReviewBrowserCard) {
        startActivity(ReviewCardDetailsActivity.intent(this, item.card.id))
    }

    companion object {
        private const val TAG = "ReviewCardBrowser"
        /** Widest a value grows on one line before it wraps at spaces, in dp. */
        private const val COLUMN_TARGET_DP = 128f
        private const val QUESTION_TARGET_DP = 240f
        private const val COLUMN_PREFERENCES = "review_card_browser"
        private const val VISIBLE_COLUMNS = "visible_columns"
        private const val COLUMN_ORDER = "column_order"
        private const val EXTRA_PDF_STEM = "review_card_browser_pdf_stem"
        private const val EXTRA_FILTER = "review_card_browser_initial_filter"
        private const val STATE_SELECTION = "review_card_browser_selection"
        private const val STATE_QUERY = "review_card_browser_query"
        private const val STATE_SEARCH_EXPANDED = "review_card_browser_search_expanded"
        private const val STATE_FILTER = "review_card_browser_filter"
        private const val STATE_PROJECT = "review_card_browser_project"
        private const val STATE_SORT = "review_card_browser_sort"
        private const val STATE_SORT_DESCENDING = "review_card_browser_sort_descending"
        private const val INVALIDATION_DEBOUNCE_MS = 120L
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

        fun intent(
            context: Context,
            pdfStem: String? = null,
            initialFilter: ReviewCardBrowserFilter? = null,
        ): Intent =
            Intent(context, ReviewCardBrowserActivity::class.java).apply {
                pdfStem?.let { putExtra(EXTRA_PDF_STEM, it) }
                initialFilter?.let { putExtra(EXTRA_FILTER, it.name) }
            }
    }
}
