// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentShareExporterTest {

    @Test
    fun jsonIsReadableAndNestsRepliesWithSourceContext() {
        val root = comment(
            id = "root",
            contents = "Please clarify the claim.",
            pageIndex = 1,
            subtype = "Highlight",
            selectedText = "The model always converges.",
            author = "Alex",
            sourceFile = "sections/results.tex",
            sourceLine = 42,
            sourceContext = "The model always converges under these assumptions.",
            anchorStatus = "anchored",
            anchorConfidence = "exact",
            created = "D:20260809120000+02'00'",
            modified = "2026-08-09T10:05:00Z",
            priority = CommentPriority.HIGH,
            pdfRect = listOf(10f, 20f, 30f, 40f),
        )
        val reply = comment(
            id = "reply",
            parentId = "root",
            contents = "Added the missing qualification.",
            pageIndex = 1,
            activityMillis = 2,
        )

        val output = buildCommentShareJson(
            documentName = "results",
            pdfSha256 = "abc123",
            exportedAt = Instant.parse("2026-08-09T11:00:00Z"),
            comments = listOf(reply, root),
        )
        val json = JsonParser.parseString(output).asJsonObject
        val document = json.getAsJsonObject("document")
        val thread = json.getAsJsonArray("threads")[0].asJsonObject
        val source = thread.getAsJsonObject("source")

        assertEquals("thoughts-comments", json.get("format").asString)
        assertEquals(1, json.get("schema_version").asInt)
        assertEquals("results.pdf", document.get("filename").asString)
        assertEquals("abc123", document.get("sha256").asString)
        assertEquals("one-based", json.get("page_numbering").asString)
        assertEquals(1, json.get("thread_count").asInt)
        assertEquals(2, json.get("comment_count").asInt)
        assertEquals(2, thread.get("page").asInt)
        assertEquals("highlight", thread.get("type").asString)
        assertEquals("high", thread.get("priority").asString)
        assertEquals(4, thread.getAsJsonArray("pdf_rect").size())
        assertEquals("sections/results.tex", source.get("file").asString)
        assertEquals(42, source.get("line").asInt)
        assertEquals("exact", source.get("anchor_confidence").asString)
        assertEquals("reply", thread.getAsJsonArray("replies")[0].asJsonObject.get("id").asString)
        assertTrue(output.contains("\n  \"document\""))
        assertFalse(output.contains("\"revision\""))
        assertFalse(output.contains("\"deleted\""))
        assertFalse(output.contains("\"origin_id\""))
        assertFalse(output.contains("source_pdf"))
    }

    @Test
    fun zeroCommentsProducesAValidEmptyExport() {
        val json = JsonParser.parseString(
            buildCommentShareJson(
                documentName = "empty.pdf",
                pdfSha256 = "",
                exportedAt = Instant.EPOCH,
                comments = emptyList(),
            )
        ).asJsonObject

        assertEquals(0, json.get("thread_count").asInt)
        assertEquals(0, json.get("comment_count").asInt)
        assertEquals(0, json.getAsJsonArray("threads").size())
        assertFalse(json.getAsJsonObject("document").has("sha256"))
    }

    @Test
    fun orphanReplyRemainsVisibleAsItsOwnThread() {
        val json = JsonParser.parseString(
            buildCommentShareJson(
                documentName = "orphan.pdf",
                pdfSha256 = "hash",
                exportedAt = Instant.EPOCH,
                comments = listOf(
                    comment(
                        id = "orphan",
                        parentId = "missing-parent",
                        contents = "Still useful context.",
                    )
                ),
            )
        ).asJsonObject
        val thread = json.getAsJsonArray("threads")[0].asJsonObject

        assertEquals(1, json.get("thread_count").asInt)
        assertEquals("orphan", thread.get("id").asString)
        assertEquals("missing-parent", thread.get("orphaned_parent_id").asString)
        assertEquals(0, thread.getAsJsonArray("replies").size())
    }

    @Test
    fun rangeFiltersAndRemapsCommentsWhileKeepingOriginalPages() {
        val selectedRoot = comment(
            id = "selected-root",
            contents = "Root on original page five.",
            pageIndex = 4,
        )
        val selectedReply = comment(
            id = "selected-reply",
            parentId = selectedRoot.id,
            contents = "Reply on original page six.",
            pageIndex = 5,
        )
        val outsideParent = comment(
            id = "outside-parent",
            contents = "Parent outside the range.",
            pageIndex = 1,
        )
        val selectedOrphan = comment(
            id = "selected-orphan",
            parentId = outsideParent.id,
            contents = "Reply inside the range.",
            pageIndex = 4,
            activityMillis = 3,
        )
        val range = PdfPageSelection.Range(4, 5)
        val selected = selectCommentsForShare(
            listOf(outsideParent, selectedReply, selectedOrphan, selectedRoot),
            range,
        )

        assertEquals(3, selected.size)
        assertFalse(selected.any { it.id == outsideParent.id })

        val json = JsonParser.parseString(
            buildCommentShareJson(
                documentName = "paper.pages-5-6.pdf",
                pdfSha256 = "subset-hash",
                exportedAt = Instant.EPOCH,
                comments = selected,
                pageSelection = range,
            )
        ).asJsonObject
        val threads = json.getAsJsonArray("threads")
        val root = threads.map { it.asJsonObject }
            .first { it.get("id").asString == selectedRoot.id }
        val reply = root.getAsJsonArray("replies")[0].asJsonObject
        val orphan = threads.map { it.asJsonObject }
            .first { it.get("id").asString == selectedOrphan.id }

        assertEquals("paper.pages-5-6.pdf", json.getAsJsonObject("document").get("filename").asString)
        assertEquals(1, root.get("page").asInt)
        assertEquals(5, root.get("original_page").asInt)
        assertEquals(2, reply.get("page").asInt)
        assertEquals(6, reply.get("original_page").asInt)
        assertEquals(1, orphan.get("page").asInt)
        assertEquals(5, orphan.get("original_page").asInt)
        assertEquals(outsideParent.id, orphan.get("orphaned_parent_id").asString)
        assertEquals(2, json.get("thread_count").asInt)
        assertEquals(3, json.get("comment_count").asInt)
    }

    private fun comment(
        id: String,
        parentId: String = "",
        contents: String,
        pageIndex: Int = 0,
        subtype: String = "Text",
        selectedText: String = "",
        author: String = "",
        sourceFile: String = "",
        sourceLine: Int = 0,
        sourceContext: String = "",
        anchorStatus: String = "",
        anchorConfidence: String = "",
        created: String = "",
        modified: String = "",
        activityMillis: Long = 1,
        priority: CommentPriority = CommentPriority.NORMAL,
        pdfRect: List<Float> = emptyList(),
    ) = OverviewComment(
        documentStem = "results",
        id = id,
        parentId = parentId,
        contents = contents,
        selectedText = selectedText,
        author = author,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
        anchorStatus = anchorStatus,
        pageIndex = pageIndex,
        subtype = subtype,
        created = created,
        modified = modified,
        activityMillis = activityMillis,
        priority = priority,
        sourceContext = sourceContext,
        anchorConfidence = anchorConfidence,
        pdfRect = pdfRect,
    )
}
