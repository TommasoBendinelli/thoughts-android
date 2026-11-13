// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.os.FileObserver
import android.util.Log
import com.gitlab.mudlej.MjPdfReader.core.io.parseCommentTimestamp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.onEach

data class CommentDocumentMetadata(
    val openThreads: Int = 0,
    val lastCommentAddedMillis: Long = 0,
    val searchableText: String = "",
    val liveComments: Int = 0,
    val anchoredComments: Int = 0,
    val unanchoredComments: Int = 0,
)

data class OverviewComment(
    val documentStem: String,
    val id: String,
    val parentId: String,
    val contents: String,
    val selectedText: String,
    val author: String,
    val sourceFile: String,
    val sourceLine: Int,
    val anchorStatus: String,
    val pageIndex: Int,
    val subtype: String,
    val created: String,
    val modified: String,
    val activityMillis: Long,
    val priority: CommentPriority = CommentPriority.NORMAL,
    val location: CommentLocation? = null,
    val color: Int = CommentCreationMetadata.DEFAULT_POPUP_COLOR,
    val sourceContext: String = "",
    val anchorConfidence: String = "",
    val pdfRect: List<Float> = emptyList(),
    val chatGptUrl: String = "",
    val logicalPayloadJson: String = "",
    val logicalLocations: List<LogicalCommentPdfLocation> = emptyList(),
    val reviewCorrespondence: ReviewCorrespondence? = null,
    val noteDetails: NoteDetails? = null,
) {
    val needsAction: Boolean get() = runCatching {
        JsonParser.parseString(logicalPayloadJson).asJsonObject.get("needs_action")?.asBoolean == true
    }.getOrDefault(false)
    val isReply: Boolean get() = parentId.isNotBlank()
    val isNote: Boolean get() = subtype == "Note"

    val searchText: String
        get() = listOf(
            contents,
            selectedText,
            documentStem,
            author,
            sourceFile,
            anchorStatus,
            priority.wireValue,
            location?.label.orEmpty(),
            noteDetails?.projectName.orEmpty(),
            noteDetails?.checkWhere.orEmpty(),
            noteDetails?.calendarDate.orEmpty(),
            noteDetails?.calendarTime.orEmpty(),
            logicalLocations.joinToString("\n") { "${it.pdfFilename} ${it.page}" },
        ).joinToString("\n")
}

data class OverviewCommentDocument(
    val stem: String,
    val comments: List<OverviewComment>,
)

data class CommentCatalogueSnapshot(
    val metadataByStem: Map<String, CommentDocumentMetadata> = emptyMap(),
    val overviewDocuments: List<OverviewCommentDocument> = emptyList(),
) {
    /** Lightweight records Home shows until the full source snapshot loads; never persisted. */
    @Transient internal var logicalComments: List<com.gitlab.mudlej.MjPdfReader.data.source.OfflineLogicalComment>? = null

    fun metadataFor(pdfStem: String): CommentDocumentMetadata {
        return metadataByStem[normalizePdfStem(pdfStem).lowercase()]
            ?: CommentDocumentMetadata()
    }
}

/**
 * Cached, read-only catalogue of the logical comment store when a context is supplied.
 *
 * Replies remain searchable but do not increase [CommentDocumentMetadata.openThreads]. Public
 * source-anchor resolutions suppress a comment identity across every document sidecar.
 */
class CommentCatalogue(
    private val root: File = CommentSidecarStore.defaultSidecarRoot(),
    private val persistentCacheFile: File? = null,
    private val context: android.content.Context? = null,
) {
    private data class CacheEntry(
        val signature: String,
        val metadata: CommentDocumentMetadata,
    )

    private data class PersistentSnapshot(
        val schemaVersion: Int = 0,
        val sourceSignature: String = "",
        val snapshot: CommentCatalogueSnapshot = CommentCatalogueSnapshot(),
    )

    private val cache = mutableMapOf<String, CacheEntry>()
    private val gson = Gson()
    private var indexedFiles: List<File>? = null
    private var catalogueSnapshot: CommentCatalogueSnapshot? = null
    private var catalogueSignature = ""
    private val logicalCache = context?.let { app ->
        CommentSnapshotCache { LogicalCommentStore(app).catalogue() }
    }

    /** Explicit asynchronous refresh; synchronous accessors only read the logical cache. */
    suspend fun loadSnapshot(usePersistentCacheImmediately: Boolean = false): CommentCatalogueSnapshot {
        return logicalCache?.load() ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            snapshot(usePersistentCacheImmediately)
        }
    }

    fun changes(emitInitialState: Boolean = true) =
        LogicalCommentStore(requireNotNull(context)).changes(emitInitialState).onEach { invalidate() }

    /**
     * Parses every catalogue input once and derives both home-screen projections from that pass.
     * The snapshot remains valid until the directory observer reports a relevant change.
     */
    @Synchronized
    fun snapshot(usePersistentCacheImmediately: Boolean = false): CommentCatalogueSnapshot {
        logicalCache?.let { return it.current() }
        val startedAt = System.nanoTime()
        if (usePersistentCacheImmediately) {
            catalogueSnapshot?.let { return it }
            readPersistentSnapshot()?.let { persisted ->
                catalogueSnapshot = persisted.snapshot
                catalogueSignature = persisted.sourceSignature
                logTiming(
                    "Loaded immediate comment snapshot: " +
                        "${persisted.snapshot.overviewDocuments.size} documents in " +
                        "${elapsedMillis(startedAt)} ms"
                )
                return persisted.snapshot
            }
        }
        val sourceSignature = sourceSignature()
        catalogueSnapshot?.takeIf { catalogueSignature == sourceSignature }?.let { return it }
        readPersistentSnapshot()?.takeIf {
            it.sourceSignature == sourceSignature
        }?.let { persisted ->
            catalogueSnapshot = persisted.snapshot
            catalogueSignature = sourceSignature
            logTiming(
                "Loaded cached comment snapshot: " +
                    "${persisted.snapshot.overviewDocuments.size} documents in " +
                    "${elapsedMillis(startedAt)} ms"
            )
            return persisted.snapshot
        }
        val resolved = resolvedCommentIds()
        val metadata = linkedMapOf<String, CommentDocumentMetadata>()
        val documents = rootFiles()
            .asSequence()
            .mapNotNull(::sidecarStem)
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .mapNotNull { stem ->
                val visible = visibleRecords(stem, resolved)
                metadata[stem.lowercase()] = metadataOf(visible)
                val comments = visible
                    .asSequence()
                    .mapNotNull { record -> overviewComment(stem, record) }
                    .sortedWith(
                        compareByDescending<OverviewComment> { it.activityMillis }
                            .thenBy { it.pageIndex }
                            .thenBy { it.id }
                    )
                    .toList()
                comments.takeIf(List<OverviewComment>::isNotEmpty)?.let {
                    OverviewCommentDocument(stem, it)
                }
            }
            .toList()
        return CommentCatalogueSnapshot(metadata, documents).also {
            catalogueSnapshot = it
            catalogueSignature = sourceSignature
            writePersistentSnapshot(sourceSignature, it)
            logTiming(
                "Built comment snapshot: ${documents.size} documents in " +
                    "${elapsedMillis(startedAt)} ms"
            )
        }
    }

    @Synchronized
    fun metadataFor(pdfStem: String): CommentDocumentMetadata {
        if (context != null) return snapshot().metadataFor(pdfStem)
        val safeStem = normalizePdfStem(pdfStem)
        if (safeStem.isBlank()) return CommentDocumentMetadata()
        catalogueSnapshot?.let { return it.metadataFor(safeStem) }
        val sidecar = File(root, "${safeStem}_comments.jsonl")
        val registry = File(root, RESOLUTION_REGISTRY)
        val inputs = conflictFiles(sidecar) + conflictFiles(registry)
        val signature = inputs
            .sortedBy(File::getName)
            .joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        cache[safeStem]?.takeIf { it.signature == signature }?.let { return it.metadata }

        val resolved = resolvedCommentIds()
        val visible = mergedRecords(sidecar).values.filter {
            !booleanValue(it, KEY_DELETED) && recordId(it) !in resolved
        }
        val metadata = metadataOf(visible)
        cache[safeStem] = CacheEntry(signature, metadata)
        return metadata
    }

    fun overviewDocuments(): List<OverviewCommentDocument> {
        return snapshot().overviewDocuments
    }

    @Synchronized
    fun commentsFor(pdfStem: String): List<OverviewComment> {
        if (context != null) return snapshot().overviewDocuments.firstOrNull { it.stem.equals(normalizePdfStem(pdfStem), true) }?.comments.orEmpty()
        val stem = normalizePdfStem(pdfStem)
        if (stem.isBlank()) return emptyList()
        val resolved = resolvedCommentIds()
        return visibleRecords(stem, resolved)
            .mapNotNull { record -> overviewComment(stem, record) }
            .sortedWith(
                compareBy<OverviewComment> { it.pageIndex }
                    .thenBy { it.activityMillis }
                    .thenBy { it.id }
            )
    }

    @Synchronized
    fun invalidate() {
        logicalCache?.invalidate()
        cache.clear()
        indexedFiles = null
        catalogueSnapshot = null
        catalogueSignature = ""
    }

    @Suppress("DEPRECATION")
    fun observe(onChanged: () -> Unit): FileObserver? {
        if (!root.exists() && !root.mkdirs()) return null
        val mask = FileObserver.CLOSE_WRITE or
            FileObserver.MOVED_TO or
            FileObserver.CREATE or
            FileObserver.DELETE
        return object : FileObserver(root.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                val name = path?.substringAfterLast('/') ?: return
                if (
                    name == RESOLUTION_REGISTRY ||
                    name.endsWith("_comments.jsonl") ||
                    name.contains(".sync-conflict-")
                ) {
                    invalidate()
                    onChanged()
                }
            }
        }.also(FileObserver::startWatching)
    }

    private fun conflictFiles(canonical: File): List<File> {
        val files = rootFiles()
        val conflicts = files
            .filter {
                it.name.startsWith(canonical.nameWithoutExtension) &&
                    it.name.contains(".sync-conflict-") &&
                    it.extension.equals("jsonl", ignoreCase = true)
            }
        val canonicalFile = files.firstOrNull { it.name == canonical.name }
        return listOfNotNull(canonicalFile) + conflicts
    }

    private fun rootFiles(): List<File> {
        indexedFiles?.let { return it }
        return root.listFiles()
            ?.filter { it.extension.equals("jsonl", ignoreCase = true) }
            .orEmpty()
            .also { indexedFiles = it }
    }

    private fun sourceSignature(): String {
        return rootFiles()
            .asSequence()
            .filter(::isCatalogueInput)
            .sortedBy(File::getName)
            .joinToString("|") { file ->
                "${file.name}:${file.length()}:${file.lastModified()}"
            }
    }

    private fun isCatalogueInput(file: File): Boolean {
        return sidecarStem(file) != null ||
            file.name == RESOLUTION_REGISTRY ||
            (
                file.name.startsWith(File(RESOLUTION_REGISTRY).nameWithoutExtension) &&
                    file.name.contains(".sync-conflict-")
                )
    }

    private fun readPersistentSnapshot(): PersistentSnapshot? {
        val file = persistentCacheFile?.takeIf(File::isFile) ?: return null
        return runCatching {
            gson.fromJson(file.readText(), PersistentSnapshot::class.java)
        }.getOrNull()?.takeIf {
            it.schemaVersion == CACHE_SCHEMA_VERSION
        }
    }

    private fun writePersistentSnapshot(
        sourceSignature: String,
        snapshot: CommentCatalogueSnapshot,
    ) {
        val destination = persistentCacheFile ?: return
        runCatching {
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.writeText(
                gson.toJson(PersistentSnapshot(CACHE_SCHEMA_VERSION, sourceSignature, snapshot))
            )
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        }.onFailure { error ->
            runCatching { Log.w(TAG, "Unable to persist comment snapshot", error) }
        }
    }

    private fun sidecarStem(file: File): String? {
        val match = SIDECAR_NAME.matchEntire(file.name) ?: return null
        return match.groupValues[1].takeIf(String::isNotBlank)
    }

    private fun resolvedCommentIds(): Set<String> {
        return mergedRecords(File(root, RESOLUTION_REGISTRY)).values
            .asSequence()
            .filter {
                booleanValue(it, KEY_DELETED) &&
                    (booleanValue(it, KEY_SOURCE_ANCHOR_DELETION) ||
                        stringValue(it, KEY_ORIGIN_ID) == ORIGIN_TEX_ANCHOR)
            }
            .map(::recordId)
            .toSet()
    }

    private fun visibleRecords(stem: String, resolved: Set<String>): List<JsonObject> {
        return mergedRecords(File(root, "${stem}_comments.jsonl")).values.filter {
            !booleanValue(it, KEY_DELETED) && recordId(it) !in resolved
        }
    }

    private fun metadataOf(visible: List<JsonObject>): CommentDocumentMetadata {
        return CommentDocumentMetadata(
            openThreads = visible.count { stringValue(it, KEY_IN_REPLY_TO).isBlank() },
            lastCommentAddedMillis = visible.maxOfOrNull(::recordCreatedMillis) ?: 0,
            searchableText = visible.joinToString(" ") { record ->
                listOf(
                    stringValue(record, KEY_CONTENTS),
                    stringValue(record, KEY_AUTHOR),
                    stringValue(record, "selected_text"),
                    stringValue(record, "tex_file"),
                    stringValue(record, "source_context"),
                ).joinToString(" ")
            }.trim(),
            liveComments = visible.size,
            anchoredComments = visible.count {
                stringValue(it, KEY_ANCHOR_STATUS) == ANCHOR_STATUS_ANCHORED
            },
            unanchoredComments = visible.count {
                stringValue(it, KEY_ANCHOR_STATUS) != ANCHOR_STATUS_ANCHORED
            },
        )
    }

    private fun overviewComment(stem: String, record: JsonObject): OverviewComment? {
        val id = recordId(record)
        val pageIndex = intValue(record, KEY_PAGE) - 1
        if (id.isBlank() || pageIndex < 0) return null
        return OverviewComment(
            documentStem = stem,
            id = id,
            parentId = stringValue(record, KEY_IN_REPLY_TO),
            contents = stringValue(record, KEY_CONTENTS),
            selectedText = stringValue(record, "selected_text"),
            author = stringValue(record, KEY_AUTHOR),
            sourceFile = stringValue(record, "tex_file"),
            sourceLine = intValue(record, "tex_line"),
            sourceContext = stringValue(record, "source_context"),
            anchorStatus = stringValue(record, KEY_ANCHOR_STATUS),
            anchorConfidence = stringValue(record, "anchor_confidence"),
            pageIndex = pageIndex,
            subtype = stringValue(record, "subtype"),
            pdfRect = floatList(record, "rect"),
            created = stringValue(record, "created"),
            modified = stringValue(record, "modified"),
            activityMillis = recordTimestampMillis(record),
            priority = CommentPriority.fromWireValue(stringValue(record, KEY_PRIORITY)),
            location = record.get(KEY_LOCATION)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let(CommentLocation::fromJson),
            color = commentColor(record),
            chatGptUrl = normalizeChatGptUrl(stringValue(record, KEY_CHATGPT_URL)).orEmpty(),
        )
    }

    private fun commentColor(record: JsonObject): Int {
        val values = record.get(KEY_COLOR)?.takeIf { it.isJsonArray }?.asJsonArray
        val opacity = runCatching { record.get(KEY_OPACITY)?.asDouble ?: 1.0 }
            .getOrDefault(1.0)
            .coerceIn(0.0, 1.0)
        fun channel(index: Int): Int {
            val fallback = when (index) {
                0 -> CommentCreationMetadata.DEFAULT_POPUP_COLOR ushr 16 and 0xFF
                1 -> CommentCreationMetadata.DEFAULT_POPUP_COLOR ushr 8 and 0xFF
                else -> CommentCreationMetadata.DEFAULT_POPUP_COLOR and 0xFF
            }
            val value = runCatching { values?.get(index)?.asDouble }.getOrNull()
                ?: return fallback
            return (if (value <= 1.0) value * 255.0 else value).toInt().coerceIn(0, 255)
        }
        return ((opacity * 255.0).toInt() shl 24) or
            (channel(0) shl 16) or
            (channel(1) shl 8) or
            channel(2)
    }

    private fun mergedRecords(canonical: File): LinkedHashMap<String, JsonObject> {
        val records = linkedMapOf<String, JsonObject>()
        conflictFiles(canonical).forEach { file ->
            runCatching {
                file.useLines { lines ->
                    lines.filter(String::isNotBlank).forEach { line ->
                        val candidate = JsonParser.parseString(line).asJsonObject
                        val id = recordId(candidate)
                        val current = records[id]
                        records[id] = if (current == null) candidate else newest(current, candidate)
                    }
                }
            }
        }
        return records
    }

    private fun newest(first: JsonObject, second: JsonObject): JsonObject {
        val firstRevision = intValue(first, KEY_REVISION)
        val secondRevision = intValue(second, KEY_REVISION)
        if (firstRevision != secondRevision) {
            return if (firstRevision > secondRevision) first else second
        }
        val firstTime = recordTimestampMillis(first)
        val secondTime = recordTimestampMillis(second)
        if (firstTime != secondTime) {
            return if (firstTime > secondTime) first else second
        }
        return if (stringValue(first, KEY_ORIGIN_ID) >= stringValue(second, KEY_ORIGIN_ID)) {
            first
        } else {
            second
        }
    }

    private fun recordId(record: JsonObject): String {
        return stringValue(record, KEY_ID)
            .ifBlank { stringValue(record, "review_key").removePrefix("id:") }
    }

    private fun recordTimestampMillis(record: JsonObject): Long {
        for (key in listOf(KEY_UPDATED_AT, "deleted_at", "modified", "created")) {
            parseTimestamp(stringValue(record, key))?.let { return it.toEpochMilli() }
        }
        return 0
    }

    private fun recordCreatedMillis(record: JsonObject): Long {
        for (key in listOf("created", "modified", KEY_UPDATED_AT)) {
            parseTimestamp(stringValue(record, key))?.let { return it.toEpochMilli() }
        }
        return 0
    }

    private fun parseTimestamp(value: String): Instant? {
        return parseCommentTimestamp(value)
    }

    private fun stringValue(record: JsonObject, key: String): String {
        val value = record.get(key) ?: return ""
        return if (value.isJsonNull) "" else runCatching { value.asString }.getOrDefault("")
    }

    private fun intValue(record: JsonObject, key: String): Int {
        return runCatching { record.get(key)?.asInt ?: 0 }.getOrDefault(0)
    }

    private fun floatList(record: JsonObject, key: String): List<Float> {
        return runCatching {
            record.get(key)
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.map { it.asFloat }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun booleanValue(record: JsonObject, key: String): Boolean {
        return runCatching { record.get(key)?.asBoolean == true }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "CommentCatalogue"
        private const val CACHE_SCHEMA_VERSION = 6
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val RESOLUTION_REGISTRY = "review_resolutions.jsonl"
        private const val KEY_ID = "id"
        private const val KEY_REVISION = "revision"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_ORIGIN_ID = "origin_id"
        private const val KEY_DELETED = "deleted"
        private const val KEY_SOURCE_ANCHOR_DELETION = "source_anchor_deletion"
        private const val KEY_IN_REPLY_TO = "in_reply_to"
        private const val KEY_CONTENTS = "contents"
        private const val KEY_PAGE = "page"
        private const val KEY_AUTHOR = "author"
        private const val KEY_ANCHOR_STATUS = "anchor_status"
        private const val KEY_PRIORITY = "priority"
        private const val KEY_LOCATION = "location"
        private const val KEY_COLOR = "color"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_CHATGPT_URL = "chatgpt_url"
        private const val ANCHOR_STATUS_ANCHORED = "anchored"
        private const val ORIGIN_TEX_ANCHOR = "mac-tex-anchor"
        private val SIDECAR_NAME = Regex(
            "^(.+)_comments(?:\\.sync-conflict-[^.]+)?\\.jsonl$",
            RegexOption.IGNORE_CASE,
        )

        private fun elapsedMillis(startedAt: Long): Long {
            return (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        }

        private fun logTiming(message: String) {
            runCatching { Log.d(TAG, message) }
        }
    }
}

private fun normalizePdfStem(pdfStem: String): String {
    val name = pdfStem.substringAfterLast('/')
    return if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name
}
