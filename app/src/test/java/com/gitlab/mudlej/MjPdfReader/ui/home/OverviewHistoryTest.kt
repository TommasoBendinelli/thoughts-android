// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentPriority
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewComment
import com.gitlab.mudlej.MjPdfReader.data.annotation.OverviewCommentDocument
import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewHistoryTest {

    @Test
    fun invalidatingSnapshotEditsPreservesServiceHistoryOnBothStacks() {
        val history = OverviewHistory<String>()
        history.record("delete-first")
        history.record("edit")
        history.record("delete-last")
        history.completeUndo()
        assertTrue(history.removeIf { it == "edit" })
        assertEquals("delete-first", history.peekUndo())
        assertEquals("delete-last", history.peekRedo())
        assertFalse(history.removeIf { it == "edit" })
    }

    @Test
    fun sourceOnlyFilterExcludesNotesAndCommentsWithPdfLocations() {
        val source = comment("source").copy(pageIndex = -1)
        val note = source.copy(id = "note", subtype = "Note")
        val placed = source.copy(id = "placed", logicalLocations = listOf(
            LogicalCommentPdfLocation("paper.pdf", "a".repeat(64), 3, "available"),
        ))
        assertEquals(listOf(source), filterOverviewComments(
            listOf(source, note, placed, comment("pdf")), OVERVIEW_SOURCE_ONLY,
            OverviewAnchorFilter.ALL, OverviewPdfFilter.ALL, emptySet(), false,
        ))
    }

    @Test
    fun documentSelectionStillCombinesWithPriorityAndCanBeCleared() {
        val high = comment("high").copy(priority = CommentPriority.HIGH)
        val other = high.copy(id = "other", documentStem = "other")
        val all = listOf(high, other, comment("normal"))
        assertEquals(listOf(high), filterOverviewComments(
            all, "paper", OverviewAnchorFilter.ALL, OverviewPdfFilter.ALL, emptySet(), false,
            OverviewPriorityFilter.HIGH,
        ))
        assertEquals(listOf(high, other), filterOverviewComments(
            all, "", OverviewAnchorFilter.ALL, OverviewPdfFilter.ALL, emptySet(), false,
            OverviewPriorityFilter.HIGH,
        ))
    }

    @Test
    fun overviewOnlyIncludesDocumentsWithAnAvailablePdf() {
        val available = OverviewCommentDocument("Paper", listOf(comment("available")))
        val unavailable = OverviewCommentDocument("offline", listOf(comment("unavailable")))
        val absent = OverviewCommentDocument("missing", listOf(comment("absent")))

        assertEquals(
            listOf("Paper"),
            availableOverviewDocuments(
                listOf(available, unavailable, absent),
                mapOf("paper" to true, "offline" to false),
            ).map { it.stem },
        )
    }

    @Test
    fun logicalCommentsRemainGloballyVisibleWhenTheirPdfsAreOffline() {
        val logical = comment("logical").copy(
            logicalPayloadJson = "{}",
            logicalLocations = listOf(
                LogicalCommentPdfLocation("Paper.pdf", "a".repeat(64), 6, "unavailable"),
            ),
        )

        assertEquals(
            listOf("logical"),
            availableOverviewDocuments(
                listOf(OverviewCommentDocument("Paper", listOf(logical))),
                mapOf("paper" to false),
            ).flatMap(OverviewCommentDocument::comments).map(OverviewComment::id),
        )
    }

    @Test
    fun newChangeClearsRedoAndExternalInvalidationClearsBothStacks() {
        val history = OverviewHistory<String>()
        history.record("edit")
        history.completeUndo()
        assertTrue(history.canRedo)

        history.record("delete")
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        history.clear()
        assertTrue(history.isEmpty)
    }

    @Test
    fun deletingRootIncludesNestedRepliesButSelectingReplyDoesNotDeleteRoot() {
        val root = comment("root")
        val reply = comment("reply", "root")
        val nested = comment("nested", "reply")
        val other = comment("other")
        val all = listOf(root, reply, nested, other)

        assertEquals(
            listOf("root", "reply", "nested"),
            expandOverviewDeletion(all, setOf(overviewCommentKey(root))).map { it.id },
        )
        assertEquals(
            listOf("reply", "nested"),
            expandOverviewDeletion(all, setOf(overviewCommentKey(reply))).map { it.id },
        )
    }

    @Test
    fun commentsAreOrderedByActivityAcrossDocuments() {
        val oldFromFirstDocument = comment("old").copy(activityMillis = 1)
        val newestFromSecondDocument = comment("new").copy(
            documentStem = "z-paper",
            activityMillis = 3,
        )
        val middle = comment("middle").copy(activityMillis = 2)

        assertEquals(
            listOf("new", "middle", "old"),
            listOf(oldFromFirstDocument, newestFromSecondDocument, middle)
                .sortedWith(overviewCommentComparator(OverviewSortOrder.NEWEST))
                .map { it.id },
        )
    }

    @Test
    fun commentsCanBeSortedByOldestDocumentAndPage() {
        val pageTwoFromB = comment("b2").copy(
            documentStem = "B",
            pageIndex = 1,
            activityMillis = 2,
        )
        val pageThreeFromA = comment("a3").copy(
            documentStem = "A",
            pageIndex = 2,
            activityMillis = 1,
        )
        val pageOneFromB = comment("b1").copy(
            documentStem = "B",
            pageIndex = 0,
            activityMillis = 3,
        )
        val comments = listOf(pageTwoFromB, pageThreeFromA, pageOneFromB)

        assertEquals(
            listOf("a3", "b2", "b1"),
            comments.sortedWith(overviewCommentComparator(OverviewSortOrder.OLDEST)).map { it.id },
        )
        assertEquals(
            listOf("a3", "b1", "b2"),
            comments.sortedWith(overviewCommentComparator(OverviewSortOrder.DOCUMENT)).map { it.id },
        )
        assertEquals(
            listOf("b1", "b2", "a3"),
            comments.sortedWith(overviewCommentComparator(OverviewSortOrder.PAGE)).map { it.id },
        )
    }

    @Test
    fun commentsCanBeFilteredByDocumentAndAnchorStatus() {
        val anchored = comment("anchored").copy(anchorStatus = "anchored")
        val needsAnchor = comment("needs").copy(anchorStatus = "needs_anchor")
        val pdfOnly = comment("pdf").copy(documentStem = "other", anchorStatus = "")
        val comments = listOf(anchored, needsAnchor, pdfOnly)

        assertEquals(
            listOf("anchored", "needs"),
            filterOverviewComments(
                comments,
                "paper",
                OverviewAnchorFilter.ALL,
                OverviewPdfFilter.ALL,
                setOf("paper"),
                true,
            ).map { it.id },
        )
        assertEquals(
            listOf("anchored"),
            filterOverviewComments(
                comments,
                "",
                OverviewAnchorFilter.ANCHORED,
                OverviewPdfFilter.ALL,
                setOf("paper"),
                true,
            ).map { it.id },
        )
        assertEquals(
            listOf("needs"),
            filterOverviewComments(
                comments,
                "",
                OverviewAnchorFilter.NEEDS_ANCHOR,
                OverviewPdfFilter.ALL,
                setOf("paper"),
                true,
            ).map { it.id },
        )
        assertEquals(
            listOf("pdf"),
            filterOverviewComments(
                comments,
                "",
                OverviewAnchorFilter.PDF_ONLY,
                OverviewPdfFilter.ALL,
                setOf("paper"),
                true,
            ).map { it.id },
        )
    }

    @Test
    fun anchorRemovedCommentsAreListedOnlyUnderIssues() {
        val linked = comment("linked").copy(anchorStatus = "source_linked")
        val removed = comment("removed").copy(anchorStatus = "anchor_removed")
        val attention = comment("attention").copy(anchorStatus = "needs_attention")
        val pdfOnly = comment("pdf").copy(anchorStatus = "pdf_only")
        val comments = listOf(linked, removed, attention, pdfOnly)
        fun ids(filter: OverviewAnchorFilter) = filterOverviewComments(
            comments, "", filter, OverviewPdfFilter.ALL, setOf("paper"), true,
        ).map { it.id }
        assertEquals(listOf("linked", "attention", "pdf"), ids(OverviewAnchorFilter.ALL))
        assertEquals(listOf("linked"), ids(OverviewAnchorFilter.ANCHORED))
        assertEquals(listOf("removed", "attention"), ids(OverviewAnchorFilter.NEEDS_ANCHOR))
        assertEquals(listOf("pdf"), ids(OverviewAnchorFilter.PDF_ONLY))
        assertEquals(R.string.overview_anchor_removed, overviewAnchorStatusLabel("anchor_removed"))
    }

    @Test
    fun commentsCanBeFilteredByPdfClassWithoutHidingDataBeforeIndexLoads() {
        val linked = comment("linked").copy(documentStem = "Paper")
        val other = comment("other").copy(documentStem = "standalone")
        val comments = listOf(linked, other)

        assertEquals(
            listOf("linked"),
            filterOverviewComments(
                comments,
                "",
                OverviewAnchorFilter.ALL,
                OverviewPdfFilter.TEX_LINKED,
                setOf("paper"),
                true,
            ).map { it.id },
        )
        assertEquals(
            listOf("other"),
            filterOverviewComments(
                comments,
                "",
                OverviewAnchorFilter.ALL,
                OverviewPdfFilter.OTHER,
                setOf("paper"),
                true,
            ).map { it.id },
        )
        assertEquals(
            listOf("linked", "other"),
            filterOverviewComments(
                comments,
                "",
                OverviewAnchorFilter.ALL,
                OverviewPdfFilter.TEX_LINKED,
                emptySet(),
                false,
            ).map { it.id },
        )
        assertEquals(
            OverviewPdfClass.UNKNOWN,
            overviewPdfClass("paper", setOf("paper"), sourceIndexAvailable = false),
        )
    }

    @Test
    fun theLocationFilterOffersThePlacesThatCommentsCarry() {
        val library = CommentLocation("Library", 48.8335, 2.3758, googlePlaceId = "place-id")
        val office = CommentLocation("office", 46.0, 7.0)
        val comments = listOf(
            comment("a").copy(location = office),
            comment("b"),
            comment("c").copy(location = library),
            // The same place on a second comment, confirmed at another time, is offered once.
            comment("d").copy(location = library.copy(confirmedAt = "2026-07-20T10:00:00Z")),
        )

        assertEquals(listOf(library, office), overviewCommentPlaces(comments))
        assertEquals(emptyList<CommentLocation>(), overviewCommentPlaces(listOf(comment("b"))))
    }

    @Test
    fun priorityAndLocationFiltersCombineWithExistingFilters() {
        val parisHigh = comment("paris-high").copy(
            anchorStatus = "anchored",
            priority = CommentPriority.HIGH,
            location = CommentLocation("Paris", 48.8566, 2.3522),
        )
        val nearbyNormal = comment("nearby-normal").copy(
            anchorStatus = "anchored",
            priority = CommentPriority.NORMAL,
            location = CommentLocation("Nearby", 48.8650, 2.3522),
        )
        val londonHigh = comment("london-high").copy(
            anchorStatus = "anchored",
            priority = CommentPriority.HIGH,
            location = CommentLocation("London", 51.5074, -0.1278),
        )
        val noLocationHigh = comment("no-location-high").copy(
            anchorStatus = "anchored",
            priority = CommentPriority.HIGH,
        )

        val filtered = filterOverviewComments(
            listOf(parisHigh, nearbyNormal, londonHigh, noLocationHigh),
            "paper",
            OverviewAnchorFilter.ANCHORED,
            OverviewPdfFilter.ALL,
            setOf("paper"),
            true,
            OverviewPriorityFilter.HIGH,
            OverviewLocationFilter("Paris", 48.8566, 2.3522, radiusKm = 5),
        )

        assertEquals(listOf("paris-high"), filtered.map { it.id })
        assertTrue(distanceKm(48.8566, 2.3522, 48.8650, 2.3522) < 1.0)
        assertTrue(distanceKm(48.8566, 2.3522, 51.5074, -0.1278) > 300.0)
    }

    @Test
    fun newOverviewCommentUsesFixedTopPageOnePlacement() {
        val rect = requireNotNull(overviewTopCommentGeometry(612f, 792f))

        assertEquals(24f, rect.left, 0f)
        assertEquals(768f, rect.top, 0f)
        assertEquals(52f, rect.right, 0f)
        assertEquals(740f, rect.bottom, 0f)
    }

    @Test
    fun detailStatusUsesAnchorStateWhileRowsCanRemainUnlabelled() {
        assertEquals(R.string.overview_source_linked, overviewAnchorStatusLabel("anchored"))
        assertEquals(R.string.overview_source_linked, overviewAnchorStatusLabel("source_linked"))
        assertEquals(R.string.overview_linking_source, overviewAnchorStatusLabel("needs_anchor"))
        assertEquals(
            R.string.overview_source_needs_attention,
            overviewAnchorStatusLabel("needs_attention"),
        )
        assertEquals(R.string.overview_only_in_pdf, overviewAnchorStatusLabel("pdf_only"))
        assertEquals(R.string.overview_pdf_only, overviewAnchorStatusLabel(""))
    }

    @Test
    fun detailSourceLocationIncludesLineOnlyWhenAvailable() {
        val source = comment("source").copy(
            sourceFile = "docs-paper/tex/source/paper.tex",
            sourceLine = 22,
        )

        assertEquals(
            "docs-paper/tex/source/paper.tex:22",
            overviewSourceLocation(source),
        )
        assertEquals(
            "docs-paper/tex/source/paper.tex",
            overviewSourceLocation(source.copy(sourceLine = 0)),
        )
        assertEquals(null, overviewSourceLocation(source.copy(sourceFile = "")))
    }

    private fun comment(id: String, parent: String = "") = OverviewComment(
        documentStem = "paper",
        id = id,
        parentId = parent,
        contents = id,
        selectedText = "",
        author = "",
        sourceFile = "",
        sourceLine = 0,
        anchorStatus = "",
        pageIndex = 0,
        subtype = "Text",
        created = "",
        modified = "",
        activityMillis = 0,
    )
}
