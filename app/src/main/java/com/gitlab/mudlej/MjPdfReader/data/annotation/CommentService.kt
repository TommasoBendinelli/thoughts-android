// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import kotlinx.coroutines.sync.withLock
import org.tbend.thoughts.sdk.CommentChange
import org.tbend.thoughts.sdk.CommentRecord
import org.tbend.thoughts.sdk.CommentResult

internal class CommentService(
    context: Context,
    private val filename: () -> String = { "" },
) {
    private val store = LogicalCommentStore(context.applicationContext)
    private val repository = FedoraRepository(context.applicationContext)

    private val engine = org.tbend.thoughts.sdk.CommentService(
        load = ::records,
        commit = ::commit,
        clock = { Instant.now().toString() },
        origin = "thoughts-android",
    )

    suspend fun deleteComments(ids: List<String>): CommentResult = execute { engine.deleteComments(ids) }
    suspend fun undo(token: Int): CommentResult = execute { engine.undo(token) }
    suspend fun redo(token: Int): CommentResult = execute { engine.redo(token) }

    private suspend fun execute(action: suspend () -> CommentResult): CommentResult = LogicalCommentStore.mutex.withLock {
        try { action() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.e("ThoughtsComments", "Canonical comment operation failed", error)
            CommentResult(false, error.message ?: "Comment operation failed")
        }
    }

    private suspend fun records(): List<CommentRecord> = store.snapshot(filename().takeIf(String::isNotBlank))
        .logicalComments
        .map { record(JsonParser.parseString(it.payloadJson).asJsonObject) }

    private fun record(payload: JsonObject) = CommentRecord(
        payload.get("comment_id").asString,
        payload.get("parent_id")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        payload,
    )

    private suspend fun commit(changes: List<CommentChange>) {
        require(changes.all { logicalCommentMutationPayload(it.before.payload, it.before.id, deleted = false) != null }) {
            "The canonical comment must be available before it can be changed."
        }
        repository.enqueueLogicalComments(changes.map { it.after.payload })
    }
}
