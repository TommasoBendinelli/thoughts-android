// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import android.content.Context
import android.net.Uri
import com.gitlab.mudlej.MjPdfReader.core.io.UriCanonicalizer
import com.gitlab.mudlej.MjPdfReader.core.io.computeSha256
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

data class VerifiedPdfSelection(
    val renderFile: File?,
    val activePdfSha256: String?,
    val expectedPdfSha256: String?,
    val updatePending: Boolean,
)

/**
 * Activates source-linked PDFs only after the public candidate and publication index agree.
 *
 * The public file remains the logical document identity. The selected private file is only the
 * immutable byte source, while history, sidecars, and source lookup keep using the canonical public
 * URI.
 */
class VerifiedPdfActivationStore(
    private val rootDirectory: File,
    private val canonicalPdfDirectory: File = PhoneRenderQueue.defaultCanonicalPdfDirectory(),
    private val sha256: (File) -> String? = ::computeSha256,
    private val copyCandidate: (File, File) -> Unit = ::copyAndSync,
) {

    constructor(
        context: Context,
        canonicalPdfDirectory: File = PhoneRenderQueue.defaultCanonicalPdfDirectory(),
    ) : this(
        File(context.filesDir, DIRECTORY_NAME),
        canonicalPdfDirectory,
    )

    fun selectForOpen(
        logicalFile: File,
        snapshot: SourceLinkedPdfIndexSnapshot,
    ): VerifiedPdfSelection = synchronized(PROCESS_LOCK) {
        if (!isCanonicalPdf(logicalFile)) {
            return@synchronized VerifiedPdfSelection(null, null, null, updatePending = false)
        }

        val expectedHash = snapshot.libraryPdfSha256ByFilename[logicalFile.name]
            ?: snapshot.pdfSha256ByFilename[logicalFile.name]
        if (snapshot.generationCurrent && expectedHash != null && logicalFile.isFile) {
            return@synchronized VerifiedPdfSelection(
                renderFile = logicalFile,
                activePdfSha256 = expectedHash,
                expectedPdfSha256 = expectedHash,
                updatePending = false,
            )
        }

        val manifest = readManifest()
        val existingEntry = manifest.documents[logicalFile.name]
        if (expectedHash != null) {
            val expectedCache = verifiedCacheFile(expectedHash)
                ?: publishVerifiedCandidate(logicalFile, expectedHash)
            if (expectedCache != null) {
                val entry = existingEntry ?: ManifestEntry().also {
                    manifest.documents[logicalFile.name] = it
                }
                val manifestChanged = entry.activeSha256 != expectedHash ||
                    expectedHash !in entry.verifiedSha256
                entry.activeSha256 = expectedHash
                entry.verifiedSha256.add(expectedHash)
                if (!manifestChanged || writeManifest(manifest)) {
                    return@synchronized VerifiedPdfSelection(
                        renderFile = expectedCache,
                        activePdfSha256 = expectedHash,
                        expectedPdfSha256 = expectedHash,
                        updatePending = false,
                    )
                }
            }
        }

        val activeHash = existingEntry?.activeSha256
        val activeFile = activeHash?.let(::verifiedCacheFile)
        if (activeHash != null && activeFile == null) {
            existingEntry.activeSha256 = null
            writeManifest(manifest)
        }
        return@synchronized VerifiedPdfSelection(
            renderFile = activeFile,
            activePdfSha256 = activeFile?.let { activeHash },
            expectedPdfSha256 = expectedHash,
            updatePending = activeFile != null && expectedHash != null && expectedHash != activeHash,
        )
    }

    fun hasVerifiedUpdateReady(
        logicalFile: File,
        currentPdfSha256: String?,
        snapshot: SourceLinkedPdfIndexSnapshot,
    ): Boolean = synchronized(PROCESS_LOCK) {
        if (!isCanonicalPdf(logicalFile)) return@synchronized false
        val expectedHash = snapshot.libraryPdfSha256ByFilename[logicalFile.name]
            ?: snapshot.pdfSha256ByFilename[logicalFile.name]
            ?: return@synchronized false
        if (expectedHash == currentPdfSha256) return@synchronized false
        if (snapshot.generationCurrent && logicalFile.isFile) return@synchronized true
        return@synchronized verifiedCacheFile(expectedHash) != null ||
            (logicalFile.isFile && sha256(logicalFile) == expectedHash)
    }

    fun commentIdentity(
        index: SourceLinkedPdfIndex,
        logicalFile: File,
        actualPdfSha256: String?,
    ): SourceLinkedCommentIdentity = synchronized(PROCESS_LOCK) {
        val normalizedHash = actualPdfSha256
            ?.lowercase()
            ?.takeIf(SHA256_REGEX::matches)
        if (normalizedHash != null && isPreviouslyVerified(logicalFile, normalizedHash)) {
            return@synchronized SourceLinkedCommentIdentity.VERIFIED
        }
        return@synchronized index.commentIdentity(logicalFile, normalizedHash)
    }

    fun isPreviouslyVerified(logicalFile: File, pdfSha256: String): Boolean = synchronized(PROCESS_LOCK) {
        if (!isCanonicalPdf(logicalFile) || !SHA256_REGEX.matches(pdfSha256)) return@synchronized false
        return@synchronized pdfSha256 in (readManifest().documents[logicalFile.name]?.verifiedSha256 ?: emptySet())
    }

    /** Removes incomplete copies and versions that are no longer active while no reader is open. */
    fun pruneInactiveVersions(): Int = synchronized(PROCESS_LOCK) {
        val manifest = readManifest()
        val activeHashes = manifest.documents.values.mapNotNull { it.activeSha256 }.toSet()
        var removed = 0
        rootDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(TEMPORARY_SUFFIX) }
            .forEach { if (it.delete()) removed += 1 }
        versionsDirectory.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.name.removeSuffix(PDF_SUFFIX) !in activeHashes }
            .forEach { if (it.delete()) removed += 1 }
        return@synchronized removed
    }

    private fun publishVerifiedCandidate(candidate: File, expectedHash: String): File? {
        if (!candidate.isFile || sha256(candidate) != expectedHash) return null
        versionsDirectory.mkdirs()
        val destination = cacheFile(expectedHash)
        val temporary = File(rootDirectory, ".${expectedHash}.${UUID.randomUUID()}$TEMPORARY_SUFFIX")
        return try {
            copyCandidate(candidate, temporary)
            if (sha256(temporary) != expectedHash) return null
            if (destination.isFile && sha256(destination) != expectedHash) {
                destination.delete()
            }
            if (!destination.isFile && !temporary.renameTo(destination)) {
                return null
            }
            verifiedCacheFile(expectedHash)
        } catch (_: Throwable) {
            null
        } finally {
            temporary.delete()
        }
    }

    private fun verifiedCacheFile(hash: String): File? {
        if (!SHA256_REGEX.matches(hash)) return null
        val file = cacheFile(hash)
        if (!file.isFile) return null
        if (sha256(file) == hash) return file
        file.delete()
        return null
    }

    private fun cacheFile(hash: String): File = File(versionsDirectory, "$hash$PDF_SUFFIX")

    private val versionsDirectory: File
        get() = File(rootDirectory, VERSIONS_DIRECTORY)

    private val manifestFile: File
        get() = File(rootDirectory, MANIFEST_FILENAME)

    private fun readManifest(): ActivationManifest {
        val file = manifestFile
        if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) return ActivationManifest()
        return runCatching {
            val root = JsonParser.parseString(file.readText(StandardCharsets.UTF_8)).asJsonObject
            if (root.get("schema_version")?.asInt != SCHEMA_VERSION) return@runCatching ActivationManifest()
            val documentsObject = root.get("documents")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: return@runCatching ActivationManifest()
            val documents = linkedMapOf<String, ManifestEntry>()
            documentsObject.entrySet().forEach { (filename, value) ->
                if (!isCanonicalPdfFilename(filename) || !value.isJsonObject) return@forEach
                val entryObject = value.asJsonObject
                val verified = entryObject.get("verified_sha256")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { hashValue ->
                        hashValue.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                            ?.asString
                            ?.lowercase()
                            ?.takeIf(SHA256_REGEX::matches)
                    }
                    ?.toMutableSet()
                    ?: mutableSetOf()
                val active = entryObject.get("active_sha256")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.lowercase()
                    ?.takeIf(SHA256_REGEX::matches)
                if (active != null) verified.add(active)
                documents[filename] = ManifestEntry(active, verified)
            }
            ActivationManifest(documents)
        }.getOrDefault(ActivationManifest())
    }

    private fun writeManifest(manifest: ActivationManifest): Boolean {
        return runCatching {
            rootDirectory.mkdirs()
            val documents = JsonObject()
            manifest.documents.toSortedMap().forEach { (filename, entry) ->
                val payload = JsonObject()
                entry.activeSha256?.let { payload.addProperty("active_sha256", it) }
                payload.add("verified_sha256", JsonArray().apply {
                    entry.verifiedSha256.sorted().forEach(::add)
                })
                documents.add(filename, payload)
            }
            val payload = JsonObject().apply {
                addProperty("schema_version", SCHEMA_VERSION)
                add("documents", documents)
            }.toString()
            val temporary = File(rootDirectory, ".$MANIFEST_FILENAME.${UUID.randomUUID()}$TEMPORARY_SUFFIX")
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(payload.toByteArray(StandardCharsets.UTF_8))
                    stream.fd.sync()
                }
                check(temporary.renameTo(manifestFile)) { "Could not activate verified PDF manifest" }
            } finally {
                temporary.delete()
            }
        }.isSuccess
    }

    private fun isCanonicalPdf(file: File): Boolean {
        return normalizedPath(file.parentFile) == normalizedPath(canonicalPdfDirectory) &&
            isCanonicalPdfFilename(file.name)
    }

    private data class ActivationManifest(
        val documents: MutableMap<String, ManifestEntry> = linkedMapOf(),
    )

    private data class ManifestEntry(
        var activeSha256: String? = null,
        val verifiedSha256: MutableSet<String> = mutableSetOf(),
    )

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val DIRECTORY_NAME = "verified-source-pdfs"
        private const val VERSIONS_DIRECTORY = "versions"
        private const val MANIFEST_FILENAME = "activation.json"
        private const val PDF_SUFFIX = ".pdf"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val MAX_MANIFEST_BYTES = 1_000_000L
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        private val PROCESS_LOCK = Any()

        private fun copyAndSync(source: File, destination: File) {
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
    }
}

private fun isCanonicalPdfFilename(filename: String): Boolean {
    return filename.endsWith(".pdf") &&
        filename.length > ".pdf".length &&
        filename == File(filename).name &&
        !filename.contains('/') &&
        !filename.contains('\\')
}

private fun normalizedPath(file: File?): String? = file?.absoluteFile?.normalize()?.path

fun sourceLinkedLogicalFile(context: Context, uri: Uri): File? {
    val original = UriCanonicalizer.canonicalize(context, uri)
        ?: uri.takeIf { it.scheme == "file" }?.path?.let(::File)
        ?: return null
    val identities = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore()
    // A PDF added to the Library on this phone keeps its local copy until Sync publishes it; a
    // reader tab opened on that copy then follows the published PDF.
    val id = identities.forFile(original)?.get("document_id")?.asString
        ?: original.takeUnless(File::exists)?.let(identities::forRegisteredPath)?.get("document_id")?.asString
        ?: return original
    return identities.fileForId(id) ?: original
}
