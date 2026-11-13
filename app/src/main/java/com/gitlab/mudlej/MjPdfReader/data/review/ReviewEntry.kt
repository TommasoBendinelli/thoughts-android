// Written for Thoughts. License is GPLv3.
package com.gitlab.mudlej.MjPdfReader.data.review

/**
 * How the review that gave an answer was started, recorded with every answer (never with a
 * deletion) as "review_entry", the same field the Mac writes: [DUE_QUEUE] when the card came up
 * among the due cards, [REVIEW_NOW] when the person picked this one card to review, due or not.
 * Events without the field come from older clients.
 */
object ReviewEntry {
    const val FIELD = "review_entry"
    const val DUE_QUEUE = "due_queue"
    const val REVIEW_NOW = "review_now"

    fun of(singleCardSession: Boolean): String = if (singleCardSession) REVIEW_NOW else DUE_QUEUE
}
