// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.content.Context
import android.os.FileObserver
import android.util.Log
import android.util.AtomicFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** UI reads never reconcile sources. One process-wide worker owns the saved projection. */
internal class SourceCommentSnapshot private constructor(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var generation = 0L
    private var invalidateAll = true
    private val dirtySources = mutableSetOf<String>()
    private val sourcePaths = mutableMapOf<String, String>()
    private var inputs = linkedMapOf<String, JsonObject>()
    private var entries = JsonObject()
    private val observers = mutableMapOf<String, FileObserver>()
    private val annotationIndex by lazy { OfflineSourceBundleStore(context).annotationIndex }
    private val readState = com.gitlab.mudlej.MjPdfReader.data.AppDatabase.getInstance(context).commentReadStateDao()
    private val loadMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var loaded = false
    private val file = AtomicFile(File(context.filesDir, "source-comment-snapshot.json"))
    /** The local-only "Comment missing" Issues list of the last complete scan; never queued or synced. */
    @Volatile private var missingMarkers: List<MissingCommentMarker> = emptyList()

    companion object {
        @Volatile private var instance: SourceCommentSnapshot? = null
        fun get(context: Context): SourceCommentSnapshot = instance ?: synchronized(this) {
            instance ?: SourceCommentSnapshot(context).also { instance = it }
        }
        fun invalidate() { instance?.invalidateSources() }
        /** The current "Comment missing" rows, or none before the first complete scan of this process. */
        fun missingCommentMarkers(): List<MissingCommentMarker> = instance?.missingCommentMarkers().orEmpty()
        /** Takes resolved rows off Issues at once and checks the sources again. */
        fun resolveMissing(rowIds: Set<String>) { instance?.resolveMissingRows(rowIds) }
    }

    init {
        scope.launch {
            requests.trySend(Unit)
            ensureLoaded()
            com.gitlab.mudlej.MjPdfReader.data.StartupReadiness.awaitMaintenanceWindow()
            val resolver = LocalSourceComments(context, cacheSourceProjection = true)
            for (ignored in requests) {
                delay(150)
                while (requests.tryReceive().isSuccess) { /* coalesce notifications */ }
                val (version, records, dirty) = synchronized(lock) {
                    val affected = if (invalidateAll) null else dirtySources.toSet()
                    invalidateAll = false; dirtySources.clear()
                    Triple(generation, inputs.mapValues { it.value.deepCopy() }, affected)
                }
                resolver.invalidate(dirty)
                val store = OfflineSourceBundleStore(context)
                store.autoMergePendingSources()
                // Install observers before reading sources, including parent directories for atomic saves.
                val paths = runCatching { store.librarySourceMetadata()?.trackedSourcePaths().orEmpty() }.getOrDefault(emptyList())
                val directories = linkedSetOf<File>()
                for (path in paths) {
                    val working = store.sourceWorkingFile(path) ?: continue
                    synchronized(lock) { sourcePaths[working.absolutePath] = path }
                    val root = working.absolutePath.removeSuffix(path).trimEnd('/')
                    var parent = working.parentFile
                    while (parent != null && parent.absolutePath.length >= root.length) {
                        if (parent.isDirectory) directories.add(parent)
                        if (parent.absolutePath == root) break
                        parent = parent.parentFile
                    }
                }
                val wanted = directories.map { it.absolutePath }.toSet()
                for (obsolete in observers.keys.filter { it !in wanted }) observers.remove(obsolete)?.stopWatching()
                for (directory in directories) {
                    if (directory.absolutePath !in observers) {
                        @Suppress("DEPRECATION")
                        val observer = object : FileObserver(directory.absolutePath, CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE or CREATE or DELETE_SELF or MOVE_SELF) {
                            override fun onEvent(event: Int, path: String?) {
                                val source = synchronized(lock) { path?.let { sourcePaths[File(directory, it).absolutePath] } }
                                invalidateSources(source)
                            }
                        }
                        observers[directory.absolutePath] = observer
                        observer.startWatching()
                    }
                }
                val locations = resolver.annotationIndex(paths)
                val result = JsonObject()
                val success = runCatching {
                    for ((id, record) in records) result.add(id, JsonObject().apply {
                        add("input", record)
                        val sourcePath = record.getAsJsonObject("scope")?.get("source_file")?.asString
                        val removed = CommentAnchorState.isAnchorRemovedPayload(record)
                        val tracked = sourcePath.isNullOrBlank() || sourcePath in paths || removed
                        // A marker move or duplicate can affect other annotations in the same project.
                        val projectChanged = sourcePath.isNullOrBlank() || dirty == null ||
                            dirty.any { SourceOwner.of(it) == SourceOwner.of(sourcePath) }
                        val unchanged = if (!projectChanged) synchronized(lock) {
                            entries.getAsJsonObject(id)?.takeIf { it.get("input") == record }
                                ?.getAsJsonObject("output")?.deepCopy()
                        } else null
                        val output = unchanged ?: if (!tracked) record.deepCopy() else runCatching { resolver.apply(record) }.getOrElse {
                            // A failed check keeps "Anchor removed" with its last location.
                            if (removed) return@getOrElse record.deepCopy()
                            val last = synchronized(lock) { entries.getAsJsonObject(id)?.takeIf { it.get("input") == record }?.getAsJsonObject("output")?.deepCopy() } ?: record.deepCopy()
                            val oldScope = last.getAsJsonObject("scope")?.deepCopy() ?: JsonObject()
                            oldScope.addProperty("state", "source_unavailable")
                            oldScope.addProperty("reason", "source_project_incomplete")
                            oldScope.remove("source_line"); oldScope.remove("source_end_line"); last.remove("source_line")
                            last.add("scope", oldScope); last
                        }
                        val scope = output.getAsJsonObject("scope")
                        if (tracked && scope?.get("state")?.asString == "source_linked") {
                            val id = scope.get("anchor_id")?.asString ?: output.get("comment_id").asString
                            val project = SourceOwner.of(scope.get("source_file")?.asString.orEmpty()).orEmpty()
                            val location = SourceAnnotationIndex.locate(locations, SourceAnnotationMarkup.commentType(output.get("subtype")?.asString.orEmpty()), id, project)
                            if (location.get("state")?.asString == "linked") {
                                output.remove("source_line")
                                scope.add("source_file", location.get("source_file"))
                                scope.add("source_line", location.get("start_line"))
                                scope.add("source_end_line", location.get("end_line"))
                            } else if (output.get("deleted")?.asBoolean != true) {
                                if (location.get("state")?.asString == "missing" && SourceAnnotationMarkup.mentionsDetectable(id)) {
                                    // A complete, valid project scan without the raw ID: keep the last location.
                                    output.add("scope", CommentAnchorState.anchorRemovedScope(scope, id, output))
                                } else {
                                    scope.addProperty("state", if (location.get("state")?.asString == "unavailable") "source_unavailable" else "needs_attention")
                                    scope.remove("source_line"); scope.remove("source_end_line"); output.remove("source_line")
                                    scope.addProperty("reason", "source_marker_unavailable")
                                }
                            }
                        }
                        add("output", output)
                    })
                }.isSuccess
                if (!success) continue // A failed read cannot erase the last good state.
                if (!resolver.annotationIndexStillCurrent(locations)) { invalidateSources(); continue }
                // "Comment missing": markers whose comment no longer exists. A failed check keeps the last list.
                val missing = runCatching {
                    val bundle = store.librarySourceMetadata()
                    if (bundle == null) emptyList() else CommentMissingState.detect(
                        com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository(context).commentMissingStore(),
                        locations, CommentMissingState.targetsByPath(bundle),
                    )
                }.onFailure { Log.w("ThoughtsSource", "Comment-missing check will retry on the next scan", it) }
                    .getOrNull() ?: missingMarkers
                val changed = synchronized(lock) {
                    if (version != generation) { requests.trySend(Unit); false }
                    else {
                        val locationsChanged = annotationIndex.install(locations)
                        val missingChanged = missing != missingMarkers
                        missingMarkers = missing
                        val changed = entries != result || locationsChanged || missingChanged
                        // Persist only changed rows before publishing the generation to readers.
                        val updates = result.entrySet().filter { (id, row) -> entries.get(id) != row }.map { (id, row) ->
                            val value = row.asJsonObject
                            com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSourceState(
                                id, value.get("input").toString(), value.get("output").toString(),
                            )
                        }
                        runBlocking(Dispatchers.IO) { if (updates.isNotEmpty()) readState.putSourceStates(updates) }
                        entries = result; changed
                    }
                }
                if (changed) {
                    // Source-derived card anchors are saved before notifying the UI or Sync.
                    runCatching { com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore(context).reconcileSourceAnchors() }
                        .onFailure { Log.w("ThoughtsSource", "Review source reconciliation will retry on the next scan", it) }
                    SourceCommentChanges.changes.tryEmit(Unit)
                    com.gitlab.mudlej.MjPdfReader.data.sync.PendingChangesSignal.notifyChanged()
                    // The sources were just rechecked: finish review-card marker removals left pending.
                    com.gitlab.mudlej.MjPdfReader.data.review.PendingMarkerRemovalRetry.run(context)
                }
            }
        }
    }

    fun missingCommentMarkers(): List<MissingCommentMarker> = missingMarkers

    private fun resolveMissingRows(rowIds: Set<String>) {
        synchronized(lock) { missingMarkers = missingMarkers.filter { it.rowId !in rowIds } }
        // A scan already running cannot publish its older list: the generation changes here.
        invalidateSources()
    }

    private fun invalidateSources(path: String? = null) = synchronized(lock) {
        if (path == null) invalidateAll = true else dirtySources.add(path)
        generation++
        annotationIndex.invalidate()
        requests.trySend(Unit)
        Unit
    }

    private suspend fun ensureLoaded() {
        loadMutex.lock()
        try {
            if (loaded) return
            val migration = "source-comment-snapshot-v1"
            if (readState.checkpoint(migration) == null) {
                val saved = if (file.baseFile.isFile) file.openRead().use {
                    JsonParser.parseReader(it.reader()).asJsonObject
                } else JsonObject()
                val rows = saved.entrySet().map { (id, value) ->
                    val row = value.asJsonObject
                    com.gitlab.mudlej.MjPdfReader.data.annotation.CommentSourceState(
                        id, row.get("input").toString(), row.get("output").toString(),
                    )
                }
                readState.importSourceStates(rows,
                    com.gitlab.mudlej.MjPdfReader.data.annotation.CommentImportCheckpoint(migration, "complete"))
            }
            val saved = JsonObject()
            readState.sourceStates().forEach { row -> saved.add(row.id, JsonObject().apply {
                add("input", JsonParser.parseString(row.inputJson))
                add("output", JsonParser.parseString(row.outputJson))
            }) }
            synchronized(lock) { entries = saved }
            loaded = true
        } finally { loadMutex.unlock() }
    }

    suspend fun read(records: List<JsonObject>): List<JsonObject> {
        ensureLoaded()
        return synchronized(lock) {
            var changed = false
            for (record in records) {
                val id = record.get("comment_id")?.asString ?: continue
                if (inputs[id] != record) { inputs[id] = record.deepCopy(); changed = true }
            }
            if (changed) { generation++; requests.trySend(Unit) }
            records.map { record ->
                val saved = entries.getAsJsonObject(record.get("comment_id")?.asString.orEmpty())
                if (saved?.get("input") == record) saved.getAsJsonObject("output").deepCopy() else record
            }
        }
    }
}
