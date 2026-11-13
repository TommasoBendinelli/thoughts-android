// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.notes

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryProjectStore

internal data class NoteProject(val id: String, val name: String)

/** Uses the same local project catalogue as Library; no network access. */
internal fun noteProjects(context: Context): List<NoteProject> {
    return LibraryProjectStore(context).projects().mapNotNull { entry ->
        val project = entry.asJsonObject
        val id = project.get("id")?.asString.orEmpty()
        val name = project.get("name")?.asString.orEmpty()
        if (id.isBlank()) null else NoteProject(id, name.ifBlank { id })
    }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
}
