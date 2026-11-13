// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceConflictWordDiffTest {
    private fun cut(text: String) = { range: ConflictTextRange -> text.substring(range.start, range.endExclusive) }

    @Test
    fun tokensAreWordsTexCommandsAndSinglePunctuation() {
        val tokens = tokenizeWordDiff("We use \\emph{don't}, \\\\ and \\textbf*{x}{} 3.5").map { it.text }
        assertEquals(
            listOf("We", "use", "\\emph", "{", "don't", "}", ",", "\\\\", "and", "\\textbf*", "{", "x", "}", "{", "}", "3", ".", "5"),
            tokens,
        )
        // A command swallows the empty groups that follow it, as in the JS original.
        assertEquals(listOf("\\LaTeX{}", "rocks"), tokenizeWordDiff("\\LaTeX{} rocks").map { it.text })
    }

    @Test
    fun onlyChangedWordsAreHighlightedOnEachSide() {
        val fedora = "The model learns quickly from feedback."
        val phone = "The model learns slowly from human feedback."
        val diff = changedWordRanges(fedora, phone)
        assertEquals(listOf("quickly"), diff.oldRanges.map(cut(fedora)))
        assertEquals(listOf("slowly", "human"), diff.newRanges.map(cut(phone)))
    }

    @Test
    fun adjacentChangedTokensFormOneRun() {
        val fedora = "keep a b c keep"
        val phone = "keep x y keep"
        val diff = changedWordRanges(fedora, phone)
        assertEquals(listOf("a b c"), diff.oldRanges.map(cut(fedora)))
        assertEquals(listOf("x y"), diff.newRanges.map(cut(phone)))
    }

    @Test
    fun whitespaceOnlyChangesAreNotHighlighted() {
        val diff = changedWordRanges("one two\nthree", "one  two three")
        assertEquals(emptyList<ConflictTextRange>(), diff.oldRanges)
        assertEquals(emptyList<ConflictTextRange>(), diff.newRanges)
    }

    @Test
    fun emptySideHighlightsEverythingOnTheOther() {
        val diff = changedWordRanges("", "added text")
        assertEquals(emptyList<ConflictTextRange>(), diff.oldRanges)
        assertEquals(listOf("added text"), diff.newRanges.map(cut("added text")))
    }

    @Test
    fun matchesAreALongestCommonSubsequence() {
        // Brute-force LCS length agrees with the Myers matching on small random token strings.
        val random = java.util.Random(7)
        repeat(300) {
            val a = List(random.nextInt(12)) { "w${random.nextInt(4)}" }
            val b = List(random.nextInt(12)) { "w${random.nextInt(4)}" }
            val pairs = matchingTokenPairs(tokenizeWordDiff(a.joinToString(" ")), tokenizeWordDiff(b.joinToString(" ")))
            val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            assertEquals("$a vs $b", lcs[0][0], pairs.size)
            pairs.forEach { (i, j) -> assertEquals(a[i], b[j]) }
            assertEquals(pairs, pairs.sortedBy { it.first })
            assertEquals(pairs.map { it.second }, pairs.map { it.second }.sorted())
        }
    }

    @Test
    fun veryDifferentLongSidesFallBackToCommonEdges() {
        val fedora = "start " + (1..1500).joinToString(" ") { "f$it" } + " end"
        val phone = "start " + (1..1500).joinToString(" ") { "p$it" } + " end"
        val diff = changedWordRanges(fedora, phone)
        assertEquals(1, diff.oldRanges.size)
        assertEquals("f1", cut(fedora)(diff.oldRanges.single()).substringBefore(' '))
        assertEquals("f1500", cut(fedora)(diff.oldRanges.single()).substringAfterLast(' '))
    }
}
