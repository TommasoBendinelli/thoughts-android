// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File

/** Only the app-owned, flat content-addressed stores can supply Library objects. */
internal class LibraryObjectInventory(
    private val filesDirectory: File,
    private val verify: (File) -> String = verifiedFileCache::sha256,
) {
    /** How a listed entry is told from a link or a directory; replaced by tests that count the looks. */
    internal var isPlainFile: (root: File, file: File) -> Boolean = ::isPlainFileDirectlyIn

    fun verifiedHashes(diagnostics: SyncDiagnostics? = null): Set<String> {
        val verified = linkedSetOf<String>()
        var candidates = 0L
        var checked = 0L
        val privateRoot = filesDirectory.canonicalFile
        for (relative in OBJECT_STORES) {
            val root = File(privateRoot, relative)
            // Do not follow a redirected store or recurse into unrelated files.
            if (root.canonicalFile != root) continue
            for (file in root.listFiles().orEmpty()) {
                if (!SHA256.matches(file.name)) continue
                candidates++
                if (file.name in verified) continue
                val valid = runCatching {
                    if (!isPlainFile(root, file)) return@runCatching false
                    checked++
                    verify(file) == file.name
                }.getOrDefault(false)
                if (valid) verified += file.name
            }
        }
        diagnostics?.count("preflight_library_objects",
            "candidate_files" to candidates, "checked_files" to checked,
            "verified_objects" to verified.size.toLong())
        return verified
    }

    internal companion object {
        val OBJECT_STORES = listOf(
            "source-editor/offline/objects",
            "source-editor/offline/object-cache",
            "source-editor/bundle-staging/objects",
            "source-editor/document-artifacts",
        )
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Whether [file], listed in the canonical directory [root], is a regular file and not a link.
 * Resolving each stored object's canonical path cost several system calls per path component, for
 * every object, in every inventory; one look at the entry itself answers the same question: the
 * directory is already canonical and the name has no separator, so only a link could lead out of
 * it. Before Android 8 there is no such call, and the canonical path is compared as before.
 */
internal fun isPlainFileDirectlyIn(root: File, file: File): Boolean {
    if (android.os.Build.VERSION.SDK_INT in 1..25) return file.canonicalFile.parentFile == root && file.isFile
    return java.nio.file.Files.readAttributes(
        file.toPath(),
        java.nio.file.attribute.BasicFileAttributes::class.java,
        java.nio.file.LinkOption.NOFOLLOW_LINKS,
    ).isRegularFile
}
