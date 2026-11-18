// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.agentnotes

import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerConfig
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Push for agent notes: when it is active, the registration job against a fake server, and the ping. */
class AgentNotesPushTest {
    @get:Rule val folder = TemporaryFolder()

    private class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private class Reply(val status: Int, val body: String)

    /** A one-request-per-connection HTTP server on localhost, scripted per path. */
    private class FakeServer(private val reply: (Recorded) -> Reply) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val requests = CopyOnWriteArrayList<Recorded>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { client.use(::serve) }
                }
            }
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream()
            val requestLine = readLine(input)
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val parts = requestLine.split(' ')
            val recorded = Recorded(parts[0], parts[1], headers, String(body, StandardCharsets.UTF_8))
            requests += recorded
            val response = reply(recorded)
            val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
            client.getOutputStream().apply {
                write(("HTTP/1.1 ${response.status} Status\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                write(bytes)
                flush()
            }
        }

        private fun readLine(input: InputStream): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
            }
            return line.toString(StandardCharsets.ISO_8859_1.name())
        }

        override fun close() = socket.close()
    }

    private class MemoryStore(var record: PushRecord = PushRecord()) : PushRecordStore {
        override fun get() = record
        override fun set(record: PushRecord) {
            this.record = record
        }
    }

    private val servers = mutableListOf<FakeServer>()

    @After fun closeServers() = servers.forEach(FakeServer::close)

    private var capabilities = listOf(PUSH_FCM_CAPABILITY, "agent_notes_v1")
    private var registerStatus = 200

    private fun server(): FakeServer = FakeServer { request ->
        when {
            request.path == "/v1/health" ->
                Reply(200, """{"status":"ok","capabilities":[${capabilities.joinToString(",") { "\"$it\"" }}]}""")
            request.method == "PUT" && request.path == "/v1/push/registration" ->
                if (registerStatus == 200) Reply(200, """{"registered":true}""")
                else Reply(registerStatus, """{"error_code":"refused","detail":"no"}""")
            request.method == "DELETE" && request.path == "/v1/push/registration" -> Reply(200, """{"removed":1}""")
            request.method == "POST" && request.path == "/v1/push/test" -> Reply(200, """{"sent":1,"results":["ok"]}""")
            else -> Reply(404, """{"error_code":"not_found"}""")
        }
    }.also { servers += it }

    private fun client(server: FakeServer) = AgentNotesClient({ ThoughtsServerConfig(server.baseUrl, "device-token-1") })

    private val app = "org.tbend.thoughts.sandbox"

    private fun json(body: String) = JsonParser.parseString(body).asJsonObject

    // --- When push is active ----------------------------------------------------------------

    private fun registered(server: String = "https://server.example", token: String = "token-1") = PushRecord(
        server = server, serverHasPush = true, playServices = true, currentTokenSha256 = pushTokenSha256(token),
        registeredToken = token, registeredApp = app, registeredAtMillis = 1,
    )

    @Test fun pushIsActiveOnlyWithConfigReceivingServerSupportAndTheCurrentTokenRegistered() {
        val server = "https://server.example"
        val record = registered(server)
        assertEquals(PushStatus(true, null, true), pushStatus(true, true, server, app, record))

        assertEquals(PushOffReason.NOT_BUILT, pushStatus(false, true, server, app, record).offReason)
        assertEquals(PushOffReason.NO_PLAY_SERVICES, pushStatus(true, true, server, app, record.copy(playServices = false)).offReason)
        assertEquals(PushOffReason.NO_SERVER, pushStatus(true, true, null, app, record).offReason)
        // Off on this phone: the server can still be asked for a test push.
        assertEquals(PushStatus(false, PushOffReason.NOT_RECEIVING, true), pushStatus(true, false, server, app, record))
        // Another server: nothing is known about it yet.
        assertEquals(PushOffReason.CHECKING, pushStatus(true, true, "https://other.example", app, record).offReason)
        assertEquals(PushOffReason.CHECKING, pushStatus(true, true, server, app, PushRecord(server = server)).offReason)
        assertEquals(PushOffReason.SERVER_WITHOUT_PUSH,
            pushStatus(true, true, server, app, record.copy(serverHasPush = false, registeredToken = null)).offReason)
        // Firebase announced a new token: off until the server has it.
        assertEquals(PushOffReason.REGISTERING,
            pushStatus(true, true, server, app, record.copy(currentTokenSha256 = pushTokenSha256("token-2"))).offReason)
        // Registered for the other build (debug instead of sandbox).
        assertFalse(pushStatus(true, true, server, "org.tbend.thoughts", record).active)
        // A refusal leaves no registration; a failure to reach the server keeps the earlier one.
        assertEquals(PushOffReason.REFUSED,
            pushStatus(true, true, server, app, record.copy(registeredToken = null, failure = PushFailure.REFUSED)).offReason)
        assertTrue(pushStatus(true, true, server, app, record.copy(failure = PushFailure.UNREACHABLE)).active)
        assertEquals(PushOffReason.UNREACHABLE,
            pushStatus(true, true, server, app, record.copy(registeredToken = null, failure = PushFailure.UNREACHABLE)).offReason)
        assertEquals(PushOffReason.NO_TOKEN,
            pushStatus(true, true, server, app, record.copy(registeredToken = null, failure = PushFailure.NO_TOKEN)).offReason)
    }

    // --- The registration job ------------------------------------------------------------------

    private fun PushRegistrar.runWith(
        server: FakeServer?,
        token: String = "token-1",
        receiving: Boolean = true,
        hasConfig: Boolean = true,
        playServices: Boolean = true,
    ) = run(hasConfig, receiving, server?.baseUrl, app, playServices = { playServices }, token = { token })

    @Test fun registersTheTokenWithTheDeviceTokenAndIsIdempotent() {
        val server = server()
        val store = MemoryStore()
        val registrar = PushRegistrar(client(server), store, nowMillis = { 42L })

        assertEquals(PushRegistrar.Outcome.REGISTERED, registrar.runWith(server))
        assertEquals(listOf("GET /v1/health", "PUT /v1/push/registration"), server.requests.map { "${it.method} ${it.path}" })
        val put = server.requests[1]
        assertEquals("Bearer device-token-1", put.headers["authorization"])
        assertTrue(put.headers["content-type"].orEmpty().startsWith("application/json"))
        assertEquals(json("""{"provider":"fcm","token":"token-1","app":"org.tbend.thoughts.sandbox"}"""), json(put.body))
        assertEquals(PushRecord(
            server = server.baseUrl, serverHasPush = true, playServices = true, currentTokenSha256 = pushTokenSha256("token-1"),
            registeredToken = "token-1", registeredApp = app, registeredAtMillis = 42L,
        ), store.record)
        assertTrue(pushStatus(true, true, server.baseUrl, app, store.record).active)

        // App start again: the same PUT, nothing removed.
        assertEquals(PushRegistrar.Outcome.REGISTERED, registrar.runWith(server))
        assertEquals(listOf("GET", "PUT", "GET", "PUT"), server.requests.map { it.method })
        assertEquals(json(put.body), json(server.requests[3].body))
        assertTrue(pushStatus(true, true, server.baseUrl, app, store.record).active)
    }

    @Test fun receivingTurnedOffDeletesTheRegistration() {
        val server = server()
        val store = MemoryStore(registered(server.baseUrl))
        val registrar = PushRegistrar(client(server), store)

        assertEquals(PushRegistrar.Outcome.REMOVED, registrar.runWith(server, receiving = false))
        val delete = server.requests.single()
        assertEquals("DELETE /v1/push/registration", "${delete.method} ${delete.path}")
        assertEquals("Bearer device-token-1", delete.headers["authorization"])
        assertEquals(json("""{"provider":"fcm","token":"token-1"}"""), json(delete.body))
        assertNull(store.record.registeredToken)
        assertEquals(PushOffReason.NOT_RECEIVING, pushStatus(true, false, server.baseUrl, app, store.record).offReason)

        // Nothing registered any more: nothing to ask.
        assertEquals(PushRegistrar.Outcome.OFF, registrar.runWith(server, receiving = false))
        assertEquals(1, server.requests.size)
    }

    @Test fun aBuildWithoutConfigRemovesWhatAnEarlierBuildRegisteredAndOtherwiseAsksNothing() {
        val server = server()
        val store = MemoryStore(registered(server.baseUrl))
        assertEquals(PushRegistrar.Outcome.REMOVED, PushRegistrar(client(server), store).runWith(server, hasConfig = false))
        assertEquals(listOf("DELETE"), server.requests.map { it.method })
        assertEquals(PushRegistrar.Outcome.OFF, PushRegistrar(client(server), store).runWith(server, hasConfig = false))
        assertEquals(1, server.requests.size)
    }

    @Test fun withoutGooglePlayServicesNothingIsRegistered() {
        val server = server()
        val store = MemoryStore()
        assertEquals(PushRegistrar.Outcome.OFF, PushRegistrar(client(server), store).runWith(server, playServices = false))
        assertTrue(server.requests.isEmpty())
        assertEquals(PushOffReason.NO_PLAY_SERVICES, pushStatus(true, true, server.baseUrl, app, store.record).offReason)
    }

    @Test fun aServerThatLostTheCapabilityGetsTheRegistrationRemovedAndPushIsOff() {
        val server = server()
        capabilities = listOf("agent_notes_v1")
        val store = MemoryStore(registered(server.baseUrl))
        assertEquals(PushRegistrar.Outcome.OFF, PushRegistrar(client(server), store).runWith(server))
        assertEquals(listOf("GET /v1/health", "DELETE /v1/push/registration"), server.requests.map { "${it.method} ${it.path}" })
        assertEquals(PushOffReason.SERVER_WITHOUT_PUSH, pushStatus(true, true, server.baseUrl, app, store.record).offReason)
        assertNull(store.record.registeredToken)
    }

    @Test fun aRefreshedTokenIsRegisteredAndTheReplacedOneRemoved() {
        val server = server()
        val store = MemoryStore(registered(server.baseUrl, token = "token-1"))
        assertEquals(PushRegistrar.Outcome.REGISTERED, PushRegistrar(client(server), store).runWith(server, token = "token-2"))
        assertEquals(listOf("GET", "PUT", "DELETE"), server.requests.map { it.method })
        assertEquals("token-2", json(server.requests[1].body)["token"].asString)
        assertEquals(json("""{"provider":"fcm","token":"token-1"}"""), json(server.requests[2].body))
        assertEquals("token-2", store.record.registeredToken)
        assertTrue(pushStatus(true, true, server.baseUrl, app, store.record).active)
    }

    @Test fun failuresRetryOrRefuseAsTheServerSays() {
        val server = server()
        // 503: tried again; the earlier registration of the same token stands.
        registerStatus = 503
        val store = MemoryStore(registered(server.baseUrl))
        assertEquals(PushRegistrar.Outcome.RETRY, PushRegistrar(client(server), store).runWith(server))
        assertEquals(PushFailure.UNREACHABLE, store.record.failure)
        assertTrue(pushStatus(true, true, server.baseUrl, app, store.record).active)

        // 400: refused, not tried again, push off.
        registerStatus = 400
        assertEquals(PushRegistrar.Outcome.REFUSED, PushRegistrar(client(server), store).runWith(server))
        assertEquals(PushOffReason.REFUSED, pushStatus(true, true, server.baseUrl, app, store.record).offReason)

        // No token from Google yet: tried again, nothing sent to the server.
        val fresh = MemoryStore()
        val before = server.requests.size
        assertEquals(
            PushRegistrar.Outcome.RETRY,
            PushRegistrar(client(server), fresh).run(true, true, server.baseUrl, app, { true }, { throw IllegalStateException("none") }),
        )
        assertEquals(listOf("GET"), server.requests.drop(before).map { it.method })
        assertEquals(PushOffReason.NO_TOKEN, pushStatus(true, true, server.baseUrl, app, fresh.record).offReason)

        // The server cannot be reached at all.
        val gone = server()
        val baseUrl = gone.baseUrl
        gone.close()
        val offline = MemoryStore()
        assertEquals(PushRegistrar.Outcome.RETRY, PushRegistrar(client(gone), offline).run(true, true, baseUrl, app, { true }, { "token-1" }))
        assertEquals(PushFailure.UNREACHABLE, offline.record.failure)
    }

    @Test fun anotherServerStartsAfreshAndNoServerForgetsTheRegistration() {
        val first = server()
        val second = server()
        val store = MemoryStore(registered(first.baseUrl))
        assertEquals(PushRegistrar.Outcome.REGISTERED, PushRegistrar(client(second), store).runWith(second))
        // The old server is not told anything: its device token is not sent elsewhere.
        assertTrue(first.requests.isEmpty())
        assertEquals(second.baseUrl, store.record.server)
        assertEquals(PushRegistrar.Outcome.OFF, PushRegistrar(client(second), store).runWith(null))
        assertNull(store.record.server)
        assertNull(store.record.registeredToken)
    }

    @Test fun testPushAndRemovalOfEveryRegistrationOfThisDevice() {
        val server = server()
        val client = client(server)
        assertEquals(PushTestResult(1, listOf("ok")), client.sendTestPush())
        assertEquals(1, client.unregisterPush(null))
        assertEquals("POST /v1/push/test", server.requests[0].let { "${it.method} ${it.path}" })
        // No body: the server removes every registration of this device.
        assertEquals("", server.requests[1].body)
    }

    // --- The ping -----------------------------------------------------------------------------

    @Test fun pingKindsDispatchFetchTestOrNothing() {
        assertEquals(PushPingAction.FETCH_NOTES, pushPingAction(mapOf("kind" to "agent_notes", "at" to "2026-11-14T18:00:00Z"), receiving = true))
        assertEquals(PushPingAction.IGNORE, pushPingAction(mapOf("kind" to "agent_notes"), receiving = false))
        assertEquals(PushPingAction.SHOW_TEST, pushPingAction(mapOf("kind" to "test"), receiving = true))
        assertEquals(PushPingAction.SHOW_TEST, pushPingAction(mapOf("kind" to "test"), receiving = false))
        assertEquals(PushPingAction.IGNORE, pushPingAction(mapOf("kind" to "something_new"), receiving = true))
        assertEquals(PushPingAction.IGNORE, pushPingAction(emptyMap(), receiving = true))
    }

    private fun note(id: String, sequence: Long) = AgentNote(noteId = id, sequence = sequence, title = "Note $id")

    @Test fun aPingAsksTheInboxOnceAndTheLiveLoopDoesNotShowTheNoteAgain() {
        val api = AgentNoteSyncTest.FakeApi()
        val store = AgentNoteStore.under(folder.root)
        val sync = AgentNoteSync(api, store)
        api.pages += { AgentNoteInboxPage(listOf(note("a", 3)), 3) }
        // The server answers the live loop's next request with the same note (it was not acknowledged yet).
        api.pages += { AgentNoteInboxPage(listOf(note("a", 3)), 3) }

        val fromPing = sync.pushCheck(liveLoopRunning = false)
        assertEquals(listOf("a"), fromPing.map { it.noteId })
        assertEquals(0, api.inboxCalls.single().second)
        assertEquals(listOf("a" to "delivered"), api.receipts)
        // The notes the ping fetched go where the live loop's go: announced while Thoughts is not open.
        assertEquals(listOf("a"), agentNoteArrival(fromPing, appOpen = false).notify.map { it.noteId })

        assertTrue(sync.pollInbox(wait = 25).isEmpty())
        assertEquals(1, api.receipts.size)
    }

    @Test fun whileTheLiveLoopHoldsItsRequestThePingStepsAsideOtherwiseItAsksAfterTheRequestInFlight() {
        val api = AgentNoteSyncTest.FakeApi()
        val sync = AgentNoteSync(api, AgentNoteStore.under(folder.root))
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        api.pages += {
            holding.countDown()
            release.await(5, TimeUnit.SECONDS)
            AgentNoteInboxPage(emptyList(), null)
        }
        val loop = thread { sync.pollInbox(wait = 25) }
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        // The live loop's long poll gets the note: the ping asks nothing.
        assertTrue(sync.pushCheck(liveLoopRunning = true).isEmpty())
        assertEquals(1, api.inboxCalls.size)

        // A periodic request in flight (no live loop): the ping waits for it, then asks.
        api.pages += { AgentNoteInboxPage(listOf(note("b", 4)), 4) }
        var fetched: List<AgentNote> = emptyList()
        val ping = thread { fetched = sync.pushCheck(liveLoopRunning = false) }
        release.countDown()
        loop.join(5_000)
        ping.join(5_000)
        assertEquals(listOf("b"), fetched.map { it.noteId })
        assertEquals(2, api.inboxCalls.size)
    }

    // --- The listener --------------------------------------------------------------------------

    @Test fun theListenerIsNotStartedAfterASendWhilePushDeliversNotes() {
        // After a reply, a page sent or "Copy for agent" (AgentStudyListenerService.start).
        assertFalse(agentNotesListenerShouldStart(receiving = true, pushActive = true))
        assertTrue(agentNotesListenerShouldStart(receiving = true, pushActive = false))
        assertFalse(agentNotesListenerShouldStart(receiving = false, pushActive = false))
    }
}
