// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

/** The selected component can share PDF bytes with other documents. Keep its title identity separate. */
const val EXTRA_DISPLAY_DOCUMENT_ID = "thoughts.display_document_id"
const val EXTRA_DISPLAY_PROJECT_ID = "thoughts.display_project_id"
const val EXTRA_DISPLAY_TITLE = "thoughts.display_title"
/** A word the reader highlights with its search after opening at the requested page (global search). */
const val EXTRA_SEARCH_HIGHLIGHT = "thoughts.search_highlight"

internal data class ReaderTitleTarget(
    val documentId: String? = null,
    val projectId: String? = null,
    val fallbackTitle: String? = null,
)

internal fun readerDisplayTitle(
    projects: JsonArray,
    publicationTitles: Map<String, String>,
    target: ReaderTitleTarget,
    path: String,
    canonicalDirectory: File,
): String? {
    fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    val file = File(path)
    val canonical = file.absoluteFile.parentFile?.canonicalFile == canonicalDirectory.canonicalFile
    val documentMatches = mutableListOf<Pair<String, String>>()
    val projectMatches = mutableListOf<String>()
    for (value in projects) {
        val project = value.asJsonObject
        if (!target.projectId.isNullOrBlank() && project.text("id") == target.projectId) {
            return project.text("name").takeIf(String::isNotBlank)
        }
        if (canonical && project.text("target") == file.name) projectMatches += project.text("name")
        for (raw in project.getAsJsonArray("documents") ?: JsonArray()) {
            val document = raw.asJsonObject
            val id = document.text("document_id")
            val name = document.text("name")
            if (!target.documentId.isNullOrBlank() && id == target.documentId) return name.takeIf(String::isNotBlank)
            val localPath = document.text("local_path")
            if ((canonical && document.text("filename") == file.name) ||
                (localPath.isNotBlank() && File(localPath).canonicalFile == file.canonicalFile)) {
                documentMatches += id to name
            }
        }
    }
    // A selected component must not acquire a sibling's label while metadata is unavailable.
    if (!target.documentId.isNullOrBlank() || !target.projectId.isNullOrBlank()) {
        return target.fallbackTitle?.takeIf(String::isNotBlank)
    }
    projectMatches.filter(String::isNotBlank).distinct().singleOrNull()?.let { return it }
    documentMatches.distinct().singleOrNull()?.second?.takeIf(String::isNotBlank)?.let { return it }
    return if (canonical) publicationTitles[file.name]?.takeIf(String::isNotBlank) else null
}

/** The Library project that contains this reader's document, as the Library shows it; null when none does. */
internal fun readerProjectName(
    projects: JsonArray,
    target: ReaderTitleTarget,
    path: String,
    canonicalDirectory: File,
): String? {
    fun JsonObject.text(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    val file = File(path)
    val canonical = file.absoluteFile.parentFile?.canonicalFile == canonicalDirectory.canonicalFile
    for (value in projects) {
        val project = value.asJsonObject
        val name = project.text("name").trim().takeIf(String::isNotBlank) ?: continue
        if (!target.projectId.isNullOrBlank() && project.text("id") == target.projectId) return name
        if (canonical && project.text("target") == file.name) return name
        for (raw in project.getAsJsonArray("documents") ?: JsonArray()) {
            val document = raw.asJsonObject
            if (!target.documentId.isNullOrBlank() && document.text("document_id") == target.documentId) return name
            val localPath = document.text("local_path")
            if ((canonical && file.name in setOf(document.text("filename"), document.text("attachment_filename"))) ||
                (localPath.isNotBlank() && File(localPath).canonicalFile == file.canonicalFile)) return name
        }
    }
    return null
}
