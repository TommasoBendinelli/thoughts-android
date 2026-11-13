// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalMovePairsTest {
    private val base = "agent-0d1e2f30-4152-4637-8899-aabbccddeeff"

    @Test
    fun partnersAreTheOtherHalfOnly() {
        assertEquals("$base.paste", ProposalMovePairs.partner("$base.cut"))
        assertEquals("$base.cut", ProposalMovePairs.partner("$base.paste"))
        for (other in listOf(base, "$base.copy", "objective.cut", "x$base.cut", "$base.cut.paste", "agent-1223c0e3")) {
            assertNull(other, ProposalMovePairs.partner(other))
            assertFalse(ProposalMovePairs.isHalf(other))
        }
        assertTrue(ProposalMovePairs.isHalf("$base.cut"))
    }

    @Test
    fun aSelectionKeepsOneHalfOfEachPair() {
        val chosen = listOf("one", "$base.paste", "two", "$base.cut")
        assertEquals(listOf("one", "$base.paste", "two"), ProposalMovePairs.oneHalfEach(chosen) { it })
        assertEquals(listOf("$base.cut"), ProposalMovePairs.oneHalfEach(listOf("$base.cut")) { it })
    }
}
