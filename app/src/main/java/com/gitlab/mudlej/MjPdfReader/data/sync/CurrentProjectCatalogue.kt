// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Cached publications never establish a Project or its document membership. */
internal fun currentManifestProjects(cached: JsonArray): JsonArray = JsonArray().apply {
    val ids = mutableSetOf<String>()
    for (raw in cached) {
        val project = raw.asJsonObject.deepCopy()
        val configuration = project.getAsJsonObject("project_configuration") ?: continue
        require(configuration.get("schema_version")?.asInt == 5 &&
            configuration.get("kind")?.asString == "thoughts-project") { "Unsupported Project configuration; Sync the current manifest." }
        val id = configuration.get("id").asString
        require(project.get("id")?.asString == id && ids.add(id)) { "Conflicting Project identity." }
        val documents = project.getAsJsonArray("documents").associateBy { it.asJsonObject.get("document_id")?.asString }
        val ordered = JsonArray()
        for (value in configuration.getAsJsonArray("documents")) {
            val declaration = value.asJsonObject
            if (!declaration.get("included").asBoolean) continue
            val documentId = declaration.get("document_id").asString
            val document = requireNotNull(documents[documentId]) {
                "Project document metadata is missing; Sync the current catalogue."
            }.asJsonObject.deepCopy()
            configuration.getAsJsonObject("document_names")?.get(documentId)?.let { document.add("name", it) }
            ordered.add(document)
        }
        project.add("name", configuration.get("name"))
        project.add("documents", ordered)
        add(project)
    }
}

/**
 * An explicit local Create operation uses the same stable folder identities as the server. Its
 * default folder is named [segment], the one the Library already uses (Rule 5 of the
 * default-folder rename); Fedora decides at Sync.
 */
internal fun emptyProjectConfiguration(id: String, name: String, segment: String = DefaultFolder.LEGACY): JsonObject {
    fun identity(prefix: String): String {
        val namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
        val bytes = ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array()
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes + (prefix + id).toByteArray(Charsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(digest)
        return UUID(buffer.long, buffer.long).toString()
    }
    val folderId = identity("thoughts:default-folder:")
    return JsonObject().apply {
        addProperty("schema_version", 5); addProperty("kind", "thoughts-project")
        addProperty("id", id); addProperty("name", name); addProperty("default_folder_id", folderId)
        add("source_folders", JsonArray().apply { add(JsonObject().apply {
            addProperty("folder_id", folderId); addProperty("path", DefaultFolder.path(id, segment))
            addProperty("document_id", identity("thoughts:default-document:"))
        }) })
        add("documents", JsonArray()); add("rendering", JsonObject()); add("document_names", JsonObject())
    }
}

/**
 * The documents unpublished in their Project (protocol/project-document-publication.md §4): those
 * listed in a Project's `unpublished_documents` or declared with `"included": false`, minus every
 * document published in some Project. Their cards are kept but never offered for review.
 */
internal fun unpublishedDocumentIds(projects: JsonArray): Set<String> {
    val unpublished = mutableSetOf<String>()
    val published = mutableSetOf<String>()
    fun id(value: com.google.gson.JsonElement) = value.takeIf { it.isJsonObject }?.asJsonObject?.get("document_id")
        ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
    for (raw in projects) {
        val project = raw.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        project.getAsJsonArray("documents")?.forEach { value -> id(value)?.let(published::add) }
        project.getAsJsonArray("unpublished_documents")?.forEach { value -> id(value)?.let(unpublished::add) }
        project.getAsJsonObject("project_configuration")?.getAsJsonArray("documents")?.forEach { value ->
            val included = value.takeIf { it.isJsonObject }?.asJsonObject?.get("included")
            if (included?.isJsonPrimitive == true && !included.asBoolean) id(value)?.let(unpublished::add)
        }
    }
    return unpublished - published
}
