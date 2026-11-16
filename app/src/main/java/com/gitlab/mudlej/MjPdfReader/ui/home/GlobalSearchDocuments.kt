// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.home

import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchIndexer
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchQuery
import com.gitlab.mudlej.MjPdfReader.data.search.GlobalSearchRow as GlobalSearchPageHit
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File

/** A PDF opened in the reader on this phone (a reader record), as the Home search sees it. */
internal data class GlobalSearchRecord(
    /** The record's URI as a string: its key in the page index and in [HomeGlobalSearch]. */
    val ref: String,
    /** Its file, for a file URI. */
    val path: String?,
    val title: String,
    val fileName: String,
    val libraryDisplayName: String,
    /** Changes whenever the PDF does. */
    val identity: String,
    val available: Boolean,
)

/** One document the Home search finds by name and, while its PDF is on the phone, by text. */
internal data class GlobalSearchDocument(
    val key: String,
    val title: String,
    /** Shown under the title: the Project, or the file name of a PDF outside every Project. */
    val subtitle: String,
    /** Matched with [title]: the file name and the Project's name. */
    val otherNames: String,
    /** The PDF whose pages are indexed, or null while it is not on the phone (or not built yet). */
    val pdf: GlobalSearchIndexer.Pdf?,
    /** The pages of [pdf] (1-based) that are this document, when one PDF holds several documents. */
    val pages: IntRange? = null,
    /** The Project's catalogue entry, opened as the Library opens it; null for a [record]. */
    val projectEntry: JsonObject? = null,
    /** The reader record it opens, for a PDF outside every Project. */
    val record: GlobalSearchRecord? = null,
) {
    val searchName: String get() = "$title $otherNames"
}

/** The documents of one search: lookups by page-index key. */
internal class GlobalSearchLibrary(val documents: List<GlobalSearchDocument>) {
    private val byRef = documents.filter { it.pdf != null }.groupBy { it.pdf!!.ref }

    /** Every PDF to keep in the page index, once each. */
    val pdfs: List<GlobalSearchIndexer.Pdf> = documents.mapNotNull(GlobalSearchDocument::pdf).distinctBy(GlobalSearchIndexer.Pdf::ref)

    /** Documents whose name matches [query]: those whose own title matches first, then those found by file or Project name. */
    fun named(query: GlobalSearchQuery): List<GlobalSearchDocument> = documents.filter { query.matches(it.searchName) }
        .sortedWith(compareByDescending<GlobalSearchDocument> { query.matches(it.title) }.thenByDescending { query.matchedStemCount(it.title) })

    /** The document page [page] (1-based) of the indexed PDF [ref] belongs to, or null when none does any more. */
    fun documentForPage(ref: String, page: Int): GlobalSearchDocument? {
        val candidates = byRef[ref] ?: return null
        return candidates.firstOrNull { it.pages == null || page in it.pages } ?: candidates.first()
    }

    /**
     * Page [hits] by the document they are in, as the Mac orders documents: those whose name holds
     * the words first, then those with the most matching pages, then by title. Hits of PDFs no
     * document lists any more are dropped.
     */
    fun pagesByDocument(query: GlobalSearchQuery, hits: List<GlobalSearchPageHit>): List<Pair<GlobalSearchDocument, List<GlobalSearchPageHit>>> {
        val documents = LinkedHashMap<String, GlobalSearchDocument>()
        val pages = LinkedHashMap<String, MutableList<GlobalSearchPageHit>>()
        for (hit in hits) {
            val document = documentForPage(hit.ref, hit.position) ?: continue
            documents.putIfAbsent(document.key, document)
            pages.getOrPut(document.key) { mutableListOf() } += hit
        }
        return pages.map { (key, found) -> documents.getValue(key) to found.sortedBy(GlobalSearchPageHit::position) }
            .sortedWith(compareByDescending<Pair<GlobalSearchDocument, List<GlobalSearchPageHit>>> { query.matches(it.first.searchName) }
                .thenByDescending { it.second.size }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.title }
                .thenBy { it.first.key })
    }
}

/**
 * What the Home search covers: every document of every Project the phone has, from the Project
 * catalogue (named as the Library names it, under its Project), and every other PDF opened in the
 * reader, as before. A Project's reading (`.thoughts-project`) is a list of documents, not a PDF:
 * it is found through its documents and never read as one. A document whose PDF is not on the
 * phone is still found by name; only PDFs on the phone are indexed, keyed by [fileRef] of their
 * file with an identity from the catalogue's hash and the file's size and time, the same whether
 * or not the reader has opened it (so opening a document does not read it again).
 */
internal fun globalSearchLibrary(
    projects: JsonArray,
    records: List<GlobalSearchRecord>,
    canonicalPdfDirectory: File,
    fileRef: (File) -> String,
): GlobalSearchLibrary {
    val entries = projects.objects().flatMap { project ->
        val name = project.text("name").ifBlank { project.text("id") }
        project.getAsJsonArray("documents")?.objects().orEmpty().map { Triple(project, name, it) }
    }
    val files = entries.map { (_, _, entry) -> libraryProjectDocumentFile(entry, canonicalPdfDirectory) }
    val fileKeys = files.map(::pathKey)
    // Older Projects list several documents in one combined PDF, each at its own pages.
    val sharedFiles = fileKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val documents = mutableListOf<GlobalSearchDocument>()
    entries.forEachIndexed { index, (project, projectName, entry) ->
        val file = files[index]
        val filename = entry.text("filename")
        val title = entry.text("name").ifBlank { filename.removeSuffix(".pdf") }
        val first = libraryProjectDocumentStartPage(entry)
        val count = entry.get("page_count")?.takeIf(JsonElement::isJsonPrimitive)?.runCatching { asInt }?.getOrNull() ?: 0
        val pdf = if (first > 0 && file.isFile && file.name.endsWith(".pdf", ignoreCase = true)) {
            GlobalSearchIndexer.Pdf(fileRef(file), "${entry.text("sha256")}:${file.length()}:${file.lastModified()}")
        } else null
        documents += GlobalSearchDocument(
            key = "project:${project.text("id")}:${entry.text("document_id").ifBlank { "$filename#$first" }}",
            title = title,
            subtitle = projectName,
            otherNames = listOf(filename, projectName).joinToString(" "),
            pdf = pdf,
            pages = if (fileKeys[index] in sharedFiles && first > 0 && count > 0) first until first + count else null,
            projectEntry = entry,
        )
    }
    val projectFiles = fileKeys.toHashSet()
    for (record in records) {
        val path = record.path?.let { pathKey(File(it)) }
        // Shown once, as the Project's document; a Project's reading is no PDF.
        if (path != null && (path in projectFiles || isProjectReading(path))) continue
        if (isProjectReading(record.ref)) continue
        val title = record.libraryDisplayName.ifBlank { record.title }.ifBlank { record.fileName }
        documents += GlobalSearchDocument(
            key = "document:${record.ref}",
            title = title,
            subtitle = record.fileName.takeIf { it != title }.orEmpty(),
            otherNames = listOf(record.title, record.fileName, record.libraryDisplayName).joinToString(" "),
            pdf = if (record.available && record.ref.isNotBlank()) GlobalSearchIndexer.Pdf(record.ref, record.identity) else null,
            record = record,
        )
    }
    return GlobalSearchLibrary(documents)
}

/** Whether [pathOrRef] is a Project's reading, which lists its documents' PDFs and is none itself. */
internal fun isProjectReading(pathOrRef: String): Boolean =
    pathOrRef.substringBefore('?').substringBefore('#').endsWith(PROJECT_READING_SUFFIX)

/** The PDF a Project's document opens: its local copy until Sync publishes it, else the Library's file. */
internal fun libraryProjectDocumentFile(entry: JsonObject, canonicalPdfDirectory: File): File =
    entry.text("local_path").takeIf(String::isNotEmpty)?.let(::File) ?: File(canonicalPdfDirectory, entry.text("filename"))

/** The document's first page in its PDF (1-based); 0 or less while it waits for its first build. */
internal fun libraryProjectDocumentStartPage(entry: JsonObject): Int =
    entry.get("start_page")?.takeIf(JsonElement::isJsonPrimitive)?.runCatching { asInt }?.getOrNull() ?: 1

private const val PROJECT_READING_SUFFIX = ".thoughts-project"

/** One spelling per file (Android's /data/data is /data/user/0), so a record and a catalogue entry meet. */
private fun pathKey(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }

private fun JsonObject.text(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
