// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewToolbarInstrumentedTest {
    @Test fun hideShowPreservesRevealStateAndEndReturnsToReading() {
        checkReviewToolbar(markupOnly = false)
    }

    @Test fun reviewMarkupDefaultsAndSessionChoices() {
        checkReviewToolbar(markupOnly = true)
    }

    private fun checkReviewToolbar(markupOnly: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val markupPreferences = context.getSharedPreferences("pdf_highlights", android.content.Context.MODE_PRIVATE)
        markupPreferences.edit().putBoolean("comments", false).putBoolean("agent_edits", false)
            .putBoolean("review", false).commit()
        // Keep the unrelated first-use dialog out of this isolated reader check.
        File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1").apply {
            parentFile!!.mkdirs()
            writeText("shown\n")
        }
        val runId = InstrumentationRegistry.getArguments().getString("isolation_run_id")!!
        val pdf = File(context.cacheDir, "review-toolbar-$runId.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            val paint = Paint().apply { textSize = 20f }
            page.canvas.drawText("Review toolbar check", 40f, 70f, paint)
            page.canvas.drawText("Workers average gradients using all-reduce.", 40f, 550f, paint)
            document.finishPage(page)
            for (number in 2..3) {
                val extra = document.startPage(PdfDocument.PageInfo.Builder(600, 800, number).create())
                extra.canvas.drawText("Reading away from the answer: page $number", 40f, 70f, paint)
                document.finishPage(extra)
            }
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val card = ReviewCard("toolbar-$runId", "approved", "toolbar-$runId", "a".repeat(64),
            1, "b".repeat(64), "How do workers synchronize gradients?",
            RectF(0.05f, 0.65f, 0.95f, 0.72f), null, "")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var controller: ReviewSessionController
            lateinit var toolbar: View
            var loaded = false
            val deadline = SystemClock.uptimeMillis() + 20000
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    binding = ActivityMainBinding.bind(activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
                    loaded = binding.pdfView.pageCount > 0
                    toolbar = activity.supportActionBar!!.customView
                    controller = MainActivity::class.java.getDeclaredField("reviewSessionController")
                        .apply { isAccessible = true }.get(activity) as ReviewSessionController
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("Fixture PDF loaded", loaded)
            fun showCard() {
                ReviewSessionController::class.java.getDeclaredMethod("showCard", ReviewCard::class.java,
                    ReviewInterval::class.java, Map::class.java)
                    .apply { isAccessible = true }.invoke(controller, card,
                        ReviewInterval.suggested(null, null), emptyMap<String, Int>())
            }
            fun maskPage() = binding.pdfView.javaClass.getDeclaredField("reviewMaskPage")
                .apply { isAccessible = true }.getInt(binding.pdfView)
            // A revealed answer stays framed: the masks are kept and drawn as outlines.
            fun framed() = binding.pdfView.javaClass.getDeclaredField("reviewMaskRevealed")
                .apply { isAccessible = true }.getBoolean(binding.pdfView)
            fun toggle() = toolbar.findViewById<View>(R.id.reviewVisibilityButton).performClick()
            fun label() = toolbar.findViewById<TextView>(R.id.reviewVisibilityButton).text.toString()
            fun capture(name: String) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), name).outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            scenario.onActivity { activity ->
                assertEquals(View.GONE, toolbar.findViewById<View>(R.id.reviewToolbarActions).visibility)
                assertEquals("Visible markup", activity.getString(R.string.pdf_highlights))
                showCard()
                assertTrue(controller.isActive)
                assertEquals("Hide review", label())
                assertTrue(markupPreferences.getBoolean("comments", false))
                assertTrue(markupPreferences.getBoolean("agent_edits", false))
                assertFalse(markupPreferences.getBoolean("review", true))
                fun visible(field: String) = binding.pdfView.javaClass.getDeclaredField(field)
                    .apply { isAccessible = true }.getBoolean(binding.pdfView)
                assertTrue(visible("highlightComments"))
                assertTrue(visible("highlightAgentEdits"))
                // A choice made during review survives another card and activity recreation.
                markupPreferences.edit().putBoolean("comments", false).putBoolean("agent_edits", false).commit()
                MainActivity::class.java.getDeclaredMethod("applyHighlightOptions")
                    .apply { isAccessible = true }.invoke(activity)
                showCard()
                assertFalse(visible("highlightComments"))
                assertFalse(visible("highlightAgentEdits"))
                val saved = Bundle()
                controller.saveState(saved)
                controller.onDocumentReset()
                controller.restoreState(saved)
                showCard()
                assertFalse(markupPreferences.getBoolean("comments", true))
                assertFalse(markupPreferences.getBoolean("agent_edits", true))
                controller.beginReviewSession()
                showCard()
                assertTrue(visible("highlightComments"))
                assertTrue(visible("highlightAgentEdits"))
            }
            if (markupOnly) return@use
            capture("review-toolbar-visible.png")
            scenario.onActivity {
                binding.pdfView.jumpTo(2, false)
                binding.reviewOptionsButton.performClick()
            }
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            instrumentation.waitForIdleSync()
            val focusItem = automation.windows.flatMap {
                it.root?.findAccessibilityNodeInfosByText("Focus").orEmpty()
            }.first { it.text?.toString() == "Focus" }
            var clickable = focusItem
            while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
            assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            capture("review-toolbar-focused-mask.png")
            scenario.onActivity {
                assertEquals(0, binding.pdfView.currentPage)
                assertEquals(0, maskPage())
                val rect = binding.pdfView.javaClass.getDeclaredMethod("normalizedTopLeftRectToDocument",
                    Int::class.javaPrimitiveType, RectF::class.java).apply { isAccessible = true }
                    .invoke(binding.pdfView, 0, card.rect) as RectF
                val pdfLocation = IntArray(2)
                val cardLocation = IntArray(2)
                binding.pdfView.getLocationInWindow(pdfLocation)
                binding.reviewCard.getLocationInWindow(cardLocation)
                val visibleBottom = cardLocation[1] - pdfLocation[1] - 12 * context.resources.displayMetrics.density
                assertEquals("Mask is centred above the review card", visibleBottom / 2,
                    rect.centerY() + binding.pdfView.currentYOffset, 2f)
            }
            scenario.onActivity {
                val actions = toolbar.findViewById<View>(R.id.reviewToolbarActions)
                assertTrue("Review actions fit in the toolbar", actions.width > 0 && actions.width <= toolbar.width)
                for (id in listOf(R.id.reviewVisibilityButton)) {
                    val button = toolbar.findViewById<TextView>(id)
                    val available = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
                    assertTrue("${button.text} fits without truncation", button.paint.measureText(button.text.toString()) <= available)
                    assertTrue("${button.text} has a 48dp touch target", button.height >= 48 * context.resources.displayMetrics.density - 1)
                }
                toggle()
                assertEquals("Focus", label())
                assertEquals(View.GONE, binding.reviewCard.visibility)
                assertEquals(-1, maskPage())
            }
            capture("review-toolbar-hidden.png")
            scenario.onActivity { activity ->
                val state = Bundle()
                controller.saveState(state)
                controller.onDocumentReset()
                controller.restoreState(state)
                showCard()
                assertEquals("Focus", label())
                assertEquals(View.GONE, binding.reviewCard.visibility)
                binding.pdfView.jumpTo(2, false)
                toggle()
                assertEquals(View.VISIBLE, binding.reviewCard.visibility)
                assertEquals(card.question, binding.reviewQuestion.text.toString())
                assertEquals(0, maskPage())
                assertFalse(framed())
                assertEquals(View.VISIBLE, binding.reviewRevealButton.visibility)
                binding.reviewRevealButton.performClick()
                toggle()
                toggle()
                assertEquals(0, maskPage())
                assertTrue(framed())
                assertEquals(View.VISIBLE, binding.reviewRatingScroll.visibility)
                assertEquals(View.GONE, binding.reviewRevealButton.visibility)
                toggle()
                val revealedState = Bundle()
                controller.saveState(revealedState)
                controller.onDocumentReset()
                controller.restoreState(revealedState)
                showCard()
                toggle()
                assertEquals(0, maskPage())
                assertTrue(framed())
                assertEquals(View.VISIBLE, binding.reviewRatingScroll.visibility)
                toggle()
            }
            capture("review-toolbar-focused-revealed.png")
            scenario.onActivity { activity ->
                // Focus restores the card without hiding an already revealed answer.
                binding.pdfView.jumpTo(2, false)
                toggle()
            }
            capture("review-toolbar-refocused.png")
            scenario.onActivity { activity ->
                assertEquals(0, binding.pdfView.currentPage)
                assertEquals(View.VISIBLE, binding.reviewCard.visibility)
                assertEquals(0, maskPage())
                assertTrue(framed())
                assertEquals(View.VISIBLE, binding.reviewRatingScroll.visibility)
                activity.intent.putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, card.id)
                binding.reviewOptionsButton.performClick()
            }
            instrumentation.waitForIdleSync()
            val endReviewItem = instrumentation.uiAutomation.rootInActiveWindow
                .findAccessibilityNodeInfosByText(context.getString(R.string.review_action_end))
                .firstOrNull() ?: error("End review remains available in the three-dot menu")
            var clickableItem = endReviewItem
            while (!clickableItem.isClickable && clickableItem.parent != null) {
                clickableItem = clickableItem.parent
            }
            assertTrue("End review menu item is clickable", clickableItem.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertFalse(controller.isActive)
                assertEquals(View.GONE, toolbar.findViewById<View>(R.id.reviewToolbarActions).visibility)
                assertEquals(View.GONE, binding.reviewCard.visibility)
                assertEquals(-1, maskPage())
            }
            scenario.onActivity { activity ->
                assertFalse(activity.intent.hasExtra(HomeActivity.EXTRA_REVIEW_CARD_ID))
                assertTrue("PDF remains open after ending review", binding.pdfView.pageCount > 0)
                showCard()
                assertTrue(markupPreferences.getBoolean("comments", false))
                assertTrue(markupPreferences.getBoolean("agent_edits", false))
                controller.onDocumentReset()
            }
            capture("review-toolbar-ended.png")
        }
    }
}
