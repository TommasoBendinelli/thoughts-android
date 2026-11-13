// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/** Identities and coordinates of the exact immutable generation opened by the reader. */
data class ProjectReadingContext(val manifest: ProjectReading, val revision: String, val documents: List<Document>) {
    fun exportDescription() = JsonObject().apply {
        addProperty("id", manifest.id); addProperty("name", manifest.title); addProperty("reading_revision", revision)
    }
    data class Document(val reading: ProjectReading.Document, val filename: String, val file: File, val sourcePackage: JsonObject)
    fun locate(page: Int): Document? = documents.firstOrNull { page in it.reading.firstPage until it.reading.firstPage + it.reading.pages }
    companion object {
        fun load(file: File): ProjectReadingContext {
            require(file.name.endsWith(".thoughts-project") && file.length() in 1..16*1024*1024)
            val bytes = file.readBytes()
            val manifest = ProjectReading.parse(bytes)
            val revision = digest(bytes)
            val generation = JsonParser.parseString(File(file.parentFile, ".thoughts-generation.json").readText()).asJsonObject
            require(generation.getAsJsonArray("project_readings").any {
                val row = it.asJsonObject
                row.get("filename").asString == file.name && row.get("sha256").asString == revision && row.get("project_id").asString == manifest.id
            }) { "The project reading generation is not verified" }
            val documents = manifest.documents.map { entry ->
                val raw = File(file.parentFile, "objects/${entry.packageSha256}").readBytes()
                require(digest(raw) == entry.packageSha256)
                val pack = JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject
                val kind = pack.get("kind").asString
                require(kind in setOf("thoughts-document-source-artifact", "thoughts-document-reading-package"))
                val pdf = pack.getAsJsonObject("pdf") ?: pack
                val filename = pack.get("filename").asString
                require(filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf")))
                require(pdf.get("pdf_sha256").asString == entry.pdfSha256 && pdf.get("page_count").asInt == entry.pages)
                if (kind == "thoughts-document-reading-package") require(pack.get("document_id").asString == entry.id)
                val source = File(file.parentFile, "objects/${entry.pdfSha256}")
                require(source.isFile && source.length() == entry.size)
                // PDFium verifies each child's full SHA before opening the composition.
                Document(entry, filename, source, pack)
            }
            return ProjectReadingContext(manifest, revision, documents)
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
