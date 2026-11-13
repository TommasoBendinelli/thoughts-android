// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

internal fun formatCompactLibraryMetadata(
    commentTimestamp: CharSequence,
    unresolvedThreadCount: Int,
    formatSummary: (CharSequence, CharSequence) -> CharSequence,
    formatUnresolvedThreads: (Int) -> CharSequence,
): CharSequence {
    return formatSummary(
        commentTimestamp,
        formatUnresolvedThreads(unresolvedThreadCount),
    )
}
