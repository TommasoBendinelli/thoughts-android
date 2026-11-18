// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKindCatalogue
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Project's default card kind: the Library-project action of protocol/review-card-kinds.md section 2.3. */
class LibraryProjectReviewKindTest {
    private fun project(kind: String? = "knowledge") = JsonParser.parseString("""{"id":"project-a","name":"Project A","revision":7,
        "target":"","documents":[{"document_id":"d-1","filename":"notes-a.pdf"}]${kind?.let { ""","review_kind":"$it"""" }.orEmpty()}}""").asJsonObject

    private fun values(kind: String) = JsonObject().apply { addProperty("review_kind", kind) }

    @Test fun theRequestIsTheActionTheServerTakes() {
        val request = libraryProjectChangeRequest(LibraryProjectStore.SET_REVIEW_KIND, project(), values("project"))
        assertEquals(setOf("operation_id", "project_id", "base_revision", "action", "review_kind"), request.keySet())
        UUID.fromString(request["operation_id"].asString)
        assertEquals("project-a", request["project_id"].asString)
        assertEquals(7, request["base_revision"].asInt)
        assertEquals("set_review_kind", request["action"].asString)
        assertEquals("project", request["review_kind"].asString)
    }

    @Test fun onlyAKnownKindAndOnlyAProjectWhoseCatalogueEntryHasAKind() {
        requireReviewKindChange(project(), values("project"))
        requireReviewKindChange(project("project"), values("knowledge"))
        // Setting the current value is a change of its own too (the server bumps the revision).
        requireReviewKindChange(project("project"), values("project"))
        assertTrue(runCatching { requireReviewKindChange(project(), values("follow_project")) }.isFailure)
        assertTrue(runCatching { requireReviewKindChange(project(), JsonObject()) }.isFailure)
        // An older server: no review_kind in the catalogue, so the choice is not offered or queued.
        assertTrue(runCatching { requireReviewKindChange(project(null), values("project")) }.isFailure)
    }

    @Test fun theQueuedChangeShowsTheNewKindUntilSync() {
        val shown = project()
        applyProjectReviewKind(shown, libraryProjectChangeRequest(LibraryProjectStore.SET_REVIEW_KIND, shown, values("project")))
        assertEquals("project", shown["review_kind"].asString)
        // The documents are untouched, and the cards that follow the Project are project cards at once.
        assertEquals(project()["documents"], shown["documents"])
        val catalogue = ReviewKindCatalogue(JsonArray().apply { add(shown) })
        assertTrue(catalogue.kind(null, "d-1", "notes-a.pdf").isProject)
        assertFalse(catalogue.kind("knowledge", "d-1", "notes-a.pdf").isProject)
    }
}
