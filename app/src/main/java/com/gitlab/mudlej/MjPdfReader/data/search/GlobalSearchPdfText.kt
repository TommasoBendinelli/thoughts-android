// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.search

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfiumCore
import java.io.File

/** Reads every page's text of a PDF with PDFium, the same text the reader's search uses. */
internal class GlobalSearchPdfText(context: Context) {
    private val app = context.applicationContext
    private val core = PdfiumCore(app)

    /** Page texts in order (index 0 = page 1); [pause] runs between pages so other work gets PDFium. */
    fun pages(uri: Uri, pause: () -> Unit = {}): List<String> {
        val descriptor = open(uri) ?: return emptyList()
        val document = try {
            core.newDocument(descriptor)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
        try {
            val count = core.getPageCount(document)
            return (0 until count).map { index ->
                pause()
                core.openPage(document, index)
                try {
                    core.getPageText(document, index).orEmpty().take(MAX_PAGE_CHARS)
                } finally {
                    core.closePage(document, index)
                }
            }
        } finally {
            runCatching { core.closeDocument(document) }
        }
    }

    private fun open(uri: Uri): ParcelFileDescriptor? = when (uri.scheme) {
        null, "file" -> uri.path?.let(::File)?.takeIf(File::isFile)
            ?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        else -> app.contentResolver.openFileDescriptor(uri, "r")
    }

    companion object {
        private const val MAX_PAGE_CHARS = 40_000
    }
}
