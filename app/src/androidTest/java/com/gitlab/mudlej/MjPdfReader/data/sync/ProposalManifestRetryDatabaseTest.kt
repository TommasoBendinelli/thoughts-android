// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the retry on an isolated database; never touches the user's queue. */
@RunWith(AndroidJUnit4::class)
class ProposalManifestRetryDatabaseTest {
    @Test fun retriesOnlyTheMissingManifestWithoutChangingRequestIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "proposal-retry-test-${UUID.randomUUID()}"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = db.thoughtsSyncDao()
            val original = ThoughtsOutboxEntity(
                operationId = "original", deviceSequence = 1,
                entityType = AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE, entityId = "original",
                collection = "agent-proposals", baseRevision = null,
                clientTimestamp = "2026-09-11T00:00:00Z", payloadJson = "{\"original\":true}",
                createdAtEpochMillis = 1, deliveryState = ThoughtsOutboxEntity.STATE_BLOCKED,
                failureCode = "http_409", failureDetail = "source bundle manifest is unavailable",
            )
            dao.insertOutbox(original)
            dao.insertOutbox(original.copy(operationId = "stale", deviceSequence = 2, failureDetail = "source changed"))
            assertEquals(1, dao.retryUnavailableProposalManifest("original"))
            assertEquals(original.copy(deliveryState = ThoughtsOutboxEntity.STATE_PENDING,
                failureCode = "", failureDetail = ""), dao.operation("original"))
            assertEquals(0, dao.retryUnavailableProposalManifest("original"))
            assertEquals(0, dao.retryUnavailableProposalManifest("stale"))
            assertEquals(1, dao.blockedOperationCount())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
