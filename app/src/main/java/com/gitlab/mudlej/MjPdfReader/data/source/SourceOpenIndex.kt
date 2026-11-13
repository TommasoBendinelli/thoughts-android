// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** A disposable projection of an already activated, verified source manifest.
 * It contains no comment geometry and never replaces the authoritative manifest.
 */
internal class SourceOpenIndex(
    private val cacheFile: File,
    private val parse: (String, ByteArray) -> OfflineSourceBundle?,
    private val write: (File, ByteArray) -> Unit,
) {
    fun load(bundleId: String, manifest: File): OfflineSourceBundle? {
        readCache(bundleId)?.let { return it }
        if (!manifest.isFile || manifest.length() !in 1..MAX_BYTES) return null
        return runCatching { manifest.inputStream().use { build(bundleId, it) } }.getOrNull()
    }

    /** Reuse metadata sync already validated; do not re-scan the large manifest during activation. */
    fun prepare(bundle: OfflineSourceBundle) {
        runCatching { persist(bundle.bundleId, sourceManifest(bundle)) }
    }

    private fun build(bundleId: String, input: InputStream): OfflineSourceBundle? {
        val digest = MessageDigest.getInstance("SHA-256")
        val retained = JsonObject()
        JsonReader(DigestInputStream(input, digest).bufferedReader(Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in SOURCE_FIELDS) retained.add(name, JsonParser.parseReader(reader))
                else reader.skipValue()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        // Hash the complete original bytes, including the skipped geometry and trailing whitespace.
        if (digest.digest().hex() != bundleId) return null
        val compact = retained.toString().toByteArray(Charsets.UTF_8)
        val bundle = parse(bundleId, compact) ?: return null
        persist(bundleId, retained)
        return bundle
    }

    private fun persist(bundleId: String, retained: JsonObject) {
        val compact = retained.toString().toByteArray(Charsets.UTF_8)
        val envelope = JsonObject().apply {
            addProperty("schema_version", 2)
            addProperty("bundle_id", bundleId)
            addProperty("source_sha256", sha256(compact))
            add("source", retained)
        }
        // A cache write failure must not prevent opening a verified source.
        runCatching { write(cacheFile, envelope.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun sourceManifest(bundle: OfflineSourceBundle) = JsonObject().apply {
        addProperty("schema_version", 3)
        add("sources", JsonArray().apply {
            bundle.sources.values.forEach { source -> add(JsonObject().apply {
                addProperty("path", source.path)
                addProperty("sha256", source.sha256)
                addProperty("object", source.objectId)
                addProperty("bytes", source.bytes)
            }) }
        })
        add("pdfs", JsonArray().apply {
            bundle.pdfs.values.forEach { pdf -> add(JsonObject().apply {
                addProperty("filename", pdf.filename)
                addProperty("pdf_sha256", pdf.pdfSha256)
                addProperty("page_count", pdf.pageCount)
                add("placement_sources", pdf.placementSources)
                add("source_revision_equivalences", pdf.sourceRevisionEquivalences)
                add("segments", JsonArray().apply {
                    pdf.segments.forEach { segment -> add(JsonObject().apply {
                        addProperty("start_page", segment.startPage)
                        addProperty("page_count", segment.pageCount)
                        addProperty("source_available", segment.sourceAvailable)
                        addProperty("primary_source_file", segment.primarySourceFile)
                        addProperty("source_format", segment.sourceFormat)
                        addProperty("fingerprint", segment.fingerprint)
                        addProperty("renderer_evidence_object", segment.rendererEvidenceObject)
                        addProperty("renderer_evidence_sha256", segment.rendererEvidenceObject)
                        addProperty("lookup_format", if (segment.sourceFormat == "markdown") "thoughts-markdown-source-map-v1" else "thoughts-reverse-synctex-v1")
                        addProperty("synctex_object", segment.rawSyncTexObject)
                        addProperty("synctex_sha256", segment.rawSyncTexObject)
                        addProperty("lookup_object", segment.synctexObject)
                        addProperty("lookup_sha256", segment.synctexSha256)
                        add("inputs", JsonArray().apply {
                            segment.inputs.forEach { input -> add(JsonObject().apply {
                                addProperty("synctex_path", input.synctexPath)
                                addProperty("source_file", input.sourceFile)
                                addProperty("source_sha256", input.sourceSha256)
                            }) }
                        })
                    }) }
                })
            }) }
        })
        add("resources", JsonArray().apply {
            bundle.resources.values.forEach { resource -> add(JsonObject().apply {
                addProperty("kind", resource.kind)
                addProperty("path", resource.path)
                addProperty("sha256", resource.sha256)
                addProperty("object", resource.objectId)
                addProperty("bytes", resource.bytes)
                add("pdf_filenames", JsonArray().apply { resource.pdfFilenames.forEach(::add) })
            }) }
        })
    }

    private fun readCache(bundleId: String): OfflineSourceBundle? = runCatching {
        if (!cacheFile.isFile || cacheFile.length() !in 1..MAX_BYTES) return null
        val envelope = cacheFile.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        if (envelope.get("schema_version").asInt != 2 ||
            envelope.get("bundle_id").asString != bundleId) return null
        val compact = envelope.getAsJsonObject("source").toString().toByteArray(Charsets.UTF_8)
        if (sha256(compact) != envelope.get("source_sha256").asString) return null
        parse(bundleId, compact)
    }.getOrNull()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_BYTES = 64_000_000L
        private val SOURCE_FIELDS = setOf("schema_version", "pdfs", "pdf", "sources", "resources")
    }
}
