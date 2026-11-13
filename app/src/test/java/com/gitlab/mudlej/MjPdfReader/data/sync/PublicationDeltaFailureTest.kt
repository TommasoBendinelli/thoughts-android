// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PublicationDeltaFailureTest {
    @Test
    fun transportCancellationAndUnexpectedFailuresPreserveThePatchPlan() {
        for (failure in listOf(
            UnknownHostException("fedora"), SocketTimeoutException("download timed out"),
            IOException("connection reset"), CancellationException("worker stopped"),
            ArtifactPlanSupersededException(), IllegalStateException("unexpected failure"),
        )) {
            var rejected = false
            try {
                attemptPublicationDelta(onInvalid = { rejected = true }) { throw failure }
                fail("Failure must reach the existing retry or cancellation handler")
            } catch (actual: Throwable) {
                assertSame(failure, actual)
            }
            assertFalse("A failed attempt must not reject the patch", rejected)
        }
    }

    @Test
    fun verifiedPatchFailureFallsBackAndPreservesItsReason() {
        for (reason in listOf("Base checksum mismatch", "Patch checksum mismatch", "Decompression failed", "Result checksum mismatch")) {
            var recorded: String? = null
            assertFalse(attemptPublicationDelta(onInvalid = { recorded = it }) {
                requireUsablePublicationDelta(false, reason)
            })
            assertEquals(reason, recorded)
        }
    }

    @Test
    fun validPatchDoesNotTriggerFallback() {
        var applied = false
        assertTrue(attemptPublicationDelta(onInvalid = { fail(it) }) { applied = true })
        assertTrue(applied)
    }
}
