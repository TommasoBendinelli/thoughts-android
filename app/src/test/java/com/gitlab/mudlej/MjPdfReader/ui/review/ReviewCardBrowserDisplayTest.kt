// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.graphics.RectF
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserState
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The card browser's "N of M cards shown" count always counts the cards on screen. */
class ReviewCardBrowserDisplayTest {

    @Test fun countChangesOnlyWhenTheListShowsTheNewCards() {
        val list = DeferredListAdapter()
        val counts = mutableListOf<Int>()
        val display = ReviewCardBrowserDisplay(list::submit)

        // The first list shows at once, as the adapter shows a list into an empty browser.
        display.show(result(cards("a", "b", "c"))) { counts += it.cards.size }
        assertEquals(listOf(3), counts)
        assertEquals(ids(list.onScreen), ids(display.shownCards))

        // A Sync ends with two more cards: the adapter is still comparing the lists.
        display.show(result(cards("a", "b", "c", "d", "e"))) { counts += it.cards.size }
        assertEquals(3, list.onScreen.size)
        assertEquals(listOf(3), counts)
        assertEquals(3, display.shown?.cards?.size)
        assertEquals(ids(list.onScreen), ids(display.shownCards))

        list.finishComparing()
        assertEquals(5, list.onScreen.size)
        assertEquals(listOf(3, 5), counts)
        assertEquals(ids(list.onScreen), ids(display.shownCards))
    }

    @Test fun aListReplacedBeforeItShowsNeverShowsItsCount() {
        val list = DeferredListAdapter()
        val counts = mutableListOf<Int>()
        val display = ReviewCardBrowserDisplay(list::submit)
        display.show(result(cards("a"))) { counts += it.cards.size }

        // Two loads during a Sync, the second before the first was shown.
        display.show(result(cards("a", "b", "c", "d"))) { counts += it.cards.size }
        display.show(result(cards("a", "b"), totalInScope = 4)) { counts += it.cards.size }
        assertEquals(listOf(1), counts)
        assertEquals(ids(list.onScreen), ids(display.shownCards))

        list.finishComparing()
        assertEquals(listOf(1, 2), counts)
        assertEquals(listOf("a", "b"), ids(list.onScreen))
        assertEquals(ids(list.onScreen), ids(display.shownCards))
        assertEquals(4, display.shown?.totalInScope)
    }

    @Test fun aFailedLoadDropsTheListStillOnItsWay() {
        val list = DeferredListAdapter()
        val counts = mutableListOf<Int>()
        val display = ReviewCardBrowserDisplay(list::submit)
        display.show(result(cards("a", "b"))) { counts += it.cards.size }
        display.show(result(cards("a", "b", "c"))) { counts += it.cards.size }

        display.clear()
        assertNull(display.shown)
        assertEquals(emptyList<String>(), ids(display.shownCards))

        list.finishComparing()
        assertEquals(listOf(2), counts)
        assertEquals(emptyList<String>(), ids(list.onScreen))
        assertNull(display.shown)

        // The next load shows again, with its own count.
        display.show(result(cards("d"))) { counts += it.cards.size }
        list.finishComparing()
        assertEquals(listOf(2, 1), counts)
        assertEquals(ids(list.onScreen), ids(display.shownCards))
    }

    /**
     * Shows lists as RecyclerView's ListAdapter does: the first list at once, a later one only
     * after it was compared with the shown one off the main thread, and only the latest one.
     */
    private class DeferredListAdapter {
        var onScreen: List<ReviewBrowserCard> = emptyList()
            private set
        private var hasList = false
        private var latest = 0
        private val comparing = ArrayDeque<() -> Unit>()

        fun submit(cards: List<ReviewBrowserCard>, shown: Runnable) {
            val submission = ++latest
            if (!hasList) {
                hasList = true
                onScreen = cards
                shown.run()
                return
            }
            comparing += {
                if (submission == latest) {
                    onScreen = cards
                    shown.run()
                }
            }
        }

        fun finishComparing() {
            while (comparing.isNotEmpty()) comparing.removeFirst()()
        }
    }

    /** Cards compare by id: a card's own equality reaches Android geometry the JVM tests lack. */
    private fun ids(cards: List<ReviewBrowserCard>) = cards.map { it.card.id }

    private fun result(cards: List<ReviewBrowserCard>, totalInScope: Int = cards.size) =
        ReviewCardBrowserResult(cards, totalInScope)

    private fun cards(vararg ids: String) = ids.map { id ->
        ReviewBrowserCard(
            card = ReviewCard(id = id, status = "approved", pdfStem = "deck", pdfSha256 = "a".repeat(64), page = 1,
                pageVisualSha256 = "b".repeat(64), question = "Question $id", rect = RectF(), schedule = null,
                reanchorState = "ok"),
            state = ReviewBrowserState.NEW, dueAt = null, intervalDays = 0, reviewCount = 0, lapseCount = 0,
            lastRating = null, lastReviewedAt = null, createdAt = null, updatedAt = null, revision = null,
            sourcePath = null, pendingSync = false, deleteEventId = null, history = emptyList(),
        )
    }
}
