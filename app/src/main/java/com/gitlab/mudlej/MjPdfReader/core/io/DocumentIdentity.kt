// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.core.io

internal data class DocumentIdentity(
    val historyHash: String?,
    val annotationPdfSha256: String?,
)

/**
 * Read both identities from the document that is being loaded.
 *
 * Neither value may come from restored activity state: a synchronizer can replace the file while
 * the reader is stopped, and comments must identify the bytes that the reopened reader displays.
 */
internal suspend fun <Source> computeFreshDocumentIdentity(
    source: Source,
    historyHasher: suspend (Source) -> String?,
    annotationHasher: suspend (Source) -> String?,
): DocumentIdentity = DocumentIdentity(
    historyHash = historyHasher(source),
    annotationPdfSha256 = annotationHasher(source),
)
