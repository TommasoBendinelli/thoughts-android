// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agents

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** thoughts-card-agent-conversations-SPEC.md §1–§2: the card list rule and Remove from this card. */
class AgentConversationsCardTest {
    private lateinit var directory: File
    private val previousFile = AgentConversations.file

    @Before fun setUp() {
        directory = Files.createTempDirectory("card-agent-conversations").toFile()
        AgentConversations.file = { File(directory, "agent-conversations.json") }
    }

    @After fun tearDown() {
        AgentConversations.file = previousFile
        directory.deleteRecursively()
    }

    private val cardA = "0fc5768d-a56c-53d0-9f37-7af64c3cf8a9"
    private val cardB = "7d1e2a40-91b3-4c55-8a66-0e5b2f3c4d11"
    private val concepts = "source:docs-concepts"
    private val otherProject = "new:8b964c95-8bc0-4045-a38e-015d2172d7ca"

    private fun entry(
        cardId: String,
        lastActive: String,
        linkedAt: String = "2026-10-26T10:00:00.000Z",
        linkedBy: String = "prompt",
        removed: Boolean = false,
    ): JsonObject = JsonObject().apply {
        addProperty("card_id", cardId)
        addProperty("linked_at", linkedAt)
        addProperty("last_active_at", lastActive)
        addProperty("linked_by", linkedBy)
        addProperty("removed", removed)
    }

    private fun record(
        conversationId: String,
        entries: List<JsonObject>,
        projectId: String = concepts,
        agent: String = "claude",
        lastActive: String = "2026-10-26T11:40:02.004Z",
        updatedAt: String = lastActive,
        revision: Int = 3,
        removed: Boolean = false,
        deleted: Boolean = false,
    ): JsonObject = JsonParser.parseString("""
        {
          "schema_version": 1,
          "conversation_url": "",
          "title": "Card question",
          "title_source": "prompt",
          "session_name": "alex-61",
          "linked_by": "tool",
          "last_tool": "prompt",
          "device_name": "Alexs-MacBook-Pro",
          "created_at": "2026-10-26T10:00:00.000Z",
          "origin_id": "desktop-abc",
          "deleted": false
        }
    """.trimIndent()).asJsonObject.apply {
        addProperty("link_id", AgentConversations.linkId(projectId, agent, conversationId))
        addProperty("project_id", projectId)
        addProperty("agent", agent)
        addProperty("conversation_id", conversationId)
        addProperty("last_active_at", lastActive)
        addProperty("updated_at", updatedAt)
        addProperty("revision", revision)
        addProperty("removed", removed)
        if (removed) addProperty("removed_at", updatedAt) else add("removed_at", JsonNull.INSTANCE)
        addProperty("deleted", deleted)
        add(AgentConversations.REVIEW_CARDS, JsonArray().apply { entries.forEach(::add) })
    }

    private fun parse(payload: JsonObject) = requireNotNull(AgentConversations.parse(payload))
    private fun ids(list: List<AgentConversations.CardConversation>) = list.map { it.record.conversationId }

    private val c1 = "11111111-1111-4111-8111-111111111111"
    private val c2 = "22222222-2222-4222-8222-222222222222"
    private val c3 = "33333333-3333-4333-8333-333333333333"
    private val c4 = "44444444-4444-4444-8444-444444444444"
    private val c5 = "55555555-5555-4555-8555-555555555555"

    @Test fun listHasTheCardsLiveEntriesNewestEntryFirst() {
        val older = record(c1, listOf(entry(cardA, "2026-10-25T09:00:00.000Z")), lastActive = "2026-10-26T23:00:00.000Z")
        val newer = record(c2, listOf(entry(cardB, "2026-10-26T15:00:00.000Z"), entry(cardA, "2026-10-26T09:00:00.000Z")))
        val removedEntry = record(c3, listOf(entry(cardA, "2026-10-26T20:00:00.000Z", removed = true)))
        val otherCardOnly = record(c4, listOf(entry(cardB, "2026-10-26T21:00:00.000Z")))
        val noField = record(c5, emptyList()).apply { remove(AgentConversations.REVIEW_CARDS) }
        val list = AgentConversations.cardConversations(cardA, listOf(older, removedEntry, newer, otherCardOnly, noField), emptyList())
        // The entry's own last_active_at orders the list, not the record's.
        assertEquals(listOf(c2, c1), ids(list))
        assertEquals("2026-10-26T09:00:00.000Z", list.first().link.lastActiveAt)
        assertEquals(cardA, list.first().link.cardId)
        assertEquals(listOf(c4, c2), ids(AgentConversations.cardConversations(cardB, listOf(older, newer, otherCardOnly), emptyList())))
        assertTrue(AgentConversations.cardConversations("99999999-9999-4999-8999-999999999999", listOf(older, newer), emptyList()).isEmpty())
    }

    @Test fun tiesFallBackToTheRecordsActivityThenLinkId() {
        val same = "2026-10-26T09:00:00.000Z"
        val quiet = record(c1, listOf(entry(cardA, same)), lastActive = "2026-10-26T10:00:00.000Z")
        val busy = record(c2, listOf(entry(cardA, same)), lastActive = "2026-10-26T12:00:00.000Z")
        val tieX = record(c3, listOf(entry(cardA, same)), lastActive = "2026-10-26T08:00:00.000Z")
        val tieY = record(c4, listOf(entry(cardA, same)), lastActive = "2026-10-26T08:00:00.000Z")
        val list = AgentConversations.cardConversations(cardA, listOf(tieY, quiet, tieX, busy), emptyList())
        val ties = listOf(tieX, tieY).map { it.get("link_id").asString }.sorted()
        assertEquals(listOf(busy, quiet).map { it.get("link_id").asString } + ties, list.map { it.record.linkId })
    }

    @Test fun projectRemovalDoesNotHideItButDeletionDoes() {
        val projectRemoved = record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z")), removed = true)
        val deleted = record(c2, listOf(entry(cardA, "2026-10-26T10:00:00.000Z")), deleted = true)
        assertEquals(listOf(c1), ids(AgentConversations.cardConversations(cardA, listOf(projectRemoved, deleted), emptyList())))
        // ... while the Library's Project list still hides the Project-removed one.
        assertTrue(AgentConversations.visibleFor(concepts, listOf(projectRemoved, deleted), emptyList()).isEmpty())
    }

    @Test fun recordsOfEveryProjectAreListed() {
        // A card moved to another Project keeps its old links on the old Project's records.
        val old = record(c1, listOf(entry(cardA, "2026-10-25T09:00:00.000Z")), projectId = otherProject)
        val current = record(c2, listOf(entry(cardA, "2026-10-26T09:00:00.000Z")))
        assertEquals(listOf(c2, c1), ids(AgentConversations.cardConversations(cardA, listOf(old, current), emptyList())))
    }

    @Test fun cardIdIsMatchedCaseInsensitively() {
        val stored = record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z")))
        assertEquals(listOf(c1), ids(AgentConversations.cardConversations(cardA.uppercase(), listOf(stored), emptyList())))
    }

    @Test fun pendingWritesOverrideProjections() {
        val server = record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z")))
        val pendingRemoval = AgentConversations.cardRemovedRecord(parse(server), cardA, "phone", Instant.parse("2026-10-26T12:00:00Z"))!!
        assertTrue(AgentConversations.cardConversations(cardA, listOf(server), listOf(pendingRemoval)).isEmpty())
        // The newest server projection of a link id wins among projections.
        val serverOld = record(c2, listOf(entry(cardA, "2026-10-26T09:00:00.000Z", removed = true)), revision = 2)
        val serverNew = record(c2, listOf(entry(cardA, "2026-10-26T09:00:00.000Z")), revision = 3)
        assertEquals(listOf(c2), ids(AgentConversations.cardConversations(cardA, listOf(serverNew, serverOld), emptyList())))
        // A pending write replaces the projection even when it carries the card while the server does not.
        val serverWithout = record(c3, emptyList(), revision = 5)
        val pendingWith = parse(record(c3, listOf(entry(cardA, "2026-10-26T09:00:00.000Z")), revision = 4))
        assertEquals(listOf(c3), ids(AgentConversations.cardConversations(cardA, listOf(serverWithout), listOf(pendingWith))))
    }

    @Test fun malformedEntriesAreIgnoredAndTheRestStillCounts() {
        val entries = JsonArray().apply {
            add("not an object")
            add(JsonNull.INSTANCE)
            add(entry(cardA.uppercase(), "2026-10-26T20:00:00.000Z"))                // not lowercase
            add(entry("not-a-card", "2026-10-26T20:00:00.000Z"))
            add(entry(cardA, "yesterday"))                                          // bad last_active_at
            add(entry(cardA, "2026-10-26T20:00:00.000Z", linkedAt = "soon"))        // bad linked_at
            add(entry(cardA, "2026-10-26T20:00:00.000Z").apply { addProperty("removed", "true") })
            add(entry(cardA, "2026-10-26T20:00:00.000Z").apply { remove("card_id") })
            add(entry(cardA, "2026-10-26T08:00:00.000Z").apply { addProperty("future", 1) })  // the valid one
        }
        val stored = record(c1, emptyList()).apply { add(AgentConversations.REVIEW_CARDS, entries) }
        val list = AgentConversations.cardConversations(cardA, listOf(stored), emptyList())
        assertEquals(listOf(c1), ids(list))
        assertEquals("2026-10-26T08:00:00.000Z", list.single().link.lastActiveAt)
        // Not an array at all: the record still parses and simply lists no card.
        val notArray = record(c2, emptyList()).apply { addProperty(AgentConversations.REVIEW_CARDS, "oops") }
        assertNotNull(AgentConversations.parse(notArray))
        assertTrue(AgentConversations.cardConversations(cardA, listOf(notArray), emptyList()).isEmpty())
        // A missing `removed` reads as live; a duplicate entry for the same card: the first counts.
        val noFlag = record(c3, listOf(entry(cardA, "2026-10-26T08:00:00.000Z").apply { remove("removed") }))
        assertEquals(listOf(c3), ids(AgentConversations.cardConversations(cardA, listOf(noFlag), emptyList())))
        val duplicate = record(c4, listOf(entry(cardA, "2026-10-26T08:00:00.000Z", removed = true), entry(cardA, "2026-10-26T09:00:00.000Z")))
        assertTrue(AgentConversations.cardConversations(cardA, listOf(duplicate), emptyList()).isEmpty())
    }

    @Test fun removeFromCardFlagsOnlyThatEntryAndKeepsEverythingElse() {
        val entries = listOf(
            entry(cardB, "2026-10-26T15:00:00.000Z", linkedBy = "tool").apply { addProperty("future_entry_field", "kept") },
            entry(cardA, "2026-10-26T09:00:00.000Z").apply { addProperty("note", "kept too") },
        )
        val payload = record(c1, entries).apply {
            addProperty("future_field", "kept")
            get(AgentConversations.REVIEW_CARDS).asJsonArray.add("malformed entry stays")
        }
        val stored = parse(payload)
        val now = Instant.parse("2026-10-26T12:00:00.123456Z")
        val removed = requireNotNull(AgentConversations.cardRemovedRecord(stored, cardA, "phone-origin", now))
        assertNull(AgentConversations.validationError(removed.payload))
        assertEquals(4L, removed.revision)
        assertEquals("2026-10-26T12:00:00.123Z", removed.updatedAt)
        assertEquals("phone-origin", removed.originId)
        // Project-level fields are untouched.
        assertFalse(removed.removed)
        assertTrue(removed.payload.get("removed_at").isJsonNull)
        assertEquals("2026-10-26T11:40:02.004Z", removed.lastActiveAt)
        assertEquals("kept", removed.payload.get("future_field").asString)
        // Only the card's entry changes; its other fields and the other entries stay.
        val after = removed.payload.getAsJsonArray(AgentConversations.REVIEW_CARDS)
        val before = payload.getAsJsonArray(AgentConversations.REVIEW_CARDS)
        assertEquals(before[0], after[0])
        assertEquals(before[2], after[2])
        val expected = before[1].asJsonObject.deepCopy().apply { addProperty("removed", true) }
        assertEquals(expected, after[1])
        // Everything outside the changed fields is identical.
        val normalized = removed.payload.deepCopy().apply {
            add("revision", payload.get("revision")); add("updated_at", payload.get("updated_at"))
            add("origin_id", payload.get("origin_id")); add(AgentConversations.REVIEW_CARDS, before)
        }
        assertEquals(payload, normalized)
        // The stored record is not modified.
        assertFalse(AgentConversations.cardLink(stored, cardA)!!.removed)
        assertTrue(AgentConversations.cardConversations(cardA, emptyList(), listOf(removed)).isEmpty())
        assertEquals(1, AgentConversations.cardConversations(cardB, emptyList(), listOf(removed)).size)
    }

    @Test fun removeFromCardKeepsUpdatedAtStrictlyIncreasing() {
        val stored = parse(record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z"))))
        val behind = AgentConversations.cardRemovedRecord(stored, cardA, "phone", Instant.parse("2026-10-26T11:00:00Z"))!!
        assertEquals("2026-10-26T11:40:02.005Z", behind.updatedAt)
        val sameMillisecond = AgentConversations.cardRemovedRecord(stored, cardA, "phone", Instant.parse("2026-10-26T11:40:02.004900Z"))!!
        assertEquals("2026-10-26T11:40:02.005Z", sameMillisecond.updatedAt)
    }

    @Test fun removeFromCardOfAProjectRemovedRecordKeepsTheProjectRemoval() {
        val stored = parse(record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z")), removed = true))
        val removed = AgentConversations.cardRemovedRecord(stored, cardA, "phone", Instant.parse("2026-10-26T12:00:00Z"))!!
        assertNull(AgentConversations.validationError(removed.payload))
        assertTrue(removed.removed)
        assertEquals(stored.payload.get("removed_at"), removed.payload.get("removed_at"))
    }

    @Test fun removeFromCardFlagsEveryDuplicateEntry() {
        val stored = parse(record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z"), entry(cardA, "2026-10-26T10:00:00.000Z"))))
        val removed = AgentConversations.cardRemovedRecord(stored, cardA, "phone")!!
        assertTrue(removed.payload.getAsJsonArray(AgentConversations.REVIEW_CARDS).all { it.asJsonObject.get("removed").asBoolean })
    }

    @Test fun removeFromCardIsANoOpWithoutALiveEntry() {
        val stored = parse(record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z", removed = true))))
        assertNull(AgentConversations.cardRemovedRecord(stored, cardA, "phone"))
        assertNull(AgentConversations.cardRemovedRecord(stored, cardB, "phone"))
        assertSame(stored, AgentConversations.removeFromCard(stored, cardA, "phone"))
        assertTrue(AgentConversations.pending().isEmpty())
    }

    @Test fun removeFromCardIsStoredPendingAndBuildsOnEarlierPendingWrites() {
        val server = record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z"), entry(cardB, "2026-10-26T10:00:00.000Z")))
        val first = AgentConversations.removeFromCard(parse(server), cardA, "phone", Instant.parse("2026-10-26T12:00:00Z"))
        assertEquals(listOf(first), AgentConversations.pending())
        val upload = AgentConversations.pendingUploads().single()
        assertEquals(3L, upload.baseRevision)
        assertEquals(first.payload, upload.payload)
        // Removing it again from the same card changes nothing and keeps the operation id.
        assertEquals(first, AgentConversations.removeFromCard(parse(server), cardA, "phone", Instant.parse("2026-10-26T12:05:00Z")))
        assertEquals(upload.operationId, AgentConversations.pendingUploads().single().operationId)
        // Removing it from a second card builds on the pending write: both entries removed, revision 5.
        val second = AgentConversations.removeFromCard(parse(server), cardB, "phone", Instant.parse("2026-10-26T12:10:00Z"))
        assertEquals(5L, second.revision)
        assertTrue(AgentConversations.cardLinks(second).all { it.removed })
        assertEquals(listOf(second), AgentConversations.pending())
        // A Project removal afterwards keeps the card removals.
        val project = AgentConversations.remove(parse(server), "phone", Instant.parse("2026-10-26T12:20:00Z"))
        assertEquals(6L, project.revision)
        assertTrue(project.removed)
        assertTrue(AgentConversations.cardLinks(project).all { it.removed })
        // The pending card removal settles like a Project removal once the server holds it.
        AgentConversations.installServer(listOf(project.payload.deepCopy()))
        assertTrue(AgentConversations.pending().isEmpty())
    }

    @Test fun removeFromCardRefusesARecordThatWouldNotValidate() {
        val broken = parse(record(c1, listOf(entry(cardA, "2026-10-26T09:00:00.000Z"))).apply { addProperty("title", "") })
        assertNotNull(runCatching { AgentConversations.removeFromCard(broken, cardA, "phone") }.exceptionOrNull())
        assertTrue(AgentConversations.pending().isEmpty())
    }
}
