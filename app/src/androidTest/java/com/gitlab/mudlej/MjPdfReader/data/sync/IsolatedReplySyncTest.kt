// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.annotation.newNotePayload
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.tbend.thoughts.sdk.SyncClient
import java.time.Instant

class IsolatedReplySyncTest {
    @Test fun parentAndReplyDeletionSyncAndLegacyRecoveryLeaveNoBlockedChanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ThoughtsServerSecretStore(context)
        // Thoughts Test refuses every server but its isolated one, the build's public server included.
        assertTrue(runCatching { store.set(ThoughtsServerAddress.PUBLIC_BASE_URL, "test-only") }.isFailure)
        val config = requireNotNull(store.config())
        assertEquals(BuildConfig.TEST_SERVER_URL, config.baseUrl)
        val client = SyncClient(config.baseUrl, config.token)
        assertEquals("thoughts-android-test", client.deviceSequence().deviceId)
        val root = "test-${TestRunIdentity.id}-sync-root"
        val reply = "$root-reply"
        val timestamp = Instant.now().toString()
        val parentPayload = newNotePayload(root, "[TEST ${TestRunIdentity.id}] Parent", timestamp)
        val replyPayload = newNotePayload(reply, "[TEST ${TestRunIdentity.id}] Reply", timestamp).apply {
            addProperty("parent_id", root); addProperty("thread_root_id", root)
        }
        val repository = FedoraRepository(context)
        val dao = AppDatabase.getInstance(context).thoughtsSyncDao()
        repository.enqueueLogicalComments(listOf(parentPayload, replyPayload))
        repository.syncDeltas()
        assertTrue(dao.undelivered().isEmpty())
        repository.enqueueLogicalComments(listOf(parentPayload, replyPayload).map {
            it.deepCopy().apply {
                addProperty("deleted", true); addProperty("revision", 2)
                addProperty("updated_at", Instant.now().toString())
            }
        })
        repository.syncDeltas()
        assertTrue("Parent/reply deletion must finish", dao.undelivered().isEmpty())
        val canonical = client.projections().projections.filter { it.entityId in setOf(root, reply) }
        assertEquals(2, canonical.size)
        assertTrue(canonical.all { it.deleted })
        // Reproduce an old blocked receipt without touching any production data.
        val deletion = dao.allOutbox().single { it.entityId == reply && it.baseRevision == 1L }
        dao.markBlocked(deletion.operationId, "http_409", "logical comment reply references a missing parent", System.currentTimeMillis())
        repository.syncDeltas()
        assertEquals("reconciled", dao.operation(deletion.operationId)?.deliveryState)
        assertEquals("deletion_already_applied", dao.operation(deletion.operationId)?.failureCode)
        assertTrue(dao.undelivered().isEmpty())
    }
}
