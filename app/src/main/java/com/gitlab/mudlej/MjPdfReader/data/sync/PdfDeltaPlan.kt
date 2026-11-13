// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import org.tbend.thoughts.sdk.LibraryEntry

internal data class PdfDeltaCandidate(
    val baseSha256: String,
    val objectSha256: String,
    val sizeBytes: Long,
)

internal fun eligiblePdfDelta(
    publication: LibraryEntry,
    verifiedBaseSha256s: Set<String>,
): PdfDeltaCandidate? {
    val delta = publication.metadata.getAsJsonObject("delta") ?: return null
    val baseSha256 = delta.get("base_sha256")?.asString.orEmpty()
    val objectSha256 = delta.get("object_sha256")?.asString.orEmpty()
    val patchSize = delta.get("size_bytes")?.asLong ?: return null
    val resultSize = delta.get("result_size_bytes")?.asLong ?: return null
    if (
        delta.get("format")?.asString != "zstd-patch-v1" ||
        !baseSha256.matches(PDF_DELTA_SHA256) ||
        baseSha256 !in verifiedBaseSha256s ||
        !objectSha256.matches(PDF_DELTA_SHA256) ||
        patchSize <= 0 || patchSize >= publication.sizeBytes ||
        patchSize > Int.MAX_VALUE ||
        delta.get("result_sha256")?.asString != publication.sha256 ||
        resultSize != publication.sizeBytes ||
        resultSize !in 0..Int.MAX_VALUE.toLong()
    ) {
        return null
    }
    return PdfDeltaCandidate(baseSha256, objectSha256, patchSize)
}

internal fun estimatedPublicationTransferBytes(
    publication: LibraryEntry,
    verifiedLocalPdfSha256s: Set<String>,
    allowDelta: Boolean,
): Long {
    if (publication.sha256 in verifiedLocalPdfSha256s) return 0
    return if (allowDelta) {
        eligiblePdfDelta(publication, verifiedLocalPdfSha256s)?.sizeBytes ?: publication.sizeBytes
    } else {
        publication.sizeBytes
    }
}

private val PDF_DELTA_SHA256 = Regex("^[0-9a-f]{64}$")

/** Only changed publications and their eligible delta bases can affect transfer estimates. */
internal fun requiredPdfReuseHashes(
    changedPublications: List<LibraryEntry>,
    localHashes: Set<String>,
    allowDelta: Boolean,
): Set<String> = buildSet {
    changedPublications.forEach { publication ->
        if (publication.sha256 in localHashes) add(publication.sha256)
        if (allowDelta) eligiblePdfDelta(publication, localHashes)?.let { add(it.baseSha256) }
    }
}
