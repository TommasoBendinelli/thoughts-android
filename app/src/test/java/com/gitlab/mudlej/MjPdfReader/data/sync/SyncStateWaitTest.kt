// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /v2/sync/state?wait_ms=` ([waitForSyncState]) against a small server that holds the
 * request as the Thoughts server does: until its state differs from the If-None-Match, or the wait.
 */
class SyncStateWaitTest {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var version = 41
    private val etag get() = "v$version"
    /** The server answers 304 at once instead of holding (too many waiting, or an older server). */
    @Volatile private var holds = true
    @Volatile private var status = 200
    private val asked = mutableListOf<String>()
    private val released = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/v2/sync/state") { exchange -> answer(exchange) }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val kept = ConditionalAnswer<JsonObject>()

    @After
    fun stop() {
        released.countDown()
        lock.withLock { changed.signalAll() }
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }

    private fun answer(exchange: HttpExchange) {
        val sent = exchange.requestHeaders.getFirst("If-None-Match")
        val waitMillis = exchange.requestURI.query.orEmpty().split('&')
            .firstOrNull { it.startsWith("wait_ms=") }?.removePrefix("wait_ms=")?.toLongOrNull() ?: 0L
        synchronized(asked) { asked += exchange.requestURI.toString() }
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            return
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        lock.withLock {
            while (holds && sent == "\"$etag\"" && System.nanoTime() < deadline && released.count > 0) {
                changed.awaitNanos(deadline - System.nanoTime())
            }
        }
        val current = lock.withLock { version }
        exchange.responseHeaders.add("ETag", "\"v$current\"")
        if (sent == "\"v$current\"") {
            exchange.sendResponseHeaders(304, -1)
            exchange.close()
            return
        }
        val raw = """{"contract_version":2,"latest_server_sequence":$current,"library_revision":"r","source_frontier":3}"""
            .toByteArray()
        runCatching {
            exchange.sendResponseHeaders(200, raw.size.toLong())
            exchange.responseBody.use { it.write(raw) }
        }
    }

    private fun change() = lock.withLock {
        version++
        changed.signalAll()
    }

    private fun read(): JsonObject? {
        val connection = URL("$base/v2/sync/state").openConnection() as HttpURLConnection
        return try {
            readSyncState(connection, "token-" + "x".repeat(4), base, kept)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun held(waitMillis: Long): SyncStateWait = waitForSyncState(
        URL("$base/v2/sync/state?wait_ms=$waitMillis"),
        open = { it.openConnection() as HttpURLConnection },
        token = "token-" + "x".repeat(4),
        key = base,
        kept = kept,
        waitMillis = waitMillis,
    )

    @Test
    fun aChangeDuringTheWaitIsAnsweredAtOnceAndTheNextReadTakesIt() = runBlocking {
        read()
        val waiting = async(Dispatchers.IO) {
            val started = System.nanoTime()
            held(20_000) to (System.nanoTime() - started) / 1_000_000
        }
        delay(300)
        change()
        val changedAt = System.nanoTime()
        val (outcome, _) = withTimeout(5_000) { waiting.await() }
        val afterChange = (System.nanoTime() - changedAt) / 1_000_000
        assertEquals(SyncStateWait.CHANGED, outcome)
        assertTrue("answered $afterChange ms after the change", afterChange < 1_000)
        // The check that follows takes the new state without asking; the one after asks again.
        val taken = kept.takeConfirmed(base, monotonicMillis(), 3_000)
        assertEquals(42L, taken?.get("latest_server_sequence")?.asLong)
        assertNull(kept.takeConfirmed(base, monotonicMillis(), 3_000))
        assertEquals(listOf("/v2/sync/state", "/v2/sync/state?wait_ms=20000"), asked)
    }

    @Test
    fun nothingChangingTheServerAnswersUnchangedAfterTheWait() = runBlocking {
        val first = read()
        val started = System.nanoTime()
        assertEquals(SyncStateWait.UNCHANGED, held(600))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 550)
        assertSame(first, kept.takeConfirmed(base, monotonicMillis(), 3_000))
    }

    @Test
    fun anUnchangedAnswerThatCameAtOnceWasNotHeld() = runBlocking {
        read()
        holds = false
        assertEquals(SyncStateWait.NOT_HELD, held(5_000))
    }

    @Test
    fun aRefusalOrNothingToWaitOnIsAFailure() = runBlocking {
        // Nothing kept: no ETag to wait on, and no request.
        assertEquals(SyncStateWait.FAILED, held(5_000))
        assertEquals(emptyList<String>(), asked)
        read()
        status = 503
        assertEquals(SyncStateWait.FAILED, held(5_000))
        // The kept answer stands for the next plain read.
        status = 200
        assertEquals(41L, read()?.get("latest_server_sequence")?.asLong)
    }

    @Test
    fun cancellingTheCallerEndsTheHeldRequestAtOnce() = runBlocking {
        read()
        val waiting = async(Dispatchers.IO) { held(25_000) }
        delay(400)
        val cancelledAt = System.nanoTime()
        waiting.cancel()
        withTimeout(2_000) { waiting.join() }
        val ended = (System.nanoTime() - cancelledAt) / 1_000_000
        assertTrue("ended $ended ms after the cancellation", ended < 1_000)
        assertTrue(waiting.isCancelled)
    }

    @Test
    fun aConfirmationIsTakenOnceAndOnlyWhileItIsFresh() {
        val answer = ConditionalAnswer<String>()
        answer.keep("k", "\"abc\"", "value")
        assertNull(answer.takeConfirmed("k", 1_000, 3_000))
        answer.confirm("k", 1_000)
        assertNull(answer.takeConfirmed("other", 1_000, 3_000))
        assertEquals("value", answer.takeConfirmed("k", 2_500, 3_000))
        assertNull(answer.takeConfirmed("k", 2_500, 3_000))
        answer.confirm("k", 1_000)
        assertNull(answer.takeConfirmed("k", 4_001, 3_000))
        // A new answer is not confirmed until a held request says so.
        answer.confirm("k", 5_000)
        answer.keep("k", "\"def\"", "newer")
        assertNull(answer.takeConfirmed("k", 5_000, 3_000))
        assertEquals("def" to "newer", answer.kept("k"))
    }
}
