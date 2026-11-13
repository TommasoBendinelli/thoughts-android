// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewHistoryEntry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
import com.gitlab.mudlej.MjPdfReader.data.review.reviewCurrentTags
import com.gitlab.mudlej.MjPdfReader.data.review.reviewTagUsageOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The tag sheet's order (most used first), its counts (cards per current tag) and chip text with counts. */
class ReviewTagSheetCountsTest {
    private val label = { tag: String -> if (tag == "correct") "Correct" else if (tag == "wrong") "Wrong" else tag }
    private val g1 = ReviewTagGroups.Group("g1", "Understanding", listOf("correct", "wrong", "fair"))

    @Test fun tagsGoMostUsedFirstThenByLabelIgnoringCase() {
        val sections = listOf(
            ReviewTagGroups.Section(g1, listOf("fair", "wrong", "correct")),
            ReviewTagGroups.Section(null, listOf("zeta", "Beta", "alpha", "notation", "Hard")),
        )
        val usage = mapOf("correct" to 42, "wrong" to 3, "fair" to 3, "notation" to 7, "Hard" to 7)
        val shown = reviewTagSheetSections(sections, usage, label)
        // Built-ins sort by their shown label like any tag: "fair" < "Wrong".
        assertEquals(listOf("correct", "fair", "wrong"), shown[0].tags)
        // Ties among the used ones and among the unused (0) ones are alphabetical, ignoring case.
        assertEquals(listOf("Hard", "notation", "alpha", "Beta", "zeta"), shown[1].tags)
        assertEquals(listOf(g1, null), shown.map { it.group })
    }

    @Test fun otherStaysOnceThereIsAGroupEvenWhenEmpty() {
        val shown = reviewTagSheetSections(listOf(ReviewTagGroups.Section(g1, listOf("correct", "wrong"))), emptyMap(), label)
        assertEquals(listOf(g1, null), shown.map { it.group })
        assertEquals(emptyList<String>(), shown[1].tags)
        val empty = ReviewTagGroups.Group("g2", "Interest", emptyList())
        assertEquals(listOf(g1, empty, null), reviewTagSheetSections(listOf(
            ReviewTagGroups.Section(g1, listOf("correct")), ReviewTagGroups.Section(empty, emptyList()),
            ReviewTagGroups.Section(null, listOf("notation")),
        ), emptyMap(), label).map { it.group })
    }

    @Test fun withoutGroupsOtherIsTheOnlyRow() {
        val shown = reviewTagSheetSections(listOf(ReviewTagGroups.Section(null, listOf("wrong", "correct"))),
            mapOf("wrong" to 1), label)
        assertEquals(listOf(ReviewTagGroups.Section(null, listOf("wrong", "correct"))), shown)
        assertEquals(listOf(ReviewTagGroups.Section(null, emptyList())), reviewTagSheetSections(emptyList(), emptyMap(), label))
    }

    @Test fun chipsShowTheCountIncludingZero() {
        assertEquals("Correct (42)", reviewTagChipText("Correct", 42))
        assertEquals("notation (0)", reviewTagChipText("notation", 0))
        assertEquals("Correct (42)", reviewTagCountText("Correct", 42))
        val long = "a long tag the chip has to shorten for sure"
        assertEquals("$long (3)", reviewTagCountText(long, 3))
    }

    @Test fun aLongLabelShortensButTheCountStays() {
        val long = "a long tag the chip has to shorten for sure"
        // A chip is as wide as its text: with room for it, the whole label shows (no fixed cut).
        assertEquals("$long (1234)", reviewTagChipText(long, 1234))
        assertEquals("$long (1234)", reviewTagChipText(long, 1234) { it.length <= 60 })
        // Wider than a whole row: the label gives way, never the count.
        val short = reviewTagChipText(long, 1234) { it.length <= 30 }
        assertEquals("a long tag the chip ha… (1234)", short)
        val narrow = reviewTagChipText(long, 1234) { it.length <= 14 }
        assertEquals("a long… (1234)", narrow)
        assertEquals("Correct… (5)", reviewTagChipText("Correct horse", 5) { it.length <= 12 })
        assertEquals("C… (5)", reviewTagChipText("Correct", 5) { it.length <= 6 })
        assertEquals("… (5)", reviewTagChipText("Correct", 5) { false })
        assertEquals("Correct (5)", reviewTagChipText("Correct", 5) { it.length <= 11 })
    }

    @Test fun aTagTheCardLacksShowsOneMoreWhileChosen() {
        val recorded = mapOf("insight" to 1, "correct" to 42)
        // The card has no tags yet (never answered, or its last answer had none).
        val current = emptySet<String>()
        val chosen = mutableSetOf<String>()
        assertEquals(1, reviewTagShownCount(recorded, current, chosen, "insight"))
        // Tapping "insight (1)" shows (2); tapping it again shows (1).
        chosen += "insight"
        assertEquals(2, reviewTagShownCount(recorded, current, chosen, "insight"))
        assertEquals("insight (2)", reviewTagChipText("insight", reviewTagShownCount(recorded, current, chosen, "insight")))
        assertEquals(42, reviewTagShownCount(recorded, current, chosen, "correct"))
        chosen -= "insight"
        assertEquals(1, reviewTagShownCount(recorded, current, chosen, "insight"))
        // A tag no card has: (0), then (1) while chosen.
        assertEquals(0, reviewTagShownCount(recorded, current, chosen, "notation"))
        assertEquals(1, reviewTagShownCount(recorded, current, setOf("notation"), "notation"))
    }

    @Test fun aTagTheCardHasStaysWhileChosenAndShowsOneFewerWhenLeftOut() {
        // The card is "correct" now and is among the 42; a review starts with that tag chosen.
        val recorded = mapOf("correct" to 42, "wrong" to 3)
        val current = setOf("correct")
        assertEquals(42, reviewTagShownCount(recorded, current, setOf("correct"), "correct"))
        assertEquals(3, reviewTagShownCount(recorded, current, setOf("correct"), "wrong"))
        // Switching it to wrong: correct loses the card, wrong gains it.
        assertEquals(41, reviewTagShownCount(recorded, current, setOf("wrong"), "correct"))
        assertEquals(4, reviewTagShownCount(recorded, current, setOf("wrong"), "wrong"))
        // Both chosen: the card keeps correct and adds wrong.
        assertEquals(42, reviewTagShownCount(recorded, current, setOf("correct", "wrong"), "correct"))
        assertEquals(4, reviewTagShownCount(recorded, current, setOf("correct", "wrong"), "wrong"))
        // A stale count never goes below zero.
        assertEquals(0, reviewTagShownCount(emptyMap(), current, emptySet(), "correct"))
    }

    @Test fun theCardIsNeverCountedTwice() {
        val start = Instant.parse("2026-10-11T10:00:00Z")
        fun answer(id: String, minute: Long, vararg tags: String) = ReviewHistoryEntry(
            eventId = id, rating = "custom_interval", reviewedAt = start.plusSeconds(60 * minute), originId = "phone",
            revision = 1, updatedAt = null, retractedEventId = null, retracted = false, effective = true,
            pendingSync = false, sourcePath = null, scheduledDueAt = start.plusSeconds(60 * minute + 86400),
            reviewTags = tags.toList(), reviewTagsRecorded = true,
        )
        val other = listOf(answer("o", 1, "insight"))
        val card = listOf(answer("a", 1, "insight"))
        fun usage(vararg cards: List<ReviewHistoryEntry>) = reviewTagUsageOf(cards.toList(), emptyMap())
        val current = reviewCurrentTags(card, emptyMap()).orEmpty().toSet()
        // The card has insight and starts with it chosen: two cards, this one included.
        assertEquals(2, reviewTagShownCount(usage(other, card), current, setOf("insight"), "insight"))
        // Recorded again with insight (Next): still two cards, and the sheet still shows two.
        val after = card + answer("b", 2, "insight")
        assertEquals(mapOf("insight" to 2), usage(other, after))
        assertEquals(2, reviewTagShownCount(usage(other, after), current, setOf("insight"), "insight"))
        // Recorded without it: one card; the next review of this card starts without it.
        val dropped = after + answer("c", 3)
        assertEquals(mapOf("insight" to 1), usage(other, dropped))
        assertEquals(1, reviewTagShownCount(usage(other, dropped), emptySet(), emptySet(), "insight"))
        assertEquals(2, reviewTagShownCount(usage(other, dropped), emptySet(), setOf("insight"), "insight"))
    }

    @Test fun theOrderStaysOnRecordedCountsWhileTagsAreTapped() {
        val sections = listOf(ReviewTagGroups.Section(null, listOf("hard", "notation")))
        val recorded = mapOf("notation" to 2, "hard" to 1)
        val opened = reviewTagSheetSections(sections, recorded, label)
        assertEquals(listOf("notation", "hard"), opened[0].tags)
        // Choosing hard shows it at 2 like notation; ordered by those counts it would jump ahead...
        val chosen = setOf("hard")
        assertEquals(listOf(2, 2), opened[0].tags.map { reviewTagShownCount(recorded, emptySet(), chosen, it) })
        val byShown = sections[0].tags.associateWith { reviewTagShownCount(recorded, emptySet(), chosen, it) }
        assertEquals(listOf("hard", "notation"), reviewTagSheetSections(sections, byShown, label)[0].tags)
        // ...but the sheet orders by the recorded counts it opened with, so the chips stay put.
        assertEquals(opened, reviewTagSheetSections(sections, recorded, label))
    }

    @Test fun aTagNoCardHasIsLeftOutUnlessChosen() {
        val recorded = mapOf("correct" to 54, "memorized" to 1)
        val current = setOf("memorized")
        val chosen = mutableSetOf("memorized")
        val count = { tag: String -> reviewTagShownCount(recorded, current, chosen, tag) }
        val shown = mutableSetOf<String>()
        // "I don't get it yet (0)" and "fair (0)" are left out; the card's own tag shows while chosen.
        assertEquals(listOf("correct", "memorized"),
            reviewTagsToShow(listOf("correct", "memorized", "I don't get it yet", "fair"), count, shown))
        // A hidden tag chosen (through New tag) gets its chip at once, with its count.
        chosen += "fair"
        assertEquals(1, count("fair"))
        assertEquals(listOf("correct", "memorized", "fair"),
            reviewTagsToShow(listOf("correct", "memorized", "I don't get it yet", "fair"), count, shown))
    }

    @Test fun aChipThatDropsToZeroStaysUntilTheSheetCloses() {
        val recorded = mapOf("memorized" to 1)
        val current = setOf("memorized")
        val chosen = mutableSetOf("memorized")
        val count = { tag: String -> reviewTagShownCount(recorded, current, chosen, tag) }
        val shown = mutableSetOf<String>()
        assertEquals(listOf("memorized"), reviewTagsToShow(listOf("memorized"), count, shown))
        // Left out: this card was the only one, so it shows (0), but it keeps its place.
        chosen -= "memorized"
        assertEquals(0, count("memorized"))
        assertEquals(listOf("memorized"), reviewTagsToShow(listOf("memorized"), count, shown))
        // Opened again (a new set of shown tags): gone.
        assertEquals(emptyList<String>(), reviewTagsToShow(listOf("memorized"), count, mutableSetOf()))
    }

    @Test fun newTagPicksAKnownTagIgnoringCaseAndMergedNames() {
        val tags = listOf("correct", "I don't get it yet", "Memorized")
        assertEquals("I don't get it yet", reviewTypedTag("i DON'T get it YET", tags, emptyMap(), label))
        assertEquals("correct", reviewTypedTag("CORRECT", tags, emptyMap(), label))
        // An old name (any case) is the tag it was merged into.
        val merges = mapOf("Memorised" to "Memorized")
        assertEquals("Memorized", reviewTypedTag("memorised", tags, merges, label))
        assertEquals("Memorized", reviewTypedTag("Memorised", emptyList(), merges, label))
        // A new name stays as typed.
        assertEquals("Brand new", reviewTypedTag("Brand new", tags, merges, label))
    }
}
