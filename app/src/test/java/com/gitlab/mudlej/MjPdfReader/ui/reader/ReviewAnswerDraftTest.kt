// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewContextNotes
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAnswerDraftTest {
    @Test fun onlyOneGroupIsOpenAndTappingTheOpenOneClosesIt() {
        val draft = ReviewAnswerDraft("card")
        draft.settle(listOf("g1", "g2", "other"))
        assertNull("All rows start closed", draft.expanded)
        draft.toggle("g1")
        assertEquals("g1", draft.expanded)
        draft.toggle("g2")
        assertEquals("g2", draft.expanded)
        draft.toggle("g2")
        assertNull(draft.expanded)
    }

    @Test fun aLoneOtherRowStartsOpenUntilClosed() {
        val draft = ReviewAnswerDraft("card")
        draft.settle(listOf("other"))
        assertEquals("other", draft.expanded)
        draft.toggle("other")
        draft.settle(listOf("other"))
        assertNull("Reopening the sheet respects the user's choice", draft.expanded)
    }

    @Test fun anOpenGroupThatWasDeletedCloses() {
        val draft = ReviewAnswerDraft("card")
        draft.toggle("g1")
        draft.settle(listOf("g2", "other"))
        assertNull(draft.expanded)
    }

    @Test fun notesStayWithTheAnswerAndAreRecordedTrimmed() {
        // The sheet is closed and opened again with the same draft: the open row and notes remain.
        val draft = ReviewAnswerDraft("card")
        draft.toggle("g1")
        draft.setNote("g1", "  second step still vague ")
        draft.setNote("other", "see the appendix")
        draft.setNote("g2", "   ")
        assertEquals("g1", draft.expanded)
        assertEquals("  second step still vague ", draft.note("g1"))
        assertTrue(draft.hasNote("g1"))
        assertFalse(draft.hasNote("g2"))
        assertEquals(mapOf("g1" to "second step still vague", "other" to "see the appendix"), draft.recordedNotes())
        // Clearing a note removes it.
        draft.setNote("other", "")
        assertEquals(mapOf("g1" to "second step still vague"), draft.recordedNotes())
        assertEquals(500, ReviewAnswerDraft().apply { setNote("g1", "y".repeat(700)) }.note("g1").length)
        // The next card starts empty.
        val next = ReviewAnswerDraft("next")
        assertEquals(emptyMap<String, String>(), next.recordedNotes())
        assertNull(next.expanded)
    }

    @Test fun goingBackToTheBeforeStepOpensItAndKeepsWhenTheAnswerWasFirstShown() {
        val shownAt = java.time.Instant.parse("2026-11-03T12:00:05.120Z")
        val draft = ReviewAnswerDraft("card").apply { beforeShown = true; answerShownAt = shownAt }
        assertTrue(draft.beforeLocked)

        draft.beforeReopened = true
        assertFalse(draft.beforeLocked)
        assertEquals(shownAt, draft.answerShownAt)

        val restored = ReviewAnswerDraft.restored("card", draft.snapshot())
        assertFalse(restored.beforeLocked)
        assertEquals(shownAt, restored.answerShownAt)
    }

    @Test fun contextNotesRecordOnlyTheScopesTheAnswerChanged() {
        val draft = ReviewAnswerDraft("card")
        // Never offered: nothing to record.
        assertEquals(emptyMap<ReviewContextNotes.Scope, ReviewContextNotes.Note>(), draft.recordedContextNotes())
        val targets = mapOf(
            ReviewContextNotes.Scope.SECTION to ReviewContextNotes.Target("2 Method › 2.1 Data", "2.1 Data"),
            ReviewContextNotes.Scope.PAGE to ReviewContextNotes.Target("3", "Page 3"),
            ReviewContextNotes.Scope.DOCUMENT to ReviewContextNotes.Target("", "Paper"),
        )
        draft.settleContext(targets, mapOf(ReviewContextNotes.Scope.PAGE to "see figure 2", ReviewContextNotes.Scope.SECTION to ""))
        // The box starts with the shared note.
        assertEquals("see figure 2", draft.contextText(ReviewContextNotes.Scope.PAGE))
        assertEquals("", draft.contextText(ReviewContextNotes.Scope.DOCUMENT))
        // Typing the same text again (blanks aside) is no change.
        draft.setContextText(ReviewContextNotes.Scope.PAGE, " see figure 2 ")
        assertEquals(emptyMap<ReviewContextNotes.Scope, ReviewContextNotes.Note>(), draft.recordedContextNotes())
        // Edits in several scopes stay, each with its own text.
        draft.setContextText(ReviewContextNotes.Scope.SECTION, " the split ")
        draft.setContextText(ReviewContextNotes.Scope.PAGE, "")
        assertEquals(" the split ", draft.contextText(ReviewContextNotes.Scope.SECTION))
        assertEquals(mapOf(
            ReviewContextNotes.Scope.SECTION to ReviewContextNotes.Note("2 Method › 2.1 Data", "2.1 Data", "the split"),
            ReviewContextNotes.Scope.PAGE to ReviewContextNotes.Note("3", "Page 3", ""),
        ), draft.recordedContextNotes())
        // A later opening of the sheet keeps what the answer started from.
        draft.settleContext(targets, mapOf(ReviewContextNotes.Scope.PAGE to "something newer"))
        assertEquals(ReviewContextNotes.Note("3", "Page 3", ""), draft.recordedContextNotes()[ReviewContextNotes.Scope.PAGE])
        // A scope the card has no target for is never recorded.
        val noSection = ReviewAnswerDraft("other")
        noSection.settleContext(targets - ReviewContextNotes.Scope.SECTION, emptyMap())
        noSection.setContextText(ReviewContextNotes.Scope.SECTION, "orphan")
        assertEquals(emptyMap<ReviewContextNotes.Scope, ReviewContextNotes.Note>(), noSection.recordedContextNotes())
    }

    @Test fun theRowSummaryListsTheChosenTagsOfThatGroup() {
        val tags = listOf("fair", "important", "cool")
        assertEquals("Fair, Important", reviewTagGroupSummary(tags, setOf("important", "fair", "elsewhere")) { it.replaceFirstChar(Char::uppercase) })
        assertEquals("", reviewTagGroupSummary(tags, setOf("elsewhere")) { it })
    }

    @Test fun moveTagsHereOffersEveryOtherGroupsTags() {
        val g1 = ReviewTagGroups.Group("g1", "Understanding", listOf("correct"))
        val g2 = ReviewTagGroups.Group("g2", "Interest", emptyList())
        val sections = listOf(
            ReviewTagGroups.Section(g1, listOf("correct")),
            ReviewTagGroups.Section(g2, emptyList()),
            ReviewTagGroups.Section(null, listOf("notation", "a very long tag that the chip shortens")),
        )
        assertEquals(listOf(null to listOf("notation", "a very long tag that the chip shortens")),
            reviewMoveTagCandidates(sections, "g1"))
        assertEquals(listOf(g1 to listOf("correct"), null to listOf("notation", "a very long tag that the chip shortens")),
            reviewMoveTagCandidates(sections, "g2"))
        assertEquals(listOf(g1 to listOf("correct")), reviewMoveTagCandidates(sections, null))
    }

    @Test fun theTagCellNamesTwoTagsAndCountsTheRest() {
        assertEquals("No tag", reviewTagCellText(emptyList(), { it }, "No tag"))
        assertEquals("I get it", reviewTagCellText(listOf("I get it"), { it }, "No tag"))
        assertEquals("I get it · Recalled", reviewTagCellText(listOf("I get it", "Recalled"), { it }, "No tag"))
        assertEquals("I get it · Recalled +2", reviewTagCellText(listOf("I get it", "Recalled", "a", "b"), { it }, "No tag"))
        assertEquals("A · B (and 1)", reviewTagCellText(listOf("a", "b", "c"), { it.uppercase() }, "-") { text, n -> "$text (and $n)" })
    }
}
