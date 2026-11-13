// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.tableofcontents

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
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PdfContentsSheetBinding
import com.gitlab.mudlej.MjPdfReader.pdf.TableOfContentsEntry
import com.gitlab.mudlej.MjPdfReader.pdf.createPdfExtractor
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun currentContentsPath(entries: List<TableOfContentsEntry>, page: Int): String? {
    var current: TableOfContentsEntry? = null
    fun visit(entry: TableOfContentsEntry) {
        val previous = current
        if (entry.pageIdx in 0..page.toLong() &&
            (previous == null || entry.pageIdx > previous.pageIdx ||
                (entry.pageIdx == previous.pageIdx && entry.level >= previous.level))) {
            current = entry
        }
        entry.subEntries.forEach(::visit)
    }
    entries.forEach(::visit)
    return current?.path
}

/** A non-modal sheet in the reader's existing overlay host; it never replaces the PDF view. */
class ContentsSheetController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val scope: CoroutineScope,
    private val documentUri: () -> Uri?,
    private val password: () -> String?,
    private val beforeShow: () -> Unit,
    private val navigate: (Int) -> Unit,
) : TableOfContentsFunctions {
    private val adapter = TableOfContentsAdapter(this)
    private var entries: List<TableOfContentsEntry>? = null
    private var savedState = TableOfContentsState()
    private var sheet: PdfContentsSheetBinding? = null
    private var container: FrameLayout? = null
    private var behavior: BottomSheetBehavior<FrameLayout>? = null
    private var loadJob: Job? = null
    private var generation = 0
    private var settledState = BottomSheetBehavior.STATE_HALF_EXPANDED
    private var reopenAfterLoad = false
    private var restoringQuery = false
    private var syncPosted = false
    private val host get() = binding.commentSheetHost

    fun show() {
        if (sheet != null || activity.isFinishing || activity.isDestroyed) return
        val uri = documentUri() ?: return
        beforeShow()
        reopenAfterLoad = false
        val request = ++generation
        val content = PdfContentsSheetBinding.inflate(activity.layoutInflater, host, false)
        val frame = FrameLayout(activity).apply {
            isClickable = true
            elevation = dp(8).toFloat()
            addView(content.root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        val bottomSheet = BottomSheetBehavior<FrameLayout>().apply {
            isFitToContents = false
            isHideable = false
            // Only the explicit size button may resize the panel.
            isDraggable = false
            halfExpandedRatio = 0.5f
            skipCollapsed = false
            state = settledState
        }
        sheet = content
        container = frame
        behavior = bottomSheet
        host.visibility = View.VISIBLE
        host.addView(frame, CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { behavior = bottomSheet })
        val manager = LinearLayoutManager(activity)
        content.contentsList.layoutManager = manager
        content.contentsList.adapter = adapter
        // Changing the current row should not fade every visible title during PDF navigation.
        content.contentsList.itemAnimator = null
        adapter.setExpandedEntryPaths(savedState.expandedPaths)
        adapter.query = savedState.query
        restoringQuery = true
        content.contentsSearch.setText(savedState.query.orEmpty())
        restoringQuery = false
        content.contentsSearch.doAfterTextChanged {
            if (!restoringQuery) {
                adapter.query = it?.toString()?.trim()?.takeIf(String::isNotEmpty)
                adapter.refresh { updateMessage() }
            }
        }
        content.contentsSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }
        content.contentsSearch.setOnFocusChangeListener { _, focused ->
            if (focused) bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
        }
        content.contentsClose.setOnClickListener { dismiss() }
        content.contentsLocate.setOnClickListener { locateCurrentSection() }
        content.contentsResize.setOnClickListener {
            hideKeyboard()
            bottomSheet.state = if (bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_HALF_EXPANDED
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
        }
        updateResizeButton()
        content.contentsOptions.setOnClickListener { view ->
            PopupMenu(activity, view).apply {
                menu.add(0, 1, 0, R.string.contents_expand_all)
                menu.add(0, 2, 1, R.string.contents_collapse_all)
                setOnMenuItemClickListener {
                    if (it.itemId == 1) adapter.expandAll() else adapter.collapseAll()
                    true
                }
                show()
            }
        }
        bottomSheet.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (container !== frame) return
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    dismiss()
                } else {
                    if (newState in listOf(BottomSheetBehavior.STATE_COLLAPSED,
                            BottomSheetBehavior.STATE_HALF_EXPANDED, BottomSheetBehavior.STATE_EXPANDED)) {
                        settledState = newState
                        updateResizeButton()
                    }
                    scheduleVisibleAreaSync()
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) = scheduleVisibleAreaSync()
        })
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleVisibleAreaSync() }
        host.addOnLayoutChangeListener(hostLayoutListener)
        host.doOnLayout {
            if (container !== frame) return@doOnLayout
            configureSize()
            scheduleVisibleAreaSync()
        }
        content.contentsLocate.isEnabled = false
        content.contentsOptions.isEnabled = false
        val cached = entries
        if (cached != null) {
            publishEntries(cached)
        } else {
            val requestedPassword = password()
            loadJob = scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        val extractor = createPdfExtractor(activity, uri, requestedPassword)
                        try { extractor.getTableOfContents() } finally { extractor.close() }
                    }
                    if (request == generation && documentUri() == uri && sheet === content) {
                        entries = loaded
                        publishEntries(loaded)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("ContentsSheet", "Could not read document outline", error)
                    if (request == generation && sheet === content) {
                        content.contentsProgress.visibility = View.GONE
                        content.contentsMessage.setText(R.string.contents_load_failed)
                        content.contentsMessage.visibility = View.VISIBLE
                    }
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
        val button = sheet?.contentsResize ?: return
        val expanded = settledState == BottomSheetBehavior.STATE_EXPANDED
        button.setImageResource(if (expanded) R.drawable.ic_contents_collapse else R.drawable.ic_fullscreen_grey)
        val label = activity.getString(if (expanded) R.string.contents_collapse_panel else R.string.contents_expand_panel)
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

    private fun publishEntries(loaded: List<TableOfContentsEntry>) {
        val content = sheet ?: return
        content.contentsProgress.visibility = View.GONE
        adapter.currentEntryPath = currentContentsPath(loaded, binding.pdfView.currentPage)
        adapter.submitEntries(loaded) {
            if (sheet !== content) return@submitEntries
            (content.contentsList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                savedState.scrollPosition, savedState.scrollOffset,
            )
            updateMessage()
        }
        content.contentsLocate.isEnabled = adapter.currentEntryPath != null
        content.contentsOptions.isEnabled = loaded.any { it.hasSubEntries() }
    }

    private fun updateMessage() {
        val content = sheet ?: return
        content.contentsMessage.setText(if (entries.isNullOrEmpty()) R.string.no_table_of_contents
            else R.string.contents_no_matches)
        content.contentsMessage.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    fun onPageChanged(page: Int) {
        adapter.currentEntryPath = currentContentsPath(entries.orEmpty(), page)
        sheet?.contentsLocate?.isEnabled = adapter.currentEntryPath != null
    }

    private fun locateCurrentSection() {
        val path = adapter.currentEntryPath ?: return
        val content = sheet ?: return
        content.contentsSearch.setText("")
        hideKeyboard()
        adapter.expandAncestors(path)
        adapter.refresh {
            if (sheet !== content) return@refresh
            val position = adapter.currentList.indexOfFirst { it.entry.path == path }
            if (position >= 0) (content.contentsList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(position, content.contentsList.height / 3)
        }
    }

    override fun onEntryClicked(entry: TableOfContentsEntry) {
        if (entry.pageIdx !in 0 until binding.pdfView.pageCount.toLong()) return
        hideKeyboard()
        syncVisibleArea()
        navigate(entry.pageIdx.toInt())
        onPageChanged(entry.pageIdx.toInt())
    }

    fun handleBack(): Boolean {
        if (sheet == null) return false
        if (ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            hideKeyboard()
            return true
        }
        return dismiss()
    }

    private fun hideKeyboard() {
        sheet?.contentsSearch?.clearFocus()
        WindowCompat.getInsetsController(activity.window, binding.root).hide(WindowInsetsCompat.Type.ime())
    }

    private fun snapshot(): TableOfContentsState {
        val content = sheet ?: return savedState
        val manager = content.contentsList.layoutManager as LinearLayoutManager
        val position = manager.findFirstVisibleItemPosition()
        return TableOfContentsState(
            expandedPaths = adapter.getExpandedEntryPaths(),
            scrollPosition = position.takeIf { it >= 0 } ?: savedState.scrollPosition,
            scrollOffset = manager.findViewByPosition(position)?.top?.minus(content.contentsList.paddingTop)
                ?: savedState.scrollOffset,
            query = adapter.query,
        )
    }

    fun dismiss(): Boolean {
        val frame = container ?: return false
        savedState = snapshot()
        generation++
        loadJob?.cancel()
        loadJob = null
        hideKeyboard()
        sheet?.contentsList?.adapter = null
        sheet = null
        container = null
        syncPosted = false
        behavior = null
        host.removeOnLayoutChangeListener(hostLayoutListener)
        host.removeView(frame)
        if (host.childCount == 0) host.visibility = View.GONE
        binding.pdfView.clearContentViewport()
        return true
    }

    fun reset() {
        dismiss()
        entries = null
        savedState = TableOfContentsState()
        adapter.query = null
        adapter.setExpandedEntryPaths(emptyList())
        adapter.currentEntryPath = null
        adapter.submitEntries(emptyList())
        settledState = BottomSheetBehavior.STATE_HALF_EXPANDED
        reopenAfterLoad = false
    }

    fun saveState(bundle: Bundle) {
        snapshot().putInto(bundle)
        bundle.putBoolean("contents_sheet_open", sheet != null || reopenAfterLoad)
        bundle.putInt("contents_sheet_position", settledState)
    }

    fun restoreState(bundle: Bundle) {
        savedState = TableOfContentsState.from(bundle)
        settledState = if (bundle.getInt("contents_sheet_position") == BottomSheetBehavior.STATE_EXPANDED) {
            BottomSheetBehavior.STATE_EXPANDED
        } else {
            BottomSheetBehavior.STATE_HALF_EXPANDED
        }
        reopenAfterLoad = bundle.getBoolean("contents_sheet_open")
    }

    fun onDocumentLoaded() {
        if (reopenAfterLoad) show()
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
