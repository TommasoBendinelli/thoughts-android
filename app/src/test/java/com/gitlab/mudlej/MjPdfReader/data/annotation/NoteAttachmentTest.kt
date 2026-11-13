// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class NoteAttachmentTest {
    private val item = NoteAttachment("picture-1", "Photo.jpg", "image/jpeg", "a".repeat(64), 123)
    @Test fun portableReferencesSurviveTextEdits() {
        val payload = newNotePayload("note", "", "2026-09-16T12:00:00Z", listOf(item))
        assertEquals(listOf(item), NoteAttachment.read(payload))
        val edited = requireNotNull(logicalCommentMutationPayload(payload, "note", false, "Caption"))
        assertEquals(listOf(item), NoteAttachment.read(edited))
        assertEquals("Caption", edited.get("contents").asString)
    }
    @Test fun legacyNotesHaveNoAttachments() { assertTrue(NoteAttachment.read(JsonObject()).isEmpty()) }
    @Test fun unsafeAndOversizedReferencesAreRejected() {
        listOf({ item.copy(name = "../photo.jpg") }, { item.copy(sha256 = "../../etc/passwd") },
            { item.copy(size = NoteAttachment.MAX_BYTES + 1) }, { item.copy(mimeType = "image/jpeg\nHeader: x") })
            .forEach { invalid -> assertTrue(runCatching { invalid() }.isFailure) }
    }
}
