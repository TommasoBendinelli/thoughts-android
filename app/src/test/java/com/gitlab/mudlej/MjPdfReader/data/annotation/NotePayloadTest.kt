// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.gitlab.mudlej.MjPdfReader.ui.home.availableOverviewDocuments
import org.junit.Assert.*
import org.junit.Test

class NotePayloadTest {
    @Test fun legacyProjectAssociationIsRemovedWithoutLosingNoteData() {
        val original = newNotePayload("legacy-note", "Keep this text", "2026-09-09T12:00:00Z").apply {
            addProperty("project", "source:old-project")
            addProperty("project_name", "Old project")
            addProperty("calendar_date", "2026-10-02")
        }
        val normalized = restoreNoteNulls(original)
        assertTrue(normalized.get("project").isJsonNull)
        assertFalse(normalized.has("project_name"))
        assertEquals("Keep this text", normalized.get("contents").asString)
        assertEquals("2026-10-02", normalized.get("calendar_date").asString)
        assertEquals(original.get("revision"), normalized.get("revision"))
        assertEquals("source:old-project", original.get("project").asString)
    }

    @Test fun legacyNoteWithoutNullFieldsCanBeEditedAgain() {
        val original = newNotePayload("note-1", "Original.", "2026-09-09T12:00:00Z")
        val stored = com.google.gson.JsonParser.parseString(com.google.gson.Gson().toJson(original)).asJsonObject
        assertFalse(stored.has("origin"))
        val recovered = restoreNoteNulls(stored)
        assertTrue(recovered.get("origin").isJsonNull)
        assertTrue(recovered.get("project").isJsonNull)
        val edited = logicalCommentMutationPayload(recovered, "note-1", false, contents = "Edited.")!!
        assertTrue(edited.get("origin").isJsonNull)
        assertEquals(2, edited.get("revision").asInt)
    }


    @Test fun noteIsVisibleWithoutAnyDocumentsAndNeverPlacedInAPdf() {
        val payload = newNotePayload("note-1", "An independent idea.", "2026-09-08T12:00:00Z")
        assertTrue(payload.get("origin").isJsonNull)
        assertTrue(payload.get("project").isJsonNull)
        val note = OfflineLogicalComment(
            id = "note-1", threadRootId = "note-1", parentId = null,
            revision = 1, deleted = false, scopeState = "standalone",
            sourceFile = "", sourceLine = 0, originCollection = "", originPageIndex = -1,
            payloadJson = payload.toString(),
        )
        val global = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(), listOf(note))
        val visible = availableOverviewDocuments(global.documents, emptyMap()).single().comments.single()
        assertTrue(visible.isNote)
        assertEquals("", visible.documentStem)
        assertEquals(-1, visible.pageIndex)
        val pdf = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(), listOf(note), "manual.pdf", "a".repeat(64))
        assertTrue(pdf.documents.isEmpty())
        val deleted = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(),
            listOf(note.copy(deleted = true)))
        assertTrue(deleted.documents.flatMap { it.comments }.isEmpty())
        val edited = logicalCommentMutationPayload(payload, "note-1", false, contents = "Corrected.")!!
        assertEquals(2, edited.get("revision").asInt)
        assertEquals("Note", edited.get("subtype").asString)
        assertTrue(edited.get("origin").isJsonNull)
        assertTrue(logicalCommentMutationPayload(edited, "note-1", true)!!.get("deleted").asBoolean)
    }
}
