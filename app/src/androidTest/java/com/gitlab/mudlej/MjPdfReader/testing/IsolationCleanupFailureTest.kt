// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.annotation.newNotePayload
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

class IsolationCleanupFailureTest {
    @Test fun interruptedCleanupProbe(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cleanup_probe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FedoraRepository(context).enqueueLogicalComment(newNotePayload(
            "test-${TestRunIdentity.id}-cleanup-probe", "[TEST ${TestRunIdentity.id}] Intentional cleanup probe", Instant.now().toString(),
        ))
        throw AssertionError("Intentional failure: the external runner must still remove the sandbox queue")
    }
}
