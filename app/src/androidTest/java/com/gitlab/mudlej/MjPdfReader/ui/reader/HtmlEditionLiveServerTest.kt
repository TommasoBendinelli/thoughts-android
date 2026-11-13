// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAvailability
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionStore
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.sync.SyncOperationStore
import com.gitlab.mudlej.MjPdfReader.data.sync.ExplicitSyncOrigin
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsSyncScheduler
import com.gitlab.mudlej.MjPdfReader.data.sync.isTerminalSyncOperationStatus
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.tbend.thoughts.sdk.LibraryEntry
import org.tbend.thoughts.sdk.SyncClient

/**
 * The HTML view end to end against a live local server (thoughts-server
 * tools/html_edition_live_server.py on the emulator's host, provisioned by
 * tools/run-isolated-tests.py --server-config; build with -PthoughtsTestServerUrl).
 *
 * Sync downloads the LaTeX PDF and its HTML edition; the HTML view highlights the existing
 * card from its marker anchors; review masks and reveals it; a card made from an HTML
 * selection is uploaded by the next Sync, the server places its markers, a later Sync rebuilds
 * the PDF, and the card gets its PDF placement on the phone. Steps log "HtmlLiveE2E step".
 */
@RunWith(AndroidJUnit4::class)
class HtmlEditionLiveServerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun step(name: String) = android.util.Log.i("HtmlLiveE2E", "step $name")

    /**
     * One Sync as the Sync button starts it (ThoughtsSyncScheduler.enqueue, through WorkManager:
     * control pass, artifact download, build continuations), waited for until it is final.
     */
    private fun sync(label: String, timeoutMs: Long = 180_000) {
        val operations = SyncOperationStore(context)
        val operationId = operations.begin(null, false)
        ThoughtsSyncScheduler.enqueue(context, preparedOperationId = operationId, origin = ExplicitSyncOrigin.BACKGROUND_COMMAND)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val record = operations.operation(operationId)
            val status = record?.status.orEmpty()
            if (status != last) android.util.Log.i("HtmlLiveE2E", "sync $label: $status")
            last = status
            if (record != null && isTerminalSyncOperationStatus(status)) {
                assertEquals("Sync $label ended with $record", SyncOperationStore.STATUS_SYNCED, status)
                return
            }
            SystemClock.sleep(1_000)
        }
        fail("Sync $label not finished after ${timeoutMs / 1000} s (last status $last)")
    }

    private fun client(): SyncClient {
        val config = checkNotNull(ThoughtsServerSecretStore(context).config()) { "no test server provisioned" }
        assertEquals(BuildConfig.TEST_SERVER_URL, config.baseUrl)
        assertTrue("a local test server", config.baseUrl.startsWith("http://10.0.2.2:"))
        return SyncClient(config.baseUrl, config.token)
    }

    private fun latexEntry(client: SyncClient): LibraryEntry =
        checkNotNull(client.library())!!.publications.single { it.filename.contains("paper") }

    private fun serverCard(client: SyncClient, cardId: String): JsonObject? =
        client.projections().projections.firstOrNull { it.entityType == "review_card" && it.entityId == cardId }?.payload

    private fun awaitTrue(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(1_000)
        }
        fail(message)
    }

    /** Waits for the edition worker that a finished Sync starts to install [pdfSha256]'s edition. */
    private fun awaitEdition(store: HtmlEditionStore, pdfSha256: String) {
        awaitTrue("HTML edition of $pdfSha256 installed", 120_000) {
            store.availability(pdfSha256) is HtmlAvailability.Ready
        }
    }

    @Test fun syncEditionReviewAndHtmlCardRoundTrip() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val client = client()
        val existingCard = "4150ddf3-0000-4000-8000-000000000001"
        val editionStore = HtmlEditionStore.default(context)

        step("1 sync")
        sync("first")
        val entry = latexEntry(client)
        assertEquals("ready", entry.htmlStatus)
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), entry.filename)
        assertTrue("PDF downloaded: $pdf", pdf.isFile)
        val stem = entry.filename.removeSuffix(".pdf")
        assertNotNull("existing card synced", ReviewStore(context).card(existingCard))

        step("2 edition download")
        awaitEdition(editionStore, entry.sha256)

        val question = "What is tidal locking?"
        val exact = "tidal locking keeps one hemisphere of the moon facing the earth"
        lateinit var created: ReviewCard
        openReader(pdf) { scenario, binding, html, viewer ->
            step("3 existing card highlighted")
            awaitUi(scenario, "existing card highlighted from its anchors") { existingCard in html.lastPlacement.placedCards }
            scenario.onActivity { assertEquals(entry.htmlEditionSha256, html.edition!!.sha256) }
            screenshot("live-1-html")

            step("4 review mask + reveal")
            val card = checkNotNull(ReviewStore(context).card(existingCard))
            lateinit var controller: ReviewSessionController
            scenario.onActivity { activity ->
                controller = ReviewSessionController(activity, binding,
                    DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = entry.sha256, documentId = entry.documentId),
                    ReviewStore(context))
                MainActivity::class.java.getDeclaredField("reviewSessionController").apply { isAccessible = true }.set(activity, controller)
                ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                    ReviewInterval::class.java, Map::class.java).apply { isAccessible = true }
                    .invoke(controller, card, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
            }
            awaitUi(scenario, "answer masked in HTML") { html.reviewPresenter.blockCount() > 0 }
            val masked = viewer("info")!!.asJsonObject.getAsJsonObject("review")
            assertEquals(existingCard, masked["card_id"].asString)
            assertFalse(masked["revealed"].asBoolean)
            screenshot("live-2-masked")
            scenario.onActivity { binding.reviewRevealButton.performClick() }
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (viewer("info")?.asJsonObject?.getAsJsonObject("review")?.get("revealed")?.asBoolean != true) {
                if (SystemClock.uptimeMillis() > deadline) fail("answer revealed")
                SystemClock.sleep(200)
            }
            screenshot("live-3-revealed")
            scenario.onActivity { html.reviewPresenter.clear() }

            step("5 card from an HTML selection")
            assertTrue(viewer("selectQuote", JsonObject().apply { addProperty("exact", exact) })!!.asBoolean)
            val selection = HtmlReaderModel.Selection.parse(viewer("getSelection")!!.asJsonObject)!!
            assertEquals(exact, selection.quote!!.exact)
            lateinit var fields: JsonObject
            scenario.onActivity { fields = html.selectionFields(selection)!! }
            created = ReviewStore(context).createHtmlCard(stem, entry.sha256, question, fields, exact,
                documentId = entry.documentId).getOrThrow()
            assertTrue(created.htmlPending)
            scenario.onActivity { html.refreshItems() }
            awaitUi(scenario, "pending card highlighted by its quote") { created.id in html.lastPlacement.placedCards }
            viewer("clearSelection")
            screenshot("live-4-pending-card")
        }

        step("6 Sync uploads the card; the server links it")
        sync("upload")
        awaitTrue("server linked the HTML card", 300_000) {
            serverCard(client, created.id)?.getAsJsonObject("source_link")?.get("state")?.asString == "linked"
        }

        step("7 Sync rebuilds; the card gets its PDF placement")
        var placed: ReviewCard? = null
        val deadline = SystemClock.uptimeMillis() + 240_000  // under run-isolated-tests.py's 600 s, so a failure is reported
        while (placed == null && SystemClock.uptimeMillis() < deadline) {
            sync("rebuild")
            val current = latexEntry(client)
            val card = ReviewStore(context).card(created.id)
            android.util.Log.i("HtmlLiveE2E", "card ${card?.reanchorState} page=${card?.page} sha=${card?.pdfSha256} " +
                "pdf=${current.sha256} link=${card?.sourceLinkState}")
            if (card != null && card.reanchorState == "succeeded" && card.page >= 1 && !card.rect.isEmpty &&
                card.pdfSha256 == current.sha256 && current.sha256 != entry.sha256) placed = card
            else SystemClock.sleep(5_000)
        }
        val card = checkNotNull(placed) { "the HTML card never got PDF placement on the phone" }
        assertFalse(card.htmlPending)
        val rebuilt = latexEntry(client)
        assertTrue(File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), rebuilt.filename).isFile)

        step("8 the new edition places the card from its anchors")
        awaitEdition(editionStore, rebuilt.sha256)
        openReader(File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), rebuilt.filename)) { scenario, _, html, _ ->
            awaitUi(scenario, "new card highlighted from its anchors") {
                card.id in html.lastPlacement.placedCards && existingCard in html.lastPlacement.placedCards
            }
            screenshot("live-5-linked-card")
        }
        step("done")
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

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/html-view-$name.png").close()
        SystemClock.sleep(700)
    }

    /** Opens [pdf] in the reader and switches to the HTML view. */
    private fun openReader(
        pdf: File,
        body: (ActivityScenario<MainActivity>, ActivityMainBinding, HtmlReaderController, Viewer) -> Unit,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var html: HtmlReaderController
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                html = activity.htmlReader
            }
            val viewer = Viewer { name, args ->
                val latch = CountDownLatch(1)
                val result = AtomicReference<Result<JsonElement>>()
                scenario.onActivity { html.webView!!.callWith(name, *args) { result.set(it); latch.countDown() } }
                assertTrue("viewer $name answered", latch.await(10, TimeUnit.SECONDS))
                result.get().getOrThrow()
            }
            awaitUi(scenario, "PDF loaded") { binding.pdfView.pageCount > 0 }
            awaitUi(scenario, "HTML view available") { html.availability() is HtmlAvailability.Ready }
            scenario.onActivity { if (!html.isActive) html.toggle() }
            awaitUi(scenario, "HTML view ready") { html.isActive && html.isReady }
            body(scenario, binding, html, viewer)
        }
    }

    /** A viewer call from the test thread: waits for its result. */
    fun interface Viewer {
        fun call(name: String, args: Array<out Any?>): JsonElement?
        operator fun invoke(name: String, vararg args: Any?): JsonElement? = call(name, args)
    }
}
