// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.security.MessageDigest

/**
 * Reuses locally verified source bytes when Fedora confirms that they are already canonical.
 * Divergent or merged results still come from Fedora and receive the same digest check.
 */
internal fun verifiedCanonicalSourceBytes(
    localBytes: ByteArray,
    resultSha256: String,
    resultObjectSha256: String,
    downloadObject: (String) -> ByteArray,
): ByteArray {
    val localSha256 = sha256ForCanonicalSource(localBytes)
    val canonical = if (
        resultSha256 == localSha256 && resultObjectSha256 == localSha256
    ) {
        localBytes
    } else {
        downloadObject(resultObjectSha256)
    }
    check(sha256ForCanonicalSource(canonical) == resultSha256) {
        "The server's canonical source failed SHA-256 verification"
    }
    return canonical
}

private fun sha256ForCanonicalSource(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
