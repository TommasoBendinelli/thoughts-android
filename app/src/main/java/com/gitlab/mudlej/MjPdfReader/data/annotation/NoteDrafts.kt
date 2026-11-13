// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** A small, durable handoff to the normal comment outbox, including across process death. */
internal object NoteDrafts {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private const val DRAFT = "draft"
    private const val WAITING = "waiting"
    private const val READY = "ready"
    private const val SAVED = "saved"
    private const val DISCARDED = "discarded"
    private fun states(context: Context) = context.getSharedPreferences("note_draft_states", Context.MODE_PRIVATE)

    data class Draft(val id: String, val text: String)

    /** New captures remain private drafts until Done, including after process death. */
    @Synchronized
    fun begin(context: Context, id: String): Boolean {
        val state = states(context)
        if (state.contains(id)) return state.getString(id, null) != DISCARDED
        return state.edit().putString(id, DRAFT).putLong("updated:$id", System.currentTimeMillis()).commit()
    }

    fun unfinished(context: Context): List<Draft> = states(context).all
        .filter { (_, value) -> value == DRAFT || value == WAITING }
        .keys.sortedByDescending { states(context).getLong("updated:$it", 0) }
        .map { Draft(it, preferences(context).getString(it, "").orEmpty()) }

    fun saveRequested(context: Context, id: String): Boolean = states(context).getString(id, null) in setOf(WAITING, READY)
    fun readyToSave(context: Context, id: String): Boolean = states(context).getString(id, null) in setOf(READY, SAVED)

    @Synchronized
    fun requestSave(context: Context, id: String): Boolean =
        states(context).edit().putString(id, WAITING).commit()

    @Synchronized
    fun ready(context: Context, id: String): Boolean {
        if (states(context).getString(id, null) !in setOf(WAITING, READY, SAVED)) return false
        return states(context).edit().putString(id, READY).commit()
    }

    suspend fun discard(context: Context, id: String) = mutex.withLock {
        synchronized(this) {
            // Keep a tombstone so stale editor saves cannot resurrect a discarded draft.
            check(states(context).edit().putString(id, DISCARDED).remove("updated:$id").commit())
            check(preferences(context).edit().remove(id).remove("details:$id").commit())
            NoteAttachmentStore(context).clearDraft(id)
        }
    }

    /** The unsaved text kept on this device, without touching the comment database. */
    fun localText(context: Context, id: String): String? = preferences(context).getString(id, null)

    /** The unsaved details kept on this device, without touching the comment database. */
    fun localDetails(context: Context, id: String): NoteDetails? = preferences(context).getString("details:$id", null)
        ?.let { runCatching { NoteDetails.fromJson(JsonParser.parseString(it).asJsonObject) }.getOrNull() }

    suspend fun text(context: Context, id: String): String? = mutex.withLock {
        synchronized(this) { preferences(context).getString(id, null) }?.let { return@withLock it }
        val saved = FedoraRepository(context).effectiveLogicalCommentPayloads().firstOrNull { commentId(it) == id }
        synchronized(this) {
            preferences(context).getString(id, null)
                ?: saved?.takeUnless { it.get("deleted")?.asBoolean == true }?.get("contents")?.asString
        }
    }

    private fun commentId(payload: JsonObject): String? =
        payload.get("comment_id")?.takeIf { it.isJsonPrimitive }?.asString

    suspend fun details(context: Context, id: String): NoteDetails = mutex.withLock {
        val draft = preferences(context).getString("details:$id", null)
        val payload = draft?.let { JsonParser.parseString(it).asJsonObject }
            ?: FedoraRepository(context).effectiveLogicalCommentPayloads().firstOrNull { commentId(it) == id }
        payload?.let(NoteDetails::fromJson) ?: NoteDetails()
    }

    private fun preferences(context: Context) = context.getSharedPreferences("note_drafts", Context.MODE_PRIVATE)

    @Synchronized
    fun keep(context: Context, id: String, text: String, details: NoteDetails? = null): Boolean {
        if (states(context).getString(id, null) == DISCARDED) return false
        return preferences(context).edit().putString(id, text).apply {
            details?.let { putString("details:$id", it.applyTo(JsonObject().apply { addProperty("subtype", "Note") }).toString()) }
        }.commit()
    }

    /**
     * Hands every finished note to the outbox. One broken entry does not block the others; with
     * [requiredId] only that note's failure is reported, otherwise the first failure is.
     */
    suspend fun flush(context: Context, requiredId: String? = null) {
        mutex.withLock {
            val prefs = preferences(context)
            // Unmarked entries are legacy outbox handoffs, not new unfinished captures.
            val pending = prefs.all.filter { (id, _) ->
                !id.startsWith("delivered:") && !id.startsWith("details:") &&
                    states(context).getString(id, null) !in setOf(DRAFT, WAITING, DISCARDED)
            }
            if (pending.isEmpty()) return@withLock
            val repository = FedoraRepository(context)
            val current = repository.effectiveLogicalCommentPayloads().mapNotNull { payload -> commentId(payload)?.let { it to payload } }.toMap()
            val failures = linkedMapOf<String, Throwable>()
            pending.forEach { (id, raw) -> runCatching {
                val text = raw as? String ?: return@forEach
                val previous = current[id]
                val attachments = NoteAttachmentStore(context).draft(id).ifEmpty { previous?.let(NoteAttachment::read).orEmpty() }
                val detailsRaw = prefs.getString("details:$id", null)
                val details = detailsRaw?.let { NoteDetails.fromJson(JsonParser.parseString(it).asJsonObject) }
                if (previous == null && text.isBlank() && attachments.isEmpty()) {
                    synchronized(this) { if (prefs.getString(id, null) == text) prefs.edit().remove(id).commit() }
                    return@forEach
                }
                val payload = if (previous == null) {
                    newNotePayload(id, text, Instant.now().toString(), attachments, CommentAuthor.current(context))
                } else {
                    require(previous.get("subtype")?.asString == "Note")
                    logicalCommentMutationPayload(previous, id, deleted = text.isBlank() && attachments.isEmpty(), contents = text)
                        ?: error("Invalid saved note")
                }
                if (attachments.isNotEmpty()) NoteAttachment.write(payload, attachments)
                details?.applyTo(payload)
                if (previous?.get("contents")?.asString != text || previous?.get("deleted")?.asBoolean == true ||
                    details != null && previous?.let(NoteDetails::fromJson) != details ||
                    attachments.isNotEmpty() && previous?.let(NoteAttachment::read) != attachments) {
                    repository.enqueueLogicalComment(payload)
                }
                synchronized(this) {
                    if (prefs.getString(id, null) == text && prefs.getString("details:$id", null) == detailsRaw) {
                        check(prefs.edit().remove(id).remove("details:$id").commit())
                        NoteAttachmentStore(context).clearDraft(id)
                        if (states(context).contains(id)) {
                            check(states(context).edit().putString(id, SAVED).remove("updated:$id").commit())
                        }
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failures[id] = it } }
            val failure = if (requiredId == null) failures.values.firstOrNull() else failures[requiredId]
            failure?.let { throw it }
        }
    }
}
