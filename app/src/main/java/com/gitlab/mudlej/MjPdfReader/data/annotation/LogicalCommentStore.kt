// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.annotation

import android.content.Context
import android.net.Uri
import com.github.barteksc.pdfviewer.PDFView
import com.gitlab.mudlej.MjPdfReader.data.source.*
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gitlab.mudlej.MjPdfReader.data.review.FeedbackVersionStamps
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewVersionStamper

/** Canonical content and local outbox revisions are the only active comment state. */
internal class LogicalCommentStore(context: Context) {
    private val context = context.applicationContext
    private val repository = FedoraRepository(this.context)
    private val sources = OfflineSourceBundleStore(this.context)
    private val formats = CommentSidecarStore(this.context)

    fun changes(emitInitialState: Boolean = true) = merge(
        com.gitlab.mudlej.MjPdfReader.data.AppDatabase.getInstance(context)
            .invalidationTracker.createFlow("ThoughtsLocalCommentState", "ThoughtsProjection", "CommentMutationJournal", "CommentSourceState",
                emitInitialState = emitInitialState).map { Unit },
        SourceCommentChanges.changes,
    )

    companion object { internal val mutex = Mutex() }

    private val readState = com.gitlab.mudlej.MjPdfReader.data.AppDatabase.getInstance(context).commentReadStateDao()

    /** The startup read path uses current database records, without running imports. */
    suspend fun catalogueSnapshot(): OfflineOverviewSourceSnapshot {
        val startedAt = System.nanoTime()
        val current = sources.catalogueCommentSnapshot()
        val sourceMillis = (System.nanoTime() - startedAt) / 1_000_000
        val payloads = repository.effectiveLogicalCommentPayloads()
        val payloadMillis = (System.nanoTime() - startedAt) / 1_000_000 - sourceMillis
        return current.withLogicalPayloads(payloads, preferPayloads = true).also {
            com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log("comment catalogue: source rows ${sourceMillis} ms, " +
                "local payloads ${payloadMillis} ms (${payloads.size}), total ${(System.nanoTime() - startedAt) / 1_000_000} ms")
        }
    }

    suspend fun snapshot(filename: String? = null): OfflineOverviewSourceSnapshot {
        val startedAt = System.nanoTime()
        fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000
        val overview = sources.overviewSourceSnapshot(filename)
        val overviewMillis = elapsed()
        val proposals = repository.currentProposalRecords()
        val proposalMillis = elapsed()
        val source = sources.withCurrentProposals(overview, proposals, filename)
        val combinedMillis = elapsed()
        return source.withLogicalPayloads(repository.effectiveLogicalCommentPayloads(
            filename?.let(source::logicalReadScope),
        ), preferPayloads = true).also {
            if (filename == null) com.gitlab.mudlej.MjPdfReader.data.StartupTiming.log(
                "logical snapshot: overview ${overviewMillis} ms, proposals until ${proposalMillis} ms (${proposals.size}), " +
                    "combined until ${combinedMillis} ms, payloads until ${elapsed()} ms")
        }
    }

    suspend fun catalogue(): CommentCatalogueSnapshot {
        val logicalComments = catalogueSnapshot().logicalComments
        val documents = projectDocumentComments(emptyList(), emptyList(), emptyList(), emptySet(), logicalComments).documents
        return CommentCatalogueSnapshot(documents.associate { document ->
            val comments = document.comments
            document.stem.lowercase() to CommentDocumentMetadata(
                openThreads = comments.count { !it.isReply },
                lastCommentAddedMillis = comments.maxOfOrNull { com.gitlab.mudlej.MjPdfReader.core.io.parseCommentTimestamp(it.created)?.toEpochMilli() ?: 0 } ?: 0,
                searchableText = comments.joinToString("\n") { it.searchText },
                liveComments = comments.size,
                anchoredComments = comments.count { it.sourceFile.isNotBlank() },
                unanchoredComments = comments.count { it.sourceFile.isBlank() },
            )
        }, documents).also { it.logicalComments = logicalComments }
    }

    /** Import missing identities once. An old copy must never overwrite a canonical tombstone. */
    suspend fun importDocument(uri: Uri, filename: String, pdfSha256: String,
                               embedded: List<PDFView.PdfComment> = emptyList()) = mutex.withLock {
        val file = formats.sidecarFile(uri, filename)
        importMissing(filename, pdfSha256, if (file.isFile) formats.importRecords(file) else formats.importEmbedded(embedded))
    }

    /** Whether this exact PDF's embedded annotations were imported before; the same bytes hold the same annotations. */
    suspend fun embeddedImportDone(filename: String, pdfSha256: String): Boolean =
        readState.checkpoint(embeddedCheckpoint(filename)) == pdfSha256

    suspend fun markEmbeddedImported(filename: String, pdfSha256: String) =
        readState.putCheckpoint(CommentImportCheckpoint(embeddedCheckpoint(filename), pdfSha256))

    private fun embeddedCheckpoint(filename: String) = "embedded-pdf:$filename"

    /** Recovery is one-way: restored old PDF annotations cannot replace an existing logical record. */
    suspend fun importRecoveredComments(filename: String, pdfSha256: String, comments: List<PDFView.PdfComment>) = mutex.withLock {
        importMissing(filename, pdfSha256, formats.importEmbedded(comments))
    }

    suspend fun importCatalogue() = mutex.withLock {
        fun fingerprint(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val files = CommentSidecarStore.defaultSidecarRoot().listFiles().orEmpty()
            .filter { it.name.endsWith("_comments.jsonl") }
            .map { it to fingerprint(it) }
            .filter { (file, hash) -> readState.checkpoint(file.absolutePath) != hash }
        if (files.isEmpty()) return@withLock
        val known = snapshot().logicalComments.associate { it.id to JsonParser.parseString(it.payloadJson).asJsonObject }.toMutableMap()
        files.forEach { (file, signature) ->
            importMissing(file.name.removeSuffix("_comments.jsonl") + ".pdf", "0".repeat(64), formats.importRecords(file), known)
            if (signature == fingerprint(file)) {
                readState.putCheckpoint(CommentImportCheckpoint(file.absolutePath, signature))
            }
        }
    }

    private suspend fun importMissing(filename: String, pdfSha256: String, records: List<JsonObject>, existing: MutableMap<String, JsonObject>? = null) {
        if (records.isEmpty()) return
        val known = existing ?: snapshot(filename).logicalComments.associate { it.id to JsonParser.parseString(it.payloadJson).asJsonObject }.toMutableMap()
        val missing = records.filter { raw ->
            val prior = known[raw.get("id")?.asString]
            prior == null || (prior.get("deleted")?.asBoolean != true && prior.get("origin_id")?.asString == "source-marker-compatibility")
        }
        val ordered = orderCommentCreations(missing, known.keys,
            { it.get("id").asString }, { it.get("in_reply_to")?.asString.orEmpty() })
        val mutations = ordered.mapNotNull { raw ->
            val value = raw.deepCopy()
            value.addProperty("document_id", DocumentIdentityStore().identifier(filename))
            value.addProperty("pdf_sha256", raw.get("annotation_pdf_sha256")?.asString?.takeIf { it.length == 64 }
                ?: raw.get("pdf_sha256")?.asString?.takeIf { it.length == 64 } ?: pdfSha256)
            val parent = known[value.get("in_reply_to")?.asString]
            if (parent?.get("deleted")?.asBoolean == true) value.addProperty("deleted", true)
            canonicalCommentForAnnotation(value, filename.removeSuffix(".pdf"), null, parent)?.also {
                known[it.get("comment_id").asString] = it
            }
        }
        repository.enqueueLogicalComments(mutations)
    }

    /**
     * Stamps created and edited comments with the version of the PDF they are about
     * ([FeedbackVersionStamps]); the phone's Library, replaceable in tests.
     */
    internal var versionStamper: ReviewVersionStamper? = runCatching { ReviewVersionStamper.library() }.getOrNull()

    /**
     * Writes [transform] of a live comment (an edit, or a reply built from it). [stamp] records the
     * version of the comment's PDF on the result; undo and redo of an earlier edit pass false.
     */
    suspend fun update(commentId: String, filename: String? = null, stamp: Boolean = true, transform: (JsonObject) -> JsonObject) = mutex.withLock {
        val current = snapshot(filename).logicalComments.firstOrNull { it.id == commentId && !it.deleted }
            ?: error("Comment is unavailable: $commentId")
        val payload = transform(JsonParser.parseString(current.payloadJson).asJsonObject.deepCopy())
        // A PDF the Library does not vouch for may have to be hashed: never on the caller's thread.
        if (stamp) withContext(Dispatchers.IO) { FeedbackVersionStamps.stampLogicalComment(payload, versionStamper, pdfFilename = filename) }
        repository.enqueueLogicalComment(payload)
    }

    /**
     * A root comment made from a selection in the HTML view (thoughts-html-view-SPEC.md, "New cards
     * and comments from the HTML view"): it has no PDF geometry. Its origin records `created_in:
     * "html"`, the edition, the text quote and (exact Markdown selections) the source range; Fedora
     * inserts its marker at the next Sync. Returns the comment id.
     */
    suspend fun createHtmlComment(
        filename: String,
        pdfSha256: String,
        html: JsonObject,
        contents: String,
        author: String,
        color: Int,
        createdDate: String,
        selectedText: String,
        priority: CommentPriority = CommentPriority.NORMAL,
        location: CommentLocation? = null,
        needsAction: Boolean = false,
    ): String = mutex.withLock {
        require(pdfSha256.matches(Regex("[0-9a-f]{64}"))) { "The PDF identity is unavailable" }
        require(html.get("created_in")?.asString == "html") { "Not a selection from the HTML view" }
        val id = CommentCreationMetadata.newThoughtsId()
        val edit = AnnotationEdit.AddPdfMarkupComment(0, id, emptyList(), color, contents, author, createdDate, createdDate,
            selectedText, emptyList(), priority, location, needsAction = needsAction)
        val raw = formats.encodeEdits(listOf(edit), pdfSha256).single { it.get("id")?.asString == id }
        raw.addProperty("document_id", DocumentIdentityStore().identifier(filename))
        raw.addProperty("pdf_sha256", pdfSha256)
        val payload = requireNotNull(canonicalCommentForAnnotation(raw, filename.removeSuffix(".pdf"), null, null))
        payload.addProperty("needs_action", needsAction)
        payload.add("scope", JsonObject().apply {
            addProperty("state", "pdf_only")
            addProperty("reason", HTML_PENDING_SERVER_LINK)
        })
        payload.getAsJsonObject("origin").apply {
            listOf("page_index", "geometry_format", "raw_pdf_geometry").forEach(::remove)
            add("rect", com.google.gson.JsonArray())
            add("fragments", com.google.gson.JsonArray())
            html.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
        }
        FeedbackVersionStamps.stampLogicalComment(payload, versionStamper, pdfSha256, filename)
        repository.enqueueLogicalComments(listOf(payload))
        id
    }

    /** The viewer may use PDF-shaped commands, but no action writes a second comment database. */
    data class DocumentEdits(val filename: String, val pdfSha256: String, val edits: List<AnnotationEdit>)

    suspend fun edit(filename: String, pdfSha256: String, edits: List<AnnotationEdit>) =
        editDocuments(listOf(DocumentEdits(filename, pdfSha256, edits)))

    suspend fun editDocuments(documents: List<DocumentEdits>) = mutex.withLock {
        // New and edited comments record the PDF the reader shows ([DocumentEdits.pdfSha256], hashed
        // when it was opened); deletions are left unstamped by the stamper.
        val changes = documents.flatMap { document ->
            prepareEdits(document.filename, document.pdfSha256, document.edits).onEach {
                FeedbackVersionStamps.stampLogicalComment(it, versionStamper, document.pdfSha256, document.filename)
            }
        }
        require(changes.map { it.get("comment_id").asString }.distinct().size == changes.size) {
            "A comment cannot be edited in multiple documents in one operation"
        }
        repository.enqueueLogicalComments(changes)
    }

    private suspend fun prepareEdits(filename: String, pdfSha256: String, edits: List<AnnotationEdit>): List<JsonObject> {
        require(pdfSha256.matches(Regex("[0-9a-f]{64}"))) { "The PDF identity is unavailable" }
        require(edits.all(AnnotationEdit::isPdfCommentEdit))
        val known = snapshot(filename).logicalComments.associate { it.id to JsonParser.parseString(it.payloadJson).asJsonObject }.toMutableMap()
        val raw = formats.encodeEdits(edits, pdfSha256)
        val creates = edits.mapNotNull { edit -> when (edit) {
            is AnnotationEdit.AddPdfComment -> edit.name
            is AnnotationEdit.AddPdfMarkupComment -> edit.name
            is AnnotationEdit.AddPdfReply -> edit.name
            else -> null
        } }.toSet()
        check(creates.none { it in known }) { "A comment with this identity already exists" }
        val changes = linkedMapOf<String, JsonObject>()
        orderCommentCreations(raw.filter { it.get("id").asString in creates }, known.keys,
            { it.get("id").asString }, { it.get("in_reply_to")?.asString.orEmpty() }).forEach { value ->
            value.addProperty("document_id", DocumentIdentityStore().identifier(filename))
            value.addProperty("pdf_sha256", pdfSha256)
            val payload = requireNotNull(canonicalCommentForAnnotation(value, filename.removeSuffix(".pdf"), null,
                known[value.get("in_reply_to")?.asString]))
            val id = payload.get("comment_id").asString
            val creation = edits.firstOrNull { when (it) {
                is AnnotationEdit.AddPdfComment -> it.name == id
                is AnnotationEdit.AddPdfMarkupComment -> it.name == id
                else -> false
            } }
            when (creation) {
                is AnnotationEdit.AddPdfComment -> payload.addProperty("needs_action", creation.needsAction)
                is AnnotationEdit.AddPdfMarkupComment -> payload.addProperty("needs_action", creation.needsAction)
                else -> Unit
            }
            known[id] = payload
            changes[id] = payload
        }
        fun mutable(id: String): JsonObject = changes.getOrPut(id) {
            val current = requireNotNull(known[id]) { "Comment is unavailable: $id" }
            check(current.get("deleted")?.asBoolean != true) { "This comment was deleted" }
            requireNotNull(logicalCommentMutationPayload(current, id, deleted = false))
        }
        edits.forEach { edit -> when (edit) {
            is AnnotationEdit.SetPdfCommentContents -> mutable(edit.name).addProperty("contents", edit.contents)
            is AnnotationEdit.SetPdfCommentMetadata -> mutable(edit.name).apply {
                edit.needsAction?.let { flag ->
                    val rootId = known[edit.name]?.get("thread_root_id")?.asString ?: edit.name
                    mutable(rootId).addProperty("needs_action", flag)
                }
                addProperty("priority", edit.priority.wireValue)
                keepOrReplaceCommentLocation("location", edit.location, removeWhenAbsent = false)
                edit.color?.let { color -> add("color", com.google.gson.JsonArray().apply {
                    add((color ushr 16 and 255) / 255.0); add((color ushr 8 and 255) / 255.0)
                    add((color and 255) / 255.0); add((color ushr 24 and 255) / 255.0)
                }) }
                edit.rect?.let { rect ->
                    // A user-selected placement is mutable; the original capture stays immutable.
                    add("pdf_placement", JsonObject().apply {
                        addProperty("collection", filename.removeSuffix(".pdf"))
                        addProperty("pdf_sha256", pdfSha256)
                        addProperty("geometry_format", "pdf_points_bottom_left")
                        add("raw_pdf_geometry", JsonObject().apply {
                            addProperty("page", edit.page + 1)
                            add("rect", com.google.gson.JsonArray().apply { add(rect.left); add(rect.top); add(rect.right); add(rect.bottom) })
                        })
                    })
                }
            }
            is AnnotationEdit.DeletePdfComment -> {
                val ids = mutableSetOf(edit.name)
                do {
                    val before = ids.size
                    known.forEach { (id, p) -> if (p.get("parent_id")?.takeUnless { it.isJsonNull }?.asString in ids) ids.add(id) }
                } while (ids.size != before)
                ids.forEach { id -> if (known[id]?.get("deleted")?.asBoolean != true) mutable(id).apply {
                    addProperty("deleted", true)
                    // Deliberate deletes name themselves, so automatic tombstones stay distinguishable.
                    addProperty("deletion_reason", USER_DELETION_REASON)
                } }
            }
            else -> Unit
        } }
        return changes.values.toList()
    }
}
