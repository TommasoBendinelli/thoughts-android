// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.util.AtomicFile
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** Persisted identity and verified file locations are independent. */
class DocumentIdentityStore(
    private val pdfDirectory: File = PhoneRenderQueue.defaultCanonicalPdfDirectory(),
) {
    private val registryFile = File(pdfDirectory.parentFile, "document-identities.json")
    private val registry = AtomicFile(registryFile)

    private fun published(): List<JsonObject> {
        val index = File(pdfDirectory, ".thoughts-library-index.json")
        if (!index.isFile) return emptyList()
        return index.reader().use { JsonParser.parseReader(it).asJsonObject }
            .getAsJsonArray("pdfs")?.map { it.asJsonObject }.orEmpty()
    }

    private fun readRegistry(): JsonObject {
        if (!registryFile.exists() && !File(registryFile.path + ".bak").exists()) return JsonObject()
        val state = registry.openRead().use { JsonParser.parseReader(it.reader()).asJsonObject }
        check(state.get("schema_version")?.asInt == 1 && state.get("documents")?.isJsonObject == true) {
            "The saved document identities could not be read; restore the registry before importing"
        }
        return state
    }

    fun byFilename(filename: String): JsonObject? = runCatching { synchronized(lock) {
        published().firstOrNull { it.get("filename")?.asString == filename && validId(it.get("document_id")?.asString) }
            ?: readRegistry().getAsJsonObject("documents")?.entrySet()
                ?.firstOrNull { validId(it.key) && it.value.asJsonObject.get("filename")?.asString == filename }
                ?.value?.asJsonObject
    } }.getOrNull()

    fun byId(id: String): JsonObject? = runCatching { synchronized(lock) {
        if (!validId(id)) return@synchronized null
        published().firstOrNull { it.get("document_id")?.asString == id }
            ?: readRegistry().getAsJsonObject("documents")?.getAsJsonObject(id)
    } }.getOrNull()

    fun fileForId(id: String): File? = runCatching { synchronized(lock) {
        val entry = byId(id) ?: return@synchronized null
        val published = File(pdfDirectory, entry.get("filename").asString)
        if (published.isFile) return@synchronized published
        readRegistry().getAsJsonObject("documents")?.getAsJsonObject(id)
            ?.get("local_path")?.asString?.let(::File)?.takeIf { it.isFile }
    } }.getOrNull()

    /** Every document with a permanent id, by filename (published entries first, then local imports). */
    fun documentIdsByFilename(): Map<String, String> = runCatching { synchronized(lock) {
        val ids = linkedMapOf<String, String>()
        published().forEach { entry ->
            val filename = entry.get("filename")?.asString ?: return@forEach
            val id = entry.get("document_id")?.asString?.takeIf(::validId) ?: return@forEach
            ids.putIfAbsent(filename, id)
        }
        readRegistry().getAsJsonObject("documents")?.entrySet()?.forEach { (id, value) ->
            val filename = value.takeIf { it.isJsonObject }?.asJsonObject?.get("filename")?.asString
            if (validId(id) && filename != null) ids.putIfAbsent(filename, id)
        }
        ids
    } }.getOrDefault(emptyMap())

    fun identifier(filename: String): String = requireNotNull(byFilename(filename)?.get("document_id")?.asString)
        { "The document has no permanent Library identifier" }.also { require(validId(it)) }

    fun forFile(file: File): JsonObject? = runCatching { synchronized(lock) {
        if (!file.isFile) return@synchronized null
        if (file.canonicalFile.parentFile == pdfDirectory.canonicalFile) return@synchronized byFilename(file.name)
        readRegistry().getAsJsonObject("documents")?.entrySet()?.firstOrNull {
            val path = it.value.asJsonObject.get("local_path")?.asString
            path != null && File(path).canonicalFile == file.canonicalFile && validId(it.key)
        }?.value?.asJsonObject
    } }.getOrNull()

    fun registerImport(filename: String, sha256: String, file: File): String = synchronized(lock) {
        require(filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf")) && sha256.matches(Regex("[0-9a-f]{64}")) && file.isFile)
        // Strict reading here prevents a corrupt registry from being replaced with new IDs.
        val state = readRegistry()
        val existing = byFilename(filename)
        require(existing == null || existing.get("pdf_sha256")?.asString == sha256) {
            "This filename belongs to another PDF version"
        }
        val id = existing?.get("document_id")?.asString ?: UUID.randomUUID().toString()
        val documents = state.getAsJsonObject("documents") ?: JsonObject()
        documents.add(id, JsonObject().apply {
            addProperty("document_id", id); addProperty("filename", filename)
            addProperty("pdf_sha256", sha256); addProperty("local_path", file.absolutePath)
            addProperty("kind", "unlinked")
        })
        state.addProperty("schema_version", 1)
        state.add("documents", documents)
        registryFile.parentFile?.mkdirs()
        val output = registry.startWrite()
        try { output.write(state.toString().toByteArray()); registry.finishWrite(output) }
        catch (error: Exception) { registry.failWrite(output); throw error }
        id
    }

    /** The identity registered for [file]'s path, even after that local copy was deleted. */
    fun forRegisteredPath(file: File): JsonObject? = runCatching { synchronized(lock) {
        val path = file.absoluteFile.normalize().path
        readRegistry().getAsJsonObject("documents")?.entrySet()?.firstOrNull {
            val registered = it.value.asJsonObject.get("local_path")?.asString
            registered != null && File(registered).absoluteFile.normalize().path == path && validId(it.key)
        }?.value?.asJsonObject
    } }.getOrNull()

    /** Forgets an import that was withdrawn before Fedora received it; published identities stay. */
    fun forgetImport(id: String): Unit = synchronized(lock) {
        if (!validId(id) || published().any { it.get("document_id")?.asString == id }) return
        val state = readRegistry()
        val documents = state.getAsJsonObject("documents") ?: return
        if (documents.remove(id) == null) return
        val output = registry.startWrite()
        try { output.write(state.toString().toByteArray()); registry.finishWrite(output) }
        catch (error: Exception) { registry.failWrite(output); throw error }
    }

    /** Rule R over the registry's local paths, for the default-folder migration. True when anything changed. */
    internal fun rewriteLegacyDefaultFolder(): Boolean = synchronized(lock) {
        if (!registryFile.exists() && !File(registryFile.path + ".bak").exists()) return false
        val raw = registry.openRead().use { it.reader().readText() }
        if (!com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.containsLegacy(raw)) return false
        val state = com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.rewriteJsonValues(JsonParser.parseString(raw))
        val output = registry.startWrite()
        try { output.write(state.toString().toByteArray()); registry.finishWrite(output) }
        catch (error: Exception) { registry.failWrite(output); throw error }
        true
    }

    /** Rule P with [renames] over the registry, for the source-folder-names migration. True when anything changed. */
    internal fun rewriteSourceFolderNames(renames: Map<String, String>): Boolean = synchronized(lock) {
        if (!registryFile.exists() && !File(registryFile.path + ".bak").exists()) return false
        val raw = registry.openRead().use { it.reader().readText() }
        if (!com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.contains(raw, renames)) return false
        val state = com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.rewriteJsonValues(JsonParser.parseString(raw), renames)
        val output = registry.startWrite()
        try { output.write(state.toString().toByteArray()); registry.finishWrite(output) }
        catch (error: Exception) { registry.failWrite(output); throw error }
        true
    }

    companion object {
        private val lock = Any()
        fun validId(id: String?): Boolean = id != null && runCatching {
            val uuid = UUID.fromString(id)
            uuid.version() == 4 && uuid.toString() == id
        }.getOrDefault(false)
    }
}
