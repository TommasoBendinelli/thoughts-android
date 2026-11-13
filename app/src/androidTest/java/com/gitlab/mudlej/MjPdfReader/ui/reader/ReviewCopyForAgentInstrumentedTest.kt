// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.ListView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
 * The review card ⋮ menu's "Copy for agent" leaves the answer out and uses the template saved in
 * Settings > Agent notes and prompts; there is no "Copy for physical studying" any more (study mode's prompt).
 */
@RunWith(AndroidJUnit4::class)
class ReviewCopyForAgentInstrumentedTest {
    @Test fun copyForAgentUsesTheSavedTemplateWithoutTheAnswer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val shared = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = listOf(Preferences.agentPromptTemplateKey, Preferences.mentraPhotoFolderKey)
        val saved = keys.associateWith { shared.getString(it, null) }
        shared.edit().apply { keys.forEach(::remove) }.commit()
        val preferences = Preferences(shared)
        val stem = "review-copy-agent-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "cards"))
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                val paint = Paint().apply { textSize = 18f }
                page.canvas.drawText("Question area above the answer", 45f, 65f, paint)
                page.canvas.drawText("Secret answer phrase", 45f, 175f, paint)
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
                .joinToString("") { "%02x".format(it) }
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore()
                .registerImport("$stem.pdf", hash, pdf)
            val id = UUID.randomUUID().toString()
            val question = "Which phrase is the answer?"
            val record = JsonObject().apply {
                addProperty("schema_version", 3)
                addProperty("card_type", "visual_region")
                addProperty("card_id", id)
                addProperty("status", "approved")
                addProperty("revision", 1)
                addProperty("origin_id", "test-server")
                add("primary", JsonObject().apply {
                    addProperty("pdf_stem", stem)
                    addProperty("pdf_sha256", hash)
                    addProperty("document_id", documentId)
                    addProperty("page", 1)
                    addProperty("page_visual_sha256", hash)
                    addProperty("question", question)
                    add("rect", JsonArray().apply { listOf(.05f, .17f, .9f, .25f).forEach { add(it) } })
                })
                add("reanchor", JsonObject().apply {
                    addProperty("state", "succeeded")
                    addProperty("strategy", "source_markers_v2")
                })
            }
            File(root, "incoming/cards/server/$id.json").apply {
                parentFile!!.mkdirs()
                writeText(record.toString())
            }
            val card: ReviewCard = checkNotNull(store.card(id))
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
                fun menuTitles(view: View): List<String> = when {
                    view is ListView -> (0 until view.count).mapNotNull { (view.adapter.getItem(it) as? MenuItem)?.title?.toString() }
                    view is ViewGroup -> (0 until view.childCount).flatMap { menuTitles(view.getChildAt(it)) }
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
                fun clipboard(): String {
                    var text = ""
                    scenario.onActivity {
                        text = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    }
                    return text
                }
                fun copy(label: String): String {
                    scenario.onActivity {
                        it.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("test", "sentinel"))
                    }
                    scenario.onActivity { binding.reviewOptionsButton.performClick() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertTrue("$label is in the rendered options menu",
                            WindowInspector.getGlobalWindowViews().asReversed().any { clickMenu(it, label) })
                    }
                    awaitUi("$label copies") { clipboard() != "sentinel" }
                    SystemClock.sleep(400)
                    instrumentation.waitForIdleSync()
                    return clipboard()
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

                // The menu lists Copy for agent; physical studying moved to study mode's prompt.
                scenario.onActivity { binding.reviewOptionsButton.performClick() }
                instrumentation.waitForIdleSync()
                var titles = emptyList<String>()
                scenario.onActivity { titles = WindowInspector.getGlobalWindowViews().asReversed().flatMap(::menuTitles) }
                val agentLabel = context.getString(R.string.copy_document_for_agent)
                assertTrue(titles.toString(), agentLabel in titles)
                assertFalse(titles.toString(), "Copy for physical studying" in titles)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                SystemClock.sleep(400)
                instrumentation.waitForIdleSync()

                val agent = copy(agentLabel)
                assertTrue(agent, agent.startsWith("Thoughts document: "))
                assertTrue(agent, agent.contains("\nBuilt PDF: current_pdfs/$stem.pdf  (1 pages)\n"))
                assertTrue(agent, agent.contains("\nReview card: $id\nQuestion: \"$question\"\nAnswer pages: 1\n"))
                assertTrue(agent, agent.endsWith("\nAnswer pages: 1\n"))
                assertFalse(agent, agent.contains("I'm studying this card"))
                assertFalse(agent, agent.contains("Secret answer"))
                assertFalse(agent, agent.contains("Answer text"))


                // The saved templates; {answer_text} reads the answer from the card's PDF regions.
                preferences.setAgentPromptTemplate(AgentPromptTemplates.Kind.AGENT, "Card {card_id}\nAnswer:\n{answer_text}\n")
                assertEquals("Card $id\nAnswer:\nSecret answer phrase\n", copy(agentLabel))
            }
        } finally {
            shared.edit().apply {
                saved.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }.commit()
            pdf.delete()
            root.deleteRecursively()
        }
    }
}
