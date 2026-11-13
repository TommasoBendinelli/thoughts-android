// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.usernotes

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineAgentProposal
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineOverviewSourceSnapshot
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineProposalQuad
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceReviewComment
import com.gitlab.mudlej.MjPdfReader.data.source.withLogicalPayloads
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCommentsTest {

    @Test
    fun listUsesAuthoritativeCommentsAndIncludesAgentProposals() {
        val userComment = comment(
            id = "user-comment",
            pageIndex = 1,
            contents = "Old wording",
            anchorStatus = "anchored",
        )
        val orphanedComment = comment(
            id = "orphaned-comment",
            pageIndex = 2,
            contents = "Removed from source",
            anchorStatus = "anchored",
        )
        val sourceSnapshot = OfflineOverviewSourceSnapshot(
            proposals = listOf(proposal()),
            reviewComments = listOf(
                OfflineSourceReviewComment(
                    id = "user-comment",
                    contents = "Authoritative wording",
                    sourceFile = "docs-sample/tex/main.tex",
                    sourceSha256 = "a".repeat(64),
                    line = 12,
                    pdfFilenames = listOf("sample.pdf"),
                ),
                OfflineSourceReviewComment(
                    id = "source-comment",
                    contents = "Source-only user comment",
                    sourceFile = "docs-sample/tex/main.tex",
                    sourceSha256 = "a".repeat(64),
                    line = 18,
                    pdfFilenames = listOf("sample.pdf"),
                ),
            ),
            reviewCommentDocuments = setOf("sample.pdf"),
        ).withLogicalPayloads(listOf("source-comment" to "Canonical source comment", "user-comment" to "Authoritative wording").map { (id, text) ->
            JsonParser.parseString("""{"schema_version":1,"comment_id":"$id","thread_root_id":"$id","revision":1,
                "deleted":false,"contents":"$text","author":"Tester","subtype":"Text",
                "origin":{"collection":"sample","page_index":0},"scope":{"state":"pdf_only"}}""").asJsonObject
        })

        val result = documentComments(
            "sample.pdf",
            listOf(userComment, orphanedComment),
            sourceSnapshot,
        )

        assertEquals(
            listOf("source-comment", "user-comment", "agent-remove-proposal"),
            result.map { it.comment.id },
        )
        assertEquals("Authoritative wording", result[1].comment.contents)
        assertFalse(result[0].isAgentProposal)
        assertFalse(result[1].isAgentProposal)
        assertTrue(result[2].isAgentProposal)
    }

    @Test
    fun filterSeparatesUserCommentsFromAgentProposalsAndCombinesWithSearch() {
        val user = DocumentCommentItem(
            comment(id = "user", contents = "Check the introduction"),
            isAgentProposal = false,
        )
        val agent = DocumentCommentItem(
            comment(id = "agent", contents = "Tighten this sentence"),
            isAgentProposal = true,
        )
        val comments = listOf(user, agent)

        assertEquals(
            listOf("user"),
            filterDocumentComments(
                comments,
                DocumentCommentFilter.USER_COMMENTS,
                "",
            ).map { it.comment.id },
        )
        assertEquals(
            listOf("agent"),
            filterDocumentComments(
                comments,
                DocumentCommentFilter.AGENT_PROPOSALS,
                "sentence",
            ).map { it.comment.id },
        )
        assertTrue(
            filterDocumentComments(
                comments,
                DocumentCommentFilter.USER_COMMENTS,
                "sentence",
            ).isEmpty(),
        )
    }

    @Test
    fun agentEditOfAHeadingIsListedAtTheHeadingNotItsContentsEntry() {
        val contents = OfflineProposalQuad("after", "sample.pdf", 1, 72f, 140f, 300f, 152f, duplicate = true)
        val heading = OfflineProposalQuad("after", "sample.pdf", 5, 72f, 300f, 300f, 316f)
        val legacy = proposal().copy(quads = listOf(contents, heading))
        assertEquals(4, documentComments("sample.pdf", emptyList(),
            OfflineOverviewSourceSnapshot(proposals = listOf(legacy))).single().comment.pageIndex)

        val hash = "a".repeat(64)
        val tocLocation = LogicalCommentPdfLocation("sample.pdf", hash, 1, "exact",
            regions = listOf(listOf(0.1f, 0.2f, 0.5f, 0.22f)), role = "after", duplicate = true)
        val headingLocation = tocLocation.copy(page = 5, regions = listOf(listOf(0.1f, 0.4f, 0.5f, 0.43f)), duplicate = false)
        val placed = proposal().copy(quads = emptyList(), correspondence = ReviewCorrespondence(
            "proposal:agent-remove-proposal", "agent_edit", "agent-remove-proposal", "", "active", "exact_text", "",
            hash, emptyList(), listOf(tocLocation, headingLocation)))
        val listed = documentComments("sample.pdf", emptyList(), OfflineOverviewSourceSnapshot(proposals = listOf(placed)))
            .single().comment
        assertEquals(4, listed.pageIndex)
        assertEquals(headingLocation.regions.single(), listed.pdfRect)

        // A proposal that only appears as a copy still has somewhere to go.
        val copyOnly = proposal().copy(quads = listOf(contents))
        assertEquals(0, documentComments("sample.pdf", emptyList(),
            OfflineOverviewSourceSnapshot(proposals = listOf(copyOnly))).single().comment.pageIndex)
    }

    @Test
    fun unavailablePagesNeverBecomePageZeroOrAnInventedFirstPage() {
        assertEquals(null, commentDisplayPage(-1))
        assertEquals(null, commentDisplayPage(Int.MIN_VALUE))
        assertEquals(1, commentDisplayPage(0))
        assertEquals(804, commentDisplayPage(803))
        assertEquals(null, commentDisplayPage(Int.MAX_VALUE))
    }

    private fun comment(
        id: String,
        pageIndex: Int = 0,
        contents: String,
        anchorStatus: String = "anchored",
    ) = OverviewComment(
        documentStem = "sample",
        id = id,
        parentId = "",
        contents = contents,
        selectedText = "selected text",
        author = "Alex",
        sourceFile = "",
        sourceLine = 0,
        anchorStatus = anchorStatus,
        pageIndex = pageIndex,
        subtype = "Highlight",
        created = "",
        modified = "",
        activityMillis = 1,
    )

    private fun proposal() = OfflineAgentProposal(
        bundleId = "b".repeat(64),
        objectSha256 = "c".repeat(64),
        id = "agent-remove-proposal",
        kind = "edit",
        comment = "The revised sentence is clearer.",
        sourceFile = "docs-sample/tex/main.tex",
        sourceSha256 = "a".repeat(64),
        line = 24,
        bodyPreview = "",
        beforePreview = "Old sentence",
        afterPreview = "New sentence",
        parentId = null,
        suppressed = false,
        pdfFilenames = listOf("other.pdf", "sample.pdf"),
        quads = listOf(
            OfflineProposalQuad(
                role = "before",
                pdfFilename = "sample.pdf",
                page = 4,
                left = 10f,
                top = 20f,
                right = 30f,
                bottom = 40f,
            ),
        ),
    )
}
