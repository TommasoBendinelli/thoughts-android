// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewCard
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewInterval
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Edit card during a review: every area is a box, Add area / Remove area, ‹ › across pages, one Save. */
@RunWith(AndroidJUnit4::class)
class ReviewCardAreasEditingInstrumentedTest {
    @Test fun addMoveRemoveAreasOnSeveralPagesAndSave() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "edit-areas-${UUID.randomUUID()}").apply { mkdirs() }
        // Keep the one-time background-sync prompt out of the screenshots.
        com.gitlab.mudlej.MjPdfReader.ui.sync.SyncBatteryPromptState(
            File(context.noBackupFilesDir, "thoughts-sync/battery-prompt-seen-v1")).markShown()
        val pdf = File(root, "edit-areas-ui-check.pdf")
        PdfDocument().let { document ->
            try {
                for (number in 1..3) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, number).create())
                    val paint = Paint().apply { textSize = 18f }
                    page.canvas.drawText("Page $number", 45f, 65f, paint)
                    page.canvas.drawText("Workers average gradients using all-reduce.", 45f, 175f, paint)
                    page.canvas.drawText("Parameter servers hold the shared weights.", 45f, 600f, paint)
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally { document.close() }
        }
        val store = ReviewStore(null, File(root, "review"), File(root, "incoming"),
            File(root, "events"), File(root, "outgoing-cards"))
        val cardId = UUID.randomUUID().toString()
        File(root, "review/cards").mkdirs()
        File(root, "review/cards/$cardId.json").writeText(
            """{"schema_version":4,"card_id":"$cardId","status":"approved","revision":3,"origin_id":"test",
               "primary":{"pdf_stem":"edit-areas","pdf_sha256":"${"a".repeat(64)}","page":1,
               "rect":[0.06,0.19,0.9,0.23],"regions":[{"page":1,"rect":[0.06,0.19,0.9,0.23]}],
               "page_visual_sha256":"${"b".repeat(64)}","question":"How are gradients combined?","source_kind":"pdf"}}""",
        )
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
                    loaded = binding.pdfView.pageCount == 3
                }
                if (!loaded) SystemClock.sleep(100)
            }
            assertTrue("PDF loaded", loaded)
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
            fun call(name: String, vararg args: Pair<Class<*>, Any>) = ReviewSessionController::class.java
                .getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
                .apply { isAccessible = true }.invoke(controller, *args.map { it.second }.toTypedArray())
            fun boxes() = binding.pdfView.reviewRegionSelections.orEmpty()
            fun editing() = ReviewSessionController::class.java.getDeclaredField("editingRegion")
                .apply { isAccessible = true }.getBoolean(controller)
            fun awaitSaved() {
                val until = SystemClock.uptimeMillis() + 10000
                while (SystemClock.uptimeMillis() < until) {
                    var done = false
                    scenario.onActivity { done = !editing() }
                    if (done) return
                    SystemClock.sleep(100)
                }
                throw AssertionError("Save did not finish")
            }
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                controller = ReviewSessionController(activity, binding,
                    DocumentState(uri = Uri.fromFile(pdf), annotationPdfSha256 = "a".repeat(64)), store)
                val card = checkNotNull(store.card(cardId))
                call("showCard", ReviewCard::class.java to card, ReviewInterval::class.java to ReviewInterval.suggested(null, null),
                    Map::class.java to emptyMap<String, Int>())
                call("editRegion", ReviewCard::class.java to card)
                assertTrue(editing())
                assertEquals(1, boxes().size)
                assertEquals(View.VISIBLE, binding.reviewAreaActions.visibility)
                assertTrue(binding.reviewAddAreaButton.isEnabled)
                // The only area cannot be removed.
                assertFalse(binding.reviewRemoveAreaButton.isEnabled)
                assertEquals(View.GONE, binding.reviewNextAnswerButton.visibility)
            }
            capture("edit-areas-1-single.png")
            // Look at page 2 and add an area there.
            scenario.onActivity { binding.pdfView.jumpTo(1, false) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            scenario.onActivity {
                binding.reviewAddAreaButton.performClick()
                assertEquals(2, boxes().size)
                assertEquals(1, boxes()[1].pageIndex)
                assertEquals(1, binding.pdfView.selectedReviewRegionIndex)
                assertTrue(binding.reviewRemoveAreaButton.isEnabled)
                assertEquals(View.VISIBLE, binding.reviewNextAnswerButton.visibility)
                assertEquals("Area 2 of 2 · page 2", binding.reviewProgress.text.toString())
            }
            capture("edit-areas-2-added-page2.png")
            scenario.onActivity {
                // ‹ goes back to the first area, on page 1.
                binding.reviewPreviousAnswerButton.performClick()
                assertEquals(0, binding.pdfView.selectedReviewRegionIndex)
                assertEquals("Area 1 of 2 · page 1", binding.reviewProgress.text.toString())
                // Add a third area and remove it again.
                binding.reviewAddAreaButton.performClick()
                assertEquals(3, boxes().size)
                binding.reviewRemoveAreaButton.performClick()
                assertEquals(2, boxes().size)
                binding.reviewRegionSaveButton.performClick()
            }
            awaitSaved()
            scenario.onActivity {
                val saved = checkNotNull(store.card(cardId))
                assertEquals(4, saved.revision)
                assertEquals(listOf(1, 2), saved.regions.map { it.page })
                assertEquals(0.06f, saved.regions[0].rect.left, 1e-4f)
                // The review masks follow the saved areas.
                assertEquals(2, binding.pdfView.reviewMaskBlocks().size)
                assertEquals(View.GONE, binding.reviewAreaActions.visibility)
                assertTrue(File(root, "outgoing-cards").walkTopDown().any { it.isFile && it.extension == "json" })
            }
            capture("edit-areas-3-saved.png")
            // Remove the page-1 area: the card moves to page 2, and its last area is protected.
            scenario.onActivity {
                call("editRegion", ReviewCard::class.java to checkNotNull(store.card(cardId)))
                assertEquals(2, boxes().size)
                assertTrue(binding.pdfView.selectReviewRegion(0))
                binding.reviewRemoveAreaButton.performClick()
                assertEquals(1, boxes().size)
                assertFalse(binding.reviewRemoveAreaButton.isEnabled)
                binding.reviewRemoveAreaButton.performClick()
                assertEquals(1, boxes().size)
                binding.reviewRegionSaveButton.performClick()
            }
            awaitSaved()
            scenario.onActivity {
                val saved = checkNotNull(store.card(cardId))
                assertEquals(2, saved.page)
                assertEquals(listOf(2), saved.regions.map { it.page })
                assertNotNull(saved.masksByPage[1])
            }
        }
        root.deleteRecursively()
    }
}
