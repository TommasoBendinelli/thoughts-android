// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.navigation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderNoteHistoryTest {

    @After
    fun tearDown() {
        ReaderNoteHistory.forget()
        ReaderNoteHistory.returned(READER)
        ReaderNoteHistory.returned(OTHER_READER)
    }

    @Test
    fun aNotePlaceTravelsAsJson() {
        val place = AgentNotePlace("note-1", "reply-2", "Links \"back\" test", 2.5f, 0.125f, 0.75f)
        assertEquals(place, AgentNotePlace.fromJson(place.toJson()))
        assertEquals(AgentNotePlace("note-1"), AgentNotePlace.fromJson(AgentNotePlace("note-1").toJson()))
        assertNull(AgentNotePlace.fromJson(null))
        assertNull(AgentNotePlace.fromJson("not json"))
        assertNull("A note id is needed", AgentNotePlace.fromJson("{\"title\":\"x\"}"))
        assertNull("No zoom: not known", AgentNotePlace.fromJson("{\"note_id\":\"n\",\"zoom\":\"big\"}")!!.zoom)
    }

    @Test
    fun aNoteAndEachOfItsRepliesAreDifferentPlacesAndNeverAPdf() {
        val note = AgentNotePlace("00000000-0000-4000-8000-0000000000b1")
        val reply = note.copy(replyId = "r1")
        assertEquals("thoughts-agent-note://note/00000000-0000-4000-8000-0000000000b1", note.uri)
        assertNotEquals(note.uri, reply.uri)
        assertEquals("Zoom and scroll do not make another place", note.uri, note.copy(zoom = 3f, top = 0.5f).uri)
        assertFalse(AgentNotePlace("a/b?c").uri.removePrefix("thoughts-agent-note://note/").contains('/'))
    }

    @Test
    fun theNotesWindowOffersTheArrowsOnlyForTheNoteTheReaderShowed() {
        val note = AgentNotePlace("note-1")
        ReaderNoteHistory.show(ReaderNoteHistory.Shown(READER, note, canGoBack = false, canGoForward = true))
        assertEquals(true, ReaderNoteHistory.shownFor("note-1", null)?.canGoForward)
        assertNull("Another note on screen", ReaderNoteHistory.shownFor("note-2", null))
        assertNull("A reply of the note", ReaderNoteHistory.shownFor("note-1", "reply"))

        ReaderNoteHistory.forget()
        assertNull(ReaderNoteHistory.shownFor("note-1", null))
    }

    @Test
    fun anArrowPressedOnTheNoteWaitsForItsReaderTab() {
        ReaderNoteHistory.show(ReaderNoteHistory.Shown(READER, AgentNotePlace("note-1"), canGoBack = true, canGoForward = true))
        ReaderNoteHistory.request(READER, back = false)
        assertTrue(ReaderNoteHistory.hasRequest(READER))
        assertFalse(ReaderNoteHistory.hasRequest(OTHER_READER))

        assertNull("Another tab shown meanwhile takes nothing", ReaderNoteHistory.returned(OTHER_READER))
        assertEquals(false, ReaderNoteHistory.returned(READER))
        assertNull("The note no longer stands in for the tab", ReaderNoteHistory.shownFor("note-1", null))
        assertNull("Carried out once", ReaderNoteHistory.returned(READER))
    }

    @Test
    fun showingTheNoteAgainDropsAnOldArrow() {
        ReaderNoteHistory.request(READER, back = true)
        ReaderNoteHistory.show(ReaderNoteHistory.Shown(READER, AgentNotePlace("note-1"), canGoBack = false, canGoForward = true))
        assertNull(ReaderNoteHistory.returned(READER))
    }

    private companion object {
        const val READER = 75
        const val OTHER_READER = 76
    }
}
