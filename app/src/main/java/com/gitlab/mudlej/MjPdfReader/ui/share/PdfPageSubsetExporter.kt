// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.gitlab.mudlej.MjPdfReader.core.io.ensurePdfFileName
import com.gitlab.mudlej.MjPdfReader.core.io.pdfShareUri
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SharedPdf(
    val uri: Uri,
    val fileName: String,
)

class PdfPageSubsetExporter(private val context: Context) {

    suspend fun export(
        sourceUri: Uri,
        displayName: String,
        password: String?,
        selection: PdfPageSelection,
        outputName: String? = null,
    ): SharedPdf = withContext(Dispatchers.IO) {
        if (selection === PdfPageSelection.All) {
            return@withContext SharedPdf(
                uri = runCatching { pdfShareUri(context, sourceUri, displayName) }
                    .getOrDefault(sourceUri),
                fileName = ensurePdfFileName(displayName),
            )
        }

        val range = selection as PdfPageSelection.Range
        val fileName = outputName?.let(::ensurePdfFileName) ?: subsetPdfFileName(displayName, range)
        val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        purgeExpiredShareFiles(directory)
        val target = File(directory, fileName)
        val temporary = File(directory, "$fileName.part")
        temporary.delete()

        val core = PdfiumCore(context)
        val sourceDescriptor = openSourceDescriptor(sourceUri)
        var document: PdfDocument? = null
        try {
            document = core.newDocument(sourceDescriptor, password)
            ParcelFileDescriptor.open(
                temporary,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            ).use { output ->
                if (!core.writePages(document, range.pageIndices(), output)) {
                    throw IOException("PDFium could not create the selected page range")
                }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        } finally {
            if (document != null) {
                core.closeDocument(document)
            } else {
                sourceDescriptor.close()
            }
        }

        SharedPdf(
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            ),
            fileName = fileName,
        )
    }

    private fun openSourceDescriptor(uri: Uri): ParcelFileDescriptor {
        return if (uri.scheme == "file") {
            ParcelFileDescriptor.open(
                File(requireNotNull(uri.path)),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Could not open PDF for page-range sharing")
        }
    }

    companion object {
        private const val SHARE_DIRECTORY = "share"
        private const val SHARE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        internal fun subsetPdfFileName(
            displayName: String,
            range: PdfPageSelection.Range,
        ): String {
            val pdfName = ensurePdfFileName(displayName)
            val stem = pdfName.dropLast(4)
            return "$stem.pages-${range.firstPageIndex + 1}-${range.lastPageIndex + 1}.pdf"
        }

        private fun purgeExpiredShareFiles(directory: File) {
            val expiredBefore = System.currentTimeMillis() - SHARE_MAX_AGE_MILLIS
            directory.listFiles()?.filter {
                (it.name.endsWith(".pdf", ignoreCase = true) ||
                    it.name.endsWith(".comments.json", ignoreCase = true) ||
                    it.name.endsWith(".part", ignoreCase = true)) &&
                    it.lastModified() < expiredBefore
            }?.forEach(File::delete)
        }
    }
}
