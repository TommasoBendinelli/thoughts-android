// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.sync.SyncDiagnostics
import com.gitlab.mudlej.MjPdfReader.data.sync.verifiedFileCache
import com.gitlab.mudlej.MjPdfReader.data.sync.measureSyncDiagnostic
import com.gitlab.mudlej.MjPdfReader.data.sync.withoutStageMessages
import com.gitlab.mudlej.MjPdfReader.data.sync.diagnostics
import com.gitlab.mudlej.MjPdfReader.data.sync.measureSyncStage
import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewPlacementProof
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewSourceTarget
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.tbend.thoughts.sdk.SyncCore
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

data class OfflineExternalEdit(
    val snapshot: SourceSnapshot,
    val contents: String,
    /**
     * True for a change in the shared folder that Thoughts did not write and the user has not
     * confirmed: Sync does not queue it until they choose to upload or discard it.
     */
    val outside: Boolean = false,
)

data class OfflineQueueState(
    val requestId: String,
    val draftRevision: Int,
)

/** The two versions of a source file needing a merge: the phone copy and the verified (synced) source. */
internal data class SourceMergeVersions(
    val sourceFile: String,
    val phoneContents: String,
    val phoneSha256: String,
    val syncedContents: String,
    val syncedSha256: String,
)

/** Where a verified source sits in the active bundle, as its working-copy state records it. */
private data class VerifiedSourceEntry(
    val sha256: String,
    val objectId: String,
    val reference: Pair<String, String>,
    val pdfFilenames: List<String>,
)

internal data class OfflineBundleSource(
    val path: String,
    val sha256: String,
    val objectId: String,
    val bytes: Long,
)

internal data class OfflineBundleResource(
    val kind: String,
    val path: String,
    val sha256: String,
    val objectId: String,
    val bytes: Long,
    val pdfFilenames: List<String>,
)

internal data class OfflineBundleInput(
    val synctexPath: String,
    val sourceFile: String,
    val sourceSha256: String,
)

internal data class OfflineBundleSegment(
    val startPage: Int,
    val pageCount: Int,
    val sourceAvailable: Boolean,
    val primarySourceFile: String?,
    val synctexObject: String?,
    val synctexSha256: String?,
    val inputs: List<OfflineBundleInput>,
    val sourceFormat: String = "latex",
    val rendererEvidenceObject: String? = null,
    val fingerprint: String = "",
    val rawSyncTexObject: String? = null,
    val sourceId: String = "",
)

internal data class OfflineBundlePdf(
    val filename: String,
    val pdfSha256: String,
    val pageCount: Int,
    val segments: List<OfflineBundleSegment>,
    val placementSources: JsonArray = JsonArray(),
    val sourceRevisionEquivalences: JsonArray = JsonArray(),
)

internal data class OfflineProposalQuad(
    val role: String,
    val pdfFilename: String,
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val normalized: Boolean = false,
    /**
     * A LaTeX-made copy (contents entry, running head), not the source's own
     * occurrence. False in bundles and rows written before servers marked copies.
     */
    val duplicate: Boolean = false,
) : java.io.Serializable

internal data class OfflineAgentProposal(
    val bundleId: String,
    val objectSha256: String,
    val id: String,
    val kind: String,
    val comment: String,
    val sourceFile: String,
    val sourceSha256: String,
    val line: Int,
    val bodyPreview: String,
    val beforePreview: String,
    val afterPreview: String,
    val parentId: String?,
    val suppressed: Boolean,
    val pdfFilenames: List<String>,
    val quads: List<OfflineProposalQuad>,
    val correspondence: ReviewCorrespondence? = null,
    /** Null in rows stored before this field existed. */
    val createdAt: String? = null,
) : java.io.Serializable

internal data class OfflineSourceReviewComment(
    val id: String,
    val contents: String,
    val sourceFile: String,
    val sourceSha256: String,
    val line: Int,
    val pdfFilenames: List<String>,
) : java.io.Serializable

/** The scope reason and "Anchor removed" state come from [payloadJson] (see [anchorRemoved] below). */
internal data class OfflineLogicalComment(
    val id: String,
    val threadRootId: String,
    val parentId: String?,
    val revision: Long,
    val deleted: Boolean,
    val scopeState: String,
    val sourceFile: String,
    val sourceLine: Int,
    val originCollection: String,
    val originPageIndex: Int,
    val payloadJson: String,
    val correspondence: ReviewCorrespondence? = null,
) : java.io.Serializable {
    val locations: List<LogicalCommentPdfLocation>
        get() = correspondence?.locations.orEmpty()
}

/** Read from the raw payload, outside the class, so cached projections keep their serialized shape. */
internal val OfflineLogicalComment.anchorRemoved: Boolean
    get() = parentId == null && CommentAnchorState.isAnchorRemovedPayloadJson(payloadJson)

internal data class OfflineOverviewSourceSnapshot(
    val bundleId: String? = null,
    val proposals: List<OfflineAgentProposal> = emptyList(),
    val reviewComments: List<OfflineSourceReviewComment> = emptyList(),
    val reviewCommentDocuments: Set<String> = emptySet(),
    val logicalComments: List<OfflineLogicalComment> = emptyList(),
    val pdfSha256ByFilename: Map<String, String> = emptyMap(),
    /** Proposals removed outside Thoughts (agent-proposal spec section 10); listed under Issues. */
    val removedProposals: List<RemovedOutsideProposal> = emptyList(),
) : java.io.Serializable

internal data class OfflineSourceBundle(
    val bundleId: String,
    val pdfs: Map<String, OfflineBundlePdf>,
    val sources: Map<String, OfflineBundleSource>,
    val resources: Map<String, OfflineBundleResource>,
    val proposals: Map<String, OfflineAgentProposal>,
    val reviewComments: Map<String, OfflineSourceReviewComment>,
    val reviewCommentDocuments: Set<String>,
    val logicalComments: Map<String, OfflineLogicalComment> = emptyMap(),
    val reviewCorrespondences: Map<String, ReviewCorrespondence> = emptyMap(),
    val componentObjectIds: Set<String> = emptySet(),
)

/** Only recorded build dependencies participate in live source tracking. */
internal fun OfflineSourceBundle.trackedSourcePaths(): List<String> = pdfs.values
    .flatMap { pdf -> pdf.segments.flatMap { segment -> segment.inputs.map { it.sourceFile } + listOfNotNull(segment.primarySourceFile) } }
    .distinct()
    .filter { it.endsWith(".tex") || it.endsWith(".md") || it.endsWith(".markdown") }

private data class WorkingSourceState(
    val sourceFile: String,
    val bundleId: String,
    val baseSha256: String,
    val baseObject: String,
    val pdfFilename: String,
    val pdfSha256: String,
    val pdfFilenames: List<String>,
    val requestId: String? = null,
    val draftRevision: Int = 0,
    val lastQueuedSha256: String? = null,
    val observedSizeBytes: Long? = null,
    val observedModifiedAtMillis: Long? = null,
    /** The contents Thoughts itself last wrote to the working file, or the user confirmed for upload. */
    val appWrittenSha256: String? = null,
)

private data class WorkingFileObservation(
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

private data class WorkingObservationIndex(
    val stateNamesSha256: String,
    val files: MutableMap<String, WorkingFileObservation>,
)

class OfflineSourceBundleStore internal constructor(
    private val stagingDirectory: File,
    workingDirectory: File,
    private val privateDirectory: File,
    private val projectVersion: String? = null,
    private val readerIndex: File? = null,
    private val currentAnnotations: CurrentSourceAnnotations? = null,
    /**
     * The working copy as the app's setting places it; asked at every use, since the setting can
     * move it while this store lives. Null: always [workingDirectory], which no other app writes.
     */
    private val workingCopy: (() -> SourceWorkingCopy)? = null,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "source-editor/bundle-staging"),
        // Looked up before this store writes anything: an installation's place is decided at its first look.
        workingDirectory(context),
        File(context.filesDir, "source-editor/offline"),
        readerIndex = File(com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue.defaultCanonicalPdfDirectory(), ".thoughts-project-sources.json"),
        currentAnnotations = CurrentSourceAnnotations(com.gitlab.mudlej.MjPdfReader.data.AppDatabase.getInstance(context).currentSourceAnnotationDao()),
        workingCopy = { SourceWorkingLocation.workingCopy(context) },
    )

    private val fixedWorkingDirectory = workingDirectory
    private val workingDirectory: File get() = workingCopy?.invoke()?.directory ?: fixedWorkingDirectory

    /** In the shared folder another app can change a source: such a change waits for the user. */
    private val holdsOutsideChanges: Boolean get() = workingCopy?.invoke()?.shared == true

    internal val annotationIndex get() = SourceAnnotationIndex.at(File(privateDirectory, "source-location-index.json"))
    private val privateObjects = File(privateDirectory, "objects")
    private val reusableObjects = File(privateDirectory, "object-cache")
    private val workingStates = File(privateDirectory, "working")
    private val metadataDirectory = projectVersion?.let { File(privateDirectory, "projects/$it") } ?: privateDirectory
    private val activeIndex = File(metadataDirectory, "active-index.json")
    private val observationIndex = File(privateDirectory, "working-observations.json")
    private val overviewProjection = File(metadataDirectory, OVERVIEW_PROJECTION_FILENAME)
    private val gson = Gson()
    /** The bundle ID is its manifest hash, so a matching active ID safely reuses the parsed value. */
    private var activeBundleCache: Pair<String, OfflineSourceBundle>? = null
    private var parsedManifestCache: Pair<String, OfflineSourceBundle>? = null
    private var sourceOpenBundleCache: Pair<String, OfflineSourceBundle>? = null
    /** [SourceFolderNames.generation] the parsed bundles above were read with. */
    private var renameGeneration = SourceFolderNames.generation

    /** Bundles parsed before this phone moved its source folders hold the old names: parse again. */
    private fun followSourceFolderRenames() {
        val current = SourceFolderNames.generation
        if (current == renameGeneration) return
        renameGeneration = current
        activeBundleCache = null
        parsedManifestCache = null
        sourceOpenBundleCache = null
    }
    private val sourceOpenIndex = SourceOpenIndex(
        File(metadataDirectory, "source-open-index-v2.json"),
        ::parseManifestUncached,
        ::atomicWrite,
    )

    @Synchronized
    internal fun importLatest(): OfflineSourceBundle? {
        importStagedLatest()
        return loadActiveBundle()
    }

    /** Imports a newly staged bundle without reparsing the active manifest when nothing is staged. */
    @Synchronized
    internal fun importStagedLatest(): Boolean {
        if (projectVersion != null) return false
        val stagedIndex = parseObject(File(stagingDirectory, INDEX_FILENAME), MAX_INDEX_BYTES)
        val stagedBundleId = stagedIndex
            ?.takeIf { it.int("schema_version") == SCHEMA_VERSION }
            ?.string("bundle_id")
            ?.takeIf(::isSha256)
        if (stagedBundleId != null) {
            val manifestObject = stagedIndex.string("manifest_object").takeIf(::isSha256)
            if (manifestObject == stagedBundleId && activeBundleId() != stagedBundleId) {
                return importStagedBundle(stagedBundleId) != null
            }
        }
        return false
    }

    @Synchronized
    internal fun installDownloaded(
        indexRaw: ByteArray,
        objects: Map<String, ByteArray>,
        progress: ((String) -> Unit)? = null,
    ): OfflineSourceBundle? {
        cleanupReceipt.delete()
        val index = runCatching {
            JsonParser.parseString(indexRaw.toString(StandardCharsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return null
        if (index.int("schema_version") != SCHEMA_VERSION) return null
        val bundleId = index.string("bundle_id").takeIf(::isSha256) ?: return null
        if (index.string("manifest_object") != bundleId) return null
        objects.forEach { (digest, content) ->
            stageDownloadedObject(digest, content)
        }
        val manifest = readVerifiedObject(File(stagingDirectory, "objects/$bundleId"), bundleId, MAX_MANIFEST_BYTES)
            ?: readVerifiedObject(File(privateObjects, bundleId), bundleId, MAX_MANIFEST_BYTES)
            ?: readVerifiedObject(File(reusableObjects, bundleId), bundleId, MAX_MANIFEST_BYTES)
            ?: return null
        if (projectReferences(manifest) != null) {
            return installProjectIndex(bundleId, manifest, progress)
        }
        if (measureSyncDiagnostic("validate_install_manifest", progress) { parseManifest(bundleId, manifest) } == null) return null
        atomicWrite(File(stagingDirectory, INDEX_FILENAME), indexRaw)
        val activated = importStagedBundle(bundleId, progress) ?: return null
        measureSyncStage("Removing source staging files", progress) { stagingDirectory.deleteRecursively() }
        measureSyncStage("Pruning inactive working source files", progress) { pruneWorkingTree(activated) }
        measureSyncStage("Pruning unused source cache", progress) { prunePrivateObjects(activated) }
        SourceCommentSnapshot.invalidate()
        return activated
    }

    @Synchronized
    internal fun stageDownloadedFile(digest: String, source: File): Boolean = runCatching {
        require(isSha256(digest) && hasVerifiedObject(source, digest, MAX_OBJECT_BYTES))
        copyVerifiedObject(source, File(stagingDirectory, "objects/$digest"), digest)
        true
    }.getOrDefault(false)

    private fun copyVerifiedObject(source: File, destination: File, digest: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            // Objects are immutable and content-addressed, and staging copies are deleted after
            // installation: a hard link keeps the verified bytes instead of copying and re-hashing them.
            val linked = runCatching { verifiedFileCache.link(source, temporary) }.isSuccess
            if (!linked) source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(hasVerifiedObject(temporary, digest, MAX_OBJECT_BYTES))
            check(verifiedFileCache.rename(temporary, destination))
        } finally { temporary.delete() }
    }

    internal fun stageDownloadedObject(digest: String, content: ByteArray): Boolean {
        if (!isSha256(digest) || sha256(content) != digest || content.size > MAX_OBJECT_BYTES) {
            return false
        }
        atomicWrite(File(stagingDirectory, "objects/$digest"), content)
        return true
    }

    @Synchronized
    internal fun missingObjectIds(
        indexRaw: ByteArray,
        manifestRaw: ByteArray,
        progress: ((String) -> Unit)? = null,
    ): Set<String>? {
        val index = runCatching {
            JsonParser.parseString(indexRaw.toString(StandardCharsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return null
        if (index.int("schema_version") != SCHEMA_VERSION) return null
        val bundleId = index.string("bundle_id").takeIf(::isSha256) ?: return null
        if (index.string("manifest_object") != bundleId || sha256(manifestRaw) != bundleId) return null
        progress.diagnostics()?.decision("parsed_manifest_cache",
            if (parsedManifestCache?.first == bundleId) "hit" else "miss")
        progress.diagnostics()?.count("manifest_parse_input", "bytes" to manifestRaw.size.toLong())
        val installedProjects = activeProjectReferences().associateBy { it.string("filename") }
        val required = projectReferences(manifestRaw)?.let { references ->
            buildSet {
                add(bundleId)
                for (reference in references) {
                    // Already installed projects retain their own verification receipt.
                    // A pull for A must not read or repair B's objects.
                    if (installedProjects[reference.string("filename")] == reference) continue
                    val digest = reference.string("manifest_object")
                    val child = projectStore(digest)
                    val bundle = child.loadSourceOpenBundle(false) ?: readManifestObject(digest)?.let { child.parseManifest(digest, it) }
                        ?: return null
                    addAll(requiredObjectIds(bundle))
                }
            }
        } ?: run {
            val bundle = measureSyncStage("Parsing source manifest", progress) { parseManifest(bundleId, manifestRaw) } ?: return null
            requiredObjectIds(bundle)
        }
        progress.diagnostics()?.count("required_source_objects", "objects" to required.size.toLong())
        return required.filterTo(linkedSetOf()) { objectId ->
            !hasVerifiedObject(File(stagingDirectory, "objects/$objectId"), objectId, MAX_OBJECT_BYTES) &&
                !hasVerifiedObject(File(privateObjects, objectId), objectId, MAX_OBJECT_BYTES) &&
                !hasVerifiedObject(File(reusableObjects, objectId), objectId, MAX_OBJECT_BYTES)
        }
    }

    /**
     * Removes what the active source generation no longer uses. [inventoryCheckEvery]: once the
     * active generation has been pruned, the walk over every file that would notice files left
     * behind since (about 2 s on a large Library, after every Sync that installed something) runs
     * only every that many calls; a new generation, whose superseded objects are the garbage,
     * is always pruned.
     */
    @Synchronized
    internal fun pruneActiveBundle(
        alreadyPrunedBundleId: String? = null,
        diagnostics: SyncDiagnostics? = null,
        inventoryCheckEvery: Int = CLEANUP_INVENTORY_CHECK_EVERY,
        cooperate: () -> Unit = {},
    ): Boolean {
        // The receipt is local to this sync attempt, issued only after install cleanup succeeds.
        // Read the small active index again: a different generation must take the recovery path.
        if (alreadyPrunedBundleId != null && alreadyPrunedBundleId == activeBundleId()) {
            diagnostics?.decision("source_bundle_cleanup", "already_pruned_during_install")
            return true
        }
        val activeId = activeBundleId() ?: return false
        val receipt = parseObject(cleanupReceipt, MAX_INDEX_BYTES)
        // A new bundle cannot reuse the old receipt, so avoid an extra tree
        // scan before the cleanup that is already required.
        fun <T> step(stage: String, block: () -> T): T = diagnostics?.measure(stage, block) ?: block()
        if (receipt?.string("bundle_id") == activeId) {
            val skipped = receipt.int("skipped_inventory_checks")
            if (skipped + 1 < inventoryCheckEvery) {
                atomicWrite(cleanupReceipt, receipt.deepCopy().apply {
                    addProperty("skipped_inventory_checks", skipped + 1)
                }.toString().toByteArray(StandardCharsets.UTF_8))
                diagnostics?.decision("source_bundle_cleanup", "active_bundle_already_pruned")
                return true
            }
        }
        val inventory = if (receipt?.string("bundle_id") == activeId) step("source_cleanup_inventory", ::cleanupInventory) else null
        if (inventory != null && receipt?.string("inventory_sha256") == inventory) {
            atomicWrite(cleanupReceipt, JsonObject().apply {
                addProperty("bundle_id", activeId)
                addProperty("inventory_sha256", inventory)
            }.toString().toByteArray(StandardCharsets.UTF_8))
            diagnostics?.decision("source_bundle_cleanup", "unchanged_cleanup_inventory")
            return true
        }
        diagnostics?.decision("source_bundle_cleanup", "prune_active_bundle")
        val bundle = step("source_cleanup_load_bundle") { loadSourceOpenBundle(false) } ?: return false
        cooperate()
        step("source_cleanup_working_tree") { pruneWorkingTree(bundle) }
        cooperate()
        prunePrivateObjects(bundle, diagnostics, cooperate) { stage, block -> step(stage, block) }
        cooperate()
        if (activeBundleId() == activeId) step("source_cleanup_inventory", ::cleanupInventory)?.let { signature ->
            atomicWrite(cleanupReceipt, JsonObject().apply {
                addProperty("bundle_id", activeId)
                addProperty("inventory_sha256", signature)
            }.toString().toByteArray(StandardCharsets.UTF_8))
        }
        return true
    }

    // This receipt skips garbage collection only; it never verifies source/PDF
    // contents or acknowledges local edits. Any file addition, removal or observed
    // modification forces the normal cleanup path, including after an app restart.
    private val cleanupReceipt get() = File(metadataDirectory, "cleanup-receipt.json")

    private fun cleanupInventory(): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((index, root) in listOf(privateObjects, reusableObjects, workingStates, workingDirectory).withIndex()) {
            if (!root.exists()) continue
            check(root.isDirectory)
            // Same signature as before; the relative path is computed once per file instead of
            // twice per sort comparison (relativeTo() normalises both paths each time).
            val prefix = root.path.length + 1
            val files = root.walkTopDown().onFail { _, error -> throw error }
                .filter { it.isFile }
                .map { file -> file.path.substring(prefix) to "\u0000${file.length()}\u0000${file.lastModified()}\n" }
                .sortedBy { it.first }
            for ((path, facts) in files) digest.update("$index\u0000$path$facts".toByteArray(StandardCharsets.UTF_8))
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    @Synchronized
    internal fun agentProposals(pdfFilename: String? = null): List<OfflineAgentProposal> {
        return overviewSourceSnapshot(pdfFilename).proposals
            .asSequence()
            .filter { proposal ->
                pdfFilename == null || pdfFilename in proposal.pdfFilenames ||
                    proposal.correspondence?.locations?.any { it.pdfFilename == pdfFilename } == true
            }
            .map { proposal ->
                val location = annotationIndex.rememberedLocation("proposal", proposal.id, SourceOwner.of(proposal.sourceFile).orEmpty())
                if (location.get("state")?.asString == "linked") proposal.copy(
                    sourceFile = location.get("source_file").asString,
                    line = location.get("start_line").asInt,
                ) else proposal.copy(line = 0)
            }
            .sortedWith(compareBy(OfflineAgentProposal::sourceFile, OfflineAgentProposal::line, OfflineAgentProposal::id))
            .toList()
    }

    @Synchronized
    internal fun withCurrentProposals(snapshot: OfflineOverviewSourceSnapshot, records: List<JsonObject>, filename: String?): OfflineOverviewSourceSnapshot {
        val proposals = snapshot.proposals.associateBy { it.id }.toMutableMap()
        // Many proposals share a source file; read its working state once per call.
        val unsentEdits = hashMapOf<String, Boolean>()
        val workingIds = hashMapOf<String, Set<String>>()
        val removed = mutableListOf<RemovedOutsideProposal>()
        for (record in records) {
            val source = record.string("source_file")
            val id = record.string("proposal_id")
            if (!isSafeSourcePath(source) || !isAgentProposalId(id)) continue
            if (record.string("status") == RemovedOutsideProposal.STATUS && record.get("deleted")?.asBoolean != true) {
                // Restored on this phone and not yet synced: the wrapper is back in the working copy.
                val local = workingIds.getOrPut(source) {
                    safeWorkingFile(source)?.takeIf { it.isFile }?.let(::readUtf8)
                        ?.let { text -> AgentProposals.parse(source, text).proposals.mapTo(hashSetOf()) { it.id } }
                        .orEmpty()
                }
                if (id in local) continue
                val previous = proposals.remove(id)
                val placed = previous ?: snapshot.proposals.firstOrNull { it.sourceFile == source && it.pdfFilenames.isNotEmpty() }
                RemovedOutsideProposal.from(record, placed?.let { it.pdfFilenames + it.correspondence?.locations.orEmpty().map { location -> location.pdfFilename } }.orEmpty().distinct())
                    ?.let(removed::add)
                continue
            }
            val hasUnsentEdit = unsentEdits.getOrPut(source) {
                val working = readWorkingState(source) ?: return@getOrPut false
                // Unsent source edits take precedence over the canonical snapshot.
                val file = safeWorkingFile(source)
                working.requestId != null || working.draftRevision > 0 || file == null || !file.isFile ||
                    working.observedSizeBytes != file.length() || working.observedModifiedAtMillis != file.lastModified()
            }
            if (hasUnsentEdit) continue
            if (record.string("status") != "pending") { proposals.remove(id); continue }
            val sha = record.string("source_sha256")
            if (!isSha256(sha)) continue
            val previous = proposals[id]
            if (previous?.sourceSha256 == sha) continue
            // Canonical source content can exist before any PDF placement does.
            if (filename != null) continue
            val content = record.getAsJsonObject("snapshot") ?: continue
            // The proposal object identity belongs to the artifact that declared it; a source
            // SHA-256 there made every server resolution of this proposal fail as stale.
            proposals[id] = OfflineAgentProposal(
                previous?.bundleId ?: snapshot.bundleId.orEmpty(), previous?.objectSha256.orEmpty(), id, "edit",
                content.string("comment"), source, sha, 0,
                content.string("after"), content.string("before"), content.string("after"), null, false,
                emptyList(), emptyList(), createdAt = content.string("created_at").ifBlank { previous?.createdAt },
            )
        }
        return snapshot.copy(
            proposals = proposals.values.sortedWith(compareBy(OfflineAgentProposal::sourceFile, OfflineAgentProposal::id)),
            removedProposals = removed.sortedWith(compareBy(RemovedOutsideProposal::sourceFile, RemovedOutsideProposal::line, RemovedOutsideProposal::id)),
        )
    }

    @Synchronized
    internal fun reviewComments(pdfFilename: String? = null): List<OfflineSourceReviewComment> {
        return overviewSourceSnapshot(pdfFilename).reviewComments
            .asSequence()
            .filter { pdfFilename == null || pdfFilename in it.pdfFilenames }
            .sortedWith(compareBy(OfflineSourceReviewComment::sourceFile, OfflineSourceReviewComment::line, OfflineSourceReviewComment::id))
            .toList()
    }

    @Synchronized
    /** Read only the current database comment rows for the installed publication. */
    internal fun catalogueCommentSnapshot(): OfflineOverviewSourceSnapshot {
        importStagedLatest()
        repeat(2) {
            val id = activeBundleId() ?: return OfflineOverviewSourceSnapshot()
            val refs = if (projectVersion == null) readProjectReferences(id) else null
            val result = if (refs != null) {
                val stores = refs.associate { ref -> ref.string("manifest_object").let { it to projectStore(it) } }
                val batched = runCatching {
                    currentAnnotations?.readCommentsForGenerations(stores.entries.associate { (digest, store) ->
                        store.overviewProjection.absolutePath to digest
                    })
                }.getOrNull().orEmpty()
                val bundles = refs.map { ref ->
                    val store = stores.getValue(ref.string("manifest_object"))
                    val snapshot = batched[store.overviewProjection.absolutePath] ?: store.catalogueCommentSnapshot()
                    check(snapshot.bundleId == ref.string("manifest_object")) { "Missing current comment records" }
                    OfflineSourceBundle(snapshot.bundleId!!, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
                        emptySet(), snapshot.logicalComments.associateBy { it.id }) to ref.get("pdf_stale").asBoolean
                }
                OfflineOverviewSourceSnapshot(bundleId = id, logicalComments = combineProjectBundles(id, bundles).logicalComments.values.toList())
            } else {
                runCatching { currentAnnotations?.readComments(overviewProjection.absolutePath, id) }.getOrNull() ?: run {
                    // One-time upgrade/recovery populates current rows; ordinary reads never rebuild.
                    val snapshot = overviewSourceSnapshot()
                    currentAnnotations?.write(overviewProjection.absolutePath, snapshot)
                    currentAnnotations?.readComments(overviewProjection.absolutePath, id) ?: snapshot
                }
            }
            if (activeBundleId() == id) return result
        }
        error("Source publication changed during catalogue loading")
    }

    internal fun overviewSourceSnapshot(pdfFilename: String? = null): OfflineOverviewSourceSnapshot {
        importStagedLatest()
        repeat(2) {
            val bundleId = activeBundleId() ?: return OfflineOverviewSourceSnapshot()
            // Project children are manifests, not indexes; do not read their full source graph
            // just to discover that fact before loading the compact review projection.
            val references = if (projectVersion == null) readProjectReferences(bundleId) else null
            if (references != null) {
                val selectedReferences = references.filter { pdfFilename == null || it.string("filename").equals(pdfFilename, ignoreCase = true) }
                val loadedAt = System.nanoTime()
                val slowest = java.util.concurrent.ConcurrentLinkedQueue<Triple<Long, String, Long>>()
                fun load(reference: JsonObject): Pair<OfflineOverviewSourceSnapshot, Boolean> {
                    val started = System.nanoTime()
                    val child = projectStore(reference.string("manifest_object"))
                    val snapshot = child.overviewSourceSnapshot()
                    slowest += Triple((System.nanoTime() - started) / 1_000_000, reference.string("filename"), child.overviewProjection.length())
                    check(snapshot.bundleId == reference.string("manifest_object")) { "Missing project review projection" }
                    return snapshot to reference.get("pdf_stale").asBoolean
                }
                // Each project reads and parses only its own projection; on a cold start reading
                // 150 of them one by one took about 3 s before Home could show every comment.
                val snapshots = if (selectedReferences.size < 8) selectedReferences.map(::load) else {
                    val executor = Executors.newFixedThreadPool(4)
                    try {
                        executor.invokeAll(selectedReferences.map { reference -> Callable { load(reference) } }).map { future ->
                            try { future.get() } catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
                        }
                    } finally { executor.shutdownNow() }
                }
                val loadedMillis = (System.nanoTime() - loadedAt) / 1_000_000
                val bundles = snapshots.map { (snapshot, stale) ->
                    OfflineSourceBundle(snapshot.bundleId!!, emptyMap(), emptyMap(), emptyMap(),
                        snapshot.proposals.associateBy { it.id }, snapshot.reviewComments.associateBy { it.id },
                        snapshot.reviewCommentDocuments, snapshot.logicalComments.associateBy { it.id }) to stale
                }
                val merged = overviewSourceSnapshot(combineProjectBundles(bundleId, bundles)).copy(
                    pdfSha256ByFilename = snapshots.flatMap { (snapshot, _) ->
                        snapshot.pdfSha256ByFilename.entries
                    }.associate { it.toPair() },
                )
                if (pdfFilename == null) com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log(
                    "overview projections: ${selectedReferences.size} loaded in $loadedMillis ms, merged in " +
                        "${(System.nanoTime() - loadedAt) / 1_000_000 - loadedMillis} ms; slowest " +
                        slowest.sortedByDescending { it.first }.take(6).joinToString { "${it.second} ${it.first} ms ${it.third / 1024} KiB" } +
                        "; total ${slowest.sumOf { it.third } / 1024} KiB")
                if (activeBundleId() == bundleId) return merged
                return@repeat
            }
            val snapshot = SourceProjectionMemoryCache.load(overviewProjection, bundleId) {
                readOverviewProjection(bundleId) ?: loadActiveBundle()?.let { bundle ->
                    writeOverviewProjection(overviewSourceSnapshot(bundle))
                }
            }
            if (activeBundleId() == bundleId) {
                // Reading the derived projection must not keep a full fallback/import graph alive.
                activeBundleCache = null
                parsedManifestCache = null
                return snapshot ?: OfflineOverviewSourceSnapshot()
            }
        }
        // A concurrently activated generation must never receive older geometry.
        return OfflineOverviewSourceSnapshot()
    }

    private fun overviewSourceSnapshot(bundle: OfflineSourceBundle): OfflineOverviewSourceSnapshot =
        OfflineOverviewSourceSnapshot(
            bundleId = bundle.bundleId,
            proposals = bundle.proposals.values.sortedWith(
                compareBy(OfflineAgentProposal::sourceFile, OfflineAgentProposal::line, OfflineAgentProposal::id),
            ),
            reviewComments = bundle.reviewComments.values.sortedWith(
                compareBy(OfflineSourceReviewComment::sourceFile, OfflineSourceReviewComment::line, OfflineSourceReviewComment::id),
            ),
            reviewCommentDocuments = bundle.reviewCommentDocuments,
            logicalComments = bundle.logicalComments.values.sortedWith(
                compareBy<OfflineLogicalComment> { it.parentId != null }
                    .thenBy { it.threadRootId }
                    .thenBy { it.id },
            ),
            pdfSha256ByFilename = bundle.pdfs.mapValues { (_, pdf) -> pdf.pdfSha256 },
        )

    @Synchronized
    internal fun hasReviewCommentProjection(pdfFilename: String): Boolean =
        pdfFilename in overviewSourceSnapshot(pdfFilename).reviewCommentDocuments

    @Synchronized
    fun resolve(selection: SourceSelection): SourceSnapshot? {
        val (segment, mapped) = mapSelection(selection, importStaged = true) ?: return null
        val sourceFile = mapped?.first ?: segment.primarySourceFile ?: return null
        val sourceLine = mapped?.second ?: 1
        val input = segment.inputs.singleOrNull { it.sourceFile == sourceFile }
        val navigationBase = input?.let { readBuildSource(it) }
        val state = readWorkingState(sourceFile) ?: return null
        val working = safeWorkingFile(sourceFile) ?: return null
        val contents = readUtf8(working) ?: return null
        val baseObject = File(privateObjects, state.baseObject)
        val baseContents = readUtf8(baseObject) ?: return null
        if (sha256(baseContents) != state.baseSha256) return null
        return SourceSnapshot(
            resolutionId = offlineResolutionId(state.bundleId, sourceFile),
            sourceFile = sourceFile,
            sourceLine = sourceLine.coerceAtLeast(1),
            sourceSha256 = state.baseSha256,
            contents = contents,
            navigationBaseContents = navigationBase.takeIf { mapped != null },
            navigationContext = selection.contextText,
            bundleId = state.bundleId,
            baseContents = baseContents,
            bundlePdfFilename = state.pdfFilename,
            bundlePdfSha256 = state.pdfSha256,
            bundlePdfFilenames = state.pdfFilenames,
        )
    }

    /**
     * The source file and line a selection point was built from, for "Copy for agent". Unlike
     * [resolve] it reads no source and imports nothing, and it answers only when the lookup maps
     * the point exactly and the synced source is still the one the PDF was built from: once a
     * source moves on, its line numbers no longer match the PDF.
     */
    @Synchronized
    internal fun sourceLine(selection: SourceSelection): Pair<String, Int>? {
        val (segment, mapped) = mapSelection(selection, importStaged = false) ?: return null
        val (sourceFile, line) = mapped ?: return null
        val input = segment.inputs.singleOrNull { it.sourceFile == sourceFile } ?: return null
        if (line < 1 || readWorkingState(sourceFile)?.baseSha256 != input.sourceSha256) return null
        return mapped
    }

    /** The source segment holding a selection point and, when the lookup maps it, its file and line. */
    private fun mapSelection(
        selection: SourceSelection,
        importStaged: Boolean,
    ): Pair<OfflineBundleSegment, Pair<String, Int>?>? {
        val bundle = loadSourceOpenBundle(importStaged = importStaged, pdfFilename = selection.pdfFilename) ?: return null
        val pdf = bundle.pdfs[selection.pdfFilename]
            ?.takeIf { it.pdfSha256 == selection.pdfSha256 }
            ?: return null
        val segment = pdf.segments.firstOrNull {
            selection.page >= it.startPage && selection.page < it.startPage + it.pageCount
        }?.takeIf(OfflineBundleSegment::sourceAvailable) ?: return null
        // The server checks Markdown renderer evidence against this segment before
        // publishing it, so the reader does not download or reopen that evidence.
        if (segment.sourceFormat == "markdown" && segment.rendererEvidenceObject == null) return null
        val synctexObject = segment.synctexObject ?: return null
        val localPage = selection.page - segment.startPage + 1
        val mapped = if (segment.sourceFormat == "latex" && segment.rawSyncTexObject != null) {
            resolveReference(segment, localPage, selection.x, selection.y)
        } else {
            // Older bundles and Markdown keep their published geometry index.
            // The source editor still verifies the text before selecting exact words.
            OfflineSyncTexResolver.resolve(
                File(privateObjects, synctexObject), localPage, selection.x, selection.y,
                segment.inputs,
                if (segment.sourceFormat == "markdown") "thoughts-markdown-source-map-v1" else "thoughts-reverse-synctex-v1",
            )
        }
        return segment to mapped
    }

    private fun resolveReference(segment: OfflineBundleSegment, page: Int, x: Float, y: Float): Pair<String, Int>? {
        val hash = segment.rawSyncTexObject ?: return null
        val raw = File(privateObjects, hash)
        if (!raw.isFile || raw.length() !in 1..MAX_OBJECT_BYTES || verifiedFileCache.sha256(raw) != hash) return null
        val directory = File(privateDirectory, "synctex-native").apply { mkdirs() }
        val data = File(directory, "$hash.synctex.gz")
        if (!data.isFile || verifiedFileCache.sha256(data) != hash) {
            val temporary = File.createTempFile(hash, ".tmp", directory)
            try {
                raw.copyTo(temporary, overwrite = true)
                if (!temporary.renameTo(data) && (!data.isFile || verifiedFileCache.sha256(data) != hash)) return null
            } finally { temporary.delete() }
        }
        val results = com.shockwave.pdfium.PdfiumCore.referenceSyncTex(
            File(directory, "$hash.pdf").absolutePath, page, x, y,
        ) ?: return null
        for (result in results.filterNotNull()) {
            val parts = result.split('\t', limit = 2)
            val line = parts.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: continue
            val input = segment.inputs.singleOrNull { it.synctexPath == parts.getOrNull(1) } ?: continue
            return input.sourceFile to line
        }
        return null
    }

    @Synchronized
    internal fun htmlAttachment(path: String, pdfFilename: String?): File? {
        val bundle = loadSourceOpenBundle(importStaged = false, pdfFilename = pdfFilename) ?: return null
        val resource = bundle.resources[path]?.takeIf { it.kind == "html_attachment" && (pdfFilename == null || pdfFilename in it.pdfFilenames) } ?: return null
        return File(privateObjects, resource.objectId).takeIf {
            it.length() == resource.bytes && hasVerifiedObject(it, resource.sha256, 100_000_000L)
        }
    }

    /**
     * The attachment [path] as the Library has it now for [pdfFilename] (any PDF when null), with
     * its SHA-256; when the path is gone, the cached version [fallbackSha256] if this phone still
     * has that object (a card made on it can still be reviewed).
     */
    @Synchronized
    internal fun htmlAttachmentFile(path: String, pdfFilename: String?, fallbackSha256: String? = null):
        com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentFile? {
        val bundle = loadSourceOpenBundle(importStaged = false, pdfFilename = pdfFilename)
        val resource = bundle?.resources?.get(path)?.takeIf {
            it.kind == "html_attachment" && (pdfFilename == null || pdfFilename in it.pdfFilenames)
        } ?: if (pdfFilename != null) {
            // A link followed from another document of the same folder: any PDF that has it.
            loadSourceOpenBundle(importStaged = false)?.resources?.get(path)?.takeIf { it.kind == "html_attachment" }
        } else null
        resource?.let { found ->
            File(privateObjects, found.objectId).takeIf {
                it.length() == found.bytes && hasVerifiedObject(it, found.sha256, 100_000_000L)
            }?.let { return com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentFile(path, it, found.sha256) }
        }
        val sha = fallbackSha256?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        return File(privateObjects, sha).takeIf { it.length() in 1..100_000_000L && hasVerifiedObject(it, sha, 100_000_000L) }
            ?.let { com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentFile(path, it, sha) }
    }

    internal fun sourceWorkingFile(sourceFile: String): File? = safeWorkingFile(sourceFile)

    fun sourceSnapshot(sourceFile: String, sourceLine: Int): SourceSnapshot? {
        importStagedLatest()
        val state = readWorkingState(sourceFile) ?: return null
        val bundle = loadSourceOpenBundle(importStaged = false, pdfFilename = state.pdfFilename) ?: return null
        if (sourceFile !in bundle.sources && sourceFile !in bundle.resources) return null
        val working = safeWorkingFile(sourceFile) ?: return null
        val contents = readUtf8(working) ?: return null
        val baseContents = readUtf8(File(privateObjects, state.baseObject)) ?: return null
        // A successful save receipt advances the editable base before a new PDF
        // bundle arrives. The bundle identifies the document and its navigation
        // revision; the verified working state identifies the next edit's base.
        // This also recovers installations already in that intermediate state.
        if (sha256(baseContents) != state.baseSha256) return null
        // A different document can change the aggregate bundle ID while this source and
        // its PDF stay identical. Recover metadata left behind by the reuse fast path.
        val reference = if (sourceFile in bundle.sources) {
            sourceReference(bundle, sourceFile)
        } else {
            bundle.resources[sourceFile]?.pdfFilenames?.firstNotNullOfOrNull { filename ->
                bundle.pdfs[filename]?.let { filename to it.pdfSha256 }
            }
        }
        if (reference != (state.pdfFilename to state.pdfSha256)) return null
        if (state.bundleId != bundle.bundleId) {
            writeWorkingState(state.copy(bundleId = bundle.bundleId))
        }
        return SourceSnapshot(
            resolutionId = offlineResolutionId(bundle.bundleId, sourceFile),
            sourceFile = sourceFile,
            sourceLine = sourceLine.coerceAtLeast(1),
            sourceSha256 = state.baseSha256,
            contents = contents,
            bundleId = bundle.bundleId,
            baseContents = baseContents,
            bundlePdfFilename = state.pdfFilename,
            bundlePdfSha256 = state.pdfSha256,
            bundlePdfFilenames = state.pdfFilenames,
        )
    }

    @Synchronized
    fun writeWorkingContents(sourceFile: String, contents: String): Boolean {
        if (contents.toByteArray(StandardCharsets.UTF_8).size > SourceEditQueue.MAX_SOURCE_BYTES) {
            return false
        }
        val destination = safeWorkingFile(sourceFile) ?: return false
        val raw = contents.toByteArray(StandardCharsets.UTF_8)
        atomicWrite(destination, raw)
        recordAppWritten(sourceFile, raw)
        SourceCommentSnapshot.invalidate()
        SourceCommentChanges.changes.tryEmit(Unit)
        return true
    }

    /**
     * Remembers that Thoughts itself wrote [raw] to [sourceFile]'s working file, which tells its own
     * changes from another app's in the shared folder. There the bytes are kept too, so that
     * discarding an outside change can put back exactly what Thoughts had.
     */
    private fun recordAppWritten(sourceFile: String, raw: ByteArray) {
        val state = readWorkingState(sourceFile) ?: return
        val written = sha256(raw)
        if (holdsOutsideChanges) atomicWrite(appWrittenCopy(sourceFile), raw)
        if (state.appWrittenSha256 != written) writeWorkingState(state.copy(appWrittenSha256 = written))
    }

    private fun appWrittenCopy(sourceFile: String) = File(privateDirectory, "app-written/${sha256(sourceFile)}")

    internal fun lastQueuedSourceSha256(sourceFile: String): String? = readWorkingState(sourceFile)?.lastQueuedSha256

    /** The review outbox owns these bytes; do not enqueue a second independent source save. */
    @Synchronized
    internal fun recordLocalReviewChange(sourceFile: String, contentsSha256: String) {
        require(isSha256(contentsSha256))
        val state = readWorkingState(sourceFile) ?: return
        val working = safeWorkingFile(sourceFile)
        writeWorkingState(state.copy(lastQueuedSha256 = contentsSha256).withObservedFile(working))
        updateObservation(sourceFile, working)
    }

    @Synchronized
    fun recordQueued(
        sourceFile: String,
        requestId: String,
        draftRevision: Int,
        contentsSha256: String,
    ) {
        val state = readWorkingState(sourceFile) ?: return
        val working = safeWorkingFile(sourceFile)
        writeWorkingState(
            state.copy(
                requestId = requestId,
                draftRevision = draftRevision,
                lastQueuedSha256 = contentsSha256,
            ).withObservedFile(working),
        )
        updateObservation(sourceFile, working)
    }

    /** Repairs a crash-interrupted queue state without overwriting a newer external edit. */
    @Synchronized
    fun repairQueuedIfWorkingMatches(
        sourceFile: String,
        requestId: String,
        draftRevision: Int,
        contentsSha256: String,
    ): Boolean {
        if (!isUuid(requestId) || draftRevision <= 0 || !isSha256(contentsSha256)) return false
        val state = readWorkingState(sourceFile) ?: return false
        val working = safeWorkingFile(sourceFile)?.takeIf(File::isFile) ?: return false
        if (hashFile(working) != contentsSha256) return false
        writeWorkingState(
            state.copy(
                requestId = requestId,
                draftRevision = draftRevision,
                lastQueuedSha256 = contentsSha256,
            ).withObservedFile(working),
        )
        updateObservation(sourceFile, working)
        return true
    }

    @Synchronized
    fun queueState(sourceFile: String): OfflineQueueState? {
        val state = readWorkingState(sourceFile) ?: return null
        val requestId = state.requestId?.takeIf(::isUuid) ?: return null
        return OfflineQueueState(requestId, state.draftRevision)
    }

    @Synchronized
    fun applySuccessfulReceipt(snapshot: SourceSnapshot, deliveredRevision: Int?) {
        val state = readWorkingState(snapshot.sourceFile) ?: return
        val canonical = snapshot.contents.toByteArray(StandardCharsets.UTF_8)
        if (canonical.size > SourceEditQueue.MAX_SOURCE_BYTES || sha256(canonical) != snapshot.sourceSha256) {
            return
        }
        atomicWrite(File(privateObjects, snapshot.sourceSha256), canonical)
        val working = safeWorkingFile(snapshot.sourceFile) ?: return
        val hasNewerDraft = deliveredRevision != null && state.draftRevision > deliveredRevision
        if (!hasNewerDraft) {
            atomicWrite(working, canonical)
        }
        writeWorkingState(
            state.copy(
                baseSha256 = snapshot.sourceSha256,
                baseObject = snapshot.sourceSha256,
                pdfFilenames = snapshot.bundlePdfFilenames.ifEmpty { state.pdfFilenames },
                requestId = if (hasNewerDraft) state.requestId else null,
                draftRevision = if (hasNewerDraft) state.draftRevision else 0,
                lastQueuedSha256 = if (hasNewerDraft) state.lastQueuedSha256 else snapshot.sourceSha256,
            ).withObservedFile(working),
        )
        updateObservation(snapshot.sourceFile, working)
    }

    @Synchronized
    fun discardWorkingChanges(sourceFile: String): Boolean {
        val state = readWorkingState(sourceFile) ?: return true
        val base = readVerifiedObject(
            File(privateObjects, state.baseObject),
            state.baseSha256,
            SourceEditQueue.MAX_SOURCE_BYTES.toLong(),
        ) ?: return false
        val working = safeWorkingFile(sourceFile) ?: return false
        atomicWrite(working, base)
        writeWorkingState(
            state.copy(
                requestId = null,
                draftRevision = 0,
                lastQueuedSha256 = state.baseSha256,
            ).withObservedFile(working),
        )
        updateObservation(sourceFile, working)
        return true
    }

    @Synchronized
    fun externalEdits(): List<OfflineExternalEdit> {
        val edits = mutableListOf<OfflineExternalEdit>()
        val stateFiles = workingStateFiles()
        val stateNamesSha256 = stateNamesSha256(stateFiles)
        val index = readObservationIndex()
            ?.takeIf { it.stateNamesSha256 == stateNamesSha256 }
            ?: rebuildObservationIndex(stateFiles)
        var observationChanged = false
        for ((sourceFile, observed) in index.files) {
            val working = safeWorkingFile(sourceFile) ?: continue
            if (working.isFile && observed == working.observation()) continue
            val state = readWorkingState(sourceFile) ?: continue
            val contents = readUtf8(working) ?: continue
            val contentsSha = sha256(contents)
            if (contentsSha == state.baseSha256 || contentsSha == state.lastQueuedSha256) {
                writeWorkingState(state.withObservedFile(working))
                index.files[sourceFile] = working.observation()
                observationChanged = true
                continue
            }
            val baseContents = readUtf8(File(privateObjects, state.baseObject)) ?: continue
            if (sha256(baseContents) != state.baseSha256) continue
            edits += OfflineExternalEdit(
                SourceSnapshot(
                    resolutionId = offlineResolutionId(state.bundleId, state.sourceFile),
                    sourceFile = state.sourceFile,
                    sourceLine = 1,
                    sourceSha256 = state.baseSha256,
                    contents = contents,
                    bundleId = state.bundleId,
                    baseContents = baseContents,
                    bundlePdfFilename = state.pdfFilename,
                    bundlePdfSha256 = state.pdfSha256,
                    bundlePdfFilenames = state.pdfFilenames,
                ),
                contents,
                outside = holdsOutsideChanges && contentsSha != state.appWrittenSha256,
            )
        }
        if (observationChanged) writeObservationIndex(index)
        return edits
    }

    /** The changes in the shared folder that wait for the user: not made in Thoughts, not confirmed. */
    internal fun heldOutsideChanges(): List<OfflineExternalEdit> = externalEdits().filter(OfflineExternalEdit::outside)

    /**
     * True when [contentsSha256] is neither what the server has for [sourceFile], nor what Thoughts
     * queued or wrote, nor what the user confirmed: somebody else's change.
     */
    @Synchronized
    internal fun isOutsideContent(sourceFile: String, contentsSha256: String): Boolean {
        val state = readWorkingState(sourceFile) ?: return false
        return contentsSha256 !in setOf(state.baseSha256, state.lastQueuedSha256, state.appWrittenSha256)
    }

    /**
     * The user chose to upload the outside change they were shown ([contentsSha256]): from now on it
     * counts as theirs, and the next scan queues it. False when the file changed meanwhile.
     */
    @Synchronized
    internal fun confirmOutsideChange(sourceFile: String, contentsSha256: String): Boolean {
        val state = readWorkingState(sourceFile) ?: return false
        val working = safeWorkingFile(sourceFile)?.takeIf(File::isFile) ?: return false
        if (hashFile(working) != contentsSha256) return false
        writeWorkingState(state.copy(appWrittenSha256 = contentsSha256))
        return true
    }

    /**
     * The user chose to discard the outside change they were shown ([contentsSha256]): the working
     * file gets back what Thoughts last wrote, or else the synced version. The discarded text is
     * kept under merge-recoveries. False when the file changed meanwhile.
     */
    @Synchronized
    internal fun discardOutsideChange(sourceFile: String, contentsSha256: String): Boolean {
        val state = readWorkingState(sourceFile) ?: return false
        val working = safeWorkingFile(sourceFile)?.takeIf(File::isFile) ?: return false
        val outside = readUtf8(working)?.takeIf { sha256(it) == contentsSha256 } ?: return false
        val known = setOfNotNull(state.appWrittenSha256, state.lastQueuedSha256)
        val restored = appWrittenCopy(sourceFile).takeIf(File::isFile)
            ?.takeIf { it.length() <= SourceEditQueue.MAX_SOURCE_BYTES }?.readBytes()?.takeIf { sha256(it) in known }
            ?: readVerifiedObject(File(privateObjects, state.baseObject), state.baseSha256, SourceEditQueue.MAX_SOURCE_BYTES.toLong())
            ?: return false
        keepMergeRecovery(sourceFile, "outside", outside)
        atomicWrite(working, restored)
        writeWorkingState(state.copy(appWrittenSha256 = sha256(restored)).withObservedFile(working))
        updateObservation(sourceFile, working)
        SourceCommentSnapshot.invalidate()
        SourceCommentChanges.changes.tryEmit(Unit)
        return true
    }

    private fun importStagedBundle(
        bundleId: String,
        progress: ((String) -> Unit)? = null,
    ): OfflineSourceBundle? = runCatching {
        val stagedManifest = File(stagingDirectory, "objects/$bundleId")
        val manifestRaw = measureSyncDiagnostic("import_read_manifest", progress) {
            readVerifiedObject(stagedManifest, bundleId, MAX_MANIFEST_BYTES)
                ?: readVerifiedObject(File(privateObjects, bundleId), bundleId, MAX_MANIFEST_BYTES)
                ?: readVerifiedObject(File(reusableObjects, bundleId), bundleId, MAX_MANIFEST_BYTES)
        } ?: return@runCatching null
        if (projectReferences(manifestRaw) != null) return@runCatching installProjectIndex(bundleId, manifestRaw, progress)
        val bundle = measureSyncDiagnostic("import_parse_manifest", progress) { parseManifest(bundleId, manifestRaw) }
            ?: return@runCatching null
        measureSyncStage("Installing verified source objects", progress) {
            val required = requiredObjectIds(bundle)
            privateObjects.mkdirs()
            for (objectId in required) {
                val destination = File(privateObjects, objectId)
                if (hasVerifiedObject(destination, objectId, MAX_OBJECT_BYTES)) continue
                val staged = File(stagingDirectory, "objects/$objectId")
                val source = listOf(staged, File(reusableObjects, objectId)).firstOrNull {
                    hasVerifiedObject(it, objectId, MAX_OBJECT_BYTES)
                } ?: error("Required source object failed verification")
                copyVerifiedObject(source, destination, objectId)
            }
        }
        measureSyncStage("Checking source resources", progress) { checkResourceObjects(bundle) }
        measureSyncStage("Updating editable source files", progress) {
            materializeWorkingTree(bundle)
        }
        measureSyncStage("Writing active source indexes", progress) {
            if (projectVersion == null) measureSyncDiagnostic("rebuild_source_observation_index", progress) { rebuildObservationIndex() }
            measureSyncDiagnostic("prepare_source_open_index", progress) { sourceOpenIndex.prepare(bundle) }
            atomicWrite(activeIndex, JsonObject().apply {
                addProperty("schema_version", SCHEMA_VERSION)
                addProperty("bundle_id", bundleId)
            }.toString().toByteArray(StandardCharsets.UTF_8))
            activeBundleCache = bundleId to bundle
        }
        measureSyncStage("Updating source review index", progress) {
            writeOverviewProjection(overviewSourceSnapshot(bundle))
        }
        bundle
    }.getOrNull()

    private fun componentObjectIds(root: JsonObject): Set<String> = buildSet {
        val pdfs = root.getAsJsonArray("pdfs") ?: JsonArray().apply {
            root.getAsJsonObject("pdf")?.let(::add)
        }
        for (pdfValue in pdfs) {
            val pdf = pdfValue.asJsonObject
            val composition = pdf.getAsJsonObject("composition") ?: continue
            val compositionId = composition.string("composition_object")
            require(isSha256(compositionId))
            val documents = composition.getAsJsonArray("documents")?.map { it.asJsonObject }
                ?: error("Composition has no documents")
            val reviews = pdf.getAsJsonArray("component_review")?.map { it.asJsonObject }
                ?: error("Composition has no component reviews")
            val segments = pdf.getAsJsonArray("segments")?.map { it.asJsonObject }
                ?: error("Composition has no segments")
            require(documents.isNotEmpty() && documents.size == segments.size && reviews.size == segments.size)
            add(compositionId)
            documents.forEachIndexed { index, document ->
                val segment = segments[index]
                val review = reviews[index]
                val sourceId = segment.string("source_id")
                val startPage = segment.int("start_page")
                val pageCount = segment.int("page_count")
                val componentSha = document.string("component_pdf_sha256")
                require(sourceId == document.string("source_id") && sourceId == review.string("source_id"))
                require(startPage == document.int("start_page") && startPage == review.int("start_page"))
                require(pageCount == document.int("page_count") && pageCount == review.int("page_count"))
                require(isSha256(componentSha) && componentSha == segment.string("fingerprint") &&
                    componentSha == review.string("component_pdf_sha256"))
                add(document.string("package_sha256").also { require(isSha256(it)) })
                add(review.string("basic_review_object").also { require(isSha256(it)) })
            }
        }
    }

    private fun requiredObjectIds(bundle: OfflineSourceBundle): Set<String> = buildSet {
        add(bundle.bundleId)
        addAll(bundle.componentObjectIds.ifEmpty {
            readManifestObject(bundle.bundleId)?.let { raw -> componentObjectIds(
                JsonParser.parseString(raw.toString(StandardCharsets.UTF_8)).asJsonObject,
            ) }.orEmpty()
        })
        bundle.sources.values.forEach { add(it.objectId) }
        bundle.resources.values.forEach { add(it.objectId) }
        bundle.pdfs.values.flatMap(OfflineBundlePdf::segments).forEach { segment ->
            segment.inputs.forEach { add(it.sourceSha256) }
            segment.synctexObject?.let(::add)
            segment.rawSyncTexObject?.let(::add)
        }
    }

    private fun loadActiveBundle(): OfflineSourceBundle? {
        val bundleId = activeBundleId() ?: run {
            activeBundleCache = null
            return null
        }
        followSourceFolderRenames()
        activeBundleCache?.takeIf { it.first == bundleId }?.let { return it.second }
        activeBundleCache = null
        val raw = readVerifiedObject(File(privateObjects, bundleId), bundleId, MAX_MANIFEST_BYTES)
            ?: return null
        return parseManifest(bundleId, raw)?.also { bundle ->
            activeBundleCache = bundleId to bundle
        }
    }

    /** Library navigation needs only compact metadata from the already activated bundle. */
    @Synchronized
    internal fun librarySourceMetadata(pdfFilename: String? = null): OfflineSourceBundle? =
        loadSourceOpenBundle(importStaged = false, pdfFilename = pdfFilename)

    /** Card lists need source metadata only for the PDFs represented by their cards. */
    @Synchronized
    internal fun librarySourceMetadataFor(pdfFilenames: Set<String>): OfflineSourceBundle? {
        if (pdfFilenames.isEmpty()) return null
        return loadSourceOpenBundle(importStaged = false, pdfFilenames = pdfFilenames)
    }

    /** Source editing needs only PDF segments, source identities and lookup references. */
    private fun loadSourceOpenBundle(
        importStaged: Boolean = true,
        pdfFilename: String? = null,
        pdfFilenames: Set<String>? = pdfFilename?.let { setOf(it) },
    ): OfflineSourceBundle? {
        if (importStaged) importStagedLatest()
        val bundleId = activeBundleId() ?: run {
            sourceOpenBundleCache = null
            return null
        }
        val filenames = pdfFilenames?.map { it.lowercase(java.util.Locale.ROOT) }?.toSortedSet()
        val cacheKey = if (filenames == null) bundleId else "$bundleId/${filenames.joinToString("/")}"
        followSourceFolderRenames()
        sourceOpenBundleCache?.takeIf { it.first == cacheKey }?.let { return it.second }
        sourceOpenBundleCache = null
        val references = if (projectVersion == null) readProjectReferences(bundleId) else null
        if (references != null) return runCatching {
            val selected = references.filter { filenames == null || it.string("filename").lowercase(java.util.Locale.ROOT) in filenames }
            combineProjectBundles(bundleId, selected.map { ref ->
                requireNotNull(projectStore(ref.string("manifest_object")).loadSourceOpenBundle(false)) to ref.get("pdf_stale").asBoolean
            }).also { sourceOpenBundleCache = cacheKey to it }
        }.getOrNull()
        return sourceOpenIndex.load(bundleId, File(privateObjects, bundleId))?.also {
            sourceOpenBundleCache = cacheKey to it
        }
    }

    private fun readOverviewProjection(bundleId: String): OfflineOverviewSourceSnapshot? = runCatching {
        val readStarted = System.nanoTime()
        runCatching { currentAnnotations?.read(overviewProjection.absolutePath, bundleId)?.takeIf(::validOverviewProjection) }.getOrNull()?.let {
            val millis = (System.nanoTime() - readStarted) / 1_000_000
            if (millis > 150) com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log("current annotations read $millis ms: " +
                "${it.proposals.size} proposals, ${it.reviewComments.size} review comments, ${it.logicalComments.size} comments")
            return@runCatching it
        }
        if ((System.nanoTime() - readStarted) / 1_000_000 > 150) com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log(
            "current annotations miss after ${(System.nanoTime() - readStarted) / 1_000_000} ms")
        if (!overviewProjection.isFile || overviewProjection.length() > MAX_OVERVIEW_PROJECTION_BYTES) {
            return@runCatching null
        }
        SourceProjectionDiskCache.read(overviewProjection, bundleId)?.let {
            currentAnnotations?.write(overviewProjection.absolutePath, it)
            return@runCatching it
        }
        overviewProjection.bufferedReader(StandardCharsets.UTF_8).use { stream ->
            JsonReader(stream).use { reader ->
                reader.beginObject()
                var version = 0
                var cachedBundleId: String? = null
                var decoded: OfflineOverviewSourceSnapshot? = null
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "schemaVersion" -> version = reader.nextInt().also {
                            require(it in 2..OVERVIEW_PROJECTION_SCHEMA_VERSION)
                        }
                        "bundleId" -> cachedBundleId = reader.nextString().also { require(it == bundleId) }
                        "snapshot" -> {
                            require(version in 2..OVERVIEW_PROJECTION_SCHEMA_VERSION && cachedBundleId == bundleId)
                            val decoder = if (version == OVERVIEW_PROJECTION_SCHEMA_VERSION) sourceProjectionGson() else gson
                            decoded = decoder.fromJson(reader, OfflineOverviewSourceSnapshot::class.java)
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT)
                val snapshot = requireNotNull(decoded)
                require(snapshot.bundleId == bundleId && validOverviewProjection(snapshot))
                // Upgrade old local data once; never modify the authoritative artifact.
                if (version != OVERVIEW_PROJECTION_SCHEMA_VERSION) writeOverviewProjection(snapshot) else snapshot.also {
                    currentAnnotations?.write(overviewProjection.absolutePath, it)
                    SourceProjectionDiskCache.write(overviewProjection, it)
                }
            }
        }
    }.getOrNull()

    private fun writeOverviewProjection(snapshot: OfflineOverviewSourceSnapshot): OfflineOverviewSourceSnapshot {
        val bundleId = snapshot.bundleId?.takeIf(::isSha256) ?: return snapshot
        val compact = compactSourceProjection(snapshot)
        if (activeBundleId() == bundleId) currentAnnotations?.write(overviewProjection.absolutePath, compact)
        runCatching {
            overviewProjection.parentFile?.mkdirs()
            val temporary = File(overviewProjection.parentFile, ".${overviewProjection.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { stream ->
                    val writer = stream.writer(StandardCharsets.UTF_8).buffered()
                    val json = JsonWriter(writer)
                    json.beginObject()
                    json.name("schemaVersion").value(OVERVIEW_PROJECTION_SCHEMA_VERSION)
                    json.name("bundleId").value(bundleId)
                    json.name("snapshot")
                    sourceProjectionGson().toJson(compact, OfflineOverviewSourceSnapshot::class.java, json)
                    json.endObject()
                    json.flush()
                    stream.fd.sync()
                }
                check(temporary.length() <= MAX_OVERVIEW_PROJECTION_BYTES)
                if (activeBundleId() == bundleId) {
                    check(temporary.renameTo(overviewProjection)) { "Could not publish overview projection" }
                }
            } finally {
                temporary.delete()
            }
        }
        SourceProjectionDiskCache.write(overviewProjection, compact)
        SourceProjectionMemoryCache.remember(overviewProjection, compact)
        return compact
    }

    private fun validOverviewProjection(snapshot: OfflineOverviewSourceSnapshot): Boolean = runCatching {
        snapshot.proposals.all { proposal ->
            isAgentProposalId(proposal.id) &&
                isSafeSourcePath(proposal.sourceFile) &&
                isSha256(proposal.sourceSha256) &&
                proposal.pdfFilenames.all(::isSafePdfFilename)
        } &&
            snapshot.reviewComments.all { comment ->
                isReviewCommentId(comment.id) &&
                    isSafeSourcePath(comment.sourceFile) &&
                    isSha256(comment.sourceSha256) &&
                    comment.pdfFilenames.all(::isSafePdfFilename)
            } &&
            snapshot.reviewCommentDocuments.all(::isSafePdfFilename) &&
            snapshot.logicalComments.all { comment ->
                comment.id.isNotBlank() && comment.revision >= 1
            } &&
            snapshot.pdfSha256ByFilename.all { (filename, sha256) ->
                isSafePdfFilename(filename) && isSha256(sha256)
            }
    }.getOrDefault(false)

    private fun activeBundleId(): String? = parseObject(readerIndex?.takeIf(File::isFile) ?: activeIndex, MAX_INDEX_BYTES)
        ?.takeIf { it.int("schema_version") == SCHEMA_VERSION }
        ?.string("bundle_id")
        ?.takeIf(::isSha256)

    private fun projectStore(digest: String) = OfflineSourceBundleStore(stagingDirectory, fixedWorkingDirectory, privateDirectory, digest,
        currentAnnotations = currentAnnotations, workingCopy = workingCopy)

    private fun readProjectReferences(digest: String): List<JsonObject>? {
        // A legacy manifest can be hundreds of MB. Only the small project index
        // can contain references, so reject oversized objects before allocating them.
        val candidates = listOf(File(privateObjects, digest), File(stagingDirectory, "objects/$digest"), File(reusableObjects, digest))
        for (file in candidates) {
            if (!file.isFile || file.length() > MAX_INDEX_BYTES) continue
            val raw = readVerifiedObject(file, digest, MAX_INDEX_BYTES) ?: continue
            return projectReferences(raw)
        }
        return null
    }

    private fun readManifestObject(digest: String): ByteArray? =
        readVerifiedObject(File(privateObjects, digest), digest, MAX_MANIFEST_BYTES)
            ?: readVerifiedObject(File(stagingDirectory, "objects/$digest"), digest, MAX_MANIFEST_BYTES)
            ?: readVerifiedObject(File(reusableObjects, digest), digest, MAX_MANIFEST_BYTES)

    private fun projectReferences(raw: ByteArray): List<JsonObject>? {
        // Only the small index uses this field; avoid parsing legacy large manifests here.
        if (raw.size > MAX_INDEX_BYTES || !raw.toString(StandardCharsets.UTF_8).contains("\"project_manifests\"")) return null
        val root = JsonParser.parseString(raw.toString(StandardCharsets.UTF_8)).asJsonObject
        require(root.int("schema_version") == SCHEMA_VERSION && projectVersion == null)
        val refs = root.getAsJsonArray("project_manifests").map { it.asJsonObject }
        require(refs.map { it.string("filename") }.distinct().size == refs.size)
        refs.forEach { require(isSafePdfFilename(it.string("filename")) && isSha256(it.string("artifact_sha256")) && isSha256(it.string("manifest_object"))) }
        return refs
    }

    internal fun activeInstalledId(): String? = activeBundleId()

    /** Pin the old source generation before preparing its replacement. PDF directory
     * activation will atomically replace this pointer with the new one. */
    internal fun pinReaderGeneration() {
        val target = readerIndex ?: return
        if (!target.exists() && target.parentFile?.isDirectory == true && activeIndex.isFile) {
            atomicWrite(target, activeIndex.readBytes())
        }
    }

    internal fun activeDocumentArtifacts(): Map<String, String> = activeProjectReferences()
        .associate { it.string("filename") to it.string("artifact_sha256") }.ifEmpty {
            // One-time upgrade uses the installation receipt, never an arbitrary cached version.
            val receipt = parseObject(File(privateDirectory, "document-artifact-receipt.json"), MAX_INDEX_BYTES)
            if (receipt?.string("bundle_id") != activeBundleId()) emptyMap() else runCatching {
                requireNotNull(receipt).getAsJsonArray("inputs").associate {
                    val row = it.asJsonArray
                    require(isSafePdfFilename(row[0].asString) && isSha256(row[1].asString))
                    row[0].asString to row[1].asString
                }
            }.getOrDefault(emptyMap())
        }

    internal fun activeDocumentStaleness(): Map<String, Boolean> = activeProjectReferences()
        .associate { it.string("filename") to it.get("pdf_stale").asBoolean }.ifEmpty {
            val receipt = parseObject(File(privateDirectory, "document-artifact-receipt.json"), MAX_INDEX_BYTES)
            if (receipt?.string("bundle_id") != activeBundleId()) emptyMap() else runCatching {
                requireNotNull(receipt).getAsJsonArray("inputs").associate {
                    val row = it.asJsonArray
                    row[0].asString to row[2].asBoolean
                }
            }.getOrDefault(emptyMap())
        }

    /** Already verified publication metadata; generation never needs a network call. */
    internal fun basicReviewPlan(filename: String, pdfSha256: String): JsonObject? = runCatching {
        val ref = activeProjectReferences().singleOrNull { it.string("filename") == filename }
        val raw = ref?.string("manifest_object")?.let(::readManifestObject)
            ?: activeBundleId()?.let(::readManifestObject) ?: return null
        val manifest = com.google.gson.JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject
        val pdf = manifest.getAsJsonObject("pdf")
            ?: manifest.getAsJsonArray("pdfs")?.firstOrNull { it.asJsonObject.string("filename") == filename }?.asJsonObject
            ?: return null
        val embedded = pdf.getAsJsonObject("basic_review")?.takeIf {
            it.int("schema_version") == 1 && it.string("pdf_sha256") == pdfSha256 && pdf.string("pdf_sha256") == pdfSha256
        }
        val composed = runCatching { composedBasicReviewPlan(pdf, pdfSha256) }.getOrNull()
        (composed?.takeIf { embedded == null || it == embedded } ?: embedded)?.deepCopy()
    }.getOrNull()

    /** Keep PDF-only review cards attached to their imported document's local page. */
    internal fun reviewPdfComponentOrigin(filename: String, pdfSha256: String, page: Int): JsonObject? {
        val pdf = librarySourceMetadata(filename)?.pdfs?.get(filename)
            ?.takeIf { it.pdfSha256 == pdfSha256 } ?: return null
        val matches = pdf.segments.filter { segment ->
            !segment.sourceAvailable && page in segment.startPage until segment.startPage + segment.pageCount &&
                segment.sourceId.isNotBlank() && isSha256(segment.fingerprint)
        }
        val segment = matches.singleOrNull() ?: return null
        return JsonObject().apply {
            addProperty("source_id", segment.sourceId)
            addProperty("component_pdf_sha256", segment.fingerprint)
            addProperty("local_page", page - segment.startPage + 1)
        }
    }

    private fun composedBasicReviewPlan(pdf: JsonObject, pdfSha256: String): JsonObject? {
        val compositionRef = pdf.getAsJsonObject("composition") ?: return null
        val compositionRaw = readManifestObject(compositionRef.string("composition_object")) ?: return null
        val composition = JsonParser.parseString(compositionRaw.toString(StandardCharsets.UTF_8)).asJsonObject
        require(composition.string("kind") == "thoughts-project-composition")
        require(composition.string("pdf_sha256") == pdfSha256)
        val documents = composition.getAsJsonArray("documents") ?: return null
        val references = compositionRef.getAsJsonArray("documents") ?: return null
        val reviews = pdf.getAsJsonArray("component_review") ?: return null
        require(documents == references && documents.size() == reviews.size())
        val blocks = JsonArray()
        val cards = JsonArray()
        documents.forEachIndexed { index, element ->
            val document = element.asJsonObject
            val review = reviews[index].asJsonObject
            val componentSha = document.string("component_pdf_sha256")
            val first = document.int("start_page")
            val count = document.int("page_count")
            require(first >= 1 && count >= 1 && componentSha == review.string("component_pdf_sha256"))
            val packageRaw = readManifestObject(document.string("package_sha256")) ?: return null
            val sourcePackage = JsonParser.parseString(packageRaw.toString(StandardCharsets.UTF_8)).asJsonObject
            require(sourcePackage.string("kind") == "thoughts-document-component-package")
            require(sourcePackage.string("pdf_sha256") == componentSha && sourcePackage.int("page_count") == count)
            require(sourcePackage.string("basic_review_object") == review.string("basic_review_object"))
            val planRaw = readManifestObject(review.string("basic_review_object")) ?: return null
            val plan = JsonParser.parseString(planRaw.toString(StandardCharsets.UTF_8)).asJsonObject
            require(plan.int("schema_version") == 1 && plan.string("pdf_sha256") == componentSha)

            fun shiftedRegions(regions: JsonArray): JsonArray = JsonArray().apply {
                regions.forEach { regionElement ->
                    val region = regionElement.asJsonObject.deepCopy()
                    val localPage = region.int("page")
                    require(localPage in 1..count)
                    region.addProperty("page", first + localPage - 1)
                    add(region)
                }
            }

            plan.getAsJsonArray("blocks").forEach { blockElement ->
                val block = blockElement.asJsonObject.deepCopy()
                block.add("regions", shiftedRegions(block.getAsJsonArray("regions")))
                blocks.add(block)
            }
            plan.getAsJsonArray("cards").forEach { cardElement ->
                val card = cardElement.asJsonObject.deepCopy()
                card.add("regions", shiftedRegions(card.getAsJsonArray("regions")))
                card.getAsJsonObject("source_anchor")?.getAsJsonArray("segments")?.forEach { spanElement ->
                    val span = spanElement.asJsonObject
                    span.add("regions", shiftedRegions(span.getAsJsonArray("regions")))
                }
                cards.add(card)
            }
        }
        return JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("pdf_sha256", pdfSha256)
            add("blocks", blocks)
            add("cards", cards)
        }
    }

    internal fun installedDocumentManifest(filename: String, artifact: String, stale: Boolean): String? =
        activeProjectReferences().singleOrNull {
            it.string("filename") == filename && it.string("artifact_sha256") == artifact && it.get("pdf_stale").asBoolean == stale
        }?.string("manifest_object")

    private fun activeProjectReferences(): List<JsonObject> = activeBundleId()?.let(::readManifestObject)?.let(::projectReferences).orEmpty()

    private fun installProjectIndex(id: String, raw: ByteArray, progress: ((String) -> Unit)?): OfflineSourceBundle? = runCatching {
        require(sha256(raw) == id)
        val references = requireNotNull(projectReferences(raw))
        val installedProjects = activeProjectReferences().associateBy { it.string("filename") }
        val previousMetadata by lazy {
            activeBundleId()?.let { sourceOpenIndex.load(it, File(privateObjects, it)) }
        }
        val pending = mutableListOf<Pair<OfflineSourceBundleStore, String>>()
        val reusedInputs = hashMapOf<String, Set<String>>()
        val compact = measureSyncDiagnostic("project_index_load_documents", progress) { references.map { ref ->
            val filename = ref.string("filename")
            if (installedProjects[filename] == ref) {
                val metadata = previousMetadata
                val pdf = metadata?.pdfs?.get(filename)
                if (metadata != null && pdf != null) {
                    val paths = pdf.segments.flatMap { segment -> segment.inputs.map { it.sourceFile } + listOfNotNull(segment.primarySourceFile) }.toSet()
                    val prefixes = paths.mapNotNull(SourceOwner::of).toSet()
                    reusedInputs[filename] = paths
                    return@map metadata.copy(
                        bundleId = ref.string("manifest_object"),
                        pdfs = mapOf(filename to pdf),
                        sources = metadata.sources.filterKeys { it in paths || SourceOwner.of(it) in prefixes },
                        resources = metadata.resources.filterValues { filename in it.pdfFilenames }.mapValues { (_, value) -> value.copy(pdfFilenames = listOf(filename)) },
                        proposals = emptyMap(), reviewComments = emptyMap(), logicalComments = emptyMap(),
                        reviewCorrespondences = emptyMap(), reviewCommentDocuments = emptySet(),
                    ) to ref.get("pdf_stale").asBoolean
                }
            }
            val digest = ref.string("manifest_object")
            val child = projectStore(digest)
            val cached = child.loadSourceOpenBundle(false)
            val bundle = cached ?: requireNotNull(readManifestObject(digest)?.let { child.parseManifest(digest, it) })
            require(bundle.pdfs.keys == setOf(ref.string("filename")))
            // Validate only the project being installed; cached metadata from other
            // projects is a lightweight reference, not an instruction to revalidate them.
            if (installedProjects[ref.string("filename")] != ref) requiredObjectIds(bundle).forEach { objectId ->
                require(hasVerifiedObject(File(privateObjects, objectId), objectId, MAX_OBJECT_BYTES) ||
                    hasVerifiedObject(File(stagingDirectory, "objects/$objectId"), objectId, MAX_OBJECT_BYTES) ||
                    hasVerifiedObject(File(reusableObjects, objectId), objectId, MAX_OBJECT_BYTES))
            }
            if (cached == null || !child.overviewProjection.isFile) pending += child to digest
            bundle to ref.get("pdf_stale").asBoolean
        } }
        // A reused projection keeps its folder's other sources at their previous revision.
        // A rebuilt PDF of the same folder is authoritative for the sources it lists.
        val rebuiltSources = compact.filter { (project, _) -> project.pdfs.keys.single() !in reusedInputs }
            .flatMapTo(hashSetOf()) { (project, _) -> project.sources.keys }
        val bundle = measureSyncDiagnostic("project_index_combine", progress) { combineProjectBundles(id, compact.map { (project, stale) ->
            val inputs = reusedInputs[project.pdfs.keys.single()] ?: return@map project to stale
            project.copy(sources = project.sources.filterKeys { it in inputs || it !in rebuiltSources }) to stale
        }) }
        measureSyncDiagnostic("project_index_import_documents", progress) {
            // Five logged stages per document made a first Sync of 150 documents write
            // 1,500 operation-log messages, about 30 s. Report the count at most twice a second.
            val itemProgress = progress.withoutStageMessages()
            var reportedAt = 0L
            pending.forEachIndexed { index, (child, digest) ->
                val now = System.nanoTime()
                if (progress != null && (index == 0 || now - reportedAt >= 500_000_000L)) {
                    reportedAt = now
                    progress("Installing sources for document ${index + 1} of ${pending.size}")
                }
                requireNotNull(child.importStagedBundle(digest, itemProgress))
            }
        }
        // Legacy shared resources are reconciled once, using the complete set's
        // freshness decision. Project-owned resources stay within their installer.
        val attention = JsonArray()
        references.forEach { ref ->
            val report = File(projectStore(ref.string("manifest_object")).metadataDirectory, "materialization-attention.json")
            if (report.isFile) JsonParser.parseString(report.readText()).asJsonArray.forEach(attention::add)
        }
        atomicWrite(File(privateDirectory, "materialization-attention.json"), attention.toString().toByteArray())
        atomicWrite(File(privateObjects, id), raw)
        measureSyncDiagnostic("project_index_open_index", progress) { sourceOpenIndex.prepare(bundle) }
        measureSyncDiagnostic("project_index_observation_index", progress) { rebuildObservationIndex() }
        // One final pointer publishes the set. Unchanged project indexes are never rewritten.
        atomicWrite(activeIndex, JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("bundle_id", id)
        }.toString().toByteArray(StandardCharsets.UTF_8))
        activeBundleCache = null
        sourceOpenBundleCache = id to bundle
        // Keep rollback objects while the PDF directory still points at the old set.
        if (readerIndex == null || activeBundleId() == id) {
            measureSyncDiagnostic("project_index_prune", progress) {
                pruneWorkingTree(bundle)
                prunePrivateObjects(bundle)
            }
        }
        measureSyncDiagnostic("project_index_clear_staging", progress) { stagingDirectory.deleteRecursively() }
        SourceCommentSnapshot.invalidate()
        bundle
    }.getOrNull()

    private fun materializeWorkingTree(bundle: OfflineSourceBundle) {
        workingDirectory.mkdirs()
        val tasks = mutableListOf<Callable<JsonObject?>>()
        for (source in bundle.sources.values) {
            val reference = sourceReference(bundle, source.path) ?: continue
            tasks += Callable {
                materializeWorkingEntry(
                    bundle,
                    source.path,
                    source.sha256,
                    source.objectId,
                    reference,
                    listOf(reference.first),
                )
            }
        }
        for (resource in bundle.resources.values) {
            val reference = resource.pdfFilenames.firstNotNullOfOrNull { filename ->
                bundle.pdfs[filename]?.let { filename to it.pdfSha256 }
            } ?: continue
            tasks += Callable {
                materializeWorkingEntry(
                    bundle,
                    resource.path,
                    resource.sha256,
                    resource.objectId,
                    reference,
                    resource.pdfFilenames,
                )
            }
        }
        // Independent files can be verified and durably updated concurrently.
        // Retain source-before-resource ordering if a bundle lists a path twice.
        val receipts = if (tasks.size <= 1 || bundle.sources.keys.any(bundle.resources::containsKey)) {
            tasks.map { it.call() }
        } else {
            val executor = Executors.newFixedThreadPool(minOf(8, tasks.size))
            try {
                executor.invokeAll(tasks).map { it.get() }
            } finally {
                executor.shutdown()
            }
        }
        val attention = JsonArray().apply {
            receipts.filterNotNull().filter { it.get("action")?.asString == "needs_attention" }.forEach(::add)
        }
        atomicWrite(File(metadataDirectory, "materialization-attention.json"), attention.toString().toByteArray(StandardCharsets.UTF_8))
    }

    internal fun materializationProblems(): List<String> {
        val file = File(privateDirectory, "materialization-attention.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            JsonParser.parseString(file.readText()).asJsonArray.map { item ->
                val receipt = item.asJsonObject
                receipt.get("source_file").asString + ": " + receipt.get("reason").asString.replace('_', ' ')
            }
        }.getOrElse { listOf("The editable-source installation record could not be read") }
    }

    /** Current verified source files whose phone copy was preserved for a merge. */
    internal fun sourceFilesNeedingMerge(): List<String> {
        // Every materialized file has a receipt and Sync asks three times; a receipt changes only
        // through atomicWrite's rename, which moves the directory's nanosecond mtime.
        val receipts = File(privateDirectory, "materialization")
        val receiptsIdentity = runCatching {
            val stat = android.system.Os.stat(receipts.path)
            "${activeBundleId()}:${stat.st_ino}:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}"
        }.getOrNull()
        val merges = synchronized(pendingMergeCache) {
            pendingMergeCache[receipts.path]?.takeIf { receiptsIdentity != null && it.first == receiptsIdentity }?.second
        } ?: run {
            val bundle = librarySourceMetadata() ?: return emptyList()
            val currentSources = bundle.sources.mapValues { it.value.sha256 } +
                bundle.resources.mapValues { it.value.sha256 }
            currentSources.mapNotNull { (path, remoteSha256) ->
                val receipt = parseObject(File(receipts, "${sha256(path)}.json"), MAX_STATE_BYTES)
                    ?: return@mapNotNull null
                if (receipt.string("action") != "merge" || receipt.string("remote_sha256") != remoteSha256) {
                    return@mapNotNull null
                }
                path to remoteSha256
            }.also { found ->
                if (receiptsIdentity != null) synchronized(pendingMergeCache) {
                    pendingMergeCache[receipts.path] = receiptsIdentity to found
                }
            }
        }
        return merges.mapNotNull { (path, remoteSha256) ->
            val working = safeWorkingFile(path) ?: return@mapNotNull null
            if (hashFile(working) == remoteSha256) null else path
        }.sorted()
    }

    /** Recheck preserved merges after an app update without starting Sync. */
    internal fun autoMergePendingSources(): Int {
        val paths = sourceFilesNeedingMerge().takeIf { it.isNotEmpty() } ?: return 0
        val bundle = librarySourceMetadata() ?: return 0
        var merged = 0
        for (path in paths) {
            val entry = verifiedSourceEntry(bundle, path) ?: continue
            val receipt = materializeWorkingEntry(bundle, path, entry.sha256, entry.objectId, entry.reference, entry.pdfFilenames)
            if (receipt?.get("action")?.asString == "auto_merge") merged++
        }
        if (merged > 0) rebuildObservationIndex()
        return merged
    }

    private fun verifiedSourceEntry(bundle: OfflineSourceBundle, path: String): VerifiedSourceEntry? {
        val source = bundle.sources[path]
        val resource = bundle.resources[path]
        val reference = if (source != null) sourceReference(bundle, path) else
            resource?.pdfFilenames?.firstNotNullOfOrNull { filename ->
                bundle.pdfs[filename]?.let { filename to it.pdfSha256 }
            }
        reference ?: return null
        return VerifiedSourceEntry(
            source?.sha256 ?: resource?.sha256 ?: return null,
            source?.objectId ?: resource?.objectId ?: return null,
            reference,
            if (source != null) listOf(reference.first) else resource?.pdfFilenames.orEmpty(),
        )
    }

    /**
     * Both versions of [sourceFile] while it needs a merge, or null once it no longer does or
     * either version cannot be read as text.
     */
    @Synchronized
    internal fun sourceMergeVersions(sourceFile: String): SourceMergeVersions? {
        if (sourceFile !in sourceFilesNeedingMerge()) return null
        val bundle = librarySourceMetadata() ?: return null
        val entry = verifiedSourceEntry(bundle, sourceFile) ?: return null
        val synced = readVerifiedObject(File(privateObjects, entry.objectId), entry.sha256,
            SourceEditQueue.MAX_SOURCE_BYTES.toLong())?.let { runCatching { decodeUtf8(it) }.getOrNull() } ?: return null
        val phone = safeWorkingFile(sourceFile)?.let(::readUtf8) ?: return null
        return SourceMergeVersions(sourceFile, phone, sha256(phone), synced, entry.sha256)
    }

    /**
     * Resolves a merge by replacing the phone copy with the synced version. The phone version
     * the user saw ([expectedPhoneSha256]) is kept under merge-recoveries first. The caller
     * withdraws any queued save of the phone version. False when the file changed meanwhile.
     */
    @Synchronized
    internal fun useSyncedVersion(sourceFile: String, expectedPhoneSha256: String): Boolean {
        val versions = sourceMergeVersions(sourceFile)?.takeIf { it.phoneSha256 == expectedPhoneSha256 } ?: return false
        val bundle = librarySourceMetadata() ?: return false
        val entry = verifiedSourceEntry(bundle, sourceFile) ?: return false
        val working = safeWorkingFile(sourceFile) ?: return false
        val previous = readWorkingState(sourceFile)
        keepMergeRecovery(sourceFile, "phone", versions.phoneContents)
        atomicWrite(working, versions.syncedContents.toByteArray(StandardCharsets.UTF_8))
        writeWorkingState(WorkingSourceState(sourceFile, bundle.bundleId, entry.sha256, entry.objectId,
            entry.reference.first, entry.reference.second, entry.pdfFilenames,
            lastQueuedSha256 = entry.sha256).withObservedFile(working))
        updateObservation(sourceFile, working)
        writeMergeResolutionReceipt(sourceFile, entry.sha256, previous?.baseSha256, versions.phoneSha256,
            "install_remote", "user_chose_synced_version")
        SourceCommentSnapshot.invalidate()
        SourceCommentChanges.changes.tryEmit(Unit)
        return true
    }

    /**
     * Resolves a merge by keeping the phone copy, rebased on the synced version, and returns
     * the snapshot the caller queues as the next source save: Sync then sends the phone version
     * as an edit of the current source. The synced version is kept under merge-recoveries.
     * Null when the file changed meanwhile.
     */
    @Synchronized
    internal fun keepPhoneVersion(sourceFile: String, expectedPhoneSha256: String): SourceSnapshot? {
        val versions = sourceMergeVersions(sourceFile)?.takeIf { it.phoneSha256 == expectedPhoneSha256 } ?: return null
        val bundle = librarySourceMetadata() ?: return null
        val entry = verifiedSourceEntry(bundle, sourceFile) ?: return null
        val previous = readWorkingState(sourceFile)
        keepMergeRecovery(sourceFile, "synced", versions.syncedContents)
        // Unobserved and not yet queued: should queueing fail, the next Sync captures it anyway.
        // Chosen by the user in the merge screen, so also theirs if it began as another app's change.
        writeWorkingState(WorkingSourceState(sourceFile, bundle.bundleId, entry.sha256, entry.objectId,
            entry.reference.first, entry.reference.second, entry.pdfFilenames,
            previous?.requestId, previous?.draftRevision ?: 0, lastQueuedSha256 = entry.sha256,
            appWrittenSha256 = versions.phoneSha256))
        forgetObservation(sourceFile)
        writeMergeResolutionReceipt(sourceFile, entry.sha256, previous?.baseSha256, versions.phoneSha256,
            "keep_local", "user_kept_phone_version")
        return sourceSnapshot(sourceFile, 1)?.takeIf { it.contents == versions.phoneContents }
    }

    /** Content-addressed, so an earlier kept version is never overwritten. */
    private fun keepMergeRecovery(sourceFile: String, version: String, contents: String) {
        atomicWrite(File(privateDirectory, "merge-recoveries/${sha256(sourceFile)}.$version.${sha256(contents)}"),
            contents.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeMergeResolutionReceipt(
        sourceFile: String, remoteSha256: String, baseSha256: String?, localSha256: String, action: String, reason: String,
    ) {
        atomicWrite(File(privateDirectory, "materialization/${sha256(sourceFile)}.json"), JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("rules_version", SyncCore.RULES_VERSION)
            addProperty("source_file", sourceFile)
            addProperty("remote_sha256", remoteSha256)
            addProperty("base_sha256", baseSha256)
            addProperty("local_sha256", localSha256)
            addProperty("action", action)
            addProperty("reason", reason)
            if (action == "install_remote") addProperty("installed_sha256", remoteSha256)
        }.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun materializeWorkingEntry(
        bundle: OfflineSourceBundle,
        path: String,
        sha256: String,
        objectId: String,
        reference: Pair<String, String>,
        pdfFilenames: List<String>,
    ): JsonObject? {
        val sourceObject = File(privateObjects, objectId)
        val working = safeWorkingFile(path) ?: return null
        val previous = readWorkingState(path)
        check(!working.exists() || working.isFile) { "Working source is not a regular file: $path" }
        if (working.isFile && previous != null &&
            previous.baseSha256 == sha256 && previous.baseObject == objectId &&
            previous.pdfFilename == reference.first && previous.pdfSha256 == reference.second &&
            previous.pdfFilenames == pdfFilenames
        ) {
            if (previous.bundleId != bundle.bundleId) {
                writeWorkingState(previous.copy(bundleId = bundle.bundleId))
            }
            return null
        }
        val observedLength = working.length()
        val observedModified = working.lastModified()
        val currentSha = hashFile(working)
        val newState = WorkingSourceState(
            path,
            bundle.bundleId,
            sha256,
            objectId,
            reference.first,
            reference.second,
            pdfFilenames,
            previous?.requestId,
            previous?.draftRevision ?: 0,
            previous?.lastQueuedSha256,
            appWrittenSha256 = previous?.appWrittenSha256,
        )
        val decision = SyncCore.sourceDecision(mapOf(
            "same_contents" to (currentSha == sha256),
            "base_known" to (previous != null),
            "local_exists" to working.isFile,
            "absence_is_deletion" to false, // A missing phone cache file is not a queued source deletion.
            "local_matches_base" to (previous != null && currentSha == previous.baseSha256),
            "remote_matches_base" to (previous != null && sha256 == previous.baseSha256),
        ))
        var action = decision.action
        var reason = decision.reason
        when (action) {
            "install_remote", "acknowledge" -> {
                if (working.length() != observedLength || working.lastModified() != observedModified || hashFile(working) != currentSha) {
                    action = "needs_attention"
                    reason = "edited_during_source_installation"
                } else {
                    if (action == "install_remote") {
                        val contents = sourceObject.readBytes()
                        check(sha256(contents) == sha256) { "Source object changed before installation" }
                        atomicWrite(working, contents)
                    }
                    if (hashFile(working) == sha256) {
                        writeWorkingState(newState.copy(lastQueuedSha256 = sha256).withObservedFile(working))
                    } else {
                        action = "needs_attention"
                        reason = "edited_before_source_acknowledgement"
                    }
                }
            }
            "merge" -> {
                if (previous != null && previous.requestId == null && previous.draftRevision == 0) {
                    val base = readVerifiedObject(File(privateObjects, previous.baseObject), previous.baseSha256,
                        SourceEditQueue.MAX_SOURCE_BYTES.toLong())
                    val remote = readVerifiedObject(sourceObject, sha256, SourceEditQueue.MAX_SOURCE_BYTES.toLong())
                    val local = readUtf8(working)
                    val merged = if (base != null && remote != null && local != null) {
                        runCatching { cleanSourceMaterializationMerge(decodeUtf8(base), local, decodeUtf8(remote), path) }.getOrNull()
                    } else null
                    if (merged != null && local != null && working.length() == observedLength &&
                        working.lastModified() == observedModified && hashFile(working) == currentSha) {
                        val recovery = File(privateDirectory, "merge-recoveries/${sha256(path)}.local")
                        atomicWrite(recovery, local.toByteArray(StandardCharsets.UTF_8))
                        atomicWrite(working, merged.toByteArray(StandardCharsets.UTF_8))
                        // Merging in the server's change does not make another app's change the user's:
                        // the result counts as written by Thoughts only when the local side was.
                        val localWasOutside = holdsOutsideChanges && currentSha != previous.appWrittenSha256 &&
                            currentSha != previous.lastQueuedSha256
                        writeWorkingState(newState.copy(requestId = null, draftRevision = 0,
                            lastQueuedSha256 = sha256, observedSizeBytes = null,
                            observedModifiedAtMillis = null,
                            appWrittenSha256 = if (localWasOutside) previous.appWrittenSha256 else sha256(merged)))
                        action = "auto_merge"
                        reason = "clean_three_way_merge"
                    }
                }
            }
            // Retain the real authoring base while a local draft waits for
            // upload/merge. Missing lineage never becomes a fabricated base.
            else -> Unit
        }
        val receipt = JsonObject().apply {
            addProperty("schema_version", 1)
            addProperty("rules_version", SyncCore.RULES_VERSION)
            addProperty("source_file", path)
            addProperty("remote_sha256", sha256)
            addProperty("base_sha256", previous?.baseSha256)
            addProperty("local_sha256", currentSha)
            addProperty("action", action)
            addProperty("reason", reason)
            if (action == "install_remote" || action == "acknowledge") addProperty("installed_sha256", sha256)
            if (action == "auto_merge") addProperty("merged_sha256", hashFile(working))
        }
        atomicWrite(File(privateDirectory, "materialization/${sha256(path)}.json"), receipt.toString().toByteArray(StandardCharsets.UTF_8))
        return receipt
    }

    private fun sourceReference(bundle: OfflineSourceBundle, sourceFile: String): Pair<String, String>? {
        for (pdf in bundle.pdfs.values.sortedBy(OfflineBundlePdf::filename)) {
            if (pdf.segments.any { segment -> segment.inputs.any { it.sourceFile == sourceFile } }) {
                return pdf.filename to pdf.pdfSha256
            }
        }
        return null
    }

    private fun readBuildSource(input: OfflineBundleInput): String? {
        val digest = input.sourceSha256
        return listOf(privateObjects, reusableObjects, File(stagingDirectory, "objects"))
            .firstNotNullOfOrNull { directory ->
                readVerifiedObject(File(directory, digest), digest, SourceEditQueue.MAX_SOURCE_BYTES.toLong())
                    ?.toString(StandardCharsets.UTF_8)
            }
    }

    private fun parseManifest(bundleId: String, raw: ByteArray): OfflineSourceBundle? {
        if (sha256(raw) != bundleId) return null
        followSourceFolderRenames()
        parsedManifestCache?.takeIf { it.first == bundleId }?.let { return it.second }
        val references = projectReferences(raw)
        if (references != null) return runCatching {
            combineProjectBundles(bundleId, references.map { ref ->
                val digest = ref.string("manifest_object")
                requireNotNull(projectStore(digest).loadActiveBundle()) to ref.get("pdf_stale").asBoolean
            })
        }.getOrNull()
        return parseManifestUncached(bundleId, raw)?.also {
            parsedManifestCache = bundleId to it
        }
    }

    private fun parseManifestUncached(bundleId: String, raw: ByteArray): OfflineSourceBundle? = runCatching {
        val parsed = raw.inputStream().reader(StandardCharsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        // After the local default-folder migration, a bundle Fedora built before its own one still
        // names `documents`: read it in the spelling the phone uses (SyncTeX keeps its build paths).
        val defaultFolderRoot = if (DefaultFolder.localStateMigrated) {
            DefaultFolder.rewriteJsonValues(parsed) { it == "synctex_path" }.asJsonObject
        } else parsed
        // Likewise a bundle Fedora built before renaming its connected folders (source-folder-names).
        val root = followSourceFolderNames(defaultFolderRoot)
        if (root.int("schema_version") != SCHEMA_VERSION) return@runCatching null
        val sources = root.getAsJsonArray("sources").associate { element ->
            val value = element.asJsonObject
            val path = value.string("path").takeIf(::isSafeSourcePath)
                ?: throw IllegalArgumentException("unsafe source path")
            val sha = value.string("sha256").takeIf(::isSha256)
                ?: throw IllegalArgumentException("invalid source hash")
            val objectId = value.string("object").takeIf(::isSha256)
                ?: throw IllegalArgumentException("invalid source object")
            require(objectId == sha)
            path to OfflineBundleSource(path, sha, objectId, value.long("bytes"))
        }
        val pdfValues = root.getAsJsonArray("pdfs") ?: JsonArray().apply {
            root.getAsJsonObject("pdf")?.let(::add)
        }
        val pdfs = pdfValues.associate { element ->
            val value = element.asJsonObject
            val filename = value.string("filename").takeIf(::isSafePdfFilename)
                ?: throw IllegalArgumentException("invalid PDF filename")
            val pdfSha = value.string("pdf_sha256").takeIf(::isSha256)
                ?: throw IllegalArgumentException("invalid PDF hash")
            val placementSources = value.getAsJsonArray("placement_sources")?.associate { element ->
                val source = element.asJsonObject
                source.string("path") to source.string("sha256").also { digest ->
                    require(isSha256(digest) && digest == source.string("object"))
                }
            }.orEmpty()
            val revisionMappings = value.getAsJsonArray("source_revision_equivalences")?.associate { element ->
                val mapping = element.asJsonObject
                mapping.string("source_file") to mapping
            }.orEmpty()
            val segments = value.getAsJsonArray("segments").map { segmentElement ->
                val segment = segmentElement.asJsonObject
                val inputs = segment.getAsJsonArray("inputs")?.map { inputElement ->
                    val input = inputElement.asJsonObject
                    OfflineBundleInput(
                        input.string("synctex_path"),
                        input.string("source_file").also {
                            require(it in sources)
                        },
                        input.string("source_sha256").also {
                            val path = input.string("source_file")
                            val currentSha = sources[path]?.sha256
                            val mapping = revisionMappings[path]
                            require(it == currentSha || (
                                it == placementSources[path] && mapping != null &&
                                    mapping.string("from_sha256") == it &&
                                    mapping.string("to_sha256") == currentSha &&
                                    mapping.string("method") in setOf(
                                        "review_metadata_only_v1", "proposal_accept_metadata_v1",
                                    )
                            ))
                        },
                    )
                }.orEmpty()
                OfflineBundleSegment(
                    segment.int("start_page"),
                    segment.int("page_count"),
                    segment.boolean("source_available"),
                    segment.string("primary_source_file").takeIf(String::isNotBlank),
                    segment.string("lookup_object").ifBlank { segment.string("synctex_object") }.takeIf(::isSha256),
                    segment.string("lookup_sha256").ifBlank { segment.string("synctex_sha256") }.takeIf(::isSha256),
                    inputs,
                    segment.string("source_format").ifBlank { "latex" }.also { require(it in setOf("latex", "markdown")) },
                    segment.string("renderer_evidence_object").takeIf(String::isNotBlank).also {
                        if (segment.string("source_format") == "markdown") {
                            require(it != null && isSha256(it) && it == segment.string("renderer_evidence_sha256"))
                            require(segment.string("lookup_format") == "thoughts-markdown-source-map-v1")
                            require(segment.string("lookup_object") == segment.string("lookup_sha256"))
                        }
                    },
                    segment.string("fingerprint"),
                    segment.string("synctex_object").takeIf(::isSha256)?.also {
                        require(it == segment.string("synctex_sha256"))
                    },
                    sourceId = segment.string("source_id"),
                )
            }
            filename to OfflineBundlePdf(filename, pdfSha, value.int("page_count"), segments,
                value.getAsJsonArray("placement_sources") ?: JsonArray(),
                value.getAsJsonArray("source_revision_equivalences") ?: JsonArray())
        }
        val resources = linkedMapOf<String, OfflineBundleResource>()
        val resourceEntries = root.getAsJsonArray("resources") ?: JsonArray()
        resourceEntries.forEach { element ->
            val value = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("invalid source resource")
            val kind = value.string("kind").also {
                require(it == "project_style" || it == RESOURCE_PROJECT_STYLE_OVERRIDE || it == "html_attachment")
            }
            val path = value.string("path").also {
                require(isSafeResourcePath(kind, it))
            }
            val sha = value.string("sha256").takeIf(::isSha256)
                ?: throw IllegalArgumentException("invalid source resource hash")
            val objectId = value.string("object").takeIf(::isSha256)
                ?: throw IllegalArgumentException("invalid source resource object")
            require(objectId == sha) { "source resource object identity differs from its hash" }
            val pdfFilenames = value.getAsJsonArray("pdf_filenames")
                ?.map { pdfElement ->
                    pdfElement.asString.also { filename ->
                        require(isSafePdfFilename(filename) && filename in pdfs)
                    }
                }
                .orEmpty()
            require(pdfFilenames.isNotEmpty())
            require(pdfFilenames.size == pdfFilenames.toSet().size) {
                "duplicate source resource consumer"
            }
            val bytes = value.long("bytes").also {
                require(it in 0..(if (kind == "html_attachment") 100_000_000L else SourceEditQueue.MAX_SOURCE_BYTES.toLong()))
            }
            require(path !in resources) { "duplicate source resource path" }
            resources[path] = OfflineBundleResource(
                kind,
                path,
                sha,
                objectId,
                bytes,
                pdfFilenames.sorted(),
            )
        }
        val reviewCorrespondences = if (root.int("review_correspondence_schema_version") == 2) {
            root.getAsJsonArray("review_correspondences")
                ?.associate { element ->
                    parseReviewCorrespondence(element.asJsonObject, pdfs, root.int("review_placement_model_version") in setOf(3, 4)).let { it.reviewKey to it }
                }
                .orEmpty()
        } else {
            emptyMap()
        }
        val proposalObjects = root.getAsJsonArray("proposal_objects")
            ?.associate { element ->
                val value = element.asJsonObject
                value.string("id") to value.string("object")
            }
            .orEmpty()
        val proposals = root.getAsJsonArray("agent_proposals")?.associate { element ->
            val value = element.asJsonObject
            val id = value.string("id").takeIf(::isAgentProposalId)
                ?: throw IllegalArgumentException("invalid agent proposal ID")
            val sourceFile = value.string("source_file").also { require(it in sources) }
            val sourceSha = value.string("source_sha256").also {
                require(it == sources[sourceFile]?.sha256)
            }
            val pdfFilenames = value.getAsJsonArray("pdf_filenames")?.map { pdfElement ->
                pdfElement.asString.also { require(isSafePdfFilename(it) && it in pdfs) }
            }.orEmpty().distinct()
            val kind = value.string("kind").ifBlank { "remove" }.also {
                require(it == "remove" || it == "edit")
            }
            val correspondence = reviewCorrespondences["proposal:$id"]
            val placement = value.getAsJsonObject("placement").takeIf { correspondence == null }
            val quadValues = placement?.getAsJsonArray("regions")?.flatMap { regionElement ->
                val region = regionElement.asJsonObject
                val role = region.string("role").also { require(it == "before" || it == "after") }
                region.getAsJsonArray("quad_fragments").map { role to it }
            } ?: placement?.getAsJsonArray("quad_fragments")?.map { "before" to it }.orEmpty()
            val legacyQuads = quadValues.map { (role, quadElement) ->
                val quad = quadElement.asJsonObject
                OfflineProposalQuad(
                    role,
                    quad.string("pdf_filename").also { require(it in pdfs) },
                    quad.int("page").also { require(it >= 1) },
                    quad.float("left"),
                    quad.float("top"),
                    quad.float("right"),
                    quad.float("bottom"),
                    duplicate = quad.boolean("duplicate"),
                ).also {
                    require(it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite())
                    require(it.left <= it.right && it.top <= it.bottom)
                }
            }.orEmpty()
            val proofQuads = correspondence?.locations.orEmpty().flatMap { location ->
                location.regions.map { region ->
                    OfflineProposalQuad(
                        role = location.role,
                        pdfFilename = location.pdfFilename,
                        page = location.page,
                        left = region[0],
                        top = region[1],
                        right = region[2],
                        bottom = region[3],
                        normalized = true,
                        duplicate = location.duplicate,
                    )
                } + if (location.point.size == 2) {
                    val x = location.point[0]
                    val y = location.point[1]
                    listOf(
                        OfflineProposalQuad(
                            role = location.role,
                            pdfFilename = location.pdfFilename,
                            page = location.page,
                            left = (x - 0.004f).coerceAtLeast(0f),
                            top = (y - 0.004f).coerceAtLeast(0f),
                            right = (x + 0.004f).coerceAtMost(1f),
                            bottom = (y + 0.004f).coerceAtMost(1f),
                            normalized = true,
                            duplicate = location.duplicate,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
            id to OfflineAgentProposal(
                value.string("artifact_sha256").takeIf(::isSha256) ?: bundleId,
                value.string("proposal_object_sha256").takeIf(::isSha256)
                    ?: proposalObjects[id].orEmpty(),
                id,
                kind,
                value.string("comment"),
                sourceFile,
                sourceSha,
                value.int("line").coerceAtLeast(1),
                value.string("body_preview"),
                value.getAsJsonObject("before")?.string("preview").orEmpty().ifBlank { value.string("body_preview") },
                value.getAsJsonObject("after")?.string("preview").orEmpty(),
                value.string("parent_id").takeIf(::isAgentProposalId),
                value.boolean("suppressed"),
                pdfFilenames,
                if (correspondence != null) proofQuads else legacyQuads,
                correspondence,
                value.string("created_at").ifBlank { null },
            )
        }.orEmpty()
        val reviewCommentDocuments = root.getAsJsonArray("review_comment_documents")
            ?.map { it.asString.also { filename -> require(isSafePdfFilename(filename) && filename in pdfs) } }
            ?.toSet()
            .orEmpty()
        val reviewComments = root.getAsJsonArray("review_comments")?.associate { element ->
            val value = element.asJsonObject
            val id = value.string("id").takeIf(::isReviewCommentId)
                ?: throw IllegalArgumentException("invalid source review comment ID")
            val sourceFile = value.string("source_file").also { require(it in sources) }
            val sourceSha = value.string("source_sha256").also {
                require(it == sources[sourceFile]?.sha256)
            }
            val pdfFilenames = value.getAsJsonArray("pdf_filenames")?.map { pdfElement ->
                pdfElement.asString.also { filename -> require(isSafePdfFilename(filename) && filename in pdfs) }
            }.orEmpty().distinct()
            require(pdfFilenames.isNotEmpty())
            id to OfflineSourceReviewComment(
                id,
                value.string("contents"),
                sourceFile,
                sourceSha,
                value.int("line").also { require(it >= 1) },
                pdfFilenames,
            )
        }.orEmpty()
        val logicalComments = root.getAsJsonArray("logical_comments")?.associate { element ->
            val parsed = parseLogicalComment(element.asJsonObject)
            val correspondence = reviewCorrespondences["comment:${parsed.threadRootId}"]
            parsed.copy(
                scopeState = correspondence?.state ?: parsed.scopeState,
                sourceFile = correspondence?.sourceTargets?.firstOrNull()?.sourceFile ?: parsed.sourceFile,
                sourceLine = correspondence?.sourceTargets?.firstOrNull()?.lineStart ?: parsed.sourceLine,
                correspondence = correspondence,
            ).let { it.id to it }
        }.orEmpty()
        OfflineSourceBundle(
            bundleId,
            pdfs,
            sources,
            resources,
            proposals,
            reviewComments,
            reviewCommentDocuments,
            logicalComments,
            reviewCorrespondences,
            componentObjectIds(root),
        )
    }.getOrNull()

    private fun prunePrivateObjects(
        bundle: OfflineSourceBundle,
        diagnostics: SyncDiagnostics? = null,
        cooperate: () -> Unit = {},
        step: (String, () -> Unit) -> Unit = { _, block -> block() },
    ) {
        var retained: Set<String>? = null
        val counters = RetainedObjectCounters()
        step("source_cleanup_retained_objects") { retained = retainedPrivateObjects(bundle, counters, cooperate) }
        diagnostics?.count(
            "source_cleanup_retained_objects_work",
            true,
            "readings" to counters.readings,
            "packages" to counters.packages,
            "visited" to counters.visited,
            "pinned" to counters.pinned,
            "index_hits" to counters.index.hits,
            "json_parsed" to counters.index.parsed,
            "json_parsed_bytes" to counters.index.parsedBytes,
            "leaf_checks" to counters.index.leafChecks,
            "legacy_package_reads" to counters.legacyPackageReads,
        )
        val keep = retained ?: return // Never prune on incomplete retained-generation evidence.
        cooperate()
        step("source_cleanup_archive_objects") {
            privateObjects.listFiles().orEmpty()
                .filter { it.isFile && it.name !in keep }
                .forEach(::archiveReusableObject)
        }
        cooperate()
        step("source_cleanup_object_cache") {
            val cached = pruneReusableObjectCache()
            // Content-addressed facts stay useful while their object is kept anywhere.
            objectReferences.retainOnly { it in keep || it in cached || it in counters.packageIds }
        }
    }

    private class RetainedObjectCounters {
        val index = ObjectReferenceIndex.Counters()
        val packageIds = HashSet<String>()
        var readings = 0L
        var packages = 0L
        var visited = 0L
        var pinned = 0L
        var legacyPackageReads = 0L
    }

    private val objectReferences get() = ObjectReferenceIndex.at(File(privateDirectory, "object-references-v1.bin"))

    /**
     * Every object a retained PDF generation or working copy still needs, or null when unsure.
     *
     * The closure is the same as when every reachable JSON object was read and tree-parsed
     * on each cleanup: seeds named by the active bundle are kept but not expanded; strings in
     * project readings and their verified document packages are expanded; an expanded object
     * that is a JSON object or array of at most [MAX_MANIFEST_BYTES] expands to the SHA-256
     * strings it contains. Those strings are now taken from [objectReferences], so an object
     * is read once in its lifetime instead of after every Sync.
     */
    private fun retainedPrivateObjects(
        bundle: OfflineSourceBundle,
        counters: RetainedObjectCounters = RetainedObjectCounters(),
        cooperate: () -> Unit = {},
    ): Set<String>? {
        return buildSet {
            add(bundle.bundleId)
            addAll(bundle.componentObjectIds.ifEmpty {
                readManifestObject(bundle.bundleId)?.let { raw -> componentObjectIds(
                    JsonParser.parseString(raw.toString(StandardCharsets.UTF_8)).asJsonObject,
                ) }.orEmpty()
            })
            // Only a small project index names project manifests; do not load a legacy
            // multi-MB manifest just to find that it has none.
            readProjectReferences(bundle.bundleId)?.forEach { add(it.string("manifest_object")) }
            // Every retained PDF generation can still be open in a reader. Keep
            // the exact source objects referenced by its immutable document packages.
            val generationParent = readerIndex?.parentFile?.parentFile
            val pending = java.util.ArrayDeque<String>()
            fun collectAll(hashes: Array<String>) {
                for (hash in hashes) if (add(hash)) pending.add(hash)
            }
            fun collect(value: com.google.gson.JsonElement) {
                when {
                    value.isJsonObject -> value.asJsonObject.entrySet().forEach { collect(it.value) }
                    value.isJsonArray -> value.asJsonArray.forEach(::collect)
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                        val hash = value.asString
                        if (isSha256(hash) && add(hash)) pending.add(hash)
                    }
                }
            }
            generationParent?.listFiles().orEmpty().filter { it.isDirectory &&
                (it.name.startsWith(".thoughts-library-stage-") || it.name == "current_pdfs" || it.name == ".thoughts-library-previous")
            }.forEach { directory ->
                directory.listFiles().orEmpty().filter { it.name.endsWith(".thoughts-project") }.forEach { manifest ->
                    runCatching {
                        counters.readings++
                        val reading = JsonParser.parseString(manifest.readText()).asJsonObject
                        collect(reading)
                        reading.getAsJsonArray("documents").forEach { entry ->
                            val hash = entry.asJsonObject.string("package_sha256")
                            // Activation moves shared reading objects from the previous generation into the active one.
                            val file = listOfNotNull(File(directory, "objects/$hash"), readerIndex?.parentFile?.let { File(it, "objects/$hash") })
                                .firstOrNull { it.isFile } ?: File(directory, "objects/$hash")
                            counters.packages++
                            counters.packageIds += hash
                            val references = packageReferences(file, hash, counters)
                                ?: error("Retained document package is unavailable")
                            collectAll(references)
                        }
                    }.getOrElse { return null }
                }
            }
            // Reachable JSON names many digests that are not stored objects (PDF digests,
            // passage ids). Test membership against one listing of both object directories
            // instead of two stats per digest; a digest stored after the listing is expanded
            // in the next round, so the result is what per-digest stats would have found.
            var stored = storedObjectNames()
            var missing = ArrayList<String>()
            while (true) {
                while (pending.isNotEmpty()) {
                    val hash = pending.removeFirst()
                    counters.visited++
                    if (hash !in stored) {
                        missing += hash
                        continue
                    }
                    val objectFile = listOf(File(privateObjects, hash), File(reusableObjects, hash)).firstOrNull { it.isFile } ?: continue
                    if (objectFile.parentFile == reusableObjects) {
                        privateObjects.mkdirs()
                        val pinned = File(privateObjects, hash)
                        if (!objectFile.renameTo(pinned)) objectFile.copyTo(pinned, overwrite = true)
                        counters.pinned++
                    }
                    val pinned = File(privateObjects, hash).takeIf { it.isFile } ?: objectFile
                    val misses = counters.index.parsed
                    collectAll(objectReferences.references(hash, pinned, MAX_MANIFEST_BYTES, counters.index))
                    // Reading a new object is the only real work left; let an active editor run first.
                    if (counters.index.parsed != misses) cooperate()
                }
                stored = storedObjectNames()
                val (appeared, absent) = missing.partition { it in stored }
                if (appeared.isEmpty()) break
                pending.addAll(appeared)
                missing = ArrayList(absent)
            }
            bundle.sources.values.forEach { add(it.objectId) }
            bundle.resources.values.forEach { add(it.objectId) }
            bundle.pdfs.values.flatMap(OfflineBundlePdf::segments).forEach { segment ->
                segment.inputs.forEach { add(it.sourceSha256) }
                segment.synctexObject?.let(::add)
                segment.rawSyncTexObject?.let(::add)
            }
            workingStates.listFiles().orEmpty().mapNotNull(::parseWorkingState).forEach { state ->
                add(state.baseObject)
            }
        }
    }

    private fun storedObjectNames(): Set<String> = HashSet<String>().apply {
        privateObjects.list()?.let(::addAll)
        reusableObjects.list()?.let(::addAll)
    }

    /**
     * The SHA-256 strings a verified document package names: indexed when it is a JSON object
     * or array, otherwise from the earlier tree walk. Null when the package is missing,
     * damaged or does not parse; cleanup then prunes nothing.
     */
    private fun packageReferences(file: File, hash: String, counters: RetainedObjectCounters): Array<String>? {
        if (!file.isFile || file.length() !in 0..MAX_MANIFEST_BYTES) return null
        if (verifiedFileCache.sha256(file) != hash) return null
        val entry = objectReferences.verifiedEntry(hash, file, counters.index) ?: return null
        return when (entry.kind) {
            ObjectReferenceIndex.KIND_JSON -> entry.references
            ObjectReferenceIndex.KIND_INVALID -> null
            else -> {
                counters.legacyPackageReads++
                val raw = readVerifiedObject(file, hash, MAX_MANIFEST_BYTES) ?: return null
                val found = LinkedHashSet<String>()
                fun walk(value: com.google.gson.JsonElement) {
                    when {
                        value.isJsonObject -> value.asJsonObject.entrySet().forEach { walk(it.value) }
                        value.isJsonArray -> value.asJsonArray.forEach(::walk)
                        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                            value.asString.takeIf(::isSha256)?.let(found::add)
                    }
                }
                walk(JsonParser.parseString(raw.toString(StandardCharsets.UTF_8)))
                found.toTypedArray()
            }
        }
    }

    private fun archiveReusableObject(source: File) {
        if (!isSha256(source.name) || source.length() !in 0..MAX_OBJECT_BYTES) {
            source.delete()
            return
        }
        reusableObjects.mkdirs()
        val destination = File(reusableObjects, source.name)
        if (
            destination.isFile &&
            hasVerifiedObject(destination, destination.name, MAX_OBJECT_BYTES)
        ) {
            source.delete()
            destination.setLastModified(System.currentTimeMillis())
            return
        }
        destination.delete()
        if (source.renameTo(destination)) {
            destination.setLastModified(System.currentTimeMillis())
            return
        }
        if (hasVerifiedObject(source, source.name, MAX_OBJECT_BYTES)) copyVerifiedObject(source, destination, source.name)
        source.delete()
    }

    /** Keeps the newest cached objects within the count and byte limits; returns their names. */
    private fun pruneReusableObjectCache(): Set<String> {
        var retainedBytes = 0L
        var retainedCount = 0
        val kept = HashSet<String>()
        // Read each modification time once; the comparator used to stat both files per comparison.
        reusableObjects.listFiles().orEmpty()
            .map { it to it.lastModified() }
            .sortedWith(compareByDescending<Pair<File, Long>> { it.second }.thenBy { it.first.name })
            .forEach { (file, _) ->
                val size = file.length()
                val retain = file.isFile && isSha256(file.name) && size in 0..MAX_OBJECT_BYTES &&
                    retainedCount < MAX_REUSABLE_OBJECTS &&
                    retainedBytes <= MAX_REUSABLE_OBJECT_BYTES - size
                if (retain) {
                    retainedCount += 1
                    retainedBytes += size
                    kept += file.name
                } else {
                    file.delete()
                }
            }
        return kept
    }

    private fun pruneWorkingTree(bundle: OfflineSourceBundle) {
        val activeSources = bundle.sources.keys + bundle.resources.keys
        workingStates.listFiles().orEmpty().filter(File::isFile).forEach { stateFile ->
            val state = parseWorkingState(stateFile) ?: return@forEach
            if (state.sourceFile in activeSources) return@forEach
            val working = safeWorkingFile(state.sourceFile) ?: return@forEach
            if (working.isFile && hashFile(working) != state.baseSha256) return@forEach
            if (working.isFile) working.delete()
            stateFile.delete()
            appWrittenCopy(state.sourceFile).delete()
        }
        rebuildObservationIndex()
    }

    private fun readWorkingState(sourceFile: String): WorkingSourceState? =
        parseWorkingState(stateFile(sourceFile))

    private fun parseWorkingState(file: File): WorkingSourceState? {
        val value = parseObject(file, MAX_STATE_BYTES) ?: return null
        val sourceFile = value.string("source_file").takeIf(::isSafeEditablePath) ?: return null
        val pdfFilename = value.string("pdf_filename").takeIf(::isSafePdfFilename) ?: return null
        val pdfFilenames = value.getAsJsonArray("pdf_filenames")
            ?.mapNotNull { element ->
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.takeIf(::isSafePdfFilename)
            }
            ?.takeIf { it.isNotEmpty() && it.size == it.toSet().size }
            ?: listOf(pdfFilename)
        return WorkingSourceState(
            sourceFile,
            value.string("bundle_id").takeIf(::isSha256) ?: return null,
            value.string("base_sha256").takeIf(::isSha256) ?: return null,
            value.string("base_object").takeIf(::isSha256) ?: return null,
            pdfFilename,
            value.string("pdf_sha256").takeIf(::isSha256) ?: return null,
            pdfFilenames,
            value.string("request_id").takeIf(::isUuid),
            value.int("draft_revision").coerceAtLeast(0),
            value.string("last_queued_sha256").takeIf(::isSha256),
            value.optionalLong("observed_size_bytes")?.takeIf { it >= 0 },
            value.optionalLong("observed_modified_at_millis")?.takeIf { it >= 0 },
            value.string("app_written_sha256").takeIf(::isSha256),
        )
    }

    private fun writeWorkingState(state: WorkingSourceState) {
        atomicWrite(stateFile(state.sourceFile), JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("source_file", state.sourceFile)
            addProperty("bundle_id", state.bundleId)
            addProperty("base_sha256", state.baseSha256)
            addProperty("base_object", state.baseObject)
            addProperty("pdf_filename", state.pdfFilename)
            addProperty("pdf_sha256", state.pdfSha256)
            add("pdf_filenames", JsonArray().apply { state.pdfFilenames.forEach(::add) })
            state.requestId?.let { addProperty("request_id", it) }
            addProperty("draft_revision", state.draftRevision)
            state.lastQueuedSha256?.let { addProperty("last_queued_sha256", it) }
            state.observedSizeBytes?.let { addProperty("observed_size_bytes", it) }
            state.observedModifiedAtMillis?.let { addProperty("observed_modified_at_millis", it) }
            state.appWrittenSha256?.let { addProperty("app_written_sha256", it) }
        }.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun WorkingSourceState.withObservedFile(file: File?): WorkingSourceState =
        if (file?.isFile == true) {
            copy(
                observedSizeBytes = file.length(),
                observedModifiedAtMillis = file.lastModified(),
            )
        } else {
            copy(observedSizeBytes = null, observedModifiedAtMillis = null)
        }

    private fun workingStateFiles(): List<File> = workingStates
        .listFiles { file -> file.isFile && file.extension == "json" }
        .orEmpty()
        .sortedBy { it.name }

    private fun stateNamesSha256(files: List<File>): String =
        sha256(files.joinToString("\n") { it.name })

    private fun File.observation(): WorkingFileObservation =
        WorkingFileObservation(length(), lastModified())

    private fun readObservationIndex(): WorkingObservationIndex? {
        val root = parseObject(observationIndex, MAX_OBSERVATION_INDEX_BYTES) ?: return null
        if (root.int("schema_version") != OBSERVATION_INDEX_SCHEMA_VERSION) return null
        val stateNamesSha256 = root.string("state_names_sha256").takeIf(::isSha256)
            ?: return null
        val files = linkedMapOf<String, WorkingFileObservation>()
        val entries = root.getAsJsonArray("files") ?: return null
        for (element in entries) {
            val value = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val sourceFile = value.string("source_file").takeIf(::isSafeEditablePath) ?: return null
            val sizeBytes = value.optionalLong("size_bytes")?.takeIf { it >= -1 } ?: return null
            val modifiedAtMillis = value.optionalLong("modified_at_millis")?.takeIf { it >= -1 }
                ?: return null
            files[sourceFile] = WorkingFileObservation(sizeBytes, modifiedAtMillis)
        }
        return WorkingObservationIndex(stateNamesSha256, files)
    }

    private fun writeObservationIndex(index: WorkingObservationIndex) {
        atomicWrite(observationIndex, JsonObject().apply {
            addProperty("schema_version", OBSERVATION_INDEX_SCHEMA_VERSION)
            addProperty("state_names_sha256", index.stateNamesSha256)
            add("files", JsonArray().apply {
                index.files.toSortedMap().forEach { (sourceFile, observed) ->
                    add(JsonObject().apply {
                        addProperty("source_file", sourceFile)
                        addProperty("size_bytes", observed.sizeBytes)
                        addProperty("modified_at_millis", observed.modifiedAtMillis)
                    })
                }
            })
        }.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun rebuildObservationIndex(
        stateFiles: List<File> = workingStateFiles(),
    ): WorkingObservationIndex {
        val files = linkedMapOf<String, WorkingFileObservation>()
        for (stateFile in stateFiles) {
            val state = parseWorkingState(stateFile) ?: continue
            files[state.sourceFile] = WorkingFileObservation(
                state.observedSizeBytes ?: -1,
                state.observedModifiedAtMillis ?: -1,
            )
        }
        return WorkingObservationIndex(stateNamesSha256(stateFiles), files).also(::writeObservationIndex)
    }

    private fun updateObservation(sourceFile: String, working: File?) {
        val file = working?.takeIf(File::isFile) ?: return
        val stateFiles = workingStateFiles()
        val namesSha256 = stateNamesSha256(stateFiles)
        val index = readObservationIndex()
            ?.takeIf { it.stateNamesSha256 == namesSha256 }
            ?: rebuildObservationIndex(stateFiles)
        index.files[sourceFile] = file.observation()
        writeObservationIndex(index)
    }

    /** Makes the next external-edit scan read [sourceFile] again. */
    private fun forgetObservation(sourceFile: String) {
        val stateFiles = workingStateFiles()
        val namesSha256 = stateNamesSha256(stateFiles)
        val index = readObservationIndex()
            ?.takeIf { it.stateNamesSha256 == namesSha256 }
            ?: rebuildObservationIndex(stateFiles)
        index.files[sourceFile] = WorkingFileObservation(-1, -1)
        writeObservationIndex(index)
    }

    private fun stateFile(sourceFile: String): File = File(workingStates, "${sha256(sourceFile)}.json")

    private fun safeWorkingFile(sourceFile: String): File? {
        if (!isSafeEditablePath(sourceFile)) return null
        val root = workingDirectory.canonicalFile
        val candidate = File(root, sourceFile).canonicalFile
        return candidate.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    private fun readVerifiedObject(file: File, expectedSha: String, maximum: Long): ByteArray? {
        return verifiedFileCache.read(file, expectedSha, maximum)
    }

    private fun hasVerifiedObject(file: File, expectedSha: String, maximum: Long): Boolean =
        file.isFile && file.length() in 0..maximum && verifiedFileCache.sha256(file) == expectedSha

    private fun readUtf8(file: File): String? = runCatching {
        if (!file.isFile || file.length() > SourceEditQueue.MAX_SOURCE_BYTES) return@runCatching null
        decodeUtf8(file.readBytes())
    }.getOrNull()

    private fun checkResourceObjects(bundle: OfflineSourceBundle) {
        bundle.resources.values.forEach { resource ->
            if (resource.kind == "html_attachment") {
                val file = File(privateObjects, resource.objectId)
                check(file.length() == resource.bytes && hasVerifiedObject(file, resource.objectId, 100_000_000L))
                return@forEach
            }
            val raw = readVerifiedObject(
                File(privateObjects, resource.objectId),
                resource.objectId,
                SourceEditQueue.MAX_SOURCE_BYTES.toLong(),
            ) ?: error("Missing source resource object ${resource.objectId}")
            check(raw.size.toLong() == resource.bytes) {
                "Source resource byte count differs for ${resource.path}"
            }
            decodeUtf8(raw)
        }
    }

    private fun decodeUtf8(raw: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw))
        .toString()

    private fun atomicWrite(destination: File, raw: ByteArray) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(raw)
            stream.fd.sync()
        }
        check(temporary.renameTo(destination)) { "Could not publish ${destination.path}" }
    }

    companion object {
        private val pendingMergeCache = hashMapOf<String, Pair<String, List<Pair<String, String>>>>()
        private const val SCHEMA_VERSION = 3
        /** [pruneActiveBundle] walks every file for an already pruned generation every this many Syncs. */
        internal const val CLEANUP_INVENTORY_CHECK_EVERY = 8
        private const val INDEX_FILENAME = "index.json"
        private const val MAX_INDEX_BYTES = 100_000L
        private const val MAX_MANIFEST_BYTES = 64_000_000L
        private const val MAX_OBJECT_BYTES = 100_000_000L
        private const val MAX_REUSABLE_OBJECT_BYTES = 200_000_000L
        private const val MAX_REUSABLE_OBJECTS = 4_096
        private const val MAX_STATE_BYTES = 100_000L
        private const val MAX_OBSERVATION_INDEX_BYTES = 1_000_000L
        private const val OBSERVATION_INDEX_SCHEMA_VERSION = 1
        private const val OVERVIEW_PROJECTION_FILENAME = "overview-projection-v1.json"
        private const val OVERVIEW_PROJECTION_SCHEMA_VERSION = 4
        private const val MAX_OVERVIEW_PROJECTION_BYTES = 64_000_000L

        private val AGENT_PROPOSAL_ID = Regex(
            "^[a-z0-9][a-z0-9._:-]{0,127}$",
        )

        private fun isAgentProposalId(value: String): Boolean = AGENT_PROPOSAL_ID.matches(value)

        private fun isReviewCommentId(value: String): Boolean =
            value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() || it == '\n' || it == '\r' }

        /** Where the working copy is now: the setting "Keep sources in a shared folder" ([SourceWorkingLocation]). */
        internal fun workingDirectory(context: Context): File = SourceWorkingLocation.workingCopy(context).directory
    }
}

internal object OfflineSyncTexResolver {
    private data class Candidate(
        val sourceFile: String,
        val line: Int,
        val distance: Double,
        val area: Double,
    )

    private val inputPattern = Regex("^Input:(\\d+):(.*)$")
    private val pagePattern = Regex("^\\{(\\d+)$")
    private val nodePattern = Regex(
        "^([\\[\\(hvrx])(-?\\d+),(-?\\d+):(-?\\d+),(-?\\d+)(?::(-?\\d+),(-?\\d+),(-?\\d+))?.*$"
    )

    fun resolve(
        synctex: File,
        page: Int,
        x: Float,
        y: Float,
        authoredInputs: List<OfflineBundleInput>,
        expectedFormat: String? = null,
    ): Pair<String, Int>? {
        if (!synctex.isFile || page < 1 || !x.isFinite() || !y.isFinite()) return null
        if (isPageIndex(synctex)) {
            return resolvePageIndex(synctex, page, x, y, authoredInputs, expectedFormat)
        }
        val authoredByPath = authoredInputs.associateBy(OfflineBundleInput::synctexPath)
        val tags = mutableMapOf<Int, String>()
        var unit = 1.0
        var magnification = 1000.0
        var xOffset = 0.0
        var yOffset = 0.0
        var currentPage = 0
        var bestPoint: Candidate? = null
        var bestBox: Candidate? = null
        runCatching {
            BufferedReader(InputStreamReader(GZIPInputStream(FileInputStream(synctex)), StandardCharsets.UTF_8)).use { reader ->
                reader.forEachLine { line ->
                    inputPattern.matchEntire(line)?.let { match ->
                        tags[match.groupValues[1].toInt()] = match.groupValues[2]
                        return@forEachLine
                    }
                    when {
                        line.startsWith("Unit:") -> unit = line.substringAfter(':').toDoubleOrNull() ?: unit
                        line.startsWith("Magnification:") -> {
                            magnification = line.substringAfter(':').toDoubleOrNull() ?: magnification
                        }
                        line.startsWith("X Offset:") -> xOffset = line.substringAfter(':').toDoubleOrNull() ?: xOffset
                        line.startsWith("Y Offset:") -> yOffset = line.substringAfter(':').toDoubleOrNull() ?: yOffset
                    }
                    pagePattern.matchEntire(line)?.let { match ->
                        currentPage = match.groupValues[1].toInt()
                        return@forEachLine
                    }
                    if (currentPage != page) return@forEachLine
                    val match = nodePattern.matchEntire(line) ?: return@forEachLine
                    val kind = match.groupValues[1]
                    val tag = match.groupValues[2].toIntOrNull() ?: return@forEachLine
                    val sourcePath = tags[tag] ?: return@forEachLine
                    val source = authoredByPath[sourcePath] ?: return@forEachLine
                    val sourceLine = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 }
                        ?: return@forEachLine
                    val scale = unit * magnification / 1000.0 / SYNCTEX_SP_PER_BIG_POINT
                    val nodeX = (match.groupValues[4].toDouble() + xOffset) * scale
                    val baselineY = (match.groupValues[5].toDouble() + yOffset) * scale
                    val width = match.groupValues[6].toDoubleOrNull()?.times(scale) ?: 0.0
                    val height = match.groupValues[7].toDoubleOrNull()?.times(scale) ?: 0.0
                    val depth = match.groupValues[8].toDoubleOrNull()?.times(scale) ?: 0.0
                    val left = min(nodeX, nodeX + width)
                    val right = max(nodeX, nodeX + width)
                    val top = baselineY - max(0.0, height)
                    val bottom = baselineY + max(0.0, depth)
                    val dx = when {
                        x < left -> left - x
                        x > right -> x - right
                        else -> 0.0
                    }
                    val dy = when {
                        y < top -> top - y
                        y > bottom -> y - bottom
                        else -> 0.0
                    }
                    val candidate = Candidate(
                        source.sourceFile,
                        sourceLine,
                        dx * dx + dy * dy,
                        max(0.0, right - left) * max(0.0, bottom - top),
                    )
                    val existing = if (kind == "x") bestPoint else bestBox
                    if (existing == null || candidate.distance < existing.distance ||
                        (candidate.distance == existing.distance && candidate.area < existing.area)
                    ) {
                        if (kind == "x") bestPoint = candidate else bestBox = candidate
                    }
                }
            }
        }.getOrNull() ?: return null
        return (bestPoint ?: bestBox)?.let { it.sourceFile to it.line }
    }

    private fun isPageIndex(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == magic.size &&
                magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun resolvePageIndex(
        index: File,
        page: Int,
        x: Float,
        y: Float,
        authoredInputs: List<OfflineBundleInput>,
        expectedFormat: String?,
    ): Pair<String, Int>? {
        val authoredByPath = authoredInputs.associateBy(OfflineBundleInput::synctexPath)
        var bestPoint: Candidate? = null
        var bestBox: Candidate? = null
        runCatching {
            ZipFile(index).use { archive ->
                val headerEntry = archive.getEntry("format.json")
                val parsedHeader = runCatching {
                    headerEntry?.let { entry ->
                        archive.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                            JsonParser.parseReader(reader).asJsonObject.string("format")
                        }
                    }
                }.getOrNull()
                // Some already-published native indexes have a damaged format.json
                // but intact CRC-checked page entries. The verified source manifest
                // supplies the format; do not infer it from damaged bytes.
                if (headerEntry != null && parsedHeader.isNullOrBlank() && expectedFormat == null) return null
                val header = parsedHeader?.takeIf(String::isNotBlank) ?: expectedFormat
                val reverseFormats = setOf("thoughts-reverse-synctex", "thoughts-reverse-synctex-v1")
                if (header != null && header !in reverseFormats && header != "thoughts-markdown-source-map-v1") return null
                if (expectedFormat != null && header != expectedFormat && !(header in reverseFormats && expectedFormat in reverseFormats)) return null
                val markdown = header == "thoughts-markdown-source-map-v1"
                val entry = archive.getEntry("pages/$page.tsv") ?: return null
                archive.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val fields = line.split('\t')
                        if (fields.size != (if (markdown) 11 else 8)) return@forEach
                        val kind = fields[0]
                        val source = authoredByPath[fields[1]] ?: return@forEach
                        val sourceLine = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                        val nodeX = fields[3].toDoubleOrNull() ?: return@forEach
                        val baselineY = fields[4].toDoubleOrNull() ?: return@forEach
                        val width = fields[5].toDoubleOrNull() ?: return@forEach
                        val height = fields[6].toDoubleOrNull() ?: return@forEach
                        val depth = fields[7].toDoubleOrNull() ?: return@forEach
                        if (listOf(nodeX, baselineY, width, height, depth).any { !it.isFinite() }) return@forEach
                        val left = min(nodeX, nodeX + width)
                        val right = max(nodeX, nodeX + width)
                        val top = baselineY - max(0.0, height)
                        val bottom = baselineY + max(0.0, depth)
                        val dx = when {
                            x < left -> left - x
                            x > right -> x - right
                            else -> 0.0
                        }
                        val dy = when {
                            y < top -> top - y
                            y > bottom -> y - bottom
                            else -> 0.0
                        }
                        val candidate = Candidate(
                            source.sourceFile,
                            sourceLine,
                            dx * dx + dy * dy,
                            max(0.0, right - left) * max(0.0, bottom - top),
                        )
                        val existing = if (kind == "x") bestPoint else bestBox
                        if (existing == null || candidate.distance < existing.distance ||
                            (candidate.distance == existing.distance && candidate.area < existing.area)
                        ) {
                            if (kind == "x") bestPoint = candidate else bestBox = candidate
                        }
                    }
                }
            }
        }.getOrNull() ?: return null
        return (bestPoint ?: bestBox)?.let { it.sourceFile to it.line }
    }

    private const val SYNCTEX_SP_PER_BIG_POINT = 65_781.76
}

private fun parseObject(file: File, maximum: Long): JsonObject? = runCatching {
    if (!file.isFile || file.length() > maximum) return@runCatching null
    JsonParser.parseString(file.readText(StandardCharsets.UTF_8)).asJsonObject
}.getOrNull()

private fun JsonObject.string(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

private fun JsonObject.int(key: String): Int =
    runCatching { get(key)?.asInt ?: 0 }.getOrDefault(0)

private fun JsonObject.long(key: String): Long =
    runCatching { get(key)?.asLong ?: 0L }.getOrDefault(0L)

private fun JsonObject.optionalLong(key: String): Long? =
    runCatching { get(key)?.asLong }.getOrNull()

private fun JsonObject.float(key: String): Float =
    runCatching { get(key)?.asFloat ?: Float.NaN }.getOrDefault(Float.NaN)

private fun JsonObject.boolean(key: String): Boolean =
    runCatching { get(key)?.asBoolean ?: false }.getOrDefault(false)

internal fun parseReviewCorrespondence(
    value: JsonObject,
    pdfs: Map<String, OfflineBundlePdf>,
    requireCompilerEvidence: Boolean = false,
): ReviewCorrespondence {
    require(value.int("schema_version") == 2)
    val kind = value.string("kind").also { require(it in setOf("comment", "agent_edit")) }
    val itemId = value.string("item_id").also { require(it.isNotBlank() && it.length <= 512) }
    val expectedKey = "${if (kind == "comment") "comment" else "proposal"}:$itemId"
    val reviewKey = value.string("review_key").also { require(it == expectedKey) }
    val threadRootId = value.string("thread_root_id")
    if (kind == "comment") require(threadRootId.isNotBlank())
    val state = value.string("state").also {
        require(it in setOf("exact_text", "exact_point", "source_only", "no_source", "linking", "needs_attention"))
    }
    val sourceBundleSha256 = value.string("source_bundle_sha256").also {
        require(it.isEmpty() || isSha256(it))
    }
    val targetValues = value.getAsJsonArray("current_source_targets")?.takeIf { it.size() > 0 }
        ?: value.getAsJsonArray("source_targets")
    val targets = targetValues?.map { element ->
        val target = element.asJsonObject
        val role = target.string("role").also { require(it in setOf("target", "before", "after")) }
        val sourceFile = target.string("source_file").also { require(isSafeSourcePath(it)) }
        val sourceSha256 = target.string("source_sha256").also { require(isSha256(it)) }
        val byteStart = target.long("byte_start").also { require(it >= 0) }
        val byteEnd = target.long("byte_end").also { require(it >= byteStart) }
        val lineStart = target.int("line_start").also { require(it >= 1) }
        val lineEnd = target.int("line_end").also { require(it >= lineStart) }
        val columnStart = target.int("column_start").also { require(it >= 1) }
        val columnEnd = target.int("column_end").also { require(it >= 1) }
        val textSha256 = target.string("text_sha256").also { require(isSha256(it)) }
        val renderIntent = target.string("render_intent").also {
            require(it in setOf("text", "point", "not_rendered"))
        }
        if (renderIntent == "text") require(byteEnd > byteStart)
        ReviewSourceTarget(
            role,
            sourceFile,
            sourceSha256,
            byteStart,
            byteEnd,
            lineStart,
            lineEnd,
            columnStart,
            columnEnd,
            textSha256,
            renderIntent,
            target.string("preview"),
            target.string("anchor_id"),
        )
    }.orEmpty()
    val locations = value.getAsJsonArray("locations")?.map { element ->
        val location = element.asJsonObject
        val filename = location.string("pdf_filename").also {
            require(isSafePdfFilename(it) && it in pdfs)
        }
        val pdfSha256 = location.string("pdf_sha256").also {
            require(isSha256(it) && pdfs[filename]?.pdfSha256 == it)
        }
        val locationSourceBundleSha256 = location.string("source_bundle_sha256").also {
            require(isSha256(it) && it == sourceBundleSha256)
        }
        val locationBuildSha256 = location.string("build_sha256").also { require(isSha256(it)) }
        val pageIndex = location.int("page_index").also { require(it >= 0) }
        val page = location.int("page").also {
            require(it == pageIndex + 1 && (pdfs[filename]?.pageCount?.let { count -> count < 1 || it <= count } != false))
        }
        val role = location.string("role").also { require(it in setOf("target", "before", "after")) }
        val placementKind = location.string("placement_kind").also {
            require(it in setOf("exact_text", "exact_point"))
        }
        val regions = location.getAsJsonArray("regions")?.map { region ->
            region.asJsonArray.map { it.asFloat }.also { coordinates ->
                require(
                    coordinates.size == 4 && coordinates.all { it.isFinite() && it in 0f..1f } &&
                        coordinates[0] < coordinates[2] && coordinates[1] < coordinates[3],
                )
            }
        }.orEmpty()
        val point = location.getAsJsonArray("point")?.map { it.asFloat }.orEmpty().also {
            require(it.isEmpty() || (it.size == 2 && it.all { channel -> channel.isFinite() && channel in 0f..1f }))
        }
        require((placementKind == "exact_text" && regions.isNotEmpty() && point.isEmpty()) ||
            (placementKind == "exact_point" && point.size == 2 && regions.isEmpty()))
        val rawProof = location.getAsJsonObject("proof") ?: throw IllegalArgumentException("missing placement proof")
        val proof = ReviewPlacementProof(
            resolverVersion = rawProof.int("resolver_version").also { require(it in setOf(3, 4) || (!requireCompilerEvidence && it == 2)) },
            method = rawProof.string("method").also { require(it.isNotBlank()) },
            sourceSha256 = rawProof.string("source_sha256").also { require(it.isEmpty() || isSha256(it)) },
            sourceBundleSha256 = rawProof.string("source_bundle_sha256").also {
                require(it == locationSourceBundleSha256)
            },
            buildSha256 = rawProof.string("build_sha256").also { require(it == locationBuildSha256) },
            pdfSha256 = rawProof.string("pdf_sha256").also { require(it == pdfSha256) },
            extractedTextSha256 = rawProof.string("extracted_text_sha256").also {
                require(it.isEmpty() || isSha256(it))
            },
            candidateCount = rawProof.int("candidate_count").also { require(it >= 0) },
            uniqueness = rawProof.string("uniqueness").also {
                require(it in setOf("unique", "not_applicable"))
            },
            verified = rawProof.boolean("verified").also { require(it) },
            reason = rawProof.string("reason"),
            humanConfirmed = rawProof.get("human_confirmation")?.isJsonObject == true,
            markerIds = rawProof.getAsJsonArray("marker_ids")?.map { it.asString }.orEmpty(),
            occurrence = rawProof.int("occurrence"),
            sourceFormat = rawProof.string("source_format").ifBlank { "latex" },
            rendererEvidenceSha256 = rawProof.string("renderer_evidence_sha256"),
            fragmentId = rawProof.string("fragment_id"),
        )
        if (proof.resolverVersion in setOf(3, 4)) {
            require(proof.hasCompilerEvidence(reviewKey, placementKind))
            // Navigation may use a metadata-only newer source revision. Proofs
            // remain bound to the original compiler input and matching role.
            require(value.getAsJsonArray("source_targets")?.any { target ->
                target.asJsonObject.string("source_sha256") == proof.sourceSha256 &&
                    target.asJsonObject.string("role") == role
            } == true)
        }
        LogicalCommentPdfLocation(
            pdfFilename = filename,
            pdfSha256 = pdfSha256,
            page = page,
            status = "exact",
            point = point,
            regions = regions,
            sourceSha256 = proof.sourceSha256,
            sourceBundleSha256 = proof.sourceBundleSha256,
            buildSha256 = proof.buildSha256,
            role = role,
            placementKind = placementKind,
            proof = proof,
            // Absent in older bundles: every location is then the source's own occurrence.
            duplicate = location.boolean("duplicate"),
        )
    }.orEmpty()
    when (state) {
        "exact_text" -> require(locations.isNotEmpty() && locations.all { it.placementKind == "exact_text" })
        "exact_point" -> require(locations.isNotEmpty() && locations.all { it.placementKind == "exact_point" })
        "source_only" -> require(targets.isNotEmpty() && targets.all { it.renderIntent == "not_rendered" } && locations.isEmpty())
        "no_source" -> require(targets.isEmpty())
    }
    return ReviewCorrespondence(
        reviewKey = reviewKey,
        kind = kind,
        itemId = itemId,
        threadRootId = threadRootId,
        lifecycle = value.string("lifecycle").ifBlank { "active" },
        state = state,
        reason = value.string("reason"),
        sourceBundleSha256 = sourceBundleSha256,
        sourceTargets = targets,
        locations = locations,
    )
}

private val KNOWN_LOGICAL_SCOPE_STATES = setOf("pdf_only", "source_linked", "needs_attention", "standalone")

private fun parseLogicalComment(
    value: JsonObject,
): OfflineLogicalComment {
    require(value.int("schema_version") == 1)
    val id = value.string("comment_id").also { require(it.isNotBlank() && it.length <= 512) }
    val threadRootId = value.string("thread_root_id").also { require(it.isNotBlank()) }
    val parentId = value.string("parent_id").takeIf(String::isNotBlank)
    require((parentId == null && threadRootId == id) || (parentId != null && threadRootId != id))
    val scope = value.getAsJsonObject("scope") ?: JsonObject()
    val origin = value.get("origin")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    return OfflineLogicalComment(
        id = id,
        threadRootId = threadRootId,
        parentId = parentId,
        revision = value.long("revision").also { require(it >= 1) },
        deleted = value.boolean("deleted"),
        // An unknown or local-only state (for example source_unavailable) is shown under Issues;
        // the raw payload is kept and the comment is never dropped.
        scopeState = scope.string("state").ifBlank { "pdf_only" }.let {
            if (it in KNOWN_LOGICAL_SCOPE_STATES) it else "needs_attention"
        },
        sourceFile = value.string("source_file").ifBlank { scope.string("source_file") },
        sourceLine = value.int("source_line").takeIf { it > 0 } ?: scope.int("source_line"),
        originCollection = origin.string("collection"),
        originPageIndex = origin.int("page_index").coerceAtLeast(0),
        payloadJson = value.deepCopy().apply { remove("locations") }.toString(),
    )
}

internal fun OfflineOverviewSourceSnapshot.withLogicalPayloads(
    payloads: List<JsonObject>,
    preferPayloads: Boolean = false,
): OfflineOverviewSourceSnapshot {
    val byId = logicalComments.associateBy(OfflineLogicalComment::id).toMutableMap()
    payloads.sortedBy { it.long("revision") }.forEach { payload ->
        val id = payload.string("comment_id")
        val previous = byId[id]
        val parsed = runCatching {
            parseLogicalComment(payload)
        }.getOrNull() ?: return@forEach
        if (preferPayloads || previous == null || parsed.revision >= previous.revision) {
            // Keep tombstones so source-marker and per-PDF fallbacks cannot revive them.
            byId[id] = parsed.copy(
                // Content operations do not carry immutable geometry. Retain all
                // fragments and the correspondence through newer local revisions.
                correspondence = previous?.correspondence,
                sourceFile = parsed.sourceFile.ifBlank { previous?.correspondence?.sourceTargets?.firstOrNull()?.sourceFile.orEmpty() },
                sourceLine = parsed.sourceLine.takeIf { it > 0 } ?: previous?.correspondence?.sourceTargets?.firstOrNull()?.lineStart ?: 0,
            )
        }
    }
    val inherited = byId.values.map { comment ->
        val root = byId[comment.threadRootId]
        if (root == null || comment.id == root.id) comment
        else comment.copy(
            deleted = comment.deleted || root.deleted,
            correspondence = root.correspondence,
            sourceFile = root.sourceFile,
            sourceLine = root.sourceLine,
        )
    }
    return copy(logicalComments = inherited.sortedWith(compareBy(OfflineLogicalComment::threadRootId, OfflineLogicalComment::id)))
}


private const val RESOURCE_PROJECT_STYLE_OVERRIDE = "project_style_override"

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

private fun isSha256(value: String): Boolean = value.matches(SHA256_PATTERN)

private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

private fun isSafePdfFilename(value: String): Boolean =
    value.endsWith(".pdf") && value == File(value).name && !value.contains('/') && !value.contains('\\')

private fun isSafeSourcePath(value: String): Boolean {
    val markdown = value.endsWith(".md", ignoreCase = true) || value.endsWith(".markdown", ignoreCase = true)
    if ((!value.endsWith(".tex") && !markdown) || value.any { it.code < 32 } || value.startsWith('/') || value.contains('\\')) return false
    val parts = value.split('/')
    return parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." }
}

private fun isSafeResourcePath(kind: String, value: String): Boolean = when (kind) {
    "html_attachment" -> com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink.isPath(value)
    "project_style" -> value.matches(PROJECT_STYLE_PATH)
    RESOURCE_PROJECT_STYLE_OVERRIDE -> value.matches(PROJECT_STYLE_OVERRIDE_PATH)
    else -> false
}

internal fun isWorkspaceStyleResourcePath(value: String): Boolean =
    value.matches(PROJECT_STYLE_PATH) || value.matches(PROJECT_STYLE_OVERRIDE_PATH)

private fun isSafeEditablePath(value: String): Boolean =
    isSafeSourcePath(value) || isWorkspaceStyleResourcePath(value)

private val PROJECT_STYLE_PATH = Regex("^docs-[A-Za-z0-9_.-]+/tex/support/manual_style\\.sty$")

private val PROJECT_STYLE_OVERRIDE_PATH =
    Regex("^docs-[A-Za-z0-9_.-]+/tex/support/manual_style_override\\.sty$")

/**
 * [manifest] with the connected folders named as this phone calls them now
 * ([SourceFolderNames.localRenames]). A bundle that already names a new folder was built after
 * Fedora's rename and is read as it is; SyncTeX keeps its build paths, source text stays.
 */
internal fun followSourceFolderNames(manifest: JsonObject): JsonObject {
    val renames = SourceFolderNames.localRenames
    if (renames.isEmpty()) return manifest
    val news = renames.values.toSet()
    val renamed = manifest.getAsJsonArray("sources")?.any { source ->
        source.takeIf { it.isJsonObject }?.asJsonObject?.get("path")?.takeIf { it.isJsonPrimitive }?.asString
            ?.let { SourceOwner.of(it) in news } == true
    } == true
    if (renamed) return manifest
    return SourceFolderNames.rewriteJsonValues(manifest, renames) { key ->
        key == "synctex_path" || DefaultFolder.SOURCE_TEXT(key)
    }.asJsonObject
}

private fun offlineResolutionId(bundleId: String, sourceFile: String): String =
    UUID.nameUUIDFromBytes("$bundleId\u001f$sourceFile".toByteArray(StandardCharsets.UTF_8)).toString()

private fun hashFile(file: File): String? = runCatching {
    if (!file.isFile) return@runCatching null
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

private fun sha256(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8))

private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { "%02x".format(it) }
