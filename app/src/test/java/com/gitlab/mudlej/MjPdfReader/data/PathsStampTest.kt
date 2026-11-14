// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathsStampTest {
    private val root: File = Files.createTempDirectory("paths-stamp").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun theStampChangesWhenAFileOrADirectoryIsWrittenAndNotOtherwise() {
        val directory = File(root, "requests").apply { mkdirs() }
        val file = File(root, "head.json").apply { writeText("{}") }
        val absent = File(root, "absent")
        fun stamp(generation: Long = 1) = pathsStamp(listOf(directory, file, absent), extra = listOf("pending:$generation"))
        val initial = stamp()
        assertEquals(initial, stamp())
        File(directory, "a.json").writeText("{}")
        val added = stamp()
        assertNotEquals(initial, added)
        file.writeText("{\"a\":1}")
        val rewritten = stamp()
        assertNotEquals(added, rewritten)
        absent.writeText("now here")
        assertNotEquals(rewritten, stamp())
        assertNotEquals(stamp(1), stamp(2))
    }

    /** Home's idle check, every 10 s for an hour, with one change in the middle. */
    @Test
    fun anIdleHourReadsEverythingOnTheChangeAndOnTheBackstopOnly() {
        val gate = IdleRefreshGate(backstopMillis = 5 * 60_000)
        var reads = 0
        var stamp = "a"
        for (tick in 0 until 360) {
            val now = 1_000_000L + tick * 10_000L
            if (tick == 100) stamp = "b"
            if (gate.shouldRefresh(stamp, now)) {
                reads++
                gate.refreshed(stamp, now)
            }
        }
        // 360 full reads before: now the first, the change, and one per five quiet minutes.
        assertEquals(13, reads)
    }

    @Test
    fun aStampThatCannotBeTakenAlwaysReadsAndAResetReadsOnce() {
        val gate = IdleRefreshGate(backstopMillis = 60_000)
        assertTrue(gate.shouldRefresh("a", 0))
        gate.refreshed("a", 0)
        assertFalse(gate.shouldRefresh("a", 10_000))
        assertTrue(gate.shouldRefresh("", 10_000))
        gate.reset()
        assertTrue(gate.shouldRefresh("a", 20_000))
        gate.refreshed("a", 20_000)
        assertFalse(gate.shouldRefresh("a", 30_000))
        assertTrue(gate.shouldRefresh("a", 80_000))
    }
}

class ParsedFileCacheTest {
    private val root: File = Files.createTempDirectory("parsed-file-cache").toFile()
    private val file = File(root, "state.json")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class Parsed(var text: String)

    @Test
    fun aFileIsReadOnceWhileItStaysAsItIs() {
        file.writeText("first")
        val cache = ParsedFileCache<Parsed> { Parsed(it.text) }
        var reads = 0
        fun read() = cache.get(file) { reads++; Parsed(file.readText()) }
        // The background thread reads it; the main thread then draws three times without reading.
        assertEquals("first", read().text)
        repeat(3) { assertEquals("first", read().text) }
        assertEquals(1, reads)
    }

    @Test
    fun everyCallerGetsItsOwnCopy() {
        file.writeText("first")
        val cache = ParsedFileCache<Parsed> { Parsed(it.text) }
        val first = cache.get(file) { Parsed(file.readText()) }
        first.text = "changed by a caller"
        assertEquals("first", cache.get(file) { error("must not read again") }.text)
    }

    @Test
    fun aWriteIsSeenByItsStampAndByTheWriterSayingSo() {
        file.writeText("first")
        val cache = ParsedFileCache<Parsed> { Parsed(it.text) }
        fun read() = cache.get(file) { Parsed(file.readText()) }.text
        assertEquals("first", read())
        // Another length: the stamp differs.
        file.writeText("second, longer")
        assertEquals("second, longer", read())
        // Same length within the same millisecond: only the writer knows.
        val modified = file.lastModified()
        file.writeText("third, longer!")
        file.setLastModified(modified)
        assertEquals("second, longer", read())
        cache.invalidate()
        assertEquals("third, longer!", read())
    }

    @Test
    fun whatElseTheValueDependsOnIsPartOfItsKey() {
        file.writeText("first")
        val cache = ParsedFileCache<Parsed> { Parsed(it.text) }
        var reads = 0
        fun read(generation: Long) = cache.get(file, generation) { reads++; Parsed(file.readText()) }
        read(1); read(1); read(2); read(2)
        assertEquals(2, reads)
    }

    @Test
    fun aFailedReadKeepsNothing() {
        val cache = ParsedFileCache<Parsed> { Parsed(it.text) }
        assertTrue(runCatching { cache.get(file) { error("unreadable") } }.isFailure)
        file.writeText("now readable")
        assertEquals("now readable", cache.get(file) { Parsed(file.readText()) }.text)
    }
}

class BackgroundOnceTest {
    @Test
    fun theTaskRunsOnceOnItsOwnThreadAndTheStarterDoesNotWait() {
        val release = java.util.concurrent.CountDownLatch(1)
        val runs = java.util.concurrent.atomic.AtomicInteger()
        var ranOn: Thread? = null
        val once = BackgroundOnce("background-once-test")
        val key = Any()
        repeat(5) {
            once.start(key) { ranOn = Thread.currentThread(); runs.incrementAndGet(); release.await() }
        }
        // start() returned five times while the task still waits: nobody ran it where they stood.
        assertEquals(1, release.count)
        release.countDown()
        once.await(key)
        assertEquals(1, runs.get())
        assertTrue(ranOn !== Thread.currentThread())
        assertEquals("background-once-test", ranOn?.name)
    }

    @Test
    fun whoeverNeedsTheResultWaitsForIt() {
        val once = BackgroundOnce("background-once-test")
        val key = Any()
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        once.start(key) { Thread.sleep(50); steps += "imported" }
        once.await(key)
        steps += "read"
        assertEquals(listOf("imported", "read"), steps)
        // Later readers do not wait again, and a key nobody started is not waited for.
        once.await(key)
        once.await(Any())
    }

    @Test
    fun eachKeyHasItsOwnTaskAndAFailureDoesNotStopTheReaders() {
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val once = BackgroundOnce("background-once-test") { failures += it.message.orEmpty() }
        val first = Any()
        val second = Any()
        val runs = java.util.concurrent.atomic.AtomicInteger()
        once.start(first) { runs.incrementAndGet(); error("database unavailable") }
        once.start(second) { runs.incrementAndGet() }
        once.await(first)
        once.await(second)
        assertEquals(2, runs.get())
        assertEquals(listOf("database unavailable"), failures)
    }
}
