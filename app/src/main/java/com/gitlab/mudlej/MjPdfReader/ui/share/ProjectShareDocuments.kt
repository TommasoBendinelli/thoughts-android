// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.share

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.gitlab.mudlej.MjPdfReader.core.io.PdfPageSelection
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineBundlePdf
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ProjectShareDocument(
    val name: String,
    val pages: PdfPageSelection.Range,
)

/** Resolve only boundaries belonging to the actual local PDF, without activating or syncing a bundle. */
internal suspend fun loadProjectShareDocuments(
    context: Context,
    sourceUri: Uri,
    pageCount: Int,
    expectedSha256: String?,
    password: String? = null,
): List<ProjectShareDocument> = withContext(Dispatchers.IO) {
    val file = sourceUri.takeIf { it.scheme == "file" }?.path?.let(::File)
        ?.takeIf(File::isFile) ?: return@withContext emptyList()
    val metadata = OfflineSourceBundleStore(context).librarySourceMetadata(file.name)
        ?.pdfs?.get(file.name) ?: return@withContext emptyList()
    val digest = computeSha256(file) ?: return@withContext emptyList()
    if (expectedSha256 != null && digest != expectedSha256) return@withContext emptyList()
    val documents = projectShareDocuments(metadata, digest, pageCount)
    if (documents.isEmpty()) return@withContext emptyList()
    val outlineTitles = runCatching {
        val core = PdfiumCore(context)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = try { core.newDocument(descriptor, password) }
        catch (error: Throwable) { descriptor.close(); throw error }
        try {
            core.getTableOfContents(pdf).associate { it.pageIdx.toInt() + 1 to it.title }
        } finally {
            core.closeDocument(pdf)
        }
    }.getOrDefault(emptyMap())
    // The catalogue supplies user-facing titles; boundaries always come from the matching build.
    val titles = runCatching {
        LibraryProjectStore(context).projects().flatMap { project ->
            project.asJsonObject.getAsJsonArray("documents").map { it.asJsonObject }
        }.filter {
            it.get("filename")?.asString == file.name && it.get("sha256")?.asString == digest
        }.associate { it.get("start_page")?.asInt to it.get("name")?.asString }
    }.getOrDefault(emptyMap())
    documents.map { doc ->
        doc.copy(name = projectShareDocumentTitle(doc, outlineTitles, titles))
    }
}

internal fun projectShareDocumentTitle(
    document: ProjectShareDocument,
    outlineTitles: Map<Int, String?>,
    catalogueTitles: Map<Int?, String?>,
): String {
    val page = document.pages.firstPageIndex + 1
    return outlineTitles[page]?.takeIf(String::isNotBlank)
        ?: catalogueTitles[page]?.takeIf(String::isNotBlank) ?: document.name
}

internal fun projectShareDocuments(
    pdf: OfflineBundlePdf,
    actualSha256: String,
    pageCount: Int,
): List<ProjectShareDocument> {
    if (actualSha256.isBlank() || pdf.pdfSha256 != actualSha256 ||
        pageCount <= 0 || pdf.pageCount != pageCount) return emptyList()
    val segments = pdf.segments.sortedBy { it.startPage }
    var previousEnd = 0L
    for (segment in segments) {
        val end = segment.startPage.toLong() + segment.pageCount - 1L
        if (segment.startPage <= previousEnd || segment.pageCount <= 0 || end > pageCount) return emptyList()
        previousEnd = end
    }
    return segments.map { segment ->
        val name = segment.primarySourceFile?.let { File(it).nameWithoutExtension.replace('_', ' ') }
            ?.takeIf(String::isNotBlank) ?: "Document ${segment.startPage}"
        ProjectShareDocument(name, PdfPageSelection.Range(
            segment.startPage - 1,
            segment.startPage + segment.pageCount - 2,
        ))
    }
}

internal fun currentProjectShareDocument(documents: List<ProjectShareDocument>, pageIndex: Int): Int =
    documents.indexOfFirst { it.pages.includes(pageIndex) }.coerceAtLeast(0)
