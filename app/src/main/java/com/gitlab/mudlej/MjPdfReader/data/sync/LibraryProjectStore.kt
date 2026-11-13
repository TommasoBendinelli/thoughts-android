// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder
import com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames
import com.gitlab.mudlej.MjPdfReader.data.source.SourceOwner
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tbend.thoughts.sdk.SyncClient
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** Project metadata and an explicit-Sync outbox, separate from immutable PDF files. */
class LibraryProjectStore(private val context: Context, stateDirectory: File? = null) {
    private val root = stateDirectory ?: File(context.noBackupFilesDir, "thoughts-sync/projects")
    private val state = AtomicFile(File(root, "state.json"))

    private fun read(): JsonObject = if (state.baseFile.exists())
        state.openRead().use { JsonParser.parseString(it.reader().readText()).asJsonObject }
    else JsonObject().apply { add("projects", JsonArray()); add("pending", JsonArray()) }

    private fun write(value: JsonObject) {
        root.mkdirs()
        val output = state.startWrite()
        try { output.write(value.toString().toByteArray()); state.finishWrite(output) }
        catch (error: Exception) { state.failWrite(output); throw error }
        PendingChangesSignal.notifyChanged()
    }

    fun pendingUploads(): List<Pair<String, Long>> = synchronized(lock) {
        read().getAsJsonArray("pending").map { value ->
            val op = value.asJsonObject
            op.get("operation_id").asString to ((op.get("size_bytes")?.asLong ?: 0) + op.toString().toByteArray().size)
        }
    }

    fun pendingCount(): Int = synchronized(lock) { read().getAsJsonArray("pending").size() }

    /**
     * Fedora's connected-folder renames (M, `source_folder_renames`) as last received with the
     * catalogue; empty before Fedora's source-folder-names migration.
     */
    internal fun storedSourceFolderRenames(): List<SourceFolderNames.Rename> = synchronized(lock) {
        SourceFolderNames.parse(read().get(SourceFolderNamesMigration.RENAMES_KEY))
    }

    /** Fetches the catalogue only for M (outside an explicit Sync, the catalogue itself stays as it is). */
    internal fun refreshSourceFolderRenames(client: SyncClient): List<SourceFolderNames.Rename> {
        val renames = client.libraryProjects(null).snapshot?.get(SourceFolderNamesMigration.RENAMES_KEY) ?: return storedSourceFolderRenames()
        synchronized(lock) {
            val state = read()
            if (state.get(SourceFolderNamesMigration.RENAMES_KEY) != renames) {
                state.add(SourceFolderNamesMigration.RENAMES_KEY, renames)
                write(state)
            }
        }
        return storedSourceFolderRenames()
    }

    /** The Project catalogue as Fedora last sent it, without queued changes. */
    internal fun storedCatalogue(): JsonArray = synchronized(lock) { read().getAsJsonArray("projects") ?: JsonArray() }

    fun projects(): JsonArray = synchronized(lock) {
        val state = read()
        val localCreates = state.getAsJsonArray("pending").map { it.asJsonObject }
            .filter { it.get("action")?.asString == "create" }.map { it.get("project_id").asString }.toSet()
        val base = JsonArray().apply {
            state.getAsJsonArray("projects").filter { it.asJsonObject.get("id")?.asString !in localCreates }.forEach(::add)
        }
        val projects = currentManifestProjects(base)
        // A queued change that no longer fits the catalogue (for example a document Fedora has
        // since moved) must not hide every Project: it is skipped here and Sync reports it.
        val segment = DefaultFolder.librarySegment(projects)
        state.getAsJsonArray("pending").forEach { value ->
            try { apply(projects, value.asJsonObject, segment) }
            catch (error: RuntimeException) {
                runCatching { android.util.Log.w("ThoughtsProjects", "A queued Project change could not be shown", error) }
            }
        }
        projects.forEach { it.asJsonObject.apply { add("sources", librarySourceFolders(this)) } }
        projects
    }

    fun enqueue(action: String, project: JsonObject, values: JsonObject = JsonObject()) = synchronized(lock) {
        // Reject before saving: a queued change is replayed onto the catalogue on every read.
        if (action == "include_source") requireIncludableSource(project, values.get("source_file")?.asString.orEmpty())
        val state = read()
        val request = values.deepCopy().apply {
            addProperty("operation_id", UUID.randomUUID().toString())
            addProperty("project_id", project.get("id").asString)
            addProperty("base_revision", project.get("revision")?.asInt ?: 0)
            addProperty("action", action)
            if (action == "include_source") addProperty("new_document_id", UUID.randomUUID().toString())
            if (action == "import_folder") addProperty("document_id", UUID.randomUUID().toString())
        }
        // Preserve the pre-Sync catalogue so queued changes survive a process restart.
        val catalogue = state.getAsJsonArray("projects")
        if (action != "create" && catalogue.none { it.asJsonObject.get("id") == project.get("id") }) catalogue.add(project.deepCopy())
        state.getAsJsonArray("pending").add(request)
        write(state)
    }

    fun stageImport(project: JsonObject, uri: Uri) {
        val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Document.pdf"
        stageImport(project, display) { requireNotNull(context.contentResolver.openInputStream(uri)) { "The document could not be opened" } }
    }

    fun stageImport(project: JsonObject, file: File, displayName: String) =
        stageImport(project, displayName) { file.inputStream() }

    private fun stageImport(project: JsonObject, display: String, openInput: () -> java.io.InputStream) {
        root.mkdirs()
        val file = File(root, UUID.randomUUID().toString() + ".pdf")
        try {
            openInput().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            require(file.inputStream().use { String(it.readNBytes(5)) } == "%PDF-") { "Choose a PDF document" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); var count = input.read(buffer)
                while (count >= 0) { digest.update(buffer, 0, count); count = input.read(buffer) } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val name = display.removeSuffix(".pdf").take(150).ifBlank { "Document" }
            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('_', '.').ifBlank { "document" }
            val projectId = project.get("id").asString
            val projectKey = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray()).joinToString("") { "%02x".format(it) }
            val filename = "${safe}_${hash.take(12)}_${projectKey.take(8)}.pdf"
            val directory = ThoughtsLocalStore.projectDocuments(projectId,
                DefaultFolder.segmentOf(defaultSourceFolder(project)) ?: DefaultFolder.LEGACY)
            check(directory.mkdirs() || directory.isDirectory) { "The Project's document directory could not be created" }
            val staged = File(directory, filename)
            if (staged.exists()) {
                require(com.gitlab.mudlej.MjPdfReader.data.sync.verifiedFileCache.sha256(staged) == hash)
                file.delete()
            } else check(file.renameTo(staged)) { "The imported PDF could not be stored" }
            val documentId = DocumentIdentityStore().registerImport(filename, hash, staged)
            enqueue("import", project, JsonObject().apply {
                addProperty("document_id", documentId)
                addProperty("filename", filename); addProperty("sha256", hash); addProperty("name", name)
                addProperty("local_path", staged.absolutePath); addProperty("size_bytes", staged.length())
            })
        } catch (error: Exception) { file.delete(); throw error }
    }

    fun stageHtmlImport(project: JsonObject, uri: Uri, sourceProject: String): String {
        require(sourceProject in connectedSourceProjects(project)) { "Choose a connected source folder" }
        val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "page.html"
        val name = display.replace(Regex("(?i)\\.html?$"), "")
            .replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('_', '.').take(150).ifBlank { "page" }
        root.mkdirs()
        val temporary = File(root, UUID.randomUUID().toString() + ".html")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            requireNotNull(context.contentResolver.openInputStream(uri)) { "The HTML file could not be opened" }.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_HTML_BYTES) { "Choose an HTML file up to 100 MB" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            require(size > 0) { "Choose a nonempty HTML file" }
            InputStreamReader(temporary.inputStream(), Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)).use { reader ->
                val buffer = CharArray(65536)
                while (reader.read(buffer) != -1) { /* Validate the complete UTF-8 stream. */ }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val filename = "${name}_${hash.take(12)}.html"
            enqueue("import_html", project, JsonObject().apply {
                addProperty("source_project", sourceProject)
                addProperty("filename", filename)
                addProperty("sha256", hash)
                addProperty("local_path", temporary.absolutePath)
                addProperty("size_bytes", size)
            })
            return "thoughts-html:$sourceProject/tex/support/html/$filename"
        } catch (error: Exception) { temporary.delete(); throw error }
    }

    /** Called only by explicit global Sync, under its existing transfer policy. */
    fun synchronize(client: SyncClient) {
        val failures = linkedMapOf<String, Exception>()
        while (true) {
            val operation = synchronized(lock) { read().getAsJsonArray("pending").firstOrNull {
                it.asJsonObject.get("project_id").asString !in failures
            }?.asJsonObject?.deepCopy() } ?: break
            val projectId = operation.get("project_id").asString
            try {
            // A change queued before this phone moved its folders names them as they are called now.
            val payload = SourceFolderNames.followLocal(operation.deepCopy(), SourceFolderNamesMigration.SKIP_KEYS)
            val localPath = payload.remove("local_path")?.asString
            payload.remove("size_bytes")
            if (localPath != null) {
                val file = File(localPath)
                val digest = payload.get("sha256").asString
                val html = payload.get("action").asString == "import_html"
                if (!client.hasObject(digest)) file.inputStream().use {
                    client.uploadObject(it, file.length(), digest, if (html) "text/html" else "application/pdf")
                }
                if (!html) {
                    val documentId = payload.get("document_id").asString
                    check(client.importLibraryPdf(payload.get("filename").asString, digest, documentId).documentId == documentId) { "The server returned a different document identifier" }
                }
            }
            val snapshot = try { client.editLibraryProject(payload) }
            catch (error: org.tbend.thoughts.sdk.ServerException) {
                if (error.statusCode == 404) throw IllegalStateException("Project changes are saved on this device. Install the project update on the Thoughts server, then tap Sync.", error)
                throw error
            }
            synchronized(lock) {
                val state = read()
                val retained = JsonArray()
                state.getAsJsonArray("pending").filter { it.asJsonObject.get("operation_id") != operation.get("operation_id") }.forEach(retained::add)
                state.add("pending", retained); state.add("projects", snapshot.getAsJsonArray("projects")); state.addProperty("downloaded", true)
                snapshot.get(SourceFolderNamesMigration.RENAMES_KEY)?.let { state.add(SourceFolderNamesMigration.RENAMES_KEY, it) }
                // This copy came from an edit response, not from the catalogue the ETag names.
                state.remove("projects_etag")
                write(state)
            }
            // Keep registered import bytes available to readers until publication is activated.
            if (operation.get("action").asString != "import") localPath?.let { File(it).delete() }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { failures[projectId] = error }
        }
        // Revalidate the stored catalogue: an unchanged one answers 304 instead of about 100 KB.
        val cachedEtag = synchronized(lock) {
            read().takeIf { it.get("downloaded")?.asBoolean == true }?.get("projects_etag")?.asString
        }
        val call = try { client.libraryProjects(cachedEtag) }
        catch (error: org.tbend.thoughts.sdk.ServerException) {
            if (error.statusCode == 404 && failures.isEmpty()) return
            throw error
        }
        val snapshot = call.snapshot
        if (snapshot != null) synchronized(lock) {
            val state = read(); state.add("projects", snapshot.getAsJsonArray("projects")); state.addProperty("downloaded", true)
            snapshot.get(SourceFolderNamesMigration.RENAMES_KEY)?.let { state.add(SourceFolderNamesMigration.RENAMES_KEY, it) }
            if (call.etag.isBlank()) state.remove("projects_etag") else state.addProperty("projects_etag", call.etag)
            write(state)
        }
        check(failures.isEmpty()) {
            "Some project changes remain pending; other projects were synchronized: " +
                failures.entries.joinToString("; ") { "${it.key}: ${it.value.message}" }
        }
    }

    /**
     * Applies Rule R to every stored Project state value (queued changes, staged-import paths,
     * the cached catalogue), for the local default-folder migration. True when anything changed.
     */
    internal fun rewriteLegacyDefaultFolder(): Boolean = synchronized(lock) {
        if (!state.baseFile.exists()) return false
        val raw = state.openRead().use { it.reader().readText() }
        val rewritten = rewriteLibraryProjectState(raw) ?: return false
        write(rewritten)
        true
    }

    /**
     * Rule P with [renames] over every stored Project state value (queued changes, the cached
     * catalogue), for the local source-folder-names migration; M itself is kept as Fedora sent
     * it. True when anything changed.
     */
    internal fun rewriteSourceFolderNames(renames: Map<String, String>): Boolean = synchronized(lock) {
        if (!state.baseFile.exists()) return false
        val raw = state.openRead().use { it.reader().readText() }
        val rewritten = rewriteLibraryProjectStateFolderNames(raw, renames) ?: return false
        write(rewritten)
        true
    }

    private fun apply(projects: JsonArray, op: JsonObject, segment: String) {
        val id = op.get("project_id").asString
        var project = projects.firstOrNull { it.asJsonObject.get("id").asString == id }?.asJsonObject
        if (op.get("action").asString == "create") {
            project = JsonObject().apply {
                addProperty("id", id); addProperty("name", op.get("name").asString); addProperty("revision", 0)
                add("documents", JsonArray()); add("sources", JsonArray()); addProperty("target", "")
                add("project_configuration", emptyProjectConfiguration(id, op.get("name").asString, segment))
            }
            projects.add(project)
        }
        project ?: return
        when (op.get("action").asString) {
            "rename" -> {
                project.add("name", op.get("name"))
                project.getAsJsonObject("project_configuration")?.add("name", op.get("name"))
            }
            "remove" -> projects.remove(project)
            "import" -> {
                project.getAsJsonArray("documents").add(JsonObject().apply {
                    add("id", op.get("document_id"))
                    listOf("document_id", "name", "filename", "sha256", "size_bytes", "local_path").forEach { key -> op.get(key)?.let { add(key, it) } }
                    addProperty("managed_path", defaultSourceFolder(project) + "/" + op.get("filename").asString)
                    addProperty("start_page", 1); addProperty("imported", true)
                })
                project.getAsJsonObject("project_configuration")?.let { configuration ->
                    val documentId = op.get("document_id").asString
                    configuration.getAsJsonArray("documents").add(JsonObject().apply {
                        addProperty("document_id", documentId)
                        add("source", JsonObject().apply {
                            add("folder_id", configuration.get("default_folder_id"))
                            add("path", op.get("filename"))
                        })
                        addProperty("format", "pdf")
                        addProperty("included", true)
                    })
                    val rendering = configuration.getAsJsonObject("rendering") ?: JsonObject().also { configuration.add("rendering", it) }
                    val names = configuration.getAsJsonObject("document_names") ?: JsonObject().also { configuration.add("document_names", it) }
                    rendering.add(documentId, JsonObject().apply { add("sha256", op.get("sha256")) })
                    names.add(documentId, op.get("name"))
                }
            }
            "include_source" -> {
                val path = queuedSourceFile(project, op.get("source_file").asString)
                if (inDefaultFolder(project, path)) {
                    includeDefaultFolderDocument(project, op.deepCopy().apply { addProperty("source_file", path) })
                } else project.getAsJsonArray("documents").add(JsonObject().apply {
                add("document_id", op.get("new_document_id"))
                val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
                val owner = connectedSourceProjects(project).singleOrNull { path.startsWith("$it/") }
                    ?: error("Choose a source document in a connected folder")
                addProperty("id", owner + "/tex/source/document_" + hash + ".tex")
                addProperty("name", File(path).nameWithoutExtension.replace('_', ' '))
                add("filename", project.get("target")); addProperty("source_file", path); addProperty("size_bytes", -1); addProperty("start_page", 0)
                })
            }
            "rename_document" -> renameLibraryDocument(project, op.get("document_id").asString, op.get("name").asString)
            "remove_document" -> {
                val documentId = op.get("document_id").asString
                project.add("documents", JsonArray().apply {
                    project.getAsJsonArray("documents").filter { it.asJsonObject.run {
                        get("document_id")?.asString != documentId && get("id")?.asString != documentId
                    } }.forEach(::add)
                })
                project.getAsJsonObject("project_configuration")?.let { configuration ->
                    configuration.add("documents", JsonArray().apply {
                        configuration.getAsJsonArray("documents").filter {
                            it.asJsonObject.get("document_id").asString != documentId
                        }.forEach(::add)
                    })
                    configuration.getAsJsonObject("rendering")?.remove(documentId)
                    configuration.getAsJsonObject("document_names")?.remove(documentId)
                }
            }
            "order" -> {
                val documents = project.getAsJsonArray("documents")
                val ordered = op.getAsJsonArray("documents").mapNotNull { id ->
                    documents.firstOrNull { it.asJsonObject.run { get("document_id") == id || get("id") == id } }
                }
                project.add("documents", JsonArray().apply { ordered.forEach(::add) })
                project.getAsJsonObject("project_configuration")?.let { configuration ->
                    val selected = ordered.mapNotNull { it.asJsonObject.get("document_id")?.asString }.toSet()
                    val declarations = configuration.getAsJsonArray("documents")
                    val byId = declarations.associateBy { it.asJsonObject.get("document_id").asString }
                    val reordered = ordered.mapNotNull { byId[it.asJsonObject.get("document_id")?.asString] }.iterator()
                    configuration.add("documents", JsonArray().apply {
                        declarations.forEach { add(if (it.asJsonObject.get("document_id").asString in selected) reordered.next() else it) }
                    })
                }
            }
        }
        project.addProperty("revision", (project.get("revision")?.asInt ?: 0) + 1)
    }

    companion object {
        private const val MAX_HTML_BYTES = 100_000_000L
        private val lock = Any()
    }
}

/** Rule R over a stored Project state (catalogue, queued changes, staged imports); null when unchanged. */
internal fun rewriteLibraryProjectState(raw: String): JsonObject? {
    if (!DefaultFolder.containsLegacy(raw)) return null
    val parsed = JsonParser.parseString(raw)
    val rewritten = DefaultFolder.rewriteJsonValues(parsed)
    return if (rewritten === parsed) null else rewritten.asJsonObject
}

/**
 * Rule P with [renames] over a stored Project state; null when unchanged. M itself is kept as
 * Fedora sent it, and so are folder labels (`source_folders[].folder`): Rename… names, which
 * this change never touches even when one reads like the old folder name.
 */
internal fun rewriteLibraryProjectStateFolderNames(raw: String, renames: Map<String, String>): JsonObject? {
    if (!SourceFolderNames.contains(raw, renames)) return null
    val parsed = JsonParser.parseString(raw)
    val rewritten = SourceFolderNames.rewriteJsonValues(parsed, renames) { key ->
        SourceFolderNamesMigration.SKIP_KEYS(key) || key == "folder"
    }
    return if (rewritten === parsed) null else rewritten.asJsonObject
}

/** Changes only the name shown in the Library; the PDF filename and document identity stay. */
internal fun renameLibraryDocument(project: JsonObject, documentId: String, name: String) {
    val document = project.getAsJsonArray("documents")?.firstOrNull { it.asJsonObject.run {
        get("document_id")?.asString == documentId || get("id")?.asString == documentId
    } }?.asJsonObject ?: return
    document.addProperty("name", name)
    project.getAsJsonObject("project_configuration")?.let { configuration ->
        val names = configuration.getAsJsonObject("document_names") ?: JsonObject().also { configuration.add("document_names", it) }
        names.addProperty(document.get("document_id")?.asString ?: documentId, name)
    }
}

internal fun connectedSourceProjects(project: JsonObject): List<String> {
    val configuration = project.getAsJsonObject("project_configuration")
    if (configuration != null) {
        val defaultId = configuration.get("default_folder_id")?.asString
        return configuration.getAsJsonArray("source_folders").mapNotNull { value ->
            val folder = value.asJsonObject
            folder.get("path")?.asString?.takeIf {
                folder.get("folder_id")?.asString != defaultId && SourceOwner.isOwner(it) && !SourceOwner.isDefaultFolder(it)
            }
        }.distinct()
    }
    return emptyList()
}

/** Markdown and LaTeX files can become documents; the configuration file, PDFs and other files cannot. */
internal fun includableSourceFormat(path: String): String? = when {
    path.endsWith(".md", ignoreCase = true) || path.endsWith(".markdown", ignoreCase = true) -> "markdown"
    path.endsWith(".tex", ignoreCase = true) -> "latex"
    else -> null
}

internal fun inDefaultFolder(project: JsonObject, path: String): Boolean =
    SourceOwner.of(path)?.let { SourceOwner.isDefaultFolder(it) && it == defaultSourceFolder(project) } == true

/** Whether [path] may be offered as "Add to final PDF": a document in this Project's default or a connected folder. */
internal fun isIncludableSource(project: JsonObject, path: String): Boolean = includableSourceFormat(path) != null &&
    (inDefaultFolder(project, path) || SourceOwner.of(path)?.let { it in connectedSourceProjects(project) } == true)

internal fun requireIncludableSource(project: JsonObject, path: String) {
    require(includableSourceFormat(path) != null) { "Choose a Markdown or LaTeX source document" }
    require(isIncludableSource(project, path)) { "Choose a source document in this project" }
}

/**
 * The path a queued change names, in the spelling of [project]'s default folder now: a change
 * queued before the default-folder rename still says `documents` (Rule R; the local migration
 * also rewrites the stored change).
 */
internal fun queuedSourceFile(project: JsonObject, path: String): String {
    // Likewise a connected folder Fedora renamed since (source-folder-names; Rule P with M).
    SourceFolderNames.followLocal(path).takeIf { it != path }?.let { return it }
    val rewritten = DefaultFolder.rewriteLegacyDefaultFolder(path)
    return if (rewritten != path && SourceOwner.of(rewritten) == defaultSourceFolder(project)) rewritten else path
}

/**
 * A default-folder document's Library PDF; a declared `rendering[document_id].output` wins.
 * Rule 4 of the default-folder rename: the name hashes the owner in its legacy spelling
 * (`…/documents`), so no PDF is renamed when the folder is.
 */
internal fun defaultFolderDocumentOutput(path: String): String =
    "document_" + MessageDigest.getInstance("SHA-256").digest(DefaultFolder.legacySpelling(path).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(20) + ".pdf"

/** Mirror the server's declaration of a default-folder document until the next Sync. */
internal fun includeDefaultFolderDocument(project: JsonObject, op: JsonObject) {
    val path = op.get("source_file").asString
    val folder = defaultSourceFolder(project)
    val relative = path.removePrefix("$folder/")
    val format = includableSourceFormat(path) ?: return
    val configuration = project.getAsJsonObject("project_configuration")
    val declared = configuration?.getAsJsonArray("documents")?.any { value ->
        val source = value.asJsonObject.getAsJsonObject("source")
        source?.get("folder_id") == configuration.get("default_folder_id") && source?.get("path")?.asString == relative
    } == true
    if (declared) return
    val documentId = op.get("new_document_id").asString
    val output = defaultFolderDocumentOutput(path)
    project.getAsJsonArray("documents").add(JsonObject().apply {
        addProperty("id", path); addProperty("document_id", documentId)
        addProperty("name", File(path).nameWithoutExtension.replace('_', ' '))
        addProperty("filename", output); addProperty("source_file", path); addProperty("source_project", folder)
        addProperty("size_bytes", -1); addProperty("start_page", 0)
    })
    configuration ?: return
    configuration.getAsJsonArray("documents").add(JsonObject().apply {
        addProperty("document_id", documentId)
        add("source", JsonObject().apply {
            add("folder_id", configuration.get("default_folder_id")); addProperty("path", relative)
        })
        addProperty("format", format); addProperty("included", true)
    })
    val rendering = configuration.getAsJsonObject("rendering") ?: JsonObject().also { configuration.add("rendering", it) }
    rendering.add(documentId, JsonObject().apply { addProperty("output", output) })
}

/**
 * The Project's default folder as its manifest names it (Rule 2); without a manifest entry, the
 * folder named [segment] (Rule 5: the caller passes the segment the Library uses).
 */
internal fun defaultSourceFolder(project: JsonObject, segment: String = DefaultFolder.LEGACY): String {
    project.getAsJsonObject("project_configuration")?.let(DefaultFolder::configuredDefaultFolder)?.let { return it }
    return DefaultFolder.path(project.get("id").asString, segment)
}
