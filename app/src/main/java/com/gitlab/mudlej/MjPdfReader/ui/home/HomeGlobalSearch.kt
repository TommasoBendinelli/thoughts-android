// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchIndexer
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where comment-like results come from: the Overview tab (comments) and the To-dos tab. */
internal interface GlobalSearchCommentSource {
    fun globalSearchComments(query: GlobalSearchQuery): List<OverviewComment>
    fun globalSearchDocumentName(comment: OverviewComment): String
    fun openGlobalSearchComment(comment: OverviewComment)
}

/**
 * The one Home search: every word of the query against document names, the text of every PDF
 * page, review cards, comments, To-dos and the diary, whichever tab is showing.
 */
internal class HomeGlobalSearch(
    private val context: Context,
    private val scope: CoroutineScope,
    private val adapter: GlobalSearchAdapter,
    private val documents: () -> List<HomeItem>,
    private val comments: GlobalSearchCommentSource,
    private val todos: GlobalSearchCommentSource,
    private val diary: DiaryTabController,
    private val openDocument: (HomeItem) -> Unit,
    private val openPage: (item: HomeItem, pageIndex: Int, highlight: String?) -> Unit,
    private val openCard: (cardId: String) -> Unit,
    private val onOpen: () -> Unit,
) {
    private val indexer = GlobalSearchIndexer.get(context)
    private var query = GlobalSearchQuery.parse("")
    private var searchJob: Job? = null
    private var indexJob: Job? = null
    private var cardsJob: Job? = null
    private var cards: List<ReviewBrowserCard> = emptyList()
    private var indexProgress: GlobalSearchIndexer.Progress? = null

    fun setQuery(text: String) {
        query = GlobalSearchQuery.parse(text)
        runSearch(debounce = true)
    }

    /** Re-reads the review cards; called when the search opens. */
    fun reloadCards() {
        if (cardsJob?.isActive == true) return
        cardsJob = scope.launch {
            cards = runCatching {
                withContext(Dispatchers.IO) { ReviewStore(context).browserCards() }
            }.onFailure { Log.w(GlobalSearchIndexer.TAG, "cards unavailable: ${it.javaClass.simpleName}") }
                .getOrDefault(cards)
            if (!query.isEmpty) runSearch(debounce = false)
        }
    }

    /** Indexes new or changed PDFs in the background; cheap when nothing changed. */
    fun refreshIndex() {
        if (indexJob?.isActive == true) return
        val pdfs = documents().filter { it.available && it.uri.toString().isNotBlank() }.map {
            GlobalSearchIndexer.Pdf(it.uri.toString(), "${it.hash}:${it.sizeBytes}:${it.fileModifiedMillis}", it.uri)
        }
        indexJob = scope.launch {
            var lastShown = 0L
            runCatching {
                indexer.update(pdfs) { progress ->
                    scope.launch {
                        indexProgress = progress
                        val now = SystemClock.elapsedRealtime()
                        // While indexing, refresh shown results now and then, and once at the end.
                        if (!query.isEmpty && (progress == null || now - lastShown > REFRESH_WHILE_INDEXING_MS)) {
                            lastShown = now
                            runSearch(debounce = false)
                        }
                    }
                }
            }.onFailure { Log.w(GlobalSearchIndexer.TAG, "index update failed", it) }
        }
    }

    private fun runSearch(debounce: Boolean) {
        searchJob?.cancel()
        val current = query
        searchJob = scope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            if (current.isEmpty) {
                adapter.submitList(listOfNotNull(progressRow(), GlobalSearchRow.Status(context.getString(R.string.global_search_empty))))
                return@launch
            }
            val startedAt = SystemClock.elapsedRealtime()
            val rows = buildRows(current)
            adapter.submitList(rows)
            Log.d(GlobalSearchIndexer.TAG, "\"${current.raw}\": ${rows.count { it is GlobalSearchRow.Result }} results " +
                "in ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    private suspend fun buildRows(q: GlobalSearchQuery): List<GlobalSearchRow> {
        val items = documents()
        val itemsByUri = items.associateBy { it.uri.toString() }
        val itemsByStem = items.filter { it.pdfStem.isNotBlank() }.associateBy { it.pdfStem.lowercase() }
        val rows = mutableListOf<GlobalSearchRow>()
        progressRow()?.let(rows::add)

        val namedDocuments = items.filter { q.matches(documentSearchName(it)) }
            .sortedByDescending { q.matchedStemCount(displayName(it)) }
        section(rows, R.string.global_search_documents, namedDocuments) { item ->
            GlobalSearchRow.Result(
                key = "document:${item.uri}",
                title = displayName(item),
                subtitle = item.fileName.takeIf { it != displayName(item) }.orEmpty(),
                snippet = null,
                titleMatches = q.matchRanges(displayName(item)),
                open = { onOpen(); openDocument(item) },
            )
        }

        val pageRows = runCatching { indexer.searchPages(q) }.getOrDefault(emptyList())
            .filter { itemsByUri.containsKey(it.ref) }
            .groupBy { it.ref }
            .entries.sortedByDescending { it.value.size }
        val pageResults = pageRows.flatMap { (ref, hits) ->
            val item = itemsByUri.getValue(ref)
            hits.sortedBy { it.position }.take(PAGES_PER_DOCUMENT).map { hit ->
                GlobalSearchRow.Result(
                    key = "page:$ref#${hit.position}",
                    title = displayName(item),
                    subtitle = context.getString(R.string.global_search_page, hit.position) +
                        if (hits.size > PAGES_PER_DOCUMENT && hit === hits.minBy { it.position }) {
                            " · " + context.getString(R.string.global_search_more_pages, hits.size)
                        } else "",
                    snippet = q.snippet(hit.body),
                    open = { onOpen(); openPage(item, hit.position - 1, q.highlightWord(hit.body)) },
                )
            }
        }
        if (pageResults.isNotEmpty()) {
            rows += GlobalSearchRow.Header(context.getString(R.string.global_search_pages, pageRows.size))
            rows += pageResults.take(MAX_SECTION_ROWS)
        }

        val matchedCards = cards.filter { q.matches(cardText(it)) }
            .sortedByDescending { q.matchedStemCount(it.card.question) }
        section(rows, R.string.global_search_cards, matchedCards) { item ->
            val answer = listOf(item.answerText, item.card.note).firstOrNull { q.matchedStemCount(it) > 0 }
            GlobalSearchRow.Result(
                key = "card:${item.card.id}",
                title = item.card.question.ifBlank { context.getString(R.string.global_search_untitled_card) },
                subtitle = itemsByStem[item.card.pdfStem.lowercase()]?.let(::displayName) ?: item.documentDisplayName,
                snippet = answer?.let(q::snippet),
                titleMatches = q.matchRanges(item.card.question),
                open = { onOpen(); openCard(item.card.id) },
            )
        }

        commentSection(rows, R.string.global_search_comments, comments, q)
        commentSection(rows, R.string.global_search_todos, todos, q)

        val diaryEntries = runCatching { diary.globalSearchEntries(q) }.getOrDefault(emptyList())
        section(rows, R.string.global_search_diary, diaryEntries) { entry ->
            val snippet = q.snippet(entry.text, maxChars = 200)
            GlobalSearchRow.Result(
                key = "diary:${entry.id}",
                title = snippet.text,
                subtitle = DIARY_DATE.format(entry.at),
                snippet = null,
                titleMatches = snippet.matches,
                open = { onOpen(); diary.openGlobalSearchEntry(entry) },
            )
        }

        if (rows.none { it is GlobalSearchRow.Result }) {
            rows += GlobalSearchRow.Status(context.getString(R.string.global_search_no_results))
        }
        return rows
    }

    private fun commentSection(rows: MutableList<GlobalSearchRow>, header: Int, source: GlobalSearchCommentSource, q: GlobalSearchQuery) {
        val matched = runCatching { source.globalSearchComments(q) }.getOrDefault(emptyList())
        section(rows, header, matched) { comment ->
            val snippet = q.snippet(comment.contents.ifBlank { comment.searchText }, maxChars = 200)
            GlobalSearchRow.Result(
                key = "comment:${comment.documentStem}:${comment.id}",
                title = snippet.text,
                subtitle = source.globalSearchDocumentName(comment),
                snippet = null,
                titleMatches = snippet.matches,
                open = { onOpen(); source.openGlobalSearchComment(comment) },
            )
        }
    }

    private fun <T> section(rows: MutableList<GlobalSearchRow>, header: Int, items: List<T>, row: (T) -> GlobalSearchRow.Result) {
        if (items.isEmpty()) return
        rows += GlobalSearchRow.Header(context.getString(header, items.size))
        items.take(MAX_SECTION_ROWS).mapTo(rows, row)
    }

    private fun progressRow(): GlobalSearchRow.Status? = indexProgress?.let {
        GlobalSearchRow.Status(context.getString(R.string.global_search_indexing, it.done, it.total))
    }

    private fun cardText(item: ReviewBrowserCard) =
        listOf(item.card.question, item.answerText, item.card.note, item.documentDisplayName).joinToString("\n")

    private fun displayName(item: HomeItem) = item.libraryDisplayName.ifBlank { item.title }.ifBlank { item.fileName }

    private fun documentSearchName(item: HomeItem) = listOf(item.title, item.fileName, item.libraryDisplayName).joinToString(" ")

    companion object {
        private const val DEBOUNCE_MS = 120L
        private const val REFRESH_WHILE_INDEXING_MS = 3_000L
        private const val PAGES_PER_DOCUMENT = 3
        private const val MAX_SECTION_ROWS = 60
        private val DIARY_DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
}
