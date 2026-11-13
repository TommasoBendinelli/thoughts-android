// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProjectDocumentExportInstrumentedTest {
    @Test fun exportedDocumentContainsOnlyItsPagesInOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "project-document-test-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$name-source.pdf")
        val output = File(context.cacheDir, "share/$name.pdf")
        try {
            val pdf = PdfDocument()
            try {
                for (number in 1..4) {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 300, number).create())
                    page.canvas.drawText("Original page $number", 20f, 50f, Paint().apply { textSize = 18f })
                    pdf.finishPage(page)
                }
                source.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            val shared = PdfPageSubsetExporter(context).export(
                Uri.fromFile(source), "project.pdf", null, PdfPageSelection.Range(1, 2), name,
            )
            assertEquals("$name.pdf", shared.fileName)
            val core = PdfiumCore(context)
            val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(shared.uri, "r"))
            val document = core.newDocument(descriptor)
            try {
                assertEquals(2, core.getPageCount(document))
                for (page in 0..1) {
                    core.openPage(document, page)
                    assertEquals("Original page ${page + 2}", core.getPageText(document, page).trim())
                }
            } finally {
                core.closeDocument(document)
            }
        } finally {
            source.delete()
            output.delete()
        }
    }
}
