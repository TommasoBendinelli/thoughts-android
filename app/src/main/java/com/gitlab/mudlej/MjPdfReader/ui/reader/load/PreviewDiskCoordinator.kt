// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader.load

import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.data.render.PdfWarmCache
import java.io.File

object PreviewDiskCoordinator {

    private const val PREVIEW_DIR = PdfWarmCache.PREVIEW_DIRECTORY

    fun docKey(fileHash: String, pageCount: Int, sizeBytes: Long?): String = docKey(fileHash, sizeBytes)

    fun docKey(fileHash: String, sizeBytes: Long?): String =
        PdfWarmCache.previewDocumentKey(fileHash, sizeBytes)

    fun prepare(
        configurator: PDFView.Configurator,
        cacheDir: File,
        fileIdentity: String,
        sizeBytes: Long?,
        hasPassword: Boolean,
    ) {
        if (!hasPassword) {
            configurator.previewDisk(File(cacheDir, PREVIEW_DIR), docKey(fileIdentity, sizeBytes))
        }
    }

    fun attach(
        pdfView: PDFView,
        cacheDir: File,
        fileHash: String,
        pageCount: Int,
        sizeBytes: Long?,
        hasPassword: Boolean,
    ) {
        if (hasPassword || pageCount <= 0) {
            return
        }
        pdfView.attachPreviewDisk(File(cacheDir, PREVIEW_DIR), docKey(fileHash, pageCount, sizeBytes))
    }
}
