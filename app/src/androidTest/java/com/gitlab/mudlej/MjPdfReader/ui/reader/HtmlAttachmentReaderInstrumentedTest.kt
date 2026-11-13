// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCardStatus
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentCards
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAttachmentFile
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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

/**
 * Review cards inside an HTML attachment page on a real WebView (thoughts-html-attachment-cards-SPEC.md):
 * a single-page-app fixture whose text its own script renders after load, with tabs routed by
 * `location.hash`. The page opens over the reader from its `thoughts-html:` link, its scripts run
 * with nothing requested outside the page and no network; a selection on the #simulator tab
 * becomes a card of the PDF's document (`created_in: "html_attachment"`, page_state #simulator);
 * its review restores #simulator from another tab and masks the answer once the page re-rendered;
 * a card whose text is not in the page shows "Answer not found in the current page" and, on
 * Reveal, its stored answer. Screens are saved to /data/local/tmp/html-attachment-*.png.
 */
@RunWith(AndroidJUnit4::class)
class HtmlAttachmentReaderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun cardsInAScriptRenderedAttachmentPage() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "html-attachment-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val reviewStore = ReviewStore(null, File(root, "review"), File(root, "incoming"), File(root, "events"), File(root, "cards"))
        val path = "docs-ideas/tex/support/html/explainer.html"
        val pageBytes = instrumentation.context.assets.open("html-attachment-sample/explainer.html").use { it.readBytes() }
        val pageFile = File(root, "explainer.html").apply { writeBytes(pageBytes) }
        val pageSha = sha256(pageBytes)
        val status = HtmlAttachmentCardStatus(context.getSharedPreferences("$stem-status", Context.MODE_PRIVATE))
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                val paint = Paint().apply { textSize = 18f; isAntiAlias = true }
                page.canvas.drawText("Ideas", 45f, 65f, paint)
                page.canvas.drawText("A page that renders itself.", 45f, 165f, paint)
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = sha256(pdf.readBytes())
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().registerImport("$stem.pdf", hash, pdf)

            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                lateinit var attachments: HtmlAttachmentReaderController
                val resolved = mutableListOf<Triple<String, String?, String?>>()
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                    attachments = activity.attachmentReader
                    attachments.reviewStore = { reviewStore }
                    attachments.cardsSupported = { true }
                    attachments.status = status
                    attachments.resolver = { requested, ownerPdf, fallback ->
                        resolved += Triple(requested, ownerPdf, fallback)
                        HtmlAttachmentFile(requested, pageFile, pageSha).takeIf { requested == path }
                    }
                }
                fun awaitUi(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + timeoutMs
                    var matched = false
                    while (!matched && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { matched = condition() }
                        if (!matched) SystemClock.sleep(100)
                    }
                    if (!matched) fail(message)
                }
                fun viewer(name: String, vararg args: Any?): JsonElement? {
                    val latch = CountDownLatch(1)
                    val result = AtomicReference<Result<JsonElement>>()
                    scenario.onActivity { attachments.webView!!.callWith(name, *args) { result.set(it); latch.countDown() } }
                    assertTrue("viewer $name answered", latch.await(10, TimeUnit.SECONDS))
                    return result.get().getOrThrow()
                }
                /** A script in the page (not through the viewer); its JSON result. */
                fun script(source: String): String {
                    val latch = CountDownLatch(1)
                    val result = AtomicReference<String>()
                    scenario.onActivity { attachments.webView!!.evaluateJavascript(source) { result.set(it); latch.countDown() } }
                    assertTrue(latch.await(10, TimeUnit.SECONDS))
                    return result.get()
                }
                fun awaitScript(message: String, source: String, expected: String) {
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (script(source) == expected) return
                        SystemClock.sleep(100)
                    }
                    fail("$message: ${script(source)}")
                }
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(700)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/html-attachment-$name.png").close()
                    SystemClock.sleep(700)
                }

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                // The PDF's thoughts-html: link: the page opens over the reader, owned by this document.
                scenario.onActivity { attachments.openLink(path) }
                awaitUi("attachment page ready") { attachments.isActive && attachments.isReady }
                scenario.onActivity {
                    val shown = attachments.shown!!
                    assertEquals(pageSha, shown.attachment.sha256)
                    assertEquals(HtmlAttachmentCards.Owner(documentId, "$stem.pdf", hash), shown.owner)
                    assertEquals(Triple(path, "$stem.pdf", null), resolved.first())
                    assertTrue(attachments.canMakeCards())
                }
                // Its own script rendered the first tab after load.
                awaitScript("page rendered", "document.body.getAttribute('data-tab')", "\"experiment\"")
                val info = viewer("info")!!.asJsonObject
                assertEquals("attachment", info["mode"].asString)
                assertEquals(com.gitlab.mudlej.MjPdfReader.ui.html.HtmlViewerAssets.PINNED_VERSION, info["version"].asString)
                // The page runs in an opaque origin (sandbox CSP) and its own scripts still work.
                assertEquals("\"null\"", script("String(self.origin)"))
                // No network: a request outside the page fails; nothing but the page was requested.
                assertEquals("\"blocked\"", script("(function(){try{var x=new XMLHttpRequest();x.open('GET','https://example.org/',false);" +
                    "x.send();return 'loaded '+x.status;}catch(e){return 'blocked';}})()"))
                scenario.onActivity { assertEquals("nothing outside the page was requested", 0, attachments.webView!!.refusedRequests) }
                screenshot("1-experiment")

                // The Simulator tab (the page's own #hash routing), and a selection there.
                script("location.hash='#simulator'")
                awaitScript("simulator tab", "document.body.getAttribute('data-tab')", "\"simulator\"")
                val answer = "The simulator replays a week of agent traffic against your rules."
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (viewer("selectQuote", JsonObject().apply { addProperty("exact", answer) })?.asBoolean != true) {
                    if (SystemClock.uptimeMillis() > deadline) fail("quote on the simulator tab not selectable")
                    SystemClock.sleep(200)
                }
                val selection = HtmlReaderModel.Selection.parse(viewer("getSelection")!!.asJsonObject)!!
                assertEquals(answer, selection.quote!!.exact)
                assertEquals("#simulator", selection.pageHash)
                lateinit var fields: JsonObject
                scenario.onActivity { fields = attachments.selectionFields(selection)!! }
                assertEquals("html_attachment", fields["created_in"].asString)
                assertEquals(path, fields.getAsJsonObject("html_attachment")["path"].asString)
                assertEquals(pageSha, fields.getAsJsonObject("html_attachment")["sha256"].asString)
                assertEquals("#simulator", fields.getAsJsonObject("page_state")["hash"].asString)
                viewer("clearSelection")
                val card: ReviewCard = reviewStore.createHtmlAttachmentCard(stem, hash, "What does the simulator replay?", fields,
                    selection.text, documentId = documentId).getOrThrow()
                val saved = JsonParser.parseString(File(root, "cards").walkTopDown().first { it.name == "${card.id}.json" }.readText()).asJsonObject
                assertEquals("html_attachment", saved.getAsJsonObject("source_link")["state"].asString)
                assertFalse(saved.getAsJsonObject("primary").has("page"))
                scenario.onActivity { attachments.refreshItems() }
                awaitUi("card highlighted by its quote") { card.id in attachments.lastPlacement.placedCards }
                screenshot("2-card-highlighted")

                // Typing re-renders part of the page; the highlight stays.
                script("(function(){var i=document.getElementById('senders');i.value='a';i.dispatchEvent(new Event('input'));})()")
                awaitScript("re-rendered", "document.getElementById('result').textContent", "\"Simulated 1 changes.\"")

                // Review from another tab: the hash is restored, the page re-renders, the answer is masked.
                script("location.hash='#activity'")
                awaitScript("activity tab", "document.body.getAttribute('data-tab')", "\"activity\"")
                lateinit var controller: ReviewSessionController
                fun present(shown: ReviewCard) = scenario.onActivity { activity ->
                    ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                        ReviewInterval::class.java, Map::class.java).apply { isAccessible = true }
                        .invoke(controller, shown, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                }
                scenario.onActivity { activity ->
                    controller = ReviewSessionController(activity, binding,
                        DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = hash, documentId = documentId), reviewStore)
                    MainActivity::class.java.getDeclaredField("reviewSessionController").apply { isAccessible = true }.set(activity, controller)
                }
                val stored = checkNotNull(reviewStore.card(card.id))
                assertTrue(stored.isHtmlAttachmentCard)
                present(stored)
                awaitUi("card shown") { binding.reviewQuestion.text.toString() == "What does the simulator replay?" }
                awaitUi("answer found after the hash restore") { attachments.reviewFound == true }
                awaitScript("hash restored", "location.hash", "\"#simulator\"")
                awaitScript("simulator rendered again", "document.body.getAttribute('data-tab')", "\"simulator\"")
                awaitUi("answer masked") { attachments.reviewPresenter.blockCount() > 0 }
                val masked = viewer("info")!!.asJsonObject.getAsJsonObject("review")
                assertEquals(card.id, masked["card_id"].asString)
                assertFalse(masked["revealed"].asBoolean)
                assertFalse("masked text cannot be copied", (viewer("selectQuote", JsonObject().apply { addProperty("exact", answer) })
                    ?.let { viewer("copySelection")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty() } ?: "").contains("replays a week"))
                viewer("clearSelection")
                scenario.onActivity { assertFalse("no new cards during a review", attachments.canMakeCards()) }
                screenshot("3-masked")
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                val revealDeadline = SystemClock.uptimeMillis() + 10_000
                while (viewer("info")?.asJsonObject?.getAsJsonObject("review")?.get("revealed")?.asBoolean != true) {
                    if (SystemClock.uptimeMillis() > revealDeadline) fail("not revealed")
                    SystemClock.sleep(100)
                }
                assertTrue(!status.isNotFound(card.id))
                screenshot("4-revealed")

                // A card whose text is no longer in the page: the review panel says so, Reveal shows the stored answer.
                val gone = reviewStore.createHtmlAttachmentCard(stem, hash, "What did the old pricing say?",
                    HtmlAttachmentCards.Anchor(path, pageSha, HtmlReaderModel.TextQuote("Prices were shown in three colours.", "", ""),
                        "#experiment").toJson(), "Prices were shown in three colours.", documentId = documentId).getOrThrow()
                present(checkNotNull(reviewStore.card(gone.id)))
                awaitUi("card shown") { binding.reviewQuestion.text.toString() == "What did the old pricing say?" }
                awaitUi("answer not found", 15_000) { attachments.reviewFound == false }
                awaitScript("its hash restored", "location.hash", "\"#experiment\"")
                scenario.onActivity {
                    val text = attachments.notFoundText
                    assertNotNull(text)
                    assertTrue(text!!.startsWith("Answer not found in the current page"))
                    assertFalse("the answer stays hidden until Reveal", text.contains("three colours"))
                    assertEquals(0, attachments.reviewPresenter.blockCount())
                }
                assertTrue(status.isNotFound(gone.id, pageSha))
                screenshot("5-not-found")
                scenario.onActivity { binding.reviewRevealButton.performClick() }
                awaitUi("stored answer shown") { attachments.notFoundText?.contains("Prices were shown in three colours.") == true }
                screenshot("6-not-found-revealed")

                // The review moves on: the page closes with it.
                scenario.onActivity { attachments.reviewPresenter.clear() }
                awaitUi("page closed") { !attachments.isActive }
            }
        } finally {
            pdf.delete()
            root.deleteRecursively()
            context.deleteSharedPreferences("$stem-status")
        }
    }
}
