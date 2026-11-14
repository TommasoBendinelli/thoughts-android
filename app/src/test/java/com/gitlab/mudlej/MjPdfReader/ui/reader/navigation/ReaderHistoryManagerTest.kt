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
        /** The agent notes the notes window was asked to show, with the arrows it offered there. */
        val notesShown = mutableListOf<Triple<AgentNotePlace, Boolean, Boolean>>()
        var notesWindowRefuses = false
        val history = ReaderHistoryManager(
            this,
            {},
            { document },
            openOtherDocument = { entry, commit ->
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
            },
            openAgentNote = { entry, canGoBack, canGoForward ->
                if (!notesWindowRefuses) notesShown += Triple(entry.note!!, canGoBack, canGoForward)
                !notesWindowRefuses
            },
        )

        /** The last note shown in the notes window and its arrows: Back, Forward. */
        fun noteOnScreen() = notesShown.lastOrNull()?.let { Triple(it.first.noteId, it.second, it.third) }

        /**
         * A link of an agent note followed into this tab (MainActivity: a new tab, or onNewIntent
         * of the tab already showing [target]).
         */
        fun followNoteLink(note: AgentNotePlace, target: String, targetPage: Int) {
            when (document) {
                null -> {
                    history.onDocumentReset()
                    document = target
                    history.recordAgentNote(note)
                }
                target -> history.recordAgentNoteLink(note, sameDocument = true, targetPage)
                else -> {
                    history.recordAgentNoteLink(note, sameDocument = false, null)
                    replaceDocument(target)
                }
            }
            page = targetPage
            view = state(targetPage)
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

    @Test
    fun backFromANoteLinkInANewTabShowsTheNoteAndForwardThereComesBack() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, GRPO, 52)
        assertTrue("Back leads to the note", tab.history.canGoBack())
        assertFalse(tab.history.canGoForward())

        tab.history.goBack()
        assertEquals("The note, with only Forward from it", Triple(NOTE.noteId, false, true), tab.noteOnScreen())
        assertEquals("The tab stays on the linked page", Triple(GRPO, 52, 1f), tab.place())
        assertEquals(0, tab.opens)

        // Forward on the note: the tab comes to the front where it was.
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple(GRPO, 52, 1f), tab.place())
        assertTrue("Back still leads to the note", tab.history.canGoBack())
        assertFalse(tab.history.canGoForward())

        tab.history.goBack()
        assertEquals(2, tab.notesShown.size)
        assertEquals(NOTE, tab.notesShown.last().first)
    }

    @Test
    fun theNoteKeepsItsZoomAndScrollThroughTheHistory() {
        val tab = Tab(null)
        val scrolled = NOTE.copy(zoom = 2.5f, left = 0.1f, top = 0.6f)
        tab.followNoteLink(scrolled, GRPO, 52)
        tab.history.goBack()
        assertEquals(scrolled, tab.notesShown.single().first)

        val saved = ReaderHistoryManager.decodeEntries(ReaderHistoryManager.encodeEntries(tab.history.backEntries()))!!
        assertEquals(scrolled, saved.single().note)
        assertEquals(Origin.AGENT_NOTE, saved.single().origin)
        assertEquals(scrolled.uri, saved.single().document)
    }

    @Test
    fun comingBackWithoutAnArrowLeavesTheTabAsItWas() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, GRPO, 52)
        tab.moveTo(55, zoom = 2f)
        tab.history.goBack()

        // e.g. the tab strip or Recents
        tab.history.returnFromAgentNote(back = null)
        assertEquals(Triple(GRPO, 55, 2f), tab.place())
        assertTrue(tab.history.canGoBack())
        assertFalse(tab.history.canGoForward())
        // Already back: an arrow pressed later is not carried out again.
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple(GRPO, 55, 2f), tab.place())
    }

    @Test
    fun linksInsideTheReaderAfterTheNoteWalkBackToTheNoteAndForwardAgain() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, SDPO, 52)
        tab.followLink(GRPO, 7)
        tab.followLink(GRPO, 20)

        tab.history.goBack()
        assertEquals(Triple(GRPO, 7, 1f), tab.place())
        tab.history.goBack()
        assertEquals(Triple(SDPO, 52, 1f), tab.place())
        assertTrue(tab.notesShown.isEmpty())
        tab.history.goBack()
        assertEquals("Back from the linked page: the note", Triple(NOTE.noteId, false, true), tab.noteOnScreen())

        // Forward on the note: the linked page (on screen already), then the reader's own steps.
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple(SDPO, 52, 1f), tab.place())
        tab.history.goForward()
        assertEquals(Triple(GRPO, 7, 1f), tab.place())
        tab.history.goForward()
        assertEquals(Triple(GRPO, 20, 1f), tab.place())
        assertFalse(tab.history.canGoForward())
    }

    @Test
    fun aNoteLinkInATabAlreadyOpenKeepsTheOldPlaceBehindTheNote() {
        val tab = Tab(SDPO).apply { moveTo(10, zoom = 1.5f) }
        tab.followNoteLink(NOTE, SDPO, 52)

        tab.history.goBack()
        assertEquals("Back goes to the note, not to page 11", Triple(NOTE.noteId, true, true), tab.noteOnScreen())
        assertEquals(Triple(SDPO, 52, 1f), tab.place())

        // Back on the note: the place the tab showed before the link.
        tab.history.returnFromAgentNote(back = true)
        assertEquals(Triple(SDPO, 10, 1.5f), tab.place())
        assertFalse(tab.history.canGoBack())
        tab.history.goForward()
        assertEquals("Forward from there is the note again", Triple(NOTE.noteId, true, true), tab.noteOnScreen())
        assertEquals(2, tab.notesShown.size)
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple(SDPO, 52, 1f), tab.place())
        assertTrue(tab.history.canGoBack())
        assertFalse(tab.history.canGoForward())
    }

    @Test
    fun aNoteLinkToAnotherDocumentOfTheTabReturnsThroughTheNote() {
        val tab = Tab(SDPO).apply { moveTo(3) }
        tab.followNoteLink(NOTE, GRPO, 40)
        assertEquals(Triple(GRPO, 40, 1f), tab.place())

        tab.history.goBack()
        assertEquals(Triple(NOTE.noteId, true, true), tab.noteOnScreen())
        tab.history.returnFromAgentNote(back = true)
        assertEquals(Triple(SDPO, 3, 1f), tab.place())
        tab.history.goForward()
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple(GRPO, 40, 1f), tab.place())
    }

    @Test
    fun followingTheSameNoteLinkAgainKeepsOneNoteBehindTheLinkedPage() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, GRPO, 52)
        tab.history.goBack()
        // The link tapped again on the note: the tab is already on that page.
        tab.followNoteLink(NOTE, GRPO, 52)
        assertEquals(listOf(NOTE.uri), tab.history.backEntries().map { it.document })
        tab.history.goBack()
        assertEquals(Triple(NOTE.noteId, false, true), tab.noteOnScreen())
    }

    @Test
    fun anotherLinkFromTheNoteReplacesWhatWasAheadOfIt() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, SDPO, 52)
        tab.followLink(GRPO, 7)
        tab.history.goBack()
        tab.history.goBack()
        assertEquals(Triple(NOTE.noteId, false, true), tab.noteOnScreen())

        // On the note, another of its links: page 52 and grpo were ahead of the note, as in a browser they go.
        tab.followNoteLink(NOTE, GRPO, 3)
        assertEquals(Triple(GRPO, 3, 1f), tab.place())
        assertEquals(listOf(NOTE.uri), tab.history.backEntries().map { it.document })
        assertFalse(tab.history.canGoForward())
        tab.history.goBack()
        assertEquals(Triple(NOTE.noteId, false, true), tab.noteOnScreen())
    }

    @Test
    fun aLinkOfAnotherNoteRightAfterComingBackFromANoteStartsAfterIt() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, GRPO, 52)
        tab.history.goBack()
        // The tab is started by the link of the next note (onStart, then the intent).
        tab.history.returnFromAgentNote(back = null)
        val other = AgentNotePlace("note-2", title = "Second note")
        tab.followNoteLink(other, GRPO, 12)

        assertEquals(listOf(NOTE.uri, other.uri), tab.history.backEntries().map { it.document })
        tab.history.goBack()
        assertEquals(Triple("note-2", true, true), tab.noteOnScreen())
        tab.history.returnFromAgentNote(back = true)
        assertEquals("Back on the second note: the first", Triple(NOTE.noteId, false, true), tab.noteOnScreen())
        assertEquals(Triple(GRPO, 12, 1f), tab.place())
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple("note-2", true, true), tab.noteOnScreen())
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple(GRPO, 12, 1f), tab.place())
    }

    @Test
    fun aNoteLinkAfterTheTabWasInUseKeepsItsPlaceBehindTheNote() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, GRPO, 52)
        tab.history.goBack()
        tab.history.returnFromAgentNote(back = null)
        tab.history.settle() // the tab was on screen again (tab strip) before the next link
        val other = AgentNotePlace("note-2", title = "Second note")
        tab.followNoteLink(other, GRPO, 12)

        tab.history.goBack()
        assertEquals(Triple("note-2", true, true), tab.noteOnScreen())
        tab.history.returnFromAgentNote(back = true)
        assertEquals("Behind the second note: where the tab was", Triple(GRPO, 52, 1f), tab.place())
        tab.history.goBack()
        assertEquals(Triple(NOTE.noteId, false, true), tab.noteOnScreen())
    }

    @Test
    fun aNoteThatCannotBeShownLeavesTheTabWhereItIs() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, GRPO, 52)
        tab.notesWindowRefuses = true
        tab.history.goBack()
        assertEquals(Triple(GRPO, 52, 1f), tab.place())
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple(GRPO, 52, 1f), tab.place())
        assertTrue(tab.history.canGoBack())
    }

    @Test
    fun historyListCanPickTheNote() {
        val tab = Tab(null)
        tab.followNoteLink(NOTE, SDPO, 52)
        tab.followLink(GRPO, 7)
        assertTrue(tab.history.goBackToBackStackIndex(0))
        assertEquals("Picked from the list: the note, Back and Forward as it stands", Triple(NOTE.noteId, false, true), tab.noteOnScreen())
        assertEquals("The tab is left as it was", Triple(GRPO, 7, 1f), tab.place())
        tab.history.returnFromAgentNote(back = false)
        assertEquals(Triple(SDPO, 52, 1f), tab.place())
        assertEquals(GRPO, tab.history.forwardEntries().single().document)
    }

    @Test
    fun anUnreadableSavedNoteIsDropped() {
        val encoded = ReaderHistoryManager.encodeEntries(listOf(
            ReaderHistoryManager.Entry(0, null, 1L, Origin.AGENT_NOTE, "thoughts-agent-note://note/x", null),
            ReaderHistoryManager.Entry(3, null, 2L, Origin.LINK, SDPO),
        ))
        assertEquals(listOf(SDPO), ReaderHistoryManager.decodeEntries(encoded)!!.map { it.document })
    }

    private companion object {
        val NOTE = AgentNotePlace("00000000-0000-4000-8000-0000000000b1", title = "Links back test")
        const val SDPO = "file:///data/user/0/org.tbend.thoughts/files/thoughts/current-pdfs/concepts_sdpo.pdf"
        const val GRPO = "file:///data/user/0/org.tbend.thoughts/files/thoughts/current-pdfs/concepts_grpo.pdf"
        const val DISTILLATION = "file:///data/user/0/org.tbend.thoughts/files/thoughts/current-pdfs/concepts_distillation.pdf"

        fun state(page: Int, zoom: Float = 1f) =
            PDFView.ViewState(zoom, page, true, false, 0.25f, 0.75f, 1, false)
    }
}
