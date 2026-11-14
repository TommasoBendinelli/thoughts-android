// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import android.app.Activity
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogue
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.sync.AgentProposalDecisionPhase
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsOutboxEntity
import com.gitlab.mudlej.MjPdfReader.data.sync.activeAgentProposals
import com.gitlab.mudlej.MjPdfReader.data.sync.agentProposalDecisionStates
import com.gitlab.mudlej.MjPdfReader.data.source.AgentProposals
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.logicalReadScope
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PdfCommentsSheetBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Document comments use the same non-modal reader panel and viewport rules as Contents. */
internal class CommentsSheetController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val scope: CoroutineScope,
    private val documentUri: () -> Uri?,
    private val documentFilename: () -> String,
    private val beforeShow: () -> Unit,
    private val preview: (OverviewComment, Boolean) -> Unit,
    private val openThread: (OverviewComment) -> Boolean,
    private val exportList: () -> Unit,
    private val projectReading: () -> com.gitlab.mudlej.MjPdfReader.data.sync.ProjectReadingContext? = { null },
) {
    private val adapter = DocumentCommentsAdapter(::selectComment)
    private var comments = emptyList<DocumentCommentItem>()
    private var sourceChecked = false
    private var texAvailable = false
    private var sourceDraftChanged = false
    private var texGeneration = 0
    private var proposals = emptyMap<String, OfflineAgentProposal>()
    private var query = ""
    private var filter = DocumentCommentFilter.USER_COMMENTS
    private var sortByDate = false
    private var selectedId: String? = null
    private var showingDetail = false
    private var scrollPosition = 0
    private var scrollOffset = 0
    private var reopenAfterLoad = false
    private var sheet: PdfCommentsSheetBinding? = null
    private var container: FrameLayout? = null
    private var behavior: BottomSheetBehavior<FrameLayout>? = null
    private var settledState = BottomSheetBehavior.STATE_HALF_EXPANDED
    private var loadJob: Job? = null
    private var decisionsJob: Job? = null
    private var commentChangesJob: Job? = null
    private var decisionRows = emptyList<ThoughtsOutboxEntity>()
    private val resolving = mutableSetOf<String>()
    private var reasonExpanded = false
    private var generation = 0
    private var syncPosted = false
    private val host get() = binding.commentSheetHost

    private val agentEdits get() = filter == DocumentCommentFilter.AGENT_PROPOSALS
    private val titleRes get() = if (agentEdits) R.string.agent_edits_title else R.string.comments_title

    private fun switchList(target: DocumentCommentFilter) {
        if (filter == target) return
        dismiss()
        filter = target
        query = ""
        selectedId = null
        showingDetail = false
        scrollPosition = 0
        scrollOffset = 0
        adapter.submitList(emptyList())
    }

    fun showComments() {
        switchList(DocumentCommentFilter.USER_COMMENTS)
        show()
    }

    fun showAgentEdits() {
        switchList(DocumentCommentFilter.AGENT_PROPOSALS)
        show()
    }

    fun showProposal(proposalId: String) {
        switchList(DocumentCommentFilter.AGENT_PROPOSALS)
        selectedId = proposalId.removePrefix("agent-remove-")
        showingDetail = true
        if (sheet == null) show()
        else comments.firstOrNull { it.comment.id.removePrefix("agent-remove-") == selectedId }?.let(::selectComment)
    }

    fun show() {
        if (sheet != null || activity.isFinishing || activity.isDestroyed) return
        val uri = documentUri() ?: return
        val filename = documentFilename()
        beforeShow()
        reopenAfterLoad = false
        val request = ++generation
        val content = PdfCommentsSheetBinding.inflate(activity.layoutInflater, host, false)
        val frame = FrameLayout(activity).apply {
            isClickable = true
            elevation = dp(8).toFloat()
            addView(content.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val bottomSheet = BottomSheetBehavior<FrameLayout>().apply {
            isFitToContents = false
            isHideable = false
            isDraggable = false
            halfExpandedRatio = 0.5f
            state = settledState
        }
        sheet = content
        container = frame
        behavior = bottomSheet
        host.visibility = View.VISIBLE
        host.addView(frame, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { behavior = bottomSheet })
        content.commentsList.layoutManager = LinearLayoutManager(activity)
        content.commentsList.adapter = adapter
        content.commentsList.itemAnimator = null
        content.commentsSearch.setText(query)
        content.commentsSearch.doAfterTextChanged {
            query = it?.toString().orEmpty()
            scrollPosition = 0
            scrollOffset = 0
            refreshList()
        }
        content.commentsSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        content.commentsSearch.setOnFocusChangeListener { _, focused ->
            if (focused) bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
        }
        content.commentsTitle.setText(titleRes)
        content.commentsSearchLayout.hint = activity.getString(if (agentEdits) R.string.agent_edits_search else R.string.comments_search)
        content.commentsOptions.contentDescription = activity.getString(if (agentEdits) R.string.agent_edits_options else R.string.comments_options)
        content.commentsBack.setText(if (agentEdits) R.string.agent_edits_back_to_list else R.string.comments_back_to_list)
        content.commentsClose.setOnClickListener { dismiss() }
        content.commentsResize.setOnClickListener {
            hideKeyboard()
            bottomSheet.state = if (bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED)
                BottomSheetBehavior.STATE_HALF_EXPANDED else BottomSheetBehavior.STATE_EXPANDED
        }
        content.commentsBack.setOnClickListener { showList() }
        content.commentsOptions.setOnClickListener { view ->
            PopupMenu(activity, view).apply {
                menu.add(0, 1, 0, R.string.sort_by_creation_date).apply { isCheckable = true; isChecked = sortByDate }
                menu.add(0, 2, 1, R.string.export)
                setOnMenuItemClickListener {
                    if (it.itemId == 1) { sortByDate = !sortByDate; showList() } else exportList()
                    true
                }
                show()
            }
        }
        bottomSheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (container !== frame) return
                if (newState == BottomSheetBehavior.STATE_EXPANDED || newState == BottomSheetBehavior.STATE_HALF_EXPANDED) {
                    settledState = newState
                    updateResizeButton()
                }
                scheduleVisibleAreaSync()
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) = scheduleVisibleAreaSync()
        })
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleVisibleAreaSync() }
        host.addOnLayoutChangeListener(hostLayoutListener)
        host.doOnLayout { if (container === frame) { configureSize(); scheduleVisibleAreaSync() } }
        updateResizeButton()
        decisionsJob = scope.launch {
            AppDatabase.getInstance(activity.applicationContext).thoughtsSyncDao()
                .observeAgentProposalDecisions().collect { rows ->
                    decisionRows = rows
                    if (sheet === content) {
                        renderDecision()
                        refreshList()
                    }
                }
        }
        commentChangesJob?.cancel()
        commentChangesJob = scope.launch {
            val store = com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity.applicationContext)
            store.changes().collect {
                val current = withContext(Dispatchers.IO) {
                    loadDocumentComments(filename, uri).first
                }
                if (sheet === content && documentUri() == uri) {
                    comments = current
                    refreshList()
                    if (showingDetail) {
                        val selected = comments.firstOrNull { it.comment.id == selectedId }
                        if (selected == null) showList() else renderDetail(selected)
                    }
                }
            }
        }
        content.commentsProgress.visibility = View.VISIBLE
        content.commentsOptions.isEnabled = false
        loadJob = scope.launch {
            try {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val loaded = withContext(Dispatchers.IO) {
                    loadDocumentComments(filename, uri)
                }
                if (generation != request || sheet !== content || documentUri() != uri || documentFilename() != filename) return@launch
                Log.d("CommentsSheet", "Loaded $filename: ${loaded.first.size} comments in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
                comments = loaded.first
                proposals = loaded.second
                content.commentsProgress.visibility = View.GONE
                content.commentsOptions.isEnabled = true
                val selected = filterDocumentComments(comments, filter, "").firstOrNull { it.comment.id.removePrefix("agent-remove-") == selectedId?.removePrefix("agent-remove-") }
                refreshList()
                if (showingDetail && selected != null) selectComment(selected) else showList()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("CommentsSheet", "Could not load comments", error)
                if (generation == request && sheet === content) {
                    content.commentsProgress.visibility = View.GONE
                    content.commentsMessage.setText(if (agentEdits) R.string.agent_edits_load_failed else R.string.comments_load_failed)
                    content.commentsMessage.visibility = View.VISIBLE
                }
            }
        }
    }

    private suspend fun loadDocumentComments(filename: String, uri: Uri): Pair<List<DocumentCommentItem>, Map<String, OfflineAgentProposal>> =
        loadAllDocumentComments(filename, uri).let { (rows, proposals) ->
            // Accepted in the source editor and not yet answered by Fedora: no longer an open edit.
            val accepted = runCatching {
                com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue(activity.applicationContext, scheduleRequests = false)
                    .editorProposalDecisions(proposals.values.map { it.sourceFile }).accepted
            }.getOrDefault(emptySet())
            rows to proposals.filterKeys { it !in accepted }
        }

    private suspend fun loadAllDocumentComments(filename: String, uri: Uri): Pair<List<DocumentCommentItem>, Map<String, OfflineAgentProposal>> {
        val store = com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(activity.applicationContext)
        val reading = projectReading()
        if (reading == null) {
            val snapshot = store.snapshot(filename)
            return documentComments(filename, emptyList(), snapshot, computeSha256(activity.applicationContext, uri)) to snapshot.proposals.associateBy { it.id }
        }
        val rows = mutableListOf<DocumentCommentItem>()
        val proposals = linkedMapOf<String, OfflineAgentProposal>()
        for (document in reading.documents) {
            val snapshot = store.snapshot(document.filename)
            rows += documentComments(document.filename, emptyList(), snapshot, document.reading.pdfSha256).map { row ->
                row.copy(comment = row.comment.copy(pageIndex = row.comment.pageIndex.let { if (it < 0) it else it + document.reading.firstPage }))
            }
            snapshot.proposals.forEach { proposals[it.id] = it }
        }
        return rows.distinctBy { it.comment.id } to proposals
    }

    private fun displayedComments(): List<DocumentCommentItem> {
        val activeIds = activeAgentProposals(proposals.values, decisionRows).map { it.id }.toSet()
        val activeComments = comments.filter { item ->
            !item.isAgentProposal || item.comment.id in activeIds ||
                item.comment.id.removePrefix("agent-remove-") in activeIds
        }
        return filterDocumentComments(activeComments, filter, query).let {
            if (sortByDate) it.sortedWith(compareBy<DocumentCommentItem> { row -> row.comment.activityMillis <= 0 }
                .thenByDescending { row -> row.comment.activityMillis }) else it
        }
    }

    fun exportText(): String = buildString {
        appendLine(documentFilename())
        for (item in displayedComments()) {
            appendLine()
            appendLine(metadata(item))
            appendLine(item.comment.contents)
            if (item.comment.selectedText.isNotBlank()) appendLine(item.comment.selectedText)
        }
    }

    private fun refreshList() {
        val content = sheet ?: return
        val displayed = displayedComments()
        adapter.selectedId = selectedId
        adapter.submitList(displayed) {
            if (sheet === content) (content.commentsList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(scrollPosition, scrollOffset)
        }
        content.commentsMessage.setText(when {
            query.isNotBlank() -> if (agentEdits) R.string.agent_edits_no_matches else R.string.comments_no_matches
            agentEdits -> R.string.no_agent_edits
            else -> R.string.no_comments
        })
        content.commentsMessage.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun snapshotScroll() {
        val content = sheet ?: return
        if (showingDetail) return
        val manager = content.commentsList.layoutManager as LinearLayoutManager
        val position = manager.findFirstVisibleItemPosition()
        if (position >= 0) {
            scrollPosition = position
            scrollOffset = manager.findViewByPosition(position)?.top?.minus(content.commentsList.paddingTop) ?: 0
        }
    }

    private fun selectComment(item: DocumentCommentItem) {
        snapshotScroll()
        selectedId = item.comment.id
        reasonExpanded = false
        hideKeyboard()
        renderDetail(item)
        if (item.comment.pageIndex in 0 until binding.pdfView.pageCount) {
            syncVisibleArea()
            // Focus after the bottom sheet has established the unobscured PDF viewport.
            contentAfterLayout(item)
        }
    }

    private fun contentAfterLayout(item: DocumentCommentItem) {
        val content = sheet ?: return
        content.root.post {
            if (sheet === content && showingDetail && selectedId == item.comment.id) {
                syncVisibleArea()
                preview(item.comment, item.isAgentProposal)
            }
        }
    }

    private fun metadata(item: DocumentCommentItem): String {
        val page = commentDisplayPage(item.comment.pageIndex)
        return listOf(
            page?.let { activity.getString(R.string.bookmark_page_label, it) } ?: activity.getString(R.string.comments_location_unavailable),
            activity.getString(if (item.isAgentProposal) R.string.comments_row_agent else R.string.comments_row_user),
        ).joinToString(" · ")
    }

    private fun renderDetail(item: DocumentCommentItem) {
        val content = sheet ?: return
        val comment = item.comment
        texAvailable = false
        sourceChecked = false
        sourceDraftChanged = false
        showingDetail = true
        content.commentsTitle.setText(if (item.isAgentProposal) R.string.comments_row_agent else R.string.comments_title)
        content.commentsBrowser.visibility = View.GONE
        content.commentsSearchLayout.visibility = View.GONE
        content.commentsDetailScroll.visibility = View.VISIBLE
        content.commentsDetailScroll.scrollTo(0, 0)
        val author = comment.author.ifBlank { activity.getString(R.string.overview_unknown_author) }
        val date = comment.activityMillis.takeIf { it > 0 }?.let {
            android.text.format.DateUtils.formatDateTime(activity, it, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_YEAR)
        }
        content.commentsDetailMetadata.text = if (item.isAgentProposal) {
            listOfNotNull(metadata(item), date?.let { activity.getString(R.string.agent_edit_added, it) }).joinToString(" · ")
        } else listOfNotNull(metadata(item), author, date).joinToString(" · ")
        content.commentsDetailText.text = comment.contents.ifBlank { activity.getString(R.string.overview_no_comment_text) }
        content.commentsDetailText.visibility = if (!item.isAgentProposal || reasonExpanded) View.VISIBLE else View.GONE
        content.commentsSelectedText.text = comment.selectedText
        content.commentsSelectedText.visibility = if (item.isAgentProposal || comment.selectedText.isBlank()) View.GONE else View.VISIBLE
        val proposal = proposals[comment.id] ?: proposals[comment.id.removePrefix("agent-remove-")]
        content.commentsOriginalPanel.visibility = if (item.isAgentProposal) View.VISIBLE else View.GONE
        content.commentsProposalActions.visibility = if (item.isAgentProposal) View.VISIBLE else View.GONE
        content.commentsOpenConversation.visibility = View.GONE
        if (item.isAgentProposal && proposal == null) content.commentsTexDiff.setText(R.string.agent_original_not_available)
        content.commentsProposalReason.setOnClickListener {
            reasonExpanded = !reasonExpanded
            content.commentsDetailText.visibility = if (reasonExpanded) View.VISIBLE else View.GONE
            ViewCompat.setStateDescription(content.commentsProposalReason, activity.getString(if (reasonExpanded) R.string.agent_original_expanded else R.string.agent_original_collapsed))
            content.commentsProposalReason.setIconResource(if (reasonExpanded) R.drawable.ic_small_arrow_down else R.drawable.ic_chevron_right)
        }
        ViewCompat.setStateDescription(content.commentsProposalReason, activity.getString(if (reasonExpanded) R.string.agent_original_expanded else R.string.agent_original_collapsed))
        content.commentsProposalReason.setIconResource(if (reasonExpanded) R.drawable.ic_small_arrow_down else R.drawable.ic_chevron_right)
        content.commentsEditSource.isEnabled = proposal != null
        content.commentsEditSource.setOnClickListener {
            proposal?.let {
                // Reopening the sheet reloads the source revision after editor changes.
                dismiss()
                activity.startActivity(com.gitlab.mudlej.MjPdfReader.ui.source.ProposalSourceActivity.intent(
                    activity, it.sourceFile, it.line, it.id,
                ))
            }
        }
        if (proposal != null) loadTexExcerpt(proposal)
        content.commentsAcceptProposal.setOnClickListener { proposal?.let { resolveProposal(it, "accept") } }
        content.commentsDisregardProposal.setOnClickListener { proposal?.let { resolveProposal(it, "disregard") } }
        renderDecision()
        content.commentsOpenThread.visibility = if (item.isAgentProposal) View.GONE else View.VISIBLE
        content.commentsOpenThread.setOnClickListener {
            if (openThread(comment)) dismiss()
        }
    }

    private fun loadTexExcerpt(proposal: OfflineAgentProposal) {
        val generation = ++texGeneration
        sheet?.commentsSourceFilename?.text = proposal.sourceFile.substringAfterLast('/')
        sheet?.commentsTexDiff?.setText(R.string.proposal_source_loading)
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { AgentProposalDecider.checkSource(activity, proposal) }.getOrNull()
                    ?.let { it.excerpt to it.changed }
            }
            if (generation != texGeneration || !showingDetail || selectedId != proposal.id) return@launch
            val excerpt = loaded?.first
            sourceChecked = loaded != null
            sourceDraftChanged = loaded?.second == true
            texAvailable = excerpt != null
            showConversation(excerpt?.conversationUrl)
            sheet?.commentsTexDiff?.text = if (excerpt == null) activity.getString(R.string.proposal_source_missing) else {
                val before = excerpt.before.lines().joinToString("\n") { "− $it" }
                val after = excerpt.after.lines().joinToString("\n") { "+ $it" }
                val dark = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                android.text.SpannableString("$before\n$after").apply {
                    setSpan(android.text.style.ForegroundColorSpan(if (dark) 0xFFFFBAC5.toInt() else 0xFF9C2E41.toInt()), 0, before.length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(if (dark) 0xFFACEDBC.toInt() else 0xFF1C7D3F.toInt()), before.length + 1, length, 0)
                }
            }
            if (sourceDraftChanged) sheet?.commentsTexDiff?.append("\n\n" + activity.getString(R.string.proposal_source_changed))
            renderDecision()
        }
    }

    private fun showList() {
        val content = sheet ?: return
        showingDetail = false
        content.commentsTitle.setText(titleRes)
        content.commentsProposalActions.visibility = View.GONE
        content.commentsBrowser.visibility = View.VISIBLE
        content.commentsSearchLayout.visibility = View.VISIBLE
        content.commentsDetailScroll.visibility = View.GONE
        refreshList()
    }

    /** Opens the Claude conversation that wrote the proposal (spec 3.6); Codex threads only open on the Mac. */
    private fun showConversation(url: String?) {
        val button = sheet?.commentsOpenConversation ?: return
        val claude = url?.takeIf { it.startsWith("https://claude.ai/code/session_") && AgentProposals.validConversationUrl(it) }
        button.visibility = if (claude != null) View.VISIBLE else View.GONE
        button.setOnClickListener {
            claude ?: return@setOnClickListener
            com.gitlab.mudlej.MjPdfReader.ui.agents.AgentConversationLinks.open(activity, claude)
        }
    }

    private fun renderDecision() {
        val content = sheet ?: return
        val proposal = proposals[selectedId] ?: proposals[selectedId?.removePrefix("agent-remove-")]
        val decision = proposal?.let { agentProposalDecisionStates(decisionRows, it.bundleId)[it.id] }
        if (showingDetail && decision?.phase == AgentProposalDecisionPhase.APPLIED) {
            showList()
            return
        }
        val enabled = showingDetail && proposal != null && sourceChecked && proposal.id !in resolving &&
            AgentProposalDecider.decidable(proposal.objectSha256, texAvailable, sourceDraftChanged, decision)
        content.commentsAcceptProposal.isEnabled = enabled
        content.commentsDisregardProposal.isEnabled = enabled
        val status = when (decision?.phase) {
            AgentProposalDecisionPhase.PENDING -> activity.getString(if (decision.action == "accept") R.string.agent_proposal_pending_accept else R.string.agent_proposal_pending_reject)
            AgentProposalDecisionPhase.APPLIED -> activity.getString(R.string.agent_original_applied)
            AgentProposalDecisionPhase.FAILED -> activity.getString(R.string.agent_proposal_resolution_failed, decision.error)
            null -> if (sourceDraftChanged) activity.getString(R.string.proposal_source_changed) else ""
        }
        content.commentsProposalStatus.text = status
        content.commentsProposalStatus.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
    }

    private fun resolveProposal(proposal: OfflineAgentProposal, action: String) {
        if (!sourceChecked || !texAvailable || sourceDraftChanged || !resolving.add(proposal.id)) return
        val content = sheet ?: return
        val selection = selectedId
        renderDecision()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AgentProposalDecider.decide(activity, proposal, action) }
                // Wait for durable state before re-enabling; repeated taps cannot enqueue duplicates.
                decisionRows = withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(activity.applicationContext).thoughtsSyncDao().allOutbox()
                }
                resolving.remove(proposal.id)
                if (sheet === content && selectedId == selection) renderDecision()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                resolving.remove(proposal.id)
                if (sheet === content && selectedId == selection) {
                    renderDecision()
                    content.commentsProposalStatus.text = activity.getString(R.string.agent_proposal_failed, error.message.orEmpty())
                    content.commentsProposalStatus.visibility = View.VISIBLE
                }
            }
        }
    }

    private val hostLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        configureSize()
        scheduleVisibleAreaSync()
    }

    private fun configureSize() {
        val current = behavior ?: return
        if (host.height <= 0) return
        val hostLocation = IntArray(2)
        val pdfLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        binding.pdfView.getLocationOnScreen(pdfLocation)
        val pdfTop = (pdfLocation[1] - hostLocation[1]).coerceAtLeast(0)
        val expandedOffset = minOf(pdfTop, host.height / 3)
        if (current.expandedOffset != expandedOffset) current.expandedOffset = expandedOffset
        val peekHeight = maxOf((host.height * .30f).toInt(), dp(200))
            .coerceAtMost((host.height * .45f).toInt())
        if (current.peekHeight != peekHeight) current.peekHeight = peekHeight
    }

    private fun updateResizeButton() {
        val button = sheet?.commentsResize ?: return
        val expanded = settledState == BottomSheetBehavior.STATE_EXPANDED
        button.setImageResource(if (expanded) R.drawable.ic_contents_collapse else R.drawable.ic_fullscreen_grey)
        val label = activity.getString(when {
            agentEdits && expanded -> R.string.agent_edits_collapse_panel
            agentEdits -> R.string.agent_edits_expand_panel
            expanded -> R.string.comments_collapse_panel
            else -> R.string.comments_expand_panel
        })
        button.contentDescription = label
        androidx.appcompat.widget.TooltipCompat.setTooltipText(button, label)
    }

    private fun scheduleVisibleAreaSync() {
        val frame = container ?: return
        if (syncPosted) return
        syncPosted = true
        // CoordinatorLayout positions its child after the child's layout callback.
        // Measure only after that offset is applied, avoiding alternating full/half heights.
        frame.postOnAnimation {
            if (container === frame) {
                syncPosted = false
                syncVisibleArea()
            }
        }
    }

    private fun syncVisibleArea() {
        val frame = container ?: return
        val content = sheet ?: return
        val height = (host.height - frame.top).coerceIn(0, frame.height)
        // The list must end at the screen edge even when the sheet is only half open.
        if (height > 0 && content.root.layoutParams.height != height) {
            content.root.layoutParams = content.root.layoutParams.apply { this.height = height }
        }
        val frameLocation = IntArray(2)
        val pdfLocation = IntArray(2)
        frame.getLocationOnScreen(frameLocation)
        binding.pdfView.getLocationOnScreen(pdfLocation)
        val bottom = (frameLocation[1] - pdfLocation[1]).coerceIn(0, binding.pdfView.height)
        if (bottom > 0 && binding.pdfView.width > 0) {
            binding.pdfView.setContentViewport(RectF(0f, 0f, binding.pdfView.width.toFloat(), bottom.toFloat()))
        }
    }

    fun handleBack(): Boolean {
        if (sheet == null) return false
        if (ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            hideKeyboard()
            return true
        }
        if (showingDetail) { showList(); return true }
        return dismiss()
    }

    private fun hideKeyboard() {
        sheet?.commentsSearch?.clearFocus()
        WindowCompat.getInsetsController(activity.window, binding.root).hide(WindowInsetsCompat.Type.ime())
    }

    fun dismiss(): Boolean {
        val frame = container ?: return false
        snapshotScroll()
        generation++
        loadJob?.cancel()
        loadJob = null
        commentChangesJob?.cancel()
        commentChangesJob = null
        decisionsJob?.cancel()
        decisionsJob = null
        hideKeyboard()
        sheet?.commentsList?.adapter = null
        sheet = null
        container = null
        behavior = null
        syncPosted = false
        host.removeOnLayoutChangeListener(hostLayoutListener)
        host.removeView(frame)
        if (host.childCount == 0) host.visibility = View.GONE
        binding.pdfView.clearContentViewport()
        return true
    }

    fun reset() {
        dismiss()
        comments = emptyList()
        proposals = emptyMap()
        query = ""
        filter = DocumentCommentFilter.USER_COMMENTS
        sortByDate = false
        selectedId = null
        showingDetail = false
        scrollPosition = 0
        scrollOffset = 0
        reopenAfterLoad = false
        settledState = BottomSheetBehavior.STATE_HALF_EXPANDED
        adapter.submitList(emptyList())
    }

    fun saveState(bundle: Bundle) {
        snapshotScroll()
        bundle.putBoolean("comments_sheet_open", sheet != null || reopenAfterLoad)
        bundle.putInt("comments_sheet_size", settledState)
        bundle.putString("comments_sheet_query", query)
        bundle.putString("comments_sheet_filter", filter.name)
        bundle.putBoolean("comments_sheet_sort_date", sortByDate)
        bundle.putString("comments_sheet_selected", selectedId)
        bundle.putBoolean("comments_sheet_detail", showingDetail)
        bundle.putInt("comments_sheet_scroll", scrollPosition)
        bundle.putInt("comments_sheet_offset", scrollOffset)
    }

    fun restoreState(bundle: Bundle) {
        reopenAfterLoad = bundle.getBoolean("comments_sheet_open")
        settledState = if (bundle.getInt("comments_sheet_size") == BottomSheetBehavior.STATE_EXPANDED)
            BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_HALF_EXPANDED
        query = bundle.getString("comments_sheet_query").orEmpty()
        filter = if (bundle.getString("comments_sheet_filter") == DocumentCommentFilter.AGENT_PROPOSALS.name)
            DocumentCommentFilter.AGENT_PROPOSALS else DocumentCommentFilter.USER_COMMENTS
        sortByDate = bundle.getBoolean("comments_sheet_sort_date")
        selectedId = bundle.getString("comments_sheet_selected")
        showingDetail = bundle.getBoolean("comments_sheet_detail")
        scrollPosition = bundle.getInt("comments_sheet_scroll").coerceAtLeast(0)
        scrollOffset = bundle.getInt("comments_sheet_offset")
    }

    fun onDocumentLoaded() { if (reopenAfterLoad) show() }
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
