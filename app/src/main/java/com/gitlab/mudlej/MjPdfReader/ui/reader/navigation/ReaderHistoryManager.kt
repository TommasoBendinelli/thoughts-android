// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.StringRes
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.R
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Back/forward history of one reader tab. An entry is a place: a document and a position in it
 * (page plus the exact zoom and scroll when known). A link to another document opens it in the
 * same tab, so following it is one history step like a jump inside the document: Back reopens
 * the linking document where the link was, Forward returns to the linked one.
 */
class ReaderHistoryManager(
    private val viewport: Viewport,
    private val onChanged: () -> Unit,
    /** The URI of the document this tab shows now, or null before one is open. */
    private val currentDocument: () -> String? = { null },
    /**
     * Shows another document of the history in this tab. Call `commit` right before replacing the
     * document, while the one being left is still displayed; skip it if opening was cancelled.
     */
    private val openOtherDocument: (entry: Entry, commit: () -> Unit) -> Unit = { _, _ -> },
) {

    /** What the history needs from the PDF view, so the model can be tested without one. */
    interface Viewport {
        val currentPage: Int
        fun captureViewState(): PDFView.ViewState?
        fun applyViewState(viewState: PDFView.ViewState?): Boolean
        fun jumpTo(pageIndex: Int)
    }

    constructor(
        pdfView: () -> PDFView,
        onChanged: () -> Unit,
        currentDocument: () -> String? = { null },
        openOtherDocument: (entry: Entry, commit: () -> Unit) -> Unit = { _, _ -> },
    ) : this(PdfViewViewport(pdfView), onChanged, currentDocument, openOtherDocument)

    private class PdfViewViewport(private val pdfView: () -> PDFView) : Viewport {
        override val currentPage get() = pdfView().currentPage
        override fun captureViewState(): PDFView.ViewState? = pdfView().captureViewState()
        override fun applyViewState(viewState: PDFView.ViewState?) = pdfView().applyViewState(viewState)
        override fun jumpTo(pageIndex: Int) = pdfView().jumpTo(pageIndex)
    }

    enum class Origin(@StringRes val labelRes: Int) {
        LINK(R.string.history_origin_link),
        TOC(R.string.table_of_contents),
        GO_TO(R.string.go_to_page),
        SEARCH(R.string.search),
        BOOKMARK(R.string.history_origin_bookmark),
        TEXT_MODE(R.string.text_mode),
        DWELL(R.string.history_origin_dwell),
        HISTORY(R.string.history_origin_visited),
    }

    data class Entry(
        val pageIndex: Int,
        val viewState: PDFView.ViewState?,
        val timestamp: Long,
        val origin: Origin,
        /** The document of this place; null means the document shown now (entries saved by older versions). */
        val document: String? = null,
    )

    private val backStack = ArrayDeque<Entry>()
    private val forwardStack = ArrayDeque<Entry>()
    private var navigatingInternally = false
    private var suppressDwellOnce = false
    private var currentPageIndex = -1
    private var arrivedAtElapsed = 0L
    private var keepOnDocumentReset = false

    fun canGoBack() = backStack.isNotEmpty()

    fun canGoForward() = forwardStack.isNotEmpty()

    fun backEntries(): List<Entry> = backStack.toList()

    fun forwardEntries(): List<Entry> = forwardStack.toList()

    /** True when [entry] is a place in another document than the one shown now. */
    fun isOtherDocument(entry: Entry): Boolean {
        val document = entry.document ?: return false
        return document != currentDocument()
    }

    fun recordJump(origin: Origin, targetPageIndex: Int? = null) {
        if (targetPageIndex != null && targetPageIndex == viewport.currentPage) {
            return
        }
        targetPageIndex?.let { removePlace(backStack, currentDocument(), it) }
        pushBack(currentEntry(origin))
        forwardStack.clear()
        suppressDwellOnce = true
        onChanged()
    }

    /**
     * Records the place being left for another document in this tab, e.g. a link to another PDF.
     * The document change that follows keeps the history; see [keepingHistory].
     */
    fun recordLeavingDocument(origin: Origin) {
        pushBack(currentEntry(origin))
        forwardStack.clear()
        onChanged()
    }

    /** Runs [replaceDocument] so that the document reset it causes keeps this tab's history. */
    fun keepingHistory(replaceDocument: () -> Unit) {
        keepOnDocumentReset = true
        try {
            replaceDocument()
        } finally {
            keepOnDocumentReset = false
        }
    }

    /** The tab is about to show a different document or reload it. */
    fun onDocumentReset() {
        if (keepOnDocumentReset) {
            resetPageTracking()
            onChanged()
        } else {
            clear()
        }
    }

    fun goBack() {
        val entry = backStack.lastOrNull() ?: return
        step(entry) {
            if (!backStack.removeIdentical(entry)) return@step false
            pushForward(currentEntry(Origin.HISTORY))
            true
        }
    }

    fun goForward() {
        val entry = forwardStack.lastOrNull() ?: return
        step(entry) {
            if (!forwardStack.removeIdentical(entry)) return@step false
            pushBack(currentEntry(Origin.HISTORY))
            true
        }
    }

    fun goBackTo(entry: Entry) {
        if (backStack.none { it === entry }) {
            return
        }
        step(entry) {
            if (backStack.none { it === entry }) return@step false
            pushForward(currentEntry(Origin.HISTORY))
            while (backStack.isNotEmpty()) {
                val top = backStack.removeLast()
                if (top === entry) break
                pushForward(top)
            }
            true
        }
    }

    fun goBackToBackStackIndex(index: Int): Boolean {
        val entry = backStack.toList().getOrNull(index) ?: return false
        goBackTo(entry)
        return true
    }

    fun goForwardTo(entry: Entry) {
        if (forwardStack.none { it === entry }) {
            return
        }
        step(entry) {
            if (forwardStack.none { it === entry }) return@step false
            pushBack(currentEntry(Origin.HISTORY))
            while (forwardStack.isNotEmpty()) {
                val top = forwardStack.removeLast()
                if (top === entry) break
                pushBack(top)
            }
            true
        }
    }

    fun goForwardToForwardStackIndex(index: Int): Boolean {
        val entry = forwardStack.toList().getOrNull(index) ?: return false
        goForwardTo(entry)
        return true
    }

    fun onPageChanged(pageIndex: Int) {
        val now = SystemClock.elapsedRealtime()
        val leftPage = currentPageIndex
        val dwellMs = now - arrivedAtElapsed
        val skipDwell = navigatingInternally || suppressDwellOnce
        navigatingInternally = false
        suppressDwellOnce = false
        currentPageIndex = pageIndex
        arrivedAtElapsed = now
        if (leftPage < 0 || leftPage == pageIndex || skipDwell) {
            return
        }
        if (dwellMs < DWELL_THRESHOLD_MS) {
            return
        }
        val document = currentDocument()
        if (backStack.lastOrNull()?.let { isSamePlace(it, document, leftPage) } == true) {
            return
        }
        pushBack(Entry(leftPage, null, System.currentTimeMillis(), Origin.DWELL, document))
        forwardStack.clear()
        onChanged()
    }

    fun clear() {
        backStack.clear()
        forwardStack.clear()
        resetPageTracking()
        onChanged()
    }

    fun saveState(outState: Bundle) {
        outState.putString(BACK_KEY, encodeEntries(backStack))
        outState.putString(FORWARD_KEY, encodeEntries(forwardStack))
    }

    fun restoreState(savedState: Bundle) {
        restoreEntries(savedState.getString(BACK_KEY), backStack)
        restoreEntries(savedState.getString(FORWARD_KEY), forwardStack)
        onChanged()
    }

    /** Applies [mutate] to the stacks and shows [entry]; for another document only once it opens. */
    private fun step(entry: Entry, mutate: () -> Boolean) {
        if (!isOtherDocument(entry)) {
            if (mutate()) navigate(entry)
            return
        }
        openOtherDocument(entry) {
            if (mutate()) {
                removePlace(backStack, entry.document, entry.pageIndex)
                removePlace(forwardStack, entry.document, entry.pageIndex)
                onChanged()
            }
        }
    }

    private fun currentEntry(origin: Origin): Entry =
        Entry(viewport.currentPage, viewport.captureViewState(), System.currentTimeMillis(), origin, currentDocument())

    private fun navigate(entry: Entry) {
        val document = currentDocument()
        removePlace(backStack, document, entry.pageIndex)
        removePlace(forwardStack, document, entry.pageIndex)
        navigatingInternally = entry.pageIndex != viewport.currentPage
        if (!viewport.applyViewState(entry.viewState)) {
            viewport.jumpTo(entry.pageIndex)
        }
        onChanged()
    }

    private fun resetPageTracking() {
        navigatingInternally = false
        suppressDwellOnce = false
        currentPageIndex = -1
        arrivedAtElapsed = 0L
    }

    private fun pushBack(entry: Entry) {
        pushUnique(backStack, entry)
    }

    private fun pushForward(entry: Entry) {
        pushUnique(forwardStack, entry)
    }

    private fun pushUnique(stack: ArrayDeque<Entry>, entry: Entry) {
        removePlace(stack, entry.document ?: currentDocument(), entry.pageIndex)
        stack.addLast(entry)
        while (stack.size > MAX_ENTRIES) {
            stack.removeFirst()
        }
    }

    private fun isSamePlace(entry: Entry, document: String?, pageIndex: Int): Boolean =
        entry.pageIndex == pageIndex && (entry.document ?: currentDocument()) == document

    private fun removePlace(stack: ArrayDeque<Entry>, document: String?, pageIndex: Int) {
        val retained = stack.filterNot { isSamePlace(it, document, pageIndex) }
        if (retained.size == stack.size) {
            return
        }
        stack.clear()
        stack.addAll(retained)
    }

    private fun ArrayDeque<Entry>.removeIdentical(entry: Entry): Boolean {
        val index = indexOfFirst { it === entry }
        if (index < 0) return false
        removeAt(index)
        return true
    }

    private fun restoreEntries(encoded: String?, stack: ArrayDeque<Entry>) {
        val entries = encoded?.let(::decodeEntries) ?: return
        stack.clear()
        entries.forEach { pushUnique(stack, it) }
    }

    companion object {
        private const val DWELL_THRESHOLD_MS = 30_000L
        private const val MAX_ENTRIES = 50
        private const val BACK_KEY = "readerHistoryBack"
        private const val FORWARD_KEY = "readerHistoryForward"

        /** Entries as JSON with fixed names, so saved state survives R8 renaming the classes. */
        fun encodeEntries(entries: Collection<Entry>): String = JsonArray().apply {
            entries.forEach { entry ->
                add(JsonObject().apply {
                    addProperty("page", entry.pageIndex)
                    addProperty("time", entry.timestamp)
                    addProperty("origin", entry.origin.name)
                    entry.document?.let { addProperty("document", it) }
                    entry.viewState?.let { state ->
                        add("view", JsonObject().apply {
                            addProperty("zoom", state.zoom)
                            addProperty("page", state.pageIndex)
                            addProperty("vertical", state.swipeVertical)
                            addProperty("rtl", state.horizontalReadingDirectionRtl)
                            addProperty("crossCenter", state.relativeCrossAxisCenter)
                            addProperty("pageCenter", state.pageCenterOffsetRatio)
                            addProperty("perRow", state.pagesPerRow)
                            addProperty("firstAlone", state.firstPageAlone)
                        })
                    }
                })
            }
        }.toString()

        fun decodeEntries(encoded: String): List<Entry>? = runCatching {
            JsonParser.parseString(encoded).asJsonArray.mapNotNull { element ->
                runCatching {
                    val json = element.asJsonObject
                    val view = json.get("view")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                        PDFView.ViewState(
                            it.get("zoom").asFloat,
                            it.get("page").asInt,
                            it.get("vertical").asBoolean,
                            it.get("rtl").asBoolean,
                            it.get("crossCenter").asFloat,
                            it.get("pageCenter").asFloat,
                            it.get("perRow").asInt,
                            it.get("firstAlone").asBoolean,
                        )
                    }
                    Entry(
                        json.get("page").asInt,
                        view,
                        json.get("time").asLong,
                        Origin.entries.firstOrNull { it.name == json.get("origin")?.asString } ?: Origin.HISTORY,
                        json.get("document")?.takeIf { it.isJsonPrimitive }?.asString,
                    )
                }.getOrNull()
            }
        }.getOrNull()
    }
}
