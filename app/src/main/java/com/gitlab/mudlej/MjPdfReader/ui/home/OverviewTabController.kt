// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAuthor
import com.gitlab.mudlej.MjPdfReader.ui.notes.calendarLabel
import com.gitlab.mudlej.MjPdfReader.ui.notes.visibleFromLabel
import com.gitlab.mudlej.MjPdfReader.data.annotation.isDiaryEntry
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.pdfDateNow
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.core.io.openChatGptLink
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCreationMetadata
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogue
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSidecarStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.isAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.annotation.projectDocumentComments
import com.gitlab.mudlej.MjPdfReader.data.annotation.normalizeChatGptUrl
import com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentMutationPayload
import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewCommentDocument
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryScheduler
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceReviewComment
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.gitlab.mudlej.MjPdfReader.data.source.RemovedOutsideProposal
import com.gitlab.mudlej.MjPdfReader.data.source.MissingCommentMarker
import com.gitlab.mudlej.MjPdfReader.data.source.LocalSourceComments
import com.gitlab.mudlej.MjPdfReader.data.source.LocalSourceComments.RestoreResult.Failure as RestoreFailure
import com.gitlab.mudlej.MjPdfReader.data.source.LocalSourceComments.RestoreResult.Placement as RestorePlacement
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecision
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecisionPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.data.sync.agentProposalDecisionStates
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorConfig
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorDialog
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorDocument
import com.gitlab.mudlej.MjPdfReader.ui.comment.CommentEditorValue
import com.gitlab.mudlej.MjPdfReader.ui.source.SourceEditorActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.gson.JsonParser
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchQuery

internal class OverviewTabController(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val catalogue: CommentCatalogue,
    private val renderQueue: PhoneRenderQueue,
    private val preferences: Preferences,
    private val notesOnly: Boolean = false,
    /** Off where Home's search bar is out of reach, under the Comment issues panel. */
    private val showSearchAction: Boolean = true,
    private val onOpenComment: (HomeItem, OverviewComment) -> Unit,
    private val onDataChanged: () -> Unit,
    private val onPendingChangesChanged: () -> Unit,
    private val onHideSearch: () -> Unit,
    private val onShowSearch: () -> Unit,
) : GlobalSearchCommentSource {
    private sealed interface HistoryEntry {
        val comments: List<OverviewComment>

        data class Edit(
            val comment: OverviewComment,
            val oldValue: CommentValue,
            val newValue: CommentValue,
        ) : HistoryEntry {
            override val comments = listOf(comment)
        }

        data class Delete(
            override val comments: List<OverviewComment>,
            val serviceToken: Int,
        ) : HistoryEntry
    }

    private data class CommentValue(
        val text: String,
        val color: Int,
        val priority: CommentPriority,
        val location: CommentLocation?,
        val noteDetails: com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails? = null,
        val needsAction: Boolean = false,
    )

    private val proposalStore = OfflineSourceBundleStore(activity.applicationContext)
    private val sourceEditQueue = SourceEditQueue(activity.applicationContext)
    private val fedoraRepository = FedoraRepository(activity.applicationContext)
    private val adapter = OverviewCommentsAdapter(
        ::commentClicked,
        ::commentLongClicked,
        ::documentDisplayName,
        ::proposalDecisionLabel,
        notesOnly = notesOnly,
        commentMetadata = ::commentLocationMetadata,
    )
    private val searchAdapter = OverviewCommentsAdapter(
        ::showDetails,
        ::searchCommentLongClicked,
        ::documentDisplayName,
        ::proposalDecisionLabel,
        notesOnly = notesOnly,
        commentMetadata = ::commentLocationMetadata,
    )
    private var page: SwipeRefreshLayout? = null
    private var baseCommentDocuments: List<OverviewCommentDocument> = emptyList()
    private var documents: List<OverviewCommentDocument> = emptyList()
    private var sourceProposals: List<OfflineAgentProposal> = emptyList()
    private var proposalsById: Map<String, OfflineAgentProposal> = emptyMap()
    private var removedProposals: List<RemovedOutsideProposal> = emptyList()
    private var removedProposalsById: Map<String, RemovedOutsideProposal> = emptyMap()
    /** "Comment missing" rows by row id: markers whose comment no longer exists (Issues only). */
    private var missingMarkersById: Map<String, MissingCommentMarker> = emptyMap()
    private var sourceReviewComments: List<OfflineSourceReviewComment> = emptyList()
    private var sourceReviewCommentDocuments: Set<String> = emptySet()
    private var logicalComments: List<OfflineLogicalComment> = emptyList()
    private var sourcePdfSha256ByFilename: Map<String, String> = emptyMap()
    private var orphanedSourceCommentCount = 0
    private var sourceBundleId: String? = null
    private var proposalDecisionRows: List<ThoughtsOutboxEntity> = emptyList()
    private val confirmedAppliedOperationIds = linkedSetOf<String>()
    private var proposalDecisionStates: Map<String, AgentProposalDecision> = emptyMap()
    private var pdfItems: Map<String, HomeItem> = emptyMap()
    private var commentDestinations: List<OverviewCommentDestination> = emptyList()
    // The PDF-stem filter is no longer shown; only its Source-only choice lives on in the Document dropdown.
    private var selectedDocument = if (notesOnly) "" else preferences.getOverviewDocumentFilter()
        .takeIf { it == OVERVIEW_SOURCE_ONLY }.orEmpty()
    private var selectedProject = if (notesOnly) "" else preferences.getOverviewProjectFilter()
    private var selectedProjectDocument = if (notesOnly) "" else preferences.getOverviewProjectDocumentFilter()
    private var anchorFilter = if (notesOnly) OverviewAnchorFilter.ALL else preferences.getOverviewAnchorFilter()
    private var pdfFilter = if (notesOnly) OverviewPdfFilter.ALL else preferences.getOverviewPdfFilter()
    private var priorityFilter = if (notesOnly) OverviewPriorityFilter.ALL else preferences.getOverviewPriorityFilter()
    private var locationFilter = if (notesOnly) null else preferences.getOverviewLocationFilter()
    private var typeFilter = if (notesOnly) TYPE_ALL else preferences.getOverviewCommentTypeFilter()
        .takeIf { it in listOf(TYPE_ALL, TYPE_COMMENTS, TYPE_AGENT_REMOVE) } ?: TYPE_ALL
    private var authorFilter = ""
    /** To-dos whose "visible from" date is still ahead stay out of the list unless this is on. */
    private var showHiddenTodos = false
    private var todayCalendar: com.gitlab.mudlej.MjPdfReader.ui.notes.TodayCalendarCard? = null
    private var sortOrder = preferences.getOverviewSort()
    private var catalogueLoaded = false
    private var sourceComplete = false
    private var sourceLinkedStems: Set<String> = emptySet()
    private var documentDisplayNames: Map<String, String> = emptyMap()
    private var commentProjects = com.google.gson.JsonArray()
    private var sourceIndexAvailable = false
    private var commentDestinationIndexAvailable = false
    private var searchQuery = ""
    private var selectedDetailKey: String? = null
    private val selectedKeys = linkedSetOf<String>()
    private val history = OverviewHistory<HistoryEntry>()
    private val commentService = com.gitlab.mudlej.MjPdfReader.data.annotation.CommentService(
        activity.applicationContext,
    )
    private var snapshotSignature = ""
    private var operationPending = false
    private var detailDialog: BottomSheetDialog? = null
    private var detailDialogContent: View? = null
    private var detailDialogDismiss: (() -> Unit)? = null
    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            finishSelection()
        }
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, selectionBackCallback)
    }

    private var observing = false
    private fun startObserving() {
        if (observing) return
        observing = true
        scope.launch {
            AppDatabase.getInstance(activity.applicationContext).thoughtsSyncDao()
                .observeLocalCommentStates()
                .distinctUntilChanged()
                // Home loads the full snapshot that the first render shows; reloading it here for the
                // initial state built the same 2 s snapshot twice, concurrently, on every start.
                .drop(1)
                .collectLatest { refreshLogicalComments() }
        }
        scope.launch {
            AppDatabase.getInstance(activity.applicationContext)
                .thoughtsSyncDao()
                .observeByEntityType(AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE)
                .collectLatest(::applyProposalDecisionRows)
        }
    }

    private var followNewItems: RecyclerView.AdapterDataObserver? = null

    fun attach(swipeRefresh: SwipeRefreshLayout) {
        page = swipeRefresh
        swipeRefresh.findViewById<RecyclerView>(R.id.pageRecyclerView).apply {
            val list = this
            // ViewPager2 rebinds the same page when the tab returns; keep its scroll position.
            if (adapter !== this@OverviewTabController.adapter) {
                layoutManager = LinearLayoutManager(context)
                adapter = this@OverviewTabController.adapter
            }
            // A new item inserted above the first visible row stays hidden unless the list follows it.
            followNewItems?.let(this@OverviewTabController.adapter::unregisterAdapterDataObserver)
            followNewItems = object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    val first = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
                    if (positionStart == 0 && first in 0..itemCount) list.scrollToPosition(0)
                }
            }.also(this@OverviewTabController.adapter::registerAdapterDataObserver)
        }
        swipeRefresh.findViewById<View>(R.id.overviewFilters).visibility =
            if (notesOnly) View.GONE else View.VISIBLE
        swipeRefresh.findViewById<View>(R.id.overviewProjectSelector).setOnClickListener {
            showProjectFilterDialog()
        }
        swipeRefresh.findViewById<View>(R.id.overviewDocumentSelector).setOnClickListener {
            showDocumentFilterDialog()
        }
        swipeRefresh.findViewById<View>(R.id.overviewTypeSelector).visibility =
            if (notesOnly) View.GONE else View.VISIBLE
        swipeRefresh.findViewById<View>(R.id.overviewTypeSelector).setOnClickListener {
            showTypeFilterDialog()
        }
        setupSelectionToolbar(swipeRefresh.findViewById(R.id.overviewSelectionToolbar))
        // Every page_home_overview variant must define the container; a missing one skips the card.
        val calendarContainer = if (notesOnly) {
            swipeRefresh.findViewById<android.widget.FrameLayout?>(R.id.todoCalendarContainer)
        } else {
            null
        }
        if (calendarContainer != null) {
            val container: android.widget.FrameLayout = calendarContainer
            container.visibility = View.VISIBLE
            todayCalendar = (todayCalendar?.takeIf { it.parent == null || it.parent === container }
                ?: com.gitlab.mudlej.MjPdfReader.ui.notes.TodayCalendarCard(activity, scope)).also { card ->
                if (card.parent == null) container.addView(card)
                card.refresh()
            }
        }
        renderViews()
    }

    /** Re-reads today's calendar events, e.g. when Home returns to the front. */
    fun refreshCalendar() {
        todayCalendar?.refresh()
    }

    override fun globalSearchComments(query: GlobalSearchQuery): List<OverviewComment> =
        allComments().filter { query.matches(it.searchText + "\n" + documentDisplayName(it)) }

    override fun globalSearchDocumentName(comment: OverviewComment): String = documentDisplayName(comment)

    override fun openGlobalSearchComment(comment: OverviewComment) = showDetails(comment)

    fun attachSearch(recyclerView: RecyclerView) {
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.adapter = searchAdapter
        renderSearch()
    }

    suspend fun loadSourceSnapshot(): OfflineOverviewSourceSnapshot {
        val startedAt = System.nanoTime()
        fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000
        runCatching { com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDrafts.flush(activity.applicationContext) }
        val flushed = elapsed()
        val comments = com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity.applicationContext)
        comments.importCatalogue()
        val imported = elapsed()
        return comments.snapshot().also {
            com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log(
                "comments snapshot: drafts ${flushed} ms, import until ${imported} ms, snapshot until ${elapsed()} ms")
        }
    }

    fun render(
        newDocuments: List<OverviewCommentDocument>,
        sourceIndexSnapshot: SourceLinkedPdfIndexSnapshot,
        newCommentDestinations: List<OverviewCommentDestination>,
        incomingSourceSnapshot: OfflineOverviewSourceSnapshot,
        sourceLoaded: Boolean = true,
        sourceComplete: Boolean = true,
    ) {
        startObserving()
        val sourceSnapshot = incomingSourceSnapshot.retainingNewerLogicalComments(logicalComments)
        baseCommentDocuments = newDocuments
        val destinationsByStem = newCommentDestinations.associateBy {
            it.item.pdfStem.lowercase()
        }
        val newPdfItems = newCommentDestinations.map { it.item }
            .filter { it.pdfStem.isNotBlank() }
            .groupBy { it.pdfStem.lowercase() }
            .mapValues { (_, candidates) ->
                destinationsByStem[candidates.first().pdfStem.lowercase()]?.item
                    ?: candidates.firstOrNull { it.available }
                    ?: candidates.first()
            }
        if (sourceBundleId != sourceSnapshot.bundleId) {
            confirmedAppliedOperationIds.clear()
        }
        sourceBundleId = sourceSnapshot.bundleId
        sourceProposals = sourceSnapshot.proposals
        removedProposals = sourceSnapshot.removedProposals
        removedProposalsById = removedProposals.associateBy(RemovedOutsideProposal::id)
        proposalDecisionStates = currentProposalDecisionStates()
        val proposals = activeProposals()
        proposalsById = proposals.associateBy(OfflineAgentProposal::id)
        sourceReviewComments = sourceSnapshot.reviewComments
        logicalComments = sourceSnapshot.logicalComments
        sourcePdfSha256ByFilename = sourceIndexSnapshot.libraryPdfSha256ByFilename + sourceSnapshot.pdfSha256ByFilename
        sourceReviewCommentDocuments = newPdfItems.values
            .map { it.fileName }
            .filter(sourceSnapshot.reviewCommentDocuments::contains)
            .toSet()
        // Missing-comment rows stand on a Library document, so the Library is known before merging.
        pdfItems = newPdfItems
        val visibleDocuments = availableOverviewDocuments(
            mergeProposalDocuments(newDocuments, proposals, sourceReviewComments, sourceReviewCommentDocuments, logicalComments),
            newPdfItems.mapValues { (_, item) -> item.available },
        )
        val newSignature = signature(visibleDocuments)
        if (snapshotSignature.isNotEmpty() && newSignature != snapshotSignature &&
            !history.isEmpty
        ) {
            // Deletion validity belongs to CommentService, which reloads current records.
            // Catalogue refreshes may invalidate older snapshot-based edit history only.
            val removedEdits = history.removeIf { it is HistoryEntry.Edit }
            snackbarHost()?.takeIf { removedEdits }?.let {
                Snackbar.make(it, R.string.overview_history_invalidated, Snackbar.LENGTH_LONG).show()
            }
        }
        snapshotSignature = newSignature
        documents = visibleDocuments
        pdfItems = newPdfItems
        commentDestinations = newCommentDestinations
        sourceIndexAvailable = sourceIndexSnapshot.available
        commentDestinationIndexAvailable =
            sourceIndexSnapshot.libraryAvailable || sourceIndexSnapshot.available
        sourceLinkedStems = sourceIndexSnapshot.filenames
            .map { it.removeSuffix(".pdf").lowercase() }
            .toSet()
        // An unsupported Project catalogue must not crash Home; Sync replaces it.
        commentProjects = runCatching {
            com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore(activity).projects()
        }.getOrElse { com.google.gson.JsonArray() }
        documentDisplayNames = sourceIndexSnapshot.libraryDisplayNamesByFilename
            .mapKeys { (filename, _) -> filename.removeSuffix(".pdf").lowercase() }
        if (selectedDocument.isNotBlank() && selectedDocument != OVERVIEW_SOURCE_ONLY && documents.none { it.stem == selectedDocument }) {
            selectedDocument = ""
            if (!notesOnly) preferences.setOverviewDocumentFilter("")
        }
        val all = allComments()
        selectedKeys.retainAll(all.map(::overviewCommentKey).toSet())
        // Notes live in the source snapshot, which arrives after the library catalogue.
        catalogueLoaded = catalogueLoaded || sourceLoaded
        this.sourceComplete = this.sourceComplete || sourceComplete
        renderViews(all)
    }

    fun setSearchQuery(query: String) {
        searchQuery = query.trim()
        renderSearch()
    }

    /** Return from capture to the Notes tab with its filters reset. */
    fun showCommentIssues() {
        showNotes()
        typeFilter = TYPE_COMMENTS
        anchorFilter = OverviewAnchorFilter.NEEDS_ANCHOR
        renderViews()
    }

    fun showNotes() {
        typeFilter = TYPE_ALL
        selectedDocument = ""
        setPlaceFilters("", "")
        authorFilter = ""
        searchQuery = ""
        anchorFilter = OverviewAnchorFilter.ALL
        pdfFilter = OverviewPdfFilter.ALL
        priorityFilter = OverviewPriorityFilter.ALL
        locationFilter = null
        if (!notesOnly) preferences.setOverviewCommentTypeFilter(typeFilter)
        if (!notesOnly) preferences.setOverviewDocumentFilter(selectedDocument)
        if (!notesOnly) preferences.setOverviewAnchorFilter(anchorFilter)
        if (!notesOnly) preferences.setOverviewPdfFilter(pdfFilter)
        if (!notesOnly) preferences.setOverviewPriorityFilter(priorityFilter)
        if (!notesOnly) preferences.setOverviewLocationFilter(null)
        finishSelection()
        renderViews()
    }

    fun showActions(anchor: View) {
        PopupMenu(activity, anchor).apply {
            if (showSearchAction) menu.add(0, ACTION_SEARCH, 0, R.string.search)
            menu.add(0, ACTION_ADD_NOTE, 1, R.string.note_add)
            val drafts = com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDrafts.unfinished(activity)
            if (drafts.isNotEmpty()) menu.add(0, ACTION_RESUME_NOTE, 1, R.string.note_resume_draft)
            menu.add(0, ACTION_ADD, 2, R.string.overview_action_add)
            menu.add(0, ACTION_SORT, 2, R.string.overview_action_sort)
            menu.add(0, ACTION_FILTER, 3, R.string.overview_action_filter)
            menu.add(0, ACTION_UNDO, 4, R.string.overview_undo).isEnabled =
                history.canUndo && !operationPending
            menu.add(0, ACTION_REDO, 5, R.string.overview_redo).isEnabled =
                history.canRedo && !operationPending
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ACTION_SEARCH -> onShowSearch()
                    ACTION_ADD_NOTE -> activity.startActivity(Intent(activity, com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity::class.java))
                    ACTION_RESUME_NOTE -> MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.note_resume_draft)
                        .setItems(drafts.map { it.text.lineSequence().firstOrNull { line -> line.isNotBlank() }
                            ?.take(100) ?: activity.getString(R.string.note_untitled_draft) }.toTypedArray()) { _, index ->
                            activity.startActivity(Intent(activity, com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity::class.java)
                                .putExtra(com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity.EXTRA_DRAFT_ID, drafts[index].id))
                        }.show()
                    ACTION_ADD -> showAddCommentDialog()
                    ACTION_SORT -> showSortDialog()
                    ACTION_FILTER -> showFilterDialog()
                    ACTION_UNDO -> undo()
                    ACTION_REDO -> redo()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    fun finishSelection() {
        if (!shouldFinishOverviewSelection(selectedKeys.size, selectionBackCallback.isEnabled)) {
            return
        }
        selectedKeys.clear()
        selectionBackCallback.isEnabled = false
        page?.findViewById<Toolbar>(R.id.overviewSelectionToolbar)?.visibility = View.GONE
        page?.findViewById<View>(R.id.overviewActions)?.visibility = View.VISIBLE
        val all = allComments()
        adapter.submitList(
            filteredComments(all),
            selectedKeys,
            all.isNotEmpty(),
        )
    }

    private fun setupSelectionToolbar(toolbar: Toolbar) {
        toolbar.menu.clear()
        toolbar.menu.add(0, SELECTION_ACCEPT, 0, R.string.agent_proposal_accept).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, SELECTION_DISREGARD, 1, R.string.agent_proposal_disregard).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, SELECTION_DELETE, 2, R.string.overview_delete_selected).apply {
            setIcon(R.drawable.ic_delete)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, SELECTION_RESTORE_MISSING, 3, R.string.overview_comment_missing_restore).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.menu.add(0, SELECTION_REMOVE_MARKER, 4, R.string.overview_comment_missing_remove).apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setNavigationOnClickListener { finishSelection() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                SELECTION_ACCEPT -> resolveSelectedProposals("accept")
                SELECTION_DISREGARD -> resolveSelectedProposals("disregard")
                SELECTION_DELETE -> deleteSelected()
                SELECTION_RESTORE_MISSING -> resolveSelectedMissingComments(restore = true)
                SELECTION_REMOVE_MARKER -> resolveSelectedMissingComments(restore = false)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        updateSelectionActions(toolbar)
    }

    private fun allComments(): List<OverviewComment> = documents
        .flatMap { it.comments }
        // Diary entries are notes too, but they live in the Diary tab, never among the To-dos.
        .filter { it.isNote == notesOnly && !(notesOnly && it.isDiaryEntry) }
        .sortedWith(overviewCommentComparator(sortOrder))

    private fun mergeProposalDocuments(
        commentDocuments: List<OverviewCommentDocument>,
        proposals: List<OfflineAgentProposal>,
        sourceComments: List<OfflineSourceReviewComment>,
        sourceCommentDocuments: Set<String>,
        logical: List<OfflineLogicalComment>,
    ): List<OverviewCommentDocument> {
        val projection = projectDocumentComments(
            commentDocuments,
            proposals,
            sourceComments,
            sourceCommentDocuments,
            logical,
            overviewPdfSha256ByFilename = sourcePdfSha256ByFilename,
        )
        orphanedSourceCommentCount = projection.orphanedSourceCommentCount
        if (notesOnly) return projection.documents
        val missing = com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.missingCommentMarkers()
        missingMarkersById = missing.associateBy(MissingCommentMarker::rowId)
        return withMissingCommentMarkers(withRemovedProposals(projection.documents, removedProposals), missing) { stem ->
            pdfItems.containsKey(stem.lowercase())
        }
    }

    private fun renderProposalDocuments() {
        val proposals = activeProposals()
        proposalsById = proposals.associateBy(OfflineAgentProposal::id)
        documents = availableOverviewDocuments(
            mergeProposalDocuments(
                baseCommentDocuments,
                proposals,
                sourceReviewComments,
                sourceReviewCommentDocuments,
                logicalComments,
            ),
            pdfItems.mapValues { (_, item) -> item.available },
        )
        snapshotSignature = signature(documents)
        val all = allComments()
        selectedKeys.retainAll(all.map(::overviewCommentKey).toSet())
        renderViews(all)
        refreshOpenDetail(all)
    }

    private fun activeProposals(): List<OfflineAgentProposal> = sourceProposals.filter { proposal ->
        proposalDecisionStates[proposal.id]?.phase != AgentProposalDecisionPhase.APPLIED
    }

    private fun applyProposalDecisionRows(rows: List<ThoughtsOutboxEntity>) {
        if (rows == proposalDecisionRows) return
        val previousStates = proposalDecisionStates
        val previousRows = proposalDecisionRows.associateBy(ThoughtsOutboxEntity::operationId)
        rows.filter { row ->
            row.deliveryState == ThoughtsOutboxEntity.STATE_ACCEPTED &&
                previousRows[row.operationId]?.deliveryState == ThoughtsOutboxEntity.STATE_PENDING
        }.mapTo(confirmedAppliedOperationIds, ThoughtsOutboxEntity::operationId)
        proposalDecisionRows = rows
        proposalDecisionStates = currentProposalDecisionStates()
        if (proposalDecisionStates == previousStates) return

        val previouslyApplied = previousStates
            .filterValues { it.phase == AgentProposalDecisionPhase.APPLIED }
            .keys
        val currentlyApplied = proposalDecisionStates
            .filterValues { it.phase == AgentProposalDecisionPhase.APPLIED }
            .keys
        if (previouslyApplied != currentlyApplied) {
            renderProposalDocuments()
        } else {
            renderProposalDecisionPresentation()
        }
        onPendingChangesChanged()

        proposalDecisionStates.entries.firstOrNull { (proposalId, decision) ->
            decision.phase == AgentProposalDecisionPhase.FAILED &&
                previousStates[proposalId]?.phase == AgentProposalDecisionPhase.PENDING
        }?.value?.error?.takeIf(String::isNotBlank)?.let { error ->
            showMessage(error, Snackbar.LENGTH_LONG)
        }
    }

    private fun currentProposalDecisionStates(): Map<String, AgentProposalDecision> =
        agentProposalDecisionStates(
            proposalDecisionRows.filter { row ->
                row.deliveryState != ThoughtsOutboxEntity.STATE_ACCEPTED ||
                    row.operationId in confirmedAppliedOperationIds
            },
            sourceProposals,
        )

    private fun renderProposalDecisionPresentation() {
        val all = allComments()
        adapter.submitList(
            filteredComments(all),
            selectedKeys,
            all.isNotEmpty(),
        )
        page?.findViewById<Toolbar>(R.id.overviewSelectionToolbar)
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.let(::updateSelectionActions)
        renderSearch(all)
        renderInlineDetail(all)
        refreshOpenDetail(all)
    }

    private fun proposalDecisionLabel(comment: OverviewComment): String? {
        if (comment.id !in proposalsById) return null
        val decision = proposalDecisionStates[comment.id] ?: return null
        return when (decision.phase) {
            AgentProposalDecisionPhase.PENDING -> activity.getString(
                if (decision.action == "accept") {
                    R.string.agent_proposal_pending_accept
                } else {
                    R.string.agent_proposal_pending_reject
                },
            )
            AgentProposalDecisionPhase.FAILED -> activity.getString(
                R.string.agent_proposal_resolution_failed,
                decision.error.ifBlank { activity.getString(R.string.home_sync_start_failed) },
            )
            AgentProposalDecisionPhase.APPLIED -> null
        }
    }

    private fun isAgentProposal(comment: OverviewComment): Boolean =
        isAgentProposal(comment, proposalsById.keys) || removedProposal(comment) != null

    /** The missing-comment marker an Issues row stands for, or null for every other row. */
    private fun missingMarker(comment: OverviewComment): MissingCommentMarker? =
        comment.takeIf { it.anchorStatus == COMMENT_MISSING_STATUS }?.let { missingMarkersById[it.id] }

    /** The removed proposal an Issues row stands for, or null for every other row. */
    private fun removedProposal(comment: OverviewComment): RemovedOutsideProposal? =
        comment.takeIf { it.anchorStatus == PROPOSAL_REMOVED_STATUS }?.let { removedProposalsById[it.id] }

    private fun filteredComments(
        all: List<OverviewComment> = allComments(),
    ): List<OverviewComment> {
        return filterOverviewComments(
            all,
            selectedDocument,
            anchorFilter,
            pdfFilter,
            sourceLinkedStems,
            sourceIndexAvailable,
            priorityFilter,
            locationFilter,
        ).filter { comment ->
            (typeFilter == TYPE_ALL ||
                typeFilter == TYPE_AGENT_REMOVE && isAgentProposal(comment) ||
                typeFilter == TYPE_NOTES && comment.isNote ||
                typeFilter == TYPE_COMMENTS && !isAgentProposal(comment) && !comment.isNote) &&
                (authorFilter.isBlank() || comment.author == authorFilter) &&
                matchesPlaceFilters(comment) &&
                !(notesOnly && !showHiddenTodos && isHiddenTodo(comment))
        }
    }

    private fun isHiddenTodo(comment: OverviewComment): Boolean =
        comment.noteDetails?.hiddenOn(java.time.LocalDate.now()) == true

    /** A comment's project and document, named as its card shows them; a blank project means none. */
    private data class CommentPlace(val project: String, val document: String)

    private fun commentPlaces(comment: OverviewComment): List<CommentPlace> {
        if (comment.isNote) return listOf(CommentPlace(documentDisplayName(comment), ""))
        return overviewCommentMetadataLocations(comment, commentProjects).flatMap { location ->
            val document = locationDocumentName(location)
            location.projects.ifEmpty { listOf("") }.map { CommentPlace(it, document) }
        }
    }

    private fun locationDocumentName(location: OverviewCommentMetadataLocation): String =
        location.documentName.ifBlank {
            if (location.requiresDocumentMapping) "" else documentDisplayNames[location.stem.lowercase()].orEmpty().ifBlank { location.stem }
        }

    private fun inProject(place: CommentPlace, project: String): Boolean =
        project.isEmpty() || place.project == if (project == OVERVIEW_NO_PROJECT) "" else project

    private fun matchesPlaceFilters(comment: OverviewComment): Boolean {
        if (selectedProject.isEmpty() && selectedProjectDocument.isEmpty()) return true
        return commentPlaces(comment).any { place ->
            inProject(place, selectedProject) &&
                (selectedProjectDocument.isEmpty() || place.document == selectedProjectDocument)
        }
    }

    private fun setPlaceFilters(project: String, document: String) {
        selectedProject = project
        selectedProjectDocument = document
        if (!notesOnly) preferences.setOverviewProjectFilter(project)
        if (!notesOnly) preferences.setOverviewProjectDocumentFilter(document)
    }

    private fun searchedComments(
        all: List<OverviewComment> = allComments(),
    ): List<OverviewComment> {
        return filteredComments(all).filter {
            searchQuery.isBlank() || it.searchText.contains(searchQuery, ignoreCase = true) ||
                documentDisplayName(it).contains(searchQuery, ignoreCase = true)
        }
    }

    private fun documentDisplayName(comment: OverviewComment): String =
        if (comment.isNote) comment.noteDetails?.projectName.orEmpty()
            .ifBlank { comment.noteDetails?.projectId.orEmpty() }
        else documentDisplayNames[comment.documentStem.lowercase()].orEmpty().ifBlank { comment.documentStem }

    private fun commentLocationMetadata(comment: OverviewComment): String {
        if (comment.isNote) return documentDisplayName(comment)
        // A multi-page edit lists each document once with its pages, not one block per page.
        val groups = overviewCommentMetadataLocations(comment, commentProjects).groupBy { location ->
            val project = location.projects.joinToString(", ").ifBlank {
                activity.getString(R.string.overview_project_unavailable)
            }
            val document = locationDocumentName(location)
                .ifBlank { activity.getString(R.string.overview_document_unavailable) }
            project to document
        }
        if (groups.isEmpty()) return ""
        return groups.entries.joinToString("\n") { (key, locations) ->
            val pages = locations.mapNotNull { it.page }
            val page = when (pages.distinct().size) {
                0 -> activity.getString(R.string.overview_page_unavailable)
                1 -> activity.getString(R.string.overview_location_page, pages.first())
                else -> activity.getString(R.string.overview_location_pages, overviewPageRanges(pages))
            }
            val projectPage = locations.singleOrNull()?.projectPage?.let { activity.getString(R.string.overview_location_page, it) }
            activity.getString(R.string.overview_location_metadata,
                if (projectPage != null) "${key.first} · $projectPage" else key.first, key.second, page)
        } + "\n" + activity.getString(
            R.string.overview_linking_metadata,
            activity.getString(overviewCommentLinkingLabel(comment, sourceLinkedStems, sourceIndexAvailable)) +
                (missingMarker(comment)?.let { " · " + missingCommentLocation(it) } ?: ""),
        )
    }

    private fun renderViews(precomputedAll: List<OverviewComment>? = null) {
        val root = page ?: return
        val all = precomputedAll ?: allComments()
        val filtered = filteredComments(all)
        // Agent edits and source comments arrive with the full snapshot; an empty list may still fill.
        val loading = !catalogueLoaded || (filtered.isEmpty() && !sourceComplete)
        adapter.submitList(
            filtered,
            selectedKeys,
            all.isNotEmpty(),
            loading = loading,
        )
        // The first cards come from the library catalogue alone; counting them would understate the total.
        val summary = if (loading || !sourceComplete) {
            activity.getString(R.string.overview_loading_title)
        } else if (notesOnly) {
            val hidden = all.count(::isHiddenTodo)
            val shown = activity.resources.getQuantityString(R.plurals.todos_summary, all.size - hidden, all.size - hidden)
            if (hidden == 0) shown else activity.getString(R.string.todos_summary_hidden, shown, hidden)
        } else if (filtered.size == all.size) {
            activity.resources.getQuantityString(
                R.plurals.overview_summary,
                all.size,
                all.size,
                activity.resources.getQuantityString(R.plurals.overview_document_count, documents.size, documents.size),
            )
        } else {
            activity.getString(R.string.overview_filtered_summary, filtered.size, all.size)
        }
        root.findViewById<TextView>(R.id.overviewSummary).text = summary
        if (notesOnly) root.findViewById<MaterialButton?>(R.id.overviewHiddenTodosToggle)?.apply {
            val hidden = if (loading || !sourceComplete) 0 else all.count(::isHiddenTodo)
            visibility = if (hidden > 0) View.VISIBLE else View.GONE
            setText(if (showHiddenTodos) R.string.todo_hide_later else R.string.todo_show_hidden)
            setOnClickListener {
                showHiddenTodos = !showHiddenTodos
                renderViews()
            }
        }
        renderFilterSelector(root, R.id.overviewProjectSelector, R.id.overviewProjectValue,
            R.string.overview_selector_project, projectFilterLabel(selectedProject))
        renderFilterSelector(root, R.id.overviewDocumentSelector, R.id.overviewDocumentValue,
            R.string.overview_selector_document, documentFilterLabel())
        renderFilterSelector(root, R.id.overviewTypeSelector, R.id.overviewTypeValue,
            R.string.overview_selector_type, typeFilterLabel(typeFilter))
        if (selectedKeys.isNotEmpty()) {
            root.findViewById<Toolbar>(R.id.overviewSelectionToolbar).title =
                activity.getString(R.string.overview_selected_count, selectedKeys.size)
        }
        renderSearch(all)
        renderInlineDetail(all)
    }

    private fun showSortDialog() {
        val choices = OverviewSortOrder.entries.toTypedArray()
        val labels = choices.map(::sortLabel).toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_sort_title)
            .setSingleChoiceItems(labels, choices.indexOf(sortOrder)) { dialog, which ->
                sortOrder = choices[which]
                if (!notesOnly) preferences.setOverviewSort(sortOrder)
                dialog.dismiss()
                finishSelection()
                renderViews()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFilterDialog() {
        val labels = arrayOf(
            activity.getString(
                R.string.overview_filter_type,
                when (typeFilter) {
                    TYPE_AGENT_REMOVE -> activity.getString(R.string.agent_proposal_type)
                    TYPE_NOTES -> activity.getString(R.string.note_type_plural)
                    TYPE_COMMENTS -> activity.getString(R.string.overview_filter_type_comments)
                    else -> activity.getString(R.string.overview_filter_all)
                },
            ),
            activity.getString(
                R.string.overview_filter_author,
                authorFilter.ifBlank { activity.getString(R.string.overview_filter_all) },
            ),
            activity.getString(
                R.string.overview_filter_document,
                documentFilterLabel(),
            ),
            activity.getString(
                R.string.overview_filter_pdf_type,
                if (sourceIndexAvailable) {
                    pdfFilterLabel(pdfFilter)
                } else {
                    activity.getString(R.string.overview_pdf_type_waiting)
                },
            ),
            activity.getString(R.string.overview_filter_status, anchorFilterLabel(anchorFilter)),
            activity.getString(
                R.string.overview_filter_priority,
                priorityFilterLabel(priorityFilter),
            ),
            activity.getString(
                R.string.overview_filter_location,
                locationFilter?.let {
                    activity.getString(
                        R.string.overview_filter_location_value,
                        it.radiusKm,
                        it.label,
                    )
                } ?: activity.getString(R.string.overview_filter_location_any),
            ),
            activity.getString(R.string.overview_filter_reset),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_filter_title)
            .setItems(labels) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    FILTER_TYPE -> showTypeFilterDialog()
                    FILTER_AUTHOR -> showAuthorFilterDialog()
                    FILTER_DOCUMENT -> showDocumentFilterDialog()
                    FILTER_PDF_TYPE -> showPdfFilterDialog()
                    FILTER_STATUS -> showAnchorFilterDialog()
                    FILTER_PRIORITY -> showPriorityFilterDialog()
                    FILTER_LOCATION -> showLocationFilterDialog()
                    FILTER_RESET -> {
                        selectedDocument = ""
                        setPlaceFilters("", "")
                        anchorFilter = OverviewAnchorFilter.ALL
                        pdfFilter = OverviewPdfFilter.ALL
                        priorityFilter = OverviewPriorityFilter.ALL
                        locationFilter = null
                        typeFilter = TYPE_ALL
                        if (!notesOnly) preferences.setOverviewCommentTypeFilter(TYPE_ALL)
                        authorFilter = ""
                        if (!notesOnly) preferences.setOverviewDocumentFilter("")
                        if (!notesOnly) preferences.setOverviewAnchorFilter(anchorFilter)
                        if (!notesOnly) preferences.setOverviewPdfFilter(pdfFilter)
                        if (!notesOnly) preferences.setOverviewPriorityFilter(priorityFilter)
                        if (!notesOnly) preferences.setOverviewLocationFilter(null)
                        finishSelection()
                        renderViews()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun typeFilterLabel(value: String): String = activity.getString(when (value) {
        TYPE_COMMENTS -> R.string.overview_filter_type_comments
        TYPE_AGENT_REMOVE -> R.string.comments_filter_agent_button
        TYPE_NOTES -> R.string.note_type_plural
        else -> R.string.overview_selector_all_types
    })

    private fun projectFilterLabel(project: String): String = when (project) {
        "" -> activity.getString(R.string.overview_selector_all_projects)
        OVERVIEW_NO_PROJECT -> activity.getString(R.string.overview_selector_no_project)
        else -> project
    }

    private fun documentFilterLabel(): String = when {
        selectedDocument == OVERVIEW_SOURCE_ONLY -> activity.getString(R.string.overview_source_only)
        selectedProjectDocument.isEmpty() -> activity.getString(R.string.overview_selector_all_documents)
        else -> selectedProjectDocument
    }

    private fun renderFilterSelector(root: View, selectorId: Int, valueId: Int, labelId: Int, value: String) {
        root.findViewById<TextView>(valueId).text = value
        root.findViewById<View>(selectorId).apply {
            contentDescription = activity.getString(
                R.string.overview_selector_description, activity.getString(labelId), value,
            )
            ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            })
        }
    }

    private fun showTypeFilterDialog() {
        if (notesOnly) return
        val anchor = page?.findViewById<View>(R.id.overviewTypeSelector) ?: return
        showOverviewFilterDropdown(
            anchor,
            activity.getString(R.string.overview_selector_type),
            listOf(TYPE_ALL, TYPE_COMMENTS, TYPE_AGENT_REMOVE)
                .map { OverviewFilterChoice(it, typeFilterLabel(it)) },
            typeFilter,
        ) { value ->
            typeFilter = value
            if (!notesOnly) preferences.setOverviewCommentTypeFilter(value)
            finishSelection()
            renderViews()
        }
    }

    private fun showAuthorFilterDialog() {
        val authors = listOf("") + allComments().map(OverviewComment::author).filter(String::isNotBlank).distinct().sorted()
        val labels = authors.map { it.ifBlank { activity.getString(R.string.overview_filter_all) } }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_filter_author_title)
            .setSingleChoiceItems(labels, authors.indexOf(authorFilter).coerceAtLeast(0)) { dialog, which ->
                authorFilter = authors[which]
                dialog.dismiss()
                finishSelection()
                renderViews()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProjectFilterDialog() {
        val anchor = page?.findViewById<View>(R.id.overviewProjectSelector) ?: return
        val places = allComments().flatMap(::commentPlaces)
        val choices = listOf(OverviewFilterChoice("", projectFilterLabel(""))) +
            places.map { it.project }.filter(String::isNotBlank).distinct()
                .map { OverviewFilterChoice(it, it) }
                .sortedBy { it.label.lowercase() } +
            listOfNotNull(OverviewFilterChoice(OVERVIEW_NO_PROJECT, projectFilterLabel(OVERVIEW_NO_PROJECT))
                .takeIf { places.any { it.project.isBlank() } })
        showOverviewFilterDropdown(
            anchor,
            activity.getString(R.string.overview_selector_choose_project),
            choices,
            selectedProject,
            searchable = true,
            searchHint = R.string.overview_selector_find_project,
            emptyText = R.string.overview_selector_no_projects,
        ) { value ->
            // Keep the chosen document only if it belongs to the new project.
            val document = selectedProjectDocument.takeIf { document ->
                places.any { inProject(it, value) && it.document == document }
            }.orEmpty()
            if (value.isNotEmpty()) selectedDocument = ""
            setPlaceFilters(value, document)
            finishSelection()
            renderViews()
        }
    }

    private fun showDocumentFilterDialog() {
        val anchor = page?.findViewById<View>(R.id.overviewDocumentSelector) ?: return
        fun key(project: String, document: String) = "$project\u0000$document"
        val places = allComments().flatMap(::commentPlaces)
            .filter { it.document.isNotBlank() && inProject(it, selectedProject) }
            .distinct()
        val choices = listOf(OverviewFilterChoice("", activity.getString(R.string.overview_selector_all_documents))) +
            places.map { place ->
                // With every project shown, name the project underneath so equal document names stay distinct.
                OverviewFilterChoice(key(place.project, place.document), place.document,
                    if (selectedProject.isEmpty()) projectFilterLabel(place.project.ifBlank { OVERVIEW_NO_PROJECT }) else "")
            }.sortedWith(compareBy({ it.label.lowercase() }, { it.detail.lowercase() })) +
            OverviewFilterChoice(OVERVIEW_SOURCE_ONLY, activity.getString(R.string.overview_source_only))
        val selected = when {
            selectedDocument == OVERVIEW_SOURCE_ONLY -> OVERVIEW_SOURCE_ONLY
            selectedProjectDocument.isEmpty() -> ""
            else -> key(if (selectedProject == OVERVIEW_NO_PROJECT) "" else selectedProject, selectedProjectDocument)
        }
        showOverviewFilterDropdown(
            anchor,
            activity.getString(R.string.overview_selector_choose_document),
            choices,
            selected,
            searchable = true,
            searchHint = R.string.overview_selector_find_document,
            emptyText = R.string.overview_selector_no_documents,
        ) { value ->
            when (value) {
                "" -> { selectedDocument = ""; setPlaceFilters(selectedProject, "") }
                OVERVIEW_SOURCE_ONLY -> { selectedDocument = OVERVIEW_SOURCE_ONLY; setPlaceFilters("", "") }
                else -> {
                    // Picking a document also picks its project.
                    val project = value.substringBefore('\u0000')
                    selectedDocument = ""
                    setPlaceFilters(project.ifEmpty { OVERVIEW_NO_PROJECT }, value.substringAfter('\u0000'))
                }
            }
            if (!notesOnly) preferences.setOverviewDocumentFilter(selectedDocument)
            finishSelection()
            renderViews()
        }
    }

    private fun showAnchorFilterDialog() {
        val choices = OverviewAnchorFilter.entries.toTypedArray()
        val labels = choices.map(::anchorFilterLabel).toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_filter_status_title)
            .setSingleChoiceItems(labels, choices.indexOf(anchorFilter)) { dialog, which ->
                anchorFilter = choices[which]
                if (!notesOnly) preferences.setOverviewAnchorFilter(anchorFilter)
                dialog.dismiss()
                finishSelection()
                renderViews()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPdfFilterDialog() {
        if (!sourceIndexAvailable) {
            showMessage(R.string.overview_pdf_type_unavailable, Snackbar.LENGTH_LONG)
            return
        }
        val choices = OverviewPdfFilter.entries.toTypedArray()
        val labels = choices.map(::pdfFilterLabel).toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_filter_pdf_type_title)
            .setSingleChoiceItems(labels, choices.indexOf(pdfFilter)) { dialog, which ->
                pdfFilter = choices[which]
                if (!notesOnly) preferences.setOverviewPdfFilter(pdfFilter)
                dialog.dismiss()
                finishSelection()
                renderViews()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPriorityFilterDialog() {
        val choices = OverviewPriorityFilter.entries.toTypedArray()
        val labels = choices.map(::priorityFilterLabel).toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_filter_priority_title)
            .setSingleChoiceItems(labels, choices.indexOf(priorityFilter)) { dialog, which ->
                priorityFilter = choices[which]
                if (!notesOnly) preferences.setOverviewPriorityFilter(priorityFilter)
                dialog.dismiss()
                finishSelection()
                renderViews()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** "Any location", the distance around the place in use, then the places that comments carry. */
    private fun showLocationFilterDialog() {
        val current = locationFilter
        val places = overviewCommentPlaces(allComments())
        val choices = mutableListOf(activity.getString(R.string.overview_filter_location_any))
        if (current != null) {
            choices += activity.getString(R.string.overview_filter_location_value, current.radiusKm, current.label)
        }
        choices += places.map(CommentLocation::label)
        val firstPlace = choices.size - places.size
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_filter_location_title)
            .setItems(choices.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                when {
                    which == 0 -> {
                        locationFilter = null
                        if (!notesOnly) preferences.setOverviewLocationFilter(null)
                        finishSelection()
                        renderViews()
                    }
                    which < firstPlace -> current?.let { showLocationRadiusDialog(it.toCommentLocation()) }
                    else -> showLocationRadiusDialog(places[which - firstPlace])
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLocationRadiusDialog(location: CommentLocation) {
        val labels = LOCATION_RADII.map {
            activity.getString(R.string.overview_filter_location_radius, it)
        }.toTypedArray()
        val checked = LOCATION_RADII.indexOf(locationFilter?.radiusKm ?: 10).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.overview_filter_location_radius_title, location.label))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                locationFilter = OverviewLocationFilter(
                    label = location.label,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    googlePlaceId = location.googlePlaceId,
                    radiusKm = LOCATION_RADII[which],
                )
                if (!notesOnly) preferences.setOverviewLocationFilter(locationFilter)
                dialog.dismiss()
                finishSelection()
                renderViews()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddCommentDialog(
        prefilledText: String = "",
    ) {
        if (operationPending) return
        val candidates = commentDestinations.filter { it.item.available }
        if (candidates.isEmpty()) {
            showMessage(
                if (commentDestinationIndexAvailable) {
                    R.string.overview_add_no_documents
                } else {
                    R.string.overview_add_library_unavailable
                },
                Snackbar.LENGTH_LONG,
            )
            return
        }
        val rememberedStem = preferences.getOverviewLastAddDocument()
        val initial = candidates.firstOrNull { it.item.pdfStem == rememberedStem }
            ?: candidates.firstOrNull { it.item.pdfStem == selectedDocument }
            ?: allComments().firstOrNull()?.documentStem?.let { recentStem ->
                candidates.firstOrNull { it.item.pdfStem == recentStem }
            }
            ?: candidates.first()
        CommentEditorDialog(activity).show(
            CommentEditorConfig(
                titleRes = R.string.add_comment,
                initialValue = CommentEditorValue(prefilledText),
                documents = candidates.map {
                    CommentEditorDocument(it.item.pdfStem, it.label)
                },
                selectedDocumentId = initial.item.pdfStem,
                focusText = prefilledText.isNotBlank(),
            ),
            onSave = { result ->
                val destination = candidates.firstOrNull {
                    it.item.pdfStem == result.documentId
                }
                if (destination == null) {
                    false
                } else {
                    addComment(destination.item, result.value)
                    true
                }
            },
        )
    }

    private fun addComment(
        item: HomeItem,
        value: CommentEditorValue,
    ) {
        if (operationPending) return
        operationPending = true
        renderViews()
        val id = CommentCreationMetadata.newThoughtsId()
        scope.launch {
            val success = addCommentToLogicalStore(item, value, id)
            operationPending = false
            if (success) {
                if (!notesOnly) preferences.setOverviewLastAddDocument(item.pdfStem)
                selectedDocument = ""
                setPlaceFilters("", "")
                anchorFilter = OverviewAnchorFilter.ALL
                pdfFilter = OverviewPdfFilter.ALL
                priorityFilter = OverviewPriorityFilter.ALL
                locationFilter = null
                sortOrder = OverviewSortOrder.NEWEST
                if (!notesOnly) preferences.setOverviewDocumentFilter(selectedDocument)
                if (!notesOnly) preferences.setOverviewAnchorFilter(anchorFilter)
                if (!notesOnly) preferences.setOverviewPdfFilter(pdfFilter)
                if (!notesOnly) preferences.setOverviewPriorityFilter(priorityFilter)
                if (!notesOnly) preferences.setOverviewLocationFilter(null)
                if (!notesOnly) preferences.setOverviewSort(sortOrder)
                selectedDetailKey = "${item.pdfStem}\u0000$id"
                reloadAfterMutation()
                onDataChanged()
                page?.findViewById<RecyclerView>(R.id.pageRecyclerView)?.scrollToPosition(0)
                showMessage(R.string.overview_add_success, Snackbar.LENGTH_SHORT)
            } else {
                reloadAfterMutation()
                showMessage(R.string.overview_add_failed, Snackbar.LENGTH_LONG)
            }
        }
    }

    private suspend fun addCommentToLogicalStore(
        item: HomeItem,
        value: CommentEditorValue,
        id: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val edit = buildAddCommentEdit(item, value, id)
            ?: return@withContext false
        val annotationPdfSha256 = computeSha256(activity.applicationContext, item.uri) ?: return@withContext false
        runCatching {
            com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity.applicationContext)
                .edit(item.fileName, annotationPdfSha256, listOf(edit))
        }.isSuccess
    }

    private fun buildAddCommentEdit(
        item: HomeItem,
        value: CommentEditorValue,
        id: String,
    ): AnnotationEdit.AddPdfComment? {
        val core = PdfiumCore(activity.applicationContext)
        val descriptor = activity.contentResolver.openFileDescriptor(item.uri, "r") ?: return null
        val document = try {
            core.newDocument(descriptor)
        } catch (throwable: Throwable) {
            runCatching { descriptor.close() }
            return null
        }
        return try {
            if (core.getPageCount(document) <= 0) return null
            core.openPage(document, 0)
            val rect = overviewTopCommentRect(
                core.getPageWidthPoint(document, 0).toFloat(),
                core.getPageHeightPoint(document, 0).toFloat(),
            ) ?: return null
            val now = pdfDateNow()
            AnnotationEdit.AddPdfComment(
                page = 0,
                name = id,
                rect = rect,
                contents = value.text,
                author = CommentAuthor.current(activity),
                createdDate = now,
                modifiedDate = now,
                priority = value.priority,
                location = value.location,
                needsAction = value.needsAction,
                color = value.color,
            )
        } catch (throwable: Throwable) {
            null
        } finally {
            core.closeDocument(document)
        }
    }

    private fun sortLabel(value: OverviewSortOrder): String = activity.getString(
        when (value) {
            OverviewSortOrder.NEWEST -> R.string.overview_sort_newest
            OverviewSortOrder.OLDEST -> R.string.overview_sort_oldest
            OverviewSortOrder.DOCUMENT -> R.string.overview_sort_document
            OverviewSortOrder.PAGE -> R.string.overview_sort_page
        },
    )

    private fun anchorFilterLabel(value: OverviewAnchorFilter): String = activity.getString(
        when (value) {
            OverviewAnchorFilter.ALL -> R.string.overview_filter_all_statuses
            OverviewAnchorFilter.ANCHORED -> R.string.overview_anchored
            OverviewAnchorFilter.NEEDS_ANCHOR -> R.string.overview_needs_anchor
            OverviewAnchorFilter.PDF_ONLY -> R.string.overview_pdf_only
        },
    )

    private fun pdfFilterLabel(value: OverviewPdfFilter): String = activity.getString(
        when (value) {
            OverviewPdfFilter.ALL -> R.string.overview_pdf_type_all
            OverviewPdfFilter.TEX_LINKED -> R.string.overview_pdf_type_tex_linked
            OverviewPdfFilter.OTHER -> R.string.overview_pdf_type_other
        },
    )

    private fun priorityLabel(value: CommentPriority): String = activity.getString(
        when (value) {
            CommentPriority.NORMAL -> R.string.overview_priority_normal
            CommentPriority.HIGH -> R.string.overview_priority_high
        },
    )

    private fun priorityFilterLabel(value: OverviewPriorityFilter): String = activity.getString(
        when (value) {
            OverviewPriorityFilter.ALL -> R.string.overview_priority_all
            OverviewPriorityFilter.NORMAL -> R.string.overview_priority_normal
            OverviewPriorityFilter.HIGH -> R.string.overview_priority_high
        },
    )

    // Snackbar.make throws when the view is no longer in a window (a decision refused while the
    // Notes page was closed crashed the app on 11 November); the messages then become toasts.
    private fun snackbarHost(): View? = page?.takeIf { it.isAttachedToWindow }

    private fun showMessage(message: Int, duration: Int) {
        snackbarHost()?.let { Snackbar.make(it, message, duration).show() }
            ?: Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String, duration: Int) {
        snackbarHost()?.let { Snackbar.make(it, message, duration).show() }
            ?: Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun renderSearch(precomputedAll: List<OverviewComment>? = null) {
        val all = precomputedAll ?: allComments()
        searchAdapter.submitList(
            searchedComments(all),
            emptySet(),
            all.isNotEmpty(),
        )
    }

    private fun commentClicked(comment: OverviewComment) {
        if (selectedKeys.isNotEmpty()) {
            toggleSelection(comment)
        } else {
            showDetails(comment)
        }
    }

    private fun commentLongClicked(comment: OverviewComment) {
        // A removed proposal is restored or dismissed one at a time, from its details.
        if (removedProposal(comment) != null) { showDetails(comment); return }
        toggleSelection(comment)
    }

    private fun searchCommentLongClicked(comment: OverviewComment) {
        onHideSearch()
        if (removedProposal(comment) != null) { showDetails(comment); return }
        toggleSelection(comment)
    }

    private fun toggleSelection(comment: OverviewComment) {
        val key = overviewCommentKey(comment)
        if (!selectedKeys.remove(key)) selectedKeys.add(key)
        if (selectedKeys.isEmpty()) {
            finishSelection()
            return
        }
        selectionBackCallback.isEnabled = true
        page?.findViewById<Toolbar>(R.id.overviewSelectionToolbar)?.apply {
            visibility = View.VISIBLE
            title = activity.getString(R.string.overview_selected_count, selectedKeys.size)
            updateSelectionActions(this)
        }
        page?.findViewById<View>(R.id.overviewActions)?.visibility = View.GONE
        val all = allComments()
        adapter.submitList(
            filteredComments(all),
            selectedKeys,
            all.isNotEmpty(),
        )
    }

    private fun showDetails(comment: OverviewComment) {
        selectedDetailKey = overviewCommentKey(comment)
        val inline = page?.findViewById<ViewGroup>(R.id.overviewDetailContainer)
        if (inline != null && inline.visibility == View.VISIBLE && inline.childCount > 0) {
            bindDetail(inline, comment, null)
            return
        }
        detailDialog?.dismiss()
        val dialog = BottomSheetDialog(activity)
        val detail = LayoutInflater.from(activity).inflate(
            R.layout.view_overview_comment_detail,
            null,
            false,
        )
        dialog.setContentView(detail)
        val dismiss = { dialog.dismiss() }
        bindDetail(detail, comment, dismiss)
        detailDialogContent = detail
        detailDialogDismiss = dismiss
        dialog.setOnDismissListener {
            if (detailDialog === dialog) {
                detailDialog = null
                detailDialogContent = null
                detailDialogDismiss = null
            }
        }
        detailDialog = dialog
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun refreshOpenDetail(precomputedAll: List<OverviewComment>? = null) {
        val dialog = detailDialog?.takeIf(BottomSheetDialog::isShowing) ?: return
        val comment = selectedDetailKey?.let { key ->
            (precomputedAll ?: allComments()).firstOrNull { overviewCommentKey(it) == key }
        }
        val content = detailDialogContent
        val dismiss = detailDialogDismiss
        if (comment == null || content == null || dismiss == null) {
            dialog.dismiss()
        } else {
            bindDetail(content, comment, dismiss)
        }
    }

    private fun renderInlineDetail(precomputedAll: List<OverviewComment>? = null) {
        val inline = page?.findViewById<ViewGroup>(R.id.overviewDetailContainer) ?: return
        if (inline.visibility != View.VISIBLE || inline.childCount == 0) return
        val all = precomputedAll ?: allComments()
        val comment = selectedDetailKey?.let { key ->
            all.firstOrNull { overviewCommentKey(it) == key }
        } ?: filteredComments(all).firstOrNull()
        if (comment != null) {
            selectedDetailKey = overviewCommentKey(comment)
            bindDetail(inline, comment, null)
        } else {
            inline.findViewById<TextView>(R.id.overviewCommentContents).text = ""
        }
    }

    private fun bindDetail(root: View, comment: OverviewComment, dismiss: (() -> Unit)?) {
        val item = pdfItem(comment)
        val proposal = proposalsById[comment.id]
        val proposalDecision = proposal?.let { proposalDecisionStates[it.id] }
        root.findViewById<View>(R.id.overviewDetailHandle).visibility =
            if (dismiss == null) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.overviewDetailMetadata).apply {
            text = commentLocationMetadata(comment)
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        root.findViewById<TextView>(R.id.overviewDetailAuthor).text = listOfNotNull(
            comment.author.ifBlank { activity.getString(R.string.overview_unknown_author) },
            comment.activityMillis.takeIf { it > 0 }?.let {
                DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            },
        ).joinToString(" · ")
        val anchorRemoved = comment.anchorStatus == ANCHOR_REMOVED_STATUS
        val removed = removedProposal(comment)
        val missing = missingMarker(comment)
        root.findViewById<TextView>(R.id.overviewDetailAnchorStatus).apply {
            val label = activity.getString(overviewAnchorStatusLabel(comment.anchorStatus))
            // "Anchor removed" names the last known source location the marker was removed from.
            text = overviewAnchorRemovedLocation(comment)?.takeIf { anchorRemoved }?.let { (file, line) ->
                label + " · " + if (line > 0) activity.getString(R.string.overview_anchor_removed_detail, file, line)
                    else activity.getString(R.string.overview_anchor_removed_detail_file, file)
            } ?: removed?.let { label + " · " + removedProposalLocation(it) }
                ?: missing?.let { label + " · " + missingCommentLocation(it) } ?: label
            visibility = if (comment.isNote) View.GONE else View.VISIBLE
        }
        root.findViewById<ImageView>(R.id.overviewDetailAnchorIcon).apply {
            visibility = if (comment.isNote) View.GONE else View.VISIBLE
            setImageResource(when (comment.anchorStatus) {
                ANCHOR_REMOVED_STATUS, PROPOSAL_REMOVED_STATUS -> R.drawable.ic_anchor_removed
                COMMENT_MISSING_STATUS -> R.drawable.ic_link_off
                "anchored", "source_linked", "source_projected", "exact_text", "exact_point" -> R.drawable.ic_link
                "needs_attention", "needs_anchor", "linking" -> R.drawable.ic_link_warning
                else -> R.drawable.ic_link_off
            })
        }
        root.findViewById<TextView>(R.id.overviewDetailSelectedText).apply {
            visibility = if (comment.selectedText.isBlank() ||
                comment.selectedText.trim() == comment.contents.trim()
            ) View.GONE else View.VISIBLE
            // An agent edit is a change, not a selection: label what it adds, removes or replaces.
            text = (proposal?.let { it.beforePreview.trim() to it.afterPreview.trim() }
                ?: removed?.let { it.before.trim() to it.after.trim() })?.let { (before, after) ->
                when {
                    before.isEmpty() && after.isNotEmpty() -> activity.getString(R.string.overview_proposal_adds, after)
                    after.isEmpty() && before.isNotEmpty() -> activity.getString(R.string.overview_proposal_removes, before)
                    before.isNotEmpty() -> activity.getString(R.string.overview_proposal_replaces, before, after)
                    else -> null
                }
            } ?: activity.getString(R.string.overview_selected_text, comment.selectedText)
        }
        root.findViewById<TextView>(R.id.overviewProposalDecisionStatus).apply {
            val status = proposalDecisionLabel(comment) ?: removed?.let { item ->
                listOfNotNull(
                    activity.getString(R.string.overview_removed_proposal_explanation),
                    removedProposalLooksLike(item.looksLike)?.let(activity::getString),
                ).joinToString(" ")
            } ?: missing?.let {
                activity.getString(if (it.state == MissingCommentMarker.STATE_DELETED) R.string.overview_comment_missing_deleted_explanation
                    else R.string.overview_comment_missing_unknown_explanation)
            }
            visibility = if (status == null) View.GONE else View.VISIBLE
            text = status.orEmpty()
        }
        root.findViewById<MaterialButton>(R.id.overviewDetailAttachments).apply {
            visibility = if (comment.isNote) View.VISIBLE else View.GONE
            setOnClickListener {
                activity.startActivity(Intent(activity, com.gitlab.mudlej.MjPdfReader.ui.notes.NoteAttachmentsActivity::class.java)
                    .putExtra("note_id", comment.id))
            }
        }
        root.findViewById<TextView>(R.id.overviewCommentContents).text = comment.contents
        root.findViewById<View>(R.id.overviewDetailCommentColor).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(comment.color)
            }
            contentDescription = activity.getString(R.string.comment_color)
        }
        root.findViewById<TextView>(R.id.overviewDetailCommentSummary).text = listOfNotNull(
            priorityLabel(comment.priority), comment.noteDetails?.calendarLabel()?.takeIf(String::isNotBlank),
            comment.noteDetails?.takeIf { isHiddenTodo(comment) }
                ?.let { activity.getString(R.string.todo_hidden_until, it.visibleFromLabel()) },
        ).joinToString(" · ")
        root.findViewById<MaterialButton>(R.id.overviewDetailHideUntil).apply {
            val details = comment.noteDetails
            visibility = if (comment.isNote && details != null) View.VISIBLE else View.GONE
            isEnabled = !operationPending && comment.logicalPayloadJson.isNotBlank()
            setOnClickListener { if (details != null) chooseVisibleFrom(comment, details, dismiss) }
        }
        root.findViewById<TextView>(R.id.overviewDetailLocation).apply {
            text = if (comment.isNote) comment.noteDetails?.checkWhere.orEmpty().takeIf(String::isNotBlank)
                ?.let { activity.getString(R.string.note_check_where_summary, it) }.orEmpty()
                else comment.location?.label.orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        root.findViewById<MaterialButton>(R.id.overviewOpenChatGpt).apply {
            val chatGptUrl = normalizeChatGptUrl(comment.chatGptUrl)
            visibility = if (chatGptUrl == null) View.GONE else View.VISIBLE
            isEnabled = chatGptUrl != null
            setOnClickListener {
                if (chatGptUrl != null && !activity.openChatGptLink(chatGptUrl)) {
                    Toast.makeText(activity, R.string.chatgpt_open_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        root.findViewById<MaterialButton>(R.id.overviewOpenComment).apply {
            val canOpenPdf = comment.logicalLocations.any(::logicalLocationCanOpen)
            val canOpenSource = comment.sourceFile.isNotBlank() &&
                (comment.logicalPayloadJson.isNotBlank() || ((proposal != null || removed != null || missing != null) && comment.pageIndex < 0))
            setText(if (!canOpenPdf && canOpenSource) R.string.overview_open_source else R.string.overview_open_comment)
            isEnabled = if (comment.logicalPayloadJson.isNotBlank() || canOpenSource) {
                canOpenPdf || canOpenSource
            } else {
                item?.available == true
            }
            setOnClickListener {
                if (canOpenPdf) {
                    chooseLogicalLocation(comment, dismiss)
                } else if (canOpenSource) {
                    openLogicalSource(comment, dismiss)
                } else if (item == null || !item.available) {
                    Toast.makeText(activity, R.string.overview_pdf_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    dismiss?.invoke()
                    onOpenComment(item, comment)
                }
            }
        }
        root.findViewById<MaterialButton>(R.id.overviewEditComment).apply {
            // A missing comment has nothing to edit: its text is the marker's.
            visibility = if (proposal == null && removed == null && missing == null) View.VISIBLE else View.GONE
            isEnabled = proposal == null && missing == null &&
                (comment.logicalPayloadJson.isNotBlank() || item?.available == true) &&
                !operationPending
            setOnClickListener { showOverviewCommentEditor(comment) }
        }
        root.findViewById<MaterialButton>(R.id.overviewDeleteComment).apply {
            // A removed proposal is dismissed where a comment is deleted (spec section 10.6). A missing
            // comment cannot be deleted; its marker is removed from the source instead.
            setText(when {
                removed != null -> R.string.overview_dismiss_proposal
                missing != null -> R.string.overview_comment_missing_remove
                else -> R.string.delete
            })
            contentDescription = when {
                removed != null -> activity.getString(R.string.overview_dismiss_proposal_description)
                missing != null -> activity.getString(R.string.overview_comment_missing_remove_description)
                else -> null
            }
            visibility = if (proposal == null) View.VISIBLE else View.GONE
            isEnabled = proposal == null && !operationPending &&
                (removed != null || missing != null || comment.logicalPayloadJson.isNotBlank() || item?.available == true)
            setOnClickListener {
                if (removed != null) {
                    confirmDismissRemovedProposal(removed, dismiss)
                } else if (missing != null) {
                    resolveMissingComments(listOf(missing), restore = false)
                } else {
                    dismiss?.invoke()
                    deleteComments(listOf(comment))
                }
            }
        }
        root.findViewById<MaterialButton>(R.id.overviewRestoreAnchor)?.apply {
            setText(if (missing != null) R.string.overview_comment_missing_restore else R.string.overview_restore_anchor)
            contentDescription = activity.getString(when {
                removed != null -> R.string.overview_restore_proposal_description
                missing != null -> R.string.overview_comment_missing_restore_description
                else -> R.string.overview_restore_anchor_description
            })
            visibility = if ((anchorRemoved || removed != null || missing != null) && proposal == null) View.VISIBLE else View.GONE
            isEnabled = !operationPending && (removed != null || missing != null || anchorRemoved && comment.logicalPayloadJson.isNotBlank())
            setOnClickListener {
                when {
                    removed != null -> restoreRemovedProposal(removed)
                    missing != null -> resolveMissingComments(listOf(missing), restore = true)
                    else -> restoreAnchor(comment)
                }
            }
        }
        root.findViewById<MaterialButton>(R.id.overviewAcceptProposal).apply {
            visibility = if (proposal != null) View.VISIBLE else View.GONE
            isEnabled = proposal != null && !operationPending &&
                proposalDecision?.phase != AgentProposalDecisionPhase.PENDING
            setOnClickListener {
                resolveProposals(listOfNotNull(proposal), "accept")
            }
        }
        root.findViewById<MaterialButton>(R.id.overviewDisregardProposal).apply {
            visibility = if (proposal != null) View.VISIBLE else View.GONE
            isEnabled = proposal != null && !operationPending &&
                proposalDecision?.phase != AgentProposalDecisionPhase.PENDING
            setOnClickListener {
                resolveProposals(listOfNotNull(proposal), "disregard")
            }
        }
    }

    private fun openLogicalSource(comment: OverviewComment, dismiss: (() -> Unit)?) {
        scope.launch {
            val handle = withContext(Dispatchers.IO) {
                sourceEditQueue.openOfflineSource(comment.sourceFile, comment.sourceLine)
            }
            val snapshot = handle?.cachedSnapshot
            val filename = snapshot?.bundlePdfFilename
            val sha256 = snapshot?.bundlePdfSha256
            if (handle == null || snapshot == null || filename.isNullOrBlank() || sha256.isNullOrBlank()) {
                Toast.makeText(activity, R.string.source_editor_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            dismiss?.invoke()
            activity.startActivity(
                SourceEditorActivity.intent(
                    activity,
                    handle.requestId,
                    handle.cacheKey,
                    filename,
                    sha256,
                    comment.selectedText,
                ),
            )
        }
    }

    private fun chooseLogicalLocation(comment: OverviewComment, dismiss: (() -> Unit)?) {
        val destinations = overviewCommentPageDestinations(comment.logicalLocations)
        if (destinations.isEmpty()) return
        if (destinations.size == 1) {
            openLogicalDestination(comment, destinations.single(), dismiss)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_choose_location)
            .setItems(destinations.map { logicalLocationLabel(it.labelLocation) }.toTypedArray()) { _, index ->
                openLogicalDestination(comment, destinations[index], dismiss)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openLogicalDestination(
        comment: OverviewComment,
        destination: OverviewCommentPageDestination,
        dismiss: (() -> Unit)?,
    ) {
        val labelLocation = destination.labelLocation
        val item = logicalLocationItem(labelLocation)
        if (labelLocation.status == "unavailable" || item?.available != true) {
            Toast.makeText(activity, R.string.overview_pdf_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val currentSha256 = withContext(Dispatchers.IO) {
                computeSha256(activity.applicationContext, item.uri)
            }
            val publishedSha256 = sourcePdfSha256ByFilename[labelLocation.pdfFilename]
            val location = destination.navigationLocation(currentSha256)
            if (location == null || publishedSha256 != null && publishedSha256 != currentSha256) {
                Toast.makeText(
                    activity,
                    R.string.overview_pdf_revision_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            dismiss?.invoke()
            onOpenComment(
                item,
                comment.copy(
                    documentStem = location.pdfFilename.removeSuffix(".pdf"),
                    pageIndex = location.pageIndex,
                    logicalLocations = listOf(location),
                ),
            )
        }
    }

    private fun logicalLocationCanOpen(location: LogicalCommentPdfLocation): Boolean =
        location.status != "unavailable" && logicalLocationItem(location)?.available == true

    private fun logicalLocationItem(location: LogicalCommentPdfLocation): HomeItem? =
        pdfItems[location.pdfFilename.removeSuffix(".pdf").lowercase()]

    private fun logicalLocationLabel(location: LogicalCommentPdfLocation): String {
        val stem = location.pdfFilename.removeSuffix(".pdf")
        val name = documentDisplayNames[stem.lowercase()].orEmpty().ifBlank { stem }
        return if (location.status == "unavailable") {
            activity.getString(R.string.overview_location_unavailable, name, location.page)
        } else {
            activity.getString(R.string.overview_comment_document_page, name, location.page)
        }
    }

    private fun updateSelectionActions(toolbar: Toolbar) {
        val selected = allComments().filter { overviewCommentKey(it) in selectedKeys }
        val proposalsOnly = selected.isNotEmpty() && selected.all(::isAgentProposal)
        // Missing-comment markers are resolved, never deleted; their actions show when only they are selected.
        val missingOnly = selected.isNotEmpty() && selected.all { missingMarker(it) != null }
        val commentsOnly = selected.isNotEmpty() && selected.none(::isAgentProposal) && selected.none { it.anchorStatus == COMMENT_MISSING_STATUS }
        val proposalDecisionPending = selected.any { comment ->
            proposalDecisionStates[comment.id]?.phase == AgentProposalDecisionPhase.PENDING
        }
        toolbar.menu.findItem(SELECTION_ACCEPT)?.apply {
            isVisible = proposalsOnly
            isEnabled = proposalsOnly && !proposalDecisionPending && !operationPending
        }
        toolbar.menu.findItem(SELECTION_DISREGARD)?.apply {
            isVisible = proposalsOnly
            isEnabled = proposalsOnly && !proposalDecisionPending && !operationPending
        }
        toolbar.menu.findItem(SELECTION_DELETE)?.isVisible = commentsOnly
        for (action in listOf(SELECTION_RESTORE_MISSING, SELECTION_REMOVE_MARKER)) toolbar.menu.findItem(action)?.apply {
            isVisible = missingOnly
            isEnabled = missingOnly && !operationPending
        }
    }

    private fun resolveSelectedMissingComments(restore: Boolean) {
        val selected = allComments().filter { overviewCommentKey(it) in selectedKeys }.mapNotNull(::missingMarker)
        finishSelection()
        resolveMissingComments(selected, restore)
    }

    /**
     * Remove marker (a source edit published by the next Sync) or Restore comment (one logical-comment
     * operation) for each marker, then a fresh source check so resolved rows leave Issues.
     */
    private fun resolveMissingComments(markers: List<MissingCommentMarker>, restore: Boolean) {
        if (operationPending || markers.isEmpty()) return
        operationPending = true
        renderViews()
        scope.launch {
            val messages = try {
                val outcomes = withContext(Dispatchers.IO) {
                    val resolver = com.gitlab.mudlej.MjPdfReader.data.source.MissingCommentResolver(activity.applicationContext)
                    markers.map { if (restore) resolver.restore(it) else resolver.remove(it) }
                }
                val resolved = outcomes.filter { it.success }.map { it.marker.rowId }.toSet()
                if (resolved.isNotEmpty()) {
                    // Resolved rows leave Issues now; the source check that follows confirms it.
                    com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.resolveMissing(resolved)
                    if (restore) runCatching { ThoughtsSyncScheduler.enqueueBackground(activity) }
                    refreshLogicalComments()
                    onPendingChangesChanged()
                }
                outcomes.map { missingCommentOutcomeMessage(activity, it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("OverviewTabController", "Unable to resolve a missing comment", error)
                listOf(activity.getString(R.string.overview_comment_missing_failed))
            } finally {
                operationPending = false
                renderViews()
            }
            val message = messages.distinct().joinToString("\n")
            if (messages.size > 1) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.overview_comment_missing_status)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                snackbarHost()?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
                    ?: Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resolveSelectedProposals(action: String) {
        val selected = allComments()
            .filter { overviewCommentKey(it) in selectedKeys }
            .mapNotNull { proposalsById[it.id] }
        finishSelection()
        resolveProposals(selected, action)
    }

    private fun resolveProposals(chosen: List<OfflineAgentProposal>, action: String) {
        // Both halves of a move pair may sit in two documents; Fedora decides the second with the first.
        val selected = com.gitlab.mudlej.MjPdfReader.data.source.ProposalMovePairs.oneHalfEach(chosen) { it.id }
        if (selected.isEmpty() || operationPending) return
        val bundleIds = selected.map(OfflineAgentProposal::bundleId).distinct()
        if (bundleIds.size != 1) {
            showMessage(R.string.agent_proposal_stale, Snackbar.LENGTH_LONG)
            return
        }
        val proposalObjects = selected.associate { it.id to it.objectSha256 }
        val pdfFilenames = selected.flatMap { proposal ->
            proposal.pdfFilenames + proposal.correspondence
                ?.locations
                .orEmpty()
                .map { it.pdfFilename }
        }
        operationPending = true
        renderProposalDecisionPresentation()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    fedoraRepository.resolveAgentProposals(
                        bundleIds.single(),
                        action,
                        proposalObjects,
                        pdfFilenames,
                    )
                    AppDatabase.getInstance(activity.applicationContext)
                        .thoughtsSyncDao().allOutbox()
                        .filter { it.entityType == AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE }
                }
            }
            result.getOrNull()?.let(::applyProposalDecisionRows)
            operationPending = false
            if (result.isSuccess) {
                showMessage(R.string.agent_proposal_queued, Snackbar.LENGTH_LONG)
            } else {
                showMessage(
                    activity.getString(
                        R.string.agent_proposal_failed,
                        result.exceptionOrNull()?.message.orEmpty().ifBlank {
                            activity.getString(R.string.home_sync_start_failed)
                        },
                    ),
                    Snackbar.LENGTH_LONG,
                )
            }
            renderProposalDecisionPresentation()
        }
    }

    /** Hides a to-do until the chosen date; "Show now" clears the date. */
    private fun chooseVisibleFrom(
        comment: OverviewComment,
        details: com.gitlab.mudlej.MjPdfReader.data.annotation.NoteDetails,
        dismiss: (() -> Unit)?,
    ) {
        fun save(visibleFrom: String) {
            if (visibleFrom == details.visibleFrom) return
            dismiss?.invoke()
            editComment(comment, CommentEditorValue(comment.contents, comment.color, comment.priority, comment.location,
                details.copy(visibleFrom = visibleFrom), comment.needsAction))
        }
        val date = runCatching { java.time.LocalDate.parse(details.visibleFrom) }.getOrNull()
            ?: java.time.LocalDate.now().plusDays(1)
        android.app.DatePickerDialog(activity, { _, year, month, day ->
            save(java.time.LocalDate.of(year, month + 1, day).toString())
        }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
            setTitle(R.string.todo_hide_until_title)
            datePicker.minDate = System.currentTimeMillis()
            if (details.visibleFrom.isNotBlank()) {
                setButton(android.content.DialogInterface.BUTTON_NEUTRAL, activity.getString(R.string.todo_show_now)) { _, _ -> save("") }
            }
        }.show()
    }

    private fun showOverviewCommentEditor(comment: OverviewComment) {
        var savedNoteValue = CommentValue(comment.contents, comment.color, comment.priority, comment.location, comment.noteDetails, comment.needsAction)
        CommentEditorDialog(activity).show(
            CommentEditorConfig(
                titleRes = if (comment.isNote) R.string.note_edit else R.string.edit_comment,
                initialValue = CommentEditorValue(
                    comment.contents,
                    comment.color,
                    comment.priority,
                    comment.location,
                    comment.noteDetails,
                    needsAction = comment.needsAction,
                    linking = runCatching { com.google.gson.JsonParser.parseString(comment.logicalPayloadJson)
                        .asJsonObject.get("linking")?.takeIf { it.isJsonObject }?.asJsonObject }.getOrNull(),
                ),
                showMetadata = !comment.isReply,
                allowEmptyText = comment.isNote || (!comment.isReply && comment.subtype != "Text"),
            ),
            onAutosave = if (comment.isNote) { result ->
                val value = result.value
                val entry = HistoryEntry.Edit(comment,
                    savedNoteValue,
                    CommentValue(value.text, value.color, value.priority, value.location, value.noteDetails, value.needsAction))
                val saved = mutate(entry, undo = false)
                if (saved) {
                    savedNoteValue = entry.newValue
                    history.record(entry)
                    refreshLogicalComments()
                    onPendingChangesChanged()
                }
                saved
            } else null,
            onSave = { result ->
                val value = result.value
                if (value.text != comment.contents || value.color != comment.color ||
                    value.priority != comment.priority || value.location != comment.location ||
                    value.noteDetails != comment.noteDetails || value.needsAction != comment.needsAction
                ) {
                    editComment(comment, value)
                }
                true
            },
        )
    }

    private fun editComment(comment: OverviewComment, value: CommentEditorValue) {
        val entry = HistoryEntry.Edit(
            comment,
            CommentValue(comment.contents, comment.color, comment.priority, comment.location, comment.noteDetails, comment.needsAction),
            CommentValue(value.text, value.color, value.priority, value.location, value.noteDetails, value.needsAction),
        )
        runHistoryMutation(entry, undo = false, addToHistory = true, R.string.overview_edit_failed)
    }

    /** Re-inserts an "Anchor removed" comment's marker through the source edit queue (no comment operation). */
    private fun restoreAnchor(comment: OverviewComment) {
        if (operationPending) return
        val payload = runCatching { JsonParser.parseString(comment.logicalPayloadJson).asJsonObject }.getOrNull() ?: return
        operationPending = true
        renderViews()
        scope.launch {
            val message = try {
                val result = withContext(Dispatchers.IO) {
                    LocalSourceComments(activity.applicationContext).restoreAnchor(payload)
                }
                if (result.success) {
                    com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.invalidate()
                    refreshLogicalComments()
                    onPendingChangesChanged()
                }
                overviewRestoreAnchorMessage(activity, result)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("OverviewTabController", "Unable to restore a comment anchor", error)
                activity.getString(R.string.overview_restore_anchor_failed)
            } finally {
                operationPending = false
                renderViews()
            }
            snackbarHost()?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
                ?: Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun removedProposalLocation(proposal: RemovedOutsideProposal): String {
        val file = proposal.sourceFile.substringAfterLast('/')
        return if (proposal.line > 0) activity.getString(R.string.overview_restore_proposal_location, file, proposal.line)
            else activity.getString(R.string.overview_restore_proposal_location_file, file)
    }

    private fun showRemovedProposalMessage(message: String) {
        snackbarHost()?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
            ?: Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    /** Restore Proposal: a local source edit through the source edit queue (spec section 10.6). */
    private fun restoreRemovedProposal(proposal: RemovedOutsideProposal) {
        if (operationPending) return
        operationPending = true
        renderViews()
        scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    com.gitlab.mudlej.MjPdfReader.data.source.RemovedProposalRestorer(activity.applicationContext).restore(proposal)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("OverviewTabController", "Unable to restore a removed proposal", error)
                null
            } finally {
                operationPending = false
            }
            val file = (outcome?.sourceFile ?: proposal.sourceFile).substringAfterLast('/')
            when {
                outcome == null -> showRemovedProposalMessage(activity.getString(R.string.overview_restore_proposal_failed))
                outcome.alreadyPresent -> {
                    refreshRemovedProposals()
                    showRemovedProposalMessage(activity.getString(R.string.overview_restore_proposal_already_present, file, outcome.line))
                }
                outcome.restored -> {
                    com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.invalidate()
                    refreshRemovedProposals()
                    onPendingChangesChanged()
                    showRemovedProposalMessage(activity.getString(R.string.overview_restore_proposal_restored, file, outcome.line) +
                        " " + activity.getString(R.string.overview_restore_proposal_publish))
                }
                outcome.refusal == "sources_unavailable" ->
                    showRemovedProposalMessage(activity.getString(R.string.overview_restore_anchor_sources_unavailable))
                else -> showRestoreRefused(proposal, outcome.refusal.orEmpty(), outcome.line)
            }
            renderViews()
        }
    }

    /** A refused Restore names the reason and the location, and offers Copy and Dismiss. */
    private fun showRestoreRefused(proposal: RemovedOutsideProposal, refusal: String, line: Int) {
        val file = proposal.sourceFile.substringAfterLast('/')
        val location = if (line > 0) activity.getString(R.string.overview_restore_proposal_location, file, line)
            else activity.getString(R.string.overview_restore_proposal_location_file, file)
        val message = listOf(
            activity.getString(removedProposalRefusalMessage(refusal)),
            location,
            activity.getString(R.string.overview_restore_proposal_texts,
                proposal.before.trimEnd().ifEmpty { "—" }, proposal.after.trimEnd().ifEmpty { "—" }),
        ).joinToString("\n\n")
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_restore_proposal_refused_title)
            .setMessage(message)
            .setPositiveButton(R.string.overview_dismiss_proposal) { _, _ -> confirmDismissRemovedProposal(proposal, null) }
            .setNeutralButton(R.string.overview_restore_proposal_copy) { _, _ -> showCopyRemovedProposal(proposal) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCopyRemovedProposal(proposal: RemovedOutsideProposal) {
        val choices = listOf(
            R.string.overview_restore_proposal_copy_original to proposal.before,
            R.string.overview_restore_proposal_copy_proposed to proposal.after,
            R.string.overview_restore_proposal_copy_explanation to proposal.comment,
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_restore_proposal_copy)
            .setItems(choices.map { activity.getString(it.first) }.toTypedArray()) { _, index ->
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(activity.getString(choices[index].first), choices[index].second))
                showRemovedProposalMessage(activity.getString(R.string.overview_restore_proposal_copied))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDismissRemovedProposal(proposal: RemovedOutsideProposal, dismissDetail: (() -> Unit)?) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overview_dismiss_proposal_title)
            .setMessage(R.string.overview_dismiss_proposal_message)
            .setPositiveButton(R.string.overview_dismiss_proposal) { _, _ ->
                dismissDetail?.invoke()
                dismissRemovedProposal(proposal)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Dismiss: a pending operation on the proposal row with its revision as base (spec section 10.6). */
    private fun dismissRemovedProposal(proposal: RemovedOutsideProposal) {
        if (operationPending) return
        operationPending = true
        renderViews()
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) { fedoraRepository.dismissRemovedProposal(proposal.row()) }
                removedProposals = removedProposals.filterNot { it.id == proposal.id }
                removedProposalsById = removedProposals.associateBy(RemovedOutsideProposal::id)
                renderProposalDocuments()
                onPendingChangesChanged()
                activity.getString(R.string.overview_dismiss_proposal_done)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("OverviewTabController", "Unable to dismiss a removed proposal", error)
                activity.getString(R.string.overview_dismiss_proposal_failed)
            } finally {
                operationPending = false
                renderViews()
            }
            showRemovedProposalMessage(message)
        }
    }

    private fun deleteSelected() {
        val selected = allComments().filter { overviewCommentKey(it) in selectedKeys }
        if (operationPending) return
        finishSelection()
        deleteComments(selected)
    }

    private fun deleteComments(selected: List<OverviewComment>) {
        if (operationPending || selected.isEmpty()) return
        operationPending = true
        renderViews()
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    commentService.deleteComments(selected.map { it.id })
                }
                if (!result.success) error(result.error)
                val entry = HistoryEntry.Delete(selected, result.historyToken).takeIf { result.historyToken != 0 }
                entry?.let(history::record)
                refreshLogicalComments()
                onPendingChangesChanged()
                // Undo lives in the "+" menu, which the Notes tab has no button for, so offer it here.
                if (entry != null) snackbarHost()?.let {
                    Snackbar.make(it, R.string.overview_deleted, Snackbar.LENGTH_LONG)
                        .setAction(R.string.overview_undo) { if (history.peekUndo() === entry) undo() }
                        .show()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("OverviewTabController", "Unable to delete comments", error)
                snackbarHost()?.let { Snackbar.make(it, R.string.overview_delete_failed, Snackbar.LENGTH_LONG).show() }
            } finally {
                operationPending = false
                renderViews()
            }
        }
    }

    private fun undo() {
        val entry = history.peekUndo() ?: return
        runHistoryMutation(entry, undo = true, addToHistory = false, R.string.overview_edit_failed)
    }

    private fun redo() {
        val entry = history.peekRedo() ?: return
        runHistoryMutation(entry, undo = false, addToHistory = false, R.string.overview_edit_failed)
    }

    private fun runHistoryMutation(
        entry: HistoryEntry,
        undo: Boolean,
        addToHistory: Boolean,
        errorMessage: Int,
    ) {
        if (operationPending) return
        operationPending = true
        renderViews()
        scope.launch {
            try {
                // Only the edit itself records the PDF version; undo and redo replay history.
                val success = mutate(entry, undo, stamp = addToHistory)
                // Canonical comments are already durable in Room. Do not rescan PDFs
                // or wait for the deferred source catalogue to show a local mutation.
                if (entry.comments.all { it.logicalPayloadJson.isNotBlank() }) {
                    refreshLogicalComments()
                } else {
                    reloadAfterMutation()
                    onDataChanged()
                }
                if (success) {
                    when {
                        addToHistory -> history.record(entry)
                        undo -> history.completeUndo()
                        else -> history.completeRedo()
                    }
                    onPendingChangesChanged()
                } else {
                    snackbarHost()?.let { Snackbar.make(it, errorMessage, Snackbar.LENGTH_LONG).show() }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("OverviewTabController", "Unable to update comment", error)
                snackbarHost()?.let { Snackbar.make(it, errorMessage, Snackbar.LENGTH_LONG).show() }
            } finally {
                operationPending = false
                renderViews()
            }
        }
    }

    /** A restored wrapper in the phone's copy of the source takes the proposal off Issues. */
    private suspend fun refreshRemovedProposals() {
        val snapshot = withContext(Dispatchers.IO) {
            com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity.applicationContext).snapshot()
        }
        logicalComments = snapshot.logicalComments
        removedProposals = snapshot.removedProposals
        removedProposalsById = removedProposals.associateBy(RemovedOutsideProposal::id)
        renderProposalDocuments()
    }

    private suspend fun refreshLogicalComments() {
        logicalComments = withContext(Dispatchers.IO) {
            com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity.applicationContext).snapshot().logicalComments
        }
        renderProposalDocuments()
    }

    private suspend fun mutate(entry: HistoryEntry, undo: Boolean, stamp: Boolean = !undo): Boolean {
        if (entry is HistoryEntry.Delete) return withContext(Dispatchers.IO) {
            (if (undo) commentService.undo(entry.serviceToken) else commentService.redo(entry.serviceToken)).success
        }
        entry as HistoryEntry.Edit
        val comments = entry.comments
        val logical = comments
        if (logical.any { it.logicalPayloadJson.isBlank() }) return false
        val logicalSucceeded = withContext(Dispatchers.IO) {
            logical.all { comment ->
                val value = if (undo) entry.oldValue else entry.newValue
                runCatching {
                    com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity.applicationContext).update(comment.id, stamp = stamp) { base ->
                        requireNotNull(logicalCommentMutationPayload(base, comment.id, deleted = false, contents = value.text, color = value.color,
                            priority = value.priority, location = value.location, replaceLocation = true,
                            needsAction = value.needsAction.takeIf { !comment.isReply && !comment.isNote })).also { value.noteDetails?.applyTo(it) }
                    }
                }.isSuccess
            }
        }
        if (!logicalSucceeded) return false
        if (logical.isNotEmpty()) runCatching { ThoughtsSyncScheduler.enqueueBackground(activity) }
        return true
    }

    private suspend fun reloadAfterMutation() {
        val refreshedSourceSnapshot = withContext(Dispatchers.IO) { loadSourceSnapshot() }
        if (sourceBundleId != refreshedSourceSnapshot.bundleId) {
            confirmedAppliedOperationIds.clear()
        }
        sourceBundleId = refreshedSourceSnapshot.bundleId
        sourceProposals = refreshedSourceSnapshot.proposals
        proposalDecisionStates = currentProposalDecisionStates()
        sourceReviewComments = refreshedSourceSnapshot.reviewComments
        logicalComments = refreshedSourceSnapshot.retainingNewerLogicalComments(logicalComments).logicalComments
        sourcePdfSha256ByFilename = sourcePdfSha256ByFilename + refreshedSourceSnapshot.pdfSha256ByFilename
        sourceReviewCommentDocuments = pdfItems.values
            .map { it.fileName }
            .filter(refreshedSourceSnapshot.reviewCommentDocuments::contains)
            .toSet()
        val reloadedDocuments = withContext(Dispatchers.IO) {
            catalogue.invalidate()
            catalogue.loadSnapshot().overviewDocuments
        }
        baseCommentDocuments = reloadedDocuments
        val proposals = activeProposals()
        proposalsById = proposals.associateBy(OfflineAgentProposal::id)
        documents = availableOverviewDocuments(
            mergeProposalDocuments(
                baseCommentDocuments,
                proposals,
                sourceReviewComments,
                sourceReviewCommentDocuments,
                logicalComments,
            ),
            pdfItems.mapValues { (_, item) -> item.available },
        )
        snapshotSignature = signature(documents)
        selectedKeys.clear()
        renderViews()
        refreshOpenDetail()
    }

    private fun pdfItem(comment: OverviewComment): HomeItem? =
        comment.logicalLocations.firstNotNullOfOrNull(::logicalLocationItem)
            ?: pdfItems[comment.documentStem.lowercase()]

    private fun signature(value: List<OverviewCommentDocument>): String = value
        .flatMap { it.comments }
        .sortedBy(::overviewCommentKey)
        .joinToString("|") {
            "${it.documentStem}:${it.id}:${it.activityMillis}:${it.contents}:" +
                "${it.color}:${it.priority}:${it.location}:${it.noteDetails}"
        }

    private companion object {
        const val ACTION_ADD_NOTE = 7
        const val ACTION_RESUME_NOTE = 8
        const val ACTION_SEARCH = 1
        const val ACTION_ADD = 2
        const val ACTION_SORT = 3
        const val ACTION_FILTER = 4
        const val ACTION_UNDO = 5
        const val ACTION_REDO = 6
        const val SELECTION_ACCEPT = 101
        const val SELECTION_DISREGARD = 102
        const val SELECTION_DELETE = 103
        const val SELECTION_RESTORE_MISSING = 104
        const val SELECTION_REMOVE_MARKER = 105
        const val FILTER_TYPE = 0
        const val FILTER_AUTHOR = 1
        const val FILTER_DOCUMENT = 2
        const val FILTER_PDF_TYPE = 3
        const val FILTER_STATUS = 4
        const val FILTER_PRIORITY = 5
        const val FILTER_LOCATION = 6
        const val FILTER_RESET = 7
        const val TYPE_NOTES = "notes"
        const val TYPE_ALL = "all"
        const val TYPE_COMMENTS = "comments"
        const val TYPE_AGENT_REMOVE = "agent_remove"
        val LOCATION_RADII = listOf(1, 5, 10, 25, 50, 100)
    }
}

internal fun shouldFinishOverviewSelection(
    selectedCount: Int,
    backCallbackEnabled: Boolean,
): Boolean = selectedCount > 0 || backCallbackEnabled

private fun OverviewLocationFilter.toCommentLocation(): CommentLocation = CommentLocation(
    label = label,
    latitude = latitude,
    longitude = longitude,
    googlePlaceId = googlePlaceId,
)

internal fun overviewAnchorStatusLabel(anchorStatus: String): Int = when (anchorStatus) {
    ANCHOR_REMOVED_STATUS -> R.string.overview_anchor_removed
    PROPOSAL_REMOVED_STATUS -> R.string.overview_removed_proposal
    COMMENT_MISSING_STATUS -> R.string.overview_comment_missing_status
    "anchored", "source_linked", "source_projected", "exact_text", "exact_point" -> R.string.overview_source_linked
    "standalone" -> R.string.note_type
    "source_only" -> R.string.overview_source_only
    "source_unavailable" -> R.string.annotation_source_unavailable
    "needs_attention" -> R.string.overview_source_needs_attention
    "needs_anchor", "linking" -> R.string.overview_linking_source
    "pdf_only", "no_source" -> R.string.overview_only_in_pdf
    else -> R.string.overview_pdf_only
}

internal fun overviewSourceLocation(comment: OverviewComment): String? {
    return comment.sourceFile.takeIf(String::isNotBlank)?.let { sourceFile ->
        if (comment.sourceLine > 0) "$sourceFile:${comment.sourceLine}" else sourceFile
    }
}

/** The last known source location of an "Anchor removed" comment, from its synced scope. */
internal fun overviewAnchorRemovedLocation(comment: OverviewComment): Pair<String, Int>? {
    val scope = runCatching { JsonParser.parseString(comment.logicalPayloadJson).asJsonObject.getAsJsonObject("scope") }.getOrNull()
    if (!com.gitlab.mudlej.MjPdfReader.data.source.CommentAnchorState.isAnchorRemoved(scope)) return null
    val file = scope!!.get("source_file")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().ifBlank { return null }
    return file to (runCatching { scope.get("source_line")?.asInt }.getOrNull() ?: 0)
}

internal fun overviewRestoreAnchorMessage(
    context: android.content.Context,
    result: LocalSourceComments.RestoreResult,
): String {
    val failure = result.failure
    if (!result.success) return when (failure) {
        RestoreFailure.SOURCES_UNAVAILABLE ->
            context.getString(R.string.overview_restore_anchor_sources_unavailable)
        RestoreFailure.SOURCE_FILE_MISSING ->
            context.getString(R.string.overview_restore_anchor_file_missing)
        RestoreFailure.MALFORMED ->
            context.getString(R.string.overview_restore_anchor_malformed, result.sourceFile, result.line)
        else -> context.getString(R.string.overview_restore_anchor_failed)
    }
    val file = result.sourceFile.substringAfterLast('/')
    val where = when {
        result.placement == RestorePlacement.ALREADY_PRESENT ->
            return context.getString(R.string.overview_restore_anchor_already_present, file, result.line)
        result.fallbackFile -> context.getString(R.string.overview_restore_anchor_fallback_file,
            result.requestedFile.substringAfterLast('/').ifBlank { file }, file, result.line)
        result.placement == RestorePlacement.END_OF_FILE -> context.getString(R.string.overview_restore_anchor_end_of_file, file, result.line)
        result.placement == RestorePlacement.NEAREST_LINE && result.requestedLine > result.lineCount ->
            context.getString(R.string.overview_restore_anchor_nearest_line, result.requestedLine, file, result.line)
        result.placement == RestorePlacement.NEAREST_LINE ->
            context.getString(R.string.overview_restore_anchor_moved_line, result.requestedLine, file, result.line)
        else -> context.getString(R.string.overview_restore_anchor_restored, file, result.line)
    }
    return listOfNotNull(
        where,
        context.getString(R.string.overview_restore_anchor_point).takeIf { result.pointAnchorFallback },
        context.getString(R.string.overview_restore_anchor_publish),
    ).joinToString(" ")
}
