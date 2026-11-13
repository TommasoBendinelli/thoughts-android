// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Current source-derived records; history and source/PDF bytes live separately. */
@Entity(tableName = "CurrentSourceAnnotation", primaryKeys = ["partition", "kind", "id"])
data class CurrentSourceAnnotation(
    val partition: String,
    val kind: String,
    val id: String,
    val generation: String,
    val payloadJson: String,
)

data class CurrentSourceAnnotationKey(val kind: String, val id: String, val generation: String)

data class CurrentSourcePartitionGeneration(val partition: String, val generation: String)

/** SQLite cursor windows hold about 2 MB, so larger payloads are read in parts. */
internal const val CURRENT_SOURCE_ANNOTATION_ROW_BYTES = 1_000_000
// Byte parts: each query walks the row's overflow pages from its start, so small character parts
// made reading a multi-megabyte row quadratic (about 0.75 s each on a cold Home load).
private const val CURRENT_SOURCE_ANNOTATION_PART_BYTES = 1_500_000

@Dao
interface CurrentSourceAnnotationDao {
    @Query("SELECT * FROM CurrentSourceAnnotation WHERE partition = :partition AND length(CAST(payloadJson AS BLOB)) <= :maxBytes")
    suspend fun smallRecords(partition: String, maxBytes: Int): List<CurrentSourceAnnotation>

    @Query("SELECT kind, id, generation FROM CurrentSourceAnnotation WHERE partition = :partition AND length(CAST(payloadJson AS BLOB)) > :maxBytes")
    suspend fun largeRecordKeys(partition: String, maxBytes: Int): List<CurrentSourceAnnotationKey>

    @Query("SELECT substr(CAST(payloadJson AS BLOB), :start, :length) FROM CurrentSourceAnnotation WHERE partition = :partition AND kind = :kind AND id = :id")
    suspend fun payloadBytes(partition: String, kind: String, id: String, start: Int, length: Int): ByteArray?

    // Not a @Transaction: Room runs transactions on its single writer connection, which serialized
    // the Home load's parallel project reads behind each other and behind Sync writes. A replace
    // between the two queries is caught by the caller, which rejects mixed generations or a
    // missing metadata row and falls back to the projection file.
    suspend fun records(partition: String): List<CurrentSourceAnnotation> {
        val large = largeRecordKeys(partition, CURRENT_SOURCE_ANNOTATION_ROW_BYTES).map { key ->
            // Join bytes before decoding, so a UTF-8 sequence split between parts stays intact.
            val payload = java.io.ByteArrayOutputStream()
            while (true) {
                val part = payloadBytes(partition, key.kind, key.id, payload.size() + 1, CURRENT_SOURCE_ANNOTATION_PART_BYTES)
                if (part == null || part.isEmpty()) break
                payload.write(part)
            }
            CurrentSourceAnnotation(partition, key.kind, key.id, key.generation, payload.toString(Charsets.UTF_8.name()))
        }
        return (smallRecords(partition, CURRENT_SOURCE_ANNOTATION_ROW_BYTES) + large).sortedWith(compareBy({ it.kind }, { it.id }))
    }

    @Query("SELECT EXISTS(SELECT 1 FROM CurrentSourceAnnotation WHERE partition = :partition AND generation = :generation AND kind = 'metadata-v2')")
    suspend fun hasGeneration(partition: String, generation: String): Boolean

    @Query("SELECT * FROM CurrentSourceAnnotation WHERE partition = :partition AND generation = :generation AND kind = 'catalogue_comment' ORDER BY id")
    suspend fun commentRecords(partition: String, generation: String): List<CurrentSourceAnnotation>

    @Transaction
    suspend fun commentsForGeneration(partition: String, generation: String): List<CurrentSourceAnnotation>? =
        if (hasGeneration(partition, generation)) commentRecords(partition, generation) else null

    @Query("SELECT partition, generation FROM CurrentSourceAnnotation WHERE kind = 'metadata-v2' AND partition IN (:partitions)")
    suspend fun metadataGenerations(partitions: List<String>): List<CurrentSourcePartitionGeneration>

    @Query("SELECT * FROM CurrentSourceAnnotation WHERE kind = 'catalogue_comment' AND partition IN (:partitions) ORDER BY partition, id")
    suspend fun catalogueRecords(partitions: List<String>): List<CurrentSourceAnnotation>

    /** One transaction for every project's catalogue rows instead of one per project. */
    @Transaction
    suspend fun commentsForGenerations(generations: Map<String, String>): Map<String, List<CurrentSourceAnnotation>> {
        val current = generations.keys.chunked(500).flatMap { metadataGenerations(it) }
            .filter { generations[it.partition] == it.generation }.map { it.partition }
        val rows = current.chunked(500).flatMap { catalogueRecords(it) }
            .filter { generations[it.partition] == it.generation }.groupBy { it.partition }
        return current.associateWith { rows[it].orEmpty() }
    }

    @Query("DELETE FROM CurrentSourceAnnotation WHERE partition = :partition")
    suspend fun removePartition(partition: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecords(records: List<CurrentSourceAnnotation>)

    @Transaction
    suspend fun replace(partition: String, records: List<CurrentSourceAnnotation>) {
        require(records.all { it.partition == partition })
        removePartition(partition)
        putRecords(records)
    }
}
