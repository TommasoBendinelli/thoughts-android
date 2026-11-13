// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonObject

/**
 * `review_version` stamps on feedback that is not a review event (spec
 * thoughts-feedback-version-stamps, 2026-10-22): every record the user writes about a document says
 * which version of its PDF they were looking at, so the server keeps that PDF. The field and its
 * shape are [ReviewVersionStamp]'s; it is put on creates and edits, never on deletes, undo or redo,
 * and left out (an older one removed) when the version is not known. The hash comes from [ReviewVersionStamper] (the
 * Library's own hash, else one computed once per file and cached), never from a new hashing path.
 */
internal object FeedbackVersionStamps {
    /** Scope states of a comment tied to .tex/.md sources (for a PDF the Library does not list). */
    private val SOURCE_SCOPES = setOf("source_linked", "linking", "needs_attention")

    /**
     * Stamps a logical comment (PDF or source comment, highlight, reply) that is being created or
     * edited, in place. [viewedSha256] is the hash of the PDF the reader shows, when the caller has
     * it, and [pdfFilename] that PDF's Library filename; otherwise the comment's own document
     * (`origin.collection`/`origin.document_id`, else [pdfFilename]) is looked up. A deletion or a
     * standalone note (it is about no document) is left unchanged; otherwise the stamp replaces an
     * older one, and an unknown version removes it ([ReviewVersionStamp.put]). Never throws.
     */
    fun stampLogicalComment(
        payload: JsonObject,
        stamper: ReviewVersionStamper?,
        viewedSha256: String? = null,
        pdfFilename: String? = null,
    ): JsonObject {
        runCatching {
            if (payload.get("deleted")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return payload
            val scope = payload.obj("scope")
            if (payload.string("subtype") == "Note" && scope?.string("state") == "standalone") return payload
            val origin = payload.obj("origin")
            val originStem = origin?.string("collection").orEmpty()
            val fileStem = pdfFilename?.takeIf { it.endsWith(".pdf", ignoreCase = true) }?.dropLast(4).orEmpty()
            val viewed = viewedSha256?.takeIf { it.isNotBlank() }
            // A hash the reader computed belongs to the file it shows; otherwise the comment's own document.
            val stem = if (viewed != null) fileStem.ifBlank { originStem } else originStem.ifBlank { fileStem }
            val documentId = origin?.string("document_id")?.takeIf { stem == originStem }
            val stamp = stamper?.stampDocument(documentId, stem, viewed, scope?.string("state") in SOURCE_SCOPES)
            ReviewVersionStamp.put(payload, stamp)
        }
        return payload
    }

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
}
