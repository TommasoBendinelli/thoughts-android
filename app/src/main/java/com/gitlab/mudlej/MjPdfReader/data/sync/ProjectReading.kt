// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A project is a verified sequence of document PDFs, never a merged reading file. */
data class ProjectReading(val id: String, val title: String, val documents: List<Document>) {
    data class Document(val id: String, val title: String, val pdfSha256: String,
                        val packageSha256: String, val size: Long, val pages: Int, val firstPage: Int)

    val pageCount: Int get() = documents.sumOf { it.pages }

    fun locate(page: Int): Pair<Document, Int>? {
        if (page !in 0 until pageCount) return null
        val document = documents.first { page < it.firstPage + it.pages }
        return document to (page - document.firstPage)
    }

    fun projectPage(documentId: String, localPage: Int): Int? =
        documents.firstOrNull { it.id == documentId && localPage in 0 until it.pages }
            ?.let { it.firstPage + localPage }

    companion object {
        private val digest = Regex("[0-9a-f]{64}")
        private fun JsonObject.identity(key: String): String = get(key).let {
            require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString.also { text -> require(text.isNotEmpty() && text.length <= 1024 && text.none { c -> c.code < 32 }) }
        }
        private fun JsonObject.integer(key: String): Long = get(key).let {
            require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
            it.asBigDecimal.longValueExact()
        }
        fun parse(bytes: ByteArray): ProjectReading {
            val value = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(value.integer("schema_version") == 1L && value.identity("kind") == "thoughts-project-reading")
            val rows = value.getAsJsonArray("documents")
            require(rows.size() <= 10000)
            val seen = mutableSetOf<String>()
            val sizes = mutableMapOf<String, Long>()
            var first = 0
            val documents = rows.map { row ->
                val item = row.asJsonObject
                val id = item.identity("document_id")
                val pdf = item.identity("pdf_sha256")
                val pack = item.identity("package_sha256")
                val pages = item.integer("page_count")
                val size = item.integer("pdf_size_bytes")
                require(seen.add(id) && digest.matches(pdf) && digest.matches(pack))
                require(pages in 1..1000000 && size > 0 && first + pages <= 10000000)
                require(sizes.put(pdf, size).let { it == null || it == size })
                Document(id, item.identity("title"), pdf, pack, size, pages.toInt(), first)
                    .also { first += it.pages }
            }
            require(value.integer("page_count") == first.toLong())
            return ProjectReading(value.identity("project_id"), value.identity("title"), documents)
        }
    }
}
