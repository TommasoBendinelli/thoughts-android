// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.sync

import com.gitlab.mudlej.MjPdfReader.data.sync.*
import com.gitlab.mudlej.MjPdfReader.data.render.isCanonicalPublishedPdfFilename
import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class SyncStatusSummary(val title: String, val message: String)

internal fun readableBuildFailure(reason: String): String = when {
    reason.contains("unresolved_tex_link", ignoreCase = true) ->
        "A document reference could not be resolved. Check the destination in Manage documents."
    reason.contains("strict review correspondence gate failed", ignoreCase = true) ->
        "A proposed edit could not be matched to its location in the updated PDF."
    reason.contains("cancel", ignoreCase = true) -> "The build was cancelled."
    reason.contains("latex", ignoreCase = true) || reason.contains("Missing $ inserted", ignoreCase = true) ->
        "A formatting error prevented the PDF from building."
    reason.contains("timeout", ignoreCase = true) || reason.contains("timed out", ignoreCase = true) ->
        "The build took too long to finish."
    else -> "The PDF could not be built. Copy diagnostics to inspect the build error."
}

private fun JsonObject.summaryText(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

private data class BuildOwner(val id: String, val name: String, val documents: List<String>)

private fun buildOwner(filename: String, projects: JsonArray): BuildOwner? {
    val owners = projects.filter { it.isJsonObject }.map { it.asJsonObject }.mapNotNull { project ->
        val documents = project.get("documents")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
        val matching = documents.filter { it.summaryText("filename") == filename }
        if (matching.isEmpty() && project.summaryText("target") != filename) return@mapNotNull null
        val id = project.summaryText("id")
        val name = project.summaryText("name")
        if (id.isBlank() || name.isBlank()) return@mapNotNull null
        BuildOwner(id, name, matching.map { it.summaryText("name") }.filter { it.isNotBlank() }.distinct())
    }.distinctBy { it.id }
    return owners.singleOrNull()
}

internal fun syncStatusSummary(record: SyncOperationRecord, saved: SavedSyncDetails,
                               projects: JsonArray = JsonArray()): SyncStatusSummary {
    val failures = saved.builds.filter { it.workKind != "review_cards" }.flatMap { build ->
        build.failedDocuments.orEmpty().ifEmpty {
            if (build.serverStatus in setOf("failed", "attention", "cancelled", "compiler_failure")) {
                // Older saved runs may have lost failed_documents while retaining the build scope.
                // A multi-document build alone does not identify which member failed.
                val filename = build.target.takeIf(::isCanonicalPublishedPdfFilename)
                    ?: build.documents.orEmpty().distinct().singleOrNull()?.takeIf(::isCanonicalPublishedPdfFilename)
                filename?.let { listOf(SyncProjectFailure(it, build.detail)) }.orEmpty()
            } else emptyList()
        }
    }.distinctBy { it.filename }.sortedBy { it.filename }
    val owners = failures.associate { it.filename to buildOwner(it.filename, projects) }
    val knownProjects = owners.values.filterNotNull().distinctBy { it.id }
    val allProjectsKnown = failures.isNotEmpty() && owners.values.all { it != null }
    val synced = record.status == SyncOperationStore.STATUS_SYNCED ||
        isPdfBuildFailedSyncedDisplayMessage(record.displayMessage)
    val pdfFailure = failures.isNotEmpty() || isTerminalPdfBuildFailure(record)
    val stillBuilding = pdfsStillBuildingAfter(record)
    val title = when {
        record.kind == SyncOperationKind.BUILD && record.status == SyncOperationStore.STATUS_SYNCED && stillBuilding > 0 ->
            "Building on the server"
        record.kind == SyncOperationKind.BUILD && record.status == SyncOperationStore.STATUS_SYNCED -> "Build complete"
        record.kind == SyncOperationKind.BUILD && pdfFailure -> SyncStatusLabels.PDF_BUILD_FAILED
        synced && pdfFailure -> SyncStatusLabels.PDF_BUILD_FAILED
        synced -> SyncStatusLabels.UP_TO_DATE
        isPendingSyncOperationStatus(record.status) -> when (record.phase) {
            SyncRunPhase.BUILDING -> "Building PDFs"
            SyncRunPhase.WAITING_NETWORK -> "Waiting for a connection"
            SyncRunPhase.WAITING_USER -> "Sync needs your attention"
            else -> SyncStatusLabels.SYNCING
        }
        record.phase == SyncRunPhase.CANCELLED -> "Sync cancelled"
        record.phase == SyncRunPhase.INTERRUPTED -> "Sync interrupted"
        else -> SyncStatusLabels.SYNC_FAILED
    }
    val paragraphs = mutableListOf<String>()
    if (record.kind == SyncOperationKind.BUILD) paragraphs += "This is a PDF build operation. Sync status is tracked separately."

    if (failures.isNotEmpty()) {
        if (!synced) paragraphs += "Some changes still need to sync. They remain saved on this device."
        failures.forEach {
            val owner = owners[it.filename]
            val heading = owner?.name ?: if (it.filename.startsWith("import-")) "Imported documents" else it.filename.removeSuffix(".pdf")
            val documents = owner?.documents.orEmpty().takeIf { names -> names.isNotEmpty() }
                ?.joinToString(", ", prefix = "Documents: ", postfix = "\n").orEmpty()
            paragraphs += "$heading\n$documents${readableBuildFailure(it.reason)}"
        }
    } else when {
        synced && pdfFailure -> "A PDF could not be built. The affected project was not recorded."
        synced || isPendingSyncOperationStatus(record.status) -> null
        else -> "Your changes remain saved on this device. Run Sync to try again."
    }?.let { paragraphs += it }
    // Sync does not wait for Fedora's builds; the PDFs arrive by themselves when they are built.
    if (stillBuilding > 0) paragraphs += pdfsStillBuildingMessage(stillBuilding) +
        ". Thoughts fetches ${if (stillBuilding == 1) "it" else "them"} when the server has built " +
        "${if (stillBuilding == 1) "it" else "them"}; until then the previous ${if (stillBuilding == 1) "PDF stays" else "PDFs stay"}."
    // The title only says a PDF failed; say how much failed and that the changes themselves arrived.
    if (synced && pdfFailure && record.kind != SyncOperationKind.BUILD) paragraphs += SyncStatusLabels.CHANGES_SYNCED_DETAIL + when {
        allProjectsKnown -> " · ${knownProjects.size} ${if (knownProjects.size == 1) "project" else "projects"} failed to build."
        failures.isNotEmpty() -> " · ${failures.size} ${if (failures.size == 1) "PDF" else "PDFs"} failed to build."
        else -> "."
    }
    return SyncStatusSummary(title, paragraphs.joinToString("\n\n"))
}
