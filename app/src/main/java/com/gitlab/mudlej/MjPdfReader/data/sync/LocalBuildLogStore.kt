// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Immutable Fedora build logs cached during an explicit sync and read without network access. */
class LocalBuildLogStore(
    private val directory: File = ThoughtsLocalStore.buildLogs(),
) {
    fun isAvailable(logReference: String): Boolean = read(logReference) != null

    fun read(logReference: String): String? {
        val expectedSha256 = serverLogSha256(logReference) ?: return null
        val source = file(logReference)?.takeIf(File::isFile) ?: return null
        val contents = runCatching { source.readBytes() }.getOrNull() ?: return null
        if (sha256(contents) != expectedSha256) return null
        return contents.toString(StandardCharsets.UTF_8)
    }

    fun cache(logReference: String, contents: ByteArray) {
        val expectedSha256 = requireNotNull(serverLogSha256(logReference)) {
            "Invalid server build-log reference"
        }
        require(sha256(contents) == expectedSha256) {
            "The server build log failed SHA-256 verification"
        }
        directory.mkdirs()
        check(directory.isDirectory) { "Could not create the local build-log directory" }
        val destination = requireNotNull(file(logReference))
        if (destination.isFile && read(logReference) != null) return
        val temporary = File(directory, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(contents)
                output.fd.sync()
            }
            if (destination.exists()) {
                check(destination.delete()) { "Could not replace a corrupt local build log" }
            }
            check(temporary.renameTo(destination)) { "Could not activate the local build log" }
        } finally {
            temporary.delete()
        }
    }

    private fun file(logReference: String): File? = serverLogSha256(logReference)?.let { sha256 ->
        File(directory, "$sha256.log")
    }

    private fun sha256(contents: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(contents)
        .joinToString("") { "%02x".format(it) }
}
