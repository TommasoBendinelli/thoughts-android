// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence
import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewCommentLinkingTest {
    private val comment = OverviewComment("paper", "id", "", "Comment", "", "", "", 0,
        "pdf_only", 2, "Text", "", "", 0)
    private fun label(state: String, reason: String = "") = overviewCommentLinkingLabel(
        comment.copy(reviewCorrespondence = ReviewCorrespondence(
            "comment:id", "comment", "id", "id", "active", state, reason, "", emptyList(), emptyList())),
        setOf("paper"), true)

    @Test fun anchorRemovedIsNamedEvenWithAStaleCorrespondence() {
        val removed = comment.copy(anchorStatus = "anchor_removed", logicalPayloadJson = """{"scope":{"state":"needs_attention",""" +
            """"reason":"anchor_removed","source_file":"docs-paper/main.tex","source_line":12}}""")
        assertEquals(R.string.overview_anchor_removed, overviewCommentLinkingLabel(removed, setOf("paper"), true))
        assertEquals(R.string.overview_anchor_removed, overviewCommentLinkingLabel(removed.copy(
            reviewCorrespondence = ReviewCorrespondence("comment:id", "comment", "id", "id", "active", "exact_point", "", "",
                emptyList(), emptyList())), setOf("paper"), true))
        assertEquals("docs-paper/main.tex" to 12, overviewAnchorRemovedLocation(removed))
        assertEquals(null, overviewAnchorRemovedLocation(comment))
    }
    @Test fun exactCorrespondenceIsLinked() {
        assertEquals(R.string.overview_linking_linked, label("exact_point"))
    }
    @Test fun absentSourceIsDifferentFromAbsentAnnotation() {
        assertEquals(R.string.overview_linking_without_source, label("no_source"))
        assertEquals(R.string.overview_linking_no_annotation,
            label("linking", "source anchor has not been established"))
    }
    @Test fun existingMarkupWithoutPlacementExplainsFailure() {
        assertEquals(R.string.overview_linking_location_unavailable,
            label("needs_attention", "source marker produced no exact PDF placement"))
    }
    @Test fun proposalPlacementFailureUsesAgreedLabelAndLaterBuildCanLink() {
        val unresolved = comment.copy(reviewCorrespondence = ReviewCorrespondence(
            "proposal:id", "agent_edit", "id", "", "pending", "needs_attention",
            "source marker produced no exact PDF placement", "", emptyList(), emptyList()))
        assertEquals(R.string.overview_linking_proposal_location_unavailable,
            overviewCommentLinkingLabel(unresolved, setOf("paper"), true))
        assertEquals(R.string.overview_linking_linked, overviewCommentLinkingLabel(
            unresolved.copy(reviewCorrespondence = unresolved.reviewCorrespondence!!.copy(state = "exact_text")),
            setOf("paper"), true))
    }
    @Test fun unestablishedLinksUseSourceAvailability() {
        assertEquals(R.string.overview_linking_no_annotation,
            overviewCommentLinkingLabel(comment, setOf("paper"), true))
        assertEquals(R.string.overview_linking_failed,
            overviewCommentLinkingLabel(comment, emptySet(), false))
    }
    @Test fun unlinkedDocumentExplainsPdfOnlyComment() {
        assertEquals(R.string.overview_linking_without_source,
            overviewCommentLinkingLabel(comment, emptySet(), true))
    }
    @Test fun linkedSourceAndPdfPlacementAreIndependent() {
        val linked = comment.copy(anchorStatus = "source_linked")
        assertEquals(R.string.overview_linking_location_unavailable,
            overviewCommentLinkingLabel(linked, setOf("paper"), true))
        val placement = com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation(
            "paper.pdf", "a".repeat(64), 3, "exact", regions = listOf(listOf(0.1f, 0.2f, 0.4f, 0.3f)))
        assertEquals(R.string.overview_linking_linked, overviewCommentLinkingLabel(
            linked.copy(logicalLocations = listOf(placement)), setOf("paper"), true))
        for (invalid in listOf(placement.copy(status = "unavailable"), placement.copy(page = 0),
            placement.copy(regions = listOf(listOf(0.4f, 0.2f, 0.1f, 0.3f))),
            placement.copy(regions = listOf(listOf(Float.NaN, 0.2f, 0.4f, 0.3f))))) {
            assertEquals(R.string.overview_linking_location_unavailable, overviewCommentLinkingLabel(
                linked.copy(logicalLocations = listOf(invalid)), setOf("paper"), true))
        }
    }

}
