// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tbend.thoughts.sdk.ServerException

class RefusedLocalChangesTest {
    private fun change(id: String, project: String, action: String = "rename", name: String = "New name", base: Int = 3) =
        JsonObject().apply {
            addProperty("operation_id", id)
            addProperty("project_id", project)
            addProperty("base_revision", base)
            addProperty("action", action)
            addProperty("name", name)
        }

    private fun state(vararg changes: JsonObject) = JsonObject().apply {
        add("projects", JsonArray().apply {
            add(JsonObject().apply { addProperty("id", "project-a"); addProperty("name", "Alpha"); addProperty("revision", 7) })
            add(JsonObject().apply { addProperty("id", "project-b"); addProperty("name", "Beta"); addProperty("revision", 2) })
        })
        add("pending", JsonArray().apply { changes.forEach(::add) })
    }

    private fun refusal() = ServerException(409, """{"detail":"The Project changed on another device"}""")

    @Test
    fun onlyAnAnswerAboutTheChangeItselfIsARefusal() {
        assertTrue(isRefusedByServer(refusal()))
        assertTrue(isRefusedByServer(ServerException(422, "invalid")))
        assertTrue(isRefusedByServer(ServerException(400, "bad request")))
        assertTrue(isRefusedByServer(ServerException(413, "too large")))
        assertTrue(isRefusedByServer(IllegalStateException("wrapped", refusal())))
        // The server or the connection failing says nothing about the change: it is sent again.
        assertFalse(isRefusedByServer(ServerException(500, "down")))
        assertFalse(isRefusedByServer(ServerException(503, "busy")))
        assertFalse(isRefusedByServer(ServerException(404, "no such route")))
        assertFalse(isRefusedByServer(ServerException(401, "token")))
        assertFalse(isRefusedByServer(IOException("timeout")))
    }

    /** One Sync over the queue, as [LibraryProjectStore.synchronize] runs it, against a server that refuses [refused]. */
    private fun sync(state: JsonObject, refused: Set<String>, sent: MutableList<String>, down: Set<String> = emptySet()) =
        drainQueuedProjectChanges(
            next = { failed -> nextProjectChangeToSend(state, failed)?.deepCopy() },
            send = { operation ->
                val id = operation.get("operation_id").asString
                sent += id
                if (id in refused) throw refusal()
                if (id in down) throw IOException("connection reset")
                val pending = state.getAsJsonArray("pending")
                pending.remove(pending.first { it.asJsonObject.get("operation_id").asString == id })
            },
            setAside = { operation, error ->
                assertTrue(setProjectChangeAside(state, operation.get("operation_id").asString, error.message.orEmpty(), 1_000))
            },
        )

    /** 13 November 2026: one refused change was sent again by every Sync, and every Sync failed on it. */
    @Test
    fun aRefusedChangeIsSentOnceSetAsideAndNoLaterSyncFailsOnIt() {
        val state = state(change("op-1", "project-a"), change("op-2", "project-b"), change("op-3", "project-a", name = "Later"))
        val sent = mutableListOf<String>()
        val first = sync(state, refused = setOf("op-1"), sent = sent)
        assertEquals(setOf("op-1"), first.refused)
        // Nothing fails the Sync, and the other Project's change went through.
        assertTrue(first.failures.isEmpty())
        assertEquals(listOf("op-1", "op-2"), sent)
        // Later Syncs send nothing: the refused change waits for the user, and so does the one after it.
        repeat(3) {
            val later = sync(state, refused = setOf("op-1"), sent = sent)
            assertTrue(later.refused.isEmpty())
            assertTrue(later.failures.isEmpty())
        }
        assertEquals(listOf("op-1", "op-2"), sent)
        assertEquals(listOf("op-1"), refusedProjectChanges(state).map { it.operationId })
        assertEquals(listOf("op-3"), sendableProjectChanges(state).map { it.get("operation_id").asString })
    }

    @Test
    fun aFailureThatIsNoRefusalStillFailsTheSyncAndIsSentAgain() {
        val state = state(change("op-1", "project-a"), change("op-2", "project-b"))
        val sent = mutableListOf<String>()
        val outcome = sync(state, refused = emptySet(), sent = sent, down = setOf("op-1"))
        assertEquals(setOf("project-a"), outcome.failures.keys)
        assertTrue(refusedProjectChanges(state).isEmpty())
        sync(state, refused = emptySet(), sent = sent)
        assertEquals(listOf("op-1", "op-2", "op-1"), sent)
    }

    @Test
    fun theSyncDetailsShowWhatWasRefusedAndWhy() {
        val state = state(change("op-1", "project-a", name = "Thesis"))
        assertTrue(setProjectChangeAside(state, "op-1", "The Project changed\n  on another device", 5_000))
        // Setting it aside twice changes nothing.
        assertFalse(setProjectChangeAside(state, "op-1", "again", 6_000))
        assertFalse(setProjectChangeAside(state, "missing", "x", 6_000))
        val shown = refusedProjectChanges(state).single()
        assertEquals("op-1", shown.operationId)
        assertEquals("project-a", shown.projectId)
        assertEquals("Rename Project “Alpha” to “Thesis”", shown.description)
        assertEquals("The Project changed on another device", shown.detail)
        assertEquals(5_000L, shown.refusedAtEpochMillis)
    }

    @Test
    fun retryPutsTheChangeBackOnTopOfTheProjectAsTheServerHasItNow() {
        val state = state(change("op-1", "project-a", base = 3), change("op-3", "project-a", name = "Later", base = 4))
        setProjectChangeAside(state, "op-1", "The Project changed on another device", 5_000)
        assertFalse(retryRefusedProjectChange(state, "op-3"))
        assertTrue(retryRefusedProjectChange(state, "op-1"))
        val retried = state.getAsJsonArray("pending")[0].asJsonObject
        assertFalse(isRefusedProjectChange(retried))
        // The stored catalogue has the Project at revision 7.
        assertEquals(7, retried.get("base_revision").asInt)
        // Its place in the queue is kept, and the later change is sendable again after it.
        assertEquals("op-1", nextProjectChangeToSend(state, emptySet())?.get("operation_id")?.asString)
        assertFalse(retryRefusedProjectChange(state, "op-1"))
    }

    @Test
    fun discardDropsOnlyTheRefusedChangeAndFreesTheOnesBehindIt() {
        val state = state(change("op-1", "project-a"), change("op-3", "project-a", name = "Later"))
        assertNull(discardRefusedProjectChange(state, "op-1"))
        setProjectChangeAside(state, "op-1", "refused", 5_000)
        assertNull(nextProjectChangeToSend(state, emptySet()))
        assertNull(discardRefusedProjectChange(state, "op-3"))
        assertEquals("op-1", discardRefusedProjectChange(state, "op-1")?.get("operation_id")?.asString)
        assertEquals(1, state.getAsJsonArray("pending").size())
        assertEquals("op-3", nextProjectChangeToSend(state, emptySet())?.get("operation_id")?.asString)
    }

    @Test
    fun everyKindOfProjectChangeIsNamed() {
        fun described(action: String, edit: JsonObject.() -> Unit = {}) =
            describeProjectChange(change("op", "project-a", action = action, name = "").apply(edit), "Alpha")
        assertEquals("Create Project “Notes”", described("create") { addProperty("name", "Notes") })
        assertEquals("Remove Project “Alpha”", described("remove"))
        assertEquals("Add the PDF “Paper” to Project “Alpha”", described("import") { addProperty("name", "Paper") })
        assertEquals("Add the HTML page “page_0a.html” to Project “Alpha”",
            described("import_html") { addProperty("filename", "page_0a.html") })
        assertEquals("Add the document “intro.md” to Project “Alpha”",
            described("include_source") { addProperty("source_file", "docs-example/tex/intro.md") })
        assertEquals("Rename a document of Project “Alpha” to “Intro”",
            described("rename_document") { addProperty("name", "Intro") })
        assertEquals("Remove a document from Project “Alpha”", described("remove_document"))
        assertEquals("Reorder the documents of Project “Alpha”", described("order"))
        assertEquals("Add a folder to Project “Alpha”", described("import_folder"))
        assertEquals("Change a Project", describeProjectChange(change("op", "gone", action = "future_action"), ""))
    }

    /** A refused PDF import must be visible outside the Sync details: one blocked row for its file. */
    @Test
    fun aRefusedChangeIsNamedInTheListOfLocalChangesUntilTheUserActs() {
        val import = change("op-1", "project-a", action = "import", name = "Paper").apply {
            addProperty("filename", "paper_0a1b2c3d4e5f_11223344.pdf")
        }
        val state = state(import, change("op-2", "project-b", name = "Thesis"))
        setProjectChangeAside(state, "op-1", "A document with this name exists", 7_000)
        setProjectChangeAside(state, "op-2", "The Project changed on another device", 8_000)
        val refusedRemoval = removal(3, "old.pdf").copy(failureDetail = "The PDF changed on the server", refused = true)
        val notices = refusedChangeNotices(refusedProjectChanges(state), listOf(refusedRemoval, removal(4, "queued.pdf")))
        assertEquals(3, notices.size)
        // The import is a row of its own PDF; a change that is about no PDF goes to the common row.
        assertEquals("paper_0a1b2c3d4e5f_11223344.pdf", notices[0].pdfFilename)
        assertEquals("Add the PDF \u201cPaper\u201d to Project \u201cAlpha\u201d", notices[0].description)
        assertEquals("A document with this name exists", notices[0].detail)
        assertEquals(7_000L, notices[0].refusedAtEpochMillis)
        assertNull(notices[1].pdfFilename)
        assertFalse(notices[1].removal)
        // A refused removal names its PDF; a removal still queued is no notice.
        assertEquals("old.pdf", notices[2].pdfFilename)
        assertTrue(notices[2].removal)
        assertEquals("The PDF changed on the server", notices[2].detail)
        // Discarding or retrying takes the notice away.
        discardRefusedProjectChange(state, "op-1")
        retryRefusedProjectChange(state, "op-2")
        assertTrue(refusedChangeNotices(refusedProjectChanges(state), emptyList()).isEmpty())
    }

    private fun removal(id: Int, filename: String) = PendingLibraryRemoval(
        requestId = "0190a000-0000-7000-8000-00000000000$id", filename = filename, sha256 = id.toString().repeat(64),
        createdAtEpochMillis = id.toLong(),
    )

    @Test
    fun aRefusedRemovalDoesNotStopTheOthersOrTheSync() {
        val removals = listOf(removal(1, "a.pdf"), removal(2, "b.pdf"), removal(3, "c.pdf"))
        val delivered = mutableListOf<String>()
        val refused = mutableListOf<String>()
        deliverLibraryRemovals(
            removals,
            remove = { if (it.filename == "b.pdf") throw refusal() },
            delivered = { delivered += it.filename },
            refused = { removal, _ -> refused += removal.filename },
            failed = { _, _ -> error("no other failure expected") },
        )
        assertEquals(listOf("a.pdf", "c.pdf"), delivered)
        assertEquals(listOf("b.pdf"), refused)
    }

    @Test
    fun aRemovalThatFailsOtherwiseStillFailsTheSync() {
        val failed = mutableListOf<String>()
        val thrown = runCatching {
            deliverLibraryRemovals(
                listOf(removal(1, "a.pdf"), removal(2, "b.pdf")),
                remove = { throw IOException("connection reset") },
                delivered = { },
                refused = { _, _ -> error("not a refusal") },
                failed = { removal, _ -> failed += removal.filename },
            )
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(listOf("a.pdf"), failed)
    }

    @Test
    fun aRefusedRemovalIsKeptNotSentAgainAndCanBeRetriedOrDiscarded() {
        val root = Files.createTempDirectory("refused-removals").toFile()
        try {
            val stateFile = File(root, "removals.json")
            val store = LibraryRemovalStore(stateFile)
            val first = store.enqueue("a.pdf", "a".repeat(64), createdAtEpochMillis = 1)
            val second = store.enqueue("b.pdf", "b".repeat(64), createdAtEpochMillis = 2)
            store.recordRefusal(first.requestId, "The PDF changed on the server")
            // It survives a restart, with the server's reason.
            val restarted = LibraryRemovalStore(stateFile)
            assertEquals(listOf("b.pdf"), restarted.toSend().map { it.filename })
            assertEquals("The PDF changed on the server", restarted.refused().single().failureDetail)
            assertEquals(2, restarted.pending().size)
            // Retry: sent again with the next Sync, without the old reason.
            assertFalse(restarted.retryRefused(second.requestId))
            assertTrue(restarted.retryRefused(first.requestId))
            assertEquals(listOf("a.pdf", "b.pdf"), restarted.toSend().map { it.filename })
            assertEquals("", restarted.toSend().first().failureDetail)
            // Discard: only a refused removal can be discarded here.
            assertFalse(restarted.discardRefused(first.requestId))
            restarted.recordRefusal(first.requestId, "again")
            assertTrue(restarted.discardRefused(first.requestId))
            assertEquals(listOf("b.pdf"), restarted.pending().map { it.filename })
            assertNotNull(restarted.pending().single())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removingAPdfAgainClearsAnEarlierRefusal() {
        val root = Files.createTempDirectory("refused-removals").toFile()
        try {
            val store = LibraryRemovalStore(File(root, "removals.json"))
            val removal = store.enqueue("a.pdf", "a".repeat(64), createdAtEpochMillis = 1)
            store.recordRefusal(removal.requestId, "refused")
            val again = store.enqueue("a.pdf", "a".repeat(64), createdAtEpochMillis = 9)
            assertFalse(again.refused)
            assertEquals(listOf("a.pdf"), store.toSend().map { it.filename })
        } finally {
            root.deleteRecursively()
        }
    }
}
