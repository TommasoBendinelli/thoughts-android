// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMetadataFormatterTest {

    @Test
    fun keepsCommentDateAndUnresolvedThreadCountOnOneLine() {
        val text = formatCompactLibraryMetadata(
            commentTimestamp = "11 Jul",
            unresolvedThreadCount = 7,
            formatSummary = { comment, unresolved -> "Comment $comment · $unresolved" },
            formatUnresolvedThreads = { count -> "$count unresolved threads" },
        )

        assertEquals("Comment 11 Jul · 7 unresolved threads", text)
    }

    @Test
    fun preservesNeverFallback() {
        val text = formatCompactLibraryMetadata(
            commentTimestamp = "Never",
            unresolvedThreadCount = 0,
            formatSummary = { comment, unresolved -> "Comment $comment · $unresolved" },
            formatUnresolvedThreads = { count -> "$count unresolved threads" },
        )

        assertEquals("Comment Never · 0 unresolved threads", text)
    }

    @Test
    fun delegatesSingularThreadFormatting() {
        val text = formatCompactLibraryMetadata(
            commentTimestamp = "5 days ago",
            unresolvedThreadCount = 1,
            formatSummary = { comment, unresolved -> "Comment $comment · $unresolved" },
            formatUnresolvedThreads = { count ->
                if (count == 1) "1 unresolved thread" else "$count unresolved threads"
            },
        )

        assertEquals("Comment 5 days ago · 1 unresolved thread", text)
    }
}
