// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.google.gson.JsonNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity

/** Creates only unique synthetic notes; cleanup tombstones those exact identities. No Sync. */
class CommentServiceInstrumentedTest {
    @Test fun noPdfDeletionCascadesAndUndoPreservesNewerText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = FedoraRepository(context)
        val root = "test-${TestRunIdentity.id}-service-${UUID.randomUUID()}"
        val child = "$root-reply"
        val keep = "$root-keep"
        val timestamp = Instant.now().toString()
        val payloads = listOf(newNotePayload(root, "[TEST ${TestRunIdentity.id}] Service fixture", timestamp), newNotePayload(child, "[TEST ${TestRunIdentity.id}] Reply fixture", timestamp).apply {
            addProperty("thread_root_id", root); addProperty("parent_id", root)
        }, newNotePayload(keep, "[TEST ${TestRunIdentity.id}] Keep fixture", timestamp))
        repository.enqueueLogicalComments(payloads)
        val service = CommentService(context)
        suspend fun payload(id: String) = repository.effectiveLogicalCommentPayloads().single { it.get("comment_id").asString == id }
        try {
            val deletion = service.deleteComments(listOf(root))
            assertTrue(deletion.error, deletion.success)
            assertEquals(setOf(root, child), deletion.ids.toSet())
            assertTrue(payload(root).get("deleted").asBoolean)
            assertTrue(payload(child).get("deleted").asBoolean)
            assertFalse(payload(keep).get("deleted").asBoolean)
            val edited = payload(root).deepCopy().apply {
                addProperty("contents", "[TEST ${TestRunIdentity.id}] Newer text")
                addProperty("revision", get("revision").asInt + 1)
                addProperty("updated_at", Instant.now().toString())
            }
            repository.enqueueLogicalComment(edited)
            assertTrue(service.undo(deletion.historyToken).success)
            assertEquals("[TEST ${TestRunIdentity.id}] Newer text", payload(root).get("contents").asString)
            assertFalse(payload(child).get("deleted").asBoolean)
            assertTrue(service.redo(deletion.historyToken).success)
            val reopened = FedoraRepository(context)
            assertTrue(reopened.effectiveLogicalCommentPayloads().single { it.get("comment_id").asString == child }.get("deleted").asBoolean)
            val invalid = service.deleteComments(listOf(keep, "$root-missing"))
            assertFalse(invalid.success)
            assertFalse(payload(keep).get("deleted").asBoolean)
        } finally {
            repository.enqueueLogicalComments(listOf(root, child, keep).map { id ->
                payload(id).deepCopy().apply {
                    addProperty("deleted", true); addProperty("revision", get("revision").asInt + 1)
                    addProperty("updated_at", Instant.now().toString())
                }
            })
        }
    }
}
