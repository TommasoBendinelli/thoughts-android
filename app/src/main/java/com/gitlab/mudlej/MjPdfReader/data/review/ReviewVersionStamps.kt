// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.gitlab.mudlej.MjPdfReader.data.render.LibraryPdfKind
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndex
import com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Which version of a card's PDF a review event was given on, recorded on every new answer and skip
 * as `review_version: {pdf_sha256, source_built}` so an answer or note can later be shown against
 * that version (Fedora keeps every stamped PDF object). The same top-level field, in the same shape,
 * goes on every other kind of feedback about a document when it is created or edited ([FeedbackVersionStamps]).
 * [pdfSha256] is the SHA-256 of the PDF's bytes; [sourceBuilt] tells a PDF built from .tex/.md
 * sources (it changes) from an imported one.
 */
data class ReviewVersionStamp(val pdfSha256: String, val sourceBuilt: Boolean) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("pdf_sha256", pdfSha256)
        addProperty("source_built", sourceBuilt)
    }

    companion object {
        const val FIELD = "review_version"
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * A create or edit of a feedback record: [stamp] becomes [payload]'s top-level [FIELD],
         * replacing an older one; when the version is unknown (null) an older stamp is removed, since
         * it would name a version the edit was not made on (the desktop does the same).
         */
        fun put(payload: JsonObject, stamp: ReviewVersionStamp?): JsonObject {
            if (stamp != null) payload.add(FIELD, stamp.toJson()) else payload.remove(FIELD)
            return payload
        }

        /** The event's stamp, or null when it has none or it is malformed. */
        fun of(event: JsonObject): ReviewVersionStamp? = runCatching {
            val field = event.getAsJsonObject(FIELD) ?: return null
            val sha = field.get("pdf_sha256")?.asString?.takeIf(SHA256::matches) ?: return null
            ReviewVersionStamp(sha, field.get("source_built")?.asBoolean == true)
        }.getOrNull()
    }
}

/**
 * Works out a card's [ReviewVersionStamp] from the PDF the phone shows it on ([pdfFile]); null when
 * that PDF is not on the phone. The hash is the Library's own hash of the installed PDF when the
 * Library vouches for the file ([trustedSha256]: the current generation's hash, installed with that
 * size), the hash the reader also trusts; otherwise the file is hashed once and remembered for its
 * path, size and modification time ([hashFile]). Never throws: a stamp that cannot be made is left out.
 */
internal class ReviewVersionStamper(
    private val pdfFile: (ReviewCard) -> File?,
    private val trustedSha256: (File) -> String?,
    private val sourceBuilt: (ReviewCard, File) -> Boolean,
    private val hashFile: (File) -> String? = ::computeSha256,
    /** A Library document's PDF from its document id or PDF stem (comments and other feedback). */
    private val documentFile: (documentId: String, pdfStem: String) -> File? = { _, _ -> null },
    /** Whether the Library lists the PDF as built from sources; null when it does not list it. */
    private val librarySourceBuilt: (File) -> Boolean? = { null },
) {
    /**
     * [card]'s stamp. [viewedSha256], the hash of the PDF the user is looking at when the caller
     * has it (a card drawn on the displayed PDF), is used as it is.
     */
    fun stamp(card: ReviewCard, viewedSha256: String? = null): ReviewVersionStamp? = runCatching {
        val file = pdfFile(card)?.takeIf { it.isFile }
        val sha = viewed(viewedSha256) ?: file?.let(::sha256Of) ?: return null
        ReviewVersionStamp(sha, file?.let { sourceBuilt(card, it) } ?: (card.sourceFiles.isNotEmpty() || card.sourceAnchor != null))
    }.getOrNull()

    private fun viewed(sha: String?): String? = sha?.lowercase()?.takeIf { it.matches(SHA256) && it != UNKNOWN_SHA256 }

    /**
     * The stamp for feedback on a Library document (a comment, a reply, ...), worked out like a
     * card's: [viewedSha256], the hash of the PDF the user is looking at when the caller already has
     * it (the reader's), is used as it is; otherwise the document's PDF ([documentId], else
     * [pdfStem]`.pdf`) is hashed through the same trusted-or-cached path as [stamp]. [sourceBuilt] is
     * the Library's kind of that PDF, else [sourceBuiltFallback]. Null when no version is known;
     * never throws.
     */
    fun stampDocument(
        documentId: String?,
        pdfStem: String?,
        viewedSha256: String? = null,
        sourceBuiltFallback: Boolean = false,
    ): ReviewVersionStamp? = runCatching {
        val file = documentFile(documentId.orEmpty(), pdfStem.orEmpty())?.takeIf { it.isFile }
        val sha = viewed(viewedSha256) ?: file?.let(::sha256Of) ?: return null
        ReviewVersionStamp(sha, file?.let(librarySourceBuilt) ?: sourceBuiltFallback)
    }.getOrNull()

    /** The Library's vouched hash of [file], else its hash computed once per path, size and mtime. */
    private fun sha256Of(file: File): String? =
        trustedSha256(file)?.lowercase()?.takeIf(SHA256::matches) ?: hashed(file)

    private fun hashed(file: File): String? {
        val path = file.absolutePath
        val key = FileKey(file.length(), file.lastModified())
        hashes[path]?.takeIf { it.first == key }?.let { return it.second }
        val sha = hashFile(file)?.lowercase()?.takeIf(SHA256::matches) ?: return null
        // Only remember the hash when the file did not change while it was read.
        if (FileKey(file.length(), file.lastModified()) == key) hashes[path] = key to sha
        return sha
    }

    private data class FileKey(val size: Long, val modified: Long)

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        /** The placeholder older comment records carry when their PDF's hash was not known. */
        private val UNKNOWN_SHA256 = "0".repeat(64)
        /** Hashes of PDFs read so far, per path, valid while size and modification time stay. */
        private val hashes = ConcurrentHashMap<String, Pair<FileKey, String>>()

        internal fun forgetHashesForTesting() = hashes.clear()

        /**
         * The phone's Library: a card's PDF is its document's installed file (else the Library PDF
         * named after its stem). It is source-built when the Library lists it as source-linked, or,
         * for a PDF outside the Library index, when the card is linked to source files.
         */
        fun library(): ReviewVersionStamper {
            val directory = PhoneRenderQueue.defaultCanonicalPdfDirectory()
            val index = SourceLinkedPdfIndex(directory)
            val libraryKind: (File) -> Boolean? = { file ->
                when (index.snapshot().libraryKindFor(file, directory)) {
                    LibraryPdfKind.SOURCE_LINKED -> true
                    LibraryPdfKind.UNLINKED -> false
                    null -> null
                }
            }
            fun byStem(stem: String): File? = File(directory, "$stem.pdf")
                .takeIf { stem.isNotBlank() && !stem.contains('/') && !stem.contains('\\') && stem != ".." && it.isFile }
            return ReviewVersionStamper(
                pdfFile = { card ->
                    card.pdfDocumentId.ifEmpty { card.documentId }.takeIf(String::isNotEmpty)
                        ?.let { DocumentIdentityStore(directory).fileForId(it) }
                        ?: byStem(card.pdfStem)
                },
                trustedSha256 = { file -> index.snapshot().canonicalLibraryPdfSha256(file, directory) },
                sourceBuilt = { card, file -> libraryKind(file) ?: (card.sourceFiles.isNotEmpty() || card.sourceAnchor != null) },
                // The stem names the PDF the feedback was given on; the id finds it after a rename.
                documentFile = { documentId, stem ->
                    byStem(stem) ?: documentId.takeIf(String::isNotEmpty)?.let { DocumentIdentityStore(directory).fileForId(it) }
                },
                librarySourceBuilt = libraryKind,
            )
        }
    }
}
