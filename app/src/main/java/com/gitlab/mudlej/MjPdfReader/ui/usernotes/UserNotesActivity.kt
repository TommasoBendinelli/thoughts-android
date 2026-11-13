// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.core.io.appDateTimeFormatter
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.core.ui.AppSnackbar
import com.gitlab.mudlej.MjPdfReader.core.ui.SegmentedButtonStyler
import com.gitlab.mudlej.MjPdfReader.core.ui.attachFilterSearchView
import com.gitlab.mudlej.MjPdfReader.core.ui.configureSearchIcon
import com.gitlab.mudlej.MjPdfReader.core.ui.setupScreenChrome
import com.gitlab.mudlej.MjPdfReader.core.ui.tintIconsForChrome
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentCatalogue
import com.gitlab.mudlej.MjPdfReader.data.annotation.firstPrimaryOrNull
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.source.logicalReadScope
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityUserNotesBinding
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.TableOfContentsPathResolver
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

class UserNotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserNotesBinding
    private val commentAdapter = DocumentCommentsAdapter(::onCommentClicked)
    private var comments: List<DocumentCommentItem> = emptyList()
    private var tableOfContentsPathResolver = TableOfContentsPathResolver.EMPTY
    private var query: String = ""
    private var filter = DocumentCommentFilter.ALL
    private var sortByDate: Boolean = false
    private var actionBarMenu: Menu? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { destinationUri ->
        destinationUri?.let(::exportComments)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreenChrome()
        title = getString(R.string.comments_title)

        binding.userNotesRecyclerView.apply {
            adapter = commentAdapter
            layoutManager = LinearLayoutManager(this@UserNotesActivity)
        }
        setupFilter()
        binding.progressBar.visibility = View.VISIBLE
        binding.message.setText(R.string.loading)
        binding.message.visibility = View.VISIBLE

        loadTableOfContentsPaths()
        loadComments()
    }

    private fun setupFilter() {
        SegmentedButtonStyler.attach(binding.commentFilterGroup)
        binding.commentFilterGroup.check(R.id.commentsFilterAll)
        binding.commentFilterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            filter = when (checkedId) {
                R.id.commentsFilterUser -> DocumentCommentFilter.USER_COMMENTS
                R.id.commentsFilterAgent -> DocumentCommentFilter.AGENT_PROPOSALS
                else -> DocumentCommentFilter.ALL
            }
            refreshDisplayedComments()
        }
    }

    private fun loadTableOfContentsPaths() {
        lifecycleScope.launch {
            val resolver = TableOfContentsPathResolver.load(
                this@UserNotesActivity,
                intent.getStringExtra(PDF.filePathKey),
                intent.getStringExtra(PDF.passwordKey),
            )
            if (resolver !== TableOfContentsPathResolver.EMPTY) {
                tableOfContentsPathResolver = resolver
            }
        }
    }

    private fun loadComments() {
        val pdfFilename = documentFilename()
        lifecycleScope.launch {
            comments = withContext(Dispatchers.IO) {
                val catalogueComments = emptyList<com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment>()
                val sourceSnapshot = com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentStore(applicationContext).snapshot(pdfFilename)
                val currentHash = computeSha256(
                    applicationContext,
                    intent.getStringExtra(PDF.filePathKey)?.let(Uri::parse),
                )
                documentComments(pdfFilename, catalogueComments, sourceSnapshot, currentHash)
            }
            binding.progressBar.visibility = View.GONE
            refreshDisplayedComments()
            refreshMenu()
        }
    }

    private fun displayedComments(): List<DocumentCommentItem> {
        var displayed = filterDocumentComments(comments, filter, query)
        if (sortByDate) {
            displayed = displayed.sortedWith(
                compareBy<DocumentCommentItem> { it.comment.activityMillis <= 0 }
                    .thenByDescending { it.comment.activityMillis },
            )
        }
        return displayed
    }

    private fun refreshDisplayedComments(): Int {
        val displayed = displayedComments()
        commentAdapter.submitList(displayed)
        binding.message.setText(
            if (comments.isEmpty()) R.string.no_comments else R.string.comments_no_matches,
        )
        binding.message.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        return displayed.size
    }

    private fun refreshMenu() {
        val menu = actionBarMenu ?: return
        configureSearchIcon(menu, comments.isNotEmpty())
        menu.findItem(R.id.export_list)?.isVisible = comments.isNotEmpty()
        menu.findItem(R.id.sort_by_creation_date)?.let { sortItem ->
            sortItem.isVisible = comments.any { it.comment.activityMillis > 0 }
            sortItem.isChecked = sortByDate
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.annotation_list_menu, menu)
        menu.tintIconsForChrome(this)
        actionBarMenu = menu
        refreshMenu()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.attachFilterSearchView(
            binding.root,
            onQueryChanged = { newQuery ->
                query = newQuery
                refreshDisplayedComments()
            },
            onClosed = {
                query = ""
                refreshDisplayedComments()
                true
            },
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.sort_by_creation_date -> {
                sortByDate = !sortByDate
                item.isChecked = sortByDate
                refreshDisplayedComments()
            }
            R.id.export_list -> exportLauncher.launch(exportFileName())
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun onCommentClicked(item: DocumentCommentItem) {
        val resultIntent = Intent()
        resultIntent.putExtra(PDF.chosenTableOfContentsEntryKey, item.comment.pageIndex)
        resultIntent.putExtra(PDF.chosenCommentIdKey, item.comment.id)
        item.comment.logicalLocations.firstPrimaryOrNull()?.let { location ->
            resultIntent.putExtra(PDF.chosenLogicalCommentStatusKey, location.status)
            resultIntent.putExtra(PDF.chosenLogicalCommentPointKey, location.point.toFloatArray())
            resultIntent.putExtra(
                PDF.chosenLogicalCommentRegionsKey,
                location.regions.flatten().toFloatArray(),
            )
            resultIntent.putExtra(PDF.chosenLogicalCommentContentsKey, item.comment.contents)
        }
        setResult(PDF.TABLE_OF_CONTENTS_RESULT_OK, resultIntent)
        finish()
    }

    private fun documentFilename(): String = intent.getStringExtra(PDF.nameKey)
        ?.takeIf { it.isNotBlank() }
        ?: intent.getStringExtra(PDF.filePathKey)
            ?.let(Uri::parse)
            ?.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        ?: "document.pdf"

    private fun documentTitle(): String = documentFilename().let { filename ->
        if (filename.endsWith(".pdf", ignoreCase = true)) filename.dropLast(4) else filename
    }

    private fun exportFileName(): String {
        return "${documentTitle()} - ${getString(R.string.comments_title)}.txt"
    }

    private fun buildExportText(): String {
        val exported = displayedComments()
        val builder = StringBuilder()
        builder.appendLine(documentTitle())
        builder.appendLine(
            getString(
                R.string.export_comments_line,
                exported.size,
                LocalDateTime.now().format(appDateTimeFormatter),
            ),
        )
        for (item in exported) {
            val comment = item.comment
            builder.appendLine()
            builder.appendLine(EXPORT_SEPARATOR)
            val infoParts = mutableListOf(
                commentDisplayPage(comment.pageIndex)?.let { getString(R.string.bookmark_page_label, it) }
                    ?: getString(R.string.comments_location_unavailable),
                getString(
                    if (item.isAgentProposal) {
                        R.string.comments_filter_agent
                    } else {
                        R.string.comments_filter_user
                    },
                ),
            )
            comment.author.takeIf { it.isNotBlank() }?.let(infoParts::add)
            tableOfContentsPathResolver.resolve(comment.pageIndex)?.let(infoParts::add)
            builder.appendLine(infoParts.joinToString(" | "))
            if (comment.selectedText.isNotBlank()) {
                builder.appendLine(
                    "${getString(R.string.export_quote_label)}: “${comment.selectedText.trim()}”",
                )
            }
            builder.appendLine("${getString(R.string.comment)}: ${comment.contents}")
        }
        return builder.toString()
    }

    private fun exportComments(destinationUri: Uri) {
        lifecycleScope.launch {
            val text = buildExportText()
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openOutputStream(destinationUri, "wt")
                        ?: contentResolver.openOutputStream(destinationUri, "w")
                        ?: return@runCatching false
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                }.getOrDefault(false)
            }
            val messageRes = if (written) R.string.export_done else R.string.export_failed
            AppSnackbar.make(binding.root, getString(messageRes), Snackbar.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXPORT_SEPARATOR = "----------------------------------------"
    }
}
