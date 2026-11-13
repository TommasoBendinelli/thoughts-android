// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

internal const val MAX_DOCUMENT_DISPLAY_NAME_LENGTH = 200

internal enum class DocumentRenameInputError {
    EMPTY,
    TOO_LONG,
    CONTROL_CHARACTER,
    FILE_SEPARATOR,
}

internal data class DocumentRenameInput(
    val normalized: String,
    val error: DocumentRenameInputError? = null,
)

internal fun documentRenameInput(
    raw: String,
    allowFileSeparator: Boolean,
): DocumentRenameInput {
    val normalized = normalizeDocumentDisplayName(raw)
    val error = when {
        normalized.isEmpty() -> DocumentRenameInputError.EMPTY
        normalized.length > MAX_DOCUMENT_DISPLAY_NAME_LENGTH -> DocumentRenameInputError.TOO_LONG
        normalized.any { it.code < 32 || it.code == 127 } ->
            DocumentRenameInputError.CONTROL_CHARACTER
        !allowFileSeparator && normalized.contains('/') -> DocumentRenameInputError.FILE_SEPARATOR
        else -> null
    }
    return DocumentRenameInput(normalized, error)
}

internal fun normalizeDocumentDisplayName(raw: String): String = buildString {
    var pendingSpace = false
    raw.forEach { character ->
        if (character.isWhitespace()) {
            pendingSpace = isNotEmpty()
        } else {
            if (pendingSpace) append(' ')
            append(character)
            pendingSpace = false
        }
    }
}
