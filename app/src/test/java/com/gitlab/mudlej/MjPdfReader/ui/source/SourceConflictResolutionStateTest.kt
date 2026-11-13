// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceConflictResolutionStateTest {
    private val conflict = "before\n<<<<<<< FEDORA_CURRENT\nfedora line\n=======\nphone line\n>>>>>>> PHONE_INCOMING\nafter\n"

    private fun status(
        canSave: Boolean = false,
        editorLoaded: Boolean = true,
        saving: Boolean = false,
        merged: Boolean = true,
        dirty: Boolean = true,
        remaining: Int = 0,
        malformed: Boolean = false,
        savedThisSession: Boolean = false,
    ) = conflictSaveStatus(canSave, editorLoaded, saving, merged, dirty, remaining, malformed, savedThisSession)

    @Test
    fun saveIsExplainedInEveryState() {
        assertEquals(ConflictSaveStatus(true, ConflictSaveBlocker.NONE, 0), status(canSave = true))
        assertEquals(ConflictSaveBlocker.LOADING, status(editorLoaded = false).blocker)
        assertEquals(ConflictSaveBlocker.SAVING, status(saving = true, remaining = 1).blocker)
        assertEquals(ConflictSaveBlocker.OTHER_VERSION_SHOWN, status(merged = false, remaining = 1).blocker)
        assertEquals(ConflictSaveBlocker.MALFORMED, status(malformed = true, remaining = 1).blocker)
        assertEquals(ConflictSaveStatus(false, ConflictSaveBlocker.UNRESOLVED, 3), status(remaining = 3))
        assertEquals(ConflictSaveBlocker.SAVED, status(dirty = false, savedThisSession = true).blocker)
        assertEquals(ConflictSaveBlocker.UNCHANGED, status(dirty = false).blocker)
        // The presentation lags one debounce behind typing: markers still block, explained as one conflict.
        assertEquals(ConflictSaveStatus(false, ConflictSaveBlocker.UNRESOLVED, 1), status(remaining = 0))
        assertFalse(status(remaining = 1).enabled)
    }

    @Test
    fun saveIsEnabledOnlyWhenEveryConflictIsResolved() {
        fun canSave(contents: String, dirty: Boolean = true, merged: Boolean = true) =
            conflictResolutionCanSave(editorLoaded = true, saving = false, mergedViewSelected = merged, dirty = dirty, contents = contents)
        assertFalse(canSave(conflict))
        val block = parseSourceConflictPresentation(conflict).blocks.single()
        val resolved = conflict.replaceRange(block.range.start, block.range.endExclusive, block.selectedSide(conflict, ConflictTextRole.PHONE_INCOMING)!!)
        assertEquals("before\nphone line\nafter\n", resolved)
        assertTrue(canSave(resolved))
        assertFalse(canSave(resolved, dirty = false))
        assertFalse(canSave(resolved, merged = false))
    }

    @Test
    fun progressCountsResolvedConflictsAndNeverGoesNegative() {
        assertEquals(ConflictProgress(resolved = 0, total = 2), conflictProgress(initialTotal = 2, remaining = 2))
        assertEquals(ConflictProgress(resolved = 1, total = 2), conflictProgress(initialTotal = 2, remaining = 1))
        assertTrue(conflictProgress(initialTotal = 2, remaining = 0).complete)
        // Markers typed by hand add conflicts instead of producing negative progress.
        assertEquals(ConflictProgress(resolved = 0, total = 3), conflictProgress(initialTotal = 2, remaining = 3))
        assertFalse(conflictProgress(initialTotal = 0, remaining = 0).complete)
    }

    @Test
    fun keepBothPutsFedoraFirstWithoutMarkers() {
        val block = parseSourceConflictPresentation(conflict).blocks.single()
        assertEquals("fedora line\nphone line\n", block.bothSides(conflict))
        val emptyFedora = "<<<<<<< F\n=======\nphone\n>>>>>>> P\n"
        assertEquals("phone\n", parseSourceConflictPresentation(emptyFedora).blocks.single().bothSides(emptyFedora))
        val incomplete = "<<<<<<< F\nfedora\n=======\nphone\n"
        assertNull(parseSourceConflictPresentation(incomplete).blocks.single().bothSides(incomplete))
    }

    @Test
    fun manualTextKeepsTheFollowingLineSeparate() {
        val block = parseSourceConflictPresentation(conflict).blocks.single()
        assertEquals("\n", block.trailingLineBreak(conflict))
        assertEquals("mine\n", manualConflictReplacement("mine", block.trailingLineBreak(conflict)))
        assertEquals("mine\n", manualConflictReplacement("mine\n", "\n"))
        assertEquals("", manualConflictReplacement("", "\n"))
        val crlf = conflict.replace("\n", "\r\n")
        val crlfBlock = parseSourceConflictPresentation(crlf).blocks.single()
        assertEquals("\r\n", crlfBlock.trailingLineBreak(crlf))
        val atEnd = "<<<<<<< F\nf\n=======\np\n>>>>>>> P"
        assertEquals("", parseSourceConflictPresentation(atEnd).blocks.single().trailingLineBreak(atEnd))
        assertEquals("mine", manualConflictReplacement("mine", ""))
    }

    @Test
    fun sideDisplayDropsOnlyTheStructuralFinalLineBreak() {
        assertEquals("a\nb", conflictSideDisplayText("a\nb\n"))
        assertEquals("a\n", conflictSideDisplayText("a\n\n"))
        assertEquals("a", conflictSideDisplayText("a\r\n"))
        assertEquals("", conflictSideDisplayText(""))
    }

    @Test
    fun conflictLineIsOneBasedSourceLine() {
        val start = parseSourceConflictPresentation(conflict).blocks.single().startOffset
        assertEquals(1, conflict.lineIndexOf(start))
        assertEquals(0, conflict.lineIndexOf(0))
    }
}
