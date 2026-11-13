// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CurrentSourceAnnotationsTest {
    private class Records : CurrentSourceAnnotationDao {
        val rows = mutableListOf<CurrentSourceAnnotation>()
        var fullReads = 0
        override suspend fun records(partition: String): List<CurrentSourceAnnotation> {
            fullReads++
            return rows.filter { it.partition == partition }.sortedWith(compareBy({ it.kind }, { it.id }))
        }
        override suspend fun smallRecords(partition: String, maxBytes: Int) = records(partition)
        override suspend fun largeRecordKeys(partition: String, maxBytes: Int) = emptyList<CurrentSourceAnnotationKey>()
        override suspend fun payloadBytes(partition: String, kind: String, id: String, start: Int, length: Int): ByteArray? = null
        override suspend fun hasGeneration(partition: String, generation: String) = rows.any {
            it.partition == partition && it.generation == generation && it.kind == "metadata-v2"
        }
        override suspend fun commentRecords(partition: String, generation: String) = rows.filter {
            it.partition == partition && it.generation == generation && it.kind == "catalogue_comment"
        }
        override suspend fun metadataGenerations(partitions: List<String>) = rows
            .filter { it.kind == "metadata-v2" && it.partition in partitions }
            .map { CurrentSourcePartitionGeneration(it.partition, it.generation) }
        override suspend fun catalogueRecords(partitions: List<String>) = rows
            .filter { it.kind == "catalogue_comment" && it.partition in partitions }
            .sortedWith(compareBy({ it.partition }, { it.id }))
        override suspend fun removePartition(partition: String) { rows.removeAll { it.partition == partition } }
        override suspend fun putRecords(records: List<CurrentSourceAnnotation>) { rows.addAll(records) }
    }
    private fun comment(id: String) = OfflineLogicalComment(
        id = id, threadRootId = id, parentId = null, revision = 1, deleted = false,
        scopeState = "pdf_only", sourceFile = "", sourceLine = 0, originCollection = "document",
        originPageIndex = 0, payloadJson = """{"comment_id":"$id","contents":"Repeated text"}""",
    )

    @Test fun independentlyEncodedRowsSurviveDatabaseOrderingAndReopening() {
        val dao = Records()
        val generation = "a".repeat(64)
        // Writing z before a used to create references into a previous row's string dictionary.
        val source = OfflineOverviewSourceSnapshot(bundleId = generation,
            logicalComments = listOf(comment("z"), comment("a")),
            pdfSha256ByFilename = mapOf("document.pdf" to "b".repeat(64)))
        CurrentSourceAnnotations(dao).write("project", source)
        val restored = CurrentSourceAnnotations(dao).read("project", generation)!!
        assertEquals(source.logicalComments.sortedBy { it.id }, restored.logicalComments)
        assertEquals(source.pdfSha256ByFilename, restored.pdfSha256ByFilename)
        assertNull(CurrentSourceAnnotations(dao).read("project", "c".repeat(64)))
    }

    @Test fun catalogueReadDoesNotDecodeUnrelatedProposalsOrOtherGenerations() {
        val dao = Records()
        val generation = "a".repeat(64)
        CurrentSourceAnnotations(dao).write("project", OfflineOverviewSourceSnapshot(
            bundleId = generation, logicalComments = listOf(comment("current"))))
        dao.rows.add(CurrentSourceAnnotation("project", "proposal", "large-proposal", generation, "invalid JSON"))
        dao.rows.add(CurrentSourceAnnotation("old-project", "catalogue_comment", "old", "b".repeat(64), "invalid JSON"))
        val restored = CurrentSourceAnnotations(dao).readComments("project", generation)!!
        assertEquals(listOf("current"), restored.logicalComments.map { it.id })
        assertEquals(0, dao.fullReads)
        assertNull(CurrentSourceAnnotations(dao).readComments("project", "c".repeat(64)))
    }

    @Test fun catalogueCanOpenFromCurrentDatabaseWithoutReadingSourceManifests() {
        val root = java.nio.file.Files.createTempDirectory("catalogue-current").toFile()
        try {
            val privateRoot = java.io.File(root, "private").apply { mkdirs() }
            val generation = "a".repeat(64)
            java.io.File(privateRoot, "active-index.json").writeText(
                """{"schema_version":3,"bundle_id":"$generation"}""")
            val dao = Records()
            val current = CurrentSourceAnnotations(dao)
            current.write(java.io.File(privateRoot, "overview-projection-v1.json").absolutePath,
                OfflineOverviewSourceSnapshot(bundleId = generation, logicalComments = listOf(comment("saved"))))
            val store = OfflineSourceBundleStore(java.io.File(root, "staged"), java.io.File(root, "working"),
                privateRoot, currentAnnotations = current)
            assertEquals(listOf("saved"), store.catalogueCommentSnapshot().logicalComments.map { it.id })
            assertEquals(0, dao.fullReads)
            assertFalse(java.io.File(privateRoot, "objects").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun legacyCrossRowEncodingRequestsOneTimeRecovery() = runBlocking {
        val dao = Records()
        dao.rows.add(CurrentSourceAnnotation("project", "metadata", "metadata", "a".repeat(64), "{}"))
        assertNull(CurrentSourceAnnotations(dao).readComments("project", "a".repeat(64)))
        assertNull(CurrentSourceAnnotations(dao).read("project", "a".repeat(64)))
    }

    @Test fun batchedCatalogueReadMatchesPerProjectReads() {
        val dao = Records()
        val annotations = CurrentSourceAnnotations(dao)
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        annotations.write("one", OfflineOverviewSourceSnapshot(bundleId = first, logicalComments = listOf(comment("x"), comment("y"))))
        annotations.write("two", OfflineOverviewSourceSnapshot(bundleId = second, logicalComments = emptyList()))
        val batched = annotations.readCommentsForGenerations(mapOf("one" to first, "two" to second, "three" to first, "stale" to second))
        assertEquals(setOf("one", "two"), batched.keys)
        assertEquals(annotations.readComments("one", first), batched["one"])
        assertEquals(annotations.readComments("two", second), batched["two"])
        // A partition whose rows belong to another generation is left to the per-project fallback.
        assertFalse(annotations.readCommentsForGenerations(mapOf("one" to second)).containsKey("one"))
    }
}
