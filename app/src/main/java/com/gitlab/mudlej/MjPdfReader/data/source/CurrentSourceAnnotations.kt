// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Converts verified source reconciliation results to independently queryable current rows. */
internal class CurrentSourceAnnotations(private val dao: CurrentSourceAnnotationDao) {
    fun read(partition: String, generation: String): OfflineOverviewSourceSnapshot? = runBlocking(Dispatchers.IO) {
        val rows = dao.records(partition)
        if (rows.isEmpty() || rows.any { it.generation != generation }) return@runBlocking null
        val metadata = rows.singleOrNull { it.kind == "metadata-v2" } ?: return@runBlocking null
        val codec = SourceProjectionRowCodec()
        val base = codec.decode(metadata.payloadJson, OfflineOverviewSourceSnapshot::class.java)
        base.copy(
            proposals = rows.filter { it.kind == "proposal" }.map { codec.decode(it.payloadJson, OfflineAgentProposal::class.java) },
            reviewComments = rows.filter { it.kind == "source_comment" }.map { codec.decode(it.payloadJson, OfflineSourceReviewComment::class.java) },
            logicalComments = rows.filter { it.kind == "logical_comment" }.map { codec.decode(it.payloadJson, OfflineLogicalComment::class.java) },
        )
    }

    /** Catalogue reads exclude proposal bodies and PDF-placement geometry. */
    fun readComments(partition: String, generation: String): OfflineOverviewSourceSnapshot? = runBlocking(Dispatchers.IO) {
        val rows = dao.commentsForGeneration(partition, generation) ?: return@runBlocking null
        val codec = SourceProjectionRowCodec()
        OfflineOverviewSourceSnapshot(bundleId = generation, logicalComments = rows
            .map { codec.decode(it.payloadJson, OfflineLogicalComment::class.java) })
    }

    /** Catalogue rows for several partitions; a partition whose rows are missing or stale is absent. */
    fun readCommentsForGenerations(generations: Map<String, String>): Map<String, OfflineOverviewSourceSnapshot> = runBlocking(Dispatchers.IO) {
        if (generations.isEmpty()) return@runBlocking emptyMap()
        val codec = SourceProjectionRowCodec()
        dao.commentsForGenerations(generations).mapValues { (partition, rows) ->
            OfflineOverviewSourceSnapshot(bundleId = generations.getValue(partition), logicalComments = rows
                .map { codec.decode(it.payloadJson, OfflineLogicalComment::class.java) })
        }
    }

    fun write(partition: String, snapshot: OfflineOverviewSourceSnapshot) = runBlocking(Dispatchers.IO) {
        val generation = requireNotNull(snapshot.bundleId)
        val codec = SourceProjectionRowCodec()
        val rows = mutableListOf(CurrentSourceAnnotation(partition, "metadata-v2", "metadata", generation,
            codec.encode(snapshot.copy(proposals = emptyList(), reviewComments = emptyList(), logicalComments = emptyList()))))
        snapshot.proposals.forEach { rows += CurrentSourceAnnotation(partition, "proposal", it.id, generation, codec.encode(it)) }
        snapshot.reviewComments.forEach { rows += CurrentSourceAnnotation(partition, "source_comment", it.id, generation, codec.encode(it)) }
        snapshot.logicalComments.forEach { rows += CurrentSourceAnnotation(partition, "logical_comment", it.id, generation, codec.encode(it)) }
        snapshot.logicalComments.forEach { comment ->
            // Keep routing and current content; exact PDF geometry is loaded only by the reader.
            val light = comment.copy(correspondence = comment.correspondence?.copy(
                locations = comment.correspondence.locations.map { it.copy(regions = emptyList(), point = emptyList(), proof = null) },
            ))
            rows += CurrentSourceAnnotation(partition, "catalogue_comment", comment.id, generation, codec.encode(light))
        }
        require(rows.map { it.kind to it.id }.distinct().size == rows.size) { "Ambiguous current source annotations" }
        dao.replace(partition, rows)
    }
}
