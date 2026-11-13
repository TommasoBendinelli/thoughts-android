// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderHistoryManager.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderHistoryManagerTest {

    /** One reader tab: the shown document and position, and what MainActivity does around the history. */
    private class Tab(var document: String?, var page: Int = 0, var view: PDFView.ViewState? = null) :
        ReaderHistoryManager.Viewport {
        var cancelNextOpen = false
        var opens = 0
        val history = ReaderHistoryManager(this, {}, { document }) { entry, commit ->
            if (cancelNextOpen) {
                cancelNextOpen = false
            } else {
                opens++
                commit()
                replaceDocument(entry.document!!)
                // The loader restores the saved view state, or the page without one.
                page = entry.viewState?.pageIndex ?: entry.pageIndex
                view = entry.viewState
            }
        }

        override val currentPage get() = page
        override fun captureViewState() = view
        override fun applyViewState(viewState: PDFView.ViewState?): Boolean {
            viewState ?: return false
            view = viewState
            page = viewState.pageIndex
            return true
        }
        override fun jumpTo(pageIndex: Int) {
            page = pageIndex
            view = null
        }

        private fun replaceDocument(target: String) {
            history.keepingHistory { history.onDocumentReset() }
            document = target
            page = 0
            view = null
        }

        /** A link to [target]'s page, followed in this tab (MainActivity.handleReaderIntent). */
        fun followLink(target: String, targetPage: Int, targetView: PDFView.ViewState? = state(targetPage)) {
            if (target == document) {
                history.recordJump(Origin.LINK, targetPage)
            } else {
                history.recordLeavingDocument(Origin.LINK)
                replaceDocument(target)
            }
            page = targetPage
            view = targetView
        }

        /** Reading on: the user scrolls or zooms without a history step. */
        fun moveTo(newPage: Int, zoom: Float = 1f) {
            page = newPage
            view = state(newPage, zoom)
        }

        fun place() = Triple(document, page, view?.zoom)
    }

    @Test
    fun backAfterLinkToAnotherDocumentReturnsToTheLinkAndForwardToTheTarget() {
        val tab = Tab(SDPO).apply { moveTo(4, zoom = 2.5f) }
        val atLink = tab.view

        tab.followLink(GRPO, 7)
        assertTrue(tab.history.canGoBack())
        assertFalse(tab.history.canGoForward())

        tab.moveTo(8, zoom = 1.75f)
        tab.history.goBack()
        assertEquals(Triple(SDPO, 4, 2.5f), tab.place())
        assertSame("Page, zoom and scroll of the link", atLink, tab.view)
        assertFalse(tab.history.canGoBack())
        assertTrue(tab.history.canGoForward())

        tab.history.goForward()
        assertEquals("Forward returns where grpo was left", Triple(GRPO, 8, 1.75f), tab.place())
        assertTrue(tab.history.canGoBack())
        assertFalse(tab.history.canGoForward())

        tab.history.goBack()
        assertEquals(Triple(SDPO, 4, 2.5f), tab.place())
        assertEquals(3, tab.opens)
    }

    @Test
    fun backWalksSeveralLinksAcrossDocumentsAndJumpsInsideThem() {
        val tab = Tab(SDPO).apply { moveTo(4, zoom = 2f) }
        tab.followLink(GRPO, 7)
        tab.followLink(DISTILLATION, 1)
        tab.history.recordJump(Origin.TOC, 9)
        tab.moveTo(9, zoom = 3f)
        tab.followLink(DISTILLATION, 12)

        val visited = mutableListOf(tab.place())
        while (tab.history.canGoBack()) {
            tab.history.goBack()
            visited += tab.place()
        }
        assertEquals(
            listOf(
                Triple(DISTILLATION, 12, 1f),
                Triple(DISTILLATION, 9, 3f),
                Triple(DISTILLATION, 1, 1f),
                Triple(GRPO, 7, 1f),
                Triple(SDPO, 4, 2f),
            ),
            visited,
        )

        val forward = mutableListOf<Triple<String?, Int, Float?>>()
        while (tab.history.canGoForward()) {
            tab.history.goForward()
            forward += tab.place()
        }
        assertEquals(visited.dropLast(1).reversed(), forward)
    }

    @Test
    fun linkInsideTheSameDocumentRestoresTheExactPosition() {
        val tab = Tab(SDPO).apply { moveTo(2, zoom = 2.25f) }
        val atLink = tab.view
        tab.followLink(SDPO, 11)
        assertEquals(0, tab.opens)

        tab.history.goBack()
        assertEquals(Triple(SDPO, 2, 2.25f), tab.place())
        assertSame(atLink, tab.view)
        tab.history.goForward()
        assertEquals(Triple(SDPO, 11, 1f), tab.place())
        assertEquals("No document is reopened for a jump inside it", 0, tab.opens)
    }

    @Test
    fun followingANewLinkAfterBackDropsTheForwardSteps() {
        val tab = Tab(SDPO).apply { moveTo(4) }
        tab.followLink(GRPO, 7)
        tab.history.goBack()
        assertTrue(tab.history.canGoForward())

        tab.followLink(DISTILLATION, 3)
        assertFalse(tab.history.canGoForward())
        tab.history.goBack()
        assertEquals(Triple(SDPO, 4, 1f), tab.place())
    }

    @Test
    fun cancelledDocumentChangeLeavesTheHistoryAsItWas() {
        val tab = Tab(SDPO).apply { moveTo(4) }
        tab.followLink(GRPO, 7)

        tab.cancelNextOpen = true // "Cancel" in the unsaved-highlights prompt
        tab.history.goBack()
        assertEquals(Triple(GRPO, 7, 1f), tab.place())
        assertTrue(tab.history.canGoBack())
        assertFalse(tab.history.canGoForward())

        tab.history.goBack()
        assertEquals(Triple(SDPO, 4, 1f), tab.place())
    }

    @Test
    fun openingAnotherDocumentOutsideTheHistoryStartsAFreshHistory() {
        val tab = Tab(SDPO).apply { moveTo(4) }
        tab.followLink(GRPO, 7)
        // e.g. a review card or the Library replacing the document of this tab
        tab.history.onDocumentReset()
        assertFalse(tab.history.canGoBack())
        assertFalse(tab.history.canGoForward())
    }

    @Test
    fun historyListSelectsAPlaceSeveralStepsBackInAnotherDocument() {
        val tab = Tab(SDPO).apply { moveTo(4, zoom = 2f) }
        tab.followLink(GRPO, 7)
        tab.followLink(DISTILLATION, 1)

        assertTrue(tab.history.goBackToBackStackIndex(0))
        assertEquals(Triple(SDPO, 4, 2f), tab.place())
        assertEquals(listOf(DISTILLATION, GRPO), tab.history.forwardEntries().map { it.document })

        assertTrue(tab.history.goForwardToForwardStackIndex(0))
        assertEquals(Triple(DISTILLATION, 1, 1f), tab.place())
        assertEquals(listOf(SDPO, GRPO), tab.history.backEntries().map { it.document })
    }

    @Test
    fun savedStateKeepsDocumentsAndPositions() {
        val view = state(4, 2.5f)
        val entries = listOf(
            ReaderHistoryManager.Entry(4, view, 1234L, Origin.LINK, SDPO),
            ReaderHistoryManager.Entry(9, null, 5678L, Origin.DWELL, null),
        )
        val decoded = ReaderHistoryManager.decodeEntries(ReaderHistoryManager.encodeEntries(entries))!!
        assertEquals(2, decoded.size)
        assertEquals(SDPO, decoded[0].document)
        assertEquals(Origin.LINK, decoded[0].origin)
        assertEquals(1234L, decoded[0].timestamp)
        val restored = decoded[0].viewState!!
        assertEquals(listOf(2.5f, 0.25f, 0.75f), listOf(restored.zoom, restored.relativeCrossAxisCenter, restored.pageCenterOffsetRatio))
        assertEquals(4, restored.pageIndex)
        assertTrue(restored.swipeVertical)
        assertNull(decoded[1].viewState)
        assertNull(decoded[1].document)
        assertNull(ReaderHistoryManager.decodeEntries("not json"))
    }

    private companion object {
        const val SDPO = "file:///data/user/0/org.tbend.thoughts/files/thoughts/current-pdfs/concepts_sdpo.pdf"
        const val GRPO = "file:///data/user/0/org.tbend.thoughts/files/thoughts/current-pdfs/concepts_grpo.pdf"
        const val DISTILLATION = "file:///data/user/0/org.tbend.thoughts/files/thoughts/current-pdfs/concepts_distillation.pdf"

        fun state(page: Int, zoom: Float = 1f) =
            PDFView.ViewState(zoom, page, true, false, 0.25f, 0.75f, 1, false)
    }
}
