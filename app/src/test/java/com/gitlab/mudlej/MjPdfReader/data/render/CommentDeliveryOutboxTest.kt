// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentDeliveryRecord
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommentDeliveryOutboxTest {

    private lateinit var root: File
    private lateinit var outbox: CommentDeliveryOutbox

    @Before
    fun setUp() {
        root = Files.createTempDirectory("comment-delivery-outbox").toFile()
        outbox = CommentDeliveryOutbox(root) {}
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun batchSurvivesReloadUntilExplicitlyRemoved() {
        val batch = outbox.enqueue(
            "template.pdf",
            listOf(record("comment-a", 2, "new")),
        )

        val restored = CommentDeliveryOutbox(root) {}.load().single()

        assertEquals(batch.batchId, restored.batchId)
        assertEquals("template.pdf", restored.target)
        assertEquals("comment-a", restored.records.single().id)
        assertEquals(2, restored.records.single().revision)
        assertTrue(outbox.hasPendingTarget("template.pdf"))

        outbox.remove(listOf(batch.batchId))

        assertTrue(outbox.load().isEmpty())
        assertFalse(outbox.hasPendingTarget("template.pdf"))
    }

    @Test
    fun newestRecordCoalescingKeepsHighestRevisionPerComment() {
        val records = newestRecords(
            listOf(
                record("comment-a", 1, "old"),
                record("comment-b", 1, "other"),
                record("comment-a", 3, "new"),
                record("comment-a", 2, "middle"),
            )
        )

        assertEquals(listOf("comment-a", "comment-b"), records.map { it.id })
        assertEquals(listOf(3, 1), records.map { it.revision })
    }

    private fun record(
        id: String,
        revision: Int,
        contents: String,
    ): CommentDeliveryRecord {
        return CommentDeliveryRecord(
            id,
            revision,
            """{"id":"$id","revision":$revision,"contents":"$contents"}""",
        )
    }
}
