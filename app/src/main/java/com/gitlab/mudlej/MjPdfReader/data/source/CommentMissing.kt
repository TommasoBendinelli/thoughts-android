// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.time.Instant

/**
 * "Comment missing" (docs/comment-missing.md of the desktop): a comment marker in a managed source
 * whose comment no longer exists, deleted or unknown to the comment store. It is the reverse of
 * "Anchor removed". Each occurrence is one local-only Issues row; nothing about it is synced.
 */
internal data class MissingCommentMarker(
    /** The marker's id, exactly as written. */
    val anchorId: String,
    /** Docs-root-relative path of the source. */
    val sourceFile: String,
    val line: Int,
    /** The marker's text (its second argument, or the Markdown metadata's contents). */
    val contents: String,
    /** The highlighted words; highlight and strikethrough only. */
    val selectedText: String?,
    /** anchor, highlight, strikethrough or markdown. */
    val kind: String,
    /** The PDFs the source builds, sorted. */
    val targets: List<String>,
    /** deleted when a deleted root comment matches, otherwise unknown. */
    val state: String,
    val commentId: String? = null,
    val deletionReason: String? = null,
) : java.io.Serializable {
    /** One row per occurrence: a duplicated id is listed once per place. */
    val rowId: String get() = "comment-missing:$sourceFile:$line:$anchorId"

    companion object {
        const val STATE_DELETED = "deleted"
        const val STATE_UNKNOWN = "unknown"
    }
}

/** The comment store as "Comment missing" reads it: server snapshot, outbox and unsent source journals. */
internal data class CommentMissingStore(
    /** At least one Sync completed; before that a comment not downloaded yet would look missing. */
    val loaded: Boolean,
    /** Logical comments (roots and replies), deleted ones included, as stored (before source projection). */
    val logicalComments: List<JsonObject>,
    /** Legacy annotation records by entity id. */
    val annotations: List<Pair<String, JsonObject>> = emptyList(),
)

internal object CommentMissingState {
    private val COMMENT_KINDS = setOf("anchor", "highlight", "strikethrough", "markdown")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** `X`, `thoughts-X` and `thoughts-{X}` name the same comment. */
    fun identity(raw: String): String {
        var id = raw.trim()
        if (id.startsWith("thoughts-")) id = id.substring(9)
        if (id.length > 2 && id.startsWith('{') && id.endsWith('}')) id = id.substring(1, id.length - 1)
        return id.lowercase()
    }

    /** Marker kinds that belong to comments; `\ReviewAnchor` is never reported. */
    fun isCommentMarkerKind(kind: String) = kind in COMMENT_KINDS

    fun storeLoaded(bootstrapped: Boolean, cursor: Long, hasProjections: Boolean) = bootstrapped || cursor > 0 || hasProjections

    fun isGenerated(path: String) = path.startsWith("tex/generated/") || path.contains("/tex/generated/")

    private fun isSourceExtension(path: String) = path.endsWith(".tex") || path.endsWith(".md") || path.endsWith(".markdown")

    fun isMarkdown(path: String) = path.endsWith(".md") || path.endsWith(".markdown")

    /** A Project's own `.tex`/Markdown build input, outside `tex/generated/`. */
    fun isManagedSource(path: String, tracked: Collection<String>): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.split('/').any { it == ".." || it == "." || it.isEmpty() }) return false
        return isSourceExtension(path) && !isGenerated(path) && SourceOwner.of(path) != null && path in tracked
    }

    /** The PDFs each tracked source builds, sorted. */
    fun targetsByPath(bundle: OfflineSourceBundle): Map<String, List<String>> {
        val targets = mutableMapOf<String, MutableSet<String>>()
        for ((filename, pdf) in bundle.pdfs) for (segment in pdf.segments) {
            (segment.inputs.map { it.sourceFile } + listOfNotNull(segment.primarySourceFile)).forEach {
                targets.getOrPut(it, ::sortedSetOf).add(filename)
            }
        }
        return targets.mapValues { it.value.toList() }
    }

    /**
     * Identities of every comment that exists: each non-deleted logical comment (its comment_id and
     * scope.anchor_id), and each non-deleted legacy annotation without a logical comment of its id.
     */
    fun liveIdentities(store: CommentMissingStore): Set<String> {
        val live = mutableSetOf<String>()
        val logicalIds = mutableSetOf<String>()
        for (record in store.logicalComments) {
            val id = record.text("comment_id")
            if (id.isBlank()) continue
            logicalIds += identity(id)
            if (record.isDeleted()) continue
            live += identity(id)
            record.scope()?.text("anchor_id")?.takeIf(String::isNotBlank)?.let { live += identity(it) }
        }
        for ((entityId, record) in store.annotations) {
            val id = identity(entityId)
            if (record.isDeleted() || id in logicalIds) continue
            live += id
            record.text("anchor_id").takeIf(String::isNotBlank)?.let { live += identity(it) }
        }
        return live
    }

    /** The deleted root comment of a marker: an exact comment_id or anchor_id match first, then the same identity. */
    fun deletedCommentForMarker(logicalComments: List<JsonObject>, markerId: String): JsonObject? {
        val wanted = identity(markerId)
        var similar: JsonObject? = null
        for (record in logicalComments) {
            if (!record.isDeleted() || record.text("parent_id").isNotBlank()) continue
            val id = record.text("comment_id")
            val anchor = record.scope()?.text("anchor_id").orEmpty()
            if (id == markerId || anchor == markerId) return record
            if (similar == null && (identity(id) == wanted || anchor.isNotBlank() && identity(anchor) == wanted)) similar = record
        }
        return similar
    }

    /** The server refuses to revive an item whose owning document was deleted (409 for the whole batch). */
    fun deletedCommentBlocksRestore(record: JsonObject): Boolean {
        val evidence = record.get("document_deletion")
        if (evidence != null && evidence.isJsonObject && evidence.asJsonObject.size() > 0) return true
        if (record.text("deletion_reason") == "document_deleted") return true
        val sources = record.get("source_deletion")?.takeIf { it.isJsonObject }?.asJsonObject?.get("sources")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        return sources.any { it.isJsonObject && it.asJsonObject.get("deleted")?.takeIf { value -> value.isJsonPrimitive }?.asBoolean == true }
    }

    /**
     * Every marker occurrence of a managed source whose comment is missing. [index] is the annotation
     * index of all tracked sources (files with `markers`, and per project whether every file was read
     * and is valid). Nothing is reported before the store is loaded, or for a project not scanned completely.
     */
    fun detect(store: CommentMissingStore, index: JsonObject, targets: Map<String, List<String>>): List<MissingCommentMarker> {
        if (!store.loaded) return emptyList()
        val live = liveIdentities(store)
        val projects = index.getAsJsonObject("projects") ?: return emptyList()
        val files = index.getAsJsonObject("files") ?: return emptyList()
        val missing = mutableListOf<MissingCommentMarker>()
        for (path in files.keySet().sorted()) {
            val project = SourceOwner.of(path) ?: continue
            if (projects.get(project)?.takeIf { it.isJsonPrimitive }?.asBoolean != true) continue
            if (!isSourceExtension(path) || isGenerated(path)) continue
            val file = files.getAsJsonObject(path) ?: continue
            if (file.get("valid")?.asBoolean != true) continue
            val markers = file.get("markers")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for (id in markers.keySet().sorted()) {
                if (identity(id) in live) continue
                val deleted = deletedCommentForMarker(store.logicalComments, id)
                for (value in markers.getAsJsonArray(id) ?: JsonArray()) {
                    val found = value.asJsonObject
                    val kind = found.text("kind")
                    if (!isCommentMarkerKind(kind)) continue
                    missing += MissingCommentMarker(
                        anchorId = id, sourceFile = path, line = found.get("line")?.asInt ?: 0,
                        contents = found.text("contents"),
                        selectedText = found.text("selected_text").takeIf { (kind == "highlight" || kind == "strikethrough") && found.has("selected_text") },
                        kind = kind, targets = targets[path].orEmpty().sorted(),
                        state = if (deleted == null) MissingCommentMarker.STATE_UNKNOWN else MissingCommentMarker.STATE_DELETED,
                        commentId = deleted?.text("comment_id"), deletionReason = deleted?.text("deletion_reason"),
                    )
                }
            }
        }
        return missing
    }

    sealed class Removal {
        data class Edited(val text: String) : Removal()
        object Missing : Removal()
        data class Duplicated(val count: Int) : Removal()
    }

    /** Occurrences of each comment marker id, as comment linking reads the file. */
    fun markerCounts(source: String, markdown: Boolean): Map<String, Int> =
        CommentSourceMarkup.markers(source, markdown).groupingBy { it.id }.eachCount()

    /**
     * Removes the single occurrence of [anchorId]: a highlight or strikethrough with words keeps its
     * words; a marker with an empty or absent third argument is removed, with its whole line when
     * only whitespace is before it and nothing or a single `%` after it. Markdown: the meta line and
     * its tokens; a token line that becomes empty is removed.
     */
    fun remove(source: String, markdown: Boolean, anchorId: String): Removal {
        val found = CommentSourceMarkup.markers(source, markdown).filter { it.id == anchorId }
        if (found.isEmpty()) return Removal.Missing
        if (found.size > 1) return Removal.Duplicated(found.size)
        val marker = found.single()
        if (markdown) return Removal.Edited(removeMarkdown(source, marker))
        var end = marker.end
        var body = marker.body
        if (body == null) {
            // `\CommentAnchor{id}{text}{}`: the optional third argument belongs to the marker.
            var position = end
            while (position < source.length && source[position] in " \t\r") position++
            braced(source, position)?.let { range -> body = source.substring(range.first + 1, range.last); end = range.last + 1 }
        }
        val words = body
        if (!words.isNullOrEmpty()) return Removal.Edited(source.replaceRange(marker.start, end, words))
        val lineStart = source.lastIndexOf('\n', marker.start - 1) + 1
        val lineEnd = source.indexOf('\n', end).let { if (it < 0) source.length else it + 1 }
        val prefix = source.substring(lineStart, marker.start).trim()
        val suffix = source.substring(end, lineEnd).trim()
        return Removal.Edited(
            if (prefix.isEmpty() && (suffix.isEmpty() || suffix == "%")) source.removeRange(lineStart, lineEnd)
            else source.removeRange(marker.start, end),
        )
    }

    /** The braced group starting at [position], as its index range including both braces. */
    private fun braced(source: String, position: Int): IntRange? {
        if (source.getOrNull(position) != '{') return null
        var depth = 0
        var i = position
        while (i < source.length) {
            when (source[i]) {
                '\\' -> { i += 2; continue }
                '{' -> depth++
                '}' -> if (--depth == 0) return position..i
            }
            i++
        }
        return null
    }

    private fun removeMarkdown(source: String, marker: CommentSourceMarkup.Marker): String {
        val metadata = com.google.gson.JsonParser.parseString(source.substring(marker.textStart, marker.textEnd)).asJsonObject
        val key = metadata.get("key").asString
        val token = Regex("<!--\\s*thoughts:v1:(?:start|end|point)\\s+${Regex.escape(key)}\\s*-->")
        val lines = source.split('\n').toMutableList()
        val metaIndex = marker.line - 1
        val visible = CommentSourceMarkup.markdownVisibleLines(source).map { it.first }.toSet()
        val removed = mutableSetOf(metaIndex)
        var offset = 0
        for (index in lines.indices) {
            val line = lines[index]
            val start = offset
            offset += line.length + 1
            if (index == metaIndex || start !in visible || !token.containsMatchIn(line)) continue
            val stripped = token.replace(line, "")
            if (stripped.isBlank()) removed += index else lines[index] = stripped
        }
        return lines.filterIndexed { index, _ -> index !in removed }.joinToString("\n")
    }

    /** The edit removed [anchorId] and kept every other marker exactly as often as before. */
    fun removalVerified(before: String, after: String, markdown: Boolean, anchorId: String): Boolean {
        val old = markerCounts(before, markdown)
        val new = markerCounts(after, markdown)
        return anchorId !in new && old.filterKeys { it != anchorId } == new
    }

    /** The scope a restored comment gets from its marker as it is now. */
    fun restoredScope(anchorId: String, sourceFile: String, line: Int) = JsonObject().apply {
        addProperty("state", "source_linked"); addProperty("anchor_id", anchorId)
        addProperty("source_file", sourceFile); addProperty("source_line", line)
        addProperty("reason", "unique_source_marker")
    }

    /** The marker as the source has it now, for Restore. */
    data class CurrentMarker(val line: Int, val contents: String, val kind: String, val selectedText: String?) {
        val markup: Boolean get() = kind == "highlight" || kind == "strikethrough"
    }

    /**
     * Undelete: the same comment one revision later (its base is the row's revision), with the marker's
     * text and scope and without the deletion evidence. Identity, thread, parent and origin are kept.
     */
    fun undeletePayload(deleted: JsonObject, marker: CurrentMarker, scope: JsonObject, now: String, originId: String): JsonObject {
        val revision = deleted.get("revision")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0
        require(revision >= 1) { "The deleted comment has no valid revision" }
        return deleted.deepCopy().apply {
            listOf("deletion_reason", "document_deletion", "source_deletion").forEach(::remove)
            addProperty("deleted", false)
            addProperty("revision", revision + 1)
            addProperty("updated_at", now)
            addProperty("origin_id", originId)
            addProperty("contents", marker.contents)
            if (marker.markup) addProperty("selected_text", marker.selectedText.orEmpty())
            add("scope", scope.deepCopy())
        }
    }

    /** Create: a new client-v1 root with the marker's id on the document's current PDF. */
    fun createPayload(
        anchorId: String, marker: CurrentMarker, scope: JsonObject, now: String, originId: String, author: String,
        targetPdf: String, documentId: String, pdfSha256: String,
    ): JsonObject = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("comment_id", anchorId)
        addProperty("thread_root_id", anchorId)
        add("parent_id", JsonNull.INSTANCE)
        addProperty("revision", 1)
        addProperty("created_at", now)
        addProperty("updated_at", now)
        addProperty("origin_id", originId)
        addProperty("deleted", false)
        addProperty("contents", marker.contents)
        addProperty("author", author)
        addProperty("subtype", when (marker.kind) { "highlight" -> "Highlight"; "strikethrough" -> "StrikeOut"; else -> "Text" })
        addProperty("priority", "normal")
        addProperty("source_authority", "client-v1")
        if (marker.markup) addProperty("selected_text", marker.selectedText.orEmpty())
        add("scope", scope.deepCopy())
        add("origin", JsonObject().apply {
            addProperty("collection", targetPdf.removeSuffix(".pdf"))
            addProperty("document_id", documentId)
            addProperty("pdf_sha256", pdfSha256)
            addProperty("page_index", 0)
            add("rect", JsonArray())
            add("fragments", JsonArray())
            addProperty("geometry_format", "pdf_points_bottom_left")
            add("raw_pdf_geometry", JsonObject())
        })
    }

    /** The first target PDF (sorted) with a Library identity: its filename, UUIDv4 document id and sha256. */
    fun libraryTarget(targets: List<String>, identity: (String) -> JsonObject?): Triple<String, String, String>? {
        for (target in targets.sorted()) {
            val entry = identity(target) ?: continue
            val documentId = entry.text("document_id")
            val sha256 = entry.text("pdf_sha256")
            if (DocumentIdentityStore.validId(documentId) && SHA256.matches(sha256)) return Triple(target, documentId, sha256)
        }
        return null
    }

    internal fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.isDeleted() = get("deleted")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    private fun JsonObject.scope() = get("scope")?.takeIf { it.isJsonObject }?.asJsonObject
}

/**
 * The two actions of a "Comment missing" row. Each re-checks first: the comment must still be missing
 * and the file still a managed source.
 *
 * - Remove marker: a source edit through the normal source edit queue (published by the next Sync),
 *   verified after writing and rolled back to the original text when the check fails.
 * - Restore comment: one logical-comment operation, an undelete of the deleted row or a new root,
 *   sent in its own batch so a refusal cannot hold back other changes. The source is not edited.
 */
internal class MissingCommentResolver internal constructor(
    private val sources: OfflineSourceBundleStore,
    private val save: (SourceSnapshot, String, String, String) -> Unit,
    private val commentStore: suspend () -> CommentMissingStore,
    private val documentIdentity: (String) -> JsonObject?,
    private val enqueue: suspend (JsonObject) -> Unit,
    private val now: () -> String = { Instant.now().toString() },
    private val author: String = AUTHOR,
) {
    constructor(context: Context) : this(
        OfflineSourceBundleStore(context),
        { snapshot, filename, hash, contents ->
            SourceEditQueue(context, scheduleRequests = false).saveForNextSync(snapshot, filename, hash, contents); Unit
        },
        { FedoraRepository(context).commentMissingStore() },
        { DocumentIdentityStore().byFilename(it) },
        { FedoraRepository(context).enqueueIsolatedLogicalComment(it) },
        author = com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAuthor.current(context),
    )

    enum class Code {
        REMOVED, RESTORED, RECREATED,
        NO_ID, INVALID_ID, NOT_MANAGED, SOURCES_UNAVAILABLE, EXISTS_AGAIN, ALREADY_EXISTS,
        MARKER_GONE, MARKER_DUPLICATED, NOT_VERIFIED, DOCUMENT_DELETED, NO_REVISION, NOT_IN_LIBRARY, SAVE_FAILED,
    }

    data class Outcome(val marker: MissingCommentMarker, val code: Code, val count: Int = 0) {
        val success: Boolean get() = code == Code.REMOVED || code == Code.RESTORED || code == Code.RECREATED
    }

    suspend fun remove(marker: MissingCommentMarker): Outcome {
        fun outcome(code: Code, count: Int = 0) = Outcome(marker, code, count)
        val id = marker.anchorId
        val path = marker.sourceFile
        if (id.isBlank()) return outcome(Code.NO_ID)
        val bundle = sources.librarySourceMetadata() ?: return outcome(Code.SOURCES_UNAVAILABLE)
        if (!CommentMissingState.isManagedSource(path, bundle.trackedSourcePaths())) return outcome(Code.NOT_MANAGED)
        if (CommentMissingState.identity(id) in CommentMissingState.liveIdentities(commentStore())) return outcome(Code.EXISTS_AGAIN)
        val snapshot = sources.sourceSnapshot(path, 1) ?: return outcome(Code.SOURCES_UNAVAILABLE)
        val markdown = CommentMissingState.isMarkdown(path)
        val original = snapshot.contents
        val updated = when (val removal = CommentMissingState.remove(original, markdown, id)) {
            CommentMissingState.Removal.Missing -> return outcome(Code.MARKER_GONE)
            is CommentMissingState.Removal.Duplicated -> return outcome(Code.MARKER_DUPLICATED, removal.count)
            is CommentMissingState.Removal.Edited -> removal.text
        }
        // Nothing that fails the check is written.
        if (!CommentMissingState.removalVerified(original, updated, markdown, id)) return outcome(Code.NOT_VERIFIED)
        val filename = snapshot.bundlePdfFilename ?: marker.targets.firstOrNull() ?: return outcome(Code.SOURCES_UNAVAILABLE)
        val pdfSha256 = snapshot.bundlePdfSha256 ?: return outcome(Code.SOURCES_UNAVAILABLE)
        try {
            save(snapshot, filename, pdfSha256, updated)
        } catch (error: Exception) {
            if (sources.sourceSnapshot(path, 1)?.contents == original) return outcome(Code.NOT_VERIFIED)
        }
        // Verify what was written; otherwise write the original text back.
        val written = sources.sourceSnapshot(path, 1)
        if (written == null || !CommentMissingState.removalVerified(original, written.contents, markdown, id)) {
            runCatching { save(written ?: snapshot, filename, pdfSha256, original) }
            return outcome(Code.NOT_VERIFIED)
        }
        SourceCommentChanges.changes.tryEmit(Unit)
        return outcome(Code.REMOVED)
    }

    suspend fun restore(marker: MissingCommentMarker): Outcome {
        fun outcome(code: Code, count: Int = 0) = Outcome(marker, code, count)
        val id = marker.anchorId
        val path = marker.sourceFile
        if (id.isBlank() || id.length > 512 || id.contains('/') || id.contains('\\')) return outcome(Code.INVALID_ID)
        val bundle = sources.librarySourceMetadata() ?: return outcome(Code.SOURCES_UNAVAILABLE)
        if (!CommentMissingState.isManagedSource(path, bundle.trackedSourcePaths())) return outcome(Code.NOT_MANAGED)
        val store = commentStore()
        if (CommentMissingState.identity(id) in CommentMissingState.liveIdentities(store)) return outcome(Code.ALREADY_EXISTS)
        // The comment is recreated from the marker as it is in the source now.
        val snapshot = sources.sourceSnapshot(path, 1) ?: return outcome(Code.SOURCES_UNAVAILABLE)
        val found = SourceAnnotationMarkup.scan(snapshot.contents, CommentMissingState.isMarkdown(path))
            .getAsJsonObject("markers")?.getAsJsonArray(id)?.map { it.asJsonObject }.orEmpty()
        if (found.isEmpty()) return outcome(Code.MARKER_GONE)
        if (found.size > 1) return outcome(Code.MARKER_DUPLICATED, found.size)
        val entry = found.single()
        val current = CommentMissingState.CurrentMarker(
            line = entry.get("line").asInt,
            contents = entry.get("contents")?.asString.orEmpty(),
            kind = entry.get("kind")?.asString.orEmpty(),
            selectedText = entry.get("selected_text")?.asString,
        )
        if (!CommentMissingState.isCommentMarkerKind(current.kind)) return outcome(Code.MARKER_GONE)
        val scope = CommentMissingState.restoredScope(id, path, current.line)
        val deleted = CommentMissingState.deletedCommentForMarker(store.logicalComments, id)
        val payload = if (deleted != null) {
            if (CommentMissingState.deletedCommentBlocksRestore(deleted)) return outcome(Code.DOCUMENT_DELETED)
            runCatching { CommentMissingState.undeletePayload(deleted, current, scope, now(), ORIGIN_ID) }
                .getOrElse { return outcome(Code.NO_REVISION) }
        } else {
            val targets = CommentMissingState.targetsByPath(bundle)[path].orEmpty()
            val (target, documentId, pdfSha256) = CommentMissingState.libraryTarget(targets, documentIdentity)
                ?: return outcome(Code.NOT_IN_LIBRARY)
            CommentMissingState.createPayload(id, current, scope, now(), ORIGIN_ID, author, target, documentId, pdfSha256)
        }
        try {
            enqueue(payload)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return outcome(Code.SAVE_FAILED)
        }
        return outcome(if (deleted != null) Code.RESTORED else Code.RECREATED)
    }

    companion object {
        const val ORIGIN_ID = "thoughts-android"
        /** The author when none is passed (tests); the app passes CommentAuthor.current. */
        const val AUTHOR = com.gitlab.mudlej.MjPdfReader.data.annotation.CommentAuthor.GENERIC_DEFAULT
    }
}
