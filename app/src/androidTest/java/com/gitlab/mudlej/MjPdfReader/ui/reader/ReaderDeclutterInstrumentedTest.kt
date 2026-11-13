// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.DocumentFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCardFeedback
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reader during a review with study mode on keeps room for the page: the review card is compact
 * (the question in two lines, its details folded; the arrow or a tap on the question shows them, and
 * the choice is kept), the agent bar is folded into its Agent button until opened, and a selection
 * that takes feedback offers one row (the feedback, Copy and ⋯) until ⋯ shows the rest. Screens are
 * saved to /data/local/tmp/reader-declutter-*.png for review.
 */
@RunWith(AndroidJUnit4::class)
class ReaderDeclutterInstrumentedTest {
    @Test fun reviewWithStudyModeLeavesRoomForThePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val previousStudyMode = preferences.getBoolean(STUDY_MODE_KEY, false)
        preferences.edit().putBoolean(STUDY_MODE_KEY, true).remove(DETAILS_KEY).commit()
        val stem = "reader-declutter-${UUID.randomUUID()}"
        val root = File(context.cacheDir, stem).apply { mkdirs() }
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "cards"))
        val previousFeedbackFile = DocumentFeedback.file
        val previousCardFeedbackFile = ReviewCardFeedback.file
        DocumentFeedback.file = { File(root, "document-feedback.json") }
        ReviewCardFeedback.file = { File(root, "card-feedback.json") }
        val paint = Paint().apply { textSize = 18f; isAntiAlias = true }
        val firstLine = "The KL term keeps the policy close"
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawText("Question area above the answer", 45f, 65f, paint)
                page.canvas.drawText(firstLine, 45f, 165f, paint)
                page.canvas.drawText("to the reference model at all times.", 45f, 195f, paint)
                document.finishPage(page)
                pdf.parentFile!!.mkdirs()
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
            val hash = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
                .joinToString("") { "%02x".format(it) }
            val documentId = com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore()
                .registerImport("$stem.pdf", hash, pdf)
            val id = UUID.randomUUID().toString()
            val question = "How does a one-forward-one-backward pipeline schedule reduce cached activation memory, " +
                "and what happens to the pipeline bubble when the micro-batches grow?"
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
                        addProperty("document_id", documentId)
                        addProperty("page", 1)
                        addProperty("page_visual_sha256", hash)
                        addProperty("question", question)
                        add("rect", JsonArray().apply { listOf(.05f, .17f, .9f, .27f).forEach { add(it) } })
                    })
                    add("reanchor", JsonObject().apply {
                        addProperty("state", "succeeded")
                        addProperty("strategy", "source_markers_v2")
                    })
                }.toString())
            }
            val card: ReviewCard = checkNotNull(store.card(id))
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(Uri.fromFile(pdf), "application/pdf")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var binding: ActivityMainBinding
                lateinit var controller: ReviewSessionController
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
                fun screenshot(name: String) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/reader-declutter-$name.png").close()
                    SystemClock.sleep(700)
                }
                val bar = binding.studyPageBar

                awaitUi("PDF loaded") { binding.pdfView.pageCount > 0 }
                // Outside a review the agent bar is shown in full, as before.
                awaitUi("Full agent bar while reading") { bar.root.isShown && bar.studyPageFull.isShown && !bar.studyPageCollapsedRow.isShown }
                scenario.onActivity { activity ->
                    controller = ReviewSessionController(activity, binding,
                        DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = hash, documentId = documentId), store)
                    MainActivity::class.java.getDeclaredField("reviewSessionController")
                        .apply { isAccessible = true }.set(activity, controller)
                    ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                        ReviewInterval::class.java, Map::class.java).apply { isAccessible = true }
                        .invoke(controller, card, ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                }
                awaitUi("Card shown") { binding.reviewQuestion.text.toString() == question }
                scenario.onActivity { binding.reviewRevealButton.performClick() }

                // Compact card: the question in two lines, the details folded away.
                awaitUi("Compact card") { binding.reviewRatingScroll.isShown && !binding.reviewDetails.isShown }
                scenario.onActivity {
                    assertEquals(2, binding.reviewQuestion.maxLines)
                    assertEquals(View.GONE, binding.reviewCurrentDetails.visibility)
                    assertTrue(binding.reviewDetailsToggle.isShown)
                    // The rating row is still there to answer.
                    assertTrue(binding.reviewNextButton.isShown)
                }
                // The agent bar is folded into its button above the card.
                awaitUi("Agent bar folded") { bar.studyPageCollapsedRow.isShown && !bar.studyPageFull.isShown }
                screenshot("1-compact")

                // The arrow shows the details and the whole question; the choice is kept.
                scenario.onActivity { assertTrue(binding.reviewDetailsToggle.performClick()) }
                awaitUi("Details shown") { binding.reviewDetails.isShown && binding.reviewQuestion.maxLines == Int.MAX_VALUE }
                assertTrue(PreferenceManager.getDefaultSharedPreferences(context).getBoolean(DETAILS_KEY, false))
                screenshot("2-details")
                // A tap on the question folds them again.
                scenario.onActivity { assertTrue(binding.reviewQuestion.performClick()) }
                awaitUi("Compact again") { !binding.reviewDetails.isShown && binding.reviewQuestion.maxLines == 2 }

                // The Agent button opens the full bar; its arrow folds it back.
                scenario.onActivity { assertTrue(bar.studyPageExpand.performClick()) }
                awaitUi("Agent bar opened") { bar.studyPageFull.isShown && bar.studyPageCollapse.isShown && !bar.studyPageCollapsedRow.isShown }
                screenshot("3-agent-bar-open")
                scenario.onActivity { assertTrue(bar.studyPageCollapse.performClick()) }
                awaitUi("Agent bar folded again") { bar.studyPageCollapsedRow.isShown && !bar.studyPageFull.isShown }

                // A selection in the answer: one row (feedback, Copy, ⋯); ⋯ shows the colours and the rest.
                val region = RectF(45f / 600, (165f + paint.ascent()) / 800, (45f + paint.measureText(firstLine)) / 600,
                    (165f + paint.descent()) / 800)
                scenario.onActivity { assertTrue(binding.pdfView.startTextSelectionCovering(listOf(0), listOf(region))) }
                awaitUi("One-row selection menu") { binding.textSelectionActionCard.isShown && binding.textSelectionMoreButton.isShown }
                scenario.onActivity {
                    assertTrue(binding.textSelectionFeedbackLikeButton.isShown)
                    assertTrue(binding.textSelectionCopyButton.isShown)
                    listOf(binding.textSelectionStrikethroughButton, binding.textSelectionShareButton, binding.textSelectionNoteButton,
                        binding.textSelectionReadAloudButton).forEach { assertEquals(View.GONE, it.visibility) }
                    assertTrue(binding.textSelectionSwatchFlow.referencedIds.all {
                        binding.textSelectionActionContent.findViewById<View>(it).visibility == View.GONE })
                }
                screenshot("4-selection-row")
                scenario.onActivity { assertTrue(binding.textSelectionMoreButton.performClick()) }
                awaitUi("Full selection menu") { binding.textSelectionStrikethroughButton.isShown && !binding.textSelectionMoreButton.isShown }
                scenario.onActivity {
                    assertTrue(binding.textSelectionSwatchFlow.referencedIds.all {
                        binding.textSelectionActionContent.findViewById<View>(it).visibility == View.VISIBLE })
                }
                screenshot("5-selection-full")
                // The next selection starts with one row again.
                scenario.onActivity { binding.pdfView.clearTextSelection() }
                awaitUi("Menu closed") { !binding.textSelectionActionCard.isShown }
                scenario.onActivity { assertTrue(binding.pdfView.startTextSelectionCovering(listOf(0), listOf(region))) }
                awaitUi("One row again") { binding.textSelectionMoreButton.isShown && !binding.textSelectionStrikethroughButton.isShown }
                scenario.onActivity { binding.pdfView.clearTextSelection() }

                // After the review the agent bar is shown in full again.
                scenario.onActivity {
                    ReviewSessionController::class.java.getDeclaredMethod("endReview").apply { isAccessible = true }.invoke(controller)
                }
                awaitUi("Full agent bar after the review") { bar.studyPageFull.isShown && !bar.studyPageCollapsedRow.isShown }
            }
        } finally {
            preferences.edit().putBoolean(STUDY_MODE_KEY, previousStudyMode).remove(DETAILS_KEY).commit()
            DocumentFeedback.file = previousFeedbackFile
            ReviewCardFeedback.file = previousCardFeedbackFile
            pdf.delete()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val STUDY_MODE_KEY = "agent_notes_study_mode"
        const val DETAILS_KEY = "review_card_details_shown"
    }
}
