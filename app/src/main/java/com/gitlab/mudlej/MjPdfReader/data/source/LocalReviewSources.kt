// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import android.content.Context
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsLocalStore
import com.gitlab.mudlej.MjPdfReader.pdf.PageCharMetrics
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Immutable object references couple a durable local edit to its card operation. */
/** Places a card's areas in its source and commits the edit with the card; Android-free for tests. */
internal interface ReviewSourcePlacer {
    /** The source file the last mapping came from, for a card left to be linked by hand. */
    val fallbackSource: String
    fun placement(id: String, filename: String, hash: String, areas: List<LocalReviewSources.PlacementArea>, previous: JsonObject?): LocalReviewSources.Edit
    fun commit(edit: LocalReviewSources.Edit, record: JsonObject, destination: File, transactionId: String? = null)
    fun hasRecoveryRecord(id: String): Boolean
}

internal class LocalReviewSources(private val context: Context, private val persistRecord: ((JsonObject, File) -> Unit)? = null) : ReviewSourcePlacer {
    override var fallbackSource: String = ""; private set
    private val sources = OfflineSourceBundleStore(context)
    private val root = File(context.filesDir, "review-source-transactions")
    private val objects = File(context.filesDir, "review-source-objects")
    data class Edit(val path: String, val before: String, val after: String, val anchor: JsonObject? = null, val deleteText: Boolean = false, val base: String = before)
    companion object {
        private val lock = Any()
        /** Characters of a box sampled for authored source lines when its ends have none. */
        private const val FLOAT_SAMPLES = 24
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun atomic(file: File, bytes: ByteArray) {
            file.parentFile!!.mkdirs()
            val target = AtomicFile(file)
            val output = target.startWrite()
            try { output.write(bytes); target.finishWrite(output) } catch (error: Throwable) { target.failWrite(output); throw error }
        }

        /** The edit removing card [cardId]'s markers (and with [deleteText], its passage) from its one verified phone source. */
        internal fun markerDeletion(sources: OfflineSourceBundleStore, objects: File?, cardId: String, anchor: JsonObject, deleteText: Boolean): Edit {
            val segments = requireNotNull(anchor.getAsJsonArray("segments")).map { it.asJsonObject }
            require(segments.size in 1..20) { "Marker removal pending" }
            val path = segments.first().get("source_file").asString
            // One operation edits one file; a card's passages all live in it.
            require(segments.all { it.get("source_file")?.asString == path }) { "Marker removal pending" }
            // Reuse the shared verified location index; an incomplete/stale index cannot authorize deleting text.
            val location = sources.annotationIndex.locate("review_card", cardId, SourceOwner.of(path).orEmpty())
            val snapshot = requireNotNull(sources.sourceSnapshot(path, 1)) { "Marker removal pending" }
            val indexed = location.get("state")?.asString == "linked" && location.get("source_file")?.asString == path &&
                digest(snapshot.contents.toByteArray()) == location.get("source_sha256")?.asString
            if (!indexed) {
                // Any source edit (including the previous card of a multi-delete) re-checks the index in the
                // background. Removing only this card's markers needs just this file's own complete pair.
                require(!deleteText) { "Marker removal pending" }
                val scan = SourceAnnotationMarkup.scan(snapshot.contents, !path.endsWith(".tex"))
                require(scan.get("valid")?.asBoolean == true &&
                    scan.getAsJsonObject("reviews")?.getAsJsonArray(cardId)?.size() == segments.size) { "Marker removal pending" }
            }
            val queued = objects?.let { directory ->
                sources.lastQueuedSourceSha256(path)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { hash ->
                    File(directory, hash).takeIf(File::isFile)?.readBytes()?.takeIf { digest(it) == hash }?.toString(Charsets.UTF_8)
                }
            }
            var after = snapshot.contents
            for (segment in segments) after = ReviewSourceMarkup.remove(path, after, cardId, segment, deleteText).after
            return Edit(path, snapshot.contents, after, deleteText = deleteText, base = queued ?: snapshot.baseContents ?: snapshot.contents)
        }

        /**
         * Removes the markers of deleted card [cardId] whose removal was left pending, when the
         * phone's verified source copy still allows the same safe removal a deletion makes. The
         * delete event is already recorded, so [save] queues the edit as an ordinary source save
         * on top of exactly the contents that were checked. Returns whether it edited the source.
         */
        internal fun retryMarkerRemoval(
            sources: OfflineSourceBundleStore,
            cardId: String,
            anchor: JsonObject,
            save: (SourceSnapshot, String) -> Unit,
        ): Boolean = synchronized(lock) {
            val edit = runCatching { markerDeletion(sources, null, cardId, anchor, deleteText = false) }.getOrNull()
                ?: return@synchronized false
            if (edit.after == edit.before) return@synchronized false
            val snapshot = sources.sourceSnapshot(edit.path, 1) ?: return@synchronized false
            if (snapshot.contents != edit.before || snapshot.bundlePdfFilename == null || snapshot.bundlePdfSha256 == null) {
                return@synchronized false
            }
            save(snapshot, edit.after)
            true
        }
    }

    /**
     * One selected area mapped to its source: the file, the lines its first and last characters come
     * from, and its text. [stale] areas come from a PDF built before the source last changed, so their
     * build lines do not name current lines and [lines] is empty. [partial] areas are boxes in a TeX
     * float some of whose characters have no authored source line; only the whole float covers them.
     */
    private class MappedArea(val snapshot: SourceSnapshot, val lines: List<Int>, val text: String,
        val stale: Boolean = false, val partial: Boolean = false)

    /** One answer area to link: its regions (1-based pages of the card's PDF), its selected text, and whether it was drawn as a box. */
    data class PlacementArea(val regions: List<ReviewAnswerRegion>, val text: String? = null, val rectangle: Boolean = text == null)

    /**
     * Marks every area as its own segment of card [id] ("0".."n-1" in area order) in the one source
     * file the areas come from. With [previous] (the card's current source anchor) the card's
     * existing markers in that file are removed first: the result moves only this card's markers.
     * An area that cannot be linked fails with [ReviewAreaLinkingException] naming it.
     */
    override fun placement(id: String, filename: String, hash: String, areas: List<PlacementArea>, previous: JsonObject?): Edit {
        require(areas.size in 1..20) { "Select between one and twenty areas." }
        require(areas.all { it.regions.isNotEmpty() }) { "Invalid review region" }
        val pages = areas.map { area -> area.regions.minOf { it.page } }
        fun fail(index: Int, reason: String?): Nothing = throw ReviewAreaLinkingException(index, areas.size, pages[index],
            reason?.takeIf(String::isNotBlank) ?: "Verified source mapping is unavailable.")
        val mapped = areas.mapIndexed { index, area ->
            try { mapArea(filename, hash, area.regions, area.rectangle) } catch (error: Exception) { fail(index, error.message) }
        }
        val first = mapped.first().snapshot
        val path = first.sourceFile
        mapped.forEachIndexed { index, area ->
            if (area.snapshot.sourceFile != path) fail(index, "It comes from ${SourceOwner.relative(area.snapshot.sourceFile) ?: area.snapshot.sourceFile}, " +
                "but area 1 comes from ${SourceOwner.relative(path) ?: path}. A card links passages of one source file.")
            if (area.snapshot.contents != first.contents) fail(index, "The source changed while the areas were being linked. Try again.")
        }
        // One operation edits one file: old markers elsewhere would stay behind.
        previous?.getAsJsonArray("segments")?.mapNotNull { it.asJsonObject.get("source_file")?.asString }?.distinct()
            ?.filter { it != path }?.forEach { other ->
                val contents = runCatching { sources.sourceSnapshot(other, 1)?.contents }.getOrNull()
                if (contents == null || contents.contains(id)) fail(0, "This card's passage is marked in " +
                    "${SourceOwner.relative(other) ?: other}. Place its areas in that file, or remove its markers there with Edit placement first.")
            }
        val edit = try {
            ReviewSourceMarkup.placeAreas(path, first.contents, id, mapped.mapIndexed { index, area ->
                MappedSourceArea(area.lines, areas[index].text?.takeIf(String::isNotBlank) ?: area.text,
                    areas[index].rectangle, area.partial, area.stale)
            })
        } catch (error: ReviewAreaLinkingException) { throw error.withPages(pages) }
        return edit.copy(base = first.baseContents ?: first.contents)
    }

    private fun mapArea(filename: String, hash: String, regions: List<ReviewAnswerRegion>, rectangle: Boolean = false): MappedArea {
        fallbackSource = sources.librarySourceMetadata(filename)?.pdfs?.get(filename)?.segments
            ?.firstOrNull { region -> regions.any { it.page >= region.startPage && it.page < region.startPage + region.pageCount } }
            ?.primarySourceFile.orEmpty()
        val pdf = File(ThoughtsLocalStore.currentPdfs(), filename)
        require(pdf.isFile && com.gitlab.mudlej.MjPdfReader.data.sync.verifiedFileCache.sha256(pdf) == hash) {
            "The displayed PDF is unavailable or changed."
        }
        // Extract once per selected page. Never launch a process or rescan the Library.
        val core = PdfiumCore(context)
        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val document = try { core.newDocument(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
        var snapshot: SourceSnapshot? = null
        var stale = false
        val selected = StringBuilder()
        val mappedLines = mutableListOf<Int>()
        var floatArea: MappedArea? = null
        try {
            for ((page, boxes) in regions.groupBy { it.page }.toSortedMap()) {
                core.openPage(document, page - 1)
                try {
                    core.openTextPage(document, page - 1)
                    val count = core.textCountChars(document, page - 1)
                    require(count in 1..100_000) { "The selected page cannot be verified." }
                    val metrics = requireNotNull(PageCharMetrics.fromRaw(core.textCharMetrics(document, page - 1, 0, count)))
                    val width = core.getPageWidthPoint(document, page - 1).toFloat()
                    val height = core.getPageHeightPoint(document, page - 1).toFloat()
                    val indices = (0 until metrics.count).filter { i ->
                        val x = (metrics.left[i] + metrics.right[i]) / 2 / width
                        val y = 1 - (metrics.top[i] + metrics.bottom[i]) / 2 / height
                        boxes.any { it.rect.contains(x, y) }
                    }
                    require(indices.isNotEmpty()) { "No editable text was found in the selection." }
                    val text = buildString { indices.forEach { appendCodePoint(metrics.codePoint[it]) } }
                    selected.append(text).append(' ')
                    fun resolve(i: Int) = sources.resolve(SourceSelection(filename, hash, page,
                        (metrics.left[i] + metrics.right[i]) / 2, height - (metrics.top[i] + metrics.bottom[i]) / 2, text))
                    val ends = listOf(indices.first(), indices.last()).distinct()
                    val resolvedEnds = ends.map(::resolve)
                    if (rectangle && regions.size == 1) {
                        // A box over a float: characters from generated or unmapped text are tolerated
                        // when the authored characters around them lie in one float.
                        val authored = resolvedEnds.map { it?.let(::authoredLine) }
                        val clean = authored.all { it != null } && resolvedEnds.map { it!!.sourceFile }.distinct().size == 1
                        if (!clean) {
                            val samples = indices.filter { !Character.isWhitespace(metrics.codePoint[it]) }
                                .let { all -> if (all.size <= FLOAT_SAMPLES) all else (0 until FLOAT_SAMPLES).map { all[it * (all.size - 1) / (FLOAT_SAMPLES - 1)] } }
                                .distinct()
                            floatArea = samples.mapNotNull { i -> resolve(i)?.let { r -> authoredLine(r)?.let { r to it } } }
                                .groupBy { it.first.sourceFile }.values.sortedByDescending { it.size }
                                .firstNotNullOfOrNull { group ->
                                    val lines = group.map { it.second }
                                    val source = group.first().first
                                    TexFloats.enclosing(source.contents, lines.min()..lines.max())?.takeIf { source.sourceFile.endsWith(".tex") }
                                        ?.let { MappedArea(source, listOf(lines.min(), lines.max()), text, partial = true) }
                                }
                            if (floatArea != null) continue
                        }
                    }
                    for (resolvedEnd in resolvedEnds) {
                        val resolved = requireNotNull(resolvedEnd) { "Verified source mapping is unavailable." }
                        fallbackSource = resolved.sourceFile
                        require(snapshot == null || snapshot!!.sourceFile == resolved.sourceFile) { "Select one continuous passage in one source file." }
                        val line = authoredLine(resolved)
                        if (line == null) stale = true else mappedLines += line
                        snapshot = resolved
                    }
                } finally { core.closePage(document, page - 1) }
            }
        } finally { core.closeDocument(document) }
        require(com.gitlab.mudlej.MjPdfReader.data.sync.verifiedFileCache.sha256(pdf) == hash) { "The PDF changed during source verification." }
        floatArea?.let { fallbackSource = it.snapshot.sourceFile; return it }
        return MappedArea(requireNotNull(snapshot), if (stale) emptyList() else mappedLines, selected.toString(), stale)
    }

    /** The current line a resolved point was built from; null when the build line is unknown or the source changed since. */
    private fun authoredLine(resolved: SourceSnapshot): Int? =
        // Card markers added or removed since the build do not move the answer text.
        ReviewSourceMarkup.lineMapIgnoringMarkers(resolved.navigationBaseContents, resolved.contents)?.getOrNull(resolved.sourceLine - 1)

    /**
     * Links card [cardId] to the marker pairs placed by hand in [path]: one or several segments
     * (one per area), all in that file.
     */
    fun manualLink(cardId: String, path: String): Edit {
        val location = sources.annotationIndex.locate("review_card", cardId, SourceOwner.of(path).orEmpty())
        check(location.get("state")?.asString == "linked" && location.get("source_file")?.asString == path) {
            "Place one unique start/end marker pair, then wait for the source check to finish."
        }
        val snapshot = checkNotNull(sources.sourceSnapshot(path, 1))
        check(digest(snapshot.contents.toByteArray()) == location.get("source_sha256")?.asString)
        val scanned = SourceAnnotationMarkup.scan(snapshot.contents, !path.endsWith(".tex"))
        check(scanned.get("valid").asBoolean)
        val segments = checkNotNull(scanned.getAsJsonObject("reviews").getAsJsonArray(cardId)).map { it.asJsonObject }
        check(segments.size in 1..20 && segments.map { it.get("segment_id")?.asString }.distinct().size == segments.size)
        val unmarked = ReviewSourceMarkup.withoutCardMarkers(path, snapshot.contents, cardId)
        return Edit(path, unmarked, snapshot.contents, ReviewSourceMarkup.anchor(path, snapshot.contents, cardId),
            base = snapshot.baseContents ?: unmarked)
    }

    fun deletion(cardId: String, anchor: JsonObject, deleteText: Boolean): Edit =
        markerDeletion(sources, objects, cardId, anchor, deleteText)

    /** Retries a deleted card's marker removal left pending; queues the edit like any phone source save. */
    fun retryMarkerRemoval(cardId: String, anchor: JsonObject): Boolean =
        retryMarkerRemoval(sources, cardId, anchor) { snapshot, contents ->
            SourceEditQueue(context, scheduleRequests = false).saveForNextSync(
                snapshot, checkNotNull(snapshot.bundlePdfFilename), checkNotNull(snapshot.bundlePdfSha256), contents,
            )
        }

    /**
     * Applies [edit] to the working source and saves [record] with its source_change, as one
     * recoverable transaction. A card creation or deletion event uses its own id; every later
     * change of a card's markers needs a fresh [transactionId], since Fedora keeps each one.
     */
    override fun commit(edit: Edit, record: JsonObject, destination: File, transactionId: String?): Unit = synchronized(lock) {
        require(edit.before.toByteArray().size <= SourceEditQueue.MAX_SOURCE_BYTES && edit.after.toByteArray().size <= SourceEditQueue.MAX_SOURCE_BYTES)
        val current = requireNotNull(sources.sourceWorkingFile(edit.path))
        check(current.readText().let { it == edit.before || it == edit.after }) { "The source changed before the review edit." }
        val before = digest(edit.before.toByteArray()); val after = digest(edit.after.toByteArray())
        for ((hash, text) in listOf(before to edit.before, after to edit.after, digest(edit.base.toByteArray()) to edit.base)) atomic(File(objects, hash), text.toByteArray())
        record.addProperty("source_authority", "client-v1")
        val id = transactionId ?: record.get("event_id")?.asString ?: record.get("card_id").asString
        record.add("source_change", JsonObject().apply {
            addProperty("transaction_id", id); addProperty("base_sha256", digest(edit.base.toByteArray()))
            addProperty("path", edit.path); addProperty("before_sha256", before); addProperty("after_sha256", after)
            if (edit.deleteText) addProperty("delete_text", true)
        })
        UUID.fromString(id)
        val journal = File(root, "$id.json")
        atomic(journal, JsonObject().apply {
            addProperty("path", edit.path); addProperty("before_sha256", before); addProperty("after_sha256", after)
            addProperty("destination", destination.absolutePath); add("record", record)
        }.toString().toByteArray())
        check(sources.writeWorkingContents(edit.path, edit.after)) { "Source edit remains saved for recovery." }
        if (persistRecord != null) persistRecord.invoke(record, destination)
        else com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore(context).saveSourceRecord(record, destination)
        sources.recordLocalReviewChange(edit.path, after)
        check(journal.delete()) { "Source edit is complete but recovery cleanup is pending." }
    }

    override fun hasRecoveryRecord(id: String): Boolean = File(root, "$id.json").isFile

    fun requireRecoveryComplete() {
        check(root.listFiles { it.extension == "json" }.orEmpty().isEmpty()) { "An interrupted review source edit needs attention before Sync." }
    }

    fun recover(transportRoot: File) = synchronized(lock) {
        for (journal in root.listFiles { f -> f.extension == "json" }.orEmpty()) {
            // Leave an interrupted operation intact when an external edit needs attention.
            runCatching {
                val value = JsonParser.parseString(journal.readText()).asJsonObject
                val destination = File(value.get("destination").asString)
                require(destination.canonicalPath.startsWith(transportRoot.canonicalPath + File.separator))
                val record = value.getAsJsonObject("record")
                val id = record.getAsJsonObject("source_change")?.get("transaction_id")?.asString
                    ?: record.get("event_id")?.asString ?: record.get("card_id").asString
                require(journal.name == "$id.json"); UUID.fromString(id)
                val path = value.get("path").asString
                val before = value.get("before_sha256").asString; val after = value.get("after_sha256").asString
                require(listOf(before, after).all { it.matches(Regex("[0-9a-f]{64}")) })
                val change = record.getAsJsonObject("source_change")
                require(change.get("path").asString == path && change.get("before_sha256").asString == before && change.get("after_sha256").asString == after)
                val bytes = File(objects, after).readBytes(); require(digest(bytes) == after)
                val current = requireNotNull(sources.sourceWorkingFile(path)).readBytes()
                when (digest(current)) {
                    before -> check(sources.writeWorkingContents(path, bytes.toString(Charsets.UTF_8)))
                    after -> Unit
                    else -> error("Review source recovery needs attention")
                }
                if (persistRecord != null) persistRecord.invoke(record, destination)
                else com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore(context).saveSourceRecord(record, destination)
                sources.recordLocalReviewChange(path, after)
                check(journal.delete())
            }.onFailure { android.util.Log.w("ThoughtsReview", "Review source recovery pending: ${journal.name}", it) }
        }
    }
}

/** Conservative source edits: ambiguity is an explicit manual-link outcome. */
internal object ReviewSourceMarkup {
    /** Letters and digits a hyphen-tolerant match needs, so a few words cannot match by chance. */
    private const val MIN_LETTER_MATCH = 24
    private fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ")
    private data class SourceText(val value: String, val starts: IntArray, val ends: IntArray)

    private fun searchable(source: String): SourceText {
        val value = StringBuilder(source.length)
        val starts = IntArray(source.length)
        val ends = IntArray(source.length)
        var index = 0
        var count = 0
        while (index < source.length) {
            val first = index
            if (source[index].isWhitespace()) {
                while (index < source.length && source[index].isWhitespace()) index++
                value.append(' ')
            } else {
                value.append(source[index])
                index++
            }
            starts[count] = first
            ends[count] = index
            count++
        }
        return SourceText(value.toString(), starts, ends)
    }

    /**
     * Marks one passage as segment [segmentId] of card [id]. A card's later passages are marked
     * in a source that already holds its earlier ones ([otherSegments]); none may repeat.
     */
    fun create(
        path: String, source: String, selected: String, id: String, mappedLines: List<Int> = emptyList(),
        segmentId: String = "0", otherSegments: Boolean = false,
    ): LocalReviewSources.Edit {
        UUID.fromString(id)
        require(segmentId.matches(Regex("[0-9]{1,2}")) && selected.isNotBlank())
        require(if (otherSegments) !source.contains("{$id}{$segmentId}") && !source.contains("review:$id:answer:$segmentId ")
            else !source.contains(id))
        // PDF text rarely equals LaTeX/Markdown source (commands, citations, lost hyphens).
        // The verified source map already names the lines, so it decides when the text cannot.
        val byText = runCatching { createByText(path, source, selected, id, mappedLines, segmentId) }
        if (byText.isSuccess || mappedLines.isEmpty()) return byText.getOrThrow()
        return createByMappedLines(path, source, id, mappedLines, segmentId)
    }

    /**
     * [source] without any marker of card [id], keeping the text: every segment the card has in it
     * removed. Unpaired or repeated markers of the card cannot be removed safely.
     */
    fun withoutCardMarkers(path: String, source: String, id: String): String {
        if (!source.contains(id)) return source
        val incomplete = "This card's markers in the source are incomplete or repeated. Fix them with Edit placement, then try again."
        val scan = SourceAnnotationMarkup.scan(source, !path.endsWith(".tex"))
        check(scan.get("valid")?.asBoolean == true) { incomplete }
        val segments = scan.getAsJsonObject("reviews")?.getAsJsonArray(id)?.map { it.asJsonObject }.orEmpty()
        var after = source
        for (segment in segments) after = runCatching { remove(path, after, id, segment, false).after }.getOrElse { error(incomplete) }
        check(!after.contains(id)) { incomplete }
        return after
    }

    /**
     * Marks each of [areas] as its own segment of card [id] ("0".."n-1" in area order) in
     * [source], after removing the markers the card already has there (placing a linked card
     * again). Fedora accepts the edit only if removing the card's markers from the result gives
     * back [source] without its old ones; this is checked before returning.
     *
     * A box (not a text selection) whose lines lie in a TeX float marks the whole float. An area
     * that cannot be marked fails with [ReviewAreaLinkingException] naming it; nothing is marked.
     */
    fun placeAreas(path: String, source: String, id: String, areas: List<MappedSourceArea>): LocalReviewSources.Edit {
        UUID.fromString(id)
        require(areas.size in 1..20) { "Select between one and twenty areas." }
        fun fail(index: Int, reason: String?): Nothing =
            throw ReviewAreaLinkingException(index, areas.size, 0, reason?.takeIf(String::isNotBlank) ?: "The source could not be marked.")
        val markdown = !path.endsWith(".tex")
        val unmarked = try { withoutCardMarkers(path, source, id) } catch (error: Exception) { fail(0, error.message) }
        // Lines mapped in the current source, which may still hold the card's old markers.
        val lineMap = if (unmarked == source) null
            else lineMapIgnoringMarkers(source, unmarked) ?: fail(0, "The card's old markers could not be removed cleanly.")
        fun current(line: Int): Int = lineMap?.getOrNull(line - 1) ?: line
        class Placed(val range: IntRange, val wholeFloat: Boolean)
        val placed = areas.mapIndexed { index, area ->
            if (area.stale || area.lines.isEmpty()) {
                if (area.partial) fail(index, PARTIAL_AREA)
                return@mapIndexed null
            }
            val lines = area.lines.map(::current)
            val range = lines.min()..lines.max()
            val float = if (!markdown && area.rectangle) TexFloats.cover(unmarked, range) else null
            if (area.partial && float == null) fail(index, PARTIAL_AREA)
            Placed(float ?: range, float != null)
        }
        // Each area must be its own passage: no shared or touching lines.
        val known = placed.indices.filter { placed[it] != null }.sortedBy { placed[it]!!.range.first }
        known.zipWithNext().forEach { (upper, lower) ->
            if (placed[upper]!!.range.last >= placed[lower]!!.range.first) {
                fail(maxOf(upper, lower), "Its source lines overlap those of area ${minOf(upper, lower) + 1}. Select separate passages.")
            }
        }
        var contents = unmarked
        // Mark from the bottom of the file up, so each area's verified line numbers stay valid.
        for (index in known.reversed()) {
            val area = areas[index]
            val place = placed[index]!!
            contents = try {
                if (place.wholeFloat) createByMappedLines(path, contents, id, listOf(place.range.first, place.range.last), index.toString()).after
                else create(path, contents, area.text, id, area.lines.map(::current), segmentId = index.toString(),
                    otherSegments = contents != unmarked).after
            } catch (error: ReviewAreaLinkingException) {
                throw error
            } catch (error: Exception) {
                fail(index, error.message)
            }
        }
        // Areas from a PDF built before the source changed: their text, found once, marks them.
        for (index in areas.indices.filter { placed[it] == null }) {
            contents = try {
                create(path, contents, areas[index].text, id, emptyList(), segmentId = index.toString(), otherSegments = contents != unmarked).after
            } catch (error: Exception) {
                fail(index, "The source changed since this PDF was built, and the highlighted text was not found once in the current source.")
            }
        }
        val anchor = try { anchor(path, contents, id) } catch (error: Exception) { fail(0, error.message) }
        val segments = anchor.getAsJsonArray("segments").map { it.asJsonObject }
        if (segments.size != areas.size) fail(0, "The marked source does not hold one passage per area.")
        val spans = segments.map { it.get("segment_id").asString.toInt() to (it.get("line_start").asInt..it.get("line_end").asInt) }
            .sortedBy { it.second.first }
        spans.zipWithNext().forEach { (upper, lower) ->
            if (upper.second.last >= lower.second.first) {
                fail(maxOf(upper.first, lower.first), "Its source text lies in the passage of area ${minOf(upper.first, lower.first) + 1}. Select separate passages.")
            }
        }
        // Fedora's rule: only this card's markers moved.
        check(withoutCardMarkers(path, contents, id).trimEnd('\r', '\n') == unmarked.trimEnd('\r', '\n')) {
            "Placing the card's markers changed other source text."
        }
        return LocalReviewSources.Edit(path, source, contents, anchor)
    }

    private const val PARTIAL_AREA = "Part of it has no source text (an image, or text generated during the build). " +
        "Draw the area around text only, or around a whole figure or table."

    private fun exactTextMatches(source: String, selected: String): List<Pair<Int, Int>> {
        val wanted = normalized(selected)
        val searchable = searchable(source)
        val matches = mutableListOf<Pair<Int, Int>>()
        var match = searchable.value.indexOf(wanted)
        while (match >= 0) {
            val end = match + wanted.length
            val firstBoundary = match == 0 || !searchable.value[match - 1].isLetterOrDigit() || !wanted.first().isLetterOrDigit()
            val lastBoundary = end == searchable.value.length || !searchable.value[end].isLetterOrDigit() || !wanted.last().isLetterOrDigit()
            if (firstBoundary && lastBoundary) matches += searchable.starts[match] to searchable.ends[end - 1]
            match = searchable.value.indexOf(wanted, match + 1)
        }
        return matches
    }

    /**
     * PDF text splits words with line-end hyphens ("iden- tify") and joins source lines, so a
     * selection over several lines rarely equals its source. Compare letters and digits only, then
     * extend over the punctuation the selection starts or ends with.
     */
    private fun letterTextMatches(source: String, selected: String): List<Pair<Int, Int>> {
        val text = java.text.Normalizer.normalize(selected, java.text.Normalizer.Form.NFKC).trim()
        val wanted = text.filter(Char::isLetterOrDigit)
        if (wanted.length < MIN_LETTER_MATCH) return emptyList()
        val positions = source.indices.filter { source[it].isLetterOrDigit() }
        val letters = String(CharArray(positions.size) { source[positions[it]] })
        val lead = text.takeWhile { !it.isLetterOrDigit() && !it.isWhitespace() }
        val tail = text.takeLastWhile { !it.isLetterOrDigit() && !it.isWhitespace() }
        val matches = mutableListOf<Pair<Int, Int>>()
        var match = letters.indexOf(wanted)
        while (match >= 0) {
            var first = positions[match]
            var last = positions[match + wanted.length - 1] + 1
            if (first > 0 && source[first - 1].isLetterOrDigit() || last < source.length && source[last].isLetterOrDigit()) {
                match = letters.indexOf(wanted, match + 1); continue
            }
            if (lead.isNotEmpty() && source.startsWith(lead, first - lead.length)) first -= lead.length
            if (tail.isNotEmpty() && source.startsWith(tail, last)) last += tail.length
            matches += first to last
            match = letters.indexOf(wanted, match + 1)
        }
        return matches
    }

    private fun createByText(path: String, source: String, selected: String, id: String, mappedLines: List<Int>, segmentId: String): LocalReviewSources.Edit {
        val matches = exactTextMatches(source, selected).ifEmpty { letterTextMatches(source, selected) }
        val (first, last) = matches.singleOrNull() ?: error(
            if (matches.isEmpty()) "The highlighted text was not found in the source. Place the markers manually."
            else "The highlighted text appears several times in the source. Place the markers manually."
        )
        val firstLine = source.take(first).count { it == '\n' } + 1
        val lastLine = source.take(last - 1).count { it == '\n' } + 1
        require(mappedLines.all { it in (firstLine - 1).coerceAtLeast(1)..lastLine + 1 }) {
            "The highlighted passage does not match its verified source location."
        }
        val body = source.substring(first, last)
        require(!Regex("[\\\\{}%]|^\\s*[#>`~*_-]", RegexOption.MULTILINE).containsMatchIn(body)) { "Place markers manually around structured source text." }
        if (path.endsWith(".tex")) {
            // The marker pair must stay outside commands and hidden environments.
            val prefix = source.substring(0, first)
            val active = prefix.lines().joinToString("\n") { it.substringBefore('%') }
            require(active.count { it == '{' } == active.count { it == '}' }) { "The selection is inside a source command." }
            val environments = mutableListOf<String>()
            Regex("\\\\(begin|end)\\{([^}]+)\\}").findAll(active).forEach { m ->
                if (m.groupValues[1] == "begin") environments += m.groupValues[2]
                else { require(environments.lastOrNull() == m.groupValues[2]); environments.removeAt(environments.lastIndex) }
            }
            require(environments.all { it in setOf("document", "itemize", "enumerate", "description") }) { "The source structure needs manual markers." }
        }
        val markdown = !path.endsWith(".tex")
        if (markdown) require((first == 0 || source[first - 1] == '\n') && (last == source.length || source[last] == '\n')) {
            "Select complete Markdown blocks before placing source markers."
        }
        if (markdown) require(source.substring(0, first).lineSequence().count { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("```") || trimmed.startsWith("~~~")
        } % 2 == 0) { "Place markers outside Markdown code fences." }
        val opening = if (markdown) "<!-- thoughts:v1:start review:$id:answer:$segmentId -->\n" else "\\ReviewCardMaskStart{$id}{$segmentId}"
        val closing = if (markdown) "\n<!-- thoughts:v1:end review:$id:answer:$segmentId -->" else "\\ReviewCardMaskEnd{$id}{$segmentId}"
        val after = source.substring(0, first) + opening + body + closing + source.substring(last)
        return anchored(path, source, after, id, markdown, firstLine, lastLine + if (markdown) 2 else 0, segmentId)
    }

    private val inlineCardMarker = Regex("\\\\ReviewCardMask(Start|End)\\s*\\{[^}]*\\}\\s*\\{[^}]*\\}%?|<!--\\s*thoughts:v1:(start|end)\\s+review:[^>]*-->")

    private fun isCardMarkerLine(line: String) = texMarkerLine.matches(line) || markdownMarkerLine.matches(line)

    /**
     * Maps each 1-based line of the source a PDF was built from to the current source, when the
     * two differ only by review card markers (own-line or inline). Any other edit returns null.
     */
    fun lineMapIgnoringMarkers(base: String?, current: String): IntArray? {
        if (base == null) return null
        val before = base.split('\n')
        val after = current.split('\n')
        val map = IntArray(before.size)
        var i = 0
        var j = 0
        while (j < before.size) {
            when {
                i < after.size && after[i] == before[j] -> { map[j++] = ++i }
                i < after.size && isCardMarkerLine(after[i]) -> i++
                isCardMarkerLine(before[j]) -> { map[j++] = i.coerceAtLeast(1) }
                i < after.size && after[i].replace(inlineCardMarker, "") == before[j].replace(inlineCardMarker, "") -> { map[j++] = ++i }
                else -> return null
            }
        }
        while (i < after.size && isCardMarkerLine(after[i])) i++
        return map.takeIf { i == after.size }
    }

    private val texMarkerLine = Regex("^\\s*\\\\ReviewCardMask(Start|End)\\s*\\{([^}]*)\\}\\s*\\{[^}]*\\}\\s*%?\\s*$")
    private val markdownMarkerLine = Regex("^\\s*<!--\\s*thoughts:v1:(start|end)\\s+review:([^:\\s]+):\\S*\\s*-->\\s*$")

    /**
     * Marks lines [first]..[last] of [source] (a section's body, [ReviewSections.sourceSection]) as
     * the one passage of card [id]: whole lines, the markers on lines of their own. Other cards'
     * passages inside stay whole (the cards nest), never cut.
     */
    fun placeSection(path: String, source: String, id: String, first: Int, last: Int): LocalReviewSources.Edit {
        UUID.fromString(id)
        require(!source.contains(id)) { "The source selection or card identity is invalid." }
        require(first in 1..last) { "The section has no text below its heading." }
        return createByMappedLines(path, source, id, listOf(first, last), "0", wholeLines = true)
    }

    /**
     * Whole source lines named by the verified PDF-to-source map, with markers on their own lines.
     * A Markdown passage grows to its whole paragraphs, unless [wholeLines] (a section's body).
     */
    private fun createByMappedLines(path: String, source: String, id: String, mappedLines: List<Int>, segmentId: String,
                                    wholeLines: Boolean = false): LocalReviewSources.Edit {
        val markdown = !path.endsWith(".tex")
        val lines = source.split('\n')
        require(mappedLines.all { it in 1..lines.size }) { "The highlighted passage does not match its verified source location." }
        var lo = mappedLines.min()
        var hi = mappedLines.max()
        if (markdown && !wholeLines) {
            while (lo > 1 && lines[lo - 2].isNotBlank()) lo--
            while (hi < lines.size && lines[hi].isNotBlank()) hi++
        }
        val markerLine = if (markdown) markdownMarkerLine else texMarkerLine
        fun marker(line: Int) = markerLine.find(lines[line - 1])?.let { it.groupValues[1].lowercase() to it.groupValues[2] }
        // Another passage of this card must stay separate, never nested in or touching this one.
        require(((lo - 1).coerceAtLeast(1)..(hi + 1).coerceAtMost(lines.size)).none { marker(it)?.second == id }) {
            "Two selected areas map to overlapping source lines. Select separate passages."
        }
        // Covering another card's whole passage also covers its adjacent marker lines, so the cards nest.
        while (true) {
            val inside = (lo..hi).mapNotNull(::marker)
            val before = if (lo > 1) marker(lo - 1) else null
            val next = if (hi < lines.size) marker(hi + 1) else null
            when {
                before?.first == "start" && ("end" to before.second) in inside && ("start" to before.second) !in inside -> lo--
                next?.first == "end" && ("start" to next.second) in inside && ("end" to next.second) !in inside -> hi++
                else -> break
            }
        }
        // Every other card's passages inside are whole: each start has its end after it, inside.
        require((lo..hi).mapNotNull(::marker).groupBy({ it.second }, { it.first }).values.all { kinds ->
            var open = 0
            kinds.all { kind -> open += if (kind == "start") 1 else -1; open >= 0 } && open == 0
        }) {
            "The highlight partly overlaps another card's passage. Place the markers manually."
        }
        val prefix = lines.subList(0, lo - 1).joinToString("\n")
        val body = lines.subList(lo - 1, hi).joinToString("\n")
        require(body.isNotBlank()) { "The highlighted passage is empty in the source." }
        if (markdown) {
            fun fences(text: String) = text.lineSequence().count { it.trimStart().startsWith("```") || it.trimStart().startsWith("~~~") }
            require(fences(prefix) % 2 == 0 && fences(body) % 2 == 0) { "Place markers outside Markdown code fences." }
        } else {
            require(texGroupDepth(prefix) == 0) { "The selection is inside a source command." }
            require(texGroupDepth(body) == 0) { "The highlighted lines split a source command. Place the markers manually." }
            val open = texEnvironments(prefix)
            require(open.all { it in setOf("document", "itemize", "enumerate", "description") }) { "The source structure needs manual markers." }
            require(texEnvironmentsBalanced(body)) { "The highlighted lines split an environment. Place the markers manually." }
        }
        val opening = if (markdown) "<!-- thoughts:v1:start review:$id:answer:$segmentId -->" else "\\ReviewCardMaskStart{$id}{$segmentId}%"
        val closing = if (markdown) "<!-- thoughts:v1:end review:$id:answer:$segmentId -->" else "\\ReviewCardMaskEnd{$id}{$segmentId}%"
        val out = lines.toMutableList()
        out.add(hi, closing)
        out.add(lo - 1, opening)
        return anchored(path, source, out.joinToString("\n"), id, markdown, lo, hi + 2, segmentId)
    }

    /** TeX text without comments; escaped characters are kept but never counted as syntax. */
    private fun texActive(text: String): String = text.lineSequence().joinToString("\n") { line ->
        var escaped = false
        val cut = line.indices.firstOrNull { i ->
            val comment = line[i] == '%' && !escaped
            escaped = line[i] == '\\' && !escaped
            comment
        }
        if (cut == null) line else line.substring(0, cut)
    }

    private fun texGroupDepth(text: String): Int {
        var depth = 0
        var escaped = false
        for (c in texActive(text)) {
            if (!escaped && c == '{') depth++
            if (!escaped && c == '}') { depth--; if (depth < 0) return -1 }
            escaped = c == '\\' && !escaped
        }
        return depth
    }

    private fun texEnvironments(text: String): List<String> {
        val environments = mutableListOf<String>()
        Regex("\\\\(begin|end)\\{([^}]+)\\}").findAll(texActive(text)).forEach { m ->
            if (m.groupValues[1] == "begin") environments += m.groupValues[2]
            else { require(environments.lastOrNull() == m.groupValues[2]) { "The source structure needs manual markers." }; environments.removeAt(environments.lastIndex) }
        }
        return environments
    }

    private fun texEnvironmentsBalanced(text: String): Boolean = runCatching { texEnvironments(text).isEmpty() }.getOrDefault(false)

    private fun anchored(path: String, source: String, after: String, id: String, markdown: Boolean, lineStart: Int, lineEnd: Int, segmentId: String = "0"): LocalReviewSources.Edit {
        val scan = SourceAnnotationMarkup.scan(after, markdown)
        require(scan.get("valid").asBoolean)
        val evidence = scan.getAsJsonObject("reviews").getAsJsonArray(id).map { it.asJsonObject }
            .single { it.get("segment_id")?.asString == segmentId }
        val anchor = JsonObject().apply {
            addProperty("version", 2)
            add("segments", JsonArray().apply { add(evidence.deepCopy().apply {
                addProperty("source_file", path); addProperty("source_sha256", LocalReviewSources.digest(after.toByteArray()))
                addProperty("line_start", lineStart); addProperty("line_end", lineEnd)
            }) })
        }
        return LocalReviewSources.Edit(path, source, after, anchor)
    }

    /** The version-2 anchor of every segment card [id] has in [source], in segment order. */
    fun anchor(path: String, source: String, id: String): JsonObject {
        val markdown = !path.endsWith(".tex")
        val scan = SourceAnnotationMarkup.scan(source, markdown)
        require(scan.get("valid").asBoolean) { "The marked source is invalid." }
        val evidence = scan.getAsJsonObject("reviews").getAsJsonArray(id).map { it.asJsonObject }
        // Locations and evidence are recorded together at each end marker, so they align.
        val locations = scan.getAsJsonObject("locations").getAsJsonArray("review_card\n$id").map { it.asJsonObject }
        require(evidence.size == locations.size)
        val sha = LocalReviewSources.digest(source.toByteArray())
        return JsonObject().apply {
            addProperty("version", 2)
            add("segments", JsonArray().apply {
                evidence.zip(locations).sortedBy { it.first.get("segment_id").asString.toInt() }.forEach { (item, location) ->
                    add(item.deepCopy().apply {
                        addProperty("source_file", path); addProperty("source_sha256", sha)
                        add("line_start", location.get("start_line")); add("line_end", location.get("end_line"))
                    })
                }
            })
        }
    }

    fun remove(path: String, source: String, id: String, segment: JsonObject, deleteText: Boolean): LocalReviewSources.Edit {
        val markdown = !path.endsWith(".tex")
        val scan = SourceAnnotationMarkup.scan(source, markdown)
        require(scan.get("valid").asBoolean) { "Marker removal pending" }
        val evidence = scan.getAsJsonObject("reviews").getAsJsonArray(id)?.map { it.asJsonObject }
            ?.singleOrNull { it.get("segment_id") == segment.get("segment_id") } ?: error("Marker removal pending")
        if (deleteText) require(evidence.get("content_sha256") == segment.get("content_sha256")) { "The enclosed text changed. Reopen the card before deleting it." }
        val identity = Regex.escape(id); val sid = Regex.escape(segment.get("segment_id").asString)
        val pattern = if (markdown) Regex("<!--\\s*thoughts:v1:(start|end)\\s+review:$identity:answer:$sid\\s*-->")
            else Regex("\\\\ReviewCardMask(Start|End)\\s*\\{$identity\\}\\s*\\{$sid\\}")
        val markers = pattern.findAll(source).toList()
        require(markers.size == 2 && markers.first().groupValues[1].equals("start", true) && markers.last().groupValues[1].equals("end", true))
        if (deleteText) {
            val body = source.substring(markers.first().range.last + 1, markers.last().range.first)
            require(!body.contains("\\ReviewCardMask") && !body.contains("thoughts:v1:")) { "Another marker overlaps this passage." }
            val locations = scan.getAsJsonObject("locations")
            require(locations.entrySet().filter { it.key.startsWith("review_card\n") && it.key != "review_card\n$id" }.none { (_, entries) ->
                entries.asJsonArray.any { entry -> val item = entry.asJsonObject
                    item.get("start_line").asInt <= source.take(markers.last().range.last).count { it == '\n' } + 1 &&
                    item.get("end_line").asInt >= source.take(markers.first().range.first).count { it == '\n' } + 1 }
            }) { "Another review overlaps this passage." }
        }
        val ranges = if (deleteText) listOf(markers.first().range.first until markers.last().range.last + 1) else markers.map { it.range }
        var after = source
        for (range in ranges.sortedByDescending { it.first }) {
            var first = range.first; var end = range.last + 1
            val line = source.lastIndexOf('\n', first - 1) + 1
            val suffix = Regex("^[ \\t]*%?[ \\t]*(?:\\r?\\n|$)").find(source.substring(end))
            if (source.substring(line, first).isBlank() && suffix != null) { first = line; end += suffix.value.length }
            after = after.removeRange(first, end)
        }
        return LocalReviewSources.Edit(path, source, after, deleteText = deleteText)
    }
}
