// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.agent

import com.google.gson.JsonArray
import org.tbend.agent.AgentError
import java.text.Normalizer

/** A Project of the local catalogue as the agent API lists it. */
internal data class AgentProject(val id: String, val name: String, val documentIds: List<String>)

/** A Library PDF with a permanent id, as the agent API lists and opens it. */
internal data class AgentDocument(
    val id: String,
    val title: String,
    val filename: String,
    val projectId: String,
    val project: String,
    val archived: Boolean,
)

/** Lowercase without accents, like Agent.fold (which cannot load in host JVM tests: it needs a Looper). */
internal fun agentFold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()

internal fun agentWords(text: String): List<String> =
    agentFold(text).split(Regex("[\\s_]+")).filter(String::isNotEmpty)

/** The Projects of the catalogue (LibraryProjectStore.projects()), with their documents' ids, sorted by name. */
internal fun agentProjects(projects: JsonArray): List<AgentProject> = projects.mapNotNull { element ->
    val project = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
    fun text(key: String) = project.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    val id = text("id").ifEmpty { return@mapNotNull null }
    val documents = project.get("documents")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { document ->
        val entry = document.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        // Like projectNamesByDocumentId: "document_id", else "id".
        listOf("document_id", "id").firstNotNullOfOrNull { key ->
            entry.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
        }
    }.orEmpty()
    AgentProject(id, text("name").ifEmpty { id }, documents.distinct())
}.distinctBy { it.id }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

/**
 * Every Library PDF with a permanent id, labelled like the link-override picker (display name, else
 * the filename without .pdf) with its Project, sorted by title.
 */
internal fun agentLibraryDocuments(
    documentIdsByFilename: Map<String, String>,
    displayNamesByFilename: Map<String, String>,
    projects: List<AgentProject>,
    archivedFilenames: Set<String>,
): List<AgentDocument> {
    val projectByDocument = linkedMapOf<String, AgentProject>()
    projects.forEach { project -> project.documentIds.forEach { projectByDocument.putIfAbsent(it, project) } }
    return documentIdsByFilename.entries.distinctBy { it.value }.map { (filename, id) ->
        val project = projectByDocument[id]
        AgentDocument(
            id = id,
            title = displayNamesByFilename[filename]?.trim().orEmpty().ifBlank { filename.removeSuffix(".pdf") },
            filename = filename,
            projectId = project?.id.orEmpty(),
            project = project?.name.orEmpty(),
            archived = filename in archivedFilenames,
        )
    }.sortedWith(compareBy({ it.title.lowercase() }, { it.project.lowercase() }, { it.id }))
}

/** True when every word of [query] is in [text] (accents and case ignored); a blank query matches. */
internal fun agentMatches(text: String, query: String): Boolean {
    val words = agentWords(query)
    val folded = agentFold(text)
    return words.all { it in folded }
}

/** A Project named by its id or by words of its name; blank matches every Project. */
internal fun agentProjectMatches(document: AgentDocument, project: String): Boolean =
    project.isBlank() || document.projectId == project.trim() || (document.project.isNotEmpty() && agentMatches(document.project, project))

/** list_documents: words of the title or filename, a Project, archived ones only when asked. */
internal fun filterAgentDocuments(
    documents: List<AgentDocument>,
    query: String,
    project: String,
    includeArchived: Boolean,
): List<AgentDocument> = documents.filter { document ->
    (includeArchived || !document.archived) &&
        agentProjectMatches(document, project) &&
        agentMatches("${document.title} ${document.filename}", query)
}

/**
 * Picks one item by [id] or by the words of [query] in [searchText]. An exact title wins; when
 * several match and [firstWhenAmbiguous] is false, the error names them so the caller can say more.
 */
internal fun <T> agentPick(
    items: List<T>,
    id: String,
    query: String,
    idOf: (T) -> String,
    titleOf: (T) -> String,
    searchText: (T) -> String = titleOf,
    describe: (T) -> String = titleOf,
    kind: String,
    listCommand: String,
    firstWhenAmbiguous: Boolean = false,
): T {
    if (id.isNotBlank()) {
        return items.firstOrNull { idOf(it) == id.trim() }
            ?: throw AgentError("no $kind has the id '${id.trim()}'; use $listCommand")
    }
    val words = agentWords(query)
    if (words.isEmpty()) throw AgentError("give an id or a query (words of the title); use $listCommand")
    val matches = items.filter { agentMatches(searchText(it), query) }
    val phrase = words.joinToString(" ")
    matches.firstOrNull { agentWords(titleOf(it)).joinToString(" ") == phrase }?.let { return it }
    return when {
        matches.isEmpty() -> throw AgentError("no $kind matches '$phrase'; use $listCommand")
        matches.size == 1 || firstWhenAmbiguous -> matches.first()
        else -> throw AgentError(
            "${matches.size} ${kind}s match '$phrase': " +
                matches.take(AMBIGUOUS_SHOWN).joinToString("; ") { "${describe(it)} (id ${idOf(it)})" } +
                (if (matches.size > AMBIGUOUS_SHOWN) "; …" else "") +
                ". Add words or give an id",
        )
    }
}

/** "Title (Project)" or "Title". */
internal fun AgentDocument.label(): String = if (project.isEmpty()) title else "$title ($project)"

/** A 1-based page from the agent's argument, as the reader's 0-based index; null when absent. */
internal fun agentPageIndex(page: Int?): Int? = when {
    page == null -> null
    page < 1 -> throw AgentError("page is 1-based: give 1 or more")
    else -> page - 1
}

private const val AMBIGUOUS_SHOWN = 6
