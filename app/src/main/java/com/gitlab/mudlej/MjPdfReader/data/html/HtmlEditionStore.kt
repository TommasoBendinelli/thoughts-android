// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.html

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The HTML edition of a published PDF (thoughts-html-view-SPEC.md, "Server: HTML edition"):
 * what the server says about it ([HtmlEditionState]) and the cached, content-addressed files.
 *
 * The edition is built by the server after the PDF, in the background, so a PDF can be ready
 * long before its edition. A failed edition never affects the PDF.
 */
data class HtmlEditionState(
    val status: Status,
    val editionSha256: String? = null,
    val error: String? = null,
) {
    enum class Status { NONE, PENDING, READY, FAILED }

    companion object {
        val NONE = HtmlEditionState(Status.NONE)

        /** `html_status` / `html_edition_sha256` / `html_error` of a publication or render-state record. */
        fun fromFields(status: String?, editionSha256: String?, error: String?): HtmlEditionState = when (status) {
            "ready" -> editionSha256?.lowercase()?.takeIf(::isSha256)
                ?.let { HtmlEditionState(Status.READY, it) }
                ?: HtmlEditionState(Status.FAILED, error = "the server named no valid HTML edition")
            "pending", "running" -> HtmlEditionState(Status.PENDING)
            "failed" -> HtmlEditionState(Status.FAILED, error = error?.trim()?.takeIf(String::isNotEmpty)?.take(500))
            else -> NONE
        }

        fun fromJson(value: JsonObject?): HtmlEditionState {
            if (value == null) return NONE
            // The Library entry carries the fields flat; the publication list nests them under "html".
            val fields = value.get("html")?.takeIf { it.isJsonObject }?.asJsonObject ?: value
            fun text(name: String) = fields.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            return fromFields(text("html_status"), text("html_edition_sha256"), text("html_error"))
        }
    }
}

/** A validated edition manifest (`kind: thoughts_html_edition`, schema 1). */
data class HtmlEditionManifest(
    val sha256: String,
    val entry: String,
    val files: Map<String, String>,
    val anchors: List<String>,
    val sourceFormat: String,
    val sourceMap: String,
    val documentId: String,
    val pdfSha256: String,
) {
    companion object {
        const val KIND = "thoughts_html_edition"
        const val MAX_BYTES = 4L * 1024 * 1024

        fun parse(sha256: String, raw: ByteArray): HtmlEditionManifest {
            val json = runCatching { JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject }
                .getOrElse { throw HtmlEditionInvalid("the HTML edition manifest is not JSON") }
            fun text(name: String) = json.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (text("kind") != KIND) throw HtmlEditionInvalid("not an HTML edition manifest")
            if (json.get("schema")?.takeIf { it.isJsonPrimitive }?.asInt != 1) {
                throw HtmlEditionInvalid("unsupported HTML edition schema ${json.get("schema")}")
            }
            val files = json.get("files")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw HtmlEditionInvalid("the HTML edition lists no files")
            val mapped = linkedMapOf<String, String>()
            files.entrySet().forEach { (path, digest) ->
                val sha = digest.takeIf { it.isJsonPrimitive }?.asString?.lowercase()
                if (!validPath(path) || sha == null || !isSha256(sha)) {
                    throw HtmlEditionInvalid("invalid HTML edition file entry $path")
                }
                mapped[path] = sha
            }
            if (mapped.size > MAX_FILES) throw HtmlEditionInvalid("the HTML edition has too many files")
            val entry = text("entry").ifEmpty { "index.html" }
            if (entry !in mapped || !entry.endsWith(".html")) throw HtmlEditionInvalid("the HTML edition has no entry page")
            val anchors = json.get("anchors")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            return HtmlEditionManifest(sha256, entry, mapped, anchors, text("source_format"),
                text("source_map").ifEmpty { "none" }, text("document_id"), text("pdf_sha256"))
        }

        /** A relative path inside the bundle: no scheme, no `..`, no leading slash, no backslash. */
        fun validPath(path: String): Boolean = path.isNotEmpty() && path.length <= 512 && !path.startsWith("/") &&
            '\\' !in path && ':' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            path.none { it.isISOControl() }

        private const val MAX_FILES = 5000
    }
}

class HtmlEditionInvalid(message: String) : IOException(message)

/** Fetches one content-addressed object (the existing `GET /v1/objects/{sha256}`). */
fun interface HtmlEditionFetcher {
    /** Writes the object's bytes to [destination]; the store verifies the hash. */
    @Throws(IOException::class)
    fun download(sha256: String, destination: File)
}

/** What the reader can show for a PDF version, and why not. */
sealed interface HtmlAvailability {
    data class Ready(val edition: HtmlEditionManifest) : HtmlAvailability
    /**
     * The server has built it, not downloaded here yet: after a Sync on Wi-Fi, after any Sync once the
     * document is read as HTML, or when the reader switches the document to HTML.
     */
    object NotDownloaded : HtmlAvailability
    object Building : HtmlAvailability
    data class Unavailable(val reason: String) : HtmlAvailability
}

/**
 * Cached HTML editions, offline next to the PDFs: `objects/<sha256>` holds the manifest and every
 * file (shared between editions, like the Library's objects), `pdf/<pdf sha256>.json` what the
 * server last said about that PDF version and whether its edition is complete here.
 */
class HtmlEditionStore(val root: File) {
    private val objects = File(root, "objects")
    private val records = File(root, "pdf")

    data class Record(
        val pdfSha256: String,
        val filename: String,
        val state: HtmlEditionState,
        val installed: Boolean,
    )

    fun record(pdfSha256: String): Record? = synchronized(lock) {
        val file = recordFile(pdfSha256) ?: return null
        if (!file.isFile) return null
        runCatching {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            Record(
                pdfSha256 = pdfSha256.lowercase(),
                filename = json.get("filename")?.asString.orEmpty(),
                state = HtmlEditionState.fromJson(json),
                installed = json.get("installed")?.asBoolean == true,
            )
        }.getOrNull()
    }

    /** Remembers the server's state for a PDF version; an installed edition stays installed if it is the same. */
    fun recordState(pdfSha256: String, filename: String, state: HtmlEditionState) = synchronized(lock) {
        val previous = record(pdfSha256)
        val installed = previous?.installed == true && state.status == HtmlEditionState.Status.READY &&
            previous.state.editionSha256 == state.editionSha256 && manifestIfComplete(state.editionSha256) != null
        write(pdfSha256, filename, state, installed)
    }

    fun availability(pdfSha256: String?, hasSource: Boolean = true): HtmlAvailability {
        if (!hasSource) return HtmlAvailability.Unavailable("imported PDFs have no HTML edition")
        val sha = pdfSha256?.lowercase()?.takeIf(::isSha256)
            ?: return HtmlAvailability.Unavailable("this PDF is not a Library version")
        val record = record(sha) ?: return HtmlAvailability.Unavailable(
            if (serverSupport() == false) "the server does not build HTML editions yet"
            else "no HTML edition known for this version yet (Sync to check)")
        return when (record.state.status) {
            HtmlEditionState.Status.READY -> manifestIfComplete(record.state.editionSha256)
                ?.let { HtmlAvailability.Ready(it) } ?: HtmlAvailability.NotDownloaded
            HtmlEditionState.Status.PENDING -> HtmlAvailability.Building
            HtmlEditionState.Status.FAILED -> HtmlAvailability.Unavailable(record.state.error ?: "the conversion failed")
            HtmlEditionState.Status.NONE -> HtmlAvailability.Unavailable("the server has no HTML edition of this version")
        }
    }

    /**
     * Downloads the edition [editionSha256] of [pdfSha256] (manifest, then each missing file), each
     * verified by its SHA-256 before it is kept. Returns the manifest; throws without a partial record.
     */
    @Throws(IOException::class)
    fun install(pdfSha256: String, filename: String, editionSha256: String, fetcher: HtmlEditionFetcher): HtmlEditionManifest {
        val edition = editionSha256.lowercase()
        require(isSha256(edition) && isSha256(pdfSha256.lowercase())) { "invalid HTML edition identity" }
        val manifestRaw = fetchObject(edition, HtmlEditionManifest.MAX_BYTES, fetcher)
        val manifest = HtmlEditionManifest.parse(edition, manifestRaw)
        var total = 0L
        manifest.files.values.distinct().forEach { sha ->
            total += fetchObjectFile(sha, MAX_FILE_BYTES, fetcher).length()
            if (total > MAX_EDITION_BYTES) throw HtmlEditionInvalid("the HTML edition is larger than ${MAX_EDITION_BYTES shr 20} MB")
        }
        synchronized(lock) { write(pdfSha256, filename, HtmlEditionState(HtmlEditionState.Status.READY, edition), installed = true) }
        return manifest
    }

    /** The manifest when it and every file it lists are stored here. */
    fun manifestIfComplete(editionSha256: String?): HtmlEditionManifest? {
        val sha = editionSha256?.lowercase()?.takeIf(::isSha256) ?: return null
        val file = File(objects, sha)
        if (!file.isFile || file.length() > HtmlEditionManifest.MAX_BYTES) return null
        val manifest = runCatching { HtmlEditionManifest.parse(sha, file.readBytes()) }.getOrNull() ?: return null
        return manifest.takeIf { it.files.values.all { digest -> File(objects, digest).isFile } }
    }

    /** The stored file of [path] in [edition], or null when the path is not part of it. */
    fun file(edition: HtmlEditionManifest, path: String): File? {
        val sha = edition.files[path] ?: return null
        return File(objects, sha).takeIf(File::isFile)
    }

    /**
     * Keeps the records (and objects) of [keepPdfSha256s], the current Library versions, and
     * deletes the rest: editions follow their PDFs, like the Library's own retention.
     */
    fun retain(keepPdfSha256s: Set<String>): Int = synchronized(lock) {
        val keep = keepPdfSha256s.map(String::lowercase).toSet()
        records.listFiles().orEmpty().forEach { file ->
            if (file.name.removeSuffix(".json") !in keep) file.delete()
        }
        File(root, "checked").listFiles().orEmpty().forEach { file -> if (file.name !in keep) file.delete() }
        val referenced = mutableSetOf<String>()
        records.listFiles().orEmpty().forEach { file ->
            val record = record(file.name.removeSuffix(".json")) ?: return@forEach
            val edition = record.state.editionSha256 ?: return@forEach
            referenced += edition
            runCatching { HtmlEditionManifest.parse(edition, File(objects, edition).readBytes()) }.getOrNull()
                ?.let { referenced += it.files.values }
        }
        var removed = 0
        objects.listFiles().orEmpty().forEach { file ->
            if (file.name !in referenced && !file.name.startsWith(".tmp")) {
                if (file.delete()) removed++
            } else if (file.name.startsWith(".tmp") && System.currentTimeMillis() - file.lastModified() > 3_600_000) {
                file.delete()
            }
        }
        removed
    }

    private fun fetchObject(sha: String, maximum: Long, fetcher: HtmlEditionFetcher): ByteArray =
        fetchObjectFile(sha, maximum, fetcher).readBytes()

    private fun fetchObjectFile(sha: String, maximum: Long, fetcher: HtmlEditionFetcher): File {
        val target = File(objects, sha)
        if (target.isFile && target.length() <= maximum) return target
        objects.mkdirs()
        val temporary = File(objects, ".tmp-$sha-${System.nanoTime()}")
        try {
            fetcher.download(sha, temporary)
            if (!temporary.isFile || temporary.length() > maximum) throw HtmlEditionInvalid("HTML edition object $sha is too large")
            if (sha256(temporary) != sha) throw HtmlEditionInvalid("HTML edition object $sha failed verification")
            if (!temporary.renameTo(target)) {
                if (!target.isFile) throw IOException("could not store HTML edition object $sha")
            }
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun write(pdfSha256: String, filename: String, state: HtmlEditionState, installed: Boolean) {
        val file = recordFile(pdfSha256) ?: return
        records.mkdirs()
        val json = JsonObject().apply {
            addProperty("pdf_sha256", pdfSha256.lowercase())
            addProperty("filename", filename)
            addProperty("html_status", when (state.status) {
                HtmlEditionState.Status.READY -> "ready"
                HtmlEditionState.Status.PENDING -> "pending"
                HtmlEditionState.Status.FAILED -> "failed"
                HtmlEditionState.Status.NONE -> null
            })
            state.editionSha256?.let { addProperty("html_edition_sha256", it) }
            state.error?.let { addProperty("html_error", it) }
            addProperty("installed", installed)
        }
        val temporary = File(records, ".${file.name}.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }

    private fun recordFile(pdfSha256: String): File? =
        pdfSha256.lowercase().takeIf(::isSha256)?.let { File(records, "$it.json") }

    companion object {
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val MAX_EDITION_BYTES = 256L * 1024 * 1024
        private val lock = Any()

        fun default(context: android.content.Context) = HtmlEditionStore(File(context.filesDir, "html-editions"))

        fun mediaType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

internal fun isSha256(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
