// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonParser
import java.io.File

private val REUSED_OBJECT = Regex("^objects/[0-9a-f]{64}$")

/** A top-level PDF or a content-addressed project-reading object of a generation. */
private fun isReusableLibraryFile(name: String) =
    (name.isNotBlank() && File(name).name == name && name.endsWith(".pdf", true)) || REUSED_OBJECT.matches(name)

/** Reuse immutable files by rename because Android may prohibit hard links to app files. */
internal fun moveReusedLibraryFiles(from: File, to: File, filenames: Collection<String>) {
    filenames.forEach { name ->
        require(isReusableLibraryFile(name))
        val source = File(from, name)
        val destination = File(to, name)
        if (destination.isFile) return@forEach // Idempotent recovery after an interrupted move.
        destination.parentFile?.mkdirs()
        check(source.isFile && verifiedFileCache.rename(source, destination)) { "Could not reuse Library file $name" }
    }
}

internal fun reusedLibraryFiles(journal: File): List<String> = runCatching {
    JsonParser.parseString(journal.readText()).asJsonObject.getAsJsonArray("reused_pdfs")
        ?.map { it.asString }.orEmpty().also { names ->
            require(names.all(::isReusableLibraryFile))
        }
}.getOrDefault(emptyList())

/** Restore every borrowed file before exposing the previous generation again. */
internal fun restoreReusedLibraryFiles(root: File, staging: File, previous: File, journal: File) {
    if (!previous.isDirectory) return
    reusedLibraryFiles(journal).forEach { name ->
        if (!File(previous, name).isFile) {
            val source = if (File(staging, name).isFile) staging else root
            moveReusedLibraryFiles(source, previous, listOf(name))
        }
    }
}
