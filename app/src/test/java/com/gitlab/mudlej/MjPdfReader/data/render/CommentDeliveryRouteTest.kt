// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentDeliveryRouteTest {
    @Test
    fun buildFreeServerNeverRendersCommentDelivery() {
        assertFalse(
            commentDeliveryNeedsRender(
                CommentDeliveryRoute.SOURCE_LINKED,
                buildFreeAnchoringSupported = true,
            ),
        )
        assertFalse(
            commentDeliveryNeedsRender(
                CommentDeliveryRoute.UNLINKED,
                buildFreeAnchoringSupported = true,
            ),
        )
        assertTrue(
            commentDeliveryNeedsRender(
                CommentDeliveryRoute.SOURCE_LINKED,
                buildFreeAnchoringSupported = false,
            ),
        )
    }

    @Test
    fun logicalDeliveryAcknowledgementDoesNotWaitForAnchoring() {
        val root = Files.createTempDirectory("comment-anchor-settled").toFile()
        try {
            val sidecar = root.resolve("manual_comments.jsonl")
            sidecar.writeText(
                """{"id":"comment-1","revision":1,"anchor_status":"needs_anchor"}
                """.trimIndent(),
            )
            val requirement = listOf(
                com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord(
                    "comment-1",
                    1,
                    """{"id":"comment-1","revision":1}""",
                ),
            )
            assertTrue(logicalCommentRecordsSettled(sidecar, requirement))

            sidecar.writeText(
                """{"id":"comment-1","revision":2,"anchor_status":"anchored","anchor_job_id":"job-1"}
                """.trimIndent(),
            )
            assertTrue(logicalCommentRecordsSettled(sidecar, requirement))

            sidecar.writeText(
                """{"id":"comment-1","revision":2,"anchor_status":"needs_anchor","anchor_job_id":"job-1"}
                """.trimIndent(),
            )
            assertTrue(logicalCommentRecordsSettled(sidecar, requirement))

            sidecar.writeText(
                """{"id":"comment-1","revision":2,"deleted":true}
                """.trimIndent(),
            )
            assertTrue(logicalCommentRecordsSettled(sidecar, requirement))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oneUnsettledBatchDoesNotRetainAnAcceptedBatch() {
        val root = Files.createTempDirectory("comment-batch-settled").toFile()
        try {
            root.resolve("ready_comments.jsonl").writeText(
                """{"id":"ready-id","revision":1,"anchor_status":""}
                """.trimIndent(),
            )
            root.resolve("waiting_comments.jsonl").writeText("")
            fun record(id: String) =
                com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord(
                    id,
                    1,
                    """{"id":"$id","revision":1}""",
                )
            val ready = PendingCommentDelivery("ready-batch", "ready.pdf", listOf(record("ready-id")), 1L)
            val waiting = PendingCommentDelivery("waiting-batch", "waiting.pdf", listOf(record("waiting-id")), 2L)

            assertEquals(
                listOf("ready-batch"),
                settledLogicalDeliveryBatchIds(listOf(ready, waiting), root),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun authoritativeLibraryRoutesLinkedAndUnlinkedButRejectsOtherPdfs() {
        val root = Files.createTempDirectory("comment-delivery-route").toFile()
        try {
            val snapshot = SourceLinkedPdfIndexSnapshot(
                available = true,
                libraryKindsByFilename = mapOf(
                    "linked.pdf" to LibraryPdfKind.SOURCE_LINKED,
                    "unlinked.pdf" to LibraryPdfKind.UNLINKED,
                ),
                libraryAvailable = true,
            )

            assertEquals(CommentDeliveryRoute.SOURCE_LINKED, commentDeliveryRoute("linked.pdf", snapshot, root))
            assertEquals(CommentDeliveryRoute.UNLINKED, commentDeliveryRoute("unlinked.pdf", snapshot, root))
            assertEquals(CommentDeliveryRoute.REJECTED, commentDeliveryRoute("legacy.pdf", snapshot, root))
            assertEquals(CommentDeliveryRoute.REJECTED, commentDeliveryRoute("../linked.pdf", snapshot, root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sourceIndexFallbackRoutesOnlyLinkedFiles() {
        val root = Files.createTempDirectory("comment-delivery-source-fallback").toFile()
        try {
            val snapshot = SourceLinkedPdfIndexSnapshot(
                filenames = setOf("linked.pdf"),
                available = true,
            )

            assertEquals(CommentDeliveryRoute.SOURCE_LINKED, commentDeliveryRoute("linked.pdf", snapshot, root))
            assertEquals(CommentDeliveryRoute.REJECTED, commentDeliveryRoute("unlinked.pdf", snapshot, root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unavailableIndexRetriesRatherThanAcceptingAnArbitraryTarget() {
        val root = Files.createTempDirectory("comment-delivery-no-index").toFile()
        try {
            assertEquals(
                CommentDeliveryRoute.INDEX_UNAVAILABLE,
                commentDeliveryRoute("unknown.pdf", SourceLinkedPdfIndexSnapshot(), root),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
