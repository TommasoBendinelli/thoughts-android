// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.pdf.PDF
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.ReaderHistoryManager
import com.gitlab.mudlej.MjPdfReader.ui.reader.navigation.SearchNavigationController
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfSearchNavigationInstrumentedTest {
    @Test fun searchesDisplayedPdfAndNavigatesIndividualMatchesWithoutOpeningResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Unlock the device for visual search verification",
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val displayed = File(context.cacheDir, "search-navigation-check.pdf")
        PdfDocument().let { document ->
            for (pageNumber in 1..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, pageNumber).create())
                val paint = Paint().apply { textSize = 20f }
                page.canvas.drawText("PDF search check", 45f, 65f, paint)
                page.canvas.drawText("Activation checkpointing saves memory.", 45f, 180f, paint)
                if (pageNumber == 1) {
                    page.canvas.drawText("Activation checkpointing repeats computation.", 45f, 500f, paint)
                }
                document.finishPage(page)
            }
            displayed.outputStream().use(document::writeTo)
            document.close()
        }
        val original = File(context.cacheDir, "search-original-check.pdf")
        PdfDocument().let { document ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawText("Original document with no matching words", 45f, 65f, Paint())
            document.finishPage(page)
            original.outputStream().use(document::writeTo)
            document.close()
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(displayed), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var controller: SearchNavigationController
            var launched: Intent? = null
            fun views(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
            fun waitFor(message: String, condition: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 15000
                var satisfied = false
                while (!satisfied && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { satisfied = condition() }
                    if (!satisfied) SystemClock.sleep(100)
                }
                assertTrue(message, satisfied)
            }
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
            }
            waitFor("PDF loaded") { binding.pdfView.pageCount == 2 }
            fun allViews() = views(binding.root.rootView)
            fun hasText(text: String) = allViews().filterIsInstance<TextView>().any { it.text.toString() == text }
            fun clickDescription(resource: Int) {
                scenario.onActivity { activity ->
                    val button = allViews().first { it.contentDescription == activity.getString(resource) }
                    assertTrue(button.isEnabled)
                    button.performClick()
                }
            }
            fun capture(name: String) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(700)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
                File(context.getExternalFilesDir(null), name).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            scenario.onActivity { activity ->
                val state = DocumentState(uri = Uri.fromFile(original), renderUri = Uri.fromFile(displayed), fileHash = "search-ui-check")
                controller = SearchNavigationController(activity, binding, state,
                    Preferences(PreferenceManager.getDefaultSharedPreferences(activity)),
                    ReaderHistoryManager({ binding.pdfView }, {}), { launched = it })
                controller.search("activation checkpointing")
            }
            waitFor("All three matches from the displayed PDF") { hasText("1/3") }
            assertNull("The results activity must not open automatically", launched)
            capture("pdf-search-first.png")
            fun activeBounds(): List<RectF> {
                val field = binding.pdfView.javaClass.getDeclaredField("activeSearchHighlightRects").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                return field.get(binding.pdfView) as List<RectF>
            }
            var firstTop = 0f
            scenario.onActivity {
                assertEquals("One clean highlight for the phrase", 1, activeBounds().size)
                firstTop = activeBounds().first().top
            }
            clickDescription(R.string.next_search_result)
            waitFor("Second occurrence on the same page") { hasText("2/3") }
            scenario.onActivity {
                assertEquals(0, binding.pdfView.currentPage)
                assertTrue("The active highlight moves to the lower occurrence", activeBounds().first().top < firstTop)
            }
            capture("pdf-search-second.png")
            clickDescription(R.string.next_search_result)
            waitFor("Third match on the next page") { hasText("3/3") && binding.pdfView.currentPage == 1 }
            capture("pdf-search-third.png")
            clickDescription(R.string.previous_search_result)
            waitFor("Previous returns to the second occurrence") { hasText("2/3") }
            scenario.onActivity { activity ->
                allViews().filterIsInstance<TextView>().first { it.text == activity.getString(R.string.results) }.performClick()
                assertEquals(Uri.fromFile(displayed).toString(), launched!!.getStringExtra(PDF.filePathKey))
                assertEquals(1, launched!!.getIntExtra(PDF.resultPositionInListKey, -1))
                allViews().first { it.id == com.google.android.material.R.id.snackbar_action }.performClick()
                assertFalse(controller.isActive)
                val rects = binding.pdfView.javaClass.getDeclaredField("activeSearchHighlightRects").apply { isAccessible = true }
                assertTrue((rects.get(binding.pdfView) as List<*>).isEmpty())
            }
            capture("pdf-search-done.png")
            scenario.onActivity { controller.search("no-such-phrase-9827") }
            waitFor("An empty search stays in the PDF") { hasText("No matches") }
            scenario.onActivity { controller.search("activation checkpointing") }
            waitFor("Cached search starts at the first occurrence") { hasText("1/3") }
            scenario.onActivity { controller.reset() }
        }
    }
}
