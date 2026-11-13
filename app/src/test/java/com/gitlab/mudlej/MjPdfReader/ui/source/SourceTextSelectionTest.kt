// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTextSelectionTest {
    @Test
    fun draggedHandlesResizeSelectionWithoutCrossingEachOther() {
        val current = SourceTextSelection(start = 10, endExclusive = 20)

        assertEquals(
            SourceTextSelection(start = 4, endExclusive = 20),
            draggedSourceTextSelection(current, 4, draggingStart = true, textLength = 30),
        )
        assertEquals(
            SourceTextSelection(start = 10, endExclusive = 27),
            draggedSourceTextSelection(current, 27, draggingStart = false, textLength = 30),
        )
        assertEquals(
            SourceTextSelection(start = 19, endExclusive = 20),
            draggedSourceTextSelection(current, 24, draggingStart = true, textLength = 30),
        )
        assertEquals(
            SourceTextSelection(start = 10, endExclusive = 11),
            draggedSourceTextSelection(current, 3, draggingStart = false, textLength = 30),
        )
    }

    @Test
    fun normalEditLaunchOpensKeyboardOnlyForTheFirstSnapshot() {
        assertTrue(
            shouldOpenSourceEditorKeyboard(
                resolvingConflict = false,
                editorAlreadyLoaded = false,
            ),
        )
        assertFalse(
            shouldOpenSourceEditorKeyboard(
                resolvingConflict = false,
                editorAlreadyLoaded = true,
            ),
        )
        assertFalse(
            shouldOpenSourceEditorKeyboard(
                resolvingConflict = true,
                editorAlreadyLoaded = false,
            ),
        )
    }

    @Test
    fun selectedSourceRangeIsCenteredWithinTheEditorViewport() {
        assertEquals(
            510,
            centeredSourceSelectionScrollY(
                selectionTop = 1_000,
                selectionBottom = 1_020,
                viewportHeight = 1_000,
            ),
        )
    }

    @Test
    fun selectionNearTheStartDoesNotScrollAboveTheSource() {
        assertEquals(
            0,
            centeredSourceSelectionScrollY(
                selectionTop = 100,
                selectionBottom = 120,
                viewportHeight = 1_000,
            ),
        )
    }

    @Test
    fun selectsAnExactSourceSpan() {
        val contents = "first line\nselected source phrase\nlast line"
        val start = contents.indexOf("selected source phrase")

        assertEquals(
            SourceTextSelection(start, start + "selected source phrase".length),
            findSourceTextSelection(contents, "selected source phrase", mappedLine = 2),
        )
    }

    @Test
    fun mapsCollapsedPdfWhitespaceBackToSourceOffsets() {
        val contents = "first line\nselected\n   source phrase\nlast line"
        val expectedText = "selected\n   source phrase"
        val start = contents.indexOf("selected")

        val selection = findSourceTextSelection(
            contents,
            "selected source\nphrase",
            mappedLine = 2,
        )

        assertEquals(SourceTextSelection(start, start + expectedText.length), selection)
        assertEquals(expectedText, contents.substring(selection!!.start, selection.endExclusive))
    }

    @Test
    fun duplicateTextUsesTheUniquelyClosestMappedLine() {
        val contents = "same phrase\nmiddle\nsame phrase\nend"
        val second = contents.lastIndexOf("same phrase")

        assertEquals(
            SourceTextSelection(second, second + "same phrase".length),
            findSourceTextSelection(contents, "same phrase", mappedLine = 3),
        )
    }

    @Test
    fun equallyCloseDuplicateTextFallsBackInsteadOfGuessing() {
        val contents = "same phrase\nmiddle\nsame phrase"

        assertNull(findSourceTextSelection(contents, "same phrase", mappedLine = 2))
    }

    @Test
    fun texMarkupMismatchFallsBackInsteadOfSelectingAnotherShape() {
        val contents = "prefix \\textbf{selected} source phrase suffix"

        assertNull(findSourceTextSelection(contents, "selected source phrase", mappedLine = 1))
    }

    @Test
    fun uniqueTextIsFoundInDisplayedDraftAfterMovingLines() {
        val contents = "draft heading\nnew line\noriginal line\nselected phrase"
        val start = contents.indexOf("selected phrase")

        assertEquals(
            SourceTextSelection(start, start + "selected phrase".length),
            findSourceTextSelection(contents, "selected phrase", mappedLine = 2),
        )
    }

    @Test
    fun shortOrBlankSelectionsFallBack() {
        assertNull(findSourceTextSelection("a source", "a", mappedLine = 1))
        assertNull(findSourceTextSelection("a source", "   ", mappedLine = 1))
    }
}
