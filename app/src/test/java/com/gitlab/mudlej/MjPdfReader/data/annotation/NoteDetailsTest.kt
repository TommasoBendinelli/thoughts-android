// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import org.junit.Assert.*
import org.junit.Test

class NoteDetailsTest {
    @Test fun noteContextSurvivesTextChangesDeletionAndRestore() {
        val details = NoteDetails("source:docs-research", "Research", "During the weekly meeting, or at home.", "2026-10-02", "09:30", "Europe/Paris")
        val original = details.applyTo(newNotePayload("note", "Initial text.", "2026-09-11T12:00:00Z"))
        val edited = logicalCommentMutationPayload(original, "note", false, contents = "Edited text.")!!
        val deleted = logicalCommentMutationPayload(edited, "note", true)!!
        val restored = logicalCommentMutationPayload(deleted, "note", false)!!
        for (payload in listOf(original, edited, deleted, restored)) {
            assertEquals(details.copy(projectId = "", projectName = ""), NoteDetails.fromJson(payload))
            assertTrue(payload.get("project").isJsonNull)
            assertFalse(payload.has("project_name"))
            assertTrue(payload.get("origin").isJsonNull)
            assertEquals("standalone", payload.getAsJsonObject("scope").get("state").asString)
        }
        assertEquals("Edited text.", restored.get("contents").asString)
    }

    @Test fun oldNotesHaveEmptyDetailsAndAssignmentCanBeCleared() {
        val original = newNotePayload("note", "Text.", "2026-09-11T12:00:00Z")
        assertEquals(NoteDetails(), NoteDetails.fromJson(original))
        NoteDetails("source:docs-research", "Research", "Anywhere").applyTo(original)
        NoteDetails().applyTo(original)
        assertEquals(NoteDetails(), NoteDetails.fromJson(original))
        assertTrue(original.get("project").isJsonNull)
    }

    @Test fun visibleFromSurvivesEditsAndHidesOnlyBeforeItsDate() {
        val details = NoteDetails(checkWhere = "Home", visibleFrom = "2026-10-25")
        val original = details.applyTo(newNotePayload("note", "Call the podologist.", "2026-10-21T12:00:00Z"))
        assertEquals("2026-10-25", original.get("visible_from").asString)
        val edited = logicalCommentMutationPayload(original, "note", false, contents = "Call the podologist today.")!!
        assertEquals(details, NoteDetails.fromJson(edited))
        fun day(text: String) = java.time.LocalDate.parse(text)
        assertTrue(details.hiddenOn(day("2026-10-24")))
        assertFalse(details.hiddenOn(day("2026-10-25")))
        assertFalse(details.hiddenOn(day("2026-11-01")))
        assertFalse(NoteDetails().hiddenOn(day("2026-10-24")))
        assertFalse(NoteDetails(visibleFrom = "garbage").hiddenOn(day("2026-10-24")))
        details.copy(visibleFrom = "").applyTo(edited)
        assertEquals("", NoteDetails.fromJson(edited).visibleFrom)
    }
}
