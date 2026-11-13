// Written by Mudlej. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import android.os.FileObserver
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutation
import com.gitlab.mudlej.MjPdfReader.data.sync.LibraryMutationStore
import com.google.gson.JsonParser
import java.io.File

enum class SourcePdfCapability {
    SOURCE_LINKED,
    NOT_SOURCE_LINKED,
    INDEX_UNAVAILABLE,
}

enum class LibraryPdfKind {
    SOURCE_LINKED,
    UNLINKED,
}

enum class SourceLinkedCommentIdentity {
    NOT_SOURCE_LINKED,
    VERIFIED,
    VERIFYING,
    INDEX_UNAVAILABLE,
    PUBLICATION_UNVERIFIED,
    HASH_MISMATCH,
    ;

    val allowsMutation: Boolean
        get() = this == NOT_SOURCE_LINKED || this == VERIFIED
}

data class SourceLinkedPdfIndexSnapshot(
    val filenames: Set<String> = emptySet(),
    val pdfSha256ByFilename: Map<String, String> = emptyMap(),
    val available: Boolean = false,
    val libraryKindsByFilename: Map<String, LibraryPdfKind> = emptyMap(),
    val libraryPdfSha256ByFilename: Map<String, String> = emptyMap(),
    val activatedPdfSha256ByFilename: Map<String, String> = emptyMap(),
    val libraryDisplayNamesByFilename: Map<String, String> = emptyMap(),
    val archivedLibraryFilenames: Set<String> = emptySet(),
    val libraryPublishedAtByFilename: Map<String, String> = emptyMap(),
    val staleBuildsByFilename: Map<String, LibraryBuildFailure> = emptyMap(),
    val libraryAvailable: Boolean = false,
    val libraryRevision: String = "",
    val sourceIndexSha256: String = "",
    val generationCurrent: Boolean = false,
    val projectsByFilename: Map<String, String> = emptyMap(),
    /** PDFs added to the Library on this phone that Sync has not published yet, by filename. */
    val pendingLibraryPdfFiles: Map<String, File> = emptyMap(),
) {
    val libraryFilenames: Set<String> get() = libraryKindsByFilename.keys

    fun libraryKindFor(file: File, canonicalPdfDirectory: File): LibraryPdfKind? {
        if (!libraryAvailable) return null
        if (isCanonicalPdf(file, canonicalPdfDirectory)) return libraryKindsByFilename[file.name]
        val pending = pendingLibraryPdfFiles[file.name] ?: return null
        return libraryKindsByFilename[file.name]?.takeIf { normalizedPath(pending) == normalizedPath(file) }
    }

    /** Where the Library PDF [filename] is read from: its local copy until Sync publishes it. */
    fun libraryPdfFile(filename: String, canonicalPdfDirectory: File): File =
        pendingLibraryPdfFiles[filename] ?: File(canonicalPdfDirectory, filename)
    fun capabilityFor(file: File, canonicalPdfDirectory: File): SourcePdfCapability {
        if (!isCanonicalPdf(file, canonicalPdfDirectory)) {
            return SourcePdfCapability.NOT_SOURCE_LINKED
        }
        if (!available) {
            return SourcePdfCapability.INDEX_UNAVAILABLE
        }
        return if (file.name in filenames) {
            SourcePdfCapability.SOURCE_LINKED
        } else {
            SourcePdfCapability.NOT_SOURCE_LINKED
        }
    }

    fun canonicalPdfSha256(file: File, canonicalPdfDirectory: File): String? {
        if (!isCanonicalPdf(file, canonicalPdfDirectory) || !available) return null
        return pdfSha256ByFilename[file.name]
    }

    fun canonicalLibraryPdfSha256(file: File, canonicalPdfDirectory: File): String? {
        if (!isCanonicalPdf(file, canonicalPdfDirectory) || !libraryAvailable || !generationCurrent) return null
        return libraryPdfSha256ByFilename[file.name]?.takeIf { activatedPdfSha256ByFilename[file.name] == it }
    }

    fun commentIdentity(
        file: File,
        actualPdfSha256: String?,
        canonicalPdfDirectory: File,
    ): SourceLinkedCommentIdentity {
        if (!isCanonicalPdf(file, canonicalPdfDirectory)) {
            return SourceLinkedCommentIdentity.NOT_SOURCE_LINKED
        }
        if (!available) {
            return SourceLinkedCommentIdentity.INDEX_UNAVAILABLE
        }
        if (file.name !in filenames) {
            return SourceLinkedCommentIdentity.NOT_SOURCE_LINKED
        }
        val publishedPdfSha256 = pdfSha256ByFilename[file.name]
            ?: return SourceLinkedCommentIdentity.PUBLICATION_UNVERIFIED
        val actual = actualPdfSha256
            ?.lowercase()
            ?.takeIf(SHA256_REGEX::matches)
            ?: return SourceLinkedCommentIdentity.PUBLICATION_UNVERIFIED
        return if (actual == publishedPdfSha256) {
            SourceLinkedCommentIdentity.VERIFIED
        } else {
            SourceLinkedCommentIdentity.HASH_MISMATCH
        }
    }
}

data class LibraryBuildFailure(
    val buildId: String,
    val message: String,
)

class SourceLinkedPdfIndex(
    private val canonicalPdfDirectory: File = PhoneRenderQueue.defaultCanonicalPdfDirectory(),
    private val indexFile: File = File(canonicalPdfDirectory, INDEX_FILENAME),
    private val libraryIndexFile: File = File(canonicalPdfDirectory, LIBRARY_INDEX_FILENAME),
    private val generationFile: File = File(canonicalPdfDirectory, GENERATION_FILENAME),
    private val libraryMutationRoot: File = LibraryMutationStore.defaultRoot(canonicalPdfDirectory),
) {

    private val libraryMutationFile = LibraryMutationStore.stateFile(libraryMutationRoot)
    private var cachedIdentity: List<IndexFileIdentity>? = null
    private var cachedSnapshot = SourceLinkedPdfIndexSnapshot()
    private val sharedKey = listOf(indexFile.path, libraryIndexFile.path, generationFile.path, libraryMutationFile.path)

    @Synchronized
    fun snapshot(): SourceLinkedPdfIndexSnapshot {
        val identity = listOf(
            IndexFileIdentity.of(indexFile),
            IndexFileIdentity.of(libraryIndexFile),
            IndexFileIdentity.of(generationFile),
            IndexFileIdentity.of(libraryMutationFile),
        )
        if (identity == cachedIdentity) {
            return cachedSnapshot
        }
        // Screens and workers each create an index; share one parse of unchanged files per process.
        val shared = synchronized(sharedSnapshots) { sharedSnapshots[sharedKey] }
        cachedSnapshot = if (shared != null && shared.first == identity) shared.second
        else withLibraryMutations(parseLibrary(libraryIndexFile, parse(indexFile))).also { parsed ->
            synchronized(sharedSnapshots) { sharedSnapshots[sharedKey] = identity to parsed }
        }
        cachedIdentity = identity
        return cachedSnapshot
    }

    fun capabilityFor(file: File): SourcePdfCapability {
        return snapshot().capabilityFor(file, canonicalPdfDirectory)
    }

    fun capabilityFor(
        file: File,
        snapshot: SourceLinkedPdfIndexSnapshot,
    ): SourcePdfCapability {
        return snapshot.capabilityFor(file, canonicalPdfDirectory)
    }

    fun isSourceLinked(file: File): Boolean {
        return capabilityFor(file) == SourcePdfCapability.SOURCE_LINKED
    }

    fun isLibraryPdf(file: File): Boolean {
        return snapshot().libraryKindFor(file, canonicalPdfDirectory) != null
    }

    fun canonicalPdfSha256(file: File): String? {
        return snapshot().canonicalPdfSha256(file, canonicalPdfDirectory)
    }

    fun canonicalLibraryPdfSha256(file: File): String? {
        return snapshot().canonicalLibraryPdfSha256(file, canonicalPdfDirectory)
    }

    fun commentIdentity(file: File, actualPdfSha256: String?): SourceLinkedCommentIdentity {
        return snapshot().commentIdentity(file, actualPdfSha256, canonicalPdfDirectory)
    }

    @Synchronized
    fun invalidate() {
        cachedIdentity = null
        synchronized(sharedSnapshots) { sharedSnapshots.remove(sharedKey) }
    }

    fun observe(onChanged: () -> Unit): FileObserver = observePaths { path ->
        if (
            path == INDEX_FILENAME ||
            path == LIBRARY_INDEX_FILENAME ||
            path == GENERATION_FILENAME
        ) {
            onChanged()
        }
    }

    fun observePaths(onChanged: (String?) -> Unit): FileObserver {
        canonicalPdfDirectory.mkdirs()
        return object : FileObserver(
            canonicalPdfDirectory,
            CREATE or MOVED_TO or CLOSE_WRITE or DELETE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (
                    path == INDEX_FILENAME ||
                    path == LIBRARY_INDEX_FILENAME ||
                    path == GENERATION_FILENAME
                ) {
                    invalidate()
                }
                if (
                    path == INDEX_FILENAME ||
                    path == LIBRARY_INDEX_FILENAME ||
                    path == GENERATION_FILENAME ||
                    path?.let(::isCanonicalPdfFilename) == true
                ) {
                    onChanged(path)
                }
            }
        }.also(FileObserver::startWatching)
    }

    /** Local Library changes waiting for Sync are shown over every Library generation. */
    private fun withLibraryMutations(snapshot: SourceLinkedPdfIndexSnapshot): SourceLinkedPdfIndexSnapshot {
        if (!snapshot.libraryAvailable || !libraryMutationFile.isFile) return snapshot
        val operations = runCatching { LibraryMutationStore.readOperations(libraryMutationFile) }
            .getOrElse { return snapshot }
        return libraryMutationOverlay(snapshot, operations) { LibraryMutationStore.pdfFile(libraryMutationRoot, it) }
    }

    private fun parse(file: File): SourceLinkedPdfIndexSnapshot {
        if (!file.isFile) {
            return SourceLinkedPdfIndexSnapshot()
        }
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            if (root.get("schema_version")?.asInt != SCHEMA_VERSION) {
                return@runCatching SourceLinkedPdfIndexSnapshot()
            }
            val entries = root.get("pdfs")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: return@runCatching SourceLinkedPdfIndexSnapshot()
            val parsedEntries = entries.map { value ->
                val entry = value.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IllegalArgumentException("Index entry is not an object")
                val filename = entry.get("filename")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.takeIf(::isCanonicalPdfFilename)
                    ?: throw IllegalArgumentException("Index filename is invalid")
                val hashValue = entry.get("pdf_sha256")
                val pdfSha256 = if (hashValue == null) {
                    null
                } else {
                    hashValue
                        .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                        ?.lowercase()
                        ?.takeIf(SHA256_REGEX::matches)
                        ?: throw IllegalArgumentException("Index PDF hash is invalid")
                }
                val project = entry.get("project")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.trim()?.takeIf(String::isNotBlank)
                Triple(filename, pdfSha256, project)
            }
            val filenames = parsedEntries.map { it.first }.toSet()
            if (filenames.size != entries.size()) {
                throw IllegalArgumentException("Index filenames are duplicated")
            }
            val hashes = parsedEntries.mapNotNull { (filename, hash) ->
                hash?.let { filename to it }
            }.toMap()
            SourceLinkedPdfIndexSnapshot(
                filenames, hashes, available = true,
                projectsByFilename = parsedEntries.mapNotNull { (filename, _, project) ->
                    project?.let { filename to it }
                }.toMap(),
            )
        }.getOrDefault(SourceLinkedPdfIndexSnapshot())
    }

    private fun parseLibrary(
        file: File,
        sourceSnapshot: SourceLinkedPdfIndexSnapshot,
    ): SourceLinkedPdfIndexSnapshot {
        if (!file.isFile) return sourceSnapshot
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            if (root.get("schema_version")?.asInt != LIBRARY_SCHEMA_VERSION) return@runCatching sourceSnapshot
            val entries = root.getAsJsonArray("pdfs") ?: return@runCatching sourceSnapshot
            val parsed = entries.associate { value ->
                val entry = value.asJsonObject
                val filename = entry.get("filename")?.asString?.takeIf(::isCanonicalPdfFilename)
                    ?: throw IllegalArgumentException("Library filename is invalid")
                val kind = when (entry.get("kind")?.asString) {
                    "source_linked" -> LibraryPdfKind.SOURCE_LINKED
                    "unlinked" -> LibraryPdfKind.UNLINKED
                    else -> throw IllegalArgumentException("Library PDF kind is invalid")
                }
                val hash = entry.get("pdf_sha256")?.asString?.lowercase()?.takeIf(SHA256_REGEX::matches)
                    ?: throw IllegalArgumentException("Library PDF hash is invalid")
                val displayName = entry.get("display_name")?.asString
                    ?.trim()
                    ?.takeIf(::isValidLibraryDisplayName)
                    .orEmpty()
                val publishedAt = entry.get("published_at")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    .orEmpty()
                val stale = entry.get("pdf_stale")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                val archived = entry.get("archived")?.let { value ->
                    value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                        ?: throw IllegalArgumentException("Library archive state is invalid")
                } ?: false
                val latestBuild = entry.get("latest_build")?.takeIf { it.isJsonObject }?.asJsonObject
                val failedDocuments = latestBuild?.getAsJsonArray("failed_documents")
                val message = latestBuild?.get("error")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: failedDocuments?.firstOrNull()?.asJsonObject?.get("message")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                        ?.trim()
                        .orEmpty()
                val failure = if (stale) {
                    LibraryBuildFailure(
                        buildId = latestBuild?.get("id")?.asString.orEmpty(),
                        message = message.ifEmpty { "The build on the server failed; the last-good PDF remains published." },
                    )
                } else {
                    null
                }
                filename to LibraryIndexEntry(kind, hash, displayName, publishedAt, failure, archived)
            }
            if (parsed.size != entries.size()) throw IllegalArgumentException("Library filenames are duplicated")
            val revision = root.get("library_revision")?.asString?.takeIf(SHA256_REGEX::matches).orEmpty()
            val sourceIndexSha256 = root.get("source_index_sha256")?.asString?.takeIf(SHA256_REGEX::matches).orEmpty()
            val generationCurrent = generationMatches(
                generationFile,
                revision,
                sourceIndexSha256,
                parsed,
                canonicalPdfDirectory,
            )
            val activated = activatedGenerationEntries(generationFile, canonicalPdfDirectory)
            sourceSnapshot.copy(
                libraryKindsByFilename = parsed.mapValues { it.value.kind },
                libraryPdfSha256ByFilename = parsed.mapValues { it.value.sha256 },
                activatedPdfSha256ByFilename = activated,
                libraryDisplayNamesByFilename = parsed.mapValues { it.value.displayName }
                    .filterValues(String::isNotEmpty),
                archivedLibraryFilenames = parsed.filterValues { it.archived }.keys,
                libraryPublishedAtByFilename = parsed.mapValues { it.value.publishedAt }
                    .filterValues(String::isNotEmpty),
                staleBuildsByFilename = parsed.mapNotNull { (filename, entry) ->
                    entry.failure?.let { filename to it }
                }.toMap(),
                libraryAvailable = true,
                libraryRevision = revision,
                sourceIndexSha256 = sourceIndexSha256,
                generationCurrent = generationCurrent,
            )
        }.getOrDefault(sourceSnapshot)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val INDEX_FILENAME = ".thoughts-source-index.json"
        const val LIBRARY_SCHEMA_VERSION = 1
        const val LIBRARY_INDEX_FILENAME = ".thoughts-library-index.json"
        const val GENERATION_FILENAME = ".thoughts-generation.json"

        private val sharedSnapshots = hashMapOf<List<String>,
            Pair<List<IndexFileIdentity>, SourceLinkedPdfIndexSnapshot>>()
    }
}

private fun activatedGenerationEntries(
    file: File,
    canonicalPdfDirectory: File,
): Map<String, String> {
    if (!file.isFile) return emptyMap()
    return runCatching {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        check(root.get("schema_version")?.asInt == 1)
        val publications = root.getAsJsonArray("publications") ?: error("Generation has no publications")
        publications.mapNotNull { value ->
            val publication = value.asJsonObject
            val filename = publication.get("filename")?.asString?.takeIf(::isCanonicalPdfFilename)
                ?: error("Invalid generation filename")
            val sha256 = publication.get("sha256")?.asString?.takeIf(SHA256_REGEX::matches)
                ?: error("Invalid generation hash")
            val size = publication.get("size_bytes")?.asLong?.takeIf { it >= 0 }
                ?: error("Invalid generation size")
            val pdf = File(canonicalPdfDirectory, filename)
            if (pdf.isFile && pdf.length() == size) filename to sha256 else null
        }.toMap()
    }.getOrDefault(emptyMap())
}

private data class LibraryIndexEntry(
    val kind: LibraryPdfKind,
    val sha256: String,
    val displayName: String,
    val publishedAt: String,
    val failure: LibraryBuildFailure?,
    val archived: Boolean,
)

private fun generationMatches(
    file: File,
    revision: String,
    sourceIndexSha256: String,
    libraryEntries: Map<String, LibraryIndexEntry>,
    canonicalPdfDirectory: File,
): Boolean {
    if (!file.isFile || revision.isEmpty() || sourceIndexSha256.isEmpty()) return false
    return runCatching {
        val root = JsonParser.parseString(file.readText()).asJsonObject
        check(root.get("schema_version")?.asInt == 1)
        check(root.get("library_revision")?.asString == revision)
        check(root.get("source_index_sha256")?.asString == sourceIndexSha256)
        val publications = root.getAsJsonArray("publications") ?: error("Generation has no publications")
        val manifestEntries = publications.associate { value ->
            val publication = value.asJsonObject
            val filename = publication.get("filename")?.asString?.takeIf(::isCanonicalPdfFilename)
                ?: error("Invalid generation filename")
            val sha256 = publication.get("sha256")?.asString?.takeIf(SHA256_REGEX::matches)
                ?: error("Invalid generation hash")
            val size = publication.get("size_bytes")?.asLong?.takeIf { it >= 0 }
                ?: error("Invalid generation size")
            val kind = when (publication.getAsJsonObject("metadata")?.get("kind")?.asString ?: "source_linked") {
                "source_linked" -> LibraryPdfKind.SOURCE_LINKED
                "unlinked" -> LibraryPdfKind.UNLINKED
                else -> error("Invalid generation kind")
            }
            filename to Triple(kind, sha256, size)
        }
        check(manifestEntries.size == publications.size())
        check(manifestEntries.keys == libraryEntries.keys)
        // This is coordinator metadata validity. Individual PDF availability is
        // represented in activatedPdfSha256ByFilename and checked by the owning reader.
        manifestEntries.all { (filename, manifest) ->
            val indexed = libraryEntries.getValue(filename)
            indexed.kind == manifest.first && indexed.sha256 == manifest.second
        }
    }.getOrDefault(false)
}

private data class IndexFileIdentity(
    val exists: Boolean,
    val length: Long,
    val modified: Long,
    // Index files are replaced by rename; the inode and nanosecond mtime tell apart two
    // same-sized rewrites within one millisecond, now that instances share parsed snapshots.
    val key: Any? = null,
    val modifiedNanos: Long = 0,
) {
    companion object {
        fun of(file: File): IndexFileIdentity {
            val attributes = runCatching {
                java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            }.getOrNull()
            return IndexFileIdentity(
                file.isFile, file.length(), file.lastModified(),
                attributes?.fileKey(),
                attributes?.lastModifiedTime()?.to(java.util.concurrent.TimeUnit.NANOSECONDS) ?: 0,
            )
        }
    }
}

private fun isCanonicalPdf(file: File, canonicalPdfDirectory: File): Boolean {
    return normalizedPath(file.parentFile) == normalizedPath(canonicalPdfDirectory) &&
        isCanonicalPdfFilename(file.name)
}

private fun isCanonicalPdfFilename(filename: String): Boolean {
    return filename.endsWith(".pdf") &&
        filename.length > ".pdf".length &&
        filename == File(filename).name &&
        !filename.contains('/') &&
        !filename.contains('\\')
}

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

private fun isValidLibraryDisplayName(value: String): Boolean =
    value.isNotEmpty() && value.length <= 200 && value.none { it.code < 32 || it.code == 127 }

private fun normalizedPath(file: File?): String? {
    return file?.absoluteFile?.normalize()?.path
}

/**
 * The local Library as this phone shows it: [base] (the active generation's index) with the queued
 * Library changes applied in order. An import shows while its filename is free in [base]; a rename
 * or archive change applies only to the exact PDF version it was made on.
 */
internal fun libraryMutationOverlay(
    base: SourceLinkedPdfIndexSnapshot,
    operations: List<LibraryMutation>,
    pdfFile: (LibraryMutation) -> File,
): SourceLinkedPdfIndexSnapshot {
    if (!base.libraryAvailable || operations.isEmpty()) return base
    val kinds = base.libraryKindsByFilename.toMutableMap()
    val hashes = base.libraryPdfSha256ByFilename.toMutableMap()
    val names = base.libraryDisplayNamesByFilename.toMutableMap()
    val archived = base.archivedLibraryFilenames.toMutableSet()
    val pending = linkedMapOf<String, File>()
    operations.forEach { operation ->
        val filename = operation.filename
        when (operation.type) {
            LibraryMutation.Type.ADD -> if (filename !in kinds) {
                kinds[filename] = LibraryPdfKind.UNLINKED
                hashes[filename] = operation.sha256
                pending[filename] = pdfFile(operation)
            }
            LibraryMutation.Type.RENAME -> if (hashes[filename] == operation.sha256) {
                names[filename] = operation.displayName
            }
            LibraryMutation.Type.ARCHIVE -> if (hashes[filename] == operation.sha256) {
                if (operation.archived) archived += filename else archived -= filename
            }
        }
    }
    return base.copy(
        libraryKindsByFilename = kinds,
        libraryPdfSha256ByFilename = hashes,
        libraryDisplayNamesByFilename = names,
        archivedLibraryFilenames = archived,
        pendingLibraryPdfFiles = pending,
    )
}
