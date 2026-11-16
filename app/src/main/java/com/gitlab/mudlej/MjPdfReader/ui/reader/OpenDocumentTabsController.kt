// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import androidx.appcompat.widget.PopupMenu
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.google.android.material.tabs.TabLayout
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DocumentTaskSnapshot(
    val taskId: Int,
    val baseActivityClassName: String?,
    val topActivityClassName: String?,
    val documentUri: String?,
    val taskLabel: String? = null,
    val titleTarget: ReaderTitleTarget = ReaderTitleTarget(),
)

internal data class OpenDocumentTab(
    val taskId: Int,
    val documentUri: String,
    val title: String,
    val isCurrent: Boolean,
)

/**
 * The document currently displayed by this activity. A reader task can swap documents in place
 * (for example while reviewing cards), which leaves the task's original launch intent behind.
 */
internal data class CurrentDocumentSnapshot(
    val uri: String?,
    val title: String?,
    val titleTarget: ReaderTitleTarget = ReaderTitleTarget(),
    val pdfDocumentId: String? = null,
    /** The reader's 1-based page and page count, 0 when unknown; kept for "Copy for agent" from other tabs. */
    val page: Int = 0,
    val pages: Int = 0,
)

/** The data URI of an agent note in the notes window, which is one tab next to the reader's documents. */
internal const val AGENT_NOTE_TAB_SCHEME = "thoughts-agent-note"

internal fun isAgentNoteTab(uri: String?): Boolean = uri?.startsWith("$AGENT_NOTE_TAB_SCHEME:") == true

/** What the tabs need from the screen showing them: a reader, or an agent note (which keeps the defaults). */
internal interface DocumentTabsHost {
    fun dismissPdfCommentSheet() = Unit
    fun applyReaderDisplayTitle(title: String?) = Unit
    fun runAfterDirtyAnnotationPrompt(discardAction: () -> Unit) = discardAction()
}

internal fun buildOpenDocumentTabs(
    snapshots: List<DocumentTaskSnapshot>,
    currentTaskId: Int,
    readerActivityClassName: String,
    currentDocument: CurrentDocumentSnapshot? = null,
    displayTitles: Map<Int, String> = emptyMap(),
    noteActivityClassName: String? = null,
): List<OpenDocumentTab> {
    return snapshots
        .asSequence()
        .filter {
            (it.baseActivityClassName == readerActivityClassName ||
                (noteActivityClassName != null && it.baseActivityClassName == noteActivityClassName)) &&
                    it.topActivityClassName != null
        }
        .mapNotNull { snapshot ->
            val isCurrent = snapshot.taskId == currentTaskId
            val launchUri = snapshot.documentUri?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val liveUri = currentDocument?.uri?.takeIf { isCurrent && it.isNotBlank() }
            val uri = liveUri ?: launchUri
            val liveTitle = currentDocument?.title?.takeIf { isCurrent && it.isNotBlank() }
            OpenDocumentTab(
                taskId = snapshot.taskId,
                documentUri = uri,
                // The recents task label tracks in-place document swaps, while baseIntent.data does not.
                title = displayTitles[snapshot.taskId]
                    ?: liveTitle
                    ?: snapshot.taskLabel?.takeIf { it.isNotBlank() }
                    ?: documentTabTitle(uri),
                isCurrent = isCurrent,
            )
        }
        .distinctBy { it.taskId }
        .sortedByDescending { it.taskId }
        .toList()
}

internal fun documentTabTitle(documentUri: String): String {
    val rawPath = runCatching { URI(documentUri).rawPath }.getOrNull()
        ?: documentUri.substringBefore('?').substringBefore('#')
    val rawName = rawPath
        .trimEnd('/')
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() }
        ?: return documentUri
    return runCatching {
        URLDecoder.decode(rawName.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(rawName)
}

internal fun tabsOnSide(
    tabs: List<OpenDocumentTab>,
    anchor: OpenDocumentTab,
    left: Boolean,
): List<OpenDocumentTab> {
    val index = tabs.indexOfFirst { it.taskId == anchor.taskId }
    if (index < 0) return emptyList()
    return if (left) tabs.take(index) else tabs.drop(index + 1)
}

internal fun documentTabPath(uri: String): String {
    val parsed = runCatching { URI(uri) }.getOrNull()
    return if (parsed?.scheme == "file" && parsed.authority.isNullOrEmpty()) {
        parsed.path ?: uri
    } else uri
}

/** Puts a "Copy for agent" text on the clipboard. */
internal fun copyAgentText(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.copy_document_for_agent), text),
    )
    // Android 13 and later confirm every copy themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

internal fun shouldReturnToLibraryAfterClosing(
    tabs: List<OpenDocumentTab>,
    closingTab: OpenDocumentTab,
): Boolean = closingTab.isCurrent && tabs.size == 1

internal fun replacementTabAfterClosing(
    tabs: List<OpenDocumentTab>,
    closingTab: OpenDocumentTab,
): OpenDocumentTab? {
    if (!closingTab.isCurrent || tabs.size <= 1) return null
    val closingIndex = tabs.indexOfFirst { it.taskId == closingTab.taskId }
    if (closingIndex < 0) return null
    return tabs.getOrNull(closingIndex + 1) ?: tabs.getOrNull(closingIndex - 1)
}

internal fun switchOpenDocumentTask(
    dismissCurrentDocumentUi: () -> Unit,
    moveTargetTaskToFront: () -> Unit,
) {
    dismissCurrentDocumentUi()
    moveTargetTaskToFront()
}

internal class OpenDocumentTabsController(
    private val activity: AppCompatActivity,
    private val host: DocumentTabsHost,
    private val tabLayout: TabLayout,
    private val readerPlace: () -> AgentReaderPlace = { AgentReaderPlace() },
    private val currentDocument: () -> CurrentDocumentSnapshot = { CurrentDocumentSnapshot(null, null) },
) {
    private val tag = "OpenDocumentTabs"
    private var rendering = false
    private var fullscreen = false
    private var released = false
    private var projects = JsonArray()
    private var projectNames = emptyMap<Int, String>()
    private var titleTargets = emptyMap<Int, ReaderTitleTarget>()
    private var publicationTitles = emptyMap<String, String>()
    private val sourceIndex = SourceLinkedPdfIndex()
    private var reloadJob: Job? = null
    private val titleChangesJob = activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            PendingChangesSignal.generation.collect { reloadTitles() }
        }
    }
    private val titleObserver = sourceIndex.observe {
        activity.runOnUiThread { reloadTitles() }
    }
    private var tasksById: Map<Int, ActivityManager.AppTask> = emptyMap()
    private var tabs: List<OpenDocumentTab> = emptyList()

    private val tabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            if (rendering) {
                return
            }
            val documentTab = tabs.getOrNull(tab.position) ?: return
            if (documentTab.isCurrent) {
                return
            }
            val task = tasksById[documentTab.taskId] ?: return
            try {
                switchOpenDocumentTask(
                    dismissCurrentDocumentUi = { host.dismissPdfCommentSheet() },
                    moveTargetTaskToFront = task::moveToFront,
                )
            } catch (error: RuntimeException) {
                Log.w(tag, "Unable to switch to document task ${documentTab.taskId}", error)
                refresh(isFullscreen = false)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab) = Unit

        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    init {
        tabLayout.addOnTabSelectedListener(tabListener)
    }

    fun refresh(isFullscreen: Boolean) {
        if (released) return
        fullscreen = isFullscreen
        val current = currentDocument()
        val previous = liveDocuments[activity.taskId]
        // Publishing can relocate the PDF without changing the selected logical document.
        val live = if (previous != null && current.pdfDocumentId != null &&
            current.pdfDocumentId == previous.pdfDocumentId && current.titleTarget == ReaderTitleTarget()) {
            current.copy(titleTarget = previous.titleTarget)
        } else current
        liveDocuments[activity.taskId] = live
        val appTasks = try {
            activity.getSystemService(ActivityManager::class.java).appTasks
        } catch (error: RuntimeException) {
            Log.w(tag, "Unable to read open document tasks", error)
            emptyList()
        }
        var taskEntries = appTasks.mapNotNull { appTask ->
            val info = try {
                appTask.taskInfo
            } catch (error: RuntimeException) {
                Log.w(tag, "Unable to read an open document task", error)
                return@mapNotNull null
            }
            val taskId = taskId(info)
            DocumentTaskSnapshot(
                taskId = taskId,
                baseActivityClassName = info.baseActivity?.className,
                topActivityClassName = info.topActivity?.className,
                documentUri = info.baseIntent.data?.toString(),
                taskLabel = info.taskDescription?.label,
                titleTarget = ReaderTitleTarget(info.baseIntent.getStringExtra(EXTRA_DISPLAY_DOCUMENT_ID),
                    info.baseIntent.getStringExtra(EXTRA_DISPLAY_PROJECT_ID), info.baseIntent.getStringExtra(EXTRA_DISPLAY_TITLE)),
            ) to appTask
        }
        // Publishing moves an imported PDF from its Project folder into the Library folder, so the
        // same document can reopen under a new path. Close the stale tab instead of showing both.
        val appFiles = activity.filesDir.absolutePath + java.io.File.separator
        val currentPath = live.uri?.let(::documentTabPath)
        val currentTaskId = activity.taskId // A binder call; read it once.
        val stale = taskEntries.filter { (snapshot, _) ->
            if (snapshot.taskId == currentTaskId || snapshot.baseActivityClassName != MainActivity::class.java.name) {
                return@filter false
            }
            val other = liveDocuments[snapshot.taskId]
            val otherUri = other?.uri ?: snapshot.documentUri ?: return@filter false
            // A tab that swapped documents in place (a review session) is not a stale copy.
            if (currentPath == null || otherUri == live.uri || otherUri != snapshot.documentUri) return@filter false
            val otherPath = documentTabPath(otherUri)
            (live.pdfDocumentId != null && other?.pdfDocumentId == live.pdfDocumentId) ||
                (currentPath.startsWith(appFiles) && otherPath.startsWith(appFiles) &&
                    java.io.File(otherPath).name == java.io.File(currentPath).name)
        }
        stale.forEach { (snapshot, appTask) ->
            runCatching { appTask.finishAndRemoveTask() }
                .onFailure { Log.w(tag, "Unable to close a duplicate document tab", it) }
            liveDocuments.remove(snapshot.taskId)
        }
        if (stale.isNotEmpty()) taskEntries = taskEntries - stale.toSet()
        tasksById = taskEntries.associate { (snapshot, appTask) -> snapshot.taskId to appTask }
        val displayTitles = taskEntries.mapNotNull { (snapshot, _) ->
            val observed = liveDocuments[snapshot.taskId]
            val target = observed?.titleTarget ?: snapshot.titleTarget
            val uri = observed?.uri ?: snapshot.documentUri ?: return@mapNotNull null
            if (isAgentNoteTab(uri)) return@mapNotNull null
            readerDisplayTitle(projects, publicationTitles, target, documentTabPath(uri),
                PhoneRenderQueue.defaultCanonicalPdfDirectory())?.let { snapshot.taskId to it }
        }.toMap()
        titleTargets = taskEntries.associate { (snapshot, _) ->
            snapshot.taskId to (liveDocuments[snapshot.taskId]?.titleTarget ?: snapshot.titleTarget)
        }
        projectNames = taskEntries.mapNotNull { (snapshot, _) ->
            val observed = liveDocuments[snapshot.taskId]
            val uri = observed?.uri ?: snapshot.documentUri ?: return@mapNotNull null
            if (isAgentNoteTab(uri)) return@mapNotNull null
            readerProjectName(projects, observed?.titleTarget ?: snapshot.titleTarget, documentTabPath(uri),
                PhoneRenderQueue.defaultCanonicalPdfDirectory())?.let { snapshot.taskId to it }
        }.toMap()
        tabs = buildOpenDocumentTabs(
            snapshots = taskEntries.map { (snapshot, _) ->
                liveDocuments[snapshot.taskId]?.let { snapshot.copy(documentUri = it.uri, taskLabel = it.title) } ?: snapshot
            },
            currentTaskId = activity.taskId,
            readerActivityClassName = MainActivity::class.java.name,
            currentDocument = live,
            displayTitles = displayTitles,
            noteActivityClassName = com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNoteActivity::class.java.name,
        )
        host.applyReaderDisplayTitle(displayTitles[activity.taskId])
        render(isFullscreen)
    }

    private fun reloadTitles() {
        if (released) return
        reloadJob?.cancel()
        reloadJob = activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val catalogue = runCatching { LibraryProjectStore(activity).projects() }
                    .onFailure { Log.w(tag, "Could not refresh document display titles", it) }.getOrNull()
                catalogue to sourceIndex.snapshot().libraryDisplayNamesByFilename
            }
            if (released) return@launch
            loaded.first?.let { projects = it }
            publicationTitles = loaded.second
            refresh(fullscreen)
        }
    }

    /** Keep the page this tab is left on, so another tab's "Copy for agent" can report it. */
    fun rememberPlace() {
        if (released) return
        val place = readerPlace()
        liveDocuments[activity.taskId]?.let { liveDocuments[activity.taskId] = it.copy(page = place.page, pages = place.pages) }
    }

    fun setFullscreen(isFullscreen: Boolean) {
        fullscreen = isFullscreen
        tabLayout.visibility = if (isFullscreen || tabs.isEmpty()) View.GONE else View.VISIBLE
    }

    fun release() {
        released = true
        reloadJob?.cancel()
        titleChangesJob.cancel()
        titleObserver.stopWatching()
        liveDocuments.remove(activity.taskId)
        tabLayout.removeOnTabSelectedListener(tabListener)
        tasksById = emptyMap()
        tabs = emptyList()
    }

    private companion object {
        val liveDocuments = mutableMapOf<Int, CurrentDocumentSnapshot>()
    }

    private fun close(documentTab: OpenDocumentTab) {
        val task = tasksById[documentTab.taskId] ?: return
        val returnToLibrary = shouldReturnToLibraryAfterClosing(tabs, documentTab)
        val replacementTask = replacementTabAfterClosing(tabs, documentTab)
            ?.let { tasksById[it.taskId] }
        val finish = {
            try {
                if (documentTab.isCurrent) {
                    when {
                        returnToLibrary -> activity.startActivity(Intent(activity, HomeActivity::class.java))
                        replacementTask != null -> replacementTask.moveToFront()
                        else -> activity.startActivity(Intent(activity, HomeActivity::class.java))
                    }
                }
                task.finishAndRemoveTask()
                if (!documentTab.isCurrent) {
                    refresh(isFullscreen = false)
                }
            } catch (error: RuntimeException) {
                Log.w(tag, "Unable to close document task ${documentTab.taskId}", error)
                refresh(isFullscreen = false)
            }
        }
        if (documentTab.isCurrent) {
            host.runAfterDirtyAnnotationPrompt(finish)
        } else {
            finish()
        }
    }

    private fun showTabMenu(anchor: View, documentTab: OpenDocumentTab) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, R.string.close_document_tabs_left).isEnabled =
            tabsOnSide(tabs, documentTab, left = true).isNotEmpty()
        popup.menu.add(0, 2, 1, R.string.close_document_tabs_right).isEnabled =
            tabsOnSide(tabs, documentTab, left = false).isNotEmpty()
        // Like the desktop tab menu. The tab shows its full title elided, so copy the complete name;
        // a document outside every Library project has no project name to copy.
        popup.menu.add(0, 3, 2, R.string.copy_document_tab_name)
        val note = isAgentNoteTab(documentTab.documentUri)
        val projectName = projectNames[documentTab.taskId]
        if (!note) popup.menu.add(0, 4, 3, R.string.copy_document_tab_project_name).isEnabled = projectName != null
        // Paste into Codex or Claude: the document, its source and the reader's place.
        if (!note) popup.menu.add(0, 6, 4, R.string.copy_document_for_agent)
        // Notes an agent sent about this document; known only for a Library document.
        val documentId = if (note) null else runCatching { documentIdOf(documentTab) }.getOrNull()
        if (!note) popup.menu.add(0, 7, 6, R.string.agent_notes).isEnabled = !documentId.isNullOrBlank()
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1, 2 -> closeSide(documentTab, left = item.itemId == 1)
                3 -> activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(documentTab.title, documentTab.title),
                )
                4 -> activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(projectName, projectName),
                )
                6 -> copyForAgent(documentTab)
                7 -> activity.startActivity(com.gitlab.mudlej.MjPdfReader.ui.agentnotes.AgentNotesListActivity.intent(
                    activity, documentId = documentId, subtitle = documentTab.title))
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun documentIdOf(documentTab: OpenDocumentTab): String? =
        agentDocumentFields(projects, titleTargetOf(documentTab), documentTabPath(documentTab.documentUri),
            PhoneRenderQueue.defaultCanonicalPdfDirectory(), documentTab.title)["document_id"]?.takeIf(String::isNotBlank)

    private fun copyForAgent(documentTab: OpenDocumentTab) {
        // The agent may answer with a note to draw on: listen for it while the user is in the agent app
        // (not while push delivers notes; AgentStudyListenerService.start says no then).
        com.gitlab.mudlej.MjPdfReader.data.agentnotes.AgentStudyListenerService.start(activity)
        val place = agentReaderPlaceForTab(documentTab.isCurrent, readerPlace, liveDocuments[documentTab.taskId])
        val description = describeForAgent(documentTab, place)
        val documentId = documentIdOf(documentTab)
        val pdf = java.io.File(documentTabPath(documentTab.documentUri))
        activity.lifecycleScope.launch {
            // As on the desktop: the cards reviewed and the feedback given on this version of the PDF follow.
            val feedback = withContext(Dispatchers.IO) {
                runCatching {
                    val sha = pdf.takeIf { it.isFile }?.let { com.gitlab.mudlej.MjPdfReader.data.review.basicPdfSha256(it) }.orEmpty()
                    val cards = com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore(activity.applicationContext)
                        .cardsWithHistory(pdf.nameWithoutExtension)
                    agentReviewedCardsSection(cards, com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.all(),
                        com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.groups(),
                        com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges::apply, sha) +
                        (documentId?.let { id ->
                            agentTextFeedbackSection(com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback.forDocument(id), sha)
                        } ?: "")
                }.onFailure { Log.w(tag, "Could not read the feedback for Copy for agent", it) }.getOrDefault("")
            }
            copyAgentText(activity, description + feedback)
        }
    }

    private fun titleTargetOf(documentTab: OpenDocumentTab): ReaderTitleTarget =
        titleTargets[documentTab.taskId] ?: liveDocuments[documentTab.taskId]?.titleTarget ?: ReaderTitleTarget()

    private fun describeForAgent(documentTab: OpenDocumentTab, place: AgentReaderPlace): String =
        agentDocumentDescription(projects, titleTargetOf(documentTab), documentTabPath(documentTab.documentUri),
            PhoneRenderQueue.defaultCanonicalPdfDirectory(), documentTab.title, place)

    private fun currentTab(): OpenDocumentTab = tabs.firstOrNull { it.isCurrent } ?: currentDocument().let { live ->
        val uri = live.uri.orEmpty()
        OpenDocumentTab(activity.taskId, uri, live.title?.takeIf(String::isNotBlank) ?: documentTabTitle(uri), true)
    }

    /** The current tab's "Copy for agent" text, as if the reader were at [place]. */
    fun currentDocumentDescription(place: AgentReaderPlace): String = describeForAgent(currentTab(), place)

    /** The current tab's document placeholders for the review card prompts ([agentDocumentFields]). */
    fun currentDocumentFields(): Map<String, String> {
        val current = currentTab()
        return agentDocumentFields(projects, titleTargetOf(current), documentTabPath(current.documentUri),
            PhoneRenderQueue.defaultCanonicalPdfDirectory(), current.title)
    }

    private fun closeSide(anchor: OpenDocumentTab, left: Boolean) {
        val closing = tabsOnSide(tabs, anchor, left)
        if (closing.isEmpty()) return
        val finish = {
            // Keep the anchor alive and close the current activity last.
            closing.sortedBy { it.isCurrent }.forEach { tab ->
                try {
                    if (tab.isCurrent) {
                        host.dismissPdfCommentSheet()
                        tasksById[anchor.taskId]?.moveToFront()
                    }
                    tasksById[tab.taskId]?.finishAndRemoveTask()
                } catch (error: RuntimeException) {
                    Log.w(tag, "Unable to close document task ${tab.taskId}", error)
                }
            }
            if (closing.none { it.isCurrent }) refresh(isFullscreen = false)
        }
        if (closing.any { it.isCurrent }) host.runAfterDirtyAnnotationPrompt(finish)
        else finish()
    }

    private fun render(isFullscreen: Boolean) {
        rendering = true
        try {
            tabLayout.removeAllTabs()
            tabs.forEach { documentTab ->
                val customView = activity.layoutInflater.inflate(
                    R.layout.item_open_document_tab,
                    tabLayout,
                    false,
                )
                customView.findViewById<TextView>(R.id.openDocumentTabTitle).apply {
                    text = documentTab.title
                    contentDescription = documentTab.title
                    // The notes window's tab, titled by the note on screen: a pen marks it among the PDFs.
                    if (isAgentNoteTab(documentTab.documentUri)) {
                        val size = (18 * resources.displayMetrics.density).toInt()
                        val pen = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_agent_note_outline)
                        pen?.setBounds(0, 0, size, size)
                        setCompoundDrawablesRelative(pen, null, null, null)
                        compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
                        TextViewCompat.setCompoundDrawableTintList(this, textColors)
                    }
                }
                customView.findViewById<ImageButton>(R.id.closeDocumentTab).apply {
                    contentDescription = activity.getString(R.string.close_document_tab_named, documentTab.title)
                    setOnClickListener { close(documentTab) }
                }
                val tab = tabLayout.newTab()
                    .setCustomView(customView)
                    .setContentDescription(documentTab.title)
                tabLayout.addTab(tab, false)
                tab.view.setOnLongClickListener {
                    showTabMenu(tab.view, documentTab)
                    true
                }
            }
            tabs.indexOfFirst { it.isCurrent }
                .takeIf { it >= 0 }
                ?.let { selectedIndex -> tabLayout.selectTab(tabLayout.getTabAt(selectedIndex)) }
            setFullscreen(isFullscreen)
        } finally {
            rendering = false
        }
    }

    @Suppress("DEPRECATION")
    private fun taskId(info: ActivityManager.RecentTaskInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.id
    }
}
