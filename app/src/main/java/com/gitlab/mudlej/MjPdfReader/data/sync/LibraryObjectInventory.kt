// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File

/** Only the app-owned, flat content-addressed stores can supply Library objects. */
internal class LibraryObjectInventory(
    private val filesDirectory: File,
    private val verify: (File) -> String = verifiedFileCache::sha256,
) {
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
                    if (file.canonicalFile.parentFile != root || !file.isFile) return@runCatching false
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
