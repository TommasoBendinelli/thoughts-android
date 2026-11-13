// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.Os
import android.util.Log
import androidx.room.withTransaction
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSidecarStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.canonicalCommentForAnnotation
import com.gitlab.mudlej.MjPdfReader.data.annotation.orderCommentCreations
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryOutbox
import com.gitlab.mudlej.MjPdfReader.data.render.PendingCommentDelivery
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderFailure
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusHistory
import com.gitlab.mudlej.MjPdfReader.data.render.PdfWarmCacheWorker
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.isCanonicalPublishedPdfFilename
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewOutgoingCardFileLock
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.gitlab.mudlej.MjPdfReader.data.source.PendingSourceMutation
import com.gitlab.mudlej.MjPdfReader.data.source.SourceConflictBundle
import com.gitlab.mudlej.MjPdfReader.data.source.SourceReceipt
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.restoreNoteNulls
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.github.luben.zstd.Zstd
import org.tbend.thoughts.sdk.ChangeRecord
import org.tbend.thoughts.sdk.AgentProposalResolutionRequest
import org.tbend.thoughts.sdk.AgentProposalUndoRequest
import org.tbend.thoughts.sdk.BuildRecord
import org.tbend.thoughts.sdk.ConflictDiscardRequest
import org.tbend.thoughts.sdk.ConflictResolveRequest
import org.tbend.thoughts.sdk.DocumentSyncSnapshot
import org.tbend.thoughts.sdk.LibraryEntry
import org.tbend.thoughts.sdk.LibrarySnapshot
import org.tbend.thoughts.sdk.OperationInput
import org.tbend.thoughts.sdk.ProjectionRecord
import org.tbend.thoughts.sdk.ProposalObjectIdentity
import org.tbend.thoughts.sdk.SourceApplyRequest
import com.gitlab.mudlej.MjPdfReader.data.source.ProposalDecisionClaim
import com.gitlab.mudlej.MjPdfReader.data.source.ProposalDecisionClaims
import com.gitlab.mudlej.MjPdfReader.data.source.RemovedProposalDismissal
import org.tbend.thoughts.sdk.SourceSelectionRequest
import org.tbend.thoughts.sdk.SourceSelectionResult
import org.tbend.thoughts.sdk.SourceSnapshot as ServerSourceSnapshot
import org.tbend.thoughts.sdk.RangeResumeUnsupportedException
import org.tbend.thoughts.sdk.ServerException
import org.tbend.thoughts.sdk.ServerStorageUsage
import org.tbend.thoughts.sdk.SyncClient
import org.tbend.thoughts.sdk.ObjectTransferProgressListener
import org.tbend.thoughts.sdk.PDF_DELTA_CAPABILITY
import org.tbend.thoughts.sdk.SOURCE_RESOURCE_CAPABILITY
import org.tbend.thoughts.sdk.SyncPlanRequest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val SERVER_OBJECT_PREFIX = "server-object:"
private const val PDF_DELTA_FORMAT = "zstd-patch-v1"

internal suspend fun handoffArtifactTransfer(
    prepare: suspend () -> Unit,
    publish: () -> Unit,
    defer: () -> Unit,
) {
    try {
        prepare()
    } catch (error: ArtifactPlanSupersededException) {
        defer()
        return
    }
    publish()
}

internal fun sourceArtifactActivationIsCurrent(
    remoteSha256: String,
    activatedSha256: String?,
    cachedSha256: String?,
): Boolean = remoteSha256.isEmpty() || (
    remoteSha256 == activatedSha256 && remoteSha256 == cachedSha256
    )

internal data class LibraryPublicationDelta(
    val changedPublications: List<LibraryEntry>,
    val removedFilenames: Set<String>,
) {
    val changedPdfCount: Int
        get() = changedPublications.size + removedFilenames.size
}

internal fun libraryPublicationDelta(
    localPublications: Map<String, Pair<String, Long>>,
    remotePublications: List<LibraryEntry>,
    targetPdfFilename: String? = null,
): LibraryPublicationDelta {
    val scopedRemote = remotePublications.filter { publication ->
        targetPdfFilename == null || publication.filename == targetPdfFilename
    }
    val remoteFilenames = remotePublications.mapTo(hashSetOf(), LibraryEntry::filename)
    return LibraryPublicationDelta(
        changedPublications = scopedRemote.filter { publication ->
            localPublications[publication.filename] !=
                (publication.sha256 to publication.sizeBytes)
        },
        removedFilenames = if (targetPdfFilename == null) {
            localPublications.keys - remoteFilenames
        } else {
            emptySet()
        },
    )
}

internal fun shouldDeliverPendingLibraryRemovals(
    explicit: Boolean,
    targetPdfFilename: String?,
    artifactOnly: Boolean,
): Boolean = explicit && targetPdfFilename == null && !artifactOnly

internal fun updateLibraryIndexEntryMetadata(
    index: JsonObject,
    filename: String,
    expectedSha256: String,
    update: (JsonObject) -> Unit,
) {
    check(index.get("schema_version")?.asInt == 1) {
        "The installed Library index is invalid"
    }
    val entries = index.getAsJsonArray("pdfs") ?: error("The installed Library index has no PDF list")
    val matches = entries
        .map { it.asJsonObject }
        .filter { entry ->
            entry.get("filename")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString == filename
        }
    val entry = matches.singleOrNull() ?: error("The installed Library PDF is missing or duplicated")
    val indexedSha256 = entry.get("pdf_sha256")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        .orEmpty()
    check(indexedSha256 == expectedSha256) {
        "The installed Library PDF changed while its metadata was updated"
    }
    update(entry)
}

/** Restores the last active generation after an interrupted local directory swap; it never starts network work. */
internal fun reconcileInterruptedLibraryActivationLocally() {
    reconcileInterruptedLibraryActivation(PhoneRenderQueue.defaultCanonicalPdfDirectory())
}

internal fun reconcileInterruptedLibraryActivation(root: File) {
    val parent = root.parentFile ?: return
    val journal = File(parent, ".thoughts-library-activation.json")
    if (!journal.isFile) return
    val previous = File(parent, ".thoughts-library-previous")
    if (!root.exists() && previous.isDirectory) {
        if (reusedLibraryFiles(journal).isNotEmpty()) {
            val value = JsonParser.parseString(journal.readText()).asJsonObject
            val staging = File(value.get("staging_path").asString)
            require(staging.parentFile?.canonicalFile == parent.canonicalFile && staging.name.startsWith(".thoughts-library-stage-"))
            restoreReusedLibraryFiles(root, staging, previous, journal)
        }
        check(previous.renameTo(root)) { "Could not restore the previous Library generation" }
    }
    if (root.isDirectory) journal.delete()
}

internal fun serverLogSha256(logReference: String): String? {
    if (!logReference.startsWith(SERVER_OBJECT_PREFIX)) return null
    return logReference.removePrefix(SERVER_OBJECT_PREFIX)
        .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
}

data class PdfBuildErrorReport(
    val buildId: String,
    val target: String,
    val status: String,
    val message: String,
    val failedDocuments: List<PhoneRenderFailure>,
    val logReference: String,
)

internal fun pdfBuildErrorReport(build: BuildRecord): PdfBuildErrorReport {
    val failedDocuments = build.result.get("failed_documents")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { value ->
            val entry = value.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@mapNotNull null
            val filename = entry.safeString("pdf_filename")
                ?.takeIf { it.length <= 512 && isCanonicalPublishedPdfFilename(it) }
                ?: return@mapNotNull null
            val message = entry.safeString("message")
                ?.takeIf(String::isNotBlank)
                ?: entry.safeString("error")?.takeIf(String::isNotBlank)
                ?: build.error?.takeIf(String::isNotBlank)
                ?: build.progress.safeString("detail")?.takeIf(String::isNotBlank)
                ?: "The PDF could not be built."
            PhoneRenderFailure(
                pdfFilename = filename,
                source = entry.safeString("source").orEmpty(),
                message = message,
            )
        }
        .orEmpty()
    val logReference = build.result.safeString("log_sha256")
        ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
        ?.let { "server-object:$it" }
        .orEmpty()
    return PdfBuildErrorReport(
        buildId = build.id,
        target = build.target,
        status = build.status,
        message = build.error.orEmpty().ifBlank {
            build.progress.safeString("detail").orEmpty()
        },
        failedDocuments = failedDocuments,
        logReference = logReference,
    )
}

internal fun terminalPdfBuildFailure(build: BuildRecord): TerminalPdfBuildFailure {
    val report = pdfBuildErrorReport(build)
    val status = requireNotNull(TerminalPdfBuildStatus.fromWireStatus(build.status)) {
        "Build ${build.id} does not have a terminal failure status: ${build.status}"
    }
    val detail = report.message.ifBlank {
        report.failedDocuments.joinToString("; ") { failure ->
            "${failure.pdfFilename}: ${failure.message}"
        }
    }.ifBlank { "The PDF update on the server ended with ${build.status}." }
    return TerminalPdfBuildFailure(
        buildId = build.id,
        status = status,
        detail = detail,
        logReference = report.logReference,
    )
}

private fun JsonObject.safeString(name: String): String? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

private fun JsonObject.safeInt(name: String): Int? =
    runCatching { get(name)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()

private fun JsonObject.safeFloat(name: String): Float? =
    runCatching { get(name)?.takeIf { it.isJsonPrimitive }?.asFloat }.getOrNull()

internal fun mergeReviewCommentDocuments(
    previous: JsonObject?,
    review: JsonObject,
    publicationFilename: String,
): JsonObject {
    require(publicationFilename.isSafeReviewCommentPdfFilename())
    if (previous != null) {
        val previousPayload = previous.deepCopy().apply { remove("pdf_filenames") }
        val reviewPayload = review.deepCopy().apply { remove("pdf_filenames") }
        check(previousPayload == reviewPayload) {
            "Document artifacts disagree about review comment ${review.safeString("id").orEmpty()}"
        }
    }
    val pdfFilenames = buildSet {
        previous?.getAsJsonArray("pdf_filenames")
            ?.mapNotNull { value ->
                value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            ?.filterTo(this) { it.isSafeReviewCommentPdfFilename() }
        add(publicationFilename)
    }
    return review.deepCopy().apply {
        add("pdf_filenames", JsonArray().apply { pdfFilenames.sorted().forEach(::add) })
    }
}

internal fun mergeSourceResourceDocuments(
    previous: JsonObject?,
    resource: JsonObject,
    publicationFilename: String,
    publicationStale: Boolean = false,
): JsonObject {
    require(publicationFilename.isSafeReviewCommentPdfFilename())
    val kind = resource.safeString("kind").orEmpty()
    require(kind in setOf("project_style", "project_style_override", "html_attachment"))
    val path = resource.safeString("path").orEmpty()
    require(path.isNotBlank())
    val previousStale = previous?.get(SOURCE_RESOURCE_STALE_MERGE_KEY)
        ?.takeIf { it.isJsonPrimitive }
        ?.asBoolean == true
    val previousPayload = previous?.deepCopy()?.apply {
        remove("pdf_filenames")
        remove(SOURCE_RESOURCE_STALE_MERGE_KEY)
    }
    val resourcePayload = resource.deepCopy().apply {
        remove("pdf_filenames")
        remove(SOURCE_RESOURCE_STALE_MERGE_KEY)
    }
    val payloadsAgree = previousPayload == null || previousPayload == resourcePayload
    if (!payloadsAgree) {
        check(
            kind in setOf("project_style", "html_attachment") && previousPayload.safeString("kind") == kind &&
                previousPayload.safeString("path") == path &&
                (previousStale || publicationStale)
        ) { "Document artifacts disagree about source resource $path" }
    }
    val pdfFilenames = buildSet {
        previous?.getAsJsonArray("pdf_filenames")
            ?.mapNotNull { value ->
                value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
            ?.filterTo(this) { it.isSafeReviewCommentPdfFilename() }
        add(publicationFilename)
    }
    val chooseIncoming = when {
        previous == null || payloadsAgree -> true
        previousStale && !publicationStale -> true
        !previousStale && publicationStale -> false
        else -> resourcePayload.stableSourceResourceKey() < previousPayload.stableSourceResourceKey()
    }
    val selected = if (chooseIncoming) resourcePayload else requireNotNull(previousPayload)
    return selected.deepCopy().apply {
        add("pdf_filenames", JsonArray().apply { pdfFilenames.sorted().forEach(::add) })
        addProperty(
            SOURCE_RESOURCE_STALE_MERGE_KEY,
            when {
                previous == null -> publicationStale
                payloadsAgree -> previousStale && publicationStale
                chooseIncoming -> publicationStale
                else -> previousStale
            },
        )
    }
}

private const val SOURCE_RESOURCE_STALE_MERGE_KEY = "__publication_stale"

private fun JsonObject.stableSourceResourceKey(): String = listOf(
    safeString("sha256").orEmpty(),
    safeString("object").orEmpty(),
    toString(),
).joinToString("\n")

internal fun mergeLogicalCommentArtifacts(previous: JsonObject?, incoming: JsonObject): JsonObject {
    val id = incoming.safeString("comment_id").orEmpty()
    require(id.isNotBlank() && incoming.safeInt("schema_version") == 1)
    if (previous != null) {
        val previousPayload = previous.logicalCanonicalPayload()
        val incomingPayload = incoming.logicalCanonicalPayload()
        check(previousPayload == incomingPayload) {
            "Document artifacts disagree about logical comment $id"
        }
    }
    return incoming.copyWithoutLocationContents().apply {
        add("locations", mergeArtifactLocationSets(previous, incoming, "logical comment placement $id"))
    }
}

internal fun mergeReviewCorrespondenceArtifacts(previous: JsonObject?, incoming: JsonObject): JsonObject {
    val reviewKey = incoming.safeString("review_key").orEmpty()
    require(reviewKey.isNotBlank() && incoming.safeInt("schema_version") == 2)
    var conflictingState = false
    if (previous != null) {
        val previousPayload = previous.correspondenceCanonicalPayload()
        val incomingPayload = incoming.correspondenceCanonicalPayload()
        check(previousPayload == incomingPayload) {
            "Document artifacts disagree about correspondence $reviewKey"
        }
        conflictingState = previous.safeString("state") != incoming.safeString("state")
    }
    val locations = mergeArtifactLocationSets(previous, incoming, "placement $reviewKey")
    return incoming.copyWithoutLocationContents().apply {
        add("locations", locations)
        if (conflictingState) {
            addProperty("state", "needs_attention")
            addProperty("reason", "Document artifacts disagree about the correspondence state.")
        }
        val sourceBundleHashes = locations.map { it.asJsonObject }
            .mapNotNull { it.getAsJsonObject("proof")?.safeString("source_bundle_sha256") }
            .filter(String::isNotBlank)
            .distinct()
        addProperty("source_bundle_sha256", sourceBundleHashes.singleOrNull().orEmpty())
    }
}

private fun mergeArtifactLocationSets(previous: JsonObject?, incoming: JsonObject, description: String): JsonArray {
    val locationsByPdf = linkedMapOf<String, Set<JsonObject>>()
    sequenceOf(previous, incoming).filterNotNull().forEach { correspondence ->
        val artifactLocations = linkedMapOf<String, MutableSet<JsonObject>>()
        correspondence.getAsJsonArray("locations")?.forEach { element ->
            val location = element.asJsonObject
            val key = listOf(
                location.safeString("pdf_filename").orEmpty(),
                location.safeString("pdf_sha256").orEmpty(),
            ).joinToString("\u0000")
            artifactLocations.getOrPut(key) { linkedSetOf() }.add(location)
        }
        artifactLocations.forEach { (key, fragments) ->
            // Compare complete fragment sets across artifacts, not individual words within one artifact.
            val existing = locationsByPdf.putIfAbsent(key, fragments)
            check(existing == null || existing == fragments) {
                "Document artifacts disagree about $description"
            }
        }
    }
    return JsonArray().apply {
        locationsByPdf.toSortedMap().values.forEach { fragments ->
            fragments.forEach { add(it.deepCopy()) }
        }
    }
}

// Comparison views never escape or mutate their inputs. Copy only retained fields for output.
private val CORRESPONDENCE_NON_CANONICAL_FIELDS = setOf("locations", "state", "reason", "source_bundle_sha256")
private val LOGICAL_NON_CANONICAL_FIELDS = setOf("locations", "source_file", "source_line", "source_sha256")

private fun JsonObject.correspondenceCanonicalPayload(): JsonObject = JsonObject().also { payload ->
    entrySet().forEach { (key, value) ->
        if (key !in CORRESPONDENCE_NON_CANONICAL_FIELDS) payload.add(key, value)
    }
}

private fun JsonObject.logicalCanonicalPayload(): JsonObject = JsonObject().also { payload ->
    entrySet().forEach { (key, value) ->
        if (key !in LOGICAL_NON_CANONICAL_FIELDS) payload.add(key, value)
    }
}

private fun JsonObject.copyWithoutLocationContents(): JsonObject = JsonObject().also { copy ->
    entrySet().forEach { (key, value) ->
        // Keep field order, and therefore serialized manifest identity, unchanged.
        copy.add(key, if (key == "locations") JsonArray() else value.deepCopy())
    }
}

private fun String.isSafeReviewCommentPdfFilename(): Boolean =
    isNotBlank() && length <= 512 && !contains('/') && !contains('\\') &&
        endsWith(".pdf", ignoreCase = true)

private fun LibrarySnapshot.effectiveArtifactRevision(): String =
    artifactRevision?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) } ?: libraryRevision

internal fun decodeZstdPublicationDelta(
    base: ByteArray,
    patch: ByteArray,
    expectedSize: Int,
): ByteArray? {
    if (expectedSize < 0) return null
    return runCatching {
        val result = ByteArray(expectedSize)
        val decoded = Zstd.decompressUsingDict(
            result,
            0,
            patch,
            0,
            patch.size,
            base,
        )
        result.takeIf { !Zstd.isError(decoded) && decoded == expectedSize.toLong() }
    }.getOrNull()
}

/** Missing on legacy requests, which retain the historical save-and-build behavior. */
private fun JsonObject.buildAfterSync(): Boolean = if (has("build_after_sync")) {
    runCatching { get("build_after_sync").asBoolean }.getOrDefault(false)
} else {
    true
}

private fun String.isSourceResolutionId(): Boolean =
    length in 1..256 && all { character ->
        character.isLetterOrDigit() || character in setOf('-', '_', '.', ':')
    }

private class PermanentSourceRequestException(message: String) : Exception(message)

private data class SourceBuildIntent(
    val pdfFilename: String,
    val sourceFrontier: Long?,
)

private data class DeltaPassResult(
    val anchorJobs: Set<String>,
    val uploadedOperations: Boolean,
    val sourceAttention: List<PendingSourceMutation> = emptyList(),
)

private data class ScopedEntity(
    val entityType: String,
    val collection: String,
    val entityId: String,
    val payload: JsonObject,
)

private data class ScopedEntityKey(
    val entityType: String,
    val collection: String,
    val entityId: String,
)

private fun requireSourceRequest(condition: Boolean, message: String) {
    if (!condition) throw PermanentSourceRequestException(message)
}

internal data class VerifiedSourceOpen(
    val resolution: SourceSelectionResult,
    val contents: String,
)

internal fun resolveVerifiedSourceOpen(
    request: SourceSelectionRequest,
    resolve: (SourceSelectionRequest) -> SourceSelectionResult,
    downloadObject: (String) -> ByteArray,
): VerifiedSourceOpen {
    val resolution = resolve(request)
    val bytes = downloadObject(resolution.objectSha256)
    check(bytes.size <= SourceEditQueue.MAX_SOURCE_BYTES) {
        "Resolved source exceeds the supported size"
    }
    check(
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) } == resolution.sha256,
    ) { "Resolved source content failed SHA-256 verification" }
    return VerifiedSourceOpen(
        resolution = resolution,
        contents = bytes.toString(StandardCharsets.UTF_8),
    )
}

internal suspend fun <T : Any> resolveSourceOpenOnce(
    existingReceipt: () -> T?,
    resolve: suspend () -> Unit,
): T {
    existingReceipt()?.let { return it }
    resolve()
    return checkNotNull(existingReceipt()) {
        "The server did not produce a source-open receipt"
    }
}

internal sealed class SourceBaseDecision {
    data class Ready(val snapshot: ThoughtsSourceSnapshotEntity) : SourceBaseDecision()
    data class Stale(val snapshot: ServerSourceSnapshot) : SourceBaseDecision()
    object Missing : SourceBaseDecision()
}

internal fun sourceBaseDecision(
    cached: ThoughtsSourceSnapshotEntity?,
    current: ServerSourceSnapshot?,
    baseSha256: String,
    resolutionId: String,
): SourceBaseDecision {
    if (cached?.sha256 == baseSha256) return SourceBaseDecision.Ready(cached)
    if (current == null || current.deleted) return SourceBaseDecision.Missing
    if (current.sha256 != baseSha256) return SourceBaseDecision.Stale(current)
    return SourceBaseDecision.Ready(
        ThoughtsSourceSnapshotEntity(
            path = current.path,
            revision = current.revision,
            sha256 = current.sha256,
            objectSha256 = current.objectSha256,
            resolutionId = resolutionId,
        ),
    )
}

internal fun staleSourceMergeBase(
    current: ServerSourceSnapshot,
    baseSha256: String,
    baseContents: String?,
    resolutionId: String,
): ThoughtsSourceSnapshotEntity? {
    val baseBytes = baseContents?.toByteArray(StandardCharsets.UTF_8) ?: return null
    if (baseBytes.size > SourceEditQueue.MAX_SOURCE_BYTES ||
        MessageDigest.getInstance("SHA-256").digest(baseBytes)
            .joinToString("") { "%02x".format(it) } != baseSha256
    ) {
        return null
    }
    return ThoughtsSourceSnapshotEntity(
        path = current.path,
        revision = current.revision,
        sha256 = baseSha256,
        objectSha256 = baseSha256,
        resolutionId = resolutionId,
    )
}

private fun String.canonicalSyncEntityType(): String = if (this == "comment") "annotation" else this

internal fun outboxMatchesServerChange(
    operation: ThoughtsOutboxEntity,
    change: ChangeRecord,
): Boolean =
    operation.operationId == change.operationId &&
        operation.entityType.canonicalSyncEntityType() == change.entityType.canonicalSyncEntityType() &&
        operation.entityId == change.entityId &&
        operation.collection == change.collection &&
        operation.baseRevision == change.baseRevision &&
        operation.clientTimestamp == change.clientTimestamp &&
        runCatching {
            JsonParser.parseString(operation.payloadJson) == change.payload
        }.getOrDefault(false)

internal data class ReviewCardCaptureMetadata(
    val operationId: String,
    val revision: Int,
    val payloadSha256: String,
    val baseRevision: Long?,
)

internal fun reviewCardCaptureMetadata(payload: JsonObject): ReviewCardCaptureMetadata? {
    val cardId = payload.safeString("card_id")?.takeIf(String::isNotBlank) ?: return null
    val revision = payload.safeInt("revision")?.takeIf { it > 0 } ?: return null
    val serialized = payload.toString().toByteArray(StandardCharsets.UTF_8)
    val payloadSha256 = MessageDigest.getInstance("SHA-256").digest(serialized)
        .joinToString("") { "%02x".format(it) }
    val createdAt = payload.safeString("created_at").orEmpty()
    val updatedAt = payload.safeString("updated_at").orEmpty()
    val pristineCreate = revision == 1 && createdAt.isNotBlank() && createdAt == updatedAt
    val operationId = UUID.nameUUIDFromBytes(
        listOf("review_card", cardId, revision.toString(), payloadSha256)
            .joinToString("\u0000")
            .toByteArray(StandardCharsets.UTF_8),
    ).toString()
    return ReviewCardCaptureMetadata(
        operationId = operationId,
        revision = revision,
        payloadSha256 = payloadSha256,
        baseRevision = if (pristineCreate) null else (revision - 1).coerceAtLeast(0).toLong(),
    )
}

internal fun deleteAcknowledgedReviewCardPayload(
    root: File,
    cardId: String,
    acknowledgedPayloadJson: String,
): Boolean = ReviewOutgoingCardFileLock.withLock {
    val acknowledged = runCatching { JsonParser.parseString(acknowledgedPayloadJson).asJsonObject }
        .getOrNull() ?: return@withLock false
    var deleted = false
    root.walkTopDown()
        .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        .filter { it.nameWithoutExtension == cardId }
        .forEach { file ->
            val current = runCatching { JsonParser.parseString(file.readText()).asJsonObject }
                .getOrNull()
            if (current == acknowledged && file.delete()) deleted = true
        }
    deleted
}

/** The person-facing reason a card made in an HTML attachment page was not sent. */
internal const val UNKNOWN_ATTACHMENT_CARD_DETAIL =
    "Not sent: the server does not know the version of the HTML page this card was made in. " +
        "The card stays on this phone. Sync, open the page from its PDF again and make the card there."

/** The server's 409 `html_attachment_unknown` for a card made in an HTML attachment page. */
internal fun isUnknownAttachmentCardRefusal(row: ThoughtsOutboxEntity, error: ServerException): Boolean =
    row.entityType == "review_card" && error.statusCode == 409 &&
        error.errorCode == org.tbend.thoughts.sdk.HTML_ATTACHMENT_UNKNOWN

internal fun cleanupAcknowledgedReviewPayload(
    operation: ThoughtsOutboxEntity,
    outgoingEventsRoot: File = ReviewStore.defaultOutgoingEventsRoot(),
    outgoingCardsRoot: File = ReviewStore.defaultOutgoingCardsRoot(),
): Boolean = when (operation.entityType) {
    "review_event" -> {
        var deleted = false
        outgoingEventsRoot.walkTopDown()
            .filter { it.isFile && it.nameWithoutExtension == operation.entityId }
            .forEach { file -> if (file.delete()) deleted = true }
        deleted
    }
    "review_card" -> deleteAcknowledgedReviewCardPayload(
        outgoingCardsRoot,
        operation.entityId,
        operation.payloadJson,
    )
    else -> false
}

internal fun locallyEquivalentOperationPayload(
    entityType: String,
    existingPayloadJson: String,
    candidate: JsonObject,
): Boolean {
    val existing = runCatching { JsonParser.parseString(existingPayloadJson).asJsonObject }
        .getOrNull() ?: return false
    if (existing == candidate) return true
    if (entityType.canonicalSyncEntityType() != "annotation") return false
    val normalizedExisting = existing.deepCopy().apply { remove("pdf_sha256") }
    val normalizedCandidate = candidate.deepCopy().apply { remove("pdf_sha256") }
    return normalizedExisting == normalizedCandidate
}


data class DeltaSyncResult(
    val buildFreeAnchoringSupported: Boolean,
    val anchorJobsSettled: Boolean,
)

internal data class ControlSyncResult(
    val buildsActive: Boolean,
    val artifactPlan: ArtifactSyncPlan?,
    val buildProgress: List<SyncBuildProgress> = emptyList(),
    val terminalBuildFailures: List<TerminalPdfBuildFailure> = emptyList(),
    val sourceAttention: List<PendingSourceMutation> = emptyList(),
    val scopeTargetPdfFilename: String? = null,
)

/**
 * A standalone PDF can sync alone. A project reading member cannot: activating one of its
 * documents by itself would leave the reading behind, so its project needs the whole-Library
 * Sync that activates one coherent collection. Unknown membership ([projectMemberPdfFilenames]
 * null) is treated as membership.
 */
internal fun effectiveSourceSyncTarget(
    requestedTargetPdfFilename: String?,
    hasWorkspaceStyleMutation: Boolean,
    projectMemberPdfFilenames: Set<String>?,
    preserveGlobalOperationScope: Boolean = false,
): String? = requestedTargetPdfFilename?.takeUnless {
    hasWorkspaceStyleMutation || preserveGlobalOperationScope ||
        projectMemberPdfFilenames == null || it in projectMemberPdfFilenames
}

internal fun sourceAttentionDetail(mutations: List<PendingSourceMutation>): String = mutations
    .joinToString("; ") { mutation ->
        listOf(mutation.sourceFile, mutation.error)
            .filter(String::isNotBlank)
            .joinToString(": ")
    }
    .ifBlank { "A TeX change needs attention." }

internal fun sourceMutationsAllowRender(mutations: List<PendingSourceMutation>): Boolean =
    mutations.isEmpty()

/**
 * Makes every source mutation authorized for this pass durable and visible to device-sequence
 * reconciliation. In particular, an explicit retry must archive its terminal receipt and restore
 * a missing request before Fedora's sequence frontier is reconciled; otherwise the restored
 * request can keep replaying an already-consumed sequence forever.
 */
internal fun prepareSourceMutationsForDeviceSequenceReconciliation(
    queue: SourceEditQueue,
    targetPdfFilename: String? = null,
    retryFailures: Boolean,
): Set<String> {
    queue.captureExternalEdits()
    if (retryFailures) queue.prepareFailedMutationsForExplicitSync(targetPdfFilename)
    return queue.undeliveredRequestIds()
}

internal fun deltaSyncHealthAfterSourceCheck(
    current: ThoughtsSyncStateEntity,
    completedAtEpochMillis: Long,
    blockedOperationCount: Int,
    sourceAttention: List<PendingSourceMutation>,
): ThoughtsSyncStateEntity = if (sourceAttention.isEmpty()) {
    current.copy(
        lastSyncSuccessEpochMillis = completedAtEpochMillis,
        lastSyncErrorCode = "",
        lastSyncErrorMessage = "",
        blockedOperationCount = blockedOperationCount,
    )
} else {
    current.copy(
        lastSyncErrorCode = "source_attention",
        lastSyncErrorMessage = sourceAttentionDetail(sourceAttention),
        blockedOperationCount = blockedOperationCount,
    )
}

internal fun controlSyncHealthAfterResult(
    current: ThoughtsSyncStateEntity,
    completedAtEpochMillis: Long,
    blockedOperationCount: Int,
    result: ControlSyncResult,
): ThoughtsSyncStateEntity = when {
    blockedOperationCount > 0 -> current.copy(
        lastSyncErrorCode = "sync_failed",
        lastSyncErrorMessage = "Some local changes could not be synchronized.",
        blockedOperationCount = blockedOperationCount,
    )
    result.sourceAttention.isNotEmpty() -> current.copy(
        lastSyncErrorCode = "source_attention",
        lastSyncErrorMessage = sourceAttentionDetail(result.sourceAttention),
        blockedOperationCount = blockedOperationCount,
    )
    result.terminalBuildFailures.isNotEmpty() -> {
        val terminal = TerminalPdfBuildException(result.terminalBuildFailures)
        current.copy(
            lastSyncErrorCode = when (terminal.primaryStatus) {
                TerminalPdfBuildStatus.ATTENTION -> "pdf_build_attention"
                TerminalPdfBuildStatus.CANCELLED -> "pdf_build_cancelled"
                TerminalPdfBuildStatus.FAILED -> "pdf_build_failed"
            },
            lastSyncErrorMessage = terminal.message.orEmpty(),
            blockedOperationCount = blockedOperationCount,
        )
    }
    else -> current.copy(
        lastSyncSuccessEpochMillis = completedAtEpochMillis,
        lastSyncErrorCode = "",
        lastSyncErrorMessage = "",
        blockedOperationCount = blockedOperationCount,
    )
}

internal data class RenderRequestSyncResult(
    val active: Boolean,
    val builds: List<SyncBuildProgress>,
    val terminalFailures: List<TerminalPdfBuildFailure> = emptyList(),
)

internal data class RenderPublicationRefresh<T>(
    val renderSync: RenderRequestSyncResult,
    val publicationState: T,
)

/**
 * A failed project must not suppress the conditional check for other changed projects.
 * Fedora deduplicates unchanged inputs per affected subset; retain every failure while
 * refreshing and activating successful publications.
 */
internal suspend fun <T> refreshPublicationsAfterRenderSync(
    explicit: Boolean,
    syncRequestedRenders: suspend () -> RenderRequestSyncResult,
    syncConditionalRender: suspend () -> RenderRequestSyncResult,
    refreshPublicationState: suspend (RenderRequestSyncResult) -> T,
): RenderPublicationRefresh<T> {
    val requested = syncRequestedRenders()
    val automatic = if (explicit) {
        syncConditionalRender()
    } else {
        RenderRequestSyncResult(active = false, builds = emptyList())
    }
    val renderSync = mergeRenderSyncResults(requested, automatic)
    return RenderPublicationRefresh(
        renderSync = renderSync,
        publicationState = refreshPublicationState(renderSync),
    )
}

internal fun mergeRenderSyncResults(
    first: RenderRequestSyncResult,
    second: RenderRequestSyncResult,
): RenderRequestSyncResult {
    val terminalFailures = (first.terminalFailures + second.terminalFailures).distinct()
    return RenderRequestSyncResult(
        active = first.active || second.active,
        builds = (first.builds + second.builds).distinctBy(SyncBuildProgress::buildId),
        terminalFailures = terminalFailures,
    )
}

internal fun syncBuildProgress(build: BuildRecord): SyncBuildProgress = SyncBuildProgress(
    failedDocuments = pdfBuildErrorReport(build).failedDocuments.map { SyncProjectFailure(it.pdfFilename, it.message) },
    logReference = pdfBuildErrorReport(build).logReference,
    workKind = build.repairMode,
    buildId = build.id,
    target = build.target,
    serverStatus = build.status,
    phase = build.progress.safeString("phase").orEmpty(),
    completed = build.progress.safeInt("completed") ?: 0,
    total = build.progress.safeInt("total") ?: 0,
    unit = build.progress.safeString("unit").orEmpty(),
    detail = build.error.orEmpty().ifBlank {
        build.progress.safeString("detail").orEmpty()
    },
    documents = build.progress.getAsJsonArray("documents")
        ?.mapNotNull { value ->
            value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf(::isCanonicalPublishedPdfFilename)
        }
    .orEmpty(),
)

internal fun terminalBuildFailureFromReceipt(
    payload: JsonObject,
    operationId: String?,
    knownBuildIds: Set<String> = emptySet(),
): TerminalPdfBuildFailure? {
    val receiptStatus = payload.safeString("status").orEmpty()
    if (receiptStatus !in setOf("partial_success", "failure")) return null
    val buildId = payload.safeString("build_id").orEmpty()
    val terminalOperationId = payload.safeString("terminal_operation_id").orEmpty()
    if (
        operationId.isNullOrBlank() ||
        (terminalOperationId != operationId && buildId !in knownBuildIds)
    ) {
        return null
    }
    val serverStatus = payload.safeString("server_status").orEmpty()
    val status = TerminalPdfBuildStatus.fromWireStatus(serverStatus) ?: if (
        receiptStatus == "partial_success"
    ) {
        TerminalPdfBuildStatus.ATTENTION
    } else {
        TerminalPdfBuildStatus.FAILED
    }
    val failedDocumentDetail = payload.getAsJsonArray("failed_documents")
        ?.mapNotNull { value ->
            val failure = value.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@mapNotNull null
            val filename = failure.safeString("pdf_filename")
                ?.takeIf(::isCanonicalPublishedPdfFilename)
                ?: return@mapNotNull null
            val message = failure.safeString("message")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            "$filename: $message"
        }
        ?.joinToString("; ")
        .orEmpty()
    return TerminalPdfBuildFailure(
        buildId = buildId,
        status = status,
        detail = payload.safeString("message").orEmpty().ifBlank {
            failedDocumentDetail
        }.ifBlank {
            "The server reported a terminal PDF build failure."
        },
        logReference = payload.safeString("log_reference").orEmpty(),
    )
}

internal fun targetedBuildsActive(
    renderRequestActive: Boolean,
    documentRenderStatus: String?,
): Boolean = renderRequestActive || documentRenderStatus == "building"

private data class PullChangesResult(
    val applied: Boolean,
    val cursor: Long,
)

class ArtifactPlanSupersededException : IllegalStateException(
    "A newer artifact update must be checked before transfer",
)

internal fun SyncTransferPlan.requireRemoteObject(
    sha256: String,
    kind: String,
    role: String,
    expectedSize: Long? = null,
    publication: String? = null,
): SyncTransferObject {
    val planned = remoteObjects.singleOrNull { it.sha256 == sha256 }
        ?: throw ArtifactPlanSupersededException()
    if (
        planned.kind != kind || role !in planned.roles ||
        (expectedSize != null && planned.sizeBytes != expectedSize) ||
        (publication != null && publication !in planned.publications)
    ) {
        throw ArtifactPlanSupersededException()
    }
    return planned
}

internal fun materializedAnnotationSidecars(
    projections: List<ThoughtsProjectionEntity>,
    pendingDeliveries: List<PendingCommentDelivery>,
    pendingOperations: List<ThoughtsOutboxEntity>,
    gson: Gson = Gson(),
): Map<String, String> {
    data class Candidate(val revision: Long, val payloadJson: String)

    fun sidecarJson(payloadJson: String): String {
        val payload = JsonParser.parseString(payloadJson).asJsonObject
        payload.get("sidecar_schema_version")?.asInt?.let {
            payload.addProperty("schema_version", it)
        }
        payload.remove("sidecar_schema_version")
        return gson.toJson(payload)
    }

    val server = linkedMapOf<String, MutableMap<String, Candidate>>()
    projections.filter {
        !it.deleted && it.entityType == "annotation" && it.collection.isSafeAnnotationCollection()
    }
        .forEach { projection ->
            server.getOrPut(projection.collection, ::linkedMapOf)[projection.entityId] =
                Candidate(projection.revision, projection.payloadJson)
        }

    val pending = linkedMapOf<String, MutableMap<String, Candidate>>()
    fun putPending(collection: String, entityId: String, revision: Long, payloadJson: String) {
        if (!collection.isSafeAnnotationCollection()) return
        val records = pending.getOrPut(collection, ::linkedMapOf)
        val current = records[entityId]
        if (current == null || revision >= current.revision) {
            records[entityId] = Candidate(revision, payloadJson)
        }
    }
    pendingDeliveries.forEach { delivery ->
        val collection = delivery.target.dropLast(4)
        delivery.records.forEach { record ->
            putPending(collection, record.id, record.revision.toLong(), record.json)
        }
    }
    pendingOperations.filter { it.entityType == "annotation" }.forEach { operation ->
        val payload = JsonParser.parseString(operation.payloadJson).asJsonObject
        val revision = payload.get("revision")?.asLong
            ?: operation.baseRevision?.plus(1)
            ?: 1L
        putPending(operation.collection, operation.entityId, revision, operation.payloadJson)
    }

    return (server.keys + pending.keys).associateWith { collection ->
        val records = linkedMapOf<String, Candidate>()
        server[collection]?.let(records::putAll)
        pending[collection]?.let(records::putAll)
        records.toSortedMap().values.joinToString("\n", postfix = "\n") { candidate ->
            sidecarJson(candidate.payloadJson)
        }
    }
}

internal fun shouldPullFinalChanges(latestServerSequence: Long?, localCursor: Long): Boolean =
    latestServerSequence != null && latestServerSequence > localCursor

internal fun reviewCardProjectionMatchesActivatedPdf(
    payloadJson: String,
    activatedPdfSha256ByFilename: Map<String, String>,
): Boolean = runCatching {
    val payload = JsonParser.parseString(payloadJson).asJsonObject
    val primary = payload.getAsJsonObject("primary") ?: return@runCatching false
    val pdfStem = primary.get("pdf_stem")?.asString.orEmpty()
    val pdfSha256 = primary.get("pdf_sha256")?.asString.orEmpty().lowercase()
    // Failure and pending status must reach the client even when the retained
    // answer geometry belongs to an older PDF; review eligibility still checks
    // the current PDF identity in ReviewStore.
    val placementUnavailable = payload.getAsJsonObject("reanchor")?.get("state")?.asString in
        setOf("needs_attention", "queued", "retry", "running", "updating") ||
        payload.get("status")?.asString == "needs_reanchor"
    pdfStem.isNotBlank() && pdfSha256.matches(Regex("^[0-9a-f]{64}$")) &&
        (placementUnavailable || activatedPdfSha256ByFilename["$pdfStem.pdf"]?.lowercase() == pdfSha256)
}.getOrDefault(false)

private fun String.isSafeAnnotationCollection(): Boolean =
    isNotBlank() && length <= 256 && !contains('/') && !contains('\\')

internal fun retireManagedUnlinkedPublications(
    root: File,
    previousLibraryIndex: String?,
    activeFilenames: Set<String>,
): List<String> {
    if (previousLibraryIndex.isNullOrBlank()) return emptyList()
    val entries = runCatching {
        val index = JsonParser.parseString(previousLibraryIndex).asJsonObject
        check(index.get("schema_version")?.asInt == 1)
        val pdfs = index.getAsJsonArray("pdfs") ?: error("Library index has no PDF list")
        val parsed = pdfs.map { value ->
            val entry = value.asJsonObject
            val filename = entry.get("filename")?.asString
                ?.takeIf { it.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*\\.pdf$", RegexOption.IGNORE_CASE)) }
                ?: error("Invalid Library filename")
            val kind = entry.get("kind")?.asString
                ?.takeIf { it == "source_linked" || it == "unlinked" }
                ?: error("Invalid Library kind")
            val sha256 = entry.get("pdf_sha256")?.asString
                ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                ?: error("Invalid Library hash")
            Triple(filename, kind, sha256)
        }
        check(parsed.map { it.first }.toSet().size == parsed.size)
        parsed
    }.getOrNull() ?: return emptyList()

    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
    return entries.mapNotNull { (filename, kind, expectedSha256) ->
        if (kind != "unlinked" || filename in activeFilenames) return@mapNotNull null
        val destination = File(root, filename)
        if (!destination.isFile || runCatching { destination.canonicalFile.parentFile == canonicalRoot }.getOrDefault(false).not()) {
            return@mapNotNull null
        }
        if (retirementFileSha256(destination) != expectedSha256 || !destination.delete()) return@mapNotNull null
        filename
    }
}

private fun retirementFileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

class FedoraRepository(
    context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val secretStore: ThoughtsServerSecretStore = ThoughtsServerSecretStore(context),
    private val gson: Gson = Gson(),
) {
    private val appContext = context.applicationContext
    private val sourceAnnotations = com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.get(appContext)
    private val dao = database.thoughtsSyncDao()
    private val connectionFactory: (URL) -> URLConnection = { url ->
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        if (network != null && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            // Each Network object keeps its own connection pool, and activeNetwork returns a new object
            // every time, so every request paid a fresh TCP and TLS handshake (about 85 ms). Reuse one.
            val pooled = pooledNetwork?.takeIf { it == network } ?: network.also { pooledNetwork = it }
            TokenConnections.withoutRedirects(pooled.openConnection(url))
        } else {
            TokenConnections.open(url)
        }
    }
    @Volatile
    private var activeTransferProgress: SyncTransferProgressReporter? = null

    /** The trace of the Sync this repository is running; every request of that Sync carries it. */
    @Volatile
    private var activeSyncTraceId: String? = null
    /** Library changes Fedora had acknowledged when the latest Library snapshot was requested. */
    private var libraryChangesDeliveredBeforeFetch: Set<String> = emptySet()

    /**
     * When this Sync delivered an Accept that keeps the PDF (it already shows the accepted text).
     * Fedora rebuilds that PDF afterwards; see [awaitDeferredAcceptRebuild].
     */
    @Volatile
    private var pdfKeepingAcceptDeliveredAtMillis: Long? = null

    /**
     * This Sync delivered something besides Accepts that can change the PDF's text (a source edit, a
     * Reject, an Undo, other uploaded operations): its rebuild is awaited as before. A Sync that only
     * accepted proposals ends at once and leaves the rebuild to [AcceptRebuildFollowUpWorker].
     */
    @Volatile
    private var pdfChangingDeliveredThisSync = false

    /** Explicit export is independent of the Sync queue and captures one immutable revision. */
    /** [documentIds] limits the export to those documents (kept in project order); null exports all. */
    suspend fun exportProjectPdf(
        project: JsonObject,
        documentIds: List<String>? = null,
        progress: suspend (String) -> Unit,
    ): Pair<File, Boolean> = withContext(Dispatchers.IO) {
        val id = project.string("id")
        val cachedRevision = project.string("reading_revision")
        require(id.isNotBlank() && cachedRevision.matches(SHA256)) { "Sync this project before exporting it" }
        require(documentIds == null || documentIds.isNotEmpty()) { "Choose at least one document to export" }
        val client = syncClient(requireNotNull(secretStore.config()) { "Configure Sync to export this project" })
        // The Library list can lag Fedora's latest build, so export the project's current revision.
        progress("Checking the project's latest version…")
        val revision = runCatching {
            client.libraryProjects().getAsJsonArray("projects")?.map { it.asJsonObject }
                ?.firstOrNull { it.string("id") == id }?.string("reading_revision")?.takeIf { it.matches(SHA256) }
        }.onFailure { Log.w("ThoughtsProjectExport", "Exporting the cached project revision", it) }.getOrNull() ?: cachedRevision
        // Each selection is its own export of the captured revision.
        val selection = documentIds?.sorted()?.joinToString("\n")?.let { sha256(it.toByteArray()).take(16) }
        val exportKey = if (selection == null) revision else "$revision-$selection"
        val preferences = appContext.getSharedPreferences("project-exports", Context.MODE_PRIVATE)
        val requestId = preferences.getString(exportKey, null) ?: java.util.UUID.randomUUID().toString().also {
            check(preferences.edit().putString(exportKey, it).commit()) { "Could not save the export request" }
        }
        progress("Exporting the project…")
        var status = try {
            client.requestProjectExport(JsonObject().apply {
                addProperty("request_id", requestId); addProperty("project_id", id); addProperty("reading_revision", revision)
                documentIds?.let { ids -> add("document_ids", JsonArray().apply { ids.forEach(::add) }) }
            })
        } catch (error: Exception) {
            if (documentIds != null && error.message.orEmpty().contains("requires a request identity"))
                error("The server cannot export chosen documents yet. Export all documents, or update the server.")
            throw error
        }
        while (true) {
            check(status.string("id") == requestId && status.string("reading_revision") == revision) { "The export response has a different identity" }
            when (status.string("status")) {
                "queued", "running" -> {
                    progress(if (status.string("status") == "queued") "Export queued…" else "Creating the combined PDF…")
                    kotlinx.coroutines.delay(2000)
                    status = client.projectExport(requestId)
                }
                "failed" -> { preferences.edit().remove(exportKey).commit(); error(status.string("error").ifBlank { "Project export failed" }) }
                "complete" -> break
                else -> error("The export response is incomplete")
            }
        }
        val digest = status.string("sha256")
        val size = status.get("size_bytes").asLong
        require(digest.matches(SHA256) && size > 0)
        val directory = File(appContext.filesDir, "project_exports/$exportKey").apply { check(isDirectory || mkdirs()) }
        val title = project.string("name").replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(100).ifBlank { "Project" }
        val destination = File(directory, "$title.pdf")
        if (!(destination.isFile && destination.length() == size && fileSha256(destination) == digest)) {
            progress("Downloading the combined PDF…")
            val temporary = File.createTempFile("export-", ".part", directory)
            try {
                temporary.outputStream().use { output -> client.downloadObject(digest, output) }
                check(temporary.length() == size && fileSha256(temporary) == digest) { "The exported PDF failed verification" }
                check(temporary.renameTo(destination)) { "Could not save the exported PDF" }
            } finally { temporary.delete() }
        }
        destination to (status.get("older_revision")?.asBoolean == true)
    }

    /**
     * PDFs that belong to a project reading: those in the activated readings plus every Library
     * project document, which may not be in a reading yet. Null when unreadable.
     */
    private fun projectMemberPdfFilenames(): Set<String>? = runCatching {
        val directory = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val generation = File(directory, SourceLinkedPdfIndex.GENERATION_FILENAME)
        // Loading a reading verifies and parses every member package (seconds for a large
        // Library); readings change only with the generation file, so reuse the member list.
        val generationIdentity = "${generation.length()}:${generation.lastModified()}"
        val readings = projectReadingMembers?.takeIf { it.first == generationIdentity }?.second ?: (if (generation.isFile) {
            JsonParser.parseString(generation.readText()).asJsonObject
                .getAsJsonArray("project_readings")?.flatMap { row ->
                    val manifest = File(directory, row.asJsonObject.get("filename").asString)
                    ProjectReadingContext.load(manifest).documents.map { it.filename }
                }.orEmpty()
        } else {
            emptyList()
        }).also { projectReadingMembers = generationIdentity to it }
        val projects = LibraryProjectStore(appContext).projects()
            .flatMap { project ->
                project.asJsonObject.getAsJsonArray("documents")?.mapNotNull { document ->
                    document.asJsonObject.get("filename")?.takeUnless { it.isJsonNull }?.asString
                }.orEmpty()
            }
        (readings + projects).toSet()
    }.getOrNull()

    private fun syncClient(
        config: ThoughtsServerConfig,
        traceId: String? = null,
        transferProgress: ObjectTransferProgressListener? = null,
    ): SyncClient = SyncClient(
        config.baseUrl,
        config.token,
        gson,
        connectionFactory = connectionFactory,
    ).setTraceId(traceId).setObjectTransferProgressListener(transferProgress)

    /**
     * Capabilities change only with a server update, but one Sync asked for them up to three
     * times (about 120 ms each). Reuse one answer per server for a minute.
     */
    private fun serverCapabilities(client: SyncClient): Collection<String> {
        val key = secretStore.config()?.baseUrl.orEmpty()
        val now = android.os.SystemClock.elapsedRealtime()
        cachedCapabilities?.takeIf { it.first == key && now - it.second < CAPABILITY_CACHE_MS }?.let { return it.third }
        return client.ready().capabilities.toSet().also { cachedCapabilities = Triple(key, now, it) }
    }

    /**
     * Fedora's Sync cursors (GET /v2/sync/state, cached on the server) for [RemoteWorkWatch]: a key
     * naming that state, and whether its operation sequence is ahead of this phone. Nothing local
     * changes. Null when no server is configured or it cannot be asked.
     */
    internal suspend fun remoteWork(): RemoteWork? = withContext(Dispatchers.IO) {
        val config = secretStore.config() ?: return@withContext null
        val state = runCatching {
            val connection = connectionFactory(java.net.URI.create(config.baseUrl.trimEnd('/') + "/v2/sync/state").toURL())
                as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "Bearer ${config.token}")
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = connection.inputStream.use { it.readBytes() }
                if (body.size > 64 * 1024) return@runCatching null
                com.google.gson.JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return@withContext null
        val latest = runCatching { state.get("latest_server_sequence").asLong }.getOrNull() ?: return@withContext null
        val revision = runCatching { state.get("library_revision").asString }.getOrNull().orEmpty()
        val frontier = runCatching { state.get("source_frontier").asLong }.getOrDefault(-1L)
        if (runCatching { state.get("contract_version").asInt }.getOrNull() != 2 || latest < 0 || !SHA256.matches(revision)) {
            return@withContext null
        }
        RemoteWork(key = "$latest/$revision/$frontier", cursorBehind = (dao.state()?.serverCursor ?: 0L) < latest)
    }

    /**
     * The server side of HTML editions for [com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionSync]:
     * capabilities, `GET /v1/html-editions/{pdf_sha256}` and objects by SHA-256 (`/v1/objects/`,
     * outside any Sync plan). Null when no server is configured.
     */
    fun htmlEditionRemote(): com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionRemote? {
        val config = secretStore.config() ?: return null
        val client = syncClient(config)
        return object : com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionRemote {
            override fun capabilities(): Set<String> = serverCapabilities(client).toSet()

            override fun edition(pdfSha256: String): com.google.gson.JsonObject? {
                require(pdfSha256.matches(Regex("[0-9a-f]{64}")))
                val connection = connectionFactory(java.net.URI.create(config.baseUrl.trimEnd('/') +
                    "/v1/html-editions/$pdfSha256").toURL()) as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("Authorization", "Bearer ${config.token}")
                    connection.setRequestProperty("Accept", "application/json")
                    val status = connection.responseCode
                    if (status == 404) return null
                    if (status !in 200..299) throw java.io.IOException("HTML edition request failed: HTTP $status")
                    val body = connection.inputStream.use { it.readBytes() }
                    if (body.size > 8 * 1024 * 1024) throw java.io.IOException("HTML edition record is too large")
                    return com.google.gson.JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject
                } finally {
                    connection.disconnect()
                }
            }

            override fun download(sha256: String, destination: File) {
                destination.outputStream().use { output -> client.downloadObject(sha256, output) }
            }
        }
    }

    fun serverStorageUsage(): ServerStorageUsage? {
        val config = requireNotNull(secretStore.config()) { "The Thoughts server is not configured" }
        return syncClient(config).ready().storage
    }

    internal fun downloadNoteAttachment(item: com.gitlab.mudlej.MjPdfReader.data.annotation.NoteAttachment): File {
        val store = com.gitlab.mudlej.MjPdfReader.data.annotation.NoteAttachmentStore(appContext)
        return store.receive(item) { destination ->
            val config = requireNotNull(secretStore.config()) { "Configure Sync to download this attachment" }
            destination.outputStream().use { output ->
                val limited = object : java.io.OutputStream() {
                    var count = 0L
                    override fun write(value: Int) {
                        check(++count <= item.size) { "Attachment exceeds its expected size" }
                        output.write(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        count += length
                        check(count <= item.size) { "Attachment exceeds its expected size" }
                        output.write(bytes, offset, length)
                    }
                }
                syncClient(config).downloadObject(item.sha256, limited)
                output.fd.sync()
            }
        }
    }

    internal suspend fun effectiveLogicalCommentPayloads(scope: com.gitlab.mudlej.MjPdfReader.data.source.LogicalCommentReadScope? = null, includeSourceJournals: Boolean = true): List<JsonObject> {
        val values = sourceAnnotations.read(storedLogicalCommentPayloads(scope, includeSourceJournals))
        val deletedRoots = values.filter { it.get("deleted")?.asBoolean == true && it.safeString("parent_id").isNullOrBlank() }
            .map { it.safeString("comment_id") }.toSet()
        return values.map { payload -> payload.apply {
            if (safeString("thread_root_id") in deletedRoots) addProperty("deleted", true)
        } }
    }

    /**
     * The comment store as "Comment missing" reads it (docs/comment-missing.md): whether a Sync has
     * completed, every logical comment as stored (server snapshot, outbox, unsent source journals;
     * before source projection), and the legacy annotation records.
     */
    internal suspend fun commentMissingStore(): com.gitlab.mudlej.MjPdfReader.data.source.CommentMissingStore {
        val state = dao.state() ?: ThoughtsSyncStateEntity()
        val loaded = com.gitlab.mudlej.MjPdfReader.data.source.CommentMissingState.storeLoaded(
            state.projectionBootstrapped, state.serverCursor, dao.projectionCount() > 0,
        )
        if (!loaded) return com.gitlab.mudlej.MjPdfReader.data.source.CommentMissingStore(false, emptyList())
        val annotations = linkedMapOf<String, JsonObject>()
        dao.annotationProjections().forEach { projection ->
            runCatching { JsonParser.parseString(projection.payloadJson).asJsonObject }.getOrNull()?.let { payload ->
                if (projection.deleted) payload.addProperty("deleted", true)
                annotations[projection.entityId] = payload
            }
        }
        dao.undeliveredByEntityType("annotation").forEach { operation ->
            runCatching { JsonParser.parseString(operation.payloadJson).asJsonObject }.getOrNull()?.let { annotations[operation.entityId] = it }
        }
        return com.gitlab.mudlej.MjPdfReader.data.source.CommentMissingStore(
            true, storedLogicalCommentPayloads(null, includeSourceJournals = true), annotations.toList(),
        )
    }

    /**
     * Restore Comment of a "Comment missing" marker: one logical-comment operation, sent in its own
     * batch so that a refusal (409) blocks only this operation. No source edit is derived from it.
     */
    internal suspend fun enqueueIsolatedLogicalComment(payload: JsonObject) {
        val mutation = prepareLogicalComment(payload)
        IsolatedOperations(appContext).add(mutation.operationId, dao.undelivered().map { it.operationId }.toSet())
        enqueue(listOf(mutation))
    }

    private suspend fun storedLogicalCommentPayloads(scope: com.gitlab.mudlej.MjPdfReader.data.source.LogicalCommentReadScope?, includeSourceJournals: Boolean): List<JsonObject> {
        data class Candidate(val revision: Long, val order: Long, val payload: JsonObject)

        val byId = linkedMapOf<String, Candidate>()
        // Completed deletions remain durable even when a live-only snapshot omits tombstones.
        val completed = if (scope == null) dao.completedLocalCommentDeletions() else scope.queries(
            "SELECT state.* FROM ThoughtsLocalCommentState AS state INNER JOIN ThoughtsOutbox AS operation ON operation.operationId = state.operationId WHERE state.deleted = 1 AND operation.deliveryState IN ('accepted', 'reconciled')", "state.entityId",
        ).flatMap { dao.scopedLocalCommentStates(it) }.distinctBy { it.entityId }
        completed.forEach { operation ->
            val payload = runCatching { JsonParser.parseString(operation.payloadJson).asJsonObject }
                .getOrNull() ?: return@forEach
            if (payload.get("deleted")?.asBoolean != true) return@forEach
            val revision = payload.safeInt("revision")?.toLong() ?: return@forEach
            val previous = byId[operation.entityId]
            if (previous == null || revision >= previous.revision) {
                byId[operation.entityId] = Candidate(revision, 0L, payload)
            }
        }
        val projections = if (scope == null) dao.logicalCommentProjections() else scope.queries(
            "SELECT * FROM ThoughtsProjection WHERE entityType = 'logical_comment' AND collection = 'comments'", "entityId",
        ).flatMap { dao.scopedLogicalCommentProjections(it) }.distinctBy { it.entityId }
        projections.forEach { projection ->
                val payload = runCatching { JsonParser.parseString(projection.payloadJson).asJsonObject }
                    .getOrNull() ?: return@forEach
                val previous = byId[projection.entityId]
                if (previous == null || projection.revision >= previous.revision) {
                    byId[projection.entityId] = Candidate(projection.revision, projection.serverSequence, payload)
                }
            }
        // Accepted changes whose server copy has not been pulled yet: without them a new comment
        // vanishes, and an edit reverts, between upload and the next successful pull.
        val accepted = if (scope == null) dao.acceptedLocalCommentStates() else scope.queries(
            "SELECT state.* FROM ThoughtsLocalCommentState AS state INNER JOIN ThoughtsOutbox AS operation ON operation.operationId = state.operationId WHERE state.deleted = 0 AND operation.deliveryState = 'accepted'", "state.entityId",
        ).flatMap { dao.scopedLocalCommentStates(it) }.distinctBy { it.entityId }
        accepted.forEach { operation ->
            val payload = runCatching { JsonParser.parseString(operation.payloadJson).asJsonObject }
                .getOrNull() ?: return@forEach
            val revision = payload.safeInt("revision")?.toLong() ?: return@forEach
            val current = byId[operation.entityId]
            if (current == null || revision > current.revision) {
                byId[operation.entityId] = Candidate(revision, operation.deviceSequence, payload)
            }
        }
        val pending = if (scope == null) dao.pendingLocalCommentStates() else scope.queries(
            "SELECT state.* FROM ThoughtsLocalCommentState AS state INNER JOIN ThoughtsOutbox AS operation ON operation.operationId = state.operationId WHERE operation.deliveryState IN ('pending', 'blocked')", "state.entityId",
        ).flatMap { dao.scopedLocalCommentStates(it) }.distinctBy { it.entityId }
        pending.forEach { operation ->
                val payload = runCatching { JsonParser.parseString(operation.payloadJson).asJsonObject }
                    .getOrNull() ?: return@forEach
                val revision = payload.safeInt("revision")?.toLong() ?: return@forEach
                val current = byId[operation.entityId]
                if (current == null || revision > current.revision || (revision == current.revision && operation.deviceSequence >= current.order)) {
                    byId[operation.entityId] = Candidate(revision, operation.deviceSequence, payload)
                }
            }
        for (journal in if (includeSourceJournals) sourceCommentJournals() else emptyList()) {
            for (payload in journal.second) {
                val id = payload.safeString("comment_id") ?: continue
                val revision = payload.safeInt("revision")?.toLong() ?: continue
                if (scope != null && id !in byId && !scope.accepts(payload)) continue
                val current = byId[id]
                if (current == null || revision > current.revision) byId[id] = Candidate(revision, Long.MAX_VALUE, payload)
            }
        }
        return byId.toSortedMap().values.map { restoreNoteNulls(it.payload) }
    }

    suspend fun enqueueLogicalComment(payload: JsonObject) = enqueueLogicalComments(listOf(payload))

    private suspend fun sourceCommentJournals(): List<Pair<String, List<JsonObject>>> {
        val state = database.commentReadStateDao()
        val marker = "source-comment-journals-v1"
        if (state.checkpoint(marker) == null) {
            val legacy = File(appContext.filesDir, "source-comment-journal").listFiles().orEmpty()
                .filter { it.extension == "json" }.sortedBy { it.name }.map { file ->
                    val raw = file.readText()
                    JsonParser.parseString(raw).asJsonArray // Validate before marking migration complete.
                    com.gitlab.mudlej.MjPdfReader.data.annotation.CommentMutationJournal(file.name, raw)
                }
            // Keep the original files for recovery; the durable marker prevents replay.
            state.migrateJournals(legacy, marker)
        }
        return state.journals().map { row ->
            row.id to JsonParser.parseString(row.payloadJson).asJsonArray.map { it.asJsonObject }
        }
    }

    private suspend fun recoverSourceCommentJournals() {
        val sourceComments = com.gitlab.mudlej.MjPdfReader.data.source.LocalSourceComments(appContext)
        val durable = effectiveLogicalCommentPayloads(includeSourceJournals = false).associateBy { it.safeString("comment_id") }
        for ((file, payloads) in sourceCommentJournals()) {
            val pending = payloads.filter { payload ->
                (durable[payload.safeString("comment_id")]?.safeInt("revision") ?: 0) < (payload.safeInt("revision") ?: 0)
            }.map { sourceComments.apply(it) }
            enqueue(pending.map(::prepareLogicalComment))
            database.commentReadStateDao().removeJournal(file)
        }
    }

    suspend fun enqueueLogicalComments(payloads: List<JsonObject>) {
        if (payloads.isEmpty()) return
        val previous = effectiveLogicalCommentPayloads().associateBy { it.safeString("comment_id") }
        payloads.forEach(::prepareLogicalComment)
        val journal = UUID.randomUUID().toString()
        val saved = JsonArray().apply { payloads.forEach { payload -> add(payload.deepCopy().apply {
            if (safeString("subtype") != "Note") addProperty("source_authority", "client-v1")
        }) } }.toString()
        database.commentReadStateDao().putJournals(listOf(
            com.gitlab.mudlej.MjPdfReader.data.annotation.CommentMutationJournal(journal, saved),
        ))
        val sourceComments = com.gitlab.mudlej.MjPdfReader.data.source.LocalSourceComments(appContext)
        val prepared = payloads.map { sourceComments.apply(it, previous[it.safeString("comment_id")], mutate = true) }
        enqueue(prepared.map(::prepareLogicalComment))
        database.commentReadStateDao().removeJournal(journal)
    }

    private fun prepareLogicalComment(payload: JsonObject): PendingMutation {
        val id = payload.safeString("comment_id").orEmpty()
        val revision = payload.safeInt("revision")?.takeIf { it >= 1 }
            ?: throw IllegalArgumentException("Invalid logical comment revision")
        require(id.isNotBlank() && id.length <= 512 && !id.contains('/') && !id.contains('\\')) {
            "Invalid logical comment identity"
        }
        val origin = payload.get("origin")
        val scope = payload.get("scope")?.takeIf { it.isJsonObject }?.asJsonObject
        val inheritedOrSourceOrigin = payload.safeString("parent_id").orEmpty().isNotBlank() ||
            scope?.safeString("state") in setOf("source_linked", "linking", "needs_attention") ||
            (payload.safeString("subtype") == "Note" && scope?.safeString("state") == "standalone")
        require(payload.safeInt("schema_version") == 1 &&
            (origin?.isJsonObject == true || (origin?.isJsonNull == true && inheritedOrSourceOrigin)) && scope != null) {
            "Invalid logical comment payload"
        }
        require(payload.safeString("thread_root_id").orEmpty().isNotBlank()) {
            "Invalid logical comment thread"
        }
        val timestamp = payload.safeString("updated_at").orEmpty().takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Invalid logical comment timestamp")
        val canonical = payload.deepCopy().apply {
            remove("locations")
            remove("source_file")
            remove("source_line")
            remove("source_sha256")
        }
        return PendingMutation(
                    operationId = stableOperationId(
                        "logical_comment",
                        id,
                        revision.toString(),
                        sha256(canonical.toString().toByteArray(StandardCharsets.UTF_8)),
                    ),
                    entityType = "logical_comment",
                    entityId = id,
                    collection = "comments",
                    baseRevision = (revision - 1).toLong(),
                    clientTimestamp = timestamp,
                    payload = canonical,
        )
    }

    suspend fun captureCommentBatches(batches: Collection<PendingCommentDelivery>) = COMMENT_CAPTURE_MUTEX.withLock {
        val logical = effectiveLogicalCommentPayloads().associateBy { it.safeString("comment_id").orEmpty() }.toMutableMap()
        var processed = 0
        val ordered = orderCommentCreations(
            batches.flatMap { batch -> batch.records.map { batch to it } }, logical.keys,
            { it.second.id },
            { JsonParser.parseString(it.second.json).asJsonObject.string("in_reply_to") },
        )
        val mutations = ordered.mapNotNull { (batch, record) ->
            val collection = batch.target.removeSuffix(".pdf")
                runCatching { JsonParser.parseString(record.json).asJsonObject }.getOrNull()?.let { payload ->
                    val pdfSha256 = payload.string("annotation_pdf_sha256")
                        .takeIf(SHA256::matches)
                        // Keep unknown legacy provenance unknown. Saving the
                        // comment must not depend on finding its old PDF.
                        ?: "0".repeat(64)
                    val sidecarSchema = payload.get("schema_version")?.asInt ?: 5
                    payload.addProperty("sidecar_schema_version", sidecarSchema)
                    payload.addProperty("schema_version", 1)
                    payload.addProperty("annotation_id", record.id)
                    payload.addProperty("document_id", DocumentIdentityStore().identifier(batch.target))
                    payload.addProperty("pdf_sha256", pdfSha256)
                    payload.addProperty("updated_at", payload.timestamp())
                    payload.addProperty("origin_id", payload.string("origin_id").ifBlank { "thoughts-android" })
                    payload.addProperty("discussion", true)
                    val canonical = canonicalCommentForAnnotation(payload, collection, logical[record.id], logical[payload.string("in_reply_to")])
                    processed++
                    if (canonical == null) return@let null
                    logical[record.id] = canonical
                    PendingMutation(
                        operationId = stableOperationId(
                            "logical_comment",
                            "comments",
                            record.id,
                            canonical.get("revision").asString,
                            sha256(canonical.toString().toByteArray(StandardCharsets.UTF_8)),
                        ),
                        entityType = "logical_comment",
                        entityId = record.id,
                        collection = "comments",
                        baseRevision = canonical.get("revision").asLong - 1,
                        clientTimestamp = payload.timestamp(),
                        payload = canonical,
                    )
                }
        }
        check(processed == batches.sumOf { it.records.size }) {
            "A pending Library comment could not be converted into a durable logical comment"
        }
        enqueueLogicalComments(mutations.map { it.payload })
    }

    suspend fun captureReviewEvents() {
        val store = ReviewStore(appContext)
        val payloads = checkNotNull(store.pendingPayloads("events"))
        val mutations = payloads.mapNotNull { payload ->
                val eventId = payload.string("event_id").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                PendingMutation(
                    operationId = stableOperationId("review_event", eventId),
                    entityType = "review_event",
                    entityId = eventId,
                    collection = "events",
                    baseRevision = null,
                    clientTimestamp = payload.string("reviewed_at").ifBlank { Instant.now().toString() },
                    payload = payload,
                )
            }
            .toList()
        enqueue(mutations)
    }

    /** Queues tag merges the server does not have yet; merges are permanent, so each is sent once. */
    private suspend fun captureReviewTagMerges() {
        val known = dao.reviewTagMergeProjections().map { it.entityId }.toSet()
        enqueue(com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges.merges()
            .filter { it.id !in known }
            .map { merge ->
                PendingMutation(
                    operationId = stableOperationId(com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges.ENTITY_TYPE, merge.id),
                    entityType = com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges.ENTITY_TYPE,
                    entityId = merge.id,
                    collection = com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges.COLLECTION,
                    baseRevision = null,
                    clientTimestamp = merge.createdAt,
                    payload = merge.payload(),
                )
            })
    }

    /** Queues the latest local tag group layout and retires older unsent ones (last write wins). */
    private suspend fun captureReviewTagGroups(capabilities: Collection<String>, targeted: Boolean) {
        val groups = com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups
        val plan = reviewTagGroupsOutboxPlan(
            capabilities, targeted, groups.pendingUpload(), dao.undeliveredByEntityType(groups.ENTITY_TYPE),
        )
        plan.superseded.forEach {
            dao.reconcileDeletion(it, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "replaced_by_newer_layout",
                "A later tag group layout replaces this one.", System.currentTimeMillis())
        }
        val upload = plan.upload ?: return
        enqueue(listOf(PendingMutation(
            operationId = upload.operationId,
            entityType = groups.ENTITY_TYPE,
            entityId = groups.ENTITY_ID,
            collection = groups.COLLECTION,
            baseRevision = null,
            clientTimestamp = upload.clientTimestamp,
            payload = upload.payload,
        )))
    }

    /** Queues local PDF link override edits (latest revision of each) and retires older unsent ones. */
    private suspend fun capturePdfLinkOverrides(capabilities: Collection<String>, targeted: Boolean) {
        val overrides = com.gitlab.mudlej.MjPdfReader.data.links.PdfLinkOverrides
        val plan = pdfLinkOverridesOutboxPlan(
            capabilities, targeted, overrides.pendingUploads(), dao.undeliveredByEntityType(overrides.ENTITY_TYPE),
        )
        plan.superseded.forEach {
            dao.reconcileDeletion(it, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "replaced_by_newer_link_override",
                "A later edit of this link override replaces this one.", System.currentTimeMillis())
        }
        enqueue(plan.uploads.map { upload ->
            PendingMutation(
                operationId = upload.operationId,
                entityType = overrides.ENTITY_TYPE,
                entityId = upload.entityId,
                collection = overrides.COLLECTION,
                baseRevision = upload.baseRevision,
                clientTimestamp = upload.clientTimestamp,
                payload = upload.payload,
            )
        })
    }

    /** Queues local agent conversation removals (latest revision of each) and retires older unsent ones. */
    private suspend fun captureAgentConversations(capabilities: Collection<String>, targeted: Boolean) {
        val conversations = com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations
        val plan = agentConversationsOutboxPlan(
            capabilities, targeted, conversations.pendingUploads(), dao.undeliveredByEntityType(conversations.ENTITY_TYPE),
        )
        plan.superseded.forEach {
            dao.reconcileDeletion(it, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "replaced_by_newer_agent_conversation",
                "A later edit of this agent conversation replaces this one.", System.currentTimeMillis())
        }
        enqueue(plan.uploads.map { upload ->
            PendingMutation(
                operationId = upload.operationId,
                entityType = conversations.ENTITY_TYPE,
                entityId = upload.entityId,
                collection = conversations.COLLECTION,
                baseRevision = upload.baseRevision,
                clientTimestamp = upload.clientTimestamp,
                payload = upload.payload,
            )
        })
    }

    /** Queues local review card feedback writes (latest revision of each) and retires older unsent ones. */
    private suspend fun captureReviewCardFeedback(capabilities: Collection<String>, targeted: Boolean) {
        val feedback = com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
        val plan = reviewCardFeedbackOutboxPlan(
            capabilities, targeted, feedback.pendingUploads(), dao.undeliveredByEntityType(feedback.ENTITY_TYPE),
        )
        plan.superseded.forEach {
            dao.reconcileDeletion(it, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "replaced_by_newer_feedback",
                "A later edit of this feedback replaces this one.", System.currentTimeMillis())
        }
        enqueue(plan.uploads.map { upload ->
            PendingMutation(
                operationId = upload.operationId,
                entityType = feedback.ENTITY_TYPE,
                entityId = upload.entityId,
                collection = feedback.COLLECTION,
                baseRevision = upload.baseRevision,
                clientTimestamp = upload.clientTimestamp,
                payload = upload.payload,
            )
        })
    }

    /** Installs the server's review card feedback, settling local writes the server has accepted or overtaken. */
    private suspend fun installReviewCardFeedback() {
        val feedback = com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
        val states = feedback.pendingUploads().associate { it.operationId to dao.operation(it.operationId)?.deliveryState }
        feedback.installServer(
            dao.reviewCardFeedbackProjections().mapNotNull { runCatching { JsonParser.parseString(it.payloadJson).asJsonObject }.getOrNull() },
            accepted = states.filterValues { it == ThoughtsOutboxEntity.STATE_ACCEPTED }.keys,
            superseded = states.filterValues { it == ThoughtsOutboxEntity.STATE_SUPERSEDED }.keys,
        )
    }

    /** Queues local document feedback writes (latest revision of each) and retires older unsent ones. */
    private suspend fun captureDocumentFeedback(capabilities: Collection<String>, targeted: Boolean) {
        val feedback = com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
        val plan = documentFeedbackOutboxPlan(
            capabilities, targeted, feedback.pendingUploads(), dao.undeliveredByEntityType(feedback.ENTITY_TYPE),
        )
        plan.superseded.forEach {
            dao.reconcileDeletion(it, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "replaced_by_newer_feedback",
                "A later edit of this feedback replaces this one.", System.currentTimeMillis())
        }
        enqueue(plan.uploads.map { upload ->
            PendingMutation(
                operationId = upload.operationId,
                entityType = feedback.ENTITY_TYPE,
                entityId = upload.entityId,
                collection = feedback.COLLECTION,
                baseRevision = upload.baseRevision,
                clientTimestamp = upload.clientTimestamp,
                payload = upload.payload,
            )
        })
    }

    /** Installs the server's document feedback, settling local writes the server has accepted or overtaken. */
    private suspend fun installDocumentFeedback() {
        val feedback = com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
        val states = feedback.pendingUploads().associate { it.operationId to dao.operation(it.operationId)?.deliveryState }
        feedback.installServer(
            dao.documentFeedbackProjections().mapNotNull { runCatching { JsonParser.parseString(it.payloadJson).asJsonObject }.getOrNull() },
            accepted = states.filterValues { it == ThoughtsOutboxEntity.STATE_ACCEPTED }.keys,
            superseded = states.filterValues { it == ThoughtsOutboxEntity.STATE_SUPERSEDED }.keys,
        )
    }

    /** Settles local agent conversation removals the server has accepted or overtaken. */
    private suspend fun installAgentConversations() {
        val conversations = com.gitlab.mudlej.MjPdfReader.data.agents.AgentConversations
        val states = conversations.pendingUploads().associate { it.operationId to dao.operation(it.operationId)?.deliveryState }
        conversations.installServer(
            dao.agentConversationProjections().mapNotNull { runCatching { JsonParser.parseString(it.payloadJson).asJsonObject }.getOrNull() },
            accepted = states.filterValues { it == ThoughtsOutboxEntity.STATE_ACCEPTED }.keys,
            superseded = states.filterValues { it == ThoughtsOutboxEntity.STATE_SUPERSEDED }.keys,
        )
    }

    /** Installs the server's link overrides, settling local edits the server has accepted or overtaken. */
    private suspend fun installPdfLinkOverrides() {
        val overrides = com.gitlab.mudlej.MjPdfReader.data.links.PdfLinkOverrides
        val states = overrides.pendingUploads().associate { it.operationId to dao.operation(it.operationId)?.deliveryState }
        overrides.installServer(
            dao.pdfLinkOverrideProjections().mapNotNull { runCatching { JsonParser.parseString(it.payloadJson).asJsonObject }.getOrNull() },
            accepted = states.filterValues { it == ThoughtsOutboxEntity.STATE_ACCEPTED }.keys,
            superseded = states.filterValues { it == ThoughtsOutboxEntity.STATE_SUPERSEDED }.keys,
        )
    }

    suspend fun captureReviewCards() {
        val store = ReviewStore(appContext)
        store.requireSourceRecoveryComplete()
        val mutations = checkNotNull(store.pendingPayloads("cards")).mapNotNull { payload ->
                val cardId = payload.string("card_id").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val capture = reviewCardCaptureMetadata(payload) ?: return@mapNotNull null
                PendingMutation(
                    operationId = capture.operationId,
                    entityType = "review_card",
                    entityId = cardId,
                    collection = "cards",
                    baseRevision = capture.baseRevision,
                    clientTimestamp = payload.string("updated_at")
                        .ifBlank { payload.string("created_at") }
                        .ifBlank { Instant.now().toString() },
                    payload = payload,
                )
            }
            .toList()
        enqueue(mutations)
    }

    suspend fun resolveAgentProposals(
        artifactSha256: String,
        action: String,
        proposalObjects: Map<String, String>,
        pdfFilenames: Collection<String>,
    ): String {
        require(artifactSha256.matches(SHA256)) { "Invalid document-artifact identity" }
        require(action in setOf("accept", "disregard")) { "Invalid proposal action" }
        val selected = proposalObjects.toSortedMap()
        require(
            selected.isNotEmpty()
                && selected.keys.all(AGENT_PROPOSAL_ID::matches)
                && selected.values.all(SHA256::matches),
        ) {
            "Invalid agent proposal selection"
        }
        val affectedPdfs = pdfFilenames
            .map(String::trim)
            .filter { filename ->
                filename == File(filename).name && filename.endsWith(".pdf", ignoreCase = true)
            }
            .distinct()
            .sorted()
        val operationId = UUID.randomUUID().toString()
        enqueue(
            listOf(
                PendingMutation(
                    operationId = operationId,
                    entityType = AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE,
                    entityId = operationId,
                    collection = "agent-proposals",
                    baseRevision = null,
                    clientTimestamp = Instant.now().toString(),
                    payload = JsonObject().apply {
                        addProperty("artifact_sha256", artifactSha256)
                        addProperty("action", action)
                        add(
                            "pdf_filenames",
                            JsonArray().apply { affectedPdfs.forEach(::add) },
                        )
                        add(
                            "proposals",
                            JsonArray().apply {
                                selected.forEach { (id, objectSha256) ->
                                    add(
                                        JsonObject().apply {
                                            addProperty("id", id)
                                            addProperty("object_sha256", objectSha256)
                                        },
                                    )
                                }
                            },
                        )
                    },
                ),
            ),
        )
        return operationId
    }

    suspend fun undoAgentProposalResolution(resolutionId: String): String {
        require(runCatching { UUID.fromString(resolutionId) }.isSuccess) {
            "Invalid agent proposal resolution ID"
        }
        val operationId = UUID.randomUUID().toString()
        enqueue(
            listOf(
                PendingMutation(
                    operationId = operationId,
                    entityType = AGENT_PROPOSAL_UNDO_ENTITY_TYPE,
                    entityId = resolutionId,
                    collection = "agent-proposals",
                    baseRevision = null,
                    clientTimestamp = Instant.now().toString(),
                    payload = JsonObject().apply { addProperty("resolution_id", resolutionId) },
                ),
            ),
        )
        return operationId
    }

    internal suspend fun updateFromFedora(
        explicit: Boolean = false,
        targetPdfFilename: String? = null,
        operationId: String? = null,
    ): ControlSyncResult {
        require(targetPdfFilename == null || targetPdfFilename.isSafePdfFilename()) {
            "Invalid targeted PDF filename"
        }
        val sourceQueue = SourceEditQueue(appContext, scheduleRequests = false)
        sourceQueue.captureExternalEdits()
        val transferPlan = SyncTransferStateStore(appContext).load()?.takeIf {
            it.operationId == operationId && it.contractVersion == 2 && it.sourceProfile == "reader-v1"
        }
        // A whole-Library Sync stays whole; only a targeted one needs the (costly) scope inputs.
        val effectiveTargetPdfFilename = if (targetPdfFilename == null) null else effectiveSourceSyncTarget(
            requestedTargetPdfFilename = targetPdfFilename,
            hasWorkspaceStyleMutation = sourceQueue.requiresGlobalWorkspaceStyleRefresh(),
            projectMemberPdfFilenames = projectMemberPdfFilenames(),
            preserveGlobalOperationScope = transferPlan != null &&
                transferPlan.targetPdfFilename == null,
        )
        if (effectiveTargetPdfFilename == null) {
            val attemptAt = System.currentTimeMillis()
            updateSyncHealth {
                it.copy(
                    lastSyncAttemptEpochMillis = attemptAt,
                    lastSyncErrorCode = "",
                    lastSyncErrorMessage = "",
                    blockedOperationCount = dao.blockedOperationCount(),
                )
            }
        }
        val config = secretStore.config()
        if (config == null) {
            val sourceAttention = sourceQueue.blockingSourceMutations(effectiveTargetPdfFilename)
            if (explicit) {
                PhoneRenderStatusHistory(appContext).markFedoraStatusRefreshFailed(
                    "The Thoughts server is not configured.",
                )
            }
            if (effectiveTargetPdfFilename == null) {
                recordSyncFailure("configuration", "The Thoughts server is not configured.")
            }
            return ControlSyncResult(
                buildsActive = false,
                artifactPlan = null,
                sourceAttention = sourceAttention,
                scopeTargetPdfFilename = effectiveTargetPdfFilename,
            )
        }
        val progress = transferPlan?.let { SyncTransferProgressReporter(appContext, it) }
        val client = syncClient(
            config,
            operationId,
            progress,
        )
        activeTransferProgress = progress
        return try {
            val controlDiagnostics = SyncDiagnostics(appContext, operationId)
            val traceEventsSupported = operationId != null && controlDiagnostics.measure("control_ready") {
                TRACE_EVENTS_CAPABILITY in serverCapabilities(client)
            }
            val result = RUN_MUTEX.withDiagnosticLock(controlDiagnostics, "control_run_mutex") {
                controlDiagnostics.measureSuspending("control_publish_and_render") {
                    performUpdateFromFedora(
                        client,
                        explicit,
                        effectiveTargetPdfFilename,
                        operationId,
                        traceEventsSupported,
                    )
                }
            }
            result.terminalBuildFailures
                .takeIf(List<TerminalPdfBuildFailure>::isNotEmpty)
                ?.let(::TerminalPdfBuildException)
                ?.let { terminalBuild ->
                    SyncOperationStore(appContext).deferBuildFailure(operationId, terminalBuild)
                }
            var terminalArtifactReplanDeferred = false
            try {
                val terminalArtifactPlan = result.artifactPlan?.takeIf {
                    result.terminalBuildFailures.isNotEmpty() && operationId != null
                }
                if (terminalArtifactPlan != null) {
                    ArtifactSyncCoordinator.publishAwaitingTerminalReplan(
                        appContext,
                        terminalArtifactPlan,
                    )
                    terminalArtifactReplanDeferred = true
                } else {
                    result.artifactPlan?.let { artifact ->
                        // The control plan describes the Library before rendering. Do not
                        // launch a download worker just to discover that it is obsolete.
                        SyncTransferCoordinator.markControlTransferComplete(appContext, operationId)
                        handoffArtifactTransfer(
                            prepare = {
                                if (artifact.operationId != null) {
                                    measureSyncStage("Refreshing the post-build download plan", { message ->
                                        SyncOperationStore(appContext).append(artifact.operationId, SyncOperationStore.STATUS_SYNCING, message)
                                    }) {
                                        SyncTransferCoordinator.refreshForTerminalBuildArtifacts(appContext, artifact)
                                    }
                                }
                            },
                            publish = { ArtifactSyncCoordinator.publish(appContext, artifact) },
                            defer = {
                                ArtifactSyncCoordinator.publishAwaitingTerminalReplan(
                                    appContext, artifact.copy(artifactOnlyRefresh = true),
                                )
                            },
                        )
                    }
                }
            } catch (postBuildError: Exception) {
                if (postBuildError is CancellationException) throw postBuildError
                if (result.terminalBuildFailures.isEmpty()) throw postBuildError
                postBuildError.addSuppressed(TerminalPdfBuildException(result.terminalBuildFailures))
                throw postBuildError
            }
            if (terminalArtifactReplanDeferred) {
                SyncOperationStore(appContext).append(
                    operationId,
                    SyncOperationStore.STATUS_SYNCING,
                    "Healthy artifact refresh queued without retrying the PDF build",
                )
            }
            // Uploads run before the newer document versions arrive: a decision refused because its
            // document changed (another decision on the same source, 11 November) is retired here,
            // in the same Sync, instead of failing this and every later Sync.
            retireFailedProposalDecisionsOfReplacedDocuments()
            if (effectiveTargetPdfFilename == null) {
                updateSyncHealth { current ->
                    controlSyncHealthAfterResult(
                        current = current,
                        completedAtEpochMillis = System.currentTimeMillis(),
                        blockedOperationCount = dao.blockedOperationCount(),
                        result = result,
                    )
                }
            }
            result
        } catch (error: Exception) {
            SyncTransferCoordinator.rollbackUncommittedControlTransfer(appContext, operationId)
            val code = if (error is ServerException) {
                "http_${error.statusCode}"
            } else {
                error.javaClass.simpleName.ifBlank { "sync_failure" }
            }
            if (effectiveTargetPdfFilename == null) {
                recordSyncFailure(code, safeFailureDetail(error))
            }
            if (explicit) {
                val history = PhoneRenderStatusHistory(appContext)
                if (effectiveTargetPdfFilename == null) {
                    history.markFedoraStatusRefreshFailed(safeFailureDetail(error))
                } else {
                    history.markPdfRenderRefreshFailed(
                        effectiveTargetPdfFilename,
                        safeFailureDetail(error),
                    )
                }
            }
            throw error
        } finally {
            // Persist server diagnostics as part of explicit Sync, independently of the status dialog.
            if (explicit && operationId != null) {
                runCatching {
                    replayTraceEvents(client, operationId, SyncOperationStore(appContext))
                }.onFailure { error ->
                    SyncDiagnosticArchive(appContext).append(operationId, "incomplete_server_log", error.message.orEmpty())
                }
            }
            if (activeTransferProgress === progress) activeTransferProgress = null
        }
    }

    /**
     * Produces one directional, content-aware estimate before a metered synchronization.
     * The plan identity changes whenever either the server snapshot or local upload set changes.
     */
    suspend fun estimateSyncTransfer(
        targetPdfFilename: String? = null,
        operationId: String? = null,
        artifactOnly: Boolean = false,
        deliverPendingLibraryRemovals: Boolean = false,
        libraryUploadBudgetBytes: Long = Long.MAX_VALUE,
    ): SyncTransferEstimate {
        require(targetPdfFilename == null || targetPdfFilename.isSafePdfFilename())
        val config = requireNotNull(secretStore.config()) { "The Thoughts server is not configured" }
        val client = syncClient(config, operationId)

        val diagnostics = SyncDiagnostics(appContext, operationId)
        val scopedTargetPdfFilename = diagnostics.measureSuspending("preflight_capture_local_edits") {
            if (artifactOnly) {
                // The post-build refresh keeps the scope its control pass already chose.
                targetPdfFilename
            } else {
                diagnostics.measureSuspending("preflight_capture_review_cards") { captureReviewCards() }
                diagnostics.measureSuspending("preflight_capture_review_events") { captureReviewEvents() }
                val sourceQueue = SourceEditQueue(appContext, scheduleRequests = false)
                diagnostics.measureSuspending("preflight_capture_external_edits") { sourceQueue.captureExternalEdits() }
                if (targetPdfFilename == null) null else diagnostics.measureSuspending("preflight_source_sync_target") {
                    effectiveSourceSyncTarget(
                        requestedTargetPdfFilename = targetPdfFilename,
                        hasWorkspaceStyleMutation = sourceQueue.requiresGlobalWorkspaceStyleRefresh(),
                        projectMemberPdfFilenames = projectMemberPdfFilenames(),
                    )
                }
            }
        }

        // Library changes made on this phone (imports, renames, archive changes, then removals)
        // reach Fedora before it plans which Library state this Sync pulls.
        var libraryChanges = LibraryMutationPublication()
        if (deliverPendingLibraryRemovals) {
            check(
                shouldDeliverPendingLibraryRemovals(
                    explicit = true,
                    targetPdfFilename = scopedTargetPdfFilename,
                    artifactOnly = artifactOnly,
                ),
            ) { "Library removals require an explicit global synchronization" }
            libraryChanges = diagnostics.measure("preflight_library_changes") {
                deliverPendingLibraryMutations(client, operationId, libraryUploadBudgetBytes)
            }
            // Removals keep their place after the changes that wait for mobile-data approval.
            if (libraryChanges.deferredIdentities.isEmpty()) {
                diagnostics.measure("preflight_library_removals") { deliverPendingLibraryRemovals(client) }
            }
        }

        val uploadIdentities = mutableListOf<String>()
        var uploadBytes = 0L
        diagnostics.measureSuspending("preflight_upload_inventory") {
            if (scopedTargetPdfFilename == null && !artifactOnly) {
                val projectUploads = LibraryProjectStore(appContext).pendingUploads()
                projectUploads.forEach { (identity, bytes) -> uploadIdentities += identity; uploadBytes += bytes }
                uploadIdentities += libraryChanges.deferredIdentities
                uploadBytes += libraryChanges.deferredUploadBytes
            }
            dao.retireVoiceOperations()
            val undelivered = if (artifactOnly) emptyList() else dao.undelivered()
            val estimatedOutbox = if (scopedTargetPdfFilename == null) {
                undelivered
            } else {
                val projectionEntities = dao.projections().map { it.toScopedEntity() }
                val targetKeys = documentScopedEntityKeys(
                    scopedTargetPdfFilename,
                    projectionEntities + undelivered.map { it.toScopedEntity() },
                )
                undelivered.filter { it.scopedKey() in targetKeys }
            }
            estimatedOutbox.forEach { row ->
                val bytes = gson.toJson(row.toOperationInput()).toByteArray(StandardCharsets.UTF_8)
                uploadBytes += bytes.size
                uploadIdentities += "operation:${row.operationId}:${sha256(bytes)}"
            }
            if (!artifactOnly) listOf(
                SourceEditQueue.defaultRequestDirectory(),
                PhoneRenderQueue.defaultRequestDirectory(),
            ).forEach { root ->
                root.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
                    .orEmpty()
                    .sortedBy(File::getName)
                    .forEach { file ->
                        if (scopedTargetPdfFilename != null) {
                            val requestTarget = runCatching {
                                JsonParser.parseString(file.readText()).asJsonObject
                                    .safeString("pdf_filename")
                            }.getOrNull()
                            if (requestTarget != scopedTargetPdfFilename) return@forEach
                        }
                        uploadBytes += file.length()
                        uploadIdentities += "request:${file.name}:${fileSha256(file)}"
                    }
            }
        }

        val knownObjects = mutableSetOf<String>()
        val pdfDeltaBases = mutableSetOf<String>()
        diagnostics.measure("preflight_pdf_inventory") {
            val generation = readGenerationManifest(PhoneRenderQueue.defaultCanonicalPdfDirectory())
            val sourceIndex = File(
                PhoneRenderQueue.defaultCanonicalPdfDirectory(),
                SourceLinkedPdfIndex.INDEX_FILENAME,
            )
            generation.sourceIndexSha256.takeIf { digest ->
                digest.matches(SHA256) && sourceIndex.isFile && fileSha256(sourceIndex) == digest
            }?.let(knownObjects::add)
            generation.publications.forEach { (filename, identity) ->
                File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), filename)
                    .takeIf {
                        it.isFile && it.length() == identity.second && fileSha256(it) == identity.first
                    }
                    ?.let {
                        knownObjects += identity.first
                        pdfDeltaBases += identity.first
                    }
            }
        }
        diagnostics.measure("preflight_library_object_inventory") {
            knownObjects += LibraryObjectInventory(appContext.filesDir).verifiedHashes(diagnostics)
            // PDFs added on this phone are not downloaded back once Fedora publishes them.
            knownObjects += verifiedLocallyAddedPdfs().keys
        }

        diagnostics.measureSuspending("preflight_attachment_inventory") {
            if (!artifactOnly) {
                val attachments = dao.undelivered().filter { it.entityType == "logical_comment" }.flatMap {
                    val payload = JsonParser.parseString(it.payloadJson).asJsonObject
                    if (payload.get("deleted")?.asBoolean == true) emptyList()
                    else com.gitlab.mudlej.MjPdfReader.data.annotation.NoteAttachment.read(payload)
                }.distinctBy { it.sha256 }
                attachments.forEach { item ->
                    if (!client.hasObject(item.sha256)) {
                        uploadBytes += item.size
                        uploadIdentities += "attachment:${item.sha256}:${item.size}"
                    }
                }
            }
        }
        val state = dao.state() ?: ThoughtsSyncStateEntity()
        check(SYNC_PLAN_CAPABILITY in diagnostics.measure("preflight_server_ready") { serverCapabilities(client) }) {
            "The Thoughts server does not support the required sync transfer plan v2 contract"
        }
        val allowPdfDelta = !PdfDeltaFallbackStore(appContext).isRejected(operationId)
        val advertisedPdfDeltaBases = if (allowPdfDelta) {
            pdfDeltaBases.sorted().take(20_000)
        } else {
            emptyList()
        }
        val advertisedKnownObjects = (advertisedPdfDeltaBases + knownObjects.sorted())
            .distinct()
            .take(20_000)
        val remote = diagnostics.measure("preflight_server_plan") {
            client.syncPlan(
                SyncPlanRequest(
                    includeVoiceMedia = false,
                    afterSequence = state.serverCursor,
                    projectionBootstrapped = state.projectionBootstrapped,
                    libraryRevision = state.libraryRevision.takeIf(SHA256::matches),
                    knownObjectSha256s = advertisedKnownObjects,
                    pdfDeltaBaseSha256s = advertisedPdfDeltaBases,
                    targetPdfFilename = scopedTargetPdfFilename,
                    capabilities = buildList {
                        add(SOURCE_RESOURCE_CAPABILITY)
                        add("project_reading_v1")
                        add("component_package_v1")
                        add("compiler_review_placement_v3")
                        add("markdown_source_v1")
                        add("markdown_evidence_omitted_v1")
                        if (allowPdfDelta) add(PDF_DELTA_CAPABILITY)
                    },
                ),
            )
        }
        check(
            remote.contractVersion == 2 && remote.sourceProfile == "reader-v1" &&
                !remote.includeVoiceMedia && remote.planId.matches(SHA256) &&
                remote.libraryRevision.matches(SHA256) && remote.latestServerSequence >= 0 &&
                remote.metadataDownloadBytes >= 0 && remote.downloadBytes >= 0
        ) { "The server returned an invalid sync transfer plan v2 envelope" }
        val plannedIdentities = hashSetOf<String>()
        var exactDownloadBytes = remote.metadataDownloadBytes
        remote.objects.forEach { item ->
            check(
                item.sha256.matches(SHA256) && plannedIdentities.add(item.sha256) &&
                    item.sizeBytes >= 0 && item.kind in REMOTE_OBJECT_KINDS &&
                    item.roles.isNotEmpty() && item.roles.all { it in REMOTE_OBJECT_ROLES } &&
                    item.publications.all { it.isSafePdfFilename() || it.matches(Regex("project_[0-9a-f]{24}\\.thoughts-project")) }
            ) { "The server returned an invalid planned object" }
            exactDownloadBytes = Math.addExact(exactDownloadBytes, item.sizeBytes)
        }
        check(exactDownloadBytes == remote.downloadBytes) {
            "The server returned an inexact sync transfer byte total"
        }
        val localIdentity = sha256(
            uploadIdentities.sorted().joinToString("\n").toByteArray(StandardCharsets.UTF_8),
        )
        val planId = sha256(
            "${remote.planId}\n$localIdentity\n$uploadBytes".toByteArray(StandardCharsets.UTF_8),
        )
        return SyncTransferEstimate(
            contractVersion = remote.contractVersion,
            sourceProfile = remote.sourceProfile,
            includeVoiceMedia = remote.includeVoiceMedia,
            planId = planId,
            serverPlanId = remote.planId,
            serverLibraryRevision = remote.libraryRevision,
            targetPdfFilename = scopedTargetPdfFilename,
            remoteObjects = remote.objects.map { item ->
                SyncTransferObject(
                    sha256 = item.sha256,
                    sizeBytes = item.sizeBytes,
                    kind = item.kind,
                    roles = item.roles,
                    publications = item.publications,
                )
            },
            metadataDownloadBytes = remote.metadataDownloadBytes,
            uploadBytes = uploadBytes,
            downloadBytes = remote.downloadBytes,
        )
    }

    private fun deliverPendingLibraryMutations(
        client: SyncClient,
        operationId: String?,
        uploadBudgetBytes: Long,
    ): LibraryMutationPublication {
        val store = LibraryMutationStore(appContext)
        if (store.pending().isEmpty()) return LibraryMutationPublication()
        return LibraryMutationPublisher(
            store = store,
            server = SyncClientLibraryMutationServer(client),
            sha256 = { fileSha256(it) },
            progress = { message ->
                if (operationId != null) {
                    SyncOperationStore(appContext).append(operationId, SyncOperationStore.STATUS_SYNCING, message)
                }
            },
        ).publish(uploadBudgetBytes)
    }

    /** Verified local copies of PDFs added to the Library on this phone, by SHA-256. */
    private fun verifiedLocallyAddedPdfs(): Map<String, File> =
        runCatching { LibraryMutationStore(appContext).storedPdfs() }.getOrDefault(emptyMap())
            .filter { (digest, file) -> runCatching { fileSha256(file) == digest }.getOrDefault(false) }

    private fun deliverPendingLibraryRemovals(client: SyncClient) {
        val store = LibraryRemovalStore(appContext)
        store.pending().forEach { removal ->
            try {
                val result = client.removeLibraryPdf(removal.filename, removal.sha256)
                check(
                    result.filename == removal.filename &&
                        result.sha256.equals(removal.sha256, ignoreCase = true) &&
                        result.status in setOf("removed", "already_removed"),
                ) { "The server returned an invalid Library removal acknowledgement" }
                store.clearFailure(removal.requestId)
            } catch (error: Exception) {
                store.recordFailure(removal.requestId, safeFailureDetail(error))
                throw error
            }
        }
    }

    /** Resolves one durable source-open request without waiting for the full Fedora sync. */
    suspend fun resolveSourceOpenRequest(requestId: String): SourceReceipt {
        require(runCatching { UUID.fromString(requestId) }.isSuccess) {
            "The source request identity is invalid"
        }
        val queue = SourceEditQueue(appContext, scheduleRequests = false)
        queue.receipt(requestId)?.let { return it }
        return SOURCE_REQUEST_MUTEX.withLock {
            resolveSourceOpenOnce(
                existingReceipt = { queue.receipt(requestId) },
                resolve = {
                    val requestFile = File(SourceEditQueue.defaultRequestDirectory(), "$requestId.json")
                    val config = requireNotNull(secretStore.config()) {
                        "The Thoughts server is not configured"
                    }
                    processSourceRequestFile(
                        client = syncClient(config, requestId),
                        queue = queue,
                        requestFile = requestFile,
                        requiredAction = SourceEditQueue.ACTION_OPEN,
                    )
                },
            )
        }
    }

    /** Downloads and verifies every side of an unresolved TeX conflict, then caches it offline. */
    suspend fun loadSourceConflict(conflictId: String): SourceConflictBundle {
        require(conflictId.isNotBlank()) { "The TeX conflict identity is invalid." }
        val queue = SourceEditQueue(appContext, scheduleRequests = false)
        val context = queue.conflictDraftContext(conflictId)
            ?: error("The TeX conflict is no longer pending on this phone.")
        val pending = queue.pendingSourceMutations(context.sourceFile)
            .singleOrNull { it.conflictId == conflictId }
            ?: error("The TeX conflict is no longer pending on this phone.")
        val cached = queue.cachedConflictBundle(conflictId)?.takeIf { bundle ->
            (pending.currentSha256 == null || pending.currentSha256 == bundle.currentSha256) &&
                (pending.incomingSha256 == null || pending.incomingSha256 == bundle.incomingSha256) &&
                (pending.mergedObjectSha256 == null ||
                    pending.mergedObjectSha256 == bundle.mergedObjectSha256)
        }
        val config = secretStore.config() ?: return requireNotNull(cached) {
            "The Thoughts server is not configured and this conflict is not cached offline."
        }
        return runCatching {
            val client = syncClient(config)
            val record = client.conflicts().singleOrNull { it.id == conflictId }
                ?: error("The server no longer reports this TeX conflict as unresolved.")
            check(record.status == "unresolved" && record.path == context.sourceFile) {
                "The server returned a different TeX conflict."
            }
            pending.currentSha256?.let { check(it == record.currentSha256) }
            pending.incomingSha256?.let { check(it == record.incomingSha256) }
            val mergedSha256 = requireNotNull(record.mergedObjectSha256) {
                "The server did not preserve merged TeX conflict contents."
            }
            pending.mergedObjectSha256?.let { check(it == mergedSha256) }
            fun verifiedContents(digest: String, label: String): String {
                check(digest.matches(SHA256)) { "The server returned an invalid $label identity." }
                val bytes = client.downloadObject(digest)
                check(bytes.size <= SourceEditQueue.MAX_SOURCE_BYTES) {
                    "The $label TeX source exceeds the supported size."
                }
                check(sha256(bytes) == digest) { "The $label TeX source failed SHA-256 verification." }
                return bytes.toString(StandardCharsets.UTF_8)
            }
            val currentContents = verifiedContents(record.currentSha256, "server-current")
            val incomingContents = verifiedContents(record.incomingSha256, "phone-incoming")
            val mergedContents = verifiedContents(mergedSha256, "merged")
            val markerCount = Regex("(?m)^<<<<<<<(?: .*)?$").findAll(mergedContents).count()
            val bundle = SourceConflictBundle(
                conflictId = conflictId,
                requestId = context.requestId,
                sourceFile = context.sourceFile,
                pdfFilename = context.pdfFilename,
                sourceLine = context.sourceLine,
                resolutionId = context.resolutionId,
                conflictCount = pending.conflictCount ?: record.conflictCount
                    ?: markerCount.coerceAtLeast(1),
                expectedRevision = requireNotNull(pending.expectedRevision ?: record.expectedRevision) {
                    "The local conflict is missing the server's guarded source revision."
                },
                currentSha256 = record.currentSha256,
                incomingSha256 = record.incomingSha256,
                mergedObjectSha256 = mergedSha256,
                currentModifiedAt = record.currentModifiedAt ?: pending.currentModifiedAt,
                incomingModifiedAt = record.incomingModifiedAt ?: pending.incomingModifiedAt,
                currentContents = currentContents,
                incomingContents = incomingContents,
                mergedContents = mergedContents,
            )
            queue.cacheConflictBundle(bundle)
            bundle
        }.getOrElse { error ->
            if (isRetryableSyncFailure(error)) cached ?: throw error else throw error
        }
    }

    /** Discards only the exact unresolved Fedora conflict and then removes its local draft. */
    suspend fun discardSourceConflict(conflictId: String, expectedSha256: String): Boolean {
        require(conflictId.isNotBlank()) { "The TeX conflict identity is invalid." }
        require(expectedSha256.matches(SHA256)) { "The server's source identity is invalid." }
        val queue = SourceEditQueue(appContext, scheduleRequests = false)
        val pending = queue.pendingSourceMutations().singleOrNull {
            it.conflictId == conflictId &&
                (it.currentSha256 == null || it.currentSha256 == expectedSha256)
        } ?: error("The guarded TeX conflict is no longer pending on this phone.")
        val config = requireNotNull(secretStore.config()) { "The Thoughts server is not configured." }
        val client = syncClient(config)
        val remote = client.conflicts().singleOrNull { it.id == conflictId }
            ?: client.conflicts("discarded").singleOrNull { it.id == conflictId }
            ?: error("The server no longer reports this guarded TeX conflict.")
        val expectedRevision = requireNotNull(pending.expectedRevision ?: remote.expectedRevision) {
            "The guarded TeX conflict has no server source revision."
        }
        check(
            remote.status in setOf("unresolved", "discarded") && remote.path == pending.sourceFile &&
                remote.currentSha256 == expectedSha256 &&
                (remote.expectedRevision == null || remote.expectedRevision == expectedRevision)
        ) { "The server's canonical TeX source advanced; the conflict was not discarded." }
        val discarded = if (remote.status == "discarded") remote else {
            client.discardConflict(
                conflictId,
                ConflictDiscardRequest(expectedRevision, expectedSha256),
            )
        }
        check(
            discarded.id == conflictId && discarded.status == "discarded" &&
                discarded.path == pending.sourceFile && discarded.currentSha256 == expectedSha256
        ) { "The server did not confirm the guarded TeX conflict discard." }
        return queue.discardConfirmedConflict(conflictId, expectedSha256)
    }

    /**
     * Refreshes a superseded download plan without submitting sources or render requests.
     * The resulting download retains its mobile-data threshold and approval policy.
     */
    internal suspend fun refreshTerminalArtifactPlan(
        supersededPlan: ArtifactSyncPlan,
    ): ArtifactSyncPlan? {
        require(supersededPlan.postTerminalBuildRefresh || supersededPlan.artifactOnlyRefresh)
        val refreshCount = nextArtifactPlanRefreshCount(supersededPlan.planRefreshCount)
        val operationId = requireNotNull(supersededPlan.operationId)
        val config = requireNotNull(secretStore.config()) { "The Thoughts server is not configured" }
        val client = syncClient(config, operationId)
        var buildsActive = false
        val candidate = RUN_MUTEX.withLock {
            // Fetch publication-derived review locations without uploading local edits.
            if (!supersededPlan.postTerminalBuildRefresh) {
                SYNC_MUTEX.withLock { pullChanges(client) }
            }
            val knownBuilds = SyncOperationStore(appContext).operation(operationId)?.buildProgress.orEmpty()
            val currentBuilds = knownBuilds.map { client.build(it.buildId) }
            buildsActive = currentBuilds.any { it.status in setOf("queued", "running") }
            val target = supersededPlan.targetPdfFilenames.singleOrNull()
            if (target == null) {
                val snapshot = requireNotNull(client.library(null)) {
                    "The server did not return the Library frontier"
                }
                recordMetadataPayload(snapshot)
                SYNC_MUTEX.withLock {
                    discoverArtifactPlan(
                        snapshot = snapshot,
                        explicit = supersededPlan.explicitRequest,
                        targetPdfFilename = null,
                        operationId = operationId,
                        buildsActive = buildsActive,
                        artifactOnlyRefresh = true,
                        postTerminalBuildRefresh = supersededPlan.postTerminalBuildRefresh,
                    )
                }
            } else {
                val snapshot = requireNotNull(client.documentSync(target).snapshot) {
                    "The server did not return the document frontier"
                }
                recordMetadataPayload(snapshot)
                SYNC_MUTEX.withLock {
                    discoverTargetArtifactPlan(
                        publication = snapshot.publication,
                        explicit = supersededPlan.explicitRequest,
                        operationId = operationId,
                        postTerminalBuildRefresh = supersededPlan.postTerminalBuildRefresh,
                    )
                }
            }
        }
        if (candidate == null) {
            ArtifactSyncStateStore(appContext).clear(supersededPlan.planId)
            SyncTransferStateStore(appContext).clearForOperation(operationId)
            ArtifactSyncCoordinator.broadcast(appContext, null, showPrompt = false)
            if (!supersededPlan.postTerminalBuildRefresh) {
                if (buildsActive) {
                    ThoughtsSyncScheduler.enqueueExplicitContinuation(
                        appContext, supersededPlan.targetPdfFilenames.singleOrNull(), operationId,
                        building = true,
                    )
                } else {
                    val sourceAttention = SourceEditQueue(appContext, scheduleRequests = false)
                        .blockingSourceMutations(supersededPlan.targetPdfFilenames.singleOrNull())
                    val operations = SyncOperationStore(appContext)
                    val installationProblems = OfflineSourceBundleStore(appContext).materializationProblems()
                    if (sourceAttention.isNotEmpty() || installationProblems.isNotEmpty()) {
                        operations.attention(operationId, sourceAttentionEvent(sourceAttention, installationProblems))
                    } else {
                        operations.append(operationId, SyncOperationStore.STATUS_SYNCED, "Completed")
                    }
                }
            }
            return null
        }
        val refreshed = candidate.copy(
            artifactOnlyRefresh = true, buildsActive = buildsActive,
            planRefreshCount = refreshCount,
        )
        measureSyncStage("Refreshing the post-build download plan", { message ->
            SyncOperationStore(appContext).append(refreshed.operationId, SyncOperationStore.STATUS_SYNCING, message)
        }) {
            SyncTransferCoordinator.refreshForTerminalBuildArtifacts(appContext, refreshed)
        }
        ArtifactSyncCoordinator.publish(appContext, refreshed, forceReplaceWork = true)
        return refreshed
    }

    suspend fun syncArtifacts(planId: String): Boolean {
        val diagnostics = SyncDiagnostics(appContext, ArtifactSyncStateStore(appContext).load()
            ?.takeIf { it.planId == planId }?.operationId)
        return RUN_MUTEX.withDiagnosticLock(diagnostics, "artifact_run_mutex") {
        val stateStore = ArtifactSyncStateStore(appContext)
        val plan = stateStore.load()?.takeIf { it.planId == planId }
            ?: throw ArtifactPlanSupersededException()
        val config = requireNotNull(secretStore.config()) { "The Thoughts server is not configured" }
        val transferPlan = SyncTransferStateStore(appContext).load()?.takeIf {
            it.operationId == plan.operationId && it.covers(plan) &&
                it.contractVersion == 2 && it.sourceProfile == "reader-v1"
        } ?: throw ArtifactPlanSupersededException()
        val client = syncClient(
            config,
            plan.operationId,
            SyncTransferProgressReporter(appContext, transferPlan),
        )
        var newlyActivatedPdfs = emptyMap<String, String>()
        val buildsStillActive = try {
            SYNC_MUTEX.withDiagnosticLock(diagnostics, "artifact_sync_mutex") {
                val activationIndex = SourceLinkedPdfIndex()
                val previouslyActivated = activationIndex.snapshot().activatedPdfSha256ByFilename
                if (plan.targetPdfFilenames.isEmpty()) {
                    if (!plan.postTerminalBuildRefresh && !plan.artifactOnlyRefresh) {
                        performDeltaSync(
                            client,

                            transferPlan = transferPlan,
                        )
                    }
                    syncLibraryAndSources(
                        client,
                        expectedPlan = plan,
                        transferPlan = transferPlan,
                    )
                } else {
                    syncTargetDocumentArtifact(client, plan, transferPlan)
                }
                if (plan.targetPdfFilenames.isEmpty()) LibraryHeadStateStore(appContext).load()
                    ?.takeIf { it.libraryRevision == plan.libraryRevision }
                    ?.let { reconcileSupersededRenderFailures(it) }
                // Clear the completed plan before another control pass can discover
                // and republish the generation that this transfer just activated.
                val latestBuildActivity = stateStore.load()
                    ?.takeIf { it.planId == planId }
                    ?.buildsActive
                    ?: plan.buildsActive
                val activatedPdfSha256ByFilename = diagnostics.measure("reload_activated_pdf_index") {
                    activationIndex.invalidate()
                    activationIndex.snapshot().activatedPdfSha256ByFilename
                }
                refreshReviewProjections(activatedPdfSha256ByFilename, diagnostics)
                newlyActivatedPdfs = activatedPdfUpdates(
                    previouslyActivated,
                    activatedPdfSha256ByFilename,
                )
                diagnostics.measure("clear_artifact_plan") { stateStore.clear(planId) }
                latestBuildActivity
            }
        } catch (error: ServerException) {
            if (error.statusCode == 409) throw ArtifactPlanSupersededException()
            throw error
        }
        diagnostics.measure("broadcast_artifact_activation") {
            ArtifactSyncCoordinator.broadcast(appContext, null, showPrompt = false)
            ArtifactSyncCoordinator.broadcastActivatedPdfs(appContext, newlyActivatedPdfs)
            PdfDeltaFallbackStore(appContext).clear(plan.operationId)
        }
        // Scheduled here, not at completion: finishing the operation cancels its workers.
        if (newlyActivatedPdfs.isNotEmpty()) {
            runCatching { ReviewPlacementFollowUpWorker.enqueueIfWaiting(appContext) }
        }
        buildsStillActive
        }
    }

    /**
     * Publishes and pulls operation-log changes without touching source saves, render requests,
     * PDFs, or the offline source bundle artifacts.
     *
     * Reports whether the configured server supports build-free annotation anchoring and
     * whether all jobs accepted by this pass reached a terminal status during the bounded poll.
     */
    suspend fun syncDeltas(targetPdfFilename: String? = null): DeltaSyncResult {
        require(targetPdfFilename == null || targetPdfFilename.isSafePdfFilename())
        val attemptAt = System.currentTimeMillis()
        updateSyncHealth {
            it.copy(
                lastSyncAttemptEpochMillis = attemptAt,
                lastSyncErrorCode = "",
                lastSyncErrorMessage = "",
                blockedOperationCount = dao.blockedOperationCount(),
            )
        }
        val config = secretStore.config()
        if (config == null) {
            recordSyncFailure("configuration", "The Thoughts server is not configured.")
            return DeltaSyncResult(
                buildFreeAnchoringSupported = false,
                anchorJobsSettled = false,
            )
        }
        val client = syncClient(config)
        return try {
            val buildFreeAnchoringSupported = ANCHOR_CAPABILITY in serverCapabilities(client)
            val pass = SYNC_MUTEX.withLock {
                performDeltaSync(
                    client,
                    includeSourceRequests = false,
                    targetPdfFilename = targetPdfFilename,
                )
            }
            val anchorsSettled = buildFreeAnchoringSupported &&
                awaitAnchorJobs(client, pass.anchorJobs)
            SYNC_MUTEX.withLock { pullChanges(client) }
            val sourceAttention = SourceEditQueue(appContext, scheduleRequests = false)
                .blockingSourceMutations(targetPdfFilename)
            val completedAt = System.currentTimeMillis()
            updateSyncHealth { current ->
                deltaSyncHealthAfterSourceCheck(
                    current = current,
                    completedAtEpochMillis = completedAt,
                    blockedOperationCount = dao.blockedOperationCount(),
                    sourceAttention = sourceAttention,
                )
            }
            DeltaSyncResult(
                buildFreeAnchoringSupported = buildFreeAnchoringSupported,
                anchorJobsSettled = anchorsSettled,
            )
        } catch (error: Exception) {
            val code = if (error is ServerException) {
                "http_${error.statusCode}"
            } else {
                error.javaClass.simpleName.ifBlank { "sync_failure" }
            }
            recordSyncFailure(code, safeFailureDetail(error))
            throw error
        }
    }

    private suspend fun performUpdateFromFedora(
        client: SyncClient,
        explicit: Boolean,
        targetPdfFilename: String?,
        operationId: String?,
        traceEventsSupported: Boolean,
    ): ControlSyncResult {
        val diagnostics = SyncDiagnostics(appContext, operationId)
        activeSyncTraceId = operationId
        pdfKeepingAcceptDeliveredAtMillis = null
        pdfChangingDeliveredThisSync = false
        if (targetPdfFilename != null) {
            return SYNC_MUTEX.withLock {
                performTargetedUpdateFromFedora(
                    client,
                    targetPdfFilename,
                    explicit,
                    operationId,
                    traceEventsSupported,
                )
            }
        }
        // Publish every local change before asking Fedora whether the resulting source
        // frontier needs a PDF update. This keeps Sync as the single user action.
        val pass = SYNC_MUTEX.withLock {
            if (explicit) diagnostics.measure("control_library_projects") {
                LibraryProjectStore(appContext).synchronize(client)
            }
            diagnostics.measureSuspending("control_delta_sync") {
                performDeltaSync(
                    client,
                    includeSourceRequests = explicit,
                    targetPdfFilename = targetPdfFilename,
                    operationId = operationId,
                )
            }
        }
        if (!sourceMutationsAllowRender(pass.sourceAttention)) {
            return ControlSyncResult(
                buildsActive = false,
                artifactPlan = null,
                sourceAttention = pass.sourceAttention,
                scopeTargetPdfFilename = null,
            )
        }
        val refreshed = refreshPublicationsAfterRenderSync(
            explicit = explicit,
            syncRequestedRenders = {
                diagnostics.measureSuspending("control_requested_renders") {
                    syncRenderRequests(
                        client,
                        targetPdfFilename = targetPdfFilename,
                        operationId = operationId,
                        traceEventsSupported = traceEventsSupported,
                    )
                }
            },
            syncConditionalRender = {
                diagnostics.measure("control_conditional_render_check") {
                    syncConditionalRenderCheck(
                        client,
                        targetPdfFilename,
                        operationId,
                        traceEventsSupported,
                    )
                }
            },
            refreshPublicationState = {
                val snapshot = diagnostics.measureSuspending("control_refresh_publication_state") {
                    coroutineScope {
                        val library = async(Dispatchers.IO) { fetchLibrarySnapshot(client) }
                        val status = async(Dispatchers.IO) {
                            refreshFedoraStatusSnapshot(client, targetPdfFilename)
                        }
                        library.await().also { status.await() }
                    }
                }
                val cursor = diagnostics.measureSuspending("control_read_final_cursor") {
                    (dao.state() ?: ThoughtsSyncStateEntity()).serverCursor
                }
                if (
                    pass.uploadedOperations ||
                    shouldPullFinalChanges(snapshot?.latestServerSequence, cursor)
                ) {
                    SYNC_MUTEX.withDiagnosticLock(diagnostics, "control_final_pull_mutex") {
                        diagnostics.measureSuspending("control_final_pull") { pullChanges(client) }
                    }
                }
                snapshot
            },
        )
        val renderSync = refreshed.renderSync
        val snapshot = refreshed.publicationState
        val artifactPlan = SYNC_MUTEX.withDiagnosticLock(diagnostics, "control_artifact_plan_mutex") {
            diagnostics.measureSuspending("control_discover_artifact_plan") {
                discoverArtifactPlan(
                    snapshot, explicit, targetPdfFilename, operationId,
                    buildsActive = renderSync.active,
                    postTerminalBuildRefresh = renderSync.terminalFailures.isNotEmpty(),
                    diagnostics = diagnostics,
                )
            }
        }
        return ControlSyncResult(
            buildsActive = renderSync.active,
            artifactPlan = artifactPlan,
            buildProgress = renderSync.builds,
            terminalBuildFailures = renderSync.terminalFailures,
            scopeTargetPdfFilename = null,
        )
    }

    /**
     * Synchronizes one Library member without reading or advancing the global change cursor.
     * The server snapshot is authoritative only for the returned document relationship slice.
     */
    private suspend fun performTargetedUpdateFromFedora(
        client: SyncClient,
        targetPdfFilename: String,
        explicit: Boolean,
        operationId: String?,
        traceEventsSupported: Boolean,
    ): ControlSyncResult {
        migrateDefaultFolderIfNeeded()
        migrateSourceFolderNamesIfNeeded()
        val sourceQueue = SourceEditQueue(appContext, scheduleRequests = false)
        val activeSourceRequestIds = prepareSourceMutationsForDeviceSequenceReconciliation(
            sourceQueue,
            targetPdfFilename,
            retryFailures = explicit,
        )
        reconcileDeviceSequenceState(client, activeSourceRequestIds)
        captureReviewCards()
        captureReviewEvents()
        val pass = drainOutbox(client, targetPdfFilename, retryProposalFailures = explicit)
        requireReviewSourceTransactionsDelivered()
        syncSourceRequests(client, targetPdfFilename, requestBuilds = false)
        sourceQueue.recoverDeliveredRequests()
        val sourceAttention = sourceQueue.blockingSourceMutations(targetPdfFilename)
        if (!sourceMutationsAllowRender(sourceAttention)) {
            return ControlSyncResult(
                buildsActive = false,
                artifactPlan = null,
                sourceAttention = sourceAttention,
                scopeTargetPdfFilename = targetPdfFilename,
            )
        }
        // A targeted Build is persisted in PhoneRenderQueue before this sync starts.
        // Submit or poll that request after its source/comment inputs have reached Fedora,
        // then preserve the active-build result so the worker keeps polling the real job.
        val stateStore = DocumentSyncStateStore(appContext)
        val renderHistory = PhoneRenderStatusHistory(appContext)
        val refreshed = refreshPublicationsAfterRenderSync(
            explicit = explicit,
            syncRequestedRenders = {
                syncRenderRequests(
                    client,
                    targetPdfFilename = targetPdfFilename,
                    operationId = operationId,
                    traceEventsSupported = traceEventsSupported,
                )
            },
            syncConditionalRender = {
                syncConditionalRenderCheck(
                    client,
                    targetPdfFilename,
                    operationId,
                    traceEventsSupported,
                )
            },
            refreshPublicationState = {
                client.documentSync(
                    targetPdfFilename,
                    stateStore.etag(targetPdfFilename).takeUnless { pass.uploadedOperations },
                )
            },
        )
        val combinedRenderSync = refreshed.renderSync
        val call = refreshed.publicationState
        call.snapshot?.let(::recordMetadataPayload)
        val snapshot = call.snapshot
        if (snapshot != null) {
            check(snapshot.target == targetPdfFilename) { "The server returned the wrong document snapshot" }
            applyTargetProjectionSnapshot(snapshot, client)
            renderHistory.saveDocumentSyncStatus(
                targetPdfFilename,
                snapshot.renderStatus,
                snapshot.publication,
            )
        }
        val documentRenderStatus = if (snapshot != null) {
            snapshot.renderStatus?.string("status")
        } else {
            renderHistory.pdfRenderStatus(targetPdfFilename)?.status
        }
        // A build may have been started by Thoughts Desktop rather than by this phone.
        // Keep polling the target until that server-reported build also reaches a terminal state.
        val activeBuilds = targetedBuildsActive(combinedRenderSync.active, documentRenderStatus)
        val remotelyObservedBuild = snapshot?.renderStatus
            ?.getAsJsonObject("build")
            ?.let { build -> runCatching { gson.fromJson(build, BuildRecord::class.java) }.getOrNull() }
            ?.let(::syncBuildProgress)
        val buildProgress = (combinedRenderSync.builds + listOfNotNull(remotelyObservedBuild))
            .distinctBy(SyncBuildProgress::buildId)
        val artifactPlan = if (snapshot != null) {
            discoverTargetArtifactPlan(
                snapshot.publication,
                explicit,
                operationId,
                postTerminalBuildRefresh = combinedRenderSync.terminalFailures.isNotEmpty(),
            )
        } else {
            LibraryHeadStateStore(appContext).load()?.let { head ->
                head.publications[targetPdfFilename]?.let { publication ->
                    discoverTargetArtifactPlan(
                        filename = publication.filename,
                        sha256 = publication.sha256,
                        sizeBytes = publication.sizeBytes,
                        sourceArtifactSha256Value = publication.sourceArtifactSha256,
                        generationId = head.libraryRevision,
                        explicit = explicit,
                        operationId = operationId,
                        postTerminalBuildRefresh = combinedRenderSync.terminalFailures.isNotEmpty(),
                    )
                }
            }
        }
        // LibraryHeadState records activated reader artifacts, not merely server metadata.
        // Publishing it before planning makes an unchanged PDF mask a changed source artifact.
        if (snapshot != null && artifactPlan == null) {
            LibraryHeadStateStore(appContext).publishDocument(snapshot.publication)
            call.etag.takeIf { it.matches(SHA256) }
                ?.let { stateStore.save(targetPdfFilename, it) }
        }
        return ControlSyncResult(
            buildsActive = activeBuilds,
            artifactPlan = artifactPlan,
            buildProgress = buildProgress,
            terminalBuildFailures = combinedRenderSync.terminalFailures,
            scopeTargetPdfFilename = targetPdfFilename,
        )
    }

    private fun refreshFedoraStatusSnapshot(client: SyncClient, targetPdfFilename: String?) {
        runCatching {
            val call = client.renderState(targetPdfFilename ?: "all")
            check(
                PhoneRenderStatusHistory(appContext).replaceFedoraStatusSnapshot(call.state),
            ) { "The server returned an invalid global PDF status snapshot." }
            Log.i(
                "ThoughtsRenderVerify",
                "status=${call.state.status} dnsMs=${call.timings.dnsMs} " +
                    "connectMs=${call.timings.connectMs} tlsMs=${call.timings.tlsMs} " +
                    "serverMs=${call.timings.serverMs} totalMs=${call.timings.totalMs}",
            )
        }.onFailure { error ->
            PhoneRenderStatusHistory(appContext).markFedoraStatusRefreshFailed(
                safeFailureDetail(error),
            )
            Log.w("ThoughtsRenderVerify", "Fedora status snapshot was not refreshed", error)
        }
    }

    private suspend fun performDeltaSync(
        client: SyncClient,
        includeSourceRequests: Boolean = false,
        targetPdfFilename: String? = null,
        transferPlan: SyncTransferPlan? = null,
        operationId: String? = null,
    ): DeltaPassResult {
        val diagnostics = operationId?.let { SyncDiagnostics(appContext, it) }
        suspend fun <T> stage(name: String, block: suspend () -> T): T =
            diagnostics?.measureSuspending(name, block) ?: block()
        // Before anything reads a source path: Fedora may have renamed the default folders.
        stage("control_default_folder_migration") { migrateDefaultFolderIfNeeded() }
        // Likewise Fedora may have renamed its connected source folders (source-folder-names).
        stage("control_source_folder_names_migration") { migrateSourceFolderNamesIfNeeded() }
        val sourceQueue = if (includeSourceRequests) {
            SourceEditQueue(appContext, scheduleRequests = false)
        } else {
            null
        }
        val activeSourceRequestIds = sourceQueue?.let { queue ->
            stage("control_prepare_source_requests") {
                prepareSourceMutationsForDeviceSequenceReconciliation(
                    queue,
                    targetPdfFilename,
                    retryFailures = true,
                )
            }
        }
        stage("control_reconcile_device_sequence") {
            reconcileDeviceSequenceState(client, activeSourceRequestIds)
        }
        if (!(dao.state() ?: ThoughtsSyncStateEntity()).projectionBootstrapped) {
            stage("control_bootstrap_projections") { bootstrapProjections(client, transferPlan) }
        }
        stage("control_pull_changes") { pullChanges(client, transferPlan, diagnostics) }
        stage("control_capture_review_cards") { captureReviewCards() }
        stage("control_capture_review_events") { captureReviewEvents() }
        val pass = stage("control_drain_outbox") {
            drainOutbox(client, targetPdfFilename, retryProposalFailures = includeSourceRequests)
        }
        stage("control_verify_review_delivery") { requireReviewSourceTransactionsDelivered() }
        var sourceAttention = emptyList<PendingSourceMutation>()
        if (sourceQueue != null) {
            stage("control_sync_source_requests") { syncSourceRequests(client, targetPdfFilename) }
            sourceQueue.recoverDeliveredRequests()
            sourceAttention = sourceQueue.blockingSourceMutations(targetPdfFilename)
        }
        return pass.copy(sourceAttention = sourceAttention)
    }

    private suspend fun reconcileDeviceSequenceState(
        client: SyncClient,
        preparedSourceRequestIds: Set<String>? = null,
    ) {
        val remote = client.deviceSequence()
        check(remote.nextDeviceSequence == remote.latestDeviceSequence + 1) {
            "The server returned an inconsistent device sequence frontier"
        }
        val activeSourceRequestIds = preparedSourceRequestIds ?: SourceEditQueue(
            appContext,
            scheduleRequests = false,
        ).undeliveredRequestIds()
        database.withTransaction {
            val current = dao.state() ?: ThoughtsSyncStateEntity()
            val allOutbox = dao.allOutbox()
            val outboxById = allOutbox.associateBy(ThoughtsOutboxEntity::operationId)
            val allSourceRequests = dao.sourceRequests()
            val plan = planDeviceSequenceReconciliation(
                remoteNextDeviceSequence = remote.nextDeviceSequence,
                localNextDeviceSequence = current.nextDeviceSequence,
                allOutbox = allOutbox,
                allSourceRequests = allSourceRequests,
                activeSourceRequestIds = activeSourceRequestIds,
            )
            plan.assignments.forEachIndexed { index, assignment ->
                val temporarySequence = Long.MIN_VALUE + index
                val updated = when (assignment.kind) {
                    DeviceSequenceReservationKind.OUTBOX -> dao.updateOutboxDeviceSequence(
                        assignment.id,
                        temporarySequence,
                    )
                    DeviceSequenceReservationKind.SOURCE_REQUEST -> dao.updateSourceRequestDeviceSequence(
                        assignment.id,
                        temporarySequence,
                    )
                }
                check(updated == 1) { "A local device sequence reservation disappeared" }
            }
            plan.assignments.forEach { assignment ->
                val updated = when (assignment.kind) {
                    DeviceSequenceReservationKind.OUTBOX -> {
                        val reconciled = requireNotNull(outboxById[assignment.id])
                            .afterDeviceSequenceReconciliation(assignment.deviceSequence)
                        dao.finishOutboxDeviceSequenceReconciliation(
                            operationId = assignment.id,
                            deviceSequence = assignment.deviceSequence,
                            deliveryState = reconciled.deliveryState,
                            failureCode = reconciled.failureCode,
                            failureDetail = reconciled.failureDetail,
                        )
                    }
                    DeviceSequenceReservationKind.SOURCE_REQUEST -> dao.updateSourceRequestDeviceSequence(
                        assignment.id,
                        assignment.deviceSequence,
                    )
                }
                check(updated == 1) { "A local device sequence reservation disappeared" }
            }
            dao.putState(
                current.copy(
                    nextDeviceSequence = plan.nextDeviceSequence,
                    blockedOperationCount = dao.blockedOperationCount(),
                ),
            )
        }
    }

    /**
     * Moves this phone's default folders and stored source paths to `default` (see
     * [DefaultFolderMigration]) once Fedora names a default folder so, or resumes an interrupted
     * run. Callers hold [SYNC_MUTEX]. A [DefaultFolderMigration.Problem] fails the Sync visibly.
     */
    private suspend fun migrateDefaultFolderIfNeeded(snapshot: LibrarySnapshot? = null) {
        val migration = DefaultFolderMigration.forContext(appContext, database)
        if (migration.done()) return
        val fedoraUsesDefault = migration.interrupted() ||
            DefaultFolderMigration.fedoraUsesDefault(LibraryProjectStore(appContext).storedCatalogue()) ||
            runCatching {
                snapshot?.publications?.any { publication ->
                    com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.containsCanonical(publication.project) ||
                        com.gitlab.mudlej.MjPdfReader.data.source.DefaultFolder.containsCanonical(publication.metadata.toString())
                } == true
            }.getOrDefault(false)
        if (!fedoraUsesDefault) return
        val report = withContext(Dispatchers.IO) { migration.run() }
        Log.i("ThoughtsSync", "Default folders renamed on this phone: $report")
        // Caches and in-memory views computed from the old paths read the new ones from now on.
        runCatching { com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore(appContext).annotationIndex.invalidate() }
        com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.invalidate()
        com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentChanges.changes.tryEmit(Unit)
        PendingChangesSignal.notifyChanged()
    }

    /**
     * Moves this phone's connected source folders and stored paths to the names Fedora gave them
     * (see [SourceFolderNamesMigration]) for every entry of Fedora's map M this phone has not
     * applied yet, or resumes an interrupted run. M comes with the Project catalogue; when a
     * Library [snapshot] names a folder neither the stored catalogue nor M knows (Fedora renamed
     * its folders since the last explicit Sync), M is fetched (once per such folder and process:
     * folders no manifest declares stay unknown).
     * Callers hold [SYNC_MUTEX]. A [SourceFolderNamesMigration.Problem] fails the Sync visibly.
     */
    private suspend fun migrateSourceFolderNamesIfNeeded(client: SyncClient? = null, snapshot: LibrarySnapshot? = null) {
        val store = LibraryProjectStore(appContext)
        val migration = SourceFolderNamesMigration.forContext(appContext, database)
        var renames = store.storedSourceFolderRenames()
        val unknown = if (client != null && snapshot != null) {
            unknownSnapshotFolders(snapshot, store.storedCatalogue(), renames) - SOURCE_FOLDER_RENAMES_CHECKED
        } else emptySet()
        if (client != null && unknown.isNotEmpty()) {
            SOURCE_FOLDER_RENAMES_CHECKED += unknown
            renames = withContext(Dispatchers.IO) {
                runCatching { store.refreshSourceFolderRenames(client) }
                    .onFailure { Log.w("ThoughtsSync", "Fedora's source folder names could not be read", it) }
                    .getOrDefault(renames)
            }
        }
        if (!migration.needed(renames)) return
        val report = withContext(Dispatchers.IO) { migration.run(renames) }
        Log.i("ThoughtsSync", "Source folders renamed on this phone: $report")
        // Caches and in-memory views computed from the old paths read the new ones from now on.
        runCatching { com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore(appContext).annotationIndex.invalidate() }
        com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.invalidate()
        com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentChanges.changes.tryEmit(Unit)
        PendingChangesSignal.notifyChanged()
    }

    /**
     * Activating the PDF generation moves readers off the generation pinned during Sync. The source
     * scan started when the new sources were installed read new working copies against the pinned
     * PDFs, found no source for every changed document and marked its project incomplete, which
     * left all of that project's source-linked cards "Source location unavailable" until the next
     * source edit or restart. Scan again now that working copies and PDFs belong together.
     */
    private fun sourceGenerationActivated() {
        com.gitlab.mudlej.MjPdfReader.data.source.SourceCommentSnapshot.invalidate()
    }

    private suspend fun awaitAnchorJobs(client: SyncClient, jobIds: Set<String>): Boolean {
        if (jobIds.isEmpty()) return true
        val pending = jobIds.toMutableSet()
        val deadline = System.currentTimeMillis() + ANCHOR_POLL_MILLIS
        while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
            pending.removeAll { jobId ->
                client.anchorJob(jobId).status in TERMINAL_ANCHOR_STATUSES
            }
            if (pending.isNotEmpty()) delay(ANCHOR_POLL_INTERVAL_MILLIS)
        }
        return pending.isEmpty()
    }

    private suspend fun syncLibraryAndSources(
        client: SyncClient,
        expectedPlan: ArtifactSyncPlan? = null,
        transferPlan: SyncTransferPlan? = null,
    ) {
        val diagnostics = SyncDiagnostics(appContext, transferPlan?.operationId)
        val before = dao.state() ?: ThoughtsSyncStateEntity()
        val localGenerationCurrent = SourceLinkedPdfIndex().snapshot().generationCurrent
        val libraryChanges = LibraryMutationStore(appContext)
        val deliveredBeforeFetch = runCatching { libraryChanges.deliveredRequestIds() }.getOrDefault(emptySet())
        var snapshot = diagnostics.measure("fetch_library_snapshot") {
            client.library(before.libraryRevision.takeIf { localGenerationCurrent })
        }
        if (expectedPlan != null) {
            val actualRevision = snapshot?.libraryRevision ?: before.libraryRevision
            val actualBundle = if (snapshot != null) {
                snapshot.sourceArtifactSetSha256()
            } else {
                before.sourceBundleSha256
            }
            if (
                actualRevision != expectedPlan.libraryRevision ||
                actualBundle != expectedPlan.sourceBundleSha256
            ) {
                throw ArtifactPlanSupersededException()
            }
        }
        if (transferPlan != null) {
            val actualRevision = snapshot?.libraryRevision ?: before.libraryRevision
            if (actualRevision != transferPlan.serverLibraryRevision) {
                throw ArtifactPlanSupersededException()
            }
        }
        var installedAndPrunedBundleId: String? = null
        if (snapshot != null) {
            // A bundle naming `default` must not be installed over working copies still under `documents`.
            migrateDefaultFolderIfNeeded(snapshot)
            // Nor a bundle naming renamed connected folders over working copies under the old names.
            migrateSourceFolderNamesIfNeeded(client, snapshot)
            LibraryHeadStateStore(appContext).publish(snapshot)
            // Prepare the complete captured source set before switching the reading generation.
            // A project must never expose a mixture of its old and new document versions.
            OfflineSourceBundleStore(appContext).pinReaderGeneration()
            installedAndPrunedBundleId = diagnostics.measure("sync_source_bundle") {
                syncSourceBundle(snapshot, client, transferPlan = transferPlan)
            }
            syncLibrary(snapshot, client, transferPlan)
            sourceGenerationActivated()
            diagnostics.measure("clear_activated_library_removals") {
                LibraryRemovalStore(appContext).clearActivated(
                    snapshot.publications.associate { publication -> publication.filename to publication.sha256 },
                )
            }
            diagnostics.measureSuspending("write_library_sync_checkpoint") {
            dao.putState(
                (dao.state() ?: before).copy(
                    libraryRevision = snapshot.libraryRevision,
                    artifactRevision = snapshot.effectiveArtifactRevision(),
                    sourceBundleSha256 = snapshot.sourceArtifactSetSha256(),
                ),
            )
            }
        }
        // The active Library now reflects Fedora after these changes; they no longer need showing.
        runCatching { libraryChanges.retire(deliveredBeforeFetch) }
        scheduleSourceBundlePrune(installedAndPrunedBundleId)
    }

    /**
     * Garbage collection of superseded source objects took about ten seconds of every
     * Sync that changed a document, although the new generation was already active.
     * Run it once the Sync releases its locks; it holds them itself, so the next
     * Sync waits for it instead of racing it. A skipped run is repeated next time.
     */
    private fun scheduleSourceBundlePrune(alreadyPrunedBundleId: String?) {
        // One waiting cleanup covers every Sync that finishes meanwhile: it prunes whatever
        // generation is active when it starts (a stale already-pruned id only means a full run).
        if (!SOURCE_BUNDLE_PRUNE_WAITING.compareAndSet(false, true)) return
        SOURCE_BUNDLE_PRUNE_SCOPE.launch {
            val gate = com.gitlab.mudlej.MjPdfReader.data.source.SourceEditingGate.shared
            val waitStarted = System.nanoTime()
            // Wait for a typing pause before taking the Sync locks; waiting inside them would
            // also hold back the Sync the editor itself requests.
            val idle = try {
                gate.awaitNotEditing()
            } finally {
                SOURCE_BUNDLE_PRUNE_WAITING.set(false)
            }
            RUN_MUTEX.withLock {
                SYNC_MUTEX.withLock {
                    val diagnostics = SyncDiagnostics(appContext, null)
                    diagnostics.decision(
                        "source_bundle_cleanup_schedule",
                        if (idle) "editor_idle" else "editor_busy_deadline",
                        "waited_ms" to (System.nanoTime() - waitStarted) / 1_000_000,
                    )
                    runCatching {
                        withBackgroundThreadPriority {
                            diagnostics.measure("prune_active_source_bundle_after_sync") {
                                OfflineSourceBundleStore(appContext).pruneActiveBundle(alreadyPrunedBundleId, diagnostics) {
                                    gate.yieldWhileEditing()
                                }
                            }
                        }
                    }.onFailure { Log.w(SYNC_LOG_TAG_REVIEW_FOLLOW_UP, "Deferred source cleanup failed", it) }
                }
            }
        }
    }

    /** Runs blocking maintenance below the UI and editor threads, then restores the pool thread. */
    private inline fun <T> withBackgroundThreadPriority(block: () -> T): T {
        val thread = android.os.Process.myTid()
        val previous = runCatching { android.os.Process.getThreadPriority(thread) }.getOrNull()
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            return block()
        } finally {
            previous?.let { runCatching { android.os.Process.setThreadPriority(it) } }
        }
    }

    private suspend fun syncTargetDocumentArtifact(
        client: SyncClient,
        expectedPlan: ArtifactSyncPlan,
        transferPlan: SyncTransferPlan,
    ) {
        val target = expectedPlan.targetPdfFilenames.singleOrNull()
            ?: throw ArtifactPlanSupersededException()
        val call = client.documentSync(target)
        val snapshot = call.snapshot ?: throw ArtifactPlanSupersededException()
        val publication = snapshot.publication
        if (
            publication.filename != target ||
            expectedPlan.targetPublicationSha256.isBlank() ||
            publication.sha256 != expectedPlan.targetPublicationSha256 ||
            publication.sizeBytes != expectedPlan.targetPublicationSizeBytes ||
            publication.sourceArtifactSha256.orEmpty() != expectedPlan.sourceBundleSha256
        ) {
            throw ArtifactPlanSupersededException()
        }
        OfflineSourceBundleStore(appContext).pinReaderGeneration()
        val activateSourceArtifact = syncTargetSourceBundle(publication, client, transferPlan)
        activateSourceArtifact?.invoke()
        val activation = activateTargetPublication(client, publication, transferPlan)
        try {
            commitTargetPublicationActivation(activation)
        } catch (error: Throwable) {
            rollbackTargetPublicationActivation(activation)
            throw error
        }
        sourceGenerationActivated()
        LibraryHeadStateStore(appContext).publishDocument(publication)
        PhoneRenderStatusHistory(appContext).saveDocumentSyncStatus(
            target,
            snapshot.renderStatus,
            publication,
        )
        call.etag.takeIf { it.matches(SHA256) }
            ?.let { DocumentSyncStateStore(appContext).save(target, it) }
    }

    private suspend fun fetchLibrarySnapshot(client: SyncClient): LibrarySnapshot? {
        val before = dao.state() ?: ThoughtsSyncStateEntity()
        val localGenerationCurrent = SourceLinkedPdfIndex().snapshot().generationCurrent
        libraryChangesDeliveredBeforeFetch = runCatching { LibraryMutationStore(appContext).deliveredRequestIds() }
            .getOrDefault(emptySet())
        val snapshot = client.library(before.libraryRevision.takeIf { localGenerationCurrent })
        snapshot?.let(::recordMetadataPayload)
        snapshot?.let { LibraryHeadStateStore(appContext).publish(it) }
        return snapshot
    }

    private suspend fun discoverArtifactPlan(
        snapshot: LibrarySnapshot?,
        explicit: Boolean,
        targetPdfFilename: String? = null,
        operationId: String? = null,
        buildsActive: Boolean = false,
        postTerminalBuildRefresh: Boolean = false,
        artifactOnlyRefresh: Boolean = false,
        diagnostics: SyncDiagnostics? = null,
    ): ArtifactSyncPlan? {
        val before = dao.state() ?: ThoughtsSyncStateEntity()

        if (snapshot == null) {
            // Fedora's Library is unchanged since the active one, which therefore shows the changes.
            runCatching { LibraryMutationStore(appContext).retire(libraryChangesDeliveredBeforeFetch) }
            return null
        }

        val generation = readGenerationManifest(PhoneRenderQueue.defaultCanonicalPdfDirectory())
        val publicationDelta = libraryPublicationDelta(
            localPublications = generation.publications,
            remotePublications = snapshot.publications,
            targetPdfFilename = targetPdfFilename,
        )
        val changedPublications = publicationDelta.changedPublications
        val allowDelta = !PdfDeltaFallbackStore(appContext).isRejected(operationId)
        val requiredHashes = requiredPdfReuseHashes(changedPublications,
            generation.publications.values.mapTo(hashSetOf()) { it.first }, allowDelta)
        val verify = {
            verifiedGenerationPdfFiles(PhoneRenderQueue.defaultCanonicalPdfDirectory(),
                generation.publications, requiredHashes).keys +
                // A PDF added on this phone is installed from its local copy, not downloaded.
                verifiedLocallyAddedPdfs().keys.filterTo(hashSetOf()) { digest ->
                    changedPublications.any { it.sha256 == digest }
                }
        }
        val verifiedLocalPdfSha256s = if (diagnostics == null) verify()
            else diagnostics.measure("control_verify_pdf_reuse_candidates", verify)
        diagnostics?.count("control_pdf_reuse_candidates", "required_hashes" to requiredHashes.size.toLong(),
            "local_publications" to generation.publications.size.toLong(),
            "verified_hashes" to verifiedLocalPdfSha256s.size.toLong())
        val pdfTransferBytes = changedPublications.sumOf { publication ->
            estimatedPublicationTransferBytes(
                publication,
                verifiedLocalPdfSha256s,
                allowDelta = allowDelta,
            )
        }
        val libraryRevision = snapshot.libraryRevision
        val sourceBundleSha256 = snapshot.sourceArtifactSetSha256()
        val sourceIndexChanged = snapshot.sourceIndexSha256.orEmpty().let { sourceIndex ->
            sourceIndex.isNotEmpty() && sourceIndex != generation.sourceIndexSha256
        }
        val sourceBundleChanged = sourceBundleSha256.isNotEmpty() &&
            sourceBundleSha256 != before.sourceBundleSha256
        val readingChanged = runCatching {
            val current = JsonParser.parseString(File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), SourceLinkedPdfIndex.GENERATION_FILENAME).readText()).asJsonObject
            current.get("project_readings") != gson.toJsonTree(snapshot.projectReadings)
        }.getOrDefault(true)
        val libraryArtifactsChanged = publicationDelta.changedPdfCount > 0 ||
            sourceIndexChanged || sourceBundleChanged || readingChanged
        if (!libraryArtifactsChanged) {
            refreshLibraryMetadata(snapshot)
            dao.putState(
                (dao.state() ?: before).copy(
                    libraryRevision = snapshot.libraryRevision,
                    artifactRevision = snapshot.effectiveArtifactRevision(),
                    sourceBundleSha256 = snapshot.sourceArtifactSetSha256(),
                ),
            )
            // A rename or archive change is metadata only: this refresh is where it arrives.
            runCatching { LibraryMutationStore(appContext).retire(libraryChangesDeliveredBeforeFetch) }
        }
        if (!libraryArtifactsChanged) return null
        val planId = sha256(
            listOf(
                libraryRevision,
                sourceBundleSha256,
                snapshot.sourceIndexSha256.orEmpty(),
                snapshot.publications.sortedBy { it.filename }
                    .joinToString("|") { "${it.filename}:${it.sha256}:${it.sizeBytes}" },
                targetPdfFilename.orEmpty(),
                postTerminalBuildRefresh.toString(),
            ).joinToString("\n").toByteArray(StandardCharsets.UTF_8),
        )
        return ArtifactSyncPlan(
            planId = planId,
            libraryRevision = libraryRevision,
            sourceBundleSha256 = sourceBundleSha256,
            changedPdfCount = publicationDelta.changedPdfCount,
            estimatedTransferBytes = pdfTransferBytes,
            sourceIndexChanged = sourceIndexChanged,
            sourceBundleChanged = sourceBundleChanged,
            postTerminalBuildRefresh = postTerminalBuildRefresh,
            artifactOnlyRefresh = artifactOnlyRefresh,
            buildsActive = buildsActive,
            targetPdfFilenames = listOfNotNull(targetPdfFilename),
            operationId = operationId,
            explicitRequest = explicit,
        )
    }

    private fun discoverTargetArtifactPlan(
        publication: LibraryEntry,
        explicit: Boolean,
        operationId: String?,
        postTerminalBuildRefresh: Boolean = false,
    ): ArtifactSyncPlan? {
        val plan = discoverTargetArtifactPlan(
            filename = publication.filename,
            sha256 = publication.sha256,
            sizeBytes = publication.sizeBytes,
            sourceArtifactSha256Value = publication.sourceArtifactSha256,
            generationId = publication.generationId,
            explicit = explicit,
            operationId = operationId,
            postTerminalBuildRefresh = postTerminalBuildRefresh,
        ) ?: return null
        val root = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val localPublications = readGenerationManifest(root).publications
        val candidateBase = eligiblePdfDelta(publication, localPublications.values.map { it.first }.toSet())
        val verifiedLocalPdfSha256s = verifiedGenerationPdfFiles(
            root,
            localPublications,
            requiredHashes = setOfNotNull(publication.sha256, candidateBase?.baseSha256),
        ).keys
        return plan.copy(
            estimatedTransferBytes = estimatedPublicationTransferBytes(
                publication,
                verifiedLocalPdfSha256s,
                allowDelta = !PdfDeltaFallbackStore(appContext).isRejected(operationId),
            ),
        )
    }

    private fun discoverTargetArtifactPlan(
        filename: String,
        sha256: String,
        sizeBytes: Long,
        sourceArtifactSha256Value: String?,
        generationId: String,
        explicit: Boolean,
        operationId: String?,
        postTerminalBuildRefresh: Boolean = false,
    ): ArtifactSyncPlan? {
        require(filename.isSafePdfFilename())
        require(sha256.matches(SHA256) && sizeBytes >= 0)
        val root = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val generation = readGenerationManifest(root)
        val destination = File(root, filename)
        val pdfCurrent = generation.publications[filename] ==
            (sha256 to sizeBytes) &&
            destination.isFile && destination.length() == sizeBytes
        val sourceArtifactSha256 = sourceArtifactSha256Value
            ?.takeIf(SHA256::matches)
            .orEmpty()
        val cachedSourceArtifact = File(
            appContext.filesDir,
            "source-editor/document-artifacts/$sourceArtifactSha256",
        )
        val cachedSourceArtifactSha256 = cachedSourceArtifact
            .takeIf { sourceArtifactSha256.isNotEmpty() && it.isFile }
            ?.let { cached ->
                runCatching { sha256(cached.readBytes()) }
                    .getOrNull()
                    ?.takeIf { it == sourceArtifactSha256 }
            }
        val activatedSourceArtifactSha256 = LibraryHeadStateStore(appContext).load()
            ?.publications
            ?.get(filename)
            ?.sourceArtifactSha256
        val sourceArtifactCurrent = sourceArtifactActivationIsCurrent(
            sourceArtifactSha256,
            activatedSourceArtifactSha256,
            cachedSourceArtifactSha256,
        )
        if (pdfCurrent && sourceArtifactCurrent) return null
        val locallyAvailable = generation.publications.entries.any { (filename, identity) ->
            identity == (sha256 to sizeBytes) &&
                File(root, filename).let { it.isFile && it.length() == sizeBytes }
        }
        val planId = sha256(
            (
                "document\n$filename\n$sha256\n" +
                    "$sizeBytes\n$sourceArtifactSha256\n$postTerminalBuildRefresh"
                ).toByteArray(StandardCharsets.UTF_8),
        )
        return ArtifactSyncPlan(
            planId = planId,
            libraryRevision = generationId,
            sourceBundleSha256 = sourceArtifactSha256,
            changedPdfCount = if (pdfCurrent) 0 else 1,
            estimatedTransferBytes = if (pdfCurrent || locallyAvailable) 0 else sizeBytes,
            sourceIndexChanged = false,
            sourceBundleChanged = !sourceArtifactCurrent,

            postTerminalBuildRefresh = postTerminalBuildRefresh,
            targetPdfFilenames = listOf(filename),
            targetPublicationSha256 = sha256,
            targetPublicationSizeBytes = sizeBytes,
            operationId = operationId,
            explicitRequest = explicit,
        )
    }

    private suspend fun updateSyncHealth(
        transform: suspend (ThoughtsSyncStateEntity) -> ThoughtsSyncStateEntity,
    ) {
        database.withTransaction {
            dao.putState(transform(dao.state() ?: ThoughtsSyncStateEntity()))
        }
    }

    private suspend fun recordSyncFailure(code: String, detail: String) {
        updateSyncHealth {
            it.copy(
                lastSyncErrorCode = code,
                lastSyncErrorMessage = detail,
                blockedOperationCount = dao.blockedOperationCount(),
            )
        }
    }

    /**
     * Current proposal rows, each with its projection revision. A Dismiss still in the outbox
     * replaces its row, so the removed proposal leaves Issues at once (agent-proposal spec 10.6).
     */
    internal suspend fun currentProposalRecords(): List<JsonObject> {
        val dismissed = dao.undeliveredByEntityType(PROPOSAL_ENTITY_TYPE)
            .filter { it.collection == PROPOSAL_COLLECTION }
            .associateBy { it.entityId }
        return dao.currentProposalProjections().map { projection ->
            val row = dismissed[projection.entityId]?.payloadJson ?: projection.payloadJson
            JsonParser.parseString(row).asJsonObject.apply {
                if (!has("revision")) addProperty("revision", projection.revision)
                addProperty(PROJECTION_REVISION, projection.revision)
            }
        }
    }

    /**
     * Dismiss of a proposal removed outside Thoughts (agent-proposal spec section 10.6): the only
     * client operation on a `proposal` row. No source changes. Fedora refuses it once the row is
     * no longer `removed_outside` (restored or decided elsewhere); the refusal drops it.
     */
    internal suspend fun dismissRemovedProposal(row: JsonObject): String {
        val dismissal = RemovedProposalDismissal.of(row, Instant.now().toString())
        val operationId = UUID.randomUUID().toString()
        enqueue(listOf(PendingMutation(operationId, RemovedProposalDismissal.ENTITY_TYPE, dismissal.proposalId,
            RemovedProposalDismissal.COLLECTION, dismissal.baseRevision, dismissal.clientTimestamp, dismissal.payload)))
        return operationId
    }

    private suspend fun enqueue(mutations: Collection<PendingMutation>) {
        if (mutations.isEmpty()) return
        database.withTransaction {
            var state = dao.state() ?: ThoughtsSyncStateEntity()
            mutations.forEach { mutation ->
                val payloadJson = mutation.payload.toString()
                val existing = dao.operation(mutation.operationId)
                if (existing != null) {
                    check(
                        existing.entityType == mutation.entityType &&
                            existing.entityId == mutation.entityId &&
                            existing.collection == mutation.collection &&
                            existing.baseRevision == mutation.baseRevision &&
                            existing.clientTimestamp == mutation.clientTimestamp &&
                            locallyEquivalentOperationPayload(
                                mutation.entityType,
                                existing.payloadJson,
                                mutation.payload,
                            )
                    ) {
                        "A stable operation ID was reused for different local content"
                    }
                    return@forEach
                }
                val inserted = dao.insertOutbox(
                    ThoughtsOutboxEntity(
                        operationId = mutation.operationId,
                        deviceSequence = state.nextDeviceSequence,
                        entityType = mutation.entityType,
                        entityId = mutation.entityId,
                        collection = mutation.collection,
                        baseRevision = mutation.baseRevision,
                        clientTimestamp = mutation.clientTimestamp,
                        payloadJson = payloadJson,
                        createdAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                if (inserted != -1L) {
                    state = state.copy(nextDeviceSequence = state.nextDeviceSequence + 1)
                }
            }
            dao.putState(state)
        }
    }

    /** Whether the server accepts moving a linked card's markers; learned at the start of each outbox drain. */
    private var reviewSourceReplacementSupported = true

    private fun reviewSourceObjects() = File(appContext.filesDir, "review-source-objects")

    private suspend fun requireReviewSourceTransactionsDelivered() {
        val undelivered = dao.undelivered()
        check(undelivered.none { it.failureCode == REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED }) {
            "$REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED_MESSAGE. The change stays on this device."
        }
        check(undelivered.none { row ->
            row.entityType in setOf("review_card", "review_event") &&
                JsonParser.parseString(row.payloadJson).asJsonObject.has("source_change")
        }) { "A review source transaction needs attention. Its source changes remain local." }
    }

    /**
     * After a Sync switched PDFs, the server repositions cards in the background. Fetch those
     * positions as they arrive so the Review counts settle without another manual Sync.
     */
    internal suspend fun followReviewPlacements(
        timeoutMillis: Long = REVIEW_PLACEMENT_FOLLOW_UP_MILLIS,
        pollMillis: Long = REVIEW_PLACEMENT_POLL_MILLIS,
    ): Boolean {
        val config = secretStore.config() ?: return false
        val client = syncClient(config)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val shown = ReviewPlacementFollowUpWorker.shownPdfSha256ByFilename().takeIf { it.isNotEmpty() } ?: return false
            val waiting = ReviewStore(appContext).cardsAwaitingPlacement(shown)
            Log.i(SYNC_LOG_TAG_REVIEW_FOLLOW_UP, "Review placement follow-up: ${waiting.size} cards still waiting")
            if (waiting.isEmpty()) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(pollMillis)
            RUN_MUTEX.withLock { SYNC_MUTEX.withLock { pullChanges(client) } }
        }
    }

    /** A card link refused for overlapping another card cannot succeed on retry; keep the server card. */
    private suspend fun retireRejectedReviewLinks() {
        val rejected = dao.undelivered().filter { it.isRejectedOverlappingReviewLink() }
        if (rejected.isEmpty()) return
        val reviews = ReviewStore(appContext)
        rejected.forEach { operation ->
            dao.reconcileDeletion(
                operation.operationId, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "review_link_rejected",
                "The server rejected this card link: ${operation.failureDetail}. The card keeps its server state.",
                System.currentTimeMillis(),
            )
            reviews.discardRejected(operation)
            cleanupAcknowledgedReviewPayload(operation)
        }
    }

    /**
     * A new card whose markers cannot be merged onto the current source keeps the card without
     * a source link. Its markers leave the working copy, so the next source install takes the
     * server version instead of trying to merge them again.
     */
    private suspend fun unlinkUnmergeableReviewLinks(): Boolean {
        val refused = dao.undelivered().filter { it.isUnmergeableReviewLink() }
        if (refused.isEmpty()) return false
        val reviews = ReviewStore(appContext)
        val sources = OfflineSourceBundleStore(appContext)
        refused.forEach { operation ->
            val payload = JsonParser.parseString(operation.payloadJson).asJsonObject
            if (movesReviewMarkers(payload, reviewSourceObjects())) {
                // The card's markers already exist on the server: keep its server placement and
                // put the old markers back rather than unlinking the card.
                if (!restoreReplacedReviewMarkers(sources, payload, reviewSourceObjects())) {
                    Log.w(SYNC_LOG_TAG_REVIEW_FOLLOW_UP, "The moved markers of card ${operation.entityId} remain in its source")
                }
                reviews.discardRejected(operation)
                dao.reconcileDeletion(
                    operation.operationId, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "review_link_unmerged",
                    "The server could not move this card's markers (${operation.failureDetail}). The card keeps its previous placement.",
                    System.currentTimeMillis(),
                )
                cleanupAcknowledgedReviewPayload(operation)
                return@forEach
            }
            if (!removeUnsentReviewMarkers(sources, payload)) {
                Log.w(SYNC_LOG_TAG_REVIEW_FOLLOW_UP, "The unsent markers of card ${operation.entityId} remain in its source")
            }
            reviews.replaceRejected(operation, unlinkedReviewCardPayload(payload, operation.failureDetail))
            dao.reconcileDeletion(
                operation.operationId, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "review_link_unmerged",
                "The server could not merge this card's markers (${operation.failureDetail}). The card was kept without a source link.",
                System.currentTimeMillis(),
            )
            cleanupAcknowledgedReviewPayload(operation)
        }
        captureReviewCards()
        return true
    }

    /** Settle blocked comment requests that Fedora's current records show were already applied. */
    private suspend fun reconcileBlockedComments(client: SyncClient) {
        val blocked = dao.undelivered().filter { it.isBlockedReplyDeletion() || it.isBlockedDuplicateCreation() }
        if (blocked.isEmpty()) return
        val snapshot = client.projections()
        val canonical = snapshot.projections.filter {
            it.entityType == "logical_comment" && it.collection == "comments"
        }.associateBy { it.entityId }
        database.withTransaction {
            blocked.forEach { candidate ->
                val current = dao.operation(candidate.operationId) ?: return@forEach
                val reply = confirmedReplyDeletion(current, canonical)
                val comment = if (reply == null) confirmedDuplicateCreation(current, canonical) else null
                val (reason, detail) = when {
                    reply != null -> "deletion_already_applied" to
                        "The server confirms the same reply and parent are deleted; reply revision ${reply.revision}."
                    comment != null -> "creation_already_applied" to
                        "The server already holds this comment with the same capture and contents; revision ${comment.revision}."
                    else -> return@forEach
                }
                dao.reconcileDeletion(
                    current.operationId, ThoughtsOutboxEntity.STATE_RECONCILED,
                    snapshot.snapshotSequence, reason, detail, System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * A failed proposal decision refers to a document version the phone no longer shows; once a newer
     * version is active its proposals can be decided again, and the old decision stops failing Sync.
     */
    private suspend fun retireFailedProposalDecisionsOfReplacedDocuments() {
        val activeArtifacts = OfflineSourceBundleStore(appContext).activeDocumentArtifacts()
        replacedFailedProposalDecisionIds(dao.undelivered(), activeArtifacts).forEach { operationId ->
            dao.reconcileDeletion(
                operationId, ThoughtsOutboxEntity.STATE_SUPERSEDED, null, "proposal_artifact_replaced",
                "The document was rebuilt after this decision failed", System.currentTimeMillis(),
            )
        }
    }

    private suspend fun drainOutbox(
        client: SyncClient,
        targetPdfFilename: String? = null,
        retryProposalFailures: Boolean = false,
    ): DeltaPassResult {
        recoverSourceCommentJournals()
        dao.retireVoiceOperations()
        val capabilities = serverCapabilities(client)
        val sourceDeletionSupported = REVIEW_SOURCE_DELETE_CAPABILITY in capabilities
        if (REVIEW_TAG_MERGE_CAPABILITY in capabilities && targetPdfFilename == null) captureReviewTagMerges()
        captureReviewTagGroups(capabilities, targeted = targetPdfFilename != null)
        capturePdfLinkOverrides(capabilities, targeted = targetPdfFilename != null)
        captureReviewCardFeedback(capabilities, targeted = targetPdfFilename != null)
        captureDocumentFeedback(capabilities, targeted = targetPdfFilename != null)
        captureAgentConversations(capabilities, targeted = targetPdfFilename != null)
        reviewSourceReplacementSupported = REVIEW_SOURCE_REPLACEMENT_CAPABILITY in capabilities
        secretStore.config()?.let {
            ReviewSourceCapabilityStore(appContext).record(it.baseUrl, sourceDeletionSupported)
            ReviewSourceCapabilityStore(appContext).recordReplacement(it.baseUrl, reviewSourceReplacementSupported)
        }
        if (reviewSourceReplacementSupported) {
            dao.undelivered().filter { it.failureCode == REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED }
                .forEach { dao.retryUnsupportedReviewReplacement(it.operationId) }
        }
        if (sourceDeletionSupported && targetPdfFilename == null) {
            dao.undelivered().filter { it.deletesReviewSource() && it.failureCode == REVIEW_SOURCE_UNSUPPORTED }
                .forEach { dao.retryUnsupportedReviewSource(it.operationId) }
        }
        // Retried on full Syncs only; a server that still refuses it just blocks it again.
        if (targetPdfFilename == null) {
            dao.undelivered().filter { it.isRetryableChangedReviewPassage() }
                .forEach { dao.retryChangedReviewPassage(it.operationId) }
        }
        // Retry this infrastructure failure once per explicit Sync, preserving the
        // exact request. Stale/changed proposals still require a new user decision.
        if (retryProposalFailures) {
            dao.undelivered().filter { it.hasRetryableProposalManifestFailure(targetPdfFilename) }
                .forEach { dao.retryUnavailableProposalManifest(it.operationId) }
        }
        retireFailedProposalDecisionsOfReplacedDocuments()
        // Called only within an existing synchronization; never start network work from UI reads.
        // Project-free Notes belong to global sync, not to an individual PDF's sync.
        if (targetPdfFilename == null) {
            val candidates = dao.undelivered().filter { it.hasLegacyNoteSchemaFailure() }
            if (candidates.isNotEmpty()) {
                val recoverable = recoverableNoteOperationIds(candidates, serverCapabilities(client).toList())
                database.withTransaction {
                    recoverable.forEach { dao.retryCompatibleNote(it) }
                }
            }
        }
        if (targetPdfFilename == null) reconcileBlockedComments(client)
        retireRejectedReviewLinks()
        unlinkUnmergeableReviewLinks()
        val anchorJobs = linkedSetOf<String>()
        var uploadedOperations = false
        while (true) {
            val pending = if (targetPdfFilename == null) {
                dao.pending(200)
            } else {
                val all = dao.undelivered()
                    .filter { it.deliveryState == ThoughtsOutboxEntity.STATE_PENDING }
                val projectionEntities = dao.projections().map { it.toScopedEntity() }
                val targetKeys = documentScopedEntityKeys(
                    targetPdfFilename,
                    projectionEntities + all.map { it.toScopedEntity() },
                )
                all.filter { it.scopedKey() in targetKeys }.take(200)
            }
            if (pending.isEmpty()) {
                // A duplicate captured during this Sync is rejected by the upload above.
                if (targetPdfFilename == null && uploadedOperations) reconcileBlockedComments(client)
                if (uploadedOperations) retireRejectedReviewLinks()
                // The unlinked cards were captured as new pending operations; send them now.
                if (uploadedOperations && unlinkUnmergeableReviewLinks()) continue
                return DeltaPassResult(anchorJobs, uploadedOperations)
            }
            uploadedOperations = true
            uploadOutboxBatch(client, pending, anchorJobs, sourceDeletionSupported, "client_review_sources_v1" in capabilities)
            var proposalDeferred = false
            for (operation in pending) {
                if (
                    operation.entityType in setOf(
                        AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE,
                        AGENT_PROPOSAL_UNDO_ENTITY_TYPE,
                    ) && dao.operation(operation.operationId)?.deliveryState ==
                    ThoughtsOutboxEntity.STATE_PENDING
                ) {
                    proposalDeferred = true
                    break
                }
            }
            if (proposalDeferred) {
                return DeltaPassResult(anchorJobs, uploadedOperations)
            }
        }
    }

    private fun documentScopedEntityKeys(
        targetPdfFilename: String,
        entities: List<ScopedEntity>,
    ): Set<ScopedEntityKey> {
        val stem = targetPdfFilename.removeSuffix(".pdf")
        val proposalSourceFiles = if (entities.any { it.entityType == "proposal_event" }) {
            OfflineSourceBundleStore(appContext).librarySourceMetadata(targetPdfFilename)
                ?.pdfs?.get(targetPdfFilename)?.segments.orEmpty()
                .flatMap { segment -> segment.inputs.map { it.sourceFile } + listOfNotNull(segment.primarySourceFile) }
                .toSet()
        } else emptySet()
        // Document feedback belongs to the target PDF's permanent document id (anchor.pdf.document_id).
        val targetDocumentId = if (entities.any { it.entityType == "document_feedback" }) {
            DocumentIdentityStore().byFilename(targetPdfFilename)?.get("document_id")
                ?.takeIf { it.isJsonPrimitive }?.asString
        } else null
        val targetCardIds = entities.filter { entity ->
            entity.entityType == "review_card" &&
                entity.payload.get("primary")?.takeIf { it.isJsonObject }?.asJsonObject?.safeString("pdf_stem") == stem
        }.mapTo(linkedSetOf(), ScopedEntity::entityId)
        return entities.mapNotNullTo(linkedSetOf()) { entity ->
            val directFilename = listOf("pdf_filename", "target", "library_filename")
                .any { name -> entity.payload.safeString(name) == targetPdfFilename } ||
                entity.payload.get("pdf_filenames")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.any { filename -> filename.asString == targetPdfFilename } == true
            val logicalOriginCollection = entity.payload.get("origin")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.safeString("collection")
            val logicalLocationFilename = entity.payload.get("location")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.safeString("pdf_filename")
            val logicalLocationFilenames = entity.payload.get("locations")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { value ->
                    value.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.safeString("pdf_filename")
                }
                .orEmpty()
            val belongs = entity.collection == stem || directFilename || when (entity.entityType) {
                "logical_comment" ->
                    logicalOriginCollection == stem ||
                        logicalLocationFilename == targetPdfFilename ||
                        targetPdfFilename in logicalLocationFilenames
                "review_card" -> entity.entityId in targetCardIds
                "review_event", "review_card_feedback" -> entity.payload.safeString("card_id") in targetCardIds
                "document_feedback" -> documentFeedbackBelongsTo(entity.payload, targetDocumentId)
                "proposal_event" -> entity.payload.getAsJsonObject("snapshot")?.safeString("source_file") in proposalSourceFiles
                "review_exclusion" -> entity.payload.safeString("pdf_stem") == stem
                else -> false
            }
            entity.key().takeIf { belongs }
        }
    }

    private fun ScopedEntity.key() = ScopedEntityKey(entityType, collection, entityId)

    private fun ThoughtsOutboxEntity.scopedKey() = ScopedEntityKey(
        entityType.canonicalSyncEntityType(),
        collection,
        entityId,
    )

    private fun ThoughtsProjectionEntity.scopedKey() = ScopedEntityKey(
        entityType.canonicalSyncEntityType(),
        collection,
        entityId,
    )

    private fun ThoughtsOutboxEntity.toScopedEntity() = ScopedEntity(
        entityType.canonicalSyncEntityType(),
        collection,
        entityId,
        runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrDefault(JsonObject()),
    )

    private fun ThoughtsProjectionEntity.toScopedEntity() = ScopedEntity(
        entityType.canonicalSyncEntityType(),
        collection,
        entityId,
        runCatching { JsonParser.parseString(payloadJson).asJsonObject }.getOrDefault(JsonObject()),
    )

    private suspend fun uploadOutboxBatch(
        client: SyncClient,
        rows: List<ThoughtsOutboxEntity>,
        anchorJobs: MutableSet<String>,
        sourceDeletionSupported: Boolean,
        clientReviewSourcesSupported: Boolean,
    ) {
        if (rows.isEmpty()) return
        val attemptedAt = System.currentTimeMillis()
        val unsupportedRows = rows.filter { it.deletesReviewSource() && !sourceDeletionSupported }
        unsupportedRows.forEach {
            dao.markBlocked(it.operationId, REVIEW_SOURCE_UNSUPPORTED, REVIEW_SOURCE_UNSUPPORTED_MESSAGE, attemptedAt)
        }
        // Moving a linked card's markers needs a server that accepts it; until then it stays on this device.
        val unsupportedReplacements = if (reviewSourceReplacementSupported) emptyList() else rows.filter { row ->
            row.entityType == "review_card" && runCatching {
                movesReviewMarkers(JsonParser.parseString(row.payloadJson).asJsonObject, reviewSourceObjects())
            }.getOrDefault(false)
        }
        unsupportedReplacements.forEach {
            dao.markBlocked(it.operationId, REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED,
                "$REVIEW_SOURCE_REPLACEMENT_UNSUPPORTED_MESSAGE. The change stays on this device.", attemptedAt)
        }
        val supportedRows = rows - unsupportedRows.toSet() - unsupportedReplacements.toSet()
        val proposalRows = supportedRows.filter {
            it.entityType == AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE ||
                it.entityType == AGENT_PROPOSAL_UNDO_ENTITY_TYPE
        }
        for (row in proposalRows) {
            if (dao.operation(row.operationId)?.deliveryState != ThoughtsOutboxEntity.STATE_PENDING) continue
            try {
                val payload = JsonParser.parseString(row.payloadJson).asJsonObject
                val resolution = if (row.entityType == AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE) {
                    client.resolveAgentProposals(
                        AgentProposalResolutionRequest(
                            operationId = row.operationId,
                            deviceSequence = row.deviceSequence,
                            artifactSha256 = payload.string("artifact_sha256"),
                            action = payload.string("action"),
                            proposals = payload.getAsJsonArray("proposals").map {
                                val proposal = it.asJsonObject
                                ProposalObjectIdentity(
                                    proposal.string("id"),
                                    proposal.string("object_sha256"),
                                )
                            },
                        ),
                    )
                } else {
                    client.undoAgentProposalResolution(
                        payload.string("resolution_id"),
                        AgentProposalUndoRequest(row.operationId, row.deviceSequence),
                    )
                }
                when (val disposition = agentProposalServerDisposition(resolution.status, resolution.error)) {
                    AgentProposalServerDisposition.Applied -> {
                        if (row.entityType == AGENT_PROPOSAL_RESOLUTION_ENTITY_TYPE && payload.string("action") == "accept") {
                            markPdfKeepingAcceptDelivered()
                        } else {
                            pdfChangingDeliveredThisSync = true
                        }
                        database.withTransaction {
                            dao.markAccepted(row.operationId, 0, attemptedAt)
                            supersededProposalDecisionIds(
                                dao.undelivered(),
                                row.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED),
                            ).forEach { previousId ->
                                dao.reconcileDeletion(
                                    previousId, "superseded", null, "newer_proposal_decision_applied",
                                    "Replaced by applied proposal decision ${row.operationId}", attemptedAt,
                                )
                            }
                            duplicatePendingProposalDecisionIds(
                                dao.allOutbox(),
                                row.copy(deliveryState = ThoughtsOutboxEntity.STATE_ACCEPTED),
                            ).forEach { duplicateId ->
                                dao.reconcileDeletion(
                                    duplicateId, ThoughtsOutboxEntity.STATE_SUPERSEDED, null,
                                    "duplicate_proposal_decision_applied",
                                    "Identical proposal decision applied by ${row.operationId}", attemptedAt,
                                )
                            }
                        }
                        recordLogicalUpload(row.toOperationInput())
                    }
                    AgentProposalServerDisposition.Pending -> Unit
                    is AgentProposalServerDisposition.Failed -> dao.markBlocked(
                        row.operationId,
                        "proposal_resolution_failed",
                        disposition.error,
                        attemptedAt,
                    )
                }
            } catch (error: ServerException) {
                if (error.statusCode !in setOf(409, 422)) throw error
                dao.markBlocked(
                    row.operationId,
                    "http_${error.statusCode}",
                    safeFailureDetail(error),
                    attemptedAt,
                )
            }
        }
        val ordinaryRows = supportedRows - proposalRows.toSet()
        if (ordinaryRows.isEmpty()) return
        // A restored "Comment missing" comment travels alone: a 409 rejects only its own batch.
        val batches = isolatedOperationBatches(ordinaryRows, IsolatedOperations(appContext).ids()) { it.operationId }
        if (batches.size > 1) {
            batches.forEach { uploadOutboxBatch(client, it, anchorJobs, sourceDeletionSupported, clientReviewSourcesSupported) }
            return
        }
        val clientReviews = ordinaryRows.filter { it.entityType in setOf("review_card", "review_event") }
            .map { JsonParser.parseString(it.payloadJson).asJsonObject }
            .filter { it.safeString("source_authority") == "client-v1" }
        if (clientReviews.isNotEmpty()) {
            check(clientReviewSourcesSupported) {
                "Update the server to synchronize local review source changes. They remain saved on this device."
            }
            clientReviews.mapNotNull { it.getAsJsonObject("source_change") }.flatMap {
                listOf(it.safeString("before_sha256").orEmpty(), it.safeString("after_sha256").orEmpty(),
                    it.safeString("base_sha256") ?: it.safeString("before_sha256").orEmpty())
            }.distinct().forEach { hash ->
                check(hash.matches(Regex("[0-9a-f]{64}")))
                if (!client.hasObject(hash)) {
                    val file = File(appContext.filesDir, "review-source-objects/$hash")
                    check(file.isFile && file.length() <= SourceEditQueue.MAX_SOURCE_BYTES)
                    val bytes = file.readBytes()
                    check(com.gitlab.mudlej.MjPdfReader.data.source.LocalReviewSources.digest(bytes) == hash) {
                        "The local review source object failed verification."
                    }
                    check(client.uploadObject(bytes, "text/plain; charset=utf-8").sha256 == hash)
                }
            }
        }
        val attachmentStore = com.gitlab.mudlej.MjPdfReader.data.annotation.NoteAttachmentStore(appContext)
        ordinaryRows.filter { it.entityType == "logical_comment" }.flatMap { row ->
            val payload = JsonParser.parseString(row.payloadJson).asJsonObject
            if (payload.get("deleted")?.asBoolean == true) emptyList()
            else com.gitlab.mudlej.MjPdfReader.data.annotation.NoteAttachment.read(payload)
        }.distinctBy { it.sha256 }.forEach { item ->
            if (!client.hasObject(item.sha256)) {
                check(attachmentStore.verify(item)) { "Attachment ${item.name} is missing or damaged" }
                attachmentStore.file(item).inputStream().use { input ->
                    check(client.uploadObject(input, item.size, item.sha256, item.mimeType).sha256 == item.sha256)
                }
            }
        }
        pdfChangingDeliveredThisSync = true
        val result = try {
            client.uploadOperations(ordinaryRows.map { it.toOperationInput() })
        } catch (error: ServerException) {
            if (error.statusCode == 404 && ordinaryRows.any { it.deletesReviewSource() }) {
                secretStore.config()?.let { ReviewSourceCapabilityStore(appContext).record(it.baseUrl, false) }
                uploadOutboxBatch(client, ordinaryRows, anchorJobs, sourceDeletionSupported = false, clientReviewSourcesSupported = clientReviewSourcesSupported)
                return
            }
            if (error.statusCode !in setOf(409, 422)) throw error
            if (ordinaryRows.size > 1) {
                val split = ordinaryRows.size / 2
                uploadOutboxBatch(client, ordinaryRows.subList(0, split), anchorJobs, sourceDeletionSupported, clientReviewSourcesSupported)
                uploadOutboxBatch(client, ordinaryRows.subList(split, ordinaryRows.size), anchorJobs, sourceDeletionSupported, clientReviewSourcesSupported)
            } else if (ordinaryRows.single().entityType == PROPOSAL_ENTITY_TYPE) {
                // A Dismiss after a Restore or a decision on another device: nothing to dismiss.
                dao.reconcileDeletion(
                    ordinaryRows.single().operationId, ThoughtsOutboxEntity.STATE_SUPERSEDED, null,
                    "proposal_not_removed_outside", safeFailureDetail(error), attemptedAt,
                )
            } else if (isUnknownAttachmentCardRefusal(ordinaryRows.single(), error)) {
                // A card made in an HTML attachment page whose version its PDF never linked to: no
                // retry can succeed. It stays on this phone, marked as not sent, and later Syncs
                // are not failed by it (a blocked row would fail every Sync).
                val row = ordinaryRows.single()
                val detail = UNKNOWN_ATTACHMENT_CARD_DETAIL
                dao.reconcileDeletion(row.operationId, ThoughtsOutboxEntity.STATE_SUPERSEDED, null,
                    org.tbend.thoughts.sdk.HTML_ATTACHMENT_UNKNOWN, detail, attemptedAt)
                com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCardStatus.of(appContext).recordNotSent(row.entityId, detail)
                Log.w(SYNC_LOG_TAG_REVIEW_FOLLOW_UP, "review card ${row.entityId} kept on this phone: ${safeFailureDetail(error)}")
            } else {
                dao.markBlocked(
                    ordinaryRows.single().operationId,
                    "http_${error.statusCode}",
                    safeFailureDetail(error),
                    attemptedAt,
                )
            }
            return
        }
        val acknowledged = result.results.associateBy { it.operationId }
        check(acknowledged.keys == ordinaryRows.map { it.operationId }.toSet()) {
            "Server did not acknowledge the complete operation batch"
        }
        ordinaryRows.forEach { operation ->
            val accepted = checkNotNull(acknowledged[operation.operationId])
            accepted.anchorJobId?.let(anchorJobs::add)
            if (accepted.outcome == "superseded") {
                dao.reconcileDeletion(
                    operation.operationId, ThoughtsOutboxEntity.STATE_SUPERSEDED,
                    accepted.serverSequence, "server_superseded",
                    "The server retained revision ${accepted.entityRevision}; this operation was not applied.",
                    attemptedAt,
                )
            } else {
                dao.markAccepted(operation.operationId, accepted.serverSequence, attemptedAt)
            }
            ReviewStore(appContext).acknowledge(operation)
            cleanupAcknowledgedReviewPayload(operation)
        }
        ordinaryRows.forEach { recordLogicalUpload(it.toOperationInput()) }
    }

    private fun ThoughtsOutboxEntity.toOperationInput(): OperationInput = OperationInput(
        operationId = operationId,
        deviceSequence = deviceSequence,
        entityType = entityType,
        entityId = entityId,
        collection = collection,
        baseRevision = baseRevision,
        clientTimestamp = clientTimestamp,
        payload = restoreNoteNulls(JsonParser.parseString(payloadJson).asJsonObject),
    )

    private suspend fun pullChanges(
        client: SyncClient,
        transferPlan: SyncTransferPlan? = null,
        diagnostics: SyncDiagnostics? = null,
    ): PullChangesResult {
        var applied = false
        val deletions = mutableListOf<ThoughtsProjectionEntity>()
        while (true) {
            val before = dao.state() ?: ThoughtsSyncStateEntity()
            val page = client.changes(before.serverCursor)
            recordMetadataPayload(page)
            check(page.nextCursor >= before.serverCursor) { "Server cursor moved backwards" }
            val reconciledLocalOperations = mutableListOf<ThoughtsOutboxEntity>()
            database.withTransaction {
                val current = dao.state() ?: ThoughtsSyncStateEntity()
                check(current.serverCursor == before.serverCursor) { "Sync cursor changed concurrently" }
                val acceptedAt = System.currentTimeMillis()
                page.changes.filter { it.outcome == "applied" }.forEach { change ->
                    applied = true
                    dao.operation(change.operationId)
                        ?.takeIf { operation -> outboxMatchesServerChange(operation, change) }
                        ?.let { operation ->
                            dao.markAccepted(change.operationId, change.serverSequence, acceptedAt)
                            reconciledLocalOperations += operation
                        }
                    val projection = change.toProjection()
                    if (projection.deleted) {
                        if (projection.entityType == "logical_comment") dao.putProjection(projection)
                        else dao.deleteProjection(
                            projection.entityType,
                            projection.collection,
                            projection.entityId,
                        )
                        deletions += projection
                    } else {
                        dao.putProjection(projection)
                    }
                }
                dao.putState(current.copy(serverCursor = page.nextCursor))
            }
            reconciledLocalOperations.forEach { operation ->
                ReviewStore(appContext).acknowledge(operation)
                cleanupAcknowledgedReviewPayload(operation)
            }
            if (!page.hasMore) {
                if (applied) {
                    materialize(
                        dao.sidecarProjections(),
                        client,


                        deletions,
                        transferPlan,
                    )
                } else {
                    refreshReviewProjections(diagnostics = diagnostics)
                }
                return PullChangesResult(applied, page.nextCursor)
            }
        }
    }

    private suspend fun applyTargetProjectionSnapshot(
        snapshot: DocumentSyncSnapshot,
        client: SyncClient,
    ) {
        val before = dao.projections()
        val incoming = snapshot.projections.map { it.toProjection(snapshot.snapshotSequence) }
        val targetKeys = documentScopedEntityKeys(
            snapshot.target,
            before.map { it.toScopedEntity() } + incoming.map { it.toScopedEntity() },
        )
        val previousTarget = before.filter { it.scopedKey() in targetKeys }
        val incomingKeys = incoming.mapTo(hashSetOf()) { it.scopedKey() }
        val deletions = previousTarget.filter { it.scopedKey() !in incomingKeys }
        database.withTransaction {
            previousTarget.forEach { projection ->
                dao.deleteProjection(
                    projection.entityType,
                    projection.collection,
                    projection.entityId,
                )
            }
            incoming.filterNot(ThoughtsProjectionEntity::deleted).forEach { dao.putProjection(it) }
        }
        materialize(
            dao.sidecarProjections(),
            client,
            deletions = deletions,
        )
    }

    private suspend fun bootstrapProjections(
        client: SyncClient,
        transferPlan: SyncTransferPlan? = null,
    ) {
        val snapshot = client.projections()
        recordMetadataPayload(snapshot)
        database.withTransaction {
            val current = dao.state() ?: ThoughtsSyncStateEntity()
            check(snapshot.snapshotSequence >= current.serverCursor) { "Projection frontier moved backwards" }
            dao.deleteProjections()
            snapshot.projections.forEach { projection -> dao.putProjection(projection.toProjection(snapshot.snapshotSequence)) }
            dao.putState(
                current.copy(
                    serverCursor = snapshot.snapshotSequence,
                    projectionBootstrapped = true,
                ),
            )
        }
        materialize(
            dao.sidecarProjections(),
            client,


            transferPlan = transferPlan,
        )
    }

    private suspend fun syncSourceRequests(
        client: SyncClient,
        targetPdfFilename: String? = null,
        requestBuilds: Boolean = false,
    ) {
        val requestRoot = SourceEditQueue.defaultRequestDirectory()
        val queue = SourceEditQueue(appContext, scheduleRequests = false)
        val buildIntents = mutableListOf<SourceBuildIntent>()
        SOURCE_REQUEST_MUTEX.withLock {
            requestRoot.listFiles { file ->
                file.isFile && file.extension.equals("json", ignoreCase = true)
            }
                .orEmpty()
                .sortedBy(File::lastModified)
                .forEach { requestFile ->
                    if (targetPdfFilename != null) {
                        val requestTarget = runCatching {
                            JsonParser.parseString(requestFile.readText()).asJsonObject
                                .safeString("pdf_filename")
                        }.getOrNull()
                        if (requestTarget != targetPdfFilename) return@forEach
                    }
                    processSourceRequestFile(client, queue, requestFile)?.let(buildIntents::add)
                }
        }
        if (!requestBuilds) return
        buildIntents
            .groupBy(SourceBuildIntent::pdfFilename)
            .mapValues { (_, intents) -> intents.mapNotNull(SourceBuildIntent::sourceFrontier).maxOrNull() }
            .forEach { (target, sourceFrontier) ->
                val inputSnapshot = runCatching { client.latestBuildInputs(target) }.getOrNull()
                val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), target)
                runCatching {
                    PhoneRenderQueue().enqueuePdf(
                        pdf,
                        sourceFrontier = sourceFrontier,
                        inputSnapshotSha256 = inputSnapshot?.sha256,
                    )
                }.getOrElse {
                    client.requestBuild(
                        target,
                        sourceFrontier = sourceFrontier,
                        inputSnapshotSha256 = inputSnapshot?.sha256,
                    )
                }
            }
    }

    private suspend fun processSourceRequestFile(
        client: SyncClient,
        queue: SourceEditQueue,
        requestFile: File,
        requiredAction: String? = null,
    ): SourceBuildIntent? {
        val requestId = requestFile.nameWithoutExtension.takeIf {
            runCatching { UUID.fromString(it) }.isSuccess
        } ?: return null
        val receipt = File(SourceEditQueue.defaultReceiptDirectory(), "$requestId.json")
        if (queue.receipt(requestId) != null) return null
        if (requestFile.length() !in 1..SourceEditQueue.MAX_REQUEST_BYTES) {
            writeSourceFailureReceipt(
                receipt,
                requestId,
                "",
                "The source request is empty or exceeds the supported size.",
            )
            return null
        }
        // A request queued before this phone moved its connected folders names them as they are called now.
        val request = runCatching { JsonParser.parseString(requestFile.readText()).asJsonObject }
            .getOrNull()?.let { com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.followLocal(it) }
        if (request == null) {
            writeSourceFailureReceipt(
                receipt,
                requestId,
                "",
                "The source request is not valid JSON.",
            )
            return null
        }
        val action = request.safeString("action").orEmpty()
        if (request.safeString("request_id") != requestId) {
            writeSourceFailureReceipt(
                receipt,
                requestId,
                action,
                "The source request identity does not match its durable filename.",
                request,
            )
            return null
        }
        if (requiredAction != null && action != requiredAction) {
            writeSourceFailureReceipt(
                receipt,
                requestId,
                action,
                "The source request is not an editable-source open request.",
                request,
            )
            return null
        }
        queue.markDeliveryStarted(requestId)
        return try {
            val result = when (action) {
                SourceEditQueue.ACTION_OPEN -> {
                    resolveSourceOpen(client, request, receipt)
                    null
                }
                SourceEditQueue.ACTION_SAVE -> applySourceSave(client, request, receipt, queue)
                SourceEditQueue.ACTION_RESOLVE_CONFLICT -> resolveSourceConflict(
                    client,
                    request,
                    receipt,
                    queue,
                )
                else -> throw PermanentSourceRequestException("The source request action is not supported.")
            }
            result
        } catch (error: PermanentSourceRequestException) {
            writeSourceFailureReceipt(
                receipt,
                requestId,
                action,
                error.message.orEmpty(),
                request,
            )
            null
        } catch (error: ServerException) {
            if (error.statusCode !in setOf(400, 404, 409, 410, 422)) throw error
            writeSourceFailureReceipt(
                receipt,
                requestId,
                action,
                safeFailureDetail(error),
                request,
            )
            null
        }
    }

    private fun recordMetadataPayload(value: Any) {
        val bytes = gson.toJson(value).toByteArray(StandardCharsets.UTF_8).size.toLong()
        activeTransferProgress?.recordMetadataDownload(bytes)
    }

    private suspend fun uploadSourceObject(client: SyncClient, bytes: ByteArray): String {
        val digest = sha256(bytes)
        val uploaded = client.uploadObject(bytes, "text/plain; charset=utf-8")
        check(uploaded.sha256 == digest) { "The server stored a different source object." }
        activeTransferProgress?.recordCommittedObjectUpload(digest, bytes.size.toLong())
        return digest
    }

    private fun recordLogicalUpload(value: Any) {
        val bytes = gson.toJson(value).toByteArray(StandardCharsets.UTF_8).size.toLong()
        activeTransferProgress?.recordLogicalUpload(bytes)
    }

    private suspend fun resolveSourceOpen(
        client: SyncClient,
        request: JsonObject,
        receipt: File,
    ) {
        val requestId = request.safeString("request_id").orEmpty()
        val pdfFilename = request.safeString("pdf_filename").orEmpty()
        val pdfSha256 = request.safeString("pdf_sha256").orEmpty()
        val page = request.safeInt("page")
        val x = request.safeFloat("x")
        val y = request.safeFloat("y")
        requireSourceRequest(pdfFilename.isSafePdfFilename(), "The source request has an invalid PDF filename.")
        requireSourceRequest(pdfSha256.matches(SHA256), "The source request has an invalid PDF identity.")
        requireSourceRequest(page != null && page > 0, "The source request has an invalid PDF page.")
        requireSourceRequest(
            x != null && x.isFinite() && x >= 0f && y != null && y.isFinite() && y >= 0f,
            "The source request has invalid PDF coordinates.",
        )
        val verified = resolveVerifiedSourceOpen(
            request = SourceSelectionRequest(
                pdfFilename = pdfFilename,
                pdfSha256 = pdfSha256,
                page = requireNotNull(page),
                x = requireNotNull(x),
                y = requireNotNull(y),
            ),
            resolve = client::resolveSourceSelection,
            downloadObject = client::downloadObject,
        )
        val resolved = verified.resolution
        dao.putSourceSnapshot(
            ThoughtsSourceSnapshotEntity(
                path = resolved.path,
                revision = resolved.revision,
                sha256 = resolved.sha256,
                objectSha256 = resolved.objectSha256,
                resolutionId = resolved.buildId,
            ),
        )
        writeSourceReceipt(
            receipt,
            requestId,
            SourceEditQueue.ACTION_OPEN,
            SourceEditQueue.STATUS_SUCCESS,
            applied = false,
            resolved.path,
            resolved.line,
            resolved.sha256,
            verified.contents,
            resolved.buildId,
        )
    }

    private suspend fun applySourceSave(
        client: SyncClient,
        request: JsonObject,
        receipt: File,
        queue: SourceEditQueue,
    ): SourceBuildIntent? {
        pdfChangingDeliveredThisSync = true
        val requestId = request.safeString("request_id").orEmpty()
        val path = request.safeString("source_file").orEmpty()
        val baseSha256 = request.safeString("base_sha256").orEmpty()
        val resolutionId = request.safeString("resolution_id").orEmpty()
        val contents = request.safeString("contents")
        val sourceLine = request.safeInt("source_line")?.takeIf { it > 0 } ?: 1
        requireSourceRequest(path.isNotBlank(), "The source request has no source path.")
        requireSourceRequest(baseSha256.matches(SHA256), "The source request has an invalid base identity.")
        requireSourceRequest(
            resolutionId.isSourceResolutionId(),
            "The source request has an invalid resolution identity.",
        )
        requireSourceRequest(contents != null, "The source request has no editable contents.")
        requireSourceRequest(
            requireNotNull(contents).toByteArray(StandardCharsets.UTF_8).size <= SourceEditQueue.MAX_SOURCE_BYTES,
            "The edited source exceeds the supported size.",
        )
        val cached = dao.sourceSnapshot(path)
        val current = if (cached?.sha256 == baseSha256) {
            null
        } else {
            client.sources().sources.firstOrNull { it.path == path }
        }
        val snapshot = when (
            val decision = sourceBaseDecision(cached, current, baseSha256, resolutionId)
        ) {
            is SourceBaseDecision.Ready -> decision.snapshot.also { refreshed ->
                if (refreshed != cached) dao.putSourceSnapshot(refreshed)
            }
            is SourceBaseDecision.Stale -> {
                val mergeBase = request.safeInt("schema_version")
                    ?.takeIf { it == SourceEditQueue.MERGE_SCHEMA_VERSION }
                    ?.let {
                        staleSourceMergeBase(
                            decision.snapshot,
                            baseSha256,
                            request.safeString("base_contents"),
                            resolutionId,
                        )
                    }
                if (mergeBase != null) {
                    val baseBytes = requireNotNull(request.safeString("base_contents"))
                        .toByteArray(StandardCharsets.UTF_8)
                    if (!client.hasObject(baseSha256)) {
                        check(uploadSourceObject(client, baseBytes) == baseSha256)
                    }
                    mergeBase
                } else {
                    val canonicalContents = client.downloadObject(decision.snapshot.objectSha256)
                        .toString(StandardCharsets.UTF_8)
                    check(
                        sha256(canonicalContents.toByteArray(StandardCharsets.UTF_8)) ==
                            decision.snapshot.sha256,
                    ) { "Current source content failed SHA-256 verification" }
                    writeSourceReceipt(
                        receipt,
                        requestId,
                        SourceEditQueue.ACTION_SAVE,
                        SourceEditQueue.STATUS_STALE,
                        applied = false,
                        path,
                        sourceLine,
                        decision.snapshot.sha256,
                        canonicalContents,
                        resolutionId,
                        message = "The canonical source advanced before this draft could be uploaded.",
                        draftRevision = request.safeInt("draft_revision"),
                    )
                    return null
                }
            }
            SourceBaseDecision.Missing -> throw PermanentSourceRequestException(
                "The canonical source no longer exists on the server.",
            )
        }
        val sequence = reserveSourceSequence(requestId)
        val sourceBytes = requireNotNull(contents).toByteArray(StandardCharsets.UTF_8)
        val sourceDigest = sha256(sourceBytes)
        val uploadedSha = if (client.hasObject(sourceDigest)) sourceDigest else {
            uploadSourceObject(client, sourceBytes)
        }
        val payload = SourceApplyRequest(
                operationId = requestId,
                deviceSequence = sequence,
                path = path,
                baseRevision = snapshot.revision,
                baseSha256 = snapshot.sha256,
                baseObjectSha256 = snapshot.objectSha256,
                contentSha256 = uploadedSha,
                clientTimestamp = request.string("created_at").ifBlank { Instant.now().toString() },
        )
        val claims = queue.requestProposalDecisions(request)
        val upload = ProposalDecisionClaims.forUpload(claims, path, contents)
        val (applyClient, exchange) = proposalDecisionClient(client, upload.send)
        val result = applyClient.applySource(payload)
        recordLogicalUpload(payload)
        if (result.outcome == "conflict") {
            writeSourceReceipt(
                receipt,
                requestId,
                SourceEditQueue.ACTION_SAVE,
                SourceEditQueue.STATUS_CONFLICT,
                applied = false,
                path,
                sourceLine,
                sourceDigest,
                contents,
                resolutionId,
                message = "The server preserved both overlapping source drafts for review.",
                conflictPath = result.conflictId,
                draftRevision = request.safeInt("draft_revision"),
                conflictCount = result.conflictCount,
                expectedRevision = result.revision,
                currentSha256 = result.sha256,
                incomingSha256 = sourceDigest,
                mergedObjectSha256 = result.mergedObjectSha256,
                currentModifiedAt = result.currentModifiedAt,
                incomingModifiedAt = result.incomingModifiedAt
                    ?: request.string("created_at").takeIf(String::isNotBlank),
            )
            return null
        }
        check(result.outcome in setOf("applied", "merged", "duplicate"))
        expireProposalDecisions(queue, path, claims, upload, exchange)
        val target = request.string("pdf_filename")
        requireSourceRequest(target.isSafePdfFilename(), "The source request has an invalid PDF filename.")
        val canonicalContents = verifiedCanonicalSourceBytes(
            localBytes = sourceBytes,
            resultSha256 = result.sha256,
            resultObjectSha256 = result.objectSha256,
            downloadObject = client::downloadObject,
        ).toString(StandardCharsets.UTF_8)
        dao.putSourceSnapshot(
            snapshot.copy(
                revision = result.revision,
                sha256 = result.sha256,
                objectSha256 = result.objectSha256,
            ),
        )
        writeSourceReceipt(
            receipt,
            requestId,
            SourceEditQueue.ACTION_SAVE,
            SourceEditQueue.STATUS_SUCCESS,
            applied = true,
            path,
            sourceLine,
            result.sha256,
            canonicalContents,
            resolutionId,
            draftRevision = request.safeInt("draft_revision"),
        )
        return if (request.buildAfterSync()) {
            SourceBuildIntent(target, result.serverSequence)
        } else {
            null
        }
    }

    private suspend fun resolveSourceConflict(
        client: SyncClient,
        request: JsonObject,
        receipt: File,
        queue: SourceEditQueue,
    ): SourceBuildIntent? {
        pdfChangingDeliveredThisSync = true
        val requestId = request.string("request_id")
        val conflictId = request.string("conflict_id")
        val path = request.string("source_file")
        val target = request.string("pdf_filename")
        val contents = request.safeString("contents")
        val expectedRevision = request.get("expected_revision")?.asLong
        val expectedSha256 = request.string("expected_sha256")
        val sourceLine = request.safeInt("source_line")?.takeIf { it > 0 } ?: 1
        val resolutionId = request.string("resolution_id")
        requireSourceRequest(requestId.isNotBlank(), "The conflict resolution has no request identity.")
        requireSourceRequest(conflictId.isNotBlank(), "The conflict resolution has no conflict identity.")
        requireSourceRequest(path.isNotBlank(), "The conflict resolution has no source path.")
        requireSourceRequest(target.isSafePdfFilename(), "The conflict resolution has an invalid PDF filename.")
        requireSourceRequest(expectedRevision != null && expectedRevision >= 0L,
            "The conflict resolution has an invalid source revision.")
        requireSourceRequest(expectedSha256.matches(SHA256),
            "The conflict resolution has an invalid source identity.")
        requireSourceRequest(contents != null, "The conflict resolution has no TeX contents.")
        val bytes = requireNotNull(contents).toByteArray(StandardCharsets.UTF_8)
        requireSourceRequest(bytes.size <= SourceEditQueue.MAX_SOURCE_BYTES,
            "The conflict resolution exceeds the supported size.")
        val contentSha256 = sha256(bytes)
        requireSourceRequest(
            request.string("content_sha256") == contentSha256,
            "The conflict resolution contents changed after they were saved.",
        )
        val uploadedSha256 = if (client.hasObject(contentSha256)) contentSha256 else {
            uploadSourceObject(client, bytes)
        }
        check(uploadedSha256 == contentSha256) { "The server stored the wrong conflict resolution object." }
        val sequence = reserveSourceSequence(requestId)
        val payload = ConflictResolveRequest(
                operationId = requestId,
                deviceSequence = sequence,
                expectedRevision = requireNotNull(expectedRevision),
                expectedSha256 = expectedSha256,
                contentSha256 = contentSha256,
                clientTimestamp = request.string("created_at").ifBlank { Instant.now().toString() },
        )
        val claims = queue.requestProposalDecisions(request)
        val upload = ProposalDecisionClaims.forUpload(claims, path, contents)
        val (resolveClient, exchange) = proposalDecisionClient(client, upload.send)
        val result = resolveClient.resolveConflict(conflictId, payload)
        recordLogicalUpload(payload)
        if (result.outcome == "conflict") {
            writeSourceReceipt(
                destination = receipt,
                requestId = requestId,
                action = SourceEditQueue.ACTION_RESOLVE_CONFLICT,
                status = SourceEditQueue.STATUS_CONFLICT,
                applied = false,
                sourceFile = path,
                sourceLine = sourceLine,
                sourceSha256 = contentSha256,
                contents = requireNotNull(contents),
                resolutionId = resolutionId,
                message = "The server advanced again and preserved a successor TeX conflict for review.",
                conflictPath = result.conflictId,
                draftRevision = request.safeInt("draft_revision"),
                conflictCount = result.conflictCount,
                expectedRevision = result.revision,
                currentSha256 = result.sha256,
                incomingSha256 = contentSha256,
                mergedObjectSha256 = result.mergedObjectSha256,
                currentModifiedAt = result.currentModifiedAt,
                incomingModifiedAt = result.incomingModifiedAt
                    ?: request.string("created_at").takeIf(String::isNotBlank),
            )
            return null
        }
        check(result.outcome in setOf("applied", "merged", "resolved", "duplicate"))
        expireProposalDecisions(queue, path, claims, upload, exchange)
        val canonicalContents = verifiedCanonicalSourceBytes(
            localBytes = bytes,
            resultSha256 = result.sha256,
            resultObjectSha256 = result.objectSha256,
            downloadObject = client::downloadObject,
        ).toString(StandardCharsets.UTF_8)
        dao.putSourceSnapshot(
            ThoughtsSourceSnapshotEntity(
                path = path,
                revision = result.revision,
                sha256 = result.sha256,
                objectSha256 = result.objectSha256,
                resolutionId = resolutionId,
            ),
        )
        writeSourceReceipt(
            destination = receipt,
            requestId = requestId,
            action = SourceEditQueue.ACTION_RESOLVE_CONFLICT,
            status = SourceEditQueue.STATUS_SUCCESS,
            applied = true,
            sourceFile = path,
            sourceLine = sourceLine,
            sourceSha256 = result.sha256,
            contents = canonicalContents,
            resolutionId = resolutionId,
            draftRevision = request.safeInt("draft_revision"),
        )
        return SourceBuildIntent(target, result.serverSequence)
    }

    private fun markPdfKeepingAcceptDelivered() {
        pdfKeepingAcceptDeliveredAtMillis = System.currentTimeMillis()
    }

    /**
     * A client whose source upload carries [claims] (agent-proposal spec section 10.4.2), only when
     * Fedora reads them; otherwise the ordinary client, and the claims simply expire with the answer.
     */
    private fun proposalDecisionClient(
        client: SyncClient,
        claims: List<ProposalDecisionClaim>,
    ): Pair<SyncClient, ProposalDecisionExchange?> {
        if (claims.isEmpty()) return client to null
        // A failed capability check fails this delivery (retried later) rather than expiring unsent claims.
        val supported = ProposalDecisionClaims.CAPABILITY in serverCapabilities(client)
        val config = secretStore.config()
        if (!supported || config == null) return client to null
        val exchange = ProposalDecisionExchange(gson, claims)
        // Without the Sync's trace this upload (and the rebuild it causes) was invisible to the
        // Sync that sent it: on 2026-10-10 it went out under a random trace (a81e3fcc).
        return SyncClient(config.baseUrl, config.token, exchange.gson, connectionFactory = connectionFactory)
            .setTraceId(activeSyncTraceId) to exchange
    }

    /** Fedora answered the upload: every claim it carried or dropped expires (section 10.4.2). */
    private fun expireProposalDecisions(
        queue: SourceEditQueue,
        path: String,
        claims: List<ProposalDecisionClaim>,
        upload: ProposalDecisionClaims.Upload,
        exchange: ProposalDecisionExchange?,
    ) {
        if (claims.isEmpty()) return
        if (upload.send.any { it.action == "accept" }) markPdfKeepingAcceptDelivered()
        val outcomes = exchange?.outcomes.orEmpty()
        android.util.Log.i(
            "ThoughtsSync",
            "proposal decisions: ${upload.send.size} ${if (exchange == null) "not sent" else "sent"}, " +
                "${upload.dropped.size} dropped (undone locally); outcomes " +
                outcomes.joinToString { "${it.proposalId}=${it.outcome}" }.ifBlank { "none" },
        )
        queue.expireProposalDecisions(path, claims.mapTo(hashSetOf()) { it.decisionId })
    }

    private suspend fun reserveSourceSequence(requestId: String): Long {
        return database.withTransaction {
            dao.sourceRequest(requestId)?.deviceSequence ?: run {
                val state = dao.state() ?: ThoughtsSyncStateEntity()
                val request = ThoughtsSourceRequestEntity(requestId, state.nextDeviceSequence)
                check(dao.insertSourceRequest(request) != -1L)
                dao.putState(state.copy(nextDeviceSequence = state.nextDeviceSequence + 1))
                request.deviceSequence
            }
        }
    }

    private fun syncConditionalRenderCheck(
        client: SyncClient,
        targetPdfFilename: String?,
        operationId: String?,
        traceEventsSupported: Boolean,
    ): RenderRequestSyncResult {
        val target = targetPdfFilename ?: PhoneRenderQueue.ACTION_ALL
        val requestId = operationId ?: UUID.randomUUID().toString()
        if (pdfKeepingAcceptDeliveredAtMillis != null && !pdfChangingDeliveredThisSync) {
            // Only Accepts: the kept PDF already shows the accepted text, so this Sync ends now
            // instead of waiting ~20 s for the rebuild that refreshes its SyncTeX (2 November).
            val check = client.checkRender(target, requestId)
            if (check.status != "current") {
                operationId?.let {
                    SyncOperationStore(appContext).append(
                        it,
                        SyncOperationStore.STATUS_SYNCING,
                        "The PDF for the accepted proposals is rebuilt in the background",
                    )
                }
                AcceptRebuildFollowUpWorker.enqueue(appContext, targetPdfFilename)
            }
            return RenderRequestSyncResult(active = false, builds = emptyList())
        }
        var waitingRecorded = false
        val check = awaitDeferredAcceptRebuild(
            initial = client.checkRender(target, requestId),
            acceptDeliveredAtMillis = pdfKeepingAcceptDeliveredAtMillis,
            nowMillis = System::currentTimeMillis,
            sleep = { millis ->
                if (!waitingRecorded && operationId != null) {
                    waitingRecorded = true
                    SyncOperationStore(appContext).append(
                        operationId,
                        SyncOperationStore.STATUS_SYNCING,
                        "Waiting for the server to queue the PDF rebuild for accepted proposals",
                    )
                }
                Thread.sleep(millis)
            },
            recheck = { client.checkRender(target, requestId) },
        )
        if (check.status == "current") {
            return RenderRequestSyncResult(active = false, builds = emptyList())
        }
        val missingBuildDetail = check.documents.joinToString("; ") { document ->
            listOf(document.filename, document.reason)
                .filter(String::isNotBlank)
                .joinToString(": ")
        }.ifBlank { "The server could not determine the required PDF update." }
        val initial = check.build ?: if (
            TerminalPdfBuildStatus.fromWireStatus(check.status) != null
        ) {
            return RenderRequestSyncResult(
                active = false,
                builds = emptyList(),
                terminalFailures = listOf(
                    TerminalPdfBuildFailure(
                        buildId = "",
                        status = requireNotNull(
                            TerminalPdfBuildStatus.fromWireStatus(check.status),
                        ),
                        detail = missingBuildDetail,
                    ),
                ),
            )
        } else {
            throw IOException(missingBuildDetail)
        }
        val observed = if (
            traceEventsSupported &&
            operationId != null &&
            initial.status in setOf("queued", "running")
        ) {
            // The check may report a build another request queued (an accept's deferred
            // rebuild, a Desktop Sync). Its events reach this trace only once GET links it.
            val linked = client.build(initial.id, 0)
            if (linked.status in setOf("queued", "running")) awaitBuildFromTrace(client, operationId, linked) else linked
        } else {
            initial
        }
        val progress = syncBuildProgress(observed)
        return when (observed.status) {
            "queued", "running" -> RenderRequestSyncResult(
                active = true,
                builds = listOf(progress),
            )
            "succeeded" -> RenderRequestSyncResult(
                active = false,
                builds = listOf(progress),
            )
            "attention", "failed", "cancelled", "compiler_failure" -> {
                cacheFailedBuildLog(client, observed)
                operationId?.let { id ->
                    SyncOperationStore(appContext).recordBuildProgress(
                        id,
                        listOf(progress),
                        buildsActive = false,
                    )
                }
                RenderRequestSyncResult(
                    active = false,
                    builds = listOf(progress),
                    terminalFailures = listOf(terminalPdfBuildFailure(observed)),
                )
            }
            else -> RenderRequestSyncResult(
                active = true,
                builds = listOf(progress),
            )
        }
    }

    private suspend fun syncRenderRequests(
        client: SyncClient,
        targetPdfFilename: String? = null,
        operationId: String? = null,
        traceEventsSupported: Boolean = false,
    ): RenderRequestSyncResult {
        val requestRoot = PhoneRenderQueue.defaultRequestDirectory()
        val receiptRoot = PhoneRenderQueue.defaultReceiptDirectory()
        cachePreviouslyReceivedFailedBuildLogs(client, receiptRoot)
        var activeBuilds = false
        val observedBuilds = mutableListOf<SyncBuildProgress>()
        val terminalFailures = mutableListOf<TerminalPdfBuildFailure>()
        val operationStore = operationId?.let { SyncOperationStore(appContext) }
        val knownBuildIds = operationId?.let { operationStore?.operation(it) }
            ?.buildProgress
            ?.mapTo(linkedSetOf(), SyncBuildProgress::buildId)
            .orEmpty()
        requestRoot.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty().sortedBy(File::lastModified).forEach { requestFile ->
            val request = runCatching { JsonParser.parseString(requestFile.readText()).asJsonObject }
                .getOrNull() ?: return@forEach
            val requestId = request.string("request_id")
                .takeIf {
                    it == requestFile.nameWithoutExtension &&
                        runCatching { UUID.fromString(it) }.isSuccess
                } ?: return@forEach
            val action = request.string("action")
            val target = when (action) {
                PhoneRenderQueue.ACTION_ALL -> PhoneRenderQueue.ACTION_ALL
                PhoneRenderQueue.ACTION_CHECK_ALL -> PhoneRenderQueue.ACTION_ALL
                PhoneRenderQueue.ACTION_PDF -> request.string("pdf_filename")
                    .takeIf { it.isSafePdfFilename() }
                else -> null
            } ?: return@forEach
            if (targetPdfFilename != null && target != targetPdfFilename) return@forEach
            val receipt = File(receiptRoot, "$requestId.json")
            if (receipt.isTerminalRenderReceipt()) {
                val payload = runCatching {
                    JsonParser.parseString(receipt.readText(StandardCharsets.UTF_8)).asJsonObject
                }.getOrNull()
                payload?.let {
                    terminalBuildFailureFromReceipt(it, operationId, knownBuildIds)
                }?.let(terminalFailures::add)
                return@forEach
            }

            val existing = dao.buildRequest(requestId)
            val build = if (existing == null) {
                val created = if (action == PhoneRenderQueue.ACTION_CHECK_ALL) {
                    val check = client.checkRender(target, requestId)
                    when (check.status) {
                        "current" -> {
                            writeCurrentRenderReceipt(receipt, request)
                            return@forEach
                        }
                        "attention" -> {
                            val terminal = check.build
                            if (terminal == null) {
                                val detail = "The current render inputs need explicit attention."
                                writeRenderCheckFailureReceipt(
                                    receipt,
                                    request,
                                    detail,
                                    operationId,
                                )
                                terminalFailures += TerminalPdfBuildFailure(
                                    buildId = "",
                                    status = TerminalPdfBuildStatus.ATTENTION,
                                    detail = detail,
                                )
                                return@forEach
                            }
                            observedBuilds += syncBuildProgress(terminal)
                            cacheFailedBuildLog(client, terminal)
                            writeRenderCheckAttentionReceipt(
                                receipt,
                                request,
                                terminal,
                                operationId,
                            )
                            terminalFailures += terminalPdfBuildFailure(terminal)
                            return@forEach
                        }
                        else -> check.build ?: throw IllegalStateException(
                            "Conditional render check did not return its active build",
                        )
                    }
                } else {
                    client.requestBuild(
                        target,
                        requestId,
                        request.get("source_frontier")?.asLong,
                        request.string("input_snapshot_sha256").ifBlank { null },
                    )
                }
                created.also {
                    dao.putBuildRequest(
                        ThoughtsBuildRequestEntity(requestId, it.id, target),
                    )
                }
            } else {
                // GET also links this trace to the existing immutable build on Fedora.
                // Resubmission can turn a scoped check into a new library rebuild.
                client.build(
                    existing.jobId,
                    if (traceEventsSupported && operationId != null) 0 else 30_000,
                )
            }
            val observedBuild = if (
                traceEventsSupported &&
                operationId != null &&
                build.status in setOf("queued", "running")
            ) {
                awaitBuildFromTrace(client, operationId, build)
            } else {
                build
            }
            observedBuilds += syncBuildProgress(observedBuild)
            if (
                observedBuild.status in setOf("queued", "running") &&
                observedBuild.updatedAt.isNotBlank() &&
                runCatching {
                    java.time.Instant.parse(observedBuild.updatedAt).isBefore(
                        java.time.Instant.now().minusSeconds(BUILD_HEARTBEAT_TIMEOUT_SECONDS),
                    )
                }.getOrDefault(false)
            ) {
                throw IOException("Build ${observedBuild.id} on the server sent no heartbeat for 90 seconds")
            }
            when (observedBuild.status) {
                "queued" -> {
                    activeBuilds = true
                    writeRenderReceipt(receipt, request, observedBuild, "running", operationId)
                }
                "running" -> {
                    activeBuilds = true
                    writeRenderReceipt(receipt, request, observedBuild, "running", operationId)
                }
                "succeeded" -> {
                    cacheFailedBuildLog(client, observedBuild)
                    writeRenderReceipt(receipt, request, observedBuild, "success", operationId)
                }
                "attention" -> {
                    cacheFailedBuildLog(client, observedBuild)
                    writeRenderReceipt(
                        receipt,
                        request,
                        observedBuild,
                        "partial_success",
                        operationId,
                    )
                    terminalFailures += terminalPdfBuildFailure(observedBuild)
                }
                "failed", "cancelled", "compiler_failure" -> {
                    cacheFailedBuildLog(client, observedBuild)
                    writeRenderReceipt(receipt, request, observedBuild, "failure", operationId)
                    terminalFailures += terminalPdfBuildFailure(observedBuild)
                }
                else -> activeBuilds = true
            }
        }
        if (terminalFailures.isNotEmpty()) {
            operationStore?.recordBuildProgress(
                operationId,
                observedBuilds.distinctBy(SyncBuildProgress::buildId),
                buildsActive = activeBuilds,
            )
        }
        return RenderRequestSyncResult(
            active = activeBuilds,
            builds = observedBuilds.distinctBy(SyncBuildProgress::buildId),
            terminalFailures = terminalFailures.distinct(),
        )
    }

    private fun awaitBuildFromTrace(
        client: SyncClient,
        operationId: String,
        initial: BuildRecord,
    ): BuildRecord {
        val store = SyncOperationStore(appContext)
        var streamFailures = 0
        while (true) {
            try {
                replayTraceEvents(client, operationId, store)
                store.operation(operationId)?.let { record ->
                    if (record.serverTerminal) {
                        store.markStreamState(operationId, SyncOperationStore.STREAM_SAVED)
                        return client.build(initial.id, 0)
                    }
                }
                store.markStreamState(operationId, SyncOperationStore.STREAM_LIVE)
                client.openTraceStream(operationId, store.serverCursor(operationId)).use { stream ->
                    while (true) {
                        val event = stream.nextEvent() ?: break
                        store.persistServerEvent(operationId, event)
                        if (event.terminal && event.buildId == initial.id) {
                            store.markStreamState(operationId, SyncOperationStore.STREAM_SAVED)
                            return client.build(initial.id, 0)
                        }
                    }
                }
                val snapshot = client.traceSnapshot(operationId)
                if (snapshot.terminal) {
                    store.markStreamState(operationId, SyncOperationStore.STREAM_SAVED)
                    return client.build(initial.id, 0)
                }
                throw IOException("The server trace stream ended before the build completed")
            } catch (_: Exception) {
                store.markStreamState(operationId, SyncOperationStore.STREAM_RECONNECTING)
                val backoffIndex = streamFailures.coerceAtMost(2)
                Thread.sleep(
                    (1L shl backoffIndex) * 1_000L +
                        operationId.hashCode().toLong().and(255L),
                )
                streamFailures++
                if (streamFailures < 3) continue

                // The journal transport is optional for correctness.  A disconnected
                // stream must never turn a still-running Fedora build into a failure.
                val observed = runCatching { client.build(initial.id, 30_000) }.getOrNull()
                if (observed != null && observed.status !in setOf("queued", "running")) {
                    store.markStreamState(operationId, SyncOperationStore.STREAM_SAVED)
                    return observed
                }
                streamFailures = 0
            }
        }
    }

    private fun replayTraceEvents(
        client: SyncClient,
        operationId: String,
        store: SyncOperationStore,
    ) {
        var cursor = store.serverCursor(operationId)
        do {
            val page = client.traceEvents(operationId, cursor, 500)
            page.events.forEach { event -> store.persistServerEvent(operationId, event) }
            cursor = store.serverCursor(operationId)
        } while (page.hasMore)
    }

    suspend fun streamTraceJournal(operationId: String) {
        require(runCatching { UUID.fromString(operationId) }.isSuccess)
        val config = requireNotNull(secretStore.config()) { "The Thoughts server is not configured" }
        val client = syncClient(config, operationId)
        val store = SyncOperationStore(appContext)
        if (TRACE_EVENTS_CAPABILITY !in serverCapabilities(client)) {
            store.markStreamState(operationId, SyncOperationStore.STREAM_SAVED)
            return
        }
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                replayTraceEvents(client, operationId, store)
                val record = store.operation(operationId)
                if (record?.serverTerminal == true) {
                    store.markStreamState(operationId, SyncOperationStore.STREAM_SAVED)
                    return
                }
                store.markStreamState(operationId, SyncOperationStore.STREAM_LIVE)
                client.openTraceStream(operationId, store.serverCursor(operationId)).use { stream ->
                    while (true) {
                        val event = stream.nextEvent() ?: break
                        store.persistServerEvent(operationId, event)
                        if (event.terminal) {
                            store.markStreamState(operationId, SyncOperationStore.STREAM_SAVED)
                            return
                        }
                    }
                }
                throw IOException("The server trace stream ended before a terminal event")
            } catch (error: Exception) {
                lastError = error
                store.markStreamState(operationId, SyncOperationStore.STREAM_RECONNECTING)
                delay((1L shl attempt) * 1_000L + (operationId.hashCode().toLong().and(255L)))
            }
        }
        store.markStreamState(operationId, SyncOperationStore.STREAM_SAVED)
        throw lastError ?: IOException("The server trace stream is unavailable")
    }

    private fun cachePreviouslyReceivedFailedBuildLogs(client: SyncClient, receiptRoot: File) {
        val store = LocalBuildLogStore()
        receiptRoot.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty().forEach { receipt ->
            val payload = runCatching {
                JsonParser.parseString(receipt.readText(StandardCharsets.UTF_8)).asJsonObject
            }.getOrNull() ?: return@forEach
            val failedDocuments = payload.getAsJsonArray("failed_documents") ?: JsonArray()
            if (payload.string("status") != "failure" && failedDocuments.size() == 0) {
                return@forEach
            }
            val logReference = payload.string("log_reference")
            val sha256 = serverLogSha256(logReference) ?: return@forEach
            if (store.isAvailable(logReference)) return@forEach
            runCatching {
                store.cache(logReference, client.downloadObject(sha256))
            }.onFailure { error ->
                Log.w("FedoraRepository", "Could not cache a previously received build log", error)
            }
        }
    }

    private fun cacheFailedBuildLog(client: SyncClient, build: BuildRecord) {
        val failedDocuments = build.result.getAsJsonArray("failed_documents") ?: JsonArray()
        if (build.status !in setOf("failed", "cancelled") && failedDocuments.size() == 0) return
        val sha256 = build.result.safeString("log_sha256")
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: return
        val logReference = "$SERVER_OBJECT_PREFIX$sha256"
        val store = LocalBuildLogStore()
        if (store.isAvailable(logReference)) return
        runCatching {
            store.cache(logReference, client.downloadObject(sha256))
        }.onFailure { error ->
            Log.w("FedoraRepository", "Could not cache the failed build log", error)
        }
    }

    private fun writeRenderReceipt(
        destination: File,
        request: JsonObject,
        build: org.tbend.thoughts.sdk.BuildRecord,
        status: String,
        operationId: String?,
    ) {
        val result = build.result
        val progress = build.progress.deepCopy().apply {
            if (build.status == "queued") addProperty("phase", "queued")
        }
        val payload = JsonObject().apply {
            addProperty("schema_version", PhoneRenderQueue.SCHEMA_VERSION)
            addProperty("request_id", request.string("request_id"))
            addProperty("build_id", build.id)
            addProperty("target", build.target)
            // Kept so saved diagnostics can tell what kind of work an old build did.
            addProperty("repair_mode", build.repairMode)
            addProperty("status", status)
            addProperty("server_status", build.status)
            if (status in setOf("partial_success", "failure")) {
                operationId?.let { addProperty("terminal_operation_id", it) }
            }
            addProperty("message", build.error ?: build.progress.string("detail"))
            addProperty("exit_code", if (status == "failure") 1 else 0)
            addProperty("started_at", request.string("created_at").ifBlank { Instant.now().toString() })
            if (status != "running") addProperty("completed_at", Instant.now().toString())
            add("progress", progress)
            add(
                "affected_pdfs",
                build.progress.get("documents")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.deepCopy()
                    ?: JsonArray(),
            )
            addProperty("attention_required", build.status == "attention")
            result.get("changes_detected")?.let { add("changes_detected", it.deepCopy()) }
            add(
                "published_pdfs",
                result.get("artifacts")?.takeIf { it.isJsonArray }?.asJsonArray?.deepCopy()
                    ?: JsonArray(),
            )
            add(
                "failed_documents",
                result.get("failed_documents")?.takeIf { it.isJsonArray }?.asJsonArray?.deepCopy()
                    ?: JsonArray(),
            )
            result.get("log_sha256")?.takeIf { it.isJsonPrimitive }?.let {
                addProperty("log_reference", "server-object:${it.asString}")
            }
        }
        atomicWrite(destination, gson.toJson(payload) + "\n")
    }

    private fun writeCurrentRenderReceipt(destination: File, request: JsonObject) {
        val payload = JsonObject().apply {
            addProperty("schema_version", PhoneRenderQueue.SCHEMA_VERSION)
            addProperty("request_id", request.string("request_id"))
            addProperty("target", PhoneRenderQueue.ACTION_ALL)
            addProperty("status", "success")
            addProperty("message", "PDFs are already current")
            addProperty("exit_code", 0)
            addProperty("started_at", request.string("created_at").ifBlank { Instant.now().toString() })
            addProperty("completed_at", Instant.now().toString())
            addProperty("changes_detected", false)
            add("published_pdfs", JsonArray())
            add("affected_pdfs", JsonArray())
            add("failed_documents", JsonArray())
        }
        atomicWrite(destination, gson.toJson(payload) + "\n")
    }

    private fun writeRenderCheckFailureReceipt(
        destination: File,
        request: JsonObject,
        message: String,
        operationId: String?,
    ) {
        val payload = JsonObject().apply {
            addProperty("schema_version", PhoneRenderQueue.SCHEMA_VERSION)
            addProperty("request_id", request.string("request_id"))
            addProperty("target", PhoneRenderQueue.ACTION_ALL)
            addProperty("status", "failure")
            addProperty("server_status", "attention")
            operationId?.let { addProperty("terminal_operation_id", it) }
            addProperty("message", message)
            addProperty("exit_code", 1)
            addProperty("started_at", request.string("created_at").ifBlank { Instant.now().toString() })
            addProperty("completed_at", Instant.now().toString())
            add("published_pdfs", JsonArray())
            add("affected_pdfs", JsonArray())
            add("failed_documents", JsonArray())
        }
        atomicWrite(destination, gson.toJson(payload) + "\n")
    }

    private fun writeRenderCheckAttentionReceipt(
        destination: File,
        request: JsonObject,
        build: org.tbend.thoughts.sdk.BuildRecord,
        operationId: String?,
    ) {
        val failedDocuments = build.result.get("failed_documents")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.deepCopy()
            ?: JsonArray()
        val payload = JsonObject().apply {
            addProperty("schema_version", PhoneRenderQueue.SCHEMA_VERSION)
            addProperty("request_id", request.string("request_id"))
            addProperty("build_id", build.id)
            addProperty("target", PhoneRenderQueue.ACTION_ALL)
            addProperty(
                "status",
                if (build.status == "attention") "partial_success" else "failure",
            )
            addProperty("server_status", build.status)
            operationId?.let { addProperty("terminal_operation_id", it) }
            addProperty("message", build.error ?: build.progress.string("detail"))
            addProperty("exit_code", if (build.status == "attention") 2 else 1)
            addProperty("started_at", request.string("created_at").ifBlank { Instant.now().toString() })
            addProperty("completed_at", Instant.now().toString())
            addProperty("changes_detected", false)
            addProperty("attention_required", true)
            add("published_pdfs", JsonArray())
            add(
                "affected_pdfs",
                build.progress.get("documents")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.deepCopy()
                    ?: JsonArray(),
            )
            add("failed_documents", failedDocuments)
            build.result.get("log_sha256")?.takeIf { it.isJsonPrimitive }?.let {
                addProperty("log_reference", "server-object:${it.asString}")
            }
        }
        atomicWrite(destination, gson.toJson(payload) + "\n")
    }

    private fun File.isTerminalRenderReceipt(): Boolean {
        if (!isFile) return false
        val status = runCatching {
            JsonParser.parseString(readText()).asJsonObject.string("status")
        }.getOrNull()
        return status in setOf("success", "partial_success", "failure")
    }

    private fun writeSourceReceipt(
        destination: File,
        requestId: String,
        action: String,
        status: String,
        applied: Boolean,
        sourceFile: String,
        sourceLine: Int,
        sourceSha256: String,
        contents: String,
        resolutionId: String,
        message: String = "",
        conflictPath: String? = null,
        draftRevision: Int? = null,
        conflictCount: Int? = null,
        expectedRevision: Long? = null,
        currentSha256: String? = null,
        incomingSha256: String? = null,
        mergedObjectSha256: String? = null,
        currentModifiedAt: String? = null,
        incomingModifiedAt: String? = null,
    ) {
        val payload = JsonObject().apply {
            addProperty(
                "schema_version",
                if (action == SourceEditQueue.ACTION_RESOLVE_CONFLICT || conflictPath != null) {
                    SourceEditQueue.CONFLICT_SCHEMA_VERSION
                } else if (draftRevision == null) {
                    SourceEditQueue.SCHEMA_VERSION
                } else {
                    SourceEditQueue.MERGE_SCHEMA_VERSION
                },
            )
            addProperty("request_id", requestId)
            addProperty("action", action)
            addProperty("status", status)
            addProperty("message", message)
            addProperty("applied", applied)
            addProperty("resolution_id", resolutionId)
            addProperty("source_file", sourceFile)
            addProperty("source_line", sourceLine)
            addProperty("source_sha256", sourceSha256)
            addProperty("contents", contents)
            conflictPath?.let { addProperty("conflict_path", it) }
            draftRevision?.let { addProperty("draft_revision", it) }
            conflictCount?.let { addProperty("conflict_count", it) }
            expectedRevision?.let { addProperty("expected_revision", it) }
            currentSha256?.let { addProperty("current_sha256", it) }
            incomingSha256?.let { addProperty("incoming_sha256", it) }
            mergedObjectSha256?.let { addProperty("merged_object_sha256", it) }
            currentModifiedAt?.let { addProperty("current_modified_at", it) }
            incomingModifiedAt?.let { addProperty("incoming_modified_at", it) }
        }
        atomicWrite(destination, gson.toJson(payload) + "\n")
    }

    private fun writeSourceFailureReceipt(
        destination: File,
        requestId: String,
        action: String,
        message: String,
        request: JsonObject? = null,
    ) {
        writeSourceReceipt(
            destination = destination,
            requestId = requestId,
            action = action,
            status = SourceEditQueue.STATUS_FAILURE,
            applied = false,
            sourceFile = request?.safeString("source_file").orEmpty(),
            sourceLine = request?.safeInt("source_line")?.takeIf { it > 0 } ?: 1,
            sourceSha256 = request?.safeString("base_sha256").orEmpty(),
            contents = request?.safeString("contents").orEmpty(),
            resolutionId = request?.safeString("resolution_id").orEmpty(),
            message = message.ifBlank { "The source request could not be processed." },
            draftRevision = request?.safeInt("draft_revision"),
        )
    }

    private fun ChangeRecord.toProjection(): ThoughtsProjectionEntity {
        return ThoughtsProjectionEntity(
            entityType = entityType.canonicalSyncEntityType(),
            collection = collection,
            entityId = entityId,
            revision = entityRevision ?: payload.get("revision")?.asLong ?: 1,
            deleted = payload.get("deleted")?.asBoolean == true,
            payloadJson = payload.toString(),
            serverSequence = serverSequence,
        )
    }

    private fun ProjectionRecord.toProjection(serverSequence: Long): ThoughtsProjectionEntity {
        return ThoughtsProjectionEntity(
            entityType = entityType.canonicalSyncEntityType(),
            collection = collection,
            entityId = entityId,
            revision = revision,
            deleted = deleted || payload.get("deleted")?.asBoolean == true,
            payloadJson = payload.toString(),
            serverSequence = serverSequence,
        )
    }

    private suspend fun materialize(
        projections: List<ThoughtsProjectionEntity>,
        client: SyncClient,
        deletions: List<ThoughtsProjectionEntity> = emptyList(),
        transferPlan: SyncTransferPlan? = null,
    ) {
        val commentsRoot = CommentSidecarStore.defaultSidecarRoot()
        val pendingDeliveries = CommentDeliveryOutbox.forContext(appContext).load()
        val pendingOperations = dao.undeliveredByEntityType("annotation")
        val annotationSidecars = materializedAnnotationSidecars(
            projections,
            pendingDeliveries,
            pendingOperations,
            gson,
        )
        annotationSidecars.forEach { (collection, contents) ->
            atomicWrite(
                File(commentsRoot, "${collection}_comments.jsonl"),
                contents,
            )
        }
        deletions.filter { it.entityType == "annotation" }
            .map(ThoughtsProjectionEntity::collection)
            .filter { it.isSafeAnnotationCollection() && it !in annotationSidecars }
            .distinct()
            .forEach { collection ->
                atomicWrite(File(commentsRoot, "${collection}_comments.jsonl"), "")
            }
        val resolutions = projections.filter { it.entityType == "resolution" }
        if (resolutions.isNotEmpty() || deletions.any { it.entityType == "resolution" }) {
                atomicWrite(
                    File(commentsRoot, "review_resolutions.jsonl"),
                    resolutions.sortedBy { it.entityId }.joinToString("\n") { it.payloadJson }
                        .let { if (it.isEmpty()) it else "$it\n" },
                )
        }
        refreshReviewProjections()
    }

    internal suspend fun refreshReviewProjections(
        activatedPdfSha256ByFilename: Map<String, String> =
            SourceLinkedPdfIndex().snapshot().activatedPdfSha256ByFilename,
        diagnostics: SyncDiagnostics? = null,
    ) {
        val root = ReviewStore.defaultIncomingRoot()
        val read = suspend {
            database.withTransaction {
                dao.reviewProjectionIdentities() to dao.changedReviewProjections(root.path)
            }
        }
        val (identities, changed) = if (diagnostics == null) read()
            else diagnostics.measureSuspending("read_review_projections", read)
        val retained = identities.mapNotNullTo(hashSetOf()) {
            reviewProjectionPath(root, it.entityType, it.entityId)
        }
        diagnostics?.count("review_projection_work", "projections" to changed.size.toLong(),
            "retained_identities" to identities.size.toLong(),
            "active_pdfs" to activatedPdfSha256ByFilename.size.toLong())
        val apply = { materializeReviewProjections(changed, activatedPdfSha256ByFilename, retained, diagnostics) }
        if (diagnostics == null) apply() else diagnostics.measure("materialize_review_projections", apply)
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagMerges.installServer(
            dao.reviewTagMergeProjections().mapNotNull { runCatching { JsonParser.parseString(it.payloadJson).asJsonObject }.getOrNull() },
        )
        com.gitlab.mudlej.MjPdfReader.data.review.ReviewTagGroups.installServer(
            dao.reviewTagGroupsProjection()?.let { runCatching { JsonParser.parseString(it.payloadJson).asJsonObject }.getOrNull() },
        )
        runCatching { installPdfLinkOverrides() }
            .onFailure { Log.w("ThoughtsLinkOverrides", "Could not install PDF link overrides", it) }
        runCatching { installReviewCardFeedback() }
            .onFailure { Log.w("ThoughtsCardFeedback", "Could not install review card feedback", it) }
        runCatching { installDocumentFeedback() }
            .onFailure { Log.w("ThoughtsDocumentFeedback", "Could not install document feedback", it) }
        runCatching { installAgentConversations() }
            .onFailure { Log.w("ThoughtsAgentConversations", "Could not settle agent conversation removals", it) }
    }

    private fun reviewProjectionPath(root: File, entityType: String, entityId: String): String? {
        if (!entityId.isSafeComponent()) return null
        val directory = when (entityType) {
            "review_card" -> "cards"
            "review_event" -> "events"
            "review_exclusion" -> "exclusions"
            else -> return null
        }
        return File(root, "$directory/server/$entityId.json").path
    }

    private fun materializeReviewProjections(
        projections: List<ThoughtsProjectionEntity>,
        activatedPdfSha256ByFilename: Map<String, String> =
            SourceLinkedPdfIndex().snapshot().activatedPdfSha256ByFilename,
        retainedPaths: Set<String> = emptySet(),
        diagnostics: SyncDiagnostics? = null,
    ) {
        val reviewRoot = ReviewStore.defaultIncomingRoot()
        val reviewStore = ReviewStore(appContext)
        val directories = mapOf("review_card" to "cards", "review_event" to "events", "review_exclusion" to "exclusions")
        val retained = retainedPaths.toHashSet()
        val records = projections.mapNotNull { projection ->
            val directory = directories[projection.entityType] ?: return@mapNotNull null
            if (!projection.entityId.isSafeComponent() || projection.deleted) return@mapNotNull null
            val path = File(reviewRoot, "$directory/server/${projection.entityId}.json").path
            if (projection.entityType == "review_card" && !reviewCardProjectionMatchesActivatedPdf(projection.payloadJson, activatedPdfSha256ByFilename)) {
                retained += path // Successful placement waits for its matching PDF.
                return@mapNotNull null
            }
            Triple(path, directory, JsonParser.parseString(projection.payloadJson).asJsonObject)
        }
        diagnostics?.count("review_projection_records", "installed" to records.size.toLong(), "retained" to retained.size.toLong())
        check(diagnostics?.measure("install_review_server_records") { reviewStore.installServerRecords(records, retained) }
            ?: reviewStore.installServerRecords(records, retained))
    }

    private fun verifyProjectReadings(
        snapshot: LibrarySnapshot, directory: File, hashes: Boolean,
        reusedObjects: Map<String, File> = emptyMap(),
    ) {
        val filenames = mutableSetOf<String>()
        val projects = mutableSetOf<String>()
        snapshot.projectReadings.forEach { entry ->
            val filename = entry.string("filename")
            check(filename.matches(Regex("project_[0-9a-f]{24}\\.thoughts-project")) && filenames.add(filename))
            val manifest = File(directory, filename)
            check(manifest.isFile && manifest.length() == entry.get("size_bytes").asLong)
            if (hashes) check(fileSha256(manifest) == entry.string("sha256"))
            val reading = ProjectReading.parse(manifest.readBytes())
            check(reading.id == entry.string("project_id") && projects.add(reading.id))
            check(reading.pageCount == entry.get("page_count").asInt)
            reading.documents.forEach { document ->
                val pdf = reusedObjects["objects/${document.pdfSha256}"] ?: File(directory, "objects/${document.pdfSha256}")
                val pack = reusedObjects["objects/${document.packageSha256}"] ?: File(directory, "objects/${document.packageSha256}")
                check(pdf.isFile && pdf.length() == document.size && pack.isFile)
                if (hashes) {
                    check(fileSha256(pdf) == document.pdfSha256)
                    check(fileSha256(pack) == document.packageSha256)
                }
            }
        }
        check(directory.listFiles().orEmpty().filter { it.name.endsWith(".thoughts-project") }.map { it.name }.toSet() == filenames)
    }

    /**
     * Stage every project reading. Objects the active generation already holds are not
     * copied: Android forbids hard links to app files, so copying them cost seconds per
     * Sync. They are listed as reused and move into the new generation on activation.
     */
    private fun prepareProjectReadings(snapshot: LibrarySnapshot, staging: File, current: File,
                                      client: SyncClient, plan: SyncTransferPlan?): Set<String> {
        val objects = File(staging, "objects").apply { mkdirs() }
        // Every place the sync plan reported as known, including PDFs the new Library no longer lists.
        val currentGeneration = readGenerationManifest(current).publications
        val reused = linkedSetOf<String>()
        fun obtain(hash: String, size: Long?, role: String, destination: File) {
            check(hash.matches(SHA256))
            val candidates = listOf(File(current, "objects/$hash")) +
                LibraryObjectInventory.OBJECT_STORES.map { File(appContext.filesDir, "$it/$hash") } +
                snapshot.publications.filter { it.sha256 == hash }.flatMap {
                    listOf(File(staging, it.filename), File(current, it.filename))
                } + currentGeneration.filterValues { it.first == hash }.keys.map { File(current, it) }
            val source = candidates.firstOrNull { it.isFile && (size == null || it.length() == size) && fileSha256(it) == hash }
            if (source != null) linkOrCopy(source, destination)
            else {
                plan?.requireRemoteObject(hash, kind = "source", role = role)
                downloadPublication(client, hash, destination, plan?.serverPlanId)
            }
            check(destination.isFile && (size == null || destination.length() == size) && fileSha256(destination) == hash)
        }
        fun stageObject(hash: String, size: Long?, role: String) {
            val name = "objects/$hash"
            if (name in reused || File(objects, hash).isFile) return
            val active = File(current, name)
            if (active.isFile && (size == null || active.length() == size) && fileSha256(active) == hash) reused += name
            else obtain(hash, size, role, File(objects, hash))
        }
        snapshot.projectReadings.forEach { entry ->
            val filename = entry.string("filename")
            check(filename.matches(Regex("project_[0-9a-f]{24}\\.thoughts-project")))
            val manifest = File(staging, filename)
            val currentManifest = File(current, filename)
            val hash = entry.string("sha256")
            if (currentManifest.isFile && fileSha256(currentManifest) == hash) linkOrCopy(currentManifest, manifest)
            else obtain(hash, entry.get("size_bytes").asLong, "project_reading", manifest)
            val reading = ProjectReading.parse(manifest.readBytes())
            reading.documents.forEach { document ->
                stageObject(document.pdfSha256, document.size, "document_pdf")
                stageObject(document.packageSha256, null, "document_reading_package")
            }
        }
        verifyProjectReadings(snapshot, staging, true, reused.associateWith { File(current, it) })
        return reused
    }

    private fun syncLibrary(
        snapshot: LibrarySnapshot,
        client: SyncClient,
        transferPlan: SyncTransferPlan?,
    ) {
        require(snapshot.libraryRevision.matches(SHA256)) { "The server returned an invalid Library revision" }
        val sourceIndexSha256 = snapshot.sourceIndexSha256
            ?.takeIf(SHA256::matches)
            ?: error("The server returned no exact source-layout index")
        val progress = syncDiagnosticProgress(appContext, transferPlan?.operationId)
        val publications = snapshot.publications.associateBy { publication ->
            require(publication.filename.isSafePdfFilename()) { "The server returned an invalid Library filename" }
            require(
                publication.generationId.matches(SHA256) &&
                    publication.sha256.matches(SHA256) &&
                    publication.sizeBytes >= 0
            ) {
                "The server returned an invalid Library publication identity"
            }
            val kind = publication.metadata.string("kind").ifBlank { "source_linked" }
            require(kind == "source_linked" || kind == "unlinked") { "The server returned an invalid Library kind" }
            publication.filename
        }
        check(publications.size == snapshot.publications.size) { "The server returned duplicate Library filenames" }

        val root = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val parent = requireNotNull(root.parentFile)
        parent.mkdirs()
        val staging = File(parent, ".thoughts-library-stage-${snapshot.libraryRevision}")
        val previous = File(parent, ".thoughts-library-previous")
        val journal = File(parent, ".thoughts-library-activation.json")

        recoverInterruptedActivation(snapshot, root, staging, previous, journal)
        if (directoryMatchesLibrary(snapshot, root, verifyHashes = false)) {
            File(appContext.filesDir, "source-editor/offline/active-index.json").takeIf(File::isFile)?.let {
                atomicWrite(File(root, ".thoughts-project-sources.json"), it.readBytes())
            }
            staging.takeIf(File::exists)?.deleteRecursively()
            journal.delete()
            PdfWarmCacheWorker.enqueue(appContext)
            return
        }

        check(!staging.exists() || staging.deleteRecursively()) { "Could not clear stale Library staging" }
        check(staging.mkdirs()) { "Could not create Library staging" }
        val currentGeneration = readGenerationManifest(root)
        val verifiedCurrentPdfFiles = verifiedGenerationPdfFiles(root, currentGeneration.publications)
        val locallyAddedPdfs = verifiedLocallyAddedPdfs()
        val reusedPdfs = linkedSetOf<String>()
        var switched = false
        try {
            measureSyncStage("Preparing PDF files (reuse or download)", progress) {
                val downloads = mutableListOf<Pair<LibraryEntry, File>>()
                snapshot.publications.forEach { publication ->
                    val destination = File(staging, publication.filename)
                    val reusableFilename = currentGeneration.publications.entries
                        .firstOrNull { (_, identity) ->
                            identity.first == publication.sha256 && identity.second == publication.sizeBytes
                        }
                        ?.key
                    val source = reusableFilename?.let { File(root, it) }
                    val trustedCurrent = reusableFilename != null
                    if (
                        trustedCurrent && source?.isFile == true && source.length() == publication.sizeBytes &&
                        (transferPlan == null || publication.sha256 in verifiedCurrentPdfFiles)
                    ) {
                        if (reusableFilename == publication.filename) {
                            reusedPdfs += publication.filename
                        } else {
                            linkOrCopy(source, destination)
                        }
                    } else {
                        // A PDF this phone added to the Library is copied from its verified local copy.
                        val added = locallyAddedPdfs[publication.sha256]
                            ?.takeIf { it.isFile && it.length() == publication.sizeBytes }
                        if (added != null) linkOrCopy(added, destination)
                        else downloads += publication to destination
                    }
                }
                // Each download writes its own staging file; one at a time, 150 PDFs took 56 s.
                val completedDownloads = java.util.concurrent.atomic.AtomicInteger()
                val reportedAt = java.util.concurrent.atomic.AtomicLong()
                parallelContentObjects(downloads) { (publication, destination) ->
                    downloadOrPatchPublication(
                        client,
                        publication,
                        eligiblePdfDelta(publication, verifiedCurrentPdfFiles.keys)
                            ?.baseSha256
                            ?.let(verifiedCurrentPdfFiles::get)
                            ?: File(root, publication.filename),
                        destination,
                        transferPlan = transferPlan,
                    )
                    // Report the count at most twice a second instead of every PDF's steps.
                    val done = completedDownloads.incrementAndGet()
                    val now = System.nanoTime()
                    val last = reportedAt.get()
                    if (done == downloads.size || (now - last >= 500_000_000L && reportedAt.compareAndSet(last, now))) {
                        progress("Updated PDF $done of ${downloads.size}")
                    }
                }
                snapshot.publications.forEach { publication ->
                    val destination = File(staging, publication.filename)
                    val source = currentGeneration.publications.entries
                        .firstOrNull { (_, identity) ->
                            identity.first == publication.sha256 && identity.second == publication.sizeBytes
                        }
                        ?.key
                        ?.let { File(root, it) }
                    val stagedPdf = if (publication.filename in reusedPdfs) requireNotNull(source) else destination
                    check(stagedPdf.length() == publication.sizeBytes) {
                        "Staged PDF ${publication.filename} failed identity verification"
                    }
                    check(!stagedPdf.canWrite() || stagedPdf.setReadOnly()) {
                        "The staged PDF from the server could not be marked read-only"
                    }
                }

            }
            val sourceIndexDestination = File(staging, SourceLinkedPdfIndex.INDEX_FILENAME)
            val currentSourceIndex = File(root, SourceLinkedPdfIndex.INDEX_FILENAME)
            measureSyncStage("Preparing the PDF source index", progress) {
                if (
                    currentGeneration.sourceIndexSha256 == sourceIndexSha256 && currentSourceIndex.isFile &&
                    (transferPlan == null || fileSha256(currentSourceIndex) == sourceIndexSha256)
                ) {
                    linkOrCopy(currentSourceIndex, sourceIndexDestination)
                } else {
                    val planned = transferPlan?.requireRemoteObject(
                        sourceIndexSha256,
                        kind = "source_index",
                        role = "source_index",
                    )
                    downloadPublication(
                        client,
                        sourceIndexSha256,
                        sourceIndexDestination,
                        transferPlan?.serverPlanId,
                    )
                    check(planned == null || sourceIndexDestination.length() == planned.sizeBytes) {
                        "Downloaded source index failed planned size verification"
                    }
                }
            }
            val reusedObjects = measureSyncStage("Preparing project readings", progress) {
                prepareProjectReadings(snapshot, staging, root, client, transferPlan)
            }
            val sourceIndexBytes = sourceIndexDestination.readBytes()
            SyncOperationStore(appContext).append(
                transferPlan?.operationId, SyncOperationStore.STATUS_SYNCING,
                "Verifying downloaded PDFs and source index",
            )
            measureSyncStage("Verifying PDFs and writing generation indexes", progress) {
                check(sha256(sourceIndexBytes) == sourceIndexSha256) { "Staged source-layout index has the wrong identity" }
                validateSourceIndex(sourceIndexBytes, snapshot)

                atomicWrite(
                    File(staging, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME),
                    gson.toJson(libraryIndex(snapshot)) + "\n",
                )
                atomicWrite(
                    File(staging, SourceLinkedPdfIndex.GENERATION_FILENAME),
                    gson.toJson(generationManifest(snapshot)) + "\n",
                )
                check(directoryMatchesLibrary(snapshot, staging, verifyHashes = false,
                    reusedPdfs = reusedPdfs.associateWith { File(root, it) },
                    reusedObjects = reusedObjects.associateWith { File(root, it) })) {
                    "The staged Library generation failed exact verification"
                }

            }
            File(appContext.filesDir, "source-editor/offline/active-index.json").takeIf(File::isFile)?.let {
                atomicWrite(File(staging, ".thoughts-project-sources.json"), it.readBytes())
            }
            atomicWrite(
                journal,
                gson.toJson(JsonObject().apply {
                    addProperty("schema_version", 1)
                    add("reused_pdfs", gson.toJsonTree(reusedPdfs + reusedObjects))
                    addProperty("library_revision", snapshot.libraryRevision)
                    addProperty("staging_path", staging.absolutePath)
                    addProperty("previous_path", previous.absolutePath)
                }) + "\n",
            )
            check(!previous.exists() || previous.deleteRecursively()) { "Could not retire the older Library rollback generation" }
            SyncOperationStore(appContext).append(
                transferPlan?.operationId, SyncOperationStore.STATUS_SYNCING,
                "Activating updated PDFs",
            )
            measureSyncStage("Switching the active PDF generation", progress) {
                if (root.exists()) Os.rename(root.absolutePath, previous.absolutePath)
                try {
                    moveReusedLibraryFiles(previous, staging, reusedPdfs + reusedObjects)
                    Os.rename(staging.absolutePath, root.absolutePath)
                    switched = true
                } catch (error: Throwable) {
                    restoreReusedLibraryFiles(root, staging, previous, journal)
                    if (!root.exists() && previous.exists()) Os.rename(previous.absolutePath, root.absolutePath)
                    throw error
                }
                check(directoryMatchesLibrary(snapshot, root, verifyHashes = false)) {
                    "The activated Library generation failed verification"
                }
                journal.delete()
            }
            // Preview rendering is optional and must not delay artifact activation.
            runCatching { PdfWarmCacheWorker.enqueue(appContext) }
        } catch (error: Throwable) {
            if (switched && journal.isFile) {
                restoreReusedLibraryFiles(root, staging, previous, journal)
                val failed = File(parent, ".thoughts-library-failed-${UUID.randomUUID()}")
                Os.rename(root.absolutePath, failed.absolutePath)
                if (previous.exists()) Os.rename(previous.absolutePath, root.absolutePath)
                failed.deleteRecursively()
                journal.delete()
            }
            if (root.exists()) staging.deleteRecursively()
            throw error
        }
    }

    private data class TargetPublicationActivation(
        val publication: LibraryEntry,
        val root: File,
        val previous: File,
        val journal: File,
    )

    /** Stages and atomically activates one exact publication using only local membership facts. */
    private fun activateTargetPublication(
        client: SyncClient,
        publication: LibraryEntry,
        transferPlan: SyncTransferPlan,
    ): TargetPublicationActivation {
        val progress = syncDiagnosticProgress(appContext, transferPlan.operationId)
        require(publication.filename.isSafePdfFilename())
        requireValidPublicationIdentity(publication)

        val root = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val parent = requireNotNull(root.parentFile).apply { mkdirs() }
        val currentManifestFile = File(root, SourceLinkedPdfIndex.GENERATION_FILENAME)
        val currentManifest = runCatching {
            JsonParser.parseString(currentManifestFile.readText(StandardCharsets.UTF_8)).asJsonObject
        }.getOrDefault(JsonObject())
        val currentPublications = (currentManifest.getAsJsonArray("publications") ?: JsonArray())
            .map { value -> gson.fromJson(value, LibraryEntry::class.java) }
        val untrackedPdfs = root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            .map(File::getName)
            .filter { filename -> currentPublications.none { it.filename == filename } }
        check(untrackedPdfs.isEmpty()) {
            "The active Library has PDFs outside its verified generation; use Sync All to repair membership"
        }
        currentPublications.forEach { current ->
            require(
                current.filename.isSafePdfFilename() &&
                    current.generationId.matches(SHA256) &&
                    current.sha256.matches(SHA256) &&
                    current.sizeBytes >= 0
            ) { "The active Library generation contains an invalid publication" }
        }
        val nextPublications = (currentPublications.filter { it.filename != publication.filename } + publication)
            .sortedBy(LibraryEntry::filename)
        val verifiedCurrentPdfFiles = verifiedGenerationPdfFiles(
            root,
            readGenerationManifest(root).publications,
            requiredHashes = setOfNotNull(
                eligiblePdfDelta(publication, currentPublications.map { it.sha256 }.toSet())?.baseSha256,
            ),
        )

        val sourceIndexFile = File(root, SourceLinkedPdfIndex.INDEX_FILENAME)
        val sourceIndex = if (sourceIndexFile.isFile) {
            JsonParser.parseString(sourceIndexFile.readText(StandardCharsets.UTF_8)).asJsonObject
        } else {
            check(currentPublications.isEmpty()) {
                "The active source-layout index is missing; use Sync All to repair it"
            }
            JsonObject().apply {
                addProperty("schema_version", 1)
                add("pdfs", JsonArray())
            }
        }
        check(sourceIndex.get("schema_version")?.asInt == 1)
        val retainedSourceEntries = (sourceIndex.getAsJsonArray("pdfs") ?: JsonArray())
            .map { it.asJsonObject.deepCopy() }
            .filter { it.string("filename") != publication.filename }
            .toMutableList()
        if (publication.metadata.string("kind").ifBlank { "source_linked" } == "source_linked") {
            retainedSourceEntries += JsonObject().apply {
                addProperty("filename", publication.filename)
                addProperty("project", publication.project)
                addProperty("pdf_sha256", publication.sha256)
            }
        }
        sourceIndex.add("pdfs", JsonArray().apply {
            retainedSourceEntries.sortedBy { it.string("filename") }.forEach(::add)
        })
        val sourceIndexBytes = gson.toJson(sourceIndex).toByteArray(StandardCharsets.UTF_8)
        val sourceIndexSha256 = sha256(sourceIndexBytes)
        val revisionInput = gson.toJson(
            JsonObject().apply {
                add("publications", gson.toJsonTree(nextPublications))
                addProperty("source_index_sha256", sourceIndexSha256)
            },
        ).toByteArray(StandardCharsets.UTF_8)
        val revision = sha256(revisionInput)
        val snapshot = LibrarySnapshot(
            publications = nextPublications,
            latestServerSequence = 0,
            sourceIndexSha256 = sourceIndexSha256,
            sourceBundleSha256 = publication.sourceArtifactSha256,
            artifactRevision = revision,
            libraryRevision = revision,
        )
        validateSourceIndex(sourceIndexBytes, snapshot)

        val staging = File(parent, ".thoughts-library-stage-$revision")
        val previous = File(parent, ".thoughts-library-previous")
        val journal = File(parent, ".thoughts-library-activation.json")
        check(!staging.exists() || staging.deleteRecursively()) {
            "Could not clear the stale targeted Library staging generation"
        }
        check(staging.mkdirs()) { "Could not create the targeted Library staging generation" }
        val reusedPdfs = linkedSetOf<String>()
        var switched = false
        try {
            SyncOperationStore(appContext).append(
                transferPlan.operationId, SyncOperationStore.STATUS_SYNCING,
                "Verifying the updated Library generation",
            )
            nextPublications.forEach { next ->
                val destination = File(staging, next.filename)
                if (next.filename == publication.filename) {
                    val current = File(root, next.filename)
                    if (
                        current.isFile && current.length() == next.sizeBytes &&
                        fileSha256(current) == next.sha256
                    ) {
                        reusedPdfs += next.filename
                    } else {
                        downloadOrPatchPublication(
                            client,
                            next,
                            eligiblePdfDelta(next, verifiedCurrentPdfFiles.keys)
                                ?.baseSha256
                                ?.let(verifiedCurrentPdfFiles::get)
                                ?: current,
                            destination,
                            transferPlan = transferPlan,
                        )
                    }
                } else {
                    // Preserve unrelated projects as they are, including unavailable PDFs.
                    // Their own reader/sync reports or repairs their state independently.
                    if (File(root, next.filename).isFile) reusedPdfs += next.filename
                    return@forEach
                }
                val stagedPdf = if (next.filename in reusedPdfs) File(root, next.filename) else destination
                check(
                    stagedPdf.isFile && stagedPdf.length() == next.sizeBytes &&
                        fileSha256(stagedPdf) == next.sha256
                ) { "Staged PDF ${next.filename} failed exact verification" }
                check(!stagedPdf.canWrite() || stagedPdf.setReadOnly()) {
                    "Staged PDF ${next.filename} could not be marked read-only"
                }
            }
            atomicWrite(File(staging, SourceLinkedPdfIndex.INDEX_FILENAME), sourceIndexBytes)
            atomicWrite(
                File(staging, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME),
                gson.toJson(libraryIndex(snapshot)) + "\n",
            )
            atomicWrite(
                File(staging, SourceLinkedPdfIndex.GENERATION_FILENAME),
                gson.toJson(generationManifest(snapshot)) + "\n",
            )
            // The changed project was verified above; the coordinator preserves other identities.
            check(directoryMatchesLibrary(snapshot, staging, verifyHashes = false, reusedPdfs = reusedPdfs.associateWith { File(root, it) }, verifyFilenames = setOf(publication.filename))) {
                "The targeted Library staging generation failed verification"
            }
            File(appContext.filesDir, "source-editor/offline/active-index.json").takeIf(File::isFile)?.let {
                atomicWrite(File(staging, ".thoughts-project-sources.json"), it.readBytes())
            }
            atomicWrite(
                journal,
                gson.toJson(JsonObject().apply {
                    addProperty("schema_version", 1)
                    addProperty("targeted", true)
                    add("reused_pdfs", gson.toJsonTree(reusedPdfs))
                    addProperty("library_revision", revision)
                    addProperty("staging_path", staging.absolutePath)
                    addProperty("previous_path", previous.absolutePath)
                }) + "\n",
            )
            check(!previous.exists() || previous.deleteRecursively()) {
                "Could not retire the older Library rollback generation"
            }
            SyncOperationStore(appContext).append(
                transferPlan.operationId, SyncOperationStore.STATUS_SYNCING,
                "Activating the updated Library generation",
            )
            measureSyncStage("Switching the active PDF generation", progress) {
                if (root.exists()) Os.rename(root.absolutePath, previous.absolutePath)
                moveReusedLibraryFiles(previous, staging, reusedPdfs)
                Os.rename(staging.absolutePath, root.absolutePath)
                switched = true
                // rename preserves the already-verified files; interrupted recovery still
                // performs its own full verification before adopting a staging directory.
                check(directoryMatchesLibrary(snapshot, root, verifyHashes = false, verifyFilenames = setOf(publication.filename))) {
                    "The targeted Library generation failed verification after activation"
                }
            }
            return TargetPublicationActivation(publication, root, previous, journal)
        } catch (error: Throwable) {
            restoreReusedLibraryFiles(root, staging, previous, journal)
            if (switched) {
                val failed = File(parent, ".thoughts-library-failed-${UUID.randomUUID()}")
                if (root.exists()) Os.rename(root.absolutePath, failed.absolutePath)
                if (previous.exists()) Os.rename(previous.absolutePath, root.absolutePath)
                failed.deleteRecursively()
            } else if (!root.exists() && previous.exists()) {
                Os.rename(previous.absolutePath, root.absolutePath)
            }
            staging.deleteRecursively()
            journal.delete()
            throw error
        }
    }

    private fun commitTargetPublicationActivation(activation: TargetPublicationActivation) {
        activation.journal.delete()
        runCatching { PdfWarmCacheWorker.enqueue(appContext) }
    }

    private fun rollbackTargetPublicationActivation(activation: TargetPublicationActivation) {
        restoreReusedLibraryFiles(activation.root, File(activation.root.parentFile, ".unused-staging"), activation.previous, activation.journal)
        val failed = File(requireNotNull(activation.root.parentFile), ".thoughts-library-failed-${UUID.randomUUID()}")
        if (activation.root.exists()) Os.rename(activation.root.absolutePath, failed.absolutePath)
        if (activation.previous.exists()) {
            Os.rename(activation.previous.absolutePath, activation.root.absolutePath)
        }
        failed.deleteRecursively()
        activation.journal.delete()
    }

    private suspend fun reconcileSupersededRenderFailures(head: RemoteLibraryHead) {
        val root = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val generation = readGenerationManifest(root)
        if (generation.libraryRevision != head.libraryRevision) return

        val verified = mutableMapOf<String, Boolean>()
        fun hasExactHealthyReplacement(filename: String): Boolean = verified.getOrPut(filename) {
            val publication = head.publications[filename] ?: return@getOrPut false
            if (publication.pdfStale || publication.publishedBuildId.isBlank()) return@getOrPut false
            if (generation.publications[filename] != (publication.sha256 to publication.sizeBytes)) {
                return@getOrPut false
            }
            val pdf = File(root, filename)
            pdf.isFile && pdf.length() == publication.sizeBytes && fileSha256(pdf) == publication.sha256
        }

        val receiptDirectory = PhoneRenderQueue.defaultReceiptDirectory()
        val requestDirectory = PhoneRenderQueue.defaultRequestDirectory()
        receiptDirectory.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty().forEach { receipt ->
            val payload = runCatching {
                JsonParser.parseString(receipt.readText(StandardCharsets.UTF_8)).asJsonObject
            }.getOrNull() ?: return@forEach
            val status = payload.string("status")
            if (status !in setOf("failure", "partial_success")) return@forEach
            val requestId = payload.string("request_id")
                .takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                ?: return@forEach
            val receiptBuildId = payload.string("build_id").ifBlank {
                dao.buildRequest(requestId)?.jobId.orEmpty()
            }
            if (receiptBuildId.isBlank()) return@forEach

            val anchorAttention = (payload.getAsJsonArray("anchor_results") ?: JsonArray()).any { value ->
                val anchorStatus = value.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.string("status")
                    .orEmpty()
                anchorStatus.isNotBlank() && anchorStatus !in setOf("anchored", "deleted")
            }
            val attentionPdfs = payload.getAsJsonArray("attention_pdfs") ?: JsonArray()
            val target = payload.string("target")
            if (target != PhoneRenderQueue.ACTION_ALL) {
                val publication = head.publications[target] ?: return@forEach
                if (
                    !anchorAttention &&
                    attentionPdfs.size() == 0 &&
                    receiptBuildId != publication.publishedBuildId &&
                    hasExactHealthyReplacement(target)
                ) {
                    check(receipt.delete()) { "Could not delete superseded render receipt ${receipt.name}" }
                    File(requestDirectory, receipt.name).delete()
                    dao.deleteBuildRequest(requestId)
                }
                return@forEach
            }

            val failures = payload.getAsJsonArray("failed_documents") ?: return@forEach
            if (failures.size() == 0) return@forEach
            val retained = JsonArray()
            failures.forEach { value ->
                val filename = value.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.string("pdf_filename")
                    .orEmpty()
                val publication = head.publications[filename]
                val superseded = publication != null &&
                    receiptBuildId != publication.publishedBuildId &&
                    hasExactHealthyReplacement(filename)
                if (!superseded) retained.add(value.deepCopy())
            }
            if (retained.size() == failures.size()) return@forEach
            if (retained.size() == 0 && !anchorAttention && attentionPdfs.size() == 0) {
                check(receipt.delete()) { "Could not delete superseded render receipt ${receipt.name}" }
                File(requestDirectory, receipt.name).delete()
                dao.deleteBuildRequest(requestId)
            } else {
                payload.add("failed_documents", retained)
                atomicWrite(receipt, gson.toJson(payload) + "\n")
            }
        }
    }

    private data class GenerationIdentity(
        val libraryRevision: String = "",
        val sourceIndexSha256: String = "",
        val publications: Map<String, Pair<String, Long>> = emptyMap(),
    )

    private fun readGenerationManifest(directory: File): GenerationIdentity {
        val file = File(directory, SourceLinkedPdfIndex.GENERATION_FILENAME)
        if (!file.isFile) return GenerationIdentity()
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            check(root.get("schema_version")?.asInt == 1)
            val libraryRevision = root.string("library_revision").takeIf(SHA256::matches).orEmpty()
            val sourceIndex = root.string("source_index_sha256").takeIf(SHA256::matches).orEmpty()
            val entries = (root.getAsJsonArray("publications") ?: JsonArray()).associate { value ->
                val publication = value.asJsonObject
                val filename = publication.string("filename").takeIf { it.isSafePdfFilename() }
                    ?: error("Invalid generation filename")
                val sha256 = publication.string("sha256").takeIf(SHA256::matches)
                    ?: error("Invalid generation hash")
                filename to (sha256 to publication.get("size_bytes").asLong)
            }
            GenerationIdentity(libraryRevision, sourceIndex, entries)
        }.getOrDefault(GenerationIdentity())
    }

    private fun verifiedGenerationPdfFiles(
        directory: File,
        publications: Map<String, Pair<String, Long>>,
        requiredHashes: Set<String>? = null,
    ): Map<String, File> = buildMap {
        publications.forEach { (filename, identity) ->
            if (requiredHashes != null && identity.first !in requiredHashes) return@forEach
            val file = File(directory, filename)
            if (
                file.isFile && file.length() == identity.second &&
                runCatching { fileSha256(file) == identity.first }.getOrDefault(false)
            ) {
                putIfAbsent(identity.first, file)
            }
        }
    }

    private fun libraryIndex(snapshot: LibrarySnapshot): JsonObject = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("generated_at", Instant.now().toString())
        addProperty("library_revision", snapshot.libraryRevision)
        addProperty("source_index_sha256", snapshot.sourceIndexSha256)
        add("pdfs", JsonArray().apply {
            snapshot.publications.sortedBy { it.filename }.forEach { publication ->
                add(libraryIndexEntry(publication))
            }
        })
    }

    private fun libraryIndexEntry(publication: LibraryEntry): JsonObject = JsonObject().apply {
        addProperty("filename", publication.filename)
        addProperty("document_id", publication.documentId)
        addProperty("generation_id", publication.generationId)
        addProperty("kind", publication.metadata.string("kind").ifBlank { "source_linked" })
        addProperty("pdf_sha256", publication.sha256)
        addProperty("source_frontier", publication.sourceFrontier)
        addProperty("comment_frontier", publication.commentFrontier)
        addProperty("annotation_frontier", publication.annotationFrontier)
        addProperty("placement_manifest_sha256", publication.placementManifestSha256)
        publication.publishedAt?.takeIf(String::isNotBlank)?.let { addProperty("published_at", it) }
        addProperty("pdf_stale", publication.pdfStale)
        publication.latestBuild?.takeIf { it.isJsonObject }?.let { add("latest_build", it) }
        publication.metadata.string("display_name").trim().takeIf(String::isNotEmpty)
            ?.let { addProperty("display_name", it) }
        addProperty("archived", publication.metadata.get("archived")?.asBoolean == true)
    }

    private fun generationManifest(snapshot: LibrarySnapshot): JsonObject = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("library_revision", snapshot.libraryRevision)
        addProperty("artifact_revision", snapshot.effectiveArtifactRevision())
        addProperty("source_index_sha256", snapshot.sourceIndexSha256)
        // The publications carry html_status / html_edition_sha256 / html_error (SDK LibraryEntry).
        addProperty(GENERATION_HTML_FIELDS, 1)
        add("publications", gson.toJsonTree(snapshot.publications))
        add("project_readings", gson.toJsonTree(snapshot.projectReadings))
    }

    private fun refreshLibraryMetadata(snapshot: LibrarySnapshot) {
        val root = PhoneRenderQueue.defaultCanonicalPdfDirectory()
        val generation = readGenerationManifest(root)
        val expected = snapshot.publications.associate { publication ->
            publication.filename to (publication.sha256 to publication.sizeBytes)
        }
        check(generation.publications == expected) {
            "Library metadata cannot advance across an artifact identity change"
        }
        check(generation.sourceIndexSha256 == snapshot.sourceIndexSha256) {
            "Library metadata cannot advance across a source-index change"
        }
        verifyProjectReadings(snapshot, root, hashes = false)
        snapshot.publications.forEach { publication ->
            val pdf = File(root, publication.filename)
            check(pdf.isFile && pdf.length() == publication.sizeBytes) {
                "Library metadata cannot trust a missing PDF"
            }
        }
        atomicWrite(
            File(root, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME),
            gson.toJson(libraryIndex(snapshot)) + "\n",
        )
        atomicWrite(
            File(root, SourceLinkedPdfIndex.GENERATION_FILENAME),
            gson.toJson(generationManifest(snapshot)) + "\n",
        )
    }

    private fun validateSourceIndex(bytes: ByteArray, snapshot: LibrarySnapshot) {
        val root = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).asJsonObject
        check(root.get("schema_version")?.asInt == 1)
        val expected = snapshot.publications
            .filter { it.metadata.string("kind").ifBlank { "source_linked" } == "source_linked" }
            .associate { it.filename to it.sha256 }
        val entries = root.getAsJsonArray("pdfs") ?: error("Source-layout index has no PDF list")
        val actual = entries.associate { value ->
            val entry = value.asJsonObject
            val filename = entry.string("filename").takeIf { it.isSafePdfFilename() }
                ?: error("Source-layout index has an invalid filename")
            val expectedSha = expected[filename] ?: error("Source-layout index contains a non-linked PDF")
            val indexedSha = entry.string("pdf_sha256")
            check(indexedSha.isBlank() || indexedSha == expectedSha) { "Source-layout index has a stale PDF identity" }
            filename to expectedSha
        }
        check(actual.size == entries.size() && actual == expected) { "Source-layout index is not the exact linked publication set" }
    }

    private fun directoryMatchesLibrary(
        snapshot: LibrarySnapshot,
        directory: File,
        verifyHashes: Boolean,
        reusedPdfs: Map<String, File> = emptyMap(),
        verifyFilenames: Set<String>? = null,
        reusedObjects: Map<String, File> = emptyMap(),
    ): Boolean = runCatching {
        if (!directory.isDirectory) return@runCatching false
        val generation = readGenerationManifest(directory)
        check(generation.libraryRevision == snapshot.libraryRevision)
        check(generation.sourceIndexSha256 == snapshot.sourceIndexSha256)
        val expected = snapshot.publications.associate { it.filename to (it.sha256 to it.sizeBytes) }
        check(generation.publications == expected)
        val actualGeneration = JsonParser.parseString(
            File(directory, SourceLinkedPdfIndex.GENERATION_FILENAME).readText(),
        ).asJsonObject
        check(actualGeneration == generationManifest(snapshot))
        verifyProjectReadings(snapshot, directory, verifyHashes, reusedObjects)
        val actualPdfFilenames = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            .mapTo(linkedSetOf()) { it.name }
        check(actualPdfFilenames.intersect(reusedPdfs.keys).isEmpty())
        val present = actualPdfFilenames + reusedPdfs.keys
        if (verifyFilenames == null) check(present == expected.keys)
        else check(present.all { it in expected } && present.containsAll(verifyFilenames))
        snapshot.publications.filter { verifyFilenames == null || it.filename in verifyFilenames }.forEach { publication ->
            val pdf = reusedPdfs[publication.filename] ?: File(directory, publication.filename)
            check(pdf.isFile && pdf.length() == publication.sizeBytes)
            if (verifyHashes) check(fileSha256(pdf) == publication.sha256)
        }
        val sourceIndex = File(directory, SourceLinkedPdfIndex.INDEX_FILENAME)
        check(sourceIndex.isFile)
        val sourceBytes = sourceIndex.readBytes()
        check(sha256(sourceBytes) == snapshot.sourceIndexSha256)
        validateSourceIndex(sourceBytes, snapshot)
        val expectedLibrary = libraryIndex(snapshot).apply { remove("generated_at") }
        val actualLibrary = JsonParser.parseString(
            File(directory, SourceLinkedPdfIndex.LIBRARY_INDEX_FILENAME).readText(),
        ).asJsonObject.apply { remove("generated_at") }
        val actualLibraryJson = gson.toJson(actualLibrary)
        val expectedLibraryJson = gson.toJson(expectedLibrary)
        check(actualLibraryJson == expectedLibraryJson) {
            val mismatch = actualLibraryJson.indices
                .firstOrNull { it >= expectedLibraryJson.length || actualLibraryJson[it] != expectedLibraryJson[it] }
                ?: minOf(actualLibraryJson.length, expectedLibraryJson.length)
            val start = (mismatch - 80).coerceAtLeast(0)
            val actualEnd = (mismatch + 80).coerceAtMost(actualLibraryJson.length)
            val expectedEnd = (mismatch + 80).coerceAtMost(expectedLibraryJson.length)
            "Library index mismatch at $mismatch: " +
                "actual=${actualLibraryJson.substring(start, actualEnd)} " +
                "expected=${expectedLibraryJson.substring(start, expectedEnd)}"
        }
        true
    }.onFailure { error ->
        if (File(directory, SourceLinkedPdfIndex.GENERATION_FILENAME).isFile) {
            Log.e(
                "ThoughtsSync",
                "Library generation verification failed for ${directory.path}: ${error.message}",
                error,
            )
        }
    }.getOrDefault(false)

    private fun recoverInterruptedActivation(
        snapshot: LibrarySnapshot,
        root: File,
        staging: File,
        previous: File,
        journal: File,
    ) {
        reconcileInterruptedLibraryActivation(root)
        if (directoryMatchesLibrary(snapshot, root, verifyHashes = false)) {
            journal.delete()
            return
        }
        if (!root.exists() && journal.isFile && directoryMatchesLibrary(snapshot, staging, verifyHashes = true)) {
            Os.rename(staging.absolutePath, root.absolutePath)
            journal.delete()
            return
        }
        if (!root.exists() && previous.isDirectory) {
            Os.rename(previous.absolutePath, root.absolutePath)
        }
    }

    private fun linkOrCopy(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            verifiedFileCache.link(source, destination)
        } catch (_: Throwable) {
            FileOutputStream(destination).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
        }
    }

    private fun syncSourceBundle(
        snapshot: LibrarySnapshot,
        client: SyncClient,
        targetFilenames: Set<String>? = null,
        transferPlan: SyncTransferPlan? = null,
    ): String? {
        var installedAndPrunedBundleId: String? = null
        syncSourceArtifacts(
            publications = snapshot.publications,
            client = client,
            targetFilenames = targetFilenames,
            knownCurrentFilenames = snapshot.publications.mapTo(hashSetOf(), LibraryEntry::filename),
            deferActivation = false,
            onInstalledAndPruned = { installedAndPrunedBundleId = it },
            transferPlan = transferPlan,
        )
        return installedAndPrunedBundleId
    }

    private fun syncTargetSourceBundle(
        publication: LibraryEntry,
        client: SyncClient,
        transferPlan: SyncTransferPlan,
    ): (() -> Unit)? = syncSourceArtifacts(
        publications = listOf(publication),
        client = client,
        targetFilenames = setOf(publication.filename),
        knownCurrentFilenames = null,
        deferActivation = true,
        transferPlan = transferPlan,
    )

    private fun syncSourceArtifacts(
        publications: List<LibraryEntry>,
        client: SyncClient,
        targetFilenames: Set<String>?,
        knownCurrentFilenames: Set<String>?,
        deferActivation: Boolean,
        transferPlan: SyncTransferPlan?,
        onInstalledAndPruned: (String) -> Unit = {},
    ): (() -> Unit)? {
        val progress = syncDiagnosticProgress(appContext, transferPlan?.operationId)
        progress("Preparing source manifests")
        val manifestStartedNanos = System.nanoTime()
        val diagnostics = progress.diagnostics
        val artifactCache = File(appContext.filesDir, "source-editor/document-artifacts").apply {
            mkdirs()
        }
        val store = OfflineSourceBundleStore(appContext)
        val artifactsByFilename = linkedMapOf<String, String>()
        // The active reference index is authoritative. Never select a historical
        // artifact by scanning an unordered content cache during a targeted pull.
        if (targetFilenames != null) {
            artifactsByFilename.putAll(store.activeDocumentArtifacts().filterKeys {
                it !in targetFilenames && (knownCurrentFilenames == null || it in knownCurrentFilenames)
            })
        }
        publications
            .asSequence()
            .filter { targetFilenames == null || it.filename in targetFilenames }
            .forEach { publication ->
                publication.sourceArtifactSha256
                    ?.takeIf(SHA256::matches)
                    ?.let { artifactsByFilename[publication.filename] = it }
            }
        val artifacts = artifactsByFilename.toSortedMap().entries.map { it.key to it.value }
        if (artifacts.isEmpty()) {
            progress(syncStageDuration("Preparing source manifests (none needed)", manifestStartedNanos))
            return null
        }
        // A targeted pull has no new freshness information for other projects.
        val publicationStaleByFilename = (if (targetFilenames != null) store.activeDocumentStaleness() else emptyMap()) +
            publications.associate { it.filename to it.pdfStale }
        val receiptFile = File(appContext.filesDir, "source-editor/offline/document-artifact-receipt.json")
        val installedObjects = File(appContext.filesDir, "source-editor/offline/objects")
        val inputs = sourceManifestInputs(artifacts, publicationStaleByFilename)
        val current = runCatching { daoStateBundleId() }.getOrDefault("")
        val receipt = runCatching { JsonParser.parseString(receiptFile.readText()).asJsonObject }.getOrNull()
        val previousInputs = runCatching { receipt?.getAsJsonArray("inputs") }.getOrNull()
        diagnostics.count("source_manifest_inputs", "documents" to artifacts.size.toLong(),
            "changed_documents" to inputs.count { candidate ->
                previousInputs?.any { it == candidate } != true
            }.toLong())
        if (diagnostics.measure("check_source_manifest_receipt") {
            canReuseSourceManifest(receipt, inputs, current, installedObjects, diagnostics)
        }) {
            progress(syncStageDuration("Preparing source manifests (unchanged)", manifestStartedNanos))
            return null
        }
        // Each uncached manifest below would otherwise be fetched alone, one round trip at a time.
        val uncached = artifacts.filter { (_, artifactSha256) -> !File(artifactCache, artifactSha256).isFile }
        if (uncached.size > 1) diagnostics.measure("prefetch_document_manifests") {
            parallelContentObjects(uncached) { (filename, artifactSha256) ->
                cachedDocumentArtifact(filename, artifactSha256, artifactCache, client, transferPlan)
            }
        }
        val manifestRaw = diagnostics.measure("prepare_project_source_index") {
            val references = JsonArray()
            for ((filename, artifactSha256) in artifacts) {
                val stale = publicationStaleByFilename[filename] == true
                val cached = store.installedDocumentManifest(filename, artifactSha256, stale)
                val manifest = cached ?: run {
                    val raw = prepareSourceArtifactManifest(
                        listOf(filename to artifactSha256), artifactCache,
                        publicationStaleByFilename, client, transferPlan, diagnostics,
                    )
                    sha256(raw).also { check(store.stageDownloadedObject(it, raw)) }
                }
                references.add(JsonObject().apply {
                    addProperty("filename", filename)
                    addProperty("artifact_sha256", artifactSha256)
                    addProperty("manifest_object", manifest)
                    addProperty("pdf_stale", stale)
                })
            }
            gson.toUtf8Bytes(JsonObject().apply {
                addProperty("schema_version", 3)
                add("project_manifests", references)
            }, trailingNewline = true)
        }
        diagnostics.count("merged_manifest", "manifest_bytes" to manifestRaw.size.toLong())
        val bundleId = diagnostics.measure("hash_merged_source_manifest") { sha256(manifestRaw) }
        progress(syncStageDuration("Preparing source manifests", manifestStartedNanos))
        if (current == bundleId) {
            diagnostics.measure("write_source_manifest_receipt") {
                atomicWrite(receiptFile, gson.toJson(sourceManifestReceipt(inputs, bundleId, installedObjects)))
            }
            return null
        }
        val indexRaw = gson.toJson(
            JsonObject().apply {
                addProperty("schema_version", 3)
                addProperty("bundle_id", bundleId)
                addProperty("manifest_object", bundleId)
            },
        ).toByteArray(StandardCharsets.UTF_8)
        check(diagnostics.measure("stage_project_source_index") { store.stageDownloadedObject(bundleId, manifestRaw) }) {
            "Merged document-source manifest failed verification"
        }
        val missing = measureSyncStage("Verifying cached source objects", progress) {
            store.missingObjectIds(indexRaw, manifestRaw, progress)
                ?: error("Merged document-source manifest is invalid")
        }
        diagnostics.count("source_objects", "missing_objects" to missing.size.toLong())
        val downloaded = measureSyncStage("Downloading and verifying ${missing.size} source objects", progress) {
            parallelContentObjects(missing.toList()) { digest ->
                val planned = transferPlan?.remoteObjects?.singleOrNull { it.sha256 == digest }
                    ?: transferPlan?.let { throw ArtifactPlanSupersededException() }
                if (
                    planned != null &&
                    (planned.kind != "source" || planned.roles.none {
                        it in setOf(
                            "source_snapshot",
                            "reverse_lookup",
                            "raw_synctex_fallback",
                            "source_resource",
                            "renderer_evidence",
                            "project_composition",
                            "document_component_package",
                            "component_review",
                        )
                    })
                ) {
                    throw ArtifactPlanSupersededException()
                }
                val temporary = File.createTempFile("html-object-", ".download", appContext.cacheDir)
                try {
                    val size = temporary.outputStream().use { output ->
                        client.downloadObject(digest, output, syncPlanId = transferPlan?.serverPlanId)
                    }
                    check(planned == null || size == planned.sizeBytes) { "Downloaded object size differs from plan" }
                    check(store.stageDownloadedFile(digest, temporary)) { "Downloaded source object failed verification" }
                    digest to size
                } finally { temporary.delete() }
            }
        }
        diagnostics.count("downloaded_source_objects", "objects" to downloaded.size.toLong(),
            "bytes" to downloaded.sumOf { it.second })
        val activate = {
            SyncOperationStore(appContext).append(
                transferPlan?.operationId, SyncOperationStore.STATUS_SYNCING,
                "Activating matching sources",
            )
            check(store.installDownloaded(indexRaw, emptyMap(), progress) != null) {
                "Downloaded document-source artifacts could not be activated"
            }
            diagnostics.measure("write_source_manifest_receipt") {
                atomicWrite(receiptFile, gson.toJson(sourceManifestReceipt(inputs, bundleId, installedObjects)))
            }
            if (targetFilenames == null) {
                SourceEditQueue(appContext, scheduleRequests = false)
                    .clearGlobalWorkspaceStyleRefresh()
            }
            // installDownloaded returns only after working files and private objects were pruned.
            if (store.activeInstalledId() == bundleId) onInstalledAndPruned(bundleId)
        }
        if (!deferActivation) activate()
        artifactCache.listFiles().orEmpty()
            .filter {
                targetFilenames == null &&
                    it.isFile && it.name !in artifacts.mapTo(hashSetOf()) { pair -> pair.second }
            }
            .forEach(File::delete)
        return activate.takeIf { deferActivation }
    }

    /** Returns the verified cached document manifest, downloading it first if needed, and the bytes downloaded. */
    private fun cachedDocumentArtifact(
        filename: String,
        artifactSha256: String,
        artifactCache: File,
        client: SyncClient,
        transferPlan: SyncTransferPlan?,
    ): Pair<File, Long> {
        val file = File(artifactCache, artifactSha256)
        if (file.isFile && fileSha256(file) == artifactSha256) return file to 0L
        val planned = transferPlan?.requireRemoteObject(
            artifactSha256,
            kind = "source",
            role = "source_manifest",
            publication = filename,
        )
        val raw = client.sourceBundleObject(artifactSha256, transferPlan?.serverPlanId)
        check(sha256(raw) == artifactSha256 && (planned == null || raw.size.toLong() == planned.sizeBytes)) {
            "Downloaded source manifest failed planned identity verification"
        }
        file.writeBytes(raw)
        return file to raw.size.toLong()
    }

    private fun prepareSourceArtifactManifest(
        artifacts: List<Pair<String, String>>,
        artifactCache: File,
        publicationStaleByFilename: Map<String, Boolean>,
        client: SyncClient,
        transferPlan: SyncTransferPlan?,
        diagnostics: SyncDiagnostics,
    ): ByteArray {
        val pdfs = JsonArray()
        val sources = linkedMapOf<String, JsonObject>()
        val resources = linkedMapOf<String, JsonObject>()
        val proposals = JsonArray()
        val reviewComments = linkedMapOf<String, JsonObject>()
        val reviewCommentDocuments = linkedSetOf<String>()
        val logicalComments = linkedMapOf<String, JsonObject>()
        val reviewCorrespondences = linkedMapOf<String, JsonObject>()
        val reused = java.util.concurrent.atomic.AtomicLong()
        val downloadedBytes = java.util.concurrent.atomic.AtomicLong()
        val artifactFiles = diagnostics.measure("load_document_manifests") { parallelContentObjects(artifacts) { (filename, artifactSha256) ->
            val (file, downloaded) = cachedDocumentArtifact(filename, artifactSha256, artifactCache, client, transferPlan)
            if (downloaded == 0L) reused.incrementAndGet() else downloadedBytes.addAndGet(downloaded)
            file
        }
        }
        diagnostics.count("document_manifest_cache", "hits" to reused.get(),
            "misses" to artifacts.size.toLong() - reused.get(), "downloaded_bytes" to downloadedBytes.get(),
            "processed_bytes" to artifactFiles.sumOf { it.length() })
        artifacts.zip(artifactFiles).forEach { (artifactEntry, file) ->
            val (filename, artifactSha256) = artifactEntry
            val artifact = diagnostics.measure("parse_document_manifest:$filename") {
                file.bufferedReader(StandardCharsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
            }
            check(artifact.string("kind") == "thoughts-document-source-artifact")
            check(artifact.string("filename") == filename)
            discardRedundantProposalGeometry(artifact)
            pdfs.add(artifact.getAsJsonObject("pdf").deepCopy().apply {
                // Keep the build input identities separate from current editable sources.
                artifact.getAsJsonArray("placement_sources")?.let { add("placement_sources", it) }
                artifact.getAsJsonArray("source_revision_equivalences")?.let {
                    add("source_revision_equivalences", it)
                }
            })
            artifact.getAsJsonArray("sources").forEach { sourceElement ->
                val source = sourceElement.asJsonObject
                val path = source.string("path")
                val previous = sources.putIfAbsent(path, source)
                check(previous == null || previous == source) {
                    "Document artifacts disagree about source $path"
                }
            }
            artifact.getAsJsonArray("resources")?.forEach { resourceElement ->
                val resource = resourceElement.asJsonObject
                val path = resource.string("path")
                resources[path] = mergeSourceResourceDocuments(
                    resources[path],
                    resource,
                    filename,
                    publicationStale = publicationStaleByFilename[filename] == true,
                )
            }
            val objectIds = artifact.getAsJsonArray("proposal_objects")
                .associate { value ->
                    val reference = value.asJsonObject
                    reference.string("id") to reference.string("object")
                }
            artifact.getAsJsonArray("agent_proposals").forEach { proposalElement ->
                val proposal = proposalElement.asJsonObject.deepCopy()
                proposal.addProperty("artifact_sha256", artifactSha256)
                proposal.addProperty(
                    "proposal_object_sha256",
                    requireNotNull(objectIds[proposal.string("id")]),
                )
                proposals.add(proposal)
            }
            if (artifact.safeInt("review_comments_schema_version") == 1) {
                reviewCommentDocuments += filename
                artifact.getAsJsonArray("review_comments").forEach { reviewElement ->
                    val review = reviewElement.asJsonObject.deepCopy()
                    val id = review.string("id")
                    val sourceFile = review.string("source_file")
                    check(sourceFile in sources) { "Review comment source is unavailable: $sourceFile" }
                    check(review.string("source_sha256") == sources[sourceFile]?.string("sha256")) {
                        "Review comment source hash is stale: $id"
                    }
                    reviewComments[id] = mergeReviewCommentDocuments(
                        reviewComments[id],
                        review,
                        filename,
                    )
                }
            }
            if (artifact.safeInt("logical_comments_schema_version") == 1) {
                artifact.getAsJsonArray("logical_comments").forEach { logicalElement ->
                    val logical = logicalElement.asJsonObject
                    val id = logical.string("comment_id")
                    logicalComments[id] = mergeLogicalCommentArtifacts(logicalComments[id], logical)
                }
            }
            if (artifact.safeInt("review_correspondence_schema_version") == 2) {
                artifact.getAsJsonArray("review_correspondences").forEach { correspondenceElement ->
                    val correspondence = correspondenceElement.asJsonObject
                    val reviewKey = correspondence.string("review_key")
                    reviewCorrespondences[reviewKey] = mergeReviewCorrespondenceArtifacts(
                        reviewCorrespondences[reviewKey],
                        correspondence,
                    )
                }
            }
        }
        val merged = JsonObject().apply {
            addProperty("schema_version", 3)
            add("pdfs", pdfs)
            add("sources", JsonArray().apply { sources.toSortedMap().values.forEach(::add) })
            add(
                "resources",
                JsonArray().apply {
                    resources.toSortedMap().values.forEach { mergedResource ->
                        add(mergedResource.deepCopy().apply {
                            remove(SOURCE_RESOURCE_STALE_MERGE_KEY)
                        })
                    }
                },
            )
            add("agent_proposals", proposals)
            add("review_comments", JsonArray().apply { reviewComments.toSortedMap().values.forEach(::add) })
            add("review_comment_documents", JsonArray().apply { reviewCommentDocuments.sorted().forEach(::add) })
            addProperty("logical_comments_schema_version", 1)
            add("logical_comments", JsonArray().apply { logicalComments.toSortedMap().values.forEach(::add) })
            if (reviewCorrespondences.isNotEmpty()) {
                addProperty("review_correspondence_schema_version", 2)
                add("review_correspondences", JsonArray().apply { reviewCorrespondences.toSortedMap().values.forEach(::add) })
            }
        }
        diagnostics.count("merged_manifest_work", "pdfs" to pdfs.size().toLong(),
            "sources" to sources.size.toLong(), "proposals" to proposals.size().toLong(),
            "correspondences" to reviewCorrespondences.size.toLong(),
            "locations" to reviewCorrespondences.values.sumOf { it.getAsJsonArray("locations")?.size()?.toLong() ?: 0L })
        return diagnostics.measure("serialize_merged_manifest") { gson.toUtf8Bytes(merged, trailingNewline = true) }
    }
    private fun daoStateBundleId(): String = OfflineSourceBundleStore(appContext).activeInstalledId().orEmpty()

    private fun <T, R> parallelContentObjects(items: List<T>, block: (T) -> R): List<R> {
        if (items.size <= 1) return items.map(block)
        val executor = Executors.newFixedThreadPool(items.size.coerceAtMost(MAX_PARALLEL_OBJECTS))
        return try {
            val futures = executor.invokeAll(items.map { item -> Callable { block(item) } })
            futures.map { future ->
                try {
                    future.get()
                } catch (error: ExecutionException) {
                    throw (error.cause as? Exception ?: error)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun downloadPublication(
        client: SyncClient,
        sha256: String,
        destination: File,
        syncPlanId: String? = null,
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${sha256}.partial")
        try {
            var offset = temporary.takeIf(File::isFile)?.length() ?: 0L
            try {
                FileOutputStream(temporary, offset > 0).use { output ->
                    client.downloadObject(sha256, output, offset, syncPlanId)
                    output.fd.sync()
                }
            } catch (_: RangeResumeUnsupportedException) {
                temporary.delete()
                offset = 0L
                FileOutputStream(temporary, false).use { output ->
                    client.downloadObject(sha256, output, offset, syncPlanId)
                    output.fd.sync()
                }
            }
            check(fileSha256(temporary) == sha256) { "Downloaded PDF failed SHA-256 verification" }
            Os.rename(temporary.absolutePath, destination.absolutePath)
        } finally {
            if (destination.isFile) temporary.delete()
        }
    }

    private fun downloadOrPatchPublication(
        client: SyncClient,
        publication: LibraryEntry,
        base: File?,
        destination: File,
        transferPlan: SyncTransferPlan? = null,
    ) {
        val plannedDelta = transferPlan?.remoteObjects?.singleOrNull { planned ->
            planned.kind == "pdf" && "pdf_delta" in planned.roles &&
                publication.filename in planned.publications
        }
        if (plannedDelta != null) {
            if (tryPatchPublication(client, publication, base, destination, transferPlan)) return
            PdfDeltaFallbackStore(appContext).reject(transferPlan.operationId)
            throw ArtifactPlanSupersededException()
        }
        if (transferPlan == null && tryPatchPublication(client, publication, base, destination, null)) {
            return
        }
        transferPlan?.requireRemoteObject(
            publication.sha256,
            kind = "pdf",
            role = "pdf",
            expectedSize = publication.sizeBytes,
            publication = publication.filename,
        )
        downloadPublication(
            client,
            publication.sha256,
            destination,
            transferPlan?.serverPlanId,
        )
    }

    private fun tryPatchPublication(
        client: SyncClient,
        publication: LibraryEntry,
        base: File?,
        destination: File,
        transferPlan: SyncTransferPlan?,
    ): Boolean = attemptPublicationDelta(
        onInvalid = { reason ->
            SyncOperationStore(appContext).append(
                transferPlan?.operationId,
                SyncOperationStore.STATUS_SYNCING,
                "PDF patch for ${publication.filename} could not be applied: $reason. " +
                    "Full PDF: ${android.text.format.Formatter.formatFileSize(appContext, publication.sizeBytes)}; " +
                    "checking the mobile-data threshold.",
            )
        },
    ) {
        val delta = publication.metadata.getAsJsonObject("delta")
            ?: throw InvalidPublicationDeltaException("Patch metadata is missing")
        val baseSha256 = delta.string("base_sha256")
        val patchSha256 = delta.string("object_sha256")
        val patchSize = delta.get("size_bytes")?.asLong
            ?: throw InvalidPublicationDeltaException("Patch size is missing")
        val resultSize = delta.get("result_size_bytes")?.asLong
            ?: throw InvalidPublicationDeltaException("Result size is missing")
        if (
            delta.string("format") != PDF_DELTA_FORMAT ||
            !baseSha256.matches(SHA256) ||
            !patchSha256.matches(SHA256) ||
            patchSize !in 1..Int.MAX_VALUE.toLong() ||
            delta.string("result_sha256") != publication.sha256 ||
            resultSize != publication.sizeBytes ||
            resultSize !in 0..Int.MAX_VALUE.toLong()
        ) {
            throw InvalidPublicationDeltaException("Patch metadata is incompatible with this PDF")
        }
        val baseFile = base?.takeIf { it.isFile }
            ?: throw InvalidPublicationDeltaException("The required base PDF is unavailable")
        requireUsablePublicationDelta(fileSha256(baseFile) == baseSha256,
            "The local PDF does not match the patch base")
        transferPlan?.requireRemoteObject(
            patchSha256,
            kind = "pdf",
            role = "pdf_delta",
            expectedSize = patchSize,
            publication = publication.filename,
        )
        // One PDF of a parallel loop: keep its diagnostic spans, but not four durable operation-log
        // writes per PDF; the serialized log writes made 111 patches take about 20 s.
        val progress = syncDiagnosticProgress(appContext, transferPlan?.operationId).withoutStageMessages()
        val patch = measureSyncStage("Downloading PDF patch for ${publication.filename}", progress) {
            client.downloadObject(patchSha256, transferPlan?.serverPlanId).also { bytes ->
                requireUsablePublicationDelta(bytes.size.toLong() == patchSize && sha256(bytes) == patchSha256,
                    "Downloaded patch failed size or checksum verification")
            }
        }
        val result = measureSyncStage("Reconstructing and verifying ${publication.filename}", progress) {
            val decoded = decodeZstdPublicationDelta(baseFile.readBytes(), patch, resultSize.toInt())
                ?: throw InvalidPublicationDeltaException("Patch decompression failed")
            requireUsablePublicationDelta(sha256(decoded) == publication.sha256,
                "Reconstructed PDF failed checksum verification")
            decoded
        }
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${publication.sha256}.delta")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(result)
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, destination.absolutePath)
        } finally {
            if (destination.isFile) temporary.delete()
        }
    }

    private fun atomicWrite(destination: File, contents: String) {
        atomicWrite(destination, contents.toByteArray(StandardCharsets.UTF_8))
    }

    private fun safeFailureDetail(error: Throwable): String {
        val raw = error.message.orEmpty()
        val serverDetail = runCatching {
            JsonParser.parseString(raw).asJsonObject.string("detail")
        }.getOrNull().orEmpty()
        return serverDetail.ifBlank { raw }
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .trim()
            .ifBlank { error.javaClass.simpleName }
            .take(600)
    }

    private fun atomicWrite(destination: File, contents: ByteArray) {
        destination.parentFile?.mkdirs()
        if (contents.size <= 2_000_000 && destination.isFile && destination.length() == contents.size.toLong() && destination.readBytes().contentEquals(contents)) {
            return
        }
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(contents)
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                check(temporary.delete()) { "Could not remove temporary sync file" }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private data class PendingMutation(
        val operationId: String,
        val entityType: String,
        val entityId: String,
        val collection: String,
        val baseRevision: Long?,
        val clientTimestamp: String,
        val payload: JsonObject,
    )

    companion object {
        /** Set in the stored generation manifest whose publications carry the HTML edition fields. */
        const val GENERATION_HTML_FIELDS = "html_edition_fields"
        private const val CAPABILITY_CACHE_MS = 60_000L
        private const val PROPOSAL_ENTITY_TYPE = RemovedProposalDismissal.ENTITY_TYPE
        private const val PROPOSAL_COLLECTION = RemovedProposalDismissal.COLLECTION
        private const val PROJECTION_REVISION = RemovedProposalDismissal.PROJECTION_REVISION
        @Volatile private var pooledNetwork: android.net.Network? = null
        @Volatile private var cachedCapabilities: Triple<String, Long, Set<String>>? = null
        private val COMMENT_CAPTURE_MUTEX = Mutex()
        private const val ANCHOR_CAPABILITY = "annotation_anchor_v1"
        private const val TRACE_EVENTS_CAPABILITY = "trace_events_v1"
        private const val SYNC_PLAN_CAPABILITY = "sync_transfer_plan_v2"
        private const val ANCHOR_POLL_MILLIS = 5_000L
        private const val ANCHOR_POLL_INTERVAL_MILLIS = 500L
        private const val BUILD_HEARTBEAT_TIMEOUT_SECONDS = 90L
        private const val MAX_PARALLEL_OBJECTS = 8
        @Volatile private var projectReadingMembers: Pair<String, List<String>>? = null
        private val REMOTE_OBJECT_KINDS = setOf("pdf", "source", "source_index", "voice")
        private val REMOTE_OBJECT_ROLES = setOf(
            "project_reading", "document_pdf", "document_reading_package",
            "pdf",
            "pdf_delta",
            "source_index",
            "source_manifest",
            "source_snapshot",
            "reverse_lookup",
            "raw_synctex_fallback",
            "source_resource",
            "renderer_evidence",
            "project_composition",
            "document_component_package",
            "component_review",
            "voice",
        )
        private val TERMINAL_ANCHOR_STATUSES = setOf(
            "succeeded",
            "needs_attention",
            "stale",
            "failed",
        )
        private val SOURCE_REQUEST_MUTEX = Mutex()
        /** Unknown snapshot folders M was already fetched for outside an explicit Sync (once per process). */
        private val SOURCE_FOLDER_RENAMES_CHECKED: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
        private const val SYNC_LOG_TAG_REVIEW_FOLLOW_UP = "ThoughtsSync"
        private const val REVIEW_PLACEMENT_FOLLOW_UP_MILLIS = 5 * 60_000L
        private const val REVIEW_PLACEMENT_POLL_MILLIS = 10_000L
        private val RUN_MUTEX = Mutex()
        private val SYNC_MUTEX = Mutex()

        /** At process start: finishes a source-folder-names migration a crash interrupted, under the Sync lock. */
        internal suspend fun resumeInterruptedSourceFolderNamesMigration(context: Context) {
            val migration = SourceFolderNamesMigration.forContext(context, AppDatabase.getInstance(context))
            if (!migration.interrupted()) return
            SYNC_MUTEX.withLock {
                if (migration.interrupted()) withContext(Dispatchers.IO) {
                    runCatching { migration.run(LibraryProjectStore(context).storedSourceFolderRenames()) }
                }.onFailure { Log.w("ThoughtsSync", "The source-folder-names migration is still incomplete", it) }
            }
        }

        /** At process start: finishes a default-folder migration a crash interrupted, under the Sync lock. */
        internal suspend fun resumeInterruptedDefaultFolderMigration(context: Context) {
            val migration = DefaultFolderMigration.forContext(context, AppDatabase.getInstance(context))
            if (!migration.interrupted()) return
            SYNC_MUTEX.withLock {
                if (migration.interrupted()) withContext(Dispatchers.IO) { runCatching { migration.run() } }
                    .onFailure { Log.w("ThoughtsSync", "The default-folder migration is still incomplete", it) }
            }
        }
        private val SOURCE_BUNDLE_PRUNE_WAITING = java.util.concurrent.atomic.AtomicBoolean(false)
        private val SOURCE_BUNDLE_PRUNE_SCOPE = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
        )
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val AGENT_PROPOSAL_ID = Regex(
            "^[a-z0-9][a-z0-9._:-]{0,127}$",
        )
        private fun stableOperationId(vararg parts: String): String {
            return UUID.nameUUIDFromBytes(parts.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)).toString()
        }

        private fun JsonObject.string(name: String): String {
            return get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        }

        private fun JsonObject.stringOrNull(name: String): String? {
            return get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }

        private fun JsonObject.timestamp(): String {
            for (name in listOf("updated_at", "deleted_at", "modified", "created")) {
                string(name).takeIf(String::isNotBlank)?.let { return it }
            }
            return Instant.now().toString()
        }

        private fun sha256(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }

        private fun fileSha256(file: File): String = verifiedFileCache.sha256(file)

        private fun String.isSafeStem(): Boolean =
            isNotBlank() && length <= 256 && !contains('/') && !contains('\\')

        private fun String.isSafeComponent(): Boolean =
            isNotBlank() && length <= 512 && !contains('/') && !contains('\\')

        private fun String.isSafePdfFilename(): Boolean =
            isSafeComponent() && endsWith(".pdf", ignoreCase = true)
    }
}

/**
 * The connected folders [snapshot] publishes from that neither the stored Project catalogue nor
 * Fedora's rename map knows: Fedora renamed folders since this phone last read M (or no
 * manifest declares the folder).
 */
internal fun unknownSnapshotFolders(
    snapshot: LibrarySnapshot,
    catalogue: com.google.gson.JsonArray,
    renames: List<com.gitlab.mudlej.MjPdfReader.data.source.SourceFolderNames.Rename>,
): Set<String> {
    val known = buildSet {
        renames.forEach { add(it.old); add(it.new) }
        for (project in catalogue) {
            val folders = project.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("project_configuration")
                ?.getAsJsonArray("source_folders") ?: continue
            for (folder in folders) folder.takeIf { it.isJsonObject }?.asJsonObject?.get("path")
                ?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
        }
    }
    return snapshot.publications.mapNotNullTo(linkedSetOf()) { publication ->
        com.gitlab.mudlej.MjPdfReader.data.source.SourceOwner.of(publication.project)?.takeIf { owner ->
            !com.gitlab.mudlej.MjPdfReader.data.source.SourceOwner.isDefaultFolder(owner) && owner !in known
        }
    }
}
