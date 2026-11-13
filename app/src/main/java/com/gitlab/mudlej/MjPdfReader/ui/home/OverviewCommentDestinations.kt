// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.entity.ScannedPdfEntry
import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import java.io.File

internal data class OverviewCommentDestinationEntry(
    val entry: ScannedPdfEntry,
    val kind: LibraryPdfKind,
    val label: String,
)

internal data class OverviewCommentDestination(
    val item: HomeItem,
    val kind: LibraryPdfKind,
    val label: String,
)

internal fun overviewCommentDestinationEntries(
    sourceIndexSnapshot: SourceLinkedPdfIndexSnapshot,
    canonicalPdfDirectory: File,
): List<OverviewCommentDestinationEntry> {
    if (!sourceIndexSnapshot.libraryAvailable && !sourceIndexSnapshot.available) {
        return emptyList()
    }
    val kinds = if (sourceIndexSnapshot.libraryAvailable) {
        sourceIndexSnapshot.libraryKindsByFilename
    } else {
        sourceIndexSnapshot.filenames.associateWith { LibraryPdfKind.SOURCE_LINKED }
    }
    val candidates = libraryDisplayEntries(
        sourceIndexSnapshot = sourceIndexSnapshot,
        canonicalPdfDirectory = canonicalPdfDirectory,
    ).mapNotNull { entry ->
        val filename = File(entry.path).name
        val kind = kinds[filename] ?: return@mapNotNull null
        val displayName = sourceIndexSnapshot.libraryDisplayNamesByFilename[filename]
            ?.trim()
            .orEmpty()
        val fallback = filename.dropLast(4)
        Triple(entry, kind, displayName.ifBlank { fallback })
    }
    val labelCounts = candidates
        .groupingBy { (_, _, label) -> label.lowercase() }
        .eachCount()
    return candidates.map { (entry, kind, baseLabel) ->
        val filename = File(entry.path).name
        val label = if (labelCounts.getValue(baseLabel.lowercase()) > 1) {
            "$baseLabel ($filename)"
        } else {
            baseLabel
        }
        OverviewCommentDestinationEntry(entry, kind, label)
    }.sortedWith(
        compareBy<OverviewCommentDestinationEntry> {
            if (it.kind == LibraryPdfKind.SOURCE_LINKED) 0 else 1
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy { File(it.entry.path).name },
    )
}

internal fun overviewCommentDeliveryTarget(
    itemPath: String?,
    destinations: List<OverviewCommentDestination>,
): String? {
    val canonicalItem = itemPath?.let(::File)?.let { file ->
        runCatching { file.canonicalFile }.getOrNull()
    } ?: return null
    return destinations.firstOrNull { destination ->
        destination.item.uri.path?.let(::File)?.let { file ->
            runCatching { file.canonicalFile == canonicalItem }.getOrDefault(false)
        } == true
    }?.item?.fileName
}
