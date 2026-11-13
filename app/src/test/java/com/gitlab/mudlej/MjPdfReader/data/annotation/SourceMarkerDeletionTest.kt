// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceReviewComment
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.logicalCommentSheetSnapshot
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SourceMarkerDeletionTest {
    private val id = "mjpdf-2760d2c8-90d5-48f7-b38f-02d1828b8b5f"
    private fun sourceMarker() = JsonParser.parseString("""
        {"schema_version":1,"comment_id":"$id","thread_root_id":"$id",
         "parent_id":null,"revision":1,"created_at":"","updated_at":"",
         "origin_id":"source-marker-compatibility","deleted":false,
         "contents":"Old source comment","author":"","subtype":"Text",
         "origin":null,"scope":{"state":"source_linked","anchor_id":"$id",
          "source_file":"docs-template/tex/source/notes_system.tex","source_line":47},
         "locations":[{"pdf_filename":"template.pdf","pdf_sha256":"${"a".repeat(64)}",
          "page":1,"status":"exact","point":[0.5,0.6]}]}
    """).asJsonObject

    @Test fun sourceMarkerCannotCreateADeletionOrEdit() {
        assertNull(logicalCommentMutationPayload(sourceMarker(), id, true))
        assertNull(logicalCommentMutationPayload(sourceMarker(), id, false))
    }

    private fun canonical() = sourceMarker().apply {
        addProperty("origin_id", "thoughts-android")
        addProperty("created_at", "2026-09-12T12:00:00Z")
        add("origin", JsonParser.parseString("""{"collection":"template","document_id":"template-document",
            "pdf_sha256":"${"a".repeat(64)}","page_index":0,"rect":[],"fragments":[]}"""))
    }

    @Test fun missingPlacementOrMismatchedIdentityCannotGenerateDeletion() {
        assertNull(logicalCommentMutationPayload(sourceMarker(), "different", true))
        assertNull(logicalCommentMutationPayload(sourceMarker().apply { remove("locations") }, id, true))
        assertNull(logicalCommentMutationPayload(sourceMarker().apply {
            getAsJsonObject("scope").addProperty("anchor_id", "different")
        }, id, true))
        assertNull(logicalCommentMutationPayload(sourceMarker(), id, false))
    }

    @Test fun ordinaryCanonicalDeletionKeepsOriginAndAdvancesExistingRevision() {
        val canonical = canonical().apply {
            addProperty("deleted", false)
            addProperty("revision", 8)
        }
        val mutation = requireNotNull(logicalCommentMutationPayload(canonical, id, true))
        assertEquals(9, mutation["revision"].asInt)
        assertEquals(canonical["origin"], mutation["origin"])
        assertEquals(canonical["created_at"], mutation["created_at"])
    }

    @Test fun canonicalDeletionWinsOverStaleSourceRevisionWhileKeepingPlacement() {
        val source = OfflineOverviewSourceSnapshot().withLogicalPayloads(listOf(canonical().apply { addProperty("revision", 99) }))
        val deletion = requireNotNull(logicalCommentMutationPayload(canonical(), id, true))
        val current = source.withLogicalPayloads(listOf(deletion), preferPayloads = true)
        assertTrue(current.logicalComments.single().deleted)
        assertEquals(source.logicalComments.single().locations, current.logicalComments.single().locations)
    }

    @Test fun tombstoneSurvivesReloadAndSuppressesStaleSourceAndPdfFallbacks() {
        val deletion = requireNotNull(logicalCommentMutationPayload(canonical(), id, true))
        // A cold reload has only a stale source marker and the persisted sync tombstone.
        val source = OfflineSourceReviewComment(id, "Old source comment", "docs/notes.tex",
            "b".repeat(64), 47, listOf("template.pdf"))
        val snapshot = OfflineOverviewSourceSnapshot(reviewComments = listOf(source))
            .withLogicalPayloads(listOf(deletion))
        assertTrue(snapshot.logicalComments.single().deleted)
        val projected = projectDocumentComments(emptyList(), emptyList(), listOf(source),
            setOf("template.pdf"), snapshot.logicalComments)
        assertTrue(projected.documents.flatMap { it.comments }.isEmpty())
        assertNull(logicalCommentSheetSnapshot(snapshot.logicalComments, id, "template.pdf", "a".repeat(64)))
        assertTrue(snapshot.withLogicalPayloads(listOf(deletion)).logicalComments.single().deleted)
    }
}
