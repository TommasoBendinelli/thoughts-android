// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Atomically persists source, history, and undo stacks together; interrupted saves are replayable.
 *
 * Format 2 keeps the journal proportional to one copy of the source:
 * - undo/redo steps are [ReviewTextEdit] spans, not full before/after copies of the file;
 * - an undo step names its decision by event ID instead of embedding the event again;
 * - whole-document evidence inside events (`context.document_*`) is stored once per distinct
 *   text in `<digest>.documents/<sha256>.txt` and referenced from the event ([DOCUMENT_REF]).
 *
 * The open editor's state is kept in memory, so typing persists a small patch (see [persist])
 * instead of re-sending, re-reading and re-comparing the whole journal every pause.
 */
internal class ProposalReviewJournal(private val root: File, private val sourceFile: String) {
    companion object {
        const val FORMAT = 2
        const val DOCUMENT_REF = "storedDocumentSha256"
        private const val DOCUMENT_MIN_CHARS = 1024
        private const val MAX_EVENT_BYTES = 900_000
        private const val RETAINED_JOURNALS = 4
        private const val TAG = "ThoughtsEditorTiming"

        /** Guards [entries]; never held by the writer thread while it waits for [IO_LOCK]. */
        private val LOCK = Any()
        private val IO_LOCK = Any()
        private val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > RETAINED_JOURNALS && eldest.value.written >= eldest.value.sequence
        }
        private val writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "thoughts-review-journal").apply { isDaemon = true }
        }

        private class Entry(@Volatile var state: JsonObject) {
            @Volatile var sequence = 0L
            /** Guarded by [IO_LOCK]. */
            @Volatile var written = 0L
            @Volatile var error: String? = null
        }

        /** Migrates journals written before format 2; format-2 journals are only scanned for their header. */
        fun cleanLegacyJournals(root: File) {
            root.listFiles { file -> file.extension == "json" }.orEmpty().forEach { candidate ->
                val header = runCatching { header(candidate) }.getOrNull() ?: return@forEach
                if (header.second >= FORMAT) return@forEach
                synchronized(LOCK) { ProposalReviewJournal(root, header.first).load(retain = false) }
            }
        }

        /** Waits until every persisted patch has reached the disk (tests and diagnostics). */
        fun awaitWrites() { writer.submit {}.get(30, TimeUnit.SECONDS) }

        /** Drops the in-memory journals so the next read parses the files (tests). */
        internal fun forgetCachedJournals() { awaitWrites(); synchronized(LOCK) { entries.clear() } }

        private fun header(file: File): Pair<String, Int>? = JsonReader(file.bufferedReader()).use { json ->
            var filename: String? = null
            var format = 1
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "filename" -> filename = json.nextString()
                    "format" -> format = json.nextInt()
                    else -> json.skipValue()
                }
                // Format-2 journals start with both fields; older ones are scanned to the end.
                if (filename != null && format >= FORMAT) break
            }
            filename?.let { it to format }
        }

        private fun log(message: String) {
            runCatching { android.util.Log.i(TAG, message) }
        }

        private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun utf8Length(text: String): Int {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return bytes
        }

        /** A new top-level object whose arrays are new containers; elements are shared and never mutated. */
        internal fun copyState(state: JsonObject): JsonObject = JsonObject().apply {
            for ((key, value) in state.entrySet()) {
                add(key, if (value.isJsonArray) JsonArray(value.asJsonArray.size()).apply { addAll(value.asJsonArray) } else value)
            }
        }

        private fun sameExceptRevision(a: JsonObject, b: JsonObject): Boolean {
            val keys = a.keySet() - "journalRevision"
            if (keys != b.keySet() - "journalRevision") return false
            return keys.all { a.get(it) == b.get(it) }
        }

        private fun JsonObject.long(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }

    /** Thrown when a patch cannot be applied to the journal in memory; the page then sends its whole state. */
    class ResyncRequired(reason: String) : Exception(reason)

    private val key = ProposalSourceActivity.digest(sourceFile)
    private val file = File(root, "$key.json")
    private val syncedFile = File(root, "$key.synced")
    private val documents = File(root, "$key.documents")
    private val path = file.absolutePath

    // ---------------------------------------------------------------- files

    /** Same layout as android.util.AtomicFile: write `name.new`, sync, rename; restore a legacy `name.bak`. */
    private fun atomicWrite(target: File, write: (FileOutputStream) -> Unit) {
        target.parentFile!!.mkdirs()
        val temporary = File(target.path + ".new")
        try {
            FileOutputStream(temporary).use { stream -> write(stream); stream.fd.sync() }
            check(temporary.renameTo(target)) { "Could not save ${target.name}" }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun readable(target: File): Boolean {
        val backup = File(target.path + ".bak")
        if (backup.exists()) { target.delete(); backup.renameTo(target) }
        File(target.path + ".new").takeIf { it.exists() }?.delete()
        return target.exists()
    }

    private fun syncedSource(): String? = if (readable(syncedFile)) syncedFile.readText() else null

    private fun store(state: JsonObject) {
        // Header first: cleanLegacyJournals reads only these two fields of a current journal.
        val ordered = JsonObject().apply {
            addProperty("format", FORMAT)
            add("filename", state.get("filename"))
            for ((name, value) in state.entrySet()) if (name != "format" && name != "filename") add(name, value)
        }
        atomicWrite(file) { stream ->
            val out = stream.bufferedWriter(Charsets.UTF_8)
            com.google.gson.internal.Streams.write(ordered, com.google.gson.stream.JsonWriter(out).apply { serializeNulls = true })
            out.flush()
        }
    }

    private fun storeDocument(text: String): String {
        val hash = sha256(text)
        val target = File(documents, "$hash.txt")
        if (!readable(target)) atomicWrite(target) { it.write(text.toByteArray(Charsets.UTF_8)) }
        return hash
    }

    // ---------------------------------------------------------------- format

    private fun documentRef(value: JsonElement?): Boolean =
        value is JsonObject && value.has(DOCUMENT_REF)

    /** Moves whole-document evidence out of [event]; returns whether it changed. */
    private fun externalize(event: JsonObject): Boolean {
        var changed = false
        fun visit(value: JsonObject, depth: Int) {
            for ((name, child) in value.entrySet().toList()) {
                if (child is JsonObject && depth < 4) visit(child, depth + 1)
                if (name.startsWith("document_") && child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                    val text = child.asString
                    if (text.length < DOCUMENT_MIN_CHARS) continue
                    value.add(name, JsonObject().apply {
                        addProperty(DOCUMENT_REF, storeDocument(text))
                        addProperty("chars", text.length)
                    })
                    changed = true
                }
            }
        }
        visit(event, 0)
        return changed
    }

    private fun hydrate(value: JsonObject): JsonObject {
        for ((name, child) in value.entrySet().toList()) {
            if (documentRef(child)) {
                val hash = (child as JsonObject).get(DOCUMENT_REF).asString
                require(hash.matches(Regex("[0-9a-f]{64}")))
                val target = File(documents, "$hash.txt")
                if (readable(target)) value.addProperty(name, target.readText())
            } else if (child is JsonObject) hydrate(child)
        }
        return value
    }

    /** Converts a format-1 journal in place; returns whether anything changed. */
    private fun migrate(state: JsonObject): Boolean {
        var changed = state.long("format") < FORMAT
        state.getAsJsonArray("events")?.forEach { if (it is JsonObject && externalize(it)) changed = true }
        for (stack in listOf("undo", "redo")) {
            val entries = state.getAsJsonArray(stack) ?: continue
            if (entries.any { (it as? JsonObject)?.let(::legacyStep) == true || (it as? JsonObject)?.get("event") is JsonObject }) {
                state.add(stack, JsonArray().apply { entries.forEach { add(compactStep(it.asJsonObject)) } })
                changed = true
            }
        }
        state.addProperty("format", FORMAT)
        return changed
    }

    private fun legacyStep(step: JsonObject) = step.get("before")?.isJsonPrimitive == true && step.get("after")?.isJsonPrimitive == true

    private fun compactStep(step: JsonObject): JsonObject {
        val event = step.get("event")
        val eventId = if (event is JsonObject) event.get("event_id") else event
        if (!legacyStep(step)) return JsonObject().apply {
            for ((name, value) in step.entrySet()) add(name, if (name == "event") eventId else value)
        }
        return ReviewTextEdit.of(step.get("before").asString, step.get("after").asString)
            .toJson(eventId, step.get("typing")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
    }

    /** Format-2 form of a state that came from the page or a caller; returns whether it changed. */
    private fun normalize(state: JsonObject): Boolean = migrate(state)

    private fun cleanRecoveries(state: JsonObject): Boolean {
        // The synced copy only matters when there is a recovery to keep or drop.
        val hasRecovery = (state.getAsJsonArray("mergeRecoveries")?.size() ?: 0) > 0
        return SourceDraftMerge.cleanRecoveries(state, if (hasRecovery) syncedSource() else null)
    }

    // ---------------------------------------------------------------- memory

    /** Caller holds [LOCK]. */
    private fun load(retain: Boolean = true): Entry? {
        entries[path]?.let { return it }
        if (!readable(file)) return null
        val started = System.nanoTime()
        val bytes = file.length()
        val state = file.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        val parsed = System.nanoTime()
        val migrated = migrate(state)
        // Recovery-only maintenance does not invalidate an open editor's draft revision.
        val cleaned = cleanRecoveries(state)
        if (migrated || cleaned) synchronized(IO_LOCK) { store(state) }
        val entry = Entry(state)
        if (retain) entries[path] = entry
        log("journal.open ${(System.nanoTime() - started) / 1_000_000} ms parse=${(parsed - started) / 1_000_000} ms " +
            "bytes=$bytes migrated=$migrated events=${state.getAsJsonArray("events")?.size() ?: 0} " +
            "undo=${state.getAsJsonArray("undo")?.size() ?: 0} redo=${state.getAsJsonArray("redo")?.size() ?: 0}")
        return entry
    }

    /** Caller holds [LOCK]. Publishes [state] (owned by the journal from now on) and writes it. */
    private fun commit(state: JsonObject, durable: Boolean) {
        val entry = entries[path] ?: Entry(state).also { entries[path] = it }
        val previous = entry.state
        entry.state = state
        entry.sequence += 1
        if (!durable) {
            writer.execute(::flush)
            return
        }
        try {
            synchronized(IO_LOCK) {
                if (entry.written < entry.sequence) {
                    store(state)
                    entry.written = entry.sequence
                }
            }
            entry.error = null
        } catch (error: Exception) {
            entry.state = previous
            entry.sequence += 1
            writer.execute(::flush)
            throw error
        }
    }

    /** Writer thread: write the newest state once, however many patches arrived meanwhile. */
    private fun flush() {
        val entry: Entry
        val state: JsonObject
        val sequence: Long
        synchronized(LOCK) {
            entry = entries[path] ?: return
            state = entry.state
            sequence = entry.sequence
        }
        val started = System.nanoTime()
        try {
            synchronized(IO_LOCK) {
                if (entry.written >= sequence) return
                store(state)
                entry.written = sequence
            }
            entry.error = null
            val millis = (System.nanoTime() - started) / 1_000_000
            if (millis >= 20) log("journal.write $millis ms bytes=${file.length()}")
        } catch (error: Exception) {
            entry.error = error.message ?: error.javaClass.simpleName
            log("journal.write failed type=${error.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- API

    fun read(): JsonObject? = synchronized(LOCK) { load()?.state?.let(::copyState) }

    /** The exact recorded event, with its stored documents restored. */
    fun hydratedEvent(eventId: String): JsonObject? = synchronized(LOCK) {
        load()?.state?.getAsJsonArray("events")
            ?.firstOrNull { (it as? JsonObject)?.text("event_id") == eventId }
            ?.asJsonObject?.deepCopy()
    }?.let(::hydrate)

    fun sourceSynced(contents: String) = synchronized(LOCK) {
        if (!readable(file) && entries[path] == null) return@synchronized
        atomicWrite(syncedFile) { it.write(contents.toByteArray(Charsets.UTF_8)) }
        val entry = load() ?: return@synchronized
        val state = copyState(entry.state)
        if (cleanRecoveries(state)) commit(state, durable = true)
        Unit
    }

    fun write(state: JsonObject) = synchronized(LOCK) {
        normalize(state)
        cleanRecoveries(state)
        validateEvents(state)
        val previous = load()?.state
        val revision = previous?.get("journalRevision")?.asLong ?: 0L
        // A blur during Activity recreation can persist the unchanged old view
        // after the replacement has read the journal. Do not invalidate that read.
        if (previous != null && sameExceptRevision(previous, state)) {
            state.addProperty("journalRevision", revision)
            return@synchronized
        }
        require((state.get("journalRevision")?.asLong ?: 0L) == revision) { "This source draft changed in another window. Reopen it before editing further." }
        state.addProperty("journalRevision", revision + 1)
        try { commit(copyState(state), durable = true) }
        catch (error: Exception) { state.addProperty("journalRevision", revision); throw error }
    }

    fun validateEvents(state: JsonObject) = synchronized(LOCK) {
        require(state.get("filename").asString == sourceFile)
        val events = state.getAsJsonArray("events").map { it.asJsonObject }
        require(events.map { it.get("event_id").asString }.distinct().size == events.size)
        val old = load()?.state?.getAsJsonArray("events")?.map { it.asJsonObject }.orEmpty()
        val byId = events.associateBy { it.get("event_id").asString }
        old.forEach { require(byId[it.get("event_id").asString] == it) { "Existing review history cannot be rewritten." } }
        val known = old.mapTo(hashSetOf()) { it.get("event_id").asString }
        events.filter { it.get("event_id").asString !in known }.forEach(::validateNewEvent)
    }

    private fun validateNewEvent(event: JsonObject) {
        require(event.getAsJsonObject("snapshot").get("source_file").asString == sourceFile)
        require(event.toString().toByteArray().size < MAX_EVENT_BYTES) { "Review history exceeds the synchronized event limit." }
    }

    /**
     * Applies a page patch to the journal in memory. A patch carries the page's revision, the
     * source as a full text or as one edit since the last persisted source, the undo stacks, and
     * only the events appended since [eventBase]. Throws [ResyncRequired] when the patch was made
     * against another state; the page then sends its whole state through [write].
     */
    fun patched(patch: JsonObject, maxSourceBytes: Int): JsonObject = synchronized(LOCK) {
        val current = load()?.state ?: throw ResyncRequired("no journal")
        val revision = current.long("journalRevision")
        if (patch.long("journalRevision") != revision) throw ResyncRequired("revision")
        if (patch.text("filename") != current.text("filename") || patch.text("baseSha256") != current.text("baseSha256")) {
            throw ResyncRequired("base")
        }
        val next = copyState(current)
        val source = current.text("source").orEmpty()
        patch.text("source")?.let { next.addProperty("source", it) }
        patch.getAsJsonObject("sourceEdit")?.let { edit ->
            val start = edit.get("start").asInt
            val removed = edit.get("removed").asInt
            val inserted = edit.get("inserted").asString
            if (start < 0 || removed < 0 || start + removed > source.length) throw ResyncRequired("edit bounds")
            val updated = source.substring(0, start) + inserted + source.substring(start + removed)
            if (updated.length != edit.get("length").asInt || ReviewTextEdit.hash(updated) != edit.get("hash").asLong) {
                throw ResyncRequired("edit hash")
            }
            next.addProperty("source", updated)
        }
        check(utf8Length(next.text("source").orEmpty()) <= maxSourceBytes) { "Source is too large." }
        for (stack in listOf("undo", "redo")) {
            val steps = patch.getAsJsonArray(stack) ?: throw ResyncRequired(stack)
            next.add(stack, if (steps.any { (it as? JsonObject)?.let(::legacyStep) == true || (it as? JsonObject)?.get("event") is JsonObject })
                JsonArray().apply { steps.forEach { add(compactStep(it.asJsonObject)) } } else steps)
        }
        if (patch.has("hideProposals")) next.add("hideProposals", patch.get("hideProposals")) else next.remove("hideProposals")
        val events = next.getAsJsonArray("events") ?: JsonArray().also { next.add("events", it) }
        val base = patch.get("eventBase").asInt
        val count = patch.get("eventCount").asInt
        val sent = patch.getAsJsonArray("events")
        require(count >= events.size()) { "Existing review history cannot be rewritten." }
        if (base > events.size() || base + sent.size() != count) throw ResyncRequired("events")
        val ids = events.mapTo(hashSetOf()) { it.asJsonObject.get("event_id").asString }
        sent.forEachIndexed { index, element ->
            val event = element.asJsonObject
            if (base + index < events.size()) {
                externalize(event)
                require(event == events[base + index]) { "Existing review history cannot be rewritten." }
            } else {
                validateNewEvent(event)
                require(ids.add(event.get("event_id").asString)) { "Review history repeats an event." }
                externalize(event)
                events.add(event)
            }
        }
        next.addProperty("journalRevision", revision)
        next
    }

    /** Persists a page patch; see [patched]. The disk write happens on the journal thread. */
    fun persist(patch: JsonObject, maxSourceBytes: Int): JsonObject = synchronized(LOCK) {
        val started = System.nanoTime()
        val next = patched(patch, maxSourceBytes)
        val current = entries[path]!!
        val revision = current.state.long("journalRevision")
        val changed = !sameExceptRevision(next, current.state)
        if (changed) {
            next.addProperty("journalRevision", revision + 1)
            commit(next, durable = false)
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        if (millis >= 5) log("journal.persist $millis ms changed=$changed sent=${patch.getAsJsonArray("events")?.size() ?: 0}")
        JsonObject().apply {
            addProperty("journalRevision", if (changed) revision + 1 else revision)
            addProperty("eventCount", next.getAsJsonArray("events").size())
            current.error?.let { addProperty("warning", "Could not write the draft on this phone: $it") }
        }
    }

    fun prepareSave(state: JsonObject) {
        state.addProperty("savingSource", state.get("source").asString)
        write(state)
    }
    fun recoverSave(state: JsonObject, working: String) {
        if (state.get("savingSource")?.asString == working) finishSave(state, working)
    }
    fun finishSave(state: JsonObject, source: String) {
        val hash = ProposalSourceActivity.digest(source)
        val events = state.getAsJsonArray("events")
        val undone = mutableSetOf<String>()
        events.forEach { val e=it.asJsonObject
            if (e.get("kind").asString == "review_undone") undone.add(e.get("related_event_id").asString)
            if (e.get("kind").asString == "review_redone") undone.remove(e.get("related_event_id").asString)
        }
        val saved = events.mapNotNullTo(hashSetOf()) { val s = it.asJsonObject
            if (s.get("kind").asString == "source_saved") s.get("related_event_id")?.takeIf { it.isJsonPrimitive }?.asString else null }
        val confirmations = events.map { it.asJsonObject }.filter { e ->
            e.has("review") && e.get("event_id").asString !in undone && e.get("source_after_sha256").asString == hash &&
                e.get("event_id").asString !in saved
        }.map { e ->
            val now=Instant.now().toString()
            JsonObject().apply {
                addProperty("schema_version",1); addProperty("event_id",UUID.randomUUID().toString())
                add("proposal_id",e.get("proposal_id")); addProperty("kind","source_saved")
                addProperty("observed_at",now); addProperty("updated_at",now); addProperty("authority","local_source")
                addProperty("source_operation_id",""); addProperty("source_before_sha256",hash); addProperty("source_after_sha256",hash)
                add("snapshot",e.get("snapshot").deepCopy()); addProperty("revision",1); addProperty("deleted",false)
                addProperty("producer",ProposalSourceActivity.PRODUCER); add("related_event_id",e.get("event_id")); addProperty("source_state","saved_file")
            }
        }
        confirmations.forEach(events::add)
        state.addProperty("savedSource", source); state.remove("savingSource"); write(state)
    }
}
