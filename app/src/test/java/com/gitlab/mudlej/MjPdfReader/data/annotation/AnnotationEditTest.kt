// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.graphics.RectF
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationEditTest {

    @Test
    fun strikethroughJournalPreservesSubtypeAndLegacyHighlightsStillLoad() {
        val edit = AnnotationEdit.AddPdfMarkupComment(0, "strike", listOf(RectF(1f, 4f, 3f, 2f)),
            0xFFD32F2F.toInt(), "", "author", "created", "modified", subtype = 3)
        val restored = AnnotationEdit.fromJsonLine(edit.toJsonLine()) as AnnotationEdit.AddPdfMarkupComment
        assertEquals(3, restored.subtype)
        val legacy = JsonParser.parseString(edit.toJsonLine()).asJsonObject.apply { remove("subtype") }
        assertEquals(2, (AnnotationEdit.fromJsonLine(legacy.toString()) as AnnotationEdit.AddPdfMarkupComment).subtype)
    }

    @Test
    fun popupCommentsDefaultToSharedBrightYellow() {
        val edit = AnnotationEdit.AddPdfComment(
            0,
            "note",
            RectF(1f, 4f, 3f, 2f),
            "text",
            "author",
            "created",
            "modified",
        )
        assertEquals(0xFFFFFF00.toInt(), edit.color)
    }

    @Test
    fun onlyPdfCommentEditsUseTheAutosaveSidecar() {
        val rect = RectF(1f, 4f, 3f, 2f)
        val commentEdits = listOf(
            AnnotationEdit.AddPdfComment(0, "note", rect, "text", "author", "created", "modified"),
            AnnotationEdit.AddPdfMarkupComment(
                0,
                "highlight",
                listOf(rect),
                0xFFFFFF00.toInt(),
                "text",
                "author",
                "created",
                "modified",
            ),
            AnnotationEdit.AddPdfReply(
                0,
                "reply",
                "note",
                0,
                0,
                rect,
                "reply text",
                "author",
                "created",
                "modified",
            ),
            AnnotationEdit.SetPdfCommentContents(0, 0, 0, "note", "updated", "modified"),
            AnnotationEdit.SetPdfCommentMetadata(
                0,
                0,
                0,
                "note",
                CommentPriority.HIGH,
                null,
                "modified",
            ),
            AnnotationEdit.DeletePdfComment(0, 0, 0, "note"),
        )

        commentEdits.forEach { assertTrue(it.isPdfCommentEdit()) }
        assertFalse(AnnotationEdit.Add(0, "legacy", listOf(rect), 0, "note").isPdfCommentEdit())
        assertFalse(AnnotationEdit.SetFieldText(0, 0, "field", "value").isPdfCommentEdit())
        assertFalse(AnnotationEdit.AddSignature(0, rect, emptyList(), 0, 1f).isPdfCommentEdit())
    }

    @Test
    fun overviewMetadataJournalRoundTripsPriorityAndGooglePlace() {
        val location = CommentLocation(
            label = "Bibliothèque nationale de France",
            latitude = 48.8335,
            longitude = 2.3758,
            googlePlaceId = "google-place-id",
            source = CommentLocation.SOURCE_GOOGLE_MAPS,
            accuracyMeters = 12.5,
            confirmedAt = "2026-08-02T12:00:00Z",
        )
        val add = AnnotationEdit.AddPdfComment(
            0,
            "note",
            RectF(1f, 4f, 3f, 2f),
            "text",
            "author",
            "created",
            "modified",
            CommentPriority.HIGH,
            location,
        )
        val metadata = AnnotationEdit.SetPdfCommentMetadata(
            0,
            0,
            0,
            "note",
            CommentPriority.HIGH,
            location,
            "modified",
            color = 0xFF64B5F6.toInt(),
            rect = RectF().apply {
                left = 5f
                top = 8f
                right = 7f
                bottom = 6f
            },
        )

        val restoredAdd = AnnotationEdit.fromJsonLine(add.toJsonLine())
            as AnnotationEdit.AddPdfComment
        val restoredMetadata = AnnotationEdit.fromJsonLine(metadata.toJsonLine())
            as AnnotationEdit.SetPdfCommentMetadata

        assertEquals(CommentPriority.HIGH, restoredAdd.priority)
        assertEquals(location, restoredAdd.location)
        assertEquals(CommentPriority.HIGH, restoredMetadata.priority)
        assertEquals(location, restoredMetadata.location)
        assertEquals(0xFF64B5F6.toInt(), restoredMetadata.color)
        assertEquals(5f, restoredMetadata.rect?.left)
        assertEquals(8f, restoredMetadata.rect?.top)
        assertEquals(7f, restoredMetadata.rect?.right)
        assertEquals(6f, restoredMetadata.rect?.bottom)
        assertTrue(metadata.isPdfCommentEdit())
    }

    @Test
    fun oldMetadataJournalDefaultsMissingColorAndRect() {
        val restored = AnnotationEdit.fromJsonLine(
            """{"op":"setPdfCommentMetadata","page":0,"annotationIndex":1,"subtype":1,"name":"note","priority":"normal","modified":"now"}""",
        ) as AnnotationEdit.SetPdfCommentMetadata

        assertEquals(null, restored.color)
        assertEquals(null, restored.rect)
        assertEquals(CommentPriority.NORMAL, restored.priority)
    }

    @Test
    fun highlightedCommentJournalRecordPreservesItsOperationAndMetadata() {
        val firstRect = RectF().apply {
            left = 10f
            top = 40f
            right = 80f
            bottom = 20f
        }
        val secondRect = RectF().apply {
            left = 10f
            top = 18f
            right = 55f
            bottom = 8f
        }
        val location = CommentLocation(
            label = "Bibliothèque nationale de France",
            latitude = 48.8335,
            longitude = 2.3758,
        )
        val edit = AnnotationEdit.AddPdfMarkupComment(
            page = 3,
            name = "highlight-comment-id",
            rects = listOf(firstRect, secondRect),
            color = 0xFFFFF176.toInt(),
            contents = "Highlighted comment",
            author = "Alex Example",
            createdDate = "D:20260725110000",
            modifiedDate = "D:20260725110000",
            priority = CommentPriority.HIGH,
            location = location,
        )

        val line = edit.toJsonLine()
        val json = JsonParser.parseString(line).asJsonObject
        assertEquals("addPdfHighlightComment", json.get("op").asString)
        assertEquals(2, json.getAsJsonArray("rects").size())

        val restored = AnnotationEdit.fromJsonLine(line)
        assertTrue(restored is AnnotationEdit.AddPdfMarkupComment)
        restored as AnnotationEdit.AddPdfMarkupComment
        assertEquals(3, restored.page)
        assertEquals("highlight-comment-id", restored.name)
        assertEquals(2, restored.rects.size)
        assertEquals(0xFFFFF176.toInt(), restored.color)
        assertEquals("Highlighted comment", restored.contents)
        assertEquals("Alex Example", restored.author)
        assertEquals(CommentPriority.HIGH, restored.priority)
        assertEquals(location, restored.location)
    }

    @Test
    fun oldHighlightedCommentJournalDefaultsMissingMetadata() {
        val restored = AnnotationEdit.fromJsonLine(
            """{"op":"addPdfHighlightComment","page":0,"name":"legacy","rects":[[1,4,3,2]],"color":-256,"contents":"legacy","author":"author","created":"created","modified":"modified","selectedText":"quote"}""",
        ) as AnnotationEdit.AddPdfMarkupComment

        assertEquals(CommentPriority.NORMAL, restored.priority)
        assertEquals(null, restored.location)
    }

    @Test
    fun crossPageHighlightedCommentJournalPreservesOrderedFragmentsAndQuote() {
        val first = RectF(10f, 40f, 80f, 20f)
        val second = RectF(12f, 42f, 70f, 22f)
        val edit = AnnotationEdit.AddPdfMarkupComment(
            page = 3,
            name = "cross-page",
            rects = listOf(first),
            color = 0xFFFFF176.toInt(),
            contents = "Review this span",
            author = "Alex Example",
            createdDate = "D:20260728120000+02'00'",
            modifiedDate = "D:20260728120000+02'00'",
            selectedText = "end of page\nstart of next page",
            fragments = listOf(
                AnnotationFragment(3, listOf(first), "end of page"),
                AnnotationFragment(4, listOf(second), "start of next page"),
            ),
        )

        val restored = AnnotationEdit.fromJsonLine(edit.toJsonLine())
            as AnnotationEdit.AddPdfMarkupComment

        assertEquals("end of page\nstart of next page", restored.selectedText)
        assertEquals(listOf(3, 4), restored.fragments.map { it.page })
        assertEquals(
            listOf("end of page", "start of next page"),
            restored.fragments.map { it.selectedText },
        )
        val restoredRect = restored.fragments.last().rects.single()
        assertEquals(second.left, restoredRect.left)
        assertEquals(second.top, restoredRect.top)
        assertEquals(second.right, restoredRect.right)
        assertEquals(second.bottom, restoredRect.bottom)
    }

    @Test
    fun everyCommentMutationProducesSourceDeliveryRequirements() {
        val rect = RectF(1f, 4f, 3f, 2f)
        val edits = listOf(
            AnnotationEdit.AddPdfComment(
                0,
                "note",
                rect,
                "text",
                "author",
                "created",
                "modified",
            ),
            AnnotationEdit.AddPdfMarkupComment(
                0,
                "highlight",
                listOf(rect),
                0xFFFFFF00.toInt(),
                "text",
                "author",
                "created",
                "modified",
            ),
            AnnotationEdit.AddPdfReply(
                0,
                "reply",
                "note",
                0,
                0,
                rect,
                "reply",
                "author",
                "created",
                "modified",
            ),
            AnnotationEdit.SetPdfCommentContents(
                0,
                0,
                0,
                "edited-note",
                "edited",
                "modified",
            ),
            AnnotationEdit.DeletePdfComment(0, 0, 0, "deleted-note"),
        )

        assertEquals(
            listOf("note", "highlight", "reply", "edited-note", "deleted-note"),
            commentIdsRequiringAnchor(edits),
        )
    }
}
