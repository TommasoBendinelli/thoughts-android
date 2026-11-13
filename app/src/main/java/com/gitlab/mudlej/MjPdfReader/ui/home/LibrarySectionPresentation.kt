// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

internal enum class LibrarySectionKind {
    LINKED,
    UNLINKED,
    ARCHIVED,
}

internal data class LibrarySectionPresentation(
    val kind: LibrarySectionKind,
    val count: Int,
    val showDivider: Boolean,
)

internal data class LibrarySectionExpansionState(
    val linkedExpanded: Boolean = true,
    val unlinkedExpanded: Boolean = true,
    val archivedExpanded: Boolean = true,
) {
    fun isExpanded(kind: LibrarySectionKind): Boolean = when (kind) {
        LibrarySectionKind.LINKED -> linkedExpanded
        LibrarySectionKind.UNLINKED -> unlinkedExpanded
        LibrarySectionKind.ARCHIVED -> archivedExpanded
    }

    fun toggled(kind: LibrarySectionKind): LibrarySectionExpansionState = when (kind) {
        LibrarySectionKind.LINKED -> copy(linkedExpanded = !linkedExpanded)
        LibrarySectionKind.UNLINKED -> copy(unlinkedExpanded = !unlinkedExpanded)
        LibrarySectionKind.ARCHIVED -> copy(archivedExpanded = !archivedExpanded)
    }
}

internal fun librarySectionPresentation(
    linkedCount: Int,
    unlinkedCount: Int,
    archivedCount: Int,
): List<LibrarySectionPresentation> = buildList {
    if (linkedCount > 0) {
        add(LibrarySectionPresentation(LibrarySectionKind.LINKED, linkedCount, showDivider = false))
    }
    if (unlinkedCount > 0) {
        add(LibrarySectionPresentation(LibrarySectionKind.UNLINKED, unlinkedCount, showDivider = linkedCount > 0))
    }
    if (archivedCount > 0) {
        add(LibrarySectionPresentation(LibrarySectionKind.ARCHIVED, archivedCount, showDivider = linkedCount > 0 || unlinkedCount > 0))
    }
}
