// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.review

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The agent recall prediction: what the phone sends, what it accepts back, and how. */
class ReviewRecallPredictionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val now = Instant.parse("2026-11-03T12:00:00Z")
    private val scale = listOf("Forgot", "Partly recalled", "Recalled")

    private fun answer(
        id: String,
        at: Instant,
        tags: List<String>,
        notes: Map<String, String> = emptyMap(),
        phases: ReviewPhases? = null,
        queueAction: String? = null,
        retracted: Boolean = false,
    ) = ReviewHistoryEntry(id, if (queueAction == null) "custom_interval" else null, at, "phone", 1, at, null, retracted,
        true, false, null, queueAction = queueAction, reviewTags = tags, reviewTagsRecorded = true, groupNotes = notes, phases = phases)

    @Test fun theRequestCarriesTheCardItsAnswersInEffectAndTheDocumentsRecentAnswers() {
        val history = listOf(
            answer("b", now.minus(Duration.ofDays(2)), listOf("Recalled", "Meh"), mapOf("note" to "easy"),
                ReviewPhases(listOf("Meh", "Partly recalled"), listOf("Recalled"), "2026-11-01T12:00:05.000Z")),
            answer("a", now.minus(Duration.ofDays(9)), listOf("Old name")),
            // Not answers in effect: a skip and an undone answer.
            answer("s", now.minus(Duration.ofDays(1)), emptyList(), queueAction = "skip"),
            answer("x", now.minus(Duration.ofHours(1)), listOf("Forgot"), retracted = true),
        )
        val others = listOf(
            ReviewRecallPrediction.OtherCardAnswers("What is a monad?", listOf(
                answer("o1", now.minus(Duration.ofDays(1)), listOf("Recalled")),
                answer("o2", now.minus(Duration.ofDays(15)), listOf("Forgot")),
            )),
            ReviewRecallPrediction.OtherCardAnswers("What is a functor?", listOf(answer("o3", now.minus(Duration.ofHours(3)), listOf("Partly recalled")))),
        )
        val body = ReviewRecallPrediction.requestBody("card-1", "What is hidden?", "The answer.", "Category theory", scale, "r",
            history, others, now, mapOf("Old name" to "Forgot"))
        assertEquals(JsonParser.parseString("""
            {"card_id":"card-1","question":"What is hidden?","answer":"The answer.","document":"Category theory",
             "scale":["Forgot","Partly recalled","Recalled"],"recall_group_id":"r",
             "history":[{"reviewed_at":"2026-10-25T12:00:00.000Z","tags":["Forgot"],"notes":{}},
                        {"reviewed_at":"2026-11-01T12:00:00.000Z","tags":["Recalled","Meh"],"notes":{"note":"easy"},
                         "before_tags":["Meh","Partly recalled"]}],
             "related":[{"question":"What is a functor?","reviewed_at":"2026-11-03T09:00:00.000Z","tags":["Partly recalled"]},
                        {"question":"What is a monad?","reviewed_at":"2026-11-02T12:00:00.000Z","tags":["Recalled"]}],
             "now":"2026-11-03T12:00:00.000Z"}
        """), body)
    }

    @Test fun theAnswerHistoryAndRelatedAnswersAreCapped() {
        val history = (1..35).map { answer("h$it", now.minus(Duration.ofHours(40L - it)), listOf("Recalled")) }
        val others = listOf(ReviewRecallPrediction.OtherCardAnswers("Other?",
            (1..25).map { answer("o$it", now.minus(Duration.ofHours(it.toLong())), listOf("Forgot")) }))
        val body = ReviewRecallPrediction.requestBody("card-1", "Q", "x".repeat(5000), "Doc", scale, "r", history, others, now)
        assertEquals(3000, body.get("answer").asString.length)
        val sent = body.getAsJsonArray("history").map { it.asJsonObject.get("reviewed_at").asString }
        assertEquals(30, sent.size)
        // The newest 30, oldest first.
        assertEquals(ReviewTagGroups.timestamp(now.minus(Duration.ofHours(34))), sent.first())
        assertEquals(ReviewTagGroups.timestamp(now.minus(Duration.ofHours(5))), sent.last())
        val related = body.getAsJsonArray("related").map { it.asJsonObject.get("reviewed_at").asString }
        assertEquals(20, related.size)
        assertEquals(ReviewTagGroups.timestamp(now.minus(Duration.ofHours(1))), related.first())
        assertEquals(ReviewTagGroups.timestamp(now.minus(Duration.ofHours(20))), related.last())
    }

    @Test fun onlyAStepOfTheScaleWithAnIdIsAPrediction() {
        val requestedAt = Instant.parse("2026-11-03T12:00:00.250Z")
        fun response(json: String) = JsonParser.parseString(json).asJsonObject
        val prediction = ReviewRecallPrediction.parseResponse(response("""
            {"prediction_id":"p-1","tag":"Partly recalled","reason":"Last time with gaps.","model":"m","made_at":"2026-11-03T12:00:03Z","extra":1}
        """), scale, "r", requestedAt)
        assertEquals(ReviewAgentPrediction("Partly recalled", "r", "Last time with gaps.", "m", "p-1",
            "2026-11-03T12:00:00.250Z", "2026-11-03T12:00:03Z"), prediction)
        assertNull(ReviewRecallPrediction.parseResponse(response("""{"prediction_id":"p-1","tag":"Meh"}"""), scale, "r", requestedAt))
        assertNull(ReviewRecallPrediction.parseResponse(response("""{"prediction_id":"p-1","tag":"recalled"}"""), scale, "r", requestedAt))
        assertNull(ReviewRecallPrediction.parseResponse(response("""{"tag":"Recalled"}"""), scale, "r", requestedAt))
        assertNull(ReviewRecallPrediction.parseResponse(response("""{"prediction_id":"p-1","tag":3}"""), scale, "r", requestedAt))
    }

    @Test fun withoutBothUrlAndTokenNothingIsAsked() {
        assertNull(ReviewRecallPrediction.Config.of(null, "t"))
        assertNull(ReviewRecallPrediction.Config.of("http://100.64.0.10:8798/recall-prediction", " "))
        assertNull(ReviewRecallPrediction.Config.of("", ""))
        assertNull(ReviewRecallPrediction.Config.of("ftp://host/x", "t"))
        val config = checkNotNull(ReviewRecallPrediction.Config.of(" http://100.64.0.10:8798/recall-prediction ", " secret "))
        assertEquals("http://100.64.0.10:8798/recall-prediction", config.url)
        assertEquals("secret", config.token)
        // Never printed.
        assertFalse(config.toString().contains("secret"))
    }

    private class FakeConnection(url: URL, private val status: Int, private val response: String) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    @Test fun theRequestIsAPostWithTheTokenAndAnythingButA200Fails() {
        val config = checkNotNull(ReviewRecallPrediction.Config.of("http://100.64.0.10:8798/recall-prediction", "secret"))
        val body = JsonObject().apply { addProperty("card_id", "card-1") }
        var connection: FakeConnection? = null
        val logged = mutableListOf<String>()
        val answer = ReviewRecallPrediction.request(config, body, { url ->
            FakeConnection(url, 200, """{"prediction_id":"p","tag":"Recalled"}""").also { connection = it }
        }, logged::add, vpnActive = { true })
        assertEquals("Recalled", answer.get("tag").asString)
        val sent = checkNotNull(connection)
        assertEquals("POST", sent.requestMethod)
        assertEquals("secret", sent.getRequestProperty("X-Recall-Token"))
        // A redirect is not followed: the token would go along.
        assertFalse(sent.instanceFollowRedirects)
        assertEquals(60_000, sent.readTimeout)
        assertEquals(body, JsonParser.parseString(sent.sent.toString(Charsets.UTF_8.name())))
        assertTrue(logged.single().startsWith("recall prediction status=200"))
        assertTrue(logged.none { "secret" in it })
        for ((status, text) in listOf(401 to "{}", 500 to "{}", 200 to "[]", 200 to "not json")) {
            try {
                ReviewRecallPrediction.request(config, body, { url -> FakeConnection(url, status, text) }, vpnActive = { true })
                fail("expected a failure for $status $text")
            } catch (expected: Exception) {
                assertTrue(expected.toString(), expected is IOException || expected is com.google.gson.JsonParseException)
            }
        }
    }

    @Test fun plainHttpIsOnlyUsedOnThisDeviceOrThroughTheVpn() {
        val vpnUp = { true }
        val vpnDown = { false }
        // https is always allowed, and the VPN is not even asked.
        assertTrue(ReviewRecallPrediction.transportAllowed("https://thoughts.example.org/recall-prediction") { error("not asked") })
        assertTrue(ReviewRecallPrediction.transportAllowed("https://server.example.ts.net:8798/recall-prediction", vpnDown))
        // Plain http to this device itself.
        for (local in listOf("http://localhost:8798/x", "http://127.0.0.1:8798/x", "http://127.8.9.10/x", "http://[::1]:8798/x")) {
            assertTrue(local, ReviewRecallPrediction.transportAllowed(local, vpnDown))
        }
        // Plain http to an address that is reached through the VPN: only while the VPN is up.
        for (tailnet in listOf("http://server.example.ts.net:8798/x", "http://100.64.0.7:8798/x", "http://[fd7a:115c:a1e0::7]:8798/x")) {
            assertTrue(tailnet, ReviewRecallPrediction.transportAllowed(tailnet, vpnUp))
            assertFalse(tailnet, ReviewRecallPrediction.transportAllowed(tailnet, vpnDown))
        }
        // Plain http anywhere else is refused, VPN or not.
        for (open in listOf(
            "http://thoughts.example.org/x", "http://192.168.1.20:8798/x", "http://10.0.0.5/x", "http://100.63.255.255/x",
            "http://100.128.0.1/x", "http://127.0.0.1.example.org/x", "http://localhost.example.org/x",
            "http://localhost@thoughts.example.org/x", "http://ts.net.example.org/x", "http://[fd7a:115c:a1e1::7]/x",
            "http:///x", "ftp://localhost/x", "not a url",
        )) {
            assertFalse(open, ReviewRecallPrediction.transportAllowed(open, vpnUp))
        }
    }

    @Test fun aRefusedAddressGetsNeitherTheTokenNorTheCard() {
        val body = JsonObject().apply { addProperty("card_id", "card-1") }
        fun refused(url: String, vpnActive: Boolean) {
            val config = checkNotNull(ReviewRecallPrediction.Config.of(url, "secret"))
            try {
                ReviewRecallPrediction.request(config, body, { error("no connection may be opened") }, vpnActive = { vpnActive })
                fail("expected $url to be refused")
            } catch (expected: IOException) {
                assertFalse(expected.message.orEmpty().contains("secret"))
            }
        }
        refused("http://thoughts.example.org:8798/recall-prediction", vpnActive = true)
        refused("http://server.example.ts.net:8798/recall-prediction", vpnActive = false)
        // The same service over https, or through the VPN, is asked.
        for ((url, vpnActive) in listOf(
            "https://thoughts.example.org/recall-prediction" to false,
            "http://server.example.ts.net:8798/recall-prediction" to true,
            "http://localhost:8798/recall-prediction" to false,
        )) {
            val config = checkNotNull(ReviewRecallPrediction.Config.of(url, "secret"))
            var connection: FakeConnection? = null
            ReviewRecallPrediction.request(config, body, { opened ->
                FakeConnection(opened, 200, """{"prediction_id":"p","tag":"Recalled"}""").also { connection = it }
            }, vpnActive = { vpnActive })
            assertEquals("secret", checkNotNull(connection).getRequestProperty("X-Recall-Token"))
        }
    }

    @Test fun theStoreGivesTheCardsHistoryAndItsDocumentsOtherCards() {
        val root = temporary.newFolder("predict-root")
        val document = "65fe82b8-0fbf-4a37-8844-fbece45c7e22"
        val card = writeCard(root, "paper", document, "What is hidden?")
        val sibling = writeCard(root, "paper", document, "What is next to it?")
        val deleted = writeCard(root, "paper", document, "What was deleted?")
        val elsewhere = writeCard(root, "other", "0b5c0a43-6a3e-4f43-9a55-3f7d4d1b2c11", "Elsewhere?")
        val store = ReviewStore(null, root, temporary.newFolder("predict-in"), temporary.newFolder("predict-events"),
            temporary.newFolder("predict-out"))
        val at = Instant.now().minusSeconds(60)
        for (id in listOf(card, sibling, deleted, elsewhere)) {
            store.record(id, ReviewRating.CUSTOM_INTERVAL, at, at.plus(Duration.ofDays(1)), reviewTags = listOf("Recalled")).getOrThrow()
        }
        store.deleteCard(deleted).getOrThrow()
        val (history, others) = store.recallPredictionHistory(card)
        assertEquals(listOf(listOf("Recalled")), history.filter { it.isAnswerInEffect() }.map { it.reviewTags })
        assertEquals(listOf("What is next to it?"), others.map { it.question })
        assertEquals(listOf("Recalled"), others.single().history.single().reviewTags)
    }

    private fun writeCard(root: File, pdfStem: String, documentId: String, question: String): String {
        val cardId = UUID.randomUUID().toString()
        val cards = File(root, "cards").apply { mkdirs() }
        File(cards, "$cardId.json").writeText(
            """{"schema_version":3,"card_id":"$cardId","status":"approved","revision":1,"primary":{"pdf_stem":"$pdfStem","document_id":"$documentId","pdf_sha256":"${"a".repeat(64)}","page":1,"rect":[0.1,0.2,0.8,0.4],"page_visual_sha256":"${"b".repeat(64)}","question":"$question"}}""",
        )
        return cardId
    }
}
