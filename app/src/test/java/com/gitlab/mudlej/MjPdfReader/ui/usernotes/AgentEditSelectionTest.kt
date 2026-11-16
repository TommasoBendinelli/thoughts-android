// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection mode of the Agent edits sheet: long press, taps, Select all, Cancel. */
class AgentEditSelectionTest {

    @Test
    fun aLongPressStartsSelectionModeWithThatEdit() {
        val selection = AgentEditSelection()
        assertFalse(selection.active)
        selection.longPress("agent-b")
        assertTrue(selection.active)
        assertEquals(setOf("agent-b"), selection.ids)
        assertEquals(1, selection.count)
    }

    @Test
    fun tapsAddAndRemoveEditsAndTheLastRemovedEndsSelectionMode() {
        val selection = AgentEditSelection()
        selection.longPress("agent-a")
        assertTrue(selection.toggle("agent-b"))
        assertTrue(selection.toggle("agent-c"))
        assertEquals(listOf("agent-a", "agent-b", "agent-c"), selection.ids.toList())
        assertTrue(selection.toggle("agent-b"))
        assertEquals(setOf("agent-a", "agent-c"), selection.ids)
        assertTrue(selection.toggle("agent-a"))
        assertFalse("Removing the last edit ends selection mode", selection.toggle("agent-c"))
        assertFalse(selection.active)
    }

    @Test
    fun aLongPressInSelectionModeAddsOrRemovesLikeATap() {
        val selection = AgentEditSelection()
        selection.longPress("agent-a")
        selection.longPress("agent-b")
        assertEquals(setOf("agent-a", "agent-b"), selection.ids)
        selection.longPress("agent-a")
        assertEquals(setOf("agent-b"), selection.ids)
    }

    @Test
    fun selectAllAddsWhatTheSearchShowsAndKeepsEarlierChoices() {
        val selection = AgentEditSelection()
        selection.longPress("agent-x")
        // The search now shows only a and b; x, chosen before, stays selected.
        assertFalse(selection.coversAll(listOf("agent-a", "agent-b")))
        selection.selectAll(listOf("agent-a", "agent-b"))
        assertEquals(setOf("agent-x", "agent-a", "agent-b"), selection.ids)
        assertTrue(selection.coversAll(listOf("agent-a", "agent-b")))
        assertTrue("Nothing shown: nothing to add", selection.coversAll(emptyList()))
    }

    @Test
    fun selectAllOutsideSelectionModeDoesNothing() {
        val selection = AgentEditSelection()
        selection.selectAll(listOf("agent-a"))
        assertFalse(selection.active)
    }

    @Test
    fun cancelAndBackClearTheSelection() {
        val selection = AgentEditSelection()
        selection.longPress("agent-a")
        selection.toggle("agent-b")
        selection.clear()
        assertFalse(selection.active)
        assertEquals(0, selection.count)
    }

    @Test
    fun editsDecidedElsewhereLeaveTheSelection() {
        val selection = AgentEditSelection()
        selection.restore(listOf("agent-a", "agent-b"))
        assertTrue(selection.retainExisting(setOf("agent-b", "agent-c")))
        assertEquals(setOf("agent-b"), selection.ids)
        assertFalse("None left: selection mode ends", selection.retainExisting(setOf("agent-c")))
    }

    @Test
    fun aRestoredSelectionComesBackInItsOrder() {
        val selection = AgentEditSelection()
        selection.restore(listOf("agent-c", "agent-a"))
        assertTrue(selection.active)
        assertEquals(listOf("agent-c", "agent-a"), selection.ids.toList())
        assertTrue("agent-a" in selection)
        assertFalse("agent-b" in selection)
    }
}
