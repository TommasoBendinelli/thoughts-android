// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.ui.reader

import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gitlab.mudlej.MjPdfReader.data.Preferences
import com.gitlab.mudlej.MjPdfReader.data.render.PhoneRenderQueue
import com.gitlab.mudlej.MjPdfReader.data.review.ReviewStore
import com.gitlab.mudlej.MjPdfReader.data.review.basicPdfSha256
import com.gitlab.mudlej.MjPdfReader.databinding.ActivityMainBinding
import com.gitlab.mudlej.MjPdfReader.testing.TestRunIdentity
import com.gitlab.mudlej.MjPdfReader.ui.home.HomeActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReviewAdvanceUiInstrumentedTest {
    @Test fun ratingDisplaysTheNextCardWithoutReloadingPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".sandbox"))
        Preferences(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)).apply {
            setFirstInstall(false); setShowFeaturesDialog(false)
        }
        val stem = "review-advance-${TestRunIdentity.id}"
        val pdf = File(PhoneRenderQueue.defaultCanonicalPdfDirectory(), "$stem.pdf")
        check(pdf.canonicalPath.startsWith(context.filesDir.canonicalPath))
        pdf.parentFile!!.mkdirs()
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(400, 600, 1).create())
            page.canvas.drawText("One answer. Another answer.", 20f, 100f, Paint().apply { textSize=18f })
            doc.finishPage(page)
            pdf.outputStream().use(doc::writeTo)
        } finally { doc.close() }
        val store = ReviewStore(context)
        val hash = basicPdfSha256(pdf)
        com.gitlab.mudlej.MjPdfReader.data.sync.DocumentIdentityStore().registerImport(pdf.name, hash, pdf)
        val first = store.createManualCard(stem, hash, 1, RectF(.05f,.1f,.9f,.3f), "First fixture question", sourceAvailable=false).getOrThrow()
        repeat(4) { index ->
            val top = .35f + index * .14f
            store.createManualCard(stem, hash, 1, RectF(.05f,top,.9f,top+.1f), "Next fixture question $index", sourceAvailable=false).getOrThrow()
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW; setDataAndType(Uri.fromFile(pdf), "application/pdf")
            putExtra(HomeActivity.EXTRA_REVIEW_CARD_ID, first.id)
            putExtra(HomeActivity.EXTRA_REVIEW_PDF_STEM, stem)
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            fun waitFor(timeout: Long, predicate: (ActivityMainBinding) -> Boolean): Boolean {
                val end = SystemClock.elapsedRealtime()+timeout
                while(SystemClock.elapsedRealtime()<end) {
                    var done=false
                    scenario.onActivity { activity -> done=predicate(ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))) }
                    if(done) return true
                    SystemClock.sleep(10)
                }
                return false
            }
            assertTrue("Initial card displayed", waitFor(20000) { it.reviewQuestion.text.toString()==first.question && it.reviewCard.visibility==View.VISIBLE })
            val timings = mutableListOf<Long>()
            repeat(4) {
                var previous = ""
                scenario.onActivity { activity ->
                    val binding=ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                    previous=binding.reviewQuestion.text.toString()
                    binding.reviewSetKnowledgeButton.performClick()
                }
                val started=SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    val binding=ActivityMainBinding.bind(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                    // A new card starts with no tag, and Next records the answer without one.
                    assertEquals(context.getString(com.gitlab.mudlej.MjPdfReader.R.string.review_tags_none),
                        binding.reviewTagButton.text.toString().substringAfter('\n'))
                    val next = binding.reviewNextButton
                    assertTrue(next.isEnabled)
                    next.performClick()
                }
                assertTrue("Next card displayed", waitFor(5000) { binding ->
                    binding.reviewQuestion.text.toString()!=previous && binding.reviewQuestion.text.startsWith("Next fixture question") &&
                        binding.reviewCard.visibility==View.VISIBLE && binding.reviewRevealButton.isEnabled
                })
                timings += SystemClock.elapsedRealtime()-started
            }
            println("REVIEW_ADVANCE_UI tap_to_next_card_ms=$timings")
            assertTrue("Median next-card display within one second", timings.sorted()[2]<1000)
            assertEquals(1, store.browserCards().single { it.card.id==first.id }.reviewCount)
        }
    }
}
