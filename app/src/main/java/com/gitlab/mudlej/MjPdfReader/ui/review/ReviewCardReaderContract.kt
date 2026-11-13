// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.content.Intent

/** Intent options used when the Review card browser opens the PDF reader. */
object ReviewCardReaderContract {
    const val EXTRA_PAGE_INDEX =
        "com.gitlab.mudlej.MjPdfReader.extra.REVIEW_BROWSER_PAGE_INDEX"
    const val EXTRA_SINGLE_CARD_SESSION =
        "com.gitlab.mudlej.MjPdfReader.extra.REVIEW_SINGLE_CARD_SESSION"

    fun openPage(intent: Intent, pageIndex: Int): Intent = intent.apply {
        putExtra(EXTRA_PAGE_INDEX, pageIndex.coerceAtLeast(0))
    }

    /** The card opens in Edit card to be placed again on the PDF shown (Card details). */
    const val EXTRA_PLACE_AGAIN =
        "com.gitlab.mudlej.MjPdfReader.extra.REVIEW_PLACE_AGAIN"

    fun singleCardSession(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_SINGLE_CARD_SESSION, true)
    }

    fun placeAgain(intent: Intent): Intent = singleCardSession(intent).apply {
        putExtra(EXTRA_PLACE_AGAIN, true)
    }
}

internal fun boundedReviewBrowserPageIndex(requestedPageIndex: Int, pageCount: Int): Int? {
    if (pageCount <= 0) return null
    return requestedPageIndex.coerceIn(0, pageCount - 1)
}

internal fun finishAfterReviewBrowserRating(singleCardSession: Boolean): Boolean =
    singleCardSession
