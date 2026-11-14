// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationFileIndexTest {
    private fun digest(number: Int) = "%064x".format(number)

    /** The walk the index replaces, counting what it compares. */
    private class Walk(private val publications: Map<String, Pair<String, Long>>) {
        var comparisons = 0L
        fun filenameHolding(sha256: String, size: Long): String? = publications.entries
            .firstOrNull { (_, identity) -> comparisons++; identity.first == sha256 && identity.second == size }?.key
        fun filenamesHolding(sha256: String): List<String> =
            publications.filterValues { comparisons++; it.first == sha256 }.keys.toList()
    }

    @Test
    fun theIndexAnswersWhatWalkingTheGenerationAnswered() {
        // 300 PDFs, some held under two names and some names sharing a hash at another size.
        val publications = linkedMapOf<String, Pair<String, Long>>()
        for (number in 1..300) publications["doc-$number.pdf"] = digest(number % 280) to (1_000L + number % 280)
        publications["odd-size.pdf"] = digest(5) to 77L
        val index = GenerationFileIndex(publications)
        val walk = Walk(publications)
        for (number in 0..320) {
            val sha256 = digest(number)
            assertEquals(walk.filenameHolding(sha256, 1_000L + number), index.filenameHolding(sha256, 1_000L + number))
            assertEquals(walk.filenamesHolding(sha256), index.filenamesHolding(sha256))
        }
        assertEquals("odd-size.pdf", index.filenameHolding(digest(5), 77L))
        assertNull(index.filenameHolding(digest(5), 78L))
        // One staging pass over these PDFs compared this often; the index is built in one pass of 301.
        assertEquals(true, walk.comparisons > 100_000)
    }

    @Test
    fun aPdfHeldUnderTwoNamesIsFoundUnderTheFirst() {
        val index = GenerationFileIndex(linkedMapOf("b.pdf" to (digest(1) to 10L), "a.pdf" to (digest(1) to 10L)))
        assertEquals("b.pdf", index.filenameHolding(digest(1), 10L))
        assertEquals(listOf("b.pdf", "a.pdf"), index.filenamesHolding(digest(1)))
    }

    @Test
    fun groupingKeepsTheOrderOfEachGroup() {
        val grouped = groupedInOrder(listOf("b1", "a1", "b2", "a2", "c1")) { it.take(1) }
        assertEquals(listOf("b1", "b2"), grouped["b"])
        assertEquals(listOf("a1", "a2"), grouped["a"])
        assertNull(grouped["z"])
    }
}
