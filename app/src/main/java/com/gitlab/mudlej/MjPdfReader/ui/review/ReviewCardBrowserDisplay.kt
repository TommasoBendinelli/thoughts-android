// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import com.gitlab.mudlej.MjPdfReader.data.review.ReviewBrowserCard

/**
 * The card browser's list and its "N of M cards shown" count, changed together.
 *
 * The list adapter compares a new list with the shown one off the main thread and shows it only
 * when that is done, a moment later for a long list (after a Sync, say). A count set when the
 * list was handed over showed the new number over the previous cards until then. [show] applies
 * the count, and whatever else follows the shown cards, when the adapter shows that list; until
 * then the previous list keeps its own count. A list replaced before it is shown never shows.
 */
internal class ReviewCardBrowserDisplay(
    /** The adapter's submitList: shows the list, then runs the callback, unless a newer list came first. */
    private val submitList: (List<ReviewBrowserCard>, Runnable) -> Unit,
) {
    private var requested = 0L

    /** The result whose cards are on screen; null before the first list and after [clear]. */
    var shown: ReviewCardBrowserResult? = null
        private set

    val shownCards: List<ReviewBrowserCard> get() = shown?.cards.orEmpty()

    fun show(result: ReviewCardBrowserResult, onShown: (ReviewCardBrowserResult) -> Unit) {
        val request = ++requested
        submitList(result.cards, Runnable {
            if (request != requested) return@Runnable
            shown = result
            onShown(result)
        })
    }

    /** Empties the list, e.g. when the cards could not be read; a list still on its way never shows. */
    fun clear() {
        requested += 1
        shown = null
        submitList(emptyList(), Runnable {})
    }
}
