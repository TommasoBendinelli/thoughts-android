// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.app.KeyguardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.WindowManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewSessionEditingInstrumentedTest {
    @Test fun revealSetKnowledgeNavigationAndCombinedEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Unlock the test device before running the review UI check",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val pdf = File(context.cacheDir, "review-session-ui-check.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            val paint = Paint().apply { textSize = 18f }
            page.canvas.drawText("Review practice", 45f, 65f, paint)
            page.canvas.drawText("How does distributed training synchronize gradients?", 45f, 125f, paint)
            page.canvas.drawText("Workers average gradients using all-reduce.", 45f, 175f, paint)
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var controller: ReviewSessionController
            var loaded = false
            val deadline = SystemClock.uptimeMillis() + 15000
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    binding = ActivityMainBinding.bind(activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                    loaded = binding.pdfView.pageCount > 0
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("PDF loaded", loaded)
            val first = ReviewCard("ui-first", "approved", "ui-check", "a".repeat(64), 1,
                "b".repeat(64), "How does distributed training synchronize gradients?",
                RectF(0.06f, 0.18f, 0.9f, 0.24f), null, "",
                sourceKind = "pdf",
                answerRegions = listOf(com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion(
                    1, RectF(0.06f, 0.18f, 0.9f, 0.24f))))
            // Two answer areas far apart on the page.
            val second = first.copy(id = "ui-second", question = "Second question",
                answerRegions = listOf(
                    com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion(1, RectF(0.06f, 0.18f, 0.9f, 0.24f)),
                    com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion(1, RectF(0.06f, 0.72f, 0.9f, 0.78f))))
            fun show(card: ReviewCard) {
                ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java, com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval::class.java, Map::class.java)
                    .apply { isAccessible = true }.invoke(controller, card, com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval.suggested(null, null), emptyMap<String, Int>())
            }
            fun capture(name: String) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1200)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), name).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller = ReviewSessionController(activity, binding, DocumentState(uri = Uri.fromFile(pdf)))
                show(first)
                assertEquals(View.VISIBLE, binding.reviewSetKnowledgeButton.visibility)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { binding.reviewOptionsButton.performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(500, 5000)
            fun menuItems(text: String) = instrumentation.uiAutomation.windows.flatMap {
                it.root?.findAccessibilityNodeInfosByText(text).orEmpty()
            }
            capture("review-menu.png")
            assertTrue(menuItems("Edit").any { it.text?.toString() == "Edit" })
            assertTrue(menuItems("Undo").isNotEmpty())
            // The note is edited in Edit card, next to the question; there is no separate menu item.
            assertTrue(menuItems("Add note").isEmpty() && menuItems("Edit note").isEmpty())
            assertTrue(menuItems("Hide again").isEmpty())
            assertTrue(menuItems("Edit answer box").isEmpty())
            var editItem = menuItems("Edit").first { it.text?.toString() == "Edit" }
            while (!editItem.isClickable && editItem.parent != null) editItem = editItem.parent
            assertTrue(editItem.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(500, 5000)
            scenario.onActivity {
                assertEquals(View.GONE, binding.reviewQuestionEditLayout.visibility)
                assertEquals(View.GONE, binding.reviewNoteEditLayout.visibility)
                assertNotNull(binding.pdfView.getReviewRegionSelection())
                binding.reviewQuestionToggleButton.performClick()
                assertEquals(View.VISIBLE, binding.reviewQuestionEditLayout.visibility)
                assertEquals(View.VISIBLE, binding.reviewNoteEditLayout.visibility)
                assertEquals(first.note, binding.reviewNoteEdit.text.toString())
                binding.reviewQuestionEdit.setText("")
                binding.reviewRegionSaveButton.performClick()
                assertNotNull(binding.reviewQuestionEditLayout.error)
                binding.reviewQuestionEdit.setText("Changed but cancelled")
                assertNull(binding.reviewQuestionEditLayout.error)
            }
            capture("review-unified-editor.png")
            scenario.onActivity {
                binding.reviewRegionCancelButton.performClick()
                assertEquals(first.question, binding.reviewQuestion.text.toString())
            }
            scenario.onActivity {
                // Reveal frames the answer and becomes Hide; the buttons stay. Set knowledge adds the bar.
                binding.reviewFlipButton.performClick()
                assertEquals("Unflip", binding.reviewFlipButton.text.toString())
                binding.reviewFlipButton.performClick()
                assertEquals("Flip", binding.reviewFlipButton.text.toString())
                binding.reviewRevealButton.performClick()
                assertEquals("Hide", binding.reviewRevealButton.text.toString())
                assertEquals(View.VISIBLE, binding.reviewFlipButton.visibility)
                assertFalse(binding.reviewFlipButton.isEnabled)
                assertEquals(View.GONE, binding.reviewRatingScroll.visibility)
                binding.reviewRevealButton.performClick()
                assertEquals("Reveal", binding.reviewRevealButton.text.toString())
                assertTrue(binding.reviewFlipButton.isEnabled)
                binding.reviewRevealButton.performClick()
                assertEquals(View.VISIBLE, binding.reviewSetKnowledgeButton.visibility)
                binding.reviewSetKnowledgeButton.performClick()
                assertEquals(View.VISIBLE, binding.reviewRatingScroll.visibility)
                assertEquals(View.VISIBLE, binding.reviewCurrentState.visibility)
                assertEquals(View.GONE, binding.reviewSetKnowledgeButton.visibility)
            }
            capture("review-answer-comparison.png")
            scenario.onActivity { binding.reviewOptionsButton.performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(500, 5000)
            capture("review-rating-menu.png")
            var hideItem = menuItems("Hide again").first { it.text?.toString() == "Hide again" }
            while (!hideItem.isClickable && hideItem.parent != null) hideItem = hideItem.parent
            assertTrue(hideItem.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(first.question, binding.reviewQuestion.text.toString())
                assertEquals(View.VISIBLE, binding.reviewRevealButton.visibility)
                assertEquals(View.GONE, binding.reviewRatingScroll.visibility)
                assertFalse(binding.reviewIntervalButton.isEnabled)
                assertEquals(View.VISIBLE, binding.reviewSetKnowledgeButton.visibility)
                val maskPage = binding.pdfView.javaClass.getDeclaredField("reviewMaskPage")
                    .apply { isAccessible = true }.getInt(binding.pdfView)
                assertEquals(first.page - 1, maskPage)
            }
            capture("review-hidden-again.png")
            scenario.onActivity { binding.reviewOptionsButton.performClick() }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(500, 5000)
            assertTrue(menuItems("Hide again").isEmpty())
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                binding.reviewSetKnowledgeButton.performClick()
                assertEquals(View.VISIBLE, binding.reviewRatingScroll.visibility)
                assertEquals(View.GONE, binding.reviewRevealButton.visibility)
                // The answer stays framed where it was hidden.
                assertEquals(first.page - 1, binding.pdfView.javaClass.getDeclaredField("reviewMaskPage")
                    .apply { isAccessible = true }.getInt(binding.pdfView))
                assertTrue(binding.pdfView.javaClass.getDeclaredField("reviewMaskRevealed")
                    .apply { isAccessible = true }.getBoolean(binding.pdfView))
            }
            scenario.onActivity {
                fun areaIndex() = ReviewSessionController::class.java.getDeclaredField("answerAreaIndex")
                    .apply { isAccessible = true }.getInt(controller)
                assertEquals(View.GONE, binding.reviewNextAnswerButton.visibility)
                show(second)
                assertEquals(View.VISIBLE, binding.reviewPreviousAnswerButton.visibility)
                assertEquals(View.VISIBLE, binding.reviewNextAnswerButton.visibility)
                assertEquals(0, areaIndex())
                binding.reviewNextAnswerButton.performClick()
                assertEquals(1, areaIndex())
                binding.reviewNextAnswerButton.performClick()
                assertEquals(0, areaIndex())
                binding.reviewPreviousAnswerButton.performClick()
                assertEquals(1, areaIndex())
                show(first)
                assertEquals(View.GONE, binding.reviewNextAnswerButton.visibility)
            }
        }
        pdf.delete()
    }

    /** The note is edited in Edit card next to the question; tapping the note opens it there. */
    @Test fun noteIsEditedInEditCard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pdf = File(context.cacheDir, "review-session-note-check.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawText("Workers average gradients using all-reduce.", 45f, 175f, Paint().apply { textSize = 18f })
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            var loaded = false
            val deadline = SystemClock.uptimeMillis() + 15000
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    binding = ActivityMainBinding.bind(activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                    loaded = binding.pdfView.pageCount > 0
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("PDF loaded", loaded)
            val card = ReviewCard("ui-note", "approved", "ui-check", "a".repeat(64), 1,
                "b".repeat(64), "How does distributed training synchronize gradients?",
                RectF(0.06f, 0.18f, 0.9f, 0.24f), null, "",
                sourceKind = "pdf",
                answerRegions = listOf(com.gitlab.mudlej.MjPdfReader.data.review.ReviewAnswerRegion(
                    1, RectF(0.06f, 0.18f, 0.9f, 0.24f)))).copy(note = "Compare with SFT")
            scenario.onActivity { activity ->
                val controller = ReviewSessionController(activity, binding, DocumentState(uri = Uri.fromFile(pdf)))
                ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java, com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval::class.java, Map::class.java)
                    .apply { isAccessible = true }.invoke(controller, card, com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval.suggested(null, null), emptyMap<String, Int>())
                assertEquals(View.VISIBLE, binding.reviewNote.visibility)
                binding.reviewNote.performClick()
                // Edit card opens with the question and the note shown, the note filled in.
                assertNotNull(binding.pdfView.getReviewRegionSelection())
                assertEquals(View.GONE, binding.reviewNote.visibility)
                assertEquals(View.VISIBLE, binding.reviewQuestionEditLayout.visibility)
                assertEquals(View.VISIBLE, binding.reviewNoteEditLayout.visibility)
                assertEquals("Compare with SFT", binding.reviewNoteEdit.text.toString())
                assertEquals(card.question, binding.reviewQuestionEdit.text.toString())
                assertEquals("Hide question & note", binding.reviewQuestionToggleButton.text.toString())
                binding.reviewQuestionToggleButton.performClick()
                assertEquals(View.GONE, binding.reviewQuestionEditLayout.visibility)
                assertEquals(View.GONE, binding.reviewNoteEditLayout.visibility)
                assertEquals("Edit question & note", binding.reviewQuestionToggleButton.text.toString())
                binding.reviewNoteEdit.setText("Changed but cancelled")
                binding.reviewRegionCancelButton.performClick()
                assertEquals(View.VISIBLE, binding.reviewNote.visibility)
                assertEquals(View.GONE, binding.reviewNoteEditLayout.visibility)
                assertTrue(binding.reviewNote.text.toString().contains("Compare with SFT"))
            }
        }
        pdf.delete()
    }
}
