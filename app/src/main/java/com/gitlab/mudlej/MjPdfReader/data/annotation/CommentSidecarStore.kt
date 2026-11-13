// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.data.render.CommentDeliveryOutbox
import com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedCommentIdentity
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.VerifiedPdfActivationStore
import com.gitlab.mudlej.MjPdfReader.data.render.sourceLinkedLogicalFile
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.core.io.parseCommentTimestamp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class StoredPdfComment(
    val pageIndex: Int,
    val subtype: Int,
    val id: String,
    val rects: List<RectF>,
    val color: Int,
    val contents: String,
    val author: String,
    val creationDate: String,
    val modifiedDate: String,
    val inReplyTo: String,
    val selectedText: String,
    val fragments: List<StoredPdfCommentFragment>,
    val priority: CommentPriority,
    val location: CommentLocation?,
    val anchorStatus: String = "",
    val chatGptUrl: String = "",
    val annotationPdfSha256: String = "",
)

internal fun sourceAnchorUnresolvedCommentNames(
    comments: List<StoredPdfComment>,
): Set<String> = comments.asSequence()
    .filter { it.inReplyTo.isBlank() }
    .filter {
        it.anchorStatus.equals("needs_anchor", ignoreCase = true) ||
            it.anchorStatus.equals("orphaned", ignoreCase = true)
    }
    .map(StoredPdfComment::id)
    .filter(String::isNotBlank)
    .toSet()

internal fun renderableStoredPdfComments(
    comments: List<StoredPdfComment>,
    activePdfSha256: String?,
): List<StoredPdfComment> {
    val active = activePdfSha256?.trim()?.lowercase().orEmpty()
    if (!active.matches(Regex("^[0-9a-f]{64}$"))) return comments
    return comments.filter { comment ->
        val annotation = comment.annotationPdfSha256.trim().lowercase()
        annotation == active
    }
}

data class StoredPdfCommentFragment(
    val pageIndex: Int,
    val rects: List<RectF>,
)

data class CommentSidecarLoadResult(
    val comments: List<StoredPdfComment>,
    val file: File,
    val bootstrapped: Boolean,
    val fingerprint: String,
)

data class CommentAnchorRequirement(
    val id: String,
    val revision: Int,
)

data class CommentDeliveryRecord(
    val id: String,
    val revision: Int,
    val json: String,
)

data class CommentSidecarFlushResult(
    val file: File,
    val documentUri: Uri,
    val documentName: String?,
    val anchorRequirements: List<CommentAnchorRequirement>,
)

data class RecoveredCommentImportResult(
    val file: File,
    val records: List<CommentDeliveryRecord>,
)

internal fun commentIdsRequiringAnchor(edits: List<AnnotationEdit>): List<String> {
    return edits.mapNotNull { edit ->
        when (edit) {
            is AnnotationEdit.AddPdfComment -> edit.name
            is AnnotationEdit.AddPdfMarkupComment -> edit.name
            is AnnotationEdit.AddPdfReply -> edit.name
            is AnnotationEdit.SetPdfCommentContents -> edit.name
            is AnnotationEdit.SetPdfCommentMetadata -> edit.name.takeIf { edit.rect != null }
            is AnnotationEdit.DeletePdfComment -> edit.name
            else -> null
        }
    }.distinct()
}

private data class ConflictFileSnapshot(
    val file: File,
    val length: Long,
    val lastModified: Long,
)

private data class MergedRecordLoad(
    val records: LinkedHashMap<String, JsonObject>,
    val conflicts: List<ConflictFileSnapshot>,
)

private data class CommentFragmentId(
    val logicalId: String,
    val index: Int,
)

/**
 * Legacy format import and recovery of pending pre-upgrade comment journals.
 *
 * The sidecar format deliberately remains compatible with the canonical JSONL files used by the
 * docs workspace. Unknown source-anchor fields are retained whenever an existing record changes.
 */
class CommentSidecarStore(
    private val context: Context,
    private val rootOverride: File? = null,
    private val deliveryOutbox: CommentDeliveryOutbox = CommentDeliveryOutbox.forContext(context),
) {
    private val sourceLinkedPdfIndex = SourceLinkedPdfIndex()
    private val verifiedPdfActivationStore = VerifiedPdfActivationStore(context)
    private val originId: String by lazy {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_ORIGIN_PREFERENCE, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_ORIGIN_PREFERENCE, it).apply()
            }
    }

    /** Format conversion only; normal actions commit these records to LogicalCommentStore. */
    internal fun encodeEdits(edits: List<AnnotationEdit>, pdfSha256: String): List<JsonObject> =
        linkedMapOf<String, JsonObject>().also { records ->
            edits.forEach { applyEdit(records, it, pdfSha256) }
        }.values.toList()

    internal fun importRecords(file: File): List<JsonObject> = loadAndMergeRecords(file).records.values.toList()

    internal fun importEmbedded(comments: List<PDFView.PdfComment>): List<JsonObject> =
        recordsFromEmbeddedComments(comments).values.toList()

    fun supports(edit: AnnotationEdit): Boolean = edit.isPdfCommentEdit()

    fun hasPending(uri: Uri): Boolean = synchronized(pendingLock) {
        pendingFile(uri).let { it.isFile && it.length() > 0 }
    }

    fun pendingJournalCount(): Int = synchronized(pendingLock) {
        File(context.filesDir, PENDING_DIRECTORY).listFiles { file ->
            file.isFile && file.extension.equals("journal", ignoreCase = true) && file.length() > 0
        }.orEmpty().size
    }

    /**
     * Queue the edit synchronously before returning control to the UI. This small fsynced private
     * journal is the crash-safe bridge to the asynchronously rewritten canonical sidecar.
     */
    fun queue(
        uri: Uri,
        edit: AnnotationEdit,
        annotationPdfSha256: String? = null,
    ): Boolean = queueAll(uri, listOf(edit), annotationPdfSha256)

    fun queueAll(
        uri: Uri,
        edits: List<AnnotationEdit>,
        annotationPdfSha256: String? = null,
    ): Boolean {
        if (edits.isEmpty() || edits.any { !supports(it) }) {
            return false
        }
        val normalizedAnnotationPdfSha256 = annotationPdfSha256
            ?.lowercase()
            ?.takeIf { it.matches(SHA256_REGEX) }
        if (!commentIdentity(uri, normalizedAnnotationPdfSha256).allowsMutation) {
            Log.w(TAG, "queue: rejected source-linked comment without a verified publication identity")
            return false
        }
        return synchronized(pendingLock) {
            runCatching {
                val file = pendingFile(uri)
                file.parentFile?.mkdirs()
                val lines = edits.map { edit ->
                    val pending = JsonParser.parseString(edit.toJsonLine()).asJsonObject
                    normalizedAnnotationPdfSha256
                        ?.let { pending.addProperty(KEY_ANNOTATION_PDF_SHA256, it) }
                    pending.toString()
                }
                FileOutputStream(file, true).use { stream ->
                    stream.write((lines.joinToString("\n", postfix = "\n")).toByteArray(StandardCharsets.UTF_8))
                    stream.fd.sync()
                }
                file.parentFile?.let(::syncDirectory)
                PendingChangesSignal.notifyChanged()
            }.onFailure { error ->
                Log.e(TAG, "queue: failed to persist pending comment edit", error)
            }.isSuccess
        }
    }

    fun commentIdentity(uri: Uri, annotationPdfSha256: String?): SourceLinkedCommentIdentity {
        val file = sourceLinkedLogicalFile(context, uri)
            ?: return SourceLinkedCommentIdentity.NOT_SOURCE_LINKED
        return verifiedPdfActivationStore.commentIdentity(
            sourceLinkedPdfIndex,
            file,
            annotationPdfSha256,
        )
    }

    suspend fun flush(
        uri: Uri,
        documentName: String?,
        embeddedComments: List<PDFView.PdfComment>,
        deliveryTarget: String? = null,
    ): Result<CommentSidecarFlushResult> = withContext(Dispatchers.IO) {
        runCatching {
            flushMutex.withLock {
                val pendingSnapshot = readPendingLines(uri)
                val sidecar = sidecarFile(uri, documentName)
                reconcileResolutionRegistry()
                val merged = loadAndMergeRecords(sidecar)
                val hasStoredSidecar = sidecar.isFile || merged.conflicts.isNotEmpty()
                val records = if (hasStoredSidecar) {
                    merged.records
                } else {
                    recordsFromEmbeddedComments(embeddedComments)
                }
                val pendingEdits = pendingSnapshot.mapNotNull(::pendingEdit)
                val edits = pendingEdits.map(PendingEdit::edit)
                pendingEdits.forEach {
                    applyEdit(records, it.edit, it.annotationPdfSha256)
                }
                val anchorRequirements = commentIdsRequiringAnchor(edits).mapNotNull { id ->
                    records[id]
                        ?.let { record ->
                            CommentAnchorRequirement(
                                id,
                                intValue(record, KEY_REVISION, 1).coerceAtLeast(1),
                            )
                        }
                }
                writeRecordsAtomically(sidecar, records.values.toList())
                if (deliveryTarget != null && anchorRequirements.isNotEmpty()) {
                    val requirementsById = anchorRequirements.associateBy(CommentAnchorRequirement::id)
                    val deliveryRecords = requirementsById.keys.mapNotNull { id ->
                        val record = records[id] ?: return@mapNotNull null
                        val requirement = requirementsById.getValue(id)
                        CommentDeliveryRecord(
                            id = id,
                            revision = requirement.revision,
                            json = record.toString(),
                        )
                    }
                    val batch = deliveryOutbox.enqueue(deliveryTarget, deliveryRecords)
                    // Persist the canonical identity locally before completing
                    // the save. This does not start Sync or use the network.
                    com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository(context)
                        .captureCommentBatches(listOf(batch))
                }
                discardPendingPrefix(uri, pendingSnapshot)
                discardMergedConflicts(sidecar, merged.conflicts)
                CommentSidecarFlushResult(
                    sidecar,
                    uri,
                    documentName,
                    anchorRequirements,
                )
            }
        }
    }

    suspend fun load(
        uri: Uri,
        documentName: String?,
        embeddedComments: List<PDFView.PdfComment>,
    ): Result<CommentSidecarLoadResult> = withContext(Dispatchers.IO) {
        runCatching {
            flushMutex.withLock {
                val sidecar = sidecarFile(uri, documentName)
                val existed = sidecar.isFile
                val pendingSnapshot = readPendingLines(uri)
                val merged = loadAndMergeRecords(sidecar)
                val resolvedIds = reconcileResolutionRegistry()
                val hasStoredSidecar = existed || merged.conflicts.isNotEmpty()
                val records = if (hasStoredSidecar) {
                    merged.records
                } else {
                    recordsFromEmbeddedComments(embeddedComments)
                }
                pendingSnapshot.mapNotNull(::pendingEdit).forEach {
                    applyEdit(records, it.edit, it.annotationPdfSha256)
                }
                if (!existed || pendingSnapshot.isNotEmpty() || merged.conflicts.isNotEmpty()) {
                    writeRecordsAtomically(sidecar, records.values.toList())
                    discardPendingPrefix(uri, pendingSnapshot)
                    discardMergedConflicts(sidecar, merged.conflicts)
                }
                val visibleRecords = records.values.filterNot { recordId(it) in resolvedIds }
                CommentSidecarLoadResult(
                    comments = visibleRecords.mapNotNull(::toStoredComment),
                    file = sidecar,
                    bootstrapped = !hasStoredSidecar,
                    fingerprint = recordsFingerprint(visibleRecords),
                )
            }
        }
    }

    suspend fun importRecoveredComments(
        canonicalUri: Uri,
        documentName: String,
        embeddedComments: List<PDFView.PdfComment>,
    ): Result<RecoveredCommentImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(embeddedComments.isNotEmpty()) {
                "The conflict PDF contains no recoverable comments"
            }
            flushMutex.withLock {
                val sidecar = sidecarFile(canonicalUri, documentName)
                reconcileResolutionRegistry()
                val merged = loadAndMergeRecords(sidecar)
                val records = merged.records
                val recovered = recordsFromEmbeddedComments(embeddedComments)
                recovered.forEach { (id, candidate) ->
                    val current = records[id]
                    records[id] = if (current == null) {
                        candidate
                    } else {
                        newest(current, candidate)
                    }
                }
                writeRecordsAtomically(sidecar, records.values.toList())
                discardMergedConflicts(sidecar, merged.conflicts)
                RecoveredCommentImportResult(
                    sidecar,
                    recovered.keys.mapNotNull { id ->
                        records[id]?.let { record ->
                            CommentDeliveryRecord(
                                id = id,
                                revision = intValue(record, KEY_REVISION, 1).coerceAtLeast(1),
                                json = record.toString(),
                            )
                        }
                    },
                )
            }
        }
    }

    suspend fun importRecoveredSidecar(
        canonicalUri: Uri,
        documentName: String,
        recoveredSidecar: File,
    ): Result<RecoveredCommentImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            flushMutex.withLock {
                val recovered = readRecords(recoveredSidecar)
                require(recovered.isNotEmpty()) {
                    "The conflict sidecar contains no recoverable comments"
                }
                val sidecar = sidecarFile(canonicalUri, documentName)
                reconcileResolutionRegistry()
                val merged = loadAndMergeRecords(sidecar)
                val records = merged.records
                recovered.forEach { (id, candidate) ->
                    val current = records[id]
                    records[id] = if (current == null) {
                        candidate
                    } else {
                        newest(current, candidate)
                    }
                }
                writeRecordsAtomically(sidecar, records.values.toList())
                discardMergedConflicts(sidecar, merged.conflicts)
                RecoveredCommentImportResult(
                    sidecar,
                    recovered.keys.mapNotNull { id ->
                        records[id]?.let { record ->
                            CommentDeliveryRecord(
                                id = id,
                                revision = intValue(record, KEY_REVISION, 1).coerceAtLeast(1),
                                json = record.toString(),
                            )
                        }
                    },
                )
            }
        }
    }

    fun sidecarFile(uri: Uri, documentName: String?): File {
        val baseName = documentName
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
        val rawName = baseName
            ?.substringBeforeLast('.', baseName)
            ?.takeIf { it.isNotBlank() }
            ?: "document-${SourceKey.of(uri).take(16)}"
        val safeStem = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return File(sidecarRoot(), "${safeStem}_comments.jsonl")
    }

    fun resolutionFile(): File = File(sidecarRoot(), RESOLUTION_REGISTRY)

    private fun sidecarRoot(): File {
        return rootOverride ?: defaultSidecarRoot()
    }

    private fun pendingFile(uri: Uri): File {
        return File(File(context.filesDir, PENDING_DIRECTORY), "${SourceKey.of(uri)}.journal")
    }

    private fun readPendingLines(uri: Uri): List<String> = synchronized(pendingLock) {
        val file = pendingFile(uri)
        if (!file.isFile) emptyList() else file.readLines().filter(String::isNotBlank)
    }

    private fun discardPendingPrefix(uri: Uri, applied: List<String>) = synchronized(pendingLock) {
        if (applied.isEmpty()) {
            return@synchronized
        }
        val file = pendingFile(uri)
        val current = if (file.isFile) file.readLines().filter(String::isNotBlank) else emptyList()
        val remainder = if (current.take(applied.size) == applied) {
            current.drop(applied.size)
        } else {
            current.filterNot(applied.toSet()::contains)
        }
        if (remainder.isEmpty()) {
            if (!file.delete() && file.exists()) {
                error("Could not remove applied pending journal ${file.path}")
            }
            file.parentFile?.let(::syncDirectory)
        } else {
            writeTextAtomically(file, remainder.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private fun loadAndMergeRecords(sidecar: File): MergedRecordLoad {
        val canonical = readRecords(sidecar)
        val conflicts = sidecar.parentFile
            ?.listFiles()
            ?.filter { candidate ->
                candidate.isFile &&
                    candidate.name.startsWith(sidecar.nameWithoutExtension) &&
                    candidate.name.contains(".sync-conflict-") &&
                    candidate.extension.equals("jsonl", ignoreCase = true)
            }
            .orEmpty()
            .map { file ->
                ConflictFileSnapshot(file, file.length(), file.lastModified())
            }
        for (conflict in conflicts) {
            for ((id, candidate) in readRecords(conflict.file)) {
                val current = canonical[id]
                if (current == null) {
                    canonical[id] = candidate
                    continue
                }
                val winner = newest(current, candidate)
                val loser = if (winner === current) candidate else current
                if (current != candidate) {
                    archiveConflict(sidecar, id, loser)
                }
                canonical[id] = winner
            }
        }
        return MergedRecordLoad(canonical, conflicts)
    }

    private fun reconcileResolutionRegistry(): Set<String> {
        val registry = resolutionFile()
        val merged = loadAndMergeRecords(registry)
        if (merged.conflicts.isNotEmpty()) {
            writeRecordsAtomically(registry, merged.records.values.toList())
            discardMergedConflicts(registry, merged.conflicts)
        }
        return merged.records.values
            .asSequence()
            .filter {
                it.get(KEY_DELETED)?.asBoolean == true &&
                    (
                        it.get(KEY_SOURCE_ANCHOR_DELETION)?.asBoolean == true ||
                            stringValue(it, KEY_ORIGIN_ID) == ORIGIN_TEX_ANCHOR
                    )
            }
            .map(::recordId)
            .toSet()
    }

    private fun discardMergedConflicts(
        sidecar: File,
        conflicts: List<ConflictFileSnapshot>,
    ) {
        var deletedAny = false
        conflicts.forEach { conflict ->
            val file = conflict.file
            if (!file.isFile) {
                return@forEach
            }
            if (file.length() != conflict.length || file.lastModified() != conflict.lastModified) {
                error("Conflict changed while merging ${file.path}")
            }
            if (file.delete()) {
                deletedAny = true
            } else {
                error("Could not remove merged conflict ${file.path}")
            }
        }
        if (deletedAny) {
            sidecar.parentFile?.let(::syncDirectory)
        }
    }

    private fun readRecords(file: File): LinkedHashMap<String, JsonObject> {
        val records = linkedMapOf<String, JsonObject>()
        if (!file.isFile) {
            return records
        }
        file.useLines { lines ->
            lines.filter(String::isNotBlank).forEach { line ->
                val record = JsonParser.parseString(line).asJsonObject
                upgradeRecord(record)
                val id = recordId(record)
                val existing = records[id]
                records[id] = if (existing == null) record else newest(existing, record)
            }
        }
        return records
    }

    private fun upgradeRecord(record: JsonObject) {
        val previousSchema = intValue(record, KEY_SCHEMA_VERSION, 0)
        record.addProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        if (!record.has(KEY_ID) || record.get(KEY_ID).asString.isBlank()) {
            record.addProperty(KEY_ID, deterministicId(record))
        }
        if (!record.has(KEY_REVISION)) record.addProperty(KEY_REVISION, 1)
        if (!record.has(KEY_UPDATED_AT)) {
            val priorDate = stringValue(record, KEY_MODIFIED)
                .ifBlank { stringValue(record, KEY_CREATED) }
            record.addProperty(
                KEY_UPDATED_AT,
                parseCommentTimestamp(priorDate)?.toString() ?: Instant.now().toString(),
            )
        }
        if (!record.has(KEY_ORIGIN_ID)) record.addProperty(KEY_ORIGIN_ID, "legacy")
        CommentCreationMetadata.upgradeLegacyRecord(record, previousSchema, SCHEMA_VERSION)
    }

    private fun recordFromEmbeddedComment(comment: PDFView.PdfComment): JsonObject {
        val record = JsonObject()
        record.addProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        record.addProperty(KEY_PAGE, comment.pageIndex + 1)
        record.addProperty(
            KEY_SUBTYPE,
            PDFView.PdfComment.subtypeName(comment.subtype),
        )
        val id = comment.name.ifBlank {
            UUID.nameUUIDFromBytes(
                "${comment.pageIndex}|${comment.subtype}|${comment.pdfBounds}|${comment.contents}"
                    .toByteArray(StandardCharsets.UTF_8)
            ).toString()
        }
        record.addProperty(KEY_ID, id)
        CommentCreationMetadata.canonicalPopupIcon(
            PDFView.PdfComment.subtypeName(comment.subtype),
        )?.let { record.addProperty(KEY_NAME, it) }
        comment.pdfBounds?.let { record.add(KEY_RECT, rectToJson(it)) }
        record.add(KEY_COLOR, colorToJson(comment.color))
        record.addProperty(KEY_OPACITY, Color.alpha(comment.color) / 255.0)
        if (PDFView.PdfComment.isTextMarkup(comment.subtype) && comment.pdfBounds != null) {
            record.add(KEY_QUAD_POINTS, quadPointsToJson(comment.pdfRects))
        }
        record.addProperty(KEY_CONTENTS, comment.contents)
        record.addProperty(KEY_AUTHOR, comment.author)
        record.addProperty(KEY_CREATED, comment.creationDate)
        record.addProperty(KEY_MODIFIED, comment.modifiedDate)
        if (comment.inReplyToName.isNotBlank()) {
            record.addProperty(KEY_IN_REPLY_TO, comment.inReplyToName)
        }
        if (comment.anchorStatus.isNotBlank()) {
            record.addProperty(KEY_ANCHOR_STATUS, comment.anchorStatus)
        }
        record.addProperty(KEY_REVISION, 1)
        record.addProperty(
            KEY_UPDATED_AT,
            parseCommentTimestamp(
                comment.modifiedDate.ifBlank { comment.creationDate },
            )?.toString() ?: Instant.now().toString(),
        )
        record.addProperty(KEY_ORIGIN_ID, "embedded-pdf")
        CommentCreationMetadata.infer(record)?.let {
            record.addProperty(KEY_CREATED_IN_APP, it)
        }
        return record
    }

    private fun recordsFromEmbeddedComments(
        comments: List<PDFView.PdfComment>,
    ): LinkedHashMap<String, JsonObject> {
        val physical = comments.map(::recordFromEmbeddedComment)
        val rootIndexes = mutableMapOf<String, MutableList<Int>>()
        val continuations = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        physical.forEachIndexed { physicalIndex, record ->
            val id = recordId(record)
            val fragment = parseFragmentId(id)
            if (fragment == null) {
                rootIndexes.getOrPut(id, ::mutableListOf).add(physicalIndex)
            } else {
                continuations
                    .getOrPut(fragment.logicalId, ::mutableListOf)
                    .add(fragment.index to physicalIndex)
            }
        }

        val grouped = mutableMapOf<Int, JsonObject>()
        val skipped = mutableSetOf<Int>()
        continuations.forEach { (logicalId, candidates) ->
            val roots = rootIndexes[logicalId].orEmpty()
            val orderedCandidates = candidates.sortedBy { it.first }
            if (
                roots.size != 1 ||
                orderedCandidates.map { it.first } !=
                    (1..orderedCandidates.size).toList()
            ) {
                return@forEach
            }
            val rootIndex = roots.single()
            val physicalIndexes = listOf(rootIndex) +
                orderedCandidates.map { it.second }
            val records = physicalIndexes.map(physical::get)
            if (
                !PDFView.PdfComment.isTextMarkup(PDFView.PdfComment.subtypeFromName(stringValue(records.first(), KEY_SUBTYPE))) ||
                records.any {
                    stringValue(it, KEY_SUBTYPE) !=
                        stringValue(records.first(), KEY_SUBTYPE)
                }
            ) {
                return@forEach
            }
            val pages = records.map { intValue(it, KEY_PAGE, 0) }
            if (pages.zipWithNext().any { (left, right) -> right != left + 1 }) {
                return@forEach
            }
            val contents = records
                .map { stringValue(it, KEY_CONTENTS) }
                .filter(String::isNotBlank)
                .distinct()
            if (contents.size > 1) {
                return@forEach
            }

            val logical = records.first().deepCopy()
            logical.addProperty(KEY_ID, logicalId)
            if (stringValue(logical, KEY_CONTENTS).isBlank() && contents.isNotEmpty()) {
                logical.addProperty(KEY_CONTENTS, contents.single())
            }
            logical.add(
                KEY_FRAGMENTS,
                JsonArray().apply {
                    records.forEach { record ->
                        add(fragmentJson(record))
                    }
                },
            )
            grouped[rootIndex] = logical
            skipped += physicalIndexes.drop(1)
        }

        return linkedMapOf<String, JsonObject>().apply {
            physical.forEachIndexed { index, raw ->
                if (index in skipped) {
                    return@forEachIndexed
                }
                val record = grouped[index] ?: raw
                val replyTarget = stringValue(record, KEY_IN_REPLY_TO)
                if (replyTarget.isNotBlank()) {
                    record.addProperty(
                        KEY_IN_REPLY_TO,
                        parseFragmentId(replyTarget)?.logicalId ?: replyTarget,
                    )
                }
                this[recordId(record)] = record
            }
        }
    }

    private fun parseFragmentId(id: String): CommentFragmentId? {
        val markerOffset = id.lastIndexOf(COMMENT_FRAGMENT_MARKER)
        if (markerOffset <= 0) {
            return null
        }
        val index = id.substring(markerOffset + COMMENT_FRAGMENT_MARKER.length)
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        return CommentFragmentId(id.substring(0, markerOffset), index)
    }

    private fun fragmentJson(record: JsonObject): JsonObject = JsonObject().apply {
        addProperty(KEY_PAGE, intValue(record, KEY_PAGE, 0))
        record.get(KEY_RECT)?.let { add(KEY_RECT, it.deepCopy()) }
        record.get(KEY_QUAD_POINTS)?.let { add(KEY_QUAD_POINTS, it.deepCopy()) }
    }

    private fun applyEdit(
        records: LinkedHashMap<String, JsonObject>,
        edit: AnnotationEdit,
        annotationPdfSha256: String = "",
    ) {
        when (edit) {
            is AnnotationEdit.AddPdfComment -> {
                records[edit.name] = newRecord(
                    page = edit.page,
                    subtype = SUBTYPE_TEXT,
                    id = edit.name,
                    rects = listOf(edit.rect),
                    color = edit.color,
                    contents = edit.contents,
                    author = edit.author,
                    created = edit.createdDate,
                    modified = edit.modifiedDate,
                    priority = edit.priority,
                    location = edit.location,
                    annotationPdfSha256 = annotationPdfSha256,
                )
            }
            is AnnotationEdit.AddPdfMarkupComment -> {
                records[edit.name] = newRecord(
                    page = edit.page,
                    subtype = PDFView.PdfComment.subtypeName(edit.subtype),
                    id = edit.name,
                    rects = edit.rects,
                    color = edit.color,
                    contents = edit.contents,
                    author = edit.author,
                    created = edit.createdDate,
                    modified = edit.modifiedDate,
                    selectedText = edit.selectedText,
                    fragments = edit.fragments,
                    priority = edit.priority,
                    location = edit.location,
                    annotationPdfSha256 = annotationPdfSha256,
                )
            }
            is AnnotationEdit.AddPdfReply -> {
                records[edit.name] = newRecord(
                    page = edit.page,
                    subtype = SUBTYPE_TEXT,
                    id = edit.name,
                    rects = listOf(edit.rect),
                    color = DEFAULT_COMMENT_COLOR,
                    contents = edit.contents,
                    author = edit.author,
                    created = edit.createdDate,
                    modified = edit.modifiedDate,
                    inReplyTo = edit.parentName,
                    annotationPdfSha256 = annotationPdfSha256,
                )
            }
            is AnnotationEdit.SetPdfCommentContents -> {
                val record = records[edit.name] ?: JsonObject().also {
                    it.addProperty(KEY_ID, edit.name)
                    it.addProperty(KEY_PAGE, edit.page + 1)
                    it.addProperty(
                        KEY_SUBTYPE,
                        PDFView.PdfComment.subtypeName(edit.subtype)
                    )
                }
                bump(record, edit.modifiedDate)
                record.addProperty(KEY_CONTENTS, edit.contents)
                record.addProperty(KEY_MODIFIED, edit.modifiedDate)
                record.remove(KEY_DELETED)
                record.remove(KEY_DELETED_AT)
                records[edit.name] = record
            }
            is AnnotationEdit.SetPdfCommentMetadata -> {
                val record = records[edit.name] ?: JsonObject().also {
                    it.addProperty(KEY_ID, edit.name)
                    it.addProperty(KEY_PAGE, edit.page + 1)
                    it.addProperty(
                        KEY_SUBTYPE,
                        PDFView.PdfComment.subtypeName(edit.subtype),
                    )
                }
                bump(record, edit.modifiedDate)
                record.addProperty(KEY_PRIORITY, edit.priority.wireValue)
                record.keepOrReplaceCommentLocation(KEY_LOCATION, edit.location, removeWhenAbsent = true)
                edit.color?.let { color ->
                    record.add(KEY_COLOR, colorToJson(color))
                    record.addProperty(KEY_OPACITY, Color.alpha(color) / 255.0)
                }
                edit.rect?.let { rect ->
                    record.add(KEY_RECT, rectToJson(rect))
                }
                record.addProperty(KEY_MODIFIED, edit.modifiedDate)
                record.remove(KEY_DELETED)
                record.remove(KEY_DELETED_AT)
                records[edit.name] = record
            }
            is AnnotationEdit.DeletePdfComment -> {
                val record = records[edit.name] ?: JsonObject().also {
                    it.addProperty(KEY_ID, edit.name)
                    it.addProperty(KEY_PAGE, edit.page + 1)
                    it.addProperty(
                        KEY_SUBTYPE,
                        PDFView.PdfComment.subtypeName(edit.subtype)
                    )
                }
                val deletedAt = Instant.now().toString()
                bump(record, deletedAt)
                record.addProperty(KEY_DELETED, true)
                record.addProperty(KEY_DELETED_AT, deletedAt)
                records[edit.name] = record
            }
            else -> Unit
        }
    }

    private fun newRecord(
        page: Int,
        subtype: String,
        id: String,
        rects: List<RectF>,
        color: Int,
        contents: String,
        author: String,
        created: String,
        modified: String,
        inReplyTo: String = "",
        selectedText: String = "",
        fragments: List<AnnotationFragment> = emptyList(),
        priority: CommentPriority = CommentPriority.NORMAL,
        location: CommentLocation? = null,
        annotationPdfSha256: String = "",
    ): JsonObject {
        val record = JsonObject()
        record.addProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        record.addProperty(KEY_PAGE, page + 1)
        record.addProperty(KEY_SUBTYPE, subtype)
        record.addProperty(KEY_ID, id)
        CommentCreationMetadata.canonicalPopupIcon(subtype)
            ?.let { record.addProperty(KEY_NAME, it) }
        unionRect(rects)?.let { record.add(KEY_RECT, rectToJson(it)) }
        if (PDFView.PdfComment.isTextMarkup(PDFView.PdfComment.subtypeFromName(subtype))) {
            record.add(KEY_QUAD_POINTS, quadPointsToJson(rects))
        }
        record.add(KEY_COLOR, colorToJson(color))
        record.addProperty(
            KEY_FLAGS,
            when {
                inReplyTo.isNotBlank() -> 60
                subtype == SUBTYPE_TEXT -> 28
                else -> 4
            },
        )
        record.addProperty(KEY_OPACITY, Color.alpha(color) / 255.0)
        record.addProperty(KEY_CONTENTS, contents)
        if (selectedText.isNotBlank()) {
            record.addProperty(KEY_SELECTED_TEXT, selectedText)
        }
        if (annotationPdfSha256.matches(SHA256_REGEX)) {
            record.addProperty(KEY_ANNOTATION_PDF_SHA256, annotationPdfSha256)
        }
        record.addProperty(KEY_AUTHOR, author)
        record.addProperty(KEY_CREATED, created)
        record.addProperty(KEY_MODIFIED, modified)
        record.addProperty(KEY_PRIORITY, priority.wireValue)
        location?.let { record.add(KEY_LOCATION, it.toJson()) }
        if (inReplyTo.isNotBlank()) record.addProperty(KEY_IN_REPLY_TO, inReplyTo)
        if (fragments.size > 1) {
            record.add(KEY_FRAGMENTS, JsonArray().apply {
                fragments.forEach { fragment ->
                    add(JsonObject().apply {
                        addProperty(KEY_PAGE, fragment.page + 1)
                        unionRect(fragment.rects)?.let { add(KEY_RECT, rectToJson(it)) }
                        add(KEY_QUAD_POINTS, quadPointsToJson(fragment.rects))
                    })
                }
            })
        }
        record.addProperty(KEY_REVISION, 1)
        record.addProperty(KEY_UPDATED_AT, Instant.now().toString())
        record.addProperty(KEY_ORIGIN_ID, originId)
        record.addProperty(KEY_CREATED_IN_APP, CommentCreationMetadata.THOUGHTS)
        return record
    }

    private fun bump(record: JsonObject, timestamp: String) {
        upgradeRecord(record)
        record.addProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        record.addProperty(KEY_REVISION, intValue(record, KEY_REVISION, 0) + 1)
        record.addProperty(
            KEY_UPDATED_AT,
            parseCommentTimestamp(timestamp)?.toString() ?: Instant.now().toString(),
        )
        record.addProperty(KEY_ORIGIN_ID, originId)
    }

    private data class PendingEdit(
        val edit: AnnotationEdit,
        val annotationPdfSha256: String,
    )

    private fun pendingEdit(line: String): PendingEdit? {
        val edit = AnnotationEdit.fromJsonLine(line) ?: return null
        val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
        return PendingEdit(
            edit,
            json?.get(KEY_ANNOTATION_PDF_SHA256)
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.takeIf { it.matches(SHA256_REGEX) }
                .orEmpty(),
        )
    }

    private fun toStoredComment(record: JsonObject): StoredPdfComment? {
        if (record.get(KEY_DELETED)?.asBoolean == true) {
            return null
        }
        val page = intValue(record, KEY_PAGE, 0) - 1
        if (page < 0) return null
        val subtypeName = stringValue(record, KEY_SUBTYPE)
        val subtype = PDFView.PdfComment.subtypeFromName(subtypeName)
        if (subtype == 0) return null
        val rootFragment = storedFragment(record, subtype) ?: return null
        val parsedFragments = record.get(KEY_FRAGMENTS)
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { value ->
                value
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.let { storedFragment(it, subtype) }
            }
            .orEmpty()
        val fragments = parsedFragments
            .takeIf {
                it.isNotEmpty() &&
                    it.first().pageIndex == page &&
                    it.zipWithNext().all { (left, right) ->
                        right.pageIndex == left.pageIndex + 1
                    } &&
                    (PDFView.PdfComment.isTextMarkup(subtype) || it.size == 1)
            }
            ?: listOf(rootFragment)
        return StoredPdfComment(
            pageIndex = page,
            subtype = subtype,
            id = recordId(record),
            rects = fragments.first().rects,
            color = colorFromJson(
                record.getAsJsonArray(KEY_COLOR),
                doubleValue(record, KEY_OPACITY, 1.0),
            ),
            contents = stringValue(record, KEY_CONTENTS),
            author = stringValue(record, KEY_AUTHOR),
            creationDate = stringValue(record, KEY_CREATED),
            modifiedDate = stringValue(record, KEY_MODIFIED),
            inReplyTo = stringValue(record, KEY_IN_REPLY_TO),
            selectedText = stringValue(record, KEY_SELECTED_TEXT),
            fragments = fragments,
            priority = CommentPriority.fromWireValue(stringValue(record, KEY_PRIORITY)),
            location = record.get(KEY_LOCATION)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let(CommentLocation::fromJson),
            anchorStatus = stringValue(record, KEY_ANCHOR_STATUS),
            chatGptUrl = normalizeChatGptUrl(stringValue(record, KEY_CHATGPT_URL)).orEmpty(),
            annotationPdfSha256 = stringValue(record, KEY_ANNOTATION_PDF_SHA256).trim().lowercase(),
        )
    }

    private fun storedFragment(
        record: JsonObject,
        subtype: Int,
    ): StoredPdfCommentFragment? {
        val page = intValue(record, KEY_PAGE, 0) - 1
        if (page < 0) {
            return null
        }
        val rects = if (PDFView.PdfComment.isTextMarkup(subtype)) {
            rectsFromQuadPoints(
                record.get(KEY_QUAD_POINTS)
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray,
            )
        } else {
            emptyList()
        }.ifEmpty {
            rectFromJson(
                record.get(KEY_RECT)
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray,
            )?.let(::listOf).orEmpty()
        }
        return rects
            .takeIf(List<RectF>::isNotEmpty)
            ?.let { StoredPdfCommentFragment(page, it) }
    }

    private fun newest(first: JsonObject, second: JsonObject): JsonObject {
        val firstRevision = intValue(first, KEY_REVISION, 0)
        val secondRevision = intValue(second, KEY_REVISION, 0)
        if (firstRevision != secondRevision) {
            return if (firstRevision > secondRevision) first else second
        }
        val firstTime = stringValue(first, KEY_UPDATED_AT)
        val secondTime = stringValue(second, KEY_UPDATED_AT)
        if (firstTime != secondTime) {
            val timeComparison = compareTimestamps(firstTime, secondTime)
            if (timeComparison != 0) {
                return if (timeComparison > 0) first else second
            }
        }
        val firstOrigin = stringValue(first, KEY_ORIGIN_ID)
        val secondOrigin = stringValue(second, KEY_ORIGIN_ID)
        return if (firstOrigin >= secondOrigin) first else second
    }

    private fun archiveConflict(sidecar: File, id: String, record: JsonObject) {
        val safeId = id.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val directory = File(File(sidecar.parentFile, ".conflicts"), sidecar.nameWithoutExtension)
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create conflict archive ${directory.path}")
        }
        val output = File(directory, "${System.currentTimeMillis()}-${UUID.randomUUID()}-$safeId.json")
        writeTextAtomically(output, record.toString() + "\n")
    }

    private fun writeRecordsAtomically(file: File, records: List<JsonObject>) {
        val text = records
            .sortedWith(compareBy({ intValue(it, KEY_PAGE, 0) }, { recordId(it) }))
            .joinToString(separator = "\n", postfix = if (records.isEmpty()) "" else "\n") {
                it.toString()
            }
        writeTextAtomically(file, text)
    }

    private fun recordsFingerprint(records: List<JsonObject>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        records
            .sortedWith(compareBy({ intValue(it, KEY_PAGE, 0) }, { recordId(it) }))
            .forEach { record ->
                digest.update(record.toString().toByteArray(StandardCharsets.UTF_8))
                digest.update('\n'.code.toByte())
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun writeTextAtomically(file: File, text: String) {
        val directory = file.parentFile ?: error("Missing parent directory for ${file.path}")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create ${directory.path}")
        }
        val temporary = File.createTempFile(".${file.name}.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            Os.rename(temporary.absolutePath, file.absolutePath)
            syncDirectory(directory)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun recordId(record: JsonObject): String {
        val id = stringValue(record, KEY_ID)
        return id.ifBlank { deterministicId(record) }
    }

    private fun deterministicId(record: JsonObject): String {
        val identity = listOf(
            stringValue(record, KEY_PAGE),
            stringValue(record, KEY_SUBTYPE),
            record.get(KEY_RECT)?.toString().orEmpty(),
            stringValue(record, KEY_AUTHOR),
            stringValue(record, KEY_CREATED),
            stringValue(record, KEY_CONTENTS),
        ).joinToString("|")
        return UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun stringValue(record: JsonObject, key: String): String {
        val value = record.get(key) ?: return ""
        return if (value.isJsonNull) "" else value.asString
    }

    private fun intValue(record: JsonObject, key: String, default: Int): Int {
        return runCatching { record.get(key)?.asInt ?: default }.getOrDefault(default)
    }

    private fun doubleValue(record: JsonObject, key: String, default: Double): Double {
        return runCatching { record.get(key)?.asDouble ?: default }.getOrDefault(default)
    }

    private fun rectToJson(rect: RectF): JsonArray = JsonArray().apply {
        add(rect.left)
        add(rect.top)
        add(rect.right)
        add(rect.bottom)
    }

    private fun rectFromJson(values: JsonArray?): RectF? {
        if (values == null || values.size() < 4) return null
        return RectF(
            values[0].asFloat,
            values[1].asFloat,
            values[2].asFloat,
            values[3].asFloat,
        )
    }

    private fun unionRect(rects: List<RectF>): RectF? {
        if (rects.isEmpty()) return null
        return RectF(
            rects.minOf(RectF::left),
            rects.maxOf(RectF::top),
            rects.maxOf(RectF::right),
            rects.minOf(RectF::bottom),
        )
    }

    private fun quadPointsToJson(rects: List<RectF>): JsonArray = JsonArray().apply {
        rects.forEach { rect ->
            add(rect.left)
            add(rect.top)
            add(rect.right)
            add(rect.top)
            add(rect.left)
            add(rect.bottom)
            add(rect.right)
            add(rect.bottom)
        }
    }

    private fun rectsFromQuadPoints(values: JsonArray?): List<RectF> {
        if (values == null || values.size() < 8) return emptyList()
        val result = mutableListOf<RectF>()
        var index = 0
        while (index + 7 < values.size()) {
            val xs = listOf(values[index], values[index + 2], values[index + 4], values[index + 6])
                .map { it.asFloat }
            val ys = listOf(values[index + 1], values[index + 3], values[index + 5], values[index + 7])
                .map { it.asFloat }
            result += RectF(xs.minOrNull()!!, ys.maxOrNull()!!, xs.maxOrNull()!!, ys.minOrNull()!!)
            index += 8
        }
        return result
    }

    private fun colorToJson(color: Int): JsonArray = JsonArray().apply {
        add(Color.red(color) / 255.0)
        add(Color.green(color) / 255.0)
        add(Color.blue(color) / 255.0)
    }

    private fun colorFromJson(values: JsonArray?, opacity: Double): Int {
        fun channel(index: Int): Int {
            val value = if (values != null && index < values.size()) {
                values[index].asDouble
            } else {
                when (index) {
                    0 -> Color.red(DEFAULT_COMMENT_COLOR).toDouble()
                    1 -> Color.green(DEFAULT_COMMENT_COLOR).toDouble()
                    else -> Color.blue(DEFAULT_COMMENT_COLOR).toDouble()
                }
            }
            return (if (value <= 1.0) value * 255.0 else value).toInt().coerceIn(0, 255)
        }
        val alpha = (opacity.coerceIn(0.0, 1.0) * 255.0).toInt()
        return Color.argb(alpha, channel(0), channel(1), channel(2))
    }

    private fun compareTimestamps(first: String, second: String): Int {
        val firstInstant = parseTimestamp(first)
        val secondInstant = parseTimestamp(second)
        return when {
            firstInstant != null && secondInstant != null -> firstInstant.compareTo(secondInstant)
            firstInstant != null -> 1
            secondInstant != null -> -1
            else -> first.compareTo(second)
        }
    }

    private fun parseTimestamp(value: String): Instant? {
        return parseCommentTimestamp(value)
    }

    private fun syncDirectory(directory: File) {
        val descriptor = try {
            Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
        } catch (error: ErrnoException) {
            throw IllegalStateException("Could not open ${directory.path} for durability sync", error)
        }
        try {
            Os.fsync(descriptor)
        } catch (error: ErrnoException) {
            throw IllegalStateException("Could not durability-sync ${directory.path}", error)
        } finally {
            Os.close(descriptor)
        }
    }

    companion object {
        private val flushMutex = Mutex()
        private val pendingLock = Any()
        private const val TAG = "CommentSidecarStore"
        private const val SIDECAR_DIRECTORY = "docs-clean-comments"
        private const val RESOLUTION_REGISTRY = "review_resolutions.jsonl"
        private const val PENDING_DIRECTORY = "comment-sidecar-pending"
        private const val PREFERENCES = "comment-sidecar"
        private const val KEY_ORIGIN_PREFERENCE = "origin-id"
        private const val SCHEMA_VERSION = 5

        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_PAGE = "page"
        private const val KEY_SUBTYPE = "subtype"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_RECT = "rect"
        private const val KEY_QUAD_POINTS = "quad_points"
        private const val KEY_FRAGMENTS = "fragments"
        private const val KEY_COLOR = "color"
        private const val KEY_FLAGS = "flags"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_CONTENTS = "contents"
        private const val KEY_SELECTED_TEXT = "selected_text"
        private const val KEY_ANNOTATION_PDF_SHA256 = "annotation_pdf_sha256"
        private const val KEY_ANCHOR_STATUS = "anchor_status"
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private const val KEY_AUTHOR = "author"
        private const val KEY_CREATED = "created"
        private const val KEY_MODIFIED = "modified"
        private const val KEY_IN_REPLY_TO = "in_reply_to"
        private const val KEY_PRIORITY = "priority"
        private const val KEY_LOCATION = "location"
        private const val KEY_CHATGPT_URL = "chatgpt_url"
        private const val KEY_REVISION = "revision"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_ORIGIN_ID = "origin_id"
        private const val KEY_CREATED_IN_APP = CommentCreationMetadata.CREATED_IN_APP_KEY
        private const val KEY_DELETED = "deleted"
        private const val KEY_DELETED_AT = "deleted_at"
        private const val KEY_SOURCE_ANCHOR_DELETION = "source_anchor_deletion"
        private const val ORIGIN_TEX_ANCHOR = "mac-tex-anchor"
        private const val SUBTYPE_TEXT = "Text"
        private const val SUBTYPE_HIGHLIGHT = "Highlight"
        private const val COMMENT_FRAGMENT_MARKER = "#oe-fragment-"
        private val DEFAULT_COMMENT_COLOR = CommentCreationMetadata.DEFAULT_POPUP_COLOR

        fun defaultSidecarRoot(): File = ThoughtsLocalStore.comments()
    }
}
