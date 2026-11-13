// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.importing

import android.content.Context
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfiumCore
import java.io.Closeable
import java.io.File

/** Owns a downloaded PDF until confirmation, cancellation, or successful local staging. */
data class PreparedPaperImport(
    val paper: PaperWebsite,
    val file: File,
    val title: String?,
    val pageCount: Int,
    val sizeBytes: Long,
) : Closeable {
    override fun close() { file.delete() }

    companion object {
        fun prepare(context: Context, paper: PaperWebsite, checkActive: () -> Unit = {}): PreparedPaperImport {
            val metadata = PaperMetadata().lookup(paper, checkActive)
            checkActive()
            val file = PaperPdfDownloader().download(metadata.paper, context.cacheDir, checkActive)
            try {
                checkActive()
                val core = PdfiumCore(context)
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val document = try { core.newDocument(descriptor) }
                catch (error: Exception) { descriptor.close(); throw error }
                try {
                    val pageCount = core.getPageCount(document)
                    require(pageCount > 0) { "The PDF has no pages" }
                    val pdfTitle = runCatching { PaperMetadata.usableTitle(core.getDocumentMeta(document)?.title) }.getOrNull()
                    checkActive()
                    return PreparedPaperImport(metadata.paper, file, metadata.title ?: pdfTitle, pageCount, file.length())
                } finally { core.closeDocument(document) }
            } catch (error: Exception) { file.delete(); throw error }
        }
    }
}
