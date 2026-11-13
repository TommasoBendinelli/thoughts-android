// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

/** Only exact, unambiguous document metadata may establish that source is absent. */
internal fun reviewSourceAvailability(
    pdfs: Map<String, OfflineBundlePdf>,
    filename: String,
    pdfSha256: String,
    pages: List<Int>,
): Boolean? {
    val pdf = pdfs.values.singleOrNull { it.filename == filename && it.pdfSha256 == pdfSha256 } ?: return null
    if (pages.isEmpty()) return null
    val segments = pages.map { page ->
        pdf.segments.singleOrNull { page >= it.startPage && page < it.startPage + it.pageCount }
            ?: return null
    }
    if (segments.distinct().size != 1) return null
    return segments.first().sourceAvailable
}
