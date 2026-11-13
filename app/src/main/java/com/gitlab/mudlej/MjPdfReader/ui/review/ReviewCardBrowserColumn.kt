// Written for Thoughts. License is GPLv3.
package com.gitlab.mudlej.MjPdfReader.ui.review

import androidx.annotation.StringRes
import com.gitlab.mudlej.MjPdfReader.R

enum class ReviewCardBrowserColumn(@StringRes val labelRes: Int) {
    CREATED(R.string.review_card_browser_created),
    LAST_REVIEWED(R.string.review_card_browser_last_reviewed),
    QUESTION(R.string.review_card_browser_question),
    DUE(R.string.review_card_browser_due),
    DECK(R.string.review_card_browser_deck),
    DOCUMENT(R.string.review_card_browser_document),
    PAGE(R.string.review_card_browser_page),
    SOURCE(R.string.review_card_browser_source),
    STATE(R.string.review_card_browser_state),
    REPETITIONS(R.string.review_card_browser_repetitions);

    companion object {
        val defaults = setOf(CREATED, QUESTION, DUE, DECK, DOCUMENT)
    }
}

internal fun ReviewCardBrowserColumn.sort(): ReviewCardBrowserSort = when (this) {
    ReviewCardBrowserColumn.CREATED -> ReviewCardBrowserSort.CREATED
    ReviewCardBrowserColumn.LAST_REVIEWED -> ReviewCardBrowserSort.LAST_REVIEWED
    ReviewCardBrowserColumn.QUESTION -> ReviewCardBrowserSort.QUESTION
    ReviewCardBrowserColumn.DUE -> ReviewCardBrowserSort.DUE
    ReviewCardBrowserColumn.DECK -> ReviewCardBrowserSort.PROJECT
    ReviewCardBrowserColumn.DOCUMENT -> ReviewCardBrowserSort.DECK
    ReviewCardBrowserColumn.PAGE -> ReviewCardBrowserSort.PAGE
    ReviewCardBrowserColumn.SOURCE -> ReviewCardBrowserSort.SOURCE
    ReviewCardBrowserColumn.STATE -> ReviewCardBrowserSort.STATE
    ReviewCardBrowserColumn.REPETITIONS -> ReviewCardBrowserSort.CARD_STATUS
}

/**
 * Width a column needs for [text]: its longest unbreakable word, or the whole text up to [target]
 * so short values stay on one line. Words break at spaces and after a hyphen before a letter.
 */
internal fun reviewColumnTextWidth(text: String, measure: (String) -> Float, target: Float): Float {
    val lines = text.lines()
    val word = lines.flatMap { it.split(WORD_BREAK) }.filter(String::isNotEmpty).maxOfOrNull(measure) ?: 0f
    val line = lines.maxOfOrNull(measure) ?: 0f
    return maxOf(word, minOf(line, target))
}

private val WORD_BREAK = Regex("\\s+|(?<=-)(?=\\p{L})")
