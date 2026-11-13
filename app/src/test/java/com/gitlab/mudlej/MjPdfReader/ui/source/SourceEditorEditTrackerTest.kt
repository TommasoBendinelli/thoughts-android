// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEditorEditTrackerTest {
    @Test
    fun ordinaryCharacterEditsDoNotRebuildLineNumbers() {
        val tracker = SourceEditorEditTracker()
        tracker.applyTextChange(removedLineBreaks = 0, insertedLineBreaks = 3, isUserEdit = false)
        tracker.markCurrentRevisionSaved()

        assertFalse(tracker.applyTextChange(removedLineBreaks = 0, insertedLineBreaks = 0, isUserEdit = true))
        assertEquals(4, tracker.lineCount)
        assertTrue(tracker.isDirty)
    }

    @Test
    fun newlineEditsUpdateLineCountIncrementally() {
        val tracker = SourceEditorEditTracker()

        assertTrue(tracker.applyTextChange(removedLineBreaks = 0, insertedLineBreaks = 4, isUserEdit = false))
        assertEquals(5, tracker.lineCount)
        assertTrue(tracker.applyTextChange(removedLineBreaks = 2, insertedLineBreaks = 1, isUserEdit = true))
        assertEquals(4, tracker.lineCount)
    }

    @Test
    fun saveKeepsLaterEditsDirty() {
        val tracker = SourceEditorEditTracker()
        tracker.applyTextChange(removedLineBreaks = 0, insertedLineBreaks = 0, isUserEdit = true)
        val revisionBeingSaved = tracker.revision
        tracker.applyTextChange(removedLineBreaks = 0, insertedLineBreaks = 0, isUserEdit = true)

        tracker.markRevisionSaved(revisionBeingSaved)

        assertTrue(tracker.isDirty)
        tracker.markCurrentRevisionSaved()
        assertFalse(tracker.isDirty)
    }

    @Test
    fun lineBreakCounterExaminesOnlyChangedRange() {
        val text = "first\nsecond\nthird"

        assertEquals(1, text.countLineBreaks(start = 5, count = 7))
        assertEquals(0, text.countLineBreaks(start = 6, count = 6))
        assertEquals(0, text.countLineBreaks(start = text.length, count = 10))
    }

    @Test
    fun dirtyComparisonDoesNotScanTextWhenLengthChanged() {
        val changedLength = object : CharSequence {
            override val length: Int = 4
            override fun get(index: Int): Char = error("content should not be scanned")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("content should not be scanned")
        }

        assertTrue(changedLength.differsFrom("saved"))
        assertFalse("saved".differsFrom("saved"))
        assertTrue("other".differsFrom("saved"))
    }
}
