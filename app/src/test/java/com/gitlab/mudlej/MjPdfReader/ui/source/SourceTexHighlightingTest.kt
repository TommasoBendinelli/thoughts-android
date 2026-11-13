// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTexHighlightingTest {
    @Test fun colorsMatchThePreviousLexerIncludingEscapesStarsAndUnicode() {
        val text = "\\section*{Title}\n\\some@command text \\% \\{ \\} \\\\ % note {é} \\λ\r\n\\\n"
        val expected = Regex("\\\\(?:[A-Za-z@]+\\*?|[^\\r\\n])|[{}%]").findAll(text)
            .map { SourceSyntaxRange(it.range.first, it.range.last + 1) }.toList()
        assertEquals(expected, sourceTexSyntaxRanges(text, SourceSyntaxRange(0, text.length)))
    }

    @Test fun ordinaryTypingOnlyInvalidatesItsLineAndConsumesThePendingChange() {
        val text = "\\section{First}\nSome prose here.\n\\section{Last}\n"
        val changes = SourceTexHighlightChanges()
        changes.changed(text.indexOf("prose"), 0, 1)
        assertEquals(SourceSyntaxRange(text.indexOf("Some"), text.indexOf("\\section{Last}")), changes.takeRange(text))
        assertNull(changes.takeRange(text))
    }

    @Test fun joiningAndSplittingLinesRecolorsBothCommandFragments() {
        val changes = SourceTexHighlightChanges()
        changes.changed(4, 1, 0)
        val joined = "\\section{Title}\nlast"
        assertEquals(SourceSyntaxRange(0, joined.indexOf('\n') + 1), changes.takeRange(joined))
        changes.changed(4, 0, 1)
        val split = "\\sec\ntion{Title}\nlast"
        assertEquals(SourceSyntaxRange(0, split.lastIndexOf('\n') + 1), changes.takeRange(split))
    }

    @Test fun pendingOffsetsFollowInsertionsAndDeletionsBeforeEarlierEdits() {
        val text = StringBuilder("first\nsecond\nthird\nfourth\n")
        val changes = SourceTexHighlightChanges()
        var at = text.indexOf("third") + 2
        text.insert(at, "X"); changes.changed(at, 0, 1)
        text.insert(0, "new\n"); changes.changed(0, 0, 4)
        at = text.indexOf("second")
        text.delete(at, at + 7); changes.changed(at, 7, 0)
        assertEquals(SourceSyntaxRange(0, text.indexOf("fourth")), changes.takeRange(text))
    }

    @Test fun replacementOfEntireTextAndEmptyDocumentsAreValid() {
        val changes = SourceTexHighlightChanges()
        changes.changed(0, 50000, 5)
        assertEquals(SourceSyntaxRange(0, 5), changes.takeRange("hello"))
        changes.changed(0, 5, 0)
        assertEquals(SourceSyntaxRange(0, 0), changes.takeRange(""))
        assertTrue(sourceTexSyntaxRanges("", SourceSyntaxRange(0, 0)).isEmpty())
    }

    @Test fun scanningNeverConvertsTheFullEditableOrReadsOutsideTheAffectedRange() {
        var reads = 0
        val contents = "x".repeat(50000) + "\\section{Title}" + "y".repeat(50000)
        val text = object : CharSequence {
            override val length = contents.length
            override fun get(index: Int): Char {
                assertTrue(index in 50000 until 50015)
                reads++
                return contents[index]
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("No Spanned copy")
            override fun toString(): String = error("No full text copy")
        }
        val ranges = sourceTexSyntaxRanges(text, SourceSyntaxRange(50000, 50015))
        assertEquals(15, reads)
        assertEquals(listOf(SourceSyntaxRange(50000, 50008), SourceSyntaxRange(50008, 50009),
            SourceSyntaxRange(50014, 50015)), ranges)
    }
}
