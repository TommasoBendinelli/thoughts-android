// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.review

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewQaPdfExporterInstrumentedTest {

    @Test
    fun exportsInstalledDeckAndAppendsItsSourcePages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceIndex = SourceLinkedPdfIndex()
        val sourceSnapshot = sourceIndex.snapshot()
        assertTrue(sourceSnapshot.libraryAvailable)
        assertTrue(sourceSnapshot.generationCurrent)

        val reviewStore = ReviewStore(context)
        val deck = reviewStore.summary(
            canonicalPdfSha256ByFilename = sourceSnapshot.libraryPdfSha256ByFilename,
        ).decks.maxByOrNull { item -> item.activeCount }
        checkNotNull(deck) { "The installed app has no active review deck" }

        val sourcePdf = File(
            PhoneRenderQueue.defaultCanonicalPdfDirectory(),
            "${deck.pdfStem}.pdf",
        )
        assertTrue(sourcePdf.isFile)
        val destination = File(context.cacheDir, "review-qa-export-with-source.pdf")
        destination.delete()

        val result = ReviewQaPdfExporter(
            context = context,
            reviewStore = reviewStore,
            sourceIndex = sourceIndex,
        ).export(
            ReviewQaExportRequest(deck.pdfStem, appendSourcePdf = true),
            Uri.fromFile(destination),
        )
        assertEquals(deck.activeCount, result.cardCount)
        assertTrue(result.qaPageCount > 0)
        assertTrue(result.sourcePdfAppended)
        assertTrue(destination.length() > 0L)

        val core = PdfiumCore(context)
        val sourceDocument = open(core, sourcePdf)
        val exportedDocument = open(core, destination)
        try {
            assertEquals(
                result.qaPageCount + core.getPageCount(sourceDocument),
                core.getPageCount(exportedDocument),
            )
            assertTrue(pageText(core, exportedDocument, 0).contains("Question 1"))
            assertEquals(
                pageText(core, sourceDocument, 0).trim(),
                pageText(core, exportedDocument, result.qaPageCount).trim(),
            )
        } finally {
            core.closeDocument(exportedDocument)
            core.closeDocument(sourceDocument)
            destination.delete()
        }
    }

    private fun open(core: PdfiumCore, file: File): PdfDocument = core.newDocument(
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
    )

    private fun pageText(core: PdfiumCore, document: PdfDocument, pageIndex: Int): String {
        check(core.openPage(document, pageIndex) != 0L)
        return core.getPageText(document, pageIndex)
    }
}
