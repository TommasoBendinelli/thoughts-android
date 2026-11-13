// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.R
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewTags
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import java.time.Instant
import java.time.ZoneId
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewCardsSheetInstrumentedTest {
    @Test fun rowsSayWhereTheCardIsItsTagsAndWhenItIsDue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = Instant.parse("2026-10-06T09:00:00Z")
        val zone = ZoneId.of("Europe/Paris")
        assertEquals("Page 4 · Correct · due 9 Oct", reviewCardsSheetMetadata(context, 3, listOf(ReviewTags.CORRECT),
            Instant.parse("2026-10-09T09:00:00Z"), 1, now, zone))
        assertEquals("Page 1 · new", reviewCardsSheetMetadata(context, 0, null, null, 0, now, zone))
        assertEquals("Page 2 · Wrong, notation · due now", reviewCardsSheetMetadata(context, 1,
            listOf(ReviewTags.WRONG, "notation"), now.minusSeconds(60), 2, now, zone))
        assertEquals("Page 1 · Correct · not scheduled", reviewCardsSheetMetadata(context, 0, listOf(ReviewTags.CORRECT),
            null, 3, now, zone))
        // The current answer's group notes follow on lines of their own.
        assertEquals("Page 4 · Correct · due 9 Oct\nUnderstanding: second step vague\nOther: see p. 4",
            reviewCardsSheetMetadata(context, 3, listOf(ReviewTags.CORRECT), Instant.parse("2026-10-09T09:00:00Z"), 1,
                now, zone, groupNotes = listOf("Understanding: second step vague", "Other: see p. 4")))
    }

    @Test fun aTapHighlightsTheCardAndItsReviewButtonHandsItOnAndCloses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val items = listOf(
            ReviewCardsSheetItem("a", "How are projects discovered?", 0, "Page 1 · new", reviewable = true),
            ReviewCardsSheetItem("b", "How does BAR work?", 0, "Page 1 · Correct · due now", reviewable = true,
                regions = mapOf(0 to listOf(android.graphics.RectF(.1f, .1f, .6f, .15f)))),
        )
        val context = instrumentation.targetContext
        // The sheet host only has room once a PDF is shown.
        val pdf = File(context.cacheDir, "review-cards-sheet-${UUID.randomUUID()}.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawText("Review cards sheet", 45f, 65f, Paint().apply { textSize = 18f })
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var binding: ActivityMainBinding
            lateinit var sheet: ReviewCardsSheetController
            var opened: ReviewCardsSheetItem? = null
            val highlighted = mutableListOf<ReviewCardsSheetItem?>()
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding = ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                sheet = ReviewCardsSheetController(activity, binding, activity.lifecycleScope, {}, { items }, { highlighted += it }, { opened = it }) {}
            }
            val loaded = SystemClock.uptimeMillis() + 15_000
            var pages = 0
            while (pages == 0 && SystemClock.uptimeMillis() < loaded) {
                scenario.onActivity { pages = binding.pdfView.pageCount }
                if (pages == 0) SystemClock.sleep(50)
            }
            assertEquals(1, pages)
            scenario.onActivity { sheet.show() }
            fun list(): RecyclerView? = binding.commentSheetHost.findViewById(R.id.reviewCardsList)
            val deadline = SystemClock.uptimeMillis() + 10_000
            var shown = 0
            while (shown < items.size && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { shown = list()?.childCount ?: 0 }
                if (shown < items.size) SystemClock.sleep(50)
            }
            scenario.onActivity { activity ->
                val view = list()
                assertEquals("finishing=${activity.isFinishing} host=${binding.commentSheetHost.visibility}/" +
                    "${binding.commentSheetHost.height} list=${view != null} items=${view?.adapter?.itemCount} " +
                    "listHeight=${view?.height}", items.size, view!!.childCount)
                assertEquals("Review cards (2)",
                    binding.commentSheetHost.findViewById<android.widget.TextView>(R.id.reviewCardsTitle).text.toString())
                val row = list()!!.getChildAt(1)
                assertEquals("How does BAR work?", row.findViewById<android.widget.TextView>(R.id.reviewCardQuestion).text.toString())
                assertEquals(View.GONE, row.findViewById<View>(R.id.reviewCardReview).visibility)
                row.performClick()
                // Like the desktop sidebar, a tap only selects the card; the sheet stays open.
                assertNull(opened)
                assertEquals(View.VISIBLE, binding.commentSheetHost.visibility)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(listOf<ReviewCardsSheetItem?>(items[1]), highlighted)
                val row = list()!!.getChildAt(1)
                assertEquals(View.VISIBLE, row.findViewById<View>(R.id.reviewCardReview).visibility)
                assertEquals(View.GONE, list()!!.getChildAt(0).findViewById<View>(R.id.reviewCardReview).visibility)
                row.findViewById<View>(R.id.reviewCardReview).performClick()
                assertEquals(items[1], opened)
                // Closing the sheet clears the highlight.
                assertEquals(listOf(items[1], null), highlighted)
                assertEquals(View.GONE, binding.commentSheetHost.visibility)
                assertFalse(sheet.dismiss())
            }
        } } finally { pdf.delete() }
    }
}
