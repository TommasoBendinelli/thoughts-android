// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlAvailability
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlEditionStore
import com.gitlab.mudlej.MjPdfReader.data.html.HtmlReaderModel
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonArray
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
 * The reader's HTML view on a real WebView (thoughts-html-view-SPEC.md, phase 3): the spike's
 * thoughts_manual tex4ht edition is installed in the edition cache, the reader switches to HTML,
 * the vendored viewer loads from the in-process origin with nothing requested outside the edition,
 * a review card is masked by its marker anchors, revealed, feedback is given on a selection inside
 * the answer (view "html"), and a selection outside it becomes a card request (created_in "html").
 * Long-press selection is checked with the viewer's contextMenu "native" and "app".
 * Screens are saved to /data/local/tmp/html-view-*.png.
 */
@RunWith(AndroidJUnit4::class)
class HtmlReaderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Installs the sample edition from the test assets for [pdfSha256]; returns its manifest hash. */
    private fun installSample(store: HtmlEditionStore, pdfSha256: String, filename: String): String {
        val assets = instrumentation.context.assets
        val manifest = JsonParser.parseString(assets.open("html-edition-sample/manifest.json").reader().readText()).asJsonObject
        val objects = mutableMapOf<String, ByteArray>()
        manifest.getAsJsonObject("files").entrySet().forEach { (path, digest) ->
            val bytes = assets.open("html-edition-sample/$path").use { it.readBytes() }
            assertEquals("sample file $path", digest.asString, sha256(bytes))
            objects[digest.asString] = bytes
        }
        val manifestBytes = manifest.toString().toByteArray()
        val editionSha = sha256(manifestBytes)
        objects[editionSha] = manifestBytes
        store.install(pdfSha256, filename, editionSha) { sha, destination ->
            destination.writeBytes(objects[sha] ?: error("no object $sha"))
        }
        return editionSha
    }

    @Test fun sampleEditionReviewsAndSelections() {
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "html-view-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val reviewStore = ReviewStore(null, File(root, "review"), File(root, "incoming"), File(root, "events"), File(root, "cards"))
        val previousFeedbackFile = ReviewCardFeedback.file
        ReviewCardFeedback.file = { File(root, "card-feedback.json") }
        val editionStore = HtmlEditionStore.default(context)
        val previousMenuMode = HtmlReaderController.contextMenuMode
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                val paint = Paint().apply { textSize = 18f; isAntiAlias = true }
                page.canvas.drawText("Thoughts Manual", 45f, 65f, paint)
                page.canvas.drawText("The Library tab shows all your projects.", 45f, 165f, paint)
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = sha256(pdf.readBytes())
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().registerImport("$stem.pdf", hash, pdf)
            val editionSha = installSample(editionStore, hash, "$stem.pdf")
            assertTrue(editionStore.availability(hash) is HtmlAvailability.Ready)

            // A source-linked card whose marker anchors are in the sample edition.
            val cardId = "bbb4a295-91f5-452d-a015-e9494adc1719"
            val question = "What does the Library tab show?"
            val record = JsonObject().apply {
                addProperty("schema_version", 3); addProperty("card_type", "visual_region"); addProperty("card_id", cardId)
                addProperty("status", "approved"); addProperty("revision", 1); addProperty("origin_id", "test-server")
                add("primary", JsonObject().apply {
                    addProperty("pdf_stem", stem); addProperty("pdf_sha256", hash); addProperty("document_id", documentId)
                    addProperty("page", 1); addProperty("page_visual_sha256", hash); addProperty("question", question)
                    add("rect", JsonArray().apply { listOf(.05f, .17f, .9f, .22f).forEach { add(it) } })
                    add("source_anchor", JsonParser.parseString("""{"version":2,"segments":[{"segment_id":"0",
                        "source_file":"docs-template/tex/source/thoughts_manual.tex","content_sha256":"${"a".repeat(64)}"}]}"""))
                })
                add("reanchor", JsonObject().apply { addProperty("state", "succeeded"); addProperty("strategy", "source_markers_v2") })
            }
            File(root, "incoming/cards/server/$cardId.json").apply { parentFile!!.mkdirs(); writeText(record.toString()) }
            val card: ReviewCard = checkNotNull(reviewStore.card(cardId))

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
                    html.reviewStore = { reviewStore }
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
                /** A viewer call from the test thread: waits for its result. */
                fun viewer(name: String, vararg args: Any?): JsonElement? {
                    val latch = CountDownLatch(1)
                    val result = AtomicReference<Result<JsonElement>>()
                    scenario.onActivity { html.webView!!.callWith(name, *args) { result.set(it); latch.countDown() } }
                    assertTrue("viewer $name answered", latch.await(10, TimeUnit.SECONDS))
                    return result.get().getOrThrow()
                }
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(700)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/html-view-$name.png").close()
                    SystemClock.sleep(700)
                }

                /** Polls a viewer call from the test thread until [condition] holds. */
                fun awaitViewer(message: String, name: String, condition: (JsonElement?) -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (condition(viewer(name))) return
                        SystemClock.sleep(100)
                    }
                    fail(message)
                }
                /** Runs a script in the page (not through the viewer) and returns its JSON result. */
                fun script(source: String): String {
                    val latch = CountDownLatch(1)
                    val result = AtomicReference<String>()
                    scenario.onActivity { html.webView!!.evaluateJavascript(source) { result.set(it); latch.countDown() } }
                    assertTrue(latch.await(10, TimeUnit.SECONDS))
                    return result.get()
                }

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                awaitUi("HTML view available") { html.availability() is HtmlAvailability.Ready }
                scenario.onActivity { html.toggle() }
                awaitUi("HTML view ready") { html.isActive && html.isReady }
                scenario.onActivity {
                    assertEquals(View.VISIBLE, html.webView!!.visibility)
                    assertEquals(View.INVISIBLE, binding.pdfView.visibility)
                    assertEquals(editionSha, html.edition!!.sha256)
                }
                awaitUi("card highlighted from its anchors") { cardId in html.lastPlacement.placedCards }
                val info = viewer("info")!!.asJsonObject
                assertEquals(com.gitlab.mudlej.MjPdfReader.ui.html.HtmlViewerAssets.PINNED_VERSION, info["version"].asString)
                assertEquals("lines", info["source_map"].asString)
                scenario.onActivity { assertEquals("nothing outside the edition was requested", 0, html.webView!!.refusedRequests) }
                // The edition is served with script-src 'none': an inline script never runs.
                assertEquals("\"false\"", script("(function(){var s=document.createElement('script');" +
                    "s.textContent='window.__inlineRan=true';document.body.appendChild(s);return String(!!window.__inlineRan);})()"))
                // No network: a fetch outside the edition fails.
                assertEquals("\"blocked\"", script("(function(){try{var x=new XMLHttpRequest();x.open('GET','https://example.org/',false);" +
                    "x.send();return 'loaded '+x.status;}catch(e){return 'blocked';}})()"))
                screenshot("1-html")

                // Text size and theme.
                assertEquals(130, viewer("setTextSize", 130)!!.asJsonObject["text_size"].asInt)
                viewer("setTheme", "dark")
                screenshot("2-dark-130")
                viewer("setTheme", "light")
                viewer("setTextSize", 100)

                // Review: the card is masked in the HTML view.
                lateinit var controller: ReviewSessionController
                scenario.onActivity { activity ->
                    controller = ReviewSessionController(activity, binding,
                        DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = hash, documentId = documentId), reviewStore)
                    MainActivity::class.java.getDeclaredField("reviewSessionController").apply { isAccessible = true }.set(activity, controller)
                    ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                        ReviewInterval::class.java, Map::class.java).apply { isAccessible = true }
                        .invoke(controller, card, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                }
                awaitUi("card shown") { binding.reviewQuestion.text.toString() == question }
                awaitUi("answer masked in HTML") { html.reviewPresenter.blockCount() > 0 }
                val masked = viewer("info")!!.asJsonObject.getAsJsonObject("review")
                assertEquals(cardId, masked["card_id"].asString)
                assertFalse(masked["revealed"].asBoolean)
                val blocks = masked.getAsJsonArray("blocks")
                assertTrue("mask blocks ${blocks.size()}", blocks.size() in 1..3)
                // Masked text cannot be copied.
                viewer("selectQuote", JsonObject().apply { addProperty("exact", "Each project groups PDF documents") })
                val maskedCopy = viewer("copySelection")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                assertFalse("masked text copied: '$maskedCopy'", maskedCopy.contains("groups PDF"))
                viewer("clearSelection")
                screenshot("3-masked")

                scenario.onActivity { binding.reviewRevealButton.performClick() }
                awaitViewer("revealed", "info") { it?.asJsonObject?.getAsJsonObject("review")?.get("revealed")?.asBoolean == true }
                screenshot("4-revealed")

                // Like inside the revealed answer: a quote-only record with view "html".
                assertTrue(viewer("selectQuote", JsonObject().apply { addProperty("exact", "Every document belongs to a project.") })!!.asBoolean)
                val inAnswer = HtmlReaderModel.Selection.parse(viewer("getSelection")!!.asJsonObject)!!
                assertEquals(cardId, inAnswer.answerCardId)
                scenario.onActivity { controller.giveHtmlFeedback(inAnswer.answerQuote!!, editionSha, HtmlReaderController.ReviewFeedbackAction.LIKE) }
                awaitUi("feedback saved") { ReviewCardFeedback.forCard(cardId).isNotEmpty() }
                val feedback = ReviewCardFeedback.forCard(cardId).single()
                assertEquals("html", feedback.view)
                assertEquals(editionSha, feedback.htmlEditionSha256)
                assertEquals("Every document belongs to a project.", feedback.exact)
                assertEquals(hash, feedback.pdfSha256)
                assertEquals(emptyList<ReviewCardFeedback.Region>(), feedback.regions)
                awaitViewer("feedback highlighted", "info") { (it?.asJsonObject?.get("items")?.asInt ?: 0) >= 2 }
                viewer("clearSelection")
                screenshot("5-feedback")

                // End the review; a selection outside any card becomes a card request.
                scenario.onActivity { html.reviewPresenter.clear() }
                assertTrue(viewer("selectQuote", JsonObject().apply { addProperty("exact", "four main tabs") })!!.asBoolean)
                val selection = HtmlReaderModel.Selection.parse(viewer("getSelection")!!.asJsonObject)!!
                assertEquals("four main tabs", selection.quote!!.exact)
                assertTrue(selection.sourceLines != null)
                lateinit var fields: JsonObject
                scenario.onActivity { fields = html.selectionFields(selection)!! }
                assertEquals("html", fields["created_in"].asString)
                assertEquals(editionSha, fields["html_edition_sha256"].asString)
                assertEquals("four main tabs", fields.getAsJsonObject("text_quote")["exact"].asString)
                assertFalse("a LaTeX edition has no exact source map", fields.has("source_range"))
                val created = reviewStore.createHtmlCard(stem, hash, "How many tabs?", fields, selection.quote!!.exact,
                    documentId = documentId).getOrThrow()
                assertTrue(created.htmlPending)
                val saved = File(root, "cards").walkTopDown().first { it.name == "${created.id}.json" }
                val savedRecord = JsonParser.parseString(saved.readText()).asJsonObject
                assertEquals("pending_server_link", savedRecord.getAsJsonObject("source_link")["state"].asString)
                assertEquals("html", savedRecord.getAsJsonObject("primary")["created_in"].asString)
                assertEquals(editionSha, savedRecord.getAsJsonObject("primary")["html_edition_sha256"].asString)
                scenario.onActivity { html.refreshItems() }
                awaitUi("pending card highlighted by its quote") { created.id in html.lastPlacement.placedCards }
                viewer("clearSelection")
                screenshot("6-pending-card")

                // Long press: contextMenu "native" (default) keeps Android's selection.
                fun longPressSelects(): String {
                    viewer("scrollTo", JsonObject().apply { addProperty("scroll_y", 0) })
                    assertTrue(viewer("selectQuote", JsonObject().apply { addProperty("exact", "Thoughts Manual") })!!.asBoolean)
                    val rect = HtmlReaderModel.Selection.parse(viewer("getSelection")!!.asJsonObject)!!.bounds
                    val dpr = viewer("getSelection")!!.asJsonObject["dpr"].asFloat
                    viewer("clearSelection")
                    val location = IntArray(2)
                    scenario.onActivity { html.webView!!.getLocationOnScreen(location) }
                    val x = location[0] + (rect[0] + rect[2] / 4) * dpr
                    val y = location[1] + (rect[1] + rect[3] / 2) * dpr
                    instrumentation.uiAutomation.executeShellCommand("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 1200").close()
                    SystemClock.sleep(2500)
                    val after = viewer("getSelection")
                    screenshot("7-long-press-${HtmlReaderController.contextMenuMode}")
                    viewer("clearSelection")
                    return if (after == null || after.isJsonNull) "" else after.asJsonObject["text"].asString
                }
                val native = longPressSelects()
                android.util.Log.i("HtmlReaderTest", "long press with contextMenu native selected '$native'")
                assertTrue("long press selects a word with contextMenu native: '$native'", native.isNotBlank())
                HtmlReaderController.contextMenuMode = "app"
                scenario.onActivity { html.showPdf(remember = false); html.toggle() }
                awaitUi("HTML view ready again") { html.isActive && html.isReady }
                val app = longPressSelects()
                android.util.Log.i("HtmlReaderTest", "long press with contextMenu app selected '$app'")

                // Back to the PDF; the choice is remembered per document.
                scenario.onActivity { html.showPdf(remember = true) }
                scenario.onActivity {
                    assertFalse(html.isActive)
                    assertEquals(View.VISIBLE, binding.pdfView.visibility)
                    assertFalse(html.prefersHtml())
                }
            }
        } finally {
            HtmlReaderController.contextMenuMode = previousMenuMode
            ReviewCardFeedback.file = previousFeedbackFile
            pdf.delete()
            root.deleteRecursively()
            editionStore.retain(emptySet())
        }
    }
}
