// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Project manifests define roots; cached inventory supplies their files, never extra roots. */
internal fun librarySourceFolders(project: JsonObject): JsonArray {
    val configuration = project.getAsJsonObject("project_configuration") ?: return JsonArray()
    val saved = project.getAsJsonArray("sources")?.map { it.asJsonObject }.orEmpty()
    val labels = project.getAsJsonArray("source_folders")?.map { it.asJsonObject }
        ?.associate { it.get("project").asString to it.get("folder")?.asString }.orEmpty()
    val documents = project.getAsJsonArray("documents")?.map { it.asJsonObject }.orEmpty()
    return JsonArray().apply {
        for (value in configuration.getAsJsonArray("source_folders")) {
            val folder = value.asJsonObject
            val path = folder.get("path").asString
            val prefix = "$path/"
            val isDefault = folder.get("folder_id") == configuration.get("default_folder_id")
            val location = saved.firstOrNull { it.get("id")?.asString == path }?.deepCopy() ?: JsonObject()
            location.addProperty("id", path)
            location.add("folder_id", folder.get("folder_id"))
            location.addProperty("name", if (isDefault) "Default source folder" else labels[path]?.takeIf { it.isNotBlank() } ?: path)
            location.addProperty("kind", "local")
            val files = linkedMapOf<String, JsonObject>()
            for (source in saved) {
                val id = source.get("id")?.asString.orEmpty()
                if (id != path && !id.startsWith(prefix)) continue
                for (raw in source.getAsJsonArray("files") ?: JsonArray()) {
                    val file = raw.asJsonObject
                    val fileId = file.get("id")?.asString.orEmpty()
                    if (!fileId.startsWith(prefix)) continue
                    files.putIfAbsent(fileId, file.deepCopy().apply {
                        addProperty("relative_path", fileId.removePrefix(prefix))
                        addProperty("name", fileId.substringAfterLast('/'))
                    })
                }
            }
            if (isDefault) {
                // Locally created Projects and PDF imports are visible before their first Sync.
                fun include(relative: String, metadata: JsonObject? = null) {
                    val id = prefix + relative
                    files.putIfAbsent(id, JsonObject().apply {
                        addProperty("id", id); addProperty("name", relative.substringAfterLast('/'))
                        addProperty("relative_path", relative)
                        addProperty("size_bytes", metadata?.get("size_bytes")?.asLong ?: -1L)
                        metadata?.get("local_path")?.let { add("local_path", it) }
                    })
                }
                include("thoughts-project.json")
                for (raw in configuration.getAsJsonArray("documents")) {
                    val document = raw.asJsonObject
                    val source = document.getAsJsonObject("source")
                    if (document.get("format")?.asString == "pdf" && source.get("folder_id") == folder.get("folder_id")) {
                        include(source.get("path").asString,
                            documents.firstOrNull { it.get("document_id") == document.get("document_id") })
                    }
                }
            }
            location.add("files", JsonArray().apply { files.values.forEach(::add) })
            add(location)
        }
    }
}
