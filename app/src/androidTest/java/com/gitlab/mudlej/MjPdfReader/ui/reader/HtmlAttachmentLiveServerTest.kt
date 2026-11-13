// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCardStatus
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionStore
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel
import com.gitlab.mudlej.MjPdfReader.data.html.attachmentCardSupport
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.source.OfflineSourceBundleStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ExplicitSyncOrigin
import com.gitlab.mudlej.MjPdfReader.data.sync.FedoraRepository
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationRecord
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.data.sync.isTerminalSyncOperationStatus
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tbend.thoughts.sdk.HTML_ATTACHMENT_UNKNOWN
import org.tbend.thoughts.sdk.HtmlAttachmentCardPrimary
import org.tbend.thoughts.sdk.HtmlAttachmentRef
import org.tbend.thoughts.sdk.HtmlPageState
import org.tbend.thoughts.sdk.HtmlTextQuote
import org.tbend.thoughts.sdk.LibraryEntry
import org.tbend.thoughts.sdk.OperationInput
import org.tbend.thoughts.sdk.ServerException
import org.tbend.thoughts.sdk.SyncClient

/**
 * Review cards inside an HTML attachment page end to end against a live local server
 * (thoughts-server tools/html_attachment_live_server.py on the emulator's host; its control API on
 * the next port). Two "devices" are two runs of the Thoughts Test app with fresh data and their own
 * tokens (run-isolated-tests.py clears the app each run):
 *
 * - [deviceA] (server.json): Sync, open the PDF, tap its thoughts-html: link, select text on
 *   #simulator, New review card, Sync; the server stores the exact primary, never links it. Then
 *   a card naming an attachment version the PDF never had: the SDK gets 409
 *   html_attachment_unknown, and the phone's Sync with such a card is checked.
 * - [deviceB] (server-b.json, after deviceA): Sync receives the card; the review queue opens the
 *   page at #simulator with the answer masked; Reveal. The attachment changes (answer text
 *   removed): the card stays, its review says "Answer not found in the current page". The owner
 *   PDF is rebuilt: the card stays due and listed.
 *
 * Steps log "AttachmentLiveE2E step"; screens go to /data/local/tmp/attachment-live-*.png.
 */
@RunWith(AndroidJUnit4::class)
class HtmlAttachmentLiveServerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val question = "What does the simulator replay?"

    private fun step(name: String) = android.util.Log.i(TAG, "step $name")
    private fun note(message: String) = android.util.Log.i(TAG, message)

    /** The one-time "Let sync finish in the background" offer would cover the reader. */
    @Before fun batteryOfferSeen() {
        File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1").apply { parentFile?.mkdirs(); writeText("shown\n") }
    }

    // ---- Server ----------------------------------------------------------------------------

    private val config by lazy {
        val config = checkNotNull(ThoughtsServerSecretStore(context).config()) { "no test server provisioned" }
        assertEquals(BuildConfig.TEST_SERVER_URL, config.baseUrl)
        assertTrue("a local test server", config.baseUrl.startsWith("http://10.0.2.2:"))
        config
    }
    private val client by lazy { SyncClient(config.baseUrl, config.token) }
    private val controlUrl by lazy {
        val port = config.baseUrl.substringAfterLast(':').toInt()
        "http://10.0.2.2:${port + 1}"
    }

    /** The launcher's control API (see html_attachment_live_server.py). */
    private fun control(method: String, path: String): JsonObject {
        val connection = URL(controlUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 300_000
        if (method == "POST") { connection.doOutput = true; connection.outputStream.use { it.write(ByteArray(0)) } }
        val status = connection.responseCode
        val body = (if (status < 400) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        assertEquals("control $method $path: $body", 200, status)
        return JsonParser.parseString(body).asJsonObject
    }

    private val serverState by lazy { control("GET", "/state") }
    private val attachmentPath get() = serverState["attachment_path"].asString
    private val answer get() = serverState["answer"].asString

    private fun ownerEntry(): LibraryEntry =
        checkNotNull(client.library())!!.publications.single { it.filename == serverState["output"].asString }

    private fun inspect(cardId: String): JsonObject = control("GET", "/inspect?card=$cardId")

    /**
     * One Sync as the Sync button starts it (ThoughtsSyncScheduler.enqueue through WorkManager),
     * waited for until it is final; its record.
     */
    private fun sync(label: String, expectSynced: Boolean = true, timeoutMs: Long = 180_000): SyncOperationRecord {
        val operations = SyncOperationStore(context)
        val operationId = operations.begin(null, false)
        ThoughtsSyncScheduler.enqueue(context, preparedOperationId = operationId, origin = ExplicitSyncOrigin.BACKGROUND_COMMAND)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val record = operations.operation(operationId)
            val status = record?.status.orEmpty()
            if (status != last) note("sync $label: $status")
            last = status
            if (record != null && isTerminalSyncOperationStatus(status)) {
                note("sync $label ended $status message='${record.displayMessage}' events=${record.events.takeLast(6)}")
                if (expectSynced) assertEquals("Sync $label ended with $record", SyncOperationStore.STATUS_SYNCED, status)
                return record
            }
            SystemClock.sleep(1_000)
        }
        fail("Sync $label not finished after ${timeoutMs / 1000} s (last status $last)")
        error("unreachable")
    }

    private fun awaitTrue(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(1_000)
        }
        fail(message)
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun localCard(id: String): ReviewCard? = ReviewStore(context).card(id)

    /** The card as the server stores it: the exact primary of the spec, never linked or placed. */
    private fun assertServerCard(cardId: String, entry: LibraryEntry, attachmentSha256: String, label: String): JsonObject {
        val inspected = inspect(cardId)
        note("inspect $label: $inspected")
        val payload = inspected.getAsJsonObject("payload")
        assertNotNull("server has card $cardId ($label)", payload)
        assertFalse("not deleted ($label)", inspected.getAsJsonObject("entity")["deleted"].asBoolean)
        val primary = payload.getAsJsonObject("primary")
        assertEquals("html_attachment", primary["created_in"].asString)
        assertEquals(JsonObject().apply { addProperty("path", attachmentPath); addProperty("sha256", attachmentSha256) },
            primary.getAsJsonObject("html_attachment"))
        val quote = primary.getAsJsonObject("text_quote")
        assertEquals(answer, quote["exact"].asString)
        assertTrue("prefix ≤ 64", quote["prefix"].asString.length <= 64)
        assertTrue("suffix ≤ 64", quote["suffix"].asString.length <= 64)
        assertEquals("#simulator", primary.getAsJsonObject("page_state")["hash"].asString)
        assertEquals(entry.filename.removeSuffix(".pdf"), primary["pdf_stem"].asString)
        assertEquals(entry.documentId, primary["document_id"].asString)
        assertTrue("pdf_sha256 is a hash", primary["pdf_sha256"].asString.matches(Regex("[0-9a-f]{64}")))
        assertEquals(answer, primary["selected_text"].asString)
        assertEquals(question, primary["question"].asString)
        for (key in HtmlAttachmentCardPrimary.GEOMETRY + listOf("scope", "page_visual_sha256")) {
            assertFalse("primary has no $key ($label)", primary.has(key))
        }
        assertEquals("client-v1", payload["source_authority"].asString)
        assertEquals(JsonObject().apply { addProperty("state", "html_attachment") }, payload.getAsJsonObject("source_link"))
        assertFalse("never needs attention ($label)", payload.toString().contains("needs_attention"))
        assertFalse("never reanchored ($label)", payload.has("reanchor"))
        inspected.getAsJsonObject("card_id_in_sources").entrySet().forEach { (path, found) ->
            assertFalse("no markers in $path ($label)", found.asBoolean)
        }
        assertEquals("no reanchor jobs ($label)", 0, inspected.getAsJsonArray("reanchor_jobs").size())
        assertEquals("no anchor jobs ($label)", 0, inspected.getAsJsonArray("anchor_jobs").size())
        return payload
    }

    // ---- Device A --------------------------------------------------------------------------

    @Test fun deviceA() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        step("A1 sync")
        sync("first")
        assertTrue("html_attachment_cards_v1 remembered", HtmlEditionStore.default(context).attachmentCardSupport())
        val entry = ownerEntry()
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), entry.filename)
        assertTrue("PDF downloaded: $pdf", pdf.isFile)
        assertEquals(entry.sha256, sha256(pdf))
        val attachmentSha = serverState["attachment_sha256"].asString
        val local = OfflineSourceBundleStore(context).htmlAttachmentFile(attachmentPath, entry.filename)
        assertNotNull("attachment downloaded with the PDF's sources", local)
        assertEquals(attachmentSha, local!!.sha256)

        lateinit var created: ReviewCard
        openReader(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) { scenario, binding, attachments, viewer, script ->
            awaitUi(scenario, "PDF loaded") { binding.pdfView.pageCount > 0 }
            step("A1 tap the thoughts-html: link")
            val tap = findLink(scenario, binding)
            note("link at screen $tap")
            screenshot("a1-pdf")
            instrumentation.uiAutomation.executeShellCommand("input tap ${tap.first} ${tap.second}").close()
            awaitUi(scenario, "attachment page opened in the app", 20_000) { attachments.isActive && attachments.isReady }
            scenario.onActivity {
                val shown = attachments.shown!!
                assertEquals(attachmentPath, shown.attachment.path)
                assertEquals(attachmentSha, shown.attachment.sha256)
                assertEquals(entry.filename, shown.owner!!.pdfFilename)
                assertEquals(entry.sha256, shown.owner!!.pdfSha256)
                assertEquals(entry.documentId, shown.owner!!.documentId)
                assertTrue("New review card offered", attachments.canMakeCards())
            }
            awaitScript(script, "page rendered by its script", "document.body.getAttribute('data-tab')", "\"experiment\"")
            screenshot("a1-page")

            step("A2 select on #simulator, New review card")
            script("document.querySelector('nav a[href=\"#simulator\"]').click()")
            awaitScript(script, "simulator tab", "document.body.getAttribute('data-tab')", "\"simulator\"")
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (viewer("selectQuote", JsonObject().apply { addProperty("exact", answer) })?.asBoolean != true) {
                if (SystemClock.uptimeMillis() > deadline) fail("answer on #simulator not selectable")
                SystemClock.sleep(200)
            }
            screenshot("a2-selected")
            // The selection menu's New review card (HtmlAttachmentReaderController.startCard).
            val startCard = HtmlAttachmentReaderController::class.java
                .getDeclaredMethod("startCard", HtmlReaderModel.Selection::class.java).apply { isAccessible = true }
            scenario.onActivity { attachments.withSelection { startCard.invoke(attachments, it) } }
            awaitUi(scenario, "card editor shown") { binding.manualReviewEditorCard.visibility == View.VISIBLE }
            scenario.onActivity { binding.manualReviewQuestion.setText(question) }
            screenshot("a2-editor")
            scenario.onActivity { binding.manualReviewAddButton.performClick() }
            awaitTrue("card saved", 15_000) {
                ReviewStore(context).browserCards().any { it.card.question == question }
            }
            created = ReviewStore(context).browserCards().first { it.card.question == question }.card
            assertTrue(created.isHtmlAttachmentCard)
            assertEquals(attachmentPath, created.htmlAttachment!!.path)
            assertEquals(attachmentSha, created.htmlAttachment!!.sha256)
            assertEquals(entry.filename.removeSuffix(".pdf"), created.pdfStem)
            scenario.onActivity { attachments.refreshItems() }
            awaitUi(scenario, "new card highlighted by its quote") { created.id in attachments.lastPlacement.placedCards }
            screenshot("a2-highlighted")
        }

        step("A2 Sync uploads the card; the server keeps it unlinked")
        sync("upload")
        assertServerCard(created.id, entry, attachmentSha, "after upload")
        // Give the review worker time to (not) act on it.
        SystemClock.sleep(15_000)
        sync("after-upload")
        assertServerCard(created.id, entry, attachmentSha, "15 s later")
        val kept = checkNotNull(localCard(created.id))
        assertTrue(kept.isHtmlAttachmentCard)

        step("A5 SDK: an attachment version the PDF never had")
        val bogusSha = MessageDigest.getInstance("SHA-256").digest("not this page".toByteArray()).joinToString("") { "%02x".format(it) }
        val sdkCard = UUID.randomUUID().toString()
        val payload = HtmlAttachmentCardPrimary(entry.documentId, entry.filename.removeSuffix(".pdf"), entry.sha256,
            HtmlAttachmentRef(attachmentPath, bogusSha), HtmlTextQuote(answer), HtmlPageState("#simulator"), question = question)
            .applyTo(JsonObject().apply {
                addProperty("schema_version", 4); addProperty("card_id", sdkCard); addProperty("card_type", "visual_region")
                addProperty("status", "approved"); addProperty("revision", 1); addProperty("created_at", Instant.now().toString())
            })
        val refused = runCatching {
            SyncClient(config.baseUrl, serverState["sdk_token"].asString).uploadOperations(listOf(OperationInput(
                operationId = UUID.randomUUID().toString(), deviceSequence = 1, entityType = "review_card", entityId = sdkCard,
                collection = "cards", clientTimestamp = Instant.now().toString(), payload = payload)))
        }.exceptionOrNull()
        note("SDK unknown attachment: $refused")
        assertTrue("SDK refused: $refused", refused is ServerException)
        assertEquals(409, (refused as ServerException).statusCode)
        assertEquals(HTML_ATTACHMENT_UNKNOWN, refused.errorCode)
        assertTrue(inspect(sdkCard)["payload"].isJsonNull)

        step("A5 phone: a card the server refuses")
        val badFields = created.htmlAttachment!!.toJson().apply {
            getAsJsonObject("html_attachment").addProperty("sha256", bogusSha)
        }
        val bad = ReviewStore(context).createHtmlAttachmentCard(entry.filename.removeSuffix(".pdf"), entry.sha256,
            "A card on a page version the PDF never had?", badFields, answer, documentId = entry.documentId).getOrThrow()
        runBlocking { FedoraRepository(context).captureReviewCards() }
        val first = sync("refused-1", expectSynced = false)
        val second = sync("refused-2", expectSynced = false)
        val rows = runBlocking { AppDatabase.getInstance(context).thoughtsSyncDao().allOutbox() }.filter { it.entityId == bad.id }
        note("refused card outbox: ${rows.map { "${it.deliveryState} ${it.failureCode} ${it.failureDetail}" }}")
        note("refused card local: ${localCard(bad.id)?.let { "kept status=${it.status}" } ?: "GONE"}")
        note("refused syncs: 1=${first.status} '${first.displayMessage}' 2=${second.status} '${second.displayMessage}'")
        note("refused card status: notSent=${HtmlAttachmentCardStatus.of(context).notSent(bad.id)}")
        // Nothing is lost, the person is told why, and the refusal does not fail every later Sync.
        assertNotNull("the refused card stays on the phone", localCard(bad.id))
        assertTrue(inspect(bad.id)["payload"].isJsonNull)
        assertEquals(SyncOperationStore.STATUS_SYNCED, first.status)
        assertTrue("the Sync says why: '${first.displayMessage}'",
            first.displayMessage.startsWith("Synced; 1 review card was not sent: the server does not know the version of the HTML page"))
        assertEquals(listOf("superseded html_attachment_unknown"), rows.map { "${it.deliveryState} ${it.failureCode}" })
        assertEquals("not retried", 1, rows.size)
        assertTrue(rows.single().failureDetail.startsWith("Not sent: the server does not know the version of the HTML page"))
        assertNotNull("listed as not sent", HtmlAttachmentCardStatus.of(context).notSent(bad.id))
        assertEquals("later Syncs are not failed by it", SyncOperationStore.STATUS_SYNCED, second.status)
        assertEquals("", second.displayMessage)
        assertNotNull("the good card is still on the server", inspect(created.id)["payload"].takeIf { it.isJsonObject })
        step("A done")
    }

    // ---- Device B --------------------------------------------------------------------------

    @Test fun deviceB() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        step("B3 sync: the card arrives")
        sync("first-b")
        val entry = ownerEntry()
        val serverCard = client.projections().projections.single {
            it.entityType == "review_card" && it.payload?.getAsJsonObject("primary")?.get("question")?.asString == question
        }
        val cardId = serverCard.entityId
        val attachmentSha = serverCard.payload!!.getAsJsonObject("primary").getAsJsonObject("html_attachment")["sha256"].asString
        val card = checkNotNull(localCard(cardId)) { "card $cardId synced to the second device" }
        assertTrue(card.isHtmlAttachmentCard)
        assertEquals("#simulator", card.htmlAttachment!!.pageHash)
        assertEquals(answer, card.htmlAttachment!!.quote.exact)

        step("B3 review from the queue: page at #simulator, answer masked, Reveal")
        review(cardId, entry, expectFound = true, expectedAttachmentSha = attachmentSha, label = "b3")

        step("B4 the attachment changes; the card stays")
        val changed = control("POST", "/change-attachment")
        note("changed: $changed")
        val newSha = changed["attachment_sha256"].asString
        assertFalse(newSha == attachmentSha)
        var resolved: String? = null
        awaitTrue("phone has the new attachment version", 240_000) {
            sync("attachment-v2")
            resolved = OfflineSourceBundleStore(context).htmlAttachmentFile(attachmentPath, entry.filename)?.sha256
            note("attachment on phone: $resolved")
            resolved == newSha
        }
        val after = ownerEntry()
        assertServerCard(cardId, after, attachmentSha, "after attachment change")
        val stillLocal = checkNotNull(localCard(cardId)) { "card kept on the phone" }
        assertEquals(ReviewStore(context).card(cardId)?.status, "approved")
        assertEquals(attachmentSha, stillLocal.htmlAttachment!!.sha256)
        review(cardId, after, expectFound = false, expectedAttachmentSha = newSha, label = "b4")
        assertTrue("listed as not found", HtmlAttachmentCardStatus.of(context).isNotFound(cardId))

        step("B6 the owner PDF is rebuilt; the card stays due and listed")
        val rebuilt = control("POST", "/rebuild")
        note("rebuilt: $rebuilt")
        val newPdf = rebuilt["pdf_sha256"].asString
        assertFalse(newPdf == after.sha256)
        awaitTrue("phone has the rebuilt PDF", 240_000) {
            sync("rebuild")
            val file = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), entry.filename)
            file.isFile && sha256(file) == newPdf
        }
        val current = ownerEntry()
        assertEquals(newPdf, current.sha256)
        assertServerCard(cardId, current, attachmentSha, "after rebuild")
        val due = ReviewStore(context).dueCards(now = Instant.now().plusSeconds(400L * 86_400), pdfStem = entry.filename.removeSuffix(".pdf"))
        note("due after rebuild: ${due.map { it.id }}")
        assertTrue("card due for its document after the rebuild", due.any { it.id == cardId })
        val listed = documentReviewCards(ReviewStore(context).browserCards(), entry.filename, null, newPdf)
        assertTrue("card listed for the rebuilt PDF", listed.any { it.card.id == cardId })
        review(cardId, current, expectFound = false, expectedAttachmentSha = newSha, label = "b6")
        step("B done")
    }

    /** The review queue's path (HomeActivity.startPdfReview): the reader opened on the card. */
    private fun review(cardId: String, entry: LibraryEntry, expectFound: Boolean, expectedAttachmentSha: String, label: String) {
        val store = ReviewStore(context)
        val queued = store.dueCards(now = Instant.now().plusSeconds(400L * 86_400), pdfStem = entry.filename.removeSuffix(".pdf"))
        assertTrue("card $cardId in the review queue ($label): ${queued.map { it.id }}", queued.any { it.id == cardId })
        val card = checkNotNull(store.card(cardId))
        val file = checkNotNull(store.reviewPdfFile(card)) { "review PDF of $cardId ($label)" }
        assertEquals(entry.filename, file.name)
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.fromFile(file)
            putExtra(HomeActivity.EXTRA_FROM_HOME, true)
            putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, cardId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        openReader(intent) { scenario, binding, attachments, viewer, script ->
            awaitUi(scenario, "card shown ($label)", 30_000) { binding.reviewQuestion.text.toString() == question }
            awaitUi(scenario, "attachment page open for the review ($label)", 20_000) { attachments.isActive && attachments.isReady }
            scenario.onActivity { assertEquals(expectedAttachmentSha, attachments.shown!!.attachment.sha256) }
            awaitScript(script, "hash restored ($label)", "location.hash", "\"#simulator\"")
            awaitScript(script, "simulator rendered ($label)", "document.body.getAttribute('data-tab')", "\"simulator\"")
            if (expectFound) {
                awaitUi(scenario, "answer found ($label)", 15_000) { attachments.reviewFound == true }
                awaitUi(scenario, "answer masked ($label)") { attachments.reviewPresenter.blockCount() > 0 }
                val masked = viewer("info")!!.asJsonObject.getAsJsonObject("review")
                assertEquals(cardId, masked["card_id"].asString)
                assertFalse(masked["revealed"].asBoolean)
                screenshot("$label-masked")
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (viewer("info")?.asJsonObject?.getAsJsonObject("review")?.get("revealed")?.asBoolean != true) {
                    if (SystemClock.uptimeMillis() > deadline) fail("answer revealed ($label)")
                    SystemClock.sleep(200)
                }
                screenshot("$label-revealed")
            } else {
                awaitUi(scenario, "answer not found ($label)", 15_000) { attachments.reviewFound == false }
                scenario.onActivity {
                    val text = attachments.notFoundText
                    note("not found text ($label): $text")
                    assertNotNull(text)
                    assertTrue(text!!.startsWith("Answer not found in the current page"))
                    assertFalse("answer hidden until Reveal", text.contains(answer))
                    assertEquals(0, attachments.reviewPresenter.blockCount())
                }
                screenshot("$label-not-found")
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                awaitUi(scenario, "stored answer shown ($label)") { attachments.notFoundText?.contains(answer) == true }
                screenshot("$label-not-found-revealed")
            }
        }
    }

    // ---- UI helpers ------------------------------------------------------------------------

    /** Screen coordinates of the first page's thoughts-html: link, as a tap there finds it. */
    private fun findLink(scenario: ActivityScenario<MainActivity>, binding: ActivityMainBinding): Pair<Int, Int> {
        var found: Pair<Int, Int>? = null
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity {
                val view = binding.pdfView
                val hits = mutableListOf<Pair<Int, Int>>()
                for (y in 0 until view.height step 12) for (x in 0 until view.width step 12) {
                    val uri = view.findLinkAt(x.toFloat(), y.toFloat())?.link?.uri ?: continue
                    if (uri.startsWith("thoughts-html:")) hits += x to y
                }
                if (hits.isNotEmpty()) {
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    found = (location[0] + hits.map { it.first }.average().toInt()) to (location[1] + hits.map { it.second }.average().toInt())
                }
            }
            if (found == null) SystemClock.sleep(500)
        }
        return checkNotNull(found) { "no thoughts-html: link on the shown page" }
    }

    private fun awaitUi(scenario: ActivityScenario<MainActivity>, message: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var matched = false
        while (!matched && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { matched = condition() }
            if (!matched) SystemClock.sleep(100)
        }
        if (!matched) fail(message)
    }

    private fun awaitScript(script: (String) -> String, message: String, source: String, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (script(source) == expected) return
            SystemClock.sleep(100)
        }
        fail("$message: ${script(source)}")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/attachment-live-$name.png").close()
        SystemClock.sleep(700)
    }

    private fun openReader(
        intent: Intent,
        body: (ActivityScenario<MainActivity>, ActivityMainBinding, HtmlAttachmentReaderController,
               (String, Array<out Any?>) -> JsonElement?, (String) -> String) -> Unit,
    ) {
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var attachments: HtmlAttachmentReaderController
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                attachments = activity.attachmentReader
            }
            val viewer = { name: String, args: Array<out Any?> ->
                val latch = CountDownLatch(1)
                val result = AtomicReference<Result<JsonElement>>()
                scenario.onActivity { attachments.webView!!.callWith(name, *args) { result.set(it); latch.countDown() } }
                assertTrue("viewer $name answered", latch.await(10, TimeUnit.SECONDS))
                result.get().getOrThrow()
            }
            val script = { source: String ->
                val latch = CountDownLatch(1)
                val result = AtomicReference<String>()
                scenario.onActivity { attachments.webView!!.evaluateJavascript(source) { result.set(it); latch.countDown() } }
                assertTrue(latch.await(10, TimeUnit.SECONDS))
                result.get()
            }
            body(scenario, binding, attachments, viewer, script)
        }
    }

    private operator fun ((String, Array<out Any?>) -> JsonElement?).invoke(name: String, vararg args: Any?): JsonElement? =
        this(name, args)

    companion object {
        private const val TAG = "AttachmentLiveE2E"
    }
}
