// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import java.io.File

/**
 * Files retained for recovery are not separate library documents.
 *
 * The companion protocol exposes one current PDF per note from docs-clean-pdfs. Historical
 * versions and conflict copies stay on disk, but Thoughts does not catalogue them.
 */
internal fun isLibraryPdfCandidate(path: String): Boolean {
    val file = File(path)
    if (file.extension.equals("pdf", ignoreCase = true).not()) {
        return false
    }
    val lowerName = file.name.lowercase()
    if (
        file.name.startsWith(".") ||
        ".sync-conflict-" in lowerName ||
        lowerName.endsWith(".tmp") ||
        lowerName.endsWith(".saving")
    ) {
        return false
    }
    if (generateSequence(file.parentFile) { it.parentFile }.any { it.name == ".stversions" }) {
        return false
    }
    return true
}
