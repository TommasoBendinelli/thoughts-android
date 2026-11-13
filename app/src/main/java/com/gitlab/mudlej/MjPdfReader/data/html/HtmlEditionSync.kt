// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import com.google.gson.JsonObject
import java.io.File
import java.io.IOException

/**
 * The server side of HTML editions, behind one small interface while the server API settles
 * (feature/html-edition: `html_edition_v1`, `GET /v1/html-editions/{pdf_sha256}`, objects by
 * SHA-256 through the existing object endpoint).
 */
interface HtmlEditionRemote : HtmlEditionFetcher {
    /** The server's capability flags. */
    @Throws(IOException::class)
    fun capabilities(): Set<String>

    /**
     * The edition record of one PDF version: `{pdf_sha256, html_status, html_edition_sha256,
     * html_error, manifest?}`, or null when the server has none (404).
     */
    @Throws(IOException::class)
    fun edition(pdfSha256: String): JsonObject?
}

/**
 * Brings the cached editions up to date with the Library (after each Sync, in the background, so
 * HTML never lengthens Sync): asks for the state of PDF versions whose edition is not final here,
 * downloads ready editions, and drops editions of PDFs the Library no longer has.
 *
 * The edition states of the Library listing are hints: the server leaves them out of
 * `library_revision`, so an edition finishing after the listing was stored does not change the
 * Library generation and the stored listing can still say "pending". Final states (ready with a
 * known edition, failed) are taken from it without a request; a version that is pending (or not
 * known) is asked for with `GET /v1/html-editions/{pdf}`, and [Summary.pollable] says how many
 * are worth asking again later ([HtmlEditionSyncWorker] does so with a bounded backoff).
 *
 * Which ready editions are downloaded is up to the caller ([HtmlEditionDownloadPolicy]): on a
 * metered network only those of documents read in the HTML view; the others are left "ready on the
 * server" ([HtmlAvailability.NotDownloaded]) and counted as deferred.
 */
class HtmlEditionSync(
    private val store: HtmlEditionStore,
    private val remote: HtmlEditionRemote,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * One current Library PDF. [listed] is the edition state the stored Library listing gave with it
     * (`html_status` / `html_edition_sha256` / `html_error` of the publication), or null when the
     * stored listing predates those fields. It can be older than the server's state.
     */
    data class Document(
        val filename: String,
        val pdfSha256: String,
        val hasSource: Boolean,
        val listed: HtmlEditionState? = null,
    )

    data class Summary(
        val supported: Boolean,
        val checked: Int = 0,
        val installed: Int = 0,
        val pending: Int = 0,
        val failed: Int = 0,
        /** Ready editions not downloaded now (metered network, document not read as HTML). */
        val deferred: Int = 0,
        val errors: List<String> = emptyList(),
        val removedObjects: Int = 0,
        /** Versions still being built whose edition would be downloaded now: worth asking again soon. */
        val pollable: Int = 0,
    ) {
        fun logLine() = "html-editions supported=$supported checked=$checked installed=$installed " +
            "pending=$pending failed=$failed deferred=$deferred pollable=$pollable errors=${errors.size} removed=$removedObjects"
    }

    /** A state that does not change any more for its PDF version: a ready edition, or a failed conversion. */
    private fun HtmlEditionState.isFinal() =
        status == HtmlEditionState.Status.FAILED || (status == HtmlEditionState.Status.READY && editionSha256 != null)

    /** Whether a version that is not final here needs a server request now. */
    private fun needsRequest(record: HtmlEditionStore.Record?, listed: HtmlEditionState?): Boolean = when {
        record == null -> true
        record.state.status == HtmlEditionState.Status.PENDING -> true
        // Queued since it was last asked.
        listed?.status == HtmlEditionState.Status.PENDING -> true
        // Not queued yet (e.g. built before the server had editions): ask again now and then.
        else -> clock() - store.checkedAt(record.pdfSha256) > NONE_RECHECK_MS
    }

    /**
     * [download] says whether the ready edition of a document is downloaded now (asked at most once
     * per document). [followUp] marks a later run that only asks again for versions still being
     * built: then a pending version whose edition would not be downloaded now is not asked for (on a
     * metered network, only documents read as HTML; the others wait for the next Sync or Wi-Fi).
     */
    fun run(
        documents: List<Document>,
        stopped: () -> Boolean = { false },
        download: (Document) -> Boolean = { true },
        followUp: Boolean = false,
    ): Summary {
        val capabilities = remote.capabilities()
        store.setServerSupport(CAPABILITY in capabilities)
        store.setAttachmentCardSupport(HtmlAttachmentCards.CAPABILITY in capabilities)
        if (CAPABILITY !in capabilities) return Summary(supported = false)
        var checked = 0
        var installed = 0
        var pending = 0
        var failed = 0
        var deferred = 0
        var pollable = 0
        val errors = mutableListOf<String>()
        documents.filter { it.hasSource }.forEach { document ->
            if (stopped()) return@forEach
            val sha = document.pdfSha256.lowercase()
            val record = store.record(sha)
            val allowed by lazy { download(document) }
            fun error(error: IOException) {
                errors += "${document.filename}: ${error.message ?: error.javaClass.simpleName}"
            }

            // Asks the server for the version's state and acts on it; [unless] is a state already tried.
            fun ask(unless: HtmlEditionState? = null): Boolean {
                checked++
                val state = remote.edition(sha)?.let(HtmlEditionState::fromJson) ?: HtmlEditionState.NONE
                store.markChecked(sha, clock())
                if (state == unless) return false
                store.recordState(sha, document.filename, state)
                when (state.status) {
                    HtmlEditionState.Status.READY -> if (allowed) {
                        store.install(sha, document.filename, state.editionSha256!!, remote)
                        installed++
                    } else {
                        deferred++
                    }
                    HtmlEditionState.Status.PENDING -> {
                        pending++
                        if (allowed) pollable++
                    }
                    HtmlEditionState.Status.FAILED -> failed++
                    HtmlEditionState.Status.NONE -> Unit
                }
                return true
            }

            // A final state known here wins over the listing, which can be older; a final listed
            // state needs no request either.
            val known = record?.state?.takeIf { it.isFinal() } ?: document.listed?.takeIf { it.isFinal() }
            if (known != null) {
                val adopted = record?.state != known
                if (adopted) store.recordState(sha, document.filename, known)
                if (known.status == HtmlEditionState.Status.FAILED) {
                    if (adopted) failed++
                    return@forEach
                }
                if (record?.installed == true && !adopted && store.manifestIfComplete(known.editionSha256) != null) return@forEach
                if (!allowed) {
                    deferred++
                    return@forEach
                }
                try {
                    store.install(sha, document.filename, known.editionSha256!!, remote)
                    installed++
                } catch (failure: IOException) {
                    // The edition may have been rebuilt since: ask once what the server has now.
                    try {
                        if (!ask(unless = known)) error(failure)
                    } catch (again: IOException) {
                        error(failure)
                    }
                }
                return@forEach
            }
            if (!needsRequest(record, document.listed)) return@forEach
            if (followUp && !allowed) return@forEach
            try {
                ask()
            } catch (failure: IOException) {
                error(failure)
                if (store.record(sha)?.state?.status == HtmlEditionState.Status.PENDING && allowed) pollable++
            }
        }
        val removed = if (stopped()) 0 else store.retain(documents.map { it.pdfSha256 }.toSet())
        return Summary(true, checked, installed, pending, failed, deferred, errors, removed, pollable)
    }

    /**
     * Downloads the edition of one PDF version now: the reader switched a document to HTML view whose
     * edition no Sync has downloaded (metered network). Asks the server for the current state first,
     * so a rebuilt or failed edition is reported as such; without an answer, a known ready edition is
     * still tried. Returns what the reader can show afterwards ([HtmlAvailability.Unavailable] with
     * the reason when the download failed).
     */
    fun downloadNow(filename: String, pdfSha256: String): HtmlAvailability {
        val sha = pdfSha256.lowercase()
        if (!isSha256(sha)) return HtmlAvailability.Unavailable("this PDF is not a Library version")
        try {
            val known = store.record(sha)?.state
            val state = try {
                (remote.edition(sha)?.let(HtmlEditionState::fromJson) ?: HtmlEditionState.NONE).also {
                    store.recordState(sha, filename, it)
                    store.markChecked(sha, clock())
                }
            } catch (error: IOException) {
                if (known?.status != HtmlEditionState.Status.READY) throw error
                known
            }
            if (state.status == HtmlEditionState.Status.READY) store.install(sha, filename, state.editionSha256!!, remote)
        } catch (error: IOException) {
            return HtmlAvailability.Unavailable(error.message ?: error.javaClass.simpleName)
        }
        return store.availability(sha).let {
            // Still not complete here after an answer of "ready" cannot happen; never ask to Sync for it.
            if (it == HtmlAvailability.NotDownloaded) HtmlAvailability.Unavailable("the download did not complete") else it
        }
    }

    companion object {
        const val CAPABILITY = "html_edition_v1"
        const val NONE_RECHECK_MS = 6L * 60 * 60 * 1000
    }
}

/** When the server last answered for a PDF version, and whether it builds editions at all. */
internal fun HtmlEditionStore.markChecked(pdfSha256: String, at: Long) {
    val file = File(root, "checked/$pdfSha256")
    file.parentFile?.mkdirs()
    file.writeText(at.toString())
}

internal fun HtmlEditionStore.checkedAt(pdfSha256: String): Long =
    runCatching { File(root, "checked/$pdfSha256").readText().trim().toLong() }.getOrDefault(0L)

fun HtmlEditionStore.setServerSupport(supported: Boolean) {
    root.mkdirs()
    File(root, "server-support").writeText(if (supported) "yes" else "no")
}

/** Null until a Sync has asked; then whether the server builds HTML editions (`html_edition_v1`). */
fun HtmlEditionStore.serverSupport(): Boolean? = runCatching {
    when (File(root, "server-support").readText().trim()) { "yes" -> true; "no" -> false; else -> null }
}.getOrNull()

fun HtmlEditionStore.setAttachmentCardSupport(supported: Boolean) {
    root.mkdirs()
    File(root, "server-attachment-cards").writeText(if (supported) "yes" else "no")
}

/**
 * Whether the server takes review cards made inside HTML attachment pages
 * (`html_attachment_cards_v1`), as the last Sync saw it; false until a Sync has asked.
 */
fun HtmlEditionStore.attachmentCardSupport(): Boolean =
    runCatching { File(root, "server-attachment-cards").readText().trim() == "yes" }.getOrDefault(false)
