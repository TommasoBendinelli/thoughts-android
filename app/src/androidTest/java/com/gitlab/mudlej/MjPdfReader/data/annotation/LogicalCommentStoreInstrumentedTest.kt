// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.*
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LogicalCommentStoreInstrumentedTest {
    @Test fun createEditReplyDeleteUndoReopenAndExportUseOneState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "test-${TestRunIdentity.id}-logical.pdf"
        val file = File(context.filesDir, filename)
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
            pdf.finishPage(page)
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val uri = Uri.fromFile(file)
        val hash = computeSha256(context, uri)!!
        com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().registerImport(filename, hash, file)
        val root = "test-${TestRunIdentity.id}-root"
        val child = "$root-reply"
        val highlight = "$root-highlight"
        val timestamp = Instant.now().toString()
        val store = LogicalCommentStore(context)
        val service = CommentService(context, { filename })
        suspend fun payload(id: String) = JsonParser.parseString(store.snapshot(filename).logicalComments.single { it.id == id }.payloadJson).asJsonObject
        store.edit(filename, hash, listOf(
            AnnotationEdit.AddPdfComment(0, root, RectF(40f, 100f, 60f, 80f), "Original", "Tester", timestamp, timestamp, needsAction = true),
            AnnotationEdit.AddPdfReply(0, child, root, 0, PDFView.PdfComment.SUBTYPE_TEXT, RectF(40f, 100f, 60f, 80f), "Reply", "Tester", timestamp, timestamp),
            AnnotationEdit.AddPdfMarkupComment(0, highlight, listOf(RectF(80f, 150f, 180f, 130f)), -256, "", "Tester", timestamp, timestamp, "Selected text", needsAction = true),
        ).map { requireNotNull(AnnotationEdit.fromJsonLine(it.toJsonLine())) })
        assertFalse(CommentSidecarStore(context).sidecarFile(uri, filename).exists())
        assertEquals(3, store.snapshot(filename).logicalComments.size)
        val origin = payload(root).get("origin").deepCopy()
        store.edit(filename, hash, listOf(AnnotationEdit.SetPdfCommentContents(0, 0, PDFView.PdfComment.SUBTYPE_TEXT, root, "Edited", timestamp),
            AnnotationEdit.SetPdfCommentMetadata(0, 0, PDFView.PdfComment.SUBTYPE_TEXT, root, CommentPriority.NORMAL, null, timestamp, rect = RectF(200f, 300f, 220f, 280f))))
        assertEquals(origin, payload(root).get("origin"))
        assertTrue(payload(root).get("needs_action").asBoolean)
        assertTrue(payload(highlight).get("needs_action").asBoolean)
        val reopenedFlag = LogicalCommentStore(context).snapshot(filename).logicalComments.single { it.id == root }
        assertTrue(JsonParser.parseString(reopenedFlag.payloadJson).asJsonObject.get("needs_action").asBoolean)
        val clearFlag = AnnotationEdit.SetPdfCommentMetadata(0, 0, PDFView.PdfComment.SUBTYPE_TEXT,
            child, CommentPriority.NORMAL, null, timestamp, needsAction = false)
        val recovered = AnnotationEdit.fromJsonLine(clearFlag.toJsonLine()) as AnnotationEdit.SetPdfCommentMetadata
        assertEquals(false, recovered.needsAction)
        store.edit(filename, hash, listOf(recovered))
        assertFalse(payload(root).get("needs_action").asBoolean)
        assertFalse(payload(child).has("needs_action"))
        assertTrue(payload(highlight).get("needs_action").asBoolean)
        val snapshot = store.snapshot(filename)
        assertEquals("Edited", logicalCommentSheetSnapshot(snapshot.logicalComments, root, filename, hash)!!.threads.single { it.root.id == root }.root.contents)
        assertNotNull(logicalCommentSheetSnapshot(snapshot.logicalComments, highlight, filename, hash))
        val before = logicalCommentOverlaySpecs(snapshot.logicalComments, filename, hash, pageSize = { 400f to 600f })
        assertTrue(before.any { it.commentId == root && it.left == 0.5f })
        val stale = CommentSidecarStore(context).sidecarFile(uri, filename)
        stale.parentFile!!.mkdirs()
        stale.writeText("""{"id":"$root","page":1,"subtype":"Text","contents":"Stale","rect":[40,100,60,80],"revision":99,"deleted":false,"annotation_pdf_sha256":"$hash"}
""")
        val deletion = service.deleteComments(listOf(root))
        assertTrue(deletion.error, deletion.success)
        store.importDocument(uri, filename, hash)
        val reopened = LogicalCommentStore(context).snapshot(filename)
        assertTrue(reopened.logicalComments.single { it.id == root }.deleted)
        assertTrue(reopened.logicalComments.single { it.id == child }.deleted)
        assertFalse(logicalCommentOverlaySpecs(reopened.logicalComments, filename, hash, pageSize = { 400f to 600f }).any { it.commentId == root })
        val comments = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(), reopened.logicalComments, filename, hash).documents.flatMap { it.comments }
        assertEquals(listOf(highlight), comments.map { it.id })
        val exported = CommentShareExporter(context).export(uri, filename)
        val json = context.contentResolver.openInputStream(exported.uri)!!.bufferedReader().use { it.readText() }
        assertFalse(json.contains("Stale"))
        assertFalse(json.contains("Edited"))
        assertEquals(1, exported.commentCount)
        assertTrue(service.undo(deletion.historyToken).success)
        assertEquals("Edited", payload(root).get("contents").asString)
        assertFalse(payload(child).get("deleted").asBoolean)
        val restoredExport = CommentShareExporter(context).export(uri, filename)
        assertEquals(3, restoredExport.commentCount)
        val restoredJson = context.contentResolver.openInputStream(restoredExport.uri)!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val movedThread = restoredJson.getAsJsonArray("threads").map { it.asJsonObject }.single { it.get("id").asString == root }
        assertEquals(JsonParser.parseString("[200.0,300.0,220.0,280.0]"), movedThread.get("pdf_rect"))
        assertEquals(movedThread.get("pdf_rect"), movedThread.getAsJsonArray("replies").single().asJsonObject.get("pdf_rect"))
    }
    /** review_version on the store's writes (spec thoughts-feedback-version-stamps): create, edit, reply; not delete or replay. */
    @Test fun createdAndEditedCommentsRecordThePdfVersion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "test-${TestRunIdentity.id}-stamped.pdf"
        val file = File(context.filesDir, filename)
        val pdf = PdfDocument()
        try {
            pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create()))
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val hash = computeSha256(context, Uri.fromFile(file))!!
        com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().registerImport(filename, hash, file)
        val library = "c".repeat(64)
        val store = LogicalCommentStore(context).apply {
            versionStamper = com.gitlab.mudlej.MjPdfReader.data.review.ReviewVersionStamper(
                { null }, { library }, { _, _ -> false },
                documentFile = { _, stem -> file.takeIf { stem == filename.removeSuffix(".pdf") } },
                librarySourceBuilt = { false },
            )
        }
        val root = "test-${TestRunIdentity.id}-stamped-root"
        val timestamp = Instant.now().toString()
        suspend fun payload(id: String) = JsonParser.parseString(store.snapshot(filename).logicalComments.single { it.id == id }.payloadJson).asJsonObject
        fun stamp(sha: String) = JsonParser.parseString("""{"pdf_sha256":"$sha","source_built":false}""")
        // The reader's save: the hash of the PDF it shows.
        store.edit(filename, hash, listOf(AnnotationEdit.AddPdfComment(0, root, RectF(40f, 100f, 60f, 80f), "Why?", "Tester", timestamp, timestamp)))
        assertEquals(stamp(hash), payload(root).get("review_version"))
        // An edit from the comment list: the document's current version (here the Library's hash).
        store.update(root, filename) { requireNotNull(logicalCommentMutationPayload(it, root, deleted = false, contents = "Why not?")) }
        assertEquals(stamp(library), payload(root).get("review_version"))
        // Undo/redo of that edit replays history: the stamp stays.
        store.update(root, filename, stamp = false) { current ->
            requireNotNull(logicalCommentMutationPayload(current, root, deleted = false, contents = "Why?")).apply {
                add("review_version", stamp(hash))
            }
        }
        assertEquals(stamp(hash), payload(root).get("review_version"))
        // A reply from the comment sheet.
        val reply = "$root-reply"
        store.update(root, filename) { requireNotNull(logicalCommentReplyPayload(it, reply, "Because.", "Tester", timestamp)) }
        assertEquals(stamp(library), payload(reply).get("review_version"))
        // Deleting keeps the stamp the comment had.
        val deletion = CommentService(context, { filename }).deleteComments(listOf(root))
        assertTrue(deletion.error, deletion.success)
        assertEquals(stamp(hash), JsonParser.parseString(store.snapshot(filename).logicalComments.single { it.id == root }.payloadJson)
            .asJsonObject.get("review_version"))
    }
}
