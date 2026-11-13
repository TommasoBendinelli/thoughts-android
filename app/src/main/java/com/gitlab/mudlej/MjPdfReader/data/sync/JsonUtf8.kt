// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Encode directly to UTF-8 without materializing a second, full-size UTF-16 JSON string. */
internal fun Gson.toUtf8Bytes(value: Any, trailingNewline: Boolean = false): ByteArray {
    val bytes = ByteArrayOutputStream()
    bytes.writer(StandardCharsets.UTF_8).buffered().use { writer ->
        toJson(value, writer)
        if (trailingNewline) writer.append('\n')
    }
    return bytes.toByteArray()
}
