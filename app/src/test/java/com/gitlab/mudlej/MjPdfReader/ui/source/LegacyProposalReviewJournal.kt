// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * The journal algorithm before format 2 (ffbb5a02), kept only to measure the improvement in
 * ProposalReviewJournalTest. android.util.AtomicFile is replaced by the same write/rename.
 * Undo steps held two full copies of the file and every persist parsed the journal three times.
 */
internal class LegacyProposalReviewJournal(root: File, private val sourceFile: String) {
    private val file = File(root, ProposalSourceActivity.digest(sourceFile) + ".json")
    private fun store(state: JsonObject) {
        val temporary = File(file.path + ".new")
        temporary.writeBytes(state.toString().toByteArray(Charsets.UTF_8))
        check(temporary.renameTo(file))
    }
    fun read(): JsonObject? {
        if (!file.exists()) return null
        val state = JsonParser.parseString(file.bufferedReader().use { it.readText() }).asJsonObject
        if (SourceDraftMerge.cleanRecoveries(state, null)) store(state)
        return state
    }
    fun write(state: JsonObject) {
        SourceDraftMerge.cleanRecoveries(state, null)
        validateEvents(state)
        val previous = read()
        val revision = previous?.get("journalRevision")?.asLong ?: 0L
        val comparable = state.deepCopy().apply { remove("journalRevision") }
        if (previous?.deepCopy()?.apply { remove("journalRevision") } == comparable) {
            state.addProperty("journalRevision", revision)
            return
        }
        require((state.get("journalRevision")?.asLong ?: 0L) == revision)
        state.addProperty("journalRevision", revision + 1)
        store(state)
    }
    fun validateEvents(state: JsonObject) {
        require(state.get("filename").asString == sourceFile)
        val events = state.getAsJsonArray("events").map { it.asJsonObject }
        require(events.map { it.get("event_id").asString }.distinct().size == events.size)
        events.forEach {
            require(it.getAsJsonObject("snapshot").get("source_file").asString == sourceFile)
            require(it.toString().toByteArray().size < 900_000)
        }
        val old = read()?.getAsJsonArray("events")?.map { it.asJsonObject }.orEmpty()
        val byId = events.associateBy { it.get("event_id").asString }
        old.forEach { require(byId[it.get("event_id").asString] == it) }
    }
    /** ProposalSourceActivity.Bridge.persist before format 2: parse, validate, write. */
    fun persist(raw: String) {
        val state = JsonParser.parseString(raw).asJsonObject
        validateEvents(state)
        write(state)
    }
}
