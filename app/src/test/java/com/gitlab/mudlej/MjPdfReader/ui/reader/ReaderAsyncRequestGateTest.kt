// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAsyncRequestGateTest {

    @Test
    fun newerRequestSupersedesOlderResult() {
        val gate = ReaderAsyncRequestGate()
        val older = gate.begin()
        val newer = gate.begin()

        assertFalse(gate.isCurrent(older))
        assertTrue(gate.isCurrent(newer))
    }

    @Test
    fun invalidationRejectsOutstandingResult() {
        val gate = ReaderAsyncRequestGate()
        val request = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(request))
    }
}
