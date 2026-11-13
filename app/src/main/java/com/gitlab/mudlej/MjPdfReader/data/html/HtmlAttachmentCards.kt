// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import com.gitlab.mudlej.MjPdfReader.ui.links.HtmlAttachmentLink
import com.google.gson.JsonObject
import java.io.File

/**
 * Review cards inside HTML attachment pages ("option C", thoughts-html-attachment-cards-SPEC.md):
 * the standalone interactive pages linked from PDFs with `thoughts-html:<docs-folder>/…/x.html`.
 *
 * A card made in such a page is anchored by remembered text, not by source markers (an explicit
 * exception to always-linked cards, for attachments only: their text is often written by the
 * page's own scripts). Its primary has no page, rectangle, regions or PDF geometry:
 *
 * ```json
 * {"document_id": "<owner>", "pdf_stem": "<owner stem>", "pdf_sha256": "<PDF the link was in>",
 *  "created_in": "html_attachment",
 *  "html_attachment": {"path": "docs-ideas/tex/support/html/explainer.html", "sha256": "<attachment>"},
 *  "text_quote": {"exact": "…", "prefix": "…", "suffix": "…"},
 *  "page_state": {"hash": "#simulator"}, "selected_text": "…", "question": "…"}
 * ```
 *
 * and `source_link: {"state": "html_attachment"}`, a terminal state (never linked by Fedora).
 */
object HtmlAttachmentCards {
    const val CREATED_IN = "html_attachment"
    const val SOURCE_LINK_STATE = "html_attachment"
    /** Clients offer New review card in attachments only when the server advertises this. */
    const val CAPABILITY = "html_attachment_cards_v1"
    const val MAX_HASH_LENGTH = 512
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** The PDF whose link opened the page: the owner of cards made in it. */
    data class Owner(val documentId: String, val pdfFilename: String, val pdfSha256: String) {
        val pdfStem: String get() = pdfFilename.removeSuffix(".pdf")
        val valid: Boolean get() = pdfFilename.matches(SAFE_PDF) && SHA256.matches(pdfSha256)

        companion object {
            private val SAFE_PDF = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.pdf")
        }
    }

    /** Where a card's answer is: the attachment (path + the version it was made on), the quote and the page state. */
    data class Anchor(val path: String, val sha256: String, val quote: HtmlReaderModel.TextQuote, val pageHash: String) {
        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("created_in", CREATED_IN)
            add("html_attachment", JsonObject().apply { addProperty("path", path); addProperty("sha256", sha256) })
            add("text_quote", quote.toJson())
            add("page_state", JsonObject().apply { addProperty("hash", pageHash) })
        }

        companion object {
            /** The anchor of a card's primary, or null when it is not a valid attachment card. */
            fun from(primary: JsonObject?): Anchor? = runCatching {
                if (primary?.get("created_in")?.takeIf { it.isJsonPrimitive }?.asString != CREATED_IN) return null
                val attachment = primary.getAsJsonObject("html_attachment") ?: return null
                val path = attachment.get("path")?.asString.orEmpty()
                val sha = attachment.get("sha256")?.asString.orEmpty().lowercase()
                if (!HtmlAttachmentLink.isPath(path) || !SHA256.matches(sha)) return null
                val quote = HtmlReaderModel.TextQuote.from(primary.getAsJsonObject("text_quote")) ?: return null
                val hash = normalizeHash(primary.getAsJsonObject("page_state")?.get("hash")?.takeIf { it.isJsonPrimitive }?.asString)
                    ?: return null
                Anchor(path, sha, quote, hash)
            }.getOrNull()
        }
    }

    /**
     * `location.hash` as stored: "" for none, else `#…` of at most [MAX_HASH_LENGTH] characters
     * without control characters; null when it cannot be stored (the card then keeps "" and only
     * the quote, see [fields]).
     */
    fun normalizeHash(raw: String?): String? {
        val value = raw.orEmpty()
        if (value.isEmpty() || value == "#") return ""
        if (!value.startsWith('#') || value.length > MAX_HASH_LENGTH || value.any { it.code < 32 || it.code == 127 }) return null
        return value
    }

    /**
     * The attachment fields of a card made from [selection] in the page [path] (version [sha256]):
     * `created_in`, `html_attachment`, `text_quote`, `page_state`. A page state that cannot be
     * stored becomes "" (the page's first state); the quote is required.
     */
    fun fields(path: String, sha256: String, selection: HtmlReaderModel.Selection): JsonObject {
        require(HtmlAttachmentLink.isPath(path)) { "This HTML attachment link is invalid." }
        require(SHA256.matches(sha256.lowercase())) { "Unknown HTML attachment version" }
        val quote = requireNotNull(selection.quote) { "the selection has no text" }
        return Anchor(path, sha256.lowercase(), quote, normalizeHash(selection.pageHash) ?: "").toJson()
    }

    /** Validates [fields] as [fields] makes them (ReviewStore checks them again before saving). */
    fun validFields(fields: JsonObject): Boolean = Anchor.from(fields) != null

    /** The file name the page is served under (its own name keeps relative `#` links readable). */
    fun servedName(path: String): String = path.substringAfterLast('/')

    /** The stored answer text shown when the page no longer contains it. */
    fun storedAnswer(anchor: Anchor?, selectedText: String): String =
        selectedText.trim().ifBlank { anchor?.quote?.exact.orEmpty() }
}

/** One attachment file ready to show: the verified file and its SHA-256. */
data class HtmlAttachmentFile(val path: String, val file: File, val sha256: String)
