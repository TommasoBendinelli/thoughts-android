// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agents

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class AgentConversationsTest {
    private lateinit var directory: File
    private val previousFile = AgentConversations.file

    @Before fun setUp() {
        directory = Files.createTempDirectory("agent-conversations").toFile()
        AgentConversations.file = { File(directory, "agent-conversations.json") }
    }

    @After fun tearDown() {
        AgentConversations.file = previousFile
        directory.deleteRecursively()
    }

    private val claudeId = "5ef4cd0f-6a8a-5b7a-ac7b-1e9f51cd3139"
    private val codexId = "01900000-0000-7000-8000-000000000002"
    private val concepts = "source:docs-concepts"
    private val newProject = "new:8b964c95-8bc0-4045-a38e-015d2172d7ca"

    /** The spec §2 example payload. */
    private fun example(): JsonObject = JsonParser.parseString("""
        {
          "schema_version": 1,
          "link_id": "ef8e44d466d11197fa75b69c419f74b574068655d7b0dd3ffeced7c341db3f4d",
          "project_id": "source:docs-concepts",
          "agent": "claude",
          "conversation_id": "5ef4cd0f-6a8a-5b7a-ac7b-1e9f51cd3139",
          "conversation_url": "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv",
          "title": "Thoughts search on mobile",
          "title_source": "app",
          "session_name": "alex-94",
          "linked_by": "tool",
          "last_tool": "create_proposal",
          "device_name": "Alexs-MacBook-Pro",
          "created_at": "2026-10-26T11:02:13.511Z",
          "last_active_at": "2026-10-26T11:40:02.004Z",
          "updated_at": "2026-10-26T11:40:02.004Z",
          "revision": 3,
          "origin_id": "desktop-abc",
          "removed": false,
          "removed_at": null,
          "deleted": false
        }
    """.trimIndent()).asJsonObject

    private fun record(
        projectId: String = concepts,
        agent: String = "claude",
        conversationId: String = claudeId,
        url: String = "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv",
        lastActive: String = "2026-10-26T11:40:02.004Z",
        updatedAt: String = lastActive,
        revision: Int = 3,
        origin: String = "desktop-abc",
        removed: Boolean = false,
        title: String = "Conversation",
    ): JsonObject = example().apply {
        addProperty("link_id", AgentConversations.linkId(projectId, agent, conversationId))
        addProperty("project_id", projectId)
        addProperty("agent", agent)
        addProperty("conversation_id", conversationId)
        addProperty("conversation_url", url)
        addProperty("title", title)
        addProperty("last_active_at", lastActive)
        addProperty("updated_at", updatedAt)
        addProperty("revision", revision)
        addProperty("origin_id", origin)
        addProperty("removed", removed)
        if (removed) addProperty("removed_at", updatedAt) else add("removed_at", JsonNull.INSTANCE)
    }

    private fun parse(payload: JsonObject) = requireNotNull(AgentConversations.parse(payload))

    @Test fun linkIdMatchesTheSpecVectors() {
        assertEquals("ef8e44d466d11197fa75b69c419f74b574068655d7b0dd3ffeced7c341db3f4d",
            AgentConversations.linkId(concepts, "claude", claudeId))
        assertEquals("e05cfb424a6849e70f4fc8ad7eb8ba99a5c6c25da759ccf97fae0a9aadca6679",
            AgentConversations.linkId(newProject, "codex", codexId))
    }

    @Test fun theSpecExampleIsValid() {
        assertNull(AgentConversations.validationError(example()))
        assertNull(AgentConversations.validationError(record(newProject, "codex", codexId, url = "codex://threads/$codexId")))
        assertNull(AgentConversations.validationError(record(url = "")))
        // `deleted` is optional and unknown fields are allowed.
        assertNull(AgentConversations.validationError(example().apply { remove("deleted"); addProperty("future", 1) }))
    }

    @Test fun validationRejectsWhatTheServerRejects() {
        fun invalid(edit: JsonObject.() -> Unit) =
            assertNotNull(AgentConversations.validationError(example().apply(edit)))
        invalid { addProperty("schema_version", 2) }
        invalid { addProperty("link_id", "0".repeat(64)) }
        invalid { addProperty("link_id", "EF8E44D466D11197FA75B69C419F74B574068655D7B0DD3FFECED7C341DB3F4D") }
        invalid { addProperty("agent", "gpt") }
        invalid { addProperty("conversation_id", "not-a-uuid") }
        invalid { addProperty("conversation_url", "https://claude.ai/chat/5ef4cd0f") }
        invalid { addProperty("conversation_url", "codex://threads/$claudeId") } // Codex link on a Claude record.
        invalid { addProperty("title", "two\nlines") }
        invalid { addProperty("title", "   ") }
        invalid { addProperty("title", "x".repeat(201)) }
        invalid { addProperty("title_source", "guess") }
        invalid { addProperty("session_name", "s".repeat(101)) }
        invalid { addProperty("linked_by", "user") }
        invalid { addProperty("last_tool", "Create-Proposal") }
        invalid { addProperty("device_name", "d".repeat(129)) }
        invalid { addProperty("updated_at", "yesterday") }
        invalid { addProperty("revision", 0) }
        invalid { addProperty("revision", 1.5) }
        invalid { addProperty("origin_id", "") }
        invalid { addProperty("removed", "false") }
        invalid { addProperty("removed", true) } // without removed_at
        invalid { addProperty("removed_at", "2026-10-26T12:00:00.000Z") } // while not removed
        invalid { addProperty("deleted", 0) }
        invalid { addProperty("project_id", "a\u0007b") }
        // A Codex record must link exactly to its own thread.
        val codex = record(newProject, "codex", codexId, url = "codex://threads/$codexId")
        assertNotNull(AgentConversations.validationError(codex.deepCopy().apply {
            addProperty("conversation_url", "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv")
        }))
        assertNotNull(AgentConversations.validationError(codex.deepCopy().apply {
            addProperty("conversation_url", "codex://threads/$claudeId")
        }))
        assertEquals(200, "é".repeat(200).length)
        assertNull(AgentConversations.validationError(example().apply { addProperty("title", "é".repeat(200)) }))
    }

    @Test fun removeKeepsTheRecordAndFlagsIt() {
        val stored = parse(example().apply { addProperty("future_field", "kept") })
        val now = Instant.parse("2026-10-26T12:00:00.123456Z")
        val removed = AgentConversations.removedRecord(stored, "phone-origin", now)
        assertNull(AgentConversations.validationError(removed.payload))
        assertTrue(removed.removed)
        assertFalse(removed.visible)
        assertEquals("2026-10-26T12:00:00.123Z", removed.payload.get("removed_at").asString)
        assertEquals("2026-10-26T12:00:00.123Z", removed.updatedAt)
        assertEquals(4L, removed.revision)
        assertEquals("phone-origin", removed.originId)
        assertEquals("2026-10-26T11:40:02.004Z", removed.lastActiveAt) // unchanged
        assertEquals("2026-10-26T11:02:13.511Z", removed.payload.get("created_at").asString)
        assertEquals("kept", removed.payload.get("future_field").asString)
        assertEquals("tool", removed.payload.get("linked_by").asString)
        // The stored record is not modified.
        assertFalse(stored.removed)
    }

    @Test fun removeWritesAnUpdatedAtAfterTheStoredOneEvenIfTheClockIsBehind() {
        val stored = parse(example())
        val removed = AgentConversations.removedRecord(stored, "phone", Instant.parse("2026-10-26T11:00:00Z"))
        assertEquals("2026-10-26T11:40:02.005Z", removed.updatedAt)
        val sameMillisecond = AgentConversations.removedRecord(stored, "phone", Instant.parse("2026-10-26T11:40:02.004900Z"))
        assertEquals("2026-10-26T11:40:02.005Z", sameMillisecond.updatedAt)
    }

    @Test fun removalIsStoredPendingAndUploadedWithTheBaseRevision() {
        val stored = parse(example())
        val removed = AgentConversations.remove(stored, "phone", Instant.parse("2026-10-26T12:00:00Z"))
        assertEquals(listOf(removed), AgentConversations.pending())
        val upload = AgentConversations.pendingUploads().single()
        assertEquals(stored.linkId, upload.entityId)
        assertEquals(3L, upload.baseRevision)
        assertEquals("2026-10-26T12:00:00.000Z", upload.clientTimestamp)
        assertEquals(removed.payload, upload.payload)
        // Removing again is a no-op and keeps the operation id stable.
        assertEquals(removed, AgentConversations.remove(stored, "phone", Instant.parse("2026-10-26T12:05:00Z")))
        assertEquals(upload.operationId, AgentConversations.pendingUploads().single().operationId)
        // A fresh read from disk gives the same pending record.
        assertTrue(File(directory, "agent-conversations.json").readText().contains(stored.linkId))
    }

    @Test fun removeRefusesARecordThatWouldNotValidate() {
        val broken = parse(example().apply { addProperty("title", "") })
        val failure = runCatching { AgentConversations.remove(broken, "phone") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(AgentConversations.pending().isEmpty())
    }

    @Test fun visibleListHidesRemovedAndDeletedAndSortsByActivity() {
        val older = record(conversationId = "11111111-1111-4111-8111-111111111111", lastActive = "2026-10-25T09:00:00.000Z")
        val newer = record(conversationId = "22222222-2222-4222-8222-222222222222", lastActive = "2026-10-26T09:00:00.000Z")
        val tieA = record(conversationId = "33333333-3333-4333-8333-333333333333", lastActive = "2026-10-24T09:00:00.000Z")
        val tieB = record(conversationId = "44444444-4444-4444-8444-444444444444", lastActive = "2026-10-24T09:00:00.000Z")
        val removed = record(conversationId = "55555555-5555-4555-8555-555555555555", removed = true)
        val deleted = record(conversationId = "66666666-6666-4666-8666-666666666666").apply { addProperty("deleted", true) }
        val other = record(projectId = newProject, agent = "codex", conversationId = codexId, url = "")
        val visible = AgentConversations.visibleFor(concepts, listOf(older, tieB, removed, newer, deleted, tieA, other), emptyList())
        val ties = listOf(tieA, tieB).map { it.get("link_id").asString }.sorted()
        assertEquals(listOf(newer.get("link_id").asString, older.get("link_id").asString) + ties, visible.map { it.linkId })
        assertEquals(listOf(codexId), AgentConversations.visibleFor(newProject, listOf(older, other), emptyList()).map { it.conversationId })
        assertTrue(AgentConversations.visibleFor("unknown", listOf(older), emptyList()).isEmpty())
    }

    @Test fun pendingRemovalHidesTheServerRecord() {
        val server = record()
        val pending = AgentConversations.remove(parse(server), "phone", Instant.parse("2026-10-26T12:00:00Z"))
        assertTrue(AgentConversations.visibleFor(concepts, listOf(server), AgentConversations.pending()).isEmpty())
        assertTrue(AgentConversations.visibleByProject(listOf(server), listOf(pending)).isEmpty())
    }

    @Test fun pendingRemovalSettlesOnceTheServerHoldsItOrSomethingLater() {
        val server = record()
        val removed = AgentConversations.remove(parse(server), "phone", Instant.parse("2026-10-26T12:00:00Z"))
        val operationId = AgentConversations.pendingUploads().single().operationId
        // The server still lists the older revision: keep it.
        AgentConversations.installServer(listOf(server))
        assertEquals(1, AgentConversations.pending().size)
        // The server holds the removal: settled.
        AgentConversations.installServer(listOf(removed.payload.deepCopy()), accepted = setOf(operationId))
        assertTrue(AgentConversations.pending().isEmpty())

        // A later touch from the Mac at the same revision wins (spec §3.3 race): settled too.
        AgentConversations.remove(parse(server), "phone", Instant.parse("2026-10-26T12:00:00Z"))
        val macTouch = record(revision = 4, updatedAt = "2026-10-26T12:30:00.000Z", origin = "desktop-abc")
        AgentConversations.installServer(listOf(macTouch))
        assertTrue(AgentConversations.pending().isEmpty())
        assertEquals(1, AgentConversations.visibleFor(concepts, listOf(macTouch), AgentConversations.pending()).size)

        // A superseded operation is dropped even before its projection arrives.
        AgentConversations.remove(parse(server), "phone", Instant.parse("2026-10-26T12:00:00Z"))
        AgentConversations.installServer(emptyList(), superseded = setOf(AgentConversations.pendingUploads().single().operationId))
        assertTrue(AgentConversations.pending().isEmpty())
    }

    @Test fun claudeUrlOnlyForValidClaudeLinks() {
        assertEquals("https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv", parse(record()).claudeUrl)
        assertNull(parse(record(url = "")).claudeUrl)
        assertNull(parse(record(newProject, "codex", codexId, url = "codex://threads/$codexId")).claudeUrl)
        assertNull(parse(record(url = "https://claude.ai/code/session_bad-chars!")).claudeUrl)
    }

    @Test fun parseNeedsIdentityOnly() {
        assertNull(AgentConversations.parse(JsonObject()))
        assertNull(AgentConversations.parse(example().apply { addProperty("agent", "other") }))
        assertNotNull(AgentConversations.parse(example().apply { addProperty("title_source", "future") }))
        assertNotEquals(parse(example()), parse(record(lastActive = "2026-10-27T00:00:00.000Z")))
    }
}
