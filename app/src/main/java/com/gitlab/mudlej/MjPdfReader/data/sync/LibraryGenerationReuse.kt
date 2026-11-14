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

/**
 * The active generation's files by what they hold, built once for a staging pass. Staging looked
 * each PDF and each reading object up by walking every publication again, so a pass over a Library
 * of n PDFs made n × n comparisons.
 */
internal class GenerationFileIndex(publications: Map<String, Pair<String, Long>>) {
    private val firstByIdentity = hashMapOf<Pair<String, Long>, String>()
    private val allBySha256 = hashMapOf<String, MutableList<String>>()

    init {
        publications.forEach { (filename, identity) ->
            firstByIdentity.putIfAbsent(identity, filename)
            allBySha256.getOrPut(identity.first) { mutableListOf() } += filename
        }
    }

    /** The first file (in the generation's own order) holding exactly this PDF, as the walk found it. */
    fun filenameHolding(sha256: String, sizeBytes: Long): String? = firstByIdentity[sha256 to sizeBytes]

    /** Every file holding these bytes, in the generation's own order. */
    fun filenamesHolding(sha256: String): List<String> = allBySha256[sha256].orEmpty()
}

/** [items] by [key], each group in the order of [items]; built once instead of filtering per lookup. */
internal fun <T> groupedInOrder(items: Iterable<T>, key: (T) -> String): Map<String, List<T>> =
    items.groupByTo(hashMapOf(), key)
