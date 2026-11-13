// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.tbend.thoughts.sdk.LibraryEntry

private val PUBLICATION_SHA256 = Regex("^[0-9a-f]{64}$")

internal fun requireValidPublicationIdentity(publication: LibraryEntry) {
    // Imported PDFs have an exact PDF identity but no linked source artifact.
    // Only an explicitly unlinked publication may omit the source hash.
    val isUnlinked = publication.metadata.get("kind")?.let {
        it.isJsonPrimitive && it.asString == "unlinked"
    } == true
    val sourceHash = publication.sourceArtifactSha256
    val validSourceIdentity = if (sourceHash == null) isUnlinked else sourceHash.matches(PUBLICATION_SHA256)
    require(
        publication.generationId.matches(PUBLICATION_SHA256) &&
            publication.sha256.matches(PUBLICATION_SHA256) &&
            publication.sizeBytes >= 0 &&
            validSourceIdentity
    ) { "The server returned an invalid document publication identity" }
}
