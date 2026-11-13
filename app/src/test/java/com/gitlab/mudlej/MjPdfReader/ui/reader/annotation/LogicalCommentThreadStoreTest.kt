// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.annotation

import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.google.gson.JsonObject
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads

class LogicalCommentThreadStoreTest {

    @Test
    fun logicalMarkerFallsBackToCanonicalThreadWhenNoPdfAnnotationExists() {
        assertEquals(
            LogicalCommentMarkerOpenTarget.LOGICAL_THREAD,
            logicalCommentMarkerOpenTarget("comment-1", pdfThreadOpened = false),
        )
        assertEquals(
            LogicalCommentMarkerOpenTarget.PDF_THREAD,
            logicalCommentMarkerOpenTarget("comment-1", pdfThreadOpened = true),
        )
        assertEquals(
            LogicalCommentMarkerOpenTarget.UNAVAILABLE,
            logicalCommentMarkerOpenTarget(null, pdfThreadOpened = false),
        )
    }

    @Test
    fun canonicalPayloadPopulatesTheSharedCommentEditor() {
        val payload = payload(
            id = "comment-1",
            contents = "Visible comment",
            extra = """
                "priority": "high",
                "color": [1, 0.5, 0, 1],
                "location": {
                  "label": "Office",
                  "latitude": 46.0,
                  "longitude": 7.0
                },
            """.trimIndent(),
        )

        val value = requireNotNull(logicalCommentEditorValue(payload))

        assertEquals("Visible comment", value.text)
        assertEquals(CommentPriority.HIGH, value.priority)
        assertEquals("Office", value.location?.label)
        assertEquals(0xFFFF7F00.toInt(), value.color)
    }

    /** A place can only be kept or removed on the phone: an edit must hand it on exactly as it was stored. */
    @Test
    fun editingACommentKeepsItsPlaceExactlyAsStored() {
        val storedPlace = """{"label":"Bibliothèque François-Mitterrand","latitude":48.8335,"longitude":2.3758,""" +
            """"google_place_id":"place-id","source":"google_maps_pin","accuracy_meters":12.5,""" +
            """"confirmed_at":"2026-07-20T10:59:00Z","added_by_a_later_version":{"kept":true}}"""
        val payload = payload(id = "comment-1", contents = "Meet here", extra = """"location": $storedPlace,""")
        assertEquals(storedPlace, payload.get("location").toString())

        // What the comment editor does: open the stored comment, change the text, save.
        val opened = requireNotNull(logicalCommentEditorValue(payload))
        assertEquals("Bibliothèque François-Mitterrand", opened.location?.label)
        val edited = requireNotNull(
            com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentMutationPayload(
                payload, "comment-1", deleted = false, contents = "Meet here at ten", color = opened.color,
                priority = opened.priority, location = opened.location, replaceLocation = true,
            ),
        )

        assertEquals("Meet here at ten", edited.get("contents").asString)
        assertEquals(2L, edited.get("revision").asLong)
        // Byte for byte, including the field this version does not know.
        assertEquals(storedPlace, edited.get("location").toString())
        // The edited comment opens with the same place again.
        assertEquals(opened.location, requireNotNull(logicalCommentEditorValue(edited)).location)

        // A place without the optional fields is not filled in with defaults either.
        val bare = payload("comment-2", "Bare", extra = """"location": {"label":"Office","latitude":46.0,"longitude":7.0},""")
        val bareOpened = requireNotNull(logicalCommentEditorValue(bare))
        val bareEdited = requireNotNull(
            com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentMutationPayload(
                bare, "comment-2", deleted = false, contents = "Bare, edited", location = bareOpened.location, replaceLocation = true,
            ),
        )
        assertEquals("""{"label":"Office","latitude":46.0,"longitude":7.0}""", bareEdited.get("location").toString())

        // "Remove location" in the editor is the one change left, and it still works.
        val removed = requireNotNull(
            com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentMutationPayload(
                payload, "comment-1", deleted = false, contents = "Meet here", location = null, replaceLocation = true,
            ),
        )
        assertTrue(removed.get("location").isJsonNull)
        // An edit that does not touch the place at all (a reply's text, a deletion) leaves it alone too.
        val untouched = requireNotNull(
            com.gitlab.mudlej.MjPdfReader.data.annotation.logicalCommentMutationPayload(payload, "comment-1", deleted = false, contents = "x"),
        )
        assertEquals(storedPlace, untouched.get("location").toString())
    }

    @Test
    fun activePdfSnapshotContainsRootAndRepliesButNotOtherDocuments() {
        val activeHash = "a".repeat(64)
        val comments = listOf(
            logicalComment(
                id = "root",
                contents = "Root",
                locations = listOf(exactLocation("paper.pdf", activeHash, page = 4)),
            ),
            logicalComment(
                id = "reply",
                rootId = "root",
                parentId = "root",
                contents = "Reply",
            ),
            logicalComment(
                id = "other",
                contents = "Other PDF",
                locations = listOf(exactLocation("other.pdf", "b".repeat(64), page = 1)),
            ),
        )

        val snapshot = requireNotNull(
            logicalCommentSheetSnapshot(comments, "root", "paper.pdf", activeHash),
        )

        assertEquals(1, snapshot.threads.size)
        assertEquals(0, snapshot.selectedThreadIndex)
        assertEquals("root", snapshot.threads.single().root.id)
        assertEquals(listOf("reply"), snapshot.threads.single().replies.map { it.id })
        assertEquals(3, snapshot.threads.single().pageIndex)
    }

    @Test
    fun logicalReplyInheritsThreadIdentityAndOriginWithoutCopyingLocations() {
        val root = payload("root", "Root")

        val reply = requireNotNull(
            logicalCommentReplyPayload(
                root,
                replyId = "reply",
                contents = "Reply text",
                author = "Alex",
                timestamp = "2026-09-01T10:00:00Z",
            ),
        )

        assertEquals("reply", reply.get("comment_id").asString)
        assertEquals("root", reply.get("thread_root_id").asString)
        assertEquals("root", reply.get("parent_id").asString)
        assertEquals("Reply text", reply.get("contents").asString)
        assertEquals("manual", reply.getAsJsonObject("origin").get("collection").asString)
        assertEquals(
            "inherits_thread_locations",
            reply.getAsJsonObject("scope").get("reason").asString,
        )
        assertFalse(reply.has("locations"))
        assertFalse(reply.has("source_file"))
    }

    @Test
    fun sourceCreatedRootWithoutPdfOriginCanReceiveReplies() {
        val root = payload("root", "Source comment").apply { add("origin", com.google.gson.JsonNull.INSTANCE) }
        val reply = requireNotNull(logicalCommentReplyPayload(root, "reply", "Reply", "Alex", "2026-09-07T18:00:00Z"))
        assertEquals("root", reply.get("thread_root_id").asString)
        assertTrue(reply.get("origin").isJsonNull)
        assertFalse(reply.has("locations"))
    }

    @Test
    fun deletedLogicalCommentCannotBeOpenedForEditing() {
        val payload = payload("comment-1", "Gone").apply {
            addProperty("deleted", true)
        }

        assertNull(logicalCommentEditorValue(payload))
    }

    @Test
    fun snapshotRejectsACommentWhoseExactLocationBelongsToAnotherRevision() {
        val comment = logicalComment(
            id = "root",
            contents = "Root",
            locations = listOf(exactLocation("paper.pdf", "b".repeat(64), page = 1)),
        )

        assertNull(
            logicalCommentSheetSnapshot(
                listOf(comment),
                "root",
                "paper.pdf",
                "a".repeat(64),
            ),
        )
    }

    @Test fun deletingRenderedCommentHidesRootAndRepliesAfterProjectionReload() = runBlocking {
        val root = logicalComment("root", contents = "Source root", locations = listOf(exactLocation("paper.pdf", "a".repeat(64), 1)))
        val reply = logicalComment("reply", rootId = "root", parentId = "root", contents = "Reply")
        val records = listOf(root, reply).associate { comment -> comment.id to org.tbend.thoughts.sdk.CommentRecord(
            comment.id, comment.parentId.orEmpty(), com.google.gson.JsonParser.parseString(comment.payloadJson).asJsonObject,
        ) }.toMutableMap()
        val service = org.tbend.thoughts.sdk.CommentService(
            { records.values.toList() }, { changes -> changes.forEach { records[it.after.id] = it.after } },
            { "2026-09-12T12:00:00Z" }, "test",
        )
        val result = service.deleteComments(listOf("root"))
        assertTrue(result.success)
        assertEquals(setOf("root", "reply"), result.ids.toSet())
        // Explicit deletes name themselves, as the app's own delete paths do.
        assertEquals(com.gitlab.mudlej.MjPdfReader.data.annotation.USER_DELETION_REASON, org.tbend.thoughts.sdk.CommentService.USER_DELETION_REASON)
        assertTrue(records.values.all { it.payload.get("deletion_reason")?.asString == "user_deleted" })
        val reloaded = OfflineOverviewSourceSnapshot(logicalComments = listOf(root, reply)).withLogicalPayloads(records.values.map { it.payload })
        assertNull(logicalCommentSheetSnapshot(reloaded.logicalComments, "root", "paper.pdf", "a".repeat(64)))
        assertNull(logicalCommentSheetSnapshot(reloaded.logicalComments, "reply", "paper.pdf", "a".repeat(64)))
        assertTrue(logicalCommentOverlaySpecs(reloaded.logicalComments, "paper.pdf", "a".repeat(64)).isEmpty())
        assertTrue(service.undo(result.historyToken).success)
        assertTrue(records.values.none { it.payload.has("deletion_reason") })
        val restored = OfflineOverviewSourceSnapshot(logicalComments = listOf(root, reply)).withLogicalPayloads(records.values.map { it.payload })
        assertNotNull(logicalCommentSheetSnapshot(restored.logicalComments, "root", "paper.pdf", "a".repeat(64)))
    }

    @Test fun anchorRemovedThreadsStayOutOfTheReaderOrderUnlessOpened() {
        val location = listOf(exactLocation("paper.pdf", "a".repeat(64), 1))
        val normal = logicalComment("normal", contents = "Linked", locations = location)
        val scope = JsonParser.parseString("""{"state":"needs_attention","reason":"anchor_removed","anchor_id":"removed"}""")
        val removed = logicalComment("removed", contents = "Removed", locations = location).let { comment ->
            comment.copy(scopeState = "needs_attention", payloadJson = JsonParser.parseString(comment.payloadJson).asJsonObject
                .apply { add("scope", scope) }.toString())
        }
        val ordered = requireNotNull(logicalCommentSheetSnapshot(listOf(normal, removed), "normal", "paper.pdf", "a".repeat(64)))
        assertEquals(listOf("normal"), ordered.threads.map { it.root.id })
        val opened = requireNotNull(logicalCommentSheetSnapshot(listOf(normal, removed), "removed", "paper.pdf", "a".repeat(64)))
        assertEquals(setOf("normal", "removed"), opened.threads.map { it.root.id }.toSet())
        assertEquals("removed", opened.threads[opened.selectedThreadIndex].root.id)
    }

    private fun logicalComment(
        id: String,
        rootId: String = id,
        parentId: String? = null,
        contents: String,
        locations: List<LogicalCommentPdfLocation> = emptyList(),
    ) = OfflineLogicalComment(
        id = id,
        threadRootId = rootId,
        parentId = parentId,
        revision = 1,
        deleted = false,
        scopeState = "pdf_only",
        sourceFile = "",
        sourceLine = 0,
        originCollection = "manual",
        originPageIndex = 0,
        payloadJson = payload(id, contents, rootId, parentId).toString(),
        correspondence = placement(rootId, locations),
    )

    private fun exactLocation(
        filename: String,
        sha256: String,
        page: Int,
    ) = LogicalCommentPdfLocation(
        pdfFilename = filename,
        pdfSha256 = sha256,
        page = page,
        status = "exact",
        point = listOf(0.25f, 0.5f),
    )

    private fun payload(
        id: String,
        contents: String,
        rootId: String = id,
        parentId: String? = null,
        extra: String = "",
    ) = JsonParser.parseString(
        """
            {
              "schema_version": 1,
              "comment_id": "$id",
              "thread_root_id": "$rootId",
              "parent_id": ${parentId?.let { "\"$it\"" } ?: "null"},
              "revision": 1,
              "deleted": false,
              "contents": "$contents",
              "author": "Alex",
              "created_at": "2026-09-01T09:00:00Z",
              "updated_at": "2026-09-01T09:00:00Z",
              $extra
              "origin": {
                "collection": "manual",
                "document_id": "manual:paper",
                "pdf_sha256": "${"a".repeat(64)}",
                "page_index": 3,
                "rect": [],
                "fragments": []
              },
              "scope": {
                "state": "pdf_only",
                "reason": "awaiting_unique_source_anchor"
              }
            }
        """.trimIndent(),
    ).asJsonObject
}

private fun placement(id: String, locations: List<com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation>) =
    com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence(
        "comment:$id", "comment", id, id, "active", "source_linked", "", "", emptyList(), locations)
