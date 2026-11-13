// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.ui.reader.annotation.forReadingDocument
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shockwave.pdfium.PdfiumCore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectReadingInstrumentedTest {
    @Test fun commentCommandsKeepDocumentLocalPages() {
        val document = ProjectReading.Document("second", "Second", "a".repeat(64), "b".repeat(64), 100, 2, 7)
        val command = com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit.AddPdfMarkupComment(8, "id", listOf(RectF(1f, 2f, 3f, 4f)), 0xff000000.toInt(), "Comment", "Test", "now", "now")
        val mapped = command.forReadingDocument(document) as com.gitlab.mudlej.MjPdfReader.data.annotation.AnnotationEdit.AddPdfMarkupComment
        assertEquals(1, mapped.page); assertEquals(1, mapped.fragments.single().page)
        try { command.copy(page = 6).forReadingDocument(document); fail("Expected boundary rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun crossDocumentLinksUseProjectPageNumbers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun fixture(name: String, remote: Boolean): File {
            val objects = listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Contents 4 0 R /Resources << >> ${if (remote) "/Annots [5 0 R]" else ""} >>",
                "<< /Length 0 >>\nstream\n\nendstream",
                "<< /Type /Annot /Subtype /Link /Rect [10 10 50 50] /A << /S /GoToR /F (second.pdf) /D [0 /Fit] >> >>",
            )
            val text = StringBuilder("%PDF-1.7\n")
            val offsets = mutableListOf<Int>()
            objects.forEachIndexed { index, value -> offsets += text.length; text.append("${index + 1} 0 obj\n$value\nendobj\n") }
            val xref = text.length
            text.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { text.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
            text.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            return File(context.cacheDir, name).also { it.writeText(text.toString(), Charsets.US_ASCII) }
        }
        val files = listOf(fixture("first.pdf", true), fixture("second.pdf", false))
        val core = PdfiumCore(context)
        val children = files.map { core.newDocument(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)) }
        val project = core.newComposition(children, arrayOf("first", "second"), arrayOf("First", "Second"), intArrayOf(1, 1), "Project")
        project.setDocumentFilenames(arrayOf("first.pdf", "second.pdf"))
        try {
            core.openPage(project, 0)
            assertEquals(1, core.getPageLinks(project, 0).single().destPageIdx)
        } finally { core.closeDocument(project); files.forEach(File::delete) }
    }

    @Test fun documentBoundariesAndAnnotationsRemainIndependent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val core = PdfiumCore(context)
        val files = (0..1).map { index ->
            File(context.cacheDir, "composition-$index.pdf").also { file ->
                val pdf = android.graphics.pdf.PdfDocument()
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(200 + 50 * index, 300, 1).create())
                page.canvas.drawText("Document $index", 20f, 50f, android.graphics.Paint().apply { textSize = 18f })
                pdf.finishPage(page)
                file.outputStream().use(pdf::writeTo)
                pdf.close()
            }
        }
        val children = files.map { core.newDocument(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)) }
        val project = core.newComposition(children, arrayOf("first", "second"), arrayOf("First", "Second"), intArrayOf(1, 1), "Project")
        try {
            assertEquals(2, core.getPageCount(project))
            core.openPage(project, 0); core.openPage(project, 1)
            assertEquals(200f, core.getPageSizePoint(project, 0).width, 0.01f)
            assertEquals(250f, core.getPageSizePoint(project, 1).width, 0.01f)
            assertTrue(core.getPageText(project, 0).contains("Document 0"))
            assertTrue(core.getPageText(project, 1).contains("Document 1"))
            assertEquals("second", project.documentIdForPage(1))
            assertEquals(0, project.documentRelativePage(1))
            assertEquals(1, project.projectPage("second", 0))
            assertEquals(-1, project.projectPage("second", 1))
            assertEquals(listOf(0L, 1L), core.getTableOfContents(project).map { it.pageIdx })
            assertTrue(core.getPdfComments(project, 0).isEmpty())
            assertTrue(core.getPdfComments(project, 1).isEmpty())
            assertTrue(core.createPdfComment(project, 1, RectF(20f, 20f, 40f, 40f), "reading-comment", "Second document", "Test", "D:20260928090000Z", "D:20260928090000Z"))
            assertTrue(core.getPdfComments(project, 0).isEmpty())
            assertEquals(1, core.getPdfComments(project, 1).size)
            assertEquals(1, core.getPdfComments(children[1], 0).size)
            // Reject an inconsistent captured page count before taking ownership.
            try {
                core.newComposition(children, arrayOf("first", "second"), arrayOf("First", "Second"), intArrayOf(1, 2), "Invalid")
                fail("Expected a page-count mismatch")
            } catch (_: java.io.IOException) { }
            assertFalse(children[0].isClosed)
        } finally {
            core.closeDocument(project)
            assertTrue(children.all { it.isClosed })
            files.forEach(File::delete)
        }
    }
}
