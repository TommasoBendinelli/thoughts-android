// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.text.format.DateUtils
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.review.MentalModelActivity
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewDeckSummary
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewLibrarySummary
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKinds
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewQueueCounts
import com.gitlab.mudlej.MjPdfReader.databinding.PageHomeReviewBinding
import java.text.NumberFormat

internal sealed interface ReviewTabLoadState {
    val summary: ReviewLibrarySummary?

    data object Loading : ReviewTabLoadState {
        override val summary: ReviewLibrarySummary? = null
    }

    data class Refreshing(
        override val summary: ReviewLibrarySummary,
    ) : ReviewTabLoadState

    data class Loaded(
        override val summary: ReviewLibrarySummary,
    ) : ReviewTabLoadState

    data class Failed(
        override val summary: ReviewLibrarySummary?,
    ) : ReviewTabLoadState
}

internal fun ReviewTabLoadState.beginRefresh(): ReviewTabLoadState = summary?.let {
    ReviewTabLoadState.Refreshing(it)
} ?: ReviewTabLoadState.Loading

internal fun ReviewTabLoadState.failRefresh(): ReviewTabLoadState =
    ReviewTabLoadState.Failed(summary)

class ReviewTabController(
    /** Starts the session of one PDF (every kind of card). */
    private val onStartReview: (String?) -> Unit,
    /** Starts a Library-wide session from one review entry: `knowledge` (Study) or `project` (Projects, null: all Projects). */
    private val onStartEntry: (kind: String, projectId: String?) -> Unit = { _, _ -> onStartReview(null) },
    private val onBrowseCards: (String?) -> Unit,
    private val onExportQaPdf: (String) -> Unit,
    private val onNavigationChanged: () -> Unit,
    private val onHideSearch: () -> Unit,
    private val onBrowseUnavailable: () -> Unit = { onBrowseCards(null) },
) {
    private var binding: PageHomeReviewBinding? = null
    private var loadState: ReviewTabLoadState = ReviewTabLoadState.Loading
    private var selectedPdfStem: String? = null
    /** The one Project the Projects entry is narrowed to; null for All Projects. */
    private var projectScope: String? = null
    private var searchQuery = ""

    private val searchAdapter = ReviewDeckAdapter { deck ->
        onHideSearch()
        showDeck(deck)
    }

    fun attach(swipeRefresh: SwipeRefreshLayout) {
        val pageBinding = PageHomeReviewBinding.bind(swipeRefresh)
        binding = pageBinding
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            if (pageBinding.reviewDashboard.isVisible) {
                pageBinding.reviewDashboard.canScrollVertically(-1)
            } else {
                pageBinding.reviewDeckOverview.canScrollVertically(-1)
            }
        }
        pageBinding.reviewStartStudyButton.setOnClickListener { onStartEntry(ReviewKinds.KNOWLEDGE, null) }
        pageBinding.reviewStartProjectsButton.setOnClickListener { onStartEntry(ReviewKinds.PROJECT, projectScope) }
        pageBinding.reviewProjectsScopeButton.setOnClickListener { chooseProjectScope() }
        pageBinding.reviewBrowseAllButton.setOnClickListener { onBrowseCards(null) }
        // The Mental Model page is served by the card-pilot site the build names; a build without one has no button.
        pageBinding.reviewMentalModelButton.isVisible = MentalModelActivity.isAvailable
        pageBinding.reviewMentalModelButton.setOnClickListener {
            it.context.startActivity(MentalModelActivity.intent(it.context))
        }
        pageBinding.reviewRepairStatus.setOnClickListener { onBrowseUnavailable() }
        pageBinding.reviewDeckBackButton.setOnClickListener { showDashboard() }
        pageBinding.reviewStartDeckButton.setOnClickListener {
            selectedPdfStem?.let(onStartReview)
        }
        pageBinding.reviewBrowseDeckButton.setOnClickListener {
            selectedPdfStem?.let(onBrowseCards)
        }
        pageBinding.reviewExportQaDeckButton.setOnClickListener {
            selectedPdfStem?.let(onExportQaPdf)
        }
        renderCurrentState()
    }

    fun attachSearch(recyclerView: RecyclerView) {
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.adapter = searchAdapter
        submitSearchResults()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query.trim()
        submitSearchResults()
    }

    fun beginLoad() {
        loadState = loadState.beginRefresh()
        renderCurrentState()
    }

    fun render(newSummary: ReviewLibrarySummary) {
        loadState = ReviewTabLoadState.Loaded(newSummary)
        renderCurrentState()
    }

    fun renderLoadError() {
        loadState = loadState.failRefresh()
        renderCurrentState()
    }

    fun renderCurrentState() {
        val page = binding ?: return
        val currentSummary = loadState.summary
        if (currentSummary == null) {
            renderWithoutSummary(page)
            submitSearchResults()
            return
        }
        renderSummary(page, currentSummary)
        renderLoadStatus(page)
        submitSearchResults()
    }

    private fun renderSummary(
        page: PageHomeReviewBinding,
        newSummary: ReviewLibrarySummary,
    ) {
        val numberFormat = NumberFormat.getIntegerInstance()
        page.reviewNewCount.text = numberFormat.format(newSummary.counts.newCount)
        // Learning cards are due cards like any other; they count under Review.
        page.reviewReviewCount.text = numberFormat.format(newSummary.counts.learningCount + newSummary.counts.reviewCount)
        page.reviewTodaySummary.text = quantityText(R.plurals.review_cards_due, newSummary.counts.total)
        renderEntries(page, newSummary)
        val unavailableCount = newSummary.unavailableCount
        page.reviewRepairStatus.isVisible = unavailableCount > 0
        page.reviewRepairStatus.text = quantityText(R.plurals.review_cards_unavailable, unavailableCount)
        renderEmptyState(page, newSummary)

        val selected = selectedPdfStem?.let { stem ->
            newSummary.decks.firstOrNull { it.pdfStem == stem }
        }
        if (selectedPdfStem != null && selected == null) {
            showDashboard()
        } else if (selected != null) {
            renderDeckOverview(selected)
        }
    }

    /** "Study · N due" and "Projects · M due", M for the Project chosen (or all of them). */
    private fun renderEntries(page: PageHomeReviewBinding, summary: ReviewLibrarySummary) {
        val context = page.root.context
        val scoped = projectScope?.let { id -> summary.projects.firstOrNull { it.projectId == id } }
        if (scoped == null) projectScope = null
        page.reviewStartStudyButton.text = context.getString(R.string.review_entry_study, summary.knowledgeCounts.total)
        page.reviewStartStudyButton.isEnabled = summary.knowledgeCounts.total > 0
        val projectsDue = scoped?.counts?.total ?: summary.projectCounts.total
        page.reviewStartProjectsButton.text = context.getString(R.string.review_entry_projects, projectsDue)
        page.reviewStartProjectsButton.isEnabled = projectsDue > 0
        // The choice lists only Projects holding active project cards; without any there is nothing to narrow.
        page.reviewProjectsScopeButton.isVisible = summary.projects.isNotEmpty()
        val scopeName = scoped?.name ?: context.getString(R.string.review_entry_all_projects)
        page.reviewProjectsScopeButton.text = context.getString(R.string.review_entry_projects_scope, scopeName)
        page.reviewProjectsScopeButton.contentDescription = context.getString(R.string.review_entry_projects_scope_description, scopeName)
    }

    /** All Projects, then each Project holding active project cards, each with its due count. */
    private fun chooseProjectScope() {
        val page = binding ?: return
        val summary = loadState.summary ?: return
        val context = page.root.context
        val choices = listOf<String?>(null) + summary.projects.map { it.projectId }
        val labels = listOf(context.getString(R.string.review_entry_project_due,
            context.getString(R.string.review_entry_all_projects), summary.projectCounts.total)) +
            summary.projects.map { context.getString(R.string.review_entry_project_due, it.name, it.counts.total) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.review_entry_choose_project)
            .setSingleChoiceItems(labels.toTypedArray(), choices.indexOf(projectScope).coerceAtLeast(0)) { dialog, which ->
                projectScope = choices[which]
                dialog.dismiss()
                renderCurrentState()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderWithoutSummary(page: PageHomeReviewBinding) {
        page.reviewNewCount.setText(R.string.review_count_pending)
        page.reviewReviewCount.setText(R.string.review_count_pending)
        page.reviewTodaySummary.setText(
            if (loadState is ReviewTabLoadState.Failed) {
                R.string.review_load_failed
            } else {
                R.string.review_loading
            },
        )
        page.reviewLoadStatus.visibility = View.GONE
        page.reviewDeckLoadStatus.visibility = View.GONE
        page.reviewStartStudyButton.text = page.root.context.getString(R.string.review_entry_study, 0)
        page.reviewStartProjectsButton.text = page.root.context.getString(R.string.review_entry_projects, 0)
        page.reviewStartStudyButton.isEnabled = false
        page.reviewStartProjectsButton.isEnabled = false
        page.reviewProjectsScopeButton.visibility = View.GONE
        page.reviewRepairStatus.visibility = View.GONE
        page.reviewEmptyState.visibility = View.GONE
    }

    private fun renderLoadStatus(page: PageHomeReviewBinding) {
        val statusViews = listOf(page.reviewLoadStatus, page.reviewDeckLoadStatus)
        when (loadState) {
            is ReviewTabLoadState.Failed -> {
                statusViews.forEach { status ->
                    status.setText(R.string.review_refresh_failed_showing_previous)
                    status.visibility = View.VISIBLE
                }
            }
            ReviewTabLoadState.Loading,
            is ReviewTabLoadState.Refreshing,
            is ReviewTabLoadState.Loaded -> statusViews.forEach { it.visibility = View.GONE }
        }
    }

    fun canGoBack() = selectedPdfStem != null

    fun goBack(): Boolean {
        if (!canGoBack()) return false
        showDashboard()
        return true
    }

    private fun showDeck(deck: ReviewDeckSummary) {
        selectedPdfStem = deck.pdfStem
        renderDeckOverview(deck)
        onNavigationChanged()
    }

    private fun showDashboard() {
        selectedPdfStem = null
        binding?.reviewDashboard?.visibility = View.VISIBLE
        binding?.reviewDeckOverview?.visibility = View.GONE
        onNavigationChanged()
    }

    private fun renderDeckOverview(deck: ReviewDeckSummary) {
        val page = binding ?: return
        page.reviewDashboard.visibility = View.GONE
        page.reviewDeckOverview.visibility = View.VISIBLE
        page.reviewDeckTitle.text = deck.pdfStem
        page.reviewDeckCounts.text = countsText(deck.counts)
        page.reviewDeckTotal.text = quantityText(R.plurals.review_active_cards, deck.activeCount)
        page.reviewStartDeckButton.isEnabled = deck.counts.total > 0
        page.reviewExportQaDeckButton.isEnabled = deck.activeCount > 0
        page.reviewDeckNoDue.visibility = if (deck.counts.total == 0) View.VISIBLE else View.GONE
        page.reviewDeckNoDue.text = if (deck.nextDueAt == null) {
            page.root.context.getString(R.string.review_none_due_for_pdf)
        } else {
            page.root.context.getString(
                R.string.review_next_due,
                relativeTime(deck.nextDueAt.toEpochMilli()),
            )
        }
    }

    private fun renderEmptyState(
        page: PageHomeReviewBinding,
        reviewSummary: ReviewLibrarySummary,
    ) {
        when {
            reviewSummary.activeCount == 0 &&
                reviewSummary.unavailableCount > 0 -> {
                page.reviewEmptyState.visibility = View.VISIBLE
                page.reviewEmptyTitle.setText(R.string.review_unavailable_title)
                page.reviewEmptyMessage.setText(R.string.review_unavailable_message)
            }
            reviewSummary.activeCount == 0 -> {
                page.reviewEmptyState.visibility = View.VISIBLE
                page.reviewEmptyTitle.setText(R.string.review_no_active_title)
                page.reviewEmptyMessage.setText(R.string.review_no_active_message)
            }
            reviewSummary.counts.total == 0 -> {
                page.reviewEmptyState.visibility = View.VISIBLE
                page.reviewEmptyTitle.setText(R.string.review_none_due_title)
                page.reviewEmptyMessage.text = reviewSummary.nextDueAt?.let { dueAt ->
                    page.root.context.getString(
                        R.string.review_next_due,
                        relativeTime(dueAt.toEpochMilli()),
                    )
                } ?: page.root.context.getString(R.string.review_none_due_message)
            }
            else -> page.reviewEmptyState.visibility = View.GONE
        }
    }

    private fun submitSearchResults() {
        val summary = loadState.summary
        val filtered = if (searchQuery.isEmpty()) {
            summary?.decks.orEmpty()
        } else {
            summary?.decks.orEmpty().filter { it.pdfStem.contains(searchQuery, ignoreCase = true) }
        }
        searchAdapter.submitList(filtered)
    }

    private fun countsText(counts: ReviewQueueCounts): String {
        val context = binding?.root?.context ?: return ""
        return context.getString(
            R.string.review_counts,
            counts.newCount,
            counts.learningCount + counts.reviewCount,
        )
    }

    private fun quantityText(resId: Int, count: Int): String {
        val resources = binding?.root?.resources ?: return count.toString()
        return resources.getQuantityString(resId, count, count)
    }

    private fun relativeTime(timeMillis: Long): CharSequence = DateUtils.getRelativeTimeSpanString(
        timeMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
}
