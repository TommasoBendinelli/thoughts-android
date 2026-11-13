// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReviewCommentMergeTest {
    @Test
    fun `same review comment unions every PDF that contains it`() {
        val review = review("same comment")
        val first = mergeReviewCommentDocuments(null, review, "concepts.pdf")

        val merged = mergeReviewCommentDocuments(first, review, "course-notes.pdf")

        assertEquals(
            listOf("concepts.pdf", "course-notes.pdf"),
            merged.getAsJsonArray("pdf_filenames").map { it.asString },
        )
    }

    @Test
    fun `different payloads with the same review id still fail closed`() {
        val first = mergeReviewCommentDocuments(null, review("first"), "concepts.pdf")

        assertThrows(IllegalStateException::class.java) {
            mergeReviewCommentDocuments(first, review("different"), "course-notes.pdf")
        }
    }

    private fun review(contents: String) = JsonParser.parseString(
        """
        {
          "id": "abd3f4ed-28c9-4c9e-b09c-85f7861572bf",
          "source_file": "docs-concepts/tex/source/distillation.tex",
          "source_sha256": "e422a9f053226586a966a443b6a844e046668d84d43f5769025f539d871a0779",
          "contents": "$contents"
        }
        """.trimIndent(),
    ).asJsonObject
}
