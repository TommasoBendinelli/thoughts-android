// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** History in the reader menu: the document's versions, newest first, each with its comments. */
class DocumentHistoryTest {
    private val current = "a".repeat(64)
    private val older = "b".repeat(64)
    private val oldest = "c".repeat(64)

    private fun record(id: Int, polarity: String, quote: String, comment: String, sha: String, page: Int, at: String,
                       cardId: String? = null, kind: String? = null, deleted: Boolean = false) = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("feedback_id", "00000000-0000-4000-8000-0000000000${"%02d".format(id)}")
        cardId?.let { addProperty("card_id", it) }
        addProperty("polarity", polarity)
        kind?.let { addProperty("kind", it) }
        addProperty("comment", comment)
        addProperty("revision", 1)
        addProperty("created_at", at)
        addProperty("updated_at", at)
        addProperty("deleted", deleted)
        add("anchor", JsonObject().apply {
            addProperty("version", 1)
            add("quote", JsonObject().apply { addProperty("exact", quote) })
            add("pdf", JsonObject().apply {
                addProperty("document_id", "78f95f08-fb36-461a-91ea-cbe67d46872f")
                addProperty("pdf_sha256", sha)
                add("regions", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("page", page)
                        add("rect", JsonArray().apply { listOf(0.1, 0.1, 0.5, 0.2).forEach { add(it) } })
                    })
                })
            })
        })
    }.let { if (cardId == null) DocumentFeedback.parse(it)!! else ReviewCardFeedback.parse(it)!! }

    @Test fun versionsAreListedNewestFirstWithTheirComments() {
        val documentFeedback = listOf(
            record(1, "negative", "remove this", "remove this!", oldest, 3, "2026-10-31T16:47:25Z"),
            record(2, "negative", "In more detail,  the batch", "Do we need\nall this text?", older, 7, "2026-11-10T00:36:24Z"),
            record(3, "neutral", "the bubble", "Why (p-1)/m?", older, 8, "2026-11-10T00:40:00Z", kind = "question"),
            record(4, "positive", "deleted", "", older, 2, "2026-11-10T00:41:00Z", deleted = true),
        )
        val cardFeedback = listOf(record(5, "negative", "stage.", "", current, 7, "2026-11-10T00:50:00Z", cardId = "card-1"))
        val history = documentHistory(current, documentFeedback, cardFeedback)
        assertEquals(listOf(current, older, oldest), history.map { it.sha256 })
        assertEquals(listOf(true, false, false), history.map { it.current })
        assertEquals(Instant.parse("2026-11-10T00:36:24Z"), history[1].firstAt)
        assertEquals(listOf(HistoryComment("Card answer: Disliked", "“stage.”", 7, false, "2026-11-10T00:50:00Z",
            ReviewCardFeedback.Region(7, 0.1, 0.1, 0.5, 0.2))), history[0].comments)
        assertEquals(listOf("Disliked" to "Do we need all this text?", "Question" to "Why (p-1)/m?"),
            history[1].comments.map { it.label to it.text })
        assertEquals(listOf("Disliked" to "remove this!"), history[2].comments.map { it.label to it.text })
    }

    @Test fun theCurrentVersionIsListedEvenWithoutComments() {
        val history = documentHistory(current, emptyList(), emptyList())
        assertEquals(listOf(HistoryVersion(current, true, null, emptyList())), history)
    }
}
