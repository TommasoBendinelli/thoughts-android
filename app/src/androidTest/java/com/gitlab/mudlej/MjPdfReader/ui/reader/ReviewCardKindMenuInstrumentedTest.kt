// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKindCatalogue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewKindSource
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The review card ⋮ menu's kind choices (protocol/review-card-kinds.md section 5): Make project
 * card on a knowledge card, then Make knowledge card and Follow the Project (Knowledge) once the
 * card has a kind of its own; the "Project card" mark under the question follows the choice.
 */
@RunWith(AndroidJUnit4::class)
class ReviewCardKindMenuInstrumentedTest {
    @Test fun theMenuMakesAProjectCardAndBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val stem = "review-kind-menu-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"), File(root, "events"), File(root, "cards"))
        // A knowledge Project holds the card's PDF.
        val catalogue = JsonParser.parseString("""[{"id":"project-a","name":"Project A","revision":1,"target":"",
            "documents":[{"document_id":"doc-a","filename":"$stem.pdf"}],"unpublished_documents":[],"review_kind":"knowledge"}]""").asJsonArray
        store.kindCatalogueSource = { ReviewKindCatalogue(catalogue) }
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                val paint = Paint().apply { textSize = 18f }
                page.canvas.drawText("Question area above the answer", 45f, 65f, paint)
                page.canvas.drawText("The answer", 45f, 175f, paint)
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes()).joinToString("") { "%02x".format(it) }
            val id = UUID.randomUUID().toString()
            val question = "Which line holds the answer?"
            File(root, "incoming/cards/server/$id.json").apply {
                parentFile!!.mkdirs()
                writeText(JsonObject().apply {
                    addProperty("schema_version", 3)
                    addProperty("card_type", "visual_region")
                    addProperty("card_id", id)
                    addProperty("status", "approved")
                    addProperty("revision", 1)
                    addProperty("origin_id", "test-server")
                    add("primary", JsonObject().apply {
                        addProperty("pdf_stem", stem)
                        addProperty("pdf_sha256", hash)
                        addProperty("page", 1)
                        addProperty("page_visual_sha256", hash)
                        addProperty("question", question)
                        add("rect", JsonArray().apply { listOf(.05f, .17f, .9f, .25f).forEach { add(it) } })
                    })
                    add("reanchor", JsonObject().apply { addProperty("state", "succeeded"); addProperty("strategy", "source_markers_v2") })
                }.toString())
            }
            val card: ReviewCard = checkNotNull(store.card(id))
            assertFalse(card.isProjectCard)
            assertEquals(ReviewKindSource.PROJECT, card.reviewKindSource)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                scenario.onActivity { activity ->
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                }
                fun awaitUi(message: String, condition: () -> Boolean) {
                    val deadline = SystemClock.uptimeMillis() + 15_000
                    var matched = false
                    while (!matched && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { matched = condition() }
                        if (!matched) SystemClock.sleep(50)
                    }
                    if (!matched) fail(message)
                }
                fun menuTitles(view: View): List<String> = when (view) {
                    is ListView -> (0 until view.count).mapNotNull { (view.adapter.getItem(it) as? MenuItem)?.title?.toString() }
                    is ViewGroup -> (0 until view.childCount).flatMap { menuTitles(view.getChildAt(it)) }
                    else -> emptyList()
                }
                fun clickMenu(view: View, label: String): Boolean {
                    if (view is ListView) {
                        for (position in 0 until view.count) {
                            if ((view.adapter.getItem(position) as? MenuItem)?.title?.toString() == label) {
                                return view.performItemClick(view.getChildAt(position - view.firstVisiblePosition),
                                    position, view.adapter.getItemId(position))
                            }
                        }
                    }
                    return view is ViewGroup && (0 until view.childCount).any { clickMenu(view.getChildAt(it), label) }
                }
                fun openMenu(): List<String> {
                    scenario.onActivity { binding.reviewOptionsButton.performClick() }
                    instrumentation.waitForIdleSync()
                    var titles = emptyList<String>()
                    awaitUi("the menu opens") {
                        titles = WindowInspector.getGlobalWindowViews().asReversed().flatMap(::menuTitles)
                        titles.isNotEmpty()
                    }
                    return titles
                }
                fun choose(label: String) {
                    scenario.onActivity {
                        assertTrue("$label is in the menu", WindowInspector.getGlobalWindowViews().asReversed().any { clickMenu(it, label) })
                    }
                    instrumentation.waitForIdleSync()
                }

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                scenario.onActivity { activity ->
                    val controller = ReviewSessionController(activity, binding,
                        DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = hash), store)
                    MainActivity::class.java.getDeclaredField("reviewSessionController")
                        .apply { isAccessible = true }.set(activity, controller)
                    ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                        ReviewInterval::class.java, Map::class.java).apply { isAccessible = true }
                        .invoke(controller, card, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                }
                awaitUi("Card shown") { binding.reviewQuestion.text.toString() == question }
                scenario.onActivity { assertEquals(View.GONE, binding.reviewKindMark.visibility) }

                // A knowledge card following its Project: Make project card, no Follow the Project.
                var titles = openMenu()
                assertTrue(titles.toString(), "Make project card" in titles)
                assertFalse(titles.toString(), titles.any { it.startsWith("Follow the Project") })
                choose("Make project card")
                awaitUi("the card is marked") { binding.reviewKindMark.visibility == View.VISIBLE }
                scenario.onActivity { assertEquals("Project card", binding.reviewKindMark.text.toString()) }
                val made = checkNotNull(store.card(id))
                assertTrue(made.isProjectCard)
                assertEquals(ReviewKindSource.CARD, made.reviewKindSource)
                val events = File(root, "events").walkTopDown().filter { it.isFile && it.extension == "json" }
                    .map { JsonParser.parseString(it.readText()).asJsonObject }.toList()
                val event = events.single { it.get("queue_action")?.asString == "set_review_kind" }
                assertEquals("project", event.get("review_kind").asString)
                assertEquals(id, event.get("card_id").asString)
                assertFalse(event.has("rating"))

                // Its own kind now: Make knowledge card, and Follow the Project naming the Project's kind.
                titles = openMenu()
                assertTrue(titles.toString(), "Make knowledge card" in titles)
                assertTrue(titles.toString(), "Follow the Project (Knowledge)" in titles)
                choose("Follow the Project (Knowledge)")
                awaitUi("the mark goes") { binding.reviewKindMark.visibility == View.GONE }
                val follows = checkNotNull(store.card(id))
                assertFalse(follows.isProjectCard)
                assertEquals(ReviewKindSource.PROJECT, follows.reviewKindSource)
                titles = openMenu()
                assertTrue(titles.toString(), "Make project card" in titles)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
            }
        } finally {
            pdf.delete()
            root.deleteRecursively()
        }
    }
}
