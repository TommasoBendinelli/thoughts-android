// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.source

import com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation
import com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewPlacementProof
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CompactSourceProjectionTest {
    private fun location(index: Int = 0) = LogicalCommentPdfLocation(
        "sample.pdf", "a".repeat(64), 1, "exact",
        regions = listOf(listOf(index / 20000f, 0.1f, (index + 1) / 20000f, 0.2f)),
        sourceSha256 = "b".repeat(64), sourceBundleSha256 = "c".repeat(64), buildSha256 = "d".repeat(64),
        role = "after", placementKind = "exact_text",
        proof = ReviewPlacementProof(3, "tex_shipout_text", "b".repeat(64), "c".repeat(64), "d".repeat(64),
            "a".repeat(64), "e".repeat(64), 1, "unique", true, "", false, listOf("start", "end"), 1),
    )

    private fun expand(locations: List<LogicalCommentPdfLocation>) = locations.flatMap { location ->
        if (location.regions.isEmpty()) listOf(location)
        else location.regions.map { location.copy(regions = listOf(it)) }
    }

    @Test fun diskCacheRetainsGeometryAndInvalidatesChangedIdentity() {
        val root = Files.createTempDirectory("projection-disk").toFile()
        val file = java.io.File(root, "projection.json").apply { writeText("first generation") }
        try {
            val snapshot = OfflineOverviewSourceSnapshot(bundleId = "first", logicalComments = listOf(
                OfflineLogicalComment("id", "id", null, 1, false, "exact", "source", 1, "paper", 0, "{}", placement("id", listOf(location()))),
            ))
            SourceProjectionDiskCache.write(file, snapshot)
            assertEquals(snapshot, SourceProjectionDiskCache.read(file, "first"))
            assertNull(SourceProjectionDiskCache.read(file, "other"))
            file.writeText("a different file generation")
            assertNull(SourceProjectionDiskCache.read(file, "first"))
        } finally { root.deleteRecursively() }
    }

    @Test fun largeGeometryRetainsEveryRectangleAndEveryProofFieldInOrder() {
        val source = (0 until 10000).map(::location)
        val compact = compactProjectionLocations(source)
        assertEquals(1, compact.size)
        assertEquals(source, expand(compact))
        assertTrue(source.all { it.regions.size == 1 })
    }

    @Test fun distinctPagesRolesHashesProofsAndPointsNeverShareGeometry() {
        val value = location()
        val variants = listOf(value, value.copy(page = 2), value.copy(role = "before"),
            value.copy(pdfFilename = "another.pdf"), value.copy(pdfSha256 = "f".repeat(64)),
            value.copy(sourceBundleSha256 = "f".repeat(64)), value.copy(status = "unavailable"),
            value.copy(proof = value.proof!!.copy(occurrence = 2)),
            value.copy(proof = value.proof.copy(extractedTextSha256 = "f".repeat(64))),
            value.copy(proof = value.proof.copy(markerIds = listOf("other", "end"))),
            value.copy(duplicate = true),
            value.copy(regions = emptyList(), point = listOf(0.1f, 0.2f), placementKind = "exact_point"), value)
        assertEquals(variants, compactProjectionLocations(variants))
    }

    @Test fun simultaneousReadersShareOneLoadAndChangedGenerationInvalidatesIt() {
        SourceProjectionMemoryCache.clear()
        val file = Files.createTempFile("projection", ".json").toFile().apply { writeText("first") }
        val executor = Executors.newFixedThreadPool(6)
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val futures = (0 until 6).map {
                executor.submit<OfflineOverviewSourceSnapshot?> {
                    ready.countDown(); start.await(5, TimeUnit.SECONDS)
                    SourceProjectionMemoryCache.load(file, "a") {
                        calls.incrementAndGet()
                        OfflineOverviewSourceSnapshot(bundleId = "a")
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val values = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            values.forEach { assertSame(values.first(), it) }
            assertEquals("b", SourceProjectionMemoryCache.load(file, "b") {
                calls.incrementAndGet(); OfflineOverviewSourceSnapshot(bundleId = "b")
            }?.bundleId)
            file.writeText("replaced with a different length")
            SourceProjectionMemoryCache.load(file, "b") { calls.incrementAndGet(); OfflineOverviewSourceSnapshot(bundleId = "b") }
            assertEquals(3, calls.get())
        } finally {
            start.countDown(); executor.shutdownNow(); file.delete(); SourceProjectionMemoryCache.clear()
        }
    }

    @Test fun projectsRetainIndependentEntriesAndDoNotWaitForAnotherProjectsLoader() {
        SourceProjectionMemoryCache.clear()
        val a = Files.createTempFile("project-a", ".json").toFile()
        val b = Files.createTempFile("project-b", ".json").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val slow = executor.submit<OfflineOverviewSourceSnapshot?> {
                SourceProjectionMemoryCache.load(a, "a") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    OfflineOverviewSourceSnapshot(bundleId = "a")
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val independent = executor.submit<OfflineOverviewSourceSnapshot?> {
                SourceProjectionMemoryCache.load(b, "b") { OfflineOverviewSourceSnapshot(bundleId = "b") }
            }.get(2, TimeUnit.SECONDS)
            assertEquals("b", independent?.bundleId)
            release.countDown()
            assertEquals("a", slow.get(5, TimeUnit.SECONDS)?.bundleId)
            assertSame(independent, SourceProjectionMemoryCache.load(b, "b") { error("Unrelated load evicted project B") })
            a.writeText("updated")
            SourceProjectionMemoryCache.load(a, "a2") { OfflineOverviewSourceSnapshot(bundleId = "a2") }
            assertSame(independent, SourceProjectionMemoryCache.load(b, "b") { error("Unrelated update evicted project B") })
        } finally {
            release.countDown(); executor.shutdownNow(); a.delete(); b.delete(); SourceProjectionMemoryCache.clear()
        }
    }

    @Test fun oversizedProjectionIsReturnedButNeverRetainedByTheCache() {
        SourceProjectionMemoryCache.clear()
        val file = Files.createTempFile("projection-budget", ".json").toFile()
        val large = OfflineOverviewSourceSnapshot(bundleId = "a", reviewComments = listOf(
            OfflineSourceReviewComment("id", "x".repeat(25 * 1024 * 1024), "source.tex", "b", 1, listOf("sample.pdf"))))
        assertTrue(estimatedProjectionBytes(large) > SourceProjectionMemoryCache.MAX_ESTIMATED_BYTES)
        var calls = 0
        try {
            repeat(2) { assertSame(large, SourceProjectionMemoryCache.load(file, "a") { calls++; large }) }
            assertEquals(2, calls)
        } finally { file.delete(); SourceProjectionMemoryCache.clear() }
    }
}

private fun placement(id: String, locations: List<com.gitlab.mudlej.MjPdfReader.data.annotation.LogicalCommentPdfLocation>) =
    com.gitlab.mudlej.MjPdfReader.data.annotation.ReviewCorrespondence(
        "comment:$id", "comment", id, id, "active", "source_linked", "", "", emptyList(), locations)
