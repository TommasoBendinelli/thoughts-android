// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IndependentProjectSyncTest {
    @Test fun failureKeepsSuccessfulProjectsAndAttemptsLaterProjects() = runBlocking {
        val activated = mutableListOf<String>()
        val failures = attemptIndependentProjects(listOf("alpha", "broken", "beta"), { it }) {
            if (it == "broken") error("missing project object")
            activated += it
        }
        assertEquals(listOf("alpha", "beta"), activated)
        assertEquals(setOf("broken"), failures.keys)
    }
    @Test fun cancellationDoesNotContinueTransfers() = runBlocking {
        val attempted = mutableListOf<String>()
        try {
            attemptIndependentProjects(listOf("alpha", "beta"), { it }) {
                attempted += it
                throw kotlinx.coroutines.CancellationException()
            }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf("alpha"), attempted)
    }
}
