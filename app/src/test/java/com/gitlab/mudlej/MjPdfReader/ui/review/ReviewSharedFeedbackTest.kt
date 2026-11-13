// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Polarity
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback.Region
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewFeedbackAnchoring.State
import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics
import com.gitlab.mudlej.MjPdfReader.ui.reader.ReviewCardFeedbackLine
import com.gitlab.mudlej.MjPdfReader.ui.reader.reviewCardFeedbackSection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Answer feedback is shared across the cards of a document: a card shows the records made on its
 * document's other cards whose passage lies inside its answer, never the others; editing or
 * deleting one from any card changes that same record. Same synthetic 100 × 100 pt page as
 * [ReviewFeedbackAnswerTextTest]: lines at y = 80 ("first line"), 60 ("second line text") and
 * 20 ("outside") pt.
 */
class ReviewSharedFeedbackTest {
    private data class Glyph(val codePoint: Int, val left: Float, val bottom: Float, val right: Float, val top: Float)

    private fun line(text: String, y: Float): List<Glyph> {
        var x = 10f
        return text.map { character ->
            if (character == ' ') Glyph(' '.code, x, y - 4f, x + 2f, y + 4f).also { x += 3f }
            else Glyph(character.code, x, y - 4f, x + 4f, y + 4f).also { x += 5f }
        }
    }

    private fun newline() = listOf(Glyph('\n'.code, 0f, 0f, 0f, 0f))

    private fun metrics(glyphs: List<Glyph>): PageCharMetrics {
        val values = DoubleArray(glyphs.size * 9)
        glyphs.forEachIndexed { index, glyph ->
            val base = index * 9
            values[base] = glyph.left.toDouble()
            values[base + 1] = glyph.bottom.toDouble()
            values[base + 2] = glyph.right.toDouble()
            values[base + 3] = glyph.top.toDouble()
            values[base + 4] = 8.0
            values[base + 6] = glyph.codePoint.toDouble()
        }
        return PageCharMetrics.fromRaw(values)!!
    }

    private val page = FeedbackPageText(
        metrics(line("first line", 80f) + newline() + line("second line text", 60f) + newline() + line("outside", 20f)),
        100f, 100f,
    )
    private fun answer(regions: List<Region>) = FeedbackAnswerText.build(regions) { if (it == 1) page else null }

    /** Card A (home of the feedback): both upper lines. */
    private val homeRegions = listOf(Region(1, 0.0, 0.1, 1.0, 0.5))
    /** Card B: only the first line, inside card A's answer. */
    private val overlappingRegions = listOf(Region(1, 0.0, 0.1, 1.0, 0.3))
    /** Card C: the "outside" line only. */
    private val separateRegions = listOf(Region(1, 0.0, 0.7, 1.0, 0.9))

    private val documentId = "c1e62ecc-2dfb-4259-97cc-e319b1186848"
    private val otherDocumentId = "7d6f0a52-3c1e-4b8a-9f21-5e7a0c9d1b44"
    private val madeOn = "a".repeat(64)
    private val rebuilt = "b".repeat(64)
    private val homeCardId = "0072b138-d3fe-416a-83e7-1407058f0e2b"
    private val home = FeedbackHomeCard("What is the first line?", deleted = false, documentIds = setOf(documentId), pdfStem = "notes")
    private fun cardB(documentIds: Set<String> = setOf(documentId)) = FeedbackCardIdentity("card-b", documentIds, "notes")

    private lateinit var directory: File
    private val previousFile = ReviewCardFeedback.file

    @Before fun setUp() {
        directory = Files.createTempDirectory("shared-feedback").toFile()
        ReviewCardFeedback.file = { File(directory, "card-feedback.json") }
    }

    @After fun tearDown() {
        ReviewCardFeedback.file = previousFile
        directory.deleteRecursively()
    }

    /** "first line" liked on card A, on the PDF [madeOn]: its line rectangle 0.16–0.24 from the top. */
    private fun likeFirstLine(cardId: String = homeCardId, document: String = documentId, comment: String = "",
                              created: Instant = Instant.parse("2026-10-20T10:00:00Z")) =
        ReviewCardFeedback.create(cardId, Polarity.POSITIVE, comment, ReviewCardFeedback.NewAnchor(
            "first line", "", "\nsecond line", document, madeOn, listOf(Region(1, 0.1, 0.16, 0.52, 0.24))), "phone", created)

    private fun candidates(card: FeedbackCardIdentity = cardB(), homes: Map<String, FeedbackHomeCard> = mapOf(homeCardId to home)) =
        sharedFeedbackCandidates(ReviewCardFeedback.all(), card) { homes[it] }

    @Test fun sharedFeedbackIsShownOnAnOverlappingCardOfTheSameDocument() {
        val liked = likeFirstLine()
        val shared = candidates()
        assertEquals(listOf(liked.id), shared.map { it.feedback.id })
        assertEquals("What is the first line?", shared.single().homeQuestion)
        // Same PDF: drawn at its own regions, which lie inside card B's answer.
        val placed = placeSharedFeedback(shared, madeOn, answer(overlappingRegions), overlappingRegions).single()
        assertEquals(State.EXACT, placed.state)
        assertEquals(liked.regions, placed.regions)
        assertEquals(0, placed.position)
        assertTrue(placed.shared)
        assertEquals("What is the first line?", placed.sharedFrom)
    }

    @Test fun sharedFeedbackIsHiddenOnACardWhoseAnswerDoesNotContainIt() {
        likeFirstLine()
        val shared = candidates()
        // Same PDF: the rectangle is outside card C's answer; another PDF: the quote is not in its text.
        // Either way it is left out, not listed as "not found".
        assertEquals(emptyList<FeedbackPlacement>(), placeSharedFeedback(shared, madeOn, answer(separateRegions), separateRegions))
        assertEquals(emptyList<FeedbackPlacement>(), placeSharedFeedback(shared, rebuilt, answer(separateRegions), separateRegions))
        // Without the answer text on another PDF, nothing can be placed and nothing is shown.
        assertEquals(emptyList<FeedbackPlacement>(), placeSharedFeedback(shared, rebuilt, null, separateRegions))
    }

    @Test fun sharedFeedbackIsRelocatedOnAnotherPdfVersion() {
        val disliked = ReviewCardFeedback.create(homeCardId, Polarity.NEGATIVE, "Unclear", ReviewCardFeedback.NewAnchor(
            "line second", "first ", " line text", documentId, madeOn, listOf(Region(1, 0.3, 0.16, 0.52, 0.24))), "phone")
        val shared = candidates()
        // Card A's answer on the rebuilt PDF also covers both lines: found by its quote.
        val placed = placeSharedFeedback(shared, rebuilt, answer(homeRegions), homeRegions).single()
        assertEquals(disliked.id, placed.feedback.id)
        assertEquals(State.RELOCATED, placed.state)
        assertEquals(2, placed.regions.size)
        assertEquals(answer(homeRegions).text.indexOf("line\nsecond"), placed.position)
        // Card B holds only the first line: the passage is not inside its answer, so it is not shown.
        assertEquals(emptyList<FeedbackPlacement>(), placeSharedFeedback(shared, rebuilt, answer(overlappingRegions), overlappingRegions))
    }

    @Test fun onlyLiveFeedbackOfOtherKnownCardsOfTheSameDocumentIsShared() {
        val own = likeFirstLine(cardId = "card-b")
        val other = likeFirstLine(document = otherDocumentId, created = Instant.parse("2026-10-20T10:01:00Z"))
        val deleted = likeFirstLine(created = Instant.parse("2026-10-20T10:02:00Z")).also { ReviewCardFeedback.remove(it.id, "phone") }
        val unknownHome = likeFirstLine(cardId = "card-gone", created = Instant.parse("2026-10-20T10:03:00Z"))
        val deletedHome = likeFirstLine(cardId = "card-deleted", created = Instant.parse("2026-10-20T10:04:00Z"))
        val kept = likeFirstLine(created = Instant.parse("2026-10-20T10:05:00Z"))
        val homes = mapOf(homeCardId to home, "card-b" to home, "card-deleted" to home.copy(deleted = true))
        val shared = candidates(homes = homes)
        assertEquals(listOf(kept.id), shared.map { it.feedback.id })
        listOf(own, other, deleted, unknownHome, deletedHome).forEach { feedback ->
            assertFalse(feedback.id, shared.any { it.feedback.id == feedback.id })
        }
    }

    @Test fun withoutADocumentIdTheHomeCardsDocumentDecides() {
        val liked = likeFirstLine()
        // The card under review has no Library identity: its home card's PDF stem decides.
        assertEquals(listOf(liked.id), candidates(card = cardB(documentIds = emptySet())).map { it.feedback.id })
        assertEquals(emptyList<SharedFeedback>(), candidates(card = FeedbackCardIdentity("card-b", emptySet(), "other-notes")))
        // The anchor's document id is matched against every identity of the card.
        assertEquals(listOf(liked.id), candidates(card = cardB(documentIds = setOf(otherDocumentId, documentId))).map { it.feedback.id })
        assertEquals(emptyList<SharedFeedback>(), candidates(card = cardB(documentIds = setOf(otherDocumentId))))
    }

    @Test fun editingOrDeletingSharedFeedbackFromAnotherCardChangesTheSameRecord() {
        val liked = likeFirstLine(created = Instant.parse("2026-10-20T10:00:00Z"))
        // Card B shows it and edits it there (the editor writes by feedback id).
        val shown = candidates().single().feedback
        val edited = ReviewCardFeedback.edit(shown.id, Polarity.NEGATIVE, "Wrong on card B too", "phone",
            Instant.parse("2026-10-20T11:00:00Z"))!!
        assertEquals(liked.id, edited.id)
        assertEquals(2L, edited.revision)
        assertEquals(homeCardId, edited.cardId)
        assertEquals(liked.createdAt, edited.createdAt)
        assertEquals(1, ReviewCardFeedback.all().size)
        // Card A (its home) and card B both see the edit.
        assertEquals("Wrong on card B too", ReviewCardFeedback.forCard(homeCardId).single().comment)
        assertEquals(Polarity.NEGATIVE, candidates().single().feedback.polarity)
        // Deleting from card B tombstones the record for every card; Undo restores it everywhere.
        val tombstone = ReviewCardFeedback.remove(shown.id, "phone", Instant.parse("2026-10-20T11:01:00Z"))!!
        assertEquals(3L, tombstone.revision)
        assertTrue(tombstone.deleted)
        assertEquals(emptyList<ReviewCardFeedback.Feedback>(), ReviewCardFeedback.forCard(homeCardId))
        assertEquals(emptyList<SharedFeedback>(), candidates())
        val restored = ReviewCardFeedback.undo(tombstone, "phone", Instant.parse("2026-10-20T11:02:00Z"))
        assertEquals(4L, restored.revision)
        assertFalse(restored.deleted)
        assertEquals(homeCardId, restored.cardId)
        assertEquals(listOf(liked.id), candidates().map { it.feedback.id })
        assertNull(ReviewCardFeedback.forCard("card-b").firstOrNull())
    }

    @Test fun ownAndSharedFeedbackAreListedTogetherByPositionInTheAnswer() {
        val own = ReviewCardFeedback.create("card-b", Polarity.NEGATIVE, "", ReviewCardFeedback.NewAnchor(
            "line text", "", "", documentId, madeOn, listOf(Region(1, 0.4, 0.36, 0.9, 0.44))), "phone")
        likeFirstLine()
        val placements = placeFeedback(listOf(own), madeOn, answer(homeRegions)) +
            placeSharedFeedback(candidates(), madeOn, answer(homeRegions), homeRegions)
        assertEquals(listOf(false, true), placements.map { it.shared })
        assertEquals(listOf("first line", "line text"), feedbackListOrder(placements).map { it.feedback.exact })
    }

    @Test fun copyForAgentLabelsSharedFeedback() {
        assertEquals(
            "Answer feedback:\n" +
                "- Liked: \"first line\" — Clear. (from another card: \"What is the first line?\")\n" +
                "- Disliked: \"line text\"\n" +
                "- Liked: \"second\" (from another card)\n",
            reviewCardFeedbackSection(listOf(
                ReviewCardFeedbackLine(false, "line text", "", "2026-10-20T10:01:00.000Z", orphaned = false),
                ReviewCardFeedbackLine(true, "first line", "Clear.", "2026-10-20T10:00:00.000Z", orphaned = false,
                    sharedFrom = "What is  the first\nline?"),
                ReviewCardFeedbackLine(true, "second", "", "2026-10-20T10:02:00.000Z", orphaned = false, sharedFrom = ""),
            )),
        )
    }
}
