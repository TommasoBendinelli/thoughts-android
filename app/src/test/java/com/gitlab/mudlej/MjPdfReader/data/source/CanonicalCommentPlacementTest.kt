// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CanonicalCommentPlacementTest {
    private fun payload(revision: Int, deleted: Boolean = false) = JsonParser.parseString("""
        {"schema_version":1,"comment_id":"root","thread_root_id":"root","revision":$revision,
         "deleted":$deleted,"scope":{"state":"source_linked","anchor_id":"anchor"},
         "origin":{"collection":"paper","page_index":0},
         "locations":[{"pdf_filename":"stale.pdf","page":803}]}
    """).asJsonObject

    @Test fun commentPayloadCannotSupplyOrReplacePdfPlacement() {
        val snapshot = OfflineOverviewSourceSnapshot().withLogicalPayloads(listOf(payload(1)))
        assertTrue(snapshot.logicalComments.single().locations.isEmpty())
        assertFalse(snapshot.logicalComments.single().payloadJson.contains("locations"))
        val location = LogicalCommentPdfLocation("paper.pdf", "a".repeat(64), 6, "exact", point = listOf(.2f, .3f))
        val proof = ReviewCorrespondence("comment:root", "comment", "root", "root", "active", "exact_point", "", "",
            emptyList(), listOf(location))
        val placed = snapshot.copy(logicalComments = listOf(snapshot.logicalComments.single().copy(correspondence = proof)))
        val edited = placed.withLogicalPayloads(listOf(payload(2)))
        assertEquals(listOf(location), edited.logicalComments.single().locations)
        assertSame(proof, edited.logicalComments.single().correspondence)
        val deleted = edited.withLogicalPayloads(listOf(payload(3, true)))
        assertTrue(deleted.logicalComments.single().deleted)
    }
}
