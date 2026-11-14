// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.tbend.thoughts.sdk.ServerException

/**
 * Whether the server refused the change itself (the request, not the connection or the moment), so
 * that sending the same change again cannot succeed. On 13 November 2026 one Project change the
 * server answered with 409 stayed queued, was sent again by every Sync, and made every Sync fail
 * before it pulled anything. Such a change is set aside: kept, shown in the Sync details with the
 * server's reason, and not sent again until the user retries it; the Sync goes on.
 */
internal fun isRefusedByServer(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause?.takeUnless { cause -> cause === it } }.take(16)
        .filterIsInstance<ServerException>().any { it.statusCode in REFUSAL_STATUS_CODES }

private val REFUSAL_STATUS_CODES = setOf(400, 409, 413, 422)

/** A queued Project change the server refused, as the Sync details show it. */
data class RefusedProjectChange(
    val operationId: String,
    val projectId: String,
    val action: String,
    /** What the change does, in words. */
    val description: String,
    /** The server's reason. */
    val detail: String,
    val refusedAtEpochMillis: Long,
    /** The PDF a refused import would have added; null for every other change. */
    val pdfFilename: String? = null,
)

private const val REFUSED_KEY = "refused"

private fun JsonObject.text(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

private fun pendingChanges(state: JsonObject): List<JsonObject> =
    (state.getAsJsonArray("pending") ?: JsonArray()).map { it.asJsonObject }

internal fun isRefusedProjectChange(change: JsonObject): Boolean = change.get(REFUSED_KEY)?.isJsonObject == true

/**
 * The next queued Project change a Sync sends: the first that is not set aside, of a Project that
 * has no change set aside before it (a later change may build on the refused one, so it waits for
 * the user's decision) and none that failed in this Sync ([failedProjects]).
 */
internal fun nextProjectChangeToSend(state: JsonObject, failedProjects: Set<String>): JsonObject? {
    val waiting = hashSetOf<String>()
    for (change in pendingChanges(state)) {
        val project = change.text("project_id")
        if (isRefusedProjectChange(change)) { waiting += project; continue }
        if (project !in waiting && project !in failedProjects) return change
    }
    return null
}

/** What one Sync did with the queued Project changes it could not deliver. */
internal class QueuedProjectChangesOutcome(
    /** Operations the server refused in this Sync; they were set aside. */
    val refused: Set<String>,
    /** Projects whose change failed otherwise, with the failure; the Sync fails on these. */
    val failures: Map<String, Exception>,
)

/**
 * One Sync's pass over the queue: sends each change [next] offers until none is left. A change
 * the server refuses ([isRefusedByServer]) is handed to [setAside] and the pass goes on with the
 * other Projects; any other failure stops that Project's changes for this Sync, as before.
 */
internal fun drainQueuedProjectChanges(
    next: (failedProjects: Set<String>) -> JsonObject?,
    send: (JsonObject) -> Unit,
    setAside: (change: JsonObject, error: Exception) -> Unit,
): QueuedProjectChangesOutcome {
    val failures = linkedMapOf<String, Exception>()
    val refused = linkedSetOf<String>()
    val offered = hashSetOf<String>()
    while (true) {
        val change = next(failures.keys) ?: break
        val operationId = change.text("operation_id")
        val projectId = change.text("project_id")
        // A change that is offered again neither left the queue nor was set aside: stop its Project.
        if (!offered.add(operationId)) {
            failures[projectId] = IllegalStateException("A queued Project change could not be recorded as sent")
            continue
        }
        try {
            send(change)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isRefusedByServer(error)) {
                setAside(change, error)
                refused += operationId
            } else {
                failures[projectId] = error
            }
        }
    }
    return QueuedProjectChangesOutcome(refused, failures)
}

/** The queued Project changes a Sync still means to send (not set aside), for the transfer estimate. */
internal fun sendableProjectChanges(state: JsonObject): List<JsonObject> =
    pendingChanges(state).filterNot(::isRefusedProjectChange)

/** Sets the queued change [operationId] aside with the server's reason. True when [state] changed. */
internal fun setProjectChangeAside(state: JsonObject, operationId: String, detail: String, nowEpochMillis: Long): Boolean {
    val change = pendingChanges(state).firstOrNull { it.text("operation_id") == operationId } ?: return false
    if (isRefusedProjectChange(change)) return false
    change.add(REFUSED_KEY, JsonObject().apply {
        addProperty("detail", detail.lineSequence().joinToString(" ") { it.trim() }.trim().take(600))
        addProperty("at_epoch_millis", nowEpochMillis)
    })
    return true
}

/** The changes set aside, oldest first, each described from the stored catalogue. */
internal fun refusedProjectChanges(state: JsonObject): List<RefusedProjectChange> {
    val names = (state.getAsJsonArray("projects") ?: JsonArray()).map { it.asJsonObject }
        .associate { it.text("id") to it.text("name") }
    return pendingChanges(state).filter(::isRefusedProjectChange).map { change ->
        val refusal = change.getAsJsonObject(REFUSED_KEY)
        val projectId = change.text("project_id")
        RefusedProjectChange(
            operationId = change.text("operation_id"),
            projectId = projectId,
            action = change.text("action"),
            description = describeProjectChange(change, names[projectId].orEmpty()),
            detail = refusal.text("detail"),
            refusedAtEpochMillis = refusal.get("at_epoch_millis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            pdfFilename = change.text("filename").takeIf { change.text("action") == "import" && it.endsWith(".pdf", ignoreCase = true) },
        )
    }
}

/**
 * Puts the change [operationId] back in the queue for the next Sync. The server usually refused it
 * because the Project had changed on another device; the retry is made on top of the Project as
 * the catalogue stored by the last Sync has it (its revision), which is what trying again means.
 * True when [state] changed.
 */
internal fun retryRefusedProjectChange(state: JsonObject, operationId: String): Boolean {
    val change = pendingChanges(state).firstOrNull { it.text("operation_id") == operationId && isRefusedProjectChange(it) }
        ?: return false
    change.remove(REFUSED_KEY)
    val current = (state.getAsJsonArray("projects") ?: JsonArray()).map { it.asJsonObject }
        .firstOrNull { it.text("id") == change.text("project_id") }
        ?.get("revision")?.takeIf { it.isJsonPrimitive }?.asInt
    if (current != null && change.has("base_revision")) change.addProperty("base_revision", current)
    return true
}

/** Drops the change [operationId] that was set aside and returns it, or null when it is not there. */
internal fun discardRefusedProjectChange(state: JsonObject, operationId: String): JsonObject? {
    val pending = state.getAsJsonArray("pending") ?: return null
    val index = pending.indexOfFirst { it.asJsonObject.text("operation_id") == operationId && isRefusedProjectChange(it.asJsonObject) }
    if (index < 0) return null
    return pending.remove(index).asJsonObject
}

/** What a queued Project change does, in a few words for the Sync details. */
internal fun describeProjectChange(change: JsonObject, projectName: String): String {
    val project = if (projectName.isBlank()) "a Project" else "Project “$projectName”"
    val name = change.text("name")
    val file = change.text("filename").ifBlank { change.text("source_file").substringAfterLast('/') }
    fun named(text: String) = if (text.isBlank()) "" else " “$text”"
    return when (change.text("action")) {
        "create" -> "Create Project${named(name)}"
        "rename" -> "Rename $project to${named(name).ifBlank { " a new name" }}"
        "remove" -> "Remove $project"
        "import" -> "Add the PDF${named(name.ifBlank { file })} to $project"
        "import_html" -> "Add the HTML page${named(file)} to $project"
        "import_folder" -> "Add a folder to $project"
        "include_source" -> "Add the document${named(file)} to $project"
        "rename_document" -> "Rename a document of $project to${named(name).ifBlank { " a new name" }}"
        "remove_document" -> "Remove a document from $project"
        "order" -> "Reorder the documents of $project"
        else -> "Change $project"
    }
}

/**
 * Sends the queued Library removals in order. One the server refuses ([isRefusedByServer]) is
 * reported to [refused] and the others are still sent; any other failure is reported to [failed]
 * and thrown, which fails the Sync as before.
 */
internal fun deliverLibraryRemovals(
    removals: List<PendingLibraryRemoval>,
    remove: (PendingLibraryRemoval) -> Unit,
    delivered: (PendingLibraryRemoval) -> Unit,
    refused: (PendingLibraryRemoval, Exception) -> Unit,
    failed: (PendingLibraryRemoval, Exception) -> Unit,
) {
    removals.forEach { removal ->
        try {
            remove(removal)
            delivered(removal)
        } catch (error: Exception) {
            if (isRefusedByServer(error)) {
                refused(removal, error)
            } else {
                failed(removal, error)
                throw error
            }
        }
    }
}

/** One change the server refused, as the Sync details list it; [retry] and [discard] say whether it was still there. */
class RefusedLocalChange(
    val projectChange: RefusedProjectChange? = null,
    val removal: PendingLibraryRemoval? = null,
    val retry: () -> Boolean,
    val discard: () -> Boolean,
)

/** Every change the server refused that waits for the user, oldest Project changes first. */
fun refusedLocalChanges(context: android.content.Context): List<RefusedLocalChange> {
    val projects = LibraryProjectStore(context)
    val removals = LibraryRemovalStore(context)
    return projects.refusedChanges().map { change ->
        RefusedLocalChange(
            projectChange = change,
            retry = { projects.retryRefused(change.operationId) },
            discard = { projects.discardRefused(change.operationId) },
        )
    } + removals.refused().map { removal ->
        RefusedLocalChange(
            removal = removal,
            retry = { removals.retryRefused(removal.requestId) },
            discard = { removals.discardRefused(removal.requestId) },
        )
    }
}

/**
 * One refused change as the list of local changes on the Library screen names it, so that the
 * Sync button carries its issue mark until the user has retried or discarded the change: a
 * refused PDF import must not go unnoticed behind a Sync that otherwise succeeded.
 */
data class RefusedChangeNotice(
    /** The PDF the change is about, when it is about one; the list has a row per file. */
    val pdfFilename: String?,
    /** What was refused, in words. */
    val description: String,
    /** The server's reason. */
    val detail: String,
    val refusedAtEpochMillis: Long,
    val removal: Boolean,
)

/** The refused Project changes and Library removals, as notices for the list of local changes. */
internal fun refusedChangeNotices(
    projectChanges: List<RefusedProjectChange>,
    removals: List<PendingLibraryRemoval>,
): List<RefusedChangeNotice> = projectChanges.map { change ->
    RefusedChangeNotice(
        pdfFilename = change.pdfFilename,
        description = change.description,
        detail = change.detail,
        refusedAtEpochMillis = change.refusedAtEpochMillis,
        removal = false,
    )
} + removals.filter(PendingLibraryRemoval::refused).map { removal ->
    RefusedChangeNotice(
        pdfFilename = removal.filename,
        description = "Remove ${removal.filename} from the Library",
        detail = removal.failureDetail,
        refusedAtEpochMillis = removal.createdAtEpochMillis,
        removal = true,
    )
}

/** [refusedChangeNotices] of this phone. */
fun refusedChangeNotices(context: android.content.Context): List<RefusedChangeNotice> =
    refusedChangeNotices(LibraryProjectStore(context).refusedChanges(), LibraryRemovalStore(context).refused())
