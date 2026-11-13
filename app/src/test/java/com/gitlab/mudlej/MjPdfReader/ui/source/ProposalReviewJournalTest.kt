// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.source

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProposalReviewJournalTest {
    private lateinit var root: File
    private val filename = "chapters/results.tex"

    @Before fun setUp() { root = Files.createTempDirectory("review-journal").toFile() }
    @After fun tearDown() { ProposalReviewJournal.forgetCachedJournals(); root.deleteRecursively() }

    // ---------------------------------------------------------------- fixtures

    private fun texSource(chars: Int): String {
        val paragraph = "\\section{Results}\nThe distillation objective improves calibration across all held-out tasks, " +
            "with the largest gains where \\cite{teacher} reports overconfidence.\n\n"
        return buildString { while (length < chars) append(paragraph) }
    }

    private fun event(kind: String, proposal: Int, document: String?, documentKey: String): JsonObject = JsonObject().apply {
        addProperty("schema_version", 1); addProperty("event_id", UUID.randomUUID().toString())
        addProperty("proposal_id", "proposal-$proposal"); addProperty("kind", kind)
        addProperty("observed_at", "2026-10-10T20:${10 + proposal}:00.000Z")
        add("snapshot", JsonObject().apply {
            addProperty("proposal_id", "proposal-$proposal"); addProperty("source_file", filename)
            addProperty("comment", "Tighten the claim."); addProperty("before", "Old $proposal."); addProperty("after", "New $proposal.")
        })
        if (document != null) {
            val context = JsonObject().apply { addProperty(documentKey, document); add("conversation_ids", JsonArray()) }
            if (kind == "first_seen") add("context", context)
            else add("review", JsonObject().apply { addProperty("action", "accept"); add("context", context) })
        }
    }

    /** A format-1 journal: [steps] typing steps and [decisions] decisions, each step two copies of the file. */
    private fun legacyState(source: String, steps: Int, decisions: Int): JsonObject {
        val events = JsonArray()
        repeat(decisions) { events.add(event("first_seen", it, source, "document_at_observation")) }
        val undo = JsonArray()
        var text = source
        var cursor = source.length / 2
        repeat(steps) { step ->
            val word = "word$step "
            val after = text.substring(0, cursor) + word + text.substring(cursor)
            val decision = if (step % (steps / maxOf(decisions, 1)) == 0 && events.size() < 2 * decisions) {
                event("accepted", events.size() - decisions, text, "document_before_review").also(events::add)
            } else null
            undo.add(JsonObject().apply {
                addProperty("before", text); addProperty("after", after)
                add("event", decision ?: JsonNull.INSTANCE)
            })
            text = after
            cursor += word.length
        }
        return JsonObject().apply {
            addProperty("filename", filename); addProperty("baseSha256", "base")
            addProperty("source", text); addProperty("savedSource", source)
            add("events", events); add("undo", undo); add("redo", JsonArray())
            addProperty("journalRevision", 7)
        }
    }

    private fun journalFile() = File(root, ProposalSourceActivity.digest(filename) + ".json")
    private fun writeLegacy(state: JsonObject) { root.mkdirs(); journalFile().writeText(state.toString()) }

    /** What ui.js sends after one keystroke; see persistPayload. */
    private fun typingPatch(state: JsonObject, at: Int, typed: String): JsonObject {
        val source = state.get("source").asString
        val updated = source.substring(0, at) + typed + source.substring(at)
        val undo = state.getAsJsonArray("undo").deepCopy().apply { add(ReviewTextEdit.of(source, updated).toJson(typing = true)) }
        return JsonObject().apply {
            addProperty("patch", 1); add("filename", state.get("filename")); add("baseSha256", state.get("baseSha256"))
            add("journalRevision", state.get("journalRevision"))
            add("undo", undo); add("redo", JsonArray())
            addProperty("eventBase", state.getAsJsonArray("events").size()); addProperty("eventCount", state.getAsJsonArray("events").size())
            add("events", JsonArray())
            add("sourceEdit", JsonObject().apply {
                addProperty("start", at); addProperty("removed", 0); addProperty("inserted", typed)
                addProperty("length", updated.length); addProperty("hash", ReviewTextEdit.hash(updated))
            })
        }
    }

    // ---------------------------------------------------------------- behaviour

    @Test fun hashMatchesTheWebView() {
        // Same constants as scripts/test-proposal-review.cjs.
        assertEquals(2166136261L, ReviewTextEdit.hash(""))
        assertEquals(970283961L, ReviewTextEdit.hash("héllo 😀 wörld\n"))
        assertEquals(500786573L, ReviewTextEdit.hash("a".repeat(1000)))
    }

    @Test fun textEditsNeverSplitSurrogatePairsAndRoundTrip() {
        val edit = ReviewTextEdit.of("a😀b", "a😁b")
        assertEquals(ReviewTextEdit(1, "😀", "😁", 4), edit)
        assertEquals("a😀b", edit.undo("a😁b"))
        assertEquals("a😁b", edit.redo("a😀b"))
        assertThrows(IllegalArgumentException::class.java) { edit.undo("a😀b") }
    }

    @Test fun legacyJournalMigratesToSpansAndStoresEachDocumentOnce() {
        val source = texSource(20_000)
        val legacy = legacyState(source, steps = 20, decisions = 4)
        writeLegacy(legacy)
        val before = journalFile().length()
        val journal = ProposalReviewJournal(root, filename)
        val state = journal.read()!!
        assertEquals(ProposalReviewJournal.FORMAT, state.get("format").asInt)
        assertEquals(legacy.get("source"), state.get("source"))
        assertEquals(7L, state.get("journalRevision").asLong)
        // Undo replays exactly to the saved source.
        var text = state.get("source").asString
        for (step in state.getAsJsonArray("undo").reversed()) text = ReviewTextEdit.fromJson(step.asJsonObject)!!.undo(text)
        assertEquals(source, text)
        val decisionStep = state.getAsJsonArray("undo").first { !it.asJsonObject.get("event").isJsonNull }.asJsonObject
        assertTrue(decisionStep.get("event").isJsonPrimitive)
        // The four first_seen documents and the first decision saw the same text: one file. The
        // three later decisions each saw another text.
        val documents = File(root, ProposalSourceActivity.digest(filename) + ".documents").listFiles()!!
        assertEquals(1 + 3, documents.size)
        val after = journalFile().length()
        assertTrue("journal $before -> $after bytes", after * 10 < before)
        // The exact record is restored on request.
        val firstSeen = state.getAsJsonArray("events")[0].asJsonObject
        assertTrue(firstSeen.getAsJsonObject("context").get("document_at_observation").isJsonObject)
        val hydrated = journal.hydratedEvent(firstSeen.get("event_id").asString)!!
        assertEquals(legacy.getAsJsonArray("events")[0], hydrated)
        // A fresh process reads the migrated file.
        ProposalReviewJournal.forgetCachedJournals()
        assertEquals(state, ProposalReviewJournal(root, filename).read())
    }

    @Test fun patchesPersistEditsAndSurviveProcessDeath() {
        val source = texSource(5_000)
        writeLegacy(legacyState(source, steps = 3, decisions = 0))
        val journal = ProposalReviewJournal(root, filename)
        val state = journal.read()!!
        val response = journal.persist(typingPatch(state, 10, "typed"), 10_000_000)
        assertEquals(8L, response.get("journalRevision").asLong)
        ProposalReviewJournal.forgetCachedJournals() // Waits for the background write, then forgets.
        val reopened = ProposalReviewJournal(root, filename).read()!!
        assertTrue(reopened.get("source").asString.startsWith(state.get("source").asString.substring(0, 10) + "typed"))
        assertEquals(4, reopened.getAsJsonArray("undo").size())
        assertEquals(8L, reopened.get("journalRevision").asLong)
    }

    @Test fun mismatchedPatchesAskForTheWholeStateAndStaleStatesStayRejected() {
        writeLegacy(legacyState(texSource(5_000), steps = 2, decisions = 0))
        val journal = ProposalReviewJournal(root, filename)
        val state = journal.read()!!
        val stale = typingPatch(state, 0, "a")
        journal.persist(typingPatch(state, 5, "b"), 10_000_000)
        assertThrows(ProposalReviewJournal.ResyncRequired::class.java) { journal.persist(stale, 10_000_000) }
        val wrongHash = typingPatch(journal.read()!!, 0, "c").apply { getAsJsonObject("sourceEdit").addProperty("hash", 1) }
        assertThrows(ProposalReviewJournal.ResyncRequired::class.java) { journal.persist(wrongHash, 10_000_000) }
        // The page's whole state at the old revision is still refused, as before.
        state.addProperty("source", "other")
        assertThrows(IllegalArgumentException::class.java) { journal.write(state) }
        // The unchanged old view (a blur during recreation) is accepted without a new revision.
        val current = journal.read()!!
        val revision = current.get("journalRevision").asLong
        journal.write(journal.read()!!.apply { addProperty("journalRevision", 3) })
        assertEquals(revision, journal.read()!!.get("journalRevision").asLong)
    }

    @Test fun eventsAreAppendOnlyThroughPatches() {
        writeLegacy(legacyState(texSource(5_000), steps = 2, decisions = 1))
        val journal = ProposalReviewJournal(root, filename)
        val state = journal.read()!!
        val count = state.getAsJsonArray("events").size()
        val added = event("accepted", 9, texSource(3_000), "document_before_review")
        val patch = typingPatch(state, 0, "x").apply {
            addProperty("eventCount", count + 1); add("events", JsonArray().apply { add(added.deepCopy()) })
        }
        assertEquals(count + 1, journal.persist(patch, 10_000_000).get("eventCount").asInt)
        val stored = journal.read()!!.getAsJsonArray("events").last().asJsonObject
        assertTrue(stored.getAsJsonObject("review").getAsJsonObject("context").get("document_before_review").isJsonObject)
        assertEquals(added, journal.hydratedEvent(added.get("event_id").asString))
        // Dropping or rewriting a recorded event is refused.
        val dropped = typingPatch(journal.read()!!, 0, "y").apply { addProperty("eventCount", count); addProperty("eventBase", count) }
        assertThrows(IllegalArgumentException::class.java) { journal.persist(dropped, 10_000_000) }
        val rewritten = typingPatch(journal.read()!!, 0, "z").apply {
            addProperty("eventBase", count); addProperty("eventCount", count + 1)
            add("events", JsonArray().apply { add(added.deepCopy().apply { addProperty("kind", "rejected") }) })
        }
        assertThrows(IllegalArgumentException::class.java) { journal.persist(rewritten, 10_000_000) }
    }

    @Test fun saveConfirmationsAndRecoveryWorkOnTheCompactJournal() {
        val source = texSource(2_000)
        writeLegacy(legacyState(source, steps = 2, decisions = 0))
        val journal = ProposalReviewJournal(root, filename)
        val state = journal.read()!!
        journal.prepareSave(state)
        ProposalReviewJournal.forgetCachedJournals()
        val reopened = ProposalReviewJournal(root, filename)
        val recovered = reopened.read()!!
        reopened.recoverSave(recovered, recovered.get("source").asString)
        val saved = reopened.read()!!
        assertEquals(saved.get("source"), saved.get("savedSource"))
        assertFalse(saved.has("savingSource"))
        assertEquals(2, saved.getAsJsonArray("undo").size())
    }

    // ---------------------------------------------------------------- benchmark

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocated(thread: Thread = Thread.currentThread()) = threads.getThreadAllocatedBytes(thread.id)
    private fun writerThread() = Thread.getAllStackTraces().keys.firstOrNull { it.name == "thoughts-review-journal" }

    private data class Cost(val millis: Double, val megabytes: Double) {
        override fun toString() = "%.1f ms, %.1f MB allocated".format(millis, megabytes)
    }

    private fun measure(block: () -> Unit): Cost {
        val writer = writerThread()
        val writerBefore = writer?.let(::allocated) ?: 0L
        val before = allocated()
        val started = System.nanoTime()
        block()
        val millis = (System.nanoTime() - started) / 1e6
        val writerAfter = (writer ?: writerThread())?.let(::allocated) ?: 0L
        return Cost(millis, (allocated() - before + writerAfter - writerBefore) / 1e6)
    }

    /**
     * 60 KB .tex source, 200 typing steps, 10 proposals observed and decided: the journal shape
     * behind the 205/60/117 MB large-object GCs when the editor opened on 2026-10-10.
     */
    @Test fun benchmarkOpenAndPersistBeforeAndAfter() {
        val source = texSource(60_000)
        val legacy = legacyState(source, steps = 200, decisions = 10)
        writeLegacy(legacy)
        val legacyBytes = journalFile().length()
        val old = LegacyProposalReviewJournal(root, filename)
        repeat(2) { old.read() } // JIT warm-up
        val oldOpen = measure { old.read() }
        // The page sent its whole state (same size as the journal) every typing pause.
        val oldState = old.read()!!
        val oldText = oldState.get("source").asString
        oldState.addProperty("source", oldText.substring(0, 100) + "x" + oldText.substring(100))
        oldState.getAsJsonArray("undo").add(JsonObject().apply {
            addProperty("before", oldText); addProperty("after", oldState.get("source").asString); add("event", JsonNull.INSTANCE)
        })
        val oldPayload = oldState.toString()
        val oldPersist = measure { old.persist(oldPayload) }

        writeLegacy(legacy)
        val migration = measure { ProposalReviewJournal(root, filename).read() }
        ProposalReviewJournal.forgetCachedJournals()
        val compactBytes = journalFile().length()
        repeat(2) { ProposalReviewJournal(root, filename).read(); ProposalReviewJournal.forgetCachedJournals() }
        val newOpen = measure { ProposalReviewJournal(root, filename).read() }
        val reopen = measure { ProposalReviewJournal(root, filename).read() } // Activity recreation: in memory.
        val journal = ProposalReviewJournal(root, filename)
        var state = journal.read()!!
        repeat(3) { journal.persist(typingPatch(state, 100 + it, "w"), 10_000_000); state = journal.read()!!; ProposalReviewJournal.awaitWrites() }
        val patch = typingPatch(state, 100, "x")
        val newPayload = patch.toString()
        val newPersist = measure {
            journal.persist(JsonParser.parseString(newPayload).asJsonObject, 10_000_000)
            ProposalReviewJournal.awaitWrites()
        }
        val report = """
            |journal file: ${legacyBytes / 1000} KB (format 1) -> ${compactBytes / 1000} KB (format 2)
            |open  before: $oldOpen
            |open  after:  $newOpen (first open migrates once: $migration; reopen in process: $reopen)
            |persist payload: ${oldPayload.length / 1000} KB -> ${newPayload.length / 1000} KB
            |persist before: $oldPersist
            |persist after:  $newPersist (includes the background write)
        """.trimMargin()
        println(report)
        File(System.getProperty("java.io.tmpdir"), "proposal-review-journal-benchmark.txt").writeText(report + "\n")
        assertTrue(compactBytes * 20 < legacyBytes)
        assertTrue("open: $oldOpen -> $newOpen", newOpen.megabytes * 10 < oldOpen.megabytes)
        assertTrue("persist: $oldPersist -> $newPersist", newPersist.megabytes * 20 < oldPersist.megabytes)
        assertNotNull(journal.read())
    }
}
