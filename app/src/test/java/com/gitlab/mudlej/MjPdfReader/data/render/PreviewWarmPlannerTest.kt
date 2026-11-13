// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.render

import com.github.barteksc.pdfviewer.util.Constants
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreviewWarmPlannerTest {

    private lateinit var root: File
    private val encodes = mutableListOf<String>()
    private val opens = mutableListOf<String>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("preview-warm-planner").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun planner(budget: Long = BUDGET, bucket: Int = 1080) =
        PreviewWarmPlanner(root, bucket = bucket, budgetBytes = budget, pagesPerDocument = PAGES)

    /** Deterministic page sizes between 1000 and 2999 bytes. */
    private fun pageSize(docKey: String, page: Int): Int = 1000 + Math.floorMod((docKey.hashCode() * 31 + page) * 7919, 2000)

    private fun candidate(docKey: String, pageCount: Int = 20) = PreviewWarmPlanner.Candidate(docKey) {
        opens += docKey
        object : PreviewWarmPlanner.Source {
            override val pageCount: Int = pageCount
            override fun encode(page: Int, bucket: Int): ByteArray {
                encodes += "$docKey/$page"
                return ByteArray(pageSize(docKey, page)) { (page + bucket).toByte() }
            }
            override fun close() = Unit
        }
    }

    /** Newest first, like the worker's order. Mixed page counts, some below [PAGES]. */
    private fun library(count: Int, prefix: String = "doc") =
        (0 until count).map { candidate("$prefix$it", pageCount = if (it % 3 == 0) 3 else 20) }

    private fun warmedBytes(): Long = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".webp") }
        .sumOf(File::length)

    private fun warmedFiles(): Set<String> = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".webp") }
        .map { it.relativeTo(root).path }
        .toSet()

    @Test
    fun firstRunFillsBudgetAndStopsThenSecondRunRendersNothing() {
        val candidates = library(200)

        val first = planner().run(candidates)
        assertTrue(first.budgetReached)
        assertFalse(first.stopped)
        assertTrue(first.pagesRendered > 0)
        assertTrue(first.bytesHeld <= BUDGET)
        assertEquals(first.bytesHeld, warmedBytes())
        assertEquals(first.bytesWritten, warmedBytes())
        // Stopped long before the end of the library: a bounded prefix, not every document.
        assertTrue(first.documentsOpened < 40)
        val filesAfterFirst = warmedFiles()

        encodes.clear()
        opens.clear()
        val second = planner().run(candidates)
        assertEquals(0, second.pagesRendered)
        assertEquals(0, second.documentsOpened)
        assertEquals(0, second.pagesDeleted)
        assertEquals(0L, second.bytesWritten)
        assertEquals(first.bytesHeld, second.bytesHeld)
        assertTrue(encodes.isEmpty())
        assertTrue(opens.isEmpty())
        assertEquals(filesAfterFirst, warmedFiles())

        val third = planner().run(candidates)
        assertEquals(0, third.pagesRendered)
        assertEquals(filesAfterFirst, warmedFiles())
    }

    @Test
    fun smallLibraryIsWarmedCompletelyOnceWithoutReachingBudget() {
        val candidates = library(4)
        val first = planner().run(candidates)
        assertFalse(first.budgetReached)
        assertEquals(3 + PAGES + PAGES + 3, first.pagesRendered)
        assertEquals(4, first.targetDocuments)

        val second = planner().run(candidates)
        assertEquals(0, second.pagesRendered)
        assertEquals(0, second.documentsOpened)
    }

    @Test
    fun newerDocumentsDisplaceOldestAndStayWithinBudget() {
        val library = library(200)
        planner().run(library)
        val before = warmedFiles()

        encodes.clear()
        val newer = listOf(candidate("new0"), candidate("new1")) + library
        val displaced = planner().run(newer)
        assertTrue(displaced.bytesHeld <= BUDGET)
        assertEquals(displaced.bytesHeld, warmedBytes())
        assertTrue(displaced.pagesDeleted > 0)
        // Only the new documents were rendered; existing ones were counted, not re-rendered.
        assertTrue(encodes.all { it.startsWith("new") })
        val after = warmedFiles()
        assertTrue(after.any { it.startsWith("new0") } && after.any { it.startsWith("new1") })
        assertTrue((after - before).all { it.startsWith("new") })
        assertTrue((before - after).isNotEmpty())

        encodes.clear()
        val again = planner().run(newer)
        assertEquals(0, again.pagesRendered)
        assertEquals(after, warmedFiles())
    }

    @Test
    fun cancelledRunKeepsItsWorkAndTheNextRunFinishesWithoutRepeatingIt() {
        val candidates = library(200)
        var budgetChecks = 0
        val stopped = planner().run(candidates) { budgetChecks++ < 25 }
        assertTrue(stopped.stopped)
        assertTrue(stopped.pagesRendered > 0)
        val partial = warmedFiles()

        encodes.clear()
        val resumed = planner().run(candidates)
        assertFalse(resumed.stopped)
        assertTrue(resumed.bytesHeld <= BUDGET)
        assertTrue(warmedFiles().containsAll(partial))
        val reEncoded = encodes.map { encoded ->
            val (docKey, page) = encoded.split('/')
            "$docKey/${PdfWarmCache.previewFilename(page.toInt(), 1080)}"
        }
        assertTrue(reEncoded.none { it in partial })

        val reference = PreviewWarmPlannerTest().apply { root = Files.createTempDirectory("preview-warm-reference").toFile() }
        try {
            reference.planner().run(reference.library(200))
            assertEquals(reference.warmedFiles(), warmedFiles())
        } finally {
            reference.tearDown()
        }
    }

    @Test
    fun readerTrimIsRepairedOnlyForTheTrimmedPages() {
        val candidates = library(200)
        planner().run(candidates)
        val trimmed = warmedFiles().sorted().take(3)
        trimmed.forEach { File(root, it).delete() }

        encodes.clear()
        val repaired = planner().run(candidates)
        assertEquals(3, repaired.pagesRendered)
        assertEquals(trimmed.toSet(), warmedFiles().filter { it in trimmed }.toSet())
        assertTrue(repaired.bytesHeld <= BUDGET)
    }

    @Test
    fun filesNotOwnedByTheWarmerAreNeverDeleted() {
        val candidates = library(200)
        planner().run(candidates)
        // The reader writes previews for an opened, older document and for later pages.
        val readerFile = File(File(root, "doc150"), PdfWarmCache.previewFilename(14, 1080)).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(5000))
        }
        val readerLaterPage = File(File(root, "doc0"), PdfWarmCache.previewFilename(PAGES + 2, 1080)).apply {
            writeBytes(ByteArray(5000))
        }
        planner().run(listOf(candidate("new0"), candidate("new1")) + candidates)
        assertTrue(readerFile.isFile)
        assertTrue(readerLaterPage.isFile)
    }

    @Test
    fun bucketChangeDeletesFilesOfTheOldWidth() {
        val candidates = library(200)
        planner(bucket = 1080).run(candidates)
        val other = planner(bucket = 810).run(candidates)
        assertTrue(other.pagesDeleted > 0)
        assertTrue(warmedFiles().none { it.contains("-w1080-") })
        assertTrue(warmedBytes() <= BUDGET)
    }

    @Test
    fun unreadablePdfIsNotOpenedAgain() {
        var attempts = 0
        val broken = PreviewWarmPlanner.Candidate("broken") {
            attempts += 1
            throw IllegalStateException("not a PDF")
        }
        planner().run(listOf(broken) + library(3))
        val second = planner().run(listOf(broken) + library(3))
        assertEquals(1, attempts)
        assertEquals(0, second.pagesRendered)
    }

    @Test
    fun warmBudgetLeavesRoomUnderTheReadersTrimBudget() {
        assertTrue(PdfWarmCache.WARM_BUDGET_BYTES < Constants.PREVIEW_DISK_BUDGET_BYTES)
    }

    @Test
    fun workerRunsOnlyWhileChargingIdleAndBatteryNotLow() {
        val constraints = pdfWarmCacheConstraints()
        assertTrue(constraints.requiresCharging())
        // Constraints.Builder drops the idle flag below API 23, and host JVM tests see SDK_INT 0.
        if (android.os.Build.VERSION.SDK_INT >= 23) assertTrue(constraints.requiresDeviceIdle())
        assertTrue(constraints.requiresBatteryNotLow())
    }

    private companion object {
        const val PAGES = 8
        const val BUDGET = 150_000L
    }
}
