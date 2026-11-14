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
 *
 * A link followed from an agent note puts the note in the history too. The note is shown by the
 * notes window, not by this tab: a step onto it brings that window up and leaves the tab as it was,
 * and the arrows on the note go on from the note's place here ([returnFromAgentNote]).
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
    /**
     * Shows the agent note of [Entry.note] in the notes window; the tab keeps its document.
     * canGoBack/canGoForward: whether the note has places before or after it in this history.
     * False when the note could not be shown.
     */
    private val openAgentNote: (entry: Entry, canGoBack: Boolean, canGoForward: Boolean) -> Boolean = { _, _, _ -> false },
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
        openAgentNote: (entry: Entry, canGoBack: Boolean, canGoForward: Boolean) -> Boolean = { _, _, _ -> false },
    ) : this(PdfViewViewport(pdfView), onChanged, currentDocument, openOtherDocument, openAgentNote)

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
        AGENT_NOTE(R.string.agent_note),
    }

    data class Entry(
        val pageIndex: Int,
        val viewState: PDFView.ViewState?,
        val timestamp: Long,
        val origin: Origin,
        /** The document of this place; null means the document shown now (entries saved by older versions). */
        val document: String? = null,
        /** Set for an agent note a link was followed from; [document] is then [AgentNotePlace.uri]. */
        val note: AgentNotePlace? = null,
    )

    private val backStack = ArrayDeque<Entry>()
    private val forwardStack = ArrayDeque<Entry>()
    private var navigatingInternally = false
    private var suppressDwellOnce = false
    private var currentPageIndex = -1
    private var arrivedAtElapsed = 0L
    private var keepOnDocumentReset = false
    /** The agent note a step showed in place of this tab's document; null while the tab shows it. */
    private var shownNote: Entry? = null
    /** The note the tab just came back from without an arrow, until the tab is in use again ([settle]). */
    private var cameBackFrom: Entry? = null

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
        shownNote = null
        cameBackFrom = null
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
        shownNote = null
        cameBackFrom = null
        pushBack(currentEntry(origin))
        forwardStack.clear()
        onChanged()
    }

    /**
     * Records the agent note a link was followed from, after the place this tab showed (if any):
     * Back from the linked place shows the note again, and Forward on the note comes back.
     */
    fun recordAgentNote(note: AgentNotePlace) {
        shownNote = null
        cameBackFrom = null
        pushBack(Entry(AGENT_NOTE_PAGE, null, System.currentTimeMillis(), Origin.AGENT_NOTE, note.uri, note))
        forwardStack.clear()
        onChanged()
    }

    /**
     * The tab is shown again after a step showed an agent note in its place. It is where it was;
     * [back] is the arrow pressed on the note (true Back, false Forward, null none), which goes on
     * from the note's place in this history.
     */
    fun returnFromAgentNote(back: Boolean?) {
        val note = shownNote ?: return
        shownNote = null
        if (back == null) {
            cameBackFrom = note
            return
        }
        val places = places()
        val index = places.indexOfFirst { it === note }
        if (index < 0) return
        // Null: the place this tab shows, already on screen.
        val target = places.getOrNull(if (back) index - 1 else index + 1) ?: return
        if (backStack.any { it === target }) goBackTo(target) else goForwardTo(target)
    }

    /** The tab is in use again (on screen and resumed): a later link from a note starts from what it shows. */
    fun settle() {
        cameBackFrom = null
    }

    /**
     * Records a link followed from the agent note [note] into this tab, to the document shown
     * ([sameDocument], at [targetPageIndex]) or another. Back from the linked place shows the note.
     * If the tab had just shown a note in its place, what it shows was ahead of that note and gives
     * way to the new place, as in a browser; otherwise it stays in the history, behind the note.
     */
    fun recordAgentNoteLink(note: AgentNotePlace, sameDocument: Boolean, targetPageIndex: Int?) {
        val atNote = shownNote ?: cameBackFrom
        shownNote = null
        cameBackFrom = null
        val places = places()
        val index = atNote?.let { shown -> places.indexOfFirst { it === shown } } ?: -1
        when {
            index >= 0 -> {
                val kept = places.take(index + 1).map { it ?: currentEntry(Origin.LINK) }
                backStack.clear()
                backStack.addAll(kept)
                forwardStack.clear()
                // Reading time on the place left does not put it back between the note and the link.
                suppressDwellOnce = true
            }
            currentDocument() == null -> Unit
            sameDocument -> recordJump(Origin.LINK, targetPageIndex)
            else -> recordLeavingDocument(Origin.LINK)
        }
        recordAgentNote(note)
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
        shownNote = null
        cameBackFrom = null
        val entry = backStack.lastOrNull() ?: return
        if (entry.note != null) {
            showNote(entry)
            return
        }
        step(entry) {
            if (!backStack.removeIdentical(entry)) return@step false
            pushForward(currentEntry(Origin.HISTORY))
            true
        }
    }

    fun goForward() {
        shownNote = null
        cameBackFrom = null
        val entry = forwardStack.lastOrNull() ?: return
        if (entry.note != null) {
            showNote(entry)
            return
        }
        step(entry) {
            if (!forwardStack.removeIdentical(entry)) return@step false
            pushBack(currentEntry(Origin.HISTORY))
            true
        }
    }

    fun goBackTo(entry: Entry) {
        shownNote = null
        cameBackFrom = null
        if (backStack.none { it === entry }) {
            return
        }
        if (entry.note != null) {
            showNote(entry)
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
        shownNote = null
        cameBackFrom = null
        if (forwardStack.none { it === entry }) {
            return
        }
        if (entry.note != null) {
            showNote(entry)
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
        shownNote = null
        cameBackFrom = null
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

    /** The places of this history in order, oldest first; null is the place on screen. */
    private fun places(): List<Entry?> = backStack + listOf(null) + forwardStack.asReversed()

    /**
     * Shows the agent note of [entry] in the notes window. The tab and its history stay as they
     * are, so coming back without an arrow finds the tab where it was; see [returnFromAgentNote].
     */
    private fun showNote(entry: Entry) {
        val places = places()
        val index = places.indexOfFirst { it === entry }
        shownNote = entry
        if (!openAgentNote(entry, index > 0, index in 0 until places.lastIndex)) shownNote = null
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
        /** An agent note is a place without pages. */
        private const val AGENT_NOTE_PAGE = 0

        /** Entries as JSON with fixed names, so saved state survives R8 renaming the classes. */
        fun encodeEntries(entries: Collection<Entry>): String = JsonArray().apply {
            entries.forEach { entry ->
                add(JsonObject().apply {
                    addProperty("page", entry.pageIndex)
                    addProperty("time", entry.timestamp)
                    addProperty("origin", entry.origin.name)
                    entry.document?.let { addProperty("document", it) }
                    entry.note?.let { addProperty("note", it.toJson()) }
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
                    val origin = Origin.entries.firstOrNull { it.name == json.get("origin")?.asString } ?: Origin.HISTORY
                    val note = json.get("note")?.takeIf { it.isJsonPrimitive }?.asString?.let(AgentNotePlace::fromJson)
                    // A note that cannot be read back is no place this tab could open.
                    if (origin == Origin.AGENT_NOTE && note == null) return@runCatching null
                    Entry(
                        json.get("page").asInt,
                        view,
                        json.get("time").asLong,
                        origin,
                        note?.uri ?: json.get("document")?.takeIf { it.isJsonPrimitive }?.asString,
                        note,
                    )
                }.getOrNull()
            }
        }.getOrNull()
    }
}
