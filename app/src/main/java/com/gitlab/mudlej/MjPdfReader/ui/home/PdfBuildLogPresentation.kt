// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

internal data class PdfBuildLogAnchor(
    val start: Int,
    val endExclusive: Int,
)

internal fun pdfBuildLogAnchor(
    contents: String,
    source: String,
    message: String,
    pdfFilename: String,
): PdfBuildLogAnchor? {
    if (contents.isEmpty()) return null
    val normalizedSource = source.trim().replace('\\', '/')
    val sourceName = normalizedSource.substringAfterLast('/').takeIf(String::isNotBlank)
    val sourceStem = sourceName?.substringBeforeLast('.')
    val pdfStem = pdfFilename.trim().substringBeforeLast('.').takeIf(String::isNotBlank)
    val candidates = buildList {
        normalizedSource.takeIf(String::isNotBlank)?.let { add("$it:") }
        sourceName?.let { add("$it:") }
        message.trim().takeIf(String::isNotBlank)?.let(::add)
        if (pdfStem != null && sourceName != null) {
            add("compile $pdfStem/$sourceName")
        }
        if (pdfStem != null && sourceStem != null) {
            add("compile $pdfStem/$sourceStem")
        }
    }
    val offset = candidates.firstNotNullOfOrNull { candidate ->
        contents.indexOf(candidate).takeIf { it >= 0 }
    } ?: listOf("Fatal error", "LaTeX Error:", "Error:")
        .firstNotNullOfOrNull { marker -> contents.lastIndexOf(marker).takeIf { it >= 0 } }
        ?: contents.lastIndex
    val lineStart = contents.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
        .let { if (it < 0) 0 else it + 1 }
    val lineEnd = contents.indexOf('\n', offset)
        .let { if (it < 0) contents.length else it }
    return PdfBuildLogAnchor(lineStart, lineEnd.coerceAtLeast(lineStart))
}
