// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment
import com.gitlab.mudlej.MjPdfReader.data.source.anchorRemoved
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceReviewComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicalCommentProjectionTest {

    @Test fun legacyOnlyRowsAreNotAnActiveDisplaySource() {
        val projection = projectDocumentComments(listOf(OverviewCommentDocument("Concepts", listOf(legacyComment("old")))),
            emptyList(), emptyList(), emptySet(), emptyList())
        assertTrue(projection.documents.flatMap { it.comments }.isEmpty())
    }

    @Test
    fun proposalWithoutVerifiedGeometryDoesNotInventPageOne() {
        val proposal = OfflineAgentProposal(
            bundleId = "a".repeat(64),
            objectSha256 = "b".repeat(64),
            id = "proposal-without-placement",
            kind = "edit",
            comment = "Needs placement review.",
            sourceFile = "docs/notes.tex",
            sourceSha256 = "c".repeat(64),
            line = 10,
            bodyPreview = "old",
            beforePreview = "old",
            afterPreview = "new",
            parentId = null,
            suppressed = false,
            pdfFilenames = listOf("Concepts.pdf"),
            quads = emptyList(),
        )

        val projected = projectDocumentComments(
            emptyList(),
            listOf(proposal),
            emptyList(),
            emptySet(),
        ).documents.single().comments.single()

        assertEquals(-1, projected.pageIndex)
        assertEquals("needs_anchor", projected.anchorStatus)
        assertEquals(0L, projected.activityMillis)
    }

    @Test
    fun proposalCreationTimeIsItsActivity() {
        val proposal = OfflineAgentProposal(
            bundleId = "a".repeat(64), objectSha256 = "b".repeat(64), id = "dated-proposal", kind = "edit",
            comment = "", sourceFile = "docs/notes.tex", sourceSha256 = "c".repeat(64), line = 10,
            bodyPreview = "old", beforePreview = "old", afterPreview = "new", parentId = null, suppressed = false,
            pdfFilenames = listOf("Concepts.pdf"), quads = emptyList(), createdAt = "2026-10-01T09:30:00.000Z",
        )

        val projected = projectDocumentComments(emptyList(), listOf(proposal), emptyList(), emptySet())
            .documents.single().comments.single()

        assertEquals(1_790_847_000_000L, projected.activityMillis)
    }

    @Test
    fun oneLogicalCommentKeepsTwoRevisionBoundLocations() {
        val logical = logicalComment()
        val legacyDuplicate = OverviewCommentDocument(
            "Concepts",
            listOf(legacyComment("shared-comment")),
        )

        val global = projectDocumentComments(
            listOf(legacyDuplicate),
            emptyList(),
            emptyList(),
            emptySet(),
            listOf(logical),
        ).documents.flatMap(OverviewCommentDocument::comments)

        assertEquals(1, global.size)
        assertEquals("shared-comment", global.single().id)
        assertEquals(listOf(6, 803), global.single().logicalLocations.map { it.page })

        val sdpo = projectDocumentComments(
            emptyList(),
            emptyList(),
            emptyList(),
            emptySet(),
            listOf(logical),
            "SDPO.pdf",
            "b".repeat(64),
        ).documents.flatMap(OverviewCommentDocument::comments)

        assertEquals(1, sdpo.size)
        assertEquals(802, sdpo.single().pageIndex)
        assertEquals("exact", sdpo.single().logicalLocations.single().status)

        val staleRevision = projectDocumentComments(
            emptyList(),
            emptyList(),
            emptyList(),
            emptySet(),
            listOf(logical),
            "SDPO.pdf",
            "c".repeat(64),
        ).documents.flatMap(OverviewCommentDocument::comments)

        assertEquals(1, staleRevision.size)
        assertEquals(-1, staleRevision.single().pageIndex)
        assertTrue(staleRevision.single().logicalLocations.isEmpty())
        assertEquals("shared-comment", staleRevision.single().id)
    }

    @Test fun overviewRejectsMappingsFromAnOlderPdfRevision() {
        val comment = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(),
            listOf(logicalComment()), overviewPdfSha256ByFilename = mapOf(
                "Concepts.pdf" to "c".repeat(64), "SDPO.pdf" to "c".repeat(64)))
            .documents.single().comments.single()
        assertEquals(-1, comment.pageIndex)
        assertTrue(comment.logicalLocations.all { it.status == "unavailable" })
    }

    @Test
    fun sourceMarkerAliasDoesNotCreateASecondPageZeroCard() {
        val pdfSha256 = "d".repeat(64)
        val logical = logicalComment().copy(
            id = "pdf-thread",
            threadRootId = "pdf-thread",
            payloadJson = """
                {
                  "schema_version": 1,
                  "comment_id": "pdf-thread",
                  "thread_root_id": "pdf-thread",
                  "revision": 3,
                  "contents": "One source-linked comment",
                  "selected_text": "shared text",
                  "author": "Alex",
                  "subtype": "Highlight",
                  "created_at": "2026-08-27T09:00:00Z",
                  "updated_at": "2026-08-27T09:01:00Z",
                  "priority": "normal",
                  "color": [1, 1, 0, 1],
                  "scope": {
                    "anchor_id": "source-marker",
                    "state": "source_linked"
                  }
                }
            """.trimIndent(),
            correspondence = placement("shared-comment", listOf(
                LogicalCommentPdfLocation(
                    "Concepts.pdf",
                    pdfSha256,
                    4,
                    "exact",
                    regions = listOf(listOf(0.1f, 0.2f, 0.3f, 0.4f)),
                ),
            )),
        )
        val sourceProjection = OfflineSourceReviewComment(
            id = "source-marker",
            contents = "One source-linked comment",
            sourceFile = "docs/notes.tex",
            sourceSha256 = "e".repeat(64),
            line = 24,
            pdfFilenames = listOf("Concepts.pdf"),
        )

        val comments = projectDocumentComments(
            commentDocuments = emptyList(),
            proposals = emptyList(),
            sourceComments = listOf(sourceProjection),
            sourceCommentDocuments = setOf("Concepts.pdf"),
            logicalComments = listOf(logical),
            logicalDocumentFilename = "Concepts.pdf",
            logicalDocumentPdfSha256 = pdfSha256,
        ).documents.single().comments

        assertEquals(1, comments.size)
        assertEquals("pdf-thread", comments.single().id)
        assertEquals(3, comments.single().pageIndex)
        assertEquals("Alex", comments.single().author)
    }


    @Test fun overviewRecoversPdfOnlyOriginAndRepliesOnlyForMatchingRevision() {
        val root = logicalComment().copy(scopeState = "pdf_only", sourceFile = "", correspondence = null,
            payloadJson = """{"contents":"Saved","origin":{"collection":"Concepts","pdf_sha256":"hash",
                "raw_pdf_geometry":{"page":6,"rect":[10,20,30,40]}}}""")
        val reply = root.copy(id = "reply", parentId = root.id, payloadJson = """{"contents":"Reply"}""")
        fun project(hash: String) = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(),
            listOf(root, reply), overviewPdfSha256ByFilename = mapOf("Concepts.pdf" to hash)).documents.single().comments
        assertTrue(project("hash").all { it.pageIndex == 5 && it.logicalLocations.single().page == 6 })
        assertTrue(project("old").all { it.pageIndex == -1 && it.logicalLocations.isEmpty() })
    }

    @Test fun anchorRemovedWinsOverEveryStatusAndRepliesFollowTheirRoot() {
        val root = logicalComment().copy(scopeState = "needs_attention", correspondence = null,
            payloadJson = """{"contents":"Kept","source_authority":"client-v1","origin":{"collection":"Concepts"},
                "scope":{"state":"needs_attention","reason":"anchor_removed","source_file":"docs/notes.tex","source_line":7}}""")
        val reply = root.copy(id = "reply", parentId = root.id, scopeState = "pdf_only",
            payloadJson = """{"contents":"Reply","scope":{"state":"pdf_only"}}""")
        val comments = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(), listOf(root, reply))
            .documents.single().comments
        assertEquals(listOf("anchor_removed", "anchor_removed"), comments.map { it.anchorStatus })
        assertTrue(root.anchorRemoved)
        assertTrue(!reply.anchorRemoved)
        // Other reasons keep their status.
        val attention = root.copy(payloadJson = root.payloadJson.replace("anchor_removed", "source_marker_unavailable"))
        assertEquals("needs_attention", projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(),
            listOf(attention)).documents.single().comments.single().anchorStatus)
    }

    private fun logicalComment() = OfflineLogicalComment(
        id = "shared-comment",
        threadRootId = "shared-comment",
        parentId = null,
        revision = 3,
        deleted = false,
        scopeState = "source_linked",
        sourceFile = "docs/notes.tex",
        sourceLine = 24,
        originCollection = "Concepts",
        originPageIndex = 5,
        payloadJson = """
            {
              "schema_version": 1,
              "comment_id": "shared-comment",
              "thread_root_id": "shared-comment",
              "revision": 3,
              "contents": "One comment in two PDFs",
              "selected_text": "shared text",
              "author": "Alex",
              "subtype": "Text",
              "created_at": "2026-08-27T09:00:00Z",
              "updated_at": "2026-08-27T09:01:00Z",
              "priority": "normal",
              "color": [1, 1, 0, 1]
            }
        """.trimIndent(),
        correspondence = placement("shared-comment", listOf(
            LogicalCommentPdfLocation(
                "Concepts.pdf",
                "a".repeat(64),
                6,
                "page_only",
            ),
            LogicalCommentPdfLocation(
                "SDPO.pdf",
                "b".repeat(64),
                803,
                "exact",
                point = listOf(0.25f, 0.5f),
            ),
        )),
    )

    private fun legacyComment(id: String) = OverviewComment(
        documentStem = "Concepts",
        id = id,
        parentId = "",
        contents = "legacy duplicate",
        selectedText = "",
        author = "",
        sourceFile = "",
        sourceLine = 0,
        anchorStatus = "anchored",
        pageIndex = 5,
        subtype = "Text",
        created = "",
        modified = "",
        activityMillis = 0,
    )
}

private fun placement(id: String, locations: List<com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation>) =
    com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence(
        "comment:$id", "comment", id, id, "active", "source_linked", "", "", emptyList(), locations)
