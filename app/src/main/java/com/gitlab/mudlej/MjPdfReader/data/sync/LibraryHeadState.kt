// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import android.content.Context
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderFailure
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderJobStatus
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStage
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderStatusSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.ExplicitPdfRenderStatus
import com.gitlab.mudlej.MjPdfReader.data.render.SourceLinkedPdfIndexSnapshot
import com.gitlab.mudlej.MjPdfReader.data.render.isCanonicalPublishedPdfFilename
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tbend.thoughts.sdk.LibrarySnapshot
import org.tbend.thoughts.sdk.LibraryEntry
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class RemoteLibraryPublication(
    val filename: String,
    val sha256: String,
    val sizeBytes: Long,
    val publishedBuildId: String,
    val latestBuildId: String,
    val latestBuildStatus: String,
    val pdfStale: Boolean,
    val error: String,
    val failedDocuments: List<PhoneRenderFailure>,
    val sourceArtifactSha256: String = "",
)

data class RemoteLibraryHead(
    val libraryRevision: String,
    val sourceIndexSha256: String,
    val sourceBundleSha256: String,
    val fetchedAtEpochMillis: Long,
    val publications: Map<String, RemoteLibraryPublication>,
)

class LibraryHeadStateStore internal constructor(
    private val stateFile: File,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "thoughts-sync/library-head.json"),
    )

    @Synchronized
    fun load(): RemoteLibraryHead? {
        if (!stateFile.isFile || stateFile.length() !in 1..MAX_STATE_BYTES) return null
        return runCatching {
            val value = JsonParser.parseString(stateFile.readText(StandardCharsets.UTF_8)).asJsonObject
            check(value.int("schema_version") == SCHEMA_VERSION)
            val revision = value.string("library_revision").also { require(it.matches(SHA256)) }
            val sourceIndex = value.string("source_index_sha256").also {
                require(it.isBlank() || it.matches(SHA256))
            }
            val sourceBundle = value.string("source_bundle_sha256").also {
                require(it.isBlank() || it.matches(SHA256))
            }
            val publications = (value.getAsJsonArray("publications") ?: JsonArray()).associate { item ->
                val publication = item.asJsonObject
                val filename = publication.string("filename").also {
                    require(isCanonicalPublishedPdfFilename(it))
                }
                val sha256 = publication.string("sha256").also { require(it.matches(SHA256)) }
                val sizeBytes = publication.long("size_bytes").also { require(it >= 0) }
                val failedDocuments = (publication.getAsJsonArray("failed_documents") ?: JsonArray())
                    .mapNotNull { failureValue ->
                        val failure = failureValue.takeIf { it.isJsonObject }?.asJsonObject
                            ?: return@mapNotNull null
                        val failedFilename = failure.string("pdf_filename")
                            .takeIf(::isCanonicalPublishedPdfFilename)
                            ?: return@mapNotNull null
                        PhoneRenderFailure(
                            pdfFilename = failedFilename,
                            source = failure.string("source"),
                            message = failure.string("message"),
                        )
                    }
                filename to RemoteLibraryPublication(
                    filename = filename,
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    publishedBuildId = publication.string("published_build_id"),
                    latestBuildId = publication.string("latest_build_id"),
                    latestBuildStatus = publication.string("latest_build_status"),
                    pdfStale = publication.boolean("pdf_stale"),
                    error = publication.string("error").take(MAX_ERROR_CHARS),
                    failedDocuments = failedDocuments,
                    sourceArtifactSha256 = publication.string("source_artifact_sha256")
                        .takeIf(SHA256::matches)
                        .orEmpty(),
                )
            }
            check(publications.size == (value.getAsJsonArray("publications") ?: JsonArray()).size())
            RemoteLibraryHead(
                libraryRevision = revision,
                sourceIndexSha256 = sourceIndex,
                sourceBundleSha256 = sourceBundle,
                fetchedAtEpochMillis = value.long("fetched_at_epoch_ms").coerceAtLeast(0),
                publications = publications,
            )
        }.getOrNull()
    }

    @Synchronized
    fun publish(snapshot: LibrarySnapshot, fetchedAtEpochMillis: Long = System.currentTimeMillis()): RemoteLibraryHead {
        return write(snapshot.toRemoteLibraryHead(fetchedAtEpochMillis))
    }

    /** Replaces only one publication while retaining the global Library head and its revision. */
    @Synchronized
    fun publishDocument(
        publication: LibraryEntry,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ): RemoteLibraryHead? {
        val current = load() ?: return null
        val replacement = LibrarySnapshot(
            publications = listOf(publication),
            latestServerSequence = 0,
            sourceIndexSha256 = current.sourceIndexSha256,
            sourceBundleSha256 = current.sourceBundleSha256,
            libraryRevision = current.libraryRevision,
        ).toRemoteLibraryHead(fetchedAtEpochMillis).publications.getValue(publication.filename)
        val publications = current.publications + (publication.filename to replacement)
        return write(
            current.copy(
                sourceBundleSha256 = sourceArtifactSetSha256(
                    publications.values.map { it.filename to it.sourceArtifactSha256 },
                ),
                fetchedAtEpochMillis = fetchedAtEpochMillis,
                publications = publications,
            ),
        )
    }

    @Synchronized
    fun refreshValidation(
        libraryRevision: String,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ): RemoteLibraryHead? {
        val current = load()?.takeIf { it.libraryRevision == libraryRevision } ?: return null
        return write(current.copy(fetchedAtEpochMillis = fetchedAtEpochMillis))
    }

    private fun write(head: RemoteLibraryHead): RemoteLibraryHead {
        val value = JsonObject().apply {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("library_revision", head.libraryRevision)
            addProperty("source_index_sha256", head.sourceIndexSha256)
            addProperty("source_bundle_sha256", head.sourceBundleSha256)
            addProperty("fetched_at_epoch_ms", head.fetchedAtEpochMillis)
            add("publications", JsonArray().apply {
                head.publications.values.sortedBy(RemoteLibraryPublication::filename).forEach { publication ->
                    add(JsonObject().apply {
                        addProperty("filename", publication.filename)
                        addProperty("sha256", publication.sha256)
                        addProperty("size_bytes", publication.sizeBytes)
                        addProperty("published_build_id", publication.publishedBuildId)
                        addProperty("latest_build_id", publication.latestBuildId)
                        addProperty("latest_build_status", publication.latestBuildStatus)
                        addProperty("pdf_stale", publication.pdfStale)
                        addProperty("error", publication.error)
                        publication.sourceArtifactSha256.takeIf(String::isNotBlank)?.let {
                            addProperty("source_artifact_sha256", it)
                        }
                        add("failed_documents", JsonArray().apply {
                            publication.failedDocuments.forEach { failure ->
                                add(JsonObject().apply {
                                    addProperty("pdf_filename", failure.pdfFilename)
                                    addProperty("source", failure.source)
                                    addProperty("message", failure.message)
                                })
                            }
                        })
                    })
                }
            })
        }
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, ".${stateFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write((value.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            check(temporary.renameTo(stateFile)) { "Could not publish the server Library head" }
        } finally {
            temporary.delete()
        }
        return head
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_STATE_BYTES = 2_000_000L
        const val MAX_ERROR_CHARS = 2_000
        val SHA256 = Regex("^[0-9a-f]{64}$")

        fun JsonObject.string(name: String): String = get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            .orEmpty()

        fun JsonObject.long(name: String): Long =
            runCatching { get(name)?.asLong }.getOrNull() ?: 0

        fun JsonObject.int(name: String): Int =
            runCatching { get(name)?.asInt }.getOrNull() ?: 0

        fun JsonObject.boolean(name: String): Boolean =
            runCatching { get(name)?.asBoolean }.getOrNull() == true
    }
}

private fun LibrarySnapshot.toRemoteLibraryHead(fetchedAtEpochMillis: Long): RemoteLibraryHead {
    require(libraryRevision.matches(Regex("^[0-9a-f]{64}$")))
    val entries = publications.associate { publication ->
        require(isCanonicalPublishedPdfFilename(publication.filename))
        require(publication.sha256.matches(Regex("^[0-9a-f]{64}$")) && publication.sizeBytes >= 0)
        val latestBuild = publication.latestBuild
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val failures = (latestBuild?.getAsJsonArray("failed_documents") ?: JsonArray())
            .mapNotNull { value ->
                val failure = value.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@mapNotNull null
                val filename = failure.get("pdf_filename")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.takeIf(::isCanonicalPublishedPdfFilename)
                    ?: return@mapNotNull null
                PhoneRenderFailure(
                    pdfFilename = filename,
                    source = failure.get("source")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    message = failure.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                )
            }
        val error = latestBuild?.get("error")
            ?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            .orEmpty()
            .ifBlank { failures.firstOrNull { it.pdfFilename == publication.filename }?.message.orEmpty() }
        publication.filename to RemoteLibraryPublication(
            filename = publication.filename,
            sha256 = publication.sha256,
            sizeBytes = publication.sizeBytes,
            publishedBuildId = publication.buildId.orEmpty(),
            latestBuildId = latestBuild?.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            latestBuildStatus = latestBuild?.get("status")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            pdfStale = publication.pdfStale,
            error = error,
            failedDocuments = failures,
            sourceArtifactSha256 = publication.sourceArtifactSha256.orEmpty(),
        )
    }
    check(entries.size == publications.size)
    return RemoteLibraryHead(
        libraryRevision = libraryRevision,
        sourceIndexSha256 = sourceIndexSha256.orEmpty(),
        sourceBundleSha256 = sourceArtifactSetSha256(),
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        publications = entries,
    )
}

internal fun LibrarySnapshot.sourceArtifactSetSha256(): String = sourceArtifactSetSha256(
    publications.map { it.filename to it.sourceArtifactSha256.orEmpty() },
)

private fun sourceArtifactSetSha256(entries: Iterable<Pair<String, String>>): String {
    val identities = entries
        .filter { (_, digest) -> digest.matches(Regex("^[0-9a-f]{64}$")) }
        .sortedBy(Pair<String, String>::first)
    if (identities.isEmpty()) return ""
    val content = identities.joinToString("\n", postfix = "\n") { (filename, digest) ->
        "$filename\t$digest"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun authoritativeLocalPdfVerificationTimes(
    remoteHead: RemoteLibraryHead?,
    localLibrary: SourceLinkedPdfIndexSnapshot,
): Map<String, Long> {
    if (
        remoteHead == null ||
        remoteHead.fetchedAtEpochMillis <= 0
    ) {
        return emptyMap()
    }
    return remoteHead.publications.values
        .asSequence()
        .filterNot(RemoteLibraryPublication::pdfStale)
        .filter { publication ->
            localLibrary.activatedPdfSha256ByFilename[publication.filename] == publication.sha256
        }
        .associate { publication ->
            publication.filename to remoteHead.fetchedAtEpochMillis
        }
}

internal fun supersededExplicitPdfRenderStatusFilenames(
    remoteHead: RemoteLibraryHead?,
    localLibrary: SourceLinkedPdfIndexSnapshot,
    statuses: List<ExplicitPdfRenderStatus>,
): Set<String> {
    if (
        remoteHead == null ||
        remoteHead.fetchedAtEpochMillis <= 0
    ) {
        return emptySet()
    }
    return statuses.mapNotNullTo(linkedSetOf()) { status ->
        val publication = remoteHead.publications[status.filename] ?: return@mapNotNullTo null
        val localExact = !publication.pdfStale &&
            localLibrary.activatedPdfSha256ByFilename[status.filename] == publication.sha256
        if (!localExact || remoteHead.fetchedAtEpochMillis < status.checkedAtEpochMillis) {
            return@mapNotNullTo null
        }
        val superseded = if (status.status == "synced") {
            status.publicationSha256 == publication.sha256 &&
                (status.publicationBuildId.isNullOrBlank() ||
                    status.publicationBuildId == publication.publishedBuildId)
        } else {
            !status.publicationBuildId.isNullOrBlank() &&
                publication.publishedBuildId.isNotBlank() &&
                status.publicationBuildId != publication.publishedBuildId
        }
        status.filename.takeIf { superseded }
    }
}

internal fun reconcileRemoteLibraryStatus(
    snapshot: PhoneRenderStatusSnapshot,
    remoteHead: RemoteLibraryHead?,
    localLibrary: SourceLinkedPdfIndexSnapshot,
): PhoneRenderStatusSnapshot {
    remoteHead ?: return snapshot
    val replacementFilenames = remoteHead.publications.values
        .filterNot(RemoteLibraryPublication::pdfStale)
        .mapTo(linkedSetOf(), RemoteLibraryPublication::filename)
    val retained = snapshot.jobs.mapNotNull { job ->
        if (
            job.target in replacementFilenames &&
            job.stage in setOf(
                PhoneRenderStage.FAILED,
                PhoneRenderStage.PARTIAL,
                PhoneRenderStage.ATTENTION,
            )
        ) {
            return@mapNotNull null
        }
        if (job.failedDocuments.none { it.pdfFilename in replacementFilenames }) {
            val supersededAttention = job.attentionPdfs.any {
                it.filename in replacementFilenames
            }
            val supersededAffected = job.affectsAll &&
                job.affectedPdfFilenames.isNotEmpty() &&
                job.affectedPdfFilenames.any(replacementFilenames::contains) &&
                job.activityAtEpochMillis in 1..remoteHead.fetchedAtEpochMillis
            if (!supersededAttention && !supersededAffected) {
                return@mapNotNull job
            }
        }
        val failures = job.failedDocuments.filterNot { it.pdfFilename in replacementFilenames }
        val attention = job.attentionPdfs.filterNot { it.filename in replacementFilenames }
        val affected = if (job.activityAtEpochMillis in 1..remoteHead.fetchedAtEpochMillis) {
            job.affectedPdfFilenames.filterNotTo(linkedSetOf(), replacementFilenames::contains)
        } else {
            job.affectedPdfFilenames
        }
        val scopedFailureResolved = job.affectsAll &&
            job.stage in setOf(
                PhoneRenderStage.BLOCKED,
                PhoneRenderStage.ATTENTION,
                PhoneRenderStage.PARTIAL,
                PhoneRenderStage.FAILED,
            ) &&
            failures.isEmpty() &&
            attention.isEmpty() &&
            (
                job.failedDocuments.isNotEmpty() ||
                    job.attentionPdfs.isNotEmpty() ||
                    job.affectedPdfFilenames.isNotEmpty()
                ) &&
            job.activityAtEpochMillis in 1..remoteHead.fetchedAtEpochMillis
        if (scopedFailureResolved) {
            null
        } else {
            job.copy(
                failedDocuments = failures,
                attentionPdfs = attention,
                affectedPdfFilenames = affected,
            )
        }
    }.toMutableList()

    remoteHead.publications.values.forEach { publication ->
        val localExact =
            localLibrary.activatedPdfSha256ByFilename[publication.filename] == publication.sha256
        if (publication.pdfStale) {
            val failure = publication.failedDocuments
                .firstOrNull { it.pdfFilename == publication.filename }
            val stage = when (publication.latestBuildStatus) {
                "attention" -> PhoneRenderStage.ATTENTION
                "failed", "failure" -> PhoneRenderStage.FAILED
                "queued", "running" -> PhoneRenderStage.REBUILDING
                else -> PhoneRenderStage.OUT_OF_SYNC
            }
            retained += PhoneRenderJobStatus(
                requestId = "remote-library:${publication.latestBuildId.ifBlank { publication.filename }}",
                target = publication.filename,
                stage = stage,
                buildId = publication.latestBuildId,
                message = publication.error.ifBlank {
                    failure?.message.orEmpty().ifBlank {
                        if (stage == PhoneRenderStage.OUT_OF_SYNC) {
                            "The server must build this PDF from the synchronized sources."
                        } else {
                            ""
                        }
                    }
                },
                completedAtEpochMillis = remoteHead.fetchedAtEpochMillis,
                bannerEligible = true,
                failedDocuments = listOfNotNull(failure),
            )
        } else if (!localExact) {
            retained += PhoneRenderJobStatus(
                requestId = "remote-library:${remoteHead.libraryRevision}:${publication.filename}",
                target = publication.filename,
                stage = PhoneRenderStage.WAITING_FOR_SYNC,
                message = "A newer PDF is ready on the server",
                completedAtEpochMillis = remoteHead.fetchedAtEpochMillis,
                bannerEligible = false,
                publishedPdfCount = 1,
                syncedPdfCount = 0,
                pendingPdfFilenames = setOf(publication.filename),
                changesDetected = true,
            )
        }
    }
    return PhoneRenderStatusSnapshot(
        retained.sortedByDescending(PhoneRenderJobStatus::activityAtEpochMillis),
    )
}
