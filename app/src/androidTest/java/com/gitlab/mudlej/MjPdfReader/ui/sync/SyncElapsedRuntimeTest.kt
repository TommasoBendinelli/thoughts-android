// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in, read-only verification of a saved sync on the device. */
class SyncElapsedRuntimeTest {
    @Test fun savedSyncUsesItsLocalCompletionTime() {
        val args = InstrumentationRegistry.getArguments()
        val traceId = args.getString("savedTraceId")
        assumeTrue(!traceId.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val record = requireNotNull(SyncOperationStore(context).operation(requireNotNull(traceId)))
        val details = syncOperationLogDetails(record, "unknown") { it.toString() }
        val expected = requireNotNull(args.getString("expectedElapsed"))
        assertTrue(details, details.contains("Elapsed: $expected\n"))
        File(context.cacheDir, "verified-sync-elapsed.txt").writeText(details)
    }
}
