// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** A lossless local representation; published artifacts and their proofs remain authoritative. */
internal fun compactSourceProjection(snapshot: OfflineOverviewSourceSnapshot): OfflineOverviewSourceSnapshot {
    val strings = HashMap<String, String>()
    fun intern(value: String): String = strings.getOrPut(value) { value }
    fun correspondence(value: ReviewCorrespondence?): ReviewCorrespondence? = value?.copy(
        locations = compactProjectionLocations(value.locations).map { location ->
            location.copy(
                pdfFilename = intern(location.pdfFilename), pdfSha256 = intern(location.pdfSha256),
                sourceSha256 = intern(location.sourceSha256), sourceBundleSha256 = intern(location.sourceBundleSha256),
                buildSha256 = intern(location.buildSha256), role = intern(location.role),
                status = intern(location.status), placementKind = intern(location.placementKind),
                proof = location.proof?.let { proof -> proof.copy(
                    method = intern(proof.method), sourceSha256 = intern(proof.sourceSha256),
                    sourceBundleSha256 = intern(proof.sourceBundleSha256), buildSha256 = intern(proof.buildSha256),
                    pdfSha256 = intern(proof.pdfSha256), extractedTextSha256 = intern(proof.extractedTextSha256),
                    uniqueness = intern(proof.uniqueness), reason = intern(proof.reason),
                    markerIds = proof.markerIds.map(::intern),
                ) },
            )
        },
    )
    return snapshot.copy(
        proposals = snapshot.proposals.map { proposal ->
            proposal.copy(
                correspondence = correspondence(proposal.correspondence),
                quads = proposal.quads.map { it.copy(pdfFilename = intern(it.pdfFilename), role = intern(it.role)) },
            )
        },
        logicalComments = snapshot.logicalComments.map { comment ->
            val proof = correspondence(comment.correspondence)
            comment.copy(
                correspondence = proof,
            )
        },
    )
}

/** Merge consecutive runs only, preserving rectangle order and every field of the proof. */
internal fun compactProjectionLocations(locations: List<LogicalCommentPdfLocation>): List<LogicalCommentPdfLocation> {
    val result = ArrayList<LogicalCommentPdfLocation>()
    var key: LogicalCommentPdfLocation? = null
    var rectangles: MutableList<List<Float>>? = null
    for (location in locations) {
        val nextKey = location.copy(regions = emptyList())
        val mergeable = location.regions.isNotEmpty() && location.point.isEmpty()
        if (mergeable && nextKey == key) {
            rectangles!!.addAll(location.regions)
        } else {
            key = nextKey.takeIf { mergeable }
            rectangles = location.regions.toMutableList()
            result.add(location.copy(regions = rectangles))
        }
    }
    return result
}

/** Independent project entries share only a bounded memory budget, never a parsing lock. */
internal object SourceProjectionMemoryCache {
    private data class Identity(val path: String, val bundleId: String, val modified: Long, val bytes: Long)
    private data class Entry(val snapshot: OfflineOverviewSourceSnapshot, val bytes: Long)
    private val monitor = Any()
    private val entries = LinkedHashMap<Identity, Entry>(16, 0.75f, true)
    private val pending = mutableMapOf<Identity, java.util.concurrent.CompletableFuture<OfflineOverviewSourceSnapshot?>>()
    private var retainedBytes = 0L
    internal const val MAX_ESTIMATED_BYTES = 48L * 1024 * 1024

    fun load(file: File, bundleId: String, loader: () -> OfflineOverviewSourceSnapshot?): OfflineOverviewSourceSnapshot? {
        val requested = Identity(file.absolutePath, bundleId, file.lastModified(), file.length())
        var owner = false
        val future = synchronized(monitor) {
            entries[requested]?.let { return it.snapshot }
            pending[requested] ?: java.util.concurrent.CompletableFuture<OfflineOverviewSourceSnapshot?>().also {
                pending[requested] = it
                owner = true
            }
        }
        if (!owner) return try { future.get() } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
        try {
            val result = loader()
            if (result != null) remember(file, result)
            future.complete(result)
            return result
        } catch (error: Throwable) {
            future.completeExceptionally(error)
            throw error
        } finally {
            synchronized(monitor) { pending.remove(requested, future) }
        }
    }

    fun remember(file: File, value: OfflineOverviewSourceSnapshot) {
        val bundleId = value.bundleId ?: return
        val bytes = estimatedProjectionBytes(value)
        val identity = Identity(file.absolutePath, bundleId, file.lastModified(), file.length())
        synchronized(monitor) {
            val obsolete = entries.keys.filter { it.path == identity.path }
            obsolete.forEach { retainedBytes -= entries.remove(it)!!.bytes }
            if (bytes > MAX_ESTIMATED_BYTES) return
            while (retainedBytes + bytes > MAX_ESTIMATED_BYTES && entries.isNotEmpty()) {
                retainedBytes -= entries.remove(entries.keys.first())!!.bytes
            }
            entries[identity] = Entry(value, bytes)
            retainedBytes += bytes
        }
    }

    fun clear() = synchronized(monitor) {
        entries.clear()
        retainedBytes = 0L
    }
}

internal fun estimatedProjectionBytes(snapshot: OfflineOverviewSourceSnapshot): Long {
    val strings = Collections.newSetFromMap(IdentityHashMap<String, Boolean>())
    var bytes = 1024L
    fun string(value: String) { if (strings.add(value)) bytes += 64L + value.length * 2L }
    fun locations(values: List<LogicalCommentPdfLocation>) {
        values.forEach { value ->
            bytes += 256L + value.regions.size * 128L + value.point.size * 24L
            listOf(value.pdfFilename, value.pdfSha256, value.sourceSha256, value.sourceBundleSha256,
                value.buildSha256, value.status, value.role, value.placementKind).forEach(::string)
            value.proof?.let { proof ->
                bytes += 256L
                listOf(proof.method, proof.sourceSha256, proof.sourceBundleSha256, proof.buildSha256,
                    proof.pdfSha256, proof.extractedTextSha256, proof.uniqueness, proof.reason).forEach(::string)
                proof.markerIds.forEach(::string)
            }
        }
    }
    fun correspondence(value: ReviewCorrespondence?) {
        value ?: return
        bytes += 256L
        listOf(value.reviewKey, value.kind, value.itemId, value.threadRootId, value.lifecycle,
            value.state, value.reason, value.sourceBundleSha256).forEach(::string)
        locations(value.locations)
        value.sourceTargets.forEach { target ->
            bytes += 256L
            listOf(target.sourceFile, target.sourceSha256, target.textSha256, target.preview, target.anchorId, target.role, target.renderIntent).forEach(::string)
        }
    }
    snapshot.proposals.forEach { proposal ->
        bytes += 256L + proposal.quads.size * 96L
        listOf(proposal.bundleId, proposal.objectSha256, proposal.id, proposal.comment, proposal.sourceFile,
            proposal.sourceSha256, proposal.bodyPreview, proposal.beforePreview, proposal.afterPreview).forEach(::string)
        proposal.parentId?.let(::string)
        string(proposal.kind)
        proposal.pdfFilenames.forEach(::string)
        proposal.quads.forEach { string(it.pdfFilename); string(it.role) }
        correspondence(proposal.correspondence)
    }
    snapshot.logicalComments.forEach { comment ->
        bytes += 256L
        listOf(comment.id, comment.threadRootId, comment.scopeState, comment.sourceFile,
            comment.originCollection, comment.payloadJson).forEach(::string)
        comment.parentId?.let(::string)
        correspondence(comment.correspondence)
        if (comment.locations !== comment.correspondence?.locations) locations(comment.locations)
    }
    snapshot.reviewComments.forEach {
        bytes += 256L
        listOf(it.id, it.contents, it.sourceFile, it.sourceSha256).forEach(::string)
        it.pdfFilenames.forEach(::string)
    }
    snapshot.reviewCommentDocuments.forEach(::string)
    snapshot.pdfSha256ByFilename.forEach { (filename, hash) -> bytes += 64L; string(filename); string(hash) }
    return bytes
}
