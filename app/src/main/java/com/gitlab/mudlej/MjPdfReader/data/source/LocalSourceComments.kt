// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.render.SourcePdfCapability
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest

internal object SourceCommentChanges {
    val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

internal class LocalSourceComments internal constructor(
    private val sources: OfflineSourceBundleStore,
    private val save: (SourceSnapshot, String, String, String) -> Unit,
    private val pdfRoot: File,
    private val capability: (File) -> SourcePdfCapability,
    private val pageHeight: (File, Int) -> Float?,
    private val cacheSourceProjection: Boolean = false,
) {
    constructor(context: Context, cacheSourceProjection: Boolean = false) : this(
        OfflineSourceBundleStore(context),
        { snapshot, filename, hash, contents -> SourceEditQueue(context, scheduleRequests = false).saveForNextSync(snapshot, filename, hash, contents); Unit },
        ThoughtsLocalStore.currentPdfs(), { SourceLinkedPdfIndex().capabilityFor(it) },
        { pdf, page -> runCatching {
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                renderer.openPage(page - 1).use { it.height.toFloat() }
            }
        }.getOrNull() },
        cacheSourceProjection,
    )
    companion object { private val lock = Any() }
    private val snapshots = mutableMapOf<String, SourceSnapshot?>()
    private var observedAbsent = emptySet<String>()
    private val parsedLocations = mutableMapOf<String, JsonObject>()
    private val parsedMarkers = mutableMapOf<String, List<CommentSourceMarkup.Marker>>()
    private fun snapshot(path: String) = snapshots.getOrPut(path) { sources.sourceSnapshot(path, 1) }

    fun invalidate(paths: Set<String>? = null) = synchronized(lock) {
        if (paths == null) { snapshots.clear(); parsedMarkers.clear(); parsedLocations.clear() }
        else paths.forEach { snapshots.remove(it); parsedMarkers.remove(it); parsedLocations.remove(it) }
    }

    fun annotationIndex(paths: List<String>): JsonObject = synchronized(lock) {
        val files = JsonObject(); val projects = JsonObject(); val absent = mutableSetOf<String>()
        for (path in paths) {
            val project = SourceOwner.of(path).orEmpty()
            if (!projects.has(project)) projects.addProperty(project, true)
            val working = sources.sourceWorkingFile(path)
            val digest = working?.let { runCatching { digest(it.readBytes()) }.getOrNull() }
            val remembered = digest?.let { sources.annotationIndex.cachedScan(path, it) }
            if (remembered != null) {
                if (remembered.get("valid")?.asBoolean != true) projects.addProperty(project, false)
                // A scan saved by an older version may name a first-segment project.
                files.add(path, remembered.apply { addProperty("project", project) })
                continue
            }
            val snapshot = snapshot(path)
            if (snapshot == null) {
                val working = sources.sourceWorkingFile(path)
                if (working == null || working.exists() || working.parentFile?.canRead() != true) projects.addProperty(project, false)
                else absent += path
                continue
            }
            val markers = parsedMarkers.getOrPut(path) { CommentSourceMarkup.markers(snapshot.contents, path.endsWith(".md") || path.endsWith(".markdown")) }
            val scanned = parsedLocations.getOrPut(path) { SourceAnnotationMarkup.scan(snapshot.contents, path.endsWith(".md") || path.endsWith(".markdown"), markers) }
            if (scanned.get("valid")?.asBoolean != true) projects.addProperty(project, false)
            files.add(path, scanned.deepCopy().apply { addProperty("project", project) })
        }
        observedAbsent = absent
        JsonObject().apply { addProperty("schema_version", 1); add("files", files); add("projects", projects) }
    }

    fun annotationIndexStillCurrent(index: JsonObject): Boolean = observedAbsent.all { sources.sourceWorkingFile(it)?.exists() == false } && index.getAsJsonObject("files").entrySet().all { (path, value) ->
        val file = sources.sourceWorkingFile(path)
        file != null && runCatching { digest(file.readBytes()) == value.asJsonObject.get("sha256").asString }.getOrDefault(false)
    }

    fun apply(input: JsonObject, previous: JsonObject? = null, mutate: Boolean = false): JsonObject = synchronized(lock) {
        if (mutate || !cacheSourceProjection) { snapshots.clear(); parsedMarkers.clear(); parsedLocations.clear() }
        val result = input.deepCopy()
        if (result.text("parent_id").isNotBlank() || result.text("subtype") == "Note") return result
        val scope = result.getAsJsonObject("scope") ?: JsonObject()
        val previousScope = previous?.getAsJsonObject("scope")
        // An "Anchor removed" comment stays eligible so a reappeared marker relinks it.
        val removedScope = scope.takeIf(CommentAnchorState::isAnchorRemoved)
            ?: previousScope?.takeIf(CommentAnchorState::isAnchorRemoved)
        val linked = scope.text("state") == "source_linked" || previousScope?.text("state") == "source_linked" || removedScope != null
        if (!mutate && (!linked && result.text("source_authority") != "client-v1" || result.get("deleted")?.asBoolean == true)) return result
        if (mutate) result.addProperty("source_authority", "client-v1")
        val id = scope.text("anchor_id").ifBlank { result.text("comment_id") }
        val origin = result.get("origin")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val knownPath = scope.text("source_file")
        val known = knownPath.takeIf(String::isNotBlank)?.let { snapshot(it) }
        val filename = origin.text("collection").takeIf(String::isNotBlank)?.plus(".pdf")
            ?: known?.bundlePdfFilename ?: return result
        fun keepRemoved(): JsonObject = result.apply { add("scope", removedScope!!.deepCopy()) }
        fun failed(reason: String): JsonObject {
            // A failed check never turns "Anchor removed" into another state; only ambiguity does.
            if (removedScope != null && reason != "source_marker_ambiguous") return keepRemoved()
            // A temporary read failure does not revoke an established source link.
            if (!mutate && linked && reason != "source_marker_ambiguous") {
                check(!cacheSourceProjection) { "Source project check is incomplete: $reason" }
                return result
            }
            result.add("scope", scope.deepCopy().apply { addProperty("state", "needs_attention"); addProperty("reason", reason) })
            return result
        }
        // Made in the HTML view and not linked yet: no PDF geometry to map here; Fedora inserts the
        // marker from its quote (or exact source range) at the next Sync.
        if (mutate && !linked && result.get("deleted")?.asBoolean != true && origin.text("created_in") == "html") {
            result.add("scope", JsonObject().apply {
                addProperty("state", "pdf_only")
                addProperty("reason", com.gitlab.mudlej.MjPdfReader.data.annotation.HTML_PENDING_SERVER_LINK)
            })
            return result
        }
        val pdf = File(pdfRoot, filename)
        if (!linked && capability(pdf) == SourcePdfCapability.NOT_SOURCE_LINKED) {
            result.add("scope", JsonObject().apply { addProperty("state", "pdf_only"); addProperty("reason", "no_source") })
            return result
        }
        val bundle = sources.librarySourceMetadata() ?: return if (mutate) failed("source_bundle_unavailable") else result
        val project = SourceOwner.of(knownPath).orEmpty().ifBlank {
            bundle.pdfs[filename]?.segments?.firstOrNull()?.primarySourceFile?.let(SourceOwner::of).orEmpty()
        }
        if (project.isBlank()) return failed("source_project_unavailable")
        val paths = bundle.trackedSourcePaths().filter { SourceOwner.of(it) == project }
        // Source-backed records outside recorded build dependencies wait for explicit discovery/Sync.
        // An "Anchor removed" comment's last file may be gone; its marker can return elsewhere.
        if (!mutate && removedScope == null && knownPath.isNotBlank() && knownPath !in paths) return result
        val snapshots = paths.map { snapshot(it) }
        val matches = snapshots.filterNotNull().flatMap { snapshot ->
            parsedMarkers.getOrPut(snapshot.sourceFile) { CommentSourceMarkup.markers(snapshot.contents, snapshot.sourceFile.endsWith(".md") || snapshot.sourceFile.endsWith(".markdown")) }.filter { it.id == id }.map { snapshot to it }
        }
        val complete = paths.isNotEmpty() && paths.zip(snapshots).all { (path, snapshot) -> snapshot != null ||
            sources.sourceWorkingFile(path)?.let { !it.exists() && it.parentFile?.isDirectory == true && it.parentFile?.canRead() == true } == true }
        // A unique match is authoritative only after every tracked source was checked.
        if (!mutate && !complete) {
            if (removedScope != null) return keepRemoved()
            check(!cacheSourceProjection) { "Source project check is incomplete" }
            return result
        }
        if (matches.size > 1) return failed("source_marker_ambiguous")
        if (matches.size == 1) {
            val (snapshot, marker) = matches.single()
            val deleting = mutate && result.get("deleted")?.asBoolean == true
            val editing = mutate && previous != null && result.text("contents") != previous.text("contents")
            val contents = when {
                deleting -> CommentSourceMarkup.remove(snapshot.contents, marker)
                editing -> CommentSourceMarkup.update(snapshot.contents, marker, result.text("contents"))
                else -> snapshot.contents
            }
            if (contents != snapshot.contents) save(snapshot, filename, origin.text("pdf_sha256").ifBlank { snapshot.bundlePdfSha256.orEmpty() }, contents)
            if (!editing && !deleting) {
                result.addProperty("contents", marker.contents)
                marker.body?.let { result.addProperty("selected_text", CommentSourceMarkup.decode(it)) }
            }
            result.add("scope", JsonObject().apply {
                addProperty("state", "source_linked"); addProperty("anchor_id", id)
                addProperty("source_file", snapshot.sourceFile); addProperty("source_line", marker.line)
                addProperty("reason", "unique_source_marker")
            })
            return result
        }
        // No marker: "Anchor removed" stays as it is for reads, edits, deletes and Undo. Nothing
        // re-inserts the anchor automatically; Restore is an explicit source edit.
        if (removedScope != null) return keepRemoved()
        val restoring = mutate && previous?.get("deleted")?.asBoolean == true && result.get("deleted")?.asBoolean != true
        if (linked && !restoring) {
            // A tombstone keeps the scope it had.
            if (result.get("deleted")?.asBoolean == true) return result
            val missingKnownFile = knownPath.isNotBlank() && sources.sourceWorkingFile(knownPath)?.let {
                !it.exists() && it.parentFile?.isDirectory == true && it.parentFile?.canRead() == true
            } == true
            // A confirmed removal keeps the comment as "Anchor removed" with its last location.
            if ((known != null || missingKnownFile) && paths.isNotEmpty() && complete &&
                snapshots.filterNotNull().none { it.contents.contains(id) }) {
                val last = if (scope.text("state") == "source_linked") scope else previousScope ?: scope
                result.add("scope", CommentAnchorState.anchorRemovedScope(last, id, result))
            }
            else return failed("source_marker_unavailable")
            return result
        }
        if (!mutate || result.get("deleted")?.asBoolean == true) return result
        if (!pdf.isFile || digest(pdf.readBytes()) != origin.text("pdf_sha256")) return failed("pdf_revision_unavailable")
        val raw = origin.getAsJsonObject("raw_pdf_geometry") ?: return failed("pdf_geometry_unavailable")
        val rect = raw.getAsJsonArray("rect")?.takeIf { it.size() == 4 } ?: return failed("pdf_geometry_unavailable")
        val page = raw.get("page")?.asInt ?: return failed("pdf_geometry_unavailable")
        val height = pageHeight(pdf, page) ?: return failed("pdf_geometry_unavailable")
        val selection = SourceSelection(filename, origin.text("pdf_sha256"), page,
            (rect[0].asFloat + rect[2].asFloat) / 2, height - (rect[1].asFloat + rect[3].asFloat) / 2,
            result.text("selected_text"))
        val snapshot = sources.resolve(selection) ?: return failed("source_map_unavailable")
        if (!snapshot.sourceFile.endsWith(".tex") || snapshot.navigationBaseContents == null) return failed("source_map_unavailable")
        // A mapped line belongs to the PDF's exact source. Remap only a unique unchanged line.
        val historicalLine = snapshot.navigationBaseContents.lines().getOrNull(snapshot.sourceLine - 1)
            ?: return failed("source_map_unavailable")
        val currentLines = snapshot.contents.lines()
        val matching = currentLines.indices.filter { currentLines[it] == historicalLine }
        val line = if (snapshot.contents == snapshot.navigationBaseContents) snapshot.sourceLine
            else matching.singleOrNull()?.plus(1) ?: return failed("source_changed_ambiguously")
        val contents = CommentSourceMarkup.insert(snapshot.contents, line, id, result.text("contents"),
            result.text("subtype"), result.text("selected_text")) ?: return failed("selection_not_uniquely_editable")
        save(snapshot, filename, origin.text("pdf_sha256").ifBlank { snapshot.bundlePdfSha256.orEmpty() }, contents)
        val marker = CommentSourceMarkup.markers(contents).single { it.id == id }
        result.add("scope", JsonObject().apply {
            addProperty("state", "source_linked"); addProperty("anchor_id", id)
            addProperty("source_file", snapshot.sourceFile); addProperty("source_line", marker.line)
            addProperty("reason", "unique_source_marker")
        })
        return result
    }
    /** The outcome of [restoreAnchor]; the UI words it. */
    data class RestoreResult(
        val success: Boolean,
        val failure: Failure? = null,
        val sourceFile: String = "",
        /** The file named by the comment's last location, when the anchor went elsewhere. */
        val requestedFile: String = "",
        val requestedLine: Int = 0,
        val line: Int = 0,
        val placement: Placement = Placement.EXACT,
        val fallbackFile: Boolean = false,
        val pointAnchorFallback: Boolean = false,
        /** Lines in the chosen file before the restore; tells a vanished line from an unsafe one. */
        val lineCount: Int = 0,
    ) {
        enum class Placement { EXACT, NEAREST_LINE, END_OF_FILE, ALREADY_PRESENT }
        enum class Failure { NOT_ANCHOR_REMOVED, SOURCES_UNAVAILABLE, SOURCE_FILE_MISSING, MALFORMED, NOT_INSERTABLE }
    }

    /**
     * Restore of an "Anchor removed" comment: re-inserts its marker at the last known line, or the
     * nearest line where a marker is safe, through the normal source edit queue. No logical-comment
     * operation is sent: the local projection and later the server relink the comment from the marker.
     */
    fun restoreAnchor(root: JsonObject): RestoreResult = synchronized(lock) {
        snapshots.clear(); parsedMarkers.clear(); parsedLocations.clear()
        val scope = root.getAsJsonObject("scope")
        if (!CommentAnchorState.isAnchorRemoved(scope) || root.text("parent_id").isNotBlank())
            return RestoreResult(false, RestoreResult.Failure.NOT_ANCHOR_REMOVED)
        scope!!
        val id = scope.text("anchor_id").ifBlank { root.text("comment_id") }
        val requestedFile = scope.text("source_file")
        val requestedLine = scope.get("source_line")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0
        val origin = root.get("origin")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val bundle = sources.librarySourceMetadata()
            ?: return RestoreResult(false, RestoreResult.Failure.SOURCES_UNAVAILABLE, requestedFile = requestedFile, requestedLine = requestedLine)
        val collectionPdf = origin.text("collection").takeIf(String::isNotBlank)?.plus(".pdf")
        val primary = collectionPdf?.let { bundle.pdfs[it]?.segments?.firstOrNull()?.primarySourceFile }.orEmpty()
        val project = SourceOwner.of(requestedFile).orEmpty().ifBlank { SourceOwner.of(primary).orEmpty() }
        val paths = bundle.trackedSourcePaths().filter { project.isNotBlank() && SourceOwner.of(it) == project }
        fun markdown(path: String) = path.endsWith(".md") || path.endsWith(".markdown")
        // Pre-check: a live marker means nothing to do; a raw identity that does not parse is left to the user.
        for (path in paths) {
            val snapshot = snapshot(path) ?: continue
            CommentSourceMarkup.markers(snapshot.contents, markdown(path)).firstOrNull { it.id == id }?.let { marker ->
                return RestoreResult(true, sourceFile = path, requestedFile = requestedFile, requestedLine = requestedLine,
                    line = marker.line, placement = RestoreResult.Placement.ALREADY_PRESENT)
            }
        }
        for (path in paths) {
            val snapshot = snapshot(path) ?: continue
            val offset = snapshot.contents.indexOf(id)
            if (offset >= 0) return RestoreResult(false, RestoreResult.Failure.MALFORMED, path, requestedFile, requestedLine,
                snapshot.contents.take(offset).count { it == '\n' } + 1)
        }
        val requested = requestedFile.takeIf { it in paths }?.let { path -> snapshot(path)?.let { path to it } }
        val (path, snapshot) = requested ?: primary.takeIf { it in paths }?.let { path -> snapshot(path)?.let { path to it } }
            ?: return RestoreResult(false, RestoreResult.Failure.SOURCE_FILE_MISSING, requestedFile = requestedFile, requestedLine = requestedLine)
        val fallbackFile = requested == null
        val contents = root.text("contents").ifBlank { scope.text("anchor_mirror") }
        val subtype = root.text("subtype"); val selected = root.text("selected_text")
        val isMarkdown = markdown(path)
        val markup = subtype in setOf("Highlight", "StrikeOut", "Underline", "Squiggly") &&
            scope.text("anchor_kind") in setOf("", "CommentHighlight", "CommentStrikethrough")
        val lineCount = snapshot.contents.split('\n').size
        val target = requestedLine.coerceIn(1, lineCount)
        val candidates = sequence {
            yield(target)
            for (distance in 1 until lineCount) {
                if (target + distance <= lineCount) yield(target + distance)
                if (target - distance >= 1) yield(target - distance)
            }
        }
        var pointFallback = false
        var chosen = 0
        var updated: String? = null
        for (candidate in candidates) {
            updated = if (isMarkdown) CommentSourceMarkup.insertMarkdownPoint(snapshot.contents, candidate, id, contents)
            else {
                val wrapped = if (markup && selected.isNotBlank())
                    CommentSourceMarkup.insert(snapshot.contents, candidate, id, contents, subtype, selected) else null
                wrapped ?: CommentSourceMarkup.insert(snapshot.contents, candidate, id, contents, "Text", "")
                    ?.also { pointFallback = markup }
            }
            if (updated != null) { chosen = candidate; break }
        }
        val placement = when {
            chosen == 0 -> RestoreResult.Placement.END_OF_FILE
            fallbackFile || chosen != requestedLine -> RestoreResult.Placement.NEAREST_LINE
            else -> RestoreResult.Placement.EXACT
        }
        if (updated == null) {
            updated = if (isMarkdown) CommentSourceMarkup.appendMarkdownPoint(snapshot.contents, id, contents)
                else CommentSourceMarkup.insertPointAtEnd(snapshot.contents, id, contents)
            pointFallback = markup && !isMarkdown
        }
        if (isMarkdown) pointFallback = markup
        // Verify: exactly one live marker with this identity, or nothing is written.
        val marker = updated?.let { CommentSourceMarkup.markers(it, isMarkdown).filter { m -> m.id == id }.singleOrNull() }
            ?: return RestoreResult(false, RestoreResult.Failure.NOT_INSERTABLE, path, requestedFile, requestedLine)
        val filename = collectionPdf ?: snapshot.bundlePdfFilename
            ?: return RestoreResult(false, RestoreResult.Failure.SOURCES_UNAVAILABLE, path, requestedFile, requestedLine)
        save(snapshot, filename, origin.text("pdf_sha256").ifBlank { snapshot.bundlePdfSha256.orEmpty() }, updated)
        invalidate(setOf(path))
        SourceCommentChanges.changes.tryEmit(Unit)
        RestoreResult(true, null, path, requestedFile, requestedLine, marker.line, placement, fallbackFile, pointFallback, lineCount)
    }

    private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
