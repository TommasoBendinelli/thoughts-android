// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.history

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityNavigationHistoryBinding
import com.gitlab.mudlej.MjPdfReader.ui.reader.documentTabTitle
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.AgentNotePlace
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderHistoryManager
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.TableOfContentsPathResolver
import kotlinx.coroutines.launch

class NavigationHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNavigationHistoryBinding
    private val historyAdapter = NavigationHistoryAdapter(::onHistoryEntryClicked)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavigationHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        title = getString(R.string.navigation_history)

        binding.navigationHistoryRecyclerView.apply {
            adapter = historyAdapter
            layoutManager = LinearLayoutManager(this@NavigationHistoryActivity)
        }
        val entries = historyEntriesFromIntent()
        showEntries(entries)
        loadTableOfContentsPaths(entries)
    }

    private fun loadTableOfContentsPaths(entries: List<NavigationHistoryRow>) {
        if (entries.isEmpty()) {
            return
        }
        lifecycleScope.launch {
            val resolver = TableOfContentsPathResolver.load(
                this@NavigationHistoryActivity,
                intent.getStringExtra(PDF.filePathKey),
                intent.getStringExtra(PDF.passwordKey),
            )
            if (resolver !== TableOfContentsPathResolver.EMPTY) {
                // Places in other documents keep their document name instead of this PDF's contents.
                showEntries(entries.map {
                    if (it.documentName != null) it else it.copy(tableOfContentsPath = resolver.resolve(it.pageIndex))
                })
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showEntries(entries: List<NavigationHistoryRow>) {
        historyAdapter.submitList(entries)
        binding.message.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun historyEntriesFromIntent(): List<NavigationHistoryRow> {
        val pages = intent.getIntArrayExtra(EXTRA_PAGES) ?: return emptyList()
        val origins = intent.getStringArrayExtra(EXTRA_ORIGINS) ?: return emptyList()
        val timestamps = intent.getLongArrayExtra(EXTRA_TIMESTAMPS) ?: return emptyList()
        val kinds = intent.getStringArrayExtra(EXTRA_KINDS) ?: return emptyList()
        val stackIndices = intent.getIntArrayExtra(EXTRA_STACK_INDICES) ?: return emptyList()
        val documents = intent.getStringArrayExtra(EXTRA_DOCUMENTS)?.takeIf { it.size == pages.size }
        if (pages.size != origins.size || pages.size != timestamps.size
            || pages.size != kinds.size || pages.size != stackIndices.size) {
            return emptyList()
        }

        return pages.indices.map { index ->
            val origin = ReaderHistoryManager.Origin.entries.firstOrNull { it.name == origins[index] }
                ?: ReaderHistoryManager.Origin.HISTORY
            val kind = NavigationHistoryRow.Kind.entries.firstOrNull { it.name == kinds[index] }
                ?: NavigationHistoryRow.Kind.BACK
            val documentName = documents?.get(index)?.takeIf { it.isNotEmpty() }
            NavigationHistoryRow(
                pageIndex = pages[index],
                origin = origin,
                timestamp = timestamps[index],
                kind = kind,
                stackIndex = stackIndices[index],
                tableOfContentsPath = documentName,
                documentName = documentName,
            )
        }
    }

    private fun onHistoryEntryClicked(entry: NavigationHistoryRow) {
        val resultIntent = Intent()
        when (entry.kind) {
            NavigationHistoryRow.Kind.BACK -> resultIntent.putExtra(EXTRA_SELECTED_BACK_STACK_INDEX, entry.stackIndex)
            NavigationHistoryRow.Kind.FORWARD -> resultIntent.putExtra(EXTRA_SELECTED_FORWARD_STACK_INDEX, entry.stackIndex)
            NavigationHistoryRow.Kind.CURRENT -> return
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    companion object {
        const val EXTRA_SELECTED_BACK_STACK_INDEX = "selectedBackStackIndex"
        const val EXTRA_SELECTED_FORWARD_STACK_INDEX = "selectedForwardStackIndex"

        private const val EXTRA_PAGES = "pages"
        private const val EXTRA_ORIGINS = "origins"
        private const val EXTRA_TIMESTAMPS = "timestamps"
        private const val EXTRA_KINDS = "kinds"
        private const val EXTRA_STACK_INDICES = "stackIndices"
        private const val EXTRA_DOCUMENTS = "documents"

        fun createIntent(
            context: Context,
            backEntries: List<ReaderHistoryManager.Entry>,
            forwardEntries: List<ReaderHistoryManager.Entry>,
            currentPageIndex: Int,
            currentDocument: String? = null,
        ): Intent {
            val pages = mutableListOf<Int>()
            val origins = mutableListOf<String>()
            val timestamps = mutableListOf<Long>()
            val kinds = mutableListOf<String>()
            val stackIndices = mutableListOf<Int>()
            val documents = mutableListOf<String>()

            fun addRow(
                pageIndex: Int,
                origin: ReaderHistoryManager.Origin,
                timestamp: Long,
                kind: NavigationHistoryRow.Kind,
                stackIndex: Int,
                document: String? = null,
                note: AgentNotePlace? = null,
            ) {
                // An agent note a link was followed from: its title, and no page.
                documents.add(note?.let { it.title ?: context.getString(R.string.agent_note) }
                    ?: document?.takeIf { it != currentDocument }?.let(::documentTabTitle).orEmpty())
                pages.add(if (note != null) NavigationHistoryRow.NO_PAGE else pageIndex)
                origins.add(origin.name)
                timestamps.add(timestamp)
                kinds.add(kind.name)
                stackIndices.add(stackIndex)
            }

            forwardEntries.forEachIndexed { index, entry ->
                addRow(entry.pageIndex, entry.origin, entry.timestamp, NavigationHistoryRow.Kind.FORWARD, index, entry.document, entry.note)
            }
            addRow(currentPageIndex, ReaderHistoryManager.Origin.HISTORY, 0L, NavigationHistoryRow.Kind.CURRENT, -1)
            backEntries.withIndex().toList().asReversed().forEach { (index, entry) ->
                addRow(entry.pageIndex, entry.origin, entry.timestamp, NavigationHistoryRow.Kind.BACK, index, entry.document, entry.note)
            }

            return Intent(context, NavigationHistoryActivity::class.java).apply {
                putExtra(EXTRA_PAGES, pages.toIntArray())
                putExtra(EXTRA_ORIGINS, origins.toTypedArray())
                putExtra(EXTRA_TIMESTAMPS, timestamps.toLongArray())
                putExtra(EXTRA_KINDS, kinds.toTypedArray())
                putExtra(EXTRA_STACK_INDICES, stackIndices.toIntArray())
                putExtra(EXTRA_DOCUMENTS, documents.toTypedArray())
            }
        }
    }
}

data class NavigationHistoryRow(
    val pageIndex: Int,
    val origin: ReaderHistoryManager.Origin,
    val timestamp: Long,
    val kind: Kind,
    val stackIndex: Int,
    val tableOfContentsPath: String?,
    /** Set for a place in another document than the one shown. */
    val documentName: String? = null,
) {
    enum class Kind { BACK, CURRENT, FORWARD }

    /** A place without pages (an agent note): [documentName] names it. */
    val isNote: Boolean get() = pageIndex == NO_PAGE

    companion object {
        const val NO_PAGE = -1
    }
}
