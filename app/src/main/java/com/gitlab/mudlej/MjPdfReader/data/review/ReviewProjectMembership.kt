// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonArray

/** Use Library membership for both project targets and their individual documents. */
internal fun reviewProjectsByFilename(projects: JsonArray): Map<String, String> {
    val memberships = linkedMapOf<String, MutableSet<String>>()
    projects.forEach { value ->
        val project = value.asJsonObject
        val id = project.get("id")?.asString?.takeIf(String::isNotBlank) ?: return@forEach
        val filenames = buildList {
            project.get("target")?.asString?.let(::add)
            project.getAsJsonArray("documents")?.forEach { document ->
                document.asJsonObject.get("filename")?.asString?.let(::add)
            }
        }
        filenames.filter(String::isNotBlank).forEach { filename ->
            memberships.getOrPut(filename) { linkedSetOf() }.add(id)
        }
    }
    // A shared PDF cannot identify which project's cards the user intends to change.
    return memberships.mapNotNull { (filename, ids) ->
        ids.singleOrNull()?.let { filename to it }
    }.toMap()
}
