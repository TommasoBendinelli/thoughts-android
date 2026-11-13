// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.links

import com.shockwave.pdfium.PdfiumCore
import java.io.File

/** A link from one document to a page of another installed PDF, resolved by PdfiumCore. */
data class RemotePdfLink(val filename: String, val pageIndex: Int, val x: Float, val y: Float)

private val remotePdfPattern = Regex(
    "^${PdfiumCore.REMOTE_PDF_LINK_SCHEME}:([A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf)\\?page=([0-9]{1,6})" +
        "(?:&x=(-?[0-9]+(?:\\.[0-9]+)?))?(?:&y=(-?[0-9]+(?:\\.[0-9]+)?))?$",
)

fun parseRemotePdfLink(value: String?): RemotePdfLink? = value?.let(remotePdfPattern::matchEntire)?.let {
    RemotePdfLink(
        it.groupValues[1],
        it.groupValues[2].toInt(),
        it.groupValues[3].toFloatOrNull() ?: Float.NaN,
        it.groupValues[4].toFloatOrNull() ?: Float.NaN,
    )
}

/** Only plain Library filenames in [directory] may be opened from a link. */
fun installedRemotePdf(directory: File, filename: String): File? =
    File(directory, filename).takeIf { parseRemotePdfLink("${PdfiumCore.REMOTE_PDF_LINK_SCHEME}:$filename?page=0") != null && it.isFile }

const val EXTRA_REMOTE_PDF_LINK = "org.tbend.thoughts.REMOTE_PDF_LINK"
