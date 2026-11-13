// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import android.view.InputDevice
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.databinding.PdfContentsSheetBinding
import com.gitlab.mudlej.MjPdfReader.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentsSheetInstrumentedTest {
    @Test fun contentsNavigateInsideReaderAndPreserveTheirState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "contents-sheet-fixture.pdf").apply { writeBytes(outlinePdf()) }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(file), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var reader: ReaderComposition
            fun attach() = scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                reader = MainActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }.get(activity) as ReaderComposition
            }
            fun waitFor(message: String, check: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + 15000
                var ok = false
                while (!ok && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { ok = check() }
                    if (!ok) SystemClock.sleep(100)
                }
                assertTrue(message, ok)
            }
            fun panelState() = BottomSheetBehavior.from(binding.commentSheetHost.getChildAt(0) as FrameLayout).state
            fun panel() = PdfContentsSheetBinding.bind((binding.commentSheetHost.getChildAt(0) as ViewGroup).getChildAt(0))
            fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
            fun capture(name: String) {
                instrumentation.waitForIdleSync()
                val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
                File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            fun swipeList(up: Boolean) {
                var x = 0f
                var startY = 0f
                var endY = 0f
                scenario.onActivity {
                    val list = panel().contentsList
                    val location = IntArray(2)
                    list.getLocationOnScreen(location)
                    x = location[0] + list.width * .5f
                    startY = location[1] + list.height * if (up) .85f else .15f
                    endY = location[1] + list.height * if (up) .15f else .85f
                }
                val start = SystemClock.uptimeMillis()
                for (step in 0..20) {
                    val action = when (step) {
                        0 -> MotionEvent.ACTION_DOWN
                        20 -> MotionEvent.ACTION_UP
                        else -> MotionEvent.ACTION_MOVE
                    }
                    val time = start + step * 20L
                    SystemClock.sleep((time - SystemClock.uptimeMillis()).coerceAtLeast(0))
                    val event = MotionEvent.obtain(start, time, action, x, startY + (endY - startY) * step / 20, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue("Swipe injected", instrumentation.uiAutomation.injectInputEvent(event, true))
                    event.recycle()
                }
                instrumentation.waitForIdleSync()
            }
            fun checkScrollingKeepsHeight() {
                var top = 0
                var originalOffset = 0
                scenario.onActivity {
                    top = binding.commentSheetHost.getChildAt(0).top
                    panel().contentsList.scrollToPosition(0)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { originalOffset = panel().contentsList.computeVerticalScrollOffset() }
                swipeList(up = true)
                scenario.onActivity {
                    assertEquals("Scrolling up keeps the panel height", top, binding.commentSheetHost.getChildAt(0).top)
                    assertTrue("The headings themselves scroll", panel().contentsList.computeVerticalScrollOffset() > originalOffset)
                    panel().contentsList.scrollToPosition(0)
                }
                instrumentation.waitForIdleSync()
                swipeList(up = false)
                scenario.onActivity {
                    assertEquals("Pulling down at the first heading keeps the panel open at the chosen height", top,
                        binding.commentSheetHost.getChildAt(0).top)
                }
            }
            attach()
            waitFor("Fixture loaded") { binding.pdfView.pageCount == 12 }
            scenario.onActivity { reader.readerNavigationController.showTableOfContents() }
            waitFor("Contents loaded in the reader") { binding.commentSheetHost.childCount == 1 && panel().contentsList.adapter!!.itemCount == 12 }
            waitFor("Half panel leaves the PDF visible") {
                val frame = binding.commentSheetHost.getChildAt(0)
                frame.top > binding.commentSheetHost.height / 3 && frame.top < binding.commentSheetHost.height * 2 / 3
            }
            capture("contents-half-open.png")
            checkScrollingKeepsHeight()
            scenario.onActivity {
                val frame = binding.commentSheetHost.getChildAt(0)
                assertTrue(binding.pdfView.isShown)
                assertTrue(frame.top > 0)
                val title = texts(panel().contentsList).first { it.text == "Chapter 3" }
                assertFalse("Compact titles are not bold", title.typeface.isBold)
                var row: View = title
                while (!row.isClickable) row = row.parent as View
                row.performClick()
            }
            waitFor("Heading navigation keeps contents open") { binding.pdfView.currentPage == 2 && binding.commentSheetHost.childCount == 1 }
            capture("contents-page-jump.png")
            scenario.onActivity {
                panel().contentsSearch.setText("Detail")
            }
            waitFor("Search includes the parent and matching children") { panel().contentsList.adapter!!.itemCount == 3 }
            scenario.onActivity { panel().contentsClose.performClick() }
            waitFor("Close removes only contents") { binding.commentSheetHost.childCount == 0 && binding.pdfView.currentPage == 2 }
            scenario.onActivity { reader.readerNavigationController.showTableOfContents() }
            waitFor("Search survives reopening") { panel().contentsSearch.text.toString() == "Detail" && panel().contentsList.adapter!!.itemCount == 3 }
            scenario.onActivity { panel().contentsLocate.performClick() }
            waitFor("Current section clears the filter") { panel().contentsSearch.text.isNullOrEmpty() && panel().contentsList.adapter!!.itemCount == 12 }
            scenario.onActivity {
                panel().contentsResize.performClick()
            }
            waitFor("Button expands the sheet") { panelState() == BottomSheetBehavior.STATE_EXPANDED }
            capture("contents-expanded.png")
            scenario.onActivity {
                assertEquals(context.getString(R.string.contents_collapse_panel), panel().contentsResize.contentDescription)
            }
            checkScrollingKeepsHeight()
            scenario.onActivity { panel().contentsResize.performClick() }
            waitFor("Button returns to half height") {
                panelState() == BottomSheetBehavior.STATE_HALF_EXPANDED &&
                    kotlin.math.abs(binding.commentSheetHost.getChildAt(0).top - binding.commentSheetHost.height / 2) <= 1
            }
            scenario.onActivity {
                assertEquals(context.getString(R.string.contents_expand_panel), panel().contentsResize.contentDescription)
            }
            scenario.onActivity { panel().contentsList.scrollToPosition(11) }
            waitFor("Last heading remains reachable in the short panel") {
                val list = panel().contentsList
                val last = list.findViewHolderForAdapterPosition(11)?.itemView
                last != null && last.bottom <= list.height
            }
            capture("contents-collapsed-last.png")
            scenario.onActivity {
                panel().contentsSearch.setText("Detail")
                panel().contentsResize.performClick()
            }
            waitFor("Filtered state ready for recreation") {
                panel().contentsList.adapter!!.itemCount == 3 && panelState() == BottomSheetBehavior.STATE_EXPANDED
            }
            scenario.recreate()
            attach()
            waitFor("The open panel and query survive recreation") {
                binding.commentSheetHost.childCount == 1 && panel().contentsSearch.text.toString() == "Detail" &&
                    panel().contentsList.adapter!!.itemCount == 3 &&
                    binding.commentSheetHost.getChildAt(0).top < binding.commentSheetHost.height / 3 &&
                    panel().contentsResize.contentDescription == context.getString(R.string.contents_collapse_panel)
            }
            scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            waitFor("Back closes the panel without leaving the PDF") { binding.commentSheetHost.childCount == 0 && binding.pdfView.pageCount == 12 }
        }
    }

    private fun outlinePdf(): ByteArray {
        val objects = mutableListOf<String>()
        fun obj(body: String): Int { objects.add(body); return objects.size }
        obj("<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R >>")
        obj("<< /Type /Pages /Count 12 /Kids [${(0..11).joinToString(" ") { "${5 + it * 2} 0 R" }}] >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        obj("<< /Type /Outlines /First 29 0 R /Last 40 0 R /Count 14 >>")
        for (page in 0..11) {
            val stream = "BT /F1 24 Tf 45 740 Td (Chapter ${page + 1}) Tj /F1 14 Tf 0 -50 Td (Live PDF navigation check - page ${page + 1}) Tj 0 -30 Td (The contents panel stays open while the page changes.) Tj ET"
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 3 0 R >> >> /Contents ${6 + page * 2} 0 R >>")
            obj("<< /Length ${stream.toByteArray().size} >>\nstream\n$stream\nendstream")
        }
        for (page in 0..11) {
            val prev = if (page > 0) "/Prev ${28 + page} 0 R" else ""
            val next = if (page < 11) "/Next ${30 + page} 0 R" else ""
            val children = if (page == 0) "/First 41 0 R /Last 42 0 R /Count 2" else ""
            obj("<< /Title (Chapter ${page + 1}) /Parent 4 0 R $prev $next $children /Dest [${5 + page * 2} 0 R /Fit] >>")
        }
        obj("<< /Title (Detail A) /Parent 29 0 R /Next 42 0 R /Dest [5 0 R /Fit] >>")
        obj("<< /Title (Detail B) /Parent 29 0 R /Prev 41 0 R /Dest [7 0 R /Fit] >>")
        val out = ByteArrayOutputStream()
        fun write(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        write("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body -> val offset = out.size(); write("${index + 1} 0 obj\n$body\nendobj\n"); offset }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(java.util.Locale.US, it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}
