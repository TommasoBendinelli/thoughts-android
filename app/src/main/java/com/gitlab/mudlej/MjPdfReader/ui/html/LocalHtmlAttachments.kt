// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.html

import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** Explicitly selected copies of desktop file links; never dereference their original paths. */
internal class LocalHtmlAttachments(private val root: File) {
    fun get(pdf: String, link: String): File? = file(pdf, link).takeIf { it.isFile && it.length() in 1..MAX_BYTES }

    fun save(pdf: String, link: String, input: InputStream): File {
        require(handles(link))
        val destination = file(pdf, link)
        root.mkdirs()
        val temporary = File.createTempFile("attachment-", ".tmp", root)
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var count = 0L
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    count += length
                    require(count <= MAX_BYTES) { "HTML files must be at most 100 MB." }
                    output.write(buffer, 0, length)
                }
                require(count > 0) { "The selected HTML file is empty." }
            }
            check(temporary.renameTo(destination)) { "Could not save the HTML attachment." }
            return destination
        } finally { temporary.delete() }
    }

    private fun file(pdf: String, link: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest((pdf + "\u0000" + link).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "$key.html")
    }

    companion object {
        private const val MAX_BYTES = 100_000_000L
        fun handles(link: String): Boolean = runCatching {
            val uri = URI(link)
            uri.scheme.equals("file", true) && uri.host.isNullOrEmpty() &&
                uri.path?.lowercase()?.let { it.endsWith(".html") || it.endsWith(".htm") } == true
        }.getOrDefault(false)
    }
}
