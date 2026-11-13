// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

/** Only verified incompatibility or invalid patch data can authorize a full-PDF fallback. */
internal class InvalidPublicationDeltaException(message: String) : IllegalStateException(message)

internal fun requireUsablePublicationDelta(condition: Boolean, reason: String) {
    if (!condition) throw InvalidPublicationDeltaException(reason)
}

internal inline fun attemptPublicationDelta(
    onInvalid: (String) -> Unit,
    apply: () -> Unit,
): Boolean = try {
    apply()
    true
} catch (error: InvalidPublicationDeltaException) {
    onInvalid(requireNotNull(error.message))
    false
}
