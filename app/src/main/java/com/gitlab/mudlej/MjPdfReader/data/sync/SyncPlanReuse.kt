// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import java.io.File

/**
 * What the phone advertises to the server when it asks for a transfer plan: every stored object
 * and PDF it already holds. Computing it reads the whole Library, so one Sync computes it once and
 * keeps it while [stamp] still describes the stores it was read from.
 */
internal data class LocalSyncInventory(
    val stamp: String,
    val knownObjects: Set<String>,
    val pdfDeltaBases: Set<String>,
)

/**
 * The plan a Sync got before it sent and pulled changes, kept in memory for the same Sync's
 * download pass. One Sync posted `/v2/sync/plan` (every local object hash, about 255 KB) again
 * for that pass, although the server answers the same objects for the same Library and inventory.
 */
internal data class PlannedTransfer(
    val operationId: String,
    val targetPdfFilename: String?,
    val inventoryStamp: String,
    val capabilities: List<String>,
    val estimate: SyncTransferEstimate,
)

/**
 * A cheap description of the stores the inventory is read from
 * ([com.gitlab.mudlej.MjPdfReader.data.pathsStamp]). The stores are content-addressed and written by create and rename, so a Sync that adds, replaces or
 * removes an object or a PDF changes the stamp. It need not catch everything: a plan that turns
 * out not to name an object the download pass needs is refused there
 * ([SyncTransferPlan.requireRemoteObject]) and planned again, as before.
 */
internal fun localInventoryStamp(paths: List<File>, extra: List<String> = emptyList()): String =
    com.gitlab.mudlej.MjPdfReader.data.pathsStamp(paths, extra)

/**
 * The plan for the download pass of the Sync [operationId] without asking the server again, or
 * null when it must be asked. The earlier plan still holds when
 * - it belongs to this Sync and, like the pass, covers the whole Library;
 * - the server's Library is the one it was computed for ([libraryRevision]): this Sync sent
 *   nothing that changed it, and nothing else did meanwhile;
 * - the phone holds what it held then ([inventoryStamp]) and asks with the same [capabilities].
 * The objects are then the ones the server would name again. Nothing remains to upload, and the
 * changes and the Library were already downloaded, so only the objects count.
 */
internal fun reusableArtifactEstimate(
    planned: PlannedTransfer?,
    operationId: String?,
    targetPdfFilename: String?,
    libraryRevision: String?,
    inventoryStamp: String,
    capabilities: List<String>,
    artifactPlanId: (serverPlanId: String) -> String,
): SyncTransferEstimate? {
    if (planned == null || operationId == null || planned.operationId != operationId) return null
    if (targetPdfFilename != null || planned.targetPdfFilename != null) return null
    val estimate = planned.estimate
    if (libraryRevision == null || estimate.serverLibraryRevision != libraryRevision) return null
    if (inventoryStamp.isBlank() || planned.inventoryStamp != inventoryStamp) return null
    if (planned.capabilities != capabilities) return null
    return estimate.copy(
        planId = artifactPlanId(estimate.serverPlanId),
        metadataDownloadBytes = 0,
        uploadBytes = 0,
        downloadBytes = estimate.remoteObjects.sumOf(SyncTransferObject::sizeBytes),
    )
}

/**
 * The Library answer for the download pass. The control pass of the same Sync already fetched
 * the Library it then planned for ([remembered], of [rememberedRevision]); when that is the
 * revision the pass expects, the server is asked only whether it still stands (`If-None-Match`,
 * answered 304 without a body) and the remembered answer is used. Otherwise, as before, it is
 * asked with the revision the phone has installed ([installedRevision], null when its Library is
 * not current), and null means the phone is up to date.
 */
internal fun <S : Any> librarySnapshotForDownloadPass(
    remembered: S?,
    rememberedRevision: String?,
    expectedRevision: String?,
    installedRevision: String?,
    fetch: (ifNoneMatch: String?) -> S?,
): S? {
    if (remembered != null && !rememberedRevision.isNullOrBlank() && rememberedRevision == expectedRevision) {
        return fetch(rememberedRevision) ?: remembered
    }
    return fetch(installedRevision)
}
