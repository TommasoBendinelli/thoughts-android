// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Diary entries go through the same outbox as every note: saved on the phone at once and
 * published at the next Sync. The diary itself never starts a Sync.
 */
internal class DiaryStore(context: Context) {
    private val context = context.applicationContext
    private val repository = FedoraRepository(this.context)

    /** Writes a new entry stamped with [at]; returns it as it will be listed. */
    suspend fun add(text: String, at: ZonedDateTime = ZonedDateTime.now(), id: String = UUID.randomUUID().toString()): DiaryEntry {
        val payload = DiaryPayload.newPayload(id, text, at, CommentAuthor.current(context))
        LogicalCommentStore.mutex.withLock { repository.enqueueLogicalComment(payload) }
        return requireNotNull(DiaryPayload.entry(payload))
    }

    /** Replaces the text; the entry keeps the moment it was written. */
    suspend fun edit(id: String, text: String): DiaryEntry = mutate(id) { base ->
        logicalCommentMutationPayload(base, id, deleted = false, contents = text)
    }

    suspend fun delete(id: String): DiaryEntry = mutate(id) { base ->
        logicalCommentMutationPayload(base, id, deleted = true)
    }

    suspend fun restore(id: String): DiaryEntry = mutate(id) { base ->
        logicalCommentMutationPayload(base, id, deleted = false)
    }

    private suspend fun mutate(id: String, change: (JsonObject) -> JsonObject?): DiaryEntry =
        LogicalCommentStore.mutex.withLock {
            val base = repository.effectiveLogicalCommentPayloads()
                .firstOrNull { it.get("comment_id")?.takeIf { value -> value.isJsonPrimitive }?.asString == id }
                ?.let(::restoreNoteNulls)
                ?.takeIf(DiaryPayload::isDiary)
                ?: error("Diary entry is unavailable: $id")
            val payload = requireNotNull(change(base)) { "Invalid diary entry: $id" }
            repository.enqueueLogicalComment(payload)
            requireNotNull(DiaryPayload.entry(payload))
        }

    companion object {
        private val order = Mutex()

        /**
         * Diary writes outlive the screen that asked for them and run one after another in the
         * order they were asked for, so an Undo can never overtake the delete it undoes.
         */
        val writes = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

        suspend fun <T> inOrder(block: suspend () -> T): T = order.withLock { block() }
    }
}
