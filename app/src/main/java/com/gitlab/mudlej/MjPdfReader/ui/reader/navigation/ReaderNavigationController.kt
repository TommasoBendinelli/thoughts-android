// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.ui.links.EXTRA_REMOTE_PDF_LINK
import com.gitlab.mudlej.MjPdfReader.ui.links.installedRemotePdf
import com.gitlab.mudlej.MjPdfReader.ui.links.parseRemotePdfLink
import com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler
import com.github.barteksc.pdfviewer.link.LinkHandler
import com.github.barteksc.pdfviewer.model.LinkTapEvent
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.ui.reader.DocumentState
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReviewCardsSheetController
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReviewCardsSheetItem
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReviewCardsSheetMergeOutcome
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.pdf.SearchResult
import com.gitlab.mudlej.MjPdfReader.pdf.grantPdfReadAccess
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.userbookmarks.UserBookmarksActivity
import com.gitlab.mudlej.MjPdfReader.ui.userhighlights.UserHighlightsActivity
import com.gitlab.mudlej.MjPdfReader.ui.usernotes.CommentsSheetController
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.ui.history.NavigationHistoryActivity
import com.gitlab.mudlej.MjPdfReader.ui.gotopage.GoToPageActivity
import com.gitlab.mudlej.MjPdfReader.ui.links.LinksActivity
import com.gitlab.mudlej.MjPdfReader.ui.tableofcontents.ContentsSheetController
import kotlinx.coroutines.CoroutineScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.max

class ReaderNavigationController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val pdf: DocumentState,
    private val pref: Preferences,
    private val historyManager: ReaderHistoryManager,
    private val onPageDisplayed: (Int) -> Unit,
    private val updateAppTitle: () -> Unit,
    scope: CoroutineScope,
    beforeShowContents: () -> Unit,
    private val launchUserBookmarks: (Intent) -> Unit,
    private val previewComment: (OverviewComment, Boolean) -> Unit,
    private val openCommentThread: (OverviewComment) -> Boolean,
    private val exportComments: () -> Unit,
    private val launchUserHighlights: (Intent) -> Unit,
    private val launchNavigationHistory: (Intent) -> Unit,
    private val launchLinks: (Intent) -> Unit,
    private val launchSearch: (Intent) -> Unit,
    private val launchGoToPageGrid: (Intent) -> Unit,
    loadReviewCards: suspend () -> com.gitlab.mudlej.MjPdfReader.ui.reader.ReviewCardsSheetContent,
    highlightReviewCard: (ReviewCardsSheetItem?) -> Unit,
    openReviewCard: (ReviewCardsSheetItem) -> Unit,
    reviewCardsSheetOpenChanged: (Boolean) -> Unit,
    openRetiredReviewCard: (ReviewCardsSheetItem) -> Unit = {},
    mergeReviewCards: (suspend (List<ReviewCardsSheetItem>, String, String) -> ReviewCardsSheetMergeOutcome)? = null,
) {

    private val renderUri get() = pdf.renderUri ?: pdf.uri
    private val searchNavigationController =
        SearchNavigationController(activity, binding, pdf, pref, historyManager, launchSearch)
    private val contentsSheet = ContentsSheetController(
        activity, binding, scope, { renderUri }, { pdf.password }, beforeShowContents,
    ) { pageIndex ->
        historyManager.recordJump(ReaderHistoryManager.Origin.TOC, pageIndex)
        resetSearchResultState()
        binding.pdfView.jumpTo(pageIndex)
    }

    private val commentsSheet: CommentsSheetController = CommentsSheetController(
        activity, binding, scope, { renderUri }, { pdf.name },
        { contentsSheet.dismiss(); reviewCardsSheet.dismiss(); beforeShowContents() },
        { comment, proposal ->
            historyManager.recordJump(ReaderHistoryManager.Origin.BOOKMARK, comment.pageIndex)
            resetSearchResultState()
            previewComment(comment, proposal)
        },
        openCommentThread, exportComments, { pdf.projectReading },
    )

    private val reviewCardsSheet: ReviewCardsSheetController = ReviewCardsSheetController(
        activity, binding, scope,
        { contentsSheet.dismiss(); commentsSheet.dismiss(); beforeShowContents() },
        loadReviewCards, highlightReviewCard, openReviewCard, reviewCardsSheetOpenChanged, openRetiredReviewCard,
        mergeReviewCards,
    )

    fun search(query: String, preferredPage: Int = 0) {
        searchNavigationController.search(query, preferredPage)
    }

    fun createLinkHandler(): LinkHandler = BackTrackingLinkHandler()

    fun showLinks() {
        Intent(activity, LinksActivity::class.java).also { linksIntent ->
            linksIntent.putExtra(PDF.filePathKey, renderUri.toString())
            linksIntent.putExtra(PDF.passwordKey, pdf.password)
            linksIntent.grantPdfReadAccess(renderUri.toString())
            launchLinks(linksIntent)
        }
    }

    fun showTableOfContents() { commentsSheet.dismiss(); reviewCardsSheet.dismiss(); contentsSheet.show() }

    fun showReviewCards() = reviewCardsSheet.show()

    /** After a Sync: the open Review Cards sheet reads its cards again (a merge shows as one card). */
    fun refreshReviewCards() = reviewCardsSheet.refresh()

    fun dismissTableOfContents(): Boolean {
        val commentsClosed = commentsSheet.dismiss()
        val reviewCardsClosed = reviewCardsSheet.dismiss()
        return contentsSheet.dismiss() || commentsClosed || reviewCardsClosed
    }

    fun handleTableOfContentsBack(): Boolean =
        reviewCardsSheet.handleBack() || commentsSheet.handleBack() || contentsSheet.handleBack()

    fun onDocumentLoaded() {
        contentsSheet.onDocumentLoaded(); commentsSheet.onDocumentLoaded(); reviewCardsSheet.onDocumentLoaded()
    }

    fun showGoToPageGrid() {
        Intent(activity, GoToPageActivity::class.java).also { gridIntent ->
            gridIntent.putExtra(PDF.filePathKey, renderUri.toString())
            gridIntent.putExtra(PDF.passwordKey, pdf.password)
            gridIntent.putExtra(PDF.pageNumberKey, pdf.pageNumber)
            gridIntent.grantPdfReadAccess(renderUri.toString())
            launchGoToPageGrid(gridIntent)
        }
    }

    fun showUserBookmarks() {
        Intent(activity, UserBookmarksActivity::class.java).also { bookmarksIntent ->
            pdf.fileHash?.let { bookmarksIntent.putExtra(PDF.fileHashKey, it) }
            bookmarksIntent.putExtra(PDF.filePathKey, renderUri.toString())
            bookmarksIntent.putExtra(PDF.passwordKey, pdf.password)
            bookmarksIntent.grantPdfReadAccess(renderUri.toString())
            launchUserBookmarks(bookmarksIntent)
        }
    }

    fun showUserNotes() = commentsSheet.showComments()

    fun showAgentEdits() = commentsSheet.showAgentEdits()

    fun showAgentProposal(proposalId: String) = commentsSheet.showProposal(proposalId)

    fun commentsExportText(): String = commentsSheet.exportText()

    fun showUserHighlights() {
        Intent(activity, UserHighlightsActivity::class.java).also { highlightsIntent ->
            highlightsIntent.putExtra(PDF.filePathKey, renderUri.toString())
            highlightsIntent.putExtra(PDF.passwordKey, pdf.password)
            highlightsIntent.putExtra(PDF.nameKey, pdf.name)
            highlightsIntent.grantPdfReadAccess(renderUri.toString())
            launchUserHighlights(highlightsIntent)
        }
    }

    fun showNavigationHistory() {
        val backEntries = historyManager.backEntries()
        val forwardEntries = historyManager.forwardEntries()
        if (backEntries.isEmpty() && forwardEntries.isEmpty()) {
            return
        }
        val historyIntent = NavigationHistoryActivity.createIntent(
            activity,
            backEntries,
            forwardEntries,
            binding.pdfView.currentPage,
            pdf.uri?.toString(),
        )
        historyIntent.putExtra(PDF.filePathKey, renderUri.toString())
        historyIntent.putExtra(PDF.passwordKey, pdf.password)
        historyIntent.grantPdfReadAccess(renderUri.toString())
        launchNavigationHistory(historyIntent)
    }

    fun onPageChanged(pageIndex: Int) {
        contentsSheet.onPageChanged(pageIndex)
        historyManager.onPageChanged(pageIndex)
        onPageDisplayed(pageIndex)
    }

    fun onFileHashComputed() {
        onPageDisplayed(pdf.pageNumber)
    }

    fun clearActiveSearchResultHighlight() {
        searchNavigationController.clearHighlight()
    }

    fun resetSearchResultState() {
        searchNavigationController.reset()
    }

    fun resetTableOfContentsState() { contentsSheet.reset(); commentsSheet.reset(); reviewCardsSheet.reset() }

    fun saveState(outState: Bundle) {
        contentsSheet.saveState(outState); commentsSheet.saveState(outState); reviewCardsSheet.saveState(outState)
    }

    fun restoreState(savedState: Bundle) {
        contentsSheet.restoreState(savedState); commentsSheet.restoreState(savedState); reviewCardsSheet.restoreState(savedState)
    }

    fun handleUserBookmarksResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.TABLE_OF_CONTENTS_RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.chosenTableOfContentsEntryKey, pdf.pageNumber) ?: return
            historyManager.recordJump(ReaderHistoryManager.Origin.BOOKMARK, pageIndex)
            binding.pdfView.jumpTo(pageIndex)
        }
    }

    fun handleUserNotesResult(resultCode: Int, intent: Intent?) {
        handleAnnotationListResult(resultCode, intent)
    }

    fun handleUserHighlightsResult(resultCode: Int, intent: Intent?) {
        handleAnnotationListResult(resultCode, intent)
    }

    private fun handleAnnotationListResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.TABLE_OF_CONTENTS_RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.chosenTableOfContentsEntryKey, pdf.pageNumber) ?: return
            historyManager.recordJump(ReaderHistoryManager.Origin.BOOKMARK, pageIndex)
            focusOnHighlight(
                pageIndex,
                intent.getStringExtra(PDF.chosenHighlightGroupKey),
                intent.getIntExtra(PDF.chosenHighlightAnnotationIndexKey, -1),
            )
        }
    }

    private fun focusOnHighlight(pageIndex: Int, groupKey: String?, annotationIndex: Int) {
        val bounds = binding.pdfView.findHighlightPdfBounds(pageIndex, groupKey, annotationIndex)
        if (bounds == null) {
            binding.pdfView.jumpTo(pageIndex)
            return
        }
        val targetZoom = max(binding.pdfView.zoom, binding.pdfView.midZoom)
            .coerceAtMost(binding.pdfView.maxZoom)
        if (binding.pdfView.focusOnPdfRect(pageIndex, bounds, targetZoom) == null) {
            binding.pdfView.jumpTo(pageIndex)
        }
    }

    fun handleNavigationHistoryResult(resultCode: Int, intent: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val data = intent ?: return
            val forwardStackIndex = data.getIntExtra(NavigationHistoryActivity.EXTRA_SELECTED_FORWARD_STACK_INDEX, -1)
            if (forwardStackIndex >= 0) {
                historyManager.goForwardToForwardStackIndex(forwardStackIndex)
                return
            }
            val backStackIndex = data.getIntExtra(NavigationHistoryActivity.EXTRA_SELECTED_BACK_STACK_INDEX, -1)
            if (backStackIndex >= 0) {
                historyManager.goBackToBackStackIndex(backStackIndex)
            }
        }
    }

    fun handleTextModeResult(resultCode: Int, intent: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.pageNumberKey, pdf.pageNumber) ?: return
            val pageCount = binding.pdfView.pageCount
            val boundedPageIndex = if (pageCount > 0) pageIndex.coerceIn(0, pageCount - 1) else pageIndex.coerceAtLeast(0)
            historyManager.recordJump(ReaderHistoryManager.Origin.TEXT_MODE, boundedPageIndex)
            binding.pdfView.jumpTo(boundedPageIndex)
        }
    }

    fun handleGoToPageGridResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.GO_TO_PAGE_RESULT_OK) {
            val pageIndex = intent?.getIntExtra(PDF.chosenPageIndexKey, pdf.pageNumber) ?: return
            historyManager.recordJump(ReaderHistoryManager.Origin.GO_TO, pageIndex)
            binding.pdfView.jumpTo(pageIndex)
        }
    }

    fun handleLinksResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.LINK_RESULT_OK) {
            intent?.getStringExtra(PDF.attachmentLinkResultKey)?.let(com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink::parse)?.let { path ->
                (activity as? MainActivity)?.attachmentReaderOrNull?.openLink(path)
                return
            }
            val pageNumber = intent?.getIntExtra(PDF.linkResultKey, pdf.pageNumber) ?: return
            val pageIndex = pageNumber - 1
            historyManager.recordJump(ReaderHistoryManager.Origin.LINK, pageIndex)
            binding.pdfView.jumpTo(pageIndex)
        }
    }

    fun handleSearchResult(resultCode: Int, intent: Intent?) {
        if (resultCode == PDF.SEARCH_RESULT_OK) {
            val searchResultJson = intent?.getStringExtra(PDF.searchResultKey) ?: return
            val searchResultType = object : TypeToken<SearchResult>() {}.type
            val searchResult = Gson().fromJson<SearchResult>(searchResultJson, searchResultType)

            searchNavigationController.start(
                searchResult,
                intent.getStringExtra(PDF.searchQueryResultKey),
                intent.getBooleanExtra(PDF.searchIgnoreAccentsKey, false),
            )
        }
    }

    /**
     * Opens another installed PDF at a link's destination in this same tab, so the link is one
     * step of this tab's history (Back returns to the link, Forward to the destination).
     */
    private fun openRemotePdfLink(uri: String?): Boolean {
        val link = parseRemotePdfLink(uri) ?: return false
        val file = installedRemotePdf(ThoughtsLocalStore.currentPdfs(), link.filename)
        if (file == null) {
            Toast.makeText(activity, R.string.remote_pdf_link_unavailable, Toast.LENGTH_LONG).show()
            return true
        }
        val linkIntent = Intent(activity, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.fromFile(file)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_REMOTE_PDF_LINK, uri)
        }
        // Delivered directly rather than through startActivity, whose task choice depends on
        // documentLaunchMode and on which tabs exist.
        (activity as? MainActivity)?.handleReaderIntent(linkIntent) ?: activity.startActivity(linkIntent)
        return true
    }

    /**
     * Consulted first for every tapped link; true when it opened something else instead
     * (a PDF link override). Set by the reader composition.
     */
    var linkOverrideHandler: ((LinkTapEvent) -> Boolean)? = null

    private val defaultLinkHandler by lazy { DefaultLinkHandler(binding.pdfView) }

    /** Follows a link to its own destination, ignoring any link override. */
    fun followOriginalLink(event: LinkTapEvent) {
        if (openRemotePdfLink(event.link.uri)) return
        // A thoughts-html: page opens over the reader; the document shown owns cards made in it.
        val attachmentPath = event.link.uri?.let(com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink::parse)
        val reader = activity as? MainActivity
        if (attachmentPath != null && reader?.attachmentReaderOrNull != null) {
            reader.attachmentReader.openLink(attachmentPath)
            return
        }
        if (com.gitlab.mudlej.MjPdfReader.ui.html.HtmlAttachmentActivity.open(activity, event.link.uri, pdf.name)) return
        val destPageIndex = event.link.destPageIdx
        if (event.link.uri.isNullOrEmpty() && destPageIndex != null) {
            historyManager.recordJump(ReaderHistoryManager.Origin.LINK, destPageIndex)
            if (!binding.pdfView.focusOnLinkDestination(event.link)) {
                binding.pdfView.jumpTo(destPageIndex)
            }
            resetSearchResultState()
        } else {
            defaultLinkHandler.handleLinkEvent(event)
        }
    }

    private inner class BackTrackingLinkHandler : LinkHandler {
        override fun handleLinkEvent(event: LinkTapEvent) {
            if (linkOverrideHandler?.invoke(event) == true) return
            followOriginalLink(event)
        }
    }
}
