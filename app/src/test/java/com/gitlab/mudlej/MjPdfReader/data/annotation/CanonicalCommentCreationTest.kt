// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CanonicalCommentCreationTest {
    private fun raw() = JsonParser.parseString("""{"id":"root","page":4,"rect":[100,300,120,280],"pdf_sha256":"${"a".repeat(64)}","contents":"Original","updated_at":"2026-09-10T10:00:00Z","deleted":false}""").asJsonObject

    @Test fun saveKeepsOriginBeforeLinkingAndRetryDoesNotCreateAnotherRevision() {
        val raw = raw()
        val saved = canonicalCommentForAnnotation(raw, "notes", null, null)!!
        assertEquals(1, saved.get("revision").asInt)
        assertEquals(3, saved.getAsJsonObject("origin").get("page_index").asInt)
        assertEquals(raw.get("rect"), saved.getAsJsonObject("origin").getAsJsonObject("raw_pdf_geometry").get("rect"))
        raw.addProperty("page", 12)
        assertNull(canonicalCommentForAnnotation(raw, "notes", saved, null))
        raw.addProperty("contents", "Edited offline")
        val edit = canonicalCommentForAnnotation(raw, "notes", saved, null)!!
        assertEquals(2, edit.get("revision").asInt)
        assertEquals(saved.get("origin"), edit.get("origin"))
    }

    @Test fun deletionPreservesCanonicalTextAndSourceLossIsNotDeletion() {
        val raw = raw()
        val saved = canonicalCommentForAnnotation(raw, "notes", null, null)!!
        raw.addProperty("contents", "Stale placement text")
        raw.addProperty("deleted", true)
        val deletion = canonicalCommentForAnnotation(raw, "notes", saved, null)!!
        assertEquals("Original", deletion.get("contents").asString)
        assertTrue(deletion.get("deleted").asBoolean)
        raw.addProperty("source_anchor_deletion", true)
        assertNull(canonicalCommentForAnnotation(raw, "notes", saved, null))
    }

    @Test fun explicitDeletionNamesItselfAndUndoClearsTheReason() {
        val saved = canonicalCommentForAnnotation(raw(), "notes", null, null)!!
        val id = saved.get("comment_id").asString
        val deleted = logicalCommentMutationPayload(saved, id, deleted = true)!!
        assertTrue(deleted.get("deleted").asBoolean)
        assertEquals(USER_DELETION_REASON, deleted.get("deletion_reason").asString)
        val restored = logicalCommentMutationPayload(deleted, id, deleted = false)!!
        assertTrue(!restored.has("deletion_reason"))
    }

    @Test fun staleLegacyContentCannotUndoCanonicalDeletion() {
        val raw = raw()
        val deleted = canonicalCommentForAnnotation(raw, "notes", null, null)!!.apply {
            addProperty("deleted", true); addProperty("revision", 7)
        }
        raw.addProperty("revision", 99)
        assertNull(canonicalCommentForAnnotation(raw, "notes", deleted, null))
    }

    @Test fun reverseOrderedThreadKeepsItsRootAndOrigin() {
        val root = raw()
        val reply = raw().apply { addProperty("id", "reply"); addProperty("in_reply_to", "root") }
        val ordered = orderCommentCreations(listOf(reply, root), emptySet(), { it.get("id").asString }, { it.get("in_reply_to")?.asString.orEmpty() })
        assertEquals(listOf("root", "reply"), ordered.map { it.get("id").asString })
        val saved = canonicalCommentForAnnotation(root, "notes", null, null)!!
        val child = canonicalCommentForAnnotation(reply, "notes", null, saved)!!
        assertEquals("root", child.get("thread_root_id").asString)
        assertEquals(saved.get("origin"), child.get("origin"))
    }

    @Test(expected = IllegalStateException::class) fun missingParentRetainsJournalInsteadOfDetachingReply() {
        orderCommentCreations(listOf("reply"), emptySet(), { it }, { "missing" })
    }
}
