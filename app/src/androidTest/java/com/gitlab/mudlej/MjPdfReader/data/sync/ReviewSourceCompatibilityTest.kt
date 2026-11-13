// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReviewSourceCompatibilityTest {
    @Test fun deferredDeletionKeepsItsIdentityAndOtherOperationsRemainPending() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.thoughtsSyncDao()
            val runId = TestRunIdentity.id
            val deletion = ThoughtsOutboxEntity("$runId-delete", 1, "review_event", "$runId-event", "events", null,
                "2026-09-13T00:00:00Z", "{\"delete_source\":{}}", 1)
            val ordinary = deletion.copy(operationId = "$runId-ordinary", deviceSequence = 2, payloadJson = "{}")
            dao.insertOutbox(deletion)
            dao.insertOutbox(ordinary)
            assertTrue(deletion.deletesReviewSource())
            assertFalse(ordinary.deletesReviewSource())
            dao.markBlocked(deletion.operationId, REVIEW_SOURCE_UNSUPPORTED, REVIEW_SOURCE_UNSUPPORTED_MESSAGE, 9)
            assertEquals(listOf(ordinary), dao.pending(200))
            assertEquals(1, dao.blockedOperationCount())
            assertEquals(1, dao.retryUnsupportedReviewSource(deletion.operationId))
            val restored = requireNotNull(dao.operation(deletion.operationId))
            assertEquals(deletion.copy(lastAttemptEpochMillis = 9), restored)
            // Compatibility recovery must not retry a source conflict without a new user decision.
            dao.markBlocked(deletion.operationId, "http_409", "passage changed", 10)
            assertEquals(0, dao.retryUnsupportedReviewSource(deletion.operationId))
            assertEquals("http_409", dao.operation(deletion.operationId)?.failureCode)
        } finally {
            db.close()
        }
    }
}
